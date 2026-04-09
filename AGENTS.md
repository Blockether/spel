# Agent Instructions

## Critical Rules

Browser Automation:
- ALWAYS `load_skills=["spel"]` for browser tasks. `skill(name="spel")` first
- NEVER `load_skills=["playwright"]` or `load_skills=["dev-browser"]` — disabled

Snapshots:
- ALWAYS `snapshot -i` when browsing — clickable elements only
- Full `snapshot` only when need text/structure
- `snapshot -i -c` → compact interactive (drops bare role lines)
- Read snapshot BEFORE clicking — no blind eval-js
- Click by ref: `spel click @eXXXX` — no CSS selectors or eval-js
- Skip REKLAMA in search results — ads, unrelated

SCI Bindings:
- NEVER inline `(fn ...)` lambdas for SCI fns — ALWAYS `defn` + docstring
- Every SCI fn = named `defn` (e.g. `sci-thread-sleep`, `sci-viewport-size`)
- Lambdas → break `gen-docs` → hidden from `FULL_API.md` → undebuggable
- Applies: `:bindings` map values, `make-ns-map` entries, `core/` stubs

Daemon Session Isolation:
- ALWAYS named session in dev/test — NEVER default "default"
- Generate: `SESSION=agent-$(date +%s)`
- Use on every cmd: `spel --session $SESSION open <url>`
- Teardown: `spel --session $SESSION close` — kills ONLY your session
- NEVER `spel close` without `--session` — kills user's default
- Verify: `spel session list`

Paren Repair:
- NEVER fix unbalanced parens by hand — `clj-paren-repair path/to/file.clj`

Linting:
- ALWAYS `lsp_diagnostics` on changed files — `clj-kondo` CLI NOT installed
- Library — unused public vars = intentional API surface, do NOT remove

Templates:
- NEVER edit `.opencode/agents/`, `.opencode/skills/`, `.opencode/prompts/`
- ALWAYS edit `resources/com/blockether/spel/templates/` → regenerate

Versioning:
- Single truth: `resources/SPEL_VERSION` (bare semver, no `v` prefix)
- NEVER hardcode — `(slurp (io/resource "SPEL_VERSION"))` + `str/trim`

API Policy:
- Pre-1.0: break callers freely, no shims, no deprecation

Feature Dev Order (Library → SCI → CLI):
- ALWAYS **library first** (`page.clj`, `input.clj`, `locator.clj`) — pure Clojure fns wrapping Playwright Java
- Then **SCI** (`sci_env.clj`) — session-atom convenience (implicit `@!page`, `@!context`) → interactive API
- Then **CLI/Daemon** (`daemon.clj` dispatch + `cli.clj` args) — JSON over Unix socket
- Library = **truth** — SCI + CLI REUSE library fns, never reimplement
- SCI → interactivity; CLI → discoverability (help, flags, JSON)

Screenshots:
- ALWAYS show screenshots for visual/UI changes
- HTML/CSS/template change → screenshot → display
- No visual change done without proof

## Testing

Every change = tests. `defdescribe`/`describe`/`it`/`expect` from `com.blockether.spel.allure`.

| Source | Test |
|---|---|
| `sci_env.clj` | `cli_integration_test.clj` → `sci-eval-integration-test` |
| `daemon.clj` | `cli_integration_test.clj` |
| `cli.clj` | `cli_test.clj` |
| `native.clj` (new CLI cmd) | `test-cli.sh` — `assert_jq` / `assert_contains` |
| `native.clj` (tool cmd) | `test-cli.sh` — minimum `--help` |
| Other | `*_test.clj` (e.g. `page.clj` → `page_test.clj`) |

### clojure.test (alt to Lazytest)

`clojure.test` + Allure via `allure-reporter`. Tests in `test/com/blockether/spel/ct/`.

```clojure
(ns my-app.test
  (:require [clojure.test :refer [deftest testing is]]
            [com.blockether.spel.allure :as allure]
            [com.blockether.spel.core :as core]))

(deftest my-test
  (allure/epic "My Epic")
  (testing "something"
    (core/with-testing-page [pg]
      (is (= 1 1)))))
```

Run: `clojure -M:test-ct`

### HTML test pages (`test_server.clj`)

Local HTTP test server at `test/com/blockether/spel/test_server.clj`.
New page:

1. `def ^:private` HTML string in `test_server.clj`
2. Route in `make-handler` cond: `(and (= "GET" method) (= "/my-page" path))`
3. Navigate: `(nav! "/my-page")` (Lazytest) or `(page/navigate pg (str ts/*test-server-url* "/my-page"))` (clojure.test)

| Route | Purpose |
|---|---|
| `/test-page` | Form elements |
| `/keyboard-page` | Keyboard events (`keydown` → `#last-key`, `#key-log`) |
| `/dialog-page` | Alert/confirm/prompt dialogs |
| `/second-page` | Nav target (back link) |
| `/iframe-page` | IFrame embedding test-page |
| `/redirect-page` | 301 → test-page |
| `/echo` | Echo req as JSON |
| `/health` | GET → `{"status":"ok"}`, HEAD → 200 |
| `/status/N` | HTTP status N |
| `/slow` | 2s delay |
| `/scrollable-page` | Scrollable containers (overflow:auto/scroll/hidden/visible) |

**Verify behavior, not "no error"** — HTML pages with observable DOM state. Never test return values only.

### CLI bash (`test-cli.sh`)
New CLI cmd/daemon action → new section:
- `section "Name (N)"` before SUMMARY
- `assert_jq`/`assert_jq_eq`/`assert_jq_contains` for `--json`
- `assert_contains` for text (e.g. `--help`)
- `TEMP_FILES+=("path")` for cleanup
- `"$SPEL"` not `$SPEL`
- Tool cmds (stitch, codegen, init-agents, ci-assemble, merge-reports, show-trace) MUST have `--help`

## Commands

```bash
make test                    # ALL: lazytest + CLI bash
make test-cli                # CLI bash only
make test-cli-clj            # CLI Clojure integration only
make format                  # auto-format
make lint                    # clojure-lsp --raw
make validate-safe-graal     # reflection/boxed-math check
make gen-docs                # regen FULL_API.md (before install-local)
make install-local           # uberjar → native-image → ~/.local/bin/spel
make init-agents ARGS="--ns com.blockether.spel --force"  # regen 8 agents
clojure -M:test-ct           # clojure.test + Allure (test/ct/)
```

Single ns/var:
```bash
clojure -M:test -n <ns>      # e.g. com.blockether.spel.core-test
clojure -M:test -v <ns>/<var>  # MUST be fully-qualified
```

## Agent Scaffolding

`spel init-agents` → 8 agents, 6 groups. `--only` for subset.

```bash
spel init-agents                              # all 8
spel init-agents --only=test                  # test agents
spel init-agents --only=automation            # browser automation
spel init-agents --only=visual                # visual QA
spel init-agents --only=bugfind               # bug-finding
spel init-agents --only=orchestrator          # orchestrator
spel init-agents --only=test,visual           # combine
```

| Group | Agents | Use for |
|-------|--------|---------|
| `test` | spel-test-planner, spel-test-writer | E2E tests |
| `automation` | spel-explorer, spel-automator | Browser automation |
| `visual` | spel-presenter | Visual content |
| `bugfind` | spel-bug-hunter | Bug-finding |
| `orchestrator` | spel-orchestrator | Smart routing |
| `discovery` | spel-product-analyst | Product inventory + coherence audit |

Individual names as `--only`: `explorer`, `automator`, `presenter`, `bug-hunter`, `orchestrator`, `product-analyst`.

### `--loop`

Valid: `opencode` (default), `claude`. `vscode` **deprecated** → error.

```bash
spel init-agents --loop=opencode   # default
spel init-agents --loop=claude     # Claude Code
# spel init-agents --loop=vscode   # DEPRECATED → error
```

## Verification

`./verify.sh`. Not manual checklist.

```bash
./verify.sh              # Full — format, lint, graal, gen-docs, build, test, secrets
./verify.sh --allure     # Allure — format, lint, test-allure
./verify.sh --quick      # Format + lint only
```

Logs → `.verification/<step>.log`, codes → `.verification/<step>.code`.
Fail → stops, shows 20 lines of failing log.

Auto-detects regen triggers (templates, `sci_env.clj`, `gen_docs.clj`) → skips when nothing changed.

### Test count sanity
`verify.sh --full` validates:
- Lazytest: **~1268** (fails if <1000)
- CLI bash: **~179** (fails if <150)

Post-push: verify CI green.

## Release

**Tag-only. Workflow handles rest.**

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

Workflow (`.github/workflows/release.yml`):
- Build natives (linux-amd64/arm64, macos-arm64, windows-amd64)
- Deploy JAR → Clojars
- GitHub Release + binaries
- CHANGELOG.md from commit log
- Bump SPEL_VERSION → next patch
- Commit + push to main

**NEVER manually:**
- `gh release create` (conflicts, immutable breaks)
- Edit CHANGELOG.md for release
- Bump SPEL_VERSION for release
- Upload binaries

## Issue #89: Missing keyboard press API

### Repro

Before `f91ea05`, `spel/press` only accepted `(selector key)`. Page-level press → arity error:

```clojure
(spel/press "Escape")           ;; => Wrong number of args (1) passed to: sci-press
(spel/keyboard-press "Escape")  ;; => Could not resolve symbol: spel/keyboard-press
(-> (spel/page) (.keyboard) (.press "Escape"))  ;; => No matching method press found taking 1 args
```

### Root cause

`sci-press` only `([sel key] ...)` + `([sel key opts] ...)` — locator-only. No page-level press at any layer.

### Fix (Library → SCI → CLI)

1. **Library** (`page.clj`): `keyboard-press` wraps `Page.keyboard().press(key)`
2. **SCI** (`sci_env.clj`): `sci-keyboard-press` (named `defn`). `sci-press` multi-arity: `([key])` → `sci-keyboard-press`, `([sel key])`/`([sel key opts])` stay locator-level. `keyboard-press` binding exposed.
3. **CLI/Daemon** (`daemon.clj`): `handle-cmd "press"` already had no-selector branch. No change.

### Verify

Tests use `/keyboard-page` + `keydown` listeners → `#last-key` + `#key-log`. Verify **DOM state** after press — not "no error".

## Learnings

### Observable HTML test pages

"No error" tests = false confidence. Fn silently does nothing → passes. Instead:

1. Dedicated HTML page in `test_server.clj` + JS listeners
2. Action → read DOM → verify effect
3. Example: `keydown` listener → `e.key` → `#last-key` → test reads `#last-key`

### Three-layer pattern

Features → Library → SCI → CLI. Library = truth. SCI + CLI reuse. Never reimplement at higher layer.

### Named defns for SCI

SCI fns = named `defn` + docstring. Lambdas → break `gen-docs` → hidden from `FULL_API.md` → undebuggable.

### Reproduce before fix

Reproduce → confirm → root cause. Document steps → verify fix against original failure.

### SCI eval = pr-str'd strings

`cmd "sci_eval"` string results = **double-quoted** (daemon `pr-str`). Gotcha:

```clojure
;; WRONG:
(let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.title\")"})]
  (expect (= "My Title" (:result r))))
;; (:result r) = "\"My Title\"", not "My Title"

;; CORRECT:
(let [r (cmd "sci_eval" {"code" "(spel/evaluate \"document.title\")"})]
  (expect (= "\"My Title\"" (:result r))))
```

`cmd "evaluate"` = raw. Only `cmd "sci_eval"` wraps.

### Playwright evaluate → Java types

`page/evaluate` → `ArrayList`, `LinkedHashMap`. `map?`/`vector?`/`sequential?` all `false`. Java interop:

```clojure
;; WRONG:
(when (map? result) (:x result))
(when (sequential? result) (mapv ...))

;; CORRECT:
(when (instance? java.util.Map result)
  (.get ^java.util.Map result "x"))
(when (instance? java.util.List result)
  (mapv (fn [^java.util.Map m] ...) result))
```

## Key References

| Resource | Location |
|---|---|
| API ref (agents) | `.opencode/skills/spel/SKILL.md` |
| SKILL source | `resources/.../templates/skills/spel/SKILL.md` |
| Full API (auto-gen) | `resources/.../templates/skills/spel/references/FULL_API.md` |
| Project docs | `README.md` |
| nREPL eval | `clj-nrepl-eval` |
| Paren repair | `clj-paren-repair <file>` |
