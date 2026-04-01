(ns com.blockether.spel.markdownify
  "Convert HTML documents into readable Markdown using the browser runtime."
  (:require
   [com.blockether.anomaly.core :as anomaly]
   [clojure.string :as str]
   [com.blockether.spel.page :as page]
   [com.blockether.spel.snapshot :as snapshot]))

(def ^:private html->markdown-js
  (str/join
    "\n"
    ["(arg) => {"
     "  const html = arg && arg[0] != null ? String(arg[0]) : '';"
     "  const includeTitle = !!(arg && arg[1]);"
     "  const readableOnly = !!(arg && arg[2]);"
     "  const parser = new DOMParser();"
     "  const doc = parser.parseFromString(html, 'text/html');"
     "  const blocks = new Set(['article','aside','blockquote','body','center','div','dl','dt','dd','fieldset','figcaption','figure','footer','form','header','main','nav','ol','p','pre','section','table','tbody','td','tfoot','th','thead','tr','ul']);"
     "  const skip = new Set(['script','style','noscript','iframe','svg','canvas']);"
     "  function collapse(text) { return String(text || '').replace(/\\s+/g, ' ').trim(); }"
     "  function scoreNode(el) {"
     "    if (!el || !el.textContent) return 0;"
     "    const text = collapse(el.textContent);"
     "    if (!text) return 0;"
     "    const links = el.querySelectorAll ? el.querySelectorAll('a').length : 0;"
     "    const densityPenalty = Math.min(links * 10, 300);"
     "    return text.length - densityPenalty;"
     "  }"
     "  function pickRoot(document) {"
     "    if (!readableOnly) return document.body || document.documentElement;"
     "    const candidates = Array.from(document.querySelectorAll('article,main,[role=\"main\"],.article,.post,.content,.main,.main-content'));"
     "    let best = null;"
     "    let bestScore = 0;"
     "    candidates.forEach((el) => {"
     "      const s = scoreNode(el);"
     "      if (s > bestScore) { best = el; bestScore = s; }"
     "    });"
     "    if (best && bestScore > 200) return best;"
     "    return document.body || document.documentElement;"
     "  }"
     "  function pruneNoise(root) {"
     "    if (!readableOnly || !root || !root.querySelectorAll) return;"
     "    const noise = root.querySelectorAll('nav,aside,footer,[aria-hidden=\"true\"],.advertisement,.ads,.cookie,.cookie-banner');"
     "    noise.forEach((el) => el.remove());"
     "  }"
     "  function inline(node) {"
     "    if (!node) return '';"
     "    if (node.nodeType === Node.TEXT_NODE) return collapse(node.textContent);"
     "    if (node.nodeType !== Node.ELEMENT_NODE) return '';"
     "    const tag = node.tagName.toLowerCase();"
     "    if (skip.has(tag)) return '';"
     "    if (tag === 'br') return '\\n';"
     "    if (tag === 'code' && node.parentElement && node.parentElement.tagName.toLowerCase() !== 'pre') return '`' + collapse(node.textContent) + '`';"
     "    if (tag === 'a') {"
     "      const text = collapse(Array.from(node.childNodes).map(inline).join(' ')) || node.getAttribute('href') || '';"
     "      const href = node.getAttribute('href') || '';"
     "      return '[' + text + '](' + href + ')';"
     "    }"
     "    if (tag === 'img') return '![' + (node.getAttribute('alt') || '') + '](' + (node.getAttribute('src') || '') + ')';"
     "    const content = collapse(Array.from(node.childNodes).map(inline).join(' '));"
     "    if (tag === 'strong' || tag === 'b') return content ? '**' + content + '**' : '';"
     "    if (tag === 'em' || tag === 'i') return content ? '*' + content + '*' : '';"
     "    return content;"
     "  }"
     "  function render(node, depth) {"
     "    depth = depth || 0;"
     "    if (!node) return '';"
     "    if (node.nodeType === Node.TEXT_NODE) return collapse(node.textContent);"
     "    if (node.nodeType !== Node.ELEMENT_NODE) return '';"
     "    const tag = node.tagName.toLowerCase();"
     "    if (skip.has(tag)) return '';"
     "    if (/^h[1-6]$/.test(tag)) {"
     "      const level = Number(tag[1]);"
     "      const text = collapse(node.textContent);"
     "      return text ? '#'.repeat(level) + ' ' + text + '\\n\\n' : '';"
     "    }"
     "    if (tag === 'p') {"
     "      const text = collapse(Array.from(node.childNodes).map(inline).join(' '));"
     "      return text ? text + '\\n\\n' : '';"
     "    }"
     "    if (tag === 'blockquote') {"
     "      const text = collapse(Array.from(node.childNodes).map(inline).join(' '));"
     "      return text ? '> ' + text + '\\n\\n' : '';"
     "    }"
     "    if (tag === 'pre') {"
     "      const text = node.textContent || '';"
     "      return text ? '```\\n' + text.replace(/^\\n+|\\n+$/g, '') + '\\n```\\n\\n' : '';"
     "    }"
     "    if (tag === 'hr') return '---\\n\\n';"
     "    if (tag === 'ul' || tag === 'ol') {"
     "      const ordered = tag === 'ol';"
     "      const items = Array.from(node.children).filter((el) => el.tagName && el.tagName.toLowerCase() === 'li');"
     "      return items.map((li, idx) => {"
     "        const prefix = '  '.repeat(depth) + (ordered ? (idx + 1) + '. ' : '- ');"
     "        const text = collapse(Array.from(li.childNodes).map((child) => child.nodeType === Node.ELEMENT_NODE && ['ul','ol'].includes(child.tagName.toLowerCase()) ? '' : inline(child)).join(' '));"
     "        const nested = Array.from(li.children).filter((el) => ['ul','ol'].includes(el.tagName.toLowerCase())).map((el) => render(el, depth + 1)).join('');"
     "        return prefix + text + '\\n' + nested;"
     "      }).join('') + '\\n';"
     "    }"
     "    if (tag === 'table') {"
     "      const rowEls = Array.from(node.rows || []);"
     "      if (!rowEls.length) return '';"
     "      const hasHeader = rowEls.some((tr) => Array.from(tr.cells || []).some((cell) => cell.tagName && cell.tagName.toLowerCase() === 'th'));"
     "      if (!hasHeader) {"
     "        const lines = rowEls.map((tr) => collapse(Array.from(tr.cells || []).map((cell) => inline(cell)).join(' '))).filter(Boolean);"
     "        return lines.length ? lines.join('\\n\\n') + '\\n\\n' : '';"
     "      }"
     "      const rows = rowEls.map((tr) => Array.from(tr.cells || []).map((cell) => collapse(cell.textContent || ''))).filter((row) => row.length);"
     "      if (!rows.length) return '';"
     "      const header = rows[0];"
     "      const body = rows.slice(1);"
     "      const out = [];"
     "      out.push('| ' + header.join(' | ') + ' |');"
     "      out.push('| ' + header.map(() => '---').join(' | ') + ' |');"
     "      body.forEach((row) => out.push('| ' + row.join(' | ') + ' |'));"
     "      return out.join('\\n') + '\\n\\n';"
     "    }"
     "    const children = Array.from(node.childNodes).map((child) => {"
     "      if (child && child.nodeType === Node.ELEMENT_NODE) {"
     "        const childTag = child.tagName.toLowerCase();"
     "        if (blocks.has(childTag)) return render(child, depth);"
     "      }"
     "      return blocks.has(tag) ? render(child, depth) : inline(child);"
     "    }).join(blocks.has(tag) ? '' : ' ');"
     "    return children;"
     "  }"
     "  const parts = [];"
     "  const title = collapse(doc.title || '');"
     "  if (includeTitle && title) parts.push('# ' + title);"
     "  const root = pickRoot(doc);"
     "  pruneNoise(root);"
     "  Array.from(root ? root.childNodes : []).forEach((node) => {"
     "    const rendered = render(node, 0).trim();"
     "    if (rendered) parts.push(rendered);"
     "  });"
     "  const out = parts.join('\\n\\n').replace(/\\n{3,}/g, '\\n\\n').trim();"
     "  return out ? out + '\\n' : '';"
     "}"]))

(defn- normalize-text
  [s]
  (-> (or s "")
    (str/replace #"\s+" " ")
    str/trim))

(defn- heading-line->markdown
  [line]
  (when-let [[_ raw-name] (re-matches #"-\s+heading\s+\"(.*)\"\s+\[@[^\]]+\].*" line)]
    (let [name  (normalize-text raw-name)
          level (or (some-> (re-find #"\[level=([1-6])\]" line) second parse-long)
                  2)]
      (when (seq name)
        (str (apply str (repeat (long level) \#)) " " name)))))

(defn- link-line->markdown
  [line]
  (when-let [[_ raw-name url] (re-matches #"-\s+link\s+\"(.*)\"\s+\[@[^\]]+\]\s+\[url=([^\]]+)\].*" line)]
    (let [name (normalize-text raw-name)]
      (when (seq name)
        (str "[" name "](" url ")")))))

(defn- button-line->markdown
  [line]
  (when-let [[_ raw-name] (re-matches #"-\s+button\s+\"(.*)\"\s+\[@[^\]]+\].*" line)]
    (let [name (normalize-text raw-name)]
      (when (seq name)
        (str "**" name "**")))))

(defn- paragraph-line->markdown
  [line]
  (when-let [[_ raw-name] (re-matches #"-\s+paragraph\s+\"(.*)\"\s+\[@[^\]]+\].*" line)]
    (let [name (normalize-text raw-name)]
      (when (seq name)
        name))))

(defn- listitem-line->markdown
  [line]
  (when-let [[_ raw-name] (re-matches #"-\s+listitem\s+\"(.*)\"\s+\[@[^\]]+\].*" line)]
    (let [name (normalize-text raw-name)]
      (when (seq name)
        (str "- " name)))))

(defn- a11y-tree->markdown
  [tree]
  (let [add-line (fn [acc line]
                   (if (or (str/blank? line)
                         (= line (peek acc)))
                     acc
                     (conj acc line)))]
    (->> (or tree "")
      str/split-lines
      (map str/trim)
      (reduce (fn [acc line]
                (cond
                  (or (str/blank? line)
                    (str/starts-with? line "["))
                  acc

                  :else
                  (or (some-> (heading-line->markdown line) (->> (add-line acc)))
                    (some-> (link-line->markdown line) (->> (add-line acc)))
                    (some-> (button-line->markdown line) (->> (add-line acc)))
                    (some-> (paragraph-line->markdown line) (->> (add-line acc)))
                    (some-> (listitem-line->markdown line) (->> (add-line acc)))
                    acc)))
        [])
      (str/join "\n"))))

(defn page->markdown
  "Converts the current page into Markdown using the accessibility snapshot.

   Options:
   - :title? include document title as a top-level heading (default true)"
  ([pg] (page->markdown pg {}))
  ([pg {:keys [title?] :or {title? true}}]
   (let [snap (snapshot/capture-snapshot pg)]
     (if (anomaly/anomaly? snap)
       snap
       (let [title (when title? (normalize-text (page/title pg)))
             body  (str/trim (or (a11y-tree->markdown (:tree snap)) ""))
             parts (cond-> []
                     (seq title) (conj (str "# " title))
                     (seq body)  (conj body))]
         (if (seq parts)
           (str (str/join "\n\n" parts) "\n")
           ""))))))

(defn html->markdown
  "Converts an HTML string into Markdown using an existing Playwright page.

   Options:
   - :title? include document title as a top-level heading (default true)
   - :readable? prefer main content over full page chrome (default true)
   - :a11y? use accessibility-based extraction when HTML is the current page content (default true)"
  ([pg html] (html->markdown pg html {}))
  ([pg html {:keys [title? readable? a11y?] :or {title? true readable? true a11y? true}}]
   (let [same-as-page? (try
                         (= (str html) (page/content pg))
                         (catch Exception _
                           false))
         use-a11y?     (and a11y? readable? same-as-page?)
         md            (if use-a11y?
                         (page->markdown pg {:title? title?})
                         (page/evaluate pg html->markdown-js [html title? readable?]))]
     (if (and use-a11y?
           (string? md)
           (str/blank? md))
       (page/evaluate pg html->markdown-js [html title? readable?])
       md))))
