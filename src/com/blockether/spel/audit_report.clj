(ns com.blockether.spel.audit-report
  "Generates comprehensive, business-oriented HTML audit reports.

   Structure mirrors professional website audits:
   1. Executive Summary (score, top problem, top opportunity, top 3 recs)
   2. Overall Health Scores (per-category scoring with methodology)
   3. Per-section: Why It Matters | Findings | Recommendations | Details
   4. Prioritized Action Plan (phased timeline)
   5. Impact Analysis (estimated improvements)

   Every recommendation is auto-generated from audit data with priority,
   effort, and impact. No truncation, no emojis, Blockether palette."
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util Base64]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Utilities
;; =============================================================================

(defn- esc ^String [s]
  (if (nil? s) ""
    (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;"))))

(defn- b64 ^String [^bytes bs]
  (str "data:image/png;base64," (.encodeToString (Base64/getEncoder) bs)))

(defn- now-str ^String []
  (.format (LocalDateTime/now) (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))

(defn- bbox-s [bbox]
  (when bbox
    (str (get bbox :x 0) "," (get bbox :y 0) " "
      (get bbox :width 0) "x" (get bbox :height 0))))

(defn- collapsible
  "Wrap content in a collapsible <details> block."
  [summary-text content]
  (str "<details class=\"collapsible\"><summary>" (esc summary-text)
    "</summary><div class=\"collapsible__body\">" content "</div></details>"))

;; =============================================================================
;; CSS
;; =============================================================================

(def ^:private base-css
  "CSS from the spel-report design system (loaded from resource at compile time)."
  (slurp (io/resource "com/blockether/spel/audit-report.css")))

(def ^:private audit-css
  "Minimal audit-specific additions. Everything else comes from the base design system."
  "
/* Dots for link status */
.dot{display:inline-block;width:10px;height:10px;border-radius:999px;vertical-align:middle;margin-right:4px;box-shadow:inset 0 0 0 1px rgba(255,255,255,0.3)}
.d-ok{background:var(--mc-accent-green)}.d-fail{background:var(--mc-accent-red)}.d-warn{background:var(--mc-accent-yellow)}.d-mute{background:var(--mc-text-secondary)}
/* Color swatches */
.sw{display:inline-block;width:22px;height:22px;border:1px solid var(--mc-border-strong);vertical-align:middle;margin-right:4px;border-radius:6px}
.swl{width:32px;height:32px;border-radius:8px;margin:2px;transition:transform .15s}.swl:hover{transform:scale(1.15);z-index:1}
.swr{display:flex;flex-wrap:wrap;gap:6px;margin:.5rem 0}
/* Heading tree */
.htr{padding:4px 0;font-size:13px;display:flex;gap:.5rem;align-items:baseline}
.htl{font-weight:800;color:var(--mc-accent);min-width:2rem;font-family:'IBM Plex Mono',ui-monospace,monospace}
/* Error */
.err{background:rgba(196,69,54,0.08);color:var(--mc-accent-red);padding:.75rem 1rem;border-radius:var(--mc-radius-md);border:1px solid rgba(196,69,54,0.2);margin:.5rem 0}
/* Rationale — agent-block style from template */
.rationale{border-left:3px solid var(--mc-accent);padding:.75rem 1rem;margin:.75rem 0;background:var(--mc-bg-secondary);border-radius:0 10px 10px 0;font-size:.84rem;color:var(--mc-text-secondary)}
.rationale p{margin:0}
/* Verdict */
.verdict-banner{font-family:'Manrope','Atkinson Hyperlegible',sans-serif;font-size:1.05rem;font-weight:800;margin-bottom:.5rem;letter-spacing:-.02em}
.verdict-pass{color:var(--mc-accent-green)}.verdict-fail{color:var(--mc-accent-red)}.verdict-warn{color:var(--mc-accent-yellow)}
/* Contrast sample inline */
.contrast-sample{display:inline-block;padding:2px 6px;border-radius:6px;font-size:12px;border:1px solid var(--mc-border);font-family:'IBM Plex Mono',ui-monospace,monospace}
/* Screenshot */
.audit-screenshot{border:2px solid var(--mc-accent);border-radius:var(--mc-radius-lg);overflow:hidden;margin:1.5rem 0 2.5rem;box-shadow:var(--mc-shadow)}
.audit-screenshot img{width:100%;display:block}
.audit-screenshot-cap{background:linear-gradient(90deg,var(--mc-accent),var(--mc-accent-cyan));color:#fff;padding:.5rem 1rem;font-size:.72rem;font-weight:600;text-transform:uppercase;letter-spacing:.1em;font-family:'IBM Plex Mono',ui-monospace,monospace}
/* Phase card for action plan */
.phase-card{background:var(--mc-bg-elevated);border:1px solid var(--mc-border);border-radius:var(--mc-radius-md);padding:1.1rem 1.3rem;margin:.75rem 0;box-shadow:var(--mc-shadow-soft)}
.phase-card h4{margin-top:0;font-family:'Manrope',sans-serif}
")

;; =============================================================================
;; Scoring Engine
;; =============================================================================

(defn- score-structure [data]
  (if (or (nil? data) (:error data)) -1
    (let [types (set (map :type (:sections data)))]
      (long (min 100 (+ (if (contains? types "main") 30 0)
                       (if (contains? types "nav") 25 0)
                       (if (contains? types "header") 20 0)
                       (if (contains? types "footer") 15 0)
                       (if (> (count types) 4) 10 0)))))))

(defn- score-contrast [data]
  (if (or (nil? data) (:error data)) -1
    (let [t (long (or (:total-elements data) 0))
          p (long (or (:passing data) 0))]
      (if (pos? t) (Math/round (* 100.0 (/ (double p) (double t)))) 100))))

(defn- score-layout [data]
  (if (or (nil? data) (:error data)) -1
    (if (:clean? data) 100
      (long (max 0 (- 100 (long (reduce + 0
                                  (map (fn [i] (case (:severity i) :high 20 :medium 12 :low 5 10))
                                    (:issues data))))))))))

(defn- score-headings [data]
  (if (or (nil? data) (:error data)) -1
    (if (:valid? data) 100
      (long (max 0 (- 100 (* 20 (long (count (:issues data))))))))))

(defn- score-links [data]
  (if (or (nil? data) (:error data)) -1
    (let [t (long (or (:total-links data) 1))
          b (long (or (:broken data) 0))]
      (if (pos? t) (Math/round (* 100.0 (/ (double (- t b)) (double t)))) 100))))

(defn- score-fonts [data]
  (if (or (nil? data) (:error data)) -1
    (long (max 0 (- 100 (* 20 (long (count (:issues data)))))))))

(defn- score-colors [data]
  (if (or (nil? data) (:error data)) -1
    (let [nd (long (count (:near-duplicates data)))
          tc (long (or (:total-colors data) 0))]
      (long (max 0 (- 100 (* 10 (long nd)) (if (> tc 30) 20 0)))))))

(defn- compute-scores [audit-data]
  (let [scores {:structure (score-structure (:structure audit-data))
                :contrast  (score-contrast  (:contrast audit-data))
                :layout    (score-layout    (:layout audit-data))
                :headings  (score-headings  (:headings audit-data))
                :links     (score-links     (:links audit-data))
                :fonts     (score-fonts     (:fonts audit-data))
                :colors    (score-colors    (:colors audit-data))}
        weights {:contrast 0.25 :links 0.20 :layout 0.15 :headings 0.15
                 :structure 0.10 :fonts 0.08 :colors 0.07}
        valid (filterv (fn [[k _]] (>= (long (get scores k -1)) 0)) weights)
        total-w (double (reduce + 0.0 (map second valid)))
        overall (if (pos? total-w)
                  (Math/round (/ ^double (reduce + 0.0
                                           (map (fn [[k w]] (* (double w) (double (get scores k 0)))) valid))
                                ^double total-w))
                  -1)]
    (assoc scores :overall overall)))

(defn- score-color ^String [^long s]
  (cond (< s 0) "var(--mc-text-secondary)" (>= s 80) "var(--mc-accent-green)" (>= s 50) "var(--mc-accent-yellow)" :else "var(--mc-accent-red)"))

(defn- score-class ^String [^long s]
  (cond (< s 0) "sn-w" (>= s 80) "sn-p" (>= s 50) "sn-w" :else "sn-f"))

;; =============================================================================
;; Recommendation Engine
;; =============================================================================

(defn- recs-structure [data]
  (when (and data (not (:error data)))
    (let [types (set (map :type (:sections data)))]
      (cond-> []
        (empty? (:sections data))
        (conj {:p :p0 :cat "Structure" :effort "HIGH" :impact "HIGH"
               :issue "No landmark sections detected"
               :fix "Add semantic HTML5 landmarks (<main>, <nav>, <header>, <footer>) or equivalent ARIA roles. Without landmarks, assistive technology users cannot navigate the page efficiently."})
        (and (seq (:sections data)) (not (contains? types "main")))
        (conj {:p :p1 :cat "Structure" :effort "LOW" :impact "MEDIUM"
               :issue "No <main> landmark — screen readers cannot skip to primary content"
               :fix "Wrap the primary content area in a <main> element. This is a WCAG 2.4.1 requirement for bypass blocks."})
        (not (contains? types "nav"))
        (conj {:p :p1 :cat "Structure" :effort "LOW" :impact "MEDIUM"
               :issue "No <nav> landmark — navigation region not identifiable"
               :fix "Wrap site navigation links in a <nav> element so assistive technology can announce and skip navigation."})))))

(defn- recs-headings [data]
  (when (and data (not (:error data)))
    (mapv (fn [issue]
            {:p :p1 :cat "Headings" :effort "LOW" :impact "MEDIUM"
             :issue (:description issue)
             :fix (case (name (or (:type issue) :unknown))
                    "skipped-level" "Insert the intermediate heading level. Screen readers announce levels — skips confuse navigation and hurt SEO structure signals."
                    "multiple-h1" "Keep exactly one H1 per page. Multiple H1s dilute the primary topic signal for search engines."
                    "missing-h1" "Add an H1 element. Every page must have one — it defines the page topic for both users and crawlers."
                    (str "Address: " (:description issue)))})
      (:issues data))))

(defn- recs-contrast [data]
  (when (and data (not (:error data)))
    (let [failures (filterv #(not (:wcag-aa %)) (:elements data))
          groups (group-by (fn [e] [(:color e) (:bg-color e)]) failures)
          sorted (sort-by #(- (long (count (val %)))) groups)]
      (mapv (fn [[[fg bg] els]]
              (let [n (long (count els))
                    ratio (double (:contrast-ratio (first els)))]
                {:p :p0 :cat "Contrast" :effort "LOW" :impact "HIGH"
                 :issue (str n " element(s) fail AA — " (esc fg) " on " (esc bg) " (ratio " (format "%.2f" ratio) ":1, need 4.5:1)")
                 :fix (str "Darken the text color or lighten the background to achieve at least 4.5:1. "
                        "Affected: " (esc (str/join ", " (take 3 (map :selector els))))
                        (when (> n 3) (str " + " (- n 3) " more")))
                 :detail (str "WCAG 2.1 SC 1.4.3 (AA). Failing elements include: "
                           (esc (str/join ", " (take 5 (map #(str "\"" (:text %) "\"") els)))))}))
        (take 10 sorted)))))

(defn- recs-layout [data]
  (when (and data (not (:error data)))
    (mapv (fn [issue]
            {:p (case (:severity issue) :high :p0 :medium :p1 :p2)
             :cat "Layout" :effort "MEDIUM"
             :impact (case (:severity issue) :high "HIGH" :medium "MEDIUM" "LOW")
             :issue (:description issue)
             :fix (case (name (or (:type issue) :unknown))
                    "horizontal-overflow" "Set max-width:100% or overflow:hidden on the overflowing element. Horizontal scrollbars on mobile cause high bounce rates."
                    "overlap" "Adjust z-index, position, or margin to prevent overlap. Overlapping elements hide content and break click targets."
                    "offscreen" "Review positioning — this element is outside the visible viewport and inaccessible to users."
                    "flex-overflow" "Add flex-shrink:1 or min-width:0 to prevent flex children from exceeding container bounds."
                    (:description issue))})
      (:issues data))))

(defn- recs-fonts [data]
  (when (and data (not (:error data)))
    (mapv (fn [issue]
            {:p :p2 :cat "Typography" :effort "MEDIUM" :impact "LOW"
             :issue (:description issue)
             :fix (case (name (or (:type issue) :unknown))
                    "too-many-sizes" "Adopt a type scale (e.g., 12/14/16/20/24/32px). Each extra size increases cognitive load and CSS complexity."
                    "too-many-families" "Limit to 2-3 font families. Each extra family adds 50-200KB of font files to download."
                    (:description issue))})
      (:issues data))))

(defn- recs-links [data]
  (when (and data (not (:error data)))
    (let [broken (filterv #(= :broken (:type %)) (:links data))
          redirs (filterv #(= :redirect (:type %)) (:links data))]
      (concat
        (mapv (fn [l]
                {:p :p0 :cat "Links" :effort "LOW" :impact "MEDIUM"
                 :issue (str "Broken: " (esc (:href l)) " (" (:status l) " " (esc (:status-text l)) ")")
                 :fix (str "Remove or update this dead link. Broken links harm SEO rankings (Google crawl budget waste) and erode user trust."
                        (when (seq (:element-text l)) (str " Link text: \"" (esc (:element-text l)) "\"")))})
          broken)
        (when (seq redirs)
          [{:p :p2 :cat "Links" :effort "LOW" :impact "LOW"
            :issue (str (count redirs) " link(s) resolve via redirect — adds 100-300ms latency per hop")
            :fix (str "Update href to the final destination URL. Redirected links: "
                   (esc (str/join ", " (take 5 (map :href redirs)))))}])))))

(defn- recs-colors [data]
  (when (and data (not (:error data)))
    (let [nd (:near-duplicates data)
          tc (long (or (:total-colors data) 0))]
      (cond-> []
        (seq nd)
        (into (mapv (fn [pair]
                      {:p :p2 :cat "Colors" :effort "LOW" :impact "LOW"
                       :issue (str "Near-duplicate: " (esc (:color1 pair)) " vs " (esc (:color2 pair))
                                " (delta " (format "%.1f" (double (:delta pair))) ")")
                       :fix "Consolidate to a single color. The visual difference is imperceptible — this is pure CSS waste."})
                (take 5 nd)))
        (> tc 30)
        (conj {:p :p2 :cat "Colors" :effort "HIGH" :impact "LOW"
               :issue (str tc " unique colors — no design system discipline")
               :fix "Define a brand color palette of 8-12 colors and enforce it. Excess colors create visual noise and maintenance burden."})))))

(defn- all-recs [audit-data]
  (vec (concat
         (recs-contrast  (:contrast audit-data))
         (recs-links     (:links audit-data))
         (recs-layout    (:layout audit-data))
         (recs-headings  (:headings audit-data))
         (recs-structure (:structure audit-data))
         (recs-fonts     (:fonts audit-data))
         (recs-colors    (:colors audit-data)))))

;; =============================================================================
;; Render: Recommendation card
;; =============================================================================

;; =============================================================================
;; Design system helpers
;; =============================================================================

(defn- render-rec [r]
  (let [pn (case (:p r) :p0 "P0" :p1 "P1" "P2")
        pc (case (:p r) :p0 "rec-priority-critical" :p1 "rec-priority-high" "rec-priority-medium")
        pl (case (:p r) :p0 "critical" :p1 "high" "medium")
        pl-label (case (:p r) :p0 "FIX NOW" :p1 "FIX SOON" "IMPROVE LATER")]
    (str "<div class=\"recommendation\">"
      "<div class=\"rec-priority " pc "\">" pn "</div>"
      "<div class=\"rec-body\">"
      "<div class=\"rec-title\">" (esc (:cat r)) ": " (esc (:issue r)) "</div>"
      "<div class=\"rec-desc\">" (esc (:fix r)) "</div>"
      "<div class=\"rec-tags\">"
      "<span class=\"pill pill-" pl "\">" pl-label "</span> "
      "<span class=\"tag tag-category\">" (esc (:effort r)) " effort</span> "
      "<span class=\"tag tag-category\">" (esc (:impact r)) " impact</span>"
      "</div></div></div>")))

(defn- summary-card [value label card-type]
  (str "<div class=\"summary-card summary-card-" card-type "\">"
    "<div class=\"summary-count\">" (esc (str value)) "</div>"
    "<div class=\"summary-label\">" (esc label) "</div></div>"))

(defn- overview-pill [label value]
  (str "<div class=\"overview-pill\"><div class=\"overview-label\">" (esc label)
    "</div><div class=\"overview-value\">" (esc (str value)) "</div></div>"))

(defn- severity-bar [label count-val color max-val]
  (let [pct (if (pos? (long max-val)) (Math/round (* 100.0 (/ (double count-val) (double max-val)))) 0)]
    (str "<div class=\"severity-row\">"
      "<span class=\"severity-dot dot-" color "\"></span>"
      "<span class=\"severity-label\">" (esc label) "</span>"
      "<div class=\"severity-track\"><div class=\"severity-fill severity-fill-" color
      "\" style=\"width:" pct "%\"></div></div>"
      "<span class=\"severity-value\">" count-val "</span></div>")))

(defn- card [& content] (str "<div class=\"card\">" (apply str content) "</div>"))

(defn- rationale [text]
  (str "<div class=\"rationale\"><p>" text "</p></div>"))

(defn- verdict [css-class text]
  (str "<div class=\"verdict-banner " css-class "\">" text "</div>"))

;; =============================================================================
;; Executive Summary
;; =============================================================================

(defn- render-exec [scores recs url]
  (let [overall (long (:overall scores))
        p0s (filterv #(= :p0 (:p %)) recs)
        p1s (filterv #(= :p1 (:p %)) recs)
        p2s (filterv #(= :p2 (:p %)) recs)
        total-recs (long (count recs))
        top3 (take 3 recs)]
    (str
      "<h2>Executive Summary</h2>"
      ;; Summary cards grid
      "<div class=\"summary-grid\">"
      "<div class=\"summary-card summary-card-highlight\">"
      "<div class=\"summary-count\" style=\"color:var(--mc-accent)\">" overall "</div>"
      "<div class=\"summary-label\">Overall Score</div></div>"
      (summary-card (count p0s) "Fix Now" "critical")
      (summary-card (count p1s) "Fix Soon" "high")
      (summary-card (count p2s) "Improve Later" "medium")
      (summary-card total-recs "Total Findings" "total")
      "</div>"
      ;; Severity distribution chart + coherence ring
      "<div class=\"summary-visuals\">"
      ;; Left: severity chart
      "<div class=\"chart-card\"><div class=\"chart-title\">Severity Distribution</div>"
      "<div class=\"severity-chart\"><div class=\"severity-bars\">"
      (severity-bar "Fix Now" (count p0s) "critical" (max 1 total-recs))
      (severity-bar "Fix Soon" (count p1s) "high" (max 1 total-recs))
      (severity-bar "Improve Later" (count p2s) "medium" (max 1 total-recs))
      "</div></div></div>"
      ;; Right: coherence ring
      "<div class=\"chart-card\"><div class=\"chart-title\">Overall Health</div>"
      "<div class=\"coherence-hero\">"
      "<div class=\"coherence-ring\" style=\"--value:" overall "\">"
      "<div class=\"coherence-value\">" overall "<span>of 100</span></div></div>"
      "<div class=\"coherence-copy\"><h3>"
      (cond (>= overall 80) "Good Health" (>= overall 50) "Needs Work" :else "Critical") "</h3>"
      "<p>Audited <code>" (esc url) "</code> across structure, accessibility, layout, typography, links, and color palette.</p>"
      "</div></div></div></div>"
      ;; Top problem
      (when (seq p0s)
        (card "<h4>Most Urgent Issue</h4>"
          "<p><strong>" (esc (:issue (first p0s))) "</strong></p>"
          "<p style=\"font-size:.84rem;color:var(--mc-text-secondary)\">" (esc (:fix (first p0s))) "</p>"))
      ;; Top recs
      (when (seq top3)
        (str "<h3>Top 3 Things to Fix</h3>"
          (str/join (map render-rec top3)))))))

;; =============================================================================
;; Health Scores
;; =============================================================================

(defn- render-scores [scores]
  (let [cats [[:contrast "Text Readability" 0.25] [:links "Working Links" 0.20]
              [:layout "Page Layout" 0.15] [:headings "Heading Structure" 0.15]
              [:structure "Page Landmarks" 0.10] [:fonts "Font Consistency" 0.08]
              [:colors "Color Palette" 0.07]]]
    (str
      "<h2 id=\"s-scores\">Health Scores</h2>"
      ;; How to read guide
      (card "<div class=\"guide-grid\">"
        "<div class=\"guide-card\"><div class=\"guide-title\">How to Read Scores</div>"
        "<p class=\"guide-text\">Green (80–100): No action needed. Yellow (50–79): Review and fix when possible. Red (below 50): Fix these first — they affect real users or carry legal risk.</p></div>"
        "<div class=\"guide-card\"><div class=\"guide-title\">What Gets Checked</div>"
        "<p class=\"guide-text\">Text readability (25%), working links (20%), page layout (15%), heading structure (15%), page landmarks (10%), font consistency (8%), color palette (7%).</p></div>"
        "<div class=\"guide-card\"><div class=\"guide-title\">How the Overall Score Works</div>"
        "<p class=\"guide-text\">Each category is scored 0–100, then combined using the weights above. Categories that affect accessibility and legal compliance are weighted highest.</p></div>"
        "</div>")
      ;; Score bars
      (card
        (str/join
          (map (fn [[k label _weight]]
                 (let [s (long (get scores k -1))
                       col (score-color s)
                       fill-class (cond (>= s 80) "score-fill-high" (>= s 50) "score-fill-medium" :else "score-fill-low")]
                   (str "<div class=\"score-bar\">"
                     "<div class=\"score-label\">" (esc label) "</div>"
                     "<div class=\"score-track\"><div class=\"" fill-class "\" style=\"width:" (if (< s 0) 0 s) "%;height:100%;border-radius:4px\"></div></div>"
                     "<div class=\"score-value\" style=\"color:" col "\">" (if (< s 0) "N/A" (str s)) "</div></div>")))
            cats)))
      ;; Score table in collapsible
      (collapsible "Score Breakdown Table"
        (str "<table><thead><tr><th>Category</th><th>Score</th><th>Weight</th><th>Weighted</th></tr></thead><tbody>"
          (str/join
            (map (fn [[k label weight]]
                   (let [s (long (get scores k -1))
                         w (* (double weight) (double (if (< s 0) 0 s)))]
                     (str "<tr><td>" (esc label) "</td>"
                       "<td style=\"color:" (score-color s) ";font-weight:700\">"
                       (if (< s 0) "N/A" (str s)) "/100</td>"
                       "<td>" (Math/round (* 100.0 (double weight))) "%</td>"
                       "<td>" (format "%.1f" w) "</td></tr>")))
              cats))
          "<tr class=\"summary-row\"><td>OVERALL</td>"
          "<td style=\"color:" (score-color (long (:overall scores))) "\">"
          (:overall scores) "/100</td><td>100%</td><td>" (:overall scores) ".0</td></tr>"
          "</tbody></table>")))))

;; =============================================================================
;; Contrast detail table helper
;; =============================================================================

(defn- contrast-table [elements]
  (str "<table><thead><tr>"
    "<th>#</th><th>Selector</th><th>Text</th><th>FG</th><th>BG</th><th>Sample</th>"
    "<th>Ratio</th><th>Size</th><th>Large?</th><th>AA</th><th>AAA</th>"
    "</tr></thead><tbody>"
    (str/join
      (map-indexed
        (fn [i e]
          (str "<tr><td>" (inc (long i)) "</td>"
            "<td><code>" (esc (:selector e)) "</code></td>"
            "<td>" (esc (:text e)) "</td>"
            "<td><code>" (esc (:color e)) "</code></td>"
            "<td><code>" (esc (:bg-color e)) "</code></td>"
            "<td><span class=\"contrast-sample\" style=\"color:" (esc (:color e))
            ";background:" (esc (:bg-color e)) "\">Aa</span></td>"
            "<td><strong>" (format "%.2f" (double (:contrast-ratio e))) ":1</strong></td>"
            "<td>" (format "%.1f" (double (:font-size e))) "px</td>"
            "<td>" (if (:large-text? e) "YES" "no") "</td>"
            "<td><span class=\"pill pill-" (if (:wcag-aa e) "low\">PASS" "critical\">FAIL") "</span></td>"
            "<td><span class=\"pill pill-" (if (:wcag-aaa e) "low\">PASS" "critical\">FAIL") "</span></td>"
            "</tr>"))
        elements))
    "</tbody></table>"))

;; =============================================================================
;; Section renderers — cards + collapsibles + overview pills
;; =============================================================================

(defn- sec-structure [data recs]
  (let [sections (:sections data)
        types (set (map :type sections))
        has? (fn [t] (contains? types t))]
    (str
      "<h2 id=\"s-structure\">Page Structure</h2>"
      ;; Overview pills
      "<div class=\"section-overview\">"
      (overview-pill "Sections" (count sections))
      (overview-pill "Distinct Types" (count types))
      (overview-pill "Scroll Height" (str (:scroll-height data) "px"))
      "</div>"
      ;; Rationale
      (rationale "Page landmarks (header, navigation, main content, footer) help screen reader users jump directly to the content they need. Without them, visitors must tab through every element on the page. Missing landmarks also make it harder for search engines to understand your page structure.")
      ;; Findings card
      (card (verdict (cond (empty? sections) "verdict-fail" (and (has? "main") (has? "nav")) "verdict-pass" :else "verdict-warn")
              (cond (empty? sections) "No page sections found" (and (has? "main") (has? "nav")) "Well-structured page" :else "Some sections missing"))
        "<p>" (count sections) " landmarks across " (count types) " types: " (esc (str/join ", " (sort types))) ".</p>"
        "<div class=\"checks-grid\">"
        "<div class=\"check-item " (if (has? "main") "check-pass" "check-fail") "\"><div class=\"check-icon\">" (if (has? "main") "&#10003;" "&#10007;") "</div><div class=\"check-body\"><div class=\"check-name\">Main Content</div><div class=\"check-desc\">&lt;main&gt; landmark</div></div></div>"
        "<div class=\"check-item " (if (has? "nav") "check-pass" "check-fail") "\"><div class=\"check-icon\">" (if (has? "nav") "&#10003;" "&#10007;") "</div><div class=\"check-body\"><div class=\"check-name\">Navigation</div><div class=\"check-desc\">&lt;nav&gt; landmark</div></div></div>"
        "<div class=\"check-item " (if (has? "header") "check-pass" "check-fail") "\"><div class=\"check-icon\">" (if (has? "header") "&#10003;" "&#10007;") "</div><div class=\"check-body\"><div class=\"check-name\">Header</div><div class=\"check-desc\">&lt;header&gt; landmark</div></div></div>"
        "<div class=\"check-item " (if (has? "footer") "check-pass" "check-fail") "\"><div class=\"check-icon\">" (if (has? "footer") "&#10003;" "&#10007;") "</div><div class=\"check-body\"><div class=\"check-name\">Footer</div><div class=\"check-desc\">&lt;footer&gt; landmark</div></div></div>"
        "</div>")
      ;; Recommendations
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      ;; Details in collapsible
      (when (seq sections)
        (collapsible (str "All Sections (" (count sections) ")")
          (str "<table><thead><tr><th>#</th><th>Type</th><th>Source</th><th>Tag</th><th>ID</th><th>Class</th><th>BBox</th><th>Preview</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i s]
                                     (str "<tr><td>" (inc (long i)) "</td><td><strong>" (esc (:type s)) "</strong></td>"
                                       "<td>" (esc (:source s)) "</td><td><code>" (esc (:tag s)) "</code></td>"
                                       "<td><code>" (esc (or (:id s) "-")) "</code></td>"
                                       "<td><code>" (esc (or (:class s) "-")) "</code></td>"
                                       "<td><code>" (esc (bbox-s (:bbox s))) "</code></td>"
                                       "<td>" (esc (:text-preview s)) "</td></tr>"))
                        sections))
            "</tbody></table>"))))))

(defn- sec-headings [data recs]
  (let [headings (:headings data) issues (:issues data) stats (:stats data)
        h1s (filterv #(= 1 (long (:level %))) headings)]
    (str
      "<h2 id=\"s-headings\">Heading Structure</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Total Headings" (count headings))
      (overview-pill "H1 Elements" (count h1s))
      (overview-pill "Issues" (count issues))
      "</div>"
      (rationale "Headings (H1–H6) create an outline of your page, like a table of contents. Screen reader users navigate by heading level to find content quickly. Search engines use headings to understand what your page is about. Each page should have exactly one H1, and levels shouldn’t skip (e.g., jumping from H2 to H4).")
      (card (verdict (if (:valid? data) "verdict-pass" "verdict-warn")
              (if (:valid? data) "Headings look good" "Heading order has problems"))
        "<p>" (count headings) " headings. " (count h1s) " H1" (when (= 1 (count h1s)) (str ": \"" (esc (:text (first h1s))) "\"")) ".</p>"
        (when stats
          (str "<div class=\"coverage-grid\">"
            "<div class=\"coverage-stat\"><span class=\"coverage-label\">H1</span><span class=\"coverage-value\">" (:h1 stats) "</span></div>"
            "<div class=\"coverage-stat\"><span class=\"coverage-label\">H2</span><span class=\"coverage-value\">" (:h2 stats) "</span></div>"
            "<div class=\"coverage-stat\"><span class=\"coverage-label\">H3</span><span class=\"coverage-value\">" (:h3 stats) "</span></div>"
            "<div class=\"coverage-stat\"><span class=\"coverage-label\">H4-H6</span><span class=\"coverage-value\">" (+ (long (:h4 stats)) (long (:h5 stats)) (long (:h6 stats))) "</span></div>"
            "</div>"))
        (when (seq issues) (str (str/join (map (fn [i] (str "<div class=\"check-item check-fail\"><div class=\"check-icon\">&#10007;</div><div class=\"check-body\"><div class=\"check-name\">" (esc (name (or (:type i) :unknown))) "</div><div class=\"check-desc\">" (esc (:description i)) "</div></div></div>")) issues)))))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (seq headings)
        (collapsible (str "Full Heading Tree (" (count headings) ")")
          (str "<div>" (str/join (map (fn [h] (let [lvl (long (:level h))]
                                                (str "<div class=\"htr\" style=\"padding-left:" (* (dec lvl) 1.5) "rem\">"
                                                  "<span class=\"htl\">H" lvl "</span>"
                                                  "<span>" (esc (:text h)) "</span></div>"))) headings)) "</div>"))))))

(defn- sec-contrast [data recs]
  (let [total (long (or (:total-elements data) 0)) passing (long (or (:passing data) 0))
        failing (long (or (:failing data) 0))
        pct (if (pos? total) (Math/round (* 100.0 (/ (double passing) (double total)))) 100)
        elements (:elements data)
        failures (filterv #(not (:wcag-aa %)) elements)
        passes (filterv :wcag-aa elements)
        worst (when (seq failures) (apply min-key #(double (:contrast-ratio %)) failures))]
    (str
      "<h2 id=\"s-contrast\">Text Contrast (WCAG)</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Elements Analyzed" total)
      (overview-pill "Pass Rate" (str pct "%"))
      (overview-pill "Failures" failing)
      "</div>"
      (rationale "Text needs enough contrast against its background to be readable by everyone — including people with low vision or color blindness (1 in 12 men). The minimum standard is 4.5:1 for normal text and 3:1 for large text. Failing to meet this is one of the most common reasons for accessibility lawsuits.")
      (card (verdict (cond (>= pct 100) "verdict-pass" (>= pct 90) "verdict-pass" (>= pct 50) "verdict-warn" :else "verdict-fail")
              (cond (>= pct 100) "All text is readable" (>= pct 90) "Most text is readable" (>= pct 50) "Many readability problems" :else "Serious readability problems"))
            ;; Severity bars for pass/fail
        "<div class=\"severity-bars\" style=\"margin:.75rem 0\">"
        (severity-bar "Passing AA" passing "low" total)
        (severity-bar "Failing AA" failing "critical" total)
        "</div>"
        (when worst
          (str "<p>Worst: <strong>" (format "%.2f" (double (:contrast-ratio worst))) ":1</strong> on <code>"
            (esc (:selector worst)) "</code> (\"" (esc (:text worst)) "\").</p>")))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (seq failures)
        (collapsible (str "Failing Elements (" (count failures) ")") (contrast-table failures)))
      (when (seq passes)
        (collapsible (str "Passing Elements (" (count passes) ")") (contrast-table passes))))))

(defn- sec-layout [data recs]
  (let [issues (:issues data) clean? (:clean? data)
        by-sev (group-by :severity issues)]
    (str
      "<h2 id=\"s-layout\">Layout Integrity</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Viewport" (str (get-in data [:viewport :width]) "x" (get-in data [:viewport :height])))
      (overview-pill "Issues" (count issues))
      (overview-pill "Status" (if clean? "Clean" "Issues Found"))
      "</div>"
      (rationale "Layout problems like horizontal scrollbars or overlapping elements frustrate visitors — especially on mobile, where they cause 38% more people to leave. This check finds elements that overflow the screen, overlap each other, or break on smaller viewports.")
      (card (verdict (cond clean? "verdict-pass" (seq (get by-sev :high)) "verdict-fail" :else "verdict-warn")
              (cond clean? "No layout problems found" (seq (get by-sev :high)) "Layout needs fixing" :else "Minor layout issues"))
        (if clean? "<p>No layout integrity issues detected.</p>"
          (str "<div class=\"severity-bars\" style=\"margin:.75rem 0\">"
            (severity-bar "High" (count (get by-sev :high)) "critical" (max 1 (long (count issues))))
            (severity-bar "Medium" (count (get by-sev :medium)) "high" (max 1 (long (count issues))))
            (severity-bar "Low" (count (get by-sev :low)) "low" (max 1 (long (count issues))))
            "</div>")))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (not clean?)
        (collapsible (str "Layout Issues (" (count issues) ")")
          (str "<table><thead><tr><th>#</th><th>Sev</th><th>Type</th><th>Description</th><th>Elements</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i issue]
                                     (str "<tr><td>" (inc (long i)) "</td>"
                                       "<td><span class=\"pill pill-" (case (name (or (:severity issue) :medium)) "high" "critical" "medium" "high" "low") "\">" (str/upper-case (name (or (:severity issue) :medium))) "</span></td>"
                                       "<td><strong>" (esc (name (:type issue))) "</strong></td>"
                                       "<td>" (esc (:description issue)) "</td><td>"
                                       (when-let [els (seq (:elements issue))]
                                         (str/join "<br>" (map (fn [el] (str "<code>" (esc (:selector el)) "</code> [" (esc (bbox-s (:bbox el))) "]")) els)))
                                       "</td></tr>")) issues))
            "</tbody></table>"))))))

(defn- sec-fonts [data recs]
  (let [fonts (:fonts data) sizes (:sizes data) weights (:weights data)
        issues (:issues data) stats (:stats data)
        fc (long (or (:font-count stats) 0)) sc (long (or (:size-count stats) 0))]
    (str
      "<h2 id=\"s-fonts\">Typography</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Font Families" fc)
      (overview-pill "Size Variants" sc)
      (overview-pill "Weight Variants" (or (:weight-count stats) 0))
      "</div>"
      (rationale "Using too many different fonts or sizes makes your page look inconsistent and load slower — each extra font family adds 50–200KB to download. Sticking to 2–3 font families and a consistent set of sizes improves readability by up to 18%.")
      (card (verdict (cond (empty? issues) "verdict-pass" (> (count issues) 2) "verdict-fail" :else "verdict-warn")
              (cond (empty? issues) "Fonts are consistent" (> (count issues) 2) "Too many font variations" :else "Minor font issues"))
        (when (seq fonts)
          (str "<p>Primary: <strong>" (esc (:family (first fonts))) "</strong> (" (:usage-count (first fonts)) " elements)"
            (when (> (count fonts) 1) (str ". Secondary: <strong>" (esc (:family (second fonts))) "</strong> (" (:usage-count (second fonts)) ")")) ".</p>"))
        (when (seq issues) (str/join (map (fn [i] (str "<div class=\"check-item check-fail\"><div class=\"check-icon\">&#10007;</div><div class=\"check-body\"><div class=\"check-name\">" (esc (name (or (:type i) :unknown))) "</div><div class=\"check-desc\">" (esc (:description i)) "</div></div></div>")) issues))))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (seq fonts)
        (collapsible (str "Font Families (" (count fonts) ")")
          (str "<table><thead><tr><th>#</th><th>Family</th><th>Usage</th><th>Samples</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i f] (str "<tr><td>" (inc (long i)) "</td><td><strong>" (esc (:family f)) "</strong></td><td>" (:usage-count f) "</td><td><code>" (esc (str/join ", " (take 5 (:elements-sample f)))) "</code></td></tr>")) fonts))
            "</tbody></table>")))
      (when (seq sizes)
        (collapsible (str "Font Sizes (" (count sizes) ")")
          (str "<table><thead><tr><th>#</th><th>px</th><th>rem</th><th>Usage</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i s] (str "<tr><td>" (inc (long i)) "</td><td><strong>" (format "%.1f" (double (:px s))) "px</strong></td><td>" (format "%.3f" (double (:rem s))) "</td><td>" (:usage-count s) "</td></tr>")) (sort-by :px sizes)))
            "</tbody></table>")))
      (when (seq weights)
        (collapsible (str "Font Weights (" (count weights) ")")
          (str "<table><thead><tr><th>#</th><th>Weight</th><th>Usage</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i w] (str "<tr><td>" (inc (long i)) "</td><td><strong>" (esc (:weight w)) "</strong></td><td>" (:usage-count w) "</td></tr>")) weights))
            "</tbody></table>"))))))

(defn- sec-links [data recs]
  (let [total (long (or (:total-links data) 0)) ok-n (long (or (:ok data) 0))
        broken (long (or (:broken data) 0)) redirs (long (or (:redirects data) 0))
        timeouts (long (or (:timeouts data) 0))
        links (:links data)
        sorted (sort-by (fn [l] (case (:type l) :broken 0 :redirect 1 :timeout 2 3)) links)]
    (str
      "<h2 id=\"s-links\">Link Health</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Total Links" total)
      (overview-pill "Broken" broken)
      (overview-pill "Redirects" redirs)
      "</div>"
      (rationale "Broken links frustrate visitors and hurt your search rankings — 88% of users won’t return to a site after hitting a dead link. Redirects add 100–300ms of delay per hop. This check tests up to 100 links on the page.")
      (card (verdict (cond (and (zero? broken) (zero? timeouts)) "verdict-pass" (zero? broken) "verdict-warn" :else "verdict-fail")
              (cond (and (zero? broken) (zero? timeouts)) "All links work" (zero? broken) "Some warnings" :else "Broken links found"))
        "<div class=\"severity-bars\" style=\"margin:.75rem 0\">"
        (severity-bar "OK" ok-n "low" total)
        (severity-bar "Broken" broken "critical" total)
        (severity-bar "Redirects" redirs "high" total)
        (severity-bar "Timeouts" timeouts "medium" total)
        "</div>"
        (when (pos? broken)
          (let [bl (filterv #(= :broken (:type %)) links)]
            (str (str/join (map (fn [l] (str "<div class=\"check-item check-fail\"><div class=\"check-icon\">&#10007;</div><div class=\"check-body\"><div class=\"check-name\">" (:status l) " " (esc (:status-text l)) "</div><div class=\"check-desc\"><code>" (esc (:href l)) "</code></div></div></div>")) bl))))))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (seq sorted)
        (collapsible (str "All Links (" (count sorted) ")")
          (str "<table><thead><tr><th>#</th><th></th><th>Status</th><th>Type</th><th>URL</th><th>Text</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i l]
                                     (let [tn (name (or (:type l) :unknown))
                                           dc (case tn "ok" "d-ok" "broken" "d-fail" "redirect" "d-warn" "d-mute")]
                                       (str "<tr><td>" (inc (long i)) "</td><td><span class=\"dot " dc "\"></span></td>"
                                         "<td><code>" (if (:status l) (str (:status l)) "-") "</code></td>"
                                         "<td><span class=\"pill pill-" (case tn "ok" "low" "broken" "critical" "redirect" "high" "medium") "\">" (str/upper-case tn) "</span></td>"
                                         "<td><code style=\"word-break:break-all\">" (esc (:href l)) "</code></td>"
                                         "<td>" (esc (:element-text l)) "</td></tr>"))) sorted))
            "</tbody></table>"))))))

(defn- sec-colors [data recs]
  (let [total (long (or (:total-colors data) 0))
        colors (:colors data) nd (:near-duplicates data) groups (:hue-groups data)
        sorted (sort-by #(- (long (:usage-count %))) colors)]
    (str
      "<h2 id=\"s-colors\">Color Palette</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "Unique Colors" total)
      (overview-pill "Near-Duplicates" (count nd))
      (overview-pill "Hue Groups" (count (filterv (fn [[_ v]] (seq v)) (or groups {}))))
      "</div>"
      (rationale "A clean color palette makes your site look professional and intentional. Near-duplicate colors (e.g., two almost-identical grays) are impossible to tell apart but add unnecessary code. More than 30 unique colors usually means colors are being picked ad-hoc rather than from a defined palette.")
      (card (verdict (cond (> total 30) "verdict-warn" (seq nd) "verdict-warn" :else "verdict-pass")
              (cond (> total 30) "Too many colors" (seq nd) "Nearly identical colors found" :else "Color palette looks good"))
        (when (seq sorted)
          (str "<div class=\"swr\">"
            (str/join (map (fn [c] (str "<div class=\"swl\" style=\"background:" (esc (:hex c)) "\" title=\"" (esc (:hex c)) " (" (:usage-count c) "x)\"></div>")) sorted))
            "</div>"))
        (when (seq nd)
          (str "<h4>Near-Duplicates to Consolidate</h4>"
            (str/join (map (fn [d] (str "<div class=\"check-item check-fail\"><div class=\"check-icon\">&#10007;</div><div class=\"check-body\"><div class=\"check-name\"><span class=\"sw\" style=\"background:" (esc (:color1 d)) "\"></span> " (esc (:color1 d)) " vs <span class=\"sw\" style=\"background:" (esc (:color2 d)) "\"></span> " (esc (:color2 d)) "</div><div class=\"check-desc\">Delta " (format "%.1f" (double (:delta d))) " — imperceptible difference</div></div></div>")) nd)))))
      (when (seq recs) (str "<h3>Recommendations</h3>" (str/join (map render-rec recs))))
      (when (seq sorted)
        (collapsible (str "Color Details (" (count sorted) ")")
          (str "<table><thead><tr><th>#</th><th></th><th>Hex</th><th>RGB</th><th>Usage</th><th>Properties</th><th>Samples</th></tr></thead><tbody>"
            (str/join (map-indexed (fn [i c1]
                                     (str "<tr><td>" (inc (long i)) "</td>"
                                       "<td><span class=\"sw\" style=\"background:" (esc (:hex c1)) "\"></span></td>"
                                       "<td><code>" (esc (:hex c1)) "</code></td><td><code>" (esc (:rgb c1)) "</code></td>"
                                       "<td>" (:usage-count c1) "</td>"
                                       "<td>" (esc (str/join ", " (map name (:properties c1)))) "</td>"
                                       "<td><code>" (esc (str/join ", " (take 5 (:elements-sample c1)))) "</code></td></tr>")) sorted))
            "</tbody></table>")))
      (when groups
        (collapsible "Hue Groups"
          (let [rg (fn [lbl cs] (when (seq cs) (str "<h4>" (esc lbl) " (" (count cs) ")</h4><div class=\"swr\">" (str/join (map (fn [h] (str "<div class=\"swl\" style=\"background:" (esc h) "\" title=\"" (esc h) "\"></div>")) cs)) "</div>")))]
            (str (rg "Reds" (:reds groups)) (rg "Blues" (:blues groups)) (rg "Greens" (:greens groups)) (rg "Neutrals" (:neutrals groups)) (rg "Other" (:other groups)))))))))

;; =============================================================================
;; Recommendations (flat, no timeline)
;; =============================================================================

(defn- render-action-plan [recs]
  (let [p0s (filterv #(= :p0 (:p %)) recs)
        p1s (filterv #(= :p1 (:p %)) recs)
        p2s (filterv #(= :p2 (:p %)) recs)]
    (str
      "<h2 id=\"s-recs\">Action Plan</h2>"
      (rationale "All findings sorted by urgency. <strong>Fix Now</strong> = affects real users or carries legal risk. <strong>Fix Soon</strong> = impacts search rankings and usability. <strong>Improve Later</strong> = design consistency and code quality. Effort estimates: LOW = under 1 hour, MEDIUM = 1–4 hours, HIGH = half a day or more.")
      (if (empty? recs)
        (card (verdict "verdict-pass" "No issues found") "<p>All checks passed — no action needed.</p>")
        (str
          (when (seq p0s)
            (str "<h3 style=\"color:var(--mc-accent-red);margin-top:1.25rem\">Fix Now — Critical</h3>"
              "<p style=\"font-size:.84rem;color:var(--mc-text-secondary);margin-bottom:.75rem\">These affect real users or carry legal risk. Address before anything else.</p>"
              (str/join (map render-rec p0s))))
          (when (seq p1s)
            (str "<h3 style=\"color:var(--mc-accent-yellow);margin-top:1.25rem\">Fix Soon — High Priority</h3>"
              "<p style=\"font-size:.84rem;color:var(--mc-text-secondary);margin-bottom:.75rem\">These impact search rankings and user experience. Plan for the next development cycle.</p>"
              (str/join (map render-rec p1s))))
          (when (seq p2s)
            (str "<h3 style=\"color:var(--mc-accent);margin-top:1.25rem\">Improve Later — Design Quality</h3>"
              "<p style=\"font-size:.84rem;color:var(--mc-text-secondary);margin-bottom:.75rem\">These improve visual consistency and code quality. Address during refactoring or design reviews.</p>"
              (str/join (map render-rec p2s)))))))))

;; =============================================================================
;; Page Insights
;; =============================================================================

(defn- fmt-bytes [^long b]
  (cond (< b 1024) (str b " B")
    (< b 1048576) (str (Math/round (/ (double b) 1024.0)) " KB")
    :else (format "%.1f MB" (/ (double b) 1048576.0))))

(defn- render-insights [data]
  (let [timing (:timing data) resources (:resources data) dom (:dom data)
        images (:images data) meta-info (:meta data) forms (:forms data)
        tp (:third-party data) mc (:mixed-content data) sd (:structured-data data)
        cookies (:cookies data) dup-ids (:duplicate-ids data)
        a11y (:a11y-extended data) img-ext (:images-extended data)
        fl (:font-loading data) https-info (:https data) seo-ext (:seo-extended data)
        rh (:resource-hints data) inl (:inline-styles data) dep (:deprecated-html data)
        resp-img (:responsive-images data) pw (:page-weight data)
        fv (:focus-visible data) tab (:tab-order data) oi (:oversized-images data)
        asset (:asset-sizes data)]
    (str
      "<h2 id=\"s-insights\">Page Insights</h2>"
      "<div class=\"section-overview\">"
      (overview-pill "DOM Elements" (or (:total-elements dom) 0))
      (overview-pill "Resources" (or (:total-count resources) 0))
      (overview-pill "Page Size" (fmt-bytes (or (:total-size-bytes resources) 0)))
      "</div>"
      (rationale "Additional checks covering page speed, security, SEO tags, and accessibility details. These complement the main quality scores above.")
      ;; Performance
      (when timing
        (card "<h3>Performance Timing</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">TTFB</span><span class=\"coverage-value\">" (:ttfb-ms timing) "ms</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">DOM Interactive</span><span class=\"coverage-value\">" (:dom-interactive-ms timing) "ms</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">DOM Complete</span><span class=\"coverage-value\">" (:dom-complete-ms timing) "ms</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Full Load</span><span class=\"coverage-value\">" (:load-ms timing) "ms</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">DNS</span><span class=\"coverage-value\">" (:dns-ms timing) "ms</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Transfer Size</span><span class=\"coverage-value\">" (fmt-bytes (:transfer-size timing)) "</span></div>"
          "</div>"))
      ;; Meta / SEO
      (when meta-info
        (let [chk (fn [pass? nm desc]
                    (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                      "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                      "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                      "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
          (card "<h3>Meta Tags &amp; SEO</h3><div class=\"checks-grid\">"
            (chk (and (:title meta-info) (<= 10 (long (:title-length meta-info)) 70))
              (str "Title (" (:title-length meta-info) " chars)")
              (or (:title meta-info) "MISSING"))
            (chk (and (:description meta-info) (pos? (long (:description-length meta-info))))
              (str "Meta Description (" (:description-length meta-info) " chars)")
              (or (:description meta-info) "MISSING"))
            (chk (some? (:viewport meta-info)) "Viewport Meta" (or (:viewport meta-info) "MISSING"))
            (chk (some? (:canonical meta-info)) "Canonical URL" (or (:canonical meta-info) "MISSING"))
            (chk (some? (:lang meta-info)) "Language" (or (:lang meta-info) "MISSING"))
            (chk (some? (:charset meta-info)) "Charset" (or (:charset meta-info) "MISSING"))
            (chk (some? (:og-title meta-info)) "Open Graph Title" (or (:og-title meta-info) "MISSING"))
            (chk (some? (:og-image meta-info)) "Open Graph Image" (or (:og-image meta-info) "MISSING"))
            (chk (some? (:robots meta-info)) "Robots Meta" (or (:robots meta-info) "not set"))
            "</div>")))
      ;; Images
      (when images
        (card "<h3>Image Accessibility</h3>"
          (verdict (if (zero? (long (:missing-alt images))) "verdict-pass" "verdict-fail")
            (if (zero? (long (:missing-alt images)))
              (str "ALL " (:total images) " IMAGES HAVE ALT TEXT")
              (str (:missing-alt images) " OF " (:total images) " IMAGES MISSING ALT TEXT")))
          "<div class=\"severity-bars\" style=\"margin:.75rem 0\">"
          (severity-bar "With alt" (- (long (:total images)) (long (:missing-alt images))) "low" (max 1 (long (:total images))))
          (severity-bar "Missing alt" (:missing-alt images) "critical" (max 1 (long (:total images))))
          "</div>"
          (when (and (pos? (long (:missing-alt images))) (seq (:missing-alt-samples images)))
            (collapsible (str "Images Missing Alt (" (:missing-alt images) ")")
              (str "<ul>" (str/join (map (fn [s] (str "<li><code>" (esc s) "</code></li>")) (:missing-alt-samples images))) "</ul>")))))
      ;; DOM stats
      (when dom
        (card "<h3>DOM Statistics</h3><div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Elements</span><span class=\"coverage-value\">" (:total-elements dom) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Scripts</span><span class=\"coverage-value\">" (:scripts dom) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Stylesheets</span><span class=\"coverage-value\">" (:stylesheets dom) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Images</span><span class=\"coverage-value\">" (:images dom) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Forms</span><span class=\"coverage-value\">" (:forms dom) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">IFrames</span><span class=\"coverage-value\">" (:iframes dom) "</span></div>"
          "</div>"))
      ;; Forms
      (when (and forms (pos? (long (:total forms))))
        (card "<h3>Form Accessibility</h3>"
          (verdict (if (zero? (long (:inputs-without-label forms))) "verdict-pass" "verdict-warn")
            (if (zero? (long (:inputs-without-label forms)))
              "ALL INPUTS LABELED"
              (str (:inputs-without-label forms) " INPUTS WITHOUT LABELS")))
          "<p>" (:total forms) " form(s). Unlabeled inputs: " (:inputs-without-label forms) ".</p>"))
      ;; Resources breakdown
      (when (and resources (seq (:by-type resources)))
        (collapsible (str "Resource Breakdown (" (:total-count resources) " resources, " (fmt-bytes (:total-size-bytes resources)) ")")
          (str "<table><thead><tr><th>Type</th><th>Count</th><th>Size</th><th>Avg Duration</th></tr></thead><tbody>"
            (str/join (map (fn [t]
                             (str "<tr><td><strong>" (esc (:type t)) "</strong></td>"
                               "<td>" (:count t) "</td>"
                               "<td>" (fmt-bytes (:size-bytes t)) "</td>"
                               "<td>" (:avg-duration-ms t) "ms</td></tr>"))
                        (:by-type resources)))
            "</tbody></table>")))
      ;; Third-party scripts
      (when tp
        (card "<h3>Third-Party Scripts</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">External Scripts</span><span class=\"coverage-value\">" (:scripts tp) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">External Domains</span><span class=\"coverage-value\">" (count (:domains tp)) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Render-Blocking Scripts</span><span class=\"coverage-value\">" (count (:render-blocking-scripts tp)) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Render-Blocking CSS</span><span class=\"coverage-value\">" (count (:render-blocking-css tp)) "</span></div>"
          "</div>"
          (when (seq (:domains tp))
            (collapsible (str "Third-Party Domains (" (count (:domains tp)) ")")
              (str "<table><thead><tr><th>Domain</th><th>Scripts</th></tr></thead><tbody>"
                (str/join (map (fn [d] (str "<tr><td><code>" (esc (:domain d)) "</code></td><td>" (:count d) "</td></tr>")) (:domains tp)))
                "</tbody></table>")))
          (when (seq (:render-blocking-scripts tp))
            (collapsible (str "Render-Blocking Scripts (" (count (:render-blocking-scripts tp)) ")")
              (str "<ul>" (str/join (map (fn [s] (str "<li><code>" (esc (:src s)) "</code>" (when (:external s) " [external]") "</li>")) (:render-blocking-scripts tp))) "</ul>")))))
      ;; Security: mixed content + HTTPS
      (when mc
        (card "<h3>Security: Mixed Content</h3>"
          (verdict (if (zero? (long (:count mc))) "verdict-pass" "verdict-fail")
            (if (zero? (long (:count mc))) "NO MIXED CONTENT" (str (:count mc) " MIXED CONTENT ITEMS")))
          (when (seq (:items mc))
            (str "<ul>" (str/join (map (fn [m] (str "<li><code>" (esc (:tag m)) "</code>: <code>" (esc (:url m)) "</code></li>")) (:items mc))) "</ul>"))))
      ;; Structured data
      (when sd
        (card "<h3>Structured Data</h3>"
          "<div class=\"checks-grid\">"
          (let [chk (fn [pass? nm desc]
                      (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                        "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                        "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                        "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
            (str (chk (pos? (long (:json-ld-count sd)))
                   (str "JSON-LD (" (:json-ld-count sd) ")")
                   (if (seq (:types sd)) (str/join ", " (:types sd)) "none found"))
              (chk (pos? (long (:microdata-count sd)))
                (str "Microdata (" (:microdata-count sd) ")")
                (if (pos? (long (:microdata-count sd))) "itemscope elements found" "none found"))))
          "</div>"))
      ;; Cookies
      (when cookies
        (card "<h3>Cookies</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Cookie Count</span><span class=\"coverage-value\">" (:count cookies) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Total Size</span><span class=\"coverage-value\">" (fmt-bytes (:total-size cookies)) "</span></div>"
          "</div>"))
      ;; Image optimization extended
      (when img-ext
        (card "<h3>Image Optimization</h3>"
          "<div class=\"checks-grid\">"
          (let [chk (fn [pass? nm desc]
                      (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                        "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                        "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                        "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
            (str (chk (zero? (long (:missing-dimensions img-ext)))
                   "Explicit Dimensions"
                   (str (:missing-dimensions img-ext) " images missing width/height (CLS risk)"))
              (chk (zero? (long (:no-lazy-below-fold img-ext)))
                "Lazy Loading"
                (str (:no-lazy-below-fold img-ext) " below-fold images without loading=lazy"))))
          "</div>"
          (when (seq (:formats img-ext))
            (str "<h4>Image Formats</h4><div class=\"coverage-grid\">"
              (str/join (map (fn [[fmt cnt]] (str "<div class=\"coverage-stat\"><span class=\"coverage-label\">" (str/upper-case (name fmt)) "</span><span class=\"coverage-value\">" cnt "</span></div>")) (sort-by (fn [[_ v]] (- (long v))) (:formats img-ext))))
              "</div>"))))
      ;; Duplicate IDs
      (when (and dup-ids (pos? (long (:count dup-ids))))
        (card "<h3>Duplicate IDs</h3>"
          (verdict "verdict-fail" (str (:count dup-ids) " DUPLICATE IDs"))
          "<p>Duplicate IDs break ARIA label associations, anchor links, and querySelector results.</p>"
          (collapsible (str "Duplicate IDs (" (:count dup-ids) ")")
            (str "<table><thead><tr><th>ID</th><th>Count</th></tr></thead><tbody>"
              (str/join (map (fn [d] (str "<tr><td><code>" (esc (:id d)) "</code></td><td>" (:count d) "</td></tr>")) (:items dup-ids)))
              "</tbody></table>"))))
      ;; A11y extended
      (when a11y
        (card "<h3>Accessibility Deep Dive</h3>"
          "<div class=\"checks-grid\">"
          (let [chk (fn [pass? nm desc]
                      (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                        "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                        "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                        "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
            (str (chk (:skip-nav a11y) "Skip Navigation Link" (if (:skip-nav a11y) "Found" "MISSING — WCAG 2.4.1"))
              (chk (zero? (long (:empty-links a11y))) (str "Empty Links (" (:empty-links a11y) ")") (if (zero? (long (:empty-links a11y))) "None found" "Links with no text or aria-label"))
              (chk (zero? (long (:empty-buttons a11y))) (str "Empty Buttons (" (:empty-buttons a11y) ")") (if (zero? (long (:empty-buttons a11y))) "None found" "Buttons with no text or aria-label"))))
          "</div>"
          (when (seq (:aria-roles a11y))
            (collapsible (str "ARIA Roles (" (count (:aria-roles a11y)) " distinct)")
              (str "<table><thead><tr><th>Role</th><th>Count</th></tr></thead><tbody>"
                (str/join (map (fn [[r cnt]] (str "<tr><td><code>" (name r) "</code></td><td>" cnt "</td></tr>")) (sort-by (fn [[_ v]] (- (long v))) (:aria-roles a11y))))
                "</tbody></table>")))))
      ;; Page Weight Budget
      (when pw
        (card "<h3>Page Weight</h3>"
          (verdict (if (:budget-ok pw) "verdict-pass" "verdict-fail")
            (if (:budget-ok pw)
              (str "UNDER BUDGET: " (fmt-bytes (:total-bytes pw)) " / 1.5 MB")
              (str "OVER BUDGET: " (fmt-bytes (:total-bytes pw)) " / 1.5 MB")))
          "<p>Total transfer size of all resources. Budget: 1.5 MB for good mobile performance.</p>"))
      ;; HTTPS
      (when https-info
        (card "<h3>HTTPS</h3>"
          (let [chk (fn [pass? nm desc]
                      (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                        "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                        "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                        "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
            (chk (:is-https https-info) "HTTPS Protocol" (if (:is-https https-info) "Page served over HTTPS" "NOT SECURE — served over HTTP")))))
      ;; Font loading
      (when fl
        (card "<h3>Font Loading</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Font Links</span><span class=\"coverage-value\">" (:font-links fl) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Preloaded Fonts</span><span class=\"coverage-value\">" (:preloaded fl) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Font Faces Loaded</span><span class=\"coverage-value\">" (:font-faces-loaded fl) "</span></div>"
          "</div>"
          (when (and (pos? (long (:font-links fl))) (zero? (long (:preloaded fl))))
            "<p style=\"color:var(--mc-accent-yellow);font-size:.84rem\">No fonts preloaded. Consider adding &lt;link rel=preload&gt; for critical web fonts to reduce FOUT/FOIT.</p>")))
      ;; SEO extended
      (when seo-ext
        (card "<h3>Link Distribution &amp; Sitemap</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Internal Links</span><span class=\"coverage-value\">" (:internal-links seo-ext) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">External Links</span><span class=\"coverage-value\">" (:external-links seo-ext) "</span></div>"
          "</div>"
          (let [chk (fn [pass? nm desc]
                      (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                        "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                        "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                        "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
            (chk (:has-sitemap-link seo-ext) "Sitemap Link" (or (:sitemap-href seo-ext) "No &lt;link rel=sitemap&gt; found")))))
      ;; Resource hints
      (when rh
        (card "<h3>Resource Hints</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Preconnect</span><span class=\"coverage-value\">" (count (:preconnect rh)) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Prefetch / DNS-Prefetch</span><span class=\"coverage-value\">" (count (:prefetch rh)) "</span></div>"
          "</div>"
          (when (seq (:preconnect rh))
            (collapsible (str "Preconnect Origins (" (count (:preconnect rh)) ")")
              (str "<ul>" (str/join (map (fn [u] (str "<li><code>" (esc u) "</code></li>")) (:preconnect rh))) "</ul>")))))
      ;; Responsive images
      (when resp-img
        (card "<h3>Responsive Images</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">With srcset</span><span class=\"coverage-value\">" (:srcset resp-img) " / " (:total-images resp-img) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">With sizes</span><span class=\"coverage-value\">" (:sizes resp-img) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">&lt;picture&gt; Elements</span><span class=\"coverage-value\">" (:picture-elements resp-img) "</span></div>"
          "</div>"))
      ;; Inline styles
      (when inl
        (card "<h3>Inline Styles</h3>"
          (verdict (if (< (long (:count inl)) 50) "verdict-pass" "verdict-warn")
            (str (:count inl) " ELEMENTS WITH INLINE STYLES"))
          "<p>High inline style count indicates CSS not in stylesheets. Hurts cacheability and maintainability.</p>"))
      ;; Deprecated HTML
      (when (and dep (pos? (long (:count dep))))
        (card "<h3>Deprecated HTML</h3>"
          (verdict "verdict-fail" (str (:count dep) " DEPRECATED TAGS FOUND"))
          "<ul>" (str/join (map (fn [d] (str "<li><code>&lt;" (esc (:tag d)) "&gt;</code> used " (:count d) " times</li>")) (:items dep))) "</ul>"))
      ;; Focus visible
      (when fv
        (let [chk (fn [pass? nm desc]
                    (str "<div class=\"check-item " (if pass? "check-pass" "check-fail") "\">"
                      "<div class=\"check-icon\">" (if pass? "&#10003;" "&#10007;") "</div>"
                      "<div class=\"check-body\"><div class=\"check-name\">" (esc nm) "</div>"
                      "<div class=\"check-desc\">" (esc desc) "</div></div></div>"))]
          (card "<h3>Focus Visibility</h3><div class=\"checks-grid\">"
            (chk (:has-focus-styles fv) "Focus / Focus-Visible CSS"
              (if (:has-focus-styles fv) "Focus styles detected in stylesheets" "No :focus or :focus-visible CSS rules found. Keyboard users may not see which element is focused."))
            "</div>")))
      ;; Tab order
      (when tab
        (card "<h3>Tab Order &amp; Focusable Elements</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Focusable Elements</span><span class=\"coverage-value\">" (:focusable-elements tab) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">Positive tabindex</span><span class=\"coverage-value\">" (:positive-tabindex tab) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">tabindex=-1</span><span class=\"coverage-value\">" (:negative-tabindex tab) "</span></div>"
          "</div>"
          (when (pos? (long (:positive-tabindex tab)))
            (str "<p style=\"color:var(--mc-accent-yellow);font-size:.84rem\">Positive tabindex values override natural tab order. This is almost always an accessibility anti-pattern.</p>"
              (collapsible (str "Elements with positive tabindex (" (:positive-tabindex tab) ")")
                (str "<ul>" (str/join (map (fn [s] (str "<li><code>&lt;" (esc (:tag s)) " tabindex=" (esc (:tabindex s)) "&gt;</code> " (esc (:text s)) "</li>")) (:positive-samples tab))) "</ul>"))))))
      ;; Oversized images
      (when (and oi (pos? (long (:count oi))))
        (card "<h3>Oversized Images (&gt;200KB)</h3>"
          (verdict "verdict-fail" (str (:count oi) " OVERSIZED IMAGES"))
          "<p>Images over 200KB significantly impact page load time, especially on mobile.</p>"
          (collapsible (str "Oversized Images (" (:count oi) ")")
            (str "<table><thead><tr><th>URL</th><th>Size</th><th>Duration</th></tr></thead><tbody>"
              (str/join (map (fn [o] (str "<tr><td><code style=\"word-break:break-all\">" (esc (:url o)) "</code></td><td>" (fmt-bytes (:size-bytes o)) "</td><td>" (:duration-ms o) "ms</td></tr>")) (:items oi)))
              "</tbody></table>"))))
      ;; Asset sizes
      (when asset
        (card "<h3>Asset Sizes (CSS &amp; JavaScript)</h3>"
          "<div class=\"coverage-grid\">"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">CSS Total</span><span class=\"coverage-value\">" (fmt-bytes (:css-total-bytes asset)) "</span></div>"
          "<div class=\"coverage-stat\"><span class=\"coverage-label\">JS Total</span><span class=\"coverage-value\">" (fmt-bytes (:js-total-bytes asset)) "</span></div>"
          "</div>"
          (when (> (long (:js-total-bytes asset)) 1048576)
            "<p style=\"color:var(--mc-accent-yellow);font-size:.84rem\">JavaScript exceeds 1MB. Consider code splitting, tree shaking, or removing unused dependencies.</p>")
          (when (> (long (:css-total-bytes asset)) 524288)
            "<p style=\"color:var(--mc-accent-yellow);font-size:.84rem\">CSS exceeds 512KB. Consider removing unused styles or splitting critical CSS.</p>"))))))

;; =============================================================================
;; Section dispatch
;; =============================================================================

(defn- render-sec [audit-data k recs-fn sec-fn]
  (let [d (get audit-data k)]
    (cond
      (nil? d) ""
      (:error d) (str "<details class=\"collapsible\" style=\"margin-bottom:1rem\"><summary>"
                      (name k) " (error)</summary><div class=\"collapsible__body\">"
                      "<div class=\"err\"><strong>Error:</strong> " (esc (:error d)) "</div>"
                      "</div></details>")
      :else (let [r (or (recs-fn d) [])]
              ;; Wrap the section content in a collapsible
              ;; sec-fn produces <h2 id=...>Title</h2>...content...
              ;; We extract the h2 text for the summary and wrap the rest
              (let [html (sec-fn d r)
                    ;; Extract title from <h2...>TITLE</h2>
                    h2-end (when html (.indexOf ^String html "</h2>"))
                    h2-start (when (and h2-end (>= (long h2-end) 0)) (.indexOf ^String html ">"))
                    title (if (and h2-start h2-end (>= (long h2-end) 0) (>= (long h2-start) 0))
                            (subs html (inc (long h2-start)) (long h2-end))
                            (name k))
                    body (if (and h2-end (>= (long h2-end) 0))
                           (subs html (+ (long h2-end) 5))
                           html)]
                (str "<details class=\"collapsible\" style=\"margin-bottom:1rem\">"
                     "<summary>" title "</summary>"
                     "<div class=\"collapsible__body\">" body "</div>"
                     "</details>"))))))

;; =============================================================================
;; Main
;; =============================================================================

(defn generate
  "Generate a comprehensive HTML audit report using the spel design system."
  ^String [audit-data opts]
  (let [url   (or (:url opts) (:url (:structure audit-data)) (:url (:contrast audit-data)) "")
        title (or (:title opts) (:title (:structure audit-data)) url)
        ts    (now-str)
        scores (compute-scores audit-data)
        recs   (all-recs audit-data)
        has-insights (and (:insights audit-data) (not (:error (:insights audit-data))))]
    (str
      "<!DOCTYPE html><html lang=\"en\"><head>"
      "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
      "<title>Spel Audit — " (esc title) "</title>"
      "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
      "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
      "<link href=\"https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700&family=Manrope:wght@500;600;700;800&family=IBM+Plex+Mono:wght@400;500;600;700&display=swap\" rel=\"stylesheet\">"
      "<style>" base-css audit-css "</style></head><body>"
      "<div class=\"layout\">"
      ;; Sidebar
      "<aside class=\"sidebar\">"
      "<div class=\"sidebar-brand\">spel Audit</div>"
      "<div class=\"sidebar-product\"><strong>" (esc title) "</strong><br>" (esc url) "</div>"
      "<ul class=\"sidebar-nav\">"
      "<li><a href=\"#executive-summary\">Summary &amp; Score</a></li>"
      "<li><a href=\"#s-scores\">Score Breakdown</a></li>"
      (when has-insights "<li><a href=\"#s-insights\">Page Insights</a></li>")
      "<li><a href=\"#s-audit\">Detailed Findings</a></li>"
      "<li><a href=\"#s-recs\">Action Plan</a></li>"
      "</ul>"
      "<div class=\"sidebar-footer\">Generated " (esc ts) "<br>spel Audit Report</div>"
      "</aside>"
      ;; Main
      "<main class=\"main-content\">"
      "<header class=\"report-header\">"
      "<div class=\"report-kicker\">Automated Website Audit</div>"
      "<h1 class=\"report-title\">" (esc title) "</h1>"
      "<p class=\"report-subtitle\">A full check of your page’s accessibility, performance, SEO, and design quality — with specific issues to fix and clear next steps.</p>"
      "<div class=\"report-meta\">"
      "<span><strong>URL:</strong> <a href=\"" (esc url) "\">" (esc url) "</a></span>"
      "<span><strong>Date:</strong> " (esc ts) "</span>"
      "<span><strong>Checks:</strong> " (if has-insights "8" "7") " categories</span>"
      "<span><strong>Score:</strong> " (:overall scores) "/100</span>"
      "</div></header>"
      ;; Executive Summary
      "<section id=\"executive-summary\" class=\"section\">" (render-exec scores recs url) "</section>"
      ;; Screenshot
      (when-let [ss (:screenshot opts)]
        (str "<div class=\"audit-screenshot\"><div class=\"audit-screenshot-cap\">Page Screenshot</div>"
          "<img src=\"" (b64 ss) "\" alt=\"screenshot\" loading=\"lazy\"></div>"))
      ;; Health Scores
      "<section id=\"s-scores\" class=\"section\">" (render-scores scores) "</section>"
      ;; Page Insights (performance, meta, images, DOM, forms)
      (when has-insights
        (str "<section id=\"s-insights\" class=\"section\">" (render-insights (:insights audit-data)) "</section>"))
      ;; Automated Audit — one section, summary grid + collapsibles (matches template)
      "<section id=\"s-audit\" class=\"section\">"
      "<h2>Detailed Findings</h2>"
      "<p style=\"margin-bottom:1rem\">Expand any section below to see specific issues and what to do about them.</p>"
      ;; Summary grid with key metrics
      "<div class=\"summary-grid\" style=\"margin-bottom:2rem\">"
      (let [c (:contrast audit-data) l (:links audit-data) ly (:layout audit-data)
            h (:headings audit-data) s (:structure audit-data) f (:fonts audit-data)
            co (:colors audit-data)]
        (str
          (summary-card (if (and c (not (:error c))) (or (:failing c) 0) "?") "Unreadable Text"
                        (if (and c (not (:error c)) (zero? (long (or (:failing c) 0)))) "success" "critical"))
          (summary-card (if (and ly (not (:error ly))) (or (:total-issues ly) 0) "?") "Layout Problems"
                        (if (and ly (not (:error ly)) (:clean? ly)) "success" "warning"))
          (summary-card (if (and l (not (:error l))) (or (:broken l) 0) "?") "Broken Links"
                        (if (and l (not (:error l)) (zero? (long (or (:broken l) 0)))) "success" "critical"))
          (summary-card (if (and h (not (:error h))) (count (:issues h)) "?") "Heading Problems"
                        (if (and h (not (:error h)) (:valid? h)) "success" "warning"))
          (summary-card (if (and s (not (:error s))) (count (:sections s)) "?") "Page Sections" "highlight")
          (summary-card (if (and f (not (:error f))) (get-in f [:stats :font-count] 0) "?") "Font Families" "highlight")
          (summary-card (if (and co (not (:error co))) (or (:total-colors co) 0) "?") "Unique Colors" "highlight")))
      "</div>"
      ;; Each audit as a collapsible
      (render-sec audit-data :contrast recs-contrast sec-contrast)
      (render-sec audit-data :layout recs-layout sec-layout)
      (render-sec audit-data :headings recs-headings sec-headings)
      (render-sec audit-data :structure recs-structure sec-structure)
      (render-sec audit-data :fonts recs-fonts sec-fonts)
      (render-sec audit-data :links recs-links sec-links)
      (render-sec audit-data :colors recs-colors sec-colors)
      "</section>"
      ;; Recommendations (all, flat)
      "<section id=\"s-recs\" class=\"section\">" (render-action-plan recs) "</section>"
      ;; Footer
      "<footer class=\"report-footer\">Generated by <strong>spel</strong> &middot; " (esc ts) "</footer>"
      "</main></div></body></html>")))
