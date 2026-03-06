(ns com.blockether.spel.snapshot
  "Accessibility snapshot with content-hash stable refs.

   Walks the DOM tree via JavaScript injection and builds a YAML-like
   accessibility tree with content-hash refs (deterministic across page states).
   Elements are tagged with `data-pw-ref` attributes for later interaction.

   Usage:
     (def snap (capture-snapshot page))
     (:tree snap)      ;; YAML-like string with [@eXXXXX] annotations
     (:refs snap)      ;; {ref-id {:role :name :tag :bbox} ...}
     (resolve-ref page ref-id) ;; returns Locator for the element"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page])
  (:import
   [com.microsoft.playwright Frame Page]))

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
  const _withStyles = typeof __STYLES__ === 'boolean' ? __STYLES__ : false;
  const _styleDetail = typeof __STYLE_DETAIL__ === 'string' ? __STYLE_DETAIL__ : 'base';
  const _withSelectors = typeof __SELECTORS__ === 'boolean' ? __SELECTORS__ : false;

  const identityCounts = {};

  function fnv1a(str) {
    let h = 0x811c9dc5;
    for (let i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h;
  }

  function stableRef(el, role, name) {
    const tag = el.tagName.toLowerCase();
    const id = el.id;
    const base = id ? ('id:' + id.trim()) : (role + '|' + (name || '').trim() + '|' + tag);
    if (!identityCounts[base]) identityCounts[base] = 0;
    identityCounts[base]++;
    const key = identityCounts[base] > 1 ? base + '|' + identityCounts[base] : base;
    let ref = 'e' + (fnv1a(key) >>> 8).toString(36).padStart(5, '0');
    let probe = 0;
    while (refs[ref] && probe < 100) {
      probe++;
      ref = 'e' + (fnv1a(key + '#' + probe) >>> 8).toString(36).padStart(5, '0');
    }
    return ref;
  }

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

    // 7. Content preview for container elements with meaningful roles
    const containerPreviewRoles = new Set(['article', 'region', 'listitem', 'figure', 'group']);
    const elRole = getRole(el);
    if (el.children.length > 0 && containerPreviewRoles.has(elRole)) {
      const fullText = (el.innerText || '').trim();
      if (fullText) {
        const preview = fullText.substring(0, 80).replace(/\\s+/g, ' ');
        return preview + (fullText.length > 80 ? '...' : '');
      }
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

  function isJSInteractive(el) {
    // JS-assigned on* handler properties (not HTML attributes)
    if (typeof el.onclick === 'function' || typeof el.ondblclick === 'function' ||
        typeof el.onmousedown === 'function' || typeof el.onpointerdown === 'function' ||
        typeof el.ontouchstart === 'function') return true;
    // cursor: pointer - but ONLY if not inherited from an interactive ancestor
    try {
      if (getComputedStyle(el).cursor === 'pointer') {
        // Walk up to find if cursor:pointer is set on this element or inherited from a clickable ancestor
        let anc = el.parentElement;
        while (anc && anc !== document.body) {
          if (INTERACTIVE_TAGS.has(anc.tagName.toLowerCase()) || anc.getAttribute('role') === 'button' ||
              anc.getAttribute('role') === 'link' || anc.getAttribute('tabindex') !== null ||
              typeof anc.onclick === 'function') {
            // cursor:pointer is inherited from interactive ancestor - not independently clickable
            return false;
          }
          // If ancestor has cursor:pointer explicitly set, it is the source - stop
          try { if (getComputedStyle(anc).cursor === 'pointer') break; } catch(e) { break; }
          anc = anc.parentElement;
        }
        return true;
      }
    } catch(e) {}
    // React event props (__reactProps$ or __reactFiber$)
    for (const k of Object.keys(el)) {
      if (k.startsWith('__reactProps$')) {
        const p = el[k];
        if (p && (p.onClick || p.onClickCapture || p.onMouseDown || p.onChange)) return true;
      }
      if (k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$')) {
        let f = el[k];
        for (let i = 0; i < 3 && f; i++) {
          const p = f.memoizedProps || f.pendingProps;
          if (p && (p.onClick || p.onClickCapture)) return true;
          f = f.return;
        }
      }
    }
    return false;
  }

  function detectListeners(el) {
    const evts = new Set();
    const ps = ['onclick','ondblclick','onmousedown','onmouseup','onpointerdown',
                'onpointerup','ontouchstart','ontouchend','onkeydown','onkeyup',
                'onchange','oninput','onfocus','onblur','onsubmit'];
    for (const p of ps) { if (typeof el[p] === 'function') evts.add(p.substring(2)); }
    // React props
    for (const k of Object.keys(el)) {
      if (k.startsWith('__reactProps$')) {
        const p = el[k]; if (!p) continue;
        if (p.onClick || p.onClickCapture) evts.add('click');
        if (p.onMouseDown) evts.add('mousedown');
        if (p.onChange) evts.add('change');
        if (p.onInput) evts.add('input');
        if (p.onSubmit) evts.add('submit');
        if (p.onKeyDown) evts.add('keydown');
        if (p.onFocus) evts.add('focus');
        if (p.onBlur) evts.add('blur');
      }
      if (k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$')) {
        let f = el[k];
        for (let i = 0; i < 5 && f; i++) {
          const p = f.memoizedProps || f.pendingProps;
          if (p) {
            if (p.onClick || p.onClickCapture) evts.add('click');
            if (p.onMouseDown) evts.add('mousedown');
            if (p.onChange) evts.add('change');
            if (p.onInput) evts.add('input');
            if (p.onSubmit) evts.add('submit');
            if (p.onKeyDown) evts.add('keydown');
            if (p.onFocus) evts.add('focus');
            if (p.onBlur) evts.add('blur');
            break;
          }
          f = f.return;
        }
      }
    }
    // jQuery
    if (typeof jQuery !== 'undefined') {
      try {
        const d = jQuery._data(el, 'events');
        if (d) for (const t of Object.keys(d)) evts.add(t);
      } catch(e) {}
    }
    // cursor: pointer - only if not inherited from interactive ancestor
    let ptr = false;
    try {
      if (getComputedStyle(el).cursor === 'pointer') {
        ptr = true;
        // Check if pointer is inherited from interactive ancestor
        let anc = el.parentElement;
        while (anc && anc !== document.body) {
          if (INTERACTIVE_TAGS.has(anc.tagName.toLowerCase()) || anc.getAttribute('role') === 'button' ||
              anc.getAttribute('role') === 'link' || anc.getAttribute('tabindex') !== null ||
              typeof anc.onclick === 'function') {
            ptr = false; break;
          }
          try { if (getComputedStyle(anc).cursor === 'pointer') break; } catch(e) { break; }
          anc = anc.parentElement;
        }
      }
    } catch(e) {}
    return {events: [...evts], pointer: ptr};
  }

  function shouldAssignRef(el, role) {
    if (INTERACTIVE_TAGS.has(el.tagName.toLowerCase())) return true;
    if (el.getAttribute('tabindex') !== null) return true;
    if (el.getAttribute('onclick') !== null) return true;
    if (el.getAttribute('contenteditable') === 'true') return true;
    if (isJSInteractive(el)) return true;
    return MEANINGFUL_ROLES.has(role);
  }

  function getHref(el) {
    const tag = el.tagName.toLowerCase();
    if (tag === 'a') {
      const href = el.getAttribute('href');
      if (href) {
        try { return new URL(href, document.baseURI).href; }
        catch(e) { return href; }
      }
    }
    return null;
  }

  function getInputType(el) {
    const tag = el.tagName.toLowerCase();
    if (tag === 'input') return (el.getAttribute('type') || 'text').toLowerCase();
    if (tag === 'textarea') return 'textarea';
    return null;
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

  // --- Computed Styles: three-tier system (opt-in via __STYLES__) ---
  var STYLE_MINIMAL = ['display','position','top','left','right','bottom','backgroundColor','color','fontSize','fontWeight',
    'padding','margin','width','height','borderRadius','border'];
  var STYLE_BASE = STYLE_MINIMAL.concat(['fontFamily','lineHeight','textAlign','boxShadow',
    'opacity','visibility','overflow','textDecoration','cursor','float','clear','flexDirection','justifyContent','alignItems','gap']);
  var STYLE_MAX = STYLE_BASE.concat(['zIndex','textTransform','letterSpacing','whiteSpace',
    'textOverflow','maxWidth','maxHeight','minWidth','minHeight','backgroundImage','pointerEvents','outline','transform']);

  function toKebab(s) { return s.replace(/[A-Z]/g, function(c) { return '-' + c.toLowerCase(); }); }

  function isDefaultStyle(k, v) {
    switch(k) {
      case 'display': return v==='block'||v==='inline';
      case 'position': return v==='static';
      case 'top': case 'left': case 'right': case 'bottom': return v==='auto';
      case 'backgroundColor': return v==='rgba(0, 0, 0, 0)'||v==='transparent';
      case 'color': return false;
      case 'fontSize': return false;
      case 'fontWeight': return v==='400';
      case 'padding': case 'margin': case 'borderRadius': return v==='0px';
      case 'width': case 'height': return v==='auto';
      case 'border': return v==='none'||v.startsWith('0px');
      case 'fontFamily': return false;
      case 'lineHeight': return v==='normal';
      case 'textAlign': return v==='start'||v==='left';
      case 'boxShadow': return v==='none';
      case 'opacity': return v==='1';
      case 'visibility': return v==='visible';
      case 'overflow': return v==='visible';
      case 'textDecoration': return v==='none'||v.startsWith('none');
      case 'cursor': return v==='auto'||v==='default';
      case 'float': return v==='none';
      case 'clear': return v==='none';
      case 'flexDirection': return v==='row';
      case 'justifyContent': return v==='normal'||v==='flex-start';
      case 'alignItems': return v==='normal'||v==='stretch';
      case 'gap': return v==='normal'||v==='0px';
      case 'zIndex': return v==='auto';
      case 'textTransform': return v==='none';
      case 'letterSpacing': return v==='normal';
      case 'whiteSpace': return v==='normal';
      case 'textOverflow': return v==='clip';
      case 'maxWidth': case 'maxHeight': return v==='none';
      case 'minWidth': case 'minHeight': return v==='0px';
      case 'backgroundImage': return v==='none';
      case 'pointerEvents': return v==='auto';
      case 'outline': return v==='none'||v.startsWith('0px');
      case 'transform': return v==='none';
      default: return false;
    }
  }

  function compactVal(k, v) {
    if (v.startsWith('rgb')) {
      var m = v.match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\s*\\)/);
      if (m && !(m[4]&&parseFloat(m[4])<1)) {
        var h = '#'+((1<<24)|(parseInt(m[1])<<16)|(parseInt(m[2])<<8)|parseInt(m[3])).toString(16).slice(1);
        if (h.length===7&&h[1]===h[2]&&h[3]===h[4]&&h[5]===h[6]) return '#'+h[1]+h[3]+h[5];
        return h;
      }
    }
    if (k==='fontFamily') return v.split(',')[0].replace(/'/g,'').replace(/\"/g,'').trim();
    return v;
  }

  function extractStyles(el) {
    if (!_withStyles) return null;
    try {
      var keys = _styleDetail === 'minimal' ? STYLE_MINIMAL : _styleDetail === 'max' ? STYLE_MAX : STYLE_BASE;
      var s = getComputedStyle(el);
      var compact = {}, full = {}, has = false;
      for (var i=0; i<keys.length; i++) {
        var k = keys[i], v = s[k];
        if (!v) continue;
        var kk = toKebab(k);
        full[kk] = v;
        if (!isDefaultStyle(k, v)) { compact[kk] = compactVal(k, v); has = true; }
      }
      return {compact: has ? compact : null, full: full};
    } catch(e) { return null; }
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
    let _elStyles = null;
    let _listeners = null;
    let _pointer = false;
    if (isInteractive || (hasMeaningfulRole && hasContent) || isTextLeaf || isMixedContent || hasCSSImage) {
      const effectiveRole = hasCSSImage ? 'img' : ((isTextLeaf || isMixedContent) ? 'text' : (role || tag));
      ref = stableRef(el, effectiveRole, name);
      counter++;
      el.setAttribute('data-pw-ref', ref);
      const rect = el.getBoundingClientRect();
      const href = getHref(el);
      const inputType = getInputType(el);
      const level = headingLevel(el);
      refs[ref] = {
        role: effectiveRole,
        name: name,
        tag: tag,
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height)
      };
      if (href) refs[ref].url = href;
      if (inputType) refs[ref].type = inputType;
      if (level) refs[ref].level = level;
      if (el.type === 'checkbox' || el.type === 'radio') refs[ref].checked = !!el.checked;
      if (el.value && (effectiveRole === 'textbox' || effectiveRole === 'searchbox' ||
          effectiveRole === 'spinbutton' || effectiveRole === 'combobox'))
        refs[ref].value = el.value.substring(0, 200);
      if (children.length > 0 && leafText) refs[ref].mixed = true;
      if (_withSelectors) {
        if (el.id) refs[ref].id = el.id;
        const cls = (el.className && typeof el.className === 'string') ? el.className.trim() : '';
        if (cls) refs[ref].classes = cls;
      }
      _elStyles = extractStyles(el);
      if (_elStyles && _elStyles.full) refs[ref].styles = _elStyles.full;
      // Detect event listeners and pointer cursor
      const _li = detectListeners(el);
      if (_li.events.length) { _listeners = _li.events; refs[ref].listeners = _listeners; }
      if (_li.pointer) { _pointer = true; refs[ref].pointer = true; }
    }

    const nodeHref = getHref(el);
    const nodeId = (_withSelectors && el.id) ? el.id : null;
    const nodeClasses = (_withSelectors && el.className && typeof el.className === 'string' && el.className.trim()) ? el.className.trim() : null;
    return {
      role: role || tag,
      name: name,
      ref: ref,
      attrs: attrs,
      text: leafText,
      href: nodeHref,
      id: nodeId,
      classes: nodeClasses,
      styles: _elStyles ? _elStyles.compact : null,
      listeners: _listeners,
      pointer: _pointer,
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

(defn- capture-js
  "Returns the capture-snapshot JS with optional flags injected.

   When `:scope` is provided, the JS walks from the element matching
   that CSS selector instead of document.body. If the selector matches nothing,
   the JS returns an empty result.

    When `:styles` is true, each ref'd element includes computed CSS styles
    in kebab-case CSS property names.  The `:styles-detail` option selects
    the tier: 'minimal' (16 props), 'base' (31, default), or 'max' (44).

    Scope can be a CSS selector or a snapshot ref (@e2yrjz)."
  [opts]
  (let [scope-selector  (:scope opts)
        with-styles     (:styles opts)
        styles-detail   (or (:styles-detail opts) "base")
        with-selectors  (:selectors opts)]
    (cond-> js-capture-snapshot
      scope-selector
      (str/replace "typeof __SCOPE__ === 'string' ? __SCOPE__ : null"
        (str "'" (escape-js-string (resolve-scope scope-selector)) "'"))
      with-styles
      (str/replace "typeof __STYLES__ === 'boolean' ? __STYLES__ : false"
        "true")
      with-styles
      (str/replace "typeof __STYLE_DETAIL__ === 'string' ? __STYLE_DETAIL__ : 'base'"
        (str "'" styles-detail "'"))
      with-selectors
      (str/replace "typeof __SELECTORS__ === 'boolean' ? __SELECTORS__ : false"
        "true"))))

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

(def ^:private style-display-order
  "Display order for CSS style keys in tree output.
   Layout → box → visual → typography → text → border → misc."
  ["display" "position" "top" "left" "right" "bottom" "flex-direction" "justify-content" "align-items" "gap"
   "width" "height" "max-width" "max-height" "min-width" "min-height"
   "padding" "margin" "overflow"
   "background-color" "background-image" "color" "opacity" "visibility" "transform"
   "float" "clear"
   "font-size" "font-weight" "font-family" "line-height" "text-align"
   "text-decoration" "text-transform" "letter-spacing" "white-space" "text-overflow"
   "border" "border-radius" "box-shadow" "outline"
   "z-index" "cursor" "pointer-events"])

(defn- format-styles
  "Formats compact styles map into inline {k:v;k:v} string."
  [styles]
  (let [ordered (keep (fn [k] (when-let [v (get styles k)] (str k ":" v)))
                  style-display-order)]
    (when (seq ordered)
      (str "{" (str/join ";" ordered) "}"))))

(defn- format-node
  "Formats a single tree node into a YAML-like line."
  [{:strs [role name ref attrs text href id classes styles listeners pointer]} depth]
  (let [depth (long depth)
        indent (apply str (repeat (* 2 depth) \space))
        parts  (cond-> [(str "- " role)]
                 (seq name)      (conj (str "\"" name "\""))
                 ref             (conj (str "[@" ref "]"))
                 (seq id)        (conj (str "[#" id "]"))
                 (seq classes)   (conj (str "[." (str/replace classes " " ".") "]"))
                 (seq href)      (conj (str "[url=" href "]"))
                 (seq attrs)     (conj (format-attrs attrs))
                 (seq listeners) (conj (str "[on:" (str/join "," listeners) "]"))
                 pointer         (conj "[clickable]")
                 (seq styles)    (conj (format-styles styles))
                 text            (conj (str ": " text)))]
    (str indent (str/join " " parts))))

(defn- render-tree
  "Recursively renders the tree into YAML-like lines."
  [node depth]
  (when node
    (let [line     (format-node node depth)
          children (get node "children")]
      (into [line]
        (mapcat #(render-tree % (inc (long depth))))
        children))))

(defn flatten-tree
  "Flattens a YAML-like tree string by stripping all leading whitespace.
   Each node appears at depth 0, removing the nested hierarchy.

   Useful for AI agents that need a simple list of elements without
   nesting structure.

   Params:
   `tree` - String. YAML-like accessibility tree from capture-snapshot.

   Returns:
   String with all lines at depth 0, or nil if tree is nil."
  [^String tree]
  (when tree
    (str/join "\n"
      (map str/triml (str/split-lines tree)))))
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
     :scope - String. CSS selector or snapshot ref (@e2yrjz, e2yrjz) to scope the
              snapshot to a subtree. When provided, only elements within the
              matched element are included in the tree and refs.
              If the selector matches nothing, returns an empty snapshot.

   Returns:
   Map with:
     :tree    - String. YAML-like accessibility tree with [@eXXXXX] annotations.
     :refs    - Map. {'e2yrjz' {:role 'button' :name 'Submit' :bbox {:x :y :width :height}} ...}
     :counter - Long. Total number of refs assigned."
  ([^Page page]
   (capture-snapshot page {}))
  ([^Page page opts]
   (let [js       (capture-js opts)
         result   (page/evaluate page js)
         raw-tree (get result "tree")
         raw-refs (get result "refs")
         counter  (get result "counter")
         vp       (page/viewport-size page)]
     {:tree    (when raw-tree
                 (let [lines  (render-tree raw-tree 0)
                       dev    (:device opts)
                       parts  (cond-> []
                                vp  (conj (str "viewport: " (:width vp) "x" (:height vp)))
                                dev (conj (str "device: " dev)))
                       header (when (seq parts) (str "[" (str/join ", " parts) "]"))]
                   (str/join "\n" (if header (into [header] lines) lines))))
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
                           (get info "mixed")   (assoc :mixed true)
                           (get info "url")     (assoc :url (get info "url"))
                           (get info "type")    (assoc :type (get info "type"))
                           (get info "level")   (assoc :level (get info "level"))
                           (some? (get info "checked")) (assoc :checked (get info "checked"))
                           (get info "value")   (assoc :value (get info "value"))
                           (get info "id")      (assoc :id (get info "id"))
                           (get info "classes") (assoc :classes (get info "classes"))
                           (get info "listeners") (assoc :listeners (vec (get info "listeners")))
                           (get info "pointer")  (assoc :pointer true)
                           (get info "styles")  (assoc :styles (into {} (get info "styles"))))]))

                 raw-refs)
      :counter  (or counter 0)
      :viewport vp
      :device   (:device opts)})))

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
                               _frame (inc (long idx))))
                           sub-frames)
            all-refs     (apply merge
                           (:refs main-snap)
                           (map :refs iframe-snaps))
            all-trees    (str/join "\n"
                           (cond-> [(:tree main-snap)]
                             (seq iframe-snaps)
                             (into (keep :tree iframe-snaps))))]
        {:tree     all-trees
         :refs     all-refs
         :counter  (+ (long (:counter main-snap))
                     (long (reduce + 0 (map :counter iframe-snaps))))
         :viewport (:viewport main-snap)}))))

(defn resolve-ref
  "Resolves a ref ID to a Playwright Locator.

   The element must have been tagged with data-pw-ref during capture-snapshot.

   Params:
   `page`   - Playwright Page instance.
   `ref-id` - String. Bare ref ID without @ prefix (e.g. e2yrjz).

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
   `ref-id` - String. Content-hash ref like \"e2yrjz\".

   Returns:
   Map {:x :y :width :height} or nil."
  [refs ref-id]
  (:bbox (get refs ref-id)))

;; =============================================================================
;; Snapshot Diffing
;; =============================================================================

(defn diff-snapshots
  "Compares two accessibility snapshot strings line-by-line.

   Returns a map with diff statistics and individual line changes.

   Params:
   `baseline` - String. The baseline snapshot text.
   `current`  - String. The current snapshot text.

   Returns:
   {:added N :removed N :changed N :unchanged N :diff [\"  line\" \"- old\" \"+ new\" ...]}."
  [^String baseline ^String current]
  (let [old-lines (str/split-lines baseline)
        new-lines (str/split-lines current)
        max-len   (max (count old-lines) (count new-lines))]
    (loop [i 0 adds 0 removes 0 changes 0 unchanged 0 diff-lines []]
      (if (>= i max-len)
        {:added adds :removed removes :changed changes :unchanged unchanged :diff diff-lines}
        (let [old-line (get old-lines i)
              new-line (get new-lines i)]
          (cond
            (= old-line new-line)
            (recur (inc i) adds removes changes (inc unchanged)
              (conj diff-lines (str "  " old-line)))

            (and old-line new-line)
            (recur (inc i) adds removes (inc changes) unchanged
              (conj diff-lines (str "- " old-line) (str "+ " new-line)))

            (and (nil? old-line) new-line)
            (recur (inc i) (inc adds) removes changes unchanged
              (conj diff-lines (str "+ " new-line)))

            :else
            (recur (inc i) adds (inc removes) changes unchanged
              (conj diff-lines (str "- " old-line)))))))))
