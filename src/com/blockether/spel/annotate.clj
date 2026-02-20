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
      :full-page       - Boolean (default false). Annotate all elements on the page,
                         not just those visible in the current viewport.
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
          ;; Phase 2: fast Clojure-side bbox filter (skip when :full-page)
         vp          (when-not (:full-page opts) (page/viewport-size page))
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

;; =============================================================================
;; Pre-action markers (highlight specific refs before interactions)
;; =============================================================================

(def ^:private remove-action-markers-js
  "document.querySelectorAll('[data-spel-action-marker]').forEach(function(el){ el.remove(); });
   var ss = document.getElementById('spel-marker-style'); if(ss) ss.remove();")

(defn- build-action-marker-js
  "Builds JavaScript that injects prominent action markers on specific refs.

   Each marker consists of:
   - A pulsing red/orange border (3px solid, CSS animation)
   - A semi-transparent red fill
   - A label '→ eN' at top-left identifying the target

   Markers use `data-spel-action-marker` (independent of annotation overlays)."
  [ref-ids]
  (let [items (for [ref-id ref-ids]
                (str "{id:'" ref-id "'}"))]
    (str
      "(function(){"
      ;; Inject keyframe animation via <style> tag (once)
      "if(!document.getElementById('spel-marker-style')){"
      "  var style=document.createElement('style');"
      "  style.id='spel-marker-style';"
      "  style.textContent='@keyframes spel-pulse{0%,100%{border-color:#FF4444;box-shadow:0 0 8px rgba(255,68,68,0.6);}50%{border-color:#FF8800;box-shadow:0 0 12px rgba(255,136,0,0.8);}}';"
      "  document.head.appendChild(style);"
      "}"
      "var sx=window.scrollX||0,sy=window.scrollY||0;"
      "var items=[" (apply str (interpose "," items)) "];"
      "var count=0;"
      "items.forEach(function(item){"
      "  var el=document.querySelector('[data-pw-ref=\"'+item.id+'\"]');"
      "  if(!el)return;"
      "  var r=el.getBoundingClientRect();"
      "  if(r.width===0&&r.height===0)return;"
      ;; Container
      "  var mk=document.createElement('div');"
      "  mk.setAttribute('data-spel-action-marker','box');"
      "  mk.style.cssText='position:absolute;pointer-events:none;z-index:2147483646;"
      "border:3px solid #FF4444;background:rgba(255,68,68,0.12);box-sizing:border-box;"
      "animation:spel-pulse 2s ease-in-out infinite;';"
      "  mk.style.top=(r.top+sy)+'px';"
      "  mk.style.left=(r.left+sx)+'px';"
      "  mk.style.width=r.width+'px';"
      "  mk.style.height=r.height+'px';"
      ;; Label
      "  var lbl=document.createElement('div');"
      "  lbl.setAttribute('data-spel-action-marker','label');"
      "  lbl.textContent='\\u2192 '+item.id;"
      "  lbl.style.cssText='position:absolute;top:-18px;left:0;background:#FF4444;color:#fff;"
      "font:bold 10px/1 sans-serif;padding:2px 6px;white-space:nowrap;border-radius:2px;"
      "pointer-events:none;';"
      "  mk.appendChild(lbl);"
      "  document.documentElement.appendChild(mk);"
      "  count++;"
      "});"
      "return count;"
      "})()")))

(defn inject-action-markers!
  "Injects prominent pre-action markers on specific snapshot refs.

   Markers are visually distinct from annotation overlays: bright red/orange
   pulsing border with a '→ eN' label. Used to highlight elements before
   interacting with them, making screenshots self-documenting.

   Markers use `data-spel-action-marker` attribute and are independent of
   annotation overlays (`data-spel-annotate`).

   Params:
   `page`    - Playwright Page instance.
   `ref-ids` - Collection of ref ID strings (e.g. [\"e5\" \"e12\"]).
               Accepts both \"e5\" and \"@e5\" formats (@ is stripped).

   Returns:
   Count of successfully created markers (long)."
  [^Page page ref-ids]
  (let [clean-ids (mapv #(str/replace (str %) #"^@" "") ref-ids)]
    (if (empty? clean-ids)
      0
      (long (page/evaluate page (build-action-marker-js clean-ids))))))

(defn remove-action-markers!
  "Removes all pre-action markers from the page DOM.

   Returns: nil."
  [^Page page]
  (page/evaluate page remove-action-markers-js)
  nil)

;; =============================================================================
;; Audit screenshots (screenshot with caption overlay)
;; =============================================================================

(defn- build-caption-js
  "Builds JavaScript that injects a caption bar at the bottom of the viewport."
  [^String caption-text]
  (let [escaped (-> caption-text
                  (str/replace "\\" "\\\\")
                  (str/replace "'" "\\'")
                  (str/replace "\n" "\\n"))]
    (str
      "(function(){"
      "var bar=document.createElement('div');"
      "bar.setAttribute('data-spel-caption','bar');"
      "bar.textContent='" escaped "';"
      "bar.style.cssText='position:fixed;bottom:0;left:0;right:0;z-index:2147483647;"
      "background:rgba(0,0,0,0.85);color:#fff;font:bold 13px/1.4 -apple-system,sans-serif;"
      "padding:8px 16px;pointer-events:none;text-align:center;';"
      "document.documentElement.appendChild(bar);"
      "})()")))

(def ^:private remove-caption-js
  "document.querySelectorAll('[data-spel-caption]').forEach(function(el){ el.remove(); });")

(defn audit-screenshot
  "Takes a screenshot with an optional caption bar at the bottom.

   Can also include annotation overlays and/or action markers.
   The caption is injected as a fixed-position bar, captured in the
   screenshot, then removed — page state is not modified.

   Params:
   `page`    - Playwright Page instance.
   `caption` - String. Caption text to display at the bottom of the screenshot.
   `opts`    - Map, optional.
     :refs      - Snapshot refs map. When provided, annotation overlays are included.
     :markers   - Collection of ref IDs to mark (e.g. [\"e5\"]). Action markers are included.
     :full-page - Boolean (default false). Capture full scrollable page.

   Returns:
   byte[] of the PNG."
  ([^Page page ^String caption]
   (audit-screenshot page caption {}))
  ([^Page page ^String caption opts]
   (let [has-refs    (seq (:refs opts))
         has-markers (seq (:markers opts))]
     ;; Inject layers
     (when has-refs
       (inject-overlays! page (:refs opts) (dissoc opts :refs :markers :full-page)))
     (when has-markers
       (inject-action-markers! page (:markers opts)))
     (when (seq caption)
       (page/evaluate page (build-caption-js caption)))
     (try
       (page/screenshot page (cond-> {}
                               (:full-page opts) (assoc :full-page true)))
       (finally
         ;; Clean up all injected layers
         (when (seq caption)
           (page/evaluate page remove-caption-js))
         (when has-markers
           (remove-action-markers! page))
         (when has-refs
           (remove-overlays! page)))))))

(defn save-audit-screenshot!
  "Takes an audit screenshot and saves it to a file.

   Params:
   `page`    - Playwright Page instance.
   `caption` - String. Caption text for the screenshot.
   `path`    - String. File path for the output PNG.
   `opts`    - Map, optional. Same as audit-screenshot.

   Returns: nil."
  ([^Page page ^String caption ^String path]
   (save-audit-screenshot! page caption path {}))
  ([^Page page ^String caption ^String path opts]
   (let [^bytes bytes (audit-screenshot page caption opts)]
     (java.nio.file.Files/write
       (java.nio.file.Paths/get path (into-array String []))
       bytes
       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
     nil)))

;; =============================================================================
;; Report Builder — Polymorphic HTML/PDF from typed entries
;; =============================================================================

(def ^:private report-css
  "CSS styles for the report HTML document.
   Designed for both browser viewing and Chromium's page.pdf() renderer."
  (str
    "body{margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;"
    "max-width:900px;margin:0 auto;padding:20px;color:#1a1a1a;line-height:1.6;}"
    "h1{color:#2d2d2d;border-bottom:3px solid #4CAF50;padding-bottom:10px;font-size:22px;}"
    "h2{color:#333;border-bottom:1px solid #ddd;padding-bottom:6px;margin-top:40px;font-size:18px;}"
    "h3{color:#555;margin-top:25px;font-size:15px;}"
    ".screenshot{width:100%;border:1px solid #ddd;border-radius:4px;margin:12px 0;"
    "box-shadow:0 2px 4px rgba(0,0,0,0.1);}"
    ".caption{color:#666;font-size:0.9em;font-style:italic;margin-top:-8px;margin-bottom:16px;}"
    ".observation{background:#f8f9fa;border-left:4px solid #2196F3;padding:12px 16px;"
    "margin:12px 0;border-radius:0 4px 4px 0;}"
    ".issue{background:#fff3cd;border-left:4px solid #FF9800;padding:12px 16px;"
    "margin:12px 0;border-radius:0 4px 4px 0;}"
    ".good{background:#d4edda;border-left:4px solid #4CAF50;padding:12px 16px;"
    "margin:12px 0;border-radius:0 4px 4px 0;}"
    "table{border-collapse:collapse;width:100%;margin:12px 0;}"
    "th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;}"
    "th{background:#f5f5f5;font-weight:600;}"
    "code{background:#f0f0f0;padding:2px 6px;border-radius:3px;font-size:0.9em;}"
    ".page-break{page-break-before:always;}"
    ".meta{color:#555;font-size:0.95em;margin-bottom:20px;}"
    ".meta strong{color:#333;}"
    "ul{margin:6px 0;padding-left:24px;}"
    "li{margin:2px 0;}"
    "hr{border:none;border-top:1px solid #ddd;margin:30px 0;}"))

(defn- escape-html
  "Escapes HTML special characters in a string."
  [^String s]
  (when s
    (-> s
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;"))))

(defn- encode-b64
  "Base64-encodes a byte array to a string."
  [^bytes bs]
  (.encodeToString (java.util.Base64/getEncoder) bs))

(defn- render-items
  "Renders an optional :items sequence as a <ul> list."
  [items]
  (when (seq items)
    (str "<ul>"
      (apply str (map #(str "<li>" (escape-html (str %)) "</li>") items))
      "</ul>")))

(defn- render-entry
  "Renders a single report entry to HTML based on its :type.

   Supported types:
   :screenshot  — {:image byte[] :caption str}
   :section     — {:text str :level int :page-break bool}
   :observation — {:text str :items [str...]}
   :issue       — {:text str :items [str...]}
   :good        — {:text str :items [str...]}
   :table       — {:headers [str...] :rows [[str...]...]}
   :meta        — {:fields [[label value]...]}
   :text        — {:text str}
   :html        — {:content str}  (raw HTML, no escaping)"
  [entry idx]
  (case (:type entry)
    :screenshot
    (let [img (:image entry)
          caption (:caption entry)]
      (str (when (:page-break entry) "<div class='page-break'></div>")
        "<img class='screenshot' src='data:image/png;base64," (encode-b64 img)
        "' alt='Screenshot " (inc (long idx)) "'/>"
        (when caption
          (str "<p class='caption'>" (escape-html caption) "</p>"))))

    :section
    (let [level (long (min 3 (max 1 (long (or (:level entry) 2)))))
          tag   (str "h" level)]
      (str (when (:page-break entry) "<div class='page-break'></div>")
        "<" tag ">" (escape-html (:text entry)) "</" tag ">"))

    :observation
    (str "<div class='observation'>"
      (when-let [t (:text entry)]
        (str "<strong>" (escape-html t) "</strong>"))
      (render-items (:items entry))
      "</div>")

    :issue
    (str "<div class='issue'>"
      (when-let [t (:text entry)]
        (str "<strong>" (escape-html t) "</strong>"))
      (render-items (:items entry))
      "</div>")

    :good
    (str "<div class='good'>"
      (when-let [t (:text entry)]
        (str "<strong>" (escape-html t) "</strong>"))
      (render-items (:items entry))
      "</div>")

    :table
    (let [{:keys [headers rows]} entry]
      (str "<table>"
        (when (seq headers)
          (str "<tr>"
            (apply str (map #(str "<th>" (escape-html (str %)) "</th>") headers))
            "</tr>"))
        (apply str
          (map (fn [row]
                 (str "<tr>"
                   (apply str (map #(str "<td>" (escape-html (str %)) "</td>") row))
                   "</tr>"))
            rows))
        "</table>"))

    :meta
    (str "<div class='meta'>"
      (apply str
        (map (fn [[label value]]
               (str "<strong>" (escape-html (str label)) ":</strong> "
                 (escape-html (str value)) "<br>"))
          (:fields entry)))
      "</div>")

    :text
    (str "<p>" (escape-html (:text entry)) "</p>")

    :html
    (str (:content entry))

    ;; Unknown type — throw
    (throw (ex-info (str "Unknown report entry type: " (pr-str (:type entry))
                      ". Supported: :screenshot :section :observation :issue :good :table :meta :text :html")
             {:entry entry}))))

(defn- build-report-html
  "Builds an HTML document from a sequence of typed report entries.

   Each entry is a map with a :type key that determines rendering.
   See `render-entry` for supported types.

   The HTML is designed for both browser viewing and Chromium's page.pdf()."
  [entries title]
  (str
    "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
    "<title>" (escape-html (or title "Report")) "</title>"
    "<style>" report-css "</style>"
    "</head><body>"
    (when title
      (str "<h1>" (escape-html title) "</h1>"))
    (apply str (map-indexed (fn [i entry] (render-entry entry i)) entries))
    "<hr><p><em>Generated by spel</em></p>"
    "</body></html>"))

(defn report->html
  "Builds a rich HTML report from a sequence of typed entries.

   Each entry is a map with a :type key that determines rendering:

   :screenshot  — {:type :screenshot :image byte[] :caption str :page-break bool}
   :section     — {:type :section :text str :level (1|2|3) :page-break bool}
   :observation — {:type :observation :text str :items [str...]}
   :issue       — {:type :issue :text str :items [str...]}
   :good        — {:type :good :text str :items [str...]}
   :table       — {:type :table :headers [str...] :rows [[str...]...]}
   :meta        — {:type :meta :fields [[label value]...]}
   :text        — {:type :text :text str}
   :html        — {:type :html :content str}  (raw HTML, no escaping)

   Params:
   `entries` - Sequence of typed entry maps.
   `opts`    - Map, optional.
     :title  - String. Document title and h1 heading.

   Returns:
   String of the HTML document."
  ([entries]
   (report->html entries {}))
  ([entries opts]
   (build-report-html entries (:title opts))))

(defn report->pdf
  "Renders a rich HTML report to PDF via Playwright's page.pdf().

   Same entry types as `report->html`. Requires a Chromium headless page.

   Params:
   `page`    - Playwright Page instance (Chromium headless only).
   `entries` - Sequence of typed entry maps (see `report->html`).
   `opts`    - Map, optional.
     :title  - String. Document title and h1 heading.
     :path   - String. Output file path. If nil, returns byte[].
     :format - String. Page format (default \"A4\").
     :margin - Map with :top :bottom :left :right (default 20px each).

   Returns:
   byte[] of the PDF, or nil if :path was provided."
  ([^Page page entries]
   (report->pdf page entries {}))
  ([^Page page entries opts]
   (let [html     (build-report-html entries (:title opts))
         old-url  (page/url page)
         pdf-opts (cond-> {:format (or (:format opts) "A4")
                           :print-background true
                           :margin (or (:margin opts)
                                     {:top "20px" :bottom "20px"
                                      :left "20px" :right "20px"})}
                    (:path opts) (assoc :path (:path opts)))]
     ;; Load the HTML into the page, render PDF, then restore
     (page/set-content! page html)
     (page/wait-for-load-state page)
     (let [result (page/pdf page pdf-opts)]
       ;; Restore previous page state
       (when (and old-url (not= old-url "about:blank"))
         (try (page/navigate page old-url) (catch Exception _)))
       (if (:path opts)
         nil
         result)))))
