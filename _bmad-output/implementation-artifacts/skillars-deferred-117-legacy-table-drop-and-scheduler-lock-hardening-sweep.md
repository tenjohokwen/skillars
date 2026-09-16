# Story: Legacy Table Drop, Scheduler Starvation Fix & Cross-Module Robustness Sweep

**Story Key:** `skillars-deferred-117-legacy-table-drop-and-scheduler-lock-hardening-sweep`
**Epic:** Deferred Work
**Priority:** Medium-High (one confirmed, worsening production bug — AC2 — plus a real cross-user race, a real memory leak, and closing a long-blocked cleanup item)
**Status:** ready-for-dev
**Created:** 2026-09-16

---

## Provenance & Scoping (read before starting)

This story was mined from `_bmad-output/implementation-artifacts/deferred-work.md` per the user's
explicit bucket order (genuine one-off bugs first, then a named list of specific buckets). **Re-verifying
every named bucket against HEAD at story-creation time found all of them already closed except one:**

| User's named bucket | Status found at HEAD |
|---|---|
| Genuine one-off bugs & gaps | Formally exhausted per repeated audits (2026-09-09 through 2026-09-16); one new finding surfaced by this story's own investigation, see AC2 |
| Drop `main.pending_blob_deletions` | **Still open** — see AC1 |
| `QuotaConfigService.resolveTierKey` player-tier gap | Closed (`skillars-deferred-113` AC3) |
| Concurrent-retry edge case | Closed (`skillars-deferred-114` AC1 — Postgres advisory lock) |
| Defensive-not-curative throw | `[DECIDED 2026-09-16 (skillars-deferred-114)]` — diagnostics-only, accepted |
| Fail-open tier fallback | Closed (`skillars-deferred-114` AC3 — fallback-shape observability added) |
| Check-then-use quota lookup | `[DECIDED 2026-09-16 (skillars-deferred-114)]` — accepted tradeoff |
| Manual runbook checklist | Closed (`skillars-deferred-114` AC5 — `POST /v1/admin/ses/preflight`) |
| No automated health-check integration | Closed (`skillars-deferred-114` AC5 — same endpoint) |

**Owner decisions taken before drafting this story (2026-09-16, both confirmed with the user directly):**

1. **`main.pending_blob_deletions`'s own closing condition** ("confirmed deployed and provably empty in
   every environment") **has never been met because no production deploy has ever happened at all** —
   the condition can never trigger under its own literal wording pre-launch. Owner decided: **proceed with
   the drop now**, since with zero production deploys there is no live "old release still reading the
   table mid-rolling-deploy" scenario for the expand/contract convention to protect against. See AC1.
2. With the named buckets exhausted and genuine one-off bugs thin, the owner explicitly relaxed the
   bundling bar: **pull in real architectural hardening items previously left as "recorded, not
   actionable"** rather than force a small story. AC3/AC4/AC5 below are the result — each re-verified as a
   genuinely reachable defect at HEAD, not merely speculative, before being included.

**AC2 is a new finding, not a pre-existing ledger bullet.** While re-verifying the leftover
`## Deferred from: code review of story-115 (2026-09-16)` section's `markPurged()` bullet — which the
ledger itself frames as "confirmed inefficiency not data loss" — tracing the actual query this story
found the ledger's own characterization understated it: this is a **worsening starvation bug**, not mere
inefficiency. See AC2 for the full trace.

**Do not re-open or re-litigate:** any `[DECIDED ...]` / `[DISMISSED ...]` bullet anywhere in
`deferred-work.md`, including the two named directly above. Do not re-audit schedulers already hardened by
`skillars-deferred-115`/`-116` (`ModerationSlaMonitorService`, `VideoLifecycleScheduler`'s BLOCKED→ARCHIVED
phase, `SubscriptionService`) — this story's scope is exactly the five ACs below.

---

## User Story

As a **Platform Engineer**, I want the long-blocked `pending_blob_deletions` legacy-table cleanup finally
closed, the `VideoLifecycleScheduler` starvation bug that permanently re-selects already-purged videos
fixed, the session-pack forfeiture scheduler's real extend/pause race closed, the in-process rate limiter's
unbounded memory growth fixed, and the pessimistic-lock retry helper's undocumented idempotency contract
turned into an enforced one, so that **none of these five independently-verified, reachable defects keeps
degrading the system as usage grows**.

---

## Acceptance Criteria

### AC1: Drop `main.pending_blob_deletions` + delete its now-unused Java/JPA surface

**Given** `skillars-deferred-100` (2026-09-08) stopped **writing** `main.pending_blob_deletions` (folded
onto the generic `platform.outbox`) but kept `PendingBlobDeletion` (entity), `PendingBlobDeletionRepository`,
and `PendingBlobDeletionResidualDrainRunner` (a startup `ApplicationRunner`) so any row a prior release
left behind could still be **read** once and migrated onto the generic outbox,

**And** the table is still declared in the post-rebaseline `V138__baseline_schema.sql:740-756,2408-2412`
(carried forward by `skillars-deferred-112`'s squash, not dropped),

**And** the item's own stated closing condition — "once confirmed deployed and `pending_blob_deletions` is
provably empty in every environment" — was re-confirmed still unmet as recently as the 2026-09-15 ad-hoc
spot-check, because **no production deploy of this application has ever happened, at all, in any release**
(confirmed repeatedly throughout `deferred-work.md`'s own audit trail — this is a pre-launch codebase),

**Owner decision (2026-09-16):** the expand/contract "drop only in a later release" convention exists
specifically to protect against an old-release pod still reading a column/table while a new release's
migration drops it mid-rolling-deploy. **That scenario cannot occur here** — there has never been a
running release to be "old." Proceed with the drop now rather than waiting indefinitely on a condition that
can structurally never be satisfied pre-launch.

**Mechanical constraint to satisfy anyway:** `MigrationConventionLintTest`'s `DROP_WITHOUT_PRIOR_RELEASE_PREP`
rule (`src/test/java/com/softropic/skillars/db/MigrationLint.java`) mechanically requires a `-- migration-lint:
drop-prepared-in: V<n>` marker naming a migration version **strictly less than** the `DROP TABLE`'s own
migration version, and independently verifies **no live reference to `pending_blob_deletions` remains
anywhere in `src/main`** at lint time. It has **no opt-out for this specific check** (only
`allow-drop-reference-scan`, which suppresses the reference *search* half, not the version-ordering half —
confirmed by reading `MigrationLint.lintDropOrdering` directly). Citing an unrelated already-existing
migration (e.g. `V140`) as the "release that removed the last reader" would be false — nothing removed
the reader before this story. **Resolve this with two migrations in this same story, not one:**

1. **`V141__remove_pending_blob_deletions_java_surface_marker.sql`** — a header-comment-only migration
   (no functional DDL) whose sole purpose is to exist as a distinct, lower Flyway version than V142,
   representing the release boundary at which the Java code stops reading the table. Document in its
   header exactly why it is a marker, not a schema change (cite this AC).
2. **`V142__drop_pending_blob_deletions.sql`** — the actual guarded drop:
   ```sql
   -- migration-lint: drop-prepared-in: V141
   DROP TABLE IF EXISTS main.pending_blob_deletions;
   ```
   Include the standard header-comment block `MigrationLint`'s `BARE_DROP_NO_HEADER` rule requires,
   explaining the table's history and citing this story. Also drop `main.pending_blob_deletions_id_seq`
   if it does not already go with the table automatically (Postgres drops an `IDENTITY` column's backing
   sequence when the owning table is dropped — confirm this is in fact automatic before adding a separate
   `DROP SEQUENCE`; do not add a redundant statement if the table drop already cascades to it).

**In the same commit, delete:**
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletion.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletionRepository.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionResidualDrainRunner.java`

**Test fixture that will break and needs updating, not deleting wholesale:**
`src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` autowires both
`PendingBlobDeletionResidualDrainRunner` and `PendingBlobDeletionRepository` (lines 62-63) and has one test,
`residualPendingBlobDeletionRows_areReEnqueuedOntoTheGenericOutbox` (line ~417), asserting the residual-drain
behavior this AC deletes. Remove that one test method and its two now-dead `@Autowired` fields; **do not**
touch any other test in that file — `GdprErasureIT` covers real, unrelated GDPR erasure behavior beyond this
one residual-drain case.

**Verified by:**
- `MigrationConventionLintTest` passes with both new migrations in place (confirms the marker/ordering/
  reference-absence checks all pass for real, not just by inspection).
- A fresh-Testcontainers-Postgres boot (any existing `@SpringBootTest`-family IT already does this) confirms
  the application still boots cleanly with `main.pending_blob_deletions` absent — no bean fails to start
  looking for it.
- `GdprErasureIT`'s remaining tests (everything except the one deleted method) still pass unmodified.
- `grep -r "PendingBlobDeletion\|pending_blob_deletions" src/main src/test` (excluding the two new migration
  files and this story/the ledger) returns nothing.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md:1547-1558` (the "Drop
`main.pending_blob_deletions`" bullet), `_bmad-output/implementation-artifacts/deferred-work.md:2259-2266`
(2026-09-15 re-confirmation), `src/main/resources/db/migration/V138__baseline_schema.sql:740-756,2408-2412`]

---

### AC2: `VideoLifecycleScheduler`'s ARCHIVED→DELETED phase permanently re-selects already-purged videos

**Given** `VideoLifecycleService.markPurged(videoId)` (`VideoLifecycleService.java:203-219`) sets
`operationalState=DELETED` on a successful purge but **never touches `accessState`** — confirmed by reading
the method directly; it stays whatever it was, which is `ARCHIVED` for every video reaching this phase,

**And** `VideoRepository.findArchivedExceedingThreshold` (`VideoRepository.java:76-84`), the query
`VideoLifecycleScheduler.runArchivedToDeletedPhase` selects its batch from, filters **only** on
`access_state = 'ARCHIVED' AND archived_at < :threshold`, `ORDER BY archived_at ASC LIMIT :batchSize`, with
no `operational_state` predicate at all,

**Then**: every video this phase successfully purges **remains permanently eligible** for the exact same
query on every subsequent daily run, forever — `archived_at` never changes and `access_state` never leaves
`ARCHIVED`. This is worse than the ledger's own framing ("confirmed inefficiency not data loss"), which
did not account for two compounding effects:
1. **`ORDER BY archived_at ASC` sorts the oldest already-purged videos first.** As purged videos
   accumulate, they permanently occupy the front of the result set.
2. **The query carries a hard `LIMIT :batchSize`** (operator-configurable, default 100, ceiling 10000).
   Once the number of already-purged videos with `archived_at` older than the newest genuinely-due video
   exceeds `batchSize`, **the phase stops making progress on real work entirely** — every batch slot is
   consumed by a video that will fail `markPurged`'s own `operationalState == READY` precondition check
   (`VideoLifecycleService.java:207-211`, throwing `VideoStateConflictException`, caught and WARN-logged by
   `logSkippedVideo`), and genuinely due-for-deletion videos never get reached. This is a slow-motion,
   self-worsening denial of the scheduler's own core function, not a cosmetic log-noise issue.

**Fix:** add an `operational_state = 'READY'` predicate to `findArchivedExceedingThreshold`'s native query —
mirroring exactly the precondition `VideoLifecycleService.markPurged` itself already enforces before doing
anything, so a video the query returns can never fail that check again for this reason. This is a one-line,
minimally-invasive change to the `WHERE` clause; do **not** touch `AccessState` (it has no terminal/`PURGED`
value today — `ACTIVE`/`BLOCKED`/`ARCHIVED` only — and adding one would require auditing every other
`AccessState` switch/conditional in the codebase, which is unnecessary scope for this fix).

**Verified by:**
- A new `VideoRepositoryIT` test: persist a video with `accessState=ARCHIVED`, `operationalState=READY`,
  `archivedAt` past the threshold — confirm it IS returned by `findArchivedExceedingThreshold`. Persist a
  second video identical except `operationalState=DELETED` (simulating an already-purged video) — confirm
  it is NOT returned. (No existing test in `VideoRepositoryIT` covers this query at all — confirmed by
  grep; this is new coverage, not an extension.)
- A new `VideoLifecycleSchedulerTest` (or extension of the existing one) proving that, given a batch
  containing both a genuinely-due video and an already-purged one, the scheduler's `deleteAsset`/`markPurged`
  call sequence only ever touches the genuinely-due one — i.e. the already-purged video is never even
  re-fetched, not merely that its `markPurged` call is caught.
- Full `VideoLifecycleSchedulerTest` suite re-run green (the fix must not change behavior for the
  BLOCKED→ARCHIVED phase, `findBlockedExceedingThreshold`, which this AC does not touch).

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md:2314-2318` (the "code review of
story-115" section's `markPurged()` bullet — corrected and closed by this AC, not merely re-confirmed),
`src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java:203-219`,
`src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:76-84`,
`src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java:141-161`]

---

### AC3: `SessionPackForfeitureScheduler` doesn't re-verify eligibility before finalizing a forfeiture

**Given** `SessionPackForfeitureScheduler.forfeitExpiredPacks()` (`SessionPackForfeitureScheduler.java:35-90`)
loads its batch via `findExpiredNotYetNotified(now)` (`WHERE expiresAt < :now AND expiredNotifiedAt IS NULL
AND remainingSessions > 0`) in one short transaction, then processes **the same in-memory `purchase` object
from that batch load** inside each item's own later transaction — it never re-fetches the row inside the
per-item transaction,

**And** two real, reachable code paths mutate `expiresAt` on a `SessionPackPurchase` after purchase:
`SessionPackPaymentService.extendPack` (`SessionPackPaymentService.java:172`, coach-initiated, `+30 days`)
and `PackSessionService.pausePack` (`PackSessionService.java:223`, `+pauseDurationDays`) — both are
ordinary, user-triggered REST-driven writes, not edge-case-only code,

**Then**: if either of those calls commits in the gap between this scheduler's batch-load transaction and
the specific purchase's own per-item transaction (a window that is small but real — the batch-load
transaction commits, releasing any row lock, before the `for` loop even starts iterating), the scheduler
finalizes forfeiture (`expiredNotifiedAt` stamped, `SessionPackExpiredEvent` published) against **stale,
now-incorrect eligibility** — a pack a coach just legitimately extended, or a parent just paused, gets
forfeited anyway, using the pre-extension `expiresAt`/`remainingSessions` snapshot the batch read captured.
This is the exact "legacy-mirrored select-then-per-row-transaction scheduler shape" race the ledger already
named but left unfixed pending this story.

**Fix:** inside each item's own `transactionTemplate.execute(...)` block, before doing anything else,
re-fetch the purchase fresh by id (`sessionPackPurchaseRepository.findById(purchase.getPurchaseId())`) and
re-check the same three conditions the batch query itself filters on: `expiresAt` still `< now`,
`expiredNotifiedAt` still `null`, `remainingSessions` still `> 0`. If any no longer holds (row no longer
found, extended, paused-so-no-longer-past-due, already finalized by a hypothetical concurrent run, or
sessions already fully consumed), **skip this row silently** (a debug-level log line is enough — this is
the expected, correct outcome of a legitimate concurrent action, not an error) rather than proceeding with
the stale batch-load snapshot. Use the freshly-fetched entity, not the stale `purchase` variable from the
outer loop, for every subsequent read/write in the block.

**Verified by:**
- A new test: seed a pack past its `expiresAt`, then — between the (test-simulated) batch load and the
  per-item transaction — mutate the persisted row's `expiresAt` forward (simulating a committed
  `extendPack`). Assert the scheduler does **not** stamp `expiredNotifiedAt` or publish
  `SessionPackExpiredEvent` for that row.
- A symmetric test for the `remainingSessions` guard (simulate a concurrent full consumption between load
  and per-item processing).
- All existing `SessionPackForfeitureSchedulerTest` cases re-run green, unmodified in their assertions
  (this fix must not change behavior for the already-covered coach-missing / blank-parent-email / one-
  failure-others-continue / no-expired-packs cases — only add a re-check that is a no-op when nothing
  changed underneath).

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md:1170` (D7),
`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java:35-90`,
`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:172`,
`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:223`]

---

### AC4: `RateLimitingService`'s in-process bucket map grows without bound

**Given** `RateLimitingService` (`RateLimitingService.java`) stores every distinct `limitKey:identifier`
combination's `Bucket4j` `Bucket` in a plain `ConcurrentHashMap` with **no eviction of any kind** —
confirmed by reading the whole 45-line class directly; `computeIfAbsent` only ever adds entries, nothing
ever removes one,

**And** `identifier` is a per-client value — an IP address (`RateLimitingAspect.getClientIdentifier()`, used
by every `@RateLimited`-annotated endpoint: registration, password reset, resend flows) or a numeric user id
(`RateLimitingService.tryConsume` direct call sites in `VideoService`, `ParentRegistrationService`,
`PlayerRegistrationService`, `CoachRegistrationService`, `RegistrationOtpResendSupport`,
`ReportGenerationService`) — confirmed **7 distinct call sites** across the codebase (6 direct
`tryConsume` calls + the `@RateLimited` aspect path), each contributing its own growing set of
`limitKey:identifier` keys,

**Then**: this map's entry count grows monotonically for the lifetime of the JVM process, bounded only by
the total number of distinct (client, rate-limited-action) pairs ever seen — a real, unbounded memory leak
in a long-running production process, independent of and in addition to the already-known, separately-
documented "not cluster-safe across multiple instances" limitation (which this AC does **not** attempt to
fix — this deployment runs a single app instance today, confirmed via the docker-compose service stack;
cluster-safety is a distinct, larger architectural change out of scope here).

**Fix:** wrap each stored `Bucket` with a `lastAccess` timestamp, updated on every `tryConsume` call, and
add a `@Scheduled` sweep that evicts entries idle past a configurable TTL. Because every `Bucket4j` bucket
fully refills after its own configured `duration` elapses (confirmed: the longest `duration` in use
anywhere in this codebase is 60 minutes — `AccountManagementFacade`'s `account_registration`/`change_email`
and the three `*_register` keys — see the grep in Dev Notes), evicting an idle bucket and letting it be
recreated fresh on next use is **behaviorally identical** to keeping it around idle, as long as the TTL is
comfortably longer than any in-use `duration`. Register the TTL as a `ConfigService.getBoundedLong(...)`
call (this project's established convention for any numeric runtime setting — see `ConfigBounds.java`),
add the corresponding `BoundedKey` entry to `ConfigBounds.ALL`, and default it generously (e.g. 24 hours) so
normal operation never evicts a bucket mid-window.

**Verified by:**
- A new `RateLimitingServiceTest` case proving an idle-past-TTL bucket is evicted by the sweep (inject a
  clock or expose the sweep method for direct invocation — do not `Thread.sleep` real wall-clock time in
  the test).
- A new test proving a bucket accessed within the TTL is **not** evicted, and that eviction does not reset
  an actively-in-use bucket's remaining token count (only genuinely idle buckets are touched).
- `ConfigBoundsEnumCoverageTest` passes with the new `BoundedKey` registered (this project's mechanical
  drift guard for exactly this kind of addition).
- All 3 existing `RateLimitingServiceTest` cases re-run green, unmodified.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md:1101` (W6),
`src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingService.java`,
`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`]

---

### AC5: `PessimisticLockRetryer`'s idempotency contract is documented, not enforced

**Given** `PessimisticLockRetryer.withBoundedRetry(Supplier<T> lockedOperation)`
(`PessimisticLockRetryer.java:125-166`) retries its `lockedOperation` supplier from a savepoint on every
`PessimisticLockingFailureException`, which means **the supplier can execute more than once for a single
logical call** — correct only if the supplier is read-only/side-effect-free, a contract stated solely in a
javadoc comment (`PessimisticLockRetryer.java:118-124`),

**And** this class now has **28 call sites** across 12 files (`PlaybackService`, `RadarCompositeCalculationService`,
`AvailabilityService`, `BookingService` ×4, `RescheduleService` ×5, `BookingDuplicationService`,
`BookingBatchService` ×4, `BookingPaymentPersistenceService`, `SessionPackPaymentService`, `PackSessionService`
×3, `ReliabilityStrikeService`, `PaymentPendingSweeper`, `CoachProfileService`, `AdminCoachEnforcementService`,
`DrillUploadService` ×2 — confirmed by grep at story-creation time, not trusted from the ledger's stale
"16 call sites" figure) — a growing surface with nothing but manual code review standing between it and a
future call site that closes over a `.save(...)`, an event publish, or an external call inside the retried
lambda, silently re-executing that side effect on every lock-contention retry,

**Then**: today, a violation would only be caught by a human reviewer noticing it, if at all.

**Fix:** add a hand-rolled source-scanning test (mirroring this codebase's own established convention —
`EmailTransportArchitectureTest`, `NoStraySmtpConfigTest`, `NoHardcodedSenderTest` all take this same
approach rather than a general-purpose static-analysis dependency) that:
1. Locates every `.withBoundedRetry(` call site in `src/main/java` by source-text scan.
2. Extracts each call's lambda argument — handling both the single-expression form
   (`() -> repo.findByIdForUpdate(id).orElseThrow(...)`) and the block form (`() -> { ...; return x; }`),
   balancing parens/braces from the opening `(` to its matching close, not a naive regex up to the first
   `)`.
3. Asserts the extracted lambda body contains **no** call matching a denylist of side-effecting patterns:
   `.save(`, `.saveAndFlush(`, `.delete(`, `.deleteAll`, `publishEvent(`, `new .*Event(`, `.send(`,
   `RestTemplate`, `.enqueue(`, `Client.` (HTTP/external-service client calls) — refine the exact list
   against what the current 28 call sites actually contain, so the test starts green, then fails the moment
   a future call site's lambda body matches one of these patterns.
4. Fails loudly (naming the offending file/line) rather than silently skipping an unparseable lambda shape.

Do **not** attempt to change `withBoundedRetry`'s signature (e.g. a marker interface) — this project's own
javadoc on this exact contract already explains why a stronger compile-time guarantee isn't practical here
(Java's type system cannot express "side-effect-free"); a source-scan test is the same class of enforcement
this codebase already relies on elsewhere for an equivalent problem.

**Verified by:**
- The new test class passes against all 28 current call sites (i.e. confirms they are all in fact
  compliant with the documented contract — this is itself new information, not merely a test-writing
  exercise, since the current claim ("all confirmed read-only") has never been mechanically checked).
- A mutation check: temporarily edit one call site's lambda to include a denylisted pattern (e.g. add a
  `.save(...)` inside one `withBoundedRetry` block in a scratch/local run, not committed) and confirm the
  new test fails — then revert. Document this manual mutation-check step in the Dev Agent Record rather
  than leaving it unverified that the test can actually catch a violation.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md:1337` (the
`PessimisticLockRetryer.withBoundedRetry`'s `Supplier<T>` contract bullet),
`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:118-124`]

---

### AC6: Ledger hygiene

**Given** this story closes AC1 (deletes the "Drop `main.pending_blob_deletions`" bullet), AC2 (deletes/
corrects the `markPurged()` bullet in the "code review of story-115" section), AC3 (deletes D7 from the
`skillars-deferred-15` code-review section — confirm which header currently holds it at implementation time,
since line numbers shift), AC4 (deletes W6 from wherever it currently lives), and AC5 (deletes the
`PessimisticLockRetryer` contract bullet),

**Then**: delete each of the five bullets outright per this file's own stated convention (delete on a real
fix, not a decision). If a bullet was the only one under its `## Deferred from:` / `## Last audit:` header,
remove the emptied header too, matching this file's own established precedent (see e.g. the 2026-09-16 top
audit's treatment of the `skillars-6-5` W4 and `skillars-3-10` D2 closures). **Do not** touch any
`[DECIDED ...]` / `[DISMISSED ...]` bullet, including the two named in Provenance & Scoping above.

**Verified by:** a diff of `deferred-work.md` before/after showing exactly the five expected bullet
deletions (plus any now-empty headers), no unrelated changes; a reconstruction check (every surviving line
matches the pre-edit file, in order).

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md` — see each AC's own "Ledger:" line
above for the exact section/bullet to remove]

---

## Tasks/Subtasks

- [ ] **Task 1 — AC1: drop `main.pending_blob_deletions`**
  - [ ] Write `V141__remove_pending_blob_deletions_java_surface_marker.sql` (header-comment-only)
  - [ ] Write `V142__drop_pending_blob_deletions.sql` with the `drop-prepared-in: V141` marker
  - [ ] Delete `PendingBlobDeletion.java`, `PendingBlobDeletionRepository.java`,
        `PendingBlobDeletionResidualDrainRunner.java`
  - [ ] Update `GdprErasureIT.java`: remove the residual-drain test + its two now-dead `@Autowired` fields
  - [ ] Run `MigrationConventionLintTest`; run a fresh-Testcontainers boot IT; confirm zero live references
        remain (grep)

- [ ] **Task 2 — AC2: `findArchivedExceedingThreshold` starvation fix**
  - [ ] Add `operational_state = 'READY'` to the native query's `WHERE` clause
  - [ ] New `VideoRepositoryIT` coverage (returned vs. not-returned cases)
  - [ ] New/extended `VideoLifecycleSchedulerTest` coverage (mixed-batch, already-purged video never
        re-touched)
  - [ ] Full `VideoLifecycleSchedulerTest` suite re-run green

- [ ] **Task 3 — AC3: `SessionPackForfeitureScheduler` re-check race fix**
  - [ ] Re-fetch by id + re-check `expiresAt`/`expiredNotifiedAt`/`remainingSessions` inside the per-item
        transaction; skip silently (debug log) if no longer eligible
  - [ ] New tests: concurrent-extend case, concurrent-consume case
  - [ ] Full `SessionPackForfeitureSchedulerTest` suite re-run green, unmodified assertions

- [ ] **Task 4 — AC4: `RateLimitingService` eviction**
  - [ ] Wrap `Bucket` with a `lastAccess` timestamp; add `@Scheduled` TTL-based eviction sweep
  - [ ] New `ConfigBounds.BoundedKey` for the TTL; wire via `ConfigService.getBoundedLong(...)`
  - [ ] New tests: idle-past-TTL evicted, within-TTL not evicted / not reset
  - [ ] `ConfigBoundsEnumCoverageTest` + existing `RateLimitingServiceTest` re-run green

- [ ] **Task 5 — AC5: `PessimisticLockRetryer` call-site audit test**
  - [ ] New hand-rolled source-scan test class over all `.withBoundedRetry(` call sites
  - [ ] Confirm it passes against all current (re-verify exact count) call sites
  - [ ] Manual mutation check (inject a denylisted pattern locally, confirm RED, revert); document in Dev
        Agent Record

- [ ] **Task 6 — AC6: ledger updates**
  - [ ] Delete all five bullets (plus any emptied headers) from `deferred-work.md`
  - [ ] Reconstruction check

- [ ] **Task 7 — Final validation**
  - [ ] Run every touched module's targeted test suites together; confirm zero regressions
  - [ ] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [ ] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate. Run
  targeted suites only, scoped per-AC as listed above, plus `MigrationConventionLintTest` and
  `ConfigBoundsEnumCoverageTest` (both fast, both mechanically gate two of this story's ACs directly).
- **AC1 risk area — re-verify the `drop-prepared-in` mechanics before writing the migration, don't assume
  this story's read of `MigrationLint.java` is exhaustive.** No live migration in this repo currently uses
  this marker (only `MigrationLint.java`'s own test fixtures do) — there is no real precedent to copy
  verbatim. Read `MigrationLint.lintDropOrdering` and its Javadoc in full before writing either migration
  file. Confirm independently whether `DROP TABLE` cascades to drop an `IDENTITY` column's sequence
  automatically in this Postgres version — do not assume either way.
- **AC1 — check for any other test beyond `GdprErasureIT` that might reference these classes** before
  deleting; the story's own grep at creation time found only that one file, but re-grep at implementation
  time (HEAD will have moved).
- **AC2 — do not touch `findBlockedExceedingThreshold` or the BLOCKED→ARCHIVED phase.** That phase already
  correctly transitions `accessState` (`BLOCKED`→`ARCHIVED` via `archiveForLifecycle`) so it has no
  equivalent bug — confirm this understanding by reading `archiveForLifecycle`
  (`VideoLifecycleService.java:190-197`) before assuming symmetry.
- **AC3 — re-fetch inside the transaction, don't add a pessimistic lock.** This scheduler already runs
  under its own `@SchedulerLock`, so the race this AC closes is against ordinary user-driven writes
  (`extendPack`/`pausePack`), not a second concurrent scheduler run — a plain fresh `findById` read inside
  the per-item transaction is sufficient (Postgres READ COMMITTED gives that transaction the latest
  committed row); do not add `findByIdForUpdate`/`PessimisticLockRetryer` here, that would be new,
  unjustified scope.
- **AC4 — do not add a new caching library dependency** (e.g. Caffeine) for the eviction sweep; this
  project has no existing Caffeine dependency (confirmed via `pom.xml` grep) and a hand-rolled
  timestamp-plus-`@Scheduled`-sweep is a small, dependency-free fix consistent with every other
  scheduled-maintenance job already in this codebase.
- **AC4 — the "not cluster-safe" limitation is explicitly out of scope**, not silently ignored: document it
  in a code comment on `RateLimitingService` so a future reader doesn't mistake the eviction fix for a
  cluster-safety fix. This deployment is single-instance today (confirmed: `docker-compose` service stack
  runs one `app` container) — revisit only if/when the app is horizontally scaled.
- **AC5 — this is a test-only change; `PessimisticLockRetryer.java`'s production code does not change.**
  Do not refactor the class itself as part of this AC.
- **Testing approach:** extend each area's existing, already-established test file/pattern rather than
  inventing a new shape — `VideoRepositoryIT` (AC2, repository-level), `VideoLifecycleSchedulerTest` (AC2,
  scheduler-level), `SessionPackForfeitureSchedulerTest` (AC3, already has a clean Mockito+`TransactionTemplate`
  pattern to extend), `RateLimitingServiceTest` (AC4, currently thin — 3 tests — but the right home for the
  new ones), a new file for AC5 (no existing precedent to extend; name it to match the convention, e.g.
  `PessimisticLockRetryerCallSiteAuditTest`, mirroring `NoStraySmtpConfigTest`'s naming style).
- **Rate limit durations currently in use** (for AC4's TTL-vs-duration reasoning — re-verify at
  implementation time in case a new call site has landed since story creation):
  `password_reset_request`=15min, `password_reset_finish`=10min, `account_registration`=60min,
  `resend_registration`=30min, `change_email`=60min, `registration_email`=5min,
  `registration_alert_email`=5min, `registration_sms`=5min, `registration_alert_sms`=5min,
  `player_register`=60min, `player_resend_verification`=30min, `player_resend_otp`=30min,
  `coach_register`=60min, `coach_resend_verification`=30min, `video.upload.init`=1min,
  `parent_otp_verify`=10min, `player_otp_verify`=10min, `coach_otp_verify`=10min, per-user OTP-resend
  buckets (`RegistrationOtpResendSupport`)=30min, `ReportGenerationService`'s `report_generate`(aspect)=1min
  and `report_generate_user` (direct `tryConsume`)=1min. Longest confirmed duration anywhere: 60min.
- **Project structure:** five independent modules touched (`filestorage`, `video`, `payment`,
  `infrastructure.security`, `infrastructure.persistence`) — no cross-AC coupling; the five ACs can be
  implemented and tested in any order.

### Project Structure Notes

- AC1 touches `platform.filestorage.{repo,service}` and `src/main/resources/db/migration/` — no new module.
- AC2 touches `platform.video.{repo,service}` — no new module, no migration.
- AC3 touches `platform.payment.service` — no new module, no migration.
- AC4 touches `infrastructure.security` and `platform.config.service.ConfigBounds` — no new module, no
  migration, no new dependency.
- AC5 is test-only, in `infrastructure.persistence` — no production code change, no new module.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletion.java`,
  `PendingBlobDeletionRepository.java`,
  `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionResidualDrainRunner.java`
  (AC1 — all three, all short)
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1 — lines 62-63, ~413-430;
  read enough surrounding context to confirm no other test in the file depends on the deleted fields)
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` (AC1 — `lintDropOrdering` and its Javadoc in
  full; this is the only source of truth for the marker mechanics, no real migration file precedent exists)
- `docs/deployment/migration-conventions.md` (AC1 — the expand/contract convention this AC's owner decision
  explicitly reasons about)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java` (AC2 — whole
  file; `markPurged` and `archiveForLifecycle` both, to confirm the asymmetry)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:60-95` (AC2 — both
  threshold queries, side by side)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleScheduler.java` (AC2 — whole
  file; both phases, `logSkippedVideo`)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java` (AC2 —
  existing test pattern to extend)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java` (AC3
  — whole file, 92 lines)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:140-175`
  (AC3 — `extendPack`, confirms the race's real trigger)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:200-230` (AC3 —
  `pausePack`, the second real trigger)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureSchedulerTest.java`
  (AC3 — existing test pattern to extend)
- `src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingService.java` (AC4 — whole
  file, 45 lines)
- `src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingAspect.java` (AC4 — confirms
  the IP-identifier call path)
- `src/test/java/com/softropic/skillars/infrastructure/security/RateLimitingServiceTest.java` (AC4 —
  existing 3-test file to extend)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC4 — `BoundedKey`
  record shape and existing entries to mirror)
- `src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java` (AC5 —
  whole file; the javadoc contract this AC enforces)
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerTest.java` (AC5 —
  confirms this file does NOT already cover call-site auditing, so a new file is needed, not an extension)
- One existing hand-rolled source-scan test for style precedent (AC5) — confirmed present at HEAD:
  `src/test/java/com/softropic/skillars/infrastructure/email/smtp/NoStraySmtpConfigTest.java` and
  `src/test/java/com/softropic/skillars/infrastructure/email/EmailTransportArchitectureTest.java` — either
  demonstrates the balanced-scan-over-source-files convention this AC's new test should follow

---

## Verification Checklist

- [ ] AC1: `V141`/`V142` land, `MigrationConventionLintTest` passes, `PendingBlobDeletion`/`-Repository`/
      `-ResidualDrainRunner` deleted, `GdprErasureIT` updated (one test removed, rest pass), zero remaining
      `pending_blob_deletions`/`PendingBlobDeletion` references in `src/main`/`src/test`
- [ ] AC2: `findArchivedExceedingThreshold` filters `operational_state = 'READY'`; new repository + scheduler
      tests prove an already-purged video is never re-selected; BLOCKED→ARCHIVED phase unchanged
- [ ] AC3: per-item re-fetch + re-check lands inside `forfeitExpiredPacks`'s transaction; concurrent-extend
      and concurrent-consume tests both prove no forfeiture on a no-longer-eligible row; all pre-existing
      `SessionPackForfeitureSchedulerTest` assertions unchanged
- [ ] AC4: `RateLimitingService` buckets carry a `lastAccess` timestamp and an eviction sweep; new
      `ConfigBounds.BoundedKey` registered and covered by `ConfigBoundsEnumCoverageTest`; idle-eviction and
      within-TTL-no-eviction both tested; no cluster-safety claim implied anywhere in code/comments
- [ ] AC5: new call-site audit test passes against all current `withBoundedRetry` call sites; a manual
      mutation check (documented in Dev Agent Record) confirms the test actually catches a violation
- [ ] AC6: all five ledger bullets (plus any emptied headers) deleted; reconstruction check passed
- [ ] No regressions in any touched module's existing test suites

---

## References

- `_bmad-output/implementation-artifacts/deferred-work.md` — see each AC's "Ledger:" line for the exact
  section
- `_bmad-output/implementation-artifacts/skillars-deferred-115-scheduler-transaction-isolation-hardening.md`,
  `skillars-deferred-116-subscription-scheduler-transaction-isolation-hardening.md` — sibling stories whose
  own code reviews surfaced AC1's and AC2's source bullets
- `docs/deployment/migration-conventions.md` — the expand/contract convention AC1's owner decision reasons
  about directly

---

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`. Re-verified every user-named bucket against HEAD
  before drafting — all closed except `pending_blob_deletions`. Two decisions confirmed directly with the
  owner before scoping: (1) proceed with the `pending_blob_deletions` drop now, since its closing condition
  can structurally never be met pre-launch and the expand/contract hazard it guards against cannot occur
  with zero production deploys ever having happened; (2) relax the bundling bar to include real
  architectural hardening items previously left as "recorded, not actionable" (AC3/AC4/AC5), since named
  buckets plus genuine one-off bugs alone were too thin for a full story. AC2 is a genuinely new finding —
  the ledger's own "confirmed inefficiency not data loss" framing of the `markPurged()` bullet was
  investigated further and found to understate a real, worsening starvation bug (`ORDER BY archived_at ASC`
  + hard `LIMIT` means already-purged videos permanently crowd out genuinely-due ones once their count
  exceeds one batch). Considered and deliberately excluded: `RegistrationEmailDurabilityIT`'s global-state
  test-fragility bullet — on inspection, `skillars-deferred-111` AC7 already narrowed nearly every lookup
  in that file to an indexed `findBySendId` call, leaving only one genuinely-unavoidable `findAll()` (paid
  once per test, not per assertion) — not worth a dedicated AC on top of the five above.

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
