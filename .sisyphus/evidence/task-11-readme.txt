TASK 11: Update README.md for 8-agent consolidated set
========================================================

VERIFICATION RESULTS:
✓ grep -c '14 agents' README.md → 0
✓ grep -c 'spec-skeptic|bug-skeptic|bug-referee|test-healer|test-orchestrator|qa-orchestrator|test-generator' README.md → 0

CHANGES MADE:
=============

1. Agent count updates (3 locations):
   - Line 184: "all 14 agents" → "all 8 agents"
   - Line 187: "# Full scaffolding with all 14 agents" → "# Full scaffolding with all 8 agents"
   - Line 364: "# all 14 agents (default)" → "# all 8 agents (default)"

2. Removed old agent references from examples:
   - Removed: "spel init-agents --only=test,spec-skeptic"
   - Updated: "spel init-agents --only=orchestrator" comment from "all 3 orchestrator agents only" → "orchestrator agent only"

3. Updated --only GROUPS flag description (line 383):
   - Removed: spec-skeptic
   - Final list: test, automation, visual, bugfind, orchestrator, discovery, core

4. Orchestrator Agents table (lines 398-399):
   - Reduced from 4 rows to 1 row
   - Kept only: @spel-orchestrator (Meta-orchestrator)
   - Removed: @spel-test-orchestrator, @spel-qa-orchestrator, duplicate @spel-orchestrator

5. Subagent Groups table (lines 407-413):
   - Updated 6 groups with new agent names:
     * test: planner, writer (was: planner, generator, healer)
     * automation: explorer, automator (unchanged)
     * visual: presenter (unchanged)
     * bugfind: bug-hunter (was: bug-hunter, bug-skeptic, bug-referee)
     * orchestrator: orchestrator (was: orchestrator, test-orch, qa-orch)
     * discovery: product-analyst (unchanged)

VERIFICATION GREP RESULTS:
==========================
$ grep -c '14 agents' README.md
0

$ grep -c 'spec-skeptic|bug-skeptic|bug-referee|test-healer|test-orchestrator|qa-orchestrator|test-generator' README.md
0

$ grep '8 agents' README.md
Scaffold agent skills (all 8 agents by default — use `--simplified` for the 6-agent core setup, `--only` to scaffold a subset, or `--no-tests` to skip the seed test file):
# Full scaffolding with all 8 agents (default)
spel init-agents                              # all 8 agents (default)

SUBAGENT GROUPS TABLE (verified):
| Group | Agents | Use for |
| `test` | planner, writer | E2E test writing |
| `automation` | explorer, automator | Browser automation |
| `visual` | presenter | Visual content |
| `bugfind` | bug-hunter | Adversarial bug finding |
| `orchestrator` | orchestrator | Smart routing |
| `discovery` | product-analyst | Product feature inventory + coherence audit |

ORCHESTRATOR AGENTS TABLE (verified):
| Agent | Purpose |
| `@spel-orchestrator` | **Meta-orchestrator** — analyzes your request and routes to the right pipeline |

STATUS: ✓ COMPLETE
All requirements met. No narrative sections rewritten. Structured tables updated correctly.
