(ns com.blockether.spel.reporter-util
  "Shared helpers for Lazytest reporters."
  (:require
   [clojure.string :as str]
   [lazytest.suite :as s]
   [lazytest.test-case :as tc])
  (:import
   [java.net InetAddress]))

(defn hostname
  "Returns the host name, or localhost if it is not available."
  ^String []
  (try (.getHostName (InetAddress/getLocalHost))
       (catch Exception _ "localhost")))

(defn doc-str
  "Returns the name of a namespace or var, or a nonblank string."
  [doc]
  (cond
    (instance? clojure.lang.Namespace doc) (str (ns-name doc))
    (instance? clojure.lang.Var doc) (str (:name (meta doc)))
    (and (some? doc) (not (str/blank? (str doc)))) (str doc)))

(defn ns-suites
  "Returns the namespace suites in a run result."
  [result]
  (when (s/suite-result? result)
    (filter #(and (s/suite-result? %)
                  (= :lazytest/ns (-> % :source :type)))
      (:children result))))

(defn collect-test-cases
  "Returns the leaf results in depth-first order, with their path and namespace."
  ([result]
   (collect-test-cases result [] nil))
  ([result path ns-name]
   (if (s/suite-result? result)
     (let [source-type (-> result :source :type)
           doc (doc-str (:doc result))
           new-ns (if (= :lazytest/ns source-type) (or doc ns-name) ns-name)
           new-path (if (and doc (not= :lazytest/run source-type) (not= :lazytest/ns source-type))
                      (conj path doc)
                      path)]
       (mapcat #(collect-test-cases % new-path new-ns) (:children result)))
     [(assoc result ::path path ::ns-name ns-name)])))

(defn ns-package
  "Returns the part of the namespace name before the last dot."
  ^String [^String ns-name]
  (let [idx (.lastIndexOf ns-name ".")]
    (if (pos? idx) (subs ns-name 0 idx) "")))

(defn install-output-capture!
  "Saves the current test hook and replaces it with a wrapper."
  [run-state wrap-try-test-case]
  (let [original (deref #'tc/try-test-case)]
    (swap! run-state assoc :original-try-test-case original)
    (alter-var-root #'tc/try-test-case wrap-try-test-case)))

(defn uninstall-output-capture!
  "Restores the saved test hook, if there is one."
  [run-state]
  (when-let [original (:original-try-test-case @run-state)]
    (alter-var-root #'tc/try-test-case (constantly original))))
