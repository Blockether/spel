(ns com.blockether.spel.locator-test
  "Tests for the locator namespace - actions, state, collections, filtering."
  (:require
   [com.blockether.spel.locator :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it before-each]])
  (:import
   [com.microsoft.playwright Locator FrameLocator]))

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

;; =============================================================================
;; Locator Actions
;; =============================================================================

(defdescribe locator-actions-test
  "Tests for locator actions (click, fill, type, press, clear)"

  (describe "click"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "clicks a button"
      (let [btn (page/locator *page* "#btn1")]
        (expect (nil? (sut/click btn))))))

  (describe "fill"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "fills an input field"
      (let [input (page/locator *page* "#text-input")]
        (sut/fill input "new value")
        (expect (= "new value" (sut/input-value input))))))

  (describe "type-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "types text into an input"
      (let [input (page/locator *page* "#text-input")]
        (sut/clear input)
        (sut/type-text input "typed")
        (expect (= "typed" (sut/input-value input))))))

  (describe "press"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "presses Enter key"
      (let [input (page/locator *page* "#text-input")]
        (expect (nil? (sut/press input "Enter"))))))

  (describe "clear"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "clears an input field"
      (let [input (page/locator *page* "#text-input")]
        (sut/clear input)
        (expect (= "" (sut/input-value input)))))))

;; =============================================================================
;; Locator Content
;; =============================================================================

(defdescribe locator-content-test
  "Tests for locator content methods (text-content, inner-text, inner-html, input-value)"

  (describe "text-content"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns text content of element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (= "Click Me" (sut/text-content btn))))))

  (describe "inner-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns inner text of element"
      (let [link (page/locator *page* "a")]
        (expect (= "Link text" (sut/inner-text link))))))

  (describe "inner-html"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns inner HTML of element"
      (let [div  (page/locator *page* "#container")
            html (sut/inner-html div)]
        (expect (string? html))
        (expect (.contains html "btn1")))))

  (describe "input-value"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns value of input field"
      (let [input (page/locator *page* "#text-input")]
        (expect (= "initial" (sut/input-value input)))))))

;; =============================================================================
;; Locator State
;; =============================================================================

(defdescribe locator-state-test
  "Tests for locator state methods (is-visible?, is-hidden?, is-enabled?, is-disabled?, is-editable?, is-checked?)"

  (describe "is-visible?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for visible element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (true? (sut/is-visible? btn)))))

    (it "returns false for hidden element"
      (let [hidden (page/locator *page* "#hidden")]
        (expect (false? (sut/is-visible? hidden))))))

  (describe "is-hidden?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for hidden element"
      (let [hidden (page/locator *page* "#hidden")]
        (expect (true? (sut/is-hidden? hidden)))))

    (it "returns false for visible element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (false? (sut/is-hidden? btn))))))

  (describe "is-enabled?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for enabled element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (true? (sut/is-enabled? btn)))))

    (it "returns false for disabled element"
      (let [btn (page/locator *page* "#btn2")]
        (expect (false? (sut/is-enabled? btn))))))

  (describe "is-disabled?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for disabled element"
      (let [btn (page/locator *page* "#btn2")]
        (expect (true? (sut/is-disabled? btn)))))

    (it "returns false for enabled element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (false? (sut/is-disabled? btn))))))

  (describe "is-editable?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for editable input"
      (let [input (page/locator *page* "#text-input")]
        (expect (true? (sut/is-editable? input))))))

  (describe "is-checked?"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns true for checked checkbox"
      (let [cb (page/locator *page* "#checkbox1")]
        (expect (true? (sut/is-checked? cb)))))

    (it "returns false for unchecked checkbox"
      (let [cb (page/locator *page* "#checkbox2")]
        (expect (false? (sut/is-checked? cb)))))))

;; =============================================================================
;; Locator Collections
;; =============================================================================

(defdescribe locator-collections-test
  "Tests for locator collection methods (count-elements, all-text-contents, all)"

  (describe "count-elements"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns count of matching elements"
      (let [items (page/locator *page* ".item")]
        (expect (= 3 (sut/count-elements items)))))

    (it "returns 1 for single element"
      (let [btn (page/locator *page* "#btn1")]
        (expect (= 1 (sut/count-elements btn))))))

  (describe "all-text-contents"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns all text contents as vector"
      (let [items (page/locator *page* ".item")]
        (expect (= ["First" "Second" "Third"] (sut/all-text-contents items))))))

  (describe "all"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns all matching locators as vector"
      (let [items (page/locator *page* ".item")
            all-items (sut/all items)]
        (expect (vector? all-items))
        (expect (= 3 (count all-items)))
        (expect (every? #(instance? Locator %) all-items))))))

;; =============================================================================
;; Locator Filtering
;; =============================================================================

(defdescribe locator-filtering-test
  "Tests for locator filtering methods (loc-filter with :has-text)"

  (describe "loc-filter"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "filters by :has-text"
      (let [items (page/locator *page* ".item")
            filtered (sut/loc-filter items {:has-text "Second"})]
        (expect (instance? Locator filtered))
        (expect (= "Second" (sut/text-content filtered)))))

    (it "filters buttons by :has-text"
      (let [btns (page/locator *page* "button")
            filtered (sut/loc-filter btns {:has-text "Click Me"})]
        (expect (= "Click Me" (sut/text-content filtered)))))))

;; =============================================================================
;; Locator Indexing
;; =============================================================================

(defdescribe locator-indexing-test
  "Tests for locator indexing methods (first-element, last-element, nth-element)"

  (describe "first-element"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns first matching element"
      (let [items (page/locator *page* ".item")
            first-item (sut/first-element items)]
        (expect (instance? Locator first-item))
        (expect (= "First" (sut/text-content first-item))))))

  (describe "last-element"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns last matching element"
      (let [items (page/locator *page* ".item")
            last-item (sut/last-element items)]
        (expect (instance? Locator last-item))
        (expect (= "Third" (sut/text-content last-item))))))

  (describe "nth-element"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns nth matching element (0-indexed)"
      (let [items (page/locator *page* ".item")
            second-item (sut/nth-element items 1)]
        (expect (instance? Locator second-item))
        (expect (= "Second" (sut/text-content second-item)))))))

;; =============================================================================
;; Locator Geometry
;; =============================================================================

(defdescribe locator-geometry-test
  "Tests for locator geometry methods (bounding-box)"

  (describe "bounding-box"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "returns bounding box map with x, y, width, height"
      (let [btn (page/locator *page* "#btn1")
            bb (sut/bounding-box btn)]
        (expect (map? bb))
        (expect (contains? bb :x))
        (expect (contains? bb :y))
        (expect (contains? bb :width))
        (expect (contains? bb :height))
        (expect (number? (:x bb)))
        (expect (pos? (:width bb)))))))

;; =============================================================================
;; Sub-Locators
;; =============================================================================

(defdescribe sub-locators-test
  "Tests for sub-locator methods (loc-locator, loc-get-by-text)"

  (describe "loc-locator"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "creates a sub-locator with CSS selector"
      (let [container (page/locator *page* "#container")
            sub-btn (sut/loc-locator container "#btn1")]
        (expect (instance? Locator sub-btn))
        (expect (= "Click Me" (sut/text-content sub-btn))))))

  (describe "loc-get-by-text"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "creates a sub-locator by text"
      (let [container (page/locator *page* "#container")
            sub-link (sut/loc-get-by-text container "Link text")]
        (expect (instance? Locator sub-link))
        (expect (= "Link text" (sut/text-content sub-link)))))))

;; =============================================================================
;; Content Frame
;; =============================================================================

(defdescribe content-frame-test
  "Tests for locator content-frame (returns FrameLocator for iframes)"

  (describe "content-frame"
    {:context [with-playwright with-browser with-page]}

    (it "returns FrameLocator for iframe element"
      (page/set-content! *page*
        "<iframe id='myframe' srcdoc='<h1>Inside Frame</h1><p>Frame content</p>'></iframe>")
      ;; Wait for iframe to load
      (page/wait-for-load-state *page*)
      (let [iframe-loc (page/locator *page* "#myframe")
            fl (sut/content-frame iframe-loc)]
        (expect (instance? FrameLocator fl))
        (let [h1 (.locator fl "h1")]
          (expect (= "Inside Frame" (sut/text-content h1))))))))
