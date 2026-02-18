(ns com.blockether.spel.input
  "Keyboard, Mouse, and Touchscreen operations."
  (:require
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright Keyboard Mouse Touchscreen]))

;; =============================================================================
;; Keyboard
;; =============================================================================

(defn key-press
  "Presses a key on the keyboard.
   
   Params:
   `keyboard` - Keyboard instance.
   `key`      - String. Key to press (e.g. \"Enter\", \"Control+a\").
   `opts`     - Map, optional. {:delay ms}.
   
   Returns:
   nil or anomaly map."
  ([^Keyboard keyboard ^String key]
   (safe (.press keyboard key)))
  ([^Keyboard keyboard ^String key press-opts]
   (safe (.press keyboard key (opts/->keyboard-press-options press-opts)))))

(defn key-type
  "Types text character by character.
   
   Params:
   `keyboard` - Keyboard instance.
   `text`     - String. Text to type.
   `opts`     - Map, optional. {:delay ms}.
   
   Returns:
   nil or anomaly map."
  ([^Keyboard keyboard ^String text]
   (safe (.type keyboard text)))
  ([^Keyboard keyboard ^String text type-opts]
   (safe (.type keyboard text (opts/->keyboard-type-options type-opts)))))

(defn key-down
  "Dispatches a keydown event.
   
   Params:
   `keyboard` - Keyboard instance.
   `key`      - String. Key name."
  [^Keyboard keyboard ^String key]
  (safe (.down keyboard key)))

(defn key-up
  "Dispatches a keyup event.
   
   Params:
   `keyboard` - Keyboard instance.
   `key`      - String. Key name."
  [^Keyboard keyboard ^String key]
  (safe (.up keyboard key)))

(defn key-insert-text
  "Inserts text without key events.
   
   Params:
   `keyboard` - Keyboard instance.
   `text`     - String. Text to insert."
  [^Keyboard keyboard ^String text]
  (safe (.insertText keyboard text)))

;; =============================================================================
;; Mouse
;; =============================================================================

(defn mouse-click
  "Clicks at the given coordinates.
   
   Params:
   `mouse` - Mouse instance.
   `x`     - Double. X coordinate.
   `y`     - Double. Y coordinate.
   `opts`  - Map, optional. Click options.
   
   Returns:
   nil or anomaly map."
  ([^Mouse mouse x y]
   (safe (.click mouse (double x) (double y))))
  ([^Mouse mouse x y click-opts]
   (safe (.click mouse (double x) (double y) (opts/->mouse-click-options click-opts)))))

(defn mouse-dblclick
  "Double-clicks at the given coordinates.
   
   Params:
   `mouse` - Mouse instance.
   `x`     - Double. X coordinate.
   `y`     - Double. Y coordinate.
   `opts`  - Map, optional.
   
   Returns:
   nil or anomaly map."
  ([^Mouse mouse x y]
   (safe (.dblclick mouse (double x) (double y))))
  ([^Mouse mouse x y dblclick-opts]
   (safe (.dblclick mouse (double x) (double y) (opts/->mouse-dblclick-options dblclick-opts)))))

(defn mouse-move
  "Moves the mouse to the given coordinates.
   
   Params:
   `mouse` - Mouse instance.
   `x`     - Double. X coordinate.
   `y`     - Double. Y coordinate.
   `opts`  - Map, optional. {:steps n}.
   
   Returns:
   nil or anomaly map."
  ([^Mouse mouse x y]
   (safe (.move mouse (double x) (double y))))
  ([^Mouse mouse x y move-opts]
   (safe (.move mouse (double x) (double y) (opts/->mouse-move-options move-opts)))))

(defn mouse-down
  "Dispatches a mousedown event.
   
   Params:
   `mouse` - Mouse instance.
   
   Returns:
   nil or anomaly map."
  [^Mouse mouse]
  (safe (.down mouse)))

(defn mouse-up
  "Dispatches a mouseup event.
   
   Params:
   `mouse` - Mouse instance.
   
   Returns:
   nil or anomaly map."
  [^Mouse mouse]
  (safe (.up mouse)))

(defn mouse-wheel
  "Dispatches a wheel event.
   
   Params:
   `mouse`  - Mouse instance.
   `delta-x` - Double. Horizontal scroll amount.
   `delta-y` - Double. Vertical scroll amount.
   
   Returns:
   nil or anomaly map."
  [^Mouse mouse delta-x delta-y]
  (safe (.wheel mouse (double delta-x) (double delta-y))))

;; =============================================================================
;; Touchscreen
;; =============================================================================

(defn touchscreen-tap
  "Taps at the given coordinates.
   
   Params:
   `ts` - Touchscreen instance.
   `x`  - Double. X coordinate.
   `y`  - Double. Y coordinate.
   
   Returns:
   nil or anomaly map."
  [^Touchscreen ts x y]
  (safe (.tap ts (double x) (double y))))
