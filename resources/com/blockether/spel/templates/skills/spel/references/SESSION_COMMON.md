# Common session and automation patterns

**Use when:** Resolve session ownership, batching and cleanup questions. Reuse the task session rather than creating one for each example.

Shared conventions for reliable spel usage.

## Session isolation

Resolve one named session per task and retain it across commands. Never use the shared default session; close only the session you created.

```bash
SESSION="run-$(date +%s)"
spel --session "$SESSION" open https://example.com
# ... work ...
spel --session "$SESSION" close
```

## CDP safety

- Sessions may share one CDP endpoint — each opens its own tab and never touches another's.
- Only a TAB is exclusive: `network route` intercepts every tab THIS session drives, so two sessions
  that end up on the same tab queue behind each other's routes (`spel tab new` gives a session its own).
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

Produce the artifacts requested for this task and verify they exist and open. Screenshots, logs and reports are alternatives according to scope, not a mandatory bundle for every browser action.

## Troubleshooting basics

- Inspect `spel --session <name> health --json` and `spel --session <name> logs -n 100`.
- Cancel only the in-flight command id belonging to this task; retry after diagnosing the failure.
- Close only your session. Never remove sockets/pids manually or kill browser processes globally; use `kill` only for a verified spel daemon you own.
