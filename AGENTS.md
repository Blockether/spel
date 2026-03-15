# Agent Instructions

## Critical Rules

Browser Automation:
- ALWAYS use `load_skills=["spel"]` for browser tasks. Load skill first: `skill(name="spel")`
- NEVER use `load_skills=["playwright"]` or `load_skills=["dev-browser"]` — disabled in this project

Snapshots:
- ALWAYS use `snapshot -i` (interactive filter) when browsing — shows only clickable/interactive elements
- Use full `snapshot` only when you need to read text content or page structure
- `snapshot -i -c` for compact interactive view (removes bare role-only lines)
- Read the snapshot output BEFORE clicking — never blind eval-js DOM exploration
- Click by ref: `spel click @eXXXX` — never by CSS selector or eval-js
- Skip REKLAMA (ad) products in search results — they appear first and are unrelated to the search query

SCI Bindings:
- NEVER use inline `(fn ...)` lambdas for SCI-exposed functions — ALWAYS use `defn` with a docstring
- Every SCI user-facing function MUST be a named `defn` (e.g. `sci-thread-sleep`, `sci-viewport-size`)
- Lambdas break `gen-docs` introspection, hide functions from FULL_API.md, and make debugging impossible
- This applies to: `:bindings` map values, `make-ns-map` entries, `core/` namespace stubs

Daemon Session Isolation:
- ALWAYS use a named session when starting a daemon during development/testing — NEVER use the default "default" session
- Generate a unique session name at task start: `SESSION=agent-$(date +%s)`
- Use it on every spel command: `spel --session $SESSION open <url>`, `spel --session $SESSION click ...`
- Teardown at end (or on error): `spel --session $SESSION close` — kills ONLY your session
- NEVER run `spel close` without `--session` — it kills the default session which may belong to the user
- Verify isolation: `spel session list` shows active sessions by name

Paren Repair:
- NEVER fix unbalanced parens/brackets by hand — always: `clj-paren-repair path/to/file.clj`

Linting:
- ALWAYS use `lsp_diagnostics` on changed files — `clj-kondo` CLI is NOT installed
- This is a library — public vars flagged unused are intentional API surface, do NOT remove

Templates:
- NEVER edit scaffolded files in `.opencode/agents/`, `.opencode/skills/`, `.opencode/prompts/`
- ALWAYS edit source templates in `resources/com/blockether/spel/templates/` and regenerate

Versioning:
- Single source of truth: `resources/SPEL_VERSION` (bare semver, no `v` prefix)
- NEVER hardcode versions — read via `(slurp (io/resource "SPEL_VERSION"))` + `str/trim`

API Policy:
- Pre-1.0: break old callers freely, no shims, no deprecation periods

Feature Development Order (Library → SCI → CLI):
- ALWAYS implement new functionality in the **library layer first** (e.g. `page.clj`, `input.clj`, `locator.clj`) as pure Clojure fns wrapping Playwright Java
- Then expose in **SCI** (`sci_env.clj`) — wrap library fns with session-atom convenience (implicit `@!page`, `@!context`) so `eval-sci` users get an interactive, stateful API
- Then wire into **CLI/Daemon** (`daemon.clj` command dispatch + `cli.clj` arg parsing) as JSON commands over the Unix socket
- The library layer is the **source of truth** — SCI and CLI must REUSE library fns, never reimplement logic
- SCI wrappers add interactivity (implicit state, REPL-friendly return values); CLI adds discoverability (help text, flags, JSON output)

Screenshots:
- ALWAYS show screenshots to the user when making visual/UI changes
- After any change to HTML, CSS, or templates: take a screenshot with spel and display it
- Never declare a visual change done without showing proof

## Testing

Every code change MUST include tests. Use `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`.

| Source changed | Test location |
|---|---|
| `sci_env.clj` | `cli_integration_test.clj` → `sci-eval-integration-test` block |
| `daemon.clj` | `cli_integration_test.clj` |
| `cli.clj` | `cli_test.clj` |
| `native.clj` (new CLI command) | `test-cli.sh` — add section with `assert_jq` / `assert_contains` assertions |
| `native.clj` (tool command, e.g. stitch/codegen) | `test-cli.sh` — at minimum `--help` smoke test |
| Everything else | Corresponding `*_test.clj` (e.g. `page.clj` → `page_test.clj`) |

### clojure.test support (alternative to Lazytest)

SPEL also supports standard `clojure.test` with Allure reporting via `allure-ct-reporter`.
Tests live in `test/com/blockether/spel/ct/`. Use `ct-fixture` to bridge Lazytest fixtures
into `clojure.test/use-fixtures`:

```clojure
(ns my-app.test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [com.blockether.spel.allure :as allure]
            [com.blockether.spel.test-fixtures
             :refer [*page* with-playwright with-browser with-traced-page ct-fixture]]))

(use-fixtures :once (ct-fixture with-playwright) (ct-fixture with-browser))
(use-fixtures :each (ct-fixture with-traced-page))

(deftest my-test
  (allure/epic "My Epic")
  (testing "something" (is (= 1 1))))
```

Run with: `clojure -M:test-ct` (activates via `-Dallure.clojure-test.enabled=true`).

### HTML test pages (`test_server.clj`)

Integration tests run against a local HTTP test server (`test/com/blockether/spel/test_server.clj`).
To add a new test page:

1. Define the HTML as a `def ^:private` string in `test_server.clj`
2. Add a route in `make-handler`'s `cond`: `(and (= "GET" method) (= "/my-page" path))`
3. Navigate in tests with `(nav! "/my-page")` or `(page/navigate *page* (str *test-server-url* "/my-page"))`

**Available test server routes:**

| Route | Purpose |
|---|---|
| `/test-page` | General form elements (inputs, checkboxes, dropdowns, buttons) |
| `/keyboard-page` | Keyboard event capture (`keydown` → `#last-key`, `#key-log` divs) |
| `/dialog-page` | Alert, confirm, prompt dialogs |
| `/second-page` | Navigation target (back link to test-page) |
| `/iframe-page` | IFrame container embedding test-page |
| `/redirect-page` | 301 redirect to test-page |
| `/echo` | Echo request back as JSON (method, path, query, body) |
| `/health` | Health check (GET → `{"status":"ok"}`, HEAD → 200) |
| `/status/N` | Return HTTP status code N |
| `/slow` | 2-second delayed response |
| `/scrollable-page` | Nested scrollable containers (overflow:auto, scroll, hidden, visible) |

**Always verify behavior, not just "no error"** — use dedicated HTML pages with observable
DOM state (event listeners, visible output elements) instead of testing return values only.

### CLI bash regression (`test-cli.sh`)
When adding a new CLI command or daemon action:
- Add a new `section "Name (N)"` block in `test-cli.sh` before the SUMMARY section
- Use `assert_jq`, `assert_jq_eq`, `assert_jq_contains` for `--json` output
- Use `assert_contains` for plain text output (e.g. `--help`)
- Register temp files in `TEMP_FILES+=("path")` for cleanup
- Quote the binary: always `"$SPEL"`, never `$SPEL`
- Tool commands (stitch, codegen, init-agents, ci-assemble, merge-reports, show-trace) MUST have at least a `--help` assertion

## Commands (via Makefile)

```bash
make test                    # ALL tests: Clojure (lazytest) + CLI bash regression
make test-cli                # CLI bash regression tests only
make test-cli-clj            # CLI Clojure integration tests only
make format                  # auto-format all source files
make lint                    # clojure-lsp diagnostics --raw
make validate-safe-graal     # check for reflection/boxed-math warnings
make gen-docs                # regenerate references/FULL_API.md from source (run BEFORE install-local)
make install-local           # uberjar → native-image → ~/.local/bin/spel
make init-agents ARGS="--ns com.blockether.spel --force"  # regenerate agent scaffolding (all 8 agents)
clojure -M:test-ct           # clojure.test suite with Allure reporting (test/ct/ directory)
```

Single test namespace / var:
```bash
clojure -M:test -n <ns>                  # e.g. com.blockether.spel.core-test
clojure -M:test -v <ns>/<var>            # MUST be fully-qualified
```

## Agent Scaffolding

`spel init-agents` scaffolds 8 agents across six groups. Use `--only` to scaffold a subset.

```bash
spel init-agents                              # all 8 agents (default)
spel init-agents --only=test                  # test agents only
spel init-agents --only=automation            # browser automation agents only
spel init-agents --only=visual                # visual QA agents only
spel init-agents --only=bugfind              # adversarial bug-finding agents only
spel init-agents --only=orchestrator          # orchestrator agent only
spel init-agents --only=test,visual           # combine groups with commas
```

### Subagent groups

| Group | Agents | Use for |
|-------|--------|---------|
| `test` | spel-test-planner, spel-test-writer | E2E test writing |
| `automation` | spel-explorer, spel-automator | Browser automation |
| `visual` | spel-presenter | Visual content |
| `bugfind` | spel-bug-hunter | Adversarial bug finding |
| `orchestrator` | spel-orchestrator | Smart routing |
| `discovery` | spel-product-analyst | Product feature inventory + coherence audit |

Individual agent names also work as `--only` values: `explorer`, `automator`, `presenter`, `bug-hunter`, `orchestrator`, `product-analyst`.

### `--loop` flag

Valid values: `opencode` (default), `claude`. The `vscode` value is **deprecated** and now exits with an error.

```bash
spel init-agents --loop=opencode   # OpenCode (default)
spel init-agents --loop=claude     # Claude Code
# spel init-agents --loop=vscode   # DEPRECATED — exits with error
```

## Verification

All verification logic lives in `./verify.sh`. Run it instead of a manual checklist.

```bash
./verify.sh              # Full verification (default) — format, lint, graal, gen-docs, build, test, secrets
./verify.sh --allure     # Allure-only — format, lint, test-allure (for report rendering changes)
./verify.sh --quick      # Format + lint only (no build/test)
```

Each step writes logs to `.verification/<step>.log` and exit codes to `.verification/<step>.code`.
On failure the script stops, shows the last 20 lines of the failing step's log, and tells you where to look.

The script auto-detects regeneration triggers (templates, `sci_env.clj`, `gen_docs.clj`) and skips
`gen-docs` / `init-agents` when nothing relevant changed.

### Test count sanity check
`verify.sh --full` validates that the full test suite ran enough cases:
- Lazytest: **~1268 cases** (fails if <1000)
- CLI bash: **~179 assertions** (fails if <150)

After push: verify GitHub Actions CI is green before declaring done.

## Release Process

**Tag-only. The Release workflow handles everything.**

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

Done. The workflow (.github/workflows/release.yml) automatically:
- Builds native binaries (linux-amd64, linux-arm64, macos-arm64, windows-amd64)
- Deploys JAR to Clojars
- Creates GitHub Release with all binaries attached
- Updates CHANGELOG.md with commit log
- Bumps SPEL_VERSION to next patch
- Commits and pushes version files to main

**NEVER manually:**
- Create a release with `gh release create` (conflicts with workflow, GitHub immutable releases break)
- Edit CHANGELOG.md for a release (workflow generates it)
- Bump SPEL_VERSION for a release (workflow does it)
- Upload binaries to a release

## Reproduction Steps (Issue #89: Missing keyboard press API)

### Reproduction

Before commit `f91ea05`, the SCI `spel/press` function only accepted two arguments `(selector key)`.
Attempting a page-level keyboard press threw an arity error:

```clojure
(spel/press "Escape")           ;; => Wrong number of args (1) passed to: sci-press
(spel/keyboard-press "Escape")  ;; => Could not resolve symbol: spel/keyboard-press
(-> (spel/page) (.keyboard) (.press "Escape"))  ;; => No matching method press found taking 1 args
```

### Root cause

`sci-press` was bound with only `([sel key] ...)` and `([sel key opts] ...)` arities — a locator-level
press. There was no page-level keyboard press function at any layer (library, SCI, or CLI).

### Fix (Library -> SCI -> CLI)

1. **Library** (`page.clj`): Added `keyboard-press` wrapping `Page.keyboard().press(key)`.
2. **SCI** (`sci_env.clj`): Added `sci-keyboard-press` (named `defn`, not lambda). Made `sci-press`
   multi-arity: `([key])` delegates to `sci-keyboard-press`, `([sel key])` and `([sel key opts])`
   remain locator-level. Exposed `keyboard-press` binding.
3. **CLI/Daemon** (`daemon.clj`): `handle-cmd "press"` already had the no-selector branch using raw
   interop; no change needed.

### Verification

Tests use a dedicated `/keyboard-page` HTML page with `keydown` listeners that write captured keys to
`#last-key` and `#key-log` divs. Tests verify **actual DOM state** after pressing — not just that the
function returns without error.

## Learnings

### Always use HTML test pages with observable behavior

Tests that only check "no error thrown" (e.g. `(spel/press "Escape") "ok"`) give false confidence.
A function can silently do nothing and the test still passes. Instead:

1. Create a dedicated test HTML page in `test_server.clj` with JavaScript event listeners
2. After performing the action, read DOM state to verify the action had the expected effect
3. Example: keyboard tests use a `keydown` listener that writes `e.key` to `#last-key`,
   then the test reads `#last-key` to confirm the key was actually received

### Follow the three-layer pattern

New features MUST go Library -> SCI -> CLI (see "Feature Development Order" in Critical Rules).
The library layer is the source of truth. SCI and CLI reuse library fns. Never reimplement logic
at a higher layer.

### Named defns for SCI bindings, never lambdas

SCI-exposed functions must be named `defn`s with docstrings. Inline `(fn ...)` lambdas break
`gen-docs` introspection, hide functions from `FULL_API.md`, and make debugging impossible.

### Reproduce before fixing

Always reproduce the issue first to confirm it exists and understand the root cause. Document
the reproduction steps so future developers can verify the fix works against the original failure.

### SCI eval test results are pr-str'd

When testing SCI functions via `cmd "sci_eval"`, string results come back **double-quoted**
because the daemon serializes them with `pr-str`. This is a common gotcha:

```clojure
;; WRONG — will fail:
(let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.title\")"})]
  (expect (= "My Title" (:result r))))
;; (:result r) is actually "\"My Title\"", not "My Title"

;; CORRECT:
(let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.title\")"})]
  (expect (= "\"My Title\"" (:result r))))
```

Daemon `cmd "evaluate"` returns raw values (no quoting). Only `cmd "sci_eval"` wraps strings.

### Playwright evaluate returns Java types, not Clojure types

`page/evaluate` returns Java `ArrayList`, `LinkedHashMap`, etc. — NOT Clojure vectors/maps.
`map?`, `vector?`, `sequential?` all return `false` on these. Use Java interop:

```clojure
;; WRONG:
(when (map? result) (:x result))       ;; map? fails on LinkedHashMap
(when (sequential? result) (mapv ...))  ;; sequential? fails on ArrayList

;; CORRECT:
(when (instance? java.util.Map result)
  (.get ^java.util.Map result "x"))    ;; use .get with string keys
(when (instance? java.util.List result)
  (mapv (fn [^java.util.Map m] ...) result))
```

## Key References

| Resource | Location |
|---|---|
| API reference (agents) | `.opencode/skills/spel/SKILL.md` |
| SKILL source (hand-edited) | `resources/.../templates/skills/spel/SKILL.md` |
| Full API ref (auto-generated) | `resources/.../templates/skills/spel/references/FULL_API.md` |
| Project docs | `README.md` |
| nREPL eval | `clj-nrepl-eval` (eval Clojure against running nREPL) |
| Paren repair | `clj-paren-repair <file>` |
