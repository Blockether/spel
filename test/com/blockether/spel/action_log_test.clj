(ns com.blockether.spel.action-log-test
  "Unit tests for action log SRT generation functions.
   No browser or daemon required — tests pure functions only."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.action-log :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; millis->srt-time
;; =============================================================================

(defdescribe millis-to-srt-time-test
  "Tests for millis->srt-time conversion"

  (describe "zero offset"
    (it "returns 00:00:00,000 for 0 ms"
      (expect (= "00:00:00,000" (sut/millis->srt-time 0)))))

  (describe "sub-second values"
    (it "converts 500ms correctly"
      (expect (= "00:00:00,500" (sut/millis->srt-time 500))))

    (it "converts 1ms correctly"
      (expect (= "00:00:00,001" (sut/millis->srt-time 1)))))

  (describe "seconds"
    (it "converts 1 second"
      (expect (= "00:00:01,000" (sut/millis->srt-time 1000))))

    (it "converts 59 seconds"
      (expect (= "00:00:59,000" (sut/millis->srt-time 59000)))))

  (describe "minutes"
    (it "converts 1 minute"
      (expect (= "00:01:00,000" (sut/millis->srt-time 60000))))

    (it "converts 59 minutes 59 seconds"
      (expect (= "00:59:59,000" (sut/millis->srt-time 3599000)))))

  (describe "hours"
    (it "converts 1 hour"
      (expect (= "01:00:00,000" (sut/millis->srt-time 3600000))))

    (it "converts 1 hour 1 minute 1 second 200ms"
      (expect (= "01:01:01,200" (sut/millis->srt-time 3661200))))))

;; =============================================================================
;; format-action-description
;; =============================================================================

(defdescribe format-action-description-test
  "Tests for human-readable action description formatting"

  (describe "navigate"
    (it "includes the URL"
      (expect (= "navigate https://example.org"
                (sut/format-action-description
                  {:action "navigate" :args {"url" "https://example.org"}})))))

  (describe "click"
    (it "shows the target ref"
      (expect (= "click @e12345"
                (sut/format-action-description
                  {:action "click" :target "@e12345"})))))

  (describe "fill"
    (it "shows target and quoted value"
      (expect (= "fill @e67890 \"search text\""
                (sut/format-action-description
                  {:action "fill" :target "@e67890" :args {"value" "search text"}})))))

  (describe "type"
    (it "shows target and quoted text"
      (expect (= "type @e11111 \"hello world\""
                (sut/format-action-description
                  {:action "type" :target "@e11111" :args {"text" "hello world"}})))))

  (describe "press"
    (it "shows target and key name"
      (expect (= "press @e22222 Enter"
                (sut/format-action-description
                  {:action "press" :target "@e22222" :args {"key" "Enter"}})))))

  (describe "screenshot"
    (it "shows the output path"
      (expect (= "screenshot → /tmp/shot.png"
                (sut/format-action-description
                  {:action "screenshot" :args {"path" "/tmp/shot.png"}}))))

    (it "shows just 'screenshot' without path"
      (expect (= "screenshot"
                (sut/format-action-description
                  {:action "screenshot"})))))

  (describe "scroll"
    (it "shows delta values"
      (expect (= "scroll Δ0,300"
                (sut/format-action-description
                  {:action "scroll" :args {"deltaY" 300}})))))

  (describe "generic actions"
    (it "shows action + target for hover"
      (expect (= "hover @e33333"
                (sut/format-action-description
                  {:action "hover" :target "@e33333"}))))

    (it "shows just the action name when no target"
      (expect (= "reload"
                (sut/format-action-description
                  {:action "reload"}))))))

;; =============================================================================
;; actions->srt
;; =============================================================================

(defdescribe actions-to-srt-test
  "Tests for SRT subtitle generation from action entries"

  (describe "empty log"
    (it "returns empty string"
      (expect (= "" (sut/actions->srt [])))))

  (describe "single entry"
    (it "generates one SRT cue"
      (let [entries [{:idx 1 :timestamp 1000000 :action "navigate"
                      :args {"url" "https://example.org"}}]
            srt (sut/actions->srt entries)]
        (expect (str/starts-with? srt "1\n"))
        (expect (str/includes? srt "00:00:00,000 --> "))
        (expect (str/includes? srt "navigate https://example.org")))))

  (describe "multiple entries"
    (it "generates numbered cues with correct timing"
      (let [t0  1000000
            entries [{:idx 1 :timestamp t0 :action "navigate"
                      :args {"url" "https://example.org"}}
                     {:idx 2 :timestamp (+ t0 2000) :action "click"
                      :target "@e123"}
                     {:idx 3 :timestamp (+ t0 5000) :action "fill"
                      :target "@e456" :args {"value" "test"}}]
            srt (sut/actions->srt entries)]
        ;; Should have 3 cues
        (expect (str/includes? srt "1\n"))
        (expect (str/includes? srt "2\n"))
        (expect (str/includes? srt "3\n"))
        ;; First cue starts at 00:00:00
        (expect (str/includes? srt "00:00:00,000 --> "))
        ;; Second cue starts at 00:00:02
        (expect (str/includes? srt "00:00:02,000 --> "))
        ;; Third cue starts at 00:00:05
        (expect (str/includes? srt "00:00:05,000 --> "))
        ;; Contains action descriptions
        (expect (str/includes? srt "navigate https://example.org"))
        (expect (str/includes? srt "click @e123"))
        (expect (str/includes? srt "fill @e456 \"test\"")))))

  (describe "timing boundaries"
    (it "clamps short gaps to min-duration-ms"
      (let [t0  1000000
            entries [{:idx 1 :timestamp t0 :action "click" :target "@a"}
                     {:idx 2 :timestamp (+ t0 100) :action "click" :target "@b"}]
            ;; Default min-duration is 1000ms
            srt (sut/actions->srt entries)]
        ;; First cue should end at 00:00:01,000 (min-duration) not 00:00:00,100
        (expect (str/includes? srt "00:00:00,000 --> 00:00:01,000"))))

    (it "clamps long gaps to max-duration-ms"
      (let [t0  1000000
            entries [{:idx 1 :timestamp t0 :action "click" :target "@a"}
                     {:idx 2 :timestamp (+ t0 30000) :action "click" :target "@b"}]
            ;; Default max-duration is 5000ms
            srt (sut/actions->srt entries)]
        ;; First cue should end at 00:00:05,000 (max-duration) not 00:00:30,000
        (expect (str/includes? srt "00:00:00,000 --> 00:00:05,000")))))

  (describe "custom options"
    (it "respects min-duration-ms"
      (let [entries [{:idx 1 :timestamp 1000000 :action "click" :target "@a"}
                     {:idx 2 :timestamp 1000200 :action "click" :target "@b"}]
            srt (sut/actions->srt entries {:min-duration-ms 500})]
        (expect (str/includes? srt "00:00:00,000 --> 00:00:00,500"))))

    (it "respects max-duration-ms"
      (let [entries [{:idx 1 :timestamp 1000000 :action "click" :target "@a"}
                     {:idx 2 :timestamp 1060000 :action "click" :target "@b"}]
            srt (sut/actions->srt entries {:max-duration-ms 10000})]
        (expect (str/includes? srt "00:00:00,000 --> 00:00:10,000")))))

  (describe "SRT format compliance"
    (it "uses correct cue separator (blank line)"
      (let [entries [{:idx 1 :timestamp 1000000 :action "click" :target "@a"}
                     {:idx 2 :timestamp 1002000 :action "click" :target "@b"}]
            srt (sut/actions->srt entries)]
        ;; SRT cues are separated by blank lines
        (expect (str/includes? srt "\n\n"))))

    (it "uses correct time format with comma separator"
      (let [entries [{:idx 1 :timestamp 1000000 :action "click" :target "@a"}]
            srt (sut/actions->srt entries)]
        ;; SRT uses comma for millisecond separator (not dot)
        (expect (re-find #"\d{2}:\d{2}:\d{2},\d{3} --> \d{2}:\d{2}:\d{2},\d{3}" srt))))))
