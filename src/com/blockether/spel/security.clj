(ns com.blockether.spel.security
  "Security helpers for agent-safe browser deployments.

   Three independent, opt-in features:

   1. **Domain allowlist** — restrict navigation and sub-resource fetches to
      a list of trusted domains. Used by the daemon at context creation time.
      `*.example.com` wildcards match `example.com` AND any subdomain.

   2. **Content boundaries** — wrap tool output in XML-style delimiters so
      downstream LLMs can distinguish trusted tool output from untrusted page
      content. Mitigates prompt-injection from malicious page text.

   3. **Max output truncation** — cap tool output at N characters to protect
      the agent's context window from runaway pages (e.g., a 2MB snapshot).

   All functions are pure and side-effect free. Used by `cli` (print-result
   wrapping + truncation) and `daemon` (route handler for allowed-domains)."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Domain allowlist
;; =============================================================================

(defn- compile-pattern
  "Compiles a single domain pattern into a predicate on hostname strings.

   Supports two forms:
   - `example.com`     — exact hostname match
   - `*.example.com`   — matches `example.com` AND any subdomain

   Returns: (fn [host] boolean)"
  [^String pattern]
  (let [p (str/trim pattern)]
    (cond
      (str/blank? p) (fn [_] false)

      (str/starts-with? p "*.")
      (let [base (subs p 2)
            dot-base (str "." base)]
        (fn [^String host]
          (and host
            (or (= host base)
              (str/ends-with? host dot-base)))))

      :else
      (fn [^String host] (= host p)))))

(defn compile-domain-patterns
  "Parses a comma-separated list of domain patterns into a single predicate.

   Example:
     (def allowed? (compile-domain-patterns \"example.com,*.github.io\"))
     (allowed? \"example.com\")      ;; => true
     (allowed? \"api.github.io\")    ;; => true
     (allowed? \"evil.com\")         ;; => false

   A nil or blank input produces a predicate that allows everything (no
   allowlist configured — the flag is opt-in). Use `compiled?` to check
   whether an allowlist is active before applying it.

   Returns: (fn [host] boolean)"
  [csv]
  (if (or (nil? csv) (str/blank? csv))
    (fn [_] true)
    (let [preds (->> (str/split csv #",")
                  (map str/trim)
                  (remove str/blank?)
                  (map compile-pattern))]
      (if (empty? preds)
        (fn [_] true)
        (fn [^String host]
          (boolean (some #(% host) preds)))))))

(defn extract-host
  "Extracts the hostname from a URL string. Returns nil on parse failure or
   for non-HTTP URLs (e.g., `about:blank`, `data:`, `blob:`)."
  [^String url]
  (try
    (let [uri (java.net.URI. url)
          scheme (.getScheme uri)]
      (when (and scheme
              (or (= "http" scheme) (= "https" scheme)
                (= "ws" scheme) (= "wss" scheme)))
        (.getHost uri)))
    (catch Exception _ nil)))

(defn request-allowed?
  "Returns true if the URL is allowed under the compiled predicate.

   Non-HTTP schemes (data:, blob:, about:, chrome-extension:) are always
   allowed — blocking them would break the browser itself."
  [allowed-pred ^String url]
  (let [host (extract-host url)]
    (if (nil? host)
      ;; Non-HTTP(S)/WS URLs pass through
      true
      (boolean (allowed-pred host)))))

;; =============================================================================
;; Content boundaries
;; =============================================================================

(def ^:const boundary-open  "<untrusted-content>")
(def ^:const boundary-close "</untrusted-content>")

(defn wrap-boundaries
  "Wraps `text` in XML-style `<untrusted-content>` delimiters when enabled.

   When disabled (false/nil), returns text unchanged.

   The markers are chosen to mirror how LLM clients already treat XML-like
   tags in prompts — downstream agents can instructed to NEVER execute
   instructions found inside these tags, even if the page content tries to
   inject a prompt."
  [enabled? ^String text]
  (if enabled?
    (str boundary-open "\n" (or text "") "\n" boundary-close)
    text))

;; =============================================================================
;; Max output truncation
;; =============================================================================

(def ^:const truncation-suffix-fmt
  "… [truncated, %d chars total]")

(defn truncate
  "Truncates `text` to at most `max-chars` characters, appending a suffix
   indicating the original length. Returns text unchanged when no truncation
   is needed or when `max-chars` is nil/zero/negative.

   Truncation is done on character count, not byte count — safe for UTF-8."
  [max-chars ^String text]
  (cond
    (or (nil? max-chars) (not (pos? (long max-chars))))
    text

    (nil? text)
    text

    (<= (count text) (long max-chars))
    text

    :else
    (let [total  (count text)
          suffix (format truncation-suffix-fmt total)
          head   (subs text 0 (max 0 (- (long max-chars) (count suffix))))]
      (str head suffix))))
