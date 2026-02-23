(ns com.blockether.spel.stitch-test
  "Tests for the vertical image stitching utility."
  (:require
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.stitch :as sut])
  (:import
   [java.awt Color Graphics2D]
   [java.awt.image BufferedImage]
   [java.io File]
   [java.nio.file Files]
   [javax.imageio ImageIO]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private tmpdir (System/getProperty "java.io.tmpdir"))

(defn- tmp-path
  "Returns a cross-platform temp file path."
  ^String [^String name]
  (str (File. tmpdir name)))

(defn- create-test-image
  "Creates a solid-color PNG at `path` with given dimensions. Returns path."
  ^String [^String path ^long w ^long h ^Color color]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        ^Graphics2D g (.createGraphics img)]
    (.setColor g color)
    (.fillRect g 0 0 w h)
    (.dispose g)
    (ImageIO/write img "png" (File. path))
    path))

(defn- read-dimensions
  "Returns [width height] of the image at path."
  [^String path]
  (let [img (ImageIO/read (File. path))]
    [(.getWidth img) (.getHeight img)]))

(defn- cleanup [& paths]
  (doseq [p paths]
    (let [f (File. ^String p)]
      (when (.exists f) (.delete f)))))

;; =============================================================================
;; Tests
;; =============================================================================

(defdescribe stitch-vertical-test
  "Tests for stitch-vertical"

  (describe "basic stitching"
    (it "stitches 3 images with correct dimensions"
      (let [f1  (create-test-image (tmp-path "stitch-t1.png") 100 50 Color/RED)
            f2  (create-test-image (tmp-path "stitch-t2.png") 100 60 Color/GREEN)
            f3  (create-test-image (tmp-path "stitch-t3.png") 100 40 Color/BLUE)
            out (tmp-path "stitch-out.png")]
        (try
          (sut/stitch-vertical [f1 f2 f3] out)
          (let [[w h] (read-dimensions out)]
            (expect (= 100 w))
            (expect (= 150 h)))
          (allure/attach-file "input-1" f1 "image/png")
          (allure/attach-file "input-2" f2 "image/png")
          (allure/attach-file "input-3" f3 "image/png")
          (allure/attach-bytes "stitched-output"
            (Files/readAllBytes (.toPath (File. out))) "image/png")
          (finally
            (cleanup f1 f2 f3 out)))))

    (it "handles images with different widths (uses max)"
      (let [f1  (create-test-image (tmp-path "stitch-w1.png") 80 30 Color/RED)
            f2  (create-test-image (tmp-path "stitch-w2.png") 120 30 Color/GREEN)
            out (tmp-path "stitch-wout.png")]
        (try
          (sut/stitch-vertical [f1 f2] out)
          (let [[w h] (read-dimensions out)]
            (expect (= 120 w))
            (expect (= 60 h)))
          (allure/attach-file "input-1" f1 "image/png")
          (allure/attach-file "input-2" f2 "image/png")
          (allure/attach-bytes "stitched-output"
            (Files/readAllBytes (.toPath (File. out))) "image/png")
          (finally
            (cleanup f1 f2 out)))))))

(defdescribe stitch-vertical-overlap-test
  "Tests for stitch-vertical-overlap"

  (describe "overlap trimming"
    (it "with overlap-px=0 produces same result as stitch-vertical"
      (let [f1  (create-test-image (tmp-path "stitch-o1.png") 100 50 Color/RED)
            f2  (create-test-image (tmp-path "stitch-o2.png") 100 60 Color/GREEN)
            out (tmp-path "stitch-oout.png")]
        (try
          (sut/stitch-vertical-overlap [f1 f2] out {:overlap-px 0})
          (let [[w h] (read-dimensions out)]
            (expect (= 100 w))
            (expect (= 110 h)))
          (allure/attach-file "input-1" f1 "image/png")
          (allure/attach-file "input-2" f2 "image/png")
          (allure/attach-bytes "stitched-output"
            (Files/readAllBytes (.toPath (File. out))) "image/png")
          (finally
            (cleanup f1 f2 out)))))

    (it "trims overlap-px from top of subsequent images"
      (let [f1  (create-test-image (tmp-path "stitch-ov1.png") 100 50 Color/RED)
            f2  (create-test-image (tmp-path "stitch-ov2.png") 100 60 Color/GREEN)
            f3  (create-test-image (tmp-path "stitch-ov3.png") 100 40 Color/BLUE)
            out (tmp-path "stitch-ovout.png")]
        (try
          ;; overlap=10: img2 becomes 50, img3 becomes 30 → total = 50+50+30 = 130
          (sut/stitch-vertical-overlap [f1 f2 f3] out {:overlap-px 10})
          (let [[w h] (read-dimensions out)]
            (expect (= 100 w))
            (expect (= 130 h)))
          (allure/attach-file "input-1" f1 "image/png")
          (allure/attach-file "input-2" f2 "image/png")
          (allure/attach-file "input-3" f3 "image/png")
          (allure/attach-bytes "stitched-output"
            (Files/readAllBytes (.toPath (File. out))) "image/png")
          (finally
            (cleanup f1 f2 f3 out)))))))

(defdescribe stitch-empty-test
  "Tests for edge cases"

  (describe "empty input"
    (it "throws on empty list"
      (let [threw? (atom false)]
        (try
          (sut/stitch-vertical [] (tmp-path "stitch-empty.png"))
          (catch Exception _
            (reset! threw? true)))
        (expect (true? @threw?))))))
