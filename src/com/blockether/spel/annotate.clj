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
   [com.blockether.spel.fonts :as fonts]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Page]))

;; =============================================================================
;; Brand palette (Blockether) — mirrors the ejected browser overlay
;; (resources/com/blockether/spel/browser/spel.js). Amber accent, ink chips,
;; JetBrains Mono, hard offset shadow, sharp corners, no animation.
;; =============================================================================

(def ^:private brand-amber "#ffc420")
(def ^:private brand-ink "#262626")
(def ^:private brand-charcoal "#3f3f3f")
(def ^:private brand-mono
  ;; Double-quote the family name: the generated cssText is a single-quoted JS
  ;; string, so a single-quoted 'JetBrains Mono' would close it and break the
  ;; injected script. Double quotes are valid CSS and safe inside 'single' JS.
  "\"JetBrains Mono\",ui-monospace,SFMono-Regular,Menlo,Consolas,monospace")

(def ^:private mark-palette
  "Outline/mark colours, cycled by entry number.

   One amber for every element is what made a dense page unreadable: 200 marks
   and 200 boxes in one colour say nothing about which mark belongs to which
   box. Set-of-Mark implementations colour each element and paint its label in
   that same colour, so the pairing is carried by hue instead of by proximity
   alone. Numbering is in reading order, so neighbours always differ.

   Every entry is dark enough to carry white text."
  ["#d7263d" "#1565c0" "#2e7d32" "#c2410c" "#6a1b9a"
   "#00796b" "#ad1457" "#4527a0" "#8d6e00" "#374151"])

;; =============================================================================
;; Annotation filtering (reduce clutter by skipping structural elements)
;; =============================================================================

(def ^:private actionable-roles
  "Roles that are rendered as annotation overlays by default.

   What an agent can ACT on, plus the two landmarks it steers by (heading,
   img). Prose is deliberately absent: on a news page every link is also a
   text node, so marking both drew two boxes and two numbers per row and the
   picture stopped answering \"what can I click\". The words are already in the
   snapshot; the overlay is the action layer. Pass :show-text to add them back."
  #{"button" "link" "textbox" "searchbox" "combobox" "checkbox" "radio"
    "switch" "slider" "spinbutton" "menuitem" "menuitemcheckbox"
    "menuitemradio" "option" "treeitem" "tab"
    "heading" "img" "dialog" "alertdialog" "progressbar"})

(def ^:private text-roles
  "Text containers, drawn only when :show-text is set."
  #{"paragraph" "p" "span" "listitem" "text" "navigation"})

(defn- annotatable-role?
  "Returns true if the role should be drawn as an annotation overlay."
  ([role] (annotatable-role? role false))
  ([role show-text?]
   (or (contains? actionable-roles role)
     (and show-text? (contains? text-roles role)))))

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
                  ;; `long` here is what keeps the comparisons below off boxed
                  ;; math; clj-kondo calls it redundant, the reflection check does not.
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
   1. Keep actionable roles only (plus text containers when `:show-text` is set)
   2. Remove containers whose bbox fully wraps a smaller ref

   Returns a subset of refs suitable for `build-inject-js`."
  ([refs] (filter-annotatable refs {}))
  ([refs opts]
   (let [show-text? (boolean (:show-text opts))]
     (-> (into {}
           (filter (fn [[_ info]] (annotatable-role? (:role info) show-text?)))
           refs)
       remove-containers))))

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

(defn refs->entries
  "Converts a refs map to a sorted, numbered list of entry maps.

   Entries are sorted top→down, left→right (by bbox y then x) for natural
   reading order and then numbered from 1. Each entry has :mark, :ref, :role,
   :name, :bbox.

   The :mark is the whole overlay label — a one- or two-character number that
   fits inside a 13×13 checkbox. Everything else the reader needs about the
   element lives in this table, which is the only thing mapping a drawn mark
   back to a ref.

   Used by build-inject-js, inject-overlays! and helpers/overview! to produce a
   deterministic, LLM-friendly listing of annotated elements."
  [refs]
  (->> refs
    (map (fn [[ref-id info]]
           {:ref  ref-id
            :role (:role info)
            :name (:name info)
            :bbox (:bbox info)}))
    (sort-by (fn [{:keys [bbox]}]
               [(double (or (:y bbox) 0.0))
                (double (or (:x bbox) 0.0))]))
    (map-indexed (fn [idx entry] (assoc entry :mark (inc (long idx)))))
    vec))

(defn- build-inject-js
  "Builds JavaScript that injects annotation overlays into the page DOM.

   Creates absolutely-positioned elements for each ref:
   - Bounding box: a 2px outline, no fill, so the content under it stays legible
   - Mark: that entry's number from `refs->entries`, and nothing else

   The mark is deliberately tiny. A badge that spells the ref id, the role and
   the size is wider than most controls, so on a form it covers the very labels
   the screenshot was taken to show. Set-of-Mark implementations paint a bare
   index for that reason; identity, role and name are carried by the entry table
   the caller prints beside the image.

   A mark and its box share one colour from `mark-palette`, and the mark is
   always placed in a slot TOUCHING its box — above, below or beside it, or in a
   corner inside when the box can spare one. Chasing whitespace across the row
   produced marks that overlapped nothing and pointed at nothing; on a dense
   page an unattached number is less readable than a slightly crowded one. So
   candidates are scored instead of accepted or refused: overlapping a mark
   already placed costs six times overlapping the page's own words, drift is
   charged per pixel and capped at two mark widths, and the cheapest slot wins.

   All elements get a data-spel-annotate attribute for cleanup.
   Refs should be pre-filtered to visible-only before calling this."
  [refs opts]
  (let [show-boxes (get opts :show-boxes true)
        show-marks (get opts :show-badges true)
        show-dims  (get opts :show-dimensions false)]
    (str
      "(function() {"
      "  var sx = window.scrollX || 0;"
      "  var sy = window.scrollY || 0;"
      "  var de = document.documentElement;"
      "  var docW = Math.max(de.scrollWidth, de.clientWidth);"
      "  var docH = Math.max(de.scrollHeight, de.clientHeight);"
      "  var container = document.createElement('div');"
      "  container.setAttribute('data-spel-annotate', 'root');"
      "  container.style.cssText = 'position:absolute;top:0;left:0;width:0;height:0;overflow:visible;z-index:2147483647;pointer-events:none;';"
      "  document.documentElement.appendChild(container);"
      "  function overlap(a, b) {"
      "    var w = Math.min(a.r, b.r) - Math.max(a.l, b.l);"
      "    var h = Math.min(a.b, b.b) - Math.max(a.t, b.t);"
      "    return (w > 0 && h > 0) ? w * h : 0;"
      "  }"
      ;; Occupied mark rectangles, in placement order.
      "  var taken = [];"
      ;; A mark should not sit on top of a word, and `taken` only knows about
      ;; other marks. So measure the page's own words ONCE, in document
      ;; coordinates: a Range around each text node gives a line's box rather
      ;; than its block's, which is the difference between \"this paragraph is
      ;; busy\" and \"these 40 pixels are\". Hit-testing with elementFromPoint
      ;; would only see the viewport; the whole page is annotated.
      "  var texts = [];"
      "  (function() {"
      "    var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;"
      "    while ((n = tw.nextNode())) {"
      "      if (!n.nodeValue || !n.nodeValue.trim()) continue;"
      "      var rg = document.createRange();"
      "      rg.selectNode(n);"
      "      var rs = rg.getClientRects();"
      "      for (var i = 0; i < rs.length; i++) {"
      "        var q = rs[i];"
      "        if (q.width > 0 && q.height > 0) texts.push({l: q.left + sx, t: q.top + sy, r: q.right + sx, b: q.bottom + sy});"
      "      }"
      "    }"
      "  })();"
      ;; Bucketed by row so a long article costs a row scan, not a full scan.
      "  var BUCKET = 64, byRow = {};"
      "  for (var ti = 0; ti < texts.length; ti++) {"
      "    var q = texts[ti];"
      "    for (var rb = Math.floor(q.t / BUCKET); rb <= Math.floor(q.b / BUCKET); rb++) {"
      "      (byRow[rb] = byRow[rb] || []).push(q);"
      "    }"
      "  }"
      "  function textCost(r) {"
      "    var c = 0;"
      "    for (var rb = Math.floor(r.t / BUCKET); rb <= Math.floor(r.b / BUCKET); rb++) {"
      "      var list = byRow[rb];"
      "      if (!list) continue;"
      "      for (var i = 0; i < list.length; i++) c += overlap(r, list[i]);"
      "    }"
      "    return c;"
      "  }"
      ;; Covering a NEIGHBOUR is what makes an annotated page unreadable; covering
      ;; the TAIL of the element the mark NAMES is not, because the reader already
      ;; has that row in the table and the words that identify a control are its
      ;; first ones. So own text is discounted almost to nothing EXCEPT over the
      ;; element's head, where a number on the first letters is the very thing the
      ;; marks were accused of: `Story number 12` read as `12ory number 12`.
      "  function markCost(r, own) {"
      "    var hw = Math.min(60, Math.max(12, (own.r - own.l) * 0.3));"
      "    var head = {l: own.l, t: own.t, r: Math.min(own.r, own.l + hw), b: own.b};"
      "    var onHead = overlap(r, head);"
      "    var c = textCost(r) - 0.95 * Math.max(0, overlap(r, own) - onHead) + 2 * onHead;"
      "    if (c < 0) c = 0;"
      "    for (var i = 0; i < taken.length; i++) c += 6 * overlap(r, taken[i]);"
      "    return c;"
      "  }"
      ;; Measure the mark in the DOM, then score slots by what they cost.
      "  function placeMark(mark, x, y, w, h) {"
      "    container.appendChild(mark);"
      "    var mw = mark.offsetWidth, mh = mark.offsetHeight;"
      ;; Candidates are [left, top, drift]: drift is how far the slot sits from
      ;; the element it names. A number that wandered into the page margin names
      ;; nothing the reader can find, so the search is SHORT — every slot touches
      ;; the box or sits within two mark widths of it, and drift is expensive.
      ;; Outside first, above before beside, because a mark dropped at a box's
      ;; top-left eats the first letters of the word it points at.
      "    var cands = [[x, y - mh, 0], [x + w - mw, y - mh, 0], [x - mw, y, 0], [x + w, y, 0],"
      "                 [x, y + h, 0], [x + w - mw, y + h, 0], [x - mw, y - mh, 0], [x + w, y - mh, 0],"
      "                 [x - mw, y + h - mh, 0], [x + w, y + h - mh, 0]];"
      "    if (w >= mw + 4 && h >= mh + 4) { cands.push([x + w - mw - 1, y + 1, 0]); cands.push([x + 1, y + 1, 0]); }"
      "    cands.push([x, y, 0]);"
      ;; A dense row (a list of small links) can leave every touching slot on ink.
      ;; Then a step ALONG the row is allowed, and two forces decide how far. Drift
      ;; is priced per pixel against the pixels of type the slot would bury, so a
      ;; number crosses a gutter to reach clear paper and refuses to wander when
      ;; the paper it would reach is inked anyway. And the walk is bounded by the
      ;; element's OWN width: a row-wide link owns the white space beside it, while
      ;; a 40px button keeps its number within a mark or two — a number that drifts
      ;; further than its element is wide has started naming its neighbour.
      "    var maxD = Math.min(160, Math.max(2 * (mw + 2), w));"
      "    for (var band = 0; band < 3; band++) {"
      "      var by = [y, y - mh, y + h][band];"
      "      for (var step = 1; step <= 10; step++) {"
      "        var d = step * (mw + 2);"
      "        if (d > maxD) break;"
      "        cands.push([x + w + d, by, d]);"
      "        cands.push([x - mw - d, by, d]);"
      "      }"
      "    }"
      "    var spot = null, best = Infinity;"
      "    for (var i = 0; i < cands.length; i++) {"
      "      var cx = Math.min(Math.max(cands[i][0], 0), Math.max(0, docW - mw));"
      "      var cy = Math.min(Math.max(cands[i][1], 0), Math.max(0, docH - mh));"
      "      var r = {l: cx, t: cy, r: cx + mw, b: cy + mh};"
      ;; Ties go to the earlier (more natural) slot; that bias is far below the
      ;; area of any real overlap.
      "      var c = markCost(r, {l: x, t: y, r: x + w, b: y + h}) + cands[i][2] * 0.45 + i * 0.05;"
      "      if (c < best) { best = c; spot = r; }"
      "      if (best === 0) break;"
      "    }"
      "    mark.style.left = spot.l + 'px';"
      "    mark.style.top = spot.t + 'px';"
      "    taken.push(spot);"
      "  }"
      (apply str
        (for [{:keys [ref mark bbox]} (refs->entries refs)
              :let [{:keys [width height]} bbox
                    color (nth mark-palette (mod (dec (long mark)) (count mark-palette)))]
              :when (and (pos? (double width)) (pos? (double height)))]
          (str
            "  (function() {"
            "    var el = document.querySelector('[data-pw-ref=\"" ref "\"]');"
            "    if (!el) return;"
            "    var r = el.getBoundingClientRect();"
            "    var x = r.left + sx, y = r.top + sy, w = r.width, h = r.height;"
            (when show-boxes
              (str
                "    var box = document.createElement('div');"
                "    box.setAttribute('data-spel-annotate', 'box');"
                "    box.setAttribute('data-spel-ref', '" ref "');"
                "    var bw = (w < 48 || h < 24) ? 1 : 2;"
                "    box.style.cssText = 'position:absolute;pointer-events:none;box-sizing:border-box;border:' + bw + 'px solid " color ";';"
                "    box.style.top = y + 'px';"
                "    box.style.left = x + 'px';"
                "    box.style.width = w + 'px';"
                "    box.style.height = h + 'px';"
                "    container.appendChild(box);"))
            (when show-marks
              (str
                "    var mark = document.createElement('div');"
                "    mark.setAttribute('data-spel-annotate', 'mark');"
                "    mark.setAttribute('data-spel-ref', '" ref "');"
                "    mark.textContent = '" mark "'"
                (when show-dims " + ' ' + Math.round(w) + 'x' + Math.round(h)")
                ";"
                ;; The white ring keeps the number readable when the cheapest
                ;; slot still lands on ink.
                "    mark.style.cssText = 'position:absolute;pointer-events:none;background:" color
                ";color:#ffffff;font:700 10px/12px " brand-mono
                ";padding:0 1px;white-space:nowrap;box-shadow:0 0 0 1px rgba(255,255,255,0.95);';"
                "    placeMark(mark, x, y, w, h);"))
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
  "Returns true if the scope string is a snapshot ref (must start with @, e.g. @e04a3f)."
  [^String s]
  (boolean (re-matches #"@e[a-z0-9]+" s)))

(defn- resolve-scope
  "Resolves a scope value to a CSS selector.

   If the scope is a ref (@e2yrjz), converts to [data-pw-ref='e2yrjz'].
   Otherwise, passes through as a CSS selector."
  [^String s]
  (if (ref-scope? s)
    (str "[data-pw-ref=\"" (str/replace s #"^@" "") "\"]")
    s))

(defn- scope-ref-ids
  "Returns a set of ref IDs whose elements are descendants of the scope selector.

   Scope can be a CSS selector or a snapshot ref (@e2yrjz).
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
   `refs` - Map from capture-snapshot. {'e2yrjz' {:role :name :bbox {:x :y :width :height}} ...}
    `opts` - Map, optional.
      :scope           - String. CSS selector or snapshot ref (@e2yrjz, e2yrjz) to restrict
                         annotations to a subtree. Only elements that are descendants
                         of the matched element will be annotated. Requires prior
                         snapshot (elements tagged with data-pw-ref).
      :full-page       - Boolean (default false). Annotate all elements on the page,
                         not just those visible in the current viewport.
      :show-dimensions - Boolean (default false). Append width x height to each mark.
      :show-badges     - Boolean (default true). Draw the mark numbers.
      :show-boxes      - Boolean (default true). Show bounding box outlines.

    Returns: {:count N :entries [{:ref :role :name :bbox} ...]} where entries
    are sorted top→down, left→right (natural reading order). The :ref field is
    the bare ref ID (no @ prefix). An empty result is {:count 0 :entries []}."
  ([^Page page refs]
   (inject-overlays! page refs {}))
  ([^Page page refs opts]
   (let [;; Phase 0: scope filter (restrict to DOM subtree)
         scoped      (apply-scope page (:scope opts) refs)
          ;; Phase 1: filter to annotatable roles + remove containers
         annotatable (filter-annotatable scoped opts)
          ;; Phase 2: fast Clojure-side bbox filter (skip when :full-page)
         vp          (when-not (:full-page opts) (page/viewport-size page))
         in-viewport (if vp (visible-refs vp annotatable) annotatable)
          ;; Phase 3: JS-side elementFromPoint occlusion check
         visible-ids (check-visible-refs page in-viewport)
         visible     (select-keys in-viewport visible-ids)]
     (when (seq visible)
       (page/evaluate page (build-inject-js visible opts)))
     {:count   (count visible)
      :entries (refs->entries visible)})))

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
      :scope           - String. CSS selector or snapshot ref (@e2yrjz, e2yrjz) to restrict
                         annotations to a subtree.
      :show-dimensions - Boolean (default false). Append width x height to each mark.
      :show-badges     - Boolean (default true). Draw the mark numbers.
      :show-boxes      - Boolean (default true). Show bounding box outlines.
      :full-page       - Boolean (default false). Capture full scrollable page.

   Returns:
   {:bytes byte[] :annotated {:count N :entries [...]}}. The :annotated value
   is the same shape as inject-overlays! — a deterministic list of actually-
   drawn elements, sorted top→down, left→right."
  ([^Page page refs]
   (annotated-screenshot page refs {}))
  ([^Page page refs opts]
   (let [annotated (inject-overlays! page refs (dissoc opts :full-page))
         ^bytes ss (try
                     (page/screenshot page (cond-> {}
                                             (:full-page opts) (assoc :full-page true)))
                     (finally
                       (remove-overlays! page)))]
     {:bytes ss :annotated annotated})))

(defn save-annotated-screenshot!
  "Takes an annotated screenshot and saves it to a file.

   Params:
   `page` - Playwright Page instance.
   `refs` - Map from capture-snapshot.
   `path` - String. File path for the output PNG.
   `opts` - Map, optional. Same as annotated-screenshot (supports :scope).

   Returns:
   {:path path :size N :annotated {:count :entries}}."
  ([^Page page refs ^String path]
   (save-annotated-screenshot! page refs path {}))
  ([^Page page refs ^String path opts]
   (let [{:keys [^bytes bytes annotated]} (annotated-screenshot page refs opts)]
     (java.nio.file.Files/write
       (java.nio.file.Paths/get path (into-array String []))
       bytes
       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
     {:path      path
      :size      (alength bytes)
      :annotated annotated})))

;; =============================================================================
;; Pre-action markers (highlight specific refs before interactions)
;; =============================================================================

(def ^:private remove-action-markers-js
  "document.querySelectorAll('[data-spel-action-marker]').forEach(function(el){ el.remove(); });")

(defn- build-action-marker-js
  "Builds JavaScript that injects prominent action markers on specific refs.

   Each marker consists of:
   - A heavier amber border (2.5px solid, Blockether brand)
   - A semi-transparent amber fill
   - A label '→ eN' at top-left identifying the target

   Markers use `data-spel-action-marker` (independent of annotation overlays)."
  [ref-ids]
  (let [items (for [ref-id ref-ids]
                (str "{id:'" ref-id "'}"))]
    (str
      "(function(){"
      ;; No animation — matches the ejected overlay's static brand style.
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
      "border:2.5px solid " brand-amber ";background:rgba(255,196,32,0.14);box-sizing:border-box;';"
      "  mk.style.top=(r.top+sy)+'px';"
      "  mk.style.left=(r.left+sx)+'px';"
      "  mk.style.width=r.width+'px';"
      "  mk.style.height=r.height+'px';"
      ;; Label
      "  var lbl=document.createElement('div');"
      "  lbl.setAttribute('data-spel-action-marker','label');"
      "  lbl.textContent='\\u2192 '+item.id;"
      "  lbl.style.cssText='position:absolute;top:-18px;left:0;background:" brand-ink ";color:" brand-amber ";"
      "font:700 10px/15px " brand-mono ";padding:0 5px;white-space:nowrap;box-shadow:1px 1px 0 0 " brand-charcoal ";"
      "pointer-events:none;';"
      "  mk.appendChild(lbl);"
      "  document.documentElement.appendChild(mk);"
      "  count++;"
      "});"
      "return count;"
      "})()")))

(defn inject-action-markers!
  "Injects prominent pre-action markers on specific snapshot refs.

   Markers are visually distinct from annotation overlays: a heavier amber
   (Blockether-brand) border with a '→ eN' label. Used to highlight elements before
   interacting with them, making screenshots self-documenting.

   Markers use `data-spel-action-marker` attribute and are independent of
   annotation overlays (`data-spel-annotate`).

   Params:
   `page`    - Playwright Page instance.
   `ref-ids` - Collection of ref ID strings (e.g. ['@e2yrjz' '@e9mter']).
               The @ prefix is stripped for DOM lookup.

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
     :markers   - Collection of ref IDs to mark (e.g. ['ea3kf5']). Action markers are included.
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
    ":root{"
    "--font-body:" fonts/body-stack ";"
    "--font-heading:" fonts/body-stack ";"
    "--font-mono:" fonts/mono-stack ";"
    "--bg:#f6f1e8;--bg-secondary:rgba(255,251,245,0.88);"
    "--surface:rgba(255,255,255,0.94);--surface-elevated:rgba(255,255,255,0.94);"
    "--border:rgba(125,99,68,0.18);--border-bright:rgba(125,99,68,0.34);"
    "--text:#1f2933;--text-dim:#55606e;"
    "--accent:#b2652a;--accent-dim:rgba(178,101,42,0.12);"
    "--node-a:#b2652a;--node-a-dim:rgba(178,101,42,0.12);"
    "--node-b:#1f8a5c;--node-b-dim:rgba(31,138,92,0.12);"
    "--node-c:#0f766e;--node-c-dim:rgba(15,118,110,0.12);"
    "--node-d:#b7791f;--node-d-dim:rgba(183,121,31,0.12);"
    "--node-e:#c44536;--node-e-dim:rgba(196,69,54,0.12);"
    "--shadow:0 18px 42px rgba(43,33,22,0.08);"
    "--shadow-soft:0 10px 24px rgba(43,33,22,0.05);"
    "--radius-lg:24px;--radius-md:18px;--radius-sm:10px;}"
    "@media (prefers-color-scheme: dark){:root{"
    "--bg:#151a20;--bg-secondary:rgba(26,32,40,0.88);"
    "--surface:rgba(24,30,38,0.96);--surface-elevated:rgba(24,30,38,0.96);"
    "--border:rgba(255,255,255,0.1);--border-bright:rgba(255,255,255,0.18);"
    "--text:#ecf1f7;--text-dim:#a9b7c8;"
    "--accent-dim:rgba(178,101,42,0.14);--node-a-dim:rgba(178,101,42,0.14);"
    "--node-b-dim:rgba(31,138,92,0.14);--node-c-dim:rgba(15,118,110,0.14);"
    "--node-d-dim:rgba(183,121,31,0.14);--node-e-dim:rgba(196,69,54,0.14);"
    "--shadow:0 22px 48px rgba(0,0,0,0.32);--shadow-soft:0 12px 28px rgba(0,0,0,0.24);}}"
    "body{margin:0;max-width:900px;margin:0 auto;padding:20px;font-family:var(--font-body);"
    "font-size:15px;line-height:1.7;color:var(--text);"
    "background:radial-gradient(circle at top left,rgba(178,101,42,0.12),transparent 30%),"
    "radial-gradient(circle at top right,rgba(15,118,110,0.10),transparent 28%),"
    "linear-gradient(180deg,#fbf7f1 0%,var(--bg) 48%,#efe7dc 100%);"
    "min-height:100vh;-webkit-font-smoothing:antialiased;overflow-wrap:break-word;}"
    "@media (prefers-color-scheme: dark){body{"
    "background:radial-gradient(circle at top left,rgba(178,101,42,0.14),transparent 24%),"
    "radial-gradient(circle at top right,rgba(15,118,110,0.15),transparent 22%),"
    "linear-gradient(180deg,#12171d 0%,#151a20 52%,#1a212a 100%);}}"
    "h1,h2,h3,h4{font-family:var(--font-heading);font-weight:800;color:var(--text);line-height:1.15;}"
    "h1{font-size:clamp(2rem,5vw,3.35rem);letter-spacing:-0.03em;margin-bottom:1rem;}"
    "h2{font-size:1.6rem;margin-top:40px;margin-bottom:1rem;letter-spacing:-0.03em;}"
    "h3{font-size:1.15rem;margin-top:25px;margin-bottom:0.75rem;letter-spacing:-0.02em;}"
    "h4{font-size:1rem;margin-bottom:0.5rem;}"
    ".screenshot{width:100%;margin:12px 0;border:1px solid var(--border);"
    "border-radius:var(--radius-md);box-shadow:var(--shadow-soft);}"
    ".caption{font-family:var(--font-mono);font-size:12px;color:var(--text-dim);"
    "margin-top:-6px;margin-bottom:16px;letter-spacing:0.03em;}"
    ".observation,.issue,.good{background:var(--surface);border:1px solid var(--border);"
    "border-radius:var(--radius-md);padding:12px 16px;margin:12px 0;"
    "box-shadow:var(--shadow-soft);backdrop-filter:blur(10px);}"
    ".observation{border-left:4px solid var(--node-c);background:var(--node-c-dim);}"
    ".issue{border-left:4px solid var(--node-d);background:var(--node-d-dim);}"
    ".good{border-left:4px solid var(--node-b);background:var(--node-b-dim);}"
    "table,.data-table{width:100%;border-collapse:collapse;font-size:13px;line-height:1.5;margin:12px 0;}"
    "table thead,.data-table thead{position:sticky;top:0;z-index:2;}"
    "table th,.data-table th{background:var(--surface-elevated);font-family:var(--font-mono);"
    "font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:1px;color:var(--text-dim);"
    "text-align:left;padding:12px 16px;border-bottom:2px solid var(--border-bright);white-space:nowrap;}"
    "table td,.data-table td{padding:12px 16px;border-bottom:1px solid var(--border);"
    "vertical-align:top;color:var(--text);}"
    "table tbody tr:nth-child(even),.data-table tbody tr:nth-child(even){background:var(--accent-dim);}"
    "table tbody tr:hover,.data-table tbody tr:hover{background:rgba(178,101,42,0.08);}"
    "table tbody tr:last-child td,.data-table tbody tr:last-child td{border-bottom:none;}"
    "code{font-family:var(--font-mono);background:var(--surface-elevated);border:1px solid var(--border);"
    "padding:2px 6px;border-radius:var(--radius-sm);font-size:0.9em;}"
    ".page-break{page-break-before:always;}"
    ".meta{font-family:var(--font-mono);font-size:12px;color:var(--text-dim);margin-bottom:20px;}"
    ".meta strong{color:var(--text);font-weight:600;}"
    "ul{margin:6px 0;padding-left:24px;}li{margin:2px 0;}"
    "hr{border:none;border-top:1px solid var(--border);margin:30px 0;}"
    "@media (max-width:768px){body{padding:16px;}}"
    "@media (prefers-reduced-motion: reduce){*,*::before,*::after{"
    "animation-duration:0.01ms !important;transition-duration:0.01ms !important;}}"))

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
    ;; Fonts are BUNDLED as base64 woff2, never linked from a CDN: this HTML
    ;; is also fed to Chromium's page.pdf(), and a network fetch there means a
    ;; PDF whose typography depends on whether the renderer had internet.
    (fonts/style-tag)
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
