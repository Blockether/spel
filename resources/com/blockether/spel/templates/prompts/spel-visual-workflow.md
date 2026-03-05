---
description: Visual QA and presentation workflow — regression checks and visual content creation
---

# Visual Workflow

Orchestrates visual regression testing and visual content creation using spel subagents.

## Parameters

- **Task**: The visual check or presentation to create
- **Target URL**: The URL to capture (for visual-qa)
- **Baseline dir** (optional): Defaults to `baselines/`
- **Output dir** (optional): Defaults to `./spel-visual/`

## Step 1: Visual Regression Check

> **GATE**: Review the diff report before proceeding to Step 2.

Invoke @spel-visual-qa with:

```xml
<visual-qa>
  <task>Capture baseline or compare against existing baseline</task>
  <url>{{target-url}}</url>
  <baseline-dir>{{baseline-dir}}</baseline-dir>
</visual-qa>
```

The visual-qa agent will:
1. Open the target URL
2. Capture accessibility snapshot with styles (`spel snapshot -S --json`)
3. Compare against baseline if one exists
4. Generate diff report showing structural and visual changes
5. Take screenshots for evidence

## Step 2: Create Visual Explanation

Invoke @spel-presenter with:

```xml
<present>
  <task>{{task}}</task>
  <output-dir>{{output-dir}}</output-dir>
</present>
```

The presenter agent will:
1. Choose appropriate diagram type and aesthetic
2. Generate self-contained HTML file
3. Preview with `spel open`
4. Capture screenshot evidence with `spel screenshot`

## Notes

- Run Step 1 alone for regression checks (no presentation needed)
- Run Step 2 alone for visual content creation (no regression check needed)
- Run both when you need to verify visual state AND create documentation
