(ns com.blockether.spel.fonts
  "The type system shipped WITH every generated artifact instead of fetched.

   Reports, annotated snapshots and presenter decks are artifacts: they get
   zipped, emailed, opened offline, attached to a CI run and re-read years
   later. A `fonts.googleapis.com` link makes their typography — and therefore
   their layout — decay outside the network they were generated on, and it
   leaks a request to a third party every time someone opens one. So the faces
   below live in `resources/com/blockether/spel/fonts` and are inlined into the
   HTML as `data:` URIs.

   Only the `latin` and `latin-ext` subsets of the variable (wght axis) fonts
   are bundled — together ~189 KB of woff2, ~250 KB base64 — which covers every
   character these documents emit while keeping the single-file artifact small.
   Italics are synthesized. Both families are SIL OFL 1.1; LICENSE-Inter.txt
   and LICENSE-JetBrainsMono.txt ship beside the files."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private latin-range
  (str "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,"
    "U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,"
    "U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"))

(def ^:private latin-ext-range
  (str "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,"
    "U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,"
    "U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"))

(def bundled-font-faces
  "Every bundled face: family, variable weight range, woff2 file, subset range."
  [{:family "Inter Variable"
    :weight "100 900"
    :file "inter-latin-wght-normal.woff2"
    :unicode-range latin-range}
   {:family "Inter Variable"
    :weight "100 900"
    :file "inter-latin-ext-wght-normal.woff2"
    :unicode-range latin-ext-range}
   {:family "JetBrains Mono Variable"
    :weight "100 800"
    :file "jetbrains-mono-latin-wght-normal.woff2"
    :unicode-range latin-range}
   {:family "JetBrains Mono Variable"
    :weight "100 800"
    :file "jetbrains-mono-latin-ext-wght-normal.woff2"
    :unicode-range latin-ext-range}])

(defn data-uri
  "Base64 `data:` URI for a bundled woff2, or nil when the resource is absent.
   Absence is tolerated (the CSS stack still falls back to system fonts)
   rather than fatal, so a trimmed uberjar can never break artifact writing."
  [file]
  (when-some [res (io/resource (str "com/blockether/spel/fonts/" file))]
    (try
      (with-open [in (io/input-stream res)]
        (str "data:font/woff2;base64,"
          (.encodeToString (java.util.Base64/getEncoder) (.readAllBytes in))))
      (catch Exception _ nil))))

(def bundled-font-css
  "`@font-face` block for the bundled faces, computed once per JVM."
  (delay
    (str/join "\n"
      (keep (fn [{:keys [family weight file unicode-range]}]
              (when-some [uri (data-uri file)]
                (str "@font-face{font-family:'" family "';font-style:normal;"
                  "font-display:swap;font-weight:" weight ";"
                  "src:url(" uri ") format('woff2-variations');"
                  "unicode-range:" unicode-range ";}")))
        bundled-font-faces))))

(defn style-tag
  "The bundled `@font-face` rules as a ready-to-embed `<style>` element."
  []
  (str "<style id=\"bundled-fonts\">" @bundled-font-css "</style>"))

(def body-stack
  "Font stack for prose and UI chrome."
  "'Inter Variable','Inter','Segoe UI',system-ui,sans-serif")

(def mono-stack
  "Font stack for code, captions and tabular data."
  "'JetBrains Mono Variable','JetBrains Mono',ui-monospace,SFMono-Regular,monospace")
