---
name: txn-and-concurrency-audit
description: 'Audits Spring/JPA code for transaction-safety and concurrency bugs — the kind that only show up under real production concurrency and pass every unit test. Use this whenever reviewing or writing @Scheduled jobs, batch/sweeper services, @Transactional service methods, ShedLock (@SchedulerLock) configuration, or any code that reads-then-writes rows another process might also touch. Also use it whenever someone asks to check for race conditions, TOCTOU bugs, write skew, N+1 queries, UnexpectedRollbackException, MultipleBagFetchException, or whether a scheduler is "safe to run on multiple instances." Trigger this proactively before merging any new or changed scheduler/batch job, not just when the user explicitly names it.'
---

# Transaction & Concurrency Audit

A systematic checklist for finding transaction-boundary and concurrency bugs in Spring Boot + JPA/Hibernate code — the category of bug that is invisible in a single-threaded test run and only bites when two schedulers overlap, a user request lands mid-batch, or a dataset gets big enough that a "chunked" job turns out not to be chunked at all.

These bugs share one root cause: code that assumes it has exclusive, instantaneous access to the data it just read. Every check below is a different shape of that same assumption breaking.

## When to reach for this

- Reviewing or authoring a `@Scheduled` method, sweeper, reconciliation job, or anything with "cleanup/expiry/retry/forfeiture/reminder" in the name.
- Reviewing `@Transactional` service methods that loop over a collection and call another transactional method per item.
- Reviewing or adding `@SchedulerLock` (ShedLock) configuration.
- Reviewing JPA entity mappings or queries that fetch related collections.
- Someone asks "is this safe under concurrency / on multiple instances / at scale?"

## How to run the audit

Don't jump straight to reading code line by line — the highest-value check here (#3, sibling lock consistency) requires seeing every scheduler at once, not one at a time. Work in three passes.

### Pass 1 — Build the inventory

Scope the audit: a specific file/PR diff, a module, or (if asked for a full audit) the whole codebase. Then enumerate every candidate, not just the one file someone handed you — checks #3 and #4 are comparative and are worthless applied to a single class in isolation.

```bash
# Every scheduled entry point
grep -rn "@Scheduled" --include="*.java" src/main/java

# Every distributed lock already in place (and its config)
grep -rn -A2 "@SchedulerLock" --include="*.java" src/main/java

# Every transactional method (candidates for propagation checks)
grep -rn "@Transactional" --include="*.java" src/main/java

# Entities with more than one List-typed collection mapping (bag-fetch risk)
grep -rln "@OneToMany\|@ManyToMany" --include="*.java" src/main/java/**/domain
```

For each `@Scheduled` method found, build a quick table before judging any single one:

| Class#method | Has `@SchedulerLock`? | lockAtMostFor (with derivation comment?) | Loop over items? | Calls a `@Transactional` method per item? | Claim-then-process pattern? |
|---|---|---|---|---|---|

This table is what makes check #3 and #4 tractable — "inconsistent with siblings" and "copy-pasted constant" are both statements about the *set*, not about one method.

### Pass 2 — Apply each check

Work through the 10 checks in `references/checks.md`. That file has, for each one: why it's a real bug (not just style), the concrete detection heuristic, a minimal before/after code pair, and the fix pattern. Read it now if you haven't — don't rely on the one-line summaries below, they're reminders, not the full heuristic:

1. Batch-wide `@Transactional` swallows a per-item exception's rollback-only flag
2. Batch-load-then-stale-write (TOCTOU) with no re-check before mutating
3. Missing `@SchedulerLock` where sibling schedulers of the same shape have one
4. Lock duration sized as a copy-pasted constant instead of derived arithmetic
5. Missed write skew (multi-row invariant, no serialization/locking)
6. ACID violations (partial writes outside the transaction boundary, wrong isolation for the invariant)
7. In-memory accumulation from "chunking" inside one outer transaction
8. General TOCTOU / check-then-act races beyond the specific patterns above
9. N+1 query problem
10. Hibernate `MultipleBagFetchException` risk (two `List` collections joined in one query)

Apply every check to every candidate in the inventory — most methods will trigger zero or one, but don't stop at the first hit and move on; a single scheduler method commonly has both #1 and #2, or both #9 and #10.

**Avoid false positives.** Don't flag a pattern just because it superficially matches — check whether the code already handles it correctly before writing it up:
- `REQUIRES_NEW` on the per-item call, or a `TransactionTemplate` per item, defeats #1 — that's the fix, not the bug.
- A conditional `UPDATE ... WHERE id = ? AND status = ?` (checking the affected-row count) *is* a re-check for #2, even without a separate SELECT.
- `SELECT ... FOR UPDATE` or `@Lock(PESSIMISTIC_WRITE)` around the read side of a multi-row invariant defeats #5.
- A `@Version` field with optimistic locking is a legitimate backstop for #3 even if `@SchedulerLock` is absent — note it as a mitigation, not a free pass, since it turns double-processing into a retry rather than preventing it, which still needs a retry path to be correct.
- `Set`-typed collections (instead of `List`) on multiple fetched associations stop `MultipleBagFetchException` from throwing, but don't treat that as clearing #10 automatically — the join still produces a Cartesian product under the hood. Check whether the collections and the number of parent rows loaded together are actually small before calling it fixed; otherwise flag the remaining performance risk (see `references/checks.md` #10).

### Pass 3 — Report

Findings go under `## Findings`, most severe first, one entry per finding using this template:

```
### [SEVERITY] <file>:<line> — <one-line summary>
**Check:** #<n> <check name>
**Failure scenario:** <concrete sequence of events that triggers it — not "this could be racy" but "if X runs while Y is between its read and write, then Z">
**Fix:** <the specific change, referencing the fix pattern from references/checks.md>
```

Severity:
- **Critical** — realistic in production today (existing traffic/scale triggers it, or it's on a hot path with concurrent writers) and causes data corruption, silent data loss, or loses other items' work in the same batch (this is what #1 does).
- **High** — a genuine correctness bug under concurrency, but needs a specific timing window or is one process restart away from happening (e.g., two instances of a scheduler that's about to be horizontally scaled).
- **Medium** — latent risk: correct today only because of an incidental fact (single-instance deployment, small batch size, low write volume) that could change without the bug being noticed.
- **Low** — defense-in-depth / best-practice gap (e.g., a lock duration that works today but has no derivation comment, so the next person who changes batch size won't know to revisit it).

If a candidate was checked and is clean, don't pad the report with "no issues found" per check — just don't list it. End with a one-line count by severity so the reader knows the audit was exhaustive, not abandoned early: `Checked N schedulers / M transactional methods / K entities. Findings: X critical, Y high, Z medium, W low.`

If nothing at all is found, say so plainly and name what you actually checked — don't imply thoroughness you didn't do.
