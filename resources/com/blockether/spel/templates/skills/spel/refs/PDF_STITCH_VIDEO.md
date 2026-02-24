# PDF Generation, Image Stitching, and Video Recording

Three capabilities for capturing and exporting browser content: page-to-PDF conversion, multi-screenshot stitching, and session video recording.

## PDF Generation

PDF output works **only in Chromium headless mode**. Firefox and WebKit don't support it.

### Basic Usage

```clojure
;; --eval (daemon running): save current page as PDF
(spel/goto "https://en.wikipedia.org/wiki/Clojure")
(spel/wait-for-load)
(spel/pdf {:path "/tmp/doc.pdf"})
```

String shorthand: `(spel/pdf "/tmp/doc.pdf")`. Without `:path`, returns `byte[]`.

CLI: `spel pdf /tmp/output.pdf`

Library:

```clojure
(core/with-testing-page [pg]
  (page/navigate pg "https://example.com")
  (page/pdf pg {:path "/tmp/doc.pdf" :format "A4"}))
```

### Full Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:path` | String | nil | Output file path. If nil, returns `byte[]` |
| `:format` | String | nil | Page format: `"A4"`, `"Letter"`, `"Legal"`, `"Tabloid"` |
| `:landscape` | Boolean | false | Landscape orientation |
| `:print-background` | Boolean | false | Include CSS backgrounds and images |
| `:page-ranges` | String | nil | Page range, e.g. `"1-3"`, `"1,3,5"` |
| `:header-template` | String | nil | HTML template for page header |
| `:footer-template` | String | nil | HTML template for page footer |
| `:prefer-css-page-size` | Boolean | false | Use CSS `@page` size over `:format` |
| `:width` | String | nil | Paper width, e.g. `"8.5in"` |
| `:height` | String | nil | Paper height, e.g. `"11in"` |
| `:scale` | Double | 1.0 | Scale of the webpage rendering (0.1 to 2.0) |

> **Note:** The `:margin` option isn't available on `page/pdf` directly. For margin control, use `report->pdf` (below) or CSS `@page` rules with `:prefer-css-page-size true`.

### Example with Headers and Footers

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

Header/footer templates support these CSS classes: `date`, `title`, `url`, `pageNumber`, `totalPages`.

---

## Custom HTML Reports to PDF

The report builder creates structured HTML documents from typed entries, then renders them to PDF. Combine screenshots, text, tables, and observations into a single document.

### Building HTML

`spel/report->html` takes a sequence of entry maps and returns an HTML string. No browser page needed.

```clojure
(let [html (spel/report->html
             [{:type :section :text "Audit Results" :level 1}
              {:type :text :text "Checked 15 pages for accessibility issues."}
              {:type :good :text "Color contrast" :items ["All text meets WCAG AA"]}
              {:type :issue :text "Missing alt text" :items ["hero-image.png" "logo.svg"]}])]
  (spit "/tmp/report.html" html))
```

### Rendering to PDF

`spel/report->pdf` loads the HTML into the current page and calls `page.pdf()`. Requires an active browser session.

```clojure
(spel/report->pdf
  [{:type :section :text "Test Results" :level 1}
   {:type :text :text "All tests passed."}]
  {:path "/tmp/results.pdf" :title "CI Report"})
```

Library (explicit page): `(annotate/report->pdf pg entries {:path "out.pdf" :title "Report" :format "A4" :margin {:top "20px" :bottom "20px" :left "20px" :right "20px"}})`

### Entry Types

| Type | Required Keys | Optional Keys | Renders As |
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

### Complete Example: Screenshots to PDF Report

```clojure
;; --eval: capture pages and build a PDF report
;; Daemon mode: omit start!/stop! — daemon owns the browser
(spel/goto "https://example.com")
(spel/wait-for-load)
(let [shot1 (spel/screenshot)  ;; returns byte[] when no :path given
      _     (spel/goto "https://example.com/about")
      _     (spel/wait-for-load)
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

## Image Stitching

Combine multiple screenshots into one tall image. Useful for virtual-scroll pages, infinite-scroll feeds, or content taller than the viewport.

Stitching uses Playwright internally: base64-encodes each image, renders them as `<img>` tags in HTML, then takes a full-page screenshot. No AWT or ImageIO dependency.

### Basic Vertical Stitch

```clojure
(stitch/stitch-vertical ["/tmp/top.png" "/tmp/mid.png" "/tmp/bot.png"] "/tmp/full.png")
```

Takes a vector of file paths and an output path. Returns the output path.

### Overlap Trimming

When you scroll and screenshot, subsequent images overlap with the previous one. `stitch-vertical-overlap` trims a fixed number of pixels from the top of each image after the first.

```clojure
(stitch/stitch-vertical-overlap
  ["/tmp/s1.png" "/tmp/s2.png" "/tmp/s3.png"]
  "/tmp/stitched.png"
  {:overlap-px 50})
```

### Reading Images

```clojure
(stitch/read-image "/tmp/screenshot.png")
;; => "iVBORw0KGgo..."  (base64 string)
```

### CLI

```bash
spel stitch top.png middle.png bottom.png -o full-page.png
spel stitch s1.png s2.png s3.png --overlap 50 -o stitched.png
```

### Complete Scrolling-Stitch Workflow

```clojure
;; --eval: scroll-capture a tall page
(spel/start!)
(spel/goto "https://news.ycombinator.com")
(spel/wait-for-load)

(let [viewport-h (-> (spel/eval-js "window.innerHeight") long)
      scroll-h   (-> (spel/eval-js "document.body.scrollHeight") long)
      overlap     50
      step        (- viewport-h overlap)
      positions   (range 0 scroll-h step)
      paths       (vec
                    (for [[i pos] (map-indexed vector positions)]
                      (let [path (str "/tmp/scroll-" i ".png")]
                        (spel/eval-js (str "window.scrollTo(0, " pos ")"))
                        (spel/wait-for-load)
                        (spel/screenshot {:path path})
                        path)))]
  (stitch/stitch-vertical-overlap paths "/tmp/full-page.png" {:overlap-px overlap})
  (println "Stitched" (count paths) "screenshots"))

(spel/stop!)
```

Captures the page in viewport-sized chunks, scrolling by `viewport-height - overlap` each time, then stitches with overlap trimming to remove duplicate content at boundaries.

---

## Video Recording

Record browser sessions as WebM video files. Useful for debugging test failures, creating demos, and CI artifacts.

### SCI Eval Mode

```clojure
(spel/start!)
(spel/start-video-recording)
(spel/goto "https://example.com")
(spel/wait-for-load)
;; ... actions ...
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
(spel/stop!)
```

`start-video-recording` closes the current context and creates a new one with video enabled. Page state (cookies, localStorage) resets.

### Options

```clojure
(spel/start-video-recording {:video-dir "/tmp/videos"
                              :video-size {:width 1280 :height 720}})
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:video-dir` | String | `"videos"` | Directory for video files |
| `:video-size` | Map | `{:width 1280 :height 720}` | Video resolution |

### Checking and Finishing

```clojure
(spel/video-path)  ;; => "/tmp/videos/abc123.webm" or nil

(spel/finish-video-recording {:save-as "/tmp/demo.webm"})  ;; stop + copy
(spel/finish-video-recording)                                ;; stop, keep in :video-dir
```

`finish-video-recording` closes the context (finalizing the video), then creates a fresh context and page without video. You can keep browsing after stopping.

### Library Mode

Pass `:record-video-dir` when creating a context:

```clojure
(core/with-playwright [pw]
  (core/with-browser [browser (core/launch-chromium pw {:headless true})]
    (core/with-context [ctx (core/new-context browser
                              {:record-video-dir "videos"
                               :record-video-size {:width 1280 :height 720}})]
      (core/with-page [pg (core/new-page-from-context ctx)]
        (page/navigate pg "https://example.com")
        ;; Video finalizes when context closes
        (core/video-save-as! pg "/tmp/recording.webm")))))
```

The video file isn't complete until the context closes. Call `video-save-as!` before closing, or retrieve the path after context cleanup.

### Complete Recording Workflow

```clojure
;; --eval: record a login flow
(spel/start!)
(spel/start-video-recording {:video-dir "/tmp/videos"
                              :video-size {:width 1920 :height 1080}})
(spel/goto "https://example.com/login")
(spel/wait-for-load)
(spel/fill "#email" "user@example.com")
(spel/fill "#password" "secret")
(spel/click "button[type=submit]")
(spel/wait-for-load "networkidle")

(let [result (spel/finish-video-recording {:save-as "/tmp/login-flow.webm"})]
  (println "Video saved:" (:video-path result)))
(spel/stop!)
```

---

## Video with Voiceover

spel records video only. There's no built-in audio capture or text-to-speech. To create narrated videos, combine spel's video output with external audio tools.

### The Process

1. **Record the browser session** with spel, adding deliberate pauses between actions
2. **Generate narration audio** using TTS (macOS `say`, Linux `espeak`, or API-based services)
3. **Merge video + audio** with ffmpeg

### Recording with Pauses

```clojure
(spel/start!)
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/goto "https://example.com")
(spel/wait-for-load)
(spel/eval-js "await new Promise(r => setTimeout(r, 3000))")
(spel/click "a.get-started")
(spel/wait-for-load)
(spel/eval-js "await new Promise(r => setTimeout(r, 3000))")
(spel/finish-video-recording {:save-as "/tmp/demo.webm"})
(spel/stop!)
```

### Generating Audio and Merging

```bash
# macOS TTS
say -o /tmp/narration.aiff "Welcome to the demo. First we open the homepage."

# Linux TTS
espeak -w /tmp/narration.wav "Welcome to the demo. First we open the homepage."
# Merge video + audio with ffmpeg (-shortest stops at the shorter stream)
ffmpeg -i /tmp/demo.webm -i /tmp/narration.mp3 \
  -c:v copy -c:a aac -shortest \
  /tmp/demo-with-narration.mp4
```
For higher quality, use API-based TTS (Google Cloud TTS, Amazon Polly, ElevenLabs).

### Scripted End-to-End

```bash
#!/bin/bash
spel --eval '
(spel/start!)
(spel/start-video-recording {:video-size {:width 1920 :height 1080}})
(spel/goto "https://example.com")
(spel/wait-for-load)
(spel/eval-js "await new Promise(r => setTimeout(r, 3000))")
(spel/click "a.learn-more")
(spel/wait-for-load)
(spel/eval-js "await new Promise(r => setTimeout(r, 3000))")
(spel/finish-video-recording {:save-as "/tmp/session.webm"})
(spel/stop!)
'

say -o /tmp/narration.aiff \
  "This is the example dot com homepage. Now clicking Learn More to see the documentation."

ffmpeg -i /tmp/session.webm -i /tmp/narration.aiff \
  -c:v copy -c:a aac -shortest /tmp/final.mp4
```

> **Tip:** Match pause durations to narration length. Three seconds per sentence works well for most TTS voices.
