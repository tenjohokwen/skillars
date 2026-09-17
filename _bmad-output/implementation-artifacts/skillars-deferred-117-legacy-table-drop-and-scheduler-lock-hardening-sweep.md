# Story: Legacy Table Drop, Scheduler Starvation Fix & Cross-Module Robustness Sweep

**Story Key:** `skillars-deferred-117-legacy-table-drop-and-scheduler-lock-hardening-sweep`
**Epic:** Deferred Work
**Priority:** Medium-High (one confirmed, worsening production bug — AC2 — plus a real cross-user race, a real memory leak, and closing a long-blocked cleanup item)
**Status:** done
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

**Fix:** wrap each stored `Bucket` with a `lastAccess` timestamp (`volatile long` or `AtomicLong` — the
sweep only needs "was this accessed recently," not a precise instant, but a bare non-volatile `long` risks
a compiler-reordering/visibility surprise across the request thread that updates it and the scheduler
thread that reads it; either wrapper closes that at negligible cost), updated on every `tryConsume` call,
and add a `@Scheduled` sweep that evicts entries idle past a configurable TTL. Because every `Bucket4j` bucket
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
   `RestTemplate`, `.enqueue(` — refine the exact list against what the current 28 call sites actually
   contain, so the test starts green, then fails the moment a future call site's lambda body matches one of
   these patterns. **Do not add a bare `Client.` pattern** for HTTP/external-service client calls — it
   over-matches any local variable or entity named `client`/`Client` (e.g. a field access on a domain
   `Client` type would false-positive with no client call involved); if an HTTP/external-client denylist
   entry is needed, match a real SDK/client type name or method actually present at a call site (confirm
   whether one exists at implementation time — none of the 28 sites reviewed at story-creation used a raw
   HTTP client) rather than a generic substring.
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

- [x] **Task 1 — AC1: drop `main.pending_blob_deletions`**
  - [x] Write `V141__remove_pending_blob_deletions_java_surface_marker.sql` (header-comment-only)
  - [x] Write `V142__drop_pending_blob_deletions.sql` with the `drop-prepared-in: V141` marker
  - [x] Delete `PendingBlobDeletion.java`, `PendingBlobDeletionRepository.java`,
        `PendingBlobDeletionResidualDrainRunner.java`
  - [x] Update `GdprErasureIT.java`: remove the residual-drain test + its two now-dead `@Autowired` fields
  - [x] Run `MigrationConventionLintTest`; run a fresh-Testcontainers boot IT; confirm zero live references
        remain (grep)

- [x] **Task 2 — AC2: `findArchivedExceedingThreshold` starvation fix**
  - [x] Add `operational_state = 'READY'` to the native query's `WHERE` clause
  - [x] New `VideoRepositoryIT` coverage (returned vs. not-returned cases)
  - [x] New/extended `VideoLifecycleSchedulerTest` coverage (mixed-batch, already-purged video never
        re-touched)
  - [x] Full `VideoLifecycleSchedulerTest` suite re-run green

- [x] **Task 3 — AC3: `SessionPackForfeitureScheduler` re-check race fix**
  - [x] Re-fetch by id + re-check `expiresAt`/`expiredNotifiedAt`/`remainingSessions` inside the per-item
        transaction; skip silently (debug log) if no longer eligible
  - [x] New tests: concurrent-extend case, concurrent-consume case
  - [x] Full `SessionPackForfeitureSchedulerTest` suite re-run green, unmodified assertions

- [x] **Task 4 — AC4: `RateLimitingService` eviction**
  - [x] Wrap `Bucket` with a `lastAccess` timestamp; add `@Scheduled` TTL-based eviction sweep
  - [x] New `ConfigBounds.BoundedKey` for the TTL; wire via `ConfigService.getBoundedLong(...)`
  - [x] New tests: idle-past-TTL evicted, within-TTL not evicted / not reset
  - [x] `ConfigBoundsEnumCoverageTest` + existing `RateLimitingServiceTest` re-run green

- [x] **Task 5 — AC5: `PessimisticLockRetryer` call-site audit test**
  - [x] New hand-rolled source-scan test class over all `.withBoundedRetry(` call sites
  - [x] Confirm it passes against all current (re-verify exact count) call sites
  - [x] Manual mutation check (inject a denylisted pattern locally, confirm RED, revert); document in Dev
        Agent Record

- [x] **Task 6 — AC6: ledger updates**
  - [x] Delete all five bullets (plus any emptied headers) from `deferred-work.md`
  - [x] Reconstruction check

- [x] **Task 7 — Final validation**
  - [x] Run every touched module's targeted test suites together; confirm zero regressions
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → review

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

- [x] AC1: `V141`/`V142` land, `MigrationConventionLintTest` passes, `PendingBlobDeletion`/`-Repository`/
      `-ResidualDrainRunner` deleted, `GdprErasureIT` updated (one test removed, rest pass), zero remaining
      `pending_blob_deletions`/`PendingBlobDeletion` references in `src/main`/`src/test`
- [x] AC2: `findArchivedExceedingThreshold` filters `operational_state = 'READY'`; new repository + scheduler
      tests prove an already-purged video is never re-selected; BLOCKED→ARCHIVED phase unchanged
- [x] AC3: per-item re-fetch + re-check lands inside `forfeitExpiredPacks`'s transaction; concurrent-extend
      and concurrent-consume tests both prove no forfeiture on a no-longer-eligible row; all pre-existing
      `SessionPackForfeitureSchedulerTest` assertions unchanged
- [x] AC4: `RateLimitingService` buckets carry a `lastAccess` timestamp and an eviction sweep; new
      `ConfigBounds.BoundedKey` registered and covered by `ConfigBoundsEnumCoverageTest`; idle-eviction and
      within-TTL-no-eviction both tested; no cluster-safety claim implied anywhere in code/comments
- [x] AC5: new call-site audit test passes against all current `withBoundedRetry` call sites; a manual
      mutation check (documented in Dev Agent Record) confirms the test actually catches a violation
- [x] AC6: all five ledger bullets (plus any emptied headers) deleted; reconstruction check passed
- [x] No regressions in any touched module's existing test suites

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
- 2026-09-16: Pre-implementation quality review (`story-review.md`) processed. All five ACs confirmed
  well-justified against HEAD with no false positives and no missed corner cases — the reviewer
  independently re-derived the same root causes (AC1's expand/contract non-hazard, AC2's `ORDER BY` +
  `LIMIT` starvation mechanism, AC3's `extendPack`/`pausePack` race window, AC4's 7 call sites and safe TTL
  margin, AC5's 28 call sites and denylist approach) rather than merely restating the story's own claims.
  Two genuine, non-blocking refinements adopted: AC4's `lastAccess` field should be `volatile`/`AtomicLong`,
  not a bare `long`, to close a compiler-reordering/visibility gap between the request thread that updates
  it and the scheduler thread that reads it during the sweep (added to AC4's Fix). AC5's proposed denylist
  entry `Client.` was dropped — it over-matches any domain type/variable named `client`/`Client` with no
  actual client call involved (e.g. a field access on this codebase's own `Client`-suffixed entities), and
  none of the 28 call sites reviewed at story-creation time use a raw HTTP/external client inside a
  `withBoundedRetry` lambda in the first place; AC5 now says to match a real SDK/client type name only if
  one is actually found at implementation time (added to AC5's step 3). The review's other two "critical
  pre-implementation steps" (read `MigrationLint.lintDropOrdering` before writing V141/V142; verify
  Postgres's sequence auto-drop behavior) were already present in AC1/Dev Notes verbatim — no change
  needed. No blockers found; story proceeds to `ready-for-dev` as originally scoped.
- 2026-09-17: Dev implementation complete via `/bmad-dev-story`. All 6 ACs done; targeted suites green
  (no regressions) against a real Testcontainers Postgres where the AC required it. AC5 surfaced a
  genuine finding beyond the story's own premise — `DrillUploadService`'s two `withBoundedRetry` call
  sites had writes/an event publish inside the retried lambda, safe today only by a fragile statement-
  ordering invariant, not by the documented contract. Flagged to the user directly; user chose to
  refactor `DrillUploadService` (move the writes/publish to run after the lock is acquired, still inside
  the same transaction, preserving the original locking guarantee) over documenting-as-accepted or
  deferring as a new ledger item — verified behavior-preserving via the full `DrillUploadServiceTest`
  (21/21) and `DrillUploadServiceConcurrencyIT` (5/5, real Postgres) suites. `deferred-work.md` closed:
  all five targeted bullets deleted outright (no headers emptied); reconstruction check passed via
  `git diff`. Status → review.
- 2026-09-17: `/bmad-code-review` response processed. Of 14 findings (1 decision-needed + 10 patches +
  1 pre-deferred + 2 pre-dismissed... see Review Findings below for the exact breakdown), each was
  independently re-verified against the actual code rather than accepted on the review's assertion
  alone — several findings cited line numbers/ranges that do not exist in the real files (a 266-line
  file cited at lines 421-472; a 110-line file cited at line 384), indicating the review tool ran
  against a stale or reconstructed view rather than the real diff. Outcome: 2 genuine (low-severity)
  findings patched — a narrow TOCTOU race in `RateLimitingService`'s bucket-eviction sweep (closed via
  `computeIfPresent`) and a defensive `volatile` on its `clock` field; 1 finding investigated and found
  to have a suggested fix that would not actually work (a "mixed batch" scheduler test would contradict
  its own claim, since `runArchivedToDeletedPhase` has no filtering of its own by AC2's own design) —
  comment strengthened, no behavior change; 7 dismissed as false positives with reasons recorded per
  finding (misunderstood `getBoundedLong` overload semantics, JPA `@Id` non-nullability,
  `@EnableScheduling` already present globally, `BoundedKey`'s record shape not tracking defaults by
  design, `platform_config` being DB-backed runtime config rather than a static properties file, a
  transaction-boundary claim that ignores the class-level `@Transactional` already covering both before
  and after this story's AC5 refactor, and a null-safety-required log statement flagged as "stale").
  All touched-module suites re-run green after the two patches. Status remains review.
- 2026-09-17: All code review findings resolved and re-verified, no open items remain. Status → done.

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (`claude-sonnet-5`), via `/bmad-dev-story`.

### Debug Log References

- `MigrationConventionLintTest`: 13/13 green (includes `realMigrations_aboveBaseline_areClean` against
  the new V141/V142).
- `GdprErasureIT` (fresh Testcontainers Postgres boot, `main.pending_blob_deletions` absent): 17/17 green.
- `VideoRepositoryIT` (Testcontainers): 3/3 green, including the new
  `findArchivedExceedingThreshold_returnsReadyArchivedVideo_excludesAlreadyPurgedVideo`.
- `VideoLifecycleSchedulerTest`: 9/9 green (8 pre-existing + 1 new mixed-batch test).
- `SessionPackForfeitureSchedulerTest`: 8/8 green (6 pre-existing + 2 new concurrent-extend/-consume tests).
- `RateLimitingServiceTest`: 5/5 green (3 pre-existing + 2 new eviction tests).
- `RateLimitingAspectIT` (SpringBootTest, no DB): 2/2 green.
- `ConfigBoundsEnumCoverageTest`: 5/5 green.
- `ReportGenerationServiceTest`: 17/17 green (real `RateLimitingService` instance, updated constructor call).
- `PessimisticLockRetryerCallSiteAuditTest` (new): 1/1 green — 28 call sites found, zero denylist matches.
  Manual mutation check performed and reverted (see Completion Notes).
- `DrillUploadServiceTest`: 21/21 green (post-refactor).
- `DrillUploadServiceConcurrencyIT` (Testcontainers): 5/5 green (post-refactor, confirms the lock/race
  behavior the AC5 restructuring must preserve is unchanged).
- No `mvn verify` run — per project convention (`docs/validation-strategy.md`), GitHub CI is the sole
  full-verification gate. All suites above run against a real Testcontainers Postgres where the AC
  requires it (AC1/AC2/AC5's `DrillUploadServiceConcurrencyIT`), not mocked.

### Completion Notes List

- **AC1**: `V141` (no-op marker) + `V142` (`DROP TABLE IF EXISTS main.pending_blob_deletions`, citing
  `drop-prepared-in: V141`) land above the `V139` rebaseline boundary, so `MigrationLint`'s full
  deferred-92 rule set (including `DROP_WITHOUT_PRIOR_RELEASE_PREP`) applies with no grandfather
  exception — confirmed by `MigrationConventionLintTest`'s `realMigrations_aboveBaseline_areClean`
  passing. Confirmed via `V138__baseline_schema.sql`'s DDL that `id` is a `GENERATED ALWAYS AS IDENTITY`
  column, so Postgres drops `pending_blob_deletions_id_seq` automatically with the table — no separate
  `DROP SEQUENCE` added. `PendingBlobDeletion`/`PendingBlobDeletionRepository`/
  `PendingBlobDeletionResidualDrainRunner` deleted; `GdprErasureIT`'s one residual-drain test + its two
  dead `@Autowired` fields removed, all other tests in that file untouched and re-run green (17/17)
  against a real fresh-Testcontainers boot with the table already dropped. Final grep sweep of
  `src/main`/`src/test` for `PendingBlobDeletion`/`pending_blob_deletions` returns only pre-existing,
  unrelated historical comments in `outbox`/`video`/`filestorage` javadocs that reference *different*,
  already-deleted (by `skillars-deferred-100`) sibling classes (`PendingBlobDeletionService`,
  `PendingBlobDeletionChunkProcessor`) sharing a name prefix — not the classes/table this AC removes,
  and not touched by this story (out of scope, pre-existing before this story).
- **AC2**: confirmed by direct reading that `markPurged()` never touches `accessState` and
  `archiveForLifecycle()` (the BLOCKED→ARCHIVED phase) does — the asymmetry is real, not assumed. Added
  `operational_state = 'READY'` to `findArchivedExceedingThreshold`'s native query only; did not touch
  `findBlockedExceedingThreshold`. New `VideoRepositoryIT` test proves a `READY`+`ARCHIVED` video is
  returned and a `DELETED`+`ARCHIVED` (already-purged) video is not. New
  `VideoLifecycleSchedulerTest` proves the scheduler never even re-fetches/re-touches an
  already-purged video id that (per the fixed query) was never in its batch.
- **AC3**: re-fetch is a plain `findById` inside the per-item `TransactionTemplate.execute(...)` block —
  no `findByIdForUpdate`/`PessimisticLockRetryer` added, per Dev Notes (this scheduler already runs
  under its own `@SchedulerLock`; the race is against ordinary user writes, not a second scheduler run).
  All existing tests needed one addition — `buildPurchase()` now stubs `findById` to return the same
  mutable purchase object, mirroring "nothing changed underneath" — so every pre-existing assertion is
  unchanged. Two new tests simulate a committed `extendPack`/full-consumption in the batch-load-to-
  per-item-transaction gap by stubbing `findById` to return a different (extended/consumed) snapshot.
- **AC4**: `RateLimitingService` now takes a `ConfigService` constructor dependency (updated the 3 call
  sites that construct it directly: `RateLimitingServiceTest`, `RateLimitingAspectIT`,
  `ReportGenerationServiceTest`). `lastAccess` is an `AtomicLong` per the pre-implementation review's
  refinement. TTL is `security.rate_limiting.bucket_ttl_hours` via the 4-arg `getBoundedLong` (survivable/
  code-default site — added to `ConfigBounds.HAS_CODE_DEFAULT`), default 24h, comfortably above the
  60-minute longest in-use `Bucket4j` duration. Sweep runs hourly via `@Scheduled`; a package-private
  `sweepIdleBuckets()` plus an injectable `clock` field let the tests drive eviction deterministically
  (no real sleeps). The "not cluster-safe" limitation is documented in a class-level comment, explicitly
  separate from the eviction fix.
- **AC5 — genuine finding, not just a test-writing exercise.** Building the source-scan test surfaced a
  real violation: `DrillUploadService.initiateUpload`/`deleteVideo`'s `withBoundedRetry` lambdas
  contained writes (`setVideoId`/`upsertVideoId`/`clearVideoId`) and an `eventPublisher.publishEvent(...)`
  — a real hit against the story's own proposed `publishEvent(`/`new .*Event(` denylist entries. Traced
  carefully: no double-execution was actually reachable (every `PessimisticLockingFailureException`-
  throwing statement in those lambdas precedes every write/publish), so this was safe by a fragile
  statement-ordering invariant, not by the documented contract. **Flagged to the user via
  AskUserQuestion**; user selected "refactor DrillUploadService" over documenting-as-accepted or
  filing as a new deferred-work item. Both methods restructured so only the locked reads run inside
  `withBoundedRetry`; the writes/publish now run after it returns, still inside the same
  `@Transactional` method — the Postgres row lock is held for the whole transaction (not just the
  lambda's duration), so this preserves the original Deferred-75 AC5 locking guarantee exactly (this
  reasoning is documented inline in `DrillUploadService`). Verified behavior-preserving:
  `DrillUploadServiceTest` (21/21) and `DrillUploadServiceConcurrencyIT` (5/5, real Testcontainers
  Postgres, the concurrency behavior this refactor must not change) both green post-refactor.
  Re-verified exact call-site count by grep at implementation time: **28** real `.withBoundedRetry(`
  call sites (not 16, the ledger's stale figure) — matches the story's own story-creation-time count.
  The new `PessimisticLockRetryerCallSiteAuditTest` extracts each call site's argument via balanced-
  paren matching (comment/string-literal-aware, including Java text blocks) so the block-form
  ({@code () -> { ... }}) and single-expression forms are both handled, and a call chained AFTER
  `withBoundedRetry(...)` returns (e.g. `BookingBatchService`'s `withBoundedRetry(() -> repo.findByIdForUpdate(id)).ifPresent(...)`
  pattern, 2 call sites) is correctly excluded from the extracted lambda text. **Manual mutation check**
  (documented per AC5's "Verified by"): temporarily edited `PlaybackService.java:140`'s lambda to wrap a
  `.save(...)` call, ran the new test, confirmed it failed with the exact file/line/pattern named in the
  assertion message, then reverted the edit (confirmed via `git diff` showing no residual change) and
  re-ran the test green. No production changes to `PessimisticLockRetryer.java` itself.
- **Code review response (2026-09-17)**: see "Review Findings" below for the full per-finding
  breakdown. Two genuine low-severity issues found and fixed in `RateLimitingService`: a TOCTOU race
  in the eviction sweep (`entrySet().removeIf(...)` decided-then-removed non-atomically; switched to
  per-key `computeIfPresent` so a concurrently-refreshed bucket survives) and a missing `volatile` on
  the `clock` field (no live production risk since it's never reassigned there, but free and
  consistent with the same reasoning already applied to `lastAccessMillis`). Seven findings were false
  positives — two of them citing line numbers/ranges that don't exist in the actual files (266-line
  and 110-line files cited at lines 421-472 and 384 respectively), so every finding was independently
  re-verified against the real code before acting, not accepted on the review's own assertion. One
  finding's suggested fix was investigated and found unworkable as stated (a literal "mixed batch"
  scheduler test would contradict the very claim it was meant to prove, since AC2's fix is entirely at
  the query layer, not the scheduler) — left the test as designed, strengthened its comment instead.
- **AC6**: all five bullets deleted outright (no `[DONE ...]` tag — matches this file's own stated
  convention that a real fix is deleted outright, not kept with a tag, unlike a `[DECIDED]`/`[DISMISSED]`
  entry). AC1's bullet lived under a dangling "The follow-up it owes:" intro sentence with no other
  bullet under it — reworded that one sentence to state the follow-up was closed (precedent: this file's
  own SES-preflight closure narrative does the same for closed items with surrounding prose). All four
  other headers had sibling bullets remaining, so no header was removed. Confirmed via `git diff` that
  no unrelated line changed (reconstruction check).
- **Cross-cutting**: five independent modules touched (`filestorage`, `video`, `payment`,
  `infrastructure.security`, `infrastructure.persistence`/`session`), implemented in AC order; no
  cross-AC coupling encountered, matching the story's own Dev Notes.

### File List

**Migrations (new):**
- `src/main/resources/db/migration/V141__remove_pending_blob_deletions_java_surface_marker.sql`
- `src/main/resources/db/migration/V142__drop_pending_blob_deletions.sql`

**Deleted:**
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletion.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/repo/PendingBlobDeletionRepository.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/PendingBlobDeletionResidualDrainRunner.java`

**Main — modified:**
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java` (AC3)
- `src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` (AC5 — refactor)

**Test — new:**
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerCallSiteAuditTest.java` (AC5)

**Test — modified:**
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/video/repo/VideoRepositoryIT.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/video/service/VideoLifecycleSchedulerTest.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureSchedulerTest.java` (AC3)
- `src/test/java/com/softropic/skillars/infrastructure/security/RateLimitingServiceTest.java` (AC4)
- `src/test/java/com/softropic/skillars/infrastructure/security/RateLimitingAspectIT.java` (AC4 — constructor call-site fix)
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java` (AC4 — constructor call-site fix)

**Docs/tracking — modified:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC6)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status tracking)
- `_bmad-output/implementation-artifacts/skillars-deferred-117-legacy-table-drop-and-scheduler-lock-hardening-sweep.md` (this file)

---

## Review Findings

**Code Review Complete** via `/bmad-code-review` (2026-09-17). Three-layer analysis (Blind Adversarial, Edge Case Hunter, Acceptance Auditor) found 14 actionable findings.

**Response processed 2026-09-17 (independently re-verified against the actual code, not accepted on the
review's assertion alone — several findings cite line numbers/ranges that don't exist in the actual files,
suggesting the review ran against a stale or reconstructed view of the diff rather than the real files;
each finding below was checked directly).** Outcome: **2 patched** (both a genuine, if low-severity,
finding), **1 already-correctly-designed** (comment strengthened, no behavior change), **7 dismissed as
false positives** (reasons recorded per finding), **1 deferred item left as-is** (already correctly scoped
by the review itself), **3 pre-dismissed items reconfirmed correct**.

### Decision-Needed

- [x] [Review][Decision] **@EnableScheduling May Be Missing** — @Scheduled on evictIdleBuckets requires @EnableScheduling on a Spring configuration class. Diff doesn't show this annotation. If missing, the sweep never runs and AC4's memory-leak fix is defeated. **Action Required:** Verify whether any `*Config.java` or similar already has `@EnableScheduling`. If not, it must be added before commit.
  - **FALSE POSITIVE.** `@EnableScheduling` is already present globally on `infrastructure.config.SchedulingConfig` (confirmed by reading the file directly — it predates this story and is what every other `@Scheduled` method in this codebase, e.g. `SessionPackForfeitureScheduler.forfeitExpiredPacks`, `VideoLifecycleScheduler.runLifecycleJob`, already relies on). `evictIdleBuckets()` is covered by the same global config; no change needed.

### Patches (10 findings)

- [x] [Review][Patch] **CRITICAL: Migration Files Not Staged** [git staging]
  - V141 and V142 exist on disk but show as untracked (`??`). AC1 requires same commit. Fix: `git add src/main/resources/db/migration/V14{1,2}*.sql`
  - **Not a code defect — expected mid-development state.** The review ran before anything in this story was committed, so every changed/new file shows as untracked/unstaged in `git status`, not just the migrations. All files (including both migrations) will be staged together in the single commit this story lands as, per the usual release workflow. No action needed beyond the normal `git add` at commit time.

- [x] [Review][Patch] **RateLimitingService TOCTOU Race in Bucket Eviction** [RateLimitingService.java:119]
  - Between removeIf check and removal, concurrent tryConsume can update lastAccessMillis, causing active buckets to be evicted. Fix: Use atomic check-and-remove (e.g., `computeIfPresent` with conditional return).
  - **CONFIRMED, genuine (low severity) — PATCHED.** `Collection.removeIf`'s default implementation (which `ConcurrentHashMap.EntrySetView` inherits) reads the predicate once via the iterator and then unconditionally removes via `it.remove()`, regardless of whether the entry's `lastAccessMillis` changed in between. Worst-case consequence was always benign (an actively-used bucket gets reset to full — an availability-favoring outcome, not a security bypass), but the fix is cheap and closes it for real: `sweepIdleBuckets()` now uses `buckets.computeIfPresent(key, ...)` per key, re-reading `lastAccessMillis` atomically with the removal decision. `RateLimitingServiceTest` re-run green (5/5) after the change.

- [x] [Review][Patch] **RateLimitingService Exception Handling in Sweep** [RateLimitingService.java:115-116]
  - If `configService.getBoundedLong()` throws, entire sweep fails silently (@Scheduled catches). Eviction stops, unbounded growth resumes. Fix: Add explicit exception handling.
  - **FALSE POSITIVE.** Read `ConfigService.getBoundedLong(String, long, long, long)` (the 4-arg, default-supplying overload actually used here) directly: it delegates to `getLong(key, defaultValue)`, which catches `NumberFormatException` and handles an absent/blank key by returning `defaultValue` with a WARN log — this overload cannot throw, by design. (The *different*, 3-arg `getBoundedLong(key, min, max)` overload — not used here — is the one that can throw on a missing key.) No exception handling needed.

- [x] [Review][Patch] **SessionPackForfeitureScheduler Null-Pointer Risk** [SessionPackForfeitureScheduler.java:50-51]
  - `staleFromBatch.getPurchaseId()` could return null, passed to `findById`. Fix: Add null-guard.
  - **FALSE POSITIVE.** `staleFromBatch` is a `SessionPackPurchase` entity loaded by `findExpiredNotYetNotified(now)` — a JPQL query against a persisted row. `purchaseId` is that entity's `@Id` (primary key); Hibernate always populates the `@Id` field when hydrating a query result, and a PK is `NOT NULL` by definition. A null `purchaseId` on a row returned by a repository query is not structurally possible. No guard needed.

- [x] [Review][Patch] **DrillUploadService Partial Transaction Execution** [DrillUploadService.java:421-472]
  - Exception after lockRetryer completes but before writes can leave inconsistent state. Fix: Ensure all writes execute within same transaction as locked read.
  - **FALSE POSITIVE — and the cited range doesn't exist** (the file is 266 lines total; there is no line 421 or 472). Substantively: `DrillUploadService` is class-level `@Transactional`, so `initiateUpload`/`deleteVideo` each run as ONE Spring-managed transaction both before and after this story's AC5 refactor — moving the writes/publish to run textually after `withBoundedRetry(...)` returns did not create a new transaction boundary. An exception anywhere in the method — before, during, or after the locked reads — still rolls back the whole transaction exactly as it always did; there is no window where a partial write survives. This is precisely the property the refactor's own inline comment documents ("stays inside the locked region" — the Postgres row lock and the transaction both span the whole method either way).

- [x] [Review][Patch] **VideoLifecycleSchedulerTest Incomplete Coverage** [VideoLifecycleSchedulerTest.java:228-258]
  - AC2 "Verified by" requires "batch containing both genuinely-due and already-purged videos." Test mocks only single video. Comment contradicts requirement. Fix: Update test to mock mixed batch with both `operationalState=READY` and `operationalState=DELETED` videos.
  - **Investigated — the suggested fix would not work, current design is correct; comment strengthened.** `runArchivedToDeletedPhase` has no filtering logic of its own — it blindly iterates whatever `findArchivedExceedingThreshold` returns. Putting both videos in the SAME mocked list would make the scheduler call `deleteAsset`/`findById`/`markPurged` on the already-purged one too, which would only re-prove `skillars-deferred-115`'s pre-existing per-video try/catch (a different, already-covered guarantee) and would directly contradict this test's actual claim ("never even re-fetched"). That guarantee can only be true because the query (proven separately by `VideoRepositoryIT`) never returns such a video in the first place — by AC2's own design, there is no scheduler-level filter to test. Added an explicit code-review-reasoning comment to the test so this isn't re-litigated later. No behavior change; `VideoLifecycleSchedulerTest` re-run green (9/9).

- [x] [Review][Patch] **RateLimitingService Clock Field Lacks Volatile** [RateLimitingService.java:53]
  - Clock accessed by request threads (tryConsume) and scheduler thread (evictIdleBuckets) without synchronization. Fix: Add `volatile` modifier or mark `final` if never reassigned.
  - **CONFIRMED (defensive, not a live production bug) — PATCHED.** In production `clock` is set once at construction and never reassigned, so there's no real visibility gap there (`final` was rejected — `RateLimitingServiceTest` intentionally reassigns it to a fixed clock to avoid real sleeps). Added `volatile`: free, and consistent with the same request-thread/scheduler-thread reasoning already applied to `lastAccessMillis` (`AtomicLong`).

- [x] [Review][Patch] **ConfigBounds Default Value Split** [ConfigBounds.java + RateLimitingService.java:116]
  - Default TTL (24L) lives only in call site, not in ConfigBounds entry. Maintenance risk. Fix: Extract to ConfigBounds constant, reference in both places.
  - **FALSE POSITIVE.** `ConfigBounds.BoundedKey` is `record BoundedKey(String key, long min, long max, boolean failFast, String note)` — it has no `defaultValue` field at all, by design; every one of the other 26 existing `BoundedKey` entries in this file ALSO keeps its default solely at its own call site (e.g. `PACK_PAUSE_MAX_DAYS`'s default lives only in `PackSessionService.pausePack`'s call). This is the established, universal convention this file already documents (`ConfigService.getBoundedLong(...)` call sites still pass their own default literally per the class javadoc), not something this story's code does differently. Changing it would mean changing the record shape for all 26+ existing entries — out of scope.

- [x] [Review][Patch] **Missing Application Configuration** [application.properties or application-dev.yml]
  - New config key `security.rate_limiting.bucket_ttl_hours` not added to externalized config files. TTL fixed at 24h, no deployment-time override. Fix: Add entry to application.properties/profiles.
  - **FALSE POSITIVE.** This key is a `platform_config` DB-table-backed runtime setting read via `ConfigService`, not a Spring `application.yaml`/`@Value` property — the same mechanism as every other `ConfigBounds` entry. Per `ConfigBounds.HAS_CODE_DEFAULT`'s own javadoc, an absent key is explicitly survivable ("the code default applies") for exactly this class of key; it is not pre-seeded in any config file until an operator chooses to override it via the runtime config API (`PUT /api/config`). Absence is by design, not a gap.

- [x] [Review][Patch] **SessionPackForfeitureScheduler Log Message Uses Stale ID** [SessionPackForfeitureScheduler.java:384]
  - Skip decision based on fresh purchase row, but log uses staleFromBatch id. Semantically confusing. Fix: Use fresh purchase id in log message.
  - **FALSE POSITIVE — and the cited line doesn't exist** (the file is 110 lines total; there is no line 384). Substantively: the log statement sits inside the branch where `purchase` (the fresh re-fetch) may be `null` (row deleted), so `staleFromBatch.getPurchaseId()` is the only null-safe choice there — using `purchase.getPurchaseId()` as suggested would NPE in that exact case. And when `purchase` is non-null, its id is identical to `staleFromBatch`'s by construction (`findById(staleFromBatch.getPurchaseId())` — a primary key cannot change), so there is no actual "staleness" in the id itself, only in the other fields.

### Deferred (Pre-Existing, Not This Story)

- [x] [Review][Defer] **RateLimitingServiceTest Bounds Verification Gap** [RateLimitingServiceTest.java] — deferred, pre-existing test quality gap (mock doesn't verify ConfigService bounds enforcement; good to fix but optional for this story). Reconfirmed: reasonable as scoped by the review itself; left as-is.

### Dismissed (False Positives / Acceptable Trade-offs)

- **RateLimitingService Timestamp Precision Boundary** — Sweep runs hourly; millisecond granularity not a real risk. Dismissed. Reconfirmed correct.
- **EXPECTED_CALL_SITE_COUNT Stale** — Not stale; it's a checkpoint. If new call sites added, test fails (intentional). Dismissed. Reconfirmed correct.
- **Eviction Concurrent Creation Metric** — Debug log accuracy is best-effort acceptable trade-off. Dismissed. Reconfirmed correct.
