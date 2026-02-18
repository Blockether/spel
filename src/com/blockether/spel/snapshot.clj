(ns com.blockether.spel.snapshot
  "Accessibility snapshot with numbered element refs.

   Walks the DOM tree via JavaScript injection and builds a YAML-like
   accessibility tree with stable refs (e1, e2, f1_e1 for iframes).
   Elements are tagged with `data-pw-ref` attributes for later interaction.

   Usage:
     (def snap (capture-snapshot page))
     (:tree snap)      ;; YAML-like string with [@eN] annotations
     (:refs snap)      ;; {\"e1\" {:role \"button\" :name \"Submit\" :bbox {...}} ...}
     (resolve-ref page \"e3\") ;; returns Locator for the element"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Frame Page]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; JavaScript Injection
;; =============================================================================

(def ^:private js-capture-snapshot
  "JavaScript to walk DOM, compute ARIA roles + names, assign refs,
   and return the tree with bounding boxes.

   Uses Element.computedRole and Element.computedName (Chromium 93+)."
  "(() => {
  // Clean up any previous refs
  document.querySelectorAll('[data-pw-ref]').forEach(el => {
    el.removeAttribute('data-pw-ref');
  });

  let counter = 0;
  const refs = {};

  const SKIP_ROLES = new Set([
    'generic', 'presentation', 'none', ''
  ]);

  const MEANINGFUL_ROLES = new Set([
    'button', 'link', 'textbox', 'checkbox', 'radio', 'combobox',
    'listbox', 'menuitem', 'tab', 'switch', 'slider', 'spinbutton',
    'searchbox', 'option', 'menuitemcheckbox', 'menuitemradio',
    'treeitem', 'heading', 'img', 'navigation', 'main', 'banner',
    'contentinfo', 'complementary', 'form', 'search', 'region',
    'article', 'dialog', 'alertdialog', 'alert', 'status', 'log',
    'progressbar', 'table', 'row', 'cell', 'columnheader', 'rowheader',
    'list', 'listitem', 'separator', 'figure', 'group', 'toolbar',
    'tablist', 'tabpanel', 'menu', 'menubar', 'tree', 'treegrid',
    'grid', 'rowgroup', 'meter', 'math',
    'paragraph', 'span'
  ]);

  const INTERACTIVE_TAGS = new Set([
    'a', 'button', 'input', 'select', 'textarea', 'summary', 'details'
  ]);

  const TAG_FALLBACK_ROLES = {
    'a': 'link', 'button': 'button', 'select': 'combobox',
    'textarea': 'textbox', 'h1': 'heading', 'h2': 'heading',
    'h3': 'heading', 'h4': 'heading', 'h5': 'heading', 'h6': 'heading',
    'nav': 'navigation', 'main': 'main', 'header': 'banner',
    'footer': 'contentinfo', 'aside': 'complementary', 'form': 'form',
    'table': 'table', 'tr': 'row', 'td': 'cell', 'th': 'columnheader',
    'ul': 'list', 'ol': 'list', 'li': 'listitem', 'img': 'img',
    'dialog': 'dialog', 'progress': 'progressbar', 'meter': 'meter',
    'article': 'article', 'section': 'region', 'figure': 'figure',
    'hr': 'separator', 'menu': 'list', 'fieldset': 'group',
    'output': 'status', 'summary': 'button', 'details': 'group',
    'p': 'paragraph', 'span': 'span'
  };

  function getInputRole(el) {
    const type = (el.getAttribute('type') || 'text').toLowerCase();
    const map = {
      'button': 'button', 'checkbox': 'checkbox', 'email': 'textbox',
      'number': 'spinbutton', 'password': 'textbox', 'radio': 'radio',
      'range': 'slider', 'reset': 'button', 'search': 'searchbox',
      'submit': 'button', 'tel': 'textbox', 'text': 'textbox',
      'url': 'textbox'
    };
    return map[type] || 'textbox';
  }

  function getRole(el) {
    // 1. Explicit role attribute
    const explicit = el.getAttribute('role');
    if (explicit) return explicit;

    // 2. computedRole (Chromium 93+)
    if (typeof el.computedRole === 'string' && el.computedRole) {
      return el.computedRole;
    }

    // 3. Tag-based fallback
    const tag = el.tagName.toLowerCase();
    if (tag === 'input') return getInputRole(el);
    return TAG_FALLBACK_ROLES[tag] || '';
  }

  function getName(el) {
    // 1. aria-label
    const ariaLabel = el.getAttribute('aria-label');
    if (ariaLabel) return ariaLabel.trim();

    // 2. aria-labelledby
    const labelledBy = el.getAttribute('aria-labelledby');
    if (labelledBy) {
      const parts = labelledBy.split(/\\s+/).map(id => {
        const ref = document.getElementById(id);
        return ref ? (ref.textContent || '').trim() : '';
      }).filter(Boolean);
      if (parts.length) return parts.join(' ');
    }

    // 3. computedName (Chromium 93+)
    if (typeof el.computedName === 'string' && el.computedName) {
      return el.computedName.trim();
    }

    // 4. Label element (for inputs)
    if (el.labels && el.labels.length) {
      return (el.labels[0].textContent || '').trim();
    }

    // 5. Title, alt, placeholder, value
    const alt = el.getAttribute('alt');
    if (alt) return alt.trim();
    const title = el.getAttribute('title');
    if (title) return title.trim();
    const placeholder = el.getAttribute('placeholder');
    if (placeholder) return placeholder.trim();

    // 6. Leaf text for simple elements
    if (el.children.length === 0) {
      const text = (el.textContent || '').trim();
      if (text.length <= 200) return text;
      return text.substring(0, 197) + '...';
    }

    return '';
  }

  function isVisible(el) {
    if (el.getAttribute('aria-hidden') === 'true') return false;
    if (el.hidden) return false;
    const style = getComputedStyle(el);
    if (style.display === 'none') return false;
    if (style.visibility === 'hidden') return false;
    if (parseFloat(style.opacity) === 0) return false;
    return true;
  }

  function headingLevel(el) {
    const tag = el.tagName.toLowerCase();
    if (tag.match(/^h[1-6]$/)) return parseInt(tag[1]);
    const ariaLevel = el.getAttribute('aria-level');
    if (ariaLevel) return parseInt(ariaLevel);
    return null;
  }

  function shouldAssignRef(el, role) {
    if (INTERACTIVE_TAGS.has(el.tagName.toLowerCase())) return true;
    if (el.getAttribute('tabindex') !== null) return true;
    if (el.getAttribute('onclick') !== null) return true;
    if (el.getAttribute('contenteditable') === 'true') return true;
    return MEANINGFUL_ROLES.has(role);
  }

  function getAttributes(el, role) {
    const attrs = {};
    const level = headingLevel(el);
    if (level) attrs.level = level;
    if (el.checked) attrs.checked = true;
    if (el.disabled) attrs.disabled = true;
    if (el.required) attrs.required = true;
    if (el.readOnly) attrs.readonly = true;
    const expanded = el.getAttribute('aria-expanded');
    if (expanded) attrs.expanded = expanded === 'true';
    const selected = el.getAttribute('aria-selected');
    if (selected) attrs.selected = selected === 'true';
    const pressed = el.getAttribute('aria-pressed');
    if (pressed) attrs.pressed = pressed === 'true';
    const current = el.getAttribute('aria-current');
    if (current && current !== 'false') attrs.current = current;
    if (el.value && (role === 'textbox' || role === 'searchbox' ||
        role === 'spinbutton' || role === 'combobox')) {
      attrs.value = el.value.substring(0, 200);
    }
    const describedBy = el.getAttribute('aria-describedby');
    if (describedBy) {
      const desc = describedBy.split(/\\s+/).map(id => {
        const ref = document.getElementById(id);
        return ref ? (ref.textContent || '').trim() : '';
      }).filter(Boolean).join(' ');
      if (desc) attrs.description = desc.substring(0, 200);
    }
    return attrs;
  }

  function walk(el) {
    if (!el || el.nodeType !== 1) return null;
    if (!isVisible(el)) return null;

    const tag = el.tagName.toLowerCase();

    // Skip script, style, noscript, svg internals
    if (['script', 'style', 'noscript', 'link', 'meta', 'br', 'wbr'].includes(tag))
      return null;

    // Skip annotation overlay elements injected by spel annotate
    if (el.hasAttribute('data-spel-annotate')) return null;

    const role = getRole(el);
    const name = getName(el);
    const attrs = getAttributes(el, role);

    // Walk children
    const children = [];
    for (const child of el.children) {
      const node = walk(child);
      if (node) children.push(node);
    }

    // Get text content — leaf text for childless elements, direct text nodes for mixed content
    let leafText = null;
    if (children.length === 0) {
      const text = (el.textContent || '').trim();
      if (text && !name) {
        leafText = text.length <= 200 ? text : text.substring(0, 197) + '...';
      }
    } else {
      const parts = [];
      for (const n of el.childNodes) {
        if (n.nodeType === 3) {
          const t = n.textContent.trim();
          if (t) parts.push(t);
        }
      }
      const directText = parts.join(' ');
      if (directText && !name) {
        leafText = directText.length <= 200 ? directText : directText.substring(0, 197) + '...';
      }
    }

    // Decide if this node is meaningful enough to include
    const hasMeaningfulRole = !SKIP_ROLES.has(role) && role !== '';
    const isInteractive = shouldAssignRef(el, role);
    const hasContent = name || leafText;

    // Detect CSS-rendered visual content (icons via background-image or content property)
    const hasCSSImage = !hasContent && children.length === 0 && (function() {
      const rect = el.getBoundingClientRect();
      if (rect.width < 4 || rect.height < 4) return false;
      try {
        const style = getComputedStyle(el);
        const bg = style.backgroundImage;
        if (bg && bg !== 'none') return true;
        const ct = style.content;
        if (ct && ct !== 'none' && ct !== 'normal' && ct !== '\"\"' && ct !== \"''\") return true;
      } catch(e) {}
      return false;
    })();

    // Detect text-bearing leaf elements without a meaningful ARIA role
    // (e.g., <div class=\"error-code\">ERR_CONNECTION_CLOSED</div>)
    const isTextLeaf = !hasMeaningfulRole && !isInteractive && children.length === 0 && hasContent;

    // Detect mixed content elements — has child elements AND direct text nodes
    // (e.g., <div><span>tibia.com</span> unexpectedly closed the connection.</div>)
    const isMixedContent = !hasMeaningfulRole && !isInteractive && children.length > 0 && leafText;

    // Skip empty non-structural nodes (but keep CSS-rendered images)
    if (!hasMeaningfulRole && !isInteractive && !hasContent && !hasCSSImage && children.length === 0) {
      return null;
    }

    // Promote single child if this node adds nothing
    if (!hasMeaningfulRole && !isInteractive && !hasContent && !hasCSSImage && children.length === 1) {
      return children[0];
    }

    // Assign ref — interactive elements, meaningful roles with content,
    // text-bearing leaves, mixed content elements, and CSS-rendered images
    let ref = null;
    if (isInteractive || (hasMeaningfulRole && hasContent) || isTextLeaf || isMixedContent || hasCSSImage) {
      ref = 'e' + (++counter);
      el.setAttribute('data-pw-ref', ref);
      const rect = el.getBoundingClientRect();
      const effectiveRole = hasCSSImage ? 'img' : ((isTextLeaf || isMixedContent) ? 'text' : (role || tag));
      refs[ref] = {
        role: effectiveRole,
        name: name,
        tag: tag,
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height)
      };
      if (children.length > 0 && leafText) refs[ref].mixed = true;
    }

    return {
      role: role || tag,
      name: name,
      ref: ref,
      attrs: attrs,
      text: leafText,
      children: children
    };
  }

  const _scopeSel = typeof __SCOPE__ === 'string' ? __SCOPE__ : null;
  const _root = _scopeSel ? document.querySelector(_scopeSel) : document.body;
  if (!_root) return { tree: null, refs: {}, counter: 0 };
  const tree = walk(_root);
  return { tree: tree, refs: refs, counter: counter };
})()")

;; =============================================================================
;; Tree Formatting
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

(defn- capture-js
  "Returns the capture-snapshot JS with an optional scope selector injected.

   When `scope-selector` is non-nil, the JS walks from the element matching
   that CSS selector instead of document.body. If the selector matches nothing,
   the JS returns an empty result.

   Scope can be a CSS selector or a snapshot ref (@e1, e1)."
  [scope-selector]
  (if scope-selector
    (let [css-sel (resolve-scope scope-selector)]
      (str/replace js-capture-snapshot
        "typeof __SCOPE__ === 'string' ? __SCOPE__ : null"
        (str "'" (escape-js-string css-sel) "'")))
    js-capture-snapshot))

(defn- format-attrs
  "Formats ARIA attributes into a string like '[level=2] [checked]'."
  [attrs]
  (when (seq attrs)
    (str/join " "
      (map (fn [[k v]]
             (if (true? v)
               (str "[" (name k) "]")
               (str "[" (name k) "=" v "]")))
        attrs))))

(defn- format-node
  "Formats a single tree node into a YAML-like line."
  [{:strs [role name ref attrs text]} depth]
  (let [indent (apply str (repeat (* 2 depth) \space))
        parts  (cond-> [(str "- " role)]
                 (seq name)  (conj (str "\"" name "\""))
                 ref         (conj (str "[@" ref "]"))
                 (seq attrs) (conj (format-attrs attrs))
                 text        (conj (str ": " text)))]
    (str indent (str/join " " parts))))

(defn- render-tree
  "Recursively renders the tree into YAML-like lines."
  [node depth]
  (when node
    (let [line     (format-node node depth)
          children (get node "children")]
      (into [line]
        (mapcat #(render-tree % (inc depth)))
        children))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn capture-snapshot
  "Captures an accessibility snapshot of the page with numbered refs.

   Injects JavaScript to walk the DOM, compute ARIA roles and names,
   assign data-pw-ref attributes, and collect bounding boxes.

   Params:
   `page` - Playwright Page instance.
   `opts` - Map, optional.
     :scope - String. CSS selector or snapshot ref (@e1, e1) to scope the
              snapshot to a subtree. When provided, only elements within the
              matched element are included in the tree and refs.
              If the selector matches nothing, returns an empty snapshot.

   Returns:
   Map with:
     :tree    - String. YAML-like accessibility tree with [@eN] annotations.
     :refs    - Map. {\"e1\" {:role \"button\" :name \"Submit\" :bbox {:x :y :width :height}} ...}
     :counter - Long. Total number of refs assigned."
  ([^Page page]
   (capture-snapshot page {}))
  ([^Page page opts]
   (let [scope    (:scope opts)
         js       (capture-js scope)
         result   (page/evaluate page js)
         raw-tree (get result "tree")
         raw-refs (get result "refs")
         counter  (get result "counter")]
     {:tree    (when raw-tree
                 (str/join "\n" (render-tree raw-tree 0)))
      :refs    (into {}
                 (map (fn [[ref-id info]]
                        [ref-id
                         (cond-> {:role   (get info "role")
                                  :name   (get info "name")
                                  :tag    (get info "tag")
                                  :bbox   {:x      (get info "x")
                                           :y      (get info "y")
                                           :width  (get info "width")
                                           :height (get info "height")}}
                           (get info "mixed") (assoc :mixed true))]))

                 raw-refs)
      :counter (or counter 0)})))

(defn capture-snapshot-for-frame
  "Captures an accessibility snapshot for a specific frame.

   Refs are prefixed with the frame ordinal: f1_e1, f2_e3, etc.

   Params:
   `frame`         - Playwright Frame instance.
   `frame-ordinal` - Long. Frame index (1-based).

   Returns:
   Same format as capture-snapshot, but with prefixed refs."
  [^Frame _frame frame-ordinal]
  ;; TODO: Implement frame-specific snapshot logic.
  ;; For now, fall back to a stub — frame-specific logic will be expanded.
  (let [prefix (str "f" frame-ordinal "_")]
    {:tree    nil
     :refs    {}
     :counter 0
     :prefix  prefix}))

(defn capture-full-snapshot
  "Captures a snapshot of the page and all its iframes.

   Combines main frame and iframe snapshots into a unified tree.

   Params:
   `page` - Playwright Page instance.

   Returns:
   Map with :tree, :refs, :counter covering all frames."
  [^Page page]
  (let [main-snap  (capture-snapshot page)
        frames     (page/frames page)
        ;; Frames include the main frame as first element
        sub-frames (rest frames)]
    (if (empty? sub-frames)
      main-snap
      ;; Merge iframe snapshots
      (let [iframe-snaps (map-indexed
                           (fn [idx _frame]
                             (capture-snapshot-for-frame
                               _frame (inc idx)))
                           sub-frames)
            all-refs     (apply merge
                           (:refs main-snap)
                           (map :refs iframe-snaps))
            all-trees    (str/join "\n"
                           (cond-> [(:tree main-snap)]
                             (seq iframe-snaps)
                             (into (keep :tree iframe-snaps))))]
        {:tree    all-trees
         :refs    all-refs
         :counter (+ (:counter main-snap)
                    (reduce + 0 (map :counter iframe-snaps)))}))))

(defn resolve-ref
  "Resolves a ref ID to a Playwright Locator.

   The element must have been tagged with data-pw-ref during capture-snapshot.

   Params:
   `page`   - Playwright Page instance.
   `ref-id` - String. Ref like \"e1\", \"e2\", etc.

   Returns:
   Locator instance for the element."
  [^Page page ^String ref-id]
  (page/locator page (str "[data-pw-ref=\"" ref-id "\"]")))

(defn clear-refs!
  "Removes all data-pw-ref attributes from the page.

   Params:
   `page` - Playwright Page instance."
  [^Page page]
  (page/evaluate page
    "(() => { document.querySelectorAll('[data-pw-ref]').forEach(el => { el.removeAttribute('data-pw-ref'); }); })()"))

(defn ref-bounding-box
  "Returns the bounding box for a ref from the last snapshot.

   Params:
   `refs`   - Map of refs from capture-snapshot.
   `ref-id` - String. Ref like \"e1\".

   Returns:
   Map {:x :y :width :height} or nil."
  [refs ref-id]
  (:bbox (get refs ref-id)))
