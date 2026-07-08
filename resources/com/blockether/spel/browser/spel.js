/*
 * spel.js — pure in-page automation engine ("Playwright, but a <script> tag").
 *
 * No bundler, no build step, no Node, no CDP. Drop this into any page (via a
 * <script> tag, page.addInitScript, or the spel daemon) and it installs
 * `window.__spel` — a single `invoke(command)` entry point that maps the spel /
 * Playwright verb surface onto real DOM operations, plus an interactive overlay
 * element picker bound to a keymap.
 *
 * Contract:
 *   window.__spel.invoke({ action: "click", selector: "@e5" })
 *     -> Promise<{ action, ok: true, value }>  (never rejects; errors -> ok:false)
 *
 * Selectors:
 *   "@eNN"          -> the element carrying data-pw-ref="eNN" (snapshot refs)
 *   "text=Foo"      -> first element whose trimmed text equals/ contains Foo
 *   "css=..." / raw -> CSS selector (default engine)
 *   "xpath=//..." / "//..." -> XPath
 */
(function (global) {
  "use strict";

  if (global.__spel && global.__spel.__installed) {
    return; // idempotent — surviving multiple init scripts
  }

  // ---------------------------------------------------------------------------
  // Ref registry — stable-ish handles the daemon/snapshot address elements by.
  // ---------------------------------------------------------------------------
  var REF_ATTR = "data-pw-ref";
  var refCounter = 0;

  function nextRef() {
    refCounter += 1;
    return "e" + refCounter;
  }

  function ensureRef(el) {
    if (!el || el.nodeType !== 1) return null;
    var existing = el.getAttribute(REF_ATTR);
    if (existing) return existing;
    var ref = nextRef();
    el.setAttribute(REF_ATTR, ref);
    return ref;
  }

  function clearRefs() {
    var els = document.querySelectorAll("[" + REF_ATTR + "]");
    for (var i = 0; i < els.length; i++) els[i].removeAttribute(REF_ATTR);
    refCounter = 0;
    return els.length;
  }

  // ---------------------------------------------------------------------------
  // Selector engine.
  // ---------------------------------------------------------------------------
  function isRef(sel) {
    return typeof sel === "string" && /^@e[a-z0-9]+$/i.test(sel);
  }

  function textMatches(el, needle, exact) {
    var t = (el.textContent || "").trim();
    return exact ? t === needle : t.indexOf(needle) !== -1;
  }

  function byText(needle, exact, root) {
    root = root || document;
    var all = root.querySelectorAll("*");
    var best = null;
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (!textMatches(el, needle, exact)) continue;
      // Prefer the deepest / smallest element that still matches.
      var hasMatchingChild = false;
      for (var j = 0; j < el.children.length; j++) {
        if (textMatches(el.children[j], needle, exact)) {
          hasMatchingChild = true;
          break;
        }
      }
      if (!hasMatchingChild) {
        best = el;
        break;
      }
    }
    return best;
  }

  function byXpath(expr, root) {
    var res = document.evaluate(
      expr,
      root || document,
      null,
      XPathResult.FIRST_ORDERED_NODE_TYPE,
      null
    );
    return res.singleNodeValue;
  }

  function allByXpath(expr, root) {
    var out = [];
    var res = document.evaluate(
      expr,
      root || document,
      null,
      XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,
      null
    );
    for (var i = 0; i < res.snapshotLength; i++) out.push(res.snapshotItem(i));
    return out;
  }

  function resolveOne(selector, root) {
    if (selector == null) throw new Error("selector is required");
    if (isRef(selector)) {
      return (root || document).querySelector(
        "[" + REF_ATTR + '="' + selector.slice(1) + '"]'
      );
    }
    if (selector.indexOf("text=") === 0) {
      var raw = selector.slice(5);
      var exact = raw[0] === '"' && raw[raw.length - 1] === '"';
      return byText(exact ? raw.slice(1, -1) : raw, exact, root);
    }
    if (selector.indexOf("xpath=") === 0) return byXpath(selector.slice(6), root);
    if (selector.indexOf("//") === 0) return byXpath(selector, root);
    if (selector.indexOf("css=") === 0) selector = selector.slice(4);
    return (root || document).querySelector(selector);
  }

  function resolveAll(selector, root) {
    if (isRef(selector)) {
      var one = resolveOne(selector, root);
      return one ? [one] : [];
    }
    if (selector.indexOf("text=") === 0) {
      var raw = selector.slice(5);
      var exact = raw[0] === '"' && raw[raw.length - 1] === '"';
      var needle = exact ? raw.slice(1, -1) : raw;
      var out = [];
      var all = (root || document).querySelectorAll("*");
      for (var i = 0; i < all.length; i++) {
        if (textMatches(all[i], needle, exact)) out.push(all[i]);
      }
      return out;
    }
    if (selector.indexOf("xpath=") === 0) return allByXpath(selector.slice(6), root);
    if (selector.indexOf("//") === 0) return allByXpath(selector, root);
    if (selector.indexOf("css=") === 0) selector = selector.slice(4);
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  function must(selector, root) {
    var el = resolveOne(selector, root);
    if (!el) throw new Error("no element for selector: " + selector);
    return el;
  }

  // ---------------------------------------------------------------------------
  // ARIA role / accessible-name computation (compact but faithful subset).
  // ---------------------------------------------------------------------------
  var IMPLICIT_ROLE = {
    A: function (el) { return el.hasAttribute("href") ? "link" : null; },
    BUTTON: "button",
    NAV: "navigation",
    MAIN: "main",
    HEADER: "banner",
    FOOTER: "contentinfo",
    ASIDE: "complementary",
    SECTION: "region",
    ARTICLE: "article",
    UL: "list",
    OL: "list",
    LI: "listitem",
    TABLE: "table",
    TR: "row",
    TD: "cell",
    TH: "columnheader",
    IMG: "img",
    H1: "heading", H2: "heading", H3: "heading",
    H4: "heading", H5: "heading", H6: "heading",
    SELECT: "combobox",
    TEXTAREA: "textbox",
    FORM: "form",
    P: "paragraph",
    OPTION: "option",
    PROGRESS: "progressbar"
  };

  function inputRole(el) {
    var type = (el.getAttribute("type") || "text").toLowerCase();
    switch (type) {
      case "checkbox": return "checkbox";
      case "radio": return "radio";
      case "button":
      case "submit":
      case "reset": return "button";
      case "range": return "slider";
      case "search": return "searchbox";
      case "email":
      case "tel":
      case "url":
      case "text": return "textbox";
      case "number": return "spinbutton";
      default: return "textbox";
    }
  }

  function roleOf(el) {
    var explicit = el.getAttribute && el.getAttribute("role");
    if (explicit) return explicit.trim().split(/\s+/)[0];
    var tag = el.tagName;
    if (tag === "INPUT") return inputRole(el);
    var r = IMPLICIT_ROLE[tag];
    return typeof r === "function" ? r(el) : r || null;
  }

  function accessibleName(el) {
    var label = el.getAttribute && el.getAttribute("aria-label");
    if (label) return label.trim();
    var labelledby = el.getAttribute && el.getAttribute("aria-labelledby");
    if (labelledby) {
      var parts = labelledby.split(/\s+/).map(function (id) {
        var t = document.getElementById(id);
        return t ? (t.textContent || "").trim() : "";
      });
      var joined = parts.join(" ").trim();
      if (joined) return joined;
    }
    if (el.tagName === "INPUT" || el.tagName === "SELECT" || el.tagName === "TEXTAREA") {
      if (el.id) {
        var lbl = document.querySelector('label[for="' + el.id + '"]');
        if (lbl) return (lbl.textContent || "").trim();
      }
      var wrap = el.closest && el.closest("label");
      if (wrap) return (wrap.textContent || "").trim();
      var ph = el.getAttribute("placeholder");
      if (ph) return ph.trim();
    }
    if (el.tagName === "IMG") {
      var alt = el.getAttribute("alt");
      if (alt != null) return alt.trim();
    }
    var title = el.getAttribute && el.getAttribute("title");
    // For structural/text roles the name is the trimmed text content.
    var txt = "";
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3) txt += n.textContent;
    }
    txt = txt.trim();
    if (!txt && el.children.length === 0) txt = (el.textContent || "").trim();
    return txt || (title ? title.trim() : "");
  }

  // ---------------------------------------------------------------------------
  // Visibility (Playwright-ish): rendered, not display:none/visibility:hidden,
  // has layout box.
  // ---------------------------------------------------------------------------
  function isVisible(el) {
    if (!el || el.nodeType !== 1) return false;
    if (!el.isConnected) return false;
    var style = global.getComputedStyle(el);
    if (style.display === "none") return false;
    if (style.visibility === "hidden" || style.visibility === "collapse") return false;
    if (parseFloat(style.opacity) === 0) return false;
    var rects = el.getClientRects();
    return rects.length > 0;
  }

  // ---------------------------------------------------------------------------
  // Overflow / clipping detection.
  // ---------------------------------------------------------------------------
  function overflowInfo(el) {
    var cs = global.getComputedStyle(el);
    var overflowsX = el.scrollWidth > el.clientWidth + 1;
    var overflowsY = el.scrollHeight > el.clientHeight + 1;
    return {
      scrollWidth: el.scrollWidth, clientWidth: el.clientWidth,
      scrollHeight: el.scrollHeight, clientHeight: el.clientHeight,
      overflowX: cs.overflowX, overflowY: cs.overflowY,
      overflowsX: overflowsX, overflowsY: overflowsY,
      isOverflowing: overflowsX || overflowsY
    };
  }

  function scrollParents(el) {
    var out = [];
    var p = el.parentElement;
    while (p) {
      var cs = global.getComputedStyle(p);
      if (/(auto|scroll|hidden|clip)/.test(cs.overflowY + " " + cs.overflowX)) out.push(p);
      p = p.parentElement;
    }
    return out;
  }

  function isClipped(el) {
    var r = el.getBoundingClientRect();
    var parents = scrollParents(el);
    for (var i = 0; i < parents.length; i++) {
      var pr = parents[i].getBoundingClientRect();
      if (r.left < pr.left - 1 || r.top < pr.top - 1 ||
          r.right > pr.right + 1 || r.bottom > pr.bottom + 1) {
        return true;
      }
    }
    var vw = global.innerWidth, vh = global.innerHeight;
    if (r.right <= 0 || r.bottom <= 0 || r.left >= vw || r.top >= vh) return true;
    return false;
  }

  // ---------------------------------------------------------------------------
  // Snapshot: assign refs + emit a YAML-ish accessibility tree.
  // ---------------------------------------------------------------------------
  var INTERESTING = {
    link: 1, button: 1, textbox: 1, searchbox: 1, checkbox: 1, radio: 1,
    combobox: 1, heading: 1, img: 1, listitem: 1, cell: 1, columnheader: 1,
    option: 1, slider: 1, spinbutton: 1, tab: 1, menuitem: 1, switch: 1,
    progressbar: 1
  };

  function snapshot(opts) {
    opts = opts || {};
    var root = opts.selector ? must(opts.selector) : document.body;
    var lines = [];
    var refs = {};
    function walk(el, depth) {
      if (!el || el.nodeType !== 1) return;
      if (el.tagName === "SCRIPT" || el.tagName === "STYLE") return;
      if (!opts.include_hidden && !isVisible(el)) return;
      var role = roleOf(el);
      var emit = role && (INTERESTING[role] || opts.all);
      if (emit) {
        var ref = ensureRef(el);
        var name = accessibleName(el);
        refs[ref] = {
          ref: ref,
          role: role,
          name: name,
          tag: el.tagName.toLowerCase()
        };
        var pad = new Array(depth + 1).join("  ");
        var line = pad + "- " + role;
        if (name) line += ' "' + name.replace(/"/g, '\\"') + '"';
        line += " [ref=" + ref + "]";
        lines.push(line);
      }
      for (var i = 0; i < el.children.length; i++) {
        walk(el.children[i], emit ? depth + 1 : depth);
      }
    }
    walk(root, 0);
    return { tree: lines.join("\n"), refs: refs, counter: refCounter };
  }

  // ---------------------------------------------------------------------------
  // Native value setters (React / framework-controlled inputs).
  // ---------------------------------------------------------------------------
  function setNativeValue(el, value) {
    var proto = el.tagName === "TEXTAREA"
      ? global.HTMLTextAreaElement.prototype
      : el.tagName === "SELECT"
        ? global.HTMLSelectElement.prototype
        : global.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
  }

  function fireInput(el) {
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // ---------------------------------------------------------------------------
  // Keyboard.
  // ---------------------------------------------------------------------------
  var KEY_ALIASES = {
    Enter: { key: "Enter", code: "Enter", keyCode: 13 },
    Tab: { key: "Tab", code: "Tab", keyCode: 9 },
    Escape: { key: "Escape", code: "Escape", keyCode: 27 },
    Backspace: { key: "Backspace", code: "Backspace", keyCode: 8 },
    Delete: { key: "Delete", code: "Delete", keyCode: 46 },
    ArrowUp: { key: "ArrowUp", code: "ArrowUp", keyCode: 38 },
    ArrowDown: { key: "ArrowDown", code: "ArrowDown", keyCode: 40 },
    ArrowLeft: { key: "ArrowLeft", code: "ArrowLeft", keyCode: 37 },
    ArrowRight: { key: "ArrowRight", code: "ArrowRight", keyCode: 39 },
    Space: { key: " ", code: "Space", keyCode: 32 },
    Home: { key: "Home", code: "Home", keyCode: 36 },
    End: { key: "End", code: "End", keyCode: 35 }
  };

  function parseKey(combo) {
    var parts = combo.split("+");
    var main = parts.pop();
    var mods = { ctrlKey: false, shiftKey: false, altKey: false, metaKey: false };
    parts.forEach(function (m) {
      m = m.toLowerCase();
      if (m === "control" || m === "ctrl") mods.ctrlKey = true;
      else if (m === "shift") mods.shiftKey = true;
      else if (m === "alt") mods.altKey = true;
      else if (m === "meta" || m === "cmd") mods.metaKey = true;
    });
    var spec = KEY_ALIASES[main] || {
      key: main,
      code: main.length === 1 ? "Key" + main.toUpperCase() : main,
      keyCode: main.length === 1 ? main.toUpperCase().charCodeAt(0) : 0
    };
    return { spec: spec, mods: mods };
  }

  function dispatchKey(target, type, spec, mods) {
    var ev = new KeyboardEvent(type, {
      key: spec.key,
      code: spec.code,
      keyCode: spec.keyCode,
      which: spec.keyCode,
      bubbles: true,
      cancelable: true,
      ctrlKey: mods.ctrlKey,
      shiftKey: mods.shiftKey,
      altKey: mods.altKey,
      metaKey: mods.metaKey
    });
    return target.dispatchEvent(ev);
  }

  function pressKey(target, combo) {
    var p = parseKey(combo);
    dispatchKey(target, "keydown", p.spec, p.mods);
    dispatchKey(target, "keypress", p.spec, p.mods);
    // Apply editing effects for text controls.
    var editable = target.tagName === "INPUT" || target.tagName === "TEXTAREA" ||
      target.isContentEditable;
    if (editable && !p.mods.ctrlKey && !p.mods.metaKey && !p.mods.altKey) {
      if (p.spec.key === "Backspace") {
        target.value = (target.value || "").slice(0, -1);
        fireInput(target);
      } else if (p.spec.key.length === 1) {
        setNativeValue(target, (target.value || "") + p.spec.key);
        fireInput(target);
      }
    }
    dispatchKey(target, "keyup", p.spec, p.mods);
  }

  function typeText(target, text) {
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      dispatchKey(target, "keydown", { key: ch, code: "", keyCode: ch.charCodeAt(0) }, {});
      setNativeValue(target, (target.value || "") + ch);
      target.dispatchEvent(new InputEvent("input", { bubbles: true, data: ch }));
      dispatchKey(target, "keyup", { key: ch, code: "", keyCode: ch.charCodeAt(0) }, {});
    }
    target.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // ---------------------------------------------------------------------------
  // Mouse.
  // ---------------------------------------------------------------------------
  function centerOf(el) {
    var r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }

  function mouseEvent(el, type, pt) {
    var ev = new MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      view: global,
      clientX: pt.x,
      clientY: pt.y,
      button: 0
    });
    el.dispatchEvent(ev);
  }

  function clickEl(el) {
    if (el.scrollIntoView) el.scrollIntoView({ block: "center", inline: "center" });
    var pt = centerOf(el);
    mouseEvent(el, "pointerdown", pt);
    mouseEvent(el, "mousedown", pt);
    mouseEvent(el, "pointerup", pt);
    mouseEvent(el, "mouseup", pt);
    if (typeof el.click === "function") el.click();
    else mouseEvent(el, "click", pt);
  }

  // ---------------------------------------------------------------------------
  // Overlay element picker (bound to a keymap).
  // ---------------------------------------------------------------------------
  var picker = {
    active: false,
    box: null,
    hotkey: { ctrlKey: true, shiftKey: true, key: "L" },
    serverHotkey: { ctrlKey: true, shiftKey: true, key: "K" },
    server: null,
    onPick: null,
    _lastPicked: null
  };

  var activeConn = null;

  function ensureBox() {
    if (picker.box) return picker.box;
    var box = document.createElement("div");
    box.setAttribute("data-spel-overlay", "1");
    box.style.cssText = [
      "position:fixed", "z-index:2147483647", "pointer-events:none",
      "background:rgba(59,130,246,0.25)", "border:2px solid #3b82f6",
      "border-radius:2px", "transition:all 40ms ease", "display:none"
    ].join(";");
    document.documentElement.appendChild(box);
    picker.box = box;
    return box;
  }

  function moveBox(el) {
    var box = ensureBox();
    var r = el.getBoundingClientRect();
    box.style.display = "block";
    box.style.left = r.left + "px";
    box.style.top = r.top + "px";
    box.style.width = r.width + "px";
    box.style.height = r.height + "px";
  }

  function pickerMove(e) {
    var el = document.elementFromPoint(e.clientX, e.clientY);
    if (el && el !== picker.box) moveBox(el);
  }

  function pickerClick(e) {
    e.preventDefault();
    e.stopPropagation();
    var el = document.elementFromPoint(e.clientX, e.clientY);
    if (!el || el === picker.box) return;
    var ref = ensureRef(el);
    var result = {
      ref: "@" + ref,
      role: roleOf(el),
      name: accessibleName(el),
      tag: el.tagName.toLowerCase(),
      selector: "@" + ref
    };
    picker._lastPicked = result;
    stopPicker();
    if (typeof picker.onPick === "function") {
      try { picker.onPick(result); } catch (err) { /* swallow */ }
    }
    global.dispatchEvent(new CustomEvent("spel:pick", { detail: result }));
  }

  function pickerKey(e) {
    if (e.key === "Escape") stopPicker();
  }

  function startPicker(opts) {
    opts = opts || {};
    if (opts.onPick) picker.onPick = opts.onPick;
    if (picker.active) return true;
    picker.active = true;
    picker._lastPicked = null;
    ensureBox();
    document.addEventListener("mousemove", pickerMove, true);
    document.addEventListener("click", pickerClick, true);
    document.addEventListener("keydown", pickerKey, true);
    return true;
  }

  function stopPicker() {
    if (!picker.active) return false;
    picker.active = false;
    if (picker.box) picker.box.style.display = "none";
    document.removeEventListener("mousemove", pickerMove, true);
    document.removeEventListener("click", pickerClick, true);
    document.removeEventListener("keydown", pickerKey, true);
    return true;
  }

  function togglePicker() {
    return picker.active ? stopPicker() : startPicker();
  }

  function disconnect() {
    if (!activeConn) return false;
    try {
      if (activeConn.socket) activeConn.socket.close();
      if (activeConn.source) activeConn.source.close();
    } catch (e) { /* ignore */ }
    activeConn = null;
    return true;
  }

  function chooseServer() {
    var current = picker.server || "";
    var url = global.prompt
      ? global.prompt("spel server URL (ws:// or http://):", current)
      : current;
    if (url == null) return null; // cancelled
    url = String(url).trim();
    picker.server = url || null;
    if (picker.server) { disconnect(); connect({ url: picker.server }); }
    return picker.server;
  }

  function hotkeyMatches(e) {
    var h = picker.hotkey;
    return (!!h.ctrlKey === e.ctrlKey) &&
      (!!h.shiftKey === e.shiftKey) &&
      (!!h.altKey === !!e.altKey) &&
      (e.key.toLowerCase() === h.key.toLowerCase());
  }

  function serverHotkeyMatches(e) {
    var h = picker.serverHotkey;
    return (!!h.ctrlKey === e.ctrlKey) &&
      (!!h.shiftKey === e.shiftKey) &&
      (!!h.altKey === !!e.altKey) &&
      (e.key.toLowerCase() === h.key.toLowerCase());
  }

  function globalHotkey(e) {
    if (serverHotkeyMatches(e)) { e.preventDefault(); chooseServer(); return; }
    if (hotkeyMatches(e)) { e.preventDefault(); togglePicker(); }
  }

  // ---------------------------------------------------------------------------
  // Command handlers.
  // ---------------------------------------------------------------------------
  var HANDLERS = {
    // --- interaction ---
    click: function (c) { clickEl(must(c.selector)); return true; },
    dblclick: function (c) {
      var el = must(c.selector);
      clickEl(el);
      mouseEvent(el, "dblclick", centerOf(el));
      return true;
    },
    hover: function (c) {
      var el = must(c.selector);
      if (el.scrollIntoView) el.scrollIntoView({ block: "center" });
      var pt = centerOf(el);
      mouseEvent(el, "pointerover", pt);
      mouseEvent(el, "mouseover", pt);
      mouseEvent(el, "mousemove", pt);
      return true;
    },
    focus: function (c) { must(c.selector).focus(); return true; },
    fill: function (c) {
      var el = must(c.selector);
      el.focus();
      if (el.isContentEditable) { el.textContent = c.value; }
      else setNativeValue(el, c.value == null ? "" : String(c.value));
      fireInput(el);
      return el.value;
    },
    clear: function (c) {
      var el = must(c.selector);
      el.focus();
      setNativeValue(el, "");
      fireInput(el);
      return true;
    },
    type: function (c) {
      var el = must(c.selector);
      el.focus();
      typeText(el, String(c.text == null ? c.value : c.text));
      return el.value;
    },
    press: function (c) {
      var el = c.selector ? must(c.selector) : document.activeElement || document.body;
      pressKey(el, c.key);
      return true;
    },
    keyboard_type: function (c) {
      typeText(document.activeElement || document.body, String(c.text));
      return true;
    },
    keydown: function (c) {
      var p = parseKey(c.key);
      dispatchKey(document.activeElement || document.body, "keydown", p.spec, p.mods);
      return true;
    },
    keyup: function (c) {
      var p = parseKey(c.key);
      dispatchKey(document.activeElement || document.body, "keyup", p.spec, p.mods);
      return true;
    },
    check: function (c) {
      var el = must(c.selector);
      if (!el.checked) { el.checked = true; fireInput(el); }
      return true;
    },
    uncheck: function (c) {
      var el = must(c.selector);
      if (el.checked) { el.checked = false; fireInput(el); }
      return true;
    },
    select: function (c) {
      var el = must(c.selector);
      var values = Array.isArray(c.values) ? c.values : [c.values != null ? c.values : c.value];
      var selected = [];
      if (el.multiple) {
        for (var i = 0; i < el.options.length; i++) {
          var opt = el.options[i];
          opt.selected = values.indexOf(opt.value) !== -1 || values.indexOf(opt.label) !== -1;
          if (opt.selected) selected.push(opt.value);
        }
      } else {
        el.value = values[0];
        selected = el.value === values[0] ? [el.value] : [];
      }
      fireInput(el);
      return selected;
    },
    scrollintoview: function (c) {
      must(c.selector).scrollIntoView({ block: "center", inline: "center" });
      return true;
    },
    scroll: function (c) {
      var target = c.selector ? must(c.selector) : global;
      var amount = c.amount == null ? 300 : Number(c.amount);
      var dx = 0, dy = 0;
      switch ((c.direction || "down").toLowerCase()) {
        case "up": dy = -amount; break;
        case "down": dy = amount; break;
        case "left": dx = -amount; break;
        case "right": dx = amount; break;
      }
      if (target === global) global.scrollBy(dx, dy);
      else { target.scrollLeft += dx; target.scrollTop += dy; }
      return true;
    },
    drag: function (c) {
      var src = must(c.source);
      var dst = must(c.target);
      var a = centerOf(src), b = centerOf(dst);
      mouseEvent(src, "pointerdown", a);
      mouseEvent(src, "mousedown", a);
      mouseEvent(dst, "mousemove", b);
      mouseEvent(dst, "pointerup", b);
      mouseEvent(dst, "mouseup", b);
      return true;
    },
    highlight: function (c) {
      moveBox(must(c.selector));
      return true;
    },

    // --- queries ---
    get_text: function (c) { return (must(c.selector).textContent || "").trim(); },
    get_value: function (c) { return must(c.selector).value; },
    get_attribute: function (c) { return must(c.selector).getAttribute(c.name); },
    get_count: function (c) { return resolveAll(c.selector).length; },
    count: function (c) { return resolveAll(c.selector).length; },
    is_visible: function (c) { return isVisible(resolveOne(c.selector)); },
    is_enabled: function (c) {
      var el = must(c.selector);
      return !el.disabled && el.getAttribute("aria-disabled") !== "true";
    },
    is_checked: function (c) { return !!must(c.selector).checked; },
    bounding_box: function (c) {
      var r = must(c.selector).getBoundingClientRect();
      return { x: r.left, y: r.top, width: r.width, height: r.height };
    },
    get_box: function (c) { return HANDLERS.bounding_box(c); },
    get_styles: function (c) {
      var el = must(c.selector);
      var cs = global.getComputedStyle(el);
      var want = c.props || ["display", "color", "background-color", "font-size", "visibility"];
      var out = {};
      want.forEach(function (p) { out[p] = cs.getPropertyValue(p); });
      return out;
    },

    // --- checks / properties (Playwright parity) ---
    is_editable: function (c) {
      var el = must(c.selector);
      if (el.isContentEditable) return true;
      var tag = el.tagName;
      var editable = tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
      return editable && !el.disabled && !el.readOnly;
    },
    is_hidden: function (c) { return !isVisible(resolveOne(c.selector)); },
    is_disabled: function (c) {
      var el = must(c.selector);
      return !!el.disabled || el.getAttribute("aria-disabled") === "true";
    },
    is_focused: function (c) { return must(c.selector) === document.activeElement; },
    input_value: function (c) { return must(c.selector).value; },
    inner_text: function (c) { return must(c.selector).innerText; },
    inner_html: function (c) { return must(c.selector).innerHTML; },
    text_content: function (c) { return must(c.selector).textContent; },
    get_property: function (c) { return must(c.selector)[c.name]; },
    tag_name: function (c) { return must(c.selector).tagName.toLowerCase(); },
    get_role: function (c) { return roleOf(must(c.selector)); },
    get_accessible_name: function (c) { return accessibleName(must(c.selector)); },
    aria_snapshot: function (c) { return snapshot(c); },
    get_aria: function (c) {
      var el = must(c.selector), out = {};
      for (var i = 0; i < el.attributes.length; i++) {
        var at = el.attributes[i];
        if (at.name.indexOf("aria-") === 0 || at.name === "role") out[at.name] = at.value;
      }
      return out;
    },

    // --- overflow / clipping / geometry ---
    overflow_info: function (c) { return overflowInfo(must(c.selector)); },
    is_overflowing: function (c) { return overflowInfo(must(c.selector)).isOverflowing; },
    is_clipped: function (c) { return isClipped(must(c.selector)); },
    scroll_position: function (c) {
      if (c.selector) { var el = must(c.selector); return { x: el.scrollLeft, y: el.scrollTop }; }
      return { x: global.scrollX, y: global.scrollY };
    },
    viewport_size: function () { return { width: global.innerWidth, height: global.innerHeight }; },

    // --- waiting ---
    wait_for: function (c) {
      var sel = c.selector;
      var stateWanted = (c.state || "visible").toLowerCase();
      var timeout = c.timeout == null ? 5000 : Number(c.timeout);
      var start = Date.now();
      return new Promise(function (resolve, reject) {
        function poll() {
          var el = resolveOne(sel);
          var ok = false;
          if (stateWanted === "attached") ok = !!el;
          else if (stateWanted === "detached") ok = !el;
          else if (stateWanted === "visible") ok = !!el && isVisible(el);
          else if (stateWanted === "hidden") ok = !el || !isVisible(el);
          if (ok) { resolve({ matched: stateWanted, ref: el ? "@" + ensureRef(el) : null }); return; }
          if (Date.now() - start > timeout) {
            reject(new Error("wait_for timeout: " + sel + " state=" + stateWanted));
            return;
          }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },

    // --- page-level ---
    url: function () { return global.location.href; },
    title: function () { return document.title; },
    content: function () { return document.documentElement.outerHTML; },
    reload: function () { global.location.reload(); return true; },
    back: function () { global.history.back(); return true; },
    forward: function () { global.history.forward(); return true; },
    evaluate: function (c) {
      var script = c.base64 ? global.atob(c.script) : c.script;
      // Playwright-like: an expression (`document.title`), or a multi-statement
      // function body with an explicit `return`. Try the expression form first,
      // fall back to a statement body on a syntax error.
      // eslint-disable-next-line no-new-func
      var fn;
      try { fn = Function('"use strict"; return (' + script + "\n);"); }
      catch (e) { fn = Function('"use strict";' + script); }
      return fn();
    },

    // --- snapshot / refs ---
    snapshot: function (c) { return snapshot(c); },
    resolve_ref: function (c) {
      var el = resolveOne(c.selector || ("@" + c.ref));
      return el ? { ref: c.ref || c.selector, found: true, tag: el.tagName.toLowerCase() } : { found: false };
    },
    clear_refs: function () { return clearRefs(); },
    find: function (c) {
      // getBy* family: by ∈ role|text|label|placeholder|testid|alt|title
      var by = (c.by || "role").toLowerCase();
      var val = c.value;
      var matches = [];
      var all = document.querySelectorAll("*");
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        var ok = false;
        switch (by) {
          case "role":
            ok = roleOf(el) === val &&
              (c.name == null || accessibleName(el) === c.name ||
                (!c.exact && accessibleName(el).indexOf(c.name) !== -1));
            break;
          case "text":
            ok = c.exact ? (el.textContent || "").trim() === val
              : (el.textContent || "").indexOf(val) !== -1;
            ok = ok && el.children.length === 0;
            break;
          case "label": {
            var nm = accessibleName(el);
            ok = (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.tagName === "SELECT") &&
              (c.exact ? nm === val : nm.indexOf(val) !== -1);
            break;
          }
          case "placeholder":
            ok = el.getAttribute("placeholder") === val;
            break;
          case "testid":
            ok = el.getAttribute("data-testid") === val;
            break;
          case "alt":
            ok = el.getAttribute("alt") === val;
            break;
          case "title":
            ok = el.getAttribute("title") === val;
            break;
        }
        if (ok) matches.push(el);
      }
      var els = matches.map(function (el) {
        return { ref: "@" + ensureRef(el), role: roleOf(el), name: accessibleName(el), tag: el.tagName.toLowerCase() };
      });
      return { count: els.length, elements: els, ref: els[0] ? els[0].ref : null };
    },

    // --- storage ---
    storage_get: function (c) {
      var store = c.session ? global.sessionStorage : global.localStorage;
      return c.key ? store.getItem(c.key) : (function () {
        var out = {};
        for (var i = 0; i < store.length; i++) { var k = store.key(i); out[k] = store.getItem(k); }
        return out;
      })();
    },
    storage_set: function (c) {
      (c.session ? global.sessionStorage : global.localStorage).setItem(c.key, c.value);
      return true;
    },
    storage_clear: function (c) {
      (c.session ? global.sessionStorage : global.localStorage).clear();
      return true;
    },

    // --- overlay picker ---
    pick: function (c) { return startPicker(c); },
    pick_stop: function () { return stopPicker(); },
    pick_toggle: function () { return togglePicker(); },
    picked: function () { return picker._lastPicked; },
    configure: function (c) {
      if (c.hotkey) picker.hotkey = c.hotkey;
      if (c.serverHotkey) picker.serverHotkey = c.serverHotkey;
      if (c.server !== undefined) picker.server = c.server;
      return { hotkey: picker.hotkey, serverHotkey: picker.serverHotkey, server: picker.server };
    },

    // --- server / two-way transport ---
    set_server: function (c) { picker.server = c.server || c.url || null; return picker.server; },
    get_server: function () { return picker.server; },
    choose_server: function () { return chooseServer(); },
    connect: function (c) { connect(c || {}); return { connected: true, server: picker.server }; },
    disconnect: function () { return disconnect(); },
    is_connected: function () { return !!activeConn; },

    // --- meta ---
    ping: function () { return "pong"; },
    ready: function () { return true; }
  };

  // Normalize verb names: accept "drag-by", "pick_stop", etc.
  function normalizeAction(a) {
    return String(a || "").replace(/-/g, "_").toLowerCase();
  }

  // ---------------------------------------------------------------------------
  // The single entry point.
  // ---------------------------------------------------------------------------
  function invoke(command) {
    return Promise.resolve().then(function () {
      if (!command || typeof command !== "object") {
        throw new Error("invoke expects a command object");
      }
      var action = normalizeAction(command.action);
      var handler = HANDLERS[action];
      if (!handler) throw new Error("unknown action: " + command.action);
      return Promise.resolve(handler(command));
    }).then(function (value) {
      return { action: command && command.action, ok: true, value: value };
    }).catch(function (err) {
      return { action: command && command.action, ok: false, error: String(err && err.message || err) };
    });
  }

  // ---------------------------------------------------------------------------
  // Optional daemon transport (two-way): WebSocket first, SSE + POST fallback.
  // ---------------------------------------------------------------------------
  function connect(opts) {
    opts = opts || {};
    var base = opts.url || picker.server || "ws://127.0.0.1:9223/spel";
    if (/^wss?:/.test(base) && global.WebSocket) {
      var ws = new global.WebSocket(base);
      ws.addEventListener("open", function () {
        ws.send(JSON.stringify({ type: "hello", version: api.version, url: global.location.href }));
      });
      ws.addEventListener("message", function (ev) {
        var msg = JSON.parse(ev.data);
        invoke(msg).then(function (res) {
          res.id = msg.id;
          ws.send(JSON.stringify(res));
        });
      });
      activeConn = { transport: "websocket", socket: ws };
      return activeConn;
    }
    // SSE for inbound commands, fetch POST for outbound results.
    var resultUrl = opts.resultUrl || base + "/result";
    var es = new global.EventSource(base);
    es.addEventListener("message", function (ev) {
      var msg = JSON.parse(ev.data);
      invoke(msg).then(function (res) {
        res.id = msg.id;
        global.fetch(resultUrl, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(res)
        });
      });
    });
    activeConn = { transport: "sse", source: es };
    return activeConn;
  }

  // ---------------------------------------------------------------------------
  // Install.
  // ---------------------------------------------------------------------------
  var api = {
    __installed: true,
    version: "0.2.0",
    invoke: invoke,
    connect: connect,
    disconnect: disconnect,
    snapshot: snapshot,
    resolveOne: resolveOne,
    resolveAll: resolveAll,
    startPicker: startPicker,
    stopPicker: stopPicker,
    chooseServer: chooseServer,
    picker: picker,
    connection: function () { return activeConn; },
    handlers: HANDLERS
  };

  if (document && document.addEventListener) {
    document.addEventListener("keydown", globalHotkey, true);
  }

  global.__spel = api;
  return api;
})(typeof window !== "undefined" ? window : this);
