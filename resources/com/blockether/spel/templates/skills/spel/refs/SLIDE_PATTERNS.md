<!-- Adapted from visual-explainer (MIT License, github.com/nicobailon/visual-explainer) -->
# Slide deck patterns

CSS patterns, JS engine, slide type layouts, transitions, and navigation chrome for self-contained HTML slide presentations.

When to use slides: only when the user explicitly requests them. Never auto-select slide format.

## Planning a Deck

Before writing any HTML, follow this process:

1. Inventory the source: enumerate every section, subsection, table row, decision, specification. Count them.
2. Map source to slides: every item must appear somewhere. If a section has 6 decisions, all 6 need slides.
3. Choose layouts: pick a slide type for each planned slide. Vary across the sequence.
4. Verify before writing: scan the inventory. Is anything unmapped?

The test: after generating the deck, a reader who has never seen the source should be able to reconstruct every major point from the slides alone.

## Slide engine base

```html
<body>
<div class="deck">
  <section class="slide slide--title"> ... </section>
  <section class="slide slide--content"> ... </section>
  <!-- one <section> per slide -->
</div>
</body>
```

```css
.deck {
  height: 100dvh;
  overflow-y: auto;
  scroll-snap-type: y mandatory;
  scroll-behavior: smooth;
}

.slide {
  height: 100dvh;
  scroll-snap-align: start;
  overflow: hidden;
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(40px, 6vh, 80px) clamp(40px, 8vw, 120px);
  isolation: isolate;
}
```

## Typography Scale

Slide typography is 2-3x larger than scrollable pages.

```css
.slide__display { font-size: clamp(48px, 10vw, 120px); font-weight: 800; letter-spacing: -3px; line-height: 0.95; }
.slide__heading { font-size: clamp(28px, 5vw, 48px); font-weight: 700; letter-spacing: -1px; line-height: 1.1; }
.slide__body { font-size: clamp(16px, 2.2vw, 24px); line-height: 1.6; }
.slide__label { font-family: var(--font-mono); font-size: clamp(10px, 1.2vw, 14px); font-weight: 600; text-transform: uppercase; letter-spacing: 1.5px; color: var(--text-dim); }
.slide__subtitle { font-family: var(--font-mono); font-size: clamp(14px, 1.8vw, 20px); color: var(--text-dim); }
```

## Cinematic Transitions

```css
.slide {
  opacity: 0;
  transform: translateY(40px) scale(0.98);
  transition: opacity 0.6s cubic-bezier(0.16, 1, 0.3, 1), transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

.slide.visible { opacity: 1; transform: none; }

.slide .reveal {
  opacity: 0;
  transform: translateY(20px);
  transition: opacity 0.5s cubic-bezier(0.16, 1, 0.3, 1), transform 0.5s cubic-bezier(0.16, 1, 0.3, 1);
}

.slide.visible .reveal { opacity: 1; transform: none; }
.slide.visible .reveal:nth-child(1) { transition-delay: 0.1s; }
.slide.visible .reveal:nth-child(2) { transition-delay: 0.2s; }
.slide.visible .reveal:nth-child(3) { transition-delay: 0.3s; }
.slide.visible .reveal:nth-child(4) { transition-delay: 0.4s; }
.slide.visible .reveal:nth-child(5) { transition-delay: 0.5s; }
.slide.visible .reveal:nth-child(6) { transition-delay: 0.6s; }

@media (prefers-reduced-motion: reduce) {
  .slide, .slide .reveal { opacity: 1 !important; transform: none !important; transition: none !important; }
}
```

## Navigation Chrome

```css
.deck-progress { position: fixed; top: 0; left: 0; height: 3px; background: var(--accent); z-index: 100; transition: width 0.3s ease; pointer-events: none; }

.deck-dots { position: fixed; right: clamp(12px, 2vw, 24px); top: 50%; transform: translateY(-50%); display: flex; flex-direction: column; gap: 8px; z-index: 100; }

.deck-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-dim); opacity: 0.3; border: none; padding: 0; cursor: pointer; transition: opacity 0.2s ease, transform 0.2s ease; }
.deck-dot:hover { opacity: 0.6; }
.deck-dot.active { opacity: 1; transform: scale(1.5); background: var(--accent); }

.deck-counter { position: fixed; bottom: clamp(12px, 2vh, 24px); right: clamp(12px, 2vw, 24px); font-family: var(--font-mono); font-size: 12px; color: var(--text-dim); z-index: 100; }
```

## SlideEngine JavaScript

```javascript
class SlideEngine {
  constructor() {
    this.deck = document.querySelector('.deck');
    this.slides = [...document.querySelectorAll('.slide')];
    this.current = 0;
    this.total = this.slides.length;
    this.buildChrome();
    this.bindEvents();
    this.observe();
    this.update();
  }

  buildChrome() {
    var bar = document.createElement('div');
    bar.className = 'deck-progress';
    document.body.appendChild(bar);
    this.bar = bar;

    var dots = document.createElement('div');
    dots.className = 'deck-dots';
    var self = this;
    this.slides.forEach(function(_, i) {
      var d = document.createElement('button');
      d.className = 'deck-dot';
      d.title = 'Slide ' + (i + 1);
      d.onclick = function() { self.goTo(i); };
      dots.appendChild(d);
    });
    document.body.appendChild(dots);
    this.dots = [].slice.call(dots.children);

    var ctr = document.createElement('div');
    ctr.className = 'deck-counter';
    document.body.appendChild(ctr);
    this.counter = ctr;
  }

  bindEvents() {
    var self = this;
    document.addEventListener('keydown', function(e) {
      if (e.target.closest('.mermaid-wrap, input, textarea')) return;
      if (['ArrowDown', 'ArrowRight', ' ', 'PageDown'].includes(e.key)) { e.preventDefault(); self.next(); }
      else if (['ArrowUp', 'ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); self.prev(); }
    });
  }

  observe() {
    var self = this;
    var obs = new IntersectionObserver(function(entries) {
      entries.forEach(function(entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          self.current = self.slides.indexOf(entry.target);
          self.update();
        }
      });
    }, { threshold: 0.5 });
    this.slides.forEach(function(s) { obs.observe(s); });
  }

  goTo(i) { this.slides[Math.max(0, Math.min(i, this.total - 1))].scrollIntoView({ behavior: 'smooth' }); }
  next() { if (this.current < this.total - 1) this.goTo(this.current + 1); }
  prev() { if (this.current > 0) this.goTo(this.current - 1); }

  update() {
    this.bar.style.width = ((this.current + 1) / this.total * 100) + '%';
    var self = this;
    this.dots.forEach(function(d, i) { d.classList.toggle('active', i === self.current); });
    this.counter.textContent = (this.current + 1) + ' / ' + this.total;
  }
}

document.addEventListener('DOMContentLoaded', function() {
  new SlideEngine();
});
```

## Slide type layouts

### Title Slide
```html
<section class="slide slide--title">
  <div class="slide__content reveal">
    <h1 class="slide__display">Deck Title</h1>
    <p class="slide__subtitle reveal">Subtitle or date</p>
  </div>
</section>
```

### Section Divider
```html
<section class="slide slide--divider">
  <span class="slide__number">02</span>
  <div class="slide__content">
    <h2 class="slide__heading reveal">Section Title</h2>
  </div>
</section>
```

```css
.slide--divider .slide__number {
  font-size: clamp(100px, 22vw, 260px);
  font-weight: 200;
  line-height: 0.85;
  opacity: 0.08;
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -55%);
  pointer-events: none;
}
```

### Content Slide
```html
<section class="slide slide--content">
  <div class="slide__inner">
    <div class="slide__text">
      <h2 class="slide__heading reveal">Heading</h2>
      <ul class="slide__bullets">
        <li class="reveal">First point</li>
        <li class="reveal">Second point</li>
      </ul>
    </div>
    <div class="slide__aside reveal"><!-- optional illustration --></div>
  </div>
</section>
```

```css
.slide--content .slide__inner {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: clamp(24px, 4vw, 60px);
  align-items: center;
  width: 100%;
}

.slide--content .slide__bullets { list-style: none; padding: 0; }
.slide--content .slide__bullets li {
  padding: 8px 0 8px 20px;
  position: relative;
  font-size: clamp(16px, 2vw, 22px);
  line-height: 1.6;
  color: var(--text-dim);
}
.slide--content .slide__bullets li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 18px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}
```

### CSS Pipeline Slide

For simple linear flows where Mermaid would render too small.

```html
<section class="slide">
  <h2 class="slide__heading reveal">Pipeline Title</h2>
  <div class="pipeline reveal">
    <div class="pipeline__step" style="border-top-color:var(--accent);">
      <div class="pipeline__num">01</div>
      <div class="pipeline__name">Step Name</div>
      <div class="pipeline__desc">What this step does</div>
    </div>
    <div class="pipeline__arrow">&#8594;</div>
    <div class="pipeline__step"> ... </div>
  </div>
</section>
```

```css
.pipeline { display: flex; align-items: stretch; gap: 0; flex: 1; min-height: 0; margin-top: clamp(12px, 2vh, 24px); }
.pipeline__step {
  flex: 1;
  background: var(--surface);
  border: 1px solid var(--border);
  border-top: 3px solid var(--accent);
  border-radius: 10px;
  padding: clamp(14px, 2.5vh, 28px) clamp(12px, 1.5vw, 22px);
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.pipeline__num { font-size: 13px; font-weight: 600; color: var(--accent); letter-spacing: 1px; }
.pipeline__name { font-size: clamp(16px, 2vw, 24px); font-weight: 700; margin: 4px 0; }
.pipeline__desc { font-size: clamp(12px, 1.3vw, 16px); color: var(--text-dim); line-height: 1.5; flex: 1; }
.pipeline__arrow { display: flex; align-items: center; padding: 0 6px; color: var(--accent); flex-shrink: 0; opacity: 0.4; }
```

### Dashboard Slide

```html
<section class="slide slide--dashboard">
  <h2 class="slide__heading reveal">Metrics Overview</h2>
  <div class="slide__kpis">
    <div class="slide__kpi reveal">
      <div class="slide__kpi-val" style="color:var(--accent)">247</div>
      <div class="slide__kpi-label">Lines Added</div>
    </div>
  </div>
</section>
```

```css
.slide--dashboard .slide__kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(clamp(140px, 20vw, 220px), 1fr));
  gap: clamp(12px, 2vw, 24px);
}

.slide__kpi {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: clamp(16px, 3vh, 32px) clamp(16px, 2vw, 24px);
  min-width: 0;
}

.slide__kpi-val {
  font-size: clamp(36px, 6vw, 64px);
  font-weight: 800;
  letter-spacing: -1.5px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.slide__kpi-label {
  font-family: var(--font-mono);
  font-size: clamp(9px, 1.2vw, 13px);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  color: var(--text-dim);
  margin-top: 8px;
}
```

### Diagram Slide

```html
<section class="slide slide--diagram">
  <h2 class="slide__heading reveal">Diagram Title</h2>
  <div class="mermaid-wrap reveal" style="flex:1; min-height:0;">
    <div class="zoom-controls">
      <button onclick="zoomDiagram(this,1.2)">+</button>
      <button onclick="zoomDiagram(this,0.8)">&minus;</button>
      <button onclick="resetZoom(this)">&#8634;</button>
      <button onclick="openDiagramFullscreen(this)">&#x26F6;</button>
    </div>
    <pre class="mermaid">graph TD
      A --> B</pre>
  </div>
</section>
```

**When to use Mermaid vs CSS Pipeline in slides:**
- Use Mermaid for complex graphs: 8+ nodes, branching paths, cycles
- Use CSS Pipeline for simple linear flows: A to B to C to D sequences
- Never leave a small Mermaid diagram alone on a slide — pair with content or switch to CSS

### Quote Slide

```html
<section class="slide slide--quote">
  <div class="slide__quote-mark reveal">&ldquo;</div>
  <blockquote class="reveal">The best code is the code you don't have to write.</blockquote>
  <cite class="reveal">Someone Wise</cite>
</section>
```

```css
.slide--quote { justify-content: center; align-items: center; text-align: center; padding: clamp(60px, 10vh, 120px) clamp(60px, 12vw, 200px); }
.slide__quote-mark { font-size: clamp(80px, 14vw, 180px); line-height: 0.5; opacity: 0.08; font-family: Georgia, serif; pointer-events: none; }
.slide--quote blockquote { font-size: clamp(24px, 4vw, 48px); font-weight: 400; line-height: 1.35; font-style: italic; margin: 0; }
.slide--quote cite { font-family: var(--font-mono); font-size: clamp(11px, 1.4vw, 14px); font-style: normal; margin-top: clamp(16px, 3vh, 32px); display: block; letter-spacing: 1.5px; text-transform: uppercase; color: var(--text-dim); }
```

## Compositional Variety

Consecutive slides must vary their spatial approach:
- Centered (title slides, quotes)
- Left-heavy: content on the left 60%, breathing room on the right
- Right-heavy: content on the right 60%, visual on the left
- Edge-aligned: content pushed to bottom or top
- Split: two distinct panels filling the viewport

## Capturing slides with spel

After creating a slide deck, capture individual slides as evidence:

```bash
# Open the slide deck
spel open ./spel-visual/slides.html

# Navigate to a specific slide, then capture
spel screenshot ./spel-visual/slide-01.png
spel screenshot ./spel-visual/slide-02.png
# etc.
```

## Curated Presets

Four starting points — pick one and commit:

### Midnight Editorial
```css
:root {
  --bg: #0f0f14;
  --surface: #1a1a24;
  --text: #e8e6e3;
  --text-dim: #8b8680;
  --accent: #d4a73a;
  --font-body: 'Instrument Serif', Georgia, serif;
  --font-mono: 'JetBrains Mono', monospace;
}
```

### Warm Signal
```css
:root {
  --bg: #faf7f5;
  --surface: #ffffff;
  --text: #2c2a25;
  --text-dim: #8b7355;
  --accent: #c2410c;
  --font-body: 'Plus Jakarta Sans', system-ui, sans-serif;
  --font-mono: 'Azeret Mono', monospace;
}
```

### Terminal Mono
```css
:root {
  --bg: #0a0e14;
  --surface: #0f1419;
  --text: #b3b1ad;
  --text-dim: #5c6773;
  --accent: #50fa7b;
  --font-body: 'IBM Plex Mono', monospace;
  --font-mono: 'IBM Plex Mono', monospace;
}
```

### Swiss Clean
```css
:root {
  --bg: #f5f5f5;
  --surface: #ffffff;
  --text: #1a1a1a;
  --text-dim: #666666;
  --accent: #0891b2;
  --font-body: 'IBM Plex Sans', system-ui, sans-serif;
  --font-mono: 'IBM Plex Mono', monospace;
}
```
