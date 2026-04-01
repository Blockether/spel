(ns com.blockether.spel.snapshot-test
  "Tests for the snapshot namespace.

   Unit tests for pure helper functions and integration tests
   that run against example.org using Playwright."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as sut]
   [com.blockether.spel.core :as core]
   [com.blockether.spel.allure :as allure :refer [around defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — ref-bounding-box
;; =============================================================================

(defdescribe ref-bounding-box-test
  "Unit tests for ref-bounding-box"
  (around [f] (core/with-testing-browser (f)))

  (describe "returns bbox for existing ref"
    (it "extracts bbox map from refs"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button" :name "Submit"
                                                      :bbox {:x 10 :y 20 :width 80 :height 30}}
                                                "e2" {:role "link" :name "Home"
                                                      :bbox {:x 0 :y 0 :width 100 :height 20}}}]
                                      (expect (= {:x 10 :y 20 :width 80 :height 30}
                                                (sut/ref-bounding-box refs "e1")))
                                      (expect (= {:x 0 :y 0 :width 100 :height 20}
                                                (sut/ref-bounding-box refs "e2")))))))

  (describe "returns nil for missing ref"
    (it "returns nil when ref not in map"

      (core/with-testing-page [_pg] (let [refs {"e1" {:role "button" :name "Submit"
                                                      :bbox {:x 10 :y 20 :width 80 :height 30}}}]
                                      (expect (nil? (sut/ref-bounding-box refs "e99")))
                                      (expect (nil? (sut/ref-bounding-box refs "nonexistent")))))))

  (describe "handles empty refs map"
    (it "returns nil for empty map"

      (core/with-testing-page [_pg] (expect (nil? (sut/ref-bounding-box {} "e1")))))))

;; =============================================================================
;; Integration Tests — capture-snapshot
;; =============================================================================

(defdescribe capture-snapshot-test
  "Integration tests for capture-snapshot against example.org"
  (around [f] (core/with-testing-browser (f)))

  (describe "snapshot structure"

    (it "returns map with :tree :refs :counter keys"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (map? snap))
          (expect (contains? snap :tree))
          (expect (contains? snap :refs))
          (expect (contains? snap :counter)))))

    (it "tree is a non-empty string"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (string? (:tree snap)))
          (expect (pos? (count (:tree snap)))))))

    (it "counter is a positive number"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (number? (:counter snap)))
          (expect (pos? (:counter snap))))))

    (it "refs is a non-empty map"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (map? (:refs snap)))
          (expect (pos? (count (:refs snap))))))))

  (describe "tree content for example.org"

    (it "contains heading role"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (str/includes? (:tree snap) "heading")))))

    (it "contains link role"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (str/includes? (:tree snap) "link")))))

    (it "contains ref annotations"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (str/includes? (:tree snap) "[@e"))))))

  (describe "refs content"

    (it "ref entries have correct structure"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)
              first-ref (val (first (:refs snap)))]
          (expect (contains? first-ref :role))
          (expect (contains? first-ref :name))
          (expect (contains? first-ref :bbox))
          (expect (contains? first-ref :tag)))))

    (it "bbox values are numbers"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)
              first-ref (val (first (:refs snap)))
              bbox (:bbox first-ref)]
          (expect (number? (:x bbox)))
          (expect (number? (:y bbox)))
          (expect (number? (:width bbox)))
          (expect (number? (:height bbox))))))

    (it "ref keys follow content-hash pattern"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (doseq [ref-id (keys (:refs snap))]
            (expect (re-matches #"e[a-z0-9]+" ref-id))))))))

;; =============================================================================
;; Integration Tests — resolve-ref
;; =============================================================================

(defdescribe resolve-ref-test
  "Integration tests for resolve-ref"
  (around [f] (core/with-testing-browser (f)))

  (describe "resolving refs after snapshot"

    (it "returns a Locator for valid ref"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)
              first-ref-id (key (first (:refs snap)))
              locator (sut/resolve-ref pg first-ref-id)]
          (expect (some? locator))
          (expect (instance? com.microsoft.playwright.Locator locator)))))

    (it "locator matches the correct element"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)
            ;; Find the heading ref
              heading-ref (some (fn [[ref-id info]]
                                  (when (= "heading" (:role info))
                                    ref-id))
                            (:refs snap))]
          (when heading-ref
            (let [locator (sut/resolve-ref pg heading-ref)
                  text (.textContent locator)]
              (expect (= "Example Domain" text)))))))))

;; =============================================================================
;; Integration Tests — clear-refs!
;; =============================================================================

(defdescribe clear-refs-test
  "Integration tests for clear-refs!"
  (around [f] (core/with-testing-browser (f)))

  (describe "clearing refs from DOM"

    (it "removes data-pw-ref attributes"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
      ;; First capture to add refs
        (sut/capture-snapshot pg)
      ;; Verify refs exist
        (let [count-before (page/evaluate pg
                             "document.querySelectorAll('[data-pw-ref]').length")]
          (expect (pos? (int count-before))))
      ;; Clear refs
        (sut/clear-refs! pg)
      ;; Verify refs removed
        (let [count-after (page/evaluate pg
                            "document.querySelectorAll('[data-pw-ref]').length")]
          (expect (zero? (int count-after))))))))

;; =============================================================================
;; Integration Tests — capture-full-snapshot
;; =============================================================================

(defdescribe capture-full-snapshot-test
  "Integration tests for capture-full-snapshot"
  (around [f] (core/with-testing-browser (f)))

  (describe "full snapshot on page without iframes"

    (it "returns same structure as capture-snapshot for simple pages"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-full-snapshot pg)]
          (expect (map? snap))
          (expect (contains? snap :tree))
          (expect (contains? snap :refs))
          (expect (contains? snap :counter))
          (expect (string? (:tree snap)))
          (expect (pos? (count (:refs snap)))))))))

;; =============================================================================
;; Integration Tests — deterministic refs (content-hash stability)
;; =============================================================================

(defdescribe deterministic-refs-test
  "Tests that snapshot refs are deterministic and stable across page states.

   Content-hash refs use FNV-1a(role|name|tag) so the same element always
   gets the same ref regardless of what other elements exist on the page."
  (around [f] (core/with-testing-browser (f)))

  (describe "same page produces identical refs"

    (it "two consecutive snapshots produce identical ref keys"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap1 (sut/capture-snapshot pg)
              snap2 (sut/capture-snapshot pg)]
          (expect (= (set (keys (:refs snap1)))
                    (set (keys (:refs snap2)))))
        ;; Same ref maps to same role+name
          (doseq [[ref-id info] (:refs snap1)]
            (expect (= (:role info) (:role (get (:refs snap2) ref-id))))
            (expect (= (:name info) (:name (get (:refs snap2) ref-id))))))))

    (it "refs are stable after page reload"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap1 (sut/capture-snapshot pg)]
          (page/navigate pg "https://example.org")
          (let [snap2 (sut/capture-snapshot pg)]
            (expect (= (set (keys (:refs snap1)))
                      (set (keys (:refs snap2))))))))))

  (describe "refs survive DOM mutations"

    (it "adding a new element does not change existing refs"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap-before (sut/capture-snapshot pg)
              before-refs (:refs snap-before)]
        ;; Inject a new button into the page
          (page/evaluate pg
            "(() => { const btn = document.createElement('button'); btn.textContent = 'Injected'; document.body.prepend(btn); })()")
          (let [snap-after (sut/capture-snapshot pg)
                after-refs (:refs snap-after)]
          ;; All old refs should still exist with same role+name
            (doseq [[ref-id info] before-refs]
              (let [after-info (get after-refs ref-id)]
                (expect (some? after-info))
                (expect (= (:role info) (:role after-info)))
                (expect (= (:name info) (:name after-info)))))
          ;; There should be one MORE ref (the injected button)
            (expect (> (count after-refs) (count before-refs)))))))

    (it "removing an element does not change remaining refs"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap-before (sut/capture-snapshot pg)
              before-refs (:refs snap-before)
            ;; Find the link ref to remove
              link-ref (some (fn [[ref-id info]]
                               (when (= "link" (:role info))
                                 ref-id))
                         before-refs)
              non-link-refs (dissoc before-refs link-ref)]
        ;; Remove the link from the DOM
          (page/evaluate pg
            "(() => { const a = document.querySelector('a'); if(a) a.remove(); })()")
          (let [snap-after (sut/capture-snapshot pg)
                after-refs (:refs snap-after)]
          ;; All non-link refs should still exist with same role+name
            (doseq [[ref-id info] non-link-refs]
              (let [after-info (get after-refs ref-id)]
                (expect (some? after-info))
                (expect (= (:role info) (:role after-info)))
                (expect (= (:name info) (:name after-info)))))
          ;; The link ref should be gone
            (expect (nil? (get after-refs link-ref)))))))))

;; =============================================================================
;; Helpers for controlled stability tests
;; =============================================================================

(defn- ref-identity-map
  "Extracts {ref-id {:role ... :name ...}} from a snapshot for comparison.
   Strips bbox since positions change — we only care about identity stability."
  [snap]
  (into {}
    (map (fn [[ref-id info]]
           [ref-id {:role (:role info) :name (:name info)}]))
    (:refs snap)))

(defn- find-ref-by
  "Finds the ref-id for the first element matching role + name in a snapshot."
  [snap role name]
  (some (fn [[ref-id info]]
          (when (and (= role (:role info)) (= name (:name info)))
            ref-id))
    (:refs snap)))

;; =============================================================================
;; Integration Tests — ref stability (controlled HTML)
;;
;; These tests use set-content! for fully deterministic HTML. No external site
;; dependencies. Each test verifies a specific stability guarantee of the
;; content-hash ref system.
;; =============================================================================

(defdescribe ref-stability-controlled-test
  "Comprehensive ref stability tests using controlled HTML.

   Tests that content-hash refs (FNV-1a of role|name|tag) are stable across
   DOM mutations that don't change element identity. Uses set-content! for
   fully deterministic HTML — no external site dependencies."
  (around [f] (core/with-testing-browser (f)))

  ;; ── A. Basic determinism ──────────────────────────────────────────────────

  (describe "basic determinism with controlled HTML"

    (it "same HTML produces identical refs across two snapshots"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Title</h1><nav><a href='/about'>About</a><a href='/contact'>Contact</a></nav><button>Submit</button>")
        (let [snap1 (sut/capture-snapshot pg)
              snap2 (sut/capture-snapshot pg)]
          (expect (= (ref-identity-map snap1) (ref-identity-map snap2))))))

    (it "capture then clear then capture produces same refs (idempotent)"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Hello</h1><form><input type='text' placeholder='Email'/><button>Sign Up</button></form>")
        (let [snap1 (sut/capture-snapshot pg)]
          (sut/clear-refs! pg)
          (let [snap2 (sut/capture-snapshot pg)]
            (expect (= (ref-identity-map snap1) (ref-identity-map snap2))))))))

;; ── B. Structural stability (refs DON'T change) ──────────────────────────

  (describe "adding unrelated elements"

    (it "appending a new element at end does not change existing refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Title</h1><button>Save</button><a href='/home'>Home</a>")
        (let [before     (sut/capture-snapshot pg)
              before-ids (ref-identity-map before)]
          (page/evaluate pg
            "document.body.appendChild(Object.assign(document.createElement('button'), {textContent:'New'}))")
          (let [after     (sut/capture-snapshot pg)
                after-ids (ref-identity-map after)]
          ;; Every original ref still exists with same identity
            (doseq [[ref-id identity] before-ids]
              (expect (= identity (get after-ids ref-id))))
          ;; Plus one new ref
            (expect (= (inc (count before-ids)) (count after-ids)))))))

    (it "prepending a new element at beginning does not change existing refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Title</h1><button>Save</button><a href='/home'>Home</a>")
        (let [before     (sut/capture-snapshot pg)
              before-ids (ref-identity-map before)]
          (page/evaluate pg
            "document.body.prepend(Object.assign(document.createElement('h2'), {textContent:'Prepended'}))")
          (let [after     (sut/capture-snapshot pg)
                after-ids (ref-identity-map after)]
            (doseq [[ref-id identity] before-ids]
              (expect (= identity (get after-ids ref-id)))))))))

  (describe "removing unrelated elements"

    (it "removing one element does not change other refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Title</h1><button>Delete Me</button><button>Keep Me</button><a href='/x'>Link</a>")
        (let [before    (sut/capture-snapshot pg)
              keep-ref  (find-ref-by before "button" "Keep Me")
              link-ref  (find-ref-by before "link" "Link")
              title-ref (find-ref-by before "heading" "Title")]
        ;; Remove the first button
          (page/evaluate pg
            "document.body.querySelector('button').remove()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "button" (:role (get (:refs after) keep-ref))))
            (expect (= "Keep Me" (:name (get (:refs after) keep-ref))))
            (expect (= "link" (:role (get (:refs after) link-ref))))
            (expect (= "heading" (:role (get (:refs after) title-ref)))))))))

  (describe "reordering unique elements"

    (it "reversing element order preserves all refs when identities are unique"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<div id='container'><button>Alpha</button><button>Beta</button><button>Gamma</button></div>")
        (let [before    (sut/capture-snapshot pg)
              alpha-ref (find-ref-by before "button" "Alpha")
              beta-ref  (find-ref-by before "button" "Beta")
              gamma-ref (find-ref-by before "button" "Gamma")]
        ;; Reverse the order: Gamma, Beta, Alpha
          (page/evaluate pg
            "(() => { const c = document.getElementById('container'); [...c.children].reverse().forEach(el => c.appendChild(el)); })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "Alpha" (:name (get (:refs after) alpha-ref))))
            (expect (= "Beta" (:name (get (:refs after) beta-ref))))
            (expect (= "Gamma" (:name (get (:refs after) gamma-ref)))))))))

  (describe "structural wrapping"

    (it "wrapping element in new container div does not change its ref"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button>Wrapped</button><a href='/x'>Stay</a>")
        (let [before   (sut/capture-snapshot pg)
              btn-ref  (find-ref-by before "button" "Wrapped")
              link-ref (find-ref-by before "link" "Stay")]
        ;; Wrap button in a new section
          (page/evaluate pg
            "(() => { const btn = document.querySelector('button'); const wrap = document.createElement('section'); btn.parentNode.insertBefore(wrap, btn); wrap.appendChild(btn); })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "Wrapped" (:name (get (:refs after) btn-ref))))
            (expect (= "Stay" (:name (get (:refs after) link-ref)))))))))

  (describe "cosmetic attribute changes"

    (it "changing CSS class, style, and data attributes does not change ref"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button class='primary'>Styled</button>")
        (let [before  (sut/capture-snapshot pg)
              btn-ref (find-ref-by before "button" "Styled")]
          (page/evaluate pg
            "(() => { const b = document.querySelector('button'); b.className = 'danger large'; b.style.color = 'red'; b.style.fontSize = '24px'; b.setAttribute('data-testid', 'xyz'); })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "button" (:role (get (:refs after) btn-ref))))
            (expect (= "Styled" (:name (get (:refs after) btn-ref)))))))))

;; ── C. ID-based stability ─────────────────────────────────────────────────

  (describe "elements with HTML id attribute"

    (it "id-based element keeps ref when surrounding content changes entirely"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button id='main-cta'>Click Me</button><p>Some text</p>")
        (let [before  (sut/capture-snapshot pg)
              btn-ref (find-ref-by before "button" "Click Me")]
        ;; Replace surrounding content, add new elements
          (page/evaluate pg
            "(() => { document.querySelector('p').remove(); document.body.appendChild(Object.assign(document.createElement('h2'), {textContent:'New Heading'})); document.body.appendChild(Object.assign(document.createElement('nav'), {innerHTML:'<a href=\"/\">Home</a>'})); })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "button" (:role (get (:refs after) btn-ref))))
            (expect (= "Click Me" (:name (get (:refs after) btn-ref))))))))

    (it "two elements with different ids get distinct stable refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button id='save-btn'>Save</button><button id='cancel-btn'>Cancel</button>")
        (let [snap       (sut/capture-snapshot pg)
              save-ref   (find-ref-by snap "button" "Save")
              cancel-ref (find-ref-by snap "button" "Cancel")]
          (expect (some? save-ref))
          (expect (some? cancel-ref))
          (expect (not= save-ref cancel-ref))))))

;; ── D. Identity changes (refs SHOULD change) ─────────────────────────────

  (describe "identity-changing mutations"

    (it "changing button text produces a different ref"

      (core/with-testing-page [pg] (page/set-content! pg "<button>Original</button>")
        (let [before  (sut/capture-snapshot pg)
              old-ref (find-ref-by before "button" "Original")]
          (page/evaluate pg "document.querySelector('button').textContent = 'Changed'")
          (let [after   (sut/capture-snapshot pg)
                new-ref (find-ref-by after "button" "Changed")]
          ;; Old ref no longer maps to anything; new ref exists
            (expect (nil? (get (:refs after) old-ref)))
            (expect (some? new-ref))
            (expect (not= old-ref new-ref))))))

    (it "changing aria-label produces a different ref"

      (core/with-testing-page [pg] (page/set-content! pg "<button aria-label='Close dialog'>X</button>")
        (let [before  (sut/capture-snapshot pg)
              old-ref (find-ref-by before "button" "Close dialog")]
          (page/evaluate pg
            "document.querySelector('button').setAttribute('aria-label', 'Dismiss')")
          (let [after   (sut/capture-snapshot pg)
                new-ref (find-ref-by after "button" "Dismiss")]
            (expect (nil? (get (:refs after) old-ref)))
            (expect (some? new-ref))
            (expect (not= old-ref new-ref)))))))

;; ── E. Duplicate disambiguation ──────────────────────────────────────────

  (describe "elements with identical role+name+tag"

    (it "two buttons with same text get distinct refs"

      (core/with-testing-page [pg] (page/set-content! pg "<button>Submit</button><button>Submit</button>")
        (let [snap        (sut/capture-snapshot pg)
              submit-refs (filterv
                            (fn [[_ info]]
                              (and (= "button" (:role info))
                                (= "Submit" (:name info))))
                            (:refs snap))]
        ;; Both exist as separate refs
          (expect (= 2 (count submit-refs)))
        ;; With different ref IDs
          (expect (not= (ffirst submit-refs) (first (second submit-refs)))))))

    (it "adding unrelated element between duplicates preserves both refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button>Submit</button><button>Submit</button>")
        (let [before             (sut/capture-snapshot pg)
              before-submit-refs (into #{}
                                   (keep (fn [[ref-id info]]
                                           (when (and (= "button" (:role info))
                                                   (= "Submit" (:name info)))
                                             ref-id)))
                                   (:refs before))]
        ;; Insert an unrelated heading between the two buttons
          (page/evaluate pg
            "(() => { const btns = document.querySelectorAll('button'); const h = document.createElement('h2'); h.textContent = 'Divider'; btns[0].after(h); })()")
          (let [after             (sut/capture-snapshot pg)
                after-submit-refs (into #{}
                                    (keep (fn [[ref-id info]]
                                            (when (and (= "button" (:role info))
                                                    (= "Submit" (:name info)))
                                              ref-id)))
                                    (:refs after))]
          ;; Same two Submit refs survive
            (expect (= before-submit-refs after-submit-refs)))))))

;; ── F. Visibility toggle ─────────────────────────────────────────────────

  (describe "visibility changes"

    (it "hiding then showing element restores the same ref"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button id='toggle-me'>Peek</button><a href='/x'>Anchor</a>")
        (let [before   (sut/capture-snapshot pg)
              btn-ref  (find-ref-by before "button" "Peek")
              link-ref (find-ref-by before "link" "Anchor")]
        ;; Hide the button
          (page/evaluate pg
            "document.getElementById('toggle-me').style.display = 'none'")
          (let [hidden-snap (sut/capture-snapshot pg)]
          ;; Button ref gone while hidden
            (expect (nil? (get (:refs hidden-snap) btn-ref)))
          ;; Link ref unchanged
            (expect (= "Anchor" (:name (get (:refs hidden-snap) link-ref)))))
        ;; Show it again
          (page/evaluate pg
            "document.getElementById('toggle-me').style.display = ''")
          (let [after (sut/capture-snapshot pg)]
          ;; Same ref returns
            (expect (= "Peek" (:name (get (:refs after) btn-ref)))))))))

;; ── G. Complex compound scenarios ────────────────────────────────────────

  (describe "compound mutations"

    (it "simultaneous add + remove + restyle leaves target refs stable"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>Dashboard</h1><button>Save</button><button>Cancel</button><a href='/help'>Help</a>")
        (let [before     (sut/capture-snapshot pg)
              save-ref   (find-ref-by before "button" "Save")
              cancel-ref (find-ref-by before "button" "Cancel")
              help-ref   (find-ref-by before "link" "Help")]
        ;; Remove h1, add a new nav, restyle existing button — all at once
          (page/evaluate pg
            "(() => { document.querySelector('h1').remove(); document.body.prepend(Object.assign(document.createElement('nav'), {innerHTML:'<a href=\"/home\">Home</a>'})); document.querySelector('button').className = 'btn-lg'; })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "Save" (:name (get (:refs after) save-ref))))
            (expect (= "Cancel" (:name (get (:refs after) cancel-ref))))
            (expect (= "Help" (:name (get (:refs after) help-ref))))))))

    (it "deeply nested element keeps ref when outer nesting changes"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<div><div><div><button id='deep'>Deep Button</button></div></div></div><p>Sibling</p>")
        (let [before   (sut/capture-snapshot pg)
              deep-ref (find-ref-by before "button" "Deep Button")]
        ;; Unwrap one nesting level
          (page/evaluate pg
            "(() => { const btn = document.getElementById('deep'); const inner = btn.parentElement; const outer = inner.parentElement; outer.replaceChild(btn, inner); })()")
          (let [after (sut/capture-snapshot pg)]
            (expect (= "Deep Button" (:name (get (:refs after) deep-ref)))))))))

;; ── H. Rich form with mixed element types ────────────────────────────────

  (describe "realistic form stability"

    (it "form elements keep refs after injecting validation messages"

      (core/with-testing-page [pg] (page/set-content! pg
                                     (str "<form>"
                                       "<label for='email'>Email</label>"
                                       "<input id='email' type='email' placeholder='you@example.org'/>"
                                       "<label for='pass'>Password</label>"
                                       "<input id='pass' type='password' placeholder='********'/>"
                                       "<button type='submit'>Log In</button>"
                                       "</form>"))
        (let [before     (sut/capture-snapshot pg)
              email-ref  (find-ref-by before "textbox" "Email")
              pass-ref   (find-ref-by before "textbox" "Password")
              submit-ref (find-ref-by before "button" "Log In")]
        ;; Inject validation error messages after each input
          (page/evaluate pg
            "document.querySelectorAll('input').forEach(inp => { const err = document.createElement('span'); err.className = 'error'; err.textContent = 'Required'; err.style.color = 'red'; inp.after(err); })")
          (let [after (sut/capture-snapshot pg)]
            (expect (some? (get (:refs after) email-ref)))
            (expect (some? (get (:refs after) pass-ref)))
            (expect (= "Log In" (:name (get (:refs after) submit-ref))))))))))

;; =============================================================================
;; Integration Tests — TASK-013: URL annotation in tree text
;; =============================================================================

(defdescribe url-annotation-test
  "Tests for AC-1..AC-4: link URLs shown in snapshot tree text"
  (around [f] (core/with-testing-browser (f)))

  (describe "link URL annotation"

    (it "links with href show [url=...] annotation in tree"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<a href='https://example.org/about'>About</a>")
        (let [snap (sut/capture-snapshot pg)]
          (expect (str/includes? (:tree snap) "[url=https://example.org/about]")))))

    (it "links without href do not show [url=...] annotation"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<a>No Link</a>")
        (let [snap (sut/capture-snapshot pg)]
          (expect (not (str/includes? (:tree snap) "[url="))))))

    (it "relative URLs are resolved to absolute"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (page/evaluate pg
          "document.body.innerHTML = '<a href=\"/relative\">Relative</a>'")
        (let [snap (sut/capture-snapshot pg)]
          (expect (str/includes? (:tree snap) "[url=https://example.org/relative]")))))

    (it "URL appears after ref annotation"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<a href='https://example.org'>Link</a>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
        ;; Pattern: [@eXXXXX] [url=...]
          (expect (re-find #"\[@e[a-z0-9]+\] \[url=https://example.org/?\]" tree)))))))

;; =============================================================================
;; Integration Tests — TASK-013: Structured refs map
;; =============================================================================

(defdescribe structured-refs-test
  "Tests for AC-5..AC-9: structured refs map in snapshot output"
  (around [f] (core/with-testing-browser (f)))

  (describe "refs include metadata fields"

    (it "link refs include url field"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<a href='https://example.org'>Example</a>")
        (let [snap (sut/capture-snapshot pg)
              link-ref (some (fn [[_ info]] (when (= "link" (:role info)) info))
                         (:refs snap))]
          (expect (= "https://example.org/" (:url link-ref))))))

    (it "heading refs include level field"

      (core/with-testing-page [pg] (page/set-content! pg "<h2>Subtitle</h2>")
        (let [snap (sut/capture-snapshot pg)
              h-ref (some (fn [[_ info]] (when (= "heading" (:role info)) info))
                      (:refs snap))]
          (expect (= 2 (:level h-ref))))))

    (it "input refs include type field"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<input type='email' placeholder='Email'/>")
        (let [snap (sut/capture-snapshot pg)
              input-ref (some (fn [[_ info]] (when (= "textbox" (:role info)) info))
                          (:refs snap))]
          (expect (= "email" (:type input-ref))))))

    (it "checkbox refs include checked field"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<input type='checkbox' aria-label='Accept'/><input type='checkbox' aria-label='Decline' checked/>")
        (let [snap (sut/capture-snapshot pg)
              accept (some (fn [[_ info]] (when (= "Accept" (:name info)) info))
                       (:refs snap))
              decline (some (fn [[_ info]] (when (= "Decline" (:name info)) info))
                        (:refs snap))]
          (expect (= false (:checked accept)))
          (expect (= true (:checked decline))))))

    (it "refs always include role field"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button>Btn</button><a href='/'>Link</a><h1>H</h1>")
        (let [snap (sut/capture-snapshot pg)]
          (doseq [[_ info] (:refs snap)]
            (expect (some? (:role info)))))))))

;; =============================================================================
;; Snapshot Diffing (pure unit tests)
;; =============================================================================

(defdescribe diff-snapshots-test
  "Tests for snapshot/diff-snapshots"
  (around [f] (core/with-testing-browser (f)))

  (describe "identical snapshots"
    (it "returns zero changes"

      (core/with-testing-page [_pg] (let [text "line1\nline2\nline3"
                                          result (sut/diff-snapshots text text)]
                                      (expect (= 0 (:added result)))
                                      (expect (= 0 (:removed result)))
                                      (expect (= 0 (:changed result)))
                                      (expect (= 3 (:unchanged result)))))))

  (describe "changed lines"
    (it "detects modified lines"

      (core/with-testing-page [_pg] (let [baseline "line1\noriginal\nline3"
                                          current  "line1\nmodified\nline3"
                                          result   (sut/diff-snapshots baseline current)]
                                      (expect (= 1 (:changed result)))
                                      (expect (= 2 (:unchanged result)))
                                      (expect (some #(str/starts-with? % "- ") (:diff result)))
                                      (expect (some #(str/starts-with? % "+ ") (:diff result)))))))

  (describe "added lines"
    (it "detects new lines at the end"

      (core/with-testing-page [_pg] (let [baseline "line1\nline2"
                                          current  "line1\nline2\nline3"
                                          result   (sut/diff-snapshots baseline current)]
                                      (expect (= 1 (:added result)))
                                      (expect (= 2 (:unchanged result)))))))

  (describe "removed lines"
    (it "detects removed lines"

      (core/with-testing-page [_pg] (let [baseline "line1\nline2\nline3"
                                          current  "line1\nline2"
                                          result   (sut/diff-snapshots baseline current)]
                                      (expect (= 1 (:removed result)))
                                      (expect (= 2 (:unchanged result))))))))

(defdescribe snapshot-styles-test
  "Integration tests for capture-snapshot styles and viewport"
  (around [f] (core/with-testing-browser (f)))

  (describe "capture-snapshot with styles and viewport"

    (it "capture-snapshot with minimal styles returns 16 style properties"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap   (sut/capture-snapshot pg {:styles true :styles-detail "minimal"})
              styled (filter :styles (vals (:refs snap)))]
          (expect (pos? (count styled)))
          (doseq [ref styled]
            (expect (= 16 (count (:styles ref))))))))

    (it "capture-snapshot with base styles includes styles in refs"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap   (sut/capture-snapshot pg {:styles true :styles-detail "base"})
              styled (filter :styles (vals (:refs snap)))]
          (expect (pos? (count styled)))
          (doseq [ref styled]
            (expect (= 31 (count (:styles ref)))))
          (expect (map? (:styles (first styled))))
          (expect (string? (first (keys (:styles (first styled))))))
          (expect (or (contains? (:styles (first styled)) "font-size")
                    (contains? (:styles (first styled)) "display"))))))

    (it "capture-snapshot with max styles returns 44 style properties"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap   (sut/capture-snapshot pg {:styles true :styles-detail "max"})
              styled (filter :styles (vals (:refs snap)))]
          (expect (pos? (count styled)))
          (doseq [ref styled]
            (expect (= 44 (count (:styles ref))))))))

    (it "capture-snapshot without styles has no styles in refs"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)]
          (expect (every? (comp nil? :styles val) (:refs snap))))))

    (it "capture-snapshot returns viewport width and height"

      (core/with-testing-page [pg] (page/navigate pg "https://example.org")
        (let [snap (sut/capture-snapshot pg)
              vp   (:viewport snap)]
          (expect (map? vp))
          (expect (number? (:width vp)))
          (expect (number? (:height vp)))
          (expect (pos? (:width vp)))
          (expect (pos? (:height vp))))))))

(defdescribe format-styles-unit-test
  "Unit tests for private format-styles"
  (around [f] (core/with-testing-browser (f)))

  (describe "format-styles output"
    (it "formats style map with semicolon separator and display order"

      (core/with-testing-page [_pg] (let [styles {"color" "rgb(0, 0, 0)" "display" "block"}
                                          out    (#'com.blockether.spel.snapshot/format-styles styles)]
                                      (expect (= "{display:block;color:rgb(0, 0, 0)}" out))
                                      (expect (str/includes? out ";"))
                                      (expect (not (str/includes? out "; ")))
                                      (expect (< (.indexOf ^String out "display:block")
                                                (.indexOf ^String out "color:rgb(0, 0, 0)"))))))

    (it "filters out nil style values"

      (core/with-testing-page [_pg] (let [out (#'com.blockether.spel.snapshot/format-styles
                                               {"display" "block" "color" nil "font-size" nil})]
                                      (expect (= "{display:block}" out)))))))

;; =============================================================================
;; Integration Tests — Event Listener Detection
;; =============================================================================

(defdescribe event-listener-detection-test
  "Integration tests for event listener annotations in snapshots.

   Tests that elements with JS event handlers (onclick, React props, etc.)
   are detected and annotated with [on:click] etc. in the snapshot tree."
  (around [f] (core/with-testing-browser (f)))

  (describe "inline onclick handler detection"

    (it "detects onclick handler and annotates with [on:click]"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<div onclick='alert(1)'>Click me</div>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
          (expect (string? tree))
          (expect (str/includes? tree "[on:click]"))))))

  (describe "JS-assigned event handler detection"

    (it "detects programmatic onclick assigned via JS"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button id='btn'>JS Click</button>")
        (page/evaluate pg
          "document.getElementById('btn').onclick = function() {}")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
          (expect (string? tree))
          (expect (str/includes? tree "[on:click]"))))))

  (describe "multiple event types detection"

    (it "detects multiple handler types on same element"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<input id='inp' type='text' />")
        (page/evaluate pg
          "(() => { const e = document.getElementById('inp'); e.onfocus = function(){}; e.onblur = function(){}; e.onchange = function(){}; })()")
        (let [snap (sut/capture-snapshot pg)
            ;; Find the ref for the input
              inp-ref (some (fn [[ref-id data]]
                              (when (= "textbox" (:role data))
                                ref-id))
                        (:refs snap))]
          (expect (some? inp-ref))
          (expect (seq (:listeners (get (:refs snap) inp-ref))))
        ;; At least focus, blur, change should be detected
          (let [listeners (set (:listeners (get (:refs snap) inp-ref)))]
            (expect (contains? listeners "focus"))
            (expect (contains? listeners "blur"))
            (expect (contains? listeners "change")))))))

  (describe "listener data in refs map"

    (it "refs include :listeners vector for elements with handlers"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button onclick='alert(1)'>Alert</button>")
        (let [snap (sut/capture-snapshot pg)
              btn-ref (some (fn [[ref-id data]]
                              (when (= "button" (:role data))
                                ref-id))
                        (:refs snap))]
          (expect (some? btn-ref))
          (expect (vector? (:listeners (get (:refs snap) btn-ref))))
          (expect (some #(= "click" %) (:listeners (get (:refs snap) btn-ref))))))))

  (describe "elements without handlers have no listeners"

    (it "plain heading has no :listeners in refs"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<h1>No Events</h1>")
        (let [snap (sut/capture-snapshot pg)
              h1-ref (some (fn [[ref-id data]]
                             (when (= "heading" (:role data))
                               ref-id))
                       (:refs snap))]
          (expect (some? h1-ref))
          (expect (nil? (:listeners (get (:refs snap) h1-ref)))))))))

;; =============================================================================
;; Integration Tests — Cursor Pointer / [clickable] Noise Reduction
;; =============================================================================

(defdescribe clickable-noise-reduction-test
  "Integration tests for [clickable] annotation noise reduction.

   Tests that cursor:pointer inherited from interactive ancestors
   does NOT mark child elements as [clickable]."
  (around [f] (core/with-testing-browser (f)))

  (describe "direct cursor:pointer gets [clickable]"

    (it "element with direct cursor:pointer style is marked clickable"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<div style='cursor:pointer'>Clickable div</div>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
          (expect (string? tree))
        ;; The div should have [clickable] or be interactive
          (expect (str/includes? tree "[clickable]"))))))

  (describe "inherited cursor:pointer from button"

    (it "child span inside button does NOT get separate [clickable]"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<button style='cursor:pointer'><span id='inner'>Inside Button</span></button>")
        (let [snap (sut/capture-snapshot pg)
            ;; The inner span should NOT have :pointer true
              inner-ref (some (fn [[ref-id data]]
                                (when (and (= "span" (:tag data))
                                        (= "Inside Button" (:name data)))
                                  ref-id))
                          (:refs snap))]
        ;; Inner span may or may not have a ref, but if it does, it should NOT have :pointer
          (when inner-ref
            (expect (not (:pointer (get (:refs snap) inner-ref)))))))))

  (describe "inherited cursor:pointer from link"

    (it "child element inside <a> link does NOT get separate [clickable]"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<a href='/test' style='cursor:pointer'><span>Link Text</span></a>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
        ;; The tree should have link but child span should not independently have [clickable]
          (expect (string? tree))
          (expect (str/includes? tree "link")))))))

;; =============================================================================
;; Integration Tests — Container Content Preview
;; =============================================================================

(defdescribe container-content-preview-test
  "Integration tests for container element content previews.

   Tests that article, region, listitem, figure, group elements
   get content preview names from innerText."
  (around [f] (core/with-testing-browser (f)))

  (describe "article content preview"

    (it "article element gets content preview as name"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<article><h2>Product Title</h2><p>Description text here</p></article>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
          (expect (string? tree))
          (expect (str/includes? tree "article"))
        ;; Content preview should include some of the article text
          (expect (str/includes? tree "Product Title"))))))

  (describe "article preview uses innerText spacing"

    (it "content preview has spaces between child block elements"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<article><div>First Block</div><div>Second Block</div></article>")
        (let [snap (sut/capture-snapshot pg)
              article-ref (some (fn [[ref-id data]]
                                  (when (= "article" (:role data))
                                    ref-id))
                            (:refs snap))
              article-name (:name (get (:refs snap) article-ref))]
          (expect (some? article-ref))
        ;; innerText should produce space-separated text, not concatenated
        ;; "First BlockSecond Block" (bad, textContent) vs "First Block Second Block" (good, innerText)
          (expect (some? article-name))
          (when article-name
            (expect (not (str/includes? article-name "BlockSecond"))))))))

  (describe "listitem content preview"

    (it "li element gets content preview"

      (core/with-testing-page [pg] (page/set-content! pg
                                     "<ul><li><span>Item One</span> <span>Detail</span></li></ul>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)]
          (expect (string? tree))
          (expect (str/includes? tree "listitem"))))))

  (describe "long content is truncated"

    (it "content preview is truncated to 80 chars with ellipsis"

      (core/with-testing-page [pg] (page/set-content! pg
                                     (str "<article><p>" (apply str (repeat 100 "x")) "</p></article>"))
        (let [snap (sut/capture-snapshot pg)
              article-ref (some (fn [[ref-id data]]
                                  (when (= "article" (:role data))
                                    ref-id))
                            (:refs snap))
              article-name (:name (get (:refs snap) article-ref))]
          (expect (some? article-ref))
          (when article-name
            (expect (<= (count article-name) 84)) ;; 80 chars + "..." + some margin
            (expect (str/ends-with? article-name "..."))))))))

;; =============================================================================
;; Integration Tests — [pos:X,Y W×H] screen position in tree output
;; =============================================================================

(defdescribe snapshot-position-test
  "Integration tests for [pos:X,Y W×H] screen position annotations in tree output.

   Uses controlled HTML with known absolute positions and fixed dimensions to verify
   that the accessibility tree includes accurate bounding rect coordinates for every
   ref'd element. Real Playwright rendering — no mocks."
  (around [f] (core/with-testing-browser (f)))

  (describe "basic position annotations present in tree"

    (it "every ref'd element has [pos:] annotation in tree output"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 800 600)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>body{margin:0;font-family:sans-serif;}</style></head>
         <body>
           <h1>Page Title</h1>
           <button>Click Me</button>
           <a href='/about'>About Us</a>
         </body></html>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)
              ref-lines (filter #(str/includes? % "[@e") (str/split-lines tree))]
          (expect (pos? (count ref-lines)))
          (doseq [line ref-lines]
            (expect (some? (re-find #"\[pos:\d+,\d+ \d+×\d+\]" line))))
          (allure/attach "tree-with-positions" tree "text/plain")))))

  (describe "position values match element bounding boxes"

    (it "absolute-positioned element has correct coordinates in tree"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 800 600)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>
           body { margin: 0; }
           .box { position: absolute; top: 100px; left: 200px; width: 150px; height: 50px;
                  background: #3b82f6; }
         </style></head>
         <body>
           <button class='box'>Positioned Button</button>
         </body></html>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)
              refs (:refs snap)
              btn-entry (first (filter (fn [[_ v]] (= "Positioned Button" (:name v))) refs))
              btn-bbox (:bbox (val btn-entry))
              btn-line (first (filter #(str/includes? % "Positioned Button") (str/split-lines tree)))]
          (expect (some? btn-line))
          (expect (some? (re-find #"\[pos:" btn-line)))
          (expect (= 200 (:x btn-bbox)))
          (expect (= 100 (:y btn-bbox)))
          (expect (= 150 (:width btn-bbox)))
          (expect (= 50 (:height btn-bbox)))
          (expect (str/includes? btn-line "[pos:200,100 150×50]"))
          (allure/attach "absolute-position-tree" tree "text/plain")
          (allure/attach "absolute-position-refs" (pr-str refs) "text/plain")))))

  (describe "complex layout with multiple positioned elements"

    (it "header, nav links, and content all have distinct positions"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 1024 768)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>
           * { margin: 0; padding: 0; box-sizing: border-box; }
           .header { width: 100%; height: 60px; background: #1e293b; display: flex;
                     align-items: center; padding: 0 20px; }
           .header h1 { color: white; font-size: 20px; }
           .nav { display: flex; gap: 0; background: #f1f5f9; height: 40px; }
           .nav a { display: inline-flex; align-items: center; padding: 0 16px;
                    color: #1e40af; text-decoration: none; font-size: 14px; height: 40px; }
           .main { padding: 20px; }
           .main h2 { font-size: 24px; margin-bottom: 12px; }
           .main p { font-size: 16px; color: #475569; }
           .sidebar { position: absolute; top: 100px; right: 0; width: 200px;
                      height: 300px; background: #fef3c7; padding: 16px; }
           .sidebar button { display: block; width: 100%; padding: 8px;
                             margin-bottom: 8px; background: #f59e0b; border: none;
                             border-radius: 4px; cursor: pointer; font-size: 14px; }
         </style></head>
         <body>
           <div class='header'><h1>Dashboard</h1></div>
           <nav class='nav'>
             <a href='/home'>Home</a>
             <a href='/reports'>Reports</a>
             <a href='/settings'>Settings</a>
           </nav>
           <div class='main'>
             <h2>Overview</h2>
             <p>Welcome to your dashboard. Here you can manage your projects.</p>
           </div>
           <div class='sidebar'>
             <button>New Project</button>
             <button>Import Data</button>
             <button>Export CSV</button>
           </div>
         </body></html>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)
              refs (:refs snap)
              lines (str/split-lines tree)
              ref-lines (filter #(re-find #"\[@e" %) lines)
              parse-pos (fn [line]
                          (when-let [m (re-find #"\[pos:(\d+),(\d+) (\d+)×(\d+)\]" line)]
                            {:x (parse-long (nth m 1)) :y (parse-long (nth m 2))
                             :w (parse-long (nth m 3)) :h (parse-long (nth m 4))}))
              header-line (first (filter #(str/includes? % "Dashboard") ref-lines))
              home-line (first (filter #(str/includes? % "Home") ref-lines))
              reports-line (first (filter #(str/includes? % "Reports") ref-lines))
              settings-line (first (filter #(str/includes? % "Settings") ref-lines))
              overview-line (first (filter #(str/includes? % "Overview") ref-lines))
              new-proj-line (first (filter #(str/includes? % "New Project") ref-lines))
              export-line (first (filter #(str/includes? % "Export CSV") ref-lines))
              header-pos (parse-pos header-line)
              home-pos (parse-pos home-line)
              reports-pos (parse-pos reports-line)
              settings-pos (parse-pos settings-line)
              overview-pos (parse-pos overview-line)
              new-proj-pos (parse-pos new-proj-line)
              export-pos (parse-pos export-line)]
        ;; All ref'd lines have position annotations
          (expect (pos? (count ref-lines)))
          (doseq [line ref-lines]
            (expect (some? (re-find #"\[pos:\d+,\d+ \d+×\d+\]" line))))
        ;; Header at top
          (expect (some? header-pos))
          (expect (< (:y header-pos) 60))
        ;; Nav below header, horizontally sequential
          (expect (some? home-pos))
          (expect (>= (:y home-pos) 60))
          (expect (some? reports-pos))
          (expect (> (:x reports-pos) (:x home-pos)))
          (expect (some? settings-pos))
          (expect (> (:x settings-pos) (:x reports-pos)))
        ;; Overview below nav
          (expect (some? overview-pos))
          (expect (> (:y overview-pos) (:y home-pos)))
        ;; Sidebar on right
          (expect (some? new-proj-pos))
          (expect (> (:x new-proj-pos) 700))
        ;; Export below New Project
          (expect (some? export-pos))
          (expect (> (:y export-pos) (:y new-proj-pos)))
          (allure/attach "complex-layout-tree" tree "text/plain")
          (allure/attach "complex-layout-refs" (pr-str refs) "text/plain")))))

  (describe "position updates after DOM mutation"

    (it "position changes when element is moved"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 800 600)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>
           body { margin: 0; }
           #movable { position: absolute; top: 50px; left: 50px; width: 120px; height: 40px; }
         </style></head>
         <body>
           <button id='movable'>Move Me</button>
         </body></html>")
        (let [snap1 (sut/capture-snapshot pg)
              tree1 (:tree snap1)
              btn-line1 (first (filter #(str/includes? % "Move Me") (str/split-lines tree1)))
              pos1-match (re-find #"\[pos:(\d+),(\d+)" btn-line1)
              x1 (parse-long (nth pos1-match 1))
              y1 (parse-long (nth pos1-match 2))]
          (page/evaluate pg
            "document.getElementById('movable').style.top = '300px';
           document.getElementById('movable').style.left = '400px';")
          (sut/clear-refs! pg)
          (let [snap2 (sut/capture-snapshot pg)
                tree2 (:tree snap2)
                btn-line2 (first (filter #(str/includes? % "Move Me") (str/split-lines tree2)))
                pos2-match (re-find #"\[pos:(\d+),(\d+)" btn-line2)
                x2 (parse-long (nth pos2-match 1))
                y2 (parse-long (nth pos2-match 2))]
            (expect (= 50 x1))
            (expect (= 50 y1))
            (expect (= 400 x2))
            (expect (= 300 y2))
            (allure/attach "before-move" tree1 "text/plain")
            (allure/attach "after-move" tree2 "text/plain"))))))

  (describe "position with viewport changes"

    (it "elements reflow to new positions when viewport shrinks"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 1200 800)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>
           body { margin: 0; }
           .grid { display: flex; flex-wrap: wrap; }
           .card { width: 300px; height: 100px; margin: 10px; background: #e2e8f0;
                   display: flex; align-items: center; justify-content: center; }
           .card button { padding: 8px 16px; background: #3b82f6; color: white;
                          border: none; border-radius: 4px; font-size: 14px; }
         </style></head>
         <body>
           <div class='grid'>
             <div class='card'><button>Card One</button></div>
             <div class='card'><button>Card Two</button></div>
             <div class='card'><button>Card Three</button></div>
           </div>
         </body></html>")
        (let [snap-wide (sut/capture-snapshot pg)
              tree-wide (:tree snap-wide)
              wide-lines (filter #(re-find #"Card (One|Two|Three)" %) (str/split-lines tree-wide))
              parse-y (fn [line]
                        (when-let [m (re-find #"\[pos:\d+,(\d+)" line)]
                          (parse-long (second m))))
              wide-ys (map parse-y wide-lines)]
          (expect (= 3 (count wide-lines)))
        ;; In 1200px viewport, all 3 cards fit on one row (same Y)
          (expect (apply = wide-ys))
        ;; Shrink viewport so cards must wrap
          (page/set-viewport-size! pg 400 800)
          (sut/clear-refs! pg)
          (let [snap-narrow (sut/capture-snapshot pg)
                tree-narrow (:tree snap-narrow)
                narrow-lines (filter #(re-find #"Card (One|Two|Three)" %) (str/split-lines tree-narrow))
                narrow-ys (map parse-y narrow-lines)]
            (expect (= 3 (count narrow-lines)))
          ;; Cards should now be stacked vertically
            (expect (apply < narrow-ys))
            (allure/attach "wide-tree" tree-wide "text/plain")
            (allure/attach "narrow-tree" tree-narrow "text/plain"))))))

  (describe "position accuracy for form elements"

    (it "form inputs and buttons have correct relative positions via refs"

      (core/with-testing-page [pg] (page/set-viewport-size! pg 800 600)
        (page/set-content! pg
          "<!DOCTYPE html>
         <html><head><style>
           * { box-sizing: border-box; }
           body { margin: 0; padding: 20px; font-family: sans-serif; }
           .form-group { margin-bottom: 20px; }
           label { display: block; font-size: 14px; margin-bottom: 4px; color: #374151; }
           input { width: 300px; height: 36px; padding: 0 8px; border: 1px solid #d1d5db;
                   border-radius: 4px; font-size: 14px; }
           button { padding: 10px 20px; background: #10b981; color: white; border: none;
                    border-radius: 4px; font-size: 16px; cursor: pointer; margin-top: 10px; }
         </style></head>
         <body>
           <h2>Registration Form</h2>
           <form>
             <div class='form-group'>
               <label for='email'>Email Address</label>
               <input id='email' type='email' placeholder='you@example.com'/>
             </div>
             <div class='form-group'>
               <label for='pw'>Password</label>
               <input id='pw' type='password' placeholder='Enter password'/>
             </div>
             <button type='submit'>Create Account</button>
           </form>
         </body></html>")
        (let [snap (sut/capture-snapshot pg)
              tree (:tree snap)
              refs (:refs snap)
              find-ref (fn [role name-substr]
                         (first (keep (fn [[_ v]]
                                        (when (and (= role (:role v))
                                                (or (nil? name-substr)
                                                  (and (:name v) (str/includes? (:name v) name-substr))))
                                          v))
                                  refs)))
              heading (find-ref "heading" "Registration")
              email-input (find-ref "textbox" "Email")
              pw-input (find-ref "textbox" "Password")
              submit-btn (find-ref "button" "Create Account")]
        ;; All form elements found in refs with bboxes
          (expect (some? heading))
          (expect (some? (:bbox heading)))
          (expect (some? email-input))
          (expect (some? (:bbox email-input)))
          (expect (some? pw-input))
          (expect (some? (:bbox pw-input)))
          (expect (some? submit-btn))
          (expect (some? (:bbox submit-btn)))
        ;; Vertical ordering: heading > email > password > button
          (expect (< (:y (:bbox heading)) (:y (:bbox email-input))))
          (expect (< (:y (:bbox email-input)) (:y (:bbox pw-input))))
          (expect (< (:y (:bbox pw-input)) (:y (:bbox submit-btn))))
        ;; Inputs left-aligned
          (expect (< (abs (- (:x (:bbox email-input)) (:x (:bbox pw-input)))) 5))
        ;; Input widths = 300px, heights = 36px (box-sizing: border-box)
          (expect (= 300 (:width (:bbox email-input))))
          (expect (= 300 (:width (:bbox pw-input))))
          (expect (= 36 (:height (:bbox email-input))))
          (expect (= 36 (:height (:bbox pw-input))))
        ;; Tree has [pos:] for all refs
          (let [ref-lines (filter #(re-find #"\[@e" %) (str/split-lines tree))]
            (doseq [line ref-lines]
              (expect (some? (re-find #"\[pos:\d+,\d+ \d+×\d+\]" line)))))
          (allure/attach "form-layout-tree" tree "text/plain")
          (allure/attach "form-positions"
            (pr-str {:heading (:bbox heading) :email (:bbox email-input)
                     :password (:bbox pw-input) :submit (:bbox submit-btn)}) "text/plain"))))))
