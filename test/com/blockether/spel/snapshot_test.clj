(ns com.blockether.spel.snapshot-test
  "Tests for the snapshot namespace.

   Unit tests for pure helper functions and integration tests
   that run against example.com using Playwright."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as sut]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                              with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — ref-bounding-box
;; =============================================================================

(defdescribe ref-bounding-box-test
  "Unit tests for ref-bounding-box"

  (describe "returns bbox for existing ref"
    (it "extracts bbox map from refs"
      (let [refs {"e1" {:role "button" :name "Submit"
                        :bbox {:x 10 :y 20 :width 80 :height 30}}
                  "e2" {:role "link" :name "Home"
                        :bbox {:x 0 :y 0 :width 100 :height 20}}}]
        (expect (= {:x 10 :y 20 :width 80 :height 30}
                  (sut/ref-bounding-box refs "e1")))
        (expect (= {:x 0 :y 0 :width 100 :height 20}
                  (sut/ref-bounding-box refs "e2"))))))

  (describe "returns nil for missing ref"
    (it "returns nil when ref not in map"
      (let [refs {"e1" {:role "button" :name "Submit"
                        :bbox {:x 10 :y 20 :width 80 :height 30}}}]
        (expect (nil? (sut/ref-bounding-box refs "e99")))
        (expect (nil? (sut/ref-bounding-box refs "nonexistent"))))))

  (describe "handles empty refs map"
    (it "returns nil for empty map"
      (expect (nil? (sut/ref-bounding-box {} "e1"))))))

;; =============================================================================
;; Integration Tests — capture-snapshot
;; =============================================================================

(defdescribe capture-snapshot-test
  "Integration tests for capture-snapshot against example.com"

  (describe "snapshot structure"
    {:context [with-playwright with-browser with-page]}

    (it "returns map with :tree :refs :counter keys"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (map? snap))
        (expect (contains? snap :tree))
        (expect (contains? snap :refs))
        (expect (contains? snap :counter))))

    (it "tree is a non-empty string"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (string? (:tree snap)))
        (expect (pos? (count (:tree snap))))))

    (it "counter is a positive number"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (number? (:counter snap)))
        (expect (pos? (:counter snap)))))

    (it "refs is a non-empty map"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (map? (:refs snap)))
        (expect (pos? (count (:refs snap)))))))

  (describe "tree content for example.com"
    {:context [with-playwright with-browser with-page]}

    (it "contains heading role"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "heading"))))

    (it "contains link role"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "link"))))

    (it "contains ref annotations"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "[@e")))))

  (describe "refs content"
    {:context [with-playwright with-browser with-page]}

    (it "ref entries have correct structure"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)
            first-ref (val (first (:refs snap)))]
        (expect (contains? first-ref :role))
        (expect (contains? first-ref :name))
        (expect (contains? first-ref :bbox))
        (expect (contains? first-ref :tag))))

    (it "bbox values are numbers"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)
            first-ref (val (first (:refs snap)))
            bbox (:bbox first-ref)]
        (expect (number? (:x bbox)))
        (expect (number? (:y bbox)))
        (expect (number? (:width bbox)))
        (expect (number? (:height bbox)))))

    (it "ref keys follow eN pattern"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (doseq [ref-id (keys (:refs snap))]
          (expect (re-matches #"e\d+" ref-id)))))))

;; =============================================================================
;; Integration Tests — resolve-ref
;; =============================================================================

(defdescribe resolve-ref-test
  "Integration tests for resolve-ref"

  (describe "resolving refs after snapshot"
    {:context [with-playwright with-browser with-page]}

    (it "returns a Locator for valid ref"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)
            first-ref-id (key (first (:refs snap)))
            locator (sut/resolve-ref *page* first-ref-id)]
        (expect (some? locator))
        (expect (instance? com.microsoft.playwright.Locator locator))))

    (it "locator matches the correct element"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)
            ;; Find the heading ref
            heading-ref (some (fn [[ref-id info]]
                                (when (= "heading" (:role info))
                                  ref-id))
                          (:refs snap))]
        (when heading-ref
          (let [locator (sut/resolve-ref *page* heading-ref)
                text (.textContent locator)]
            (expect (= "Example Domain" text))))))))

;; =============================================================================
;; Integration Tests — clear-refs!
;; =============================================================================

(defdescribe clear-refs-test
  "Integration tests for clear-refs!"

  (describe "clearing refs from DOM"
    {:context [with-playwright with-browser with-page]}

    (it "removes data-pw-ref attributes"
      (page/navigate *page* "https://example.com")
      ;; First capture to add refs
      (sut/capture-snapshot *page*)
      ;; Verify refs exist
      (let [count-before (page/evaluate *page*
                           "document.querySelectorAll('[data-pw-ref]').length")]
        (expect (pos? (int count-before))))
      ;; Clear refs
      (sut/clear-refs! *page*)
      ;; Verify refs removed
      (let [count-after (page/evaluate *page*
                          "document.querySelectorAll('[data-pw-ref]').length")]
        (expect (zero? (int count-after)))))))

;; =============================================================================
;; Integration Tests — capture-full-snapshot
;; =============================================================================

(defdescribe capture-full-snapshot-test
  "Integration tests for capture-full-snapshot"

  (describe "full snapshot on page without iframes"
    {:context [with-playwright with-browser with-page]}

    (it "returns same structure as capture-snapshot for simple pages"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-full-snapshot *page*)]
        (expect (map? snap))
        (expect (contains? snap :tree))
        (expect (contains? snap :refs))
        (expect (contains? snap :counter))
        (expect (string? (:tree snap)))
        (expect (pos? (count (:refs snap))))))))
