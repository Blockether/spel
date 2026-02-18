(ns com.blockether.spel.codegen
  "Transforms Playwright JSONL recordings into idiomatic Clojure test code.

   Reads JSONL produced by `playwright codegen --target=jsonl` and emits
   Clojure code using the com.blockether.spel API.

   Two usage modes:

   A) Library:

(set! *warn-on-reflection* true)

      (require '[com.blockether.spel.codegen :as codegen])
      (codegen/jsonl->clojure \"recording.jsonl\")
      (codegen/jsonl-str->clojure jsonl-string {:format :script})

   B) CLI:
      clojure -M -m com.blockether.spel.codegen recording.jsonl
      clojure -M -m com.blockether.spel.codegen --format=script recording.jsonl
      cat recording.jsonl | clojure -M -m com.blockether.spel.codegen

   Workflow:
      clojure -M -m com.blockether.spel.cli codegen --target=jsonl -o recording.jsonl https://example.com
      clojure -M -m com.blockether.spel.codegen recording.jsonl > test/my_test.clj

   Any unrecognized action, unsupported signal, or unimplemented feature
   causes an IMMEDIATE hard error with details about what failed."
  (:refer-clojure :exclude [format])
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- parse-json
  [^String s]
  (json/read-str s :key-fn keyword))

;; =============================================================================
;; Error Handling - HARD ERRORS
;; =============================================================================

(def ^:dynamic *exit-on-error*
  "When true (default for CLI), errors call System/exit 1.
   Set to false for library use (throws ex-info instead)."
  true)

(defn- die!
  "Hard error. Prints details and kills the process or throws.
   There is no recovery. Fix the issue or implement support."
  [msg action]
  (let [full-msg (str msg "\n\n"
                   "Action data:\n"
                   (pr-str action) "\n\n"
                   "This action is NOT implemented in spel codegen.\n"
                   "Either implement support in com.blockether.spel.codegen\n"
                   "or manually translate this action.")]
    (if *exit-on-error*
      (do
        (binding [*out* *err*]
          (println "")
          (println (apply str (repeat 70 "=")))
          (println "CODEGEN FATAL ERROR")
          (println (apply str (repeat 70 "=")))
          (println "")
          (println full-msg)
          (println (apply str (repeat 70 "=")))
          (println ""))
        (System/exit 1))
      (throw (ex-info (str "Codegen error: " msg)
               {:codegen/error msg
                :codegen/action action})))))

;; =============================================================================
;; Signal & Frame Handling
;; =============================================================================

(defn- has-signal?
  "Returns the first signal with the given name, or nil."
  [action sig-name]
  (first (filter #(= sig-name (str (:name %))) (:signals action))))

(defn- validate-unknown-signals!
  "Dies on unknown signal types. Known: popup, download, dialog."
  [action]
  (when-let [signals (seq (:signals action))]
    (doseq [sig signals]
      (let [sig-name (str (:name sig))]
        (when-not (#{"popup" "download" "dialog"} sig-name)
          (die! (str "Unknown signal '" sig-name "' is not implemented.") action))))))

(defn- wrap-dialog-signal
  "Wraps action code with dialog handler if action has dialog signal.
   Dialog handlers are registered BEFORE the action (per Playwright pattern)."
  [pg action-code action]
  (if (has-signal? action "dialog")
    (str "(page/on-dialog " pg " (fn [dialog] (.dismiss dialog)))\n"
      action-code)
    action-code))

(defn- wrap-popup-signal
  "Wraps action code with waitForPopup if action has popup signal.
   Uses Java interop: (.waitForPopup ^Page pg (reify Runnable (run [_] ...)))"
  [pg action-code action]
  (if-let [_sig (has-signal? action "popup")]
    (str "(let [popup-pg (.waitForPopup ^Page " pg
      " (reify Runnable (run [_] " action-code ")))]\n"
      "  ;; popup-pg is now available for further actions"
      ")")
    action-code))

(defn- wrap-download-signal
  "Wraps action code with waitForDownload if action has download signal.
   Uses Java interop: (.waitForDownload ^Page pg (reify Runnable (run [_] ...)))"
  [pg action-code action]
  (if-let [_sig (has-signal? action "download")]
    (str "(let [download (.waitForDownload ^Page " pg
      " (reify Runnable (run [_] " action-code ")))]\n"
      "  ;; download is now available - (.path download), (.suggestedFilename download)"
      ")")
    action-code))

(defn- wrap-signals
  "Applies signal wrapping in correct order: dialog BEFORE action, popup/download AROUND action."
  [pg action-code action]
  (let [code (wrap-popup-signal pg action-code action)
        code (wrap-download-signal pg code action)
        code (wrap-dialog-signal pg code action)]
    code))

(defn- frame-locator-code
  "Generates code to navigate into frames via framePath.
   Each entry in framePath is a selector producing a .contentFrame() chain.

   Example framePath: [\"iframe[name='main']\", \"iframe.nested\"]
   Output: (let [fl0 (.contentFrame (page/locator pg \"iframe[name='main']\"))
                 fl1 (.contentFrame (.locator fl0 \"iframe.nested\"))]
             fl1)

   Returns [root-sym, let-bindings-code] where root-sym is the final frame symbol."
  [pg-sym frame-path]
  (if (empty? frame-path)
    [pg-sym nil]
    (let [steps (map-indexed
                  (fn [i selector]
                    (let [fl-sym (str "fl" i)
                          parent (if (zero? i) pg-sym (str "fl" (dec i)))]
                      [fl-sym
                       (if (zero? i)
                         (clojure.core/format "(.contentFrame (page/locator %s %s))" parent (pr-str selector))
                         (clojure.core/format "(.contentFrame (.locator %s %s))" parent (pr-str selector)))]))
                  frame-path)
          final-sym (first (last steps))
          bindings (str/join "\n              "
                     (map (fn [[sym code]] (str sym " " code)) steps))]
      [final-sym (str "(let [" bindings "]")])))

;; =============================================================================
;; Locator Code Generation
;; =============================================================================

(def ^:private role-map
  "Maps lowercase role strings to AriaRole enum names."
  {"alert" "ALERT" "alertdialog" "ALERTDIALOG" "application" "APPLICATION"
   "article" "ARTICLE" "banner" "BANNER" "blockquote" "BLOCKQUOTE"
   "button" "BUTTON" "caption" "CAPTION" "cell" "CELL"
   "checkbox" "CHECKBOX" "code" "CODE" "columnheader" "COLUMNHEADER"
   "combobox" "COMBOBOX" "complementary" "COMPLEMENTARY"
   "contentinfo" "CONTENTINFO" "definition" "DEFINITION"
   "deletion" "DELETION" "dialog" "DIALOG" "directory" "DIRECTORY"
   "document" "DOCUMENT" "emphasis" "EMPHASIS" "feed" "FEED"
   "figure" "FIGURE" "form" "FORM" "generic" "GENERIC"
   "grid" "GRID" "gridcell" "GRIDCELL" "group" "GROUP"
   "heading" "HEADING" "img" "IMG" "insertion" "INSERTION"
   "link" "LINK" "list" "LIST" "listbox" "LISTBOX"
   "listitem" "LISTITEM" "log" "LOG" "main" "MAIN"
   "marquee" "MARQUEE" "math" "MATH" "meter" "METER"
   "menu" "MENU" "menubar" "MENUBAR" "menuitem" "MENUITEM"
   "menuitemcheckbox" "MENUITEMCHECKBOX" "menuitemradio" "MENUITEMRADIO"
   "navigation" "NAVIGATION" "none" "NONE" "note" "NOTE"
   "option" "OPTION" "paragraph" "PARAGRAPH" "presentation" "PRESENTATION"
   "progressbar" "PROGRESSBAR" "radio" "RADIO" "radiogroup" "RADIOGROUP"
   "region" "REGION" "row" "ROW" "rowgroup" "ROWGROUP"
   "rowheader" "ROWHEADER" "scrollbar" "SCROLLBAR" "search" "SEARCH"
   "searchbox" "SEARCHBOX" "separator" "SEPARATOR" "slider" "SLIDER"
   "spinbutton" "SPINBUTTON" "status" "STATUS" "strong" "STRONG"
   "subscript" "SUBSCRIPT" "superscript" "SUPERSCRIPT" "switch" "SWITCH"
   "tab" "TAB" "table" "TABLE" "tablist" "TABLIST"
   "tabpanel" "TABPANEL" "term" "TERM" "textbox" "TEXTBOX"
   "time" "TIME" "timer" "TIMER" "toolbar" "TOOLBAR"
   "tooltip" "TOOLTIP" "tree" "TREE" "treegrid" "TREEGRID"
   "treeitem" "TREEITEM"})

(defn- role->enum
  "Converts a role string to AriaRole/ENUM code. Dies if unknown."
  [role-str action]
  (let [normalized (str/lower-case (str role-str))]
    (if-let [enum-name (role-map normalized)]
      (str "AriaRole/" enum-name)
      (die! (clojure.core/format "Unknown ARIA role '%s'. Not in AriaRole enum." role-str) action))))

(defn- parse-internal-selector
  "Attempts to parse Playwright's internal selector format.
   Returns Clojure locator code string or nil if not recognized."
  [pg-sym selector]
  (cond
    ;; internal:role=heading[name=\"Example Domain\"i]
    (str/starts-with? selector "internal:role=")
    (let [rest-str (subs selector (count "internal:role="))
          [role-part attrs] (str/split rest-str #"\[" 2)
          role-str (str/replace role-part #"[^a-zA-Z]" "")
          name-val (when attrs (second (re-find #"name=\"([^\"]+)\"" attrs)))]
      (if name-val
        (clojure.core/format "(locator/loc-filter (page/get-by-role %s %s) {:has-text %s})"
          pg-sym (role->enum role-str nil) (pr-str name-val))
        (clojure.core/format "(page/get-by-role %s %s)" pg-sym (role->enum role-str nil))))

    ;; internal:text=\"value\"i
    (str/starts-with? selector "internal:text=")
    (when-let [val (second (re-find #"internal:text=\"([^\"]+)\"" selector))]
      (clojure.core/format "(page/get-by-text %s %s)" pg-sym (pr-str val)))

    ;; internal:label=\"value\"i
    (str/starts-with? selector "internal:label=")
    (when-let [val (second (re-find #"internal:label=\"([^\"]+)\"" selector))]
      (clojure.core/format "(page/get-by-label %s %s)" pg-sym (pr-str val)))

    ;; internal:testid=\"value\"
    (str/starts-with? selector "internal:testid=")
    (when-let [val (second (re-find #"internal:testid=\"([^\"]+)\"" selector))]
      (clojure.core/format "(page/get-by-test-id %s %s)" pg-sym (pr-str val)))

    ;; internal:attr=[placeholder=\"value\"i]
    (str/starts-with? selector "internal:attr=")
    (when-let [val (second (re-find #"placeholder=\"([^\"]+)\"" selector))]
      (clojure.core/format "(page/get-by-placeholder %s %s)" pg-sym (pr-str val)))))

(defn- locator-from-map
  "Generates locator code from a structured locator map.
   Dies if the map format is not recognized."
  [pg-sym locator action]
  (cond
    (:role locator)
    (let [role-code (role->enum (:role locator) action)]
      (if (:name locator)
        (clojure.core/format "(locator/loc-filter (page/get-by-role %s %s) {:has-text %s})"
          pg-sym role-code (pr-str (:name locator)))
        (clojure.core/format "(page/get-by-role %s %s)" pg-sym role-code)))

    ;; Playwright 1.58+ JSONL format: {:kind "role", :body "heading", :options {:attrs [...]}}
    (and (:kind locator) (:body locator))
    (case (str (:kind locator))
      "role" (let [role-code (role->enum (:body locator) action)
                   name-val (some (fn [attr]
                                    (when (= "name" (str (:name attr)))
                                      (:value attr)))
                              (get-in locator [:options :attrs]))]
               (if name-val
                 (clojure.core/format "(locator/loc-filter (page/get-by-role %s %s) {:has-text %s})"
                   pg-sym role-code (pr-str name-val))
                 (clojure.core/format "(page/get-by-role %s %s)" pg-sym role-code)))
      ;; Unknown kind
      (die! (clojure.core/format "Unrecognized locator kind '%s'." (:kind locator)) action))

    (:text locator)
    (clojure.core/format "(page/get-by-text %s %s)" pg-sym (pr-str (:text locator)))

    (:label locator)
    (clojure.core/format "(page/get-by-label %s %s)" pg-sym (pr-str (:label locator)))

    (:placeholder locator)
    (clojure.core/format "(page/get-by-placeholder %s %s)" pg-sym (pr-str (:placeholder locator)))

    (:testId locator)
    (clojure.core/format "(page/get-by-test-id %s %s)" pg-sym (pr-str (:testId locator)))

    (:altText locator)
    (clojure.core/format "(page/get-by-alt-text %s %s)" pg-sym (pr-str (:altText locator)))

    (:title locator)
    (clojure.core/format "(page/get-by-title %s %s)" pg-sym (pr-str (:title locator)))

    (:css locator)
    (clojure.core/format "(page/locator %s %s)" pg-sym (pr-str (:css locator)))

    :else
    (die! (clojure.core/format "Unrecognized locator map format: %s" (pr-str locator)) action)))

(defn- locator->code
  "Generates a Clojure locator expression from JSONL action data.
   Tries structured locator field first, then raw selector, then dies."
  [pg-sym {:keys [selector locator] :as action}]
  (cond
    ;; Structured locator map from JSONL
    (map? locator)
    (locator-from-map pg-sym locator action)

    ;; Locator is a string - use as CSS selector
    (string? locator)
    (clojure.core/format "(page/locator %s %s)" pg-sym (pr-str locator))

    ;; Locator is an array (chained locators)
    (vector? locator)
    (die! (clojure.core/format "Chained locator arrays not implemented.\nLocator: %s" (pr-str locator)) action)

    ;; No locator field - try parsing internal selector
    (string? selector)
    (or (parse-internal-selector pg-sym selector)
        ;; Fallback: use selector as CSS
      (clojure.core/format "(page/locator %s %s)" pg-sym (pr-str selector)))

    :else
    (die! "No locator or selector found for action." action)))

;; =============================================================================
;; Modifier Handling
;; =============================================================================

(defn- modifiers->keys
  "Converts modifier bitmask to key name strings."
  [modifiers]
  (when (and modifiers (pos? modifiers))
    (cond-> []
      (pos? (bit-and modifiers 1)) (conj "Alt")
      (pos? (bit-and modifiers 2)) (conj "ControlOrMeta")
      (pos? (bit-and modifiers 4)) (conj "Meta")
      (pos? (bit-and modifiers 8)) (conj "Shift"))))

;; =============================================================================
;; Action -> Clojure Code
;; =============================================================================

(defn- page-sym
  "Returns the Clojure symbol name for a page alias."
  [action]
  (let [alias (:pageAlias action "page")]
    (if (= alias "page") "pg" (str "pg-" (str/replace alias #"[^a-zA-Z0-9]" "-")))))

(defn- action->raw-code
  "Generates raw Clojure code for an action (without signal/frame wrapping).
   `root-sym` is the page or frame variable to use for locators."
  [root-sym action]
  (let [loc #(locator->code root-sym action)
        action-name (:name action)
        pg (page-sym action)]
    (case action-name
      ;; ----- Page lifecycle -----
      "openPage"
      (let [open-comment (clojure.core/format ";; New page: %s" pg)]
        (if (and (:url action)
              (not (#{"about:blank" "chrome://newtab/"} (:url action))))
          (str open-comment "\n"
            (clojure.core/format "          (page/navigate %s %s)" pg (pr-str (:url action))))
          open-comment))

      "closePage"
      (clojure.core/format "(core/close-page! %s)" pg)

      ;; ----- Navigation -----
      "navigate"
      (clojure.core/format "(page/navigate %s %s)" pg (pr-str (:url action)))

      ;; ----- Click -----
      "click"
      (let [cc (or (:clickCount action) 1)]
        (cond
          (= cc 2)
          (clojure.core/format "(locator/dblclick %s)" (loc))

          (> cc 2)
          (clojure.core/format "(locator/click %s {:click-count %d})" (loc) cc)

          :else
          (let [mods (modifiers->keys (:modifiers action))
                has-button? (and (:button action) (not= "left" (:button action)))
                has-pos? (:position action)
                has-opts? (or (seq mods) has-button? has-pos?)]
            (if has-opts?
              (let [opts-parts (cond-> []
                                 has-button?
                                 (conj (clojure.core/format ":button %s" (pr-str (:button action))))
                                 (seq mods)
                                 (conj (clojure.core/format ":modifiers [%s]" (str/join " " (map pr-str mods))))
                                 has-pos?
                                 (conj (clojure.core/format ":position {:x %s :y %s}"
                                         (:x (:position action)) (:y (:position action)))))]
                (clojure.core/format "(locator/click %s {%s})" (loc) (str/join " " opts-parts)))
              (clojure.core/format "(locator/click %s)" (loc))))))

      ;; ----- Fill -----
      "fill"
      (clojure.core/format "(locator/fill %s %s)" (loc) (pr-str (:text action)))

      ;; ----- Press -----
      "press"
      (let [mods (modifiers->keys (:modifiers action))
            key-combo (if (seq mods)
                        (str/join "+" (conj (vec mods) (:key action)))
                        (:key action))]
        (clojure.core/format "(locator/press %s %s)" (loc) (pr-str key-combo)))

      ;; ----- Hover -----
      "hover"
      (if (:position action)
        (clojure.core/format "(locator/hover %s {:position {:x %s :y %s}})"
          (loc) (:x (:position action)) (:y (:position action)))
        (clojure.core/format "(locator/hover %s)" (loc)))

      ;; ----- Check/Uncheck -----
      "check"   (clojure.core/format "(locator/check %s)" (loc))
      "uncheck" (clojure.core/format "(locator/uncheck %s)" (loc))

      ;; ----- Select -----
      "select"
      (let [options (:options action)]
        (if (and (vector? options) (= 1 (count options)))
          (clojure.core/format "(locator/select-option %s %s)" (loc) (pr-str (first options)))
          (clojure.core/format "(locator/select-option %s %s)" (loc) (pr-str options))))

      ;; ----- Set Input Files -----
      "setInputFiles"
      (let [files (:files action)]
        (cond
          (and (vector? files) (> (count files) 1))
          (clojure.core/format "(locator/set-input-files! %s %s)" (loc) (pr-str files))

          (vector? files)
          (clojure.core/format "(locator/set-input-files! %s %s)" (loc) (pr-str (first files)))

          (string? files)
          (clojure.core/format "(locator/set-input-files! %s %s)" (loc) (pr-str files))

          :else
          (die! (clojure.core/format "setInputFiles: unexpected files format: %s" (pr-str files)) action)))

      ;; ----- Assertions -----
      "assertText"
      (if (:substring action)
        (clojure.core/format "(assert/contains-text %s %s)" (loc) (pr-str (:text action)))
        (clojure.core/format "(assert/has-text %s %s)" (loc) (pr-str (:text action))))

      "assertChecked"
      (if (:checked action)
        (clojure.core/format "(assert/is-checked %s)" (loc))
        (clojure.core/format "(assert/is-checked (assert/loc-not %s))" (loc)))

      "assertVisible"
      (clojure.core/format "(assert/is-visible %s)" (loc))

      "assertValue"
      (if (str/blank? (str (:value action)))
        (clojure.core/format "(assert/is-empty %s)" (loc))
        (clojure.core/format "(assert/has-value %s %s)" (loc) (pr-str (:value action))))

      "assertSnapshot"
      (clojure.core/format "(assert/matches-aria-snapshot (assert/assert-that %s) %s)"
        (loc) (pr-str (str (:snapshot action))))

      ;; ----- Unknown = HARD ERROR -----
      (die! (clojure.core/format "Unknown action '%s' is not implemented." action-name) action))))

(defn- action->code
  "Transforms a single JSONL action into a Clojure code string.
   Handles frame navigation, signals, and action code generation.
   Dies immediately on any unrecognized or unimplemented action."
  [action]
  (validate-unknown-signals! action)
  (let [pg (page-sym action)
        frame-path (:framePath action)
        [root-sym frame-let] (if (seq frame-path)
                               (frame-locator-code pg frame-path)
                               [pg nil])
        raw-code (action->raw-code root-sym action)
        with-signals (wrap-signals pg raw-code action)]
    (if frame-let
      (str frame-let "\n  " with-signals ")")
      with-signals)))

;; =============================================================================
;; Output Generation
;; =============================================================================

(defn- generate-test-header
  "Generates a Lazytest test file header."
  [opts]
  (let [browser-name (or (:browserName opts) "chromium")
        launch-fn (case browser-name
                    "chromium" "core/launch-chromium"
                    "firefox"  "core/launch-firefox"
                    "webkit"   "core/launch-webkit"
                    "core/launch-chromium")
        headless? (not (false? (get-in opts [:launchOptions :headless])))]
    (str
      ";; =============================================================================\n"
      ";; Auto-generated by spel codegen\n"
      ";; Source: Playwright JSONL recording\n"
      ";; =============================================================================\n"
      "\n"
      "(ns my-app.generated-test\n"
      "  \"Auto-generated Playwright test.\"\n"
      "  (:require\n"
      "   [com.blockether.spel.assertions :as assert]\n"
      "   [com.blockether.spel.core :as core]\n"
      "   [com.blockether.spel.locator :as locator]\n"
      "   [com.blockether.spel.page :as page]\n"
      "   [lazytest.core :refer [defdescribe it expect]])\n"
      "  (:import\n"
      "   [com.microsoft.playwright Page]\n"
      "   [com.microsoft.playwright.options AriaRole]))\n"
      "\n"
      "(defdescribe generated-test\n"
      "  (it \"recorded test\"\n"
      "    (core/with-playwright [pw]\n"
      (clojure.core/format
        "      (core/with-browser [browser (%s pw {:headless %s})]\n" launch-fn headless?)
      "        (core/with-context [ctx (core/new-context browser)]\n"
      "          (core/with-page [pg (core/new-page-from-context ctx)]\n")))

(defn- generate-test-footer
  "Generates a Lazytest test file footer."
  []
  (str
    ")))))\n"))

(defn- generate-script-header
  "Generates a standalone script header."
  [opts]
  (let [browser-name (or (:browserName opts) "chromium")
        launch-fn (case browser-name
                    "chromium" "core/launch-chromium"
                    "firefox"  "core/launch-firefox"
                    "webkit"   "core/launch-webkit"
                    "core/launch-chromium")
        headless? (not (false? (get-in opts [:launchOptions :headless])))]
    (str
      ";; Auto-generated by spel codegen\n"
      "\n"
      "(require '[com.blockether.spel.assertions :as assert])\n"
      "(require '[com.blockether.spel.core :as core])\n"
      "(require '[com.blockether.spel.locator :as locator])\n"
      "(require '[com.blockether.spel.page :as page])\n"
      "(import '[com.microsoft.playwright Page])\n"
      "(import '[com.microsoft.playwright.options AriaRole])\n"
      "\n"
      "(core/with-playwright [pw]\n"
      (clojure.core/format
        "  (core/with-browser [browser (%s pw {:headless %s})]\n" launch-fn headless?)
      "    (core/with-context [ctx (core/new-context browser)]\n"
      "      (core/with-page [pg (core/new-page-from-context ctx)]\n")))

(defn- generate-script-footer
  "Generates a standalone script footer."
  []
  (str
    "))))\n"))

;; =============================================================================
;; Public API
;; =============================================================================

(defn jsonl-str->clojure
  "Transforms a JSONL string into Clojure source code.

   Params:
   `jsonl-str` - String. JSONL content from `playwright codegen --target=jsonl`.
   `opts`      - Optional map:
     :format - :test (default), :script, or :body

   Returns:
   String of Clojure source code.

   Throws:
   Hard error (System/exit or ex-info) on any unsupported action."
  ([jsonl-str] (jsonl-str->clojure jsonl-str {}))
  ([jsonl-str opts]
   (let [lines (remove str/blank? (str/split-lines jsonl-str))
         _ (when (empty? lines) (die! "Empty JSONL input. No actions recorded." {}))
         header-data (parse-json (first lines))
         actions (mapv parse-json (rest lines))
         _ (when (empty? actions) (die! "JSONL has header but no actions. Nothing was recorded." header-data))
         fmt (or (:format opts) :test)
         indent (case fmt :test "          " :script "      " :body "")
         action-lines (mapv (fn [action]
                              (let [code (action->code action)]
                                (when code
                                  (str/join "\n"
                                    (map #(str indent %)
                                      (str/split-lines code))))))
                        actions)
         body (str/join "\n" (remove nil? action-lines))]
     (case fmt
       :test   (str (generate-test-header header-data) body (generate-test-footer))
       :script (str (generate-script-header header-data) body (generate-script-footer))
       :body   body))))

(defn jsonl->clojure
  "Reads a JSONL file and returns Clojure test code as a string.

   Params:
   `path` - String. Path to JSONL file.
   `opts` - Optional map (same as jsonl-str->clojure)."
  ([path] (jsonl->clojure path {}))
  ([path opts]
   (jsonl-str->clojure (slurp (io/file path)) opts)))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn -main
  "CLI entry point. Transforms JSONL recording to Clojure code.

   Usage:
     clojure -M -m com.blockether.spel.codegen recording.jsonl
     clojure -M -m com.blockether.spel.codegen --format=script recording.jsonl
     clojure -M -m com.blockether.spel.codegen --format=body recording.jsonl
     clojure -M -m com.blockether.spel.codegen --output=test.clj recording.jsonl
     cat recording.jsonl | clojure -M -m com.blockether.spel.codegen"
  [& args]
  (let [args (vec args)]
    (when (or (empty? args) (some #{"--help" "-h"} args))
      (println "spel codegen — JSONL to Clojure transformer")
      (println "")
      (println "Usage: spel codegen [OPTIONS] [FILE]")
      (println "")
      (println "Options:")
      (println "  --format=test     Lazytest test file (default)")
      (println "  --format=script   Standalone require + with-playwright script")
      (println "  --format=body     Only action lines (for pasting)")
      (println "  --output=FILE     Write to file instead of stdout")
      (println "  -h, --help        Show this help")
      (println "")
      (println "If no FILE argument, reads JSONL from stdin.")
      (println "")
      (println "Workflow:")
      (println "  # 1. Record with Playwright codegen (JSONL target)")
      (println "  npx playwright codegen --target=jsonl -o recording.jsonl https://example.com")
      (println "")
      (println "  # 2. Transform to Clojure")
      (println "  spel codegen recording.jsonl")
      (println "  spel codegen --format=script --output=my_test.clj recording.jsonl")
      (System/exit 0))
    (let [fmt-arg (first (filter #(str/starts-with? % "--format=") args))
          out-arg (first (filter #(str/starts-with? % "--output=") args))
          fmt (if fmt-arg
                (keyword (subs fmt-arg (count "--format=")))
                :test)
          out-path (when out-arg (subs out-arg (count "--output=")))
          file-args (remove #(str/starts-with? % "--") args)
          input (if (seq file-args)
                  (slurp (io/file (first file-args)))
                  (slurp *in*))
          result (jsonl-str->clojure input {:format fmt})]
      (if out-path
        (do
          (spit out-path result)
          (binding [*out* *err*]
            (println (str "Written to " out-path))))
        (println result)))))
