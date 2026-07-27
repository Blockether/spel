(ns com.blockether.spel.fonts-test
  "Tests for the bundled typography.

   These guard the packaging contract: the faces must resolve from the
   classpath (jar included), decode back to real woff2 bytes, and stay
   declared in the GraalVM resource config so the native binary embeds
   them too. `fonts/data-uri` degrades silently to nil, so without these
   tests a dropped resource would only show up as ugly artifacts."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.blockether.spel.fonts :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]])
  (:import
   [java.util Base64]))

(defn- decoded-bytes
  "Raw bytes carried by a `data:font/woff2;base64,...` URI."
  [uri]
  (.decode (Base64/getDecoder) ^String (subs uri (count "data:font/woff2;base64,"))))

(defdescribe bundled-fonts-test
  "Every declared face is present, decodable and shipped in every artifact"

  (describe "resources"

    (it "resolves every declared face from the classpath"
      (doseq [{:keys [file]} sut/bundled-font-faces]
        (expect (some? (io/resource (str "com/blockether/spel/fonts/" file))))
        (expect (some? (sut/data-uri file)))))

    (it "encodes real woff2 payloads"
      (doseq [{:keys [file]} sut/bundled-font-faces]
        (let [bs (decoded-bytes (sut/data-uri file))]
          ;; woff2 signature: wOF2
          (expect (= [0x77 0x4F 0x46 0x32]
                    (mapv #(bit-and (int %) 0xFF) (take 4 bs))))
          (expect (< 10000 (count bs)))))))

  (describe "css"

    (it "emits one @font-face per declared face, all inline"
      (let [css @sut/bundled-font-css]
        (expect (= (count sut/bundled-font-faces)
                  (count (re-seq #"@font-face" css))))
        (expect (= (count sut/bundled-font-faces)
                  (count (re-seq #"data:font/woff2;base64," css))))
        (expect (not (str/includes? css "http")))
        (expect (str/includes? css "font-display:swap"))))

    (it "wraps the faces in a self-contained style tag"
      (let [tag (sut/style-tag)]
        (expect (str/starts-with? tag "<style id=\"bundled-fonts\">"))
        (expect (str/ends-with? tag "</style>"))
        (expect (not (str/includes? tag "<link"))))))

  (describe "native-image packaging"

    (it "declares the fonts directory in the GraalVM resource config"
      (let [cfg (slurp (io/resource "META-INF/native-image/com.blockether/spel/resource-config.json"))]
        (expect (str/includes? cfg "com/blockether/spel/fonts/.*"))))))
