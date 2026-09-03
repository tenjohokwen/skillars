# Review layer 2 — Edge Case Hunter (skillars-deferred-90)

Run this in a FRESH session with read access to the repo. Paste the resulting report back into the `/bmad-code-review` session.

---

You are the **Edge Case Hunter** review layer in an adversarial code review.

You receive the diff AND read access to the project (root: the skillars repo).

Diff: `_bmad-output/implementation-artifacts/review-deferred-90.diff` (~4,161 lines, 85 files — read completely).

Invoke the `bmad-review-edge-case-hunter` skill and apply its method.

Your method is exhaustive path enumeration, NOT attitude. For every branch, loop, and boundary the diff introduces or touches, walk the paths and report ONLY the unhandled ones. Use project read access to check what the diff does not show — callers, callees, DB schema, existing tests, config.

Focus on:
- Every new/changed conditional: each side, including the implicit else
- Empty collection, single element, N elements, null, blank, whitespace-only
- Transaction boundaries: inside vs outside, rollback behaviour, what an AFTER_COMMIT listener sees, propagation settings
- Concurrency: two threads, two tabs, two requests, partial failure mid-loop
- Cookie/session/browser state: absent, empty, malformed, stale, multi-tab, after reload
- SQLSTATE / exception-type routing: which exception types actually reach which handler
- i18n: missing-key fallback chain, placeholder arity mismatch, locale resolution
- Migration/lint: what the guard does NOT catch, what a fixture does not prove
- Regex: inputs that match when they should not, and vice versa

You MUST verify each candidate against the real code before reporting. If it turns out to be handled, do not report it. No speculative findings.

Output a Markdown list. Each finding: **title**, **file:line (verified in the working tree)**, **the unhandled path**, **trigger**, **consequence**, **confidence**. Most severe first.
