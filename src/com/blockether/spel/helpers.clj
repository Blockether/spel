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
   If :path is set: {:path \"...\" :size N :refs-annotated N}
   If no :path: {:bytes <byte[]> :refs-annotated N}"
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
         n-annotated  (annotate/inject-overlays! page refs annotate-opts)
         ;; Take full-page screenshot
         ss-bytes     (try
                        (page/screenshot page {:full-page true})
                        (finally
                          (annotate/remove-overlays! page)))]
     (if-let [path (:path opts)]
       (do (save-bytes! ss-bytes path)
           {:path path :size (alength ^bytes ss-bytes) :refs-annotated n-annotated})
       {:bytes ss-bytes :refs-annotated n-annotated}))))

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
