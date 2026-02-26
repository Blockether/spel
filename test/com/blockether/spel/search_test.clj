(ns com.blockether.spel.search-test
  "Tests for com.blockether.spel.search namespace.

   Unit tests cover URL building, CLI arg parsing, and SCI namespace
   availability — no browser needed. Integration tests use a real
   Playwright browser session."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.search :as sut]
   [com.blockether.spel.sci-env :as sci-env]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; URL Building
;; =============================================================================

(defdescribe search-url-test
  "Unit tests for search-url"

  (describe "basic query"
    (it "builds URL with just a query"
      (let [url (sut/search-url "test")]
        (expect (= "https://www.google.com/search?q=test" url))))

    (it "encodes query with spaces"
      (let [url (sut/search-url "hello world")]
        (expect (= "https://www.google.com/search?q=hello+world" url))))

    (it "encodes special characters"
      (let [url (sut/search-url "clojure & java")]
        (expect (.contains ^String url "q=clojure+%26+java")))))

  (describe "search type"
    (it "adds tbm=isch for :images"
      (let [url (sut/search-url "cats" {:type :images})]
        (expect (.contains ^String url "tbm=isch"))))

    (it "adds tbm=nws for :news"
      (let [url (sut/search-url "news" {:type :news})]
        (expect (.contains ^String url "tbm=nws"))))

    (it "adds no tbm for :web (default)"
      (let [url (sut/search-url "test" {:type :web})]
        (expect (not (.contains ^String url "tbm="))))))

  (describe "pagination"
    (it "page 1 has no start param"
      (let [url (sut/search-url "test" {:page 1})]
        (expect (not (.contains ^String url "start=")))))

    (it "page 2 with default num=10 adds start=10"
      (let [url (sut/search-url "test" {:page 2})]
        (expect (.contains ^String url "start=10"))))

    (it "page 3 with num=20 adds start=40"
      (let [url (sut/search-url "test" {:page 3 :num 20})]
        (expect (.contains ^String url "start=40"))))

    (it "num defaults to 10 — no num param"
      (let [url (sut/search-url "test")]
        (expect (not (.contains ^String url "num=")))))

    (it "non-default num adds num param"
      (let [url (sut/search-url "test" {:num 20})]
        (expect (.contains ^String url "num=20")))))

  (describe "language"
    (it "adds hl param"
      (let [url (sut/search-url "test" {:lang "de"})]
        (expect (.contains ^String url "hl=de")))))

  (describe "safe search"
    (it ":off adds no safe param"
      (let [url (sut/search-url "test" {:safe :off})]
        (expect (not (.contains ^String url "safe=")))))

    (it ":medium adds safe=medium"
      (let [url (sut/search-url "test" {:safe :medium})]
        (expect (.contains ^String url "safe=medium"))))

    (it ":high adds safe=high"
      (let [url (sut/search-url "test" {:safe :high})]
        (expect (.contains ^String url "safe=high")))))

  (describe "time range"
    (it ":day adds tbs=qdr:d"
      (let [url (sut/search-url "test" {:time-range :day})]
        (expect (.contains ^String url "tbs=qdr:d"))))

    (it ":week adds tbs=qdr:w"
      (let [url (sut/search-url "test" {:time-range :week})]
        (expect (.contains ^String url "tbs=qdr:w"))))

    (it ":month adds tbs=qdr:m"
      (let [url (sut/search-url "test" {:time-range :month})]
        (expect (.contains ^String url "tbs=qdr:m"))))

    (it ":year adds tbs=qdr:y"
      (let [url (sut/search-url "test" {:time-range :year})]
        (expect (.contains ^String url "tbs=qdr:y")))))

  (describe "combined options"
    (it "builds URL with multiple params"
      (let [url (sut/search-url "clojure" {:type :images
                                           :page 2
                                           :num 20
                                           :lang "en"
                                           :safe :high
                                           :time-range :week})]
        (expect (.contains ^String url "q=clojure"))
        (expect (.contains ^String url "tbm=isch"))
        (expect (.contains ^String url "start=20"))
        (expect (.contains ^String url "num=20"))
        (expect (.contains ^String url "hl=en"))
        (expect (.contains ^String url "safe=high"))
        (expect (.contains ^String url "tbs=qdr:w"))))))

;; =============================================================================
;; CLI Arg Parsing
;; =============================================================================

(defdescribe parse-search-args-test
  "Unit tests for parse-search-args (private)"

  (describe "basic parsing"
    (it "parses bare query"
      (let [result (#'sut/parse-search-args ["hello world"])]
        (expect (= "hello world" (:query result)))
        (expect (= :web (get-in result [:opts :type])))
        (expect (false? (:json? result)))
        (expect (nil? (:screenshot result)))
        (expect (true? (:stealth? result)))
        (expect (false? (:help? result)))))

    (it "parses --help flag"
      (let [result (#'sut/parse-search-args ["--help"])]
        (expect (true? (:help? result)))))

    (it "parses -h flag"
      (let [result (#'sut/parse-search-args ["-h"])]
        (expect (true? (:help? result))))))

  (describe "type flags"
    (it "parses --images"
      (let [result (#'sut/parse-search-args ["cats" "--images"])]
        (expect (= :images (get-in result [:opts :type])))))

    (it "parses --news"
      (let [result (#'sut/parse-search-args ["news topic" "--news"])]
        (expect (= :news (get-in result [:opts :type]))))))

  (describe "option flags"
    (it "parses --json"
      (let [result (#'sut/parse-search-args ["test" "--json"])]
        (expect (true? (:json? result)))))

    (it "parses --page"
      (let [result (#'sut/parse-search-args ["test" "--page" "3"])]
        (expect (= 3 (get-in result [:opts :page])))))

    (it "parses --num"
      (let [result (#'sut/parse-search-args ["test" "--num" "20"])]
        (expect (= 20 (get-in result [:opts :num])))))

    (it "parses --max-pages"
      (let [result (#'sut/parse-search-args ["test" "--max-pages" "5"])]
        (expect (= 5 (get-in result [:opts :max-pages])))))

    (it "parses --lang"
      (let [result (#'sut/parse-search-args ["test" "--lang" "de"])]
        (expect (= "de" (get-in result [:opts :lang])))))

    (it "parses --safe"
      (let [result (#'sut/parse-search-args ["test" "--safe" "high"])]
        (expect (= :high (get-in result [:opts :safe])))))

    (it "parses --time-range"
      (let [result (#'sut/parse-search-args ["test" "--time-range" "week"])]
        (expect (= :week (get-in result [:opts :time-range])))))

    (it "parses --screenshot"
      (let [result (#'sut/parse-search-args ["test" "--screenshot" "out.png"])]
        (expect (= "out.png" (:screenshot result)))))

    (it "stealth defaults to true"
      (let [result (#'sut/parse-search-args ["test"])]
        (expect (true? (:stealth? result)))))

    (it "parses --no-stealth"
      (let [result (#'sut/parse-search-args ["test" "--no-stealth"])]
        (expect (false? (:stealth? result)))))

    (it "parses --stealth explicitly"
      (let [result (#'sut/parse-search-args ["test" "--stealth"])]
        (expect (true? (:stealth? result))))))

  (describe "limit and open flags"
    (it "parses --limit"
      (let [result (#'sut/parse-search-args ["test" "--limit" "5"])]
        (expect (= 5 (:limit result)))))

    (it "limit defaults to nil"
      (let [result (#'sut/parse-search-args ["test"])]
        (expect (nil? (:limit result)))))

    (it "parses --open"
      (let [result (#'sut/parse-search-args ["test" "--open" "3"])]
        (expect (= 3 (:open result)))))

    (it "open defaults to nil"
      (let [result (#'sut/parse-search-args ["test"])]
        (expect (nil? (:open result)))))

    (it "parses --limit and --open together"
      (let [result (#'sut/parse-search-args ["test" "--limit" "5" "--open" "2"])]
        (expect (= 5 (:limit result)))
        (expect (= 2 (:open result))))))

  (describe "combined flags"
    (it "parses multiple options together"
      (let [result (#'sut/parse-search-args ["test query"
                                             "--images"
                                             "--page" "2"
                                             "--num" "20"
                                             "--json"
                                             "--screenshot" "shot.png"
                                             "--lang" "en"
                                             "--safe" "medium"
                                             "--time-range" "month"
                                             "--max-pages" "3"
                                             "--limit" "10"
                                             "--open" "1"])]
        (expect (= "test query" (:query result)))
        (expect (= :images (get-in result [:opts :type])))
        (expect (= 2 (get-in result [:opts :page])))
        (expect (= 20 (get-in result [:opts :num])))
        (expect (= 3 (get-in result [:opts :max-pages])))
        (expect (= "en" (get-in result [:opts :lang])))
        (expect (= :medium (get-in result [:opts :safe])))
        (expect (= :month (get-in result [:opts :time-range])))
        (expect (true? (:json? result)))
        (expect (= "shot.png" (:screenshot result)))
        (expect (= 10 (:limit result)))
        (expect (= 1 (:open result)))))))

;; =============================================================================
;; SCI Namespace Availability
;; =============================================================================

(defdescribe sci-search-namespace-test
  "Unit tests for search/ SCI namespace availability"

  (describe "search/ namespace functions"
    (it "has search-url function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/search-url)")))))

    (it "has search! function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/search!)")))))

    (it "has search-and-collect! function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/search-and-collect!)")))))

    (it "has extract-web-results function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-web-results)")))))

    (it "has extract-image-results function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-image-results)")))))

    (it "has extract-news-results function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-news-results)")))))

    (it "has extract-result-stats function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-result-stats)")))))

    (it "has extract-people-also-ask function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-people-also-ask)")))))

    (it "has extract-related-searches function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/extract-related-searches)")))))

    (it "has has-next-page? function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/has-next-page?)")))))

    (it "has next-page! function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/next-page!)")))))

    (it "has go-to-page! function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/go-to-page!)"))))))

  (describe "search/ URL building via SCI"
    (it "search/search-url builds correct URL"
      (let [ctx (sci-env/create-sci-ctx)
            url (sci-env/eval-string ctx "(search/search-url \"test\")")]
        (expect (= "https://www.google.com/search?q=test" url))))

    (it "search/search-url with opts builds correct URL"
      (let [ctx (sci-env/create-sci-ctx)
            url (sci-env/eval-string ctx "(search/search-url \"test\" {:type :images})")]
        (expect (.contains ^String url "tbm=isch")))))

  (describe "search/search-pages lazy pagination"
    (it "has search-pages function"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? search/search-pages)"))))))

  (describe "iteration binding"
    (it "iteration is available in SCI"
      (let [ctx (sci-env/create-sci-ctx)]
        (expect (true? (sci-env/eval-string ctx "(fn? iteration)")))))

    (it "iteration works with basic step function"
      (let [ctx (sci-env/create-sci-ctx)
            result (sci-env/eval-string ctx
                     "(vec (iteration (fn [k] (when (<= k 3) {:v k}))
                                     :initk 1
                                     :kf (fn [r] (inc (:v r)))
                                     :vf :v
                                     :somef some?))")]
        (expect (= [1 2 3] result)))))

  (describe "require com.blockether.spel.search"
    (it "can require and use search namespace"
      (let [ctx (sci-env/create-sci-ctx)]
        (sci-env/eval-string ctx "(require '[com.blockether.spel.search :as s])")
        (expect (true? (sci-env/eval-string ctx "(fn? s/search!)")))
        (expect (true? (sci-env/eval-string ctx "(fn? s/search-url)")))
        (expect (true? (sci-env/eval-string ctx "(fn? s/search-pages)")))))))

;; =============================================================================
;; ANSI Terminal Rendering
;; =============================================================================

(defdescribe color-enabled-test
  "Unit tests for color-enabled? with dynamic binding"

  (describe "*color-enabled* binding"
    (it "returns false when *color-enabled* is false"
      (binding [sut/*color-enabled* false]
        (expect (false? (#'sut/color-enabled?)))))

    (it "returns true when *color-enabled* is true"
      (binding [sut/*color-enabled* true]
        (expect (true? (#'sut/color-enabled?)))))))

(defdescribe format-position-test
  "Unit tests for format-position"

  (describe "alignment"
    (it "pads single digits with space"
      (expect (= " 1" (#'sut/format-position 1)))
      (expect (= " 9" (#'sut/format-position 9))))

    (it "leaves double digits as-is"
      (expect (= "10" (#'sut/format-position 10)))
      (expect (= "99" (#'sut/format-position 99))))))

(defdescribe ansi-rendering-test
  "Unit tests for ANSI rendering helpers"

  (describe "ansi function"
    (it "returns plain text when color disabled"
      (binding [sut/*color-enabled* false]
        (expect (= "hello" (#'sut/ansi "1" "hello")))))

    (it "wraps text with escape codes when color enabled"
      (binding [sut/*color-enabled* true]
        (let [result (#'sut/ansi "1;33" "hello")]
          (expect (.contains ^String result "\033["))
          (expect (.contains ^String result "hello"))
          (expect (.startsWith ^String result "\033[1;33m"))
          (expect (.endsWith ^String result "\033[0m")))))))

;; =============================================================================
;; Card Renderers
;; =============================================================================

(defdescribe print-web-cards-test
  "Unit tests for print-web-cards"

  (describe "web result cards"
    (it "prints title, URL, and snippet for each result"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "Example" :url "https://example.com" :snippet "A snippet" :position 1}]
              output (with-out-str (#'sut/print-web-cards results))]
          (expect (.contains ^String output "1"))
          (expect (.contains ^String output "Example"))
          (expect (.contains ^String output "https://example.com"))
          (expect (.contains ^String output "A snippet")))))

    (it "omits snippet line when snippet is nil"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "No Snippet" :url "https://example.com" :snippet nil :position 1}]
              output (with-out-str (#'sut/print-web-cards results))]
          (expect (.contains ^String output "No Snippet"))
          (expect (.contains ^String output "https://example.com"))
          ;; Should have title line + url line = 2 non-blank lines
          (expect (= 2 (count (remove str/blank? (str/split output #"\n"))))))))

    (it "prints multiple results separated by blank lines"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "First" :url "https://first.com" :snippet "s1" :position 1}
                       {:title "Second" :url "https://second.com" :snippet "s2" :position 2}]
              output (with-out-str (#'sut/print-web-cards results))]
          (expect (.contains ^String output "First"))
          (expect (.contains ^String output "Second"))
          (expect (.contains ^String output "https://first.com"))
          (expect (.contains ^String output "https://second.com")))))))

(defdescribe print-image-cards-test
  "Unit tests for print-image-cards"

  (describe "image result cards"
    (it "prints title, thumb, and source labels"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "Cat" :thumbnail-url "https://img.com/cat.jpg"
                        :source-url "https://cats.com" :position 1}]
              output (with-out-str (#'sut/print-image-cards results))]
          (expect (.contains ^String output "Cat"))
          (expect (.contains ^String output "thumb"))
          (expect (.contains ^String output "https://img.com/cat.jpg"))
          (expect (.contains ^String output "source"))
          (expect (.contains ^String output "https://cats.com")))))

    (it "shows (no title) for blank title"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "" :thumbnail-url "https://img.com/a.jpg" :source-url nil :position 1}]
              output (with-out-str (#'sut/print-image-cards results))]
          (expect (.contains ^String output "(no title)")))))))

(defdescribe print-news-cards-test
  "Unit tests for print-news-cards"

  (describe "news result cards"
    (it "prints title, source, time, URL, and snippet"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "Breaking News" :url "https://news.com/article"
                        :source "BBC" :time "2h ago" :snippet "Details here" :position 1}]
              output (with-out-str (#'sut/print-news-cards results))]
          (expect (.contains ^String output "Breaking News"))
          (expect (.contains ^String output "BBC"))
          (expect (.contains ^String output "2h ago"))
          (expect (.contains ^String output "https://news.com/article"))
          (expect (.contains ^String output "Details here")))))

    (it "handles missing source and time gracefully"
      (binding [sut/*color-enabled* false]
        (let [results [{:title "Headline" :url "https://n.com" :source nil :time nil :snippet nil :position 1}]
              output (with-out-str (#'sut/print-news-cards results))]
          (expect (.contains ^String output "Headline"))
          (expect (.contains ^String output "https://n.com")))))))

(defdescribe print-header-test
  "Unit tests for print-header"

  (describe "header formatting"
    (it "prints query and stats"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-header "test query" "About 100 results"))]
          (expect (.contains ^String output "test query"))
          (expect (.contains ^String output "About 100 results")))))

    (it "prints query without stats when nil"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-header "test query" nil))]
          (expect (.contains ^String output "test query")))))))

(defdescribe print-people-also-ask-test
  "Unit tests for print-people-also-ask"

  (describe "people also ask section"
    (it "prints section header and questions"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-people-also-ask ["Question 1?" "Question 2?"]))]
          (expect (.contains ^String output "People also ask"))
          (expect (.contains ^String output "Question 1?"))
          (expect (.contains ^String output "Question 2?")))))

    (it "prints nothing for empty list"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-people-also-ask []))]
          (expect (str/blank? output)))))

    (it "prints nothing for nil"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-people-also-ask nil))]
          (expect (str/blank? output)))))))

(defdescribe print-related-searches-test
  "Unit tests for print-related-searches"

  (describe "related searches section"
    (it "prints section header and searches joined by dots"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-related-searches ["foo" "bar" "baz"]))]
          (expect (.contains ^String output "Related searches"))
          (expect (.contains ^String output "foo"))
          (expect (.contains ^String output "bar"))
          (expect (.contains ^String output "baz"))
          ;; Middle dot separator
          (expect (.contains ^String output "\u00b7")))))

    (it "prints nothing for empty list"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-related-searches []))]
          (expect (str/blank? output)))))

    (it "prints nothing for nil"
      (binding [sut/*color-enabled* false]
        (let [output (with-out-str (#'sut/print-related-searches nil))]
          (expect (str/blank? output)))))))

;; =============================================================================
;; Block Detection & Diagnostics
;; =============================================================================

(defdescribe detect-block-js-test
  "Unit tests for detect-block-js constant"

  (describe "JS constant"
    (it "is a non-blank string"
      (expect (string? @#'sut/detect-block-js))
      (expect (not (str/blank? @#'sut/detect-block-js))))

    (it "contains sorry page check"
      (expect (.contains ^String @#'sut/detect-block-js "/sorry")))

    (it "contains captcha check"
      (expect (.contains ^String @#'sut/detect-block-js "#captcha")))

    (it "contains recaptcha check"
      (expect (.contains ^String @#'sut/detect-block-js ".g-recaptcha")))

    (it "contains unusual traffic check"
      (expect (.contains ^String @#'sut/detect-block-js "unusual traffic")))

    (it "returns empty string by default"
      (expect (.contains ^String @#'sut/detect-block-js "return ''")))))

(defdescribe print-diagnostics-test
  "Unit tests for print-diagnostics!"

  (describe "diagnostics output"
    (it "prints URL and Title header to stderr"
      (let [output (with-out-str
                     (binding [*err* *out*]
                       (with-redefs [page/url   (constantly "https://www.google.com/sorry")
                                     page/title (constantly "Sorry...")]
                         (#'sut/print-diagnostics! nil :sorry))))]
        (expect (.contains ^String output "Diagnostics:"))
        (expect (.contains ^String output "URL:   https://www.google.com/sorry"))
        (expect (.contains ^String output "Title: Sorry..."))
        (expect (.contains ^String output "Block: sorry"))))

    (it "omits block line when block-type is nil"
      (let [output (with-out-str
                     (binding [*err* *out*]
                       (with-redefs [page/url   (constantly "https://www.google.com/search?q=test")
                                     page/title (constantly "test - Google Search")]
                         (#'sut/print-diagnostics! nil nil))))]
        (expect (.contains ^String output "Diagnostics:"))
        (expect (.contains ^String output "URL:"))
        (expect (not (.contains ^String output "Block:")))))))

;; =============================================================================
;; --debug flag parsing
;; =============================================================================

(defdescribe parse-debug-flag-test
  "Unit tests for --debug flag in parse-search-args"

  (describe "debug flag"
    (it "defaults to false"
      (let [result (#'sut/parse-search-args ["test"])]
        (expect (false? (:debug? result)))))

    (it "parses --debug"
      (let [result (#'sut/parse-search-args ["test" "--debug"])]
        (expect (true? (:debug? result)))))

    (it "works with other flags"
      (let [result (#'sut/parse-search-args ["test" "--debug" "--json" "--images"])]
        (expect (true? (:debug? result)))
        (expect (true? (:json? result)))
        (expect (= :images (get-in result [:opts :type])))))))
