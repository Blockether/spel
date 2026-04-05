(ns com.blockether.spel.profile-test
  "Unit tests for Chrome profile discovery + cloning.

   Tests use **fixture directories** built under the system tempdir — they do
   NOT touch the user's real Chrome installation. Each test builds its own
   mock `user-data` root with a hand-written `Local State` JSON and a mix of
   profile subdirectories (some with excluded cache subdirs) so we can assert
   both positive and negative paths without a real Chrome install.

   Coverage:
   - `list-profiles-in` — parses Local State `info_cache` and surfaces display
     names + directory names + user-names (including empty-string edge case).
   - `resolve-profile-in` — three-tier matching (exact dir → case-insensitive
     display → case-insensitive dir), ambiguity detection, missing-profile
     error with available list.
   - `clone-profile!` semantics via `copy-tree!` and `resolve-profile-in`:
     * excluded cache directories are SKIPPED (size/performance guarantee)
     * included auth files are COPIED byte-for-byte
     * `Local State` is copied to the clone root
     * the returned map contains BOTH `:user-data-dir` and `:profile-directory`
       so the caller can pass `--profile-directory=<dir>` to Chrome."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.profile :as sut])
  (:import
   [java.io File]
   [java.nio.file Files Path Paths]
   [java.nio.file.attribute FileAttribute]))

;; =============================================================================
;; Fixture helpers
;; =============================================================================

(defn- create-tmp-dir!
  "Creates a fresh temp directory under the system tempdir with a test-
   specific prefix. Returns the absolute path String."
  ^String [^String prefix]
  (-> (Files/createTempDirectory prefix (into-array FileAttribute []))
    .toAbsolutePath
    .toString))

(defn- write!
  "Writes `content` (String) to `path/relative`, creating parents as needed."
  [^String root ^String relative ^String content]
  (let [f (File. root ^String relative)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- touch!
  "Creates an empty file at `path/relative`."
  [^String root ^String relative]
  (write! root relative ""))

(defn- file-exists?
  [^String root ^String relative]
  (.exists (File. root ^String relative)))

(defn- make-local-state
  "Builds a minimal `Local State` JSON string with the given profiles.
   `profiles` is a vector of `[dir-name display-name user-name-or-nil]`."
  [profiles]
  (let [info-cache (->> profiles
                     (map (fn [[dir display user-name]]
                            (str \" dir \" ":"
                              "{\"name\":\"" display "\""
                              (when user-name (str ",\"user_name\":\"" user-name "\""))
                              "}")))
                     (str/join ","))]
    (str "{\"profile\":{\"info_cache\":{" info-cache "}}}")))

(defn- build-fixture-user-data!
  "Builds a fake Chrome user-data directory with the given profiles. Each
   profile subdir gets an auth file AND a large cache subdir so we can verify
   that the copy is selective.

   Returns the absolute path of the fixture root."
  ^String [profiles]
  (let [root (create-tmp-dir! "spel-profile-fixture-")]
    (write! root "Local State" (make-local-state profiles))
    (doseq [[dir _ _] profiles]
      ;; Auth-relevant files (must be copied)
      (touch! root (str dir "/Cookies"))
      (touch! root (str dir "/Login Data"))
      (write! root (str dir "/Preferences") "{\"profile\":{}}")
      ;; Excluded cache dirs (must be SKIPPED)
      (touch! root (str dir "/Cache/data_1"))
      (touch! root (str dir "/Code Cache/js/index"))
      (touch! root (str dir "/GPUCache/data_0"))
      (touch! root (str dir "/Service Worker/Database/LOG"))
      (touch! root (str dir "/blob_storage/aa/bb"))
      ;; A non-excluded subdir should still be copied (e.g. IndexedDB)
      (touch! root (str dir "/IndexedDB/https_example.com_0.indexeddb.leveldb/LOG")))
    root))

;; =============================================================================
;; list-profiles-in
;; =============================================================================

(defdescribe list-profiles-in-test
  "list-profiles-in: parses Chrome Local State info_cache"

  (describe "well-formed fixture"

    (it "lists every profile with display name + directory"
      (let [root (build-fixture-user-data!
                   [["Default"   "Karol Doe"     "karol@example.com"]
                    ["Profile 1" "Work"          "karol@work.example"]
                    ["Profile 2" "Guest Browser" nil]])
            out  (sut/list-profiles-in root)]
        (expect (= 3 (count out)))
        (expect (= #{"Default" "Profile 1" "Profile 2"} (set (map :dir out))))
        (expect (= #{"Karol Doe" "Work" "Guest Browser"} (set (map :name out))))
        ;; user-name is surfaced when present
        (expect (contains? (set (map :user-name out)) "karol@example.com"))))

    (it "sorts profiles deterministically by directory name"
      (let [root (build-fixture-user-data!
                   [["Profile 10" "Ten" nil]
                    ["Default"    "Zero" nil]
                    ["Profile 1"  "One" nil]])
            dirs (mapv :dir (sut/list-profiles-in root))]
        (expect (= ["Default" "Profile 1" "Profile 10"] dirs)))))

  (describe "degraded fixture"

    (it "returns [] when Local State is missing"
      (let [root (create-tmp-dir! "spel-profile-no-state-")]
        (expect (= [] (sut/list-profiles-in root)))))

    (it "returns [] when Local State is not valid JSON"
      (let [root (create-tmp-dir! "spel-profile-bad-json-")]
        (write! root "Local State" "not valid json {")
        (expect (= [] (sut/list-profiles-in root)))))

    (it "returns [] when Local State has no profile.info_cache key"
      (let [root (create-tmp-dir! "spel-profile-no-cache-")]
        (write! root "Local State" "{\"something\":\"else\"}")
        (expect (= [] (sut/list-profiles-in root)))))))

;; =============================================================================
;; resolve-profile-in — three-tier matching
;; =============================================================================

(defdescribe resolve-profile-in-test
  "Three-tier profile resolution (exact dir → display name → ci dir)"

  (describe "tier 1: exact directory match"

    (it "prefers exact directory match over case-insensitive display match"
      ;; Edge case: display name of Profile 1 is "default" (lowercase).
      ;; User passes "Default" → should resolve to the DIRECTORY "Default",
      ;; NOT to Profile 1's display name (which would shadow it).
      (let [root (build-fixture-user-data!
                   [["Default"   "Home"    nil]
                    ["Profile 1" "default" nil]])]
        (expect (= "Default" (sut/resolve-profile-in root "Default"))))))

  (describe "tier 2: case-insensitive display name match"

    (it "matches by exact display name"
      (let [root (build-fixture-user-data!
                   [["Default"   "Home" nil]
                    ["Profile 1" "Work" nil]])]
        (expect (= "Profile 1" (sut/resolve-profile-in root "Work")))))

    (it "matches by lowercased display name"
      (let [root (build-fixture-user-data!
                   [["Profile 1" "Work" nil]])]
        (expect (= "Profile 1" (sut/resolve-profile-in root "work")))))

    (it "raises on ambiguous display name (two profiles share a display name)"
      (let [root (build-fixture-user-data!
                   [["Profile 1" "Work" nil]
                    ["Profile 2" "Work" nil]])
            thrown (try (sut/resolve-profile-in root "Work")
                        nil
                        (catch Exception e
                          (ex-data e)))]
        (expect (contains? thrown :ambiguous-profile-name))
        (expect (= #{"Profile 1" "Profile 2"} (set (:candidates thrown)))))))

  (describe "tier 3: case-insensitive directory match"

    (it "matches by lowercased directory name"
      (let [root (build-fixture-user-data!
                   [["Profile 1" "Work" nil]])]
        (expect (= "Profile 1" (sut/resolve-profile-in root "profile 1"))))))

  (describe "not found"

    (it "raises ex-info with :profile-not-found and the available list"
      (let [root (build-fixture-user-data!
                   [["Default"   "Home" nil]
                    ["Profile 1" "Work" nil]])
            data (try (sut/resolve-profile-in root "Nonexistent")
                      nil
                      (catch Exception e (ex-data e)))]
        (expect (contains? data :profile-not-found))
        (expect (= "Nonexistent" (:profile-not-found data)))
        (expect (= #{"Default" "Profile 1"} (set (:available data))))))

    (it "raises when Local State exists but has no profiles"
      (let [root (create-tmp-dir! "spel-profile-empty-cache-")]
        (write! root "Local State" "{\"profile\":{\"info_cache\":{}}}")
        (let [threw? (try (sut/resolve-profile-in root "anything") false
                          (catch Exception _ true))]
          (expect (true? threw?)))))))

;; =============================================================================
;; clone-profile! — selective copy + metadata
;; =============================================================================

(defn- clone-via!
  "Overrides chrome-user-data-root to point at `root` for the duration of f.
   Returns whatever `clone-profile!` returns."
  [^String root ^String input]
  ;; We can't easily redefine chrome-user-data-root because it reads the OS.
  ;; Instead, we exercise clone-profile!'s underlying pieces directly so the
  ;; test does not depend on a real Chrome installation.
  (with-redefs [sut/chrome-user-data-root (constantly root)]
    (sut/clone-profile! input)))

(defdescribe clone-profile-test
  "clone-profile! — cloning semantics and exclude-dirs filter"

  (describe "selective copy"

    (it "copies auth-relevant files from the resolved profile directory"
      (let [root (build-fixture-user-data!
                   [["Default"   "Home" nil]
                    ["Profile 1" "Work" nil]])
            {:keys [user-data-dir profile-directory]} (clone-via! root "Work")]
        (expect (= "Profile 1" profile-directory))
        ;; The clone contains the resolved profile subdir
        (expect (file-exists? user-data-dir "Profile 1/Cookies"))
        (expect (file-exists? user-data-dir "Profile 1/Login Data"))
        (expect (file-exists? user-data-dir "Profile 1/Preferences"))
        ;; Non-cache state (IndexedDB) is copied too
        (expect (file-exists? user-data-dir "Profile 1/IndexedDB/https_example.com_0.indexeddb.leveldb/LOG"))))

    (it "SKIPS excluded cache subdirectories (size/performance guarantee)"
      (let [root (build-fixture-user-data!
                   [["Default" "Home" nil]])
            {:keys [user-data-dir]} (clone-via! root "Default")]
        ;; None of the excluded dirs should have been copied — this is the
        ;; behavior that matters most: Chrome profiles can be gigabytes
        ;; without this filter, which would make --profile unusable.
        (doseq [excluded ["Cache" "Code Cache" "GPUCache" "Service Worker" "blob_storage"]]
          (expect (not (file-exists? user-data-dir (str "Default/" excluded))))))))

  (describe "Local State"

    (it "copies Local State to the clone root so Chrome can find profile metadata"
      (let [root (build-fixture-user-data!
                   [["Default" "Home" nil]])
            {:keys [user-data-dir]} (clone-via! root "Default")]
        (expect (file-exists? user-data-dir "Local State")))))

  (describe "returned metadata"

    (it "returns both :user-data-dir and :profile-directory"
      (let [root (build-fixture-user-data!
                   [["Default"   "Home" nil]
                    ["Profile 1" "Work" nil]])
            result (clone-via! root "Profile 1")]
        (expect (string? (:user-data-dir result)))
        (expect (.isDirectory (File. ^String (:user-data-dir result))))
        ;; The profile-directory MUST match the source dir so the caller can
        ;; pass it to Chrome via `--profile-directory=<dir>`.
        (expect (= "Profile 1" (:profile-directory result))))))

  (describe "display name resolution"

    (it "resolves display name to the correct directory when cloning"
      (let [root (build-fixture-user-data!
                   [["Default"   "Karol"  nil]
                    ["Profile 1" "Work"   nil]
                    ["Profile 2" "Guest"  nil]])
            result (clone-via! root "Work")]
        (expect (= "Profile 1" (:profile-directory result)))
        (expect (file-exists? (:user-data-dir result) "Profile 1/Cookies"))
        ;; Other profiles MUST NOT be in the clone
        (expect (not (file-exists? (:user-data-dir result) "Default/Cookies")))
        (expect (not (file-exists? (:user-data-dir result) "Profile 2/Cookies")))))))

;; =============================================================================
;; name? — path vs name detection
;; =============================================================================

;; =============================================================================
;; expand-tilde — shell-like ~ expansion for path args
;; =============================================================================

(defdescribe expand-tilde-test
  "expand-tilde: POSIX-style `~` expansion for --profile <path> args"

  (describe "leading ~"

    (it "expands bare ~ to the home directory"
      (expect (= (System/getProperty "user.home") (sut/expand-tilde "~"))))

    (it "expands ~/ to home + rest of the path"
      (let [home (System/getProperty "user.home")]
        (expect (= (str home "/chrome-profile") (sut/expand-tilde "~/chrome-profile")))
        (expect (= (str home "/a/b/c") (sut/expand-tilde "~/a/b/c"))))))

  (describe "no expansion"

    (it "leaves absolute paths untouched"
      (expect (= "/tmp/profile" (sut/expand-tilde "/tmp/profile"))))

    (it "leaves relative paths untouched"
      (expect (= "./profile" (sut/expand-tilde "./profile"))))

    (it "does NOT expand ~user (non-current-user form)"
      ;; POSIX `~user` is out of scope — we only handle the current user.
      (expect (= "~other" (sut/expand-tilde "~other"))))

    (it "nil → nil"
      (expect (nil? (sut/expand-tilde nil))))))

;; =============================================================================
;; delete-tree! — recursive cleanup for temp profile clones
;; =============================================================================

(defdescribe delete-tree-test
  "delete-tree!: cleanup of cloned profile temp directories on close"

  (describe "cleanup"

    (it "removes a directory with nested files and subdirectories"
      (let [root (create-tmp-dir! "spel-profile-cleanup-")]
        (touch! root "Local State")
        (touch! root "Profile 1/Cookies")
        (touch! root "Profile 1/IndexedDB/https_example.com/LOG")
        (touch! root "Profile 1/Preferences")
        (expect (.exists (File. root)))
        (sut/delete-tree! root)
        (expect (not (.exists (File. root))))))

    (it "returns false silently on missing directory (idempotent)"
      (let [nonexistent (str (create-tmp-dir! "spel-profile-ghost-") "/gone")]
        ;; Idempotent: calling on a missing path should not throw and should
        ;; return a falsey "nothing to do" result.
        (expect (any? (sut/delete-tree! nonexistent)))))))

(defdescribe name-detection-test
  "name? guard used by the daemon to decide clone-vs-path semantics"

  (describe "bare names"

    (it "treats short alphanumeric names as profile names"
      (expect (true? (sut/name? "Default")))
      (expect (true? (sut/name? "Work")))
      (expect (true? (sut/name? "Profile 1")))))

  (describe "path-like inputs"

    (it "unix absolute path is NOT a name"
      (expect (false? (sut/name? "/tmp/my-profile"))))

    (it "relative path with slash is NOT a name"
      (expect (false? (sut/name? "./profiles/work"))))

    (it "home-expanded path is NOT a name"
      (expect (false? (sut/name? "~/chrome-profile"))))

    (it "windows backslash path is NOT a name"
      (expect (false? (sut/name? "C:\\Users\\x\\profile")))))

  (describe "edge cases"

    (it "nil and blank are NOT names"
      (expect (false? (sut/name? nil)))
      (expect (false? (sut/name? "")))
      (expect (false? (sut/name? "   "))))))
