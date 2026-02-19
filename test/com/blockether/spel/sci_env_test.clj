(ns com.blockether.spel.sci-env-test
  "Tests for the sci-env namespace.

   Unit tests verify SCI context creation and expression evaluation
   without a browser. Integration tests use SCI eval to drive a
   real Playwright session."
  (:require
   [com.blockether.spel.sci-env :as sut]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — SCI Context & Evaluation
;; =============================================================================

(defdescribe create-sci-ctx-test
  "Unit tests for SCI context creation"

  (describe "context creation"
    (it "returns a non-nil context"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? ctx)))))

  (describe "basic evaluation"
    (it "evaluates arithmetic"
      (let [ctx (sut/create-sci-ctx)]
        (expect (= 6 (sut/eval-string ctx "(+ 1 2 3)")))))

    (it "evaluates string operations"
      (let [ctx (sut/create-sci-ctx)]
        (expect (= "hello world"
                  (sut/eval-string ctx "(str \"hello\" \" \" \"world\")")))))

    (it "evaluates collections"
      (let [ctx (sut/create-sci-ctx)]
        (expect (= [2 3 4]
                  (sut/eval-string ctx "(mapv inc [1 2 3])")))))

    (it "evaluates let bindings"
      (let [ctx (sut/create-sci-ctx)]
        (expect (= 10
                  (sut/eval-string ctx "(let [x 5] (* x 2))")))))

    (it "evaluates fn definitions"
      (let [ctx (sut/create-sci-ctx)]
        (expect (= 25
                  (sut/eval-string ctx "((fn [x] (* x x)) 5)")))))))

(defdescribe sci-namespace-availability-test
  "Unit tests for registered SCI namespaces"

  (describe "spel namespace"
    (it "has goto function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/goto)")))))

    (it "has click function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/click)")))))

    (it "has start! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/start!)")))))

    (it "has stop! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/stop!)")))))

    (it "has snapshot function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/snapshot)")))))

    (it "has url function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/url)")))))

    (it "has title function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/title)")))))

    (it "has annotate function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/annotate)")))))

    (it "has unannotate function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? spel/unannotate)"))))))

  (describe "snapshot namespace"
    (it "has capture function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? snapshot/capture)")))))

    (it "has capture-full function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? snapshot/capture-full)")))))

    (it "has resolve-ref function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? snapshot/resolve-ref)")))))

    (it "has ref-bounding-box function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? snapshot/ref-bounding-box)"))))))

  (describe "annotate namespace"
    (it "has annotated-screenshot function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? annotate/annotated-screenshot)")))))

    (it "has save! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? annotate/save!)"))))))

  (describe "page namespace (raw Page-arg functions)"
    (it "has navigate function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/navigate)")))))

    (it "has locator function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/locator)")))))

    (it "has get-by-role function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/get-by-role)")))))

    (it "has get-by-text function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/get-by-text)")))))

    (it "has get-by-label function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/get-by-label)")))))

    (it "has title function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/title)")))))

    (it "has url function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/url)")))))

    (it "has screenshot function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/screenshot)")))))

    (it "has evaluate function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/evaluate)")))))

    (it "has wait-for-load-state function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/wait-for-load-state)")))))

    (it "has set-content! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? page/set-content!)"))))))

  (describe "locator namespace (raw Locator-arg functions)"
    (it "has click function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/click)")))))

    (it "has fill function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/fill)")))))

    (it "has type-text function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/type-text)")))))

    (it "has press function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/press)")))))

    (it "has text-content function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/text-content)")))))

    (it "has is-visible? function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/is-visible?)")))))

    (it "has hover function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/hover)")))))

    (it "has check function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/check)")))))

    (it "has select-option function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/select-option)")))))

    (it "has count-elements function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/count-elements)")))))

    (it "has all function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/all)")))))

    (it "has wait-for function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/wait-for)")))))

    (it "has screenshot function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? locator/screenshot)"))))))

  (describe "core namespace (lifecycle macros and functions)"
    (it "with-playwright is a macro"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx
                         "(-> (resolve 'core/with-playwright) meta :sci/macro)")))))

    (it "with-browser is a macro"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx
                         "(-> (resolve 'core/with-browser) meta :sci/macro)")))))

    (it "with-context is a macro"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx
                         "(-> (resolve 'core/with-context) meta :sci/macro)")))))

    (it "with-page is a macro"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx
                         "(-> (resolve 'core/with-page) meta :sci/macro)")))))

    (it "has launch-chromium function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/launch-chromium)")))))

    (it "has launch-firefox function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/launch-firefox)")))))

    (it "has launch-webkit function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/launch-webkit)")))))

    (it "has create function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/create)")))))

    (it "has new-context function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/new-context)")))))

    (it "has new-page function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/new-page)")))))

    (it "has new-page-from-context function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/new-page-from-context)")))))

    (it "has close! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/close!)")))))

    (it "has close-browser! function"
      (let [ctx (sut/create-sci-ctx)]
        (expect (true? (sut/eval-string ctx "(fn? core/close-browser!)")))))))

(defdescribe sci-enum-availability-test
  "Unit tests for Playwright enum access in SCI"

  (describe "AriaRole enum"
    (it "resolves AriaRole/BUTTON"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "AriaRole/BUTTON")))))

    (it "resolves AriaRole/HEADING"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "AriaRole/HEADING")))))

    (it "resolves AriaRole/NAVIGATION"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "AriaRole/NAVIGATION")))))

    (it "resolves AriaRole/LINK"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "AriaRole/LINK"))))))

  (describe "LoadState enum"
    (it "resolves LoadState/LOAD"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "LoadState/LOAD")))))

    (it "resolves LoadState/DOMCONTENTLOADED"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "LoadState/DOMCONTENTLOADED")))))

    (it "resolves LoadState/NETWORKIDLE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "LoadState/NETWORKIDLE"))))))

  (describe "WaitUntilState enum"
    (it "resolves WaitUntilState/LOAD"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "WaitUntilState/LOAD")))))

    (it "resolves WaitUntilState/COMMIT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "WaitUntilState/COMMIT"))))))

  (describe "WaitForSelectorState enum"
    (it "resolves WaitForSelectorState/VISIBLE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "WaitForSelectorState/VISIBLE")))))

    (it "resolves WaitForSelectorState/HIDDEN"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "WaitForSelectorState/HIDDEN")))))

    (it "resolves WaitForSelectorState/ATTACHED"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "WaitForSelectorState/ATTACHED"))))))

  (describe "MouseButton enum"
    (it "resolves MouseButton/LEFT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "MouseButton/LEFT")))))

    (it "resolves MouseButton/RIGHT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "MouseButton/RIGHT"))))))

  (describe "ScreenshotType enum"
    (it "resolves ScreenshotType/PNG"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ScreenshotType/PNG")))))

    (it "resolves ScreenshotType/JPEG"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ScreenshotType/JPEG"))))))

  (describe "Media enum"
    (it "resolves Media/SCREEN"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "Media/SCREEN")))))

    (it "resolves Media/PRINT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "Media/PRINT"))))))

  (describe "ColorScheme enum"
    (it "resolves ColorScheme/LIGHT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ColorScheme/LIGHT")))))

    (it "resolves ColorScheme/DARK"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ColorScheme/DARK")))))

    (it "resolves ColorScheme/NO_PREFERENCE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ColorScheme/NO_PREFERENCE"))))))

  (describe "ForcedColors enum"
    (it "resolves ForcedColors/ACTIVE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ForcedColors/ACTIVE")))))

    (it "resolves ForcedColors/NONE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ForcedColors/NONE"))))))

  (describe "ReducedMotion enum"
    (it "resolves ReducedMotion/REDUCE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ReducedMotion/REDUCE")))))

    (it "resolves ReducedMotion/NO_PREFERENCE"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ReducedMotion/NO_PREFERENCE"))))))

  (describe "HarMode enum"
    (it "resolves HarMode/FULL"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarMode/FULL")))))

    (it "resolves HarMode/MINIMAL"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarMode/MINIMAL"))))))

  (describe "HarContentPolicy enum"
    (it "resolves HarContentPolicy/EMBED"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarContentPolicy/EMBED")))))

    (it "resolves HarContentPolicy/OMIT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarContentPolicy/OMIT"))))))

  (describe "HarNotFound enum"
    (it "resolves HarNotFound/ABORT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarNotFound/ABORT")))))

    (it "resolves HarNotFound/FALLBACK"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "HarNotFound/FALLBACK"))))))

  (describe "RouteFromHarUpdateContentPolicy enum"
    (it "resolves RouteFromHarUpdateContentPolicy/EMBED"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "RouteFromHarUpdateContentPolicy/EMBED")))))

    (it "resolves RouteFromHarUpdateContentPolicy/ATTACH"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "RouteFromHarUpdateContentPolicy/ATTACH"))))))

  (describe "SameSiteAttribute enum"
    (it "resolves SameSiteAttribute/STRICT"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "SameSiteAttribute/STRICT")))))

    (it "resolves SameSiteAttribute/LAX"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "SameSiteAttribute/LAX"))))))

  (describe "ServiceWorkerPolicy enum"
    (it "resolves ServiceWorkerPolicy/ALLOW"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ServiceWorkerPolicy/ALLOW")))))

    (it "resolves ServiceWorkerPolicy/BLOCK"
      (let [ctx (sut/create-sci-ctx)]
        (expect (some? (sut/eval-string ctx "ServiceWorkerPolicy/BLOCK")))))))

(defdescribe sci-require-test
  "Unit tests for full qualified namespace require support in SCI"

  (describe "require com.blockether.spel.core"
    (it "can require and use core namespace"
      (let [ctx (sut/create-sci-ctx)]
        (sut/eval-string ctx "(require '[com.blockether.spel.core :as c])")
        (expect (true? (sut/eval-string ctx "(fn? c/close!)"))))))

  (describe "require com.blockether.spel.page"
    (it "can require and use page namespace"
      (let [ctx (sut/create-sci-ctx)]
        (sut/eval-string ctx "(require '[com.blockether.spel.page :as p])")
        (expect (true? (sut/eval-string ctx "(fn? p/navigate)"))))))

  (describe "require com.blockether.spel.locator"
    (it "can require and use locator namespace"
      (let [ctx (sut/create-sci-ctx)]
        (sut/eval-string ctx "(require '[com.blockether.spel.locator :as l])")
        (expect (true? (sut/eval-string ctx "(fn? l/click)"))))))

  (describe "require com.blockether.spel.assertions"
    (it "can require and use assertions namespace"
      (let [ctx (sut/create-sci-ctx)]
        (sut/eval-string ctx "(require '[com.blockether.spel.assertions :as a])")
        (expect (true? (sut/eval-string ctx "(fn? a/assert-that)")))))))

(defdescribe sci-error-handling-test
  "Unit tests for SCI error handling"

  (describe "invalid code"
    (it "throws on syntax errors"
      (let [ctx (sut/create-sci-ctx)]
        (expect (try
                  (sut/eval-string ctx "(+ 1")
                  false
                  (catch Exception _ true)))))

    (it "throws on undefined symbols"
      (let [ctx (sut/create-sci-ctx)]
        (expect (try
                  (sut/eval-string ctx "undefined-symbol-xyz")
                  false
                  (catch Exception _ true)))))))

;; =============================================================================
;; Integration Tests — SCI-driven Playwright session
;; =============================================================================

(defdescribe sci-playwright-integration-test
  "Integration tests driving Playwright through SCI eval"

  (describe "lifecycle and navigation"
    (it "can start, navigate, query, and stop via SCI"
      ;; Reset SCI atoms in case a previous test (e.g. daemon sci_eval) left them dirty
      (reset! sut/!pw nil) (reset! sut/!browser nil)
      (reset! sut/!context nil) (reset! sut/!page nil)
      (reset! sut/!daemon-mode? false)
      (let [ctx (sut/create-sci-ctx)]
        (try
          ;; Start browser
          (expect (= :started (sut/eval-string ctx "(spel/start!)")))

          ;; Navigate
          (sut/eval-string ctx "(spel/goto \"https://example.com\")")

          ;; Query page info
          (let [title (sut/eval-string ctx "(spel/title)")
                url   (sut/eval-string ctx "(spel/url)")]
            (expect (= "Example Domain" title))
            (expect (.contains ^String url "example.com")))

          ;; Get snapshot
          (let [snap (sut/eval-string ctx "(spel/snapshot)")]
            (expect (map? snap))
            (expect (contains? snap :tree))
            (expect (contains? snap :refs))
            (expect (contains? snap :counter)))

          ;; Get info
          (let [info (sut/eval-string ctx "(spel/info)")]
            (expect (map? info))
            (expect (contains? info :url))
            (expect (contains? info :title))
            (expect (contains? info :viewport)))

          (finally
            (sut/eval-string ctx "(spel/stop!)"))))))

  (describe "daemon-mode awareness"
    (it "start! is no-op when page is already set"
      (let [sentinel (Object.)]
        (try
          (reset! sut/!page sentinel)
          (let [result (sut/sci-start!)]
            (expect (= :started result))
            ;; Page should still be our sentinel — not replaced
            (expect (identical? sentinel @sut/!page)))
          (finally
            (reset! sut/!page nil)))))

    (it "stop! nils atoms without closing in daemon mode"
      (try
        (reset! sut/!daemon-mode? true)
        (reset! sut/!pw (Object.))
        (reset! sut/!browser (Object.))
        (reset! sut/!context (Object.))
        (reset! sut/!page (Object.))
        (let [result (sut/sci-stop!)]
          (expect (= :stopped result))
          (expect (nil? @sut/!pw))
          (expect (nil? @sut/!browser))
          (expect (nil? @sut/!context))
          (expect (nil? @sut/!page)))
        (finally
          (reset! sut/!daemon-mode? false)
          (reset! sut/!pw nil)
          (reset! sut/!browser nil)
          (reset! sut/!context nil)
          (reset! sut/!page nil))))))
