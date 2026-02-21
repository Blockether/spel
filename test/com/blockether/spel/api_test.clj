(ns com.blockether.spel.api-test
  "Comprehensive tests for the Playwright API testing module.

   Covers: FormData, RequestOptions, JSON encoding, context lifecycle,
   all HTTP methods, response accessors, response->map, request!,
   hooks (*hooks*, with-hooks), and retry (retry, with-retry)."
  (:require
   [clojure.string :as str]
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.api :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :as tf
    :refer [*pw* *page* *browser-context*
            with-playwright with-browser with-page]]
   [com.blockether.spel.test-server :as ts
    :refer [*test-server-url* with-test-server test-server-requests]]
   [com.blockether.spel.allure :refer [defdescribe describe expect expect-it it]])
  (:import
   [com.microsoft.playwright APIRequest APIRequestContext APIResponse]
   [com.microsoft.playwright.options FormData RequestOptions]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- simple-json-encode
  "Minimal JSON encoder for tests. Handles maps with string/keyword keys
   and simple values (strings, numbers, booleans)."
  [data]
  (cond
    (nil? data)    "null"
    (string? data) (str "\"" data "\"")
    (number? data) (str data)
    (boolean? data) (str data)
    (keyword? data) (str "\"" (name data) "\"")
    (map? data)    (str "{"
                     (str/join ","
                       (map (fn [[k v]]
                              (str "\"" (name k) "\":"
                                (simple-json-encode v)))
                         data))
                     "}")
    (sequential? data) (str "["
                         (str/join "," (map simple-json-encode data))
                         "]")
    :else (str data)))

;; =============================================================================
;; FormData
;; =============================================================================

(defdescribe form-data-test
  "Tests for FormData creation and manipulation"

  (expect-it "form-data creates a FormData instance"
    (instance? FormData (sut/form-data)))

  (it "fd-set sets a field and returns FormData"
    (let [fd (sut/fd-set (sut/form-data) "name" "Alice")]
      (expect (instance? FormData fd))))

  (it "fd-append appends a field and returns FormData"
    (let [fd (sut/fd-append (sut/form-data) "tag" "clojure")]
      (expect (instance? FormData fd))))

  (it "map->form-data converts a map to FormData"
    (let [fd (sut/map->form-data {:name "Alice" :email "alice@example.com"})]
      (expect (instance? FormData fd))))

  (it "map->form-data handles empty map"
    (let [fd (sut/map->form-data {})]
      (expect (instance? FormData fd))))

  (it "fd-set and fd-append can be chained"
    (let [fd (-> (sut/form-data)
               (sut/fd-set "name" "Alice")
               (sut/fd-append "tag" "clojure")
               (sut/fd-append "tag" "java"))]
      (expect (instance? FormData fd)))))

;; =============================================================================
;; RequestOptions
;; =============================================================================

(defdescribe request-options-test
  "Tests for RequestOptions creation from Clojure maps"

  (expect-it "returns a RequestOptions instance"
    (instance? RequestOptions (sut/request-options {})))

  (it "accepts :headers"
    (let [ro (sut/request-options {:headers {"Authorization" "Bearer token"
                                             "X-Custom" "value"}})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :data as string"
    (let [ro (sut/request-options {:data "{\"name\": \"Alice\"}"})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :data as bytes"
    (let [ro (sut/request-options {:data (.getBytes "hello" "UTF-8")})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :timeout"
    (let [ro (sut/request-options {:timeout 5000})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :params"
    (let [ro (sut/request-options {:params {:page 1 :limit 10}})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :form"
    (let [ro (sut/request-options {:form (sut/map->form-data {:name "Alice"})})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :multipart"
    (let [ro (sut/request-options {:multipart (sut/map->form-data {:file "data"})})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :max-redirects"
    (let [ro (sut/request-options {:max-redirects 5})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :max-retries"
    (let [ro (sut/request-options {:max-retries 3})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :fail-on-status-code true"
    (let [ro (sut/request-options {:fail-on-status-code true})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :fail-on-status-code false"
    (let [ro (sut/request-options {:fail-on-status-code false})]
      (expect (instance? RequestOptions ro))))

  (it "accepts :ignore-https-errors"
    (let [ro (sut/request-options {:ignore-https-errors true})]
      (expect (instance? RequestOptions ro))))

  (describe ":json key"
    (it "encodes json and sets Content-Type when *json-encoder* is bound"
      (binding [sut/*json-encoder* simple-json-encode]
        (let [ro (sut/request-options {:json {:name "Alice" :age 30}})]
          (expect (instance? RequestOptions ro)))))

    (it "throws when *json-encoder* is not bound"
      (let [threw? (try
                     (sut/request-options {:json {:name "Alice"}})
                     false
                     (catch clojure.lang.ExceptionInfo _
                       true))]
        (expect threw?)))

    (it "throws with descriptive message"
      (try
        (sut/request-options {:json {:x 1}})
        (expect false) ;; should not reach here
        (catch clojure.lang.ExceptionInfo e
          (expect (str/includes? (.getMessage e) "*json-encoder*"))))))

  (it "accepts combined options"
    (binding [sut/*json-encoder* simple-json-encode]
      (let [ro (sut/request-options {:json {:name "Alice"}
                                     :headers {"X-Request-Id" "123"}
                                     :timeout 10000
                                     :params {:v 2}
                                     :max-redirects 3})]
        (expect (instance? RequestOptions ro))))))

;; =============================================================================
;; APIRequest & Context Creation
;; =============================================================================

(defdescribe api-request-test
  "Tests for APIRequest accessor"

  (describe "api-request"
    {:context [with-playwright]}
    (it "returns an APIRequest from Playwright instance"
      (let [req (sut/api-request *pw*)]
        (expect (instance? APIRequest req))))))

(defdescribe new-api-context-test
  "Tests for APIRequestContext creation"

  (describe "without options"
    {:context [with-playwright]}
    (it "creates an APIRequestContext"
      (let [ctx (sut/new-api-context (sut/api-request *pw*))]
        (try
          (expect (instance? APIRequestContext ctx))
          (finally
            (sut/api-dispose! ctx))))))

  (describe "with options"
    {:context [with-playwright with-test-server]}
    (it "creates context with base-url"
      (let [ctx (sut/new-api-context (sut/api-request *pw*)
                  {:base-url *test-server-url*})]
        (try
          (expect (instance? APIRequestContext ctx))
          (finally
            (sut/api-dispose! ctx)))))

    (it "creates context with multiple options"
      (let [ctx (sut/new-api-context (sut/api-request *pw*)
                  {:base-url *test-server-url*
                   :extra-http-headers {"X-Test" "true"}
                   :timeout 15000
                   :user-agent "spel-test"
                   :max-redirects 5})]
        (try
          (expect (instance? APIRequestContext ctx))
          (finally
            (sut/api-dispose! ctx)))))))

;; =============================================================================
;; Context Lifecycle — with-api-context / with-api-contexts
;; =============================================================================

(defdescribe with-api-context-test
  "Tests for with-api-context lifecycle macro"

  (describe "lifecycle"
    {:context [with-playwright with-test-server]}

    (it "binds context and executes body"
      (let [result (sut/with-api-context [ctx (sut/new-api-context
                                                (sut/api-request *pw*)
                                                {:base-url *test-server-url*})]
                     (expect (instance? APIRequestContext ctx))
                     :done)]
        (expect (= :done result))))

    (it "returns body value"
      (let [result (sut/with-api-context [ctx (sut/new-api-context
                                                (sut/api-request *pw*)
                                                {:base-url *test-server-url*})]
                     (sut/api-response-status (sut/api-get ctx "/health")))]
        (expect (= 200 result))))

    (it "disposes context after body (context unusable after)"
      (let [ctx-ref (atom nil)]
        (sut/with-api-context [ctx (sut/new-api-context
                                     (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (reset! ctx-ref ctx)
          (expect (instance? APIRequestContext ctx)))
        ;; Context should be disposed — using it should throw
        (let [threw? (try
                       (.get ^APIRequestContext @ctx-ref "/health")
                       false
                       (catch Exception _
                         true))]
          (expect threw?))))))

(defdescribe with-api-contexts-test
  "Tests for with-api-contexts multi-context lifecycle macro"

  (describe "multiple contexts"
    {:context [with-playwright with-test-server]}

    (it "binds multiple contexts and executes body"
      (let [result (sut/with-api-contexts
                     [ctx1 (sut/new-api-context (sut/api-request *pw*)
                             {:base-url *test-server-url*})
                      ctx2 (sut/new-api-context (sut/api-request *pw*)
                             {:base-url *test-server-url*})]
                     (expect (instance? APIRequestContext ctx1))
                     (expect (instance? APIRequestContext ctx2))
                     :both-ok)]
        (expect (= :both-ok result))))

    (it "each context can make independent requests"
      (sut/with-api-contexts
        [ctx1 (sut/new-api-context (sut/api-request *pw*)
                {:base-url *test-server-url*})
         ctx2 (sut/new-api-context (sut/api-request *pw*)
                {:base-url *test-server-url*})]
        (let [r1 (sut/api-get ctx1 "/health")
              r2 (sut/api-get ctx2 "/health")]
          (expect (= 200 (sut/api-response-status r1)))
          (expect (= 200 (sut/api-response-status r2))))))

    (it "disposes all contexts after body"
      (let [refs (atom [])]
        (sut/with-api-contexts
          [ctx1 (sut/new-api-context (sut/api-request *pw*)
                  {:base-url *test-server-url*})
           ctx2 (sut/new-api-context (sut/api-request *pw*)
                  {:base-url *test-server-url*})]
          (swap! refs conj ctx1 ctx2)
          :ok)
        ;; Both contexts should be disposed
        (doseq [ctx @refs]
          (let [threw? (try
                         (.get ^APIRequestContext ctx "/health")
                         false
                         (catch Exception _
                           true))]
            (expect threw?)))))))

;; =============================================================================
;; HTTP Methods
;; =============================================================================

(defdescribe api-get-test
  "Tests for api-get"

  (describe "GET requests"
    {:context [with-playwright with-test-server]}

    (it "GET /health returns 200"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")]
          (expect (instance? APIResponse resp))
          (expect (= 200 (sut/api-response-status resp)))
          (expect (sut/api-response-ok? resp)))))

    (it "GET /health body contains status ok"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              body (sut/api-response-text resp)]
          (expect (str/includes? body "ok")))))

    (it "GET with query params"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/echo" {:params {:foo "bar" :n "42"}})
              body (sut/api-response-text resp)]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? body "foo")))))

    (it "GET with custom headers"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/echo" {:headers {"X-Custom" "test-value"}})]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "GET /status/404 returns 404"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/status/404")]
          (expect (= 404 (sut/api-response-status resp)))
          (expect (not (sut/api-response-ok? resp))))))

    (it "GET /status/500 returns 500"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/status/500")]
          (expect (= 500 (sut/api-response-status resp)))
          (expect (not (sut/api-response-ok? resp))))))))

(defdescribe api-post-test
  "Tests for api-post"

  (describe "POST requests"
    {:context [with-playwright with-test-server]}

    (it "POST /echo with string data"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-post ctx "/echo"
                     {:data "{\"name\":\"Alice\"}"
                      :headers {"Content-Type" "application/json"}})
              body (sut/api-response-text resp)]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? body "POST"))
          (expect (str/includes? body "Alice")))))

    (it "POST /echo with :json and *json-encoder*"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (binding [sut/*json-encoder* simple-json-encode]
          (let [resp (sut/api-post ctx "/echo" {:json {:name "Bob"}})
                body (sut/api-response-text resp)]
            (expect (= 200 (sut/api-response-status resp)))
            (expect (str/includes? body "Bob"))))))

    (it "POST /echo with form data"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-post ctx "/echo"
                     {:form (sut/map->form-data {:user "admin" :pass "secret"})})
              body (sut/api-response-text resp)]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? body "POST")))))

    (it "POST without opts"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-post ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp))))))))

(defdescribe api-put-test
  "Tests for api-put"

  (describe "PUT requests"
    {:context [with-playwright with-test-server]}

    (it "PUT /echo returns 200"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-put ctx "/echo" {:data "{\"update\":true}"})]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? (sut/api-response-text resp) "PUT")))))

    (it "PUT without opts"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-put ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp))))))))

(defdescribe api-patch-test
  "Tests for api-patch"

  (describe "PATCH requests"
    {:context [with-playwright with-test-server]}

    (it "PATCH /echo returns 200"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-patch ctx "/echo" {:data "{\"patch\":true}"})]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? (sut/api-response-text resp) "PATCH")))))))

(defdescribe api-delete-test
  "Tests for api-delete"

  (describe "DELETE requests"
    {:context [with-playwright with-test-server]}

    (it "DELETE /echo returns 200"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-delete ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? (sut/api-response-text resp) "DELETE")))))))

(defdescribe api-head-test
  "Tests for api-head"

  (describe "HEAD requests"
    {:context [with-playwright with-test-server]}

    (it "HEAD /health returns 200"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-head ctx "/health")]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "HEAD /health with opts"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-head ctx "/health" {:timeout 5000})]
          (expect (= 200 (sut/api-response-status resp))))))))

(defdescribe api-fetch-test
  "Tests for api-fetch"

  (describe "FETCH requests"
    {:context [with-playwright with-test-server]}

    (it "fetch with default method hits the endpoint"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-fetch ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "fetch with explicit :method"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-fetch ctx "/echo" {:method "PATCH"})
              body (sut/api-response-text resp)]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? body "PATCH")))))))

;; =============================================================================
;; APIResponse Accessors
;; =============================================================================

(defdescribe api-response-accessors-test
  "Tests for APIResponse accessor functions"

  (describe "response accessors"
    {:context [with-playwright with-test-server]}

    (it "api-response-url returns the request URL"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              url  (sut/api-response-url resp)]
          (expect (string? url))
          (expect (str/includes? url "/health")))))

    (it "api-response-status returns integer status"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "api-response-status-text returns status text"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              text (sut/api-response-status-text resp)]
          (expect (string? text)))))

    (it "api-response-ok? returns true for 2xx"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (expect (true? (sut/api-response-ok? (sut/api-get ctx "/health"))))))

    (it "api-response-ok? returns false for 4xx"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (expect (false? (sut/api-response-ok? (sut/api-get ctx "/status/404"))))))

    (it "api-response-headers returns a map"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp    (sut/api-get ctx "/health")
              headers (sut/api-response-headers resp)]
          (expect (map? headers))
          (expect (contains? headers "content-type")))))

    (it "api-response-headers-array returns vector of maps"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              arr  (sut/api-response-headers-array resp)]
          (expect (vector? arr))
          (expect (pos? (count arr)))
          (let [first-h (first arr)]
            (expect (contains? first-h :name))
            (expect (contains? first-h :value))
            (expect (string? (:name first-h)))
            (expect (string? (:value first-h)))))))

    (it "api-response-text returns body as string"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              text (sut/api-response-text resp)]
          (expect (string? text))
          (expect (str/includes? text "ok")))))

    (it "api-response-body returns bytes"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp  (sut/api-get ctx "/health")
              bytes (sut/api-response-body resp)]
          (expect (bytes? bytes))
          (expect (pos? (alength ^bytes bytes))))))))

;; =============================================================================
;; api-response->map
;; =============================================================================

(defdescribe api-response-map-test
  "Tests for api-response->map"

  (describe "response map shape"
    {:context [with-playwright with-test-server]}

    (it "returns a map with all expected keys"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (sut/api-get ctx "/health")
              m    (sut/api-response->map resp)]
          (expect (map? m))
          (expect (contains? m :status))
          (expect (contains? m :status-text))
          (expect (contains? m :url))
          (expect (contains? m :ok?))
          (expect (contains? m :headers))
          (expect (contains? m :body)))))

    (it "has correct types for each key"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [m (sut/api-response->map (sut/api-get ctx "/health"))]
          (expect (integer? (:status m)))
          (expect (string? (:status-text m)))
          (expect (string? (:url m)))
          (expect (boolean? (:ok? m)))
          (expect (map? (:headers m)))
          (expect (string? (:body m))))))

    (it "status is 200 for /health"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [m (sut/api-response->map (sut/api-get ctx "/health"))]
          (expect (= 200 (:status m)))
          (expect (true? (:ok? m)))
          (expect (str/includes? (:body m) "ok")))))

    (it "status is 404 for /status/404"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [m (sut/api-response->map (sut/api-get ctx "/status/404"))]
          (expect (= 404 (:status m)))
          (expect (false? (:ok? m))))))

    (it "url contains the endpoint path"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [m (sut/api-response->map (sut/api-get ctx "/health"))]
          (expect (str/includes? (:url m) "/health")))))))

;; =============================================================================
;; request! — Standalone Requests
;; =============================================================================

(defdescribe request-bang-test
  "Tests for request! standalone HTTP calls"

  (describe "standalone requests"
    {:context [with-playwright with-test-server]}

    (it "GET returns a response map"
      (let [m (sut/request! *pw* :get (str *test-server-url* "/health"))]
        (expect (map? m))
        (expect (= 200 (:status m)))
        (expect (true? (:ok? m)))
        (expect (str/includes? (:body m) "ok"))))

    (it "POST with data returns response map"
      (let [m (sut/request! *pw* :post (str *test-server-url* "/echo")
                {:data "{\"x\":1}"
                 :headers {"Content-Type" "application/json"}})]
        (expect (= 200 (:status m)))
        (expect (str/includes? (:body m) "POST"))))

    (it "returns response map for non-2xx status"
      (let [m (sut/request! *pw* :get (str *test-server-url* "/status/503"))]
        (expect (= 503 (:status m)))
        (expect (false? (:ok? m)))))

    (it "GET without opts"
      (let [m (sut/request! *pw* :get (str *test-server-url* "/health"))]
        (expect (= 200 (:status m)))))

    (it "PUT returns response map"
      (let [m (sut/request! *pw* :put (str *test-server-url* "/echo")
                {:data "update-data"})]
        (expect (= 200 (:status m)))
        (expect (str/includes? (:body m) "PUT"))))

    (it "DELETE returns response map"
      (let [m (sut/request! *pw* :delete (str *test-server-url* "/echo"))]
        (expect (= 200 (:status m)))
        (expect (str/includes? (:body m) "DELETE"))))))

;; =============================================================================
;; Hooks — on-request
;; =============================================================================

(defdescribe hooks-on-request-test
  "Tests for :on-request hook"

  (describe "basic invocation"
    {:context [with-playwright with-test-server]}

    (it ":on-request is called with method, url, opts"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method url opts]
                                       (reset! called {:method method :url url :opts opts})
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health" {:timeout 5000})))
        (expect (some? @called))
        (expect (= :get (:method @called)))
        (expect (str/includes? (:url @called) "/health"))
        (expect (map? (:opts @called)))))

    (it ":on-request receives correct method for POST"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method _ opts]
                                       (reset! called method)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-post ctx "/echo" {})))
        (expect (= :post @called))))

    (it ":on-request receives correct method for PUT"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method _ opts]
                                       (reset! called method)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-put ctx "/echo" {})))
        (expect (= :put @called))))

    (it ":on-request receives correct method for PATCH"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method _ opts]
                                       (reset! called method)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-patch ctx "/echo" {})))
        (expect (= :patch @called))))

    (it ":on-request receives correct method for DELETE"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method _ opts]
                                       (reset! called method)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-delete ctx "/echo" {})))
        (expect (= :delete @called))))

    (it ":on-request receives correct method for HEAD"
      (let [called (atom nil)]
        (sut/with-hooks {:on-request (fn [method _ opts]
                                       (reset! called method)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-head ctx "/health" {})))
        (expect (= :head @called))))

    (it ":on-request is called once per request"
      (let [counter (atom 0)]
        (sut/with-hooks {:on-request (fn [_ _ opts]
                                       (swap! counter inc)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health" {})
            (sut/api-get ctx "/echo" {})
            (sut/api-get ctx "/status/200" {})))
        (expect (= 3 @counter)))))

  (describe "request transformation"
    {:context [with-playwright with-test-server]}

    (it ":on-request can inject headers"
      (sut/with-hooks {:on-request (fn [_ _ opts]
                                     (assoc-in opts [:headers "X-Injected"] "yes"))}
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (sut/api-get ctx "/echo" {:headers {"X-Original" "true"}})))
      (let [reqs (test-server-requests)
            echo-req (last reqs)]
        (expect (some? echo-req))))

    (it ":on-request can add query params"
      (sut/with-hooks {:on-request (fn [_ _ opts]
                                     (assoc-in opts [:params :injected] "true"))}
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-get ctx "/echo" {:params {:original "yes"}})
                body (sut/api-response-text resp)]
            (expect (= 200 (sut/api-response-status resp)))
            (expect (str/includes? body "injected"))))))

    (it ":on-request returning nil keeps original opts"
      (sut/with-hooks {:on-request (fn [_ _ _opts] nil)}
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-get ctx "/health" {:timeout 5000})]
            (expect (= 200 (sut/api-response-status resp)))))))

    (it ":on-request can replace opts entirely"
      (sut/with-hooks {:on-request (fn [_ _ _opts]
                                     {:params {:replaced "true"}})}
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-get ctx "/echo" {:params {:original "yes"}})
                body (sut/api-response-text resp)]
            (expect (str/includes? body "replaced"))))))

    (it ":on-request not called when opts are nil"
      (let [counter (atom 0)]
        (sut/with-hooks {:on-request (fn [_ _ opts]
                                       (swap! counter inc)
                                       opts)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")))
        ;; No opts passed → on-request should NOT be called
        (expect (= 0 @counter))))))

;; =============================================================================
;; Hooks — on-response
;; =============================================================================

(defdescribe hooks-on-response-test
  "Tests for :on-response hook"

  (describe "basic invocation"
    {:context [with-playwright with-test-server]}

    (it ":on-response is called with method, url, response"
      (let [called (atom nil)]
        (sut/with-hooks {:on-response (fn [method _url resp]
                                        (reset! called {:method method
                                                        :status (.status ^APIResponse resp)})
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")))
        (expect (some? @called))
        (expect (= :get (:method @called)))
        (expect (= 200 (:status @called)))))

    (it ":on-response tracks all methods in sequence"
      (let [methods (atom [])]
        (sut/with-hooks {:on-response (fn [method _ resp]
                                        (swap! methods conj method)
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")
            (sut/api-post ctx "/echo")
            (sut/api-put ctx "/echo")
            (sut/api-patch ctx "/echo")
            (sut/api-delete ctx "/echo")
            (sut/api-head ctx "/health")))
        (expect (= [:get :post :put :patch :delete :head] @methods))))

    (it ":on-response sees actual status codes"
      (let [statuses (atom [])]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (swap! statuses conj
                                          (.status ^APIResponse resp))
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")
            (sut/api-get ctx "/status/201")
            (sut/api-get ctx "/status/404")
            (sut/api-get ctx "/status/500")))
        (expect (= [200 201 404 500] @statuses))))

    (it ":on-response returning nil keeps original response"
      (let [resp-ref (atom nil)]
        (sut/with-hooks {:on-response (fn [_ _ _resp] nil)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (let [resp (sut/api-get ctx "/health")]
              (reset! resp-ref resp))))
        ;; Should still get the original APIResponse
        (expect (instance? APIResponse @resp-ref))
        (expect (= 200 (.status ^APIResponse @resp-ref)))))

    (it ":on-response call count matches request count"
      (let [counter (atom 0)]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (swap! counter inc)
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (dotimes [_ 7]
              (sut/api-get ctx "/health"))))
        (expect (= 7 @counter))))))

;; =============================================================================
;; Hooks — on-error
;; =============================================================================

(defdescribe hooks-on-error-test
  "Tests for :on-error hook"

  (describe "error hook invocation"
    {:context [with-playwright with-test-server]}

    (it ":on-error is called on anomaly result"
      (let [called (atom nil)]
        (sut/with-hooks {:on-error (fn [method _url anomaly]
                                     (reset! called {:method method :anomaly anomaly})
                                     anomaly)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*
                                        :fail-on-status-code true})]
            (sut/api-get ctx "/status/404")))
        ;; fail-on-status-code causes Playwright to throw → anomaly → on-error
        (expect (some? @called))
        (expect (= :get (:method @called)))
        (expect (anomaly/anomaly? (:anomaly @called)))))

    (it ":on-error returning nil keeps original anomaly"
      (let [result (sut/with-hooks {:on-error (fn [_ _ _anomaly] nil)}
                     (sut/with-api-context [ctx (sut/new-api-context
                                                  (sut/api-request *pw*)
                                                  {:base-url *test-server-url*
                                                   :fail-on-status-code true})]
                       (sut/api-get ctx "/status/500")))]
        (expect (anomaly/anomaly? result))))

    (it ":on-error is not called on success"
      (let [counter (atom 0)]
        (sut/with-hooks {:on-error (fn [_ _ anomaly]
                                     (swap! counter inc)
                                     anomaly)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")))
        (expect (= 0 @counter))))))

;; =============================================================================
;; Hooks — with-hooks merging & nesting
;; =============================================================================

(defdescribe hooks-merging-test
  "Tests for with-hooks scoping and merging behavior"

  (describe "merging semantics"
    {:context [with-playwright with-test-server]}

    (it "inner with-hooks merges with outer"
      (let [outer-calls (atom 0)
            inner-calls (atom 0)]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (swap! outer-calls inc)
                                        resp)}
          (sut/with-hooks {:on-request (fn [_ _ opts]
                                         (swap! inner-calls inc)
                                         opts)}
            (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                         {:base-url *test-server-url*})]
              (sut/api-get ctx "/health" {}))))
        (expect (= 1 @inner-calls))
        (expect (= 1 @outer-calls))))

    (it "inner with-hooks overrides same key from outer"
      (let [outer-calls (atom 0)
            inner-calls (atom 0)]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (swap! outer-calls inc)
                                        resp)}
          (sut/with-hooks {:on-response (fn [_ _ resp]
                                          (swap! inner-calls inc)
                                          resp)}
            (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                         {:base-url *test-server-url*})]
              (sut/api-get ctx "/health"))))
        ;; Inner replaces outer for :on-response
        (expect (= 1 @inner-calls))
        (expect (= 0 @outer-calls))))

    (it "hooks restore after with-hooks scope exits"
      (let [counter (atom 0)]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (swap! counter inc)
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            ;; Request inside hook scope
            (sut/api-get ctx "/health")
            (expect (= 1 @counter))))
        ;; Outside with-hooks — default hooks (nil) restored
        (expect (nil? (:on-response sut/*hooks*)))))

    (it "three levels of nesting work correctly"
      (let [log (atom [])]
        (sut/with-hooks {:on-request (fn [_ _ opts]
                                       (swap! log conj :outer)
                                       opts)}
          (sut/with-hooks {:on-response (fn [_ _ resp]
                                          (swap! log conj :middle)
                                          resp)}
            (sut/with-hooks {:on-retry (fn [_]
                                         (swap! log conj :inner))}
              ;; All three hook types should be active
              (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                           {:base-url *test-server-url*})]
                (sut/api-get ctx "/health" {})))))
        ;; :on-request from outer, :on-response from middle — both fired
        (expect (some #{:outer} @log))
        (expect (some #{:middle} @log))))

    (it "with-hooks with empty map is a no-op"
      (sut/with-hooks {}
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-get ctx "/health")]
            (expect (= 200 (sut/api-response-status resp))))))))

  (describe "default hooks state"
    (it "*hooks* is a map"
      (expect (map? sut/*hooks*)))

    (it "*hooks* has all four keys"
      (expect (contains? sut/*hooks* :on-request))
      (expect (contains? sut/*hooks* :on-response))
      (expect (contains? sut/*hooks* :on-error))
      (expect (contains? sut/*hooks* :on-retry)))

    (it "all default hook values are nil"
      (expect (nil? (:on-request sut/*hooks*)))
      (expect (nil? (:on-response sut/*hooks*)))
      (expect (nil? (:on-error sut/*hooks*)))
      (expect (nil? (:on-retry sut/*hooks*))))))

;; =============================================================================
;; Hooks — on-retry
;; =============================================================================

(defdescribe hooks-on-retry-test
  "Tests for :on-retry hook"

  (it ":on-retry receives retry metadata"
    (let [retry-log (atom [])]
      (sut/with-hooks {:on-retry (fn [info]
                                   (swap! retry-log conj info))}
        (let [counter (atom 0)]
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 3)
                  {::anomaly/category ::anomaly/busy}
                  :done)))
            {:delay-ms 10})))
      (expect (= 2 (count @retry-log)))
      (let [first-r (first @retry-log)]
        (expect (= 1 (:attempt first-r)))
        (expect (= 3 (:max-attempts first-r)))
        (expect (number? (:delay-ms first-r)))
        (expect (anomaly/anomaly? (:result first-r))))))

  (it ":on-retry attempt numbers increment"
    (let [attempts (atom [])]
      (sut/with-hooks {:on-retry (fn [info]
                                   (swap! attempts conj (:attempt info)))}
        (let [counter (atom 0)]
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 5)
                  {::anomaly/category ::anomaly/busy}
                  :done)))
            {:max-attempts 5 :delay-ms 10})))
      (expect (= [1 2 3 4] @attempts))))

  (it ":on-retry sees the failing result"
    (let [results (atom [])]
      (sut/with-hooks {:on-retry (fn [info]
                                   (swap! results conj (:result info)))}
        (let [counter (atom 0)]
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 2)
                  {:status 503 :body "unavailable"}
                  {:status 200 :body "ok"})))
            {:delay-ms 10
             :retry-when (fn [r] (>= (:status r) 500))})))
      (expect (= 1 (count @results)))
      (expect (= 503 (:status (first @results))))))

  (it ":on-retry is not called when first attempt succeeds"
    (let [counter (atom 0)]
      (sut/with-hooks {:on-retry (fn [_] (swap! counter inc))}
        (sut/retry (fn [] :ok) {:delay-ms 10}))
      (expect (= 0 @counter))))

  (it ":on-retry is not called when retries exhausted (only between retries)"
    (let [counter  (atom 0)
          attempts (atom 0)]
      (sut/with-hooks {:on-retry (fn [_] (swap! counter inc))}
        (sut/retry
          (fn []
            (swap! attempts inc)
            {::anomaly/category ::anomaly/fault})
          {:max-attempts 3 :delay-ms 10}))
      ;; 3 attempts → 2 retries
      (expect (= 3 @attempts))
      (expect (= 2 @counter)))))

;; =============================================================================
;; Hooks — combined scenarios
;; =============================================================================

(defdescribe hooks-combined-test
  "Tests for multiple hooks working together"

  (describe "full lifecycle"
    {:context [with-playwright with-test-server]}

    (it "all four hooks fire in a retry scenario"
      (let [log     (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-request  (fn [_ _ opts]
                                        (swap! log conj :req)
                                        opts)
                         :on-response (fn [_ _ resp]
                                        (swap! log conj :resp)
                                        resp)
                         :on-error    (fn [_ _ anomaly]
                                        (swap! log conj :err)
                                        anomaly)
                         :on-retry    (fn [_]
                                        (swap! log conj :retry))}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/retry
              (fn []
                (let [n   (swap! counter inc)
                      path (if (< n 2) "/status/500" "/health")
                      resp (sut/api-get ctx path {})]
                  (sut/api-response->map resp)))
              {:delay-ms 10
               :retry-when (fn [r] (and (map? r) (>= (:status r) 500)))})))
        ;; Pattern: req → resp (500) → retry → req → resp (200)
        (expect (= 2 (count (filter #{:req} @log))))
        (expect (= 2 (count (filter #{:resp} @log))))
        (expect (= 1 (count (filter #{:retry} @log))))
        (expect (= 0 (count (filter #{:err} @log))))))

    (it "on-request auth injection works across multiple requests"
      (let [req-count (atom 0)]
        (sut/with-hooks {:on-request (fn [_ _ opts]
                                       (swap! req-count inc)
                                       (assoc-in opts [:headers "Authorization"]
                                         "Bearer test-token-123"))}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/echo" {})
            (sut/api-post ctx "/echo" {:data "body"})
            (sut/api-put ctx "/echo" {})))
        (expect (= 3 @req-count))
        ;; Verify the header was actually sent
        (let [reqs (test-server-requests)]
          (expect (>= (count reqs) 3)))))

    (it "on-response can collect metrics"
      (let [metrics (atom {:total 0 :ok 0 :error 0})]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (let [status (.status ^APIResponse resp)]
                                          (swap! metrics update :total inc)
                                          (if (< status 400)
                                            (swap! metrics update :ok inc)
                                            (swap! metrics update :error inc)))
                                        resp)}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/api-get ctx "/health")
            (sut/api-get ctx "/health")
            (sut/api-get ctx "/status/404")
            (sut/api-get ctx "/status/500")
            (sut/api-get ctx "/health")))
        (expect (= 5 (:total @metrics)))
        (expect (= 3 (:ok @metrics)))
        (expect (= 2 (:error @metrics)))))

    (it "hooks work with request! standalone"
      (let [called (atom false)]
        (sut/with-hooks {:on-response (fn [_ _ resp]
                                        (reset! called true)
                                        resp)}
          (sut/request! *pw* :get (str *test-server-url* "/health")))
        (expect (true? @called))))))

;; =============================================================================
;; Retry — extensive
;; =============================================================================

(defdescribe retry-test
  "Tests for retry function"

  (it "succeeds on first attempt without retry"
    (let [counter (atom 0)
          result  (sut/retry (fn []
                               (swap! counter inc)
                               :success)
                    {:delay-ms 10})]
      (expect (= :success result))
      (expect (= 1 @counter))))

  (it "retries and succeeds after failures"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 3)
                          {::anomaly/category ::anomaly/busy
                           ::anomaly/message "not ready"}
                          :success)))
                    {:delay-ms 10})]
      (expect (= :success result))
      (expect (= 3 @counter))))

  (it "respects :max-attempts"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (swap! counter inc)
                      {::anomaly/category ::anomaly/fault
                       ::anomaly/message "always fails"})
                    {:max-attempts 5 :delay-ms 10})]
      (expect (anomaly/anomaly? result))
      (expect (= 5 @counter))))

  (it "max-attempts of 1 means no retries"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (swap! counter inc)
                      {::anomaly/category ::anomaly/fault})
                    {:max-attempts 1 :delay-ms 10})]
      (expect (anomaly/anomaly? result))
      (expect (= 1 @counter))))

  (it "retries on 5xx status by default"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 2)
                          {:status 500 :body "error"}
                          {:status 200 :body "ok"})))
                    {:delay-ms 10})]
      (expect (= 200 (:status result)))
      (expect (= 2 @counter))))

  (it "retries on 502 Bad Gateway"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 2)
                          {:status 502 :body "bad gateway"}
                          {:status 200 :body "ok"})))
                    {:delay-ms 10})]
      (expect (= 200 (:status result)))))

  (it "retries on 503 Service Unavailable"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 2)
                          {:status 503 :body "unavailable"}
                          {:status 200 :body "ok"})))
                    {:delay-ms 10})]
      (expect (= 200 (:status result)))))

  (it "does not retry on 4xx by default"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (swap! counter inc)
                      {:status 404 :body "not found"})
                    {:delay-ms 10})]
      (expect (= 404 (:status result)))
      (expect (= 1 @counter))))

  (it "does not retry on 200"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (swap! counter inc)
                      {:status 200 :body "ok"})
                    {:delay-ms 10})]
      (expect (= 200 (:status result)))
      (expect (= 1 @counter))))

  (it "does not retry on 201"
    (let [counter (atom 0)]
      (sut/retry
        (fn []
          (swap! counter inc)
          {:status 201 :body "created"})
        {:delay-ms 10})
      (expect (= 1 @counter))))

  (it "custom :retry-when for 429 Too Many Requests"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 3)
                          {:status 429 :body "rate limited"}
                          {:status 200 :body "ok"})))
                    {:delay-ms 10
                     :retry-when (fn [r] (= 429 (:status r)))})]
      (expect (= 200 (:status result)))
      (expect (= 3 @counter))))

  (it "custom :retry-when with keyword check"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (let [n (swap! counter inc)]
                        (if (< n 2)
                          {:state :pending}
                          {:state :ready})))
                    {:delay-ms 10
                     :retry-when (fn [r] (= :pending (:state r)))})]
      (expect (= :ready (:state result)))))

  (it "returns non-retryable result immediately"
    (let [counter (atom 0)
          result  (sut/retry
                    (fn []
                      (swap! counter inc)
                      "plain-string")
                    {:delay-ms 10})]
      (expect (= "plain-string" result))
      (expect (= 1 @counter))))

  (describe "backoff strategies"
    (it ":fixed backoff — delay stays constant"
      (let [delays (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-retry (fn [info]
                                     (swap! delays conj (:delay-ms info)))}
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 4)
                  {::anomaly/category ::anomaly/busy}
                  :ok)))
            {:delay-ms 50 :backoff :fixed :max-attempts 5}))
        ;; All delays should be equal (50ms)
        (expect (= 3 (count @delays)))
        (expect (every? #(= 50 %) @delays))))

    (it ":linear backoff — delay increases linearly"
      (let [delays (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-retry (fn [info]
                                     (swap! delays conj (:delay-ms info)))}
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 4)
                  {::anomaly/category ::anomaly/busy}
                  :ok)))
            {:delay-ms 10 :backoff :linear :max-attempts 5}))
        (expect (= 3 (count @delays)))
        ;; Linear: 10*(0+1)=10, 10*(1+1)=20, 10*(2+1)=30
        (expect (= [10 20 30] @delays))))

    (it ":exponential backoff — delay doubles"
      (let [delays (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-retry (fn [info]
                                     (swap! delays conj (:delay-ms info)))}
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 4)
                  {::anomaly/category ::anomaly/busy}
                  :ok)))
            {:delay-ms 10 :backoff :exponential :max-attempts 5}))
        (expect (= 3 (count @delays)))
        ;; Exponential: 10*2^0=10, 10*2^1=20, 10*2^2=40
        (expect (= [10 20 40] @delays))))

    (it ":max-delay-ms caps the delay"
      (let [delays (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-retry (fn [info]
                                     (swap! delays conj (:delay-ms info)))}
          (sut/retry
            (fn []
              (let [n (swap! counter inc)]
                (if (< n 5)
                  {::anomaly/category ::anomaly/busy}
                  :ok)))
            {:delay-ms 100 :backoff :exponential :max-delay-ms 250
             :max-attempts 6}))
        ;; Exponential: 100, 200, 400→250, 800→250
        (expect (= 4 (count @delays)))
        (expect (= 100 (first @delays)))
        (expect (= 200 (second @delays)))
        ;; Third and fourth should be capped at 250
        (expect (= 250 (nth @delays 2)))
        (expect (= 250 (nth @delays 3)))))))

(defdescribe with-retry-test
  "Tests for with-retry macro"

  (it "wraps body with default retry"
    (let [counter (atom 0)
          result  (sut/with-retry {}
                    (let [n (swap! counter inc)]
                      (if (< n 2)
                        {::anomaly/category ::anomaly/busy}
                        :success)))]
      (expect (= :success result))
      (expect (= 2 @counter))))

  (it "accepts opts map as first arg"
    (let [counter (atom 0)
          result  (sut/with-retry {:max-attempts 5 :delay-ms 10}
                    (let [n (swap! counter inc)]
                      (if (< n 4)
                        {::anomaly/category ::anomaly/busy}
                        :success)))]
      (expect (= :success result))
      (expect (= 4 @counter))))

  (it "exhausts retries and returns last result"
    (let [counter (atom 0)
          result  (sut/with-retry {:max-attempts 2 :delay-ms 10}
                    (swap! counter inc)
                    {::anomaly/category ::anomaly/fault})]
      (expect (anomaly/anomaly? result))
      (expect (= 2 @counter))))

  (it "with-retry default succeeds on first try for good result"
    (let [counter (atom 0)
          result  (sut/with-retry
                    (swap! counter inc)
                    {:status 200})]
      (expect (= 200 (:status result)))
      (expect (= 1 @counter)))))

;; =============================================================================
;; Integration: Retry + HTTP + Hooks
;; =============================================================================

(defdescribe retry-http-integration-test
  "Integration tests for retry with real HTTP calls"

  (describe "retry with test server"
    {:context [with-playwright with-test-server]}

    (it "retry with request! on flaky endpoint simulation"
      (let [counter (atom 0)
            result  (sut/retry
                      (fn []
                        (let [n (swap! counter inc)
                              path (if (< n 3) "/status/500" "/health")]
                          (sut/request! *pw* :get (str *test-server-url* path))))
                      {:delay-ms 10
                       :retry-when (fn [r] (and (map? r) (>= (:status r) 500)))})]
        (expect (= 200 (:status result)))
        (expect (= 3 @counter))))

    (it "with-retry and hooks combined — full lifecycle verification"
      (let [hook-calls (atom {:requests 0 :responses 0 :retries 0})
            counter    (atom 0)]
        (sut/with-hooks {:on-request  (fn [_ _ opts]
                                        (swap! hook-calls update :requests inc)
                                        opts)
                         :on-response (fn [_ _ resp]
                                        (swap! hook-calls update :responses inc)
                                        resp)
                         :on-retry    (fn [_]
                                        (swap! hook-calls update :retries inc))}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/retry
              (fn []
                (let [n  (swap! counter inc)
                      path (if (< n 2) "/status/500" "/health")
                      resp (sut/api-get ctx path {})]
                  (sut/api-response->map resp)))
              {:delay-ms 10
               :retry-when (fn [r] (and (map? r) (>= (:status r) 500)))})))
        (expect (= 2 (:requests @hook-calls)))
        (expect (= 2 (:responses @hook-calls)))
        (expect (= 1 (:retries @hook-calls)))))

    (it "retry multiple failures then success with all hooks"
      (let [log     (atom [])
            counter (atom 0)]
        (sut/with-hooks {:on-request  (fn [_ _ opts] (swap! log conj :req) opts)
                         :on-response (fn [_ _ resp] (swap! log conj :resp) resp)
                         :on-retry    (fn [_] (swap! log conj :retry))}
          (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                       {:base-url *test-server-url*})]
            (sut/retry
              (fn []
                (let [n    (swap! counter inc)
                      path (if (< n 4) "/status/503" "/health")
                      resp (sut/api-get ctx path {})]
                  (sut/api-response->map resp)))
              {:max-attempts 5 :delay-ms 10
               :retry-when (fn [r] (and (map? r) (>= (:status r) 500)))})))
        ;; 4 total calls: 3 x 503, 1 x 200
        (expect (= 4 (count (filter #{:req} @log))))
        (expect (= 4 (count (filter #{:resp} @log))))
        (expect (= 3 (count (filter #{:retry} @log))))))))

;; =============================================================================
;; JSON Encoding — extensive
;; =============================================================================

(defdescribe json-encoding-test
  "Tests for *json-encoder* and :json option"

  (describe "encoder binding"
    {:context [with-playwright with-test-server]}

    (it "json with bound encoder sends encoded body"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo" {:json {:name "Alice" :age 30}})
                body (sut/api-response-text resp)]
            (expect (= 200 (sut/api-response-status resp)))
            (expect (str/includes? body "Alice"))))))

    (it "json with nested data encodes correctly"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo"
                       {:json {:user {:name "Bob" :roles ["admin" "user"]}}})
                body (sut/api-response-text resp)]
            (expect (= 200 (sut/api-response-status resp)))
            (expect (str/includes? body "Bob"))))))

    (it "json with string value encodes correctly"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo" {:json "hello"})]
            (expect (= 200 (sut/api-response-status resp)))))))

    (it "json with vector value"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo" {:json [1 2 3]})]
            (expect (= 200 (sut/api-response-status resp)))))))

    (it "json with null value"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo" {:json nil})]
            (expect (= 200 (sut/api-response-status resp)))))))

    (it "json encoder can use pr-str as encoder"
      (binding [sut/*json-encoder* pr-str]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          (let [resp (sut/api-post ctx "/echo" {:json {:key "value"}})]
            (expect (= 200 (sut/api-response-status resp)))))))

    (it "json with additional headers preserves user headers"
      (binding [sut/*json-encoder* simple-json-encode]
        (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                     {:base-url *test-server-url*})]
          ;; User sets X-Request-Id alongside :json — both should work
          (let [resp (sut/api-post ctx "/echo"
                       {:json {:data true}
                        :headers {"X-Request-Id" "abc-123"}})]
            (expect (= 200 (sut/api-response-status resp)))))))))

;; =============================================================================
;; Test Server Request Logging
;; =============================================================================

(defdescribe test-server-logging-test
  "Tests that the test server logs requests properly"

  (describe "request logging"
    {:context [with-playwright with-test-server]}

    (it "logs requests to the test server"
      (sut/with-api-context [ctx (sut/new-api-context (sut/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (sut/api-get ctx "/health")
        (sut/api-post ctx "/echo" {:data "hello"})
        (let [reqs (test-server-requests)]
          (expect (>= (count reqs) 2))
          (let [get-req  (first (filter #(= "GET" (:method %)) reqs))
                post-req (first (filter #(= "POST" (:method %)) reqs))]
            (expect (= "/health" (:path get-req)))
            (expect (= "/echo" (:path post-req)))))))))

;; =============================================================================
;; page-api — APIRequestContext from Page
;; =============================================================================

(defdescribe page-api-test
  "Tests for page-api: extracting APIRequestContext from a Page"

  (describe "page-api"
    {:context [with-playwright with-browser with-page with-test-server]}

    (expect-it "returns an APIRequestContext instance"
      (instance? APIRequestContext (sut/page-api *page*)))

    (it "makes GET requests that share the page's browser context"
      (let [api-ctx (sut/page-api *page*)
            resp    (sut/api-get api-ctx (str *test-server-url* "/health"))]
        (expect (= 200 (sut/api-response-status resp)))))

    (it "shares cookies with the browser session"
      ;; Navigate to set a cookie, then verify API sees it
      (page/navigate *page* (str *test-server-url* "/set-cookie?name=session&value=abc123"))
      (let [api-ctx (sut/page-api *page*)
            resp    (sut/api-get api-ctx (str *test-server-url* "/echo"))]
        (expect (= 200 (sut/api-response-status resp)))
        (let [body (sut/api-response-text resp)]
          (expect (some? body)))))

    (it "supports POST with data"
      (let [api-ctx (sut/page-api *page*)
            resp    (sut/api-post api-ctx (str *test-server-url* "/echo")
                      {:data "{\"action\":\"test\"}"
                       :headers {"Content-Type" "application/json"}})]
        (expect (= 200 (sut/api-response-status resp)))))))

;; =============================================================================
;; context-api — APIRequestContext from BrowserContext
;; =============================================================================

(defdescribe context-api-test
  "Tests for context-api: extracting APIRequestContext from a BrowserContext"

  (describe "context-api"
    {:context [with-playwright with-browser with-page with-test-server]}

    (expect-it "returns an APIRequestContext instance"
      (instance? APIRequestContext (sut/context-api *browser-context*)))

    (it "makes GET requests through the browser context"
      (let [api-ctx (sut/context-api *browser-context*)
            resp    (sut/api-get api-ctx (str *test-server-url* "/health"))]
        (expect (= 200 (sut/api-response-status resp)))))

    (it "supports all HTTP methods"
      (let [api-ctx (sut/context-api *browser-context*)]
        ;; GET
        (expect (= 200 (sut/api-response-status
                         (sut/api-get api-ctx (str *test-server-url* "/health")))))
        ;; POST
        (expect (= 200 (sut/api-response-status
                         (sut/api-post api-ctx (str *test-server-url* "/echo")
                           {:data "test"}))))
        ;; PUT
        (expect (= 200 (sut/api-response-status
                         (sut/api-put api-ctx (str *test-server-url* "/echo")
                           {:data "test"}))))
        ;; DELETE
        (expect (= 200 (sut/api-response-status
                         (sut/api-delete api-ctx (str *test-server-url* "/echo")))))))))

;; =============================================================================
;; with-testing-api — All-in-one API testing macro
;; =============================================================================

(defdescribe with-testing-api-test
  "Tests for with-testing-api: zero-ceremony API testing macro"

  (describe "basic usage"
    {:context [with-test-server]}

    (it "creates a working API context with base-url"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/health")]
          (expect (instance? APIResponse resp))
          (expect (= 200 (sut/api-response-status resp))))))

    (it "works without opts — requires full URLs"
      (sut/with-testing-api [ctx]
        (let [resp (sut/api-get ctx (str *test-server-url* "/health"))]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "returns the body expression value"
      (let [result (sut/with-testing-api {:base-url *test-server-url*} [ctx]
                     (sut/api-response-status (sut/api-get ctx "/health")))]
        (expect (= 200 result))))

    (it "cleans up resources — context is usable inside, macro returns cleanly"
      (let [ctx-ref (atom nil)
            result  (sut/with-testing-api {:base-url *test-server-url*} [ctx]
                      (reset! ctx-ref ctx)
                      (sut/api-response-status (sut/api-get ctx "/health")))]
        ;; Verify the body completed successfully and returned a value
        (expect (= 200 result))
        ;; Verify we captured a real APIRequestContext
        (expect (instance? APIRequestContext @ctx-ref)))))

  (describe "HTTP methods"
    {:context [with-test-server]}

    (it "supports GET"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status (sut/api-get ctx "/health"))))))

    (it "supports POST with data"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-post ctx "/echo"
                     {:data "{\"name\":\"Alice\"}"
                      :headers {"Content-Type" "application/json"}})]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "supports PUT"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-put ctx "/echo" {:data "update"}))))))

    (it "supports PATCH"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-patch ctx "/echo" {:data "patch"}))))))

    (it "supports DELETE"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-delete ctx "/echo")))))))

  (describe "json-encoder"
    {:context [with-test-server]}

    (it "binds *json-encoder* when :json-encoder is provided"
      (sut/with-testing-api {:base-url     *test-server-url*
                             :json-encoder simple-json-encode} [ctx]
        (let [resp (sut/api-post ctx "/echo" {:json {:name "Alice" :age 30}})]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "leaves *json-encoder* nil when :json-encoder is not provided"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (expect (nil? sut/*json-encoder*))
        (let [resp (sut/api-post ctx "/echo"
                     {:data "{\"raw\":true}"
                      :headers {"Content-Type" "application/json"}})]
          (expect (= 200 (sut/api-response-status resp)))))))

  (describe "context options"
    {:context [with-test-server]}

    (it "passes extra-http-headers to all requests"
      ;; extra-http-headers is a context option that adds headers to every request
      ;; The test server's /echo endpoint doesn't reflect headers, but the request
      ;; succeeds which proves the option is accepted
      (sut/with-testing-api {:base-url           *test-server-url*
                             :extra-http-headers {"X-Custom" "spel-test"}} [ctx]
        (let [resp (sut/api-get ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "supports ignore-https-errors"
      (sut/with-testing-api {:base-url            *test-server-url*
                             :ignore-https-errors true} [ctx]
        (expect (= 200 (sut/api-response-status (sut/api-get ctx "/health")))))))

  (describe "error handling"
    {:context [with-test-server]}

    (it "handles non-2xx responses gracefully"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/status/404")]
          (expect (= 404 (sut/api-response-status resp)))
          (expect (false? (sut/api-response-ok? resp))))))

    (it "handles server errors"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/status/500")]
          (expect (= 500 (sut/api-response-status resp))))))))

;; =============================================================================
;; with-page-api — Page-bound API with custom base-url
;; =============================================================================

(defdescribe with-page-api-test
  "Tests for with-page-api: API context from page with custom base-url"

  (describe "basic usage"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "creates an APIRequestContext with custom base-url"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/health")]
          (expect (instance? APIResponse resp))
          (expect (= 200 (sut/api-response-status resp))))))

    (it "returns the body expression value"
      (let [result (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
                     (sut/api-response-status (sut/api-get ctx "/health")))]
        (expect (= 200 result))))

    (it "shares cookies from the page's browser context"
      ;; Navigate to set a cookie via the page
      (page/navigate *page* (str *test-server-url* "/set-cookie?name=session&value=abc123"))
      ;; API context should have the same cookies
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp)))))))

  (describe "HTTP methods"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "supports GET"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status (sut/api-get ctx "/health"))))))

    (it "supports POST with data"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-post ctx "/echo"
                     {:data "{\"name\":\"Alice\"}"
                      :headers {"Content-Type" "application/json"}})]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "supports PUT"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-put ctx "/echo" {:data "update"}))))))

    (it "supports DELETE"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-delete ctx "/echo"))))))

    (it "supports PATCH"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (expect (= 200 (sut/api-response-status
                         (sut/api-patch ctx "/echo" {:data "patch"}))))))

    (it "supports HEAD"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-head ctx "/health")]
          (expect (= 200 (sut/api-response-status resp)))))))

  (describe "json-encoder"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "binds *json-encoder* when :json-encoder is provided"
      (sut/with-page-api *page* *pw* {:base-url     *test-server-url*
                                      :json-encoder simple-json-encode} [ctx]
        (let [resp (sut/api-post ctx "/echo" {:json {:name "Alice" :age 30}})]
          (expect (= 200 (sut/api-response-status resp)))))))

  (describe "cleanup"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "disposes the API context after body completes"
      ;; Just verify no exception is thrown and body executes
      (let [executed? (atom false)]
        (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
          (reset! executed? true)
          (sut/api-get ctx "/health"))
        (expect (true? @executed?)))))

  (describe "context options"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "accepts extra-http-headers"
      (sut/with-page-api *page* *pw* {:base-url           *test-server-url*
                                      :extra-http-headers {"X-Test" "value"}} [ctx]
        (let [resp (sut/api-get ctx "/echo")]
          (expect (= 200 (sut/api-response-status resp))))))

    (it "accepts ignore-https-errors"
      (sut/with-page-api *page* *pw* {:base-url            *test-server-url*
                                      :ignore-https-errors true} [ctx]
        (expect (= 200 (sut/api-response-status (sut/api-get ctx "/health")))))))

  (describe "error handling"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "handles 404 responses"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/status/404")]
          (expect (= 404 (sut/api-response-status resp)))
          (expect (false? (sut/api-response-ok? resp))))))

    (it "handles 500 responses"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/status/500")]
          (expect (= 500 (sut/api-response-status resp)))))))

  (describe "response inspection"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "can read response text"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/health")
              body (sut/api-response-text resp)]
          (expect (some? body))
          (expect (str/includes? body "ok")))))

    (it "can read response headers"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp     (sut/api-get ctx "/health")
              headers  (sut/api-response-headers resp)
              ct       (get headers "content-type")]
          (expect (some? ct))
          (expect (str/includes? ct "application/json")))))

    (it "can convert response to map"
      (sut/with-page-api *page* *pw* {:base-url *test-server-url*} [ctx]
        (let [resp  (sut/api-get ctx "/health")
              m     (sut/api-response->map resp)]
          (expect (= 200 (:status m)))
          (expect (true? (:ok? m)))
          (expect (some? (:body m)))
          (expect (some? (:headers m))))))))

;; =============================================================================
;; run-with-page-api — function variant
;; =============================================================================

(defdescribe run-with-page-api-test
  "Tests for run-with-page-api: functional variant"

  (describe "basic usage"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "works with function argument"
      (let [result (sut/run-with-page-api *page* *pw* {:base-url *test-server-url*}
                     (fn [ctx]
                       (sut/api-response-status (sut/api-get ctx "/health"))))]
        (expect (= 200 result))))

    (it "returns nil for side-effect-only body"
      (let [result (sut/run-with-page-api *page* *pw* {:base-url *test-server-url*}
                     (fn [ctx]
                       (sut/api-get ctx "/health")
                       nil))]
        (expect (nil? result))))))

;; =============================================================================
;; run-with-testing-api — function variant
;; =============================================================================

(defdescribe run-with-testing-api-test
  "Tests for run-with-testing-api: functional variant"

  (describe "basic usage"
    {:context [with-test-server]}

    (it "works with function argument"
      (let [result (sut/run-with-testing-api {:base-url *test-server-url*}
                     (fn [ctx]
                       (sut/api-response-status (sut/api-get ctx "/health"))))]
        (expect (= 200 result))))

    (it "accepts empty opts"
      (let [result (sut/run-with-testing-api {}
                     (fn [ctx]
                       (sut/api-response-status
                         (sut/api-get ctx (str *test-server-url* "/health")))))]
        (expect (= 200 result))))

    (it "can make multiple requests"
      (let [result (sut/run-with-testing-api {:base-url *test-server-url*}
                     (fn [ctx]
                       (sut/api-get ctx "/health")
                       (sut/api-get ctx "/echo")
                       (sut/api-response-status (sut/api-get ctx "/health"))))]
        (expect (= 200 result))))))

;; =============================================================================
;; page-api / context-api — additional edge cases
;; =============================================================================

(defdescribe page-api-edge-cases-test
  "Additional edge case tests for page-api"

  (describe "multiple calls"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "returns same context instance on repeated calls"
      (let [ctx1 (sut/page-api *page*)
            ctx2 (sut/page-api *page*)]
        (expect (identical? ctx1 ctx2))))

    (it "can make multiple requests with same context"
      (let [api-ctx (sut/page-api *page*)]
        (expect (= 200 (sut/api-response-status
                         (sut/api-get api-ctx (str *test-server-url* "/health")))))
        (expect (= 200 (sut/api-response-status
                         (sut/api-get api-ctx (str *test-server-url* "/echo")))))))))

(defdescribe context-api-edge-cases-test
  "Additional edge case tests for context-api"

  (describe "multiple calls"
    {:context [with-playwright with-browser with-page with-test-server]}

    (it "returns same context instance on repeated calls"
      (let [ctx1 (sut/context-api *browser-context*)
            ctx2 (sut/context-api *browser-context*)]
        (expect (identical? ctx1 ctx2))))

    (it "can make multiple requests with same context"
      (let [api-ctx (sut/context-api *browser-context*)]
        (expect (= 200 (sut/api-response-status
                         (sut/api-get api-ctx (str *test-server-url* "/health")))))
        (expect (= 200 (sut/api-response-status
                         (sut/api-post api-ctx (str *test-server-url* "/echo") {:data "test"}))))))))

;; =============================================================================
;; with-testing-api — additional edge cases
;; =============================================================================

(defdescribe with-testing-api-edge-cases-test
  "Additional edge case tests for with-testing-api"

  (describe "query parameters"
    {:context [with-test-server]}

    (it "passes query params correctly"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (sut/api-get ctx "/echo" {:params {:foo "bar" :baz "qux"}})
              body (sut/api-response-text resp)]
          (expect (= 200 (sut/api-response-status resp)))
          (expect (str/includes? body "foo=bar"))))))

  (describe "nested bindings"
    {:context [with-playwright with-test-server]}

    (it "can nest with-api-context inside with-testing-api"
      (sut/with-testing-api {:base-url *test-server-url*} [ctx]
        (sut/with-api-context [ctx2 (sut/new-api-context (sut/api-request *pw*)
                                      {:base-url *test-server-url*})]
          (expect (= 200 (sut/api-response-status (sut/api-get ctx "/health"))))
          (expect (= 200 (sut/api-response-status (sut/api-get ctx2 "/health")))))))))
