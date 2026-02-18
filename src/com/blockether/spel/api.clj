(ns com.blockether.spel.api
  "Playwright API testing — APIRequest, APIRequestContext, APIResponse.

    Provides idiomatic Clojure wrappers for Playwright's built-in HTTP client.
    Supports all HTTP methods, form data, query params, custom headers, and
    lifecycle management via `with-api-context`.

    Usage:
    (require '[com.blockether.spel.core :as pw]
             '[com.blockether.spel.api :as api])

    (core/with-playwright [playwright]
      (api/with-api-context [ctx (-> playwright api/api-request
                                     (api/new-api-context {:base-url \"https://api.example.com\"}))]
        (let [resp (api/get ctx \"/users\" {:params {:page 1 :limit 10}})]
          (println (api/response->map resp)))))

    ;; Response map:
    ;; {:status 200
    ;;  :status-text \"OK\"
    ;;  :url \"https://api.example.com/users?page=1&limit=10\"
    ;;  :ok? true
    ;;  :headers {\"content-type\" \"application/json\"}
    ;;  :body \"{...}\"}

    All HTTP methods accept either a RequestOptions object or a Clojure map:

    (api/post ctx \"/users\"
      {:headers {\"Content-Type\" \"application/json\"}
       :data    \"{\\\"name\\\": \\\"Alice\\\"}\"
       :timeout 5000})

    (api/put ctx \"/users/1\"
      {:form (api/map->form-data {:name \"Bob\" :email \"bob@example.com\"})})

    (api/delete ctx \"/users/1\")"
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [com.blockether.spel.core :refer [safe]])
  (:import
   [com.microsoft.playwright APIRequest APIRequestContext APIResponse
    Playwright]
   [com.microsoft.playwright.options FormData RequestOptions]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; JSON Encoding
;; =============================================================================

(def ^:dynamic *json-encoder*
  "Function that encodes Clojure data to a JSON string.
   Bind to your preferred JSON library's encode function.

   Must accept any Clojure data (maps, vectors, strings, numbers, etc.)
   and return a String.

   Examples:
   ;; cheshire
   (binding [api/*json-encoder* cheshire.core/generate-string]
     (api/api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; data.json
   (binding [api/*json-encoder* clojure.data.json/write-str]
     (api/api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; jsonista
   (binding [api/*json-encoder* jsonista.core/write-value-as-string]
     (api/api-post ctx \"/users\" {:json {:name \"Alice\"}}))

   ;; Set globally for convenience
   (alter-var-root #'api/*json-encoder* (constantly cheshire.core/generate-string))"
  nil)

;; =============================================================================
;; Hooks
;; =============================================================================

(def ^:dynamic *hooks*
  "Map of hook functions invoked during the API request lifecycle.

   All hooks are optional (nil = no-op). Each hook receives context
   about the current operation and can observe or transform it.

   Keys:

   :on-request   (fn [method url opts]) -> opts
                 Called before every request. Receives the HTTP method keyword
                 (:get, :post, ...), the URL string, and the opts map.
                 Return value replaces opts. Return nil to keep original.
                 Use for: logging, auth token injection, request transformation.

   :on-response  (fn [method url response]) -> response
                 Called after every successful request. Receives the method,
                 URL, and the APIResponse (or response map from `request!`).
                 Return value replaces the response. Return nil to keep original.
                 Use for: logging, metrics, response transformation.

   :on-error     (fn [method url anomaly]) -> anomaly
                 Called when a request returns an anomaly (network error,
                 timeout, etc.). Return value replaces the anomaly.
                 Return nil to keep original.
                 Use for: error logging, alerting, error transformation.

   :on-retry     (fn [{:keys [attempt max-attempts delay-ms result]}])
                 Called before each retry sleep. Side-effect only, return
                 value ignored.
                 Use for: logging retry attempts, metrics.

   Examples:

   ;; Global logging
   (alter-var-root #'api/*hooks*
     (constantly
       {:on-request  (fn [m url _] (println \"→\" m url))
        :on-response (fn [m url r] (println \"←\" m url (.status r)) r)}))

   ;; Per-scope auth injection
   (binding [api/*hooks* {:on-request (fn [_ _ opts]
                                        (update opts :headers
                                          assoc \"Authorization\" (str \"Bearer \" (get-token))))}]
     (api-get ctx \"/protected\"))"
  {:on-request  nil
   :on-response nil
   :on-error    nil
   :on-retry    nil})

(defmacro with-hooks
  "Execute body with the given hooks merged into `*hooks*`.

   Merges with (not replaces) any existing hooks so outer bindings
   are preserved for keys you don't override.

   Usage:
   (with-hooks {:on-request  (fn [m url opts] (log/info m url) opts)
                :on-response (fn [m url resp] (metrics/inc! :api-calls) resp)}
     (api-get ctx \"/users\")
     (api-post ctx \"/users\" {:json {:name \"Alice\"}}))"
  [hooks & body]
  `(binding [*hooks* (merge *hooks* ~hooks)]
     ~@body))

;; =============================================================================
;; FormData
;; =============================================================================

(defn form-data
  "Creates a new FormData instance.
   
   Returns:
   FormData instance."
  ^FormData []
  (FormData/create))

(defn fd-set
  "Sets a field in FormData.
   
   Params:
   `fd`    - FormData instance.
   `name`  - String. Field name.
   `value` - String. Field value.
   
   Returns:
   FormData instance."
  ^FormData [^FormData fd ^String name ^String value]
  (.set fd name value))

(defn fd-append
  "Appends a field to FormData.
   
   Params:
   `fd`    - FormData instance.
   `name`  - String. Field name.
   `value` - String. Field value.
   
   Returns:
   FormData instance."
  ^FormData [^FormData fd ^String name ^String value]
  (.append fd name value))

(defn map->form-data
  "Converts a Clojure map to FormData.
   
   Params:
   `m` - Map of string->string.
   
   Returns:
   FormData instance."
  ^FormData [m]
  (let [fd (form-data)]
    (doseq [[k v] m]
      (fd-set fd (name k) (str v)))
    fd))

;; =============================================================================
;; RequestOptions
;; =============================================================================

(defn request-options
  "Creates RequestOptions from a map.

   Params:
   `opts` - Map with optional:
     :method              - String. HTTP method override.
     :headers             - Map. HTTP headers ({\"Authorization\" \"Bearer ...\"}).
     :data                - String, bytes, or Object. Request body.
                            Objects are auto-serialized to JSON by Playwright.
     :json                - Any Clojure data. Encoded via `*json-encoder*`,
                            sets Content-Type to application/json automatically.
                            Mutually exclusive with :data, :form, :multipart.
     :form                - FormData. URL-encoded form data.
     :multipart           - FormData. Multipart form data (file uploads).
     :timeout             - Double. Timeout in ms (default: 30000).
     :params              - Map. Query parameters.
     :max-redirects       - Long. Max redirects to follow (default: 20).
     :max-retries         - Long. Retry network errors (default: 0).
     :fail-on-status-code - Boolean. Throw on non-2xx/3xx status.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.

   The :json key requires `*json-encoder*` to be bound:

     (binding [api/*json-encoder* cheshire.core/generate-string]
       (api/api-post ctx \"/users\" {:json {:name \"Alice\" :age 30}}))

   Returns:
   RequestOptions instance."
  ^RequestOptions [opts]
  (let [;; Handle :json → encode and merge into :data + Content-Type header
        opts (if-let [json-data (:json opts)]
               (do
                 (when-not *json-encoder*
                   (throw (ex-info (str "Cannot use :json without binding *json-encoder*. "
                                     "Set it to your JSON library's encode function, e.g.:\n"
                                     "  (binding [api/*json-encoder* cheshire.core/generate-string] ...)\n"
                                     "  (alter-var-root #'api/*json-encoder* (constantly cheshire.core/generate-string))")
                            {:key :json :value json-data})))
                 (-> opts
                   (dissoc :json)
                   (assoc :data (*json-encoder* json-data))
                   (update :headers (fn [h]
                                      (merge {"Content-Type" "application/json"} h)))))
               opts)
        ^RequestOptions ro (RequestOptions/create)]
    (when-let [v (:method opts)]
      (.setMethod ro ^String v))
    (when-let [v (:headers opts)]
      (doseq [[k val] v]
        (.setHeader ro (name k) (str val))))
    (when-let [v (:data opts)]
      (cond
        (string? v) (.setData ro ^String v)
        (bytes? v)  (.setData ro ^bytes v)
        :else       (.setData ro ^Object v)))
    (when-let [v (:form opts)]
      (.setForm ro ^FormData v))
    (when-let [v (:multipart opts)]
      (.setMultipart ro ^FormData v))
    (when-let [v (:timeout opts)]
      (.setTimeout ro (double v)))
    (when-let [v (:params opts)]
      (doseq [[k val] v]
        (.setQueryParam ro (name k) (str val))))
    (when-let [v (:max-redirects opts)]
      (.setMaxRedirects ro (long v)))
    (when-let [v (:max-retries opts)]
      (.setMaxRetries ro (long v)))
    (when (contains? opts :fail-on-status-code)
      (.setFailOnStatusCode ro (boolean (:fail-on-status-code opts))))
    (when (contains? opts :ignore-https-errors)
      (.setIgnoreHTTPSErrors ro (boolean (:ignore-https-errors opts))))
    ro))

;; =============================================================================
;; APIRequest
;; =============================================================================

(defn api-request
  "Returns the APIRequest for the Playwright instance.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   APIRequest instance."
  ^APIRequest [^Playwright pw]
  (.request pw))

(defn new-api-context
  "Creates a new APIRequestContext.

   Params:
   `api-req` - APIRequest instance (from `api-request`).
   `opts`    - Map, optional. Context options:
     :base-url            - String. Base URL for all requests.
     :extra-http-headers  - Map. Headers sent with every request.
     :ignore-https-errors - Boolean. Ignore SSL certificate errors.
     :timeout             - Double. Default timeout in ms (default: 30000).
     :user-agent          - String. User-Agent header.
     :max-redirects       - Long. Max redirects to follow (default: 20).
     :fail-on-status-code - Boolean. Throw on non-2xx/3xx status.
     :storage-state       - String. Storage state JSON or path.

   Returns:
   APIRequestContext or anomaly map.

   Examples:
   (new-api-context (api-request pw)
     {:base-url \"https://api.example.com\"
      :extra-http-headers {\"Authorization\" \"Bearer token\"}
      :timeout 10000})"
  (^APIRequestContext [^APIRequest api-req]
   (safe (.newContext api-req)))
  (^APIRequestContext [^APIRequest api-req opts]
   (safe
     (let [co (com.microsoft.playwright.APIRequest$NewContextOptions.)]
       (when-let [v (:base-url opts)]
         (.setBaseURL co ^String v))
       (when-let [v (:extra-http-headers opts)]
         (.setExtraHTTPHeaders co ^java.util.Map v))
       (when (contains? opts :ignore-https-errors)
         (.setIgnoreHTTPSErrors co (boolean (:ignore-https-errors opts))))
       (when-let [v (:timeout opts)]
         (.setTimeout co (double v)))
       (when-let [v (:user-agent opts)]
         (.setUserAgent co ^String v))
       (when-let [v (:max-redirects opts)]
         (.setMaxRedirects co (long v)))
       (when (contains? opts :fail-on-status-code)
         (.setFailOnStatusCode co (boolean (:fail-on-status-code opts))))
       (when-let [v (:storage-state opts)]
         (.setStorageState co ^String v))
       (.newContext api-req co)))))

(defmacro with-api-context
  "Binds a single APIRequestContext and ensures disposal.

   Usage:
   (with-api-context [ctx (new-api-context (api-request pw) {:base-url \"https://api.example.com\"})]
     (api-get ctx \"/users\"))"
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try
       ~@body
       (finally
         (when (instance? APIRequestContext ~sym)
           (api-dispose! ~sym))))))

(defmacro with-api-contexts
  "Binds multiple APIRequestContexts and disposes all on exit.

   Same shape as `with-open` — flat pairs of [name expr].

   Usage:
   (with-api-contexts [users   (new-api-context (api-request pw)
                                 {:base-url \"https://users.example.com\"
                                  :extra-http-headers {\"Authorization\" \"Bearer token\"}})
                       billing (new-api-context (api-request pw)
                                 {:base-url \"https://billing.example.com\"
                                  :extra-http-headers {\"X-API-Key\" \"key\"}})
                       public  (new-api-context (api-request pw)
                                 {:base-url \"https://public.example.com\"})]
     (api-get users \"/me\")
     (api-get billing \"/invoices\")
     (api-get public \"/catalog\"))"
  [bindings & body]
  (assert (vector? bindings) "bindings must be a vector")
  (assert (even? (count bindings)) "bindings must have an even number of forms")
  (let [pairs (partition 2 bindings)
        syms  (mapv first pairs)]
    `(let [~@bindings]
       (try
         ~@body
         (finally
           ~@(for [s (reverse syms)]
               `(when (instance? APIRequestContext ~s)
                  (try (api-dispose! ~s) (catch Exception ~'_)))))))))

;; =============================================================================
;; Internal — Options Coercion & Request Execution
;; =============================================================================

(defn- ->request-options
  "Coerces opts to RequestOptions. If already a RequestOptions instance,
   returns it unchanged. If a map, converts via `request-options`."
  ^RequestOptions [opts]
  (if (instance? RequestOptions opts)
    opts
    (request-options opts)))

(defn- fire-hook
  "Invoke a hook function if present. Returns the hook's return value
   or the original value if the hook returns nil or doesn't exist."
  [hook-key original & args]
  (if-let [hook-fn (get *hooks* hook-key)]
    (let [result (apply hook-fn args)]
      (if (nil? result) original result))
    original))

(defn- execute-request
  "Central request executor. All HTTP methods route through here.
   Handles hook lifecycle: on-request → Playwright call → on-response/on-error."
  [^APIRequestContext ctx method ^String url opts]
  (let [opts   (when opts (fire-hook :on-request opts method url opts))
        ro     (when opts (->request-options opts))
        result (safe
                 (case method
                   :get    (if ro (.get ctx url ro)    (.get ctx url))
                   :post   (if ro (.post ctx url ro)   (.post ctx url))
                   :put    (if ro (.put ctx url ro)    (.put ctx url))
                   :patch  (if ro (.patch ctx url ro)  (.patch ctx url))
                   :delete (if ro (.delete ctx url ro) (.delete ctx url))
                   :head   (if ro (.head ctx url ro)   (.head ctx url))
                   (if ro (.fetch ctx url ro) (.fetch ctx url))))]
    (if (anomaly/anomaly? result)
      (fire-hook :on-error result method url result)
      (fire-hook :on-response result method url result))))

;; =============================================================================
;; APIRequestContext — HTTP Methods
;; =============================================================================

(defn api-get
  "Sends a GET request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path (resolved against base-url if set).
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-get ctx \"/users\")
   (api-get ctx \"/users\" {:params {:page 1 :limit 10}
                            :headers {\"Authorization\" \"Bearer token\"}})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :get url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :get url opts)))

(defn api-post
  "Sends a POST request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-post ctx \"/users\" {:data \"{\\\"name\\\": \\\"Alice\\\"}\"
                              :headers {\"Content-Type\" \"application/json\"}})
   (api-post ctx \"/login\" {:form (map->form-data {:user \"admin\" :pass \"secret\"})})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :post url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :post url opts)))

(defn api-put
  "Sends a PUT request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :put url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :put url opts)))

(defn api-patch
  "Sends a PATCH request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :patch url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :patch url opts)))

(defn api-delete
  "Sends a DELETE request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :delete url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :delete url opts)))

(defn api-head
  "Sends a HEAD request.

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map."
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :head url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :head url opts)))

(defn api-fetch
  "Sends a request with custom method (set via :method in opts).

   Params:
   `ctx`  - APIRequestContext instance.
   `url`  - String. URL path.
   `opts` - RequestOptions or map, optional. See `request-options`.

   Returns:
   APIResponse or anomaly map.

   Examples:
   (api-fetch ctx \"/resource\" {:method \"OPTIONS\"})"
  ([^APIRequestContext ctx ^String url]
   (execute-request ctx :fetch url nil))
  ([^APIRequestContext ctx ^String url opts]
   (execute-request ctx :fetch url opts)))

(defn api-dispose!
  "Disposes the APIRequestContext and all responses.

   Params:
   `ctx` - APIRequestContext instance.

   Returns:
   nil."
  [^APIRequestContext ctx]
  (.dispose ctx)
  nil)

;; =============================================================================
;; APIResponse
;; =============================================================================

(defn api-response-url
  "Returns the response URL.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.url ^APIResponse resp)))

(defn api-response-status
  "Returns the HTTP status code.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.status ^APIResponse resp)))

(defn api-response-status-text
  "Returns the HTTP status text.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.statusText ^APIResponse resp)))

(defn api-response-headers
  "Returns the response headers.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (into {} (.headers ^APIResponse resp))))

(defn api-response-body
  "Returns the response body as bytes.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.body ^APIResponse resp)))

(defn api-response-text
  "Returns the response body as text.
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.text ^APIResponse resp)))

(defn api-response-ok?
  "Returns whether the response is OK (2xx).
   Passes through anomaly maps unchanged."
  [resp]
  (if (anomaly/anomaly? resp) resp (.ok ^APIResponse resp)))

(defn api-response-headers-array
  "Returns the response headers as a vector of {:name :value} maps.
   Preserves duplicate headers (unlike `api-response-headers` which
   keeps only the last value for each name).
   Passes through anomaly maps unchanged.

   Params:
   `resp` - APIResponse instance.

   Returns:
   Vector of maps, or anomaly map."
  [resp]
  (if (anomaly/anomaly? resp)
    resp
    (mapv (fn [^com.microsoft.playwright.options.HttpHeader h]
            {:name (.-name h) :value (.-value h)})
      (.headersArray ^APIResponse resp))))

(defn api-response-dispose!
  "Disposes the APIResponse.
   No-op on anomaly maps.

   Params:
   `resp` - APIResponse instance.

   Returns:
   nil."
  [resp]
  (when-not (anomaly/anomaly? resp)
    (.dispose ^APIResponse resp))
  nil)

;; =============================================================================
;; Response Convenience
;; =============================================================================

(defn api-response->map
  "Converts an APIResponse to a Clojure map.

   Reads the response body as text and includes all metadata.
   Useful for logging, debugging, and test assertions.

   Params:
   `resp` - APIResponse instance.

   Returns:
   Map with keys:
     :status      - Long. HTTP status code.
     :status-text - String. HTTP status text.
     :url         - String. Response URL.
     :ok?         - Boolean. True if status is 2xx.
     :headers     - Map. Response headers.
     :body        - String. Response body text (nil on read failure).

   Examples:
   (let [resp (api-get ctx \"/users\")]
     (api-response->map resp))
   ;; => {:status 200
   ;;     :status-text \"OK\"
   ;;     :url \"https://api.example.com/users\"
   ;;     :ok? true
   ;;     :headers {\"content-type\" \"application/json\"}
    ;;     :body \"{\\\"users\\\": [...]}\"}"
  [resp]
  (if (anomaly/anomaly? resp)
    resp
    {:status      (long (.status ^APIResponse resp))
     :status-text (.statusText ^APIResponse resp)
     :url         (.url ^APIResponse resp)
     :ok?         (.ok ^APIResponse resp)
     :headers     (into {} (.headers ^APIResponse resp))
     :body        (try (.text ^APIResponse resp) (catch Exception _ nil))}))

;; =============================================================================
;; Standalone Request — no pre-built context needed
;; =============================================================================

(defn request!
  "Fire-and-forget HTTP request. Creates an ephemeral context, makes the
   request, reads the full response into a Clojure map, and disposes
   everything. No context management needed.

   Params:
   `pw`     - Playwright instance.
   `method` - Keyword. :get :post :put :patch :delete :head.
   `url`    - String. Full URL (not relative — no base-url).
   `opts`   - Map, optional. See `request-options` for all keys.

   Returns:
   Response map (same shape as `api-response->map`) or anomaly map.

   Examples:
   ;; Simple GET — no setup, no cleanup
   (request! pw :get \"https://api.example.com/health\")
   ;; => {:status 200 :ok? true :body \"OK\" ...}

   ;; POST with JSON body
   (request! pw :post \"https://api.example.com/users\"
     {:data    \"{\\\"name\\\": \\\"Alice\\\"}\"
      :headers {\"Content-Type\" \"application/json\"
                \"Authorization\" \"Bearer token\"}})

   ;; Hit multiple domains without any context setup
   (let [users    (request! pw :get \"https://users.example.com/me\"
                    {:headers {\"Authorization\" \"Bearer user-token\"}})
         invoices (request! pw :get \"https://billing.example.com/invoices\"
                    {:headers {\"X-API-Key\" \"billing-key\"}})]
     [users invoices])"
  ([^Playwright pw method ^String url]
   (request! pw method url {}))
  ([^Playwright pw method ^String url opts]
   (let [ctx (new-api-context (api-request pw))]
     (try
       (let [result (execute-request ctx method url opts)]
         (if (anomaly/anomaly? result)
           result
           (api-response->map result)))
       (finally
         (try (api-dispose! ctx)
           (catch Exception e
             (binding [*out* *err*]
               (println (str "spel: warn: api-dispose failed: " (.getMessage e)))))))))))

;; =============================================================================
;; Retry
;; =============================================================================

(def default-retry-opts
  "Default retry configuration.

   :max-attempts  - Total attempts including the first (default: 3).
   :delay-ms      - Initial delay between retries in ms (default: 200).
   :backoff       - Backoff strategy (default: :exponential).
                    :fixed        — constant delay.
                    :linear       — delay * attempt.
                    :exponential  — delay * 2^attempt.
   :max-delay-ms  - Ceiling on delay (default: 10000).
   :retry-when    - Predicate (fn [result] -> truthy to retry).
                    Default: retries on anomalies and 5xx status codes."
  {:max-attempts 3
   :delay-ms     200
   :backoff      :exponential
   :max-delay-ms 10000
   :retry-when   (fn [result]
                   (or (anomaly/anomaly? result)
                     (and (map? result)
                       (contains? result :status)
                       (>= (:status result) 500))))})

(defn- compute-delay
  "Compute delay in ms for the given attempt number (0-based)."
  ^long [backoff delay-ms max-delay-ms attempt]
  (let [raw (case backoff
              :fixed       delay-ms
              :linear      (* delay-ms (inc attempt))
              :exponential (* delay-ms (long (Math/pow 2 attempt)))
              (* delay-ms (long (Math/pow 2 attempt))))]
    (min raw max-delay-ms)))

(defn retry
  "Execute `f` (a no-arg function) with retry logic.

   Params:
   `f`    - No-arg function that returns a result.
   `opts` - Map, optional. Override keys from `default-retry-opts`:
     :max-attempts  - Total attempts (default: 3).
     :delay-ms      - Initial delay in ms (default: 200).
     :backoff       - :fixed, :linear, or :exponential (default).
     :max-delay-ms  - Max delay ceiling in ms (default: 10000).
     :retry-when    - (fn [result]) → truthy to retry.

   Returns:
   The result of the last attempt.

   Examples:
   ;; Retry a flaky endpoint
   (retry #(api-get ctx \"/flaky\"))

   ;; Custom: retry on 429 Too Many Requests with linear backoff
   (retry #(api-get ctx \"/rate-limited\")
     {:max-attempts 5
      :delay-ms     1000
      :backoff      :linear
      :retry-when   (fn [r] (= 429 (:status (api-response->map r))))})"
  ([f] (retry f {}))
  ([f opts]
   (let [{:keys [max-attempts delay-ms backoff max-delay-ms retry-when]}
         (merge default-retry-opts opts)]
     (loop [attempt 0]
       (let [result (f)]
         (if (and (< (inc attempt) max-attempts)
               (retry-when result))
           (let [sleep-ms (compute-delay backoff delay-ms max-delay-ms attempt)]
             (fire-hook :on-retry nil
               {:attempt      (inc attempt)
                :max-attempts max-attempts
                :delay-ms     sleep-ms
                :result       result})
             (Thread/sleep sleep-ms)
             (recur (inc attempt)))
           result))))))

(defmacro with-retry
  "Execute body with retry logic.

   Usage:
   ;; Default: 3 attempts, exponential backoff, retry on anomalies + 5xx
   (with-retry
     (api-get ctx \"/flaky-endpoint\"))

   ;; Custom retry config
   (with-retry {:max-attempts 5
                :delay-ms     500
                :backoff      :linear
                :retry-when   (fn [r] (and (map? r) (>= (:status r) 500)))}
     (api-post ctx \"/idempotent-endpoint\"
       {:json {:action \"process\"}}))

   ;; Retry standalone requests too
   (with-retry {:max-attempts 3}
     (request! pw :get \"https://api.example.com/health\"))"
  [opts-or-body & body]
  (if (and (map? opts-or-body) (seq body))
    `(retry (fn [] ~@body) ~opts-or-body)
    `(retry (fn [] ~opts-or-body ~@body))))

