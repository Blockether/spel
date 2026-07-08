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
   [clojure.java.io :as io]
   [clojure.string :as str])
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

(def ^:private sw-engine-resource
  "Classpath location of the embedded service worker (spel-sw.js), which does
   same-origin capture of passive subresources the in-page wrappers miss."
  "com/blockether/spel/browser/spel-sw.js")

(defn sw-source
  "Returns the embedded `spel-sw.js` source as a string, or throws if it is
   missing from the classpath / native image."
  ^String []
  (if-let [r (io/resource sw-engine-resource)]
    (slurp r)
    (throw (ex-info (str "embedded service worker resource not found: " sw-engine-resource)
             {:resource sw-engine-resource}))))

(defn eject-origin
  "Resolves the (origin, path) an ejected loader/bookmarklet should target.
   With an explicit `url` (e.g. http://host:port/spel) it is split into an
   origin (`scheme://authority`) and a path; otherwise the serving
   `host`/`port`/`path` defaults are used. Returns `[origin path]`."
  [url ^String host port ^String path]
  (if (and url (seq url))
    (let [u (java.net.URL. ^String url)
          p (.getPath u)]
      [(str (.getProtocol u) "://" (.getAuthority u))
       (if (str/blank? p) path p)])
    [(str "http://" host ":" port) path]))

(defn loader-script
  "A tiny, minified in-page loader (plain JS, no `javascript:` prefix) that
   injects the embedded engine from `<origin>/spel.js` and subscribes it to the
   bridge at `<origin><path>`. Idempotent: if `window.__spel` is already
   installed it just (re)connects.

   Local Network Access aware: modern Chromium (Edge 143+/Chrome 142+) gates a
   public origin reaching `127.0.0.1` behind a per-origin permission, and a bare
   `<script src>` no-cors subresource to loopback is DENIED silently instead of
   prompting. So the loader fetches the engine with `targetAddressSpace:'loopback'`
   — the sanctioned call that actually raises the grantable prompt (and is exempt
   from mixed-content once allowed) — then inline-injects the text. It falls back
   to a `<script src>` tag on older browsers where either path works.

   Returned WITHOUT a prefix so it serves two uses — pasted into the DevTools
   Console (or saved as a Sources Snippet), or prefixed with `javascript:` to
   form a draggable bookmarklet."
  [^String origin ^String path token]
  (let [o (json/write-json-str origin)
        c (json/write-json-str (str origin path))
        t (if (and token (seq token)) (json/write-json-str token) "null")]
    (str "(function(){"
      "var o=" o ",c=" c ",t=" t ";"
      "function go(){try{window.__spel&&window.__spel.connect({url:c,token:t});}catch(e){console.error('spel:',e);}}"
      "if(window.__spel){go();return;}"
      "function inject(code){var s=document.createElement('script');s.textContent=code;(document.head||document.documentElement).appendChild(s);go();}"
      "function tag(){var s=document.createElement('script');s.src=o+'/spel.js';s.onload=go;s.onerror=fail;(document.head||document.documentElement).appendChild(s);}"
      "function fail(){console.error('spel: could not load '+o+'/spel.js (is `spel bridge` running, and did you Allow local network access? see edge://settings/content/localNetworkAccess)');}"
      "try{fetch(o+'/spel.js',{mode:'cors',targetAddressSpace:'loopback'}).then(function(r){if(!r.ok)throw 0;return r.text();}).then(inject).catch(tag);}catch(e){tag();}"
      "})();")))

(defn bookmarklet
  "The loader as a ready `javascript:` bookmarklet URL — drag it to the bookmarks
   bar (or Edge favorites); clicking it on any page injects + connects the engine.
   Note: a page's Content-Security-Policy or a managed browser policy can still
   block inline/bookmarklet execution — see `spel bridge --help`."
  [^String origin ^String path token]
  (str "javascript:" (loader-script origin path token)))

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

(defn- gen-token
  "A short random shared secret gating browser<->bridge traffic on loopback."
  ^String []
  (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 16))

(defn runtime-path
  "Filesystem location of the RUNNING bridge's connection details (port + token),
   written while `spel bridge` is up so a same-box `spel bridge use` can pick up
   the live token with zero friction. Cleared on shutdown."
  ^String []
  (str (System/getProperty "user.home") "/.spel/bridge-runtime.json"))

(defn write-runtime!
  "Records the live bridge route (url/port/path/token) for local discovery."
  [m]
  (let [f (io/file (runtime-path))]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (json/write-json-str m))
    m))

(defn read-runtime
  "Reads the running bridge's route, or nil when no bridge is up."
  []
  (let [f (io/file (runtime-path))]
    (when (.isFile f)
      (try (json/read-json (slurp f) :key-fn keyword)
        (catch Exception _ nil)))))

(defn clear-runtime!
  "Removes the runtime discovery file (bridge shutting down)."
  []
  (let [f (io/file (runtime-path))]
    (boolean (and (.isFile f) (.delete f)))))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn route-command!
  "Submits ONE spel command map to a running bridge's /command endpoint and
   returns the browser's result adapted to the daemon `{:success :data :error}`
   shape, so CLI output is identical whether a command hit the daemon or the
   bridge. `url` is the bridge connect URL (e.g. http://127.0.0.1:8787/spel)."
  ([url command] (route-command! url command 30000 nil))
  ([url command timeout-ms] (route-command! url command timeout-ms nil))
  ([^String url command timeout-ms ^String token]
   (let [endpoint (str url "/command"
                    (when (and token (seq token))
                      (str "?t=" (java.net.URLEncoder/encode token "UTF-8"))))
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
         (when (and token (seq token))
           (.setRequestProperty conn "X-Spel-Token" token))
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
    (.set h "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
    ;; Older Private Network Access (pre-LNA Chromium): a public origin reaching
    ;; loopback wants this on the preflight. Harmless on newer LNA browsers,
    ;; which gate loopback by user permission instead (see loader-script).
    (.set h "Access-Control-Allow-Private-Network" "true"))
  (let [bytes (->bytes body)]
    (.sendResponseHeaders ex status (long (alength bytes)))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- cors-preflight? [^HttpExchange ex]
  (= "OPTIONS" (.getRequestMethod ex)))

(defn- query-param
  "Reads a query-string parameter from the request URI, URL-decoded."
  [^HttpExchange ex ^String k]
  (when-let [q (.getRawQuery (.getRequestURI ex))]
    (some (fn [^String pair]
            (let [i (.indexOf pair "=")]
              (when (and (pos? i) (= k (subs pair 0 i)))
                (java.net.URLDecoder/decode (subs pair (inc i)) "UTF-8"))))
      (str/split q #"&"))))

(defn- authorized?
  "True when the bridge has no token (auth disabled) or the request carries the
   matching token — either as `?t=` (SSE can only pass it in the URL) or an
   `X-Spel-Token` header. This is what stops another page open in the same
   browser from driving the tab / reading captured traffic over loopback."
  [^String token ^HttpExchange ex]
  (or (str/blank? token)
    (= token (query-param ex "t"))
    (= token (.getFirst (.getRequestHeaders ex) "X-Spel-Token"))))

(defn- forbidden! [^HttpExchange ex]
  (respond! ex 403 "application/json"
    "{\"ok\":false,\"error\":\"spel bridge: unauthorized (bad or missing token)\"}"))

(defn- harness-html
  "A minimal self-connecting page, handy for smoke-testing a bridge in a real
   browser: it loads the engine and immediately subscribes to this server."
  [^String path token]
  (str "<!doctype html>\n"
    "<html><head><meta charset=\"utf-8\"><title>spel bridge</title></head>\n"
    "<body>\n"
    "<h1>spel bridge</h1>\n"
    "<p>Engine loaded. This tab is subscribed to the local spel server.</p>\n"
    "<script src=\"/spel.js\"></script>\n"
    "<script>\n"
    "  window.__spel.connect({ url: window.location.origin + " (json/write-json-str path)
    (if (and token (seq token)) (str ", token: " (json/write-json-str token)) "")
    " });\n"
    "</script>\n"
    "</body></html>\n"))

(defn- sse-handler
  "GET → open a Server-Sent-Events stream and register the tab as a client.
   Blocks its worker thread for the life of the connection, draining the
   client's queue (heartbeats keep proxies/`EventSource` from timing out)."
  [clients token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
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
  [pending token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
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
  [send! token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
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
  [& {:keys [host port path token] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [clients   (ConcurrentHashMap.)
        pending   (ConcurrentHashMap.)
        ^HttpServer server (try (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
                             (catch java.io.IOException _
                      ;; Requested port busy — fall back to an ephemeral one.
                               (HttpServer/create (InetSocketAddress. ^String host (int 0)) 0)))
        ^ScheduledExecutorService scheduler (Executors/newSingleThreadScheduledExecutor)
        result-path (str path "/result")]
    (.setExecutor server (Executors/newCachedThreadPool))
    (.createContext server result-path (result-handler pending token))
    (.createContext server path (sse-handler clients token))
    (.createContext server "/spel.js" (static-handler "application/javascript; charset=utf-8" (engine-source)))
    (.createContext server "/spel-sw.js" (static-handler "application/javascript; charset=utf-8" (sw-source)))
    (.createContext server "/" (static-handler "text/html; charset=utf-8" (harness-html path token)))
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
      (.createContext server (str path "/command") (command-handler send! token))
      {:server  server
       :host    host
       :port    actual-port
       :path    path
       :token   token
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
  [& {:keys [host port path token] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [token       (or token (gen-token))
        {:keys [url page stop] :as bridge} (create-bridge :host host :port port :path path :token token)
        actual-port (:port bridge)
        connect-url (str url "?t=" token)]
    (println "spel bridge — loopback bridge running")
    (when (not= actual-port port)
      (println (str "  (port " port " was busy — using " actual-port ")")))
    (println (str "  engine:  http://" host ":" actual-port "/spel.js"))
    (println (str "  connect: " connect-url))
    (println (str "  harness: " page))
    (println (str "  token:   " token))
    (println)
    (println "Embed in a page (same box, sidesteps CDP lockdown):")
    (println (str "  <script src=\"http://" host ":" actual-port "/spel.js\"></script>"))
    (println (str "  <script>window.__spel.connect({url:\"" url "\",token:\"" token "\"})</script>"))
    (println)
    (println "Route regular commands from this box: spel bridge use  (picks up the token automatically)")
    (println "Press Ctrl-C to stop.")
    (flush)
    (write-runtime! {:url url :port actual-port :path path :token token})
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (clear-runtime!) (stop))))
    (try
      @(promise) ; block forever
      (catch InterruptedException _ (clear-runtime!) (stop)))
    bridge))
