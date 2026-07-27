(ns com.blockether.spel.errors-test
  "Tests for the readable-error formatter.

   Drives `concise` with the exact dumps Playwright produces (captured from
   a live `spel eval-js` failure and a click timeout) and asserts the
   OBSERVABLE result: what survives, what is filtered, and that nothing is
   ever silently emptied. Pure strings — no browser, no daemon."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.errors :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Fixtures — real Playwright output
;; =============================================================================

(def ^:private eval-error-envelope
  "One `Error { … }` dump exactly as Playwright renders an evaluate failure."
  (str "Error {\n"
    "  message='TypeError: Cannot read properties of null (reading 'getBoundingClientRect')\n"
    "    at eval (eval at evaluate (:303:30), <anonymous>:1:32)\n"
    "    at eval (<anonymous>)\n"
    "    at UtilityScript.evaluate (<anonymous>:303:30)\n"
    "    at UtilityScript.<anonymous> (<anonymous>:1:44)\n"
    "  name='Error\n"
    "  stack='Error: TypeError: Cannot read properties of null (reading 'getBoundingClientRect')\n"
    "    at eval (eval at evaluate (:303:30), <anonymous>:1:32)\n"
    "    at CRExecutionContext.evaluateWithArguments (/Users/x/.cache/spel/1.61.0/mac-arm64/package/lib/coreBundle.js:35188:17)\n"
    "    at async LongStandingScope._race (/Users/x/.cache/spel/1.61.0/mac-arm64/package/lib/coreBundle.js:3390:18)\n"
    "    at async _Frame.evaluateExpression (/Users/x/.cache/spel/1.61.0/mac-arm64/package/lib/coreBundle.js:22782:16)\n"
    "    at async DispatcherConnection.dispatch (/Users/x/.cache/spel/1.61.0/mac-arm64/package/lib/coreBundle.js:18408:27)\n"
    "}"))

(def ^:private eval-error-raw
  "What the daemon actually sees: the envelope repeated once per cause."
  (str eval-error-envelope " → " eval-error-envelope))

(def ^:private timeout-raw
  (str "Timeout 5000ms exceeded.\n"
    "Call log:\n"
    "  - waiting for locator(\"#missing\")\n"
    "  -   locator resolved to hidden <div>\n"
    "  - attempting click action\n"
    "  -   waiting for element to be visible\n"
    "  - retrying click action\n"
    "  - waiting 20ms\n"
    "  - retrying click action\n"
    "  - waiting 100ms\n"
    "  - retrying click action\n"
    "  - waiting 500ms\n"
    "  - retrying click action\n"))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- caret-line-idx
  [lines]
  (first (keep-indexed (fn [i l] (when (re-find #"^\s*\|\s*\^" l) i)) lines)))

(defn- caret-target
  "The exact source substring the `^^^` run points at — what the reader's eye
   lands on. nil when the output carries no caret at all."
  [out]
  (let [lines (str/split-lines out)]
    (when-let [i (caret-line-idx lines)]
      (let [carets (re-find #"\^+" (nth lines i))
            start  (str/index-of (nth lines i) "^")
            src    (nth lines (dec i))]
        (subs src start (min (count src) (+ start (count carets))))))))

(defn- caret-column
  "0-based terminal column the caret run starts at, counted from the first
   character of the excerpted source line — the number a reader's eye uses."
  [out]
  (let [lines (str/split-lines out)]
    (when-let [i (caret-line-idx lines)]
      (let [line (nth lines i)]
        (- (long (str/index-of line "^"))
          (+ 2 (long (str/index-of line "| "))))))))

;; =============================================================================
;; Tests
;; =============================================================================

(defdescribe concise-test
  (describe "playwright evaluate failures"
    (it "keeps the real message as the first line"
      (expect (= "TypeError: Cannot read properties of null (reading 'getBoundingClientRect')"
                (first (str/split-lines (sut/concise eval-error-raw))))))

    (it "reports the failing position inside the caller's expression"
      (expect (str/includes? (sut/concise eval-error-raw) "at <expression>:1:32")))

    (it "drops every driver-internal frame"
      (let [out (sut/concise eval-error-raw)]
        (expect (not (str/includes? out "coreBundle.js")))
        (expect (not (str/includes? out "UtilityScript")))
        (expect (not (str/includes? out "at async")))))

    (it "drops the Error {} envelope scaffolding"
      (let [out (sut/concise eval-error-raw)]
        (expect (not (str/includes? out "message='")))
        (expect (not (str/includes? out "stack='")))
        (expect (not (str/includes? out "name='")))))

    (it "collapses the duplicated cause chain"
      (let [out (sut/concise eval-error-raw)]
        (expect (= 1 (count (re-seq #"Cannot read properties of null" out))))))

    (it "shrinks a 30-line dump to a couple of lines"
      (let [out (sut/concise eval-error-raw)]
        (expect (> (count (str/split-lines eval-error-raw)) 25))
        (expect (<= (count (str/split-lines out)) 3)))))

  (describe "call logs"
    (it "keeps the timeout message and its call log header"
      (let [out (sut/concise timeout-raw)]
        (expect (str/starts-with? out "Timeout 5000ms exceeded."))
        (expect (str/includes? out "Call log:"))
        (expect (str/includes? out "waiting for locator(\"#missing\")"))))

    (it "caps the call log and says how much it cut"
      (let [out (sut/concise timeout-raw)]
        (expect (str/includes? out "more call-log line(s)"))
        (expect (< (count (str/split-lines out))
                  (count (str/split-lines timeout-raw)))))))

  (describe "messages that need no cleaning"
    (it "passes a plain message through untouched"
      (expect (= "Selector not found: #missing"
                (sut/concise "Selector not found: #missing"))))

    (it "returns nil for nil"
      (expect (nil? (sut/concise nil))))

    (it "keeps distinct causes, labelled"
      (let [out (sut/concise (str "PlaywrightException: outer failure"
                               " → TimeoutError: inner root cause"))]
        (expect (str/includes? out "outer failure"))
        (expect (str/includes? out "caused by: TimeoutError: inner root cause"))))

    (it "drops a cause that only repeats its parent"
      (expect (= "PlaywrightException: boom happened"
                (sut/concise (str "PlaywrightException: boom happened"
                               " → PlaywrightException: boom happened")))))

    (it "never splits a prose arrow belonging to the message itself"
      (let [msg "expected transition 'idle → ready' but saw 'ready → idle'"]
        (expect (= msg (sut/concise msg)))))

    (it "never empties a message that is nothing but filtered frames"
      (let [out (sut/concise "at async DispatcherConnection.dispatch (coreBundle.js:1:1)")]
        (expect (seq out))))

    (it "truncates one absurdly long line and says by how much"
      (let [out (sut/concise (str "Boom: " (apply str (repeat 900 \x))))]
        (expect (< (count out) 400))
        (expect (str/includes? out "chars)"))))

    (it "caps a very tall message and says how much it cut"
      (let [out (sut/concise (str/join "\n" (map #(str "detail " %) (range 100))))]
        (expect (<= (count (str/split-lines out)) 41))
        (expect (str/includes? out "more line(s)"))))

    (it "returns nil rather than a blank string"
      (expect (nil? (sut/concise "   \n\n  ")))))

  (describe "code frames"
    (it "points a caret at the JS property that blew up"
      (let [out (sut/concise eval-error-raw
                  {:source "document.querySelector('#nope').getBoundingClientRect()"
                   :lang :js})]
        (expect (str/includes? out "  1 | document.querySelector('#nope').getBoundingClientRect()"))
        (expect (= ".getBoundingClientRect" (caret-target out)))))

    (it "aligns the caret with the TRIMMED expression Playwright evaluated"
      (let [out (sut/concise eval-error-raw
                  {:source "\n   document.querySelector('#nope').getBoundingClientRect()  \n"
                   :lang :js})]
        (expect (= ".getBoundingClientRect" (caret-target out)))))

    (it "shows the neighbouring lines of a multi-line script"
      (let [out (sut/concise
                  (str "TypeError: Cannot read properties of null (reading 'x')\n"
                    "    at eval (eval at evaluate (:1:1), <anonymous>:2:14)")
                  {:source "const el = document.querySelector('#nope');\nconst r = el.getBoundingClientRect();\nr.top"
                   :lang :js})]
        (expect (str/includes? out "  1 | const el"))
        (expect (str/includes? out "  3 | r.top"))
        ;; V8 reports the property NAME here, not the dot before it.
        (expect (= "getBoundingClientRect" (caret-target out)))))

    (it "carets the whole Clojure form SCI reports in ex-data"
      (let [out (sut/concise "Unable to resolve symbol: spel/set-viewport-siz"
                  {:source "(let [x 1]\n  (spel/set-viewport-siz 390 844))"
                   :lang :clj :line 2 :column 3})]
        (expect (= "(spel/set-viewport-siz 390 844)" (caret-target out)))))

    (it "consumes SCI's inline position marker instead of printing it twice"
      (let [out (sut/concise "Could not resolve symbol: nope [at line 2, column 3]"
                  {:source "(let [x 1]\n  (nope x))"})]
        (expect (not (str/includes? out "[at line")))
        (expect (= "(nope x)" (caret-target out)))))

    (it "never maps a JS position onto a Clojure source"
      ;; `(spel/evaluate "…")` fails at a column INSIDE the JS string; pointing
      ;; that column at the Clojure form would caret an unrelated character.
      (let [out (sut/concise
                  (str "TypeError: Cannot read properties of null (reading 'innerText')\n"
                    "    at eval (eval at evaluate (:1:1), <anonymous>:1:32)")
                  {:source "(spel/evaluate \"document.querySelector('#nope').innerText\")"
                   :lang :clj :line 1 :column 1})]
        (expect (str/includes? out "at <expression>:1:32"))
        (expect (= "(spel/evaluate" (caret-target out)))))

    (it "renders a tab as one space so the caret cannot drift"
      (let [out (sut/concise
                  "TypeError: nope\n    at eval (eval at evaluate (:1:1), <anonymous>:2:2)"
                  {:source "function f(){\n\tnope();\n}" :lang :js})]
        (expect (not (str/includes? out "\t")))
        (expect (= "nope" (caret-target out)))))

    (it "windows a minified line around the caret instead of wrapping"
      (let [src (str (apply str (repeat 60 "abcde")) "BOOM();"
                  (apply str (repeat 40 "xyzw")))
            out (sut/concise
                  "SyntaxError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:300)"
                  {:source src :lang :js})]
        (expect (every? #(< (count %) 200) (str/split-lines out)))
        (expect (str/includes? out "…"))
        (expect (= "eBOOM" (caret-target out)))))

    (it "fabricates no caret for a position outside the source, only an echo"
      ;; The line does not exist, so nothing may claim to be the failing spot —
      ;; but a one-line expression is still worth showing back.
      (let [out (sut/concise
                  "Boom\n    at eval (eval at evaluate (:1:1), <anonymous>:99:3)"
                  {:source "let x = 1" :lang :js})]
        (expect (nil? (caret-target out)))
        (expect (not (str/includes? out "^")))
        (expect (str/includes? out "  1 | let x = 1"))))

    (it "carets the last character when the column runs past the line end"
      (let [out (sut/concise
                  "Boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:400)"
                  {:source "let x = 1" :lang :js})]
        (expect (str/includes? out "  1 | let x = 1"))
        (expect (= "1" (caret-target out)))))

    (it "adds nothing when no source was supplied"
      (expect (nil? (caret-target (sut/concise eval-error-raw)))))

    (it "survives a nil source, a blank source and a bogus position"
      (expect (seq (sut/concise eval-error-raw {:source nil})))
      (expect (seq (sut/concise eval-error-raw {:source ""})))
      (expect (seq (sut/concise eval-error-raw {:source "x" :line 0 :column 0}))))

    (it "aligns the caret by terminal columns, not string indexes"
      ;; A full-width character is one string index but two columns; padding by
      ;; index alone parks the caret two columns to the left of its target.
      (let [out (sut/concise
                  "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:18)"
                  {:source "const 変数 = {}; 変数.foo()" :lang :js})]
        (expect (= 21 (caret-column out)))))

    (it "gives a zero-width combining mark no column of its own"
      (let [out (sut/concise
                  "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:16)"
                  {:source "let a\u0301b\u0301c\u0301 = {}; a\u0301b\u0301c\u0301.f()" :lang :js})]
        (expect (= 12 (caret-column out)))))

    (it "windows every excerpt line the same way as the caret line"
      (let [wide (fn [c] (apply str (repeat 400 c)))
            out  (sut/concise
                   "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:2:300)"
                   {:source (str (wide "A") "\n" (apply str (repeat 295 "B")) "x.foo()\n"
                              (wide "C"))
                    :lang :js})
            rows (filter #(re-find #"^\s*\d+ \| " %) (str/split-lines out))]
        (expect (= 3 (count rows)))
        ;; Every row is cut at the same offset, so the excerpt stays a straight
        ;; vertical slice instead of three unrelated windows.
        (expect (every? #(str/includes? % "| …") rows))))

    (it "keeps the column a dropped control character used to occupy"
      (let [out (sut/concise
                  "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:4)"
                  {:source "ab\u0000cd.ef()" :lang :js})]
        (expect (= "cd" (caret-target out)))))

    (it "stacks no second excerpt when an already-concise message is re-read"
      (let [opts {:source "var t={};t.foo()" :lang :js}
            once (sut/concise
                   (str "TypeError: t.foo is not a function\n"
                     "    at eval (eval at evaluate (:1:1), <anonymous>:1:12)")
                   opts)]
        (expect (= "foo" (caret-target once)))
        (expect (= once (sut/concise once opts)))))

    (it "echoes a short expression when the engine names no position"
      ;; A compile-time SyntaxError carries no line/column at all, so the only
      ;; way to see what was evaluated is the expression itself.
      (let [out (sut/concise
                  (str "SyntaxError: Unexpected token ';'\n"
                    "    at eval (<anonymous>)\n"
                    "    at UtilityScript.evaluate (<anonymous>:303:30)")
                  {:source "const x = ;" :lang :js})]
        (expect (str/includes? out "  1 | const x = ;"))
        (expect (nil? (caret-target out)))))

    (it "echoes nothing of a long script the engine gave no position for"
      (let [src (str/join "\n" (repeat 40 "let x = 1;"))
            out (sut/concise "SyntaxError: boom" {:source src :lang :js})]
        (expect (= "SyntaxError: boom" out))))

    (it "carets the delimiter the reader could not close, not the end of input"
      ;; The exception's own position is where reading STOPPED; the readable
      ;; spot is the `(` it names in the message.
      (let [out (sut/concise "EOF while reading, expected ) to match ( at [1,1]"
                  {:source "(let [x 1]" :lang :clj :line 1 :column 11})]
        (expect (= "(let" (caret-target out)))))

    (it "reads the reader position on the line the delimiter opened"
      (let [out (sut/concise "EOF while reading, expected ] to match [ at [2,8]"
                  {:source "(let [x 1]\n  (vec [1 2" :lang :clj})]
        (expect (= "[1" (caret-target out)))))

    (it "unwraps an Error envelope closed by its brace, with no name= field"
      (let [out (sut/concise
                  (str "Error {\n  message='TypeError: el.focus is not a function\n"
                    "    at eval (eval at evaluate (:1:1), <anonymous>:1:4)'\n}")
                  {:source "el.focus()" :lang :js})]
        (expect (str/starts-with? out "TypeError: el.focus is not a function"))
        (expect (not (str/includes? out "message=")))
        (expect (= "focus" (caret-target out)))))

    (it "unwraps a truncated Error envelope rather than printing it raw"
      (let [out (sut/concise "Error {\n  message='TypeError: nope is not a function")]
        (expect (= "TypeError: nope is not a function" out))))

    (it "drops the JVM's module provenance clause and core package prefixes"
      (let [out (sut/concise
                  (str "clojure.lang.ExceptionInfo: class java.lang.String cannot be cast "
                    "to class java.lang.Number (java.lang.String and java.lang.Number "
                    "are in module java.base of loader 'bootstrap')")
                  {:source "(+ 1 \"a\")" :lang :clj :line 1 :column 1})]
        (expect (str/starts-with? out "String cannot be cast to Number"))
        (expect (not (str/includes? out "module")))
        (expect (not (str/includes? out "ExceptionInfo")))
        (expect (= "(+ 1 \"a\")" (caret-target out)))))

    (it "backs the caret up to the start of the token the column lands inside"
      (let [out (sut/concise
                  "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:8)"
                  {:source "computeTotal(items)" :lang :js})]
        (expect (= "computeTotal" (caret-target out)))))

    (it "keeps the reported column when the token is too long to walk back"
      (let [src (str (apply str (repeat 30 "abcde")) "BOOM")
            out (sut/concise
                  "TypeError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:100)"
                  {:source src :lang :js})]
        ;; Index 99 of the run is an `e`; walking back would start it at `a`.
        (expect (str/starts-with? (caret-target out) "e"))))

    (it "never draws a caret run wider than the source line above it"
      (let [src (str (apply str (repeat 200 "x")) " = 1")
            out (sut/concise
                  "SyntaxError: boom\n    at eval (eval at evaluate (:1:1), <anonymous>:1:1)"
                  {:source src :lang :js})
            lines (str/split-lines out)
            i (caret-line-idx lines)]
        (expect (some? i))
        (expect (<= (count (nth lines i)) (count (nth lines (dec i)))))))

    (it "carets an explicitly supplied position in a JS source too"
      ;; The daemon reads a JS position from the `at <expression>` frame, but a
      ;; caller that already knows line:column must not lose the caret.
      (let [out (sut/concise "TypeError: boom"
                  {:source "let total = compute();" :lang :js :line 1 :column 13})]
        (expect (= "compute" (caret-target out)))))

    (it "echoes a short source instead of caretting a line it does not have"
      (let [out (sut/concise "TypeError: boom"
                  {:source "let a = 1;" :lang :js :line 99 :column 1})]
        (expect (str/includes? out "1 | let a = 1;"))
        (expect (not (str/includes? out "^")))))

    (it "adds nothing at all when a bogus position meets a long source"
      (let [src (str/join "\n" (repeat 40 "console.log(1);"))
            out (sut/concise "TypeError: boom" {:source src :lang :js :line 999 :column 1})]
        (expect (= "TypeError: boom" out))))

    (it "treats a lone carriage return as a line break, in message and source"
      ;; Left in place it rewinds the terminal and eats the excerpt.
      (let [out (sut/concise "Line one\rLine two"
                  {:source "aaa\rbbb\rccc" :lang :js :line 2 :column 1})]
        (expect (not (str/includes? out "\r")))
        (expect (str/includes? out "Line one\nLine two"))
        (expect (= "bbb" (caret-target out)))))

    (it "strips a byte-order mark rather than printing it as the first glyph"
      (expect (= "TypeError: boom" (sut/concise "\uFEFFTypeError: boom")))
      ;; Indented first: trimming the indent would otherwise promote the BOM
      ;; to the first printed glyph.
      (expect (= "TypeError: boom" (sut/concise "   \uFEFFTypeError: boom"))))

    (it "puts frames flush left when the message is nothing but frames"
      (let [out (sut/concise "    at foo (/a/b.js:1:2)\n    at bar (/a/b.js:3:4)")]
        (expect (= ["at foo (b.js:1:2)" "at bar (b.js:3:4)"] (str/split-lines out)))))

    (it "frames the inner JS a Clojure form evaluated, never the form itself"
      ;; `(spel/evaluate "…")` reports a position inside the JS string it was
      ;; handed — the daemon hands that JS over as `:source`.
      (let [out (sut/concise
                  (str "TypeError: Cannot read properties of null (reading 'x')\n"
                    "    at eval (eval at evaluate (:1:1), <anonymous>:2:3)")
                  {:source "const b = null;\nb.x.y;" :lang :js})]
        (expect (str/includes? out "2 | b.x.y;"))
        (expect (= "x" (caret-target out)))))

    (it "echoes every line of a position-less snippet, not a window around line 1"
      (let [src (str/join "\n" ["function f(){" "  const q = null;" "  return q.x;" "}" "f();"])
            out (sut/concise "SyntaxError: Unexpected identifier 'f'" {:source src :lang :js})]
        (expect (str/includes? out "1 | function f(){"))
        (expect (str/includes? out "5 | f();"))
        (expect (not (str/includes? out "^")))))

    (it "leaves a position-less source too big to echo alone"
      (let [src (str/join "\n" (map #(str "const w" % " = " % ";") (range 1 20)))
            out (sut/concise "SyntaxError: Unexpected token '='" {:source src :lang :js})]
        (expect (= "SyntaxError: Unexpected token '='" out))))

    (it "keeps the stack frames together and puts the excerpt below them"
      ;; An excerpt wedged between two frames splits one stack in half.
      (let [out   (sut/concise
                    (str "TypeError: e.nope is not a function\n"
                      "    at eval (eval at evaluate (:1:1), <anonymous>:1:23)\n"
                      "    at Array.map (<anonymous>)\n"
                      "    at eval (eval at evaluate (:1:1), <anonymous>:1:12)")
                    {:source "els => els.map(e => e.nope())" :lang :js})
            lines (str/split-lines out)]
        (expect (= ["TypeError: e.nope is not a function"
                    "  at <expression>:1:23"
                    "  at Array.map (<anonymous>)"
                    "  at <expression>:1:12"]
                  (take 4 lines)))
        (expect (= "nope" (caret-target out)))))))
