---
description: Discovery workflow — analyze web products to produce feature inventory, user roles, coherence audit, and FAQ
---

# Discovery workflow

Product discovery via spel subagents. Two agents, progressive pipeline.

| Step | Agent | Produces | Consumes |
|------|-------|----------|----------|
| 1. Explore | `@spel-explorer` | `exploration-manifest.json`, snapshots, `auth-state.json` (opt.) | Target URL |
| 2. Analyze | `@spel-product-analyst` | `product-spec.json`, `product-faq.json`, `spel-report.html`, `spel-report.md` | Exploration data (opt.) |

## Parameters

- Task (analyze / crawl + analyze / full auth-gated)
- Target URL
- Output dir (default `discovery-output/`)
- Session name (default `disc`)

## 1. Explore

```xml
<explore>
  <task>Crawl the target URL, discover all pages, capture snapshots and links</task>
  <url>{{target-url}}</url>
  <output>exploration-manifest.json</output>
</explore>
```

**GATE** — pages discovered, link graph, snapshot coverage.

## 2. Analyze

```xml
<analyze>
  <task>Analyze product structure, features, user roles, and FAQ from exploration data</task>
  <url>{{target-url}}</url>
  <manifest>exploration-manifest.json</manifest>
  <output-path>{{output-path}}</output-path>
</analyze>
```

**GATE** — `product-spec.json` complete, `product-faq.json` accurate,
`spel-report.html` clear, `spel-report.md` LLM-readable.

## Auth-gated (optional)

Site needs login / cookie acceptance? Explorer runs Step 0 with `--interactive`
+ user's profile, user authenticates manually, explorer exports
`auth-state.json`, then continues normal exploration.

## Handoff artifacts

- **exploration-manifest.json** — pages[], links[], snapshots[], session
- **product-spec.json** — name, description, url, features[], user_roles[], coherence_audit
- **product-faq.json** — FAQ derived from spec + page content
- **spel-report.html** — human-readable with sidebar nav + snapshots
- **spel-report.md** — LLM-friendly mirror

## Session isolation

- Explorer: `disc-explorer`
- Analyst: `disc-analyst`

Sessions never overlap; each agent closes its session on completion or error.

## Patterns

1. **Quick** — public site, no auth → `@spel-product-analyst` only
2. **Standard** — deep crawl needed → explorer then analyst
3. **Full** — auth/cookies → explorer (with auth bootstrap) then analyst

## Notes

- Analyst can run standalone (does its own crawl).
- `exploration-manifest.json` present → analyst skips its CRAWL phase and uses the manifest.
- Large sites (100+ pages) → run explorer first with scoped crawl.
- Every step has a GATE.
