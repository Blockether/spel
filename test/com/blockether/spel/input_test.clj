(ns com.blockether.spel.input-test
  "Tests for the input namespace - keyboard and mouse operations."
  (:require
   [com.blockether.spel.input :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it before-each]])
  (:import
   [com.microsoft.playwright Keyboard Mouse]))

;; Test HTML with input and clickable button
(def ^:private test-html
  "<html>
     <head><title>Input Test</title></head>
     <body>
       <input id='text-input' type='text' placeholder='Type here'/>
       <button id='click-btn' onclick='document.getElementById(\"result\").textContent=\"clicked\"'>Click</button>
       <div id='result'></div>
     </body>
   </html>")

;; =============================================================================
;; Keyboard Tests
;; =============================================================================

(defdescribe keyboard-test
  "Tests for keyboard operations (key-type, key-press)"

  (describe "keyboard instance"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "page-keyboard returns Keyboard instance"
      (let [kb (page/page-keyboard *page*)]
        (expect (instance? Keyboard kb)))))

  (describe "key-type"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "types text into a focused input"
      (let [input (page/locator *page* "#text-input")
            kb (page/page-keyboard *page*)]
        ;; Click to focus the input
        (locator/click input)
        ;; Type text
        (sut/key-type kb "Hello World")
        ;; Verify input value
        (expect (= "Hello World" (locator/input-value input))))))

  (describe "key-press"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "presses Enter key"
      (let [input (page/locator *page* "#text-input")
            kb (page/page-keyboard *page*)]
        ;; Click to focus the input
        (locator/click input)
        ;; Press Enter (no error means success)
        (expect (nil? (sut/key-press kb "Enter")))))))

;; =============================================================================
;; Mouse Tests
;; =============================================================================

(defdescribe mouse-test
  "Tests for mouse operations (mouse-click)"

  (describe "mouse instance"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "page-mouse returns Mouse instance"
      (let [mouse (page/page-mouse *page*)]
        (expect (instance? Mouse mouse)))))

  (describe "mouse-click"
    {:context [with-playwright with-browser with-page]}
    (before-each
      (page/set-content! *page* test-html))

    (it "clicks at coordinates"
      (let [btn      (page/locator *page* "#click-btn")
            mouse    (page/page-mouse *page*)
            bb       (locator/bounding-box btn)
            center-x (+ (:x bb) (/ (:width bb) 2))
            center-y (+ (:y bb) (/ (:height bb) 2))]
        ;; Click at the center of the button
        (sut/mouse-click mouse center-x center-y)
        ;; Wait a bit for the click to take effect
        (page/wait-for-timeout *page* 100)
        ;; Verify the click had an effect
        (expect (= "clicked" (locator/text-content (page/locator *page* "#result"))))))))
