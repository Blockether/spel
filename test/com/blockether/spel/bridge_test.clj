(ns com.blockether.spel.bridge-test
  "End-to-end tests for the `spel connect` loopback bridge — no mocks.

   A real Chromium tab navigates to the bridge's harness page, which loads the
   embedded engine and subscribes over Server-Sent Events. The test then drives
   the tab entirely from the server side via the bridge's `:send!` fn and
   asserts the browser actually executed each command against the live DOM."
  (:require
   [com.blockether.spel.bridge :as bridge]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe expect it around]]))

(defn- wait-for-client!
  "Blocks until at least one browser tab has subscribed, or throws on timeout."
  [b timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pos? ((:clients b))) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "no browser subscribed to the bridge in time" {}))
        :else (do (Thread/sleep 50) (recur))))))

(defdescribe bridge-engine-test
  "The bridge exposes the embedded engine source."

  (it "engine-source returns the installable spel.js"
    (let [src (bridge/engine-source)]
      (expect (string? src))
      (expect (re-find #"__spel" src))
      (expect (re-find #"version: \"0\.2\.0\"" src)))))

(defdescribe bridge-roundtrip-test
  "A real browser subscribes over SSE and answers commands pushed by the server."
  (around [f] (core/with-testing-browser (f)))

  (it "browser subscribes and round-trips invoke commands"
    (let [b (bridge/create-bridge :port 0)]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; meta command straight through the SSE → POST loop
          (let [r ((:send! b) {:action "ping"})]
            (expect (= true (get r "ok")))
            (expect (= "pong" (get r "value"))))
          ;; read the live DOM of the harness page from the server side
          (let [r ((:send! b) {:action "get_text" :selector "h1"})]
            (expect (= "spel bridge" (get r "value"))))
          ;; mutate the page remotely with a multi-statement body, then observe
          ((:send! b) {:action "evaluate"
                       :script "document.body.setAttribute('data-remote','ok'); return true;"})
          (let [r ((:send! b) {:action "evaluate"
                               :script "document.body.getAttribute('data-remote')"})]
            (expect (= "ok" (get r "value"))))
          ;; error results propagate back with ok:false
          (let [r ((:send! b) {:action "no-such-action"})]
            (expect (= false (get r "ok")))
            (expect (re-find #"unknown action" (get r "error")))))
        (finally ((:stop b))))))

  (it "serves the engine over HTTP and the tab installs the api"
    (let [b (bridge/create-bridge :port 0)]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          (expect (= "0.2.0" (page/evaluate pg "window.__spel.version")))
          ;; the tab holds a live SSE connection back to this bridge
          (expect (= "sse" (page/evaluate pg "window.__spel.connection() && window.__spel.connection().transport"))))
        (finally ((:stop b)))))))
