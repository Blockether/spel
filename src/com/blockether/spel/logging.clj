(ns com.blockether.spel.logging
  "The one logging system for spel.

   Every spel process — the CLI client, the background daemon, the browser
   engine helpers — writes through this namespace, so all diagnostics land in
   ONE place with ONE format instead of scattered `println` calls that vanish
   into a redirected stdout.

   Layout:
   - One log file per session: `<tmpdir>/spel-<session>.log` (also the file the
     daemon subprocess appends its raw stdout/stderr to).
   - One line format: `<iso-ts> <LEVEL> [<component>] <message>`.
   - One level threshold: `SPEL_LOG_LEVEL` (debug|info|warn|error|off), or
     `SPEL_DEBUG=true` for debug. Default: info.

   Read it back with `spel logs` (see `read-lines`, `tail!`)."
  (:require
   [clojure.string :as str])
  (:import
   [java.io File RandomAccessFile]
   [java.nio.charset StandardCharsets]
   [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
   [java.time Instant ZoneOffset]
   [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Levels
;; =============================================================================

(def ^:private level-rank
  {:debug 0 :info 1 :warn 2 :error 3 :off 99})

(defn parse-level
  "Parses a level name (string or keyword) into a level keyword.
   Returns `default-level` when the input is blank or unrecognized."
  [x default-level]
  (let [s (some-> x name str/trim str/lower-case)]
    (case s
      ("debug" "trace" "all") :debug
      "info" :info
      ("warn" "warning") :warn
      "error" :error
      ("off" "none" "silent") :off
      default-level)))

(defn- env-level
  "Resolves the default threshold from the environment."
  []
  (cond
    (seq (str (or (System/getenv "SPEL_LOG_LEVEL") ""))) (parse-level (System/getenv "SPEL_LOG_LEVEL") :info)
    (= "true" (System/getenv "SPEL_DEBUG")) :debug
    :else :info))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private !state
  (atom {:session   "default"
         :component "spel"
         :level     nil            ;; nil → resolve from env on first use
         :mirror    :warn          ;; mirror this level and above to stderr
         :file?     true}))

(defn log-file-path
  "Returns the log file path for a session — spel's single log sink."
  ^Path [^String session]
  (Path/of (str (System/getProperty "java.io.tmpdir")
             File/separator
             "spel-" session ".log")
    (into-array String [])))

(defn init!
  "Configures the logger for this process. Call once at startup.

   Params:
   `opts` - Map:
     :session   - String session name (default \"default\")
     :component - String tag shown in every line, e.g. \"cli\" or \"daemon\"
     :level     - Keyword/String threshold; defaults to SPEL_LOG_LEVEL/SPEL_DEBUG
     :mirror    - Keyword/String level mirrored to stderr, or :off to never
                  mirror (the daemon uses :off — its stderr already appends to
                  the same log file, so mirroring would double every line)
     :file?     - Boolean, write to the session log file (default true)"
  [{:keys [session component level mirror file?]}]
  (swap! !state
    (fn [st]
      (cond-> st
        session          (assoc :session session)
        component        (assoc :component component)
        level            (assoc :level (parse-level level :info))
        mirror           (assoc :mirror (parse-level mirror :warn))
        (some? file?)    (assoc :file? (boolean file?)))))
  nil)

(defn current-level
  "Returns the active threshold keyword."
  []
  (or (:level @!state) (env-level)))

(defn enabled?
  "True when `level` passes the active threshold."
  [level]
  (>= (long (get level-rank level 1))
    (long (get level-rank (current-level) 1))))

;; =============================================================================
;; Formatting
;; =============================================================================

(def ^:private ^DateTimeFormatter ts-formatter
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    ZoneOffset/UTC))

(defn timestamp
  "Formats an Instant as the log's fixed-width UTC timestamp."
  ^String [^Instant instant]
  (.format ts-formatter instant))

(defn format-line
  "Builds one canonical log line: `<ts> <LEVEL> [<component>] <message>`.
   Pure — the single place the wire format is defined."
  ^String [^String ts level ^String component ^String message]
  (str ts " "
    (format "%-5s" (str/upper-case (name level)))
    " [" component "] "
    (str/trim-newline (str message))))

;; =============================================================================
;; Writing
;; =============================================================================

(defn- append-line!
  "Appends one line to the session log file. Best-effort: logging must never
   break a browser command."
  [^Path path ^String line]
  (try
    (Files/write path
      (.getBytes (str line "\n") StandardCharsets/UTF_8)
      ^"[Ljava.nio.file.OpenOption;"
      (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND]))
    (catch Exception _ nil)))

(defn log!
  "Logs `message` at `level` through the one sink. No-op below threshold."
  [level message]
  (when (enabled? level)
    (let [{:keys [session component mirror file?]} @!state
          line (format-line (timestamp (Instant/now)) level component message)]
      (when file?
        (append-line! (log-file-path session) line))
      (when (>= (long (get level-rank level 1))
              (long (get level-rank mirror 2)))
        (binding [*out* *err*]
          (println line)))))
  nil)

(defn debug!
  "Logs a debug line. Parts are `str`-joined."
  [& parts]
  (log! :debug (apply str parts)))

(defn info!
  "Logs an info line. Parts are `str`-joined."
  [& parts]
  (log! :info (apply str parts)))

(defn warn!
  "Logs a warning line. Parts are `str`-joined."
  [& parts]
  (log! :warn (apply str parts)))

(defn error!
  "Logs an error line. Parts are `str`-joined."
  [& parts]
  (log! :error (apply str parts)))

(defn- origin-frame
  "The most useful frame of `e`'s stack: the first that names spel, Playwright or
   user-script code, else the top frame.

   A warning that says only `Broken pipe` names what went wrong and never where,
   which is why whole classes of daemon failures were undiagnosable from the
   session log."
  [^Throwable e]
  (let [frames (.getStackTrace e)]
    (when (pos? (alength frames))
      (str (or (first (filter (fn [^StackTraceElement el]
                                (let [c (.getClassName el)]
                                  (or (str/starts-with? c "com.blockether.")
                                    (str/starts-with? c "com.microsoft.playwright.")
                                    (str/starts-with? c "sci."))))
                        frames))
             (aget frames 0))))))

(defn describe-throwable
  "Renders a throwable as ONE log line: class, message, origin frame, and up to
   three causes.

   `(.getMessage e)` alone is empty for entire exception families — NPEs,
   ArityExceptions raised inside a proxy, most IOExceptions — so logging just
   the message produced literally blank warnings such as `WARN [daemon]
   ios-snapshot:`. Nil-safe: a nil throwable renders instead of throwing inside
   a handler that is already failing."
  [^Throwable e]
  (if (nil? e)
    "<no exception>"
    (let [head   (str (.getName (class e)) ": "
                   (or (not-empty (str (.getMessage e))) "<no message>"))
          origin (origin-frame e)
          causes (loop [^Throwable c (.getCause e)
                        n            0
                        acc          ""]
                   (if (and (some? c) (< n 3))
                     (recur (.getCause c) (inc n)
                       (str acc " <- " (.getName (class c)) ": "
                         (or (not-empty (str (.getMessage c))) "<no message>")))
                     acc))]
      (str head (when origin (str " at " origin)) causes))))

(def ^:private max-trace-frames
  "How many stack frames `full-trace` prints. A StackOverflowError arrives
   carrying a thousand near-identical frames; the cycle that produced it is
   always visible at the top, so the rest is elided with a count instead of
   filling the session log."
  40)

(defn full-trace
  "Renders `e` as `describe-throwable` followed by its stack frames, one per
   line, capped at `max-trace-frames`.

   The one-line rendering names the origin frame and nothing below it, which is
   enough for a lone failure and useless for a repeating one: issue #125 could
   not be diagnosed from the log because every WARN line stopped at the top
   frame. Callers print this once per burst, never per event.

   Params:
   `e` - Throwable; nil renders instead of throwing.

   Returns:
   A multi-line String."
  [^Throwable e]
  (if (nil? e)
    "<no exception>"
    (let [frames (vec (.getStackTrace e))
          shown  (subvec frames 0 (min (count frames) (long max-trace-frames)))
          more   (- (count frames) (count shown))]
      (str/join "\n"
        (concat [(describe-throwable e)]
          (map #(str "    at " %) shown)
          (when (pos? more) [(str "    ... " more " more frames")]))))))

(defn exception!
  "Logs an exception with a context label at warn level. Used in cleanup paths
   where we continue despite errors but never swallow them silently.

   Logs the full `describe-throwable` rendering, not just the message."
  [^String context ^Throwable e]
  (log! :warn (str context ": " (describe-throwable e))))

;; =============================================================================
;; Reading
;; =============================================================================

(defn log-exists?
  "True when the session has a log file on disk."
  [^String session]
  (Files/exists (log-file-path session) (into-array LinkOption [])))

(defn read-lines
  "Returns the last `n` lines of a session's log (all of them when `n` is nil).
   Returns an empty vector when there is no log file yet."
  [^String session {:keys [lines]}]
  (let [path (log-file-path session)]
    (if-not (Files/exists path (into-array LinkOption []))
      []
      (let [all (vec (str/split-lines (String. (Files/readAllBytes path) StandardCharsets/UTF_8)))
            all (if (= [""] all) [] all)
            n   (long (or lines -1))]
        (if (and (pos? n) (> (count all) n))
          (subvec all (- (count all) n))
          all)))))

(defn clear!
  "Truncates a session's log file. Returns true when a file was cleared."
  [^String session]
  (let [path (log-file-path session)]
    (if (Files/exists path (into-array LinkOption []))
      (do (Files/write path (byte-array 0)
            ^"[Ljava.nio.file.OpenOption;"
            (into-array OpenOption [StandardOpenOption/TRUNCATE_EXISTING]))
          true)
      false)))

(defn tail!
  "Prints a session's log and then follows it, printing appended lines as they
   arrive. Blocks until interrupted.

   Params:
   `session` - String session name
   `opts`    - Map: :lines (initial tail size), :poll-ms (default 250)"
  [^String session {:keys [lines poll-ms] :or {poll-ms 250}}]
  (doseq [l (read-lines session {:lines lines})]
    (println l))
  (.flush *out*)
  (let [path (log-file-path session)
        file (.toFile path)]
    (loop [offset (if (.exists file) (.length file) 0)]
      (Thread/sleep (long poll-ms))
      (let [len (if (.exists file) (.length file) 0)
            ;; Truncation (e.g. `spel logs --clear`) restarts from the top.
            offset (if (< len offset) 0 offset)]
        (if (> len offset)
          (let [raf (RandomAccessFile. file "r")]
            (try
              (.seek raf offset)
              (let [buf (byte-array (- len offset))]
                (.readFully raf buf)
                (print (String. buf StandardCharsets/UTF_8))
                (.flush *out*))
              (finally (.close raf)))
            (recur len))
          (recur offset))))))
