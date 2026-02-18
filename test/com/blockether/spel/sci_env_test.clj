(ns com.blockether.spel.sci-env-test
  "Tests for the sci-env namespace.

   Unit tests verify SCI context creation and expression evaluation
   without a browser. Integration tests use SCI eval to drive a
   real Playwright session."
  (:require
   [com.blockether.spel.sci-env :as sut]
   [lazytest.core :refer [defdescribe describe expect it]]))

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
        (expect (true? (sut/eval-string ctx "(fn? annotate/save!)")))))))

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
