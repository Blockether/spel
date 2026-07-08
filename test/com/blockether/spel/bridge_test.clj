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
      (expect (re-find #"version: \"0\.4\.0\"" src)))))

(defdescribe bridge-eject-loader-test
  "The ejected loader/bookmarklet target the right origin + connect URL."

  (it "eject-origin defaults to the serving host/port, splits an explicit url"
    (expect (= ["http://127.0.0.1:8787" "/spel"]
              (bridge/eject-origin nil "127.0.0.1" 8787 "/spel")))
    (expect (= ["http://box.local:9000" "/spel"]
              (bridge/eject-origin "http://box.local:9000/spel" "127.0.0.1" 8787 "/spel")))
    ;; a bare origin (no path) falls back to the default path
    (expect (= ["http://box.local:9000" "/spel"]
              (bridge/eject-origin "http://box.local:9000" "127.0.0.1" 8787 "/spel"))))

  (it "loader-script injects spel.js and connects; bookmarklet just prefixes it"
    (let [loader (bridge/loader-script "http://127.0.0.1:8787" "/spel" "tok123")
          mark   (bridge/bookmarklet "http://127.0.0.1:8787" "/spel" "tok123")]
      (expect (not (.startsWith loader "javascript:")))
      (expect (.startsWith mark "javascript:"))
      (expect (= mark (str "javascript:" loader)))
      ;; the loader references the engine url and the connect url
      (expect (re-find #"/spel\.js" loader))
      (expect (re-find #"__spel" loader))
      (expect (re-find #"createElement" loader))
      (expect (re-find #"connect" loader))
      ;; the token rides into the connect call
      (expect (re-find #"tok123" loader))
      ;; without a token the loader emits t=null (auth disabled)
      (expect (re-find #"t=null" (bridge/loader-script "http://x" "/spel" nil))))))

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
          (expect (= "0.4.0" (page/evaluate pg "window.__spel.version")))
          ;; the tab holds a live SSE connection back to this bridge
          (expect (= "sse" (page/evaluate pg "window.__spel.connection() && window.__spel.connection().transport"))))
        (finally ((:stop b)))))))

(defdescribe bridge-target-test
  "The saved target profile round-trips and drives which transport a command
   takes (daemon vs. bridge)."

  (it "save/load/clear a bridge target on an explicit path"
    (let [tmp (str (System/getProperty "java.io.tmpdir")
                "/spel-target-test-" (System/currentTimeMillis) ".json")]
      (try
        (expect (nil? (bridge/load-target tmp)))
        (bridge/save-target! {:url "http://127.0.0.1:8787/spel"} tmp)
        (expect (= "http://127.0.0.1:8787/spel" (:url (bridge/load-target tmp))))
        (expect (= true (bridge/clear-target! tmp)))
        (expect (nil? (bridge/load-target tmp)))
        (finally (.delete (java.io.File. tmp)))))))

(defdescribe bridge-route-command-test
  "`route-command!` is the CLI-side client: it POSTs one command to the bridge's
   /command endpoint and gets the browser's result back in daemon shape. This is
   exactly the path a regular `spel <verb>` follows once a target is saved."
  (around [f] (core/with-testing-browser (f)))

  (it "routes a command to a live tab and adapts the result"
    (let [b (bridge/create-bridge :port 0)]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; meta ping straight through the HTTP /command endpoint
          (let [r (bridge/route-command! (:url b) {:action "ping"} 8000)]
            (expect (= true (:success r)))
            (expect (= "pong" (:data r))))
          ;; read the live DOM from the CLI side
          (let [r (bridge/route-command! (:url b) {:action "get_text" :selector "h1"} 8000)]
            (expect (= true (:success r)))
            (expect (= "spel bridge" (:data r))))
          ;; an unknown action comes back as a structured failure
          (let [r (bridge/route-command! (:url b) {:action "no-such-action"} 8000)]
            (expect (= false (:success r)))
            (expect (re-find #"unknown action" (:error r)))))
        (finally ((:stop b))))))

  (it "reports a clear error when no bridge is listening"
    (let [r (bridge/route-command! "http://127.0.0.1:1/spel" {:action "ping"} 1000)]
      (expect (= false (:success r)))
      (expect (re-find #"not reachable" (:error r))))))

(defdescribe bridge-token-test
  "A shared token gates browser<->bridge traffic: the right token round-trips,
   a missing/wrong one is refused with 403 before it reaches the tab."
  (around [f] (core/with-testing-browser (f)))

  (it "authorizes a tab carrying the token and refuses requests without it"
    (let [b (bridge/create-bridge :port 0 :token "s3cr3t")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; the harness embedded the token, so the tab subscribed and answers
          (let [r ((:send! b) {:action "ping"})]
            (expect (= "pong" (get r "value"))))
          ;; CLI-side: the correct token routes and adapts the result
          (let [r (bridge/route-command! (:url b) {:action "ping"} 8000 "s3cr3t")]
            (expect (= true (:success r)))
            (expect (= "pong" (:data r))))
          ;; CLI-side: a missing token is refused before it reaches the tab
          (let [r (bridge/route-command! (:url b) {:action "ping"} 8000 nil)]
            (expect (= false (:success r)))
            (expect (re-find #"unauthorized" (:error r))))
          ;; and a wrong token is refused too
          (let [r (bridge/route-command! (:url b) {:action "ping"} 8000 "nope")]
            (expect (= false (:success r)))
            (expect (re-find #"unauthorized" (:error r)))))
        (finally ((:stop b)))))))

(defdescribe bridge-reinject-test
  "The engine survives a full-page navigation: its connect route (url + token)
   is persisted per-tab in sessionStorage, so a real reload re-subscribes
   automatically instead of losing the tab."
  (around [f] (core/with-testing-browser (f)))

  (it "persists the route, clears it on disconnect, and re-subscribes on reload"
    (let [b (bridge/create-bridge :port 0 :token "tkn")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; the route is remembered so a fresh page can auto-reconnect
          (expect (= (:url b)
                    (page/evaluate pg "JSON.parse(sessionStorage.getItem('__spel_connect')).url")))
          (expect (= "tkn"
                    (page/evaluate pg "JSON.parse(sessionStorage.getItem('__spel_connect')).token")))
          ;; a real reload tears down window.__spel + the SSE; the fresh page
          ;; must re-subscribe on its own and still answer commands
          (page/reload pg)
          (wait-for-client! b 8000)
          ;; a fresh reload may briefly leave a stale SSE client in the map that
          ;; the one-shot broadcast hits first; retry until the reloaded tab answers
          (let [r (loop [n 6]
                    (let [res (try ((:send! b) {:action "ping"} 3000)
                                (catch Exception _ ::retry))]
                      (if (and (= res ::retry) (pos? n)) (recur (dec n)) res)))]
            (expect (= "pong" (get r "value"))))
          ;; disconnect forgets the route so it won't reconnect after that
          (page/evaluate pg "window.__spel.disconnect()")
          (expect (nil? (page/evaluate pg "sessionStorage.getItem('__spel_connect')"))))
        (finally ((:stop b)))))))
