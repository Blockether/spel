<!-- Adapted from visual-explainer (MIT License, github.com/nicobailon/visual-explainer) -->
# Presenter Reference

Generate self-contained HTML files for technical diagrams, visualizations, and data tables. Use `spel open` to preview and `spel screenshot` to capture evidence.

> **Design system**: ALL output MUST use the **spel report design system** from `CSS_PATTERNS.md` — Atkinson Hyperlegible / Manrope / IBM Plex Mono, warm earth tones (#b2652a brown accent). Do NOT invent your own color palette or font stack.

## Workflow

### 1. Think (5 seconds)
Before writing HTML, commit to a direction.

Who is looking? Developer understanding a system? PM seeing the big picture? This shapes information density.

What type of content? Architecture, flowchart, sequence, data flow, schema/ER, state machine, mind map, class diagram, C4 architecture, data table, timeline, dashboard, or slide deck.

What aesthetic? The **default is always the spel brand** (warm earth tones from CSS_PATTERNS.md). Only deviate if the user explicitly requests a different aesthetic:
- Blueprint (technical drawing feel, deep slate/blue palette, monospace labels)
- Editorial (serif headlines, generous whitespace, muted earth tones)
- Paper/ink (warm cream background, terracotta/sage accents)
- Monochrome terminal (green/amber on near-black)
- IDE-inspired (commit to a real named scheme: Dracula, Nord, Catppuccin, Solarized, Gruvbox)

Forbidden aesthetics:
- Neon dashboard (cyan + magenta + purple on dark) — always produces AI slop
- Gradient mesh (pink/purple/cyan blobs)
- Inter font + violet/indigo accents + gradient text

### 2. Structure
Choose rendering approach:

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

Mermaid theming: always use `theme: 'base'` with custom `themeVariables`. Never use built-in themes — they ignore variable overrides.

Mermaid containers: always center with `display: flex; justify-content: center;`. Add zoom controls (+/−/reset/expand) to every `.mermaid-wrap`.

### 3. Style
- Typography: Use the **spel report font stack** from CSS_PATTERNS.md (Atkinson Hyperlegible / Manrope / IBM Plex Mono). Do NOT substitute other fonts unless user explicitly requests it.
- Color: Copy the **exact CSS custom properties** from CSS_PATTERNS.md theme setup. Use `--accent: #b2652a`, `--node-b: #1f8a5c`, `--node-c: #0f766e`, `--node-d: #b7791f`, `--node-e: #c44536`.
- Surfaces: Build depth through the `--surface`, `--surface-elevated`, `--bg-secondary` tiers defined in CSS_PATTERNS.md.
- Animation: staggered fade-ins on load. Respect `prefers-reduced-motion`. Forbidden: animated glowing box-shadows, pulsing effects on static content.

### 4. Deliver
Output location: write to `$(pwd)/spel-visual/` (ALWAYS absolute path — the daemon's CWD is fixed at startup). Use descriptive filenames: `architecture.html`, `pipeline-flow.html`.

Preview in browser:
```bash
spel open $(pwd)/spel-visual/filename.html
```

Capture as evidence:
```bash
spel screenshot $(pwd)/spel-visual/filename.png
```

Tell the user the file path so they can re-open or share it.

---

## Content Specification Protocol (ANTI-HALLUCINATION)

**This section prevents you from hallucinating content. Follow it exactly.**

### Rule 1: Only use information the user provided
- NEVER invent metric values, statistics, percentages, or counts
- NEVER fabricate component names, API endpoints, or file paths the user didn't mention
- If the user said "3 services" — show exactly 3, not 4 or 5
- If you need a label but the user didn't provide one, use a generic placeholder like `[Service Name]` and note it in the output

### Rule 2: Every text slot must be filled intentionally
For every text element in the HTML, you must be able to answer: "Where did this text come from?"
- **From the user's input** — exact quote or close paraphrase ✅
- **Structural label** — "Overview", "Details", "Pipeline", "Step 1" ✅
- **Made up because it looked good** — ❌ NEVER

### Rule 3: Describe what you're showing
Every diagram or visualization MUST include:
- **Title** (`<h1>` or `<h2>`): What this visualization represents. Use the user's own words.
- **Subtitle/description** (1-2 sentences below title): WHY this visualization exists — what question it answers or what decision it supports.
- **Source note** (small text at bottom): Where the data came from. Example: "Source: user-provided architecture description" or "Generated from: [user's document name]"

---

## Design Token Contract (enforced, not optional)

The visual identity is defined by **design tokens** — CSS custom properties, font stacks, color values, and spacing rules from `CSS_PATTERNS.md`. The HTML structure is flexible; the tokens are not.

### What is enforced (MUST match spel report)

| Token | Value | Why |
|---|---|---|
| `--font-body` | `'Atkinson Hyperlegible', 'Segoe UI', sans-serif` | Body text readability |
| `--font-heading` | `'Manrope', 'Atkinson Hyperlegible', sans-serif` | Heading weight and character |
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

### What is flexible (adapt to content)

- Page layout (single column, sidebar + main, full-width, grid)
- Section ordering and nesting
- Which CSS components to use (cards, tables, pipelines, Mermaid, charts)
- Number of sections and their headings
- Whether to use collapsible sections, tabs, or flat layout
- Container max-width (900px is a good default, wider for dashboards)

### What every page MUST include (regardless of layout)

1. **Google Fonts block** — the exact 3-family `<link>` from CSS_PATTERNS.md
2. **Full theme CSS** — copy the `:root` and `@media (prefers-color-scheme: dark)` blocks from CSS_PATTERNS.md
3. **Background atmosphere** — the body gradient from CSS_PATTERNS.md (warm radial)
4. **A title** — `<h1>` or equivalent, using the user's own words
5. **Context text** — at least 1-2 sentences explaining WHAT this visualization shows and WHY
6. **Source attribution** — small text somewhere on the page noting where the data came from

---

## Content Type Guidance

These are patterns, not rigid templates. Use the CSS classes from `CSS_PATTERNS.md` and adapt the layout to suit the content. The design tokens above are the contract — the HTML structure is yours to choose.

### Architecture Diagram (CSS Grid Cards)

Use `.ve-card` components in a `.card-grid` layout. Each card represents one user-described component.

**Per card, include:**
- A `.ve-card__label` pill: component category (e.g., "FRONTEND", "DATABASE")
- A title: component name from user's input
- A body: 1-2 sentences describing what it does — ONLY from user's input
- A left-border accent (`--accent-a` through `--accent-e`) to visually categorize

Use `--i` CSS variable for staggered fade-in animation (e.g., `style="--i:0"`, `style="--i:1"`)

**Anti-hallucination check:** Count the user's components. Your card count MUST match exactly.

### Architecture Diagram (Mermaid Topology)

Use `.mermaid-wrap` with zoom controls. One node per component the user named — no invented nodes.

- Edge labels: only if user specified the relationship type
- Subgraphs: only if user described logical groupings
- Below the diagram: consider a legend explaining colors/shapes — using data from the user's input

### Flowchart / Pipeline

Use Mermaid in a `.mermaid-wrap` for complex flows. For simple 3-4 step linear flows, consider the `.pipeline` CSS layout from CSS_PATTERNS.md instead.

**Required:**
- One step per stage the user described — no extra steps
- Decision diamonds only if the user described conditional logic
- Edge labels only if user specified transitions

Consider pairing the diagram with a `.data-table` summary below that lists each step, its description, inputs, and outputs. Use "—" for fields the user didn't specify.

### Data Table / Comparison

Use `.table-wrap` > `.table-scroll` > `.data-table` from CSS_PATTERNS.md.

- Column headers: EXACTLY what the user specified
- Row count: EXACTLY what the user provided
- Cell values: EXACTLY what the user provided — no rounding, no summarizing
- Use `.status` pills for categorical values (match/gap/warn/info)

### Dashboard / Metrics

Use `.kpi-row` > `.kpi-card` from CSS_PATTERNS.md. ONLY metrics the user provided.

**Color mapping for KPI values:**
- Green (`--node-b`): positive metrics, growth, success counts
- Red (`--node-e`): negative metrics, errors, failures
- Brown (`--accent`): neutral metrics, totals, counts
- Yellow (`--node-d`): warnings, thresholds approaching

NEVER invent numbers. If the user gave you 4 metrics, show exactly 4.

---

## Diagram Types

### Architecture / system diagrams
- Simple topology (< 10 elements): Mermaid `graph TD`
- Text-heavy (< 15 elements): CSS Grid cards with colored borders and monospace labels
- Complex (15+ elements): hybrid — simple Mermaid overview (5-8 nodes) + CSS Grid cards for details

### Flowcharts / Pipelines
Use Mermaid. Prefer `graph TD` (top-down) over `graph LR` (left-to-right) for complex diagrams.

### Data tables / comparisons
Use real `<table>` elements. Wrap in scrollable container. Sticky `<thead>`. Alternating row backgrounds.

### Slide deck mode
Opt-in only — when user explicitly requests slides. See SLIDE_PATTERNS.md for the full slide engine.

## File Structure
Every diagram is a single self-contained `.html` file. No external assets except CDN links (fonts, optional libraries).

## Quality Checks
- Squint test: blur your eyes. Can you still perceive hierarchy?
- Swap test: would replacing fonts/colors with a generic dark theme make this indistinguishable from a template?
- Both themes: toggle OS between light and dark. Both should look intentional.
- No overflow: resize browser. No content should clip. Every grid/flex child needs `min-width: 0`.
- Mermaid zoom controls: every `.mermaid-wrap` must have zoom controls and click-to-expand.
- **Design token check**: Does the output use Atkinson Hyperlegible / Manrope / IBM Plex Mono and the brown accent palette? If not, fix it.
- **Content fidelity**: Does every piece of text trace back to the user's input? If not, remove it.

## Anti-Patterns (AI Slop)
- Inter/Roboto as primary font — use Atkinson Hyperlegible / Manrope / IBM Plex Mono
- Indigo/violet accents (`#8b5cf6`, `#7c3aed`) — use warm earth tones from CSS_PATTERNS.md
- Gradient text on headings (`background-clip: text`)
- Animated glowing box-shadows
- Emoji icons in section headers
- All cards styled identically with no visual hierarchy
- **Invented metrics or statistics the user didn't provide**
- **Extra components or nodes the user didn't mention**
- **Generic placeholder text like "Lorem ipsum" or "Description goes here"**
