(ns com.blockether.spel.driver-test
  (:require
   [clojure.tools.deps :as deps]
   [com.blockether.spel.allure :refer [defdescribe expect it]]
   [com.blockether.spel.driver :as driver])
  (:import
   [java.nio.charset StandardCharsets]
   [java.nio.file Files Path]
   [java.nio.file.attribute FileAttribute]
   [java.util.zip ZipEntry ZipOutputStream]))

(defn- empty-file-attrs []
  (into-array FileAttribute []))

(defn- tmp-path
  ^Path [& parts]
  (Path/of (System/getProperty "java.io.tmpdir")
    (into-array String (map str parts))))

(defn- write-zip!
  [^Path path entries]
  (with-open [zos (ZipOutputStream. (Files/newOutputStream path (into-array java.nio.file.OpenOption [])))]
    (doseq [[entry-name content] entries]
      (.putNextEntry zos (ZipEntry. entry-name))
      (when content
        (.write zos (.getBytes ^String content StandardCharsets/UTF_8)))
      (.closeEntry zos))))

(defdescribe driver-artifact-resolution-test
  "Tests for Playwright driver artifact resolution."

  (it "reads the Playwright version from Maven metadata"
    (let [version ((var-get #'driver/playwright-version))]
      (expect (string? version))
      (expect (boolean (re-matches #"\d+\.\d+\.\d+" version)))))

  (it "resolves artifacts through tools.deps coordinates"
    (let [resolved-jar (.resolve (tmp-path) "driver.jar")
          !basis-opts  (atom nil)]
      (with-redefs [deps/create-basis
                    (fn [opts]
                      (reset! !basis-opts opts)
                      {:libs {'com.microsoft.playwright/driver
                              {:paths [(.toString resolved-jar)]}}})]
        (let [resolved ((var-get #'driver/resolve-artifact-path) "driver")]
          (expect (= (.toString resolved-jar) (.toString resolved)))
          (expect (= {:project nil
                      :extra   {:deps {'com.microsoft.playwright/driver
                                       {:mvn/version ((var-get #'driver/playwright-version))}}}}
                    @!basis-opts))))))

  (it "materializes artifacts from the resolved Maven jar"
    (let [tmp-dir  (Files/createTempDirectory "spel-driver-test" (empty-file-attrs))
          source   (.resolve tmp-dir "source.jar")
          out-dir  (.resolve tmp-dir "out")]
      (Files/write source (.getBytes "jar-bytes" StandardCharsets/UTF_8)
        (into-array java.nio.file.OpenOption []))
      (with-redefs-fn {#'driver/resolve-artifact-path (fn [_] source)}
        (fn []
          (let [dest ((var-get #'driver/materialize-artifact!) "driver" out-dir)]
            (expect (= (.toString (.resolve out-dir "driver.jar")) (.toString dest)))
            (expect (= "jar-bytes" (Files/readString dest))))))))

  (it "extracts Playwright's current Maven driver jar layout"
    (let [tmp-dir    (Files/createTempDirectory "spel-driver-layout-test" (empty-file-attrs))
          driver-jar (.resolve tmp-dir "driver.jar")
          bundle-jar (.resolve tmp-dir "driver-bundle.jar")
          platform   ((var-get #'driver/platform-name))
          node-name  ((var-get #'driver/node-binary-name))]
      (write-zip! driver-jar
        {"driver/package/cli.js"      "console.log('ok')"
         "driver/package/package.json" "{}"})
      (write-zip! bundle-jar
        {(str "driver/" platform "/" node-name) "node-binary"})
      ((var-get #'driver/extract-driver-package!) driver-jar tmp-dir)
      ((var-get #'driver/extract-node!) bundle-jar tmp-dir)
      (expect ((var-get #'driver/driver-ready-at?) tmp-dir))
      (expect (Files/exists (.resolve tmp-dir "package/cli.js")
                (into-array java.nio.file.LinkOption [])))
      (expect (Files/exists (.resolve tmp-dir node-name)
                (into-array java.nio.file.LinkOption []))))))
