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
   [com.blockether.spel.core :as core]
   [clojure.string :as str]
   [com.blockether.spel.allure :as allure :refer [defdescribe describe expect it around]]
   [com.blockether.spel.locator :as locator]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.test-server
    :refer [*test-server-url* with-test-server]]))

;; =============================================================================
;; Metadata and Steps (no browser needed)
;; =============================================================================

(defdescribe allure-metadata-test
  "Tests for Allure metadata API"
  (around [f] (core/with-testing-browser (f)))

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
    (allure/tms "TC-100" "https://tms.example.org/tc/100")
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
  (around [f] (core/with-testing-browser (f)))

  (describe "with browser"

    (it "captures screenshot and attaches to report"
      (core/with-testing-page [pg]
        (allure/epic "E2E Testing")
        (allure/feature "Screenshots")
        (allure/severity :normal)

        (allure/step "Navigate to test page"
          (page/set-content! pg "<h1>Allure Screenshot Test</h1><p>Hello from spel!</p>"))

        (allure/step "Capture screenshot"
          (allure/screenshot pg "Test Page"))

        (allure/step "Verify page title"
          (let [title (page/title pg)]
            (allure/parameter "title" title)
            (expect true)))))

    (it "compares baseline screenshot and returns diff stats"
      (core/with-testing-page [pg]
        (page/set-content! pg "<h1>Visual Baseline</h1><p>before</p>")
        (let [baseline (page/screenshot pg)]
          (page/set-content! pg "<h1>Visual Baseline</h1><p>after</p>")
          (let [r (allure/visual-diff pg baseline "Visual Diff")]
            (expect (map? r))
            (expect (contains? r :matched))
            (expect (number? (:diff-count r)))
            (expect (nil? (:diff-image r)))))))))

;; =============================================================================
;; ui-step — before/after screenshots
;; =============================================================================

(defdescribe allure-ui-step-test
  "Tests for ui-step macro"
  (around [f] (core/with-testing-browser (f)))

  (describe "without browser (no pg)"
    (it "ui-step executes body and returns result without pg"
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

    (it "ui-step executes body with page bound"
      (core/with-testing-page [pg]
        (page/set-content! pg "<h1>UI Step Test</h1>")
        (let [result (allure/ui-step "Check heading"
                       (let [title (page/title pg)]
                         (expect (some? title))
                         :checked))]
          (expect (= :checked result)))))

    (it "ui-step captures before/after screenshots (no error)"
      (core/with-testing-page [pg]
        (page/set-content! pg "<h1>Before/After Test</h1><p>Content here</p>")
        ;; This should complete without error — screenshots are captured
        ;; as child steps (visible in Allure report, no-op without reporter)
        (allure/ui-step "Navigate and verify"
          (let [h1 (locator/text-content (page/locator pg "h1"))]
            (expect (= "Before/After Test" h1))))))

    (it "ui-step returns the body value"
      (core/with-testing-page [pg]
        (page/set-content! pg "<p>42</p>")
        (let [result (allure/ui-step "Read paragraph"
                       (locator/text-content (page/locator pg "p")))]
          (expect (= "42" result)))))))

;; =============================================================================
;; api-step — auto-attach HTTP response metadata
;; =============================================================================

(defdescribe allure-api-step-test
  "Tests for api-step macro"
  (around [f] (core/with-testing-browser (f)))
  (around [f] ((:around with-test-server) f))

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

    (it "api-step returns the APIResponse"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "GET health check"
                     (core/api-get ctx "/health"))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (core/api-response-status resp))))))

    (it "api-step with POST returns response"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "POST to echo"
                     (core/api-post ctx "/echo"
                       {:data "{\"action\":\"test\"}"
                        :headers {"Content-Type" "application/json"}}))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (core/api-response-status resp)))
          (expect (str/includes?
                    (core/api-response-text resp) "POST")))))

    (it "api-step with non-200 response still works"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/api-step "GET 404 endpoint"
                     (core/api-get ctx "/status/404"))]
          (expect (= 404 (core/api-response-status resp)))
          (expect (false? (core/api-response-ok? resp))))))

    (it "api-step nested in regular step"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (allure/step "API test flow"
          (let [resp (allure/api-step "Health check"
                       (core/api-get ctx "/health"))]
            (expect (= 200 (core/api-response-status resp)))))))))

;; =============================================================================
;; Unified step with opts — composable behaviors
;; =============================================================================

(defdescribe unified-step-opts-test
  "Tests for unified step macro with opts map"
  (around [f] (core/with-testing-browser (f)))
  (around [f] ((:around with-test-server) f))

  (describe "step with empty/no opts behaves like plain step"
    (it "step with no opts executes body"
      (let [result (allure/step "Plain step"
                     (+ 10 20))]
        (expect (= 30 result))))

    (it "marker step still works"
      (allure/step "Just a marker")
      (expect true)))

  (describe "step with {:screenshots? true} (ui-step equivalent)"
    (it "works without pg bound"
      (let [result (allure/step "Screenshot step no page" {:screenshots? true}
                     (+ 1 2 3))]
        (expect (= 6 result))))

    (it "works with pg bound"
      (core/with-testing-page [pg]
        (page/set-content! pg "<h1>Opts Screenshot</h1>")
        (let [result (allure/step "Screenshot step with page" {:screenshots? true}
                       (locator/text-content (page/locator pg "h1")))]
          (expect (= "Opts Screenshot" result))))))

  (describe "step with {:http? true} (api-step equivalent)"
    (it "works with non-response result"
      (let [result (allure/step "HTTP step no response" {:http? true}
                     {:some "data"})]
        (expect (= {:some "data"} result))))

    (it "works with API response"
      (core/with-testing-api {:base-url *test-server-url*} [ctx]
        (let [resp (allure/step "GET via step opts" {:http? true}
                     (core/api-get ctx "/health"))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (core/api-response-status resp)))))))

  (describe "step with {:screenshots? true :http? true} (both)"
    (it "works without browser"
      (let [result (allure/step "Combined no browser" {:screenshots? true :http? true}
                     42)]
        (expect (= 42 result))))

    (it "works with browser and API"
      (core/with-testing-page [pg]
        (page/set-content! pg "<h1>Combined</h1>")
        (let [resp (allure/step "Combined step" {:screenshots? true :http? true}
                     (core/with-testing-api {:base-url *test-server-url*} [ctx]
                       (core/api-get ctx "/health")))]
          (expect (instance? com.microsoft.playwright.APIResponse resp))
          (expect (= 200 (core/api-response-status resp)))))))

  (describe "step with :opts keyword (runtime opts)"
    (it "supports runtime opts expression"
      (let [my-opts {:http? true}
            result  (allure/step "Runtime opts step" :opts my-opts
                      (* 7 8))]
        (expect (= 56 result))))

    (it "supports nil runtime opts (plain step)"
      (let [result (allure/step "Nil opts" :opts nil
                     :ok)]
        (expect (= :ok result)))))

  (describe "ui-step and api-step still work as wrappers"
    (it "ui-step delegates to step with {:screenshots? true}"
      (let [result (allure/ui-step "Wrapper ui-step"
                     (* 3 4))]
        (expect (= 12 result))))

    (it "api-step delegates to step with {:http? true}"
      (let [result (allure/api-step "Wrapper api-step"
                     (str "hello" " " "world"))]
        (expect (= "hello world" result))))))
