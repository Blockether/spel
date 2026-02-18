# Test Specifications

This directory contains E2E test plans (specs) created by the @spel-test-planner agent.
Specs are the **source of truth** for test generation and failure diagnosis.

**Agents: read this file before creating, generating, or diagnosing tests.**

## Before Creating a New Spec

1. **List existing specs** in this directory to see what's already covered
2. **Identify gaps** — determine which flows or features still need coverage
3. **Avoid duplicates** — if a spec already exists for a feature, update it rather than creating a new one

## Creating a Spec: Interactive Exploration

Always explore the live application before writing a spec. Open the browser interactively so the user can see your exploration in real-time:

```bash
# Open the app visibly
spel open <url> --interactive

# Capture accessibility snapshot with numbered refs (e1, e2, ...)
spel snapshot -i

# Annotate the page — overlays ref badges and bounding boxes on visible elements
spel annotate

# Take an annotated screenshot as evidence
spel screenshot <feature>-annotated.png

# Remove overlays when done
spel unannotate
```

**Repeat this cycle for every page you explore.** Annotated screenshots are your primary evidence — they show the user exactly what you see.

## Creating a Spec: Scripted Exploration with --eval

Use `spel --eval` for multi-step exploration in a single command:

```bash
spel --timeout 5000 --eval '
  (do
    (spel/goto "<url>")

    ;; Snapshot the page
    (let [snap (spel/snapshot)]
      (println (:tree snap)))

    ;; Discover interactive elements
    (println "Links:" (spel/all-text-contents "a"))
    (println "Buttons:" (spel/all-text-contents "button"))
    (println "Inputs:" (spel/count-of "input"))

    ;; Navigate deeper
    (spel/click (spel/$text "Login"))
    (println "After click — Title:" (spel/title))
    (println "After click — URL:" (spel/url))

    ;; Snapshot the new page
    (let [snap2 (spel/snapshot)]
      (println (:tree snap2))))'
```

**Notes:**
- `spel/start!` and `spel/stop!` are NOT needed — the daemon manages the browser
- Use `--timeout` to fail fast on bad selectors
- Errors throw automatically in `--eval` mode
- Use `spel open <url> --interactive` before `--eval` if the user wants to watch

## Confirming What Exists vs What Doesn't

Before writing assertions, verify actual page state against expectations:

```bash
# Verify specific element text
spel get text @e1

# Check element visibility
spel is visible @e3

# Verify page title and URL
spel get title
spel get url

# Count elements matching a selector
spel get count ".items"

# Check form state
spel get value @e2
spel is enabled @e4
spel is checked @e5
```

**Document every verification** — include the snapshot ref, expected value, and actual value in the spec. This ensures the generator uses correct selectors and the healer can diagnose changes.

## Spec File Format

Each spec is a markdown file named `<feature>-test-plan.md`:

```markdown
# <Feature> Test Plan

**Seed:** `test/e2e/seed_test.clj`
**Target URL:** `<url>`
**Explored on:** <date>

## Exploration Summary

Pages visited:
- Homepage (`/`) — heading, 1 link, 1 paragraph
- Login (`/login`) — email input, password input, submit button

Screenshots:
- `homepage-annotated.png` — annotated accessibility overlay
- `login-annotated.png` — annotated login form

## 1. <Scenario Group>

### 1.1 <Test Case Name>
**Steps:**
1. Navigate to `<url>`
2. Click the element with text "Submit"
3. Fill the input with label "Email" with "test@example.com"

**Expected:**
- Page title changes to "Dashboard"
- Element with text "Welcome" is visible
- URL contains "/dashboard"

**Selectors verified via snapshot:**
- Submit button: ref e3, role "button", name "Submit"
- Email input: ref e5, role "textbox", name "Email"

### 1.2 <Another Test Case>
...
```

## Quality Checklist

- [ ] All selectors verified against the live app via `spel snapshot`
- [ ] Annotated screenshots taken as evidence
- [ ] Steps specific enough for any agent to follow
- [ ] Exact text content specified for assertions (never substring)
- [ ] Negative scenarios included (error states, validation failures)
- [ ] Scenarios are independent and can run in any order
- [ ] Snapshot refs documented to prove selectors were verified

## Workflow

1. **Planner** explores the app and creates specs here → `<feature>-test-plan.md`
2. **User reviews and approves** the spec (GATE — do not proceed without approval)
3. **Generator** reads specs and creates Lazytest code, verifying selectors against the live app
4. **Healer** references specs when diagnosing failures to understand expected behavior
