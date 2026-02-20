(ns com.blockether.spel.sci-eval-video-test
  "Tests for video recording through SCI eval."
  (:require
   [com.blockether.spel.sci-env :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
)

(defdescribe sci-video-recording-test
  "Video recording via SCI eval"

  (describe "start and finish video recording"
    (it "records and produces a video file"
      ;; Reset SCI atoms
      (reset! sut/!pw nil) (reset! sut/!browser nil)
      (reset! sut/!context nil) (reset! sut/!page nil)
      (reset! sut/!daemon-mode? false)
      (let [ctx (sut/create-sci-ctx)]
        (try
          ;; Start browser
          (expect (= :started (sut/eval-string ctx "(spel/start!)")))

          ;; Start video recording
          (let [result (sut/eval-string ctx "(spel/start-video-recording {:video-dir \"test-videos-sci\"})")]
            (expect (map? result))
            (expect (= "recording" (:status result)))
            (expect (= "test-videos-sci" (:video-dir result))))

          ;; Navigate to generate some video content
          (sut/eval-string ctx "(spel/goto \"https://example.com\")")
          (sut/eval-string ctx "(spel/sleep 500)")

          ;; Check video path is available
          (let [vpath (sut/eval-string ctx "(spel/video-path)")]
            (expect (string? vpath))
            (expect (.contains ^String vpath "test-videos-sci")))

          ;; Finish recording
          (let [result (sut/eval-string ctx "(spel/finish-video-recording)")]
            (expect (map? result))
            (expect (= "stopped" (:status result)))
            (expect (string? (:video-path result)))
            ;; Video file should exist after context close
            (let [vpath (:video-path result)]
              (expect (.exists (java.io.File. vpath)))))

          ;; Should have a new page without video
          (let [vpath (sut/eval-string ctx "(spel/video-path)")]
            (expect (nil? vpath)))

          (finally
            (sut/eval-string ctx "(spel/stop!)"))))))

  (describe "video-path is nil without recording"
    (it "returns nil when not recording"
      (reset! sut/!pw nil) (reset! sut/!browser nil)
      (reset! sut/!context nil) (reset! sut/!page nil)
      (reset! sut/!daemon-mode? false)
      (let [ctx (sut/create-sci-ctx)]
        (try
          (expect (= :started (sut/eval-string ctx "(spel/start!)")))
          (let [vpath (sut/eval-string ctx "(spel/video-path)")]
            (expect (nil? vpath)))
          (finally
            (sut/eval-string ctx "(spel/stop!)")))))))
