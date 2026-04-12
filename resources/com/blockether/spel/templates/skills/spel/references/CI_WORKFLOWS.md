# GitHub Actions CI/CD workflows

Three workflows: `.github/workflows/ci.yml`, `allure.yml`, `release.yml`.

## 1 · CI (`ci.yml`)

Tests on 3 OSes, Linux-only lint/validation + dual test suites with Allure, native-binary build + smoke tests.

```yaml
on:
  push:         { branches: [main] }
  pull_request: { branches: [main] }
```

| Job | Runs on | Matrix | Notes |
|-----|---------|--------|-------|
| `test` | `${{ matrix.os }}` | `ubuntu-latest`, `macos-latest`, `windows-latest` | `fail-fast: false`; binaries: `spel-dev-linux-amd64`, `-macos-arm64`, `-windows-amd64` |

### Caches

| Step | Path | Key |
|------|------|-----|
| `Cache Clojure deps` | `~/.m2/repository`, `~/.gitlibs`, `~/.clojure/.cpcache` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` |
| `Cache Playwright browsers` | `~/.cache/ms-playwright` | `playwright-${{ runner.os }}-1.58.0` |

Restore key prefixes are the same with the hash dropped.

### Step flow

| # | Scope | Step |
|--:|-------|------|
| 1 | all | `actions/checkout@v4` |
| 2 | all | `Normalize Playwright browsers path` |
| 3 | all | `Setup GraalVM` |
| 4 | all | `DeLaGuardo/setup-clojure@13.5` |
| 5 | Linux | `clojure-lsp/setup-clojure-lsp@v1` |
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
| 17 | Linux | `Fail if Linux tests failed` (explicit gate) |
| 18 | non-Linux | `Run tests` (`clojure -M:test`) |
| 19 | all | `Build jar` |
| 20 | all | `Build spel native image` |
| 21 | Unix | `CLI bash regression tests (Unix)` |
| 22 | Unix (`always()`) | `Dump daemon log (Unix)` |
| 23 | Unix | `CLI smoke tests (Unix)` |
| 24 | Windows | `CLI smoke tests (Windows)` |
| 25 | Unix | `Upload spel binary (Unix)` |
| 26 | Windows | `Upload spel binary (Windows)` |

**Linux** runs `make lint`, `make validate-safe-graal`, both test commands with Allure wiring
(`clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure` + `clojure -M:test-ct`),
uploads `allure-results`, and fails at the explicit gate if either suite failed.
**macOS/Windows** run a single plain `clojure -M:test`.

## 2 · Allure report (`allure.yml`)

Consumes the Linux Allure artifact from the CI run, generates an HTML report, and deploys to `gh-pages` with per-build directories.

```yaml
on:
  workflow_run: { workflows: ["CI"], types: [completed] }
```

| Setting | Value |
|---------|-------|
| Name | `Allure Report` |
| `permissions` | `contents: write`, `pull-requests: write`, `checks: write` |
| `concurrency.group` | `allure-report` |
| `concurrency.cancel-in-progress` | `false` |
| `env.PAGES_BASE_URL` | `https://blockether.github.io/spel` |
| `env.MAX_REPORTS` | `15` |
| `env.MAX_PR_REPORTS` | `3` |

| Job | Runs on | Gate |
|-----|---------|------|
| `report` | `ubuntu-latest` | Upstream CI conclusion `success` or `failure` and `head_repository.full_name == github.repository` |

### Caches

| Step | Path | Key |
|------|------|-----|
| `Cache Clojure deps` | `~/.m2/repository`, `~/.gitlibs`, `~/.clojure/.cpcache` | `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}` |
| `Restore Allure history` (cache/restore) | `.allure-history.jsonl` | `allure-history-jsonl-${{ steps.ctx.outputs.ci_run_number }}` |
| `Cache Allure history` (cache/save) | `.allure-history.jsonl` | same |

### Step flow

1. `actions/checkout@v4` (checks out `head_sha`)
2. `actions/setup-node@v4`
3. `DeLaGuardo/setup-clojure@13.5`
4. `Cache Clojure deps`
5. `Download Allure results from CI`
6. `Create empty results dir if download failed`
7. `Detect context`
8. `Detect commit info`
9. `Restore Allure history` *(main builds)*
10. `Detect version`
11. `Generate combined Allure report`
12. `Extract test counts from Allure results`
13. `Comment PR with live report link` *(PR builds)*
14. `Build PR deploy with metadata` *(PR builds)*
15. `Deploy PR report to GitHub Pages` *(PR builds)*
16. `Inject report URL and commit info into history` *(main)*
17. `Fetch existing site from gh-pages` *(main)*
18. `Assemble site with per-build reports` *(main)*
19. `Mark merged PRs` *(main)*
20. `Update PR check statuses` *(main)*
21. `Cache Allure history` *(main)*
22. `Deploy to GitHub Pages` *(main, via `peaceiris/actions-gh-pages@v4`)*

### Deployment layout

| Path on `gh-pages` | Purpose |
|--------------------|---------|
| `/<run-number>/` | Report for each main CI run |
| `/latest/` | HTML redirect to newest main report |
| `/pr/<number>/` | Latest report for each PR |
| `/builds-meta.json`, `/builds-meta.jsonl`, `/builds.json`, `/pr-builds.json`, `/badge.svg` | Landing-page metadata and status |

## 3 · Release (`release.yml`)

Tag-driven pipeline: builds native binaries on 4 targets, publishes GitHub Release, deploys JAR to Clojars, updates version files on `main`.

```yaml
on:
  push: { tags: ['v*'] }
```

| Job | Runs on | Needs | Purpose |
|-----|---------|-------|---------|
| `build` | `${{ matrix.os }}` | — | Build / test / upload 4 binaries |
| `release` | `ubuntu-latest` | `build` | Changelog, GitHub Release, Clojars deploy, version-file updates |

### Build matrix

| OS runner | arch | Artifact |
|-----------|------|----------|
| `ubuntu-latest` | amd64 | `spel-linux-amd64` |
| `ubuntu-24.04-arm` | arm64 | `spel-linux-arm64` |
| `macos-latest` | arm64 | `spel-macos-arm64` |
| `windows-latest` | amd64 | `spel-windows-amd64` |

Both jobs cache Clojure deps with `deps-${{ runner.os }}-${{ hashFiles('deps.edn') }}`.

### `build` steps

1. `actions/checkout@v4`
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

### `release` steps

1. `actions/checkout@v4` (`ref: main`, full history + tags)
2. `DeLaGuardo/setup-clojure@13.5`
3. `Cache Clojure deps`
4. `Generate changelog`
5. `Download all artifacts`
6. `Make Unix binaries executable`
7. `Create GitHub Release`
8. `Check if version exists on Clojars`
9. `Build & Deploy to Clojars` *(only when version is new)*
10. `Update README.md version`
11. `Update CHANGELOG.md`
12. `Bump SPEL_VERSION to next patch`
13. `Commit version updates`

## Running locally

```bash
make test
make lint
make validate-safe-graal
./verify.sh

clojure -T:build jar
clojure -T:build native-image
clojure -T:build uberjar

make test-cli
make test-cli-clj
```
