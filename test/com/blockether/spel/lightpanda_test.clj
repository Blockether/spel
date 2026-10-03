(ns com.blockether.spel.lightpanda-test
  "Lightpanda engine validation and command metadata."
  (:require
   [com.blockether.spel.daemon :as daemon]
   [lazytest.core :refer [defdescribe describe it expect]]))

(defdescribe lightpanda-engine-test
  (describe "launch validation"
    (it "accepts the default engine and headless Lightpanda"
      (with-redefs [daemon/!state (atom {})]
        (expect (nil? (#'daemon/browser-engine-rejection {})))
        (expect (nil? (#'daemon/browser-engine-rejection {"engine" "lightpanda"})))))

    (it "rejects unknown engines and incompatible launch options"
      (with-redefs [daemon/!state (atom {})]
        (expect (= "unknown_engine"
                  (:error_code (#'daemon/browser-engine-rejection {"engine" "typo"}))))
        (doseq [flag [{"headless" false} {"profile" "/tmp/profile"}
                      {"cdp" "9222"} {"auto-launch" true} {"browser" "firefox"}
                      {"channel" "chrome"} {"proxy" "http://127.0.0.1:8080"}
                      {"extensions" ["/tmp/extension"]} {"device" "iPhone 14"}]]
          (expect (= "unsupported_engine_option"
                    (:error_code (#'daemon/browser-engine-rejection
                                  (assoc flag "engine" "lightpanda"))))))))

    (it "keeps a live session on its original engine"
      (with-redefs [daemon/!state (atom {:browser :running :launch-flags {"engine" "lightpanda"}})]
        (expect (= "engine_conflict"
                  (:error_code (#'daemon/browser-engine-rejection {"engine" "chrome"})))))))

  (describe "non-visual output"
    (it "labels text exports without changing Chromium results"
      (doseq [engine ["chrome" "lightpanda"]]
        (with-redefs [daemon/!state (atom {:launch-flags {"engine" engine}})
                      daemon/handle-cmd (fn [_ _] {:path "export"})]
          (expect (= (cond-> {:path "export"}
                       (= engine "lightpanda") (assoc :rendering "text-only"))
                    (#'daemon/dispatch-cmd "screenshot" {}))))))

    (it "rejects visual annotation before calling the browser"
      (with-redefs [daemon/!state (atom {:launch-flags {"engine" "lightpanda"}})
                    daemon/handle-cmd (fn [_ _] (throw (Exception. "Unexpected browser call")))]
        (expect (= "unsupported_engine_command"
                  (try (#'daemon/dispatch-cmd "screenshot" {"annotate" true})
                    (catch clojure.lang.ExceptionInfo e (:error_code (ex-data e))))))))))
