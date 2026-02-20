(ns com.blockether.spel.markdown
  "Parse and generate GitHub-flavored markdown tables.

   Converts between markdown table strings and Clojure data:
   - `from-markdown-table` : markdown string → vector of maps
   - `to-markdown-table`   : vector of maps → markdown string

   Useful in --eval scripts for processing tabular data from web pages,
   API responses, or LLM output."
  (:require
   [clojure.string :as str]))

;; =============================================================================
;; Parsing (markdown → data)
;; =============================================================================

(defn- parse-row
  "Parses a single markdown table row into a vector of trimmed cell strings.
   Handles leading/trailing pipes and trims whitespace from each cell."
  [line]
  (let [trimmed (str/trim line)
        ;; Strip leading and trailing pipes
        stripped (cond-> trimmed
                   (str/starts-with? trimmed "|") (subs 1)
                   true                           (#(if (str/ends-with? % "|")
                                                      (subs % 0 (dec (count %)))
                                                      %)))]
    (mapv str/trim (str/split stripped #"\|" -1))))

(defn- separator-row?
  "Returns true if the row is a separator (e.g. |---|---|)."
  [line]
  (boolean (re-matches #"\s*\|?[\s:]*-[-\s:|]*\|?\s*" line)))

(defn from-markdown-table
  "Parses a markdown table string into a vector of maps.

   Each map has keys from the header row (as strings by default,
   or as keywords when :keywordize true).

   Options:
     :keywordize  - Convert header names to keywords (default: false)

   Examples:
     (from-markdown-table \"| Name | Age |\\n|---|---|\\n| Alice | 30 |\")
     ;; => [{\"Name\" \"Alice\", \"Age\" \"30\"}]

     (from-markdown-table \"| Name | Age |\\n|---|---|\\n| Alice | 30 |\" {:keywordize true})
     ;; => [{:name \"Alice\", :age \"30\"}]"
  ([s] (from-markdown-table s {}))
  ([s opts]
   (let [lines (->> (str/split-lines s)
                 (map str/trim)
                 (remove str/blank?))
         ;; First non-empty line is the header
         header-cells (parse-row (first lines))
         ;; Remaining lines, skipping separator
         data-lines (remove separator-row? (rest lines))
         ;; Build key functions
         make-key (if (:keywordize opts)
                    (fn [h] (-> h str/trim str/lower-case (str/replace #"\s+" "-") keyword))
                    identity)]
     (mapv (fn [line]
             (let [cells (parse-row line)
                   ;; Pad with empty strings if row has fewer cells
                   padded (concat cells (repeat (- (count header-cells) (count cells)) ""))]
               (zipmap (map make-key header-cells)
                 (take (count header-cells) padded))))
       data-lines))))

;; =============================================================================
;; Generation (data → markdown)
;; =============================================================================

(defn- cell-width
  "Returns the display width of a cell value as a string."
  [v]
  (count (str v)))

(defn to-markdown-table
  "Converts a vector of maps to a markdown table string.

   Options:
     :columns  - Explicit column order (vector of keys). Default: sorted keys from first map.
     :headers  - Custom header labels (map of key → label string). Default: key as string.
     :align    - Column alignment (map of key → :left/:right/:center). Default: :left.

   Examples:
     (to-markdown-table [{\"Name\" \"Alice\" \"Age\" \"30\"} {\"Name\" \"Bob\" \"Age\" \"25\"}])
     ;; => \"| Age | Name |\\n|-----|------|\\n| 30  | Alice |\\n| 25  | Bob  |\"

     (to-markdown-table [{:name \"Alice\" :age 30}] {:columns [:name :age]})
     ;; => \"| name | age |\\n|------|-----|\\n| Alice | 30 |\"

     (to-markdown-table [{:name \"Alice\" :age 30}]
       {:columns [:name :age]
        :headers {:name \"Name\" :age \"Age\"}
        :align   {:age :right}})
     ;; => \"| Name | Age |\\n|------|----:|\\n| Alice | 30 |\""
  ([rows] (to-markdown-table rows {}))
  ([rows opts]
   (if (empty? rows)
     ""
     (let [cols (or (:columns opts)
                  (sort-by str (keys (first rows))))
           headers-map (or (:headers opts) {})
           align-map (or (:align opts) {})
           ;; Header labels
           header-labels (mapv #(str (get headers-map % (str (if (keyword? %) (name %) %)))) cols)
           ;; Convert all cell values to strings
           str-rows (mapv (fn [row]
                            (mapv #(str (get row % "")) cols))
                      rows)
           ;; Calculate column widths (min 3 for separator)
           col-widths (mapv (fn [i]
                              (apply max 3
                                (cell-width (nth header-labels i))
                                (map #(cell-width (nth % i)) str-rows)))
                        (range (count cols)))
           ;; Format a row with padding
           fmt-row (fn [cells]
                     (str "| "
                       (str/join " | "
                         (map-indexed (fn [i cell]
                                        (let [w (long (nth col-widths i))
                                              pad (- w (long (cell-width cell)))]
                                          (case (get align-map (nth cols i) :left)
                                            :right (str (apply str (repeat pad \space)) cell)
                                            :center (let [l (long (quot pad 2))
                                                          r (long (- pad l))]
                                                      (str (apply str (repeat l \space))
                                                        cell
                                                        (apply str (repeat r \space))))
                                           ;; :left (default)
                                            (str cell (apply str (repeat pad \space))))))
                           cells))
                       " |"))
           ;; Separator row
           separator (str "| "
                       (str/join " | "
                         (map-indexed (fn [i _]
                                        (let [w (long (nth col-widths i))
                                              dashes (apply str (repeat w \-))]
                                          (case (get align-map (nth cols i) :left)
                                            :right  (str (subs dashes 0 (dec w)) ":")
                                            :center (str ":" (subs dashes 0 (- w 2)) ":")
                                            ;; :left
                                            dashes)))
                           cols))
                       " |")]
       (str/join "\n"
         (concat [(fmt-row header-labels)
                  separator]
           (map fmt-row str-rows)))))))
