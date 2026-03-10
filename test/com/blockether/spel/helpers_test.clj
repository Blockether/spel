(ns com.blockether.spel.helpers-test
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.helpers :as helpers]
   [com.blockether.spel.page :as page]))

(def ^:private todomvc-url "https://demo.playwright.dev/todomvc")

(defdescribe qa-helpers-test
  "Integration tests for QA helper return shapes on a real page."

  (describe "text-contrast!"
    (it "returns expected contrast audit shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/text-contrast! pg)]
          (expect (vector? (:elements result)))
          (expect (number? (:total-elements result)))
          (expect (number? (:passing result)))))))

  (describe "color-palette!"
    (it "returns expected color palette shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/color-palette! pg)
              colors (:colors result)]
          (expect (vector? colors))
          (when (seq colors)
            (expect (every? #(or (contains? % :hex) (contains? % :rgb)) colors)))))))

  (describe "layout-check!"
    (it "returns expected layout check shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/layout-check! pg)]
          (expect (vector? (:issues result)))
          (expect (number? (:total-issues result)))))))

  (describe "font-audit!"
    (it "returns expected font audit shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/font-audit! pg)
              fonts (:fonts result)]
          (expect (vector? fonts))
          (when (seq fonts)
            (expect (every? #(contains? % :family) fonts)))))))

  (describe "link-health!"
    (it "returns expected link health shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/link-health! pg)]
          (expect (number? (:total-links result)))
          (expect (vector? (:links result)))))))

  (describe "heading-structure!"
    (it "returns expected heading structure shape"
      (core/with-testing-page [pg]
        (page/navigate pg todomvc-url)
        (let [result (helpers/heading-structure! pg)]
          (expect (vector? (:headings result)))
          (expect (instance? Boolean (:valid? result)))
          (expect (map? (:stats result)))
          (when-let [first-heading (first (:headings result))]
            (expect (contains? first-heading :level))
            (expect (or (nil? (:text first-heading)) (str/blank? (:text first-heading)) (string? (:text first-heading))))))))))
