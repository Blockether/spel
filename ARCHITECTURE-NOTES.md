# Architecture Analysis: spel --profile flag

## Current Implementation

The `--profile` flag is defined in `cli.clj` (line 1058) as:
```
--profile PATH          Chrome user data directory (persistent profile)
```

**Parsing:** `cli.clj:1142-1146` — stores the raw value as `:profile` in flags map. Also reads `SPEL_PROFILE` env var as default (`cli.clj:1090`).

**Usage:** `daemon.clj:215` — `profile-dir` is read from `(get flags "profile")` and passed directly to `core/launch-persistent-context` as the `user-data-dir` string (`daemon.clj:237`). No path expansion, no resolution — the value is passed verbatim to Playwright's Java API which creates a `java.nio.file.Path` from it (`core.clj:295`).

## Design Patterns Found

**Every path-related flag uses raw paths with zero expansion:**

| Flag | Help text | Pattern |
|------|-----------|---------|
| `--profile PATH` | Chrome user data directory | Raw path, no expansion |
| `--executable-path PATH` | Custom browser binary | Raw path, no expansion |
| `--storage-state PATH` | Browser storage state JSON | Raw path, no expansion |
| `--screenshot [path]` | Screenshot output path | Raw path (with auto-generated default filename) |

There is **no `~` expansion**, no default directories, no "convention over configuration" for any path flag. All paths are explicit.

The only "smart default" pattern is `--screenshot` without a path argument, which auto-generates a filename. But even that doesn't involve directory conventions.

## Project Philosophy

From `AGENTS.md` and code patterns:

1. **Thin wrapper philosophy** — spel wraps Playwright's Java API directly. It doesn't add abstraction layers or opinionated conventions on top. The README states: "Not a port: Wraps the official Playwright Java library directly."

2. **Data-driven, explicit** — "Maps for options... no option builders." The architecture favors explicit configuration over implicit conventions.

3. **Environment variables as defaults** — Instead of magic paths, spel uses env vars (`SPEL_PROFILE`, `SPEL_EXECUTABLE_PATH`, etc.) for persistent defaults. This is a Unix-idiomatic approach that gives users control without baking in path conventions.

4. **The `SPEL_PROFILE` env var already solves the UX problem** — Users who want a short command can set `export SPEL_PROFILE=~/.spel-profiles/myprofile` in their shell config and never type the path again.

## Assessment

**The proposal doesn't fit the architecture well.** Here's why:

1. **Inconsistent with all other flags** — No other path flag has a default directory. Adding one just for `--profile` would be a special case.

2. **The env var already solves it** — `SPEL_PROFILE` provides the exact UX improvement (type less) without adding magic path resolution.

3. **Ambiguity** — If `--profile myprofile` maps to `~/.spel-profiles/myprofile`, what about `--profile /tmp/chrome-profile`? You'd need heuristics (contains `/` → absolute, otherwise → convention). This adds complexity to a deliberately simple wrapper.

4. **Thin wrapper principle** — Playwright takes a path. Spel passes it through. Adding path resolution adds a layer of abstraction counter to the project's philosophy.

## Recommendation

**Close issue #7.** The proposal is well-intentioned but doesn't fit spel's architecture:

- It would be the only flag with implicit path resolution
- `SPEL_PROFILE` env var already provides the "don't repeat yourself" UX
- It adds complexity to a deliberately thin wrapper

If anything, the README/docs could better document the `SPEL_PROFILE` env var pattern as the recommended workflow for persistent profiles.
