# Lessons Learned: Agentic Browser Automation with spel

Hard-won lessons from an AI agent attempting to automate grocery shopping on frisco.pl.

## 1. Use Snapshots, Not Blind DOM Exploration

**What went wrong:** The agent used `eval-js` with speculative DOM queries (`document.querySelectorAll('article')`, walking `parentElement` chains) to find and click products. This is fragile, slow, and error-prone.

**What works:** spel's `snapshot` command returns a full accessibility tree with:
- Element roles and names
- `[@eXXXX]` refs that can be passed to `click @eXXXX`
- `[on:click]` event listener annotations showing what's interactive
- `[clickable]` for cursor:pointer elements
- `[url=...]` for links

**Rule:** Always `snapshot` first, read it, then `click @ref`. Never `eval-js` to find UI elements.

```bash
# BAD: blind DOM exploration
spel eval-js "(() => { const btns = document.querySelectorAll('span'); for (const s of btns) { if (s.textContent === 'Do koszyka') { s.click(); ... } } })()"

# GOOD: read the tree, click by ref
spel snapshot                    # read the output, find the right product
spel click @e61wc9               # click the specific "Do koszyka" button
```

## 2. Never Click the First Match

**What went wrong:** The agent searched for "Coccolino" and clicked the first "Do koszyka" — which might have been an ad (`REKLAMA`) for an unrelated product. Frisco search results include sponsored products at the top that don't match the query.

**What works:** Read all search results from the snapshot. Skip anything marked `REKLAMA`. Compare product names to the actual item wanted. Then click.

**Rule:** Always review ALL results before selecting. Ads appear as the first 1-2 results with `REKLAMA` label.

## 3. Frisco Search Results Structure

Each search result page contains:
- 1-2 **REKLAMA** (ad) products at the top — completely unrelated to search
- Actual search results below, each as an `article` element
- Each product card has:
  - Product image link → `/pid,XXXX/n,product-name/stn,product`
  - Brand name (heading level 3)
  - Product description (paragraph)
  - Weight/volume + unit price
  - Optional promo badges ("Frisco poleca", "Cena promocyjna")
  - `contentinfo` footer with "Do koszyka" button `[on:click]`

After clicking "Do koszyka", the card transforms: the button is replaced with quantity controls (`-` / textbox / `+`).

## 4. Basket Is a Side Panel, Not a Page

**What went wrong:** Navigating to `/stn,koszyk` shows "Ups... Nie znaleźliśmy strony" (404). The basket is not a standalone page.

**What works:** Click the "Mój koszyk" button in the header to open a side panel overlay. The side panel contains:
- `link "Wyczyść koszyk"` — clears entire basket
- `link "Pełny widok"` — full basket view
- Individual product items with `div "Usuń"` buttons
- Product links with `/pid,` URLs

**Rule:** To interact with the basket, click the header button, wait for the panel, then snapshot.

## 5. JVM Startup Kills Automation Speed

**What went wrong:** Every `clojure -M -m com.blockether.spel.native` call incurs ~5-10 second JVM startup. For 14 products × 2 calls each (search + click) = 28 calls × 7s = ~3 minutes of pure JVM overhead.

**What works:** Use the native-image binary (`spel`) which starts instantly. Or use `--session` to reuse the daemon and minimize overhead.

**Rule:** Always use the native binary for interactive/iterative work. Reserve `clojure -M` for development/testing only.

## 6. URL Encoding for Polish Characters

Frisco search URLs require URL-encoded Polish characters:

| Char | Encoded |
|------|---------|
| ą | %c4%85 |
| ć | %c4%87 |
| ę | %c4%99 |
| ł | %c5%82 |
| ń | %c5%84 |
| ó | %c3%b3 |
| ś | %c5%9b |
| ź | %c5%ba |
| ż | %c5%bc |
| ä | %c3%a4 |

Search URL pattern: `https://www.frisco.pl/q,ENCODED_QUERY/stn,searchResults`

## 7. CDP Connection Pitfalls

- Use `127.0.0.1`, **never** `localhost` — IPv6 resolution causes ECONNREFUSED
- Chrome must be started with `--remote-debugging-port=9222`
- CDP reuses existing tabs — don't expect a fresh page, use `open` to navigate
- `--user-data-dir=/tmp/spel-cdp-profile` for a clean profile, or point to real Chrome profile

## 8. Snapshot Quality Matters

Lessons from improving snapshots:
- `textContent` concatenates child text without spaces → garbled previews like `"opiniiREKLAMAJAN NIEZBĘDNY"`. Use `innerText` which respects layout.
- `cursor:pointer` inherits to ALL children via CSS → every `span` inside a `button` gets `[clickable]`. Must check ancestors to avoid noise.
- Event listener detection via `__reactProps$` catches React handlers that aren't visible as HTML attributes — critical for SPAs like frisco.pl.

## 9. Agent Delegation Failures

**What went wrong:** Delegating the shopping task to a background agent failed because:
1. The agent tried to plan first (wasting time) instead of executing
2. JVM startup per command meant the agent hit timeout limits
3. The agent couldn't read snapshot output effectively — it used `eval-js` DOM exploration instead
4. No feedback loop — clicking "first match" without reviewing results

**What works:** For interactive browser automation:
- Do it directly, not via delegation
- Use snapshot → review → click pattern
- Use native binary for speed
- Show the human what you're about to click

## 10. The Shopping List Mapping Problem

**What went wrong:** Searching for generic terms matched wrong products:
- "karma hipoalergiczna" → matched Whiskas (cat food) instead of dog food
- "pieprz czarny ziarnisty" → matched Prymat instead of Kamis
- "płyty indukcyjne" → matched Cillit Bang instead of Dr. Beckmann
- "barwnik" → matched any food coloring, not specifically yellow

**What works:** Use specific brand + product name in search. If the exact product isn't found, show alternatives to the human rather than auto-selecting.

**Rule:** Search with the most specific terms first (brand + full product name). Only broaden if no results. Never auto-substitute without review.

## Summary: The Right Automation Pattern

```
for each product on the list:
  1. search(brand + specific product name)
  2. snapshot → READ the results
  3. skip REKLAMA ads
  4. find the exact product (or best alternative)
  5. show to human if uncertain
  6. click @ref for "Do koszyka"
  7. snapshot → verify it was added (quantity controls visible)
```
