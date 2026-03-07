---
description: Discovery workflow — analyze web products to produce feature inventory, user roles, coherence audit, and FAQ
---

# Discovery workflow

Orchestrates product discovery using spel subagents to analyze web products and produce structured specifications.

## Parameters

- Task: the discovery goal (analyze a product, crawl and analyze, full auth-gated analysis)
- Target URL: the URL to analyze
- Output path (optional): directory for `product-spec.json`, `product-faq.json`, `product-report.html` (default: `discovery-output/`)
- Session name (optional): named session for isolation (default: `disc`)

## Pipeline overview

Three agents in a progressive pipeline. Run only what you need.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | @spel-explorer | `exploration-manifest.json`, snapshots | Target URL |
| 2. Analyze | @spel-product-analyst | `product-spec.json`, `product-faq.json`, `product-report.html` | Exploration data (optional) |
| 3. Interactive | @spel-interactive | `auth-state.json`, authenticated snapshots | Target URL |

## Explore

```xml
<explore>
  <task>Crawl the target URL, discover all pages, capture snapshots and links</task>
  <url>{{target-url}}</url>
  <output>exploration-manifest.json</output>
</explore>
```

GATE: Review exploration artifacts, pages discovered, link graph, snapshot coverage. Do NOT proceed until reviewed.

## Analyze

```xml
<analyze>
  <task>Analyze product structure, features, user roles, and FAQ from exploration data</task>
  <url>{{target-url}}</url>
  <manifest>exploration-manifest.json</manifest>
  <output-path>{{output-path}}</output-path>
</analyze>
```

GATE: Review `product-spec.json` for completeness, `product-faq.json` for accuracy, `product-report.html` for clarity. Do NOT proceed until approved.

## Interactive setup (optional)

Only needed when auth, cookies, or manual interaction is required.

```xml
<interact>
  <task>Open headed browser for user interaction — accept cookies, log in, set up session</task>
  <url>{{target-url}}</url>
  <channel>chrome</channel>
  <session>{{session-name}}</session>
</interact>
```

GATE: Confirm authenticated state, verify `auth-state.json` was exported and snapshot shows expected page. Do NOT proceed until confirmed.

## Handoff artifacts

### exploration-manifest.json
Produced by spel-explorer, consumed by spel-product-analyst. Contains:
- `pages[]`: All discovered pages with URL, title, type, and snapshot refs
- `links[]`: Internal link graph
- `snapshots[]`: Accessibility snapshots for key pages
- `session`: Named session used during crawl

### product-spec.json
Produced by spel-product-analyst. Full product specification with:
- `name`, `description`, `url`
- `features[]`: Feature inventory with descriptions and page refs
- `user_roles[]`: Identified user roles and their capabilities
- `coherence_audit`: Consistency checks and issues found

### product-faq.json
Produced by spel-product-analyst. FAQ entries derived from the spec and page content.

### product-report.html
Produced by spel-product-analyst. Human-readable HTML report with sidebar navigation and embedded snapshots.

## Session isolation

Each agent uses its own named session:

- Explorer: `disc-explorer`
- Analyst: `disc-analyst`
- Interactive: `disc-interactive`

Sessions never overlap. Each agent closes its session on completion or error.

## Usage patterns

### Pattern 1: Quick analysis
For public sites with no auth:
```
@spel-product-analyst Analyze https://example.com and produce product-spec.json, product-faq.json, product-report.html
```

### Pattern 2: Standard (with deep crawl)
For sites where you want thorough page coverage:
```
@spel-explorer Crawl https://example.com and save exploration-manifest.json
@spel-product-analyst Analyze https://example.com using exploration-manifest.json
```

### Pattern 3: Full (with auth)
For sites requiring login or cookie acceptance:
```
@spel-interactive Set up session for https://example.com — accept cookies and log in
@spel-explorer Crawl https://example.com (session already set up) and save exploration-manifest.json
@spel-product-analyst Analyze https://example.com using exploration-manifest.json
```

## Composition

- With automation workflow: run the explore step before automating. The automator reads `exploration-manifest.json` for selectors and page structure.
- With test workflow: exploration data helps the test planner identify user flows and test scenarios.
- With visual workflow: explorer snapshots provide baseline material for visual-qa.

## Notes

- spel-product-analyst can run standalone without spel-explorer — it will do its own crawl
- If exploration-manifest.json exists, spel-product-analyst skips the CRAWL phase and uses the manifest
- The full pipeline takes 10-30 minutes depending on site size
- For large sites (100+ pages), use spel-explorer first to control crawl scope
- Every step has a GATE — human review before proceeding
