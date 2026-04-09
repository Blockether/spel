# GitHub Actions CI/CD Workflows

Reference for three workflows: `.github/workflows/ci.yml`, `.github/workflows/allure.yml`, `.github/workflows/release.yml`.

## 1) CI (`ci.yml`)

Continuous integration for main and PRs. Runs tests on 3 OSes, Linux-only lint/validation + dual test suites with Allure output, then builds and smoke-tests native binaries.

### Trigger

```yaml
on:
  push:
    branches: ["main"]
  pull_request:
    branches: ["main"]
```

### Job structure

| Job | Runs on | Matrix | Notes |
|---|---|---|---|
| `test` | `${{ matrix.os }}` | `ubuntu-latest`, `macos-latest`, `windows-latest` | `fail-fast: false`; artifact names: `spel-dev-linux-amd64`, `spel-dev-macos-arm64`, `spel-dev-windows-amd64` |

### Caches

| Cache step | Path | Key | Restore keys |
|---|---|---|---|
| `Cache Clojure deps` | `~/.m2/repository`, `~/.gitlibs`, `~/.clojure/.cpcache` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` | `deps-${{ runner.os }}-` |
| `Cache Playwright browsers` | `~/.cache/ms-playwright` | `playwright-${{ runner.os }}-1.58.0` | `playwright-${{ runner.os }}-` |

### Step flow (exact names)

| Order | Scope | Step name |
|---:|---|---|
| 1 | all | *(unnamed)* `actions/checkout@v4` |
| 2 | all | `Normalize Playwright browsers path` |
| 3 | all | `Setup GraalVM` |
| 4 | all | *(unnamed)* `DeLaGuardo/setup-clojure@13.5` |
| 5 | Linux | *(unnamed)* `clojure-lsp/setup-clojure-lsp@v1` |
| 6 | all | `Cache Clojure deps` |
| 7 | all | `Cache Playwright browsers` |
| 8 | Linux | `Install Playwright browsers (Linux — with system deps)` |
| 9 | non-Linux | `Install Playwright browsers` |
| 10 | all | `Check Clojure syntax` |
| 11 | Linux | `Lint (clojure-lsp)` |
| 12 | Linux | `Validate GraalVM native-image safety` |
| 13 | Linux | `Clean Allure results` |
| 14 | Linux | `Run tests with Allure reporter (lazytest)` *(continue-on-error)* |
| 15 | Linux | `Run clojure.test suite with Allure reporter` *(continue-on-error)* |
| 16 | Linux (`always()`) | `Upload Allure results` |
| 17 | Linux (conditional fail gate) | `Fail if Linux tests failed` |
| 18 | non-Linux | `Run tests` (`clojure -M:test`) |
| 19 | all | `Build jar` |
| 20 | all | `Build spel native image` |
| 21 | Unix | `CLI bash regression tests (Unix)` |
| 22 | Unix (`always()`) | `Dump daemon log (Unix)` |
| 23 | Unix | `CLI smoke tests (Unix)` |
| 24 | Windows | `CLI smoke tests (Windows)` |
| 25 | Unix | `Upload spel binary (Unix)` |
| 26 | Windows | `Upload spel binary (Windows)` |

### Linux vs non-Linux test behavior

- **Linux**: runs `make lint`, `make validate-safe-graal`, then two test commands with Allure env wiring:
  - `clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure`
  - `clojure -M:test-ct`
  - uploads `allure-results` artifact and fails at explicit gate step if either suite failed.
- **macOS/Windows**: single plain test step: `clojure -M:test`.

---

## 2) Allure Report (`allure.yml`)

Post-CI report workflow. Consumes Linux Allure artifact from CI run, generates HTML report, deploys to `gh-pages` with per-build directories.

### Trigger

```yaml
on:
  workflow_run:
    workflows: ["CI"]
    types: [completed]
```

### Top-level settings

| Setting | Value |
|---|---|
| Workflow name | `Allure Report` |
| `permissions` | `contents: write`, `pull-requests: write`, `checks: write` |
| `concurrency.group` | `allure-report` |
| `concurrency.cancel-in-progress` | `false` |
| `env.PAGES_BASE_URL` | `https://blockether.github.io/spel` |
| `env.MAX_REPORTS` | `15` |
| `env.MAX_PR_REPORTS` | `3` |

### Job structure

| Job | Runs on | Gate |
|---|---|---|
| `report` | `ubuntu-latest` | runs only when upstream CI conclusion is `success` or `failure`, and `head_repository.full_name == github.repository` |

### Caches

| Cache step | Path | Key | Restore keys |
|---|---|---|---|
| `Cache Clojure deps` | `~/.m2/repository`, `~/.gitlibs`, `~/.clojure/.cpcache` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` | `deps-${{ runner.os }}-` |
| `Restore Allure history` (`actions/cache/restore`) | `.allure-history.jsonl` | `allure-history-jsonl-${{ steps.ctx.outputs.ci_run_number }}` | `allure-history-jsonl-` |
| `Cache Allure history` (`actions/cache/save`) | `.allure-history.jsonl` | `allure-history-jsonl-${{ steps.ctx.outputs.ci_run_number }}` | n/a |

### Step flow (exact names)

`report` job steps, in order:

1. *(unnamed)* `actions/checkout@v4` (checks out `head_sha`)
2. *(unnamed)* `actions/setup-node@v4`
3. *(unnamed)* `DeLaGuardo/setup-clojure@13.5`
4. `Cache Clojure deps`
5. `Download Allure results from CI`
6. `Create empty results dir if download failed`
7. `Detect context`
8. `Detect commit info`
9. `Restore Allure history` *(main builds only)*
10. `Detect version`
11. `Generate combined Allure report`
12. `Extract test counts from Allure results`
13. `Comment PR with live report link` *(PR builds only)*
14. `Build PR deploy with metadata` *(PR builds only)*
15. `Deploy PR report to GitHub Pages` *(PR builds only)*
16. `Inject report URL and commit info into history` *(main builds only)*
17. `Fetch existing site from gh-pages` *(main builds only)*
18. `Assemble site with per-build reports` *(main builds only)*
19. `Mark merged PRs` *(main builds only)*
20. `Update PR check statuses` *(main builds only)*
21. `Cache Allure history` *(main builds only)*
22. `Deploy to GitHub Pages` *(main builds only, via `peaceiris/actions-gh-pages@v4`)*

### Deployment layout

| Path on `gh-pages` | Purpose |
|---|---|
| `/<run-number>/` | Report for each main CI run number |
| `/latest/` | HTML redirect to newest main report |
| `/pr/<number>/` | Latest report for each PR |
| `/builds-meta.json`, `/builds-meta.jsonl`, `/builds.json`, `/pr-builds.json`, `/badge.svg` | metadata and status artifacts used by landing page |

---

## 3) Release (`release.yml`)

Tag-driven release pipeline: builds native binaries on 4 targets, publishes GitHub Release, deploys JAR to Clojars, updates versioning files on `main`.

### Trigger

```yaml
on:
  push:
    tags: ['v*']
```

### Job structure

| Job | Runs on | Needs | Purpose |
|---|---|---|---|
| `build` | `${{ matrix.os }}` | — | build/test/upload 4 binaries |
| `release` | `ubuntu-latest` | `build` | changelog, GitHub Release, Clojars deploy, version-file updates |

#### Build matrix (exact)

| OS runner | `arch` | Artifact basename |
|---|---|---|
| `ubuntu-latest` | `amd64` | `spel-linux-amd64` |
| `ubuntu-24.04-arm` | `arm64` | `spel-linux-arm64` |
| `macos-latest` | `arm64` | `spel-macos-arm64` |
| `windows-latest` | `amd64` | `spel-windows-amd64` |

### Caches

| Job | Cache step | Key |
|---|---|---|
| `build` | `Cache Clojure deps` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` |
| `release` | `Cache Clojure deps` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` |

### Steps (exact names)

**`build` job:**

1. *(unnamed)* `actions/checkout@v4`
2. `Setup GraalVM`
3. `Setup Clojure`
4. `Cache Clojure deps`
5. `Build uberjar`
6. `Build native image`
7. `CLI smoke tests (Unix)` *(non-Windows)*
8. `CLI smoke tests (Windows)`
9. `Rename binary (Unix)` *(non-Windows)*
10. `Rename binary (Windows)`
11. `Upload artifact (Unix)` *(non-Windows)*
12. `Upload artifact (Windows)`

**`release` job:**

1. *(unnamed)* `actions/checkout@v4` (`ref: main`, full history + tags)
2. *(unnamed)* `DeLaGuardo/setup-clojure@13.5`
3. `Cache Clojure deps`
4. `Generate changelog`
5. `Download all artifacts`
6. `Make Unix binaries executable`
7. `Create GitHub Release`
8. `Check if version exists on Clojars`
9. `Build & Deploy to Clojars` *(only if version does not already exist)*
10. `Update README.md version`
11. `Update CHANGELOG.md`
12. `Bump SPEL_VERSION to next patch`
13. `Commit version updates`

---

## Running Locally

Common commands mirroring CI/CD checks:

```bash
make test
make lint
make validate-safe-graal
./verify.sh
```

```bash
clojure -T:build jar
clojure -T:build native-image
clojure -T:build uberjar
```

```bash
make test-cli
make test-cli-clj
```
