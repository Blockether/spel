# Learnings

- AGENT_COMMON heading-count verification uses substring grep (`## Position annotations|...`), so embedded `### Position annotations...` inside other sections inflates the count and must be avoided.
- Consolidation insertion point for shared agent rules is strictly between `## Evidence capture` and `## Daemon notes`, with `---` separators between each inserted section.
- Session short-name registry in AGENT_COMMON now includes `product-analyst -> disc`; downstream templates can reference this alias for named sessions.
