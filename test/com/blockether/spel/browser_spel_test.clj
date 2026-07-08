(ns com.blockether.spel.browser-spel-test
  "Integration tests for browser/spel.js — the pure in-page automation engine.

   Every test injects the real spel.js into a real Chromium page (no mocks,
   no stubs) and drives it through the single `window.__spel.invoke` entry
   point, asserting against the actual live DOM."
  (:require
   [clojure.java.io :as io]
   [charred.api :as json]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server :refer [*test-server-url* with-test-server]]
   [com.blockether.spel.allure :refer [defdescribe expect it around]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private spel-js
  "The engine source, loaded once from the classpath resource that also
   ships inside the native image."
  (slurp (io/resource "com/blockether/spel/browser/spel.js")))

(def ^:private test-html
  "<html>
     <head><title>Spel JS Test</title></head>
     <body>
       <h1>Welcome</h1>
       <nav aria-label='Main'>
         <a id='home' href='#home'>Home</a>
         <a id='docs' href='#docs'>Docs</a>
       </nav>
       <form>
         <label for='name'>Full name</label>
         <input id='name' type='text' placeholder='Your name'/>
         <input id='email' type='email' data-testid='email-field'/>
         <input id='agree' type='checkbox'/>
         <select id='color'>
           <option value='r'>Red</option>
           <option value='g'>Green</option>
           <option value='b'>Blue</option>
         </select>
         <textarea id='bio'></textarea>
       </form>
       <button id='go' onclick=\"document.getElementById('out').textContent='clicked'\">Go</button>
       <button id='disabled-btn' disabled>Nope</button>
       <div id='out'></div>
       <div id='scroller' style='height:80px;overflow:auto'>
         <div style='height:600px'>tall</div>
       </div>
     </body>
   </html>")

(defn- load-spel!
  "Injects spel.js and returns its reported version."
  [pg]
  (page/evaluate pg (str spel-js "\nwindow.__spel && window.__spel.version")))

(defn- invoke
  "Runs a command through the engine and returns the full result map
   (string keys: \"action\" \"ok\" \"value\")."
  [pg cmd]
  (page/evaluate pg (str "window.__spel.invoke(" (json/write-json-str cmd) ")")))

(defn- value
  "Runs a command and returns just its :value, asserting ok."
  [pg cmd]
  (let [r (invoke pg cmd)]
    (when-not (get r "ok")
      (throw (ex-info (str "command failed: " (get r "error")) {:cmd cmd :result r})))
    (get r "value")))

(defn- setup!
  "Loads the test page + engine into a fresh page."
  [pg]
  (page/set-content! pg test-html)
  (load-spel! pg))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defdescribe engine-lifecycle-test
  "Engine installs and answers to meta commands."
  (around [f] (core/with-testing-browser (f)))

  (it "installs window.__spel and reports a version"
    (core/with-testing-page [pg]
      (expect (= "0.8.0" (setup! pg)))))

  (it "responds to ping/ready"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "pong" (value pg {:action "ping"})))
      (expect (true? (value pg {:action "ready"})))))

  (it "returns ok:false for an unknown action"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (invoke pg {:action "does-not-exist"})]
        (expect (false? (get r "ok")))
        (expect (re-find #"unknown action" (get r "error")))))))

;; ---------------------------------------------------------------------------
;; Snapshot + refs
;; ---------------------------------------------------------------------------

(defdescribe snapshot-test
  "Accessibility snapshot assigns refs and describes the tree."
  (around [f] (core/with-testing-browser (f)))

  (it "produces a tree with roles, names and refs"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [snap (value pg {:action "snapshot"})
            tree (get snap "tree")]
        (expect (string? tree))
        (expect (re-find #"heading \"Welcome\"" tree))
        (expect (re-find #"link \"Home\"" tree))
        (expect (re-find #"button \"Go\"" tree))
        (expect (re-find #"\[ref=e\d+\]" tree)))))

  (it "refs resolve back to elements for actions"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [snap (value pg {:action "snapshot"})
            refs (get snap "refs")
            go-ref (some (fn [[r m]] (when (= "Go" (get m "name")) (str "@" r))) refs)]
        (expect (some? go-ref))
        (value pg {:action "click" :selector go-ref})
        (expect (= "clicked" (value pg {:action "get_text" :selector "#out"}))))))

  (it "clear_refs removes all pw refs"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "snapshot"})
      (let [removed (value pg {:action "clear_refs"})]
        (expect (pos? removed))
        (expect (zero? (page/evaluate pg "document.querySelectorAll('[data-pw-ref]').length")))))))

;; ---------------------------------------------------------------------------
;; Interaction
;; ---------------------------------------------------------------------------

(defdescribe interaction-test
  "Click / fill / type / press / check / select against the live DOM."
  (around [f] (core/with-testing-browser (f)))

  (it "click triggers the button handler"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "click" :selector "#go"})
      (expect (= "clicked" (value pg {:action "get_text" :selector "#out"})))))

  (it "click resolves text= selectors"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "click" :selector "text=Go"})
      (expect (= "clicked" (value pg {:action "get_text" :selector "#out"})))))

  (it "fill sets an input value and fires events"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "fill" :selector "#name" :value "Ada Lovelace"})
      (expect (= "Ada Lovelace" (value pg {:action "get_value" :selector "#name"})))))

  (it "clear empties an input"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "fill" :selector "#name" :value "temp"})
      (value pg {:action "clear" :selector "#name"})
      (expect (= "" (value pg {:action "get_value" :selector "#name"})))))

  (it "type appends character by character"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "type" :selector "#bio" :text "hi"})
      (expect (= "hi" (value pg {:action "get_value" :selector "#bio"})))))

  (it "press Backspace edits the focused input"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "fill" :selector "#name" :value "abc"})
      (value pg {:action "focus" :selector "#name"})
      (value pg {:action "press" :selector "#name" :key "Backspace"})
      (expect (= "ab" (value pg {:action "get_value" :selector "#name"})))))

  (it "check and uncheck toggle a checkbox"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "check" :selector "#agree"})
      (expect (true? (value pg {:action "is_checked" :selector "#agree"})))
      (value pg {:action "uncheck" :selector "#agree"})
      (expect (false? (value pg {:action "is_checked" :selector "#agree"})))))

  (it "select picks an option by value"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [selected (value pg {:action "select" :selector "#color" :values ["b"]})]
        (expect (= ["b"] selected))
        (expect (= "b" (value pg {:action "get_value" :selector "#color"}))))))

  (it "scroll moves a scrollable container"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "scroll" :selector "#scroller" :direction "down" :amount 200})
      (expect (pos? (page/evaluate pg "document.getElementById('scroller').scrollTop"))))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defdescribe query-test
  "Read-only inspection commands."
  (around [f] (core/with-testing-browser (f)))

  (it "get_text / get_attribute / count"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "Welcome" (value pg {:action "get_text" :selector "h1"})))
      (expect (= "#home" (value pg {:action "get_attribute" :selector "#home" :name "href"})))
      (expect (= 2 (value pg {:action "count" :selector "nav a"})))))

  (it "is_visible / is_enabled"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (true? (value pg {:action "is_visible" :selector "#go"})))
      (expect (true? (value pg {:action "is_enabled" :selector "#go"})))
      (expect (false? (value pg {:action "is_enabled" :selector "#disabled-btn"})))))

  (it "bounding_box returns numeric geometry"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [box (value pg {:action "bounding_box" :selector "#go"})]
        (expect (number? (get box "width")))
        (expect (pos? (get box "height"))))))

  (it "get_styles reads computed style"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [st (value pg {:action "get_styles" :selector "#go" :props ["display"]})]
        (expect (string? (get st "display"))))))

  (it "title returns the document title"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "Spel JS Test" (value pg {:action "title"}))))))

;; ---------------------------------------------------------------------------
;; getBy* — the `find` family
;; ---------------------------------------------------------------------------

(defdescribe find-test
  "Playwright getBy* mapped onto the `find` action."
  (around [f] (core/with-testing-browser (f)))

  (it "find by role+name returns a usable ref"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (value pg {:action "find" :by "role" :value "button" :name "Go"})]
        (expect (= 1 (get r "count")))
        (value pg {:action "click" :selector (get r "ref")})
        (expect (= "clicked" (value pg {:action "get_text" :selector "#out"}))))))

  (it "find by testid"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (value pg {:action "find" :by "testid" :value "email-field"})]
        (expect (= 1 (get r "count")))
        (value pg {:action "fill" :selector (get r "ref") :value "a@b.c"})
        (expect (= "a@b.c" (value pg {:action "get_value" :selector "#email"}))))))

  (it "find by placeholder"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (value pg {:action "find" :by "placeholder" :value "Your name"})]
        (expect (= 1 (get r "count"))))))

  (it "find by label"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (value pg {:action "find" :by "label" :value "Full name"})]
        (expect (pos? (get r "count")))))))

;; ---------------------------------------------------------------------------
;; Storage + evaluate
;; ---------------------------------------------------------------------------

(defdescribe misc-test
  "Storage, evaluate, and page meta."
  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (it "storage set/get/clear round-trips"
    (core/with-testing-page [pg]
      ;; localStorage needs a real (non-opaque) origin, so navigate to the
      ;; local HTTP test server before setting content.
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "storage_set" :key "tok" :value "xyz"})
      (expect (= "xyz" (value pg {:action "storage_get" :key "tok"})))
      (value pg {:action "storage_clear"})
      (expect (nil? (value pg {:action "storage_get" :key "tok"})))))

  (it "evaluate runs arbitrary JS"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= 4 (value pg {:action "evaluate" :script "2 + 2"}))))))

;; ---------------------------------------------------------------------------
;; Overlay picker
;; ---------------------------------------------------------------------------

(defdescribe picker-test
  "Interactive overlay element picker."
  (around [f] (core/with-testing-browser (f)))

  (it "pick activates and pick_stop deactivates"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (true? (value pg {:action "pick"})))
      (expect (true? (page/evaluate pg "window.__spel.picker.active")))
      (expect (true? (value pg {:action "pick_stop"})))
      (expect (false? (page/evaluate pg "window.__spel.picker.active")))))

  (it "the overlay chrome is spel-branded and cleans up on stop"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "pick"})
      ;; Hover over the Go button to render the highlight box + label chip.
      (page/evaluate pg
        (str "(() => {"
          "  const el = document.getElementById('go');"
          "  const r = el.getBoundingClientRect();"
          "  const x = r.left + r.width/2, y = r.top + r.height/2;"
          "  document.dispatchEvent(new MouseEvent('mousemove', {bubbles:true, clientX:x, clientY:y}));"
          "})()"))
      ;; HUD pill is shown while picking.
      (expect (= "flex" (page/evaluate pg "window.__spel.picker.hud.style.display")))
      ;; Highlight box wears the tragedy-green border, not the old blue.
      (expect (re-find #"46, ?173, ?51"
                (page/evaluate pg "window.__spel.picker.box.style.borderColor")))
      (expect (= "block" (page/evaluate pg "window.__spel.picker.box.style.display")))
      ;; Label chip is visible and carries the element's role.
      (expect (= "block" (page/evaluate pg "window.__spel.picker.label.style.display")))
      (expect (re-find #"button" (page/evaluate pg "window.__spel.picker.label.textContent")))
      ;; The breathing-glow keyframes were injected once.
      (expect (true? (page/evaluate pg "!!window.__spel.picker.styleEl")))
      (value pg {:action "pick_stop"})
      (expect (= "none" (page/evaluate pg "window.__spel.picker.hud.style.display")))
      (expect (= "none" (page/evaluate pg "window.__spel.picker.label.style.display")))
      (expect (= "none" (page/evaluate pg "window.__spel.picker.box.style.display")))))

  (it "clicking during a pick records the selected element"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "pick"})
      ;; Simulate a real mouse click over the Go button's center.
      (page/evaluate pg
        (str "(() => {"
          "  const el = document.getElementById('go');"
          "  const r = el.getBoundingClientRect();"
          "  const x = r.left + r.width/2, y = r.top + r.height/2;"
          "  el.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, clientX:x, clientY:y}));"
          "})()"))
      (let [picked (value pg {:action "picked"})]
        (expect (some? picked))
        (expect (= "button" (get picked "role")))
        (expect (= "Go" (get picked "name")))
        (expect (re-find #"^@e" (get picked "selector"))))))

  (it "configure changes the hotkey"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [cfg (value pg {:action "configure" :hotkey {:ctrlKey true :shiftKey true :key "P"}})]
        (expect (= "P" (get-in cfg ["hotkey" "key"]))))))

  (it "configure sets the server URL and a server hotkey"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [cfg (value pg {:action "configure"
                           :server "ws://127.0.0.1:9999/spel"
                           :serverHotkey {:ctrlKey true :shiftKey true :key "J"}})]
        (expect (= "ws://127.0.0.1:9999/spel" (get cfg "server")))
        (expect (= "J" (get-in cfg ["serverHotkey" "key"]))))
      (expect (= "ws://127.0.0.1:9999/spel" (value pg {:action "get_server"})))
      (expect (= "http://host:1/x" (value pg {:action "set_server" :server "http://host:1/x"}))))))

;; ---------------------------------------------------------------------------
;; Checks / properties (Playwright parity)
;; ---------------------------------------------------------------------------

(defdescribe checks-test
  "is_* / property / aria queries against the live DOM."
  (around [f] (core/with-testing-browser (f)))

  (it "is_editable / is_disabled / is_hidden / is_focused"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (true? (value pg {:action "is_editable" :selector "#name"})))
      (expect (true? (value pg {:action "is_disabled" :selector "#disabled-btn"})))
      (expect (false? (value pg {:action "is_disabled" :selector "#go"})))
      (expect (false? (value pg {:action "is_editable" :selector "#go"})))
      (value pg {:action "focus" :selector "#name"})
      (expect (true? (value pg {:action "is_focused" :selector "#name"})))))

  (it "text_content / inner_text / inner_html / tag_name / get_property"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "Welcome" (value pg {:action "text_content" :selector "h1"})))
      (expect (= "Welcome" (value pg {:action "inner_text" :selector "h1"})))
      (expect (re-find #"Home" (value pg {:action "inner_html" :selector "nav"})))
      (expect (= "input" (value pg {:action "tag_name" :selector "#name"})))
      (value pg {:action "fill" :selector "#name" :value "Ada"})
      (expect (= "Ada" (value pg {:action "input_value" :selector "#name"})))
      (expect (= "Ada" (value pg {:action "get_property" :selector "#name" :name "value"})))))

  (it "get_role / get_accessible_name / get_aria / aria_snapshot"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "button" (value pg {:action "get_role" :selector "#go"})))
      (expect (= "Go" (value pg {:action "get_accessible_name" :selector "#go"})))
      (let [aria (value pg {:action "get_aria" :selector "nav"})]
        (expect (= "Main" (get aria "aria-label"))))
      (let [snap (value pg {:action "aria_snapshot"})]
        (expect (re-find #"button \"Go\"" (get snap "tree")))))))

;; ---------------------------------------------------------------------------
;; Overflow / clipping / geometry
;; ---------------------------------------------------------------------------

(defdescribe overflow-test
  "Overflow + clipping detection, the stuff CDP-based tooling gives us."
  (around [f] (core/with-testing-browser (f)))

  (it "is_overflowing detects a scroll container"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (true? (value pg {:action "is_overflowing" :selector "#scroller"})))
      (expect (false? (value pg {:action "is_overflowing" :selector "h1"})))
      (let [info (value pg {:action "overflow_info" :selector "#scroller"})]
        (expect (true? (get info "overflowsY")))
        (expect (> (get info "scrollHeight") (get info "clientHeight"))))))

  (it "is_clipped detects an element overflowing its scroll parent"
    (core/with-testing-page [pg]
      (setup! pg)
      ;; The 600px-tall child inside the 80px scroller is clipped.
      (expect (true? (value pg {:action "is_clipped" :selector "#scroller > div"})))
      (expect (false? (value pg {:action "is_clipped" :selector "h1"})))))

  (it "scroll_position / viewport_size"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [vp (value pg {:action "viewport_size"})]
        (expect (pos? (get vp "width")))
        (expect (pos? (get vp "height"))))
      (value pg {:action "scroll" :selector "#scroller" :direction "down" :amount 100})
      (let [pos (value pg {:action "scroll_position" :selector "#scroller"})]
        (expect (pos? (get pos "y")))))))

;; ---------------------------------------------------------------------------
;; Waiting
;; ---------------------------------------------------------------------------

(defdescribe wait-for-test
  "wait_for polls for element state and resolves/times out."
  (around [f] (core/with-testing-browser (f)))

  (it "resolves immediately for an already-visible element"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (value pg {:action "wait_for" :selector "#go" :state "visible"})]
        (expect (= "visible" (get r "matched"))))))

  (it "waits for an element that appears later"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg
        (str "setTimeout(() => {"
          "  const d = document.createElement('div');"
          "  d.id = 'late'; d.textContent = 'here';"
          "  document.body.appendChild(d);"
          "}, 150)"))
      (let [r (value pg {:action "wait_for" :selector "#late" :state "visible" :timeout 3000})]
        (expect (= "visible" (get r "matched"))))))

  (it "times out and reports ok:false when the element never appears"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (invoke pg {:action "wait_for" :selector "#never" :state "visible" :timeout 200})]
        (expect (false? (get r "ok")))
        (expect (re-find #"timeout" (get r "error")))))))

;; ---------------------------------------------------------------------------
;; Network capture (in-page, no CDP)
;; ---------------------------------------------------------------------------

(defdescribe network-test
  "fetch / XMLHttpRequest are wrapped and surfaced via network_* commands."
  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (it "captures a fetch() with method, status and response body"
    (core/with-testing-page [pg]
      ;; Real HTTP origin so fetch is same-origin and bodies are readable.
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      ;; Drive a real request and await it before asserting.
      (page/evaluate pg (str "fetch(" (json/write-json-str (str *test-server-url* "/health"))
                          ").then(r => r.text())"))
      (let [entries (get (value pg {:action "network_list"}) "entries")
            hit (some (fn [e] (when (= "fetch" (get e "resourceType")) e)) entries)]
        (expect (some? hit))
        (expect (= 200 (get hit "status")))
        (expect (= "GET" (get hit "method")))
        (expect (true? (get hit "ok")))
        (expect (re-find #"^@n\d+" (get hit "ref")))
        ;; Full entry carries the response body.
        (let [full (value pg {:action "network_get" :ref (get hit "ref")})]
          (expect (string? (get full "responseBody")))))))

  (it "filters by method and reports failed requests"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      (page/evaluate pg (str "fetch(" (json/write-json-str (str *test-server-url* "/nope-404"))
                          ").then(r => r.text()).catch(() => {})"))
      (let [entries (get (value pg {:action "network_list" :method "GET"}) "entries")]
        (expect (pos? (count entries)))
        (expect (every? #(= "GET" (get % "method")) entries)))
      (let [failed (get (value pg {:action "network_list" :failed true}) "entries")]
        (expect (some #(>= (long (get % "status")) 400) failed)))))

  (it "captures an XMLHttpRequest"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      (page/evaluate pg
        (str "new Promise(res => {"
          "  const x = new XMLHttpRequest();"
          "  x.open('GET', " (json/write-json-str (str *test-server-url* "/health")) ");"
          "  x.addEventListener('loadend', () => res(true));"
          "  x.send();"
          "})"))
      (let [entries (get (value pg {:action "network_list" :type "xhr"}) "entries")]
        (expect (pos? (count entries)))
        (expect (= 200 (get (first entries) "status"))))))

  (it "network_clear empties the window"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (page/evaluate pg (str "fetch(" (json/write-json-str *test-server-url*) ").then(r => r.text())"))
      (value pg {:action "network_clear"})
      (expect (empty? (get (value pg {:action "network_list"}) "entries"))))))

;; ---------------------------------------------------------------------------
;; Dialogs, console, waits, uploads, events, frames (pure-JS Playwright parity)
;; ---------------------------------------------------------------------------

(defdescribe dialogs-test
  "alert/confirm/prompt are intercepted, follow a policy and are recorded."
  (around [f] (core/with-testing-browser (f)))

  (it "accepts confirm and records the dialog by default"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "dialog_handler" :policy "accept"})
      (expect (true? (page/evaluate pg "window.confirm('sure?')")))
      (let [entries (get (value pg {:action "dialogs"}) "entries")
            d (last entries)]
        (expect (= "confirm" (get d "type")))
        (expect (= "sure?" (get d "message")))
        (expect (true? (get d "accepted"))))))

  (it "dismisses confirm/prompt under the dismiss policy"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "dialog_handler" :policy "dismiss"})
      (expect (false? (page/evaluate pg "window.confirm('no?')")))
      (expect (nil? (page/evaluate pg "window.prompt('name?')")))))

  (it "feeds promptText back to prompt()"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "dialog_handler" :policy "accept" :promptText "Ada"})
      (expect (= "Ada" (page/evaluate pg "window.prompt('name?')"))))))

(defdescribe console-test
  "console.* and page errors are captured after console_capture."
  (around [f] (core/with-testing-browser (f)))

  (it "captures console messages and filters by level"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "console_capture"})
      (page/evaluate pg "console.log('hello'); console.error('boom'); true")
      (let [all (get (value pg {:action "console_list"}) "entries")]
        (expect (some #(= "hello" (get % "text")) all))
        (expect (some #(= "error" (get % "type")) all)))
      (let [errs (get (value pg {:action "console_list" :level "error"}) "entries")]
        (expect (every? #(= "error" (get % "type")) errs))
        (expect (some #(= "boom" (get % "text")) errs)))))

  (it "captures an uncaught page error"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "console_capture"})
      (page/evaluate pg "setTimeout(() => { throw new Error('kaboom'); }, 0); true")
      (value pg {:action "wait_for_timeout" :timeout 100})
      (let [errs (get (value pg {:action "console_list" :level "pageerror"}) "entries")]
        (expect (some #(re-find #"kaboom" (get % "text")) errs))))))

(defdescribe waits-test
  "wait_for_function / _load_state / _timeout resolve on real conditions."
  (around [f] (core/with-testing-browser (f)))

  (it "wait_for_function resolves when the predicate turns truthy"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg "setTimeout(() => { window.__flag = 42; }, 120); true")
      (expect (= 42 (long (value pg {:action "wait_for_function"
                                     :script "window.__flag" :timeout 3000}))))))

  (it "wait_for_function times out to ok:false"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [r (invoke pg {:action "wait_for_function" :script "window.__never" :timeout 150})]
        (expect (false? (get r "ok")))
        (expect (re-find #"timeout" (get r "error"))))))

  (it "wait_for_load_state resolves for an already-complete document"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (= "complete" (value pg {:action "wait_for_load_state" :state "load"})))))

  (it "wait_for_timeout just delays"
    (core/with-testing-page [pg]
      (setup! pg)
      (expect (true? (value pg {:action "wait_for_timeout" :timeout 50}))))))

(defdescribe upload-events-test
  "set_input_files, dispatch_event and tap drive real DOM state."
  (around [f] (core/with-testing-browser (f)))

  (it "upload sets files on a file input"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg
        (str "const i = document.createElement('input'); i.type='file'; i.id='up';"
          "document.body.appendChild(i); true"))
      (let [r (value pg {:action "upload" :selector "#up"
                         :files [{:name "a.txt" :content "hello" :mimeType "text/plain"}]})]
        (expect (= ["a.txt"] (get r "files")))
        (expect (= 1 (long (page/evaluate pg "document.getElementById('up').files.length")))))))

  (it "dispatch_event fires an arbitrary event"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg "document.getElementById('go').addEventListener('customping', () => { window.__ping = 1; }); true")
      (value pg {:action "dispatch_event" :selector "#go" :type "customping"})
      (expect (= 1 (long (page/evaluate pg "window.__ping"))))))

  (it "tap clicks through pointer events"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "tap" :selector "#go"})
      (expect (= "clicked" (page/evaluate pg "document.getElementById('out').textContent"))))))

(defdescribe frames-test
  "same-origin iframe drill-down via frame= selectors."
  (around [f] (core/with-testing-browser (f)))

  (it "lists frames and acts inside a same-origin iframe"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg
        (str "const fr = document.createElement('iframe'); fr.id='fr';"
          "document.body.appendChild(fr);"
          "const d = fr.contentDocument;"
          "d.body.innerHTML = '<button id=\"inner\">Inner</button>';"
          "d.getElementById('inner').addEventListener('click', function () {"
          "  d.body.setAttribute('data-hit', 'yes'); });"
          "true"))
      (let [frames (get (value pg {:action "frames"}) "frames")]
        (expect (pos? (count frames)))
        (expect (true? (get (first frames) "sameOrigin"))))
      (expect (= "button" (value pg {:action "tag_name" :selector "frame=#fr >> #inner"})))
      (value pg {:action "click" :selector "frame=#fr >> #inner"})
      (expect (= "yes" (page/evaluate pg "document.getElementById('fr').contentDocument.body.getAttribute('data-hit')"))))))

;; ---------------------------------------------------------------------------
;; Cookies + request routing (server-backed, real origin)
;; ---------------------------------------------------------------------------

(defdescribe cookies-routing-test
  "cookies + page.route()-style fetch mocking against a real HTTP origin."
  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (it "sets, reads and clears cookies"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "set_cookie" :name "tok" :value "abc123"})
      (let [jar (value pg {:action "cookies"})]
        (expect (some #(and (= "tok" (get % "name")) (= "abc123" (get % "value"))) jar)))
      (expect (= [{"name" "tok" "value" "abc123"}]
                (value pg {:action "cookies" :name "tok"})))
      (value pg {:action "clear_cookies"})
      (expect (empty? (value pg {:action "cookies" :name "tok"})))))

  (it "route fulfills a fetch without hitting the network"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      (value pg {:action "route" :url "*/mock-api" :status 202 :body "{\"ok\":true}"})
      (page/evaluate pg "fetch('/mock-api').then(r => r.text()).then(t => { window.__m = t; })")
      (value pg {:action "wait_for_function" :script "window.__m !== undefined" :timeout 3000})
      (expect (= "{\"ok\":true}" (page/evaluate pg "window.__m")))
      (let [entries (get (value pg {:action "network_list"}) "entries")
            mock (some (fn [e] (when (get e "mocked") e)) entries)]
        (expect (some? mock))
        (expect (= 202 (long (get mock "status")))))))

  (it "route abort rejects the fetch"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "route" :url "*/blocked" :abort true})
      (page/evaluate pg
        (str "fetch('/blocked').then(() => { window.__b = 'ok'; })"
          ".catch(() => { window.__b = 'aborted'; })"))
      (value pg {:action "wait_for_function" :script "window.__b !== undefined" :timeout 3000})
      (expect (= "aborted" (page/evaluate pg "window.__b")))))

  (it "wait_for_response resolves on a matching later response"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      (page/evaluate pg (str "setTimeout(() => fetch("
                          (json/write-json-str (str *test-server-url* "/health"))
                          ").then(r => r.text()), 120)"))
      (let [r (value pg {:action "wait_for_response" :url "/health" :timeout 3000})]
        (expect (= 200 (long (get r "status"))))
        (expect (re-find #"/health" (get r "url")))))))

;; ---------------------------------------------------------------------------
;; Locator composition — `>>` chains + nth/first/last/has-text/visible filters
;; ---------------------------------------------------------------------------

(defdescribe composition-test
  "Playwright-style `>>` locator chaining and index/text/visibility filters."
  (around [f] (core/with-testing-browser (f)))

  (it "resolves nth / first / last / has-text / visible over a matched set"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg
        (str "document.body.insertAdjacentHTML('beforeend',"
          "'<ul id=\"lst\"><li>Alpha</li><li>Beta</li><li>Gamma</li>"
          "<li style=\"display:none\">Hidden</li></ul>'); true"))
      (expect (= 4 (value pg {:action "count" :selector "#lst li"})))
      (expect (= "Beta" (value pg {:action "get_text" :selector "#lst li >> nth=1"})))
      (expect (= "Alpha" (value pg {:action "get_text" :selector "#lst li >> first"})))
      (expect (= "Hidden" (value pg {:action "get_text" :selector "#lst li >> last"})))
      (expect (= "Gamma"
                (value pg {:action "get_text" :selector "#lst li >> has-text=Gamma"})))
      (expect (= 3 (value pg {:action "count" :selector "#lst li >> visible"})))))

  (it "chains a descendant selector across every match in the set"
    (core/with-testing-page [pg]
      (setup! pg)
      (page/evaluate pg
        (str "document.body.insertAdjacentHTML('beforeend',"
          "'<div class=\"card\"><button>Buy</button></div>"
          "<div class=\"card\"><button>Sell</button></div>'); true"))
      (expect (= 2 (value pg {:action "count" :selector ".card >> button"})))
      (expect (= "Sell"
                (value pg {:action "get_text" :selector ".card >> button >> nth=1"})))
      (expect (= "Buy"
                (value pg {:action "get_text"
                           :selector ".card >> button >> has-text=Buy"}))))))

;; ---------------------------------------------------------------------------
;; Cross-validation additions — capabilities once documented as CDP-only that
;; are in fact doable in pure in-page JS: HAR export, JS-level environment
;; emulation, and a DOM screenshot.
;; ---------------------------------------------------------------------------

(defdescribe har-export-test
  "network_har serializes the in-page capture as HAR 1.2."
  (around [f] (core/with-testing-browser ((:around with-test-server) f)))

  (it "emits a HAR log with a real captured entry"
    (core/with-testing-page [pg]
      (page/navigate pg *test-server-url*)
      (setup! pg)
      (value pg {:action "network_clear"})
      (page/evaluate pg (str "fetch(" (json/write-json-str (str *test-server-url* "/health"))
                          ").then(r => r.text())"))
      (let [har (get (value pg {:action "network_har"}) "log")
            entries (get har "entries")
            entry (some (fn [e] (when (re-find #"/health" (get e "_ref" "")) e))
                    entries)
            entry (or entry (first entries))]
        (expect (= "1.2" (get har "version")))
        (expect (= "spel-bridge" (get-in har ["creator" "name"])))
        (expect (pos? (count entries)))
        (expect (= "GET" (get-in entry ["request" "method"])))
        (expect (re-find #"/health" (get-in entry ["request" "url"])))
        (expect (= 200 (long (get-in entry ["response" "status"]))))
        (expect (sequential? (get-in entry ["request" "headers"])))
        (expect (vector? (get-in entry ["response" "headers"])))
        (expect (string? (get-in entry ["startedDateTime"])))))))

(defdescribe emulate-test
  "emulate overrides what page JS reads (no CDP): geolocation / timezone /
   locale / device / prefers-color-scheme."
  (around [f] (core/with-testing-browser (f)))

  (it "overrides geolocation, locale and device metrics"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [applied (value pg {:action "emulate"
                               :geolocation {:latitude 51.5 :longitude -0.12}
                               :locale "pl-PL"
                               :languages ["pl-PL" "en-US"]
                               :userAgent "SpelBot/1.0"
                               :hardwareConcurrency 12
                               :colorScheme "dark"})]
        (expect (= "pl-PL" (get applied "locale")))
        (expect (= "SpelBot/1.0" (get applied "userAgent"))))
      (expect (= "pl-PL" (page/evaluate pg "navigator.language")))
      (expect (= "SpelBot/1.0" (page/evaluate pg "navigator.userAgent")))
      (expect (= 12 (long (page/evaluate pg "navigator.hardwareConcurrency"))))
      (expect (true? (page/evaluate pg "matchMedia('(prefers-color-scheme: dark)').matches")))
      (expect (= 51.5 (page/evaluate pg
                        (str "new Promise(res => navigator.geolocation"
                          ".getCurrentPosition(p => res(p.coords.latitude)))"))))))

  (it "overrides the reported timezone for Intl"
    (core/with-testing-page [pg]
      (setup! pg)
      (value pg {:action "emulate" :timezone "Asia/Tokyo"})
      (expect (= "Asia/Tokyo"
                (page/evaluate pg
                  "new Intl.DateTimeFormat().resolvedOptions().timeZone"))))))

(defdescribe screenshot-test
  "screenshot serializes the DOM into an SVG (and rasterizes to PNG when the
   canvas is not tainted) — pure JS, no CDP."
  (around [f] (core/with-testing-browser (f)))

  (it "returns an SVG data URL sized to the element"
    (core/with-testing-page [pg]
      (setup! pg)
      (let [shot (value pg {:action "screenshot" :selector "#out"})]
        (expect (re-find #"^data:image/svg\+xml" (get shot "svg")))
        (expect (pos? (long (get shot "width"))))
        (expect (pos? (long (get shot "height"))))
        ;; PNG rasterization succeeds on a clean (untainted) canvas.
        (when-let [png (get shot "png")]
          (expect (re-find #"^data:image/png" png)))))))
