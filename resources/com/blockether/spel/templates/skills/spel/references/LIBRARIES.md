<!-- Adapted from visual-explainer (MIT License, github.com/nicobailon/visual-explainer) -->
# External Libraries (CDN)

Optional CDN libraries for when pure CSS/HTML isn't enough. Only include what diagram actually needs.

## Mermaid.js — Diagramming Engine

Use for flowcharts, sequence diagrams, ER diagrams, state machines, mind maps, class diagrams.

```html
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
  mermaid.initialize({ startOnLoad: true, /* ... */ });
</script>
```

### Deep Theming

Always use `theme: 'base'` — only theme where all `themeVariables` fully customizable.

```html
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';

  const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  mermaid.initialize({
    startOnLoad: true,
    theme: 'base',
    look: 'classic',
    themeVariables: {
      // Warm earth tones matching spel report design system
      primaryColor: isDark ? '#3d2514' : '#fdf0e6',
      primaryBorderColor: isDark ? '#b2652a' : '#b2652a',
      primaryTextColor: isDark ? '#ecf1f7' : '#1f2933',
      secondaryColor: isDark ? '#1a2520' : '#f0fdf4',
      secondaryBorderColor: isDark ? '#1f8a5c' : '#1f8a5c',
      tertiaryColor: isDark ? '#1a2028' : '#f0f8ff',
      tertiaryBorderColor: isDark ? '#0f766e' : '#0f766e',
      lineColor: isDark ? '#a9b7c8' : '#55606e',
      fontSize: '16px',
      fontFamily: 'var(--font-body)',
    }
  });
</script>
```

**FORBIDDEN in themeVariables:** `#8b5cf6`, `#7c3aed`, `#a78bfa` (indigo/violet), `#d946ef` (fuchsia).

### CSS Overrides on Mermaid SVG

```css
.mermaid .nodeLabel { color: var(--text) !important; }
.mermaid .edgeLabel { color: var(--text-dim) !important; background-color: var(--bg) !important; }
.mermaid .edgeLabel rect { fill: var(--bg) !important; }
.mermaid .node rect, .mermaid .node circle, .mermaid .node polygon { stroke-width: 1.5px; }
.mermaid .nodeLabel { font-family: var(--font-body) !important; font-size: 16px !important; }
.mermaid .edgeLabel { font-family: var(--font-mono) !important; font-size: 13px !important; }
```

### classDef Gotchas

1. **Never set `color:` in classDef** — hardcodes text color, breaks in opposite color scheme.
2. **Use semi-transparent fills** for node backgrounds:
   ```
   classDef highlight fill:#b5761433,stroke:#b57614,stroke-width:2px
   ```

### Writing Valid Mermaid

- `<br/>` for multi-line labels (not `\n`)
- Quote labels with special chars: `A["handleRequest(ctx)"]`
- Keep IDs simple (alphanumeric, no spaces)
- Max 10-12 nodes per diagram — hybrid pattern for more
- Prefer `flowchart TD` over `flowchart LR` for complex diagrams

### Layout Direction: TD vs LR

| Direction | Use when |
|-----------|----------|
| `TD` (top-down) | Complex diagrams, 5+ nodes, hierarchies |
| `LR` (left-to-right) | Simple linear flows, 3-4 nodes |

### Diagram Type Quick Reference

| Show... | Use |
|---|---|
| Process flow, decisions | `graph TD` |
| Request/response, API calls | `sequenceDiagram` |
| Database tables | `erDiagram` |
| OOP classes | `classDiagram` |
| System architecture (C4) | `graph TD` + `subgraph` (NOT native `C4Context`) |
| State transitions | `stateDiagram-v2` |
| Hierarchical breakdowns | `mindmap` |

### Dark Mode Handling

```javascript
const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
// Use isDark to pick light or dark values in themeVariables
```

## Chart.js — Data Visualizations

Use for bar charts, line charts, pie/doughnut charts in dashboards.

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"></script>

<canvas id="myChart" width="600" height="300"></canvas>

<script>
  const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const textColor = isDark ? '#8b949e' : '#6b7280';
  const gridColor = isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)';
  const fontFamily = getComputedStyle(document.documentElement)
    .getPropertyValue('--font-body').trim() || 'system-ui, sans-serif';

  new Chart(document.getElementById('myChart'), {
    type: 'bar',
    data: {
      labels: ['Jan', 'Feb', 'Mar'],
      datasets: [{
        label: 'Items',
        data: [45, 62, 78],
        backgroundColor: isDark ? 'rgba(129,140,248,0.6)' : 'rgba(79,70,229,0.6)',
        borderColor: isDark ? '#818cf8' : '#4f46e5',
        borderWidth: 1,
        borderRadius: 4,
      }]
    },
    options: {
      responsive: true,
      plugins: {
        legend: { labels: { color: textColor, font: { family: fontFamily } } },
      },
      scales: {
        x: { ticks: { color: textColor, font: { family: fontFamily } }, grid: { color: gridColor } },
        y: { ticks: { color: textColor, font: { family: fontFamily } }, grid: { color: gridColor } },
      }
    }
  });
</script>
```

## anime.js — Orchestrated Animations

Use when diagram has 10+ elements + you want choreographed entrance sequence.

```html
<script src="https://cdn.jsdelivr.net/npm/animejs@3.2.2/lib/anime.min.js"></script>

<script>
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  if (!prefersReduced) {
    anime({
      targets: '.ve-card',
      opacity: [0, 1],
      translateY: [20, 0],
      delay: anime.stagger(80, { start: 200 }),
      easing: 'easeOutCubic',
      duration: 500,
    });
  }
</script>
```

When using anime.js, set initial opacity to 0 in CSS:
```css
.ve-card { opacity: 0; }
@media (prefers-reduced-motion: reduce) { .ve-card { opacity: 1 !important; } }
```

## Google Fonts — Typography

Always load with `display=swap`.

**Default (spel brand):** Atkinson Hyperlegible + Manrope + IBM Plex Mono. Use unless user explicitly requests different aesthetic.

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400;1,700&display=swap" rel="stylesheet">
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&display=swap" rel="stylesheet">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600;700&display=swap" rel="stylesheet">
```

```css
:root {
  --font-body: 'Atkinson Hyperlegible', 'Segoe UI', sans-serif;
  --font-heading: 'Manrope', 'Atkinson Hyperlegible', sans-serif;
  --font-mono: 'IBM Plex Mono', ui-monospace, monospace;
}
```

**FORBIDDEN as `--font-body`:** Inter, Roboto, Arial, Helvetica, system-ui alone.

### Alternative pairings (non-default aesthetics only)

### Font Pairings (rotate — never same pairing twice in a row)

| Body / Headings | Mono / Labels | Feel |
|---|---|---|
| DM Sans | Fira Code | Friendly, developer |
| Instrument Serif | JetBrains Mono | Editorial, refined |
| IBM Plex Sans | IBM Plex Mono | Reliable, readable |
| Bricolage Grotesque | Fragment Mono | Bold, characterful |
| Plus Jakarta Sans | Azeret Mono | Rounded, approachable |
| Outfit | Space Mono | Clean geometric, modern |
| Sora | IBM Plex Mono | Technical, precise |
| Crimson Pro | Noto Sans Mono | Scholarly, serious |
| Fraunces | Source Code Pro | Warm, distinctive |
| Geist | Geist Mono | Vercel-inspired, sharp |
| Red Hat Display | Red Hat Mono | Cohesive family |
| Libre Franklin | Inconsolata | Classic, reliable |
| Playfair Display | Roboto Mono | Elegant contrast |

### Typography by Content Voice

| Voice | Fonts | Best For |
|-------|-------|----------|
| Literary / Thoughtful | Literata, Lora, Newsreader | Essays, long-form articles |
| Technical / Precise | IBM Plex Sans + Mono, Geist + Geist Mono | Documentation, READMEs |
| Bold / Contemporary | Bricolage Grotesque, Space Grotesk | Product pages, announcements |
| Minimal / Focused | Source Serif 4 + Source Sans 3 | Tutorials, how-tos |