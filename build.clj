(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def lib 'com.blockether/spel)
(def version
  (let [v (System/getenv "VERSION")]
    (if (and v (.startsWith v "v"))
      (subs v 1)
      (or v (str/trim (slurp "resources/SPEL_VERSION"))))))

(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def uber-file "target/spel-standalone.jar")
(def native-binary "target/spel")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def native-basis (delay (b/create-basis {:project "deps.edn"
                                          :aliases [:native]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data [[:description "Clojure wrapper for Microsoft Playwright - browser automation, testing, and codegen"]
                           [:url "https://github.com/Blockether/spel"]
                           [:licenses
                            [:license
                             [:name "Apache License, Version 2.0"]
                             [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]
                           [:scm
                            [:url "https://github.com/Blockether/spel"]
                            [:connection "scm:git:https://github.com/Blockether/spel.git"]
                            [:developerConnection "scm:git:ssh://git@github.com/Blockether/spel.git"]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built:" jar-file "version:" version))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

;; =============================================================================
;; Patchright driver artifact
;; =============================================================================

(def patchright-lib 'com.blockether/spel-patchright-driver)
(def patchright-spec-file "resources/com/blockether/spel/patchright-driver.edn")
(def patchright-dir "target/patchright-driver")
(def patchright-class-dir (str patchright-dir "/classes"))

(defn- patchright-spec []
  (edn/read-string (slurp patchright-spec-file)))

(defn- patchright-jar-file [{:keys [version]}]
  (format "%s/spel-patchright-driver-%s.jar" patchright-dir version))

(defn- sri-sha512
  "Returns the npm Subresource Integrity string of a file."
  [^java.io.File f]
  (let [md (java.security.MessageDigest/getInstance "SHA-512")]
    (with-open [in (io/input-stream f)]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update md buf 0 n)
              (recur))))))
    (str "sha512-" (.encodeToString (java.util.Base64/getEncoder) (.digest md)))))

(defn- download-patchright-tarball!
  "Downloads the pinned npm tarball and stops when its integrity differs."
  [{:keys [package version integrity]}]
  (let [url  (format "https://registry.npmjs.org/%s/-/%s-%s.tgz" package package version)
        dest (io/file patchright-dir (format "%s-%s.tgz" package version))]
    (io/make-parents dest)
    (with-open [in (io/input-stream (java.net.URL. url))]
      (io/copy in dest))
    (let [actual (sri-sha512 dest)]
      (when-not (= integrity actual)
        (throw (ex-info "Patchright tarball integrity mismatch"
                 {:url url :expected integrity :actual actual}))))
    dest))

(defn patchright-driver-jar
  "Builds the com.blockether/spel-patchright-driver jar from the pinned npm package."
  [_]
  (let [{:keys [version] :as spec} (patchright-spec)
        unpack-dir                 (str patchright-dir "/npm")]
    (b/delete {:path patchright-dir})
    (let [tgz (download-patchright-tarball! spec)]
      (.mkdirs (io/file unpack-dir))
      (let [{:keys [exit err]} (shell/sh "tar" "-xzf" (.getPath tgz) "-C" unpack-dir)]
        (when-not (zero? exit)
          (throw (ex-info "tar failed for the Patchright tarball" {:exit exit :err err})))))
    (b/copy-dir {:src-dirs   [(str unpack-dir "/package")]
                 :target-dir (str patchright-class-dir "/driver/package")})
    (b/write-pom {:class-dir patchright-class-dir
                  :lib       patchright-lib
                  :version   version
                  :basis     {:libs {} :deps {}}
                  :pom-data  [[:description (str "Patchright driver package (patchright-core " version
                                                  ") for spel --undetected")]
                              [:url "https://github.com/Blockether/spel"]
                              [:licenses
                               [:license
                                [:name "Apache License, Version 2.0"]
                                [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]
                              [:scm
                               [:url "https://github.com/Blockether/spel"]
                               [:connection "scm:git:https://github.com/Blockether/spel.git"]
                               [:developerConnection "scm:git:ssh://git@github.com/Blockether/spel.git"]]]})
    (b/jar {:class-dir patchright-class-dir
            :jar-file  (patchright-jar-file spec)})
    (println "Built:" (patchright-jar-file spec) "version:" version)))

(defn install-patchright-driver
  "Installs the Patchright driver jar into the local Maven repository."
  [_]
  (patchright-driver-jar nil)
  (let [{:keys [version] :as spec} (patchright-spec)]
    (b/install {:basis     {:libs {} :deps {}}
                :lib       patchright-lib
                :version   version
                :jar-file  (patchright-jar-file spec)
                :class-dir patchright-class-dir})))

(defn deploy-patchright-driver
  "Deploys the Patchright driver jar to Clojars."
  [_]
  (patchright-driver-jar nil)
  (dd/deploy {:installer :remote
              :artifact  (patchright-jar-file (patchright-spec))
              :pom-file  (b/pom-path {:lib patchright-lib :class-dir patchright-class-dir})}))

;; =============================================================================
;; Native Image Build
;; =============================================================================

(defn uberjar
  "Builds an uberjar for native-image compilation.

   The uberjar includes all dependencies (Playwright, SCI, charred)
   and the graal-build-time library for automatic class initialization.

   Usage: clojure -T:build uberjar"
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @native-basis
                  :src-dirs  ["src"]
                  :class-dir class-dir
                  :ns-compile ['com.blockether.spel.native]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @native-basis
           :main      'com.blockether.spel.native})
  (println "Built uberjar:" uber-file))

(defn native-image
  "Compiles the uberjar to a GraalVM native-image binary.

   Requires GraalVM with native-image installed.
    The binary is output to target/spel (or target/spel.exe on Windows).

   Usage: clojure -T:build native-image

   Options (via system properties):
     -Dplaywright.native.extra-args='--verbose' - Extra native-image arguments"
  [_]
  ;; Always rebuild uberjar to pick up source changes
  (uberjar nil)
  (let [os-name    (str/lower-case (System/getProperty "os.name" ""))
        windows?   (str/includes? os-name "win")
        ;; GraalVM native-image auto-appends .exe on Windows, so always
        ;; pass the base name to -o to avoid producing spel.exe.exe
        binary     (if windows?
                     (str native-binary ".exe")
                     native-binary)
        extra-args (or (System/getProperty "playwright.native.extra-args") "")
        ;; Most flags come from META-INF/native-image/.../native-image.properties
        ;; Only specify output path and jar here
        ni-cmd     (if windows?
                     "native-image.cmd"
                     "native-image")
        cmd        (cond-> [ni-cmd
                            "-jar" uber-file
                            "-o" native-binary]
                     (seq extra-args)
                     (into (str/split extra-args #"\s+")))
        _          (println "Running:" (str/join " " cmd))
        result     (apply shell/sh cmd)]
    (println (:out result))
    (when (seq (:err result))
      (binding [*out* *err*]
        (println (:err result))))
    (if (zero? (:exit result))
      (println "Native image built:" binary)
      (do
        (println "Native image build FAILED (exit code:" (:exit result) ")")
        (System/exit 1)))))

;; =============================================================================
;; API Documentation Generation
;; =============================================================================

(defn gen-docs
  "Generates API reference tables in the SKILL.md template from source code.

   Introspects all public namespaces, SCI eval registrations, and CLI help text
   to produce markdown tables that replace {{library-api}}, {{sci-api}}, and
   {{cli-commands}} placeholders in the template.

   Also generates the help registry EDN for `spel/help` in eval-sci mode.

   Runs in the project JVM (`clojure -M`) because the -T tool classpath
   does not include the project's src/resources paths.

   Usage: clojure -T:build gen-docs"
  [_]
  (let [{:keys [exit]} (b/process {:command-args ["clojure" "-M" "-m" "com.blockether.spel.gen-docs"]})]
    (when-not (zero? exit)
      (println "gen-docs FAILED (exit code:" exit ")")
      (System/exit 1))))
