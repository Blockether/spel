(ns com.blockether.spel.source-maps-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.core :as core]
   [lazytest.core :refer [defdescribe it expect]])
  (:import
   [com.microsoft.playwright BrowserContext Page]
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.net InetSocketAddress]
   [java.util.function Consumer]))

(defn- with-source-server [map-body f]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange exchange exchange
                path (.getPath (.getRequestURI exchange))
                _ (swap! requests conj path)
                [mime body] (case path
                              "/main.js" ["application/javascript"
                                          "window.makeError = function(){return new Error('mapped');}; window.savedError = makeError(); setTimeout(function(){throw window.savedError;}, 0);\n//# sourceMappingURL=main.js.map"]
                              "/main.js.map" [(if (str/starts-with? map-body "<") "text/html" "application/json") map-body]
                              ["text/html" "<!doctype html><script src='/main.js?version=1'></script>"])
                bytes (.getBytes ^String body "UTF-8")]
            (.set (.getResponseHeaders exchange) "Content-Type" mime)
            (.sendResponseHeaders exchange 200 (alength bytes))
            (with-open [out (.getResponseBody exchange)]
              (.write out bytes))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) requests)
      (finally (.stop server 0)))))

(def ^:private index-map
  "{\"version\":3,\"sections\":[{\"offset\":{\"line\":0,\"column\":0},\"map\":{\"version\":3,\"sources\":[\"glms/app.cljs\"],\"names\":[],\"mappings\":\"AASA\"}}]}")

;; Regression, issue #140: browser stacks retained generated bundle positions.
(defdescribe source-maps-test
  (it "maps page stacks and page errors through an index map"
    (with-source-server index-map
      (fn [url _]
        (core/with-testing-page [^Page pg]
          (let [errors (atom [])]
            (.onPageError pg (reify Consumer (accept [_ error] (swap! errors conj error))))
            (.navigate pg url)
            (expect (str/includes? (str (.evaluate pg "window.savedError.stack")) "glms/app.cljs:10:1"))
            (.waitForTimeout pg 50)
            (expect (some #(str/includes? % "glms/app.cljs:10:1") @errors))
            (expect (= "undefined" (.evaluate pg "typeof window.sourceMapSupport"))))))))

  (it "leaves malformed maps harmless and reads each map once"
    (with-source-server "<!doctype html><title>SPA fallback</title>"
      (fn [url requests]
        (core/with-testing-page [^Page pg]
          (.navigate pg url)
          (dotimes [_ 2]
            (expect (str/includes? (str (.evaluate pg "window.makeError().stack")) "main.js?version=1:1:")))
          (expect (= 1 (count (filter #{"/main.js.map"} @requests))))))))

  (it "does not request maps when disabled"
    (with-source-server index-map
      (fn [url requests]
        (core/with-testing-page {:source-maps false} [^Page pg]
          (.navigate pg url)
          (expect (str/includes? (str (.evaluate pg "window.savedError.stack")) "main.js?version=1:1:"))
          (expect (not-any? #{"/main.js.map"} @requests))))))

  (it "supports explicit source maps on library contexts"
    (with-source-server index-map
      (fn [url _]
        (core/with-testing-browser
          (with-open [^BrowserContext ctx (core/new-context core/*testing-browser* {:source-maps true})]
            (let [pg (.newPage ctx)]
              (.navigate pg url)
              (expect (str/includes? (str (.evaluate pg "window.savedError.stack")) "glms/app.cljs:10:1"))))))))

  (it "maps frame stacks in a persistent test context"
    (with-source-server index-map
      (fn [url _]
        (core/with-testing-page {:profile ""} [^Page pg]
          (.navigate pg url)
          (.evaluate pg "url => { const f = document.createElement('iframe'); f.src = url; document.body.appendChild(f); }" url)
          (.waitForFunction pg "document.querySelector('iframe').contentWindow.savedError !== undefined")
          (expect (str/includes?
                    (str (.evaluate pg "document.querySelector('iframe').contentWindow.savedError.stack"))
                    "glms/app.cljs:10:1")))))))
