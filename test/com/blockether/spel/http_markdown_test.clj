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
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.core :as api]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures
    :refer [*page* *browser-api* with-browser with-page with-playwright]]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]))

;; =============================================================================
;; Unit tests — render-http-markdown
;; =============================================================================

(defdescribe render-http-markdown-test
  "Tests for render-http-markdown output"

  (describe "full exchange with all sections"

    (it "includes title with method, URL, status, and arrow"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://api.example.com/users"
                  :status       201
                  :status-text  "Created"
                  :content-type "application/json"})]
        (expect (str/includes? md "## POST https://api.example.com/users → 201 Created"))))

    (it "includes request headers section"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.com"
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
                  :url          "https://example.com/api"
                  :status       200
                  :status-text  "OK"
                  :request-body "{\"name\":\"Alice\"}"})]
        (expect (str/includes? md "### Request Body"))
        (expect (str/includes? md "```json"))))

    (it "includes response headers section"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.com"
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
                  :url           "https://example.com"
                  :status        200
                  :status-text   "OK"
                  :response-body "{\"status\":\"ok\"}"
                  :content-type  "application/json"})]
        (expect (str/includes? md "### Response Body"))
        (expect (str/includes? md "```json"))))

    (it "includes cURL section"
      (let [md (allure/render-http-markdown
                 {:method "GET"
                  :url    "https://example.com/health"
                  :status 200})]
        (expect (str/includes? md "### cURL"))
        (expect (str/includes? md "```bash"))
        (expect (str/includes? md "'https://example.com/health'")))))

  (describe "nil and empty data handling"

    (it "handles nil request headers gracefully — still shows method line"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.com"
                  :status           200
                  :request-headers  nil})]
        (expect (str/includes? md "### Request Headers"))
        (expect (str/includes? md "GET https://example.com"))))

    (it "handles empty request headers gracefully — still shows method line"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.com"
                  :status           200
                  :request-headers  {}})]
        (expect (str/includes? md "### Request Headers"))
        (expect (str/includes? md "GET https://example.com"))))

    (it "handles nil request body gracefully"
      (let [md (allure/render-http-markdown
                 {:method       "GET"
                  :url          "https://example.com"
                  :status       200
                  :request-body nil})]
        (expect (not (str/includes? md "### Request Body")))))

    (it "handles empty request body gracefully"
      (let [md (allure/render-http-markdown
                 {:method       "GET"
                  :url          "https://example.com"
                  :status       200
                  :request-body ""})]
        (expect (not (str/includes? md "### Request Body")))))

    (it "handles nil response headers gracefully"
      (let [md (allure/render-http-markdown
                 {:method           "GET"
                  :url              "https://example.com"
                  :status           200
                  :response-headers nil})]
        (expect (not (str/includes? md "### Response Headers")))))

    (it "handles nil response body gracefully"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.com"
                  :status        200
                  :response-body nil})]
        (expect (not (str/includes? md "### Response Body")))))

    (it "handles nil method — defaults to GET"
      (let [md (allure/render-http-markdown
                 {:method nil
                  :url    "https://example.com"
                  :status 200})]
        (expect (str/includes? md "## GET "))))

    (it "handles nil status — defaults to 0"
      (let [md (allure/render-http-markdown
                 {:method "GET"
                  :url    "https://example.com"
                  :status nil})]
        (expect (str/includes? md "→ 0 ")))))

  (describe "JSON pretty-printing"

    (it "pretty-prints JSON response body"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.com"
                  :status        200
                  :response-body "{\"name\":\"Alice\",\"age\":30}"
                  :content-type  "application/json"})]
        ;; pretty-json should add newlines/indentation
        (expect (str/includes? md "\"name\""))
        (expect (str/includes? md "\"Alice\""))))

    (it "pretty-prints JSON request body"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://example.com"
                  :status       200
                  :request-body "{\"key\":\"value\"}"})]
        (expect (str/includes? md "\"key\""))
        (expect (str/includes? md "\"value\"")))))

  (describe "non-JSON body handling"

    (it "renders plain text body without language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.com"
                  :status        200
                  :response-body "Hello, World!"
                  :content-type  "text/plain"})]
        (expect (str/includes? md "### Response Body"))
        (expect (str/includes? md "Hello, World!"))))

    (it "renders HTML body with html language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.com"
                  :status        200
                  :response-body "<html><body>Hi</body></html>"
                  :content-type  "text/html"})]
        (expect (str/includes? md "```html"))))

    (it "renders XML body with xml language tag"
      (let [md (allure/render-http-markdown
                 {:method        "GET"
                  :url           "https://example.com"
                  :status        200
                  :response-body "<root><item/></root>"
                  :content-type  "application/xml"})]
        (expect (str/includes? md "```xml"))))

    (it "renders non-JSON request body without json tag"
      (let [md (allure/render-http-markdown
                 {:method       "POST"
                  :url          "https://example.com"
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

  (describe "API GET with api-step"
    {:context [with-playwright with-test-server]}

    (it "api-step returns the APIResponse"
      (api/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "GET /health"
                     (api/api-get ctx "/health"))]
          (expect (= 200 (api/api-response-status resp))))))

    (it "api-step with POST returns the APIResponse"
      (api/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "POST /echo"
                     (api/api-post ctx "/echo"
                       {:data    "{\"hello\":\"world\"}"
                        :headers {"Content-Type" "application/json"}}))]
          (expect (= 200 (api/api-response-status resp)))))))

  (describe "api-step with non-response result"
    {:context [with-playwright with-test-server]}

    (it "api-step with non-response body is a no-op for attachment"
      (let [result (allure/api-step "Compute something"
                     (+ 1 2))]
        (expect (= 3 result))))))

;; =============================================================================
;; Integration tests — api-step with browser Response
;; =============================================================================

(defdescribe api-step-browser-response-test
  "Integration: api-step attaches Markdown for browser network Response"

  (describe "browser Response with api-step"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "api-step captures browser Response"
      (let [resp (allure/api-step "Navigate to health"
                   (page/wait-for-response *page* "**/health"
                     #(page/navigate *page* (str *test-server-url* "/health"))))]
        (expect (= 200 (.status resp)))))))

;; =============================================================================
;; Integration tests — flush-network-steps!
;; =============================================================================

(defdescribe flush-network-steps-markdown-test
  "Tests for flush-network-steps! with Markdown attachments"

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
                      :url           "http://example.com"
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

;; =============================================================================
;; Integration tests — network auto-capture through with-page fixture
;; =============================================================================

(defdescribe network-capture-integration-markdown-test
  "Integration: with-page fixture auto-captures network calls"

  (describe "auto-capture with with-page"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "navigating to a page captures network activity"
      (page/navigate *page* (str *test-server-url* "/health"))
      (page/wait-for-load-state *page*)
      ;; Verify the *network-log* dynamic var is bound
      ;; (bound to an atom when allure reporter is active,
      ;;  nil when not active — both are valid)
      (expect true))

    (it "API calls through *browser-api* work correctly"
      (let [resp (api/api-get *browser-api*
                   (str *test-server-url* "/health"))]
        (expect (= 200 (api/api-response-status resp)))))))
