(ns com.blockether.spel.annotate-test
  "Tests for the annotate namespace.

   Unit tests verify JS generation logic, annotation filtering, and
   containment dedup (no browser needed).
   Integration tests run against example.org using Playwright."
  (:require
   [com.blockether.spel.annotate :as sut]
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.allure :refer [around defdescribe describe expect it]])
  (:import
   [java.io ByteArrayInputStream File]
   [javax.imageio ImageIO]))

;; =============================================================================
;; Helpers
;; =============================================================================

(def ^:private test-refs
  {"e1" {:role "button" :name "Submit" :bbox {:x 10 :y 20 :width 60 :height 25}}
   "e2" {:role "link"   :name "Home"   :bbox {:x 5  :y 50 :width 40 :height 15}}
   "e3" {:role "heading" :name "Title" :bbox {:x 10 :y 5  :width 80 :height 20}}})

(def ^:private build-inject-js
  "Access private build-inject-js for unit testing."
  #'sut/build-inject-js)

;; =============================================================================
;; Unit Tests — bundled typography
;; =============================================================================

(defdescribe report-fonts-test
  "The report document ships its fonts instead of fetching them"

  (describe "report->html typography"

    (it "bundles every face as a data URI and never links a font CDN"
      (let [html (sut/report->html [{:type :text :text "hello"}] {:title "Fonts"})]
        (expect (not (str/includes? html "fonts.googleapis.com")))
        (expect (not (str/includes? html "fonts.gstatic.com")))
        (expect (not (str/includes? html "<link")))
        (expect (= 4 (count (re-seq #"data:font/woff2;base64," html))))
        (expect (str/includes? html "--font-body:'Inter Variable'"))
        (expect (str/includes? html "--font-mono:'JetBrains Mono Variable'"))))))

;; =============================================================================
;; Unit Tests — JS overlay generation
;; =============================================================================

(defdescribe build-inject-js-test
  "Unit tests for JS overlay injection script generation"
  (around [f] (core/with-testing-browser (f)))

  (describe "basic JS generation"
    (it "produces a non-empty JS string"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (string? js))
                                      (expect (pos? (count js))))))

    (it "contains the root container setup"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (.contains ^String js "data-spel-annotate"))
                                      (expect (.contains ^String js "z-index:2147483647"))
                                      (expect (.contains ^String js "pointer-events:none")))))

    (it "labels carry the mark number only — never the ref id, role or size"

      ;; Regression: the label spelled "e4khqh textbox 153x21", ~150px of ink for
      ;; a 153x21 control, so on a form every badge covered the neighbouring
      ;; field's own label. The mark is the number; the table carries the rest.
      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (.contains ^String js "mark.textContent = '1'"))
                                      (expect (.contains ^String js "mark.textContent = '3'"))
                                      (expect (not (.contains ^String js "e1 button")))
                                      (expect (not (.contains ^String js "e2 link")))
                                      (expect (not (.contains ^String js "Math.round(w)"))))))

    (it "numbers marks in reading order, exactly as refs->entries reports them"

      (core/with-testing-page [_pg] (let [entries (sut/refs->entries test-refs)]
                                      (expect (= [1 2 3] (mapv :mark entries)))
                                      (expect (= ["e3" "e1" "e2"] (mapv :ref entries))))))

    (it "stamps the ref on the drawn mark so a click target stays reachable"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (.contains ^String js "data-spel-ref")))))

    (it "uses the Blockether brand palette (amber box, ink chip)"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
        ;; box border → amber accent
                                      (expect (.contains ^String js "#ffc420"))
        ;; mark chip → ink background
                                      (expect (.contains ^String js "#262626"))
        ;; mark chip → JetBrains Mono
                                      (expect (.contains ^String js "JetBrains Mono"))))))

  (describe "mark placement"
    (it "measures the mark in the DOM and takes the first free slot"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (.contains ^String js "function placeMark"))
                                      (expect (.contains ^String js "function isFree"))
                                      (expect (.contains ^String js "mark.offsetWidth"))
                                      (expect (.contains ^String js "taken.push(spot)")))))

    (it "keeps the mark off an isolated control and parks it in the margin beside it"

      (core/with-testing-page [pg]
        (page/set-content! pg "<html><body style='margin:0'><button id='b' style='position:absolute;left:100px;top:100px;width:300px;height:200px'>Save</button></body></html>")
        (let [snap (snapshot/capture-snapshot pg)
              _    (sut/inject-overlays! pg (:refs snap))
              geo  (page/evaluate pg
                     (str "(function(){"
                       "var m = document.querySelector('[data-spel-annotate=\"mark\"]').getBoundingClientRect();"
                       "var b = document.getElementById('b').getBoundingClientRect();"
                       "var apart = m.right <= b.left + 1 || m.left >= b.right - 1 || m.bottom <= b.top + 1 || m.top >= b.bottom - 1;"
                       "var near = Math.max(b.left - m.right, m.left - b.right, b.top - m.bottom, m.top - b.bottom) <= 4;"
                       "return [apart ? 1 : 0, near ? 1 : 0, Math.round(m.width)];})()"))
              [apart near width] (mapv long geo)]
          (expect (= 1 apart))
          (expect (= 1 near))
          ;; A mark is a number, not a caption: it stays narrow enough to park.
          (expect (< width 40))
          (sut/remove-overlays! pg))))

    (it "clamps every candidate inside the document"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {})]
                                      (expect (.contains ^String js "docW - mw"))
                                      (expect (.contains ^String js "docH - mh"))))))

  (describe "option toggles"
    (it "excludes boxes when :show-boxes false"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {:show-boxes false})]
        ;; Should still have marks, but no box outline divs
                                      (expect (.contains ^String js "mark.textContent"))
                                      (expect (not (.contains ^String js "border:2px solid"))))))

    (it "excludes marks when :show-badges false"

      (core/with-testing-page [_pg] (let [js (build-inject-js test-refs {:show-badges false})]
        ;; No mark elements
                                      (expect (not (.contains ^String js "mark.textContent")))
        ;; But should still have boxes with an outline
                                      (expect (.contains ^String js "border:2px solid")))))

    (it "adds dimensions to the mark only when :show-dimensions true"

      (core/with-testing-page [_pg] (let [off (build-inject-js test-refs {})
                                          on  (build-inject-js test-refs {:show-dimensions true})]
                                      (expect (not (.contains ^String off "Math.round(w)")))
                                      (expect (.contains ^String on "Math.round(w)"))
                                      (expect (.contains ^String on "Math.round(h)")))))

    (it "generates minimal JS with all options disabled"

      (core/with-testing-page [_pg] (let [js-all  (build-inject-js test-refs {})
                                          js-none (build-inject-js test-refs {:show-boxes false
                                                                              :show-badges false
                                                                              :show-dimensions false})]
        ;; All-disabled should be smaller — only the container
                                      (expect (< (count js-none) (count js-all)))))))

  (describe "edge cases"
    (it "handles empty refs map"

      (core/with-testing-page [_pg] (let [js (build-inject-js {} {})]
                                      (expect (string? js))
        ;; Should still set up the container
                                      (expect (.contains ^String js "data-spel-annotate")))))

    (it "handles single ref"

      (core/with-testing-page [_pg] (let [js (build-inject-js {"e1" {:role "button" :name "OK"
                                                                     :bbox {:x 10 :y 10 :width 30 :height 20}}} {})]
                                      (expect (.contains ^String js "mark.textContent = '1'"))
                                      (expect (.contains ^String js "data-pw-ref")))))

    (it "skips refs with zero-size bbox"

      (core/with-testing-page [_pg] (let [js (build-inject-js {"e1" {:role "button" :name "Hidden"
                                                                     :bbox {:x 0 :y 0 :width 0 :height 0}}} {})]
        ;; Zero-size bbox should be filtered out
                                      (expect (not (.contains ^String js "data-pw-ref=\"e1\""))))))))

;; =============================================================================
;; Unit Tests — annotation filtering
;; =============================================================================

(defdescribe filter-annotatable-test
  "Unit tests for structural role filtering and containment dedup"
  (around [f] (core/with-testing-browser (f)))

  (describe "structural role filtering"
    (it "keeps interactive roles (button, link, textbox)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button" :bbox {:x 0 :y 0 :width 50 :height 20}}
                                                "e2" {:role "link"   :bbox {:x 0 :y 30 :width 50 :height 20}}
                                                "e3" {:role "textbox" :bbox {:x 0 :y 60 :width 50 :height 20}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 3 (count result)))
                                      (expect (contains? result "e1"))
                                      (expect (contains? result "e2"))
                                      (expect (contains? result "e3")))))

    (it "keeps content anchors (heading, img)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "heading" :bbox {:x 0 :y 0 :width 100 :height 30}}
                                                "e2" {:role "img"     :bbox {:x 0 :y 40 :width 100 :height 80}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 2 (count result))))))

    (it "removes pure structural roles (list, region)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "list"   :bbox {:x 0 :y 30 :width 300 :height 100}}
                                                "e2" {:role "region" :bbox {:x 0 :y 0 :width 500 :height 500}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 0 (count result))))))

    (it "keeps listitem role (annotatable for list content)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "listitem" :bbox {:x 0 :y 30 :width 300 :height 25}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 1 (count result)))
                                      (expect (contains? result "e1")))))

    (it "keeps text containers (paragraph, span)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "paragraph" :bbox {:x 0 :y 0 :width 300 :height 20}}
                                                "e2" {:role "span"      :bbox {:x 0 :y 30 :width 100 :height 15}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 2 (count result))))))

    (it "keeps text role (generic divs with text content)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "text" :bbox {:x 0 :y 0 :width 200 :height 20}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 1 (count result)))
                                      (expect (contains? result "e1")))))

    (it "paragraph wrapping link is removed by containment dedup"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "paragraph" :bbox {:x 0 :y 0 :width 300 :height 20}}
                                                "e2" {:role "link"      :bbox {:x 5 :y 2 :width 60  :height 16}}
                                                "e3" {:role "heading"   :bbox {:x 0 :y 30 :width 300 :height 30}}}
                                          result (sut/filter-annotatable refs)]
        ;; e1 paragraph contains e2 link → e1 suppressed by containment dedup
                                      (expect (= 2 (count result)))
                                      (expect (contains? result "e2"))
                                      (expect (contains? result "e3"))
                                      (expect (not (contains? result "e1")))))))

  (describe "containment dedup"
    (it "removes container whose bbox fully wraps a child"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "navigation" :bbox {:x 0 :y 0 :width 500 :height 100}}
                                                "e2" {:role "link"       :bbox {:x 10 :y 10 :width 60 :height 20}}}
                                          result (sut/filter-annotatable refs)]
        ;; navigation contains link → navigation suppressed
                                      (expect (= 1 (count result)))
                                      (expect (contains? result "e2")))))

    (it "keeps both when neither contains the other"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button" :bbox {:x 0  :y 0  :width 60 :height 20}}
                                                "e2" {:role "link"   :bbox {:x 80 :y 0  :width 60 :height 20}}}
                                          result (sut/filter-annotatable refs)]
                                      (expect (= 2 (count result))))))

    (it "handles nested containment (grandparent → parent → child)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "navigation" :bbox {:x 0 :y 0 :width 500 :height 100}}
                                                "e2" {:role "dialog"     :bbox {:x 5 :y 5 :width 200 :height 50}}
                                                "e3" {:role "button"     :bbox {:x 10 :y 10 :width 60 :height 20}}}
                                          result (sut/filter-annotatable refs)]
        ;; e1 contains e2 and e3 → e1 removed
        ;; e2 contains e3 → e2 removed
        ;; Only e3 (button) survives
                                      (expect (= 1 (count result)))
                                      (expect (contains? result "e3")))))

    (it "handles identical bboxes by keeping both elements"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button"  :bbox {:x 10 :y 10 :width 60 :height 20}}
                                                "e2" {:role "heading" :bbox {:x 10 :y 10 :width 60 :height 20}}}
                                          result (sut/filter-annotatable refs)]
        ;; Same bbox → neither is a "container" → both kept
        ;; (prevents <a><img> same-size suppression bug)
                                      (expect (= 2 (count result)))
                                      (expect (contains? result "e1"))
                                      (expect (contains? result "e2")))))

    (it "keeps mixed-content container when it wraps a child (has own text)"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "paragraph" :mixed true :bbox {:x 0 :y 0 :width 300 :height 20}}
                                                "e2" {:role "text"      :bbox {:x 5 :y 2 :width 60  :height 16}}}
                                          result (sut/filter-annotatable refs)]
        ;; e1 paragraph has :mixed true (own text besides child) → not suppressed
                                      (expect (= 2 (count result)))
                                      (expect (contains? result "e1"))
                                      (expect (contains? result "e2")))))

    (it "suppresses non-mixed container even if same role"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "text" :bbox {:x 0 :y 0 :width 300 :height 20}}
                                                "e2" {:role "text" :bbox {:x 5 :y 2 :width 60  :height 16}}}
                                          result (sut/filter-annotatable refs)]
        ;; e1 has no :mixed flag → pure container → suppressed
                                      (expect (= 1 (count result)))
                                      (expect (contains? result "e2")))))

    (it "zero-area child does not suppress visible container"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "link" :bbox {:x 63 :y 0 :width 0 :height 0}}
                                                "e2" {:role "img"  :bbox {:x 63 :y 0 :width 132 :height 70}}}
                                          result (sut/filter-annotatable refs)]
        ;; e1 is a 0×0 hidden link inside e2 (a CSS background-image logo)
        ;; e2 must NOT be suppressed — the zero-area ghost shouldn't trigger
        ;; container dedup
                                      (expect (contains? result "e2")))))

    (it "handles empty refs"

      (core/with-testing-page [_pg] (expect (= {} (sut/filter-annotatable {})))))

    (it "handles single ref"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button" :bbox {:x 0 :y 0 :width 50 :height 20}}}]
                                      (expect (= refs (sut/filter-annotatable refs)))))))

  (describe "combined filtering (structural + containment)"
    (it "filters example.org-like structure: heading + paragraphs + link"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "heading"   :bbox {:x 0 :y 50 :width 768 :height 45}}
                                                "e2" {:role "paragraph" :bbox {:x 0 :y 97 :width 768 :height 20}}
                  ;; e3 paragraph wraps e4 link (link bbox inside paragraph bbox)
                                                "e3" {:role "paragraph" :bbox {:x 0 :y 120 :width 768 :height 25}}
                                                "e4" {:role "link"      :bbox {:x 2 :y 122 :width 80  :height 19}}}
                                          result (sut/filter-annotatable refs)]
        ;; e3 paragraph contains e4 link → e3 suppressed by containment dedup
        ;; heading, standalone paragraph (e2), and link all kept
                                      (expect (= 3 (count result)))
                                      (expect (contains? result "e1"))
                                      (expect (contains? result "e2"))
                                      (expect (contains? result "e4")))))))

;; =============================================================================
;; Unit Tests — viewport visibility filtering
;; =============================================================================

(defdescribe visible-refs-test
  "Unit tests for viewport-based ref filtering"
  (around [f] (core/with-testing-browser (f)))

  (describe "viewport filtering"
    (it "includes fully visible elements"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y 20 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 1 (count vis)))
                                      (expect (contains? vis "e1")))))

    (it "includes partially visible elements (overlapping right edge)"

      (core/with-testing-page [_pg] (let [vp   {:width 100 :height 100}
                                          refs {"e1" {:role "button" :bbox {:x 80 :y 10 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 1 (count vis))))))

    (it "includes partially visible elements (overlapping bottom edge)"

      (core/with-testing-page [_pg] (let [vp   {:width 100 :height 100}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y 80 :width 60 :height 50}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 1 (count vis))))))

    (it "excludes elements fully below viewport"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y 800 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 0 (count vis))))))

    (it "excludes elements fully to the right of viewport"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 1100 :y 10 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 0 (count vis))))))

    (it "excludes elements fully above viewport (negative y + height)"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y -50 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 0 (count vis))))))

    (it "excludes elements fully to the left of viewport"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x -100 :y 10 :width 60 :height 25}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 0 (count vis))))))

    (it "filters mixed visible and offscreen refs"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y 20 :width 60 :height 25}}
                                                "e2" {:role "link"   :bbox {:x 10 :y 800 :width 40 :height 15}}
                                                "e3" {:role "heading" :bbox {:x 10 :y 5 :width 80 :height 20}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 2 (count vis)))
                                      (expect (contains? vis "e1"))
                                      (expect (contains? vis "e3"))
                                      (expect (not (contains? vis "e2"))))))

    (it "excludes zero-size bbox"

      (core/with-testing-page [_pg] (let [vp   {:width 1024 :height 768}
                                          refs {"e1" {:role "button" :bbox {:x 10 :y 10 :width 0 :height 0}}}
                                          vis  (sut/visible-refs vp refs)]
                                      (expect (= 0 (count vis))))))

    (it "handles empty refs"

      (core/with-testing-page [_pg] (let [vp  {:width 1024 :height 768}
                                          vis (sut/visible-refs vp {})]
                                      (expect (= 0 (count vis))))))))

;; =============================================================================
;; Unit Tests — scope filtering
;; =============================================================================

(defdescribe scope-helpers-test
  "Unit tests for scope ref resolution helpers"
  (around [f] (core/with-testing-browser (f)))

  (describe "ref-scope? detection"
    (it "recognizes @e1 as a ref scope"

      (core/with-testing-page [_pg] (expect (true? (#'sut/ref-scope? "@e1")))))

    (it "rejects bare ref without @ prefix"

      (core/with-testing-page [_pg] (expect (false? (#'sut/ref-scope? "e1")))))

    (it "rejects e123 without @ prefix"

      (core/with-testing-page [_pg] (expect (false? (#'sut/ref-scope? "e123")))))

    (it "rejects CSS selectors"

      (core/with-testing-page [_pg] (expect (false? (#'sut/ref-scope? "#main")))
        (expect (false? (#'sut/ref-scope? ".container")))
        (expect (false? (#'sut/ref-scope? "div"))))))

  (describe "resolve-scope"
    (it "converts @e1 to data-pw-ref selector"

      (core/with-testing-page [_pg] (expect (= "[data-pw-ref=\"e1\"]" (#'sut/resolve-scope "@e1")))))

    (it "passes bare e1 through as CSS (no @ prefix)"

      (core/with-testing-page [_pg] (expect (= "e1" (#'sut/resolve-scope "e1")))))

    (it "passes CSS selectors through unchanged"

      (core/with-testing-page [_pg] (expect (= "#main" (#'sut/resolve-scope "#main")))
        (expect (= ".container" (#'sut/resolve-scope ".container")))))))

;; =============================================================================
;; Integration Tests — annotated-screenshot
;; =============================================================================

(defdescribe annotated-screenshot-integration-test
  "Integration tests with real Playwright screenshots"
  (around [f] (core/with-testing-browser (f)))

  (describe "annotated-screenshot with real page"

    (it "returns {:bytes :annotated} with a valid PNG and ref entries"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap   (snapshot/capture-snapshot pg)
              result (sut/annotated-screenshot pg (:refs snap))
              ^bytes png (:bytes result)]
          (expect (bytes? png))
          (expect (pos? (alength png)))
          ;; :annotated is the LLM-friendly ref→label mapping
          (expect (map? (:annotated result)))
          (expect (number? (get-in result [:annotated :count])))
          (expect (vector? (get-in result [:annotated :entries])))
          ;; Entries carry ref + role + bbox so downstream tooling can map
          ;; visual labels back to @refs
          (when (pos? (get-in result [:annotated :count]))
            (let [e (first (get-in result [:annotated :entries]))]
              (expect (string? (:ref e)))
              (expect (string? (:role e)))
              (expect (map? (:bbox e)))))
          ;; Verify it's a valid PNG
          (let [img (ImageIO/read (ByteArrayInputStream. png))]
            (expect (some? img))
            (expect (pos? (.getWidth img)))
            (expect (pos? (.getHeight img)))))))

    (it "entries are sorted top→down, left→right"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap    (snapshot/capture-snapshot pg)
              result  (sut/annotated-screenshot pg (:refs snap))
              entries (get-in result [:annotated :entries])]
          ;; y coordinate is non-decreasing in reading order
          (doseq [[a b] (partition 2 1 entries)]
            (let [ya (double (or (get-in a [:bbox :y]) 0))
                  yb (double (or (get-in b [:bbox :y]) 0))]
              (expect (<= ya yb)))))))

    (it "marks never overlap each other on a dense grid of tiny controls"

      ;; Regression: every label was drawn at its own box's top-left, so a row of
      ;; 13x13 checkboxes piled its labels into one unreadable stack.
      (core/with-testing-page [pg]
        (page/set-content! pg (str "<html><body style='margin:0'>"
                                (apply str (for [i (range 40)]
                                             (str "<label style='display:inline-block;width:60px'>"
                                               "<input type='checkbox' id='c" i "'></label>")))
                                "</body></html>"))
        (let [snap  (snapshot/capture-snapshot pg)
              _     (sut/inject-overlays! pg (:refs snap))
              rects (page/evaluate pg
                      (str "Array.from(document.querySelectorAll('[data-spel-annotate=\"mark\"]'))"
                        ".map(function(e){var r = e.getBoundingClientRect();"
                        "return [r.left, r.top, r.right, r.bottom];})"))
              rs    (mapv (fn [r] (mapv double r)) rects)
              clash (for [i (range (count rs))
                          j (range (inc i) (count rs))
                          :let [[l1 t1 r1 b1] (nth rs i)
                                [l2 t2 r2 b2] (nth rs j)]
                          :when (and (< l1 r2) (< l2 r1) (< t1 b2) (< t2 b1))]
                      [i j])]
          (expect (> (count rs) 10))
          (expect (empty? clash))
          (sut/remove-overlays! pg))))

    (it "a mark never lands on the page's own text in a tight list"

      ;; Regression: marks were only kept off each other, so on a link list with
      ;; no vertical gap every number was dropped onto the first letters of a
      ;; title — the picture named the rows it had made unreadable.
      (core/with-testing-page [pg]
        (page/set-content! pg (str "<html><body style='margin:0;font:13px monospace;width:900px'>"
                                (apply str (for [i (range 25)]
                                             (str "<div style='line-height:14px'>"
                                               "<a href='#a" i "'>Story number " i " with a reasonably long title</a> "
                                               "<span>(example.com)</span></div>")))
                                "</body></html>"))
        (let [snap    (snapshot/capture-snapshot pg)
              _       (sut/inject-overlays! pg (:refs snap))
              covered (page/evaluate pg
                        (str "(function(){"
                          "var marks = Array.from(document.querySelectorAll('[data-spel-annotate=\"mark\"]'))"
                          "  .map(function(e){return e.getBoundingClientRect();});"
                          "var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n, texts = [];"
                          "while ((n = tw.nextNode())) {"
                          "  if (!n.nodeValue.trim()) continue;"
                          "  var rg = document.createRange(); rg.selectNode(n);"
                          "  var rs = rg.getClientRects();"
                          "  for (var i = 0; i < rs.length; i++) if (rs[i].width > 0 && rs[i].height > 0) texts.push(rs[i]);"
                          "}"
                          "var hit = 0;"
                          "for (var m = 0; m < marks.length; m++) {"
                          "  var a = marks[m];"
                          "  for (var t = 0; t < texts.length; t++) {"
                          "    var b = texts[t];"
                          "    if (a.left < b.right - 1 && b.left + 1 < a.right && a.top < b.bottom - 1 && b.top + 1 < a.bottom) { hit++; break; }"
                          "  }"
                          "}"
                          "return [marks.length, hit];})()"))
              [n hit] (mapv long covered)]
          (expect (> n 10))
          (expect (zero? hit))
          (sut/remove-overlays! pg))))

    (it "annotated is larger than raw screenshot"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap             (snapshot/capture-snapshot pg)
              raw              (page/screenshot pg)
              ^bytes annotated (:bytes (sut/annotated-screenshot pg (:refs snap)))]
          (expect (> (alength annotated) (alength raw))))))))

;; =============================================================================
;; Integration Tests — save-annotated-screenshot!
;; =============================================================================

(defdescribe save-annotated-screenshot-test
  "Integration tests for saving annotated screenshots to file"
  (around [f] (core/with-testing-browser (f)))

  (describe "save to file"

    (it "writes a non-empty PNG file and returns {:path :size :annotated}"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap     (snapshot/capture-snapshot pg)
              tmp-file (File/createTempFile "annotate-test-" ".png")
              path     (.getAbsolutePath tmp-file)]
          (try
            (let [result (sut/save-annotated-screenshot! pg (:refs snap) path)]
              (expect (.exists tmp-file))
              (expect (pos? (.length tmp-file)))
              (expect (= path (:path result)))
              (expect (pos? (:size result)))
              (expect (number? (get-in result [:annotated :count])))
              ;; Verify it's a valid image
              (let [img (ImageIO/read tmp-file)]
                (expect (some? img))))
            (finally
              (.delete tmp-file))))))))

;; =============================================================================
;; Integration Tests — scoped annotations
;; =============================================================================

(defdescribe scoped-annotation-test
  "Integration tests for scoped annotation support"
  (around [f] (core/with-testing-browser (f)))

  (describe "scoped inject-overlays! with CSS selector"

    (it "annotates fewer elements when scoped to a subtree"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap     (snapshot/capture-snapshot pg)
              refs     (:refs snap)
              full-n   (:count (sut/inject-overlays! pg refs))
              _        (sut/remove-overlays! pg)
            ;; Scope to just the body > div (inner content container)
              scoped-n (:count (sut/inject-overlays! pg refs {:scope "div > p"}))]
          (sut/remove-overlays! pg)
        ;; Scoped should annotate fewer (or equal) elements
          (expect (<= scoped-n full-n)))))

    (it "returns {:count 0 :entries []} when scope selector matches nothing"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap   (snapshot/capture-snapshot pg)
              result (sut/inject-overlays! pg (:refs snap) {:scope "#nonexistent-element"})]
          (expect (= 0 (:count result)))
          (expect (= [] (:entries result)))))))

  (describe "scoped snapshot via capture-snapshot :scope"

    (it "captures fewer refs when scoped"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [full-snap   (snapshot/capture-snapshot pg)
              scoped-snap (snapshot/capture-snapshot pg {:scope "div > p"})]
        ;; Scoped snapshot should have fewer (or equal) refs
          (expect (<= (count (:refs scoped-snap)) (count (:refs full-snap)))))))

    (it "returns empty snapshot when scope matches nothing"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (snapshot/capture-snapshot pg {:scope "#nonexistent"})]
          (expect (nil? (:tree snap)))
          (expect (= 0 (count (:refs snap))))
          (expect (= 0 (:counter snap)))))))

  (describe "scoped annotated-screenshot"

    (it "produces valid PNG with scoped annotations"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap       (snapshot/capture-snapshot pg)
              result     (sut/annotated-screenshot pg (:refs snap) {:scope "body"})
              ^bytes png (:bytes result)]
          (expect (bytes? png))
          (expect (pos? (alength png)))
          (expect (map? (:annotated result)))
          (let [img (ImageIO/read (ByteArrayInputStream. png))]
            (expect (some? img))))))))
