(ns com.blockether.spel.profile
  "Chrome/Chromium user-profile lookup and cloning for `--profile <name>`.

   Matches the semantics of vercel-labs/agent-browser:

   1. Three-tier resolution of the user-supplied string to an on-disk profile
      directory (exact match → case-insensitive display name → case-insensitive
      directory name). Ambiguous display-name matches raise an error.

   2. Selective copy to a fresh temp user-data directory. Large cache/non-auth
      subdirectories are skipped (e.g. `Cache`, `Code Cache`, `GPUCache`,
      `Service Worker`) because they are not needed for login-state reuse and
      would bloat the copy by hundreds of MB. See `exclude-dirs`.

   3. The clone function returns BOTH the temp user-data dir path AND the
      resolved profile directory name. The caller is responsible for passing
      `--profile-directory=<dir>` to Chrome so it picks the right profile from
      the cloned user-data dir (without this, Chrome always defaults to
      `Default` and ignores other profiles in the clone).

   Platform-specific Chrome user-data roots:
     macOS   ~/Library/Application Support/Google/Chrome
     Linux   ~/.config/google-chrome
     Windows %LOCALAPPDATA%/Google/Chrome/User Data"
  (:require
   [charred.api :as json]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files Path Paths StandardCopyOption]
   [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Platform detection
;; =============================================================================

(defn- os-kind
  "Returns :mac, :linux, or :windows based on the JVM `os.name` system property."
  []
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond
      (str/includes? n "mac") :mac
      (str/includes? n "win") :windows
      :else :linux)))

(defn chrome-user-data-root
  "Returns the absolute path (as a String) to the current user's Chrome
   user-data directory for the host OS, or nil if the standard location does
   not exist on disk."
  ^String []
  (let [home (System/getProperty "user.home")
        candidate (case (os-kind)
                    :mac     (str home "/Library/Application Support/Google/Chrome")
                    :linux   (str home "/.config/google-chrome")
                    :windows (str (or (System/getenv "LOCALAPPDATA")
                                    (str home "/AppData/Local"))
                               "/Google/Chrome/User Data"))]
    (when (.isDirectory (File. ^String candidate))
      candidate)))

;; =============================================================================
;; Profile discovery
;; =============================================================================

(defn- read-local-state
  "Reads and parses the `Local State` JSON at the root of the Chrome user-data
   directory. Returns a map (with STRING keys) or nil if the file is missing
   or unreadable."
  [^String root]
  (let [f (File. ^String root "Local State")]
    (when (.isFile f)
      (try
        (json/read-json (slurp f))
        (catch Exception _ nil)))))

(defn list-profiles-in
  "Lists Chrome profiles found in a specific `user-data-dir`. Extracted from
   `list-profiles` so tests can point at fixture directories without touching
   the real Chrome installation.

   Each entry: `{:name <display> :dir <directory> :user-name <email?>}`.
   `:dir` is the on-disk subdirectory name (e.g. `Default`, `Profile 1`) which
   is what Chrome's `--profile-directory` flag expects."
  [^String user-data-dir]
  (let [state (read-local-state user-data-dir)
        cache (get-in state ["profile" "info_cache"])]
    (->> cache
      (map (fn [[dir-name info]]
             {:name      (get info "name" dir-name)
              :dir       dir-name
              :user-name (get info "user_name")}))
      (sort-by :dir)
      vec)))

(defn list-profiles
  "Lists available Chrome profiles on the current system. Returns `[]` if
   Chrome is not installed or no profiles exist."
  []
  (if-let [root (chrome-user-data-root)]
    (list-profiles-in root)
    []))

(defn resolve-profile-in
  "Three-tier profile resolution against the profile list from a specific
   user-data directory. Mirrors agent-browser's semantics:

   - Tier 1: exact directory name match (case-sensitive)
   - Tier 2: case-insensitive display name match. If multiple profiles share
             the same display name (rare but possible), raises an error with
             the list of candidates — the caller must disambiguate using the
             directory name.
   - Tier 3: case-insensitive directory name match.

   Returns the resolved directory name (String) on success. Throws ex-info
   with `:profile-not-found` or `:ambiguous-profile-name` on failure."
  [^String user-data-dir ^String input]
  (let [profiles (list-profiles-in user-data-dir)]
    (when (empty? profiles)
      (throw (ex-info (str "No Chrome profiles found in " user-data-dir)
               {:user-data-dir user-data-dir})))
    (or
      ;; Tier 1: exact directory match
      (some #(when (= input (:dir %)) (:dir %)) profiles)
      ;; Tier 2: case-insensitive display name match (with ambiguity check)
      (let [input-lower (str/lower-case input)
            matches (filter #(= input-lower (str/lower-case (:name %))) profiles)]
        (case (count matches)
          0 nil
          1 (:dir (first matches))
          (throw (ex-info
                   (str "Ambiguous profile name \"" input "\". Multiple profiles match: "
                     (str/join ", " (map :dir matches))
                     ". Use the directory name instead.")
                   {:ambiguous-profile-name input
                    :candidates (mapv :dir matches)}))))
      ;; Tier 3: case-insensitive directory match
      (let [input-lower (str/lower-case input)]
        (some #(when (= input-lower (str/lower-case (:dir %))) (:dir %)) profiles))
      ;; Not found
      (throw (ex-info (str "Chrome profile not found: " input)
               {:profile-not-found input
                :available (mapv :dir profiles)})))))

(defn resolve-profile
  "Resolves a user-supplied profile name against the active Chrome installation.
   See `resolve-profile-in` for matching rules. Throws if Chrome is not found."
  [^String input]
  (let [root (chrome-user-data-root)]
    (when-not root
      (throw (ex-info "Chrome user-data directory not found on this system"
               {:os (os-kind)})))
    (resolve-profile-in root input)))

;; =============================================================================
;; Profile cloning
;; =============================================================================

(def ^:private exclude-dirs
  "Subdirectories to skip when cloning a Chrome profile. These hold large
   non-auth state (browser cache, GPU cache, service-worker registrations,
   WebStorage blobs) that is not needed for reusing login sessions and would
   make the copy slow (often 100s of MB). Matches agent-browser's list."
  #{"Cache"
    "Code Cache"
    "GPUCache"
    "Service Worker"
    "blob_storage"
    "File System"
    "GCM Store"
    "optimization_guide"
    "ShaderCache"
    "component_crx_cache"})

(defn- copy-tree!
  "Recursively copies `src` into `dst`, skipping directory entries listed in
   `exclude-dirs`. Individual per-file failures (locked SQLite files, missing
   permissions, sockets, etc.) are swallowed silently — the resulting clone
   is used read-only, so partial copies are acceptable."
  [^Path src ^Path dst]
  (let [src-file (.toFile src)]
    (if (.isDirectory src-file)
      (do
        (Files/createDirectories dst (into-array FileAttribute []))
        (doseq [^File child (.listFiles src-file)]
          (let [child-name (.getName child)]
            (when-not (and (.isDirectory child) (contains? exclude-dirs child-name))
              (let [child-dst (.resolve dst ^String child-name)]
                (try
                  (copy-tree! (.toPath child) child-dst)
                  (catch Exception _ nil)))))))
      (try
        (Files/copy ^Path src ^Path dst
          ^"[Ljava.nio.file.CopyOption;"
          (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (catch Exception _ nil)))))

(defn clone-profile!
  "Clones the requested Chrome profile into a fresh temp user-data directory
   and returns both the temp path AND the resolved profile directory name.

   Structure of the output temp dir:

     <tempdir>/
       Local State         (copied from the source user-data root)
       <profile-dir>/      (e.g. 'Default' or 'Profile 1')
         Cookies, Login Data, Preferences, ...  (auth-relevant files)

   The caller MUST pass `--profile-directory=<profile-directory>` as a Chrome
   command-line argument when launching — otherwise Chrome defaults to
   'Default' and ignores any other profile that was cloned.

   Returns: `{:user-data-dir \"<tmp-path>\" :profile-directory \"<dir>\"}`.
   Throws ex-info on missing Chrome, unknown profile, or ambiguous name."
  [^String input]
  (let [root (chrome-user-data-root)
        _    (when-not root
               (throw (ex-info "Chrome user-data directory not found on this system"
                        {:os (os-kind)})))
        dir  (resolve-profile-in root input)
        src-profile (Paths/get root (into-array String [dir]))
        _    (when-not (.isDirectory (.toFile src-profile))
               (throw (ex-info (str "Resolved profile directory does not exist: " src-profile) {})))
        tmp  (Files/createTempDirectory
               (str "spel-profile-" (System/currentTimeMillis) "-")
               (into-array FileAttribute []))
        local-state-src (Paths/get root (into-array String ["Local State"]))]
    (try
      ;; Copy the profile subdirectory (with exclude-dirs filtering)
      (copy-tree! src-profile (.resolve tmp ^String dir))
      ;; Copy Local State at the root so Chrome can find the profile metadata
      (when (.isFile (.toFile local-state-src))
        (try
          (Files/copy ^Path local-state-src
            ^Path (.resolve tmp "Local State")
            ^"[Ljava.nio.file.CopyOption;"
            (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (catch Exception _ nil)))
      {:user-data-dir (-> tmp .toAbsolutePath .toString)
       :profile-directory dir}
      (catch Throwable t
        ;; Best-effort cleanup on failure
        (try (.delete (.toFile tmp)) (catch Exception _ nil))
        (throw t)))))

(defn name?
  "Returns true if `s` looks like a profile *name* rather than a filesystem
   *path*. A name contains no path separators, no `~`, and is non-blank. Path-
   like inputs are handed to the existing persistent-profile-path flow as-is."
  [^String s]
  (and (string? s)
    (not (str/blank? s))
    (not (or (str/includes? s "/")
           (str/includes? s "\\")
           (str/starts-with? s "~")))))

(defn expand-tilde
  "Expands a leading `~` in a path to the current user's home directory, the
   way POSIX shells do. Matches agent-browser's `expand_tilde` semantics so
   `--profile ~/my-profile` works without requiring the shell to pre-expand."
  ^String [^String s]
  (cond
    (or (nil? s) (not (string? s)))
    s

    (= "~" s)
    (System/getProperty "user.home")

    (str/starts-with? s "~/")
    (str (System/getProperty "user.home") (subs s 1))

    :else
    s))

(defn delete-tree!
  "Recursively deletes a directory tree. Used for temp profile cleanup on
   browser close. Best-effort: retries once after a short sleep to handle
   transient file locks (Chrome sometimes releases .lock files lazily).
   Returns true on success, false on final failure."
  [^String path]
  (letfn [(try-delete []
            (let [root (File. path)]
              (when (.exists root)
                (doseq [^File f (reverse (file-seq root))]
                  (try (.delete f) (catch Exception _ nil))))
              (not (.exists root))))]
    (or (try-delete)
      (do (Thread/sleep 100) (try-delete))
      false)))
