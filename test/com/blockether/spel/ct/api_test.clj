(ns com.blockether.spel.ct.api-test
  "clojure.test tests for API testing functions.
   Tests: page-api, context-api, with-testing-api, with-page-api"
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.api :as api]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.test-server :as ts])
  (:import
   [com.microsoft.playwright APIRequestContext]))

;; page-api

(deftest page-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "page-api")
  (testing "returns APIRequestContext"
    (core/with-testing-page [pg]
      (is (instance? APIRequestContext (api/page-api pg))))))

(deftest page-api-get-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "page-api")
  (testing "makes GET requests"
    (core/with-testing-page [pg]
      (let [api-ctx (api/page-api pg)
            resp (api/api-get api-ctx (str ts/*test-server-url* "/health"))]
        (is (= 200 (api/api-response-status resp)))))))

;; context-api

(deftest context-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "context-api")
  (testing "returns APIRequestContext"
    (core/with-playwright [pw]
      (core/with-browser [browser (core/launch-chromium pw)]
        (core/with-context [ctx (core/new-context browser)]
          (is (instance? APIRequestContext (api/context-api ctx))))))))

;; with-testing-api

(deftest with-testing-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-testing-api")
  (testing "creates working API context"
    (api/with-testing-api {:base-url ts/*test-server-url*} [ctx]
      (let [resp (api/api-get ctx "/health")]
        (is (= 200 (api/api-response-status resp)))))))

(deftest with-testing-api-post-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-testing-api")
  (testing "supports POST"
    (api/with-testing-api {:base-url ts/*test-server-url*} [ctx]
      (let [resp (api/api-post ctx "/echo" {:data "test"})]
        (is (= 200 (api/api-response-status resp)))))))

;; with-page-api

(deftest with-page-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-page-api")
  (testing "creates API context with custom base-url"
    (core/with-testing-page [pg]
      (api/with-page-api pg {:base-url ts/*test-server-url*} [ctx]
        (let [resp (api/api-get ctx "/health")]
          (is (= 200 (api/api-response-status resp))))))))

;; run-with-testing-api

(deftest run-with-testing-api-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "run-with-testing-api")
  (testing "functional variant"
    (let [result (api/run-with-testing-api {:base-url ts/*test-server-url*}
                   (fn [ctx]
                     (api/api-response-status (api/api-get ctx "/health"))))]
      (is (= 200 result)))))

;; run-with-page-api

(deftest run-with-page-api-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "run-with-page-api")
  (testing "functional variant"
    (core/with-testing-page [pg]
      (let [result (api/run-with-page-api pg {:base-url ts/*test-server-url*}
                     (fn [ctx]
                       (api/api-response-status (api/api-get ctx "/health"))))]
        (is (= 200 result))))))
