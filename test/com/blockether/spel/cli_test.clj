(ns com.blockether.spel.cli-test
  "Tests for the CLI arg parser.

   Unit tests for parse-args covering all supported CLI commands,
   global flags, and edge cases, plus result rendering for
   bridge-routed scalar responses."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.cli :as sut]
   [com.blockether.spel.daemon :as daemon]
   [com.blockether.spel.logging :as log]
   [com.blockether.spel.native] ;; for #' access to parse-global-flags
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; print-result — rendering bridge-routed responses
;; =============================================================================

(defn- render-result
  ([response]
   (render-result response false))
  ([response flags]
   (let [print-result (var-get #'sut/print-result)]
     ;; Strip CR so assertions are line-ending agnostic: on Windows `println`
     ;; emits platform CRLF, but the expected strings use bare LF.
     (str/replace (with-out-str (print-result response flags)) "\r" ""))))

(defdescribe print-result-test
  "Rendering of CLI results, including scalar bridge responses.

   A bridge-routed get text/value/title/url can return a bare string (not a
   daemon-shaped map). Every dispatch branch in print-result assumes `data`
   is a map, so a scalar used to crash with `contains? not supported on type:
   java.lang.String`. Scalars must now render cleanly."

  (describe "scalar bridge results"
    (it "renders a bare string without crashing"
      (expect (= "Spel Dev Test\n"
                (render-result {:success true :data "Spel Dev Test"}))))

    (it "renders a bare url string"
      (expect (= "http://127.0.0.1/page\n"
                (render-result {:success true :data "http://127.0.0.1/page"}))))

    (it "renders a number scalar"
      (expect (= "42\n"
                (render-result {:success true :data 42})))))

  (describe "map results still dispatch"
    (it "renders :title map"
      (expect (= "Spel Dev Test\n"
                (render-result {:success true :data {:title "Spel Dev Test"}}))))

    (it "renders :url map"
      (expect (= "http://127.0.0.1/page\n"
                (render-result {:success true :data {:url "http://127.0.0.1/page"}}))))

    ;; Regression, user report: content boundaries turned silent results into
    ;; visible empty <untrusted-content> blocks.
    (it "keeps a nil JS-eval result silent with content boundaries enabled"
      (expect (= ""
                (render-result {:success true :data {:result nil}}
                  {:content-boundaries true})))))

  ;; Regression, user report: --json wrapped its payload in
  ;; <untrusted-content> delimiters, so the stdout an agent was told to parse
  ;; stopped being JSON.
  (describe "--json output stays machine-parseable"
    (it "leaves a JSON payload unwrapped when content boundaries are on"
      (expect (= "{\"url\":\"http://127.0.0.1/page\"}\n"
                (render-result {:success true :data {:url "http://127.0.0.1/page"}}
                  {:json true :content-boundaries true}))))

    (it "leaves a JSON error object unwrapped when content boundaries are on"
      (expect (= "{\"error\":\"Ref @e1 not found.\"}\n"
                (render-result {:success false :error "Ref @e1 not found."}
                  {:json true :content-boundaries true}))))

    (it "still wraps non-JSON stdout"
      (expect (= "<untrusted-content>\nSpel Dev Test\n</untrusted-content>\n"
                (render-result {:success true :data {:title "Spel Dev Test"}}
                  {:content-boundaries true}))))

    (it "keeps the same combination unwrapped when it comes off argv"
      (expect (= "{\"url\":\"http://127.0.0.1/page\"}\n"
                (render-result {:success true :data {:url "http://127.0.0.1/page"}}
                  (:flags (sut/parse-args ["--json" "--content-boundaries" "url"]))))))))

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

(defn- abs-path
  "Resolves a relative path to absolute (matches cli/resolve-path behavior)."
  ^String [^String path]
  (str (.toAbsolutePath (java.nio.file.Path/of path (into-array String [])))))

;; =============================================================================
;; iOS application provider
;; =============================================================================

(defdescribe ios-application-provider-test
  "iOS app startup flags and SCI-first provider orchestration"

  (it "parses an installed app bundle target"
    (let [f (flags ["--provider" "ios" "--bundle-id" "com.example.app"
                    "snapshot"])]
      (expect (= "ios" (:provider f)))
      (expect (= "com.example.app" (:bundle-id f)))))

  (it "resolves an app bundle path"
    (expect (= (abs-path "build/Demo.app")
              (:app (flags ["--provider=ios" "--app" "build/Demo.app"
                            "snapshot"])))))

  (it "rejects removed provider-specific command trees"
    (doseq [args [["context" "list"]
                  ["app" "activate" "com.example.app"]
                  ["doctor" "ios"]]]
      (expect (str/includes? (:error (cmd args)) "Unknown command"))))

  (it "directs iOS device discovery to SCI"
    (let [result (cmd ["device" "list" "--provider" "ios"])]
      (expect (str/includes? (:error result) "spel/ios-devices"))))

  (it "does not recognize persistent auto-webview startup"
    (let [result (sut/parse-args ["--provider" "ios" "--auto-webview" "snapshot"])]
      (expect (nil? (:auto-webview (:flags result))))
      (expect (str/includes? (:error (:command result)) "Unknown command"))))

  (it "uses generic click/scroll instead of provider-specific tap/swipe"
    (expect (= {:action "click" :x 100 :y 200}
              (cmd ["click" "100" "200"])))
    (expect (= "scroll" (:action (cmd ["scroll" "down" "400"]))))
    (expect (str/includes? (:error (cmd ["tap" "100" "200"])) "Unknown command"))
    (expect (str/includes? (:error (cmd ["swipe" "up"])) "Unknown command")))

  (it "parses keyboard dismissal"
    (expect (= {:action "keyboard_hide"} (cmd ["keyboard" "hide"])))))

;; =============================================================================
;; Navigation Commands
;; =============================================================================

(defdescribe navigation-test
  "Tests for navigation commands"

  (describe "open command"
    (it "parses open with URL"
      (let [c (cmd ["open" "https://example.org"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.org" (:url c)))))

    (it "parses goto as alias for open"
      (let [c (cmd ["goto" "https://example.org"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.org" (:url c)))))

    (it "auto-prefixes https for bare domains"
      (let [c (cmd ["open" "example.org"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.org" (:url c)))))

    (it "preserves file:// protocol"
      (let [c (cmd ["open" "file:///tmp/page.html"])]
        (expect (= "navigate" (:action c)))
        (expect (= "file:///tmp/page.html" (:url c)))))

    (it "preserves data: protocol"
      (let [c (cmd ["open" "data:text/html,<h1>hi</h1>"])]
        (expect (= "navigate" (:action c)))
        (expect (= "data:text/html,<h1>hi</h1>" (:url c)))))
    (it "preserves about:blank"
      (let [c (cmd ["open" "about:blank"])]
        (expect (= "navigate" (:action c)))
        (expect (= "about:blank" (:url c)))))

    (it "preserves chrome:// protocol"
      (let [c (cmd ["open" "chrome://settings"])]
        (expect (= "navigate" (:action c)))
        (expect (= "chrome://settings" (:url c)))))

    (it "preserves javascript: protocol"
      (let [c (cmd ["open" "javascript:void(0)"])]
        (expect (= "navigate" (:action c)))
        (expect (= "javascript:void(0)" (:url c)))))

    (it "preserves blob: protocol"
      (let [c (cmd ["open" "blob:http://example.org/abc"])]
        (expect (= "navigate" (:action c)))
        (expect (= "blob:http://example.org/abc" (:url c)))))

    (it "includes raw-input in command map"
      (let [c (cmd ["open" "example.org"])]
        (expect (= "example.org" (:raw-input c)))
        (expect (= "https://example.org" (:url c)))))

    (it "parses open with no URL"
      (let [c (cmd ["open"])]
        (expect (= "navigate" (:action c)))
        (expect (nil? (:url c)))))

    (it "parses open with --viewport WxH"
      (let [c (cmd ["open" "https://example.org" "--viewport" "1200x800"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.org" (:url c)))
        (expect (= 1200 (:viewport-width c)))
        (expect (= 800 (:viewport-height c)))))

    (it "parses open with --viewport using comma separator"
      (let [c (cmd ["open" "https://example.org" "--viewport" "1024,768"])]
        (expect (= 1024 (:viewport-width c)))
        (expect (= 768 (:viewport-height c)))))

    (it "parses open with --viewport and --screenshot together"
      (let [c (cmd ["open" "https://example.org" "--viewport" "800x600" "--screenshot" "out.png"])]
        (expect (= "navigate" (:action c)))
        (expect (= "https://example.org" (:url c)))
        (expect (= 800 (:viewport-width c)))
        (expect (= 600 (:viewport-height c)))
        (expect (true? (:screenshot c)))
        (expect (= (abs-path "out.png") (:screenshot-path c)))))

    (it "does not include viewport keys when --viewport not given"
      (let [c (cmd ["open" "https://example.org"])]
        (expect (nil? (:viewport-width c)))
        (expect (nil? (:viewport-height c))))))

  (it "finds URL at end after --viewport flag"
    (let [c (cmd ["open" "--viewport" "390x844" "https://example.com"])]
      (expect (= "navigate" (:action c)))
      (expect (= "https://example.com" (:url c)))
      (expect (= 390 (:viewport-width c)))
      (expect (= 844 (:viewport-height c)))))

  (it "finds URL at end after unknown flags"
    (let [c (cmd ["open" "--width" "390" "--height" "844" "https://example.com"])]
      (expect (= "navigate" (:action c)))
      (expect (= "https://example.com" (:url c)))))

  (it "finds bare domain URL at end after flags"
    (let [c (cmd ["open" "--interactive" "example.com"])]
      (expect (= "navigate" (:action c)))
      (expect (= "https://example.com" (:url c)))
      (expect (= "example.com" (:raw-input c)))))

  (it "finds URL between flags"
    (let [r (sut/parse-args ["open" "--interactive" "https://example.com" "--screenshot"])
          c (:command r)
          f (:flags r)]
      (expect (= "navigate" (:action c)))
      (expect (= "https://example.com" (:url c)))
      (expect (false? (:headless f)))
      (expect (true? (:screenshot c)))))

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

    (it "parses snapshot --interactive (consumed as global flag)"
      (let [r (sut/parse-args ["snapshot" "--interactive"])
            c (:command r)
            f (:flags r)]
        (expect (= "snapshot" (:action c)))
        (expect (false? (:headless f)))))

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

    (it "parses snapshot -F flat flag"
      (let [c (cmd ["snapshot" "-F"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:flat c)))))

    (it "parses snapshot --flat flag"
      (let [c (cmd ["snapshot" "--flat"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:flat c)))))

    (it "parses snapshot with combined flags"
      (let [c (cmd ["snapshot" "-i" "-c" "-d" "3" "-s" "#main" "--flat"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:interactive c)))
        (expect (true? (:compact c)))
        (expect (= 3 (:depth c)))
        (expect (= "#main" (:selector c)))
        (expect (true? (:flat c)))))))

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
        (expect (= (abs-path "shot.png") (:path c)))))

    (it "parses screenshot without path"
      (let [c (cmd ["screenshot"])]
        (expect (= "screenshot" (:action c)))
        (expect (nil? (:path c)))))

    (it "parses screenshot with full-page flag"
      (let [c (cmd ["screenshot" "shot.png" "-f"])]
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "shot.png") (:path c)))
        (expect (true? (:fullPage c)))))

    (it "parses screenshot with --crop-to-content flag"
      (let [c (cmd ["screenshot" "shot.png" "--crop-to-content"])]
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "shot.png") (:path c)))
        (expect (true? (:cropToContent c)))))

    (it "parses screenshot with both -f and --crop-to-content"
      (let [c (cmd ["screenshot" "shot.png" "-f" "--crop-to-content"])]
        (expect (= "screenshot" (:action c)))
        (expect (true? (:fullPage c)))
        (expect (true? (:cropToContent c)))))

    (it "does not include cropToContent when flag not given"
      (let [c (cmd ["screenshot" "shot.png"])]
        (expect (nil? (:cropToContent c)))))

    (it "parses --session with screenshot path"
      (let [r (sut/parse-args ["--session" "mysess" "screenshot" "some_path/screenshot.png"])
            f (:flags r)
            c (:command r)]
        (expect (= "mysess" (:session f)))
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "some_path/screenshot.png") (:path c)))))

    (it "parses --session=value with screenshot"
      (let [r (sut/parse-args ["--session=work" "screenshot" "out.png"])
            f (:flags r)
            c (:command r)]
        (expect (= "work" (:session f)))
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "out.png") (:path c)))))

    (it "parses --session with screenshot and -f flag"
      (let [r (sut/parse-args ["--session" "agent1" "screenshot" "dir/shot.png" "-f"])
            f (:flags r)
            c (:command r)]
        (expect (= "agent1" (:session f)))
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "dir/shot.png") (:path c)))
        (expect (true? (:fullPage c)))))

    (it "parses --json --session with screenshot"
      (let [r (sut/parse-args ["--json" "--session" "test" "screenshot" "out.png"])
            f (:flags r)
            c (:command r)]
        (expect (= "test" (:session f)))
        (expect (true? (:json f)))
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "out.png") (:path c))))))

  (describe "pdf"
    (it "parses pdf with path"
      (let [c (cmd ["pdf" "page.pdf"])]
        (expect (= "pdf" (:action c)))
        (expect (= (abs-path "page.pdf") (:path c)))))

    (it "defaults pdf path"
      (let [c (cmd ["pdf"])]
        (expect (= "pdf" (:action c)))
        (expect (= (abs-path "page.pdf") (:path c)))))))

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

    (it "parses annotate --dimensions"
      (let [c (cmd ["annotate" "--dimensions"])]
        (expect (= "annotate" (:action c)))
        (expect (true? (:show-dimensions c)))))

    (it "parses annotate --dims"
      (let [c (cmd ["annotate" "--dims"])]
        (expect (= "annotate" (:action c)))
        (expect (true? (:show-dimensions c)))))

    (it "leaves dimensions off unless asked"
      (let [c (cmd ["annotate"])]
        (expect (nil? (:show-dimensions c)))))

    (it "parses annotate --no-boxes"
      (let [c (cmd ["annotate" "--no-boxes"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-boxes c)))))

    (it "parses annotate with all options disabled"
      (let [c (cmd ["annotate" "--no-badges" "--no-boxes"])]
        (expect (= "annotate" (:action c)))
        (expect (false? (:show-badges c)))
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
    (it "parses eval-js with script"
      (let [c (cmd ["eval-js" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (= "document.title" (:script c)))))

    (it "joins multi-word scripts"
      (let [c (cmd ["eval-js" "1" "+" "2"])]
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
      (let [c (cmd ["tab" "new" "https://example.org"])]
        (expect (= "tab_new" (:action c)))
        (expect (= "https://example.org" (:url c)))))

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
      (expect (= {:action "close"} (cmd ["close"]))))

    (it "parses --session with close"
      (let [r (sut/parse-args ["--session" "mysess" "close"])
            f (:flags r)
            c (:command r)]
        (expect (= "mysess" (:session f)))
        (expect (= "close" (:action c)))))

    (it "parses --session=value with close"
      (let [r (sut/parse-args ["--session=work" "close"])
            f (:flags r)
            c (:command r)]
        (expect (= "work" (:session f)))
        (expect (= "close" (:action c)))))))

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

  (describe "--channel flag"
    (it "sets channel"
      (let [f (flags ["--channel" "msedge" "open" "http://x.com"])]
        (expect (= "msedge" (:channel f)))))

    (it "supports --channel=value syntax"
      (let [f (flags ["--channel=chrome-beta" "open" "http://x.com"])]
        (expect (= "chrome-beta" (:channel f)))))

    (it "combines --channel with --session"
      (let [r (sut/parse-args ["--channel" "msedge" "--session" "mysess" "screenshot" "path.png"])
            f (:flags r)
            c (:command r)]
        (expect (= "msedge" (:channel f)))
        (expect (= "mysess" (:session f)))
        (expect (= "screenshot" (:action c)))
        (expect (= (abs-path "path.png") (:path c)))))

    (it "combines --channel with --session and --browser"
      (let [r (sut/parse-args ["--channel" "msedge" "--session" "dev" "--browser" "chromium" "open" "http://x.com"])
            f (:flags r)
            c (:command r)]
        (expect (= "msedge" (:channel f)))
        (expect (= "dev" (:session f)))
        (expect (= "chromium" (:browser f)))
        (expect (= "navigate" (:action c))))))

  (describe "em-dash / en-dash normalization"
    (it "normalizes em-dash —session to --session"
      (let [f (flags ["—session" "test" "open" "http://x.com"])]
        (expect (= "test" (:session f)))))

    (it "normalizes em-dash —session=value to --session=value"
      (let [f (flags ["—session=mysess" "open" "http://x.com"])]
        (expect (= "mysess" (:session f)))))

    (it "normalizes en-dash –session to --session"
      (let [f (flags ["–session" "test" "open" "http://x.com"])]
        (expect (= "test" (:session f))))))

  (it "normalizes en-dash+hyphen \u2013-session to --session"
    (let [f (flags ["\u2013-session" "test" "open" "http://x.com"])]
      (expect (= "test" (:session f)))))

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
        (expect (= "http://localhost:9222" (:cdp f)))))

    (it "supports --cdp-url alias"
      (let [f (flags ["--cdp-url" "http://localhost:9222" "open" "http://x.com"])]
        (expect (= "http://localhost:9222" (:cdp f)))))

    (it "supports --cdp-url=value syntax"
      (let [f (flags ["--cdp-url=http://localhost:9222" "open" "http://x.com"])]
        (expect (= "http://localhost:9222" (:cdp f))))))

  (describe "--allow-file-access flag"
    (it "sets allow-file-access"
      (let [f (flags ["--allow-file-access" "open" "http://x.com"])]
        (expect (true? (:allow-file-access f))))))

  (describe "--no-persist flag"
    (it "sets no-persist"
      (let [f (flags ["--no-persist" "open" "http://x.com"])]
        (expect (true? (:no-persist f))))))

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
        (expect (= "{\"Auth\":\"Bearer\"}" (:headers f))))))

  (describe "--stealth / --no-stealth flags"
    (it "defaults stealth to true"
      (let [f (flags ["open" "http://x.com"])]
        (expect (true? (:stealth f)))))

    (it "--stealth explicitly sets stealth to true"
      (let [f (flags ["--stealth" "open" "http://x.com"])]
        (expect (true? (:stealth f)))))

    (it "--no-stealth disables stealth"
      (let [f (flags ["--no-stealth" "open" "http://x.com"])]
        (expect (false? (:stealth f))))))

  (describe "--load-state flag"
    (it "sets storage-state via --load-state"
      (let [f (flags ["--load-state" "/tmp/state.json" "open" "http://x.com"])]
        (expect (= "/tmp/state.json" (:storage-state f)))))

    (it "supports --load-state=value syntax"
      (let [f (flags ["--load-state=/tmp/state.json" "open" "http://x.com"])]
        (expect (= "/tmp/state.json" (:storage-state f)))))

    (it "is equivalent to --storage-state"
      (let [f1 (flags ["--load-state" "/tmp/s.json" "open" "http://x.com"])
            f2 (flags ["--storage-state" "/tmp/s.json" "open" "http://x.com"])]
        (expect (= (:storage-state f1) (:storage-state f2)))))

    (it "supports --extension=value syntax"
      (let [f (flags ["--extension=/tmp/my-ext" "open" "http://x.com"])]
        (expect (= ["/tmp/my-ext"] (:extensions f)))))

    (it "accumulates mixed --extension and --extension= syntax"
      (let [f (flags ["--extension" "./ext1" "--extension=./ext2" "open" "http://x.com"])]
        (expect (= ["./ext1" "./ext2"] (:extensions f)))))

    (it "defaults to nil when no --extension given"
      (let [f (flags ["open" "http://x.com"])]
        (expect (nil? (:extensions f)))))))

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

(defdescribe cdp-command-test
  "Tests for cdp disconnect/reconnect command parsing"

  (describe "cdp disconnect"
    (it "parses cdp disconnect"
      (let [c (cmd ["cdp" "disconnect"])]
        (expect (= "cdp_disconnect" (:action c))))))

  (describe "cdp reconnect"
    (it "parses cdp reconnect without URL"
      (let [c (cmd ["cdp" "reconnect"])]
        (expect (= "cdp_reconnect" (:action c)))
        (expect (nil? (:url c)))))

    (it "parses cdp reconnect with URL"
      (let [c (cmd ["cdp" "reconnect" "ws://localhost:9222"])]
        (expect (= "cdp_reconnect" (:action c)))
        (expect (= "ws://localhost:9222" (:url c)))))))

;; =============================================================================
;; Eval Flags
;; =============================================================================

(defdescribe eval-flags-test
  "Tests for eval flags"

  (describe "eval-js -b flag"
    (it "parses eval-js with base64 flag"
      (let [c (cmd ["eval-js" "-b" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (= "document.title" (:script c)))
        (expect (true? (:base64 c)))))

    (it "parses eval-js with --base64 flag"
      (let [c (cmd ["eval-js" "--base64" "document.title"])]
        (expect (= "evaluate" (:action c)))
        (expect (true? (:base64 c))))))

  (describe "eval-js --stdin flag"
    (it "parses eval-js with --stdin"
      (let [c (cmd ["eval-js" "--stdin"])]
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
;; Download
;; =============================================================================

(defdescribe download-test
  "Tests for download command"

  (describe "download with CSS selector"
    (it "parses download with selector and path"
      (let [c (cmd ["download" "#export-btn" "./report.csv"])]
        (expect (= "download" (:action c)))
        (expect (= "#export-btn" (:selector c)))
        (expect (= "./report.csv" (:save-path c))))))

  (describe "download with ref"
    (it "parses download with element ref"
      (let [c (cmd ["download" "@e5" "./file.zip"])]
        (expect (= "download" (:action c)))
        (expect (= "@e5" (:selector c)))
        (expect (= "./file.zip" (:save-path c))))))

  (describe "download with timeout"
    (it "parses download with --timeout flag"
      (let [c (cmd ["download" "--timeout" "5000" "#btn" "./out.pdf"])]
        (expect (= "download" (:action c)))
        (expect (= "#btn" (:selector c)))
        (expect (= "./out.pdf" (:save-path c)))
        (expect (= 5000 (:timeout-ms c)))))))

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
                        "drag" "upload" "download" "screenshot" "annotate" "unannotate" "pdf"
                        "eval-js" "wait" "tab" "get" "is" "count" "bbox" "highlight",
                        "find" "set" "cookies" "storage" "network" "frame" "dialog"
                        "trace" "console" "errors" "state" "session" "connect"
                        "close" "install" "inspector" "show-trace" "stitch"]]
        (expect (string? (get sut/command-help cmd-name))))))

  (describe "top-level-help"
    (it "returns a non-empty string"
      (expect (string? (sut/top-level-help)))
      (expect (pos? (count (sut/top-level-help)))))

    (it "contains key sections"
      (let [h (sut/top-level-help)]
        (expect (.contains ^String h "Navigation:"))
        (expect (.contains ^String h "Global Flags:"))
        (expect (.contains ^String h "Environment Variables"))))))

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
        (expect (= (abs-path "trace.zip") (:path c)))))))

;; =============================================================================
;; Console & Errors
;; =============================================================================

(defdescribe console-errors-test
  "Tests for console and errors commands"

  (describe "console"
    (it "parses console (no args) as console_list"
      (expect (= "console_list" (:action (cmd ["console"])))))

    (it "parses console get @c1 as console_get_ref"
      (let [c (cmd ["console" "get" "@c1"])]
        (expect (= "console_get_ref" (:action c)))
        (expect (= "@c1" (:ref c)))))

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
        (expect (= (abs-path "state.json") (:path c))))))

  (describe "state load"
    (it "parses state load"
      (let [c (cmd ["state" "load" "state.json"])]
        (expect (= "state_load" (:action c)))
        (expect (= (abs-path "state.json") (:path c))))))

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

  (describe "network list and get"
    (it "parses network (no args) as network_list"
      (expect (= "network_list" (:action (cmd ["network"])))))

    (it "parses network get @n1 as network_get_ref"
      (let [c (cmd ["network" "get" "@n1"])]
        (expect (= "network_get_ref" (:action c)))
        (expect (= "@n1" (:ref c))))))

  (describe "network unroute and requests"
    (it "parses network unroute"
      (let [c (cmd ["network" "unroute" "**/api/**"])]
        (expect (= "network_unroute" (:action c)))
        (expect (= "**/api/**" (:url c)))))

    (it "parses network unroute without url"
      (let [c (cmd ["network" "unroute"])]
        (expect (= "network_unroute" (:action c)))))

    ;; Regression, user report: the daemon's own cdp_route_lock hint tells the user to
    ;; run `network unroute all`, and that removed a route whose pattern was literally
    ;; "all" — every real route stayed installed and the endpoint stayed locked.
    (it "parses network unroute all as every route"
      (let [c (cmd ["network" "unroute" "all"])]
        (expect (= "network_unroute" (:action c)))
        (expect (nil? (:url c)))))
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

  (describe "open --interactive (global flag, sets headed mode)"
    (it "parses open --interactive"
      (let [r (sut/parse-args ["open" "https://example.org" "--interactive"])
            c (:command r)
            f (:flags r)]
        (expect (= "navigate" (:action c)))
        (expect (false? (:headless f))))))

  (describe "inspector command"
    (it "parses inspector"
      (let [c (cmd ["inspector"])]
        (expect (= "inspector" (:action c)))))

    (it "parses inspector with url"
      (let [c (cmd ["inspector" "https://example.org"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["https://example.org"] (:cli-args c)))))

    (it "parses inspector with browser flag"
      (let [c (cmd ["inspector" "-b" "firefox" "https://example.org"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["-b" "firefox" "https://example.org"] (:cli-args c)))))

    (it "parses inspector with target flag"
      ;; NOTE: `--device` cannot be used here — it is now a spel global flag
      ;; consumed during pre-parse, never reaching the inspector passthrough.
      ;; Use a neutral flag name to exercise the passthrough semantics.
      (let [c (cmd ["inspector" "--target" "chromium" "https://example.org"])]
        (expect (= "inspector" (:action c)))
        (expect (= ["--target" "chromium" "https://example.org"] (:cli-args c))))))

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
;; Native parse-global-flags (--autoclose, --session for eval-sci mode)
;; =============================================================================

(defdescribe native-global-flags-test
  "Tests for native.clj parse-global-flags (private)"

  (describe "--autoclose flag"
    (it "defaults to false"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (false? (:autoclose? g)))))

    (it "sets autoclose? to true"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--autoclose" "eval-sci" "(+ 1 2)"])]
        (expect (true? (:autoclose? g)))))

    (it "strips --autoclose from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--autoclose" "eval-sci" "(+ 1 2)"])]
        (expect (not (some #{"--autoclose"} (:command-args g)))))))

  (describe "--session flag"
    (it "defaults to nil"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (nil? (:session g)))))

    (it "parses --session <name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session" "mytest" "eval-sci" "(+ 1 2)"])]
        (expect (= "mytest" (:session g)))))

    (it "parses --session=<name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session=mytest" "eval-sci" "(+ 1 2)"])]
        (expect (= "mytest" (:session g)))))

    (it "strips --session from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--session" "work" "eval-sci" "(+ 1 2)"])]
        (expect (not (some #{"--session"} (:command-args g))))
        (expect (not (some #{"work"} (:command-args g)))))))

  (describe "em-dash / en-dash normalization (normalize-args + parse-global-flags)"
    (it "normalizes em-dash —session to --session"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               (com.blockether.spel.native/normalize-args ["—session" "work" "eval-sci" "(+ 1 2)"]))]
        (expect (= "work" (:session g)))))

    (it "normalizes em-dash —session=value to --session=value"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               (com.blockether.spel.native/normalize-args ["—session=work" "eval-sci" "(+ 1 2)"]))]
        (expect (= "work" (:session g)))))

    (it "strips em-dash —session from command-args after normalization"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               (com.blockether.spel.native/normalize-args ["—session" "work" "eval-sci" "(+ 1 2)"]))]
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "normalizes en-dash –session to --session"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               (com.blockether.spel.native/normalize-args ["–session" "work" "eval-sci" "(+ 1 2)"]))]
        (expect (= "work" (:session g)))))

    (it "normalizes en-dash+hyphen \u2013-session to --session"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               (com.blockether.spel.native/normalize-args ["\u2013-session" "work" "eval-sci" "(+ 1 2)"]))]
        (expect (= "work" (:session g))))))

  (describe "--interactive flag"
    (it "defaults to false"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (false? (:interactive? g)))))

    (it "sets interactive? to true"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--interactive" "eval-sci" "(+ 1 2)"])]
        (expect (true? (:interactive? g)))))

    (it "strips --interactive from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--interactive" "eval-sci" "(+ 1 2)"])]
        (expect (not (some #{"--interactive"} (:command-args g)))))))

  (describe "combined flags"
    (it "parses --autoclose --session --timeout together"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--autoclose" "--session" "dev" "--timeout" "5000" "eval-sci" "(+ 1 2)"])]
        (expect (true? (:autoclose? g)))
        (expect (= "dev" (:session g)))
        (expect (= 5000 (:timeout-ms g)))))

    (it "parses --interactive with other flags"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--interactive" "--autoclose" "--session" "dev" "eval-sci" "(+ 1 2)"])]
        (expect (true? (:interactive? g)))
        (expect (true? (:autoclose? g)))
        (expect (= "dev" (:session g))))))

  (describe "--browser flag"
    (it "defaults to SPEL_BROWSER env when no CLI override"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (= (System/getenv "SPEL_BROWSER") (:browser g)))))

    (it "parses --browser <type>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--browser" "firefox" "eval-sci" "(+ 1 2)"])]
        (expect (= "firefox" (:browser g)))))

    (it "parses --browser=<type>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--browser=webkit" "eval-sci" "(+ 1 2)"])]
        (expect (= "webkit" (:browser g)))))

    (it "strips --browser from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--browser" "firefox" "eval-sci" "(+ 1 2)"])]
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "combines --browser with --autoclose and --session"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--browser" "firefox" "--autoclose" "--session" "dev" "eval-sci" "(+ 1 2)"])]
        (expect (= "firefox" (:browser g)))
        (expect (true? (:autoclose? g)))
        (expect (= "dev" (:session g))))))

  (describe "--profile flag"
    (it "defaults to SPEL_PROFILE env when no CLI override"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (= (System/getenv "SPEL_PROFILE") (:profile g)))))

    (it "parses --profile <path>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--profile" "/path/to/chrome" "eval-sci" "(+ 1 2)"])]
        (expect (= "/path/to/chrome" (:profile g)))))

    (it "parses --profile=<path>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--profile=/path/to/chrome" "eval-sci" "(+ 1 2)"])]
        (expect (= "/path/to/chrome" (:profile g)))))

    (it "strips --profile from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--profile" "/path/to/chrome" "eval-sci" "(+ 1 2)"])]
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "preserves eval-sci as first command-arg when --profile precedes it"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--profile" "/path/to/chrome" "eval-sci" "(do (println :ok))"])]
        (expect (= "/path/to/chrome" (:profile g)))
        (expect (= "eval-sci" (first (:command-args g))))))

    (it "handles paths with spaces"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--profile" "/Users/user/Library/Application Support/Google/Chrome" "eval-sci" "(+ 1 2)"])]
        (expect (= "/Users/user/Library/Application Support/Google/Chrome" (:profile g)))
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "combines --profile with --browser and --session"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--profile" "/path/to/chrome" "--browser" "chromium" "--session" "dev" "eval-sci" "(+ 1 2)"])]
        (expect (= "/path/to/chrome" (:profile g)))
        (expect (= "chromium" (:browser g)))
        (expect (= "dev" (:session g))))))

  (describe "--cdp flag"
    (it "defaults to SPEL_CDP env"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (= (System/getenv "SPEL_CDP") (:cdp g)))))

    (it "parses --cdp <url>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--cdp" "http://localhost:9222" "eval-sci" "(+ 1 2)"])]
        (expect (= "http://localhost:9222" (:cdp g)))))

    (it "parses --cdp=<url>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--cdp=http://localhost:9222" "eval-sci" "(+ 1 2)"])]
        (expect (= "http://localhost:9222" (:cdp g)))))

    (it "strips --cdp from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--cdp" "http://localhost:9222" "eval-sci" "(+ 1 2)"])]
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "combines --profile --cdp for Chrome profile with CDP"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--profile" "/path/to/chrome" "--cdp" "http://localhost:9222" "eval-sci" "(+ 1 2)"])]
        (expect (= "/path/to/chrome" (:profile g)))
        (expect (= "http://localhost:9222" (:cdp g)))
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g))))))

  (describe "--channel flag"
    (it "defaults to SPEL_CHANNEL env"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["eval-sci" "(+ 1 2)"])]
        (expect (= (System/getenv "SPEL_CHANNEL") (:channel g)))))

    (it "parses --channel <name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--channel" "msedge" "eval-sci" "(+ 1 2)"])]
        (expect (= "msedge" (:channel g)))))

    (it "parses --channel=<name>"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--channel=chrome-beta" "eval-sci" "(+ 1 2)"])]
        (expect (= "chrome-beta" (:channel g)))))

    (it "strips --channel from command-args"
      (let [g (#'com.blockether.spel.native/parse-global-flags ["--channel" "msedge" "eval-sci" "(+ 1 2)"])]
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))

    (it "combines --channel with --session and --browser"
      (let [g (#'com.blockether.spel.native/parse-global-flags
               ["--channel" "msedge" "--session" "dev" "--browser" "chromium" "eval-sci" "(+ 1 2)"])]
        (expect (= "msedge" (:channel g)))
        (expect (= "dev" (:session g)))
        (expect (= "chromium" (:browser g)))
        (expect (= ["eval-sci" "(+ 1 2)"] (:command-args g)))))))

;; =============================================================================
;; eval-sci --json output
;; =============================================================================

(defn- json-line
  "Shorthand: the one JSON object --json eval-sci writes for a response."
  [response]
  (#'com.blockether.spel.native/eval-json-line response))

;; Regression, user report: --json did nothing for eval-sci. The daemon's EDN
;; pr-str went to stdout unchanged, so `{:a 1}` reached the parser as Clojure
;; and anything the script printed landed beside the payload.
(defdescribe eval-sci-json-output-test
  "Tests for native.clj eval-json-line (private) — the --json eval-sci payload"

  (describe "a successful evaluation"
    (it "answers the evaluated value as JSON data, not EDN"
      (expect (= "{\"result\":{\"a\":1,\"b\":[1,2],\"c\":\"x\"}}"
                (json-line {:success true
                            :data {:result "{:a 1, :b [1 2], :c \"x\"}"
                                   :result-data {"a" 1 "b" [1 2] "c" "x"}}}))))

    (it "answers a scalar result"
      (expect (= "{\"result\":\"Example Domain\"}"
                (json-line {:success true
                            :data {:result "\"Example Domain\""
                                   :result-data "Example Domain"}}))))

    (it "answers null for a nil result"
      (expect (= "{\"result\":null}"
                (json-line {:success true :data {:result "nil" :result-data nil}}))))

    (it "folds what the script printed into the same object"
      (expect (= "{\"result\":42,\"stdout\":\"hello\\n\"}"
                (json-line {:success true
                            :data {:result "42" :result-data 42 :stdout "hello\n"}}))))

    (it "answers the EDN string when a daemon sent no projection"
      (expect (= "{\"result\":\"{:a 1}\"}"
                (json-line {:success true :data {:result "{:a 1}"}})))))

  (describe "a failed evaluation"
    (it "answers an error object carrying the daemon detail"
      (expect (= (str "{\"error\":\"Ref @e1 not found.\","
                   "\"hint\":\"Take a fresh snapshot.\","
                   "\"error_code\":\"ref_stale\"}")
                (json-line {:success false
                            :data {:error "Ref @e1 not found."}
                            :hint "Take a fresh snapshot."
                            :error_code "ref_stale"}))))

    (it "keeps stdout printed before the failure inside the object"
      (expect (= "{\"error\":\"boom\",\"stdout\":\"step 1\\n\"}"
                (json-line {:success false :data {:error "boom" :stdout "step 1\n"}}))))

    (it "names the failure when the daemon said nothing about it"
      (expect (str/includes? (json-line {:success false :data {}})
                "\"error\":\"unexpected browser error")))))

;; =============================================================================
;; eval-sci text output and the flags an eval carries
;; =============================================================================

(defn- error-lines
  "Shorthand: the stderr lines eval-sci writes for a failed response."
  [response]
  (#'com.blockether.spel.native/eval-error-lines response))

(defn- eval-flags
  "Shorthand: the `_flags` an eval-sci command carries to the daemon."
  [global]
  (#'com.blockether.spel.native/eval-daemon-flags global))

;; Regression, user report: `spel eval-sci` printed the error and swallowed the
;; hint the very same failure answered under --json, so the reader at the
;; terminal was the only one who never saw the sentence naming the way out.
(defdescribe eval-sci-text-output-test
  "Tests for native.clj eval-error-lines (private) — a failed eval without --json"

  (describe "a failed evaluation"
    (it "prints the hint the same failure carries under --json"
      (expect (= ["Error: Timeout 1000ms exceeded."
                  "Hint: Verify the selector and consider raising --timeout."]
                (error-lines {:success false
                              :data {:error "Timeout 1000ms exceeded."}
                              :hint "Verify the selector and consider raising --timeout."
                              :error_code "timeout"}))))

    (it "prints the error alone when the daemon offered no hint"
      (expect (= ["Error: boom"]
                (error-lines {:success false :data {:error "boom"}}))))

    (it "falls back to the response-level error"
      (expect (= ["Error: no response from the spel daemon"]
                (error-lines {:success false :error "no response from the spel daemon"}))))

    (it "names the failure when the daemon said nothing about it"
      (expect (str/includes? (first (error-lines {:success false :data {}}))
                "unexpected browser error")))))

;; Regression, user report: `spel --timeout 800 eval-sci ...` still waited
;; Playwright's 10s. The flag configured a SCI env inside the CLI process while
;; the daemon holding the page was never told what the user asked for.
(defdescribe eval-sci-daemon-flags-test
  "Tests for native.clj eval-daemon-flags (private) — the _flags an eval carries"

  (describe "--timeout"
    (it "rides along as the daemon's action timeout"
      (expect (= {"timeout" 800} (eval-flags {:timeout-ms 800}))))

    (it "is absent when the user asked for none"
      (expect (= {} (eval-flags {})))))

  (describe "launch flags"
    (it "keeps browser, channel, profile and cdp beside it"
      (expect (= {"browser" "firefox" "channel" "chrome" "profile" "/tmp/p"
                  "cdp" "http://127.0.0.1:9222" "timeout" 5000 "auto-launch" true}
                (eval-flags {:browser "firefox" :channel "chrome" :profile "/tmp/p"
                             :cdp "http://127.0.0.1:9222" :timeout-ms 5000
                             :auto-launch true}))))

    (it "carries the provider selection parse-global-flags consumed"
      (expect (= {"provider" "ios"} (eval-flags {:cli-flags {"provider" "ios"}}))))))

;; =============================================================================
;; merge-reports arg parsing
;; =============================================================================

(defdescribe merge-reports-args-test
  "Tests for parse-merge-reports-args"

  (it "parses positional directories"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["results-a" "results-b"])]
      (expect (= ["results-a" "results-b"] dirs))
      (expect (empty? opts))))

  (it "parses --output= flag"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--output=combined" "dir2"])]
      (expect (= ["dir1" "dir2"] dirs))
      (expect (= "combined" (:output-dir opts)))))

  (it "parses --output flag with space"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--output" "combined"])]
      (expect (= ["dir1"] dirs))
      (expect (= "combined" (:output-dir opts)))))

  (it "parses --report-dir= flag"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--report-dir=my-report"])]
      (expect (= ["dir1"] dirs))
      (expect (= "my-report" (:report-dir opts)))))

  (it "parses --no-report flag"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--no-report"])]
      (expect (= ["dir1"] dirs))
      (expect (false? (:report opts)))))

  (it "parses --no-clean flag"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--no-clean"])]
      (expect (= ["dir1"] dirs))
      (expect (false? (:clean opts)))))

  (it "parses all options together"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "dir2" "--output=merged" "--report-dir=report"
                                "--no-clean" "dir3"])]
      (expect (= ["dir1" "dir2" "dir3"] dirs))
      (expect (= "merged" (:output-dir opts)))
      (expect (= "report" (:report-dir opts)))
      (expect (false? (:clean opts)))))

  (it "returns empty dirs for no args"
    (let [{:keys [dirs]} (#'com.blockether.spel.native/parse-merge-reports-args [])]
      (expect (empty? dirs))))

  (it "parses --renderer flag with space"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--renderer" "alternative"])]
      (expect (= ["dir1"] dirs))
      (expect (= :alternative (:renderer opts)))))

  (it "parses --renderer= flag"
    (let [{:keys [dirs opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                               ["dir1" "--renderer=alternative"])]
      (expect (= ["dir1"] dirs))
      (expect (= :alternative (:renderer opts)))))

  (it "defaults to no renderer when not specified"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-merge-reports-args
                          ["dir1"])]
      (expect (nil? (:renderer opts))))))

;; =============================================================================
;; Report CLI Arg Parsing Tests
;; =============================================================================

(defdescribe report-args-test
  "Tests for parse-report-args"

  (it "parses --renderer flag with space"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--renderer" "alternative"])]
      (expect (= "alternative" (:renderer opts)))))

  (it "parses --renderer= flag"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--renderer=alternative"])]
      (expect (= "alternative" (:renderer opts)))))

  (it "parses --renderer allure"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--renderer" "allure"])]
      (expect (= "allure" (:renderer opts)))))

  (it "defaults to no renderer when not specified"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args [])]
      (expect (nil? (:renderer opts)))))

  (it "parses --results-dir"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--results-dir" "my-results"])]
      (expect (= "my-results" (:results-dir opts)))))

  (it "parses --results-dir= form"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--results-dir=my-results"])]
      (expect (= "my-results" (:results-dir opts)))))

  (it "parses --output-dir"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--output-dir" "my-report"])]
      (expect (= "my-report" (:output-dir opts)))))

  (it "parses --from-json"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--from-json" "results.json"])]
      (expect (= "results.json" (:from-json opts)))))

  (it "parses --title"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--title" "My Report"])]
      (expect (= "My Report" (:title opts)))))

  (it "parses --help"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args ["--help"])]
      (expect (true? (:help opts)))))

  (it "parses all options together"
    (let [{:keys [opts]} (#'com.blockether.spel.native/parse-report-args
                          ["--renderer" "alternative" "--results-dir" "res"
                           "--output-dir" "out" "--title" "Test" "--kicker" "CI"])]
      (expect (= "alternative" (:renderer opts)))
      (expect (= "res" (:results-dir opts)))
      (expect (= "out" (:output-dir opts)))
      (expect (= "Test" (:title opts)))
      (expect (= "CI" (:kicker opts))))))

;; =============================================================================
;; Stitch CLI Tests
;; =============================================================================

(defdescribe stitch-test
  "Tests for the stitch CLI command"

  (describe "command-help entry"
    (it "has help text for stitch"
      (expect (string? (get sut/command-help "stitch"))))

    (it "help text mentions overlap"
      (expect (.contains ^String (get sut/command-help "stitch") "overlap")))))

(defdescribe snapshot-styles-flag-test
  "Tests for snapshot styles flag parsing"

  (describe "snapshot styles options"
    (it "snapshot -S sets styles true"
      (let [c (cmd ["snapshot" "-S"])]
        (expect (= "snapshot" (:action c)))
        (expect (true? (:styles c)))))

    (it "snapshot --styles sets styles true"
      (let [c (cmd ["snapshot" "--styles"])]
        (expect (true? (:styles c)))))

    (it "snapshot -S --minimal sets styles and minimal detail"
      (let [c (cmd ["snapshot" "-S" "--minimal"])]
        (expect (true? (:styles c)))
        (expect (= "minimal" (:styles_detail c)))))

    (it "snapshot -S --max sets max detail"
      (let [c (cmd ["snapshot" "-S" "--max"])]
        (expect (true? (:styles c)))
        (expect (= "max" (:styles_detail c)))))

    (it "snapshot without -S has no styles key"
      (let [c (cmd ["snapshot"])]
        (expect (not (:styles c)))))

    (it "snapshot -S -s body sets styles and selector"
      (let [c (cmd ["snapshot" "-S" "-s" "body"])]
        (expect (true? (:styles c)))
        (expect (= "body" (:selector c)))))))

;; =============================================================================
;; Diff CLI
;; =============================================================================

(defdescribe diff-cli-test
  "Tests for diff command parsing"

  (describe "diff screenshot parsing"
    (it "parses diff screenshot baseline"
      (let [c (cmd ["diff" "screenshot" "--baseline" "before.png"])]
        (expect (= "diff_screenshot" (:action c)))
        (expect (= (abs-path "before.png") (:baseline c)))))

    (it "parses diff screenshot threshold and output path"
      (let [c (cmd ["diff" "screenshot" "--baseline" "before.png" "--threshold" "0.03" "-o" "out.png"])]
        (expect (= "diff_screenshot" (:action c)))
        (expect (= (abs-path "before.png") (:baseline c)))
        (expect (= "0.03" (:threshold c)))
        (expect (= (abs-path "out.png") (:path c)))))))

;; =============================================================================
;; Typographic Dash Normalization (native.clj normalize-arg / normalize-args)
;; =============================================================================

(defdescribe normalize-args-test
  "Tests for typographic dash normalization in native.clj"

  (describe "normalize-arg"
    (it "passes normal double-dash through unchanged"
      (expect (= "--session"
                (#'com.blockether.spel.native/normalize-arg "--session"))))

    (it "replaces leading em-dash with double-hyphen"
      (expect (= "--session"
                (#'com.blockether.spel.native/normalize-arg "—session"))))

    (it "replaces leading en-dash with double-hyphen"
      (expect (= "--session"
                (#'com.blockether.spel.native/normalize-arg "–session"))))

    (it "handles en-dash + hyphen (\u2013-session → --session)"
      (expect (= "--session"
                (#'com.blockether.spel.native/normalize-arg "\u2013-session"))))

    (it "does not modify non-flag arguments"
      (expect (= "https://example.com"
                (#'com.blockether.spel.native/normalize-arg "https://example.com"))))

    (it "does not modify arguments without leading dashes"
      (expect (= "open"
                (#'com.blockether.spel.native/normalize-arg "open"))))

    (it "handles em-dash for --timeout"
      (expect (= "--timeout"
                (#'com.blockether.spel.native/normalize-arg "—timeout"))))

    (it "handles em-dash for --json"
      (expect (= "--json"
                (#'com.blockether.spel.native/normalize-arg "—json"))))

    (it "handles em-dash for --browser"
      (expect (= "--browser"
                (#'com.blockether.spel.native/normalize-arg "—browser"))))

    (it "handles em-dash with =value syntax"
      (expect (= "--session=mytest"
                (#'com.blockether.spel.native/normalize-arg "—session=mytest")))))

  (describe "normalize-args"
    (it "normalizes all args in a vector"
      (expect (= ["--session" "test" "open" "http://x.com"]
                (com.blockether.spel.native/normalize-args
                  ["—session" "test" "open" "http://x.com"]))))

    (it "handles mixed normal and typographic dashes"
      (expect (= ["--json" "--session" "dev" "open" "http://x.com"]
                (com.blockether.spel.native/normalize-args
                  ["—json" "--session" "dev" "open" "http://x.com"]))))

    (it "passes through when no typographic dashes present"
      (expect (= ["--session" "test" "open" "http://x.com"]
                (com.blockether.spel.native/normalize-args
                  ["--session" "test" "open" "http://x.com"]))))))

;; =============================================================================
;; Audit Subcommand Tests
;; =============================================================================

(defdescribe utility-command-test
  "Tests for utility CLI commands"

  (describe "find-free-port"
    (it "parses find-free-port command"
      (expect (= {:action "find_free_port"}
                (cmd ["find-free-port"]))))))

(defdescribe markdownify-command-test
  "Tests for markdownify CLI parsing"

  (it "parses markdownify with no args"
    (expect (= {:action "markdownify" :title true :readable true}
              (cmd ["markdownify"]))))

  (it "parses markdownify --url"
    (expect (= {:action "markdownify" :url "https://example.com" :title true :readable true}
              (cmd ["markdownify" "--url" "https://example.com"]))))

  (it "parses markdownify --file as absolute path"
    (expect (= {:action "markdownify" :file (abs-path "page.html") :title true :readable true}
              (cmd ["markdownify" "--file" "page.html"]))))

  (it "parses markdownify --input"
    (expect (= {:action "markdownify" :input "<h1>Hello</h1>" :title true :readable true}
              (cmd ["markdownify" "--input" "<h1>Hello</h1>"]))))

  (it "parses markdownify readability and title flags"
    (expect (= {:action "markdownify" :url "https://example.com" :title false :readable false}
              (cmd ["markdownify" "--url" "https://example.com" "--full" "--no-title"])))))

;; =============================================================================
;; daemon-failure-report — telling the user WHY the daemon stopped answering
;; =============================================================================

(defn- dfr-session
  "A unique session name, so each case owns its own log file under tmp."
  []
  (str "cli-test-dfr-" (System/nanoTime)))

(defn- with-daemon-log
  "Writes `lines` as the session's log file, calls `f`, then deletes the file."
  [session lines f]
  (let [file (io/file (str (log/log-file-path session)))]
    (try
      (spit file (str/join "\n" lines))
      (f)
      (finally (io/delete-file file true)))))

(defdescribe daemon-failure-report-test
  "The stderr message for a command that never got a daemon response.

   A daemon that dies mid-command used to surface as a bare 'Could not connect
   to daemon', which hides both the reason — the daemon records it on the way
   out — and the cost: every silent reconnect starts a FRESH browser, so the
   page, cookies and refs the script was using are gone."

  (describe "transport cause"
    (it "names the EOF case when there is no exception"
      (expect (str/includes? (sut/daemon-failure-report (dfr-session) nil 0)
                "cause:  daemon closed the connection without answering")))

    (it "includes the timeout budget carried in ex-data"
      (let [e (ex-info "daemon did not respond" {:timeout-ms 5000})]
        (expect (str/includes? (sut/daemon-failure-report (dfr-session) e 0)
                  "cause:  daemon did not respond after 5000ms"))))

    (it "falls back to the exception class for a plain throwable"
      (let [e (java.io.IOException. "broken pipe")]
        (expect (str/includes? (sut/daemon-failure-report (dfr-session) e 0)
                  "cause:  IOException: broken pipe")))))

  (describe "why the daemon went away"
    (it "calls it a crash when the log records no shutdown"
      (expect (str/includes? (sut/daemon-failure-report (dfr-session) nil 0)
                "process is gone and logged no shutdown")))

    (it "reads the shutdown reason back from the daemon's own log"
      (let [s (dfr-session)]
        (with-daemon-log s
          ["12:00:00 INFO daemon starting session=x"
           "12:00:30 INFO daemon stopping session=x reason=session idle timeout (30s) — no commands received"]
          (fn []
            (let [out (sut/daemon-failure-report s nil 0)]
              (expect (str/includes? out "daemon: exited — session idle timeout (30s)"))
              (expect (str/includes? out "SPEL_SESSION_IDLE_TIMEOUT")))))))

    (it "ignores a shutdown older than the last start"
      (let [s (dfr-session)]
        (with-daemon-log s
          ["11:00:00 INFO daemon stopping session=x reason=client requested close"
           "12:00:00 INFO daemon starting session=x"]
          (fn []
            (let [out (sut/daemon-failure-report s nil 0)]
              (expect (str/includes? out "process is gone and logged no shutdown"))
              (expect (not (str/includes? out "client requested close"))))))))

    (it "offers the idle-timeout knob only for an idle shutdown"
      (let [s (dfr-session)]
        (with-daemon-log s
          ["12:00:00 INFO daemon starting session=x"
           "12:00:01 INFO daemon stopping session=x reason=client requested close"]
          (fn []
            (let [out (sut/daemon-failure-report s nil 0)]
              (expect (str/includes? out "daemon: exited — client requested close"))
              (expect (not (str/includes? out "SPEL_SESSION_IDLE_TIMEOUT")))))))))

  (describe "cost of the silent reconnects"
    (it "says nothing about retries when there were none"
      (expect (not (str/includes? (sut/daemon-failure-report (dfr-session) nil 0) "tried:"))))

    (it "spells out that every retry resets the browser"
      (let [out (sut/daemon-failure-report (dfr-session) nil 3)]
        (expect (str/includes? out "tried:  3 reconnects"))
        (expect (str/includes? out "FRESH browser"))))

    (it "keeps reconnect singular for a single attempt"
      (expect (str/includes? (sut/daemon-failure-report (dfr-session) nil 1)
                "tried:  1 reconnect —"))))

  (describe "where to look next"
    (it "points at the session log file and the logs command"
      (let [s   (dfr-session)
            out (sut/daemon-failure-report s nil 0)]
        (expect (str/starts-with? out
                  (str "Error: no response from the spel daemon (session '" s "')")))
        (expect (str/includes? out (str "log:    " (log/log-file-path s))))
        (expect (str/includes? out (str "hint:   spel --session " s " logs")))))))

;; =============================================================================
;; daemon-health — the one answer a wedged daemon can still give
;; =============================================================================

(defn- with-live-pid
  "Presents THIS JVM as a verified daemon without spawning a browser."
  [session f]
  (let [pid  (str (.pid (java.lang.ProcessHandle/current)))
        file (io/file (str (daemon/pid-file-path session)))]
    (try
      (spit file pid)
      (with-redefs [sut/daemon-process-at-pid (fn [_] {:pid pid :session session})
                    sut/orphan-daemon-processes (constantly [])]
        (f))
      (finally (io/delete-file file true)))))

(defdescribe daemon-health-test
  "`spel health` has to answer for a daemon that is down, silent, or working —
   the probe is injected so every branch is proven without a browser.

   A daemon busy inside a long browser call used to be indistinguishable from a
   dead one: the client timed out, restarted the daemon, and the live browser
   went with it."

  (describe "no daemon process"
    (it "reports down without ever probing"
      (let [calls (atom 0)
            h     (sut/daemon-health (dfr-session) (fn [] (swap! calls inc) nil))]
        (expect (= "down" (:status h)))
        (expect (zero? @calls))))

    (it "repeats why the daemon left, straight from its log"
      (let [s (dfr-session)]
        (with-daemon-log s
          ["12:00:00 INFO daemon starting session=x"
           "12:30:00 INFO daemon stopping session=x reason=session idle timeout (30 min) — no commands received"]
          (fn []
            (let [h (sut/daemon-health s (fn [] nil))]
              (expect (= "down" (:status h)))
              (expect (str/includes? (:last_exit h) "idle timeout"))
              (expect (str/includes? (sut/health-report h)
                        "last exit: session idle timeout"))))))))

  (describe "process alive"
    (it "passes the daemon's own verdict through, with session and log attached"
      (let [s (dfr-session)]
        (with-live-pid s
          (fn []
            (let [h (sut/daemon-health s (fn [] {:success true
                                                 :data {:status "busy"
                                                        :uptime "2 min"
                                                        :commands_total 41}}))]
              (expect (= "busy" (:status h)))
              (expect (= s (:session h)))
              (expect (= (str (log/log-file-path s)) (:log h))))))))

    (it "calls a silent daemon unresponsive and names the transport failure"
      (let [s (dfr-session)]
        (with-live-pid s
          (fn []
            (let [h (sut/daemon-health s (fn [] (throw (ex-info "Daemon response timed out"
                                                         {:timeout-ms 3000}))))]
              (expect (= "unresponsive" (:status h)))
              (expect (str/includes? (:cause h) "timed out after 3000ms"))
              (expect (str/includes? (:hint h) "kill")))))))

    (it "treats a closed connection (no answer at all) as unresponsive too"
      (let [s (dfr-session)]
        (with-live-pid s
          (fn []
            (let [h (sut/daemon-health s (fn [] nil))]
              (expect (= "unresponsive" (:status h)))
              (expect (str/includes? (:cause h) "without answering")))))))))

(defdescribe health-report-test
  "The terminal rendering: one status line plus only the facts that decide what
   to do next."

  (it "leads with status, uptime and command count"
    (let [out (sut/health-report {:status "ok" :session "agent1" :uptime "2 min"
                                  :commands_total 41 :in_flight []})]
      (expect (str/starts-with? out "agent1: ok — up 2 min, 41 commands"))
      (expect (str/includes? out "in flight: none"))))

  (it "names every in-flight command and how long it has been running"
    (let [out (sut/health-report {:status "busy" :session "agent1" :uptime "5 min"
                                  :commands_total 3
                                  :in_flight [{:id "c7" :action "evaluate" :running "45s"}]})]
      (expect (str/includes? out "in flight: c7 evaluate (45s)"))))

  (it "says plainly when the browser is gone but the daemon is fine"
    (let [out (sut/health-report {:status "degraded" :session "agent1" :uptime "5 min"
                                  :commands_total 3 :in_flight []
                                  :browser {:launched true :connected false
                                            :page_open false :type "chromium"
                                            :headless true}})]
      (expect (str/includes? out "browser:   chromium headless, GONE"))
      (expect (str/includes? out "relaunches on the next command"))))

  (it "reports a live browser with its page"
    (let [out (sut/health-report {:status "ok" :session "a" :uptime "1s" :commands_total 1
                                  :in_flight []
                                  :browser {:launched true :connected true :page_open true
                                            :page_url "https://example.org" :type "chromium"
                                            :headless false}})]
      (expect (str/includes? out "chromium headed, connected, page open"))))

  ;; Regression, issue #125: a browser relaunched after it went away sits on
  ;; about:blank, and `health` called that "connected, page open" while every page
  ;; command answered "No page loaded" — nothing told the caller the page was gone.
  (it "calls a blank page blank instead of an open one"
    (let [out (sut/health-report {:status "ok" :session "a" :uptime "1s" :commands_total 1
                                  :in_flight []
                                  :browser {:launched true :connected true :page_open true
                                            :page_url nil :type "chromium" :headless true}})]
      (expect (str/includes? out "connected, blank page — no URL loaded yet"))))

  ;; Regression, issue #125: a daemon whose response handler threw on every
  ;; event still reported a healthy session, so the caller kept driving a page
  ;; whose console and network capture were silently dead.
  (it "names a failing event handler and what to do about it"
    (let [out (sut/health-report {:status "degraded" :session "a" :uptime "9 min"
                                  :commands_total 12 :in_flight []
                                  :handler_errors [{:label "response" :count 799
                                                    :error "java.lang.StackOverflowError"}]})]
      (expect (str/includes? out "handlers:  response ×799 java.lang.StackOverflowError"))
      (expect (str/includes? out "close this session"))))

  ;; Regression, issue #125: a command that never came back left the session
  ;; answering `daemon is busy` for a command health did not list at all.
  (it "names a command the daemon gave up on"
    (let [out (sut/health-report {:status "degraded" :session "a" :uptime "3 min"
                                  :commands_total 25 :in_flight []
                                  :lost_commands [{:id "c25" :action "reload"}]})]
      (expect (str/includes? out "lost:      reload (c25)"))
      (expect (str/includes? out "the next command opens a fresh one"))))

  ;; Regression, issue #127: a page whose renderer died still read "connected,
  ;; page open" while every command answered "Target crashed", so the caller
  ;; retried into a tab that no longer existed.
  (it "says the renderer crashed instead of calling the page open"
    (let [out (sut/health-report {:status "degraded" :session "a" :uptime "4 min"
                                  :commands_total 8 :in_flight []
                                  :browser {:launched true :connected true :page_open false
                                            :page_crashed true :page_url "https://example.org"
                                            :type "chromium" :headless true}})]
      (expect (str/includes? out "renderer CRASHED"))
      (expect (str/includes? out "opens a fresh tab"))))

  (it "omits the in-flight line for a daemon that is not running"
    (let [out (sut/health-report {:status "down" :session "a" :log "/tmp/spel-a.log"})]
      (expect (str/includes? out "a: down — no daemon process"))
      (expect (not (str/includes? out "in flight"))))))

(defdescribe daemon-process-entry-test
  "A daemon whose socket and PID file are gone cannot be found by name, so
   `kill --all-sessions` asks the OS for spel daemon processes instead. That
   match has to be tight in BOTH directions: too narrow leaves an unkillable
   daemon holding a browser, too wide and a kill sweep destroys a bystander."
  (describe "recognises a daemon"
    (it "matches the native binary"
      (let [e (sut/daemon-process-entry 42 "/Users/x/.local/bin/spel daemon --session agent-1")]
        (expect (= "42" (:pid e)))
        (expect (= "agent-1" (:session e)))))

    (it "matches a daemon started from the JVM classpath"
      (let [e (sut/daemon-process-entry
                43
                (str "java -cp target/classes clojure.main -m "
                  "com.blockether.spel.native daemon --session s2"))]
        (expect (= "s2" (:session e)))))

    (it "matches spel.exe so a Windows sweep is not blind"
      (expect (= "win1" (:session (sut/daemon-process-entry
                                    44
                                    "C:\\\\tools\\\\spel.exe daemon --session win1")))))

    ;; Regression, issue #117: a daemon started from a downloaded release asset
    ;; (`spel-macos-arm64`, `spel-windows-x64.exe`) was not recognised as spel's
    ;; own process, so `kill` answered "REFUSED unsafe stale pid - unrelated
    ;; process left alive" and the daemon could never be stopped by its own binary.
    (it "matches a daemon started from a release asset's file name"
      (expect (= "a1" (:session (sut/daemon-process-entry
                                  49 "/tmp/dl/spel-macos-arm64 daemon --session a1")))))

    (it "matches a Windows release asset"
      (expect (= "w2" (:session (sut/daemon-process-entry
                                  50 "C:\\\\dl\\\\spel-windows-x64.exe daemon --session w2")))))

    (it "matches a release asset invoked with --session before the subcommand"
      (expect (= "a3" (:session (sut/daemon-process-entry
                                  51 "/tmp/dl/spel-linux-x64 --session a3 daemon"))))))

  (describe "refuses everything else"
    (it "leaves a spel CLIENT alone — it carries --session too"
      (expect (nil? (sut/daemon-process-entry
                      45 "/usr/local/bin/spel --session agent-1 open https://example.com"))))

    (it "leaves a client whose script contains the word daemon alone"
      (expect (nil? (sut/daemon-process-entry
                      48 "/usr/local/bin/spel --session victim evaluate daemon"))))

    (it "leaves an unrelated process that merely says daemon alone"
      (expect (nil? (sut/daemon-process-entry
                      46 "node /some/daemon-runner --session foo"))))

    (it "leaves a release-asset CLIENT invocation alone"
      (expect (nil? (sut/daemon-process-entry
                      52 "/tmp/dl/spel-linux-x64 --session victim open https://example.com"))))

    (it "ignores a process with no command line at all"
      (expect (nil? (sut/daemon-process-entry 47 nil))))))

;; Regression, issue #132: a daemon started from a JVM was invisible on Linux.
;; The JDK fills `ProcessHandle`'s command line from a fixed-size read of
;; /proc/<pid>/cmdline, and 7288 characters of classpath came before
;; `--session <name>`, so `session list` could not show that daemon and
;; `kill --all-sessions` could not kill it.
(defdescribe long-command-line-scan-test
  "A daemon is recognised from the command line the OS keeps, not the excerpt the JDK returns."

  (it "finds a daemon whose command line outgrows the JDK's Linux excerpt"
    (when-not (str/includes? (str/lower-case (System/getProperty "os.name")) "windows")
      (let [session (str "spel-longcmd-" (System/currentTimeMillis))
            padding (apply str (repeat 8000 "x"))
            proc    (.start (ProcessBuilder. ^java.util.List
                              ["/bin/sh" "-c" "while :; do sleep 0.5; done" padding
                               "com.blockether.spel.native" "daemon" "--session" session]))]
        (try
          (let [entry (sut/daemon-process-at-pid (.pid proc))]
            (expect (= session (:session entry)))
            (expect (= (str (.pid proc)) (:pid entry))))
          (finally
            (.destroyForcibly proc)))))))

(defdescribe daemon-pid-integrity-test
  "Stale PID files must be visible as unhealthy and must never kill a bystander."

  (it "reports a reused live PID as stale without probing it"
    (let [s     (dfr-session)
          calls (atom 0)
          pid   (str (.pid (java.lang.ProcessHandle/current)))]
      (with-live-pid s
        (fn []
          (with-redefs [sut/daemon-process-at-pid (constantly nil)
                        sut/orphan-daemon-processes (constantly [])]
            (let [h (sut/daemon-health s (fn [] (swap! calls inc)))]
              (expect (= "stale" (:status h)))
              (expect (= pid (:stale_pid h)))
              (expect (zero? @calls))))))))

  (it "refuses to signal an unrelated process named by a stale PID file"
    (let [s   (dfr-session)
          pid (str (.pid (java.lang.ProcessHandle/current)))]
      (with-live-pid s
        (fn []
          (with-redefs [sut/daemon-process-at-pid (constantly nil)
                        sut/orphan-daemon-processes (constantly [])]
            (let [result (sut/force-kill-daemon! s)]
              (expect (true? (:refused result)))
              (expect (false? (:killed result)))
              (expect (= pid (:pid result)))
              (expect (.isAlive (java.lang.ProcessHandle/current)))))))))

  (it "reports a verified daemon with deleted state files as orphaned"
    (let [s (dfr-session)]
      (with-redefs [sut/orphan-daemon-processes (fn [] [{:pid "4242" :session s}])]
        (let [h (sut/daemon-health s (fn [] nil))]
          (expect (= "orphaned" (:status h)))
          (expect (= "4242" (:pid h)))
          (expect (str/includes? (sut/health-report h) "missing/stale state")))))))

;; =============================================================================
;; Unit Tests — Client transport timeout
;; =============================================================================

(defdescribe client-timeout-for-test
  "Unit tests for client-timeout-for — the CLI must outlive the daemon budget"

  (describe "derived from the daemon's own per-action budget"
    (it "matches daemon/client-timeout-ms for open-ended actions"
      (expect (= (daemon/client-timeout-ms "sci_eval")
                (#'sut/client-timeout-for {"action" "sci_eval"})))
      (expect (= (daemon/client-timeout-ms "ios_tap")
                (#'sut/client-timeout-for {"action" "ios_tap"}))))

    (it "accepts keyword action keys too"
      (expect (= (#'sut/client-timeout-for {"action" "sci_eval"})
                (#'sut/client-timeout-for {:action "sci_eval"})))))

  (describe "bounds"
    (it "never returns nil — a dead daemon must not hang the client forever"
      (doseq [action ["sci_eval" "goto" "snapshot" "ios-snapshot"]]
        (let [t (#'sut/client-timeout-for {"action" action})]
          (expect (pos? t))
          (expect (>= t 30000)))))

    (it "stays above the daemon budget for every action"
      (doseq [action ["sci_eval" "script" "goto" "snapshot" "ios_tap"]]
        (expect (> (#'sut/client-timeout-for {"action" action})
                  (daemon/command-budget-ms action)))))))

;; =============================================================================
;; Regression, issue #114: `spel session list` died with
;; `java.util.MissingFormatWidthException: %-0s` whenever no listed session was
;; the current one — the unnamed current-session marker column was then empty in
;; the header and in every row, so its computed width was 0 and `render-table`
;; formatted it as "%-0s", which java.util.Formatter rejects.
;; =============================================================================

(defdescribe render-table-test
  "Column sizing in render-table, including all-empty columns."

  (describe "a column that is empty in the header and in every row"
    (it "renders the table instead of throwing"
      (let [render-table (var-get #'sut/render-table)
            out (str/replace
                  (with-out-str
                    (render-table ["" "SESSION" "BROWSER"]
                      [["" "foo" "chromium"]
                       ["" "bar" "firefox"]]))
                  "\r" "")]
        (expect (str/includes? out "SESSION"))
        (expect (str/includes? out "foo"))
        (expect (str/includes? out "bar"))))

    (it "still renders when one row fills the marker column"
      (let [render-table (var-get #'sut/render-table)
            out (str/replace
                  (with-out-str
                    (render-table ["" "SESSION"]
                      [["" "foo"]
                       ["→" "bar"]]))
                  "\r" "")]
        (expect (str/includes? out "→   bar"))))))

;; =============================================================================
;; Regression, issue #116: `spel wait --load <state>` printed a bare "Saved: "
;; with an empty value — the shared state branch assumed a saved-artifact path,
;; so a command that saves nothing advertised a save confirmation and scripts
;; matching "Saved: " acted on an empty path.
;; =============================================================================

(defdescribe state-result-test
  "Rendering of results carrying a :state key."

  (describe "wait --load"
    (it "prints the reached load state and no save prefix"
      (let [out (render-result {:success true :data {:state "domcontentloaded"}})]
        (expect (= "domcontentloaded\n" out))
        (expect (not (str/includes? out "Saved:"))))))

  (describe "state save/load"
    (it "prints the saved path"
      (expect (= "Saved: /tmp/state-x.json\n"
                (render-result {:success true :data {:state "saved" :path "/tmp/state-x.json"}}))))

    (it "prints the loaded path"
      (expect (= "Loaded: /tmp/state-x.json\n"
                (render-result {:success true :data {:state "loaded" :path "/tmp/state-x.json"}}))))))

;; =============================================================================
;; Regression, user report: `spel annotate` printed a bare "Saved: " with an
;; empty path above its reference table — the annotated branch assumed a saved
;; artifact, so an overlay-only command advertised a file it never wrote.
;; =============================================================================

(defdescribe annotated-result-test
  "Rendering of results carrying an :annotated reference table."

  (describe "overlay-only annotate"
    (it "reports the annotated ref count and no save prefix"
      (let [out (render-result {:success true
                                :data    {:annotated {:count   2
                                                      :entries [{:ref "e1" :role "button" :name "Save"}
                                                                {:ref "e2" :role "link" :name "Home"}]}}})]
        (expect (str/starts-with? out "Annotated: 2 refs\n"))
        (expect (not (str/includes? out "Saved:")))
        (expect (str/includes? out "@e1"))
        (expect (str/includes? out "\"Save\"")))))

  (describe "annotated screenshot"
    (it "prints the saved artifact and the ref table under it"
      (let [out (render-result {:success true
                                :data    {:path      "/tmp/shot.png"
                                          :size      1234
                                          :annotated {:count   1
                                                      :entries [{:ref "e1" :role "button" :name "Save"}]}}})]
        (expect (str/starts-with? out "Saved: /tmp/shot.png (1234 bytes, 1 refs annotated)\n"))
        (expect (str/includes? out "@e1"))))))

;; =============================================================================
;; Regression, issue #117: a downloaded release binary never started its daemon.
;; Release assets are published as `spel-macos-arm64`, `spel-linux-x64`, … and the
;; launcher only re-exec'd itself when the executable's file name was exactly
;; `spel`/`spel.exe`, so the renamed asset spawned
;; `java -cp <empty> clojure.main -m com.blockether.spel.native` and the JVM died
;; with "Could not find or load main class clojure.main".
;; =============================================================================

(defdescribe daemon-launch-command-test
  "How the CLI relaunches itself as a daemon."

  (describe "native image"
    (it "re-execs the running binary whatever the file is named"
      (expect (= ["/opt/bin/spel-macos-arm64" "daemon" "--session" "s1"]
                (sut/daemon-launch-command
                  {:native?   true
                   :exec-path "/opt/bin/spel-macos-arm64"
                   :classpath ""}
                  ["daemon" "--session" "s1"]))))

    (it "re-execs a binary named spel too"
      (expect (= ["/usr/local/bin/spel" "daemon"]
                (sut/daemon-launch-command
                  {:native? true :exec-path "/usr/local/bin/spel" :classpath ""}
                  ["daemon"]))))

    (it "re-execs spel.exe on Windows"
      (expect (= ["C:\\tools\\spel-windows-x64.exe" "daemon"]
                (sut/daemon-launch-command
                  {:native? true :exec-path "C:\\tools\\spel-windows-x64.exe" :classpath ""}
                  ["daemon"])))))

  (describe "jvm"
    (it "relaunches through the classpath, never through the java executable path"
      (expect (= ["java" "-cp" "/cp/spel.jar" "clojure.main"
                  "-m" "com.blockether.spel.native" "daemon"]
                (sut/daemon-launch-command
                  {:native?   false
                   :exec-path "/usr/bin/java"
                   :classpath "/cp/spel.jar"}
                  ["daemon"]))))))

;; =============================================================================
;; Native dispatch — CLI-owned flags must not hide the command
;; =============================================================================

;; Regression, issue #119: parse-global-flags stripped only its own handful of
;; flags and left everything else in :command-args, so `spel --provider ios
;; eval-sci '(+ 1 2)'` dispatched on "--provider" and printed
;; `Unknown command: eval-sci` — including for --content-boundaries,
;; --max-output and --allowed-domains, which the shipped skill tells agents to
;; pass on every call.
(defdescribe native-cli-flag-passthrough-test
  "CLI-owned flags never hide the command that follows them (issue #119)"

  (it "finds eval-sci after every CLI value flag"
    (doseq [[flag value] [["--provider" "ios"]
                          ["--bundle-id" "com.example.app"]
                          ["--udid" "ABC-123"]
                          ["--device" "iPhone 17 Pro"]
                          ["--app" "/tmp/Example.app"]
                          ["--platform-version" "26.0"]
                          ["--appium-url" "http://127.0.0.1:4723"]
                          ["--max-output" "500"]
                          ["--allowed-domains" "example.org"]]]
      (let [g (#'com.blockether.spel.native/parse-global-flags
               [flag value "eval-sci" "(+ 1 2)"])]
        (expect (= "eval-sci" (first (:command-args g))))
        (expect (= value (get-in g [:cli-flags (subs flag 2)]))))))

  (it "finds eval-sci after a valueless CLI flag"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["--content-boundaries" "eval-sci" "(+ 1 2)"])]
      (expect (= "eval-sci" (first (:command-args g))))
      (expect (true? (get-in g [:cli-flags "content-boundaries"])))))

  (it "accepts the = spelling"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["--provider=ios" "eval-sci" "(+ 1 2)"])]
      (expect (= "eval-sci" (first (:command-args g))))
      (expect (= "ios" (get-in g [:cli-flags "provider"])))))

  (it "leaves the command and its code argument intact"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["--provider" "ios" "--max-output" "100" "eval-sci" "(+ 1 2)"])]
      (expect (= ["eval-sci" "(+ 1 2)"] (vec (:command-args g))))))

  (it "finds daemon and init-agents behind the same flags"
    (expect (= "daemon"
              (first (:command-args
                      (#'com.blockether.spel.native/parse-global-flags
                       ["--provider" "ios" "daemon"])))))
    (expect (= "init-agents"
              (first (:command-args
                      (#'com.blockether.spel.native/parse-global-flags
                       ["--content-boundaries" "init-agents"])))))))

;; Regression, issue #119 (second round): the first fix copied cli.clj's flag
;; NAMES into native.clj. That second registry drifts the day a flag is added
;; over there, and the command disappears again — the same bug, one release
;; later. Recognition now rests on the closed set of commands this namespace
;; dispatches, so no flag has to be known in advance.
(defdescribe native-unknown-flag-passthrough-test
  "A flag this parser never heard of still cannot hide the command (issue #119)"

  (it "finds the command behind a flag nobody registered here"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["--brand-new-flag" "whatever" "eval-sci" "(+ 1 2)"])]
      (expect (= ["eval-sci" "(+ 1 2)"] (vec (:command-args g))))
      (expect (= "whatever" (get-in g [:cli-flags "brand-new-flag"])))))

  (it "treats a flag standing directly before a command as valueless"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["--brand-new-flag" "daemon"])]
      (expect (= ["daemon"] (vec (:command-args g))))
      (expect (true? (get-in g [:cli-flags "brand-new-flag"])))))

  (it "still dispatches help and version"
    (doseq [token ["--help" "-h" "help" "--version" "version"]]
      (expect (= [token]
                (vec (:command-args
                      (#'com.blockether.spel.native/parse-global-flags [token])))))))

  (it "leaves a command's own flags to that command"
    (let [g (#'com.blockether.spel.native/parse-global-flags
             ["report" "--results-dir" "out"])]
      (expect (= ["report" "--results-dir" "out"] (vec (:command-args g))))
      (expect (nil? (:cli-flags g))))))

;; Regression, issue #127: the snapshot walk had no element budget, so a page
;; with 150 000 elements burned the whole 25 s command budget and answered
;; nothing while the renderer's memory doubled. `--max-nodes` is the caller's
;; way to spend more of it deliberately.
(defdescribe snapshot-node-budget-flag-test
  "Tests for the snapshot --max-nodes flag"

  (describe "snapshot --max-nodes"
    (it "passes the element budget through as a number"
      (let [c (cmd ["snapshot" "--max-nodes" "5000"])]
        (expect (= "snapshot" (:action c)))
        (expect (= 5000 (:max_nodes c)))))

    (it "leaves the key out when the flag is absent"
      (expect (nil? (:max_nodes (cmd ["snapshot"])))))

    (it "keeps working next to a selector"
      (let [c (cmd ["snapshot" "-s" "main" "--max-nodes" "100"])]
        (expect (= "main" (:selector c)))
        (expect (= 100 (:max_nodes c)))))))

;; Regression, user report: two skill references told agents to pipe a generated
;; script — `echo '(…)' | spel eval-sci --stdin` — but only `eval-js` ever read
;; stdin, so eval-sci evaluated the FLAG and answered
;; `Unable to resolve symbol: --stdin`.
(defdescribe eval-sci-stdin-test
  "Tests for the source one eval-sci invocation evaluates"

  (describe "eval-sci code source"
    (it "reads the whole of stdin for --stdin"
      (binding [*in* (java.io.StringReader. "(+ 1 2)\n(+ 3 4)")]
        (expect (= "(+ 1 2)\n(+ 3 4)"
                  (#'com.blockether.spel.native/eval-sci-code "--stdin")))))

    (it "reads stdin for the bare - spelling"
      (binding [*in* (java.io.StringReader. "(spel/title)")]
        (expect (= "(spel/title)"
                  (#'com.blockether.spel.native/eval-sci-code "-")))))

    (it "slurps an existing .clj path"
      (let [f (java.io.File/createTempFile "spel-eval-sci" ".clj")]
        (try
          (spit f "(println :from-file)")
          (expect (= "(println :from-file)"
                    (#'com.blockether.spel.native/eval-sci-code (.getAbsolutePath f))))
          (finally (.delete f)))))

    (it "keeps an inline expression, and a missing path, as itself"
      (expect (= "(+ 1 2)" (#'com.blockether.spel.native/eval-sci-code "(+ 1 2)")))
      (expect (= "no-such-script.clj"
                (#'com.blockether.spel.native/eval-sci-code "no-such-script.clj"))))))
