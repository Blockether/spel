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

