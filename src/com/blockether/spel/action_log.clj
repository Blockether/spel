(ns com.blockether.spel.action-log
  "Pure functions for action log management and SRT subtitle generation.

   The action log records user-facing browser commands with timestamps,
   enabling SRT subtitle export for video overlays. All functions are
   pure — no atoms, no side effects, no Playwright dependency.

   Action entry shape:
     {:idx       long          ;; 1-based sequence number
      :timestamp long          ;; epoch millis (System/currentTimeMillis)
      :action    string        ;; command name (\"click\", \"navigate\", etc.)
      :target    string|nil    ;; ref or selector (\"@e12345\")
      :args      map|nil       ;; additional arguments
      :session   string|nil}   ;; session name

   SRT format:
     1
     00:00:01,200 --> 00:00:03,500
     click @e12345

     2
     00:00:03,500 --> 00:00:05,800
     fill @e67890 \"search text\""
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Time Formatting
;; =============================================================================

(defn millis->srt-time
  "Converts a millisecond offset to SRT time format: HH:MM:SS,mmm
   Example: 3661200 → \"01:01:01,200\""
  ^String [^long millis]
  (let [ms  (mod millis 1000)
        sec (mod (quot millis 1000) 60)
        min (mod (quot millis 60000) 60)
        hr  (quot millis 3600000)]
    (format "%02d:%02d:%02d,%03d" hr min sec ms)))

;; =============================================================================
;; Action Description Formatting
;; =============================================================================

(defn format-action-description
  "Formats an action entry into a human-readable description for subtitles.

   Examples:
     {:action \"click\" :target \"@e12345\"} → \"click @e12345\"
     {:action \"navigate\" :args {\"url\" \"https://example.org\"}} → \"navigate https://example.org\"
     {:action \"fill\" :target \"@e67890\" :args {\"value\" \"hello\"}} → \"fill @e67890 \\\"hello\\\"\"
     {:action \"press\" :target \"@e11111\" :args {\"key\" \"Enter\"}} → \"press @e11111 Enter\""
  ^String [entry]
  (let [action (:action entry)
        target (:target entry)
        args   (:args entry)]
    (cond
      ;; navigate — show the URL
      (= "navigate" action)
      (str "navigate " (or (get args "url") (get args :url) target ""))

      ;; fill — show target and quoted value
      (= "fill" action)
      (let [value (or (get args "value") (get args :value) "")]
        (if target
          (str "fill " target " \"" value "\"")
          (str "fill \"" value "\"")))

      ;; type — show target and quoted text
      (= "type" action)
      (let [text (or (get args "text") (get args :text) "")]
        (if target
          (str "type " target " \"" text "\"")
          (str "type \"" text "\"")))

      ;; press — show target and key
      (= "press" action)
      (let [key (or (get args "key") (get args :key) "")]
        (if target
          (str "press " target " " key)
          (str "press " key)))

      ;; select — show target and selected values
      (= "select" action)
      (let [values (or (get args "values") (get args :values)
                     (get args "value") (get args :value) "")]
        (if target
          (str "select " target " " values)
          (str "select " values)))

      ;; screenshot — show path if available
      (= "screenshot" action)
      (let [path (or (get args "path") (get args :path))]
        (if path
          (str "screenshot → " path)
          "screenshot"))

      ;; scroll — show direction/amount
      (= "scroll" action)
      (let [x (or (get args "deltaX") (get args "delta-x") (get args :delta-x) 0)
            y (or (get args "deltaY") (get args "delta-y") (get args :delta-y) 0)]
        (if target
          (str "scroll " target " Δ" x "," y)
          (str "scroll Δ" x "," y)))

      ;; Generic: action + target
      target
      (str action " " target)

      ;; Generic: just the action name
      :else
      action)))

;; =============================================================================
;; SRT Generation
;; =============================================================================

(defn actions->srt
  "Converts a vector of action log entries to an SRT subtitle string.

   Each action becomes one subtitle cue. Timing is computed as offsets from
   the first entry's timestamp. Each cue's end time is the next cue's start
   time (or start + 3000ms for the last cue).

   Options:
     :min-duration-ms  Minimum cue duration in ms (default: 1000)
     :max-duration-ms  Maximum cue duration in ms (default: 5000)

   Returns an SRT-formatted string, or empty string if entries is empty."
  ([entries] (actions->srt entries {}))
  ([entries opts]
   (if (empty? entries)
     ""
     (let [min-dur   (long (or (:min-duration-ms opts) 1000))
           max-dur   (long (or (:max-duration-ms opts) 5000))
           sorted    (sort-by :timestamp entries)
           t0        (long (:timestamp (first sorted)))
           n         (long (count sorted))]
       (->> (map-indexed
              (fn [i entry]
                (let [i         (long i)
                      start-ms  (- (long (:timestamp entry)) t0)
                      ;; End time = next entry's start, clamped to [min-dur, max-dur]
                      raw-end   (long (if (< i (dec n))
                                        (- (long (:timestamp (nth sorted (inc i)))) t0)
                                        (+ start-ms max-dur)))
                      duration  (- raw-end start-ms)
                      clamped   (max min-dur (min max-dur duration))
                      end-ms    (+ start-ms clamped)
                      desc      (format-action-description entry)]
                  (str (inc i) "\n"
                    (millis->srt-time start-ms) " --> " (millis->srt-time end-ms) "\n"
                    desc)))
              sorted)
         (str/join "\n\n")
         (str/trimr))))))
