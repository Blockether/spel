# Product discovery reference

Methodology + canonical schemas for converting browser observations into a structured product model.

Covers: 7-phase pipeline · `product-spec.json` / `product-faq.json` schemas · shared vocabularies · evidence expectations.

## Overview

Product discovery is **black-box analysis** — no source code assumptions. The analyst documents:

- Information architecture + page relationships
- Feature boundaries + role ownership
- UI states across interaction paths
- Coherence quality (visual / interaction / a11y …)

Use it to bootstrap product understanding, compare expected-vs-observed role access, generate FAQs from behavior, identify quality debt, or build a machine-readable baseline.

Core rule: every claim maps to visible evidence — snapshot refs, URLs, interaction traces, screenshots.

## 7-phase pipeline

`CRAWL → CLASSIFY → DISCOVER ROLES → MAP STATES → EXTRACT DOMAIN → COHERENCE AUDIT → SYNTHESIZE`

| Phase | Goal | Primary output |
|------:|------|----------------|
| 1. CRAWL | Reachable pages + core nav graph | `navigation_map.pages[]` |
| 2. CLASSIFY | Pages/interactions → product areas | `features[].category` + page `type` |
| 3. DISCOVER ROLES | Role model + privileges | `roles[]` |
| 4. MAP STATES | Observable UI states + transitions | `features[].states` |
| 5. EXTRACT DOMAIN | Consolidated feature model | `features[]`, `feature_matrix` |
| 6. COHERENCE AUDIT | Cross-product consistency scores | `coherence_audit` |
| 7. SYNTHESIZE | Recommendations + FAQ | `recommendations[]`, `product-faq.json` |

### Phase details

**1 · CRAWL.** Start from entry URLs; traverse primary + secondary nav; record canonical URL / title / outbound links; note dead ends, gated pages, redirects. **Done when**: navigation coverage includes major menu branches and `navigation_map.pages[]` has no duplicate canonical URLs.

**2 · CLASSIFY.** Assign each page a `type` (`landing`, `auth`, `dashboard`, `settings`, …); group interactions into candidate features; map each feature to one of the 10 categories. **Done when**: every feature has exactly one valid category and page types match observed purpose.

**3 · DISCOVER ROLES.** Compare menus/controls/routes across account contexts; infer privilege levels from exposed capabilities. **Done when**: each role has `id`, `name`, `description`, `access_level`; `features_accessible[]` matches evidence.

**4 · MAP STATES.** Enumerate states (empty / loading / populated / error / success / disabled …); trigger transitions; record evidence + navigation impact. **Done when**: every core feature has at least one non-default state; names are product-meaningful, not implementation-specific.

**5 · EXTRACT DOMAIN.** Finalize kebab-case feature IDs; consolidate duplicates; build the role × feature matrix. **Done when**: IDs unique + referenced consistently; matrix covers every role × feature.

**6 · COHERENCE AUDIT.** Score all 8 dimensions (0–100) with issue lists + element-level evidence. **Done when**: all eight present; each has score + issues + elements.

**7 · SYNTHESIZE.** Actionable recommendations; FAQs grounded in real features; schema validation. **Done when**: recommendations are concrete/actionable; FAQ entries include confidence + related feature IDs.

## Output schemas

Keep keys + nesting exactly as defined.

### `product-spec.json`

```json
{
  "url": "string — the analyzed URL",
  "analyzed_at": "ISO 8601 timestamp",
  "metadata": {
    "title": "string",
    "description": "string",
    "primary_language": "string",
    "detected_framework": "string | null"
  },
  "features": [
    {
      "id": "kebab-case unique id",
      "name": "human-readable",
      "category": "one of 10 categories",
      "description": "string",
      "regions": ["from region vocabulary"],
      "states": ["UI states observed"],
      "roles_required": ["role ids"],
      "evidence": "snapshot ref or URL"
    }
  ],
  "roles": [
    {"id":"kebab-case","name":"string","description":"string",
     "access_level":"guest | user | admin | superadmin",
     "features_accessible":["feature ids"]}
  ],
  "feature_matrix": {
    "description": "2D matrix: roles × features",
    "rows": [{"role_id":"string", "feature_access":{"feature-id":"bool|partial"}}]
  },
  "coherence_audit": {
    "score": "0-100",
    "dimensions": {
      "visual_consistency":     {"score":"0-100","issues":[],"elements":[]},
      "interaction_patterns":   {"score":"0-100","issues":[],"elements":[]},
      "terminology":            {"score":"0-100","issues":[],"elements":[]},
      "navigation_flow":        {"score":"0-100","issues":[],"elements":[]},
      "error_handling":         {"score":"0-100","issues":[],"elements":[]},
      "loading_states":         {"score":"0-100","issues":[],"elements":[]},
      "responsive_behavior":    {"score":"0-100","issues":[],"elements":[]},
      "accessibility_baseline": {"score":"0-100","issues":[],"elements":[]}
    }
  },
  "navigation_map": {
    "pages": [{"url":"string","title":"string","type":"landing|auth|dashboard|…","links_to":["urls"]}]
  },
  "recommendations": ["actionable improvement suggestions"]
}
```

Field notes:

- `analyzed_at` — UTC `YYYY-MM-DDTHH:mm:ssZ`.
- `metadata.primary_language` — observed UI language, not guessed locale.
- `metadata.detected_framework` — `null` when uncertain.
- `features[].evidence` — snapshot refs, URL+state, or both.
- `roles[].access_level` — one of `guest | user | admin | superadmin`.
- `feature_matrix.rows[].feature_access` — boolean for binary, `"partial"` only for deterministic conditional access (plan tier, state, route).
- `coherence_audit.score` — explainable from dimension scores.
- `navigation_map.pages[].links_to` — canonicalized URLs only.
- `recommendations[]` — start with an action verb (`unify`, `rename`, `add`).

Role / feature normalization:

1. IDs: lowercase kebab-case, immutable once published.
2. Feature names: user-facing, not implementation terms.
3. Merge duplicates representing the same user outcome.
4. Don't invent synthetic roles without evidence.
5. Every `features_accessible[]` entry resolves to an existing feature id.

### `product-faq.json`

```json
{
  "generated_at": "ISO 8601 timestamp",
  "source_spec": "path to product-spec.json",
  "faqs": [
    {"id":"kebab-case","question":"string","answer":"string",
     "category":"feature category or general",
     "related_features":["feature ids"],
     "confidence":"0-1"}
  ]
}
```

FAQ quality bar:

- User-intent-driven questions (not schema-driven).
- Answers explicit about role constraints when relevant.
- Low certainty → lower `confidence`, never overstate.
- Never speculate beyond the spec.

### `elements[]` schema (shared)

Used by every `coherence_audit.dimensions.*.elements`:

```json
{"ref":"@e123", "region":"from region vocab",
 "description":"what was observed", "url":"page observed on"}
```

Rules: `ref` identifies a specific UI element from a snapshot; `region` must be one of the 15 vocabulary values; `description` states the issue or consistency signal; `url` is the page where observed. Use multiple entries when the same issue appears on multiple pages.

```json
{"ref":"@e4kqmn","region":"nav",
 "description":"Primary CTA label differs from dashboard nav wording",
 "url":"https://example.app/dashboard"}
```

## Vocabularies

Contract constants — don't invent alternatives.

### Region vocabulary (15)

`hero`, `nav`, `sidebar`, `footer`, `modal`, `drawer`, `toast`, `card`, `table`, `form`, `cta`, `badge`, `tab`, `accordion`, `carousel`.

| Region | Typical signals |
|--------|-----------------|
| `hero` | Top-of-page headline with primary proposition/CTA |
| `nav` | Global or local route controls, menu structures |
| `sidebar` | Persistent side navigation or contextual tools |
| `footer` | Bottom-of-page global links, legal, support |
| `modal` | Overlay requiring contextual acknowledgement |
| `drawer` | Side panel sliding over content |
| `toast` | Short-lived notification container |
| `card` | Self-contained grouped content block |
| `table` | Grid/list with row-column semantics |
| `form` | Inputs + submission controls |
| `cta` | Primary action trigger with conversion intent |
| `badge` | Compact status/label token |
| `tab` | Alternate view switcher within one context |
| `accordion` | Expand/collapse grouped sections |
| `carousel` | Rotating or paged visual/content track |

### Feature categories (10)

`auth`, `commerce`, `content`, `social`, `search`, `media`, `settings`, `analytics`, `notifications`, `integrations`.

- `auth` — login, signup, password reset, session
- `commerce` — cart, checkout, payment, billing, subscriptions
- `content` — CMS, publishing, editing, article/page mgmt
- `social` — profiles, follows, comments, messaging, sharing
- `search` — query, filter, ranking, results navigation
- `media` — image/video/audio upload, playback, galleries
- `settings` — prefs, account config, feature toggles
- `analytics` — dashboards, charts, KPIs, reports
- `notifications` — alerts, inbox, digests, preferences
- `integrations` — third-party connections, API keys, webhooks

## Coherence dimensions (8)

Each scored 0–100 with issue list + `elements[]`:

1. **visual_consistency** — color, typography, spacing, icon style.
2. **interaction_patterns** — button behaviors, forms, hover/focus, keyboard nav.
3. **terminology** — consistent naming of features, actions, concepts.
4. **navigation_flow** — page hierarchy, breadcrumbs, back, deep links.
5. **error_handling** — error style, validation, empty states, 404.
6. **loading_states** — skeletons, spinners, progress, optimistic updates.
7. **responsive_behavior** — mobile/tablet/desktop consistency, touch, overflow.
8. **accessibility_baseline** — ARIA, focus, contrast, keyboard traps.

Scoring rubric:

| Range | Interpretation |
|-------|----------------|
| 90–100 | Highly coherent; minor refinements |
| 75–89 | Mostly coherent; moderate inconsistencies |
| 60–74 | Noticeable friction; targeted remediations |
| 40–59 | Significant inconsistency affecting usability |
| 0–39 | Severe coherence debt; systemic redesign likely |

Per dimension: ≥1 confirming or violating element; prefer issues that repeat across pages; keep phrasing neutral + observable; no implementation guesses without proof.

## End-to-end synthesis

1. Validate every required key exists.
2. Every feature categorized + role-linked.
3. Matrix coverage for every role × feature.
4. Reconcile dimension scores with issue severity.
5. Recommendations by highest impact first.
6. FAQs grounded in observed behavior + role limits.

Quality gates before publishing: no dangling feature refs, no off-vocabulary region/category values, no missing coherence dimensions, no FAQ entries without related features (unless `general`).

## Layout

```text
product-discovery/
  product-spec.json     # canonical structured model
  product-faq.json      # derivative communication artifact
  evidence/
    homepage-snapshot.json
    dashboard-snapshot.json
    settings-snapshot.json
```

## Common pitfalls

- Mixing inferred vs observed behavior without confidence notes.
- Feature IDs changing between runs.
- Free-form region names outside the 15-item vocabulary.
- Omitting `partial` context in the feature matrix when constraints exist.
- Scoring coherence dimensions without element-level evidence.
- FAQs untraceable to feature evidence.

## See also

- `AGENT_COMMON.md` · `BUGFIND_GUIDE.md` · `SELECTORS_SNAPSHOTS.md`
