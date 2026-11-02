# Baseline 2026-11-02: reading the numbers

Two arms over the 25 promoted cases (108 expected findings). Reviewer model: the session default (Fable 5.1 at the time); judge: same model, tool-less. Reviewer cost $32.52 for both arms, about $1.30 per case per arm; roughly 2h wall clock at two cases in parallel per arm.

## Headline

| arm | recall | precision | reports per case |
|---|---|---|---|
| control (plain prompt, tools on snapshots) | 62% | 81% | 8.1 |
| blind-hunter (mto layer A, tool-less, scoped files inlined) | 67% | 46% | 17.7 |

Blind Hunter buys 5 points of recall with more than twice the reports and 35 points less precision. In the mto skill's own history that is the shape that ended with reviewers ignoring the output. The control arm is the floor phase 3 must beat.

## Where recall is weak (control arm)

| category | expected | control recall | blind-hunter recall |
|---|---|---|---|
| security | 37 | 50% | 68% |
| logic | 51 | 76% | 71% |
| concurrency | 11 | 64% | 73% |
| edge-case | 6 | 33% | 50% |
| database / architecture / performance | 1 each | too few to read | |

Security is the largest weak category: 37 expected findings, half missed by the plain prompt. That makes the **security reviewer the first phase 3 candidate**, with the transaction/race reviewer second. Edge-case is weak in both arms but only six findings; the database category has one, so a migration/schema reviewer cannot be scored until cases are added from pre-October history.

## Missed by both arms: 18 of 108

Any-arm recall is 83%: the union of the two arms finds most of what either misses, which is the argument for a multi-reviewer orchestrator rather than a single better prompt. The 18 findings neither arm touched: by category {'logic': 6, 'security': 7, 'edge-case': 3, 'performance': 1, 'concurrency': 1}; by severity {'high': 3, 'low': 5, 'medium': 10}. High-severity misses:

- `242-logout-race-and-cookie-id` F4 (logic): Hand-built skp cookie writes the Long user id as a bare JSON number, losing precision in the browser
- `359-charge-idempotency-unknown-outcome` F8 (concurrency): Subscribe double-click creates two Stripe subscriptions and the loser's first invoice is not refunded
- `376-completion-dispute-integrity` F2 (logic): A coach no-show cannot be reported once the coach quick-completed

## The judge's `real` verdicts need a human sample

Unmatched reports: control 109, blind-hunter 359. The judge called 69 and 119 of them `real` defects outside the answer key. Precision above counts those as good. If a human sample of, say, 20 shows most are not real, precision for both arms drops and blind-hunter's drops furthest. Until that sample exists treat precision as an upper bound. Conversely, the ones that hold up are new dataset cases; 25 are high severity. Examples:

- [control] `242-logout-race-and-cookie-id`: Failed-login attempts are rolled back, so the login rate limit never triggers
- [control] `242-logout-race-and-cookie-id`: Logout does not revoke the live refresh token when the presented token was already rotated
- [control] `337-subscription-entitlement-integrity`: Tier string is stored unnormalised, so lowercase input rolls back the DB write after Stripe has charged
- [control] `340-unpaid-paused-grace-sweep`: Per-attempt random idempotency key lets a retried subscribe create and charge a duplicate Stripe subscription
- [control] `350-pack-refund-rounding-remainder`: Parent cancellation more than 24h ahead adds a pack session back that was never taken off
- [control] `351-paused-subscription-card-sync`: Successful synchronous invoice retry doesn't update local state, so the grace sweep can cancel a just-paid subscription
- [control] `381-auth-hardening-rate-limits-and-keys`: Login lock wait loop holds a pooled DB connection for up to 2s per request, so a same-key burst can use up the connection pool
- [control] `382-upload-content-verification`: After confirm, the still-valid presigned PUT URL can overwrite the object, getting around the size, quota and gallery checks

## Process notes

- One case (`340-unpaid-paused-grace-sweep`) first failed in both arms: the PR #337 diff is 445 KB because of a minified search index. `run.py` now drops docs/generated files from inlined diffs (203 KB after filtering) and pages oversized diffs from disk.
- Two cases (`242-logout-race-and-cookie-id`, `307-frontend-links-and-tus`) first produced no Blind Hunter result and possibly degraded control results: both arms materialised the same export at once and one deleted the tree the other was extracting. `materialize.sh` now takes a mkdir lock and renames a finished temp export into place; `run.py` no longer aborts a run on one case's failure. Both cases were re-run in both arms.
- Judge and reviewer are the same model. A judge from a different model family would remove one source of correlated error; worth doing before trusting precision differences under ~10 points.

## Update 2026-11-03: the `real` verdicts were sampled

`real-sample.md`: 20 items, 16 real, 2 known (expected findings of another case the judge could not see), 2 not-real. 90% holds up, so the precision figures above stand. Both not-real items had the same shape: the reviewer's own `verification` caveat named the exact check that falsifies the finding, and the tool-less judge credited it anyway. The judge now gets Read/Grep on the export and is told to run such checks (judge v2, from the next run; this baseline's verdicts are v1). 12 of the 16 real items are open defects at HEAD and are filed in `deferred-work.md`; all 16 become cases or added expected findings.
