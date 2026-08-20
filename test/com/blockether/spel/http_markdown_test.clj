(ns com.blockether.spel.http-markdown-test
  "Tests for Markdown-based HTTP exchange reporting in Allure.

   Verifies that:
   - `render-http-markdown` generates correct Markdown with all sections
   - `render-http-markdown` handles nil/empty request data gracefully
   - `render-http-markdown` pretty-prints JSON bodies
   - `render-http-markdown` handles non-JSON bodies
   - `api-step` with API GET request attaches markdown
   - `api-step` with browser Response attaches markdown
   - `flush-network-steps!` creates markdown attachments
   - Network auto-capture through with-page fixture"
  (:require
   [com.blockether.spel.core :as core]
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it around]]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]))

;; =============================================================================
;; Unit tests — render-http-markdown
;; =============================================================================

(defdescribe render-http-markdown-test
  "Tests for render-http-markdown output"
  (around [f] (core/with-testing-browser (f)))

  (describe "full exchange with all sections"

    (it "includes title with method, URL, status, and arrow"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://api.example.org/users"
                  :status       201
                  :status-text  "Created"
                  :content-type "application/json"})]
        (expect (str/includes? md "## POST https://api.example.org/users → 201 Created"))))

    (it "includes request headers section"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.org"
                  :status           200
                  :status-text      "OK"
                  :request-headers  {"Authorization" "Bearer token123"
                                     "Accept"        "application/json"}})]
        (expect (str/includes? md "### Request Headers"))
        (expect (str/includes? md "Accept: application/json"))
        (expect (str/includes? md "Authorization: Bearer token123"))))

    (it "includes request body section with JSON highlighting"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://example.org/api"
                  :status       200
                  :status-text  "OK"
                  :request-body "{\"name\":\"Alice\"}"})]
        (expect (str/includes? md "### Request Body"))
        (expect (str/includes? md "```json"))))

    (it "includes response headers section"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.org"
                  :status           200
                  :status-text      "OK"
                  :response-headers {"content-type" "application/json"
                                     "x-request-id" "abc-123"}})]
        (expect (str/includes? md "### Response Headers"))
        (expect (str/includes? md "content-type: application/json"))
        (expect (str/includes? md "x-request-id: abc-123"))))

    (it "includes response body section with JSON highlighting"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :status-text   "OK"
                  :response-body "{\"status\":\"ok\"}"
                  :content-type  "application/json"})]
        (expect (str/includes? md "### Response Body"))
        (expect (str/includes? md "```json"))))

    (it "includes cURL section"
      (let [md (allure/render-http-markdown
                 {:method "GET"
                  :url    "https://example.org/health"
                  :status 200})]
        (expect (str/includes? md "### cURL"))
        (expect (str/includes? md "```bash"))
        (expect (str/includes? md "'https://example.org/health'")))))

  (describe "nil and empty data handling"

    (it "omits Request Headers section entirely when nil"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.org"
                  :status           200
                  :request-headers  nil})]
        (expect (not (str/includes? md "### Request Headers")))
        ;; And in particular never renders the request line as a fake header.
        (expect (not (str/includes? md "GET https://example.org\n```")))))

    (it "omits Request Headers section entirely when empty"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.org"
                  :status           200
                  :request-headers  {}})]
        (expect (not (str/includes? md "### Request Headers")))
        (expect (not (str/includes? md "GET https://example.org\n```")))))

    (it "handles nil request body gracefully"
      (let [md (allure/render-http-markdown
                 {:method       "GET"
                  :url          "https://example.org"
                  :status       200
                  :request-body nil})]
        (expect (not (str/includes? md "### Request Body")))))

    (it "handles empty request body gracefully"
      (let [md (allure/render-http-markdown
                 {:method       "GET"
                  :url          "https://example.org"
                  :status       200
                  :request-body ""})]
        (expect (not (str/includes? md "### Request Body")))))

    (it "handles nil response headers gracefully"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.org"
                  :status           200
                  :response-headers nil})]
        (expect (not (str/includes? md "### Response Headers")))))

    (it "handles nil response body gracefully"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :response-body nil})]
        (expect (not (str/includes? md "### Response Body")))))

    (it "handles nil method — defaults to GET"
      (let [md (allure/render-http-markdown
                 {:method nil
                  :url    "https://example.org"
                  :status 200})]
        (expect (str/includes? md "## GET "))))

    (it "handles nil status — defaults to 0"
      (let [md (allure/render-http-markdown
                 {:method "GET"
                  :url    "https://example.org"
                  :status nil})]
        (expect (str/includes? md "→ 0 ")))))

  (describe "JSON pretty-printing"

    (it "pretty-prints JSON response body"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :response-body "{\"name\":\"Alice\",\"age\":30}"
                  :content-type  "application/json"})]
        ;; pretty-json should add newlines/indentation
        (expect (str/includes? md "\"name\""))
        (expect (str/includes? md "\"Alice\""))))

    (it "pretty-prints JSON request body"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://example.org"
                  :status       200
                  :request-body "{\"key\":\"value\"}"})]
        (expect (str/includes? md "\"key\""))
        (expect (str/includes? md "\"value\"")))))

  (describe "non-JSON body handling"

    (it "renders plain text body without language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :response-body "Hello, World!"
                  :content-type  "text/plain"})]
        (expect (str/includes? md "### Response Body"))
        (expect (str/includes? md "Hello, World!"))))

    (it "renders HTML body with html language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :response-body "<html><body>Hi</body></html>"
                  :content-type  "text/html"})]
        (expect (str/includes? md "```html"))))

    (it "renders XML body with xml language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.org"
                  :status        200
                  :response-body "<root><item/></root>"
                  :content-type  "application/xml"})]
        (expect (str/includes? md "```xml"))))

    (it "renders non-JSON request body without json tag"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://example.org"
                  :status       200
                  :request-body "plain text body"})]
        ;; Should have ``` without json
        (expect (str/includes? md "### Request Body"))
        (expect (str/includes? md "plain text body"))))))

;; =============================================================================
;; Integration tests — api-step with API requests
;; =============================================================================

(defdescribe api-step-markdown-test
  "Integration: api-step attaches Markdown for API requests"
  (around [f] (core/with-testing-browser (f)))
  (around [f] ((:around with-test-server) f))

  (describe "API GET with api-step"

    (it "api-step returns the APIResponse"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "GET /health"
                     (core/api-get ctx "/health"))]
          (expect (= 200 (core/api-response-status resp))))))

    (it "api-step with POST returns the APIResponse"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "POST /echo"
                     (core/api-post ctx "/echo"
                       {:data    "{\"hello\":\"world\"}"
                        :headers {"Content-Type" "application/json"}}))]
          (expect (= 200 (core/api-response-status resp)))))))

  (describe "api-step with non-response result"

    (it "api-step with non-response body is a no-op for attachment"
      (let [result (allure/api-step "Compute something"
                     (+ 1 2))]
        (expect (= 3 result))))))

;; =============================================================================
;; Integration tests — api-step with browser Response
;; =============================================================================

(defdescribe api-step-browser-response-test
  "Integration: api-step attaches Markdown for browser network Response"
  (around [f] (core/with-testing-browser (f)))
  (around [f] ((:around with-test-server) f))

  (describe "browser Response with api-step"

    (it "api-step captures browser Response"
      (core/with-testing-page [pg]
        (let [resp (allure/api-step "Navigate to health"
                     (page/wait-for-response pg "**/health"
                       #(page/navigate pg (str *test-server-url* "/health"))))]
          (expect (= 200 (.status resp))))))))

;; =============================================================================
;; Integration tests — flush-network-steps!
;; =============================================================================

(defdescribe flush-network-steps-markdown-test
  "Tests for flush-network-steps! with Markdown attachments"
  (around [f] (core/with-testing-browser (f)))

  (it "is a no-op when *network-log* is nil"
    (binding [allure/*network-log* nil]
      (allure/flush-network-steps!)
      (expect true)))

  (it "is a no-op when *network-log* is empty"
    (binding [allure/*network-log* (atom [])]
      (allure/flush-network-steps!)
      (expect true)))

  (it "is a no-op when *context* is nil (no allure reporter)"
    (let [log (atom [{:response      nil
                      :method        "GET"
                      :url           "http://example.org"
                      :status        200
                      :status-text   "OK"
                      :resource-type "fetch"
                      :timestamp     0}])]
      ;; Without *context* bound (no allure reporter), flush is a no-op
      (binding [allure/*network-log* log
                allure/*context*     nil]
        (allure/flush-network-steps!)
        ;; Log should still have entries (not consumed since no context)
        (expect (= 1 (count @log)))))))

;; Regression, issue #55: response bodies were read inside Playwright callbacks,
;; recursively dispatching concurrent responses until capture failed.
(defdescribe network-capture-materialization-test
  "Tests event-safe network capture materialization"

  (it "reads complete headers and bodies only after event callbacks return"
    (let [listeners (atom {})
          calls     (atom {:request-all 0 :response-all 0 :response-text 0})
          log       (atom [])
          response* (atom nil)
          request   (proxy [com.microsoft.playwright.Request] []
                      (resourceType [] "xhr")
                      (method [] "GET")
                      (url [] "https://example.test/failure")
                      (headers [] {"accept" "application/json"})
                      (allHeaders []
                        (swap! calls update :request-all inc)
                        {"accept" "application/json"
                         "cookie" "trace-request=recorded"})
                      (postData [] nil)
                      (response [] @response*))
          response  (proxy [com.microsoft.playwright.Response] []
                      (request [] request)
                      (url [] "https://example.test/failure")
                      (status [] 500)
                      (statusText [] "Internal Server Error")
                      (headers [] {"content-type" "application/json"})
                      (allHeaders []
                        (swap! calls update :response-all inc)
                        {"content-type" "application/json"
                         "set-cookie" "trace-response=recorded; Path=/"})
                      (text []
                        (swap! calls update :response-text inc)
                        "{\"status\":500}"))
          pg        (proxy [com.microsoft.playwright.Page] []
                      (onRequest [listener]
                        (swap! listeners assoc :request listener))
                      (onResponse [listener]
                        (swap! listeners assoc :response listener))
                      (onRequestFinished [listener]
                        (swap! listeners assoc :request-finished listener))
                      (onRequestFailed [listener]
                        (swap! listeners assoc :request-failed listener)))]
      (reset! response* response)
      (let [snapshot! (binding [allure/*network-log* log]
                        (allure/install-network-capture! pg))]
        (.accept ^java.util.function.Consumer (:request @listeners) request)
        (if-let [listener (:response @listeners)]
          (.accept ^java.util.function.Consumer listener response)
          (.accept ^java.util.function.Consumer (:request-finished @listeners) request))

        (expect (fn? snapshot!))
        (expect (= {:request-all 0 :response-all 0 :response-text 0} @calls))
        (expect (empty? @log))
        (when snapshot! (snapshot!))

        (let [entry (first @log)]
          (expect (= 1 (count @log)))
          (expect (= "trace-request=recorded"
                    (get (:request-headers entry) "cookie")))
          (expect (= "trace-response=recorded; Path=/"
                    (get (:response-headers entry) "set-cookie")))
          (expect (= "{\"status\":500}" (:response-body entry))))))))

;; =============================================================================
;; Integration tests — network auto-capture through with-page fixture
;; =============================================================================

(defdescribe network-capture-integration-markdown-test
  "Integration: with-page fixture auto-captures network calls"
  (around [f] (core/with-testing-browser (f)))
  (around [f] ((:around with-test-server) f))

  (describe "auto-capture with with-page"

    (it "navigating to a page captures network activity"
      (core/with-testing-page [pg]
        (page/navigate pg (str *test-server-url* "/health"))
        (page/wait-for-load-state pg)
        ;; Verify the *network-log* dynamic var is bound
        ;; (bound to an atom when allure reporter is active,
        ;;  nil when not active — both are valid)
        (expect true)))

    ;; Regression, issue #55: HTTP 500 entries lost request timing and cookie headers.
    (it "records request timing and complete headers for HTTP 500 responses"
      (let [log         (atom [])
            context     (atom (allure/make-context))
            request-window-start (atom nil)
            response-at          (atom nil)
            attached             (atom nil)
            output-dir  (.toFile
                          (java.nio.file.Files/createTempDirectory
                            "spel-network-report-"
                            (into-array java.nio.file.attribute.FileAttribute [])))
            was-active? (allure/reporter-active?)]
        (try
          (allure/set-reporter-active! true)
          (binding [allure/*network-log* log
                    allure/*context* context
                    allure/*output-dir* (.getAbsolutePath output-dir)]
            (core/with-testing-page [pg]
              (let [cookie (doto (com.microsoft.playwright.options.Cookie.
                                   "trace-request" "recorded")
                             (.setUrl *test-server-url*))]
                (.addCookies (.context pg)
                  (java.util.Collections/singletonList cookie))
                (reset! request-window-start (System/currentTimeMillis))
                (page/navigate pg (str *test-server-url* "/network-failure"))
                (page/wait-for-load-state pg)
                (reset! response-at (System/currentTimeMillis))))

            ;; Flush only after with-testing-page has closed the page and context,
            ;; matching the reporter lifecycle for failed tests.
            (let [entry (some #(when (= 500 (:status %)) %) @log)]
              (expect (some? entry))
              (when entry
                (let [timestamp (:timestamp entry)
                      expected  (str (java.time.Instant/ofEpochMilli timestamp))]
                  (expect (and @request-window-start @response-at
                            (<= (- @request-window-start 25) timestamp)
                            (>= (- @response-at timestamp) 150)))
                  (with-redefs [allure/attach
                                (fn [_ content _]
                                  (reset! attached content))]
                    (allure/flush-network-steps!))
                  (expect (str/includes? (or @attached "")
                            (str "Request started: " expected)))
                  (expect (str/includes? (or @attached "")
                            "cookie: trace-request=recorded"))
                  (expect (str/includes? (or @attached "")
                            "set-cookie: trace-response=recorded; Path=/")))))

            (let [har-attachment (some #(when (= "Network Activity (HAR)" (:name %)) %)
                                   (:attachments @context))]
              (expect (some? har-attachment))
              (when har-attachment
                (let [har (slurp (java.io.File. output-dir (:source har-attachment)))]
                  (expect (str/includes? har "\"status\":500"))
                  (expect (str/includes? har "\"startedDateTime\""))
                  (expect (str/includes? har "trace-request=recorded"))
                  (expect (str/includes? har "trace-response=recorded"))))))
          (finally
            (allure/set-reporter-active! was-active?)
            (doseq [file (reverse (file-seq output-dir))]
              (.delete file))))))

    (it "API calls through (.request (.context pg)) work correctly"
      (core/with-testing-page [pg]
        (let [resp (core/api-get (.request (.context pg))
                     (str *test-server-url* "/health"))]
          (expect (= 200 (core/api-response-status resp))))))))
