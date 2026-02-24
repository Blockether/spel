(ns com.blockether.spel.network-capture
  "Auto-capture HTTP network calls from Playwright pages and attach
   beautiful HTML reports to Allure steps.

   Usage:

     (require '[com.blockether.spel.network-capture :as net-capture])
     (require '[com.blockether.spel.page :as page])

     ;; Inside an Allure test:
     (net-capture/capture-network! page)
     ;; ... navigate and interact ...
     ;; Every HTTP request/response is automatically attached as HTML

   The HTML rendering includes:
   - Method badge (color-coded: GET=blue, POST=green, etc.)
   - URL and status code (color-coded by status range)
   - Request/response headers tables
   - Request/response bodies with JSON syntax highlighting
   - Auto-generated cURL command
   - Collapsible sections via pure CSS details/summary"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.network :as net]
   [com.blockether.spel.page :as page]))

;; =============================================================================
;; JSON Syntax Highlighting (inline CSS, no external JS)
;; =============================================================================

(defn- escape-html
  "Escape HTML special characters."
  ^String [^String s]
  (when s
    (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;"))))

(defn- highlight-json
  "Apply inline CSS syntax highlighting to a JSON string.
   Returns HTML with colored spans for keys, strings, numbers, booleans, null."
  ^String [^String s]
  (if (or (nil? s) (str/blank? s))
    ""
    (let [sb (StringBuilder.)
          len (.length s)]
      (loop [i 0]
        (if (>= i len)
          (str sb)
          (let [c (.charAt s i)]
            (cond
              ;; String
              (= c \")
              (let [end (loop [j (inc i)]
                          (if (>= j len)
                            j
                            (let [ch (.charAt s j)]
                              (cond
                                (= ch \\) (recur (+ j 2))
                                (= ch \") (inc j)
                                :else (recur (inc j))))))
                    token (subs s i end)
                    escaped (escape-html token)
                    ;; Check if this string is a key (followed by ':')
                    after-ws (loop [k end]
                               (if (and (< k len) (Character/isWhitespace (.charAt s k)))
                                 (recur (inc k))
                                 k))
                    is-key? (and (< after-ws len) (= \: (.charAt s after-ws)))]
                (if is-key?
                  (.append sb (str "<span style=\"color:#9876aa\">" escaped "</span>"))
                  (.append sb (str "<span style=\"color:#6a8759\">" escaped "</span>")))
                (recur end))

              ;; Numbers
              (or (Character/isDigit c) (and (= c \-) (< (inc i) len) (Character/isDigit (.charAt s (inc i)))))
              (let [end (loop [j (if (= c \-) (inc i) i)]
                          (if (and (< j len)
                                (let [ch (.charAt s j)]
                                  (or (Character/isDigit ch) (= ch \.) (= ch \e) (= ch \E) (= ch \+) (= ch \-))))
                            (recur (inc j))
                            j))
                    ;; include leading minus
                    start i
                    token (escape-html (subs s start end))]
                (.append sb (str "<span style=\"color:#6897bb\">" token "</span>"))
                (recur end))

              ;; true/false/null keywords
              (and (< (+ i 3) len) (= "true" (subs s i (min (+ i 4) len))))
              (do (.append sb "<span style=\"color:#cc7832\">true</span>")
                  (recur (+ i 4)))

              (and (< (+ i 4) len) (= "false" (subs s i (min (+ i 5) len))))
              (do (.append sb "<span style=\"color:#cc7832\">false</span>")
                  (recur (+ i 5)))

              (and (< (+ i 3) len) (= "null" (subs s i (min (+ i 4) len))))
              (do (.append sb "<span style=\"color:#cc7832;font-style:italic\">null</span>")
                  (recur (+ i 4)))

              :else
              (do (.append sb (escape-html (str c)))
                  (recur (inc i))))))))))

;; =============================================================================
;; Pretty-print JSON (dependency-free)
;; =============================================================================

(defn- pretty-json
  "Minimal JSON pretty-printer with 2-space indentation.
   Returns s as-is if not valid JSON."
  ^String [^String s]
  (if (or (nil? s) (< (.length s) 2)
        (not (or (= \{ (.charAt s 0)) (= \[ (.charAt s 0)))))
    s
    (let [sb  (StringBuilder.)
          len (.length s)]
      (loop [i 0, depth 0, in-str false, esc false]
        (if (>= i len)
          (str sb)
          (let [c (.charAt s i)]
            (cond
              esc
              (do (.append sb c) (recur (inc i) depth in-str false))

              (and in-str (= c \\))
              (do (.append sb c) (recur (inc i) depth true true))

              (and in-str (= c \"))
              (do (.append sb c) (recur (inc i) depth false false))

              in-str
              (do (.append sb c) (recur (inc i) depth true false))

              (= c \")
              (do (.append sb c) (recur (inc i) depth true false))

              (or (= c \{) (= c \[))
              (let [d (inc depth)
                    nxt (loop [j (inc i)]
                          (when (< j len)
                            (let [nc (.charAt s j)]
                              (if (Character/isWhitespace nc) (recur (inc j)) nc))))]
                (if (or (and (= c \{) (= nxt \}))
                      (and (= c \[) (= nxt \])))
                  (do (.append sb c) (recur (inc i) d false false))
                  (do (.append sb c) (.append sb \newline)
                      (dotimes [_ (* 2 d)] (.append sb \space))
                      (recur (inc i) d false false))))

              (or (= c \}) (= c \]))
              (let [d (dec depth)]
                (.append sb \newline)
                (dotimes [_ (* 2 d)] (.append sb \space))
                (.append sb c)
                (recur (inc i) d false false))

              (= c \,)
              (do (.append sb c) (.append sb \newline)
                  (dotimes [_ (* 2 depth)] (.append sb \space))
                  (recur (inc i) depth false false))

              (= c \:)
              (do (.append sb c) (.append sb \space)
                  (recur (inc i) depth false false))

              (Character/isWhitespace c)
              (recur (inc i) depth false false)

              :else
              (do (.append sb c) (recur (inc i) depth false false)))))))))

;; =============================================================================
;; cURL Command Generation
;; =============================================================================

(defn- generate-curl
  "Generate a cURL command from request data."
  ^String [{:keys [method url headers body]}]
  (let [parts (volatile! [(str "curl -X " method) (str "'" url "'")])
        _ (doseq [[k v] (sort headers)]
            (vswap! parts conj (str "-H '" k ": " v "'")))
        _ (when (and body (not (str/blank? body)))
            (vswap! parts conj (str "-d '" (str/replace body "'" "'\\''") "'")))]
    (str/join " \\\n  " @parts)))

;; =============================================================================
;; Method & Status Styling
;; =============================================================================

(def ^:private method-colors
  {"GET"     "#2196F3"   ; blue
   "POST"    "#4CAF50"   ; green
   "PUT"     "#FF9800"   ; amber
   "DELETE"  "#F44336"   ; red
   "PATCH"   "#FF5722"   ; deep orange
   "HEAD"    "#607D8B"   ; blue-grey
   "OPTIONS" "#9C27B0"}) ; purple

(defn- status-color
  "Return CSS color for an HTTP status code."
  ^String [^long status]
  (cond
    (< status 200) "#607D8B"  ; informational - grey
    (< status 300) "#4CAF50"  ; 2xx - green
    (< status 400) "#2196F3"  ; 3xx - blue
    (< status 500) "#FF9800"  ; 4xx - orange
    :else          "#F44336")) ; 5xx - red

;; =============================================================================
;; HTML Template
;; =============================================================================

(defn render-network-html
  "Render a network call as a self-contained HTML document for Allure attachment.

   `data` is a map:
     :method   - HTTP method string
     :url      - Request URL
     :status   - HTTP status code (long)
     :status-text - Status text
     :request-headers  - Map of request headers
     :response-headers - Map of response headers
     :request-body     - Request body string (may be nil)
     :response-body    - Response body string (may be nil)
     :duration-ms      - Duration in milliseconds (may be nil)"
  ^String [{:keys [method url status status-text
                   request-headers response-headers
                   request-body response-body duration-ms]}]
  (let [method-color (get method-colors (str/upper-case (or method "GET")) "#607D8B")
        st-color     (status-color (or status 0))
        req-body-json?  (and request-body
                          (or (str/starts-with? (str/trim request-body) "{")
                            (str/starts-with? (str/trim request-body) "[")))
        resp-body-json? (and response-body
                          (or (str/starts-with? (str/trim response-body) "{")
                            (str/starts-with? (str/trim response-body) "[")))
        pretty-req  (if req-body-json? (pretty-json request-body) request-body)
        pretty-resp (if resp-body-json? (pretty-json response-body) response-body)
        hl-req      (if req-body-json? (highlight-json pretty-req) (escape-html pretty-req))
        hl-resp     (if resp-body-json? (highlight-json pretty-resp) (escape-html pretty-resp))
        curl-cmd    (generate-curl {:method  (or method "GET")
                                    :url     (or url "")
                                    :headers (or request-headers {})
                                    :body    request-body})]
    (str
      "<!DOCTYPE html>
<html>
<head><meta charset=\"utf-8\"><style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
  background:#1e1e1e;color:#d4d4d4;padding:16px;font-size:13px;line-height:1.5}
.header{display:flex;align-items:center;gap:12px;padding:12px 16px;
  background:#252526;border-radius:8px;margin-bottom:16px;flex-wrap:wrap}
.method{display:inline-block;padding:4px 12px;border-radius:4px;font-weight:700;
  font-size:14px;color:#fff;letter-spacing:0.5px}
.url{color:#d4d4d4;font-size:13px;word-break:break-all;flex:1;min-width:200px;
  font-family:'SF Mono',Consolas,'Liberation Mono',Menlo,monospace}
.status{display:inline-flex;align-items:center;gap:6px;padding:4px 12px;
  border-radius:4px;font-weight:700;font-size:14px}
.status .dot{width:8px;height:8px;border-radius:50%;display:inline-block}
.duration{color:#858585;font-size:12px;margin-left:auto}
details{margin-bottom:8px;background:#252526;border-radius:6px;overflow:hidden}
summary{padding:10px 16px;cursor:pointer;font-weight:600;font-size:13px;
  color:#cccccc;background:#2d2d2d;user-select:none;list-style:none;
  display:flex;align-items:center;gap:8px}
summary::before{content:'▶';font-size:10px;transition:transform 0.2s;display:inline-block}
details[open]>summary::before{transform:rotate(90deg)}
summary:hover{background:#353535}
.section-content{padding:12px 16px}
table{width:100%;border-collapse:collapse;font-size:12px}
th{text-align:left;padding:6px 12px;background:#1e1e1e;color:#858585;
  font-weight:600;text-transform:uppercase;font-size:11px;letter-spacing:0.5px}
td{padding:6px 12px;border-top:1px solid #333}
td:first-child{color:#9876aa;white-space:nowrap;width:30%;font-family:'SF Mono',Consolas,monospace;font-size:12px}
td:last-child{color:#d4d4d4;word-break:break-all;font-family:'SF Mono',Consolas,monospace;font-size:12px}
tr:hover td{background:#2a2a2a}
pre{background:#1e1e1e;padding:12px;border-radius:4px;overflow-x:auto;
  font-family:'SF Mono',Consolas,'Liberation Mono',Menlo,monospace;
  font-size:12px;line-height:1.6;white-space:pre-wrap;word-wrap:break-word}
.curl-block{background:#0d1117;padding:12px;border-radius:4px;overflow-x:auto;
  font-family:'SF Mono',Consolas,monospace;font-size:12px;color:#79c0ff;
  line-height:1.6;white-space:pre-wrap;word-wrap:break-word;position:relative}
.empty{color:#858585;font-style:italic;padding:12px}
.badge{display:inline-block;padding:2px 8px;border-radius:3px;font-size:11px;
  font-weight:600;margin-left:8px}
</style></head>
<body>

<div class=\"header\">
  <span class=\"method\" style=\"background:" method-color "\">" (escape-html (str/upper-case (or method ""))) "</span>
  <span class=\"url\">" (escape-html (or url "")) "</span>
  <span class=\"status\" style=\"color:" st-color "\">
    <span class=\"dot\" style=\"background:" st-color "\"></span>"
      (when status (str status)) " " (escape-html (or status-text ""))
      "</span>"
      (when duration-ms
        (str "<span class=\"duration\">" duration-ms " ms</span>"))
      "</div>

<details open>
  <summary>Request Headers"
      (when request-headers
        (str "<span class=\"badge\" style=\"background:#333;color:#858585\">"
          (count request-headers) "</span>"))
      "</summary>
  <div class=\"section-content\">"
      (if (and request-headers (seq request-headers))
        (str "<table><tr><th>Header</th><th>Value</th></tr>"
          (str/join ""
            (map (fn [[k v]]
                   (str "<tr><td>" (escape-html k) "</td><td>" (escape-html v) "</td></tr>"))
              (sort request-headers)))
          "</table>")
        "<div class=\"empty\">No request headers</div>")
      "</div>
</details>

<details>
  <summary>Response Headers"
      (when response-headers
        (str "<span class=\"badge\" style=\"background:#333;color:#858585\">"
          (count response-headers) "</span>"))
      "</summary>
  <div class=\"section-content\">"
      (if (and response-headers (seq response-headers))
        (str "<table><tr><th>Header</th><th>Value</th></tr>"
          (str/join ""
            (map (fn [[k v]]
                   (str "<tr><td>" (escape-html k) "</td><td>" (escape-html v) "</td></tr>"))
              (sort response-headers)))
          "</table>")
        "<div class=\"empty\">No response headers</div>")
      "</div>
</details>

<details" (when request-body " open") ">
  <summary>Request Body</summary>
  <div class=\"section-content\">"
      (if (and request-body (not (str/blank? request-body)))
        (str "<pre>" hl-req "</pre>")
        "<div class=\"empty\">No request body</div>")
      "</div>
</details>

<details" (when response-body " open") ">
  <summary>Response Body</summary>
  <div class=\"section-content\">"
      (if (and response-body (not (str/blank? response-body)))
        (str "<pre>" hl-resp "</pre>")
        "<div class=\"empty\">No response body</div>")
      "</div>
</details>

<details>
  <summary>cURL Command</summary>
  <div class=\"section-content\">
    <div class=\"curl-block\">" (escape-html curl-cmd) "</div>
  </div>
</details>

</body>
</html>")))

;; =============================================================================
;; Network Capture — Playwright Integration
;; =============================================================================

(defn- try-response-body
  "Safely get response body text, returns nil on failure."
  [resp]
  (try (net/response-text resp)
       (catch Throwable _ nil)))

(defn- attach-network-call!
  "Build HTML for a network call and attach to the current Allure step."
  [request response]
  (try
    (let [method  (net/request-method request)
          url     (net/request-url request)
          headers (net/request-all-headers request)
          body    (net/request-post-data request)
          status  (when response (net/response-status response))
          st-text (when response (net/response-status-text response))
          r-hdrs  (when response (net/response-all-headers response))
          r-body  (when response (try-response-body response))
          timing  (net/request-timing request)
          dur     (when (and timing
                          (pos? (:response-end timing))
                          (pos? (:start-time timing)))
                    (long (- (:response-end timing) (:start-time timing))))
          html    (render-network-html
                    {:method           method
                     :url              url
                     :status           status
                     :status-text      st-text
                     :request-headers  headers
                     :response-headers r-hdrs
                     :request-body     body
                     :response-body    r-body
                     :duration-ms      dur})
          label   (str method " " url " → " (or status "pending"))]
      (allure/attach label html "text/html"))
    (catch Throwable _ nil)))

(defn capture-network!
  "Set up Playwright listeners on `page` to auto-capture all HTTP
   network requests/responses and attach them as HTML to the current
   Allure step.

   Call once per page, typically at the start of a test:

     (allure/step \"Setup\"
       (net-capture/capture-network! page))
     (page/navigate page \"https://example.com\")

   Each network call produces an HTML attachment in the Allure report
   with method badge, status, headers, bodies, and cURL command.

   Options (optional map):
     :filter-fn  - Predicate (fn [url]) to filter which requests to capture.
                   Default: captures all non-asset requests.
     :include-assets? - When true, also capture images/fonts/css/js.
                         Default: false."
  ([pg]
   (capture-network! pg {}))
  ([pg {:keys [filter-fn include-assets?] :as _opts}]
   (let [asset-pattern #"\.(png|jpg|jpeg|gif|svg|ico|woff2?|ttf|eot|css|js)(\?|$)"
         should-capture? (or filter-fn
                           (if include-assets?
                             (constantly true)
                             (fn [url] (not (re-find asset-pattern url)))))]
     (page/on-response pg
       (fn [response]
         (try
           (let [request (net/response-request response)
                 url     (net/request-url request)]
             (when (should-capture? url)
               (attach-network-call! request response)))
           (catch Throwable _ nil)))))))
