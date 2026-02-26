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

    (it "ref keys follow content-hash pattern"
      (page/navigate *page* "https://example.com")
      (let [snap (sut/capture-snapshot *page*)]
        (doseq [ref-id (keys (:refs snap))]
          (expect (re-matches #"e[a-z0-9]+" ref-id)))))))

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

;; =============================================================================
;; Integration Tests — deterministic refs (content-hash stability)
;; =============================================================================

(defdescribe deterministic-refs-test
  "Tests that snapshot refs are deterministic and stable across page states.

   Content-hash refs use FNV-1a(role|name|tag) so the same element always
   gets the same ref regardless of what other elements exist on the page."

  (describe "same page produces identical refs"
    {:context [with-playwright with-browser with-page]}

    (it "two consecutive snapshots produce identical ref keys"
      (page/navigate *page* "https://example.com")
      (let [snap1 (sut/capture-snapshot *page*)
            snap2 (sut/capture-snapshot *page*)]
        (expect (= (set (keys (:refs snap1)))
                  (set (keys (:refs snap2)))))
        ;; Same ref maps to same role+name
        (doseq [[ref-id info] (:refs snap1)]
          (expect (= (:role info) (:role (get (:refs snap2) ref-id))))
          (expect (= (:name info) (:name (get (:refs snap2) ref-id)))))))

    (it "refs are stable after page reload"
      (page/navigate *page* "https://example.com")
      (let [snap1 (sut/capture-snapshot *page*)]
        (page/navigate *page* "https://example.com")
        (let [snap2 (sut/capture-snapshot *page*)]
          (expect (= (set (keys (:refs snap1)))
                    (set (keys (:refs snap2)))))))))

  (describe "refs survive DOM mutations"
    {:context [with-playwright with-browser with-page]}

    (it "adding a new element does not change existing refs"
      (page/navigate *page* "https://example.com")
      (let [snap-before (sut/capture-snapshot *page*)
            before-refs (:refs snap-before)]
        ;; Inject a new button into the page
        (page/evaluate *page*
          "(() => { const btn = document.createElement('button'); btn.textContent = 'Injected'; document.body.prepend(btn); })()")
        (let [snap-after (sut/capture-snapshot *page*)
              after-refs (:refs snap-after)]
          ;; All old refs should still exist with same role+name
          (doseq [[ref-id info] before-refs]
            (let [after-info (get after-refs ref-id)]
              (expect (some? after-info))
              (expect (= (:role info) (:role after-info)))
              (expect (= (:name info) (:name after-info)))))
          ;; There should be one MORE ref (the injected button)
          (expect (> (count after-refs) (count before-refs))))))

    (it "removing an element does not change remaining refs"
      (page/navigate *page* "https://example.com")
      (let [snap-before (sut/capture-snapshot *page*)
            before-refs (:refs snap-before)
            ;; Find the link ref to remove
            link-ref (some (fn [[ref-id info]]
                             (when (= "link" (:role info))
                               ref-id))
                       before-refs)
            non-link-refs (dissoc before-refs link-ref)]
        ;; Remove the link from the DOM
        (page/evaluate *page*
          "(() => { const a = document.querySelector('a'); if(a) a.remove(); })()")
        (let [snap-after (sut/capture-snapshot *page*)
              after-refs (:refs snap-after)]
          ;; All non-link refs should still exist with same role+name
          (doseq [[ref-id info] non-link-refs]
            (let [after-info (get after-refs ref-id)]
              (expect (some? after-info))
              (expect (= (:role info) (:role after-info)))
              (expect (= (:name info) (:name after-info)))))
          ;; The link ref should be gone
          (expect (nil? (get after-refs link-ref))))))))
