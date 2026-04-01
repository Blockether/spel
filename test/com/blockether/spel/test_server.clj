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
    <ul role=\"listbox\"><li role=\"option\" id=\"aria-disabled-opt\" aria-disabled=\"true\">Disabled Option</li><li role=\"option\" id=\"aria-enabled-opt\">Enabled Option</li></ul>
    <div id=\"hover-target\" title=\"Hover tooltip\">Hover Me</div>
    <div id=\"scroll-anchor\" style=\"margin-top:2000px\">Scroll Target</div>
    <img id=\"logo\" alt=\"Test Logo\" src=\"data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7\" />
  </div>
  <script>
    window.testReady = true;
    console.log('test-page-loaded');
  </script>
</body></html>")

(def ^:private scrollable-page-html
  "<!DOCTYPE html>
<html><head><title>Scrollable Test Page</title>
<style>
  body { margin: 0; padding: 20px; height: 3000px; }
  .scroll-auto { width: 300px; height: 200px; overflow: auto; border: 1px solid #ccc; }
  .scroll-y    { width: 300px; height: 150px; overflow-y: scroll; overflow-x: hidden; border: 1px solid #aaa; }
  .no-overflow { width: 300px; height: 200px; overflow: hidden; border: 1px solid #eee; }
  .no-scroll   { width: 300px; height: 200px; overflow: visible; border: 1px solid #ddd; }
  .tall-content { height: 800px; }
  .wide-content { width: 600px; }
  #nested-outer { width: 400px; height: 300px; overflow: auto; border: 2px solid blue; }
  #nested-inner { width: 350px; height: 200px; overflow: auto; border: 2px solid green; margin: 10px; }
</style>
</head>
<body>
  <h1>Scrollable Test</h1>

  <!-- Scrollable: overflow:auto with tall content -->
  <div id=\"auto-scroll\" class=\"scroll-auto\" role=\"region\" aria-label=\"Auto Scroll\">
    <div class=\"tall-content\">Auto scroll content that overflows vertically</div>
  </div>

  <!-- Scrollable: overflow-y:scroll -->
  <div id=\"y-scroll\" class=\"scroll-y\">
    <div class=\"tall-content\">Y-scroll content that overflows</div>
  </div>

  <!-- NOT scrollable: overflow:hidden -->
  <div id=\"hidden-overflow\" class=\"no-overflow\">
    <div class=\"tall-content\">Hidden overflow — NOT scrollable</div>
  </div>

  <!-- NOT scrollable: no overflow, content fits -->
  <div id=\"no-overflow\" class=\"no-scroll\">
    <p>Short content that fits</p>
  </div>

  <!-- Nested scrollable containers -->
  <div id=\"nested-outer\">
    <div class=\"tall-content\">
      <div id=\"nested-inner\">
        <div class=\"tall-content\">Nested inner scrollable content</div>
      </div>
      Outer scrollable content
    </div>
  </div>

  <!-- Scroll position tracker -->
  <div id=\"scroll-pos\">0</div>
  <script>
    window.addEventListener('scroll', function() {
      document.getElementById('scroll-pos').textContent = Math.round(window.scrollY);
    });
  </script>
</body></html>")

(def ^:private keyboard-page-html
  "<!DOCTYPE html>
<html><head><title>Keyboard Test Page</title></head>
<body>
  <h1>Keyboard Test</h1>
  <div id=\"last-key\">none</div>
  <div id=\"key-log\"></div>
  <input type=\"text\" id=\"key-input\" placeholder=\"Type here\" />
  <script>
    document.addEventListener('keydown', function(e) {
      document.getElementById('last-key').textContent = e.key;
      var log = document.getElementById('key-log');
      log.textContent = (log.textContent ? log.textContent + ',' : '') + e.key;
    });
  </script>
</body></html>")

(def ^:private dialog-page-html
  "<!DOCTYPE html>
<html><head><title>Dialog Page</title></head>
<body>
  <h1>Dialog Test</h1>
  <button id=\"alert-btn\" onclick=\"alert('hello')\">Alert</button>
  <button id=\"confirm-btn\" onclick=\"document.getElementById('result').textContent = confirm('ok?')\">Confirm</button>
  <button id=\"prompt-btn\" onclick=\"document.getElementById('result').textContent = prompt('name?','default')\">Prompt</button>
  <div id=\"result\"></div>
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

(def ^:private markdown-layout-page-html
  "<!DOCTYPE html>
<html><head><title>Markdown Layout Page</title></head>
<body>
  <center>
    <table>
      <tr><td><a href=\"https://example.com/1\">First Story</a></td></tr>
      <tr><td><a href=\"https://example.com/2\">Second Story</a></td></tr>
    </table>
  </center>
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

          (and (= "GET" method) (= "/dialog-page" path))
          (send-response exchange 200 dialog-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/iframe-page" path))
          (send-response exchange 200 iframe-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/markdown-layout-page" path))
          (send-response exchange 200 markdown-layout-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/keyboard-page" path))
          (send-response exchange 200 keyboard-page-html "text/html; charset=UTF-8")

          (and (= "GET" method) (= "/scrollable-page" path))
          (send-response exchange 200 scrollable-page-html "text/html; charset=UTF-8")

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

          (and (= "GET" method) (= "/redirect-page" path))
          (let [headers (.getResponseHeaders exchange)]
            (.set headers "Location" (str "http://localhost:" (.getPort (.getLocalAddress exchange)) "/test-page"))
            (.sendResponseHeaders exchange 301 -1)
            (.close (.getResponseBody exchange)))

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
