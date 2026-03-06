(ns com.blockether.spel.visual-diff
  "Pixel-level screenshot comparison using the pixelmatch algorithm.
   Runs inside Playwright's Canvas API — no AWT/ImageIO dependency,
   GraalVM native-image safe.

   Based on pixelmatch by Mapbox (ISC license):
   https://github.com/mapbox/pixelmatch"
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot])
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
    var alpha = options.alpha === undefined ? 0.5 : options.alpha;
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
                    if (output && !diffMask) drawDiffPixel(img2, pos, aaR, aaG, aaB, alpha, output);
                } else {
                    if (output) {
                        if (delta < 0) drawDiffPixel(img2, pos, altR, altG, altB, alpha, output);
                        else drawDiffPixel(img2, pos, diffR, diffG, diffB, alpha, output);
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

function drawDiffPixel(img2, pos, r, g, b, alpha, output) {
    output[pos] = Math.round(img2[pos] * (1 - alpha) + r * alpha);
    output[pos+1] = Math.round(img2[pos+1] * (1 - alpha) + g * alpha);
    output[pos+2] = Math.round(img2[pos+2] * (1 - alpha) + b * alpha);
    output[pos+3] = 255;
}

function drawGrayPixel(img, i, alpha, output) {
    output[i] = img[i]; output[i+1] = img[i+1]; output[i+2] = img[i+2]; output[i+3] = img[i+3];
}")

(def ^:private js-detect-regions
  "function detectRegions(diffData, img1, img2, w, h, opts) {
    opts = opts || {};
    var denoise = opts.denoise === undefined ? 25 : opts.denoise;
    var dilate = opts.dilate === undefined ? 3 : opts.dilate;
    var mergeDistance = opts.mergeDistance === undefined ? 50 : opts.mergeDistance;
    var len = w * h;

    function isGray(r, g, b) {
        return Math.abs(r - g) <= 5 && Math.abs(g - b) <= 5 && Math.abs(r - b) <= 5;
    }

    var mask = new Uint8Array(len);
    for (var i = 0; i < len; i++) {
        var p = i * 4;
        var r = diffData[p], g = diffData[p + 1], b = diffData[p + 2], a = diffData[p + 3];
        if (a > 0 && !isGray(r, g, b)) mask[i] = 1;
    }

    if (dilate > 0) {
        var expanded = new Uint8Array(len);
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                if (mask[y * w + x] !== 1) continue;
                var minX = Math.max(0, x - dilate);
                var maxX = Math.min(w - 1, x + dilate);
                var minY = Math.max(0, y - dilate);
                var maxY = Math.min(h - 1, y + dilate);
                for (var yy = minY; yy <= maxY; yy++) {
                    for (var xx = minX; xx <= maxX; xx++) {
                        expanded[yy * w + xx] = 1;
                    }
                }
            }
        }
        mask = expanded;
    }

    var visited = new Uint8Array(len);
    var components = [];
    var stack = [];

    for (var y = 0; y < h; y++) {
        for (var x = 0; x < w; x++) {
            var start = y * w + x;
            if (mask[start] !== 1 || visited[start] === 1) continue;

            var minX = x, minY = y, maxX = x, maxY = y, pixels = 0;
            stack.push(start);
            visited[start] = 1;

            while (stack.length > 0) {
                var idx = stack.pop();
                var cx = idx % w;
                var cy = (idx / w) | 0;
                pixels++;

                if (cx < minX) minX = cx;
                if (cy < minY) minY = cy;
                if (cx > maxX) maxX = cx;
                if (cy > maxY) maxY = cy;

                var left = idx - 1;
                var right = idx + 1;
                var up = idx - w;
                var down = idx + w;

                if (cx > 0 && mask[left] === 1 && visited[left] === 0) {
                    visited[left] = 1;
                    stack.push(left);
                }
                if (cx < w - 1 && mask[right] === 1 && visited[right] === 0) {
                    visited[right] = 1;
                    stack.push(right);
                }
                if (cy > 0 && mask[up] === 1 && visited[up] === 0) {
                    visited[up] = 1;
                    stack.push(up);
                }
                if (cy < h - 1 && mask[down] === 1 && visited[down] === 0) {
                    visited[down] = 1;
                    stack.push(down);
                }
            }

            if (pixels >= denoise) {
                components.push({
                    pixels: pixels,
                    bounding_box: {
                        x: minX,
                        y: minY,
                        width: maxX - minX + 1,
                        height: maxY - minY + 1
                    }
                });
            }
        }
    }

    function boxesClose(a, b, distance) {
        var aMinX = a.bounding_box.x;
        var aMinY = a.bounding_box.y;
        var aMaxX = a.bounding_box.x + a.bounding_box.width - 1;
        var aMaxY = a.bounding_box.y + a.bounding_box.height - 1;
        var bMinX = b.bounding_box.x;
        var bMinY = b.bounding_box.y;
        var bMaxX = b.bounding_box.x + b.bounding_box.width - 1;
        var bMaxY = b.bounding_box.y + b.bounding_box.height - 1;

        var dx = 0;
        if (aMaxX < bMinX) dx = bMinX - aMaxX;
        else if (bMaxX < aMinX) dx = aMinX - bMaxX;
        var dy = 0;
        if (aMaxY < bMinY) dy = bMinY - aMaxY;
        else if (bMaxY < aMinY) dy = aMinY - bMaxY;
        return dx <= distance && dy <= distance;
    }

    function mergeBoxes(a, b) {
        var minX = Math.min(a.bounding_box.x, b.bounding_box.x);
        var minY = Math.min(a.bounding_box.y, b.bounding_box.y);
        var maxX = Math.max(a.bounding_box.x + a.bounding_box.width - 1, b.bounding_box.x + b.bounding_box.width - 1);
        var maxY = Math.max(a.bounding_box.y + a.bounding_box.height - 1, b.bounding_box.y + b.bounding_box.height - 1);
        return {
            pixels: a.pixels + b.pixels,
            bounding_box: {
                x: minX,
                y: minY,
                width: maxX - minX + 1,
                height: maxY - minY + 1
            }
        };
    }

    var merged = true;
    while (merged) {
        merged = false;
        for (var i = 0; i < components.length; i++) {
            for (var j = i + 1; j < components.length; j++) {
                if (boxesClose(components[i], components[j], mergeDistance)) {
                    components[i] = mergeBoxes(components[i], components[j]);
                    components.splice(j, 1);
                    merged = true;
                    break;
                }
            }
            if (merged) break;
        }
    }

    function classifyRegion(region) {
        var bb = region.bounding_box;
        var startX = bb.x;
        var startY = bb.y;
        var endX = bb.x + bb.width;
        var endY = bb.y + bb.height;
        var added = 0;
        var removed = 0;
        var sampled = 0;

        for (var y = startY; y < endY; y++) {
            for (var x = startX; x < endX; x++) {
                var idx = y * w + x;
                if (mask[idx] !== 1) continue;
                sampled++;
                var p = idx * 4;
                var a1 = img1[p + 3];
                var a2 = img2[p + 3];
                if (a1 <= 10 && a2 > 10) added++;
                else if (a2 <= 10 && a1 > 10) removed++;
            }
        }

        if (sampled === 0) return 'content-change';
        if ((added / sampled) > 0.5) return 'added';
        if ((removed / sampled) > 0.5) return 'removed';
        return 'content-change';
    }

    components.sort(function(a, b) { return b.pixels - a.pixels; });

    var regions = [];
    for (var i = 0; i < components.length; i++) {
        var c = components[i];
        regions.push({
            id: i + 1,
            label: classifyRegion(c),
            pixels: c.pixels,
            bounding_box: c.bounding_box
        });
    }
    return regions;
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

(defn- bbox-overlap-area
  "Returns the intersection area of two bounding boxes, or 0 if no overlap."
  ^long [region-bbox element-bbox]
  (let [x1 (Math/max (long (:x region-bbox)) (long (:x element-bbox)))
        y1 (Math/max (long (:y region-bbox)) (long (:y element-bbox)))
        x2 (Math/min (+ (long (:x region-bbox)) (long (:width region-bbox)))
             (+ (long (:x element-bbox)) (long (:width element-bbox))))
        y2 (Math/min (+ (long (:y region-bbox)) (long (:height region-bbox)))
             (+ (long (:y element-bbox)) (long (:height element-bbox))))]
    (if (and (> x2 x1) (> y2 y1))
      (* (- x2 x1) (- y2 y1))
      0)))

(defn- element-area ^long [bbox]
  (* (long (:width bbox)) (long (:height bbox))))

(defn- enrich-region
  "Enriches a single diff region with snapshot element information.
   Finds all snapshot elements whose bounding boxes overlap with the region,
   filters by minimum overlap threshold, and selects the best match
   (smallest element with >50% of its area covered)."
  [region refs]
  (let [region-bbox (:bounding-box region)
        candidates
        (->> refs
          (keep (fn [[ref-id {:keys [bbox role name] :as _info}]]
                  (when (and bbox (pos? (element-area bbox)))
                    (let [overlap (bbox-overlap-area region-bbox bbox)
                          el-area (element-area bbox)
                          overlap-ratio (/ (double overlap) (double el-area))]
                      (when (> overlap-ratio 0.1)
                        {:ref ref-id
                         :role role
                         :name name
                         :overlap (Double/parseDouble (format "%.2f" overlap-ratio))
                         :area el-area})))))
          (sort-by :area)
          vec)
        primary (or (first (filter #(> (double (:overlap %)) 0.5) candidates))
                  (first candidates))
        semantic-label (when primary
                         (str (:role primary)
                           (when (seq (:name primary))
                             (str " '" (:name primary) "'"))
                           " — " (:label region)))]
    (cond-> region
      (seq candidates) (assoc :elements (mapv #(dissoc % :area) candidates))
      primary          (assoc :element (dissoc primary :area))
      semantic-label   (assoc :semantic-label semantic-label))))

(defn enrich-regions
  "Enriches diff regions with semantic labels from accessibility snapshot refs.

   `regions` - vector of diff regions from compare-screenshots
   `refs` - snapshot refs map from capture-snapshot (:refs key)
            Format: {e2yrjz {:role button :name Submit :bbox {:x :y :width :height}} ...}

   Returns the regions vector with additional keys on each region:
     :element        - best matching element {:ref :role :name :overlap}
     :elements       - all overlapping elements sorted by specificity
     :semantic-label - human-readable label like button 'Submit' — content-change"
  [regions refs]
  (if (or (empty? regions) (empty? refs))
    regions
    (mapv #(enrich-region % refs) regions)))

(defn- build-diff-html [b64-baseline b64-current {:keys [threshold include-aa alpha denoise dilate merge-distance]
                                                  :or {denoise 25 dilate 3 merge-distance 50}}]
  (str "<!DOCTYPE html><html><head><style>"
    "* { margin: 0; padding: 0; }"
    "body { overflow: hidden; }"
    "canvas { display: block; }"
    "</style></head><body>"
    "<canvas id=\"diff\"></canvas>"
    "<script>"
    js-pixelmatch
    js-detect-regions
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
    "    var regions = detectRegions(diffData.data, data1.data, data2.data, w, h, {denoise: "
    (long denoise)
    ", dilate: "
    (long dilate)
    ", mergeDistance: "
    (long merge-distance)
    "});"
    "    window.__spel_diff_result = {"
    "      diffCount: diffCount,"
    "      regions: regions,"
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
      :alpha       — red overlay opacity on changed pixels (default 0.5)
      :denoise     — minimum connected-component pixels to keep (default 25)
      :dilate      — dilation radius in pixels before component labeling (default 3)
      :merge-distance — merge nearby region boxes within this many pixels (default 50)
      :diff-path   — optional path to save the diff image PNG
      :baseline-refs — snapshot refs map for baseline state (optional, from capture-snapshot :refs)
      :current-refs  — snapshot refs map for current state (optional, from capture-snapshot :refs)
                       When provided, regions are enriched with semantic element labels.

   Returns:
     {:matched         true/false
      :diff-count      number of different pixels
      :total-pixels    total pixel count
      :diff-percent    percentage of different pixels (2 decimal places)
      :width           comparison width
      :height          comparison height
      :regions         [{:id n :label \"added|removed|content-change\" :pixels n
                         :bounding-box {:x n :y n :width n :height n}
                         :element {:ref s :role s :name s :overlap n}
                         :elements [{:ref s :role s :name s :overlap n}]
                         :semantic-label s} ...]
      :diff-image      diff PNG as byte[]
      :baseline-dimensions {:width w :height h}
      :current-dimensions  {:width w :height h}
      :dimension-mismatch  true/false}"
  [^bytes baseline ^bytes current & {:keys [threshold include-aa alpha denoise dilate merge-distance diff-path baseline-refs current-refs]
                                     :or {threshold 0.1 include-aa false alpha 0.5 denoise 25 dilate 3 merge-distance 50}}]
  (let [baseline-dims (png-dimensions baseline)
        current-dims (png-dimensions current)
        baseline-b64 (bytes->base64 baseline)
        current-b64 (bytes->base64 current)
        max-w (long (Math/max (long (:width baseline-dims)) (long (:width current-dims))))
        max-h (long (Math/max (long (:height baseline-dims)) (long (:height current-dims))))
        html (build-diff-html baseline-b64 current-b64 {:threshold threshold
                                                        :include-aa include-aa
                                                        :alpha alpha
                                                        :denoise denoise
                                                        :dilate dilate
                                                        :merge-distance merge-distance})
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
        regions (mapv (fn [r]
                        (let [bbox (or (:bounding_box r)
                                     (:bounding-box r)
                                     (get r "bounding_box")
                                     (get r "bounding-box"))]
                          {:id (long (or (:id r) (get r "id") 0))
                           :label (or (:label r) (get r "label") "content-change")
                           :pixels (long (or (:pixels r) (get r "pixels") 0))
                           :bounding-box {:x (long (or (:x bbox) (get bbox "x") 0))
                                          :y (long (or (:y bbox) (get bbox "y") 0))
                                          :width (long (or (:width bbox) (get bbox "width") 0))
                                          :height (long (or (:height bbox) (get bbox "height") 0))}}))
                  (or (:regions result) (get result "regions") []))
        diff-percent (if (pos? total-pixels)
                       (Double/parseDouble (format "%.2f" (* 100.0 (/ (double diff-count) (double total-pixels)))))
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
      (Files/write (Path/of ^String diff-path (into-array String []))
        ^bytes diff-bytes
        ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [])))
    {:matched (zero? diff-count)
     :diff-count diff-count
     :total-pixels total-pixels
     :diff-percent diff-percent
     :width width
     :height height
     :regions (if (or baseline-refs current-refs)
                (enrich-regions regions (or current-refs baseline-refs))
                regions)
     :diff-image diff-bytes
     :baseline-dimensions baseline-dims
     :current-dimensions current-dims
     :dimension-mismatch dimension-mismatch}))

(defn compare-pages
  "Screenshot + snapshot + diff in one call. Takes two live Playwright pages,
   captures screenshots and accessibility snapshots from both, then runs
   pixel diff with semantic region enrichment.

   This is the highest-level diff function — produces fully enriched regions
   with element labels like \"button 'Submit' — content-change\".

   Options: same as compare-screenshots (threshold, include-aa, etc.)

   Returns: same as compare-screenshots, with enriched regions, plus:
     :baseline-snapshot  - accessibility tree of baseline page
     :current-snapshot   - accessibility tree of current page"
  [baseline-page current-page & {:as opts}]
  (let [baseline-bytes (page/screenshot baseline-page)
        current-bytes  (page/screenshot current-page)
        baseline-snap  (snapshot/capture-snapshot baseline-page)
        current-snap   (snapshot/capture-snapshot current-page)
        result         (apply compare-screenshots baseline-bytes current-bytes
                         :baseline-refs (:refs baseline-snap)
                         :current-refs (:refs current-snap)
                         (mapcat identity (dissoc opts :baseline-refs :current-refs)))]
    (assoc result
      :baseline-snapshot (:tree baseline-snap)
      :current-snapshot (:tree current-snap))))

(defn compare-screenshot-files
  "Compare two PNG screenshot files. See compare-screenshots for options."
  [^String baseline-path ^String current-path & opts]
  (apply compare-screenshots
    (file->bytes baseline-path)
    (file->bytes current-path)
    opts))
