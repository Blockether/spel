# Product Discovery Reference

Product discovery methodology converts exploratory browser observations → structured product model. Used when agent must infer what product does, who it serves, where UX/IA inconsistencies appear.

Defines:
- 7-phase pipeline from crawl → synthesis
- Canonical JSON outputs (`product-spec.json`, `product-faq.json`)
- Shared vocabularies + scoring dimensions
- Evidence expectations for reproducible discovery artifacts

---

## Overview

Product discovery = black-box analysis workflow for understanding product behavior from outside in. Instead of internal source code assumptions, analyst documents:

- Information architecture + page relationships
- Feature boundaries + feature ownership by role
- UI states across interaction paths
- Coherence quality across visual, interaction, accessibility dimensions

Use when you need:

- Bootstrap product understanding for new team
- Compare expected vs observed feature access by role
- Generate FAQ content from observed behavior
- Identify quality debt before roadmap planning
- Build machine-readable baseline for future audits

Core principle: every extracted claim → maps to visible evidence (snapshot refs, URLs, interaction traces, screenshots).

---

## 7-Phase Pipeline

`CRAWL → CLASSIFY → DISCOVER ROLES → MAP STATES → EXTRACT DOMAIN → COHERENCE AUDIT → SYNTHESIZE`

Each phase produces data required by later phases. Don't skip order; downstream `product-spec.json` sections depend on upstream completeness.

| Phase | Primary goal | Main output section |
|------|--------------|---------------------|
| 1. CRAWL | Enumerate reachable pages + core navigation graph | `navigation_map.pages[]` |
| 2. CLASSIFY | Tag pages + interactions into product areas | `features[].category` + page `type` |
| 3. DISCOVER ROLES | Infer user roles + role privileges | `roles[]` |
| 4. MAP STATES | Capture observable UI states + transitions | `features[].states` |
| 5. EXTRACT DOMAIN | Consolidate product concepts → feature model | `features[]`, `feature_matrix` |
| 6. COHERENCE AUDIT | Score cross-product consistency + quality | `coherence_audit` |
| 7. SYNTHESIZE | Produce recommendations + FAQ-ready narrative | `recommendations[]`, `product-faq.json` |

### Phase 1: CRAWL

Purpose: discover product surface area.

Tasks:
- Start from one or more entry URLs
- Traverse primary + secondary navigation
- Record canonical page URL, visible title, outbound links
- Note dead ends, gated pages, redirects

Expected artifacts:
- Initial site map draft
- URL normalization rules (e.g., trailing slash handling)
- Candidate page list for classification

Completion criteria:
- Navigation coverage includes major menu branches
- `navigation_map.pages[]` has no duplicate canonical URLs

### Phase 2: CLASSIFY

Purpose: map observed pages/modules → known product areas.

Tasks:
- Assign each page type (`landing`, `auth`, `dashboard`, `settings`, etc.)
- Group interactions into candidate features
- Map each feature → category from canonical 10-category vocabulary

Expected artifacts:
- Classified page inventory
- Draft feature candidates with category tags

Completion criteria:
- Every tracked feature has exactly one valid category
- Page type labels consistent with observed purpose

### Phase 3: DISCOVER ROLES

Purpose: infer user role model from visible access boundaries.

Tasks:
- Observe guest-visible vs authenticated surfaces
- Compare menus, controls, routes across account contexts
- Infer privilege levels (user/admin/superadmin) from exposed capabilities

Expected artifacts:
- Role list with semantic names + descriptions
- Feature accessibility mapping per role

Completion criteria:
- Each role has `id`, `name`, `description`, `access_level`
- `features_accessible[]` aligns with observed evidence

### Phase 4: MAP STATES

Purpose: capture dynamic behavior per feature.

Tasks:
- Enumerate states (empty, loading, populated, error, success, disabled, etc.)
- Trigger transitions through real interactions
- Record state-specific evidence + navigation impacts

Expected artifacts:
- State list per feature in `features[].states`
- Notes on transition triggers + blockers

Completion criteria:
- Every core feature includes at least one non-default state
- State names product-meaningful, not implementation-specific

### Phase 5: EXTRACT DOMAIN

Purpose: convert observational data → stable domain model.

Tasks:
- Finalize unique feature IDs (kebab-case)
- Consolidate duplicate feature candidates
- Build role × feature access matrix

Expected artifacts:
- Normalized `features[]`
- Final `roles[]`
- Complete `feature_matrix`

Completion criteria:
- Feature IDs unique + referenced consistently
- Matrix rows cover all roles + all mapped features

### Phase 6: COHERENCE AUDIT

Purpose: quantify consistency + usability quality across product.

Tasks:
- Evaluate all 8 coherence dimensions
- Assign dimension scores (0-100)
- Capture issue statements + `elements[]` evidence objects

Expected artifacts:
- `coherence_audit.score`
- `coherence_audit.dimensions.*`

Completion criteria:
- All eight dimensions present
- Each dimension provides score, issue list, element-level evidence

### Phase 7: SYNTHESIZE

Purpose: produce consumable outputs for product, design, QA, support teams.

Tasks:
- Write actionable recommendations
- Generate FAQ candidates tied to real features
- Validate schema completeness + field consistency

Expected artifacts:
- Final `product-spec.json`
- Final `product-faq.json`

Completion criteria:
- Recommendations concrete + action-oriented
- FAQ entries include confidence + related feature IDs

---

## Output Schemas

Schema snippets use inline annotations (`string — ...`) for intent. Keep keys + nesting exactly as defined.

### product-spec.json

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
      "id": "string — kebab-case unique identifier",
      "name": "string — human-readable name",
      "category": "string — one of the 10 feature categories",
      "description": "string",
      "regions": ["string — region vocabulary values"],
      "states": ["string — UI states observed"],
      "roles_required": ["string — role IDs that can access this feature"],
      "evidence": "string — snapshot ref or URL where observed"
    }
  ],
  "roles": [
    {
      "id": "string — kebab-case",
      "name": "string",
      "description": "string",
      "access_level": "string — guest | user | admin | superadmin",
      "features_accessible": ["string — feature IDs"]
    }
  ],
  "feature_matrix": {
    "description": "2D matrix: roles × features",
    "rows": [
      {
        "role_id": "string",
        "feature_access": {
          "feature-id": "boolean | string — true/false/partial"
        }
      }
    ]
  },
  "coherence_audit": {
    "score": "number 0-100",
    "dimensions": {
      "visual_consistency": {
        "score": "number 0-100",
        "issues": ["string"],
        "elements": []
      },
      "interaction_patterns": { "score": "number", "issues": [], "elements": [] },
      "terminology": { "score": "number", "issues": [], "elements": [] },
      "navigation_flow": { "score": "number", "issues": [], "elements": [] },
      "error_handling": { "score": "number", "issues": [], "elements": [] },
      "loading_states": { "score": "number", "issues": [], "elements": [] },
      "responsive_behavior": { "score": "number", "issues": [], "elements": [] },
      "accessibility_baseline": { "score": "number", "issues": [], "elements": [] }
    }
  },
  "navigation_map": {
    "pages": [
      {
        "url": "string",
        "title": "string",
        "type": "string — landing | auth | dashboard | settings | etc.",
        "links_to": ["string — URLs"]
      }
    ]
  },
  "recommendations": ["string — actionable improvement suggestions"]
}
```

#### product-spec.json field notes

| Field | Guidance |
|------|----------|
| `url` | Root URL scoping analyzed product surface |
| `analyzed_at` | UTC timestamp (`YYYY-MM-DDTHH:mm:ssZ`) |
| `metadata.primary_language` | Prefer observed UI language, not guessed locale |
| `metadata.detected_framework` | `null` when uncertain |
| `features[].evidence` | Stable references: snapshot refs, URL+state, or both |
| `roles[].access_level` | One of: `guest`, `user`, `admin`, `superadmin` |
| `feature_matrix.rows[].feature_access` | Boolean for binary; `partial` for conditional availability |
| `coherence_audit.score` | Overall score explainable from dimension scores |
| `navigation_map.pages[].links_to` | Canonicalized URLs only |
| `recommendations[]` | Action verbs + scope ("unify", "rename", "add") |

#### Role + feature normalization rules

1. IDs: lowercase kebab-case, immutable once published.
2. Feature names: user-facing, avoid internal implementation terms.
3. Merge duplicate features representing same user outcome.
4. Keep role definitions minimal; avoid synthetic roles without evidence.
5. Every `features_accessible[]` entry resolves to existing feature ID.

#### Feature matrix interpretation

- `true`: directly accessible in that role context
- `false`: not accessible
- `partial`: conditional access (plan tier, state, route path)

Use `partial` only when deterministic condition observed.

### product-faq.json

```json
{
  "generated_at": "ISO 8601 timestamp",
  "source_spec": "string — path to product-spec.json",
  "faqs": [
    {
      "id": "string — kebab-case",
      "question": "string",
      "answer": "string",
      "category": "string — feature category or general",
      "related_features": ["string — feature IDs"],
      "confidence": "number 0-1"
    }
  ]
}
```

#### product-faq.json field notes

| Field | Guidance |
|------|----------|
| `generated_at` | FAQ generation timestamp, not crawl time |
| `source_spec` | Relative path or artifact URI to exact spec used |
| `faqs[].id` | Stable kebab-case key for downstream indexing |
| `faqs[].category` | One feature category value or `general` |
| `faqs[].related_features` | One or more `features[].id` links |
| `faqs[].confidence` | `0.0` to `1.0` based on evidence coverage |

#### FAQ writing quality bar

- Questions: user-intent driven, not schema driven.
- Answers: explicit about role constraints when relevant.
- Low certainty → reduce confidence, don't overstate.
- Avoid speculative details unsupported by source spec.

### elements[] Schema

Shared reference type used by all `coherence_audit.dimensions.*.elements` arrays.

```json
{
  "ref": "string — snapshot ref (e.g. @e123)",
  "region": "string — region vocabulary value",
  "description": "string — what was observed",
  "url": "string — page where observed"
}
```

#### elements[] usage rules

1. `ref` identifies specific UI element from snapshot output.
2. `region` must be one value from region vocabulary.
3. `description` describes issue or positive consistency signal.
4. `url` = page where element observed.
5. Multiple entries when same issue appears on multiple pages.

Example:

```json
{
  "ref": "@e4kqmn",
  "region": "nav",
  "description": "Primary CTA label differs from dashboard nav wording",
  "url": "https://example.app/dashboard"
}
```

---

## Vocabulary

Vocabularies = contract-level constants. Don't invent alternatives during reporting.

### Region Vocabulary (15 regions)

Use exactly these values:

1. `hero`
2. `nav`
3. `sidebar`
4. `footer`
5. `modal`
6. `drawer`
7. `toast`
8. `card`
9. `table`
10. `form`
11. `cta`
12. `badge`
13. `tab`
14. `accordion`
15. `carousel`

Region mapping heuristics:

| Region | Typical signals |
|--------|------------------|
| `hero` | Top-of-page headline area with primary proposition/CTA |
| `nav` | Global or local route controls + menu structures |
| `sidebar` | Persistent side navigation or contextual tools |
| `footer` | Bottom-of-page global links/legal/support content |
| `modal` | Overlay dialog requiring contextual acknowledgement |
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

### Feature Categories (10)

Use exactly these category identifiers:

1. `auth`
2. `commerce`
3. `content`
4. `social`
5. `search`
6. `media`
7. `settings`
8. `analytics`
9. `notifications`
10. `integrations`

Category assignment:

- `auth`: login, signup, password reset, session management
- `commerce`: cart, checkout, payment, billing, subscriptions
- `content`: CMS, publishing, editing, article/page management
- `social`: profiles, follows, comments, messaging, sharing
- `search`: query input, filtering, ranking, search result navigation
- `media`: image/video/audio upload, playback, galleries
- `settings`: preferences, account config, feature toggles
- `analytics`: dashboards, charts, KPIs, reporting views
- `notifications`: alerts, inbox, digests, alert preferences
- `integrations`: third-party connections, API keys, webhooks

---

## Coherence Dimensions (8)

Coherence audit uses eight required dimensions. Each gets score (0-100), issue list, `elements[]` evidence links.

1. **visual_consistency** — Color palette, typography, spacing, icon style consistency across pages
2. **interaction_patterns** — Button behaviors, form patterns, hover/focus states, keyboard navigation
3. **terminology** — Consistent naming of features, actions, concepts across product
4. **navigation_flow** — Logical page hierarchy, breadcrumbs, back navigation, deep-link support
5. **error_handling** — Error message style, validation feedback, empty states, 404 handling
6. **loading_states** — Skeleton screens, spinners, progress indicators, optimistic updates
7. **responsive_behavior** — Mobile/tablet/desktop layout consistency, touch targets, overflow
8. **accessibility_baseline** — ARIA labels, focus management, color contrast, keyboard traps

### Scoring rubric (suggested)

| Score range | Interpretation |
|-------------|----------------|
| 90-100 | Highly coherent; minor refinements only |
| 75-89 | Mostly coherent; moderate inconsistencies |
| 60-74 | Noticeable friction; targeted remediations required |
| 40-59 | Significant inconsistency affecting usability |
| 0-39 | Severe coherence debt; systemic redesign likely required |

### Dimension audit checklist

Per dimension:
- Capture at least one confirming or violating `elements[]` evidence object
- Prefer repeatable issues across multiple pages
- Keep issue phrasing neutral + observable
- Avoid implementation guesses without visible proof

---

## End-to-End Synthesis Pattern

Use after phase completion:

1. Validate all required schema keys exist.
2. Every feature categorized + role-linked.
3. Matrix coverage for every role + feature.
4. Reconcile coherence dimension scores with issue severity.
5. Draft recommendations from highest-impact inconsistencies first.
6. Generate FAQs grounded in observed behavior + role limits.

Quality gates before publishing:

- No dangling feature references
- No undefined region/category vocabulary values
- No missing coherence dimensions
- No FAQ entries without related features (unless `general`)

---

## Minimal Example Bundle

Expected output layout:

```text
product-discovery/
  product-spec.json
  product-faq.json
  evidence/
    homepage-snapshot.json
    dashboard-snapshot.json
    settings-snapshot.json
```

Artifact relationships:

- `product-spec.json` = canonical structured model.
- `product-faq.json` = derivative communication artifact.
- `evidence/*` files justify observed claims in both outputs.

---

## Common Pitfalls

1. Mixing inferred + observed behavior without confidence notes.
2. Feature IDs changing between runs.
3. Free-form region names outside 15-item vocabulary.
4. Omitting `partial` access context in feature matrix when constraints exist.
5. Scoring coherence dimensions without element-level evidence.
6. FAQs untraceable to feature evidence.

---

## See Also

- [AGENT_COMMON.md](AGENT_COMMON.md) - shared agent conventions + operational contracts
- [BUGFIND_GUIDE.md](BUGFIND_GUIDE.md) - reference style model for long-form methodology docs
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) - snapshot usage + evidence mechanics