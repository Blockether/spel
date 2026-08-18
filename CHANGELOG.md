# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v0.9.30] - 2026-08-18

### Changed
- docs(skill): stop routing readers to a reference that never shipped
- fix(cli): answer `eval-sci --help` with help, not a SCI error
- fix(snapshot): capture iframe content instead of dropping it
- fix(pdf): apply :margin, and document what the action log records
- fix(cli): print the action log as JSON instead of its size
- fix(sci): let event handlers print, and make off-dialog remove one
- test(daemon): stop two fabricated-state tests launching a browser
- fix(daemon): start the browser before a session's first page command
- fix(cli): honour --timeout in eval-sci and print its hint
- fix(assertions): assert against the page you have, refuse a glob URL
- fix(daemon): fail the command when the browser refused the side effect
- fix(daemon): deliver the browser's events before every command answers
- fix(daemon): fall back to a live tab when the one spel drives is killed
- fix(daemon): capture failed requests and route every tab of the session
- fix(daemon): keep capture alive across popups, crashes, routes and refs
- fix(daemon): keep one closed tab from relaunching the whole session
- fix(daemon): scope console, errors and network capture to the tab
- fix(daemon): scope the CDP route lock to the intercepted tab
- fix(daemon): attach the browser when a route is the session's first command
- fix(daemon): report the CDP lock owner instead of a command timeout
- fix(cli): answer eval-sci --json with JSON, not EDN
- fix(cli): hand --json output over bare, never wrapped in boundaries
- docs(skill): one spel session per task, not per command


## [v0.9.29] - 2026-08-17

### Changed
- fix(ios): measure where the web viewport starts, never assume it


### Fixed
- fix(ios): measure where the web viewport starts instead of assuming a status bar

## [v0.9.28] - 2026-08-16

### Changed
- docs(skill): cut the generated skill to what changes behavior
- build(release): keep the version-bump commit out of generated notes


## [v0.9.27] - 2026-08-16

### Changed
- fix(cli): find a daemon by the command line the OS keeps
- build(make): run the native-image gate inside make lint
- fix(core): keep the handler-error tally unboxed
- fix(daemon): report a navigation that never arrived
- perf(daemon): snapshot where a ref is used, not after every action
- fix(ios): root a native ref XPath where WDA can resolve it
- perf(ios): stop re-dumping the XCTest tree after every gesture
- fix(daemon): end a command on a crashed renderer, and cap the snapshot
- fix(daemon): keep a session alive when a command never comes back
- fix(daemon): serve one session name with one daemon


## [v0.9.26] - 2026-08-10

### Changed
- fix(ci): block-scalar the NVD steps so the security audit can run at all
- fix(annotate): keep a pixel of air between two marks, not just no overlap
- fix(annotate): keep the number off the words that name the row
- feat(annotate): number the boxes and keep the number off the words
- fix(webdriver): read the script's value from its structure, not from its punctuation
- refactor(native,webdriver): decide from what the code IS, not from a copied list
- fix(webdriver): box the quote character so native-image sees no reflection
- fix(ios,cli): make every iOS ceiling and the flag parser fit real work
- Make the agent propose boxed snapshots and carry the ref table
- Keep content boundaries silent for empty output


### Fixed

- Annotate a page the way the agent research does it, and make the drawing readable on a dense one: a bare NUMBER instead of a `@ref role name` caption stamped over the element; one colour per entry shared by the box outline and its mark, so a number is paired with its box by hue and not by luck; and only what can be ACTED on is drawn — prose roles (`paragraph`, `span`, `text`, `listitem`, `navigation`) are in the snapshot, not on the picture, and come back with `--text`. Placement scores every slot against the page's own words (text nodes measured with a Range) and against the marks already placed: a word belonging to the element the mark NAMES is nearly free to cover, EXCEPT over that element's head, because a number on the first letters of a title (`Story number 12` read as `12ory number 12`) is exactly what made an annotated list unreadable; and a number may step along its row as far as its own element is wide, so a row-wide link reaches the clear paper beside it while a small button keeps its number close. Measured on Hacker News: 181 marks, 0 on a title's first words, 0 mark collisions; a 25-row link list with no vertical gap 0 marks burying a neighbour's word (worst overlap 3% of a mark); a 40-field grid and a tight button toolbar 0 and 0; mark width 8–20px against ~150px for the old caption. The `#N  @ref  role  name` table under the artifact is the legend, and `--dimensions` puts `WxH` back on a mark when the size must be visible in the pixels.
- Keep marks a pixel APART rather than merely non-overlapping: the scorer charges a slot that comes within two pixels of a mark already placed, because two numbers that touch read as one longer number — and a slot that only grazed a neighbour on one machine landed on it on another, where a checkbox rounds differently (a 40-checkbox grid passed locally and collided in 19 pairs on CI). Measured minimum gap: 7px on that grid, 2.9px on a 40-field form, 2.0px on a 25-row link list, 2.2px across 181 marks on Hacker News, 0 collisions everywhere.
- Dispatch `eval-sci`, `daemon`, `report` and `init-agents` behind ANY leading flag, known here or not: recognition rest
- Run multi-statement JavaScript through `evaluate`: a statement sequence is treated as a function body and its last expression is returned, and the script is classified from a scan that blanks strings, template literals and comments, so a `;` or a `return` inside a string decides nothing (#120)
- Decide a script's own `return` as a TOKEN outside any nested function, blank regex literals like the values they are, and keep the last statement's value across a trailing `;`: `return(x)` is no longer wrapped into `return (return(x));`, `const f = () => { return 1 }; f()` no longer answers `undefined`, `x = /;/.test(s)` is no longer split inside its regex, and `var a = 1; document.title;` answers the title (#120)
- Give every command the open-ended budget on an iOS session, so a healthy `snapshot`/`click` is no longer interrupted after 25s (#121)
- Report a budget interrupt as `command_timeout` with `SPEL_COMMAND_BUDGET_MS`, whichever side of the interrupt answers first, instead of "was cancelled" (#122)
- Let an iOS command outlast a browser page: a session's per-command WebDriver timeout is 300s, so a `snapshot` of a real hybrid app (measured 76s for a 2.1 MB WDA source dump) no longer fails with `request timed out` (#121)
- Explain a failed XCUITest session with the red doctor checks and `appium driver install xcuitest` instead of a raw WebDriver error (#124)

### Added

- Hybrid-app taps from DOM coordinates: `ios-viewport-offset`, `ios-tap-dom!` and `ios-tap-element!` measure in the WebView and tap natively (#123)


## [v0.9.25] - 2026-08-05

### Changed
- Render test status as a glyph tile in the alternative report
- fix(platform): read /proc/net/route with a single NIO read
- ci(wsl-verify): probe every /proc/net/route read strategy
- refactor(platform): split the WSL gateway lookup into a pure parser
- fix(wsl-diag): report one curl status per probe
- ci(wsl-verify): bridge CDP onto the WSL-facing address
- ci(wsl-verify): pin WSL networking to NAT so the distro has a network


## [v0.9.24] - 2026-08-04

### Changed
- Recognise a daemon started from any spel binary name
- Start the daemon from a native binary of any file name
- Transport snapshot captures as JSON text


## [v0.9.23] - 2026-08-04

### Changed
- Probe TLS-fronted CDP endpoints over HTTPS and WSS
- Serve the fake /json/version endpoint from the JDK, not python3
- Wait for the fake /json/version server instead of sleeping a second
- Fix session list crash, wait output, silent snapshots and response-handler overflow
- docs: require reproduce, RED, then GREEN for reported bugs
- fix: enable http URL protocol in the native image so CDP connect works


## [v0.9.22] - 2026-08-03

### Changed
- fix(daemon): keep sessions alive when user handlers or clients fail


## [v0.9.21] - 2026-08-01

### Changed
- feat(init-agents): warn when generated skill references drift
- ci: wait for matching CI before releasing


## [v0.9.20] - 2026-08-01

### Changed
- docs(agents): harden the native iOS verification loop


## [v0.9.19] - 2026-08-01

### Changed
- chore: prepare v0.9.19 release


## [v0.9.18] - 2026-08-01

### Changed
- feat(ios): expose device orientation controls


## [v0.9.17] - 2026-08-01

### Changed
- Fix native iOS snapshot XML parsing
- ci(security): add CVE dependency scanning


## [v0.9.16] - 2026-07-28

### Changed
- fix(daemon): require accepted CDP before auto-launch succeeds
- Fix auto-launch readiness probe


## [v0.9.15] - 2026-07-27

### Changed
- fix(daemon): heal dead browser handles and make message-less errors diagnosable


## [v0.9.14] - 2026-07-27

### Changed
- docs(agents): centralize release instructions in AGENTS.md
- fix(ci,docs): guard release tag against SPEL_VERSION, document init-agents
- fix(report): flatten step tree, indent header chips


### Changed
- fix(report): drop the step-tree border rails and connector ticks — nesting now reads through indentation and the status icon alone
- fix(report): collapse wrapper steps that only restate the test (or parent step) name, so an expanded card no longer repeats the same sentence before the one line that matters
- fix(report): singular step/attachment chips (`1 step`, not `1 steps`)
- fix(report): the header is now a two-column grid (logo / text), so the summary chips are indented under the title instead of hugging the card edge
- fix(ci): the release workflow now refuses to publish when the tag does not match `resources/SPEL_VERSION`, and re-checks that the built binaries print the tag version — v0.9.13 shipped binaries that self-reported `0.9.12`
- docs(readme): document `spel init-agents` (how to scaffold **and regenerate** the agent + skill files after an upgrade)
- docs(agents): release instructions now live only in `AGENTS.md` ("Releasing"); the README section was removed and the shipped `CI_WORKFLOWS.md` reference points there instead of restating the rules

## [v0.9.13] - 2026-07-27

### Changed
- fix(fonts,deps): bundle Inter/JetBrains Mono, drop font CDNs, bump deps and CI
- fix(daemon,report): handle-free drag, inline failure message, agents ledger docs
- fix(daemon,skills,report): in-page drag, ledger docs, cleaner report
- fix(daemon): drag natively-draggable elements with synthetic HTML5 events (headless Linux never returns mouseup)
- fix(daemon): drive drag with explicit mouse events so it cannot wedge the daemon on headless Linux
- fix(daemon): bound drag with an explicit timeout and keep the command budget under the client transport timeout
- fix(graal): avoid boxed math in the command watchdog budget; document SPEL_COMMAND_BUDGET_MS in README
- fix(daemon): guard commands with a watchdog budget so a wedged action cannot freeze the daemon
- fix(cli,daemon): raise client transport timeout to 30s and stop forcing page load on evaluate
- fix(graal): restore primitive coercions and URI type hints
- Harden CDP ownership, cut latency, refresh docs
- Improve native tooling and agent guidance
- Add structured CLI errors and diagnostics


### Added
- feat(cdp): spel always opens its own tab when attaching to an external browser; user tabs are never hijacked
- feat(cdp): positive tab provenance — only spel-opened tabs can be closed (`tab_not_owned` otherwise)

### Fixed
- fix(cdp): `session list` no longer advertises stale DevTools endpoints (real WebSocket upgrade check)
- fix(cdp): `connect` preflights the endpoint and fails fast with `cdp_endpoint_unreachable` instead of hanging
- fix(cdp): disconnect/shutdown no longer close a user-owned browser, context, or tabs
- fix(page): boxed `Long` args rejected by Playwright `evaluate` are now passed as ints
- fix(daemon): `eval-js` works on a blank page again (no `No page loaded` on the replacement tab created by `tab close`)
- fix(cli): transport timeout raised to 30s and made overridable via `SPEL_CLIENT_TIMEOUT_MS`; 12s produced spurious `client_timeout` on loaded machines
- fix(daemon): `drag` no longer uses Playwright `dragTo` or CDP mouse input, both of which could block past their own timeout on headless Linux and wedge the daemon. The whole gesture is now dispatched in the page: natively draggable sources (links, images, `draggable="true"`) get a full HTML5 drag sequence, everything else gets a pointer + mouse sequence with `--steps` intermediate moves. The drop target is resolved in-page from the drop point, so no element handle crosses the protocol — native-image builds rejected those with `arg.handles[0]: expected guid`
- fix(report): a failing test always shows its failure message inline; the full stack trace stays in a collapsed "Stack trace" panel
- fix(skills): restore the emptied `references/ENVIRONMENT_VARIABLES.md` template and document `SPEL_COMMAND_BUDGET_MS`, the command ledger, and the watchdog in the skill, `COMMON_PROBLEMS.md`, and `FULL_API.md`; `init-agents` tests now fail when a shipped template is blank
- fix(report): the alternative Allure report drops the dark theme and theme toggle, uses the Vis type stack (Inter Variable / JetBrains Mono Variable), and renders status marks as inline SVG glyphs instead of text entities
- fix(fonts): generated artifacts no longer link a font CDN. Inter Variable and JetBrains Mono Variable (latin + latin-ext, ~189 KB woff2) ship in `resources/com/blockether/spel/fonts` and are inlined as base64 `data:` URIs by the new `com.blockether.spel.fonts` namespace, used by both the Allure alternative report and `report->html` / `report->pdf`. Reports and PDFs now render identically offline, in air-gapped CI, and years after archiving; the presenter skill references teach the same rule
- fix(native): the GraalVM `resource-config.json` now includes `com/blockether/spel/fonts/.*`, so the native binary embeds the woff2 faces instead of silently emitting reports with no bundled typography
- fix(daemon): the command watchdog budget now stays below the CLI transport timeout, so a wedged command is reported by the daemon rather than as a client timeout
- fix(daemon): every browser command now runs under a hard budget (`SPEL_COMMAND_BUDGET_MS`, default 25s); a wedged command is interrupted with `command_timeout` and its stack logged, instead of holding the command lock and timing out every later command
- fix(daemon): a command that cannot get the command lock answers `daemon_busy` with the in-flight command, rather than hanging

### Changed
- chore(deps): bump every outdated dependency — SCI 0.12.51 → 0.15.56, charred 1.038 → 1.041, tools.deps 0.31.1629 → 0.31.1638, nREPL 1.5.2 → 1.7.0, cider-nrepl 0.58.0 → 0.62.2, tools.build 0.10.12 → 0.10.14, deps-deploy 0.2.2 → 0.2.5, graal-build-time 1.0.5 → 1.0.6, slf4j-nop 1.7.36 → 2.0.18
- chore(ci): bump pinned GitHub Actions (checkout v7, cache v6, upload-artifact v7, download-artifact v8, github-script v9, setup-node v7, setup-clojure 13.6.1, action-gh-release v3, setup-wsl v7.0.0), Node 20 → 24, and clojure-lsp 2025.11.28 → 2026.07.06
- perf(cli): deadline-based `poll-until` with backoff replaces flat 100 ms polling; orphan-daemon scan cached (250 ms TTL)
- perf(page,locator,helpers,sci): smooth-scroll waits are event-driven (rAF settle, self-capped) instead of fixed sleeps
- chore: remove `verify.sh`; verification is `make lint`, `make test`, `make test-cli`
- docs(skill): document CDP tab ownership, stale-endpoint recovery, and click-is-not-proof verification


## [v0.9.11] - 2026-07-20

### Changed
- chore: bump SPEL_VERSION to 0.9.11 for release
- feat(bridge): refined light popup — header/subtitle, live status dot, layered shadow
- fix(bridge): solid green popup button + soft outer panel, drop gradient
- feat(bridge): gold gradient popup button (bookmarklet feel), no sparkle glyphs
- fix(bridge): drop popup sparkles, solid green button
- feat(bridge): sexy gold gradient popup button + sparkle accents (light theme)
- fix(bridge): polish popup + force light with color-scheme:light only
- fix(bridge): light-theme-only extension popup


## [v0.9.9] - 2026-07-20

### Changed
- fix(win): normalize CRLF in templates + CLI output so Windows CI is green
- test(init-agents): cover --loop=agents parsing + e2e scaffolding
- feat(init-agents): add tool-agnostic --loop=agents flavour (#108)
- feat(ios): automate native apps and hybrid webviews
- docs(changelog): fold phantom v0.9.9 section back into Unreleased
- feat(report): pimp the alternative HTML report to the Blockether brand
- feat(bridge): ship a Manifest V3 browser extension + stable token + per-tab profiles
- build(driver): resolve Playwright driver via tools.deps, not a hand-rolled Maven/CDN fetch
- fix(video): stop matching Playwright's exact English text
- fix(video): treat non-recording page video-path as nil
- build(playwright): bump to 1.61.0 and source driver from Maven artifacts\n\nReplace the dead Playwright driver CDN flow with assembly from\nPlaywright Java's official Maven artifacts, and bump the project\nversion/caches/docs to 1.61.0.
- fix(native): resolve GraalVM reflection warnings in fetch-latest-release
- chore(release): format source + promote CHANGELOG to v0.9.9
- feat(annotate,report): unify Java overlay + alt report on Blockether brand
- feat(bridge): reveal overlay — label every ref at once (v0.14.0)
- feat(bridge): restyle overlay to the Blockether brand, drop animations (v0.13.0)
- feat(bridge): clipboard-copy on pick + light-theme overlay (v0.12.0)
- feat(bridge): branded connect modal for the Ctrl+Shift+K server chooser
- fix(bridge): Local Network Access support for the ejected loader
- feat(bridge): service worker (spel-sw.js) for passive-subresource capture
- feat(bridge): cross-validate CDP-only limits — add network_har, emulate, screenshot (v0.8.0)
- docs(bridge): document CDP-only limits upfront (capture, emulation, HAR)
- feat(bridge): Playwright-style locator composition in spel.js (v0.7.0)
- feat(bridge): close real Playwright-parity gaps in spel.js (v0.6.0)
- feat(bridge): brand the overlay picker in spel's theatrical style
- feat(bridge): token auth, re-inject across navigation, port fallback
- docs(bridge): document the CDP-free bridge across skill + README
- feat(bridge): --eject --bookmarklet / --console loader generators
- feat(bridge): in-page network capture (fetch/XHR/resources)
- feat(bridge): route regular spel commands through the bridge via a saved target
- refactor(cli): fold browser-js into `spel bridge --eject`
- fix(bridge): bound pending map + clean SSE shutdown
- feat(browser): embeddable spel.js engine + loopback bridge, shipped in native image
- fix(report): drop duplicated awesome/ sub-report + cap PR report retention
- chore(ci): cap Allure report retention MAX_REPORTS 15 -> 5


### Added

- feat(bridge): **`reveal` overlay — a "what is what" map** (spel.js v0.14.0).
  New handlers `reveal` / `reveal_stop` / `reveal_toggle` draw one amber box +
  a mono `@ref` chip over EVERY interesting element at once (branded, no
  animation), live-repositioned on scroll/resize via a throttled rAF. Optional
  `selector` scopes it to a subtree; `all` includes non-interesting roles.
  Exposed as `window.__spel.reveal`. Tested on real Chromium (72/72).
- feat(bridge): **overlay + connect modal restyled to the Blockether brand**
  (spel.js v0.13.0) — cream paper (`#faf3eb`), charcoal ink/borders (`#3f3f3f`),
  amber accent (`#ffc420`), Inter/JetBrains-Mono fonts, hard offset shadows and
  sharp corners, matching blockether.com. **All entrance/idle animations removed**
  (breathing glow, blinking masks, pop/fade) — the chrome is now flat and static.
- feat(bridge): **picker copies the picked selector to the clipboard** and shows
  a branded confirmation toast on click (spel.js v0.12.0). Previously clicking an
  element during a pick recorded it silently — nothing landed in the clipboard,
  so "nic się nie działo". Now `pickerClick` writes `@ref` via
  `navigator.clipboard` (with a `document.execCommand('copy')` textarea fallback
  for non-secure/http origins), records it on `picker._lastCopied`, and flashes a
  top-centre "copied @eXXXX" pill.
- feat(bridge): **overlay + connect modal re-themed to a light palette** — the
  label chip, HUD pill, connect modal card and its input/buttons moved off the
  dark slate gradient onto white/paper backgrounds with dark-ink text, keeping
  the tragedy-green accent (`#2EAD33` / darkened `#249329` for text-on-light) and
  serif wordmark. The highlight box stays green.
- feat(bridge): **branded connect modal** for the Ctrl+Shift+K server chooser
  (spel.js v0.11.0). Replaces the native `prompt()` with a spel-themed dialog
  (slate/tragedy-green palette, serif wordmark, theatrical-mask header, focus
  glow, hover states, `spel-fade`/`spel-pop` entrance). `Enter` connects,
  `Escape` or a backdrop click cancels; input is pre-filled with the current
  server. Two real-Chromium tests cover open + Escape-dismiss.
- fix(bridge): **Local Network Access (LNA)** support so the ejected loader
  works on Edge 143+/Chrome 142+, which gate a public origin reaching
  `127.0.0.1` behind a per-origin user permission (the "Permission was denied
  for this request to access the `loopback` address space" error). The loader
  now fetches `spel.js` with `fetch(url, {targetAddressSpace:'loopback'})` — the
  call that raises the grantable prompt instead of the silent no-cors deny — and
  inline-injects it, falling back to `<script src>` on older browsers. `spel.js`
  result POSTs carry the same flag (→ v0.10.0), and the bridge emits
  `Access-Control-Allow-Private-Network: true` for pre-LNA browsers. Documented
  the browser toggle + managed-policy limit in `references/BRIDGE.md`.
- feat(bridge): **service worker** (`spel-sw.js`) for same-origin capture of
  passive subresources the `fetch`/XHR wrappers can't see — `<img>`, `<script>`,
  `<link rel=stylesheet>`, fonts, media (`spel.js` → v0.9.0):
  - New handlers `sw_register` (`{:url … :scope …}`, defaults `/spel-sw.js`),
    `sw_status`, `sw_unregister`. Once the worker controls the page it fetches
    those passive requests itself and forwards a full entry (status, headers,
    body) into the same `network_list`/`network_har` store, tagged `via:"sw"`;
    the `PerformanceObserver` path steps aside to avoid double counting.
  - Bridge serves the worker at `/spel-sw.js`; `spel bridge --eject-sw`
    (`-o <file>`) unpacks it for hosting on your own origin (service workers are
    same-origin only + need https/localhost). Ships inside the native image via
    the existing `com/blockether/spel/browser/.*` resource pattern.
  - Tested on live Chromium (registers, claims control, captures a passive
    `<script>` with real status/headers, then unregisters).
- feat(bridge): cross-validation pass — three capabilities previously
  documented as CDP-only turned out doable in pure in-page JS and are now
  implemented + tested on live Chromium (`spel.js` → v0.8.0):
  - **`network_har`** — serializes the in-page fetch/XHR capture as HAR 1.2
    (`{log:{version,creator,entries[]}}`), so the captured traffic exports to
    any HAR viewer. "No HAR record" was overstated.
  - **`emulate`** — JS-level overrides of `geolocation`, `timezone` (Intl),
    `locale`/`languages`, `userAgent`/`platform`/`vendor`/device metrics, and
    `prefers-color-scheme`/`reduced-motion` via a `matchMedia` override.
    Affects what page scripts read (not the real network stack or CSS
    `@media`) — the JS half of Playwright's env emulation, no CDP.
  - **`screenshot`** — rasterizes the DOM via an SVG `<foreignObject>` →
    canvas → PNG data URL (falls back to the SVG data URL when a cross-origin
    image taints the canvas). "No screenshots" was overstated.
- feat(bridge): locator composition in the selector engine (`spel.js` →
  v0.7.0) — Playwright-style `>>` chains (`.card >> button`) plus `nth=N`
  (negative from end), `first`, `last`, `has-text="…"` and `visible[=…]` filter
  segments; `frame=` steps unified into the same chain resolver. Tested on live
  Chromium.
- feat(bridge): fill the real Playwright-parity gaps in the in-page engine
  (`spel.js` → v0.6.0), all doable in pure JS without CDP and tested against
  live Chromium (61/61):
  - **Dialogs** — `dialog_handler` (policy `accept|dismiss`, `promptText`),
    `dialogs`, `dialogs_clear`: wraps `alert/confirm/prompt/beforeunload`.
  - **Console/errors** — `console_capture`, `console_list`, `console_clear`
    (wraps `console.*` + `onerror` + `unhandledrejection`).
  - **Navigation** — `goto`/`navigate` to any URL (survives via the
    sessionStorage re-inject).
  - **Cookies** — `cookies`, `set_cookie`/`add_cookie`, `clear_cookies`.
  - **Waits** — `wait_for_timeout`, `wait_for_function`, `wait_for_url`,
    `wait_for_load_state`, `wait_for_response`, `wait_for_request`.
  - **Request mocking** — `route`/`unroute`/`routes` (same-origin `fetch`/XHR
    abort or fulfil, short-circuited in the wrapper).
  - **Input/events** — `upload`/`set_input_files` (base64 → `DataTransfer`),
    `tap`, `dispatch_event`.
  - **Frames** — `frames` + `frame=<sel> >> <inner>` same-origin drill-down.
- docs(bridge): documented the new verbs and the revised CDP limits (protocol/
  cross-origin interception, in-page-but-polled events) in `references/BRIDGE.md`
  and the browser `README.md`.
- feat(bridge): the overlay picker now wears spel's theatrical brand — a
  tragedy-green (`#2EAD33`) highlight box with a softly breathing glow and
  rounded corners, a Playwright-style label chip (role · accessible name ·
  size) that follows the cursor, a centred HUD pill (🎭 masks, "click to
  select · Esc to cancel"), and a crosshair cursor while picking. `spel.js`
  → v0.5.0.
- feat(bridge): CDP-free in-page automation — `spel bridge` serves a loopback
  SSE/POST transport + the embedded `spel.js` engine (`window.__spel.invoke`,
  ~80 verbs, in-page network capture, overlay picker keymap). `--eject` unpacks
  the engine (ships inside the native image); `--eject --bookmarklet`/`--console`
  emit loaders; `spel bridge use|off|status` routes regular `spel <verb>`
  commands through the bridge (saved in `~/.spel/bridge.json`).
- docs(bridge): `references/BRIDGE.md`, browser `spel.js` README, SKILL +
  CAPABILITIES coverage (documents limits vs CDP upfront).
- feat(bridge): token-gated transport — the bridge auto-generates a shared
  secret and refuses SSE/command/result requests without it (403), so another
  page in the same browser cannot drive the tab or read captured traffic over
  loopback. `spel bridge use` (same box) picks up the live token automatically
  (from `~/.spel/bridge-runtime.json`); remote bridges take `--token`.
- feat(bridge): re-inject survival — the engine remembers its route per-tab in
  sessionStorage, so a full-page navigation/reload to a page that re-loads
  `spel.js` re-subscribes automatically instead of losing the tab. `connect`
  now replaces any prior connection (single live SSE/WS per tab).
- feat(bridge): if the fixed port is busy, an ephemeral one is chosen instead
  of crashing; the live port + token are published to `~/.spel/bridge-runtime.json`.
- build(driver): resolve the Playwright driver from the Playwright Java Maven
  artifacts (`driver` + `driver-bundle`) instead of the deprecated
  `cdn.playwright.dev` zips (deleted mid-2026), and bump Playwright Java to
  `1.61.0`. `driver.clj` assembles `package/cli.js` + the platform Node runtime
  into the cache dir and points `playwright.cli.dir` at it (prefers `~/.m2`,
  falls back to Maven Central) — unblocks Linux/Windows CI.

### Changed

- feat(report): **pimp the alternative HTML report to the Blockether brand**.
  Swapped the generic soft blur shadows for the signature hard offset-shadow
  system (`--shadow` / `--shadow-md` / `--shadow-brand` amber hero block /
  `--shadow-hard`); the report header now reads as a hero — 2px ink border,
  6px amber offset shadow, and a 4px amber top-accent bar. Summary chips became
  branded stat-tiles (mono tabular-nums value stacked over a mono label, colored
  left-rail per status, hover lift). Test cards gain a hover lift + offset pop.
  Real responsive behavior: header stacks and summary chips become a 2-col grid
  under 768px, the toolbar goes full-width/column, env grid collapses to a
  single column under 560px. Light + dark themes both retuned. 32/32 alt-report
  tests green.
- fix(video): `core/video-path` returns `nil` for a non-recording page again
  (Playwright 1.61 started throwing "Video recording has not been started.");
  detected structurally via `VideoImpl`'s missing `artifact` field, not the
  English message.

## [v0.9.8] - 2026-06-02

### Changed
- build: add <scm> to POM so Clojars links the GitHub repo
- Update Clojure library version to 0.9.7
- Rename Video Recording to Test Reporting in README


## [v0.9.5] - 2026-04-16

### Changed
- feat(alt-report): markdown + HTML attachments, merged HTTP step, assertion polish
- feat(cdp): scan 9223 alongside 9222/9229, extract to platform/common-cdp-ports
- feat(alt-report): merge HTTP step + body, live iframe for HTML attachments


## [v0.9.4] - 2026-04-15

### Changed
- refactor(alt-report): soften green for passed — outline only, no bg tint
- feat(alt-report): tighten failure UX — full trace, per-step errors, no dupe


## [v0.9.3] - 2026-04-14

### Changed
- test(alt-report): add second result in toolbar tests to leave single mode
- feat(alt-report): polish single-test mode + rich-headers showcase test


## [v0.9.2] - 2026-04-13

### Changed
- fix(test): normalize CRLF in sci_eval stdout assertions for Windows
- feat(sci): full math class surface — Math, StrictMath, MathContext, RoundingMode, Random family
- test(sci_eval): pin println + result contract (issue #106)


## [v0.9.1] - 2026-04-13

### Changed
- refactor(report): rename "block" alternative report to "alternative"


## [v0.9.0] - 2026-04-13

### Changed
- feat(page,sci): evaluate-file, add-init-script!, new-cdp-session
- fix(sci): restore pprint/pp/clojure.pprint — all backed by zprint
- refactor(sci): drop clojure.pprint alias + dead fork-sci-ctx


## [v0.8.0] - 2026-04-12

### Changed
- fix(ci): restore 3 orchestrator sentinel strings removed by compression
- fix(evals): restore canonical contract strings lost in caveman compression
- fix(ci): sync tests + docs with spel-test-planner removal, silence boxed math
- refactor(templates): caveman compression + alt-report stats + SCI set-device!
- feat(cli): add --from-json flag to spel report for single-run mode
- feat: add generate-from-results! for single-run / lambda reports
- feat: emit summary.json + report.json alongside alt report HTML
- feat: add Allure label filtering to alternative report
- test: add 16 tests for platform CDP probe, SCI eval, stdlib aliases
- alt report: icon-only theme toggle, no label text
- refactor: extract CDP probe to platform.clj, fix anomaly cause chain
- feat: add page/goto alias + enhance cdp-connect auto-discovery
- sci: add goto as alias for navigate
- feat: add spel/cdp-connect with WSL auto-discovery
- fix: eagerly require zprint.core for GraalVM native-image compat
- fix: restore clojure.set/walk :as aliases, suppress unused-namespace lint
- fix: drop unused :as aliases for clojure.set/walk (lint failure)
- sci: merge markdownify into markdown namespace, add md alias
- feat: SCI env overhaul — stdlib, aliases, zprint, WSL exposure, issue #105
- fix: clear boxed-math warnings in wsl-default-gateway-ip hex parser
- ci(wsl-verify): write test.clj to project dir, not /tmp (cross-fs)
- ci(wsl-verify): add default-jre back to additional-packages
- ci(wsl-verify): set +e to disable errexit in wsl-bash runner step
- ci(wsl-verify): split Clojure script into separate step, fix heredoc
- ci(wsl-verify): find java binary via direct path search, not which
- ci(wsl-verify): set JAVA_HOME for Clojure CLI inside WSL
- ci(wsl-verify): back to Vampire/setup-wsl + wsl --update + set-default-version 2
- ci(wsl-verify): switch to vedantmgoyal9/setup-wsl2 for real WSL2
- ci(wsl-verify): force WSL2, gate Playwright on Node.js availability
- ci(wsl-verify): install WSL-native Node.js for Playwright driver
- ci(wsl-verify): use winning host for connectOverCDP, handle WSL1
- ci(wsl-verify): fix gateway IP parsing + GITHUB_WORKSPACE in WSL
- ci(wsl-verify): actually run spel-level CDP code from inside WSL
- alt report: pin theme toggle INSIDE the header card (not page-shell)
- ci(wsl-verify): drop unrecognised wsl-version input (cleans up warning)
- ci(wsl-verify): force LF line endings, pin WSL2, guard GITHUB_OUTPUT
- ci(wsl-verify): detach Chrome properly + try mirrored networking
- ci(wsl-verify): poll /json/version instead of waiting for DTAP file
- ci(wsl-verify): fall back Chrome→Edge and capture stderr on launch failure
- ci: verify WSL ↔ Windows CDP discovery on a real GH Actions runner
- alt report: keep theme toggle inside .page-shell, not floating in gutter
- dev: surface 403 body + webSocketDebuggerUrl in WSL CDP diagnostic
- daemon: resolve Windows host IP for CDP discovery under WSL
- dev: add WSL → Windows CDP reachability diagnostic
- alt report: pin theme toggle to top-right corner, fixed positioning
- alt report: fix logo layout, description HTML, suite sort, fake request headers
- fix: update CLI bash test assertions for redesigned alternative report
- fix: remove obsolete session_list daemon test — handler moved to CLI-side
- fix: clear GraalVM native-image boxed-math + reflection warnings
- alt report: fix lint, wire workflow to the new customization opts
- chore: drop OBSERVATIONS.md now that the bug hunt shipped
- alt report: sanitize description HTML, expose CLI flags, drop PLAN.md
- alt report: harden customization opts (self-critique fixes)
- alt report: custom logo, description, CSS, and build metadata
- alt report: fix 12 findings from the visual/UX bug hunt
- surface CDP endpoints + clean up session list and CDP discovery
- surface CDP endpoints in `spel session list`
- make trace chunk size configurable and disable chunking by default
- refine alternative report UX and add split-trace loading
- fix mobile UX: lighter descriptions, full-width attachment buttons, HTTP card overflow
- improve alt report mobile UX and visual refinements
- redesign alternative report: neutral palette, search, and sort
- style: refine blockether alternative html report visual design
- redesign alternative report investigation UX
- test: prevent Claude skill path leak into opencode scaffold
- fix: keep alternative reports available in failed empty runs
- fix: make load-results test OS-agnostic — file ordering not guaranteed
- refactor: rename block-report to spel-allure-alternative-html-report with direct require
- test: add CLI assertions for dashboard commands
- feat: add alternative HTML allure report and block report tests
- feat: dashboard commands and allure reporter enhancements
- docs: compress templates with caveman ultra style
- feat: observability dashboard — live browser viewport, activity feed, network, sessions
- test: agentic E2E for confirm-actions TTY gate + lightpanda mock launch
- test: add 17 E2E assertions for sessions 3+4 features
- feat: quick wins — keyboard inserttext, window new, download-path
- feat: agent-browser parity III — config file, upgrade, Lightpanda, dialog/confirm, allow-file, screenshot output flags
- feat: agent-browser parity II — iOS device, profile clone, HAR, auth vault, and more
- feat: agent-browser parity — annotated screenshot, security trio, batch


## [v0.7.11] - 2026-04-02

### Changed
- chore: remove temporary test-e2e seed scaffolding files
- test: add e2e seed scaffolding and apply formatting updates
- fix: treat non-200 CDP probe responses as unavailable endpoints
- fix: M144+ WebSocket-only fallback when /json/version returns 404
- Revert "Fix CDP discovery on Chrome/Edge M144+ WebSocket-only mode"
- Fix CDP discovery on Chrome/Edge M144+ WebSocket-only mode
- fix: render paragraph and listitem in a11y-tree->markdown
- fix: default markdownify to a11y snapshot output and preserve readable line breaks


## [v0.7.10] - 2026-03-30

### Changed
- docs: update templates and agents for unified allure reporter
- chore: remove opencode/sisyphus artifacts, add CLAUDE.md -> AGENTS.md symlink
- fix: add src/clj, test/clj, src/cljc, test/cljc to default source paths
- chore: remove stale planning docs
- chore: clean cljfmt config and bump allure CLI to 3.3.1


## [v0.7.9] - 2026-03-29

### Changed
- docs: document SPEL_ALLURE_CWD env var for read-only filesystems
- fix: support SPEL_ALLURE_CWD for allure on read-only filesystems


## [v0.7.8] - 2026-03-29

### Changed
- fix: prefer globally installed allure CLI over npx
- fix: add missing with-test-server fixture to ct/api_test
- fix: compose around hooks correctly in cli integration tests
- refactor: unify allure reporters and migrate tests to with-testing-page
- docs: rewrite README for clarity and restore testing coverage
- ci(release): shallow clone + targeted fetch to skip gh-pages download
- ci: reuse CI native image artifacts in release — only build linux-arm64
- fix(test): restore session idle timeout after daemon_test to prevent mid-test daemon kill


## [v0.7.7] - 2026-03-18

### Changed
- fix(core): make new-api-context thread-safe with locking (#99)
- docs: comprehensive retry documentation across all skill references
- feat(retry): add retry-guard predicate + catch exceptions in retry loop (#98)
- fix(retry): guard :status cast with number? check in default :retry-when (#98)


## [v0.7.6] - 2026-03-18

### Changed
- fix(allure): pretty-print JSON bodies in cURL and remove 500-char truncation


## [v0.7.5] - 2026-03-18

### Changed
- fix(allure): always use resolved full URL in HTTP exchange attachments


## [v0.7.4] - 2026-03-18

### Changed
- docs: document session idle timeout, CDP lock wait env vars, and SCI APIs
- chore: change session idle timeout default from 1 hour to 30 minutes
- feat(daemon): auto-kill session after 1 hour of inactivity
- fix(test): deref atom values in route lock tests to prevent Atom-as-Number cascade
- feat(daemon): queue on CDP route lock instead of fail-fast, inspired by browser-lock (#97)
- docs: update skills, agent templates, and README for --auto-launch (#97)
- feat: add --auto-launch flag for per-session browser isolation (#97)
- docs: fix diff tooling claim — spel has snapshot diff and pixel diff
- docs: fix 5 factual errors in comparison after deep verification (#90)
- docs: update comparison with agent-browser 0.20.11 (native Rust rewrite)


## [v0.7.3] - 2026-03-15

### Changed
- feat: surface scroll metrics in snapshot -S for overflow containers (#96)
- docs: add agent install hint comment to README (#93)
- fix(ci,ui): reliable PR detection fallback and hash-based URL routing (#94, #95)


## [v0.7.2] - 2026-03-15

### Changed
- docs: point README links to specific reference files instead of generic SKILL.md
- docs: add computed styles for visual comparison to SELECTORS_SNAPSHOTS.md (#92)
- test: harden find-scrollable tests with nested, horizontal, ref-based scrolling (#90)
- feat(page): add SPEL snapshot refs to find-scrollable results (#90)
- docs: add scrollable-page route and Java type gotcha to AGENTS.md
- test: add scrollable discovery and smooth scroll tests (#90)
- feat(sci,daemon): expose scrollable discovery and smooth scroll (#90)
- feat(page): add scrollable discovery, scroll-position, and smooth scroll (#90)
- docs: update skill/agent templates with keyboard-press and allure-ct-reporter (#89, #91)
- docs: expand AGENTS.md testing section with clojure.test, HTML pages, and gotchas
- docs: add issue #89 reproduction steps and learnings to AGENTS.md
- test: add behavioral keyboard press tests with dedicated HTML page (#89)
- fix: expose aria-disabled in snapshots and add single-arg keyboard press (#88, #89)


## [v0.7.1] - 2026-03-14

### Changed
- fix(cli): add --cdp-url alias and regenerate docs with CDP/dialog functions (issue #87)
- fix(cli): markdownify --url handles protocol-less URLs and redirects (issue #86)


## [v0.7.0] - 2026-03-14

### Changed
- fix(graal): add Dialog interface to reflection config for SCI interop
- fix(sci): expose dialog manipulation functions in SCI (issue #85)


## [v0.6.10] - 2026-03-13

### Changed
- feat(daemon): auto-shutdown after CDP idle timeout (default 30min)


## [v0.6.9] - 2026-03-13

### Changed
- chore: regenerate help registry with CDP disconnect/reconnect entries
- fix(test): update daemon_test CDP error message to match new wording
- fix(sci): cdp-reconnect auto-resolves URL from daemon — no args needed
- fix(sci): context-cookies returns Clojure maps (#84) + CDP disconnect/reconnect in eval-sci
- feat(evals,daemon,sci): harden frisco flow and add cdp/url helpers
- fix(daemon): use ProcessHandle for portable pid liveness checks
- fix(evals): handle missing opencode binary in real eval smoke
- feat(evals): enforce real-site fast-path orchestration


## [v0.6.8] - 2026-03-13

### Changed
- fix: resolve boxed math warning in daemon.clj for GraalVM


## [v0.6.7] - 2026-03-13

### Changed
- fix: repair unbalanced parens in core.clj
- fix: skip browser download in Playwright/create, fix README links (#82, #83)
- chore(evals): remove compiled python cache artifacts
- feat(evals): add real orchestrator eval harness and references docs
- fix(daemon): fail fast on shared CDP route ownership


## [v0.6.6] - 2026-03-12

### Changed
- test(cli): cover markdownify and audit workflows
- feat(cli): add markdownify command and unify audit flags
- fix(tests): align init-agents help assertions with current CLI
- docs: fix README rendering and remove duplicated setup notes
- fix(daemon): stop returning snapshots from action responses


## [v0.6.5] - 2026-03-11

### Changed
- docs: align skill and agent templates with session-first CDP workflow
- fix: humanize unknown runtime errors and harden free-port handling
- fix(cli): resolve relative paths against caller CWD, add spel/eval-js alias


## [v0.6.4] - 2026-03-11

### Changed
- fix(search): resolve boxed math warnings for GraalVM native-image


## [v0.6.3] - 2026-03-11

### Changed
- fix(search): remove dead card-style renderer code and ANSI color helpers
- fix(skill): add missing presenter/visual refs to SKILL.md index
- feat(report): port CSS_PATTERNS.md design system into report HTML
- fix(search): forward --json flag from native binary to search command
- feat(search): add DuckDuckGo fallback via HTML endpoint with anti-detection and fast block detection
- feat(search): retry with backoff, markdown output, warning field
- fix: session close timing + forward --channel to daemon subprocess
- feat: restructure 7 audit commands under 'spel audit' umbrella
- fix: CLI bugs (--session screenshot, --session close, --channel) + presenter design system alignment
- chore: mark agent-helpers-v0523 plan complete (105/105)
- fix(tests): align CLI assertion with actual orchestrator template wording
- Merge branch 'agent-helpers-v0523': add 6 QA helpers, wire agents, fix dark theme (v0.5.23)
- feat(helpers): add 6 QA helpers, wire helpers into agents, fix report dark theme
- chore: mark agent-consolidation plan complete in boulder state
- chore: add F-wave review evidence and update notepads
- fix(agents): replace stale spel-test-generator refs in explorer and automator templates
- refactor(agents): consolidate 14 agents to 8, apply Anthropic skill-building principles
- refactor(agents): merge visual-qa into bug-hunter, consolidate to 14 agents
- fix(refs): remove 'navigate directly' anti-patterns from NAVIGATION_WAIT and COMMON_PROBLEMS
- refactor(agents): consolidate to 15 agents, enforce human-like navigation
- feat(autotrainer): add bounded training loop with iteration, feedback, convergence
- feat(init-agents): add simplified core scaffold mode
- feat(helpers): add overview all-frames plus debug and emulate commands
- test(init-agents): add 113 unit tests for agent scaffolding logic
- chore(agents): regenerate test specs README for 0.5.19
- chore: remove LEARNINGS.md and bugfind-reports from repo root
- feat(agents): add orchestrator routing, discovery pipeline, report template enhancements, and Unbound site audit
- fix(init-agents): use record format for tools in product-analyst template
- fix(init-agents): suppress --ns warning when --no-tests is set
- feat(init-agents): add opt-in learnings and markdown report scaffolding
- feat(report): elevate unified report clarity and visual storytelling
- fix(init-agents): improve Claude template transformation fidelity
- chore: wire orphaned refs into subagent-ref-map + delete dogfood-report.html
- fix(init-agents): --no-tests now scaffolds all agents, skips only seed test + specs
- feat(cross-agent-integration): wire product-spec.json downstream + unified spel-report.html
- test(integration): add CLI regression tests for unified template scaffolding
- chore: delete old report templates, update init_agents ref-map, regenerate docs
- refactor(templates): update all remaining template references to spel-report.html
- refactor(product-analyst): update template reference to spel-report.html
- fix(templates): use canonical responsive_behavior dimension name in appendix
- feat(templates): create unified spel-report.html merging QA and product reports
- feat(bug-hunter): add product-spec.json consumption and feature-enriched coverage
- feat(product-analyst): add viewport mandate to Phase 6 coherence audit
- fix(visual-qa): remove duplicate severity/GATE blocks and add product-spec.json input
- chore: regenerate FULL_API.md and help-registry after discovery agent addition
- test(discovery): add CLI regression tests for discovery group
- docs(discovery): update agent counts and group references
- feat(discovery): wire product-analyst into init-agents scaffolding
- feat(discovery): add product-analyst to orchestrator pipelines
- feat(discovery): add product-report.html template with sidebar navigation
- feat(discovery): add product-analyst agent template and PRODUCT_DISCOVERY ref
- refactor(agents): strip duplicated patterns from test pipeline agents
- refactor(agents): strip duplicated patterns from automation+visual agents
- feat(discovery): add discovery workflow prompt
- refactor(agents): strip duplicated patterns from bugfind pipeline agents
- refactor(agents): extract 5 common patterns to AGENT_COMMON.md
- fix(templates): change orchestrator mode from 'agent' to 'all' and add visual checks + viewport sections to QA report
- feat(templates): enforce multi-viewport testing with structured evidence
- fix(templates): require annotated screenshots with action markers as bug evidence
- feat(templates): add visual_checks schema and visual coherence detection
- fix(templates): restore re-snapshot examples and add visual anomaly detection
- style(templates): humanize all 43 agent/skill markdown files
- feat(action-log): add daemon-level action tracking with SRT export
- feat(templates): add QA report HTML template and integrate dogfood workflow
- feat(templates): add 4 orchestrator agents for smart pipeline routing
- fix(templates): allow all bash commands for subagent-only agents
- test(snapshot): add 6 Playwright integration tests for [pos:X,Y W×H] tree output
- feat(snapshot): add [pos:X,Y W×H] screen position to tree output
- feat(visual-diff): add semantic region enrichment with accessibility snapshot labels
- feat(templates): integrate QA methodology into bugfind agent templates
- feat(templates): integrate OpenAI QA methodology into test agent templates
- fix(docs): replace deprecated --eval with eval-sci in source code, README, and docs
- fix(templates): replace deprecated --eval with eval-sci across all 27 template files
- feat(templates): add snapshot-first refs, flavor awareness, and JSON gate artifacts to test agents
- feat(templates): wire test agent pipeline with inter-agent handoffs and contracts
- fix(test-cli): use portable mktemp for macOS compatibility
- fix(docs): add missing search, role, and allure namespaces to generated API docs
- feat(templates): add cookie consent, snapshot-first interaction, and e-commerce patterns to agent templates
- fix: visual diff shows original image with red overlay, snake_case JSON, CLI routing
- fix: correct diff CLI test assertions to use kebab-case jq keys
- feat: expose constants/ and device/ namespaces in SCI eval environment
- fix: correct 5 ref docs to match implementation
- feat: rewrite agent system — 12 agents across 4 groups with contracts, gates, and adversarial bug-finding
- fix: XSS escape all user data in Allure builds page, disable empty failed builds, fix GraalVM boxed-math warnings
- feat: add pixel-level screenshot diffing via pixelmatch + Playwright Canvas
- docs: collapse proxy config and env vars under <details>
- fix: implement SPEL_CHANNEL env var + sync README env var table with code
- feat(cdp): add Microsoft Edge support to auto-connect discovery
- fix(ci): reduce gh-pages size from 12.7 GB to under 1 GB — fix GitHub Pages deployment
- fix(cdp): handle Chrome 136+ --user-data-dir requirement and M144+ WebSocket-only mode
- feat: delete chrome_cookies, remove state export, add --auto-connect
- fix(release): fetch tags in release job, reorder Clojars deploy after GitHub release
- chore: trigger release v0.5.5
- Delete LESSONS.md
- fix: restore CI workflows and remove unused chrome-cookies require
- feat: persist launch flags per session — --cdp only needed once
- chore: remove .github directory (cove images, CI workflows)
- fix: rename --eval to eval-sci in CLI tests, add LESSONS.md and snapshot rules to AGENTS.md
- feat: CDP support, eval-js/eval-sci rename, snapshot event listeners & clickable detection
- fix(docs): update stale style tier counts in docstrings (12/24/36 → 16/31/44)
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- docs: update AGENTS.md and README for subagent architecture + --only flag
- feat(templates): add workflow prompts for visual and automation subagent groups
- feat(templates): add 5 new subagent templates (explorer, automator, interactive, presenter, visual-qa)
- feat(templates): add presenter reference docs extracted from visual-explainer
- refactor(templates): refine test agent templates with priority refs
- refactor(init-agents): add --only flag for selective scaffolding
- refactor(init-agents): add --only flag for selective scaffolding
- feat(templates): add visual QA reference guide
- feat(cli): add -- args separator with *command-line-args* binding
- feat(cli): add -- args separator with *command-line-args* binding
- refactor(init-agents): smart ref assignment data structure
- refactor(init-agents): deprecate --loop=vscode with error message
- feat(snapshot): add position properties to MINIMAL style tier
- fix: resolve strict mode violations in drag tests and boxed math warnings
- docs: collapse guided install section by default
- docs: agentic guided install with env vars reference
- docs: remove test example from README, link to SKILL.md instead
- feat: add drag-and-drop support with options across library, SCI, and CLI layers
- fix: support --profile and --channel flags in --eval mode
- docs: add library-first development order rule to AGENTS.md
- chore: add verify.sh script and update verification docs
- test: add CLI bash and Clojure tests for styles, viewport, device, --browser
- fix: replace bc with awk in test assertions and skip headed tests without display
- fix: make stitch command browser-aware via SPEL_BROWSER env
- feat: add viewport, device tracking, and --browser to snapshot pipeline
- feat: computed styles, clipboard ops, snapshot diff — library + daemon + CLI + SCI
- Update opencode.json
- chore: remove artistry override, B3 uses direct ACP spawn
- chore: add ultrabrain→opus override for CoVe B2
- chore: override artistry category to zai-coding-plan/glm-5 for CoVe B3
- fix: move One-Shot Installation heading outside details (visible header, collapsed content)
- docs: wrap LLM install section in details collapse
- docs: add one-shot LLM installation section + surface corporate CA setup (fixes #47)
- docs(skill): add daemon hang/kill troubleshooting to COMMON_PROBLEMS.md
- docs: add release process to AGENTS.md — tag-only, never manual
- release: bump to next dev version 0.5.3
- release: v0.5.2 (v0.5.1 tag immutable, re-releasing as 0.5.2)
- release: bump to next dev version 0.5.2
- release: v0.5.1 — eprintln helpers, versioned skill, profile docs, CI fix
- fix(ci): Allure workflow now runs on failed CI — shows failures in builds page (BLO-163)
- docs(skill): add daemon launch modes comparison (Mode 1/2/3) (BLO-162)
- docs(skill): add profile vs state comparison, Edge support, Quick Reference (BLO-162)
- refactor: replace (binding [*out* *err*]) with eprintln/eprint helpers (BLO-160)
- feat(skill): add skill-version field and agent validation (BLO-161)
- fix(docs): audit and correct comparison file — 6 sections had stale claims
- docs: rewrite POTENTIAL_TASKS.md — correct 13 features mislabeled as missing
- chore: remove TASK-003 (sequential refs) — rejected by design, hash refs preferred
- chore: remove 9 completed tasks from TASKS.md — verified via CLI
- fix(docs): mark link URLs and structured refs as done in 0.5.0 — verified via CLI
- release: v0.5.0 — extensions, download, flat snapshots, profile support, comparison update
- feat(cli): add --extension flag for loading Chrome extensions (fixes #41)
- fix(cli): detect URL at any position in open command args
- fix(ci): add concurrency group and retry logic to pr-cleanup workflow
- fix(graal): add type hints to Files/write call for native image compatibility
- feat: Edge/Chrome profile support with persistent context, cookie injection, and logging
- fix: increment injected counter and reorder copy-cookies-db! for fail-fast
- fix: support Edge and other Chromium browsers for cookie decryption
- chore: add CoVe report screenshot
- fix: update test assertion to use example.org
- fix: replace example.com with example.org
- chore: add CoVe report screenshot
- feat: add --flat option for snapshot command
- chore: add CoVe report screenshot
- fix(ui): correct mobile view for PRs (fixes #63)
- fix(ui): correct mobile view for PRs (fixes #63)
- feat: add download CLI command (fixes #40)
- fix: resolve GraalVM reflection and boxed math warnings in daemon.clj and sci_env.clj
- fix: video save-as - close page/context before saveAs (fixes #43)
- fix: resolve CI failures - unused vars lint, console get dispatch
- feat: update TASK-013 - preview structure, network/console get, page refs (AC-20..AC-25)
- feat: unified snapshot enrichment - URLs, refs map, network/console scoping
- fix(ci): only run Allure Report on successful CI builds
- docs(agents): daemon session isolation rules
- fix(sci_eval): propagate call_log/selector in SCI error responses
- feat(daemon): propagate Playwright error context in CLI output
- fix: wrap unbound page refs with with-testing-page in skill docs
- fix(templates): migrate all templates from old fixtures to with-testing-page/with-testing-api
- fix(screenshot): crop-to-content for content shorter than viewport (#52)
- fix(ci): merge release + deploy-clojars into single job
- release: v0.4.2 — viewport on open, crop-to-content screenshots (#33)
- remove .opencode/skills/ - auto-generated, shouldn't be tracked
- remove opencode agents/prompts cruft, keep oh-my-opencode.json + skills/
- release: v0.4.1
- chore: bump oh-my-opencode 3.8.5 → 3.9.0
- feat: validate URLs on open — reject invalid domains with clear error (closes #36)
- fix: replace hardcoded snapshot refs with generic <sel> and @ref in CLI help text
- feat: smooth scroll + element scroll across library, SCI, CLI, and daemon
- chore: regenerate help-registry and FULL_API.md
- test: add 18 snapshot ref stability tests covering determinism, structural independence, and disambiguation
- Switch spel daemon to virtual threads (BLO-132)
- feat: make stealth mode automatic for all CLI and eval commands
- feat: show page URL, title, and description in snapshot and eval output
- feat: close --all-sessions, show ref table on not-found error
- fix: skip daemon restart when already running in headed mode
- Merge branch 'main' into opencode/nimble-island
- enforce @ prefix for snapshot refs — bare refs no longer auto-detected
- feat: auto-persist sessions by default, fix session list socket detection
- feat: deterministic content-hash snapshot refs, get-by-ref, snapshot --all, codegen improvements
- Fix search reliability — block detection, faster timeouts, diagnostics, --debug flag
- Add opts arity to page/wait-for-function for explicit timeout control
- feat: add Thread/sleep, System, File interop to SCI sandbox + codegen→eval round-trip tests
- release: bump version to 0.4.0
- fix: rename spel/eval-js → spel/evaluate in test-cli.sh bash test
- Rename SCI embedded API to mirror library function names exactly
- Fix console/page-error output showing empty text in --eval mode
- Fix Safari TransformStream shim to return wrapper objects, not native instances
- Update README subtitle: highlight E2E testing, normalize punctuation
- Add Google Search module with CLI, SCI eval, and lazy pagination
- fix: only set is_pr=true when PR number is available
- BLO-118: reposition README — Swiss Army Knife for agents
- fix: standardize margins and positions between list and grid views in allure-index
- fix: merge view toggle and theme toggle into single toolbar
- fix: remove emoji icon from no-results empty state
- fix: render stats above builds list on filtered views
- Upgrade Safari TransformStream shim detection to test real zip.js patterns
- Add stealth mode, state export (cookies + localStorage), and standardized CLI naming
- Style theme toggle to match view-toggle pill container
- Add step type badges ([API], [UI], [UI+API]) to Allure reports
- Remove unnecessary start!/stop! from --eval examples across skill docs
- Fix corporate proxy install example to set both env vars together
- Consolidate README Quick Start and document shared-vs-separate Playwright traces
- Improve HTTP exchange display and add Allure report-only verification shortcut
- Inject MutationObserver-based markdown renderer into Allure reports
- Unify step, ui-step, api-step macros with composable options
- Add opencode.json
- Replace rich HTML exchange panels with markdown attachments
- Equalize margins above and below filter pills in dashboard
- Replace Allure plugin with auto network capture in test fixtures
- Fix date-group headline font-size mismatch between list and grid views
- Add Allure plugin for full-height HTML attachments
- Fixes
- Fix Keychain args for macOS profile, improve HTML preview toggle
- Fixes
- Fixes
- Fixes
- fixes
- Fixes
- Fixes
- Fixes
- In progress
- In progress
- feat: add rich HTTP exchange reporting for both API and browser network responses
- fix: pr-cleanup marks cancelled on close, improve merged styling, fix kondo hook
- fix: keep stats visible on filter, add Cancelled to PR stats
- fix: add Cancelled pill to static HTML, improve mobile search layout
- feat: add cancelled build status support across backend and landing page
- chore: add .claude/ to .gitignore
- chore: remove .claude/ directory — not permitted in this repo
- refactor: merge api namespace into core — eliminate com.blockether.spel.api
- fix: escape quotes in Allure workflow Clojure code
- fix: deduplicate PR comment link, add update-pr-statuses! for accurate CI status
- fix: coerce max-n to long for GraalVM primitive math
- feat: make PR badge URL configurable via pr_url field in pr-builds.json
- fix: PR badge links to GitHub PR, force scrollbar to prevent tab layout shift
- fix: unify list/grid max-width to 64rem (was 56rem list, none grid)
- feat: add PR tabs, merge tracking, and Clojure-based PR management to landing page
- fix: show workflow run_number for PR cards and enable stats dashboard on landing page
- fix: guard pr/ checkout with grep to avoid false-positive ls-tree exit code
- feat: add stats dashboard and date labels to test reports landing page
- BLO-108: fix README - remove allure section, add features overview, fix broken refs
- test: rewrite video recording test with meaningful browser activity
- feat: add PR reports to builds list on landing page
- fix: video attachments not appearing in Allure reports
- fix: resolve session flag passing and macOS timeout in CLI tests
- fix: resolve 6 pre-existing CLI test failures in test-cli.sh
- fix: add driver/ensure-driver! before stitch in native CLI
- feat: host PR Allure reports on GitHub Pages at /pr/<number>/
- fix: resolve boxed math warning and viewport sizing in stitch
- refactor: rewrite stitch to use Playwright instead of AWT/ImageIO
- fix: resolve remaining test-cli.sh failures + add ImageIO to native-image config
- fix: align test-cli.sh assertions with actual CLI --json output format
- ci: bust stale Playwright browser cache (v2 key)
- ci: fix PLAYWRIGHT_BROWSERS_PATH - use GITHUB_ENV instead of literal tilde
- ci: remove continue-on-error from test-cli.sh step
- ci: fix PLAYWRIGHT_BROWSERS_PATH tilde expansion + add daemon log debug output
- ci: mark test-cli.sh as continue-on-error (requires network access)
- BLO-20: fix Allure PR comment - rename CT to clojure-test, add CLI test reference
- BLO-20: add missing CLI tests (stitch, annotate, tool --help) and AGENTS.md CLI test requirements
- ci: add test-cli.sh bash regression to CI workflow (Linux + macOS)
- AGENTS.md: add test count sanity check with bash regression details
- BLO-99: fix Windows CI (tmpdir), add stitch docs to SKILL template, update verification checklist
- BLO-99: add SCI env registration, Allure attachments, and CLI/SCI tests for stitch
- BLO-99: add spel stitch command for vertical image stitching
- BLO-97: add video+Allure integration tests and fix *video-path* binding
- AGENTS.md: validate-safe-graal + install-local before CLI integration tests
- fix: unmatched delimiter in cli_integration_test — missing quote after escaped-tmpdir
- AGENTS.md: reorder verification checklist per Opus review (format first, no redundancy, better secret scan)
- AGENTS.md: verification checklist — binary note, pre-push scan, CI green check
- BLO-96: Fix Windows SCI tests — use java.io.tmpdir and escape backslashes
- BLO-96: Suppress unused-import in clj-kondo config (fixes CI lint)
- AGENTS.md: add make test full suite to verification checklist
- AGENTS.md: expand verification checklist — explicit test-cli-clj and test-cli steps
- BLO-96: Fix CI lint warnings and Windows path escaping in tests
- Fix Safari trace viewer, trim AGENTS.md, restructure Makefile and README
- Add left padding to grid cards
- Drop square aspect-ratio on mobile grid cards
- Fix list view width, mobile toggle overlap
- Update SKILL docs and agent instructions for SCI file I/O
- Add spel/navigate alias, file I/O, and Base64 to SCI sandbox
- Fix Allure reporter test-count parity with Lazytest
- Builds list grid: square cards, 6 per row, wider container
- CT reporter parity, builds list redesign, lint cleanup
- Bump opencode
- BLO-93: Fix CI lint - commit clj-kondo config to suppress unused-binding
- BLO-93: Fix CI lint failure - reduce unused-binding to info level
- Restore .lsp/config.edn with unused-public-var linter disabled
- Restore original LIST view - flex column with horizontal cards
- Fix lint issues: suppress unused public var warnings for API functions
- Make build cards full-width on mobile for better list experience
- Fix mobile responsiveness: 3-column grid on builds page, integrate API testing into Quick Start
- Add inline video player modal to Allure reports
- Fix compact view: equal-sized grid cards with min-height
- Add cljfmt indents for new API testing macros
- Add clojure.test version for new API testing functions
- Update README with full API testing docs and fix compact view on mobile
- Never commit lsp and clj-kondo
- Remove clj-kondo and .lsp configs
- Add clj-kondo hooks for with-testing-api and with-page-api macros
- feat: add page-api, context-api, with-testing-api, with-page-api for easy API testing
- feat: add compact view toggle to builds list + keep 50 builds
- fix: use workflow_run trigger so in-progress build shows correct run number
- fix: remove unused private var allure-result-file?
- ci: add commit author to Allure report name
- docs: vary SKILL link phrasing, tighten README prose
- docs: revamp README — lean landing page, merge Quick Start with with-testing-page
- chore: bump Allure CLI 3.1.0 → 3.2.0, add lint rule, clean up SKILL template
- docs: update SKILL.md.template with with-testing-page section and verification checklist
- style: format 14 namespaces with clojure-lsp
- docs: add with-testing-page to SKILL template and SKILL check rule to AGENTS.md
- feat: auto-attach traces to clojure.test Allure reporter
- feat: add merge-reports CLI command and library API
- feat: add :profile and browser launch opts to with-testing-page
- docs: add with-testing-page section to README
- docs: clarify clj-kondo CLI unavailability in AGENTS.md
- test: add with-testing-page tests for Lazytest and clojure.test
- feat: add clj-kondo hooks for with-testing-page dual-arity
- refactor: use shared devices namespace in daemon
- feat: add with-testing-page macro with device/viewport support and auto-tracing
- feat: add shared device and viewport presets namespace
- BLO-93: Align badges to left on mobile
- BLO-93: Fix version badge spacing on mobile
- Split Allure into two workflows to fix artifact conflict
- BLO-92: Store theme globally (shared across all builds)
- BLO-92: Remove unused with-video-page refer to fix lint
- BLO-92: Scope Allure localStorage by report path with 30-day auto-cleanup
- Fix artifact conflict - remove duplicate in-progress pages deploy
- Hide chevron arrow on in-progress build cards
- feat: add in-progress build tracking to Allure landing page
- Remove headless environments documentation from README
- Delete ARCHITECTURE-NOTES.md
- BLO-90: Remove LATEST badge from builds list
- feat: add commit author to build list and Allure report
- fix: eliminate boxed math and reflection warnings for GraalVM safety
- fix: unify landing page badges as HTML pills with visible borders
- feat: replace Python CI scripts with Clojure ci-assemble command
- feat: add spel/help and spel/source for runtime API discovery
- BLO-91: Update SKILL template with version badges documentation
- BLO-91: Add version badges to Allure landing page
- Add video recording support for browser sessions
- BLO-89: Add headless environments documentation to README
- BLO-89: Add xvfb-run auto-detection for headless codegen record
- BLO-90: Add version badges to Allure landing page
- Add cleanup step before Allure test runs
- BLO-87: Fix unmatched paren in test file
- BLO-87: Fix boxed math warnings and Windows line-ending test failures
- BLO-87: Fix ct namespace nesting in Allure - use common parent
- BLO-88: Fix Allure workflow - make github-pages environment conditional for PRs
- BLO-88: Enable Allure reports for PR/branches
- BLO-86: Release v0.3.0
- fix: add missing closing paren in navigate handler defmethod
- BLO-85: Fix --screenshot flag not saving file when used with open command
- feat: add report/clean config to both Allure reporters, run clojure.test in CI
- feat: browser config via system properties (slow-mo, engine, interactive)
- feat: polymorphic report builder (report->html, report->pdf) with typed entries
- feat: auto-capture console/errors in --eval, add anti-sleep and SPA wait guidance
- feat: auto-resolve @eN refs in all spel/ functions, fix eval timeout architecture
- docs: remove verbose test mapping table from AGENTS.md
- docs: add testing policy to AGENTS.md, add stdout/stderr capture tests
- fix: capture both stdout and stderr, preserve output on error
- fix: capture stdout during sci_eval to enable println in --eval mode
- fix: resolve lint errors in markdown_test and init_agents
- refactor: global defmethod hooks for clojure.test Allure reporter
- feat: add clojure.test Allure reporter with auto-injected fixtures
- feat: add --flavour flag to init-agents for clojure.test support
- fix: replace Lazytest references with spel.allure in all templates, fix test-e2e paths
- feat: add spel.markdown (from/to-markdown-table), replace data.json with charred
- feat: add --no-tests flag to init-agents for interactive-only scaffolding
- docs: add prefer --eval over standalone CLI rule to SKILL template
- feat: include test-e2e in Playwright tracing source directories
- fix: promote Native CLI to top-level README section, fix broken nav anchor
- feat: add spel.roles namespace, replace AriaRole imports with idiomatic Clojure vars
- chore: set SPEL_VERSION to 0.2.0 for release
- feat: Allure version in builds, classpath trace viewer, with-traced-page default, cljfmt
- feat: eval file support, --load-state flag, SCI page/locator namespaces
- feat: interactive test mode, spel.allure imports, init-agents --dir, code formatting
- feat: add storage state API, version management, and SKILL docs
- feat: add --interactive to eval mode, storage state examples to codegen help
- release: update README and CHANGELOG for v0.0.2
- Consolidate README: replace verbose Usage with SKILL reference, fix lint warning, update docs and tests
- Fix Trace Viewer source path resolution: prepend source directory prefix
- Fix Trace Viewer source mapping and codegen CLI improvements
- Add corporate CA certificate support for SSL-inspecting proxies
- feat(allure): integrate Allure reporting with lazytest macros
- Auto-save in-flight traces when daemon shuts down or context is replaced
- Fix codegen selector translation: extract role name, exact flag, and wrap assertions with assert-that
- Add tracing API to SCI --eval environment
- Add routeFromHAR and routeWebSocket wrappers for complete mock API coverage
- Add inspector and show-trace commands for Playwright visual tools
- Replace inline changelog in README with reference to CHANGELOG.md
- Make landing page title, subtitle, and logo configurable via env vars
- Fix spel install, update SKILL.md docs, add changelog, remove Makefile refs from templates
- Add clickable CI pipeline link to run number in Allure landing page
- Fix Allure report logo 404: copy logo file into report output dir
- Add landing page with clickable SHA links, date grouping, and pass/fail status
- Add logo support for Allure report header
- Add commit SHA and message to Allure report history entries
- Add per-build Allure report archives with clickable history links
- Fix reflection warning in detect-source-dirs String/join call
- Fix Allure history: switch to JSONL mechanism with configurable limit
- Auto-detect Clojure source dirs for Playwright trace sources
- Update clj-kondo sinker hook import
- Add --full flag to annotate: annotate all elements, not just viewport
- Fix try plus hook
- Fix Allure report history: preserve history/ across runs for trend dropdown
- release: update README and CHANGELOG for v0.0.1
- Fix unresolved clojure.string namespace in integration test
- Update clj-kondo sinker hook import
- Regenerate scaffolded agents, skills, and specs from updated templates
- Update agent and skill templates for E2E test workflow
- Update README: rationale, install docs, Gatekeeper note, remove macOS Intel
- Fix Windows double .exe in build, remove macOS amd64 from CI, update Allure workflow
- Expand integration test coverage for codegen and tracing
- Improve daemon robustness and native image compatibility
- Fix codegen for Playwright 1.58+ locator kinds and improve CLI error messages
- Expand context options mapping and add anomaly helpers to core
- Add Allure trace groups, test title binding, and fixture error guards
- Preserve Allure report data across deployments so historical test results don't 404
- Auto-update README version on tag, consolidate release creation in native-image workflow
- Add dev build install instructions, macOS Gatekeeper note, and full release docs to README
- Fix CI: use Java driver CLI for browser install, handle Windows native-image.cmd, normalize cache paths
- Remove spel/start! from SKILL.md snapshot examples — daemon manages browser lifecycle
- Guard test fixtures against anomaly maps from safe-wrapped Playwright calls
- Upgrade setup-clojure to @13.5, restrict lint/graal checks to Linux
- Persist Allure history across CI runs via actions/cache
- Fix CI: install all browser deps, guard close-page!/close-browser! against anomaly maps
- Fix Windows CI: default to bash shell for cross-platform compatibility
- Add Allure Report badge and test report screenshots to README
- Fix README nav link and eval column wrapping, harden test fixture cleanup
- Update README.md and allure.yml
- Fix description of all spel evaluation
- Fix codegen for default roles
- Attempt to fix CI
- fix: use built-in Allure 3 reporter pipeline in CI, remove redundant allure generate
- Add docs regeneration scripts
- feat: daemon-backed --eval mode, per-command help, CLI tests, CI smoke tests, strict mode docs
- Initial commit


## [v0.6.2] - 2026-03-11

### Changed
- fix(skill): add missing presenter/visual refs to SKILL.md index


## [v0.6.1] - 2026-03-11

### Changed
- feat(report): port CSS_PATTERNS.md design system into report HTML


## [v0.6.0] - 2026-03-11

### Changed
- fix(search): forward --json flag from native binary to search command
- feat(search): add DuckDuckGo fallback via HTML endpoint with anti-detection and fast block detection


## [v0.5.27] - 2026-03-10

### Changed
- feat(search): retry with backoff, markdown output, warning field


## [v0.5.26] - 2026-03-10

### Changed
- fix: session close timing + forward --channel to daemon subprocess


## [v0.5.25] - 2026-03-10

### Changed
- feat: restructure 7 audit commands under 'spel audit' umbrella


## [v0.5.24] - 2026-03-10

### Changed
- fix: CLI bugs (--session screenshot, --session close, --channel) + presenter design system alignment
- chore: mark agent-helpers-v0523 plan complete (105/105)
- fix(tests): align CLI assertion with actual orchestrator template wording


## [v0.5.23] - 2026-03-10

### Changed
- Merge branch 'agent-helpers-v0523': add 6 QA helpers, wire agents, fix dark theme (v0.5.23)
- feat(helpers): add 6 QA helpers, wire helpers into agents, fix report dark theme
- chore: mark agent-consolidation plan complete in boulder state
- chore: add F-wave review evidence and update notepads
- fix(agents): replace stale spel-test-generator refs in explorer and automator templates


## [v0.5.22] - 2026-03-09

### Changed
- refactor(agents): consolidate 14 agents to 8, apply Anthropic skill-building principles


## [v0.5.21] - 2026-03-09

### Changed
- refactor(agents): merge visual-qa into bug-hunter, consolidate to 14 agents


## [v0.5.20] - 2026-03-09

### Changed
- fix(refs): remove 'navigate directly' anti-patterns from NAVIGATION_WAIT and COMMON_PROBLEMS
- refactor(agents): consolidate to 15 agents, enforce human-like navigation
- feat(autotrainer): add bounded training loop with iteration, feedback, convergence
- feat(init-agents): add simplified core scaffold mode
- feat(helpers): add overview all-frames plus debug and emulate commands
- test(init-agents): add 113 unit tests for agent scaffolding logic
- chore(agents): regenerate test specs README for 0.5.19


## [v0.5.19] - 2026-03-08

### Changed
- chore: remove LEARNINGS.md and bugfind-reports from repo root
- feat(agents): add orchestrator routing, discovery pipeline, report template enhancements, and Unbound site audit


## [v0.5.18] - 2026-03-08

### Changed
- fix(init-agents): use record format for tools in product-analyst template
- fix(init-agents): suppress --ns warning when --no-tests is set


## [v0.5.17] - 2026-03-07

### Changed
- feat(init-agents): add opt-in learnings and markdown report scaffolding


## [v0.5.16] - 2026-03-07

### Changed
- feat(report): elevate unified report clarity and visual storytelling
- fix(init-agents): improve Claude template transformation fidelity
- chore: wire orphaned refs into subagent-ref-map + delete dogfood-report.html
- fix(init-agents): --no-tests now scaffolds all agents, skips only seed test + specs
- feat(cross-agent-integration): wire product-spec.json downstream + unified spel-report.html
- test(integration): add CLI regression tests for unified template scaffolding
- chore: delete old report templates, update init_agents ref-map, regenerate docs
- refactor(templates): update all remaining template references to spel-report.html
- refactor(product-analyst): update template reference to spel-report.html
- fix(templates): use canonical responsive_behavior dimension name in appendix
- feat(templates): create unified spel-report.html merging QA and product reports
- feat(bug-hunter): add product-spec.json consumption and feature-enriched coverage
- feat(product-analyst): add viewport mandate to Phase 6 coherence audit
- fix(visual-qa): remove duplicate severity/GATE blocks and add product-spec.json input
- chore: regenerate FULL_API.md and help-registry after discovery agent addition
- test(discovery): add CLI regression tests for discovery group
- docs(discovery): update agent counts and group references
- feat(discovery): wire product-analyst into init-agents scaffolding
- feat(discovery): add product-analyst to orchestrator pipelines
- feat(discovery): add product-report.html template with sidebar navigation
- feat(discovery): add product-analyst agent template and PRODUCT_DISCOVERY ref
- refactor(agents): strip duplicated patterns from test pipeline agents
- refactor(agents): strip duplicated patterns from automation+visual agents
- feat(discovery): add discovery workflow prompt
- refactor(agents): strip duplicated patterns from bugfind pipeline agents
- refactor(agents): extract 5 common patterns to AGENT_COMMON.md


## [v0.5.15] - 2026-03-07

### Changed
- fix(templates): change orchestrator mode from 'agent' to 'all' and add visual checks + viewport sections to QA report
- feat(templates): enforce multi-viewport testing with structured evidence
- fix(templates): require annotated screenshots with action markers as bug evidence
- feat(templates): add visual_checks schema and visual coherence detection
- fix(templates): restore re-snapshot examples and add visual anomaly detection
- style(templates): humanize all 43 agent/skill markdown files
- feat(action-log): add daemon-level action tracking with SRT export
- feat(templates): add QA report HTML template and integrate dogfood workflow


## [v0.5.14] - 2026-03-06

### Changed
- feat(templates): add 4 orchestrator agents for smart pipeline routing


## [v0.5.13] - 2026-03-06

### Changed
- fix(templates): allow all bash commands for subagent-only agents
- test(snapshot): add 6 Playwright integration tests for [pos:X,Y W×H] tree output
- feat(snapshot): add [pos:X,Y W×H] screen position to tree output
- feat(visual-diff): add semantic region enrichment with accessibility snapshot labels


## [v0.5.12] - 2026-03-06

### Changed
- feat(templates): integrate QA methodology into bugfind agent templates
- feat(templates): integrate OpenAI QA methodology into test agent templates
- fix(docs): replace deprecated --eval with eval-sci in source code, README, and docs
- fix(templates): replace deprecated --eval with eval-sci across all 27 template files
- feat(templates): add snapshot-first refs, flavor awareness, and JSON gate artifacts to test agents
- feat(templates): wire test agent pipeline with inter-agent handoffs and contracts
- fix(test-cli): use portable mktemp for macOS compatibility
- fix(docs): add missing search, role, and allure namespaces to generated API docs
- feat(templates): add cookie consent, snapshot-first interaction, and e-commerce patterns to agent templates


## [v0.5.11] - 2026-03-06

### Changed
- fix: visual diff shows original image with red overlay, snake_case JSON, CLI routing


## [v0.5.10] - 2026-03-06

### Changed
- fix: correct diff CLI test assertions to use kebab-case jq keys
- feat: expose constants/ and device/ namespaces in SCI eval environment
- fix: correct 5 ref docs to match implementation
- feat: rewrite agent system — 12 agents across 4 groups with contracts, gates, and adversarial bug-finding
- fix: XSS escape all user data in Allure builds page, disable empty failed builds, fix GraalVM boxed-math warnings
- feat: add pixel-level screenshot diffing via pixelmatch + Playwright Canvas
- docs: collapse proxy config and env vars under <details>
- fix: implement SPEL_CHANNEL env var + sync README env var table with code


## [v0.5.9] - 2026-03-05

### Changed
- feat(cdp): add Microsoft Edge support to auto-connect discovery
- fix(ci): reduce gh-pages size from 12.7 GB to under 1 GB — fix GitHub Pages deployment


## [v0.5.8] - 2026-03-05

### Changed
- fix(cdp): handle Chrome 136+ --user-data-dir requirement and M144+ WebSocket-only mode


## [v0.5.7] - 2026-03-05

### Changed
- feat: delete chrome_cookies, remove state export, add --auto-connect


## [v0.5.6] - 2026-03-05

### Changed
- fix(release): fetch tags in release job, reorder Clojars deploy after GitHub release
- chore: trigger release v0.5.5
- Delete LESSONS.md
- fix: restore CI workflows and remove unused chrome-cookies require
- feat: persist launch flags per session — --cdp only needed once
- chore: remove .github directory (cove images, CI workflows)
- fix: rename --eval to eval-sci in CLI tests, add LESSONS.md and snapshot rules to AGENTS.md
- feat: CDP support, eval-js/eval-sci rename, snapshot event listeners & clickable detection
- fix(docs): update stale style tier counts in docstrings (12/24/36 → 16/31/44)
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- test: integration tests for subagent scaffolding, --args, and snapshot position props
- docs: update AGENTS.md and README for subagent architecture + --only flag
- feat(templates): add workflow prompts for visual and automation subagent groups
- feat(templates): add 5 new subagent templates (explorer, automator, interactive, presenter, visual-qa)
- feat(templates): add presenter reference docs extracted from visual-explainer
- refactor(templates): refine test agent templates with priority refs
- refactor(init-agents): add --only flag for selective scaffolding
- refactor(init-agents): add --only flag for selective scaffolding
- feat(templates): add visual QA reference guide
- feat(cli): add -- args separator with *command-line-args* binding
- feat(cli): add -- args separator with *command-line-args* binding
- refactor(init-agents): smart ref assignment data structure
- refactor(init-agents): deprecate --loop=vscode with error message
- feat(snapshot): add position properties to MINIMAL style tier
- fix: resolve strict mode violations in drag tests and boxed math warnings
- docs: collapse guided install section by default


## [v0.5.3] - 2026-03-05

### Changed
- docs: agentic guided install with env vars reference
- docs: remove test example from README, link to SKILL.md instead
- feat: add drag-and-drop support with options across library, SCI, and CLI layers
- fix: support --profile and --channel flags in --eval mode
- docs: add library-first development order rule to AGENTS.md
- chore: add verify.sh script and update verification docs
- test: add CLI bash and Clojure tests for styles, viewport, device, --browser
- fix: replace bc with awk in test assertions and skip headed tests without display
- fix: make stitch command browser-aware via SPEL_BROWSER env
- feat: add viewport, device tracking, and --browser to snapshot pipeline
- feat: computed styles, clipboard ops, snapshot diff — library + daemon + CLI + SCI
- Update opencode.json
- chore: remove artistry override, B3 uses direct ACP spawn
- chore: add ultrabrain→opus override for CoVe B2
- chore: override artistry category to zai-coding-plan/glm-5 for CoVe B3
- fix: move One-Shot Installation heading outside details (visible header, collapsed content)
- docs: wrap LLM install section in details collapse
- docs: add one-shot LLM installation section + surface corporate CA setup (fixes #47)
- docs(skill): add daemon hang/kill troubleshooting to COMMON_PROBLEMS.md
- docs: add release process to AGENTS.md — tag-only, never manual
- release: bump to next dev version 0.5.3


## [v0.5.2] - 2026-03-02

### Changed
- release: v0.5.2 (v0.5.1 tag immutable, re-releasing as 0.5.2)
- release: bump to next dev version 0.5.2
- release: v0.5.1 — eprintln helpers, versioned skill, profile docs, CI fix
- fix(ci): Allure workflow now runs on failed CI — shows failures in builds page (BLO-163)
- docs(skill): add daemon launch modes comparison (Mode 1/2/3) (BLO-162)
- docs(skill): add profile vs state comparison, Edge support, Quick Reference (BLO-162)
- refactor: replace (binding [*out* *err*]) with eprintln/eprint helpers (BLO-160)
- feat(skill): add skill-version field and agent validation (BLO-161)
- fix(docs): audit and correct comparison file — 6 sections had stale claims
- docs: rewrite POTENTIAL_TASKS.md — correct 13 features mislabeled as missing
- chore: remove TASK-003 (sequential refs) — rejected by design, hash refs preferred
- chore: remove 9 completed tasks from TASKS.md — verified via CLI
- fix(docs): mark link URLs and structured refs as done in 0.5.0 — verified via CLI


## [v0.5.2] - 2026-03-02

### Changed
- refactor: replace (binding [*out* *err*]) with eprintln/eprint helpers (BLO-160)
- feat(skill): add skill-version field and agent validation — agents detect stale skills (BLO-161)
- docs(skill): add profile vs state comparison, Edge/channel support, Chrome profile docs (BLO-162)
- docs(skill): add daemon launch modes comparison (Mode 1/2/3) (BLO-162)
- fix(ci): Allure workflow now runs on failed CI — failures visible in builds page (BLO-163)
- fix(docs): audit and correct comparison file — 6 sections had stale claims
- docs: rewrite POTENTIAL_TASKS.md — correct 13 features mislabeled as missing

## [v0.5.0] - 2026-03-01

### Changed
- release: v0.5.0 — extensions, download, flat snapshots, profile support, comparison update
- feat(cli): add --extension flag for loading Chrome extensions (fixes #41)
- fix(cli): detect URL at any position in open command args
- fix(ci): add concurrency group and retry logic to pr-cleanup workflow
- fix(graal): add type hints to Files/write call for native image compatibility
- feat: Edge/Chrome profile support with persistent context, cookie injection, and logging
- fix: increment injected counter and reorder copy-cookies-db! for fail-fast
- fix: support Edge and other Chromium browsers for cookie decryption
- chore: add CoVe report screenshot
- fix: update test assertion to use example.org
- fix: replace example.com with example.org
- chore: add CoVe report screenshot
- feat: add --flat option for snapshot command
- chore: add CoVe report screenshot
- fix(ui): correct mobile view for PRs (fixes #63)
- fix(ui): correct mobile view for PRs (fixes #63)
- feat: add download CLI command (fixes #40)
- fix: resolve GraalVM reflection and boxed math warnings in daemon.clj and sci_env.clj
- fix: video save-as - close page/context before saveAs (fixes #43)
- fix: resolve CI failures - unused vars lint, console get dispatch
- feat: update TASK-013 - preview structure, network/console get, page refs (AC-20..AC-25)
- feat: unified snapshot enrichment - URLs, refs map, network/console scoping
- fix(ci): only run Allure Report on successful CI builds
- docs(agents): daemon session isolation rules
- fix(sci_eval): propagate call_log/selector in SCI error responses
- feat(daemon): propagate Playwright error context in CLI output
- fix: wrap unbound page refs with with-testing-page in skill docs
- fix(templates): migrate all templates from old fixtures to with-testing-page/with-testing-api
- fix(screenshot): crop-to-content for content shorter than viewport (#52)
- fix(ci): merge release + deploy-clojars into single job


## [v0.5.0] - 2026-03-01

### Added
- feat(cli): add `--extension` flag for loading Chrome extensions (#41)
- feat: Edge/Chrome profile support with persistent context, cookie injection, and logging
- feat: add `--flat` option for snapshot command
- feat: add `download` CLI command (#40)
- feat: unified snapshot enrichment — URLs, refs map, network/console scoping
- feat: preview structure, network/console get, page refs
- feat(daemon): propagate Playwright error context in CLI output

### Fixed
- fix(cli): detect URL at any position in `open` command args
- fix(graal): add type hints to `Files/write` call for native image compatibility
- fix: increment injected counter and reorder `copy-cookies-db!` for fail-fast
- fix: support Edge and other Chromium browsers for cookie decryption
- fix(ui): correct mobile view for PRs (#63)
- fix: resolve GraalVM reflection and boxed math warnings in `daemon.clj` and `sci_env.clj`
- fix: video `save-as` — close page/context before `saveAs` (#43)
- fix: resolve CI failures — unused vars lint, console get dispatch
- fix(sci_eval): propagate `call_log`/`selector` in SCI error responses
- fix(templates): migrate all templates from old fixtures to `with-testing-page`/`with-testing-api`
- fix(screenshot): `crop-to-content` for content shorter than viewport (#52)
- fix(ci): merge release + deploy-clojars into single job
- fix(ci): add concurrency group and retry logic to pr-cleanup workflow
- fix(ci): only run Allure Report on successful CI builds

## [v0.4.2] - 2026-02-27

### Added
- feat: `spel open --viewport WxH` — set viewport dimensions during navigation in one step
- feat: `spel screenshot --crop-to-content` — crop full-page screenshots to actual content height

### Fixed
- fix: full-page screenshots capturing empty space below content due to Playwright's `max(viewport, content)` behavior (#33)

## [v0.4.1] - 2026-02-27

### Added
- feat: validate URLs on `spel open` — reject invalid domains with clear error message (closes #36)
- feat: smooth scroll + element scroll across library, SCI, CLI, and daemon
- test: add 18 snapshot ref stability tests covering determinism, structural independence, and disambiguation

### Changed
- Switch spel daemon to virtual threads (BLO-132)
- Make stealth mode automatic for all CLI and eval commands
- Show page URL, title, and description in snapshot and eval output
- Close `--all-sessions`, show ref table on not-found error
- Enforce `@` prefix for snapshot refs — bare refs no longer auto-detected
- Auto-persist sessions by default, fix session list socket detection
- Deterministic content-hash snapshot refs, `get-by-ref`, `snapshot --all`, codegen improvements

### Fixed
- fix: replace hardcoded snapshot refs with generic `<sel>` and `@ref` in CLI help text
- fix: skip daemon restart when already running in headed mode
- fix: search reliability — block detection, faster timeouts, diagnostics, `--debug` flag

## [v0.4.0] - 2026-02-25

### Changed
- release: bump version to 0.4.0
- fix: rename spel/eval-js → spel/evaluate in test-cli.sh bash test
- Rename SCI embedded API to mirror library function names exactly
- Fix console/page-error output showing empty text in --eval mode
- Fix Safari TransformStream shim to return wrapper objects, not native instances
- Update README subtitle: highlight E2E testing, normalize punctuation
- Add Google Search module with CLI, SCI eval, and lazy pagination
- fix: only set is_pr=true when PR number is available
- BLO-118: reposition README — Swiss Army Knife for agents
- fix: standardize margins and positions between list and grid views in allure-index
- fix: merge view toggle and theme toggle into single toolbar
- fix: remove emoji icon from no-results empty state
- fix: render stats above builds list on filtered views
- Upgrade Safari TransformStream shim detection to test real zip.js patterns
- Add stealth mode, state export (cookies + localStorage), and standardized CLI naming
- Style theme toggle to match view-toggle pill container
- Add step type badges ([API], [UI], [UI+API]) to Allure reports
- Remove unnecessary start!/stop! from --eval examples across skill docs
- Fix corporate proxy install example to set both env vars together
- Consolidate README Quick Start and document shared-vs-separate Playwright traces
- Improve HTTP exchange display and add Allure report-only verification shortcut
- Inject MutationObserver-based markdown renderer into Allure reports
- Unify step, ui-step, api-step macros with composable options
- Add opencode.json
- Replace rich HTML exchange panels with markdown attachments
- Equalize margins above and below filter pills in dashboard
- Replace Allure plugin with auto network capture in test fixtures
- Fix date-group headline font-size mismatch between list and grid views
- Add Allure plugin for full-height HTML attachments
- Fixes
- Fix Keychain args for macOS profile, improve HTML preview toggle
- Fixes
- Fixes
- Fixes
- fixes
- Fixes
- Fixes
- Fixes
- In progress
- In progress
- feat: add rich HTTP exchange reporting for both API and browser network responses
- fix: pr-cleanup marks cancelled on close, improve merged styling, fix kondo hook
- fix: keep stats visible on filter, add Cancelled to PR stats
- fix: add Cancelled pill to static HTML, improve mobile search layout
- feat: add cancelled build status support across backend and landing page
- chore: add .claude/ to .gitignore
- chore: remove .claude/ directory — not permitted in this repo
- refactor: merge api namespace into core — eliminate com.blockether.spel.api
- fix: escape quotes in Allure workflow Clojure code
- fix: deduplicate PR comment link, add update-pr-statuses! for accurate CI status
- fix: coerce max-n to long for GraalVM primitive math
- feat: make PR badge URL configurable via pr_url field in pr-builds.json
- fix: PR badge links to GitHub PR, force scrollbar to prevent tab layout shift
- fix: unify list/grid max-width to 64rem (was 56rem list, none grid)
- feat: add PR tabs, merge tracking, and Clojure-based PR management to landing page
- fix: show workflow run_number for PR cards and enable stats dashboard on landing page
- fix: guard pr/ checkout with grep to avoid false-positive ls-tree exit code
- feat: add stats dashboard and date labels to test reports landing page
- BLO-108: fix README - remove allure section, add features overview, fix broken refs
- test: rewrite video recording test with meaningful browser activity
- feat: add PR reports to builds list on landing page
- fix: video attachments not appearing in Allure reports
- fix: resolve session flag passing and macOS timeout in CLI tests
- fix: resolve 6 pre-existing CLI test failures in test-cli.sh
- fix: add driver/ensure-driver! before stitch in native CLI
- feat: host PR Allure reports on GitHub Pages at /pr/<number>/
- fix: resolve boxed math warning and viewport sizing in stitch
- refactor: rewrite stitch to use Playwright instead of AWT/ImageIO
- fix: resolve remaining test-cli.sh failures + add ImageIO to native-image config
- fix: align test-cli.sh assertions with actual CLI --json output format
- ci: bust stale Playwright browser cache (v2 key)
- ci: fix PLAYWRIGHT_BROWSERS_PATH - use GITHUB_ENV instead of literal tilde
- ci: remove continue-on-error from test-cli.sh step
- ci: fix PLAYWRIGHT_BROWSERS_PATH tilde expansion + add daemon log debug output
- ci: mark test-cli.sh as continue-on-error (requires network access)
- BLO-20: fix Allure PR comment - rename CT to clojure-test, add CLI test reference
- BLO-20: add missing CLI tests (stitch, annotate, tool --help) and AGENTS.md CLI test requirements
- ci: add test-cli.sh bash regression to CI workflow (Linux + macOS)
- AGENTS.md: add test count sanity check with bash regression details
- BLO-99: fix Windows CI (tmpdir), add stitch docs to SKILL template, update verification checklist
- BLO-99: add SCI env registration, Allure attachments, and CLI/SCI tests for stitch
- BLO-99: add spel stitch command for vertical image stitching
- BLO-97: add video+Allure integration tests and fix *video-path* binding
- AGENTS.md: validate-safe-graal + install-local before CLI integration tests
- fix: unmatched delimiter in cli_integration_test — missing quote after escaped-tmpdir
- AGENTS.md: reorder verification checklist per Opus review (format first, no redundancy, better secret scan)
- AGENTS.md: verification checklist — binary note, pre-push scan, CI green check
- BLO-96: Fix Windows SCI tests — use java.io.tmpdir and escape backslashes
- BLO-96: Suppress unused-import in clj-kondo config (fixes CI lint)
- AGENTS.md: add make test full suite to verification checklist
- AGENTS.md: expand verification checklist — explicit test-cli-clj and test-cli steps
- BLO-96: Fix CI lint warnings and Windows path escaping in tests
- Fix Safari trace viewer, trim AGENTS.md, restructure Makefile and README
- Add left padding to grid cards
- Drop square aspect-ratio on mobile grid cards
- Fix list view width, mobile toggle overlap
- Update SKILL docs and agent instructions for SCI file I/O
- Add spel/navigate alias, file I/O, and Base64 to SCI sandbox
- Fix Allure reporter test-count parity with Lazytest
- Builds list grid: square cards, 6 per row, wider container
- CT reporter parity, builds list redesign, lint cleanup
- Bump opencode


## [v0.3.1] - 2026-02-22

### Changed
- BLO-93: Fix CI lint - commit clj-kondo config to suppress unused-binding
- BLO-93: Fix CI lint failure - reduce unused-binding to info level
- Restore .lsp/config.edn with unused-public-var linter disabled
- Restore original LIST view - flex column with horizontal cards
- Fix lint issues: suppress unused public var warnings for API functions
- Make build cards full-width on mobile for better list experience
- Fix mobile responsiveness: 3-column grid on builds page, integrate API testing into Quick Start
- Add inline video player modal to Allure reports
- Fix compact view: equal-sized grid cards with min-height
- Add cljfmt indents for new API testing macros
- Add clojure.test version for new API testing functions
- Update README with full API testing docs and fix compact view on mobile
- Never commit lsp and clj-kondo
- Remove clj-kondo and .lsp configs
- Add clj-kondo hooks for with-testing-api and with-page-api macros
- feat: add page-api, context-api, with-testing-api, with-page-api for easy API testing
- feat: add compact view toggle to builds list + keep 50 builds
- fix: use workflow_run trigger so in-progress build shows correct run number
- fix: remove unused private var allure-result-file?
- ci: add commit author to Allure report name
- docs: vary SKILL link phrasing, tighten README prose
- docs: revamp README — lean landing page, merge Quick Start with with-testing-page
- chore: bump Allure CLI 3.1.0 → 3.2.0, add lint rule, clean up SKILL template
- docs: update SKILL.md.template with with-testing-page section and verification checklist
- style: format 14 namespaces with clojure-lsp
- docs: add with-testing-page to SKILL template and SKILL check rule to AGENTS.md
- feat: auto-attach traces to clojure.test Allure reporter
- feat: add merge-reports CLI command and library API
- feat: add :profile and browser launch opts to with-testing-page
- docs: add with-testing-page section to README
- docs: clarify clj-kondo CLI unavailability in AGENTS.md
- test: add with-testing-page tests for Lazytest and clojure.test
- feat: add clj-kondo hooks for with-testing-page dual-arity
- refactor: use shared devices namespace in daemon
- feat: add with-testing-page macro with device/viewport support and auto-tracing
- feat: add shared device and viewport presets namespace
- BLO-93: Align badges to left on mobile
- BLO-93: Fix version badge spacing on mobile
- Split Allure into two workflows to fix artifact conflict
- BLO-92: Store theme globally (shared across all builds)
- BLO-92: Remove unused with-video-page refer to fix lint
- BLO-92: Scope Allure localStorage by report path with 30-day auto-cleanup
- Fix artifact conflict - remove duplicate in-progress pages deploy
- Hide chevron arrow on in-progress build cards
- feat: add in-progress build tracking to Allure landing page
- Remove headless environments documentation from README
- Delete ARCHITECTURE-NOTES.md
- BLO-90: Remove LATEST badge from builds list
- feat: add commit author to build list and Allure report
- fix: eliminate boxed math and reflection warnings for GraalVM safety
- fix: unify landing page badges as HTML pills with visible borders
- feat: replace Python CI scripts with Clojure ci-assemble command
- feat: add spel/help and spel/source for runtime API discovery
- BLO-91: Update SKILL template with version badges documentation
- BLO-91: Add version badges to Allure landing page
- Add video recording support for browser sessions
- BLO-89: Add headless environments documentation to README
- BLO-89: Add xvfb-run auto-detection for headless codegen record
- BLO-90: Add version badges to Allure landing page
- Add cleanup step before Allure test runs
- BLO-87: Fix unmatched paren in test file
- BLO-87: Fix boxed math warnings and Windows line-ending test failures
- BLO-87: Fix ct namespace nesting in Allure - use common parent
- BLO-88: Fix Allure workflow - make github-pages environment conditional for PRs
- BLO-88: Enable Allure reports for PR/branches


## [v0.3.0] - 2026-02-20

### Changed
- BLO-86: Release v0.3.0
- fix: add missing closing paren in navigate handler defmethod
- BLO-85: Fix --screenshot flag not saving file when used with open command
- feat: add report/clean config to both Allure reporters, run clojure.test in CI
- feat: browser config via system properties (slow-mo, engine, interactive)
- feat: polymorphic report builder (report->html, report->pdf) with typed entries
- feat: auto-capture console/errors in --eval, add anti-sleep and SPA wait guidance
- feat: auto-resolve @eN refs in all spel/ functions, fix eval timeout architecture
- docs: remove verbose test mapping table from AGENTS.md
- docs: add testing policy to AGENTS.md, add stdout/stderr capture tests
- fix: capture both stdout and stderr, preserve output on error
- fix: capture stdout during sci_eval to enable println in --eval mode
- fix: resolve lint errors in markdown_test and init_agents
- refactor: global defmethod hooks for clojure.test Allure reporter
- feat: add clojure.test Allure reporter with auto-injected fixtures
- feat: add --flavour flag to init-agents for clojure.test support
- fix: replace Lazytest references with spel.allure in all templates, fix test-e2e paths
- feat: add spel.markdown (from/to-markdown-table), replace data.json with charred
- feat: add --no-tests flag to init-agents for interactive-only scaffolding
- docs: add prefer --eval over standalone CLI rule to SKILL template
- feat: include test-e2e in Playwright tracing source directories
- fix: promote Native CLI to top-level README section, fix broken nav anchor
- feat: add spel.roles namespace, replace AriaRole imports with idiomatic Clojure vars


## [v0.2.0] - 2026-02-19

### Changed
- chore: set SPEL_VERSION to 0.2.0 for release
- feat: Allure version in builds, classpath trace viewer, with-traced-page default, cljfmt
- feat: eval file support, --load-state flag, SCI page/locator namespaces


## [v0.1.0] - 2026-02-19

### Changed
- feat: interactive test mode, spel.allure imports, init-agents --dir, code formatting
- feat: add storage state API, version management, and SKILL docs
- feat: add --interactive to eval mode, storage state examples to codegen help
- release: update README and CHANGELOG for v0.0.2


## [v0.0.2] - 2026-02-19

### Changed
- Consolidate README: replace verbose Usage with SKILL reference, fix lint warning, update docs and tests
- Fix Trace Viewer source path resolution: prepend source directory prefix
- Fix Trace Viewer source mapping and codegen CLI improvements
- Add corporate CA certificate support for SSL-inspecting proxies
- feat(allure): integrate Allure reporting with lazytest macros
- Auto-save in-flight traces when daemon shuts down or context is replaced
- Fix codegen selector translation: extract role name, exact flag, and wrap assertions with assert-that
- Add tracing API to SCI --eval environment
- Add routeFromHAR and routeWebSocket wrappers for complete mock API coverage
- Add inspector and show-trace commands for Playwright visual tools
- Replace inline changelog in README with reference to CHANGELOG.md
- Make landing page title, subtitle, and logo configurable via env vars
- Fix spel install, update SKILL.md docs, add changelog, remove Makefile refs from templates
- Add clickable CI pipeline link to run number in Allure landing page
- Fix Allure report logo 404: copy logo file into report output dir
- Add landing page with clickable SHA links, date grouping, and pass/fail status
- Add logo support for Allure report header
- Add commit SHA and message to Allure report history entries
- Add per-build Allure report archives with clickable history links
- Fix reflection warning in detect-source-dirs String/join call
- Fix Allure history: switch to JSONL mechanism with configurable limit
- Auto-detect Clojure source dirs for Playwright trace sources
- Update clj-kondo sinker hook import
- Add --full flag to annotate: annotate all elements, not just viewport
- Fix try plus hook
- Fix Allure report history: preserve history/ across runs for trend dropdown
- release: update README and CHANGELOG for v0.0.1


## [v0.0.1] - 2026-02-18

### Changed
- Fix unresolved clojure.string namespace in integration test
- Update clj-kondo sinker hook import
- Regenerate scaffolded agents, skills, and specs from updated templates
- Update agent and skill templates for E2E test workflow
- Update README: rationale, install docs, Gatekeeper note, remove macOS Intel
- Fix Windows double .exe in build, remove macOS amd64 from CI, update Allure workflow
- Expand integration test coverage for codegen and tracing
- Improve daemon robustness and native image compatibility
- Fix codegen for Playwright 1.58+ locator kinds and improve CLI error messages
- Expand context options mapping and add anomaly helpers to core
- Add Allure trace groups, test title binding, and fixture error guards
- Preserve Allure report data across deployments so historical test results don't 404
- Auto-update README version on tag, consolidate release creation in native-image workflow
- Add dev build install instructions, macOS Gatekeeper note, and full release docs to README
- Fix CI: use Java driver CLI for browser install, handle Windows native-image.cmd, normalize cache paths
- Remove spel/start! from SKILL.md snapshot examples — daemon manages browser lifecycle
- Guard test fixtures against anomaly maps from safe-wrapped Playwright calls
- Upgrade setup-clojure to @13.5, restrict lint/graal checks to Linux
- Persist Allure history across CI runs via actions/cache
- Fix CI: install all browser deps, guard close-page!/close-browser! against anomaly maps
- Fix Windows CI: default to bash shell for cross-platform compatibility
- Add Allure Report badge and test report screenshots to README
- Fix README nav link and eval column wrapping, harden test fixture cleanup
- Update README.md and allure.yml
- Fix description of all spel evaluation
- Fix codegen for default roles
- Attempt to fix CI
- fix: use built-in Allure 3 reporter pipeline in CI, remove redundant allure generate
- Add docs regeneration scripts
- feat: daemon-backed --eval mode, per-command help, CLI tests, CI smoke tests, strict mode docs
- Initial commit


### Added
- Initial release of spel
- Clojure wrapper for Microsoft Playwright 1.58.0
- Browser automation: page navigation, screenshots, PDF generation
- Locator API for element selection and interaction
- Network interception and request/response handling
- Assertions module for test verification
- Input handling (keyboard, mouse, touch)
- Frame support for iframe interaction
- Codegen: record browser sessions and transform JSONL to Clojure test code
- CLI tools: install, codegen, open, screenshot, pdf
- Page exploration utility
- Allure test reporting integration
- OpenCode agent scaffolding via init-agents

[Unreleased]: https://github.com/Blockether/spel/compare/v0.9.30...HEAD
[v0.5.0]: https://github.com/Blockether/spel/compare/v0.4.2...v0.5.0
[v0.4.2]: https://github.com/Blockether/spel/compare/v0.4.1...v0.4.2
[v0.4.1]: https://github.com/Blockether/spel/compare/v0.4.0...v0.4.1
[v0.4.0]: https://github.com/Blockether/spel/compare/v0.3.1...v0.4.0
[v0.3.1]: https://github.com/Blockether/spel/releases/tag/v0.3.1
[v0.3.0]: https://github.com/Blockether/spel/releases/tag/v0.3.0
[v0.2.0]: https://github.com/Blockether/spel/releases/tag/v0.2.0
[v0.1.0]: https://github.com/Blockether/spel/releases/tag/v0.1.0
[v0.0.2]: https://github.com/Blockether/spel/releases/tag/v0.0.2
[v0.0.1]: https://github.com/Blockether/spel/releases/tag/v0.0.1
[v0.5.2]: https://github.com/Blockether/spel/releases/tag/v0.5.2
[v0.5.3]: https://github.com/Blockether/spel/releases/tag/v0.5.3
[v0.5.6]: https://github.com/Blockether/spel/releases/tag/v0.5.6
[v0.5.7]: https://github.com/Blockether/spel/releases/tag/v0.5.7
[v0.5.8]: https://github.com/Blockether/spel/releases/tag/v0.5.8
[v0.5.9]: https://github.com/Blockether/spel/releases/tag/v0.5.9
[v0.5.10]: https://github.com/Blockether/spel/releases/tag/v0.5.10
[v0.5.11]: https://github.com/Blockether/spel/releases/tag/v0.5.11
[v0.5.12]: https://github.com/Blockether/spel/releases/tag/v0.5.12
[v0.5.13]: https://github.com/Blockether/spel/releases/tag/v0.5.13
[v0.5.14]: https://github.com/Blockether/spel/releases/tag/v0.5.14
[v0.5.15]: https://github.com/Blockether/spel/releases/tag/v0.5.15
[v0.5.16]: https://github.com/Blockether/spel/releases/tag/v0.5.16
[v0.5.17]: https://github.com/Blockether/spel/releases/tag/v0.5.17
[v0.5.18]: https://github.com/Blockether/spel/releases/tag/v0.5.18
[v0.5.19]: https://github.com/Blockether/spel/releases/tag/v0.5.19
[v0.5.20]: https://github.com/Blockether/spel/releases/tag/v0.5.20
[v0.5.21]: https://github.com/Blockether/spel/releases/tag/v0.5.21
[v0.5.22]: https://github.com/Blockether/spel/releases/tag/v0.5.22
[v0.5.23]: https://github.com/Blockether/spel/releases/tag/v0.5.23
[v0.5.24]: https://github.com/Blockether/spel/releases/tag/v0.5.24
[v0.5.25]: https://github.com/Blockether/spel/releases/tag/v0.5.25
[v0.5.26]: https://github.com/Blockether/spel/releases/tag/v0.5.26
[v0.5.27]: https://github.com/Blockether/spel/releases/tag/v0.5.27
[v0.6.0]: https://github.com/Blockether/spel/releases/tag/v0.6.0
[v0.6.1]: https://github.com/Blockether/spel/releases/tag/v0.6.1
[v0.6.2]: https://github.com/Blockether/spel/releases/tag/v0.6.2
[v0.6.3]: https://github.com/Blockether/spel/releases/tag/v0.6.3
[v0.6.4]: https://github.com/Blockether/spel/releases/tag/v0.6.4
[v0.6.5]: https://github.com/Blockether/spel/releases/tag/v0.6.5
[v0.6.6]: https://github.com/Blockether/spel/releases/tag/v0.6.6
[v0.6.7]: https://github.com/Blockether/spel/releases/tag/v0.6.7
[v0.6.8]: https://github.com/Blockether/spel/releases/tag/v0.6.8
[v0.6.9]: https://github.com/Blockether/spel/releases/tag/v0.6.9
[v0.6.10]: https://github.com/Blockether/spel/releases/tag/v0.6.10
[v0.7.0]: https://github.com/Blockether/spel/releases/tag/v0.7.0
[v0.7.1]: https://github.com/Blockether/spel/releases/tag/v0.7.1
[v0.7.2]: https://github.com/Blockether/spel/releases/tag/v0.7.2
[v0.7.3]: https://github.com/Blockether/spel/releases/tag/v0.7.3
[v0.7.4]: https://github.com/Blockether/spel/releases/tag/v0.7.4
[v0.7.5]: https://github.com/Blockether/spel/releases/tag/v0.7.5
[v0.7.6]: https://github.com/Blockether/spel/releases/tag/v0.7.6
[v0.7.7]: https://github.com/Blockether/spel/releases/tag/v0.7.7
[v0.7.8]: https://github.com/Blockether/spel/releases/tag/v0.7.8
[v0.7.9]: https://github.com/Blockether/spel/releases/tag/v0.7.9
[v0.7.10]: https://github.com/Blockether/spel/releases/tag/v0.7.10
[v0.7.11]: https://github.com/Blockether/spel/releases/tag/v0.7.11
[v0.8.0]: https://github.com/Blockether/spel/releases/tag/v0.8.0
[v0.9.0]: https://github.com/Blockether/spel/releases/tag/v0.9.0
[v0.9.1]: https://github.com/Blockether/spel/releases/tag/v0.9.1
[v0.9.2]: https://github.com/Blockether/spel/releases/tag/v0.9.2
[v0.9.3]: https://github.com/Blockether/spel/releases/tag/v0.9.3
[v0.9.4]: https://github.com/Blockether/spel/releases/tag/v0.9.4
[v0.9.5]: https://github.com/Blockether/spel/releases/tag/v0.9.5
[v0.9.8]: https://github.com/Blockether/spel/releases/tag/v0.9.8
[v0.9.9]: https://github.com/Blockether/spel/releases/tag/v0.9.9
[v0.9.11]: https://github.com/Blockether/spel/releases/tag/v0.9.11
[v0.9.13]: https://github.com/Blockether/spel/releases/tag/v0.9.13
[v0.9.14]: https://github.com/Blockether/spel/releases/tag/v0.9.14
[v0.9.15]: https://github.com/Blockether/spel/releases/tag/v0.9.15
[v0.9.16]: https://github.com/Blockether/spel/releases/tag/v0.9.16
[v0.9.17]: https://github.com/Blockether/spel/releases/tag/v0.9.17
[v0.9.18]: https://github.com/Blockether/spel/releases/tag/v0.9.18
[v0.9.19]: https://github.com/Blockether/spel/releases/tag/v0.9.19
[v0.9.20]: https://github.com/Blockether/spel/releases/tag/v0.9.20
[v0.9.21]: https://github.com/Blockether/spel/releases/tag/v0.9.21
[v0.9.22]: https://github.com/Blockether/spel/releases/tag/v0.9.22
[v0.9.23]: https://github.com/Blockether/spel/releases/tag/v0.9.23
[v0.9.24]: https://github.com/Blockether/spel/releases/tag/v0.9.24
[v0.9.25]: https://github.com/Blockether/spel/releases/tag/v0.9.25
[v0.9.26]: https://github.com/Blockether/spel/releases/tag/v0.9.26
[v0.9.27]: https://github.com/Blockether/spel/releases/tag/v0.9.27
[v0.9.28]: https://github.com/Blockether/spel/releases/tag/v0.9.28
[v0.9.29]: https://github.com/Blockether/spel/releases/tag/v0.9.29
[v0.9.30]: https://github.com/Blockether/spel/releases/tag/v0.9.30
