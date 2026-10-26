# Story skillars-deferred-163: ConfigService hardening, recap/timeline lifecycle fixes, frontend request robustness, CI gate hardening, and data-model cleanup

Status: ready-for-dev

<!-- Created 2026-10-26 (/bmad-create-story). User-requested bundle mined from deferred-work.md (8 groups,
     20 ledger bullets). Verified against HEAD 0f089e5b (skillars-deferred-162 merged) before drafting; see
     "Verified state at drafting." Ledger line numbers below are current working-tree lines as of drafting
     time — locate bullets by lead text if they have drifted by the time dev starts. -->

## Story

As a maintainer of the Skillars platform,

I want ConfigService's concurrent-refresh paths hardened, the recap/plan-note/timeline lifecycle bugs around
session completion fixed, three frontend request-robustness gaps closed, two CI gate scripts hardened, a
missing CHECK constraint added, the dead `voice_note_text` column's last code reader removed, and the
orphaned pre-12.7 voice-note uploads cleaned up,

so that a DB outage does not wedge config readers indefinitely, a coach never loses typed wrap-up text or
sees a mis-dated or unsanitised recap, prep and plan-note flows never show a misleading booking-status error
or a carried-over note from the wrong session, the coach-player workspace degrades gracefully instead of
hanging forever, a CI failure is never masked by a swallowed test-load error or a mis-scoped log parse,
`user.verification_status` cannot silently take an invalid value, the path to finally dropping
`voice_note_text` is unblocked, and storage stops holding onto audio blobs nothing references.

## Owner decisions (2026-10-26)

- **D163-A, scope: one bundled story, not split.** All 8 groups below ship together, matching the
  established "bundle small unrelated items" pattern from prior deferred-* stories.
- **D163-B, orphaned uploads (AC8): a one-off cleanup script/job, run once.** Not an ongoing scheduled
  mechanism — story 12.7 already stopped creating new ones, so there is nothing to sweep going forward.

### Explicitly out of scope (do not re-pick)

- **`ProjectedRevenueService` ignoring variable session pricing** (ledger line ~845) — the ledger itself
  says "revisit in a pricing-model story"; needs its own story, not a bundle item.
- **No index for `main.user`'s cleanup-sweep predicate** (ledger line ~2186) — blocked twice already
  (deferred-125, considered and dropped again at deferred-161) pending production row-count/`EXPLAIN`
  evidence. Still no production deployment exists. Do not guess an index shape.
- **"Frontend unit-test job isn't merge-gating"** (ledger lines ~1749-1768, ~2643-2651) — an
  owner-reaffirmed **accepted design** (opt-in auto-detection via `detect-frontend-changes.sh`, decided at
  deferred-129). Not an open defect; do not relitigate.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets live in `_bmad-output/implementation-artifacts/deferred-work.md`. On completion the dev tags each
with `[PICKED UP by skillars-deferred-163 ACn]` and then follows AC9's hygiene step.

| AC | Ledger bullet (lead text) | Section | Line |
|----|---------------------------|---------|------|
| 1 | "Must-be-fresh waiters have no wait bound…" | code review of skillars-deferred-161 (2026-10-25) | 3250 |
| 1 | "`scheduledRefresh()` can park the shared `@Scheduled` thread." | code review of skillars-deferred-161 (2026-10-25) | 3251 |
| 1 | "`invalidate()` is per-JVM and not deferred to after-commit." | code review of skillars-deferred-161 (2026-10-25) | 3252 |
| 1 | "`load()` `putAll`+`retainAll` is not an atomic swap." | code review of skillars-deferred-161 (2026-10-25) | 3253 |
| 1 | "`refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409…" | code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11) | 909 |
| 2 | "Sweeper-vs-resume race loses typed wrap-up text." | code review of skillars-12-7-recap-lifecycle (2026-10-22) | 3214 |
| 2 | "Recap text is stored and reported raw (unsanitised)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3209 |
| 2 | "QUICK-mode drill snapshot reflects plan at parent confirmation." | code review of skillars-12-8-timeline-enrichment (2026-10-23) | 3218 |
| 3 | "`findLatestWhatsNextSuggestion` is not time-bounded…" | code review of skillars-12-6-session-prep-completeness (2026-10-22) | 3198 |
| 3 | "Recovered recaps are timestamped at write time (Open Question 3)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3207 |
| 3 | "`SessionPlanService.isBookingPlannable` accepts only `CONFIRMED`." | dev of skillars-12-6-session-prep-completeness (2026-10-22) | 3192 |
| 3 | "`setPlayerRequest` still uses bare `Instant.now()`…" | dev of skillars-12-6-session-prep-completeness (2026-10-22) | 3193 |
| 4 | "Radar-preference flush on `pagehide` may be cancelled." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3222 |
| 4 | "Radar-preference PUTs are unordered." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3223 |
| 4 | "`fetchCoachTier` has no timeout." | code review of skillars-12-9-coach-player-workspace-parity (2026-10-23) | 3224 |
| 5 | "Convention-test class loading swallows failures." | code review of skillars-deferred-160 (2026-10-24) | 3236 |
| 5 | "Gate evaluates the last statistics line of the whole build log." | code review of skillars-deferred-160 (2026-10-24) | 3237 |
| 6 | "D2: `verification_status` unconstrained `VARCHAR(20)`…" | code review of skillars-1-3 Group A (2026-06-11) | 893 |
| 7 | "Drop `booking.session_completion_data.voice_note_text` (contract migration)." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3205 |
| 8 | "Orphaned voice-note audio uploads." | dev of skillars-12-7-recap-lifecycle (2026-10-22) | 3206 |

Not picked up (left for its own story, see "Explicitly out of scope" above): the recap-attendance-on-recovery
Open Question at line 3208 is a related but distinct feature (coach confirms attendance on a recovered
recap) and is **not** part of this story.

## Verified state at drafting (HEAD 0f089e5b)

Paths are relative to `src/main/java/com/softropic/skillars/` unless prefixed `src/frontend/` or
`.github/`. Re-verify every citation against HEAD at dev time — ledger line numbers have historically
drifted by the time a story is picked up.

**AC1, ConfigService hardening** (`platform/config/service/ConfigService.java`)
- Fields: `cache`:47, `lastRefreshed`:48, `invalidationEpoch`:54, `loadedEpoch`:55, `refreshLock` (plain
  `ReentrantLock`):56.
- `scheduledRefresh()`:73-76 — just calls `refreshCache()`, no special-casing for a busy lock.
- `invalidate()`:380-382 — only increments an `AtomicLong`; no DB/transactional interaction today.
- `refreshCache()`:416-442 — single-flight already works (post-deferred-161); the must-be-fresh waiter path
  is the plain, uninterruptible `refreshLock.lock()` at **line 439**.
- `load()`:449-460 — `cache.putAll(newData)` (455) then `cache.keySet().retainAll(newData.keySet())` (456); a
  reader can observe a cross-key mix mid-load.
- **The old line-909 "409" claim is stale against current code — do not re-assert a 409 in the AC.**
  `ApiAdvice.java:643-648` maps `IllegalStateException` → **500** (deliberately, since skillars-deferred-91
  AC17 — see its javadoc at `:626-641`), and the generic `@ExceptionHandler(Throwable.class)` fallback
  (`ApiAdvice.java:127-132`) also returns 500. A must-be-fresh load failure during a DB outage surfaces as
  500 today, not 409. AC1's fix is "bounded wait instead of an indefinite hang, still surfacing as 500 on
  genuine failure," and it supersedes/closes the old line-909 note.
- Tests: `ConfigServiceTest.java` already has an extensive concurrency suite.
  `mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt` (~660-702) asserts the waiter thread
  reaches `Thread.State.WAITING` on the exact uninterruptible `lock()` call AC1 must change — **this test
  needs rewriting to match bounded-wait semantics, not just extending.** `mustBeFreshRefreshFailure_stillPropagates`
  (718-729) and `ttlOnlyRefreshFailure_servesTheCachedSnapshot_andRetriesNextRead` (704-716) pin
  failure-propagation behavior AC1 must preserve. No IT covers this path (`ConfigResourceIT.java` is
  REST-level only).
- **After-commit deferral precedent already exists** (not first-of-a-kind in this codebase):
  `EmailRetryScheduler.java:147-150` and `OutboxService.java:78-86` both use
  `TransactionSynchronizationManager.registerSynchronization(...).afterCommit()`. Follow
  `EmailRetryScheduler.java`'s javadoc (~line 40) as the pattern for the after-commit deferral.
- **AC1's `invalidate()`-after-commit item is defensive/future-proofing, not a live bug today:** `invalidate()`'s
  only production caller, `updateConfig()` (`ConfigService.java:243,248`), is not `@Transactional`, and its
  only caller `ConfigResource.updateValue()` is not `@Transactional` either.

**AC2, recap lifecycle** (`platform/booking/service/BookingCompletionService.java`, `platform/development`,
`src/frontend/src/components/booking/WrapUpSequence.vue`)
- `submitWrapUp` (:154-230); the MISSING_RIGHTS throw for a swept booking is at **:168-175** (ledger cited
  :157-161 — drifted ~11 lines; reverify at dev time). `updateRecap` (the still-available `PUT /recap` path)
  is at `:378-399`, gated by `recapEditability` (`:339-364`) with a 30-day-default edit window — genuinely
  still usable after a sweep today.
- `WrapUpSequence.vue:296-308`'s `handleSubmitWrapUp` catch only branches on
  `errorKey === 'booking.concurrentModification'` (`:301`); everything else — including the sweeper case —
  falls to the generic `step5SubmitError` toast (`:304`) with the typed text discarded (not reused), even
  though the component already holds the typed text in local refs (`recapWhatWeDid`/`recapWhatsNext`/
  `recapWhatWentWell`).
- `submitWrapUp` (:179-190) stores `req.recapWhatWeDid()`/`recapWhatsNext()`/`recapWhatWentWell()` raw — no
  sanitizer call anywhere in the method. `ReportGenerationService.java:475-482,647-654` renders these
  straight into the PDF, unsanitised. Contrast: `SessionPrepService.java:116-120` already sanitizes **at
  read** (`contactDetailSanitizer.sanitize(raw.toString()).sanitized()`) for the prep-card "carried over"
  display only — the raw/sanitized split is already live and inconsistent. Established call pattern:
  `BookingService.sanitizeAndTruncate(ContactDetailSanitizer, String, int)` static helper
  (`BookingService.java:1124-1128`), used by `setPlayerRequest` (`:771`) and `setCoachPlanNote` (`:811`).
- `SessionPlanService.updateSession` (`:115-158`) only blocks edits when `session.getStatus()` is
  COMPLETED/CANCELLED (`:129-132`) or the booking is terminal (`:138-144`) — a QUICK-mode booking's plan
  stays editable through `BookingCompletedEvent`. `submitWrapUp`'s LIVE branch publishes
  `BookingCompletedEvent` immediately (`:207-218`); QUICK instead goes through `confirmCompletion`
  (`:429-451`), which already reads `scd.getHomeworkDrillIds()` at that later point — there is no equivalent
  snapshot of the session **plan's** drill blocks. `submitWrapUp` already serializes `homeworkDrillIds` at
  `:192-198`, giving a direct precedent to snapshot the plan's drill ids the same way, rather than coupling
  `SessionPlanService` locking into `BookingCompletionService`.
- Tests covering this area today (will need new cases, not rewrites): `BookingCompletionServiceTest.java`,
  `AdminBookingCompletionServiceTest.java`, `WrapUpSequenceSpec.js` (Vitest).

**AC3, plan-note/timeline correctness** (couples with AC2's recap-timestamp item — same root cause, same files)
- `TimelineEventListener.saveTimelineEvent` sets `evt.setOccurredAt(Instant.now())` at **:281**; the
  SESSION_COMPLETED path `writeSessionCompleted` (`:120-136`) separately stamps `Instant.now()` inline at
  **:131** — **both places** need the fix, not just one.
- `PlayerTimelineRepository.findLatestWhatsNextSuggestion` (~`:100-108`, called from
  `SessionPrepService.getPrep:111-112`) is a native query: filters `player_id`, coach metadata match,
  `event_type = 'SESSION_COMPLETED'`, `reference_id <> :bookingId`, non-blank `whatsNext`, ordered
  `occurred_at DESC, id DESC LIMIT 1`. **It has zero time bound relative to the prepped booking.**
  `Booking.requestedStartTime`/`requestedEndTime` (`Booking.java:42,45`) are available as the anchor. The fix
  must bound by the **other** booking's `requestedStartTime` (booking-time-based, via a join/subquery against
  the referenced booking), not by `occurred_at` — `occurred_at` is itself unreliable for recovered/swept
  sessions, which is exactly why this item and the timestamp fix above are coupled: fixing the timestamp
  alone does not add the missing bound, and adding the bound without fixing the timestamp still leaves a
  mislabelled sweep-date on recovered recaps.
- `isBookingPlannable` exists in **two places**: `SessionPlanService.java:242-252` and an identical copy in
  `SessionTemplateService.java:179-187`. Both return `true` only for `"CONFIRMED"`; `"UPCOMING"` explicitly
  logs a warning and returns `false`. `SessionPlanService.createSession` (`:76-86`) throws
  `SessionErrorCode.SESSION_BOOKING_NOT_OWNED` for **two different conditions**: genuine non-ownership
  (`:76-80`) and the plannable-status rejection (`:82-86`) — reusing an ownership-flavored code for a status
  rejection. Confirmed genuinely misleading.
- `BookingService.rejectIfNotEditablePlayerRequest` (`:838-847`) uses bare `Instant.now()` against
  `booking.getRequestedEndTime()`, while `setCoachPlanNote` (`:793-818`) uses
  `Instant.now(ClockProvider.getClock())` and delegates to `Booking.isPrepNoteEditable(now)`
  (`Booking.java:140-144`: `status ∈ {CONFIRMED, UPCOMING} && (requestedEndTime == null ||
  now.isBefore(requestedEndTime))`). `PLAYER_REQUEST_EDITABLE_STATUSES` (`BookingService.java:149`) is the
  same `{CONFIRMED, UPCOMING}` set — `rejectIfNotEditablePlayerRequest` is a duplicate reimplementation of
  `isPrepNoteEditable`, on the wrong clock. `ClockProvider` injection idiom:
  `Instant.now(ClockProvider.getClock())` (confirmed at `SessionPrepService.java:107`,
  `BookingService.java:801`).
- `BookingServiceTest.java:1646-1763` already covers `setPlayerRequest` editability; confirmed no test
  depends on the wall clock specifically.

**AC4, frontend request robustness** (`src/frontend/src/components/development/CoachRadarCard.vue`,
`src/frontend/src/stores/{development,auth}.store.js`)
- `CoachRadarCard.vue`: local `useDebounce` helper (`:63-85`) with a `.flush()` method; `pagehide` listener
  at **:129** (`window.addEventListener('pagehide', flushPendingSave)`); `flushPendingSave` (`:104-106`)
  calls `debouncedSave.flush()`, which runs the normal debounced async fn (`:96-102`) —
  `await store.saveRadarPreferences(...)` → `putRadarPreferences`
  (`src/frontend/src/api/development.api.js:28-29`) → `api.put(...)` through the shared axios instance
  (`src/frontend/src/boot/axios.js`). Confirmed: no `keepalive`/`sendBeacon` anywhere in this flow.
- `saveRadarPreferences` (`stores/development.store.js:273-280`) deliberately does **not** write
  `radarPreferences` back locally (comment explains races), but has **no sequencing guard at all** for the
  PUT — unlike its sibling fetches, which already use a `_seq` pattern (`_radarDisplaySeq` `:223/226`,
  `_radarPreferencesSeq` `:244/250`, `_correlationSeq` `:282/285` — `if (seq === ...)` drops stale
  responses). Extend the identical pattern to saves (e.g. `_radarSaveSeq`). Note: `useDebounce` only tracks
  one pending timer, not in-flight requests — check whether the debounce+flush interaction can even produce
  overlapping in-flight PUTs before over-engineering the guard.
- `fetchCoachTier` (`stores/auth.store.js:34-41`): plain `api.get('/api/marketplace/coaches/me/tier')`, no
  timeout. `coachTier.value = null` in the catch handles a *rejected* request gracefully, but a request that
  never settles leaves the awaiting caller stuck forever. Consumer:
  `pages/coach/CoachPlayerDetailPage.vue` — `tierPromise = authStore.fetchCoachTier()` (`:431`), `await
  tierPromise` then `tierSettled.value = true` (`:386-389`); `tierSettled` gates the radar card, correlation
  insights, and `isCoach`-driven UI (`:220, 329-334`) with no timeout/error messaging if it hangs.
- No app-wide axios timeout exists (`boot/axios.js`; confirmed by existing in-code comments at
  `composables/useSession.js:91` and `layouts/MainLayout.vue:458` that call this out explicitly). Precedent
  for a **per-call** timeout: `api/booking.api.js`'s `getCoachBookingRequests` (`:38-39`) and
  `acceptBooking`/`declineBooking` (`:24-28`), each `{ timeout: 20000 }` with a code comment citing the
  originating deferred-story. `fetchCoachTier` currently bypasses this project's centralized-API-call
  convention (it calls `api.get` directly inside the store instead of through an `api/*.api.js` module) —
  fold it into `auth.api.js` (or wherever the project's auth API module lives) with the same
  `{ timeout: 20000 }` shape as part of this fix, not just adding an inline timeout.
- No existing Vitest specs for `CoachRadarCard.vue` or `fetchCoachTier` — new coverage, not rewrites.

**AC5, CI script hardening** (`src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`,
`.github/scripts/assert-context-count.sh`)
- `IntegrationTestConventionTest.allConcreteItClasses()` (`:163-171`, called by `integrationTestClasses()`
  `:138-144`, which filters out slices and asserts `hasSizeGreaterThan(100)` — "the floor") does
  `Class.forName(cn, false, ...)` wrapped in `catch (Throwable ignored) { ... }` at `:169-171`. A class that
  fails to load for a genuine reason (bad bytecode, missing dependency, broken static init) silently never
  enters the model, invisible to every check built on it — including `distinctContextKeyCountIsPinned`
  (`:252`) and the size-100 floor itself.
- `.github/scripts/assert-context-count.sh`: a single line, `grep -aoE 'size = [0-9]+, maxSize = ...' "$LOG" |
  tail -1`. `pr-build.yml:71` runs one `mvn -B verify 2>&1 | tee build.log` (surefire's `test` phase then
  failsafe's `integration-test` phase, same combined log); the script is invoked at `pr-build.yml:88` as
  `assert-context-count.sh build.log 17`. Zero section-awareness today. Maven emits plugin-execution banners
  that let the fix scope to failsafe only:
  ```
  [INFO] --- maven-surefire-plugin:...:test (default-test) @ skillars ---
  ...
  [INFO] --- maven-failsafe-plugin:...:integration-test (default) @ skillars ---
  ```

**AC6, `verification_status` CHECK constraint**
- Lives on `main."user"` (`V138__baseline_schema.sql:1290`: `verification_status character varying(20)
  DEFAULT 'UNVERIFIED' NOT NULL`, widened to `varchar(255)` by
  `V149__widen_user_skillars_role_verification_status.sql:56`). Entity: `User.java:216-218`,
  `@Enumerated(STRING)`, enum `SkillarsVerificationStatus { UNVERIFIED, EMAIL_VERIFIED, BASIC_VERIFIED,
  SUSPENDED }`. No CHECK exists today (`V149:39` explicitly notes none exists).
- **`main.user_aud` (the Envers audit table) also carries a `verification_status` column** (added by
  `V145__user_aud_role_verification_status.sql`, see `User.java:197-215`'s javadoc on the sibling
  `skillarsRole` field for the full history). **Do not add the CHECK to `user_aud`** — it is a Hibernate/Envers-
  managed audit mirror, not a live business table, and `User.java:202-214`'s javadoc already documents a
  known null-vs-non-null-default mismatch there as an accepted risk (`[DECIDED: accepted risk —
  skillars-deferred-124]`); constraining it would be new, out-of-scope risk surface.
- Precedent template: `V168__envelope_entity_email_template_check_ses_cutover_preflight.sql` — `SET LOCAL
  lock_timeout='5s'; ALTER TABLE ... DROP CONSTRAINT IF EXISTS ..., ADD CONSTRAINT ... CHECK
  ((col::text = ANY (ARRAY[...]::text[])))`, with `-- migration-lint: allow-validating-constraint` (skips
  `NOT VALID`/`VALIDATE` because no production deployment of this application has ever happened — same
  rationale as V168 and V162). Latest migration on disk is **V169**
  (`V169__player_timeline_events_session_completed_unique.sql`), so this story's constraint migration is
  **V170**.

**AC7, drop `voice_note_text` — contract migration, step 1 only**
- Entity field: `SessionCompletionData.java:49-50` (`@Column(name="voice_note_text", length=2000)`).
  `isSweeperPlaceholder`'s null-check: `BookingCompletionService.java:470-475` (includes
  `existing.getVoiceNoteText() == null` in its placeholder test, alongside other field null-checks). Both
  GDPR scrubs are in `SessionCompletionDataRepository.java`: `:53` (`anonymizeByPlayerId`) and `:60`
  (`anonymizeByCoachId`), both nulling `voiceNoteText`. `GdprErasureService.java:413` references the column
  in a comment.
- Per `docs/deployment/migration-conventions.md` rule 2 (`:58-85`), a column is dropped **only in a release
  after** the one that removed every code reference to it — this story is **that earlier release**, not the
  drop itself. No migration SQL ships in this story for this item.

**AC8, orphaned voice-note upload cleanup** (`platform/filestorage`)
- `FileStorageObject` (`platform/filestorage/repo/FileStorageObject.java:23-61`) has no `entity`/`entityId`
  columns — those are embedded only in the storage `key` string via `StorageKeyGenerator.generate()`
  (`.../service/StorageKeyGenerator.java:20-27`): `{entity}/{entityId}/{year}/{month}/{uuid}.{extension}`
  (`.parse()` at `:29` splits it back out). The entity has `contentType`, `tags` (jsonb), `deletedAt`,
  `physicalDeletedAt` — no status field, no existing "unreferenced upload" concept.
- Origin confirmed via `git log -S"audio/webm"`: introduced in commit `97ceb58f` (`WrapUpSequence.vue`),
  removed in `05f81d6d` (skillars-12.7, PR #292). The upload call used `entity: 'booking', entityId:
  props.bookingId, contentType: 'audio/webm', extension: 'webm', tags: {}`. Keys look like
  `booking/{bookingId}/{yyyy}/{MM}/{uuid}.webm`, `content_type = 'audio/webm'`.
- **Discriminator — safe and 100%-precise:** a current-tree grep for `audio/webm` finds **zero** remaining
  producers anywhere in `src/` (only shows up in the historical diff of the two commits above). No other
  feature ever created an `audio/webm` upload, under `entity: 'booking'` or any other entity. So
  `content_type = 'audio/webm' AND key LIKE 'booking/%' AND deleted_at IS NULL` cannot false-positive-match a
  legitimate booking attachment.
- **Correct deletion path — do not call `FileStorageService.softDelete(key, currentUserLogin)`** (it
  enforces `fso.getCreatedBy().equals(currentUserLogin)`, `FileStorageService.java:244-246`, which assumes a
  real logged-in caller a batch job doesn't have). Call `FileStorageObjectRepository.softDeleteByKey(key,
  Instant.now())` (`FileStorageObjectRepository.java:22-25`) directly instead — same soft-delete mechanism,
  stays inside the `platform.filestorage` module boundary.
- **No physical-deletion work needed in this story.** `DeletionSchedulerService.processDeletions()`
  (`.../service/DeletionSchedulerService.java:61-98`) already polls for soft-deleted rows past the retention
  window and performs real S3 deletion + `OutboxReplicationJob` fan-out automatically. The one-off job's only
  job is to soft-delete the matched rows — it must **not** call `deleteRawBytes`/`StorageService` itself.
- Structure precedent: `StorageMigrationService.migrateAll(ownerId)`
  (`.../service/StorageMigrationService.java:39-52`) — a plain `platform.filestorage.service` class, loops a
  repository query, logs progress per item, catches-and-continues per-item failures. Model the new one-off
  job on this shape.

## Acceptance Criteria

1. **ConfigService's concurrent-refresh paths have bounded waits, an atomic cache swap, and an after-commit
   invalidation hook; this supersedes the old line-909 ledger note.**
   1. A must-be-fresh reader that loses `refreshLock.tryLock()` no longer parks in an uninterruptible
      `lock()` (`ConfigService.java:439`). It uses `tryLock(timeout, unit)` with a short, documented timeout,
      and on timeout/failure retries once with a short backoff before rethrowing the last failure — not an
      indefinite hang, and not a silent fallback to stale data for a caller that explicitly asked for fresh.
      Still surfaces as 500 on genuine failure (`ApiAdvice.java:643-648`), never a 409.
   2. `scheduledRefresh()` (`:73-76`) uses `tryLock()` only (non-blocking) and skips the tick (logging at
      DEBUG/INFO) if the lock is busy, so it never parks the shared `@Scheduled` thread behind a concurrent
      must-be-fresh load.
   3. `invalidate()` (`:380-382`) defers its effect to after the caller's transaction commits, following the
      `TransactionSynchronizationManager.registerSynchronization(...).afterCommit()` pattern already used by
      `EmailRetryScheduler.java:147-150`/`OutboxService.java:78-86`. If no transaction is active, it still
      invalidates immediately (current behavior) — this fix is defensive/future-proofing since no current
      caller wraps `updateConfig()` in an outer transaction; say so in Completion Notes.
   4. `load()`'s `putAll`+`retainAll` (`:455-456`) is replaced with a single atomic swap of one immutable map
      via a `volatile` reference, so no reader can ever observe a cross-key mix of old and new values mid-load.
   5. Unit tests: `mustBeFreshReaderParkedBehindAPreInvalidateLoad_reloadsAfterIt` (`ConfigServiceTest.java`
      ~660-702) is rewritten to assert the new bounded-wait behavior (still reloads after the in-flight load
      completes, but is no longer provably parked in `WAITING` on a plain `lock()`).
      `mustBeFreshRefreshFailure_stillPropagates` (718-729) and
      `ttlOnlyRefreshFailure_servesTheCachedSnapshot_andRetriesNextRead` (704-716) still pass unmodified in
      their outcome (failure still propagates / stale TTL read still served), only their mechanism assertions
      may need adjusting. New tests: a timed-out `tryLock` rethrows after the backoff window, not before;
      `scheduledRefresh` skips (not blocks) when the lock is held; `load()` under concurrent readers never
      observes a partial map (a tight loop asserting map size is always either the old or new size, never
      between).

2. **A coach never silently loses typed wrap-up text to the sweeper, and recap text is sanitised at submission.**
   1. `submitWrapUp` (`BookingCompletionService.java:154-230`) throws a **new**, dedicated error (distinct
      from the generic MISSING_RIGHTS path) when the sweeper has already force-completed the booking the
      coach is submitting a resumed wrap-up for — e.g. `BOOKING_RECAP_AUTO_COMPLETED`/
      `booking.recapAutoCompleted` — instead of the existing "can no longer be edited" message, which is now
      inaccurate since `PUT /recap` (`updateRecap`, `:378-399`) can still save it.
   2. `WrapUpSequence.vue`'s `handleSubmitWrapUp` catch (`:296-308`) adds a branch on this new `errorKey`
      that redirects to the recap dialog (`SessionRecapDialog.vue`, confirmed usable post-sweep via
      `recapEditability`), prefilled from the component's own already-held
      `recapWhatWeDid`/`recapWhatsNext`/`recapWhatWentWell` refs — no retyping, no silent data loss.
   3. `submitWrapUp` (`:179-190`) runs `BookingService.sanitizeAndTruncate(ContactDetailSanitizer, String,
      int)` (`BookingService.java:1124-1128`, the same helper `setPlayerRequest`/`setCoachPlanNote` already
      use) on all three recap text fields before `completionDataRepository.save(scd)`. Use the same
      truncation length convention as the existing callers unless the recap field's own DB column length
      dictates otherwise.
   4. The QUICK-mode session plan's drill blocks are **snapshotted into `session_completion_data` at
      `submitWrapUp` time** (QUICK branch), the same way `homeworkDrillIds` is already serialized
      (`:192-198`), rather than locking the `Session` row in `confirmCompletion` — this avoids coupling
      `SessionPlanService` into `BookingCompletionService`. The timeline's "Drills" entry for a QUICK session
      reads from this snapshot, not from the live, still-editable plan.
   5. Tests: `BookingCompletionServiceTest` gains cases for the new error path (sweeper-then-resume) and the
      sanitization call (assert `ContactDetailSanitizer` is invoked, a contact-detail-bearing recap is
      redacted before save). `WrapUpSequenceSpec.js` gains a case for the new `errorKey` branch (redirect +
      prefill). A new or extended IT confirms the QUICK drill snapshot reflects the plan **as of wrap-up**,
      not as of a later edit.

3. **Timeline events carry the real session-end time, and plan-note/prep lookups are booking-time-bounded, not
   ownership-coded.**
   1. Both `TimelineEventListener.saveTimelineEvent` (`:281`) and `writeSessionCompleted`'s inline stamp
      (`:131`) stop using wall-clock `Instant.now()` for `occurredAt` and instead use the booking's actual
      session-end time (e.g. `booking.getRequestedEndTime()`, or the captured session-end instant for a
      QUICK/swept recovery — reuse whatever instant the recap/completion flow already has available at that
      point rather than introducing a new field if one already exists). A recovered/swept event is now
      labelled and ordered by when the session actually happened, not by when the sweeper got to it.
   2. `PlayerTimelineRepository.findLatestWhatsNextSuggestion` (~`:100-108`) gains a bound against the
      **prepped booking's own `requestedStartTime`**: only consider `whatsNext` suggestions from events whose
      referenced booking's `requestedStartTime` is before the prepped booking's `requestedStartTime` (a
      join/subquery against the bookings table via `reference_id`, not a naive `occurred_at <
      requestedStartTime` filter, which would wrongly drop back-to-back sessions). This item and 3.1 are one
      coordinated fix: 3.1 makes `occurredAt` trustworthy for display/ordering ties, 3.2 adds the booking-time
      bound that was always missing.
   3. `isBookingPlannable` (`SessionPlanService.java:242-252` **and** `SessionTemplateService.java:179-187` —
      both copies) accepts `UPCOMING` in addition to `CONFIRMED`. `SessionPlanService.createSession`'s status
      rejection (`:82-86`) throws a **new, distinct** error code instead of reusing
      `SessionErrorCode.SESSION_BOOKING_NOT_OWNED` (e.g. `SESSION_BOOKING_NOT_PLANNABLE`) — genuine
      non-ownership (`:76-80`) keeps its existing code.
   4. `BookingService.rejectIfNotEditablePlayerRequest` (`:838-847`) is rewritten to use
      `Booking.isPrepNoteEditable(Instant.now(ClockProvider.getClock()))` instead of a separate bare-clock
      status+time check, matching `setCoachPlanNote`'s idiom — **but still throws the correct distinct error
      per failure reason** (`BOOKING_NOT_EDITABLE` for a non-editable status, `SESSION_ALREADY_ENDED` for the
      time bound), since `isPrepNoteEditable` collapses both into one boolean; the caller re-derives which
      one failed before throwing.
   5. Tests: `PlayerTimelineRepositoryTest`/IT gains a case proving a later-dated-but-earlier-booking-time
      suggestion is excluded (and vice versa); `SessionPlanServiceTest`/`SessionTemplateServiceTest` gain
      `UPCOMING`-accepted cases and assert the new distinct error code; `BookingServiceTest` (building on
      `:1646-1763`) gains a case proving the two distinct error codes still fire correctly after the
      `isPrepNoteEditable` refactor.

4. **The coach-player workspace's radar save and coach-tier fetch degrade gracefully instead of silently
   losing data or hanging.**
   1. `CoachRadarCard.vue`'s `pagehide` flush (`flushPendingSave`, `:104-106`) uses `fetch(..., { keepalive:
      true })` or `navigator.sendBeacon` for the final save on unload, instead of the normal axios PUT that
      the browser may cancel.
   2. `saveRadarPreferences` (`stores/development.store.js:273-280`) gains a sequencing guard
      (`_radarSaveSeq`, mirroring `_radarDisplaySeq`/`_radarPreferencesSeq`/`_correlationSeq`) so an
      older in-flight save's resolution can never be treated as authoritative once a newer save has been
      issued. Document in Completion Notes whether the debounce+flush interaction can actually produce
      overlapping in-flight PUTs in practice, since `useDebounce` tracks only one pending timer.
   3. `fetchCoachTier` moves into the project's centralized `api/*.api.js` convention (it currently calls
      `api.get` directly inside `stores/auth.store.js:34-41`, bypassing that rule) and gets a scoped
      `{ timeout: 20000 }`, following the exact shape of `api/booking.api.js`'s `getCoachBookingRequests`
      (`:38-39`)/`acceptBooking`/`declineBooking` (`:24-28`). On timeout, `CoachPlayerDetailPage.vue`'s
      `tierSettled` gate (`:386-389`) still resolves (degrading `coachTier` to `null`/not-available) instead
      of hanging forever, with a visible (even if minimal) indication rather than silent permanently-hidden
      tools.
   4. New Vitest specs for `CoachRadarCard.vue` (pagehide flush uses `keepalive`/`sendBeacon`; a stale save
      response is ignored under the new sequencing guard) and for `fetchCoachTier` (times out, resolves
      `tierSettled` regardless) — neither has any existing coverage today.

5. **CI's Spring-context-count gate cannot be fooled by a swallowed class-load failure or a mis-scoped log
   parse.**
   1. `IntegrationTestConventionTest.allConcreteItClasses()` (`:163-171`) collects `(className, Throwable)`
      pairs for any `Class.forName` failure instead of silently swallowing them, and a new assertion (in
      `integrationTestClasses()` or a sibling test method) fails the build if that collection is non-empty.
   2. `.github/scripts/assert-context-count.sh` scopes its `grep ... | tail -1` to the section of the log
      **after** the `maven-failsafe-plugin` `integration-test` execution banner (e.g. via `sed -n
      '/maven-failsafe-plugin.*integration-test/,$p' "$LOG" | grep ... | tail -1`, or an equivalent `awk`),
      so a surefire stats line from before a failsafe crash is never mistaken for failsafe's own ceiling
      check.
   3. Both changes are verified against a real `build.log` (or a crafted fixture reproducing a failsafe
      crash before its stats line) rather than only read-reviewed, since this script has no existing test
      harness of its own.

6. **`main."user".verification_status` has a CHECK constraint matching its enum.**
   1. New migration `V170` adds `CHECK ((verification_status)::text = ANY ((ARRAY['UNVERIFIED',
      'EMAIL_VERIFIED', 'BASIC_VERIFIED', 'SUSPENDED'])::text[]))` on `main."user"`, following
      `V168__envelope_entity_email_template_check_ses_cutover_preflight.sql`'s exact shape: `SET LOCAL
      lock_timeout = '5s';` then `ALTER TABLE ... DROP CONSTRAINT IF EXISTS ..., ADD CONSTRAINT ... CHECK
      (...)`, with a `-- migration-lint: allow-validating-constraint <reason>` marker (no production
      deployment exists yet — same rationale as V168/V162) so the `NOT VALID`/`VALIDATE` split is skipped.
   2. `main.user_aud` (the Envers audit mirror) is **not** touched by this migration.
   3. `MigrationConventionLintTest` passes with the new migration in place.
   4. A unit or IT-level test attempts an invalid `verification_status` value via direct SQL and confirms the
      CHECK rejects it (follow whatever test pattern `EnvelopeEntitySchemaIT` or a similar existing
      CHECK-parity test uses, if one exists for V168's constraint, as a model).

7. **`isSweeperPlaceholder` no longer reads `voice_note_text` — step 1 of the contract migration to eventually
   drop the column. No migration SQL ships in this story for this item.**
   1. `BookingCompletionService.isSweeperPlaceholder`'s (`:470-475`) placeholder test no longer includes
      `existing.getVoiceNoteText() == null` in its condition — placeholder detection relies solely on its
      other existing null-checks.
   2. The `SessionCompletionData` entity field, both GDPR scrub references
      (`SessionCompletionDataRepository.java:53,60`), and the `GdprErasureService.java:413` comment are all
      **left untouched** — they remain the documented safety net for legacy rows holding real coach-typed
      text until the column is actually dropped in a later release.
   3. A new ledger bullet records that the actual `DROP COLUMN voice_note_text` (with its
      `-- migration-lint: drop-prepared-in: <this story's PR/merge>` marker, removing the entity field and
      both scrub references in the same change) can ship starting the release after this story merges, since
      this is now the release that removed the last non-scrub code reference.

8. **Orphaned pre-12.7 voice-note audio uploads are found and soft-deleted once.**
   1. A new one-off service method in `platform.filestorage` (modeled on
      `StorageMigrationService.migrateAll`'s shape) queries `FileStorageObjectRepository` for rows matching
      `content_type = 'audio/webm' AND key LIKE 'booking/%' AND deleted_at IS NULL` (add the derived/native
      query method; none exists today) and calls `FileStorageObjectRepository.softDeleteByKey(key,
      Instant.now())` on each match — **never** `FileStorageService.softDelete(...)` (wrong ownership
      assumption) and **never** a direct `StorageService`/blobstore call.
   2. The method logs a count before deleting (dry-run visibility) and logs each deleted key, continuing past
      a single-row failure rather than aborting the whole batch.
   3. It is triggered once — the dev's choice of a temporary admin-only endpoint, a one-off test/manual
      runner, or equivalent — **not** a recurring `@Scheduled` job, per D163-A. State in Completion Notes how
      it was actually invoked and confirm the result (how many rows matched/deleted) in the target
      environment(s).
   4. Existing `DeletionSchedulerService.processDeletions()` handles the actual physical S3 deletion after
      the retention window automatically — no change needed there.
   5. Unit/IT test proving the discriminator only matches `audio/webm` + `booking/%` rows and leaves other
      booking-entity files (e.g. any legitimate attachment) untouched.

9. **Ledger hygiene and sprint status.**
   1. Delete the 19 bullets closed by code in this story (all rows in the mapping table above except the
      recap-attendance item at line 3208, which is explicitly not picked up). If a section ends up with no
      bullets, remove its heading.
   2. Add the new bullets this story generates: AC7.3 (voice_note_text DROP follow-up), plus any other
      narrower out-of-scope findings surfaced during implementation, under a new `## Deferred from:
      skillars-deferred-163 (<date>)` heading.
   3. Set the `sprint-status.yaml` entry for this story to `review` when dev is complete.

## Tasks / Subtasks

- [ ] Task 1: ConfigService hardening (AC: 1)
  - [ ] Bounded `tryLock` + backoff for must-be-fresh waiters; rewrite the affected `ConfigServiceTest` case
  - [ ] `scheduledRefresh` → `tryLock()`-only, skip-if-busy
  - [ ] `invalidate()` after-commit deferral, following `EmailRetryScheduler`'s pattern
  - [ ] Atomic `volatile` map swap in `load()`
  - [ ] New concurrency tests; confirm old line-909 ledger note is superseded
- [ ] Task 2: Recap sweeper race, sanitization, QUICK drill snapshot (AC: 2)
  - [ ] New dedicated error key + `WrapUpSequence.vue` redirect/prefill on sweeper race
  - [ ] `ContactDetailSanitizer` call on all three recap fields at `submitWrapUp`
  - [ ] Snapshot QUICK-mode plan drill ids into `session_completion_data` at wrap-up
  - [ ] Unit/IT/Vitest coverage
- [ ] Task 3: Timeline timestamp + plan-note/prep bounding (AC: 3) — coordinate with Task 2's recap work; same files
  - [ ] Fix both `occurredAt` stamp sites to use session-end time, not wall clock
  - [ ] Booking-time-bounded `findLatestWhatsNextSuggestion`
  - [ ] `isBookingPlannable` accepts `UPCOMING` (both copies); new distinct error code for status rejection
  - [ ] `setPlayerRequest` routed through `isPrepNoteEditable`/`ClockProvider`, preserving distinct error codes
  - [ ] Tests for all of the above
- [ ] Task 4: Frontend request robustness (AC: 4)
  - [ ] `pagehide` flush via `keepalive`/`sendBeacon`
  - [ ] `_radarSaveSeq` ordering guard
  - [ ] `fetchCoachTier` into `api/*.api.js` convention with scoped timeout
  - [ ] New Vitest specs
- [ ] Task 5: CI script hardening (AC: 5)
  - [ ] Convention test collects and asserts no class-load failures
  - [ ] `assert-context-count.sh` scoped to the failsafe log section
  - [ ] Verify both against a real or crafted log
- [ ] Task 6: `verification_status` CHECK constraint (AC: 6)
  - [ ] V170 migration, modeled on V168; confirm `user_aud` untouched
  - [ ] `MigrationConventionLintTest` passes; new constraint-rejection test
- [ ] Task 7: `voice_note_text` step 1 (AC: 7)
  - [ ] Remove the field from `isSweeperPlaceholder`'s condition only
  - [ ] Confirm scrubs and entity field untouched; record the follow-up ledger bullet
- [ ] Task 8: Orphaned upload cleanup (AC: 8)
  - [ ] New repository query + one-off service method using `softDeleteByKey`
  - [ ] Trigger once; log counts; test the discriminator
- [ ] Task 9: Ledger hygiene and sprint status (AC: 9)

## Dev Notes

- **Do not run `mvn verify` locally** (project rule); GitHub CI is the gate. Targeted unit tests and single
  ITs are fine.
- **Spring test-context gate:** do not add `@MockitoBean`/`@MockitoSpyBean`/`@TestPropertySource` to any IT
  for this story if it can be avoided — none of the 8 ACs above should need a new Spring context (ConfigService
  and migration-lint tests are plain unit tests; filestorage/CI items can reuse existing IT bases). If a new
  context genuinely can't be avoided, read `.github/scripts/assert-context-count.sh`'s own comment history
  before touching the ceiling.
- **IT writes:** bare `jdbcTemplate` writes in an IT method body never commit (Hikari `auto-commit=false`);
  wrap them in `transactionTemplate` if the orphaned-upload test or any new fixture needs direct inserts.
- **Booking fixture CHECK** `chk_bkg_end_after_start`: derive any new booking fixture's start and end from one
  `Instant.now()` call, not two separate calls (a known CI-only flake source).
- **Prettier gate** on frontend files (`.github/actions/`, not `workflows/`): run `npx prettier --check` on
  every touched `.vue`/`.js`/`.json` before pushing.
- **i18n:** any new user-facing error key (AC2.1/2.2, AC3.3/3.4) needs `en-US`, `de-DE`, `fr-FR` entries;
  **de-DE uses the formal register (`Sie`)**.
- **Re-verify every cited line against HEAD at dev time.** All citations above come from a research pass
  against HEAD 0f089e5b; historically about 1 in 3 ledger items has already drifted or partially closed by
  the time a story is picked up.
- AC1–AC3 all touch adjacent areas of `booking`/`development` — read the current state of
  `TimelineEventListener`, `SessionPlanService`, and `BookingCompletionService` fully before starting, since
  AC2's QUICK snapshot, AC3's timestamp fix, and AC3's `isPrepNoteEditable` refactor all touch overlapping
  code paths.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (bullets in the mapping table)
- `docs/deployment/migration-conventions.md` (rule 2: drop-last-and-guarded; rule 3: CHECK validate-in-two-steps/opt-out)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java`
- `src/main/java/com/softropic/skillars/infrastructure/**/EmailRetryScheduler.java`, `OutboxService.java` (after-commit precedent)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`, `BookingService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/{SessionPlanService,SessionTemplateService}.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java`, `repo/PlayerTimelineRepository.java`
- `src/frontend/src/components/booking/WrapUpSequence.vue`, `components/development/CoachRadarCard.vue`
- `src/frontend/src/stores/{development,auth}.store.js`, `api/{development,booking,auth}.api.js`
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java`
- `.github/scripts/assert-context-count.sh`, `.github/workflows/pr-build.yml`
- `src/main/resources/db/migration/V168__envelope_entity_email_template_check_ses_cutover_preflight.sql` (CHECK precedent)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java` (verification_status/user_aud history)
- `src/main/java/com/softropic/skillars/platform/filestorage/{repo/FileStorageObject,repo/FileStorageObjectRepository,service/FileStorageService,service/StorageKeyGenerator,service/StorageMigrationService,service/DeletionSchedulerService}.java`

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
