# Common problems and troubleshooting

Real-world issues you'll hit when using spel, with tested fixes.

## 1. "Session already running"

`(spel/start!)` throws an error saying a session is already active. A previous `spel/start!` call wasn't cleaned up, so the daemon is still holding the browser open.

```clojure
(spel/stop!)
(spel/start!)
```

If that doesn't work, the daemon may be orphaned:

```bash
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
```

Then `(spel/start!)` again.

## 2. CAPTCHA / bot detection

Sites like Allegro.pl, Cloudflare-protected pages, or banking sites show CAPTCHA challenges or block access entirely. Headless Chromium sends detectable signals (missing GPU, specific user-agent patterns, `navigator.webdriver` flag) that anti-bot systems pick up.

Stealth mode is ON by default in the CLI, so this should work out of the box. For stubborn sites, try headed mode or combine with real Chrome cookies:

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

See `refs/PROFILES_AGENTS.md` for full details on what stealth patches are applied.

## 3. `assert-url` fails with partial URLs

`(spel/assert-url "example.org/page")` fails even though the URL contains that string. `spel/assert-url` wraps Playwright's `has-url`, which does exact string matching by default but also accepts `java.util.regex.Pattern` for flexible matching.

```clojure
;; Exact match (uses implicit page)
(spel/assert-url "https://example.org/page")

;; Regex pattern — substring, wildcard, etc.
(spel/assert-url #".*example\.com.*")

;; Regex for path prefix
(spel/assert-url #".*/page.*")
```

## 4. Snapshot ref not found / stale refs

`(spel/click "@e6t2x4")` throws "element not found" or clicks the wrong thing. Refs from `(spel/capture-snapshot)` are tied to the DOM at capture time. Navigation, AJAX updates, or any DOM mutation invalidates them.

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

`(spel/navigate "https://slow-site.com")` throws `TimeoutError` after 30 seconds. The default timeout is 30s, and heavy pages with lots of resources or slow APIs can exceed this.

```clojure
;; Increase the navigation timeout
(spel/navigate "https://slow-site.com" {:timeout 60000})

;; Or use a less strict wait condition
(spel/navigate "https://slow-site.com" {:wait-until :domcontentloaded})

;; For all subsequent navigations
(spel/set-default-navigation-timeout! 60000)
```

Wait states from least to most strict: `:commit` < `:domcontentloaded` < `:load` (default) < `:networkidle`.

## 6. PDF generation fails or produces empty file

`(spel/pdf "output.pdf")` throws an error or creates a 0-byte file. PDF only works in Chromium headless mode. Firefox, WebKit, and headed Chromium don't support it.

```clojure
;; Ensure headless Chromium (the default)
(spel/start! {:browser :chromium :headless true})
(spel/navigate "https://example.org")
(spel/pdf {:path "/tmp/output.pdf"})
```

If you started with `{:headless false}`, restart with `(spel/stop!)` then `(spel/start! {:headless true})`.

## 7. Snapshot functions in eval

Not sure which snapshot function to use in `eval-sci` mode? Use the same names as the library, but with implicit page:

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

`(spel/click "button.submit")` throws "element is not visible" or "element is outside the viewport". The element might be behind a modal/overlay, below the fold, hidden by CSS, or covered by another element (z-index).

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

## 9. File I/O in eval mode

`(require '[clojure.java.io :as io])` throws an error. `require` doesn't work in the SCI sandbox. All namespaces are pre-registered, and `clojure.java.io` is already available as `io`.

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

EU sites show a consent modal that blocks all interaction with the page. The modal sits on top of everything with a high z-index.

Dismiss the consent dialog before doing anything else:

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

For repeat visits, use a persistent browser session so consent is remembered.

## 11. Stale browser / "Target closed"

Any command throws "Target closed" or "Browser has been closed". The browser process crashed, was killed externally, or the system ran out of memory.

```clojure
(spel/stop!)
(spel/start!)
```

If `spel/stop!` itself fails:

```bash
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
rm -f /tmp/spel-*.sock /tmp/spel-*.pid
```

Then `(spel/start!)` again.

---

## Debug workflow

When something isn't working and you're not sure why, follow these steps.

### Check page state

```clojure
(spel/info)
;; => {:url "https://..." :title "..." :viewport {:width 1280 :height 720} :closed? false}
```

If `:closed?` is `true`, the browser died. Run `(spel/stop!)` then `(spel/start!)`.

### Take a snapshot

```clojure
(spel/capture-snapshot)
```

Shows the accessibility tree with numbered refs. You'll see what elements exist, their roles, and whether the page rendered at all.

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

Produces a screenshot with numbered overlay badges on each interactive element. Compare with the snapshot tree.

### Check for browser console errors

```clojure
;; Register listeners early, before navigation
(spel/on-console (fn [msg] (println "[console]" msg)))
(spel/on-page-error (fn [err] (println "[page-error]" err)))
```

Console messages are also auto-captured in `eval-sci` mode and printed to stderr after evaluation. Check stderr if your script produces unexpected results.

### Inspect network activity

From the CLI:

```bash
spel network requests --status 4    # show 4xx errors
spel network requests --status 5    # show 5xx errors
spel network requests --type fetch  # show API calls
```

---

## 12. Daemon hangs / unresponsive browser

spel command appears to hang, doesn't return, or the browser seems frozen.

Common causes:
- Stale daemon from a previous session still running
- Browser crashed but daemon process didn't exit
- Persistent context lock (another process holds the profile directory)
- Edge/Chrome profile migration running in the background on first launch

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
spel close
# or with a named session:
spel --session mysession close

# Nuclear: kill ALL spel and browser processes
pkill -f "spel daemon"
pkill -f "chrome-headless-shell"
pkill -f "chromium"
pkill -f "msedge"

# Verify nothing is left
ps aux | grep -E "spel|chrome|msedge" | grep -v grep

# Start fresh
spel open https://example.com
```

### Profile directory locked

If using `--profile` and getting hangs, the profile dir may be locked by another process:

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
- Use named sessions in automation: `spel --session agent-$(date +%s) open <url>` to avoid collision with default session
- Don't share profiles between concurrent processes, since Chromium locks the directory
- Check `spel session list` before starting if you suspect a stale daemon
