(ns com.blockether.spel.annotate
  "Page annotation with ref labels, bounding boxes, and dimensions.

   Injects CSS overlays directly into the page DOM. Overlays persist until
   explicitly removed with `remove-overlays!`. No AWT dependency — works in
   GraalVM native-image without any java.awt configuration.

   Usage:
     (def snap (snapshot/capture-snapshot page))
     (inject-overlays! page (:refs snap))   ;; overlays now visible on page
     ;; ... inspect, screenshot, etc. ...
     (remove-overlays! page)                ;; clean up"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Page]))

;; =============================================================================
;; Role → Color mapping (CSS hex)
;; =============================================================================

(def ^:private role-colors
  {"button"       "#4CAF50"    ;; green
   "link"         "#2196F3"    ;; blue
   "textbox"      "#FF9800"    ;; orange
   "searchbox"    "#FF9800"    ;; orange
   "combobox"     "#FF9800"    ;; orange
   "checkbox"     "#9C27B0"    ;; purple
   "radio"        "#9C27B0"    ;; purple
   "heading"      "#E91E63"    ;; pink
   "img"          "#00BCD4"    ;; cyan
   "navigation"   "#795548"    ;; brown
   "dialog"       "#FF5722"    ;; deep orange
   "tab"          "#3F51B5"    ;; indigo
   "menuitem"     "#3F51B5"    ;; indigo
   "slider"       "#CDDC39"    ;; lime
   "spinbutton"   "#FF9800"    ;; orange
   "switch"       "#9C27B0"    ;; purple
   "progressbar"  "#009688"    ;; teal
   "table"        "#607D8B"    ;; blue-grey
   "list"         "#607D8B"    ;; blue-grey
   "listitem"     "#607D8B"    ;; blue-grey
   "span"         "#78909C"    ;; grey
   "p"            "#78909C"    ;; grey
   "paragraph"    "#78909C"    ;; grey
   "text"         "#78909C"    ;; grey (text-bearing generic elements)
   })

(def ^:private default-color "#F44336") ;; red

(defn- color-for-role [role]
  (get role-colors role default-color))

;; =============================================================================
;; Annotation filtering (reduce clutter by skipping structural elements)
;; =============================================================================

(def ^:private annotatable-roles
  "Roles that are rendered as annotation overlays.

   Interactive elements, content anchors, and text containers.
   Pure layout/structural roles (region, group, form, etc.) are filtered
   out to reduce clutter. Containment dedup handles overlap when a
   paragraph wraps a link — the link wins, the paragraph is suppressed."
  #{"button" "link" "textbox" "searchbox" "combobox" "checkbox" "radio"
    "switch" "slider" "spinbutton" "menuitem" "menuitemcheckbox"
    "menuitemradio" "option" "treeitem" "tab"
    "heading" "img" "dialog" "alertdialog" "navigation" "progressbar"
    "paragraph" "p" "span" "listitem" "text"})

(defn- annotatable-role?
  "Returns true if the role should be drawn as an annotation overlay."
  [role]
  (contains? annotatable-roles role))

(defn- bbox-contains?
  "Returns true if outer bbox fully contains inner bbox (with 2px epsilon).

  Used to detect parent-wrapping-child overlap: if a paragraph's bbox
  fully wraps a link's bbox, we prefer annotating the link only.
  
  IMPORTANT: The outer bbox must be strictly larger in at least one dimension
  to avoid false positives when two elements have identical bboxes (e.g., an
  img that fills its containing link). Without this check, equal-sized elements
  would 'contain' each other, leading to the child being incorrectly suppressed."
  [{ox :x oy :y ow :width oh :height}
   {ix :x iy :y iw :width ih :height}]
  (let [eps 2.0
        ox (double ox) oy (double oy) ow (double ow) oh (double oh)
        ix (double ix) iy (double iy) iw (double iw) ih (double ih)]
    (and (<= (- ox eps) ix)
      (<= (- oy eps) iy)
      (>= (+ ox ow eps) (+ ix iw))
      (>= (+ oy oh eps) (+ iy ih))
      ;; Must be strictly larger in at least one dimension (without epsilon)
      ;; to be a true container — prevents identical bboxes from suppressing
      ;; each other (e.g., <a><img></a> where img fills the link)
      (or (> ow iw) (> oh ih)))))

(defn remove-containers
  "Removes refs whose bbox fully contains another ref's bbox.

   When a parent element fully wraps a child (e.g., paragraph around a link),
   the parent is suppressed to avoid overlapping boxes and labels.
   Ties on identical-size bboxes are broken by ref ID (lower ID kept)."
  [refs]
  (if (<= (count refs) 1)
    refs
    (let [entries (vec refs)
          bbox-area (fn ^long [{:keys [width height]}] (* (long width) (long height)))
          suppressed
          (into #{}
            (for [[id-a info-a] entries
                  [id-b info-b] entries
                  :when (not= id-a id-b)
                  :let [bbox-a (:bbox info-a)
                        bbox-b (:bbox info-b)]
                  :when (and bbox-a bbox-b
                          (bbox-contains? bbox-a bbox-b)
                          ;; Don't suppress mixed-content containers — they have
                          ;; their own direct text content distinct from children
                          (not (:mixed info-a)))
                  :let [area-a (long (bbox-area bbox-a))
                        area-b (long (bbox-area bbox-b))]
                  ;; A zero-area child (invisible/hidden element) must not
                  ;; trigger suppression of a visible container (e.g., a 0×0
                  ;; hidden link inside a CSS-background logo div)
                  :when (pos? area-b)
                  :when (or (> area-a area-b)
                          (and (= area-a area-b)
                            (pos? (compare id-a id-b))))]
              id-a))]
      (apply dissoc refs suppressed))))

(defn filter-annotatable
  "Filters refs to only those worth rendering as overlays.

   Two-step process:
   1. Remove structural roles (paragraph, list, span, etc.)
   2. Remove containers whose bbox fully wraps a smaller ref

   Returns a subset of refs suitable for `build-inject-js`."
  [refs]
  (-> (into {}
        (filter (fn [[_ info]] (annotatable-role? (:role info))))
        refs)
    remove-containers))

;; =============================================================================
;; Viewport filtering (fast, Clojure-side pre-filter)
;; =============================================================================

(defn- bbox-visible?
  "Returns true if the bbox rectangle overlaps the viewport.

   viewport: {:width W :height H}  — scroll offset is 0,0 (viewport coords).
   bbox:     {:x X :y Y :width W :height H}"
  [{vw :width vh :height} {:keys [x y width height]}]
  (let [vw (double vw) vh (double vh)
        x (double x) y (double y) width (double width) height (double height)]
    (and (pos? width) (pos? height)
      (< x vw) (< y vh)
      (> (+ x width) 0.0) (> (+ y height) 0.0))))

(defn visible-refs
  "Filters refs to only those whose bbox is at least partially visible
   within the given viewport dimensions.

   `viewport` — {:width N :height N}
   `refs`     — snapshot refs map"
  [viewport refs]
  (into {}
    (filter (fn [[_ info]]
              (when-let [bbox (:bbox info)]
                (bbox-visible? viewport bbox))))
    refs))

;; =============================================================================
;; DOM visibility check (JS-side, detects occlusion via data-pw-ref)
;; =============================================================================

(defn- build-visibility-check-js
  "Builds JS that checks which refs are truly visible using elementFromPoint.

   The snapshot tags each ref element with data-pw-ref='eN'. For each ref:
   1. Compute multiple sample points across the bbox (center + 4 inset corners)
   2. For each point, elementFromPoint → get the topmost DOM element
   3. Pierce through invisible overlays (opacity:0, visibility:hidden,
      pointer-events:none) by temporarily hiding them and re-probing
   4. Walk UP from the hit element checking if any ancestor has data-pw-ref
      matching this ref ID → if yes, the element is on top = visible
   5. If ANY sample point matches, the element is visible
   6. Fallback: if no probe matches, query the element directly by
      data-pw-ref and check its computed styles. If the element exists and
      is stylistically visible (display, visibility, opacity), consider it
      visible. This catches elements under transparent containers like
      navbars that fully cover the target's bbox.

   Multi-point sampling handles partial occlusion: when a navbar or other
   element covers the center of a logo, a corner probe still hits the logo.

   This is exact — no heuristic role matching. Uses the same data-pw-ref
   attribute that capture-snapshot already sets on the DOM."
  [refs]
  (let [inset 5.0
        items (for [[ref-id _info] refs
                    :let [{:keys [x y width height]} (:bbox _info)
                          x (double x) y (double y)
                          w (double width) h (double height)
                          cx (+ x (/ w 2.0))
                          cy (+ y (/ h 2.0))
                          ;; Inset corners — clamp to center for very small elements
                          left   (min (+ x inset) cx)
                          right  (max (- (+ x w) inset) cx)
                          top    (min (+ y inset) cy)
                          bottom (max (- (+ y h) inset) cy)]
                    :when (and (pos? w) (pos? h))]
                (str "{id:'" ref-id "'"
                  ",pts:["
                  "[" cx "," cy "],"
                  "[" left "," top "],"
                  "[" right "," top "],"
                  "[" left "," bottom "],"
                  "[" right "," bottom "]"
                  "]}"))]
    (str
      "(function(){"
      "var checks=[" (apply str (interpose "," items)) "];"
      "var vw=window.innerWidth,vh=window.innerHeight;"
      "var visible=[];"
     ;; Helper: probe a single point, piercing invisible overlays.
     ;; Returns true if the hit element (or ancestor) has matching data-pw-ref.
      "function probe(px,py,id){"
      "  if(px<0||py<0||px>=vw||py>=vh)return false;"
      "  var hidden=[];"
      "  var el=document.elementFromPoint(px,py);"
      "  while(el){"
      "    var s=getComputedStyle(el);"
      "    if(parseFloat(s.opacity)===0||s.visibility==='hidden'||s.pointerEvents==='none'){"
      "      el.style.display='none';hidden.push(el);"
      "      el=document.elementFromPoint(px,py);"
      "    }else{break;}"
      "  }"
      "  hidden.forEach(function(h){h.style.display='';});"
      "  if(!el)return false;"
      "  var node=el;"
      "  while(node&&node!==document.documentElement){"
      "    if(node.getAttribute&&node.getAttribute('data-pw-ref')===id)return true;"
      "    node=node.parentElement;"
      "  }"
      "  return false;"
      "}"
     ;; Fallback: check the element directly by its data-pw-ref attribute.
     ;; When elementFromPoint misses (e.g., a transparent nav fully covers
     ;; a logo), verify the element itself is stylistically visible.
      "function directCheck(id){"
      "  var el=document.querySelector('[data-pw-ref=\"'+id+'\"]');"
      "  if(!el)return false;"
      "  var s=getComputedStyle(el);"
      "  return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity)>0;"
      "}"
      "checks.forEach(function(c){"
      "  for(var i=0;i<c.pts.length;i++){"
      "    if(probe(c.pts[i][0],c.pts[i][1],c.id)){"
      "      visible.push(c.id);return;"
      "    }"
      "  }"
     ;; All probes missed — try direct style check
      "  if(directCheck(c.id)){visible.push(c.id);}"
      "});"
      "return visible;"
      "})()")))

(defn check-visible-refs
  "Runs JavaScript in the page to determine which refs are truly visible.

   Two-phase check for each ref:
   1. Multi-point elementFromPoint (center + 4 inset corners) — pierces
      invisible overlays (opacity:0, visibility:hidden, pointer-events:none).
      Walks up from hit element checking for matching data-pw-ref.
   2. Fallback: direct style check — queries the element by data-pw-ref and
      verifies computed display/visibility/opacity. Catches elements under
      transparent containers (e.g., a logo under a transparent navbar).

   Returns a set of ref IDs that are actually visible."
  [^Page page refs]
  (if (empty? refs)
    #{}
    (let [result (page/evaluate page (build-visibility-check-js refs))]
      (set result))))

;; =============================================================================
;; JavaScript injection
;; =============================================================================

(defn- build-inject-js
  "Builds JavaScript that injects annotation overlays into the page DOM.

   Creates absolutely-positioned elements for each ref:
   - Bounding box (colored border + semi-transparent fill)
   - Compact label inside the box: \"e1 heading 768x45\"

   Labels are placed inside the top-left of the bounding box to avoid
   inter-element collisions. For very small elements (height < 16px)
   the label is placed to the right of the box instead.

   All elements get a data-spel-annotate attribute for cleanup.
   Refs should be pre-filtered to visible-only before calling this."
  [refs opts]
  (let [show-boxes  (get opts :show-boxes true)
        show-labels (get opts :show-badges true)
        show-dims   (get opts :show-dimensions true)
        items       (sort-by key refs)
        ;; Refs whose bbox contains another ref — badge goes top-right
        container-ids (if show-labels
                        (into #{}
                          (for [[id-a info-a] items
                                [id-b info-b] items
                                :when (not= id-a id-b)
                                :let [ba (:bbox info-a) bb (:bbox info-b)]
                                :when (and ba bb (bbox-contains? ba bb))]
                            id-a))
                        #{})]
    (str
      "(function() {"
      "  var sx = window.scrollX || 0;"
      "  var sy = window.scrollY || 0;"
      "  var container = document.createElement('div');"
      "  container.setAttribute('data-spel-annotate', 'root');"
      "  container.style.cssText = 'position:absolute;top:0;left:0;width:0;height:0;overflow:visible;z-index:2147483647;pointer-events:none;';"
      "  document.documentElement.appendChild(container);"
      (apply str
        (for [[ref-id info] items
              :let [{:keys [role bbox]} info
                    {:keys [width height]} bbox
                    width (double width) height (double height)
                    color (color-for-role role)]
              :when (and (pos? width) (pos? height))]
          (str
          ;; Find the actual DOM element and get its real position
            "  (function() {"
            "    var el = document.querySelector('[data-pw-ref=\"" ref-id "\"]');"
            "    if (!el) return;"
            "    var r = el.getBoundingClientRect();"
            "    var dx = r.left + sx, dy = r.top + sy;"
            "    var dw = r.width, dh = r.height;"
          ;; Bounding box at element's document position
            (when show-boxes
              (let [is-container (contains? container-ids ref-id)]
                (str
                  "    var box = document.createElement('div');"
                  "    box.setAttribute('data-spel-annotate', 'box');"
                  "    box.style.cssText = 'position:absolute;pointer-events:none;"
                  "border:1px solid " color ";"
                  (when-not is-container
                    (str "background:" color "80;"))
                  "box-sizing:border-box;';"
                  "    box.style.top = dy + 'px';"
                  "    box.style.left = dx + 'px';"
                  "    box.style.width = dw + 'px';"
                  "    box.style.height = dh + 'px';"
                  "    container.appendChild(box);")))
          ;; Compact label
            (when show-labels
              (let [label-text (str ref-id " " role
                                 (when show-dims
                                   " ' + Math.round(dw) + 'x' + Math.round(dh) + '"))
                    is-container (contains? container-ids ref-id)]
                (str
                  "    var lbl = document.createElement('div');"
                  "    lbl.setAttribute('data-spel-annotate', 'label');"
                  "    lbl.textContent = '" label-text "';"
                  "    lbl.style.cssText = 'position:absolute;pointer-events:none;"
                  "background:" color ";opacity:1;"
                  "color:#fff;font:bold 9px/1 sans-serif;padding:1px 3px;white-space:nowrap;';"
                  (if is-container
                    ;; Container labels: top-right (avoid overlapping child labels at top-left)
                    (str "    lbl.style.top = dy + 'px';"
                      "    lbl.style.left = (dx + dw) + 'px';"
                      "    lbl.style.transform = 'translateX(-100%)';")
                    ;; Leaf labels: top-left inside box, or right-side for tiny elements
                    (str "    if (dh >= 16) { lbl.style.top = dy + 'px'; lbl.style.left = dx + 'px'; }"
                      "    else { lbl.style.top = dy + 'px'; lbl.style.left = (dx + dw + 2) + 'px'; }"))
                  "    container.appendChild(lbl);")))
            "  })();")))
      "})();")))

;; =============================================================================
;; Scope filtering (restrict annotations to a DOM subtree)
;; =============================================================================

(defn- escape-js-string
  "Escapes single quotes and backslashes for embedding in JS string literal."
  [^String s]
  (-> s
    (str/replace "\\" "\\\\")
    (str/replace "'" "\\'")))

(defn- ref-scope?
  "Returns true if the scope string is a snapshot ref like @e1 or e1."
  [^String s]
  (boolean (re-matches #"@?e\d+" s)))

(defn- resolve-scope
  "Resolves a scope value to a CSS selector.

   If the scope is a ref (@e1, e1), converts to [data-pw-ref=\"e1\"].
   Otherwise, passes through as a CSS selector."
  [^String s]
  (if (ref-scope? s)
    (str "[data-pw-ref=\"" (str/replace s #"^@" "") "\"]")
    s))

(defn- scope-ref-ids
  "Returns a set of ref IDs whose elements are descendants of the scope selector.

   Scope can be a CSS selector or a snapshot ref (@e1, e1).
   Queries the DOM for all `data-pw-ref` elements inside the scoped element.
   Requires that `capture-snapshot` has already been called (elements tagged)."
  [^Page page ^String scope-selector]
  (let [css-sel (resolve-scope scope-selector)
        js (str "(function(){"
             "var scope=document.querySelector('" (escape-js-string css-sel) "');"
             "if(!scope)return [];"
             "var refs=[];"
             "scope.querySelectorAll('[data-pw-ref]').forEach(function(el){"
             "  refs.push(el.getAttribute('data-pw-ref'));"
             "});"
             "return refs;"
             "})()")
        result (page/evaluate page js)]
    (set result)))

(defn- apply-scope
  "Filters refs to only those within the scope selector's DOM subtree.

   When `scope-selector` is nil, returns refs unchanged."
  [^Page page scope-selector refs]
  (if scope-selector
    (let [scoped-ids (scope-ref-ids page scope-selector)]
      (select-keys refs scoped-ids))
    refs))

(def ^:private remove-overlays-js
  "document.querySelectorAll('[data-spel-annotate]').forEach(function(el){ el.remove(); });")

;; =============================================================================
;; Public API
;; =============================================================================

(defn inject-overlays!
  "Injects annotation overlays into the page DOM for visible elements only.

   Four-phase filtering pipeline:
   0. Scope filter: restrict to refs within a CSS selector's DOM subtree
   1. Annotation filter: skip structural roles + remove containers
   2. Clojure-side: bbox-in-viewport pre-filter (fast, no JS roundtrip)
   3. JS-side: elementFromPoint check at each center (detects occlusion,
      hidden CSS, aria-hidden, and verifies semantic role match)

   Params:
   `page` - Playwright Page instance.
   `refs` - Map from capture-snapshot. {\"e1\" {:role :name :bbox {:x :y :width :height}} ...}
   `opts` - Map, optional.
     :scope           - String. CSS selector or snapshot ref (@e1, e1) to restrict
                        annotations to a subtree. Only elements that are descendants
                        of the matched element will be annotated. Requires prior
                        snapshot (elements tagged with data-pw-ref).
     :show-dimensions - Boolean (default true). Show width x height in labels.
     :show-badges     - Boolean (default true). Show compact labels.
     :show-boxes      - Boolean (default true). Show bounding box outlines.

   Returns: count of annotated elements (long)."
  ([^Page page refs]
   (inject-overlays! page refs {}))
  ([^Page page refs opts]
   (let [;; Phase 0: scope filter (restrict to DOM subtree)
         scoped      (apply-scope page (:scope opts) refs)
         ;; Phase 1: filter to annotatable roles + remove containers
         annotatable (filter-annotatable scoped)
         ;; Phase 2: fast Clojure-side bbox filter
         vp          (page/viewport-size page)
         in-viewport (if vp (visible-refs vp annotatable) annotatable)
         ;; Phase 3: JS-side elementFromPoint occlusion check
         visible-ids (check-visible-refs page in-viewport)
         visible     (select-keys in-viewport visible-ids)]
     (when (seq visible)
       (page/evaluate page (build-inject-js visible opts)))
     (count visible))))

(defn remove-overlays!
  "Removes all annotation overlays from the page DOM.

   Returns: nil."
  [^Page page]
  (page/evaluate page remove-overlays-js)
  nil)

(defn annotated-screenshot
  "Takes a screenshot with annotation overlays (convenience function).

   Injects CSS overlays into the page, takes a screenshot, then removes them.
   Only annotates elements visible in the current viewport.
   No AWT dependency — everything is done in the browser.

   Params:
   `page` - Playwright Page instance.
   `refs` - Map from capture-snapshot.
    `opts` - Map, optional.
      :scope           - String. CSS selector or snapshot ref (@e1, e1) to restrict
                         annotations to a subtree.
      :show-dimensions - Boolean (default true). Show width x height in labels.
      :show-badges     - Boolean (default true). Show compact labels.
      :show-boxes      - Boolean (default true). Show bounding box outlines.
      :full-page       - Boolean (default false). Capture full scrollable page.

   Returns:
   byte[] of the annotated PNG."
  ([^Page page refs]
   (annotated-screenshot page refs {}))
  ([^Page page refs opts]
   (inject-overlays! page refs (dissoc opts :full-page))
   (try
     (page/screenshot page (cond-> {}
                             (:full-page opts) (assoc :full-page true)))
     (finally
       (remove-overlays! page)))))

(defn save-annotated-screenshot!
  "Takes an annotated screenshot and saves it to a file.

   Params:
   `page` - Playwright Page instance.
   `refs` - Map from capture-snapshot.
   `path` - String. File path for the output PNG.
   `opts` - Map, optional. Same as annotated-screenshot (supports :scope).

   Returns:
   nil."
  ([^Page page refs ^String path]
   (save-annotated-screenshot! page refs path {}))
  ([^Page page refs ^String path opts]
   (let [^bytes bytes (annotated-screenshot page refs opts)]
     (java.nio.file.Files/write
       (java.nio.file.Paths/get path (into-array String []))
       bytes
       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
     nil)))
