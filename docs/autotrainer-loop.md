# Autotrainer Loop For spel

This document adapts the core idea from `karpathy/autoresearch` to `spel`,

The goal is not "let the repo mutate itself forever". The goal is a bounded,
reproducible loop that improves:

- agent templates
- workflow prompts
- helper commands
- agent-facing docs

while staying testable, revertible, and cheap enough to run repeatedly.

## Recommendation

Do this in two phases.

1. Train on a deterministic target.
2. Validate on noisy live targets like `onet.pl`.

Do not use `onet.pl` as the primary training target. It is too noisy for a
useful improvement signal because of ads, consent flows, iframe churn, and A/B
variation.

## What Maps From autoresearch (Karpathy's pattern)

`autoresearch` works because it has three clean layers:

1. fixed evaluation harness
2. one editable artifact
3. a keep-or-revert rule

For `spel`, the adapted layers should be:

1. fixed evaluation harness
   - `./verify.sh --quick`
   - targeted integration tests
   - deterministic exploration target
2. editable artifacts
   - `resources/com/blockether/spel/templates/agents/*.md`
   - `resources/com/blockether/spel/templates/prompts/*.md`
   - `src/com/blockether/spel/helpers.clj`
   - `src/com/blockether/spel/cli.clj`
   - `src/com/blockether/spel/daemon.clj`
   - `src/com/blockether/spel/sci_env.clj`
3. keep-or-revert rule
   - keep only if evaluation improves or stays correct with a simpler design
   - revert if tests fail, artifacts regress, or instructions become less clear

## Hard Constraints

- Max `50` iterations total.
- Start with `10` iterations max until the loop is stable.
- One meaningful mutation per iteration.
- Never mutate more than one concern at once.
- Never train on a live site first.
- Never require a native rebuild for prompt-only iterations.

## Training Targets

### Phase A: deterministic training targets

Use one of these first:

- a local static fixture site under `test-e2e/`
- a tiny known test site with stable structure
- a repo-owned fixture site with fixed depth-2 navigation

The target should have known answers:

- expected page count
- expected landmark count
- expected route count
- expected forms/buttons/links
- expected screenshots/artifacts

### Phase B: live validation targets

Only after the loop works on deterministic fixtures:

- `onet.pl` depth 2
- another noisy real-world site

Live sites are validation-only, not the optimization objective.

## Mutation Strategy

### Level 1: scaffolded-output mutation first

Fastest path:

- mutate `.opencode/agents/*.md`
- mutate `.opencode/prompts/*.md`

Why:

- no native rebuild required
- very fast iteration cycle
- easy to compare before/after behavior

Tradeoff:

- changes do not persist to source templates automatically

### Level 2: source-template mutation second

Once a change proves useful in scaffolded output, promote it into:

- `resources/com/blockether/spel/templates/agents/*.md`
- `resources/com/blockether/spel/templates/prompts/*.md`

Then regenerate and rebuild only when needed.

### Level 3: helper/source mutation last

Only change Clojure sources when repeated runs show a real missing primitive or
bad helper API.

This includes:

- `src/com/blockether/spel/helpers.clj`
- `src/com/blockether/spel/cli.clj`
- `src/com/blockether/spel/daemon.clj`
- `src/com/blockether/spel/sci_env.clj`

These iterations are expensive because they trigger verification and often a
native rebuild.

## Evaluation Signal

The loop needs a scorecard. Use a structured per-iteration report with these
fields:

- `iteration`
- `target`
- `depth`
- `model`
- `changed_files`
- `artifacts_expected`
- `artifacts_produced`
- `artifact_completeness_score`
- `verify_quick_passed`
- `targeted_tests_passed`
- `pages_explored`
- `routes_found`
- `sections_found`
- `console_issue_count`
- `network_failure_count`
- `learnings_count`
- `decision` (`keep` or `revert`)
- `summary`

Do not optimize on screenshot pixels.

Do not optimize on raw element counts from noisy sites.

## Suggested Loop

Each iteration should look like this:

1. create a new run directory under `.sisyphus/autotrainer/`
2. choose exactly one hypothesis
3. apply one bounded change
4. scaffold or rebuild only if needed
5. run the orchestrator against the target
6. collect artifacts and learnings
7. run verification
8. score the iteration
9. keep or revert
10. record a concise learning

## Hypothesis Examples

Good hypotheses:

- "The explorer prompt is too verbose; shorten the action loop and refs will be used more reliably."
- "The QA orchestrator should call `overview --all` before visual judgment on iframe-heavy pages."
- "The automation prompt should prefer `routes` before deeper crawling."
- "A `debug` helper reduces dead-end diagnosis steps."

Bad hypotheses:

- "Improve all templates."
- "Make agents smarter somehow."
- "Rewrite orchestrator stack."

## Rebuild Policy

Use smart rebuilds instead of rebuilding every round.

### No rebuild needed

- `.opencode/agents/*`
- `.opencode/prompts/*`

### Re-scaffold needed

- `resources/com/blockether/spel/templates/agents/*`
- `resources/com/blockether/spel/templates/prompts/*`

Run:

```bash
./target/spel init-agents --simplified --force
```

### Native rebuild needed

- `src/com/blockether/spel/*.clj`
- doc generation changes that affect embedded assets

Run:

```bash
make gen-docs
make install-local
```

## Model Recommendation

Using GLM-5 is reasonable if it is available in your OpenCode environment, but
it should be used as the mutation proposer, not the final judge.

The judge should be deterministic:

- artifact checks
- test results
- verification status
- simple scalar metrics

LLM-as-judge is fine for narrative synthesis, but not for pass/fail.

## Proposed First Slice

Implement the loop in this order:

### Slice 1

Create a bounded, manual-supervised loop that:

- runs against a deterministic target
- mutates scaffolded output only
- records per-iteration JSON under `.sisyphus/autotrainer/`
- uses `spel-orchestrator` as the subject under test
- uses `./verify.sh --quick` plus targeted checks

The repo now includes a baseline bootstrapper for the deterministic first step:

```bash
make autotrainer-example-org
```

This creates a run directory under `.sisyphus/autotrainer/example-org/` and
captures baseline helper artifacts plus `iteration-000-baseline.json`.

It also does the refresh step first:

1. `make install-local`
2. `spel init-agents --simplified --force --learnings --no-tests`

That guarantees the next OpenCode run sees freshly rebuilt code and freshly
scaffolded agent files.

For live validation with supervised OpenCode execution against `onet.pl`:

```bash
make autotrainer-onet
```

That run still captures the deterministic `example.org` baseline first, then it
launches a supervised OpenCode validation step against `https://onet.pl` with:

- agent: `spel-orchestrator`
- model: `zai-coding-plan/glm-5`
- depth: `2`
- hard cap recorded as `50` iterations
- OpenCode timeout: `900` seconds by default (`--opencode-timeout-sec`)

The validation step writes:

- command: `.sisyphus/autotrainer/.../logs/opencode-command.txt`
- raw events: `.sisyphus/autotrainer/.../logs/opencode-run.jsonl`
- parsed summary: `.sisyphus/autotrainer/.../logs/opencode-summary.json`
- manifest: `.sisyphus/autotrainer/.../validation-001.json`

This is intentional supervision: if OpenCode fails to actually run, the harness
records the failure in machine-readable form instead of pretending the agent did
work.

### Slice 2

Add source-template promotion:

- when a scaffolded change works in 2 to 3 consecutive iterations
- copy it back into `resources/.../templates/`
- re-run `init-agents`

### Slice 3

Add helper optimization:

- promote repeated prompt pain points into helper changes
- run full verification
- rebuild/install local binary only on these rounds

### Slice 4

Add live-site validation:

- run on `onet.pl` depth 2
- do not use the result as the optimization target
- use it as a stress test for generalization

## Keep/Revert Rule

Keep a change only if all of these are true:

1. required artifacts still exist
2. targeted verification passes
3. the run is at least as complete as the previous baseline
4. instructions/helpers are simpler or more reliable

Otherwise revert.

## Example Iteration Manifest

```json
{
  "iteration": 3,
  "model": "glm-5",
  "target": "local-fixture-site",
  "depth": 2,
  "mutation_scope": "scaffolded-output",
  "changed_files": [
    ".opencode/agents/spel-auto-orchestrator.md"
  ],
  "artifacts_expected": [
    "orchestration/automation-pipeline.json",
    "LEARNINGS.md"
  ],
  "artifacts_produced": [
    "orchestration/automation-pipeline.json",
    "LEARNINGS.md"
  ],
  "artifact_completeness_score": 1.0,
  "verify_quick_passed": true,
  "targeted_tests_passed": true,
  "pages_explored": 5,
  "routes_found": 18,
  "sections_found": 7,
  "console_issue_count": 0,
  "network_failure_count": 0,
  "learnings_count": 2,
  "decision": "keep",
  "summary": "Shorter exploration loop improved route coverage without breaking artifacts."
}
```

## Bottom Line

Yes, this can work here.

But the right adaptation is:

- helper-first
- deterministic-first
- scaffolded-first
- source-template-second
- native-rebuild-last
- live-site-validation-last

If we do that, a 50-iteration cap is reasonable.

If we start with direct source mutation + native rebuild + `onet.pl` as the
training target, the loop will be slow, noisy, and hard to trust.
