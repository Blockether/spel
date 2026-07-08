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
  // Network capture — pure in-page, no CDP.
  //   fetch + XMLHttpRequest are wrapped to record method/url/status/headers/
  //   bodies + timing; a PerformanceObserver picks up passive resources
  //   (img/script/css/beacon) that never travel through fetch/XHR.
  //
  //   DOCUMENTED LIMITS vs the CDP-based daemon (`network_list`):
  //     * only traffic AFTER this script loads is seen (install early);
  //     * cross-origin response headers are limited to the CORS-exposed set;
  //     * opaque (no-cors) response bodies are unreadable -> null;
  //     * wire `size` is best-effort (Resource Timing transferSize, else null);
  //     * request/response bodies are capped at NET_BODY_CAP bytes.
  // ---------------------------------------------------------------------------
  var NET_MAX = 500; // sliding-window cap (entries)
  var NET_BODY_CAP = 65536; // per-body char cap kept in the full store
  var netWindow = []; // light entries (no bodies)
  var netFull = {}; // ref (no @) -> full entry (headers + bodies)
  var netCounter = 0;
  var netInstalled = false;

  function netCap(s) {
    if (s == null) return s;
    s = String(s);
    return s.length > NET_BODY_CAP ? s.slice(0, NET_BODY_CAP) + "\u2026[truncated]" : s;
  }

  function netHeadersToObj(h) {
    var out = {};
    try {
      if (h && typeof h.forEach === "function") {
        h.forEach(function (v, k) { out[String(k).toLowerCase()] = v; });
      } else if (typeof h === "string") {
        h.trim().split(/[\r\n]+/).forEach(function (line) {
          var i = line.indexOf(":");
          if (i > 0) out[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
        });
      } else if (h && typeof h === "object") {
        for (var k in h) {
          if (Object.prototype.hasOwnProperty.call(h, k)) out[String(k).toLowerCase()] = h[k];
        }
      }
    } catch (e) {}
    return out;
  }

  function netNow() {
    return (global.performance && global.performance.now) ? global.performance.now() : Date.now();
  }

  function netSizeFor(url) {
    try {
      var perf = global.performance;
      if (!perf || !perf.getEntriesByName) return null;
      var es = perf.getEntriesByName(url, "resource");
      if (es && es.length) {
        var e = es[es.length - 1];
        return {
          size: e.transferSize || e.encodedBodySize || null,
          fromCache: e.transferSize === 0 && e.decodedBodySize > 0
        };
      }
    } catch (e) {}
    return null;
  }

  function netRecord(entry) {
    netCounter += 1;
    var id = "n" + netCounter;
    entry.ref = "@" + id;
    netFull[id] = entry;
    netWindow.push({
      ref: entry.ref, url: entry.url, method: entry.method,
      status: entry.status, statusText: entry.statusText,
      resourceType: entry.resourceType, ok: entry.ok,
      fromCache: entry.fromCache, size: entry.size, mocked: entry.mocked,
      startTime: entry.startTime, duration: entry.duration,
      error: entry.error
    });
    if (netWindow.length > NET_MAX) {
      var dropped = netWindow.shift();
      if (dropped && dropped.ref) delete netFull[dropped.ref.replace(/^@/, "")];
    }
    return entry;
  }

  // ---------------------------------------------------------------------------
  // Request routing / mocking — page.route() equivalent for fetch + XHR.
  //   Pure in-page: only requests issued through window.fetch / XMLHttpRequest
  //   can be short-circuited (fulfill/abort). Passive resources (img/script/css
  //   pulled by the browser loader) are NOT interceptable without CDP.
  // ---------------------------------------------------------------------------
  var routes = [];

  function urlMatch(pattern, url) {
    if (pattern == null || pattern === "" || pattern === "*" || pattern === "**") return true;
    url = String(url);
    if (String(pattern).indexOf("*") !== -1) {
      var re = new RegExp(
        "^" + String(pattern).replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*") + "$"
      );
      return re.test(url);
    }
    return url.indexOf(pattern) !== -1;
  }

  function matchRoute(url, method) {
    for (var i = 0; i < routes.length; i++) {
      var r = routes[i];
      if (r.method && String(r.method).toUpperCase() !== String(method || "GET").toUpperCase()) continue;
      if (urlMatch(r.url, url)) return r;
    }
    return null;
  }

  function wrapFetch() {
    var orig = global.fetch;
    if (typeof orig !== "function" || orig.__spelWrapped) return;
    function wrapped(input, init) {
      var start = netNow();
      var url = (typeof input === "string") ? input : (input && input.url) || String(input);
      var method = (init && init.method) || (input && input.method) || "GET";
      var reqHeaders = netHeadersToObj((init && init.headers) || (input && input.headers));
      var reqBody = (init && init.body != null)
        ? netCap(typeof init.body === "string" ? init.body : "[non-text body]") : null;
      var mock = matchRoute(url, method);
      if (mock) {
        return Promise.resolve().then(function () {
          if ((mock.action || "fulfill") === "abort") {
            netRecord({
              url: url, method: String(method).toUpperCase(), status: 0,
              statusText: "aborted by route", resourceType: "fetch", ok: false,
              requestHeaders: reqHeaders, requestBody: reqBody,
              responseHeaders: {}, responseBody: null, fromCache: false, mocked: true,
              error: "aborted by route", startTime: Math.round(start),
              duration: Math.round(netNow() - start), size: null
            });
            throw new Error("aborted by route: " + url);
          }
          var status = mock.status || 200;
          var body = mock.body == null ? ""
            : (typeof mock.body === "string" ? mock.body : JSON.stringify(mock.body));
          var headers = mock.headers || { "content-type": mock.contentType || "application/json" };
          netRecord({
            url: url, method: String(method).toUpperCase(),
            status: status, statusText: mock.statusText || "OK",
            resourceType: "fetch", ok: status >= 200 && status < 400,
            requestHeaders: reqHeaders, requestBody: reqBody,
            responseHeaders: netHeadersToObj(headers), responseBody: netCap(body),
            fromCache: false, mocked: true, startTime: Math.round(start),
            duration: Math.round(netNow() - start), size: body.length
          });
          return new global.Response(body, {
            status: status, statusText: mock.statusText || "", headers: headers
          });
        });
      }
      return orig.apply(this, arguments).then(function (resp) {
        var entry = netRecord({
          url: url, method: String(method).toUpperCase(),
          status: resp.status, statusText: resp.statusText,
          resourceType: "fetch", ok: resp.ok,
          requestHeaders: reqHeaders, requestBody: reqBody,
          responseHeaders: netHeadersToObj(resp.headers), responseBody: null,
          fromCache: false, startTime: Math.round(start),
          duration: Math.round(netNow() - start), size: null
        });
        var meta = netSizeFor(url);
        if (meta) { entry.size = meta.size; entry.fromCache = meta.fromCache; }
        try {
          resp.clone().text().then(function (t) { entry.responseBody = netCap(t); }, function () {});
        } catch (e) {}
        return resp;
      }, function (err) {
        netRecord({
          url: url, method: String(method).toUpperCase(), status: 0,
          statusText: "network error", resourceType: "fetch", ok: false,
          requestHeaders: reqHeaders, requestBody: reqBody,
          responseHeaders: {}, responseBody: null, fromCache: false,
          error: String((err && err.message) || err),
          startTime: Math.round(start), duration: Math.round(netNow() - start), size: null
        });
        throw err;
      });
    }
    wrapped.__spelWrapped = true;
    global.fetch = wrapped;
  }

  function wrapXHR() {
    var XHR = global.XMLHttpRequest;
    if (!XHR || !XHR.prototype || XHR.prototype.__spelWrapped) return;
    var open = XHR.prototype.open;
    var send = XHR.prototype.send;
    var setHeader = XHR.prototype.setRequestHeader;
    XHR.prototype.open = function (method, url) {
      this.__spelNet = { method: String(method || "GET").toUpperCase(), url: String(url), reqHeaders: {} };
      return open.apply(this, arguments);
    };
    XHR.prototype.setRequestHeader = function (k, v) {
      if (this.__spelNet) this.__spelNet.reqHeaders[String(k).toLowerCase()] = v;
      return setHeader.apply(this, arguments);
    };
    XHR.prototype.send = function (body) {
      var self = this, info = this.__spelNet;
      if (info) {
        var start = netNow();
        info.reqBody = body != null ? netCap(typeof body === "string" ? body : "[non-text body]") : null;
        this.addEventListener("loadend", function () {
          var respBody = null;
          try {
            if (self.responseType === "" || self.responseType === "text") respBody = netCap(self.responseText);
          } catch (e) {}
          var entry = netRecord({
            url: info.url, method: info.method,
            status: self.status, statusText: self.statusText,
            resourceType: "xhr", ok: self.status >= 200 && self.status < 400,
            requestHeaders: info.reqHeaders, requestBody: info.reqBody,
            responseHeaders: netHeadersToObj(self.getAllResponseHeaders && self.getAllResponseHeaders()),
            responseBody: respBody, fromCache: false,
            startTime: Math.round(start), duration: Math.round(netNow() - start), size: null
          });
          var meta = netSizeFor(info.url);
          if (meta) { entry.size = meta.size; entry.fromCache = meta.fromCache; }
        });
      }
      return send.apply(this, arguments);
    };
    XHR.prototype.__spelWrapped = true;
  }

  function wrapPerf() {
    try {
      if (!global.PerformanceObserver) return;
      var obs = new global.PerformanceObserver(function (list) {
        var ents = list.getEntries();
        for (var i = 0; i < ents.length; i++) {
          var e = ents[i], it = e.initiatorType;
          if (it === "xmlhttprequest" || it === "fetch") continue; // covered by wrappers
          netRecord({
            url: e.name, method: "GET", status: null, statusText: null,
            resourceType: it || "resource", ok: true,
            requestHeaders: {}, requestBody: null, responseHeaders: {}, responseBody: null,
            size: e.transferSize || e.encodedBodySize || null,
            fromCache: e.transferSize === 0 && e.decodedBodySize > 0,
            startTime: Math.round(e.startTime), duration: Math.round(e.duration)
          });
        }
      });
      obs.observe({ type: "resource", buffered: true });
    } catch (e) {}
  }

  function installNetwork() {
    if (netInstalled) return false;
    netInstalled = true;
    wrapFetch();
    wrapXHR();
    wrapPerf();
    return true;
  }

  function netMatches(e, c) {
    if (c.filter && String(e.url).indexOf(c.filter) < 0) return false;
    if (c.type && e.resourceType !== c.type) return false;
    if (c.method && String(e.method).toUpperCase() !== String(c.method).toUpperCase()) return false;
    if (c.status != null && e.status !== Number(c.status)) return false;
    if (c.failed && !(e.status === 0 || (e.status >= 400))) return false;
    return true;
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
    if (typeof selector === "string" && selector.indexOf("frame=") === 0) {
      var fsep = selector.indexOf(">>");
      var frameSel = (fsep < 0 ? selector.slice(6) : selector.slice(6, fsep)).trim();
      var innerSel = fsep < 0 ? "" : selector.slice(fsep + 2).trim();
      var frameEl = resolveOne(frameSel, root);
      if (!frameEl) return null;
      var fdoc;
      try { fdoc = frameEl.contentDocument; } catch (e) { fdoc = null; }
      if (!fdoc) throw new Error("frame not accessible (cross-origin?): " + frameSel);
      return innerSel ? resolveOne(innerSel, fdoc) : frameEl;
    }
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
    if (typeof selector === "string" && selector.indexOf("frame=") === 0) {
      var afsep = selector.indexOf(">>");
      var aFrameSel = (afsep < 0 ? selector.slice(6) : selector.slice(6, afsep)).trim();
      var aInner = afsep < 0 ? "" : selector.slice(afsep + 2).trim();
      var aFrame = resolveOne(aFrameSel, root);
      if (!aFrame) return [];
      var afdoc;
      try { afdoc = aFrame.contentDocument; } catch (e) { afdoc = null; }
      if (!afdoc) return [];
      return aInner ? resolveAll(aInner, afdoc) : [aFrame];
    }
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

  var SPEL_SLATE = "#2D4552";
  var SPEL_SERIF =
    "'Iowan Old Style','Palatino Linotype','Palatino',Georgia,serif";

  function escHtml(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
    });
  }

  function spelEl(css) {
    var d = document.createElement("div");
    d.setAttribute("data-spel-overlay", "1");
    d.style.cssText = css;
    return d;
  }

  // Inject the theatrical-brand keyframes once (breathing green glow + blink).
  function ensureStyle() {
    if (picker.styleEl || !document.head) return;
    var s = document.createElement("style");
    s.setAttribute("data-spel-overlay", "1");
    s.textContent =
      "@keyframes spel-breathe{" +
      "0%,100%{box-shadow:0 0 0 1px rgba(46,173,51,.35)," +
      "0 6px 20px rgba(46,173,51,.22),inset 0 0 10px rgba(46,173,51,.10)}" +
      "50%{box-shadow:0 0 0 1px rgba(46,173,51,.55)," +
      "0 10px 30px rgba(46,173,51,.40),inset 0 0 16px rgba(46,173,51,.18)}}" +
      "@keyframes spel-blink{0%,100%{opacity:1}50%{opacity:.35}}";
    document.head.appendChild(s);
    picker.styleEl = s;
  }

  function ensureBox() {
    if (picker.box) return picker.box;
    ensureStyle();
    // Highlight box — tragedy-green, rounded, softly breathing glow.
    var box = spelEl([
      "position:fixed", "z-index:2147483646", "pointer-events:none",
      "background:rgba(46,173,51,0.14)",
      "border:1.5px solid rgba(46,173,51,0.92)", "border-radius:6px",
      "transition:all 55ms cubic-bezier(.4,0,.2,1)",
      "animation:spel-breathe 1.7s ease-in-out infinite", "display:none"
    ].join(";"));
    document.documentElement.appendChild(box);
    picker.box = box;

    // Floating label chip — role / accessible name / size (Playwright-style).
    var label = spelEl([
      "position:fixed", "z-index:2147483647", "pointer-events:none",
      "display:none", "max-width:360px", "padding:5px 9px",
      "border-radius:6px",
      "background:linear-gradient(180deg," + SPEL_SLATE + ",#22333d)",
      "color:#eaf6ea", "font:600 12px/1.35 " + SPEL_SERIF,
      "box-shadow:0 6px 20px rgba(45,69,82,0.42)", "white-space:nowrap",
      "overflow:hidden", "text-overflow:ellipsis",
      "border:1px solid rgba(46,173,51,0.55)"
    ].join(";"));
    document.documentElement.appendChild(label);
    picker.label = label;

    // HUD pill — theatrical masks + status, centred at the top while picking.
    var hud = spelEl([
      "position:fixed", "top:16px", "left:50%",
      "transform:translateX(-50%)", "z-index:2147483647",
      "pointer-events:none", "display:none", "align-items:center",
      "gap:8px", "padding:7px 15px", "border-radius:999px",
      "background:linear-gradient(180deg," + SPEL_SLATE + ",#1f2e36)",
      "color:#f4faf4", "font:600 12.5px/1 " + SPEL_SERIF,
      "letter-spacing:.3px",
      "box-shadow:0 10px 34px rgba(45,69,82,0.5)," +
      "0 0 0 1px rgba(46,173,51,0.45)"
    ].join(";"));
    hud.innerHTML =
      '<span style="animation:spel-blink 1.2s ease-in-out infinite;' +
      'font-size:14px">\uD83C\uDFAD</span>' +
      '<span style="color:#7fe08a">spel picker</span>' +
      '<span style="opacity:.72;font-weight:500">click to select ' +
      '\u00b7 Esc to cancel</span>';
    document.documentElement.appendChild(hud);
    picker.hud = hud;
    return box;
  }

  // Fill + position the label chip above (or below, near the top) an element.
  function showLabel(el) {
    if (!picker.label) return;
    var lb = picker.label, r = el.getBoundingClientRect();
    var role = roleOf(el) || el.tagName.toLowerCase();
    var name = accessibleName(el) || "";
    lb.innerHTML =
      '<span style="color:#7fe08a;font-weight:700">' + escHtml(role) +
      '</span>' +
      (name
        ? '<span style="opacity:.95"> \u201c' +
          escHtml(name.slice(0, 60)) + '\u201d</span>'
        : '') +
      '<span style="opacity:.6;font-weight:500"> ' +
      Math.round(r.width) + '\u00d7' + Math.round(r.height) + '</span>';
    lb.style.display = "block";
    var lh = lb.offsetHeight || 24;
    var top = r.top - lh - 6;
    if (top < 4) top = r.bottom + 6;
    lb.style.top = top + "px";
    lb.style.left = (r.left < 4 ? 4 : r.left) + "px";
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
    if (el && el !== picker.box && !el.hasAttribute("data-spel-overlay")) {
      moveBox(el);
      showLabel(el);
    }
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
    if (picker.hud) picker.hud.style.display = "flex";
    picker._prevCursor = document.body ? document.body.style.cursor : "";
    if (document.body) document.body.style.cursor = "crosshair";
    document.addEventListener("mousemove", pickerMove, true);
    document.addEventListener("click", pickerClick, true);
    document.addEventListener("keydown", pickerKey, true);
    return true;
  }

  function stopPicker() {
    if (!picker.active) return false;
    picker.active = false;
    if (picker.box) picker.box.style.display = "none";
    if (picker.label) picker.label.style.display = "none";
    if (picker.hud) picker.hud.style.display = "none";
    if (document.body) document.body.style.cursor = picker._prevCursor || "";
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
    forgetConnect();
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
  // Dialogs — alert/confirm/prompt/beforeunload (page.on("dialog") equivalent).
  //   Native modal dialogs freeze automation, so we replace them with
  //   non-blocking stand-ins that follow a policy and record every occurrence.
  // ---------------------------------------------------------------------------
  var dialogLog = [];
  var dialogPolicy = { action: "accept", promptText: null };
  var dialogsInstalled = false;

  function recordDialog(type, message, dflt) {
    var accept = dialogPolicy.action !== "dismiss";
    var entry = {
      type: type, message: message == null ? "" : String(message),
      defaultValue: dflt == null ? null : String(dflt),
      accepted: accept, at: Date.now()
    };
    dialogLog.push(entry);
    if (dialogLog.length > 200) dialogLog.shift();
    return entry;
  }

  function installDialogs() {
    if (dialogsInstalled) return true;
    global.alert = function (msg) { recordDialog("alert", msg, null); };
    global.confirm = function (msg) { return recordDialog("confirm", msg, null).accepted; };
    global.prompt = function (msg, dflt) {
      var e = recordDialog("prompt", msg, dflt);
      if (!e.accepted) return null;
      return dialogPolicy.promptText != null ? dialogPolicy.promptText : (dflt == null ? "" : dflt);
    };
    global.addEventListener("beforeunload", function () { recordDialog("beforeunload", "", null); }, true);
    dialogsInstalled = true;
    return true;
  }

  // ---------------------------------------------------------------------------
  // Console + page errors (page.on("console") / page.on("pageerror")).
  // ---------------------------------------------------------------------------
  var consoleLog = [];
  var consoleInstalled = false;

  function recordConsole(level, args) {
    var text;
    try {
      text = Array.prototype.map.call(args, function (a) {
        if (typeof a === "string") return a;
        try { return JSON.stringify(a); } catch (e) { return String(a); }
      }).join(" ");
    } catch (e2) { text = String(args); }
    consoleLog.push({ type: level, text: text, at: Date.now() });
    if (consoleLog.length > 500) consoleLog.shift();
  }

  function installConsole() {
    if (consoleInstalled) return true;
    var c = global.console || (global.console = {});
    ["log", "info", "warn", "error", "debug"].forEach(function (level) {
      var orig = c[level];
      c[level] = function () {
        recordConsole(level, arguments);
        if (typeof orig === "function") return orig.apply(c, arguments);
      };
    });
    global.addEventListener("error", function (ev) {
      recordConsole("pageerror", [ev && ev.message ? ev.message : String(ev),
        ev && ev.filename ? (ev.filename + ":" + ev.lineno) : ""]);
    }, true);
    global.addEventListener("unhandledrejection", function (ev) {
      recordConsole("pageerror", ["unhandledrejection:", ev && ev.reason ? String(ev.reason) : ""]);
    }, true);
    consoleInstalled = true;
    return true;
  }

  // ---------------------------------------------------------------------------
  // Cookies — document.cookie (httpOnly + Secure-only cookies stay invisible).
  // ---------------------------------------------------------------------------
  function parseCookies() {
    var out = {};
    String(document.cookie || "").split(";").forEach(function (p) {
      var i = p.indexOf("=");
      if (i < 0) return;
      var k = p.slice(0, i).trim();
      if (k) {
        try { out[k] = decodeURIComponent(p.slice(i + 1).trim()); }
        catch (e) { out[k] = p.slice(i + 1).trim(); }
      }
    });
    return out;
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

    // --- network (in-page capture; see installNetwork) ---
    network_list: function (c) {
      c = c || {};
      return { entries: netWindow.filter(function (e) { return netMatches(e, c); }) };
    },
    network_requests: function (c) { return HANDLERS.network_list(c); },
    network_get: function (c) {
      var id = String((c && (c.ref || c.selector)) || "").replace(/^@/, "");
      var e = netFull[id];
      return e || { error: "network ref @" + id + " not found" };
    },
    network_get_ref: function (c) { return HANDLERS.network_get(c); },
    network_clear: function () {
      var n = netWindow.length;
      netWindow.length = 0;
      netFull = {};
      netCounter = 0;
      return { cleared: n };
    },
    network_capture: function () { return { installed: installNetwork(), capturing: netInstalled }; },

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

    // --- dialogs ---
    dialog_handler: function (c) {
      c = c || {};
      if (c.policy) dialogPolicy.action = String(c.policy).toLowerCase();
      if (c.promptText !== undefined) dialogPolicy.promptText = c.promptText;
      installDialogs();
      return { action: dialogPolicy.action, promptText: dialogPolicy.promptText };
    },
    dialogs: function () { installDialogs(); return { entries: dialogLog.slice() }; },
    dialogs_clear: function () { var n = dialogLog.length; dialogLog.length = 0; return { cleared: n }; },

    // --- console / page errors ---
    console_capture: function () { return { installed: installConsole() }; },
    console_list: function (c) {
      c = c || {};
      var out = consoleLog;
      if (c.level) out = out.filter(function (e) { return e.type === String(c.level).toLowerCase(); });
      return { entries: out.slice() };
    },
    console_clear: function () { var n = consoleLog.length; consoleLog.length = 0; return { cleared: n }; },

    // --- navigation to an arbitrary URL (reinject rides in sessionStorage) ---
    goto: function (c) {
      var url = c.url || c.value;
      if (!url) throw new Error("goto requires a url");
      global.location.href = url;
      return { navigating: url };
    },
    navigate: function (c) { return HANDLERS.goto(c); },

    // --- cookies ---
    cookies: function (c) {
      var jar = parseCookies();
      if (c && c.name) return jar[c.name] != null ? [{ name: c.name, value: jar[c.name] }] : [];
      return Object.keys(jar).map(function (k) { return { name: k, value: jar[k] }; });
    },
    set_cookie: function (c) {
      var s = encodeURIComponent(c.name) + "=" + encodeURIComponent(c.value == null ? "" : String(c.value));
      s += "; path=" + (c.path || "/");
      if (c.domain) s += "; domain=" + c.domain;
      if (c.maxAge != null) s += "; max-age=" + c.maxAge;
      if (c.expires) s += "; expires=" + c.expires;
      if (c.sameSite) s += "; samesite=" + c.sameSite;
      if (c.secure) s += "; secure";
      document.cookie = s;
      return true;
    },
    add_cookie: function (c) { return HANDLERS.set_cookie(c); },
    clear_cookies: function () {
      var jar = parseCookies(), n = 0;
      Object.keys(jar).forEach(function (k) {
        document.cookie = encodeURIComponent(k) + "=; path=/; max-age=0";
        document.cookie = encodeURIComponent(k) + "=; max-age=0";
        n += 1;
      });
      return { cleared: n };
    },

    // --- waiting (Playwright wait_for* family) ---
    wait_for_timeout: function (c) {
      var ms = Number((c && (c.timeout != null ? c.timeout : c.ms)) || 0);
      return new Promise(function (resolve) { global.setTimeout(function () { resolve(true); }, ms); });
    },
    wait_for_function: function (c) {
      var expr = c.base64 ? global.atob(c.script || c.expression) : (c.script || c.expression);
      var timeout = c.timeout == null ? 5000 : Number(c.timeout);
      var start = Date.now();
      var fn = Function('"use strict"; return (' + expr + "\n);");
      return new Promise(function (resolve, reject) {
        function poll() {
          var v;
          try { v = fn(); } catch (e) { v = undefined; }
          if (v) { resolve(typeof v === "object" ? true : v); return; }
          if (Date.now() - start > timeout) { reject(new Error("wait_for_function timeout")); return; }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },
    wait_for_url: function (c) {
      var want = c.url || c.value || "";
      var timeout = c.timeout == null ? 5000 : Number(c.timeout);
      var start = Date.now();
      return new Promise(function (resolve, reject) {
        function poll() {
          if (urlMatch(want, global.location.href)) { resolve(global.location.href); return; }
          if (Date.now() - start > timeout) { reject(new Error("wait_for_url timeout: " + want)); return; }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },
    wait_for_load_state: function (c) {
      var want = (c && c.state ? c.state : "load").toLowerCase();
      var timeout = c && c.timeout != null ? Number(c.timeout) : 5000;
      var start = Date.now();
      return new Promise(function (resolve, reject) {
        function done() {
          var rs = document.readyState;
          if (want === "domcontentloaded") return rs === "interactive" || rs === "complete";
          return rs === "complete";
        }
        function poll() {
          if (done()) { resolve(document.readyState); return; }
          if (Date.now() - start > timeout) { reject(new Error("wait_for_load_state timeout")); return; }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },
    wait_for_response: function (c) {
      installNetwork();
      var want = c.url || c.value || "";
      var timeout = c.timeout == null ? 5000 : Number(c.timeout);
      var start = Date.now(), base = netCounter;
      return new Promise(function (resolve, reject) {
        function poll() {
          for (var i = netWindow.length - 1; i >= 0; i--) {
            var e = netWindow[i];
            var num = parseInt(String(e.ref).replace(/[^0-9]/g, ""), 10);
            if (num > base && e.status != null && urlMatch(want, e.url)) { resolve(e); return; }
          }
          if (Date.now() - start > timeout) { reject(new Error("wait_for_response timeout: " + want)); return; }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },
    wait_for_request: function (c) {
      installNetwork();
      var want = c.url || c.value || "";
      var timeout = c.timeout == null ? 5000 : Number(c.timeout);
      var start = Date.now(), base = netCounter;
      return new Promise(function (resolve, reject) {
        function poll() {
          for (var i = netWindow.length - 1; i >= 0; i--) {
            var e = netWindow[i];
            var num = parseInt(String(e.ref).replace(/[^0-9]/g, ""), 10);
            if (num > base && urlMatch(want, e.url)) { resolve(e); return; }
          }
          if (Date.now() - start > timeout) { reject(new Error("wait_for_request timeout: " + want)); return; }
          global.setTimeout(poll, 50);
        }
        poll();
      });
    },

    // --- request routing / mocking (fetch + XHR only) ---
    route: function (c) {
      installNetwork();
      routes.push({
        url: c.url || c.pattern || "*", method: c.method || null,
        action: c.abort ? "abort" : "fulfill",
        status: c.status, statusText: c.statusText,
        body: c.body, headers: c.headers, contentType: c.contentType
      });
      return { routes: routes.length };
    },
    unroute: function (c) {
      if (c && (c.url || c.pattern)) {
        var pat = c.url || c.pattern;
        routes = routes.filter(function (r) { return r.url !== pat; });
      } else {
        routes.length = 0;
      }
      return { routes: routes.length };
    },
    routes: function () { return { routes: routes.slice() }; },

    // --- file uploads (set_input_files) ---
    upload: function (c) {
      var el = must(c.selector);
      var files = c.files || c.value || [];
      if (!Array.isArray(files)) files = [files];
      var dt = new global.DataTransfer();
      files.forEach(function (f) {
        var name = (f && f.name) || (typeof f === "string" ? f : "file");
        var mime = (f && (f.mimeType || f.type)) || "text/plain";
        var content = f && f.content != null ? f.content : "";
        if (f && f.base64) { try { content = global.atob(content); } catch (e) {} }
        var blob = new global.Blob([content], { type: mime });
        dt.items.add(new global.File([blob], name, { type: mime }));
      });
      el.files = dt.files;
      fireInput(el);
      el.dispatchEvent(new global.Event("change", { bubbles: true }));
      return { files: Array.prototype.map.call(el.files, function (x) { return x.name; }) };
    },
    set_input_files: function (c) { return HANDLERS.upload(c); },

    // --- touch / generic events ---
    tap: function (c) {
      var el = must(c.selector);
      var pt = centerOf(el);
      mouseEvent(el, "pointerdown", pt);
      mouseEvent(el, "pointerup", pt);
      clickEl(el);
      return true;
    },
    dispatch_event: function (c) {
      var el = must(c.selector);
      var type = c.type || c.event;
      if (!type) throw new Error("dispatch_event requires a type");
      var init = c.init || c.eventInit || { bubbles: true, cancelable: true };
      var ev;
      try { ev = new global.Event(type, init); }
      catch (e) { ev = document.createEvent("Event"); ev.initEvent(type, !!init.bubbles, !!init.cancelable); }
      el.dispatchEvent(ev);
      return true;
    },

    // --- frames (same-origin) ---
    frames: function () {
      var out = [], ifr = document.querySelectorAll("iframe,frame");
      for (var i = 0; i < ifr.length; i++) {
        var f = ifr[i], same = false;
        try { same = !!f.contentDocument; } catch (e) { same = false; }
        out.push({ ref: "@" + ensureRef(f), src: f.getAttribute("src"),
          name: f.getAttribute("name"), sameOrigin: same });
      }
      return { count: out.length, frames: out };
    },

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
  var CONNECT_KEY = "__spel_connect";

  // Append the shared secret as a query param so the bridge can authorize this
  // tab. EventSource cannot set headers, so the token has to ride in the URL.
  function connWith(u, token) {
    if (!token) return u;
    return u + (u.indexOf("?") < 0 ? "?" : "&") + "t=" + encodeURIComponent(token);
  }

  // Remember the route (url + token) for THIS tab so a full-page navigation to
  // another same-origin page that re-loads spel.js can auto-reconnect. Scoped
  // to sessionStorage: per-tab, per-origin, cleared when the tab closes.
  function persistConnect(cfg) {
    try { global.sessionStorage.setItem(CONNECT_KEY, JSON.stringify(cfg)); } catch (e) { /* no storage */ }
  }
  function forgetConnect() {
    try { global.sessionStorage.removeItem(CONNECT_KEY); } catch (e) { /* no storage */ }
  }
  function savedConnect() {
    try {
      var s = global.sessionStorage && global.sessionStorage.getItem(CONNECT_KEY);
      return s ? JSON.parse(s) : null;
    } catch (e) { return null; }
  }

  function connect(opts) {
    opts = opts || {};
    var base = opts.url || picker.server || "ws://127.0.0.1:9223/spel";
    var token = opts.token != null ? String(opts.token) : null;
    persistConnect({ url: base, token: token });
    // Replace any prior connection (e.g. an auto-reconnect that then gets an
    // explicit connect) so a tab holds exactly one live SSE/WS, not several.
    if (activeConn) {
      try {
        if (activeConn.socket) activeConn.socket.close();
        if (activeConn.source) activeConn.source.close();
      } catch (e) { /* ignore */ }
      activeConn = null;
    }
    if (/^wss?:/.test(base) && global.WebSocket) {
      var ws = new global.WebSocket(connWith(base, token));
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
      activeConn = { transport: "websocket", socket: ws, url: base, token: token };
      return activeConn;
    }
    // SSE for inbound commands, fetch POST for outbound results.
    var resultUrl = opts.resultUrl || connWith(base + "/result", token);
    var es = new global.EventSource(connWith(base, token));
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
    es.addEventListener("error", function () {
      // EventSource reconnects on its own; surface it for debugging only.
      if (global.console && global.console.debug) global.console.debug("spel: SSE reconnecting");
    });
    activeConn = { transport: "sse", source: es, url: base, token: token };
    return activeConn;
  }

  // ---------------------------------------------------------------------------
  // Install.
  // ---------------------------------------------------------------------------
  var api = {
    __installed: true,
    version: "0.6.0",
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

  installNetwork();

  // Re-inject survival: a previous page on this tab connected, so re-subscribe
  // automatically after a full-page navigation re-loaded this script. SPA route
  // changes keep window.__spel alive and need nothing; real navigations rebuild
  // the window and land here on the fresh page.
  (function () {
    var cfg = savedConnect();
    if (cfg && cfg.url) {
      try { connect(cfg); } catch (e) { /* ignore */ }
    }
  })();

  global.__spel = api;
  return api;
})(typeof window !== "undefined" ? window : this);
