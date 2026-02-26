---
name: dogfood
description: "Systematically explore and test a web application to find bugs, UX issues, and other problems using spel (Blockether's Playwright wrapper). Use when asked to 'dogfood', 'QA', 'exploratory test', 'find issues', 'bug hunt', or 'test this app/site'. Produces a structured report with screenshots, repro steps, and console evidence for every finding."
---

# Dogfood

Systematically explore a web application using `spel`, find issues, and produce a report with full reproduction evidence.

## Setup

Only the **Target URL** is required. Everything else has sensible defaults.

| Parameter | Default | Example override |
|-----------|---------|-----------------|
| **Target URL** | _(required)_ | `blockether.com`, `http://localhost:3000` |
| **Session name** | Slugified domain | `my-session` |
| **Output directory** | `./dogfood-output/` | `/tmp/qa` |
| **Scope** | Full app | `Focus on the billing page` |
| **Authentication** | None | `Sign in as user@example.com` |

If the user says "dogfood blockether.com", start immediately with defaults. Do not ask clarifying questions unless authentication is mentioned but credentials are missing.

## Workflow

```
1. Initialize    Set up output dirs, report file
2. Authenticate  Sign in if needed via spel state export
3. Orient        Navigate to starting point, take initial snapshot
4. Explore       Systematically visit pages and test features
5. Document      Screenshot each issue as found
6. Wrap up       Update summary counts, present findings
```

### 1. Initialize

```bash
mkdir -p {OUTPUT_DIR}/screenshots {OUTPUT_DIR}/videos
cp {SKILL_DIR}/templates/dogfood-report-template.md {OUTPUT_DIR}/report.md
```

Open the target:

```bash
spel --stealth open {TARGET_URL}
```

### 2. Authenticate

If the app requires login, use spel's state export to load an existing Chrome profile:

```bash
spel state export --profile ~/.config/google-chrome/Default -o {OUTPUT_DIR}/auth-state.json
```

Then launch with the saved state. For manual login flows, use `spel codegen` to record the interaction, then replay.

### 3. Orient

Take an initial screenshot to understand the app structure:

```bash
spel screenshot {OUTPUT_DIR}/screenshots/initial.png
spel snapshot
```

Identify main navigation elements and map out sections to visit.

### 4. Explore

Read [references/issue-taxonomy.md](references/issue-taxonomy.md) for the full list of what to look for and the exploration checklist.

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

Use judgment on depth. Spend more time on core features, less on peripheral pages.

### 5. Document Issues

Steps 4 and 5 happen together -- explore and document in a single pass. When you find an issue, stop and document it immediately before moving on.

Every issue must have evidence. Match evidence to the issue type:

#### Interactive / behavioral issues

1. Screenshot before the action
2. Perform the action
3. Screenshot the broken state
4. Capture console errors if relevant

```bash
spel screenshot {OUTPUT_DIR}/screenshots/issue-{NNN}-step-1.png
# Perform action
spel screenshot {OUTPUT_DIR}/screenshots/issue-{NNN}-result.png
spel console
```

#### Static / visible-on-load issues

A single screenshot is sufficient:

```bash
spel screenshot {OUTPUT_DIR}/screenshots/issue-{NNN}.png
```

**For all issues:** Append to the report immediately. Increment the issue counter (ISSUE-001, ISSUE-002, ...).

### 6. Wrap Up

Aim for **5-10 well-documented issues**. Depth of evidence matters more than count.

1. Re-read the report. Update summary severity counts to match actual issues.
2. Tell the user the report is ready. Summarize: total issues, breakdown by severity, most critical items.

## Guidance

- **Repro is everything.** Every issue needs proof -- match evidence to the issue type.
- **Screenshot each step for interactive bugs.** Before, action, after.
- **Static bugs need one screenshot.** Typos, misalignment, placeholder text.
- **Write findings incrementally.** Append each issue as you discover it.
- **Never delete output files.** Work forward, not backward.
- **Never read the target app's source code.** Test as a user.
- **Check the console.** Many issues are invisible in UI but show as JS errors.
- **Test like a user.** Try common workflows end-to-end. Enter realistic data.
- **Use spel's stealth mode** for sites with bot detection: `spel --stealth open {URL}`

## References

| Reference | When to Read |
|-----------|--------------|
| [references/issue-taxonomy.md](references/issue-taxonomy.md) | Start of session -- calibrate what to look for, severity levels, exploration checklist |

## Templates

| Template | Purpose |
|----------|---------|
| [templates/dogfood-report-template.md](templates/dogfood-report-template.md) | Copy into output directory as the report file |
