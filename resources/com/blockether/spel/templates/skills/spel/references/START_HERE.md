# Start Here

**Use when:** Choose a first launch command when no task session exists. If one is already open, continue it instead of restarting.

Launch examples for the common connection modes. The skill owns operating rules and reference routing; this page is not a prerequisite for every task.

Use an isolated session for ordinary work. Attach through CDP only when authorized; sessions may share an endpoint, but each must own its tab. Retain the resolved session name across commands.

## Typical starting patterns

```bash
# Resolve the name once. If later commands run in fresh shells, retain this
# resolved value in agent state; do not evaluate the timestamp again.
SESSION="exp-$(date +%s)"
spel --session "$SESSION" open https://example.com
spel --session "$SESSION" snapshot -i
spel --session "$SESSION" eval-sci '(spel/title)'
spel --session "$SESSION" close
```

```bash
SESSION="auto-$(date +%s)"
spel --session "$SESSION" --auto-launch open https://example.com
spel --session "$SESSION" --auto-launch snapshot -i
spel --session "$SESSION" close
```

```bash
# Explicit CDP endpoint:
SESSION="cdp-$(date +%s)"
spel --session "$SESSION" --cdp http://127.0.0.1:9222 open https://example.com
spel --session "$SESSION" --cdp http://127.0.0.1:9222 snapshot -i
spel --session "$SESSION" close
```