(ns com.blockether.spel.stitch
  "Vertical image stitching utility. Combines multiple screenshots into one."
  (:import
   [java.awt.image BufferedImage]
   [java.io File]
   [javax.imageio ImageIO]))

(defn read-image ^BufferedImage [path]
  (ImageIO/read (File. ^String path)))

(defn stitch-vertical
  "Stitch multiple images vertically into one PNG.
   All images must have the same width (uses the max width, pads narrower images).
   Returns the output path."
  [paths ^String out-path]
  (let [images  (mapv read-image paths)
        width   (long (apply max (map #(.getWidth ^BufferedImage %) images)))
        total-h (long (reduce + (map #(.getHeight ^BufferedImage %) images)))
        out     (BufferedImage. width total-h BufferedImage/TYPE_INT_ARGB)
        g       (.createGraphics out)]
    (reduce (fn [^long y ^BufferedImage img]
              (.drawImage g img (int 0) (int y) nil)
              (+ y (.getHeight img)))
      (long 0) images)
    (.dispose g)
    (ImageIO/write out "png" (File. ^String out-path))
    out-path))

(defn stitch-vertical-overlap
  "Stitch images vertically, trimming `overlap-px` from the top of each image
   after the first (to remove duplicate content from scrolling).
   overlap-px default: 0"
  [paths out-path {:keys [overlap-px] :or {overlap-px 0}}]
  (if (zero? (long overlap-px))
    (stitch-vertical paths out-path)
    (let [images    (mapv read-image paths)
          first-img ^BufferedImage (first images)
          rest-imgs (rest images)
          overlap   (long overlap-px)
          ;; Trim overlap-px from top of each subsequent image
          trimmed   (mapv (fn [^BufferedImage img]
                            (let [h    (long (.getHeight img))
                                  trim (long (min overlap (dec h)))]
                              (.getSubimage img 0 (int trim) (.getWidth img) (int (- h trim)))))
                      rest-imgs)
          all-imgs  (into [first-img] trimmed)
          width     (long (apply max (map #(.getWidth ^BufferedImage %) all-imgs)))
          total-h   (long (reduce + (map #(.getHeight ^BufferedImage %) all-imgs)))
          out       (BufferedImage. width total-h BufferedImage/TYPE_INT_ARGB)
          g         (.createGraphics out)]
      (reduce (fn [^long y ^BufferedImage img]
                (.drawImage g img (int 0) (int y) nil)
                (+ y (.getHeight img)))
        (long 0) all-imgs)
      (.dispose g)
      (ImageIO/write out "png" (File. ^String out-path))
      out-path)))
