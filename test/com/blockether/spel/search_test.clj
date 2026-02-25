(ns com.blockether.spel.search-test
  "Tests for com.blockether.spel.search namespace.

   Unit tests cover URL building, CLI arg parsing, and SCI namespace
   availability — no browser needed. Integration tests use a real
   Playwright browser session."
  (:require
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
