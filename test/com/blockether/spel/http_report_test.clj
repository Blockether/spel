(ns com.blockether.spel.http-report-test
  "Showcase tests for rich HTTP exchange reporting in Allure.

   These tests demonstrate the new HTML attachment panel that displays
   full request/response details with:
     - Colored method badges (GET=blue, POST=green, PUT=orange, DELETE=red)
     - Colored status code badges (2xx=green, 3xx=blue, 4xx=orange, 5xx=red)
     - Collapsible request/response headers tables
     - Syntax-highlighted JSON bodies (keys=blue, strings=green, numbers=orange)
     - Curl command for each request
     - Both request AND response details in a single panel

   Run with the Allure reporter for the full visual experience:
     clojure -M:test --namespace com.blockether.spel.http-report-test \\
       --output nested --output com.blockether.spel.allure-reporter/allure

   Then:
     allure serve allure-results"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.core :as api]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures
    :refer [*page* *pw* with-browser with-page with-playwright]]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- simple-json-encode
  "Minimal JSON encoder for tests — no external dependency."
  [data]
  (cond
    (nil? data)        "null"
    (string? data)     (str "\"" data "\"")
    (number? data)     (str data)
    (boolean? data)    (str data)
    (keyword? data)    (str "\"" (name data) "\"")
    (map? data)        (str "{"
                         (str/join ","
                           (map (fn [[k v]]
                                  (str "\"" (name k) "\":"
                                    (simple-json-encode v)))
                             data))
                         "}")
    (sequential? data) (str "["
                         (str/join "," (map simple-json-encode data))
                         "]")
    :else              (str data)))

;; =============================================================================
;; HTTP Report Showcase — GET Requests
;; =============================================================================

(defdescribe http-report-get-showcase
  "Showcase: Rich HTTP exchange panel for GET requests"

  (describe "GET requests with HTML exchange panel"
    {:context [with-playwright with-test-server]}

    (it "GET /health — JSON response with headers"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "GET Requests")
      (allure/story "Health Check with Full Exchange Details")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "get")
      (allure/description
        "Demonstrates the rich HTTP exchange panel for a simple GET request.
        Open the 'HTTP Exchange' attachment to see:
        - Blue GET method badge
        - Green 200 status badge
        - Request/response headers in collapsible tables
        - Syntax-highlighted JSON response body
        - Curl command for reproduction")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "GET /health"
                     (api/api-get ctx "/health"))]
          (allure/step "Verify response"
            (expect (= 200 (api/api-response-status resp)))
            (expect (true? (api/api-response-ok? resp)))
            (expect (str/includes? (api/api-response-text resp) "ok"))))))

    (it "GET /echo — request with query parameters and custom headers"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "GET Requests")
      (allure/story "Query Parameters & Custom Headers")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "get")
      (allure/description
        "Shows how query parameters and custom request headers appear
        in the exchange panel. The request headers table shows all headers
        sent with the request, and the curl command includes -H flags.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "GET /echo with params and headers"
                     (api/api-get ctx "/echo"
                       {:params  {:user "alice" :role "admin" :page "1" :limit "25"}
                        :headers {"Authorization" "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                                  "Accept"        "application/json"
                                  "X-Request-ID"  "req-12345-abcde"}}))]
          (allure/step "Verify echo response"
            (expect (= 200 (api/api-response-status resp)))
            (expect (str/includes? (api/api-response-text resp) "GET"))))))))

;; =============================================================================
;; HTTP Report Showcase — POST Requests
;; =============================================================================

(defdescribe http-report-post-showcase
  "Showcase: Rich HTTP exchange panel for POST requests"

  (describe "POST requests with JSON bodies"
    {:context [with-playwright with-test-server]}

    (it "POST /echo — create user with JSON body"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "POST Requests")
      (allure/story "JSON Request & Response Bodies")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "post")
      (allure/description
        "Demonstrates the exchange panel for a POST with a JSON body.
        The panel shows:
        - Green POST method badge
        - Request Body section with syntax-highlighted JSON
        - Response Body section with syntax-highlighted echo
        - Curl command with -X POST and -d flags")

      (binding [api/*json-encoder* simple-json-encode]
        (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (allure/api-step "POST /echo — create user"
                       (api/api-post ctx "/echo"
                         {:json {:name "Alice Johnson"
                                 :email "alice@example.com"
                                 :role "admin"
                                 :permissions ["read" "write" "delete"]
                                 :metadata {:department "Engineering"
                                            :team "Platform"
                                            :start-date "2024-01-15"}}}))]
            (allure/step "Verify creation response"
              (expect (= 200 (api/api-response-status resp)))
              (let [body (api/api-response-text resp)]
                (expect (str/includes? body "POST"))
                (expect (str/includes? body "Alice Johnson"))))))))

    (it "POST /echo — form-style request with raw data"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "POST Requests")
      (allure/story "Raw String Body")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "post")
      (allure/description
        "Shows the exchange panel with a raw string body (not JSON).
        The request body section displays plain text without JSON highlighting.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "POST /echo — raw data"
                     (api/api-post ctx "/echo"
                       {:data "username=alice&password=secret&remember=true"
                        :headers {"Content-Type" "application/x-www-form-urlencoded"
                                  "X-CSRF-Token" "abc123def456"}}))]
          (allure/step "Verify echo"
            (expect (= 200 (api/api-response-status resp)))))))))

;; =============================================================================
;; HTTP Report Showcase — PUT / PATCH / DELETE
;; =============================================================================

(defdescribe http-report-crud-showcase
  "Showcase: Exchange panels for PUT, PATCH, and DELETE methods"

  (describe "full CRUD lifecycle with rich panels"
    {:context [with-playwright with-test-server]}

    (it "PUT /echo — full resource update"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "CRUD Methods")
      (allure/story "PUT Update")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "put")
      (allure/description
        "Orange PUT method badge. Shows JSON request body for a full update.")

      (binding [api/*json-encoder* simple-json-encode]
        (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (allure/api-step "PUT /echo — update user"
                       (api/api-put ctx "/echo"
                         {:json {:id 42
                                 :name "Alice Johnson-Smith"
                                 :email "alice.new@example.com"
                                 :role "superadmin"
                                 :active true}}))]
            (allure/step "Verify update"
              (expect (= 200 (api/api-response-status resp)))
              (expect (str/includes? (api/api-response-text resp) "PUT")))))))

    (it "PATCH /echo — partial resource update"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "CRUD Methods")
      (allure/story "PATCH Partial Update")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "patch")
      (allure/description
        "Purple PATCH method badge. Shows a small JSON patch body.")

      (binding [api/*json-encoder* simple-json-encode]
        (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (allure/api-step "PATCH /echo — patch email"
                       (api/api-patch ctx "/echo"
                         {:json {:email "alice.updated@example.com"}
                          :headers {"If-Match" "etag-abc123"}}))]
            (allure/step "Verify patch"
              (expect (= 200 (api/api-response-status resp)))
              (expect (str/includes? (api/api-response-text resp) "PATCH")))))))

    (it "DELETE /echo — resource deletion"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "CRUD Methods")
      (allure/story "DELETE Request")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "delete")
      (allure/description
        "Red DELETE method badge. Shows request with Authorization header.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "DELETE /echo — remove resource"
                     (api/api-delete ctx "/echo"
                       {:headers {"Authorization" "Bearer admin-token-xyz"
                                  "X-Reason" "User requested deletion"}}))]
          (allure/step "Verify deletion"
            (expect (= 200 (api/api-response-status resp)))
            (expect (str/includes? (api/api-response-text resp) "DELETE"))))))))

;; =============================================================================
;; HTTP Report Showcase — Status Code Colors
;; =============================================================================

(defdescribe http-report-status-colors-showcase
  "Showcase: Status code color coding across the spectrum"

  (describe "status codes with colored badges"
    {:context [with-playwright with-test-server]}

    (it "2xx success — green status badges"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Status Codes")
      (allure/story "2xx Success Responses")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "status-codes")
      (allure/description
        "Green status badges for successful responses: 200, 201, 204.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [r200 (allure/api-step "GET /status/200 — OK"
                     (api/api-get ctx "/status/200"))
              r201 (allure/api-step "GET /status/201 — Created"
                     (api/api-get ctx "/status/201"))
              r204 (allure/api-step "GET /status/204 — No Content"
                     (api/api-get ctx "/status/204"))]
          (allure/step "Verify green badges"
            (expect (= 200 (api/api-response-status r200)))
            (expect (= 201 (api/api-response-status r201)))
            (expect (= 204 (api/api-response-status r204)))))))

    (it "4xx client errors — orange status badges"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Status Codes")
      (allure/story "4xx Client Error Responses")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "status-codes")
      (allure/description
        "Orange status badges for client errors: 400, 401, 403, 404, 429.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [r400 (allure/api-step "GET /status/400 — Bad Request"
                     (api/api-get ctx "/status/400"))
              r401 (allure/api-step "GET /status/401 — Unauthorized"
                     (api/api-get ctx "/status/401"))
              r403 (allure/api-step "GET /status/403 — Forbidden"
                     (api/api-get ctx "/status/403"))
              r404 (allure/api-step "GET /status/404 — Not Found"
                     (api/api-get ctx "/status/404"))
              r429 (allure/api-step "GET /status/429 — Too Many Requests"
                     (api/api-get ctx "/status/429"))]
          (allure/step "Verify orange badges"
            (expect (= 400 (api/api-response-status r400)))
            (expect (= 401 (api/api-response-status r401)))
            (expect (= 403 (api/api-response-status r403)))
            (expect (= 404 (api/api-response-status r404)))
            (expect (= 429 (api/api-response-status r429)))))))

    (it "5xx server errors — red status badges"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Status Codes")
      (allure/story "5xx Server Error Responses")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "status-codes")
      (allure/description
        "Red status badges for server errors: 500, 502, 503.")

      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [r500 (allure/api-step "GET /status/500 — Internal Server Error"
                     (api/api-get ctx "/status/500"))
              r502 (allure/api-step "GET /status/502 — Bad Gateway"
                     (api/api-get ctx "/status/502"))
              r503 (allure/api-step "GET /status/503 — Service Unavailable"
                     (api/api-get ctx "/status/503"))]
          (allure/step "Verify red badges"
            (expect (= 500 (api/api-response-status r500)))
            (expect (= 502 (api/api-response-status r502)))
            (expect (= 503 (api/api-response-status r503)))))))))

;; =============================================================================
;; HTTP Report Showcase — Complex JSON Bodies
;; =============================================================================

(defdescribe http-report-json-showcase
  "Showcase: Syntax-highlighted JSON in exchange panels"

  (describe "complex JSON with syntax highlighting"
    {:context [with-playwright with-test-server]}

    (it "POST /echo — deeply nested JSON object"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "JSON Bodies")
      (allure/story "Nested JSON with Syntax Highlighting")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "json")
      (allure/description
        "Shows a complex, deeply nested JSON request body with:
        - Keys highlighted in blue
        - String values in green
        - Numbers in orange
        - Booleans in purple
        - Proper indentation and formatting")

      (binding [api/*json-encoder* simple-json-encode]
        (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (allure/api-step "POST /echo — complex payload"
                       (api/api-post ctx "/echo"
                         {:json {:order {:id "ORD-2024-001"
                                         :created "2024-06-15T10:30:00Z"
                                         :customer {:name "Alice Johnson"
                                                    :email "alice@example.com"
                                                    :tier "premium"
                                                    :verified true}
                                         :items [{:sku "WIDGET-001"
                                                  :name "Super Widget"
                                                  :quantity 3
                                                  :price 29.99}
                                                 {:sku "GADGET-002"
                                                  :name "Mega Gadget"
                                                  :quantity 1
                                                  :price 149.50}]
                                         :shipping {:method "express"
                                                    :address {:street "123 Main St"
                                                              :city "San Francisco"
                                                              :state "CA"
                                                              :zip "94102"
                                                              :country "US"}}
                                         :total 239.47
                                         :paid true}}}))]
            (allure/step "Verify complex body echoed"
              (expect (= 200 (api/api-response-status resp)))
              (expect (str/includes? (api/api-response-text resp) "POST")))))))

    (it "multiple sequential API calls — exchange panel per step"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "JSON Bodies")
      (allure/story "Multiple Panels in One Test")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "multi-step")
      (allure/description
        "Each api-step generates its own HTTP Exchange panel.
        Open each step to see its individual exchange details.
        This simulates a real-world API test flow with multiple calls.")

      (binding [api/*json-encoder* simple-json-encode]
        (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                     {:base-url *test-server-url*})]

          (allure/step "Phase 1: Create resource"
            (let [resp (allure/api-step "POST /echo — create"
                         (api/api-post ctx "/echo"
                           {:json {:action "create"
                                   :name "Test Resource"
                                   :tags ["important" "v2"]}
                            :headers {"X-Idempotency-Key" "idem-123"}}))]
              (expect (= 200 (api/api-response-status resp)))))

          (allure/step "Phase 2: Read back"
            (let [resp (allure/api-step "GET /echo — read"
                         (api/api-get ctx "/echo"
                           {:params {"id" "42" "expand" "metadata"}
                            :headers {"Accept" "application/json"
                                      "Cache-Control" "no-cache"}}))]
              (expect (= 200 (api/api-response-status resp)))))

          (allure/step "Phase 3: Update"
            (let [resp (allure/api-step "PUT /echo — update"
                         (api/api-put ctx "/echo"
                           {:json {:name "Updated Resource"
                                   :version 2
                                   :active true}}))]
              (expect (= 200 (api/api-response-status resp)))))

          (allure/step "Phase 4: Cleanup"
            (let [resp (allure/api-step "DELETE /echo — delete"
                         (api/api-delete ctx "/echo"
                           {:headers {"Authorization" "Bearer cleanup-token"}}))]
              (expect (= 200 (api/api-response-status resp))))))))))

;; =============================================================================
;; HTTP Report Showcase — render-http-html unit tests
;; =============================================================================

(defdescribe http-report-html-rendering-test
  "Unit tests for the HTML rendering function"

  (it "render-http-html generates valid HTML with all sections"
    (let [html (allure/render-http-html
                 {:method           "POST"
                  :url              "https://api.example.com/users"
                  :status           201
                  :status-text      "Created"
                  :request-headers  {"Content-Type" "application/json"
                                     "Authorization" "Bearer token123"}
                  :request-body     "{\"name\":\"Alice\",\"age\":30}"
                  :response-headers {"content-type" "application/json"
                                     "x-request-id" "req-abc-123"}
                  :response-body    "{\"id\":42,\"name\":\"Alice\",\"created\":true}"
                  :content-type     "application/json"})]
      ;; Structure checks
      (expect (str/includes? html "<!DOCTYPE html>"))
      (expect (str/includes? html "<style>"))
      (expect (str/includes? html "exchange"))

      ;; Method badge
      (expect (str/includes? html "POST"))
      (expect (str/includes? html "#4CAF50"))  ;; POST = green

      ;; Status badge
      (expect (str/includes? html "201"))
      (expect (str/includes? html "Created"))

      ;; URL
      (expect (str/includes? html "api.example.com/users"))

      ;; Collapsible sections
      (expect (str/includes? html "Request Headers"))
      (expect (str/includes? html "Request Body"))
      (expect (str/includes? html "Response Headers"))
      (expect (str/includes? html "Response Body"))
      (expect (str/includes? html "Curl Command"))

      ;; Headers content
      (expect (str/includes? html "Content-Type"))
      (expect (str/includes? html "Authorization"))
      (expect (str/includes? html "x-request-id"))

      ;; JSON syntax highlighting classes
      (expect (str/includes? html "json-key"))
      (expect (str/includes? html "json-str"))

      ;; Curl command
      (expect (str/includes? html "curl"))
      (expect (str/includes? html "-X POST"))))

  (it "render-http-html handles missing request data gracefully"
    (let [html (allure/render-http-html
                 {:method           "GET"
                  :url              "https://api.example.com/health"
                  :status           200
                  :status-text      "OK"
                  :request-headers  nil
                  :request-body     nil
                  :response-headers {"content-type" "application/json"}
                  :response-body    "{\"status\":\"ok\"}"
                  :content-type     "application/json"})]
      ;; Should still render without errors
      (expect (str/includes? html "<!DOCTYPE html>"))
      (expect (str/includes? html "GET"))
      (expect (str/includes? html "200"))
      ;; No request body section when nil
      (expect (not (str/includes? html "Request Body")))))

  (it "render-http-html colors status codes correctly"
    (let [html-200 (allure/render-http-html {:status 200 :status-text "OK"})
          html-404 (allure/render-http-html {:status 404 :status-text "Not Found"})
          html-500 (allure/render-http-html {:status 500 :status-text "Internal Server Error"})]
      ;; 200 = green
      (expect (str/includes? html-200 "#4CAF50"))
      ;; 404 = orange
      (expect (str/includes? html-404 "#FF9800"))
      ;; 500 = red
      (expect (str/includes? html-500 "#f44336"))))

  (it "render-http-html handles non-JSON bodies"
    (let [html (allure/render-http-html
                 {:method       "POST"
                  :url          "https://api.example.com/submit"
                  :status       200
                  :status-text  "OK"
                  :request-body "username=alice&password=secret"
                  :response-body "<html><body>OK</body></html>"
                  :content-type "text/html"})]
      ;; Request body should be escaped as plain text (not HTML content-type)
      (expect (str/includes? html "username=alice"))
      ;; Response body is HTML — should render inline (not escaped)
      (expect (str/includes? html "html-inline"))
      (expect (str/includes? html "<html><body>OK</body></html>"))
      (expect (not (str/includes? html "srcdoc=")))))

  (it "render-http-html renders plain text bodies as pre"
    (let [html (allure/render-http-html
                 {:method       "GET"
                  :url          "https://api.example.com/plain"
                  :status       200
                  :status-text  "OK"
                  :response-body "just plain text"
                  :content-type "text/plain"})]
      ;; Plain text should be in a pre tag, no iframe
      (expect (str/includes? html "just plain text"))
      (expect (not (str/includes? html "<div class=\"html-preview-container\"")))
      (expect (not (str/includes? html "srcdoc=\""))))))

;; =============================================================================
;; HTTP Report Showcase — Browser Network Responses
;; =============================================================================

(defdescribe http-report-browser-network-showcase
  "Showcase: Rich HTTP exchange panels for browser network responses.
   These tests demonstrate that the same rich HTML panel works for
   browser-level network calls (page/wait-for-response, page/on-response),
   not just API testing calls."

  (describe "browser network responses with exchange panels"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "page navigation — capture response with wait-for-response"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Browser Network")
      (allure/story "Navigation Response Capture")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "browser-network")
      (allure/description
        "Captures the browser network Response when navigating to a page.
        Uses page/wait-for-response to intercept the navigation response
        and display it in a rich HTML exchange panel.
        The panel shows GET method, 200 status, response headers, and HTML body.")

      (let [resp (allure/api-step "Navigate to /test-page"
                   (page/wait-for-response *page* "**/test-page"
                     #(page/navigate *page* (str *test-server-url* "/test-page"))))]
        (allure/step "Verify browser response captured"
          (expect (some? resp))
          (expect (= 200 (net/response-status resp)))
          (expect (str/includes? (net/response-url resp) "/test-page")))))

    (it "JSON API call from browser — capture XHR-style response"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Browser Network")
      (allure/story "JSON API from Browser")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "browser-network")
      (allure/description
        "Navigates to a page, then captures a subsequent JSON API response.
        Uses page/wait-for-response with page/evaluate to make a fetch() call
        from the browser and capture the network response with full details.
        The panel shows the JSON response with syntax highlighting.")

      ;; First navigate to a page (needed for evaluate to work)
      (page/navigate *page* (str *test-server-url* "/test-page"))
      (let [resp (allure/api-step "Fetch /health from browser"
                   (page/wait-for-response *page* "**/health"
                     #(page/evaluate *page*
                        (str "fetch('" *test-server-url* "/health')"))))]
        (allure/step "Verify JSON response from browser"
          (expect (some? resp))
          (expect (= 200 (net/response-status resp)))
          (expect (str/includes? (or (net/response-text resp) "") "ok")))))

    (it "multiple browser responses — on-response listener"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Browser Network")
      (allure/story "Multiple Responses with on-response")
      (allure/severity :normal)
      (allure/tag "http-report")
      (allure/tag "browser-network")
      (allure/description
        "Registers a response listener with page/on-response to collect
        multiple network responses during navigation. Each captured response
        is attached as a separate exchange panel via allure/api-step.
        Demonstrates that on-response captures ALL network activity.")

      (let [responses (atom [])]
        (page/on-response *page* (fn [r] (swap! responses conj r)))
        (allure/step "Navigate to /test-page (triggers responses)"
          (page/navigate *page* (str *test-server-url* "/test-page")))
        (allure/step "Attach captured network responses"
          ;; Filter to only document responses (skip sub-resources)
          (let [doc-responses (filter
                                #(str/includes? (net/response-url %) "/test-page")
                                @responses)]
            (expect (pos? (count doc-responses)))
            (doseq [r doc-responses]
              (allure/api-step (str "Network: " (net/response-url r))
                r))))))))

;; =============================================================================
;; HTTP Report Showcase — Mixed API + Browser Network
;; =============================================================================

(defdescribe http-report-mixed-showcase
  "Showcase: Mix of API calls and browser network in the same test.
   Demonstrates that both response types produce the same rich exchange panel."

  (describe "API and browser responses in same test"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "API call + browser navigation — both get exchange panels"
      (allure/epic "HTTP Exchange Report")
      (allure/feature "Mixed API + Browser")
      (allure/story "Unified Exchange Panels")
      (allure/severity :critical)
      (allure/tag "http-report")
      (allure/tag "mixed")
      (allure/description
        "Makes an API call via APIRequestContext AND a browser navigation,
        showing that both produce identical-looking rich HTML exchange panels.
        This is the key showcase: one consistent experience regardless of
        whether the HTTP call was from the API layer or the browser.")

      ;; API call
      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [api-resp (allure/api-step "API: GET /health"
                         (api/api-get ctx "/health"))]
          (allure/step "Verify API response"
            (expect (= 200 (api/api-response-status api-resp))))))

      ;; Browser network call
      (let [nav-resp (allure/api-step "Browser: Navigate to /test-page"
                       (page/wait-for-response *page* "**/test-page"
                         #(page/navigate *page* (str *test-server-url* "/test-page"))))]
        (allure/step "Verify browser response"
          (expect (= 200 (net/response-status nav-resp)))
          (expect (str/includes? (page/title *page*) "Test Page")))))))
