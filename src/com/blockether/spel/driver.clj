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
     1. Clojure tools.deps resolver (local Maven cache first)
     2. Configured Maven repositories / mirrors / ~/.m2/settings.xml

   Cache: ~/.cache/spel/<version>/<platform>/

   The undetected variant (`spel --undetected`) replaces only the JS package
   with com.blockether/spel-patchright-driver, a Maven repack of the npm
   `patchright-core` package. The Node runtime still comes from driver-bundle.
   Its cache is <cache-root>/patchright-<patchright-version>/<platform>/.

   Configuration (checked in order):
     1. playwright.cli.dir system property — points to pre-installed driver dir
     2. SPEL_DRIVER_DIR env var — overrides cache location
     3. Default: ~/.cache/spel/<version>/<platform>/"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.deps :as deps])
  (:import
   [com.microsoft.playwright CLI]
   [com.microsoft.playwright.impl.driver Driver]
   [java.io InputStream]
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute]
   [java.util.zip ZipInputStream]))

;; =============================================================================
;; Constants
;; =============================================================================

(defn- playwright-version
  "Returns the version of the Playwright Java artifact currently on the classpath.

   The driver and driver-bundle artifacts must be resolved with this exact version;
   reading Maven metadata from the dependency avoids a second hardcoded version
   that can drift when deps.edn is updated."
  []
  (let [resource "META-INF/maven/com.microsoft.playwright/playwright/pom.properties"]
    (or (some-> (io/resource resource)
          slurp
          (str/split-lines)
          (->> (some (fn [line]
                       (when (str/starts-with? line "version=")
                         (subs line (count "version="))))))
          str/trim
          not-empty)
      (throw (ex-info "Cannot determine Playwright version from classpath metadata"
               {:resource resource})))))

(def patchright-lib
  "Maven coordinate of the Patchright driver package for `--undetected`."
  'com.blockether/spel-patchright-driver)

(defn patchright-version
  "Returns the pinned Patchright driver version from the spel resources."
  []
  (let [resource "com/blockether/spel/patchright-driver.edn"]
    (or (some-> (io/resource resource) slurp edn/read-string :version not-empty)
      (throw (ex-info "Cannot determine the Patchright driver version" {:resource resource})))))

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
        (into-array String [".cache" "spel" (playwright-version)])))))

(defn- driver-dir
  "Returns the platform-specific driver directory of a driver variant."
  (^Path [] (driver-dir :playwright))
  (^Path [variant]
   (let [root (cache-dir)
         base (case variant
                :playwright root
                :patchright (.resolve root (str "patchright-" (patchright-version))))]
     (.resolve ^Path base ^String (platform-name)))))

(defn- driver-ready-at?
  ^Boolean [^Path dir]
  (let [node (.resolve dir ^String (node-binary-name))
        cli  (.resolve dir (Paths/get "package" (into-array String ["cli.js"])))
        pkg  (.resolve dir (Paths/get "package" (into-array String ["package.json"])))]
    (and (Files/exists node no-link-opts)
      (Files/exists cli no-link-opts)
      (Files/exists pkg no-link-opts))))

(defn driver-ready?
  "Checks if the driver of a variant (:playwright by default) is ready to use."
  ([] (driver-ready? :playwright))
  ([variant] (driver-ready-at? (driver-dir variant))))

(defn- artifact-lib
  "Returns the Maven coordinate symbol for a Playwright driver artifact."
  [^String artifact]
  (symbol "com.microsoft.playwright" artifact))

(defn- resolve-artifact-path
  "Resolves a Maven artifact through Clojure's dependency resolver.

   This is intentionally not a hand-rolled HTTP GET: tools.deps is the same
   resolver the `clojure` CLI uses, so local Maven cache, :mvn/repos,
   corporate mirrors and ~/.m2/settings.xml are honoured. Returns the resolved
   jar path in the local Maven repository. The one-argument form resolves a
   Playwright artifact at the classpath Playwright version."
  (^Path [^String artifact]
   (resolve-artifact-path (artifact-lib artifact) (playwright-version)))
  (^Path [lib ^String version]
   (let [basis (deps/create-basis {:project nil
                                   :extra   {:deps {lib {:mvn/version version}}}})
         path  (-> basis :libs (get lib) :paths first)]
     (when-not path
       (throw (ex-info (str "Could not resolve artifact " lib " " version
                         " via Clojure's dependency resolver. Check Maven repositories / mirrors.")
                {:lib lib :version version})))
     (Paths/get ^String path (into-array String [])))))

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
  "Copies a Maven artifact into tmp-dir as <artifact-name>.jar.

   Resolves through tools.deps instead of hardcoding Maven Central, so corporate
   mirrors and ~/.m2/settings.xml are honoured. Returns the copied file path."
  ^Path [lib ^String version ^Path tmp-dir]
  (let [dest   (.resolve tmp-dir (str (name lib) ".jar"))
        source (resolve-artifact-path lib version)]
    (binding [*out* *err*]
      (println (str "  - " lib " " version " (resolved via tools.deps: " source ")")))
    (Files/createDirectories (.getParent dest) no-file-attrs)
    (Files/copy source dest
      ^"[Ljava.nio.file.CopyOption;"
      (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
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

(defn- package-coordinate
  "Returns the [lib version] of the JS driver package of a variant."
  [variant]
  (case variant
    :playwright [(artifact-lib "driver") (playwright-version)]
    :patchright [patchright-lib (patchright-version)]))

(defn- assemble-driver!
  "Assembles the driver into a temp directory under parent, then atomically
   moves it to the final cache location.

   Uses Maven artifacts:
     1. the package jar    → package/ directory
        (com.microsoft.playwright/driver, or the Patchright repack)
     2. driver-bundle.jar  → current platform node binary"
  [variant]
  (let [dir    (driver-dir variant)
        parent (.getParent dir)]
    (Files/createDirectories parent no-file-attrs)
    (let [^Path tmp-dir (Files/createTempDirectory
                          parent
                          (str ".tmp-" (platform-name) "-")
                          no-file-attrs)]
      (try
        (let [[pkg-lib pkg-version] (package-coordinate variant)]
          (binding [*out* *err*]
            (println (str "spel: assembling " (name variant) " driver v" pkg-version
                       " for " (platform-name) "...")))
          (let [package-jar (materialize-artifact! pkg-lib pkg-version tmp-dir)]
            (extract-driver-package! package-jar tmp-dir)
            (Files/deleteIfExists package-jar))

          (let [bundle-jar (materialize-artifact! (artifact-lib "driver-bundle")
                             (playwright-version) tmp-dir)]
            (extract-node! bundle-jar tmp-dir)
            (Files/deleteIfExists bundle-jar)))

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

(def node-env
  "Environment overrides for Spel's Node processes. Keep inherited Node options out of the bundled runtime."
  {"NODE_OPTIONS" ""})

(defn node-process-builder
  "Creates a process builder with inherited Node options cleared. Keeps all other environment variables."
  ^ProcessBuilder [args]
  (let [pb (ProcessBuilder. ^java.util.List args)]
    (.putAll (.environment pb) node-env)
    pb))

(defn run-cli!
  "Runs the Playwright CLI without inherited Node options. The CLI exits the current process."
  [args]
  (Driver/ensureDriverInstalled node-env false)
  (CLI/main (into-array String args)))

(defn ensure-driver!
  "Ensures the Playwright Java Node.js driver is available locally.

   Materializes it from Maven artifacts on first use and sets the
   playwright.cli.dir system property so Playwright Java uses the external
   driver instead of trying to extract from bundled resources.

   With `{:undetected? true}`, uses the Patchright package. Playwright Java
   starts one driver per process, so call this before the first
   `Playwright/create` of the process.

   No-op if playwright.cli.dir is already set (for custom installations)."
  ([] (ensure-driver! {}))
  ([{:keys [undetected?]}]
   ;; Respect pre-configured system property
   (when-not (System/getProperty "playwright.cli.dir")
     (let [variant (if undetected? :patchright :playwright)]
       (when-not (driver-ready? variant)
         (assemble-driver! variant))
       (System/setProperty "playwright.cli.dir"
         (.toString (driver-dir variant)))))))
