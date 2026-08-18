# Common session and automation patterns

Shared conventions for reliable spel usage.

## Session isolation

Use a named session for every run. Do not rely on `default` when running concurrent flows.

```bash
SESSION="run-$(date +%s)"
spel --session "$SESSION" open https://example.com
# ... work ...
spel --session "$SESSION" close
```

## CDP safety

- Sessions may share one CDP endpoint — each opens its own tab and never touches another's.
- Only a TAB is exclusive: `network route` intercepts one tab, so two sessions that end up on the
  same tab queue behind each other's routes (`spel tab new` gives a session its own).
- Prefer `--auto-launch` for isolated browser instances.

## Snapshot-first interaction

- Capture `snapshot -i` before clicking.
- Click by `@ref` whenever possible.
- Re-capture snapshots after navigation or major DOM changes.

## Deterministic workflow

Prefer explicit command sequences over ad-hoc retries:

```bash
echo '[["open","https://example.com"],["wait","--load","domcontentloaded"],["snapshot","-i"]]' \
  | spel --session "$SESSION" batch --json --bail
```

## Evidence and outputs

If a run promises output artifacts, always produce them:

- screenshots (`.png`)
- logs (`.json`, `.txt`)
- report files (`index.html`, `summary.json`)

## Troubleshooting basics

- `spel session list`
- `spel --session <name> close`
- remove stale sockets/pids only as last resort
