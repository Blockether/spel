# Common problems and troubleshooting

## 1. "Session already running"

Previous `spel/start!` wasn't cleaned up:

```clojure
(spel/stop!)
(spel/start!)
```

If that fails, the daemon may be orphaned:

```bash
spel --session <name> close                   # target your session first
pkill -f "spel daemon"                        # kill stale daemon
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
```

**Never** `pkill -f "Google Chrome"` as a default recovery step — it kills the user's browser.

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

See `PROFILES_AGENTS.md` for full stealth patches.

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

Default timeout is 30 s. Heavy pages can exceed this.

```clojure
(spel/navigate "https://slow-site.com" {:timeout 60000})
(spel/navigate "https://slow-site.com" {:wait-until :domcontentloaded})
(spel/set-default-navigation-timeout! 60000)
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

Browser crashed, killed externally, or OOM:

```clojure
(spel/stop!) (spel/start!)
```

If `stop!` itself fails:

```bash
spel --session <name> close
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
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

Common causes: stale daemon, zombie browser, profile-dir lock, first-launch profile migration.

```bash
spel session list
tail -50 /tmp/spel-default.log
ps aux | grep -E "chrome|chromium|msedge|spel" | grep -v grep
```

```bash
spel --session mysession close

pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid

ps aux | grep -E "spel daemon|chrome-headless-shell" | grep -v grep   # verify
spel --session mysession open https://example.com
```

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
- Use named sessions (`spel --session agent-$(date +%s) …`).
- Never share profiles between concurrent processes — Chromium locks the dir.
- `spel session list` before starting if a stale daemon is suspected.

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
