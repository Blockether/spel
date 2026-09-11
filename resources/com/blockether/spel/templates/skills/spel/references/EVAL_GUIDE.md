# SCI scripts

**Use when:** Writing or debugging `eval-sci` scripts. SCI's implicit-page API differs from the JVM library's explicit-page API.

## Run in the task session

```bash
spel --session "$SESSION" eval-sci '(spel/title)'
spel --session "$SESSION" eval-sci script.clj
printf '%s\n' '(spel/title)' | spel --session "$SESSION" eval-sci --stdin
```

The daemon owns the browser and reuses session state. Do not call `spel/start!`, `spel/stop!` or `spel/restart!` inside these scripts. Launch options belong on the session's first command; see `references/BROWSER_OPTIONS.md`. `--autoclose` ends the daemon after evaluation, so omit it when continuing the task.

## Discover before calling

```clojure
(spel/help "spel/click")       ; exact SCI signature and documentation
(spel/help "screenshot")       ; search names
(spel/source "spel/navigate") ; wrapper and delegation target
```

Use `spel/` for implicit current-page calls and ref/string/Locator resolution. Raw `page/`, `loc/`, `frame/`, `input/`, `net/` and `assert/` functions take explicit objects: inspect their signatures, do not substitute library arities. `(spel/help)` lists namespaces; `(spel/help "spel")` lists that namespace. `references/FULL_API.md` is a lookup, not a required read.

## Data and output

The last expression is the result. `--json` returns one JSON object with `result` (or `error` on failure); script prints appear in `stdout`. Browser console output goes to stderr. Without `--json`, Clojure values print as EDN, not JSON.

For an explicit JSON string in a script, the registered encoder is:

```clojure
(json/write-str {:ok true})
```

It returns `"{\"ok\":true}"`. The SCI `json/` namespace is not the full Charred library. `core/*json-encoder*` is the API body's encoder, not an unqualified binding.

Playwright evaluation may return Java maps/lists. Use `get` with string keys and `seq`/`into` for collections instead of assuming Clojure keyword lookup works. Use `slurp`, `spit` or `io/` for requested files; `str/`, `set/`, `walk/` and `zp/` are preloaded utilities.

## Language boundary

Registered namespaces can be `require`d and registered classes imported; aliases already work without setup. Local `defn` and `defmacro` work. Arbitrary dependencies, Maven loading and unregistered Java classes do not: use the project's JVM/library runner for those, not SCI.

Preserve the task's browser state and assert observable effects. For synchronization use `spel/wait-for-selector`, `spel/wait-for-url` or `spel/wait-for-function` tied to expected state, not fixed sleeps. See `references/NAVIGATION_WAIT.md`; snapshot refs are transient, not selectors for persisted tests.
