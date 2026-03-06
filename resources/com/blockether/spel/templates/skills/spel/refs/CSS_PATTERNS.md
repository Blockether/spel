
# CSS patterns for diagrams

Copy-paste CSS for HTML diagrams. Uses custom properties for automatic light/dark theming.

## Theme setup

Light and dark palettes — copy the whole block.

```css
:root {
  --font-body: 'Outfit', system-ui, sans-serif;
  --font-mono: 'Space Mono', 'SF Mono', Consolas, monospace;
  --bg: #f8f9fa;
  --surface: #ffffff;
  --surface-elevated: #ffffff;
  --border: rgba(0, 0, 0, 0.08);
  --border-bright: rgba(0, 0, 0, 0.15);
  --text: #1a1a2e;
  --text-dim: #6b7280;
  --accent: #0891b2;
  --accent-dim: rgba(8, 145, 178, 0.1);
  --node-a: #0891b2;
  --node-a-dim: rgba(8, 145, 178, 0.1);
  --node-b: #059669;
  --node-b-dim: rgba(5, 150, 105, 0.1);
  --node-c: #d97706;
  --node-c-dim: rgba(217, 119, 6, 0.1);
}

@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0d1117;
    --surface: #161b22;
    --surface-elevated: #1c2333;
    --border: rgba(255, 255, 255, 0.06);
    --border-bright: rgba(255, 255, 255, 0.12);
    --text: #e6edf3;
    --text-dim: #8b949e;
    --accent: #22d3ee;
    --accent-dim: rgba(34, 211, 238, 0.12);
    --node-a: #22d3ee;
    --node-a-dim: rgba(34, 211, 238, 0.12);
    --node-b: #34d399;
    --node-b-dim: rgba(52, 211, 153, 0.12);
    --node-c: #fbbf24;
    --node-c-dim: rgba(251, 191, 36, 0.12);
  }
}
```

## Background atmosphere

Radial glow or dot grid behind the focal area.

```css
/* Radial glow behind focal area */
body {
  background: var(--bg);
  background-image: radial-gradient(ellipse at 50% 0%, var(--accent-dim) 0%, transparent 60%);
}

/* Faint dot grid */
body {
  background-color: var(--bg);
  background-image: radial-gradient(circle, var(--border) 1px, transparent 1px);
  background-size: 24px 24px;
}
```

## Card components

**NEVER use `.node` as a class name.** Mermaid.js owns it. Use `.ve-card` instead.

```css
.ve-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 20px;
  position: relative;
}

.ve-card--accent-a { border-left: 3px solid var(--node-a); }

/* Depth tiers */
.ve-card--elevated {
  background: var(--surface-elevated);
  box-shadow: 0 2px 8px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04);
}

.ve-card--recessed {
  background: color-mix(in srgb, var(--bg) 70%, var(--surface) 30%);
  box-shadow: inset 0 1px 3px rgba(0,0,0,0.06);
}

.ve-card--hero {
  background: color-mix(in srgb, var(--surface) 92%, var(--accent) 8%);
  box-shadow: 0 4px 20px rgba(0,0,0,0.08), 0 1px 3px rgba(0,0,0,0.04);
  border-color: color-mix(in srgb, var(--border) 50%, var(--accent) 50%);
}

.ve-card__label {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  color: var(--node-a);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ve-card__label::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}
```

## Code blocks

Monospace code display with optional scroll and file header.

```css
.code-block {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.5;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
  white-space: pre-wrap; /* CRITICAL: preserve line breaks */
  word-break: break-word;
}

.code-block--scroll {
  max-height: 400px;
  overflow-y: auto;
}

.code-file {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: hidden;
}

.code-file__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-dim);
}

.code-file__body {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.5;
  padding: 16px;
  background: var(--surface-elevated);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 500px;
  overflow: auto;
}
```

## Mermaid containers

Centered diagrams with zoom controls on every `.mermaid-wrap`.

```css
.mermaid-wrap {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 32px 24px;
  overflow: auto;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 400px;
}

.mermaid-wrap .mermaid { zoom: 1.4; }

.zoom-controls {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  gap: 2px;
  z-index: 10;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px;
}

.zoom-controls button {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--text-dim);
  font-family: var(--font-mono);
  font-size: 14px;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease, color 0.15s ease;
}

.zoom-controls button:hover {
  background: var(--border);
  color: var(--text);
}
```

HTML for zoom controls:
```html
<div class="mermaid-wrap">
  <div class="zoom-controls">
    <button onclick="zoomDiagram(this, 1.2)" title="Zoom in">+</button>
    <button onclick="zoomDiagram(this, 0.8)" title="Zoom out">&minus;</button>
    <button onclick="resetZoom(this)" title="Reset zoom">&#8634;</button>
    <button onclick="openDiagramFullscreen(this)" title="Open full size">&#x26F6;</button>
  </div>
  <pre class="mermaid">graph TD
    A --> B</pre>
</div>
```

## Grid layouts

Three layouts: 2-column architecture, auto-fit card grid, horizontal pipeline.

```css
/* Architecture (2-column with sidebar) */
.arch-grid {
  display: grid;
  grid-template-columns: 260px 1fr;
  gap: 20px;
  max-width: 1100px;
  margin: 0 auto;
}

/* Card Grid (dashboard / metrics) */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 16px;
}

/* Pipeline (horizontal steps) */
.pipeline {
  display: flex;
  align-items: stretch;
  gap: 0;
  overflow-x: auto;
}

.pipeline__step { min-width: 130px; flex-shrink: 0; }
.pipeline__arrow {
  display: flex;
  align-items: center;
  padding: 0 4px;
  color: var(--border-bright);
  font-size: 18px;
  flex-shrink: 0;
}
```

## Data tables

Sticky header, striped rows, hover highlight.

```css
.table-wrap {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}

.table-scroll { overflow-x: auto; }

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  line-height: 1.5;
}

.data-table thead { position: sticky; top: 0; z-index: 2; }

.data-table th {
  background: var(--surface-elevated);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--text-dim);
  text-align: left;
  padding: 12px 16px;
  border-bottom: 2px solid var(--border-bright);
  white-space: nowrap;
}

.data-table td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  vertical-align: top;
  color: var(--text);
}

.data-table tbody tr:nth-child(even) { background: var(--accent-dim); }
.data-table tbody tr:hover { background: var(--border); }
.data-table tbody tr:last-child td { border-bottom: none; }
```

### Status indicators

```css
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 6px;
  white-space: nowrap;
}

.status--match { background: rgba(5,150,105,0.1); color: #059669; }
.status--gap { background: rgba(239,68,68,0.1); color: #ef4444; }
.status--warn { background: rgba(217,119,6,0.1); color: #d97706; }
.status--info { background: var(--accent-dim); color: var(--accent); }
```

## Animations

Fade-up on load, hover lift, reduced-motion override.

```css
@keyframes fadeUp {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}

.ve-card {
  animation: fadeUp 0.4s ease-out both;
  animation-delay: calc(var(--i, 0) * 0.05s);
}

/* Hover lift */
.ve-card {
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.ve-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

## KPI / metric cards

Big number with monospace label.

```css
.kpi-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}

.kpi-card {
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.kpi-card__value {
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -1px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.kpi-card__label {
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  color: var(--text-dim);
  margin-top: 6px;
}
```

## Overflow protection

Stops grid/flex children from overflowing; handles long text.

```css
/* Every grid/flex child must be able to shrink */
.grid > *, .flex > * { min-width: 0; }

/* Long text wraps instead of overflowing */
body { overflow-wrap: break-word; }

/* Never use display: flex on <li> for marker characters — use absolute positioning */
li {
  padding-left: 14px;
  position: relative;
}
li::before {
  content: '›';
  position: absolute;
  left: 0;
}
```

## Collapsible sections

`<details>` with animated arrow and body padding.

```css
details.collapsible {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}

details.collapsible summary {
  padding: 14px 20px;
  background: var(--surface);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text);
}

details.collapsible summary::-webkit-details-marker { display: none; }

details.collapsible summary::before {
  content: '▸';
  font-size: 11px;
  color: var(--text-dim);
  transition: transform 0.15s ease;
}

details.collapsible[open] summary::before { transform: rotate(90deg); }

details.collapsible .collapsible__body {
  padding: 16px 20px;
  border-top: 1px solid var(--border);
  font-size: 13px;
  line-height: 1.6;
}
```

## Responsive breakpoint

Stacks arch-grid and hides pipeline arrows on narrow screens.

```css
@media (max-width: 768px) {
  .arch-grid { grid-template-columns: 1fr; }
  .pipeline { flex-wrap: wrap; gap: 8px; }
  .pipeline__arrow { display: none; }
  body { padding: 16px; }
}
```

## Capturing results

Open and screenshot the HTML file:

```bash
# Preview in browser
spel open ./spel-visual/diagram.html

# Capture screenshot as evidence
spel screenshot ./spel-visual/diagram.png
```
