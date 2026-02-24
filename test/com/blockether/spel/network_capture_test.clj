(ns com.blockether.spel.network-capture-test
  "Tests for network-capture HTML rendering and capture functionality."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :refer [defdescribe describe it expect]]
   [com.blockether.spel.network-capture :as net-capture]))

(defdescribe network-capture-tests

  (describe "render-network-html"

    (it "renders basic GET request"
      (let [html (net-capture/render-network-html
                   {:method "GET"
                    :url "https://api.example.com/users"
                    :status 200
                    :status-text "OK"
                    :request-headers {"accept" "application/json"}
                    :response-headers {"content-type" "application/json"}
                    :request-body nil
                    :response-body "{\"users\": []}"
                    :duration-ms 42})]
        (expect (string? html))
        (expect (str/includes? html "GET"))
        (expect (str/includes? html "https://api.example.com/users"))
        (expect (str/includes? html "200"))
        (expect (str/includes? html "42 ms"))
        (expect (str/includes? html "curl"))))

    (it "renders POST request with body"
      (let [html (net-capture/render-network-html
                   {:method "POST"
                    :url "https://api.example.com/users"
                    :status 201
                    :status-text "Created"
                    :request-headers {"content-type" "application/json"
                                      "authorization" "Bearer token123"}
                    :response-headers {"content-type" "application/json"}
                    :request-body "{\"name\": \"Alice\", \"age\": 30}"
                    :response-body "{\"id\": 1, \"name\": \"Alice\"}"
                    :duration-ms 150})]
        (expect (str/includes? html "POST"))
        (expect (str/includes? html "201"))
        (expect (str/includes? html "Alice"))
        ;; JSON syntax highlighting - keys should be colored
        (expect (str/includes? html "color:#9876aa"))))

    (it "renders DELETE request with error status"
      (let [html (net-capture/render-network-html
                   {:method "DELETE"
                    :url "https://api.example.com/users/1"
                    :status 404
                    :status-text "Not Found"
                    :request-headers {}
                    :response-headers {}
                    :request-body nil
                    :response-body nil
                    :duration-ms nil})]
        (expect (str/includes? html "DELETE"))
        (expect (str/includes? html "404"))
        (expect (str/includes? html "No request body"))))

    (it "renders 500 error with red status"
      (let [html (net-capture/render-network-html
                   {:method "GET"
                    :url "https://api.example.com/crash"
                    :status 500
                    :status-text "Internal Server Error"
                    :request-headers {}
                    :response-headers {}
                    :request-body nil
                    :response-body "{\"error\": \"something went wrong\"}"
                    :duration-ms 5000})]
        ;; 5xx should use red color #F44336
        (expect (str/includes? html "#F44336"))
        (expect (str/includes? html "500"))))

    (it "handles nil/empty bodies gracefully"
      (let [html (net-capture/render-network-html
                   {:method "HEAD"
                    :url "https://example.com"
                    :status 204
                    :status-text "No Content"
                    :request-headers nil
                    :response-headers nil
                    :request-body nil
                    :response-body nil
                    :duration-ms nil})]
        (expect (string? html))
        (expect (str/includes? html "HEAD"))
        (expect (str/includes? html "No request body"))
        (expect (str/includes? html "No response body"))))

    (it "generates cURL command"
      (let [html (net-capture/render-network-html
                   {:method "POST"
                    :url "https://api.example.com/data"
                    :status 200
                    :status-text "OK"
                    :request-headers {"content-type" "application/json"
                                      "x-api-key" "secret"}
                    :response-headers {}
                    :request-body "{\"key\": \"value\"}"
                    :response-body nil
                    :duration-ms nil})]
        (expect (str/includes? html "curl"))
        (expect (str/includes? html "-X POST"))
        (expect (str/includes? html "x-api-key")))))

  (describe "highlight-json"
    (it "highlights JSON keys and string values"
      (let [result (#'net-capture/highlight-json "{\"name\": \"Alice\"}")]
        ;; Keys: purple (#9876aa), strings: green (#6a8759)
        (expect (str/includes? result "#9876aa"))
        (expect (str/includes? result "#6a8759"))))

    (it "highlights numbers"
      (let [result (#'net-capture/highlight-json "{\"count\": 42}")]
        (expect (str/includes? result "#6897bb"))))

    (it "highlights booleans and null"
      (let [result (#'net-capture/highlight-json "{\"ok\": true, \"err\": null}")]
        (expect (str/includes? result "#cc7832"))))))
