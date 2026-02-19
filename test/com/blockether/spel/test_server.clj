(ns com.blockether.spel.test-server
  "Local HTTP test server for integration tests.

   Provides HTML pages, echo/health/status endpoints, and request logging.
   NOT part of the public API — only used by our own test suite."
  (:require
   [com.blockether.spel.allure :refer [around]])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]))

(def ^{:dynamic true :doc "Base URL of the running test HTTP server."}
  *test-server-url* nil)

(def ^:private request-log (atom []))

(defn test-server-requests [] @request-log)

(defn- read-request-body ^String [^HttpExchange exchange]
  (let [is (.getRequestBody exchange)]
    (try (slurp is) (finally (.close is)))))

(defn- send-response [^HttpExchange exchange status ^String body content-type]
  (let [headers (.getResponseHeaders exchange)
        body-bytes (.getBytes body "UTF-8")]
    (.set headers "Content-Type" content-type)
    (.sendResponseHeaders exchange status (alength body-bytes))
    (let [os (.getResponseBody exchange)]
      (.write os body-bytes)
      (.close os))))

(def ^:private test-page-html
  "<!DOCTYPE html>
<html><head><title>Test Page</title></head>
<body>
  <h1 id=\"heading\">Test Heading</h1>
  <p id=\"description\">Test description paragraph.</p>
  <div id=\"content\">
    <a id=\"link\" href=\"/second-page\">Go to Second Page</a>
    <form id=\"test-form\">
      <label for=\"text-input\">Name</label>
      <input type=\"text\" id=\"text-input\" placeholder=\"Enter text\" aria-label=\"Name\" />
      <input type=\"text\" id=\"prefilled\" value=\"initial value\" />
      <input type=\"password\" id=\"password-input\" placeholder=\"Password\" />
      <input type=\"checkbox\" id=\"checkbox\" />
      <input type=\"checkbox\" id=\"checked-box\" checked />
      <select id=\"dropdown\">
        <option value=\"a\">Option A</option>
        <option value=\"b\">Option B</option>
        <option value=\"c\">Option C</option>
      </select>
      <textarea id=\"textarea\"></textarea>
      <input type=\"file\" id=\"file-input\" />
      <button id=\"submit-btn\" type=\"button\" data-testid=\"submit\">Submit</button>
    </form>
    <button id=\"hidden-btn\" style=\"display:none\">Hidden</button>
    <button id=\"disabled-btn\" disabled>Disabled</button>
    <div id=\"hover-target\" title=\"Hover tooltip\">Hover Me</div>
    <div id=\"scroll-anchor\" style=\"margin-top:2000px\">Scroll Target</div>
    <img id=\"logo\" alt=\"Test Logo\" src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7\" />
  </div>
  <script>
    window.testReady = true;
    console.log('test-page-loaded');
  </script>
</body></html>")

(def ^:private second-page-html
  "<!DOCTYPE html>
<html><head><title>Second Page</title></head>
<body>
  <h1 id=\"heading\">Second Page</h1>
  <a id=\"back-link\" href=\"/test-page\">Back to Test Page</a>
</body></html>")

(def ^:private iframe-page-html
  "<!DOCTYPE html>
<html><head><title>IFrame Page</title></head>
<body>
  <h1>IFrame Container</h1>
  <iframe id=\"test-iframe\" name=\"test-frame\" src=\"/test-page\" width=\"800\" height=\"600\"></iframe>
</body></html>")

(defn- make-handler ^HttpHandler []
  (reify HttpHandler
    (handle [_ exchange]
      (let [method  (.toUpperCase (str (.getRequestMethod exchange)))
            uri     (.getRequestURI exchange)
            path    (.getPath uri)
            query   (.getQuery uri)
            headers (into {} (map (fn [[k vs]] [(str k) (first vs)]))
                      (.getRequestHeaders exchange))
            body    (read-request-body exchange)]
        (swap! request-log conj {:method method :path path :query query
                                 :headers headers :body body})
        (cond
          (and (= "GET" method) (= "/test-page" path))
          (send-response exchange 200 test-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/second-page" path))
          (send-response exchange 200 second-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/iframe-page" path))
          (send-response exchange 200 iframe-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/health" path))
          (send-response exchange 200 "{\"status\":\"ok\"}" "application/json")

          (and (= "HEAD" method) (= "/health" path))
          (do (.sendResponseHeaders exchange 200 -1)
              (.close (.getResponseBody exchange)))

          (= "/echo" path)
          (let [resp-body (str "{\"method\":\"" method "\""
                            ",\"path\":\"" path "\""
                            (when query (str ",\"query\":\"" query "\""))
                            (when (seq body) (str ",\"body\":" body))
                            "}")]
            (send-response exchange 200 resp-body "application/json"))

          (.startsWith path "/status/")
          (let [code (Integer/parseInt (subs path 8))]
            (send-response exchange code
              (str "{\"status\":" code "}")
              "application/json"))

          (= "/slow" path)
          (do (Thread/sleep 2000)
              (send-response exchange 200 "{\"slow\":true}" "application/json"))

          :else
          (send-response exchange 404
            (str "{\"error\":\"not found\",\"path\":\"" path "\"}")
            "application/json"))))))

(defn start-test-server ^HttpServer []
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)]
    (.createContext server "/" (make-handler))
    (.setExecutor server nil)
    (.start server)
    server))

(defn stop-test-server [^HttpServer server]
  (when server (.stop server 0)))

(defn server-port ^long [^HttpServer server]
  (.getPort (.getAddress server)))

(def with-test-server
  (around [f]
    (let [server (start-test-server)
          port   (server-port server)
          url    (str "http://localhost:" port)]
      (reset! request-log [])
      (try
        (binding [*test-server-url* url]
          (f))
        (finally
          (stop-test-server server))))))
