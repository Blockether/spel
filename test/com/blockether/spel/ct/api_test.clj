(ns com.blockether.spel.ct.api-test
  "clojure.test tests for API testing functions.
   Tests: page-api, context-api, with-testing-api, with-page-api"
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.core :as api]
   [com.blockether.spel.test-server :as ts])
  (:import
   [com.microsoft.playwright APIRequestContext]))

(clojure.test/use-fixtures :each
  (fn [f]
    (let [server (ts/start-test-server)
          port   (ts/server-port server)
          url    (str "http://localhost:" port)]
      (try
        (binding [ts/*test-server-url* url]
          (f))
        (finally
          (ts/stop-test-server server))))))

;; page-api

(deftest page-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "page-api")
  (testing "returns APIRequestContext"
    (api/with-testing-page [pg]
      (is (instance? APIRequestContext (api/page-api pg))))))

(deftest page-api-get-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "page-api")
  (testing "makes GET requests"
    (api/with-testing-page [pg]
      (let [api-ctx (api/page-api pg)
            resp (allure/api-step "GET /health via page-api"
                   (api/api-get api-ctx (str ts/*test-server-url* "/health")))]
        (is (= 200 (api/api-response-status resp)))))))

;; context-api

(deftest context-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "context-api")
  (testing "returns APIRequestContext"
    (api/with-playwright [pw]
      (api/with-browser [browser (api/launch-chromium pw)]
        (api/with-context [ctx (api/new-context browser)]
          (is (instance? APIRequestContext (api/context-api ctx))))))))

;; with-testing-api

(deftest with-testing-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-testing-api")
  (testing "creates working API context"
    (api/with-testing-api {:base-url ts/*test-server-url*} [ctx]
      (let [resp (allure/api-step "GET /health"
                   (api/api-get ctx "/health"))]
        (is (= 200 (api/api-response-status resp)))))))

(deftest with-testing-api-post-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-testing-api")
  (testing "supports POST"
    (api/with-testing-api {:base-url ts/*test-server-url*} [ctx]
      (let [resp (allure/api-step "POST /echo"
                   (api/api-post ctx "/echo"
                     {:data "{\"action\":\"test\"}"
                      :headers {"Content-Type" "application/json"}}))]
        (is (= 200 (api/api-response-status resp)))))))

;; with-page-api

(deftest with-page-api-basic-test
  (allure/epic "API Testing (clojure.test)")
  (allure/feature "with-page-api")
  (testing "creates API context with custom base-url"
    (api/with-testing-page [pg]
      (api/with-page-api pg {:base-url ts/*test-server-url*} [ctx]
        (let [resp (allure/api-step "GET /health via page-api"
                     (api/api-get ctx "/health"))]
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
    (api/with-testing-page [pg]
      (let [result (api/run-with-page-api pg {:base-url ts/*test-server-url*}
                     (fn [ctx]
                       (api/api-response-status (api/api-get ctx "/health"))))]
        (is (= 200 result))))))
