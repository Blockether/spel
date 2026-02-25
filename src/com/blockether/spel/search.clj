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
   [com.blockether.spel.page :as page]
   [com.blockether.spel.stealth :as stealth])
  (:import
   [com.microsoft.playwright BrowserContext Page]
   [com.microsoft.playwright.options AriaRole]
   [java.net URLEncoder]
   [java.nio.charset StandardCharsets]))

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
                      (conj "tbs=qdr:y"))]
     (str "https://www.google.com/search?" (str/join "&" params)))))

;; =============================================================================
;; Consent Handling
;; =============================================================================

(defn dismiss-consent!
  "Dismisses Google's cookie consent dialog if present.

   Handles EU GDPR consent dialogs by clicking the accept/reject button.
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
                      (catch Exception _ false)))]
    (or (try-click "Accept all")
      (try-click "Reject all")
      (try-click "I agree")))
  nil)

;; =============================================================================
;; Wait Helpers
;; =============================================================================

(defn- wait-for-results!
  "Waits for search results to render using Playwright's wait-for-function.
   Uses a short timeout so callers can fall back gracefully."
  [^Page page search-type]
  (try
    (let [js (case search-type
               :images "() => document.querySelectorAll('#islrg img, div[data-id] img').length > 0"
               :news   "() => document.querySelectorAll('div.SoaBEf, #rso h3').length > 0"
               ;; :web default
               "() => document.querySelectorAll('#rso h3').length > 0")]
      (page/wait-for-function page js))
    (catch Exception _)))

;; =============================================================================
;; Warmup
;; =============================================================================

(defn warmup!
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
     (page/wait-for-load-state page :networkidle)
     (dismiss-consent! page)
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
     (page/wait-for-load-state page :networkidle)
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
   Uses networkidle + wait-for-function for reliable result rendering.

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
     (page/wait-for-load-state page :networkidle)
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
;; Table Rendering
;; =============================================================================

(defn- truncate
  ^String [^String s ^long max-len]
  (if (or (nil? s) (<= (.length s) max-len))
    (or s "")
    (str (subs s 0 (- max-len 3)) "...")))

(defn- pad-right
  ^String [^String s ^long width]
  (let [s (or s "")]
    (if (>= (.length s) width)
      s
      (str s (apply str (repeat (- width (.length s)) \space))))))

(defn- print-table-row [widths cells]
  (println
    (str " "
      (str/join " | "
        (map (fn [w cell]
               (pad-right (truncate (str cell) w) w))
          widths cells)))))

(defn- print-table-sep [widths]
  (println
    (str "-"
      (str/join "-+-"
        (map (fn [w] (apply str (repeat (inc (long w)) \-))) widths)))))

(defn- print-web-table [results]
  (let [widths [3 38 40 38]]
    (print-table-row widths ["#" "Title" "URL" "Snippet"])
    (print-table-sep widths)
    (doseq [{:keys [title url snippet position]} results]
      (print-table-row widths [position title url snippet]))))

(defn- print-image-table [results]
  (let [widths [3 38 60]]
    (print-table-row widths ["#" "Title" "Thumbnail URL"])
    (print-table-sep widths)
    (doseq [{:keys [title thumbnail-url position]} results]
      (print-table-row widths [position (if (str/blank? title) "(no title)" title) thumbnail-url]))))

(defn- print-news-table [results]
  (let [widths [3 36 16 10 40]]
    (print-table-row widths ["#" "Title" "Source" "Time" "URL"])
    (print-table-sep widths)
    (doseq [{:keys [title url source time position]} results]
      (print-table-row widths [position title source time url]))))

;; =============================================================================
;; CLI Entry Point
;; =============================================================================

(defn- print-search-help []
  (println "search - Search Google from the command line")
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
  (println "  --json                Output as JSON")
  (println "  --screenshot PATH     Save screenshot of results page")
  (println "  --no-stealth          Disable stealth mode (stealth is ON by default)")
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
         help?     false]
    (if (empty? remaining)
      {:query query :opts (assoc opts :max-pages max-pages) :json? json?
       :screenshot screenshot :limit limit :open open :stealth? stealth? :help? help?}
      (let [arg (first remaining)]
        (cond
          (or (= "--help" arg) (= "-h" arg))
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? true)

          (= "--images" arg)
          (recur (rest remaining) (assoc opts :type :images) query json? screenshot max-pages limit open stealth? help?)

          (= "--news" arg)
          (recur (rest remaining) (assoc opts :type :news) query json? screenshot max-pages limit open stealth? help?)

          (= "--json" arg)
          (recur (rest remaining) opts query true screenshot max-pages limit open stealth? help?)

          (= "--page" arg)
          (recur (drop 2 remaining) (assoc opts :page (parse-long (second remaining)))
            query json? screenshot max-pages limit open stealth? help?)

          (= "--num" arg)
          (recur (drop 2 remaining) (assoc opts :num (parse-long (second remaining)))
            query json? screenshot max-pages limit open stealth? help?)

          (= "--max-pages" arg)
          (recur (drop 2 remaining) opts query json? screenshot
            (parse-long (second remaining)) limit open stealth? help?)

          (= "--limit" arg)
          (recur (drop 2 remaining) opts query json? screenshot max-pages
            (parse-long (second remaining)) open stealth? help?)

          (= "--open" arg)
          (recur (drop 2 remaining) opts query json? screenshot max-pages
            limit (parse-long (second remaining)) stealth? help?)

          (= "--lang" arg)
          (recur (drop 2 remaining) (assoc opts :lang (second remaining))
            query json? screenshot max-pages limit open stealth? help?)

          (= "--safe" arg)
          (recur (drop 2 remaining) (assoc opts :safe (keyword (second remaining)))
            query json? screenshot max-pages limit open stealth? help?)

          (= "--time-range" arg)
          (recur (drop 2 remaining) (assoc opts :time-range (keyword (second remaining)))
            query json? screenshot max-pages limit open stealth? help?)

          (= "--screenshot" arg)
          (recur (drop 2 remaining) opts query json? (second remaining) max-pages limit open stealth? help?)

          (= "--stealth" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open true help?)

          (= "--no-stealth" arg)
          (recur (rest remaining) opts query json? screenshot max-pages limit open false help?)

          (nil? query)
          (recur (rest remaining) opts arg json? screenshot max-pages limit open stealth? help?)

          :else
          (recur (rest remaining) opts query json? screenshot max-pages limit open stealth? help?))))))

(defn -main
  "CLI entry point for the search tool command.

   Usage:
     spel search <query> [options]

   See --help for all options."
  [& args]
  (let [{:keys [query opts json? screenshot limit open stealth? help?]} (parse-search-args args)]
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
                ctx-opts    (cond-> {:viewport {:width 1280 :height 720}}
                              stealth?
                              (assoc :user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))
                ctx         (core/new-context browser ctx-opts)
                _           (when stealth?
                              (.addInitScript ^BrowserContext ctx ^String (stealth/stealth-init-script)))
                pg          (core/new-page-from-context ctx)]
            (try
              (let [result    (if (> max-pages 1)
                                (search-and-collect! pg query (dissoc opts :max-pages :page))
                                (search! pg query (dissoc opts :max-pages)))
                    n-results (long (if (> max-pages 1)
                                      (:total-results result 0)
                                      (count (:results result))))]
                (when (zero? n-results)
                  (binding [*out* *err*]
                    (println "Warning: 0 results returned. Google may have blocked the request (bot detection).")))
                (when screenshot
                  (page/screenshot pg {:path screenshot :full-page true})
                  (when-not json?
                    (println (str "Screenshot saved: " screenshot))))

                ;; Handle --open N: navigate to result and print info
                (when open
                  (let [all-results (if (> max-pages 1)
                                      (mapcat :results (:pages result))
                                      (:results result))
                        target      (first (filter #(= (long open) (long (:position %))) all-results))]
                    (if target
                      (do
                        (page/navigate pg (:url target))
                        (page/wait-for-load-state pg :networkidle)
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
                    (if (> max-pages 1)
                      ;; Multi-page output
                      (do
                        (when-let [stats (:stats (first (:pages result)))]
                          (println stats)
                          (println))
                        (doseq [pg-data (:pages result)]
                          (let [rs (cond->> (:results pg-data)
                                     limit (take limit))]
                            (println (str "--- Page " (:page pg-data 1) " ---"))
                            (case (:type result)
                              :images (print-image-table rs)
                              :news   (print-news-table rs)
                              (print-web-table rs))))
                        (println (str "Total: " (:total-results result) " results")))
                      ;; Single page output
                      (let [rs (cond->> (:results result)
                                 limit (take limit))]
                        (when (:stats result)
                          (println (:stats result))
                          (println))
                        (case (:type result)
                          :images (print-image-table rs)
                          :news   (print-news-table rs)
                          (print-web-table rs))
                        (when (and (= :web (:type result))
                                (seq (:people-also-ask result)))
                          (println)
                          (println "People also ask:")
                          (doseq [q (:people-also-ask result)]
                            (println (str "  • " q))))
                        (when (and (= :web (:type result))
                                (seq (:related-searches result)))
                          (println)
                          (println "Related searches:")
                          (doseq [s (:related-searches result)]
                            (println (str "  • " s)))))))))
              (finally
                (try (core/close-browser! browser) (catch Exception _))
                (try (core/close! pw) (catch Exception _)))))
          (catch Exception e
            (vreset! exit-code 1)
            (binding [*out* *err*]
              (println (str "Error: " (.getMessage e)))))
          (finally
            (System/exit @exit-code)))))))
