(ns com.blockether.spel.webdriver
  "Minimal W3C WebDriver HTTP client for the iOS application provider.

   Talks directly to a WebDriver-compatible endpoint (Appium) using JDK
   `java.net.http.HttpClient` and charred JSON — no Selenium/Appium Java
   client dependency, keeping the GraalVM native-image surface small.

   The `WebDriverSession` record holds data only (base URL, session id,
   capabilities, timeout). Process and simulator ownership belongs to the
   iOS session layer (`com.blockether.spel.ios`).

   Errors are raised as `ex-info` with structured keys:
     :webdriver/error       - W3C error code string (e.g. \"no such element\")
     :webdriver/message     - human message from the remote end
     :webdriver/stacktrace  - remote stacktrace when provided
     :webdriver/http-status - HTTP status code
     :webdriver/endpoint    - request path"
  (:require
   [charred.api :as json]
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpClient$Version HttpRequest
    HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
   [java.time Duration]
   [java.util Base64]))

(def default-connect-timeout-ms
  "Default connect timeout for WebDriver HTTP requests (milliseconds)."
  10000)

(def default-command-timeout-ms
  "Default per-command timeout for WebDriver HTTP requests (milliseconds).
   Generous because XCUITest commands (session creation aside) can be slow."
  60000)

(defrecord WebDriverSession
  [base-url session-id capabilities command-timeout-ms])

;; =============================================================================
;; HTTP transport
;; =============================================================================

(defonce ^:private !client
  (delay
    (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)
      (.connectTimeout (Duration/ofMillis default-connect-timeout-ms))
      (.build))))

(defn- normalize-base-url
  "Strips a trailing slash from a base URL so path joining is predictable."
  ^String [^String base-url]
  (if (str/ends-with? base-url "/")
    (subs base-url 0 (dec (count base-url)))
    base-url))

(defn- decode-error!
  "Throws a structured ex-info for a non-2xx WebDriver response.

   W3C error shape: {\"value\" {\"error\" .. \"message\" .. \"stacktrace\" ..}}"
  [^long status ^String endpoint body-str]
  (let [parsed (try (json/read-json body-str) (catch Exception _ nil))
        value  (when (map? parsed) (get parsed "value"))
        error  (when (map? value) (get value "error"))
        msg    (or (when (map? value) (get value "message"))
                 (str "WebDriver request failed with HTTP " status))]
    (throw (ex-info (str "WebDriver error"
                      (when error (str " [" error "]"))
                      ": " msg)
             {:webdriver/error       error
              :webdriver/message     msg
              :webdriver/stacktrace  (when (map? value) (get value "stacktrace"))
              :webdriver/http-status status
              :webdriver/endpoint    endpoint}))))

(defn request
  "Performs a WebDriver HTTP request and returns the decoded `value` field.

   Params:
   `opts` - Map:
     :base-url   - String. WebDriver server root (e.g. http://127.0.0.1:4901).
     :method     - Keyword. :get, :post, or :delete.
     :path       - String. Endpoint path starting with /.
     :body       - Map, optional. JSON-encoded for :post requests.
     :timeout-ms - Long, optional. Per-request timeout.

   Returns the decoded JSON `value` (may be nil) plus the full parsed body
   under metadata is NOT used — callers needing the envelope use
   `request-envelope`.

   Throws ex-info with :webdriver/* keys on HTTP or W3C errors."
  [{:keys [base-url method path body timeout-ms]}]
  (let [base       (normalize-base-url base-url)
        uri        (URI/create (str base path))
        timeout    (Duration/ofMillis (long (or timeout-ms default-command-timeout-ms)))
        body-str   (when (= :post method)
                     (json/write-json-str (or body {})))
        builder    (-> (HttpRequest/newBuilder uri)
                     (.timeout timeout)
                     (.header "Content-Type" "application/json; charset=utf-8")
                     (.header "Accept" "application/json"))
        req        (case method
                     :get    (.build (.GET builder))
                     :delete (.build (.DELETE builder))
                     :post   (.build (.POST builder
                                       (HttpRequest$BodyPublishers/ofString ^String body-str)))
                     (throw (ex-info (str "Unsupported WebDriver HTTP method: " method)
                              {:webdriver/endpoint path})))
        resp       (.send ^HttpClient @!client req (HttpResponse$BodyHandlers/ofString))
        status     (long (.statusCode resp))
        resp-body  (.body resp)]
    (if (<= 200 status 299)
      (let [parsed (try (json/read-json resp-body)
                     (catch Exception e
                       (throw (ex-info (str "WebDriver returned malformed JSON from " path)
                                {:webdriver/http-status status
                                 :webdriver/endpoint    path
                                 :webdriver/body        resp-body}
                                e))))]
        parsed)
      (decode-error! status path resp-body))))

(defn- session-request
  "Performs a request scoped to a session's base URL and timeout.
   Returns the `value` field of the response envelope."
  [{:keys [base-url command-timeout-ms]} method ^String path body]
  (get (request {:base-url   base-url
                 :method     method
                 :path       path
                 :body       body
                 :timeout-ms command-timeout-ms})
    "value"))

(defn- session-path
  "Builds /session/{id}{suffix} for a WebDriverSession."
  ^String [{:keys [session-id]} ^String suffix]
  (str "/session/" session-id suffix))

;; =============================================================================
;; Session lifecycle
;; =============================================================================

(defn status
  "GET /status — returns the decoded value map ({\"ready\" true ...}).
   Throws on connection failure or non-2xx response."
  ([base-url] (status base-url {}))
  ([base-url {:keys [timeout-ms]}]
   (get (request {:base-url   base-url
                  :method     :get
                  :path       "/status"
                  :timeout-ms (or timeout-ms 5000)})
     "value")))

(defn ready?
  "Returns true when GET /status reports the server ready, false otherwise
   (including connection failures — safe for startup polling)."
  [base-url]
  (try
    (boolean (get (status base-url) "ready"))
    (catch Exception _ false)))

(defn extract-session-id
  "Extracts a session id from a parsed create-session response envelope.

   Handles both shapes:
   - W3C:    {\"value\" {\"sessionId\" \"...\" \"capabilities\" {...}}}
   - Legacy: {\"sessionId\" \"...\" \"value\" {...}}"
  [parsed]
  (or (get-in parsed ["value" "sessionId"])
    (get parsed "sessionId")))

(defn extract-capabilities
  "Extracts the capabilities map from a parsed create-session response,
   handling both W3C and legacy shapes."
  [parsed]
  (or (get-in parsed ["value" "capabilities"])
    (let [v (get parsed "value")]
      (when (map? v) v))))

(defn create-session
  "POST /session — creates a new WebDriver session.

   Params:
   `base-url`     - String. WebDriver server root.
   `capabilities` - Map. W3C capabilities (sent as {\"capabilities\"
                    {\"alwaysMatch\" caps \"firstMatch\" [{}]}}).
   `opts`         - Map, optional:
     :timeout-ms         - Long. Session-creation timeout (WDA install can
                           take 60-90s on first run). Default 120000.
     :command-timeout-ms - Long. Timeout stored on the returned session for
                           subsequent commands.

   Returns a WebDriverSession record.
   Throws ex-info when no session id can be extracted."
  ([base-url capabilities] (create-session base-url capabilities {}))
  ([base-url capabilities {:keys [timeout-ms command-timeout-ms]}]
   (let [parsed     (request {:base-url   base-url
                              :method     :post
                              :path       "/session"
                              :body       {"capabilities" {"alwaysMatch" capabilities
                                                           "firstMatch"  [{}]}}
                              :timeout-ms (or timeout-ms 120000)})
         session-id (extract-session-id parsed)]
     (when (str/blank? (str session-id))
       (throw (ex-info "WebDriver create-session returned no session id"
                {:webdriver/endpoint "/session"
                 :webdriver/response parsed})))
     (->WebDriverSession (normalize-base-url base-url)
       session-id
       (extract-capabilities parsed)
       (long (or command-timeout-ms default-command-timeout-ms))))))

(defn delete-session!
  "DELETE /session/{id} — ends the WebDriver session. Idempotent-friendly:
   callers should tolerate exceptions when the session is already gone."
  [session]
  (session-request session :delete (session-path session "") nil))

;; =============================================================================
;; Navigation and page state
;; =============================================================================

(defn navigate
  "POST /session/{id}/url — navigates to `url`."
  [session ^String url]
  (session-request session :post (session-path session "/url") {"url" url}))

(defn url
  "GET /session/{id}/url — returns the current URL string."
  [session]
  (session-request session :get (session-path session "/url") nil))

(defn title
  "GET /session/{id}/title — returns the page title string."
  [session]
  (session-request session :get (session-path session "/title") nil))

(defn content
  "GET /session/{id}/source — returns the page HTML source string."
  [session]
  (session-request session :get (session-path session "/source") nil))

(defn back
  "POST /session/{id}/back — history back."
  [session]
  (session-request session :post (session-path session "/back") {}))

(defn forward
  "POST /session/{id}/forward — history forward."
  [session]
  (session-request session :post (session-path session "/forward") {}))

(defn reload
  "POST /session/{id}/refresh — reloads the page."
  [session]
  (session-request session :post (session-path session "/refresh") {}))

(defn cookies
  "GET /session/{id}/cookie — returns the cookie list."
  [session]
  (session-request session :get (session-path session "/cookie") nil))

;; =============================================================================
;; Appium contexts and applications
;; =============================================================================

(declare execute-mobile)

(defn contexts
  "Returns the available Appium automation contexts, for example
   [\"NATIVE_APP\" \"WEBVIEW_1\"]."
  [session]
  (vec (or (session-request session :get (session-path session "/contexts") nil) [])))

(defn context-details
  "Returns Appium's detailed context records when supported. Records may
   contain id, title, url, bundleId, and page identifiers. Falls back to
   name-only records on older XCUITest drivers."
  [session]
  (try
    (let [result (execute-mobile session "mobile: getContexts"
                   {"waitForWebviewMs" 0})]
      (if (sequential? result)
        (mapv (fn [item]
                (if (map? item) item {"id" (str item)}))
          result)
        []))
    (catch Exception _
      (mapv (fn [context] {"id" context}) (contexts session)))))

(defn current-context
  "Returns the current Appium automation context name."
  [session]
  (session-request session :get (session-path session "/context") nil))

(defn switch-context
  "Switches the Appium session to `context-name`. Native element commands
   operate in NATIVE_APP; DOM, CSS, and JavaScript commands operate in a
   WEBVIEW context."
  [session ^String context-name]
  (session-request session :post (session-path session "/context")
    {"name" context-name})
  context-name)

(defn native-context?
  "Returns true when the session currently targets NATIVE_APP. Plain W3C
   servers that do not implement contexts are treated as web sessions."
  [session]
  (try
    (= "NATIVE_APP" (current-context session))
    (catch Exception _ false)))

(defn activate-app
  "Activates an installed iOS application by bundle identifier."
  [session ^String bundle-id]
  (execute-mobile session "mobile: activateApp" {"bundleId" bundle-id})
  bundle-id)

(defn app-state
  "Returns Appium's numeric application state for `bundle-id`:
   0 unknown, 1 not running, 2 background suspended, 3 background, 4 foreground."
  [session ^String bundle-id]
  (execute-mobile session "mobile: queryAppState" {"bundleId" bundle-id}))

(defn launch-app
  "Launches an installed application. Optional opts may include :arguments
   and :environment."
  ([session bundle-id] (launch-app session bundle-id {}))
  ([session ^String bundle-id {:keys [arguments environment]}]
   (execute-mobile session "mobile: launchApp"
     (cond-> {"bundleId" bundle-id}
       (seq arguments) (assoc "arguments" (vec arguments))
       (seq environment) (assoc "environment" environment)))
   bundle-id))

(defn terminate-app
  "Terminates an installed application. Returns Appium's result."
  [session ^String bundle-id]
  (execute-mobile session "mobile: terminateApp" {"bundleId" bundle-id}))

(defn background-app
  "Moves the active application to the background for `seconds`."
  [session seconds]
  (execute-mobile session "mobile: backgroundApp" {"seconds" (long seconds)}))

(defn install-app
  "Installs a simulator-compatible .app through Appium."
  [session ^String app-path]
  (execute-mobile session "mobile: installApp" {"app" app-path})
  app-path)

(defn remove-app
  "Uninstalls an application by bundle identifier through Appium."
  [session ^String bundle-id]
  (execute-mobile session "mobile: removeApp" {"bundleId" bundle-id}))

(defn app-installed?
  "Returns true when an application is installed."
  [session ^String bundle-id]
  (boolean (execute-mobile session "mobile: isAppInstalled" {"bundleId" bundle-id})))

(defn open-url
  "Opens a deep/universal URL for an application."
  [session ^String url bundle-id]
  (execute-mobile session "mobile: deepLink"
    (cond-> {"url" url} bundle-id (assoc "bundleId" bundle-id)))
  url)

(defn get-permission
  "Returns the current iOS Simulator permission value for a service."
  [session ^String bundle-id ^String service]
  (execute-mobile session "mobile: getPermission"
    {"bundleId" bundle-id "service" service}))

(defn set-permission
  "Sets an iOS Simulator permission using XCUITest's mobile: setPermission.
   `access` is yes, no, or unset for the requested service."
  [session ^String bundle-id ^String service access]
  (execute-mobile session "mobile: setPermission"
    {"bundleId" bundle-id "access" {service (name access)}}))

(defn window-rect
  "Returns the current native window rectangle as {:x :y :width :height}."
  [session]
  (let [r (session-request session :get (session-path session "/window/rect") nil)]
    {:x (long (or (get r "x") 0))
     :y (long (or (get r "y") 0))
     :width (long (or (get r "width") 0))
     :height (long (or (get r "height") 0))}))

;; =============================================================================
;; JavaScript evaluation
;; =============================================================================

(def ^:private regex-after-keyword-re
  "Keywords after which a `/` can only open a REGEX literal, never divide.
   These are JavaScript's own grammar, not a list spel maintains about itself."
  #"(?:^|[^\w$.])(?:return|typeof|instanceof|case|in|of|new|delete|do|else|void|yield|await|throw)$")

(defn- regex-literal-start?
  "True when the `/` following `before` (the BLANKED code scanned so far) opens
   a regex literal rather than a division. A division can only follow a value:
   `)`, `]` or the end of an identifier or number — and an identifier that is a
   keyword is not a value."
  [^String before]
  (let [b (str/trimr before)
        n (.length b)]
    (if (zero? n)
      true
      (let [c (.charAt b (dec n))]
        (cond
          (or (= c \)) (= c \])) false
          (or (Character/isLetterOrDigit c) (= c \_) (= c \$))
          (boolean (re-find regex-after-keyword-re b))
          :else true)))))

(def ^:private value-char
  "A string, template literal or regex literal is blanked to this digit rather
   than to a space: the offsets survive AND the fact that a VALUE stands there
   survives, so `a = 1; 'x'` still reads as two statements and `'for (;;) x'`
   still reads as an expression."
  \0)

(defn- fill-value!
  "Blanks `[from to)` of `buf` to `value-char`."
  [^chars buf from to]
  (loop [i (long from)]
    (when (< i (long to))
      (aset-char buf i value-char)
      (recur (inc i)))))

(defn- blank-script
  "Reads `script` once and returns the two facts every later question is asked
   of.

     :semicolons  indexes of the `;` characters at depth 0 — outside
                  parentheses, brackets, braces, strings, template literals,
                  regex literals and comments. Such a `;` is the one construct
                  that cannot appear in a JS EXPRESSION, so it is what
                  separates `a + 1` (wrappable in `return (…)`) from `a = 1; a`
                  (a function body). A `for (;;)` header's semicolons sit
                  inside parentheses and are therefore invisible here, which is
                  exactly right.
     :code        the same script with every string, template literal, regex
                  literal and comment blanked to spaces, offsets preserved. A
                  `return`, a `for` or a `;` living inside one of those cannot
                  answer a question about the script's structure."
  [^String script]
  (let [n   (.length script)
        buf (char-array n \space)]
    (loop [i 0, depth 0, in-string nil, in-comment nil, semis []]
      (if (>= i n)
        {:semicolons semis :code (String. buf)}
        (let [c (.charAt script i)]
          (cond
            in-comment
            (cond
              (and (= in-comment :line) (= c \newline))
              (do (aset-char buf i \newline)
                (recur (inc i) depth in-string nil semis))

              (and (= in-comment :block) (= c \*) (< (inc i) n) (= (.charAt script (inc i)) \/))
              (recur (+ i 2) depth in-string nil semis)

              :else (recur (inc i) depth in-string in-comment semis))

            in-string
            (cond
              (= c \\) (do (fill-value! buf i (min n (+ i 2)))
                         (recur (+ i 2) depth in-string in-comment semis))
              ;; `in-string` holds the opening quote as an int CODE POINT: an
              ;; int comparison is reflection-free, while a primitive char
              ;; compared against a nilable local is what native-image refuses.
              (== (int c) (long in-string))
              (do (fill-value! buf i (inc i))
                (recur (inc i) depth nil in-comment semis))
              :else (do (fill-value! buf i (inc i))
                      (recur (inc i) depth in-string in-comment semis)))

            (and (= c \/) (< (inc i) n) (= (.charAt script (inc i)) \/))
            (recur (+ i 2) depth in-string :line semis)

            (and (= c \/) (< (inc i) n) (= (.charAt script (inc i)) \*))
            (recur (+ i 2) depth in-string :block semis)

            ;; A regex literal is a VALUE written with delimiters, so its `;`
            ;; and quotes are not the script's. Skipping to the closing `/`
            ;; leaves the flags to be read as the identifier characters they
            ;; are.
            (and (= c \/) (regex-literal-start? (String. ^chars buf 0 i)))
            (let [close (loop [j (inc i), in-class false]
                          (cond
                            (>= j n) n
                            (= (.charAt script j) \\) (recur (+ j 2) in-class)
                            (= (.charAt script j) \[) (recur (inc j) true)
                            (= (.charAt script j) \]) (recur (inc j) false)
                            (and (not in-class) (= (.charAt script j) \newline)) j
                            (and (not in-class) (= (.charAt script j) \/)) (inc j)
                            :else (recur (inc j) in-class)))]
              (fill-value! buf i (min n (long close)))
              (recur (long close) depth in-string in-comment semis))

            (or (= c \") (= c \') (= c \`))
            (do (fill-value! buf i (inc i))
              (recur (inc i) depth (int c) in-comment semis))

            :else
            (do (aset-char buf i c)
              (cond
                (or (= c \() (= c \[) (= c \{))
                (recur (inc i) (inc depth) in-string in-comment semis)

                (or (= c \)) (= c \]) (= c \}))
                (recur (inc i) (dec depth) in-string in-comment semis)

                (and (= c \;) (zero? depth))
                (recur (inc i) depth in-string in-comment (conj semis i))

                :else (recur (inc i) depth in-string in-comment semis)))))))))

(def ^:private control-head-re
  "A `)` that closes one of these heads is followed by a BLOCK, not by a
   function body."
  #"(?:^|[^\w$.])(?:if|for|while|switch|catch|with)\s*$")

(def ^:private own-return-re
  #"(?:^|[^\w$.])return(?![\w$])")

(defn- scan-returns
  "Indexes of the `return` keywords that belong to THE SCRIPT — read off the
   BLANKED code, and counted only outside any nested function body.

   A W3C script IS a function body, so a `return` at its own level (including
   one inside an `if` block) is the script producing its value, and spel must
   not add a second one. A `return` inside a nested function or arrow belongs
   to that function; treating it as the script's own is what made
   `const f = () => { return 1 }; f()` evaluate to undefined."
  [^String code]
  (let [n (.length code)]
    (loop [i 0, parens [], opens {}, braces [], fn-depth 0, returns []]
      (if (>= i n)
        returns
        (let [c (.charAt code i)]
          (cond
            (= c \() (recur (inc i) (conj parens i) opens braces fn-depth returns)

            (= c \))
            (recur (inc i) (cond-> parens (seq parens) pop)
              (cond-> opens (seq parens) (assoc i (peek parens)))
              braces fn-depth returns)

            (= c \{)
            (let [before (str/trimr (subs code 0 i))
                  m      (.length before)
                  prev   (when (pos? m) (.charAt before (dec m)))
                  fn?    (cond
                           (str/ends-with? before "=>") true
                           (= prev \))
                           (let [open (get opens (dec m))
                                 head (if open (str/trimr (subs code 0 (long open))) "")]
                             (not (re-find control-head-re head)))
                           :else false)]
              (recur (inc i) parens opens (conj braces fn?)
                (cond-> fn-depth fn? inc) returns))

            (= c \})
            (let [fn? (peek braces)]
              (recur (inc i) parens opens (cond-> braces (seq braces) pop)
                (cond-> fn-depth fn? dec) returns))

            (and (zero? fn-depth) (= c \r)
              (re-find own-return-re (subs code (max 0 (dec i)) (min n (+ i 7)))))
            (recur (+ i 6) parens opens braces fn-depth (conj returns i))

            :else (recur (inc i) parens opens braces fn-depth returns)))))))

(def ^:private statement-start-re
  "Keywords that can only begin a STATEMENT, so `return (script)` could never
   be valid for a script starting with one."
  #"^(?:var|let|const|if|for|while|do|switch|try|throw|function|class|debugger)(?![\w$])")

(defn- statement-start?
  "True when the BLANKED code starts with a keyword that can only begin a
   statement."
  [^String code]
  (boolean (re-find statement-start-re (str/triml (str code)))))

(defn- script-segments
  "The script's top-level statements as `{:start :text :code}`, split on the
   top-level semicolons. `:code` is that statement's BLANKED text, so a segment
   that is only a comment reads as blank."
  [^String trimmed ^String code semicolons]
  (let [n     (.length trimmed)
        bound (conj (vec semicolons) n)]
    (first
      (reduce (fn [[segs start] end]
                [(conj segs {:start start
                             :text  (subs trimmed start (long end))
                             :code  (subs code start (long end))})
                 (inc (long end))])
        [[] 0] bound))))

(defn wrap-expression-script
  "Wraps a script for W3C execute-script semantics.

   W3C execute-script runs a function BODY and only returns values from an
   explicit `return`. Playwright-style expressions (including spel's snapshot
   scripts) are written as expressions, so they need the wrapper:

     (wrap-expression-script \"document.title\")
     ;; => \"return (document.title);\"

   Scripts that return for themselves pass through — `return` is read as a
   TOKEN, so `return(x)` and `return\\n x` are that script too.

   A STATEMENT SEQUENCE is a function body, and wrapping one in `return (…)`
   used to fail with a bare `Unexpected token ';'` that named neither spel's
   wrapper nor the offending statement — so multi-statement JS was simply
   impossible through this path:

     (wrap-expression-script \"var a = 1; a + 1\")
     ;; => \"var a = 1;\\nreturn (a + 1);\"

   The rule, decided from the blanked source rather than from the raw text: a
   script is an EXPRESSION when it holds no top-level `;` other than a trailing
   one and does not open with a statement keyword. Otherwise it is a body, and
   a body that returns nothing itself gets its LAST top-level statement
   returned when that statement is an expression — a trailing `;` is
   punctuation and never decides whether the script has a value. Everything
   else passes through untouched."
  ^String [^String script]
  (let [trimmed  (str/trim (or script ""))
        {:keys [semicolons code]} (blank-script trimmed)
        segments (script-segments trimmed code semicolons)
        last-seg (last (remove #(str/blank? (:code %)) segments))
        trailing-only? (and (= 1 (count semicolons))
                         (str/blank? (subs code (inc (long (peek semicolons))))))
        expression? (and (not (statement-start? code))
                      (or (empty? semicolons) trailing-only?))]
    (cond
      (str/blank? code) "return undefined;"
      (re-find #"^return(?![\w$])" code) trimmed
      expression? (str "return (" (str/replace trimmed #";\s*$" "") ");")
      (seq (scan-returns code)) trimmed
      (and last-seg (not (statement-start? (:code last-seg))))
      (str (str/trimr (subs trimmed 0 (:start last-seg)))
        "\nreturn (" (str/trim (:text last-seg)) ");")
      :else trimmed)))

(defn evaluate
  "POST /session/{id}/execute/sync — executes JavaScript synchronously.

   `script` is treated as an expression and wrapped with `return (...)` when
   it does not already start with `return`. `args` (optional vector) is passed
   as WebDriver script arguments.

   Returns the decoded script return value."
  ([session ^String script] (evaluate session script []))
  ([session ^String script args]
   (session-request session :post (session-path session "/execute/sync")
     {"script" (wrap-expression-script script)
      "args"   (vec (or args []))})))

(defn execute-script-raw
  "POST /session/{id}/execute/sync — executes a script body VERBATIM (no
   `return` wrapping). Use for multi-statement scripts that manage their own
   `return`, and for Appium `mobile:` extension commands."
  ([session ^String script] (execute-script-raw session script []))
  ([session ^String script args]
   (session-request session :post (session-path session "/execute/sync")
     {"script" script
      "args"   (vec (or args []))})))

(defn execute-mobile
  "Executes an Appium `mobile:` extension command (e.g. \"mobile: tap\",
   \"mobile: viewportRect\") with a single argument map."
  [session ^String command args-map]
  (execute-script-raw session command [(or args-map {})]))

;; =============================================================================
;; Elements
;; =============================================================================

(def ^:private w3c-element-key
  "W3C element identifier key in element responses."
  "element-6066-11e4-a52e-4f735466cecf")

(defn extract-element-id
  "Extracts an element id from a find-element response value.
   Handles the W3C key and the legacy \"ELEMENT\" key."
  [value]
  (when (map? value)
    (or (get value w3c-element-key)
      (get value "ELEMENT"))))

(defn find-element
  "POST /session/{id}/element — finds a single element.

   `using` - String. Locator strategy (\"css selector\", \"xpath\", ...).
   `value` - String. Selector value.

   Returns the element id string.
   Throws ex-info with :webdriver/error \"no such element\" when absent."
  [session ^String using ^String value]
  (let [result (session-request session :post (session-path session "/element")
                 {"using" using "value" value})
        el-id  (extract-element-id result)]
    (when (str/blank? (str el-id))
      (throw (ex-info (str "WebDriver find-element returned no element id for "
                        using " " value)
               {:webdriver/endpoint (session-path session "/element")
                :webdriver/response result})))
    el-id))

(defn find-elements
  "POST /session/{id}/elements — returns all matching element ids."
  [session ^String using ^String value]
  (let [result (session-request session :post (session-path session "/elements")
                 {"using" using "value" value})]
    (mapv extract-element-id (or result []))))

(defn find-element-by-css
  "Finds a single element by CSS selector. Returns the element id string."
  [session ^String css]
  (find-element session "css selector" css))

(def native-role-types
  "Semantic native roles mapped to XCTest element class names."
  {"application" "XCUIElementTypeApplication"
   "button" "XCUIElementTypeButton"
   "cell" "XCUIElementTypeCell"
   "checkbox" "XCUIElementTypeCheckBox"
   "image" "XCUIElementTypeImage"
   "keyboard" "XCUIElementTypeKeyboard"
   "link" "XCUIElementTypeLink"
   "list" "XCUIElementTypeTable"
   "menu" "XCUIElementTypeMenu"
   "navigation" "XCUIElementTypeNavigationBar"
   "radio" "XCUIElementTypeRadioButton"
   "scrollbar" "XCUIElementTypeScrollBar"
   "searchbox" "XCUIElementTypeSearchField"
   "slider" "XCUIElementTypeSlider"
   "statictext" "XCUIElementTypeStaticText"
   "text" "XCUIElementTypeStaticText"
   "switch" "XCUIElementTypeSwitch"
   "tab" "XCUIElementTypeTabBar"
   "textbox" ["XCUIElementTypeTextField" "XCUIElementTypeSecureTextField"
              "XCUIElementTypeTextView"]
   "securetextbox" "XCUIElementTypeSecureTextField"
   "webview" "XCUIElementTypeWebView"})

(def native-selector-prefixes
  "Explicit selector prefixes accepted by the iOS application provider."
  {"accessibility-id" "accessibility id"
   "id"               "id"
   "xpath"            "xpath"
   "class-chain"      "-ios class chain"
   "predicate"        "-ios predicate string"})

(defn selector-strategy
  "Converts a user selector into a WebDriver locator strategy.

   In web contexts, unprefixed selectors are CSS. In NATIVE_APP, unprefixed
   selectors are accessibility identifiers. Explicit forms work in native
   context, for example `accessibility-id=Login`, `id=username`,
   `xpath=//XCUIElementTypeButton`, `class-chain=...`, and `predicate=...`."
  [selector native?]
  (let [selector       (str selector)
        [prefix value] (str/split selector #"=" 2)
        using          (get native-selector-prefixes prefix)
        role-type      (when (and native? (= "role" prefix))
                         (get native-role-types (str/lower-case (or value ""))))]
    (cond
      using                  {:using using :value (or value "")}
      (string? role-type)     {:using "class name" :value role-type}
      (sequential? role-type) {:using "-ios predicate string"
                               :value (str "type IN {"
                                        (str/join ", " (map #(str "'" % "'") role-type))
                                        "}")}
      :else                  {:using (if native? "accessibility id" "css selector")
                              :value selector})))

(defn find-element-by-selector
  "Finds an element using context-aware selector semantics."
  [session selector]
  (let [{:keys [using value]} (selector-strategy selector (native-context? session))]
    (find-element session using value)))

(defn click-element
  "POST /session/{id}/element/{element}/click — clicks a found element id."
  [session ^String element-id]
  (session-request session :post
    (session-path session (str "/element/" element-id "/click")) {}))

(defn clear-element
  "POST /session/{id}/element/{element}/clear — clears a found element id."
  [session ^String element-id]
  (session-request session :post
    (session-path session (str "/element/" element-id "/clear")) {}))

(defn send-keys
  "POST /session/{id}/element/{element}/value — types text into an element."
  [session ^String element-id ^String text]
  (session-request session :post
    (session-path session (str "/element/" element-id "/value"))
    {"text" text}))

(defn active-element
  "Returns the currently focused element id."
  [session]
  (extract-element-id
    (session-request session :get (session-path session "/element/active") nil)))

(def webdriver-key-values
  "Common WebDriver key names mapped to Unicode key values."
  {"Backspace" "\uE003" "Tab" "\uE004" "Return" "\uE006"
   "Enter" "\uE007" "Escape" "\uE00C" "Space" "\uE00D"
   "ArrowLeft" "\uE012" "ArrowUp" "\uE013" "ArrowRight" "\uE014"
   "ArrowDown" "\uE015" "Delete" "\uE017"})

(defn press-key
  "Sends a named WebDriver key to a selector or the focused element."
  ([session key] (press-key session nil key))
  ([session element-id key]
   (let [target (or element-id (active-element session))
         value  (get webdriver-key-values (str key) (str key))]
     (when (str/blank? (str target))
       (throw (ex-info "No focused element is available for keyboard input." {})))
     (send-keys session target value)
     key)))

(defn type-keys
  "Types text into the currently focused native or web element."
  [session text]
  (let [target (active-element session)]
    (when (str/blank? (str target))
      (throw (ex-info "No focused element is available for keyboard input." {})))
    (send-keys session target (str text))
    text))

(defn hide-keyboard
  "Dismisses the iOS software keyboard."
  [session]
  (execute-mobile session "mobile: hideKeyboard" {}))

(def ^:private supported-orientations
  #{:portrait :landscape})

(defn orientation
  "Returns the current device orientation as :portrait or :landscape."
  [session]
  (some-> (session-request session :get (session-path session "/orientation") nil)
    str
    str/lower-case
    keyword))

(defn set-orientation
  "Rotates the device to :portrait or :landscape and returns the resulting orientation."
  [session requested]
  (let [requested (keyword requested)]
    (when-not (contains? supported-orientations requested)
      (throw (ex-info "Orientation must be :portrait or :landscape."
               {:orientation requested
                :supported supported-orientations})))
    (session-request session :post (session-path session "/orientation")
      {"orientation" (str/upper-case (name requested))})
    (or (orientation session) requested)))

(defn element-text
  "Returns an element's rendered/accessibility text."
  [session ^String element-id]
  (session-request session :get
    (session-path session (str "/element/" element-id "/text")) nil))

(defn element-attribute
  "Returns a WebDriver/Appium element attribute."
  [session ^String element-id ^String attribute]
  (session-request session :get
    (session-path session (str "/element/" element-id "/attribute/" attribute)) nil))

(defn element-displayed?
  "Returns whether an element is displayed."
  [session ^String element-id]
  (boolean (session-request session :get
             (session-path session (str "/element/" element-id "/displayed")) nil)))

(defn element-enabled?
  "Returns whether an element is enabled."
  [session ^String element-id]
  (boolean (session-request session :get
             (session-path session (str "/element/" element-id "/enabled")) nil)))

(defn element-selected?
  "Returns whether an element is selected/checked."
  [session ^String element-id]
  (boolean (session-request session :get
             (session-path session (str "/element/" element-id "/selected")) nil)))

(defn element-rect
  "GET /session/{id}/element/{element}/rect — returns {:x :y :width :height}
   as doubles."
  [session ^String element-id]
  (let [r (session-request session :get
            (session-path session (str "/element/" element-id "/rect")) nil)]
    {:x      (double (get r "x" 0))
     :y      (double (get r "y" 0))
     :width  (double (get r "width" 0))
     :height (double (get r "height" 0))}))

;; --- Selector-level conveniences (find + act) --------------------------------

(defn click
  "Finds an element by CSS selector and clicks it via WebDriver."
  [session ^String css]
  (click-element session (find-element-by-css session css)))

(defn clear
  "Finds an element by CSS selector and clears its value."
  [session ^String css]
  (clear-element session (find-element-by-css session css)))

(defn fill
  "Finds an element by CSS selector, clears it, then types `value`."
  [session ^String css ^String value]
  (let [el (find-element-by-css session css)]
    (clear-element session el)
    (send-keys session el value)))

;; =============================================================================
;; Screenshot
;; =============================================================================

(defn screenshot
  "GET /session/{id}/screenshot — returns PNG bytes (decoded from base64)."
  ^bytes [session]
  (let [b64 (session-request session :get (session-path session "/screenshot") nil)]
    (.decode (Base64/getDecoder) ^String (str/replace (str b64) #"\s" ""))))

;; =============================================================================
;; W3C pointer actions (touch)
;; =============================================================================

(defn viewport-offset
  "Returns {:x :y} — the native-point offset of the web viewport within the
   device screen.

   W3C pointer actions on Appium's XCUITest driver ALWAYS run in the native
   context using absolute screen points, while DOM rects are web-viewport CSS
   pixels. Without this offset, taps computed from DOM coordinates land in
   Safari's chrome (status bar + URL bar) instead of the page content.

   Derived from `mobile: viewportRect` (physical pixels) divided by the
   screen scale from `mobile: deviceScreenInfo`. Falls back to {:x 0 :y 0}
   when the Appium extensions are unavailable (plain WebDriver servers,
   unit-test fakes)."
  [session]
  (let [rect (try (execute-mobile session "mobile: viewportRect" {})
               (catch Exception _ nil))]
    (if (instance? java.util.Map rect)
      (let [info  (try (execute-mobile session "mobile: deviceScreenInfo" {})
                    (catch Exception _ nil))
            scale (let [s (when (instance? java.util.Map info)
                            (.get ^java.util.Map info "scale"))]
                    (if (and (number? s) (pos? (double s))) (double s) 1.0))
            m     ^java.util.Map rect
            left  (or (.get m "left") 0)
            top   (or (.get m "top") 0)]
        {:x (long (/ (double left) scale))
         :y (long (/ (double top) scale))})
      {:x 0 :y 0})))

(def ^:private element-viewport-center-script
  "Scrolls the element into view and returns its center in web-viewport CSS
   pixels. getBoundingClientRect is viewport-relative, so this stays correct
   after scrolling — unlike the W3C element rect, which is document-relative."
  (str "arguments[0].scrollIntoView({block: 'center', inline: 'center'});\n"
    "var r = arguments[0].getBoundingClientRect();\n"
    "return {x: r.left + r.width / 2, y: r.top + r.height / 2};"))

(defn scroll-element
  "Scrolls a DOM element in a semantic direction through WebDriver script
   arguments. Returns the requested direction and amount."
  [session ^String element-id direction amount smooth?]
  (let [amount (long amount)
        [dx dy] (case (keyword direction)
                  :up [0 (- amount)]
                  :down [0 amount]
                  :left [(- amount) 0]
                  :right [amount 0]
                  [0 amount])]
    (execute-script-raw session
      (str "arguments[0].scrollBy({left: arguments[1], top: arguments[2], "
        "behavior: arguments[3] ? 'smooth' : 'auto'});")
      [{w3c-element-key element-id} dx dy (boolean smooth?)])
    {:direction (keyword direction) :amount amount}))

(defn element-viewport-center
  "Returns {:x :y} — the element's center in web-viewport CSS pixels after
   scrolling it into view. Returns nil when the script yields no usable
   coordinates (e.g. unit-test fakes)."
  [session ^String element-id]
  (let [c (execute-script-raw session element-viewport-center-script
            [{w3c-element-key element-id}])]
    (when (and (instance? java.util.Map c)
            (number? (.get ^java.util.Map c "x"))
            (number? (.get ^java.util.Map c "y")))
      {:x (long (double (.get ^java.util.Map c "x")))
       :y (long (double (.get ^java.util.Map c "y")))})))

(defn tap-actions-payload
  "Builds a W3C pointer-actions payload for a single touch tap at (x, y)."
  [x y]
  {"actions"
   [{"type"       "pointer"
     "id"         "finger1"
     "parameters" {"pointerType" "touch"}
     "actions"    [{"type" "pointerMove" "duration" 0
                    "x" (long x) "y" (long y)}
                   {"type" "pointerDown" "button" 0}
                   {"type" "pause" "duration" 100}
                   {"type" "pointerUp" "button" 0}]}]})

(defn swipe-actions-payload
  "Builds a W3C pointer-actions payload for a touch swipe.

   Params:
   `from`     - [x y] start coordinates.
   `to`       - [x y] end coordinates.
   `duration` - Long. Move duration in ms (default 800)."
  ([from to] (swipe-actions-payload from to 800))
  ([[fx fy] [tx ty] duration]
   {"actions"
    [{"type"       "pointer"
      "id"         "finger1"
      "parameters" {"pointerType" "touch"}
      "actions"    [{"type" "pointerMove" "duration" 0
                     "x" (long fx) "y" (long fy)}
                    {"type" "pointerDown" "button" 0}
                    {"type" "pointerMove" "duration" (long duration)
                     "x" (long tx) "y" (long ty)}
                    {"type" "pointerUp" "button" 0}]}]}))

(defn perform-actions
  "POST /session/{id}/actions — performs a raw W3C actions payload."
  [session payload]
  (session-request session :post (session-path session "/actions") payload))

(defn release-actions
  "DELETE /session/{id}/actions — releases all pressed inputs."
  [session]
  (session-request session :delete (session-path session "/actions") nil))

(defn- mobile-command-unsupported?
  "True when a failure means the server does not implement the Appium
   `mobile:` extension (plain WebDriver servers) — the only case where
   falling back to W3C actions is appropriate."
  [e]
  (let [d   (ex-data e)
        msg (str/lower-case (str (ex-message e) " "
                              (:webdriver/message d) " "
                              (:webdriver/error d)))]
    (or (str/includes? msg "unknown command")
      (str/includes? msg "unknown mobile command")
      (str/includes? msg "not implemented")
      (str/includes? msg "unsupported operation"))))

(defn tap-screen
  "Performs a native tap at absolute screen coordinates without applying a
   web-viewport chrome offset. Use this in NATIVE_APP context."
  [session x y]
  (let [sx (long x)
        sy (long y)]
    (try
      (execute-mobile session "mobile: tap" {"x" sx "y" sy})
      (catch clojure.lang.ExceptionInfo e
        (if (mobile-command-unsupported? e)
          (perform-actions session (tap-actions-payload sx sy))
          (throw e))))
    nil))

(defn tap
  "Performs a native tap at web-viewport coordinates (x, y).

   Coordinates are translated to absolute screen points via `viewport-offset`,
   then executed with Appium's `mobile: tap` — a genuine XCUITest tap
   gesture. W3C touch actions are deliberately NOT the primary path: WDA
   synthesizes them at the IOHID level, which fires touchstart/touchend in
   Safari but frequently fails the system tap recognizer, so no `click` is
   ever synthesized. Falls back to the W3C payload only when `mobile: tap`
   is unavailable (non-Appium WebDriver servers).

   No click-verification/retap is attempted: legitimate mobile pages
   preventDefault() the synthetic click in touch handlers, so a retap would
   double-fire their touch events. Note that iOS hit-tests the synthesized
   click ~100ms AFTER touchstart — pages whose touch handlers mutate layout
   will retarget the click exactly as they would for a real finger."
  [session x y]
  (let [{ox :x oy :y} (viewport-offset session)
        sx (+ (long x) (long ox))
        sy (+ (long y) (long oy))]
    (try
      (execute-mobile session "mobile: tap" {"x" sx "y" sy})
      (catch clojure.lang.ExceptionInfo e
        (if (mobile-command-unsupported? e)
          (perform-actions session (tap-actions-payload sx sy))
          (throw e))))
    nil))

(defn tap-element
  "Taps the center of the element matching a CSS selector using a native
   touch gesture.

   Flow: find element → scroll into view + viewport-relative center (via
   getBoundingClientRect) → translate to screen points → tap. Falls back to
   the W3C element rect when the center script yields nothing (unit-test
   fakes). Returned :x/:y are web-viewport CSS coordinates."
  [session ^String css]
  (let [el     (find-element-by-css session css)
        center (or (element-viewport-center session el)
                 (let [rect (element-rect session el)]
                   {:x (long (+ (double (:x rect)) (/ (double (:width rect)) 2.0)))
                    :y (long (+ (double (:y rect)) (/ (double (:height rect)) 2.0)))}))]
    (tap session (:x center) (:y center))
    {:element el :x (:x center) :y (:y center)}))

(defn swipe-screen
  "Performs a native touch swipe using absolute screen coordinates."
  [session {:keys [from to duration]}]
  (when-not (and from to)
    (throw (ex-info "swipe requires :from and :to coordinates" {:opts {:from from :to to}})))
  (perform-actions session (swipe-actions-payload from to (or duration 800))))

(defn swipe
  "Performs a native touch swipe.

   Params:
   `session` - WebDriverSession.
   `opts`    - Map:
     :from     - [x y] start coordinates.
     :to       - [x y] end coordinates.
     :duration - Long ms, default 800."
  [session {:keys [from to duration]}]
  (when-not (and from to)
    (throw (ex-info "swipe requires :from and :to coordinates" {:opts {:from from :to to}})))
  (let [{ox :x oy :y} (viewport-offset session)
        [fx fy] from
        [tx ty] to]
    (perform-actions session
      (swipe-actions-payload [(+ (long fx) (long ox)) (+ (long fy) (long oy))]
        [(+ (long tx) (long ox)) (+ (long ty) (long oy))]
        (or duration 800)))))
