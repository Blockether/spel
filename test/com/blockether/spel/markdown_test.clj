(ns com.blockether.spel.markdown-test
  (:require
   [clojure.string]
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.markdown :as md]
   [com.blockether.spel.sci-env]))

(defdescribe markdown-table-test

  (describe "from-markdown-table"

    (describe "basic parsing"
      (it "parses a simple table"
        (let [table "| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |"
              result (md/from-markdown-table table)]
          (expect (= 2 (count result)))
          (expect (= "Alice" (get (first result) "Name")))
          (expect (= "30" (get (first result) "Age")))
          (expect (= "Bob" (get (second result) "Name")))))

      (it "parses table without leading/trailing pipes"
        (let [table "Name | Age\n---|---\nAlice | 30"
              result (md/from-markdown-table table)]
          (expect (= 1 (count result)))
          (expect (= "Alice" (get (first result) "Name")))))

      (it "handles extra whitespace in cells"
        (let [table "|  Name  |  Age  |\n|--------|-------|\n|  Alice  |  30  |"
              result (md/from-markdown-table table)]
          (expect (= "Alice" (get (first result) "Name")))
          (expect (= "30" (get (first result) "Age")))))

      (it "handles empty table (header only)"
        (let [table "| Name | Age |\n|------|-----|"
              result (md/from-markdown-table table)]
          (expect (= [] result))))

      (it "handles single row"
        (let [table "| Key | Value |\n|-----|-------|\n| foo | bar |"
              result (md/from-markdown-table table)]
          (expect (= 1 (count result)))
          (expect (= "foo" (get (first result) "Key"))))))

    (describe "keywordize option"
      (it "converts headers to keywords"
        (let [table "| Name | Age |\n|------|-----|\n| Alice | 30 |"
              result (md/from-markdown-table table {:keywordize true})]
          (expect (= "Alice" (:name (first result))))
          (expect (= "30" (:age (first result))))))

      (it "converts multi-word headers to kebab-case keywords"
        (let [table "| First Name | Last Name |\n|------------|----------|\n| Alice | Smith |"
              result (md/from-markdown-table table {:keywordize true})]
          (expect (= "Alice" (:first-name (first result))))
          (expect (= "Smith" (:last-name (first result)))))))

    (describe "edge cases"
      (it "handles rows with fewer cells than header"
        (let [table "| A | B | C |\n|---|---|---|\n| 1 | 2 |"
              result (md/from-markdown-table table)]
          (expect (= "1" (get (first result) "A")))
          (expect (= "2" (get (first result) "B")))
          (expect (= "" (get (first result) "C")))))

      (it "handles empty cells"
        (let [table "| A | B |\n|---|---|\n|  | val |"
              result (md/from-markdown-table table)]
          (expect (= "" (get (first result) "A")))
          (expect (= "val" (get (first result) "B")))))

      (it "handles alignment markers in separator"
        (let [table "| Left | Center | Right |\n|:-----|:------:|------:|\n| a | b | c |"
              result (md/from-markdown-table table)]
          (expect (= 1 (count result)))
          (expect (= "a" (get (first result) "Left")))))

      (it "skips blank lines"
        (let [table "| A | B |\n\n|---|---|\n\n| 1 | 2 |"
              result (md/from-markdown-table table)]
          (expect (= 1 (count result)))
          (expect (= "1" (get (first result) "A")))))))

  (describe "to-markdown-table"

    (describe "basic generation"
      (it "generates a simple table from string-keyed maps"
        (let [rows [{"Name" "Alice" "Age" "30"} {"Name" "Bob" "Age" "25"}]
              result (md/to-markdown-table rows)]
          (expect (string? result))
          (expect (clojure.string/includes? result "Alice"))
          (expect (clojure.string/includes? result "Bob"))
          (expect (clojure.string/includes? result "---"))))

      (it "generates a table from keyword-keyed maps"
        (let [rows [{:name "Alice" :age 30} {:name "Bob" :age 25}]
              result (md/to-markdown-table rows {:columns [:name :age]})]
          (expect (clojure.string/includes? result "Alice"))
          (expect (clojure.string/includes? result "30"))))

      (it "returns empty string for empty input"
        (expect (= "" (md/to-markdown-table [])))))

    (describe "options"
      (it "respects :columns ordering"
        (let [rows [{:b "2" :a "1"}]
              result (md/to-markdown-table rows {:columns [:a :b]})
              lines (clojure.string/split-lines result)]
          ;; Header should have a before b
          (expect (< (clojure.string/index-of (first lines) "a")
                    (clojure.string/index-of (first lines) "b")))))

      (it "uses custom :headers labels"
        (let [rows [{:n "Alice"}]
              result (md/to-markdown-table rows {:columns [:n] :headers {:n "Full Name"}})]
          (expect (clojure.string/includes? result "Full Name"))))

      (it "applies right alignment"
        (let [rows [{:val "123"}]
              result (md/to-markdown-table rows {:columns [:val] :align {:val :right}})
              lines (clojure.string/split-lines result)]
          ;; Separator should end with : for right align
          (expect (re-find #"-+:" (second lines))))))

    (describe "round-trip"
      (it "round-trips through from → to → from"
        (let [original "| Name | Score |\n|------|-------|\n| Alice | 95 |\n| Bob | 87 |"
              parsed (md/from-markdown-table original)
              regenerated (md/to-markdown-table parsed {:columns ["Name" "Score"]})
              reparsed (md/from-markdown-table regenerated)]
          (expect (= parsed reparsed))))))

  (describe "SCI eval integration"
    (it "markdown/from-markdown-table is accessible in SCI"
      (let [ctx (com.blockether.spel.sci-env/create-sci-ctx)
            result (com.blockether.spel.sci-env/eval-string ctx
                     "(markdown/from-markdown-table \"| A | B |\\n|---|---|\\n| 1 | 2 |\")")]
        (expect (= 1 (count result)))
        (expect (= "1" (get (first result) "A")))))

    (it "markdown/to-markdown-table is accessible in SCI"
      (let [ctx (com.blockether.spel.sci-env/create-sci-ctx)
            result (com.blockether.spel.sci-env/eval-string ctx
                     "(markdown/to-markdown-table [{\"X\" \"hello\"}])")]
        (expect (string? result))
        (expect (clojure.string/includes? result "hello"))))

    (it "require com.blockether.spel.markdown works in SCI"
      (let [ctx (com.blockether.spel.sci-env/create-sci-ctx)
            result (com.blockether.spel.sci-env/eval-string ctx
                     "(require '[com.blockether.spel.markdown :as md]) (md/from-markdown-table \"| K |\\n|---|\\n| v |\")")]
        (expect (= "v" (get (first result) "K")))))))
