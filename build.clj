(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def lib 'com.blockether/spel)
(def version
  (let [v (System/getenv "VERSION")]
    (if (and v (.startsWith v "v"))
      (subs v 1)
      (or v "0.0.1-SNAPSHOT"))))

(def class-dir "target/classes")
(def jar-file (format "target/%s.jar" (name lib)))
(def uber-file "target/playwright-clj-standalone.jar")
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
                             [:url "https://www.apache.org/licenses/LICENSE-2.0"]]]]})
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
;; Native Image Build
;; =============================================================================

(defn uberjar
  "Builds an uberjar for native-image compilation.

   The uberjar includes all dependencies (Playwright, SCI, data.json)
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
        binary     (if (str/includes? os-name "win")
                     (str native-binary ".exe")
                     native-binary)
        extra-args (or (System/getProperty "playwright.native.extra-args") "")
        ;; Most flags come from META-INF/native-image/.../native-image.properties
        ;; Only specify output path and jar here
        cmd        (cond-> ["native-image"
                             "-jar" uber-file
                             "-o" binary]
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
