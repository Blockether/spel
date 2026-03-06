(ns com.blockether.spel.visual-diff-test
  "Tests for pixel-level screenshot comparison (pixelmatch via Playwright Canvas)."
  (:require
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.visual-diff :as sut])
  (:import
   [java.awt Color Graphics2D]
   [java.awt.image BufferedImage]
   [java.io ByteArrayOutputStream File]
   [javax.imageio ImageIO]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- create-solid-png
  "Creates a solid-color PNG as byte array."
  ^bytes [^long w ^long h ^Color color]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D g (.createGraphics img)]
    (.setColor g color)
    (.fillRect g 0 0 w h)
    (.dispose g)
    (let [baos (ByteArrayOutputStream.)]
      (ImageIO/write img "png" baos)
      (.toByteArray baos))))

(defn- create-half-and-half-png
  "Creates a PNG with left half one color, right half another."
  ^bytes [^long w ^long h ^Color left-color ^Color right-color]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D g (.createGraphics img)
        half-w (quot w 2)]
    (.setColor g left-color)
    (.fillRect g 0 0 half-w h)
    (.setColor g right-color)
    (.fillRect g half-w 0 (- w half-w) h)
    (.dispose g)
    (let [baos (ByteArrayOutputStream.)]
      (ImageIO/write img "png" baos)
      (.toByteArray baos))))

(def ^:private tmpdir (System/getProperty "java.io.tmpdir"))

(defn- tmp-path ^String [^String name]
  (str (File. tmpdir name)))

(defn- write-bytes [^String path ^bytes bs]
  (java.nio.file.Files/write
    (java.nio.file.Path/of path (into-array String []))
    bs
    (into-array java.nio.file.OpenOption [])))

(defn- cleanup [& paths]
  (doseq [p paths]
    (let [f (File. ^String p)]
      (when (.exists f) (.delete f)))))

(def ^:private html-baseline
  "<!DOCTYPE html>
<html><head><style>
  body { margin: 0; font-family: sans-serif; background: white; }
  .header { background: #2563eb; color: white; padding: 20px; font-size: 24px; }
  .nav { display: flex; gap: 16px; padding: 12px 20px; background: #f1f5f9; }
  .nav a { color: #1e40af; text-decoration: none; font-size: 16px; }
  .content { padding: 40px 20px; }
  .content h1 { font-size: 32px; margin-bottom: 16px; }
  .content p { font-size: 16px; color: #334155; line-height: 1.6; max-width: 600px; }
  .cta { margin-top: 24px; }
  .cta button { background: #2563eb; color: white; border: none; padding: 12px 24px;
                font-size: 16px; border-radius: 6px; cursor: pointer; }
  .footer { position: fixed; bottom: 0; width: 100%; background: #1e293b;
            color: #94a3b8; padding: 12px 20px; font-size: 14px; }
</style></head><body>
  <div class='header'>My Application</div>
  <nav class='nav'>
    <a href='/home'>Home</a>
    <a href='/about'>About</a>
    <a href='/contact'>Contact</a>
  </nav>
  <div class='content'>
    <h1>Welcome to Our Platform</h1>
    <p>Build amazing applications with our powerful tools and intuitive interface.
       Get started today and see the difference.</p>
    <div class='cta'>
      <button>Get Started</button>
    </div>
  </div>
  <div class='footer'>© 2026 My Application. All rights reserved.</div>
</body></html>")

(def ^:private html-text-changed
  "Same page but button text and heading changed."
  (.replace (.replace ^String html-baseline
              "Get Started" "Sign Up Now")
    "Welcome to Our Platform" "Welcome to Your Dashboard"))

(def ^:private html-element-added
  "Same page but with a banner added between nav and content."
  (.replace ^String html-baseline
    "<div class='content'>"
    "<div style='background:#fef3c7;padding:12px 20px;font-size:14px;color:#92400e;border-bottom:1px solid #fcd34d;'>🔔 New feature: Dark mode is now available! <a href='/settings'>Enable it</a></div><div class='content'>"))

(def ^:private html-style-changed
  "Same page but header background color changed."
  (.replace ^String html-baseline
    "background: #2563eb" "background: #dc2626"))

(def ^:private html-multiple-changes
  "Multiple changes: heading text, button text, AND header color."
  (-> html-baseline
    (.replace "Get Started" "Launch App")
    (.replace "Welcome to Our Platform" "Dashboard Ready")
    (.replace "background: #2563eb" "background: #059669")))

;; =============================================================================
;; Tests
;; =============================================================================

(defdescribe png-dimensions-test
  "PNG header parsing"
  (describe "valid PNG"
    (it "extracts correct width and height"
      (let [img (create-solid-png 320 240 Color/RED)
            dims (#'sut/png-dimensions img)]
        (expect (= 320 (:width dims)))
        (expect (= 240 (:height dims))))))

  (describe "edge case"
    (it "handles 1x1 image"
      (let [img (create-solid-png 1 1 Color/BLACK)
            dims (#'sut/png-dimensions img)]
        (expect (= 1 (:width dims)))
        (expect (= 1 (:height dims)))))

    (it "throws on invalid data"
      (let [threw? (atom false)]
        (try
          (#'sut/png-dimensions (byte-array 5))
          (catch Exception _
            (reset! threw? true)))
        (expect (true? @threw?))))))

(defdescribe compare-identical-test
  "Comparing identical screenshots"
  (describe "identical images"
    (it "returns matched=true with 0% diff"
      (let [img (create-solid-png 100 100 Color/BLUE)
            result (sut/compare-screenshots img img)]
        (expect (true? (:matched result)))
        (expect (zero? (:diff-count result)))
        (expect (= 0.0 (:diff-percent result)))
        (expect (= 10000 (:total-pixels result)))
        (expect (= 100 (:width result)))
        (expect (= 100 (:height result)))
        (expect (false? (:dimension-mismatch result)))
        (expect (bytes? (:diff-image result)))
        (allure/attach-bytes "identical-input" img "image/png")
        (allure/attach-bytes "identical-diff" (:diff-image result) "image/png")))))

(defdescribe compare-different-test
  "Comparing different screenshots"
  (describe "solid color vs different solid color"
    (it "detects difference between opposite colors"
      (let [red (create-solid-png 50 50 Color/RED)
            blue (create-solid-png 50 50 Color/BLUE)
            result (sut/compare-screenshots red blue)]
        (expect (false? (:matched result)))
        (expect (pos? (:diff-count result)))
        (expect (> (:diff-percent result) 0.0))
        (expect (= 2500 (:total-pixels result)))
        (expect (false? (:dimension-mismatch result)))
        (allure/attach-bytes "input-red" red "image/png")
        (allure/attach-bytes "input-blue" blue "image/png")
        (allure/attach-bytes "diff-result" (:diff-image result) "image/png"))))

  (describe "partially different"
    (it "detects partial differences"
      (let [baseline (create-solid-png 100 100 Color/WHITE)
            current  (create-half-and-half-png 100 100 Color/WHITE Color/BLACK)
            result   (sut/compare-screenshots baseline current)]
        (expect (false? (:matched result)))
        (expect (pos? (:diff-count result)))
        ;; About half the pixels should differ
        (expect (> (:diff-percent result) 20.0))
        (expect (< (:diff-percent result) 80.0))
        (allure/attach-bytes "baseline-white" baseline "image/png")
        (allure/attach-bytes "current-half" current "image/png")
        (allure/attach-bytes "diff-partial" (:diff-image result) "image/png"))))

  (describe "very small images"
    (it "handles 1x1 comparisons"
      (let [black (create-solid-png 1 1 Color/BLACK)
            white (create-solid-png 1 1 Color/WHITE)
            result (sut/compare-screenshots black white)]
        (expect (= 1 (:total-pixels result)))
        (expect (= 1 (:width result)))
        (expect (= 1 (:height result)))
        (expect (false? (:dimension-mismatch result)))
        (allure/attach-bytes "tiny-black" black "image/png")
        (allure/attach-bytes "tiny-white" white "image/png")
        (allure/attach-bytes "tiny-diff" (:diff-image result) "image/png")))))

(defdescribe compare-dimension-mismatch-test
  "Comparing screenshots with different dimensions"
  (describe "different sizes"
    (it "reports dimension mismatch"
      (let [small (create-solid-png 50 50 Color/RED)
            large (create-solid-png 100 100 Color/RED)
            result (sut/compare-screenshots small large)]
        (expect (true? (:dimension-mismatch result)))
        (expect (= {:width 50 :height 50} (:baseline-dimensions result)))
        (expect (= {:width 100 :height 100} (:current-dimensions result)))
        (expect (= 100 (:width result)))
        (expect (= 100 (:height result)))
        (allure/attach-bytes "small-red" small "image/png")
        (allure/attach-bytes "large-red" large "image/png")
        (allure/attach-bytes "dimension-diff" (:diff-image result) "image/png")))))

(defdescribe region-detection-test
  "Connected-component region extraction from visual diffs"
  (describe "identical images"
    (it "returns empty regions"
      (let [img (create-solid-png 64 64 Color/BLACK)
            result (sut/compare-screenshots img img)]
        (expect (true? (:matched result)))
        (expect (= [] (:regions result))))))

  (describe "different images"
    (it "returns regions with expected structure and valid labels"
      (let [baseline (create-solid-png 120 80 Color/WHITE)
            current (create-half-and-half-png 120 80 Color/WHITE Color/BLACK)
            result (sut/compare-screenshots baseline current)
            regions (:regions result)
            valid-labels #{"added" "removed" "content-change"}]
        (expect (false? (:matched result)))
        (expect (vector? regions))
        (expect (pos? (count regions)))
        (doseq [region regions]
          (expect (integer? (:id region)))
          (expect (contains? valid-labels (:label region)))
          (expect (integer? (:pixels region)))
          (expect (map? (:bounding-box region)))
          (expect (integer? (get-in region [:bounding-box :x])))
          (expect (integer? (get-in region [:bounding-box :y])))
          (expect (integer? (get-in region [:bounding-box :width])))
          (expect (integer? (get-in region [:bounding-box :height]))))
        (allure/attach-bytes "region-baseline" baseline "image/png")
        (allure/attach-bytes "region-current" current "image/png")
        (allure/attach-bytes "region-diff" (:diff-image result) "image/png")))))

(defdescribe compare-files-test
  "File-based comparison"
  (describe "file convenience function"
    (it "compares files from disk"
      (let [p1 (tmp-path "vdiff-f1.png")
            p2 (tmp-path "vdiff-f2.png")]
        (try
          (write-bytes p1 (create-solid-png 80 60 Color/GREEN))
          (write-bytes p2 (create-solid-png 80 60 Color/GREEN))
          (let [result (sut/compare-screenshot-files p1 p2)]
            (expect (true? (:matched result)))
            (expect (zero? (:diff-count result)))
            (allure/attach-file "file-1" p1 "image/png")
            (allure/attach-file "file-2" p2 "image/png")
            (allure/attach-bytes "file-diff" (:diff-image result) "image/png"))
          (finally
            (cleanup p1 p2)))))))

(defdescribe diff-path-output-test
  "Saving diff image to file"
  (describe "diff-path option"
    (it "saves diff image to specified path"
      (let [out (tmp-path "vdiff-output.png")
            img1 (create-solid-png 40 40 Color/RED)
            img2 (create-solid-png 40 40 Color/BLUE)]
        (try
          (sut/compare-screenshots img1 img2 :diff-path out)
          (expect (.exists (File. out)))
          (expect (> (.length (File. out)) 0))
          (allure/attach-bytes "diff-path-input-1" img1 "image/png")
          (allure/attach-bytes "diff-path-input-2" img2 "image/png")
          (allure/attach-file "saved-diff" out "image/png")
          (finally
            (cleanup out)))))))

(defdescribe threshold-test
  "Threshold sensitivity"
  (describe "threshold affects results"
    (it "strict threshold catches more diffs than loose"
      (let [img1 (create-solid-png 50 50 (Color. 200 200 200))
            img2 (create-solid-png 50 50 (Color. 210 210 210))
            strict (sut/compare-screenshots img1 img2 :threshold 0.01)
            loose  (sut/compare-screenshots img1 img2 :threshold 0.5)]
        (expect (>= (:diff-count strict) (:diff-count loose)))
        (allure/attach-bytes "threshold-img-1" img1 "image/png")
        (allure/attach-bytes "threshold-img-2" img2 "image/png")
        (allure/attach-bytes "threshold-strict-diff" (:diff-image strict) "image/png")
        (allure/attach-bytes "threshold-loose-diff" (:diff-image loose) "image/png")))))

;; =============================================================================
;; Semantic Region Enrichment — Pure Function Tests
;; =============================================================================

(defdescribe enrich-regions-test
  "Bbox overlap matching for semantic region labels"

  (describe "empty inputs"
    (it "returns regions unchanged when refs are empty"
      (let [regions [{:id 1 :label "content-change" :pixels 100
                      :bounding-box {:x 10 :y 10 :width 50 :height 30}}]
            result (sut/enrich-regions regions {})]
        (expect (= regions result))))

    (it "returns empty vector when regions are empty"
      (let [result (sut/enrich-regions [] {"e1" {:role "button" :name "Click" :bbox {:x 0 :y 0 :width 100 :height 50}}})]
        (expect (= [] result)))))

  (describe "single region matching single element"
    (it "enriches region with overlapping element"
      (let [regions [{:id 1 :label "content-change" :pixels 100
                      :bounding-box {:x 10 :y 10 :width 80 :height 30}}]
            refs {"eabcde" {:role "button" :name "Submit"
                            :bbox {:x 10 :y 10 :width 80 :height 30}}}
            result (sut/enrich-regions regions refs)
            r (first result)]
        (expect (= "eabcde" (get-in r [:element :ref])))
        (expect (= "button" (get-in r [:element :role])))
        (expect (= "Submit" (get-in r [:element :name])))
        (expect (= 1.0 (get-in r [:element :overlap])))
        (expect (string? (:semantic-label r)))
        (expect (pos? (count (:elements r)))))))

  (describe "region overlapping multiple elements"
    (it "selects smallest element as primary"
      (let [regions [{:id 1 :label "content-change" :pixels 200
                      :bounding-box {:x 0 :y 0 :width 200 :height 50}}]
            refs {"ebig00" {:role "navigation" :name "Main Nav"
                            :bbox {:x 0 :y 0 :width 400 :height 60}}
                  "esmall" {:role "link" :name "Home"
                            :bbox {:x 10 :y 10 :width 60 :height 30}}}
            result (sut/enrich-regions regions refs)
            r (first result)]
        (expect (= "esmall" (get-in r [:element :ref])))
        (expect (= "link" (get-in r [:element :role])))
        (expect (= 2 (count (:elements r)))))))

  (describe "no overlap"
    (it "does not enrich region with non-overlapping elements"
      (let [regions [{:id 1 :label "content-change" :pixels 50
                      :bounding-box {:x 0 :y 0 :width 20 :height 20}}]
            refs {"efar00" {:role "button" :name "Far Away"
                            :bbox {:x 500 :y 500 :width 40 :height 40}}}
            result (sut/enrich-regions regions refs)
            r (first result)]
        (expect (nil? (:element r)))
        (expect (nil? (:elements r)))
        (expect (nil? (:semantic-label r)))))))

;; =============================================================================
;; Semantic Region Enrichment — REAL Playwright Integration Tests
;; =============================================================================

(defdescribe semantic-diff-integration-test
  "Real screenshot diff with Playwright — verifies semantic enrichment
   against actual HTML pages with known visual changes."

  (describe "text change detection"
    (it "identifies button and heading text changes with element labels"
      (core/with-playwright [pw]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [pg1 (core/new-page browser)]
            (page/set-viewport-size! pg1 800 600)
            (page/set-content! pg1 html-baseline)
            (page/wait-for-load-state pg1 :load)
            (let [baseline-bytes (page/screenshot pg1)
                  baseline-snap (snapshot/capture-snapshot pg1)]
              (page/set-content! pg1 html-text-changed)
              (page/wait-for-load-state pg1 :load)
              (let [current-bytes (page/screenshot pg1)
                    current-snap (snapshot/capture-snapshot pg1)
                    result (sut/compare-screenshots baseline-bytes current-bytes
                             :current-refs (:refs current-snap)
                             :baseline-refs (:refs baseline-snap))
                    regions (:regions result)
                    enriched (filter :element regions)]
                (expect (false? (:matched result)))
                (expect (pos? (count regions)))
                (expect (pos? (count enriched)))
                (doseq [r enriched]
                  (expect (string? (get-in r [:element :ref])))
                  (expect (string? (get-in r [:element :role])))
                  (expect (number? (get-in r [:element :overlap])))
                  (expect (string? (:semantic-label r))))
                (allure/attach-bytes "text-change-baseline" baseline-bytes "image/png")
                (allure/attach-bytes "text-change-current" current-bytes "image/png")
                (allure/attach-bytes "text-change-diff" (:diff-image result) "image/png")
                (allure/attach "text-change-regions"
                  (pr-str (mapv #(dissoc % :diff-image) regions)) "text/plain"))))))))

  (describe "element addition detection"
    (it "detects new banner with semantic label"
      (core/with-playwright [pw]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [pg1 (core/new-page browser)]
            (page/set-viewport-size! pg1 800 600)
            (page/set-content! pg1 html-baseline)
            (page/wait-for-load-state pg1 :load)
            (let [baseline-bytes (page/screenshot pg1)
                  baseline-snap (snapshot/capture-snapshot pg1)]
              (page/set-content! pg1 html-element-added)
              (page/wait-for-load-state pg1 :load)
              (let [current-bytes (page/screenshot pg1)
                    current-snap (snapshot/capture-snapshot pg1)
                    result (sut/compare-screenshots baseline-bytes current-bytes
                             :current-refs (:refs current-snap)
                             :baseline-refs (:refs baseline-snap))
                    regions (:regions result)]
                (expect (false? (:matched result)))
                (expect (pos? (count regions)))
                (expect (pos? (:diff-count result)))
                (allure/attach-bytes "addition-baseline" baseline-bytes "image/png")
                (allure/attach-bytes "addition-current" current-bytes "image/png")
                (allure/attach-bytes "addition-diff" (:diff-image result) "image/png")
                (allure/attach "addition-regions"
                  (pr-str (mapv #(dissoc % :diff-image) regions)) "text/plain"))))))))

  (describe "style change detection"
    (it "detects header color change"
      (core/with-playwright [pw]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [pg1 (core/new-page browser)]
            (page/set-viewport-size! pg1 800 600)
            (page/set-content! pg1 html-baseline)
            (page/wait-for-load-state pg1 :load)
            (let [baseline-bytes (page/screenshot pg1)]
              (page/set-content! pg1 html-style-changed)
              (page/wait-for-load-state pg1 :load)
              (let [current-bytes (page/screenshot pg1)
                    current-snap (snapshot/capture-snapshot pg1)
                    result (sut/compare-screenshots baseline-bytes current-bytes
                             :current-refs (:refs current-snap))
                    regions (:regions result)
                    enriched (filter :element regions)]
                (expect (false? (:matched result)))
                (expect (pos? (count regions)))
                (expect (pos? (count enriched)))
                (allure/attach-bytes "style-baseline" baseline-bytes "image/png")
                (allure/attach-bytes "style-current" current-bytes "image/png")
                (allure/attach-bytes "style-diff" (:diff-image result) "image/png")
                (allure/attach "style-regions"
                  (pr-str (mapv #(dissoc % :diff-image) regions)) "text/plain"))))))))

  (describe "multiple changes detection"
    (it "detects heading, button, and header changes as separate enriched regions"
      (core/with-playwright [pw]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [pg1 (core/new-page browser)]
            (page/set-viewport-size! pg1 800 600)
            (page/set-content! pg1 html-baseline)
            (page/wait-for-load-state pg1 :load)
            (let [baseline-bytes (page/screenshot pg1)
                  baseline-snap (snapshot/capture-snapshot pg1)]
              (page/set-content! pg1 html-multiple-changes)
              (page/wait-for-load-state pg1 :load)
              (let [current-bytes (page/screenshot pg1)
                    current-snap (snapshot/capture-snapshot pg1)
                    result (sut/compare-screenshots baseline-bytes current-bytes
                             :current-refs (:refs current-snap)
                             :baseline-refs (:refs baseline-snap))
                    regions (:regions result)
                    enriched (filter :element regions)
                    roles (set (map #(get-in % [:element :role]) enriched))]
                (expect (false? (:matched result)))
                (expect (>= (count regions) 2))
                (expect (pos? (count enriched)))
                (allure/attach-bytes "multi-baseline" baseline-bytes "image/png")
                (allure/attach-bytes "multi-current" current-bytes "image/png")
                (allure/attach-bytes "multi-diff" (:diff-image result) "image/png")
                (allure/attach "multi-regions"
                  (pr-str (mapv #(dissoc % :diff-image) regions)) "text/plain")
                (allure/attach "multi-roles" (pr-str roles) "text/plain"))))))))

  (describe "compare-pages convenience"
    (it "produces enriched regions from two live pages"
      (core/with-playwright [pw]
        (core/with-browser [browser (core/launch-chromium pw {:headless true})]
          (core/with-page [pg1 (core/new-page browser)]
            (core/with-page [pg2 (core/new-page browser)]
              (page/set-viewport-size! pg1 800 600)
              (page/set-viewport-size! pg2 800 600)
              (page/set-content! pg1 html-baseline)
              (page/set-content! pg2 html-text-changed)
              (page/wait-for-load-state pg1 :load)
              (page/wait-for-load-state pg2 :load)
              (let [result (sut/compare-pages pg1 pg2)]
                (expect (false? (:matched result)))
                (expect (string? (:baseline-snapshot result)))
                (expect (string? (:current-snapshot result)))
                (expect (pos? (count (:regions result))))
                (let [enriched (filter :element (:regions result))]
                  (expect (pos? (count enriched)))
                  (doseq [r enriched]
                    (expect (string? (:semantic-label r)))))
                (allure/attach-bytes "pages-diff" (:diff-image result) "image/png")
                (allure/attach "pages-baseline-snap" (:baseline-snapshot result) "text/plain")
                (allure/attach "pages-current-snap" (:current-snapshot result) "text/plain")
                (allure/attach "pages-regions"
                  (pr-str (mapv #(dissoc % :diff-image) (:regions result))) "text/plain")))))))))
