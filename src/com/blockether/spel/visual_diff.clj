(ns com.blockether.spel.visual-diff
  "Pixel-level screenshot comparison using the pixelmatch algorithm.
   Runs inside Playwright's Canvas API — no AWT/ImageIO dependency,
   GraalVM native-image safe.

   Based on pixelmatch by Mapbox (ISC license):
   https://github.com/mapbox/pixelmatch"
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page])
  (:import
   [java.nio.file Files Path]
   [java.util Base64]))

(def ^:private js-pixelmatch
  "/*
   * pixelmatch by Mapbox (ISC License)
   * https://github.com/mapbox/pixelmatch
   */
function pixelmatch(img1, img2, output, width, height, options) {
    if (!options) options = {};
    var threshold = options.threshold === undefined ? 0.1 : options.threshold;
    var alpha = options.alpha === undefined ? 0.1 : options.alpha;
    var includeAA = options.includeAA || false;
    var diffColor = options.diffColor || [255, 0, 0];
    var diffColorAlt = options.diffColorAlt || diffColor;
    var aaColor = options.aaColor || [255, 255, 0];
    var diffMask = options.diffMask || false;

    var len = width * height;
    var a32 = new Uint32Array(img1.buffer, img1.byteOffset, len);
    var b32 = new Uint32Array(img2.buffer, img2.byteOffset, len);
    var identical = true;
    for (var i = 0; i < len; i++) {
        if (a32[i] !== b32[i]) { identical = false; break; }
    }
    if (identical) {
        if (output && !diffMask) {
            for (var i = 0; i < len; i++) drawGrayPixel(img1, 4 * i, alpha, output);
        }
        return 0;
    }

    var maxDelta = 35215 * threshold * threshold;
    var aaR = aaColor[0], aaG = aaColor[1], aaB = aaColor[2];
    var diffR = diffColor[0], diffG = diffColor[1], diffB = diffColor[2];
    var altR = diffColorAlt[0], altG = diffColorAlt[1], altB = diffColorAlt[2];
    var diff = 0;

    for (var y = 0; y < height; y++) {
        for (var x = 0; x < width; x++) {
            var i = y * width + x;
            var pos = i * 4;
            var delta = a32[i] === b32[i] ? 0 : colorDelta(img1, img2, pos, pos, false);
            if (Math.abs(delta) > maxDelta) {
                var isExcludedAA = !includeAA && (antialiased(img1, x, y, width, height, a32, b32) || antialiased(img2, x, y, width, height, b32, a32));
                if (isExcludedAA) {
                    if (output && !diffMask) drawPixel(output, pos, aaR, aaG, aaB);
                } else {
                    if (output) {
                        if (delta < 0) drawPixel(output, pos, altR, altG, altB);
                        else drawPixel(output, pos, diffR, diffG, diffB);
                    }
                    diff++;
                }
            } else if (output && !diffMask) {
                drawGrayPixel(img1, pos, alpha, output);
            }
        }
    }
    return diff;
}

function antialiased(img, x1, y1, width, height, a32, b32) {
    var x0 = Math.max(x1 - 1, 0);
    var y0 = Math.max(y1 - 1, 0);
    var x2 = Math.min(x1 + 1, width - 1);
    var y2 = Math.min(y1 + 1, height - 1);
    var pos = y1 * width + x1;
    var zeroes = x1 === x0 || x1 === x2 || y1 === y0 || y1 === y2 ? 1 : 0;
    var min = 0, max = 0, minX = 0, minY = 0, maxX = 0, maxY = 0;

    for (var x = x0; x <= x2; x++) {
        for (var y = y0; y <= y2; y++) {
            if (x === x1 && y === y1) continue;
            var delta = colorDelta(img, img, pos * 4, (y * width + x) * 4, true);
            if (delta === 0) {
                zeroes++;
                if (zeroes > 2) return false;
            } else if (delta < min) { min = delta; minX = x; minY = y; }
              else if (delta > max) { max = delta; maxX = x; maxY = y; }
        }
    }
    if (min === 0 || max === 0) return false;
    return (hasManySiblings(a32, minX, minY, width, height) && hasManySiblings(b32, minX, minY, width, height)) ||
           (hasManySiblings(a32, maxX, maxY, width, height) && hasManySiblings(b32, maxX, maxY, width, height));
}

function hasManySiblings(img, x1, y1, width, height) {
    var x0 = Math.max(x1 - 1, 0);
    var y0 = Math.max(y1 - 1, 0);
    var x2 = Math.min(x1 + 1, width - 1);
    var y2 = Math.min(y1 + 1, height - 1);
    var val = img[y1 * width + x1];
    var zeroes = x1 === x0 || x1 === x2 || y1 === y0 || y1 === y2 ? 1 : 0;
    for (var x = x0; x <= x2; x++) {
        for (var y = y0; y <= y2; y++) {
            if (x === x1 && y === y1) continue;
            zeroes += +(val === img[y * width + x]);
            if (zeroes > 2) return true;
        }
    }
    return false;
}

function colorDelta(img1, img2, k, m, yOnly) {
    var r1 = img1[k], g1 = img1[k+1], b1 = img1[k+2], a1 = img1[k+3];
    var r2 = img2[m], g2 = img2[m+1], b2 = img2[m+2], a2 = img2[m+3];
    var dr = r1 - r2, dg = g1 - g2, db = b1 - b2, da = a1 - a2;
    if (!dr && !dg && !db && !da) return 0;
    if (a1 < 255 || a2 < 255) {
        var rb = 48 + 159 * (k % 2);
        var gb = 48 + 159 * ((k / 1.618033988749895 | 0) % 2);
        var bb = 48 + 159 * ((k / 2.618033988749895 | 0) % 2);
        dr = (r1 * a1 - r2 * a2 - rb * da) / 255;
        dg = (g1 * a1 - g2 * a2 - gb * da) / 255;
        db = (b1 * a1 - b2 * a2 - bb * da) / 255;
    }
    var y = dr * 0.29889531 + dg * 0.58662247 + db * 0.11448223;
    if (yOnly) return y;
    var i = dr * 0.59597799 - dg * 0.27417610 - db * 0.32180189;
    var q = dr * 0.21147017 - dg * 0.52261711 + db * 0.31114694;
    var delta = 0.5053 * y * y + 0.299 * i * i + 0.1957 * q * q;
    return y > 0 ? -delta : delta;
}

function drawPixel(output, pos, r, g, b) {
    output[pos] = r; output[pos+1] = g; output[pos+2] = b; output[pos+3] = 255;
}

function drawGrayPixel(img, i, alpha, output) {
    var val = 255 + (img[i] * 0.29889531 + img[i+1] * 0.58662247 + img[i+2] * 0.11448223 - 255) * alpha * img[i+3] / 255;
    drawPixel(output, i, val, val, val);
}")

(defn- bytes->base64 [^bytes png-bytes]
  (.encodeToString (Base64/getEncoder) png-bytes))

(defn- file->bytes [^String path]
  (Files/readAllBytes (Path/of path (into-array String []))))

(defn- png-dimensions [^bytes png-bytes]
  (when (< (alength png-bytes) 24)
    (throw (ex-info "Invalid PNG bytes: header too short" {:byte-length (alength png-bytes)})))
  (let [width  (bit-or (bit-shift-left (long (bit-and (long (aget png-bytes 16)) 0xFF)) 24)
                 (bit-shift-left (long (bit-and (long (aget png-bytes 17)) 0xFF)) 16)
                 (bit-shift-left (long (bit-and (long (aget png-bytes 18)) 0xFF)) 8)
                 (long (bit-and (long (aget png-bytes 19)) 0xFF)))
        height (bit-or (bit-shift-left (long (bit-and (long (aget png-bytes 20)) 0xFF)) 24)
                 (bit-shift-left (long (bit-and (long (aget png-bytes 21)) 0xFF)) 16)
                 (bit-shift-left (long (bit-and (long (aget png-bytes 22)) 0xFF)) 8)
                 (long (bit-and (long (aget png-bytes 23)) 0xFF)))]
    {:width width :height height}))

(defn- ensure-playwright!
  "Guards against anomaly maps from core/create. Throws if Playwright
   initialization failed instead of passing a map to browser launch."
  [pw]
  (when (core/anomaly? pw)
    (throw (ex-info (str "Failed to create Playwright: " (:anomaly/message pw))
             (select-keys pw [:anomaly/category :anomaly/message])))))

(defn- resolve-launch-fn
  "Returns the browser launch function based on browser type string.
   Reads SPEL_BROWSER env var as fallback. Defaults to chromium."
  [browser-type]
  (case (or browser-type (System/getenv "SPEL_BROWSER") "chromium")
    "firefox" core/launch-firefox
    "webkit" core/launch-webkit
    core/launch-chromium))

(defn- build-diff-html [b64-baseline b64-current {:keys [threshold include-aa alpha]}]
  (str "<!DOCTYPE html><html><head><style>"
    "* { margin: 0; padding: 0; }"
    "body { overflow: hidden; }"
    "canvas { display: block; }"
    "</style></head><body>"
    "<canvas id=\"diff\"></canvas>"
    "<script>"
    js-pixelmatch
    "(function() {"
    "  var img1 = new Image();"
    "  var img2 = new Image();"
    "  var loaded = 0;"
    "  function onBothLoaded() {"
    "    var w = Math.max(img1.width, img2.width);"
    "    var h = Math.max(img1.height, img2.height);"
    "    var canvas1 = document.createElement('canvas');"
    "    canvas1.width = w; canvas1.height = h;"
    "    var ctx1 = canvas1.getContext('2d');"
    "    ctx1.drawImage(img1, 0, 0);"
    "    var data1 = ctx1.getImageData(0, 0, w, h);"
    "    var canvas2 = document.createElement('canvas');"
    "    canvas2.width = w; canvas2.height = h;"
    "    var ctx2 = canvas2.getContext('2d');"
    "    ctx2.drawImage(img2, 0, 0);"
    "    var data2 = ctx2.getImageData(0, 0, w, h);"
    "    var diffCanvas = document.getElementById('diff');"
    "    diffCanvas.width = w; diffCanvas.height = h;"
    "    var diffCtx = diffCanvas.getContext('2d');"
    "    var diffData = diffCtx.createImageData(w, h);"
    "    var diffCount = pixelmatch(data1.data, data2.data, diffData.data, w, h, {threshold: "
    (double threshold)
    ", includeAA: "
    (if include-aa "true" "false")
    ", alpha: "
    (double alpha)
    "});"
    "    diffCtx.putImageData(diffData, 0, 0);"
    "    window.__spel_diff_result = {"
    "      diffCount: diffCount,"
    "      totalPixels: w * h,"
    "      width: w,"
    "      height: h,"
    "      baselineWidth: img1.width,"
    "      baselineHeight: img1.height,"
    "      currentWidth: img2.width,"
    "      currentHeight: img2.height"
    "    };"
    "  }"
    "  function check() { if (++loaded === 2) onBothLoaded(); }"
    "  img1.onload = check;"
    "  img2.onload = check;"
    "  img1.src = 'data:image/png;base64,"
    b64-baseline
    "';"
    "  img2.src = 'data:image/png;base64,"
    b64-current
    "';"
    "})();"
    "</script></body></html>"))

(defn compare-screenshots
  "Compare two PNG screenshots pixel-by-pixel using the pixelmatch algorithm
   (YIQ NTSC color space with anti-aliasing detection).

   Runs the comparison inside Playwright's Canvas API — no AWT/ImageIO,
   GraalVM native-image safe.

   `baseline` and `current` are PNG byte arrays (as returned by page/screenshot).

   Options:
     :threshold   — matching threshold 0.0-1.0 (default 0.1, lower = stricter)
     :include-aa  — count anti-aliased pixels as diff (default false)
     :alpha       — opacity of original image in diff (default 0.1)
     :diff-path   — optional path to save the diff image PNG

   Returns:
     {:matched         true/false
      :diff-count      number of different pixels
      :total-pixels    total pixel count
      :diff-percent    percentage of different pixels (2 decimal places)
      :width           comparison width
      :height          comparison height
      :diff-image      diff PNG as byte[]
      :baseline-dimensions {:width w :height h}
      :current-dimensions  {:width w :height h}
      :dimension-mismatch  true/false}"
  [^bytes baseline ^bytes current & {:keys [threshold include-aa alpha diff-path]
                                     :or {threshold 0.1 include-aa false alpha 0.1}}]
  (let [baseline-dims (png-dimensions baseline)
        current-dims (png-dimensions current)
        baseline-b64 (bytes->base64 baseline)
        current-b64 (bytes->base64 current)
        max-w (long (max (:width baseline-dims) (:width current-dims)))
        max-h (long (max (:height baseline-dims) (:height current-dims)))
        html (build-diff-html baseline-b64 current-b64 {:threshold threshold
                                                        :include-aa include-aa
                                                        :alpha alpha})
        launch-fn (resolve-launch-fn nil)
        result+diff
        (core/with-playwright [pw]
          (ensure-playwright! pw)
          (core/with-browser [browser (launch-fn pw {:headless true})]
            (core/with-page [pg (core/new-page browser)]
              (page/set-viewport-size! pg max-w max-h)
              (page/set-content! pg html)
              (page/wait-for-load-state pg :networkidle)
              (page/wait-for-function pg "() => window.__spel_diff_result != null" {})
              {:result (page/evaluate pg "window.__spel_diff_result")
               :diff-bytes (page/screenshot pg)})))
        result (:result result+diff)
        diff-bytes (:diff-bytes result+diff)
        diff-count (long (or (:diffCount result) (get result "diffCount") 0))
        total-pixels (long (or (:totalPixels result) (get result "totalPixels") (* max-w max-h)))
        width (long (or (:width result) (get result "width") max-w))
        height (long (or (:height result) (get result "height") max-h))
        diff-percent (if (pos? total-pixels)
                       (Double/parseDouble (format "%.2f" (* 100.0 (/ diff-count total-pixels))))
                       0.0)
        dimension-mismatch (or (not= (:width baseline-dims) (:width current-dims))
                             (not= (:height baseline-dims) (:height current-dims)))]
    (when (core/anomaly? result)
      (throw (ex-info (str "Failed to evaluate diff result: " (:anomaly/message result))
               (select-keys result [:anomaly/category :anomaly/message]))))
    (when (core/anomaly? diff-bytes)
      (throw (ex-info (str "Failed to capture diff screenshot: " (:anomaly/message diff-bytes))
               (select-keys diff-bytes [:anomaly/category :anomaly/message]))))
    (when diff-path
      (Files/write (Path/of diff-path (into-array String []))
        ^bytes diff-bytes
        (into-array java.nio.file.OpenOption [])))
    {:matched (zero? diff-count)
     :diff-count diff-count
     :total-pixels total-pixels
     :diff-percent diff-percent
     :width width
     :height height
     :diff-image diff-bytes
     :baseline-dimensions baseline-dims
     :current-dimensions current-dims
     :dimension-mismatch dimension-mismatch}))

(defn compare-screenshot-files
  "Compare two PNG screenshot files. See compare-screenshots for options."
  [^String baseline-path ^String current-path & opts]
  (apply compare-screenshots
    (file->bytes baseline-path)
    (file->bytes current-path)
    opts))
