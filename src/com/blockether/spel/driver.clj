(ns com.blockether.spel.driver
  "External Playwright driver management for native-image builds.

   Instead of bundling Playwright's large Node.js driver in the native binary,
   this module materializes it into a local cache on first use.

   Playwright shut down its prebuilt driver CDN (cdn.playwright.dev/builds/driver)
   in mid-2026. For Playwright Java 1.61.0+, the official upstream layout changed:
   `driver.jar` now contains `playwright-core` (`driver/package/...`) and
   `driver-bundle.jar` contains only the per-platform Node runtimes
   (`driver/<platform>/node`). This module mirrors that official layout by
   sourcing those same Maven artifacts, extracting only the current platform,
   and assembling the exact directory Playwright Java expects:

     <driver-dir>/node              # the Node.js runtime (node.exe on Windows)
     <driver-dir>/package/cli.js    # the driver entry point

   Source order:
     1. Local Maven cache (~/.m2/repository or -Dmaven.repo.local)
     2. Maven Central as fallback

   Cache: ~/.cache/spel/<version>/<platform>/

   Configuration (checked in order):
     1. playwright.cli.dir system property — points to pre-installed driver dir
     2. SPEL_DRIVER_DIR env var — overrides cache location
     3. Default: ~/.cache/spel/<version>/<platform>/"
  (:require
   [clojure.string :as str]
   [com.blockether.spel.ssl :as ssl])
  (:import
   [java.io InputStream]
   [java.net HttpURLConnection URL]
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.util.zip ZipInputStream]
   [javax.net.ssl HttpsURLConnection SSLSocketFactory]))

;; =============================================================================
;; Constants
;; =============================================================================

;; Must match com.microsoft.playwright/playwright version in deps.edn.
(def ^:private playwright-version "1.61.0")

(def ^:private maven-base "https://repo1.maven.org/maven2/com/microsoft/playwright")
(def ^:private no-link-opts (into-array java.nio.file.LinkOption []))
(def ^:private no-file-attrs (into-array FileAttribute []))

;; =============================================================================
;; Platform Detection
;; =============================================================================

(defn- is-windows?
  []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win"))

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

(defn- node-binary-name
  []
  (if (is-windows?) "node.exe" "node"))

;; =============================================================================
;; Paths
;; =============================================================================

(defn- cache-dir
  "Returns the driver cache root directory.

   Respects SPEL_DRIVER_DIR env var, otherwise defaults to
   ~/.cache/spel/<version>."
  ^Path []
  (let [override (System/getenv "SPEL_DRIVER_DIR")]
    (if override
      (Paths/get override (into-array String []))
      (Paths/get (System/getProperty "user.home")
        (into-array String [".cache" "spel" playwright-version])))))

(defn- driver-dir
  "Returns the platform-specific driver directory."
  ^Path []
  (.resolve (cache-dir) ^String (platform-name)))

(defn- driver-ready-at?
  ^Boolean [^Path dir]
  (let [node (.resolve dir ^String (node-binary-name))
        cli  (.resolve dir (Paths/get "package" (into-array String ["cli.js"])))
        pkg  (.resolve dir (Paths/get "package" (into-array String ["package.json"])))]
    (and (Files/exists node no-link-opts)
      (Files/exists cli no-link-opts)
      (Files/exists pkg no-link-opts))))

(defn driver-ready?
  "Checks if the driver is already extracted and ready to use."
  []
  (driver-ready-at? (driver-dir)))

(defn- maven-local-repo
  ^Path []
  (if-let [repo (System/getProperty "maven.repo.local")]
    (Paths/get repo (into-array String []))
    (Paths/get (System/getProperty "user.home")
      (into-array String [".m2" "repository"]))))

(defn- local-artifact-path
  ^Path [^String artifact]
  (Paths/get (.toString (maven-local-repo))
    (into-array String ["com" "microsoft" "playwright"
                        artifact
                        playwright-version
                        (str artifact "-" playwright-version ".jar")])))

(defn- artifact-url
  ^String [^String artifact]
  (str maven-base "/" artifact "/" playwright-version "/"
    artifact "-" playwright-version ".jar"))

;; =============================================================================
;; HTTP fetch
;; =============================================================================

(defn- open-conn
  "Opens an HttpURLConnection following redirects, with the project's custom
   SSL factory attached (corporate proxy certificate support)."
  ^HttpURLConnection [^String url-str]
  (let [url  (URL. url-str)
        conn ^HttpURLConnection (.openConnection url)]
    (when (instance? HttpsURLConnection conn)
      (when-let [^SSLSocketFactory sf (ssl/custom-ssl-factory)]
        (.setSSLSocketFactory ^HttpsURLConnection conn sf)))
    (.setInstanceFollowRedirects conn true)
    (.setRequestMethod conn "GET")
    (.setConnectTimeout conn 30000)
    (.setReadTimeout conn 120000)
    conn))

(defn- http-input-stream
  "Connects and returns the response InputStream, throwing on non-200."
  ^InputStream [^String url-str]
  (let [conn (open-conn url-str)]
    (let [code (.getResponseCode conn)]
      (when (not= 200 code)
        (.disconnect conn)
        (throw (ex-info (str "Failed to download: HTTP " code)
                 {:url url-str :status code}))))
    (.getInputStream conn)))

(defn- download-file
  "Downloads url-str fully into dest."
  [^String url-str ^Path dest]
  (Files/createDirectories (.getParent dest) no-file-attrs)
  (with-open [in (http-input-stream url-str)]
    (Files/copy in dest
      ^"[Ljava.nio.file.CopyOption;"
      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

;; =============================================================================
;; Extract helpers
;; =============================================================================

(defn- set-executable!
  "Sets +x on a file. No-op on Windows."
  [^Path path]
  (when-not (is-windows?)
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

(defn- strip-prefix
  "Strips a leading path prefix from an archive entry name. Returns nil if the
   entry is not under the prefix (so it can be skipped)."
  ^String [^String name ^String prefix]
  (cond
    (= name prefix)                     nil
    (.startsWith name (str prefix "/")) (subs name (inc (count prefix)))
    :else                               nil))

(defn- copy-entry-to
  "Writes the current archive entry's content to target, creating parents."
  [^InputStream in ^Path target]
  (Files/createDirectories (.getParent target) no-file-attrs)
  (Files/copy in target
    ^"[Ljava.nio.file.CopyOption;"
    (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- extract-zip
  "Extracts a zip into tmp-dir, stripping the given top-level directory prefix."
  [^Path archive ^Path tmp-dir ^String strip-dir]
  (with-open [fis (Files/newInputStream archive (into-array java.nio.file.OpenOption []))
              zis (ZipInputStream. fis)]
    (loop []
      (when-let [entry (.getNextEntry zis)]
        (let [name ^String (.getName entry)
              rel  (strip-prefix name strip-dir)]
          (when (and rel (not (str/blank? rel)))
            (if (.endsWith rel "/")
              (Files/createDirectories (.resolve tmp-dir rel) no-file-attrs)
              (copy-entry-to zis (.resolve tmp-dir rel)))))
        (recur)))))

;; =============================================================================
;; Assemble driver
;; =============================================================================

(defn- materialize-artifact!
  "Copies an official Playwright Maven artifact into tmp-dir.

   Prefers the local Maven cache (~/.m2 or -Dmaven.repo.local) and falls back
   to Maven Central when the artifact is absent. Returns the copied file path."
  ^Path [^String artifact ^Path tmp-dir]
  (let [dest  (.resolve tmp-dir (str artifact ".jar"))
        local (local-artifact-path artifact)]
    (if (Files/exists local no-link-opts)
      (do
        (binding [*out* *err*]
          (println (str "  - " artifact " (local Maven cache: " local ")")))
        (Files/copy local dest
          ^"[Ljava.nio.file.CopyOption;"
          (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING])))
      (do
        (binding [*out* *err*]
          (println (str "  - " artifact " (" (artifact-url artifact) ")")))
        (download-file (artifact-url artifact) dest)))
    dest))

(defn- extract-driver-package!
  "Extracts Playwright's JS package from driver.jar into tmp-dir/package."
  [^Path archive ^Path tmp-dir]
  (extract-zip archive tmp-dir "driver"))

(defn- extract-node!
  "Extracts only the current platform's Node runtime from driver-bundle.jar."
  [^Path archive ^Path tmp-dir]
  (extract-zip archive tmp-dir (str "driver/" (platform-name)))
  (set-executable! (.resolve tmp-dir ^String (node-binary-name))))

(defn- assemble-driver!
  "Assembles the driver into a temp directory under parent, then atomically
   moves it to the final cache location.

   Uses the official Playwright Java Maven artifacts:
     1. driver.jar         → package/ directory
     2. driver-bundle.jar  → current platform node binary"
  []
  (let [dir    (driver-dir)
        parent (.getParent dir)]
    (Files/createDirectories parent no-file-attrs)
    (let [^Path tmp-dir (Files/createTempDirectory
                          parent
                          (str ".tmp-" (platform-name) "-")
                          no-file-attrs)]
      (try
        (binding [*out* *err*]
          (println (str "spel: assembling Playwright Java driver v" playwright-version
                     " for " (platform-name) "...")))
        (let [driver-jar (.resolve tmp-dir "driver.jar")
              bundle-jar (.resolve tmp-dir "driver-bundle.jar")]
          (materialize-artifact! "driver" tmp-dir)
          (extract-driver-package! driver-jar tmp-dir)
          (Files/deleteIfExists driver-jar)

          (materialize-artifact! "driver-bundle" tmp-dir)
          (extract-node! bundle-jar tmp-dir)
          (Files/deleteIfExists bundle-jar))

        (when-not (driver-ready-at? tmp-dir)
          (throw (ex-info "Assembled driver is incomplete"
                   {:dir tmp-dir :platform (platform-name)})))

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
  "Ensures the Playwright Java Node.js driver is available locally.

   Materializes it from the official Playwright Java Maven artifacts on first
   use and sets the playwright.cli.dir system property so Playwright Java uses
   the external driver instead of trying to extract from bundled resources.

   No-op if playwright.cli.dir is already set (for custom installations)."
  []
  ;; Respect pre-configured system property
  (when-not (System/getProperty "playwright.cli.dir")
    (when-not (driver-ready?)
      (assemble-driver!))
    (System/setProperty "playwright.cli.dir"
      (.toString (driver-dir)))))
