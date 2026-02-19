(ns com.blockether.spel.cli-test
  "Tests for the CLI arg parser.

   Unit tests for parse-args covering all supported CLI commands,
   global flags, and edge cases."
  (:require
   [com.blockether.spel.cli :as sut]
   [com.blockether.spel.native] ;; for #' access to parse-global-flags
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Helper
;; =============================================================================

(defn- cmd
  "Shorthand: parse args and return just the :command map."
  [args]
  (:command (sut/parse-args args)))

(defn- flags
  "Shorthand: parse args and return just the :flags map."
  [args]
  (:flags (sut/parse-args args)))

;; =============================================================================
;; Navigation Commands
;; =============================================================================

(defdescribe navigation-test
  "Tests for navigation commands"

  (describe "open command"
    (it "parses open with URL"
      (let [c (cmd ["open" "https://example.com"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.com" (:url c)))))

    (it "parses goto as alias for open"
      (let [c (cmd ["goto" "https://example.com"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.com" (:url c)))))

    (it "auto-prefixes https for bare domains"
      (let [c (cmd ["open" "example.com"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.com" (:url c)))))

    (it "preserves file:// protocol"
      (let [c (cmd ["open" "file:///tmp/page.html"])]
        (expect (= "navigate" (:action c)))
        (expect (= "file:///tmp/page.html" (:url c)))))

    (it "preserves data: protocol"
      (let [c (cmd ["open" "data:text/html,<h1>hi</h1>"])]
        (expect (= "navigate" (:action c)))
        (expect (= "data:text/html,<h1>hi</h1>" (:url c)))))

    (it "parses open with no URL"
      (let [c (cmd ["open"])]
        (expect (= "navigate" (:action c)))
        (expect (nil? (:url c))))))

  (describe "back/forward/reload"
    (it "parses back"
      (expect (= {:action "back"} (cmd ["back"]))))

    (it "parses forward"
      (expect (= {:action "forward"} (cmd ["forward"]))))

    (it "parses reload"
      (expect (= {:action "reload"} (cmd ["reload"]))))))

;; =============================================================================
;; Snapshot
;; =============================================================================

(defdescribe snapshot-test
  "Tests for snapshot command"

  (describe "basic snapshot"
    (it "parses snapshot without flags"
      (expect (= "snapshot" (:action (cmd ["snapshot"])))))

    (it "parses snapshot -i"
      (let [c (cmd ["snapshot" "-i"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:interactive c)))))

    (it "parses snapshot --interactive"
      (let [c (cmd ["snapshot" "--interactive"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:interactive c)))))

    (it "parses snapshot -c compact flag"
      (let [c (cmd ["snapshot" "-c"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:compact c)))))

    (it "parses snapshot --compact flag"
      (let [c (cmd ["snapshot" "--compact"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:compact c)))))

    (it "parses snapshot -d depth"
      (let [c (cmd ["snapshot" "-d" "3"])]
        (expect (= "snapshot" (:action c)))
        (expect (= 3 (:depth c)))))

    (it "parses snapshot --depth"
      (let [c (cmd ["snapshot" "--depth" "5"])]
        (expect (= "snapshot" (:action c)))
        (expect (= 5 (:depth c)))))

    (it "parses snapshot -s selector"
      (let [c (cmd ["snapshot" "-s" "#main"])]
        (expect (= "snapshot" (:action c)))
        (expect (= "#main" (:selector c)))))

    (it "parses snapshot --selector"
      (let [c (cmd ["snapshot" "--selector" ".content"])]
        (expect (= "snapshot" (:action c)))
        (expect (= ".content" (:selector c)))))

    (it "parses snapshot with combined flags"
      (let [c (cmd ["snapshot" "-i" "-c" "-d" "3" "-s" "#main"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:interactive c)))
        (expect (true? (:compact c)))
        (expect (= 3 (:depth c)))
        (expect (= "#main" (:selector c)))))))

;; =============================================================================
;; Click / Input
;; =============================================================================

(defdescribe click-input-test
  "Tests for click and input commands"

  (describe "click"
    (it "parses click with ref"
      (let [c (cmd ["click" "@e1"])]
        (expect (= "click" (:action c)))
        (expect (= "@e1" (:selector c)))))

    (it "parses dblclick"
      (let [c (cmd ["dblclick" "@e5"])]
        (expect (= "dblclick" (:action c)))
        (expect (= "@e5" (:selector c))))))

  (describe "fill"
    (it "parses fill with ref and value"
      (let [c (cmd ["fill" "@e2" "hello"])]
        (expect (= "fill" (:action c)))
        (expect (= "@e2" (:selector c)))
        (expect (= "hello" (:value c)))))

    (it "joins multi-word values"
      (let [c (cmd ["fill" "@e2" "hello" "world"])]
        (expect (= "hello world" (:value c))))))

  (describe "type"
    (it "parses type with ref and text"
      (let [c (cmd ["type" "@e3" "world"])]
        (expect (= "type" (:action c)))
        (expect (= "@e3" (:selector c)))
        (expect (= "world" (:text c))))))

  (describe "clear"
    (it "parses clear with ref"
      (let [c (cmd ["clear" "@e4"])]
        (expect (= "clear" (:action c)))
        (expect (= "@e4" (:selector c)))))))

;; =============================================================================
;; Keyboard
;; =============================================================================

(defdescribe keyboard-test
  "Tests for keyboard commands"

  (describe "press without selector"
    (it "parses press key"
      (let [c (cmd ["press" "Enter"])]
        (expect (= "press" (:action c)))
        (expect (= "Enter" (:key c)))
        (expect (nil? (:selector c))))))

  (describe "press with selector"
    (it "parses press on element"
      (let [c (cmd ["press" "@e1" "Tab"])]
        (expect (= "press" (:action c)))
        (expect (= "@e1" (:selector c)))
        (expect (= "Tab" (:key c)))))))

;; =============================================================================
;; Mouse
;; =============================================================================

(defdescribe mouse-test
  "Tests for mouse commands"

  (describe "hover"
    (it "parses hover with ref"
      (let [c (cmd ["hover" "@e1"])]
        (expect (= "hover" (:action c)))
        (expect (= "@e1" (:selector c)))))))

;; =============================================================================
;; Checkbox / Select / Focus
;; =============================================================================

(defdescribe form-controls-test
  "Tests for form control commands"

  (describe "check/uncheck"
    (it "parses check"
      (expect (= "check" (:action (cmd ["check" "@e1"])))))

    (it "parses uncheck"
      (expect (= "uncheck" (:action (cmd ["uncheck" "@e1"]))))))

  (describe "select"
    (it "parses select with values"
      (let [c (cmd ["select" "@e1" "opt1" "opt2"])]
        (expect (= "select" (:action c)))
        (expect (= "@e1" (:selector c)))
        (expect (= ["opt1" "opt2"] (:values c))))))

  (describe "focus"
    (it "parses focus"
      (expect (= "focus" (:action (cmd ["focus" "@e1"])))))))

;; =============================================================================
;; Screenshot / PDF
;; =============================================================================

(defdescribe screenshot-pdf-test
  "Tests for screenshot and PDF commands"

  (describe "screenshot"
    (it "parses screenshot with path"
      (let [c (cmd ["screenshot" "shot.png"])]
        (expect (= "screenshot" (:action c)))
        (expect (= "shot.png" (:path c)))))

    (it "parses screenshot without path"
      (let [c (cmd ["screenshot"])]
        (expect (= "screenshot" (:action c)))
        (expect (nil? (:path c)))))

    (it "parses screenshot with full-page flag"
      (let [c (cmd ["screenshot" "shot.png" "-f"])]
        (expect (= "screenshot" (:action c)))
        (expect (= "shot.png" (:path c)))
        (expect (true? (:fullPage c))))))

  (describe "pdf"
    (it "parses pdf with path"
      (let [c (cmd ["pdf" "page.pdf"])]
        (expect (= "pdf" (:action c)))
        (expect (= "page.pdf" (:path c)))))

    (it "defaults pdf path"
      (let [c (cmd ["pdf"])]
        (expect (= "pdf" (:action c)))
        (expect (= "page.pdf" (:path c)))))))

;; =============================================================================
;; Annotate
;; =============================================================================

(defdescribe annotate-test
  "Tests for annotate and unannotate commands"

  (describe "annotate"
    (it "parses annotate without args"
      (let [c (cmd ["annotate"])]
        (expect (= "annotate" (:action c)))))

    (it "parses annotate --no-badges"
      (let [c (cmd ["annotate" "--no-badges"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-badges c)))))

    (it "parses annotate --no-dimensions"
      (let [c (cmd ["annotate" "--no-dimensions"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-dimensions c)))))

    (it "parses annotate --no-dims"
      (let [c (cmd ["annotate" "--no-dims"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-dimensions c)))))

    (it "parses annotate --no-boxes"
      (let [c (cmd ["annotate" "--no-boxes"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-boxes c)))))

    (it "parses annotate with all options disabled"
      (let [c (cmd ["annotate" "--no-badges" "--no-dims" "--no-boxes"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-badges c)))
        (expect (false? (:show-dimensions c)))
        (expect (false? (:show-boxes c)))))

    (it "parses annotate --full"
      (let [c (cmd ["annotate" "--full"])]
        (expect (= "annotate" (:action c)))
        (expect (true? (:full-page c)))))

    (it "parses annotate -f"
      (let [c (cmd ["annotate" "-f"])]
        (expect (= "annotate" (:action c)))
        (expect (true? (:full-page c)))))

    (it "parses annotate --full with other flags"
      (let [c (cmd ["annotate" "--full" "--no-badges"])]
        (expect (= "annotate" (:action c)))
        (expect (true? (:full-page c)))
        (expect (false? (:show-badges c))))))

  (describe "unannotate"
    (it "parses unannotate"
      (let [c (cmd ["unannotate"])]
        (expect (= "unannotate" (:action c)))))))

;; =============================================================================
;; Eval
;; =============================================================================

(defdescribe eval-test
  "Tests for eval command"

  (describe "evaluate JavaScript"
    (it "parses eval with script"
      (let [c (cmd ["eval" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (= "document.title" (:script c)))))

    (it "joins multi-word scripts"
      (let [c (cmd ["eval" "1" "+" "2"])]
        (expect (= "1 + 2" (:script c)))))))

;; =============================================================================
;; Scroll
;; =============================================================================

(defdescribe scroll-test
  "Tests for scroll command"

  (describe "scroll with direction and amount"
    (it "parses scroll down 300"
      (let [c (cmd ["scroll" "down" "300"])]
        (expect (= "scroll" (:action c)))
        (expect (= "down" (:direction c)))
        (expect (= 300 (:amount c)))))

    (it "parses scroll up with default amount"
      (let [c (cmd ["scroll" "up"])]
        (expect (= "scroll" (:action c)))
        (expect (= "up" (:direction c)))
        (expect (= 500 (:amount c)))))

    (it "defaults to down 500"
      (let [c (cmd ["scroll"])]
        (expect (= "scroll" (:action c)))
        (expect (= "down" (:direction c)))
        (expect (= 500 (:amount c)))))))

;; =============================================================================
;; Wait
;; =============================================================================

(defdescribe wait-test
  "Tests for wait command"

  (describe "wait variants"
    (it "parses wait with timeout"
      (let [c (cmd ["wait" "2000"])]
        (expect (= "wait" (:action c)))
        (expect (= 2000 (:timeout c)))))

    (it "parses wait with selector"
      (let [c (cmd ["wait" "@e1"])]
        (expect (= "wait" (:action c)))
        (expect (= "@e1" (:selector c)))))

    (it "defaults to load state"
      (let [c (cmd ["wait"])]
        (expect (= "wait" (:action c)))
        (expect (= "load" (:state c)))))))

;; =============================================================================
;; Tabs
;; =============================================================================

(defdescribe tab-test
  "Tests for tab commands"

  (describe "tab subcommands"
    (it "parses tab new"
      (expect (= "tab_new" (:action (cmd ["tab" "new"])))))

    (it "parses tab new with URL"
      (let [c (cmd ["tab" "new" "https://example.com"])]
        (expect (= "tab_new" (:action c)))
        (expect (= "https://example.com" (:url c)))))

    (it "parses tab switch by index"
      (let [c (cmd ["tab" "2"])]
        (expect (= "tab_switch" (:action c)))
        (expect (= 2 (:index c)))))

    (it "parses tab close"
      (expect (= "tab_close" (:action (cmd ["tab" "close"])))))

    (it "parses tab list"
      (expect (= "tab_list" (:action (cmd ["tab" "list"])))))

    (it "defaults to tab list with no args"
      (expect (= "tab_list" (:action (cmd ["tab"])))))))

;; =============================================================================
;; Getters
;; =============================================================================

(defdescribe getter-test
  "Tests for get commands"

  (describe "get text"
    (it "parses get text with selector"
      (let [c (cmd ["get" "text" "@e1"])]
        (expect (= "get_text" (:action c)))
        (expect (= "@e1" (:selector c))))))

  (describe "get url"
    (it "parses get url"
      (expect (= "url" (:action (cmd ["get" "url"]))))))

  (describe "get title"
    (it "parses get title"
      (expect (= "title" (:action (cmd ["get" "title"]))))))

  (describe "get html"
    (it "parses get html with selector"
      (let [c (cmd ["get" "html" "@e3"])]
        (expect (= "content" (:action c)))
        (expect (= "@e3" (:selector c))))))

  (describe "get count"
    (it "parses get count with selector"
      (let [c (cmd ["get" "count" ".items"])]
        (expect (= "get_count" (:action c)))
        (expect (= ".items" (:selector c))))))

  (describe "get box"
    (it "parses get box with selector"
      (let [c (cmd ["get" "box" "@e1"])]
        (expect (= "get_box" (:action c)))
        (expect (= "@e1" (:selector c))))))

  (describe "get with no subcommand"
    (it "defaults to url"
      (expect (= "url" (:action (cmd ["get"])))))))

;; =============================================================================
;; Is Checks
;; =============================================================================

(defdescribe is-check-test
  "Tests for is visibility/state checks"

  (describe "is visible"
    (it "parses is visible"
      (let [c (cmd ["is" "visible" "@e1"])]
        (expect (= "is_visible" (:action c)))
        (expect (= "@e1" (:selector c))))))

  (describe "is enabled"
    (it "parses is enabled"
      (let [c (cmd ["is" "enabled" "@e2"])]
        (expect (= "is_enabled" (:action c)))
        (expect (= "@e2" (:selector c))))))

  (describe "is checked"
    (it "parses is checked"
      (let [c (cmd ["is" "checked" "@e3"])]
        (expect (= "is_checked" (:action c)))
        (expect (= "@e3" (:selector c)))))))

;; =============================================================================
;; Close
;; =============================================================================

(defdescribe close-test
  "Tests for close command"

  (describe "close"
    (it "parses close"
      (expect (= {:action "close"} (cmd ["close"]))))))

;; =============================================================================
;; Count / BBox
;; =============================================================================

(defdescribe count-bbox-test
  "Tests for count and bounding box commands"

  (describe "count"
    (it "parses count with selector"
      (let [c (cmd ["count" ".items"])]
        (expect (= "count" (:action c)))
        (expect (= ".items" (:selector c))))))

  (describe "bbox"
    (it "parses bbox with selector"
      (let [c (cmd ["bbox" "@e1"])]
        (expect (= "bounding_box" (:action c)))
        (expect (= "@e1" (:selector c)))))))

;; =============================================================================
;; Global Flags
;; =============================================================================

(defdescribe global-flags-test
  "Tests for global flag parsing"

  (describe "--headed flag"
    (it "sets headless to false"
      (let [f (flags ["--headed" "open" "http://x.com"])]
        (expect (false? (:headless f)))))

    (it "defaults to headless true"
      (let [f (flags ["open" "http://x.com"])]
        (expect (true? (:headless f))))))

  (describe "--session flag"
    (it "sets custom session"
      (let [f (flags ["--session" "test" "open" "http://x.com"])]
        (expect (= "test" (:session f)))))

    (it "supports --session=value syntax"
      (let [f (flags ["--session=mysess" "open" "http://x.com"])]
        (expect (= "mysess" (:session f)))))

    (it "defaults session to default"
      (let [f (flags ["open" "http://x.com"])]
        (expect (= "default" (:session f))))))

  (describe "--json flag"
    (it "sets json to true"
      (let [f (flags ["--json" "get" "url"])]
        (expect (true? (:json f)))))

    (it "defaults json to false"
      (let [f (flags ["get" "url"])]
        (expect (false? (:json f))))))

  (describe "--headless flag"
    (it "sets headless to true"
      (let [f (flags ["--headless" "open" "http://x.com"])]
        (expect (true? (:headless f))))))

  (describe "--timeout flag"
    (it "sets timeout with --timeout"
      (let [f (flags ["--timeout" "5000" "open" "http://x.com"])]
        (expect (= 5000 (:timeout f)))))

    (it "supports --timeout=value syntax"
      (let [f (flags ["--timeout=10000" "open" "http://x.com"])]
        (expect (= 10000 (:timeout f)))))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defdescribe error-handling-test
  "Tests for error cases"

  (describe "unknown command"
    (it "returns error for unknown command"
      (let [c (cmd ["blah"])]
        (expect (some? (:error c))))))

  (describe "no command"
    (it "returns error when no command given"
      (let [c (cmd [])]
        (expect (some? (:error c)))))))

;; =============================================================================
;; New Global Flags
;; =============================================================================

(defdescribe new-global-flags-test
  "Tests for new global flags"

  (describe "--proxy flag"
    (it "sets proxy"
      (let [f (flags ["--proxy" "http://proxy:8080" "open" "http://x.com"])]
        (expect (= "http://proxy:8080" (:proxy f))))))

  (describe "--proxy-bypass flag"
    (it "sets proxy-bypass"
      (let [f (flags ["--proxy-bypass" "localhost,127.0.0.1" "open" "http://x.com"])]
        (expect (= "localhost,127.0.0.1" (:proxy-bypass f)))))

    (it "supports --proxy-bypass=value syntax"
      (let [f (flags ["--proxy-bypass=localhost" "open" "http://x.com"])]
        (expect (= "localhost" (:proxy-bypass f))))))

  (describe "--executable-path flag"
    (it "sets executable-path"
      (let [f (flags ["--executable-path" "/usr/bin/chromium" "open" "http://x.com"])]
        (expect (= "/usr/bin/chromium" (:executable-path f)))))

    (it "supports --executable-path=value syntax"
      (let [f (flags ["--executable-path=/usr/bin/chromium" "open" "http://x.com"])]
        (expect (= "/usr/bin/chromium" (:executable-path f))))))

  (describe "--user-agent flag"
    (it "sets user-agent"
      (let [f (flags ["--user-agent" "CustomAgent/1.0" "open" "http://x.com"])]
        (expect (= "CustomAgent/1.0" (:user-agent f))))))

  (describe "--args flag"
    (it "sets browser args"
      (let [f (flags ["--args" "--disable-gpu,--no-sandbox" "open" "http://x.com"])]
        (expect (= "--disable-gpu,--no-sandbox" (:args f)))))

    (it "supports --args=value syntax"
      (let [f (flags ["--args=--disable-gpu" "open" "http://x.com"])]
        (expect (= "--disable-gpu" (:args f))))))

  (describe "--cdp flag"
    (it "sets CDP endpoint"
      (let [f (flags ["--cdp" "http://localhost:9222" "open" "http://x.com"])]
        (expect (= "http://localhost:9222" (:cdp f)))))

    (it "supports --cdp=value syntax"
      (let [f (flags ["--cdp=http://localhost:9222" "open" "http://x.com"])]
        (expect (= "http://localhost:9222" (:cdp f))))))

  (describe "--allow-file-access flag"
    (it "sets allow-file-access"
      (let [f (flags ["--allow-file-access" "open" "http://x.com"])]
        (expect (true? (:allow-file-access f))))))

  (describe "--session-name flag"
    (it "sets session via --session-name"
      (let [f (flags ["--session-name" "agent1" "open" "http://x.com"])]
        (expect (= "agent1" (:session f)))))

    (it "supports --session-name=value syntax"
      (let [f (flags ["--session-name=agent1" "open" "http://x.com"])]
        (expect (= "agent1" (:session f))))))

  (describe "--ignore-https-errors flag"
    (it "sets ignore-https-errors"
      (let [f (flags ["--ignore-https-errors" "open" "http://x.com"])]
        (expect (true? (:ignore-https-errors f))))))

  (describe "--debug flag"
    (it "sets debug"
      (let [f (flags ["--debug" "open" "http://x.com"])]
        (expect (true? (:debug f))))))

  (describe "--storage-state flag"
    (it "sets storage-state"
      (let [f (flags ["--storage-state" "/tmp/state.json" "open" "http://x.com"])]
        (expect (= "/tmp/state.json" (:storage-state f)))))

    (it "supports --storage-state=value syntax"
      (let [f (flags ["--storage-state=/tmp/state.json" "open" "http://x.com"])]
        (expect (= "/tmp/state.json" (:storage-state f))))))

  (describe "--profile flag"
    (it "sets profile"
      (let [f (flags ["--profile" "/tmp/chrome-profile" "open" "http://x.com"])]
        (expect (= "/tmp/chrome-profile" (:profile f)))))

    (it "supports --profile=value syntax"
      (let [f (flags ["--profile=/tmp/chrome-profile" "open" "http://x.com"])]
        (expect (= "/tmp/chrome-profile" (:profile f))))))

  (describe "--headers flag"
    (it "sets headers"
      (let [f (flags ["--headers" "{\"Auth\":\"Bearer\"}" "open" "http://x.com"])]
        (expect (= "{\"Auth\":\"Bearer\"}" (:headers f)))))

    (it "supports --headers=value syntax"
      (let [f (flags ["--headers={\"Auth\":\"Bearer\"}" "open" "http://x.com"])]
        (expect (= "{\"Auth\":\"Bearer\"}" (:headers f)))))))

;; =============================================================================
;; Network Route (Bug Fix)
;; =============================================================================

(defdescribe network-route-fix-test
  "Tests for network route key naming"

  (describe "network route sends action_type"
    (it "sends action_type abort"
      (let [c (cmd ["network" "route" "**/ads/**" "--abort"])]
        (expect (= "network_route" (:action c)))
        (expect (= "abort" (:action_type c)))))

    (it "sends action_type fulfill with body"
      (let [c (cmd ["network" "route" "**/api/users" "--body" "{\"users\":[]}"])]
        (expect (= "network_route" (:action c)))
        (expect (= "fulfill" (:action_type c)))
        (expect (= "{\"users\":[]}" (:body c)))))

    (it "sends action_type continue as default"
      (let [c (cmd ["network" "route" "**/api/**"])]
        (expect (= "network_route" (:action c)))
        (expect (= "continue" (:action_type c)))))))

;; =============================================================================
;; Mouse Commands (Button)
;; =============================================================================

(defdescribe mouse-button-test
  "Tests for mouse button parameter"

  (describe "mouse down with button"
    (it "parses mouse down with left"
      (let [c (cmd ["mouse" "down" "left"])]
        (expect (= "mouse_down" (:action c)))
        (expect (= "left" (:button c)))))

    (it "parses mouse down with right"
      (let [c (cmd ["mouse" "down" "right"])]
        (expect (= "mouse_down" (:action c)))
        (expect (= "right" (:button c)))))

    (it "defaults to left when no button"
      (let [c (cmd ["mouse" "down"])]
        (expect (= "mouse_down" (:action c)))
        (expect (= "left" (:button c))))))

  (describe "mouse up with button"
    (it "parses mouse up with middle"
      (let [c (cmd ["mouse" "up" "middle"])]
        (expect (= "mouse_up" (:action c)))
        (expect (= "middle" (:button c))))))

  (describe "mouse move"
    (it "parses mouse move coordinates"
      (let [c (cmd ["mouse" "move" "100" "200"])]
        (expect (= "mouse_move" (:action c)))
        (expect (= 100.0 (:x c)))
        (expect (= 200.0 (:y c))))))

  (describe "mouse wheel"
    (it "parses mouse wheel"
      (let [c (cmd ["mouse" "wheel" "100"])]
        (expect (= "mouse_wheel" (:action c)))
        (expect (= 100.0 (:deltaY c)))))))

;; =============================================================================
;; Set Device
;; =============================================================================

(defdescribe set-device-test
  "Tests for set device command"

  (describe "set device"
    (it "parses set device with name"
      (let [c (cmd ["set" "device" "iphone" "14"])]
        (expect (= "set_device" (:action c)))
        (expect (= "iphone 14" (:device c)))))

    (it "parses set device pixel 7"
      (let [c (cmd ["set" "device" "pixel" "7"])]
        (expect (= "set_device" (:action c)))
        (expect (= "pixel 7" (:device c)))))))

;; =============================================================================
;; Connect CDP
;; =============================================================================

(defdescribe connect-test
  "Tests for connect command"

  (describe "connect"
    (it "parses connect with URL"
      (let [c (cmd ["connect" "http://localhost:9222"])]
        (expect (= "connect" (:action c)))
        (expect (= "http://localhost:9222" (:url c)))))))

;; =============================================================================
;; Eval Flags
;; =============================================================================

(defdescribe eval-flags-test
  "Tests for eval flags"

  (describe "eval -b flag"
    (it "parses eval with base64 flag"
      (let [c (cmd ["eval" "-b" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (= "document.title" (:script c)))
        (expect (true? (:base64 c)))))

    (it "parses eval with --base64 flag"
      (let [c (cmd ["eval" "--base64" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (true? (:base64 c))))))

  (describe "eval --stdin flag"
    (it "parses eval with --stdin"
      (let [c (cmd ["eval" "--stdin"])]
        (expect (= "evaluate" (:action c)))
        (expect (true? (:stdin c)))))))

;; =============================================================================
;; Snapshot Cursor Filter
;; =============================================================================

(defdescribe snapshot-cursor-test
  "Tests for snapshot -C cursor flag"

  (describe "snapshot -C"
    (it "parses snapshot with cursor flag"
      (let [c (cmd ["snapshot" "-C"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:cursor c)))))

    (it "parses snapshot with --cursor flag"
      (let [c (cmd ["snapshot" "--cursor"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:cursor c)))))

    (it "combines -i and -C"
      (let [c (cmd ["snapshot" "-i" "-C"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:interactive c)))
        (expect (true? (:cursor c)))))))

;; =============================================================================
;; Keydown / Keyup
;; =============================================================================

(defdescribe keydown-keyup-test
  "Tests for keydown and keyup commands"

  (describe "keydown"
    (it "parses keydown with key"
      (let [c (cmd ["keydown" "Shift"])]
        (expect (= "keydown" (:action c)))
        (expect (= "Shift" (:key c))))))

  (describe "keyup"
    (it "parses keyup with key"
      (let [c (cmd ["keyup" "Shift"])]
        (expect (= "keyup" (:action c)))
        (expect (= "Shift" (:key c)))))))

;; =============================================================================
;; Scrollintoview
;; =============================================================================

(defdescribe scrollintoview-test
  "Tests for scrollintoview command"

  (describe "scrollintoview"
    (it "parses scrollintoview with selector"
      (let [c (cmd ["scrollintoview" "#footer"])]
        (expect (= "scrollintoview" (:action c)))
        (expect (= "#footer" (:selector c)))))

    (it "parses scrollinto alias"
      (let [c (cmd ["scrollinto" ".bottom"])]
        (expect (= "scrollintoview" (:action c)))
        (expect (= ".bottom" (:selector c)))))))

;; =============================================================================
;; Drag & Upload
;; =============================================================================

(defdescribe drag-upload-test
  "Tests for drag and upload commands"

  (describe "drag"
    (it "parses drag with source and target"
      (let [c (cmd ["drag" "#source" "#target"])]
        (expect (= "drag" (:action c)))
        (expect (= "#source" (:source c)))
        (expect (= "#target" (:target c))))))

  (describe "upload"
    (it "parses upload with selector and files"
      (let [c (cmd ["upload" "input#file" "file1.txt" "file2.pdf"])]
        (expect (= "upload" (:action c)))
        (expect (= "input#file" (:selector c)))
        (expect (= ["file1.txt" "file2.pdf"] (:files c)))))))

;; =============================================================================
;; Find (Semantic Locators)
;; =============================================================================

(defdescribe find-test
  "Tests for find command"

  (describe "find by role"
    (it "parses find role with action"
      (let [c (cmd ["find" "role" "button" "click"])]
        (expect (= "find" (:action c)))
        (expect (= "role" (:by c)))
        (expect (= "button" (:value c)))
        (expect (= "click" (:find_action c)))))

    (it "parses find role with --name"
      (let [c (cmd ["find" "role" "button" "click" "--name" "Submit"])]
        (expect (= "find" (:action c)))
        (expect (= "Submit" (:name c)))))

    (it "parses find role with --exact"
      (let [c (cmd ["find" "role" "button" "click" "--exact"])]
        (expect (= "find" (:action c)))
        (expect (true? (:exact c))))))

  (describe "find by text"
    (it "parses find text"
      (let [c (cmd ["find" "text" "Welcome" "click"])]
        (expect (= "text" (:by c)))
        (expect (= "Welcome" (:value c))))))

  (describe "find by label"
    (it "parses find label with fill action"
      (let [c (cmd ["find" "label" "Email" "fill" "test@test.com"])]
        (expect (= "label" (:by c)))
        (expect (= "fill" (:find_action c)))
        (expect (= "test@test.com" (:find_value c))))))

  (describe "find by placeholder"
    (it "parses find placeholder"
      (let [c (cmd ["find" "placeholder" "Search" "type" "query"])]
        (expect (= "placeholder" (:by c)))
        (expect (= "type" (:find_action c))))))

  (describe "find by alt"
    (it "parses find alt"
      (let [c (cmd ["find" "alt" "Logo" "click"])]
        (expect (= "alt" (:by c)))
        (expect (= "Logo" (:value c)))
        (expect (= "click" (:find_action c))))))

  (describe "find by title"
    (it "parses find title"
      (let [c (cmd ["find" "title" "Close" "click"])]
        (expect (= "title" (:by c)))
        (expect (= "Close" (:value c)))
        (expect (= "click" (:find_action c))))))

  (describe "find by testid"
    (it "parses find testid"
      (let [c (cmd ["find" "testid" "submit-btn" "click"])]
        (expect (= "testid" (:by c)))
        (expect (= "submit-btn" (:value c)))
        (expect (= "click" (:find_action c))))))

  (describe "find positional"
    (it "parses find first"
      (let [c (cmd ["find" "first" ".item" "click"])]
        (expect (= "first" (:by c)))
        (expect (= ".item" (:value c)))))

    (it "parses find last"
      (let [c (cmd ["find" "last" "input" "focus"])]
        (expect (= "last" (:by c)))))

    (it "parses find nth"
      (let [c (cmd ["find" "nth" "2" "li" "click"])]
        (expect (= "nth" (:by c)))
        (expect (= "2" (:value c)))
        (expect (= "li" (:selector c)))
        (expect (= "click" (:find_action c))))))

  (describe "ARIA role shortcuts"
    (it "treats unknown find type as ARIA role shortcut"
      (let [c (cmd ["find" "link" "click"])]
        (expect (= "find" (:action c)))
        (expect (= "role" (:by c)))
        (expect (= "link" (:value c)))
        (expect (= "click" (:find_action c)))))

    (it "parses role shortcut with action value"
      (let [c (cmd ["find" "button" "fill" "hello"])]
        (expect (= "role" (:by c)))
        (expect (= "button" (:value c)))
        (expect (= "fill" (:find_action c)))
        (expect (= "hello" (:find_value c)))))

    (it "parses role shortcut with --name flag"
      (let [c (cmd ["find" "heading" "click" "--name" "Title"])]
        (expect (= "role" (:by c)))
        (expect (= "heading" (:value c)))
        (expect (= "Title" (:name c)))))

    (it "parses role shortcut with no action"
      (let [c (cmd ["find" "paragraph"])]
        (expect (= "role" (:by c)))
        (expect (= "paragraph" (:value c)))
        (expect (nil? (:find_action c)))))))

;; =============================================================================
;; Help
;; =============================================================================

(defdescribe help-test
  "Tests for per-command help system"

  (describe "parse-args help detection"
    (it "detects --help for a command"
      (let [c (cmd ["open" "--help"])]
        (expect (= "help" (:action c)))
        (expect (= "open" (:for c)))))

    (it "detects -h for a command"
      (let [c (cmd ["click" "-h"])]
        (expect (= "help" (:action c)))
        (expect (= "click" (:for c)))))

    (it "detects bare spel --help"
      (let [c (cmd ["--help"])]
        (expect (= "help" (:action c)))
        (expect (nil? (:for c)))))

    (it "detects bare spel -h"
      (let [c (cmd ["-h"])]
        (expect (= "help" (:action c)))
        (expect (nil? (:for c))))))

  (describe "command-help map"
    (it "has help for all major commands"
      (doseq [cmd-name ["open" "back" "forward" "reload" "snapshot" "click" "dblclick"
                        "fill" "type" "clear" "press" "keydown" "keyup" "hover" "mouse"
                        "check" "uncheck" "select" "focus" "scroll" "scrollintoview"
                        "drag" "upload" "screenshot" "annotate" "unannotate" "pdf"
                        "eval" "wait" "tab" "get" "is" "count" "bbox" "highlight"
                        "find" "set" "cookies" "storage" "network" "frame" "dialog"
                        "trace" "console" "errors" "state" "session" "connect"
                        "close" "install" "inspector" "show-trace"]]
        (expect (string? (get sut/command-help cmd-name))))))

  (describe "top-level-help"
    (it "returns a non-empty string"
      (expect (string? (sut/top-level-help)))
      (expect (pos? (count (sut/top-level-help)))))

    (it "contains key sections"
      (let [h (sut/top-level-help)]
        (expect (.contains ^String h "Navigation:"))
        (expect (.contains ^String h "Global Flags:"))
        (expect (.contains ^String h "Environment Variables:"))))))

;; =============================================================================
;; Cookies
;; =============================================================================

(defdescribe cookies-test
  "Tests for cookies commands"

  (describe "cookies get"
    (it "parses cookies with no args"
      (expect (= "cookies_get" (:action (cmd ["cookies"]))))))

  (describe "cookies set"
    (it "parses cookies set"
      (let [c (cmd ["cookies" "set" "session_id" "abc123"])]
        (expect (= "cookies_set" (:action c)))
        (expect (= "session_id" (:name c)))
        (expect (= "abc123" (:value c))))))

  (describe "cookies clear"
    (it "parses cookies clear"
      (expect (= "cookies_clear" (:action (cmd ["cookies" "clear"])))))))

;; =============================================================================
;; Storage
;; =============================================================================

(defdescribe storage-test
  "Tests for storage commands"

  (describe "storage local get all"
    (it "parses storage local"
      (let [c (cmd ["storage" "local"])]
        (expect (= "storage_get" (:action c)))
        (expect (= "local" (:type c))))))

  (describe "storage local get key"
    (it "parses storage local with key"
      (let [c (cmd ["storage" "local" "user"])]
        (expect (= "storage_get" (:action c)))
        (expect (= "user" (:key c))))))

  (describe "storage local set"
    (it "parses storage local set"
      (let [c (cmd ["storage" "local" "set" "token" "xyz"])]
        (expect (= "storage_set" (:action c)))
        (expect (= "local" (:type c)))
        (expect (= "token" (:key c)))
        (expect (= "xyz" (:value c))))))

  (describe "storage clear"
    (it "parses storage local clear"
      (let [c (cmd ["storage" "local" "clear"])]
        (expect (= "storage_clear" (:action c)))
        (expect (= "local" (:type c))))))

  (describe "storage session"
    (it "parses storage session"
      (let [c (cmd ["storage" "session"])]
        (expect (= "storage_get" (:action c)))
        (expect (= "session" (:type c)))))))

;; =============================================================================
;; Frame
;; =============================================================================

(defdescribe frame-test
  "Tests for frame commands"

  (describe "frame switch"
    (it "parses frame with selector"
      (let [c (cmd ["frame" "iframe#content"])]
        (expect (= "frame_switch" (:action c)))
        (expect (= "iframe#content" (:selector c)))))

    (it "parses frame main"
      (let [c (cmd ["frame" "main"])]
        (expect (= "frame_switch" (:action c)))
        (expect (= "main" (:selector c)))))

    (it "parses frame list"
      (expect (= "frame_list" (:action (cmd ["frame" "list"])))))

    (it "defaults to main frame with no args"
      (let [c (cmd ["frame"])]
        (expect (= "frame_switch" (:action c)))
        (expect (= "main" (:selector c)))))))

;; =============================================================================
;; Dialog
;; =============================================================================

(defdescribe dialog-test
  "Tests for dialog commands"

  (describe "dialog accept"
    (it "parses dialog accept"
      (let [c (cmd ["dialog" "accept"])]
        (expect (= "dialog_accept" (:action c)))))

    (it "parses dialog accept with text"
      (let [c (cmd ["dialog" "accept" "prompt text"])]
        (expect (= "dialog_accept" (:action c)))
        (expect (= "prompt text" (:text c))))))

  (describe "dialog dismiss"
    (it "parses dialog dismiss"
      (expect (= "dialog_dismiss" (:action (cmd ["dialog" "dismiss"])))))))

;; =============================================================================
;; Trace
;; =============================================================================

(defdescribe trace-test
  "Tests for trace commands"

  (describe "trace start"
    (it "parses trace start"
      (let [c (cmd ["trace" "start" "my-trace"])]
        (expect (= "trace_start" (:action c)))
        (expect (= "my-trace" (:name c))))))

  (describe "trace stop"
    (it "parses trace stop"
      (let [c (cmd ["trace" "stop" "trace.zip"])]
        (expect (= "trace_stop" (:action c)))
        (expect (= "trace.zip" (:path c)))))))

;; =============================================================================
;; Console & Errors
;; =============================================================================

(defdescribe console-errors-test
  "Tests for console and errors commands"

  (describe "console"
    (it "parses console get"
      (expect (= "console_get" (:action (cmd ["console"])))))

    (it "parses console --clear flag"
      (expect (= "console_clear" (:action (cmd ["console" "--clear"])))))

    (it "parses console clear subcommand"
      (expect (= "console_clear" (:action (cmd ["console" "clear"]))))))

  (describe "errors"
    (it "parses errors get"
      (expect (= "errors_get" (:action (cmd ["errors"])))))

    (it "parses errors --clear flag"
      (let [c (cmd ["errors" "--clear"])]
        (expect (= "errors_get" (:action c)))
        (expect (true? (:clear c)))))

    (it "parses errors clear subcommand"
      (expect (= "errors_clear" (:action (cmd ["errors" "clear"])))))))

;; =============================================================================
;; Highlight
;; =============================================================================

(defdescribe highlight-test
  "Tests for highlight command"

  (describe "highlight"
    (it "parses highlight with selector"
      (let [c (cmd ["highlight" "button.submit"])]
        (expect (= "highlight" (:action c)))
        (expect (= "button.submit" (:selector c)))))))

;; =============================================================================
;; State Management
;; =============================================================================

(defdescribe state-test
  "Tests for state management commands"

  (describe "state save"
    (it "parses state save"
      (let [c (cmd ["state" "save" "state.json"])]
        (expect (= "state_save" (:action c)))
        (expect (= "state.json" (:path c))))))

  (describe "state load"
    (it "parses state load"
      (let [c (cmd ["state" "load" "state.json"])]
        (expect (= "state_load" (:action c)))
        (expect (= "state.json" (:path c))))))

  (describe "state list"
    (it "parses state list"
      (expect (= "state_list" (:action (cmd ["state" "list"]))))))

  (describe "state show"
    (it "parses state show"
      (let [c (cmd ["state" "show" "state.json"])]
        (expect (= "state_show" (:action c)))
        (expect (= "state.json" (:file c))))))

  (describe "state rename"
    (it "parses state rename"
      (let [c (cmd ["state" "rename" "old.json" "new.json"])]
        (expect (= "state_rename" (:action c)))
        (expect (= "old.json" (:old_name c)))
        (expect (= "new.json" (:new_name c))))))

  (describe "state clear"
    (it "parses state clear with name"
      (let [c (cmd ["state" "clear" "session1"])]
        (expect (= "state_clear" (:action c)))
        (expect (= "session1" (:name c)))))

    (it "parses state clear --all"
      (let [c (cmd ["state" "clear" "--all"])]
        (expect (= "state_clear" (:action c)))
        (expect (true? (:all c))))))

  (describe "state clean"
    (it "parses state clean with --older-than"
      (let [c (cmd ["state" "clean" "--older-than" "7"])]
        (expect (= "state_clean" (:action c)))
        (expect (= 7 (:older_than_days c)))))))

;; =============================================================================
;; Install
;; =============================================================================

(defdescribe install-test
  "Tests for install command"

  (describe "install"
    (it "parses install"
      (let [c (cmd ["install"])]
        (expect (= "install" (:action c)))))

    (it "parses install --with-deps"
      (let [c (cmd ["install" "--with-deps"])]
        (expect (= "install" (:action c)))
        (expect (some? (:with-deps c))))))

  (describe "session"
    (it "parses session list"
      (expect (= "session_list" (:action (cmd ["session" "list"])))))

    (it "parses session info"
      (expect (= "session_info" (:action (cmd ["session"]))))))

  (describe "aliases"
    (it "parses quit as close"
      (expect (= "close" (:action (cmd ["quit"])))))

    (it "parses exit as close"
      (expect (= "close" (:action (cmd ["exit"])))))

    (it "parses navigate as open"
      (expect (= "navigate" (:action (cmd ["navigate" "http://x.com"])))))

    (it "parses key as press"
      (expect (= "press" (:action (cmd ["key" "Enter"]))))))

  (describe "set commands"
    (it "parses set viewport"
      (let [c (cmd ["set" "viewport" "1280" "720"])]
        (expect (= "set_viewport" (:action c)))
        (expect (= 1280 (:width c)))
        (expect (= 720 (:height c)))))

    (it "parses set geo"
      (let [c (cmd ["set" "geo" "37.7749" "-122.4194"])]
        (expect (= "set_geo" (:action c)))
        (expect (= 37.7749 (:latitude c)))))

    (it "parses set offline on"
      (let [c (cmd ["set" "offline" "on"])]
        (expect (= "set_offline" (:action c)))
        (expect (true? (:enabled c)))))

    (it "parses set offline off"
      (let [c (cmd ["set" "offline" "off"])]
        (expect (= "set_offline" (:action c)))
        (expect (false? (:enabled c)))))

    (it "parses set credentials"
      (let [c (cmd ["set" "credentials" "user" "pass"])]
        (expect (= "set_credentials" (:action c)))
        (expect (= "user" (:username c)))
        (expect (= "pass" (:password c)))))

    (it "parses set media"
      (let [c (cmd ["set" "media" "dark"])]
        (expect (= "set_media" (:action c)))
        (expect (= "dark" (:colorScheme c)))))

    (it "parses set headers"
      (let [c (cmd ["set" "headers" "{\"X-Custom\":\"value\"}"])]
        (expect (= "set_headers" (:action c)))
        (expect (= {"X-Custom" "value"} (:headers c))))))

  (describe "get value and attribute"
    (it "parses get value"
      (let [c (cmd ["get" "value" "@e1"])]
        (expect (= "get_value" (:action c)))
        (expect (= "@e1" (:selector c)))))

    (it "parses get attr"
      (let [c (cmd ["get" "attr" "@e1" "href"])]
        (expect (= "get_attribute" (:action c)))
        (expect (= "@e1" (:selector c)))
        (expect (= "href" (:attribute c)))))

    (it "parses get attribute"
      (let [c (cmd ["get" "attribute" "@e2" "class"])]
        (expect (= "get_attribute" (:action c)))
        (expect (= "class" (:attribute c))))))

  (describe "wait enhanced"
    (it "parses wait --text"
      (let [c (cmd ["wait" "--text" "Welcome"])]
        (expect (= "wait" (:action c)))
        (expect (= "Welcome" (:text c)))))

    (it "parses wait --url"
      (let [c (cmd ["wait" "--url" "**/dashboard"])]
        (expect (= "wait" (:action c)))
        (expect (= "**/dashboard" (:url c)))))

    (it "parses wait --load"
      (let [c (cmd ["wait" "--load" "networkidle"])]
        (expect (= "wait" (:action c)))
        (expect (= "networkidle" (:state c)))))

    (it "parses wait --fn"
      (let [c (cmd ["wait" "--fn" "window.ready === true"])]
        (expect (= "wait" (:action c)))
        (expect (= "window.ready === true" (:function c))))))

  (describe "network unroute and requests"
    (it "parses network unroute"
      (let [c (cmd ["network" "unroute" "**/api/**"])]
        (expect (= "network_unroute" (:action c)))
        (expect (= "**/api/**" (:url c)))))

    (it "parses network unroute without url"
      (let [c (cmd ["network" "unroute"])]
        (expect (= "network_unroute" (:action c)))))

    (it "parses network requests"
      (expect (= "network_requests" (:action (cmd ["network" "requests"])))))

    (it "parses network requests with filter"
      (let [c (cmd ["network" "requests" "--filter" "api"])]
        (expect (= "network_requests" (:action c)))
        (expect (= "api" (:filter c)))))

    (it "parses network requests with --type"
      (let [c (cmd ["network" "requests" "--type" "fetch"])]
        (expect (= "network_requests" (:action c)))
        (expect (= "fetch" (:type c)))))

    (it "parses network requests with --method"
      (let [c (cmd ["network" "requests" "--method" "POST"])]
        (expect (= "network_requests" (:action c)))
        (expect (= "POST" (:method c)))))

    (it "parses network requests with --status"
      (let [c (cmd ["network" "requests" "--status" "4"])]
        (expect (= "network_requests" (:action c)))
        (expect (= "4" (:status c)))))

    (it "parses network requests with combined flags"
      (let [c (cmd ["network" "requests" "--type" "fetch" "--status" "2"])]
        (expect (= "network_requests" (:action c)))
        (expect (= "fetch" (:type c)))
        (expect (= "2" (:status c)))))

    (it "parses network clear"
      (expect (= "network_clear" (:action (cmd ["network" "clear"]))))))

  (describe "open --interactive"
    (it "parses open --interactive"
      (let [c (cmd ["open" "https://example.com" "--interactive"])]
        (expect (= "navigate" (:action c)))
        (expect (true? (:interactive c))))))

  (describe "inspector command"
    (it "parses inspector"
      (let [c (cmd ["inspector"])]
        (expect (= "inspector" (:action c)))))

    (it "parses inspector with url"
      (let [c (cmd ["inspector" "https://example.com"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["https://example.com"] (:cli-args c)))))

    (it "parses inspector with browser flag"
      (let [c (cmd ["inspector" "-b" "firefox" "https://example.com"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["-b" "firefox" "https://example.com"] (:cli-args c)))))

    (it "parses inspector with device flag"
      (let [c (cmd ["inspector" "--device" "iPhone 14" "https://example.com"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["--device" "iPhone 14" "https://example.com"] (:cli-args c))))))

  (describe "show-trace command"
    (it "parses show-trace"
      (let [c (cmd ["show-trace"])]
        (expect (= "show-trace" (:action c)))))

    (it "parses show-trace with file"
      (let [c (cmd ["show-trace" "trace.zip"])]
        (expect (= "show-trace" (:action c)))
        (expect (= ["trace.zip"] (:cli-args c)))))

    (it "parses show-trace with port flag"
      (let [c (cmd ["show-trace" "--port" "8080" "trace.zip"])]
        (expect (= "show-trace" (:action c)))
        (expect (= ["--port" "8080" "trace.zip"] (:cli-args c)))))))

;; =============================================================================
;; Native parse-global-flags (--autoclose, --session for --eval mode)
;; =============================================================================

(defdescribe native-global-flags-test
  "Tests for native.clj parse-global-flags (private)"

  (describe "--autoclose flag"
    (it "defaults to false"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--eval" "(+ 1 2)"])]
        (expect (false? (:autoclose? g)))))

    (it "sets autoclose? to true"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--autoclose" "--eval" "(+ 1 2)"])]
        (expect (true? (:autoclose? g)))))

    (it "strips --autoclose from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--autoclose" "--eval" "(+ 1 2)"])]
        (expect (not (some #{"--autoclose"} (:command-args g)))))))

  (describe "--session flag"
    (it "defaults to nil"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--eval" "(+ 1 2)"])]
        (expect (nil? (:session g)))))

    (it "parses --session <name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session" "mytest" "--eval" "(+ 1 2)"])]
        (expect (= "mytest" (:session g)))))

    (it "parses --session=<name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session=mytest" "--eval" "(+ 1 2)"])]
        (expect (= "mytest" (:session g)))))

    (it "strips --session from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session" "work" "--eval" "(+ 1 2)"])]
        (expect (not (some #{"--session"} (:command-args g))))
        (expect (not (some #{"work"} (:command-args g)))))))

  (describe "combined flags"
    (it "parses --autoclose --session --timeout together"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--autoclose" "--session" "dev" "--timeout" "5000" "--eval" "(+ 1 2)"])]
        (expect (true? (:autoclose? g)))
        (expect (= "dev" (:session g)))
        (expect (= 5000 (:timeout-ms g)))))))
