# Common Problems and Troubleshooting

Real-world issues you'll hit when using spel, with tested fixes.

## 1. "Session already running"

**Problem:** `(spel/start!)` throws an error saying a session is already active.

**Cause:** A previous `spel/start!` call wasn't cleaned up. The daemon is still holding the browser open.

**Fix:**

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

## 2. CAPTCHA / Bot Detection

**Problem:** Sites like Allegro.pl, Cloudflare-protected pages, or banking sites show CAPTCHA challenges or block access entirely.

**Cause:** Headless Chromium sends detectable signals (missing GPU, specific user-agent patterns, `navigator.webdriver` flag) that anti-bot systems pick up.

**Fix:** Use headed mode, a persistent profile, or a real user-agent:

```clojure
;; Option A: headed mode (browser window visible)
(spel/start! {:headless false})

;; Option B: persistent profile (keeps cookies/login between runs)
(spel/start! {:profile "/tmp/my-chrome-profile"})

;; Option C: real user-agent
(spel/start! {:user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"})
```

Combining all three gives the best results for stubborn sites.

## 3. `assert-url` Fails with Partial URLs

**Problem:** `(spel/assert-url "example.com/page")` fails even though the URL contains that string.

**Cause:** `spel/assert-url` does exact string matching only. No regex, no substring, no glob.

**Fix:**

```clojure
;; Exact match works
(spel/assert-url "https://example.com/page")

;; For partial/substring matching, check manually
(assert (clojure.string/includes? (spel/url) "example.com"))

;; For regex matching
(assert (re-find #"example\.com/page" (spel/url)))
```

## 4. Snapshot Ref Not Found / Stale Refs

**Problem:** `(spel/click "@e3")` throws "element not found" or clicks the wrong thing.

**Cause:** Refs from `(spel/snapshot)` are tied to the DOM at capture time. Navigation, AJAX updates, or any DOM mutation invalidates them.

**Fix:** Always re-snapshot after DOM changes:

```clojure
;; Wrong: refs from an old snapshot
(spel/snapshot)
(spel/click "@e2")       ;; navigates somewhere
(spel/click "@e5")       ;; STALE! refs are from the old page

;; Right: re-snapshot after any DOM change
(spel/snapshot)
(spel/click "@e2")
(spel/snapshot)           ;; fresh capture
(spel/click "@e5")        ;; works correctly
```

## 5. TimeoutError on Navigation

**Problem:** `(spel/goto "https://slow-site.com")` throws `TimeoutError` after 30 seconds.

**Cause:** Default timeout is 30s. Heavy pages with lots of resources or slow APIs can exceed this.

**Fix:**

```clojure
;; Increase the navigation timeout
(spel/goto "https://slow-site.com" {:timeout 60000})

;; Or use a less strict wait condition
(spel/goto "https://slow-site.com" {:wait-until "domcontentloaded"})

;; For all subsequent navigations
(spel/set-default-navigation-timeout! 60000)
```

Wait states from least to most strict: `"commit"` < `"domcontentloaded"` < `"load"` (default) < `"networkidle"`.

## 6. PDF Generation Fails or Produces Empty File

**Problem:** `(spel/pdf "output.pdf")` throws an error or creates a 0-byte file.

**Cause:** PDF only works in Chromium headless mode. Firefox, WebKit, and headed Chromium don't support it.

**Fix:**

```clojure
;; Ensure headless Chromium (the default)
(spel/start! {:browser :chromium :headless true})
(spel/goto "https://example.com")
(spel/pdf {:path "/tmp/output.pdf"})
```

If you started with `{:headless false}`, restart with `(spel/stop!)` then `(spel/start! {:headless true})`.

## 7. `snapshot/capture-snapshot` Not Found in Eval

**Problem:** `(snapshot/capture-snapshot (spel/page))` throws "Unable to resolve symbol".

**Cause:** SCI eval uses different namespace mappings than the library. The function exists under a different name.

**Fix:**

```clojure
;; Wrong (library-style call)
(snapshot/capture-snapshot (spel/page))

;; Right (eval-mode call)
(spel/snapshot)

;; Other snapshot functions
(snapshot/capture)       ;; snapshot/ namespace uses different names
(spel/full-snapshot)     ;; includes iframes
```

When in doubt: `(spel/help "snapshot")` lists all snapshot-related functions.

## 8. Elements Not Interactable

**Problem:** `(spel/click "button.submit")` throws "element is not visible" or "element is outside the viewport".

**Cause:** The element might be behind a modal/overlay, below the fold, hidden by CSS, or covered by another element (z-index).

**Fix:**

```clojure
;; Scroll into view first
(spel/scroll-into-view "button.submit")
(spel/click "button.submit")

;; Wait for visibility
(spel/wait-for "button.submit" {:state "visible"})
(spel/click "button.submit")

;; Check what's blocking it
(spel/snapshot)  ;; look for overlays, modals, banners in the tree
```

## 9. File I/O in Eval Mode

**Problem:** `(require '[clojure.java.io :as io])` throws an error.

**Cause:** `require` doesn't work in the SCI sandbox. All namespaces are pre-registered. `clojure.java.io` is already available as `io`.

**Fix:**

```clojure
;; Reading and writing files
(slurp "/tmp/data.txt")
(spit "/tmp/output.txt" "hello world")

;; Creating directories
(io/make-parents "/tmp/deep/nested/file.txt")
(spit (io/file "/tmp/deep/nested/file.txt") "content")

;; DON'T require anything. io is already available.
```

## 10. Cookie Consent / GDPR Popups

**Problem:** EU sites show a consent modal that blocks all interaction with the page.

**Cause:** GDPR compliance. The modal sits on top of everything with a high z-index.

**Fix:** Dismiss the consent dialog before doing anything else:

```clojure
(spel/goto "https://some-eu-site.com")

;; Try common consent button patterns
(spel/click "button:has-text('Accept')")
;; or
(spel/click "button:has-text('Accept all')")
;; or use snapshot to find the button
(spel/snapshot)
(spel/click "@e4")  ;; whatever ref the consent button has
```

For repeat visits, use a persistent profile so consent is remembered: `(spel/start! {:profile "/tmp/eu-browsing"})`.

## 11. Stale Browser / "Target closed"

**Problem:** Any command throws "Target closed" or "Browser has been closed".

**Cause:** The browser process crashed, was killed externally, or the system ran out of memory.

**Fix:**

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

## Debug Workflow

When something isn't working and you're not sure why, follow these steps.

### Step 1: Check page state

```clojure
(spel/info)
;; => {:url "https://..." :title "..." :viewport {:width 1280 :height 720} :closed? false}
```

If `:closed?` is `true`, the browser died. Run `(spel/stop!)` then `(spel/start!)`.

### Step 2: Take a snapshot

```clojure
(spel/snapshot)
```

Shows the accessibility tree with numbered refs. You'll see what elements exist, their roles, and whether the page rendered at all.

### Step 3: Verify function signatures

```clojure
(spel/help "goto")       ;; check args and description
(spel/source "goto")     ;; see the actual implementation
(spel/help "snapshot")   ;; find all snapshot-related functions
```

### Step 4: Take an annotated screenshot

```clojure
(let [snap (spel/snapshot)]
  (spel/save-annotated-screenshot! (:refs snap) "/tmp/debug.png"))
```

Produces a screenshot with numbered overlay badges on each interactive element. Compare with the snapshot tree.

### Step 5: Check for browser console errors

```clojure
;; Register listeners early, before navigation
(spel/on-console (fn [msg] (println "[console]" msg)))
(spel/on-page-error (fn [err] (println "[page-error]" err)))
```

Console messages are also auto-captured in `--eval` mode and printed to stderr after evaluation. Check stderr if your script produces unexpected results.

### Step 6: Inspect network activity

From the CLI:

```bash
spel network requests --status 4    # show 4xx errors
spel network requests --status 5    # show 5xx errors
spel network requests --type fetch  # show API calls
```
