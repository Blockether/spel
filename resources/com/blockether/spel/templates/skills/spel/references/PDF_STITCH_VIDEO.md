# PDF generation, image stitching, video recording

Three capabilities: page→PDF, multi-screenshot stitching, session video recording.

## PDF generation

PDF output works only in Chromium headless. Firefox/WebKit don't support it.

### Basic usage

```clojure
;; eval-sci (daemon running): save current page as PDF
(spel/navigate "https://en.wikipedia.org/wiki/Clojure")
(spel/wait-for-load-state)
(spel/pdf {:path "/tmp/doc.pdf"})
```

String shorthand: `(spel/pdf "/tmp/doc.pdf")`. Without `:path`, returns `byte[]`.

CLI: `spel pdf /tmp/output.pdf`

Library:

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.org")
  (page/pdf pg {:path "/tmp/doc.pdf" :format "A4"}))
```

### Full options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:path` | String | nil | Output file path. If nil, returns `byte[]` |
| `:format` | String | nil | Page format: `"A4"`, `"Letter"`, `"Legal"`, `"Tabloid"` |
| `:landscape` | Boolean | false | Horizontal orientation |
| `:print-background` | Boolean | false | Include CSS backgrounds + images |
| `:page-ranges` | String | nil | Page range, e.g. `"1-3"`, `"1,3,5"` |
| `:header-template` | String | nil | HTML template for page header |
| `:footer-template` | String | nil | HTML template for page footer |
| `:prefer-css-page-size` | Boolean | false | Use CSS `@page` size over `:format` |
| `:width` | String | nil | Paper width, e.g. `"8.5in"` |
| `:height` | String | nil | Paper height, e.g. `"11in"` |
| `:scale` | Double | 1.0 | Webpage rendering scale (0.1 to 2.0) |

> Note: `:margin` not available on `page/pdf` directly. For margin control, use `report->pdf` (below) or CSS `@page` rules with `:prefer-css-page-size true`.

### Example with headers + footers

```clojure
(spel/pdf {:path "/tmp/report.pdf"
           :format "A4"
           :landscape true
           :print-background true
           :scale 0.8
           :page-ranges "1-5"
           :display-header-footer true
           :header-template "<div style='font-size:10px; text-align:center; width:100%'>My Report</div>"
           :footer-template "<div style='font-size:10px; text-align:center; width:100%'>Page <span class='pageNumber'></span> of <span class='totalPages'></span></div>"})
```

Header/footer template CSS classes: `date`, `title`, `url`, `pageNumber`, `totalPages`.

---

## Custom HTML reports → PDF

Report builder creates structured HTML from typed entries → renders to PDF. Combine screenshots, text, tables, observations into one document.

### Building HTML

`spel/report->html` takes entry map seq → returns HTML string. No browser page needed.

```clojure
(let [html (spel/report->html
             [{:type :section :text "Audit Results" :level 1}
              {:type :text :text "Checked 15 pages for accessibility issues."}
              {:type :good :text "Color contrast" :items ["All text meets WCAG AA"]}
              {:type :issue :text "Missing alt text" :items ["hero-image.png" "logo.svg"]}])]
  (spit "/tmp/report.html" html))
```

### Rendering to PDF

`spel/report->pdf` loads HTML into current page → calls `page.pdf()`. Requires active browser session.

```clojure
(spel/report->pdf
  [{:type :section :text "Test Results" :level 1}
   {:type :text :text "All tests passed."}]
  {:path "/tmp/results.pdf" :title "CI Report"})
```

Library (explicit page): `(annotate/report->pdf pg entries {:path "out.pdf" :title "Report" :format "A4" :margin {:top "20px" :bottom "20px" :left "20px" :right "20px"}})`

### Entry types

| Type | Required keys | Optional keys | Renders as |
|------|--------------|---------------|------------|
| `:screenshot` | `:image` (byte[]) | `:caption`, `:page-break` | Base64 image with caption |
| `:section` | `:text` | `:level` (1/2/3), `:page-break` | Heading (h1/h2/h3) |
| `:observation` | `:text` | `:items` [strings] | Highlighted block with bullet list |
| `:issue` | `:text` | `:items` [strings] | Red-tinted block with bullet list |
| `:good` | `:text` | `:items` [strings] | Green-tinted block with bullet list |
| `:table` | `:headers`, `:rows` | | HTML table |
| `:meta` | `:fields` [[label val]...] | | Key-value pairs |
| `:text` | `:text` | | Paragraph |
| `:html` | `:content` | | Raw HTML (no escaping) |

### Complete example: screenshots → PDF report

```clojure
;; eval-sci: capture pages + build PDF report
;; Daemon mode: omit start!/stop! — daemon owns browser
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
(let [shot1 (spel/screenshot)  ;; returns byte[] when no :path given
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
     {:type :issue :text "Missing meta description" :items ["SEO impact: moderate"]}]
    {:path "/tmp/site-audit.pdf" :title "Site Audit Report"}))
```

---

## Slide-deck presentations (HTML → PDF)

Generate presentation-quality PDFs from HTML slides using CSS `@page` + `page-break-after`.
Same pattern as Slidev, Marp, reveal.js.

### CSS template

```css
@page { size: 1920px 1080px; margin: 0; }
* { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
.slide {
  width: 1920px; height: 1080px;
  padding: 80px 100px; overflow: hidden; position: relative;
  page-break-after: always; break-after: page;
  display: flex; flex-direction: column;
}
.slide:last-child { page-break-after: avoid; break-after: avoid; }
```

### Generating from eval-sci

```clojure
(spel/set-content! (str "<style>" css "</style>" slides-html))
(spel/wait-for-load-state :load)
(spel/emulate-media! {:media :screen})  ;; CRITICAL: screen media for visual fidelity
(spel/pdf {:path "presentation.pdf"
           :print-background true
           :prefer-css-page-size true})
```

### Things to know

- `(spel/emulate-media! {:media :screen})` before PDF → gradients + colors render. Required.
- CSS animations DO NOT survive PDF → use `* { animation: none !important; }`
- GIFs show first frame only in PDF
- `{:print-background true :prefer-css-page-size true}` for exact slide dimensions

---

## Image stitching

Combine multiple screenshots into one tall image. Useful for virtual-scroll pages, infinite-scroll feeds, content taller than viewport.

Stitching uses Playwright internally: base64-encodes each image, renders as `<img>` tags in HTML, takes full-page screenshot. No AWT/ImageIO dependency.

### Basic vertical stitch

```clojure
(stitch/stitch-vertical ["/tmp/top.png" "/tmp/mid.png" "/tmp/bot.png"] "/tmp/full.png")
```

Takes vector of file paths + output path. Returns output path.

### Overlap trimming

When scrolling + screenshotting, subsequent images overlap. `stitch-vertical-overlap` trims fixed pixels from top of each image after first.

```clojure
(stitch/stitch-vertical-overlap
  ["/tmp/s1.png" "/tmp/s2.png" "/tmp/s3.png"]
  "/tmp/stitched.png"
  {:overlap-px 50})
```

### Reading images

```clojure
(stitch/read-image "/tmp/screenshot.png")
;; => "iVBORw0KGgo..."  (base64 string)
```

### CLI

```bash
spel stitch top.png middle.png bottom.png -o full-page.png
spel stitch s1.png s2.png s3.png --overlap 50 -o stitched.png
```

### Complete scrolling-stitch workflow

```clojure
;; eval-sci: scroll-capture tall page (daemon manages browser)
(spel/navigate "https://news.ycombinator.com")
(spel/wait-for-load-state)

(let [viewport-h (-> (spel/evaluate "window.innerHeight") long)
      scroll-h   (-> (spel/evaluate "document.body.scrollHeight") long)
      overlap     50
      step        (- viewport-h overlap)
      positions   (range 0 scroll-h step)
      paths       (vec
                    (for [[i pos] (map-indexed vector positions)]
                      (let [path (str "/tmp/scroll-" i ".png")]
                        (spel/evaluate (str "window.scrollTo(0, " pos ")"))
                        (spel/wait-for-load-state)
                        (spel/screenshot {:path path})
                        path)))]
  (stitch/stitch-vertical-overlap paths "/tmp/full-page.png" {:overlap-px overlap})
  (println "Stitched" (count paths) "screenshots"))
```

Captures page in viewport-sized chunks, scrolling by `viewport-height - overlap`, stitches with overlap trimming to remove duplicate content at boundaries.

---

## Video recording

Record browser sessions as WebM. Useful for debugging test failures, demos, CI artifacts.

### SCI eval mode

```clojure
;; Daemon mode: no start!/stop! needed
(spel/start-video-recording)
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
;; ... actions ...
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
```

`start-video-recording` closes current context → creates new one with video enabled. Page state (cookies, localStorage) resets.

### Options

```clojure
(spel/start-video-recording {:video-dir "/tmp/videos"
                              :video-size {:width 1280 :height 720}})
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:video-dir` | String | `"videos"` | Directory for video files |
| `:video-size` | Map | `{:width 1280 :height 720}` | Video resolution |

### Checking + finishing

```clojure
(spel/video-path)  ;; => "/tmp/videos/abc123.webm" or nil

(spel/finish-video-recording {:save-as "/tmp/demo.webm"})  ;; stop + copy
(spel/finish-video-recording)                                ;; stop, keep in :video-dir
```

`finish-video-recording` closes context (finalizing video) → creates fresh context + page without video. Can keep browsing after stopping.

### Library mode

Pass `:record-video-dir` when creating context:

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser
                              {:record-video-dir "videos"
                               :record-video-size {:width 1280 :height 720}})]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.org")
        ;; Video finalizes when context closes
        (core/video-save-as! pg "/tmp/recording.webm")))))
```

Video file incomplete until context closes. Call `video-save-as!` before closing, or retrieve path after context cleanup.

### Complete recording workflow

```clojure
;; eval-sci: record login flow (daemon manages browser)
(spel/start-video-recording {:video-dir "/tmp/videos"
                              :video-size {:width 1920 :height 1080}})
(spel/navigate "https://example.org/login")
(spel/wait-for-load-state)
(spel/fill "#email" "user@example.org")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-load-state :networkidle)

(let [result (spel/finish-video-recording {:save-as "/tmp/login-flow.webm"})]
  (println "Video saved:" (:video-path result)))
```

---

## Video with voiceover

spel records video only. No built-in audio capture or TTS. For narrated videos, combine spel video + external audio tools.

### Process

1. Record browser session with spel, add deliberate pauses between actions
2. Generate narration via TTS (macOS `say`, Linux `espeak`, API services)
3. Merge video + audio with ffmpeg

### Recording with pauses

```clojure
;; Daemon mode: no start!/stop! needed
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
(spel/evaluate "await new Promise(r => setTimeout(r, 3000))")
(spel/click "a.get-started")
(spel/wait-for-load-state)
(spel/evaluate "await new Promise(r => setTimeout(r, 3000))")
(spel/finish-video-recording {:save-as "/tmp/demo.webm"})
```

### Generating audio + merging

```bash
# macOS TTS
say -o /tmp/narration.aiff "Welcome to the demo. First we open the homepage."

# Linux TTS
espeak -w /tmp/narration.wav "Welcome to the demo. First we open the homepage."
# Merge video + audio with ffmpeg (-shortest stops at shorter stream)
ffmpeg -i /tmp/demo.webm -i /tmp/narration.mp3 \
  -c:v copy -c:a aac -shortest \
  /tmp/demo-with-narration.mp4
```
For higher quality, use API-based TTS (Google Cloud TTS, Amazon Polly, ElevenLabs).

### Scripted end-to-end

```bash
#!/bin/bash
spel eval-sci '
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/navigate "https://example.org")
(spel/wait-for-load-state)
(spel/evaluate "await new Promise(r => setTimeout(r, 3000))")
(spel/click "a.learn-more")
(spel/wait-for-load-state)
(spel/evaluate "await new Promise(r => setTimeout(r, 3000))")
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
'

say -o /tmp/narration.aiff \
  "This is the example dot com homepage. Now clicking Learn More to see the documentation."

ffmpeg -i /tmp/session.webm -i /tmp/narration.aiff \
  -c:v copy -c:a aac -shortest /tmp/final.mp4
```

> Tip: Match pause durations to narration length. ~3s per sentence works for most TTS voices.

---

## Action log + SRT subtitles

Daemon automatically tracks all user-facing browser commands (click, navigate, fill, etc.) with timestamps. Export as SRT subtitles for video overlays or JSON for session replay.

### Action log API

CLI:

```bash
# View action log as JSON
spel action-log
spel action-log --json

# Export as SRT subtitle format
spel action-log --srt

# Write to file
spel action-log --srt -o session.srt
spel action-log --json -o session.json

# Clear log (start fresh)
spel action-log --clear
```

eval-sci:

```clojure
;; Get action log entries
(spel/action-log)
;; => [{:idx 1, :timestamp 1709741234567, :time "2025-03-06T14:07:14.567Z",
;;      :action "navigate", :target nil,
;;      :args {"url" "https://example.org"},
;;      :url "https://example.org", :title "Example Domain",
;;      :snapshot "..."}]

;; Export as SRT string
(spel/export-srt)
;; => "1\n00:00:00,000 --> 00:00:02,000\nnavigate https://example.org\n\n2\n..."

;; Custom timing options
(spel/export-srt {:min-duration-ms 500 :max-duration-ms 8000})

;; Clear log
(spel/clear-action-log!)
```

### JSON export format

Each entry:

| Field | Type | Description |
|-------|------|-------------|
| `idx` | long | 1-based sequence number |
| `timestamp` | long | Epoch milliseconds |
| `time` | string | ISO 8601 timestamp (human-readable) |
| `action` | string | Command name ("click", "navigate", etc.) |
| `target` | string/nil | Ref or selector ("@e12345") |
| `args` | map/nil | Additional command arguments |
| `url` | string/nil | Page URL at time of action |
| `title` | string/nil | Page title at time of action |
| `snapshot` | string/nil | Post-action accessibility tree (when available) |

### Tracked commands

Navigate, click, fill, type, press, hover, check, uncheck, select, dblclick, focus, clear, screenshot, scroll, back, forward, reload, drag, tap, set-input-files.

Read-only commands (snapshot, evaluate, network, console, etc.) NOT tracked.

---

## Smooth video recording

For natural-looking video, use smooth scrolling + human-like pacing over instant jumps.

### Smooth scroll

```clojure
;; Scroll to absolute Y position (smooth CSS animation)
(spel/smooth-scroll 500)

;; Scroll with options
(spel/smooth-scroll {:top 500})       ;; scroll to Y=500
(spel/smooth-scroll {:delta-y 300})   ;; scroll down by 300px
```

### Human-like pacing

```clojure
;; Random pause (300-700ms default)
(spel/human-pause)

;; Custom range
(spel/human-pause 500 1000)  ;; 500-1000ms
```

### Example: smooth video session

```clojure
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/clear-action-log!)  ;; start fresh

(spel/navigate "https://example.org")
(spel/human-pause)

(spel/smooth-scroll 300)
(spel/human-pause)

(spel/click "a")
(spel/human-pause 500 1000)

;; Export SRT before finishing video
(spit "/tmp/session.srt" (spel/export-srt))
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
```

---

## FFmpeg post-processing

FFmpeg optional, useful for polishing recordings. spel does NOT depend on FFmpeg — use as external post-processing step.

### Burn in subtitles

```bash
# Burn SRT subtitles into video (hard subs)
ffmpeg -i session.webm -vf "subtitles=session.srt" -c:a copy output.mp4

# With styling (white text, semi-transparent black background)
ffmpeg -i session.webm \
  -vf "subtitles=session.srt:force_style='FontSize=18,PrimaryColour=&HFFFFFF&,BackColour=&H80000000&,BorderStyle=4'" \
  -c:a copy output.mp4
```

### Remove idle frames

When nothing happens on screen, trim dead time:

```bash
# Remove duplicate frames, re-encode at 30fps
ffmpeg -i session.webm -vf "mpdecimate,setpts=N/30/TB" -r 30 trimmed.mp4
```

### Speed up / slow down

```bash
# 2x speed
ffmpeg -i session.webm -vf "setpts=0.5*PTS" -af "atempo=2.0" fast.mp4

# 0.5x speed (slow motion)
ffmpeg -i session.webm -vf "setpts=2.0*PTS" -af "atempo=0.5" slow.mp4
```

### Concatenate segments

```bash
# Create file list
echo "file 'segment1.mp4'" > list.txt
echo "file 'segment2.mp4'" >> list.txt
echo "file 'segment3.mp4'" >> list.txt

# Concatenate
ffmpeg -f concat -safe 0 -i list.txt -c copy combined.mp4
```

### Full pipeline: record, SRT, trim, subtitle, narrate

```bash
#!/bin/bash
set -e

# 1. Record session with spel (produces video + action log)
spel eval-sci '
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/clear-action-log!)
(spel/navigate "https://example.org")
(spel/human-pause)
(spel/smooth-scroll 500)
(spel/human-pause)
(spel/click "a")
(spel/human-pause 500 1000)
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
'

# 2. Export SRT from action log
spel action-log --srt -o /tmp/session.srt

# 3. Remove idle frames
ffmpeg -i /tmp/session.webm -vf "mpdecimate,setpts=N/30/TB" -r 30 /tmp/trimmed.mp4

# 4. Burn in subtitles
ffmpeg -i /tmp/trimmed.mp4 -vf "subtitles=/tmp/session.srt" -c:a copy /tmp/final.mp4

# 5. (Optional) Add narration
say -o /tmp/narration.aiff "Welcome to the demo."
ffmpeg -i /tmp/final.mp4 -i /tmp/narration.aiff -c:v copy -c:a aac -shortest /tmp/narrated.mp4
```