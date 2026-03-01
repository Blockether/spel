# TASKS — spel Backlog

> Sourced from the spel vs agent-browser dogfood comparison (Feb 26, 2026).
> Updated March 1, 2026 — completed tasks removed, only open items remain.
> See `POTENTIAL_TASKS.md` for 32 additional features identified from agent-browser 0.15.1.

---

## Completed Tasks (removed from backlog)

| Task | Description | Shipped |
|---|---|---|
| TASK-001 | Error propagation (Playwright call log, selector, reason) | v0.5.0 |
| TASK-002 | Invalid URL detection (exit 1 with clear message) | v0.4.2 |
| TASK-004 | Link URLs in snapshots (`[url=https://...]` inline) | v0.5.0 |
| TASK-005 | Structured refs map in JSON (`refs` with role/name/url) | v0.5.0 |
| TASK-006 | `download` command | v0.5.0 |
| TASK-007 | `--extension` flag for Chrome extensions | v0.5.0 |
| TASK-008 | Flat snapshot mode (`--flat`) | v0.5.0 |
| TASK-009 | Video save-as bug | v0.5.0 |
| TASK-012 | Combined JSON enrichment (pages, network, console) | v0.5.0 |

---

## TASK-003: Sequential Ref IDs for LLM Agents

### Problem Statement

spel uses 6-character random hash refs like `[@e2yrjz]`, `[@e9mter]`, `[@e6t2x4]`. agent-browser uses sequential refs like `[ref=e1]`, `[ref=e2]`, `[ref=e3]`. Sequential refs are measurably easier for LLMs to work with:

- Shorter (2-3 chars vs 6 chars) — less tokens per snapshot
- Predictable ordering — LLM can reason about position
- Easier to type/reference in follow-up commands — `@e3` vs `@e2yrjz`
- Lower hallucination risk — LLMs more reliably reproduce short sequential IDs than random hashes

On a complex page like github.com with 100+ refs, this means ~300 fewer tokens per snapshot.

### Reproduction

Not a bug — feature comparison:

```bash
# spel (current):
# - link "Skip to content" [@e27w9t]
# - button "Platform" [@e55nqg]
# - textbox "Enter your email" [@e3k2ih]

# agent-browser (desired):
# - link "Skip to content" [ref=e1]
# - button "Platform" [ref=e2]
# - textbox "Enter your email" [ref=e3]
```

### Type

✨ **Feature** — LLM ergonomics improvement.

### Priority

🟡 **P1 — High**. This is agent-browser's biggest UX advantage over spel. Eliminating it would remove the primary reason an AI agent team might choose agent-browser.

### Acceptance Criteria

1. Snapshot refs use sequential format: `@e1`, `@e2`, ... `@e107`
2. All commands that accept refs (`click @e3`, `fill @e5 "text"`, `get text @e7`) work with the new format
3. Refs are stable within a single snapshot — same page, same snapshot, same refs
4. Refs reset on each new snapshot call (they're ephemeral, not persistent)
5. Existing hash-based refs (`@e2yrjz`) continue to work during a transition period (backward compatibility)
6. `--json` output uses the new sequential refs
7. Snapshot `-i` flag produces a contiguous sequence (no gaps from filtered-out non-interactive elements)

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Sequential numbering (e1, e2, e3)** | Simplest, matches agent-browser | Refs change when page changes (but they already do with hashes) |
| **B) Role-prefixed sequential (btn1, lnk2, txt3)** | More semantic | Longer, harder to type, more token overhead |
| **C) Short hash (3 chars instead of 6)** | Minimal change | Still random, still hard to reference |
| **D) Dual mode: `--refs=sequential\|hash`** | Backward compatible | Complexity, two code paths |

### Implementation Hints

- The ref generation happens in `sci_env.clj` or wherever the accessibility tree is parsed and refs are assigned. Look for where `[@eXXXXX]` strings are constructed.
- The ref→locator mapping is maintained in the daemon state. Change the key generation from random hash to sequential counter.
- Use an atom with a counter that resets at the start of each `capture-snapshot` call.
- The `@` prefix in CLI commands (`click @e3`) routes through the ref resolver — this should work unchanged as long as the daemon maps `e3` to the correct locator.

### Consequences for spel

- **Positive**: Major LLM ergonomics win. ~300 fewer tokens per complex page snapshot. Easier manual use. Removes agent-browser's primary advantage.
- **Negative**: Breaking change for any tooling that parses ref format (unlikely outside of spel itself). Refs are less "unique" (but they're ephemeral per-snapshot anyway).

### Pareto Estimate

**25% effort / 75% impact**. Moderate refactor of the ref generation system. Possibly a few hours to trace the ref lifecycle through daemon → CLI → native. High impact because it directly affects every snapshot, every agent interaction.

---

## TASK-010: iOS Simulator Support

### Problem Statement

agent-browser supports iOS Simulator testing via Appium: `-p ios open https://example.org`, with device listing, swipe gestures, and tap interactions. spel has `set device <name>` for viewport/UA emulation but no real mobile browser engine testing.

Real iOS testing catches bugs that viewport emulation cannot: Safari-specific rendering, iOS-specific touch behaviors, platform-specific font rendering, iOS permission dialogs.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser:
agent-browser -p ios --device "iPhone 15 Pro" open https://example.org
agent-browser -p ios swipe up
agent-browser -p ios tap @e1
agent-browser -p ios device list

# spel:
spel set device "iphone 14"  # only viewport + UA emulation, not real Safari
```

### Type

✨ **Feature** — Platform expansion.

### Priority

🟢 **P3 — Low**. Nice to have for mobile-focused teams. Most users are fine with viewport emulation. Appium dependency adds significant complexity.

### Acceptance Criteria

1. `spel --provider ios open https://example.org` launches iOS Simulator with Safari
2. `spel --provider ios --device "iPhone 15 Pro" open ...` targets specific simulator
3. `spel --provider ios device list` shows available simulators
4. Standard commands work: `snapshot`, `screenshot`, `click`, `fill`
5. iOS-specific: `swipe up/down/left/right`, `tap @ref`
6. Requires Xcode + Appium (documented prerequisites)
7. Graceful error if Xcode/Appium not installed

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Appium integration (like agent-browser)** | Real Safari engine, proven approach | Heavy dependency (Xcode, Appium, Java), macOS-only |
| **B) Safari WebDriver via Playwright WebKit** | Already have WebKit engine | Not real iOS Safari, misses platform quirks |
| **C) BrowserStack/Sauce Labs cloud** | No local dependencies | Paid service, network latency |

### Implementation Hints

- agent-browser uses Appium under the hood for iOS. Would need to add Appium Java client as a dependency.
- Could be a separate provider module that implements the same command interface.
- Consider making this a plugin/extension rather than core — keeps the binary small for non-iOS users.

### Consequences for spel

- **Positive**: Real mobile Safari testing. Competitive with agent-browser on mobile.
- **Negative**: Appium is a heavy dependency. macOS-only. Xcode install is 12+ GB. Significantly increases complexity for a niche use case.

### Pareto Estimate

**80% effort / 15% impact**. Very heavy implementation (Appium integration, new provider abstraction, iOS-specific commands). Low impact — most users don't need real iOS testing. Consider only if mobile testing becomes a strategic priority.

---

## TASK-011: Cloud Browser Providers

### Problem Statement

agent-browser supports cloud browser providers: `-p browserbase`, `-p kernel`, `-p browseruse`. These provide serverless browser instances for CI/CD at scale — no local Chromium needed, automatic scaling, session recording built in.

spel requires a local browser installation. For CI pipelines running hundreds of parallel tests, local browsers don't scale.

### Reproduction

Not a bug — missing feature:

```bash
# agent-browser:
agent-browser -p browserbase open https://example.org  # cloud browser
agent-browser -p kernel open https://example.org        # another provider

# spel: local browser only
spel open https://example.org  # always local Chromium
```

### Type

✨ **Feature** — Infrastructure expansion.

### Priority

🟢 **P3 — Low**. Most users run local browsers. Cloud browsers are for enterprise-scale CI. CDP connect (`spel connect <url>`) already enables connecting to remote browsers — just not the managed provider APIs.

### Acceptance Criteria

1. `spel --provider browserbase open https://example.org` connects to a BrowserBase cloud browser
2. At least one cloud provider is supported (BrowserBase recommended — largest ecosystem)
3. Authentication via environment variables (API key)
4. All standard commands work through the cloud browser
5. Session recordings are accessible
6. Graceful error if API key is missing or invalid
7. Provider-specific options documented in `--help`

### Alternative Approaches Considered

| Approach | Pros | Cons |
|---|---|---|
| **A) Native provider integration** | First-class experience | Each provider has its own API, high maintenance |
| **B) CDP connect to provider's endpoint** | Leverage existing `connect` command | Providers often need custom handshake beyond CDP |
| **C) Plugin system for providers** | Extensible, community-driven | Architecture overhead for v1 |

### Implementation Hints

- BrowserBase provides a WebSocket CDP endpoint after session creation via REST API. Could be implemented as: (1) REST call to create session, (2) `connect` to the returned WebSocket URL.
- The `connect` command already handles CDP connections — the provider integration is mostly session lifecycle management.
- Consider starting with just BrowserBase (most popular) and adding others later.

### Consequences for spel

- **Positive**: Enterprise CI/CD scalability. No local browser installation needed. Competitive with agent-browser for cloud-native teams.
- **Negative**: External API dependency. Provider API changes break spel. Paid service requirement.

### Pareto Estimate

**60% effort / 20% impact**. Moderate implementation per provider, but each provider has a different API. Low impact — most users don't need cloud browsers. The existing `spel connect <cdp-url>` already covers the manual case.

---

## Summary — Priority Matrix

| Task | Type | Priority | Effort | Impact | Pareto |
|---|---|---|---|---|---|
| **TASK-003** Sequential refs | ✨ Feature | P1 | 25% | 75% | High ROI |
| **TASK-011** Cloud browser providers | ✨ Feature | P3 | 60% | 20% | Low ROI |
| **TASK-010** iOS Simulator | ✨ Feature | P3 | 80% | 15% | **Worst ROI** |

### Recommended Implementation Order

1. **TASK-003**: Sequential refs — biggest LLM ergonomics win, moderate effort
2. **TASK-011**: Cloud browser providers — if enterprise demand materializes
3. **TASK-010**: iOS Simulator — only if mobile testing becomes strategic priority

---

*Generated from spel vs agent-browser comparison, February 26, 2026. Updated March 1, 2026.*
