(ns com.blockether.spel.image-util
  "Shared helpers for browser-based image operations."
  (:require
   [com.blockether.spel.core :as core])
  (:import
   [java.nio.file Files Path]
   [java.util Base64]))

(defn file->bytes
  "Reads the bytes of an image file."
  [^String path]
  (Files/readAllBytes (Path/of path (into-array String []))))

(defn bytes->base64
  "Encodes image bytes as Base64."
  [^bytes image-bytes]
  (.encodeToString (Base64/getEncoder) image-bytes))

(defn png-axis-size
  "Reads a PNG dimension as four bytes, most significant byte first."
  ^long [^bytes png-bytes ^long offset]
  (bit-or (bit-shift-left (bit-and (long (aget png-bytes offset)) 0xFF) 24)
    (bit-shift-left (bit-and (long (aget png-bytes (+ offset 1))) 0xFF) 16)
    (bit-shift-left (bit-and (long (aget png-bytes (+ offset 2))) 0xFF) 8)
    (bit-and (long (aget png-bytes (+ offset 3))) 0xFF)))

(defn ensure-playwright!
  "Throws if Playwright did not start."
  [pw]
  (when (core/anomaly? pw)
    (throw (ex-info (str "Failed to create Playwright: " (:anomaly/message pw))
             (select-keys pw [:anomaly/category :anomaly/message])))))

(defn resolve-launch-fn
  "Returns the launch function for the browser type.
   Uses SPEL_BROWSER if no type is given. The default is Chromium."
  [browser-type]
  (case (or browser-type (System/getenv "SPEL_BROWSER") "chromium")
    "firefox" core/launch-firefox
    "webkit"  core/launch-webkit
    core/launch-chromium))
