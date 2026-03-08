(ns com.blockether.spel.autotrainer-test
  (:require
   [com.blockether.spel.autotrainer :as sut]
   [com.blockether.spel.allure :refer [defdescribe expect it]]))

(defdescribe target-slug-test
  "Unit tests for target->slug"

  (it "removes scheme and path"
    (expect (= "example-org" (sut/target->slug "https://example.org/path?q=1"))))

  (it "normalizes punctuation to dashes"
    (expect (= "app-example-org" (sut/target->slug "app.example.org"))))

  (it "falls back to target for blank input"
    (expect (= "target" (sut/target->slug "   ")))))

(defdescribe parse-args-test
  "Unit tests for autotrainer CLI arg parsing"

  (it "uses default values"
    (let [opts (sut/parse-args [])]
      (expect (= "https://example.org" (:target opts)))
      (expect (= "https://onet.pl" (:validation-target opts)))
      (expect (= 1 (:depth opts)))
      (expect (= 50 (:max-iterations opts)))
      (expect (= "glm-5" (:model opts)))
      (expect (= "zai-coding-plan/glm-5" (:opencode-model opts)))
      (expect (= 900 (:opencode-timeout-sec opts)))
      (expect (= ".sisyphus/autotrainer" (:run-root opts)))
      (expect (= false (:refresh opts)))
      (expect (= false (:validate opts)))
      (expect (= true (:capture opts)))
      (expect (= 3 (:convergence-window opts)))
      (expect (= 5000 (:preflight-timeout-ms opts)))
      (expect (= false (:loop opts)))))

  (it "parses explicit values"
    (let [opts (sut/parse-args ["--target" "https://example.com"
                                "--validation-target" "https://onet.pl/"
                                "--depth" "2"
                                "--max-iterations" "12"
                                "--model" "glm-5-max"
                                "--opencode-model" "zai-coding-plan/glm-5"
                                "--opencode-timeout-sec" "123"
                                "--run-root" "tmp/runs"
                                "--convergence-window" "5"
                                "--preflight-timeout-ms" "3000"
                                "--refresh"
                                "--validate"
                                "--loop"
                                "--no-capture"])]
      (expect (= "https://example.com" (:target opts)))
      (expect (= "https://onet.pl/" (:validation-target opts)))
      (expect (= 2 (:depth opts)))
      (expect (= 12 (:max-iterations opts)))
      (expect (= "glm-5-max" (:model opts)))
      (expect (= "zai-coding-plan/glm-5" (:opencode-model opts)))
      (expect (= 123 (:opencode-timeout-sec opts)))
      (expect (= "tmp/runs" (:run-root opts)))
      (expect (= true (:refresh opts)))
      (expect (= true (:validate opts)))
      (expect (= false (:capture opts)))
      (expect (= 5 (:convergence-window opts)))
      (expect (= 3000 (:preflight-timeout-ms opts)))
      (expect (= true (:loop opts)))))

  (it "parses equals syntax"
    (let [opts (sut/parse-args ["--target=https://example.net" "--depth=3" "--model=glm" "--run-root=tmp/out"])]
      (expect (= "https://example.net" (:target opts)))
      (expect (= 3 (:depth opts)))
      (expect (= "glm" (:model opts)))
      (expect (= "tmp/out" (:run-root opts))))))

(defdescribe artifact-paths-test
  "Unit tests for run artifact planning"

  (it "builds expected artifact paths"
    (let [paths (sut/artifact-paths ".sisyphus/autotrainer/example-org/run-1")]
      (expect (= ".sisyphus/autotrainer/example-org/run-1/artifacts/audit.json" (:audit-json paths)))
      (expect (= ".sisyphus/autotrainer/example-org/run-1/artifacts/overview.png" (:overview-png paths)))
      (expect (= ".sisyphus/autotrainer/example-org/run-1/logs/refresh-error.log" (:refresh-error-log paths)))
      (expect (= ".sisyphus/autotrainer/example-org/run-1/logs/opencode-run.jsonl" (:opencode-jsonl paths)))
      (expect (= ".sisyphus/autotrainer/example-org/run-1/validation-001.json" (:validation-manifest-json paths)))
      (expect (= ".sisyphus/autotrainer/example-org/run-1/iteration-000-baseline.json" (:manifest-json paths))))))

(defdescribe validation-prompt-test
  "Unit tests for live validation prompt generation"

  (it "mentions onet target and supervision cap"
    (let [prompt (sut/validation-prompt {:validation-target "https://onet.pl"
                                         :depth 2
                                         :max-iterations 50})]
      (expect (.contains ^String prompt "https://onet.pl"))
      (expect (.contains ^String prompt "depth 2"))
      (expect (.contains ^String prompt "at most 50"))
      (expect (.contains ^String prompt "LEARNINGS.md")))))

(defdescribe manifest-test
  "Unit tests for baseline iteration manifest"

  (it "computes completeness from produced artifacts"
    (let [artifacts {:audit-json "/definitely/missing/audit.json"
                     :routes-json "/definitely/missing/routes.json"
                     :inspect-json "/definitely/missing/inspect.json"
                     :debug-json "/definitely/missing/debug.json"
                     :overview-json "/definitely/missing/overview.json"
                     :overview-png "/definitely/missing/overview.png"}
          manifest (sut/iteration-manifest {:target "https://example.org"
                                            :depth 1
                                            :model "glm-5"
                                            :refresh true}
                     artifacts
                     {:routes-found 3
                      :sections-found 4
                      :console-issue-count 0
                      :network-failure-count 1})]
      (expect (= 0 (:iteration manifest)))
      (expect (= "https://example.org" (:target manifest)))
      (expect (= 3 (:routes-found manifest)))
      (expect (= 4 (:sections-found manifest)))
      (expect (= true (:refresh-performed manifest)))
      (expect (= ["init-agents" "--simplified" "--force" "--learnings" "--no-tests"]
                (:init-agents-args manifest)))
      (expect (= 0.0 (:artifact-completeness-score manifest)))
      (expect (= "baseline" (:decision manifest))))))

(defdescribe preflight-check-test
  "Unit tests for pre-flight HTTP health check"

  (it "succeeds on reachable URL"
    (let [result (sut/preflight-check! "https://example.org" 5000)]
      (expect (= true (:reachable result)))
      (expect (contains? result :status))
      (expect (contains? result :latency-ms))
      (expect (= "https://example.org" (:url result)))))

  (it "throws on unreachable host"
    (let [threw? (atom false)]
      (try
        (sut/preflight-check! "https://definitely-not-a-real-host-12345.invalid" 2000)
        (catch clojure.lang.ExceptionInfo e
          (reset! threw? true)
          (expect (= false (:reachable (ex-data e))))
          (expect (contains? (ex-data e) :error))))
      (expect (= true @threw?)))))

(defdescribe compare-manifests-test
  "Unit tests for manifest comparison / feedback delta"

  (it "detects improvement when artifacts increase"
    (let [prev {:artifact-completeness-score 0.5
                :artifacts-produced ["a.json" "b.json"]
                :routes-found 5
                :sections-found 3
                :console-issue-count 2
                :network-failure-count 1
                :iteration 0}
          curr {:artifact-completeness-score 0.8
                :artifacts-produced ["a.json" "b.json" "c.json"]
                :routes-found 8
                :sections-found 5
                :console-issue-count 1
                :network-failure-count 0
                :iteration 1}
          delta (sut/compare-manifests prev curr)]
      (expect (= true (:improved delta)))
      (expect (= false (:regressed delta)))
      (expect (= false (:stable delta)))
      (expect (> (:completeness-delta delta) 0.0))
      (expect (= 1 (:artifact-delta delta)))
      (expect (= 3 (:routes-delta delta)))
      (expect (= 2 (:sections-delta delta)))
      (expect (= -1 (:console-issue-delta delta)))
      (expect (= -1 (:network-failure-delta delta)))))

  (it "detects regression when artifacts decrease"
    (let [prev {:artifact-completeness-score 1.0
                :artifacts-produced ["a.json" "b.json" "c.json"]
                :routes-found 10
                :sections-found 5
                :console-issue-count 0
                :network-failure-count 0
                :iteration 1}
          curr {:artifact-completeness-score 0.5
                :artifacts-produced ["a.json"]
                :routes-found 10
                :sections-found 5
                :console-issue-count 0
                :network-failure-count 0
                :iteration 2}
          delta (sut/compare-manifests prev curr)]
      (expect (= false (:improved delta)))
      (expect (= true (:regressed delta)))
      (expect (= false (:stable delta)))))

  (it "detects regression when errors increase"
    (let [prev {:artifact-completeness-score 1.0
                :artifacts-produced ["a.json"]
                :routes-found 5
                :sections-found 3
                :console-issue-count 0
                :network-failure-count 0
                :iteration 1}
          curr {:artifact-completeness-score 1.0
                :artifacts-produced ["a.json"]
                :routes-found 5
                :sections-found 3
                :console-issue-count 3
                :network-failure-count 0
                :iteration 2}
          delta (sut/compare-manifests prev curr)]
      (expect (= false (:improved delta)))
      (expect (= true (:regressed delta)))))

  (it "detects stable when nothing changes"
    (let [manifest {:artifact-completeness-score 1.0
                    :artifacts-produced ["a.json" "b.json"]
                    :routes-found 10
                    :sections-found 5
                    :console-issue-count 0
                    :network-failure-count 0
                    :iteration 1}
          delta (sut/compare-manifests manifest manifest)]
      (expect (= false (:improved delta)))
      (expect (= false (:regressed delta)))
      (expect (= true (:stable delta))))))

(defdescribe keep-or-revert-decision-test
  "Unit tests for keep/revert decision logic"

  (it "returns :keep on improvement"
    (expect (= :keep (sut/keep-or-revert-decision {:improved true :regressed false :stable false}))))

  (it "returns :revert on regression"
    (expect (= :revert (sut/keep-or-revert-decision {:improved false :regressed true :stable false}))))

  (it "returns :keep-stable on stable"
    (expect (= :keep-stable (sut/keep-or-revert-decision {:improved false :regressed false :stable true}))))

  (it "prefers :revert when both improved and regressed (should not happen but defensive)"
    (expect (= :revert (sut/keep-or-revert-decision {:improved true :regressed true :stable false})))))

(defdescribe converged-test
  "Unit tests for convergence detection"

  (it "returns false with empty history"
    (expect (= false (sut/converged? [] 3))))

  (it "returns false with insufficient history"
    (expect (= false (sut/converged? [{:improved false :stable true}] 3))))

  (it "returns false when recent iterations show improvement"
    (expect (= false (sut/converged? [{:improved true}
                                       {:improved false}
                                       {:improved false}] 3))))

  (it "returns true when window iterations show no improvement"
    (expect (= true (sut/converged? [{:improved false}
                                      {:improved false}
                                      {:improved false}] 3))))

  (it "considers only the last window entries"
    (expect (= true (sut/converged? [{:improved true}
                                      {:improved false}
                                      {:improved false}
                                      {:improved false}] 3))))

  (it "returns false if any in window improved"
    (expect (= false (sut/converged? [{:improved false}
                                       {:improved true}
                                       {:improved false}] 3)))))

(defdescribe parse-args-loop-test
  "Unit tests for --loop and new CLI flags"

  (it "parses --loop flag"
    (let [opts (sut/parse-args ["--loop"])]
      (expect (= true (:loop opts)))))

  (it "parses --convergence-window"
    (let [opts (sut/parse-args ["--convergence-window" "7"])]
      (expect (= 7 (:convergence-window opts)))))

  (it "parses --convergence-window= syntax"
    (let [opts (sut/parse-args ["--convergence-window=5"])]
      (expect (= 5 (:convergence-window opts)))))

  (it "parses --preflight-timeout-ms"
    (let [opts (sut/parse-args ["--preflight-timeout-ms" "3000"])]
      (expect (= 3000 (:preflight-timeout-ms opts)))))

  (it "parses --preflight-timeout-ms= syntax"
    (let [opts (sut/parse-args ["--preflight-timeout-ms=2000"])]
      (expect (= 2000 (:preflight-timeout-ms opts))))))
