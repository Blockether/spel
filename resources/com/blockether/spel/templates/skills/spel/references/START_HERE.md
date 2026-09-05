# Launch a task session

**Use when:** No task session exists. Otherwise continue the existing one.

Choose one launch mode below. The skill owns session names, safety and cleanup; retain the resolved name across shell calls.

```bash
SESSION="agent-$(date +%s)-$$"
export SPEL_SESSION="$SESSION"
spel --session "$SESSION" --content-boundaries open https://example.com &&
spel --session "$SESSION" --content-boundaries snapshot -i -c
# Work in this session, then close it.
spel --session "$SESSION" close
```

For an auto-launched Chrome or an authorized CDP connection, replace the `open` command with one of these (do not run all three):

```bash
spel --session "$SESSION" --auto-launch open https://example.com
spel --session "$SESSION" --cdp http://127.0.0.1:9222 open https://example.com
```

Read `references/PROFILES_CDP.md` for connection details and `references/SESSION_COMMON.md` for tab ownership and batching.