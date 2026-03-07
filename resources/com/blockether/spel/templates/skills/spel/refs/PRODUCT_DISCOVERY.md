# Product Discovery Reference

The product discovery methodology converts exploratory browser observations into a structured product model. It is used when an agent must infer what a product does, who it serves, and where UX or IA inconsistencies appear.

This reference defines:
- The 7-phase pipeline from crawl to synthesis
- Canonical JSON outputs (`product-spec.json`, `product-faq.json`)
- Shared vocabularies and scoring dimensions
- Evidence expectations for reproducible discovery artifacts

---

## Overview

Product discovery is a black-box analysis workflow for understanding product behavior from the outside in. Instead of relying on internal source code assumptions, the analyst documents:

- Information architecture and page relationships
- Feature boundaries and feature ownership by role
- UI states across interaction paths
- Coherence quality across visual, interaction, and accessibility dimensions

Use this methodology when you need one or more of the following outcomes:

- Bootstrap product understanding for a new team
- Compare expected vs observed feature access by role
- Generate FAQ content from observed product behavior
- Identify quality debt before roadmap planning
- Build a machine-readable baseline for future audits

Core principle: every extracted claim should map to visible evidence (snapshot refs, URLs, interaction traces, or screenshots).

---

## 7-Phase Pipeline

`CRAWL → CLASSIFY → DISCOVER ROLES → MAP STATES → EXTRACT DOMAIN → COHERENCE AUDIT → SYNTHESIZE`

Each phase produces data required by later phases. Do not skip order; downstream sections in `product-spec.json` depend on upstream completeness.

| Phase | Primary goal | Main output section |
|------|--------------|---------------------|
| 1. CRAWL | Enumerate reachable pages and core navigation graph | `navigation_map.pages[]` |
| 2. CLASSIFY | Tag pages and interactions into product areas | `features[].category` + page `type` |
| 3. DISCOVER ROLES | Infer user roles and role privileges | `roles[]` |
| 4. MAP STATES | Capture observable UI states and transitions | `features[].states` |
| 5. EXTRACT DOMAIN | Consolidate product concepts into feature model | `features[]`, `feature_matrix` |
| 6. COHERENCE AUDIT | Score cross-product consistency and quality | `coherence_audit` |
| 7. SYNTHESIZE | Produce recommendations + FAQ-ready narrative | `recommendations[]`, `product-faq.json` |

### Phase 1: CRAWL

Purpose: discover the product surface area.

Tasks:
- Start from one or more entry URLs
- Traverse primary and secondary navigation
- Record canonical page URL, visible title, and outbound links
- Note dead ends, gated pages, and redirects

Expected artifacts:
- Initial site map draft
- URL normalization rules (e.g., trailing slash handling)
- Candidate page list for classification

Completion criteria:
- Navigation coverage includes major menu branches
- `navigation_map.pages[]` has no duplicate canonical URLs

### Phase 2: CLASSIFY

Purpose: map observed pages and modules to known product areas.

Tasks:
- Assign each page a type (`landing`, `auth`, `dashboard`, `settings`, etc.)
- Group interactions into candidate features
- Map each feature to a category from the canonical 10-category vocabulary

Expected artifacts:
- Classified page inventory
- Draft feature candidates with category tags

Completion criteria:
- Every tracked feature has exactly one valid category
- Page type labels are consistent with observed purpose

### Phase 3: DISCOVER ROLES

Purpose: infer user role model from visible access boundaries.

Tasks:
- Observe guest-visible vs authenticated surfaces
- Compare menus, controls, and routes across account contexts
- Infer privilege levels (user/admin/superadmin) from exposed capabilities

Expected artifacts:
- Role list with semantic names and descriptions
- Feature accessibility mapping per role

Completion criteria:
- Each role has `id`, `name`, `description`, and `access_level`
- `features_accessible[]` aligns with observed evidence

### Phase 4: MAP STATES

Purpose: capture dynamic behavior for each feature.

Tasks:
- Enumerate states (empty, loading, populated, error, success, disabled, etc.)
- Trigger transitions through real interactions
- Record state-specific evidence and navigation impacts

Expected artifacts:
- State list per feature in `features[].states`
- Notes on transition triggers and blockers

Completion criteria:
- Every core feature includes at least one non-default state
- State names are product-meaningful, not implementation-specific

### Phase 5: EXTRACT DOMAIN

Purpose: convert observational data into a stable domain model.

Tasks:
- Finalize unique feature IDs (kebab-case)
- Consolidate duplicate feature candidates
- Build role x feature access matrix

Expected artifacts:
- Normalized `features[]`
- Final `roles[]`
- Complete `feature_matrix`

Completion criteria:
- Feature IDs are unique and referenced consistently
- Matrix rows cover all roles and all mapped features

### Phase 6: COHERENCE AUDIT

Purpose: quantify consistency and usability quality across the product.

Tasks:
- Evaluate all 8 coherence dimensions
- Assign dimension scores (0-100)
- Capture issue statements plus `elements[]` evidence objects

Expected artifacts:
- `coherence_audit.score`
- `coherence_audit.dimensions.*`

Completion criteria:
- All eight dimensions are present
- Each dimension provides score, issue list, and element-level evidence

### Phase 7: SYNTHESIZE

Purpose: produce consumable outputs for product, design, QA, and support teams.

Tasks:
- Write actionable recommendations
- Generate FAQ candidates tied to real features
- Validate schema completeness and field consistency

Expected artifacts:
- Final `product-spec.json`
- Final `product-faq.json`

Completion criteria:
- Recommendations are concrete and action-oriented
- FAQ entries include confidence and related feature IDs

---

## Output Schemas

Schema snippets below use inline annotations (`string — ...`) for intent clarity. Keep keys and nesting exactly as defined.

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
| `url` | Use the root URL that scopes the analyzed product surface |
| `analyzed_at` | Emit UTC timestamp (`YYYY-MM-DDTHH:mm:ssZ`) |
| `metadata.primary_language` | Prefer observed UI language, not guessed locale |
| `metadata.detected_framework` | Set to `null` when uncertain |
| `features[].evidence` | Use stable references: snapshot refs, URL+state, or both |
| `roles[].access_level` | Must be one of: `guest`, `user`, `admin`, `superadmin` |
| `feature_matrix.rows[].feature_access` | Use boolean when binary; `partial` for conditional availability |
| `coherence_audit.score` | Overall score should be explainable from dimension scores |
| `navigation_map.pages[].links_to` | Include canonicalized URLs only |
| `recommendations[]` | Use action verbs and scope ("unify", "rename", "add") |

#### role and feature normalization rules

1. IDs are lowercase kebab-case and immutable once published.
2. Feature names are user-facing and avoid internal implementation terms.
3. Merge duplicate features that represent the same user outcome.
4. Keep role definitions minimal; avoid synthetic roles without evidence.
5. Ensure every `features_accessible[]` entry resolves to an existing feature ID.

#### feature matrix interpretation

- `true`: feature is directly accessible in that role context
- `false`: feature is not accessible
- `partial`: feature access is conditional (plan tier, state, or route path)

Use `partial` only when a deterministic condition is observed.

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
| `generated_at` | Timestamp of FAQ generation, not crawl time |
| `source_spec` | Relative path or artifact URI to the exact spec used |
| `faqs[].id` | Stable kebab-case key for downstream indexing |
| `faqs[].category` | Use one feature category value or `general` |
| `faqs[].related_features` | Include one or more `features[].id` links |
| `faqs[].confidence` | Value from `0.0` to `1.0` based on evidence coverage |

#### FAQ writing quality bar

- Questions should be user-intent driven, not schema driven.
- Answers should be explicit about role constraints when relevant.
- If certainty is low, reduce confidence instead of overstating behavior.
- Avoid speculative details not supported by the source spec.

### elements[] Schema

This shared reference type is used by all `coherence_audit.dimensions.*.elements` arrays.

```json
{
  "ref": "string — snapshot ref (e.g. @e123)",
  "region": "string — region vocabulary value",
  "description": "string — what was observed",
  "url": "string — page where observed"
}
```

#### elements[] usage rules

1. `ref` should identify a specific UI element from snapshot output.
2. `region` must be one value from the region vocabulary section.
3. `description` should describe the issue or positive consistency signal.
4. `url` should be the page where the element was observed.
5. Use multiple entries when the same issue appears on multiple pages.

Example entry:

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

Vocabularies are contract-level constants. Do not invent alternatives during reporting.

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
| `nav` | Global or local route controls and menu structures |
| `sidebar` | Persistent side navigation or contextual tools |
| `footer` | Bottom-of-page global links/legal/support content |
| `modal` | Overlay dialog requiring contextual acknowledgement |
| `drawer` | Side panel that slides over content |
| `toast` | Short-lived notification container |
| `card` | Self-contained grouped content block |
| `table` | Grid/list with row-column semantics |
| `form` | Inputs and submission controls |
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

Category assignment guidance:

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

The coherence audit uses eight required dimensions. Each dimension gets a score (0-100), issue list, and `elements[]` evidence links.

1. **visual_consistency** — Color palette, typography, spacing, icon style consistency across pages
2. **interaction_patterns** — Button behaviors, form patterns, hover/focus states, keyboard navigation
3. **terminology** — Consistent naming of features, actions, and concepts across the product
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

For each dimension:
- Capture at least one confirming or violating `elements[]` evidence object
- Prefer issues that are repeatable across multiple pages
- Keep issue phrasing neutral and observable
- Avoid implementation guesses without visible proof

---

## End-to-End Synthesis Pattern

Use this synthesis sequence after phase completion:

1. Validate all required schema keys exist.
2. Ensure every feature is categorized and role-linked.
3. Confirm matrix coverage for every role and feature.
4. Reconcile coherence dimension scores with issue severity.
5. Draft recommendations from highest-impact inconsistencies first.
6. Generate FAQs grounded in observed behavior and role limits.

Quality gates before publishing artifacts:

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

Artifact relationship summary:

- `product-spec.json` is the canonical structured model.
- `product-faq.json` is a derivative communication artifact.
- `evidence/*` files justify observed claims in both outputs.

---

## Common Pitfalls

1. Mixing inferred behavior with observed behavior without confidence notes.
2. Creating feature IDs that change between runs.
3. Using free-form region names outside the 15-item vocabulary.
4. Omitting `partial` access context in feature matrix when constraints exist.
5. Scoring coherence dimensions without element-level evidence.
6. Generating FAQs that cannot be traced back to feature evidence.

---

## See Also

- [AGENT_COMMON.md](AGENT_COMMON.md) - shared agent conventions and operational contracts
- [BUGFIND_GUIDE.md](BUGFIND_GUIDE.md) - reference style model for long-form methodology docs
- [SELECTORS_SNAPSHOTS.md](SELECTORS_SNAPSHOTS.md) - snapshot usage and evidence mechanics
