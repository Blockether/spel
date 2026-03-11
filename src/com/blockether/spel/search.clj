(ns com.blockether.spel.search
  "Google Search automation using Playwright.

   Provides functions for searching Google, extracting results, handling
   pagination, and supporting web, image, and news search types — all
   without any API key. Uses Playwright browser automation to navigate
   Google Search and extract structured data from the results pages.

   Library functions take a Page as the first argument.
   The -main entry point provides a standalone CLI tool.

   Usage (library):
     (search! page \"clojure programming\")
     (search! page \"cats\" {:type :images})
     (next-page! page)
     (search-and-collect! page \"clojure\" {:max-pages 3})

   Usage (CLI):
     spel search \"clojure programming\"
     spel search \"cats\" --images
     spel search \"news topic\" --news --json
     spel search \"query\" --page 2 --num 20"
  (:require
   [charred.api :as json]
   [clojure.string :as str]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.driver :as driver]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.markdown :as markdown]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.stealth :as stealth])
  (:import
   [com.microsoft.playwright BrowserContext Page]
   [com.microsoft.playwright.options AriaRole Cookie]
   [java.net URLDecoder URLEncoder]
   [java.nio.charset StandardCharsets]
   [java.util Collections Random]))

;; =============================================================================
;; URL Building
;; =============================================================================

(defn search-url
  "Builds a Google Search URL from a query string and options.

   Params:
   `query` - String. The search query.
   `opts`  - Map, optional. Search options:
             :type       - Keyword. :web (default), :images, :news.
             :page       - Long. 1-based page number (default 1).
             :num        - Long. Results per page, 10-100 (default 10).
             :lang       - String. 2-letter language code (e.g. \"en\").
             :safe       - Keyword. :off (default), :medium, :high.
             :time-range - Keyword. :day, :week, :month, :year.

   Returns:
   String. Fully-formed Google Search URL."
  (^String [^String query]
   (search-url query {}))
  (^String [^String query opts]
   (let [encoded-q  (URLEncoder/encode query ^java.nio.charset.Charset StandardCharsets/UTF_8)
         search-type (:type opts :web)
         page-num   (long (or (:page opts) 1))
         num        (long (or (:num opts) 10))
         lang       (:lang opts)
         safe-mode  (:safe opts)
         time-range (:time-range opts)
         start      (when (> page-num 1) (* (dec page-num) num))
         params     (cond-> [(str "q=" encoded-q)]
                      (= :images search-type)
                      (conj "tbm=isch")
                      (= :news search-type)
                      (conj "tbm=nws")
                      start
                      (conj (str "start=" start))
                      (not= num 10)
                      (conj (str "num=" num))
                      lang
                      (conj (str "hl=" lang))
                      (= :medium safe-mode)
                      (conj "safe=medium")
                      (= :high safe-mode)
                      (conj "safe=high")
                      (= :day time-range)
                      (conj "tbs=qdr:d")
                      (= :week time-range)
                      (conj "tbs=qdr:w")
                      (= :month time-range)
                      (conj "tbs=qdr:m")
                      (= :year time-range)
                      (conj "tbs=qdr:y")
                      ;; Anti-detection: disable personalization & filtering
                      true
                      (conj "pws=0" "filter=0"))]
     (str "https://www.google.com/search?" (str/join "&" params)))))

;; =============================================================================
;; User-Agent Rotation
;; =============================================================================

(def ^:private user-agents
  "Pool of realistic Chrome user-agent strings for rotation."
  ["Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
   "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
   "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
   "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"])

(def ^:private rng (delay (Random.)))

(defn- random-user-agent
  "Picks a random user-agent from the pool."
  ^String []
  (nth user-agents (.nextInt ^Random @rng (count user-agents))))

(defn- random-delay!
  "Sleeps for a random duration between min-ms and max-ms."
  [^long min-ms ^long max-ms]
  (let [delay (+ min-ms (.nextInt ^Random @rng (- max-ms min-ms)))]
    (Thread/sleep delay)))

;; =============================================================================
;; Anti-Detection Headers
;; =============================================================================

(defn- extract-chrome-version
  "Extracts the Chrome major version number from a user-agent string.
   Returns nil if not found."
  [^String ua]
  (when-let [m (re-find #"Chrome/(\d+)" ua)]
    (Long/parseLong (second m))))

(defn- sec-ch-ua-for
  "Generates sec-ch-ua Client Hints header matching the given user-agent.
   Google uses these to cross-check browser identity — mismatches trigger detection."
  ^String [^String ua]
  (let [v (or (extract-chrome-version ua) 131)]
    (str "\"Google Chrome\";v=\"" v "\", \"Chromium\";v=\"" v "\", \"Not_A Brand\";v=\"24\"")))

(defn- detect-platform
  "Detects the platform from a user-agent string for sec-ch-ua-platform header."
  ^String [^String ua]
  (cond
    (str/includes? ua "Windows")   "\"Windows\""
    (str/includes? ua "Macintosh") "\"macOS\""
    :else                          "\"Linux\""))

(defn anti-detection-headers
  "Returns a map of HTTP headers that reduce Google bot detection.
   Should be used as :extra-http-headers in context options.

   Includes sec-ch-ua Client Hints that match the user-agent,
   Accept-Language, DNT, and Upgrade-Insecure-Requests.

   Params:
   `ua` - String. The user-agent string to match headers against.

   Returns:
   Map of String->String."
  [^String ua]
  {"Accept-Language"           "en-US,en;q=0.9"
   "DNT"                       "1"
   "Upgrade-Insecure-Requests" "1"
   "sec-ch-ua"                 (sec-ch-ua-for ua)
   "sec-ch-ua-mobile"          "?0"
   "sec-ch-ua-platform"        (detect-platform ua)})

(defn- set-consent-cookie!
  "Sets CONSENT=YES+ cookie on the browser context to pre-approve Google's
   GDPR cookie consent. Prevents the consent dialog from appearing.

   Params:
   `ctx` - BrowserContext instance."
  [^BrowserContext ctx]
  (let [cookie (doto (Cookie. "CONSENT" "YES+")
                 (.setDomain ".google.com")
                 (.setPath "/"))]
    (.addCookies ctx (Collections/singletonList cookie))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

;; =============================================================================
;; Consent Handling
;; =============================================================================

(defn- dismiss-consent!
  "Dismisses Google's cookie consent dialog if present.

   Handles EU GDPR consent dialogs by clicking the accept/reject button.
   Tries multiple selectors including Google's known button IDs.
   Uses a short timeout so it does not block if no consent dialog appears.

   Params:
   `page` - Page instance.

   Returns:
   nil."
  [^Page page]
  (let [try-click (fn [btn-name]
                    (try
                      (let [btn (page/get-by-role page AriaRole/BUTTON {:name btn-name})]
                        (when (pos? (locator/count-elements btn))
                          (locator/click btn {:timeout 3000})
                          true))
                      (catch Exception _ false)))
        try-css   (fn [selector]
                    (try
                      (let [el (page/locator page selector)]
                        (when (pos? (locator/count-elements el))
                          (locator/click el {:timeout 3000})
                          true))
                      (catch Exception _ false)))]
    (or (try-click "Accept all")
      (try-click "Reject all")
      (try-click "I agree")
      (try-css "#L2AGLb")
      (try-css ".QS5gu")))
  nil)

;; =============================================================================
;; Wait Helpers
;; =============================================================================

(def ^:private detect-block-js
  "JavaScript that detects Google CAPTCHA/block pages.
   Returns a string describing the block type, or empty string if no block."
  (str "() => {"
    "  if (window.location.pathname.startsWith('/sorry')) return 'sorry';"
    "  if (document.querySelector('#captcha, #recaptcha, .g-recaptcha')) return 'captcha';"
    "  if (document.querySelector('form[action*=\"/sorry/\"]')) return 'sorry-form';"
    "  var title = document.title.toLowerCase();"
    "  if (title.includes('unusual traffic') || title.includes('not a robot')) return 'unusual-traffic';"
    "  return '';"
    "}"))

(defn- detect-block!
  "Detects if Google has blocked the request (CAPTCHA, sorry page, etc.).

   Params:
   `page` - Page instance.

   Returns:
   Keyword (:sorry, :captcha, :sorry-form, :unusual-traffic) or nil if not blocked."
  [^Page page]
  (try
    (let [result (page/evaluate page detect-block-js)]
      (when (and (string? result) (not (str/blank? result)))
        (keyword result)))
    (catch Exception _ nil)))

(defn- print-diagnostics!
  "Prints diagnostic information to stderr when search fails.

   Params:
   `page`       - Page instance.
   `block-type` - Keyword or nil. The detected block type."
  [^Page page block-type]
  (binding [*out* *err*]
    (println "Diagnostics:")
    (println (str "  URL:   " (page/url page)))
    (println (str "  Title: " (page/title page)))
    (when block-type
      (println (str "  Block: " (name block-type))))))

(defn- wait-for-results!
  "Waits for search results to render using Playwright's wait-for-function.
   Uses a 10-second timeout so callers can fall back gracefully."
  [^Page page search-type]
  (try
    (let [js (case search-type
               :images "() => document.querySelectorAll('#islrg img, div[data-id] img').length > 0"
               :news   "() => document.querySelectorAll('div.SoaBEf, #rso h3').length > 0"
               ;; :web default
               "() => document.querySelectorAll('#rso h3').length > 0")]
      (page/wait-for-function page js {:timeout 10000}))
    (catch Exception _)))

;; =============================================================================
;; Warmup
;; =============================================================================

(defn- warmup!
  "Visits Google homepage to establish cookies and bypass bot detection.
   Should be called before search! when using stealth mode.

   Params:
   `page` - Page instance.
   `opts` - Map, optional. {:lang \"en\"} for language.

   Returns:
   nil."
  ([^Page page] (warmup! page {}))
  ([^Page page opts]
   (let [lang (or (:lang opts) "en")
         url  (str "https://www.google.com/?hl=" lang)]
     (page/navigate page url)
     (page/wait-for-load-state page :domcontentloaded)
     (dismiss-consent! page)
     ;; Randomized delay to appear more human (800-2000ms)
     (random-delay! 800 2000)
     nil)))

;; =============================================================================
;; Result Extraction (JavaScript evaluation)
;; =============================================================================

(def ^:private ^String web-results-js
  "() => {
  const results = [];
  const seen = new Set();
  const h3s = document.querySelectorAll('#rso h3');
  for (const h3 of h3s) {
    const a = h3.closest('a');
    if (!a || !a.href || seen.has(a.href)) continue;
    seen.add(a.href);
    const card = h3.closest('div.tF2Cxc') || h3.closest('div.MjjYud') || h3.closest('div[data-hveid]');
    let snippet = null;
    if (card) {
      const snEl = card.querySelector('[data-sncf], [data-snf], div.VwiC3b, span.aCOpRe');
      if (snEl) snippet = snEl.textContent.trim();
      if (!snippet) {
        for (const el of card.querySelectorAll('div, span')) {
          if (el.querySelector('h3') || el.querySelector('cite')) continue;
          const t = el.textContent.trim();
          if (t.length > 50 && t !== h3.textContent.trim()) { snippet = t; break; }
        }
      }
    }
    results.push({title: h3.textContent.trim(), url: a.href, snippet: snippet || null});
  }
  return results;
}")

(def ^:private ^String image-results-js
  "() => {
  const results = [];
  const imgs = document.querySelectorAll('div[data-id] img, #islrg img');
  const seen = new Set();
  for (const img of imgs) {
    const src = img.src || img.getAttribute('data-src');
    if (!src || src.startsWith('data:image/gif') || seen.has(src)) continue;
    seen.add(src);
    const a = img.closest('a');
    const href = a ? a.href : null;
    const alt = img.alt || '';
    results.push({title: alt, thumbnailUrl: src, sourceUrl: href});
  }
  return results;
}")

(def ^:private ^String news-results-js
  "() => {
  const results = [];
  const cards = document.querySelectorAll('div.SoaBEf');
  if (cards.length > 0) {
    for (const card of cards) {
      const a = card.querySelector('a');
      const heading = card.querySelector(\"div[role='heading']\");
      const srcEl = card.querySelector('.CEMjEf, .NUnG9d');
      const timeEl = card.querySelector('.OSrXXb, time, span[aria-label]');
      const snipEl = card.querySelector('.GI74Re, .Y3v8qd');
      if (heading) {
        results.push({
          title: heading.textContent.trim(),
          url: a ? a.href : null,
          source: srcEl ? srcEl.textContent.trim() : null,
          time: timeEl ? timeEl.textContent.trim() : null,
          snippet: snipEl ? snipEl.textContent.trim() : null
        });
      }
    }
  } else {
    const h3s = document.querySelectorAll('#rso h3');
    for (const h3 of h3s) {
      const a = h3.closest('a');
      results.push({
        title: h3.textContent.trim(),
        url: a ? a.href : null,
        source: null, time: null, snippet: null
      });
    }
  }
  return results;
}")

(def ^:private ^String people-also-ask-js
  "() => {
  const qs = [];
  document.querySelectorAll('div[jsname=Cpkphb] span, div.related-question-pair span, [data-q]').forEach(el => {
    const t = el.getAttribute ? (el.getAttribute('data-q') || el.textContent.trim()) : el.textContent.trim();
    if (t && t.length > 10 && !qs.includes(t)) qs.push(t);
  });
  return qs;
}")

(def ^:private ^String related-searches-js
  "() => {
  const rs = new Set();
  document.querySelectorAll('#brs a, a.k8XOCe, div.s75CSd a, #botstuff a').forEach(el => {
    const t = el.textContent.trim();
    if (t.length > 3 && t.length < 80 && !t.includes('Next') && !t.includes('Previous')) rs.add(t);
  });
  return [...rs];
}")

(def ^:private ^String pagination-js
  "() => {
  const next = document.querySelector('#pnnext, a[aria-label=\"Next\"], td.d6cvqb a[href]');
  return {hasNext: !!next, href: next ? next.href : null};
}")

(def ^:private ^String result-stats-js
  "() => {
  const el = document.querySelector('#result-stats');
  return el ? el.textContent.trim() : null;
}")

(defn extract-result-stats
  "Extracts the result statistics text (e.g. 'About 1,234 results').

   Params:
   `page` - Page instance.

   Returns:
   String or nil."
  [^Page page]
  (try
    (page/evaluate page result-stats-js)
    (catch Exception _ nil)))

(defn extract-web-results
  "Extracts web search results from the current Google results page.
   Uses JavaScript evaluation for resilient extraction that survives
   Google DOM class changes.

   Params:
   `page` - Page instance.

   Returns:
   Vector of maps with :title, :url, :snippet, :position."
  [^Page page]
  (try
    (let [raw (page/evaluate page web-results-js)]
      (vec
        (map-indexed
          (fn [idx r]
            {:title    (get r "title")
             :url      (get r "url")
             :snippet  (get r "snippet")
             :position (inc (long idx))})
          raw)))
    (catch Exception _ [])))

(defn extract-image-results
  "Extracts image search results from the current Google Images page.
   Uses JavaScript evaluation for resilient extraction.

   Params:
   `page` - Page instance.

   Returns:
   Vector of maps with :title, :thumbnail-url, :source-url, :position."
  [^Page page]
  (try
    (let [raw (page/evaluate page image-results-js)]
      (vec
        (map-indexed
          (fn [idx r]
            {:title         (or (get r "title") "")
             :thumbnail-url (get r "thumbnailUrl")
             :source-url    (get r "sourceUrl")
             :position      (inc (long idx))})
          raw)))
    (catch Exception _ [])))

(defn extract-news-results
  "Extracts news search results from the current Google News results page.
   Uses JavaScript evaluation for resilient extraction.

   Params:
   `page` - Page instance.

   Returns:
   Vector of maps with :title, :url, :source, :time, :snippet, :position."
  [^Page page]
  (try
    (let [raw (page/evaluate page news-results-js)]
      (vec
        (map-indexed
          (fn [idx r]
            {:title    (get r "title")
             :url      (get r "url")
             :source   (get r "source")
             :time     (get r "time")
             :snippet  (get r "snippet")
             :position (inc (long idx))})
          raw)))
    (catch Exception _ [])))

(defn extract-people-also-ask
  "Extracts 'People also ask' questions from the results page.
   Uses JavaScript evaluation for resilient extraction.

   Params:
   `page` - Page instance.

   Returns:
   Vector of question strings."
  [^Page page]
  (try
    (vec (page/evaluate page people-also-ask-js))
    (catch Exception _ [])))

(defn extract-related-searches
  "Extracts related search suggestions from the bottom of the results page.
   Uses JavaScript evaluation for resilient extraction.

   Params:
   `page` - Page instance.

   Returns:
   Vector of suggestion strings."
  [^Page page]
  (try
    (vec (page/evaluate page related-searches-js))
    (catch Exception _ [])))

;; =============================================================================
;; Pagination
;; =============================================================================

(defn has-next-page?
  "Returns true if there is a next page of results.
   Uses JavaScript evaluation for resilient detection.

   Params:
   `page` - Page instance.

   Returns:
   Boolean."
  [^Page page]
  (try
    (let [result (page/evaluate page pagination-js)]
      (boolean (get result "hasNext")))
    (catch Exception _ false)))

(defn next-page!
  "Navigates to the next page of results and waits for them to load.
   Uses the next-page URL from JavaScript evaluation instead of clicking,
   which is more reliable across Google DOM changes.

   Returns the new page's web results, or nil if no next page.

   Params:
   `page` - Page instance.

   Returns:
   Vector of result maps, or nil."
  [^Page page]
  (try
    (let [result (page/evaluate page pagination-js)]
      (when (get result "hasNext")
        (let [href (get result "href")]
          (when href
            (page/navigate page href)
            (page/wait-for-load-state page :networkidle)
            (wait-for-results! page :web)
            (dismiss-consent! page)
            (extract-web-results page)))))
    (catch Exception _ nil)))

(defn go-to-page!
  "Navigates directly to a specific page of search results using URL parameters.

   Params:
   `page`     - Page instance.
   `query`    - String. The search query.
   `page-num` - Long. 1-based page number.
   `opts`     - Map, optional. Same options as search-url.

   Returns:
   Vector of result maps."
  ([^Page page ^String query page-num]
   (go-to-page! page query page-num {}))
  ([^Page page ^String query page-num opts]
   (let [url         (search-url query (assoc opts :page page-num))
         search-type (:type opts :web)]
     (page/navigate page url)
     (page/wait-for-load-state page :domcontentloaded)
     (wait-for-results! page search-type)
     (dismiss-consent! page)
     (case search-type
       :images (extract-image-results page)
       :news   (extract-news-results page)
       (extract-web-results page)))))

;; =============================================================================
;; Search Execution
;; =============================================================================

(defn search!
  "Navigates to Google Search and returns structured results.

   Automatically warms up by visiting google.com first if the page is
   not already on a Google domain (required for stealth/bot bypass).
   Uses domcontentloaded + wait-for-function for reliable result rendering.

   Params:
   `page`  - Page instance.
   `query` - String. The search query.
   `opts`  - Map, optional. Search options:
             :type       - Keyword. :web (default), :images, :news.
             :page       - Long. Page number (default 1).
             :num        - Long. Results per page (default 10).
             :lang       - String. Language code.
             :safe       - Keyword. :off, :medium, :high.
             :time-range - Keyword. :day, :week, :month, :year.

   Returns:
   Map with :query, :type, :stats, :results, :has-next?, :page,
   :people-also-ask, :related-searches."
  ([^Page page ^String query]
   (search! page query {}))
  ([^Page page ^String query opts]
   (let [url         (search-url query opts)
         search-type (:type opts :web)
         page-num    (long (or (:page opts) 1))]
     ;; Warmup: visit google.com first if not already there
     (when-not (str/starts-with? (or (page/url page) "") "https://www.google.com")
       (warmup! page opts))
     (page/navigate page url)
     (page/wait-for-load-state page :domcontentloaded)
     (wait-for-results! page search-type)
     (dismiss-consent! page)
     (let [results (case search-type
                     :images (extract-image-results page)
                     :news   (extract-news-results page)
                     (extract-web-results page))
           stats   (extract-result-stats page)]
       (cond-> {:query    query
                :type     search-type
                :stats    stats
                :results  results
                :has-next? (has-next-page? page)
                :page     page-num}
         (= :web search-type)
         (assoc :people-also-ask  (extract-people-also-ask page)
           :related-searches (extract-related-searches page)))))))

;; =============================================================================
;; Retry Logic
;; =============================================================================

(def ^:private max-retries
  "Maximum number of search retries on empty results."
  3)

(defn- retry-search!
  "Retries search! up to max-retries times with exponential backoff.
   If Google actively blocks the request (sorry page, CAPTCHA, etc.),
   returns immediately without retrying — retries only help with transient
   empty results, not hard blocks from datacenter IPs.

   Returns the first result with non-empty :results, or the last result.

   Params:
   `page`    - Page instance.
   `query`   - String.
   `opts`    - Search options map.
   `attempt` - Current attempt (1-based)."
  [^Page page ^String query opts ^long attempt]
  (let [result (search! page query opts)]
    (if (empty? (:results result))
      (let [block (detect-block! page)]
        (if block
          ;; Hard block (sorry page, CAPTCHA) — retrying is pointless, return immediately
          (do (binding [*out* *err*]
                (println (str "Google blocked (" (name block) ") — skipping retries.")))
            result)
          ;; Empty results but not blocked — transient failure, retry with backoff
          (if (< attempt max-retries)
            (let [backoff-ms (* 1000 (long (Math/pow 2 attempt)))]
              (binding [*out* *err*]
                (println (str "Retry " attempt "/" max-retries
                           " (empty results) \u2014 waiting " backoff-ms "ms...")))
              (Thread/sleep backoff-ms)
              (page/navigate page "https://www.google.com/")
              (page/wait-for-load-state page :domcontentloaded)
              (dismiss-consent! page)
              (random-delay! 500 1500)
              (recur page query opts (inc attempt)))
            result)))
      result)))

;; =============================================================================
;; DuckDuckGo Fallback
;; =============================================================================

(defn duckduckgo-url
  "Builds a DuckDuckGo HTML search URL from a query string.

   Uses the bot-friendly html.duckduckgo.com/html/ endpoint which returns
   static HTML without JavaScript or CAPTCHA challenges. This is the
   recommended endpoint for automated search from datacenter IPs.

   Params:
   `query` - String. The search query.

   Returns:
   String. Fully-formed DuckDuckGo HTML search URL."
  ^String [^String query]
  (let [encoded-q (URLEncoder/encode query ^java.nio.charset.Charset StandardCharsets/UTF_8)]
    (str "https://html.duckduckgo.com/html/?q=" encoded-q)))

(defn- ddg-decode-redirect-url
  "Decodes a DuckDuckGo redirect URL to extract the real destination.

   DDG wraps result URLs in redirects like:
   //duckduckgo.com/l/?uddg=https%3A%2F%2Freal-url.com&rut=...

   This function extracts and decodes the `uddg` parameter.

   Params:
   `href` - String. The href attribute from a DDG result link.

   Returns:
   String. The decoded real URL, or the original href if not a redirect."
  ^String [^String href]
  (if (and href (or (.contains href "uddg=") (.contains href "/l/?uddg")))
    (try
      (let [idx (.indexOf href "uddg=")]
        (when (>= idx 0)
          (let [start   (+ idx 5)
                amp-idx (.indexOf href "&" start)
                encoded (if (> amp-idx start)
                          (.substring href start amp-idx)
                          (.substring href start))]
            (URLDecoder/decode encoded "UTF-8"))))
      (catch Exception _ href))
    href))

(def ^:private ^String ddg-html-results-js
  "JavaScript that extracts search results from DuckDuckGo's HTML endpoint.
   The HTML endpoint (html.duckduckgo.com/html/) uses a different DOM structure
   than the JS endpoint. Results use .result, .result__a, .result__snippet classes.
   URLs are wrapped in DDG redirects with a ?uddg= parameter that must be decoded.
   Returns an array of {title, url, snippet} objects."
  "() => {
  const results = [];
  const seen = new Set();
  let pos = 0;
  // DDG HTML endpoint uses div.result inside #links container
  const resultDivs = document.querySelectorAll('#links .result');
  for (const div of resultDivs) {
    // Skip ad results
    if (div.closest('.results--ads')) continue;
    const titleLink = div.querySelector('a.result__a');
    if (!titleLink) continue;
    let href = titleLink.href || '';
    // DDG wraps URLs in redirect: //duckduckgo.com/l/?uddg=REAL_URL
    // Extract the uddg parameter to get the real URL
    try {
      const url = new URL(href, window.location.origin);
      const uddg = url.searchParams.get('uddg');
      if (uddg) href = decodeURIComponent(uddg);
    } catch(e) {}
    if (!href || seen.has(href)) continue;
    if (href.startsWith('https://duckduckgo.com')) continue;
    seen.add(href);
    pos++;
    const snippetEl = div.querySelector('a.result__snippet');
    const snippet = snippetEl ? snippetEl.textContent.trim() : null;
    const title = titleLink.textContent.trim();
    if (!title) continue;
    results.push({
      title: title,
      url: href,
      snippet: snippet,
      position: pos
    });
  }
  return results;
}")

(defn- ddg-extract-web-results
  "Extracts web results from a DuckDuckGo HTML search results page.

   Params:
   `page` - Page instance on a DDG HTML results page.

   Returns:
   Vector of maps with :title, :url, :snippet, :position."
  [^Page page]
  (try
    (let [raw (page/evaluate page ddg-html-results-js)]
      (vec (for [r raw]
             {:title    (get r "title")
              :url      (get r "url")
              :snippet  (get r "snippet")
              :position (long (get r "position" 0))})))
    (catch Exception _ [])))

(defn ddg-search!
  "Searches DuckDuckGo and returns structured results.

   Uses the bot-friendly HTML endpoint (html.duckduckgo.com/html/) which
   returns static HTML without JavaScript or CAPTCHA challenges. This makes
   it a reliable fallback when Google blocks datacenter IPs (Hetzner, AWS, etc.).

   Params:
   `page`  - Page instance.
   `query` - String. The search query.

   Returns:
   Map with :query, :type, :engine, :results, :stats."
  [^Page page ^String query]
  (let [url (duckduckgo-url query)]
    (page/navigate page url)
    (page/wait-for-load-state page :domcontentloaded)
    ;; Wait for results to render — HTML endpoint is fast but still needs DOM
    (try
      (page/wait-for-function page
        "() => document.querySelectorAll('#links .result').length > 0"
        {:timeout 10000})
      (catch Exception _))
    (let [results (ddg-extract-web-results page)]
      {:query   query
       :type    :web
       :engine  :duckduckgo
       :stats   (str (count results) " results from DuckDuckGo")
       :results results})))

;; =============================================================================
;; High-Level Collect
;; =============================================================================

(defn search-and-collect!
  "Searches Google and collects results across multiple pages.

   Params:
   `page`  - Page instance.
   `query` - String. The search query.
   `opts`  - Map, optional. Same as search! opts, plus:
             :max-pages - Long. Maximum number of pages to collect (default 1).

   Returns:
   Map with :query, :type, :total-results (count), :pages (vector of page results)."
  ([^Page page ^String query]
   (search-and-collect! page query {}))
  ([^Page page ^String query opts]
   (let [max-pages  (long (or (:max-pages opts) 1))
         search-opts (dissoc opts :max-pages)
         first-page (search! page query search-opts)
         pages      (loop [collected [first-page]
                           remaining (dec max-pages)]
                      (if (and (pos? remaining)
                            (:has-next? (peek collected)))
                        (let [next-results (next-page! page)]
                          (if (seq next-results)
                            (recur (conj collected
                                     {:query   query
                                      :type    (:type first-page)
                                      :results next-results
                                      :page    (inc (long (:page (peek collected))))})
                              (dec remaining))
                            collected))
                        collected))]
     {:query         query
      :type          (:type first-page)
      :total-results (reduce + 0 (map #(count (:results %)) pages))
      :pages         pages})))

;; =============================================================================
;; Lazy Pagination (iteration)
;; =============================================================================

(defn search-pages
  "Returns a lazy sequence of search result pages using clojure.core/iteration.
   Each element is a result map from search! (first page) or a map with
   :results, :page, :query, :type (subsequent pages).

   Lazily fetches the next page only when consumed. Stops when Google
   reports no more pages.

   Params:
   `page`  - Page instance.
   `query` - String. The search query.
   `opts`  - Map, optional. Same as search! opts.

   Returns:
   Lazy seq of page result maps. Use (mapcat :results ...) to get flat results.

   Example:
     (->> (search-pages page \"clojure\")
          (take 3)
          (mapcat :results)
          (map :title))"
  ([^Page page ^String query]
   (search-pages page query {}))
  ([^Page page ^String query opts]
   (iteration
     (fn [page-num]
       (if (= 1 (long page-num))
         (search! page query opts)
         (let [results (next-page! page)]
           (when (seq results)
             {:query   query
              :type    (:type opts :web)
              :results results
              :page    page-num
              :has-next? (has-next-page? page)}))))
     :initk 1
     :kf    (fn [result]
              (when (and result (:has-next? result))
                (inc (long (:page result)))))
     :vf    identity
     :somef some?)))

;; =============================================================================
;; ANSI Terminal Rendering
;; =============================================================================

(def ^:dynamic *color-enabled*
  "Controls ANSI color output. nil = auto-detect, true/false = force."
  nil)

(defn- color-enabled?
  "Returns true when ANSI color output should be used.
   Respects NO_COLOR env var (https://no-color.org/) and TTY detection."
  []
  (if (some? *color-enabled*)
    *color-enabled*
    (and (nil? (System/getenv "NO_COLOR"))
      (some? (System/console)))))

(defn- ansi
  "Wraps text with ANSI escape codes when color is enabled.
   codes is a string of ANSI parameter codes (e.g. \"1;33\" for bold yellow)."
  ^String [^String codes ^String text]
  (if (color-enabled?)
    (str "\033[" codes "m" text "\033[0m")
    text))

(defn- bold         ^String [^String s] (ansi "1" s))
(defn- dim          ^String [^String s] (ansi "2" s))
(defn- green        ^String [^String s] (ansi "32" s))
(defn- cyan         ^String [^String s] (ansi "36" s))
(defn- magenta      ^String [^String s] (ansi "35" s))
(defn- bold-yellow  ^String [^String s] (ansi "1;33" s))
(defn- bold-cyan    ^String [^String s] (ansi "1;36" s))
(defn- dim-yellow   ^String [^String s] (ansi "2;33" s))

;; ---------------------------------------------------------------------------
;; Card-style renderers (googler/ddgr-inspired)
;; ---------------------------------------------------------------------------

(defn- format-position
  "Formats a result position number, right-aligned to 2 chars."
  ^String [^long pos]
  (let [s (str pos)]
    (if (< pos 10) (str " " s) s)))

(defn- print-web-cards
  "Prints web results as cards: position + title, URL, snippet."
  [results]
  (doseq [{:keys [title url snippet position]} results]
    (println (str " " (bold-yellow (format-position (long position))) "  " (bold title)))
    (when url
      (println (str "    " (green url))))
    (when (and snippet (not (str/blank? snippet)))
      (println (str "    " (dim snippet))))
    (println)))

(defn- print-image-cards
  "Prints image results as cards: position + title, thumbnail, source."
  [results]
  (doseq [{:keys [title thumbnail-url source-url position]} results]
    (let [display-title (if (str/blank? title) "(no title)" title)]
      (println (str " " (bold-yellow (format-position (long position))) "  " (bold display-title)))
      (when thumbnail-url
        (println (str "    " (dim "thumb") "  " (green thumbnail-url))))
      (when source-url
        (println (str "    " (dim "source") " " (green source-url))))
      (println))))

(defn- print-news-cards
  "Prints news results as cards: position + title, source/time, URL, snippet."
  [results]
  (doseq [{:keys [title url source time snippet position]} results]
    (println (str " " (bold-yellow (format-position (long position))) "  " (bold title)))
    (when (or source time)
      (println (str "    "
                 (when source (magenta source))
                 (when (and source time) (dim " \u00b7 "))
                 (when time (dim-yellow time)))))
    (when url
      (println (str "    " (green url))))
    (when (and snippet (not (str/blank? snippet)))
      (println (str "    " (dim snippet))))
    (println)))

(defn- print-header
  "Prints the search header: query in bold, stats in dim."
  [^String query stats]
  (println (str " " (bold query)
             (when stats (str "  " (dim stats)))))
  (println))

(defn- print-people-also-ask
  "Prints 'People also ask' section with styled markers."
  [questions]
  (when (seq questions)
    (println (bold-cyan "People also ask"))
    (doseq [q questions]
      (println (str "  " (cyan (str "\u25b8 " q)))))
    (println)))

(defn- print-related-searches
  "Prints related searches inline with dot separators."
  [searches]
  (when (seq searches)
    (println (bold-cyan "Related searches"))
    (println (str "  " (str/join (dim " \u00b7 ") searches)))
    (println)))

;; =============================================================================
;; Markdown Table Rendering
;; =============================================================================

(defn- format-web-results-markdown
  "Formats web results as a markdown table with #, Title, URL, Snippet columns."
  [results]
  (if (empty? results)
    "*No results found.*"
    (markdown/to-markdown-table
      (mapv (fn [{:keys [title url snippet position]}]
              {"#" position
               "Title" (or title "")
               "URL" (or url "")
               "Snippet" (or snippet "")})
        results)
      {:columns ["#" "Title" "URL" "Snippet"]})))

(defn- format-image-results-markdown
  "Formats image results as a markdown table with #, Title, Thumbnail, Source columns."
  [results]
  (if (empty? results)
    "*No results found.*"
    (markdown/to-markdown-table
      (mapv (fn [{:keys [title thumbnail-url source-url position]}]
              {"#" position
               "Title" (if (str/blank? title) "(no title)" title)
               "Thumbnail" (or thumbnail-url "")
               "Source" (or source-url "")})
        results)
      {:columns ["#" "Title" "Thumbnail" "Source"]})))

(defn- format-news-results-markdown
  "Formats news results as a markdown table with #, Title, Source, Time, URL columns."
  [results]
  (if (empty? results)
    "*No results found.*"
    (markdown/to-markdown-table
      (mapv (fn [{:keys [title url source time snippet position]}]
              {"#" position
               "Title" (or title "")
               "Source" (or source "")
               "Time" (or time "")
               "URL" (or url "")})
        results)
      {:columns ["#" "Title" "Source" "Time" "URL"]})))

(defn format-results-as-markdown
  "Formats search results as a markdown table based on search type.

   Params:
   `search-type` - Keyword. :web, :images, or :news.
   `results`     - Vector of result maps.

   Returns:
   String. Markdown table."
  [search-type results]
  (case search-type
    :images (format-image-results-markdown results)
    :news   (format-news-results-markdown results)
    (format-web-results-markdown results)))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn- print-search-help []
  (println "search - Search Google from the command line")
  (println "")
  (println "Output: Markdown table by default. Use --json for machine-readable output.")
  (println "Retries: Automatically retries up to 3 times with backoff on empty results.")
  (println "")
  (println "Usage:")
  (println "  spel search <query> [options]")
  (println "")
  (println "Options:")
  (println "  --images              Search for images")
  (println "  --news                Search for news")
  (println "  --page N              Results page number (default: 1)")
  (println "  --num N               Results per page (default: 10)")
  (println "  --max-pages N         Collect N pages of results (default: 1)")
  (println "  --lang LANG           Language code (e.g. en, de, fr)")
  (println "  --safe MODE           Safe search: off, medium, high")
  (println "  --time-range RANGE    Time filter: day, week, month, year")
  (println "  --limit N             Show only first N results")
  (println "  --open N              Navigate to result #N and print its info")
  (println "  --json                Output as JSON (includes warning field on failure)")
  (println "  --screenshot PATH     Save screenshot of results page")
  (println "  --no-stealth          Disable stealth mode (stealth is ON by default)")
  (println "  --debug               Show extra diagnostics on failure")
  (println "  --ddg                 Use DuckDuckGo instead of Google")
  (println "                        (auto-fallback: DDG is tried when Google blocks)")
  (println "  --help, -h            Show this help")
  (println "")
  (println "Examples:")
  (println "  spel search \"clojure programming\"")
  (println "  spel search \"cats\" --images")
  (println "  spel search \"world news\" --news --json")
  (println "  spel search \"rust lang\" --page 2 --num 20")
  (println "  spel search \"query\" --max-pages 3 --json")
  (println "  spel search \"query\" --limit 5")
  (println "  spel search \"query\" --open 1")
  (println "  spel search \"query\" --screenshot results.png")
  (println "  spel search \"query\" --ddg")
  (println "  spel search \"query\" --lang en --time-range week"))

(defn- parse-search-args
  [args]
  (loop [remaining args
         opts      {:type :web :page 1 :num 10}
         query     nil
         json?     false
         screenshot nil
         max-pages 1
         limit     nil
         open      nil
         stealth?  true
         help?     false
         debug?    false
         ddg?      false]
    (if (empty? remaining)
      {:query query :opts (assoc opts :max-pages max-pages) :json? json?
       :screenshot screenshot :limit limit :open open :stealth? stealth? :help? help? :debug? debug? :ddg? ddg?}
      (let [arg (first remaining)]
        (cond
          (or (= "--help" arg) (= "-h" arg))
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? true debug? ddg?)

          (= "--images" arg)
          (recur (rest remaining) (assoc opts :type :images) query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--news" arg)
          (recur (rest remaining) (assoc opts :type :news) query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--json" arg)
          (recur (rest remaining) opts query true screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--page" arg)
          (recur (drop 2 remaining) (assoc opts :page (parse-long (second remaining)))
            query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--num" arg)
          (recur (drop 2 remaining) (assoc opts :num (parse-long (second remaining)))
            query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--max-pages" arg)
          (recur (drop 2 remaining) opts query json? screenshot
            (parse-long (second remaining)) limit open stealth? help? debug? ddg?)

          (= "--limit" arg)
          (recur (drop 2 remaining) opts query json? screenshot max-pages
            (parse-long (second remaining)) open stealth? help? debug? ddg?)

          (= "--open" arg)
          (recur (drop 2 remaining) opts query json? screenshot max-pages
            limit (parse-long (second remaining)) stealth? help? debug? ddg?)

          (= "--lang" arg)
          (recur (drop 2 remaining) (assoc opts :lang (second remaining))
            query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--safe" arg)
          (recur (drop 2 remaining) (assoc opts :safe (keyword (second remaining)))
            query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--time-range" arg)
          (recur (drop 2 remaining) (assoc opts :time-range (keyword (second remaining)))
            query json? screenshot max-pages limit open stealth? help? debug? ddg?)

          (= "--screenshot" arg)
          (recur (drop 2 remaining) opts query json? (second remaining) max-pages limit open stealth? help? debug? ddg?)

          (= "--stealth" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open true help? debug? ddg?)

          (= "--no-stealth" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open false help? debug? ddg?)

          (= "--debug" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? help? true ddg?)

          (= "--ddg" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? help? debug? true)

          (nil? query)
          (recur (rest remaining) opts arg json? screenshot max-pages limit open stealth? help? debug? ddg?)

          :else
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? help? debug? ddg?))))))

(defn -main
  "CLI entry point for the search tool command.

   Usage:
     spel search <query> [options]

   See --help for all options."
  [& args]
  (let [{:keys [query opts json? screenshot limit open stealth? help? debug? ddg?]} (parse-search-args args)]
    (cond
      help?
      (print-search-help)

      (str/blank? query)
      (do (binding [*out* *err*]
            (println "Error: search requires a query argument")
            (println "Usage: spel search <query> [options]")
            (println "Run 'spel search --help' for details."))
        (System/exit 1))

      :else
      (let [max-pages (long (or (:max-pages opts) 1))
            exit-code (volatile! 0)]
        (try
          (driver/ensure-driver!)
          (let [pw          (core/create)
                _           (when (core/anomaly? pw)
                              (throw (ex-info "Failed to create Playwright" {})))
                launch-opts (cond-> {:headless true}
                              stealth?
                              (-> (assoc :args (stealth/stealth-args))
                                (assoc :ignore-default-args (stealth/stealth-ignore-default-args))))
                browser     (core/launch-chromium pw launch-opts)
                ua          (if stealth? (random-user-agent) nil)
                ctx-opts    (cond-> {:viewport {:width 1280 :height 720}}
                              ua (assoc :user-agent ua
                                   :extra-http-headers (anti-detection-headers ua)))
                ctx         (core/new-context browser ctx-opts)
                _           (when stealth?
                              (set-consent-cookie! ctx)
                              (.addInitScript ^BrowserContext ctx ^String (stealth/stealth-init-script)))
                pg          (core/new-page-from-context ctx)]
            (try
              (let [;; Primary search: Google or DDG based on --ddg flag
                    result    (if ddg?
                                (ddg-search! pg query)
                                (if (> max-pages 1)
                                  (search-and-collect! pg query (dissoc opts :max-pages :page))
                                  (retry-search! pg query (dissoc opts :max-pages) 1)))
                    n-results (long (if (and (not ddg?) (> max-pages 1))
                                      (:total-results result 0)
                                      (count (:results result))))
                    blocked?  (when (and (zero? n-results) (not ddg?)) (detect-block! pg))
                    ;; DDG fallback: auto-switch when Google is blocked
                    [result n-results] (if (and blocked? (not ddg?))
                                         (do
                                           (binding [*out* *err*]
                                             (println "Google blocked \u2014 falling back to DuckDuckGo..."))
                                           (let [ddg-result (ddg-search! pg query)
                                                 ddg-n     (long (count (:results ddg-result)))]
                                             [ddg-result ddg-n]))
                                         [result n-results])
                    warning   (when (zero? n-results)
                                (if blocked?
                                  (str "Google blocked (" (name blocked?) ") and DuckDuckGo fallback returned 0 results.")
                                  (when-not ddg? "0 results returned.")))
                    result    (cond-> result
                                warning (assoc :warning warning)
                                warning (assoc :warning warning))]
                (when (zero? n-results)
                  (binding [*out* *err*]
                    (println (str "Warning: " warning)))
                  (print-diagnostics! pg blocked?)
                  (let [debug-path (str (System/getProperty "java.io.tmpdir")
                                     "/spel-search-debug-" (System/currentTimeMillis) ".png")]
                    (page/screenshot pg {:path debug-path :full-page true})
                    (binding [*out* *err*]
                      (println (str "  Debug screenshot: " debug-path)))))
                (when screenshot
                  (page/screenshot pg {:path screenshot :full-page true})
                  (when-not json?
                    (println (str "Screenshot saved: " screenshot))))

                ;; Handle --open N
                (when open
                  (let [all-results (if (> max-pages 1)
                                      (mapcat :results (:pages result))
                                      (:results result))
                        target      (first (filter #(= (long open) (long (:position %))) all-results))]
                    (if target
                      (do
                        (page/navigate pg (:url target))
                        (page/wait-for-load-state pg :domcontentloaded)
                        (let [title (page/title pg)
                              url   (page/url pg)]
                          (println (str "Opened result #" open ":"))
                          (println (str "  Title: " title))
                          (println (str "  URL:   " url))))
                      (binding [*out* *err*]
                        (println (str "Error: no result at position " open))))))

                ;; Output (skip if --open was used without --json)
                (when-not (and open (not json?))
                  (if json?
                    (println (json/write-json-str result :escape-slash false))
                    ;; Default: markdown table output
                    (if (> max-pages 1)
                      (do
                        (println (str "## " query))
                        (when (:stats (first (:pages result)))
                          (println (str "*" (:stats (first (:pages result))) "*")))
                        (println)
                        (doseq [pg-data (:pages result)]
                          (let [rs (cond->> (:results pg-data)
                                     limit (take limit))]
                            (println (str "### Page " (:page pg-data 1)))
                            (println)
                            (println (format-results-as-markdown (:type result) rs))
                            (println)))
                        (println (str "**Total: " (:total-results result) " results**")))
                      (let [rs (cond->> (:results result)
                                 limit (take limit))]
                        (println (str "## " query))
                        (when (:stats result)
                          (println (str "*" (:stats result) "*")))
                        (println)
                        (println (format-results-as-markdown (:type result) rs))
                        (when (= :web (:type result))
                          (when (seq (:people-also-ask result))
                            (println)
                            (println "### People also ask")
                            (doseq [q (:people-also-ask result)]
                              (println (str "- " q))))
                          (when (seq (:related-searches result))
                            (println)
                            (println "### Related searches")
                            (println (str/join " \u00b7 " (:related-searches result)))))))))
                (when warning
                  (println)
                  (println (str "> \u26a0\ufe0f **Warning:** " warning))))
              (finally
                (try (core/close-browser! browser) (catch Exception _))
                (try (core/close! pw) (catch Exception _)))))
          (catch Exception e
            (vreset! exit-code 1)
            (binding [*out* *err*]
              (println (str "Error: " (.getMessage e)))))
          (finally
            (System/exit @exit-code)))))))
