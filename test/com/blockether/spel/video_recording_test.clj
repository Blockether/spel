(ns com.blockether.spel.video-recording-test
  (:require
   [clojure.string]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.annotate :as annotate]
   [com.blockether.spel.roles :as role]
   [com.blockether.spel.allure :refer [around defdescribe describe expect it step screenshot]]))

;; =============================================================================
;; Video Recording Tests
;; =============================================================================

(defdescribe video-recording-test
  "Tests for video recording support"

  (around [f] (core/with-testing-browser (f)))

  (describe "records browser session to video file"

    (it "page has video object when recording"
      (core/with-testing-page {:record-video-dir "test-videos" :record-video-size {:width 1280 :height 720}} [pg]
        (expect (some? (page/video pg)))))

    (it "video-path returns a path while recording"
      (core/with-testing-page {:record-video-dir "test-videos" :record-video-size {:width 1280 :height 720}} [pg]
        (let [vpath (core/video-path pg)]
          (expect (string? vpath))
          (expect (.contains ^String vpath "test-videos"))))))

  (describe "video file exists after context closes"

    (it "creates a video file"
      (core/with-testing-page {:record-video-dir "test-videos" :record-video-size {:width 1280 :height 720}} [_]
        (let [video-dir "test-videos-lifecycle"
              ctx  (core/new-context core/*testing-browser* {:record-video-dir video-dir
                                                             :record-video-size {:width 1280 :height 720}})
              pg   (core/new-page-from-context ctx)]
          (page/navigate pg "https://example.org")
          (Thread/sleep 500)
          (let [vpath (core/video-path pg)]
            (expect (string? vpath))
            (core/close-page! pg)
            (core/close-context! ctx)
          ;; After context close, video file should exist
            (expect (.exists (java.io.File. vpath)))
            (expect (> (.length (java.io.File. vpath)) 0))
          ;; Cleanup
            (.delete (java.io.File. vpath)))))))

  (describe "video-save-as copies video"

    (it "saves video to specified path"
      (core/with-testing-page {:record-video-dir "test-videos" :record-video-size {:width 1280 :height 720}} [_]
        (let [video-dir "test-videos-save"
              save-path "test-videos-save/copy.webm"
              ctx  (core/new-context core/*testing-browser* {:record-video-dir video-dir
                                                             :record-video-size {:width 640 :height 480}})
              pg   (core/new-page-from-context ctx)]
          (page/navigate pg "https://example.org")
          (Thread/sleep 500)
          (core/close-page! pg)
          (core/close-context! ctx)
          (core/video-save-as! pg save-path)
          (expect (.exists (java.io.File. save-path)))
        ;; Cleanup
          (.delete (java.io.File. save-path))
          (let [vpath (core/video-path pg)]
            (when vpath (.delete (java.io.File. vpath))))))))

  (describe "with-video-page-opts records a meaningful browser session"
    (it "navigates pages, annotates elements, and interacts with UI"
      (core/with-testing-page {:record-video-dir "test-videos-fixture" :record-video-size {:width 1280 :height 720}} [pg]
      ;; Verify video-path binding
        (expect (string? (core/video-path pg)))
        (expect (clojure.string/includes? (core/video-path pg) "test-videos-fixture"))

      ;; Step 1: Navigate to example.org
        (step "Navigate to example.org"
          (page/navigate pg "https://example.org")
          (page/wait-for-load-state pg))

      ;; Step 2: Capture accessibility snapshot and annotate
        (step "Annotate example.org"
          (let [snap (snapshot/capture-snapshot pg)
                refs (:refs snap)]
            (annotate/inject-overlays! pg refs)
            (Thread/sleep 800)
            (screenshot pg "example.org with annotations")
            (annotate/remove-overlays! pg)))

      ;; Step 3: Load a custom interactive page
        (step "Load interactive demo page"
          (page/set-content! pg
            (str "<html><head><title>spel Video Demo</title>"
              "<style>"
              "body{font-family:system-ui;margin:2rem;background:#f8f9fb}"
              "h1{color:#1a1d23}"
              ".card{background:#fff;border:1px solid #e2e5ea;border-radius:8px;padding:1.5rem;margin:1rem 0;box-shadow:0 1px 3px rgba(0,0,0,.06)}"
              "button{background:#2EAD33;color:#fff;border:none;padding:0.6rem 1.2rem;border-radius:6px;cursor:pointer;font-size:1rem}"
              "input{padding:0.5rem;border:1px solid #ccc;border-radius:4px;width:200px}"
              ".result{color:#2EAD33;font-weight:bold;margin-top:0.5rem;display:none}"
              "</style></head><body>"
              "<h1>spel Video Demo</h1>"
              "<div class='card'><h2>Search</h2>"
              "<input id='q' type='text' placeholder='Type something...'>"
              "<button onclick=\"document.getElementById('result').style.display='block'\">Search</button>"
              "<div id='result' class='result'>Found 42 results</div></div>"
              "<div class='card'><h2>Navigation</h2>"
              "<a href='#about'>About</a> | <a href='#docs'>Docs</a> | <a href='#contact'>Contact</a></div>"
              "<div class='card'><h2>Features</h2>"
              "<ul><li>Browser automation</li><li>Test reporting</li>"
              "<li>Accessibility snapshots</li><li>Visual annotations</li></ul></div>"
              "</body></html>"))
          (page/wait-for-load-state pg))

      ;; Step 4: Annotate the interactive page
        (step "Annotate interactive page"
          (let [snap (snapshot/capture-snapshot pg)
                refs (:refs snap)]
            (annotate/inject-overlays! pg refs)
            (Thread/sleep 800)
            (screenshot pg "Interactive page annotated")
            (annotate/remove-overlays! pg)))

      ;; Step 5: Fill search input and click
        (step "Search interaction"
          (locator/fill (page/get-by-placeholder pg "Type something...") "Playwright")
          (Thread/sleep 300)
          (locator/click (page/get-by-role pg role/button {:name "Search"}))
          (Thread/sleep 500)
          (screenshot pg "After search"))

      ;; Step 6: Verify result appeared
        (step "Verify search result"
          (let [result (page/locator pg "#result")]
            (expect (locator/is-visible? result))
            (expect (clojure.string/includes? (locator/text-content result) "42 results"))))

      ;; Step 7: Final annotated screenshot
        (step "Final annotated state"
          (let [snap (snapshot/capture-snapshot pg)
                refs (:refs snap)]
            (annotate/inject-overlays! pg refs)
            (Thread/sleep 500)
            (screenshot pg "Final state with annotations")))))))
