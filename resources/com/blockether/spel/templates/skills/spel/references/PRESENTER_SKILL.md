<!-- Adapted from visual-explainer (MIT, github.com/nicobailon/visual-explainer) -->
# Presenter reference

Generate self-contained HTML for technical diagrams, visualizations, data tables. `spel open` to preview, `spel screenshot` to capture evidence.

> **Design system.** All output uses the **spel report design system** from `CSS_PATTERNS.md` — Atkinson Hyperlegible / Manrope / IBM Plex Mono, warm earth tones (`#b2652a` brown accent). Do not invent a new palette or font stack.

## Workflow

### 1. Think (5 s)

- **Who's looking?** Dev understanding a system? PM seeing the big picture? → shapes density.
- **What type?** Architecture, flowchart, sequence, data flow, schema/ER, state machine, mind map, class diagram, C4, data table, timeline, dashboard, slide deck.
- **What aesthetic?** Default = spel brand. Only deviate on explicit user request: Blueprint (deep slate/blue + mono labels), Editorial (serif headlines + muted earth), Paper/ink (cream + terracotta/sage), Monochrome terminal (green/amber on near-black), IDE-inspired (Dracula / Nord / Catppuccin / Solarized / Gruvbox).

**Forbidden** aesthetics: neon dashboard (cyan + magenta + purple on dark), gradient mesh (pink/purple/cyan blobs), Inter + violet/indigo + gradient text.

### 2. Structure

| Content | Approach |
|---------|----------|
| Architecture (text-heavy) | CSS Grid cards + flow arrows |
| Architecture (topology-focused) | Mermaid `graph TD` |
| Flowchart / pipeline | Mermaid (or `.pipeline` CSS for simple linear) |
| Sequence | Mermaid `sequenceDiagram` |
| Data flow | Mermaid with edge labels |
| ER / schema | Mermaid `erDiagram` |
| State machine | Mermaid `stateDiagram-v2` |
| Mind map | Mermaid `mindmap` |
| Class diagram | Mermaid `classDiagram` |
| C4 | Mermaid `graph TD` + `subgraph` (not native C4Context) |
| Data table | HTML `<table>` |
| Dashboard | CSS Grid + Chart.js |
| Slide deck | Scroll-snap slides — see `SLIDE_PATTERNS.md` |

Mermaid: always `theme: 'base'` with custom `themeVariables` (built-in themes ignore overrides). Always center with `display: flex; justify-content: center;` and add zoom controls to every `.mermaid-wrap`.

### 3. Style

- **Typography** — spel font stack from `CSS_PATTERNS.md` (Atkinson Hyperlegible / Manrope / IBM Plex Mono). Never substitute unless user asks.
- **Color** — copy CSS custom properties verbatim. Accent `#b2652a`; semantic `--node-b … --node-e`.
- **Surfaces** — build depth with `--surface`, `--surface-elevated`, `--bg-secondary`.
- **Animation** — staggered fade-ins; respect `prefers-reduced-motion`. No glowing/pulsing effects on static content.

### 4. Deliver

Output path: always **absolute** (daemon CWD is fixed at startup).

```bash
# Preview
spel open $(pwd)/spel-visual/filename.html
# Capture
spel screenshot $(pwd)/spel-visual/filename.png
```

Tell the user the path for re-open / sharing.

## Content spec protocol (anti-hallucination)

**Rule 1 — Only user-provided information.** Never invent metrics, counts, component names, API endpoints, or file paths. User said "3 services" → show exactly 3. Missing label → `[Service Name]` placeholder, note it.

**Rule 2 — Every text slot intentional.** For each text element, answer "Where did this come from?"
- User's input (exact quote or close paraphrase) ✅
- Structural label ("Overview", "Step 1") ✅
- Made up because it looked good ❌

**Rule 3 — Describe what you're showing.** Every diagram needs:
- **Title** (`<h1>`/`<h2>`) — what this represents, in user's own words.
- **Subtitle** (1–2 sentences) — why this exists / what question it answers.
- **Source note** (footer) — where the data came from.

## Design token contract (enforced)

Enforced tokens (must match spel report):

| Token | Value | Why |
|-------|-------|-----|
| `--font-body` | `'Atkinson Hyperlegible', 'Segoe UI', sans-serif` | Body readability |
| `--font-heading` | `'Manrope', 'Atkinson Hyperlegible', sans-serif` | Heading character |
| `--font-mono` | `'IBM Plex Mono', ui-monospace, monospace` | Code / labels / metrics |
| `--accent` | `#b2652a` | Primary accent (brown) |
| `--node-b / -c / -d / -e` | `#1f8a5c / #0f766e / #b7791f / #c44536` | Success / info / warning / error |
| `--radius-md` | `18px` | Card border-radius |
| Background | Warm radial gradient (brown + teal glow) | Signature atmosphere |
| Card depth | `backdrop-filter: blur(10px)` + soft shadows | Glass-like elevation |
| Label style | IBM Plex Mono, uppercase, pill-shaped, accent bg | Consistent categorization |

Flexible: page layout, section ordering, component choice (cards / tables / pipelines / Mermaid / charts), section count, flat vs tabs / collapsible, container max-width.

Every page must include: Google Fonts block, full theme CSS (`:root` + dark media query), body gradient, `<h1>`, 1–2 sentence context text, source attribution.

## Content type guidance

Patterns, not rigid templates. Tokens are the contract; HTML is yours.

### Architecture — CSS Grid cards

`.ve-card` in `.card-grid`. One card per component. Pill for category, user-provided title, 1–2 sentence body from user input, left-border accent for visual categorization. Stagger fade-ins with `--i` (`style="--i:0"`, `style="--i:1"`, …). Card count must match user's component count.

### Architecture — Mermaid topology

`.mermaid-wrap` + zoom controls. One node per component user named. Edge labels only if user specified; subgraphs only if user described groupings; below diagram consider a legend.

### Flowchart / pipeline

Mermaid for complex; `.pipeline` CSS for simple 3–4 step linear flows. One step per stage the user described. Decision diamonds only for explicit conditional logic. Consider pairing diagram with a `.data-table` summary (step, description, inputs, outputs; "—" for unspecified).

### Data table / comparison

`.table-wrap > .table-scroll > .data-table`. Headers, row count, cell values exactly as user provided — no rounding, no summarizing. `.status` pills for categorical values (`match` / `gap` / `warn` / `info`).

### Dashboard / metrics

`.kpi-row > .kpi-card`. Only user-provided metrics. Color mapping: green (`--node-b`) positive / growth; red (`--node-e`) negative / failure; brown (`--accent`) neutral / totals; yellow (`--node-d`) warning / threshold.

## Diagram type sizing

- Architecture < 10 elements → Mermaid `graph TD`
- Text-heavy < 15 → CSS Grid cards
- 15+ → hybrid: Mermaid overview (5–8 nodes) + CSS Grid cards for detail
- Simple linear pipelines → CSS `.pipeline`

Prefer `graph TD` over `graph LR` for complex diagrams.

## Quality checks

- **Squint test** — hierarchy still perceptible when blurred?
- **Swap test** — generic dark theme / generic fonts → still distinguishable from template?
- **Both themes** — toggle OS light/dark. Both look intentional.
- **No overflow** — resize. Every grid/flex child has `min-width: 0`.
- **Mermaid zoom controls** on every `.mermaid-wrap`.
- **Design token check** — Atkinson Hyperlegible / Manrope / IBM Plex Mono + brown accent.
- **Content fidelity** — every text traces to user input.

## Anti-patterns (AI slop)

- Inter / Roboto as primary font → use Atkinson Hyperlegible / Manrope / IBM Plex Mono.
- Indigo / violet accents (`#8b5cf6`, `#7c3aed`) → use warm earth tones.
- Gradient text on headings (`background-clip: text`).
- Animated glowing box-shadows.
- Emoji icons in section headers.
- All cards styled identically with no visual hierarchy.
- Invented metrics, extra components, generic placeholder text ("Lorem ipsum", "Description goes here").
