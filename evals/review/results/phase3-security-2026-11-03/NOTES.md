# Phase 3 run: the security-reviewer agent against control, judge v2

Run `phase3-security-2026-11-03`. Both arms judged with judge v2 on the 116-finding dataset.
`control` findings are the baseline's, re-judged; `security` is `.claude/agents/security-reviewer.md`
run fresh (snapshots with Read/Grep/Glob, diffs inlined). Reviewer cost: control $17.99, security $95.65.

## The rule and the result

Keep a specialised arm only if recall in its category rises over `control` AND its own precision is
not below `control`'s.

| | control | security |
|---|---|---|
| security recall (37 findings) | 51% | **80%** |
| overall precision | 73% | 62% |
| overall recall | 65% | 57% |
| cost per case | $0.72 | $3.83 |

**Verdict under the rule as written: not accepted.** Security recall clears the bar by 29 points; precision
is 11 points under it.

## Where the precision went

| arm | reported | credited | unverified | noise | precision | precision excl. unverified |
|---|---|---|---|---|---|---|
| control, all reports | 203 | 149 | 34 | 20 | 73% | 88% |
| security, all reports | 186 | 116 | 46 | 24 | 62% | 83% |
| security, security-category reports only | 117 | 70 | 31 | 16 | 60% | 81% |

The arm's *noise* rate (reports the judge refuted or found speculative) is 13% against
control's 10%: nearly the same. The gap is `unverified`: 46 of 186 security-arm reports
carry a `verification` caveat the judge could not settle from the cited file, against 34 of 203 for
control. The agent's prompt tells it to report what it cannot confirm with the exact check attached, and it
does so far more often than the plain prompt. Those reports are not refuted; they are unsettled, and the
scorecard deliberately refuses to credit them (that is where the sampled false positives hid).

## What the recall buys

Security findings hit by control: 21 of 37; by the agent: 30; by either: 31 (83%). The agent caught 10 that
control missed, including the one high-severity miss in the category (contact details stored and delivered
before moderation), the logout endpoint that matches any method and revokes nothing, per-instance login
counters, the rejected-upload object left in the bucket, and the messaging/preview endpoints without a limit.

Diff cases are the agent's weak spot: 40% recall and 35% precision on the four introducing-diff cases. A
security specialist reading a payment-logic PR diff has little to find and reports speculation.

## Options (owner decision)

1. **Keep as a specialist with a filter (recommended).** In the orchestrator, merge only its `security`
   -category findings, and route reports that carry a `verification` caveat into a separate "verify before
   merging" list instead of the findings list. On this run that presents 70 credited security
   findings and 16 noise (81% precision on what is shown as a finding) and keeps the 29-point
   recall gain. Zero re-run cost; the cost per PR is bounded by diff size, not by these audit-sized
   snapshots. Risk: the verify-list becomes ignored, and the per-case cost is 6x control.
2. **Revise the prompt and re-run.** Tell the agent to settle its own caveats with the tools it has
   before reporting (it has Read/Grep on snapshots and used them for recall, not for its caveats) and to
   stay quiet on diffs without a security surface. A targeted re-run on the 8 security cases + 4 diff
   cases is about $50. Risk: the stricter prompt also drops recall; the rule is then re-applied.
3. **Reject.** Keep `control` only. Loses 10 security findings, one high.

Options 1 and 2 are not exclusive: 1 now, 2 when the orchestrator exists and the verify-list's real
hit rate is known.
