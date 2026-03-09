# Google Search API

Search Google from the CLI, SCI `eval-sci` mode, or Clojure library — no API key required. Uses Playwright with stealth mode.

## CLI

```bash
spel search "clojure programming"                    # table output (default)
spel search "clojure" --json                          # JSON output
spel search "cats" --images                           # image search
spel search "world news" --news                       # news search
spel search "query" --page 2 --num 20                 # pagination
spel search "query" --max-pages 3 --json              # multi-page collect
spel search "query" --limit 5                         # first 5 results only
spel search "query" --open 1                          # navigate to result #1
spel search "query" --lang en --time-range week       # language + time filter
spel search "query" --screenshot results.png          # save screenshot
spel search "query" --no-stealth                      # disable stealth mode
```

| Flag | Description |
|------|-------------|
| `--images` | Image search |
| `--news` | News search |
| `--page N` | Results page (default: 1) |
| `--num N` | Results per page (default: 10) |
| `--max-pages N` | Collect N pages |
| `--limit N` | Show first N results |
| `--open N` | Navigate to result #N |
| `--json` | JSON output |
| `--screenshot PATH` | Save screenshot |
| `--lang LANG` | Language code |
| `--safe MODE` | Safe search: off, medium, high |
| `--time-range RANGE` | Time: day, week, month, year |
| `--no-stealth` | Disable stealth mode |

## SCI `eval-sci` mode

```clojure
;; Basic search (returns Clojure map)
(def r (search/search! "clojure programming"))
(:results r)    ;; => [{:title "..." :url "..." :snippet "..." :position 1} ...]
(:stats r)      ;; => "About 1,234,567 results (0.42 seconds)"

;; Image / news search
(search/search! "cats" {:type :images})
(search/search! "news" {:type :news})

;; Extract from current page (after search!)
(search/extract-web-results)          ;; web results
(search/extract-image-results)        ;; image results
(search/extract-news-results)         ;; news results
(search/extract-people-also-ask)      ;; PAA questions
(search/extract-related-searches)     ;; related queries
(search/extract-result-stats)         ;; result stats

;; Pagination
(search/has-next-page?)   ;; => true/false
(search/next-page!)       ;; navigate + extract next page
(search/go-to-page! "query" 3) ;; jump to page 3

;; Lazy pagination with iteration
(->> (search/search-pages "clojure")
     (take 3)
     (mapcat :results)
     (map :title))

;; Build URL only
(search/search-url "test" {:type :images :page 2})
```

## Library API

```clojure
(require '[com.blockether.spel.search :as search])

;; Single page search
(search/search! page "clojure" {:type :web :num 20})

;; Multi-page collect
(search/search-and-collect! page "clojure" {:type :web :max-pages 3})

;; Lazy pagination with iteration
(->> (search/search-pages page "clojure")
     (take 3)
     (mapcat :results))

;; Individual extractors (after navigating to Google results)
(search/extract-web-results page)
(search/extract-image-results page)
(search/extract-news-results page)
(search/extract-people-also-ask page)
(search/extract-related-searches page)
(search/has-next-page? page)
(search/next-page! page)
```
