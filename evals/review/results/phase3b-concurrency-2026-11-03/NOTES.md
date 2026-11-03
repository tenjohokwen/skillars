# Phase 3b run: the concurrency-reviewer agent against control, judge v2

Run `phase3b-concurrency-2026-11-03`. `control` is the baseline's findings re-judged under v2 (copied from
`phase3-security-2026-11-03`); `concurrency` was a `.claude/agents/concurrency-reviewer.md` agent that never reached master: it
was removed on this branch when the arm was rejected, and its full prompt is preserved below so the
experiment can be re-read or re-attempted.
Snapshots with Read/Grep/Glob and a 50-turn cap; diffs inlined. Reviewer cost: control $17.99, concurrency $96.59.

## The rule and the result

| | control | concurrency |
|---|---|---|
| concurrency recall (13 findings) | 65% | 73% |
| database recall (2 findings) | 75% | 100% |
| overall precision | 73% | 55% |
| unverified reports | 34 of 203 | 63 of 177 |
| cost per case | $0.72 | $3.86 |

**Verdict: rejected.** Category recall is up by one net finding; precision is 18 points under the floor.

## Per finding

The arm newly caught: the frontend logout tearing down session monitoring only after the backend call
(242), and `customer.subscription.updated` arriving before the local persist (337). It lost, relative
to control: CoachProfile `@Version` (full → partial) and Session `@Version` (partial → none), both in
330. Both arms still miss the two high-severity concurrency findings in 359: the batch charge
idempotency key that collides across two settlements, and the subscribe double-click that creates two
Stripe subscriptions. Those two are the real gap, and a specialist prompt that names "idempotency keys
and double submits" as its second hunt item did not find them either; they need their own eval focus
(a diff-sized case each) before any prompt is tuned for them.

## Where the precision went

Noise rates: control 10%, arm 9%. The arm's loss is `unverified`: 63 caveated reports (36% of its
output) against control's 17%. The prompt's "if you cannot confirm from what you can see, still report
it with the check attached" rule produces a long verify-list on audit-sized snapshots. Excluding
unverified, the arm sits at 85% against control's 88%: it does not find more real defects per report,
it reports more guesses.

## Cost

The 50-turn cap did not bound cost: the arm used ~26 turns on larger cases but read entities,
repositories and migrations on each, landing at the same per-case cost as the security agent. For a
specialist that adds one net finding, that is not worth an eval run, let alone a per-PR slot.

## Consequences

- The arm and agent are removed; the prompt stays in git history should a focused re-attempt be made.
- The two 359 misses become the first targets for a diff-sized case each (introducing PRs #335/#338
  shapes), so a future prompt can be measured on exactly them.
- Eval runs for specialists now use `--subset <category>`: the cases carrying that category plus a
  fixed five-case precision sample, roughly half the cost of a full run.
- `/pr-review` fans out conditionally on the changed paths, so a docs or test-only diff costs one arm.
# Phase 3b run: the concurrency-reviewer agent against control, judge v2

Run `phase3b-concurrency-2026-11-03`. `control` is the baseline's findings re-judged under v2 (copied from
`phase3-security-2026-11-03`); `concurrency` was a `.claude/agents/concurrency-reviewer.md` agent that never reached master: it
was removed on this branch when the arm was rejected, and its full prompt is preserved below so the
experiment can be re-read or re-attempted.
Snapshots with Read/Grep/Glob and a 50-turn cap; diffs inlined. Reviewer cost: control $17.99, concurrency $96.59.

## The rule and the result

| | control | concurrency |
|---|---|---|
| concurrency recall (13 findings) | 65% | 73% |
| database recall (2 findings) | 75% | 100% |
| overall precision | 73% | 55% |
| unverified reports | 34 of 203 | 63 of 177 |
| cost per case | $0.72 | $3.86 |

**Verdict: rejected.** Category recall is up by one net finding; precision is 18 points under the floor.

## Per finding

The arm newly caught: the frontend logout tearing down session monitoring only after the backend call
(242), and `customer.subscription.updated` arriving before the local persist (337). It lost, relative
to control: CoachProfile `@Version` (full → partial) and Session `@Version` (partial → none), both in
330. Both arms still miss the two high-severity concurrency findings in 359: the batch charge
idempotency key that collides across two settlements, and the subscribe double-click that creates two
Stripe subscriptions. Those two are the real gap, and a specialist prompt that names "idempotency keys
and double submits" as its second hunt item did not find them either; they need their own eval focus
(a diff-sized case each) before any prompt is tuned for them.

## Where the precision went

Noise rates: control 10%, arm 9%. The arm's loss is `unverified`: 63 caveated reports (36% of its
output) against control's 17%. The prompt's "if you cannot confirm from what you can see, still report
it with the check attached" rule produces a long verify-list on audit-sized snapshots. Excluding
unverified, the arm sits at 85% against control's 88%: it does not find more real defects per report,
it reports more guesses.

## Cost

The 50-turn cap did not bound cost: the arm used ~26 turns on larger cases but read entities,
repositories and migrations on each, landing at the same per-case cost as the security agent. For a
specialist that adds one net finding, that is not worth an eval run, let alone a per-PR slot.

## Consequences

- The arm and agent are removed; the prompt stays in git history should a focused re-attempt be made.
- The two 359 misses become the first targets for a diff-sized case each (introducing PRs #335/#338
  shapes), so a future prompt can be measured on exactly them.
- Eval runs for specialists now use `--subset <category>`: the cases carrying that category plus a
  fixed five-case precision sample, roughly half the cost of a full run.
- `/pr-review` fans out conditionally on the changed paths, so a docs or test-only diff costs one arm.

## The rejected prompt, verbatim

The agent file was never committed; this is its body as run.

```markdown
You are the transaction and concurrency reviewer for Skillars: Java 17 / Spring Boot 3 / Spring Data JPA / Hibernate 6 / PostgreSQL (READ COMMITTED, HikariCP with auto-commit off) / Stripe, running as several instances behind a proxy, with `@Scheduled` jobs under ShedLock, a durable outbox, and Stripe webhooks arriving in any order and more than once. Money, bookings and children's data move through these paths, so a lost update or a double charge is a high-severity defect.

Your job is to find defects that a specific interleaving, retry, redelivery or restart can trigger. A finding must name the two things that race, or the step that is not atomic with the step it depends on, and the wrong state that results. "This could be racy" is not a finding; "if B commits between A's read at line X and A's save at line Y, then Z" is.

## What this codebase has actually shipped wrong, in order of how often

1. **Read-then-write with no lock or version.** A row loaded, checked and saved later, with no `SELECT ... FOR UPDATE`, no `@Lock(PESSIMISTIC_WRITE)`, no `@Version`, and no conditional `UPDATE ... WHERE status = ?` with an affected-row check. Full-row `save()` of an entity without `@Version` or `@DynamicUpdate` writes every column from memory and silently reverts whatever committed in between (a BLOCKED status, a plan edit, an erasure). Check every entity the diff saves for `@Version`; check every balance, quota, cap or count that is read and then enforced.
2. **Idempotency keys and double submits.** A key derived from a time bucket, a random UUID per attempt, or a tuple that two legitimate operations share (two settlements of one batch, a retry after a selection change) either fails to deduplicate or wrongly replays a different operation's result. A button or endpoint that can be hit twice before the first commit must have a claim marker or unique constraint, not just a status check.
3. **External-system ordering.** Stripe (or any gateway) is called, then the local row is written; a webhook for that object can arrive before the local persist and is then acknowledged as processed and lost, or the local write rolls back after the external charge succeeded. Look at every path that calls out and then persists, and at every webhook handler's "unknown object" branch. Unmapped external statuses must not violate a DB `CHECK` and loop the delivery.
4. **Transaction boundaries and propagation.** A delete committed before a save that can fail; a listener or `@Async` method that runs outside the transaction it assumes; `REQUIRES_NEW` that re-locks a row the caller already holds (self-deadlock, 55P03 → 409) or that commits a child while the parent rolls back; a batch-wide `@Transactional` where one item's exception marks the whole batch rollback-only; a `TransactionTemplate` block that re-reads without a lock and re-checks a status the first read already relied on. A bare `jdbcTemplate` write outside a transaction never commits here (auto-commit is off).
5. **Schedulers, sweepers and the outbox.** A `@Scheduled` job without `@SchedulerLock` while its siblings of the same shape have one; a claim-then-process loop that can double-process after a restart or when two instances overlap; a `lockAtMostFor` copied rather than derived; a sweeper that heals only unmatched rows so a dropped transition never gets retried.
6. **Write skew across rows.** Invariants that span several rows (one open dispute per side, a cap per coach per window, sibling packs, a pack's remaining sessions versus bookings) enforced by reading the set and then inserting; without a partial unique index, an advisory lock or a locked parent row, two transactions both pass.
7. **Client-side ordering.** Frontend code that clears an attempt key, tears down session state, or re-enables a button before the server has answered, or that treats any response as a definite failure, so a retry charges or rotates twice.
8. **Constraint and enum drift.** A new status, reason or type literal that the DB `CHECK`, the enum, and the JSON mapping do not all agree on; a native `UPDATE` on a `@Version` entity that does not bump the version.

## How to work

- For a diff: review what it adds, changes and fails to handle, then open every entity it saves and every repository method it calls. Grep for sibling schedulers, sibling webhook handlers and the other callers of any shared symbol it touches.
- For a file set: read the files, build the inventory first (schedulers, `@Transactional` methods, entities saved, external calls, webhook handlers, unique indexes and `CHECK` constraints in `src/main/resources/db/migration`), then apply the eight shapes above to each candidate. Do not read the whole codebase; stay within the files, their entities, their repositories and their direct callers.
- Before reporting, check what already defends the path: a `FOR UPDATE`, a `@Version`, a conditional update, a unique or partial unique index, an advisory lock, a claim marker. If one exists and covers the interleaving, there is no finding. If you cannot confirm from what you can see, still report it and put the exact thing to check in `verification`.

## What to report

For every finding: a one-line title; the file; the class, method or key; a severity (`high` = money moved twice or not at all, a lost write on a status that gates safety or payment, or data corruption on a hot path; `medium` = a genuine race that needs a timing window or a second instance; `low` = latent, correct today only because of a deployment fact); a category of `concurrency` for races, lost updates, ordering and idempotency, `database` for transaction-boundary, constraint and migration defects, otherwise the closest of logic, edge-case, architecture, performance, security; and a description of two to four sentences giving the interleaving and the resulting wrong state. Do not propose fixes.
```
