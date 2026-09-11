(ns vis-spel-host-test
  "Crosses Vis registration, the trusted Python worker and the native browser."
  (:require [clojure.java.io :as io]
            [com.blockether.vis.internal.python.extensions-test :as fixtures]
            [lazytest.experimental.interfaces.clojure-test :refer [deftest is]]))

(deftest native-browser-through-trusted-worker
  (let [package (.getParentFile (.getParentFile (io/file (io/resource "vis_spel_host_test.clj"))))
        sources (into {} (for [path ["extension.py" "pyproject.toml" "uv.lock" "README.md"
                                     "src/vis_spel/__init__.py" "src/vis_spel/install.py"
                                     "skills/browser/SKILL.md"]]
                           [(str "vis-spel/" path) (slurp (io/file package path))]))]
    (#'fixtures/with-shared-packages
     (fn [_]
       (#'fixtures/with-fresh-loaded
        sources
        (fn [loaded _]
          (is (= 1 (:loaded loaded)))
          (is (zero? (:failed loaded)))
          (let [ext (#'fixtures/registered "vis-spel")
                invoke (fn [sym & args]
                         (let [result (apply (#'fixtures/symbol-fn ext sym) args)]
                           (is (:success? result) (pr-str result))
                           (:result result)))
                installation (invoke 'spel.installed)]
            (is (= "0.9.33" (get-in installation ["__vis_attrs__" "version"])))
            (let [lease (invoke 'spel.reserve)
                  id (get-in lease ["__vis_attrs__" "id"])]
              (is (string? id))
              (try
                (invoke 'spel.open id "data:text/html,<title>Vis Spel host</title><button>Owned fixture</button>")
                (is (re-find #"Owned fixture" (pr-str (invoke 'spel.snapshot id))))
                (is (= "Vis Spel host"
                      (get-in (invoke 'spel.evaluate id "document.title")
                        ["__vis_attrs__" "data" "result"])))
                (is (= "ok" (get-in (invoke 'spel.health id)
                              ["__vis_attrs__" "data" "status"])))
                (finally (invoke 'spel.release id)))))))))))
