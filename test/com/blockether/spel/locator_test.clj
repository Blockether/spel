(ns com.blockether.spel.locator-test
  "Tests for the locator namespace - actions, state, collections, filtering."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.locator :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.allure :refer [defdescribe describe expect it around]])
  (:import
   [com.microsoft.playwright Locator FrameLocator Page]))

;; Test HTML content for locator tests
(def ^:private test-html
  "<html>
     <head><title>Locator Test</title></head>
     <body>
       <div id='container'>
         <button id='btn1' class='btn primary'>Click Me</button>
         <button id='btn2' class='btn secondary' disabled>Disabled</button>
         <input id='text-input' type='text' value='initial' placeholder='Enter text'/>
         <input id='checkbox1' type='checkbox' checked/>
         <input id='checkbox2' type='checkbox'/>
         <span class='item'>First</span>
         <span class='item'>Second</span>
         <span class='item'>Third</span>
         <div id='hidden' style='display:none'>Hidden content</div>
         <a href='#link'>Link text</a>
       </div>
     </body>
    </html>")

(def ^:private drag-test-html
  "<html><body>
     <div id='source' draggable='true' style='width:50px;height:50px;background:red;position:absolute;left:10px;top:10px'>Drag</div>
     <div id='target' style='width:100px;height:100px;background:blue;position:absolute;left:300px;top:10px'>Drop</div>
     <div id='hidden-el' style='display:none'>Hidden</div>
   </body></html>")

;; =============================================================================
;; Locator Actions
;; =============================================================================

(defdescribe locator-actions-test
  "Tests for locator actions (click, fill, type, press, clear)"
  (around [f] (core/with-testing-browser (f)))

  (describe "click"

    (it "clicks a button"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (nil? (sut/click btn)))))))

  (describe "fill"

    (it "fills an input field"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (sut/fill input "new value")
          (expect (= "new value" (sut/input-value input)))))))

  (describe "type-text"

    (it "types text into an input"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (sut/clear input)
          (sut/type-text input "typed")
          (expect (= "typed" (sut/input-value input)))))))

  (describe "press"

    (it "presses Enter key"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (expect (nil? (sut/press input "Enter")))))))

  (describe "clear"

    (it "clears an input field"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (sut/clear input)
          (expect (= "" (sut/input-value input))))))))

(defdescribe locator-drag-test
  "Tests for locator drag methods (drag-to, drag-by)"
  (around [f] (core/with-testing-browser (f)))

  (describe "drag-to"

    (it "drag-to basic (2-arg) returns nil"
      (core/with-testing-page [pg]
        (page/set-content! pg drag-test-html)
        (expect (nil? (sut/drag-to (page/locator pg "#source")
                        (page/locator pg "#target"))))))

    (it "drag-to with opts {:steps 5} returns nil"
      (core/with-testing-page [pg]
        (page/set-content! pg drag-test-html)
        (expect (nil? (sut/drag-to (page/locator pg "#source")
                        (page/locator pg "#target")
                        {:steps 5}))))))

  (describe "drag-by"

    (it "drag-by basic returns nil"
      (core/with-testing-page [pg]
        (page/set-content! pg drag-test-html)
        (expect (nil? (sut/drag-by ^Page pg (page/locator pg "#source") 200 0)))))

    (it "drag-by with {:steps 10} returns nil"
      (core/with-testing-page [pg]
        (page/set-content! pg drag-test-html)
        (expect (nil? (sut/drag-by ^Page pg (page/locator pg "#source") 200 0 {:steps 10})))))

    (it "drag-by throws when bounding box cannot be resolved"
      (core/with-testing-page [pg]
        (page/set-content! pg drag-test-html)
        (let [threw? (try (sut/drag-by ^Page pg (page/locator pg "#hidden-el") 200 0)
                       false
                       (catch Exception _ true))]
          (expect threw?))))))

;; =============================================================================
;; Locator Content
;; =============================================================================

(defdescribe locator-content-test
  "Tests for locator content methods (text-content, inner-text, inner-html, input-value)"
  (around [f] (core/with-testing-browser (f)))

  (describe "text-content"

    (it "returns text content of element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (= "Click Me" (sut/text-content btn)))))))

  (describe "inner-text"

    (it "returns inner text of element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [link (page/locator pg "a")]
          (expect (= "Link text" (sut/inner-text link)))))))

  (describe "inner-html"

    (it "returns inner HTML of element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [div  (page/locator pg "#container")
              html (sut/inner-html div)]
          (expect (string? html))
          (expect (.contains html "btn1"))))))

  (describe "input-value"

    (it "returns value of input field"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (expect (= "initial" (sut/input-value input))))))))

;; =============================================================================
;; Locator State
;; =============================================================================

(defdescribe locator-state-test
  "Tests for locator state methods (is-visible?, is-hidden?, is-enabled?, is-disabled?, is-editable?, is-checked?)"
  (around [f] (core/with-testing-browser (f)))

  (describe "is-visible?"

    (it "returns true for visible element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (true? (sut/is-visible? btn))))))

    (it "returns false for hidden element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [hidden (page/locator pg "#hidden")]
          (expect (false? (sut/is-visible? hidden)))))))

  (describe "is-hidden?"

    (it "returns true for hidden element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [hidden (page/locator pg "#hidden")]
          (expect (true? (sut/is-hidden? hidden))))))

    (it "returns false for visible element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (false? (sut/is-hidden? btn)))))))

  (describe "is-enabled?"

    (it "returns true for enabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (true? (sut/is-enabled? btn))))))

    (it "returns false for disabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn2")]
          (expect (false? (sut/is-enabled? btn)))))))

  (describe "is-disabled?"

    (it "returns true for disabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn2")]
          (expect (true? (sut/is-disabled? btn))))))

    (it "returns false for enabled element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (false? (sut/is-disabled? btn)))))))

  (describe "is-editable?"

    (it "returns true for editable input"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")]
          (expect (true? (sut/is-editable? input)))))))

  (describe "is-checked?"

    (it "returns true for checked checkbox"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [cb (page/locator pg "#checkbox1")]
          (expect (true? (sut/is-checked? cb))))))

    (it "returns false for unchecked checkbox"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [cb (page/locator pg "#checkbox2")]
          (expect (false? (sut/is-checked? cb))))))))

;; =============================================================================
;; Locator Collections
;; =============================================================================

(defdescribe locator-collections-test
  "Tests for locator collection methods (count-elements, all-text-contents, all)"
  (around [f] (core/with-testing-browser (f)))

  (describe "count-elements"

    (it "returns count of matching elements"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")]
          (expect (= 3 (sut/count-elements items))))))

    (it "returns 1 for single element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")]
          (expect (= 1 (sut/count-elements btn)))))))

  (describe "all-text-contents"

    (it "returns all text contents as vector"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")]
          (expect (= ["First" "Second" "Third"] (sut/all-text-contents items)))))))

  (describe "all"

    (it "returns all matching locators as vector"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")
              all-items (sut/all items)]
          (expect (vector? all-items))
          (expect (= 3 (count all-items)))
          (expect (every? #(instance? Locator %) all-items)))))))

;; =============================================================================
;; Locator Filtering
;; =============================================================================

(defdescribe locator-filtering-test
  "Tests for locator filtering methods (loc-filter with :has-text)"
  (around [f] (core/with-testing-browser (f)))

  (describe "loc-filter"

    (it "filters by :has-text"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")
              filtered (sut/loc-filter items {:has-text "Second"})]
          (expect (instance? Locator filtered))
          (expect (= "Second" (sut/text-content filtered))))))

    (it "filters buttons by :has-text"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btns (page/locator pg "button")
              filtered (sut/loc-filter btns {:has-text "Click Me"})]
          (expect (= "Click Me" (sut/text-content filtered))))))))

;; =============================================================================
;; Locator Indexing
;; =============================================================================

(defdescribe locator-indexing-test
  "Tests for locator indexing methods (first-element, last-element, nth-element)"
  (around [f] (core/with-testing-browser (f)))

  (describe "first-element"

    (it "returns first matching element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")
              first-item (sut/first-element items)]
          (expect (instance? Locator first-item))
          (expect (= "First" (sut/text-content first-item)))))))

  (describe "last-element"

    (it "returns last matching element"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")
              last-item (sut/last-element items)]
          (expect (instance? Locator last-item))
          (expect (= "Third" (sut/text-content last-item)))))))

  (describe "nth-element"

    (it "returns nth matching element (0-indexed)"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [items (page/locator pg ".item")
              second-item (sut/nth-element items 1)]
          (expect (instance? Locator second-item))
          (expect (= "Second" (sut/text-content second-item))))))))

;; =============================================================================
;; Locator Geometry
;; =============================================================================

(defdescribe locator-geometry-test
  "Tests for locator geometry methods (bounding-box)"
  (around [f] (core/with-testing-browser (f)))

  (describe "bounding-box"

    (it "returns bounding box map with x, y, width, height"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn (page/locator pg "#btn1")
              bb (sut/bounding-box btn)]
          (expect (map? bb))
          (expect (contains? bb :x))
          (expect (contains? bb :y))
          (expect (contains? bb :width))
          (expect (contains? bb :height))
          (expect (number? (:x bb)))
          (expect (pos? (:width bb))))))))

;; =============================================================================
;; Sub-Locators
;; =============================================================================

(defdescribe sub-locators-test
  "Tests for sub-locator methods (loc-locator, loc-get-by-text)"
  (around [f] (core/with-testing-browser (f)))

  (describe "loc-locator"

    (it "creates a sub-locator with CSS selector"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [container (page/locator pg "#container")
              sub-btn (sut/loc-locator container "#btn1")]
          (expect (instance? Locator sub-btn))
          (expect (= "Click Me" (sut/text-content sub-btn)))))))

  (describe "loc-get-by-text"

    (it "creates a sub-locator by text"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [container (page/locator pg "#container")
              sub-link (sut/loc-get-by-text container "Link text")]
          (expect (instance? Locator sub-link))
          (expect (= "Link text" (sut/text-content sub-link))))))))

;; =============================================================================
;; Content Frame
;; =============================================================================

(defdescribe content-frame-test
  "Tests for locator content-frame (returns FrameLocator for iframes)"
  (around [f] (core/with-testing-browser (f)))

  (describe "content-frame"

    (it "returns FrameLocator for iframe element"
      (core/with-testing-page [pg]
        (page/set-content! pg
          "<iframe id='myframe' srcdoc='<h1>Inside Frame</h1><p>Frame content</p>'></iframe>")
        ;; Wait for iframe to load
        (page/wait-for-load-state pg)
        (let [iframe-loc (page/locator pg "#myframe")
              fl (sut/content-frame iframe-loc)]
          (expect (instance? FrameLocator fl))
          (let [h1 (.locator fl "h1")]
            (expect (= "Inside Frame" (sut/text-content h1)))))))))

;; =============================================================================
;; Computed Styles
;; =============================================================================

(defdescribe computed-styles-test
  "Tests for locator/computed-styles"
  (around [f] (core/with-testing-browser (f)))

  (describe "curated styles"
    (it "returns a map of curated CSS properties"
      (core/with-testing-page [pg]
        (page/set-content! pg
          "<h1 style='color: red; font-size: 24px;'>Styled</h1>")
        (let [loc    (page/locator pg "h1")
              styles (sut/computed-styles loc)]
          (expect (map? styles))
          (expect (contains? styles "fontSize"))
          (expect (contains? styles "color"))
          (expect (contains? styles "display")))))

    (it "does not include non-curated properties by default"
      (core/with-testing-page [pg]
        (page/set-content! pg "<div>Test</div>")
        (let [styles (sut/computed-styles (page/locator pg "div"))]
        ;; curated set has ~16 properties, not hundreds
          (expect (<= (count styles) 20))))))

  (describe "full styles"
    (it "returns all computed properties with {:full true}"
      (core/with-testing-page [pg]
        (page/set-content! pg "<p>Full styles</p>")
        (let [styles (sut/computed-styles (page/locator pg "p") {:full true})]
          (expect (map? styles))
        ;; full computed styles have 100+ properties
          (expect (> (count styles) 50)))))))
