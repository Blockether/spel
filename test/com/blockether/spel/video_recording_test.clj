(ns com.blockether.spel.video-recording-test
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.test-fixtures :as tf :refer [*page* *browser* with-playwright with-browser with-video-page with-video-page-opts]])
)

;; =============================================================================
;; Video Recording Tests
;; =============================================================================

(defdescribe video-recording-test
  "Tests for video recording support"

  (describe "records browser session to video file"
    {:context [with-playwright with-browser (with-video-page-opts {:video-dir "test-videos"})]}

    (it "page has video object when recording"
      (expect (some? (page/video *page*))))

    (it "video-path returns a path while recording"
      (let [vpath (core/video-path *page*)]
        (expect (string? vpath))
        (expect (.contains ^String vpath "test-videos")))))

  (describe "video file exists after context closes"
    {:context [with-playwright with-browser]}

    (it "creates a video file"
      (let [video-dir "test-videos-lifecycle"
            ctx  (core/new-context *browser* {:record-video-dir video-dir
                                               :record-video-size {:width 1280 :height 720}})
            pg   (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        (Thread/sleep 500)
        (let [vpath (core/video-path pg)]
          (expect (string? vpath))
          (core/close-page! pg)
          (core/close-context! ctx)
          ;; After context close, video file should exist
          (expect (.exists (java.io.File. vpath)))
          (expect (> (.length (java.io.File. vpath)) 0))
          ;; Cleanup
          (.delete (java.io.File. vpath))))))

  (describe "video-save-as copies video"
    {:context [with-playwright with-browser]}

    (it "saves video to specified path"
      (let [video-dir "test-videos-save"
            save-path "test-videos-save/copy.webm"
            ctx  (core/new-context *browser* {:record-video-dir video-dir
                                               :record-video-size {:width 640 :height 480}})
            pg   (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        (Thread/sleep 500)
        (core/close-page! pg)
        (core/close-context! ctx)
        (core/video-save-as! pg save-path)
        (expect (.exists (java.io.File. save-path)))
        ;; Cleanup
        (.delete (java.io.File. save-path))
        (let [vpath (core/video-path pg)]
          (when vpath (.delete (java.io.File. vpath))))))))
