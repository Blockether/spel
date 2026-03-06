<!-- Adapted from visual-explainer (MIT License, github.com/nicobailon/visual-explainer) -->
# Presenter Reference

Generate self-contained HTML files for technical diagrams, visualizations, and data tables. Use `spel open` to preview and `spel screenshot` to capture evidence.

## Workflow

### 1. Think (5 seconds)
Before writing HTML, commit to a direction.

Who is looking? Developer understanding a system? PM seeing the big picture? This shapes information density.

What type of content? Architecture, flowchart, sequence, data flow, schema/ER, state machine, mind map, class diagram, C4 architecture, data table, timeline, dashboard, or slide deck.

What aesthetic? Pick one and commit:
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
- Typography: pick a distinctive font pairing from LIBRARIES.md. Forbidden as `--font-body`: Inter, Roboto, Arial, Helvetica, system-ui alone.
- Color: use CSS custom properties. Define `--bg`, `--surface`, `--border`, `--text`, `--text-dim`, and 3-5 accent colors. Forbidden accents: `#8b5cf6` `#7c3aed` (indigo/violet), `#d946ef` (fuchsia), cyan-magenta-pink combination.
- Surfaces: build depth through subtle lightness shifts (2-4% between levels). Borders: low-opacity rgba.
- Animation: staggered fade-ins on load. Respect `prefers-reduced-motion`. Forbidden: animated glowing box-shadows, pulsing effects on static content.

### 4. Deliver
Output location: write to `./spel-visual/` by default. Use descriptive filenames: `architecture.html`, `pipeline-flow.html`.

Preview in browser:
```bash
spel open ./spel-visual/filename.html
```

Capture as evidence:
```bash
spel screenshot ./spel-visual/filename.png
```

Tell the user the file path so they can re-open or share it.

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

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Descriptive Title</title>
  <link href="https://fonts.googleapis.com/css2?family=...&display=swap" rel="stylesheet">
  <style>/* All CSS inline */</style>
</head>
<body>
  <!-- Semantic HTML: sections, headings, lists, tables, inline SVG -->
  <!-- Optional: <script> for Mermaid, Chart.js when used -->
</body>
</html>
```

## Quality Checks
- Squint test: blur your eyes. Can you still perceive hierarchy?
- Swap test: would replacing fonts/colors with a generic dark theme make this indistinguishable from a template?
- Both themes: toggle OS between light and dark. Both should look intentional.
- No overflow: resize browser. No content should clip. Every grid/flex child needs `min-width: 0`.
- Mermaid zoom controls: every `.mermaid-wrap` must have zoom controls and click-to-expand.

## Anti-Patterns (AI Slop)
- Inter/Roboto as primary font
- Indigo/violet accents (`#8b5cf6`, `#7c3aed`)
- Gradient text on headings (`background-clip: text`)
- Animated glowing box-shadows
- Emoji icons in section headers
- All cards styled identically with no visual hierarchy
