(ns com.blockether.spel.bridge
  "Loopback HTTP bridge for `spel bridge` — the transport that lets an
   in-page `spel.js` engine subscribe to a spel server and exchange commands
   two ways.

   Why this exists: in locked-down corporate environments the Chrome DevTools
   Protocol (CDP) is disabled, so classic Playwright/CDP automation is dead.
   But loopback traffic (`127.0.0.1`) never leaves the machine, so a page that
   `<script src>`-embeds `spel.js` can talk to a local spel server the proxy
   never sees. This bridge is that server side.

   Transport: Server-Sent Events (inbound commands, server → browser) plus a
   JSON POST endpoint (outbound results, browser → server). SSE + POST is used
   rather than WebSocket because it is the JDK-native option (no extra deps,
   `com.sun.net.httpserver`) and behaves predictably on loopback. `spel.js`
   also speaks WebSocket first, but the bundled server implements the SSE
   fallback path.

   Endpoints (all under the configurable `:path`, default `/spel`):
     GET  /spel          → SSE stream; each browser tab that connects is a client
     POST /spel/result   → browser posts an invoke result here (correlated by id)
     POST /spel/command  → an external client (the spel CLI) submits one command;
                           it is pushed to the connected tab and the browser's
                           result is returned as the HTTP response (this is what
                           lets regular `spel <verb>` route through the bridge)
     POST /spel/hello    → browser announces tab metadata (url, title, ua, transport)
                           on connect; stored against its SSE client id and surfaced
                           back as the connected-profiles list
     GET  /spel/clients  → the connected profiles (tabs) currently subscribed,
                           as JSON; token-gated like every other endpoint
     GET  /spel.js       → the embedded engine source (same file that ships in
                           the native image)
     GET  /              → a tiny harness page that loads the engine and connects"
  (:require
   [charred.api :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.net InetSocketAddress]
   [java.nio.charset StandardCharsets]
   [java.util UUID]
   [java.util.concurrent ConcurrentHashMap Executors LinkedBlockingQueue ScheduledExecutorService TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private engine-resource
  "Classpath location of the embedded engine (also listed in the native-image
   resource-config so it survives into the standalone binary)."
  "com/blockether/spel/browser/spel.js")

(defn engine-source
  "Returns the embedded `spel.js` source as a string, or throws if the resource
   is missing from the classpath / native image."
  ^String []
  (if-let [r (io/resource engine-resource)]
    (slurp r)
    (throw (ex-info (str "embedded engine resource not found: " engine-resource)
             {:resource engine-resource}))))

(def ^:private sw-engine-resource
  "Classpath location of the embedded service worker (spel-sw.js), which does
   same-origin capture of passive subresources the in-page wrappers miss."
  "com/blockether/spel/browser/spel-sw.js")

(defn sw-source
  "Returns the embedded `spel-sw.js` source as a string, or throws if it is
   missing from the classpath / native image."
  ^String []
  (if-let [r (io/resource sw-engine-resource)]
    (slurp r)
    (throw (ex-info (str "embedded service worker resource not found: " sw-engine-resource)
             {:resource sw-engine-resource}))))

(def ^:private icon-sizes
  "Extension icon sizes (px). Each is README's logo.svg emblem rendered to a
   transparent square PNG at com/blockether/spel/browser/icons/icon-<n>.png
   (also listed in resource-config so it survives into the native binary)."
  [16 32 48 128])

(defn icon-bytes
  "Returns the embedded extension icon PNG (`icon-<px>.png`) as a byte array,
   or throws if the resource is missing from the classpath / native image."
  ^bytes [px]
  (let [res (str "com/blockether/spel/browser/icons/icon-" px ".png")]
    (if-let [r (io/resource res)]
      (with-open [in  (io/input-stream r)
                  out (java.io.ByteArrayOutputStream.)]
        (io/copy in out)
        (.toByteArray out))
      (throw (ex-info (str "embedded extension icon resource not found: " res)
               {:resource res})))))

(defn eject-origin
  "Resolves the (origin, path) an ejected loader/bookmarklet should target.
   With an explicit `url` (e.g. http://host:port/spel) it is split into an
   origin (`scheme://authority`) and a path; otherwise the serving
   `host`/`port`/`path` defaults are used. Returns `[origin path]`."
  [url ^String host port ^String path]
  (if (and url (seq url))
    (let [u (java.net.URL. ^String url)
          p (.getPath u)]
      [(str (.getProtocol u) "://" (.getAuthority u))
       (if (str/blank? p) path p)])
    [(str "http://" host ":" port) path]))

(defn loader-script
  "A tiny, minified in-page loader (plain JS, no `javascript:` prefix) that
   injects the embedded engine from `<origin>/spel.js` and subscribes it to the
   bridge at `<origin><path>`. Idempotent: if `window.__spel` is already
   installed it just (re)connects.

   Local Network Access aware: modern Chromium (Edge 143+/Chrome 142+) gates a
   public origin reaching `127.0.0.1` behind a per-origin permission, and a bare
   `<script src>` no-cors subresource to loopback is DENIED silently instead of
   prompting. So the loader fetches the engine with `targetAddressSpace:'loopback'`
   — the sanctioned call that actually raises the grantable prompt (and is exempt
   from mixed-content once allowed) — then inline-injects the text. It falls back
   to a `<script src>` tag on older browsers where either path works.

   Returned WITHOUT a prefix so it serves two uses — pasted into the DevTools
   Console (or saved as a Sources Snippet), or prefixed with `javascript:` to
   form a draggable bookmarklet."
  [^String origin ^String path token]
  (let [o (json/write-json-str origin)
        c (json/write-json-str (str origin path))
        t (if (and token (seq token)) (json/write-json-str token) "null")]
    (str "(function(){"
      "var o=" o ",c=" c ",t=" t ";"
      "function banner(m,bg){try{var b=document.createElement('div');b.textContent=m;b.style.cssText='position:fixed;top:0;left:0;right:0;z-index:2147483647;background:'+bg+';color:#fff;font:700 13px/1.45 -apple-system,Segoe UI,sans-serif;padding:10px 14px;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.25)';(document.body||document.documentElement).appendChild(b);setTimeout(function(){if(b.parentNode)b.parentNode.removeChild(b);},6000);}catch(e){}}"
      "function go(){try{window.__spel&&window.__spel.connect({url:c,token:t});banner('\u2713 spel installed \u2014 subscribing to '+o+'   \u00b7   Ctrl+Shift+K: manage   \u00b7   Ctrl+Shift+L: pick element','#1f7a3d');}catch(e){console.error('spel:',e);banner('spel: '+e,'#b3261e');}}"
      "if(window.__spel){go();return;}"
      "function inject(code){var s=document.createElement('script');s.textContent=code;(document.head||document.documentElement).appendChild(s);go();}"
      "function tag(){var s=document.createElement('script');s.src=o+'/spel.js';s.onload=go;s.onerror=fail;(document.head||document.documentElement).appendChild(s);}"
      "function fail(){var m='spel: could not reach '+o+'/spel.js \u2014 is `spel bridge` running, and did you Allow local network access? (edge://settings/content/localNetworkAccess)';console.error(m);banner(m,'#b3261e');}"
      "try{fetch(o+'/spel.js',{mode:'cors',targetAddressSpace:'loopback'}).then(function(r){if(!r.ok)throw 0;return r.text();}).then(inject).catch(tag);}catch(e){tag();}"
      "})();")))

(defn bookmarklet
  "The loader as a ready `javascript:` bookmarklet URL — drag it to the bookmarks
   bar (or Edge favorites); clicking it on any page injects + connects the engine.
   Note: a page's Content-Security-Policy or a managed browser policy can still
   block inline/bookmarklet execution — see `spel bridge --help`."
  [^String origin ^String path token]
  (str "javascript:" (loader-script origin path token)))

;; =============================================================================
;; Browser extension — the permanent, any-site, restart-proof loader
;; =============================================================================
;;
;; The bookmarklet injects a CROSS-ORIGIN loopback script into a page you don't
;; own, so it trips every browser guard (Local Network Access prompt, mixed
;; content, no persistence, re-click after each restart). A Manifest V3 extension
;; sidesteps all of it: a content script is granted the page by the browser
;; itself, runs at document_start on every site, survives restarts, and — with a
;; host permission for 127.0.0.1 — reaches the bridge with no LNA prompt.
;;
;; It REUSES the same embedded engine (spel.js) served at /spel.js and shipped in
;; the native image; the extension is just a thin MV3 wrapper that injects it into
;; the page's MAIN world and calls window.__spel.connect({url, token}). The engine
;; is NOT modified. The (moved-here) service worker spel-sw.js rides along as a
;; web-accessible resource so passive-subresource capture lives with the extension
;; rather than the regular bridge.

(defn- extension-version
  "spel version stamped into the MV3 manifest (bare semver from SPEL_VERSION)."
  ^String []
  (or (some-> (io/resource "SPEL_VERSION") slurp str/trim not-empty) "0.0.0"))

(defn extension-manifest
  "The Manifest V3 `manifest.json` for the spel bridge browser extension.

   Two content-script entries run at document_start on every page: engine.js +
   bootstrap.js in the page's MAIN world (so `window.__spel` is defined on the
   page), and content.js in the ISOLATED world (it can read chrome.storage for the
   saved bridge route and hand it to the MAIN world via a CustomEvent). A host
   permission for loopback lets the injected engine fetch/connect the local bridge
   without the Local Network Access prompt."
  ^String []
  (json/write-json-str
    (array-map
      "manifest_version" 3
      "name" "spel bridge"
      "version" (extension-version)
      "description" (str "Injects the spel engine into every page and auto-connects to a local spel "
                      "bridge — DOM automation that survives browser restarts, with no bookmarklet "
                      "and no local-network-access prompt.")
      "permissions" ["storage"]
      "host_permissions" ["http://127.0.0.1/*" "http://localhost/*"]
      "icons" (array-map "16" "icons/icon-16.png" "32" "icons/icon-32.png"
                "48" "icons/icon-48.png" "128" "icons/icon-128.png")
      "action" (array-map "default_popup" "popup.html" "default_title" "spel bridge"
                 "default_icon" (array-map "16" "icons/icon-16.png" "32" "icons/icon-32.png"
                                  "48" "icons/icon-48.png" "128" "icons/icon-128.png"))
      "content_scripts"
      [(array-map
         "matches" ["<all_urls>"]
         "js" ["engine.js" "bootstrap.js"]
         "run_at" "document_start"
         "world" "MAIN"
         "all_frames" false)
       (array-map
         "matches" ["<all_urls>"]
         "js" ["content.js"]
         "run_at" "document_start"
         "world" "ISOLATED"
         "all_frames" false)]
      "web_accessible_resources"
      [(array-map "resources" ["engine.js" "spel-sw.js"] "matches" ["<all_urls>"])])))

(defn extension-bootstrap-script
  "MAIN-world bootstrap. Runs right after engine.js (which defines `window.__spel`)
   and waits for the isolated content script to hand over the saved bridge route,
   then connects the page engine to it."
  ^String []
  (str "\"use strict\";\n"
    "// spel bridge extension — MAIN-world bootstrap (runs after engine.js).\n"
    "(function(){\n"
    "  function connect(d){try{if(window.__spel&&d&&d.url){window.__spel.connect({url:d.url,token:d.token||null});}}catch(e){console.error('spel:',e);}}\n"
    "  window.addEventListener('spel:connect',function(e){connect(e.detail);});\n"
    "})();\n"))

(defn extension-content-script
  "ISOLATED-world content script. Reads the saved bridge `{url, token}` from
   chrome.storage.local (set via the popup), falling back to the values baked in at
   eject time, and dispatches them to the MAIN-world engine via a `spel:connect`
   CustomEvent. Re-fires when the stored route changes."
  [^String origin ^String path token]
  (let [u (if (and origin (seq origin)) (json/write-json-str (str origin path)) "null")
        t (if (and token (seq token)) (json/write-json-str token) "null")]
    (str "\"use strict\";\n"
      "// spel bridge extension — isolated-world content script.\n"
      "(function(){\n"
      "  var DEFAULT_URL=" u ";\n"
      "  var DEFAULT_TOKEN=" t ";\n"
      "  function push(cfg){if(!cfg||!cfg.url)return;try{window.dispatchEvent(new CustomEvent('spel:connect',{detail:{url:cfg.url,token:cfg.token||null}}));}catch(e){}}\n"
      "  function load(){try{chrome.storage.local.get(['spelUrl','spelToken'],function(v){v=v||{};push({url:v.spelUrl||DEFAULT_URL,token:v.spelToken||DEFAULT_TOKEN});});}catch(e){push({url:DEFAULT_URL,token:DEFAULT_TOKEN});}}\n"
      "  load();\n"
      "  try{chrome.storage.onChanged.addListener(function(ch,area){if(area==='local'&&(ch.spelUrl||ch.spelToken))load();});}catch(e){}\n"
      "})();\n")))

(defn logo-mark-svg
  "The spel brand mark (theatre masks + curtains + play triangle) as an inline
   SVG string, matching README's logo.svg. `px` sets the rendered width."
  ^String [px]
  (let [h (int (Math/round (* (double px) (/ 220.0 280.0))))]
    (str "<svg width=\"" px "\" height=\"" h "\" viewBox=\"60 18 280 220\" fill=\"none\" "
      "xmlns=\"http://www.w3.org/2000/svg\" style=\"display:block\" aria-label=\"spel\">"
      "<path d=\"M148,60 C68,102 68,184 148,226\" stroke=\"#C04B41\" stroke-width=\"18\" fill=\"none\" stroke-linecap=\"round\"/>"
      "<path d=\"M252,60 C332,102 332,184 252,226\" stroke=\"#C04B41\" stroke-width=\"18\" fill=\"none\" stroke-linecap=\"round\"/>"
      "<g transform=\"translate(162,28)\">"
      "<path d=\"M 4,0 C 0,0 -2,4 0,8 L 6,30 C 8,36 14,40 20,40 C 26,40 30,36 30,30 L 30,8 C 30,3 26,0 22,0 Z\" fill=\"#E2574C\"/>"
      "<ellipse cx=\"10\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"#fff\" opacity=\"0.9\"/>"
      "<ellipse cx=\"22\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"#fff\" opacity=\"0.9\"/>"
      "<path d=\"M 9,28 Q 16,35 23,28\" stroke=\"#fff\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\" opacity=\"0.9\"/>"
      "</g>"
      "<g transform=\"translate(206,28)\">"
      "<path d=\"M 8,0 C 4,0 0,3 0,8 L 0,30 C 0,36 4,40 10,40 C 16,40 22,36 24,30 L 30,8 C 32,4 30,0 26,0 Z\" fill=\"#2EAD33\"/>"
      "<ellipse cx=\"10\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"#fff\" opacity=\"0.9\"/>"
      "<ellipse cx=\"22\" cy=\"16\" rx=\"3.5\" ry=\"4.5\" fill=\"#fff\" opacity=\"0.9\"/>"
      "<path d=\"M 9,32 Q 16,25 23,32\" stroke=\"#fff\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\" opacity=\"0.9\"/>"
      "</g>"
      "<path d=\"M 183,102 L 237,143 L 183,184 Z\" fill=\"#2EAD33\"/>"
      "</svg>")))

(defn extension-popup-html
  "The extension popup: enter/override the bridge URL + token; saved to
   chrome.storage.local so it persists across browser restarts on any site."
  ^String []
  (str "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"light\"><title>spel bridge</title>\n"
    "<style>*{box-sizing:border-box}:root{color-scheme:light only}html,body{background:#f2f3f5!important;color:#1a1a1a!important}body{font:13px/1.5 -apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;width:308px;margin:0;padding:14px;-webkit-font-smoothing:antialiased}"
    ".card{background:#fff;border:1px solid #e6e8eb;border-radius:16px;padding:18px;box-shadow:0 1px 2px rgba(16,24,40,.04),0 8px 24px rgba(16,24,40,.08)}.hd{display:flex;align-items:center;gap:10px}.tt{display:flex;flex-direction:column;line-height:1.2}"
    "h1{font-size:15px;font-weight:650;margin:0;color:#101828;letter-spacing:-.01em}.sub{font-size:11px;color:#98a2b3;font-weight:500;margin-top:2px}.status{display:flex;align-items:center;gap:7px;margin:15px 0 2px;font-size:11.5px;color:#667085;font-weight:500}.dot{width:7px;height:7px;border-radius:50%;background:#d0d5dd;box-shadow:0 0 0 3px rgba(208,213,221,.28);transition:background .2s,box-shadow .2s}.dot.on{background:#12b76a;box-shadow:0 0 0 3px rgba(18,183,106,.2)}"
    "label{display:block;margin:13px 0 5px;color:#475467;font-size:11.5px;font-weight:600}input{width:100%;padding:9px 11px;border:1px solid #d0d5dd;border-radius:9px;background:#fff!important;color:#1a1a1a!important;font-size:13px;outline:none;transition:border-color .15s,box-shadow .15s}input:focus{border-color:#1f7a3d;box-shadow:0 0 0 3px rgba(31,122,61,.14)}input::placeholder{color:#b0b7c3}"
    "button{margin-top:18px;width:100%;padding:10px 12px;border:0;border-radius:9px;background:#1f7a3d!important;color:#fff!important;font-weight:650;font-size:13px;letter-spacing:.01em;cursor:pointer;box-shadow:0 1px 2px rgba(16,24,40,.16),0 1px 3px rgba(31,122,61,.24);transition:background .15s,transform .05s,box-shadow .15s}button:hover{background:#1b6c36!important;box-shadow:0 2px 8px rgba(31,122,61,.3)}button:active{transform:translateY(1px)}"
    ".st{margin-top:12px;font-size:11.5px;color:#1f7a3d;min-height:15px;font-weight:500}</style></head>\n"
    "<body><div class=\"card\"><div class=\"hd\">" (logo-mark-svg 26) "<div class=\"tt\"><h1>spel bridge</h1><span class=\"sub\">Local automation bridge</span></div></div>\n"
    "<div class=\"status\"><span class=\"dot\" id=\"dot\"></span><span id=\"stt\">Set the bridge URL, then save</span></div>\n"
    "<label>Bridge URL</label><input id=\"url\" placeholder=\"http://127.0.0.1:8787/spel\">\n"
    "<label>Token</label><input id=\"token\" placeholder=\"(optional)\">\n"
    "<button id=\"save\">Save &amp; connect</button>\n<div class=\"st\" id=\"st\"></div>\n"
    "</div>\n<script src=\"popup.js\"></script></body></html>\n"))

(defn extension-popup-js
  "Popup logic: prefill from storage, persist the bridge URL + token on save."
  ^String []
  (str "\"use strict\";\n"
    "(function(){\n"
    "  var $=function(id){return document.getElementById(id);};\n"
    "  function lit(on,msg){$('dot').className=on?'dot on':'dot';$('stt').textContent=msg;}\n"
    "  chrome.storage.local.get(['spelUrl','spelToken'],function(v){v=v||{};if(v.spelUrl){$('url').value=v.spelUrl;lit(true,'Connected · '+v.spelUrl);}if(v.spelToken)$('token').value=v.spelToken;});\n"
    "  $('save').addEventListener('click',function(){var url=$('url').value.trim();var token=$('token').value.trim();chrome.storage.local.set({spelUrl:url,spelToken:token},function(){lit(!!url,url?('Connected · '+url):'Set the bridge URL, then save');$('st').textContent='Saved \u00b7 reload the target tab to connect.';});});\n"
    "})();\n"))

(defn extension-readme
  "A short install/usage README dropped into the ejected extension folder."
  [^String origin ^String path token]
  (let [url (str origin path)]
    (str "# spel bridge — browser extension (Manifest V3)\n\n"
      "Unpacked extension that injects the spel engine into every page and\n"
      "auto-connects to your local `spel bridge`. Unlike the bookmarklet it needs\n"
      "no Local Network Access prompt, works on any site, and survives restarts.\n\n"
      "## Install (Chrome / Edge / Brave)\n\n"
      "1. Open `chrome://extensions` (or `edge://extensions`).\n"
      "2. Toggle **Developer mode** (top-right).\n"
      "3. Click **Load unpacked** and pick this folder.\n\n"
      "## Configure\n\n"
      "The bridge route is baked in at eject time:\n\n"
      "    url:   " url "\n"
      "    token: " (if (and token (seq token)) token "(none)") "\n\n"
      "To point at a different bridge, click the extension's toolbar icon and set the\n"
      "**Bridge URL** + **Token** (saved to `chrome.storage.local`, persists across\n"
      "restarts). Reload the target tab to reconnect.\n\n"
      "Start a bridge with `spel bridge -p <port>` and re-eject to refresh the token.\n")))

(defn extension-files
  "Returns the full set of files for the unpacked MV3 extension as an ordered\n   vector of `[relative-path content-string]`. Reuses the embedded engine + service\n   worker verbatim; the rest is thin MV3 glue with the bridge route baked in."
  [^String origin ^String path token]
  (into
    [["manifest.json" (extension-manifest)]
     ["engine.js" (engine-source)]
     ["spel-sw.js" (sw-source)]
     ["bootstrap.js" (extension-bootstrap-script)]
     ["content.js" (extension-content-script origin path token)]
     ["popup.html" (extension-popup-html)]
     ["popup.js" (extension-popup-js)]
     ["README.md" (extension-readme origin path token)]]
    (for [n icon-sizes]
      [(str "icons/icon-" n ".png") (icon-bytes n)])))

;; =============================================================================
;; Target profile — the persisted route for regular `spel <verb>` commands
;; =============================================================================
;;
;; When a target is saved (via `spel bridge use`), the CLI sends every browser
;; command to the bridge's /command endpoint instead of the Playwright daemon,
;; so a locked-down / CDP-disabled box can still drive a real tab through the
;; embedded engine. "Where we send / how we communicate" lives in one small
;; JSON file at ~/.spel/bridge.json.

(defn target-path
  "Filesystem location of the saved bridge target (the route regular spel
   commands follow when set)."
  ^String []
  (str (System/getProperty "user.home") "/.spel/bridge.json"))

(defn load-target
  "Reads the persisted bridge target, or nil when none is set. Shape:
   `{:url \"http://127.0.0.1:8787/spel\"}`."
  ([] (load-target (target-path)))
  ([^String path]
   (let [f (io/file path)]
     (when (.isFile f)
       (try (json/read-json (slurp f) :key-fn keyword)
         (catch Exception _ nil))))))

(defn save-target!
  "Persists the active bridge target so subsequent `spel <verb>` invocations
   route through the bridge. Returns the saved map."
  ([target] (save-target! target (target-path)))
  ([target ^String path]
   (let [f (io/file path)]
     (when-let [parent (.getParentFile f)] (.mkdirs parent))
     (spit f (json/write-json-str target))
     target)))

(defn clear-target!
  "Removes the persisted bridge target; regular commands go back to the daemon.
   Returns true if a target file was removed."
  ([] (clear-target! (target-path)))
  ([^String path]
   (let [f (io/file path)]
     (boolean (and (.isFile f) (.delete f))))))

(defn- gen-token
  "A short random shared secret gating browser<->bridge traffic on loopback."
  ^String []
  (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 16))

(defn token-path
  "Filesystem location of the STABLE bridge token, reused across `spel bridge`
   restarts so a once-loaded bookmarklet / extension keeps working — the token no
   longer rotates on every start. Delete this file (or pass an explicit --token)
   to rotate the secret."
  ^String []
  (str (System/getProperty "user.home") "/.spel/bridge-token"))

(defn persisted-token!
  "Returns the stable per-machine bridge token, generating and persisting one to
   ~/.spel/bridge-token on first use. Because it is reused across restarts, an
   extension or bookmarklet ejected once stays authorized against every later
   bridge — killing the 'unauthorized (bad or missing token)' churn you hit when
   the token was random per start."
  ^String []
  (let [f (io/file (token-path))]
    (or (when (.isFile f)
          (let [t (str/trim (slurp f))]
            (when (seq t) t)))
      (let [t (gen-token)]
        (when-let [parent (.getParentFile f)] (.mkdirs parent))
        (spit f t)
        t))))

(defn runtime-path
  "Filesystem location of the RUNNING bridge's connection details (port + token),
   written while `spel bridge` is up so a same-box `spel bridge use` can pick up
   the live token with zero friction. Cleared on shutdown."
  ^String []
  (str (System/getProperty "user.home") "/.spel/bridge-runtime.json"))

(defn write-runtime!
  "Records the live bridge route (url/port/path/token) for local discovery."
  [m]
  (let [f (io/file (runtime-path))]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (json/write-json-str m))
    m))

(defn read-runtime
  "Reads the running bridge's route, or nil when no bridge is up."
  []
  (let [f (io/file (runtime-path))]
    (when (.isFile f)
      (try (json/read-json (slurp f) :key-fn keyword)
        (catch Exception _ nil)))))

(defn clear-runtime!
  "Removes the runtime discovery file (bridge shutting down)."
  []
  (let [f (io/file (runtime-path))]
    (boolean (and (.isFile f) (.delete f)))))

(defn- ->bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn route-command!
  "Submits ONE spel command map to a running bridge's /command endpoint and
   returns the browser's result adapted to the daemon `{:success :data :error}`
   shape, so CLI output is identical whether a command hit the daemon or the
   bridge. `url` is the bridge connect URL (e.g. http://127.0.0.1:8787/spel)."
  ([url command] (route-command! url command 30000 nil))
  ([url command timeout-ms] (route-command! url command timeout-ms nil))
  ([^String url command timeout-ms ^String token]
   (let [endpoint (str url "/command"
                    (when (and token (seq token))
                      (str "?t=" (java.net.URLEncoder/encode token "UTF-8"))))
         body     (->bytes (json/write-json-str
                             (into {} (map (fn [[k v]] [(name k) v]) command))))]
     (try
       (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. endpoint))]
         (doto conn
           (.setRequestMethod "POST")
           (.setConnectTimeout 3000)
           (.setReadTimeout (int (or timeout-ms 30000)))
           (.setDoOutput true)
           (.setRequestProperty "Content-Type" "application/json"))
         (when (and token (seq token))
           (.setRequestProperty conn "X-Spel-Token" token))
         (with-open [os (.getOutputStream conn)]
           (.write os body))
         (let [code (.getResponseCode conn)
               ins  (if (and (>= code 200) (< code 400))
                      (.getInputStream conn)
                      (.getErrorStream conn))
               resp (when ins (slurp ins))
               r    (try (json/read-json resp) (catch Exception _ nil))]
           (if (map? r)
             {:success (boolean (get r "ok"))
              :data    (get r "value")
              :error   (get r "error")}
             {:success false
              :error   (str "bridge: unexpected response (HTTP " code ")")})))
       (catch java.net.ConnectException _
         {:success false
          :error   (str "bridge not reachable at " url
                     " — is `spel bridge` running? (start it, or run `spel bridge off`)")})
       (catch Exception e
         {:success false :error (str "bridge error: " (.getMessage e))})))))

(defn- respond!
  "Writes a one-shot response with the given status, content-type and body."
  [^HttpExchange ex ^long status ^String content-type ^String body]
  (let [h (.getResponseHeaders ex)]
    (.set h "Content-Type" content-type)
    (.set h "Access-Control-Allow-Origin" "*")
    (.set h "Access-Control-Allow-Headers" "Content-Type")
    (.set h "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
    ;; Older Private Network Access (pre-LNA Chromium): a public origin reaching
    ;; loopback wants this on the preflight. Harmless on newer LNA browsers,
    ;; which gate loopback by user permission instead (see loader-script).
    (.set h "Access-Control-Allow-Private-Network" "true"))
  (let [bytes (->bytes body)]
    (.sendResponseHeaders ex status (long (alength bytes)))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- cors-preflight? [^HttpExchange ex]
  (= "OPTIONS" (.getRequestMethod ex)))

(defn- query-param
  "Reads a query-string parameter from the request URI, URL-decoded."
  [^HttpExchange ex ^String k]
  (when-let [q (.getRawQuery (.getRequestURI ex))]
    (some (fn [^String pair]
            (let [i (.indexOf pair "=")]
              (when (and (pos? i) (= k (subs pair 0 i)))
                (java.net.URLDecoder/decode (subs pair (inc i)) "UTF-8"))))
      (str/split q #"&"))))

(defn- authorized?
  "True when the bridge has no token (auth disabled) or the request carries the
   matching token — either as `?t=` (SSE can only pass it in the URL) or an
   `X-Spel-Token` header. This is what stops another page open in the same
   browser from driving the tab / reading captured traffic over loopback."
  [^String token ^HttpExchange ex]
  (or (str/blank? token)
    (= token (query-param ex "t"))
    (= token (.getFirst (.getRequestHeaders ex) "X-Spel-Token"))))

(defn- forbidden! [^HttpExchange ex]
  (respond! ex 403 "application/json"
    "{\"ok\":false,\"error\":\"spel bridge: unauthorized (bad or missing token)\"}"))

(defn- harness-html
  "A minimal self-connecting page, handy for smoke-testing a bridge in a real
   browser: it loads the engine and immediately subscribes to this server."
  [^String path token]
  (str "<!doctype html>\n"
    "<html><head><meta charset=\"utf-8\"><title>spel bridge</title></head>\n"
    "<body>\n"
    "<h1 style=\"display:flex;align-items:center;gap:10px;font-family:-apple-system,Segoe UI,sans-serif\">" (logo-mark-svg 40) "<span>spel bridge</span></h1>\n"
    "<p>Engine loaded. This tab is subscribed to the local spel server.</p>\n"
    "<script src=\"/spel.js\"></script>\n"
    "<script>\n"
    "  window.__spel.connect({ url: window.location.origin + " (json/write-json-str path)
    (if (and token (seq token)) (str ", token: " (json/write-json-str token)) "")
    " });\n"
    "</script>\n"
    "</body></html>\n"))

(defn- client-info
  "Reads the metadata map stored alongside a client's SSE queue. Returns a
   profile map for the `/clients` listing, or nil when the entry was removed."
  [clients id]
  (some-> ^ConcurrentHashMap clients (.get id) (get :info)))

(defn- client-profiles
  "Returns the currently-connected profiles (one per subscribed tab) as a vector
   of plain maps, newest first. Safe to call from any handler thread.

   Entries can be removed concurrently (a tab closing mid-iteration), so we read
   each info defensively and drop nils; the comparator runs on the materialized
   info maps, never on bare client-id strings."
  [clients]
  (let [infos (into []
                (comp (map (fn [id] (client-info clients id)))
                  (remove nil?))
                (.keySet ^ConcurrentHashMap clients))]
    (vec (sort-by :connected-at > infos))))

(defn- sse-handler
  "GET → open a Server-Sent-Events stream and register the tab as a client.
   Blocks its worker thread for the life of the connection, draining the
   client's queue (heartbeats keep proxies/`EventSource` from timing out).

   Each client entry is a map `{:queue :info}`; `:info` is populated by the
   `/hello` POST and read by `/clients` so the connect dialog can show the
   currently-connected profiles."
  [clients token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
          (not= "GET" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [h (.getResponseHeaders ex)]
            (.set h "Content-Type" "text/event-stream")
            (.set h "Cache-Control" "no-cache")
            (.set h "Connection" "keep-alive")
            (.set h "Access-Control-Allow-Origin" "*")
            (.sendResponseHeaders ex 200 0)
            (let [os (.getResponseBody ex)
                  id (str (UUID/randomUUID))
                  ;; Placeholder info so `/clients` sees the tab before its
                  ;; `/hello` POST lands; the POST then enriches this map.
                  base-info {:id id
                             :connected-at (System/currentTimeMillis)
                             :transport "sse"
                             :url nil
                             :title nil
                             :user-agent nil}
                  entry {:queue (LinkedBlockingQueue.) :info base-info}]
              (.put ^ConcurrentHashMap clients id entry)
              (try
                (.write os (->bytes ": connected\n\n"))
                (.flush os)
                (loop []
                  (let [msg (.poll ^LinkedBlockingQueue (:queue entry) 20 TimeUnit/SECONDS)]
                    (cond
                      (nil? msg) (do (.write os (->bytes ": ping\n\n")) (.flush os) (recur))
                      (identical? msg ::close) nil
                      :else (do (.write os (->bytes (str "data: " msg "\n\n")))
                              (.flush os)
                              (recur)))))
                (catch Exception _ nil)
                (finally
                  (.remove ^ConcurrentHashMap clients id)
                  (try (.close os) (catch Exception _ nil)))))))
        (catch Exception _ (try (.close ex) (catch Exception _ nil)))))))

(defn- result-handler
  "POST ← a browser posts one invoke result `{id, action, ok, value|error}`.
   Correlates it to the waiting promise and delivers."
  [pending token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
          (not= "POST" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [body (slurp (io/reader (.getRequestBody ex)))
                msg  (try (json/read-json body) (catch Exception _ nil))
                id   (get msg "id")]
            (when id
              (when-let [p (.remove ^ConcurrentHashMap pending id)]
                (deliver p msg)))
            (respond! ex 200 "application/json" "{\"ok\":true}")))
        (catch Exception _ (respond! ex 500 "text/plain" "error"))))))

(defn- hello-handler
  "POST ← a browser tab announces its metadata (url, title, userAgent, transport)
   right after subscribing. The bridge correlates it to the most-recent SSE client
   that still carries the placeholder (nil url) info, and stores it so `/clients`
   can list the connected profiles.

   The engine fires this POST from the EventSource `open` handler, so its SSE
   entry already exists by the time we get here; if a race leaves none, we
   no-op rather than mint a duplicate metadata-only entry — the listing stays
   accurate (placeholder shows the tab), and the next dialog refresh re-reads it."
  [clients token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
          (not= "POST" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [body (slurp (io/reader (.getRequestBody ex)))
                msg  (try (json/read-json body) (catch Exception _ nil))
                info {:connected-at (System/currentTimeMillis)
                      :transport    (or (get msg "transport") "sse")
                      :url          (get msg "url")
                      :title        (get msg "title")
                      :tab-id       (get msg "tabId")
                      :user-agent   (get msg "userAgent")}
                ;; Materialize the live entries once, then pick the newest one
                ;; whose url is still nil (the just-opened SSE stream). We read
                ;; defensively so a concurrently-closed tab doesn't NPE.
                infos (into []
                        (comp (map (fn [id] [id (client-info clients id)]))
                          (remove (fn [[_ i]] (nil? i))))
                        (.keySet ^ConcurrentHashMap clients))
                target-id (->> infos
                            (sort-by (fn [[_ i]] (:connected-at i)) >)
                            (some (fn [[id i]] (when (nil? (:url i)) id))))]
            (when-let [entry (some-> ^ConcurrentHashMap clients (.get target-id))]
              (.put ^ConcurrentHashMap clients target-id
                (assoc entry :info (assoc info :id target-id)))
              ;; Collapse a reloaded tab: its previous SSE thread stays blocked
              ;; (draining nothing) until its next ping write finally fails, so
              ;; drop any OTHER entry sharing this tab-id now — /clients lists one
              ;; row per tab, not one per stale reconnect.
              (when-let [tid (:tab-id info)]
                (doseq [id (vec (.keySet ^ConcurrentHashMap clients))]
                  (when (not= id target-id)
                    (when-let [i (client-info clients id)]
                      (when (= tid (:tab-id i))
                        (when-let [stale (.get ^ConcurrentHashMap clients id)]
                          (.remove ^ConcurrentHashMap clients id)
                          (try (.offer ^LinkedBlockingQueue (:queue stale) ::close)
                            (catch Exception _ nil)))))))))
            (respond! ex 200 "application/json" "{\"ok\":true}")))
        (catch Exception _ (respond! ex 500 "text/plain" "error"))))))

(defn- clients-handler
  "GET → the connected profiles (tabs) currently subscribed to this bridge, as
   JSON `{clients: [...]}`. Token-gated like every other endpoint so a foreign
   page in the same browser cannot enumerate the tabs. This is what the connect
   dialog (`spel.js` `chooseServer`) fetches to render the profiles list."
  [clients token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
          (not= "GET" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (respond! ex 200 "application/json"
            (json/write-json-str {:clients (client-profiles clients)})))
        (catch Exception _ (respond! ex 500 "text/plain" "error"))))))

(defn- static-handler [content-type ^String body]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (if (cors-preflight? ex)
          (respond! ex 204 "text/plain" "")
          (respond! ex 200 content-type body))
        (catch Exception _ (try (.close ex) (catch Exception _ nil)))))))

(defn- command-handler
  "POST ← an external client (the spel CLI) submits one command map. It is
   pushed to the connected tab(s) via `send!` and the browser's result is
   returned as the JSON response. With no tab subscribed `send!` times out and
   a structured `{ok:false,error}` is returned so the caller gets a clear error."
  [send! token]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (cond
          (cors-preflight? ex) (respond! ex 204 "text/plain" "")
          (not (authorized? token ex)) (forbidden! ex)
          (not= "POST" (.getRequestMethod ex)) (respond! ex 405 "text/plain" "method not allowed")
          :else
          (let [body   (slurp (io/reader (.getRequestBody ex)))
                cmd    (json/read-json body)
                result (try (send! cmd 30000)
                         (catch Exception e
                           {"ok" false "error" (or (.getMessage e) "bridge command failed")}))]
            (respond! ex 200 "application/json" (json/write-json-str result))))
        (catch Exception e
          (respond! ex 500 "text/plain" (str "error: " (.getMessage e))))))))

(defn create-bridge
  "Creates and starts a loopback bridge. Returns a map:
     :url    the SSE/connect URL to hand to `spel.js` `connect({url})`
     :page   the harness page URL (open it in a browser to auto-connect)
     :host :port :path
     :clients   count of currently-subscribed tabs (deref-able fn)
     :clients-list  fn returning the connected profiles (maps with :url,
                :title, :user-agent, :transport, :connected-at) for the dialog
     :send      (fn [command] promise) — pushes a command to every connected
                tab; the promise is delivered with the first result posted back
     :send!     (fn [command] result | (fn [command timeout-ms] result)) —
                blocking convenience wrapper around :send
     :stop      (fn []) — stops the server and releases the port

   opts: :host (default \"127.0.0.1\") :port (default 8787) :path (default \"/spel\").
   Binds to loopback only by design — this never listens on a routable address."
  [& {:keys [host port path token] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [clients   (ConcurrentHashMap.)
        pending   (ConcurrentHashMap.)
        ^HttpServer server (try (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
                             (catch java.io.IOException _
                      ;; Requested port busy — fall back to an ephemeral one.
                               (HttpServer/create (InetSocketAddress. ^String host (int 0)) 0)))
        ^ScheduledExecutorService scheduler (Executors/newSingleThreadScheduledExecutor)
        result-path (str path "/result")
        hello-path  (str path "/hello")
        clients-path (str path "/clients")]
    (.setExecutor server (Executors/newCachedThreadPool))
    (.createContext server result-path (result-handler pending token))
    (.createContext server clients-path (clients-handler clients token))
    (.createContext server hello-path (hello-handler clients token))
    (.createContext server path (sse-handler clients token))
    (.createContext server "/spel.js" (static-handler "application/javascript; charset=utf-8" (engine-source)))
    (.createContext server "/spel-sw.js" (static-handler "application/javascript; charset=utf-8" (sw-source)))
    (.createContext server "/" (static-handler "text/html; charset=utf-8" (harness-html path token)))
    (.start server)
    (let [actual-port (.getPort (.getAddress server))
          base-url    (str "http://" host ":" actual-port path)
          send (fn send-fn [command]
                 (let [id      (str (UUID/randomUUID))
                       p       (promise)
                       payload (assoc (into {} (map (fn [[k v]] [(name k) v]) command))
                                 "id" id)
                       js      (json/write-json-str payload)]
                   (.put ^ConcurrentHashMap pending id p)
                   ;; Bound the pending map: if no browser ever posts a result
                   ;; (no tab subscribed, tab closed, command died mid-flight)
                   ;; drop the orphaned promise rather than leak it for the life
                   ;; of the process. A delivered result is removed earlier by
                   ;; result-handler; this scheduled removal then no-ops.
                   (.schedule scheduler
                     ^Runnable (fn [] (.remove ^ConcurrentHashMap pending id))
                     (long 60) TimeUnit/SECONDS)
                   ;; Client entries are now `{:queue :info}` maps (the SSE
                   ;; handler stores metadata next to the queue), so reach into
                   ;; each entry's :queue to fan the command out to every tab.
                   (doseq [entry (vals clients)]
                     (.offer ^LinkedBlockingQueue (:queue entry) js))
                   p))
          send! (fn send-sync
                  ([command] (send-sync command 10000))
                  ([command timeout-ms]
                   (let [r (deref (send command) timeout-ms ::timeout)]
                     (if (= r ::timeout)
                       (throw (ex-info "spel bridge: timed out waiting for browser result"
                                {:command command :timeout-ms timeout-ms}))
                       r))))]
      (.createContext server (str path "/command") (command-handler send! token))
      {:server  server
       :host    host
       :port    actual-port
       :path    path
       :token   token
       :url     base-url
       :page    (str "http://" host ":" actual-port "/")
       :clients (fn [] (.size clients))
       :clients-list (fn [] (client-profiles clients))
       :send    send
       :send!   send!
       :stop    (fn []
                  ;; Wake every blocked SSE loop so it unwinds cleanly, then
                  ;; release the cleanup thread and the port.
                  (doseq [entry (vals clients)]
                    (.offer ^LinkedBlockingQueue (:queue entry) ::close))
                  (.shutdownNow scheduler)
                  (.stop server 0))})))

(defn serve!
  "Starts a bridge and blocks the current thread, printing connection details.
   Used by the `spel bridge` CLI command. Returns never (until interrupted)."
  [& {:keys [host port path token] :or {host "127.0.0.1" port 8787 path "/spel"}}]
  (let [token       (or token (persisted-token!))
        {:keys [url page stop] :as bridge} (create-bridge :host host :port port :path path :token token)
        actual-port (:port bridge)
        connect-url (str url "?t=" token)]
    (println "spel bridge — loopback bridge running")
    (when (not= actual-port port)
      (println (str "  (port " port " was busy — using " actual-port ")")))
    (println (str "  engine:  http://" host ":" actual-port "/spel.js"))
    (println (str "  connect: " connect-url))
    (println (str "  harness: " page))
    (println (str "  token:   " token))
    (println)
    (println "Embed in a page (same box, sidesteps CDP lockdown):")
    (println (str "  <script src=\"http://" host ":" actual-port "/spel.js\"></script>"))
    (println (str "  <script>window.__spel.connect({url:\"" url "\",token:\"" token "\"})</script>"))
    (println)
    (println "Route regular commands from this box: spel bridge use  (picks up the token automatically)")
    (println "Press Ctrl-C to stop.")
    (flush)
    (write-runtime! {:url url :port actual-port :path path :token token})
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (clear-runtime!) (stop))))
    (try
      @(promise) ; block forever
      (catch InterruptedException _ (clear-runtime!) (stop)))
    bridge))
