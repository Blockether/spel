(ns com.blockether.spel.allure-api-test
  "Tests exercising the Allure in-test API.

   These tests verify that steps, metadata, parameters, attachments,
   and screenshots are properly captured and enriched in the Allure
   result JSON when running under the Allure reporter.

   When running without the Allure reporter, all API calls are no-ops
   and these tests still pass — they just don't produce enriched output.

   Also tests ui-step (before/after screenshots) and api-step
   (auto-attach HTTP response metadata) macros."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure]
   [com.blockether.spel.api :as api]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-fixtures
    :refer [*pw* *page* with-playwright with-browser with-page]]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Metadata and Steps (no browser needed)
;; =============================================================================

(defdescribe allure-metadata-test
  "Tests for Allure metadata API"

  (it "enriches test with epic, feature, story, severity, owner, tag"
    (allure/epic "E2E Testing")
    (allure/feature "Allure Integration")
    (allure/story "In-test API")
    (allure/severity :critical)
    (allure/owner "spel")
    (allure/tag "smoke")
    (allure/tag "allure-api")
    (allure/description "This test verifies that all metadata functions work correctly.")
    (expect true))

  (it "enriches test with links"
    (allure/link "Documentation" "https://allurereport.org")
    (allure/issue "BUG-42" "https://github.com/example/issues/42")
    (allure/tms "TC-100" "https://tms.example.com/tc/100")
    (expect true))

  (it "enriches test with parameters"
    (allure/parameter "browser" "chromium")
    (allure/parameter "headless" "true")
    (allure/parameter "locale" "en-US")
    (expect true))

  (it "enriches test with marker steps"
    (allure/step "Setup complete")
    (allure/step "Verified preconditions")
    (allure/step "Test body executed")
    (expect true))

  (it "enriches test with lambda steps"
    (allure/step "Compute sum"
      (allure/parameter "a" "1")
      (allure/parameter "b" "2")
      (let [result (+ 1 2)]
        (allure/step "Verify result"
          (expect (= 3 result))))))

  (it "enriches test with nested steps"
    (allure/step "Outer step"
      (allure/step "Middle step"
        (allure/step "Inner step"
          (expect true))
        (allure/step "Another inner step"
          (expect (= 4 (* 2 2)))))))

  (it "enriches test with text attachment"
    (allure/attach "Test Data" "{\"key\": \"value\"}" "application/json")
    (allure/attach "Log Output" "Step 1: OK\nStep 2: OK" "text/plain")
    (expect true)))

;; =============================================================================
;; Screenshot (needs browser)
;; =============================================================================

(defdescribe allure-screenshot-test
  "Tests for Allure screenshot API"

  (describe "with browser"
    {:context [with-playwright with-browser with-page]}

    (it "captures screenshot and attaches to report"
      (allure/epic "E2E Testing")
      (allure/feature "Screenshots")
      (allure/severity :normal)

      (allure/step "Navigate to test page"
        (page/set-content! *page* "<h1>Allure Screenshot Test</h1><p>Hello from spel!</p>"))

      (allure/step "Capture screenshot"
        (allure/screenshot *page* "Test Page"))

      (allure/step "Verify page title"
        (let [title (page/title *page*)]
          (allure/parameter "title" title)
          (expect true))))))

;; =============================================================================
;; ui-step — before/after screenshots
;; =============================================================================

(defdescribe allure-ui-step-test
  "Tests for ui-step macro"

  (describe "without browser (no *page*)"
    (it "ui-step executes body and returns result without *page*"
      (let [result (allure/ui-step "Compute something"
                     (+ 1 2 3))]
        (expect (= 6 result))))

    (it "ui-step with side effects works"
      (let [log (atom [])]
        (allure/ui-step "Log some stuff"
          (swap! log conj :step-1)
          (swap! log conj :step-2))
        (expect (= [:step-1 :step-2] @log))))

    (it "nested ui-steps work"
      (let [result (allure/ui-step "Outer"
                     (allure/ui-step "Inner"
                       :inner-result))]
        (expect (= :inner-result result)))))

  (describe "with browser"
    {:context [with-playwright with-browser with-page]}

    (it "ui-step executes body with page bound"
      (page/set-content! *page* "<h1>UI Step Test</h1>")
      (let [result (allure/ui-step "Check heading"
                     (let [title (page/title *page*)]
                       (expect (some? title))
                       :checked))]
        (expect (= :checked result))))

    (it "ui-step captures before/after screenshots (no error)"
      (page/set-content! *page* "<h1>Before/After Test</h1><p>Content here</p>")
      ;; This should complete without error — screenshots are captured
      ;; as child steps (visible in Allure report, no-op without reporter)
      (allure/ui-step "Navigate and verify"
        (let [h1 (locator/text-content (page/locator *page* "h1"))]
          (expect (= "Before/After Test" h1)))))

    (it "ui-step returns the body value"
      (page/set-content! *page* "<p>42</p>")
      (let [result (allure/ui-step "Read paragraph"
                     (locator/text-content (page/locator *page* "p")))]
        (expect (= "42" result))))))

;; =============================================================================
;; api-step — auto-attach HTTP response metadata
;; =============================================================================

(defdescribe allure-api-step-test
  "Tests for api-step macro"

  (describe "without Allure reporter (no-op mode)"
    (it "api-step executes body and returns non-APIResponse result"
      (let [result (allure/api-step "Compute"
                     (* 6 7))]
        (expect (= 42 result))))

    (it "api-step with map result works"
      (let [result (allure/api-step "Build map"
                     {:status 200 :body "ok"})]
        (expect (= 200 (:status result)))))

    (it "api-step with nil result works"
      (let [result (allure/api-step "Nil op"
                     nil)]
        (expect (nil? result)))))

  (describe "with real HTTP response"
    {:context [with-playwright with-test-server]}

    (it "api-step returns the APIResponse"
      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "GET health check"
                     (api/api-get ctx "/health"))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (api/api-response-status resp))))))

    (it "api-step with POST returns response"
      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "POST to echo"
                     (api/api-post ctx "/echo"
                       {:data "{\"action\":\"test\"}"
                        :headers {"Content-Type" "application/json"}}))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (api/api-response-status resp)))
          (expect (str/includes?
                    (api/api-response-text resp) "POST")))))

    (it "api-step with non-200 response still works"
      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (let [resp (allure/api-step "GET 404 endpoint"
                     (api/api-get ctx "/status/404"))]
          (expect (= 404 (api/api-response-status resp)))
          (expect (false? (api/api-response-ok? resp))))))

    (it "api-step nested in regular step"
      (api/with-api-context [ctx (api/new-api-context (api/api-request *pw*)
                                   {:base-url *test-server-url*})]
        (allure/step "API test flow"
          (let [resp (allure/api-step "Health check"
                       (api/api-get ctx "/health"))]
            (expect (= 200 (api/api-response-status resp)))))))))
