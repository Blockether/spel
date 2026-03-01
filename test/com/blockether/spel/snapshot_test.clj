(ns com.blockether.spel.snapshot-test
  "Tests for the snapshot namespace.

   Unit tests for pure helper functions and integration tests
   that run against example.org using Playwright."
  (:require
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as sut]
   [com.blockether.spel.test-fixtures :refer [*page* with-playwright
                                              with-browser with-page]]
   [com.blockether.spel.allure :refer [defdescribe describe expect it]]))

;; =============================================================================
;; Unit Tests — ref-bounding-box
;; =============================================================================

(defdescribe ref-bounding-box-test
  "Unit tests for ref-bounding-box"

  (describe "returns bbox for existing ref"
    (it "extracts bbox map from refs"
      (let [refs {"e1" {:role "button" :name "Submit"
                        :bbox {:x 10 :y 20 :width 80 :height 30}}
                  "e2" {:role "link" :name "Home"
                        :bbox {:x 0 :y 0 :width 100 :height 20}}}]
        (expect (= {:x 10 :y 20 :width 80 :height 30}
                  (sut/ref-bounding-box refs "e1")))
        (expect (= {:x 0 :y 0 :width 100 :height 20}
                  (sut/ref-bounding-box refs "e2"))))))

  (describe "returns nil for missing ref"
    (it "returns nil when ref not in map"
      (let [refs {"e1" {:role "button" :name "Submit"
                        :bbox {:x 10 :y 20 :width 80 :height 30}}}]
        (expect (nil? (sut/ref-bounding-box refs "e99")))
        (expect (nil? (sut/ref-bounding-box refs "nonexistent"))))))

  (describe "handles empty refs map"
    (it "returns nil for empty map"
      (expect (nil? (sut/ref-bounding-box {} "e1"))))))

;; =============================================================================
;; Integration Tests — capture-snapshot
;; =============================================================================

(defdescribe capture-snapshot-test
  "Integration tests for capture-snapshot against example.org"

  (describe "snapshot structure"
    {:context [with-playwright with-browser with-page]}

    (it "returns map with :tree :refs :counter keys"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (map? snap))
        (expect (contains? snap :tree))
        (expect (contains? snap :refs))
        (expect (contains? snap :counter))))

    (it "tree is a non-empty string"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (string? (:tree snap)))
        (expect (pos? (count (:tree snap))))))

    (it "counter is a positive number"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (number? (:counter snap)))
        (expect (pos? (:counter snap)))))

    (it "refs is a non-empty map"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (map? (:refs snap)))
        (expect (pos? (count (:refs snap)))))))

  (describe "tree content for example.org"
    {:context [with-playwright with-browser with-page]}

    (it "contains heading role"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "heading"))))

    (it "contains link role"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "link"))))

    (it "contains ref annotations"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "[@e")))))

  (describe "refs content"
    {:context [with-playwright with-browser with-page]}

    (it "ref entries have correct structure"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)
            first-ref (val (first (:refs snap)))]
        (expect (contains? first-ref :role))
        (expect (contains? first-ref :name))
        (expect (contains? first-ref :bbox))
        (expect (contains? first-ref :tag))))

    (it "bbox values are numbers"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)
            first-ref (val (first (:refs snap)))
            bbox (:bbox first-ref)]
        (expect (number? (:x bbox)))
        (expect (number? (:y bbox)))
        (expect (number? (:width bbox)))
        (expect (number? (:height bbox)))))

    (it "ref keys follow content-hash pattern"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)]
        (doseq [ref-id (keys (:refs snap))]
          (expect (re-matches #"e[a-z0-9]+" ref-id)))))))

;; =============================================================================
;; Integration Tests — resolve-ref
;; =============================================================================

(defdescribe resolve-ref-test
  "Integration tests for resolve-ref"

  (describe "resolving refs after snapshot"
    {:context [with-playwright with-browser with-page]}

    (it "returns a Locator for valid ref"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)
            first-ref-id (key (first (:refs snap)))
            locator (sut/resolve-ref *page* first-ref-id)]
        (expect (some? locator))
        (expect (instance? com.microsoft.playwright.Locator locator))))

    (it "locator matches the correct element"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-snapshot *page*)
            ;; Find the heading ref
            heading-ref (some (fn [[ref-id info]]
                                (when (= "heading" (:role info))
                                  ref-id))
                          (:refs snap))]
        (when heading-ref
          (let [locator (sut/resolve-ref *page* heading-ref)
                text (.textContent locator)]
            (expect (= "Example Domain" text))))))))

;; =============================================================================
;; Integration Tests — clear-refs!
;; =============================================================================

(defdescribe clear-refs-test
  "Integration tests for clear-refs!"

  (describe "clearing refs from DOM"
    {:context [with-playwright with-browser with-page]}

    (it "removes data-pw-ref attributes"
      (page/navigate *page* "https://example.org")
      ;; First capture to add refs
      (sut/capture-snapshot *page*)
      ;; Verify refs exist
      (let [count-before (page/evaluate *page*
                           "document.querySelectorAll('[data-pw-ref]').length")]
        (expect (pos? (int count-before))))
      ;; Clear refs
      (sut/clear-refs! *page*)
      ;; Verify refs removed
      (let [count-after (page/evaluate *page*
                          "document.querySelectorAll('[data-pw-ref]').length")]
        (expect (zero? (int count-after)))))))

;; =============================================================================
;; Integration Tests — capture-full-snapshot
;; =============================================================================

(defdescribe capture-full-snapshot-test
  "Integration tests for capture-full-snapshot"

  (describe "full snapshot on page without iframes"
    {:context [with-playwright with-browser with-page]}

    (it "returns same structure as capture-snapshot for simple pages"
      (page/navigate *page* "https://example.org")
      (let [snap (sut/capture-full-snapshot *page*)]
        (expect (map? snap))
        (expect (contains? snap :tree))
        (expect (contains? snap :refs))
        (expect (contains? snap :counter))
        (expect (string? (:tree snap)))
        (expect (pos? (count (:refs snap))))))))

;; =============================================================================
;; Integration Tests — deterministic refs (content-hash stability)
;; =============================================================================

(defdescribe deterministic-refs-test
  "Tests that snapshot refs are deterministic and stable across page states.

   Content-hash refs use FNV-1a(role|name|tag) so the same element always
   gets the same ref regardless of what other elements exist on the page."

  (describe "same page produces identical refs"
    {:context [with-playwright with-browser with-page]}

    (it "two consecutive snapshots produce identical ref keys"
      (page/navigate *page* "https://example.org")
      (let [snap1 (sut/capture-snapshot *page*)
            snap2 (sut/capture-snapshot *page*)]
        (expect (= (set (keys (:refs snap1)))
                  (set (keys (:refs snap2)))))
        ;; Same ref maps to same role+name
        (doseq [[ref-id info] (:refs snap1)]
          (expect (= (:role info) (:role (get (:refs snap2) ref-id))))
          (expect (= (:name info) (:name (get (:refs snap2) ref-id)))))))

    (it "refs are stable after page reload"
      (page/navigate *page* "https://example.org")
      (let [snap1 (sut/capture-snapshot *page*)]
        (page/navigate *page* "https://example.org")
        (let [snap2 (sut/capture-snapshot *page*)]
          (expect (= (set (keys (:refs snap1)))
                    (set (keys (:refs snap2)))))))))

  (describe "refs survive DOM mutations"
    {:context [with-playwright with-browser with-page]}

    (it "adding a new element does not change existing refs"
      (page/navigate *page* "https://example.org")
      (let [snap-before (sut/capture-snapshot *page*)
            before-refs (:refs snap-before)]
        ;; Inject a new button into the page
        (page/evaluate *page*
          "(() => { const btn = document.createElement('button'); btn.textContent = 'Injected'; document.body.prepend(btn); })()")
        (let [snap-after (sut/capture-snapshot *page*)
              after-refs (:refs snap-after)]
          ;; All old refs should still exist with same role+name
          (doseq [[ref-id info] before-refs]
            (let [after-info (get after-refs ref-id)]
              (expect (some? after-info))
              (expect (= (:role info) (:role after-info)))
              (expect (= (:name info) (:name after-info)))))
          ;; There should be one MORE ref (the injected button)
          (expect (> (count after-refs) (count before-refs))))))

    (it "removing an element does not change remaining refs"
      (page/navigate *page* "https://example.org")
      (let [snap-before (sut/capture-snapshot *page*)
            before-refs (:refs snap-before)
            ;; Find the link ref to remove
            link-ref (some (fn [[ref-id info]]
                             (when (= "link" (:role info))
                               ref-id))
                       before-refs)
            non-link-refs (dissoc before-refs link-ref)]
        ;; Remove the link from the DOM
        (page/evaluate *page*
          "(() => { const a = document.querySelector('a'); if(a) a.remove(); })()")
        (let [snap-after (sut/capture-snapshot *page*)
              after-refs (:refs snap-after)]
          ;; All non-link refs should still exist with same role+name
          (doseq [[ref-id info] non-link-refs]
            (let [after-info (get after-refs ref-id)]
              (expect (some? after-info))
              (expect (= (:role info) (:role after-info)))
              (expect (= (:name info) (:name after-info)))))
          ;; The link ref should be gone
          (expect (nil? (get after-refs link-ref))))))))

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

  ;; ── A. Basic determinism ──────────────────────────────────────────────────

  (describe "basic determinism with controlled HTML"
    {:context [with-playwright with-browser with-page]}

    (it "same HTML produces identical refs across two snapshots"
      (page/set-content! *page*
        "<h1>Title</h1><nav><a href='/about'>About</a><a href='/contact'>Contact</a></nav><button>Submit</button>")
      (let [snap1 (sut/capture-snapshot *page*)
            snap2 (sut/capture-snapshot *page*)]
        (expect (= (ref-identity-map snap1) (ref-identity-map snap2)))))

    (it "capture then clear then capture produces same refs (idempotent)"
      (page/set-content! *page*
        "<h1>Hello</h1><form><input type='text' placeholder='Email'/><button>Sign Up</button></form>")
      (let [snap1 (sut/capture-snapshot *page*)]
        (sut/clear-refs! *page*)
        (let [snap2 (sut/capture-snapshot *page*)]
          (expect (= (ref-identity-map snap1) (ref-identity-map snap2)))))))

  ;; ── B. Structural stability (refs DON'T change) ──────────────────────────

  (describe "adding unrelated elements"
    {:context [with-playwright with-browser with-page]}

    (it "appending a new element at end does not change existing refs"
      (page/set-content! *page*
        "<h1>Title</h1><button>Save</button><a href='/home'>Home</a>")
      (let [before     (sut/capture-snapshot *page*)
            before-ids (ref-identity-map before)]
        (page/evaluate *page*
          "document.body.appendChild(Object.assign(document.createElement('button'), {textContent:'New'}))")
        (let [after     (sut/capture-snapshot *page*)
              after-ids (ref-identity-map after)]
          ;; Every original ref still exists with same identity
          (doseq [[ref-id identity] before-ids]
            (expect (= identity (get after-ids ref-id))))
          ;; Plus one new ref
          (expect (= (inc (count before-ids)) (count after-ids))))))

    (it "prepending a new element at beginning does not change existing refs"
      (page/set-content! *page*
        "<h1>Title</h1><button>Save</button><a href='/home'>Home</a>")
      (let [before     (sut/capture-snapshot *page*)
            before-ids (ref-identity-map before)]
        (page/evaluate *page*
          "document.body.prepend(Object.assign(document.createElement('h2'), {textContent:'Prepended'}))")
        (let [after     (sut/capture-snapshot *page*)
              after-ids (ref-identity-map after)]
          (doseq [[ref-id identity] before-ids]
            (expect (= identity (get after-ids ref-id))))))))

  (describe "removing unrelated elements"
    {:context [with-playwright with-browser with-page]}

    (it "removing one element does not change other refs"
      (page/set-content! *page*
        "<h1>Title</h1><button>Delete Me</button><button>Keep Me</button><a href='/x'>Link</a>")
      (let [before    (sut/capture-snapshot *page*)
            keep-ref  (find-ref-by before "button" "Keep Me")
            link-ref  (find-ref-by before "link" "Link")
            title-ref (find-ref-by before "heading" "Title")]
        ;; Remove the first button
        (page/evaluate *page*
          "document.body.querySelector('button').remove()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "button" (:role (get (:refs after) keep-ref))))
          (expect (= "Keep Me" (:name (get (:refs after) keep-ref))))
          (expect (= "link" (:role (get (:refs after) link-ref))))
          (expect (= "heading" (:role (get (:refs after) title-ref))))))))

  (describe "reordering unique elements"
    {:context [with-playwright with-browser with-page]}

    (it "reversing element order preserves all refs when identities are unique"
      (page/set-content! *page*
        "<div id='container'><button>Alpha</button><button>Beta</button><button>Gamma</button></div>")
      (let [before    (sut/capture-snapshot *page*)
            alpha-ref (find-ref-by before "button" "Alpha")
            beta-ref  (find-ref-by before "button" "Beta")
            gamma-ref (find-ref-by before "button" "Gamma")]
        ;; Reverse the order: Gamma, Beta, Alpha
        (page/evaluate *page*
          "(() => { const c = document.getElementById('container'); [...c.children].reverse().forEach(el => c.appendChild(el)); })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "Alpha" (:name (get (:refs after) alpha-ref))))
          (expect (= "Beta" (:name (get (:refs after) beta-ref))))
          (expect (= "Gamma" (:name (get (:refs after) gamma-ref))))))))

  (describe "structural wrapping"
    {:context [with-playwright with-browser with-page]}

    (it "wrapping element in new container div does not change its ref"
      (page/set-content! *page*
        "<button>Wrapped</button><a href='/x'>Stay</a>")
      (let [before   (sut/capture-snapshot *page*)
            btn-ref  (find-ref-by before "button" "Wrapped")
            link-ref (find-ref-by before "link" "Stay")]
        ;; Wrap button in a new section
        (page/evaluate *page*
          "(() => { const btn = document.querySelector('button'); const wrap = document.createElement('section'); btn.parentNode.insertBefore(wrap, btn); wrap.appendChild(btn); })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "Wrapped" (:name (get (:refs after) btn-ref))))
          (expect (= "Stay" (:name (get (:refs after) link-ref))))))))

  (describe "cosmetic attribute changes"
    {:context [with-playwright with-browser with-page]}

    (it "changing CSS class, style, and data attributes does not change ref"
      (page/set-content! *page*
        "<button class='primary'>Styled</button>")
      (let [before  (sut/capture-snapshot *page*)
            btn-ref (find-ref-by before "button" "Styled")]
        (page/evaluate *page*
          "(() => { const b = document.querySelector('button'); b.className = 'danger large'; b.style.color = 'red'; b.style.fontSize = '24px'; b.setAttribute('data-testid', 'xyz'); })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "button" (:role (get (:refs after) btn-ref))))
          (expect (= "Styled" (:name (get (:refs after) btn-ref))))))))

  ;; ── C. ID-based stability ─────────────────────────────────────────────────

  (describe "elements with HTML id attribute"
    {:context [with-playwright with-browser with-page]}

    (it "id-based element keeps ref when surrounding content changes entirely"
      (page/set-content! *page*
        "<button id='main-cta'>Click Me</button><p>Some text</p>")
      (let [before  (sut/capture-snapshot *page*)
            btn-ref (find-ref-by before "button" "Click Me")]
        ;; Replace surrounding content, add new elements
        (page/evaluate *page*
          "(() => { document.querySelector('p').remove(); document.body.appendChild(Object.assign(document.createElement('h2'), {textContent:'New Heading'})); document.body.appendChild(Object.assign(document.createElement('nav'), {innerHTML:'<a href=\"/\">Home</a>'})); })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "button" (:role (get (:refs after) btn-ref))))
          (expect (= "Click Me" (:name (get (:refs after) btn-ref)))))))

    (it "two elements with different ids get distinct stable refs"
      (page/set-content! *page*
        "<button id='save-btn'>Save</button><button id='cancel-btn'>Cancel</button>")
      (let [snap       (sut/capture-snapshot *page*)
            save-ref   (find-ref-by snap "button" "Save")
            cancel-ref (find-ref-by snap "button" "Cancel")]
        (expect (some? save-ref))
        (expect (some? cancel-ref))
        (expect (not= save-ref cancel-ref)))))

  ;; ── D. Identity changes (refs SHOULD change) ─────────────────────────────

  (describe "identity-changing mutations"
    {:context [with-playwright with-browser with-page]}

    (it "changing button text produces a different ref"
      (page/set-content! *page* "<button>Original</button>")
      (let [before  (sut/capture-snapshot *page*)
            old-ref (find-ref-by before "button" "Original")]
        (page/evaluate *page* "document.querySelector('button').textContent = 'Changed'")
        (let [after   (sut/capture-snapshot *page*)
              new-ref (find-ref-by after "button" "Changed")]
          ;; Old ref no longer maps to anything; new ref exists
          (expect (nil? (get (:refs after) old-ref)))
          (expect (some? new-ref))
          (expect (not= old-ref new-ref)))))

    (it "changing aria-label produces a different ref"
      (page/set-content! *page* "<button aria-label='Close dialog'>X</button>")
      (let [before  (sut/capture-snapshot *page*)
            old-ref (find-ref-by before "button" "Close dialog")]
        (page/evaluate *page*
          "document.querySelector('button').setAttribute('aria-label', 'Dismiss')")
        (let [after   (sut/capture-snapshot *page*)
              new-ref (find-ref-by after "button" "Dismiss")]
          (expect (nil? (get (:refs after) old-ref)))
          (expect (some? new-ref))
          (expect (not= old-ref new-ref))))))

  ;; ── E. Duplicate disambiguation ──────────────────────────────────────────

  (describe "elements with identical role+name+tag"
    {:context [with-playwright with-browser with-page]}

    (it "two buttons with same text get distinct refs"
      (page/set-content! *page* "<button>Submit</button><button>Submit</button>")
      (let [snap        (sut/capture-snapshot *page*)
            submit-refs (filterv
                          (fn [[_ info]]
                            (and (= "button" (:role info))
                              (= "Submit" (:name info))))
                          (:refs snap))]
        ;; Both exist as separate refs
        (expect (= 2 (count submit-refs)))
        ;; With different ref IDs
        (expect (not= (ffirst submit-refs) (first (second submit-refs))))))

    (it "adding unrelated element between duplicates preserves both refs"
      (page/set-content! *page*
        "<button>Submit</button><button>Submit</button>")
      (let [before             (sut/capture-snapshot *page*)
            before-submit-refs (into #{}
                                 (keep (fn [[ref-id info]]
                                         (when (and (= "button" (:role info))
                                                 (= "Submit" (:name info)))
                                           ref-id)))
                                 (:refs before))]
        ;; Insert an unrelated heading between the two buttons
        (page/evaluate *page*
          "(() => { const btns = document.querySelectorAll('button'); const h = document.createElement('h2'); h.textContent = 'Divider'; btns[0].after(h); })()")
        (let [after             (sut/capture-snapshot *page*)
              after-submit-refs (into #{}
                                  (keep (fn [[ref-id info]]
                                          (when (and (= "button" (:role info))
                                                  (= "Submit" (:name info)))
                                            ref-id)))
                                  (:refs after))]
          ;; Same two Submit refs survive
          (expect (= before-submit-refs after-submit-refs))))))

  ;; ── F. Visibility toggle ─────────────────────────────────────────────────

  (describe "visibility changes"
    {:context [with-playwright with-browser with-page]}

    (it "hiding then showing element restores the same ref"
      (page/set-content! *page*
        "<button id='toggle-me'>Peek</button><a href='/x'>Anchor</a>")
      (let [before   (sut/capture-snapshot *page*)
            btn-ref  (find-ref-by before "button" "Peek")
            link-ref (find-ref-by before "link" "Anchor")]
        ;; Hide the button
        (page/evaluate *page*
          "document.getElementById('toggle-me').style.display = 'none'")
        (let [hidden-snap (sut/capture-snapshot *page*)]
          ;; Button ref gone while hidden
          (expect (nil? (get (:refs hidden-snap) btn-ref)))
          ;; Link ref unchanged
          (expect (= "Anchor" (:name (get (:refs hidden-snap) link-ref)))))
        ;; Show it again
        (page/evaluate *page*
          "document.getElementById('toggle-me').style.display = ''")
        (let [after (sut/capture-snapshot *page*)]
          ;; Same ref returns
          (expect (= "Peek" (:name (get (:refs after) btn-ref))))))))

  ;; ── G. Complex compound scenarios ────────────────────────────────────────

  (describe "compound mutations"
    {:context [with-playwright with-browser with-page]}

    (it "simultaneous add + remove + restyle leaves target refs stable"
      (page/set-content! *page*
        "<h1>Dashboard</h1><button>Save</button><button>Cancel</button><a href='/help'>Help</a>")
      (let [before     (sut/capture-snapshot *page*)
            save-ref   (find-ref-by before "button" "Save")
            cancel-ref (find-ref-by before "button" "Cancel")
            help-ref   (find-ref-by before "link" "Help")]
        ;; Remove h1, add a new nav, restyle existing button — all at once
        (page/evaluate *page*
          "(() => { document.querySelector('h1').remove(); document.body.prepend(Object.assign(document.createElement('nav'), {innerHTML:'<a href=\"/home\">Home</a>'})); document.querySelector('button').className = 'btn-lg'; })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "Save" (:name (get (:refs after) save-ref))))
          (expect (= "Cancel" (:name (get (:refs after) cancel-ref))))
          (expect (= "Help" (:name (get (:refs after) help-ref)))))))

    (it "deeply nested element keeps ref when outer nesting changes"
      (page/set-content! *page*
        "<div><div><div><button id='deep'>Deep Button</button></div></div></div><p>Sibling</p>")
      (let [before   (sut/capture-snapshot *page*)
            deep-ref (find-ref-by before "button" "Deep Button")]
        ;; Unwrap one nesting level
        (page/evaluate *page*
          "(() => { const btn = document.getElementById('deep'); const inner = btn.parentElement; const outer = inner.parentElement; outer.replaceChild(btn, inner); })()")
        (let [after (sut/capture-snapshot *page*)]
          (expect (= "Deep Button" (:name (get (:refs after) deep-ref))))))))

  ;; ── H. Rich form with mixed element types ────────────────────────────────

  (describe "realistic form stability"
    {:context [with-playwright with-browser with-page]}

    (it "form elements keep refs after injecting validation messages"
      (page/set-content! *page*
        (str "<form>"
          "<label for='email'>Email</label>"
          "<input id='email' type='email' placeholder='you@example.org'/>"
          "<label for='pass'>Password</label>"
          "<input id='pass' type='password' placeholder='********'/>"
          "<button type='submit'>Log In</button>"
          "</form>"))
      (let [before     (sut/capture-snapshot *page*)
            email-ref  (find-ref-by before "textbox" "Email")
            pass-ref   (find-ref-by before "textbox" "Password")
            submit-ref (find-ref-by before "button" "Log In")]
        ;; Inject validation error messages after each input
        (page/evaluate *page*
          "document.querySelectorAll('input').forEach(inp => { const err = document.createElement('span'); err.className = 'error'; err.textContent = 'Required'; err.style.color = 'red'; inp.after(err); })")
        (let [after (sut/capture-snapshot *page*)]
          (expect (some? (get (:refs after) email-ref)))
          (expect (some? (get (:refs after) pass-ref)))
          (expect (= "Log In" (:name (get (:refs after) submit-ref)))))))))

;; =============================================================================
;; Integration Tests — TASK-013: URL annotation in tree text
;; =============================================================================

(defdescribe url-annotation-test
  "Tests for AC-1..AC-4: link URLs shown in snapshot tree text"

  (describe "link URL annotation"
    {:context [with-playwright with-browser with-page]}

    (it "links with href show [url=...] annotation in tree"
      (page/set-content! *page*
        "<a href='https://example.org/about'>About</a>")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "[url=https://example.org/about]"))))

    (it "links without href do not show [url=...] annotation"
      (page/set-content! *page*
        "<a>No Link</a>")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (not (str/includes? (:tree snap) "[url=")))))

    (it "relative URLs are resolved to absolute"
      (page/navigate *page* "https://example.org")
      (page/evaluate *page*
        "document.body.innerHTML = '<a href=\"/relative\">Relative</a>'")
      (let [snap (sut/capture-snapshot *page*)]
        (expect (str/includes? (:tree snap) "[url=https://example.org/relative]"))))

    (it "URL appears after ref annotation"
      (page/set-content! *page*
        "<a href='https://example.org'>Link</a>")
      (let [snap (sut/capture-snapshot *page*)
            tree (:tree snap)]
        ;; Pattern: [@eXXXXX] [url=...]
        (expect (re-find #"\[@e[a-z0-9]+\] \[url=https://example.org/?\]" tree))))))

;; =============================================================================
;; Integration Tests — TASK-013: Structured refs map
;; =============================================================================

(defdescribe structured-refs-test
  "Tests for AC-5..AC-9: structured refs map in snapshot output"

  (describe "refs include metadata fields"
    {:context [with-playwright with-browser with-page]}

    (it "link refs include url field"
      (page/set-content! *page*
        "<a href='https://example.org'>Example</a>")
      (let [snap (sut/capture-snapshot *page*)
            link-ref (some (fn [[_ info]] (when (= "link" (:role info)) info))
                       (:refs snap))]
        (expect (= "https://example.org/" (:url link-ref)))))

    (it "heading refs include level field"
      (page/set-content! *page* "<h2>Subtitle</h2>")
      (let [snap (sut/capture-snapshot *page*)
            h-ref (some (fn [[_ info]] (when (= "heading" (:role info)) info))
                    (:refs snap))]
        (expect (= 2 (:level h-ref)))))

    (it "input refs include type field"
      (page/set-content! *page*
        "<input type='email' placeholder='Email'/>")
      (let [snap (sut/capture-snapshot *page*)
            input-ref (some (fn [[_ info]] (when (= "textbox" (:role info)) info))
                        (:refs snap))]
        (expect (= "email" (:type input-ref)))))

    (it "checkbox refs include checked field"
      (page/set-content! *page*
        "<input type='checkbox' aria-label='Accept'/><input type='checkbox' aria-label='Decline' checked/>")
      (let [snap (sut/capture-snapshot *page*)
            accept (some (fn [[_ info]] (when (= "Accept" (:name info)) info))
                     (:refs snap))
            decline (some (fn [[_ info]] (when (= "Decline" (:name info)) info))
                      (:refs snap))]
        (expect (= false (:checked accept)))
        (expect (= true (:checked decline)))))

    (it "refs always include role field"
      (page/set-content! *page*
        "<button>Btn</button><a href='/'>Link</a><h1>H</h1>")
      (let [snap (sut/capture-snapshot *page*)]
        (doseq [[_ info] (:refs snap)]
          (expect (some? (:role info))))))))
