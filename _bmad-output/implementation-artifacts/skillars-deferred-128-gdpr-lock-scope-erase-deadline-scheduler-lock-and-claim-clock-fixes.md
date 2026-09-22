# Story: GDPR Erasure Lock-Scope & Erase-Deadline, Scheduler-Lock Ledger Correction & Claim-Stamp Clock Fixes

**Story Key:** `skillars-deferred-128-gdpr-lock-scope-erase-deadline-scheduler-lock-and-claim-clock-fixes`
**Epic:** Deferred Work
**Priority:** High (a real FK-lock exposure widening concurrent-insert latency on 7 tables during every
GDPR erasure — and a real Article-17 blob-orphaning bug in the naive version of that fix, caught and
corrected in story review; a genuinely unbounded-latency call site inside `PessimisticLockRetryer`'s own
documented "short call sites only" precondition; two benign/retryable-disappearance edge cases that can
fail an entire multi-child GDPR erasure) — plus a documentation-only Hikari-pressure justification, a
latent clock-coupling fix, and a corrected understanding (no code change) of five `@Scheduled` methods the
ledger had mis-flagged as needing `@SchedulerLock`, plus the standard ledger closeout.
**Status:** done
**Created:** 2026-09-22
**Reviewed:** 2026-09-22 (`story-review.md`, senior-dev pre-implementation audit). 3 "must fix" + 11
"should fix" findings, all independently re-verified against actual source before applying — zero false
positives. Most significant: AC1 as originally drafted would have broken `BlobDeletionOutboxSupport`'s
atomicity contract and permanently orphaned GDPR-erasure S3 blobs (fixed by moving the per-child blob
enqueue inside the same inner transaction); AC6's entire "mechanical, no design decision" premise was
false for all five of its targets (four are horizontally-scaled `FOR UPDATE SKIP LOCKED` workers where
`@SchedulerLock` would be a pure throughput regression and, for two of them, introduce a new bug; the
fifth is unreachable dead code) — AC6 now makes no production-code change and instead corrects the
ledger's understanding; the PARENT branch of `erase()` had zero existing test coverage, so AC1/AC2/AC4's
original "existing tests must stay green" claims were vacuous and are replaced with an explicit new
multi-child fixture. See the Change Log for the full response.

---

## Provenance & Scoping (read before starting)

This story mines the **freshest same-day code-review deferral** in `deferred-work.md`
(`## Deferred from: code review of skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes (2026-09-21)`
— surfaced by `/bmad-code-review` across four parallel layers when skillars-deferred-127 was reviewed),
per this project's established "read same-day code-review deferrals before drawing scope" convention,
plus the one still-genuinely-open bullet left over from the prior deferral
(`## Deferred from: code review of skillars-deferred-126-... (2026-09-21)`).

**Both sections were re-read in full against the actual current ledger content (not paraphrased) before
drafting this story.** Neither section carries any `[CLOSED by ...]` annotation on the items this story
closes — confirmed by direct inspection; skillars-deferred-127 only merged its own review response the
same day this story was created, so nothing in its fresh section has been touched yet.

### skillars-deferred-127's review section — five bullets, all fresh, none `[DECIDED]`/`[CLOSED]`

1. **FK-lock exposure** — `erase()` holds the `player_profiles` `FOR UPDATE` lock for its full remaining
   transaction after skillars-deferred-127 AC1's shared-lock fix, blocking FK `FOR KEY SHARE` RI checks
   on 7 child tables for concurrent inserts. **Closed by AC1 below** (owner decision, `AskUserQuestion`:
   narrow the lock scope).
2. **`PessimisticLockRetryer` is now used from exactly the long-running call site its own Javadoc
   forbids** — no cap on N children per PARENT erasure, no per-erase deadline. **Closed by AC2 below**
   (owner decision: add a per-erase deadline).
3. **Hikari pool pressure from AC3's 8 concurrent schedulers is undocumented and `8` is underived.**
   **Closed by AC3 below** (owner decision: document the derivation only, no behavior change).
4. **Five DB-touching `@Scheduled` methods still carry no `@SchedulerLock`** — flagged in the ledger as
   pre-existing/multi-instance-only, not worsened by skillars-deferred-127's diff, described as a genuine
   actionable gap. **Corrected, not closed as originally framed, by AC6 below (story review, 2026-09-22):**
   per-method verification found the ledger's premise wrong for all five — four are already fully
   protected by `FOR UPDATE SKIP LOCKED` (adding `@SchedulerLock` would regress throughput and, for two of
   them, introduce a new bug), and the fifth (`AlertEvaluationService.evaluate`) is unreachable dead code
   with no risk to protect against. AC6 now closes the ledger bullet with this corrected understanding
   instead of adding annotations.
5. **`orElseThrow` inside the multi-child PARENT loop aborts an entire erasure if one child's profile row
   vanishes.** **Closed by AC4 below** (owner decision: skip missing children, continue siblings; widened
   during story review to also cover a lock-contended child, not only a vanished one — see M11 in AC4).

Bullets 2 and 4 above are **not the same finding** — bullet 2 is about `PessimisticLockRetryer`'s own
long-running-call-site precondition (AC2 adds a per-erase time budget, but per M3 does not fully close
bullet 2 — see AC7's corrected disposition); bullet 4 is about five *unrelated* video/notification
schedulers, and per AC6's corrected analysis none of them actually need `@SchedulerLock`. Keeping them
distinct matters because AC2's fix does not touch any of AC6's five files, and AC6 makes no code change
to `GdprErasureService` (or anywhere else) at all.

### skillars-deferred-126's review section — one bullet still genuinely open

- **`now()` is `transaction_timestamp()`**, so `claimPendingBatch`/`resetStaleClaimed`'s claim-stamp and
  stale-claim deadline are correct today only because each repository call happens to run in its own
  short transaction — nothing enforces it. **Re-verified directly against HEAD as still open** (no
  `[CLOSED]`/`[DECIDED]` tag on this bullet; every other bullet in that same section already carries one
  from skillars-deferred-127's own closeout). **Closed by AC5 below.**

Every other bullet in the skillars-deferred-126 section is already `[CLOSED by skillars-deferred-127 ...]`
or `[DECIDED: accepted risk — skillars-deferred-126/127]` and is **not** touched by this story — including
the two `[DECIDED: accepted risk — skillars-deferred-127]` bullets added by skillars-deferred-127's own
code review (the `radar_composite_dlq` cleanup gap, and the new `markFailed`/no-`AdminAlert` path); both
remain accepted-as-documented, not reopened here.

### Out of scope (explicitly, not re-decided here)

- Reaching further into `deferred-work.md` beyond these two same-day-adjacent sections — this story's
  7-AC bundle (5 code/doc fixes, 1 ledger-correction-only AC, and the standard ledger closeout) is already
  larger than this series' usual 4–5 AC size; no further ledger-mining was attempted.
- Building `AdminAlert`/auto-retry machinery for `GdprErasureService.markFailed` — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.
- Clearing stale `radar_composite_dlq` rows for an erased player — already
  `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened.

All citations below were independently re-verified against
`story/deferred-127-lock-config-scheduler-fixes@6e9b034c` (skillars-deferred-127's own final,
post-code-review commit — the branch this repo's worktree tooling had checked out elsewhere at
story-creation time; this worktree's own `HEAD` predates skillars-deferred-127 entirely, so all
verification for this story was done by reading that branch's tip directly via `git show`, not by
running anything in this worktree's own checkout). **Re-verify against whatever `HEAD` looks like at
actual implementation time** — by then skillars-deferred-127 should be merged to `master` and this
worktree's own checkout should reflect it; if any citation below has drifted, that is expected and should
simply be corrected, not treated as a sign something else is wrong.

---

## AC1 — Narrow the `player_profiles` FOR UPDATE lock scope so it no longer spans `erase()`'s full remaining transaction

### Context

`GdprErasureService.erase`
(`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:81-203`) is a
single `@Transactional(propagation = Propagation.REQUIRES_NEW)` method. Its private
`deletePlayerDevelopmentData(Long playerId, ...)` helper (`:253-281`) takes the `player_profiles`
pessimistic lock skillars-deferred-127 AC1 added (`:254-256`, `lockRetryer.withBoundedRetry(() ->
playerProfileRepository.findByIdForUpdate(playerId)...)`), runs **11 bulk deletes across 11 tables**
(`:258-266`, `:274-275` — `player_timeline`, `slu`, `slu_weekly_snapshot`,
`player_slu_weekly_snapshot_applied`, `slu_target`, `neglected_skill_flag`, `player_radar_baseline`,
`player_radar_composite`, `radar_assessment`, `performance_report`, `homework_completion`) plus a
`performance_reports` scan collecting S3 storage keys (`:267-273`), and stamps the
`developmentDataErasedAt` tombstone (`:279-280`) — all inside `erase()`'s one outer transaction.
(**Correction (story review, 2026-09-22):** earlier drafts of this story said "13/14 deletes across 13
tables," inherited from the ledger bullet without re-counting; the actual body has 11 deletes across 11
tables, verified line-by-line above.) Because a Postgres `SELECT ... FOR UPDATE` row lock
releases only at transaction end, that lock is held not just for the duration of
`deletePlayerDevelopmentData` itself but for everything `erase()` does **afterward**, in the same
transaction:

- `refreshTokenRepository.markAllUsedByUserId(userId)` — `:154`
- `gdprRequestRepository.deleteExpiredByUserId(userId, ...)` — `:157`
- `blobDeletionOutboxSupport.enqueue(blobKeysToDelete)` / `.requestDrainAfterCommit()` — `:166-167`
- the `request.setStatus("COMPLETED")` save and both `eventPublisher.publishEvent(...)` calls — `:170-199`

None of that later work touches `player_profiles` or its FK'd children, so holding the lock through it
serves no purpose — it only widens the exposure window. Seven tables carry a `FOREIGN KEY ... REFERENCES
main.player_profiles(id)` and are therefore subject to an RI `SELECT 1 ... FOR KEY SHARE` check on every
concurrent `INSERT` referencing that same row, which conflicts with `FOR UPDATE` and blocks for however
long the outer lock is held (re-verified at `V138__baseline_schema.sql:4084` `coach_radar_preferences`,
`:4091` `player_radar_baselines`, `:4098` `player_radar_composites`, `:4105`
`player_slu_weekly_snapshot_applied`, `:4231` `parent_player_links`, `:4497` `payment.player_subscriptions`,
`:4504` `payment.player_subscription_changes`). Those inserting callers set no `lock_timeout`, so the wait
is bounded only by Postgres defaults (effectively unbounded absent a statement/connection-level timeout).
A PARENT erasure with N children widens this to N profiles' worth of exposure, since each child's lock is
acquired inside the same single `erase()` transaction and, per `deletePlayerDevelopmentData`'s own current
Javadoc (`:234-239`), "accumulates with every other player's lock already acquired earlier in the same
`erase` call until that whole transaction commits."

**Owner decision (already taken): narrow the lock scope.** Split `erase()` so
`deletePlayerDevelopmentData`'s lock acquisition and 11 deletes/tombstone-stamp commit in their **own**
short transaction per child, releasing that child's `player_profiles` lock immediately afterward —
**before** the unrelated downstream steps (refresh-token revoke, `gdprRequest` cleanup) run in `erase()`'s
own transaction.

**Required correction (story review, 2026-09-22, H1) — the per-child blob-deletion enqueue must move
INSIDE the same inner transaction, not stay on the outer path:** `BlobDeletionOutboxSupport`'s own class
Javadoc states its invariant explicitly
(`src/main/java/com/softropic/skillars/platform/filestorage/service/BlobDeletionOutboxSupport.java:20-22`,
verified) — "Producers call `enqueue` *inside* their business transaction (so the rows commit atomically
with the work that decided they should be deleted)" — and `enqueueOne` (`:50-62`) deliberately rethrows on
serialisation failure specifically so "the producing (e.g. GDPR erasure) transaction rolls back rather
than committing COMPLETED with a PII key that was never scheduled for deletion." The
`performance_reports` rows scanned at `:267-273` are the **only** record of their S3 `storage_key`s — once
`deletePlayerDevelopmentData` deletes those rows (`:274`), the keys exist nowhere else. If the blob
enqueue for those keys stays on `erase()`'s **outer** path (as originally drafted, alongside the
`refreshTokenRepository`/`gdprRequestRepository` calls), any failure between the inner transaction's
commit and the outer transaction's commit — including this story's own AC2 deadline throw — rolls back the
outbox rows while the `performance_reports` deletes remain durable. On re-drive,
`findByPlayerIdOrderByGeneratedAtDesc` returns nothing for that child, so those keys are **never**
enqueued and the S3 blobs are **permanently unerasable** — a real Article 17 violation this fix would
introduce, not merely "a partially-erased player," and materially worse than the atomicity this AC
otherwise trades away.

**Fix:** call `blobDeletionOutboxSupport.enqueue(...)` for a child's own `performance_reports` keys
**inside** that child's `REQUIRES_NEW` lambda, right after its deletes, so the key-bearing enqueue commits
atomically with the deletes that made the keys unrecoverable elsewhere. Keep the GDPR-export-zip keys
(`:160-162`, derived from surviving `gdpr_requests` rows, unaffected by this AC) on `erase()`'s own outer
path — those are a different, still-atomic case. This means `blobKeysToDelete` is no longer a single list
threaded through both scopes: `deletePlayerDevelopmentData` needs its own local list for its child's keys,
enqueued and drained before its lambda returns, while `erase()` keeps a separate list for the export-zip
keys only.

**Established precedent for the mechanism:** `ModerationSlaMonitorService`
(`src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java:36,44,52-56`)
already solves the identical problem — a method that must commit a per-item write independently of its
caller's own transaction, from a `private final` collaborator on the *same* bean (so a plain
`@Transactional` on a private/self-invoked method would silently not apply, per Spring AOP's proxy
limitation) — via a `PlatformTransactionManager txManager` field plus a `TransactionTemplate
requiresNewTemplate` built once in a `@PostConstruct` method with
`setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)`. `TransactionTemplate` manages
its transaction programmatically, so it works correctly even when invoked from `this.` inside another
`@Transactional` method on the same bean — no self-invocation caveat, unlike a second `@Transactional`
annotation would have.

**Accepted, intentional side effect of this fix — document it plainly, do not let it surprise a future
reader:** with `deletePlayerDevelopmentData` now committing in its own transaction (and, per the H1 fix
above, its own blob-deletion enqueue committing atomically with it), a later failure in `erase()` (e.g. in
`gdprRequestRepository.deleteExpiredByUserId`, or AC2's own deadline throw) can no longer roll back an
already-processed child's development-data deletion. Previously the whole `erase()` was one atomic unit;
after this fix, a GDPR erasure that fails partway through can leave one or more children's development
data durably deleted (and its blobs durably enqueued for deletion) while the account-level anonymisation,
refresh-token revocation, etc. did not complete — the request still routes to `markFailed`
(`GdprEventListener.onErasureRequested`'s catch, `:36-40`) and is re-drivable, and (with the H1 fix)
re-driving makes genuine forward progress with no unrecoverable data. This is the deliberate cost of
narrowing the lock; it is not a data-integrity bug (a partially-erased player is not "less erased" than
intended — Article 17 only requires eventual full erasure, not atomicity of it), but it must be documented
on `deletePlayerDevelopmentData`'s own Javadoc, not left implicit.

**Second accepted tradeoff, found during story review (2026-09-22, M10) — the sticky tombstone can now
commit before the erasure is known to succeed.** `developmentDataErasedAt` is documented as one-way
("Never reset back to null: a `player_profiles` row is never 'un-erased'", `:277-278`) and
`RadarCompositeCalculationService.recalculateComposite` hard-skips every future recalculation once it is
set (`:230-238`, verified: `if (playerProfile.getDevelopmentDataErasedAt() != null) { ...; return; }`).
Before this AC the tombstone committed atomically with the whole erasure; after this AC it commits as soon
as that one child's inner transaction commits — before `erase()`'s own outer transaction (anonymisation,
refresh-token revocation, etc.) is known to succeed. If `erase()` then fails for any reason (AC2's own
deadline, a downstream exception), the outcome is a **live, non-anonymised player whose radar composites
silently and permanently stop updating**, with no reset path by design, even though the erasure itself
never completed. **Owner decision (already taken): accept and document this, do not change the commit
timing.** Moving the tombstone write to `erase()`'s outer transaction would need its own analysis of
`RadarCompositeCalculationService`'s lock-ordering argument (`:230-233`) and is out of scope for this
story — revisit only if this scenario is ever actually observed in production.

**Third accepted, narrower tradeoff (M11):** this AC also changes the blast radius of the
already-`[DECIDED: accepted risk — skillars-deferred-127]` item where a contended child can hit
`PessimisticLockingFailureException` (a concurrent `recalculateComposite` can hold the shared lock for up
to 120s vs. the erasure's own ~3.2s retry budget). Pre-AC1 that failure rolled the whole `erase()` back to
a clean, re-drivable state; post-AC1 it aborts with any earlier children already durably deleted (and,
per this story's own AC4 decision below, the PARENT loop now also skips-and-continues past this specific
exception rather than aborting the whole request — see AC4). Document this connection in the same
Javadoc paragraph rather than leaving AC1 and AC4's interaction implicit.

### Tasks

1. Re-verify all line citations above against current `HEAD` before implementing.
2. Add `private final PlatformTransactionManager txManager` and a `private TransactionTemplate
   requiresNewTemplate` field to `GdprErasureService`, plus a `@PostConstruct void initTemplates()`
   building it with `PROPAGATION_REQUIRES_NEW` — mirror `ModerationSlaMonitorService.java:36,44,52-56`
   exactly (same imports: `jakarta.annotation.PostConstruct`,
   `org.springframework.transaction.PlatformTransactionManager`,
   `org.springframework.transaction.TransactionDefinition`,
   `org.springframework.transaction.support.TransactionTemplate`).
3. Wrap `deletePlayerDevelopmentData`'s current body (`:254-280`) in
   `requiresNewTemplate.executeWithoutResult(status -> { ... })` so the lock acquisition, all 11
   deletes, the scan, the per-child blob enqueue (Task 4 below), and the tombstone stamp commit as their
   own transaction and release the lock as soon as the lambda returns — before control returns to
   `erase()`.
4. **(H1 fix)** Give `deletePlayerDevelopmentData` its own local `List<String> childBlobKeys` (do not
   thread `erase()`'s outer list into it). Populate it in the existing `performance_reports` scan
   (`:267-273`), then call `blobDeletionOutboxSupport.enqueue(childBlobKeys)` **inside the same
   `REQUIRES_NEW` lambda**, after the deletes and before the tombstone stamp (ordering within the lambda
   does not matter for atomicity, but keep it readable). Do **not** call
   `blobDeletionOutboxSupport.requestDrainAfterCommit()` here — that stays exactly where it already is,
   as a single call in `erase()`'s own transaction (`:167`, unchanged), since it only needs to fire once
   per `erase()` invocation, not once per child. `erase()`'s own `blobKeysToDelete` list (`:129`) then only ever receives the GDPR-export-zip
   keys (`:160-162`) — rename it to something like `exportBlobKeysToDelete` so the two lists are not
   confused at either call site.
5. Confirm the child-scoped `childBlobKeys` list from Task 4 is correctly scoped per-child (a fresh list
   per `deletePlayerDevelopmentData` invocation, not shared across PARENT-branch iterations) — re-verify
   rather than assume, since this is exactly the kind of subtle behavior a lift-into-`TransactionTemplate`
   refactor can silently break.
6. **(M9)** After AC1, the outer persistence context still holds the `PlayerProfile` instances returned by
   `findByParentIdOrderByIdAsc` at `:149` (used again at `:191` for `AccountDeletionRequestedEvent`'s
   `linkedPlayerIds`), while the tombstone write now happens in the **inner** transaction/EntityManager.
   Hibernate's first-level cache will return the same stale (pre-tombstone) managed instances at `:191`.
   Only `PlayerProfile::getId` is read there today, so this is harmless now, but call
   `entityManager.detach(pp)` on each child's outer-context instance right after its
   `deletePlayerDevelopmentData` call returns (or re-read fresh by id at `:191` instead of reusing the
   `:149` list) so a future reader who adds a field read at `:191` does not silently get pre-erasure data.
7. Update `deletePlayerDevelopmentData`'s Javadoc (`:214-252`) to document: (a) the method now commits
   independently in its own transaction, releasing its `player_profiles` lock before returning; (b) the
   accepted atomicity tradeoff — a later `erase()` failure can no longer roll back an already-committed
   child's development-data deletion (but, per the H1 fix, blob-deletion enqueue now travels with it
   atomically, so no data becomes unrecoverable); (c) the sticky-tombstone tradeoff (a later `erase()`
   failure leaves a non-anonymised, still-live player whose radar composites permanently stop updating);
   (d) the connection to the already-`[DECIDED]` `PessimisticLockingFailureException` blast-radius change,
   cross-referencing AC4's skip-and-continue extension.
8. Grep-sweep `GdprErasureIT` and any other test asserting on `erase()`'s joint-rollback behavior (e.g. a
   test that forces a late failure and expects development-data deletions to have rolled back with it) —
   any such assertion is now stale by design and must be updated, not left to fail silently as a "flaky"
   test.
9. **(L4)** `entityManager.refresh(playerProfile, LockModeType.PESSIMISTIC_WRITE)` (`:256`) only works
   correctly if the injected shared-EntityManager proxy resolves to the **inner** transaction's
   EntityManager — the same one `playerProfileRepository.findByIdForUpdate` used just above it. It does
   (`JpaTransactionManager` suspends the outer `EntityManagerHolder` and binds a fresh one on
   `REQUIRES_NEW`), but state this explicitly in the Javadoc from Task 7, and let the first IT run
   (Tests below) be the actual confirmation rather than assuming it silently.

### Tests

**Required prerequisite (H3, story review 2026-09-22) — the PARENT branch has zero existing test
coverage today; this is not optional setup, it is this AC's (and AC2's and AC4's) actual test scope.**
Verified against `GdprErasureIT.java`: there is no `erase_parentUser_*` test; `setUp()` inserts exactly
one `main.player_profiles` row (`:118-123`), linked by `user_id` to `SELF_PLAYER_USER_ID`, and no row
anywhere in the fixture has `parent_id` set (grep for `parent_id` in that file returns only
`messaging.conversations`/`payment.parent_credit_ledger`/`video_approval_requests` usages, all unrelated
to `player_profiles`). `playerProfileRepository.findByParentIdOrderByIdAsc(PARENT_ID)` therefore returns an
**empty list** in every test today, and the PARENT loop body (`:148-150`) has never executed in CI. Every
"existing PARENT-branch test" claim below was corrected to reflect this — do not carry forward any
"existing tests must stay green" language for the PARENT branch without first adding this fixture.

- Add a new `main.player_profiles` fixture with **at least 2 children** (`parent_id = PARENT_ID`, distinct
  TSID-shaped ids per this file's own established convention), each seeded with at least one row in a
  table `deletePlayerDevelopmentData` deletes plus one `performance_reports` row carrying a real
  `storage_key` (so the H1 fix's per-child blob-enqueue path is exercised by real data, not just an empty
  scan). This fixture is shared scope for AC1/AC2/AC4's tests below — add it once.
- A `GdprErasureIT` test proving the lock is genuinely released early: start `erase()` for a PARENT with
  the new multi-child fixture, then — timed to land after `deletePlayerDevelopmentData`'s expected commit
  point but before `erase()` itself returns/commits (e.g. via a test hook, a `CountDownLatch` injected
  through a test-only seam, or by asserting on a concurrent `FOR UPDATE`/insert against one of the 7 FK'd
  tables succeeding quickly rather than blocking) — confirm a concurrent locker/inserter is not blocked for
  the rest of `erase()`'s duration. Follow `RadarCompositeCalculationServiceConcurrencyIT`'s own
  Testcontainers-plus-real-threads pattern for asserting on lock timing; do not assert on wall-clock
  timing alone without a deterministic synchronization point.
- A `GdprErasureIT` test proving the accepted tradeoff from Task 7 is real: inject a failure into a step
  *after* the first child's `deletePlayerDevelopmentData` has already committed (e.g. via a test double/spy
  on `gdprRequestRepository` or another post-loop step), and assert that child's development-data rows are
  gone, its blob keys are genuinely enqueued (not lost — the H1 regression this test must also guard
  against), and its tombstone is set (i.e., NOT rolled back) even though the overall `erase()` call throws
  and the request ends up `FAILED`.
- A new `GdprErasureIT` test for the multi-child fixture with all children present and reachable, no
  contention, no injected failure, proving the PARENT branch's basic happy path (this did not exist before
  this story and must be added, not merely "kept green").
- Existing `GdprErasureIT.erase_playerUser_...` tests (the PLAYER branch, which does have coverage) must
  stay green with unchanged externally-observable single-threaded behavior.

---

## AC2 — Bound the total lock-holding/backoff time across all children in one `erase()` run (per-erase deadline)

### Context

`PessimisticLockRetryer`'s own class Javadoc (`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:45-50`)
states: "all current call sites are short read-then-maybe-refresh operations (`findByIdForUpdate` +
optional `refresh`). If a future call site is long-running, or the pool is small relative to the
contended row's traffic, revisit this." skillars-deferred-127 AC1 made
`GdprErasureService.deletePlayerDevelopmentData` — 11 bulk deletes across 11 tables plus a
`performance_reports` scan (see AC1's Context for the corrected count/citations), invoked once per child
in the PARENT-branch loop
(`GdprErasureService.java:148-150`, `playerProfileRepository.findByParentIdOrderByIdAsc(userId).forEach(pp
-> deletePlayerDevelopmentData(pp.getId(), blobKeysToDelete))`) — exactly such a call site. Nothing
today bounds N (the number of children a single PARENT can have), nor the cumulative time `erase()` can
spend across all of them waiting on `PessimisticLockRetryer`'s own jittered backoff (default 8 attempts,
100ms→800ms×1.6, ~3.2s worst case per call, per that same Javadoc's "Cost model" section, `:38-56`).

**Precision correction (story review, 2026-09-22, M3):** the ledger's "used from the long-running call
site its own Javadoc forbids" framing is loose. The actual supplier passed to `withBoundedRetry` is short
— `findByIdForUpdate(...).orElseThrow(...)` only (`GdprErasureService.java:254-255`); the 11 deletes run
*after* `withBoundedRetry` returns, not inside it. `PessimisticLockRetryer`'s own connection-hold concern
(`:40-44`) — sleeping while holding the caller's pooled JDBC connection — is bounded to that short
supplier's own ~3.2s worst case, regardless of what `deletePlayerDevelopmentData` does afterward. The
genuine, still-real concern this AC targets is a related but distinct one: **the whole inner transaction**
(lock + 11 deletes + blob enqueue + tombstone, all now one `REQUIRES_NEW` transaction per AC1) holds one
pooled connection for its own duration, and AC2's deadline bounds how many of those per-child
transactions can run in sequence within one `erase()` call — it does not, and by itself cannot, bound any
*single* child's own connection-hold time (see the M3/ledger-bullet-2 disposition in AC7 below — this AC
does not fully close that bullet, and AC7 now annotates it as accepted risk rather than deleting it).

**This story's own AC1 changes the exact shape of the risk, without eliminating the underlying concern:**
before AC1, all N children's locks accumulated on the *single* connection backing `erase()`'s one outer
transaction, so a PARENT with N children could hold **one** connection for up to N × ~3.2s while
simultaneously holding N row locks at once. After AC1, each child's
`deletePlayerDevelopmentData` call acquires and releases its **own** `REQUIRES_NEW` connection
sequentially, so no single connection is held across children and locks no longer accumulate — but the
loop as a whole can still take up to N × ~3.2s of **wall-clock** time with nothing capping N, which is
still squarely the "long-running call site" and "pool is small relative to the contended row's traffic"
scenario `PessimisticLockRetryer`'s Javadoc warns about, just expressed as request latency and serial
connection churn rather than simultaneous connection pinning. **Re-verify this reframing once AC1 is
implemented** — do not assume it holds without re-reading the actual post-AC1 code.

**Owner decision (already taken): add a per-erase deadline.** Bound the total lock-holding/backoff time
across all children in one `erase()` run so a PARENT with large N cannot accumulate unbounded
connection-hold/request-latency time.

### Tasks

1. Re-verify citations above against current `HEAD`, including after AC1 lands (line numbers inside
   `GdprErasureService` will shift once AC1's `TransactionTemplate` plumbing is added).
2. Add a named budget constant — e.g. `private static final Duration GDPR_ERASE_LOCK_BUDGET =
   Duration.ofSeconds(10);` — with a documented derivation.
   **Corrected derivation (story review, 2026-09-22, M5):** the originally-drafted "30s matches Hikari's
   `connection-timeout: 30000`" reasoning is a category error — `connection-timeout` bounds how long a
   caller waits to *acquire* a connection; this budget instead bounds how long `erase()` *holds*
   connections (serially, one per child) and how long the calling thread is occupied. Those are unrelated
   quantities, and equating them gives the number a false provenance. The real constraint: `erase()` runs
   synchronously on the **request thread**, since `GdprEventListener.onErasureRequested` is a plain
   (non-`@Async`) `@TransactionalEventListener(phase = AFTER_COMMIT)` (`GdprEventListener.java:21,31-42`,
   verified) fired from `GdprRequestService.requestErasure`'s own `@Transactional` HTTP-request-scoped
   method (`GdprRequestService.java:80-100`) — so this budget is added directly to that HTTP response
   time. Derive it from that: ~10s is a defensible ceiling for a synchronous admin-triggered HTTP call
   (roughly 3× a single child's ~3.2s worst case, i.e. tolerating about 3 genuinely-contended children
   before tripping), not from Hikari's unrelated connection-acquisition timeout. Verify this reasoning
   holds, or substitute a different bound with its own documented derivation tied to request-latency
   tolerance, during implementation — this is a starting point, not a mandate.
3. Decide during implementation whether this budget is a plain `private static final Duration` or a new
   `ConfigBounds`/`platform_config` bounded key (this project's established convention for comparable
   runtime-tunable durations, e.g. `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`). Note: skillars-deferred-127
   AC2's own upsert-on-write fix to `ConfigService.updateConfig` already closes the "a new
   `HAS_CODE_DEFAULT` key is unwritable until an operator hand-inserts a row" gap a brand-new bounded key
   here would otherwise hit — so a runtime-tunable key is now a genuinely viable option, not just a
   constant, if a config-driven budget is preferred.
4. Convert the PARENT-branch `.forEach(...)` (`GdprErasureService.java:148-150`, or wherever AC1's edits
   leave it) into an explicit loop — a `Stream.forEach` cannot cleanly early-exit — that:
   - computes `deadline = Instant.now().plus(GDPR_ERASE_LOCK_BUDGET)` once, before the first child;
   - before each child's `deletePlayerDevelopmentData` call, checks whether `Instant.now()` is already
     past `deadline`; if so, stops processing further children and throws (do not silently truncate) —
     log which requestId/userId/how-many-of-N children were actually processed before stopping, so the
     eventual `[GDPR_ERASURE_FAILED]` log line (`GdprErasureService`'s existing pattern,
     `GdprEventListener.java:40`) is diagnosable.
   - lets the thrown exception propagate through `erase()` to `GdprEventListener.onErasureRequested`'s
     existing catch-all (`:36-40`), which already routes any exception to `markFailed`.
   **Owner decision (story review, 2026-09-22, M4): unlike the generic `markFailed`/no-`AdminAlert` gap
   (already `[DECIDED: accepted risk — skillars-deferred-127]`, not reopened), this specific deadline path
   must raise a targeted `AdminAlert`** (`adminAlertRepository`, already injected at
   `GdprErasureService.java:57`) before/alongside throwing — without it, a PARENT that reliably exceeds
   the budget produces a `FAILED` request with no admin alert and no auto-retry, indefinitely, on every
   re-drive, which is materially worse than the latency it replaces and is a *new*, narrower failure mode
   this AC itself introduces (distinct from the broader "alert on every `markFailed` cause" concern the
   `[DECIDED]` bullet correctly declines to build). Keep this alert narrowly scoped to the deadline-exceeded
   case only — do not generalize it into `markFailed` itself.
5. Confirm the deadline clock starts at the top of the PARENT-branch loop specifically, not at the top of
   `erase()` as a whole. Note (story review, 2026-09-22, L6): the account anonymisation and message/review
   deletion work preceding the loop (`:93-123`) includes unbounded bulk deletes
   (`messageRepository.deleteAllBySenderId`, `adminAlertRepository.resolveOpenAlertsForDeletedMessages`,
   `coachReviewRepository.deleteNonApprovedByAuthorId`) — "fast" is not a reliable reason to exclude them
   from the budget. The real reason to scope the deadline to the loop only is that those steps are not the
   *lock-holding* concern this AC targets (they do not contend `player_profiles`); use that framing, not
   speed, when documenting the scoping choice.
6. The PLAYER branch (`:139-147`) only ever calls `deletePlayerDevelopmentData` once — it is inherently
   bounded by a single `PessimisticLockRetryer` call's own ~3.2s worst case already. Do not add deadline
   machinery to the PLAYER branch; this AC is a PARENT-branch-only concern.

### Tests

**Corrected test seam (story review, 2026-09-22, M6):** the originally-drafted pointer to
`RadarCompositeCalculationServiceConcurrencyIT` as a precedent for overriding a normally-fixed timing
constant does not exist — verified that IT's only timing constant is its own test-side locker-thread hold
duration (`LOCK_HOLD_MILLIS`), with no `@TestPropertySource`/`ReflectionTestUtils`/
`@DynamicPropertySource`/production-constant override anywhere in the file. Decide the seam here instead:
given Task 3 already notes deferred-127 AC2's `createOrReadBack` upsert makes a new bounded key viable, the
cleanest option is a new `ConfigBounds` key (seconds-as-long — `createOrReadBack` hardcodes
`ConfigValueType.LONG`, `ConfigService.java:250-255`, verified — not a `Duration`) overridden per-test via
`@TestPropertySource`/a seeded `platform_config` row, rather than a constructor/setter seam. If Task 3
instead lands on a plain constant, use `ReflectionTestUtils.setField` on the constant's holder field
instead.

- A test proving a PARENT whose combined per-child processing time would exceed the configured budget
  stops early, marks the request `FAILED` (not `COMPLETED`, not silently truncated with no trace), raises
  the new targeted `AdminAlert` (Task 4), and logs how many children were actually processed — construct
  this with the budget seam above (a small overridden value) rather than waiting out a real ~10s window in
  a test. Use the multi-child fixture added under AC1's Tests (above); this is shared scope, not a second
  fixture.
- A `GdprErasureIT` test with the multi-child fixture and a small number of children under the budget, no
  contention, must complete successfully and unaffected in timing (this did not exist before this story —
  see AC1's H3 note; do not phrase this as "existing tests must stay green").

---

## AC3 — Document the Hikari connection-pressure derivation behind `spring.task.scheduling.pool.size: 8` (no behavior change)

### Context

`application.yaml:53-66` already documents *why* `spring.task.scheduling.pool.size: 8` exists (fixing
single-thread `@Scheduled` starvation, skillars-deferred-127 AC3) but not the connection-pressure `8`
itself creates against the shared `datasource.hikari.maximum-pool-size: 25` (`application.yaml:137`,
`minimum-idle: 8` at `:138`). That pool is also shared by Tomcat request-handling threads, the clustered
Quartz job store (`org.quartz.threadPool.threadCount: 3`, `application.yaml:38`,
`org.quartz.jobStore.isClustered: true`, `:42`), and **seven application-managed thread pools** —
re-verified against `infrastructure.threadpool.ExecutorShutdown`'s own authoritative inventory
(`ExecutorShutdown.java:77-83`, which explicitly corrects an earlier undercount: **"There are seven pools,
not the five the story enumerated"**, `:103-114`): `outboxDrainPool`, `sluRetryExecutor`,
`storageUploadExecutor`, `sendMailPool`, `moderationTaskExecutor`, `taskExecutor`, `reportExecutor`.

**Correction (story review, 2026-09-22, M8):** these seven are not all `@Async`-qualifier targets — the
original draft's "seven `@Async` executors" wording is itself imprecise. `storageUploadExecutor` is a raw
`java.util.concurrent.ThreadPoolExecutor` passed directly to the AWS SDK
(`S3StorageService.java:56`, verified: `AsyncRequestBody.fromInputStream(data, contentLength,
storageUploadExecutor)`), never an `@Async` method target — `ExecutorShutdown.java:104-106` says so
explicitly ("is a raw `ThreadPoolExecutor` rather than a `ThreadPoolTaskExecutor`, which is why an
inventory built by grepping for `ThreadPoolTaskExecutor` missed it"). Use "seven application-managed
thread pools (not all `@Async`)" in the new comment, not "seven `@Async` executors" — and the original
ledger bullet's "six `@Async` executors" estimate is stale regardless, so this AC's own correction should
not just swap one imprecise count for another.

**Correction (story review, 2026-09-22, M7) — the "8 threads parked in `PessimisticLockRetryer` backoff"
mechanism is wrong; the real 8-of-25 pressure claim is simpler and correct on its own terms.** Verified:
only **two** `@Scheduled` methods in the whole codebase can reach `PessimisticLockRetryer` at all —
`PaymentPendingSweeper.sweepStrandedPayments` → `sweepOne` → `lockRetryer.withBoundedRetry`
(`PaymentPendingSweeper.java:110-112,141,149`, 15-minute cadence, `@SchedulerLock`ed) and
`RadarCompositeDlqProcessor.process` → `compositeCalculationService.recalculateComposite`
(`RadarCompositeDlqProcessor.java:157,257`, 60s cadence, `@SchedulerLock`ed). Spring's
`ReschedulingRunnable` means each `@Scheduled` method has at most one in-flight run per JVM, and both of
these are `@SchedulerLock`ed across instances too — so the true worst case for the
backoff-while-holding-a-connection mechanism specifically is **2** scheduler threads, not 8. The
underlying 8-of-25 pressure argument does not need that mechanism to be true, though: **8 scheduler
threads can each hold a pooled connection for the duration of their own transaction** (any DB-touching
`@Scheduled` method, not only the two that reach the retryer), which is the real, simpler basis for the
"8 of 25" claim. Write the new comment around that mechanism — `PessimisticLockRetryer`'s
backoff-while-holding pattern is a narrower, 2-thread sub-case worth a one-line mention, not the
headline mechanism.

**Owner decision (already taken): document the derivation only, no behavior change.** Add an
`application.yaml` comment showing the connection-pressure math (worst-case concurrent DB-touching
scheduled jobs vs. 25 total connections, headroom for Tomcat/Quartz/`@Async`) to justify `8` as a chosen
number, not just as a fix for starvation.

### Tasks

1. Re-verify all citations above against current `HEAD` before implementing, including re-counting the
   actual number of DB-touching `@Scheduled` methods with a cadence of 60s or less (the deferred-127
   review bullet's own "ten high-cadence jobs (5s–60s)" figure should be re-derived, not assumed —
   `grep -rn "@Scheduled" src/main/java` and cross-reference each hit's `fixedDelay`/`fixedRate` against
   whether its body touches the database).
2. Extend the existing `application.yaml:53-62` comment block (or add a clearly-linked follow-on comment
   immediately after it, before `pool:\n  size: 8` at `:63-66`) with the connection-pressure math: total
   Hikari capacity (25), the scheduler pool's own worst-case share (8 — any DB-touching `@Scheduled` method
   can hold a pooled connection for its transaction's duration, not only the two that reach
   `PessimisticLockRetryer`), what that leaves for Tomcat + clustered Quartz (3 threads) + the seven
   application-managed thread pools (not all `@Async` — see the corrected M8 wording above), and a
   one-line note that `PessimisticLockRetryer`'s own backoff-while-holding pattern
   (`PessimisticLockRetryer.java:40-44`) is a narrower 2-thread sub-case (only
   `PaymentPendingSweeper.sweepStrandedPayments` and `RadarCompositeDlqProcessor.process` reach it), not the
   basis for the 8-of-25 claim itself. State explicitly that `8` was chosen with this pressure in mind, not
   picked independent of it.
3. This is a comment-only change — do **not** alter `spring.task.scheduling.pool.size`'s actual value, and
   do not add a new `ConfigBounds`/`platform_config` key for it (skillars-deferred-127's own rationale for
   keeping it a static property — a thread pool cannot be resized at runtime without recreating the
   scheduler bean — is unaffected by this AC and still applies).

### Tests

- None expected — this is a documentation-only change to a YAML comment. Confirm the application still
  boots normally (no test assertion needed beyond the existing Spring context tests already covering
  `application.yaml`'s parseability).

---

## AC4 — PARENT-loop resilience: skip a vanished or lock-contended child, continue remaining siblings

### Context

`GdprErasureService.deletePlayerDevelopmentData`'s per-child lock acquisition
(`:254-255`, `lockRetryer.withBoundedRetry(() -> playerProfileRepository.findByIdForUpdate(playerId)
.orElseThrow(() -> new ResourceNotFoundException("Player not found: " + playerId, "player_profile")))`)
throws `ResourceNotFoundException`, unretried by `PessimisticLockRetryer` (which explicitly does not
absorb a non-lock exception, per its own Javadoc, `:118-124`), if the child's `player_profiles` row is
gone by the time the lock is actually attempted. For a PARENT with children `[A, B, C]` processed via the
loop at `:148-150`, if B's row vanishes between `findByParentIdOrderByIdAsc` returning the list and this
method's lock attempt for B (most plausibly because B was erased by a *second*, concurrent GDPR request —
a benign, already-complete outcome), that exception currently propagates out of the whole `erase()` call,
routing the entire request to `markFailed` (`GdprEventListener.java:36-40`) — needlessly, since B's
disappearance means there is nothing left to erase for B at all.

**Interaction with this story's own AC1:** before AC1, a mid-loop failure additionally rolled back A's
already-completed deletions (all inside the same one transaction); after AC1, A's deletion has already
committed independently by the time B's lock attempt runs, so AC1 alone reduces — but does not eliminate —
the blast radius of this bug. The request still ends up `FAILED` and needs a manual/automatic resubmission
purely because of a benign, already-resolved disappearance. This AC closes that residual.

**Owner decision (already taken): skip missing children, continue siblings.** Treat a vanished child
profile as already-erased and continue processing the remaining siblings, instead of failing the whole
PARENT request.

**Widened scope (story review, 2026-09-22, M11 — owner decision taken live): also skip-and-continue on
`PessimisticLockingFailureException`, not only `ResourceNotFoundException`.** AC1 changes the blast radius
of an already-`[DECIDED: accepted risk — skillars-deferred-127]` item: `RadarCompositeCalculationService`
can hold the shared `player_profiles` lock for up to `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS` (120s) while
`deletePlayerDevelopmentData`'s own `PessimisticLockRetryer` budget is only ~3.2s, so a genuinely contended
child throws `PessimisticLockingFailureException` and — today, and still after only the
`ResourceNotFoundException` fix above — aborts the **entire** PARENT request, even though a lock-contended
child is retryable on a later re-drive and is not the same "nothing left to erase" case a vanished child
is. Unlike a vanished child (permanently resolved — nothing to retry), a lock-contended child should be
revisited, but per the already-`[DECIDED]` position on `markFailed`'s lack of auto-retry, this story does
not build new retry machinery — it only prevents one contended child from failing *every other* child in
the same PARENT request. Catch `PessimisticLockingFailureException` alongside `ResourceNotFoundException`
in the same loop, log at `warn` (distinguishable from the vanished-child case — this is genuine contention,
not a benign resolution), and continue to the next sibling.

**This is deliberately narrower than the PLAYER branch's existing `orElse`-skip pattern
(`:139-147`, `ifPresentOrElse` skipping with a `log.warn` when no profile exists at all) — do not conflate
the two.** The PLAYER branch resolves its own profile via `findByUserId` *before* calling
`deletePlayerDevelopmentData` at all, so a legitimately-never-built profile is distinguished one level up,
outside the method. The PARENT branch instead trusts `findByParentIdOrderByIdAsc`'s result and only
discovers a vanished row *inside* `deletePlayerDevelopmentData`, at lock-acquisition time — this AC's fix
belongs at the PARENT-loop call site (or via a caught exception around each iteration), not inside
`deletePlayerDevelopmentData` itself, since `deletePlayerDevelopmentData`'s own Javadoc
(`:226-232`) is correct that a not-found *at that exact point* is a real anomaly for whichever caller
reaches it — the fix is in how the PARENT loop *reacts* to that anomaly (skip-and-continue), not in
suppressing the method's own not-found signal.

### Tasks

1. Re-verify citations above against current `HEAD`, including after AC1's and AC2's edits to the same
   loop (this AC's fix and AC2's deadline-check both restructure the same `.forEach` into an explicit
   loop — implement them together, in one pass over the loop body, rather than editing it twice).
2. In the PARENT-branch loop, wrap each child's `deletePlayerDevelopmentData` call in a `try { ... }` with
   **two** catch clauses (do not collapse into one `catch (Exception e)` — keep the two cases visibly
   distinct in both code and logs):
   - `catch (ResourceNotFoundException e)` — logs at `warn` that the child's profile vanished before its
     lock could be acquired (treated as already-erased, benign, resolved), then `continue`s.
   - `catch (PessimisticLockingFailureException e)` — logs at `warn` that the child's profile lock was
     genuinely contended (distinct wording from the vanished-child case — this is not benign, it is a real
     retryable-later contention, per M11's widened scope above), then `continue`s.
3. **(L5)** Today the only thrower inside `deletePlayerDevelopmentData` reachable this way is the
   `orElseThrow` at `:255` for `ResourceNotFoundException`, and `PessimisticLockingFailureException` can
   only surface from the lock acquisition itself once `PessimisticLockRetryer`'s retry budget is exhausted
   — so both catches are precise today, but only by construction. Add a comment pinning this assumption (a
   future `ResourceNotFoundException` or `PessimisticLockingFailureException` thrown from deeper in the
   delete chain — e.g. a repository call — must not be silently swallowed as "child vanished/contended" if
   the method's shape ever changes) rather than leaving the precision implicit.
4. Confirm both of this AC's `catch` clauses are scoped to exactly the PARENT-branch loop, not applied to
   the PLAYER branch's single call site (which never reaches either exception today, since it resolves its
   own profile via `findByUserId` first) or turned into a blanket catch-and-continue anywhere else
   `deletePlayerDevelopmentData` is called.
5. Confirm this AC's two catches and AC2's deadline-`throw` are not confused with each other in the loop
   body — a vanished or contended child should `continue`; an exhausted deadline should stop the loop
   entirely (`break`/`throw`, plus the AC2 Task 4 `AdminAlert`). Write the loop so all three conditions are
   visibly distinct, not collapsed into one generic catch-and-decide branch.

### Tests

Use the multi-child `GdprErasureIT` fixture added under AC1's Tests (above) — do not add a second fixture.

- A test with children `[A, B]`: delete B's `player_profiles` row directly (simulating a concurrent
  erasure having already completed for B) between the loop's `findByParentIdOrderByIdAsc` read and its lock
  attempt for B (e.g. via a test hook, or by pre-deleting B before the loop even starts if a mid-loop
  injection point is impractical — a pre-deleted B is a simpler, equally valid reproduction of "vanished
  before lock attempt"); assert `erase()` completes successfully, A's development data is fully deleted,
  and B's absence produced only a `warn` log, not a failed request.
- A test for the widened M11 scope: with children `[A, B]`, hold B's `player_profiles` lock from a second
  thread long enough to exhaust `PessimisticLockRetryer`'s budget for B specifically (mirroring
  `GdprErasureIT`'s own existing lock-contention test pattern, e.g. the raw-JDBC-hold approach at
  `:591,694`), so B throws `PessimisticLockingFailureException`; assert `erase()` still completes
  successfully, A's development data is fully deleted, and B's contention produced only a `warn` log (with
  wording distinguishable from the vanished-child case), not a failed request.
- A test with all children present and reachable, no contention, must complete successfully (this did not
  exist before this story — see AC1's H3 note).

---

## AC5 — Use `clock_timestamp()` instead of `now()` for the claim-stamp write in `claimPendingBatch`/`resetStaleClaimed`

### Context

`now()` is Postgres's `transaction_timestamp()` — constant for the whole transaction, not
statement-accurate. `VideoDeletionOutboxRepository.claimPendingBatch`
(`src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java:40,49`,
`SET status = 'CLAIMED', claimed_at = now(), claimed_by = :runId`) and its `resetStaleClaimed` sibling
(`:103,105`, `claimed_at < now() - make_interval(secs => :staleWindowSeconds)`), plus the identical pair in
`RadarCompositeDlqRepository`
(`src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java:35,44`
and `:85,87`), are correct today only because `process()` carries no `@Transactional` and ShedLock's own
accessor runs `REQUIRES_NEW` — so each `@Modifying @Transactional` repository call gets a fresh
transaction and `now()` happens to equal statement time. Nothing in the code enforces this; adding
`@Transactional` to `process()` (or calling it from any transactional caller) in some future change would
silently join both statements into one transaction, stamping `claimed_at` with the *outer* transaction's
start time and freezing `resetStaleClaimed`'s deadline for the whole run — eroding the `MAX_RUN_DURATION <
lockAtMostFor < STALE_CLAIM_WINDOW` margin (skillars-deferred-126 AC1) from both ends at once, silently
and with no compiler/test signal.

`clock_timestamp()` returns the actual current wall-clock time at the moment it is evaluated, regardless
of transaction boundaries — using it here removes the hidden transaction-boundary coupling entirely,
independent of whether some future change ever adds `@Transactional` to `process()`.

**Verification item, not a blocker (story review, 2026-09-22):** unlike `now()`, `clock_timestamp()` is
evaluated **per row**, not once per statement — so a multi-row `claimPendingBatch` UPDATE would stamp each
claimed row with a very slightly different `claimed_at`. This would have broken this codebase's
pre-skillars-deferred-124 design (which used `claimed_at` itself as the batch-identity predicate for a
later release/complete step), but skillars-deferred-124 AC4 already moved that identity predicate to
`claimed_by` in both repositories (verified: `VideoDeletionOutboxRepository.java:44`,
`RadarCompositeDlqRepository.java:50,97,114,124`) — so per-row evaluation is safe today. Confirm this
during implementation (grep both repositories for any remaining `claimed_at`-based identity comparison
before making this change) rather than assuming it from this story text alone.

**Owner decision (already taken, mechanical — no design choice needed): use `clock_timestamp()` instead of
`now()` for the claim-stamp write(s).**

### Tasks

1. Re-verify the four exact citations above against current `HEAD` before implementing (they may shift
   slightly if AC1–AC4 above are implemented first and touch unrelated line numbers elsewhere in the
   repo — these two repository files are untouched by AC1–AC4, so drift here should only come from
   unrelated intervening changes, if any).
2. In `VideoDeletionOutboxRepository.claimPendingBatch` (`:40`), change `claimed_at = now()` to
   `claimed_at = clock_timestamp()`. In `resetStaleClaimed` (`:103`), change `claimed_at < now() -
   make_interval(...)` to `claimed_at < clock_timestamp() - make_interval(...)`.
3. Make the identical two changes in `RadarCompositeDlqRepository` (`:35` and `:85`).
4. Update each query's surrounding comment (e.g. `VideoDeletionOutboxRepository.java:27-34`,
   `RadarCompositeDlqRepository.java:22-29`, both of which currently narrate the `now()`-based "database's
   own now()" reasoning) to describe `clock_timestamp()` instead, preserving the still-accurate parts of
   each comment (the "matching `ShedLockConfig`'s `usingDbTime()` choice" framing, and the "DB time, not
   app time" rationale, both remain true — only the specific function name and its transaction-scoping
   caveat need correcting).
5. Do **not** touch any other `now()` usage in either repository (e.g. the `next_retry_at <= :now` bind
   parameter comparisons, or any other query) — this AC is scoped exactly to the claim-stamp write and its
   paired staleness read, per the ledger bullet's own scope; the `next_retry_at` app-clock eligibility gap
   is a separate, already-`[DECIDED: accepted risk — skillars-deferred-126]` item, not touched here.

### Tests

- Confirm existing `VideoDeletionOutboxProcessor`/`RadarCompositeDlqProcessor`-adjacent IT tests that
  assert on `claimed_at` values (if any) still pass — `clock_timestamp()` and `now()` produce
  observably-identical results for any test that does not span multiple statements inside one open
  transaction, so no existing assertion should need to change.
- No new test is expected to meaningfully distinguish `now()` from `clock_timestamp()` behavior in this
  project's existing test infrastructure (the difference only manifests across multiple statements inside
  one transaction, which neither repository method currently runs inside) — if a narrow test can
  demonstrate the two functions' distinct semantics directly (e.g. a raw `EXPLAIN`/query test asserting
  the SQL text itself uses `clock_timestamp()`), add it; otherwise document why not in the Dev Agent
  Record rather than inventing a strained multi-statement-transaction test.

---

## AC6 — Correct the ledger's "five unlocked schedulers" framing (no `@SchedulerLock` added; per-method analysis, not a mechanical annotation sweep)

### Context

**This AC's entire premise was wrong and is corrected here (story review, 2026-09-22, H2/M1/M2) — it no
longer adds `@SchedulerLock` to any of the five originally-named methods.** The ledger bullet (and this
story's own first draft) framed all five as a "mechanical fix, no design decision needed." Verified
per-method against actual source, that framing does not survive contact with the code for **any** of the
five:

| method | why `@SchedulerLock` is NOT the right fix | verified at |
|---|---|---|
| `UploadSessionExpiryScheduler.processExpired` | already fully protected: `findExpiredPendingForUpdate` = `FOR UPDATE SKIP LOCKED`, plus a `status != PENDING` re-check inside the per-row transaction. Locking it forces single-node execution for zero additional correctness benefit. | `UploadSessionRepository.java:27-29`; `UploadSessionExpiryScheduler.java:35-58` |
| `ReconciliationWorkerScheduler.reconcile` | already fully protected: `findNonTerminalForUpdate` = `FOR UPDATE SKIP LOCKED`, plus `Video.@Version` optimistic locking on every write path. Same "no benefit, real cost" shape as above. | `VideoRepository.java:39-41`; `ModerationSlaMonitorService.java:79-94` documents the identical `@Version` reliance by name |
| `WebhookEventProcessorScheduler.processPending` | already fully protected: `findPendingForUpdate` = `FOR UPDATE SKIP LOCKED` **plus** an explicit `PENDING → PROCESSING` claim step. The class's own Javadoc states the design outright ("Concurrent processing is prevented using `FOR UPDATE SKIP LOCKED`... ensuring that only one scheduler node processes a given event at any time" — this is about per-event exclusivity, not needing single-node-only execution). Locking the whole method also breaks its own gauge metrics (see below). | `VideoWebhookEventRepository.java:41-43`; `WebhookEventProcessorScheduler.java:39-42,62-66` |
| `ModerationSlaMonitorService.detectSlaViolations` | already fully protected: `findScanningOlderThan` = `FOR UPDATE SKIP LOCKED`; the resulting rare double-pick window is explicitly analysed and accepted in the method's own Javadoc (`:79-94`). Locking it also degrades a node-local failure counter (see below). | `VideoRepository.java:62-64`; `ModerationSlaMonitorService.java:58-94` |
| `AlertEvaluationService.evaluate` | unreachable dead code today: `computeMetricValue` is a stub that unconditionally `return -1.0` (`:84-88`, `TODO: Implement generic metric computation...`), and `evaluate()`'s own guard (`if (actualValue < 0) continue;`, `:58-61`) means **no rule can ever breach and no `AlertFiredEvent` can ever be published**, on any number of instances. There is no duplicate-email risk to protect against — `@SchedulerLock` here would just acquire/release a `main.shedlock` row every 30s, forever, on a method that (per its own Javadoc, `:23-24`) is deliberately DB-free. | `AlertEvaluationService.java:23-24,55-61,84-88` |

**Two concrete regressions locking would introduce, beyond the throughput cost:**

- `WebhookEventProcessorScheduler.processPending` publishes two Micrometer gauges (`videoMetrics.
  updateWebhookQueueDepth(...)`/`updateActiveUploadSessions(...)`, `:63-66`) **before** the batch load, at
  the top of the method. If `@SchedulerLock` wrapped the whole method, the N−1 nodes that lose the lock on
  any given cycle would skip the gauge updates entirely, freezing their `webhook_queue_depth`/
  `active_upload_sessions` metrics at stale values forever, silently corrupting any cross-instance
  max/avg aggregation.
- `ModerationSlaMonitorService`'s `videoConsecutiveFailures` is a plain in-memory `HashMap<UUID,Integer>`
  (`:49-50`), and the "three consecutive failures = 15 minutes of unrecoverable failure" forced-`FAILED`
  backstop (`:181-185`) only holds because every instance runs every cycle today, so each node accumulates
  a complete local history. Under `@SchedulerLock`, an arbitrary node runs each cycle, so a node that skips
  a cycle neither increments nor clears its own counter — counters diverge per node, and the very backstop
  that exists so "an infinite failure loop cannot escape notice" becomes the thing that escapes notice.

**Corrected disposition, per the narrowed per-method review this AC's owner decision asked for:** none of
the five methods gets `@SchedulerLock` in this story. The four `SKIP LOCKED`-protected schedulers are
already correctly designed for horizontal scale-out with no coverage gap to close; adding a lock to any of
them is a pure throughput regression (and, for two of them, a new bug) with no corresponding benefit.
`AlertEvaluationService.evaluate` is inert pending `computeMetricValue`'s implementation — locking dead
code protects against nothing, and can be revisited in whatever future story implements real metric
computation for it.

The other four already-excluded, genuinely node-local-cache `@Scheduled` methods —
`ConfigService.scheduledRefresh`, `AlertRuleCache.refresh`, `MessagingEmitterRegistry.sendHeartbeats`,
`RateLimitingService.evictIdleBuckets` — remain correctly out of scope, unchanged from the original
ledger bullet's own framing.

### Tasks

1. **No production code changes for this AC.** Re-verify the per-method table above against current
   `HEAD` (in particular, that none of the four `SKIP LOCKED` queries or the `AlertEvaluationService`
   stub has changed since this story was written) before treating this AC as still accurate.
2. If `computeMetricValue` has been implemented by the time this story is picked up (i.e. `AlertEvaluationService`
   is no longer dead code), re-open the `@SchedulerLock` question for that one method specifically — the
   duplicate-ops-alert-email risk becomes real again once rules can actually breach. Do not silently ship
   past this without re-checking.
3. Update the ledger closeout (AC7 below) to record the corrected reasoning per method, so a future ledger
   pass does not re-raise "these five need `@SchedulerLock`" without re-deriving why that premise is false
   for four of them and moot for the fifth.

### Tests

- None — no production code changes in this AC.

---

## AC7 — Ledger closeout

Standard closeout per this project's established convention (delete outright what this story genuinely
fixes; annotate `[DECIDED: accepted risk — skillars-deferred-128]` what it deliberately does not fix;
leave everything else exactly as-is).

### Tasks

1. Re-verify the actual final diff (`git status --short` / `git diff --stat`) against this story's own
   File List before touching the ledger — do not close anything this story did not actually ship.
2. Under `## Deferred from: code review of skillars-deferred-127-gdpr-radar-lock-serialization-config-upsert-and-scheduler-pool-fixes (2026-09-21)`:
   - **Bullet 1** (FK-lock exposure) — delete outright, fully fixed by AC1 (per the H1-corrected version:
     the per-child blob enqueue also moved inside the inner transaction, so no new data-loss risk was
     introduced closing this one).
   - **Bullet 2** (`PessimisticLockRetryer` long-running call site) — **corrected disposition (story
     review, 2026-09-22, M3): annotate `[DECIDED: accepted risk — skillars-deferred-128]`, do NOT delete.**
     AC2's deadline bounds how many children run per `erase()` call; it does not bound any single child's
     own connection-hold time, which is the actual concern this bullet and `PessimisticLockRetryer`'s own
     Javadoc describe. Record in the closeout note: the per-child inner transaction (post-AC1) still holds
     one pooled connection for the duration of its own lock + 11 deletes + blob enqueue + tombstone; AC2's
     cross-child deadline (plus its own targeted `AdminAlert` on trip) is an accepted mitigation of the
     *aggregate* risk, not a fix for the *per-child* one. Revisit if a future story is asked to bound
     single-child hold time directly (e.g. a `statement_timeout`/`lock_timeout` mirroring
     `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`'s approach).
   - **Bullet 3** (Hikari pool pressure undocumented) — delete outright, fully addressed by AC3 (a
     documentation fix closes a documentation gap) — using the corrected M7/M8 derivation and wording, not
     the story's original (wrong) mechanism.
   - **Bullet 4** (five `@Scheduled` methods with no `@SchedulerLock`) — **corrected disposition (story
     review, 2026-09-22, H2/M1/M2): delete outright, but with the corrected reasoning, not "fixed by
     adding locks."** Record in the closeout note: all four `FOR UPDATE SKIP LOCKED`-protected schedulers
     (`UploadSessionExpiryScheduler.processExpired`, `ReconciliationWorkerScheduler.reconcile`,
     `WebhookEventProcessorScheduler.processPending`, `ModerationSlaMonitorService.detectSlaViolations`)
     are correctly unlocked by design — already-adequate per-row cross-node protection via `SKIP LOCKED`
     (plus `@Version` / an explicit claim step, depending on the method); adding `@SchedulerLock` to any of
     them would be a pure throughput regression, and for two of them (`WebhookEventProcessorScheduler`'s
     pre-lock gauge writes; `ModerationSlaMonitorService`'s node-local consecutive-failure counter) a new
     bug. `AlertEvaluationService.evaluate` is separately inert — `computeMetricValue` is a stub that
     always returns -1.0, so no alert can ever fire on any number of instances; there is no
     duplicate-notification risk today. Add a new
     `[DECIDED: accepted risk — skillars-deferred-128 — AlertEvaluationService]` note (not a full bullet)
     flagging that the `@SchedulerLock` question should be re-opened if/when `computeMetricValue` is ever
     implemented.
   - **Bullet 5** (`orElseThrow` aborts whole PARENT erasure) — delete outright, fully fixed by AC4 (widened
     during story review to also cover `PessimisticLockingFailureException`, per M11 — not just
     `ResourceNotFoundException` as originally scoped).
3. Under `## Deferred from: code review of skillars-deferred-126-... (2026-09-21)`:
   - The `now()`/`clock_timestamp()` bullet — delete outright, fully fixed by AC5.
   - Leave every other (already `[CLOSED]`/`[DECIDED]`) bullet in that section untouched.
4. Add a `## Last audit: 2026-09-22 (skillars-deferred-128 story creation)` narrative section — update to
   `... dev-story completion` when implementation finishes — summarizing what closed, what was
   accepted-and-documented instead (bullet 2's reclassification, the tombstone-timing and
   `PessimisticLockingFailureException`-blast-radius tradeoffs from AC1/AC4), and the AC6 pivot (no
   `@SchedulerLock` added; ledger corrected instead), matching this series' established style.
5. Grep-sweep every file this story touches (`GdprErasureService`, `PlayerProfileRepository` — its
   `findByParentIdOrderByIdAsc` comment justifies `ORDER BY id` partly as "deterministic acquisition order"
   for the PARENT loop; after AC1 the loop holds one lock at a time and releases it before the next, so
   that specific rationale needs rewording, though the retry-budget-determinism half of the comment stays
   valid — `PlayerProfileRepository.java:16-22`, `PessimisticLockRetryer` — read-only, not modified, but
   its Javadoc's "revisit this" language may now be worth a small update noting the call site *was*
   revisited and a per-erase (not per-call) budget was added around it, `VideoDeletionOutboxRepository`,
   `RadarCompositeDlqRepository`, `application.yaml`) against the rest of the ledger for any other stale
   reference this story's changes might affect. (AC6 makes no production-code changes, so
   `UploadSessionExpiryScheduler`/`ReconciliationWorkerScheduler`/`WebhookEventProcessorScheduler`/
   `ModerationSlaMonitorService`/`AlertEvaluationService` are not part of this sweep.)

---

## Dev Notes

- **Module boundaries respected:** AC1/AC2/AC4 touch `platform.admin` (`GdprErasureService`) and, for a
  comment-only rewording, `platform.security.repo` (`PlayerProfileRepository`); AC3 touches
  `application.yaml` (comment-only); AC5 touches `platform.video.repo` and `platform.development.repo`;
  AC6 (post-review) touches **no production code** — see AC6's own Context for why. No cross-module
  leakage beyond what each AC's own fix requires.
- **No new Flyway migration is needed for any AC.** All ACs are Java/YAML/comment changes against
  existing schema and existing repository methods.
- **skillars-deferred-127 is merged** (`master@23c912cc`, PR #215) as of this story review
  (2026-09-22) — the original Dev Notes caveat about verifying it first is now moot; `HEAD` already
  reflects it and all `story/deferred-127-lock-config-scheduler-fixes@6e9b034c`-based citations survived
  the merge intact (confirmed during this review pass).
- **Testing convention (per this project's `docs/validation-strategy.md`):** targeted tests only for the
  affected classes — no `mvn verify` run locally. Frontend is untouched by this story.
- **Re-verify every file/line citation in this story against `HEAD` immediately before implementing each
  AC** — this story's own citations needed correcting twice already: once during creation (the "six
  `@Async` executors" → seven correction; the `AlertEvaluationService` "DB-touching" →
  "duplicate-notification" correction) and again during story review (the delete count, AC2's budget
  derivation, AC3's connection-pressure mechanism, and AC6's entire premise — see each AC's own
  review-correction callouts above for full detail; do not treat this story's first-draft reasoning as
  authoritative where a later correction is noted).

---

## File List (reconciled against the actual final diff)

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` (AC1, AC2, AC4)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1, AC2, AC4 tests)
- `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfileRepository.java`
  (comment-only reword of `findByParentIdOrderByIdAsc`'s Javadoc — AC1 changes its
  deterministic-acquisition-order rationale; see AC7 Task 5)
- `src/main/resources/application.yaml` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoDeletionOutboxRepository.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/development/repo/RadarCompositeDlqRepository.java` (AC5)
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`
  (Javadoc-only update noting the "revisit this" precondition was revisited — see AC7 Task 5; not a
  behavior change)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status → review at dev-story completion)

**Added applying `/bmad-code-review`'s Patch findings (2026-09-22) — see `## Review Findings` above:**

- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminQueueSummaryDto.java` (Decision 2
  — new `gdprErasureDeadlines` field)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminQueueService.java` (Decision 2 —
  summary bucket + `buildSummary` case)
- `src/test/java/com/softropic/skillars/infrastructure/persistence/ClaimStampUsesClockTimestampTest.java`
  (new — discharges AC5's own Tests-bullet fallback)

**No files under `platform.video.service` or `platform.notification.service` are touched** — AC6 (post
story-review correction) makes no production-code changes; see AC6's own Context.

**Not anticipated by this story's original Dev Notes ("No new Flyway migration is needed for any
AC") — added during implementation, see Dev Agent Record → Completion Notes for why:**

- `src/main/resources/db/migration/V152__admin_alerts_gdpr_erasure_deadline_type.sql` (new — AC2,
  widens `admin.admin_alerts`' two `CHECK` constraints so the new targeted `AdminAlert` can be
  written)
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java` (AC2, new
  `GDPR_ERASURE_DEADLINE` constant)
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertReferenceType.java` (AC2,
  new `GDPR_REQUEST` constant)

---

## Dev Agent Record

### Completion Notes

All 7 ACs implemented and tested. Every AC's own re-verification task was performed against actual
`HEAD` before implementing; citations matched exactly.

- **AC1 (narrow the `player_profiles` lock scope):** `GdprErasureService` gained a
  `PlatformTransactionManager txManager` field and a `TransactionTemplate requiresNewTemplate` built
  in a new `@PostConstruct initTemplates()`, mirroring `ModerationSlaMonitorService` exactly.
  `deletePlayerDevelopmentData(Long playerId)` (parameter list simplified — no longer takes a shared
  `blobKeysToDelete` list) now wraps its whole body — lock acquisition, all 11 deletes, the
  `performance_reports` scan, a per-child `List<String> childBlobKeys` enqueued via
  `blobDeletionOutboxSupport.enqueue(childBlobKeys)`, and the tombstone save — in
  `requiresNewTemplate.executeWithoutResult(...)`, committing and releasing its lock before returning.
  `erase()`'s own `blobKeysToDelete` was renamed `exportBlobKeysToDelete` and now only ever receives
  the GDPR-export-zip keys. A new `eraseParentChildren(UUID, Long)` helper replaces the old
  `.forEach(...)` PARENT-branch call site (also carries AC2/AC4's logic — see below); each processed
  child's outer-context `PlayerProfile` instance is `entityManager.detach(child)`ed in a `finally`
  block (M9) so `erase()`'s later `findByParentIdOrderByIdAsc` re-read for
  `AccountDeletionRequestedEvent.linkedPlayerIds` reads fresh, not Hibernate's first-level-cache-stale,
  rows. `deletePlayerDevelopmentData`'s Javadoc documents the two accepted tradeoffs (atomicity;
  early-committing sticky tombstone) and cross-references AC4. `PlayerProfileRepository
  .findByParentIdOrderByIdAsc`'s comment and `PessimisticLockRetryer`'s class Javadoc were both
  reworded (AC7 Task 5) — the retryer's own "revisit this" precondition now documents that it WAS
  revisited (AC2) and what residual risk remains (see AC7's `[DECIDED]` bullet).
- **AC2 (per-erase deadline):** added `private Duration gdprEraseLockBudget = Duration.ofSeconds(10)`
  — a plain instance field (not `static final`), deliberately, so
  `ReflectionTestUtils.setField(bean, "gdprEraseLockBudget", ...)` can override it per-test against the
  live Spring singleton, exactly the seam this story's own Tests section anticipated as the fallback if
  a plain-constant (not `ConfigBounds`) route was chosen. `eraseParentChildren` computes the deadline
  once before the loop, checks it before each child (not after), logs+raises a targeted `AdminAlert`
  and throws `IllegalStateException` on trip (propagates through `erase()` to `GdprEventListener`'s
  existing catch-all → `markFailed`). **Genuine gap found during implementation, not anticipated by the
  story text:** `raiseErasureDeadlineAlert` must run in `requiresNewTemplate`'s OWN `REQUIRES_NEW`
  transaction, not `erase()`'s own — the caller throws immediately after it returns, which rolls back
  `erase()`'s outer transaction, and without its own transaction the alert row would roll back right
  along with it and never actually be recorded. Fixed by wrapping the alert save in
  `requiresNewTemplate.executeWithoutResult(...)`, reusing the same template AC1 built.
- **AdminAlert taxonomy gap (found during implementation, resolved via `AskUserQuestion`):** neither
  `AdminAlertType` nor `AdminAlertReferenceType` had a value fitting a GDPR-erasure alert, and both are
  backed by a DB `CHECK` constraint (`V138__baseline_schema.sql`) limiting them to their existing
  values — directly contradicting this story's own Dev Notes ("No new Flyway migration is needed for
  any AC"). Asked the user; chose "add a small Flyway migration" over misusing an existing
  type/reference-type pair or skipping the alert. Added `V152__admin_alerts_gdpr_erasure_deadline_type
  .sql` (widens both `CHECK`s; `-- migration-lint: allow-validating-constraint` opt-out, justified
  inline — `admin_alerts` is a small internal admin-queue table, not the large/hot-table case rule 3
  exists to protect), plus `AdminAlertType.GDPR_ERASURE_DEADLINE` and
  `AdminAlertReferenceType.GDPR_REQUEST`. Per this project's rolling-deploy rule 5 ("CHECK widening
  precedes the first write by one release"), this would normally need to ship a release ahead of the
  code that writes the new values — but per skillars-deferred-117's owner decision (re-confirmed at
  V149, still true here — no production deploy of this application has ever happened), there is no live
  rolling-deploy window this could break today, so the widening and its first write ship together in
  this same migration, mirroring V145's own precedent for a first-time CHECK addition. First draft of
  the migration failed `MigrationConventionLintTest` (`DROP CONSTRAINT` without `IF EXISTS`); fixed.
- **AC3 (Hikari pressure documentation):** re-derived the DB-touching `@Scheduled`-methods-at-≤60s-
  cadence count from scratch (`grep -rn "@Scheduled" src/main/java`, cross-referenced each hit's own
  cadence and whether its body reaches a repository/`jdbcTemplate`) rather than trusting the ledger
  bullet's stale "ten" estimate: 11 methods (listed in the new `application.yaml` comment). Extended the
  existing `task.scheduling.pool` comment block with the connection-pressure math, the seven-pool
  inventory (corrected to "not all `@Async`" per `ExecutorShutdown`'s own authoritative count), and the
  `PessimisticLockRetryer` 2-thread sub-case note. Comment-only; `pool.size: 8` unchanged; confirmed via
  `git diff` that no non-comment line in `application.yaml` changed.
- **AC4 (skip-and-continue):** `eraseParentChildren`'s per-child loop wraps
  `deletePlayerDevelopmentData(child.getId())` in `try { … } catch (ResourceNotFoundException e) { … }
  catch (PessimisticLockingFailureException e) { … }` — two visibly distinct `catch` clauses (not
  collapsed), each `warn`-logging with distinguishable wording, neither aborting the loop. Both catches
  are scoped only to this PARENT-branch loop; the PLAYER branch's single call site is unchanged
  (`ifPresentOrElse`, resolves its own profile first, never reaches either exception).
- **AC5 (`clock_timestamp()`):** both `claimPendingBatch` (the `claimed_at = now()` write) and
  `resetStaleClaimed` (the `claimed_at < now() - make_interval(...)` read) changed to
  `clock_timestamp()` in both `VideoDeletionOutboxRepository` and `RadarCompositeDlqRepository` — four
  call sites total. `next_retry_at <= :now`/other `now()` usages left untouched, per the story's own
  explicit scope limit. Comments updated to describe `clock_timestamp()`'s statement-accurate,
  transaction-boundary-independent semantics while preserving the still-accurate "matches
  `ShedLockConfig.usingDbTime()`"/"DB time, not app time" framing.
- **AC6 (ledger correction, no code change):** re-verified the per-method table against `HEAD` —
  `AlertEvaluationService.computeMetricValue` is still a `return -1.0` stub (confirmed unreachable), and
  all four `SKIP LOCKED` queries (`UploadSessionRepository.findExpiredPendingForUpdate`,
  `VideoRepository.findNonTerminalForUpdate`/`findScanningOlderThan`,
  `VideoWebhookEventRepository.findPendingForUpdate`) are unchanged. No production code touched for
  this AC, confirmed by the final `git status --short` diff above.
- **AC7 (ledger closeout):** re-verified the actual diff against this File List before editing
  `deferred-work.md`. Deleted outright: the FK-lock-exposure bullet, the Hikari-pressure bullet, the
  five-schedulers bullet, the `orElseThrow` bullet (all from the `skillars-deferred-127` review
  section), and the `now()`/`clock_timestamp()` bullet (the `skillars-deferred-126` section's one
  remaining open item). Reclassified, not deleted: the `PessimisticLockRetryer` bullet, now
  `[DECIDED: accepted risk — skillars-deferred-128]` with the corrected aggregate-vs-single-child
  framing. Added a narrower `[DECIDED: accepted risk — skillars-deferred-128 — AlertEvaluationService]`
  note for AC6's re-open condition. New `## Last audit: 2026-09-22 (skillars-deferred-128 dev-story
  completion)` heading added directly above the `skillars-deferred-126` "Deferred from" section,
  matching the file's own precedent of positioning the newest audit summary immediately above the
  sections it closes bullets in.

### Validation performed

Targeted tests only, per this project's `docs/validation-strategy.md` convention — no `mvn verify` run
locally.

`mvn -o -Dtest=GdprErasureIT test`: **27 tests, 0 failures, 0 errors** (21 pre-existing + 6 new
PARENT-branch tests — happy path, lock-released-early, later-failure-does-not-roll-back-committed-
child, deadline-exceeded, vanished-child, contended-child — every one genuinely new coverage per
story-review H3, the PARENT branch had zero prior tests). Re-run twice to check for flakiness in the
concurrency-timing-sensitive tests (lock-released-early, contended-child); both runs green.

`mvn -o -Dtest=MigrationConventionLintTest test`: **30 tests, 0 failures** (after the `IF EXISTS` fix
above).

`mvn -o -Dtest=VideoDeletionOutboxProcessorIT,RadarCompositeDlqRepositoryIT test`: **21 tests, 0
failures** (AC5's `clock_timestamp()` change; also exercises V152 applying cleanly via Flyway on
Testcontainers boot).

`mvn -o -Dtest=AdminQueueIT,AdminAlertEventListenerTest test`: **17 tests, 0 failures** (AC2's new
`AdminAlertType`/`AdminAlertReferenceType` constants against the admin-alert queue/read paths).

`mvn -o -Dtest=PessimisticLockRetryerCallSiteAuditTest,PessimisticLockRetryerTest test`: **12 tests, 0
failures** — confirms AC1's refactor did not add/remove any `.withBoundedRetry(` call site (still
exactly 31, the audit test's own hard-coded expectation).

Broader regression sweep (every touched package plus its siblings):
`mvn -o -Dtest='com.softropic.skillars.platform.admin.**,com.softropic.skillars.platform.video.**,com.softropic.skillars.platform.development.**,com.softropic.skillars.infrastructure.persistence.**,com.softropic.skillars.platform.security.**,com.softropic.skillars.db.**' test`
— **1139 tests, 0 failures, 0 errors, 4 skipped, BUILD SUCCESS** (7m50s). No regressions found from
AC1's lock-scope refactor, AC2's deadline logic, AC4's skip-and-continue catches, or AC5's
`clock_timestamp()` change anywhere in `platform.admin`, `platform.video`, `platform.development`,
`infrastructure.persistence`, `platform.security`, or the migration-lint suite (`db`). Frontend is
untouched by this story (confirmed via `git status --short` — no `src/frontend/**` diff); no
`frontend-tests` PR label needed.

**Validation of the `/bmad-code-review` Patch-finding round (2026-09-22), targeted per
`docs/validation-strategy.md`:**

`mvn -o -Dtest=GdprErasureIT test`, run twice to re-check the rewritten concurrency-timing tests
(lock-release, vanished-child) for flakiness: **27 tests, 0 failures, 0 errors** both runs (~59s
each).

`mvn -o -Dtest=AdminQueueIT,ClaimStampUsesClockTimestampTest,VideoDeletionOutboxProcessorIT,RadarCompositeDlqRepositoryIT test`:
**34 tests, 0 failures, 0 errors** — confirms `AdminQueueSummaryDto`'s new field/case and the new
`ClaimStampUsesClockTimestampTest` (AC5's Tests-bullet fallback) both pass, and the
`clock_timestamp()` comment rewording introduced no behavior change.

`mvn -o -Dtest=PessimisticLockRetryerCallSiteAuditTest,PessimisticLockRetryerTest test`: **12 tests,
0 failures** — confirms this round's edits (the `volatile` field, the `nanoTime()` deadline, the L5
comment) did not add/remove any `.withBoundedRetry(` call site.

---

## Change Log

- 2026-09-22: Story created via manual ledger-mining process (this project's established convention for
  `skillars-deferred-N` stories). Mined skillars-deferred-127's own fresh same-day code-review deferral
  (5 bullets, all open, none `[DECIDED]`/`[CLOSED]`) plus the one still-genuinely-open bullet remaining
  from skillars-deferred-126's own deferral section (`now()`/`clock_timestamp()`). All citations
  independently re-verified against `story/deferred-127-lock-config-scheduler-fixes@6e9b034c`
  (skillars-deferred-127's own final post-code-review commit) — this worktree's own checkout predates
  skillars-deferred-127 entirely, so verification was done via `git show` against that branch tip rather
  than this worktree's own files (see Dev Notes). Four owner decisions taken live (`AskUserQuestion`,
  reported by the requesting session, not re-asked here): (1) narrow the `player_profiles` FOR UPDATE lock
  scope so `deletePlayerDevelopmentData` commits independently, releasing the lock before erase()'s
  unrelated downstream steps; (2) add a per-erase deadline bounding total lock-holding/backoff time across
  a PARENT's children; (3) document (not change) the Hikari connection-pressure derivation behind the
  scheduler pool size 8; (4) skip a vanished child and continue remaining siblings in the PARENT erasure
  loop instead of failing the whole request. Two further mechanical fixes bundled in, no decision needed:
  (5) `clock_timestamp()` instead of `now()` for the outbox/DLQ claim-stamp writes; (6) `@SchedulerLock`
  added to five previously-unlocked `@Scheduled` methods. Two corrections found during this story's own
  verification, not present in the original ledger bullets: the Hikari-pressure bullet's "six `@Async`
  executors" estimate is actually seven, per `ExecutorShutdown.java`'s own authoritative, previously
  self-corrected count; and `AlertEvaluationService.evaluate()` — one of the "five DB-touching" scheduler
  bullet's five methods — is not actually DB-touching at all (confirmed by its own class Javadoc and
  method body), so AC6 documents its real multi-instance risk as duplicate ops-alert emails rather than
  repeating the imprecise "DB-touching" framing for that one method.

- 2026-09-22: `/story-review` response applied (senior-dev pre-implementation audit, `story-review.md`).
  3 "must fix" + 11 "should fix" findings, every one independently re-verified against actual source
  (`GdprErasureService`, `BlobDeletionOutboxSupport`, `PessimisticLockRetryer`, `GdprEventListener`,
  `GdprRequestService`, `ModerationSlaMonitorService`, `AlertEvaluationService`,
  `WebhookEventProcessorScheduler`, `UploadSessionExpiryScheduler`, `ReconciliationWorkerScheduler`,
  `VideoRepository`, `UploadSessionRepository`, `RadarCompositeCalculationService`, `GdprErasureIT`)
  before applying — zero false positives. Four owner decisions taken live (`AskUserQuestion`): (1) AC6 —
  narrow per-method rather than drop outright or apply blindly; per-method analysis then found none of the
  five targets clears the bar (four already fully protected by `FOR UPDATE SKIP LOCKED`, adding a lock
  would regress throughput and, for two, introduce a new bug; the fifth is unreachable dead code) — AC6
  now makes no production-code change and instead corrects the ledger's understanding; (2) AC4's
  skip-and-continue widened to also cover `PessimisticLockingFailureException` (a contended child), not
  only `ResourceNotFoundException` (a vanished child); (3) AC1's early-committed sticky tombstone
  (composites permanently stop updating if `erase()` later fails) is documented as an accepted tradeoff,
  not fixed by moving the tombstone to the outer transaction; (4) AC2 keeps its cross-child deadline scope
  but gains a targeted `AdminAlert` on deadline-exceeded, and ledger bullet 2 is now annotated
  `[DECIDED: accepted risk]` rather than deleted, since the deadline does not bound a single child's own
  connection-hold time. Most significant "must fix": AC1 as originally drafted moved
  `deletePlayerDevelopmentData`'s deletes into their own transaction while leaving the per-child
  performance-report blob-deletion enqueue on `erase()`'s outer path — since the `performance_reports`
  rows are the only record of their S3 keys, any failure between the inner commit and the outer commit
  would have permanently orphaned those blobs, a real Article 17 regression the story's own "accepted
  tradeoff" framing did not account for; fixed by moving the per-child enqueue inside the same inner
  transaction. Second: the entire PARENT branch of `GdprErasureService.erase` (exercised by AC1/AC2/AC4)
  has zero existing test coverage — no `erase_parentUser_*` test exists and no fixture sets `parent_id` —
  so the original "existing tests must stay green" claims were vacuous; a new multi-child fixture is now
  explicit, required scope. Also corrected: AC1's delete count (11 across 11 tables, not 13/14 across 13
  — inherited from the ledger bullet without re-counting); AC2's budget derivation (was based on Hikari's
  unrelated `connection-timeout`; re-derived from `erase()` running synchronously on the request thread);
  AC2's test-seam pointer (the cited `RadarCompositeCalculationServiceConcurrencyIT` precedent does not
  exist; a `ConfigBounds` key + `@TestPropertySource` seam is specified instead); AC3's connection-pressure
  mechanism (only 2 scheduled methods actually reach `PessimisticLockRetryer`, not 8 — the real "8 of 25"
  claim rests on transaction-duration connection-holding, not retryer backoff) and its "seven `@Async`
  executors" wording (one of the seven, `storageUploadExecutor`, is a raw SDK `ThreadPoolExecutor`, not an
  `@Async` target). Several lower-priority documentation gaps also closed: stale Dev Notes (deferred-127 is
  now merged), a stale outer-persistence-context staleness note (AC1's `PlayerProfile` re-read at `:191`),
  an `EntityManager`-rebind verification step, a narrowed/pinned AC4 catch-clause assumption, and a
  per-row-`clock_timestamp()`-evaluation verification note on AC5 (confirmed safe: skillars-deferred-124
  AC4 already moved the claim-identity predicate to `claimed_by`). See `story-review.md` for full
  finding-by-finding detail.

- 2026-09-22: `/bmad-dev-story` implementation complete, status → review. All 7 ACs implemented and
  tested (targeted tests per `docs/validation-strategy.md`, no local `mvn verify`). Summary: AC1
  narrows the `player_profiles` FOR UPDATE lock scope (`deletePlayerDevelopmentData` now commits, with
  its per-child blob-deletion enqueue, in its own `REQUIRES_NEW` transaction, releasing the lock before
  `erase()`'s unrelated downstream steps); AC2 adds a ~10s per-erase deadline across a PARENT's
  children with a targeted `AdminAlert` on trip; AC3 documents (no behavior change) the Hikari
  connection-pressure derivation behind the 8-thread scheduler pool; AC4 makes the PARENT loop
  skip-and-continue past a vanished or lock-contended child instead of failing the whole request; AC5
  switches the outbox/DLQ claim-stamp writes from `now()` to `clock_timestamp()`; AC6 makes no code
  change (re-verifies and records why none of the five originally-flagged schedulers need
  `@SchedulerLock`); AC7 closes the ledger. One genuine gap surfaced during implementation and resolved
  live via `AskUserQuestion`, not anticipated by the story text: AC2's new targeted `AdminAlert` needs
  an `AdminAlertType`/`AdminAlertReferenceType` pair, but both enums are backed by a DB `CHECK`
  constraint with no fitting existing value — resolved by adding a small Flyway migration
  (`V152__admin_alerts_gdpr_erasure_deadline_type.sql`) rather than misusing an existing type or
  skipping the alert, directly superseding this story's own Dev Notes claim that no migration was
  needed. A second implementation-time finding, not in the story text: the new `AdminAlert` must be
  raised in its own `REQUIRES_NEW` transaction (reusing AC1's `requiresNewTemplate`), since the caller
  throws immediately afterward and would otherwise roll the alert back along with `erase()`'s own
  transaction. `gdprEraseLockBudget` was implemented as a plain (non-`static final`) instance field
  rather than a new `ConfigBounds` key, per the story's own "starting point, not a mandate" language —
  chosen for lower scope/risk; test seam is `ReflectionTestUtils.setField` against the live Spring
  singleton bean, exactly the fallback the story's own Tests section anticipated. See Dev Agent Record
  above for full validation detail (107 targeted tests across five focused runs + a 1139-test broader
  regression sweep, zero regressions).

- 2026-09-22: `/bmad-code-review` response applied (22 findings across four parallel layers — Blind
  Hunter, Edge Case Hunter, Acceptance Auditor, `/txn-and-concurrency-audit`; 4 dismissed in triage, 3
  resolved live via `AskUserQuestion` as Decisions, 18 applied as Patch items, 1 accepted as
  pre-existing/deferred). All three Decisions took the option consistent with this story's own
  existing decision lineage (AC2's alert-on-narrow-cause precedent; AC4's own skip-and-continue
  choice; `deletePlayerDevelopmentData`'s own "re-driving makes genuine forward progress" premise).
  Most significant Patch fixes: two concurrency-timing tests (lock-release, vanished-child) were
  tautological or non-reproducing — rewritten with deterministic, independently-controlled
  synchronization instead of racing an outcome the bug/fix pair could not actually distinguish; the
  atomicity test's 1ms-budget race was replaced with a `@MockitoSpyBean` on a genuine post-loop step
  (mirrors `AccountDeletionCascadeIT`'s own established pattern); the deadline test's `FAILED`
  assertion was fabricated by the test itself — rewritten to route through the real HTTP +
  `AFTER_COMMIT` listener path. AC4's skip-and-continue now also raises a targeted `AdminAlert` per
  skipped child (reusing `GDPR_ERASURE_DEADLINE`, distinguished by a new `reason` column value,
  deduplicated per-request) and re-drives make genuine forward progress (children already tombstoned
  are filtered out before the loop, not re-processed). The deadline itself moved from `Instant.now()`
  to `System.nanoTime()` (NTP-step-proof, consistent with AC5's own clock-coupling removal). See the
  `## Review Findings` section above for the full finding-by-finding disposition and outcome notes.
  Validation: `GdprErasureIT` run twice (27/27 green both times, no flakiness in the rewritten
  concurrency tests) plus targeted runs on every other touched suite — see Dev Agent Record's
  Validation performed section above.

---

## Review Findings

`/bmad-code-review` 2026-09-22 — four parallel layers (Blind Hunter, Edge Case Hunter, Acceptance
Auditor, plus `/txn-and-concurrency-audit` as a fourth layer at the reviewer's request). 22 findings
survived triage; 4 dismissed. Every finding below was independently re-verified against source by the
orchestrator before being written here.

### Decisions (resolved 2026-09-22 via AskUserQuestion — all three took the option consistent with this story's own existing decision lineage, no prior owner decision overridden)

- [x] [Review][Decision] **RESOLVED — option 1: raise a targeted `AdminAlert` per skipped child, keep `COMPLETED`.** Rationale: AC2 Task 4 already established the owner's position for this exact shape ("a PARENT that reliably exceeds the budget produces a FAILED request with no admin alert and no auto-retry, indefinitely") — a silent/indefinite/no-auto-retry outcome gets a targeted alert, not a status change. Preserves AC4's skip-and-continue decision and its tests; reuses the `GDPR_REQUEST` reference type and V152 already shipped. Paired with the AC4 (L5) pinning-comment patch below, which is what keeps the catch from silently widening. Original finding: **A lock-contended child is skipped, yet the request is still stamped `COMPLETED` — that child is never erased and there is no re-drive path** — `GdprErasureService.java:300-309` + `:211-213`. Found independently by all three adversarial layers (high confidence each). AC4's `catch (PessimisticLockingFailureException)` swallows the failure and `erase()` falls through to `request.setStatus("COMPLETED")`. Verified: `GdprRequestService.requestErasure:83` only blocks a new request while an ERASURE is `PENDING`/`PROCESSING`, so a `COMPLETED` request is never revisited; the parent's account is already anonymised and `setLocked(true)`/`setActivated(false)` with all refresh tokens revoked (`:131-142`, `:195`), so the user cannot self-serve a re-drive; there is no admin re-drive endpoint (`GdprEventListener.onErasureRequested` is `erase`'s only caller). The only trace is a `log.warn` — no `AdminAlert`, unlike the deadline path at `:284`. Two aggravating factors: (a) the catch wraps the *whole* inner transaction, so `DeadlockLoserDataAccessException`/`CannotSerializeTransactionException` (40P01/40001) raised by any of the 11 bulk deletes at `:436-453` — long after the retryer returned successfully — is swallowed under the same "retry budget exhausted" log line; (b) the accepted tombstone tradeoff at `:398-408` is justified by "re-driving makes genuine forward progress", which this finding falsifies — an already-tombstoned child of a request that ends `COMPLETED` has its radar composites permanently frozen with no reset path. Pre-diff, this exception propagated to `markFailed` and was visible. `GdprErasureIT.java:1037-1041` currently pins the new behaviour as correct. **Options:** (a) keep skip-and-continue but raise an `AdminAlert` per skipped child; (b) track skipped children and end the request `FAILED` rather than `COMPLETED`; (c) narrow the catch to the lock-acquisition call only, letting deletion-phase failures propagate as before. AC4's owner decision explicitly chose "skip and continue", so overriding it is the owner's call.
- [x] [Review][Decision] **RESOLVED — option 1: add a `gdprErasureDeadlines` field to `AdminQueueSummaryDto` + a `buildSummary` case + an exists-check dedupe before raising; the resolution path is NOT added.** Rationale: this discharges precisely what V152's own header claimed as the justification for the new enum value ("would misrepresent this alert's real subject in the queue UI and in `AdminQueueService`'s per-type counts"), and prevents a repeat of the `AdminAlertRepository:53-63` lesson. A generic dismiss endpoint is out of scope — every existing alert type resolves via a domain action, so adding one would set a new precedent this story did not sanction. Original finding: **The new `GDPR_ERASURE_DEADLINE` alert has no resolution path, no dedupe, and no summary bucket** — `GdprErasureService.java:335-343`, `AdminQueueService.java:220-228`, `AdminQueueSummaryDto`. Verified: every other alert type is closed by a domain action keyed to its own reference type (`AdminMessageService:180`, `AdminConversationService:88`, `DisputeService:360`, `AdminCoachEnforcementService:536`) or by the orphan sweeper `resolveOpenAlertsForDeletedMessages()`, which is hard-scoped to `reference_type = 'MESSAGE'`. Nothing resolves `GDPR_REQUEST`, and the admin API exposes only `GET /queue` and `GET /queue/summary` — no dismiss endpoint. Separately, `AdminQueueSummaryDto` is a fixed six-bucket record plus `total`, and `getSummary()` sums *all* counts into `total` — so one open GDPR alert makes `total` strictly exceed the sum of its own reported buckets, growing monotonically since the alerts never close. `buildSummary`'s `default -> ""` also renders it with a blank summary in the list view. This directly undercuts V152's own stated justification for the new enum value ("would misrepresent this alert's real subject in the queue UI and in `AdminQueueService`'s per-type counts"). **Options:** (a) add a `gdprErasureDeadlines` field to `AdminQueueSummaryDto` (an admin API contract change) + a `buildSummary` case; (b) add an admin dismiss endpoint; (c) auto-resolve on a subsequent successful erasure of the same `requestId`; (d) accept and document.
- [x] [Review][Decision] **RESOLVED — option 1: filter out children whose `developmentDataErasedAt` is already set.** Rationale: `deletePlayerDevelopmentData`'s own accepted-tradeoff (a) rests on the premise that "re-driving makes genuine forward progress" — filtering makes that premise true rather than overriding it, and reuses the tombstone's already-established "done, never revisit" semantics from `recalculateComposite`. Original finding: **A deadline-truncated erasure is not resumable — a re-drive restarts from child 0 and re-spends the whole budget on already-tombstoned children** — `GdprErasureService.java:272-290`, `:425-429`. The `children` list is unfiltered and `deletePlayerDevelopmentData` never inspects `developmentDataErasedAt` before taking the lock and re-running all 11 bulk deletes against empty sets. Every re-drive therefore re-contends `player_profiles` for children that are already done, with no forward-progress guarantee — a PARENT with enough children can never finish. A `filter(c -> c.getDevelopmentDataErasedAt() == null)` would make the loop resumable, but whether a tombstone alone is sufficient proof a child is "done" is a semantics call, and it interacts with the decision above.

### Patch

- [x] [Review][Patch] **[from Decision 1]** Raise a targeted `AdminAlert` (`GDPR_ERASURE_DEADLINE` / `GDPR_REQUEST`) for each child skipped by either AC4 catch, so a skipped child is no longer visible only as a `log.warn` [`GdprErasureService.java:292-310`] — **Fixed.** `raiseErasureDeadlineAlert` generalized to `raiseErasureAlert(requestId, reason)`, called from both AC4 catches with `reason="CHILD_VANISHED"`/`"CHILD_CONTENDED"` (deadline path keeps `"DEADLINE_EXCEEDED"`); deduplicated per-request via Decision 2's own exists-check. New assertions added to both AC4 tests.
- [x] [Review][Patch] **[from Decision 2]** Add `gdprErasureDeadlines` to `AdminQueueSummaryDto`, a matching `buildSummary` case, and an exists-check dedupe before raising the alert [`AdminQueueService.java:172`, `:220-228`, `AdminQueueSummaryDto.java`] — **Fixed.** Dedupe uses the already-existing `AdminAlertRepository.findFirstByReferenceIdAndTypeAndStatus`; `buildSummary`'s new case prefixes with `reason` (mirrors `MODERATION_UNRESOLVED`'s pattern).
- [x] [Review][Patch] **[from Decision 3]** Skip children whose `developmentDataErasedAt` is already set, so a re-driven erasure makes real forward progress instead of re-spending the budget from child 0 [`GdprErasureService.java:272-276`] — **Fixed.** `eraseParentChildren` now filters `findByParentIdOrderByIdAsc`'s result by `developmentDataErasedAt == null` before computing the deadline.
- [x] [Review][Patch] Vanished-child test never reaches the catch it claims to cover — B is deleted *before* `erase()`, so it never enters the `children` list [`GdprErasureIT.java:959-966`] — **Fixed.** Rewritten to hold B's lock from a second connection BEFORE `erase()` starts (so B IS in the list read), then delete+commit while `PessimisticLockRetryer` is mid-retry on B — its next lock acquisition finds the row genuinely gone, throwing the real `ResourceNotFoundException`.
- [x] [Review][Patch] Lock-release test's sync point is tautological — `awaitChildTombstoned(A)` observes only committed state, which under the old wide-lock code coincides with B's lock release, so it passes identically pre-narrowing [`GdprErasureIT.java:848`, `:869-873`] — **Fixed.** Rewritten with two independently-controlled, fixed-duration lockers (A short, B long) and a fixed post-latch delay chosen relative to those known durations, not to any outcome `erase()` itself produces — see the rewritten test's own Javadoc for why the old sync point could not distinguish the fix from the bug.
- [x] [Review][Patch] Atomicity test turns on a 1 ms budget — a sub-millisecond gap between two adjacent statements decides the outcome; comment also describes a negative budget while the code passes `+1 ms`. AC1's Tests bullet prescribed spying a post-loop step instead [`GdprErasureIT.java:890`] — **Fixed.** Rewritten using a `@MockitoSpyBean RefreshTokenRepository` (mirrors `AccountDeletionCascadeIT`'s established spy pattern) throwing from `markAllUsedByUserId` — the first post-loop step — instead of racing AC2's deadline. Now also proves BOTH children survive, not just A.
- [x] [Review][Patch] Deadline test's `FAILED` assertion is tautological — the test calls `markFailed` itself; the `processed={}/{}` log AC2 names as its diagnosability deliverable is never asserted [`GdprErasureIT.java:930-936`] — **Fixed.** Rewritten to route through the real HTTP + `AFTER_COMMIT` listener path so `FAILED` is genuinely produced by `GdprEventListener`'s own catch, not fabricated by the test. The `processed=0/2` fact is asserted via the same observable the log line reports (neither child tombstoned) — noted inline that the log text itself isn't independently assertable without a log-capturing appender.
- [x] [Review][Patch] Contended-child test's 4000 ms hold leaves under ~800 ms of margin for `erase()`'s preamble before the retry budget exhausts [`GdprErasureIT.java:990`, `:1041`] — **Fixed.** Raised to 6000 ms; `.get()` timeouts extended to 20s accordingly.
- [x] [Review][Patch] Executor and DB row lock leak — `awaitChildTombstoned` and the timed insert run outside the `try`/`finally` that shuts the executor down, and the sync point throws on exactly the regression the test exists to catch [`GdprErasureIT.java:848-865`] — **Fixed.** Whole body (locker submission through final assertions) now runs inside one `try { … } finally { executor.shutdownNow(); … }` in the rewritten lock-release test.
- [x] [Review][Patch] H1 outbox regression guard counts `reports/%` rows schema-wide, so it passes even if the enqueue were deleted entirely [`GdprErasureIT.java:901-905`] — **Fixed.** `seedParentChildren()` now returns each child's own exact `storage_key`; the atomicity test asserts `payload->>'storageKey' = ?` per child instead of a schema-wide `LIKE`.
- [x] [Review][Patch] PLAYER branch omits the `entityManager.detach` the PARENT branch added — `PlayerProfile` has no `@Version` and is not `@DynamicUpdate`, so any future edit to `pp` in the outer transaction would rewrite the stale snapshot and reset the one-way tombstone to NULL [`GdprErasureService.java:182-184`] — **Fixed.** `entityManager.detach(pp)` added right after `deletePlayerDevelopmentData(pp.getId())` in the PLAYER branch.
- [x] [Review][Patch] `gdprEraseLockBudget` is non-volatile but written by the test thread and read from executor threads in two concurrency tests [`GdprErasureService.java:110`] — **Fixed.** Field marked `volatile`.
- [x] [Review][Patch] `processed` counts only successes, so a run where contended children burned the entire budget reports `processed=0/N` and points the operator at the wrong cause [`GdprErasureService.java:281-291`] — **Fixed.** Added a `skipped` counter, incremented in both AC4 catches; both the log line and the exception message now report `processed={}/{} children (skipped={})`.
- [x] [Review][Patch] Javadoc over-claims the budget "bounds the total wall-clock time" — it is sampled only between children, so the real worst case is budget + one child's full retry budget [`GdprErasureService.java:98-104`, `:256-257`] — **Fixed.** Both the field's and `eraseParentChildren`'s own Javadoc reworded to "bounds how many children can be ATTEMPTED," with the budget-plus-one-child worst case spelled out.
- [x] [Review][Patch] AC3's pool accounting is stale on arrival — `REQUIRES_NEW` suspends `erase()`'s transaction without releasing its connection, so each in-flight erasure now pins two of the 25 Hikari connections (three transiently on the deadline path); the new derivation block does not mention the doubling this same story introduced [`application.yaml:64-95`] — **Fixed.** New "STALE ON ARRIVAL" comment paragraph added directly after the existing derivation, documenting the doubling without changing the 8-of-25 scheduler-pool math (GDPR erasure is a low-frequency admin call, not a `@Scheduled` job).
- [x] [Review][Patch] AC4 Task 3's (L5) "pinning" comment was never written, though the Change Log claims it was — nothing warns that the two catches are precise only by construction [`GdprErasureService.java:292-310`] — **Fixed.** Comment added inside the `try` block, right before `deletePlayerDevelopmentData(child.getId())`.
- [x] [Review][Patch] AC7's `[DECIDED: accepted risk — skillars-deferred-128 — AlertEvaluationService]` note does not exist in the deferred section; the only three occurrences are self-referential narrative claiming it was added [`deferred-work.md:2807`, `:2811`, `:2831`] — **Fixed.** The literal bracketed marker now appears inline in the AC6 closeout bullet itself, not only in narrative referring to it.
- [x] [Review][Patch] AC5's Tests fallback not discharged — no SQL-text test was added and no "why not" rationale was recorded in the Dev Agent Record, so nothing guards a revert to `now()` [story AC5 Tests bullet 2] — **Fixed.** Added `ClaimStampUsesClockTimestampTest` (plain reflection test, no Spring context) asserting both repositories' `claimPendingBatch`/`resetStaleClaimed` `@Query` text contains `clock_timestamp()` and not `now()`.
- [x] [Review][Patch] The deadline uses non-monotonic `Instant.now()` for an elapsed-time budget, in a diff whose other half exists to remove clock-semantics coupling; an NTP step makes it unreachable or trips it spuriously [`GdprErasureService.java:274`, `:280`] — **Fixed.** Switched to a `System.nanoTime()`-based deadline (overflow-safe subtraction idiom), immune to wall-clock steps.
- [x] [Review][Patch] `clock_timestamp()` comment over-claims — it says the coupling is removed "regardless of any future change" to `process()`'s transactionality, but it fixes only the timestamp symptom; a transactional `process()` would break claim visibility and `deleteAsset` side-effect atomicity far worse [`VideoDeletionOutboxRepository.java:38-48`] — **Fixed.** Softened in both `VideoDeletionOutboxRepository` and `RadarCompositeDlqRepository` (the latter is where the literal "regardless of any future change" phrase actually lived) to scope the claim to "this timestamp symptom specifically."
- [x] [Review][Patch] `resetStaleClaimed`'s comment claims "both sides of this comparison now use the DB's true current time" — `claimed_at` is a stored column, not a clock call [`VideoDeletionOutboxRepository.java:113-117`] — **Fixed.** Reworded to state only the right side is a live clock call; `claimed_at` is a stored value written once at claim time.

### Deferred

- [x] [Review][Defer] No `lock_timeout`/`statement_timeout` on the inner transaction's 11 bulk deletes [`GdprErasureService.java:436-453`] — deferred, pre-existing. The deletes carry no `NOWAIT` (unlike `findByIdForUpdate`) and the transaction sets no `lock_timeout` (unlike `RadarCompositeCalculationService`'s `set_config`), and there is no global one. A blocked `DELETE` hangs the request thread unbounded while holding the child's `player_profiles` lock — the deadline at `:280` is never re-evaluated because control never returns to the loop top. The story already annotates this residual as `[DECIDED: accepted risk — skillars-deferred-128]` and names the fix as future work in `PessimisticLockRetryer`'s own Javadoc.
