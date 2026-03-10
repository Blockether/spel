(ns com.blockether.spel.cli
  "CLI client for the spel daemon.

   Parses command-line arguments into JSON commands, sends them to the
   daemon over a Unix domain socket, and pretty-prints the results.

   If the daemon isn't running, it auto-starts one in the background.

   Usage:
     spel open https://example.org
     spel snapshot
     spel click @ref
     spel fill @ref \"search text\"
     spel screenshot shot.png
     spel close"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.daemon :as daemon])
  (:import
   [java.io BufferedReader InputStreamReader OutputStreamWriter]
   [java.net StandardProtocolFamily UnixDomainSocketAddress]
   [java.nio.channels Channels SocketChannel]
   [java.nio.file Files]))

(defn- looks-like-url?
  "Returns true if the string looks like a URL (has a scheme, contains a dot,
   or contains a slash). Used to disambiguate URLs from stray flag values."
  [s]
  (or (str/starts-with? s "http://")
    (str/starts-with? s "https://")
    (str/starts-with? s "file://")
    (str/starts-with? s "data:")
    (str/starts-with? s "about:")
    (str/starts-with? s "chrome:")
    (str/starts-with? s "javascript:")
    (str/starts-with? s "blob:")
    (str/includes? s ".")
    (str/includes? s "/")
    (str/starts-with? s "localhost")))

;; =============================================================================
;; Per-Command Help
;; =============================================================================

(def command-help
  "Help text for each CLI command. Keys are primary command names."
  {"open"
   (str/join \newline
     ["open - Navigate to a URL"
      ""
      "Aliases: open, goto, navigate"
      ""
      "Usage:"
      "  spel open <url>"
      "  spel open <url> [flags]"
      "  spel open [flags] <url>"
      ""
      "The URL can appear at any position — before or after flags."
      ""
      "Examples:"
      "  spel open https://example.org"
      "  spel open example.org"
      "  spel open file:///tmp/page.html"
      "  spel open https://example.org --interactive"
      "  spel open --viewport 1200x800 https://example.org"
      "  spel open https://example.org --screenshot page.png"
      "  spel open https://example.org --screenshot"
      "  spel open https://example.org --viewport 1200x800"
      ""
      "Flags:"
      "  --interactive          Show browser window (headed mode, alias for --headed)"
      "  --screenshot [path]    Take screenshot after navigation; saves to <path> or"
      "                         a timestamped file in the system temp dir if omitted"
      "  --viewport <WxH>       Set viewport size before navigation (e.g. 1200x800)"])

   "back"
   (str/join \newline
     ["back - Go back in browser history"
      ""
      "Usage:"
      "  spel back"
      ""
      "Examples:"
      "  spel back"])

   "forward"
   (str/join \newline
     ["forward - Go forward in browser history"
      ""
      "Usage:"
      "  spel forward"
      ""
      "Examples:"
      "  spel forward"])

   "reload"
   (str/join \newline
     ["reload - Reload the current page"
      ""
      "Usage:"
      "  spel reload"
      ""
      "Examples:"
      "  spel reload"])

   "snapshot"
   (str/join \newline
     ["snapshot - Capture accessibility tree with numbered refs"
      ""
      "Usage:"
      "  spel snapshot [flags]"
      ""
      "Examples:"
      "  spel snapshot"
      "  spel snapshot -i"
      "  spel snapshot -i -c"
      "  spel snapshot -i -c -d 3"
      "  spel snapshot -s \"#main\""
      "  spel snapshot -a"
      "  spel snapshot -i -C"
      "  spel snapshot -F"
      ""
      "Flags:"
      "  -i, --interactive    Interactive elements only"
      "  -c, --compact        Compact output format"
      "  -C, --cursor         Include cursor/pointer elements"
      "  -F, --flat           Flat output (no nesting, all elements at same level)"
      "  -d, --depth N        Limit tree depth to N levels"
      "  -s, --selector SEL   Scope snapshot to CSS selector"
      "  -a, --all            Include all iframes in snapshot"
      "  -S, --styles         Include computed CSS styles per element"
      "      --minimal        Styles: 16 core properties (with -S)"
      "      --max            Styles: 44 properties (with -S, default: 31)"])

   "click"
   (str/join \newline
     ["click - Click an element"
      ""
      "Usage:"
      "  spel click <selector>"
      ""
      "Examples:"
      "  spel click @ref"
      "  spel click \"#submit-btn\""
      "  spel click \"text=Login\""])

   "dblclick"
   (str/join \newline
     ["dblclick - Double-click an element"
      ""
      "Usage:"
      "  spel dblclick <selector>"
      ""
      "Examples:"
      "  spel dblclick @ref"
      "  spel dblclick \".editable-cell\""])

   "fill"
   (str/join \newline
     ["fill - Clear and fill an input element"
      ""
      "Usage:"
      "  spel fill <selector> <text>"
      ""
      "Examples:"
      "  spel fill @ref \"user@example.org\""
      "  spel fill \"#search\" \"search query\""])

   "type"
   (str/join \newline
     ["type - Type text without clearing the input first"
      ""
      "Usage:"
      "  spel type <selector> <text>"
      ""
      "Examples:"
      "  spel type @ref \"additional text\""
      "  spel type \"#editor\" \"appended content\""])

   "clear"
   (str/join \newline
     ["clear - Clear an input element"
      ""
      "Usage:"
      "  spel clear <selector>"
      ""
      "Examples:"
      "  spel clear @ref"
      "  spel clear \"#search\""])

   "press"
   (str/join \newline
     ["press - Press a keyboard key"
      ""
      "Aliases: press, key"
      ""
      "Usage:"
      "  spel press <key>"
      "  spel press <selector> <key>"
      ""
      "Examples:"
      "  spel press Enter"
      "  spel press Tab"
      "  spel press Control+a"
      "  spel press @ref Enter"
      "  spel key Escape"])

   "keydown"
   (str/join \newline
     ["keydown - Hold a key down"
      ""
      "Usage:"
      "  spel keydown <key>"
      ""
      "Examples:"
      "  spel keydown Shift"
      "  spel keydown Control"])

   "keyup"
   (str/join \newline
     ["keyup - Release a held key"
      ""
      "Usage:"
      "  spel keyup <key>"
      ""
      "Examples:"
      "  spel keyup Shift"
      "  spel keyup Control"])

   "hover"
   (str/join \newline
     ["hover - Hover over an element"
      ""
      "Usage:"
      "  spel hover <selector>"
      ""
      "Examples:"
      "  spel hover @ref"
      "  spel hover \".dropdown-trigger\""])

   "mouse"
   (str/join \newline
     ["mouse - Low-level mouse control"
      ""
      "Usage:"
      "  spel mouse <subcommand> [args]"
      ""
      "Subcommands:"
      "  move <x> <y>      Move mouse to coordinates"
      "  down [button]      Press mouse button (default: left)"
      "  up [button]        Release mouse button (default: left)"
      "  wheel <deltaY>     Scroll mouse wheel"
      ""
      "Examples:"
      "  spel mouse move 100 200"
      "  spel mouse down"
      "  spel mouse up"
      "  spel mouse down right"
      "  spel mouse wheel 300"])

   "check"
   (str/join \newline
     ["check - Check a checkbox or radio button"
      ""
      "Usage:"
      "  spel check <selector>"
      ""
      "Examples:"
      "  spel check @ref"
      "  spel check \"#agree-terms\""])

   "uncheck"
   (str/join \newline
     ["uncheck - Uncheck a checkbox"
      ""
      "Usage:"
      "  spel uncheck <selector>"
      ""
      "Examples:"
      "  spel uncheck @ref"
      "  spel uncheck \"#newsletter\""])

   "select"
   (str/join \newline
     ["select - Select a dropdown option"
      ""
      "Usage:"
      "  spel select <selector> <value> [value...]"
      ""
      "Examples:"
      "  spel select @ref \"option1\""
      "  spel select \"#country\" \"US\""
      "  spel select @ref \"opt1\" \"opt2\""])

   "focus"
   (str/join \newline
     ["focus - Focus an element"
      ""
      "Usage:"
      "  spel focus <selector>"
      ""
      "Examples:"
      "  spel focus @ref"
      "  spel focus \"#email-input\""])

   "scroll"
   (str/join \newline
     ["scroll - Scroll the page or an element"
      ""
      "Usage:"
      "  spel scroll [direction] [amount] [selector]"
      "  spel scroll [direction] [amount] --in <selector>"
      ""
      "Arguments:"
      "  direction    up, down, left, or right (default: down)"
      "  amount       Pixels to scroll (default: 500)"
      "  selector     Element ref (@ref) or CSS selector to scroll within"
      ""
      "Flags:"
      "  -S, --smooth    Smooth animated scroll (default: instant jump)"
      "  --in <sel>      Element to scroll within (alternative to positional selector)"
      ""
      "Examples:"
      "  spel scroll                          Scroll page down 500px (instant)"
      "  spel scroll down 1000               Scroll page down 1000px"
      "  spel scroll up 500 --smooth          Smooth scroll page up 500px"
      "  spel scroll down 300 @ref           Scroll within element by ref"
      "  spel scroll down 500 --in #sidebar   Scroll within #sidebar element"
      "  spel scroll -S down 800              Smooth scroll shorthand"])

   "scrollintoview"
   (str/join \newline
     ["scrollintoview - Scroll an element into view"
      ""
      "Aliases: scrollintoview, scrollinto"
      ""
      "Usage:"
      "  spel scrollintoview <selector>"
      ""
      "Examples:"
      "  spel scrollintoview @ref"
      "  spel scrollinto \"#footer\""])

   "drag"
   (str/join \newline
     ["drag - Drag an element to another element"
      ""
      "Usage:"
      "  spel drag <source> <target> [flags]"
      ""
      "Flags:"
      "  --steps N          Intermediate mousemove events (default 1)."
      "                     Higher values produce smoother drags."
      "  --force            Bypass actionability checks."
      "  --timeout N        Maximum time in ms."
      ""
      "Examples:"
      "  spel drag @ref @ref2"
      "  spel drag \"#item\" \"#dropzone\""
      "  spel drag @ref @ref2 --steps 10"
      "  spel drag @ref @ref2 --force"])

   "drag-by"
   (str/join \newline
     ["drag-by - Drag an element by pixel offset"
      ""
      "Drags from the element's center by (dx, dy) pixels using a mouse"
      "event sequence: move -> mousedown -> mousemove(+dx,+dy) -> mouseup."
      ""
      "Usage:"
      "  spel drag-by <selector> <dx> <dy> [flags]"
      ""
      "Flags:"
      "  --steps N          Intermediate mousemove events (default 1)."
      "                     Higher values produce smoother drags."
      ""
      "Examples:"
      "  spel drag-by @ref 200 0"
      "  spel drag-by \"#card\" -100 50 --steps 10"])

   "upload"
   (str/join \newline
     ["upload - Upload files to a file input"
      ""
      "Usage:"
      "  spel upload <selector> <file> [file...]"
      ""
      "Examples:"
      "  spel upload @ref photo.jpg"
      "  spel upload \"input[type=file]\" doc.pdf image.png"])

   "download"
   (str/join \newline
     ["download - Click an element and save the triggered download"
      ""
      "Usage:"
      "  spel download <selector> <save-path>"
      ""
      "Examples:"
      "  spel download \"#export-btn\" ./report.csv"
      "  spel download @e5 ./file.zip"
      ""
      "Flags:"
      "  --timeout <ms>            Download timeout in milliseconds"])

   "screenshot"
   (str/join \newline
     ["screenshot - Take a screenshot"
      ""
      "Usage:"
      "  spel screenshot [path] [flags]"
      ""
      "Examples:"
      "  spel screenshot"
      "  spel screenshot page.png"
      "  spel screenshot -f full.png"
      "  spel screenshot --crop-to-content cropped.png"
      ""
      "Flags:"
      "  -f, --full-page, --full    Capture full page (not just viewport)"
      "  --crop-to-content          Crop screenshot to actual content height"])

   "annotate"
   (str/join \newline
     ["annotate - Inject visual annotation overlays onto the page"
      ""
      "Usage:"
      "  spel annotate [flags]"
      ""
      "Examples:"
      "  spel annotate"
      "  spel annotate --no-badges"
      "  spel annotate -s \"#main\""
      "  spel annotate --no-boxes --no-dims"
      ""
      "Flags:"
      "  -f, --full                Annotate all elements (not just viewport)"
      "  --no-badges               Hide element type badges"
      "  --no-dimensions, --no-dims  Hide dimension overlays"
      "  --no-boxes                Hide bounding boxes"
      "  -s, --scope SEL           Scope annotations to selector"])

   "unannotate"
   (str/join \newline
     ["unannotate - Remove annotation overlays from the page"
      ""
      "Usage:"
      "  spel unannotate"
      ""
      "Examples:"
      "  spel unannotate"])

   "survey"
   (str/join \newline
     ["survey - Scroll full page and save viewport screenshots"
      ""
      "Usage:"
      "  spel survey [flags]"
      ""
      "Examples:"
      "  spel survey"
      "  spel survey --max-frames 5"
      "  spel survey -o ./shots --overlap 120"
      "  spel survey -a"
      ""
      "Flags:"
      "  -a, --annotate            Annotate each frame before screenshot"
      "  -o, --output-dir DIR      Directory for output frames"
      "  --overlap PX              Pixel overlap between frames"
      "  --max-frames N            Maximum frames to capture"])

   "audit"
   (str/join \newline
     ["audit - Run page quality audits (all or specific)"
      ""
      "Usage:"
      "  spel audit                    Run all audits at once"
      "  spel audit <subcommand>       Run a specific audit"
      ""
      "Subcommands:"
      "  structure    Discover page landmarks and sections"
      "  contrast     Audit WCAG text contrast for all visible text"
      "  colors       Extract the color palette used on the page"
      "  layout       Detect layout issues (overflow, overlap, alignment)"
      "  fonts        Audit font usage consistency across the page"
      "  links        Check all links for health (status codes, broken links)"
      "  headings     Analyze heading hierarchy (h1-h6) for structure issues"
      ""
      "Flags:"
      "  --only LIST   Comma-separated list of audits to run (e.g. --only contrast,layout)"
      ""
      "Examples:"
      "  spel audit                        # run all 7 audits"
      "  spel audit structure              # page landmarks only"
      "  spel audit contrast               # WCAG contrast only"
      "  spel audit --only fonts,links     # selective"])

   "routes"
   (str/join \newline
     ["routes - Extract links from the current page"
      ""
      "Usage:"
      "  spel routes [flags]"
      ""
      "Examples:"
      "  spel routes"
      "  spel routes --internal"
      "  spel routes --visible"
      ""
      "Flags:"
      "  --internal, --internal-only  Include same-origin links only"
      "  --visible, --visible-only    Include visible links only"])

   "inspect"
   (str/join \newline
     ["inspect - Interactive snapshot with computed styles (agent view)"
      ""
      "Usage:"
      "  spel inspect [flags]"
      ""
      "Examples:"
      "  spel inspect"
      "  spel inspect --minimal"
      "  spel inspect --max -s \"#main\""
      ""
      "Flags:"
      "  -s, --scope SEL            Scope inspect snapshot to selector"
      "  --minimal                  Use minimal style detail"
      "  --max                      Use max style detail"])

   "overview"
   (str/join \newline
     ["overview - Annotated full-page screenshot"
      ""
      "Usage:"
      "  spel overview [path] [flags]"
      ""
      "Examples:"
      "  spel overview"
      "  spel overview page-overview.png"
      "  spel overview --no-badges --no-boxes"
      "  spel overview --all                   # include iframe content"
      ""
      "Flags:"
      "  -a, --all                 Include iframe content (capture-full-snapshot)"
      "  --no-badges               Hide element type badges"
      "  --no-dimensions, --no-dims  Hide dimensions"
      "  --no-boxes                Hide bounding boxes"
      "  -s, --scope SEL           Scope overview annotations to selector"])

   "debug"
   (str/join \newline
     ["debug - Page diagnostic snapshot"
      ""
      "Usage:"
      "  spel debug [flags]"
      ""
      "Examples:"
      "  spel debug"
      "  spel debug --clear"
      "  spel debug --json"
      ""
      "Collects:"
      "  - Performance timing (DNS, TTFB, DOM complete, page load)"
      "  - Console errors and warnings"
      "  - Uncaught JS exceptions"
      "  - Failed network requests (4xx/5xx)"
      "  - Failed resource loads"
      "  - DOM statistics and dimensions"
      "  --clear                   Clear console/error buffers after reading"])
   "emulate"
   (str/join \newline
     ["emulate - Device emulation with annotated overview"
      ""
      "Usage:"
      "  spel emulate <device> [path] [flags]"
      ""
      "Examples:"
      "  spel emulate 'iPhone 14'"
      "  spel emulate 'Pixel 7' mobile-view.png"
      "  spel emulate 'iPad Pro 11' --no-badges"
      ""
      "Sets device emulation (viewport, user-agent, touch) and takes an"
      "annotated full-page overview screenshot in one command."
      ""
      "Flags:"
      "  -a, --all                 Include iframe content"
      "  --no-badges               Hide element type badges"
      "  --no-dimensions, --no-dims  Hide dimensions"
      "  --no-boxes                Hide bounding boxes"])

   "pdf"
   (str/join \newline
     ["pdf - Save page as PDF (Chromium only)"
      ""
      "Usage:"
      "  spel pdf [path]"
      ""
      "Examples:"
      "  spel pdf"
      "  spel pdf page.pdf"])

   "eval-js"
   (str/join \newline
     ["eval-js - Evaluate JavaScript in the page context"
      ""
      "Usage:"
      "  spel eval-js <script>"
      "  spel eval-js --stdin"
      ""
      "Examples:"
      "  spel eval-js \"document.title\""
      "  spel eval-js \"document.querySelector('h1').textContent\""
      "  spel eval-js -b \"JSON.stringify(data)\""
      "  echo 'document.title' | spel eval-js --stdin"
      ""
      "Flags:"
      "  -b, --base64    Base64-encode the result"
      "  --stdin          Read script from stdin"])

   "wait"
   (str/join \newline
     ["wait - Wait for a condition"
      ""
      "Usage:"
      "  spel wait [selector]"
      "  spel wait <timeout-ms>"
      "  spel wait --text <text>"
      "  spel wait --url <pattern>"
      "  spel wait --fn <expression>"
      "  spel wait --load <state>"
      ""
      "Examples:"
      "  spel wait @ref"
      "  spel wait 2000"
      "  spel wait --text \"Welcome\""
      "  spel wait --url \"**/dashboard\""
      "  spel wait --fn \"window.appReady\""
      "  spel wait --load networkidle"
      ""
      "Flags:"
      "  --text TEXT       Wait for text to appear on page"
      "  --url PATTERN     Wait for URL to match pattern"
      "  --fn EXPR         Wait for JavaScript expression to be truthy"
      "  --load STATE      Wait for load state (load, domcontentloaded, networkidle)"])

   "tab"
   (str/join \newline
     ["tab - Manage browser tabs"
      ""
      "Usage:"
      "  spel tab [subcommand] [args]"
      ""
      "Subcommands:"
      "  (none)        List all tabs"
      "  list          List all tabs"
      "  new [url]     Open a new tab, optionally navigating to URL"
      "  close         Close the current tab"
      "  <n>           Switch to tab by index (0-based)"
      ""
      "Examples:"
      "  spel tab"
      "  spel tab list"
      "  spel tab new https://example.org"
      "  spel tab 0"
      "  spel tab close"])

   "get"
   (str/join \newline
     ["get - Get page information"
      ""
      "Usage:"
      "  spel get <what> [selector] [args]"
      ""
      "Subcommands:"
      "  text <sel>            Get text content of element"
      "  html <sel>            Get innerHTML of element"
      "  value <sel>           Get input value"
      "  attr <sel> <name>     Get attribute value"
      "  attribute <sel> <name>  Get attribute value"
      "  url                   Get current page URL"
      "  title                 Get current page title"
      "  count <sel>           Count matching elements"
      "  box <sel>             Get bounding box {x, y, width, height}"
      ""
      "Examples:"
      "  spel get text @ref"
      "  spel get url"
      "  spel get title"
      "  spel get html @ref"
      "  spel get value @ref"
      "  spel get attr @ref href"
      "  spel get count \".items\""
      "  spel get box @ref"])

   "is"
   (str/join \newline
     ["is - Check element state"
      ""
      "Usage:"
      "  spel is <what> <selector>"
      ""
      "Subcommands:"
      "  visible <sel>    Check if element is visible"
      "  enabled <sel>    Check if element is enabled"
      "  checked <sel>    Check if element is checked"
      ""
      "Examples:"
      "  spel is visible @ref"
      "  spel is enabled @ref"
      "  spel is checked @ref"])

   "count"
   (str/join \newline
     ["count - Count elements matching a selector"
      ""
      "Usage:"
      "  spel count <selector>"
      ""
      "Examples:"
      "  spel count \".items\""
      "  spel count \"li\""])

   "bbox"
   (str/join \newline
     ["bbox - Get the bounding box of an element"
      ""
      "Usage:"
      "  spel bbox <selector>"
      ""
      "Examples:"
      "  spel bbox @ref"
      "  spel bbox \"#header\""])

   "highlight"
   (str/join \newline
     ["highlight - Visually highlight an element on the page"
      ""
      "Usage:"
      "  spel highlight <selector>"
      ""
      "Examples:"
      "  spel highlight @ref"
      "  spel highlight \".target\""])

   "find"
   (str/join \newline
     ["find - Find elements by semantic locator and optionally interact"
      ""
      "Usage:"
      "  spel find <type> <value> [action] [action-value]"
      "  spel find <role-name> [action] [action-value]"
      ""
      "Find types:"
      "  role <name>           By ARIA role"
      "  text <text>           By text content"
      "  label <text>          By associated label"
      "  placeholder <text>    By placeholder text"
      "  alt <text>            By alt text"
      "  title <text>          By title attribute"
      "  testid <id>           By test ID"
      "  first <sel>           First matching element"
      "  last <sel>            Last matching element"
      "  nth <n> <sel>         Nth matching element"
      ""
      "ARIA role shortcuts:"
      "  Unknown find types are treated as ARIA role names."
      "  spel find link click  is equivalent to  spel find role link click"
      ""
      "Examples:"
      "  spel find role button click"
      "  spel find role button click --name Submit"
      "  spel find text \"Login\" click"
      "  spel find label \"Email\" fill \"user@example.org\""
      "  spel find link click"
      "  spel find heading text"
      "  spel find first \".item\" click"
      "  spel find nth 2 \".item\" click"
      ""
      "Flags:"
      "  --name VALUE    Filter by accessible name"
      "  --exact         Require exact text match"])

   "set"
   (str/join \newline
     ["set - Configure browser settings"
      ""
      "Usage:"
      "  spel set <setting> [args]"
      ""
      "Subcommands:"
      "  viewport <width> <height>    Set viewport size"
      "  device <name>                Emulate device"
      "  geo <lat> <lon>              Set geolocation"
      "  offline [on|off]             Toggle offline mode"
      "  headers <json>               Set extra HTTP headers"
      "  credentials <user> <pass>    Set HTTP basic auth"
      "  media <scheme>               Emulate color scheme (dark/light)"
      ""
      "Examples:"
      "  spel set viewport 1280 720"
      "  spel set device \"iPhone 14\""
      "  spel set geo 37.7749 -122.4194"
      "  spel set offline on"
      "  spel set offline off"
      "  spel set headers '{\"X-Custom\":\"value\"}'"
      "  spel set credentials admin secret"
      "  spel set media dark"])

   "cookies"
   (str/join \newline
     ["cookies - Manage browser cookies"
      ""
      "Usage:"
      "  spel cookies [subcommand] [args]"
      ""
      "Subcommands:"
      "  (none)              List all cookies"
      "  set <name> <value>  Set a cookie"
      "  clear               Clear all cookies"
      ""
      "Examples:"
      "  spel cookies"
      "  spel cookies set session_id abc123"
      "  spel cookies clear"])

   "storage"
   (str/join \newline
     ["storage - Manage web storage (localStorage / sessionStorage)"
      ""
      "Usage:"
      "  spel storage <type> [subcommand] [args]"
      ""
      "Arguments:"
      "  type    local or session"
      ""
      "Subcommands:"
      "  (none)              List all entries"
      "  <key>               Get value by key"
      "  set <key> <value>   Set a key-value pair"
      "  clear               Clear all entries"
      ""
      "Examples:"
      "  spel storage local"
      "  spel storage local token"
      "  spel storage local set theme dark"
      "  spel storage local clear"
      "  spel storage session"
      "  spel storage session set cart_id abc"])

   "network"
   (str/join \newline
     ["network - Network inspection and request interception"
      ""
      "Usage:"
      "  spel network                    List recent network entries"
      "  spel network get @n1            Get full details for a network ref"
      "  spel network <subcommand>       Run a network subcommand"
      ""
      "Subcommands:"
      "  get <@ref>            Get full network entry by ref (e.g. @n1)"
      "  requests [flags]      View tracked requests (auto-tracked, last 500)"
      "  route <url> [flags]   Intercept requests matching URL pattern"
      "  unroute <url>         Remove route for URL pattern"
      "  clear                 Clear tracked requests"
      ""
      "Request flags:"
      "  --filter REGEX    Filter by URL regex"
      "  --type TYPE       Filter by resource type (fetch, document, script, image, etc.)"
      "  --method METHOD   Filter by HTTP method (GET, POST, etc.)"
      "  --status PREFIX   Filter by status prefix (2 = 2xx, 404 = exact)"
      ""
      "Route flags:"
      "  --abort           Block matching requests"
      "  --body JSON       Fulfill with mock response body"
      ""
      "Examples:"
      "  spel network"
      "  spel network get @n1"
      "  spel network requests"
      "  spel network requests --type fetch --status 4"
      "  spel network requests --filter \"/api\""
      "  spel network route \"**/api/**\""
      "  spel network route \"**/ads/**\" --abort"
      "  spel network route \"**/data\" --body '{\"mock\":true}'"
      "  spel network unroute \"**/api/**\""
      "  spel network clear"])

   "frame"
   (str/join \newline
     ["frame - Navigate between frames and iframes"
      ""
      "Usage:"
      "  spel frame [subcommand]"
      ""
      "Subcommands:"
      "  (none)        Switch to main frame"
      "  main          Switch to main frame"
      "  list          List all frames"
      "  <selector>    Switch to frame by CSS selector"
      ""
      "Examples:"
      "  spel frame list"
      "  spel frame main"
      "  spel frame \"iframe#content\""])

   "dialog"
   (str/join \newline
     ["dialog - Handle browser dialogs (alert, confirm, prompt)"
      ""
      "Usage:"
      "  spel dialog <subcommand> [text]"
      ""
      "Subcommands:"
      "  accept [text]    Accept the dialog, optionally with input text"
      "  dismiss          Dismiss the dialog"
      ""
      "Examples:"
      "  spel dialog accept"
      "  spel dialog accept \"my input\""
      "  spel dialog dismiss"])

   "trace"
   (str/join \newline
     ["trace - Record a Playwright trace for debugging"
      ""
      "Usage:"
      "  spel trace <subcommand> [args]"
      ""
      "Subcommands:"
      "  start [name]     Start recording a trace"
      "  stop [path]      Stop recording and save trace file"
      ""
      "Examples:"
      "  spel trace start"
      "  spel trace start my-trace"
      "  spel trace stop"
      "  spel trace stop trace.zip"])

   "console"
   (str/join \newline
     ["console - View captured console messages"
      ""
      "Console messages are automatically captured from the moment a page opens."
      ""
      "Usage:"
      "  spel console                    List recent console entries"
      "  spel console get @c1            Get full details for a console ref"
      "  spel console [subcommand]"
      ""
      "Subcommands:"
      "  get <@ref>    Get console entry by ref (e.g. @c1)"
      "  clear         Clear captured messages"
      ""
      "Examples:"
      "  spel console"
      "  spel console get @c1"
      "  spel console clear"])

   "errors"
   (str/join \newline
     ["errors - View captured page errors"
      ""
      "Page errors are automatically captured from the moment a page opens."
      ""
      "Usage:"
      "  spel errors [subcommand]"
      ""
      "Subcommands:"
      "  (none)    View all captured page errors"
      "  clear     Clear captured errors"
      ""
      "Examples:"
      "  spel errors"
      "  spel errors clear"])

   "pages"
   (str/join \newline
     ["pages - View tracked page navigations"
      ""
      "Usage:"
      "  spel pages                      List all tracked page navigations"
      "  spel pages get @p1              Get details for a page ref"
      ""
      "Subcommands:"
      "  get <@ref>    Get page entry by ref (e.g. @p1)"
      ""
      "Examples:"
      "  spel pages"
      "  spel pages get @p1"])

   "state"
   (str/join \newline
     ["state - Manage persistent browser state (cookies, storage, auth)"
      ""
      "Usage:"
      "  spel state <subcommand> [args]"
      ""
      "Subcommands:"
      "  save [path]                Save current state"
      "  load [path]                Load saved state"
      "  list                       List saved state files"
      "  show <file>                Show contents of a state file"
      "  rename <old> <new>         Rename a state file"
      "  clear [name] [--all]       Clear state file(s)"
      "  clean [--older-than N]     Remove states older than N days"
      ""
      "Examples:"
      "  spel state save"
      "  spel state save auth.json"
      "  spel state load auth.json"
      "  spel state list"
      "  spel state show auth.json"
      "  spel state rename old.json new.json"
      "  spel state clear auth.json"
      "  spel state clear --all"
      "  spel state clean --older-than 30"])

   "session"
   (str/join \newline
     ["session - Manage browser sessions"
      ""
      "Usage:"
      "  spel session [subcommand]"
      ""
      "Subcommands:"
      "  (none)    Show current session info"
      "  list      List all active sessions"
      ""
      "Examples:"
      "  spel session"
      "  spel session list"
      "  spel --session work open https://example.org"])

   "connect"
   (str/join \newline
     ["connect - Connect to a browser via Chrome DevTools Protocol"
      ""
      "Usage:"
      "  spel connect <url>"
      ""
      "Examples:"
      "  spel connect ws://localhost:9222"])

   "close"
   (str/join \newline
     ["close - Close the browser and stop the daemon"
      ""
      "Aliases: close, quit, exit"
      ""
      "Usage:"
      "  spel close                    Close current session (default)"
      "  spel close --all-sessions     Close all active sessions"
      "  spel --session NAME close     Close a specific named session"
      ""
      "Flags:"
      "  --all-sessions    Close every active daemon session"
      ""
      "Examples:"
      "  spel close"
      "  spel close --all-sessions"
      "  spel --session work close"
      "  spel quit"
      "  spel exit"])

   "install"
   (str/join \newline
     ["install - Install Playwright browsers"
      ""
      "Usage:"
      "  spel install [flags]"
      ""
      "Examples:"
      "  spel install"
      "  spel install --with-deps"
      ""
      "Flags:"
      "  --with-deps    Install system dependencies alongside browsers"])

   "codegen-record"
   (str/join \newline
     ["codegen record - Record browser session with Playwright Codegen"
      ""
      "Opens a headed browser with the Playwright Codegen recorder."
      "Interact with the page and actions are recorded to a file."
      "Defaults to --target=jsonl for use with `spel codegen` transform."
      "When -o is omitted, auto-generates recording-<timestamp>.jsonl."
      ""
      "Usage:"
      "  spel codegen record [options] [url]"
      ""
      "Examples:"
      "  spel codegen record https://example.org                       # auto-saves to recording-YYYYMMDD-HHmmss.jsonl"
      "  spel codegen record -o recording.jsonl https://example.org"
      "  spel codegen record --target=java https://example.org"
      "  spel codegen record -b firefox https://example.org"
      ""
      "Full workflow:"
      "  # 1. Record (opens browser with codegen panel)"
      "  spel codegen record -o recording.jsonl https://example.org"
      ""
      "  # 2. Record with pre-loaded auth state (cookies/localStorage)"
      "  spel codegen record --load-state auth-state.json -o recording.jsonl https://example.org"
      ""
      "  # 3. Record and save state on exit"
      "  spel codegen record --save-state auth-state.json -o recording.jsonl https://example.org"
      ""
      "  # 4. Transform to idiomatic Clojure"
      "  spel codegen recording.jsonl"
      "  spel codegen --format=script recording.jsonl"
      ""
      "Options:"
      "  -o, --output <file>           Save recording to file"
      "  --target <language>           Output format (default: jsonl)"
      "                                jsonl, javascript, python, java, csharp, etc."
      "  --test-id-attribute <attr>    Use attribute for test ID selectors"
      "  -b, --browser <type>          Browser: cr, ff, wk (default: chromium)"
      "  --channel <channel>           Chromium channel: chrome, msedge-dev, etc."
      "  --device <name>               Emulate device"
      "  --color-scheme <scheme>       light or dark"
      "  --viewport-size <w,h>         Viewport size"
      "  --geolocation <lat,lng>       Geolocation coordinates"
      "  --lang <locale>               Language locale"
      "  --timezone <tz>               Timezone"
      "  --proxy-server <url>          Proxy server"
      "  --ignore-https-errors         Ignore HTTPS errors"
      "  --load-state <file>           Load saved state (alias: --load-storage)"
      "  --save-state <file>           Save state on exit (alias: --save-storage)"
      "  --save-har <file>             Save HAR file on exit"])

   "inspector"
   (str/join \newline
     ["inspector - Launch Playwright Inspector"
      ""
      "Opens a headed browser with the Playwright Inspector attached."
      "Use the Inspector to explore the page, pick locators, and record actions."
      ""
      "Usage:"
      "  spel inspector [options] [url]"
      ""
      "Examples:"
      "  spel inspector"
      "  spel inspector https://example.org"
      "  spel inspector -b firefox https://example.org"
      "  spel inspector --device \"iPhone 14\" https://example.org"
      ""
      "Options:"
      "  -b, --browser <type>         Browser: cr, chromium, ff, firefox, wk, webkit (default: chromium)"
      "  --channel <channel>          Chromium channel: chrome, chrome-beta, msedge-dev, etc."
      "  --device <name>              Emulate device (e.g. \"iPhone 14\")"
      "  --color-scheme <scheme>      Color scheme: light, dark"
      "  --geolocation <lat,lng>      Geolocation coordinates"
      "  --lang <locale>              Language locale (e.g. en-GB)"
      "  --timezone <tz>              Timezone (e.g. Europe/Rome)"
      "  --viewport-size <w,h>        Viewport size (e.g. 1280,720)"
      "  --user-agent <ua>            Custom user agent"
      "  --proxy-server <url>         Proxy server"
      "  --ignore-https-errors        Ignore HTTPS errors"
      "  --load-state <file>          Load saved state (alias: --load-storage)"
      "  --save-state <file>          Save state on exit (alias: --save-storage)"
      "  --save-har <file>            Save HAR file on exit"
      "  --timeout <ms>               Action timeout in ms"
      "  --user-data-dir <dir>        Custom browser user data directory"])

   "stitch"
   (str/join \newline
     ["stitch - Stitch multiple screenshots vertically into one image"
      ""
      "Usage:"
      "  spel stitch <img1> <img2> [img3...] [-o output.png]"
      ""
      "Flags:"
      "  -o, --output   Output file path (default: /tmp/spel-stitched-<timestamp>.png)"
      "  --overlap <N>  Pixels to trim from top of each image after first (default: 0)"
      ""
      "Examples:"
      "  spel stitch s1.png s2.png s3.png"
      "  spel stitch s1.png s2.png -o full.png"
      "  spel stitch s1.png s2.png --overlap 50"])

   "search"
   (str/join \newline
     ["search - Search Google from the command line"
      ""
      "Searches Google using Playwright browser automation (no API key needed)."
      "Supports web, image, and news search with pagination."
      ""
      "Usage:"
      "  spel search <query> [options]"
      ""
      "Options:"
      "  --images              Search for images"
      "  --news                Search for news"
      "  --page N              Results page number (default: 1)"
      "  --num N               Results per page (default: 10)"
      "  --max-pages N         Collect N pages of results (default: 1)"
      "  --lang LANG           Language code (e.g. en, de, fr)"
      "  --safe MODE           Safe search: off, medium, high"
      "  --time-range RANGE    Time filter: day, week, month, year"
      "  --limit N             Show only first N results"
      "  --open N              Navigate to result #N and print its info"
      "  --json                Output as JSON"
      "  --screenshot PATH     Save screenshot of results page"
      "  --no-stealth          Disable stealth mode (stealth is ON by default)"
      "  --debug               Show diagnostics and save screenshot on failure"
      ""
      "Examples:"
      "  spel search \"clojure programming\""
      "  spel search \"cats\" --images"
      "  spel search \"world news\" --news --json"
      "  spel search \"rust lang\" --page 2 --num 20"
      "  spel search \"query\" --max-pages 3 --json"
      "  spel search \"query\" --limit 5"
      "  spel search \"query\" --open 1"
      "  spel search \"query\" --screenshot results.png"
      "  spel search \"query\" --lang en --time-range week"])

   "show-trace"
   (str/join \newline
     ["show-trace - Open Playwright Trace Viewer"
      ""
      "Opens the Playwright Trace Viewer to inspect recorded traces."
      "Traces are recorded via `spel trace start` / `spel trace stop`"
      "or automatically by test fixtures with Allure reporter active."
      ""
      "Usage:"
      "  spel show-trace [options] [trace-file]"
      ""
      "Examples:"
      "  spel show-trace"
      "  spel show-trace trace.zip"
      "  spel show-trace --port 8080 trace.zip"
      ""
      "Options:"
      "  -b, --browser <type>    Browser for viewer: cr, ff, wk (default: chromium)"
      "  -h, --host <host>       Host to serve trace on (opens in browser tab)"
      "  -p, --port <port>       Port to serve trace on (0 = any free port)"])

   "action-log"
   (str/join \newline
     ["action-log - View or export the daemon's action log"
      ""
      "The action log records all user-facing browser commands (click, navigate,"
      "fill, etc.) with timestamps. Use it to generate SRT subtitles for video"
      "recordings or to review what happened during a session."
      ""
      "Usage:"
      "  spel action-log              Show action log as JSON"
      "  spel action-log --srt        Export as SRT subtitle format"
      "  spel action-log --clear      Clear the action log"
      "  spel action-log --srt -o f   Export SRT to file"
      ""
      "Flags:"
      "  --srt           Output in SRT subtitle format"
      "  --clear         Clear the action log"
      "  -o, --output    Write output to file instead of stdout"
      "  --json          Force JSON output (default)"
      ""
      "Examples:"
      "  spel action-log"
      "  spel action-log --srt"
      "  spel action-log --srt -o session.srt"
      "  spel action-log --clear"
      ""
      "SRT subtitles can be used with video recordings:"
      "  1. Record video:  spel eval-sci '(spel/start-video-recording)'"
      "  2. Do actions:    spel click @e123, spel fill @e456 \"text\""
      "  3. Export SRT:    spel action-log --srt -o session.srt"
      "  4. Burn in:       ffmpeg -i video.webm -vf subtitles=session.srt out.mp4"])

   "styles"
   (str/join \newline
     ["styles - Get computed CSS styles for an element"
      ""
      "Usage:"
      "  spel styles <selector> [--full]"
      ""
      "Returns a curated set of computed CSS properties by default:"
      "  fontSize, fontWeight, fontFamily, color, backgroundColor,"
      "  borderRadius, border, boxShadow, padding, margin,"
      "  display, position, width, height, lineHeight, textAlign"
      ""
      "Use --full to return all 300+ computed CSS properties."
      ""
      "Examples:"
      "  spel styles h1"
      "  spel styles \"#main\" --full"
      "  spel styles @e3 --json"])

   "clipboard"
   (str/join \newline
     ["clipboard - Browser clipboard operations"
      ""
      "Usage:"
      "  spel clipboard copy <text>    Write text to clipboard"
      "  spel clipboard read           Read clipboard text"
      "  spel clipboard paste          Paste into focused element"
      ""
      "Clipboard permissions are granted automatically."
      ""
      "Examples:"
      "  spel clipboard copy \"Hello World\""
      "  spel clipboard read --json"
      "  spel clipboard paste"])

   "diff"
   (str/join \newline
     ["diff - Compare page states"
      ""
      "Usage:"
      "  spel diff snapshot --baseline <file>   Compare current snapshot vs baseline file"
      "  spel diff screenshot --baseline <file> Compare current page screenshot against a baseline PNG image"
      "                                      Outputs JSON with diff stats and saves diff image"
      "    --baseline <file>                 Path to baseline PNG file (required)"
      "    --threshold <0.0-1.0>             Matching sensitivity (default: 0.1, lower=stricter)"
      "    -o <path>                         Output path for diff image (default: temp file)"
      ""
      "The diff command compares the current page state against a saved baseline."
      "The baseline is a text file containing a previous snapshot output."
      ""
      "Returns added, removed, changed, and unchanged line counts."
      ""
      "Examples:"
      "  spel snapshot -i > baseline.txt"
      "  # ... make changes ..."
      "  spel diff snapshot --baseline baseline.txt"
      "  spel diff snapshot --baseline baseline.txt --json"])})
(defn top-level-help
  "Returns the top-level help string shown by `spel --help`."
  []
  (str/join \newline
    ["spel - Browser automation CLI"
     ""
     "Usage:"
     "  spel [flags] <command> [args]"
     "  spel <command> --help"
     ""
     "Navigation:"
     "  open, goto, navigate    Navigate to URL"
     "  back                    Go back"
     "  forward                 Go forward"
     "  reload                  Reload page"
     ""
     "Accessibility:"
     "  snapshot                Capture accessibility tree with refs"
     ""
     "Interactions:"
     "  click                   Click element"
     "  dblclick                Double-click element"
     "  fill                    Clear and fill input"
     "  type                    Type text (no clear)"
     "  clear                   Clear input"
     "  press, key              Press keyboard key"
     "  keydown                 Hold key down"
     "  keyup                   Release key"
     "  hover                   Hover element"
     "  mouse                   Mouse control (move, down, up, wheel)"
     "  check                   Check checkbox"
     "  uncheck                 Uncheck checkbox"
     "  select                  Select dropdown option"
     "  focus                   Focus element"
     "  scroll                  Scroll page"
     "  scrollintoview          Scroll element into view"
     "  drag                    Drag and drop"
     "  drag-by                 Drag by pixel offset"
     "  upload                  Upload files"
     "  download                 Download file (click + save)"
     ""
     "Capture:"
     "  screenshot              Take screenshot"
     "  stitch                  Stitch multiple screenshots vertically"
     "  annotate                Inject annotation overlays"
     "  unannotate              Remove annotation overlays"
     "  survey                  Sweep viewport screenshots down page"
     "  audit                   Page quality audits (structure, contrast, layout, fonts, links, headings, colors)"
     "  routes                  Extract links from page"
     "  inspect                 Interactive styled snapshot"
     "  overview                Full-page annotated screenshot"
     "  pdf                     Save page as PDF"
     ""
     "JavaScript:"
     "  eval-js                 Evaluate JavaScript"
     ""
     "Wait:"
     "  wait                    Wait for condition"
     ""
     "Tabs:"
     "  tab                     Manage tabs (new, list, close, switch)"
     ""
     "Getters:"
     "  get                     Get page info (text, url, title, html, value, attr, count, box)"
     ""
     "State Checks:"
     "  is                      Check element state (visible, enabled, checked)"
     ""
     "Element Info:"
     "  count                   Count matching elements"
     "  bbox                    Get bounding box"
     "  highlight               Highlight element"
     ""
     "Find:"
     "  find                    Semantic locators (role, text, label, etc.)"
     ""
     "Settings:"
     "  set                     Browser settings (viewport, device, geo, media, etc.)"
     ""
     "Storage:"
     "  cookies                 Manage cookies"
     "  storage                 Manage localStorage / sessionStorage"
     ""
     "Network:"
     "  network                 List/get network entries, intercept routes"
     ""
     "Pages:"
     "  pages                   List/get tracked page navigations"
     ""
     "Frames:"
     "  frame                   Navigate between frames"
     ""
     "Dialogs:"
     "  dialog                  Handle browser dialogs"
     ""
     "Debug:"
     "  trace                   Record Playwright traces"
     "  console                 List/get console messages"
     "  errors                  View page errors"
     ""
     "State Management:"
     "  state                   Save/load browser state"
     ""
     "Sessions:"
     "  session                 Manage browser sessions"
     ""
     "Connection:"
     "  connect                 Connect via CDP"
     ""
     "Search:"
     "  search <query>          Google search (web, images, news)"
     ""
     "Playwright Tools:"
     "  inspector [url]         Launch Playwright Inspector (headed browser)"
     "  show-trace [trace]      Open Playwright Trace Viewer"
     ""
     "Lifecycle:"
     "  close, quit, exit       Close browser and daemon"
     "  install                 Install Playwright browsers"
     ""
     "Global Flags:"
     "  --session NAME          Named browser session (default: \"default\")"
     "  --no-persist             Disable auto-persist of cookies/storage"
     "  --json                  JSON output mode"
     "  --interactive, --headed Show browser window (headed mode)"
     "  --proxy URL             HTTP proxy"
     "  --proxy-bypass DOMAINS  Proxy bypass list"
     "  --user-agent STRING     Custom User-Agent"
     "  --executable-path PATH  Custom browser binary"
     "  --args \"ARG1,ARG2\"     Extra browser arguments"
     "  --cdp URL               Connect via Chrome DevTools Protocol"
     "  --auto-connect          Auto-discover running Chrome/Edge CDP endpoint"
     "                          Chrome/Edge 136+ requires --user-data-dir for debug port"
     "                          Or use chrome://inspect/#remote-debugging (M144+)"
     "  --ignore-https-errors   Ignore HTTPS certificate errors"
     "  --load-state PATH       Load browser state (cookies/localStorage JSON, alias: --storage-state)"
     "  --profile PATH          Chrome user data directory (persistent profile)"
     "  --no-stealth            Disable stealth mode (stealth is ON by default)"
     "  --extension PATH        Load Chrome extension (repeatable, Chromium-only)"
     "  --timeout MS            Command timeout in milliseconds"
     "  --debug                 Enable debug logging"
     ""
     "Environment Variables (CLI flags take priority):"
     "  SPEL_BROWSER             Browser engine: chromium (default), firefox, webkit"
     "  SPEL_CHANNEL             Chromium channel (e.g. \"chrome\", \"msedge\", \"chrome-beta\")"
     "  SPEL_PROFILE             Chrome/Edge user data directory path"
     "  SPEL_LOAD_STATE          Default state file path (alias: SPEL_STORAGE_STATE)"
     "  SPEL_SESSION             Default session name"
     "  SPEL_JSON                Set to \"true\" for JSON output"
     "  SPEL_TIMEOUT             Command timeout in milliseconds"
     "  SPEL_STEALTH             Set to \"false\" to disable stealth mode (ON by default)"
     "  SPEL_PROXY               Proxy server URL"
     "  SPEL_PROXY_BYPASS        Proxy bypass patterns"
     "  SPEL_HEADERS             Default HTTP headers (JSON)"
     "  SPEL_IGNORE_HTTPS_ERRORS Set to \"true\" to ignore HTTPS errors"
     "  SPEL_USER_AGENT          Custom user agent string"
     "  SPEL_EXECUTABLE_PATH     Custom browser executable path"
     "  SPEL_CA_BUNDLE           PEM file with extra CA certs (corporate proxy)"
     "  NODE_EXTRA_CA_CERTS      PEM file, also used by Node.js subprocess"
     "  SPEL_TRUSTSTORE          JKS/PKCS12 truststore path (corporate proxy)"
     "  SPEL_TRUSTSTORE_TYPE     Truststore type (default: JKS)"
     "  SPEL_TRUSTSTORE_PASSWORD Truststore password"
     "  SPEL_AUTO_CONNECT        Set to any value to auto-discover Chrome CDP"
     "  SPEL_CDP                 Connect via Chrome DevTools Protocol URL"
     "  SPEL_ARGS                Extra Chromium launch args (comma-separated)"
     "  SPEL_DRIVER_DIR          Override Playwright browser driver directory"
     "  SPEL_DEBUG               Set to \"true\" for debug logging"]))

;; =============================================================================
;; Arg Parsing
;; =============================================================================

(defn- normalize-smart-dashes
  "Replaces leading em-dash (\u2014) or en-dash (\u2013) with double ASCII hyphens.
   Rich-text editors and chat apps often auto-correct -- to \u2014 when copy-pasting.
   This makes flags like --session silently fail without this normalization.

   Handles three cases:
   - \u2014session  → --session  (em-dash replaced both hyphens)
   - \u2013-session → --session  (en-dash replaced first hyphen only)
   - \u2013session  → --session  (en-dash replaced both hyphens)"
  [^String arg]
  (cond
    (str/starts-with? arg "\u2014") (str "--" (subs arg 1))
    (str/starts-with? arg "\u2013-") (str "--" (subs arg 2))
    (str/starts-with? arg "\u2013") (str "--" (subs arg 1))
    :else arg))

(defn parse-args
  "Parses CLI argv into a command map suitable for sending to the daemon.

   Returns a map with :command and :flags.
   :command is the action map to send to the daemon.
   :flags contains global options like :session, :json."
  [args]
  (let [;; Read environment variable defaults
        env-defaults (cond-> {:session "default" :headless true :json false :stealth true}
                       (System/getenv "SPEL_SESSION")
                       (assoc :session (System/getenv "SPEL_SESSION"))
                       (= "true" (System/getenv "SPEL_JSON"))
                       (assoc :json true)
                       (or (System/getenv "SPEL_LOAD_STATE") (System/getenv "SPEL_STORAGE_STATE"))
                       (assoc :storage-state (or (System/getenv "SPEL_LOAD_STATE") (System/getenv "SPEL_STORAGE_STATE")))
                       (System/getenv "SPEL_PROFILE")
                       (assoc :profile (System/getenv "SPEL_PROFILE"))
                       (System/getenv "SPEL_HEADERS")
                       (assoc :headers (System/getenv "SPEL_HEADERS"))
                       (System/getenv "SPEL_EXECUTABLE_PATH")
                       (assoc :executable-path (System/getenv "SPEL_EXECUTABLE_PATH"))
                       (System/getenv "SPEL_USER_AGENT")
                       (assoc :user-agent (System/getenv "SPEL_USER_AGENT"))
                       (System/getenv "SPEL_PROXY")
                       (assoc :proxy (System/getenv "SPEL_PROXY"))
                       (System/getenv "SPEL_PROXY_BYPASS")
                       (assoc :proxy-bypass (System/getenv "SPEL_PROXY_BYPASS"))
                       (= "true" (System/getenv "SPEL_IGNORE_HTTPS_ERRORS"))
                       (assoc :ignore-https-errors true)
                       (= "true" (System/getenv "SPEL_DEBUG"))
                       (assoc :debug true)
                       (= "false" (System/getenv "SPEL_STEALTH"))
                       (assoc :stealth false)
                       (System/getenv "SPEL_CDP")
                       (assoc :cdp (System/getenv "SPEL_CDP"))
                       (System/getenv "SPEL_BROWSER")
                       (assoc :browser (System/getenv "SPEL_BROWSER"))
                       (System/getenv "SPEL_CHANNEL")
                       (assoc :channel (System/getenv "SPEL_CHANNEL"))
                       (System/getenv "SPEL_ARGS")
                       (assoc :args (System/getenv "SPEL_ARGS"))
                       (System/getenv "SPEL_TIMEOUT")
                       (assoc :timeout (parse-long (System/getenv "SPEL_TIMEOUT"))))

        ;; Extract global flags first
        {:keys [flags remaining]}
        (loop [args (mapv normalize-smart-dashes args)
               flags env-defaults
               remaining []]
          (if (empty? args)
            {:flags flags :remaining remaining}
            (let [arg (first args)]
              (cond
                (= "--headless" arg)
                (recur (rest args) (assoc flags :headless true) remaining)

                (or (= "--headed" arg) (= "--interactive" arg))
                (recur (rest args) (assoc flags :headless false) remaining)

                (= "--session" arg)
                (recur (drop 2 args) (assoc flags :session (second args)) remaining)

                (str/starts-with? arg "--session=")
                (recur (rest args) (assoc flags :session (subs arg 10)) remaining)

                (= "--json" arg)
                (recur (rest args) (assoc flags :json true) remaining)

                (or (= "--storage-state" arg) (= "--load-state" arg))
                (recur (drop 2 args) (assoc flags :storage-state (second args)) remaining)

                (str/starts-with? arg "--storage-state=")
                (recur (rest args) (assoc flags :storage-state (subs arg 16)) remaining)

                (str/starts-with? arg "--load-state=")
                (recur (rest args) (assoc flags :storage-state (subs arg 13)) remaining)

                (= "--profile" arg)
                (recur (drop 2 args) (assoc flags :profile (second args)) remaining)

                (str/starts-with? arg "--profile=")
                (recur (rest args) (assoc flags :profile (subs arg 10)) remaining)

                (= "--browser" arg)
                (recur (drop 2 args) (assoc flags :browser (second args)) remaining)

                (str/starts-with? arg "--browser=")
                (recur (rest args) (assoc flags :browser (subs arg 10)) remaining)

                (= "--channel" arg)
                (recur (drop 2 args) (assoc flags :channel (second args)) remaining)

                (str/starts-with? arg "--channel=")
                (recur (rest args) (assoc flags :channel (subs arg 10)) remaining)

                (= "--headers" arg)
                (recur (drop 2 args) (assoc flags :headers (second args)) remaining)

                (str/starts-with? arg "--headers=")
                (recur (rest args) (assoc flags :headers (subs arg 10)) remaining)

                (= "--executable-path" arg)
                (recur (drop 2 args) (assoc flags :executable-path (second args)) remaining)

                (str/starts-with? arg "--executable-path=")
                (recur (rest args) (assoc flags :executable-path (subs arg 18)) remaining)

                (= "--user-agent" arg)
                (recur (drop 2 args) (assoc flags :user-agent (second args)) remaining)

                (= "--proxy" arg)
                (recur (drop 2 args) (assoc flags :proxy (second args)) remaining)

                (= "--proxy-bypass" arg)
                (recur (drop 2 args) (assoc flags :proxy-bypass (second args)) remaining)

                (str/starts-with? arg "--proxy-bypass=")
                (recur (rest args) (assoc flags :proxy-bypass (subs arg 15)) remaining)

                (= "--ignore-https-errors" arg)
                (recur (rest args) (assoc flags :ignore-https-errors true) remaining)

                (= "--debug" arg)
                (recur (rest args) (assoc flags :debug true) remaining)

                (= "--args" arg)
                (recur (drop 2 args) (assoc flags :args (second args)) remaining)

                (str/starts-with? arg "--args=")
                (recur (rest args) (assoc flags :args (subs arg 7)) remaining)

                (= "--allow-file-access" arg)
                (recur (rest args) (assoc flags :allow-file-access true) remaining)

                (= "--no-persist" arg)
                (recur (rest args) (assoc flags :no-persist true) remaining)

                (= "--cdp" arg)
                (recur (drop 2 args) (assoc flags :cdp (second args)) remaining)

                (str/starts-with? arg "--cdp=")
                (recur (rest args) (assoc flags :cdp (subs arg 6)) remaining)

                (= "--timeout" arg)
                (recur (drop 2 args) (assoc flags :timeout (parse-long (second args))) remaining)

                (str/starts-with? arg "--timeout=")
                (recur (rest args) (assoc flags :timeout (parse-long (subs arg 10))) remaining)

                (= "--stealth" arg)
                (recur (rest args) (assoc flags :stealth true) remaining)

                (= "--no-stealth" arg)
                (recur (rest args) (assoc flags :stealth false) remaining)

                (= "--extension" arg)
                (recur (drop 2 args) (update flags :extensions (fnil conj []) (second args)) remaining)

                (str/starts-with? arg "--extension=")
                (recur (rest args) (update flags :extensions (fnil conj []) (subs arg 12)) remaining)

                (= "--auto-connect" arg)
                (recur (rest args) (assoc flags :auto-connect true) remaining)

                :else
                (recur (rest args) flags (conj remaining arg))))))

        ;; Parse the command from remaining args
        cmd (first remaining)
        cmd-args (rest remaining)
        bare-help? (or (= "--help" cmd) (= "-h" cmd))
        has-help? (or bare-help? (some #{"--help" "-h"} cmd-args))
        command
        (if has-help?
          {:action "help" :for (when-not bare-help? cmd)}
          (case cmd
          ;; Navigation (+ aliases).
          ;; --interactive / --headed is a global flag (sets headless=false).
          ;; When it appears after 'open', it's also parsed here for backward
          ;; compat. The daemon is restarted if it was previously running headless.
            ("open" "goto" "navigate")
            (let [interactive?  (some #{"--interactive"} cmd-args)
                  ;; Parse --screenshot [path]: flag triggers screenshot after navigation.
                  ;; Path is optional — if omitted a timestamped temp file is used.
                  cmd-args-vec  (vec cmd-args)
                  screenshot?   (some #{"--screenshot"} cmd-args)
                  ss-idx        (when screenshot?
                                  (.indexOf ^java.util.List cmd-args-vec "--screenshot"))
                  ss-next       (when (and ss-idx (>= (long ss-idx) 0))
                                  (nth cmd-args-vec (inc (long ss-idx)) nil))
                  ;; Only treat the next token as a path if it isn't itself a flag
                  ss-path       (when (and ss-next (not (str/starts-with? ss-next "-")))
                                  ss-next)
                  ;; Parse --viewport WxH
                  viewport?     (some #{"--viewport"} cmd-args)
                  vp-idx        (when viewport?
                                  (.indexOf ^java.util.List cmd-args-vec "--viewport"))
                  vp-next       (when (and vp-idx (>= (long vp-idx) 0))
                                  (nth cmd-args-vec (inc (long vp-idx)) nil))
                  [vp-w vp-h]   (when vp-next
                                  (let [parts (str/split vp-next #"[xX,]")]
                                    (when (= 2 (count parts))
                                      [(Integer/parseInt (first parts))
                                       (Integer/parseInt (second parts))])))
                  ;; Exclude flag tokens (and the screenshot/viewport value args) before URL detection
                  skip-tokens   (cond-> #{"--interactive" "--screenshot" "--viewport"}
                                  ss-path (conj ss-path)
                                  vp-next (conj vp-next))
                  url-args      (remove #(or (str/starts-with? % "-") (skip-tokens %)) cmd-args)
                  ;; Prefer the arg that looks like a URL; fall back to first positional.
                  ;; This handles unknown flags whose values leak into positional args
                  ;; (e.g. `open --width 390 --height 844 https://example.com`).
                  raw-url       (or (some #(when (looks-like-url? %) %) url-args)
                                  (first url-args))
                  url           (when raw-url
                                  (if (looks-like-url? raw-url)
                                    (if (or (str/starts-with? raw-url "http://")
                                          (str/starts-with? raw-url "https://")
                                          (str/starts-with? raw-url "file://")
                                          (str/starts-with? raw-url "data:")
                                          (str/starts-with? raw-url "about:")
                                          (str/starts-with? raw-url "chrome:")
                                          (str/starts-with? raw-url "javascript:")
                                          (str/starts-with? raw-url "blob:"))
                                      raw-url
                                      (str "https://" raw-url))
                                    (str "https://" raw-url)))]
              (cond-> {:action "navigate" :url url :raw-input raw-url}
                interactive? (assoc :interactive true)
                screenshot?  (assoc :screenshot true)
                ss-path      (assoc :screenshot-path ss-path)
                vp-w         (assoc :viewport-width vp-w :viewport-height vp-h)))

          ;; Snapshot (with filter options)
            "snapshot" (let [snap-flags (set cmd-args)]
                         (cond-> {:action "snapshot"}
                           (or (snap-flags "-i") (snap-flags "--interactive"))
                           (assoc :interactive true)
                           (or (snap-flags "-c") (snap-flags "--compact"))
                           (assoc :compact true)
                           (or (snap-flags "-C") (snap-flags "--cursor"))
                           (assoc :cursor true)
                           (or (snap-flags "-F") (snap-flags "--flat"))
                           (assoc :flat true)
                         ;; Parse -d N
                           (some #{"-d" "--depth"} cmd-args)
                           (assoc :depth (let [v    (vec cmd-args)
                                               idx1 (long (.indexOf ^java.util.List v "-d"))
                                               idx2 (long (.indexOf ^java.util.List v "--depth"))
                                               idx  (long (cond (>= idx1 0) idx1
                                                            (>= idx2 0) idx2
                                                            :else -1))]
                                           (when (>= idx 0)
                                             (try (Integer/parseInt (nth cmd-args (inc idx)))
                                               (catch Exception _ nil)))))
                         ;; Parse -s <sel>
                           (some #{"-s" "--selector"} cmd-args)
                           (assoc :selector (let [v    (vec cmd-args)
                                                  idx1 (long (.indexOf ^java.util.List v "-s"))
                                                  idx2 (long (.indexOf ^java.util.List v "--selector"))
                                                  idx  (long (cond (>= idx1 0) idx1
                                                               (>= idx2 0) idx2
                                                               :else -1))]
                                              (when (>= idx 0)
                                                (nth cmd-args (inc idx) nil))))
                           (or (snap-flags "-a") (snap-flags "--all"))
                           (assoc :all true)
                           (or (snap-flags "-S") (snap-flags "--styles"))
                           (assoc :styles true)
                           (snap-flags "--minimal")
                           (assoc :styles_detail "minimal")
                           (snap-flags "--max")
                           (assoc :styles_detail "max")
                           (snap-flags "--no-network")
                           (assoc :no_network true)
                           (snap-flags "--no-console")
                           (assoc :no_console true)
                           (snap-flags "--network")
                           (assoc :network true)
                           (snap-flags "--console")
                           (assoc :console true)))

          ;; Click
            "click"    {:action "click" :selector (first cmd-args)}
            "dblclick"  {:action "dblclick" :selector (first cmd-args)}

          ;; Download
            "download" (let [[selector save-path] cmd-args
                             timeout-ms (:timeout flags)]
                         (cond-> {:action "download" :selector selector :save-path save-path}
                           timeout-ms (assoc :timeout-ms timeout-ms)))

          ;; Input
            "fill"     {:action "fill"
                        :selector (first cmd-args)
                        :value (str/join " " (rest cmd-args))}
            "type"     {:action "type"
                        :selector (first cmd-args)
                        :text (str/join " " (rest cmd-args))}
            "clear"    {:action "clear" :selector (first cmd-args)}

          ;; Keyboard (+ aliases)
            ("press" "key")
            (if (some #(str/starts-with? % "@") cmd-args)
              {:action "press" :selector (first cmd-args) :key (second cmd-args)}
              {:action "press" :key (first cmd-args)})

            "keydown"  {:action "keydown" :key (first cmd-args)}
            "keyup"    {:action "keyup" :key (first cmd-args)}

          ;; Mouse
            "hover"    {:action "hover" :selector (first cmd-args)}

            "mouse"    (let [sub (first cmd-args)]
                         (case sub
                           "move"  {:action "mouse_move"
                                    :x (Double/parseDouble (second cmd-args))
                                    :y (Double/parseDouble (nth cmd-args 2))}
                           "down"  {:action "mouse_down"
                                    :button (or (second cmd-args) "left")}
                           "up"    {:action "mouse_up"
                                    :button (or (second cmd-args) "left")}
                           "wheel" {:action "mouse_wheel"
                                    :deltaY (Double/parseDouble (second cmd-args))
                                    :deltaX (if (nth cmd-args 2 nil)
                                              (Double/parseDouble (nth cmd-args 2))
                                              0.0)}
                           {:error (str "Unknown mouse command: " sub)}))

          ;; Checkbox
            "check"    {:action "check" :selector (first cmd-args)}
            "uncheck"  {:action "uncheck" :selector (first cmd-args)}

          ;; Select
            "select"   {:action "select"
                        :selector (first cmd-args)
                        :values (vec (rest cmd-args))}

          ;; Focus
            "focus"    {:action "focus" :selector (first cmd-args)}

          ;; Scroll (+ aliases)
            "scroll"   (let [scroll-flags (set cmd-args)
                             smooth?   (or (scroll-flags "--smooth") (scroll-flags "-S"))
                             ;; Parse --in <selector> for element scrolling
                             in-idx    (let [v    (vec cmd-args)
                                             idx1 (long (.indexOf ^java.util.List v "--in"))]
                                         (when (>= idx1 0)
                                           (nth cmd-args (inc idx1) nil)))
                             ;; Positional args: direction [amount] [@selector]
                             pos-args  (remove #(or (str/starts-with? % "-")
                                                  (= % (str in-idx))) cmd-args)
                             direction (or (first pos-args) "down")
                             rest-pos  (rest pos-args)
                             ;; Check if second positional is a number or selector
                             second-arg (first rest-pos)
                             amount    (if second-arg
                                         (try (Integer/parseInt second-arg)
                                           (catch Exception _ 500))
                                         500)
                             ;; Third positional as selector, or second if it's not a number
                             sel       (or in-idx
                                         (let [third (second rest-pos)]
                                           (when (and third (or (str/starts-with? third "@")
                                                              (str/starts-with? third "#")
                                                              (str/starts-with? third ".")))
                                             third))
                                           ;; If second-arg wasn't a number, it's a selector
                                         (when (and second-arg
                                                 (not (try (Integer/parseInt second-arg) true
                                                        (catch Exception _ false)))
                                                 (or (str/starts-with? second-arg "@")
                                                   (str/starts-with? second-arg "#")
                                                   (str/starts-with? second-arg ".")))
                                           second-arg))]
                         (cond-> {:action "scroll" :direction direction :amount amount}
                           smooth? (assoc :smooth true)
                           sel     (assoc :selector sel)))

            ("scrollintoview" "scrollinto")
            {:action "scrollintoview" :selector (first cmd-args)}

          ;; Drag & Upload
            "drag"     (let [positional (remove #(str/starts-with? % "-") cmd-args)
                             v          (vec cmd-args)]
                         (cond-> {:action "drag"
                                  :source (first positional)
                                  :target (second positional)}
                           (some #{"--force"} cmd-args)
                           (assoc :force true)
                           (some #{"--steps"} cmd-args)
                           (assoc :steps (let [idx (long (.indexOf ^java.util.List v "--steps"))]
                                           (when (>= idx 0)
                                             (try (Integer/parseInt (nth cmd-args (inc idx)))
                                               (catch Exception _ nil)))))
                           (some #{"--timeout"} cmd-args)
                           (assoc :timeout (let [idx (long (.indexOf ^java.util.List v "--timeout"))]
                                             (when (>= idx 0)
                                               (try (Double/parseDouble (nth cmd-args (inc idx)))
                                                 (catch Exception _ nil)))))))

            "drag-by"  (let [positional (remove #(str/starts-with? % "-") cmd-args)
                             v          (vec cmd-args)]
                         (cond-> {:action    "drag-by"
                                  :selector  (first positional)
                                  :dx        (try (Double/parseDouble (second positional))
                                               (catch Exception _ 0))
                                  :dy        (try (Double/parseDouble (nth positional 2))
                                               (catch Exception _ 0))}
                           (some #{"--steps"} cmd-args)
                           (assoc :steps (let [idx (long (.indexOf ^java.util.List v "--steps"))]
                                           (when (>= idx 0)
                                             (try (Integer/parseInt (nth cmd-args (inc idx)))
                                               (catch Exception _ nil)))))))

            "upload"   {:action "upload"
                        :selector (first cmd-args)
                        :files (vec (rest cmd-args))}

          ;; Screenshot
            "screenshot" (let [path-args (remove #(str/starts-with? % "-") cmd-args)
                               path      (first path-args)]
                           (cond-> {:action "screenshot"}
                             path (assoc :path path)
                             (some #{"-f" "--full-page" "--full"} cmd-args)
                             (assoc :fullPage true)
                             (some #{"--crop-to-content"} cmd-args)
                             (assoc :cropToContent true)))

          ;; Annotate (inject overlays onto the page for visible elements)
            "annotate" (cond-> {:action "annotate"}
                         (some #{"-f" "--full"} cmd-args)
                         (assoc :full-page true)
                         (some #{"--no-badges"} cmd-args)
                         (assoc :show-badges false)
                         (some #{"--no-dimensions" "--no-dims"} cmd-args)
                         (assoc :show-dimensions false)
                         (some #{"--no-boxes"} cmd-args)
                         (assoc :show-boxes false)
                         ;; Parse -s <selector-or-ref>
                         (some #{"-s" "--scope"} cmd-args)
                         (assoc :selector (let [idx (long (or (.indexOf ^java.util.List (vec cmd-args) "-s")
                                                            (.indexOf ^java.util.List (vec cmd-args) "--scope")))]
                                            (when (>= idx 0)
                                              (nth cmd-args (inc idx) nil)))))

          ;; Unannotate (remove overlays from the page)
            "unannotate" {:action "unannotate"}

          ;; Survey (full-page screenshot sweep)
            "survey" (cond-> {:action "survey"}
                       (some #{"-a" "--annotate"} cmd-args) (assoc :annotate true)
                       (some #{"-o" "--output-dir"} cmd-args)
                       (assoc :output-dir (let [idx (long (or (.indexOf ^java.util.List (vec cmd-args) "-o")
                                                            (.indexOf ^java.util.List (vec cmd-args) "--output-dir")))]
                                            (when (>= idx 0) (nth cmd-args (inc idx) nil))))
                       (some #{"--overlap"} cmd-args)
                       (assoc :overlap (let [idx (long (.indexOf ^java.util.List (vec cmd-args) "--overlap"))]
                                         (when (>= idx 0) (Long/parseLong (nth cmd-args (inc idx) "0")))))
                       (some #{"--max-frames"} cmd-args)
                       (assoc :max-frames (let [idx (long (.indexOf ^java.util.List (vec cmd-args) "--max-frames"))]
                                            (when (>= idx 0) (Long/parseLong (nth cmd-args (inc idx) "50"))))))

          ;; Audit (umbrella: runs all audits or a specific subcommand)
            "audit" (let [sub (first cmd-args)
                          only-val (when (some #{"--only"} cmd-args)
                                     (let [idx (.indexOf ^java.util.List (vec cmd-args) "--only")]
                                       (when (>= idx 0) (nth cmd-args (inc idx) nil))))]
                      (case sub
                        "structure" {:action "audit"}
                        "contrast"  {:action "text-contrast"}
                        "colors"    {:action "color-palette"}
                        "layout"    {:action "layout-check"}
                        "fonts"     {:action "font-audit"}
                        "links"     {:action "link-health"}
                        "headings"  {:action "heading-structure"}
                       ;; No subcommand or unknown → run all
                        (cond-> {:action "audit-all"}
                          only-val (assoc :only only-val))))

          ;; Routes (link extraction)
            "routes" (cond-> {:action "routes"}
                       (some #{"--internal" "--internal-only"} cmd-args) (assoc :internal-only true)
                       (some #{"--visible" "--visible-only"} cmd-args) (assoc :visible-only true))

          ;; Inspect (interactive snapshot with styles - agent view)
            "inspect" (cond-> {:action "inspect"}
                        (some #{"--scope" "-s"} cmd-args)
                        (assoc :scope (let [idx (long (or (.indexOf ^java.util.List (vec cmd-args) "-s")
                                                        (.indexOf ^java.util.List (vec cmd-args) "--scope")))]
                                        (when (>= idx 0) (nth cmd-args (inc idx) nil))))
                        (some #{"--minimal"} cmd-args) (assoc :style-detail "minimal")
                        (some #{"--max"} cmd-args) (assoc :style-detail "max"))

          ;; Overview (annotated full-page screenshot)
            "overview" (let [path-args (remove #(str/starts-with? % "-") cmd-args)
                             path      (first path-args)]
                         (cond-> {:action "overview"}
                           path (assoc :path path)
                           (some #{"-a" "--all"} cmd-args) (assoc :all true)
                           (some #{"--no-badges"} cmd-args) (assoc :show-badges false)
                           (some #{"--no-dimensions" "--no-dims"} cmd-args) (assoc :show-dimensions false)
                           (some #{"--no-boxes"} cmd-args) (assoc :show-boxes false)
                           (some #{"-s" "--scope"} cmd-args)
                           (assoc :scope (let [idx (long (or (.indexOf ^java.util.List (vec cmd-args) "-s")
                                                           (.indexOf ^java.util.List (vec cmd-args) "--scope")))]
                                           (when (>= idx 0) (nth cmd-args (inc idx) nil))))))

          ;; Debug (page diagnostic snapshot)
            "debug" (cond-> {:action "debug"}
                      (some #{"--clear"} cmd-args) (assoc :clear true))

          ;; Emulate (device emulation + annotated overview)
            "emulate" (let [;; First non-flag arg is the device name, second is optional path
                            non-flag (remove #(str/starts-with? % "-") cmd-args)
                            device   (first non-flag)
                            path     (second non-flag)]
                        (when-not device
                          (throw (ex-info "emulate requires a device name. Usage: spel emulate 'iPhone 14'" {})))
                        (cond-> {:action "emulate" :device device}
                          path (assoc :path path)
                          (some #{"-a" "--all"} cmd-args) (assoc :all true)
                          (some #{"--no-badges"} cmd-args) (assoc :show-badges false)
                          (some #{"--no-dimensions" "--no-dims"} cmd-args) (assoc :show-dimensions false)
                          (some #{"--no-boxes"} cmd-args) (assoc :show-boxes false)))

;; PDF
            "pdf"      {:action "pdf" :path (or (first cmd-args) "page.pdf")}

          ;; JavaScript
            "eval-js"  (let [base64?  (some #{"-b" "--base64"} cmd-args)
                             stdin?   (some #{"--stdin"} cmd-args)
                             js-args  (remove #(#{"-b" "--base64" "--stdin"} %) cmd-args)]
                         (cond-> {:action "evaluate"
                                  :script (str/join " " js-args)}
                           base64? (assoc :base64 true)
                           stdin?  (assoc :stdin true)))

          ;; Navigation
            "back"     {:action "back"}
            "forward"  {:action "forward"}
            "reload"   {:action "reload"}

          ;; Wait (enhanced with --text, --url, --fn, --load)
            "wait"     (let [wait-args (vec cmd-args)]
                         (cond
                           (some #{"--text"} wait-args)
                           (let [idx (long (.indexOf ^java.util.List wait-args "--text"))]
                             {:action "wait" :text (nth wait-args (inc idx))})

                           (some #{"--url"} wait-args)
                           (let [idx (long (.indexOf ^java.util.List wait-args "--url"))]
                             {:action "wait" :url (nth wait-args (inc idx))})

                           (some #{"--fn"} wait-args)
                           (let [idx (long (.indexOf ^java.util.List wait-args "--fn"))]
                             {:action "wait" :function (nth wait-args (inc idx))})

                           (some #{"--load"} wait-args)
                           (let [idx (long (.indexOf ^java.util.List wait-args "--load"))]
                             {:action "wait" :state (nth wait-args (inc idx))})

                           (nil? (first wait-args))
                           {:action "wait" :state "load"}

                           (re-matches #"\d+" (first wait-args))
                           {:action "wait" :timeout (Integer/parseInt (first wait-args))}

                           :else
                           {:action "wait" :selector (first wait-args)}))

          ;; Tabs
            "tab"      (let [sub (first cmd-args)]
                         (case sub
                           "new"   (cond-> {:action "tab_new"}
                                     (second cmd-args) (assoc :url (second cmd-args)))
                           "list"  {:action "tab_list"}
                           "close" {:action "tab_close"}
                           (nil)   {:action "tab_list"}
                           (if (re-matches #"\d+" sub)
                             {:action "tab_switch" :index (Integer/parseInt sub)}
                             {:action "tab_list"})))

          ;; Getters (extended with value, attr, count, box)
            "get"      (let [what (first cmd-args)
                             sel  (second cmd-args)]
                         (case what
                           "text"      {:action "get_text" :selector sel}
                           "url"       {:action "url"}
                           "title"     {:action "title"}
                           "html"      {:action "content" :selector sel}
                           "value"     {:action "get_value" :selector sel}
                           "attribute" {:action "get_attribute"
                                        :selector sel
                                        :attribute (nth cmd-args 2 nil)}
                           "attr"      {:action "get_attribute"
                                        :selector sel
                                        :attribute (nth cmd-args 2 nil)}
                           "count"     {:action "get_count" :selector sel}
                           "box"       {:action "get_box" :selector sel}
                           {:action "url"}))

          ;; State checks
            "is"       (let [what (first cmd-args)
                             sel  (second cmd-args)]
                         (case what
                           "visible" {:action "is_visible" :selector sel}
                           "enabled" {:action "is_enabled" :selector sel}
                           "checked" {:action "is_checked" :selector sel}
                           {:error (str "Unknown check: " what)}))

          ;; Count / Bounding box
            "count"    {:action "count" :selector (first cmd-args)}
            "bbox"     {:action "bounding_box" :selector (first cmd-args)}

          ;; Highlight
            "highlight" {:action "highlight" :selector (first cmd-args)}

          ;; Find (semantic locators)
          ;; Supports explicit types: role, text, label, placeholder, alt, title, testid, first, last, nth
          ;; Unknown types are treated as ARIA role shortcuts:
          ;;   spel find link click      → spel find role link click
          ;;   spel find button          → spel find role button
          ;;   spel find heading text    → spel find role heading text
            "find"     (let [by     (first cmd-args)
                             value  (second cmd-args)
                             action (nth cmd-args 2 nil)
                             known-types #{"role" "text" "label" "placeholder" "alt" "title" "testid" "first" "last" "nth"}
                           ;; Parse --name and --exact flags
                             name-idx (long (.indexOf ^java.util.List (vec cmd-args) "--name"))
                             name-val (when (>= name-idx 0)
                                        (nth cmd-args (inc name-idx) nil))
                             exact?   (some #{"--exact"} cmd-args)
                             find-map (cond
                                      ;; For nth, the args are: nth <n> <sel> <action> [value]
                                        (= by "nth")
                                        {:action "find" :by "nth" :value value
                                         :selector (nth cmd-args 2 nil)
                                         :find_action (nth cmd-args 3 nil)
                                         :find_value (nth cmd-args 4 nil)}

                                      ;; Known find types: <by> <value> <action> [value]
                                        (contains? known-types by)
                                        (let [rest-args (drop 3 cmd-args)
                                              fv (first (remove #(str/starts-with? % "-") rest-args))]
                                          (cond-> {:action "find" :by by :value value
                                                   :find_action action}
                                            fv        (assoc :find_value fv)
                                            name-val  (assoc :name name-val)
                                            exact?    (assoc :exact true)))

                                      ;; Role shortcut: unknown type treated as ARIA role name
                                      ;; spel find link click "val" → by=role value=link find_action=click find_value=val
                                        :else
                                        (let [rest-args (drop 2 cmd-args)
                                              fv (first (remove #(str/starts-with? % "-") rest-args))]
                                          (cond-> {:action "find" :by "role" :value by
                                                   :find_action value}
                                            fv        (assoc :find_value fv)
                                            name-val  (assoc :name name-val)
                                            exact?    (assoc :exact true))))]
                         find-map)

          ;; Browser settings
            "set"      (let [sub (first cmd-args)]
                         (case sub
                           "viewport"    {:action "set_viewport"
                                          :width (Integer/parseInt (second cmd-args))
                                          :height (Integer/parseInt (nth cmd-args 2))}
                           "device"      {:action "set_device"
                                          :device (str/join " " (rest cmd-args))}
                           "geo"         {:action "set_geo"
                                          :latitude (Double/parseDouble (second cmd-args))
                                          :longitude (Double/parseDouble (nth cmd-args 2))}
                           "offline"     {:action "set_offline"
                                          :enabled (not= "off" (second cmd-args))}
                           "headers"     {:action "set_headers"
                                          :headers (json/read-json (second cmd-args))}
                           "credentials" {:action "set_credentials"
                                          :username (second cmd-args)
                                          :password (nth cmd-args 2)}
                           "media"       {:action "set_media"
                                          :colorScheme (second cmd-args)}
                           {:error (str "Unknown set command: " sub)}))

          ;; Cookies
            "cookies"  (let [sub (first cmd-args)]
                         (case sub
                           "set"   {:action "cookies_set"
                                    :name (second cmd-args)
                                    :value (nth cmd-args 2)}
                           "clear" {:action "cookies_clear"}
                           (nil)   {:action "cookies_get"}
                           {:action "cookies_get"}))

          ;; Storage
            "storage"  (let [st   (first cmd-args)   ;; "local" or "session"
                             sub  (second cmd-args)]
                         (case sub
                           "set"   {:action "storage_set"
                                    :type st
                                    :key (nth cmd-args 2)
                                    :value (nth cmd-args 3)}
                           "clear" {:action "storage_clear" :type st}
                           (nil)   {:action "storage_get" :type st}
                         ;; sub is a key name
                           {:action "storage_get" :type st :key sub}))

          ;; Network
            "network"  (let [sub (first cmd-args)
                             arg (second cmd-args)]
                         (cond
                           ;; spel network get @n1
                           (= sub "get")
                           {:action "network_get_ref" :ref arg}

                           ;; spel network (no args) → list recent entries
                           (nil? sub)
                           {:action "network_list"}

                           :else
                           (case sub
                             "route"    (let [url       (second cmd-args)
                                              rest-args (set (drop 2 cmd-args))
                                              abort?    (rest-args "--abort")
                                              body-idx  (long (.indexOf ^java.util.List (vec cmd-args) "--body"))
                                              body      (when (>= body-idx 0)
                                                          (nth cmd-args (inc body-idx) nil))]
                                          (cond-> {:action "network_route" :url url}
                                            abort? (assoc :action_type "abort" :action "network_route")
                                            body   (assoc :action_type "fulfill" :body body)
                                            (not (or abort? body)) (assoc :action_type "continue")))
                             "unroute"  {:action "network_unroute"
                                         :url (second cmd-args)}
                             "requests" (let [args-v    (vec cmd-args)
                                              flag-val  (fn [flag]
                                                          (let [i (long (.indexOf ^java.util.List args-v flag))]
                                                            (when (>= i 0) (nth args-v (inc i) nil))))]
                                          (cond-> {:action "network_requests"}
                                            (flag-val "--filter") (assoc :filter (flag-val "--filter"))
                                            (flag-val "--type")   (assoc :type   (flag-val "--type"))
                                            (flag-val "--method") (assoc :method (flag-val "--method"))
                                            (flag-val "--status") (assoc :status (flag-val "--status"))))
                             "clear"    {:action "network_clear"}
                             {:error (str "Unknown network command: " sub)})))

          ;; Frames
            "frame"    (let [sel (first cmd-args)]
                         (case sel
                           "list" {:action "frame_list"}
                           "main" {:action "frame_switch" :selector "main"}
                           (nil)  {:action "frame_switch" :selector "main"}
                           {:action "frame_switch" :selector sel}))

          ;; Dialogs
            "dialog"   (let [sub (first cmd-args)]
                         (case sub
                           "accept"  {:action "dialog_accept" :text (second cmd-args)}
                           "dismiss" {:action "dialog_dismiss"}
                           {:error (str "Unknown dialog command: " sub)}))

          ;; Debug: trace, console, errors
            "trace"    (let [sub (first cmd-args)]
                         (case sub
                           "start" {:action "trace_start"
                                    :name (second cmd-args)}
                           "stop"  {:action "trace_stop"
                                    :path (second cmd-args)}
                           {:error (str "Unknown trace command: " sub)}))

            "console"  (let [sub (first cmd-args)
                             arg (second cmd-args)]
                         (cond
                           ;; spel console get @c1
                           (= sub "get")
                           {:action "console_get_ref" :ref arg}

                           ;; spel console (no args) → list recent entries
                           (nil? sub)
                           {:action "console_list"}

                           :else
                           (case sub
                             ("clear" "--clear") {:action "console_clear"}
                             {:action "console_get"})))

          ;; Action Log
            "action-log" (let [args-set  (set cmd-args)
                               srt?     (args-set "--srt")
                               clear?   (args-set "--clear")
                               ;; Parse -o / --output flag value
                               args-v   (vec cmd-args)
                               out-idx  (long (max (long (.indexOf ^java.util.List args-v "-o"))
                                                (long (.indexOf ^java.util.List args-v "--output"))))
                               out-path (when (>= out-idx 0) (nth args-v (inc out-idx) nil))]
                           (cond
                             clear?
                             {:action "action_log_clear"}

                             srt?
                             (cond-> {:action "action_log_srt"}
                               out-path (assoc :_output_file out-path))

                             :else
                             (cond-> {:action "action_log"}
                               out-path (assoc :_output_file out-path))))

            "errors"   (case (first cmd-args)
                         "clear" {:action "errors_clear"}
                         "--clear" {:action "errors_get" :clear true}
                         {:action "errors_get"})

          ;; Pages
            "pages"    (let [sub (first cmd-args)
                             arg (second cmd-args)]
                         (cond
                           (= sub "get")
                           {:action "pages_get_ref" :ref arg}
                           :else
                           {:action "pages_list"}))

          ;; State management
            "state"    (let [sub (first cmd-args)]
                         (case sub
                           "save"   {:action "state_save" :path (second cmd-args)}
                           "load"   {:action "state_load" :path (second cmd-args)}
                           "list"   {:action "state_list"}
                           "show"   {:action "state_show" :file (second cmd-args)}
                           "rename" {:action "state_rename"
                                     :old_name (second cmd-args)
                                     :new_name (nth cmd-args 2)}
                           "clear"  (if (some #{"--all"} cmd-args)
                                      {:action "state_clear" :all true}
                                      {:action "state_clear" :name (second cmd-args)})
                           "clean"  (let [idx (long (.indexOf ^java.util.List (vec cmd-args) "--older-than"))]
                                      {:action "state_clean"
                                       :older_than_days (when (>= idx 0)
                                                          (try (Integer/parseInt (nth cmd-args (inc idx)))
                                                            (catch Exception _ 30)))})
                           {:error (str "Unknown state command: " sub)}))

          ;; Sessions
            "session"  (let [sub (first cmd-args)]
                         (case sub
                           "list" {:action "session_list"}
                           (nil)  {:action "session_info"}
                           {:action "session_info"}))

          ;; Connect CDP
            "connect"  {:action "connect" :url (first cmd-args)}

          ;; Styles
            "styles"   (let [sel  (first cmd-args)
                             full (some #{"--full"} cmd-args)]
                         (cond-> {:action "get_styles" :selector sel}
                           full (assoc :full true)))

          ;; Clipboard
            "clipboard" (let [sub (first cmd-args)]
                          (case sub
                            "copy"  {:action "clipboard_copy" :text (second cmd-args)}
                            "read"  {:action "clipboard_read"}
                            "paste" {:action "clipboard_paste"}
                            {:error (str "Unknown clipboard command: " sub)}))

          ;; Diff
            "diff"     (let [sub (first cmd-args)]
                         (case sub
                           "snapshot" (let [args-v    (vec cmd-args)
                                            bl-idx   (long (.indexOf ^java.util.List args-v "--baseline"))
                                            baseline (when (>= bl-idx 0) (nth args-v (inc bl-idx) nil))
                                            content  (when baseline (slurp baseline))]
                                        (cond-> {:action "diff_snapshot"}
                                          content (assoc :baseline content)
                                          (some #{"--compact" "-c"} cmd-args) (assoc :compact true)
                                          (some #{"--no-network"} cmd-args) (assoc :no-network true)
                                          (some #{"--no-console"} cmd-args) (assoc :no-console true)))
                           "screenshot" {:action "diff_screenshot"
                                         :baseline (let [args (rest cmd-args)
                                                         idx (.indexOf ^java.util.List (vec args) "--baseline")]
                                                     (when (>= idx 0) (nth args (inc idx))))
                                         :threshold (let [args (rest cmd-args)
                                                          idx (.indexOf ^java.util.List (vec args) "--threshold")]
                                                      (when (>= idx 0) (nth args (inc idx))))
                                         :path (let [args (rest cmd-args)
                                                     idx (.indexOf ^java.util.List (vec args) "-o")]
                                                 (when (>= idx 0) (nth args (inc idx))))}
                           {:error (str "Unknown diff command: " sub)}))

          ;; Close (+ aliases)
            ("close" "quit" "exit")
            (cond-> {:action "close"}
              (some #{"--all-sessions"} cmd-args)
              (assoc :all-sessions true))

          ;; Install
            "install"  {:action "install"
                        :with-deps (some #{"--with-deps"} cmd-args)}

          ;; Inspector — launch Playwright Inspector (passthrough to Playwright CLI)
            "inspector" {:action "inspector"
                         :cli-args cmd-args}

          ;; Show-trace — launch Trace Viewer (passthrough to Playwright CLI)
            "show-trace" {:action "show-trace"
                          :cli-args cmd-args}

          ;; No command given
            (nil)      {:error "No command specified. Run with --help for usage."}

          ;; Default — unknown command
            {:error (str "Unknown command: " cmd)}))]

    {:command command :flags flags}))

;; =============================================================================
;; Daemon Communication
;; =============================================================================

(defn send-command!
  "Sends a JSON command to the daemon and returns the parsed response.

   Connects to the daemon's Unix domain socket, writes one JSON line,
   reads one JSON response line, and closes the connection.

   `timeout-ms` controls how long to wait for a response:
   - positive long: wait up to that many milliseconds (default 30000)
   - nil: block indefinitely until the daemon responds or throws.
     Use nil for eval-sci mode where each Playwright action has its own
     timeout — the transport layer should not race against it."
  ([^String session command-map]
   (send-command! session command-map 30000))
  ([^String session command-map timeout-ms]
   (let [command-map (cond-> command-map
                       (and (contains? command-map :args)
                         (some? (:args command-map))
                         (not (contains? command-map "args")))
                       (assoc "args" (:args command-map)))
         sock-path (daemon/socket-path session)
         addr      (UnixDomainSocketAddress/of (.toString sock-path))
         channel   (SocketChannel/open StandardProtocolFamily/UNIX)]
     (try
       (.connect channel addr)
       (let [reader (BufferedReader.
                      (InputStreamReader. (Channels/newInputStream channel)))
             writer (OutputStreamWriter. (Channels/newOutputStream channel))]
         (.write writer ^String (json/write-json-str command-map))
         (.write writer "\n")
         (.flush writer)
         (let [f      (future (.readLine reader))
               result (if timeout-ms
                        (let [r (deref f (long timeout-ms) ::timeout)]
                          (when (= ::timeout r)
                            (future-cancel f)
                            (throw (ex-info "Daemon response timed out" {:timeout-ms timeout-ms})))
                          r)
                        ;; nil timeout = block forever. Playwright action timeouts
                        ;; are the correct control mechanism — not the transport layer.
                        (deref f))]
           (when result
             (json/read-json result :key-fn keyword))))
       (finally
         (try (.close channel)
           (catch Exception e
             (binding [*out* *err*]
               (println (str "warn: close-channel: " (.getMessage e)))))))))))

(defn- process-alive?
  [^String pid]
  (try
    (let [p (.start (ProcessBuilder. ^java.util.List (list "kill" "-0" pid)))]
      (= 0 (.waitFor p)))
    (catch Exception _ false)))

(defn- cleanup-session-files!
  "Deletes stale socket and PID files for a session."
  [session]
  (try (Files/deleteIfExists (daemon/socket-path session))
    (catch Exception e (binding [*out* *err*] (println (str "warn: delete-socket: " (.getMessage e))))))
  (try (Files/deleteIfExists (daemon/pid-file-path session))
    (catch Exception e (binding [*out* *err*] (println (str "warn: delete-pid: " (.getMessage e)))))))

(defn- read-pid
  "Reads the PID from a session's PID file, or nil if unavailable."
  [session]
  (let [pid-path (daemon/pid-file-path session)]
    (when (Files/exists pid-path (into-array java.nio.file.LinkOption []))
      (try (str/trim (String. (Files/readAllBytes pid-path)))
        (catch Exception _ nil)))))

(defn- discover-sessions
  "Finds all active spel sessions by looking for .sock files in tmpdir.
   Returns a seq of session name strings."
  []
  (let [tmp-dir (java.io.File. (System/getProperty "java.io.tmpdir"))
        socks   (.listFiles tmp-dir)]
    (when socks
      (->> socks
        (filter (fn [^java.io.File f]
                  (and (.exists f)
                    (str/starts-with? (.getName f) "spel-")
                    (str/ends-with? (.getName f) ".sock"))))
        (mapv (fn [^java.io.File f]
                (-> (.getName f)
                  (str/replace "spel-" "")
                  (str/replace ".sock" ""))))))))

(defn- close-session!
  "Gracefully closes a single daemon session. Sends close command,
   waits for the process to exit, then cleans up files."
  [^String session]
  (let [old-pid (read-pid session)]
    (try
      (send-command! session {:action "close"} 5000)
      (catch Exception _ nil))
    (when old-pid
      (loop [tries 0]
        (when (and (< tries 100) (process-alive? old-pid))
          (Thread/sleep 100)
          (recur (inc tries)))))
    (cleanup-session-files! session)))

(defn- socket-connectable?
  "Tries to connect to the daemon's Unix socket. Returns true if connectable."
  [session]
  (let [sock-path (daemon/socket-path session)]
    (when (Files/exists sock-path (into-array java.nio.file.LinkOption []))
      (try
        (let [addr    (UnixDomainSocketAddress/of (.toString sock-path))
              channel (SocketChannel/open StandardProtocolFamily/UNIX)]
          (try
            (.connect channel addr)
            true
            (finally
              (try (.close channel) (catch Exception e
                                      (binding [*out* *err*] (println (str "warn: close-channel: " (.getMessage e)))))))))
        (catch Exception _ false)))))

(defn- restart-daemon!
  "Restarts the daemon by closing the existing one first.
   Sends close command, waits for the old process to exit, then cleans up files.
   If an active trace was running, the daemon auto-saves it before shutdown."
  [session]
  (let [old-pid (read-pid session)]
    ;; Send graceful close (5s timeout — just asking daemon to exit)
    ;; The daemon auto-saves any in-flight trace during shutdown.
    (try
      (let [resp (send-command! session {:action "close"} 5000)]
        (when-let [tw (get-in resp ["data" "trace-warning"])]
          (binding [*out* *err*]
            (println (str "spel: " tw)))))
      (catch Exception e (binding [*out* *err*] (println (str "warn: close-command: " (.getMessage e))))))
    ;; Wait for old process to actually die (up to 10s)
    (when old-pid
      (loop [tries 0]
        (when (and (< tries 100) (process-alive? old-pid))
          (Thread/sleep 100)
          (recur (inc tries)))))
    ;; Clean up stale files
    (cleanup-session-files! session)))

(defn- kill-stale-daemon!
  "Force-kills a stale daemon process and cleans up its files."
  [session]
  (when-let [old-pid (read-pid session)]
    (when (process-alive? old-pid)
      (try (.start (ProcessBuilder. ^java.util.List (list "kill" "-9" old-pid)))
        (catch Exception e (binding [*out* *err*] (println (str "warn: kill-daemon: " (.getMessage e))))))
      ;; Wait for process to die
      (loop [tries 0]
        (when (and (< tries 50) (process-alive? old-pid))
          (Thread/sleep 100)
          (recur (inc tries))))))
  (cleanup-session-files! session))

(defn- start-daemon-process!
  "Starts a new daemon subprocess and waits until its socket is connectable.
   Returns true if the daemon started successfully."
  [session opts]
  ;; Clean up any stale files before starting
  (cleanup-session-files! session)
  (let [info      (.info (java.lang.ProcessHandle/current))
        exec-path (when (.isPresent (.command info))
                    (.get (.command info)))
        args      (cond-> ["daemon" "--session" session]
                    (not (:headless opts true))
                    (conj "--headed")
                    (:browser opts)
                    (into ["--browser" (:browser opts)])
                    (:channel opts)
                    (into ["--channel" (:channel opts)])
                    (:cdp opts)
                    (into ["--cdp" (:cdp opts)]))
        pb        (if (and exec-path
                        (or (str/ends-with? exec-path "spel")
                          (str/ends-with? exec-path "spel.exe")))
                    (ProcessBuilder. ^java.util.List
                      (into [exec-path] args))
                    (let [classpath (System/getProperty "java.class.path")]
                      (ProcessBuilder. ^java.util.List
                        (into ["java" "-cp" classpath
                               "clojure.main" "-m" "com.blockether.spel.native"]
                          args))))]
    (.redirectOutput pb
      (java.io.File. (.toString (daemon/log-file-path session))))
    (.redirectErrorStream pb true)
    (.start pb)
    ;; Wait for socket to appear AND be connectable (up to 30s)
    (loop [tries 0]
      (if (>= tries 300)
        false
        (if (socket-connectable? session)
          true
          (do (Thread/sleep 100)
            (recur (inc tries))))))))

(defn ensure-daemon!
  "Ensures a daemon is running and responsive for the given session.
   Starts one if needed. Handles:
   - No daemon running → start fresh
   - Daemon running headless but --interactive requested → restart headed
   - Daemon already headed and --interactive requested → no-op (skip restart)
   - Stale daemon (process alive but socket broken) → kill and restart
   - Dead daemon (PID file exists but process gone) → clean up and restart"
  [session opts]
  ;; If interactive mode requested and daemon is running, only restart if
  ;; the daemon is currently headless. Skip restart if already headed.
  (when (and (not (:headless opts true))
          (daemon/daemon-running? session)
          (socket-connectable? session))
    (let [resp (try (send-command! session {:action "session_info"} 5000)
                 (catch Exception _ nil))]
      (when (get-in resp [:data :headless])
        (restart-daemon! session))))

  ;; If daemon claims to be running, verify it's actually connectable
  (when (daemon/daemon-running? session)
    (when-not (socket-connectable? session)
      ;; Process alive but socket broken — kill and clean up
      (kill-stale-daemon! session)))

  ;; Start daemon if not running
  (when-not (daemon/daemon-running? session)
    (cleanup-session-files! session)
    (start-daemon-process! session opts)))

;; =============================================================================
;; Pretty Printing
;; =============================================================================

(defn- print-snapshot
  "Prints a snapshot tree to stdout, stripping surrounding whitespace."
  [snapshot]
  (when (and snapshot (not (str/blank? (str snapshot))))
    (println (str/trim (str snapshot)))))

(defn- print-result
  "Pretty-prints a daemon response to the terminal.
   Default mode: human-friendly strings and tables (never EDN).
   JSON mode (--json): clean JSON of just the data (no envelope)."
  [response json-mode?]
  (if json-mode?
    ;; --json: output just the data payload (success) or error object (failure)
    (let [{:keys [success data error]} response]
      (if success
        (println (json/write-json-str data :escape-slash false))
        (let [err-map (cond-> {:error (or error "Unknown error")}
                        (:call_log response) (assoc :call_log (:call_log response))
                        (:selector response) (assoc :selector (:selector response)))]
          (println (json/write-json-str err-map :escape-slash false)))))
    (let [{:keys [success data error]} response]
      (if success
        (cond
          ;; Snapshot responses
          (:snapshot data)
          (do (print-snapshot (:snapshot data))
            (when (:url data)
              (println (str "\n  URL: " (:url data))))
            (when (:title data)
              (println (str "  Title: " (:title data))))
            (when (:description data)
              (println (str "  Description: " (:description data)))))

          ;; Screenshot
          (:base64 data)
          (println (str "Screenshot captured (" (:size data) " bytes)"))

          ;; State save/load
          (:state data)
          (println (str (if (= "loaded" (:state data)) "Loaded: " "Saved: ")
                     (:path data)))

          (:path data)
          (println (str "Saved: " (:path data)
                     (when (:size data)
                       (str " (" (:size data) " bytes)"))))

          ;; Session list (before :session — session_list has :sessions AND :current)
          (:sessions data)
          (let [current (:current data)
                sessions (:sessions data)]
            (println (format "  %-3s %-14s %s" "" "SESSION" "SOCKET"))
            (println (str "  " (apply str (repeat 60 "─"))))
            (if (seq sessions)
              (doseq [s sessions]
                (println (format "  %-3s %-14s %s"
                           (if (= (:name s) current) "→" "")
                           (:name s)
                           (:socket s))))
              (println "  No active sessions.")))

          ;; Session info (before :url — session_info also has :url)
          (:session data)
          (let [{:keys [session headless url title refs_count]} data]
            (println (format "  %-12s %-10s %-6s %-40s %s" "SESSION" "HEADLESS" "REFS" "URL" "TITLE"))
            (println (str "  " (apply str (repeat 90 "─"))))
            (println (format "  %-12s %-10s %-6s %-40s %s"
                       session headless (or refs_count 0)
                       (or url "-") (or title "-"))))

          ;; Tab list
          (:tabs data)
          (doseq [tab (:tabs data)]
            (println (str (if (:active tab) "* " "  ")
                       "[" (:index tab) "] "
                       (:title tab) " — " (:url tab))))

          ;; URL/Title
          (:url data)
          (println (:url data))

          (:title data)
          (println (:title data))

          ;; Text content
          (:text data)
          (println (:text data))

          (:html data)
          (println (:html data))

          ;; Boolean results
          (contains? data :visible)
          (println (:visible data))

          (contains? data :enabled)
          (println (:enabled data))

          (contains? data :checked)
          (println (:checked data))

          ;; Count
          (:count data)
          (println (:count data))

          ;; Bounding box
          (:box data)
          (let [b (:box data)]
            (println (str "x=" (:x b) " y=" (:y b)
                       " w=" (:width b) " h=" (:height b))))

          ;; Value (get value)
          (contains? data :value)
          (println (:value data))

          ;; Evaluate (JS eval result — can be any type)
          (contains? data :result)
          (let [r (:result data)]
            (cond
              (nil? r) nil ;; void JS expressions — no output
              (or (map? r) (sequential? r))
              (println (json/write-json-str r :escape-slash false))
              :else (println r)))

          ;; Storage (localStorage/sessionStorage value or JSON entries)
          (contains? data :storage)
          (when-some [s (:storage data)]
            (println s))

          ;; Cookies
          (:cookies data)
          (doseq [c (:cookies data)]
            (println (str (:name c) "=" (:value c)
                       " (domain=" (:domain c) " path=" (:path c) ")")))

          ;; SRT subtitle output (raw text, not formatted)
          (:srt data)
          (println (:srt data))

          ;; Console messages
          (:messages data)
          (doseq [m (:messages data)]
            (println (str "[" (:type m) "] " (:text m))))

          ;; Errors
          (and (contains? data :errors) (sequential? (:errors data)))
          (doseq [e (:errors data)]
            (println (str "ERROR: " (:message e))))

          ;; Frames
          (:frames data)
          (doseq [f (:frames data)]
            (println (str "  " (:name f) " — " (:url f))))

          ;; States list
          (:states data)
          (doseq [s (:states data)]
            (println (str "  " s)))

          ;; Requests
          (:requests data)
          (let [reqs (:requests data)]
            (when (seq reqs)
              (println (format "  %-8s %-8s %-14s %s" "METHOD" "STATUS" "TYPE" "URL"))
              (println (str "  " (apply str (repeat 90 "─"))))
              (doseq [r reqs]
                (println (format "  %-8s %-8s %-14s %s"
                           (:method r "GET")
                           (str (:status r ""))
                           (:resource-type r "")
                           (:url r))))))

          ;; Network status (clear etc.)
          (:network data)
          (println (str "Network requests " (:network data) "."))

          ;; Close
          (:closed data)
          (println "Browser closed.")

          ;; Fallback — human-friendly key: value output (never EDN)
          :else
          (let [desc (:desc data)
                data (dissoc data :desc)]
            (doseq [[k v] data]
              (let [suffix (when desc (str " — " desc))]
                (cond
                  ;; Boolean confirmations: "cookies cleared." / "headers set."
                  (true? v)
                  (println (str (str/replace (name k) "_" " ") "." suffix))
                  ;; Nested maps/vectors: format as JSON
                  (or (map? v) (sequential? v))
                  (println (str (str/replace (name k) "_" " ") ": "
                             (json/write-json-str v :escape-slash false) suffix))
                  ;; Simple scalars
                  :else
                  (println (str (str/replace (name k) "_" " ") ": " v suffix)))))))

        ;; Error
        (binding [*out* *err*]
          (println (str "Error: " (or error "Unknown error"))))))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn run-cli!
  "Main CLI entry point. Parses args, ensures daemon, sends command, prints result.

   Returns the response map (for programmatic use) or nil on error."
  [args]
  (let [{:keys [command flags]} (parse-args args)
        ;; Recover persisted launch flags from the daemon's flags file.
        ;; This lets --cdp, --browser, etc. be specified once and reused
        ;; across commands without re-typing them every time.
        session (or (:session flags) "default")
        persisted (daemon/read-session-flags session)
        ;; Merge persisted flags as DEFAULTS — CLI args always win.
        ;; Only merge keys that the daemon actually uses as launch flags.
        flags (cond-> flags
                (and (get persisted "cdp") (not (:cdp flags)))
                (assoc :cdp (get persisted "cdp"))
                (and (get persisted "browser") (not (:browser flags)))
                (assoc :browser (get persisted "browser"))
                (and (get persisted "profile") (not (:profile flags)))
                (assoc :profile (get persisted "profile")))
        ;; Auto-connect: discover running Chrome if no explicit --cdp
        flags (if (and (:auto-connect flags) (not (:cdp flags)))
                (assoc flags :cdp (daemon/discover-cdp-endpoint))
                flags)
        ;; open --interactive launches the browser in headed (visible) mode so you
        ;; can watch and interact with the page while commands run. This restarts
        ;; the daemon if it was previously running headless. Only applies to
        ;; the 'open' (navigate) command — snapshot -i is a different flag
        ;; that filters interactive elements in the accessibility tree.
        flags (if (and (= "navigate" (:action command)) (:interactive command))
                (assoc flags :headless false)
                flags)]
    ;; Check for parse errors
    (when (:error command)
      (binding [*out* *err*]
        (println (:error command)))
      (System/exit 1))

    ;; Subcommand --help: print help and exit
    (when (= "help" (:action command))
      (let [cmd-name (:for command)
            ;; Resolve aliases to primary command name
            resolved (get {"goto" "open" "navigate" "open"
                           "key" "press"
                           "scrollinto" "scrollintoview"
                           "quit" "close" "exit" "close"}
                       cmd-name cmd-name)
            help-text (get command-help resolved)]
        (cond
          help-text       (println help-text)
          (nil? cmd-name) (println (top-level-help))
          :else           (println (str "Unknown command: " cmd-name ". Run 'spel --help' for usage."))))
      (System/exit 0))

    ;; Install — handled directly, not via daemon
    (when (= "install" (:action command))
      (let [extra-args (cond-> []
                         (:with-deps command) (conj "--with-deps"))]
        (println (str "Installing Playwright browsers"
                   (when (seq extra-args) (str " (" (clojure.string/join " " extra-args) ")"))
                   "..."))
        (flush)
        (com.microsoft.playwright.CLI/main
          (into-array String (into ["install"] extra-args)))
        ;; CLI/main may call System.exit — if we get here, print done
        (println "Done.")
        (flush)
        (System/exit 0)))

    ;; Inspector — launch Playwright Inspector (bypasses daemon)
    (when (= "inspector" (:action command))
      (com.microsoft.playwright.CLI/main
        (into-array String (into ["open"] (:cli-args command))))
      (System/exit 0))

    ;; Show-trace — launch Playwright Trace Viewer (bypasses daemon)
    (when (= "show-trace" (:action command))
      (com.microsoft.playwright.CLI/main
        (into-array String (into ["show-trace"] (:cli-args command))))
      (System/exit 0))

    ;; Close --all-sessions — close every active daemon (bypasses ensure-daemon!)
    (when (and (= "close" (:action command)) (:all-sessions command))
      (let [sessions (discover-sessions)]
        (if (seq sessions)
          (do (doseq [s sessions]
                (close-session! s)
                (println (str "Closed session: " s)))
            (System/exit 0))
          (do (println "No active sessions.")
            (System/exit 0)))))

    ;; Close (single session) — bypass ensure-daemon! to avoid starting a
    ;; daemon just to immediately close it. If no daemon is running, clean up
    ;; any stale files and exit.
    (when (and (= "close" (:action command)) (not (:all-sessions command)))
      (let [session (:session flags)]
        (when (:json flags)
          (println (json/write-json-str {:closed true} :escape-slash false))
          (.flush *out*))
        (if (daemon/daemon-running? session)
          (close-session! session)
          (cleanup-session-files! session))
        (System/exit 0)))

    ;; Ensure daemon is running
    (ensure-daemon! (:session flags) flags)

    ;; Read from stdin if eval --stdin was used
    (let [command (if (:stdin command)
                    (let [stdin-script (slurp *in*)]
                      (-> command
                        (assoc :script stdin-script)
                        (dissoc :stdin)))
                    command)
          ;; Extract output-file directive (CLI-side, not sent to daemon)
          output-file (:_output_file command)
          command     (dissoc command :_output_file)
          ;; Send command with global flags for daemon to use
          flag-keys (dissoc flags :session :headless :json)
          cmd-with-flags (if (seq flag-keys)
                           (assoc command :_flags (into {} (map (fn [[k v]] [(name k) v]) flag-keys)))
                           command)
          timeout-ms (or (:timeout flags) 30000)
          response (loop [retries 0]
                     (let [res (try
                                 (send-command! (:session flags) cmd-with-flags timeout-ms)
                                 (catch Exception _
                                   (when (< retries 5)
                                     (Thread/sleep 200)
                                     ;; Re-ensure daemon is alive before retry
                                     (ensure-daemon! (:session flags) flags)
                                     ::retry)))]
                       (cond
                         (= ::retry res) (recur (inc retries))
                         ;; nil = daemon closed connection without responding (dying/stale)
                         ;; Treat as retriable — kill stale daemon and restart
                         (and (nil? res) (< retries 5))
                         (do (Thread/sleep 200)
                           (kill-stale-daemon! (:session flags))
                           (ensure-daemon! (:session flags) flags)
                           (recur (inc retries)))
                         :else res)))]
      (if response
        (if (and output-file (:success response))
          ;; Write to file: SRT as raw text, JSON for action_log
          (let [data (:data response)
                content (if (:srt data)
                          (:srt data)
                          (json/write-json-str data :escape-slash false))]
            (spit output-file content)
            (println (str "Written to: " output-file))
            (System/exit 0))
          (do (print-result response (:json flags))
            (System/exit (if (:success response) 0 1))))
        (do (binding [*out* *err*]
              (println "Error: Could not connect to daemon"))
          (System/exit 1))))))
