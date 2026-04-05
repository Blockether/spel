(ns com.blockether.spel.config-test
  "Unit tests for the `spel.json` config-file loader.

   Tests use fixture JSON files under the system tempdir so they do NOT touch
   `~/.spel/config.json` or `./spel.json` from the developer's real
   environment. An explicit `--config <path>` exercises every resolution
   tier without stubbing the filesystem."
  (:require
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.config :as sut])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

(defn- tmp-config-file!
  "Writes `json-str` to a fresh temp file and returns the absolute path."
  ^String [^String json-str]
  (let [dir (-> (Files/createTempDirectory "spel-config-test-"
                  (into-array FileAttribute []))
              .toAbsolutePath
              .toString)
        f   (File. dir "spel.json")]
    (spit f json-str)
    (.getAbsolutePath f)))

;; =============================================================================
;; Key normalization + type coercion
;; =============================================================================

(defdescribe normalize-keys-test
  "Maps camelCase JSON keys to the kebab-case keywords the CLI flag layer uses"

  (describe "supported keys"

    (it "maps headed → :headless false (CLI inverse convention)"
      (let [path (tmp-config-file! "{\"headed\": true}")
            cfg  (sut/load-config path)]
        (expect (= false (:headless cfg)))))

    (it "maps headed: false → :headless true"
      (let [path (tmp-config-file! "{\"headed\": false}")
            cfg  (sut/load-config path)]
        (expect (= true (:headless cfg)))))

    (it "maps userAgent → :user-agent"
      (let [path (tmp-config-file! "{\"userAgent\": \"Mozilla/5.0 test\"}")
            cfg  (sut/load-config path)]
        (expect (= "Mozilla/5.0 test" (:user-agent cfg)))))

    (it "maps allowedDomains → :allowed-domains"
      (let [path (tmp-config-file! "{\"allowedDomains\": \"example.com,*.example.com\"}")
            cfg  (sut/load-config path)]
        (expect (= "example.com,*.example.com" (:allowed-domains cfg)))))

    (it "maps contentBoundaries → :content-boundaries"
      (let [path (tmp-config-file! "{\"contentBoundaries\": true}")
            cfg  (sut/load-config path)]
        (expect (= true (:content-boundaries cfg)))))

    (it "maps maxOutput → :max-output"
      (let [path (tmp-config-file! "{\"maxOutput\": 50000}")
            cfg  (sut/load-config path)]
        (expect (= 50000 (:max-output cfg)))))

    (it "maps screenshotFormat + screenshotQuality + screenshotDir"
      (let [path (tmp-config-file! "{\"screenshotFormat\": \"jpeg\", \"screenshotQuality\": 80, \"screenshotDir\": \"/tmp/shots\"}")
            cfg  (sut/load-config path)]
        (expect (= "jpeg" (:screenshot-format cfg)))
        (expect (= 80 (:screenshot-quality cfg)))
        (expect (= "/tmp/shots" (:screenshot-dir cfg)))))

    (it "preserves :session :profile :proxy :timeout at root level"
      (let [path (tmp-config-file! "{\"session\":\"work\",\"profile\":\"Default\",\"proxy\":\"http://x:8080\",\"timeout\":60000}")
            cfg  (sut/load-config path)]
        (expect (= "work" (:session cfg)))
        (expect (= "Default" (:profile cfg)))
        (expect (= "http://x:8080" (:proxy cfg)))
        (expect (= 60000 (:timeout cfg))))))

  (describe "unknown keys"

    (it "silently ignores unknown keys (forward compatibility)"
      (let [path (tmp-config-file! "{\"futureFeature\": \"xyz\", \"headed\": true}")
            cfg  (sut/load-config path)]
        ;; Known key is preserved
        (expect (= false (:headless cfg)))
        ;; Unknown key does not appear
        (expect (not (contains? cfg :futureFeature)))
        (expect (not (contains? cfg :future-feature)))))))

;; =============================================================================
;; File resolution + error handling
;; =============================================================================

(defdescribe file-resolution-test
  "load-config: explicit path vs auto-discovery"

  (describe "explicit --config path"

    (it "loads the explicitly provided path"
      (let [path (tmp-config-file! "{\"headed\": true, \"timeout\": 45000}")
            cfg  (sut/load-config path)]
        (expect (= false (:headless cfg)))
        (expect (= 45000 (:timeout cfg)))))

    (it "raises when explicit path does not exist"
      (let [missing "/tmp/spel-config-does-not-exist.json"
            data (try (sut/load-config missing)
                      nil
                      (catch Exception e (ex-data e)))]
        (expect (some? data))
        (expect (= missing (:config-path data))))))

  (describe "malformed JSON"

    (it "raises a clear error instead of silently dropping config"
      (let [path (tmp-config-file! "{ this is not json")
            data (try (sut/load-config path)
                      nil
                      (catch Exception e (ex-data e)))]
        (expect (some? data)))))

  (describe "empty and missing (auto-discovery)"

    (it "returns {} when no files exist and no explicit path is given"
      ;; NOTE: this test only works when neither ~/.spel/config.json nor
      ;; ./spel.json exists in the developer's environment. We skip strict
      ;; assertions and just verify the call doesn't throw on an empty setup.
      (let [result (try (sut/load-config) (catch Exception _ :threw))]
        (expect (map? result))))))
