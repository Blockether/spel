(ns com.blockether.spel.input-test
  "Tests for the input namespace - keyboard and mouse operations."
  (:require
   [com.blockether.spel.core :as core]
   [com.blockether.spel.input :as sut]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.allure :refer [defdescribe describe expect it around]])
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
  (around [f] (core/with-testing-browser (f)))

  (describe "keyboard instance"

    (it "page-keyboard returns Keyboard instance"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [kb (page/page-keyboard pg)]
          (expect (instance? Keyboard kb))))))

  (describe "key-type"

    (it "types text into a focused input"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")
              kb (page/page-keyboard pg)]
        ;; Click to focus the input
          (locator/click input)
        ;; Type text
          (sut/key-type kb "Hello World")
        ;; Verify input value
          (expect (= "Hello World" (locator/input-value input)))))))

  (describe "key-press"

    (it "presses Enter key"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [input (page/locator pg "#text-input")
              kb (page/page-keyboard pg)]
        ;; Click to focus the input
          (locator/click input)
        ;; Press Enter (no error means success)
          (expect (nil? (sut/key-press kb "Enter"))))))))

;; =============================================================================
;; Mouse Tests
;; =============================================================================

(defdescribe mouse-test
  "Tests for mouse operations (mouse-click)"
  (around [f] (core/with-testing-browser (f)))

  (describe "mouse instance"

    (it "page-mouse returns Mouse instance"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [mouse (page/page-mouse pg)]
          (expect (instance? Mouse mouse))))))

  (describe "mouse-click"

    (it "clicks at coordinates"
      (core/with-testing-page [pg]
        (page/set-content! pg test-html)
        (let [btn      (page/locator pg "#click-btn")
              mouse    (page/page-mouse pg)
              bb       (locator/bounding-box btn)
              center-x (+ (:x bb) (/ (:width bb) 2))
              center-y (+ (:y bb) (/ (:height bb) 2))]
        ;; Click at the center of the button
          (sut/mouse-click mouse center-x center-y)
        ;; Wait a bit for the click to take effect
          (page/wait-for-timeout pg 100)
        ;; Verify the click had an effect
          (expect (= "clicked" (locator/text-content (page/locator pg "#result")))))))))
