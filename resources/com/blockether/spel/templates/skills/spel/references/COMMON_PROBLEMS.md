# Common problems and troubleshooting

## 1. "Session already running"

Previous `spel/start!` wasn't cleaned up:

```clojure
(spel/stop!)
(spel/start!)
```

If that fails, end the daemon with spel's own kill — it force-closes, then
destroys the process, and cleans up the socket/PID files:

```bash
spel --session <name> health   # what is it actually doing?
spel --session <name> kill     # end it now, even mid-command
spel kill --all-sessions       # every spel daemon, orphans included
```

**Never** `pkill -f "Google Chrome"` as a default recovery step — it kills the
user's browser. `pkill`/`rm -f /tmp/spel-*.sock` are last resorts only if
`spel kill` itself is unavailable.

## 2. CAPTCHA / bot detection

Headless Chromium is detectable (missing GPU, UA patterns, `navigator.webdriver`). Stealth is on by default in the CLI; for stubborn sites try headed + real cookies:

```bash
spel open https://protected-site.com                   # stealth (default)
spel --interactive open https://protected-site.com     # stealth + headed

# Stealth + real Chrome cookies (most authentic)
spel state export --profile ~/Library/Application\ Support/Google/Chrome/Default -o auth.json
spel --load-state auth.json open https://protected-site.com

# Disable stealth if it causes problems
spel --no-stealth open https://protected-site.com
```

Library + stealth:

```clojure
(require '[com.blockether.spel.stealth :as stealth])
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw
                                {:headless false
                                 :args (stealth/stealth-args)
                                 :ignore-default-args (stealth/stealth-ignore-default-args)})]
    (core/with-context [ctx (core/new-context browser)]
      (.addInitScript ctx (stealth/stealth-init-script))
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://protected-site.com")))))
```

See `PROFILES_CDP.md` for full stealth patches.

## 3. `assert-url` fails with partial URLs

`spel/assert-url` wraps Playwright's `has-url` — exact string by default. Use a regex for substring/wildcard:

```clojure
(spel/assert-url "https://example.org/page")   ; exact
(spel/assert-url #".*example\.com.*")          ; substring
(spel/assert-url #".*/page.*")                 ; path prefix
```

## 4. Stale snapshot refs

Refs from `spel/capture-snapshot` are tied to the DOM at capture time. Any navigation or AJAX invalidates them — always re-snapshot:

```clojure
;; Wrong
(spel/capture-snapshot)
(spel/click "@e9mter")       ; navigates
(spel/click "@ea3kf5")       ; STALE — from old page

;; Right
(spel/capture-snapshot)
(spel/click "@e9mter")
(spel/capture-snapshot)      ; fresh
(spel/click "@ea3kf5")
```

## 5. `TimeoutError` on navigation

The CLI/daemon action timeout defaults to 10 s. First choose a precise readiness
signal; increase the timeout only for a known-slow operation.

```clojure
(spel/navigate "https://slow-site.com" {:wait-until :domcontentloaded})
(spel/navigate "https://slow-site.com" {:timeout 15000})
(spel/set-default-navigation-timeout! 15000)
```

Wait states from least → most strict: `:commit` < `:domcontentloaded` < `:load` (default) < `:networkidle`.

## 6. PDF empty / fails

PDF only works in **Chromium headless**. Firefox, WebKit, and headed Chromium don't support it.

```clojure
(spel/start! {:browser :chromium :headless true})
(spel/navigate "https://example.org")
(spel/pdf {:path "/tmp/output.pdf"})
```

If started headed, restart: `(spel/stop!)` then `(spel/start! {:headless true})`.

## 7. Snapshot fns in eval

Same names as library, implicit page:

```clojure
(spel/capture-snapshot)
(spel/capture-full-snapshot)

;; Library-style (explicit page)
(snapshot/capture-snapshot      (spel/page))
(snapshot/capture-full-snapshot (spel/page))
```

When in doubt: `(spel/help "snapshot")`.

## 8. Element not interactable

`(spel/click "button.submit")` — "element is not visible" or "outside viewport". Usually behind a modal, below fold, hidden by CSS, or covered by another element (z-index).

```clojure
(spel/scroll-into-view "button.submit") (spel/click "button.submit")
(spel/wait-for-selector "button.submit" {:state "visible"}) (spel/click "button.submit")
(spel/capture-snapshot)                   ; look for overlays, modals, banners
```

## 8a. Click hangs on SPA / portal

Click itself is valid but the readiness signal is wrong:

```clojure
;; Prefer route-aware waits after clicks
(spel/click "@eXXXX")
(spel/wait-for-url #".*target-route.*")
(spel/wait-for-load-state :domcontentloaded)

;; WRONG — never skip the click by navigating directly:
;; (spel/navigate "https://www.frisco.pl/login")
;; Always click the link/button like a human.
```

Rules: heavy portals → `:domcontentloaded` or `wait-for-url` after interactions. SPAs → `wait-for-url` to detect route changes, never direct navigation. Raising the timeout helps only after you've picked the right wait strategy.

## 9. File I/O in eval mode

`require` doesn't work in SCI. `clojure.java.io` is already available as `io`:

```clojure
(slurp "/tmp/data.txt")
(spit  "/tmp/output.txt" "hello")

(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file  "/tmp/deep/nested/file.txt") "content")
```

## 10. Cookie consent / GDPR popups

Modal blocks interaction; dismiss it first:

```clojure
(spel/navigate "https://some-eu-site.com")
(spel/click "button:has-text('Accept')")
;; or
(spel/click "button:has-text('Accept all')")
;; or via snapshot
(spel/capture-snapshot)
(spel/click "@e0k8qp")
```

For repeat visits, use a persistent browser session so the consent sticks.

## 11. Stale browser / "Target closed"

Browser crashed, killed externally, or OOM. The CLI daemon **recovers by
itself**: the failed command relaunches the browser, re-opens the page that was
open, and runs once more. `spel health` reports `degraded` until then.

```bash
spel --session <name> health   # browser: GONE — relaunches on the next command
spel --session <name> get url  # just re-run: it relaunches and answers
```

In the library (`spel/start!` API) there is no daemon to do that for you:

```clojure
(spel/stop!) (spel/start!)
```

If even `spel health` cannot get an answer:

```bash
spel --session <name> kill
```

## Debug workflow

### Page state

```clojure
(spel/info)
;; => {:url "…" :title "…" :viewport {:width 1280 :height 720} :closed? false}
```

`:closed? true` → browser died; `(spel/stop!)` then `(spel/start!)`.

### Snapshot

```clojure
(spel/capture-snapshot)
```

Shows the a11y tree with numbered refs — see what's actually there.

### Verify fn signatures

```clojure
(spel/help   "navigate")
(spel/source "navigate")
(spel/help   "snapshot")
```

### Annotated screenshot

```clojure
(let [snap (spel/capture-snapshot)]
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/debug.png"))
```

### Console errors

```clojure
;; Register early, before navigation
(spel/on-console    (fn [msg] (println "[console]"    msg)))
(spel/on-page-error (fn [err] (println "[page-error]" err)))
```

Auto-captured in `eval-sci` — check stderr.

### Network

```bash
spel network requests --status 4
spel network requests --status 5
spel network requests --type fetch
```

## 12. Daemon hangs / unresponsive browser

A daemon busy inside a 60-second browser call looks exactly like a dead one from
the outside. Ask it — `health` answers from daemon-local state and touches no
Playwright object, so it replies even while every browser call is stuck:

```bash
spel --session mysession health
```

```
mysession: busy — up 4 min, 37 commands
  browser:   chromium headless, connected, page open
  in flight: c12 evaluate (48s)
  socket:    /tmp/spel-mysession.sock
  log:       /tmp/spel-mysession.log
```

| Status | Meaning | Do |
|---|---|---|
| `ok` | idle, healthy | nothing |
| `busy` | commands running — `in flight` names them | wait, or cancel |
| `degraded` | browser connection or daemon state files are damaged | next command repairs browser; kill/restart repairs files |
| `stale` | PID file names an unrelated process | `spel kill` removes files but refuses to signal it |
| `orphaned` | verified daemon exists without usable state/socket | `spel kill` |
| `unresponsive` | verified daemon process alive, socket silent | `spel kill` |
| `down` | no verified daemon; `last exit` says why | just run your command |

Exit code: 0 for `ok`/`busy`, 1 otherwise. `--json` for the full payload.

```bash
spel --session mysession cancel c12   # interrupt one command
spel --session mysession cancel       # interrupt everything in flight
spel --session mysession kill         # end the daemon now
spel kill --all-sessions              # every session, plus file-less orphans
spel --session mysession logs -n 50   # ONE log: CLI + daemon lines interleaved
```

A call already parked inside the browser ends when the browser answers, so
re-check with `health`; when it never does, `kill`. Killing loses the browser
(page, cookies, refs) — cancel first, kill second.

### The command ledger

`in flight` comes from the daemon's **command ledger**: one entry per command,
with its id (`c12`), action, phase, and age. It is plain daemon-local state, so
`health` and `cancel` work while every Playwright call is blocked.

- Ids in `health` are exactly the ids `cancel <id>` accepts.
- A command that outlives its watchdog budget (`SPEL_COMMAND_BUDGET_MS`,
  default 25s; 900s for `eval-js`/`eval-sci`) is abandoned, its stack frames go
  to the log, and its ledger entry is dropped — the daemon keeps serving.
- An abandoned command answers with `command <id> (<action>) was cancelled`;
  that is a wedged action, not a dead daemon. Fix the action, do not `kill`.
- `health` and `cancel` are observers: they get ledger entries too, but are
  never listed as in-flight work, so an idle daemon never reports itself busy.

### Profile locked

```bash
ls -la /path/to/profile/SingletonLock    2>/dev/null
ls -la /path/to/profile/SingletonCookie  2>/dev/null

# Only if no other Chrome/Edge uses this profile
rm -f /path/to/profile/SingletonLock /path/to/profile/SingletonCookie

# Or fresh temp profile
spel --profile /tmp/fresh-profile open https://example.com
```

### Prevention

- Always close sessions when done.
- Use named sessions (`spel --session run-$(date +%s) …`).
- Never share profiles between concurrent processes — Chromium locks the dir.
- `spel --session <name> health` before blaming Spel: it says busy vs wedged vs
  down and never starts a daemon merely to answer.
- `spel --session <name> kill` instead of `pkill` + `rm`: it also cleans that
  session's socket and PID files.
- `spel --session <name> logs -f` in a second terminal while a run misbehaves:
  daemon start, every command with its duration, and every error land there
  (`spel --session <name> logs --path` locates the file;
  `SPEL_LOG_LEVEL=debug` adds detail).

## 13. Another session is driving the same tab

Two spel sessions CAN attach to one CDP browser — each opens its own tab and both keep
working, `network route` included: spel installs this session's routes on the tabs THIS session
drives — every one of them, tabs opened later included — so they never touch a tab another session
drives. The interception lock names every tab this session routes, not just the one in front, so the
other session is warned off all of them and gets them all back on `network unroute`. Sharing an
endpoint is normal.

Sessions only collide on the SAME tab — `spel tab <n>` can switch onto one another session
already drives. Then page-driving commands queue behind that session's routes:

```bash
spel --session b open https://example.com
# {"error":"Session 'a' is intercepting network requests in the tab session 'b' drives…",
#  "error_code":"cdp_route_lock","owner_session":"a","tab":"9F2C…"}
```

Read `owner_session`, then take your own tab — or free interception in this one:

```bash
spel --session b tab new                # own tab: the endpoint is shared, the tab is not
spel --session a network unroute all    # or release that session's routes
spel --session a close                  # or end that session entirely
```

Any of the three frees it instantly — the next command goes straight through, no wait. A
session that died without cleaning up frees it too: the lock names its owner, so the first
command that finds that daemon gone deletes the lock and proceeds.

`SPEL_CDP_LOCK_WAIT=0` fails immediately instead of queuing. The wait never exceeds the command
budget: the answer always names the owner instead of expiring as a generic `command_timeout`.

## 14. Console, errors and requests are per TAB

Capture follows the tab, not the session: `spel console`, `spel errors` and `spel network
requests` answer with the tab this session is on right now. After `spel tab new` or `spel tab
<n>` the listing starts from that tab — nothing was lost, it belongs to the tab you left.

```bash
spel console          # this tab
spel console --all    # every tab this session opened, each entry tagged with its tab
spel console clear    # clears this tab only (--all clears every tab)
```

`errors`, `network requests` and `network clear` take `--all` the same way. Each tab also keeps
its own slice of the capture window, so a chatty tab can no longer evict the one under test.

A tab id (`t3`) is handed out once and never reused, so it names that one tab for as long as it
lives. A tab NUMBER is only a position in the browser's tab strip and shifts the moment anyone
closes a tab before it — including the person at the keyboard. `spel tab list` prints both, and
`spel tab t3` can never land on the wrong tab:

```bash
spel tab list      # * [2] t3   Checkout — https://shop.example.com/cart
spel tab t3        # that same tab, whatever its position is now
```

Closing a tab outside spel is safe for the tabs around it: every entry keeps the id of the tab that
produced it, the closed tab drops out of `spel tab list`, and what it captured stays readable under
`--all` until the window rolls over.

Kill the tab spel is ON and the session survives it — but the command that was sent to that tab is
refused once, with `error_code` `tab_closed`, naming the tab that died and the tab spel moved to.
spel lands on a tab this session already drives (the highest id still open) and opens a fresh one
only when it drives none, so killing a tab never leaves a blank tab behind in a browser you own.
Re-run the command — `spel open <url>` when it had to open a fresh tab — and the session carries on;
`spel console --all` still holds what the dead tab captured, under its own id.

```bash
spel get title  # error tab_closed: "…was driving (t2) is gone … spel is now driving t1 (…)"
spel tab list   # t1 is still there and no blank tab was added
spel get title  # answers t1's title — one refusal, not a broken session
```

Which command gets refused is Playwright's timing, not spel's: `spel get url` is answered from the
driver's own last known state, so right after the kill it can still print the dead tab's URL. The first
command that really touches the page — `get title`, a click, an eval, a snapshot — is the one refused,
and everything after it runs on the tab spel landed on.
A tab the PAGE opens — `target="_blank"`, `window.open` — gets its own id and is captured from the moment
Playwright hands it over, without switching to it. Playwright cannot hand a popup over before its initial
navigation, so a message logged in that very first instant can still be missed; everything after it —
console, page errors, requests — lands under that tab's id.

A request that never gets a response is captured like any other: a refused connection, a DNS failure, a
TLS error or a route that aborted it is listed with `status` 0, the browser's own text in `error`
(`net::ERR_CONNECTION_REFUSED`) and how long it waited in `duration_ms`. Before that, `spel network` was
empty for exactly the request being debugged while devtools showed the error.

A ref lives exactly as long as the listing that shows it: `spel console get @c17` and `spel network get
@n42` answer for every ref `spel console` / `spel network requests` still prints, and stop answering for
the ones a clear removed. A session that has captured a million entries stops recording to keep the
browser responsive — it says so in the log, and `spel console clear` or `spel network clear` resumes it.

## 18. `ClassCastException` in `with-retry`

`with-retry` crashed with `ClassCastException: Keyword cannot be cast to Number` when the retried fn returned a map with non-numeric `:status` (e.g. `{:status :created}`).

**Fixed in v0.7.7.** Default `:retry-when` now guards with `(number? (:status result))` before casting. On older versions, pass explicit `:retry-when`:

```clojure
(spel/with-retry {:retry-when (fn [r] (and (map? r) (number? (:status r)) (>= (:status r) 500)))}
  (api-get ctx "/users"))
```

## 19. Retry doesn't catch exceptions

Before v0.7.7, `retry`/`with-retry` didn't catch exceptions. Now they do, and re-throw on the last attempt.

## 20. Polling until a condition

Use `retry-guard` to turn a predicate into a `:retry-when`:

```clojure
(spel/with-retry {:max-attempts 10 :delay-ms 1000 :backoff :fixed
                  :retry-when (spel/retry-guard #(= "ready" (:status %)))}
  (spel/api-get ctx "/job/123"))
```

## 21. iOS automation looks slow or disagrees with the screen

An Appium/XCUITest command's wall time includes transport, WebDriverAgent, and
XCTest quiescence. It is not the application's paint or animation duration.
For performance claims, capture native before/after evidence and poll the first
observable matching frame; pair it with WebView metrics for hybrid apps.

If `(spel/ios-hide-keyboard!)` takes seconds and then fails on a WKWebView, that
is commonly an unsupported WebDriverAgent keyboard endpoint. Dismiss it through
the same visible action a user performs and verify the native keyboard is gone.
Do not charge the WDA timeout to the application.

When native snapshot parsing itself fails, first compare `spel version` with
the generated skill version. Native binaries before 0.9.17 can hit the fixed
XCTest XML/SAX arity bug. Upgrade and regenerate the skill before debugging the
application. If a Spel defect remains, reduce it against Settings, fix Spel,
and rerun through Spel; raw Appium is only a diagnostic fallback.
