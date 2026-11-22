(ns com.blockether.spel.stitch
  "Vertical image stitching via Playwright browser rendering.
   Combines multiple screenshot PNGs into one by rendering them as HTML
   and taking a full-page screenshot — no AWT/ImageIO dependency."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.image-util :as image]
   [com.blockether.spel.page :as page]))

(defn- png-width
  ^long [path]
  (image/png-axis-size (image/file->bytes path) 16))

(defn- build-html [base64-images {:keys [overlap-px] :or {overlap-px 0}}]
  (let [overlap (long overlap-px)]
    (str "<!DOCTYPE html><html><head><style>"
      "* { margin: 0; padding: 0; } "
      "body { width: fit-content; } "
      "img { display: block; } "
      (when (> overlap 0)
        (str ".trimmed { margin-top: -" overlap "px; }"))
      "</style></head><body>"
      (apply str
        (map-indexed
          (fn [i b64]
            (str "<img src=\"data:image/png;base64," b64 "\""
              (when (and (> overlap 0) (> (long i) 0))
                " class=\"trimmed\"")
              "/>"))
          base64-images))
      "</body></html>")))

(defn read-image
  "Reads an image file and returns its base64 encoding.
   (Replaces the old AWT BufferedImage version.)"
  [path]
  (image/bytes->base64 (image/file->bytes path)))

(defn stitch-vertical
  "Stitch multiple images vertically into one PNG using Playwright.
   Returns the output path."
  [paths ^String out-path]
  (when (empty? paths)
    (throw (IllegalArgumentException. "stitch-vertical requires at least one image path")))
  (let [max-w    (long (apply max (map png-width paths)))
        b64s     (mapv read-image paths)
        html     (build-html b64s {})
        launch-fn (image/resolve-launch-fn nil)]
    (core/with-playwright [pw]
      (image/ensure-playwright! pw)
      (core/with-browser [browser (launch-fn pw {:headless true})]
        (core/with-page [pg (core/new-page browser)]
          (page/set-viewport-size! pg max-w 1)
          (page/set-content! pg html)
          (page/wait-for-load-state pg :networkidle)
          (page/screenshot pg {:path out-path :full-page true}))))
    out-path))

(defn stitch-vertical-overlap
  "Stitch images vertically, overlapping by `overlap-px` pixels.
   Uses negative margin to overlap subsequent images."
  [paths out-path {:keys [overlap-px browser-type] :or {overlap-px 0}}]
  (when (empty? paths)
    (throw (IllegalArgumentException. "stitch-vertical-overlap requires at least one image path")))
  (let [max-w    (long (apply max (map png-width paths)))
        b64s     (mapv read-image paths)
        html     (build-html b64s {:overlap-px (or overlap-px 0)})
        launch-fn (image/resolve-launch-fn browser-type)]
    (core/with-playwright [pw]
      (image/ensure-playwright! pw)
      (core/with-browser [browser (launch-fn pw {:headless true})]
        (core/with-page [pg (core/new-page browser)]
          (page/set-viewport-size! pg max-w 1)
          (page/set-content! pg html)
          (page/wait-for-load-state pg :networkidle)
          (page/screenshot pg {:path out-path :full-page true}))))
    out-path))
