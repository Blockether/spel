# Dogfood -- Exploratory Testing with spel

Reference for systematically exploring and testing a web application using spel, finding issues, and producing an HTML report with screenshots interleaved with agent explanations, video recording of the whole session, and a transcript with timestamps.

Trigger words: "dogfood", "QA", "exploratory test", "find issues", "bug hunt", "test this app/site".

## Setup

Only the **Target URL** is required. Everything else has sensible defaults.

| Parameter | Default | Example override |
|-----------|---------|-----------------|
| **Target URL** | _(required)_ | `github.com`, `http://localhost:3000` |
| **Crawl depth** | `1` | `2` (visit links found on linked pages) |
| **Session name** | Slugified domain | `my-session` |
| **Output directory** | `./dogfood-output/` | `/tmp/qa` |
| **Scope** | Full app | `Focus on the billing page` |
| **Authentication** | None | `Sign in as user@example.com` |

If the user says "dogfood github.com", start immediately with defaults.

## Workflow

```
1. Initialize    Set up output dirs, HTML report, start video recording
2. Authenticate  Sign in if needed via spel state export
3. Discover      Crawl target site to depth N, build URL list
4. Explore       Systematically visit each discovered URL, test features
5. Document      Screenshot each issue, write explanation, append to HTML report
6. Wrap up       Stop video, generate transcript + subtitles, finalize report
```

### 1. Initialize

```bash
mkdir -p {OUTPUT_DIR}/screenshots {OUTPUT_DIR}/videos
cp {SKILL_DIR}/refs/dogfood-report.html {OUTPUT_DIR}/report.html
```

Open the target and start video recording:

```bash
spel --stealth open {TARGET_URL}
spel --eval '(spel/start-video-recording {:video-dir "{OUTPUT_DIR}/videos"})'
```

**Important:** Video recording creates a new browser context. After starting, navigate back to the target:

```bash
spel navigate {TARGET_URL}
```

Initialize a transcript file. The transcript is a timestamped log of every action the agent performs -- it becomes the source for video subtitles and the HTML report's activity timeline.

```bash
# Create transcript.srt -- append entries as you work
# Format: sequential number, timestamp range, description
# Example:
# 1
# 00:00:00,000 --> 00:00:05,000
# Navigating to github.com homepage
```

Record the session start time. Every transcript entry is timestamped relative to this moment.

### 2. Authenticate

If the app requires login, use spel's state export:

```bash
spel state export --profile ~/.config/google-chrome/Default -o {OUTPUT_DIR}/auth-state.json
```

For manual login flows, use `spel codegen` to record the interaction, then replay.

### 3. Discover

**This is the key step.** Before exploring randomly, crawl the site to build a URL map.

#### Depth 1: Extract links from the landing page

```bash
spel --eval '(spel/evaluate "Array.from(document.querySelectorAll(\"a[href]\")).map(a => a.href).filter(h => h.startsWith(location.origin))")'
```

This returns a vector of same-origin URLs. Save this as your URL list.

#### Depth 2+: Follow each discovered link

For each URL from depth 1, navigate and extract more links:

```bash
spel navigate {DISCOVERED_URL}
spel --eval '(spel/evaluate "Array.from(document.querySelectorAll(\"a[href]\")).map(a => a.href).filter(h => h.startsWith(location.origin))")'
```

**Crawl rules:**

- **Same origin only** -- filter by `location.origin` to stay on the target domain.
- **Deduplicate** -- track visited URLs, skip duplicates.
- **Normalize** -- strip fragments (`#...`), trailing slashes, query params for comparison.
- **Skip assets** -- ignore URLs ending in `.png`, `.jpg`, `.css`, `.js`, `.svg`, `.ico`, `.woff`.
- **Limit scope** -- cap at ~50 URLs to keep the session manageable.
- **Respect depth** -- stop at the configured crawl depth.

#### Output

After crawling, you should have a deduplicated list of URLs organized by depth:

```
Depth 0: https://github.com/
Depth 1: https://github.com/explore, https://github.com/trending, ...
Depth 2: https://github.com/topics/javascript, ...
```

Take a screenshot and log this URL map in the HTML report's "Discovered URLs" section.

### 4. Explore

Work through the discovered URL list systematically. At each page:

1. **Navigate** and wait for load:

```bash
spel navigate {URL}
```

2. **Snapshot** the accessibility tree:

```bash
spel snapshot
```

3. **Screenshot** the page:

```bash
spel screenshot {OUTPUT_DIR}/screenshots/{page-slug}.png
```

4. **Scroll** through the full page to check below-the-fold content:

```bash
spel --eval '(spel/evaluate "window.scrollTo(0, document.body.scrollHeight)")'
spel screenshot {OUTPUT_DIR}/screenshots/{page-slug}-bottom.png
```

5. **Check console** for errors:

```bash
spel console
```

6. **Test interactive elements** -- click buttons, open dropdowns, fill forms.

7. **Check responsive behavior** if relevant:

```bash
spel set viewport 375 812
spel screenshot {OUTPUT_DIR}/screenshots/{page-slug}-mobile.png
spel set viewport 1280 720
```

8. **Log a transcript entry** for every action. Example:

```
14
00:02:35,000 --> 00:02:42,000
Navigating to /explore — checking trending repositories section
```

**Strategy:**

- Start from the URL list. Visit each URL in order.
- Within each page, test interactive elements: click buttons, fill forms, open dropdowns/modals.
- Check edge cases: empty states, error handling, boundary inputs.
- Try realistic end-to-end workflows (create, edit, delete).
- Check the browser console for errors at every page.

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
  <div class="finding-body">
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
  </div>
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

#### Stop video recording

```bash
spel --eval '(spel/finish-video-recording {:save-as "{OUTPUT_DIR}/videos/session.webm"})'
```

#### Generate subtitles and final video

If ffmpeg is available, convert WebM to MP4 with embedded subtitles:

```bash
ffmpeg -i {OUTPUT_DIR}/videos/session.webm \
       -i {OUTPUT_DIR}/transcript.srt \
       -c:v libx264 -c:a aac \
       -c:s mov_text \
       -metadata:s:s:0 language=eng \
       {OUTPUT_DIR}/videos/session.mp4
```

If ffmpeg is not available, keep the `.webm` and `.srt` files side by side -- most players can load external subtitle files.

#### Finalize the HTML report

1. Update the summary section with final severity counts.
2. Add the video embed (use relative path to `videos/session.mp4` or `session.webm`).
3. Add the transcript timeline entries.
4. Open the HTML file in a browser to verify rendering: `spel open file://{OUTPUT_DIR}/report.html`
5. Take a screenshot of the rendered report as proof.
6. Tell the user the report is ready. Summarize: total issues, breakdown by severity, most critical items.

## Transcript Format (SRT)

The transcript doubles as video subtitles. Use standard SRT format:

```
1
00:00:00,000 --> 00:00:05,000
Opening github.com in stealth mode

2
00:00:05,000 --> 00:00:12,000
Homepage loaded. Taking initial screenshot.

3
00:00:12,000 --> 00:00:20,000
Extracting links for depth-1 crawl: found 47 same-origin URLs

4
00:00:20,000 --> 00:00:28,000
Navigating to /explore — checking layout and interactive elements

5
00:00:28,000 --> 00:00:35,000
ISSUE-001: Search input has no visible focus indicator (accessibility)
```

**Rules:**

- One entry per significant action (navigate, click, screenshot, issue found).
- Keep text concise -- subtitles should be readable at video playback speed.
- Include issue IDs when documenting problems so viewers can cross-reference the report.
- Timestamps must be relative to the video start (when `start-video-recording` was called).

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
- **Log every action to the transcript.** It becomes the video subtitles.
- **Never delete output files.** Work forward, not backward.
- **Never read the target app's source code.** Test as a user.
- **Check the console.** Many issues are invisible in UI but show as JS errors.
- **Use spel's stealth mode** for sites with bot detection: `spel --stealth open {URL}`
