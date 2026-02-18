#!/usr/bin/env bash
source "$(dirname "$0")/helpers.sh"
section "Help & Init-Agents (48)"

# ── spel --help ──────────────────────────────────────────────────────────────
OUT=$(timeout 5 "$SPEL" --help 2>&1)
assert_contains "spel --help → banner" "$OUT" "spel — Native browser automation CLI"
assert_contains "spel --help → lists init-agents tool" "$OUT" "init-agents"
assert_contains "spel --help → lists codegen tool" "$OUT" "codegen"
assert_contains "spel --help → shows --eval mode" "$OUT" "--eval"
assert_contains "spel --help → shows install" "$OUT" "install"

# ── spel -h same output ─────────────────────────────────────────────────────
OUT2=$(timeout 5 "$SPEL" -h 2>&1)
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$OUT" == "$OUT2" ]]; then
  pass "spel -h → identical to --help"
else
  fail "spel -h → identical to --help" "Output differs from --help"
fi

# ── init-agents --help (must NOT be the main help) ───────────────────────────
IA_HELP=$(timeout 5 "$SPEL" init-agents --help 2>&1)
assert_contains "init-agents --help → own banner" "$IA_HELP" "Scaffold E2E testing agents for your editor"
assert_contains "init-agents --help → --loop option documented" "$IA_HELP" "--loop TARGET"
assert_contains "init-agents --help → --ns option documented" "$IA_HELP" "--ns NS"
assert_contains "init-agents --help → --dry-run documented" "$IA_HELP" "--dry-run"
assert_contains "init-agents --help → --force documented" "$IA_HELP" "--force"
assert_contains "init-agents --help → opencode target paths" "$IA_HELP" ".opencode/agents/"
assert_contains "init-agents --help → claude target paths" "$IA_HELP" ".claude/agents/"
assert_contains "init-agents --help → vscode target paths" "$IA_HELP" ".github/agents/"
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$IA_HELP" != *"Native browser automation CLI"* ]]; then
  pass "init-agents --help → NOT the main spel help"
else
  fail "init-agents --help → NOT the main spel help" "Got main help instead of init-agents help"
fi

# ── init-agents --loop=bogus → error to stderr ──────────────────────────────
OUT=$(timeout 5 "$SPEL" init-agents --loop=bogus 2>&1)
EXIT_CODE=$?
assert_contains "init-agents --loop=bogus → error message" "$OUT" "Unknown --loop target: bogus"
assert_contains "init-agents --loop=bogus → lists valid targets" "$OUT" "claude, opencode, vscode"
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ $EXIT_CODE -ne 0 ]]; then
  pass "init-agents --loop=bogus → exit code non-zero"
else
  fail "init-agents --loop=bogus → exit code non-zero" "Got exit code 0"
fi

# ── init-agents --dry-run opencode paths ─────────────────────────────────────
OUT=$(timeout 5 "$SPEL" init-agents --ns test-app --dry-run 2>&1)
assert_contains "dry-run opencode → banner says OpenCode" "$OUT" "for OpenCode"
assert_contains "dry-run opencode → planner path" "$OUT" ".opencode/agents/spel-test-planner.md"
assert_contains "dry-run opencode → generator path" "$OUT" ".opencode/agents/spel-test-generator.md"
assert_contains "dry-run opencode → healer path" "$OUT" ".opencode/agents/spel-test-healer.md"
assert_contains "dry-run opencode → prompt path" "$OUT" ".opencode/prompts/spel-test-workflow.md"
assert_contains "dry-run opencode → skill path" "$OUT" ".opencode/skills/spel/SKILL.md"
assert_contains "dry-run opencode → seed test uses --ns" "$OUT" "test-e2e/test_app/e2e/seed_test.clj"
assert_contains "dry-run opencode → footer @mention" "$OUT" "@spel-test-planner"
assert_contains "dry-run opencode → next steps header" "$OUT" "Next steps:"
assert_contains "dry-run opencode → step: install browsers" "$OUT" "spel install --with-deps chromium"
assert_contains "dry-run opencode → step: deps.edn alias" "$OUT" ':e2e {:extra-paths ["test-e2e"]'
assert_contains "dry-run opencode → step: spel dep" "$OUT" "com.blockether/spel"
assert_contains "dry-run opencode → step: run command" "$OUT" "clojure -M:e2e --dir test-e2e"
assert_contains "dry-run opencode → step: update seed URL" "$OUT" "Update the seed test URL"

# ── init-agents --dry-run --loop=claude paths ────────────────────────────────
OUT=$(timeout 5 "$SPEL" init-agents --ns test-app --loop=claude --dry-run 2>&1)
assert_contains "dry-run claude → banner says Claude Code" "$OUT" "for Claude Code"
assert_contains "dry-run claude → agent dir" "$OUT" ".claude/agents/spel-test-planner.md"
assert_contains "dry-run claude → prompt dir" "$OUT" ".claude/prompts/spel-test-workflow.md"
assert_contains "dry-run claude → docs dir" "$OUT" ".claude/docs/spel/SKILL.md"
assert_contains "dry-run claude → generic footer" "$OUT" "Use the spel-test-planner agent"

# ── init-agents --dry-run --loop=vscode paths ────────────────────────────────
OUT=$(timeout 5 "$SPEL" init-agents --ns test-app --loop=vscode --dry-run 2>&1)
assert_contains "dry-run vscode → banner says VS Code" "$OUT" "for VS Code / Copilot"
assert_contains "dry-run vscode → .agent.md extension" "$OUT" ".github/agents/spel-test-planner.agent.md"
assert_contains "dry-run vscode → prompt dir" "$OUT" ".github/prompts/spel-test-workflow.md"
assert_contains "dry-run vscode → docs dir" "$OUT" ".github/docs/spel/SKILL.md"

# ── codegen --help ───────────────────────────────────────────────────────────
CG_HELP=$(timeout 5 "$SPEL" codegen --help 2>&1)
assert_contains "codegen --help → own banner" "$CG_HELP" "JSONL to Clojure transformer"
assert_contains "codegen --help → --format option" "$CG_HELP" "--format"
assert_contains "codegen --help → --output option" "$CG_HELP" "--output"
TOTAL_COUNT=$((TOTAL_COUNT + 1))
if [[ "$CG_HELP" != *"Native browser automation CLI"* ]]; then
  pass "codegen --help → NOT the main spel help"
else
  fail "codegen --help → NOT the main spel help" "Got main help instead of codegen help"
fi

print_summary
