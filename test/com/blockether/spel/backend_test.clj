(ns com.blockether.spel.backend-test
  "Unit tests for the shared browser-backend abstraction.

   Covers:
   - Capability sets and supports?
   - Ref → CSS selector resolution
   - Capability error shape (unsupported!)
   - Playwright backend rejecting native tap/swipe
   - IosAppBackend routing through WebDriver (fake HTTP server)
   - Stale-ref error for missing @refs on the iOS backend"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe expect it]]
   [com.blockether.spel.backend :as sut]
   [com.blockether.spel.ios :as ios]
   [com.blockether.spel.webdriver :as webdriver])
  (:import
   [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
   [java.net InetSocketAddress]))

;; =============================================================================
;; Fake WebDriver server (records requests, queued responses)
;; =============================================================================

(defn- start-fake-server! []
  (let [requests  (atom [])
        responses (atom [])
        srv       (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                body (slurp (.getRequestBody ex))
                _    (swap! requests conj
                       {:method (.getRequestMethod ex)
                        :path   (.getPath (.getRequestURI ex))
                        :body   (when-not (str/blank? body) (json/read-json body))})
                resp (or (when (seq @responses)
                           (let [[r & more] @responses]
                             (reset! responses (vec more))
                             r))
                       {:status 200 :body {"value" nil}})
                out  (.getBytes ^String (json/write-json-str (:body resp)))]
            (.add (.getResponseHeaders ex) "Content-Type" "application/json")
            (.sendResponseHeaders ex (int (:status resp)) (alength out))
            (with-open [os (.getResponseBody ex)]
              (.write os out))))))
    (.start srv)
    {:server   srv
     :url      (str "http://127.0.0.1:" (.getPort (.getAddress srv)))
     :requests requests
     :respond! (fn [& rs] (swap! responses into rs))}))

(defmacro ^:private with-ios-backend
  "Runs body with `b` bound to an IosAppBackend over a fake WebDriver
   server and `srv` bound to the server handle."
  [[b srv] & body]
  `(let [~srv (start-fake-server!)
         wd#  (webdriver/->WebDriverSession (:url ~srv) "sess-1" {} 5000)
         ~b   (sut/ios-backend
                (ios/map->IosSession {:device {:udid "U1" :name "iPhone 16"}
                                      :webdriver wd#
                                      :context* (atom "WEBVIEW_1")
                                      :native-refs* (atom {})
                                      :session-name "backend-test"}))]
     (try
       ~@body
       (finally (.stop ^HttpServer (:server ~srv) 0)))))

;; =============================================================================
;; Selector / ref resolution
;; =============================================================================

(defdescribe selector-resolution-test
  "ref detection and CSS resolution"

  (it "detects @refs"
    (expect (true? (sut/ref-selector? "@e2yrjz")))
    (expect (false? (sut/ref-selector? "#main")))
    (expect (false? (sut/ref-selector? "e2yrjz")))
    (expect (false? (sut/ref-selector? nil))))

  (it "resolves refs to data-pw-ref selectors"
    (expect (= "[data-pw-ref=\"e2yrjz\"]" (sut/resolve-css "@e2yrjz"))))

  (it "passes CSS selectors through unchanged"
    (expect (= "#main .btn" (sut/resolve-css "#main .btn")))))

;; =============================================================================
;; Capabilities
;; =============================================================================

(defdescribe capabilities-test
  "backend capability matrices"

  (it "iOS backend supports the MVP surface"
    (with-ios-backend [b _srv]
      (expect (= :ios (sut/backend-kind b)))
      (doseq [cap [:navigate :evaluate :snapshot :click :fill :clear
                   :screenshot :back :forward :reload :cookies :scroll
                   :element-query :element-wait :keyboard :deep-link :permissions]]
        (expect (true? (sut/supports? b cap))))))

  (it "iOS backend rejects Playwright-only capabilities"
    (with-ios-backend [b _srv]
      (doseq [cap [:cdp :tracing :har :network :frames :tabs :emulation
                   :storage-state]]
        (expect (false? (sut/supports? b cap))))))

  (it "unsupported! names the operation, backend, and capabilities"
    (with-ios-backend [b _srv]
      (let [[msg data] (try
                         (sut/unsupported! b "trace" "Use Playwright for tracing.")
                         nil
                         (catch clojure.lang.ExceptionInfo e
                           [(.getMessage e) (ex-data e)]))]
        (expect (str/includes? msg "'trace' is not supported"))
        (expect (str/includes? msg "ios"))
        (expect (str/includes? msg "Use Playwright for tracing."))
        (expect (= "unsupported_capability" (:error_code data)))
        (expect (= "ios" (:backend data)))
        (expect (some #{"element-query"} (:capabilities data)))))))

;; =============================================================================
;; iOS backend WebDriver routing
;; =============================================================================

(defdescribe ios-backend-routing-test
  "IosAppBackend operations map to WebDriver endpoints"

  (it "navigate! POSTs the url then reads it back"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 200 :body {"value" nil}}
       {:status 200 :body {"value" "https://example.org/"}})
      (expect (= {:url "https://example.org/"}
                (sut/navigate! b "https://example.org" {})))
      (expect (= ["/session/sess-1/url" "/session/sess-1/url"]
                (mapv :path @(:requests srv))))))

  (it "click! on a ref uses the data-pw-ref CSS selector"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 200 :body {"value" {"element-6066-11e4-a52e-4f735466cecf" "el-7"}}})
      (sut/click! b "@e2yrjz" {})
      (let [reqs @(:requests srv)]
        (expect (= {"using" "css selector"
                    "value" "[data-pw-ref=\"e2yrjz\"]"}
                  (:body (first reqs))))
        (expect (= "/session/sess-1/element/el-7/click"
                  (:path (second reqs)))))))

  (it "fill! clears before typing"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 200 :body {"value" {"ELEMENT" "el-2"}}})
      (sut/fill! b "#name" "hello" {})
      (expect (= ["/session/sess-1/element"
                  "/session/sess-1/element/el-2/clear"
                  "/session/sess-1/element/el-2/value"]
                (mapv :path @(:requests srv))))))

  (it "click! uses accessibility selectors in native app context"
    (with-ios-backend [b srv]
      (reset! (:context* (:ios-session b)) "NATIVE_APP")
      ((:respond! srv)
       {:status 200 :body {"value" {"ELEMENT" "native-button"}}})
      (sut/click! b "accessibility-id=Sign in" {})
      (expect (= {"using" "accessibility id" "value" "Sign in"}
                (:body (first @(:requests srv)))))
      (expect (= "/session/sess-1/element/native-button/click"
                (:path (second @(:requests srv)))))))

  (it "native snapshots expose compact semantics and clickable refs"
    (with-ios-backend [b srv]
      (reset! (:context* (:ios-session b)) "NATIVE_APP")
      ((:respond! srv)
       {:status 200
        :body {"value" (str "<AppiumAUT><XCUIElementTypeApplication type=\"XCUIElementTypeApplication\" name=\"Demo\">"
                         "<XCUIElementTypeButton type=\"XCUIElementTypeButton\" label=\"Log in\"/>"
                         "</XCUIElementTypeApplication></AppiumAUT>")}})
      (let [snap (sut/capture-snapshot! b {})]
        (expect (true? (:native snap)))
        (expect (= "NATIVE_APP" (:context snap)))
        (expect (str/includes? (:tree snap) "application \"Demo\""))
        (expect (re-find #"button \"Log in\" \[@e[a-z0-9]+\]" (:tree snap)))
        (expect (= 1 (:counter snap))))))

  (it "cookies returns Playwright-shaped maps (W3C expiry → :expires)"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 200
        :body {"value" [{"name" "sid" "value" "abc" "domain" "127.0.0.1"
                         "path" "/" "expiry" 1999999999
                         "httpOnly" true "secure" false "sameSite" "Lax"}]}})
      (expect (= [{:name "sid" :value "abc" :domain "127.0.0.1" :path "/"
                   :expires 1999999999 :httpOnly true :secure false
                   :sameSite "Lax"}]
                (sut/cookies b)))
      (expect (= [["GET" "/session/sess-1/cookie"]]
                (mapv (juxt :method :path) @(:requests srv))))))

  (it "tap! on a ref taps the scroll-safe viewport center via mobile: tap"
    (with-ios-backend [b srv]
      ((:respond! srv)
       ;; 1. find element, 2. scrollIntoView + getBoundingClientRect center,
       ;; 3. mobile: viewportRect probe (nil → zero offset), 4. mobile: tap.
       {:status 200 :body {"value" {"element-6066-11e4-a52e-4f735466cecf" "el-3"}}}
       {:status 200 :body {"value" {"x" 125 "y" 215}}})
      (let [result (sut/tap! b "@e2yrjz" {})]
        (expect (= 125 (:x result)))
        (expect (= 215 (:y result))))
      (let [reqs       @(:requests srv)
            center-req (second reqs)
            tap-req    (last reqs)]
        (expect (str/includes? (get (:body center-req) "script")
                  "getBoundingClientRect"))
        (expect (= "mobile: tap" (get (:body tap-req) "script")))
        (expect (= [{"x" 125 "y" 215}] (get (:body tap-req) "args"))))))

  (it "tap! accepts raw [x y] coordinates without any element lookup"
    (with-ios-backend [b srv]
      (expect (= {:x 42 :y 77} (sut/tap! b [42 77] {})))
      (let [reqs (vec @(:requests srv))]
        ;; Only the viewport-offset probe + the mobile: tap — no /element.
        (expect (not-any? #(str/includes? (:path %) "/element") reqs))
        (expect (= "mobile: tap" (get (:body (last reqs)) "script")))
        (expect (= [{"x" 42 "y" 77}] (get (:body (last reqs)) "args"))))))

  (it "navigate! retries transient 'no connected web application' failures"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 500
        :body {"value" {"error" "unknown error"
                        "message" "Remote debugger returned no connected web application within 5250ms"}}}
       {:status 200 :body {"value" nil}}
       {:status 200 :body {"value" "https://example.org/"}})
      (expect (= {:url "https://example.org/"}
                (sut/navigate! b "https://example.org" {})))
      ;; navigate (fail) → navigate (retry ok) → read url back.
      (expect (= 3 (count @(:requests srv))))))

  (it "navigate! does not retry ordinary navigation failures"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 500
        :body {"value" {"error" "unknown error"
                        "message" "cannot determine loading status"}}})
      (expect
        (try
          (sut/navigate! b "https://example.org" {})
          false
          (catch clojure.lang.ExceptionInfo _ true)))
      (expect (= 1 (count @(:requests srv))))))

  (it "stale @refs raise an explicit stale-ref error, never coordinates"
    (with-ios-backend [b srv]
      ((:respond! srv)
       {:status 404
        :body {"value" {"error" "no such element"
                        "message" "not found"}}})
      (let [data (try
                   (sut/tap! b "@e99999" {})
                   nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (expect (true? (:stale-ref data)))
        (expect (= "@e99999" (:selector data)))
        ;; No gesture was ever sent — neither W3C actions nor mobile: tap.
        (expect (not-any? #(or (str/ends-with? (:path %) "/actions")
                             (= "mobile: tap" (get (:body %) "script")))
                  @(:requests srv))))))

  (it "evaluate! wraps expressions for execute-script"
    (with-ios-backend [b srv]
      ((:respond! srv) {:status 200 :body {"value" "Example"}})
      (expect (= "Example" (sut/evaluate! b "document.title" [])))
      (expect (= "return (document.title);"
                (get (:body (first @(:requests srv))) "script"))))))

;; =============================================================================
;; Playwright backend capability rejections
;; =============================================================================

(defdescribe playwright-backend-test
  "Playwright backend surface"

  (it "reports the playwright kind and rich capability set"
    (let [b (sut/playwright-backend nil)]
      (expect (= :playwright (sut/backend-kind b)))
      (expect (true? (sut/supports? b :cdp)))
      (expect (true? (sut/supports? b :tracing)))
      (expect (false? (sut/supports? b :tap)))))

  (it "rejects native tap with a capability error pointing to click"
    (let [b (sut/playwright-backend nil)]
      (expect
        (try (sut/tap! b "@e1" {})
             false
             (catch clojure.lang.ExceptionInfo e
               (and (str/includes? (.getMessage e) "'tap' is not supported")
                 (str/includes? (.getMessage e) "click")))))))

  (it "rejects native swipe with a capability error pointing to scroll"
    (let [b (sut/playwright-backend nil)]
      (expect
        (try (sut/swipe! b {:direction :up})
             false
             (catch clojure.lang.ExceptionInfo e
               (str/includes? (.getMessage e) "scroll")))))))
