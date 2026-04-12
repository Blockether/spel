# CSS patterns for diagrams

**Canonical design system.** All presenter output uses these fonts, colors, and patterns unless the user explicitly asks for a different aesthetic. Reference implementation: `spel-report.html`.

Warm earth tones · Atkinson Hyperlegible / Manrope / IBM Plex Mono · rounded cards with soft shadows · light + dark via `prefers-color-scheme`.

## Google Fonts (required)

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:ital,wght@0,400;0,700;1,400;1,700&display=swap" rel="stylesheet">
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;600;700;800&display=swap" rel="stylesheet">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600;700&display=swap" rel="stylesheet">
```

No substitutions.

## Theme tokens

```css
:root {
  --font-body:    'Atkinson Hyperlegible', 'Segoe UI', sans-serif;
  --font-heading: 'Manrope', 'Atkinson Hyperlegible', sans-serif;
  --font-mono:    'IBM Plex Mono', ui-monospace, monospace;

  --bg: #f6f1e8;
  --bg-secondary: rgba(255, 251, 245, 0.88);
  --surface: rgba(255, 255, 255, 0.94);
  --surface-elevated: rgba(255, 255, 255, 0.94);
  --border: rgba(125, 99, 68, 0.18);
  --border-bright: rgba(125, 99, 68, 0.34);
  --text: #1f2933;
  --text-dim: #55606e;

  --accent: #b2652a;
  --accent-dim: rgba(178, 101, 42, 0.12);
  --node-a: #b2652a; --node-a-dim: rgba(178, 101, 42, 0.12);  /* brown  — primary */
  --node-b: #1f8a5c; --node-b-dim: rgba(31, 138, 92, 0.12);   /* green  — success  */
  --node-c: #0f766e; --node-c-dim: rgba(15, 118, 110, 0.12);  /* teal   — info     */
  --node-d: #b7791f; --node-d-dim: rgba(183, 121, 31, 0.12);  /* yellow — warning  */
  --node-e: #c44536; --node-e-dim: rgba(196, 69, 54, 0.12);   /* red    — critical */

  --shadow:      0 18px 42px rgba(43, 33, 22, 0.08);
  --shadow-soft: 0 10px 24px rgba(43, 33, 22, 0.05);
  --radius-lg: 24px; --radius-md: 18px; --radius-sm: 10px;
}

@media (prefers-color-scheme: dark) {
  :root {
    --bg: #151a20;
    --bg-secondary: rgba(26, 32, 40, 0.88);
    --surface: rgba(24, 30, 38, 0.96);
    --surface-elevated: rgba(24, 30, 38, 0.96);
    --border: rgba(255, 255, 255, 0.1);
    --border-bright: rgba(255, 255, 255, 0.18);
    --text: #ecf1f7; --text-dim: #a9b7c8;
    --accent-dim:  rgba(178, 101, 42, 0.14);
    --node-a-dim: rgba(178, 101, 42, 0.14);
    --node-b-dim: rgba(31, 138, 92, 0.14);
    --node-c-dim: rgba(15, 118, 110, 0.14);
    --node-d-dim: rgba(183, 121, 31, 0.14);
    --node-e-dim: rgba(196, 69, 54, 0.14);
    --shadow:      0 22px 48px rgba(0, 0, 0, 0.32);
    --shadow-soft: 0 12px 28px rgba(0, 0, 0, 0.24);
  }
}
```

## Body atmosphere

```css
body {
  font-family: var(--font-body);
  font-size: 15px; line-height: 1.7; color: var(--text);
  background:
    radial-gradient(circle at top left,  rgba(178, 101, 42, 0.12), transparent 30%),
    radial-gradient(circle at top right, rgba(15, 118, 110, 0.10), transparent 28%),
    linear-gradient(180deg, #fbf7f1 0%, var(--bg) 48%, #efe7dc 100%);
  min-height: 100vh; -webkit-font-smoothing: antialiased;
}
@media (prefers-color-scheme: dark) {
  body {
    background:
      radial-gradient(circle at top left,  rgba(178, 101, 42, 0.14), transparent 24%),
      radial-gradient(circle at top right, rgba(15, 118, 110, 0.15), transparent 22%),
      linear-gradient(180deg, #12171d 0%, #151a20 52%, #1a212a 100%);
  }
}
```

## Headings

```css
h1, h2, h3, h4 { font-family: var(--font-heading); font-weight: 800; color: var(--text); line-height: 1.15; }
h1 { font-size: clamp(2rem, 5vw, 3.35rem); letter-spacing: -0.03em; }
h2 { font-size: 1.6rem;  margin-bottom: 1rem;    letter-spacing: -0.03em; }
h3 { font-size: 1.15rem; margin-bottom: 0.75rem; letter-spacing: -0.02em; }
h4 { font-size: 1rem;    margin-bottom: 0.5rem;  }
```

## Cards

**Never use `.node` as a class** — Mermaid.js owns it. Use `.ve-card`.

```css
.ve-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 1.3rem; position: relative;
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(10px);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.ve-card--accent-a { border-left: 4px solid var(--node-a); }
.ve-card--accent-b { border-left: 4px solid var(--node-b); }
.ve-card--accent-c { border-left: 4px solid var(--node-c); }
.ve-card--accent-d { border-left: 4px solid var(--node-d); }
.ve-card--accent-e { border-left: 4px solid var(--node-e); }

.ve-card--elevated { background: var(--surface-elevated); box-shadow: var(--shadow); }
.ve-card--recessed { background: color-mix(in srgb, var(--bg) 70%, var(--surface) 30%); box-shadow: inset 0 1px 3px rgba(0,0,0,0.06); }
.ve-card--hero     { background: color-mix(in srgb, var(--surface) 92%, var(--accent) 8%); box-shadow: var(--shadow); border-color: color-mix(in srgb, var(--border) 50%, var(--accent) 50%); }

.ve-card__label {
  font-family: var(--font-mono); font-size: 0.74rem; font-weight: 600;
  text-transform: uppercase; letter-spacing: 0.08em;
  color: var(--accent); margin-bottom: 10px;
  display: inline-flex; align-items: center; gap: 0.5rem;
  padding: 0.35rem 0.7rem; border-radius: 999px; background: var(--accent-dim);
}
.ve-card__title { font-family: var(--font-heading); font-size: 1.15rem; font-weight: 700; margin-bottom: 0.5rem; letter-spacing: -0.02em; }
.ve-card__body  { font-size: 0.9rem; color: var(--text-dim); line-height: 1.6; }
```

## Kicker

```css
.kicker {
  display: inline-flex; align-items: center; gap: 0.5rem;
  padding: 0.35rem 0.7rem; margin-bottom: 0.9rem;
  border-radius: 999px; background: var(--accent-dim); color: var(--accent);
  font-family: var(--font-mono); font-size: 0.74rem; letter-spacing: 0.08em; text-transform: uppercase;
}
```

## Pills / badges

```css
.pill {
  display: inline-flex; align-items: center; gap: 0.375rem;
  padding: 0.3rem 0.65rem; border-radius: 999px;
  font-family: var(--font-mono); font-size: 0.65rem; font-weight: 600;
  letter-spacing: 0.06em; text-transform: uppercase; border: 1px solid;
}
.pill--brown  { background: rgba(178, 101, 42, 0.1); color: var(--node-a); border-color: rgba(178, 101, 42, 0.2); }
.pill--green  { background: rgba(31, 138, 92, 0.1);  color: var(--node-b); border-color: rgba(31, 138, 92, 0.2); }
.pill--teal   { background: rgba(15, 118, 110, 0.1); color: var(--node-c); border-color: rgba(15, 118, 110, 0.2); }
.pill--yellow { background: rgba(183, 121, 31, 0.1); color: var(--node-d); border-color: rgba(183, 121, 31, 0.2); }
.pill--red    { background: rgba(196, 69, 54, 0.1);  color: var(--node-e); border-color: rgba(196, 69, 54, 0.2); }
```

## Code blocks

```css
.code-block {
  font-family: var(--font-mono); font-size: 13px; line-height: 1.5;
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 16px; overflow-x: auto;
  white-space: pre-wrap;  /* CRITICAL: preserve line breaks */
  word-break: break-word;
}
.code-block--scroll { max-height: 400px; overflow-y: auto; }

.code-file { border: 1px solid var(--border); border-radius: var(--radius-sm); overflow: hidden; }
.code-file__header {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; background: var(--surface); border-bottom: 1px solid var(--border);
  font-family: var(--font-mono); font-size: 12px; color: var(--text-dim);
}
.code-file__body {
  font-family: var(--font-mono); font-size: 13px; line-height: 1.5;
  padding: 16px; background: var(--surface-elevated);
  white-space: pre-wrap; word-break: break-word;
  max-height: 500px; overflow: auto;
}
```

## Mermaid containers

Every `.mermaid-wrap` has zoom controls.

```css
.mermaid-wrap {
  position: relative; background: var(--surface);
  border: 1px solid var(--border); border-radius: var(--radius-md);
  padding: 32px 24px; overflow: auto;
  display: flex; justify-content: center; align-items: center;
  min-height: 400px;
}
.mermaid-wrap .mermaid { zoom: 1.4; }

.zoom-controls {
  position: absolute; top: 8px; right: 8px;
  display: flex; gap: 2px; z-index: 10;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 6px; padding: 2px;
}
.zoom-controls button {
  width: 28px; height: 28px; border: none; background: transparent;
  color: var(--text-dim); font-family: var(--font-mono); font-size: 14px;
  cursor: pointer; border-radius: 4px;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.15s ease, color 0.15s ease;
}
.zoom-controls button:hover { background: var(--border); color: var(--text); }
```

```html
<div class="mermaid-wrap">
  <div class="zoom-controls">
    <button onclick="zoomDiagram(this, 1.2)" title="Zoom in">+</button>
    <button onclick="zoomDiagram(this, 0.8)" title="Zoom out">&minus;</button>
    <button onclick="resetZoom(this)"        title="Reset zoom">&#8634;</button>
    <button onclick="openDiagramFullscreen(this)" title="Full size">&#x26F6;</button>
  </div>
  <pre class="mermaid">graph TD
    A --> B</pre>
</div>
```

## Grid layouts

```css
/* 2-column architecture */
.arch-grid { display: grid; grid-template-columns: 260px 1fr; gap: 20px; max-width: 1100px; margin: 0 auto; }

/* auto-fit card grid */
.card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; }

/* horizontal pipeline */
.pipeline { display: flex; align-items: stretch; gap: 0; overflow-x: auto; }
.pipeline__step {
  min-width: 130px; flex-shrink: 0;
  background: var(--surface); border: 1px solid var(--border);
  border-top: 3px solid var(--accent);
  border-radius: var(--radius-sm); padding: 14px 12px;
}
.pipeline__arrow { display: flex; align-items: center; padding: 0 6px; color: var(--accent); flex-shrink: 0; opacity: 0.4; }
```

## Data tables

```css
.table-wrap { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; }
.table-scroll { overflow-x: auto; }

.data-table { width: 100%; border-collapse: collapse; font-size: 13px; line-height: 1.5; }
.data-table thead { position: sticky; top: 0; z-index: 2; }
.data-table th {
  background: var(--surface-elevated);
  font-family: var(--font-mono); font-size: 11px; font-weight: 600;
  text-transform: uppercase; letter-spacing: 1px; color: var(--text-dim);
  text-align: left; padding: 12px 16px;
  border-bottom: 2px solid var(--border-bright); white-space: nowrap;
}
.data-table td { padding: 12px 16px; border-bottom: 1px solid var(--border); vertical-align: top; color: var(--text); }
.data-table tbody tr:nth-child(even) { background: var(--accent-dim); }
.data-table tbody tr:hover            { background: rgba(178, 101, 42, 0.08); }
.data-table tbody tr:last-child td    { border-bottom: none; }
```

### Status indicators

```css
.status {
  display: inline-flex; align-items: center; gap: 6px;
  font-family: var(--font-mono); font-size: 11px; font-weight: 500;
  padding: 3px 10px; border-radius: 6px; white-space: nowrap;
}
.status--match { background: rgba(31,138,92,0.1); color: #1f8a5c; }
.status--gap   { background: rgba(196,69,54,0.1); color: #c44536; }
.status--warn  { background: rgba(183,121,31,0.1); color: #b7791f; }
.status--info  { background: var(--accent-dim);    color: var(--accent); }
```

## KPI cards

```css
.kpi-row  { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; }
.kpi-card {
  background: var(--surface-elevated); border: 1px solid var(--border);
  border-radius: var(--radius-md); padding: 20px; box-shadow: var(--shadow-soft);
}
.kpi-card__value { font-size: 36px; font-weight: 700; letter-spacing: -1px; line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-card__label {
  font-family: var(--font-mono); font-size: 10px; font-weight: 600;
  text-transform: uppercase; letter-spacing: 1.5px; color: var(--text-dim);
  margin-top: 6px;
}
```

## Animations

```css
@keyframes fadeUp { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }

.ve-card {
  animation: fadeUp 0.4s ease-out both;
  animation-delay: calc(var(--i, 0) * 0.05s);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}
.ve-card:hover { transform: translateY(-2px); box-shadow: var(--shadow); border-color: var(--border-bright); }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
}
```

## Overflow protection

```css
.grid > *, .flex > * { min-width: 0; }                       /* let children shrink */
body { overflow-wrap: break-word; }                          /* long text wraps */

li { padding-left: 14px; position: relative; }               /* never flex <li> for markers */
li::before { content: '›'; position: absolute; left: 0; }
```

## Collapsible sections

```css
details.collapsible { border: 1px solid var(--border); border-radius: var(--radius-sm); overflow: hidden; }
details.collapsible summary {
  padding: 14px 20px; background: var(--surface);
  font-family: var(--font-mono); font-size: 12px; font-weight: 600;
  cursor: pointer; list-style: none;
  display: flex; align-items: center; gap: 8px; color: var(--text);
}
details.collapsible summary::-webkit-details-marker { display: none; }
details.collapsible summary::before { content: '▸'; font-size: 11px; color: var(--text-dim); transition: transform 0.15s ease; }
details.collapsible[open] summary::before { transform: rotate(90deg); }
details.collapsible .collapsible__body { padding: 16px 20px; border-top: 1px solid var(--border); font-size: 13px; line-height: 1.6; }
```

## Responsive breakpoint

```css
@media (max-width: 768px) {
  .arch-grid { grid-template-columns: 1fr; }
  .pipeline { flex-wrap: wrap; gap: 8px; }
  .pipeline__arrow { display: none; }
  body { padding: 16px; }
}
```

## Capture

```bash
spel open $(pwd)/spel-visual/diagram.html
spel screenshot $(pwd)/spel-visual/diagram.png
```
