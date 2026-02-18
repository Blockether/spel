(ns com.blockether.spel.cli
  "CLI client for the spel daemon.

   Parses command-line arguments into JSON commands, sends them to the
   daemon over a Unix domain socket, and pretty-prints the results.

   If the daemon isn't running, it auto-starts one in the background.

   Usage:
     spel open https://example.com
     spel snapshot
     spel click @e1
     spel fill @e2 \"search text\"
     spel screenshot shot.png
     spel close"
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]
   [com.blockether.spel.daemon :as daemon])
  (:import
   [java.io BufferedReader InputStreamReader OutputStreamWriter]
   [java.net StandardProtocolFamily UnixDomainSocketAddress]
   [java.nio.channels Channels SocketChannel]
   [java.nio.file Files]))

(set! *warn-on-reflection* true)

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
      "  spel open <url> --interactive"
      ""
      "Examples:"
      "  spel open https://example.com"
      "  spel open example.com"
      "  spel open file:///tmp/page.html"
      "  spel open https://example.com --interactive"
      ""
      "Flags:"
      "  --interactive    Show browser window (headed mode)"])

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
      "  spel snapshot -i -C"
      ""
      "Flags:"
      "  -i, --interactive    Interactive elements only"
      "  -c, --compact        Compact output format"
      "  -C, --cursor         Include cursor/pointer elements"
      "  -d, --depth N        Limit tree depth to N levels"
      "  -s, --selector SEL   Scope snapshot to CSS selector"])

   "click"
   (str/join \newline
     ["click - Click an element"
      ""
      "Usage:"
      "  spel click <selector>"
      ""
      "Examples:"
      "  spel click @e1"
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
      "  spel dblclick @e1"
      "  spel dblclick \".editable-cell\""])

   "fill"
   (str/join \newline
     ["fill - Clear and fill an input element"
      ""
      "Usage:"
      "  spel fill <selector> <text>"
      ""
      "Examples:"
      "  spel fill @e2 \"user@example.com\""
      "  spel fill \"#search\" \"search query\""])

   "type"
   (str/join \newline
     ["type - Type text without clearing the input first"
      ""
      "Usage:"
      "  spel type <selector> <text>"
      ""
      "Examples:"
      "  spel type @e2 \"additional text\""
      "  spel type \"#editor\" \"appended content\""])

   "clear"
   (str/join \newline
     ["clear - Clear an input element"
      ""
      "Usage:"
      "  spel clear <selector>"
      ""
      "Examples:"
      "  spel clear @e2"
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
      "  spel press @e1 Enter"
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
      "  spel hover @e1"
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
      "  spel check @e3"
      "  spel check \"#agree-terms\""])

   "uncheck"
   (str/join \newline
     ["uncheck - Uncheck a checkbox"
      ""
      "Usage:"
      "  spel uncheck <selector>"
      ""
      "Examples:"
      "  spel uncheck @e3"
      "  spel uncheck \"#newsletter\""])

   "select"
   (str/join \newline
     ["select - Select a dropdown option"
      ""
      "Usage:"
      "  spel select <selector> <value> [value...]"
      ""
      "Examples:"
      "  spel select @e4 \"option1\""
      "  spel select \"#country\" \"US\""
      "  spel select @e4 \"opt1\" \"opt2\""])

   "focus"
   (str/join \newline
     ["focus - Focus an element"
      ""
      "Usage:"
      "  spel focus <selector>"
      ""
      "Examples:"
      "  spel focus @e1"
      "  spel focus \"#email-input\""])

   "scroll"
   (str/join \newline
     ["scroll - Scroll the page"
      ""
      "Usage:"
      "  spel scroll [direction] [amount]"
      ""
      "Arguments:"
      "  direction    up, down, left, or right (default: down)"
      "  amount       Pixels to scroll (default: 500)"
      ""
      "Examples:"
      "  spel scroll"
      "  spel scroll down 1000"
      "  spel scroll up 500"
      "  spel scroll left 200"])

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
      "  spel scrollintoview @e5"
      "  spel scrollinto \"#footer\""])

   "drag"
   (str/join \newline
     ["drag - Drag an element to another element"
      ""
      "Usage:"
      "  spel drag <source> <target>"
      ""
      "Examples:"
      "  spel drag @e1 @e2"
      "  spel drag \"#item\" \"#dropzone\""])

   "upload"
   (str/join \newline
     ["upload - Upload files to a file input"
      ""
      "Usage:"
      "  spel upload <selector> <file> [file...]"
      ""
      "Examples:"
      "  spel upload @e1 photo.jpg"
      "  spel upload \"input[type=file]\" doc.pdf image.png"])

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
      ""
      "Flags:"
      "  -f, --full-page, --full    Capture full page (not just viewport)"])

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

   "eval"
   (str/join \newline
     ["eval - Evaluate JavaScript in the page context"
      ""
      "Usage:"
      "  spel eval <script>"
      "  spel eval --stdin"
      ""
      "Examples:"
      "  spel eval \"document.title\""
      "  spel eval \"document.querySelector('h1').textContent\""
      "  spel eval -b \"JSON.stringify(data)\""
      "  echo 'document.title' | spel eval --stdin"
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
      "  spel wait @e1"
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
      "  spel tab new https://example.com"
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
      "  spel get text @e1"
      "  spel get url"
      "  spel get title"
      "  spel get html @e1"
      "  spel get value @e2"
      "  spel get attr @e1 href"
      "  spel get count \".items\""
      "  spel get box @e1"])

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
      "  spel is visible @e1"
      "  spel is enabled @e2"
      "  spel is checked @e3"])

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
      "  spel bbox @e1"
      "  spel bbox \"#header\""])

   "highlight"
   (str/join \newline
     ["highlight - Visually highlight an element on the page"
      ""
      "Usage:"
      "  spel highlight <selector>"
      ""
      "Examples:"
      "  spel highlight @e1"
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
      "  spel find label \"Email\" fill \"user@example.com\""
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
      "  spel network <subcommand> [args]"
      ""
      "Subcommands:"
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
      "  spel console [subcommand]"
      ""
      "Subcommands:"
      "  (none)    View all captured console messages"
      "  clear     Clear captured messages"
      ""
      "Examples:"
      "  spel console"
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
      "  spel --session work open https://example.com"])

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
      "  spel close"
      ""
      "Examples:"
      "  spel close"
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
      "  --with-deps    Install system dependencies alongside browsers"])})

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
     "  upload                  Upload files"
     ""
     "Capture:"
     "  screenshot              Take screenshot"
     "  annotate                Inject annotation overlays"
     "  unannotate              Remove annotation overlays"
     "  pdf                     Save page as PDF"
     ""
     "JavaScript:"
     "  eval                    Evaluate JavaScript"
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
     "  network                 Inspect requests, intercept routes"
     ""
     "Frames:"
     "  frame                   Navigate between frames"
     ""
     "Dialogs:"
     "  dialog                  Handle browser dialogs"
     ""
     "Debug:"
     "  trace                   Record Playwright traces"
     "  console                 View console messages"
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
     "Lifecycle:"
     "  close, quit, exit       Close browser and daemon"
     "  install                 Install Playwright browsers"
     ""
     "Global Flags:"
     "  --session NAME          Named browser session (default: \"default\")"
     "  --json                  JSON output mode"
     "  --interactive           Show browser window (headed mode)"
     "  --proxy URL             HTTP proxy"
     "  --proxy-bypass DOMAINS  Proxy bypass list"
     "  --user-agent STRING     Custom User-Agent"
     "  --executable-path PATH  Custom browser binary"
     "  --args \"ARG1,ARG2\"     Extra browser arguments"
     "  --cdp URL               Connect via Chrome DevTools Protocol"
     "  --ignore-https-errors   Ignore HTTPS certificate errors"
     "  --profile PATH          Persistent browser profile"
     "  --timeout MS            Command timeout in milliseconds"
     "  --debug                 Enable debug logging"
     ""
     "Environment Variables:"
     "  SPEL_SESSION            Default session name"
     "  SPEL_JSON               Set to \"true\" for JSON output"
     "  SPEL_PROFILE            Default browser profile path"
     "  SPEL_HEADERS            Default HTTP headers (JSON)"
     "  SPEL_EXECUTABLE_PATH    Default browser executable"]))

;; =============================================================================
;; Arg Parsing
;; =============================================================================

(defn parse-args
  "Parses CLI argv into a command map suitable for sending to the daemon.

   Returns a map with :command and :flags.
   :command is the action map to send to the daemon.
   :flags contains global options like :session, :json."
  [args]
  (let [;; Read environment variable defaults
        env-defaults (cond-> {:session "default" :headless true :json false}
                       (System/getenv "SPEL_SESSION")
                       (assoc :session (System/getenv "SPEL_SESSION"))
                       (= "true" (System/getenv "SPEL_JSON"))
                       (assoc :json true)
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
                       (System/getenv "SPEL_CDP")
                       (assoc :cdp (System/getenv "SPEL_CDP"))
                       (System/getenv "SPEL_ARGS")
                       (assoc :args (System/getenv "SPEL_ARGS"))
                       (System/getenv "SPEL_TIMEOUT")
                       (assoc :timeout (parse-long (System/getenv "SPEL_TIMEOUT"))))

        ;; Extract global flags first
        {:keys [flags remaining]}
        (loop [args args
               flags env-defaults
               remaining []]
          (if (empty? args)
            {:flags flags :remaining remaining}
            (let [arg (first args)]
              (cond
                (= "--headless" arg)
                (recur (rest args) (assoc flags :headless true) remaining)

                (= "--headed" arg)
                (recur (rest args) (assoc flags :headless false) remaining)

                (= "--session" arg)
                (recur (drop 2 args) (assoc flags :session (second args)) remaining)

                (str/starts-with? arg "--session=")
                (recur (rest args) (assoc flags :session (subs arg 10)) remaining)

                (= "--json" arg)
                (recur (rest args) (assoc flags :json true) remaining)

                (= "--profile" arg)
                (recur (drop 2 args) (assoc flags :profile (second args)) remaining)

                (str/starts-with? arg "--profile=")
                (recur (rest args) (assoc flags :profile (subs arg 10)) remaining)

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

                (= "--session-name" arg)
                (recur (drop 2 args) (assoc flags :session (second args)) remaining)

                (str/starts-with? arg "--session-name=")
                (recur (rest args) (assoc flags :session (subs arg 15)) remaining)

                (= "--cdp" arg)
                (recur (drop 2 args) (assoc flags :cdp (second args)) remaining)

                (str/starts-with? arg "--cdp=")
                (recur (rest args) (assoc flags :cdp (subs arg 6)) remaining)

                (= "--timeout" arg)
                (recur (drop 2 args) (assoc flags :timeout (parse-long (second args))) remaining)

                (str/starts-with? arg "--timeout=")
                (recur (rest args) (assoc flags :timeout (parse-long (subs arg 10))) remaining)

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
          ;; --interactive opens the browser in headed (visible) mode, allowing
          ;; you to see and manually interact with the page. The daemon is
          ;; restarted if it was previously running headless. This is the only
          ;; way to get a visible browser window — there is no global --headed flag.
            ("open" "goto" "navigate")
            (let [interactive? (some #{"--interactive"} cmd-args)
                  url-args (remove #(str/starts-with? % "-") cmd-args)
                  raw-url  (first url-args)
                  url      (when raw-url
                             (if (or (str/starts-with? raw-url "http://")
                                   (str/starts-with? raw-url "https://")
                                   (str/starts-with? raw-url "file://")
                                   (str/starts-with? raw-url "data:"))
                               raw-url
                               (str "https://" raw-url)))]
              (cond-> {:action "navigate" :url url}
                interactive? (assoc :interactive true)))

          ;; Snapshot (with filter options)
            "snapshot" (let [snap-flags (set cmd-args)]
                         (cond-> {:action "snapshot"}
                           (or (snap-flags "-i") (snap-flags "--interactive"))
                           (assoc :interactive true)
                           (or (snap-flags "-c") (snap-flags "--compact"))
                           (assoc :compact true)
                           (or (snap-flags "-C") (snap-flags "--cursor"))
                           (assoc :cursor true)
                         ;; Parse -d N
                           (some #{"-d" "--depth"} cmd-args)
                           (assoc :depth (let [v    (vec cmd-args)
                                               idx1 (.indexOf ^java.util.List v "-d")
                                               idx2 (.indexOf ^java.util.List v "--depth")
                                               idx  (cond (>= idx1 0) idx1
                                                      (>= idx2 0) idx2
                                                      :else -1)]
                                           (when (>= idx 0)
                                             (try (Integer/parseInt (nth cmd-args (inc idx)))
                                               (catch Exception _ nil)))))
                         ;; Parse -s <sel>
                           (some #{"-s" "--selector"} cmd-args)
                           (assoc :selector (let [v    (vec cmd-args)
                                                  idx1 (.indexOf ^java.util.List v "-s")
                                                  idx2 (.indexOf ^java.util.List v "--selector")
                                                  idx  (cond (>= idx1 0) idx1
                                                         (>= idx2 0) idx2
                                                         :else -1)]
                                              (when (>= idx 0)
                                                (nth cmd-args (inc idx) nil))))))

          ;; Click
            "click"    {:action "click" :selector (first cmd-args)}
            "dblclick"  {:action "dblclick" :selector (first cmd-args)}

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
            "scroll"   (let [direction (or (first cmd-args) "down")
                             amount (try (Integer/parseInt (or (second cmd-args) "500"))
                                      (catch Exception _ 500))]
                         {:action "scroll" :direction direction :amount amount})

            ("scrollintoview" "scrollinto")
            {:action "scrollintoview" :selector (first cmd-args)}

          ;; Drag & Upload
            "drag"     {:action "drag"
                        :source (first cmd-args)
                        :target (second cmd-args)}

            "upload"   {:action "upload"
                        :selector (first cmd-args)
                        :files (vec (rest cmd-args))}

          ;; Screenshot
            "screenshot" (let [path-args (remove #(str/starts-with? % "-") cmd-args)
                               path      (first path-args)]
                           (cond-> {:action "screenshot"}
                             path (assoc :path path)
                             (some #{"-f" "--full-page" "--full"} cmd-args)
                             (assoc :fullPage true)))

          ;; Annotate (inject overlays onto the page for visible elements)
            "annotate" (cond-> {:action "annotate"}
                         (some #{"--no-badges"} cmd-args)
                         (assoc :show-badges false)
                         (some #{"--no-dimensions" "--no-dims"} cmd-args)
                         (assoc :show-dimensions false)
                         (some #{"--no-boxes"} cmd-args)
                         (assoc :show-boxes false)
                         ;; Parse -s <selector-or-ref>
                         (some #{"-s" "--scope"} cmd-args)
                         (assoc :selector (let [idx (or (.indexOf ^java.util.List (vec cmd-args) "-s")
                                                      (.indexOf ^java.util.List (vec cmd-args) "--scope"))]
                                            (when (>= idx 0)
                                              (nth cmd-args (inc idx) nil)))))

          ;; Unannotate (remove overlays from the page)
            "unannotate" {:action "unannotate"}

          ;; PDF
            "pdf"      {:action "pdf" :path (or (first cmd-args) "page.pdf")}

          ;; JavaScript
            "eval"     (let [base64?  (some #{"-b" "--base64"} cmd-args)
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
                           (let [idx (.indexOf ^java.util.List wait-args "--text")]
                             {:action "wait" :text (nth wait-args (inc idx))})

                           (some #{"--url"} wait-args)
                           (let [idx (.indexOf ^java.util.List wait-args "--url")]
                             {:action "wait" :url (nth wait-args (inc idx))})

                           (some #{"--fn"} wait-args)
                           (let [idx (.indexOf ^java.util.List wait-args "--fn")]
                             {:action "wait" :function (nth wait-args (inc idx))})

                           (some #{"--load"} wait-args)
                           (let [idx (.indexOf ^java.util.List wait-args "--load")]
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
                             name-idx (.indexOf ^java.util.List (vec cmd-args) "--name")
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
                                          :headers (json/read-str (second cmd-args))}
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
            "network"  (let [sub (first cmd-args)]
                         (case sub
                           "route"    (let [url       (second cmd-args)
                                            rest-args (set (drop 2 cmd-args))
                                            abort?    (rest-args "--abort")
                                            body-idx  (.indexOf ^java.util.List (vec cmd-args) "--body")
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
                                                        (let [i (.indexOf ^java.util.List args-v flag)]
                                                          (when (>= i 0) (nth args-v (inc i) nil))))]
                                        (cond-> {:action "network_requests"}
                                          (flag-val "--filter") (assoc :filter (flag-val "--filter"))
                                          (flag-val "--type")   (assoc :type   (flag-val "--type"))
                                          (flag-val "--method") (assoc :method (flag-val "--method"))
                                          (flag-val "--status") (assoc :status (flag-val "--status"))))
                           "clear"    {:action "network_clear"}
                           {:error (str "Unknown network command: " sub)}))

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

            "console"  (case (first cmd-args)
                         ("clear" "--clear") {:action "console_clear"}
                         {:action "console_get"})

            "errors"   (case (first cmd-args)
                         "clear" {:action "errors_clear"}
                         "--clear" {:action "errors_get" :clear true}
                         {:action "errors_get"})

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
                           "clean"  (let [idx (.indexOf ^java.util.List (vec cmd-args) "--older-than")]
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

          ;; Close (+ aliases)
            ("close" "quit" "exit")
            {:action "close"}

          ;; Install
            "install"  {:action "install"
                        :with-deps (some #{"--with-deps"} cmd-args)}

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
   `timeout-ms` controls how long to wait for a response (default 30000ms)."
  ([^String session command-map]
   (send-command! session command-map 30000))
  ([^String session command-map timeout-ms]
   (let [sock-path (daemon/socket-path session)
         addr      (UnixDomainSocketAddress/of (.toString sock-path))
         channel   (SocketChannel/open StandardProtocolFamily/UNIX)]
     (try
       (.connect channel addr)
       (let [reader (BufferedReader.
                      (InputStreamReader. (Channels/newInputStream channel)))
             writer (OutputStreamWriter. (Channels/newOutputStream channel))]
         (.write writer ^String (json/write-str command-map))
         (.write writer "\n")
         (.flush writer)
         (let [f      (future (.readLine reader))
               result (deref f timeout-ms ::timeout)]
           (when (= ::timeout result)
             (future-cancel f)
             (throw (ex-info "Daemon response timed out" {:timeout-ms timeout-ms})))
           (when result
             (json/read-str result :key-fn keyword))))
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
   Sends close command, waits for the old process to exit, then cleans up files."
  [session]
  (let [old-pid (read-pid session)]
    ;; Send graceful close (5s timeout — just asking daemon to exit)
    (try (send-command! session {:action "close"} 5000)
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
                    (conj "--headed"))
        pb        (if (and exec-path
                        (or (str/ends-with? exec-path "spel")
                          (str/ends-with? exec-path "spel.exe")))
                    (ProcessBuilder. ^java.util.List
                      (into [exec-path] args))
                    (let [classpath (System/getProperty "java.class.path")]
                      (ProcessBuilder. ^java.util.List
                        (into ["java" "-cp" classpath
                               "com.blockether.spel.native"]
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
   - Stale daemon (process alive but socket broken) → kill and restart
   - Dead daemon (PID file exists but process gone) → clean up and restart"
  [session opts]
  ;; If interactive mode requested but daemon is running, restart in headed mode
  (when (and (not (:headless opts true))
          (daemon/daemon-running? session))
    (restart-daemon! session))

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
  "Pretty-prints a daemon response to the terminal."
  [response json-mode?]
  (if json-mode?
    (println (json/write-str response :escape-slash false))
    (let [{:keys [success data error]} response]
      (if success
        (cond
          ;; Snapshot responses
          (:snapshot data)
          (do (print-snapshot (:snapshot data))
            (when (:url data)
              (println (str "\n  URL: " (:url data))))
            (when (:title data)
              (println (str "  Title: " (:title data)))))

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

          ;; Evaluate
          (contains? data :result)
          (prn (:result data))

          ;; Storage
          (contains? data :storage)
          (prn (:storage data))

          ;; Cookies
          (:cookies data)
          (doseq [c (:cookies data)]
            (println (str (:name c) "=" (:value c)
                       " (domain=" (:domain c) " path=" (:path c) ")")))

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

          ;; Fallback
          :else
          (prn data))

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

    ;; Ensure daemon is running
    (ensure-daemon! (:session flags) flags)

    ;; Read from stdin if eval --stdin was used
    (let [command (if (:stdin command)
                    (let [stdin-script (slurp *in*)]
                      (-> command
                        (assoc :script stdin-script)
                        (dissoc :stdin)))
                    command)
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
        (do (print-result response (:json flags))
          (System/exit (if (:success response) 0 1)))
        (do (binding [*out* *err*]
              (println "Error: Could not connect to daemon"))
          (System/exit 1))))))
