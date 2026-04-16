(ns com.blockether.spel.api-showcase-test
  "Showcase integration tests demonstrating the full Allure reporting
   capabilities for API testing: rich step hierarchies, api-step with
   auto-attached response metadata, ui-step with before/after screenshots,
   and mixed API+UI test flows.

   These tests hit the local test server (no external network) and produce
   rich Allure report output with steps, parameters, attachments, and
   screenshots at every level.

   Run with the Allure reporter:
     clojure -M:test --namespace com.blockether.spel.api-showcase-test \\
       --output nested --output com.blockether.spel.allure-reporter/allure"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure :refer [around defdescribe describe expect it]]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]])
  (:import
   [com.microsoft.playwright APIResponse]))

(alias 'api 'com.blockether.spel.core)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- simple-json-encode
  "Minimal JSON encoder for tests."
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
;; API Showcase — Health Check Flow
;; =============================================================================

(defdescribe api-health-check-showcase
  "Showcase: API health check with rich Allure steps"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "health check flow"

    (it "performs full health check with detailed steps"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Health Check")
        (allure/story "Service Health Verification")
        (allure/severity :blocker)
        (allure/owner "spel")
        (allure/tag "api")
        (allure/tag "health")
        (allure/description
          "Verifies the test server is healthy by hitting the /health endpoint.
        Inspects status code, response headers, and body content.
        Demonstrates api-step with auto-attached response metadata.")

        (allure/step "Create API context"
          (allure/parameter "base-url" *test-server-url*)
          (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                       {:base-url *test-server-url*})]
            (allure/step "Verify context is ready"
              (expect (some? ctx)))

            (let [resp (allure/api-step "GET /health"
                         (api/api-get ctx "/health"))]

              (allure/step "Verify HTTP status 200"
                (let [status (api/api-response-status resp)]
                  (allure/parameter "status" status)
                  (expect (= 200 status))))

              (allure/step "Verify response is OK"
                (expect (true? (api/api-response-ok? resp))))

              (allure/step "Verify response URL"
                (let [url (api/api-response-url resp)]
                  (allure/parameter "url" url)
                  (expect (str/includes? url "/health"))))

              (allure/step "Verify Content-Type header"
                (let [headers (api/api-response-headers resp)
                      ct      (get headers "content-type")]
                  (allure/parameter "content-type" ct)
                  (expect (some? ct))
                  (expect (str/includes? ct "application/json"))))

              (allure/step "Verify response body"
                (let [body (api/api-response-text resp)]
                  (allure/parameter "body-length" (count body))
                  (allure/attach "Health Response" body "application/json")
                  (expect (str/includes? body "ok")))))))))))

;; =============================================================================
;; API Showcase — CRUD Operations
;; =============================================================================

(defdescribe api-crud-showcase
  "Showcase: Full CRUD lifecycle with api-step"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "CRUD operations on /echo"

    (it "executes full Create-Read-Update-Delete cycle"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "CRUD Operations")
        (allure/story "Full Lifecycle")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "crud")
        (allure/description
          "Demonstrates a full CRUD lifecycle against the echo endpoint.
        Each operation is wrapped in api-step for automatic response
        metadata capture in the Allure report.")

        (binding [api/*json-encoder* simple-json-encode]
          (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                       {:base-url *test-server-url*})]

            (allure/step "CREATE — POST new resource"
              (let [resp (allure/api-step "POST /echo — create user"
                           (api/api-post ctx "/echo"
                             {:json {:name "Alice" :email "alice@example.org"}}))]
                (allure/step "Verify creation succeeded"
                  (expect (= 200 (api/api-response-status resp)))
                  (let [body (api/api-response-text resp)]
                    (allure/parameter "response-body" body)
                    (expect (str/includes? body "POST"))
                    (expect (str/includes? body "Alice"))))))

            (allure/step "READ — GET resource"
              (let [resp (allure/api-step "GET /echo — fetch user"
                           (api/api-get ctx "/echo" {:params {:id "1" :fields "name,email"}}))]
                (allure/step "Verify read succeeded"
                  (expect (= 200 (api/api-response-status resp)))
                  (let [body (api/api-response-text resp)]
                    (allure/parameter "query" "id=1&fields=name,email")
                    (expect (str/includes? body "GET"))))))

            (allure/step "UPDATE — PUT resource"
              (let [resp (allure/api-step "PUT /echo — update user"
                           (api/api-put ctx "/echo"
                             {:json {:name "Alice Updated" :email "alice2@example.org"}}))]
                (allure/step "Verify update succeeded"
                  (expect (= 200 (api/api-response-status resp)))
                  (expect (str/includes? (api/api-response-text resp) "PUT")))))

            (allure/step "PATCH — Partial update"
              (let [resp (allure/api-step "PATCH /echo — patch email"
                           (api/api-patch ctx "/echo"
                             {:json {:email "alice3@example.org"}}))]
                (allure/step "Verify patch succeeded"
                  (expect (= 200 (api/api-response-status resp)))
                  (expect (str/includes? (api/api-response-text resp) "PATCH")))))

            (allure/step "DELETE — Remove resource"
              (let [resp (allure/api-step "DELETE /echo — delete user"
                           (api/api-delete ctx "/echo"))]
                (allure/step "Verify deletion succeeded"
                  (expect (= 200 (api/api-response-status resp)))
                  (expect (str/includes? (api/api-response-text resp) "DELETE")))))))))))

;; =============================================================================
;; API Showcase — Error Handling & Status Codes
;; =============================================================================

(defdescribe api-status-codes-showcase
  "Showcase: HTTP status code verification with rich steps"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "status code validation"

    (it "verifies various HTTP status codes"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Status Codes")
        (allure/story "HTTP Status Verification")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "status-codes")
        (allure/description
          "Tests multiple HTTP status codes from the test server.
        Each status code check is a nested step with response parameters.")

        (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                     {:base-url *test-server-url*})]

          (allure/step "2xx — Success responses"
            (allure/step "200 OK"
              (let [resp (allure/api-step "GET /status/200"
                           (api/api-get ctx "/status/200"))]
                (expect (= 200 (api/api-response-status resp)))
                (expect (true? (api/api-response-ok? resp)))))

            (allure/step "201 Created"
              (let [resp (allure/api-step "GET /status/201"
                           (api/api-get ctx "/status/201"))]
                (expect (= 201 (api/api-response-status resp)))
                (expect (true? (api/api-response-ok? resp)))))

            (allure/step "204 No Content"
              (let [resp (allure/api-step "GET /status/204"
                           (api/api-get ctx "/status/204"))]
                (expect (= 204 (api/api-response-status resp))))))

          (allure/step "3xx — Redirect responses"
            (allure/step "301 Moved Permanently"
              (let [resp (allure/api-step "GET /status/301"
                           (api/api-get ctx "/status/301"))]
                (allure/parameter "status" (api/api-response-status resp))
                (expect (some? resp)))))

          (allure/step "4xx — Client error responses"
            (allure/step "400 Bad Request"
              (let [resp (allure/api-step "GET /status/400"
                           (api/api-get ctx "/status/400"))]
                (expect (= 400 (api/api-response-status resp)))
                (expect (false? (api/api-response-ok? resp)))))

            (allure/step "401 Unauthorized"
              (let [resp (allure/api-step "GET /status/401"
                           (api/api-get ctx "/status/401"))]
                (expect (= 401 (api/api-response-status resp)))))

            (allure/step "403 Forbidden"
              (let [resp (allure/api-step "GET /status/403"
                           (api/api-get ctx "/status/403"))]
                (expect (= 403 (api/api-response-status resp)))))

            (allure/step "404 Not Found"
              (let [resp (allure/api-step "GET /status/404"
                           (api/api-get ctx "/status/404"))]
                (expect (= 404 (api/api-response-status resp)))))

            (allure/step "429 Too Many Requests"
              (let [resp (allure/api-step "GET /status/429"
                           (api/api-get ctx "/status/429"))]
                (expect (= 429 (api/api-response-status resp))))))

          (allure/step "5xx — Server error responses"
            (allure/step "500 Internal Server Error"
              (let [resp (allure/api-step "GET /status/500"
                           (api/api-get ctx "/status/500"))]
                (expect (= 500 (api/api-response-status resp)))
                (expect (false? (api/api-response-ok? resp)))))

            (allure/step "502 Bad Gateway"
              (let [resp (allure/api-step "GET /status/502"
                           (api/api-get ctx "/status/502"))]
                (expect (= 502 (api/api-response-status resp)))))

            (allure/step "503 Service Unavailable"
              (let [resp (allure/api-step "GET /status/503"
                           (api/api-get ctx "/status/503"))]
                (expect (= 503 (api/api-response-status resp)))))))))))

;; =============================================================================
;; API Showcase — Hooks & Auth Injection
;; =============================================================================

(defdescribe api-hooks-showcase
  "Showcase: API hooks with logging and auth injection"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "hooks in action"

    (it "demonstrates auth injection and response logging via hooks"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Hooks")
        (allure/story "Auth Injection & Logging")
        (allure/severity :normal)
        (allure/tag "api")
        (allure/tag "hooks")
        (allure/description
          "Shows how hooks intercept requests and responses.
        on-request injects an Authorization header.
        on-response logs status codes to an atom for verification.")

        (let [request-log  (atom [])
              response-log (atom [])]

          (allure/step "Configure hooks"
            (allure/parameter "hooks" ":on-request, :on-response")
            (allure/attach "Hook config"
              (str "{:on-request  \"Injects Authorization header\"\n"
                " :on-response \"Logs method + status\"}")
              "text/plain"))

          (api/with-hooks
            {:on-request  (fn [method url opts]
                            (swap! request-log conj {:method method :url url})
                            (assoc-in opts [:headers "Authorization"] "Bearer test-token"))
             :on-response (fn [method _url resp]
                            (swap! response-log conj
                              {:method method
                               :status (.status ^APIResponse resp)})
                            resp)}

            (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                         {:base-url *test-server-url*})]

              (allure/step "Execute API calls with hooks active"
                (allure/api-step "GET /health"
                  (api/api-get ctx "/health" {}))

                (allure/api-step "POST /echo with JSON body"
                  (binding [api/*json-encoder* simple-json-encode]
                    (api/api-post ctx "/echo" {:json {:action "create"}})))

                (allure/api-step "GET /echo with params"
                  (api/api-get ctx "/echo" {:params {:page "1" :limit "10"}})))

              (allure/step "Verify hooks captured all requests"
                (allure/parameter "request-count" (count @request-log))
                (allure/parameter "response-count" (count @response-log))
                (expect (= 3 (count @request-log)))
                (expect (= 3 (count @response-log)))

                (allure/step "Verify request methods logged"
                  (let [methods (mapv :method @request-log)]
                    (allure/parameter "methods" (str methods))
                    (expect (= [:get :post :get] methods))))

                (allure/step "Verify all responses were 200"
                  (let [statuses (mapv :status @response-log)]
                    (allure/parameter "statuses" (str statuses))
                    (expect (every? #(= 200 %) statuses))))

                (allure/step "Verify auth header was injected"
                  (allure/attach "Request log"
                    (pr-str @request-log) "text/plain")
                  (allure/attach "Response log"
                    (pr-str @response-log) "text/plain")
                  (expect (every? some? @request-log)))))))))))

;; =============================================================================
;; API Showcase — Retry with Backoff
;; =============================================================================

(defdescribe api-retry-showcase
  "Showcase: Retry with exponential backoff and hook tracking"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "retry flow"

    (it "retries flaky endpoint with exponential backoff"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Retry")
        (allure/story "Exponential Backoff")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "retry")
        (allure/description
          "Simulates a flaky endpoint that returns 503 twice before succeeding.
        Uses retry with exponential backoff. Hooks track all attempts.
        The Allure report shows the full retry lifecycle.")

        (let [attempt-log (atom [])
              retry-log   (atom [])]

          (api/with-hooks
            {:on-response (fn [method _url resp]
                            (swap! attempt-log conj
                              {:method method
                               :status (.status ^APIResponse resp)})
                            resp)
             :on-retry    (fn [info]
                            (swap! retry-log conj info))}

            (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                         {:base-url *test-server-url*})]
              (let [counter (atom 0)]

                (allure/step "Execute retry loop"
                  (allure/parameter "max-attempts" 5)
                  (allure/parameter "backoff" "exponential")
                  (allure/parameter "initial-delay" "10ms")

                  (let [result (api/retry
                                 (fn []
                                   (let [n    (swap! counter inc)
                                         path (if (< n 3) "/status/503" "/health")]
                                     (allure/step (str "Attempt " n ": GET " path)
                                       (let [resp (api/api-get ctx path)]
                                         (allure/parameter "attempt" n)
                                         (allure/parameter "status"
                                           (api/api-response-status resp))
                                         (api/api-response->map resp)))))
                                 {:max-attempts 5
                                  :delay-ms     10
                                  :backoff      :exponential
                                  :retry-when   (fn [r] (and (map? r)
                                                          (>= (:status r) 500)))})]

                    (allure/step "Verify final result is successful"
                      (allure/parameter "final-status" (:status result))
                      (allure/parameter "total-attempts" @counter)
                      (allure/attach "Final response"
                        (pr-str result) "text/plain")
                      (expect (= 200 (:status result)))
                      (expect (= 3 @counter)))

                    (allure/step "Verify retry history"
                      (allure/parameter "retry-count" (count @retry-log))
                      (expect (= 2 (count @retry-log)))

                      (allure/step "First retry metadata"
                        (let [r1 (first @retry-log)]
                          (allure/parameter "attempt" (:attempt r1))
                          (allure/parameter "delay-ms" (:delay-ms r1))
                          (expect (= 1 (:attempt r1)))))

                      (allure/step "Second retry metadata"
                        (let [r2 (second @retry-log)]
                          (allure/parameter "attempt" (:attempt r2))
                          (allure/parameter "delay-ms" (:delay-ms r2))
                          (expect (= 2 (:attempt r2))))))

                    (allure/step "Verify attempt history"
                      (allure/parameter "attempt-statuses"
                        (str (mapv :status @attempt-log)))
                      (expect (= [503 503 200] (mapv :status @attempt-log)))
                      (allure/attach "Full attempt log"
                        (pr-str @attempt-log)
                        "text/plain"))))))))))))

;; =============================================================================
;; API Showcase — Standalone request! 
;; =============================================================================

(defdescribe api-standalone-showcase
  "Showcase: Fire-and-forget requests with request!"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "standalone requests"

    (it "hits multiple endpoints without context management"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Standalone Requests")
        (allure/story "request! convenience")
        (allure/severity :normal)
        (allure/tag "api")
        (allure/tag "request!")
        (allure/description
          "Demonstrates request! for quick one-off HTTP calls.
        No context creation/disposal needed — fully self-contained.")

        (allure/step "GET health check"
          (let [m (api/request! core/*testing-pw* :get (str *test-server-url* "/health"))]
            (allure/parameter "status" (:status m))
            (allure/parameter "ok?" (:ok? m))
            (allure/attach "Response" (pr-str m) "text/plain")
            (expect (= 200 (:status m)))
            (expect (true? (:ok? m)))))

        (allure/step "POST with JSON data"
          (binding [api/*json-encoder* simple-json-encode]
            (let [m (api/request! core/*testing-pw* :post (str *test-server-url* "/echo")
                      {:json {:name "Bob" :role "admin"}})]
              (allure/parameter "status" (:status m))
              (allure/parameter "method-echo" "POST")
              (allure/attach "Response body" (:body m) "application/json")
              (expect (= 200 (:status m)))
              (expect (str/includes? (:body m) "Bob")))))

        (allure/step "PUT update"
          (let [m (api/request! core/*testing-pw* :put (str *test-server-url* "/echo")
                    {:data "{\"updated\":true}"
                     :headers {"Content-Type" "application/json"}})]
            (allure/parameter "status" (:status m))
            (expect (= 200 (:status m)))
            (expect (str/includes? (:body m) "PUT"))))

        (allure/step "DELETE resource"
          (let [m (api/request! core/*testing-pw* :delete (str *test-server-url* "/echo"))]
            (allure/parameter "status" (:status m))
            (expect (= 200 (:status m)))
            (expect (str/includes? (:body m) "DELETE"))))

        (allure/step "Handle error status"
          (let [m (api/request! core/*testing-pw* :get (str *test-server-url* "/status/404"))]
            (allure/parameter "status" (:status m))
            (allure/parameter "ok?" (:ok? m))
            (expect (= 404 (:status m)))
            (expect (false? (:ok? m)))))))))

;; =============================================================================
;; API Showcase — Response Map Inspection
;; =============================================================================

(defdescribe api-response-inspection-showcase
  "Showcase: Deep response inspection with allure steps"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "response dissection"

    (it "inspects every field of api-response->map"
      (core/with-testing-page [_]
        (allure/epic "API Testing")
        (allure/feature "Response Inspection")
        (allure/story "response->map")
        (allure/severity :normal)
        (allure/tag "api")
        (allure/tag "response")
        (allure/description
          "Converts an APIResponse to a Clojure map and verifies every field.
        Shows parameters and attachments for each response component.")

        (api/with-api-context [ctx (api/new-api-context (api/api-request core/*testing-pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (allure/api-step "GET /echo with query params"
                       (api/api-get ctx "/echo" {:params {:user "alice" :action "view"}}))]

            (allure/step "Convert to Clojure map"
              (let [m (api/api-response->map resp)]
                (allure/attach "Full response map" (pr-str m) "text/plain")

                (allure/step "Verify :status"
                  (allure/parameter "status" (:status m))
                  (expect (= 200 (:status m)))
                  (expect (integer? (:status m))))

                (allure/step "Verify :status-text"
                  (allure/parameter "status-text" (:status-text m))
                  (expect (string? (:status-text m))))

                (allure/step "Verify :url"
                  (allure/parameter "url" (:url m))
                  (expect (string? (:url m)))
                  (expect (str/includes? (:url m) "/echo")))

                (allure/step "Verify :ok?"
                  (allure/parameter "ok?" (:ok? m))
                  (expect (true? (:ok? m))))

                (allure/step "Verify :headers"
                  (allure/parameter "header-count" (count (:headers m)))
                  (allure/attach "Response headers"
                    (pr-str (:headers m)) "text/plain")
                  (expect (map? (:headers m)))
                  (expect (contains? (:headers m) "content-type")))

                (allure/step "Verify :body"
                  (allure/parameter "body-length" (count (:body m)))
                  (allure/attach "Response body" (:body m) "application/json")
                  (expect (string? (:body m)))
                  (expect (str/includes? (:body m) "GET"))))))

          (allure/step "Headers array (preserves duplicates)"
            (let [resp (api/api-get ctx "/health")
                  arr  (api/api-response-headers-array resp)]
              (allure/parameter "header-array-count" (count arr))
              (allure/attach "Headers array" (pr-str arr) "text/plain")
              (expect (vector? arr))
              (expect (every? #(and (contains? % :name) (contains? % :value)) arr)))))))))

;; =============================================================================
;; API-Only with Playwright Tracing — Full Request/Response Capture
;; =============================================================================

(defdescribe api-traced-showcase
  "Showcase: API-only tests with Playwright tracing — every request and
   response (including POST bodies) appears in the trace file."

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "traced API calls (no browser page)"

    (it "traces GET requests with query params"
      (core/with-testing-page [pg]
        (allure/epic "API Tracing")
        (allure/feature "GET Tracing")
        (allure/story "Query params in trace")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "tracing")
        (allure/description
          "Performs GET requests through (.request (.context pg)) (BrowserContext.request()).
        The Playwright trace captures the full request URL, headers, query
        params, response headers, and response body — all without opening
        a browser page.")

        (let [resp (allure/api-step "GET /echo with params"
                     (api/api-get (.request (.context pg))
                       (str *test-server-url* "/echo")
                       {:params {:user "alice"
                                 :action "view"
                                 :limit "50"}}))]
          (allure/step "Verify response"
            (allure/parameter "status" (api/api-response-status resp))
            (expect (= 200 (api/api-response-status resp)))
            (let [body (api/api-response-text resp)]
              (allure/parameter "body" body)
              (expect (str/includes? body "GET"))
              (expect (str/includes? body "alice")))))))

    (it "traces POST requests with JSON body"
      (core/with-testing-page [pg]
        (allure/epic "API Tracing")
        (allure/feature "POST Tracing")
        (allure/story "Request body in trace")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "tracing")
        (allure/description
          "Sends a POST with a JSON body through (.request (.context pg)). The Playwright
        trace captures both the request body (postData) and the echoed
        response body as resource files in the trace ZIP.")

        (binding [api/*json-encoder* simple-json-encode]
          (let [resp (allure/api-step "POST /echo — create user"
                       (api/api-post (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:json {:name "Bob"
                                 :email "bob@example.org"
                                 :role "admin"}}))]
            (allure/step "Verify response echoes POST"
              (let [status (api/api-response-status resp)
                    body   (api/api-response-text resp)]
                (allure/parameter "status" status)
                (allure/parameter "body" body)
                (expect (= 200 status))
                (expect (str/includes? body "POST"))
                (expect (str/includes? body "Bob"))))))))

    (it "traces full CRUD cycle in a single trace"
      (core/with-testing-page [pg]
        (allure/epic "API Tracing")
        (allure/feature "CRUD Tracing")
        (allure/story "Full lifecycle in trace viewer")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "tracing")
        (allure/description
          "Performs GET, POST, PUT, PATCH, DELETE through (.request (.context pg)).
        Every request and response appears in the Playwright trace file
        — open it in trace.playwright.dev to see the full HTTP timeline.")

        (binding [api/*json-encoder* simple-json-encode]
          (allure/api-step "POST /echo — create"
            (let [resp (api/api-post (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:json {:name "Eve" :action "create"}})]
              (expect (= 200 (api/api-response-status resp)))
              resp))

          (allure/api-step "GET /echo — read"
            (let [resp (api/api-get (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:params {:id "1"}})]
              (expect (= 200 (api/api-response-status resp)))
              resp))

          (allure/api-step "PUT /echo — update"
            (let [resp (api/api-put (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:json {:name "Eve Updated"}})]
              (expect (= 200 (api/api-response-status resp)))
              resp))

          (allure/api-step "PATCH /echo — partial update"
            (let [resp (api/api-patch (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:json {:email "eve@new.com"}})]
              (expect (= 200 (api/api-response-status resp)))
              resp))

          (allure/api-step "DELETE /echo — remove"
            (let [resp (api/api-delete (.request (.context pg))
                         (str *test-server-url* "/echo"))]
              (expect (= 200 (api/api-response-status resp)))
              resp)))))))

;; =============================================================================
;; Mixed API + UI — Fetch Data Then Render in Browser
;; =============================================================================

(defdescribe mixed-api-ui-showcase
  "Showcase: Combined API + UI testing — fetch data via API, render in browser"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "API data → Browser rendering"

    (it "fetches API data and verifies it renders in the browser"
      (core/with-testing-page [pg]
        (allure/epic "Mixed API + UI")
        (allure/feature "Data Flow")
        (allure/story "API → Browser Rendering")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "ui")
        (allure/tag "mixed")
        (allure/description
          "Fetches JSON data from the API test server, then injects it into
        a browser page as HTML and verifies the DOM rendering.
        Uses (.request (.context pg)) (from BrowserContext.request()) so API calls
        appear in the Playwright trace alongside browser interactions.
        Demonstrates combining api-step (HTTP calls) with ui-step
        (browser interactions with before/after screenshots).")

      ;; Phase 1: API — fetch data via traced browser-context API
        (allure/step "Phase 1: Fetch data via traced API"
          (let [resp (allure/api-step "GET /echo — fetch user data"
                       (api/api-get (.request (.context pg))
                         (str *test-server-url* "/echo")
                         {:params {:name "Alice"
                                   :role "Engineer"
                                   :team "Platform"}}))
                status (api/api-response-status resp)
                body   (api/api-response-text resp)]

            (allure/step "Verify API response"
              (allure/parameter "status" status)
              (allure/parameter "body-length" (count body))
              (expect (= 200 status)))

          ;; Phase 2: UI — render the data in browser
            (allure/step "Phase 2: Render data in browser"
              (let [html (str "<html><head><title>User Profile</title></head>"
                           "<body>"
                           "<h1 id='title'>User Profile</h1>"
                           "<div id='data'>"
                           "<p class='name'>Name: Alice</p>"
                           "<p class='role'>Role: Engineer</p>"
                           "<p class='team'>Team: Platform</p>"
                           "</div>"
                           "<div id='raw-response'>" body "</div>"
                           "</body></html>")]

                (allure/ui-step "Load user profile page"
                  (page/set-content! pg html))

                (allure/ui-step "Verify page title"
                  (let [title (page/title pg)]
                    (allure/parameter "title" title)
                    (expect (= "User Profile" title))))

                (allure/ui-step "Verify heading"
                  (let [h1 (page/locator pg "#title")]
                    (expect (= "User Profile" (locator/text-content h1)))))

                (allure/ui-step "Verify user name"
                  (let [name-el (page/locator pg ".name")
                        text    (locator/text-content name-el)]
                    (allure/parameter "name" text)
                    (expect (str/includes? text "Alice"))))

                (allure/ui-step "Verify user role"
                  (let [role-el (page/locator pg ".role")
                        text    (locator/text-content role-el)]
                    (allure/parameter "role" text)
                    (expect (str/includes? text "Engineer"))))

                (allure/ui-step "Verify team"
                  (let [team-el (page/locator pg ".team")
                        text    (locator/text-content team-el)]
                    (allure/parameter "team" text)
                    (expect (str/includes? text "Platform"))))

                (allure/ui-step "Verify raw API response is embedded"
                  (let [raw-el (page/locator pg "#raw-response")
                        text   (locator/text-content raw-el)]
                    (allure/parameter "raw-length" (count text))
                    (expect (pos? (count text)))))))))))

    (it "performs API health check then verifies dashboard"
      (core/with-testing-page [pg]
        (allure/epic "Mixed API + UI")
        (allure/feature "Dashboard")
        (allure/story "Health → Dashboard Render")
        (allure/severity :normal)
        (allure/tag "api")
        (allure/tag "ui")
        (allure/tag "mixed")
        (allure/description
          "Checks API health via (.request (.context pg)) (traced), then renders a
        dashboard in the browser showing service status. API calls
        appear in the Playwright trace alongside browser actions.")

      ;; Step 1: Check multiple API endpoints via traced context
        (let [results (atom {})]

          (allure/step "Check API endpoints (traced)"
            (allure/api-step "Health check"
              (let [resp (api/api-get (.request (.context pg))
                           (str *test-server-url* "/health"))]
                (swap! results assoc :health (api/api-response-status resp))
                resp))

            (allure/api-step "Echo service"
              (let [resp (api/api-get (.request (.context pg))
                           (str *test-server-url* "/echo"))]
                (swap! results assoc :echo (api/api-response-status resp))
                resp))

            (allure/api-step "Status endpoint"
              (let [resp (api/api-get (.request (.context pg))
                           (str *test-server-url* "/status/200"))]
                (swap! results assoc :status (api/api-response-status resp))
                resp)))

        ;; Step 2: Render dashboard from results
          (allure/step "Render status dashboard"
            (let [{:keys [health echo status]} @results
                  html (str "<html><head><title>Service Dashboard</title></head>"
                         "<body>"
                         "<h1>Service Dashboard</h1>"
                         "<table id='status-table'>"
                         "<tr><th>Service</th><th>Status</th></tr>"
                         "<tr class='svc'><td>Health</td>"
                         "<td class='code'>" health "</td></tr>"
                         "<tr class='svc'><td>Echo</td>"
                         "<td class='code'>" echo "</td></tr>"
                         "<tr class='svc'><td>Status</td>"
                         "<td class='code'>" status "</td></tr>"
                         "</table>"
                         "</body></html>")]

              (allure/ui-step "Load dashboard"
                (page/set-content! pg html))

              (allure/ui-step "Verify dashboard title"
                (expect (= "Service Dashboard" (page/title pg))))

              (allure/ui-step "Verify all services show 200"
                (let [codes (page/locator pg ".code")
                      count (locator/count-elements codes)]
                  (allure/parameter "service-count" count)
                  (expect (= 3 count))
                  (dotimes [i count]
                    (let [code-text (locator/text-content (locator/nth-element codes i))]
                      (allure/parameter (str "service-" i "-status") code-text)
                      (expect (= "200" code-text))))))

              (allure/step "Final dashboard screenshot"
                (allure/screenshot pg "Service Dashboard — All Green")))))))))

;; =============================================================================
;; Mixed API + UI — Multi-Domain with Form Submission
;; =============================================================================

(defdescribe mixed-multi-domain-showcase
  "Showcase: Multi-context API + browser form submission"

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "multi-domain API with form"

    (it "uses multiple API contexts and submits a browser form"
      (core/with-testing-page [pg]
        (allure/epic "Mixed API + UI")
        (allure/feature "Multi-Domain")
        (allure/story "API Contexts + Form Submit")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "ui")
        (allure/tag "multi-domain")
        (allure/description
          "Uses (.request (.context pg)) with per-request headers to simulate
        multi-domain API access (all calls traced in Playwright),
        then renders results in a browser form.
        Shows the full spel API testing workflow.")

        (allure/step "Fetch from multiple services (traced)"
          (let [r1 (allure/api-step "GET users /health"
                     (api/api-get (.request (.context pg))
                       (str *test-server-url* "/health")
                       {:headers {"X-Service" "users"}}))
                r2 (allure/api-step "GET billing /health"
                     (api/api-get (.request (.context pg))
                       (str *test-server-url* "/health")
                       {:headers {"X-Service" "billing"}}))]

            (allure/step "Verify both services healthy"
              (allure/parameter "users-status" (api/api-response-status r1))
              (allure/parameter "billing-status" (api/api-response-status r2))
              (expect (= 200 (api/api-response-status r1)))
              (expect (= 200 (api/api-response-status r2))))))

        (allure/step "Render and interact with browser form"
          (let [html (str "<html><head><title>Service Config</title></head>"
                       "<body>"
                       "<h1>Service Configuration</h1>"
                       "<form id='config-form'>"
                       "<label for='svc-name'>Service Name</label>"
                       "<input id='svc-name' type='text' value='' />"
                       "<label for='svc-url'>Service URL</label>"
                       "<input id='svc-url' type='text' value='' />"
                       "<button id='submit-btn' type='button'"
                       " onclick=\"document.getElementById('result')"
                       ".textContent='Saved: ' + "
                       "document.getElementById('svc-name').value\">"
                       "Save</button>"
                       "<p id='result'></p>"
                       "</form>"
                       "</body></html>")]

            (allure/ui-step "Load configuration form"
              (page/set-content! pg html))

            (allure/ui-step "Fill service name"
              (let [input (page/locator pg "#svc-name")]
                (locator/fill input "users-api")))

            (allure/ui-step "Fill service URL"
              (let [input (page/locator pg "#svc-url")]
                (locator/fill input *test-server-url*)))

            (allure/ui-step "Submit form"
              (let [btn (page/locator pg "#submit-btn")]
                (locator/click btn)))

            (allure/ui-step "Verify form submission result"
              (let [result-el (page/locator pg "#result")
                    text      (locator/text-content result-el)]
                (allure/parameter "result" text)
                (expect (str/includes? text "Saved: users-api"))))))))))

;; =============================================================================
;; API Showcase — Rich Request Headers + Body (cURL reproducibility)
;; =============================================================================

(defdescribe api-rich-headers-showcase
  "Showcase: API calls with custom request headers and JSON bodies so the
   HTTP exchange markdown / cURL command is fully reproducible from the
   Allure report."

  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (describe "Authenticated JSON API calls"

    (it "issues GET and POST with auth, content-type, correlation headers and JSON body"
      (core/with-testing-page [pg]
        (allure/epic "Mixed API + UI")
        (allure/feature "Request Headers")
        (allure/story "Auth + JSON body → reproducible cURL")
        (allure/severity :critical)
        (allure/tag "api")
        (allure/tag "headers")
        (allure/tag "curl")
        (allure/description
          "Exercises the full request-meta capture: Authorization bearer
          token, Content-Type, Accept, User-Agent and an X-Request-ID
          correlation header, plus a JSON request body on POST. The
          resulting HTTP markdown attachment must include a Request
          Headers section, a Request Body section, and a cURL command
          with `-H` flags + `-d` payload so a developer can replay the
          call straight from the report.")

        ;; Attach a plain markdown note + a rendered HTML snapshot so
        ;; the alt report's text/markdown and text/html attachment paths
        ;; are both exercised alongside the HTTP exchange attachments.
        (allure/attach "Integration Notes"
          (str "# Integration Notes\n\n"
            "This test checks the **order pipeline** from submission to fulfillment.\n\n"
            "## Steps\n\n"
            "1. Submit an order with `POST /orders`.\n"
            "2. Poll `/orders/:id/status` until it's `fulfilled`.\n"
            "3. Verify the shipping label was generated.\n\n"
            "## Sample Response\n\n"
            "```json\n"
            "{\n  \"orderId\": \"ord-1234\",\n  \"status\": \"fulfilled\",\n  \"tracking\": \"1Z999AA10123456784\"\n}\n"
            "```\n\n"
            "## References\n\n"
            "- [Order API Spec](https://example.com/api/orders)\n"
            "- See also the `checkout` domain notes.\n"
            "> Heads up: the tracking number format depends on the carrier.\n")
          "text/markdown")

        (allure/attach "Rendered Dashboard"
          (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
            "<style>body{font-family:sans-serif;padding:1rem;background:#f6f6f8}"
            "h1{color:#4f46e5;margin:0 0 .5rem}"
            "table{border-collapse:collapse;width:100%}"
            "th,td{border:1px solid #ccc;padding:.4rem .6rem;font-size:.9rem}"
            ".ok{color:#16a34a;font-weight:700}</style></head>"
            "<body><h1>Service Dashboard</h1>"
            "<table><tr><th>Service</th><th>Status</th></tr>"
            "<tr><td>Health</td><td class='ok'>200 OK</td></tr>"
            "<tr><td>Echo</td><td class='ok'>200 OK</td></tr>"
            "<tr><td>Status</td><td class='ok'>200 OK</td></tr>"
            "</table></body></html>")
          "text/html")

        (let [ctx     (.request (.context pg))
              base    *test-server-url*
              bearer  "Bearer eyJhbGciOiJIUzI1NiJ9.fake-jwt.signature"
              req-id  "req-a1b2c3d4-1111"
              ua      "spel-showcase/1.0 (+https://github.com/blockether/spel)"]

          (allure/step "GET /echo with auth + correlation headers"
            (allure/api-step "GET /echo — authenticated read"
              (api/api-get ctx (str base "/echo?view=summary")
                {:headers {"Authorization"   bearer
                           "Accept"          "application/json"
                           "User-Agent"      ua
                           "X-Request-ID"    req-id
                           "X-Tenant-ID"     "acme-42"}})))

          (allure/step "POST /echo with JSON body + headers"
            (let [payload (simple-json-encode
                            {:user   "alice@example.com"
                             :action "create-order"
                             :items  [{:sku "SKU-123" :qty 2}
                                      {:sku "SKU-999" :qty 1}]
                             :total  199.95})
                  resp (allure/api-step "POST /echo — create order"
                         (api/api-post ctx (str base "/echo")
                           {:headers {"Authorization"    bearer
                                      "Content-Type"     "application/json"
                                      "Accept"           "application/json"
                                      "User-Agent"       ua
                                      "X-Request-ID"     req-id
                                      "X-Idempotency-Key" "order-7f3a"}
                            :data    payload}))]
              (allure/parameter "status" (api/api-response-status resp))
              (expect (= 200 (api/api-response-status resp)))))

          (allure/step "Render request summary in browser"
            (let [html (str "<html><head><title>Request Audit</title></head>"
                         "<body>"
                         "<h1>Request Audit Log</h1>"
                         "<table id='audit'>"
                         "<tr><th>Field</th><th>Value</th></tr>"
                         "<tr><td>Correlation ID</td>"
                         "<td id='rid'>" req-id "</td></tr>"
                         "<tr><td>Auth scheme</td>"
                         "<td id='auth'>Bearer (JWT)</td></tr>"
                         "<tr><td>User agent</td>"
                         "<td id='ua'>" ua "</td></tr>"
                         "</table>"
                         "</body></html>")]
              (allure/ui-step "Load audit view"
                (page/set-content! pg html))

              (allure/ui-step "Verify correlation id rendered"
                (let [text (locator/text-content (page/locator pg "#rid"))]
                  (allure/parameter "rendered-rid" text)
                  (expect (= req-id text))))

              (allure/step "Audit screenshot"
                (allure/screenshot pg "Request Audit Log")))))))))
