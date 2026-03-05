---
description: Automation workflow — explore, script, and interact with browser sessions
---

# Automation Workflow

Orchestrates browser exploration, script creation, and interactive sessions using spel subagents.

## Parameters

- **Task**: The automation goal (explore a site, write a script, interactive session)
- **Target URL**: The URL to automate
- **Script output** (optional): Path for generated `.clj` scripts (default: `spel-scripts/`)
- **Args** (optional): Arguments to pass to eval scripts via `--`

## Step 1: Explore

Invoke @spel-explorer with:

```xml
<explore>
  <task>Explore the target URL, capture data, identify selectors</task>
  <url>{{target-url}}</url>
</explore>
```

The explorer agent will:
1. Open the URL and capture accessibility snapshot
2. Annotate and screenshot key elements
3. Extract structured data to JSON if needed
4. Identify selectors for automation

## Step 2: Automate

Invoke @spel-automator with:

```xml
<automate>
  <task>Write reusable automation scripts based on exploration findings</task>
  <url>{{target-url}}</url>
  <script-output>{{script-output}}</script-output>
  <args>{{args}}</args>
</automate>
```

The automator agent will:
1. Write `.clj` eval scripts using selectors from Step 1
2. Use `*command-line-args*` for parameterized scripts
3. Test: `spel --eval script.clj -- {{args}}`
4. Save scripts to the specified output directory

## Step 3: Interactive Refinement (Optional)

> Only needed when human-in-the-loop is required (2FA, CAPTCHA, SSO).

Invoke @spel-interactive with:

```xml
<interact>
  <task>Open headed browser for user interaction, then continue automation</task>
  <url>{{target-url}}</url>
  <channel>chrome</channel>
</interact>
```

The interactive agent will:
1. Ask user for browser channel and profile preferences
2. Open headed browser for user action
3. Continue automation from authenticated state

## Notes

- Run Step 1 alone for data exploration and research
- Run Steps 1+2 for full automation script creation
- Add Step 3 only when human authentication is required
- Scripts accept args via `--` separator: `spel --eval script.clj -- arg1 arg2`
