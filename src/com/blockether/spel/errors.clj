(ns com.blockether.spel.errors
  "Readable browser errors.

   Playwright reports a failure as a wall of text: a Java
   `Error { message='…' name='…' stack='…' }` envelope, the SAME text again
   for every level of the cause chain, and a stack whose frames all point
   inside the bundled driver (`coreBundle.js`, `UtilityScript`, `async`
   dispatcher frames). The one line the caller needs — `ReferenceError: foo
   is not defined` — is buried in the middle of ~40 useless ones.

   `concise` collapses that dump into:

     <the real message>
       at <expression>:1:32          ; user frames only, capped

       1 | document.querySelector('#nope').getBoundingClientRect()
         |                                ^^^^^^^^^^^^^^^^^^^^^^^

     Call log:                        ; kept — it says what the driver awaited
       - waiting for locator('#x')

   The numbered excerpt with the caret run is the `:source` code frame: pass
   the code you sent to the browser (`{:source script}`) and the reported
   position is pointed at, babashka-style. JS positions come from the
   `at <expression>:line:col` frame and are resolved against the TRIMMED
   source, because Playwright trims an expression before evaluating it; a
   caller that already knows the position (SCI's `:line`/`:column` ex-data)
   passes it explicitly and the source is used as given.

   Pure string→string (no `Throwable`, no IO), so the daemon, the CLI and
   library callers all format errors the same way. It never throws — any
   internal failure falls back to the trimmed original. What it never does is
   invent or drop information the caller cannot recover: the untouched
   original is written to the session log (`spel logs`)."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Limits
;; =============================================================================

(def ^:private ^:const max-frames
  "Stack frames kept after filtering — enough to locate the failing line,
   never a wall of driver internals."
  4)

(def ^:private ^:const max-call-log-lines
  "Entries kept from Playwright's `Call log:` section."
  8)

(def ^:private ^:const max-lines
  "Hard ceiling on the rendered message, before the source code frame."
  40)

(def ^:private ^:const max-line-chars
  "Ceiling on ONE rendered line. A minified page throws messages thousands of
   characters wide; the line budget alone cannot catch those."
  200)

(def ^:private ^:const code-frame-context
  "Source lines shown above and below the failing one."
  2)

(def ^:private ^:const code-frame-width
  "Visible width of a source line in the code frame. A longer line is
   windowed around the caret instead of wrapping the terminal."
  120)

(def ^:private noise-markers
  "Substrings that mark a stack frame as runtime plumbing, not user code."
  ["coreBundle.js"
   "node:internal"
   "node_modules"
   "UtilityScript"
   "InjectedScript"
   "com.microsoft.playwright"
   "java.base/"
   "jdk.internal"
   "clojure.lang."
   "sci.impl"
   "sci.lang"])

;; =============================================================================
;; Sanitising
;; =============================================================================

(def ^:private ansi-re
  #"\u001B\[[0-9;?]*[ -/]*[@-~]")

(defn- sanitize
  "Removes ANSI colour sequences, a line-leading byte-order mark and C0 control
   characters (tabs become one space, newlines survive) so one source character
   is one caret column. A lone carriage return becomes a newline: left in place
   it rewinds the terminal and eats the line it was printed on. An INTERIOR
   zero-width mark is left alone — it costs no column, and dropping it would
   shift every reported column after it."
  [^String s]
  (-> s
    (str/replace ansi-re "")
    (str/replace #"\r\n?" "\n")
    ;; A BOM only prints as a glyph at the start of a line — including after
    ;; the indent an editor left in front of it.
    (str/replace #"(?m)^([ \t]*)\uFEFF+" "$1")
    (str/replace #"\t" " ")
    (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" "")))

(defn- sanitize-source
  "Like `sanitize`, but a dropped control character leaves a space behind so a
   reported column still lands on the character it named."
  [^String s]
  (-> s
    (str/replace ansi-re "")
    (str/replace #"\r\n?" "\n")
    (str/replace #"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]" " ")))

;; =============================================================================
;; Envelope + cause chain
;; =============================================================================

(defn- error-object-message
  "Extracts the `message='…'` body from Playwright's `Error { … }` dump —
   both the multi-line Java form and the single-line one. Returns nil when
   `s` is not such a dump."
  [^String s]
  (some-> (or (re-find #"(?s)Error\s*\{\s*\n\s*message='(.*?)\n\s*name='" s)
            (re-find #"(?s)Error\s*\{\s*message='(.*?)'\s+name=" s)
            ;; Envelope closed by its brace: no `name=`/`stack=` field at all.
            (re-find #"(?s)Error\s*\{\s*\n?\s*message='(.*?)\n\s*\}" s)
            ;; Truncated dump — the body still beats printing the envelope.
            (re-find #"(?s)Error\s*\{\s*\n?\s*message='(.*)\z" s))
    second
    (str/replace #"'\s*\}?\s*\z" "")
    str/trim))

(def ^:private module-note-re
  "The provenance clause the JVM appends to every cast message — `(java.lang.Long
   is in module java.base of loader 'bootstrap'…)`. It is longer than the error
   and has never once helped anybody debug a script."
  #"\s*\([\w.$][^)]*\b(?:is|are) in (?:an? )?(?:module|unnamed module)[^)]*\)")

(defn- simplify-jvm-noise
  "Trims the JVM's boilerplate out of a message: the generic `ExceptionInfo`
   wrapper, the module provenance clause, and the package of the core classes
   a cast error names — `class java.lang.String` reads better as `String`."
  [^String s]
  (-> s
    (str/replace #"^clojure\.lang\.ExceptionInfo:\s*" "")
    (str/replace module-note-re "")
    (str/replace #"\bclass (?:java\.lang|clojure\.lang)\.([A-Za-z_$][\w$]*)" "$1")))

(defn- unwrap-part
  "Reduces one cause-chain segment to its message text."
  [^String part]
  (simplify-jvm-noise (or (error-object-message part) (str/trim part))))

(def ^:private cause-head-re
  "A cause segment starts with an error class, a Java FQCN or an `Error { … }`
   envelope. Prose never does — which is how a `→` inside the user's own text
   is told apart from the ` → ` that joins the cause chain."
  #"^(?:Error\s*\{|[\w.$]*(?:Error|Exception|Throwable)\b|Timeout\b|Unable to\b|Could not\b)")

(defn- split-causes
  "Splits a `' → '`-joined cause chain (see `core/full-message`) into segments.
   A segment that does not look like the head of a new error is glued back to
   its predecessor: `\"expected \\\"Docs → API\\\"\"` is ONE message, not three."
  [^String s]
  (reduce
    (fn [acc seg]
      (if (or (empty? acc) (re-find cause-head-re (str/trim seg)))
        (conj acc seg)
        (conj (pop acc) (str (peek acc) " → " seg))))
    []
    (str/split s #"\s+→\s+")))

(defn- informative-parts
  "Drops duplicate cause segments and any segment already contained in a
   longer one. Playwright repeats the identical message at every level of
   the chain, which is what makes the raw error read three times as long as
   it is."
  [parts]
  (let [parts (vec (distinct (remove str/blank? parts)))]
    (vec (keep-indexed
           (fn [i part]
             (when-not (some (fn [[j other]]
                               (and (not= i j) (str/includes? other part)))
                         (map-indexed vector parts))
               part))
           parts))))

;; =============================================================================
;; Line filtering
;; =============================================================================

(def ^:private eval-frame-re
  "Chrome's frame for code evaluated through `page.evaluate`:
   `at eval (eval at evaluate (:303:30), <anonymous>:1:32)`. The trailing
   position is the one inside the caller's own expression."
  #"^at [^(]*\(eval at [^,]*, <anonymous>:(\d+):(\d+)\)$")

(def ^:private expression-frame-re
  #"^at <expression>:(\d+):(\d+)$")

(defn- frame-line?
  "True only for a real stack frame: `at name (file:1:2)` or `at file:1:2`.
   Prose such as `at least one option must be selected` is text, and must
   neither be indented as a frame nor eat the frame budget."
  [^String line]
  (let [t (str/triml line)]
    (boolean
      (and (str/starts-with? t "at ")
        (or (re-find #":\d+:\d+\)?$" t)
          (re-find #"\(<anonymous>\)$" t)
          (re-find #"\([^)]*\)$" t))))))

(defn- noisy-frame?
  [^String line]
  (let [trimmed (str/triml line)]
    (or (str/starts-with? trimmed "at async ")
      (= trimmed "at eval (<anonymous>)")
      (boolean (some #(str/includes? line %) noise-markers)))))

(defn- envelope-line?
  "True for the structural leftovers of an `Error { … }` dump."
  [^String line]
  (let [trimmed (str/trim line)]
    (or (= trimmed "}")
      (str/starts-with? trimmed "name='")
      (str/starts-with? trimmed "stack='"))))

(defn- call-log-header?
  [^String line]
  (boolean (re-find #"(?i)^\s*(call log:|={3,}\s*logs\s*={3,})\s*$" line)))

(defn- normalize-frame
  "Rewrites one kept frame into its shortest useful form: the page's
   `eval at evaluate` wrapper becomes the caller's own expression, and
   machine-specific absolute paths collapse to a file name."
  [^String line]
  (-> (str/trim line)
    (str/replace eval-frame-re "at <expression>:$1:$2")
    (str/replace #"\(([^()]*/)([^/()]+:\d+:\d+)\)$" "($2)")))

(defn- expression-frame?
  "True for the ONE frame that points inside the caller's own code. It is
   kept even though its wrapper mentions driver internals — dropping it is
   what leaves a JS error with no position at all."
  [^String normalized]
  (boolean (re-find expression-frame-re normalized)))

(def ^:private truncated-line-re
  "The counted trailer `truncate-line` leaves behind. Re-cutting an already cut
   line would only shrink its own count, so a second pass must leave it be."
  #"… \(\+\d+ chars\)\z")

(defn- truncate-line
  [^String line]
  (if (or (<= (count line) max-line-chars) (re-find truncated-line-re line))
    line
    (str (subs line 0 max-line-chars) "… (+" (- (count line) max-line-chars) " chars)")))

(defn- keep-line
  "Line-at-a-time reducer implementing the filtering rules. `state` carries
   the kept lines, the frame / call-log budgets, and what was dropped."
  [state ^String line]
  (let [{:keys [lines in-log? seen]} state
        frames (long (:frames state))
        log-lines (long (:log-lines state))
        blank? (str/blank? line)]
    (cond
      blank?
      (if (or (empty? lines) (str/blank? (peek lines)))
        state
        (update state :lines conj ""))

      (envelope-line? line)
      state

      (call-log-header? line)
      (-> state (assoc :in-log? true) (update :lines conj (str/trim line)))

      (frame-line? line)
      (let [norm (normalize-frame line)
            expr? (expression-frame? norm)]
        (cond
          (contains? seen norm)     state
          (and (not expr?) (noisy-frame? line)) state
          (and (not expr?) (>= frames max-frames))
          (update state :frames-dropped inc)

          :else (-> state
                  (update :frames inc)
                  (update :seen conj norm)
                  (update :lines conj (str "  " norm)))))

      in-log?
      (if (>= log-lines max-call-log-lines)
        (update state :log-dropped inc)
        (-> state (update :log-lines inc) (update :lines conj (truncate-line (str/trimr line)))))

      :else
      (update state :lines conj (truncate-line (str/trimr line))))))

(defn- clean-block
  "Applies the filtering rules to one cause segment. Anything the budgets
   cut off is reported as a counted trailer, never silently swallowed."
  [^String s]
  (let [{:keys [lines frames-dropped log-dropped]}
        (reduce keep-line
          {:lines [] :frames 0 :log-lines 0 :in-log? false :seen #{}
           :frames-dropped 0 :log-dropped 0}
          (str/split-lines s))

        dropped-frames (long frames-dropped)
        dropped-log (long log-dropped)
        trailer (cond-> []
                  (pos? dropped-frames)
                  (conj (str "  … " dropped-frames " more stack frame(s)"))

                  (pos? dropped-log)
                  (conj (str "  … " dropped-log " more call-log line(s)")))
        kept (vec (dedupe lines))
        ;; A block that is only frames has no message line to indent under,
        ;; so its frames sit flush left rather than half of them stepping in.
        kept (if (every? #(or (str/blank? %) (str/starts-with? % "  at ")) kept)
               (mapv #(cond-> % (str/starts-with? % "  at ") (subs 2)) kept)
               kept)]
    (-> (into kept trailer)
      (->> (str/join "\n"))
      str/trim)))

;; =============================================================================
;; Source code frame (the caret)
;; =============================================================================

(defn- ident-char?
  [^Character c]
  (or (Character/isLetterOrDigit c) (= \_ c) (= \$ c)))

(def ^:private clj-token-stop
  "Characters that end a Clojure token; a symbol may hold anything else."
  #{\space \tab \newline \return \( \) \[ \] \{ \} \" \, \; \'})

(defn- span-while
  ^long [^String text ^long i pred]
  (long (count (take-while pred (subs text (min i (long (count text))))))))

(defn- token-char?
  [clj? ^Character c]
  (if clj? (not (clj-token-stop c)) (ident-char? c)))

(def ^:private ^:const max-token-backtrack
  "How far left the caret may travel to find the start of the token it lands in.
   A minified bundle is one 900-character `token`; walking to its start would
   move the caret an entire screen away from the position the runtime named."
  40)

(defn- caret-start
  "Backs 0-based `i` up to the first character of the token it lands inside. A
   runtime often names a column mid-name — V8 points at a property, the reader
   at a form's head — and caretting from there underlines half a word. A token
   too long to walk keeps the reported column."
  ^long [^String text ^long i clj?]
  (if-not (and (< -1 i (long (count text))) (token-char? clj? (nth text i)))
    i
    (loop [j i]
      (cond
        (or (zero? j) (not (token-char? clj? (nth text (dec j))))) j
        (>= (- i j) max-token-backtrack) i
        :else (recur (dec j))))))

(defn- string-span
  "Columns from an opening quote at `i` through its closing quote."
  ^long [^String text ^long i]
  (let [n (long (count text))]
    (loop [j (inc i)]
      (cond
        (>= j n) (- n i)
        (= \\ (nth text j)) (recur (+ j 2))
        (= \" (nth text j)) (- (inc j) i)
        :else (recur (inc j))))))

(def ^:private ^:const max-form-caret
  "A form wider than this carets only its head token — forty columns of `^`
   point at nothing in particular."
  40)

(defn- form-span
  "Columns from an opening delimiter at `i` through its match on the same line,
   or 0 when the form does not close there."
  ^long [^String text ^long i]
  (let [n (long (count text))
        open (nth text i)
        close ({\( \) \[ \] \{ \}} open)]
    (loop [j (inc i) depth 1]
      (if (>= j n)
        0
        (let [c (nth text j)]
          (cond
            (= \" c) (recur (+ j (string-span text j)) depth)
            (= open c) (recur (inc j) (inc depth))
            (= close c) (if (= 1 depth) (- (inc j) i) (recur (inc j) (dec depth)))
            :else (recur (inc j) depth)))))))

(defn- caret-span
  "Width of the caret run at 0-based `i`: an opening delimiter carets the token
   it opens (Clojure reports the head of a form), a quote carets the whole
   string, a `.` carets the property access, an identifier carets the whole
   identifier, anything else one column."
  ^long [^String text ^long i clj?]
  (let [c (when (< -1 i (long (count text))) (nth text i))]
    (cond
      (nil? c) 1
      (#{\( \[ \{} c) (let [f (form-span text i)]
                        (if (and (pos? f) (<= f max-form-caret))
                          f
                          (inc (span-while text (inc i) (complement clj-token-stop)))))
      (= \" c) (string-span text i)
      (and clj? (not (clj-token-stop c))) (span-while text i (complement clj-token-stop))
      (= \. c) (inc (span-while text (inc i) ident-char?))
      (ident-char? c) (span-while text i ident-char?)
      :else 1)))

(defn- wide-char?
  "True for a code point a terminal paints two columns wide (CJK, Hangul,
   fullwidth forms, emoji)."
  [^long cp]
  (or (<= 0x1100 cp 0x115F) (<= 0x2E80 cp 0xA4CF) (<= 0xAC00 cp 0xD7A3)
    (<= 0xF900 cp 0xFAFF) (<= 0xFE30 cp 0xFE4F) (<= 0xFF00 cp 0xFF60)
    (<= 0xFFE0 cp 0xFFE6) (<= 0x1F300 cp 0x1FAFF) (<= 0x20000 cp 0x3FFFD)))

(defn- code-point-width
  ^long [^long cp]
  (let [t (long (Character/getType (int cp)))]
    (cond
      (or (= t (long (int Character/NON_SPACING_MARK)))
        (= t (long (int Character/ENCLOSING_MARK)))
        (= t (long (int Character/COMBINING_SPACING_MARK)))
        (= t (long (int Character/FORMAT)))) 0
      (wide-char? cp) 2
      :else 1)))

(defn- display-width
  "Terminal columns `s` occupies. A caret row padded by character count drifts
   under CJK or emoji, which are one string index but two columns."
  ^long [^String s]
  (let [n (long (count s))]
    (loop [i 0 w 0]
      (if (>= i n)
        w
        (let [cp (long (.codePointAt s (int i)))]
          (recur (+ i (long (Character/charCount (int cp))))
            (+ w (code-point-width cp))))))))

(defn- window-shift
  "Characters dropped from the left of a source line so that column `offset`
   stays visible inside `code-frame-width`."
  ^long [^String text ^long offset]
  (let [offset (min offset (long (count text)))]
    (if (> offset (- code-frame-width 20))
      (max 0 (- offset (quot code-frame-width 3)))
      0)))

(defn- window-line
  "Renders one source line cut to `code-frame-width` starting `shift`
   characters in, marking each trimmed side with `…`. Every line of one frame
   shares the same `shift`, so the excerpt stays a straight vertical slice."
  [^String text ^long shift]
  (let [text (str/replace text "\t" " ")
        cut (subs text (min shift (long (count text))))
        cut (if (> (long (count cut)) code-frame-width)
              (str (subs cut 0 code-frame-width) "…")
              cut)]
    (if (pos? shift) (str "…" cut) cut)))

(defn- code-frame
  "Babashka-style excerpt of `source`: a numbered window around the 1-based
   `line`, with a caret run under the 1-based `col`. Returns nil when the
   position is not inside the source, so a bogus position never fabricates
   a frame. A nil `col` echoes the lines with no caret row — a compile-time
   SyntaxError names no position at all.

   `context` is how many lines to keep on each side of `line`, defaulting to
   `code-frame-context`. A position-less echo passes a budget wide enough to
   show the whole snippet, since there is no failing line to center on."
  ([^String source line col clj?]
   (code-frame source line col clj? code-frame-context))
  ([^String source line col clj? context]
   (let [lines (vec (str/split-lines (sanitize-source source)))
         n (long (count lines))]
     (when (and line (<= 1 (long line) n))
       (let [i0 (dec (long line))
             lo (max 0 (- i0 (long context)))
             hi (min (dec n) (+ i0 (long context)))
             width (count (str (inc hi)))
             ;; Trailing blanks are not part of the line the reader sees, and a
             ;; caret parked past its last visible glyph reads as a caret row
             ;; wider than the code above it.
             target (str/trimr (str/replace (nth lines i0) "\t" " "))
             len (long (count target))
             ;; A column past the end of the line (Playwright reports one for a
             ;; truncated expression) carets the last character instead of
             ;; silently dropping the caret row.
             col0 (min (max 0 (dec (long (or col 1)))) (max 0 (dec len)))
             col0 (caret-start target col0 clj?)
             shift (window-shift target col0)
             ;; The `…` marker occupies exactly one column of a shifted line.
             lead (if (pos? shift) 1 0)
             gutter (fn [n'] (format (str "  %" width "s | ") n'))]
         (str/join "\n"
           (mapcat
             (fn [idx]
               (let [idx (long idx)
                     text (window-line (nth lines idx) shift)
                     row (str/trimr (str (gutter (inc idx)) text))]
                 (if (and (= idx i0) (pos? len) (some? col))
                   (let [span (caret-span target col0 clj?)
                         seen (subs target col0 (min len (+ col0 span)))
                         pad (+ lead (display-width (subs target (min shift col0) col0)))
                         ;; The caret run stops at the window's right edge: a
                         ;; minified line can hold a 900-character token, and a
                         ;; caret row wider than the source line above it is
                         ;; unreadable in any terminal.
                         room (max 1 (- code-frame-width pad))]
                     [row (str (gutter "")
                            (apply str (repeat pad \space))
                            (apply str (repeat (max 1 (min room (display-width seen))) \^)))])
                   [row])))
             (range lo (inc hi)))))))))

(def ^:private reader-position-re
  "The Clojure reader names the form it could not close: `expected ) to match (
   at [1,1]`. That opening delimiter is the useful spot — far better than the
   end of input the exception itself carries."
  #"to match \S+ at \[(\d+),(\d+)\]")

(def ^:private sci-position-re
  "SCI states a Clojure position inline: `… [at line 2, column 4]`. Once the
   excerpt shows that spot, the marker is redundant and is removed."
  #"\s*\[at line (\d+),? column (\d+)\]")

(def ^:private code-frame-re
  "A rendered excerpt gutter (`  2 | …`). Its presence means the message already
   carries a frame, so `concise` stays idempotent instead of stacking a second."
  #"(?m)^ *\d+ \| ")

(def ^:private ^:const max-echo-lines
  "Line budget for echoing a source back when the failure names no position.
   Big enough for a real evaluated snippet, small enough never to dump a file."
  12)

(defn- short-source?
  "True for a source small enough to echo whole when nothing points into it —
   a compile-time `SyntaxError` names no line, so the code itself is the only
   clue left. A long or minified script is not echoed."
  [source]
  (and (string? source)
    (not (str/blank? source))
    (let [ls (str/split-lines (str/trim source))]
      (and (<= (count ls) max-echo-lines)
        (every? #(<= (count %) code-frame-width) ls)))))

(def ^:private frame-line-re
  "A normalized stack frame line: `at <expression>:1:12`, `at Array.map
   (<anonymous>)`. Frames belong together, so an excerpt is never wedged
   between two of them."
  #"^at \S")

(defn- frame-block-end
  "Index of the last frame in the contiguous run of stack frames starting at
   `idx` — where the excerpt reads best."
  [lines ^long idx]
  (loop [i idx]
    (if (and (< (inc i) (count lines))
          (re-find frame-line-re (str/trim (nth lines (inc i)))))
      (recur (inc i))
      i)))

(defn- with-code-frame
  "Splices the `source` excerpt into `cleaned` directly under the line that
   carries the position — the `at <expression>` frame (JS) or the `[at line …,
   column …]` marker (SCI). An explicitly supplied `:line` has no such line, so
   its excerpt is appended.

   `lang` keeps the two apart: a `:clj` source NEVER reads a JS frame, because
   `(spel/evaluate \"…\")` reports a position inside the JS string it was
   handed, which does not address the Clojure form at all. Returns `cleaned`
   unchanged when nothing resolves."
  [^String cleaned {:keys [source line column lang]}]
  (let [lines   (vec (str/split-lines cleaned))
        framed? (boolean (re-find code-frame-re cleaned))
        idx-of  (fn [re] (first (keep-indexed (fn [i l] (when (re-find re (str/trim l)) i))
                                  lines)))
        at-idx  (when-not (= :clj lang) (idx-of expression-frame-re))
        sci-idx (when-not (or at-idx (= :js lang)) (idx-of sci-position-re))
        rdr-idx (when-not (or at-idx sci-idx (= :js lang)) (idx-of reader-position-re))

        [l c src anchor]
        (cond
          at-idx
          (let [m (re-find expression-frame-re (str/trim (nth lines at-idx)))]
            ;; Playwright trims an expression before evaluating it, so a
            ;; reported line/col counts from the trimmed source.
            [(parse-long (nth m 1)) (parse-long (nth m 2)) (str/trim source) at-idx])

          sci-idx
          (let [m (re-find sci-position-re (nth lines sci-idx))]
            [(parse-long (nth m 1)) (parse-long (nth m 2)) source sci-idx])

          rdr-idx
          (let [m (re-find reader-position-re (nth lines rdr-idx))]
            [(parse-long (nth m 1)) (parse-long (nth m 2)) source rdr-idx])

          line
          [line column source (dec (count lines))]

          ;; A compile-time `SyntaxError` names no position whatsoever, so
          ;; echoing a short expression back is the only way to see what was
          ;; actually evaluated. A long script is left alone.
          (short-source? source)
          [1 nil (str/trim source) (dec (count lines))])
        anchor  (if at-idx (frame-block-end lines (long anchor)) anchor)
        clj?    (nil? at-idx)
        ;; No column means no failing line to center on — the whole snippet is
        ;; the answer, so the window widens to the echo budget.
        frame   (and (not framed?) l (code-frame src l c clj? (if c code-frame-context max-echo-lines)))
        ;; A position outside the source (a stale line number, a truncated
        ;; script) fabricates nothing — but a short expression is still worth
        ;; echoing whole, with no caret to lie about the spot.
        echo?   (and (not framed?) (nil? frame) (short-source? source))
        frame   (if echo? (code-frame (str/trim source) 1 nil clj? max-echo-lines) frame)
        anchor  (if echo? (dec (count lines)) anchor)]
    (if frame
      (let [lines (if sci-idx
                    (update lines sci-idx #(str/trimr (str/replace % sci-position-re "")))
                    lines)]
        (str/join "\n" (concat (take (inc (long anchor)) lines) [""] [frame]
                         (drop (inc (long anchor)) lines))))
      cleaned)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn concise
  "Collapses a raw Playwright/Java error message into the short, readable
   form described in this namespace's docstring.

   Params:
   `msg`  - raw error text (may be nil, multi-line, or a `→`-joined chain).
   `opts` - optional map:
     `:source` - the code that was evaluated; enables the numbered excerpt
                 with the caret under the failing position.
     `:lang`   - `:js` or `:clj`, the language of `:source`. A `:clj` source
                 ignores JS `at <expression>` frames (they address the JS
                 string a Clojure form passed on, not the form).
     `:line`   - 1-based line into `:source`, when the caller already knows it
                 (SCI puts it in ex-data). Without it the position is read from
                 the `at <expression>:line:col` frame.
     `:column` - 1-based column, paired with `:line`.

   Returns:
   The cleaned message, or nil when `msg` is nil or blank. Never returns a
   blank string, never throws: on any internal failure the trimmed original
   is returned."
  ([msg] (concise msg nil))
  ([msg opts]
   (when (and (some? msg) (not (str/blank? (str msg))))
     (let [raw (str msg)]
       (try
         (let [blocks  (->> (sanitize raw)
                         split-causes
                         (map unwrap-part)
                         informative-parts
                         (map clean-block)
                         (remove str/blank?))
               cleaned (->> blocks
                         (map-indexed (fn [i block]
                                        (if (zero? (long i)) block (str "caused by: " block))))
                         (str/join "\n"))
               lines   (str/split-lines cleaned)
               capped  (cond
                         (str/blank? cleaned)
                         ;; Everything was plumbing: show one line, not the wall.
                         (let [ls (str/split-lines (str/trim (sanitize raw)))]
                           (str/join "\n"
                             (cond-> [(truncate-line (str/trim (first ls)))]
                               (> (count ls) 1)
                               (conj (str "… " (dec (count ls))
                                       " more line(s) — full error: `spel logs`")))))

                         (> (count lines) max-lines)
                         (str/join "\n" (conj (vec (take max-lines lines))
                                          (str "… " (- (count lines) max-lines)
                                            " more line(s) — full error: `spel logs`")))

                         :else cleaned)
               src     (:source opts)
               framed  (if (and (string? src) (not (str/blank? src)))
                         (with-code-frame capped opts)
                         capped)
               ;; A message whose every line was plumbing leaves the excerpt
               ;; behind blank lines; an error never opens with whitespace.
               out     (str/trimr (str/replace framed #"\A(?:[ \t]*\n)+" ""))]
           (when-not (str/blank? out) out))
         (catch Throwable _ (str/trim raw)))))))
