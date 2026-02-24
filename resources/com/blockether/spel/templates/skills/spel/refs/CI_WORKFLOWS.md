# GitHub Actions CI/CD Workflows

Reference workflows for testing, reporting, native image builds, and deployment.

### CI — Tests + Lint + Native Image (`ci.yml`)

Multi-platform CI (Linux, macOS, Windows) that runs tests, lints, builds native images, and uploads binaries as artifacts.

```yaml
name: CI

on:
  push:
    branches: ["main"]
  pull_request:
    branches: ["main"]

permissions:
  contents: read

jobs:
  test:
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            artifact: spel-dev-linux-amd64
          - os: macos-latest
            artifact: spel-dev-macos-arm64
          - os: windows-latest
            artifact: spel-dev-windows-amd64

    runs-on: ${{ matrix.os }}
    name: CI (${{ matrix.os }})
    defaults:
      run:
        shell: bash
    env:
      # Normalize Playwright browser path across all OSes (macOS default differs)
      PLAYWRIGHT_BROWSERS_PATH: ~/.cache/ms-playwright

    steps:
      - uses: actions/checkout@v4

      - name: Setup GraalVM
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: 'graalvm'

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - uses: clojure-lsp/setup-clojure-lsp@v1
        with:
          clojure-lsp-version: 2025.11.28-12.47.43

      - name: Cache Clojure deps
        uses: actions/cache@v4
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
            ~/.clojure/.cpcache
          key: ${{ runner.os }}-ci-${{ hashFiles('deps.edn') }}
          restore-keys: |
            ${{ runner.os }}-ci-

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('deps.edn') }}
          restore-keys: |
            playwright-${{ runner.os }}-

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      - name: Lint (clojure-lsp)
        if: runner.os == 'Linux'
        run: clojure-lsp diagnostics --raw

      - name: Run tests
        run: clojure -M:test

      - name: Build native image
        run: clojure -T:build native-image

      - name: CLI smoke tests
        run: |
          chmod +x target/spel
          target/spel --help
          target/spel version

      - name: Upload binary
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: target/spel
```

**Key details:**
- `PLAYWRIGHT_BROWSERS_PATH` must be normalized — macOS uses a different default path than Linux
- Browser install uses Java driver CLI (`CLI/main`) so versions match library exactly
- GraalVM 25 + `graalvm` distribution for native-image
- Lint runs only on Linux (one platform is sufficient)

### Allure Report to GitHub Pages (`allure.yml`)

Runs tests with Allure reporter, generates HTML report with embedded Playwright traces, assembles a multi-build landing page, and deploys to GitHub Pages.

**Version Badges:**
The landing page displays version badges for each build:
- **Green "release" badge** for tagged commits (e.g., `v0.3.0`)
- **Yellow "candidate" badge** for untagged commits (e.g., `v0.3.1-candidate`)

Version is read from `resources/SPEL_VERSION` file and matched against git tags. The workflow automatically detects version and badge type, stores them in `builds-meta.json` and `builds.json`, and renders them in landing page template.

```yaml
name: Allure Report

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

env:
  PAGES_BASE_URL: https://<org>.github.io/<repo>
  MAX_REPORTS: 10

jobs:
  report:
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Cache Clojure deps
        uses: actions/cache@v4
        with:
          path: |
            ~/.m2/repository
            ~/.gitlibs
            ~/.clojure/.cpcache
          key: allure-${{ hashFiles('deps.edn') }}

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      # Allure 3 uses .allure-history.jsonl for run history.
      # Cache it between builds so trend graphs and history work.
      - name: Restore Allure history
        uses: actions/cache/restore@v4
        with:
          path: .allure-history.jsonl
          key: allure-history-jsonl-${{ github.run_number }}
          restore-keys: allure-history-jsonl-

      # Restore previous per-build reports so landing page accumulates.
      - name: Restore previous reports
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/restore@v4
        with:
          path: gh-pages-site
          key: allure-site-${{ github.run_number }}
          restore-keys: allure-site-

      - name: Run tests with Allure reporter
        id: tests
        env:
          LAZYTEST_ALLURE_LOGO: logo.svg
          FULL_MSG: ${{ github.event.head_commit.message }}
        run: |
          FIRST_LINE=$(echo "$FULL_MSG" | head -n1 | cut -c1-100)
          export LAZYTEST_ALLURE_REPORT_NAME="#${{ github.run_number }} · $(echo '${{ github.sha }}' | cut -c1-8) · ${FIRST_LINE}"
          clojure -M:test --output nested --output com.blockether.spel.allure-reporter/allure
        continue-on-error: true

      # Inject GitHub Pages URL and commit metadata into latest
      # history entry so landing page can link back to each report.
      - name: Inject report URL and commit info into history
        if: github.ref == 'refs/heads/main'
        env:
          REPORT_URL: ${{ env.PAGES_BASE_URL }}/${{ github.run_number }}
          COMMIT_SHA: ${{ github.sha }}
          COMMIT_MSG: ${{ github.event.head_commit.message }}
          RUN_NUMBER: ${{ github.run_number }}
        run: |
          if [ -f .allure-history.jsonl ]; then
            python3 -c "
          import json, os
          report_url = os.environ['REPORT_URL']
          sha = os.environ.get('COMMIT_SHA', '')[:8]
          msg = os.environ.get('COMMIT_MSG', '').split('\n')[0][:100]
          run = os.environ.get('RUN_NUMBER', '')
          name = f'#{run} · {sha} · {msg}'
          lines = open('.allure-history.jsonl').read().strip().split('\n')
          result = []
          for i, line in enumerate(lines):
              if not line.strip():
                  continue
              entry = json.loads(line)
              if i == len(lines) - 1:
                  entry['url'] = report_url
                  entry['name'] = name
                  for tid in entry.get('testResults', {}):
                      entry['testResults'][tid]['url'] = report_url
              result.append(json.dumps(entry, separators=(',', ':')))
          with open('.allure-history.jsonl', 'w') as f:
              f.write('\n'.join(result) + '\n')
          "
          fi

      # Assemble a multi-build site:
      #   gh-pages-site/
      #     index.html          — landing page with build list
      #     builds.json         — metadata for JS rendering
      #     latest/index.html   — redirect to newest report
      #     <run_number>/       — each Allure HTML report
      - name: Assemble site with per-build reports
        if: github.ref == 'refs/heads/main'
        env:
          RUN_NUMBER: ${{ github.run_number }}
          COMMIT_SHA: ${{ github.sha }}
          COMMIT_MSG: ${{ github.event.head_commit.message }}
          COMMIT_TS: ${{ github.event.head_commit.timestamp }}
          TEST_PASSED: ${{ steps.tests.outcome == 'success' }}
          REPO_URL: ${{ github.server_url }}/${{ github.repository }}
          RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
        run: |
          RUN="${{ github.run_number }}"
          mkdir -p gh-pages-site
          cp -r allure-report "gh-pages-site/${RUN}"

          cd gh-pages-site

          # Prune oldest reports beyond MAX_REPORTS
          DIRS=$(ls -1d [0-9]* 2>/dev/null | sort -n)
          COUNT=$(echo "$DIRS" | grep -c .)
          if [ "$COUNT" -gt "$MAX_REPORTS" ]; then
            REMOVE=$((COUNT - MAX_REPORTS))
            echo "$DIRS" | head -n "$REMOVE" | while read -r dir; do
              rm -rf "$dir"
            done
          fi

          # Generate builds.json for landing page
          BUILDS_META="builds-meta.json"
          [ -f "$BUILDS_META" ] || echo '{}' > "$BUILDS_META"

          python3 -c "
          import json, os, time
          meta_file = '$BUILDS_META'
          meta = json.load(open(meta_file))
          run = os.environ['RUN_NUMBER']
          sha = os.environ.get('COMMIT_SHA', '')
          msg = os.environ.get('COMMIT_MSG', '').split('\n')[0]
          ts_str = os.environ.get('COMMIT_TS', '')
          ts = int(time.time() * 1000)
          if ts_str:
              from datetime import datetime
              try:
                  dt = datetime.fromisoformat(ts_str.replace('Z', '+00:00'))
                  ts = int(dt.timestamp() * 1000)
              except Exception:
                  pass
          passed = os.environ.get('TEST_PASSED', 'false') == 'true'
          repo_url = os.environ.get('REPO_URL', '')
          run_url = os.environ.get('RUN_URL', '')
          meta[run] = {'sha': sha, 'message': msg, 'timestamp': ts, 'passed': passed, 'repo_url': repo_url, 'run_url': run_url}
          dirs = sorted([d for d in os.listdir('.') if d.isdigit()], key=int, reverse=True)
          pruned = {k: v for k, v in meta.items() if k in dirs}
          json.dump(pruned, open(meta_file, 'w'), separators=(',', ':'))
          builds = []
          for d in dirs:
              entry = pruned.get(d, {})
              builds.append({'run': d, 'sha': entry.get('sha', ''), 'message': entry.get('message', ''), 'timestamp': entry.get('timestamp', 0), 'passed': entry.get('passed', True), 'repo_url': entry.get('repo_url', ''), 'run_url': entry.get('run_url', '')})
          json.dump(builds, open('builds.json', 'w'), separators=(',', ':'))
          "

          # Landing page + /latest redirect
          cp ../resources/allure-index.html index.html
          mkdir -p latest
          cat > latest/index.html <<EOF
          <!DOCTYPE html><html><head><meta charset="utf-8"><meta http-equiv="refresh" content="0; url=../${RUN}/"></head><body></body></html>
          EOF
          cd ..

      - name: Cache Allure history
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/save@v4
        with:
          path: .allure-history.jsonl
          key: allure-history-jsonl-${{ github.run_number }}

      - name: Cache site archive
        if: github.ref == 'refs/heads/main'
        uses: actions/cache/save@v4
        with:
          path: gh-pages-site
          key: allure-site-${{ github.run_number }}

      - name: Upload Pages artifact
        if: github.ref == 'refs/heads/main'
        uses: actions/upload-pages-artifact@v3
        with:
          path: gh-pages-site

      - name: Deploy to GitHub Pages
        if: github.ref == 'refs/heads/main'
        id: deployment
        uses: actions/deploy-pages@v4
```

**Key details:**
- **History**: `.allure-history.jsonl` is cached between builds — Allure 3 uses this for trend graphs and run history
- **Multi-build site**: Each run gets its own subdirectory (`gh-pages-site/<run_number>/`), pruned to `MAX_REPORTS`
- **Landing page**: `allure-index.html` renders `builds.json` — shows commit SHA (clickable to repo), date, pass/fail status, and links to each report
- **`/latest` redirect**: `gh-pages-site/latest/index.html` meta-refreshes to newest report number
- **`continue-on-error: true`**: Test failures don't block report generation — report shows what failed
- **Report naming**: `#<run> · <sha8> · <commit msg first line>` for clear identification in Allure history

### Native Image Build + Release (`native-image.yml`)

Cross-platform native image build with automatic GitHub Release on tags.

```yaml
name: Native Image Build

on:
  push:
    branches: [main]
    tags: ['v*']

permissions:
  contents: write

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: ubuntu-latest
            arch: amd64
            artifact: spel-linux-amd64
          - os: ubuntu-24.04-arm
            arch: arm64
            artifact: spel-linux-arm64
          - os: macos-latest
            arch: arm64
            artifact: spel-macos-arm64
          - os: windows-latest
            arch: amd64
            artifact: spel-windows-amd64

    runs-on: ${{ matrix.os }}
    defaults:
      run:
        shell: bash
    env:
      PLAYWRIGHT_BROWSERS_PATH: ~/.cache/ms-playwright

    steps:
      - uses: actions/checkout@v4

      - name: Setup GraalVM
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: 'graalvm'

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('deps.edn') }}

      - name: Install Playwright browsers
        run: clojure -M -e "(com.microsoft.playwright.CLI/main (into-array String [\"install\" \"--with-deps\"]))"

      - name: Build uberjar + native image
        run: |
          clojure -T:build uberjar
          if [[ "$RUNNER_OS" == "Windows" ]]; then
            "$GRAALVM_HOME/bin/native-image.cmd" -jar target/spel-standalone.jar -o target/spel
          else
            native-image -jar target/spel-standalone.jar -o target/spel
          fi

      - name: CLI smoke tests
        run: |
          chmod +x target/spel || true
          target/spel --help
          target/spel version

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: ${{ matrix.artifact }}
          path: target/spel*

  release:
    needs: build
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/download-artifact@v4
        with:
          path: artifacts
      - name: Create Release
        uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: |
            artifacts/spel-linux-amd64/spel-linux-amd64
            artifacts/spel-linux-arm64/spel-linux-arm64
            artifacts/spel-macos-arm64/spel-macos-arm64
            artifacts/spel-windows-amd64/spel-windows-amd64.exe
```

**Key details:**
- Linux arm64 uses `ubuntu-24.04-arm` runner (GitHub's ARM runner)
- Windows native-image uses `$GRAALVM_HOME/bin/native-image.cmd` (not `native-image` directly)
- Release job downloads all platform artifacts and creates a GitHub Release with binaries attached
- Triggered on `v*` tags (e.g., `git tag v0.1.0 && git push --tags`)

### Deploy to Clojars (`deploy.yml`)

Publishes JAR to Clojars and creates a GitHub Release with auto-generated changelog on version tags.

```yaml
name: Deploy to Clojars

on:
  push:
    tags:
      - "v*"

permissions:
  contents: write

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: DeLaGuardo/setup-clojure@13.5
        with:
          cli: latest

      - name: Build & Deploy to Clojars
        env:
          VERSION: ${{ github.ref_name }}
          CLOJARS_USERNAME: <your-deployer>
          CLOJARS_PASSWORD: ${{ secrets.CLOJARS_DEPLOY_TOKEN }}
        run: clojure -T:build deploy

      - name: Update README + CHANGELOG
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          sed -i "s/{:mvn\/version \"[^\"]*\"}/{:mvn\/version \"$VERSION\"}/g" README.md
          # ... update CHANGELOG.md with git log between tags ...
          git config user.name "deployer"
          git config user.email "deploy@example.com"
          git add README.md CHANGELOG.md
          git commit -m "release: update for ${{ github.ref_name }}" || true
          git push origin HEAD:main
```

**Key details:**
- `fetch-depth: 0` — full git history needed for changelog generation between tags
- `CLOJARS_DEPLOY_TOKEN` stored as GitHub secret
- Auto-updates `README.md` version string and `CHANGELOG.md` after deploy
- Pushes version bump commit back to `main`

### Prerequisites for GitHub Pages

Before the Allure workflow can deploy:

1. **Enable GitHub Pages** in repo settings → Pages → Source: **GitHub Actions**
2. **Create environment** named `github-pages` (Settings → Environments)
3. **Set `PAGES_BASE_URL`** in workflow env to `https://<org>.github.io/<repo>`
4. **Landing page**: Place `allure-index.html` in `resources/` — it renders `builds.json` client-side with build list, commit links, pass/fail badges, and date grouping
