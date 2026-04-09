<!-- Adapted from visual-explainer (MIT License, github.com/nicobailon/visual-explainer) -->
# Presenter Reference

Generate self-contained HTML for technical diagrams, visualizations, data tables. Use `spel open` to preview, `spel screenshot` to capture evidence.

> **Design system**: ALL output MUST use **spel report design system** from `CSS_PATTERNS.md` — Atkinson Hyperlegible / Manrope / IBM Plex Mono, warm earth tones (#b2652a brown accent). Do NOT invent your own color palette or font stack.

## Workflow

### 1. Think (5 seconds)
Before writing HTML, commit to direction.

Who's looking? Dev understanding a system? PM seeing big picture? Shapes information density.

What type? Architecture, flowchart, sequence, data flow, schema/ER, state machine, mind map, class diagram, C4, data table, timeline, dashboard, slide deck.

What aesthetic? **Default = always spel brand** (warm earth tones from CSS_PATTERNS.md). Only deviate on explicit user request:
- Blueprint (technical drawing, deep slate/blue palette, monospace labels)
- Editorial (serif headlines, generous whitespace, muted earth tones)
- Paper/ink (warm cream background, terracotta/sage accents)
- Monochrome terminal (green/amber on near-black)
- IDE-inspired (commit to real named scheme: Dracula, Nord, Catppuccin, Solarized, Gruvbox)

Forbidden aesthetics:
- Neon dashboard (cyan + magenta + purple on dark) → always AI slop
- Gradient mesh (pink/purple/cyan blobs)
- Inter font + violet/indigo accents + gradient text

### 2. Structure
Rendering approach:

| Content type | Approach |
|---|---|
| Architecture (text-heavy) | CSS Grid cards + flow arrows |
| Architecture (topology-focused) | Mermaid `graph TD` |
| Flowchart / pipeline | Mermaid |
| Sequence diagram | Mermaid `sequenceDiagram` |
| Data flow | Mermaid with edge labels |
| ER / schema | Mermaid `erDiagram` |
| State machine | Mermaid `stateDiagram-v2` |
| Mind map | Mermaid `mindmap` |
| Class diagram | Mermaid `classDiagram` |
| C4 architecture | Mermaid `graph TD` + `subgraph` (NOT native C4Context) |
| Data table | HTML `<table>` |
| Dashboard | CSS Grid + Chart.js |
| Slide deck | Scroll-snap slides (see SLIDE_PATTERNS.md) |

Mermaid theming: always `theme: 'base'` with custom `themeVariables`. Never use built-in themes — they ignore variable overrides.

Mermaid containers: always center with `display: flex; justify-content: center;`. Add zoom controls (+/−/reset/expand) to every `.mermaid-wrap`.

### 3. Style
- Typography: Use **spel report font stack** from CSS_PATTERNS.md (Atkinson Hyperlegible / Manrope / IBM Plex Mono). Do NOT substitute unless user explicitly requests.
- Color: Copy **exact CSS custom properties** from CSS_PATTERNS.md theme. Use `--accent: #b2652a`, `--node-b: #1f8a5c`, `--node-c: #0f766e`, `--node-d: #b7791f`, `--node-e: #c44536`.
- Surfaces: Build depth via `--surface`, `--surface-elevated`, `--bg-secondary` tiers from CSS_PATTERNS.md.
- Animation: staggered fade-ins on load. Respect `prefers-reduced-motion`. Forbidden: animated glowing box-shadows, pulsing effects on static content.

### 4. Deliver
Output: write to `$(pwd)/spel-visual/` (ALWAYS absolute path — daemon CWD fixed at startup). Descriptive filenames: `architecture.html`, `pipeline-flow.html`.

Preview:
```bash
spel open $(pwd)/spel-visual/filename.html
```

Capture:
```bash
spel screenshot $(pwd)/spel-visual/filename.png
```

Tell user file path for re-open/sharing.

---

## Content Specification Protocol (ANTI-HALLUCINATION)

**Prevents hallucinated content. Follow exactly.**

### Rule 1: Only use information user provided
- NEVER invent metric values, statistics, percentages, counts
- NEVER fabricate component names, API endpoints, file paths user didn't mention
- User said "3 services" → show exactly 3, not 4 or 5
- Need label user didn't provide → use generic placeholder `[Service Name]`, note in output

### Rule 2: Every text slot filled intentionally
Per text element, answer: "Where did this text come from?"
- **From user's input** — exact quote or close paraphrase ✅
- **Structural label** — "Overview", "Details", "Pipeline", "Step 1" ✅
- **Made up because it looked good** — ❌ NEVER

### Rule 3: Describe what you're showing
Every diagram/visualization MUST include:
- **Title** (`<h1>` or `<h2>`): What this represents. Use user's own words.
- **Subtitle/description** (1-2 sentences below title): WHY this exists — what question it answers / decision it supports.
- **Source note** (small text at bottom): Where data came from. "Source: user-provided architecture description" or "Generated from: [user's document name]"

---

## Design Token Contract (enforced, not optional)

Visual identity = **design tokens** — CSS custom properties, font stacks, color values, spacing rules from `CSS_PATTERNS.md`. HTML structure flexible; tokens are not.

### Enforced (MUST match spel report)

| Token | Value | Why |
|---|---|---|
| `--font-body` | `'Atkinson Hyperlegible', 'Segoe UI', sans-serif` | Body text readability |
| `--font-heading` | `'Manrope', 'Atkinson Hyperlegible', sans-serif` | Heading weight + character |
| `--font-mono` | `'IBM Plex Mono', ui-monospace, monospace` | Code, labels, metrics |
| `--accent` | `#b2652a` | Primary accent (brown, warm) |
| `--node-b` | `#1f8a5c` | Success / positive (green) |
| `--node-c` | `#0f766e` | Info / secondary (teal) |
| `--node-d` | `#b7791f` | Warning (yellow) |
| `--node-e` | `#c44536` | Error / critical (red) |
| `--radius-md` | `18px` | Card border-radius |
| Background | Warm radial gradients (brown + teal glow) | Signature atmosphere |
| Card depth | `backdrop-filter: blur(10px)`, soft shadows | Glass-like elevation |
| Label style | IBM Plex Mono, uppercase, pill-shaped, accent bg | Consistent categorization |

### Flexible (adapt to content)

- Page layout (single column, sidebar + main, full-width, grid)
- Section ordering + nesting
- Which CSS components to use (cards, tables, pipelines, Mermaid, charts)
- Number of sections + headings
- Collapsible sections, tabs, or flat layout
- Container max-width (900px good default, wider for dashboards)

### Every page MUST include

1. **Google Fonts block** — exact 3-family `<link>` from CSS_PATTERNS.md
2. **Full theme CSS** — copy `:root` + `@media (prefers-color-scheme: dark)` blocks from CSS_PATTERNS.md
3. **Background atmosphere** — body gradient from CSS_PATTERNS.md (warm radial)
4. **Title** — `<h1>` or equivalent, using user's own words
5. **Context text** — 1-2 sentences explaining WHAT + WHY
6. **Source attribution** — small text noting data provenance

---

## Content Type Guidance

Patterns, not rigid templates. Use CSS classes from `CSS_PATTERNS.md`, adapt layout to content. Design tokens above = contract; HTML structure = yours.

### Architecture Diagram (CSS Grid Cards)

Use `.ve-card` components in `.card-grid` layout. Each card = one user-described component.

**Per card:**
- `.ve-card__label` pill: component category ("FRONTEND", "DATABASE")
- Title: component name from user's input
- Body: 1-2 sentences describing what it does — ONLY from user's input
- Left-border accent (`--accent-a` through `--accent-e`) for visual categorization

Use `--i` CSS variable for staggered fade-in (`style="--i:0"`, `style="--i:1"`)

**Anti-hallucination check:** Count user's components. Card count MUST match.

### Architecture Diagram (Mermaid Topology)

Use `.mermaid-wrap` with zoom controls. One node per component user named — no invented nodes.

- Edge labels: only if user specified relationship type
- Subgraphs: only if user described logical groupings
- Below diagram: consider legend explaining colors/shapes — using user's data

### Flowchart / Pipeline

Mermaid in `.mermaid-wrap` for complex flows. For simple 3-4 step linear flows, consider `.pipeline` CSS layout from CSS_PATTERNS.md.

**Required:**
- One step per stage user described — no extra steps
- Decision diamonds only if user described conditional logic
- Edge labels only if user specified transitions

Consider pairing diagram with `.data-table` summary below listing step, description, inputs, outputs. Use "—" for unspecified fields.

### Data Table / Comparison

Use `.table-wrap` > `.table-scroll` > `.data-table` from CSS_PATTERNS.md.

- Column headers: EXACTLY user-specified
- Row count: EXACTLY user-provided
- Cell values: EXACTLY user-provided — no rounding, no summarizing
- `.status` pills for categorical values (match/gap/warn/info)

### Dashboard / Metrics

Use `.kpi-row` > `.kpi-card` from CSS_PATTERNS.md. ONLY metrics user provided.

**Color mapping for KPI values:**
- Green (`--node-b`): positive, growth, success
- Red (`--node-e`): negative, errors, failures
- Brown (`--accent`): neutral, totals, counts
- Yellow (`--node-d`): warnings, thresholds approaching

NEVER invent numbers. User gave 4 metrics → show exactly 4.

---

## Diagram Types

### Architecture / system diagrams
- Simple topology (< 10 elements): Mermaid `graph TD`
- Text-heavy (< 15 elements): CSS Grid cards with colored borders + monospace labels
- Complex (15+ elements): hybrid — simple Mermaid overview (5-8 nodes) + CSS Grid cards for details

### Flowcharts / Pipelines
Mermaid. Prefer `graph TD` over `graph LR` for complex diagrams.

### Data tables / comparisons
Real `<table>` elements. Wrap in scrollable container. Sticky `<thead>`. Alternating row backgrounds.

### Slide deck mode
Opt-in only — explicit user request. See SLIDE_PATTERNS.md for full slide engine.

## File Structure
Every diagram = single self-contained `.html` file. No external assets except CDN links (fonts, optional libraries).

## Quality Checks
- Squint test: blur eyes. Still perceive hierarchy?
- Swap test: generic dark theme fonts/colors → still distinguishable from template?
- Both themes: toggle OS light/dark. Both look intentional.
- No overflow: resize browser. No content clips. Every grid/flex child needs `min-width: 0`.
- Mermaid zoom controls: every `.mermaid-wrap` must have zoom + click-to-expand.
- **Design token check**: Atkinson Hyperlegible / Manrope / IBM Plex Mono + brown accent? If not, fix.
- **Content fidelity**: Every text traces to user's input? If not, remove.

## Anti-Patterns (AI Slop)
- Inter/Roboto as primary font → use Atkinson Hyperlegible / Manrope / IBM Plex Mono
- Indigo/violet accents (`#8b5cf6`, `#7c3aed`) → use warm earth tones from CSS_PATTERNS.md
- Gradient text on headings (`background-clip: text`)
- Animated glowing box-shadows
- Emoji icons in section headers
- All cards styled identically with no visual hierarchy
- **Invented metrics/statistics user didn't provide**
- **Extra components/nodes user didn't mention**
- **Generic placeholder text: "Lorem ipsum" / "Description goes here"**