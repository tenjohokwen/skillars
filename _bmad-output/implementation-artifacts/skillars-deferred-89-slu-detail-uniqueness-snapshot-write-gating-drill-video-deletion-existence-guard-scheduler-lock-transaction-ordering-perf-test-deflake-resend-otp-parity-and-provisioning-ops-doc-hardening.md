# Story skillars-deferred-89: SLU detail-row uniqueness + snapshot-write gating, drill-video physical-deletion existence guard, `@SchedulerLock`/`@Transactional` advisor ordering, `PlaybackServiceIT` perf-assertion de-flake, `/resend-otp` coach+player parity, and provisioning / CI ops-doc hardening

Status: ready-for-dev

## Story

As the platform operator and as an engineer maintaining the development (SLU), video, booking-scheduler, registration and deployment paths,
I want (1) `development.player_skill_stats` to carry a DB-level unique key on `(session_id, skill_code)` so two concurrent `BookingCompletedEvent` deliveries for the same session can no longer each pass the in-code `findBySessionId(...).isEmpty()` / `existsBySessionId(...)` guard and both `saveAll` a full set of detail rows, permanently diverging `SUM(player_skill_stats.slu_value)` from the once-applied `player_slu_weekly_snapshot.total_slu`; (2) `SluPersistenceDispatcher.dispatchSluPersistence` to run the weekly-snapshot write **only** when the SLU detail save actually succeeded, so a session whose `saveSluWithRetry` exhausted its retries (its `@Recover` returns `void` and the chain falls through) can no longer add deltas to `player_slu_weekly_snapshot` / its V119 marker rows for a session that has **no** rows in `player_skill_stats`; (3) `DrillUploadService.initiateUpload` (and the mirrored `deleteVideo` path) to confirm the `videos` row itself still exists — using the `findByIdForUpdate` result it already holds — before publishing `VideoPhysicalDeletionEvent`, so a already-deleted video is not re-queued for physical deletion; (4) the three schedulers that stack `@SchedulerLock` and `@Transactional` on one method (`BookingExpiryScheduler`, `BookingReminderScheduler`, `BandwidthResetService`) to have a **defined** advisor nesting order so the ShedLock distributed lock is always released *after* the DB transaction commits, never before; (5) `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` to stop gating build correctness on a hard millisecond wall-clock latency bound in CI, where JIT / GC / Testcontainers jitter make the threshold structurally flaky even after the percentile-index and warmup fixes `skillars-deferred-23` AC1 already applied; (6) the `booking.session_packs_purchased` partial-index predicate (already converted from a `NOT IN` blacklist to a `status = 'ACTIVE'` whitelist by `V83`) to gain an automated guard that keeps it — and any sibling status-scoped partial index — aligned with the `chk_spp_status` CHECK constraint that is the de-facto status enum, so a future added status can never silently change which rows an "active" partial index covers; (7) `/resend-otp` endpoint parity — `CoachRegistrationResource` / `PlayerRegistrationResource` and their services gain the `resendPhoneOtp` path that `ParentRegistrationResource` already has (`skillars-deferred-43`), so an `EMAIL_VERIFIED` coach or player whose single OTP SMS never arrived has a way to re-request one without re-registering; and (8) the deployment docs and `provision.sh` hardened for three residual items — the SSH-port-22-open-to-all window between `provision.sh` and `apply-firewall.sh` documented (and optionally scoped by an `SSH_ALLOWLIST_IP` the operator already knows), the pre-Volume-migration no-op on an already-shadowed host surfaced loudly instead of silently, the `build-and-push` "cancelled while pending → no tags at all" gap documented as a known rollback-target hole, and `chown_if_needed`'s `find -maxdepth 2` scope/early-exit corrected to match its comment,
so that the SLU dashboard fast-path and the detail queries can never permanently disagree, an exhausted SLU save never leaves a phantom weekly total, a deleted video is not re-deleted, a scheduler cannot release its cluster lock before its writes are durable, a latency spike in CI no longer red-builds a correct change, a new pack status cannot silently widen an index, a coach/player with a lost OTP is not stuck, and an operator provisioning a fresh node knows exactly what is exposed and for how long.

## Story creation context

Per the standing `deferred-work.md` re-mining priority order (`[[project_skillars_release_workflow]]`), the project owner selected a cross-cutting bundle of nine concrete, previously-unpicked-up ledger items spanning **Development/SLU**, **Video/Drills**, **Booking schedulers**, **Video test hygiene**, **DB-migration convention**, **Auth/Registration** and **Deploy/Infra**. All nine were verified against source at `4f5b5cb` (post-`skillars-deferred-88` merge) during this creation pass; findings below.

- **`## Deferred from: code review of skillars-deferred-86-…` (2026-08-31)** — two of the four bullets, both flagged independently by all three review layers and both explicitly scoped by the project owner as follow-up stories (not review patches) because they need a DB change / a return-value contract change:
  - **`development.player_skill_stats` has no unique constraint on `(session_id, skill_code)`.** `SluCalculationService.onBookingCompleted` guards on `sluRepository.findBySessionId(session.getId()).isEmpty()` (`:80`); `SluPersistenceRetrier.saveSluWithRetry` additionally guards on `existsBySessionId(...)` (`skillars-deferred-86` AC2). Neither is a lock. Two `BookingCompletedEvent` deliveries for one session can both pass the line-80 check before either commits, both `dispatchSluPersistence` tasks run on `sluRetryExecutor`, and both `saveSluWithRetry` calls see `existsBySessionId(...) == false` in their own tx and call `saveAll` → two full sets of detail rows. V119's marker table serialises the *snapshot* writers, so `player_slu_weekly_snapshot.total_slu` is applied once while the detail rows double — `SUM(player_skill_stats.slu_value)` and the snapshot total diverge permanently. → **AC1** (V122 partial unique index + concurrent-delivery conflict handling).
  - **`SluPersistenceDispatcher.dispatchSluPersistence` runs `writeAllWithRetry` even when `saveSluWithRetry` exhausted its retries.** `SluPersistenceRetrier`'s `@Recover` returns `void`, so the chained method falls straight through to `snapshotPersistenceRetrier.writeAllWithRetry(...)`: `player_slu_weekly_snapshot` (and its V119 marker rows) gain deltas for a session with **no** rows in `player_skill_stats`; the dashboard fast-path then shows SLU the detail queries cannot reproduce. Pre-existing sequencing carried unchanged when `skillars-deferred-86` AC3 extracted the two calls into the dispatcher. → **AC2** (gate the snapshot write on the SLU save having actually landed).
- **`## Deferred from: code review of skillars-deferred-22-…` (2026-08-14)** — the first bullet: **`initiateUpload`'s orphaned-reservation release doesn't confirm the video row itself still exists before publishing `VideoPhysicalDeletionEvent`.** `initiateUpload` (`DrillUploadService.java:111`) already takes `Optional<Video> lockedVideo = videoRepository.findByIdForUpdate(existingVideoId)` under the Drill→Video lock order (`skillars-deferred-81` AC3), but the subsequent publish at `:128-129` gates only on `!drillVideoRefRepository.existsByVideoId(existingVideoId)` — not on `lockedVideo.isPresent()`. `deleteVideo` (`:168`, `:172-173`) has the identical shape. Pre-existing pattern; the TOCTOU half of this cluster was closed by `skillars-deferred-75`/`-81`, but the "publish deletion for a row that may already be gone" half is still open. → **AC3**.
- **`## Deferred from: code review of skillars-deferred-4 (2026-07-02)`**, D3 — **`@SchedulerLock` and `@Transactional` stacked on one method with no explicit advisor order.** `BookingExpiryScheduler.expireStaleRequests` (`:41` `@SchedulerLock`, `:43` `@Transactional`), `BookingReminderScheduler` (`:43`/`:45`), `BandwidthResetService` (`:19`/`:21`). If the transaction advisor runs *inside* the ShedLock advisor the lock is held across commit (correct); if it runs *outside*, ShedLock's `LockProvider.unlock` can fire before the transaction commits, so a second node can start the job against not-yet-committed state. The nesting is currently left to Spring's default advisor ordering, which is not pinned. → **AC4**.
- **`## Deferred from: code review of skillars-deferred-23-…` (2026-08-14)** — the second bullet: **`PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` asserts a hard wall-clock p99 bound inside a correctness-gating IT.** `skillars-deferred-23` AC1 fixed the two proximate flake causes (wrong percentile index, no warmup) but the design choice — `assertThat(p99).isLessThan(200L)` in the same Maven module CI blocks the merge on — is still structurally fragile. → **AC5**.
- **`## Deferred from: code review of skillars-deferred-3 (2026-07-01)`**, D1 — **V76's partial-index predicate hardcodes status literals with no enum cross-check.** *Substantially already addressed*: `V83__fix_session_packs_expiry_index_predicate.sql` (a deferred-3 **review follow-up**) already rewrote `idx_session_packs_purchased_coach_expires` from the blacklist `status NOT IN ('EXHAUSTED', 'EXPIRED')` to the whitelist `status = 'ACTIVE'`, precisely so "any status added in the future is excluded by default instead of included by default". What remains open is the **automated guard**: nothing asserts the migration predicates stay aligned with `chk_spp_status` (V30 — `'ACTIVE','EXHAUSTED','EXPIRED'`), which is the de-facto status enum (`SessionPackPaymentService:270-278` returns the same three string literals; there is no Java enum). → **AC6** (consistency guard test + a sweep for any other status-scoped partial index).
- **`## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)`**, W8 — **`EMAIL_VERIFIED` users have no path to re-request phone OTP.** *Partially addressed*: `skillars-deferred-43` added `POST /api/security/parent/resend-otp` → `ParentRegistrationService.resendPhoneOtp(userId)` (with the `skillars-deferred-88` AC10 V121-collision handling and AC11 locked-user guard already layered on). `CoachRegistrationResource` and `PlayerRegistrationResource` still expose only `/resend-verification` (email). → **AC7** (add `/resend-otp` + `resendPhoneOtp` to coach and player, mirroring parent exactly).
- **`## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`** — **SSH port 22 open to all internet IPs during the provisioning window.** `provision.sh:166` does `ufw allow 22/tcp` (all sources); the Hetzner Cloud firewall that restricts 22 to `SSH_ALLOWLIST_IP/32` is applied later by `deploy/firewall/apply-firewall.sh`, run from the operator's local machine (`first-time-setup.md` Step 6). Deliberate ordering (Hetzner firewall needs the local `hcloud` CLI), but the exposure window is undocumented. → **AC8** (document the window + let `provision.sh` optionally scope the ufw SSH rule to a passed-in `SSH_ALLOWLIST_IP`).
- **`## Deferred from: code review of skillars-deferred-87-… (2026-08-31)`** — three residuals not taken by `skillars-deferred-88` (which took the `flock`, `df` guard, multi-Volume hard-fail, comment-safe fstab purge, `.bak`, and `name unknown` GHCR grep from this same review vein):
  - **deferred-work.md line ~1353** — pre-Volume migration does not run on a host where a prior run already mounted the Volume over a non-empty pre-Volume `data/` tree; `mountpoint -q` short-circuits the staging branch and the shadowed root-disk tree stays hidden forever. AC5 step 6 accepts already-shadowed data as manual-reclaim-only; the gap is that it is **silent**. → **AC9(a)** (detect + warn loudly, document manual reclaim).
  - **deferred-work.md line ~1359** — a `build-and-push` run **cancelled while still *pending*** in the `build-and-push-${{ github.ref }}` concurrency group publishes *no* tags at all, not even `sha-<short>`; in a ≥3-push burst the middle commit has no rollback image. `cancel-in-progress: false` protects only the *running* run. → **AC9(b)** (document as a known rollback-target hole + the manual re-run remedy; a `sha-` "always build even if superseded" path is out of scope).
  - **deferred-work.md line ~1361** — `chown_if_needed`'s `find -maxdepth 2` walks grandchildren (not the "immediate children" its comment claims) and only `-quit`s on a mismatch, so a clean idempotent re-run stat-walks two levels of every observability dir. → **AC9(c)** (align the scope + comment; keep the `skillars-deferred-87` AC3 partial-`chown` recovery intent).
- **AC10** — `deferred-work.md` ledger hygiene: tag every bullet this story closes / partially closes.

**Ten ACs across `development` (SLU persistence), `session` (drill-video), `booking` + `video` schedulers, one video IT, one Flyway migration + a migration-convention guard test, three registration services + two resources + one existing DTO, `provision.sh`, `first-time-setup.md`, `ci.yml`, and the ledger. One Flyway migration (`V122`), additive, small-table, partial unique index. No new Spring context expected (all new tests reuse existing IT base classes — confirm during dev).**

## Acceptance Criteria

### AC1 — `development.player_skill_stats` gets a unique key on `(session_id, skill_code)`; concurrent duplicate SLU delivery for one session conflicts cleanly instead of double-writing.

**Problem** (verified against source at `4f5b5cb`): `PlayerSkillStat` (`src/main/java/com/softropic/skillars/platform/development/repo/PlayerSkillStat.java`) is `@Table(schema = "development", name = "player_skill_stats")`, `@Id @GeneratedValue(GenerationType.UUID) UUID id`, columns `player_id` (Long TSID), `session_id` (UUID, **nullable**, `updatable = false`), `coach_id`, `skill_code` (`length = 10`, `updatable = false`), `slu_value`, `calculated_at`. One `SluCalculationService.onBookingCompleted` invocation writes exactly one row per distinct `skill_code` for one `session_id`. The only guards against a duplicate `BookingCompletedEvent` delivery are non-locking reads: `SluCalculationService.onBookingCompleted:80` (`sluRepository.findBySessionId(...).isEmpty()`) and `SluPersistenceRetrier.saveSluWithRetry` (`existsBySessionId(...)`, `skillars-deferred-86` AC2). Two deliveries racing the check-then-`saveAll` both insert a full set of rows; V119's marker table means the snapshot total is applied once, so the detail sum and the snapshot total diverge permanently for that player/skill/week.

**Fix:**

- **`V122__player_skill_stats_session_skill_unique.sql`** — `CREATE UNIQUE INDEX ix_pss_session_skill_unique ON development.player_skill_stats (session_id, skill_code) WHERE session_id IS NOT NULL;`. Partial (`WHERE session_id IS NOT NULL`) because `session_id` is nullable — the Quick Complete path has no session and is documented as *not reachable* in `saveSluWithRetry` (`rows.get(0).getSessionId()` null-checked), but the column allows null and a plain unique index would be a needless constraint on any future null-session rows. Small table, additive; index build takes a brief `SHARE` lock on a table that is append-only and low-volume (one row per session × skill). This follows the same accepted class as `V119`/`V120`/`V121` (see the `skillars-deferred-84` code-review deferral about online-safe migration convention — stays open codebase-wide; this table is small). Name the index `ix_pss_session_skill_unique` (mirrors the `ix_`/`idx_` prefix already used in `development` migrations — match whichever the newest `development` migration uses).
- **`SluPersistenceRetrier.saveSluWithRetry`** — `saveAll` can now throw `DataIntegrityViolationException` (Spring translation of PG `23505`) when a concurrent delivery already inserted the rows between the `existsBySessionId` check and the flush. Handle it as the **idempotent-collision** case it is: catch `DataIntegrityViolationException` (or `DuplicateKeyException`) from `saveAll`, `log.info` that the session's SLU detail rows were already persisted by a concurrent delivery, and **return normally** — do **not** let it propagate into `@Retryable` (it is not in `retryFor` today; adding it would retry a guaranteed-to-fail insert). The existing `existsBySessionId` short-circuit stays as the first-line fast path; the catch is the race backstop. Update the class javadoc's "Why a whole-method retry is safe" paragraph to name the new unique index as the concurrent-delivery backstop (the merge-on-detached reasoning it documents covers only the *retry* case, not two *distinct* deliveries).
  - **Do not** widen `@Retryable`'s `retryFor` to include `DataIntegrityViolationException` — that would turn a legitimate first-attempt constraint bug into 3 × the same failure.
  - Keep both `@Recover` overloads unchanged.
- **`SluCalculationService.onBookingCompleted`** — no change required (the line-80 read stays as the cheap common-case guard); the DB index + the retrier catch are the correctness backstop. If dev finds it trivial to also wrap the `dispatchSluPersistence` call site, that is acceptable but not required.
- **Do not** change `PlayerSkillStat`'s field set, `updatable = false` flags, `@GeneratedValue`, or the `SluRepository` query set.

**Tests:**
- **Concurrent-delivery IT** (`SluCalculationServiceIT` / `SluPersistenceIT` — `grep -rn "class Slu.*IT" src/test`): drive two `onBookingCompleted` (or two `dispatchSluPersistence`) calls for one session, barrier-synchronised or with a second connection holding a row, and assert the end state has **exactly one** set of `player_skill_stats` rows for that `session_id` (one row per skill), `SUM(slu_value)` equals the single-delivery value, and the `player_slu_weekly_snapshot.total_slu` for that bucket equals that same sum. Mutation check: drop `V122` (or point the test at a schema without it) → two row sets → `SUM` doubles → assertion fails.
- **Unit/slice test on `saveSluWithRetry`**: pre-insert the rows, then call `saveSluWithRetry` with the same session's list and assert it returns normally, logs the "already persisted by a concurrent delivery" line, and adds no rows. (This exercises the catch even if `existsBySessionId` also short-circuits — force the race by pre-inserting *after* the `existsBySessionId` stub returns false, or spy the repo; if a spy would fork a Spring context, keep it a pure Mockito unit test on `SluPersistenceRetrier` with a mocked `SluRepository`.)
- `grep -rn "player_skill_stats" src/test` — any fixture that bulk-inserts detail rows for one session still satisfies the new unique index.

### AC2 — `SluPersistenceDispatcher` runs the weekly-snapshot write only when the SLU detail save actually succeeded.

**Problem** (verified against source): `SluPersistenceDispatcher.dispatchSluPersistence` (`src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcher.java`) is:
```java
sluPersistenceRetrier.saveSluWithRetry(stats);
snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
```
`SluPersistenceRetrier.saveSluWithRetry` is `void`; both its `@Recover` methods are `void` and return normally on exhausted retries (logging `"… rows lost … manual recovery needed"`). So when the detail save is permanently lost, control still falls into `writeAllWithRetry`, which applies additive deltas to `player_slu_weekly_snapshot` and inserts V119 marker rows for a session that now has **zero** `player_skill_stats` rows. The dashboard fast-path (`player_slu_weekly_snapshot`) then shows SLU that `SUM(player_skill_stats.slu_value)` for that session cannot reproduce.

**Fix:**

- **`SluPersistenceRetrier.saveSluWithRetry`** — change the signature to return a `boolean` (`true` = rows are persisted, either by this call, the `existsBySessionId` short-circuit, or the AC1 concurrent-collision catch; `false` = **only** from the `@Recover` paths). Both `@Recover` methods return `false`. The happy path and both idempotent-skip paths return `true`.
  - This is a small, self-contained contract change: `SluPersistenceDispatcher` is the **only** caller (`grep -rn "saveSluWithRetry" src` — confirm; there is one production call site and any test call sites).
- **`SluPersistenceDispatcher.dispatchSluPersistence`:**
  ```java
  boolean sluPersisted = sluPersistenceRetrier.saveSluWithRetry(stats);
  if (sluPersisted) {
      snapshotPersistenceRetrier.writeAllWithRetry(stats, isoYear, isoWeek);
  } else {
      log.error("SLU detail save exhausted its retries for session {} player {} — skipping the weekly-snapshot "
              + "write so player_slu_weekly_snapshot cannot gain a total the detail rows do not back. "
              + "Manual recovery of both is needed.",
          stats.isEmpty() ? null : stats.get(0).getSessionId(),
          stats.isEmpty() ? null : stats.get(0).getPlayerId());
  }
  ```
  Keep the existing end-of-chain `log.info("SLU persistence chain finished …")` line, but only emit it on the `sluPersisted` branch (a skipped snapshot write is not a "finished chain") — or reword it to state which legs ran. Dev's call on the exact wording; the invariant is: **no snapshot write after a failed detail save**, and the logs make the skip explicit.
- Update the `SluPersistenceDispatcher` class javadoc paragraph that currently says *"`saveSluWithRetry`'s `@Recover` returns `void` … so `writeAllWithRetry` still runs afterward — identical to the two sequential calls this replaced"* — that behaviour is exactly what this AC removes; the javadoc must now describe the gated call.
- **Do not** change `SnapshotPersistenceRetrier`, the `@Async("sluRetryExecutor")` boundary, the single-task chaining rationale (both legs still run on one `sluRetryExecutor` task, in order), or `MdcDecorator`.

**Tests:**
- **`SluPersistenceDispatcherTest`** (or extend an existing one — `grep -rn "SluPersistenceDispatcher" src/test`): mock `SluPersistenceRetrier.saveSluWithRetry` to return `false`, call `dispatchSluPersistence`, and `verify(snapshotPersistenceRetrier, never()).writeAllWithRetry(any(), anyShort(), anyShort())` + assert the "skipping the weekly-snapshot write" error logged. Then a second case: `saveSluWithRetry` returns `true` → `writeAllWithRetry` called once. Mutation check: revert the dispatcher to the unconditional call → the `never()` verification fails.
- **`SluPersistenceRetrier` test**: assert `saveSluWithRetry` returns `true` on the happy path, `true` on the `existsBySessionId` short-circuit, `true` on the AC1 concurrent-collision catch, and `false` from each `@Recover` (drive the `@Recover` with a mocked repo throwing a `retryFor` exception `maxAttempts` times).
- If an IT exercises the real `sluRetryExecutor` chain (`grep -rn "sluRetryExecutor" src/test`), add a case where the detail save is forced to exhaust retries and assert `player_slu_weekly_snapshot` gains **no** row for that bucket.

### AC3 — `DrillUploadService` confirms the video row still exists before publishing `VideoPhysicalDeletionEvent`.

**Problem** (verified against source): `DrillUploadService.initiateUpload` (`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`) takes `Optional<Video> lockedVideo = videoRepository.findByIdForUpdate(existingVideoId)` at `:111` (under the Drill→Video pessimistic lock, `skillars-deferred-81` AC3), but the orphaned-reservation publish at `:128-129`:
```java
if (existingVideoId != null && !drillVideoRefRepository.existsByVideoId(existingVideoId)) {
    eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(existingVideoId, drillId));
}
```
gates only on the *ref* table, not on `lockedVideo.isPresent()`. `deleteVideo` (`:150-173`) has the identical shape — it re-locks `videoRepository.findByIdForUpdate(videoId)` at `:168` (discarding the `Optional`) then publishes at `:172-173` on `!drillVideoRefRepository.existsByVideoId(videoId)` alone. If the `videos` row was already physically deleted (e.g. a prior `VideoPhysicalDeletionEvent` already processed, or an admin hard-delete), the event is published again for a row that no longer exists.

**Fix:**

- **`initiateUpload`** — the `lockedVideo` `Optional` is already in scope at the publish site. Gate the publish on it:
  ```java
  if (existingVideoId != null
          && lockedVideo.isPresent()
          && !drillVideoRefRepository.existsByVideoId(existingVideoId)) {
      eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(existingVideoId, drillId));
  }
  ```
  (`lockedVideo` is only assigned inside the `if (existingVideoId != null)` block that precedes this one — confirm the variable scope during dev; if it is scoped narrower, hoist the `findByIdForUpdate` result or add a fresh `videoRepository.existsById(existingVideoId)` check under the lock. Prefer reusing `lockedVideo`.)
- **`deleteVideo`** — capture the `findByIdForUpdate` result instead of discarding it:
  ```java
  Optional<Video> lockedVideo = videoRepository.findByIdForUpdate(videoId);
  // … existing ref-clear …
  if (lockedVideo.isPresent() && !drillVideoRefRepository.existsByVideoId(videoId)) {
      eventPublisher.publishEvent(new VideoPhysicalDeletionEvent(videoId, drillId));
  }
  ```
  Keep the in-code comment that explains why the lock is taken (Def14 ordering); add one line noting the presence check prevents a double-publish for an already-deleted row.
- The two sites must stay structurally identical (their comments already cross-reference each other).
- **Do not** change the lock order, the `lockRetryer.withBoundedRetry` wrapper, the `VideoPhysicalDeletionEvent` record, or the `DrillVideoRef` ref-table semantics.

**Tests:**
- Extend `DrillUploadServiceConcurrencyIT` (or `DrillUploadServiceTest` — `grep -rn "class DrillUploadService.*Test\|class DrillUploadServiceConcurrencyIT" src/test`): seed a drill whose `drill_video_refs` row points at a `videoId` that has **no** `videos` row, invoke `deleteVideo` (and separately the `initiateUpload` replace path), and assert **no** `VideoPhysicalDeletionEvent` is published (`@RecordApplicationEvents` / a captured `ApplicationEventPublisher` mock / an `@EventListener` test bean — mirror whatever the existing tests use). Mutation check: remove the `lockedVideo.isPresent()` guard → the event fires for the missing row → test fails.
- Keep an existing case that a genuinely-orphaned reservation (video row present, ref cleared) **still** publishes — proves the presence check does not suppress the legitimate path.

### AC4 — the three `@SchedulerLock` + `@Transactional` schedulers have a defined advisor order (lock outermost, transaction innermost).

**Problem** (verified against source): `BookingExpiryScheduler.expireStaleRequests` (`src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40-43` — `@Scheduled`, `@SchedulerLock(name = "BookingExpiryScheduler_expire", …)`, `@Transactional`), `BookingReminderScheduler` (`:42-45`), and `BandwidthResetService` (`:18-21`) each stack ShedLock and Spring's transaction advisor on one method with no explicit ordering. ShedLock's advisor and `TransactionInterceptor` both default to a low/late order; which one wraps the other is not pinned. If the transaction advisor is *outermost*, `LockProvider.unlock` runs before commit and another node can pick up the job against uncommitted state.

**Fix:**

- Make ShedLock's advice **outermost** so the lock spans the whole transaction including commit. Two accepted mechanisms — pick one and apply it consistently to all three methods:
  - **(preferred, local + explicit)** move `@Transactional` off the `@SchedulerLock` method onto a **separate delegate bean method** the scheduler calls (`schedulerLockedMethod() { delegate.doWorkTransactional(); }`), so the lock proxy and the transaction proxy are on different beans and the call order is unambiguous (lock acquired → cross-bean call → transaction begins → work → commit → return → lock released). This mirrors how `SluPersistenceDispatcher` keeps `@Async` and `@Retryable` on separate beans "since the advisor nesting order between them is unspecified" — the same reasoning applies here.
  - **(config-level)** register ShedLock via `@EnableSchedulerLock(...)` with an explicit low `order` / a `ScheduledLockConfiguration` whose `LockConfigurationExtractor` / advisor `Ordered` value is set **below** `Ordered.LOWEST_PRECEDENCE` relative to `@EnableTransactionManagement(order = …)` so the lock advisor sorts before the transaction advisor. If the project already sets a transaction advisor order, document the chosen ShedLock order relative to it.
- Whichever mechanism: add a short in-code comment on each of the three methods stating the guarantee ("ShedLock advice is outermost — the distributed lock is released only after this transaction commits; see AC4 of `skillars-deferred-89`").
- Confirm the ShedLock version in use and how `@EnableSchedulerLock` is currently configured (`grep -rn "EnableSchedulerLock\|SchedulerLock\|net.javacrumbs" src pom.xml`). If a `ScheduledLockConfiguration` bean already exists, extend it; do not add a second.

**Tests:**
- **Advisor-order assertion** (`SchedulerLockTransactionOrderingTest`, a lightweight `@SpringBootTest` slice or a context test — reuse an existing base if one exposes the `AopProxyUtils` / advisor chain): for each of the three scheduler beans, resolve the advised method's advisor chain and assert the ShedLock interceptor precedes `TransactionInterceptor`. If the delegate-bean approach is used instead, the test asserts the `@Transactional` annotation is **not** on the `@SchedulerLock` method and **is** on the delegate.
- **Behavioural IT** (best-effort, only if an existing pattern makes it cheap — e.g. `BookingExpirySchedulerIT`): hold the ShedLock row / a DB advisory lock from an external transaction, invoke the scheduler method on a background thread, assert it blocks until the external lock releases, then assert its writes are committed before the method returns. If no cheap harness exists, the advisor-order assertion is sufficient (mirrors how `skillars-deferred-16` AC1 was pinned at the service level).
- Run `-Dtest` for all three existing scheduler test classes green.

### AC5 — `PlaybackServiceIT`'s p99 latency assertion no longer gates build correctness.

**Problem** (verified against source): `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` (`src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:105-125`) seeds a `READY`/`ACTIVE` video, does 20 warmup + 100 measured `authorizePlayback` calls, computes a nearest-rank p99 over `System.nanoTime()` deltas, and `assertThat(p99).isLessThan(200L)`. The percentile-index and warmup bugs are fixed (`skillars-deferred-23` AC1); the remaining issue is that a millisecond wall-clock threshold in the same CI job that gates merges is inherently sensitive to JIT/GC/Testcontainers/CI-host noise.

**Fix** — pick one, dev's call, in this order of preference:

1. **Convert to a non-gating measurement.** Drop the `assertThat(...).isLessThan(200L)` and instead `log.info` the p50/p95/p99 (still run the 100 iterations so the numbers appear in CI logs / can be scraped later). Rename the method to `authorizePlayback_performance_measuresLatencyDistribution` and add a Javadoc line: "measurement only — not a gate; see `skillars-deferred-89` AC5. The correctness of `authorizePlayback` is covered by the other cases in this class." Keep a **very loose** sanity ceiling only if it catches a real pathology (e.g. `isLessThan(5_000L)` — a 5 s p99 means something is genuinely broken, not jitter).
2. **Tag it out of the gate.** Add `@Tag("perf")` and ensure the Surefire/Failsafe config for the CI `build` job `excludedGroups` includes `perf` (check `pom.xml` / the CI `mvn` invocation — `grep -n "excludedGroups\|groups\|Tag(" pom.xml .github/workflows/ci.yml`). Only viable if a tag-exclusion mechanism already exists or is trivial to add without disturbing other tests.

Whichever: the change must **not** reduce coverage of `authorizePlayback`'s correctness — the not-found / denied / access-state cases in `PlaybackServiceIT` stay exactly as they are.

**Tests:** the modified `PlaybackServiceIT` itself; `-Dtest=PlaybackServiceIT` green. If option 2, confirm the CI `build` step's log shows the perf case skipped and the rest of the class run.

### AC6 — an automated guard keeps status-scoped partial indexes aligned with `chk_spp_status`.

**Problem** (verified against source): `V76__missing_indexes.sql` originally created `idx_session_packs_purchased_coach_expires … WHERE status NOT IN ('EXHAUSTED', 'EXPIRED')`. `V83__fix_session_packs_expiry_index_predicate.sql` (a deferred-3 review follow-up) **already** rewrote it to `WHERE status = 'ACTIVE'` — a whitelist, so a future status is excluded by default. `chk_spp_status` (`V30__booking_session_packs.sql`) currently allows `'ACTIVE','EXHAUSTED','EXPIRED'`; `SessionPackPaymentService:270-278` returns those same three literals (there is no Java enum — the CHECK constraint is the de-facto enum). What is still missing is any test that fails when a migration predicate and the CHECK constraint drift apart, or when a **new** status-scoped partial index is added as a blacklist.

**Fix — a guard test, no migration change** (V83 already did the substantive fix):

- **`SessionPackStatusIndexConsistencyIT`** (a `@SpringBootTest` + Testcontainers IT reusing the existing DB IT base — `grep -rn "class .*IT extends AbstractIntegrationTest" src/test | head`): query `pg_constraint` for `chk_spp_status`'s definition and `pg_indexes` for `idx_session_packs_purchased_coach_expires`, parse the status literal set out of each, and assert:
  - the CHECK constraint's allowed set is exactly `{ACTIVE, EXHAUSTED, EXPIRED}` (the known-good baseline — this line is what fails the day someone adds a 4th status without revisiting the indexes);
  - the partial index predicate is a **whitelist** (`status = '…'` or `status IN ('…')`), not a blacklist (`status NOT IN (...)`), and every literal it names is in the CHECK set.
- **Sweep** for other status-scoped partial indexes: `grep -rn "WHERE .*status" src/main/resources/db/migration/`. For each hit on a `NOT IN` / `!=` status predicate, either add it to the same consistency assertion or record in the Dev Agent Record why it is safe (e.g. the column has no CHECK constraint / is genuinely open-ended). Do **not** rewrite other migrations in this story unless one is a live blacklist bug — if found, flag it as a decision-needed item, don't silently change it.
- Add a one-line comment to `V83`'s trailing note (or a `docs/` line) pointing at the new guard test as the thing that enforces the convention it describes.

**Tests:** `SessionPackStatusIndexConsistencyIT` itself. Mutation check to record: temporarily change the expected CHECK set to include a bogus 4th status → the test fails (proves it is actually asserting, not vacuous).

### AC7 — `/resend-otp` parity for coach and player registration.

**Problem** (verified against source): `ParentRegistrationResource` exposes `POST /api/security/parent/resend-otp` → `ParentRegistrationService.resendPhoneOtp(Long userId)` (`skillars-deferred-43`), which: loads the user, throws `OtpVerificationException("security.accountLocked")` if `user.isLocked()` (`skillars-deferred-88` AC11), rejects non-`EMAIL_VERIFIED` status, `otpTokenRepository.deleteByUserIdAndUsedFalse(userId)`, `generateOtp()`, persists a fresh token (with the `skillars-deferred-88` AC10 V121 `uq_pot_one_active_per_user` collision → `ApiAdvice` 409 `security.otpResendInProgress` handling), and sends the SMS. `ResendOtpRequest` (`src/main/java/com/softropic/skillars/platform/security/api/dto/ResendOtpRequest.java` — `record ResendOtpRequest(@NotNull @Positive Long userId)`) already exists. `CoachRegistrationResource` and `PlayerRegistrationResource` expose only `/register`, `/verify-email`, `/verify-phone`, `/resend-verification` — **no `/resend-otp`**. `CoachRegistrationService` / `PlayerRegistrationService` have no `resendPhoneOtp` method.

**Fix — mirror parent exactly:**

- **`CoachRegistrationService` / `PlayerRegistrationService`** — add `public void resendPhoneOtp(Long userId)` copying `ParentRegistrationService.resendPhoneOtp` line-for-line (adjusting only the injected collaborators' names if they differ). It must include: the `user.isLocked()` → `OtpVerificationException("security.accountLocked")` guard, the `verificationStatus != EMAIL_VERIFIED` rejection, `deleteByUserIdAndUsedFalse`, `generateOtp`, token persist, SMS send. If parent's version takes a rate-limit check (`grep -n "RateLimit\|rateLimiting\|attempts" ParentRegistrationService.java`), copy that too.
- **`CoachRegistrationResource` / `PlayerRegistrationResource`** — add:
  ```java
  @PreAuthorize("permitAll()")
  @PostMapping("/resend-otp")
  public ResponseEntity<Void> resendOtp(@RequestBody @Valid ResendOtpRequest request) {
      coachRegistrationService.resendPhoneOtp(request.userId());   // playerRegistrationService for the player one
      return ResponseEntity.noContent().build();
  }
  ```
  Match parent's exact annotation set, return type, and status (parent returns `noContent()` / 204 — confirm and mirror). Reuse the existing `ResendOtpRequest` DTO — do **not** create per-role copies.
- **Anti-enumeration parity:** whatever parent's `resendPhoneOtp` does for a non-existent `userId` (throw vs. silent-ish), coach and player must do the same. Confirm parent's behaviour and mirror it; note it in the Dev Agent Record.
- **i18n:** if the coach/player SMS body template key differs from parent's, add the coach/player keys to `messages_en.properties` / `messages_de.properties`; otherwise reuse. `grep -rn "otp\|Otp" src/main/resources/i18n/messages_en.properties`.
- **Frontend:** out of scope for this story (backend endpoint parity only) — but add a one-line note to the story's Completion Notes that the coach/player registration UI still has no "resend OTP" button (matches the standing frontend-coverage gap the ledger repeatedly accepts).

**Tests:**
- **`CoachRegistrationResourceIT` / `PlayerRegistrationResourceIT`** — mirror the parent `/resend-otp` cases (`grep -n "resend-otp\|resendPhoneOtp\|resendOtp" src/test/java/com/softropic/skillars/platform/security/api/ParentRegistrationResourceIT.java`): happy path (EMAIL_VERIFIED user → 204, a new `used=false` token row exists, old one deleted); locked user → 400 `security.accountLocked`, no token inserted; non-`EMAIL_VERIFIED` → rejected; the V121 collision → 409 `security.otpResendInProgress` (reuse the spy-free `secondActiveOtpInsert_forSameUser_isRejectedByPartialUniqueIndex` shape from `skillars-deferred-88` AC10 — **do not** add a `@MockitoSpyBean`, it forks a Spring context over the CI ceiling of 37).
- Keep the new tests inside the existing `CoachRegistrationResourceIT` / `PlayerRegistrationResourceIT` classes (they already `extend AbstractIntegrationTest` — no new context).

### AC8 — the SSH-port-22 provisioning-exposure window is documented, and `provision.sh` can optionally scope the ufw SSH rule.

**Problem** (verified against source): `provision.sh` section 5 (`deploy/provision.sh:162-176`) does `ufw allow 22/tcp comment 'SSH'` with no source restriction, then `ufw --force enable`. The Hetzner Cloud firewall that restricts 22 to `SSH_ALLOWLIST_IP/32` is applied later, from the operator's local machine, by `deploy/firewall/apply-firewall.sh` (`first-time-setup.md` Step 6). `first-time-setup.md:180` notes "The Hetzner default allows SSH from all IPs" but does not call out the exposure **window** between provisioning and firewall application, nor offer a way to narrow it.

**Fix:**

- **`docs/deployment/first-time-setup.md`** — in the Step 3 (provisioning) section and/or a "Security notes" callout, add an explicit paragraph: between finishing `provision.sh` and running `apply-firewall.sh`, TCP 22 is reachable from any internet IP (protected only by key-only auth + `fail2ban`); minimise the window by running Step 6 immediately after Step 3, and — for an operator who already has a local clone and knows their egress IP — optionally pass `SSH_ALLOWLIST_IP` to `provision.sh` (see below) so the host firewall is scoped from the start. State plainly that the Hetzner Cloud firewall remains the real perimeter.
- **`deploy/provision.sh`** section 5 — if `SSH_ALLOWLIST_IP` is set in the environment and non-empty, replace `ufw allow 22/tcp` with `ufw allow from "${SSH_ALLOWLIST_IP}" to any port 22 proto tcp comment 'SSH (allowlisted)'`; otherwise keep today's `ufw allow 22/tcp` and `log` a one-line warning that SSH is open to all IPs until `apply-firewall.sh` runs. Validate the value shape defensively (`case "${SSH_ALLOWLIST_IP}" in *[!0-9./]*) log "SSH_ALLOWLIST_IP malformed — ignoring, opening 22 to all"; unset SSH_ALLOWLIST_IP ;; esac` before the `ufw` call — a malformed value must **fail open** to today's behaviour, never brick the provisioning SSH session. Document the env var at the top-of-script variable block and in `first-time-setup.md`.
- Keep the "allow SSH before `ufw enable`" ordering exactly as-is (the critical-path comment stays).

**Verification:** `bash -n deploy/provision.sh`; `shellcheck` no-new-findings vs. the saved baseline; Dev Agent Record hand-trace: (a) `SSH_ALLOWLIST_IP` unset → `ufw allow 22/tcp` + warning line (today's behaviour); (b) `SSH_ALLOWLIST_IP=203.0.113.10` → scoped `ufw allow from …` rule; (c) `SSH_ALLOWLIST_IP="; rm -rf /"` or other malformed → ignored, falls back to (a) with a "malformed — ignoring" log, provisioning SSH session unaffected. Confirm `first-time-setup.md` renders (markdown lint / manual read) and the new env var appears in any secrets/vars reference table if one lists provisioning-time vars.

### AC9 — three `skillars-deferred-87` review residuals.

**`deploy/provision.sh` + `.github/workflows/ci.yml` + `docs/deployment/`.**

**(a) Pre-Volume migration silently no-ops on an already-shadowed host.** `provision.sh` section 7's `mountpoint -q "${MOUNT_POINT}"` short-circuit skips the staging branch, so a host where a prior run mounted the Volume over a **non-empty** pre-Volume `data/` tree keeps that tree shadowed forever with no signal. AC5 step 6 of `skillars-deferred-87` accepts already-shadowed data as manual-reclaim-only.
- **Fix:** when `mountpoint -q "${MOUNT_POINT}"` is true (Volume already mounted), before short-circuiting, check whether the **underlying** root-disk directory (the mount source path, inspected via a bind mount to a temp dir, or `findmnt`/`lsblk` to reach the shadowed content — pick the simplest reliable method for this Ubuntu base) is non-empty. If it is, `log` a prominent multi-line WARNING naming the shadowed path, the fact that its contents (possibly old TLS certs / LGTM / Redis data) are inaccessible under the mount, and the exact manual-reclaim steps (`umount ${MOUNT_POINT}` → inspect/copy/`rm -rf` the root-disk contents → remount / `mount -a`). Do **not** attempt automatic reclaim (destructive, out of scope). If reaching the shadowed content is not reliably possible without unmounting, degrade to an unconditional "if you provisioned this host before attaching the Volume, verify no pre-Volume data is shadowed under ${MOUNT_POINT} — see first-time-setup.md" warning on the already-mounted path.
- **`first-time-setup.md`** — ensure the manual-reclaim procedure is documented (it may already be from `skillars-deferred-87` AC5 — confirm and cross-link, don't duplicate).

**(b) `build-and-push` cancelled while *pending* publishes no tags at all.** GitHub keeps one pending run per concurrency group and cancels a previously-pending one when a newer queues; in a ≥3-push burst the middle commit's image is never built, so `docs/deployment/rollback.md` has no rollback target for it. `cancel-in-progress: false` protects only the *running* run.
- **Fix (documentation + a comment, no workflow logic change):** add a comment in `ci.yml` at the `concurrency:` block for `build-and-push` stating this known gap explicitly (a pending run superseded by a newer push produces no `sha-` tag for its commit), and add a short subsection to `docs/deployment/rollback.md` (or the CI/ops doc that owns rollback targets): "If a commit has no `sha-<short>` image in GHCR (its CI run was cancelled while queued behind newer pushes), re-run that commit's CI from the Actions tab (`Re-run all jobs`) to produce the image before rolling back to it." A code fix (a separate always-runs `sha-`-only build job outside the concurrency group) is explicitly **out of scope** — record that in the Dev Agent Record.

**(c) `chown_if_needed`'s `find -maxdepth 2` scope + early-exit.** The comment says "immediate children" but `-maxdepth 2` walks grandchildren; it only `-quit`s on a mismatch, so a clean re-run stat-walks two levels of every observability dir.
- **Fix:** align the code and the comment. Either narrow to `-maxdepth 1` if immediate children are genuinely all that the `skillars-deferred-87` AC3 partial-`chown` recovery needs to detect (a run killed mid-`chown -R` leaves the top dir done and *some* children wrong — one level is enough to catch that), **or** keep `-maxdepth 2` and fix the comment to say "the directory plus two levels" and explain why two levels are needed. Preferred: `-maxdepth 1` + comment "immediate children only — enough to detect a `chown -R` killed after the top-level entry". Preserve the `-quit`-on-first-mismatch behaviour and the overall "recheck when the top-level owner already matches" logic from `skillars-deferred-87` AC3.

**Verification:** `bash -n`; `shellcheck` no-new-findings; Dev Agent Record hand-trace for (a) [already-mounted + shadowed non-empty root tree → WARNING with reclaim steps; already-mounted + empty/clean → silent as today] and (c) [top-level owner matches, one child wrong → detected and re-`chown`ed; all correct → single-level stat walk, no `chown`]. For (b): confirm the `ci.yml` comment and the `rollback.md` subsection render and are accurate against the current `concurrency:` config.

### AC10 — `deferred-work.md` ledger hygiene.

Update `_bmad-output/implementation-artifacts/deferred-work.md`:

- **`## Deferred from: code review of skillars-deferred-86-…`** — append `[CLOSED by skillars-deferred-89 AC1 …]` to the `player_skill_stats` uniqueness bullet and `[CLOSED by skillars-deferred-89 AC2 …]` to the `SluPersistenceDispatcher` snapshot-write bullet, each naming the mechanism (V122 partial unique index + concurrent-collision catch; `saveSluWithRetry` boolean return gating `writeAllWithRetry`).
- **`## Deferred from: code review of skillars-deferred-22-…`** — append `[CLOSED by skillars-deferred-89 AC3 …]` to the "`initiateUpload` doesn't confirm the video row still exists" bullet (note the mirrored `deleteVideo` fix).
- **`## Deferred from: code review of skillars-deferred-4 (2026-07-02)`** — append `[CLOSED by skillars-deferred-89 AC4 …]` to D3, naming the chosen mechanism (delegate-bean separation or explicit advisor order) and the guarantee (lock released after commit).
- **`## Deferred from: code review of skillars-deferred-23-…`** — append `[CLOSED by skillars-deferred-89 AC5 …]` to the `PlaybackServiceIT` p99 bullet (note: converted to measurement-only / tagged out of the gate).
- **`## Deferred from: code review of skillars-deferred-3 (2026-07-01)`** — append to D1: note `V83` already converted the specific index to a whitelist and `skillars-deferred-89` AC6 adds the automated `chk_spp_status` ↔ partial-index consistency guard + a codebase sweep; mark `[CLOSED]` only if the sweep finds no other live blacklist (else record the follow-up).
- **`## Deferred from: code review of skillars-1-3-… (2026-06-11)`** — update W8: `skillars-deferred-43` added parent `/resend-otp`; `skillars-deferred-89` AC7 adds coach + player parity → `[CLOSED]`.
- **`## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`** — append `[CLOSED by skillars-deferred-89 AC8 …]` to the "SSH port 22 open to all IPs during the provisioning window" bullet (documented + optional `SSH_ALLOWLIST_IP` scoping).
- **`## Deferred from: code review of skillars-deferred-87-… (2026-08-31)`** — append `[CLOSED by skillars-deferred-89 AC9(a)/(b)/(c)]` to the three residual bullets (already-shadowed host; `build-and-push` cancelled-while-pending; `chown_if_needed` `-maxdepth 2`), each stating what was done (warn+document; document+comment, code fix out of scope; `-maxdepth 1` + comment).
- If any item is only *partially* closed, use `[PARTIALLY CLOSED by skillars-deferred-89 …]` with the residual spelled out, matching the ledger's existing convention.

## Tasks / Subtasks

- [ ] **Task 1 — AC1: `player_skill_stats` unique key + concurrent-delivery handling**
  - [ ] `V122__player_skill_stats_session_skill_unique.sql` — partial unique index `(session_id, skill_code) WHERE session_id IS NOT NULL`; confirm index-name prefix against newest `development` migration
  - [ ] `SluPersistenceRetrier.saveSluWithRetry` — catch `DataIntegrityViolationException`/`DuplicateKeyException` from `saveAll` as an idempotent-collision no-op (log + return); do NOT add to `retryFor`; update class javadoc
  - [ ] Concurrent-delivery IT (one set of detail rows, `SUM` == single-delivery, snapshot total == `SUM`); mutation check = drop V122
  - [ ] Unit test on the collision catch
  - [ ] `grep -rn "player_skill_stats" src/test` — fixtures still satisfy the index
- [ ] **Task 2 — AC2: gate the snapshot write on SLU-save success**
  - [ ] `SluPersistenceRetrier.saveSluWithRetry` → return `boolean` (true = persisted incl. idempotent skips; false = only from `@Recover`); both `@Recover` return false
  - [ ] `SluPersistenceDispatcher.dispatchSluPersistence` — call `writeAllWithRetry` only when `saveSluWithRetry` returned true; ERROR-log the skip; fix the end-of-chain log line; update class javadoc
  - [ ] `grep -rn "saveSluWithRetry" src` — update the (single) production caller + any test callers
  - [ ] `SluPersistenceDispatcherTest` — `never()` on the snapshot write when save fails; called once when it succeeds; mutation check
  - [ ] `SluPersistenceRetrier` test — return value across happy / short-circuit / collision-catch / both `@Recover` paths
- [ ] **Task 3 — AC3: video-row existence guard before `VideoPhysicalDeletionEvent`**
  - [ ] `DrillUploadService.initiateUpload` — add `lockedVideo.isPresent()` to the publish condition at `:128`
  - [ ] `DrillUploadService.deleteVideo` — capture `findByIdForUpdate` result; add `lockedVideo.isPresent()` to the publish condition at `:172`; add one comment line
  - [ ] Test: ref points at a non-existent `videos` row → no event published (both `deleteVideo` and `initiateUpload` replace path); mutation check
  - [ ] Test: genuinely orphaned reservation (video row present) still publishes
- [ ] **Task 4 — AC4: `@SchedulerLock`/`@Transactional` advisor order on the 3 schedulers**
  - [ ] `grep -rn "EnableSchedulerLock\|net.javacrumbs\|ScheduledLockConfiguration" src pom.xml` — establish current ShedLock config + version
  - [ ] Apply the chosen mechanism (preferred: move `@Transactional` to a separate delegate bean) to `BookingExpiryScheduler`, `BookingReminderScheduler`, `BandwidthResetService`; add the guarantee comment on each
  - [ ] `SchedulerLockTransactionOrderingTest` — assert ShedLock advice precedes `TransactionInterceptor` (or the delegate-split structure)
  - [ ] Optional behavioural IT if a cheap harness exists
  - [ ] `-Dtest` for the 3 existing scheduler test classes green
- [ ] **Task 5 — AC5: de-flake `PlaybackServiceIT` p99 assertion**
  - [ ] Convert to measurement-only (log p50/p95/p99, drop the hard `< 200ms`, keep a loose pathology ceiling) OR `@Tag("perf")` + CI exclusion — record which and why
  - [ ] Rename the method to reflect measurement-only; Javadoc note pointing at AC5
  - [ ] `-Dtest=PlaybackServiceIT` green; confirm other cases untouched
- [ ] **Task 6 — AC6: status-scoped partial-index consistency guard**
  - [ ] `SessionPackStatusIndexConsistencyIT` — parse `chk_spp_status` + `idx_session_packs_purchased_coach_expires` from catalogs; assert CHECK set == {ACTIVE,EXHAUSTED,EXPIRED}, predicate is a whitelist over a subset
  - [ ] `grep -rn "WHERE .*status" src/main/resources/db/migration/` — sweep for other status-scoped partial indexes; extend the assertion or document each as safe; flag (don't fix) any live blacklist bug as decision-needed
  - [ ] Comment/doc line pointing V83's note at the new guard
  - [ ] Mutation check: bogus 4th expected status → test fails
- [ ] **Task 7 — AC7: `/resend-otp` coach + player parity**
  - [ ] Read `ParentRegistrationService.resendPhoneOtp` + `ParentRegistrationResource` `/resend-otp` in full
  - [ ] `CoachRegistrationService.resendPhoneOtp` / `PlayerRegistrationService.resendPhoneOtp` — mirror parent (locked guard, status check, delete-then-insert, V121 collision, rate limit if parent has one, SMS send, anti-enumeration behaviour)
  - [ ] `CoachRegistrationResource` / `PlayerRegistrationResource` — `POST /resend-otp` mirroring parent's annotations/return; reuse `ResendOtpRequest`
  - [ ] i18n keys if the SMS template differs
  - [ ] `CoachRegistrationResourceIT` / `PlayerRegistrationResourceIT` — happy / locked / non-EMAIL_VERIFIED / V121-collision cases (NO `@MockitoSpyBean` — spy-free per `skillars-deferred-88` AC10)
  - [ ] Completion note: coach/player registration UI still has no resend-OTP button (frontend out of scope)
- [ ] **Task 8 — AC8: SSH provisioning-window doc + optional `SSH_ALLOWLIST_IP` scoping**
  - [ ] `docs/deployment/first-time-setup.md` — document the port-22-open window + mitigation + the new env var
  - [ ] `deploy/provision.sh` section 5 — optional scoped `ufw allow from ${SSH_ALLOWLIST_IP} … port 22`; malformed value fails open to `ufw allow 22/tcp` + warning; document the var at the top-of-script block
  - [ ] `bash -n` + `shellcheck` (no-new-findings); hand-trace unset / valid / malformed
  - [ ] Add `SSH_ALLOWLIST_IP` to any provisioning-vars reference table
- [ ] **Task 9 — AC9: three deferred-87 residuals**
  - [ ] (a) `provision.sh` — already-mounted `${MOUNT_POINT}` + non-empty shadowed root tree → prominent WARNING + manual-reclaim steps; no auto-reclaim; cross-link `first-time-setup.md`
  - [ ] (b) `ci.yml` — comment at the `build-and-push` `concurrency:` block re: cancelled-while-pending → no `sha-` tag; `rollback.md` subsection with the `Re-run all jobs` remedy; code fix explicitly out of scope
  - [ ] (c) `provision.sh` `chown_if_needed` — `-maxdepth 1` + corrected comment (or keep `-maxdepth 2` + fix comment); preserve the `skillars-deferred-87` AC3 recovery intent + `-quit`
  - [ ] `bash -n` + `shellcheck` (no-new-findings); hand-traces for (a) and (c)
- [ ] **Task 10 — AC10: ledger hygiene**
  - [ ] Tag deferred-86 (×2), deferred-22, deferred-4 D3, deferred-23, deferred-3 D1, skillars-1-3 W8, deploy-1-5 SSH, deferred-87 (×3) bullets with `[CLOSED / PARTIALLY CLOSED by skillars-deferred-89 AC…]`
- [ ] **Task 11 — verification roll-up**
  - [ ] Targeted `-Dtest` for every touched/added test class (record counts in the Dev Agent Record)
  - [ ] `bash -n` + `shellcheck` + (if `ci.yml` touched) `actionlint` clean
  - [ ] No local `mvn verify` (`[[feedback_no_local_mvn_verify]]`); CI is the full-suite gate
  - [ ] Confirm no new Spring context (context-count ceiling is 37 — `skillars-deferred-88` hit it; do NOT add `@MockitoBean`/`@MockitoSpyBean`/`@TestPropertySource`/extra `@SpringBootTest` config)

## Dev Notes

- **No local `mvn verify`** — `[[feedback_no_local_mvn_verify]]`. Backend changes (AC1–AC7) are verified by targeted `-Dtest` runs recorded in the Dev Agent Record; the full suite is CI's gate. Shell/YAML/doc changes (AC8, AC9) are verified by `bash -n`, `shellcheck` (no-new-findings vs. the saved baseline), `actionlint` (if `ci.yml` changes), and manual doc read — matching the established `deploy/**` verification path (`skillars-deferred-85`/`-87`/`-88`).
- **Context-count ceiling is 37 and was hit exactly by `skillars-deferred-88`.** The CI `build` job fails if the suite builds >37 Spring contexts. Every new test in this story MUST reuse an existing IT base class (`AbstractIntegrationTest` / the security / development / session IT bases) with **no** `@MockitoBean`, `@MockitoSpyBean`, `@TestPropertySource`, `@ActiveProfiles`, `@Import`, or extra `@SpringBootTest` config. `skillars-deferred-88` AC10's `secondActiveOtpInsert_forSameUser_isRejectedByPartialUniqueIndex` is the reference pattern for proving a DB constraint without a spy. If a new context is genuinely unavoidable, STOP and flag it.
- **One Flyway migration: `V122`** (AC1). Additive, small-table, partial unique index. Max existing migration is `V121`. No `NOT VALID`/`VALIDATE` split at this table size (accepted class — the `skillars-deferred-84` online-migration deferral stays open codebase-wide).
- **AC2 is a return-type contract change on `saveSluWithRetry`** — `void` → `boolean`. `SluPersistenceDispatcher` is the sole production caller. Keep the two `@Recover` overloads returning the sentinel (`false`); Spring Retry requires `@Recover` return types to be assignable to the retried method's return type, so both must now return `boolean`.
- **AC3**: `DrillUploadService` — the `lockedVideo` `Optional` from `findByIdForUpdate` is the causal lever (mirrors the `skillars-deferred-88` AC2 / `MessagingService` insight that the lock's *result*, not just the call, is what a fix must hang off). `initiateUpload` and `deleteVideo` MUST stay structurally parallel — their comments cross-reference each other.
- **AC4**: prefer the **delegate-bean** split (transaction on a different bean than `@SchedulerLock`) — it needs no global advisor-order tuning and is locally obvious, matching this codebase's own precedent (`SluPersistenceDispatcher` keeps `@Async`/`@Retryable` on separate beans "since the advisor nesting order between them is unspecified"). Only reach for `@EnableSchedulerLock` order config if a single `ScheduledLockConfiguration` bean already centralises this.
- **AC5**: measurement-only is preferred over tag-exclusion unless a `@Tag` exclusion mechanism already exists in `pom.xml`/CI. The correctness of `authorizePlayback` is covered by the other `PlaybackServiceIT` cases — this method must stop being a merge gate, that's the whole point.
- **AC6**: **no migration change** — `V83` already did the substantive whitelist conversion. This AC is purely the automated guard + a sweep. If the sweep finds another live `NOT IN` status blacklist index, that is a **decision-needed** item (surface it, don't silently rewrite another story's migration).
- **AC7**: mirror `ParentRegistrationService.resendPhoneOtp` and `ParentRegistrationResource` `/resend-otp` **exactly** — including anti-enumeration behaviour for a bad `userId`, the `skillars-deferred-88` AC10 V121-collision → 409 path (already handled centrally by `ApiAdvice`'s `DataIntegrityViolationException` handler + `CONSTRAINT_MAPPINGS` — no per-service catch needed), and the AC11 `user.isLocked()` guard. Reuse `ResendOtpRequest`.
- **AC8/AC9 `provision.sh`** — line citations are against `provision.sh` at its current length post-`skillars-deferred-88` (which added section 0 `flock` + the multi-Volume/`df`/fstab work — re-anchor exact line numbers during dev). Section 5 = ufw; section 7 = Volume resolve/stage/mount/fstab/migrate; `chown_if_needed` is a helper near the top.
- **AC9(b)** is deliberately documentation-only — a code fix (separate always-runs `sha-`-only job) is out of scope and must be recorded as such, not silently attempted.

### Project Structure Notes

- **Development/SLU** lives under `src/main/java/com/softropic/skillars/platform/development/**` (schema `development`). `SluCalculationService`, `SluPersistenceRetrier`, `SnapshotPersistenceRetrier`, `SluPersistenceDispatcher` are all in `.../development/service/`; `PlayerSkillStat` / `SluRepository` in `.../development/repo/`.
- **Drill-video** lives under `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` (schema `session`); `VideoPhysicalDeletionEvent` is in `platform/session/contract/`. The `videos` table is owned by `platform/video/**`.
- **Booking schedulers** under `platform/booking/service/` (`BookingExpiryScheduler`, `BookingReminderScheduler`); `BandwidthResetService` under `platform/video/service/`. ShedLock config: locate via `grep`.
- **Registration** under `platform/security/service/**` + `platform/security/api/**` (+ `api/dto/`). Tables `main.phone_otp_tokens`, `main.email_verification_tokens`, `main."user"`.
- **Migrations**: `src/main/resources/db/migration/` — next is `V122`.
- **Deploy**: `deploy/provision.sh`, `deploy/firewall/apply-firewall.sh`, `docs/deployment/first-time-setup.md`, `docs/deployment/rollback.md`, `.github/workflows/ci.yml`.
- New tests reuse existing IT base classes — **no new Spring `@SpringBootTest` context** expected (context-count ceiling — see Dev Notes).

### References

- [Source: `src/main/java/com/softropic/skillars/platform/development/repo/PlayerSkillStat.java`] — `@Table(schema="development", name="player_skill_stats")`; `session_id` nullable `updatable=false`; `skill_code` `length=10`; `@GeneratedValue(GenerationType.UUID)`, no `@Version`.
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java:80`] — non-locking `findBySessionId(...).isEmpty()` idempotency guard.
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java`] — `saveSluWithRetry` (`void`), `existsBySessionId` short-circuit (`skillars-deferred-86` AC2), two `void` `@Recover` methods, `@Retryable(retryFor = {DataAccessException, TransactionSystemException, CannotCreateTransactionException})`.
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcher.java`] — `dispatchSluPersistence` `@Async("sluRetryExecutor")`; two sequential retrier calls on one task; javadoc explicitly notes `@Recover` `void` → `writeAllWithRetry` still runs.
- [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:111,128-129,150-173`] — `initiateUpload` `lockedVideo` from `findByIdForUpdate`; publish gated only on `!existsByVideoId`; `deleteVideo` mirror.
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40-43`, `BookingReminderScheduler.java:42-45`, `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java:18-21`] — `@Scheduled` + `@SchedulerLock` + `@Transactional` stacked, no explicit order.
- [Source: `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:105-125`] — `authorizePlayback_performance_p99Under200ms`, `assertThat(p99).isLessThan(200L)`.
- [Source: `src/main/resources/db/migration/V76__missing_indexes.sql`, `V83__fix_session_packs_expiry_index_predicate.sql`, `V30__booking_session_packs.sql`] — V76 original blacklist predicate; V83 whitelist rewrite + its rationale note; `chk_spp_status` allows `'ACTIVE','EXHAUSTED','EXPIRED'`.
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:270-278`] — returns the string literals `EXHAUSTED` / `EXPIRED` / `ACTIVE` (no Java enum).
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/ParentRegistrationResource.java:58-61`] — `POST /resend-otp` → `parentRegistrationService.resendPhoneOtp(request.userId())`.
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java:227-240`] — `resendPhoneOtp`: locked guard, `EMAIL_VERIFIED` check, `deleteByUserIdAndUsedFalse`, `generateOtp`, persist.
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/dto/ResendOtpRequest.java`] — `record ResendOtpRequest(@NotNull @Positive Long userId)` — already exists.
- [Source: `src/main/java/com/softropic/skillars/platform/security/api/CoachRegistrationResource.java`, `PlayerRegistrationResource.java`] — only `/register`, `/verify-email`, `/verify-phone`, `/resend-verification`; no `/resend-otp`.
- [Source: `deploy/provision.sh:162-176`] — section 5 ufw; `ufw allow 22/tcp comment 'SSH'` unrestricted, before `ufw --force enable`.
- [Source: `docs/deployment/first-time-setup.md:91,164-181`] — ufw allows 22/80/443; Hetzner firewall (Step 6, `apply-firewall.sh`) restricts 22 to `SSH_ALLOWLIST_IP`; "Hetzner default allows SSH from all IPs".
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — deferred-86 review (player_skill_stats uniqueness; `SluPersistenceDispatcher`), deferred-22 (initiateUpload existence), deferred-4 D3 (`@SchedulerLock`/`@Transactional` order), deferred-23 (`PlaybackServiceIT` p99), deferred-3 D1 (V76 predicate), skillars-1-3 W8 (`/resend-otp`), deploy-1-5 2026-06-03 (SSH window), deferred-87 review lines ~1353/~1359/~1361.
- `[[project_skillars_release_workflow]]`, `[[feedback_no_local_mvn_verify]]`.

## Dev Agent Record

### Agent Model Used

_TBD by dev_

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Version | Description | Author |
|------|---------|-------------|--------|
| 2026-08-31 | 0.1 | Story created via story-creation process. Cross-cutting bundle of 9 project-owner-selected ledger items: AC1 `player_skill_stats` `(session_id, skill_code)` partial unique index (V122) + concurrent-delivery collision catch (deferred-86 review); AC2 `SluPersistenceDispatcher` gates the snapshot write on `saveSluWithRetry` success — signature `void`→`boolean` (deferred-86 review); AC3 `DrillUploadService.initiateUpload`/`deleteVideo` confirm the `videos` row exists (via the held `findByIdForUpdate` result) before publishing `VideoPhysicalDeletionEvent` (deferred-22); AC4 defined `@SchedulerLock`/`@Transactional` advisor order (lock outermost) on `BookingExpiryScheduler`/`BookingReminderScheduler`/`BandwidthResetService` — preferred fix is a delegate-bean transaction split (deferred-4 D3); AC5 `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` converted to measurement-only / tagged out of the CI gate (deferred-23); AC6 automated `chk_spp_status` ↔ status-scoped partial-index consistency guard + codebase sweep — no migration change, V83 already did the whitelist conversion (deferred-3 D1); AC7 `/resend-otp` + `resendPhoneOtp` parity for coach + player, mirroring the existing parent implementation (skillars-1-3 W8, extends deferred-43); AC8 document the SSH-port-22-open provisioning window + optional `SSH_ALLOWLIST_IP` scoping in `provision.sh` (deploy-1-5); AC9 three deferred-87 residuals — (a) warn loudly on an already-shadowed pre-Volume host, (b) document the `build-and-push` cancelled-while-pending → no-tags gap (code fix out of scope), (c) `chown_if_needed` `-maxdepth` scope/comment fix. AC10 ledger hygiene. 1 Flyway migration (V122), additive small-table. No new Spring context expected (CI context ceiling 37, hit by deferred-88). Status: ready-for-dev. | Mbah (create-story) |
