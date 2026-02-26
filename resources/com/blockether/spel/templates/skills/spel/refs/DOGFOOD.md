# Dogfood -- Exploratory Testing with spel

Reference for systematically exploring and testing a web application using spel, finding issues, and producing an HTML report with screenshots interleaved with agent explanations.

Trigger words: "dogfood", "QA", "exploratory test", "find issues", "bug hunt", "test this app/site".

## Setup

Only the **Target URL** is required. Everything else has sensible defaults.

| Parameter | Default | Example override |
|-----------|---------|-----------------|
| **Target URL** | _(required)_ | `blockether.com`, `http://localhost:3000` |
| **Session name** | Slugified domain | `my-session` |
| **Output directory** | `./dogfood-output/` | `/tmp/qa` |
| **Scope** | Full app | `Focus on the billing page` |
| **Authentication** | None | `Sign in as user@example.com` |

If the user says "dogfood blockether.com", start immediately with defaults.

## Workflow

```
1. Initialize    Set up output dirs, HTML report file
2. Authenticate  Sign in if needed via spel state export
3. Orient        Navigate to starting point, take initial snapshot
4. Explore       Systematically visit pages and test features
5. Document      Screenshot each issue, write explanation, append to HTML report
6. Wrap up       Finalize HTML report, present findings
```

### 1. Initialize

```bash
mkdir -p {OUTPUT_DIR}/screenshots {OUTPUT_DIR}/videos
cp {SKILL_DIR}/refs/dogfood-report.html {OUTPUT_DIR}/report.html
```

Open the target:

```bash
spel --stealth open {TARGET_URL}
```

### 2. Authenticate

If the app requires login, use spel's state export:

```bash
spel state export --profile ~/.config/google-chrome/Default -o {OUTPUT_DIR}/auth-state.json
```

For manual login flows, use `spel codegen` to record the interaction, then replay.

### 3. Orient

Take an initial screenshot to understand the app structure:

```bash
spel screenshot {OUTPUT_DIR}/screenshots/initial.png
spel snapshot
```

Identify main navigation elements and map out sections to visit.

### 4. Explore

**Strategy -- work through the app systematically:**

- Start from main navigation. Visit each top-level section.
- Within each section, test interactive elements: click buttons, fill forms, open dropdowns/modals.
- Check edge cases: empty states, error handling, boundary inputs.
- Try realistic end-to-end workflows (create, edit, delete).
- Check the browser console for errors periodically.

**At each page:**

```bash
spel snapshot
spel screenshot {OUTPUT_DIR}/screenshots/{page-name}.png
spel console
```

### 5. Document Issues

Explore and document in a single pass. When you find an issue, document it immediately.

Every issue must have evidence. For each issue found, append an HTML section to the report:

```html
<section class="finding">
  <div class="finding-header">
    <span class="finding-id">ISSUE-NNN</span>
    <span class="severity severity--{level}">{SEVERITY}</span>
    <span class="category">{CATEGORY}</span>
  </div>
  <h3>{Short title}</h3>
  <p class="finding-url">{page URL}</p>
  <div class="explanation">{Agent explanation: what is wrong, what was expected, what happened}</div>
  <div class="evidence">
    <figure>
      <img src="screenshots/issue-NNN-step-1.png" alt="Step 1">
      <figcaption>Step 1: {description}</figcaption>
    </figure>
    <div class="explanation">{What the agent did next and why}</div>
    <figure>
      <img src="screenshots/issue-NNN-result.png" alt="Result">
      <figcaption>Result: {what went wrong}</figcaption>
    </figure>
  </div>
  <div class="console-output"><pre>{console errors if any}</pre></div>
</section>
```

The key pattern is **interleaved screenshots and explanations** -- each screenshot is followed by the agent's narrative of what it observed, what it tried, and what happened. This creates a visual walkthrough that reads like a story.

#### Interactive / behavioral issues

1. Screenshot before the action + explain what you are about to test
2. Perform the action
3. Screenshot the broken state + explain what went wrong
4. Capture console errors if relevant

#### Static / visible-on-load issues

A single screenshot with explanation is sufficient.

### 6. Wrap Up

Aim for **5-10 well-documented issues**. Depth of evidence matters more than count.

1. Update the summary section of the HTML report with final severity counts.
2. Open the HTML file in a browser to verify rendering: `spel open file://{OUTPUT_DIR}/report.html`
3. Take a screenshot of the rendered report as proof.
4. Tell the user the report is ready. Summarize: total issues, breakdown by severity, most critical items.

## Issue Taxonomy

### Severity Levels

| Severity | Definition |
|----------|------------|
| **critical** | Blocks a core workflow, causes data loss, or crashes the app |
| **high** | Major feature broken or unusable, no workaround |
| **medium** | Feature works but with noticeable problems, workaround exists |
| **low** | Minor cosmetic or polish issue |

### Categories

- **visual** -- Layout, alignment, rendering, responsive, z-index, fonts, colors, animations
- **functional** -- Broken links, dead buttons, form validation, redirects, state persistence, race conditions
- **ux** -- Confusing navigation, missing feedback, slow interactions, unclear errors, dead ends
- **content** -- Typos, outdated text, placeholders, truncation, wrong labels
- **performance** -- Slow loads (>3s), jank, layout shifts, excessive requests, memory leaks
- **console** -- JS exceptions, failed requests (4xx/5xx), CORS, mixed content, unhandled rejections
- **accessibility** -- Missing alt text, unlabeled inputs, keyboard navigation, focus traps, contrast, ARIA

### Exploration Checklist

1. **Visual scan** -- Screenshot. Look for layout and rendering issues.
2. **Interactive elements** -- Click every button, link, control. Feedback?
3. **Forms** -- Fill and submit. Test empty, invalid, edge cases.
4. **Navigation** -- All paths, breadcrumbs, back button, deep links.
5. **States** -- Empty, loading, error, overflow.
6. **Console** -- JS errors, failed requests, warnings.
7. **Responsiveness** -- Different viewport sizes if relevant.
8. **Auth boundaries** -- Logged out behavior, different roles.

## Guidance

- **Repro is everything.** Every issue needs proof.
- **Screenshot each step for interactive bugs.** Before, action, after.
- **Static bugs need one screenshot.** Typos, misalignment, placeholder text.
- **Write findings incrementally.** Append each issue as you discover it.
- **Interleave screenshots with narrative.** The report should read like a visual walkthrough.
- **Never delete output files.** Work forward, not backward.
- **Never read the target app's source code.** Test as a user.
- **Check the console.** Many issues are invisible in UI but show as JS errors.
- **Use spel's stealth mode** for sites with bot detection: `spel --stealth open {URL}`
