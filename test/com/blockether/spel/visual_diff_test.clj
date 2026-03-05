(ns com.blockether.spel.visual-diff-test
  "Tests for pixel-level screenshot comparison (pixelmatch via Playwright Canvas)."
  (:require
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it]]
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
