(ns com.blockether.spel.stitch
  "Vertical image stitching via Playwright browser rendering.
   Combines multiple screenshot PNGs into one by rendering them as HTML
   and taking a full-page screenshot — no AWT/ImageIO dependency."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [java.nio.file Files Path]
   [java.util Base64]))

(defn- file->base64 [^String path]
  (let [bytes (Files/readAllBytes (Path/of path (into-array String [])))]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn- build-html [base64-images {:keys [overlap-px] :or {overlap-px 0}}]
  (let [overlap (long overlap-px)]
    (str "<!DOCTYPE html><html><head><style>"
         "* { margin: 0; padding: 0; } "
         "body { display: flex; flex-direction: column; width: fit-content; } "
         "img { display: block; } "
         (when (pos? overlap)
           (str ".trimmed { margin-top: -" overlap "px; }"))
         "</style></head><body>"
         (apply str
                (map-indexed
                 (fn [i b64]
                   (str "<img src=\"data:image/png;base64," b64 "\""
                        (when (and (pos? overlap) (pos? i))
                          " class=\"trimmed\"")
                        "/>"))
                 base64-images))
         "</body></html>")))

(defn stitch-vertical
  "Stitch multiple images vertically into one PNG using Playwright.
   Returns the output path."
  [paths ^String out-path]
  (let [b64s (mapv file->base64 paths)
        html (build-html b64s {})]
    (core/with-playwright [pw]
      (core/with-browser [browser (core/launch-chromium pw {:headless true})]
        (core/with-page [pg (core/new-page browser)]
          (page/set-content! pg html)
          (page/wait-for-load-state pg :networkidle)
          (page/screenshot pg {:path out-path :full-page true}))))
    out-path))

(defn stitch-vertical-overlap
  "Stitch images vertically, overlapping by `overlap-px` pixels.
   Uses negative margin to overlap subsequent images."
  [paths out-path {:keys [overlap-px] :or {overlap-px 0}}]
  (let [b64s (mapv file->base64 paths)
        html (build-html b64s {:overlap-px (or overlap-px 0)})]
    (core/with-playwright [pw]
      (core/with-browser [browser (core/launch-chromium pw {:headless true})]
        (core/with-page [pg (core/new-page browser)]
          (page/set-content! pg html)
          (page/wait-for-load-state pg :networkidle)
          (page/screenshot pg {:path out-path :full-page true}))))
    out-path))

(defn read-image
  "Reads an image file and returns its base64 encoding.
   (Replaces the old AWT BufferedImage version.)"
  [path]
  (file->base64 path))
