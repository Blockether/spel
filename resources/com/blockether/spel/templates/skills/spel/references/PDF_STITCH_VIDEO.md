# PDF generation, image stitching, video recording

Three capabilities: page → PDF, multi-screenshot stitching, session video.

## PDF generation

Chromium-only. Firefox/WebKit don't support it.

```clojure
;; eval-sci (daemon running)
(spel/navigate "https://en.wikipedia.org/wiki/Clojure")
(spel/wait-for-load-state)
(spel/pdf {:path "/tmp/doc.pdf"})

;; shorthand — string path
(spel/pdf "/tmp/doc.pdf")

;; no :path → returns byte[]
```

CLI: `spel pdf /tmp/output.pdf`.

Library:

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/pdf pg {:path "/tmp/doc.pdf" :format "A4"}))
```

### Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:path` | String | nil | Output path; nil → `byte[]` |
| `:format` | String | nil | `"A4"` `"Letter"` `"Legal"` `"Tabloid"` |
| `:landscape` | Boolean | false | Horizontal |
| `:print-background` | Boolean | false | CSS backgrounds + images |
| `:page-ranges` | String | nil | `"1-3"`, `"1,3,5"` |
| `:header-template` / `:footer-template` | String | nil | HTML templates |
| `:prefer-css-page-size` | Boolean | false | CSS `@page` over `:format` |
| `:width` / `:height` | String | nil | e.g. `"8.5in"` / `"11in"` |
| `:scale` | Double | 1.0 | 0.1–2.0 |

> `:margin` takes `{:top "20px" :bottom "20px" :left "20px" :right "20px"}` (any subset, CSS units) and is applied by `page/pdf`; `report->pdf` uses exactly this default.

Header/footer template classes: `date`, `title`, `url`, `pageNumber`, `totalPages`.

```clojure
(spel/pdf {:path "/tmp/report.pdf" :format "A4" :landscape true
           :print-background true :scale 0.8 :page-ranges "1-5"
           :display-header-footer true
           :header-template "<div style='font-size:10px;text-align:center;width:100%'>My Report</div>"
           :footer-template "<div style='font-size:10px;text-align:center;width:100%'>Page <span class='pageNumber'></span> of <span class='totalPages'></span></div>"})
```

## Custom HTML reports → PDF

`report->html` builds an HTML string from typed entries — no browser needed.
`report->pdf` loads the HTML into the current page and calls `page.pdf()` — needs an active session.

```clojure
(let [html (spel/report->html
             [{:type :section :text "Audit Results" :level 1}
              {:type :text    :text "Checked 15 pages for a11y."}
              {:type :good    :text "Color contrast" :items ["All text meets WCAG AA"]}
              {:type :issue   :text "Missing alt text" :items ["hero-image.png" "logo.svg"]}])]
  (spit "/tmp/report.html" html))

(spel/report->pdf
  [{:type :section :text "Test Results" :level 1}
   {:type :text    :text "All tests passed."}]
  {:path "/tmp/results.pdf" :title "CI Report"})
```

Library: `(annotate/report->pdf pg entries {:path "out.pdf" :title "Report" :format "A4" :margin {:top "20px" :bottom "20px" :left "20px" :right "20px"}})`.

### Entry types

| Type | Required | Optional | Renders as |
|------|----------|----------|-----------|
| `:screenshot` | `:image` (byte[]) | `:caption`, `:page-break` | Base64 image + caption |
| `:section` | `:text` | `:level` 1/2/3, `:page-break` | Heading |
| `:observation` | `:text` | `:items` | Highlighted block + bullets |
| `:issue` | `:text` | `:items` | Red block + bullets |
| `:good` | `:text` | `:items` | Green block + bullets |
| `:table` | `:headers`, `:rows` | — | HTML table |
| `:meta` | `:fields [[label val]...]` | — | Key-value pairs |
| `:text` | `:text` | — | Paragraph |
| `:html` | `:content` | — | Raw HTML (not escaped) |

```clojure
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
(let [shot1 (spel/screenshot)               ;; byte[] when no :path
      _     (spel/navigate "https://example.org/about")
      _     (spel/wait-for-load-state)
      shot2 (spel/screenshot)]
  (spel/report->pdf
    [{:type :meta :fields [["Date" "2026-02-24"] ["Auditor" "spel"]]}
     {:type :section :text "Homepage" :level 2}
     {:type :screenshot :image shot1 :caption "Landing page"}
     {:type :good :text "Page loads correctly" :items ["Title present" "No console errors"]}
     {:type :section :text "About Page" :level 2 :page-break true}
     {:type :screenshot :image shot2 :caption "About page"}
     {:type :issue :text "Missing meta description" :items ["SEO: moderate"]}]
    {:path "/tmp/site-audit.pdf" :title "Site Audit Report"}))
```

## Slide-deck PDFs (HTML → PDF)

Same pattern as Slidev/Marp/reveal.js — CSS `@page` + `page-break-after`.

```css
@page { size: 1920px 1080px; margin: 0; }
* { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
.slide {
  width: 1920px; height: 1080px; padding: 80px 100px;
  overflow: hidden; position: relative;
  page-break-after: always; break-after: page;
  display: flex; flex-direction: column;
}
.slide:last-child { page-break-after: avoid; break-after: avoid; }
```

```clojure
(spel/set-content! (str "<style>" css "</style>" slides-html))
(spel/wait-for-load-state :load)
(spel/emulate-media! {:media :screen})      ;; CRITICAL — without this, colors/gradients fade in PDF
(spel/pdf {:path "presentation.pdf"
           :print-background true
           :prefer-css-page-size true})
```

Gotchas: CSS animations don't survive PDF (`* { animation: none !important; }`); GIFs show only first frame; `{:print-background true :prefer-css-page-size true}` = exact slide dimensions.

## Image stitching

Combine screenshots into one tall image (virtual scroll, infinite feed, content taller than viewport). Internally: base64-encodes each image, renders as `<img>` in HTML, full-page screenshot — no AWT/ImageIO.

```clojure
(stitch/stitch-vertical ["/tmp/top.png" "/tmp/mid.png" "/tmp/bot.png"] "/tmp/full.png")

;; Overlap trim (subsequent images overlap when scrolling)
(stitch/stitch-vertical-overlap
  ["/tmp/s1.png" "/tmp/s2.png" "/tmp/s3.png"]
  "/tmp/stitched.png" {:overlap-px 50})

;; Read as base64
(stitch/read-image "/tmp/screenshot.png")    ;; => "iVBORw0KGgo..."
```

CLI:

```bash
spel stitch top.png middle.png bottom.png -o full-page.png
spel stitch s1.png s2.png s3.png --overlap 50 -o stitched.png
```

Scroll-capture workflow:

```clojure
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)
(let [vh      (-> (spel/evaluate "window.innerHeight") long)
      sh      (-> (spel/evaluate "document.body.scrollHeight") long)
      overlap 50
      step    (- vh overlap)
      paths   (vec (for [[i pos] (map-indexed vector (range 0 sh step))]
                     (let [p (str "/tmp/scroll-" i ".png")]
                       (spel/evaluate (str "window.scrollTo(0, " pos ")"))
                       (spel/wait-for-load-state)
                       (spel/screenshot {:path p})
                       p)))]
  (stitch/stitch-vertical-overlap paths "/tmp/full-page.png" {:overlap-px overlap}))
```

## Video recording

WebM. Useful for debugging failures, demos, CI artifacts.

### eval-sci

```clojure
(spel/start-video-recording)
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
;; ... actions ...
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
```

`start-video-recording` closes the current context and creates a new one with video enabled — page state (cookies, localStorage) resets.

Options: `{:video-dir "videos" :video-size {:width 1280 :height 720}}`.

```clojure
(spel/video-path)                                          ;; current file or nil
(spel/finish-video-recording {:save-as "/tmp/demo.webm"})  ;; stop + copy
(spel/finish-video-recording)                              ;; stop, keep in :video-dir
```

`finish-video-recording` closes the context (finalizing video) and creates a fresh one without video — you can keep browsing.

### Library mode

Pass `:record-video-dir` when creating a context; video finalizes on context close.

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser
                              {:record-video-dir "videos"
                               :record-video-size {:width 1280 :height 720}})]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.org")
        (core/video-save-as! pg "/tmp/recording.webm")))))
```

### Video with voiceover

spel records video only — no built-in audio or TTS. For narrated videos: record with pauses → generate TTS → merge via ffmpeg.

```bash
# macOS
say -o /tmp/narration.aiff "Welcome to the demo."
# Linux
espeak -w /tmp/narration.wav "Welcome to the demo."

# Merge (-shortest stops at shorter stream)
ffmpeg -i /tmp/demo.webm -i /tmp/narration.aiff \
       -c:v copy -c:a aac -shortest /tmp/demo-narrated.mp4
```

Tip: ~3 s per sentence matches most TTS voices.

## Action log + SRT subtitles

The daemon records every trackable **CLI command** (`spel click`, `spel open`, …) with a timestamp. Export as SRT for video overlays, JSON for replay.

**Actions run inside `eval-sci` are NOT recorded** — tracking happens where the daemon dispatches a command, and one `eval-sci` is a single non-trackable command however many `spel/click` calls it makes. Drive the steps you want subtitled as CLI commands (video recording itself can stay in `eval-sci`).

CLI:

```bash
spel action-log                   # JSON dump (same payload as --json)
spel action-log --srt             # SRT format
spel action-log --srt -o s.srt
spel action-log --json -o s.json
spel action-log --clear
```

eval-sci:

```clojure
(spel/action-log)                                  ;; entries
(spel/export-srt)                                  ;; SRT string
(spel/export-srt {:min-duration-ms 500 :max-duration-ms 8000})
(spel/clear-action-log!)
```

Entry fields: `idx, timestamp, time, action, target, args, url, title, snapshot`.

Tracked (`trackable-actions`, `daemon.clj`): navigate, click, fill, type, press, hover, check, uncheck, select, dblclick, focus, clear, screenshot, scroll, survey, routes, inspect, overview, debug, emulate, markdownify, back, forward, reload, drag_to, tap, swipe, set_input_files.

Not tracked: snapshot, evaluate, network, console, eval-sci (and therefore everything it calls).

## Smooth video pacing

```clojure
(spel/smooth-scroll 500)                 ;; to Y=500 (CSS-animated)
(spel/smooth-scroll {:delta-y 300})      ;; scroll down 300

(spel/human-pause)                       ;; 300–700ms random
(spel/human-pause 500 1000)              ;; custom range
```

Smooth video example:

```bash
spel eval-sci '(do (spel/start-video-recording {:video-size {:width 1920 :height 1080}}) :ok)'
spel action-log --clear
spel open https://example.org
spel eval-sci '(do (spel/smooth-scroll 300) (spel/human-pause))'   # pacing only — not subtitled
spel click "a"
spel action-log --srt -o /tmp/session.srt
spel eval-sci '(spel/finish-video-recording {:save-as "/tmp/session.webm"})'
```

## FFmpeg post-processing

Optional, for polish. spel doesn't depend on ffmpeg.

```bash
# Burn in subs (hard subs)
ffmpeg -i session.webm -vf "subtitles=session.srt" -c:a copy output.mp4

# Styled subs (white text, semi-transparent background)
ffmpeg -i session.webm \
  -vf "subtitles=session.srt:force_style='FontSize=18,PrimaryColour=&HFFFFFF&,BackColour=&H80000000&,BorderStyle=4'" \
  -c:a copy output.mp4

# Remove idle frames, re-encode at 30fps
ffmpeg -i session.webm -vf "mpdecimate,setpts=N/30/TB" -r 30 trimmed.mp4

# Speed
ffmpeg -i session.webm -vf "setpts=0.5*PTS" -af "atempo=2.0" fast.mp4
ffmpeg -i session.webm -vf "setpts=2.0*PTS" -af "atempo=0.5" slow.mp4

# Concatenate
printf "file 'a.mp4'\nfile 'b.mp4'\n" > list.txt
ffmpeg -f concat -safe 0 -i list.txt -c copy out.mp4
```

### Full pipeline

```bash
set -e
spel eval-sci '(do (spel/start-video-recording {:video-size {:width 1920 :height 1080}}) :ok)'
spel action-log --clear

# The subtitled steps must be CLI commands — eval-sci actions never reach the log.
spel open https://example.org
spel eval-sci '(spel/smooth-scroll 500)'
spel click "a"

spel action-log --srt -o /tmp/session.srt
spel eval-sci '(spel/finish-video-recording {:save-as "/tmp/session.webm"})'
ffmpeg -i /tmp/session.webm -vf "mpdecimate,setpts=N/30/TB" -r 30 /tmp/trimmed.mp4
ffmpeg -i /tmp/trimmed.mp4  -vf "subtitles=/tmp/session.srt" -c:a copy /tmp/final.mp4

say -o /tmp/narration.aiff "Welcome to the demo."
ffmpeg -i /tmp/final.mp4 -i /tmp/narration.aiff -c:v copy -c:a aac -shortest /tmp/narrated.mp4
```
