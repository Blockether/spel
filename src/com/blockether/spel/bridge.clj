(ns com.blockether.spel.bridge
  "Loopback HTTP bridge for `spel bridge` — the transport that lets an
   in-page `spel.js` engine subscribe to a spel server and exchange commands
   two ways.

   Why this exists: in locked-down corporate environments the Chrome DevTools
   Protocol (CDP) is disabled, so classic Playwright/CDP automation is dead.
   But loopback traffic (`127.0.0.1`) never leaves the machine, so a page that
   `<script src>`-embeds `spel.js` can talk to a local spel server the proxy
   never sees. This bridge is that server side.

   Transport: Server-Sent Events (inbound commands, server → browser) plus a
   JSON POST endpoint (outbound results, browser → server). SSE + POST is used
   rather than WebSocket because it is the JDK-native option (no extra deps,
   `com.sun.net.httpserver`) and behaves predictably on loopback. `spel.js`
   also speaks WebSocket first, but the bundled server implements the SSE
   fallback path.

   Endpoints (all under the configurable `:path`, default `/spel`):
     GET  /spel          → SSE stream; each browser tab that connects is a client
     POST /spel/result   → browser posts an invoke result here (correlated by id)
     POST /spel/command  → an external client (the spel CLI) submits one command;
                           it is pushed to the connected tab and the browser's
                           result is returned as the HTTP response (this is what
                           lets regular `spel <verb>` route through the bridge)
     GET  /spel.js       → the embedded engine source (same file that ships in
                           the native image)
     GET  /              → a tiny harness page that loads the engine and connects"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io])
  (:import
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]
   [java.util UUID]
   [java.util.concurrent ConcurrentHashMap Executors LinkedBlockingQueue ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private engine-resource
  "Classpath location of the embedded engine (also listed in the native-image
   resource-config so it survives into the standalone binary)."
  "com/blockether/spel/browser/spel.js")

(defn engine-source
  "Returns the embedded `spel.js` source as a string, or throws if the resource
   is missing from the classpath / native image."
  ^String []
  (if-let [r (io/resource engine-resource)]
    (slurp r)
    (throw (ex-info (str "embedded engine resource not found: " engine-resource)
             {:resource engine-resource}))))

;; =============================================================================
;; Target profile — the persisted route for regular `spel <verb>` commands
;; =============================================================================
;;
;; When a target is saved (via `spel bridge use`), the CLI sends every browser
;; command to the bridge's /command endpoint instead of the Playwright daemon,
;; so a locked-down / CDP-disabled box can still drive a real tab through the
;; embedded engine. "Where we send / how we communicate" lives in one small
;; JSON file at ~/.spel/bridge.json.

(defn target-path
  "Filesystem location of the saved bridge target (the route regular spel
   commands follow when set)."
  ^String []
  (str (System/getProperty "user.home") "/.spel/bridge.json"))

(defn load-target
  "Reads the persisted bridge target, or nil when none is set. Shape:
   `{:url \"http://127.0.0.1:8787/spel\"}`."
  ([] (load-target (target-path)))
  ([^String path]
   (let [f (io/file path)]
     (when (.isFile f)
       (try (json/read-json (slurp f) :key-fn keyword)
         (catch Exception _ nil))))))

(defn save-target!
  "Persists the active bridge target so subsequent `spel <verb>` invocations
   route through the bridge. Returns the saved map."
  ([target] (save-target! target (target-path)))
  ([target ^String path]
   (let [f (io/file path)]
     (when-let [parent (.getParentFile f)] (.mkdirs parent))
     (spit f (json/write-json-str target))
     target)))

(defn clear-target!
  "Removes the persisted bridge target; regular commands go back to the daemon.
   Returns true if a target file was removed."
  ([] (clear-target! (target-path)))
  ([^String path]
   (let [f (io/file path)]
     (boolean (and (.isFile f) (.delete f))))))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn route-command!
  "Submits ONE spel command map to a running bridge's /command endpoint and
   returns the browser's result adapted to the daemon `{:success :data :error}`
   shape, so CLI output is identical whether a command hit the daemon or the
   bridge. `url` is the bridge connect URL (e.g. http://127.0.0.1:8787/spel)."
  ([url command] (route-command! url command 30000))
  ([^String url command timeout-ms]
   (let [endpoint (str url "/command")
         body     (->bytes (json/write-json-str
                             (into {} (map (fn [[k v]] [(name k) v]) command))))]
     (try
       (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. endpoint))]
         (doto conn
           (.setRequestMethod "POST")
           (.setConnectTimeout 3000)
           (.setReadTimeout (int (or timeout-ms 30000)))
           (.setDoOutput true)
           (.setRequestProperty "Content-Type" "application/json"))
         (with-open [os (.getOutputStream conn)]
           (.write os body))
         (let [code (.getResponseCode conn)
               ins  (if (and (>= code 200) (< code 400))
                      (.getInputStream conn)
                      (.getErrorStream conn))
               resp (when ins (slurp ins))
               r    (try (json/read-json resp) (catch Exception _ nil))]
           (if (map? r)
             {:success (boolean (get r "ok"))
              :data    (get r "value")
              :error   (get r "error")}
             {:success false
              :error   (str "bridge: unexpected response (HTTP " code ")")})))
       (catch java.net.ConnectException _
         {:success false
          :error   (str "bridge not reachable at " url
                     " — is `spel bridge` running? (start it, or run `spel bridge off`)")})
       (catch Exception e
         {:success false :error (str "bridge error: " (.getMessage e))})))))

(defn- respond!
  "Writes a one-shot response with the given status, content-type and body."
  [^HttpExchange ex ^long status ^String content-type ^String body]
  (let [h (.getResponseHeaders ex)]
    (.set h "Content-Type" content-type)
    (.set h "Access-Control-Allow-Origin" "*")
    (.set h "Access-Control-Allow-Headers" "Content-Type")
    (.set h "Access-Control-Allow-Methods" "GET, POST, OPTIONS"))
  (let [bytes (->bytes body)]
    (.sendResponseHeaders ex status (long (alength bytes)))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- cors-preflight? [^HttpExchange ex]
  (= "OPTIONS" (.getRequestMethod ex)))

(defn- harness-html
  "A minimal self-connecting page, handy for smoke-testing a bridge in a real
   browser: it loads the engine and immediately subscribes to this server."
  [^String path]
  (str "<!doctype html>\n"
    "<html><head><meta charset=\"utf-8\"><title>spel bridge</title></head>\n"
    "<body>\n"
    "<h1>spel bridge</h1>\n"
    "<p>Engine loaded. This tab is subscribed to the local spel server.</p>\n"
    "<script src=\"/spel.js\"></script>\n"
    "<script>\n"
    "  window.__spel.connect({ url: window.location.origin + " (json/write-json-str path) " });\n"
    "</script>\n"
    "</body></html>\n"))

(defn- sse-handler
  "GET → open a Server-Sent-Events stream and register the tab as a client.
   Blocks its worker thread for the life of the connection, draining the
   client's queue (heartbeats keep proxies/`EventSource` from timing out)."
  [clients]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not= "GET" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [h (.getResponseHeaders ex)]
            (.set h "Content-Type" "text/event-stream")
            (.set h "Cache-Control" "no-cache")
            (.set h "Connection" "keep-alive")
            (.set h "Access-Control-Allow-Origin" "*")
            (.sendResponseHeaders ex 200 0)
            (let [os (.getResponseBody ex)
                  id (str (UUID/randomUUID))
                  ^LinkedBlockingQueue q (LinkedBlockingQueue.)]
              (.put ^ConcurrentHashMap clients id q)
              (try
                (.write os (->bytes ": connected\n\n"))
                (.flush os)
                (loop []
                  (let [msg (.poll q 20 TimeUnit/SECONDS)]
                    (cond
                      (nil? msg) (do (.write os (->bytes ": ping\n\n")) (.flush os) (recur))
                      (identical? msg ::close) nil
                      :else (do (.write os (->bytes (str "data: " msg "\n\n")))
                              (.flush os)
                              (recur)))))
                (catch Exception _ nil)
                (finally
                  (.remove ^ConcurrentHashMap clients id)
                  (try (.close os) (catch Exception _ nil)))))))
        (catch Exception _ (try (.close ex) (catch Exception _ nil)))))))

(defn- result-handler
  "POST ← a browser posts one invoke result `{id, action, ok, value|error}`.
   Correlates it to the waiting promise and delivers."
  [pending]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not= "POST" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [body (slurp (io/reader (.getRequestBody ex)))
                msg  (try (json/read-json body) (catch Exception _ nil))
                id   (get msg "id")]
            (when id
              (when-let [p (.remove ^ConcurrentHashMap pending id)]
                (deliver p msg)))
            (respond! ex 200 "application/json" "{\"ok\":true}")))
        (catch Exception _ (respond! ex 500 "text/plain" "error"))))))

(defn- static-handler [content-type ^String body]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if (cors-preflight? ex)
          (respond! ex 204 "text/plain" "")
          (respond! ex 200 content-type body))
        (catch Exception _ (try (.close ex) (catch Exception _ nil)))))))

(defn- command-handler
  "POST ← an external client (the spel CLI) submits one command map. It is
   pushed to the connected tab(s) via `send!` and the browser's result is
   returned as the JSON response. With no tab subscribed `send!` times out and
   a structured `{ok:false,error}` is returned so the caller gets a clear error."
  [send!]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not= "POST" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [body   (slurp (io/reader (.getRequestBody ex)))
                cmd    (json/read-json body)
                result (try (send! cmd 30000)
                         (catch Exception e
                           {"ok" false "error" (or (.getMessage e) "bridge command failed")}))]
            (respond! ex 200 "application/json" (json/write-json-str result))))
        (catch Exception e
          (respond! ex 500 "text/plain" (str "error: " (.getMessage e))))))))

(defn create-bridge
  "Creates and starts a loopback bridge. Returns a map:
     :url    the SSE/connect URL to hand to `spel.js` `connect({url})`
     :page   the harness page URL (open it in a browser to auto-connect)
     :host :port :path
     :clients   count of currently-subscribed tabs (deref-able fn)
     :send      (fn [command] promise) — pushes a command to every connected
                tab; the promise is delivered with the first result posted back
     :send!     (fn [command] result | (fn [command timeout-ms] result)) —
                blocking convenience wrapper around :send
     :stop      (fn []) — stops the server and releases the port

   opts: :host (default \"127.0.0.1\") :port (default 8787) :path (default \"/spel\").
   Binds to loopback only by design — this never listens on a routable address."
  [& {:keys [host port path] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [clients   (ConcurrentHashMap.)
        pending   (ConcurrentHashMap.)
        server    (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
        ^ScheduledExecutorService scheduler (Executors/newSingleThreadScheduledExecutor)
        result-path (str path "/result")]
    (.setExecutor server (Executors/newCachedThreadPool))
    (.createContext server result-path (result-handler pending))
    (.createContext server path (sse-handler clients))
    (.createContext server "/spel.js" (static-handler "application/javascript; charset=utf-8" (engine-source)))
    (.createContext server "/" (static-handler "text/html; charset=utf-8" (harness-html path)))
    (.start server)
    (let [actual-port (.getPort (.getAddress server))
          base-url    (str "http://" host ":" actual-port path)
          send (fn send-fn [command]
                 (let [id      (str (UUID/randomUUID))
                       p       (promise)
                       payload (assoc (into {} (map (fn [[k v]] [(name k) v]) command))
                                 "id" id)
                       js      (json/write-json-str payload)]
                   (.put ^ConcurrentHashMap pending id p)
                   ;; Bound the pending map: if no browser ever posts a result
                   ;; (no tab subscribed, tab closed, command died mid-flight)
                   ;; drop the orphaned promise rather than leak it for the life
                   ;; of the process. A delivered result is removed earlier by
                   ;; result-handler; this scheduled removal then no-ops.
                   (.schedule scheduler
                     ^Runnable (fn [] (.remove ^ConcurrentHashMap pending id))
                     (long 60) TimeUnit/SECONDS)
                   (doseq [q (vals clients)]
                     (.offer ^LinkedBlockingQueue q js))
                   p))
          send! (fn send-sync
                  ([command] (send-sync command 10000))
                  ([command timeout-ms]
                   (let [r (deref (send command) timeout-ms ::timeout)]
                     (if (= r ::timeout)
                       (throw (ex-info "spel bridge: timed out waiting for browser result"
                                {:command command :timeout-ms timeout-ms}))
                       r))))]
      (.createContext server (str path "/command") (command-handler send!))
      {:server  server
       :host    host
       :port    actual-port
       :path    path
       :url     base-url
       :page    (str "http://" host ":" actual-port "/")
       :clients (fn [] (.size clients))
       :send    send
       :send!   send!
       :stop    (fn []
                  ;; Wake every blocked SSE loop so it unwinds cleanly, then
                  ;; release the cleanup thread and the port.
                  (doseq [q (vals clients)]
                    (.offer ^LinkedBlockingQueue q ::close))
                  (.shutdownNow scheduler)
                  (.stop server 0))})))

(defn serve!
  "Starts a bridge and blocks the current thread, printing connection details.
   Used by the `spel bridge` CLI command. Returns never (until interrupted)."
  [& {:keys [host port path] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [{:keys [url page stop] :as bridge} (create-bridge :host host :port port :path path)]
    (println "spel bridge — loopback bridge running")
    (println (str "  engine:  http://" host ":" (:port bridge) "/spel.js"))
    (println (str "  connect: " url))
    (println (str "  harness: " page))
    (println)
    (println "Embed in a page (same box, sidesteps CDP lockdown):")
    (println "  <script src=\"http://127.0.0.1:8787/spel.js\"></script>")
    (println (str "  <script>window.__spel.connect({url:\"" url "\"})</script>"))
    (println)
    (println "Press Ctrl-C to stop.")
    (flush)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop))))
    (try
      @(promise) ; block forever
      (catch InterruptedException _ (stop)))
    bridge))
