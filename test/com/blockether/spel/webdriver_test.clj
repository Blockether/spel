(ns com.blockether.spel.webdriver-test
  "Unit tests for the W3C WebDriver client — runs against an in-process fake
   WebDriver HTTP server (no Appium, no simulator, all platforms).

   Covers:
   - Request methods, endpoint paths, and JSON payloads
   - Session ID extraction from W3C and legacy response shapes
   - Element ID extraction from W3C and legacy keys
   - Execute-script `return` wrapping semantics
   - Screenshot base64 decoding
   - W3C error decoding into structured ex-info
   - Malformed responses and request timeouts
   - Tap/swipe pointer-action payload structure"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.webdriver :as sut])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]
   [java.util Base64]))

;; =============================================================================
;; Fake WebDriver server
;; =============================================================================

(defn- start-fake-server!
  "Starts an HttpServer that records every request and answers from a
   response queue. Returns {:server :port :url :requests-atom :respond!}.

   `respond!` sets the next response as {:status N :body map-or-string}.
   When the queue is empty, responds 200 {\"value\" nil}."
  []
  (let [requests  (atom [])
        responses (atom [])
        srv       (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                body   (slurp (.getRequestBody ex))
                req    {:method (.getRequestMethod ex)
                        :path   (.getPath (.getRequestURI ex))
                        :body   (when-not (str/blank? body)
                                  (json/read-json body))}
                _      (swap! requests conj req)
                resp   (or (when (seq @responses)
                             (let [[r & more] @responses]
                               (reset! responses (vec more))
                               r))
                         {:status 200 :body {"value" nil}})
                out    (.getBytes ^String
                         (if (string? (:body resp))
                           (:body resp)
                           (json/write-json-str (:body resp))))]
            (.add (.getResponseHeaders ex) "Content-Type" "application/json")
            (.sendResponseHeaders ex (int (:status resp)) (alength out))
            (with-open [os (.getResponseBody ex)]
              (.write os out))))))
    (.start srv)
    (let [port (.getPort (.getAddress srv))]
      {:server   srv
       :port     port
       :url      (str "http://127.0.0.1:" port)
       :requests requests
       :respond! (fn [& rs] (swap! responses into rs))})))

(defmacro with-fake-server
  "Runs body with `srv` bound to a running fake WebDriver server, stopping
   it afterwards."
  [srv & body]
  `(let [~srv (start-fake-server!)]
     (try
       ~@body
       (finally (.stop ^HttpServer (:server ~srv) 0)))))

(defn- fake-session
  "Builds a WebDriverSession pointing at the fake server."
  [srv]
  (sut/->WebDriverSession (:url srv) "sess-1" {} 5000))

;; =============================================================================
;; Session ID / element ID extraction (pure)
;; =============================================================================

(defdescribe extraction-test
  "session/element id extraction across W3C and legacy shapes"

  (describe "extract-session-id"
    (it "extracts W3C sessionId from value"
      (expect (= "abc"
                (sut/extract-session-id {"value" {"sessionId" "abc"}}))))

    (it "extracts legacy top-level sessionId"
      (expect (= "legacy"
                (sut/extract-session-id {"sessionId" "legacy" "value" {}}))))

    (it "prefers W3C shape when both are present"
      (expect (= "w3c"
                (sut/extract-session-id {"sessionId" "legacy"
                                         "value" {"sessionId" "w3c"}})))))

  (describe "extract-capabilities"
    (it "extracts W3C capabilities"
      (expect (= {"browserName" "Safari"}
                (sut/extract-capabilities
                  {"value" {"sessionId" "x"
                            "capabilities" {"browserName" "Safari"}}}))))

    (it "falls back to legacy value map"
      (expect (= {"browserName" "Safari"}
                (sut/extract-capabilities
                  {"sessionId" "x" "value" {"browserName" "Safari"}})))))

  (describe "extract-element-id"
    (it "extracts the W3C element key"
      (expect (= "el-1"
                (sut/extract-element-id
                  {"element-6066-11e4-a52e-4f735466cecf" "el-1"}))))

    (it "extracts the legacy ELEMENT key"
      (expect (= "el-2" (sut/extract-element-id {"ELEMENT" "el-2"}))))

    (it "returns nil for non-map values"
      (expect (nil? (sut/extract-element-id nil)))
      (expect (nil? (sut/extract-element-id "nope"))))))

;; =============================================================================
;; Script wrapping
;; =============================================================================

(defdescribe wrap-expression-script-test
  "W3C execute-script requires an explicit return"

  (it "wraps bare expressions in return (...)"
    (expect (= "return (document.title);"
              (sut/wrap-expression-script "document.title"))))

  (it "wraps IIFE-style snapshot scripts"
    (expect (= "return ((() => { return 1; })());"
              (sut/wrap-expression-script "(() => { return 1; })()"))))

  (it "passes through scripts that already return"
    (expect (= "return document.title;"
              (sut/wrap-expression-script "return document.title;"))))

  (it "trims surrounding whitespace before deciding"
    (expect (= "return 42;"
              (sut/wrap-expression-script "  return 42;  ")))))

;; =============================================================================
;; HTTP request behavior
;; =============================================================================

(defdescribe request-transport-test
  "request paths, methods, and payloads against a fake server"

  (describe "create-session"
    (it "POSTs W3C capabilities envelope to /session and returns a session record"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200
          :body {"value" {"sessionId" "s-123"
                          "capabilities" {"browserName" "Safari"}}}})
        (let [session (sut/create-session (:url srv)
                        {"platformName" "iOS"} {:timeout-ms 3000})]
          (expect (= "s-123" (:session-id session)))
          (expect (= {"browserName" "Safari"} (:capabilities session)))
          (let [req (first @(:requests srv))]
            (expect (= "POST" (:method req)))
            (expect (= "/session" (:path req)))
            (expect (= {"platformName" "iOS"}
                      (get-in (:body req) ["capabilities" "alwaysMatch"])))
            (expect (= [{}] (get-in (:body req) ["capabilities" "firstMatch"])))))))

    (it "throws when no session id is present"
      (with-fake-server srv
        ((:respond! srv) {:status 200 :body {"value" {}}})
        (expect
          (try
            (sut/create-session (:url srv) {})
            false
            (catch clojure.lang.ExceptionInfo _ true))))))

  (describe "navigation endpoints"
    (it "navigate POSTs the url"
      (with-fake-server srv
        (sut/navigate (fake-session srv) "https://example.org")
        (let [req (first @(:requests srv))]
          (expect (= "POST" (:method req)))
          (expect (= "/session/sess-1/url" (:path req)))
          (expect (= {"url" "https://example.org"} (:body req))))))

    (it "url GETs the current url"
      (with-fake-server srv
        ((:respond! srv) {:status 200 :body {"value" "https://example.org/"}})
        (expect (= "https://example.org/" (sut/url (fake-session srv))))
        (let [req (first @(:requests srv))]
          (expect (= "GET" (:method req)))
          (expect (= "/session/sess-1/url" (:path req))))))

    (it "back/forward/reload hit the standard endpoints"
      (with-fake-server srv
        (let [s (fake-session srv)]
          (sut/back s)
          (sut/forward s)
          (sut/reload s))
        (expect (= [["POST" "/session/sess-1/back"]
                    ["POST" "/session/sess-1/forward"]
                    ["POST" "/session/sess-1/refresh"]]
                  (mapv (juxt :method :path) @(:requests srv))))))

    (it "cookies GETs /session/{id}/cookie and returns the raw W3C list"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200
          :body {"value" [{"name" "sid" "value" "abc" "domain" "127.0.0.1"
                           "path" "/" "expiry" 1999999999
                           "httpOnly" true "secure" false "sameSite" "Lax"}]}})
        (let [cs (sut/cookies (fake-session srv))]
          (expect (= 1 (count cs)))
          (expect (= "sid" (get (first cs) "name")))
          (expect (= "abc" (get (first cs) "value"))))
        (let [req (first @(:requests srv))]
          (expect (= "GET" (:method req)))
          (expect (= "/session/sess-1/cookie" (:path req))))))

    (it "delete-session! DELETEs /session/{id}"
      (with-fake-server srv
        (sut/delete-session! (fake-session srv))
        (let [req (first @(:requests srv))]
          (expect (= "DELETE" (:method req)))
          (expect (= "/session/sess-1" (:path req)))))))

  (describe "Appium contexts and applications"
    (it "lists and switches native/webview contexts"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" ["NATIVE_APP" "WEBVIEW_1"]}}
         {:status 200 :body {"value" nil}})
        (let [s (fake-session srv)]
          (expect (= ["NATIVE_APP" "WEBVIEW_1"] (sut/contexts s)))
          (expect (= "WEBVIEW_1" (sut/switch-context s "WEBVIEW_1"))))
        (expect (= [["GET" "/session/sess-1/contexts"]
                    ["POST" "/session/sess-1/context"]]
                  (mapv (juxt :method :path) @(:requests srv))))
        (expect (= {"name" "WEBVIEW_1"}
                  (:body (second @(:requests srv)))))))

    (it "activates and queries an app by bundle id"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" nil}}
         {:status 200 :body {"value" 4}})
        (let [s (fake-session srv)]
          (expect (= "com.example.app" (sut/activate-app s "com.example.app")))
          (expect (= 4 (sut/app-state s "com.example.app"))))
        (expect (= ["mobile: activateApp" "mobile: queryAppState"]
                  (mapv #(get-in % [:body "script"]) @(:requests srv)))))))

  (describe "evaluate"
    (it "POSTs wrapped script and args to execute/sync"
      (with-fake-server srv
        ((:respond! srv) {:status 200 :body {"value" 42}})
        (expect (= 42 (sut/evaluate (fake-session srv) "6 * 7")))
        (let [req (first @(:requests srv))]
          (expect (= "/session/sess-1/execute/sync" (:path req)))
          (expect (= "return (6 * 7);" (get (:body req) "script")))
          (expect (= [] (get (:body req) "args"))))))

    (it "execute-script-raw never wraps the script"
      (with-fake-server srv
        (sut/execute-script-raw (fake-session srv) "var x = 1; return x;")
        (expect (= "var x = 1; return x;"
                  (get (:body (first @(:requests srv))) "script")))))

    (it "execute-mobile sends the command verbatim with a single args map"
      (with-fake-server srv
        (sut/execute-mobile (fake-session srv) "mobile: viewportRect" {})
        (let [req (first @(:requests srv))]
          (expect (= "mobile: viewportRect" (get (:body req) "script")))
          (expect (= [{}] (get (:body req) "args")))))))

  (describe "elements"
    (it "parses web and native selector strategies"
      (expect (= {:using "css selector" :value "#save"}
                (sut/selector-strategy "#save" false)))
      (expect (= {:using "accessibility id" :value "Save"}
                (sut/selector-strategy "Save" true)))
      (expect (= {:using "xpath" :value "//XCUIElementTypeButton"}
                (sut/selector-strategy "xpath=//XCUIElementTypeButton" true)))
      (expect (= {:using "-ios predicate string" :value "label == 'Save'"}
                (sut/selector-strategy "predicate=label == 'Save'" true))))

    (it "find-element-by-css POSTs the css selector strategy"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200
          :body {"value" {"element-6066-11e4-a52e-4f735466cecf" "el-9"}}})
        (expect (= "el-9" (sut/find-element-by-css (fake-session srv) "#btn")))
        (let [req (first @(:requests srv))]
          (expect (= "/session/sess-1/element" (:path req)))
          (expect (= {"using" "css selector" "value" "#btn"} (:body req))))))

    (it "fill finds, clears, then sends keys"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" {"ELEMENT" "el-3"}}})
        (sut/fill (fake-session srv) "#name" "hello")
        (let [reqs @(:requests srv)]
          (expect (= ["/session/sess-1/element"
                      "/session/sess-1/element/el-3/clear"
                      "/session/sess-1/element/el-3/value"]
                    (mapv :path reqs)))
          (expect (= {"text" "hello"} (:body (nth reqs 2)))))))

    (it "element-rect decodes numeric rect fields"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200
          :body {"value" {"x" 10 "y" 20 "width" 100 "height" 50}}})
        (expect (= {:x 10.0 :y 20.0 :width 100.0 :height 50.0}
                  (sut/element-rect (fake-session srv) "el-1"))))))

  (describe "screenshot"
    (it "decodes base64 PNG bytes"
      (with-fake-server srv
        (let [payload (byte-array [(byte 1) (byte 2) (byte 3)])
              b64     (.encodeToString (Base64/getEncoder) payload)]
          ((:respond! srv) {:status 200 :body {"value" b64}})
          (expect (= [1 2 3] (vec (sut/screenshot (fake-session srv))))))))))

;; =============================================================================
;; Error handling
;; =============================================================================

(defdescribe error-handling-test
  "W3C errors, malformed responses, and timeouts"

  (it "decodes W3C error responses into structured ex-info"
    (with-fake-server srv
      ((:respond! srv)
       {:status 404
        :body {"value" {"error" "no such element"
                        "message" "element not found"
                        "stacktrace" "trace..."}}})
      (let [data (try
                   (sut/find-element-by-css (fake-session srv) "#missing")
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (expect (= "no such element" (:webdriver/error data)))
        (expect (= "element not found" (:webdriver/message data)))
        (expect (= "trace..." (:webdriver/stacktrace data)))
        (expect (= 404 (:webdriver/http-status data)))
        (expect (= "/session/sess-1/element" (:webdriver/endpoint data))))))

  (it "handles non-JSON error bodies without losing the HTTP status"
    (with-fake-server srv
      ((:respond! srv) {:status 500 :body "Internal Server Error"})
      (let [data (try
                   (sut/url (fake-session srv))
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (expect (= 500 (:webdriver/http-status data))))))

  (it "throws on malformed JSON in a 200 response"
    (with-fake-server srv
      ((:respond! srv) {:status 200 :body "{not json"})
      (expect
        (try
          (sut/url (fake-session srv))
          false
          (catch clojure.lang.ExceptionInfo e
            (str/includes? (.getMessage e) "malformed"))))))

  (it "times out when the server does not respond in time"
    ;; Point at a routable but non-responding address using a tiny timeout.
    (expect
      (try
        (sut/request {:base-url "http://10.255.255.1:4723"
                      :method :get
                      :path "/status"
                      :timeout-ms 200})
        false
        (catch Exception _ true))))

  (it "status returns the ready value and ready? is false on connect failure"
    (with-fake-server srv
      ((:respond! srv) {:status 200 :body {"value" {"ready" true}}})
      (expect (true? (get (sut/status (:url srv)) "ready"))))
    (expect (false? (sut/ready? "http://127.0.0.1:1")))))

;; =============================================================================
;; Pointer action payloads
;; =============================================================================

(defdescribe pointer-actions-test
  "W3C touch pointer payload structure"

  (it "tap payload is a touch pointer down/pause/up at coordinates"
    (let [payload (sut/tap-actions-payload 100 200)
          pointer (first (get payload "actions"))
          actions (get pointer "actions")]
      (expect (= "pointer" (get pointer "type")))
      (expect (= {"pointerType" "touch"} (get pointer "parameters")))
      (expect (= ["pointerMove" "pointerDown" "pause" "pointerUp"]
                (mapv #(get % "type") actions)))
      (expect (= 100 (get (first actions) "x")))
      (expect (= 200 (get (first actions) "y")))))

  (it "swipe payload moves between coordinates with duration"
    (let [payload (sut/swipe-actions-payload [200 600] [200 100] 800)
          actions (get (first (get payload "actions")) "actions")
          move    (nth actions 2)]
      (expect (= ["pointerMove" "pointerDown" "pointerMove" "pointerUp"]
                (mapv #(get % "type") actions)))
      (expect (= 800 (get move "duration")))
      (expect (= 200 (get move "x")))
      (expect (= 100 (get move "y")))))

  (it "tap issues an Appium mobile: tap gesture (viewport probe first)"
    (with-fake-server srv
      (sut/tap (fake-session srv) 10 20)
      ;; Request 1 probes mobile: viewportRect (fake answers nil → no
      ;; offset), request 2 is the mobile: tap gesture. No /actions — WDA's
      ;; W3C touch synthesis fails Safari's tap recognizer (no click).
      (let [reqs @(:requests srv)]
        (expect (= [["POST" "/session/sess-1/execute/sync"]
                    ["POST" "/session/sess-1/execute/sync"]]
                  (mapv (juxt :method :path) reqs)))
        (expect (= "mobile: tap" (get (:body (last reqs)) "script")))
        (expect (= [{"x" 10 "y" 20}] (get (:body (last reqs)) "args"))))))

  (it "tap falls back to W3C actions when mobile: tap is not implemented"
    (with-fake-server srv
      ((:respond! srv)
       {:status 200 :body {"value" nil}} ; viewportRect → no offset
       {:status 404 :body {"value" {"error" "unknown command"
                                    "message" "Method is not implemented"}}})
      (sut/tap (fake-session srv) 10 20)
      (let [last-req (last @(:requests srv))]
        (expect (= "/session/sess-1/actions" (:path last-req)))
        (expect (= "touch" (get-in (:body last-req)
                             ["actions" 0 "parameters" "pointerType"]))))))

  (it "tap re-throws genuine mobile: tap failures instead of falling back"
    (with-fake-server srv
      ((:respond! srv)
       {:status 200 :body {"value" nil}}
       {:status 500 :body {"value" {"error" "invalid argument"
                                    "message" "coordinates out of bounds"}}})
      (expect
        (try (sut/tap (fake-session srv) 10 20)
             false
             (catch clojure.lang.ExceptionInfo _ true)))
      (expect (not-any? #(= "/session/sess-1/actions" (:path %))
                @(:requests srv)))))

  (describe "viewport offset translation"
    (it "falls back to zero offset when mobile: extensions are unavailable"
      (with-fake-server srv
        (expect (= {:x 0 :y 0} (sut/viewport-offset (fake-session srv))))))

    (it "derives native-point offset from viewportRect pixels / screen scale"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" {"left" 0 "top" 282 "width" 1170 "height" 2148}}}
         {:status 200 :body {"value" {"scale" 3 "statusBarSize" {"width" 390 "height" 47}}}})
        (expect (= {:x 0 :y 94} (sut/viewport-offset (fake-session srv))))))

    (it "tap adds the chrome offset to viewport coordinates"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" {"left" 0 "top" 282}}}
         {:status 200 :body {"value" {"scale" 3}}})
        (sut/tap (fake-session srv) 10 20)
        (let [tap-req (last @(:requests srv))]
          (expect (= "mobile: tap" (get (:body tap-req) "script")))
          (expect (= [{"x" 10 "y" 114}] (get (:body tap-req) "args"))))))

    (it "swipe translates both endpoints by the offset"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" {"left" 0 "top" 100}}}
         {:status 200 :body {"value" {"scale" 1}}})
        (sut/swipe (fake-session srv) {:from [200 600] :to [200 100]})
        (let [actions (get-in (:body (last @(:requests srv)))
                        ["actions" 0 "actions"])]
          (expect (= 700 (get (first actions) "y")))
          (expect (= 200 (get (nth actions 2) "y"))))))

    (it "element-viewport-center passes the element as a W3C script argument"
      (with-fake-server srv
        ((:respond! srv)
         {:status 200 :body {"value" {"x" 125.4 "y" 215.2}}})
        (expect (= {:x 125 :y 215}
                  (sut/element-viewport-center (fake-session srv) "el-9")))
        (let [req (first @(:requests srv))]
          (expect (= [{"element-6066-11e4-a52e-4f735466cecf" "el-9"}]
                    (get (:body req) "args")))
          (expect (str/includes? (get (:body req) "script") "getBoundingClientRect")))))

    (it "element-viewport-center returns nil on unusable results"
      (with-fake-server srv
        (expect (nil? (sut/element-viewport-center (fake-session srv) "el-9"))))))

  (it "swipe requires :from and :to"
    (expect
      (try
        (sut/swipe (sut/->WebDriverSession "http://x" "s" {} 1000) {:from [1 2]})
        false
        (catch clojure.lang.ExceptionInfo _ true)))))

(defdescribe native-element-and-appium-extensions-test
  "Native element queries, detailed contexts, lifecycle, and keyboard"

  (it "returns detailed webview context metadata"
    (with-fake-server srv
      ((:respond! srv)
       {:status 200 :body {"value" [{"id" "NATIVE_APP"}
                                    {"id" "WEBVIEW_1" "title" "Expo"
                                     "url" "https://example.test"}]}})
      (let [details (sut/context-details (fake-session srv))
            req     (first @(:requests srv))]
        (expect (= "https://example.test" (get (second details) "url")))
        (expect (= "mobile: getContexts" (get (:body req) "script"))))))

  (it "finds multiple elements and reads native state"
    (with-fake-server srv
      ((:respond! srv)
       {:status 200 :body {"value" [{"element-6066-11e4-a52e-4f735466cecf" "a"}
                                    {"ELEMENT" "b"}]}}
       {:status 200 :body {"value" "Log in"}}
       {:status 200 :body {"value" true}})
      (let [session (fake-session srv)]
        (expect (= ["a" "b"] (sut/find-elements session "class name" "XCUIElementTypeButton")))
        (expect (= "Log in" (sut/element-text session "a")))
        (expect (true? (sut/element-enabled? session "a"))))))

  (it "sends named keys to the focused element"
    (with-fake-server srv
      ((:respond! srv)
       {:status 200 :body {"value" {"element-6066-11e4-a52e-4f735466cecf" "focused"}}}
       {:status 200 :body {"value" nil}})
      (sut/press-key (fake-session srv) "Enter")
      (let [requests @(:requests srv)]
        (expect (= "/session/sess-1/element/active" (:path (first requests))))
        (expect (= "\uE007" (get-in (second requests) [:body "text"]))))))

  (it "uses Appium mobile commands for app lifecycle and permissions"
    (with-fake-server srv
      (let [session (fake-session srv)]
        (sut/terminate-app session "com.example.app")
        (sut/background-app session 5)
        (sut/open-url session "example://screen" "com.example.app")
        (sut/get-permission session "com.example.app" "camera")
        (sut/set-permission session "com.example.app" "camera" "yes")
        (expect (= ["mobile: terminateApp" "mobile: backgroundApp"
                    "mobile: deepLink" "mobile: getPermission" "mobile: setPermission"]
                  (mapv #(get-in % [:body "script"]) @(:requests srv))))
        (expect (= [{"bundleId" "com.example.app"
                     "access" {"camera" "yes"}}]
                  (get-in (last @(:requests srv)) [:body "args"])))))))
