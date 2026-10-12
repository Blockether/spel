(ns com.blockether.spel.report-regression-test
  "Browser and size regressions for issue #139."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.blockether.spel.allure-reporter :as reporter]
            [com.blockether.spel.spel-allure-alternative-html-report :as report]
            [lazytest.core :refer [defdescribe it expect]])
  (:import [com.microsoft.playwright Page Playwright Playwright$CreateOptions]
           [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.io File]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-report [large? f]
  (let [dir (.toFile (Files/createTempDirectory "spel-139-" (make-array FileAttribute 0)))
        results (io/file dir "results")
        out (io/file dir "report")
        md "## POST https://gateway.example.com/Command?request-id=AbC → 200 OK\n\n### Request Body\n```json\n{}\n```\n\n### Response Body\n```json\n{}\n```\n\n### cURL\n```bash\ncurl example\n```\n"
        trace "failure\nex-data: {:id 42}\n\tat app.core$run.invoke(core.clj:12)\n\tat lazytest.runner.run(runner.clj:20)\n\tat clojure.core$apply.invoke(core.clj:30)\nCaused by: nested\n\tat app.client.call(client.clj:9)\n"]
    (try
      (.mkdirs results)
      (spit (io/file results "small.md") md)
      (spit (io/file results "note.md") "# Mixed Case\n\nA **short** note.")
      (when large?
        (doseq [name ["large-a.md" "large-b.md"]]
          (with-open [w (io/writer (io/file results name))]
            (.write w md)
            (let [chunk (str/join (repeat 1024 "<payload>&"))]
              (dotimes [_ 4800] (.write w ^String chunk)))
            (.write w "END-OF-LARGE-ATTACHMENT"))))
      (spit (io/file results "one-result.json")
        (json/write-json-str
          {"uuid" "one" "name" "HTTP integration" "status" "failed"
           "start" 1 "stop" 2 "labels" [{"name" "suite" "value" "issue139"}]
           "statusDetails" {"message" "failure" "trace" trace}
           "attachments" (into [{"name" "Poll #2" "source" "small.md" "type" "text/markdown"}
                                {"name" "Note Mixed Case" "source" "note.md" "type" "text/markdown"}]
                           (when large?
                             (concat
                               (for [name ["large-a.md" "large-b.md"]]
                                 {"name" name "source" name "type" "text/markdown"})
                               (repeat 350 {"name" "Poll #2" "source" "small.md" "type" "text/markdown"}))))}))
      (report/generate! (str results) (str out))
      (f results out)
      (finally
        (doseq [^File file (reverse (file-seq dir))] (.delete file))))))

(defn- with-report-page [out f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange exchange exchange
                file (io/file out (subs (.getPath (.getRequestURI exchange)) 1))]
            (try
              (if (.isFile file)
                (do
                  (.sendResponseHeaders exchange 200 (.length file))
                  (with-open [stream (.getResponseBody exchange)] (io/copy file stream)))
                (.sendResponseHeaders exchange 404 -1))
              (finally (.close exchange)))))))
    (.start server)
    (try
      (with-open [pw (Playwright/create
                       (.setEnv (Playwright$CreateOptions.)
                         (dissoc (into {} (System/getenv)) "NODE_OPTIONS")))
                  browser (.launch (.chromium pw))
                  page (.newPage browser)]
        (.navigate page (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/index.html"))
        (f page))
      (finally (.stop server 0)))))

;; Regression, issue #139: large HTTP bodies were copied into the report DOM.
(defdescribe bounded-attachments-test
  (it "bounds HTML while preserving complete raw files"
    (with-report true
      (fn [results out]
        (let [index (io/file out "index.html")
              html (slurp index)]
          (println "Issue #139 report bytes:" (.length index))
          (expect (< (.length index) 4000000))
          (expect (not (str/includes? html "END-OF-LARGE-ATTACHMENT")))
          (expect (str/includes? html "Preview truncated"))
          (expect (= (.length (io/file results "large-a.md"))
                    (.length (io/file out "data/attachments/large-a.md"))))
          (with-report-page out
            (fn [^Page page]
              (println "Issue #139 navigation:" (.evaluate page "JSON.stringify(performance.getEntriesByType('navigation')[0].toJSON())"))
              (expect (= 0 (.evaluate page "document.querySelectorAll('.attachment-panel[open]:not(.stacktrace-panel)').length")))
              (expect (< (long (.evaluate page "document.body.textContent.length")) 1000000))
              (expect (= 0 (.evaluate page "document.querySelectorAll('pre.language-md').length"))))))))))

;; Regression, issue #139: single mode blocked HTTP cards and changed title case.
(defdescribe report-browser-test
  (it "keeps attachments closed and HTTP cards interactive"
    (with-report false
      (fn [_ out]
        (with-report-page out
          (fn [^Page page]
            (.waitForSelector page ".http-card" (doto (com.microsoft.playwright.Page$WaitForSelectorOptions.)
                                                  (.setState com.microsoft.playwright.options.WaitForSelectorState/ATTACHED)))
            (let [actual (vec (for [script ["document.querySelectorAll('.attachment-panel[open]:not(.stacktrace-panel)').length"
                                            "getComputedStyle(document.querySelector('.http-url')).textTransform"
                                            "getComputedStyle(document.querySelector('.attachment-panel-markdown:last-of-type > summary')).textTransform"
                                            "document.querySelectorAll('.attachment-panel-markdown > summary a').length"
                                            "(() => {const cards=[...document.querySelectorAll('.http-card')]; return cards.length===3 && cards.every(c=>{c.querySelector('summary').click(); const opened=c.open; c.querySelector('summary').click(); return opened&&!c.open;});})()"
                                            "(() => {document.body.classList.remove('single-mode'); const c=document.querySelector('.test-card'); c.open=true;c.querySelector('summary').click();return !c.open;})()"
                                            "(() => {document.body.classList.add('single-mode'); const c=document.querySelector('.test-card'); c.open=true;c.querySelector('summary').click();return c.open;})()"
                                            "(() => {const p=document.querySelector('.stacktrace-panel'); return p.open && p.textContent.includes('2 framework frames') && !p.querySelector('details')?.open && !!p.querySelector('details')?.textContent.includes('lazytest.runner');})()"]]
                                (.evaluate page ^String script)))]
              (println "Issue #139 browser checks:" actual)
              (expect (= [0 "none" "none" 2 true true true true] actual)))))))))

;; Regression coverage, issue #139: compact traces must retain exception data.
(defdescribe trace-data-test
  (it "keeps exception data and cause details"
    (let [trace (#'reporter/stacktrace-str (ex-info "outer" {:id 42} (ex-info "inner" {:cause 7})))]
      (expect (str/includes? trace "{:id 42}"))
      (expect (str/includes? trace "{:cause 7}"))
      (expect (str/includes? trace "Caused by:")))))

;; Regression, issue #139: Markdown waited for the entire document and a timer.
(defdescribe streaming-markdown-test
  (it "renders a completed attachment before the document finishes"
    (with-report false
      (fn [_ out]
        (let [html (slurp (io/file out "index.html"))
              prefix (subs html 0 (str/index-of html "Note Mixed Case"))]
          (with-report-page out
            (fn [^Page page]
              (.navigate page "about:blank")
              (.evaluate page "html => {document.open(); document.write(html);}" prefix)
              (.waitForFunction page "document.readyState === 'loading' && document.querySelector('.http-card') !== null")
              (expect (= "loading" (.evaluate page "document.readyState")))
              (expect (= 3 (.evaluate page "document.querySelectorAll('.http-card').length")))
              (.evaluate page "document.close()"))))))))
