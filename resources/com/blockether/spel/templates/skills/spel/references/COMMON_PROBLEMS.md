# Common problems and troubleshooting

## 1. "Session already running"

A live library session refuses a second `spel/start!` — stop it first:

```clojure
(spel/stop!)
(spel/start!)
```

A `start!` that FAILS leaves nothing behind: the Playwright instance it created is
closed before the error propagates.

A daemon that will not go away ends with spel's own kill — it force-closes, destroys
the process and removes the socket/PID files:

```bash
spel --session <name> health   # what is it actually doing?
spel --session <name> kill     # end it now, even mid-command
spel kill --all-sessions       # every spel daemon, orphans included
```

**Never** `pkill -f "Google Chrome"` as a recovery step — it kills the user's own
browser. `pkill` and `rm -f` on spel's socket/PID files are last resorts for when
`spel kill` itself is unavailable.

## 2. CAPTCHA / bot detection

Headless Chromium is detectable (missing GPU, UA patterns, `navigator.webdriver`).
Stealth is on by default in the CLI; for stubborn sites try headed + real cookies:

```bash
spel open https://protected-site.com                   # stealth (default)
spel --interactive open https://protected-site.com     # stealth + headed

# Stealth + real Chrome cookies (most authentic): drive that profile directly,
# or save its state once and load it into later sessions
spel --channel chrome --profile "$HOME/Library/Application Support/Google/Chrome/Default" open https://protected-site.com
spel state save auth.json
spel --load-state auth.json open https://protected-site.com

# Disable stealth if it causes problems
spel --no-stealth open https://protected-site.com
```

`PROFILES_CDP.md` has the library-side stealth patches (`stealth/stealth-args`,
`stealth/stealth-init-script`).

## 3. `assert-url` fails with partial URLs

A string is matched against the WHOLE URL, exactly; a regex is matched anywhere inside it,
so padding it with `.*` adds nothing. `**` globs are a different dialect — `wait --url` and
`network route` speak it, an assertion never did, and spel refuses one instead of
comparing it literally until the assertion times out:

```clojure
(spel/assert-url "https://example.org/page")  ; exact — the whole URL, nothing less
(spel/assert-url #"example\.org")             ; matches anywhere in the URL
(spel/assert-url #"/page$")                   ; anchor it yourself when it matters
(spel/assert-url "**/page")                   ; refused — that is the `wait --url` dialect
```

Waiting for a URL is the glob form, and it waits instead of asserting:

```bash
spel wait --url "**/page"
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

The CLI/daemon action timeout defaults to 10 s (`--timeout <ms>` overrides it, for
`eval-sci` too). First choose a precise readiness signal; raise the timeout only for a
known-slow operation.

```clojure
(spel/navigate "https://slow-site.com" {:wait-until :domcontentloaded})
(spel/navigate "https://slow-site.com" {:timeout 15000})
(spel/set-default-navigation-timeout! 15000)
```

Wait states from least → most strict: `:commit` < `:domcontentloaded` < `:load` (default) < `:networkidle`.

## 6. PDF empty / fails

PDF is a **Chromium** feature — headless and headed both print one. Firefox and
WebKit have no PDF backend at all and refuse:

```bash
spel pdf out.pdf
# Error: PDF generation is only supported for Headless Chromium
# Hint:  PDF is Chromium-only — Firefox and WebKit have no PDF backend at all.
#        Re-run this session with --browser chromium; headed is fine.
```

```clojure
(spel/start! {:browser :chromium})
(spel/navigate "https://example.org")
(spel/pdf {:path "/tmp/output.pdf"})
```

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

## 8. Element not interactable, or the click hangs

"element is not visible" / "outside viewport": behind a modal, below the fold, hidden by
CSS, or covered by another element (z-index).

```clojure
(spel/scroll-into-view "button.submit") (spel/click "button.submit")
(spel/wait-for-selector "button.submit" {:state "visible"}) (spel/click "button.submit")
(spel/capture-snapshot)                   ; look for overlays, modals, banners
```

A click that HANGS is a wrong readiness signal, not a wrong selector. Wait for the route
after the click — and never replace the click with a navigation to where it would have led;
click the link or button like a human.

```clojure
(spel/click "@eXXXX")
(spel/wait-for-url #".*target-route.*")
(spel/wait-for-load-state :domcontentloaded)
```

Heavy portals → `:domcontentloaded` or `wait-for-url` after interactions. SPAs →
`wait-for-url` to detect route changes. Raising the timeout helps only once the wait
strategy is right.

## 9. File I/O in eval mode

`clojure.java.io` is already bound as `io`, and `require` works for the namespaces
the SCI environment carries (`clojure.string`, for instance). One it does not carry
is refused by name — `Could not find namespace cheshire.core` — never silently.

```clojure
(slurp "/tmp/data.txt")
(spit  "/tmp/output.txt" "hello")

(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file  "/tmp/deep/nested/file.txt") "content")
```

## 10. Cookie consent / GDPR popups

Modal blocks interaction; dismiss it first:

```clojure
(spel/click "button:has-text('Accept')")
;; or via snapshot
(spel/capture-snapshot)
(spel/click "@e0k8qp")
```

For repeat visits, keep the consent with `--profile <dir>` or a saved
`spel state save auth.json`.

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

If even `spel health` cannot get an answer: `spel --session <name> kill`.

## Debug workflow

```clojure
(spel/info)                           ; {:url … :title … :viewport … :closed? false}
                                      ; :closed? true → (spel/stop!) then (spel/start!)
(spel/capture-snapshot)               ; a11y tree with numbered refs
(spel/help   "snapshot")              ; every fn of a namespace
(spel/source "spel/navigate")         ; qualify it — a bare name that lives in two
                                      ; namespaces answers with both
(let [snap (spel/capture-snapshot)]   ; those refs drawn onto a screenshot
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/debug.png"))
```

Console and page errors are auto-captured in `eval-sci` (check stderr); register your own
early, before navigation, when a script needs them — `msg` is a Java `ConsoleMessage`, so ask
it for the parts:

```clojure
(spel/on-console    (fn [msg] (println "[console]" (.type msg) (.text msg))))
(spel/on-page-error (fn [err] (println "[page-error]" err)))
```

Events arrive only while the script sits INSIDE a browser call: `(Thread/sleep …)` dispatches
nothing, so wait with a real one (`spel/wait-for-selector`, `spel/evaluate`). What a handler
prints while its own command still runs comes back as that command's stdout; anything later
goes to the session log (`spel logs -f`).

```bash
spel network requests --status 4      # 4xx only (--status 5 for 5xx, --type fetch by kind)
```

## 12. Daemon hangs / unresponsive browser

A daemon busy inside a 60-second browser call looks exactly like a dead one from the
outside. Ask it — `health` answers from daemon-local state and touches no Playwright
object, so it replies even while every browser call is stuck:

```bash
spel --session mysession health
# mysession: busy — up 4 min, 37 commands
#   browser:   chromium headless, connected, page open
#   in flight: c12 evaluate (48s)
#   socket:    /tmp/spel-mysession.sock    log: /tmp/spel-mysession.log
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

`in flight` comes from the daemon's **command ledger**: one entry per command with its id
(`c12`), action, phase and age — daemon-local, so `health` and `cancel` answer while every
Playwright call is blocked, and both are observers, never listed as work. Ids in `health`
are exactly the ids `cancel <id>` accepts.

Past its watchdog budget (`SPEL_COMMAND_BUDGET_MS`, default 25 s; 900 s for
`eval-js`/`eval-sci`) a command is abandoned with `command <id> (<action>) was cancelled`,
its stack frames go to the log and the daemon keeps serving: a wedged action, not a dead
daemon. Fix the action, do not `kill`.

### Prevention

- Always close sessions when done, and name them (`spel --session run-$(date +%s) …`).
- Never share a profile between concurrent processes — Chromium locks the dir. A locked
  profile clears with `rm -f <profile>/SingletonLock <profile>/SingletonCookie` (only when
  no Chrome/Edge uses it), or use a fresh one: `spel --profile /tmp/fresh-profile open …`.
- `spel --session <name> health` before blaming spel: busy vs wedged vs down, and it never
  starts a daemon merely to answer.
- `spel --session <name> kill` instead of `pkill` + `rm`: it also cleans that session's
  socket and PID files.
- `spel --session <name> logs -f` in a second terminal while a run misbehaves
  (`logs --path` locates the file; `SPEL_LOG_LEVEL=debug` adds detail).

## 13. Another session is driving the same tab

Two spel sessions CAN attach to one CDP browser — each opens its own tab and both keep
working, `network route` included: a session's routes are installed only on the tabs THAT
session drives. Sharing an endpoint is normal.

Sessions collide only on the SAME tab — `spel tab <n>` can switch onto one another session
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

Any of the three frees it instantly. A session that died without cleaning up frees it too:
the first command that finds that daemon gone deletes the lock and proceeds.
`SPEL_CDP_LOCK_WAIT=0` fails immediately instead of queuing; the wait never exceeds the
command budget, and the answer names the owner instead of expiring as `command_timeout`.

## 14. Console, errors and requests are per TAB

Capture follows the tab, not the session: `spel console`, `spel errors` and
`spel network requests` answer for the tab this session is on right now. After
`spel tab new` or `spel tab <n>` the listing starts from that tab — nothing was lost,
it belongs to the tab you left.

```bash
spel console          # this tab
spel console --all    # every tab this session opened, each entry tagged with its tab
spel console clear    # clears this tab only (--all clears every tab)
```

`errors`, `network requests` and `network clear` take `--all` the same way; each tab keeps
its own slice of the capture window, so a chatty tab cannot evict the one under test.

A tab id (`t3`) is handed out once and never reused; a tab NUMBER is only a position in the
strip and shifts when anyone closes a tab before it. `spel tab list` prints both:

```bash
spel tab list      # * [2] t3   Checkout — https://shop.example.com/cart
spel tab t3        # that same tab, whatever its position is now
```

Kill the tab spel is ON and the session survives: the command sent to that tab is refused
ONCE with `error_code` `tab_closed`, naming the dead tab and the one spel moved to (a tab
this session already drives; a fresh one only when it drives none). Re-run it and the
session carries on; `spel console --all` still holds what the dead tab captured.

Other facts worth knowing when a listing surprises you:

- Every command first delivers what the browser told Playwright since the last one, so
  `get url`, `tab list`, `console`, `errors` and `network` answer for the browser as it is
  NOW. `spel health` is the exception — it must answer while the driver is wedged.
- A tab the PAGE opens (`target="_blank"`, `window.open`) gets its own id and is captured
  from the moment Playwright hands it over, without switching to it.
- A request that never gets a response is listed too: `status` 0, the browser's own text in
  `error` (`net::ERR_CONNECTION_REFUSED`), and its wait in `duration_ms`.
- A ref lives as long as the listing that shows it: `spel console get @c17` and
  `spel network get @n42` answer for what the current listing prints, not for what a clear
  removed. A session that captured a million entries stops recording (it says so in the
  log) until `spel console clear` / `spel network clear`.

## 15. Polling until a condition

`with-retry`, `retry-guard` and the API client live in `core`. `spel/` is the
implicit-page API and carries none of them, so `spel/with-retry` does not
resolve — spel answers with the namespace that does have the name.
`retry-guard` turns a predicate into a `:retry-when`, retrying while the
predicate is falsy (and on anomalies and 5xx, as the default does):

```clojure
(core/with-retry {:max-attempts 10 :delay-ms 1000 :backoff :fixed
                  :retry-when (core/retry-guard #(= 200 (:status %)))}
  (core/api-response->map
    (core/api-get (core/page-api (spel/page)) "https://api.example.com/job/123")))
```

## 16. iOS automation looks slow or disagrees with the screen

An Appium/XCUITest command's wall time includes transport, WebDriverAgent, and
XCTest quiescence. It is not the application's paint or animation duration.
For performance claims, capture native before/after evidence and poll the first
observable matching frame; pair it with WebView metrics for hybrid apps.

If `(spel/ios-hide-keyboard!)` takes seconds and then fails on a WKWebView, that
is commonly an unsupported WebDriverAgent keyboard endpoint. Dismiss it through
the same visible action a user performs and verify the native keyboard is gone.
Do not charge the WDA timeout to the application.

If a Spel defect remains, reduce it against Settings, fix Spel, and rerun through
Spel; raw Appium is only a diagnostic fallback.
