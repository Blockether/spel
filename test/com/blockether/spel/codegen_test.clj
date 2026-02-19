(ns com.blockether.spel.codegen-test
  "Tests for JSONL → Clojure codegen.

   Two bugs fixed:
   1. wrap-signals used ->> which swapped action-code/action args → ClassCastException
   2. locator-from-map didn't handle Playwright 1.58+ {:kind :body} locator format"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.codegen :as sut]
   [com.blockether.spel.allure :refer [defdescribe expect it]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- codegen
  "Run codegen with *exit-on-error* false, return string."
  ([jsonl] (codegen jsonl :body))
  ([jsonl fmt]
   (binding [sut/*exit-on-error* false]
     (sut/jsonl-str->clojure jsonl {:format fmt}))))

(defn- codegen-throws
  "Run codegen expecting an exception, return it."
  [jsonl]
  (try
    (binding [sut/*exit-on-error* false]
      (sut/jsonl-str->clojure jsonl))
    nil
    (catch Exception e e)))

(def ^:private example-com-jsonl
  "The EXACT JSONL from the bug report that caused ClassCastException."
  (str/join "\n"
    ["{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":false},\"contextOptions\":{},\"generateAutoExpect\":true}"
     "{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@07f352880dafdf485e2be771e2e1ee43\",\"pageAlias\":\"page\",\"framePath\":[]}"
     "{\"name\":\"navigate\",\"url\":\"https://example.com/\",\"signals\":[],\"pageGuid\":\"page@07f352880dafdf485e2be771e2e1ee43\",\"pageAlias\":\"page\",\"framePath\":[]}"
     "{\"name\":\"assertText\",\"selector\":\"internal:role=heading\",\"signals\":[],\"text\":\"Example Domain\",\"substring\":true,\"pageGuid\":\"page@07f352880dafdf485e2be771e2e1ee43\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"heading\",\"options\":{\"attrs\":[]}}}"
     "{\"name\":\"closePage\",\"signals\":[],\"pageGuid\":\"page@07f352880dafdf485e2be771e2e1ee43\",\"pageAlias\":\"page\",\"framePath\":[]}"]))

;; =============================================================================
;; Full Recording — :body format (exact output)
;; =============================================================================

(defdescribe body-format-test
  "Exact output verification for :body format"

  (it "produces exact output for example.com recording"
    (let [result (codegen example-com-jsonl :body)]
      (expect (= result
                (str ";; New page: pg\n"
                  "(page/navigate pg \"https://example.com/\")\n"
                  "(assert/contains-text (assert/assert-that (page/get-by-role pg role/heading)) \"Example Domain\")\n"
                  "(core/close-page! pg)")))))

  (it "produces exact navigate action"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"navigate\",\"url\":\"https://example.com/path\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(page/navigate pg \"https://example.com/path\")"))))

  (it "produces exact closePage action"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"closePage\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(core/close-page! pg)"))))

  (it "produces exact openPage comment for about:blank"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result ";; New page: pg"))))

  (it "produces openPage with navigate for non-blank URL"
      ;; NOTE: action->raw-code for openPage hard-codes 10-space indent on the navigate line
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"openPage\",\"url\":\"https://example.com\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result (str ";; New page: pg\n"
                          "          (page/navigate pg \"https://example.com\")")))))

  (it "produces exact click action"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"button.submit\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/locator pg \"button.submit\"))"))))

  (it "produces exact dblclick for clickCount=2"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"button\",\"clickCount\":2,\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/dblclick (page/locator pg \"button\"))"))))

  (it "produces exact fill action"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"fill\",\"selector\":\"input[name=email]\",\"text\":\"test@example.com\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/fill (page/locator pg \"input[name=email]\") \"test@example.com\")"))))

  (it "produces exact assertText with substring=true"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Hello World\",\"substring\":true,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/contains-text (assert/assert-that (page/locator pg \"h1\")) \"Hello World\")"))))

  (it "produces exact assertText with substring=false"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Exact Title\",\"substring\":false,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/has-text (assert/assert-that (page/locator pg \"h1\")) \"Exact Title\")"))))

  (it "produces exact assertVisible"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertVisible\",\"selector\":\".modal\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/is-visible (assert/assert-that (page/locator pg \".modal\")))"))))

  (it "produces exact assertChecked"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertChecked\",\"selector\":\"#agree\",\"checked\":true,\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/is-checked (assert/assert-that (page/locator pg \"#agree\")))")))))

;; =============================================================================
;; Full Recording — :test format (exact output)
;; =============================================================================

(defdescribe test-format-test
  "Exact output verification for :test format"

  (it "produces exact output for example.com recording"
    (let [result (codegen example-com-jsonl :test)]
      (expect (= result
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
                  "   [com.blockether.spel.roles :as role]\n"
                  "   [com.blockether.spel.allure :refer [defdescribe it expect]]))\n"
                  "\n"
                  "(defdescribe generated-test\n"
                  "  (it \"recorded test\"\n"
                  "    (core/with-playwright [pw]\n"
                  "      (core/with-browser [browser (core/launch-chromium pw {:headless false})]\n"
                  "        (core/with-context [ctx (core/new-context browser)]\n"
                  "          (core/with-page [pg (core/new-page-from-context ctx)]\n"
                  "          ;; New page: pg\n"
                  "          (page/navigate pg \"https://example.com/\")\n"
                  "          (assert/contains-text (assert/assert-that (page/get-by-role pg role/heading)) \"Example Domain\")\n"
                  "          (core/close-page! pg))))))\n"))))))

;; =============================================================================
;; Full Recording — :script format (exact output)
;; =============================================================================

(defdescribe script-format-test
  "Exact output verification for :script format"

  (it "produces exact output for example.com recording"
    (let [result (codegen example-com-jsonl :script)]
      (expect (= result
                (str
                  ";; Auto-generated by spel codegen\n"
                  "\n"
                  "(require '[com.blockether.spel.assertions :as assert])\n"
                  "(require '[com.blockether.spel.core :as core])\n"
                  "(require '[com.blockether.spel.locator :as locator])\n"
                  "(require '[com.blockether.spel.page :as page])\n"
                  "(require '[com.blockether.spel.roles :as role])\n"
                  "\n"
                  "(core/with-playwright [pw]\n"
                  "  (core/with-browser [browser (core/launch-chromium pw {:headless false})]\n"
                  "    (core/with-context [ctx (core/new-context browser)]\n"
                  "      (core/with-page [pg (core/new-page-from-context ctx)]\n"
                  "      ;; New page: pg\n"
                  "      (page/navigate pg \"https://example.com/\")\n"
                  "      (assert/contains-text (assert/assert-that (page/get-by-role pg role/heading)) \"Example Domain\")\n"
                  "      (core/close-page! pg)))))\n"))))))

;; =============================================================================
;; Signal Handling (exact output)
;; =============================================================================

(defdescribe signal-handling-test
  "Exact output for signal wrapping — the original bug site"

  (it "dialog signal: on-dialog BEFORE click, exact output"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"button\",\"signals\":[{\"name\":\"dialog\"}],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result
                (str "(page/on-dialog pg (fn [dialog] (.dismiss dialog)))\n"
                  "(locator/click (page/locator pg \"button\"))")))))

  (it "popup signal: waitForPopup wrapping click, exact output"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"a.link\",\"signals\":[{\"name\":\"popup\"}],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result
                (str "(let [popup-pg (page/wait-for-popup pg"
                  " (fn [] (locator/click (page/locator pg \"a.link\"))))]\n"
                  "  ;; popup-pg is now available for further actions)")))))

  (it "download signal: waitForDownload wrapping click, exact output"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"a.dl\",\"signals\":[{\"name\":\"download\"}],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result
                (str "(let [download (page/wait-for-download pg"
                  " (fn [] (locator/click (page/locator pg \"a.dl\"))))]\n"
                  "  ;; download is now available - (.path download), (.suggestedFilename download)"
                  ")"))))))

;; =============================================================================
;; Locator Formats
;; =============================================================================

(defdescribe locator-format-test
  "Tests for different locator input formats"

  (it "handles Playwright 1.58+ {:kind :body} locator map"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"internal:role=heading\",\"signals\":[],\"text\":\"Hello\",\"substring\":true,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"heading\",\"options\":{\"attrs\":[]}}}"
          result (codegen jsonl)]
      (expect (= result "(assert/contains-text (assert/assert-that (page/get-by-role pg role/heading)) \"Hello\")"))))

  (it "handles internal:role selector fallback"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=button\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button))"))))

  (it "handles internal:text selector"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:text=\\\"Submit\\\"\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-text pg \"Submit\"))"))))

  (it "handles {:role ...} locator map"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"role\":\"button\"}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button))"))))

  (it "handles 1.58+ default kind (CSS selector)"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"div\",\"signals\":[],\"text\":\"Learn more\",\"substring\":true,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"default\",\"body\":\"div\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(assert/contains-text (assert/assert-that (page/locator pg \"div\")) \"Learn more\")"))))

  (it "handles 1.58+ css kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"css\",\"body\":\"#submit-btn\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/locator pg \"#submit-btn\"))"))))

  (it "handles 1.58+ text kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"text\",\"body\":\"Submit\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-text pg \"Submit\"))"))))

  (it "handles 1.58+ label kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"fill\",\"text\":\"user@test.com\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"label\",\"body\":\"Email\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/fill (page/get-by-label pg \"Email\") \"user@test.com\")"))))

  (it "handles 1.58+ placeholder kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"fill\",\"text\":\"search\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"placeholder\",\"body\":\"Search...\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/fill (page/get-by-placeholder pg \"Search...\") \"search\")"))))

  (it "handles 1.58+ testid kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"testid\",\"body\":\"login-btn\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-test-id pg \"login-btn\"))"))))

  (it "handles 1.58+ alt kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"alt\",\"body\":\"Company logo\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-alt-text pg \"Company logo\"))"))))

  (it "handles 1.58+ title kind"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"title\",\"body\":\"Close dialog\",\"options\":{}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-title pg \"Close dialog\"))"))))

  (it "handles 1.58+ role kind with named attr in attrs array (legacy)"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"button\",\"options\":{\"attrs\":[{\"name\":\"name\",\"value\":\"Submit\"}]}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button {:name \"Submit\"}))")))))

;; =============================================================================
;; Role Name & Exact Flag (the real recording format)
;; =============================================================================

(defdescribe role-name-exact-test
  "Tests for role locators with :name and :exact options"

  (it "extracts name from options.name (Playwright 1.58+ JSONL format)"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=link[name=\\\"Sign in\\\"i]\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"link\",\"options\":{\"attrs\":[],\"exact\":false,\"name\":\"Sign in\"}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/link {:name \"Sign in\"}))"))))

  (it "passes :exact true when options.exact is true"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=button[name=\\\"Sign in\\\"s]\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"button\",\"options\":{\"attrs\":[],\"exact\":true,\"name\":\"Sign in\"}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button {:name \"Sign in\", :exact true}))"))))

  (it "omits :exact when options.exact is false"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"textbox\",\"options\":{\"attrs\":[],\"exact\":false,\"name\":\"Username\"}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/textbox {:name \"Username\"}))"))))

  (it "role without name or exact generates plain get-by-role"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"alert\",\"options\":{\"attrs\":[]}}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/alert))"))))

  (it "internal:role selector with name uses get-by-role {:name}"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=navigation[name=\\\"Platform\\\"i]\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/navigation {:name \"Platform\"}))"))))

  (it "internal:role selector with exact suffix 's' adds :exact true"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=button[name=\\\"Submit\\\"s]\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button {:name \"Submit\", :exact true}))"))))

  (it "assertVisible with named role wraps assert-that"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertVisible\",\"selector\":\"internal:role=navigation[name=\\\"Platform\\\"i]\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"navigation\",\"options\":{\"attrs\":[],\"exact\":false,\"name\":\"Platform\"}}}"
          result (codegen jsonl)]
      (expect (= result "(assert/is-visible (assert/assert-that (page/get-by-role pg role/navigation {:name \"Platform\"})))"))))

  (it "assertText with named role wraps assert-that"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"internal:role=alert\",\"signals\":[],\"text\":\"Error occurred\",\"substring\":true,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"kind\":\"role\",\"body\":\"alert\",\"options\":{\"attrs\":[]}}}"
          result (codegen jsonl)]
      (expect (= result "(assert/contains-text (assert/assert-that (page/get-by-role pg role/alert)) \"Error occurred\")"))))

  (it "legacy {:role :name} locator map uses get-by-role {:name}"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"role\":\"button\",\"name\":\"Submit\"}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/button {:name \"Submit\"}))"))))

  (it "legacy {:role :name :exact true} locator passes :exact true"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"role\":\"link\",\"name\":\"Home\",\"exact\":true}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg role/link {:name \"Home\", :exact true}))")))))

;; =============================================================================
;; Browser Name / Headless Options
;; =============================================================================

(defdescribe header-options-test
  "Tests for header options affecting output"

  (it "firefox browser uses core/launch-firefox in :test format"
    (let [jsonl "{\"browserName\":\"firefox\"}\n{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl :test)]
      (expect (str/includes? result "core/launch-firefox"))
      (expect (not (str/includes? result "core/launch-chromium")))))

  (it "webkit browser uses core/launch-webkit in :script format"
    (let [jsonl "{\"browserName\":\"webkit\"}\n{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl :script)]
      (expect (str/includes? result "core/launch-webkit"))
      (expect (not (str/includes? result "core/launch-chromium")))))

  (it "headless:true produces {:headless true}"
    (let [jsonl "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":true}}\n{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl :test)]
      (expect (str/includes? result "{:headless true}"))))

  (it "headless:false produces {:headless false}"
    (let [jsonl "{\"browserName\":\"chromium\",\"launchOptions\":{\"headless\":false}}\n{\"name\":\"openPage\",\"url\":\"about:blank\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl :test)]
      (expect (str/includes? result "{:headless false}")))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(defdescribe edge-cases-test
  "Error handling and edge cases"

  (it "throws on empty input with 'Empty JSONL' message"
    (let [ex (codegen-throws "")]
      (expect (some? ex))
      (expect (str/includes? (ex-message ex) "Empty JSONL"))))

  (it "throws on header-only input with 'no actions' message"
    (let [ex (codegen-throws "{\"browserName\":\"chromium\"}")]
      (expect (some? ex))
      (expect (str/includes? (ex-message ex) "no actions"))))

  (it "throws on unknown action with action name in message"
    (let [ex (codegen-throws "{\"browserName\":\"chromium\"}\n{\"name\":\"magicAction\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}")]
      (expect (some? ex))
      (expect (str/includes? (ex-message ex) "magicAction"))))

  (it "throws on unknown signal"
    (let [ex (codegen-throws "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"button\",\"signals\":[{\"name\":\"teleport\"}],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}")]
      (expect (some? ex))
      (expect (str/includes? (ex-message ex) "teleport")))))
