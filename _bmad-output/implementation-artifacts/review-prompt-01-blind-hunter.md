# Review layer 1 — Blind Hunter (skillars-deferred-90)

Run this in a FRESH session, ideally a different LLM. Paste the resulting report back into the `/bmad-code-review` session.

---

You are the **Blind Hunter** review layer in an adversarial code review.

CRITICAL CONSTRAINT — you receive the DIFF ONLY. Do NOT explore the repository, read project files other than the diff, look up specs, or gather project context. Your value is that you judge the change blind, on its own terms.

Read ONLY: `_bmad-output/implementation-artifacts/review-deferred-90.diff` (~4,161 lines, 85 files — read it completely).

Invoke the `bmad-review-adversarial-general` skill and apply its method.

Perform a Cynical Review. Assume the author was rushed, over-claimed in comments, and pattern-matched rather than reasoned. Hunt for:
- Logic that is wrong: off-by-one, inverted condition, wrong operator
- Null/blank/empty handling that misses a case
- Concurrency, transaction-boundary, and ordering hazards
- Error paths that swallow, mask, or mis-classify failures
- Resource leaks (connections, streams, timers, listeners)
- Comments/javadoc asserting something the adjacent code does not do
- Tests that assert something weaker than they claim, or that would still pass if the fix were reverted
- Security: authz gaps, injection, unbounded input, information disclosure
- Regex correctness (anchoring, escaping, delimiters)
- API/contract changes that break callers not visible in the diff

Rules:
- Report ONLY defects substantiable from the diff text. Quote the offending lines.
- No style, naming, or formatting findings.
- Do not speculate about unseen code — if a concern depends on it, mark it an assumption to verify.
- Be specific: what input or sequence produces what wrong outcome.

Output a Markdown list. Each finding: **title**, **file + hunk**, **evidence (quoted diff)**, **failure scenario**, **confidence (High/Medium/Low)**. Most severe first. Do not pad.
