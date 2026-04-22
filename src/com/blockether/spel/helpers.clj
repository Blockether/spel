(ns com.blockether.spel.helpers
  "High-level agent helpers — opinionated one-shot commands that replace
   multi-step agent workflows with deterministic, correct-by-default operations.

   These functions compose lower-level primitives (screenshot, scroll, snapshot,
   annotate, evaluate) into the operations AI agents need most often but get wrong
   when composing manually.

   All functions take an explicit Page argument (library layer).
   SCI wrappers in sci_env.clj provide implicit-page versions."
  (:require
   [com.blockether.spel.annotate :as annotate]
   [com.blockether.spel.audit-report :as audit-report]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot])
  (:import
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [com.microsoft.playwright Page]))

;; =============================================================================
;; Internal Helpers
;; =============================================================================

(defn- page-scroll-height
  "Returns the total scrollable height of the page in pixels."
  ^long [^Page page]
  (long (page/evaluate page "Math.max(document.body.scrollHeight, document.documentElement.scrollHeight)")))

(defn- scroll-to!
  "Scrolls the page to an absolute Y position and waits for settle."
  [^Page page ^long y]
  (page/evaluate page (str "window.scrollTo({top: " y ", behavior: 'instant'})"))
  ;; Brief pause for any lazy-loaded content / scroll handlers
  (Thread/sleep 150))

(defn- save-bytes!
  "Writes byte[] to a file path string. Returns the path."
  ^String [^bytes data ^String path]
  (Files/write (Path/of path (into-array String []))
    data
    ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
  path)

(defn- temp-dir!
  "Creates a temporary directory with the given prefix. Returns path string."
  ^String [^String prefix]
  (str (Files/createTempDirectory prefix (into-array FileAttribute []))))

;; =============================================================================
;; survey! — Full-page screenshot sweep
;; =============================================================================

(defn survey!
  "Scrolls through the entire page taking a screenshot at each viewport position.
   Returns a vector of maps, one per screenshot:
     [{:path \"...\", :y 0, :index 0, :viewport {:width W :height H}} ...]

   This is NOT a full-page screenshot — it captures what the agent would 'see'
   at each scroll position, including any viewport-specific rendering (sticky
   headers, lazy-loaded content, etc.).

   Params:
   `page` - Playwright Page instance.
   `opts` - Map, optional.
     :output-dir  - String. Directory for screenshots (default: temp dir).
     :prefix      - String. Filename prefix (default: \"survey\").
     :overlap     - Long. Pixel overlap between frames (default: 0).
     :annotate?   - Boolean. Inject annotation overlays per frame (default: false).
     :device      - String. Device name to emulate before sweep (e.g. \"iPhone 14\").
                    Sets viewport + user-agent, restores original viewport after.
     :max-frames  - Long. Maximum number of frames to capture (default: 50).

   Returns:
   Vector of {:path :y :index :viewport} maps."
  ([^Page page]
   (survey! page {}))
  ([^Page page opts]
   (let [output-dir  (or (:output-dir opts) (temp-dir! "spel-survey-"))
         prefix      (or (:prefix opts) "survey")
         overlap     (long (or (:overlap opts) 0))
         annotate?   (boolean (:annotate? opts))
         max-frames  (long (or (:max-frames opts) 50))
         ;; Get viewport dimensions
         vp          (page/viewport-size page)
         vp-h        (long (:height vp))
         vp-w        (long (:width vp))
         ;; Calculate scroll positions
         total-h     (page-scroll-height page)
         step        (max 1 (- vp-h overlap))
         positions   (->> (range 0 total-h step)
                       (take max-frames)
                       vec)]
     ;; Scroll to top first
     (scroll-to! page 0)

     (let [results (mapv
                     (fn [i y]
                       (scroll-to! page y)
                       (let [frame-name (format "%s-%03d.png" prefix (int i))
                             frame-path (str output-dir "/" frame-name)
                             ;; Optionally annotate visible elements
                             _          (when annotate?
                                          (let [snap (snapshot/capture-snapshot page)]
                                            (annotate/inject-overlays! page (:refs snap))))
                             ;; Capture screenshot
                             ss-bytes   (page/screenshot page)
                             _          (save-bytes! ss-bytes frame-path)
                             ;; Remove annotations if added
                             _          (when annotate?
                                          (annotate/remove-overlays! page))]
                         {:path      frame-path
                          :y         (long y)
                          :index     (int i)
                          :viewport  {:width vp-w :height vp-h}}))
                     (range)
                     positions)]

       ;; Restore scroll position
       (scroll-to! page 0)

       results))))

;; =============================================================================
;; audit! — Page structure discovery
;; =============================================================================

(def ^:private audit-js
  "JavaScript to discover page landmarks and structural sections.
   Finds elements by ARIA role, semantic HTML tags, and common CSS patterns."
  "(function() {
    var sections = [];
    var seen = new Set();

    function addSection(el, type, source) {
      if (!el || seen.has(el)) return;
      var r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;
      seen.add(el);
      var ref = el.getAttribute('data-pw-ref') || null;
      var text = (el.textContent || '').trim().substring(0, 200);
      var tag = el.tagName.toLowerCase();
      var id = el.id || null;
      var cls = el.className && typeof el.className === 'string'
        ? el.className.split(/\\s+/).slice(0, 5).join(' ') : null;
      sections.push({
        type: type,
        source: source,
        tag: tag,
        id: id,
        class: cls,
        ref: ref,
        bbox: { x: Math.round(r.x), y: Math.round(r.y),
                width: Math.round(r.width), height: Math.round(r.height) },
        text_preview: text.substring(0, 100)
      });
    }

    // Phase 1: ARIA landmark roles
    var roleMap = {
      'banner': 'header', 'navigation': 'nav', 'main': 'main',
      'contentinfo': 'footer', 'complementary': 'aside',
      'search': 'search', 'form': 'form'
    };
    Object.keys(roleMap).forEach(function(role) {
      document.querySelectorAll('[role=\"' + role + '\"]').forEach(function(el) {
        addSection(el, roleMap[role], 'aria-role');
      });
    });

    // Phase 2: Semantic HTML5 tags
    ['header', 'nav', 'main', 'footer', 'aside', 'section', 'article'].forEach(function(tag) {
      document.querySelectorAll(tag).forEach(function(el) {
        // Skip if inside another landmark (e.g. <nav> inside <header>)
        addSection(el, tag, 'semantic-tag');
      });
    });

    // Phase 3: Common CSS patterns (fallback for non-semantic markup)
    var cssPatterns = [
      { sel: '#header, .header, [class*=\"header\"]', type: 'header' },
      { sel: '#nav, .nav, .navbar, .navigation, [class*=\"nav\"]', type: 'nav' },
      { sel: '#main, .main, .content, .main-content, [class*=\"main-content\"]', type: 'main' },
      { sel: '#footer, .footer, [class*=\"footer\"]', type: 'footer' },
      { sel: '#sidebar, .sidebar, [class*=\"sidebar\"]', type: 'aside' }
    ];
    cssPatterns.forEach(function(pat) {
      document.querySelectorAll(pat.sel).forEach(function(el) {
        addSection(el, pat.type, 'css-pattern');
      });
    });

    // Sort by vertical position (top of page first)
    sections.sort(function(a, b) { return a.bbox.y - b.bbox.y; });

    return {
      url: location.href,
      title: document.title,
      scroll_height: Math.max(document.body.scrollHeight, document.documentElement.scrollHeight),
      viewport: { width: window.innerWidth, height: window.innerHeight },
      sections: sections
    };
  })()")

(defn audit!
  "Discovers the structural layout of the page — header, nav, main content,
   footer, sidebar, and other landmark sections.

   Uses a three-phase detection strategy:
   1. ARIA landmark roles (role='banner', role='main', etc.)
   2. Semantic HTML5 tags (<header>, <nav>, <main>, <footer>, <aside>)
   3. Common CSS patterns (.header, .nav, .sidebar, etc.)

   Elements are deduplicated — if an element matches multiple phases, it appears
   once (first match wins). Results are sorted top-to-bottom by Y position.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url           - String. Current page URL.
     :title         - String. Page title.
     :scroll-height - Long. Total scrollable height.
     :viewport      - {:width :height}.
     :sections      - Vector of section maps:
       {:type         - String (header, nav, main, footer, aside, search, form, section, article).
        :source       - String (aria-role, semantic-tag, css-pattern).
        :tag          - String (HTML tag).
        :id           - String or nil.
        :class        - String or nil (first 5 classes).
        :ref          - String or nil (snapshot ref if previously snapshotted).
        :bbox         - {:x :y :width :height}.
        :text-preview - String (first 100 chars of text content).}"
  [^Page page]
  (let [result (page/evaluate page audit-js)]
    {:url           (get result "url")
     :title         (get result "title")
     :scroll-height (long (or (get result "scroll_height") 0))
     :viewport      (let [vp (get result "viewport")]
                      {:width  (long (get vp "width"))
                       :height (long (get vp "height"))})
     :sections      (mapv (fn [s]
                            {:type         (get s "type")
                             :source       (get s "source")
                             :tag          (get s "tag")
                             :id           (get s "id")
                             :class        (get s "class")
                             :ref          (get s "ref")
                             :bbox         (let [b (get s "bbox")]
                                             {:x      (long (get b "x"))
                                              :y      (long (get b "y"))
                                              :width  (long (get b "width"))
                                              :height (long (get b "height"))})
                             :text-preview (get s "text_preview")})
                      (get result "sections"))}))

;; =============================================================================
;; routes! — Link extraction
;; =============================================================================

(def ^:private routes-js
  "JavaScript to extract all links from the page with metadata."
  "(function() {
    var origin = location.origin;
    var links = [];
    var seen = new Set();
    document.querySelectorAll('a[href]').forEach(function(a) {
      var href = a.getAttribute('href');
      if (!href || href === '#' || href.startsWith('javascript:')) return;
      // Resolve relative URLs
      var resolved;
      try { resolved = new URL(href, location.href).href; } catch(e) { resolved = href; }
      // Deduplicate by resolved URL
      if (seen.has(resolved)) return;
      seen.add(resolved);
      var text = (a.textContent || '').trim().substring(0, 200);
      var ref = a.getAttribute('data-pw-ref') || null;
      var r = a.getBoundingClientRect();
      var internal = resolved.startsWith(origin);
      links.push({
        text: text,
        href: resolved,
        raw_href: href,
        ref: ref,
        internal: internal,
        visible: r.width > 0 && r.height > 0,
        bbox: { x: Math.round(r.x), y: Math.round(r.y),
                width: Math.round(r.width), height: Math.round(r.height) },
        target: a.getAttribute('target') || null,
        rel: a.getAttribute('rel') || null
      });
    });
    // Sort by DOM order (already natural order from querySelectorAll)
    return { url: location.href, count: links.length, links: links };
  })()")

(defn routes!
  "Extracts all links from the current page.

   Returns every <a href> on the page with resolved URLs, visibility status,
   and snapshot refs (if a snapshot was taken prior). Links are deduplicated
   by resolved URL.

   Params:
   `page` - Playwright Page instance.
   `opts` - Map, optional.
     :internal-only? - Boolean. Only return same-origin links (default: false).
     :visible-only?  - Boolean. Only return visible links (default: false).

   Returns:
   Map with:
     :url   - String. Current page URL.
     :count - Long. Number of links returned.
     :links - Vector of link maps:
       {:text      - String. Link text content (trimmed, max 200 chars).
        :href      - String. Fully resolved URL.
        :raw-href  - String. Original href attribute value.
        :ref       - String or nil. Snapshot ref if available.
        :internal? - Boolean. True if same-origin.
        :visible?  - Boolean. True if the link has a visible bounding box.
        :bbox      - {:x :y :width :height}.
        :target    - String or nil. Target attribute value.
        :rel       - String or nil. Rel attribute value.}"
  ([^Page page]
   (routes! page {}))
  ([^Page page opts]
   (let [result (page/evaluate page routes-js)
         links  (mapv (fn [l]
                        {:text      (get l "text")
                         :href      (get l "href")
                         :raw-href  (get l "raw_href")
                         :ref       (get l "ref")
                         :internal? (boolean (get l "internal"))
                         :visible?  (boolean (get l "visible"))
                         :bbox      (let [b (get l "bbox")]
                                      {:x      (long (get b "x"))
                                       :y      (long (get b "y"))
                                       :width  (long (get b "width"))
                                       :height (long (get b "height"))})
                         :target    (get l "target")
                         :rel       (get l "rel")})
                  (get result "links"))
         filtered (cond->> links
                    (:internal-only? opts) (filterv :internal?)
                    (:visible-only? opts)  (filterv :visible?))]
     {:url   (get result "url")
      :count (count filtered)
      :links filtered})))

;; =============================================================================
;; inspect! — Combined interactive snapshot with styles
;; =============================================================================

(defn inspect!
  "Takes an interactive snapshot with styles — the 'agent view' of a page.

   Combines snapshot flags that agents always need together:
   - Interactive elements only (buttons, links, inputs, etc.)
   - Computed styles on each element (font-size, color, background, etc.)
   - Screen coordinates (pos:X,Y W×H)

   This replaces the common pattern of `snapshot -i -S` with a single call.

   Params:
   `page` - Playwright Page instance.
   `opts` - Map, optional.
     :compact?     - Boolean. Remove bare role-only lines (default: true).
     :style-detail - String. Style detail level: \"minimal\", \"base\", \"max\"
                     (default: \"base\").
     :scope        - String. CSS selector or ref to scope the snapshot.
     :device       - String. Device name (included in tree header).

   Returns:
   Map with same structure as capture-snapshot:
     :tree    - String. YAML-like accessibility tree with styles.
     :refs    - Map of ref-id → element data (includes :styles key).
     :counter - Long. Total refs.
     :viewport - {:width :height}."
  ([^Page page]
   (inspect! page {}))
  ([^Page page opts]
   (let [snap-opts (cond-> {:interactive true
                            :styles      true
                            :style-detail (or (:style-detail opts) "base")}
                     (:compact? opts true)  (assoc :compact true)
                     (:scope opts)          (assoc :scope (:scope opts))
                     (:device opts)         (assoc :device (:device opts)))]
     (snapshot/capture-snapshot page snap-opts))))

;; =============================================================================
;; overview! — Annotated full-page screenshot
;; =============================================================================

(defn overview!
  "Takes an annotated full-page screenshot — a single image showing the entire
   page with element annotations (ref labels, bounding boxes).

   Unlike regular annotated-screenshot which only annotates viewport-visible
   elements, overview annotates ALL elements on the page by using the
   :full-page option for both overlay injection and screenshot capture.

   Params:
   `page` - Playwright Page instance.
   `opts` - Map, optional.
     :path            - String. Output file path (if nil, returns bytes only).
     :show-dimensions - Boolean (default true).
     :show-badges     - Boolean (default true).
     :show-boxes      - Boolean (default true).
     :scope           - String. CSS selector or ref to restrict annotations.
     :all-frames?     - Boolean. Include iframe content via capture-full-snapshot
                        (default: false). When true, the snapshot covers all
                        frames including iframes, not just the main frame.

   Returns:
   If :path is set: {:path \"...\" :size N :annotated {:count N :entries [...]}}
   If no :path:    {:bytes <byte[]>  :annotated {:count N :entries [...]}}

   The :annotated value is a deterministic, top→down/left→right-sorted list of
   actually-drawn elements (after role + container + visibility filtering). Each
   entry is {:ref :role :name :bbox}. This is the LLM-friendly mapping between
   labels on the image and snapshot refs that can be used for subsequent
   interactions (e.g., `spel click @e2yrjz`)."
  ([^Page page]
   (overview! page {}))
  ([^Page page opts]
   (let [;; Capture fresh snapshot — full (all frames) or main-frame only
         snap         (if (:all-frames? opts)
                        (snapshot/capture-full-snapshot page)
                        (snapshot/capture-snapshot page))
         refs         (:refs snap)
         ;; Inject overlays on ALL elements (full-page mode)
         annotate-opts (cond-> {:full-page true}
                         (contains? opts :show-dimensions) (assoc :show-dimensions (:show-dimensions opts))
                         (contains? opts :show-badges)     (assoc :show-badges (:show-badges opts))
                         (contains? opts :show-boxes)      (assoc :show-boxes (:show-boxes opts))
                         (:scope opts)                     (assoc :scope (:scope opts)))
         annotated    (annotate/inject-overlays! page refs annotate-opts)
         ;; Take full-page screenshot
         ss-bytes     (try
                        (page/screenshot page {:full-page true})
                        (finally
                          (annotate/remove-overlays! page)))]
     (if-let [path (:path opts)]
       (do (save-bytes! ss-bytes path)
           {:path path :size (alength ^bytes ss-bytes) :annotated annotated})
       {:bytes ss-bytes :annotated annotated}))))

;; =============================================================================
;; debug! — Page diagnostic snapshot
;; =============================================================================

(def ^:private debug-js
  "JavaScript to collect page-level diagnostics: JS errors in the error console,
   performance timing, and resource load failures."
  "(function() {
    var diag = {};

    // Performance timing
    try {
      var nav = performance.getEntriesByType('navigation')[0];
      if (nav) {
        diag.timing = {
          dns_ms: Math.round(nav.domainLookupEnd - nav.domainLookupStart),
          connect_ms: Math.round(nav.connectEnd - nav.connectStart),
          ttfb_ms: Math.round(nav.responseStart - nav.requestStart),
          dom_interactive_ms: Math.round(nav.domInteractive - nav.startTime),
          dom_complete_ms: Math.round(nav.domComplete - nav.startTime),
          load_ms: Math.round(nav.loadEventEnd - nav.startTime)
        };
      }
    } catch(e) {}

    // Failed resource loads (images, scripts, stylesheets)
    try {
      var resources = performance.getEntriesByType('resource');
      diag.failed_resources = [];
      resources.forEach(function(r) {
        if (r.transferSize === 0 && r.decodedBodySize === 0 && r.duration > 0) {
          diag.failed_resources.push({
            url: r.name,
            type: r.initiatorType,
            duration_ms: Math.round(r.duration)
          });
        }
      });
    } catch(e) {}

    // Page metadata
    diag.url = location.href;
    diag.title = document.title;
    diag.readyState = document.readyState;
    diag.doctype = document.doctype ? document.doctype.name : null;

    // DOM stats
    diag.dom = {
      elements: document.querySelectorAll('*').length,
      forms: document.forms.length,
      images: document.images.length,
      links: document.links.length,
      scripts: document.scripts.length
    };

    // Viewport vs content
    diag.dimensions = {
      viewport_width: window.innerWidth,
      viewport_height: window.innerHeight,
      scroll_width: document.documentElement.scrollWidth,
      scroll_height: document.documentElement.scrollHeight
    };

    return diag;
  })()")

(defn debug!
  "Collects page-level diagnostic information — the \"health check\" an agent
   needs after something breaks or before reporting issues.

   Gathers from the Page object directly:
   - Performance timing (DNS, TTFB, DOM interactive/complete, load)
   - Failed resource loads (0-byte transfers)
   - DOM statistics (element count, forms, images, links, scripts)
   - Viewport vs content dimensions
   - Page metadata (URL, title, readyState)

   The daemon handler enriches this with tracked console messages,
   page errors, and failed network requests from its ring buffers.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url             - String. Current page URL.
     :title           - String. Page title.
     :ready-state     - String. Document readyState.
     :timing          - Map. Performance timing breakdown (ms).
     :failed-resources - Vector. Resources that failed to load.
     :dom             - Map. DOM element counts.
     :dimensions      - Map. Viewport and scroll dimensions."
  [^Page page]
  (let [result (page/evaluate page debug-js)]
    {:url              (get result "url")
     :title            (get result "title")
     :ready-state      (get result "readyState")
     :doctype          (get result "doctype")
     :timing           (when-let [t (get result "timing")]
                         {:dns-ms             (long (or (get t "dns_ms") 0))
                          :connect-ms         (long (or (get t "connect_ms") 0))
                          :ttfb-ms            (long (or (get t "ttfb_ms") 0))
                          :dom-interactive-ms (long (or (get t "dom_interactive_ms") 0))
                          :dom-complete-ms    (long (or (get t "dom_complete_ms") 0))
                          :load-ms            (long (or (get t "load_ms") 0))})
     :failed-resources  (mapv (fn [r]
                                {:url         (get r "url")
                                 :type        (get r "type")
                                 :duration-ms (long (or (get r "duration_ms") 0))})
                          (get result "failed_resources" []))
     :dom              (when-let [d (get result "dom")]
                         {:elements (long (or (get d "elements") 0))
                          :forms    (long (or (get d "forms") 0))
                          :images   (long (or (get d "images") 0))
                          :links    (long (or (get d "links") 0))
                          :scripts  (long (or (get d "scripts") 0))})
     :dimensions       (when-let [dim (get result "dimensions")]
                         {:viewport-width  (long (or (get dim "viewport_width") 0))
                          :viewport-height (long (or (get dim "viewport_height") 0))
                          :scroll-width    (long (or (get dim "scroll_width") 0))
                          :scroll-height   (long (or (get dim "scroll_height") 0))})}))

(def ^:private text-contrast-js
  "JavaScript to compute WCAG text contrast across visible text elements in bulk."
  "(function() {
    var MAX_ELEMENTS = 500;

    function toLinear(v) {
      var c = v / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    function luminance(rgb) {
      return (0.2126 * toLinear(rgb.r)) + (0.7152 * toLinear(rgb.g)) + (0.0722 * toLinear(rgb.b));
    }

    function contrastRatio(c1, c2) {
      var l1 = luminance(c1);
      var l2 = luminance(c2);
      var light = Math.max(l1, l2);
      var dark = Math.min(l1, l2);
      return (light + 0.05) / (dark + 0.05);
    }

    function parseRgbString(str) {
      if (!str || typeof str !== 'string') return null;
      var m = str.match(/rgba?\\(([^)]+)\\)/i);
      if (!m) return null;
      var parts = m[1].split(',').map(function(p) { return p.trim(); });
      if (parts.length < 3) return null;
      var r = parseFloat(parts[0]);
      var g = parseFloat(parts[1]);
      var b = parseFloat(parts[2]);
      var a = parts.length >= 4 ? parseFloat(parts[3]) : 1;
      if (isNaN(r) || isNaN(g) || isNaN(b)) return null;
      if (isNaN(a)) a = 1;
      return { r: Math.max(0, Math.min(255, Math.round(r))),
               g: Math.max(0, Math.min(255, Math.round(g))),
               b: Math.max(0, Math.min(255, Math.round(b))),
               a: Math.max(0, Math.min(1, a)) };
    }

    function normalizeToRgba(el, colorValue, propName) {
      if (!colorValue || typeof colorValue !== 'string') return null;
      var raw = colorValue.trim().toLowerCase();
      if (raw === 'transparent') return null;
      if (raw === 'currentcolor') {
        return parseRgbString(getComputedStyle(el).color);
      }
      var parsed = parseRgbString(colorValue);
      if (parsed) return parsed;

      var probe = document.createElement('span');
      probe.style.all = 'initial';
      probe.style[propName] = colorValue;
      document.body.appendChild(probe);
      var computed = getComputedStyle(probe)[propName];
      document.body.removeChild(probe);
      return parseRgbString(computed);
    }

    function opaqueColorFrom(el, propName) {
      var current = el;
      while (current && current.nodeType === 1) {
        var val = getComputedStyle(current)[propName];
        var rgba = normalizeToRgba(current, val, propName);
        if (rgba && rgba.a > 0) return rgba;
        current = current.parentElement;
      }
      return { r: 255, g: 255, b: 255, a: 1 };
    }

    function hasOwnVisibleText(el) {
      if (!el || !el.childNodes) return false;
      for (var i = 0; i < el.childNodes.length; i++) {
        var n = el.childNodes[i];
        if (n.nodeType === 3 && n.textContent && n.textContent.trim()) return true;
      }
      return false;
    }

    var items = [];
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length && items.length < MAX_ELEMENTS; i++) {
      var el = all[i];
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (!hasOwnVisibleText(el)) continue;

      var rect = el.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;

      var fg = normalizeToRgba(el, cs.color, 'color');
      var bg = opaqueColorFrom(el, 'backgroundColor');
      if (!fg || !bg) continue;

      var ratio = contrastRatio(fg, bg);
      var size = parseFloat(cs.fontSize || '0') || 0;
      var weightRaw = cs.fontWeight || '400';
      var weight = parseInt(weightRaw, 10);
      if (isNaN(weight)) weight = (String(weightRaw).toLowerCase() === 'bold') ? 700 : 400;
      var isLarge = size >= 18 || (size >= 14 && weight >= 700);

      var aaThreshold = isLarge ? 3.0 : 4.5;
      var aaaThreshold = isLarge ? 4.5 : 7.0;

      var selector = el.tagName.toLowerCase();
      if (el.id) selector += '#' + el.id;
      if (el.className && typeof el.className === 'string') {
        var cls = el.className.trim().split(/\\s+/).slice(0, 2).join('.');
        if (cls) selector += '.' + cls;
      }

      items.push({
        selector: selector,
        text: (el.textContent || '').trim().substring(0, 160),
        color: 'rgb(' + fg.r + ', ' + fg.g + ', ' + fg.b + ')',
        bg_color: 'rgb(' + bg.r + ', ' + bg.g + ', ' + bg.b + ')',
        contrast_ratio: Math.round(ratio * 100) / 100,
        font_size: Math.round(size * 10) / 10,
        wcag_aa: ratio >= aaThreshold,
        wcag_aaa: ratio >= aaaThreshold,
        large_text: isLarge
      });
    }

    items.sort(function(a, b) {
      if (a.wcag_aa !== b.wcag_aa) return a.wcag_aa ? 1 : -1;
      return a.contrast_ratio - b.contrast_ratio;
    });

    var passing = 0;
    for (var j = 0; j < items.length; j++) {
      if (items[j].wcag_aa) passing++;
    }

    return {
      url: location.href,
      total_elements: items.length,
      passing: passing,
      failing: items.length - passing,
      elements: items
    };
  })()")

(defn text-contrast!
  "Audits text contrast across visible text elements using WCAG 2.1 contrast rules.

   Uses one bulk JavaScript evaluation to collect up to 500 text-bearing elements,
   resolve foreground/background colors (including transparent/background-inheritance
   handling), and compute contrast ratio + AA/AAA pass status.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url            - String. Current page URL.
     :total-elements - Long. Number of analyzed text elements.
     :passing        - Long. Elements passing WCAG AA.
     :failing        - Long. Elements failing WCAG AA.
     :elements       - Vector of element maps sorted with failures first:
       {:selector       - String.
        :text           - String (trimmed preview).
        :color          - String (rgb).
        :bg-color       - String (rgb).
        :contrast-ratio - Double.
        :font-size      - Double (px).
        :wcag-aa        - Boolean.
        :wcag-aaa       - Boolean.
        :large-text?    - Boolean.}"
  [^Page page]
  (let [result (page/evaluate page text-contrast-js)]
    {:url            (get result "url")
     :total-elements (long (or (get result "total_elements") 0))
     :passing        (long (or (get result "passing") 0))
     :failing        (long (or (get result "failing") 0))
     :elements       (mapv (fn [e]
                             {:selector       (get e "selector")
                              :text           (get e "text")
                              :color          (get e "color")
                              :bg-color       (get e "bg_color")
                              :contrast-ratio (double (or (get e "contrast_ratio") 0.0))
                              :font-size      (double (or (get e "font_size") 0.0))
                              :wcag-aa        (boolean (get e "wcag_aa"))
                              :wcag-aaa       (boolean (get e "wcag_aaa"))
                              :large-text?    (boolean (get e "large_text"))})
                       (get result "elements" []))}))

(def ^:private color-palette-js
  "JavaScript to aggregate computed page colors and palette relationships."
  "(function() {
    function parseRgb(str) {
      if (!str || typeof str !== 'string') return null;
      var m = str.match(/rgba?\\(([^)]+)\\)/i);
      if (!m) return null;
      var p = m[1].split(',').map(function(x) { return x.trim(); });
      if (p.length < 3) return null;
      var r = parseFloat(p[0]);
      var g = parseFloat(p[1]);
      var b = parseFloat(p[2]);
      var a = p.length >= 4 ? parseFloat(p[3]) : 1;
      if (isNaN(r) || isNaN(g) || isNaN(b)) return null;
      if (isNaN(a)) a = 1;
      return { r: Math.round(r), g: Math.round(g), b: Math.round(b), a: a };
    }

    function normalizeColor(el, val, prop) {
      if (!val) return null;
      var raw = String(val).trim().toLowerCase();
      if (raw === 'transparent' || raw === 'none') return null;
      if (raw === 'currentcolor') {
        return parseRgb(getComputedStyle(el).color);
      }
      var rgb = parseRgb(val);
      if (rgb) return rgb.a > 0 ? rgb : null;
      var probe = document.createElement('span');
      probe.style.all = 'initial';
      probe.style[prop] = val;
      document.body.appendChild(probe);
      var computed = getComputedStyle(probe)[prop];
      document.body.removeChild(probe);
      var parsed = parseRgb(computed);
      return parsed && parsed.a > 0 ? parsed : null;
    }

    function toHex(v) {
      var s = Math.max(0, Math.min(255, v)).toString(16);
      return s.length === 1 ? '0' + s : s;
    }

    function rgbToHex(c) {
      return '#' + toHex(c.r) + toHex(c.g) + toHex(c.b);
    }

    function rgbString(c) {
      return 'rgb(' + c.r + ', ' + c.g + ', ' + c.b + ')';
    }

    function rgbToHsl(c) {
      var r = c.r / 255;
      var g = c.g / 255;
      var b = c.b / 255;
      var max = Math.max(r, g, b);
      var min = Math.min(r, g, b);
      var h = 0;
      var s = 0;
      var l = (max + min) / 2;

      if (max !== min) {
        var d = max - min;
        s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
        switch (max) {
          case r: h = ((g - b) / d + (g < b ? 6 : 0)); break;
          case g: h = ((b - r) / d + 2); break;
          default: h = ((r - g) / d + 4); break;
        }
        h = h * 60;
      }

      return { h: h, s: s * 100, l: l * 100 };
    }

    function colorDelta(a, b) {
      var dr = a.r - b.r;
      var dg = a.g - b.g;
      var db = a.b - b.b;
      var d = Math.sqrt((dr * dr) + (dg * dg) + (db * db));
      return (d / 441.67) * 100;
    }

    var map = {};
    var all = document.querySelectorAll('*');
    var props = [
      { key: 'color', css: 'color' },
      { key: 'background-color', css: 'backgroundColor' },
      { key: 'border-color', css: 'borderTopColor' }
    ];

    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      var cs = getComputedStyle(el);
      for (var p = 0; p < props.length; p++) {
        var prop = props[p];
        var parsed = normalizeColor(el, cs[prop.css], prop.css);
        if (!parsed) continue;
        var hex = rgbToHex(parsed);
        if (!map[hex]) {
          map[hex] = {
            hex: hex,
            rgb: rgbString(parsed),
            usage_count: 0,
            properties: {},
            elements_sample: [],
            raw: parsed
          };
        }
        var entry = map[hex];
        entry.usage_count += 1;
        entry.properties[prop.key] = true;
        if (entry.elements_sample.length < 5) {
          var tag = el.tagName.toLowerCase();
          var id = el.id ? ('#' + el.id) : '';
          entry.elements_sample.push(tag + id);
        }
      }
    }

    var colors = Object.keys(map).map(function(hex) {
      var e = map[hex];
      return {
        hex: e.hex,
        rgb: e.rgb,
        usage_count: e.usage_count,
        properties: Object.keys(e.properties),
        elements_sample: e.elements_sample,
        _raw: e.raw
      };
    });

    colors.sort(function(a, b) { return b.usage_count - a.usage_count; });

    var nearDuplicates = [];
    for (var a = 0; a < colors.length; a++) {
      for (var b = a + 1; b < colors.length; b++) {
        var delta = colorDelta(colors[a]._raw, colors[b]._raw);
        if (delta < 5) {
          nearDuplicates.push({
            color1: colors[a].hex,
            color2: colors[b].hex,
            delta: Math.round(delta * 100) / 100
          });
        }
      }
    }

    var groups = { reds: [], blues: [], greens: [], neutrals: [], other: [] };
    colors.forEach(function(c) {
      var hsl = rgbToHsl(c._raw);
      if (hsl.s < 10) {
        groups.neutrals.push(c.hex);
      } else if (hsl.h >= 75 && hsl.h < 165) {
        groups.greens.push(c.hex);
      } else if (hsl.h >= 165 && hsl.h < 255) {
        groups.blues.push(c.hex);
      } else if (hsl.h >= 345 || hsl.h < 15) {
        groups.reds.push(c.hex);
      } else {
        groups.other.push(c.hex);
      }
      delete c._raw;
    });

    return {
      url: location.href,
      total_colors: colors.length,
      colors: colors,
      near_duplicates: nearDuplicates,
      hue_groups: groups
    };
  })()")

(defn color-palette!
  "Builds a page color inventory from computed text/background/border colors.

   Uses one bulk JavaScript evaluation to aggregate unique colors, usage frequency,
   near-duplicate color pairs, and coarse hue grouping for palette consistency checks.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url             - String. Current page URL.
     :total-colors    - Long. Number of unique colors found.
     :colors          - Vector of color maps:
       {:hex             - String (#rrggbb).
        :rgb             - String (rgb).
        :usage-count     - Long.
        :properties      - Vector of property keywords (:color, :background-color, :border-color).
        :elements-sample - Vector of up to 5 element selectors.}
     :near-duplicates - Vector of close color pairs:
       {:color1 :color2 :delta}
     :hue-groups      - Map of grouped hex colors:
       {:reds [] :blues [] :greens [] :neutrals [] :other []}."
  [^Page page]
  (let [result (page/evaluate page color-palette-js)]
    {:url             (get result "url")
     :total-colors    (long (or (get result "total_colors") 0))
     :colors          (mapv (fn [c]
                              {:hex             (get c "hex")
                               :rgb             (get c "rgb")
                               :usage-count     (long (or (get c "usage_count") 0))
                               :properties      (mapv keyword (get c "properties" []))
                               :elements-sample (mapv identity (get c "elements_sample" []))})
                        (get result "colors" []))
     :near-duplicates (mapv (fn [n]
                              {:color1 (get n "color1")
                               :color2 (get n "color2")
                               :delta  (double (or (get n "delta") 0.0))})
                        (get result "near_duplicates" []))
     :hue-groups      (let [g (get result "hue_groups")]
                        {:reds     (mapv identity (get g "reds" []))
                         :blues    (mapv identity (get g "blues" []))
                         :greens   (mapv identity (get g "greens" []))
                         :neutrals (mapv identity (get g "neutrals" []))
                         :other    (mapv identity (get g "other" []))})}))

(def ^:private layout-check-js
  "JavaScript to detect common page layout integrity issues in one pass."
  "(function() {
    function selectorFor(el) {
      var s = el.tagName.toLowerCase();
      if (el.id) s += '#' + el.id;
      if (el.className && typeof el.className === 'string') {
        var cls = el.className.trim().split(/\\s+/).slice(0, 2).join('.');
        if (cls) s += '.' + cls;
      }
      return s;
    }

    function bbox(r) {
      return {
        x: Math.round(r.x),
        y: Math.round(r.y),
        width: Math.round(r.width),
        height: Math.round(r.height)
      };
    }

    function intersects(a, b) {
      return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
    }

    var issues = [];
    var viewportWidth = document.documentElement.clientWidth;
    var viewportHeight = window.innerHeight;

    var overflowElements = [];
    var roots = Array.from(new Set([].concat(Array.from(document.body.children), Array.from(document.documentElement.children))));
    roots.forEach(function(el) {
      if (el.scrollWidth > viewportWidth) {
        overflowElements.push({
          tag: el.tagName.toLowerCase(),
          selector: selectorFor(el),
          bbox: bbox(el.getBoundingClientRect())
        });
      }
    });
    if (overflowElements.length) {
      issues.push({
        type: 'horizontal-overflow',
        description: 'Elements exceed viewport width and may cause horizontal scrolling.',
        severity: 'high',
        elements: overflowElements
      });
    }

    var offscreen = [];
    document.querySelectorAll('body *').forEach(function(el) {
      var r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return;
      if (r.right < 0 || r.bottom < 0) {
        offscreen.push({
          tag: el.tagName.toLowerCase(),
          selector: selectorFor(el),
          bbox: bbox(r)
        });
      }
    });
    if (offscreen.length) {
      issues.push({
        type: 'offscreen',
        description: 'Visible-sized elements are positioned entirely offscreen (top/left).',
        severity: 'low',
        elements: offscreen
      });
    }

    var flexOverflow = [];
    document.querySelectorAll('body *').forEach(function(el) {
      var parent = el.parentElement;
      if (!parent) return;
      var pcs = getComputedStyle(parent);
      if (pcs.display !== 'flex' && pcs.display !== 'inline-flex') return;
      if (el.offsetWidth > parent.clientWidth + 1) {
        flexOverflow.push({
          tag: el.tagName.toLowerCase(),
          selector: selectorFor(el),
          bbox: bbox(el.getBoundingClientRect())
        });
      }
    });
    if (flexOverflow.length) {
      issues.push({
        type: 'flex-overflow',
        description: 'Flex children exceed parent width and may overflow containers.',
        severity: 'medium',
        elements: flexOverflow
      });
    }

    var overlapCandidates = [];
    document.querySelectorAll('body *').forEach(function(el) {
      if (overlapCandidates.length >= 200) return;
      var cs = getComputedStyle(el);
      if (cs.position === 'fixed' || cs.position === 'sticky') return;
      if (cs.display === 'none' || cs.visibility === 'hidden') return;
      if (parseFloat(cs.opacity || '1') === 0) return;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      overlapCandidates.push({ el: el, rect: r });
    });

    var overlaps = [];
    for (var i = 0; i < overlapCandidates.length; i++) {
      for (var j = i + 1; j < overlapCandidates.length; j++) {
        var a = overlapCandidates[i];
        var b = overlapCandidates[j];
        if (intersects(a.rect, b.rect)) {
          overlaps.push({
            tag: a.el.tagName.toLowerCase() + ' + ' + b.el.tagName.toLowerCase(),
            selector: selectorFor(a.el) + ' <-> ' + selectorFor(b.el),
            bbox: bbox({
              x: Math.max(a.rect.left, b.rect.left),
              y: Math.max(a.rect.top, b.rect.top),
              width: Math.min(a.rect.right, b.rect.right) - Math.max(a.rect.left, b.rect.left),
              height: Math.min(a.rect.bottom, b.rect.bottom) - Math.max(a.rect.top, b.rect.top)
            })
          });
          if (overlaps.length >= 200) break;
        }
      }
      if (overlaps.length >= 200) break;
    }
    if (overlaps.length) {
      issues.push({
        type: 'overlap',
        description: 'Element bounding boxes intersect and may hide content.',
        severity: 'medium',
        elements: overlaps
      });
    }

    return {
      url: location.href,
      viewport: { width: viewportWidth, height: viewportHeight },
      issues: issues,
      total_issues: issues.length,
      clean: issues.length === 0
    };
  })()")

(defn layout-check!
  "Checks page layout for common rendering integrity issues in one bulk pass.

   Detects horizontal overflow, offscreen elements, flex child overflow, and
   overlapping element rectangles (excluding fixed/sticky/hidden/transparent nodes
   from overlap analysis). Overlap candidate checks are capped for performance.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url          - String. Current page URL.
     :viewport     - {:width :height}.
     :issues       - Vector of issue maps:
       {:type        - Keyword (:horizontal-overflow, :offscreen, :overlap, :flex-overflow).
        :description - String.
        :severity    - Keyword (:high, :medium, :low).
        :elements    - Vector of {:tag :selector :bbox}.}
     :total-issues - Long.
     :clean?       - Boolean."
  [^Page page]
  (let [result (page/evaluate page layout-check-js)]
    {:url          (get result "url")
     :viewport     (let [vp (get result "viewport")]
                     {:width  (long (or (get vp "width") 0))
                      :height (long (or (get vp "height") 0))})
     :issues       (mapv (fn [i]
                           {:type        (keyword (get i "type"))
                            :description (get i "description")
                            :severity    (keyword (get i "severity"))
                            :elements    (mapv (fn [e]
                                                 {:tag      (get e "tag")
                                                  :selector (get e "selector")
                                                  :bbox     (let [b (get e "bbox")]
                                                              {:x      (long (or (get b "x") 0))
                                                               :y      (long (or (get b "y") 0))
                                                               :width  (long (or (get b "width") 0))
                                                               :height (long (or (get b "height") 0))})})
                                           (get i "elements" []))})
                     (get result "issues" []))
     :total-issues (long (or (get result "total_issues") 0))
     :clean?       (boolean (get result "clean"))}))

(def ^:private font-audit-js
  "JavaScript to aggregate typography usage and consistency issues."
  "(function() {
    function hasOwnVisibleText(el) {
      for (var i = 0; i < el.childNodes.length; i++) {
        var n = el.childNodes[i];
        if (n.nodeType === 3 && n.textContent && n.textContent.trim()) return true;
      }
      return false;
    }

    function selectorFor(el) {
      var s = el.tagName.toLowerCase();
      if (el.id) s += '#' + el.id;
      if (el.className && typeof el.className === 'string') {
        var cls = el.className.trim().split(/\\s+/).slice(0, 2).join('.');
        if (cls) s += '.' + cls;
      }
      return s;
    }

    var fonts = {};
    var sizes = {};
    var weights = {};

    document.querySelectorAll('body *').forEach(function(el) {
      var cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return;
      if (!hasOwnVisibleText(el)) return;

      var familyRaw = (cs.fontFamily || '').split(',')[0].trim().replace(/^['\"]|['\"]$/g, '');
      var family = familyRaw || 'unknown';
      var px = parseFloat(cs.fontSize || '0') || 0;
      var roundedPx = Math.round(px * 10) / 10;
      var weight = (cs.fontWeight || '400').trim();

      if (!fonts[family]) fonts[family] = { family: family, usage_count: 0, elements_sample: [] };
      fonts[family].usage_count += 1;
      if (fonts[family].elements_sample.length < 5) fonts[family].elements_sample.push(selectorFor(el));

      var sizeKey = String(roundedPx);
      if (!sizes[sizeKey]) sizes[sizeKey] = { px: roundedPx, rem: Math.round((roundedPx / 16) * 1000) / 1000, usage_count: 0 };
      sizes[sizeKey].usage_count += 1;

      if (!weights[weight]) weights[weight] = { weight: weight, usage_count: 0 };
      weights[weight].usage_count += 1;
    });

    var fontList = Object.keys(fonts).map(function(k) { return fonts[k]; }).sort(function(a, b) { return b.usage_count - a.usage_count; });
    var sizeList = Object.keys(sizes).map(function(k) { return sizes[k]; }).sort(function(a, b) { return a.px - b.px; });
    var weightList = Object.keys(weights).map(function(k) { return weights[k]; }).sort(function(a, b) { return a.weight.localeCompare(b.weight); });

    var issues = [];
    if (fontList.length > 4) {
      issues.push({ type: 'too-many-fonts', description: 'More than 4 font families are used on the page.' });
    }
    if (sizeList.length > 8) {
      issues.push({ type: 'too-many-sizes', description: 'More than 8 font sizes are used on the page.' });
    }
    sizeList.forEach(function(s) {
      if (s.usage_count === 1) {
        issues.push({ type: 'orphan-size', description: 'Font size ' + s.px + 'px appears only once.' });
      }
    });

    return {
      url: location.href,
      fonts: fontList,
      sizes: sizeList,
      weights: weightList,
      issues: issues,
      stats: {
        font_count: fontList.length,
        size_count: sizeList.length,
        weight_count: weightList.length
      }
    };
  })()")

(defn font-audit!
  "Audits typography usage consistency across visible text elements.

   Runs one bulk JavaScript pass that aggregates primary font family usage,
   font-size/weight distributions, and emits basic consistency issues.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url    - String. Current page URL.
     :fonts  - Vector of {:family :usage-count :elements-sample}.
     :sizes  - Vector of {:px :rem :usage-count}.
     :weights - Vector of {:weight :usage-count}.
     :issues - Vector of {:type :description}.
     :stats  - {:font-count :size-count :weight-count}."
  [^Page page]
  (let [result (page/evaluate page font-audit-js)]
    {:url     (get result "url")
     :fonts   (mapv (fn [f]
                      {:family          (get f "family")
                       :usage-count     (long (or (get f "usage_count") 0))
                       :elements-sample (mapv identity (get f "elements_sample" []))})
                (get result "fonts" []))
     :sizes   (mapv (fn [s]
                      {:px          (double (or (get s "px") 0.0))
                       :rem         (double (or (get s "rem") 0.0))
                       :usage-count (long (or (get s "usage_count") 0))})
                (get result "sizes" []))
     :weights (mapv (fn [w]
                      {:weight      (get w "weight")
                       :usage-count (long (or (get w "usage_count") 0))})
                (get result "weights" []))
     :issues  (mapv (fn [i]
                      {:type        (keyword (get i "type"))
                       :description (get i "description")})
                (get result "issues" []))
     :stats   (let [s (get result "stats")]
                {:font-count   (long (or (get s "font_count") 0))
                 :size-count   (long (or (get s "size_count") 0))
                 :weight-count (long (or (get s "weight_count") 0))})}))

(def ^:private link-health-js
  "JavaScript to validate link targets and classify URL health in one pass."
  "(async function() {
    var MAX_LINKS = 100;
    var anchors = Array.from(document.querySelectorAll('a[href]'));
    var entries = [];
    var urlToMeta = {};
    var seenUrls = new Set();

    function classifyByStatus(status) {
      if (status >= 200 && status < 300) return 'ok';
      if (status >= 300 && status < 400) return 'redirect';
      if (status >= 400 && status < 500) return 'client-error';
      if (status >= 500) return 'server-error';
      return 'network-error';
    }

    for (var i = 0; i < anchors.length; i++) {
      if (entries.length >= MAX_LINKS) break;
      var a = anchors[i];
      var hrefRaw = a.getAttribute('href');
      if (!hrefRaw) continue;
      var text = (a.textContent || '').trim().substring(0, 160);

      if (hrefRaw.startsWith('mailto:') || hrefRaw.startsWith('tel:') || hrefRaw.startsWith('javascript:')) {
        entries.push({ href: hrefRaw, status: null, status_text: 'Skipped protocol', type: 'skipped', anchor: false, element_text: text });
        continue;
      }

      if (hrefRaw.startsWith('#')) {
        var id = hrefRaw.slice(1);
        var exists = id ? !!document.getElementById(id) : true;
        entries.push({
          href: hrefRaw,
          status: exists ? 200 : 404,
          status_text: exists ? 'Anchor found' : 'Anchor target missing',
          type: exists ? 'ok' : 'client-error',
          anchor: true,
          element_text: text
        });
        continue;
      }

      var resolved;
      try {
        resolved = new URL(hrefRaw, location.href).href;
      } catch (e) {
        entries.push({ href: hrefRaw, status: null, status_text: 'Invalid URL', type: 'network-error', anchor: false, element_text: text });
        continue;
      }

      if (!seenUrls.has(resolved)) {
        seenUrls.add(resolved);
        urlToMeta[resolved] = { href: resolved, status: null, status_text: null, type: 'network-error' };
      }
      entries.push({ href: resolved, status: null, status_text: null, type: null, anchor: false, element_text: text });
    }

    var urls = Object.keys(urlToMeta);
    var checks = await Promise.allSettled(urls.map(function(url) {
      var ctrl = new AbortController();
      var timer = setTimeout(function() { ctrl.abort(); }, 5000);
      return fetch(url, { method: 'HEAD', signal: ctrl.signal })
        .then(function(r) {
          return { url: url, status: r.status, status_text: r.statusText || '', type: classifyByStatus(r.status) };
        })
        .catch(function(e) {
          return {
            url: url,
            status: null,
            status_text: e && e.name ? e.name : 'NetworkError',
            type: (e && e.name === 'AbortError') ? 'timeout' : 'network-error'
          };
        })
        .finally(function() { clearTimeout(timer); });
    }));

    checks.forEach(function(c) {
      if (c.status !== 'fulfilled') return;
      var v = c.value;
      urlToMeta[v.url] = {
        href: v.url,
        status: v.status,
        status_text: v.status_text,
        type: v.type
      };
    });

    entries = entries.map(function(e) {
      if (e.anchor || e.type === 'skipped' || e.type === 'network-error' || e.href.startsWith('mailto:') || e.href.startsWith('tel:') || e.href.startsWith('javascript:')) {
        return e;
      }
      var meta = urlToMeta[e.href] || { status: null, status_text: 'NetworkError', type: 'network-error' };
      return {
        href: e.href,
        status: meta.status,
        status_text: meta.status_text,
        type: meta.type,
        anchor: false,
        element_text: e.element_text
      };
    });

    var rank = {
      'client-error': 0,
      'server-error': 1,
      'network-error': 2,
      'timeout': 3,
      'redirect': 4,
      'ok': 5,
      'skipped': 6
    };
    entries.sort(function(a, b) {
      var ra = (a.type in rank) ? rank[a.type] : 99;
      var rb = (b.type in rank) ? rank[b.type] : 99;
      if (ra !== rb) return ra - rb;
      return String(a.href).localeCompare(String(b.href));
    });

    var ok = 0;
    var broken = 0;
    var redirects = 0;
    var timeouts = 0;
    entries.forEach(function(e) {
      if (e.type === 'ok') ok++;
      else if (e.type === 'redirect') redirects++;
      else if (e.type === 'timeout') { timeouts++; broken++; }
      else if (e.type === 'client-error' || e.type === 'server-error' || e.type === 'network-error') broken++;
    });

    return {
      url: location.href,
      total_links: entries.length,
      ok: ok,
      broken: broken,
      redirects: redirects,
      timeouts: timeouts,
      warning: anchors.length > MAX_LINKS ? ('Truncated at ' + MAX_LINKS + ' links for performance.') : null,
      links: entries
    };
  })()")

(defn link-health!
  "Checks link health using in-page bulk collection plus parallel HEAD requests.

   Uses a single `page/evaluate` call to collect anchor tags, validate hash anchors,
   skip non-HTTP protocols, and classify fetched URL statuses (2xx/3xx/4xx/5xx,
   timeout, network error). Results are capped to 100 links for performance.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url         - String. Current page URL.
     :total-links - Long. Number of link entries checked (after cap).
     :ok          - Long. Links classified as ok.
     :broken      - Long. Links classified as broken.
     :redirects   - Long. Links classified as redirects.
     :timeouts    - Long. Links classified as timeout.
     :warning     - String or nil. Truncation note when link cap is hit.
     :links       - Vector of link result maps:
       {:href :status :status-text :type :anchor? :element-text}."
  [^Page page]
  (let [result (page/evaluate page link-health-js)]
    {:url         (get result "url")
     :total-links (long (or (get result "total_links") 0))
     :ok          (long (or (get result "ok") 0))
     :broken      (long (or (get result "broken") 0))
     :redirects   (long (or (get result "redirects") 0))
     :timeouts    (long (or (get result "timeouts") 0))
     :warning     (get result "warning")
     :links       (mapv (fn [l]
                          {:href         (get l "href")
                           :status       (when-let [s (get l "status")] (long s))
                           :status-text  (get l "status_text")
                           :type         (keyword (or (get l "type") "network-error"))
                           :anchor?      (boolean (get l "anchor"))
                           :element-text (get l "element_text")})
                    (get result "links" []))}))

(def ^:private heading-structure-js
  "JavaScript to capture heading order and detect structural heading issues."
  "(function() {
    var nodes = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6'));
    var headings = [];
    var issues = [];
    var stats = { h1: 0, h2: 0, h3: 0, h4: 0, h5: 0, h6: 0 };

    nodes.forEach(function(h, idx) {
      var level = parseInt(h.tagName.slice(1), 10);
      var key = 'h' + level;
      stats[key] += 1;
      headings.push({
        level: level,
        text: (h.textContent || '').trim().substring(0, 100),
        tag: h.tagName.toLowerCase(),
        index: idx
      });
    });

    if (stats.h1 === 0) {
      issues.push({ type: 'no-h1', description: 'Document has no h1 heading.', at_index: -1 });
    }
    if (stats.h1 > 1) {
      var seen = 0;
      headings.forEach(function(h) {
        if (h.level !== 1) return;
        seen += 1;
        if (seen > 1) {
          issues.push({ type: 'multiple-h1', description: 'Additional h1 found beyond the first.', at_index: h.index });
        }
      });
    }

    for (var i = 1; i < headings.length; i++) {
      var prev = headings[i - 1];
      var curr = headings[i];
      var jump = curr.level - prev.level;
      if (jump > 1) {
        issues.push({
          type: 'skipped-level',
          description: 'Heading level jumps from h' + prev.level + ' to h' + curr.level + '.',
          at_index: curr.index
        });
      }
      if (curr.level < prev.level && (prev.level - curr.level) > 1) {
        issues.push({
          type: 'wrong-order',
          description: 'Heading level drops from h' + prev.level + ' to h' + curr.level + ' abruptly.',
          at_index: curr.index
        });
      }
    }

    return {
      url: location.href,
      headings: headings,
      issues: issues,
      valid: issues.length === 0,
      stats: stats
    };
  })()")

(defn heading-structure!
  "Validates heading hierarchy and order from h1..h6 in DOM sequence.

   Uses one lightweight JavaScript evaluation over heading tags only, returning
   heading entries in document order with structural issues (missing/multiple h1,
   skipped levels, abrupt order drops).

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with:
     :url      - String. Current page URL.
     :headings - Vector of {:level :text :tag :index}.
     :issues   - Vector of {:type :description :at-index}.
     :valid?   - Boolean.
     :stats    - {:h1 :h2 :h3 :h4 :h5 :h6}."
  [^Page page]
  (let [result (page/evaluate page heading-structure-js)]
    {:url      (get result "url")
     :headings (mapv (fn [h]
                       {:level (long (or (get h "level") 0))
                        :text  (get h "text")
                        :tag   (get h "tag")
                        :index (long (or (get h "index") 0))})
                 (get result "headings" []))
     :issues   (mapv (fn [i]
                       {:type        (keyword (get i "type"))
                        :description (get i "description")
                        :at-index    (long (or (get i "at_index") -1))})
                 (get result "issues" []))
     :valid?   (boolean (get result "valid"))
     :stats    (let [s (get result "stats")]
                 {:h1 (long (or (get s "h1") 0))
                  :h2 (long (or (get s "h2") 0))
                  :h3 (long (or (get s "h3") 0))
                  :h4 (long (or (get s "h4") 0))
                  :h5 (long (or (get s "h5") 0))
                  :h6 (long (or (get s "h6") 0))})}))

;; =============================================================================
;; page-insights! — Performance, meta, SEO, DOM stats in one shot
;; =============================================================================

(def ^:private page-insights-js
  "JavaScript to collect page insights: performance timing, meta tags,
   DOM stats, images, forms, scripts, SEO signals in one evaluation."
  "(function() {
    var r = {};
    // Performance timing
    try {
      var nav = performance.getEntriesByType('navigation')[0];
      if (nav) {
        r.timing = {
          dns_ms: Math.round(nav.domainLookupEnd - nav.domainLookupStart),
          connect_ms: Math.round(nav.connectEnd - nav.connectStart),
          ttfb_ms: Math.round(nav.responseStart - nav.requestStart),
          dom_interactive_ms: Math.round(nav.domInteractive - nav.startTime),
          dom_complete_ms: Math.round(nav.domComplete - nav.startTime),
          load_ms: Math.round(nav.loadEventEnd - nav.startTime),
          transfer_size: nav.transferSize || 0,
          encoded_size: nav.encodedBodySize || 0,
          decoded_size: nav.decodedBodySize || 0
        };
      }
    } catch(e) { r.timing = null; }
    // Resource summary
    try {
      var res = performance.getEntriesByType('resource');
      var byType = {};
      var totalSize = 0;
      res.forEach(function(e) {
        var t = e.initiatorType || 'other';
        if (!byType[t]) byType[t] = { count: 0, size: 0, duration: 0 };
        byType[t].count++;
        byType[t].size += (e.transferSize || 0);
        byType[t].duration += e.duration;
        totalSize += (e.transferSize || 0);
      });
      var summary = [];
      for (var k in byType) {
        summary.push({ type: k, count: byType[k].count,
          size_bytes: Math.round(byType[k].size),
          avg_duration_ms: Math.round(byType[k].duration / byType[k].count) });
      }
      summary.sort(function(a,b) { return b.size_bytes - a.size_bytes; });
      r.resources = { total_count: res.length, total_size_bytes: Math.round(totalSize), by_type: summary };
    } catch(e) { r.resources = null; }
    // DOM stats
    var all = document.querySelectorAll('*');
    r.dom = {
      total_elements: all.length,
      forms: document.forms.length,
      images: document.images.length,
      scripts: document.scripts.length,
      stylesheets: document.styleSheets.length,
      iframes: document.querySelectorAll('iframe').length
    };
    // Images without alt
    var imgs = Array.from(document.images);
    var noAlt = imgs.filter(function(i) { return !i.getAttribute('alt') || i.alt.trim() === ''; });
    r.images = {
      total: imgs.length,
      missing_alt: noAlt.length,
      missing_alt_samples: noAlt.slice(0, 10).map(function(i) { return i.src ? i.src.substring(0, 120) : ''; })
    };
    // Meta tags
    var getMeta = function(name) {
      var el = document.querySelector('meta[name=\"' + name + '\"]') || document.querySelector('meta[property=\"' + name + '\"]');
      return el ? el.getAttribute('content') : null;
    };
    r.meta = {
      title: document.title,
      title_length: document.title.length,
      description: getMeta('description'),
      description_length: getMeta('description') ? getMeta('description').length : 0,
      viewport: getMeta('viewport'),
      charset: document.characterSet,
      canonical: (function() { var l = document.querySelector('link[rel=\"canonical\"]'); return l ? l.href : null; })(),
      robots: getMeta('robots'),
      og_title: getMeta('og:title'),
      og_description: getMeta('og:description'),
      og_image: getMeta('og:image'),
      lang: document.documentElement.lang || null
    };
    // Forms
    var forms = Array.from(document.forms);
    var inputsWithoutLabel = 0;
    forms.forEach(function(f) {
      Array.from(f.elements).forEach(function(el) {
        if (['INPUT','SELECT','TEXTAREA'].indexOf(el.tagName) >= 0 && el.type !== 'hidden') {
          var id = el.id;
          var hasLabel = id && document.querySelector('label[for=\"' + id + '\"]');
          var hasAria = el.getAttribute('aria-label') || el.getAttribute('aria-labelledby');
          var wrapped = el.closest('label');
          if (!hasLabel && !hasAria && !wrapped) inputsWithoutLabel++;
        }
      });
    });
    r.forms = { total: forms.length, inputs_without_label: inputsWithoutLabel };
    // Viewport
    r.viewport = {
      width: window.innerWidth, height: window.innerHeight,
      scroll_width: document.documentElement.scrollWidth,
      scroll_height: document.documentElement.scrollHeight,
      device_pixel_ratio: window.devicePixelRatio
    };
    // Third-party analysis
    var origin = location.origin;
    var scripts = Array.from(document.querySelectorAll('script[src]'));
    var thirdParty = [];
    var renderBlocking = [];
    var domainSet = {};
    scripts.forEach(function(s) {
      var src = s.getAttribute('src') || '';
      var isExternal = false;
      try { isExternal = src.startsWith('http') && !src.startsWith(origin); } catch(e) {}
      var hasAsync = s.hasAttribute('async');
      var hasDefer = s.hasAttribute('defer');
      var inHead = s.closest('head') !== null;
      if (isExternal) {
        try { var u = new URL(src); domainSet[u.hostname] = (domainSet[u.hostname] || 0) + 1; } catch(e) {}
        thirdParty.push({ src: src.substring(0, 150), async: hasAsync, defer: hasDefer, in_head: inHead });
      }
      if (inHead && !hasAsync && !hasDefer && src) {
        renderBlocking.push({ src: src.substring(0, 150), external: isExternal });
      }
    });
    var rbCss = [];
    Array.from(document.querySelectorAll('link[rel=stylesheet]')).forEach(function(l) {
      var href = l.getAttribute('href') || '';
      if (l.closest('head') && !l.hasAttribute('media') || l.getAttribute('media') === 'all') {
        rbCss.push({ href: href.substring(0, 150) });
      }
    });
    var domains = [];
    for (var d in domainSet) domains.push({ domain: d, count: domainSet[d] });
    domains.sort(function(a,b) { return b.count - a.count; });
    r.third_party = { scripts: thirdParty.length, domains: domains, render_blocking_scripts: renderBlocking, render_blocking_css: rbCss };
    // Mixed content
    var mixed = [];
    if (location.protocol === 'https:') {
      document.querySelectorAll('img[src],script[src],link[href],iframe[src],video[src],audio[src]').forEach(function(el) {
        var u = el.getAttribute('src') || el.getAttribute('href') || '';
        if (u.startsWith('http:')) mixed.push({ tag: el.tagName.toLowerCase(), url: u.substring(0, 150) });
      });
    }
    r.mixed_content = { count: mixed.length, items: mixed.slice(0, 20) };
    // Structured data
    var jsonLd = []; try { jsonLd = document.querySelectorAll('script[type]'); jsonLd = Array.from(jsonLd).filter(function(s) { return (s.getAttribute('type') || '').indexOf('ld+json') > -1; }); } catch(e) {}
    var schemaTypes = [];
    jsonLd.forEach(function(s) { try { var j = JSON.parse(s.textContent); schemaTypes.push(j['@type'] || 'unknown'); } catch(e) {} });
    var microdata = document.querySelectorAll('[itemscope]').length;
    r.structured_data = { json_ld_count: jsonLd.length, types: schemaTypes, microdata_count: microdata };
    // Cookies
    var cookies = document.cookie ? document.cookie.split(';') : [];
    r.cookies = { count: cookies.length, total_size: document.cookie.length };
    // Duplicate IDs
    var idMap = {};
    var dupIds = [];
    document.querySelectorAll('[id]').forEach(function(el) {
      var id = el.id;
      if (id) { idMap[id] = (idMap[id] || 0) + 1; }
    });
    for (var id in idMap) { if (idMap[id] > 1) dupIds.push({ id: id, count: idMap[id] }); }
    r.duplicate_ids = { count: dupIds.length, items: dupIds.slice(0, 20) };
    // ARIA / a11y extended
    var skipNav = document.querySelector('a[href*=main],a[href*=content],[class*=skip]');
    var ariaRoles = {};
    document.querySelectorAll('[role]').forEach(function(el) {
      var role = el.getAttribute('role');
      ariaRoles[role] = (ariaRoles[role] || 0) + 1;
    });
    var emptyLinks = Array.from(document.querySelectorAll('a')).filter(function(a) { return !a.textContent.trim() && !a.querySelector('img[alt]') && !a.getAttribute('aria-label'); }).length;
    var emptyBtns = Array.from(document.querySelectorAll('button')).filter(function(b) { return !b.textContent.trim() && !b.getAttribute('aria-label'); }).length;
    r.a11y_extended = { skip_nav: !!skipNav, aria_roles: ariaRoles, empty_links: emptyLinks, empty_buttons: emptyBtns };
    // Image optimization extended
    var imgStats = { missing_dimensions: 0, no_lazy_below_fold: 0, large_src: 0, formats: {} };
    var vpH = window.innerHeight;
    imgs.forEach(function(img) {
      if (!img.getAttribute('width') || !img.getAttribute('height')) imgStats.missing_dimensions++;
      var rect = img.getBoundingClientRect();
      if (rect.top > vpH && img.getAttribute('loading') !== 'lazy') imgStats.no_lazy_below_fold++;
      var src = (img.currentSrc || img.src || '').toLowerCase();
      var ext = 'other';
      if (src.indexOf('.webp') > -1 || src.indexOf('format=webp') > -1) ext = 'webp';
      else if (src.indexOf('.avif') > -1) ext = 'avif';
      else if (src.indexOf('.jpg') > -1 || src.indexOf('.jpeg') > -1) ext = 'jpeg';
      else if (src.indexOf('.png') > -1) ext = 'png';
      else if (src.indexOf('.gif') > -1) ext = 'gif';
      else if (src.indexOf('.svg') > -1) ext = 'svg';
      else if (src.indexOf('data:') === 0) ext = 'data-uri';
      imgStats.formats[ext] = (imgStats.formats[ext] || 0) + 1;
    });
    r.images_extended = imgStats;
    try {
    // Font loading
    var fontLinks = document.querySelectorAll('link[rel=stylesheet][href*=font],link[rel=preload][as=font],link[href*=fonts.googleapis],link[href*=fonts.gstatic]');
    var preloadedFonts = document.querySelectorAll('link[rel=preload][as=font]').length;
    var fontFaceCount = 0;
    try { fontFaceCount = document.fonts ? document.fonts.size : 0; } catch(e) {}
    r.font_loading = { font_links: fontLinks.length, preloaded: preloadedFonts, font_faces_loaded: fontFaceCount };
    // HTTPS
    r.https = { is_https: location.protocol === 'https:', protocol: location.protocol };
    // Sitemap / robots hints
    var sitemapLink = document.querySelector('link[rel=sitemap]');
    r.seo_extended = {
      has_sitemap_link: !!sitemapLink,
      sitemap_href: sitemapLink ? sitemapLink.href : null,
      internal_links: Array.from(document.querySelectorAll('a[href]')).filter(function(a) { try { return new URL(a.href).origin === origin; } catch(e) { return false; } }).length,
      external_links: Array.from(document.querySelectorAll('a[href]')).filter(function(a) { try { return a.href.startsWith('http') && new URL(a.href).origin !== origin; } catch(e) { return false; } }).length
    };
    // Preconnect / prefetch
    var preconnects = document.querySelectorAll('link[rel=preconnect]');
    var prefetches = document.querySelectorAll('link[rel=prefetch],link[rel=dns-prefetch]');
    r.resource_hints = {
      preconnect: Array.from(preconnects).map(function(l) { return l.href; }).slice(0, 10),
      prefetch: Array.from(prefetches).map(function(l) { return l.href; }).slice(0, 10)
    };
    // Inline styles
    r.inline_styles = { count: document.querySelectorAll('[style]').length };
    // Deprecated HTML
    var deprecated = ['font','center','marquee','blink','frame','frameset','noframes','applet','basefont','dir','isindex','listing','plaintext','strike','tt','xmp','big','bgsound'];
    var depFound = [];
    deprecated.forEach(function(tag) { var c = document.querySelectorAll(tag).length; if (c > 0) depFound.push({ tag: tag, count: c }); });
    r.deprecated_html = { count: depFound.length, items: depFound };
    // Responsive images
    var withSrcset = Array.from(document.querySelectorAll('img[srcset]')).length;
    var withSizes = Array.from(document.querySelectorAll('img[sizes]')).length;
    var withPicture = document.querySelectorAll('picture').length;
    r.responsive_images = { srcset: withSrcset, sizes: withSizes, picture_elements: withPicture, total_images: imgs.length };
    // Page weight
    var pageWeight = 0;
    try { performance.getEntriesByType('resource').forEach(function(e) { pageWeight += (e.transferSize || 0); }); } catch(e) {}
    r.page_weight = { total_bytes: Math.round(pageWeight), budget_1500kb: pageWeight <= 1536000 };
    // Focus visible
    var hasFocusVisible = false;
    try {
      for (var si = 0; si < document.styleSheets.length; si++) {
        try {
          var rules = document.styleSheets[si].cssRules || [];
          for (var ri = 0; ri < rules.length; ri++) {
            if (rules[ri].selectorText && rules[ri].selectorText.indexOf('focus-visible') > -1) { hasFocusVisible = true; break; }
            if (rules[ri].selectorText && rules[ri].selectorText.indexOf(':focus') > -1) { hasFocusVisible = true; break; }
          }
        } catch(ce) {}
        if (hasFocusVisible) break;
      }
    } catch(e) {}
    r.focus_visible = { has_focus_styles: hasFocusVisible };
    // Tab order
    var positiveTabindex = Array.from(document.querySelectorAll('[tabindex]')).filter(function(el) {
      var ti = parseInt(el.getAttribute('tabindex'), 10);
      return ti > 0;
    });
    var focusableCount = document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]').length;
    var negativeTabindex = Array.from(document.querySelectorAll('[tabindex]')).filter(function(el){return el.getAttribute('tabindex')==='-1'}).length;
    r.tab_order = { positive_tabindex: positiveTabindex.length, focusable_elements: focusableCount, negative_tabindex: negativeTabindex,
      positive_samples: positiveTabindex.slice(0, 5).map(function(el) { return { tag: el.tagName.toLowerCase(), tabindex: el.getAttribute('tabindex'), text: (el.textContent || '').trim().substring(0, 60) }; }) };
    // Oversized images
    var oversized = [];
    try {
      performance.getEntriesByType('resource').forEach(function(re) {
        if (re.initiatorType === 'img' && re.transferSize > 204800) {
          oversized.push({ url: re.name.substring(0, 150), size_bytes: Math.round(re.transferSize), duration_ms: Math.round(re.duration) });
        }
      });
    } catch(e) {}
    r.oversized_images = { count: oversized.length, items: oversized.slice(0, 20) };
    // Unused CSS/JS estimation via Coverage API (if available)
    var coverageAvailable = typeof window.__coverage__ !== 'undefined';
    var totalCSSBytes = 0, totalJSBytes = 0;
    try {
      performance.getEntriesByType('resource').forEach(function(re) {
        if (re.initiatorType === 'link' || re.initiatorType === 'css') totalCSSBytes += (re.transferSize || 0);
        if (re.initiatorType === 'script') totalJSBytes += (re.transferSize || 0);
      });
    } catch(e) {}
    r.asset_sizes = { css_total_bytes: Math.round(totalCSSBytes), js_total_bytes: Math.round(totalJSBytes), coverage_available: coverageAvailable };
    } catch(ex) { r._extended_error = ex.message; }
    return r;
  })()")

(defn page-insights!
  "Collects page performance, meta tags, DOM stats, image accessibility,
   form labels, and resource loading summary in one evaluation.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with :timing, :resources, :dom, :images, :meta, :forms, :viewport."
  [^Page page]
  (let [raw (page/evaluate page page-insights-js)]
    {:timing    (when-let [t (get raw "timing")]
                 {:dns-ms            (long (or (get t "dns_ms") 0))
                  :connect-ms        (long (or (get t "connect_ms") 0))
                  :ttfb-ms           (long (or (get t "ttfb_ms") 0))
                  :dom-interactive-ms (long (or (get t "dom_interactive_ms") 0))
                  :dom-complete-ms   (long (or (get t "dom_complete_ms") 0))
                  :load-ms           (long (or (get t "load_ms") 0))
                  :transfer-size     (long (or (get t "transfer_size") 0))
                  :encoded-size      (long (or (get t "encoded_size") 0))
                  :decoded-size      (long (or (get t "decoded_size") 0))})
     :resources (when-let [r (get raw "resources")]
                  {:total-count     (long (or (get r "total_count") 0))
                   :total-size-bytes (long (or (get r "total_size_bytes") 0))
                   :by-type         (mapv (fn [t]
                                            {:type            (get t "type")
                                             :count           (long (or (get t "count") 0))
                                             :size-bytes      (long (or (get t "size_bytes") 0))
                                             :avg-duration-ms (long (or (get t "avg_duration_ms") 0))})
                                     (get r "by_type" []))})
     :dom       (let [d (get raw "dom")]
                  {:total-elements (long (or (get d "total_elements") 0))
                   :forms          (long (or (get d "forms") 0))
                   :images         (long (or (get d "images") 0))
                   :scripts        (long (or (get d "scripts") 0))
                   :stylesheets    (long (or (get d "stylesheets") 0))
                   :iframes        (long (or (get d "iframes") 0))})
     :images    (let [im (get raw "images")]
                  {:total              (long (or (get im "total") 0))
                   :missing-alt        (long (or (get im "missing_alt") 0))
                   :missing-alt-samples (vec (get im "missing_alt_samples" []))})
     :meta      (let [m (get raw "meta")]
                  {:title              (get m "title")
                   :title-length       (long (or (get m "title_length") 0))
                   :description        (get m "description")
                   :description-length (long (or (get m "description_length") 0))
                   :viewport           (get m "viewport")
                   :charset            (get m "charset")
                   :canonical          (get m "canonical")
                   :robots             (get m "robots")
                   :og-title           (get m "og_title")
                   :og-description     (get m "og_description")
                   :og-image           (get m "og_image")
                   :lang               (get m "lang")})
     :forms     (let [f (get raw "forms")]
                  {:total                (long (or (get f "total") 0))
                   :inputs-without-label (long (or (get f "inputs_without_label") 0))})
     :viewport  (let [v (get raw "viewport")]
                  {:width              (long (or (get v "width") 0))
                   :height             (long (or (get v "height") 0))
                   :scroll-width       (long (or (get v "scroll_width") 0))
                   :scroll-height      (long (or (get v "scroll_height") 0))
                   :device-pixel-ratio (double (or (get v "device_pixel_ratio") 1.0))})
     :third-party (let [tp (get raw "third_party")]
                    {:scripts              (long (or (get tp "scripts") 0))
                     :domains              (mapv (fn [d] {:domain (get d "domain") :count (long (or (get d "count") 0))})
                                             (get tp "domains" []))
                     :render-blocking-scripts (mapv (fn [s] {:src (get s "src") :external (boolean (get s "external"))})
                                               (get tp "render_blocking_scripts" []))
                     :render-blocking-css    (mapv (fn [s] {:href (get s "href")})
                                               (get tp "render_blocking_css" []))})
     :mixed-content (let [mc (get raw "mixed_content")]
                      {:count (long (or (get mc "count") 0))
                       :items (mapv (fn [m] {:tag (get m "tag") :url (get m "url")})
                                (get mc "items" []))})
     :structured-data (let [sd (get raw "structured_data")]
                        {:json-ld-count  (long (or (get sd "json_ld_count") 0))
                         :types          (vec (get sd "types" []))
                         :microdata-count (long (or (get sd "microdata_count") 0))})
     :cookies   (let [c (get raw "cookies")]
                  {:count      (long (or (get c "count") 0))
                   :total-size (long (or (get c "total_size") 0))})
     :duplicate-ids (let [di (get raw "duplicate_ids")]
                      {:count (long (or (get di "count") 0))
                       :items (mapv (fn [d] {:id (get d "id") :count (long (or (get d "count") 0))})
                                (get di "items" []))})
     :a11y-extended (let [a (get raw "a11y_extended")]
                      {:skip-nav      (boolean (get a "skip_nav"))
                       :aria-roles    (into {} (map (fn [[k v]] [(keyword k) (long v)])
                                                 (get a "aria_roles" {})))
                       :empty-links   (long (or (get a "empty_links") 0))
                       :empty-buttons (long (or (get a "empty_buttons") 0))})
     :images-extended (let [ie (get raw "images_extended")]
                        {:missing-dimensions   (long (or (get ie "missing_dimensions") 0))
                         :no-lazy-below-fold   (long (or (get ie "no_lazy_below_fold") 0))
                         :formats              (into {} (map (fn [[k v]] [(keyword k) (long v)])
                                                         (get ie "formats" {})))})
     :font-loading (let [fl (get raw "font_loading")]
                     {:font-links       (long (or (get fl "font_links") 0))
                      :preloaded        (long (or (get fl "preloaded") 0))
                      :font-faces-loaded (long (or (get fl "font_faces_loaded") 0))})
     :https         (let [h (get raw "https")]
                     {:is-https (boolean (get h "is_https"))
                      :protocol (get h "protocol")})
     :seo-extended  (let [s (get raw "seo_extended")]
                     {:has-sitemap-link (boolean (get s "has_sitemap_link"))
                      :sitemap-href    (get s "sitemap_href")
                      :internal-links  (long (or (get s "internal_links") 0))
                      :external-links  (long (or (get s "external_links") 0))})
     :resource-hints (let [rh (get raw "resource_hints")]
                       {:preconnect (vec (get rh "preconnect" []))
                        :prefetch   (vec (get rh "prefetch" []))})
     :inline-styles {:count (long (or (get-in raw ["inline_styles" "count"]) 0))}
     :deprecated-html (let [dh (get raw "deprecated_html")]
                        {:count (long (or (get dh "count") 0))
                         :items (mapv (fn [d] {:tag (get d "tag") :count (long (or (get d "count") 0))})
                                  (get dh "items" []))})
     :responsive-images (let [ri (get raw "responsive_images")]
                          {:srcset          (long (or (get ri "srcset") 0))
                           :sizes           (long (or (get ri "sizes") 0))
                           :picture-elements (long (or (get ri "picture_elements") 0))
                           :total-images    (long (or (get ri "total_images") 0))})
     :page-weight (let [pw (get raw "page_weight")]
                    {:total-bytes  (long (or (get pw "total_bytes") 0))
                     :budget-ok    (boolean (get pw "budget_1500kb"))})
     :focus-visible (let [fv (get raw "focus_visible")]
                      {:has-focus-styles (boolean (get fv "has_focus_styles"))})
     :tab-order (let [to (get raw "tab_order")]
                  {:positive-tabindex  (long (or (get to "positive_tabindex") 0))
                   :focusable-elements (long (or (get to "focusable_elements") 0))
                   :negative-tabindex  (long (or (get to "negative_tabindex") 0))
                   :positive-samples   (mapv (fn [s] {:tag (get s "tag") :tabindex (get s "tabindex") :text (get s "text")})
                                          (get to "positive_samples" []))})
     :oversized-images (let [oi (get raw "oversized_images")]
                         {:count (long (or (get oi "count") 0))
                          :items (mapv (fn [o] {:url (get o "url") :size-bytes (long (or (get o "size_bytes") 0))
                                                :duration-ms (long (or (get o "duration_ms") 0))})
                                   (get oi "items" []))})
     :asset-sizes (let [as (get raw "asset_sizes")]
                    {:css-total-bytes    (long (or (get as "css_total_bytes") 0))
                     :js-total-bytes     (long (or (get as "js_total_bytes") 0))
                     :coverage-available (boolean (get as "coverage_available"))})}))


;; =============================================================================
;; audit-all! — Run every audit sub-check, return combined data map
;; =============================================================================

(defn audit-all!
  "Runs every audit sub-check and returns the combined results map.

   This is the canonical way to collect all audit data from a page.
   Individual audit failures are caught and returned as {:error \"msg\"}
   so the caller always gets a complete map.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with keys :structure, :contrast, :colors, :layout, :fonts, :links,
   :headings — each containing the result map from the corresponding audit
   function, or {:error \"msg\"} if that audit failed."
  [^Page page]
  (let [safe (fn [f] (try (f) (catch Exception e {:error (.getMessage e)})))]
    {:structure (safe #(audit! page))
     :contrast  (safe #(text-contrast! page))
     :colors    (safe #(color-palette! page))
     :layout    (safe #(layout-check! page))
     :fonts     (safe #(font-audit! page))
     :links     (safe #(link-health! page))
     :headings  (safe #(heading-structure! page))
     :insights  (safe #(page-insights! page))}))

;; =============================================================================
;; write-audit-report! — Generate HTML report from audit data
;; =============================================================================

(defn write-audit-report!
  "Generates an HTML audit report from pre-computed audit data and writes it
   to a file. The report is fully deterministic — same data in, same HTML out
   (modulo timestamp).

   The audit data map is exactly what `audit-all!` returns, or equivalently
   the JSON output of `spel audit --all`. This function never runs audits
   itself.

   Params:
   `audit-data` - Map with keys :structure, :contrast, :colors, :layout,
                  :fonts, :links, :headings.
   `path`       - String. Output file path for the HTML report.
   `opts`       - Map, optional:
     :url        - String. Page URL (fallback if not in audit data).
     :title      - String. Page title.
     :screenshot - byte[]. PNG screenshot to embed.

   Returns:
   Map with:
     :path - String. Output file path.
     :size - Long. File size in bytes."
  ([audit-data ^String path]
   (write-audit-report! audit-data path {}))
  ([audit-data ^String path opts]
   (let [html       (audit-report/generate audit-data opts)
         html-bytes (.getBytes ^String html "UTF-8")
         out-path   (Path/of ^String path (into-array String []))]
     (Files/write out-path html-bytes
       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
     {:path path
      :size (alength html-bytes)})))
