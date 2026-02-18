(ns com.blockether.spel.driver
  "External Playwright driver management for native-image builds.

   Instead of bundling Playwright's ~600MB Node.js driver in the native binary,
   this module downloads and caches it on first use.

    Cache:  ~/.cache/spel/<version>/<platform>/
    CDN:    https://cdn.playwright.dev/builds/driver/playwright-<version>-<platform>.zip

    Configuration (checked in order):
      1. playwright.cli.dir system property — points to pre-installed driver dir
      2. SPEL_DRIVER_DIR env var — overrides cache location
      3. Default: ~/.cache/spel/<version>/<platform>/"
  (:require
   [clojure.string :as str])
  (:import
   [java.io InputStream]
   [java.net HttpURLConnection URL]
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.util.zip ZipInputStream]))

(set! *warn-on-reflection* true)

;; =============================================================================
;; Constants
;; =============================================================================

;; Must match com.microsoft.playwright/playwright version in deps.edn
(def ^:private playwright-version "1.58.0")

(def ^:private cdn-base "https://cdn.playwright.dev/builds/driver")

;; =============================================================================
;; Platform Detection
;; =============================================================================

(defn- platform-name
  "Returns the Playwright platform identifier for the current OS/arch.

   Matches the directory names used in Playwright's driver-bundle:
   mac, mac-arm64, linux, linux-arm64, win32_x64"
  []
  (let [os   (str/lower-case (System/getProperty "os.name" ""))
        arch (str/lower-case (System/getProperty "os.arch" ""))]
    (cond
      (str/includes? os "win")   "win32_x64"
      (str/includes? os "linux") (if (= arch "aarch64") "linux-arm64" "linux")
      (str/includes? os "mac")   (if (= arch "aarch64") "mac-arm64" "mac")
      :else (throw (ex-info (str "Unsupported platform: " os " / " arch) {})))))

;; =============================================================================
;; Paths
;; =============================================================================

(defn- ^Path cache-dir
  "Returns the driver cache root directory.

    Respects SPEL_DRIVER_DIR env var, otherwise defaults to
    ~/.cache/spel/<version>."
  []
  (let [override (System/getenv "SPEL_DRIVER_DIR")]
    (if override
      (Paths/get override (into-array String []))
      (Paths/get (System/getProperty "user.home")
        (into-array String [".cache" "spel" playwright-version])))))

(defn- ^Path driver-dir
  "Returns the platform-specific driver directory."
  []
  (.resolve (cache-dir) ^String (platform-name)))

(def ^:private no-link-opts (into-array java.nio.file.LinkOption []))
(def ^:private no-file-attrs (into-array FileAttribute []))

(defn driver-ready?
  "Checks if the driver is already extracted and ready to use.
   Verifies both the Node.js binary and the CLI entry point exist."
  []
  (let [dir  (driver-dir)
        node ^String (if (str/includes? (System/getProperty "os.name" "") "Windows")
                       "node.exe" "node")
        cli  (.resolve dir (Paths/get "package" (into-array String ["cli.js"])))]
    (and (Files/exists (.resolve dir node) no-link-opts)
      (Files/exists cli no-link-opts))))

;; =============================================================================
;; Download & Extract
;; =============================================================================

(defn- ^String download-url
  "Returns the CDN URL for the current platform's driver archive."
  []
  (str cdn-base "/playwright-" playwright-version "-" (platform-name) ".zip"))

(defn- set-executable!
  "Sets +x on a file. No-op on Windows."
  [^Path path]
  (when-not (str/includes? (System/getProperty "os.name" "") "Windows")
    (try
      (.setExecutable (.toFile path) true true)
      (catch Exception e
        (binding [*out* *err*]
          (println (str "spel: warn: set-executable failed for " path ": " (.getMessage e))))))))

(defn- delete-tree!
  "Recursively deletes a directory tree. Best-effort, ignores errors."
  [^Path root]
  (when (Files/exists root no-link-opts)
    (try
      (let [stream (Files/walk root (into-array java.nio.file.FileVisitOption []))]
        (doseq [^Path f (reverse (vec (iterator-seq (.iterator stream))))]
          (Files/deleteIfExists f))
        (.close stream))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "spel: warn: delete-tree failed for " root ": " (.getMessage e))))))))

(defn- download-and-extract!
  "Downloads the driver ZIP from Playwright CDN and extracts it.

   Extracts to a temp directory first, then atomically moves to the
   final cache location to avoid partial extractions."
  []
  (let [url-str (download-url)
        dir     (driver-dir)
        parent  (.getParent dir)]
    (Files/createDirectories parent no-file-attrs)
    (let [^Path tmp-dir (Files/createTempDirectory
                          parent
                          (str ".tmp-" (platform-name) "-")
                          no-file-attrs)]
      (try
        (binding [*out* *err*]
          (println (str "spel: downloading driver v" playwright-version
                     " for " (platform-name) "...")))
        (let [url  (URL. url-str)
              conn ^HttpURLConnection (.openConnection url)]
          (.setInstanceFollowRedirects conn true)
          (.setRequestMethod conn "GET")
          (.setConnectTimeout conn 30000)
          (.setReadTimeout conn 120000)
          (.connect conn)
          (let [code (.getResponseCode conn)]
            (when (not= 200 code)
              (throw (ex-info (str "Failed to download driver: HTTP " code)
                       {:url url-str :status code}))))
          (with-open [zis (ZipInputStream. (.getInputStream conn))]
            (loop []
              (when-let [entry (.getNextEntry zis)]
                (let [name   ^String (.getName entry)
                      target (.resolve tmp-dir name)]
                  (if (.isDirectory entry)
                    (Files/createDirectories target no-file-attrs)
                    (do
                      (Files/createDirectories (.getParent target) no-file-attrs)
                      (Files/copy ^InputStream zis target
                        ^"[Ljava.nio.file.CopyOption;" (into-array java.nio.file.CopyOption
                                                         [StandardCopyOption/REPLACE_EXISTING]))
                      ;; Make Node.js binary and shell scripts executable
                      (when (or (str/ends-with? name "/node")
                              (= name "node")
                              (str/ends-with? name ".sh"))
                        (set-executable! target)))))
                (recur))))
          (.disconnect conn))

        ;; Atomic move temp → final
        (if (Files/exists dir no-link-opts)
          ;; Another process beat us — clean up temp
          (delete-tree! tmp-dir)
          (try
            (Files/move tmp-dir dir
              (into-array java.nio.file.CopyOption
                [StandardCopyOption/ATOMIC_MOVE]))
            (catch java.nio.file.AtomicMoveNotSupportedException _
              (Files/move tmp-dir dir
                (into-array java.nio.file.CopyOption [])))
            (catch java.nio.file.FileAlreadyExistsException _
              ;; Race condition — another process created it
              (delete-tree! tmp-dir))))

        (binding [*out* *err*]
          (println "spel: driver ready."))

        (catch Exception e
          (delete-tree! tmp-dir)
          (throw e))))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn ensure-driver!
  "Ensures the Playwright Node.js driver is available locally.

   Downloads from Playwright CDN on first use (~120MB per platform).
   Sets the playwright.cli.dir system property so Playwright Java uses
   the external driver instead of trying to extract from bundled resources.

   No-op if playwright.cli.dir is already set (for custom installations)."
  []
  ;; Respect pre-configured system property
  (when-not (System/getProperty "playwright.cli.dir")
    (when-not (driver-ready?)
      (download-and-extract!))
    (System/setProperty "playwright.cli.dir"
      (.toString (driver-dir)))))
