# Common problems and troubleshooting

Real-world issues, tested fixes.

## 1. "Session already running"

`(spel/start!)` throws — previous `spel/start!` wasn't cleaned up, daemon still holding browser open.

```clojure
(spel/stop!)
(spel/start!)
```

If that fails, daemon may be orphaned:

```bash
# Target only the affected session first
spel --session <session-name> close

# If daemon is stale, clean up only spel-owned daemon/socket artifacts
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
```

Do not kill all user Chrome/Edge processes as default recovery step.

Then `(spel/start!)` again.

## 2. CAPTCHA / bot detection

Sites (Allegro.pl, Cloudflare-protected, banking) show CAPTCHAs or block access. Headless Chromium sends detectable signals (missing GPU, specific UA patterns, `navigator.webdriver` flag) → anti-bot systems catch them.

Stealth mode ON by default in CLI. For stubborn sites, try headed mode or combine with real Chrome cookies:

```bash
# Default: stealth is already on
spel open https://protected-site.com

# Option A: stealth + headed (best results for stubborn sites)
spel --interactive open https://protected-site.com

# Option B: stealth + real Chrome cookies (maximum authenticity)
spel state export --profile ~/Library/Application\ Support/Google/Chrome/Default -o auth.json
spel --load-state auth.json open https://protected-site.com

# Option C: disable stealth if it causes issues
spel --no-stealth open https://protected-site.com
```

```clojure
;; Library: stealth + headed
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

See `references/PROFILES_AGENTS.md` for full stealth patch details.

## 3. `assert-url` fails with partial URLs

`(spel/assert-url "example.org/page")` fails even though URL contains that string. `spel/assert-url` wraps Playwright's `has-url` — exact string matching by default, also accepts `java.util.regex.Pattern`.

```clojure
;; Exact match (uses implicit page)
(spel/assert-url "https://example.org/page")

;; Regex pattern — substring, wildcard, etc.
(spel/assert-url #".*example\.com.*")

;; Regex for path prefix
(spel/assert-url #".*/page.*")
```

## 4. Snapshot ref not found / stale refs

`(spel/click "@e6t2x4")` throws "element not found" or clicks wrong thing. Refs from `(spel/capture-snapshot)` tied to DOM at capture time. Navigation, AJAX, any DOM mutation invalidates them.

Always re-snapshot after DOM changes:

```clojure
;; Wrong: refs from an old snapshot
(spel/capture-snapshot)
(spel/click "@e9mter")       ;; navigates somewhere
(spel/click "@ea3kf5")       ;; STALE! refs are from the old page

;; Right: re-snapshot after any DOM change
(spel/capture-snapshot)
(spel/click "@e9mter")
(spel/capture-snapshot)           ;; fresh capture
(spel/click "@ea3kf5")        ;; works correctly
```

## 5. TimeoutError on navigation

`(spel/navigate "https://slow-site.com")` throws `TimeoutError` after 30s. Default timeout 30s — heavy pages with lots of resources or slow APIs can exceed this.

```clojure
;; Increase navigation timeout
(spel/navigate "https://slow-site.com" {:timeout 60000})

;; Or use less strict wait condition
(spel/navigate "https://slow-site.com" {:wait-until :domcontentloaded})

;; For all subsequent navigations
(spel/set-default-navigation-timeout! 60000)
```

Wait states least → most strict: `:commit` < `:domcontentloaded` < `:load` (default) < `:networkidle`.

## 6. PDF generation fails or produces empty file

`(spel/pdf "output.pdf")` throws or creates 0-byte file. PDF only works in Chromium headless mode. Firefox, WebKit, headed Chromium don't support it.

```clojure
;; Ensure headless Chromium (the default)
(spel/start! {:browser :chromium :headless true})
(spel/navigate "https://example.org")
(spel/pdf {:path "/tmp/output.pdf"})
```

If started with `{:headless false}`, restart: `(spel/stop!)` then `(spel/start! {:headless true})`.

## 7. Snapshot functions in eval

Unsure which snapshot fn to use in `eval-sci` mode? Same names as library, with implicit page:

```clojure
;; Eval-mode (implicit page)
(spel/capture-snapshot)
(spel/capture-full-snapshot)

;; Library-style (explicit page)
(snapshot/capture-snapshot (spel/page))
(snapshot/capture-full-snapshot (spel/page))
```

When in doubt: `(spel/help "snapshot")` lists all snapshot-related functions.

## 8. Elements not interactable

`(spel/click "button.submit")` throws "element is not visible" or "element is outside the viewport". Element may be behind modal/overlay, below fold, hidden by CSS, or covered by another element (z-index).

```clojure
;; Scroll into view first
(spel/scroll-into-view "button.submit")
(spel/click "button.submit")

;; Wait for visibility
(spel/wait-for-selector "button.submit" {:state "visible"})
(spel/click "button.submit")

;; Check what's blocking it
(spel/capture-snapshot)  ;; look for overlays, modals, banners in the tree
```

## 8a. Click works poorly on SPA or portal navigation

`spel click @eXXXX` hangs or times out on sites using client-side routing or loading third-party resources forever. Click may be valid, but wrong readiness signal makes it look broken.

Try in order:

```clojure
;; Prefer route-aware waiting after clicks
(spel/click "@eXXXX")
(spel/wait-for-url #".*target-route.*")
(spel/wait-for-load-state :domcontentloaded)

;; WRONG — NEVER skip user actions by navigating directly:
;; (spel/navigate "https://www.frisco.pl/login")
;; Always click the link/button like a human would.
```

Rules of thumb:
- Heavy portals: prefer `:domcontentloaded` or `wait-for-url` after interactions.
- SPAs: use `wait-for-url` after clicks to detect route changes — NEVER skip the click with direct navigation.
- Raising timeout helps only after choosing right wait strategy.
## 9. File I/O in eval mode

`(require '[clojure.java.io :as io])` throws. `require` doesn't work in SCI sandbox. All namespaces pre-registered, `clojure.java.io` already available as `io`.

```clojure
;; Reading and writing files
(slurp "/tmp/data.txt")
(spit "/tmp/output.txt" "hello world")

;; Creating directories
(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file "/tmp/deep/nested/file.txt") "content")

;; DON'T require anything. io is already available.
```

## 10. Cookie consent / GDPR popups

EU sites show consent modal blocking all interaction. Modal sits on top of everything with high z-index.

Dismiss consent dialog before anything else:

```clojure
(spel/navigate "https://some-eu-site.com")

;; Try common consent button patterns
(spel/click "button:has-text('Accept')")
;; or
(spel/click "button:has-text('Accept all')")
;; or use snapshot to find the button
(spel/capture-snapshot)
(spel/click "@e0k8qp")  ;; whatever ref the consent button has
```

For repeat visits, use persistent browser session so consent remembered.

## 11. Stale browser / "Target closed"

Any command throws "Target closed" or "Browser has been closed". Browser process crashed, killed externally, or system ran out of memory.

```clojure
(spel/stop!)
(spel/start!)
```

If `spel/stop!` itself fails:

```bash
spel --session <session-name> close
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
```

Then `(spel/start!)` again.

---

## Debug workflow

When something isn't working, follow these steps.

### Check page state

```clojure
(spel/info)
;; => {:url "https://..." :title "..." :viewport {:width 1280 :height 720} :closed? false}
```

If `:closed?` is `true`, browser died. Run `(spel/stop!)` then `(spel/start!)`.

### Take a snapshot

```clojure
(spel/capture-snapshot)
```

Shows accessibility tree with numbered refs. See what elements exist, their roles, whether page rendered at all.

### Verify function signatures

```clojure
(spel/help "navigate")     ;; check args and description
(spel/source "navigate")   ;; see the actual implementation
(spel/help "snapshot")   ;; find all snapshot-related functions
```

### Take an annotated screenshot

```clojure
(let [snap (spel/capture-snapshot)]
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/debug.png"))
```

Produces screenshot with numbered overlay badges on each interactive element. Compare with snapshot tree.

### Check for browser console errors

```clojure
;; Register listeners early, before navigation
(spel/on-console (fn [msg] (println "[console]" msg)))
(spel/on-page-error (fn [err] (println "[page-error]" err)))
```

Console messages auto-captured in `eval-sci` mode and printed to stderr after evaluation. Check stderr if script produces unexpected results.

### Inspect network activity

From CLI:

```bash
spel network requests --status 4    # show 4xx errors
spel network requests --status 5    # show 5xx errors
spel network requests --type fetch  # show API calls
```

---

## 12. Daemon hangs / unresponsive browser

spel command hangs, doesn't return, browser seems frozen.

Common causes:
- Stale daemon from previous session still running
- Browser crashed but daemon process didn't exit
- Persistent context lock (another process holds profile directory)
- Edge/Chrome profile migration running in background on first launch

### Diagnose

```bash
# Check if daemon is running
spel session list

# Check daemon log for errors
tail -50 /tmp/spel-default.log

# Check for zombie browser processes
ps aux | grep -E "chrome|chromium|msedge|spel" | grep -v grep
```

### Kill and restart

```bash
# Graceful: close your session
spel --session mysession close

# If stale, kill only spel daemon + spel-owned headless shell and stale sockets
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid

# Verify only spel daemons are gone (do not kill unrelated user Chrome/Edge)
ps aux | grep -E "spel daemon|chrome-headless-shell" | grep -v grep

# Start fresh with explicit session
spel --session mysession open https://example.com
```

### Profile directory locked

If using `--profile` and getting hangs, profile dir may be locked by another process:

```bash
# Check for lock files
ls -la /path/to/profile/SingletonLock 2>/dev/null
ls -la /path/to/profile/SingletonCookie 2>/dev/null

# Remove locks (only if no other Chrome/Edge instance uses this profile)
rm -f /path/to/profile/SingletonLock /path/to/profile/SingletonCookie

# Or use a fresh temp profile instead
spel --profile /tmp/fresh-profile open https://example.com
```

### Prevention

- Always close sessions when done: `spel close` or `spel --session <name> close`
- Use named sessions in automation: `spel --session agent-$(date +%s) open <url>` → avoids collision with default session
- Don't share profiles between concurrent processes — Chromium locks the directory
- Check `spel session list` before starting if stale daemon suspected

## 18. ClassCastException in `with-retry` / `retry`

`with-retry` crashes with `ClassCastException: class clojure.lang.Keyword cannot be cast to class java.lang.Number` when retried function returns map with non-numeric `:status` key (e.g. `{:status :created}`).

**Fixed in v0.7.7.** Default `:retry-when` now guards with `(number? (:status result))` before casting. On older version, provide explicit `:retry-when`:

```clojure
(spel/with-retry {:retry-when (fn [r] (and (map? r) (number? (:status r)) (>= (:status r) 500)))}
  (api-get ctx "/users"))
```

## 19. Retry doesn't catch exceptions

Prior to v0.7.7, `retry` / `with-retry` did not catch exceptions thrown by retried function — bubbled up immediately. Now exceptions caught and retried automatically, re-thrown only on last attempt.

## 20. Polling until a condition is met

Use `retry-guard` to create `:retry-when` that retries until your predicate passes:

```clojure
;; Retry until the job is ready (up to 10 attempts, 1s apart)
(spel/with-retry {:max-attempts 10 :delay-ms 1000 :backoff :fixed
                  :retry-when (spel/retry-guard #(= "ready" (:status %)))}
  (spel/api-get ctx "/job/123"))
```
