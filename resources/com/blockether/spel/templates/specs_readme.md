# Test specifications

E2E test plans (specs) created by @spel-test-planner.
Specs = ground truth for generator + healer.

Agents: read this before creating, generating, or diagnosing tests.

## Before creating a new spec

1. List existing specs → see coverage
2. Find gaps: uncovered flows/features
3. Don't duplicate → update existing spec for same feature

## Creating a spec: interactive exploration

Explore live app before writing spec. Open browser so user can watch:

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

Repeat cycle per page explored. Annotated screenshots = evidence.

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
- `spel/start!` and `spel/stop!` NOT needed → daemon manages browser
- Use `--timeout` → fail fast on bad selectors
- Errors throw in `eval-sci` mode → no catch needed
- Use `spel open <url> --interactive` before `eval-sci` if user wants to watch

## Checking what's actually there

Check actual page state before writing assertions. Don't assume:

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

Document every check. Include snapshot ref, expected value, actual value in spec.
Generator needs correct selectors → healer needs this to diagnose changes.

## Spec file format

Spec = markdown file named `<feature>-test-plan.md`:

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

- [ ] All selectors verified against live app via `spel snapshot`
- [ ] Annotated screenshots taken as evidence
- [ ] Steps clear enough for any agent to follow
- [ ] Exact text content specified for assertions (never substring)
- [ ] Error states and validation failures covered
- [ ] Scenarios independent → runnable in any order
- [ ] Snapshot refs documented → proves selectors verified

## Workflow

1. Planner explores app → creates specs: `<feature>-test-plan.md`
2. User reviews + approves spec (GATE: don't proceed without approval)
3. Generator reads specs → creates test code using `spel.allure` (`defdescribe`, `it`, `expect`). Checks selectors against live app.
4. Healer reads specs when diagnosing failures → understands test intent.

## product-spec.json

Produced by `@spel-product-analyst`. Structured product feature inventory, user role mapping, coherence audit, navigation map.

Use for:
- Inform test planning via feature inventory
- Focus QA on low-coherence areas
- Generate role-specific automation scripts

See `PRODUCT_DISCOVERY.md` for full schema.
