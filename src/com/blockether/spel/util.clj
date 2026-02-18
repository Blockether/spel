(ns com.blockether.spel.util
  "Utility classes: Dialog, Download, ConsoleMessage, CDPSession, Clock,
   Tracing, Video, WebError, Worker, FileChooser, Selectors."
  (:require
   [com.blockether.spel.core :refer [safe]]
   [com.blockether.spel.options :as opts])
  (:import
   [com.microsoft.playwright
    Dialog Download ConsoleMessage CDPSession Clock
    Tracing Video WebError Worker FileChooser Selectors
    BrowserContext Page]))

;; =============================================================================
;; Dialog
;; =============================================================================

(defn dialog-type
  "Returns the dialog type (alert, confirm, prompt, beforeunload).
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.type dialog))

(defn dialog-message
  "Returns the dialog message.
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.message dialog))

(defn dialog-default-value
  "Returns the default value for prompt dialogs.
   
   Params:
   `dialog` - Dialog instance.
   
   Returns:
   String."
  ^String [^Dialog dialog]
  (.defaultValue dialog))

(defn dialog-accept!
  "Accepts the dialog.
   
   Params:
   `dialog`     - Dialog instance.
   `prompt-text` - String, optional. Text for prompt dialogs."
  ([^Dialog dialog]
   (safe (.accept dialog)))
  ([^Dialog dialog ^String prompt-text]
   (safe (.accept dialog prompt-text))))

(defn dialog-dismiss!
  "Dismisses the dialog.
   
   Params:
   `dialog` - Dialog instance."
  [^Dialog dialog]
  (safe (.dismiss dialog)))

;; =============================================================================
;; Download
;; =============================================================================

(defn download-url
  "Returns the download URL.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String."
  ^String [^Download download]
  (.url download))

(defn download-suggested-filename
  "Returns the suggested filename.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String."
  ^String [^Download download]
  (.suggestedFilename download))

(defn download-path
  "Returns the local path to the downloaded file.
   
   Params:
   `download` - Download instance.
   
   Returns:
   Path or nil."
  [^Download download]
  (safe (.path download)))

(defn download-save-as!
  "Saves the download to the given path.
   
   Params:
   `download` - Download instance.
   `path`     - String. Destination path."
  [^Download download ^String path]
  (safe (.saveAs download (java.nio.file.Paths/get path (into-array String [])))))

(defn download-cancel!
  "Cancels the download.
   
   Params:
   `download` - Download instance."
  [^Download download]
  (.cancel download))

(defn download-failure
  "Returns the download failure reason, or nil.
   
   Params:
   `download` - Download instance.
   
   Returns:
   String or nil."
  [^Download download]
  (.failure download))

(defn download-page
  "Returns the page the download belongs to.
   
   Params:
   `download` - Download instance.
   
   Returns:
   Page instance."
  ^Page [^Download download]
  (.page download))

;; =============================================================================
;; ConsoleMessage
;; =============================================================================

(defn console-type
  "Returns the console message type (log, debug, info, error, warning, etc).
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String."
  ^String [^ConsoleMessage msg]
  (.type msg))

(defn console-text
  "Returns the console message text.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String."
  ^String [^ConsoleMessage msg]
  (.text msg))

(defn console-args
  "Returns the console message arguments as JSHandles.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   Vector of JSHandle."
  [^ConsoleMessage msg]
  (vec (.args msg)))

(defn console-location
  "Returns the source location of the console message.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   String. The location string."
  ^String [^ConsoleMessage msg]
  (.location msg))

(defn console-page
  "Returns the page the console message belongs to.
   
   Params:
   `msg` - ConsoleMessage instance.
   
   Returns:
   Page instance."
  ^Page [^ConsoleMessage msg]
  (.page msg))

;; =============================================================================
;; CDPSession
;; =============================================================================

(defn cdp-send
  "Sends a Chrome DevTools Protocol command.
   
   Params:
   `session` - CDPSession instance.
   `method`  - String. CDP method name.
   `params`  - Map, optional. CDP parameters.
   
   Returns:
   JSON result or anomaly map."
  ([^CDPSession session ^String method]
   (safe (.send session method)))
  ([^CDPSession session ^String method params]
   (let [json-obj (com.google.gson.JsonObject.)]
     (doseq [[k v] params]
       (.addProperty json-obj (name k) (str v)))
     (safe (.send session method json-obj)))))

(defn cdp-detach!
  "Detaches the CDP session.
   
   Params:
   `session` - CDPSession instance."
  [^CDPSession session]
  (.detach session))

(defn cdp-on
  "Registers a handler for CDP events.
   
   Params:
   `session` - CDPSession instance.
   `event`   - String. Event name.
   `handler` - Function that receives the event data."
  [^CDPSession session ^String event handler]
  (.on session event
    (reify java.util.function.Consumer
      (accept [_ data] (handler data)))))

;; =============================================================================
;; Clock
;; =============================================================================

(defn clock-install!
  "Installs fake timers on the clock.
   
   Params:
   `clock` - Clock instance."
  [^Clock clock]
  (.install clock))

(defn clock-set-fixed-time!
  "Sets the clock to a fixed time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.setFixedTime clock (long time)))

(defn clock-set-system-time!
  "Sets the system time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.setSystemTime clock (long time)))

(defn clock-fast-forward!
  "Fast-forwards the clock by the given time.
   
   Params:
   `clock` - Clock instance.
   `ticks` - Long. Time to advance in ms."
  [^Clock clock ticks]
  (.fastForward clock (long ticks)))

(defn clock-pause-at!
  "Pauses the clock at the given time.
   
   Params:
   `clock` - Clock instance.
   `time`  - Long. Unix timestamp in ms."
  [^Clock clock time]
  (.pauseAt clock (long time)))

(defn clock-resume!
  "Resumes the clock.
   
   Params:
   `clock` - Clock instance."
  [^Clock clock]
  (.resume clock))

(defn page-clock
  "Returns the Clock for a page.
   
   Params:
   `page` - Page instance.
   
   Returns:
   Clock instance."
  ^Clock [^Page page]
  (.clock page))

;; =============================================================================
;; Tracing
;; =============================================================================

(defn context-tracing
  "Returns the Tracing for a context.
   
   Params:
   `context` - BrowserContext instance.
   
   Returns:
   Tracing instance."
  ^Tracing [^BrowserContext context]
  (.tracing context))

(defn tracing-start!
  "Starts tracing.
   
   Params:
   `tracing` - Tracing instance.
   `opts`    - Map, optional. Tracing options."
  ([^Tracing tracing]
   (safe (.start tracing)))
  ([^Tracing tracing trace-opts]
   (safe (.start tracing (opts/->tracing-start-options trace-opts)))))

(defn tracing-stop!
  "Stops tracing and saves the trace file.
   
   Params:
   `tracing` - Tracing instance.
   `opts`    - Map, optional. {:path \"trace.zip\"}."
  ([^Tracing tracing]
   (safe (.stop tracing)))
  ([^Tracing tracing stop-opts]
   (safe (.stop tracing (opts/->tracing-stop-options stop-opts)))))

;; =============================================================================
;; Video
;; =============================================================================

(defn video-path
  "Returns the path to the video file.
   
   Params:
   `video` - Video instance.
   
   Returns:
   Path or anomaly map."
  [^Video video]
  (safe (.path video)))

(defn video-save-as!
  "Saves the video to the given path.
   
   Params:
   `video` - Video instance.
   `path`  - String. Destination path."
  [^Video video ^String path]
  (safe (.saveAs video (java.nio.file.Paths/get path (into-array String [])))))

(defn video-delete!
  "Deletes the video file.
   
   Params:
   `video` - Video instance."
  [^Video video]
  (safe (.delete video)))

;; =============================================================================
;; Worker
;; =============================================================================

(defn worker-url
  "Returns the worker URL.
   
   Params:
   `worker` - Worker instance.
   
   Returns:
   String."
  ^String [^Worker worker]
  (.url worker))

(defn worker-evaluate
  "Evaluates JavaScript in the worker context.
   
   Params:
   `worker`     - Worker instance.
   `expression` - String.
   `arg`        - Optional argument.
   
   Returns:
   Result or anomaly map."
  ([^Worker worker ^String expression]
   (safe (.evaluate worker expression)))
  ([^Worker worker ^String expression arg]
   (safe (.evaluate worker expression arg))))

;; =============================================================================
;; FileChooser
;; =============================================================================

(defn file-chooser-page
  "Returns the page the file chooser belongs to.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   Page instance."
  ^Page [^FileChooser fc]
  (.page fc))

(defn file-chooser-element
  "Returns the element handle for the file input.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   ElementHandle."
  [^FileChooser fc]
  (.element fc))

(defn file-chooser-is-multiple?
  "Returns whether the file chooser accepts multiple files.
   
   Params:
   `fc` - FileChooser instance.
   
   Returns:
   Boolean."
  [^FileChooser fc]
  (.isMultiple fc))

(defn file-chooser-set-files!
  "Sets the files for the file chooser.
   
   Params:
   `fc`    - FileChooser instance.
   `files` - String path or vector of paths."
  [^FileChooser fc files]
  (safe
    (if (sequential? files)
      (.setFiles fc ^"[Ljava.nio.file.Path;"
        (into-array java.nio.file.Path
          (map #(java.nio.file.Paths/get ^String % (into-array String []))
            files)))
      (.setFiles fc (java.nio.file.Paths/get ^String (str files) (into-array String []))))))

;; =============================================================================
;; Selectors
;; =============================================================================

(defn selectors
  "Returns the Selectors for a Playwright instance.
   
   Params:
   `pw` - Playwright instance.
   
   Returns:
   Selectors instance."
  ^Selectors [^com.microsoft.playwright.Playwright pw]
  (.selectors pw))

;; =============================================================================
;; WebError
;; =============================================================================

(defn web-error-page
  "Returns the page that generated this web error, if any.

   Params:
   `we` - WebError instance.

   Returns:
   Page instance or nil."
  [^WebError we]
  (.page we))

(defn web-error-error
  "Returns the underlying error for this web error.

   Params:
   `we` - WebError instance.

   Returns:
   String. The error message."
  [^WebError we]
  (.error we))

(defn selectors-register!
  "Registers a custom selector engine.
   
   Params:
   `sels`   - Selectors instance.
   `name`   - String. Selector engine name.
   `script` - String. JavaScript for the selector engine."
  [^Selectors sels ^String name ^String script]
  (safe (.register sels name script)))
