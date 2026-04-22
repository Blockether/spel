(ns com.blockether.spel.helpers-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe expect it]]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.markdownify :as markdownify]
   [com.blockether.spel.page :as page]))

(defdescribe markdownify-test
  "Tests for HTML to Markdown conversion"

  (it "converts headings and paragraphs"
    (core/with-testing-page [pg]
      (let [md (markdownify/html->markdown pg "<html><head><title>Sample</title></head><body><h1>Hello</h1><p>World</p></body></html>")]
        (expect (str/includes? md "# Sample"))
        (expect (str/includes? md "# Hello"))
        (expect (str/includes? md "World")))))

  (it "converts links and lists"
    (core/with-testing-page [pg]
      (let [md (markdownify/html->markdown pg "<ul><li><a href='https://example.com'>Example</a></li></ul>" {:title? false})]
        (expect (str/includes? md "- [Example](https://example.com)")))))

  (it "converts tables"
    (core/with-testing-page [pg]
      (let [md (markdownify/html->markdown pg "<table><tr><th>Name</th><th>Age</th></tr><tr><td>Alice</td><td>30</td></tr></table>" {:title? false})]
        (expect (str/includes? md "| Name | Age |"))
        (expect (str/includes? md "| --- | --- |"))
        (expect (str/includes? md "| Alice | 30 |")))))

  (it "separates layout table rows under non-semantic wrappers"
    (core/with-testing-page [pg]
      (let [md (markdownify/html->markdown pg "<html><body><center><table><tr><td><a href='https://example.com/1'>First</a></td></tr><tr><td><a href='https://example.com/2'>Second</a></td></tr></table></center></body></html>" {:title? false})]
        (expect (str/includes? md "[First](https://example.com/1)\n\n[Second](https://example.com/2)")))))

  (it "uses a11y extraction by default for current page content"
    (core/with-testing-page [pg]
      (page/set-content! pg "<html><head><title>A11y Page</title></head><body><center><table><tr><td><a href='https://example.com/1'>First</a></td></tr><tr><td><a href='https://example.com/2'>Second</a></td></tr></table></center></body></html>")
      (let [md (markdownify/html->markdown pg (page/content pg) {:title? false})]
        (expect (some? (re-find #"\[First\]\(https://example.com/1\)\n+\[Second\]\(https://example.com/2\)" md)))
        (expect (not (str/includes? md "[First](https://example.com/1) [Second](https://example.com/2)")))))))
