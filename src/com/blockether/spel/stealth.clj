(ns com.blockether.spel.stealth
  "Stealth mode for browser automation anti-detection.

   Provides Chrome launch arguments, default-arg suppressions, and JavaScript
   init scripts that hide Playwright/automation signals from bot-detection
   systems. Based on puppeteer-extra-plugin-stealth evasions.

   Usage:
     (stealth-args)                ;; Chrome launch args
     (stealth-ignore-default-args) ;; args to suppress
     (stealth-init-script)         ;; JS evasion script for addInitScript")

(defn stealth-args
  "Returns a vector of extra Chrome launch arguments for stealth mode.
   These disable automation-detection features in Blink."
  []
  ["--disable-blink-features=AutomationControlled"])

(defn stealth-ignore-default-args
  "Returns a vector of Chromium default args to suppress in stealth mode.
   Removing --enable-automation prevents the 'Chrome is being controlled
   by automated software' infobar and related automation signals."
  []
  ["--enable-automation"])

(defn stealth-init-script
  "Returns a JavaScript string containing all stealth evasion patches.
   Inject via BrowserContext.addInitScript before page creation.

   Patches (based on puppeteer-extra-plugin-stealth):
   1. navigator.webdriver — returns undefined
   2. navigator.plugins — emulates Chrome PDF plugins
   3. navigator.languages — returns ['en-US', 'en']
   4. chrome.runtime — mocks connect/sendMessage
   5. navigator.permissions.query — fixes notification permission
   6. WebGL vendor/renderer — returns realistic GPU strings
   7. window.outerWidth/outerHeight — matches inner dimensions
   8. iframe contentWindow — prevents contentWindow detection"
  []
  (str
    "(function() {\n"
    "  // 1. navigator.webdriver — hide automation flag\n"
    "  Object.defineProperty(navigator, 'webdriver', {get: () => undefined});\n"
    "\n"
    "  // 2. navigator.plugins — emulate Chrome PDF plugins\n"
    "  Object.defineProperty(navigator, 'plugins', {\n"
    "    get: () => {\n"
    "      const plugins = [\n"
    "        {name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format'},\n"
    "        {name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: ''},\n"
    "        {name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: ''}\n"
    "      ];\n"
    "      plugins.length = 3;\n"
    "      return plugins;\n"
    "    }\n"
    "  });\n"
    "\n"
    "  // 3. navigator.languages — return realistic language list\n"
    "  Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en']});\n"
    "\n"
    "  // 4. chrome.runtime — mock window.chrome\n"
    "  if (!window.chrome) window.chrome = {};\n"
    "  if (!window.chrome.runtime) {\n"
    "    window.chrome.runtime = {\n"
    "      connect: function() {},\n"
    "      sendMessage: function() {}\n"
    "    };\n"
    "  }\n"
    "\n"
    "  // 5. navigator.permissions — fix notification permission query\n"
    "  const originalQuery = window.navigator.permissions.query;\n"
    "  window.navigator.permissions.query = (parameters) => (\n"
    "    parameters.name === 'notifications'\n"
    "      ? Promise.resolve({state: Notification.permission, onchange: null})\n"
    "      : originalQuery(parameters)\n"
    "  );\n"
    "\n"
    "  // 6. WebGL vendor/renderer — return realistic GPU strings\n"
    "  const getParameter = WebGLRenderingContext.prototype.getParameter;\n"
    "  WebGLRenderingContext.prototype.getParameter = function(parameter) {\n"
    "    if (parameter === 37445) return 'Google Inc. (NVIDIA)';\n"
    "    if (parameter === 37446) return 'ANGLE (NVIDIA, NVIDIA GeForce GTX 1050 Direct3D11 vs_5_0 ps_5_0, D3D11)';\n"
    "    return getParameter.call(this, parameter);\n"
    "  };\n"
    "\n"
    "  // 7. window.outerWidth/outerHeight — match inner dimensions\n"
    "  Object.defineProperty(window, 'outerWidth', {get: () => window.innerWidth});\n"
    "  Object.defineProperty(window, 'outerHeight', {get: () => window.innerHeight});\n"
    "\n"
    "  // 8. iframe contentWindow — prevent detection\n"
    "  try {\n"
    "    if (window.self !== window.top) {\n"
    "      Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {\n"
    "        get: function() { return window; }\n"
    "      });\n"
    "    }\n"
    "  } catch(e) {}\n"
    "})();"))
