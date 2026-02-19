(ns com.blockether.spel.ct.markdown-test
  "Markdown table tests using standard clojure.test.

   Pure unit tests (no browser) demonstrating that clojure.test works
   for non-browser tests alongside Lazytest tests."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.markdown :as md]))

(deftest from-markdown-table-test
  (testing "basic parsing"
    (let [table "| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |"
          result (md/from-markdown-table table)]
      (is (= 2 (count result)))
      (is (= "Alice" (get (first result) "Name")))
      (is (= "30" (get (first result) "Age")))
      (is (= "Bob" (get (second result) "Name")))))

  (testing "empty table (header only)"
    (let [table "| Name | Age |\n|------|-----|"
          result (md/from-markdown-table table)]
      (is (= [] result))))

  (testing "handles extra whitespace in cells"
    (let [table "|  Name  |  Age  |\n|--------|-------|\n|  Alice  |  30  |"
          result (md/from-markdown-table table)]
      (is (= "Alice" (get (first result) "Name")))
      (is (= "30" (get (first result) "Age"))))))

(deftest from-markdown-table-keywordize-test
  (testing "converts headers to keywords"
    (let [table "| Name | Age |\n|------|-----|\n| Alice | 30 |"
          result (md/from-markdown-table table {:keywordize true})]
      (is (= "Alice" (:name (first result))))
      (is (= "30" (:age (first result))))))

  (testing "converts multi-word headers to kebab-case keywords"
    (let [table "| First Name | Last Name |\n|------------|----------|\n| Alice | Smith |"
          result (md/from-markdown-table table {:keywordize true})]
      (is (= "Alice" (:first-name (first result))))
      (is (= "Smith" (:last-name (first result)))))))

(deftest to-markdown-table-test
  (testing "generates table from string-keyed maps"
    (let [rows [{"Name" "Alice" "Age" "30"} {"Name" "Bob" "Age" "25"}]
          result (md/to-markdown-table rows)]
      (is (string? result))
      (is (str/includes? result "Alice"))
      (is (str/includes? result "Bob"))
      (is (str/includes? result "---"))))

  (testing "generates table from keyword-keyed maps"
    (let [rows [{:name "Alice" :age 30} {:name "Bob" :age 25}]
          result (md/to-markdown-table rows {:columns [:name :age]})]
      (is (str/includes? result "Alice"))
      (is (str/includes? result "30"))))

  (testing "returns empty string for empty input"
    (is (= "" (md/to-markdown-table [])))))

(deftest round-trip-test
  (testing "from -> to -> from preserves data"
    (let [original "| Name | Score |\n|------|-------|\n| Alice | 95 |\n| Bob | 87 |"
          parsed (md/from-markdown-table original)
          regenerated (md/to-markdown-table parsed {:columns ["Name" "Score"]})
          reparsed (md/from-markdown-table regenerated)]
      (is (= parsed reparsed)))))
