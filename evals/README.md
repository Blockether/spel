# Agent evals

This folder contains a lightweight evaluation harness for generated spel agents.

The current focus is scaffold quality and discoverability:

- run `spel init-agents --force` in an isolated temp workspace for every eval case
- inspect generated `spel-orchestrator`, workflow prompts, and skill refs
- report which required contracts pass and which advisory discoverability checks are still missing

Why this exists:

- agent regressions are otherwise easy to miss
- we want repeated, deterministic checks after prompt/template changes
- we want explicit visibility into what works today vs what should improve next

Run it with:

```bash
python3 evals/run.py --binary ./target/spel
python3 evals/run.py --binary ./target/spel --case orchestrator-core-opencode --json
```

For real agent-behavior probes (actual `opencode run --agent spel-orchestrator` in a fresh temp workspace):

```bash
python3 evals/run_real.py --binary ./target/spel
python3 evals/run_real.py --binary ./target/spel --case orchestrator-automation-blocked-no-url --json
python3 evals/run_real.py --binary ./target/spel --model opencode/glm-5 --json
```

Notes:

- Every eval case uses a fresh temp directory and still appends `--force` to `init-agents`.
- Required failures make the run fail.
- Advisory failures are reported but do not fail the run unless `--strict-advisory` is used.
- The real eval runner classifies blocked runtime conditions separately (for example, missing model auth causing `opencode run` timeouts).
- Real evals default to `openai/gpt-5.3-codex` unless a case overrides `model`.
- If provider access is reachable but billing is depleted, runs are classified as `blocked_runtime_billing`.
