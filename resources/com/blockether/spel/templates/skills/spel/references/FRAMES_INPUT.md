# Frames and input devices

Working with iframes and low-level input (keyboard, mouse, touch).

## Frame navigation

Use `FrameLocator` when iframe selector known upfront. For dynamic frames, grab frame object from `page/frames` and use frame-specific locator methods.

## Frame navigation

```clojure
;; Via FrameLocator (preferred)
(let [fl (frame/frame-locator-obj pg "iframe#main")]
  (locator/click (frame/fl-locator fl "button")))

;; Via Locator.contentFrame()
(let [fl (locator/content-frame (page/locator pg "iframe"))]
  (locator/click (.locator fl "h1")))

;; Nested frames
(let [fl1 (frame/frame-locator-obj pg "iframe.outer")
      fl2 (.frameLocator (frame/fl-locator fl1 "iframe.inner") "iframe.inner")]
  (locator/click (frame/fl-locator fl2 "button")))

;; Frame hierarchy
(let [main-frame (page/main-frame pg)
      children (frame/child-frames main-frame)]
  (doseq [f children]
    (println "Frame:" (frame/frame-name f) "URL:" (frame/frame-url f))))

;; Frame locator methods (same as page)
(let [f (first (page/frames pg))]
  (frame/frame-locator f "button")
  (frame/frame-get-by-text f "Click me")
  (frame/frame-get-by-role f role/button)
  (frame/frame-get-by-label f "Email")
  (frame/frame-evaluate f "document.title"))

;; FrameLocator sub-locators
(let [fl (frame/frame-locator-obj pg "iframe")]
  (frame/fl-locator fl "button")
  (frame/fl-get-by-text fl "Submit")
  (frame/fl-get-by-role fl role/link)
  (frame/fl-get-by-label fl "Password")
  (frame/fl-first fl)
  (frame/fl-last fl)
  (frame/fl-nth fl 0))
```

## Keyboard press (high-level)

For most keyboard interactions, use high-level `keyboard-press` / `press` functions:

```clojure
;; Page-level keyboard press (no selector needed)
(page/keyboard-press pg "Escape")
(page/keyboard-press pg "Enter")
(page/keyboard-press pg "Tab")
(page/keyboard-press pg "Control+a")

;; SCI / eval-sci equivalents:
(spel/press "Escape")              ;; page-level keyboard press
(spel/keyboard-press "Tab")        ;; explicit alias

;; Locator-level press (on a specific element)
(spel/press "#my-input" "Enter")   ;; two-arg form presses on element
```

## Input devices (low-level)

Low-level keyboard, mouse, touch events. Most interactions should go through `spel/click`, `spel/fill`, `spel/press`, etc. Use these only when you need precise control over timing or event sequences.

```clojure
(require '[com.blockether.spel.input :as input])

;; Keyboard (low-level — prefer spel/press or page/keyboard-press instead)
(let [kb (page/page-keyboard pg)]
  (input/key-press kb "Enter")
  (input/key-press kb "Control+a")
  (input/key-press kb "Shift+ArrowRight" {:delay 100})
  (input/key-type kb "Hello World" {:delay 50})
  (input/key-down kb "Shift")
  (input/key-up kb "Shift")
  (input/key-insert-text kb "直接挿入"))  ; insert without key events

;; Mouse
(let [mouse (page/page-mouse pg)]
  (input/mouse-click mouse 100 200)
  (input/mouse-dblclick mouse 100 200)
  (input/mouse-move mouse 300 400 {:steps 10})
  (input/mouse-down mouse)
  (input/mouse-up mouse)
  (input/mouse-wheel mouse 0 100))    ; scroll down 100px

;; Touchscreen
(let [ts (page/page-touchscreen pg)]
  (input/touchscreen-tap ts 100 200))
```
