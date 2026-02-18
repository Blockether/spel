(ns com.blockether.spel.codegen-test
  "Tests for JSONL → Clojure codegen.

   Two bugs fixed:
   1. wrap-signals used ->> which swapped action-code/action args → ClassCastException
   2. locator-from-map didn't handle Playwright 1.58+ {:kind :body} locator format"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.codegen :as sut]
   [lazytest.core :refer [defdescribe describe expect it]]))

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
                  "(assert/contains-text (page/get-by-role pg AriaRole/HEADING) \"Example Domain\")\n"
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
      (expect (= result "(assert/contains-text (page/locator pg \"h1\") \"Hello World\")"))))

  (it "produces exact assertText with substring=false"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertText\",\"selector\":\"h1\",\"signals\":[],\"text\":\"Exact Title\",\"substring\":false,\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/has-text (page/locator pg \"h1\") \"Exact Title\")"))))

  (it "produces exact assertVisible"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertVisible\",\"selector\":\".modal\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/is-visible (page/locator pg \".modal\"))"))))

  (it "produces exact assertChecked"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"assertChecked\",\"selector\":\"#agree\",\"checked\":true,\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(assert/is-checked (page/locator pg \"#agree\"))")))))

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
                  "   [lazytest.core :refer [defdescribe it expect]])\n"
                  "  (:import\n"
                  "   [com.microsoft.playwright Page]\n"
                  "   [com.microsoft.playwright.options AriaRole]))\n"
                  "\n"
                  "(defdescribe generated-test\n"
                  "  (it \"recorded test\"\n"
                  "    (core/with-playwright [pw]\n"
                  "      (core/with-browser [browser (core/launch-chromium pw {:headless false})]\n"
                  "        (core/with-context [ctx (core/new-context browser)]\n"
                  "          (core/with-page [pg (core/new-page-from-context ctx)]\n"
                  "          ;; New page: pg\n"
                  "          (page/navigate pg \"https://example.com/\")\n"
                  "          (assert/contains-text (page/get-by-role pg AriaRole/HEADING) \"Example Domain\")\n"
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
                  "(import '[com.microsoft.playwright Page])\n"
                  "(import '[com.microsoft.playwright.options AriaRole])\n"
                  "\n"
                  "(core/with-playwright [pw]\n"
                  "  (core/with-browser [browser (core/launch-chromium pw {:headless false})]\n"
                  "    (core/with-context [ctx (core/new-context browser)]\n"
                  "      (core/with-page [pg (core/new-page-from-context ctx)]\n"
                  "      ;; New page: pg\n"
                  "      (page/navigate pg \"https://example.com/\")\n"
                  "      (assert/contains-text (page/get-by-role pg AriaRole/HEADING) \"Example Domain\")\n"
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
                (str "(let [popup-pg (.waitForPopup ^Page pg"
                  " (reify Runnable (run [_] (locator/click (page/locator pg \"a.link\")))))]\n"
                  "  ;; popup-pg is now available for further actions)")))))

  (it "download signal: waitForDownload wrapping click, exact output"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"a.dl\",\"signals\":[{\"name\":\"download\"}],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result
                (str "(let [download (.waitForDownload ^Page pg"
                  " (reify Runnable (run [_] (locator/click (page/locator pg \"a.dl\")))))]\n"
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
      (expect (= result "(assert/contains-text (page/get-by-role pg AriaRole/HEADING) \"Hello\")"))))

  (it "handles internal:role selector fallback"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:role=button\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg AriaRole/BUTTON))"))))

  (it "handles internal:text selector"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"selector\":\"internal:text=\\\"Submit\\\"\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[]}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-text pg \"Submit\"))"))))

  (it "handles {:role ...} locator map"
    (let [jsonl "{\"browserName\":\"chromium\"}\n{\"name\":\"click\",\"signals\":[],\"pageGuid\":\"page@123\",\"pageAlias\":\"page\",\"framePath\":[],\"locator\":{\"role\":\"button\"}}"
          result (codegen jsonl)]
      (expect (= result "(locator/click (page/get-by-role pg AriaRole/BUTTON))")))))

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
