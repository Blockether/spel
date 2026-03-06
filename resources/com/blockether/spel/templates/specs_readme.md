# Test specifications

This directory contains E2E test plans (specs) created by the @spel-test-planner agent.
Specs are the ground truth for the generator and healer.

Agents: read this file before creating, generating, or diagnosing tests.

## Before creating a new spec

1. List existing specs to see what's covered
2. Find gaps: which flows or features still need coverage
3. Don't duplicate: if a spec already exists for a feature, update it instead of creating a new one

## Creating a spec: interactive exploration

Always explore the live app before writing a spec. Open the browser so the user can watch:

```bash
spel open <url> --interactive

# Capture accessibility snapshot with numbered refs (e1, e2, ...)
spel snapshot -i

# Annotate the page (overlays ref badges and bounding boxes on visible elements)
spel annotate

# Take an annotated screenshot as evidence
spel screenshot <feature>-annotated.png

# Remove overlays when done
spel unannotate
```

Repeat this cycle for every page you explore. Annotated screenshots are your evidence.

## Creating a spec: scripted exploration with eval-sci

```bash
spel --timeout 5000 eval-sci '
  (do
    (spel/navigate "<url>")

    ;; Snapshot the page
    (let [snap (spel/capture-snapshot)]
      (println (:tree snap)))

    ;; Discover interactive elements
    (println "Links:" (spel/all-text-contents "a"))
    (println "Buttons:" (spel/all-text-contents "button"))
    (println "Inputs:" (spel/count-of "input"))

    ;; Navigate deeper
    (spel/click (spel/get-by-text "Login"))
    (println "After click, title:" (spel/title))
    (println "After click, URL:" (spel/url))

    ;; Snapshot the new page
    (let [snap2 (spel/capture-snapshot)]
      (println (:tree snap2))))'
```

Notes:
- `spel/start!` and `spel/stop!` are NOT needed. The daemon manages the browser.
- Use `--timeout` to fail fast on bad selectors
- Errors throw in `eval-sci` mode. No need to catch them.
- Use `spel open <url> --interactive` before `eval-sci` if the user wants to watch

## Checking what's actually there

Before writing assertions, check the actual page state. Don't assume:

```bash
spel get text @e1
spel is visible @e3
spel get title
spel get url
spel get count ".items"
spel get value @e2
spel is enabled @e4
spel is checked @e5
```

Document every check. Include the snapshot ref, expected value, and actual value in the spec. The generator needs correct selectors, and the healer needs this to diagnose changes.

## Spec file format

Each spec is a markdown file named `<feature>-test-plan.md`:

```markdown
# <Feature> Test Plan

**Seed:** `test-e2e/<ns>/e2e/seed_test.clj`
**Target URL:** `<url>`
**Explored on:** <date>

## Exploration summary

Pages visited:
- Homepage (`/`): heading, 1 link, 1 paragraph
- Login (`/login`): email input, password input, submit button

Screenshots:
- `homepage-annotated.png`: annotated accessibility overlay
- `login-annotated.png`: annotated login form

## 1. <Scenario Group>

### 1.1 <Test Case Name>
**Steps:**
1. Navigate to `<url>`
2. Click the element with text "Submit"
3. Fill the input with label "Email" with "test@example.org"

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

## Quality checklist

- [ ] All selectors verified against the live app via `spel snapshot`
- [ ] Annotated screenshots taken as evidence
- [ ] Steps clear enough for any agent to follow
- [ ] Exact text content specified for assertions (never substring)
- [ ] Error states and validation failures covered
- [ ] Scenarios are independent and can run in any order
- [ ] Snapshot refs documented to prove selectors were verified

## Workflow

1. Planner explores the app and creates specs here: `<feature>-test-plan.md`
2. User reviews and approves the spec (GATE: do not proceed without approval)
3. Generator reads specs and creates test code using `spel.allure` (`defdescribe`, `it`, `expect`). It checks selectors against the live app.
4. Healer reads specs when diagnosing failures to understand what the test was supposed to do.
