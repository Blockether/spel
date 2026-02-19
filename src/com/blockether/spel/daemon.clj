(ns com.blockether.spel.daemon
  "Background daemon that keeps a Playwright browser alive between CLI calls.

   Listens on a Unix domain socket for JSON commands, executes them against
   the browser, and returns JSON responses. Each command is one JSON line;
   each response is one JSON line.

   Usage:
     (start-daemon! {:session \"default\" :headless true})   ;; blocks
     (daemon-running? \"default\")                           ;; check
     (stop-daemon!)                                         ;; cleanup"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.annotate :as annotate]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.input :as input]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.network :as network]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.options :as options]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.util :as util])
  (:import
   [com.microsoft.playwright BrowserContext ConsoleMessage Dialog Frame Keyboard Mouse Page Request Response]
   [com.microsoft.playwright.options AriaRole Cookie Geolocation]
   [java.io BufferedReader File InputStreamReader OutputStreamWriter]
   [java.net StandardProtocolFamily UnixDomainSocketAddress]
   [java.nio.channels Channels ServerSocketChannel SocketChannel]
   [java.nio.file Files Path]
   [java.util Base64]))

(declare stop-daemon!)
(declare save-inflight-trace!)

(defn- warn
  "Logs a warning to stderr. Used in cleanup paths where we must continue
   despite errors but never silently swallow them."
  [^String context ^Exception e]
  (binding [*out* *err*]
    (println (str "warn: " context ": " (.getMessage e)))))

;; =============================================================================
;; Paths
;; =============================================================================

(defn socket-path
  "Returns the Unix socket path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".sock")
    (into-array String [])))

(defn pid-file-path
  "Returns the PID file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".pid")
    (into-array String [])))

(defn log-file-path
  "Returns the log file path for a session."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".log")
    (into-array String [])))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private !state
  (atom {:pw       nil
         :browser  nil
         :context  nil
         :page     nil
         :refs     {}
         :counter  0
         :headless true
         :session  "default"
         :tracing? false}))

(defonce ^:private !server (atom nil))
(defonce ^:private !console-messages (atom []))
(defonce ^:private !page-errors (atom []))
(defonce ^:private !dialog-handler (atom nil))
(defonce ^:private !tracked-requests (atom []))
(def ^:private max-tracked-requests 500)
(defonce ^:private !routes (atom {}))

(defn- track-response!
  "Appends a response summary to the tracked-requests ring buffer, capped at
   `max-tracked-requests` most-recent entries."
  [^Response resp]
  (let [^Request req (.request resp)
        entry {:url    (.url req)
               :method (.method req)
               :status (.status resp)
               :resource-type (.resourceType req)}]
    (swap! !tracked-requests
      (fn [reqs]
        (let [updated (conj reqs entry)
              n (long (count updated))]
          (if (> n (long max-tracked-requests))
            (subvec updated (- n (long max-tracked-requests)))
            updated))))))

(defn- pg ^Page [] (:page @!state))
(defn- ctx ^BrowserContext [] (:context @!state))

(defn- str->aria-role
  "Converts a lowercase role string to AriaRole enum.
   Throws ex-info with a helpful message if the role name is invalid."
  ^AriaRole [^String s]
  (try
    (AriaRole/valueOf (.toUpperCase s))
    (catch IllegalArgumentException _
      (throw (ex-info (str "Unknown ARIA role: " s
                        ". Valid roles include: alert, button, checkbox, combobox, dialog, grid, "
                        "heading, img, link, list, listbox, listitem, menu, menuitem, navigation, "
                        "option, paragraph, progressbar, radio, region, row, search, searchbox, "
                        "separator, slider, spinbutton, switch, tab, table, tabpanel, textbox, "
                        "toolbar, tooltip, tree, treeitem")
               {})))))

(defn- filter-snapshot-tree
  "Applies snapshot filters to the tree string."
  [tree {:strs [interactive cursor compact depth]}]
  (if (or (nil? tree) (str/blank? tree))
    tree
    (let [lines (str/split-lines tree)
          lines (if interactive
                  (if cursor
                    ;; -C cursor mode: include interactive elements + cursor-related generic elements
                    (filter #(or (str/includes? % "[@")
                               (re-find #"role=\"(textbox|combobox|searchbox|spinbutton|slider)\"" %)
                               (re-find #"\[focused\]" %))
                      lines)
                    (filter #(str/includes? % "[@") lines))
                  lines)
          lines (if compact
                  (remove #(re-matches #"\s*- \w+\s*" %) lines)
                  lines)
          lines (if depth
                  (let [max-indent (* 2 (long depth))]
                    (filter (fn [line]
                              (<= (count (take-while #{\ } line)) max-indent))
                      lines))
                  lines)]
      (str/join "\n" lines))))

(defn- session-state-path
  "Returns the state file path for a named session."
  ^String [^String session-name]
  (str (System/getProperty "java.io.tmpdir")
    File/separator
    "spel-session-" session-name ".json"))

(defn- auto-load-session-state!
  "If --session-name flag is set and a saved state file exists, loads it into the context.
   Called after browser/context creation to restore cookies/storage from a previous session."
  []
  (let [flags (get @!state :launch-flags {})
        sn    (get flags "session-name")]
    (when sn
      (let [state-path (session-state-path sn)]
        (when (Files/exists (Path/of state-path (into-array String []))
                (into-array java.nio.file.LinkOption []))
          ;; Save in-flight trace before destroying context
          (save-inflight-trace!)
           ;; Close current page and context, re-create with saved state
          (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
          (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
          (let [new-ctx (core/new-context (:browser @!state) {:storage-state state-path})
                new-pg  (core/new-page-from-context new-ctx)]
            (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
            ;; Re-register console, error, and request listeners on new page
            (page/on-console new-pg (fn [msg]
                                      (swap! !console-messages conj
                                        {:type (.type ^ConsoleMessage msg)
                                         :text (.text ^ConsoleMessage msg)})))
            (page/on-page-error new-pg (fn [error]
                                         (swap! !page-errors conj
                                           {:message (str error)})))
            (page/on-response new-pg track-response!)))))))

(defn- auto-save-session-state!
  "If --session-name flag is set, saves the current browser context state (cookies/storage)
   to a file before closing. Called on close to persist session state."
  []
  (let [flags (get @!state :launch-flags {})
        sn    (get flags "session-name")]
    (when (and sn (:context @!state))
      (try
        (let [state-path (session-state-path sn)]
          (.storageState ^BrowserContext (ctx)
            (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
              (.setPath (Path/of state-path (into-array String []))))))
        (catch Exception _
          ;; Best-effort — don't fail close on state save error
          nil)))))

(defn- ensure-browser!
  "Lazily starts Chromium on first command. Uses launch-flags from !state if present.
   If --profile is set, uses launchPersistentContext (real Chrome profile).
   If --session-name is set and a saved state file exists, auto-loads it."
  []
  (when-not (:browser @!state)
    (let [flags       (get @!state :launch-flags {})
          profile-dir (get flags "profile")
          launch-opts (cond-> {:headless (:headless @!state)}
                        (get flags "executable-path") (assoc :executable-path (get flags "executable-path"))
                        (get flags "args")            (assoc :args (clojure.string/split (get flags "args") #","))
                        (get flags "proxy")           (assoc :proxy {:server (get flags "proxy")
                                                                     :bypass (get flags "proxy-bypass" "")})
                        (get flags "cdp")             (assoc :cdp (get flags "cdp")))
          ctx-opts    (cond-> {}
                        (get flags "user-agent")          (assoc :user-agent (get flags "user-agent"))
                        (get flags "ignore-https-errors")  (assoc :ignore-https-errors true)
                        (get flags "headers")             (assoc :extra-http-headers
                                                            (try (json/read-json (get flags "headers"))
                                                              (catch Exception _ {})))
                        (get flags "storage-state")       (assoc :storage-state (get flags "storage-state")))
          pw          (core/create)]
      (if profile-dir
        ;; Persistent context: real Chrome profile directory.
        ;; launchPersistentContext returns BrowserContext directly, not Browser.
        ;; Closing the context auto-closes the browser.
        (let [persistent-opts (merge launch-opts ctx-opts)
              context         (core/launch-persistent-context
                                (.chromium ^com.microsoft.playwright.Playwright pw)
                                profile-dir
                                persistent-opts)
              browser         (.browser ^com.microsoft.playwright.BrowserContext context)
              pg-inst         (if (seq (.pages ^com.microsoft.playwright.BrowserContext context))
                                (first (.pages ^com.microsoft.playwright.BrowserContext context))
                                (core/new-page-from-context context))]
          (swap! !state assoc :pw pw :browser browser :context context :page pg-inst
            :persistent-profile true))
        ;; Normal path: launch browser + create context separately.
        (let [browser (if (get flags "cdp")
                        (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String (get flags "cdp"))
                        (core/launch-chromium pw launch-opts))
              context (if (seq ctx-opts)
                        (core/new-context browser ctx-opts)
                        (core/new-context browser))
              pg-inst (core/new-page-from-context context)]
          (swap! !state assoc :pw pw :browser browser :context context :page pg-inst)))
      ;; Common setup for both paths
      (let [pg-inst (:page @!state)]
        (reset! !console-messages [])
        (page/on-console pg-inst (fn [msg]
                                   (swap! !console-messages conj
                                     {:type (.type ^ConsoleMessage msg)
                                      :text (.text ^ConsoleMessage msg)})))
        (reset! !page-errors [])
        (page/on-page-error pg-inst (fn [error]
                                      (swap! !page-errors conj
                                        {:message (str error)})))
        (reset! !tracked-requests [])
        (page/on-response pg-inst track-response!)
        ;; Auto-load session state if --session-name is set (not for persistent profiles)
        (when-not profile-dir
          (auto-load-session-state!))))))

;; =============================================================================
;; Ref Resolution
;; =============================================================================

(defn- ref? [^String s]
  (or (re-matches #"@?e\d+" s)
    (str/starts-with? s "@e")))

(defn- resolve-selector
  "Resolves a selector — if it's a ref (@e1, e1) resolve via snapshot,
   otherwise return a regular CSS locator.
   Throws immediately if the ref was never captured in a snapshot."
  [^String selector]
  (if (ref? selector)
    (let [ref-id (str/replace selector #"^@" "")
          refs   (:refs @!state)]
      (when-not (get refs ref-id)
        (let [known (sort-by (fn [k] (parse-long (subs k 1))) (keys refs))
              hint  (if (seq known)
                      (str "Available: @" (first known) "–@" (last known) ". Run 'snapshot' to refresh.")
                      "No refs available. Run 'snapshot' first to assign refs (@e1, @e2, …).")]
          (throw (ex-info (str "Ref " ref-id " not found. " hint) {}))))
      (snapshot/resolve-ref (pg) ref-id))
    (page/locator (pg) selector)))

(defn- describe-element
  "Returns a short human-readable description of the element behind a locator.
   e.g. 'h1 \"Example Domain\"', 'button \"Submit\"', 'input[type=text][name=email]'.
   Returns nil on failure (element detached, timeout, etc.)."
  [loc]
  (try
    (locator/evaluate-locator loc
      (str "el => {"
        "  const tag = el.tagName.toLowerCase();"
        "  const text = (el.innerText || '').trim().replace(/\\s+/g, ' ');"
        "  const cls = el.className ? '.' + el.className.trim().split(/\\s+/)[0] : '';"
        "  const name = el.getAttribute('name');"
        "  const type = el.getAttribute('type');"
        "  let desc = tag;"
        "  if (cls && !text) desc += cls;"
        "  if (type) desc += '[type=' + type + ']';"
        "  if (name) desc += '[name=' + name + ']';"
        "  const dt = text.length > 30 ? text.slice(0, 30) + '…' : text;"
        "  if (dt) desc += ' \"' + dt + '\"';"
        "  return desc;"
        "}"))
    (catch Exception _ nil)))

;; =============================================================================
;; Snapshot Helper
;; =============================================================================

(defn- ensure-page-loaded!
  "Throws if no page has been navigated to (still on about:blank)."
  []
  (let [url (page/url (pg))]
    (when (or (nil? url) (#{"about:blank" ""} url))
      (throw (ex-info "No page loaded. Navigate first: spel open <url>" {})))))

(defn- snapshot-after-action!
  "Captures a snapshot, stores refs in state, returns the tree string.

   Accepts optional opts map with :scope (CSS selector) to restrict
   the snapshot to a DOM subtree."
  ([]
   (snapshot-after-action! {}))
  ([opts]
   (let [snap (snapshot/capture-snapshot (pg) opts)]
     (swap! !state assoc :refs (:refs snap) :counter (:counter snap))
     (:tree snap))))

;; =============================================================================
;; Command Handlers
;; =============================================================================

(defmulti ^:private handle-cmd (fn [action _params] action))

(defmethod handle-cmd "navigate" [_ {:strs [url]}]
  (ensure-browser!)
  (page/navigate (pg) url)
  (page/wait-for-load-state (pg))
  (let [tree (snapshot-after-action!)]
    {:snapshot tree :url (page/url (pg)) :title (page/title (pg))}))

(defmethod handle-cmd "snapshot" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [sel  (get params "selector")
        tree (snapshot-after-action! (cond-> {}
                                       sel (assoc :scope sel)))
        tree (filter-snapshot-tree tree params)]
    {:snapshot tree :refs_count (:counter @!state)}))

(defmethod handle-cmd "click" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/click (resolve-selector selector))
  (let [tree (snapshot-after-action!)]
    {:clicked selector :snapshot tree}))

(defmethod handle-cmd "fill" [_ {:strs [selector value]}]
  (ensure-page-loaded!)
  (locator/fill (resolve-selector selector) value)
  (let [tree (snapshot-after-action!)]
    {:filled selector :snapshot tree}))

(defmethod handle-cmd "type" [_ {:strs [selector text]}]
  (ensure-page-loaded!)
  (locator/type-text (resolve-selector selector) text)
  (let [tree (snapshot-after-action!)]
    {:typed selector :snapshot tree}))

(defmethod handle-cmd "press" [_ {:strs [key selector]}]
  (ensure-page-loaded!)
  (if selector
    (locator/press (resolve-selector selector) key)
    (.press ^Keyboard (page/page-keyboard (pg)) key))
  (let [tree (snapshot-after-action!)]
    {:pressed key :snapshot tree}))

(defmethod handle-cmd "hover" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (locator/hover loc)
        desc (describe-element loc)]
    (cond-> {:hovered selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "check" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/check (resolve-selector selector))
  (let [tree (snapshot-after-action!)]
    {:checked selector :snapshot tree}))

(defmethod handle-cmd "uncheck" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/uncheck (resolve-selector selector))
  (let [tree (snapshot-after-action!)]
    {:unchecked selector :snapshot tree}))

(defmethod handle-cmd "select" [_ {:strs [selector values]}]
  (ensure-page-loaded!)
  (locator/select-option (resolve-selector selector) values)
  (let [tree (snapshot-after-action!)]
    {:selected selector :snapshot tree}))

(defmethod handle-cmd "dblclick" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/dblclick (resolve-selector selector))
  (let [tree (snapshot-after-action!)]
    {:dblclicked selector :snapshot tree}))

(defmethod handle-cmd "focus" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (locator/focus loc)
        desc (describe-element loc)]
    (cond-> {:focused selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "clear" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (locator/clear loc)
        desc (describe-element loc)]
    (cond-> {:cleared selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "screenshot" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [path-str    (get params "path")
        full-page?  (get params "fullPage" false)
        sel         (get params "selector")
        ^bytes ss-bytes (if sel
                          (locator/locator-screenshot (resolve-selector sel))
                          (page/screenshot (pg) (cond-> {}
                                                  full-page? (assoc :full-page true))))]
    (if path-str
      (do (java.nio.file.Files/write
            (Path/of ^String path-str (into-array String []))
            ss-bytes
            ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
        {:path path-str :size (alength ss-bytes)})
      (let [tmp-path (str (System/getProperty "java.io.tmpdir")
                       java.io.File/separator
                       "spel-screenshot-"
                       (System/currentTimeMillis) ".png")]
        (java.nio.file.Files/write
          (Path/of ^String tmp-path (into-array String []))
          ss-bytes
          ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
        {:path tmp-path :size (alength ss-bytes)}))))

(defmethod handle-cmd "annotate" [_ params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (let [scope (get params "selector")
        opts (cond-> {}
               scope
               (assoc :scope scope)
               (get params "full-page")              (assoc :full-page true)
               (contains? params "show-badges")     (assoc :show-badges (get params "show-badges"))
               (contains? params "show-dimensions") (assoc :show-dimensions (get params "show-dimensions"))
               (contains? params "show-boxes")      (assoc :show-boxes (get params "show-boxes")))
        ;; Capture fresh snapshot for refs
        _    (snapshot-after-action!)
        refs (:refs @!state)
        n    (if (seq refs)
               (annotate/inject-overlays! (pg) refs opts)
               0)]
    {:annotated n :refs_total (:counter @!state)}))

(defmethod handle-cmd "unannotate" [_ _params]
  (ensure-browser!)
  (ensure-page-loaded!)
  (annotate/remove-overlays! (pg))
  {:removed true})

(defmethod handle-cmd "evaluate" [_ {:strs [script base64]}]
  (ensure-page-loaded!)
  (let [result (page/evaluate (pg) script)]
    (if base64
      {:result (.encodeToString (Base64/getEncoder)
                 (.getBytes (str result) "UTF-8"))}
      {:result result})))

(defmethod handle-cmd "scroll" [_ params]
  (ensure-page-loaded!)
  (let [direction (get params "direction" "down")
        amount    (long (get params "amount" 500))
        sel       (get params "selector")
        [dx dy]   (case direction
                    "up"    [0 (- amount)]
                    "down"  [0 amount]
                    "left"  [(- amount) 0]
                    "right" [amount 0]
                    [0 amount])]
    (if sel
      (page/evaluate (pg)
        (str "document.querySelector('" sel "').scrollBy(" dx ", " dy ")"))
      (page/evaluate (pg)
        (str "window.scrollBy(" dx ", " dy ")")))
    (let [tree (snapshot-after-action!)]
      {:scrolled direction :amount amount :snapshot tree})))

(defmethod handle-cmd "back" [_ _]
  (ensure-page-loaded!)
  (page/go-back (pg))
  (let [tree (snapshot-after-action!)]
    {:snapshot tree :url (page/url (pg))}))

(defmethod handle-cmd "forward" [_ _]
  (ensure-page-loaded!)
  (page/go-forward (pg))
  (let [tree (snapshot-after-action!)]
    {:snapshot tree :url (page/url (pg))}))

(defmethod handle-cmd "reload" [_ _]
  (ensure-page-loaded!)
  (page/reload (pg))
  (let [tree (snapshot-after-action!)]
    {:snapshot tree :url (page/url (pg))}))

(defmethod handle-cmd "wait" [_ params]
  (cond
    (get params "text")
    (do (page/wait-for-selector (pg) (str "text=" (get params "text")))
      {:found_text (get params "text")})

    (get params "url")
    (do (page/wait-for-url (pg) (get params "url"))
      {:url (get params "url")})

    (get params "function")
    (do (page/wait-for-function (pg) (get params "function"))
      {:function_completed true})

    (get params "selector")
    (do (page/wait-for-selector (pg) (get params "selector"))
      {:found (get params "selector")})

    (get params "state")
    (do (page/wait-for-load-state (pg) (keyword (get params "state")))
      {:state (get params "state")})

    (get params "timeout")
    (do (page/wait-for-timeout (pg) (double (get params "timeout")))
      {:waited (get params "timeout")})

    :else
    {:error "No wait condition specified"}))

(defmethod handle-cmd "tab_new" [_ params]
  (let [new-pg (core/new-page-from-context (ctx))]
    (swap! !state assoc :page new-pg)
    (when-let [url (get params "url")]
      (page/navigate new-pg url))
    {:tab "new" :url (page/url new-pg)}))

(defmethod handle-cmd "tab_list" [_ _]
  (let [pages  (core/context-pages (ctx))
        active (pg)]
    {:tabs (mapv (fn [idx p]
                   {:index idx :url (page/url p) :title (page/title p)
                    :active (= p active)})
             (range) pages)}))

(defmethod handle-cmd "tab_switch" [_ {:strs [index]}]
  (let [pages (core/context-pages (ctx))
        pg-inst (nth pages (int index))]
    (swap! !state assoc :page pg-inst)
    (let [tree (snapshot-after-action!)]
      {:tab index :url (page/url pg-inst) :snapshot tree})))

(defmethod handle-cmd "tab_close" [_ _]
  (let [current (pg)]
    (core/close-page! current)
    (let [remaining (core/context-pages (ctx))]
      (when (seq remaining)
        (swap! !state assoc :page (last remaining)))
      {:closed true :remaining (count remaining)})))

(defmethod handle-cmd "url" [_ _]
  {:url (page/url (pg))})

(defmethod handle-cmd "title" [_ _]
  {:title (page/title (pg))})

(defmethod handle-cmd "content" [_ params]
  (ensure-page-loaded!)
  (if-let [sel (get params "selector")]
    {:html (locator/inner-html (resolve-selector sel))}
    {:html (page/content (pg))}))

(defmethod handle-cmd "get_text" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:text (locator/text-content (resolve-selector selector))})

(defmethod handle-cmd "get_attribute" [_ {:strs [selector attribute]}]
  (ensure-page-loaded!)
  {:value (locator/get-attribute (resolve-selector selector) attribute)})

(defmethod handle-cmd "is_visible" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:visible (locator/is-visible? (resolve-selector selector))})

(defmethod handle-cmd "is_enabled" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:enabled (locator/is-enabled? (resolve-selector selector))})

(defmethod handle-cmd "is_checked" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:checked (locator/is-checked? (resolve-selector selector))})

(defmethod handle-cmd "count" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:count (locator/count-elements (page/locator (pg) selector))})

(defmethod handle-cmd "bounding_box" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:box (locator/bounding-box (resolve-selector selector))})

(defmethod handle-cmd "pdf" [_ {:strs [path]}]
  (ensure-page-loaded!)
  (page/pdf (pg) {:path path})
  {:path path})

;; --- Phase 1: Core Gaps ---

(defmethod handle-cmd "keydown" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (input/key-down (page/page-keyboard (pg)) key)
  {:keydown key})

(defmethod handle-cmd "keyup" [_ {:strs [key]}]
  (ensure-page-loaded!)
  (input/key-up (page/page-keyboard (pg)) key)
  {:keyup key})

(defmethod handle-cmd "scrollintoview" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (locator/scroll-into-view (resolve-selector selector))
  (let [tree (snapshot-after-action!)]
    {:scrolled_into_view selector :snapshot tree}))

(defmethod handle-cmd "drag" [_ {:strs [source target]}]
  (ensure-page-loaded!)
  (locator/drag-to (resolve-selector source) (resolve-selector target))
  (let [tree (snapshot-after-action!)]
    {:dragged {:from source :to target} :snapshot tree}))

(defmethod handle-cmd "upload" [_ {:strs [selector files]}]
  (ensure-page-loaded!)
  (let [file-paths (if (string? files) [files] files)]
    (locator/set-input-files! (resolve-selector selector) file-paths)
    (let [tree (snapshot-after-action!)]
      {:uploaded {:selector selector :files file-paths} :snapshot tree})))

(defmethod handle-cmd "get_value" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:value (locator/input-value (resolve-selector selector))})

(defmethod handle-cmd "get_count" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:count (locator/count-elements (page/locator (pg) selector))})

(defmethod handle-cmd "get_box" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  {:box (locator/bounding-box (resolve-selector selector))})

(defmethod handle-cmd "highlight" [_ {:strs [selector]}]
  (ensure-page-loaded!)
  (let [loc  (resolve-selector selector)
        _    (locator/highlight loc)
        desc (describe-element loc)]
    (cond-> {:highlighted selector}
      desc (assoc :desc desc))))

(defmethod handle-cmd "find" [_ {:strs [by value find_action find_value name exact selector]}]
  (ensure-page-loaded!)
  (let [loc (case by
              "role"        (if name
                               ;; Use role selector string to avoid GetByRoleOptions reflection issues in native-image
                              (let [name-part (if exact
                                                (str "[name=\"" name "\" s]")
                                                (str "[name=/" name "/i]"))]
                                (page/locator (pg) (str "role=" value name-part)))
                              (page/get-by-role (pg) (str->aria-role value)))
              "text"        (page/get-by-text (pg) value)
              "label"       (page/get-by-label (pg) value)
              "placeholder" (page/get-by-placeholder (pg) value)
              "alt"         (page/get-by-alt-text (pg) value)
              "title"       (page/get-by-title (pg) value)
              "testid"      (page/get-by-test-id (pg) value)
              "first"       (locator/first-element (resolve-selector value))
              "last"        (locator/last-element (resolve-selector value))
              "nth"         (locator/nth-element (resolve-selector selector)
                              (Integer/parseInt value))
              (throw (ex-info (str "Unknown find type: " by) {})))]
    (case find_action
      "click"   (do (locator/click loc)
                  (let [tree (snapshot-after-action!)]
                    {:found by :value value :action "click" :snapshot tree}))
      "fill"    (do (locator/fill loc find_value)
                  (let [tree (snapshot-after-action!)]
                    {:found by :value value :action "fill" :snapshot tree}))
      "type"    (do (locator/type-text loc find_value)
                  {:found by :value value :action "type"})
      "check"   (do (locator/check loc) {:found by :value value :action "check"})
      "uncheck" (do (locator/uncheck loc) {:found by :value value :action "uncheck"})
      "hover"   (do (locator/hover loc) {:found by :value value :action "hover"})
      "focus"   (do (locator/focus loc) {:found by :value value :action "focus"})
      "text"    {:found by :value value :text (locator/text-content loc)}
      "count"   {:found by :value value :count (locator/count-elements loc)}
      "visible" {:found by :value value :visible (locator/is-visible? loc)}
      (nil)     {:found by :value value :count (locator/count-elements loc)}
      {:error (str "Unknown find action: " find_action)})))

;; --- Phase 2: Mouse Control ---

(defmethod handle-cmd "mouse_move" [_ {:strs [x y]}]
  (input/mouse-move (page/page-mouse (pg)) (double x) (double y))
  {:moved {:x x :y y}})

(defmethod handle-cmd "mouse_down" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (input/mouse-down m)
      (.down m (options/->mouse-down-options {:button (keyword btn)})))
    {:mouse_down btn}))

(defmethod handle-cmd "mouse_up" [_ {:strs [button]}]
  (let [^Mouse m (page/page-mouse (pg))
        btn (or button "left")]
    (if (= btn "left")
      (input/mouse-up m)
      (.up m (options/->mouse-up-options {:button (keyword btn)})))
    {:mouse_up btn}))

(defmethod handle-cmd "mouse_wheel" [_ {:strs [deltaX deltaY]}]
  (input/mouse-wheel (page/page-mouse (pg))
    (double (or deltaX 0))
    (double (or deltaY 0)))
  {:wheel {:dx (or deltaX 0) :dy (or deltaY 0)}})

;; --- Phase 2: Browser Settings ---

(defmethod handle-cmd "set_viewport" [_ {:strs [width height]}]
  (page/set-viewport-size! (pg) (long width) (long height))
  {:viewport {:width width :height height}})

(defmethod handle-cmd "set_offline" [_ {:strs [enabled]}]
  (let [offline (if (nil? enabled) true (boolean enabled))]
    (core/context-set-offline! (ctx) offline)
    {:offline offline}))

(defmethod handle-cmd "set_headers" [_ {:strs [headers]}]
  (page/set-extra-http-headers! (pg) headers)
  {:headers_set true})

(defmethod handle-cmd "set_media" [_ {:strs [colorScheme]}]
  (let [scheme (case colorScheme
                 ("dark" "Dark")       :dark
                 ("light" "Light")     :light
                 ("no-preference")     :no-preference
                 :no-preference)]
    (page/emulate-media! (pg) {:color-scheme scheme})
    {:media {:colorScheme colorScheme}}))

(def ^:private device-presets
  "Common device presets for set_device."
  {"iphone 14"        {:width 390  :height 844  :device-scale-factor 3     :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   "iphone 14 pro"    {:width 393  :height 852  :device-scale-factor 3     :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   "iphone 12"        {:width 390  :height 844  :device-scale-factor 3     :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.0 Mobile/15E148 Safari/604.1"}
   "pixel 7"          {:width 412  :height 915  :device-scale-factor 2.625 :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"}
   "pixel 5"          {:width 393  :height 851  :device-scale-factor 2.75  :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.0.0 Mobile Safari/537.36"}
   "ipad"             {:width 810  :height 1080 :device-scale-factor 2     :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   "ipad pro"         {:width 1024 :height 1366 :device-scale-factor 2     :is-mobile true  :has-touch true
                       :user-agent "Mozilla/5.0 (iPad; CPU OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"}
   "desktop"          {:width 1280 :height 720  :device-scale-factor 1     :is-mobile false :has-touch false
                       :user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"}
   "desktop hd"       {:width 1920 :height 1080 :device-scale-factor 1     :is-mobile false :has-touch false
                       :user-agent "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"}})

(defmethod handle-cmd "set_device" [_ {:strs [device]}]
  (let [device-name (str/lower-case (or device ""))
        preset      (get device-presets device-name)]
    (if preset
      (let [current-url (try (page/url (pg)) (catch Exception _ nil))]
        ;; Save in-flight trace before destroying context
        (save-inflight-trace!)
        ;; Close current page and context, recreate with device settings
        (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
        (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
        (let [new-ctx (core/new-context (:browser @!state)
                        (-> preset
                          (dissoc :width :height)
                          (assoc :viewport {:width (:width preset) :height (:height preset)})))
              new-pg  (core/new-page-from-context new-ctx)]
          (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
          ;; Re-register console, error, and request listeners on new page
          (reset! !console-messages [])
          (page/on-console new-pg (fn [msg]
                                    (swap! !console-messages conj
                                      {:type (.type ^ConsoleMessage msg)
                                       :text (.text ^ConsoleMessage msg)})))
          (reset! !page-errors [])
          (page/on-page-error new-pg (fn [error]
                                       (swap! !page-errors conj
                                         {:message (str error)})))
          (reset! !tracked-requests [])
          (page/on-response new-pg track-response!)
          (when current-url (page/navigate new-pg current-url))
          {:device device :preset preset}))
      {:error (str "Unknown device: " device
                ". Available: " (str/join ", " (keys device-presets)))})))

(defmethod handle-cmd "set_geo" [_ {:strs [latitude longitude accuracy]}]
  (core/context-grant-permissions! (ctx) ["geolocation"])
  (.setGeolocation ^BrowserContext (ctx)
    (doto (Geolocation. (double latitude) (double longitude))
      (.setAccuracy (double (or accuracy 1)))))
  {:geolocation {:latitude latitude :longitude longitude}})

(defmethod handle-cmd "set_credentials" [_ {:strs [username password]}]
  ;; HTTP credentials require recreating the context
  (let [current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (core/new-context (:browser @!state)
                    {:http-credentials {:username username :password password}})
          new-pg  (core/new-page-from-context new-ctx)]
      (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
      ;; Re-register console, error, and request listeners on new page
      (reset! !console-messages [])
      (page/on-console new-pg (fn [msg]
                                (swap! !console-messages conj
                                  {:type (.type ^ConsoleMessage msg)
                                   :text (.text ^ConsoleMessage msg)})))
      (reset! !page-errors [])
      (page/on-page-error new-pg (fn [error]
                                   (swap! !page-errors conj
                                     {:message (str error)})))
      (reset! !tracked-requests [])
      (page/on-response new-pg track-response!)
      (when current-url (page/navigate new-pg current-url))
      {:credentials_set true})))

;; --- Phase 3: Cookies ---

(defmethod handle-cmd "cookies_get" [_ {:strs [urls]}]
  (let [cookies (if urls
                  (.cookies ^BrowserContext (ctx)
                    (java.util.ArrayList. ^java.util.Collection (vec urls)))
                  (core/context-cookies (ctx)))]
    {:cookies (mapv (fn [^Cookie c]
                      {:name (.name c) :value (.value c) :domain (.domain c)
                       :path (.path c) :expires (.expires c)
                       :httpOnly (.httpOnly c) :secure (.secure c)
                       :sameSite (str (.sameSite c))})
                cookies)}))

(defmethod handle-cmd "cookies_set" [_ {:strs [name value domain path url]}]
  (let [cookie (Cookie. name value)]
    (if domain
      (do (.setDomain cookie domain)
        (.setPath cookie (or path "/")))
      (.setUrl cookie (or url (page/url (pg)))))
    (let [cookie-list (java.util.Collections/singletonList cookie)]
      (.addCookies ^BrowserContext (ctx) cookie-list))
    {:cookie_set {:name name :value value}}))

(defmethod handle-cmd "cookies_clear" [_ _]
  (core/context-clear-cookies! (ctx))
  {:cookies_cleared true})

;; --- Phase 3: Storage ---

(defmethod handle-cmd "storage_get" [_ {:strs [type key]}]
  (let [st (or type "local")
        js (if key
             (str st "Storage.getItem('" key "')")
             (str "JSON.stringify(Object.entries(" st "Storage))"))]
    {:storage (page/evaluate (pg) js)}))

(defmethod handle-cmd "storage_set" [_ {:strs [type key value]}]
  (let [st (or type "local")]
    (page/evaluate (pg) (str st "Storage.setItem('" key "', '" value "')"))
    {:storage_set {:key key :value value}}))

(defmethod handle-cmd "storage_clear" [_ {:strs [type]}]
  (let [st (or type "local")]
    (page/evaluate (pg) (str st "Storage.clear()"))
    {:storage_cleared st}))

;; --- Phase 3: Network ---

(defmethod handle-cmd "network_route" [_ {:strs [url action_type body status content_type]}]
  (let [handler (fn [route]
                  (case action_type
                    "abort"   (network/route-abort! route)
                    "fulfill" (network/route-fulfill! route
                                (cond-> {}
                                  status       (assoc :status (long status))
                                  body         (assoc :body body)
                                  content_type (assoc :content-type content_type)))
                    ;; default: continue
                    (network/route-continue! route)))]
    (page/route! (pg) url handler)
    (swap! !routes assoc url handler)
    {:route_added url}))

(defmethod handle-cmd "network_unroute" [_ {:strs [url]}]
  (if url
    (do (page/unroute! (pg) url)
      (swap! !routes dissoc url)
      {:route_removed url})
    (do (doseq [[u _] @!routes]
          (page/unroute! (pg) u))
      (reset! !routes {})
      {:all_routes_removed true})))

(defmethod handle-cmd "network_requests" [_ {:strs [filter type method status]}]
  (let [reqs     @!tracked-requests
        filtered (cond->> reqs
                   filter (filterv #(re-find (re-pattern filter) (str (:url %))))
                   type   (filterv #(= (:resource-type %) type))
                   method (filterv #(= (str/upper-case (:method %)) (str/upper-case method)))
                   status (filterv #(str/starts-with? (str (:status %)) status)))]
    {:requests filtered}))

(defmethod handle-cmd "network_clear" [_ _]
  (reset! !tracked-requests [])
  {:network "cleared"})

;; --- Phase 4: Frames ---

(defmethod handle-cmd "frame_switch" [_ {:strs [selector]}]
  (if (= selector "main")
    {:frame "main"}
    (let [frames (page/frames (pg))
          target (or (page/frame-by-name (pg) selector)
                   (some #(when (str/includes? (.url ^Frame %) selector) %) frames))]
      (if target
        {:frame selector :url (.url ^Frame target)}
        {:error (str "Frame not found: " selector)}))))

(defmethod handle-cmd "frame_list" [_ _]
  {:frames (mapv (fn [f]
                   {:name (.name ^Frame f) :url (.url ^Frame f)})
             (page/frames (pg)))})

;; --- Phase 4: Dialogs ---

(defmethod handle-cmd "dialog_accept" [_ {:strs [text]}]
  (when-let [old @!dialog-handler]
    (.offDialog ^Page (pg) old))
  (let [handler (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (.accept ^Dialog dialog (or text ""))))]
    (reset! !dialog-handler handler)
    (.onDialog ^Page (pg) handler))
  {:dialog_handler "accept" :text text})

(defmethod handle-cmd "dialog_dismiss" [_ _]
  (when-let [old @!dialog-handler]
    (.offDialog ^Page (pg) old))
  (let [handler (reify java.util.function.Consumer
                  (accept [_ dialog]
                    (.dismiss ^Dialog dialog)))]
    (reset! !dialog-handler handler)
    (.onDialog ^Page (pg) handler))
  {:dialog_handler "dismiss"})

;; --- Phase 4: Debug ---

(defmethod handle-cmd "trace_start" [_ {:strs [name]}]
  (util/tracing-start! (util/context-tracing (ctx))
    (cond-> {:screenshots true :snapshots true}
      name (assoc :name name)))
  (swap! !state assoc :tracing? true)
  {:trace "started" :name name})

(defmethod handle-cmd "trace_stop" [_ {:strs [path]}]
  (let [out-path (or path "trace.zip")]
    (util/tracing-stop! (util/context-tracing (ctx)) {:path out-path})
    (swap! !state assoc :tracing? false)
    {:trace "stopped" :path out-path}))

(defmethod handle-cmd "console_get" [_ {:strs [clear]}]
  (let [msgs @!console-messages]
    (when clear (reset! !console-messages []))
    {:messages msgs}))

(defmethod handle-cmd "console_clear" [_ _]
  (reset! !console-messages [])
  {:console "cleared"})

(defmethod handle-cmd "errors_get" [_ {:strs [clear]}]
  (let [errs @!page-errors]
    (when clear (reset! !page-errors []))
    {:errors errs}))

(defmethod handle-cmd "errors_clear" [_ _]
  (reset! !page-errors [])
  {:errors "cleared"})

(defmethod handle-cmd "console_start" [_ _]
  (page/on-console (pg) (fn [msg]
                          (swap! !console-messages conj
                            {:type (.type ^ConsoleMessage msg)
                             :text (.text ^ConsoleMessage msg)})))
  {:console "listening"})

(defmethod handle-cmd "errors_start" [_ _]
  (page/on-page-error (pg) (fn [error]
                             (swap! !page-errors conj
                               {:message (str error)})))
  {:errors "listening"})

;; --- Phase 4: State Management ---

(defmethod handle-cmd "state_save" [_ {:strs [path]}]
  (let [save-path (or path (str "state-" (:session @!state) ".json"))]
    (.storageState ^BrowserContext (ctx)
      (doto (com.microsoft.playwright.BrowserContext$StorageStateOptions.)
        (.setPath (Path/of save-path (into-array String [])))))
    {:state "saved" :path save-path}))

(defmethod handle-cmd "state_load" [_ {:strs [path]}]
  (let [state-path (or path (str "state-" (:session @!state) ".json"))
        current-url (try (page/url (pg)) (catch Exception _ nil))]
    ;; Save in-flight trace before destroying context
    (save-inflight-trace!)
    (when-let [p (:page @!state)] (try (core/close-page! p) (catch Exception e (warn "close-page" e))))
    (when-let [c (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (let [new-ctx (core/new-context (:browser @!state) {:storage-state state-path})]
      (if (anomaly/anomaly? new-ctx)
        {:error (str "Failed to load state: " (:anomaly/message new-ctx))}
        (let [new-pg (core/new-page-from-context new-ctx)]
          (if (anomaly/anomaly? new-pg)
            (do (.close ^BrowserContext new-ctx)
              {:error (str "Failed to create page: " (:anomaly/message new-pg))})
            (do
              (swap! !state assoc :context new-ctx :page new-pg :tracing? false)
               ;; Re-register console, error, and request listeners on new page
              (reset! !console-messages [])
              (page/on-console new-pg (fn [msg]
                                        (swap! !console-messages conj
                                          {:type (.type ^ConsoleMessage msg)
                                           :text (.text ^ConsoleMessage msg)})))
              (reset! !page-errors [])
              (page/on-page-error new-pg (fn [error]
                                           (swap! !page-errors conj
                                             {:message (str error)})))
              (reset! !tracked-requests [])
              (page/on-response new-pg track-response!)
              (when current-url (page/navigate new-pg current-url))
              {:state "loaded" :path state-path})))))))

(defmethod handle-cmd "state_list" [_ _]
  (let [dir (java.io.File. ".")
        files (->> (.listFiles dir)
                (filter (fn [^File f] (and (.isFile f)
                                        (str/ends-with? (.getName f) ".json")
                                        (str/starts-with? (.getName f) "state-")))))]
    {:states (mapv (fn [^File f] (.getName f)) files)}))

(defmethod handle-cmd "state_show" [_ {:strs [file]}]
  (let [content (String. ^bytes (Files/readAllBytes (Path/of ^String file (into-array String []))))]
    {:state (json/read-json content)}))

(defmethod handle-cmd "state_rename" [_ {:strs [old_name new_name]}]
  (Files/move (Path/of ^String old_name (into-array String []))
    (Path/of ^String new_name (into-array String []))
    ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption []))
  {:renamed {:from old_name :to new_name}})

(defmethod handle-cmd "state_clear" [_ {:strs [name all]}]
  (if all
    (let [dir (java.io.File. ".")
          files (->> (.listFiles dir)
                  (filter (fn [^File f] (and (.isFile f)
                                          (str/ends-with? (.getName f) ".json")
                                          (str/starts-with? (.getName f) "state-")))))]
      (doseq [^File f files] (.delete f))
      {:cleared (count files)})
    (let [file-name ^String (or name (str "state-" (:session @!state) ".json"))]
      (Files/deleteIfExists (Path/of file-name (into-array String [])))
      {:cleared file-name})))

(defmethod handle-cmd "state_clean" [_ {:strs [older_than_days]}]
  (let [days     (long (or older_than_days 30))
        cutoff   (- (System/currentTimeMillis) (* days 24 60 60 1000))
        dir      (java.io.File. ".")
        files    (->> (.listFiles dir)
                   (filter (fn [^File f] (and (.isFile f)
                                           (str/ends-with? (.getName f) ".json")
                                           (str/starts-with? (.getName f) "state-")
                                           (< (.lastModified f) ^long cutoff)))))]
    (doseq [^File f files] (.delete f))
    {:cleaned (count files) :older_than_days days}))

;; --- Phase 5: Sessions ---

(defmethod handle-cmd "session_list" [_ _]
  (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir"))
        socks   (->> (.listFiles tmp-dir)
                  (filter (fn [^File f]
                            (and (.isFile f)
                              (str/starts-with? (.getName f) "spel-")
                              (str/ends-with? (.getName f) ".sock")))))]
    {:sessions (mapv (fn [^File f]
                       (let [n (-> (.getName f)
                                 (str/replace "spel-" "")
                                 (str/replace ".sock" ""))]
                         {:name n :socket (.getAbsolutePath f)}))
                 socks)
     :current (:session @!state)}))

(defmethod handle-cmd "session_info" [_ _]
  {:session    (:session @!state)
   :headless   (:headless @!state)
   :tracing    (:tracing? @!state)
   :url        (try (page/url (pg)) (catch Exception _ nil))
   :title      (try (page/title (pg)) (catch Exception _ nil))
   :refs_count (:counter @!state)})

;; --- Phase 5: Connect CDP ---

(defmethod handle-cmd "connect" [_ {:strs [url]}]
  (let [pw (or (:pw @!state) (core/create))
        browser (.connectOverCDP (.chromium ^com.microsoft.playwright.Playwright pw) ^String url)
        contexts (.contexts ^com.microsoft.playwright.Browser browser)
        context (if (seq contexts) (first contexts) (core/new-context browser))
        pages (core/context-pages context)
        pg-inst (if (seq pages) (first pages) (core/new-page-from-context context))]
    (swap! !state assoc :pw pw :browser browser :context context :page pg-inst)
    ;; Auto-register console, error, and request listeners
    (reset! !console-messages [])
    (page/on-console pg-inst (fn [msg]
                               (swap! !console-messages conj
                                 {:type (.type ^ConsoleMessage msg)
                                  :text (.text ^ConsoleMessage msg)})))
    (reset! !page-errors [])
    (page/on-page-error pg-inst (fn [error]
                                  (swap! !page-errors conj
                                    {:message (str error)})))
    (reset! !tracked-requests [])
    (page/on-response pg-inst track-response!)
    {:connected url :url (page/url pg-inst)}))

;; --- SCI Eval ---

;; Cached SCI evaluation context. Created once per daemon lifetime and reused
;; across eval invocations so that def'd vars persist between calls.
(defonce ^:private !sci-ctx (atom nil))

(defn- sync-state-to-sci!
  "Copies daemon's Playwright objects into SCI atoms so user code sees them."
  []
  (reset! sci-env/!daemon-mode? true)
  (let [st @!state]
    (reset! sci-env/!pw (:pw st))
    (reset! sci-env/!browser (:browser st))
    (reset! sci-env/!context (:context st))
    (reset! sci-env/!page (:page st))))

(defn- sync-sci-to-state!
  "After SCI eval, syncs SCI atoms back to daemon state in case user code
   changed the page (e.g. navigated, opened new tab)."
  []
  (swap! !state assoc
    :page @sci-env/!page
    :context @sci-env/!context))

(defmethod handle-cmd "sci_eval" [_ params]
  (ensure-browser!)
  (sync-state-to-sci!)
  (let [code (get params "code")]
    (when-not code
      (throw (ex-info "sci_eval requires a 'code' parameter" {})))
    (let [ctx (or @!sci-ctx
                (let [c (sci-env/create-sci-ctx)]
                  (reset! !sci-ctx c)
                  c))]
      (sci-env/set-throw-on-error! true)
      (try
        (let [result (sci-env/eval-string ctx code)]
          (sync-sci-to-state!)
          (if (anomaly/anomaly? result)
            {:error (::anomaly/message result)}
            {:result (pr-str result)}))
        (catch Exception e
          (sync-sci-to-state!)
          (throw e))))))

;; --- Close & Default ---

(defmethod handle-cmd "close" [_ _]
  ;; Auto-save session state if --session-name is set
  (auto-save-session-state!)
  ;; Note: in-flight trace is saved by stop-daemon! (called after this returns)
  (cond-> {:closed true :shutdown true}
    (:tracing? @!state) (assoc :trace-warning "active trace will be auto-saved on shutdown")))

(defmethod handle-cmd :default [action _]
  {:error (str "Unknown action: " action)})

;; =============================================================================
;; Protocol
;; =============================================================================

(defn- reflection-error-hint
  "Detects GraalVM reflection errors from Gson/UnsafeAllocator failures and
   returns a user-friendly message with the offending class name. Returns nil
   if the exception is not reflection-related."
  [^Throwable e]
  (let [msgs (loop [^Throwable t e, acc []]
               (if t
                 (recur (.getCause t) (conj acc (or (.getMessage t) "")))
                 acc))
        combined (str/join " " msgs)]
    (when (or (str/includes? combined "Unable to invoke no-args constructor")
            (str/includes? combined "UnsafeAllocator")
            (str/includes? combined "InstantiationException")
            (and (str/includes? combined "reflection")
              (str/includes? combined "registered")))
      (let [class-name (second (re-find #"for class ([\w.$]+)" combined))]
        (str (.getMessage e)
          "\n\n[GraalVM native-image] "
          (if class-name
            (str "Class '" class-name "' needs reflection registration. "
              "Add to reflect-config.json: "
              "{\"name\": \"" class-name "\", \"unsafeAllocated\": true, "
              "\"allDeclaredFields\": true, \"allDeclaredConstructors\": true, "
              "\"allDeclaredMethods\": true}")
            "A class may need reflection registration in reflect-config.json with \"unsafeAllocated\": true"))))))

(defn- process-command
  "Processes a single JSON command string. Returns a JSON response string."
  [^String line]
  (try
    (let [cmd    (json/read-json line)
          action (get cmd "action")
          flags  (get cmd "_flags")
          params (dissoc cmd "action" "_flags")]
      ;; Store launch flags if present (used by ensure-browser!)
      (when (seq flags)
        (swap! !state update :launch-flags merge flags))
      (try
        (let [result    (handle-cmd action params)
              anomaly-v (cond
                          (anomaly/anomaly? result)
                          result
                          (map? result)
                          (some (fn [[_ v]] (when (anomaly/anomaly? v) v)) result))]
          (if anomaly-v
            (let [msg (::anomaly/message anomaly-v)
                  ex  (:playwright/exception anomaly-v)
                  hint (when ex (reflection-error-hint ex))]
              (json/write-json-str {:success false
                                    :error (or hint msg (when ex (.getMessage ^Throwable ex)) "Unknown error")}))
            (json/write-json-str {:success true :data result})))
        (catch Throwable e
          (let [msg (or (reflection-error-hint e) (.getMessage e))]
            (json/write-json-str {:success false :error msg})))))
    (catch Throwable e
      (json/write-json-str {:success false :error (str "Parse error: " (.getMessage e))}))))

;; =============================================================================
;; Socket Server
;; =============================================================================

(defn- handle-connection
  "Handles a single client connection — reads commands, writes responses."
  [^SocketChannel client]
  (let [reader (BufferedReader. (InputStreamReader. (Channels/newInputStream client)))
        ^OutputStreamWriter writer (OutputStreamWriter. (Channels/newOutputStream client))]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (let [shutdown? (when-not (str/blank? line)
                            (let [response (process-command line)]
                              (.write writer ^String response)
                              (.write writer "\n")
                              (.flush writer)
                              ;; Check if shutdown was requested
                              (let [parsed (try (json/read-json response) (catch Exception _ nil))]
                                (and parsed (get-in parsed ["data" "shutdown"])))))]
            (if shutdown?
              (future (stop-daemon!))
              (recur)))))
      (catch Exception e (warn "handle-connection" e))
      (finally
        (try (.close client) (catch Exception e (warn "close-client" e)))))))

(defn- cleanup!
  "Removes socket and PID files."
  [^String session]
  (try (Files/deleteIfExists (socket-path session)) (catch Exception e (warn "delete-socket" e)))
  (try (Files/deleteIfExists (pid-file-path session)) (catch Exception e (warn "delete-pid" e))))

(defn daemon-running?
  "Checks if a daemon is running for the given session."
  [^String session]
  (let [pid-path (pid-file-path session)]
    (when (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try
        (let [pid (str/trim (String. (Files/readAllBytes pid-path)))
              p   (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["kill" "-0" pid])))]
          (= 0 (.waitFor p)))
        (catch Exception _
          (cleanup! session)
          false)))))

(defn- my-pid
  "Returns this process's PID as a string."
  []
  (str (.pid (java.lang.ProcessHandle/current))))

(defn- owns-pid-file?
  "Returns true if the PID file for `session` contains THIS process's PID."
  [^String session]
  (let [pid-path (pid-file-path session)]
    (and (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try
        (= (str/trim (String. (Files/readAllBytes pid-path))) (my-pid))
        (catch Exception _ false)))))

(defn- save-inflight-trace!
  "If tracing is active, stops the trace and saves it to an auto-generated path.
   Logs a warning to stderr so the user knows where the trace file went.
   Called during daemon shutdown to avoid losing in-flight traces."
  []
  (when (:tracing? @!state)
    (when-let [c (:context @!state)]
      (let [out-path (str "trace-autosave-" (System/currentTimeMillis) ".zip")]
        (try
          (util/tracing-stop! (util/context-tracing c) {:path out-path})
          (swap! !state assoc :tracing? false)
          (binding [*out* *err*]
            (println (str "spel: trace auto-saved to " out-path " (daemon shutting down)")))
          (catch Exception e
            (binding [*out* *err*]
              (println (str "spel: warn: failed to auto-save trace: " (.getMessage e))))))))))

(defn stop-daemon!
  "Stops the daemon server and cleans up browser resources.
   Closes server socket first so new CLI invocations fail fast and start
   a fresh daemon. Only deletes PID/socket files if they still belong to
   THIS process (prevents nuking a replacement daemon's files)."
  []
  (let [session (:session @!state)]
    ;; 1. Close server socket — reject new connections immediately
    (when-let [server @!server]
      (try (.close ^ServerSocketChannel server) (catch Exception e (warn "close-server" e)))
      (reset! !server nil))
    ;; 2. Save in-flight trace before closing browser resources
    (save-inflight-trace!)
    ;; 3. Close browser resources (may take seconds — Chromium shutdown)
    (when-let [p  (:page @!state)]    (try (core/close-page! p)    (catch Exception e (warn "close-page" e))))
    (when-let [c  (:context @!state)] (try (.close ^BrowserContext c) (catch Exception e (warn "close-context" e))))
    (when-let [b  (:browser @!state)] (try (core/close-browser! b) (catch Exception e (warn "close-browser" e))))
    (when-let [pw (:pw @!state)]      (try (core/close! pw)        (catch Exception e (warn "close-playwright" e))))
    ;; 4. Only delete files if they still belong to US (a new daemon may
    ;;    have already replaced them during our slow browser cleanup)
    (when (owns-pid-file? session)
      (cleanup! session))
    ;; 5. Reset state and exit
    (reset! !state {:pw nil :browser nil :context nil :page nil
                    :refs {} :counter 0 :headless true :session session
                    :tracing? false})
    (System/exit 0)))

(defn start-daemon!
  "Starts the daemon server. Blocks until shutdown.

   Params:
   `opts` - Map:
     :session  - String (default \"default\")
     :headless - Boolean (default true)"
  [opts]
  (let [session  (get opts :session "default")
        headless (get opts :headless true)
        sock-path (socket-path session)
        pid-path  (pid-file-path session)]
    ;; Store session config
    (swap! !state assoc :headless headless :session session)

    ;; Clean up stale socket
    (cleanup! session)

    ;; Write PID file
    (Files/writeString pid-path
      (str (.pid (java.lang.ProcessHandle/current)))
      (into-array java.nio.file.OpenOption []))

    ;; Create Unix domain socket server
    (let [addr   (UnixDomainSocketAddress/of (.toString sock-path))
          server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server addr)
      (reset! !server server)

      ;; Signal handlers
      (let [shutdown-hook (Thread. ^Runnable (fn [] (stop-daemon!)))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook))

      ;; Accept connections
      (try
        (loop []
          (let [client (.accept server)]
            (future (handle-connection client)))
          (recur))
        (catch Exception _
          ;; Server closed
          nil)))))
