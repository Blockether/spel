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
      (expect (re-find #"version: \"\d+\.\d+\.\d+\"" src)))))

(defdescribe bridge-sw-source-test
  "The bridge exposes the embedded service worker source (spel-sw.js)."
  (it "sw-source returns the installable spel-sw.js"
    (let [src (bridge/sw-source)]
      (expect (string? src))
      (expect (re-find #"__spel_sw" src))
      (expect (re-find #"\"fetch\"" src)))))

(defdescribe bridge-persisted-token-test
  "The bridge token is stable across restarts (persisted to ~/.spel/bridge-token)
   so a once-ejected extension / bookmarklet stays authorized against every later
   bridge, instead of the old random-per-start token that caused 'unauthorized'."

  (it "persisted-token! is stable across calls and written to disk"
    (let [tmp (java.io.File/createTempFile "spel-token" ".txt")]
      (.delete tmp)
      (try
        (with-redefs [bridge/token-path (fn [] (.getPath tmp))]
          (let [t1 (bridge/persisted-token!)
                t2 (bridge/persisted-token!)]
            (expect (string? t1))
            (expect (seq t1))
            ;; same secret on every call -> survives a bridge restart
            (expect (= t1 t2))
            ;; actually persisted, read back verbatim
            (expect (= t1 (.trim ^String (slurp tmp))))))
        (finally (.delete tmp)))))

  (it "persisted-token! honors a pre-existing token file (no rotation)"
    (let [tmp (java.io.File/createTempFile "spel-token" ".txt")]
      (try
        (spit tmp "preexisting-secret\n")
        (with-redefs [bridge/token-path (fn [] (.getPath tmp))]
          (expect (= "preexisting-secret" (bridge/persisted-token!))))
        (finally (.delete tmp))))))

(defdescribe bridge-eject-extension-test
  "`--eject-extension` bundles the embedded engine + worker with thin MV3 glue."

  (it "extension-files returns the full unpacked MV3 file set"
    (let [files (bridge/extension-files "http://127.0.0.1:8787" "/spel" "tok123")
          m     (into {} files)]
      (doseq [f ["manifest.json" "engine.js" "spel-sw.js" "bootstrap.js"
                 "content.js" "popup.html" "popup.js" "README.md"]]
        (expect (contains? m f)))
      ;; engine + worker are the embedded sources reused verbatim, not reimplemented
      (expect (= (bridge/engine-source) (get m "engine.js")))
      (expect (= (bridge/sw-source) (get m "spel-sw.js")))))

  (it "manifest.json is an MV3 manifest injecting into MAIN world with loopback perms"
    (let [manifest (bridge/extension-manifest)]
      (expect (re-find #"manifest_version" manifest))
      (expect (re-find #"<all_urls>" manifest))
      (expect (re-find #"MAIN" manifest))
      (expect (re-find #"127\.0\.0\.1" manifest))
      (expect (re-find #"web_accessible_resources" manifest))
      (expect (re-find #"engine\.js" manifest))))

  (it "bundles the real logo.svg PNG icons (16/32/48/128) and wires them into the manifest"
    (let [files    (bridge/extension-files "http://127.0.0.1:8787" "/spel" "tok123")
          m        (into {} files)
          manifest (bridge/extension-manifest)]
      (doseq [n [16 32 48 128]]
        (let [rel (str "icons/icon-" n ".png")
              b   (get m rel)]
          (expect (contains? m rel))
          (expect (bytes? b))
          ;; PNG magic header — proves it's a real rendered image, not text/emoji
          (expect (= [(unchecked-byte 0x89) (byte 0x50) (byte 0x4E) (byte 0x47)]
                    (vec (take 4 b))))
          ;; both the store listing ("icons") and the toolbar action point at it
          (expect (re-find (re-pattern (str "icon-" n "\\.png")) manifest))))
      (expect (re-find #"\"icons\"" manifest))
      (expect (re-find #"default_icon" manifest))))

  (it "content.js bakes in the bridge connect url + token and dispatches to the engine"
    (let [cs (bridge/extension-content-script "http://127.0.0.1:8787" "/spel" "tok123")]
      ;; charred escapes `/` as `\/` in the baked JSON string, so match host:port
      (expect (re-find #"DEFAULT_URL=" cs))
      (expect (re-find #"127\.0\.0\.1:8787" cs))
      (expect (re-find #"tok123" cs))
      (expect (re-find #"spel:connect" cs))))

  (it "content.js tolerates a missing token (bakes DEFAULT_TOKEN=null)"
    (let [cs (bridge/extension-content-script "http://127.0.0.1:8787" "/spel" nil)]
      (expect (re-find #"DEFAULT_TOKEN=null" cs)))))

(defdescribe bridge-logo-mark-test
  "The connect dialog / popup / harness show the real spel brand mark (theatre
   masks + play triangle from README's logo.svg), not the placeholder emoji."

  (it "logo-mark-svg renders the branded SVG scaled to the requested width"
    (let [svg (bridge/logo-mark-svg 24)]
      (expect (re-find #"<svg" svg))
      (expect (re-find #"width=\"24\"" svg))
      ;; 24 * 220/280 -> 19, preserving the logo's aspect ratio
      (expect (re-find #"height=\"19\"" svg))
      ;; the red comedy mask + green tragedy/play triangle fills from logo.svg
      (expect (re-find #"#E2574C" svg))
      (expect (re-find #"#2EAD33" svg))))

  (it "the extension popup + harness embed the brand mark, not a mask emoji"
    (let [popup   (bridge/extension-popup-html)]
      (expect (re-find #"<svg" popup))
      (expect (re-find #"#2EAD33" popup))
      ;; light theme only — never dark-mode the popup chrome
      (expect (re-find #"color-scheme.{0,12}light" popup))
      (expect (re-find #"background:#fff" popup))
      (expect (nil? (re-find #"#1e1e1e" popup)))
      ;; solid green button matching the bookmarklet banner (#1f7a3d), no gradient
      (expect (re-find #"background:#1f7a3d" popup))
      (expect (nil? (re-find #"linear-gradient" popup)))
      ;; framed white card on a soft outer background
      (expect (re-find #"class=\"card\"" popup))
      (expect (nil? (re-find #"\u2726" popup)))
      (expect (nil? (re-find #"\uD83C\uDFAD" popup))))))

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
      ;; LNA-aware: fetches the engine with targetAddressSpace to raise the
      ;; grantable local-network permission prompt, with a <script src> fallback
      (expect (re-find #"targetAddressSpace" loader))
      (expect (re-find #"fetch\(" loader))
      ;; a failed engine load surfaces a VISIBLE on-page banner, not just a
      ;; console.error — so the user sees something when the bridge is unreachable
      (expect (re-find #"could not reach" loader))
      (expect (re-find #"position:fixed" loader))
      ;; a SUCCESSFUL install/connect also surfaces a visible on-page banner so
      ;; the user knows the engine loaded — and it names the real hotkeys
      ;; (Ctrl+Shift+K / Ctrl+Shift+L), since plain Ctrl+K/L do nothing.
      (expect (re-find #"spel installed" loader))
      (expect (re-find #"Ctrl\+Shift\+K" loader))
      (expect (re-find #"Ctrl\+Shift\+L" loader))
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
          (expect (re-find #"\d+\.\d+\.\d+" (page/evaluate pg "window.__spel.version")))
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
          ;; eval-js parity: --base64 means encode the result, not decode the script.
          (let [r (bridge/route-command! (:url b)
                    {:action "evaluate" :script "document.title" :base64 true}
                    8000)]
            (expect (= true (:success r)))
            (expect (= "c3BlbCBicmlkZ2U=" (:data r))))
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
  "The engine survives a full-page navigation AND a browser restart: its connect
   route (url + token) is persisted per-origin in localStorage, so a real reload
   re-subscribes automatically instead of losing the tab."
  (around [f] (core/with-testing-browser (f)))

  (it "persists the route, clears it on disconnect, and re-subscribes on reload"
    (let [b (bridge/create-bridge :port 0 :token "tkn")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; the route is remembered in localStorage so a fresh page (even after a
          ;; browser restart) can auto-reconnect without re-entering the token
          (expect (= (:url b)
                    (page/evaluate pg "JSON.parse(localStorage.getItem('__spel_connect')).url")))
          (expect (= "tkn"
                    (page/evaluate pg "JSON.parse(localStorage.getItem('__spel_connect')).token")))
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
          (expect (nil? (page/evaluate pg "localStorage.getItem('__spel_connect')"))))
        (finally ((:stop b)))))))

(defdescribe bridge-clients-test
  "The bridge tracks connected profiles (tabs) and exposes them so the connect
     dialog can show who is already connected. A real tab announces itself via
     the `/hello` POST; `/clients` lists it, and the dialog (`connected_profiles`)
   reads that listing."
  (around [f] (core/with-testing-browser (f)))

  (it "lists a connected tab's profile after the hello POST"
    (let [b (bridge/create-bridge :port 0 :token "prf")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; the engine fires the hello POST on connect; wait for it to land
          (loop [n 40]
            (let [profiles ((:clients-list b))]
              (cond
                (some #(get % :url) profiles) nil
                (pos? n) (do (Thread/sleep 100) (recur (dec n)))
                :else (throw (ex-info "tab never announced its profile via /hello" {})))))
          ;; the server-side registry carries the tab url + transport
          (let [profiles ((:clients-list b))
                mine      (some #(when (= (:transport %) "sse") %) profiles)]
            (expect (some? mine))
            (expect (= "sse" (:transport mine)))
            (expect (string? (:url mine)))
            (expect (.contains (:url mine) (str (:port b))))))
        (finally ((:stop b))))))

  (it "collapses a reloaded tab into a single profile (no stale duplicate)"
    (let [b (bridge/create-bridge :port 0 :token "rld")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; wait for the first hello to enrich the entry with its url
          (loop [n 40]
            (when (and (pos? n) (not (some #(get % :url) ((:clients-list b)))))
              (Thread/sleep 100) (recur (dec n))))
          ;; reload the SAME tab: same sessionStorage tab-id => the bridge must
          ;; reap the stale pre-reload SSE entry, not list the tab twice.
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          (loop [n 60]
            (let [named (filter #(get % :url) ((:clients-list b)))]
              (cond
                (= 1 (count named)) nil
                (pos? n) (do (Thread/sleep 100) (recur (dec n)))
                :else (throw (ex-info "reloaded tab left a duplicate profile"
                               {:profiles ((:clients-list b))})))))
          (expect (= 1 (count (filter #(get % :url) ((:clients-list b)))))))
        (finally ((:stop b))))))

  (it "renders the connected profiles inside the connect dialog"
    (let [b (bridge/create-bridge :port 0 :token "dlg")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; wait for the hello POST to enrich the registry
          (loop [n 40]
            (let [profiles ((:clients-list b))]
              (cond
                (some #(get % :url) profiles) nil
                (pos? n) (do (Thread/sleep 100) (recur (dec n)))
                :else (throw (ex-info "tab never announced its profile via /hello" {})))))
          ;; open the connect dialog fire-and-forget (chooseServer returns a
          ;; Promise that only resolves once the dialog is dismissed, so do NOT
          ;; let page/evaluate await it — kick it off and poll the DOM instead)
          (page/evaluate pg "setTimeout(function(){ window.__spel.chooseServer(); }, 0); 'opened'")
          (loop [n 40]
            (let [raw (try (page/evaluate pg
                             "(function(){var el=document.querySelector('[data-spel-profiles-list]'); return el ? String(el.textContent||'') : ''; })()")
                        (catch Exception _ ""))
                  txt (str raw)]
              (cond
                (.contains txt "SSE") nil
                (pos? n) (do (Thread/sleep 100) (recur (dec n)))
                :else (throw (ex-info "connect dialog never listed the connected profile" {})))))
          ;; the panel lists the tab: the SSE transport badge is present, and the
          ;; "no tabs" empty-state is gone
          (let [raw (page/evaluate pg
                      "(function(){var el=document.querySelector('[data-spel-profiles-list]'); return el ? String(el.textContent||'') : ''; })()")
                txt (str raw)]
            (expect (.contains txt "SSE"))
            (expect (not (.contains txt "No tabs connected")))))
        (finally ((:stop b)))))))

(defdescribe bridge-no-sw-autoregister-test
  "The regular bridge NO LONGER auto-registers a service worker on connect — that
   responsibility moved to the browser EXTENSION (`spel bridge --eject-extension`).
   Connecting to the harness must not silently register /spel-sw.js."
  (around [f] (core/with-testing-browser (f)))

  (it "connect() does not auto-register spel-sw.js (moved to the extension)"
    (let [b (bridge/create-bridge :port 0 :token "nosw")]
      (try
        (core/with-testing-page [pg]
          (page/navigate pg (:page b))
          (wait-for-client! b 8000)
          ;; give any (unwanted) auto-registration ample time to appear, then assert
          ;; the engine stayed passive: no active worker, no registration for the origin
          (Thread/sleep 800)
          (let [active  (page/evaluate pg
                          "(function(){var s=window.__spel.handlers.sw_status(); return !!(s && s.active); })()")
                has-reg (page/evaluate pg
                          "navigator.serviceWorker.getRegistration().then(function(r){return !!r;})")]
            (expect (= false active))
            (expect (= false has-reg))))
        (finally ((:stop b)))))))
