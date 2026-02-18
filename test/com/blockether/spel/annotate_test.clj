(ns com.blockether.spel.annotate-test
  "Tests for the annotate namespace.

   Unit tests verify JS generation logic, annotation filtering, and
   containment dedup (no browser needed).
   Integration tests run against example.com using Playwright."
  (:require
   [com.blockether.spel.annotate :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                              with-browser with-page]]
   [lazytest.core :refer [defdescribe describe expect it]])
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
;; Unit Tests — JS overlay generation
;; =============================================================================

(defdescribe build-inject-js-test
  "Unit tests for JS overlay injection script generation"

  (describe "basic JS generation"
    (it "produces a non-empty JS string"
      (let [js (build-inject-js test-refs {})]
        (expect (string? js))
        (expect (pos? (count js)))))

    (it "contains the root container setup"
      (let [js (build-inject-js test-refs {})]
        (expect (.contains ^String js "data-spel-annotate"))
        (expect (.contains ^String js "z-index:2147483647"))
        (expect (.contains ^String js "pointer-events:none"))))

    (it "includes ref IDs and roles in label text"
      (let [js (build-inject-js test-refs {})]
        (expect (.contains ^String js "e1 button"))
        (expect (.contains ^String js "e2 link"))
        (expect (.contains ^String js "e3 heading"))))

    (it "includes dimensions computed from element rect"
      (let [js (build-inject-js test-refs {})]
        ;; Dims are now computed in JS via Math.round(dw) + 'x' + Math.round(dh)
        (expect (.contains ^String js "Math.round(dw)"))))

    (it "uses role-specific colors"
      (let [js (build-inject-js test-refs {})]
        ;; button → green
        (expect (.contains ^String js "#4CAF50"))
        ;; link → blue
        (expect (.contains ^String js "#2196F3"))
        ;; heading → pink
        (expect (.contains ^String js "#E91E63")))))

  (describe "compact label placement"
    (it "places label inside box for tall elements (dh >= 16)"
      (let [refs {"e1" {:role "button" :name "OK" :bbox {:x 10 :y 20 :width 60 :height 25}}}
            js   (build-inject-js refs {})]
        ;; Position computed from element rect via JS
        (expect (.contains ^String js "if (dh >= 16)"))))

    (it "places label to the right for small elements (dh < 16)"
      (let [refs {"e1" {:role "link" :name "X" :bbox {:x 10 :y 20 :width 40 :height 12}}}
            js   (build-inject-js refs {})]
        ;; Small element fallback: left = dx + dw + 2
        (expect (.contains ^String js "(dx + dw + 2)"))))

    (it "uses a single label element, not separate badge and dims"
      (let [js (build-inject-js test-refs {})]
        ;; No separate badge or dim elements
        (expect (not (.contains ^String js "'badge'")))
        (expect (not (.contains ^String js "'dim'")))
        ;; Uses label
        (expect (.contains ^String js "'label'")))))

  (describe "option toggles"
    (it "excludes boxes when :show-boxes false"
      (let [js (build-inject-js test-refs {:show-boxes false})]
        ;; Should still have labels, but no box border divs
        (expect (.contains ^String js "e1 button"))
        (expect (not (.contains ^String js "border:1px solid")))))

    (it "excludes labels when :show-badges false"
      (let [js (build-inject-js test-refs {:show-badges false})]
        ;; No label elements
        (expect (not (.contains ^String js "lbl.textContent")))
        ;; But should still have boxes with border
        (expect (.contains ^String js "border:1px solid"))))

    (it "excludes dimensions from label when :show-dimensions false"
      (let [js (build-inject-js test-refs {:show-dimensions false})]
        ;; No JS dim computation in label text
        (expect (not (.contains ^String js "Math.round(dw)")))
        ;; But ref + role still present
        (expect (.contains ^String js "e1 button"))
        (expect (.contains ^String js "e2 link"))))

    (it "generates minimal JS with all options disabled"
      (let [js-all  (build-inject-js test-refs {})
            js-none (build-inject-js test-refs {:show-boxes false
                                                :show-badges false
                                                :show-dimensions false})]
        ;; All-disabled should be smaller — only the container
        (expect (< (count js-none) (count js-all))))))

  (describe "edge cases"
    (it "handles empty refs map"
      (let [js (build-inject-js {} {})]
        (expect (string? js))
        ;; Should still set up the container
        (expect (.contains ^String js "data-spel-annotate"))))

    (it "handles single ref"
      (let [js (build-inject-js {"e1" {:role "button" :name "OK"
                                       :bbox {:x 10 :y 10 :width 30 :height 20}}} {})]
        (expect (.contains ^String js "e1 button"))
        (expect (.contains ^String js "data-pw-ref"))))

    (it "skips refs with zero-size bbox"
      (let [js (build-inject-js {"e1" {:role "button" :name "Hidden"
                                       :bbox {:x 0 :y 0 :width 0 :height 0}}} {})]
        ;; Zero-size bbox should be filtered out
        (expect (not (.contains ^String js "data-pw-ref=\"e1\"")))))))

;; =============================================================================
;; Unit Tests — annotation filtering
;; =============================================================================

(defdescribe filter-annotatable-test
  "Unit tests for structural role filtering and containment dedup"

  (describe "structural role filtering"
    (it "keeps interactive roles (button, link, textbox)"
      (let [refs {"e1" {:role "button" :bbox {:x 0 :y 0 :width 50 :height 20}}
                  "e2" {:role "link"   :bbox {:x 0 :y 30 :width 50 :height 20}}
                  "e3" {:role "textbox" :bbox {:x 0 :y 60 :width 50 :height 20}}}
            result (sut/filter-annotatable refs)]
        (expect (= 3 (count result)))
        (expect (contains? result "e1"))
        (expect (contains? result "e2"))
        (expect (contains? result "e3"))))

    (it "keeps content anchors (heading, img)"
      (let [refs {"e1" {:role "heading" :bbox {:x 0 :y 0 :width 100 :height 30}}
                  "e2" {:role "img"     :bbox {:x 0 :y 40 :width 100 :height 80}}}
            result (sut/filter-annotatable refs)]
        (expect (= 2 (count result)))))

    (it "removes pure structural roles (list, region)"
      (let [refs {"e1" {:role "list"   :bbox {:x 0 :y 30 :width 300 :height 100}}
                  "e2" {:role "region" :bbox {:x 0 :y 0 :width 500 :height 500}}}
            result (sut/filter-annotatable refs)]
        (expect (= 0 (count result)))))

    (it "keeps listitem role (annotatable for list content)"
      (let [refs {"e1" {:role "listitem" :bbox {:x 0 :y 30 :width 300 :height 25}}}
            result (sut/filter-annotatable refs)]
        (expect (= 1 (count result)))
        (expect (contains? result "e1"))))

    (it "keeps text containers (paragraph, span)"
      (let [refs {"e1" {:role "paragraph" :bbox {:x 0 :y 0 :width 300 :height 20}}
                  "e2" {:role "span"      :bbox {:x 0 :y 30 :width 100 :height 15}}}
            result (sut/filter-annotatable refs)]
        (expect (= 2 (count result)))))

    (it "keeps text role (generic divs with text content)"
      (let [refs {"e1" {:role "text" :bbox {:x 0 :y 0 :width 200 :height 20}}}
            result (sut/filter-annotatable refs)]
        (expect (= 1 (count result)))
        (expect (contains? result "e1"))))

    (it "paragraph wrapping link is removed by containment dedup"
      (let [refs {"e1" {:role "paragraph" :bbox {:x 0 :y 0 :width 300 :height 20}}
                  "e2" {:role "link"      :bbox {:x 5 :y 2 :width 60  :height 16}}
                  "e3" {:role "heading"   :bbox {:x 0 :y 30 :width 300 :height 30}}}
            result (sut/filter-annotatable refs)]
        ;; e1 paragraph contains e2 link → e1 suppressed by containment dedup
        (expect (= 2 (count result)))
        (expect (contains? result "e2"))
        (expect (contains? result "e3"))
        (expect (not (contains? result "e1"))))))

  (describe "containment dedup"
    (it "removes container whose bbox fully wraps a child"
      (let [refs {"e1" {:role "navigation" :bbox {:x 0 :y 0 :width 500 :height 100}}
                  "e2" {:role "link"       :bbox {:x 10 :y 10 :width 60 :height 20}}}
            result (sut/filter-annotatable refs)]
        ;; navigation contains link → navigation suppressed
        (expect (= 1 (count result)))
        (expect (contains? result "e2"))))

    (it "keeps both when neither contains the other"
      (let [refs {"e1" {:role "button" :bbox {:x 0  :y 0  :width 60 :height 20}}
                  "e2" {:role "link"   :bbox {:x 80 :y 0  :width 60 :height 20}}}
            result (sut/filter-annotatable refs)]
        (expect (= 2 (count result)))))

    (it "handles nested containment (grandparent → parent → child)"
      (let [refs {"e1" {:role "navigation" :bbox {:x 0 :y 0 :width 500 :height 100}}
                  "e2" {:role "dialog"     :bbox {:x 5 :y 5 :width 200 :height 50}}
                  "e3" {:role "button"     :bbox {:x 10 :y 10 :width 60 :height 20}}}
            result (sut/filter-annotatable refs)]
        ;; e1 contains e2 and e3 → e1 removed
        ;; e2 contains e3 → e2 removed
        ;; Only e3 (button) survives
        (expect (= 1 (count result)))
        (expect (contains? result "e3"))))

    (it "handles identical bboxes by keeping lower ref ID"
      (let [refs {"e1" {:role "button"  :bbox {:x 10 :y 10 :width 60 :height 20}}
                  "e2" {:role "heading" :bbox {:x 10 :y 10 :width 60 :height 20}}}
            result (sut/filter-annotatable refs)]
        ;; Same bbox → lower ID (e1) kept, higher (e2) removed
        (expect (= 1 (count result)))
        (expect (contains? result "e1"))))

    (it "keeps mixed-content container when it wraps a child (has own text)"
      (let [refs {"e1" {:role "paragraph" :mixed true :bbox {:x 0 :y 0 :width 300 :height 20}}
                  "e2" {:role "text"      :bbox {:x 5 :y 2 :width 60  :height 16}}}
            result (sut/filter-annotatable refs)]
        ;; e1 paragraph has :mixed true (own text besides child) → not suppressed
        (expect (= 2 (count result)))
        (expect (contains? result "e1"))
        (expect (contains? result "e2"))))

    (it "suppresses non-mixed container even if same role"
      (let [refs {"e1" {:role "text" :bbox {:x 0 :y 0 :width 300 :height 20}}
                  "e2" {:role "text" :bbox {:x 5 :y 2 :width 60  :height 16}}}
            result (sut/filter-annotatable refs)]
        ;; e1 has no :mixed flag → pure container → suppressed
        (expect (= 1 (count result)))
        (expect (contains? result "e2"))))

    (it "handles empty refs"
      (expect (= {} (sut/filter-annotatable {}))))

    (it "handles single ref"
      (let [refs {"e1" {:role "button" :bbox {:x 0 :y 0 :width 50 :height 20}}}]
        (expect (= refs (sut/filter-annotatable refs))))))

  (describe "combined filtering (structural + containment)"
    (it "filters example.com-like structure: heading + paragraphs + link"
      (let [refs {"e1" {:role "heading"   :bbox {:x 0 :y 50 :width 768 :height 45}}
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
        (expect (contains? result "e4"))))))

;; =============================================================================
;; Unit Tests — viewport visibility filtering
;; =============================================================================

(defdescribe visible-refs-test
  "Unit tests for viewport-based ref filtering"

  (describe "viewport filtering"
    (it "includes fully visible elements"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 10 :y 20 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 1 (count vis)))
        (expect (contains? vis "e1"))))

    (it "includes partially visible elements (overlapping right edge)"
      (let [vp   {:width 100 :height 100}
            refs {"e1" {:role "button" :bbox {:x 80 :y 10 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 1 (count vis)))))

    (it "includes partially visible elements (overlapping bottom edge)"
      (let [vp   {:width 100 :height 100}
            refs {"e1" {:role "button" :bbox {:x 10 :y 80 :width 60 :height 50}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 1 (count vis)))))

    (it "excludes elements fully below viewport"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 10 :y 800 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 0 (count vis)))))

    (it "excludes elements fully to the right of viewport"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 1100 :y 10 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 0 (count vis)))))

    (it "excludes elements fully above viewport (negative y + height)"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 10 :y -50 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 0 (count vis)))))

    (it "excludes elements fully to the left of viewport"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x -100 :y 10 :width 60 :height 25}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 0 (count vis)))))

    (it "filters mixed visible and offscreen refs"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 10 :y 20 :width 60 :height 25}}
                  "e2" {:role "link"   :bbox {:x 10 :y 800 :width 40 :height 15}}
                  "e3" {:role "heading" :bbox {:x 10 :y 5 :width 80 :height 20}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 2 (count vis)))
        (expect (contains? vis "e1"))
        (expect (contains? vis "e3"))
        (expect (not (contains? vis "e2")))))

    (it "excludes zero-size bbox"
      (let [vp   {:width 1024 :height 768}
            refs {"e1" {:role "button" :bbox {:x 10 :y 10 :width 0 :height 0}}}
            vis  (sut/visible-refs vp refs)]
        (expect (= 0 (count vis)))))

    (it "handles empty refs"
      (let [vp  {:width 1024 :height 768}
            vis (sut/visible-refs vp {})]
        (expect (= 0 (count vis)))))))

;; =============================================================================
;; Unit Tests — scope filtering
;; =============================================================================

(defdescribe scope-helpers-test
  "Unit tests for scope ref resolution helpers"

  (describe "ref-scope? detection"
    (it "recognizes @e1 as a ref scope"
      (expect (true? (#'sut/ref-scope? "@e1"))))

    (it "recognizes e1 as a ref scope"
      (expect (true? (#'sut/ref-scope? "e1"))))

    (it "recognizes e123 as a ref scope"
      (expect (true? (#'sut/ref-scope? "e123"))))

    (it "rejects CSS selectors"
      (expect (false? (#'sut/ref-scope? "#main")))
      (expect (false? (#'sut/ref-scope? ".container")))
      (expect (false? (#'sut/ref-scope? "div")))))

  (describe "resolve-scope"
    (it "converts @e1 to data-pw-ref selector"
      (expect (= "[data-pw-ref=\"e1\"]" (#'sut/resolve-scope "@e1"))))

    (it "converts e1 to data-pw-ref selector"
      (expect (= "[data-pw-ref=\"e1\"]" (#'sut/resolve-scope "e1"))))

    (it "passes CSS selectors through unchanged"
      (expect (= "#main" (#'sut/resolve-scope "#main")))
      (expect (= ".container" (#'sut/resolve-scope ".container"))))))

;; =============================================================================
;; Integration Tests — annotated-screenshot
;; =============================================================================

(defdescribe annotated-screenshot-integration-test
  "Integration tests with real Playwright screenshots"

  (describe "annotated-screenshot with real page"
    {:context [with-playwright with-browser with-page]}

    (it "returns valid PNG bytes with annotations"
      (page/navigate *page* "https://example.com")
      (let [snap   (snapshot/capture-snapshot *page*)
            result (sut/annotated-screenshot *page* (:refs snap))]
        (expect (bytes? result))
        (expect (pos? (alength result)))
        ;; Verify it's a valid PNG
        (let [img (ImageIO/read (ByteArrayInputStream. result))]
          (expect (some? img))
          (expect (pos? (.getWidth img)))
          (expect (pos? (.getHeight img))))))

    (it "annotated is larger than raw screenshot"
      (page/navigate *page* "https://example.com")
      (let [snap      (snapshot/capture-snapshot *page*)
            raw       (page/screenshot *page*)
            annotated (sut/annotated-screenshot *page* (:refs snap))]
        (expect (> (alength annotated) (alength raw)))))))

;; =============================================================================
;; Integration Tests — save-annotated-screenshot!
;; =============================================================================

(defdescribe save-annotated-screenshot-test
  "Integration tests for saving annotated screenshots to file"

  (describe "save to file"
    {:context [with-playwright with-browser with-page]}

    (it "writes a non-empty PNG file"
      (page/navigate *page* "https://example.com")
      (let [snap     (snapshot/capture-snapshot *page*)
            tmp-file (File/createTempFile "annotate-test-" ".png")
            path     (.getAbsolutePath tmp-file)]
        (try
          (sut/save-annotated-screenshot! *page* (:refs snap) path)
          (expect (.exists tmp-file))
          (expect (pos? (.length tmp-file)))
          ;; Verify it's a valid image
          (let [img (ImageIO/read tmp-file)]
            (expect (some? img)))
          (finally
            (.delete tmp-file)))))))

;; =============================================================================
;; Integration Tests — scoped annotations
;; =============================================================================

(defdescribe scoped-annotation-test
  "Integration tests for scoped annotation support"

  (describe "scoped inject-overlays! with CSS selector"
    {:context [with-playwright with-browser with-page]}

    (it "annotates fewer elements when scoped to a subtree"
      (page/navigate *page* "https://example.com")
      (let [snap      (snapshot/capture-snapshot *page*)
            refs      (:refs snap)
            full-n    (sut/inject-overlays! *page* refs)
            _         (sut/remove-overlays! *page*)
            ;; Scope to just the body > div (inner content container)
            scoped-n  (sut/inject-overlays! *page* refs {:scope "div > p"})]
        (sut/remove-overlays! *page*)
        ;; Scoped should annotate fewer (or equal) elements
        (expect (<= scoped-n full-n))))

    (it "returns 0 when scope selector matches nothing"
      (page/navigate *page* "https://example.com")
      (let [snap (snapshot/capture-snapshot *page*)
            n    (sut/inject-overlays! *page* (:refs snap) {:scope "#nonexistent-element"})]
        (expect (= 0 n)))))

  (describe "scoped snapshot via capture-snapshot :scope"
    {:context [with-playwright with-browser with-page]}

    (it "captures fewer refs when scoped"
      (page/navigate *page* "https://example.com")
      (let [full-snap   (snapshot/capture-snapshot *page*)
            scoped-snap (snapshot/capture-snapshot *page* {:scope "div > p"})]
        ;; Scoped snapshot should have fewer (or equal) refs
        (expect (<= (count (:refs scoped-snap)) (count (:refs full-snap))))))

    (it "returns empty snapshot when scope matches nothing"
      (page/navigate *page* "https://example.com")
      (let [snap (snapshot/capture-snapshot *page* {:scope "#nonexistent"})]
        (expect (nil? (:tree snap)))
        (expect (= 0 (count (:refs snap))))
        (expect (= 0 (:counter snap))))))

  (describe "scoped annotated-screenshot"
    {:context [with-playwright with-browser with-page]}

    (it "produces valid PNG with scoped annotations"
      (page/navigate *page* "https://example.com")
      (let [snap   (snapshot/capture-snapshot *page*)
            result (sut/annotated-screenshot *page* (:refs snap) {:scope "body"})]
        (expect (bytes? result))
        (expect (pos? (alength result)))
        (let [img (ImageIO/read (ByteArrayInputStream. result))]
          (expect (some? img)))))))
