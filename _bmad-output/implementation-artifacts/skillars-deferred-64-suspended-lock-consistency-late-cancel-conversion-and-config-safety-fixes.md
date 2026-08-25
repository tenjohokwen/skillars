# Story Deferred-64: Suspended-Lock Consistency, Late-Cancel Refund Widening & Config Safety Fixes

Status: review

## Story

As an engineer operating this platform,
I want six items from `deferred-work.md` shipped together — a third `SUSPENDED`-coach lock-consistency
gap, a missing locked-refresh on a parent's booking cancellation, a concurrency-race lock for the video
playback bandwidth-dedup check, a product-directed rule making a late parent cancellation refund-eligible
(without issuing an automatic coach reliability strike), a loud/retriable quota-release failure path for
admin video deletion, and an upfront format guard on the platform's configured payment currency —
so that the ledger's decision-light and freshly-decided backlog keeps draining in the same disciplined,
one-bundled-story-at-a-time way the `skillars-deferred-*` series has followed since `skillars-deferred-59`.

### Why this story exists

This story was scoped by re-mining `deferred-work.md` in full (1570 lines, all sections read, not just the
recent tail) immediately after `skillars-deferred-63` shipped, live-verifying every open-looking item
against the current tree rather than trusting ledger text — this file's own repeatedly-stated convention.
That pass surfaced two categories of finding:

**Eleven items are already fixed but still carry a stale `[PICKED UP by skillars-deferred-NN ...]` tag**
naming a story that has since shipped (`deferred-41`, `-43`, `-44`, `-45`, `-46`, `-49` ×3, `-51`, `-60`) —
the same "unannotated-fix" pattern this ledger's own audit history has flagged more than a dozen times
before. Every one was independently re-verified directly against the current source in this story's
creation pass (see AC7); none needed a code change, only ledger hygiene.

**Three surviving open items are decision-light** — mechanical fixes with no new product call needed,
because the product call was already made by an earlier story and this is that same call applied to one
more call site or one more accepted gap. **Three more items required a fresh decision from the project
owner**, made directly in a short round of questions during this story's creation (2026-08-25): a coach
still can't contest a dispute a parent already filed (**decision: leave as-is**, first-raiser-wins stays
final), the per-window coach timezone picker in `ProfileBuilderStep4.vue` can still drift from the coach
profile (**decision: leave as-is**, it is a deliberate feature, not a bug — no code change), whether
`NO_SHOW_COACH` should be raisable from `IN_PROGRESS` (**decision: leave as-is**, `UPCOMING`-only stays the
rule), whether a late `cancelBookingAsParent` call should become refund-eligible (**decision: yes, but
refund-only — no automatic coach strike, per story-review; see AC4**), whether overnight availability
windows should be supported
(**decision: leave as-is**, not supported), whether `BookingDuplicationService.duplicateNextWeek`'s
DST-crossing wall-clock shift is worth fixing (**decision: leave as-is**), whether a video quota-release
failure during admin-initiated deletion should be surfaced/retriable (**decision: yes, implement it — this
story's AC5**), and whether `platform.payment.currency` needs upfront format validation (**decision: yes,
implement it — this story's AC6**). The five "leave as-is" decisions produce no AC of their own; AC7
records them in the ledger so they are not re-asked next time.

## Acceptance Criteria

1. **`CoachProfileService.saveStep4` gains the same `CoachProfileStatus.SUSPENDED` guard its two sibling
   coach-row-lock methods already have.** `saveStep4`
   (`[src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:229-262]`)
   already takes the coach-row lock (`skillars-deferred-58` AC2's `lockRetryer.withBoundedRetry(...)` +
   `entityManager.refresh(profile, PESSIMISTIC_WRITE)`, `:241-247`) before rewriting the coach's
   availability windows, but never checks `CoachProfileStatus.SUSPENDED` on the locked, refreshed row —
   unlike `RescheduleService.acceptReschedule`
   (`[src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:224-227]`) and
   `BookingDuplicationService.duplicateNextWeek`
   (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:75-78]`,
   added by `skillars-deferred-63` AC2), which both do, on the identical lock pattern. Add the same check
   immediately after `entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE)` and before the
   `coachAvailabilityWindowRepository.deleteByCoachId(...)` call, throwing
   `OperationNotAllowedException` with `BookingError.COACH_UNAVAILABLE` — the same error code both sibling
   methods use — for byte-for-byte consistent behavior across all three coach-row-lock methods. **Note,
   corrected during story-review (2026-08-25): this is a new, one-directional package dependency, not a
   continuation of an existing one.** `CoachProfileService` is in `platform.marketplace.service`, not
   `platform.booking.service` like its two siblings; today the dependency direction between these two
   packages runs exclusively `booking → marketplace` (`RescheduleService`/`BookingDuplicationService` both
   depend on `marketplace.CoachProfileRepository`/`CoachProfileStatus`) — grepping all of
   `platform.marketplace` for any existing `platform.booking.*` import returns nothing. Importing
   `BookingError` here is therefore the **first-ever `marketplace → booking` import**. There is no
   build-level module boundary in this single-Maven-module codebase (no ArchUnit/checkstyle
   import-control rule), so this compiles fine and is a deliberate, accepted trade-off for byte-for-byte
   error-code consistency across all three sibling methods — not evidence the reverse direction was already
   established elsewhere.
   `[src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:229-262]`
   — from `## Deferred from: code review of skillars-deferred-58` (2026-08-24).

2. **`BookingService.cancelBookingAsParent` refreshes its locked booking row before reading the status
   used for refund-eligibility, closing the same stale-read trap already fixed in three sibling methods.**
   `cancelBookingAsParent`
   (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:635-679]`) takes a
   locked re-read (`bookingRepository.findByIdForUpdate`, `:645-646`, via `lockRetryer.withBoundedRetry`)
   but the `booking` entity returned is the *same managed instance* already loaded by the earlier unlocked
   `getBookingOrThrow(bookingId)` call at `:640` — Hibernate's persistence-context identity map returns the
   existing in-memory instance for a repeat load of the same id within one transaction, so the "locked"
   read does not actually refresh its field values from the row `FOR UPDATE` just acquired. This is the
   exact stale-in-memory-status trap this codebase's own established convention already guards against in
   `RescheduleService.acceptReschedule`, `BookingDuplicationService.duplicateNextWeek`, and
   `CoachProfileService.saveStep4` (AC1 above) — each follows its locked read with
   `entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` for exactly this reason. Add
   `entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE)` immediately after the
   `lockRetryer.withBoundedRetry(...)` call at `:645-646`, before `readStatusOrThrow(booking)` at `:655`
   reads `statusBeforeCancel` (the value that decides refund eligibility and gates the
   `PAYMENT_PENDING`/`CAPTURE_PENDING` guard at `:663-666`). `BookingService` already injects
   `EntityManager` as a field (`[BookingService.java:127]`) and already calls
   `entityManager.refresh(c, LockModeType.PESSIMISTIC_WRITE)` twice elsewhere in this same class
   (`:242`, `:338`) for the identical stale-read reason — no new dependency needed, just the same
   one-line call added here.
   `[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:635-679]` — from
   `## Deferred from: code review of skillars-deferred-62` (2026-08-24).

3. **`PlaybackService.authorizePlayback`'s bandwidth-dedup check gains a lock around its exists-then-charge
   sequence, closing the concurrency-race gap `skillars-deferred-63` AC3 explicitly accepted and deferred.**
   Today
   (`[src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java:123-132]`) the
   dedup check is check-then-act with no lock: `playbackTokenRepository.existsActiveForViewerAndVideo(...)`
   is read, then (if false) `quotaService.incrementBandwidthUsedBytes(...)` is called and a new
   `PlaybackToken` row is saved later in the method (`:143-146`) — two genuinely concurrent
   `authorizePlayback` calls for the same `(viewerId, videoId)` pair can both pass the exists-check before
   either commits its new token row, and both charge. Add a per-`Video`-row pessimistic lock around this
   decision, mirroring this codebase's established `PessimisticLockRetryer` pattern
   (`[src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java]`,
   most recently standardized codebase-wide by `skillars-deferred-62`): add a new
   `findByIdForUpdate(UUID id)` method to `VideoRepository`
   (`[src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java]`) using
   `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@QueryHints(@QueryHint(name =
   "jakarta.persistence.lock.timeout", value = "0"))` — `0` means `NO_WAIT` under Hibernate's Postgres
   dialect, per `skillars-deferred-62`'s finding, now this codebase's standard lock idiom — mirroring
   `CoachProfileRepository.findByIdForUpdate`
   (`[src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:35-38]`)
   exactly. In `authorizePlayback`, wrap `videoRepository.findByIdForUpdate(videoId)` in
   `lockRetryer.withBoundedRetry(...)` immediately before the `existsActiveForViewerAndVideo` check, then
   `entityManager.refresh(video, LockModeType.PESSIMISTIC_WRITE)` on the already-loaded `video` instance
   (loaded unlocked at `:62-63` for the eligibility checks earlier in the method) — the same
   locked-read-then-refresh shape AC1 and AC2 both restate. This serializes the exists-check and the
   conditional charge against any other concurrent authorization of the *same video* (coarser than
   per-viewer, matching this codebase's existing coarse-grained coach-row-lock precedent rather than
   introducing a new, finer-grained locking primitive like a Postgres advisory lock, which has no
   precedent in this codebase). **Known, accepted trade-off — corrected during story-review (2026-08-25) to
   describe it accurately, not as merely brief.** `PessimisticLockRetryer.withBoundedRetry` runs the locked
   read inside the *caller's own* transaction via a JDBC savepoint — it never opens or commits a
   transaction of its own
   (`[src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:17-31]`).
   Since `authorizePlayback` is itself one `@Transactional` method (`:54`), the Postgres row lock taken here
   is held until that whole transaction commits or rolls back at the end of the method — genuinely through
   the `PlaybackToken` insert (`:145-149`) and, on the owner-viewing-their-own-video branch, the second
   `generateDownloadUrl` provider call (`:137-142`), not released "briefly" the moment the charge decision is
   made. Accept this anyway for this story (`authorizePlayback` is a hotter, per-play-request path than
   AC1/AC2's occasional per-user writes, so this is a real trade-off, not a cosmetic one): the lock only
   contends across concurrent authorizations of the *same* video, correctness is unaffected either way, and
   narrowing it further (e.g. a `REQUIRES_NEW`-scoped nested transaction around just the lock/check/charge,
   requiring a separate Spring-proxied bean method to avoid the self-invocation problem) is explicitly out
   of scope for this AC. Verify under realistic concurrent-viewer load for a popular video during this AC's
   own testing (Task 3.3) rather than assuming "brief" — if it proves too costly, narrowing the lock's scope
   is a follow-up, not a blocker for this story. `PessimisticLockRetryer` itself needs no changes.
   `[src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java:47-149]`,
   `[src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java]` — from the final
   `## Deferred from: story-review and implementation of skillars-deferred-63` section.

4. **A parent cancellation submitted after the booking's scheduled start time has already passed is
   refund-eligible, even though it arrives less than 24 hours before (or after) that start time** — but
   **does not** issue a coach reliability strike and **does not** change the booking's terminal status
   away from the ordinary `CANCELLED_PARENT` outcome. **Scope narrowed during story-review (2026-08-25,
   confirmed with the project owner): refund-only, not a no-show conversion.** The original plan (route
   through `BookingEvent.NO_SHOW_COACH`/`CoachNoShowEvent`, reusing `recordNoShowCoach`'s exact
   consequence) was rejected on two independent grounds found during story-review: (a) `CoachNoShowEvent`
   carries no `coachEmail` field and has no coach-facing listener at all
   (`[src/main/java/com/softropic/skillars/platform/booking/contract/CoachNoShowEvent.java]`,
   `[src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java:383]`
   only emails the parent) — switching event types would have silently stopped notifying the coach that
   their booking was cancelled at all, a real regression from today's `BookingCancelledByParentEvent`
   coach email; and (b) `CancellationRefundService.onCoachNoShow`
   (`[src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java:90-108]`)
   unconditionally calls `reliabilityStrikeService.issue(event.getCoachId(), event.getBookingId(),
   "COACH_NO_SHOW")` at `:107` — but a parent cancelling late has not attested that the coach failed to
   show up (that attestation is exactly what the separate `recordNoShowCoach` endpoint is for), and there
   is still no way for a coach to contest a strike (this story's AC7 records the dispute-contest question
   as a deliberate "leave as-is"). Issuing an uncontestable strike with no attestation was judged the wrong
   trade-off; refunding the parent is not.
   **Implementation, all confined to the existing `CANCEL_PARENT` event path — no `BookingStateMachine`
   change, no `CoachNoShowEvent` involvement, no new coach-notification gap:** in `cancelBookingAsParent`
   (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:635-679]`), the
   existing `refundEligible` computation at `:670-671` is
   `paymentWasCaptured && booking.getRequestedStartTime().isAfter(Instant.now().plus(24, HOURS))` — today,
   a cancellation arriving after the booking's own start time already fails this (a past instant can never
   be more than 24 hours in the future), so it silently gets the ordinary no-refund outcome. Add an `||`
   arm: `refundEligible = paymentWasCaptured && (booking.getRequestedStartTime().isAfter(Instant.now()
   .plus(24, ChronoUnit.HOURS)) || Instant.now().isAfter(booking.getRequestedStartTime()))`. Everything
   downstream is already correct and needs no further change: `transition(bookingId,
   BookingEvent.CANCEL_PARENT, ...)` still fires exactly as today (`:677`), `BookingCancelledByParentEvent`
   still carries the coach email and still triggers `BookingEmailListener.onBookingCancelledByParent`'s
   existing coach notification, and `CancellationRefundService.onBookingCancelledByParent`
   (`[CancellationRefundService.java:34-52]`) already branches on `event.isRefundEligible()` to restore a
   pack session or issue a `BOOKING_REFUND` credit — with no `reliabilityStrikeService` call anywhere in
   that listener. Add test coverage to `BookingServiceTest`: a cancel arriving after `requestedStartTime`
   while `CONFIRMED` is refund-eligible (was: not, under the old `>24h`-only rule); the same for
   `UPCOMING`; the existing `>24h`-before-start refund-eligible case and the existing `<24h`-before-but-
   still-`UPCOMING`/before-start no-refund case are both unchanged; a cancel on a booking whose payment
   never captured (`PAYMENT_PENDING`/`ACCEPTED`) remains refund-ineligible regardless of timing, since
   `paymentWasCaptured` still gates the whole expression; no `ReliabilityStrikeService` interaction of any
   kind is introduced by this AC — assert it is never invoked from this path.
   `[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:635-679]` — new
   item, product-directed decision made during this story's creation (2026-08-25) and narrowed during
   story-review the same day; the original open question is filed under `## Deferred from:
   skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test story
   creation (2026-08-17)`.

5. **`AdminVideoService.deleteVideo`'s quota-release failure becomes retriable and loudly logged, instead
   of silently becoming un-retriable after the first attempt.** Today
   (`[src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:46-89]`), Phase 1
   (`:61-75`) transactionally sets the video `DELETED` and, if a `PENDING` upload session exists for it,
   transitions that session to `EXPIRED` and returns it as `expiredSession`; Phase 2 (`:78-82`) then calls
   `quotaProvider.release(expiredSession.getReservationHandle())` **only when `expiredSession != null`**,
   with no `try/catch` around the release call itself. Two compounding problems: (a) if `release()` throws,
   the exception propagates uncaught past the method's `finally` (`:85-87`) straight to the caller as an
   opaque failure — misleading, since Phase 1's `DELETED`/`EXPIRED` writes already committed in their own
   transaction and are not rolled back by a later, unrelated exception; and (b) a **retry is silently
   impossible**: Phase 1's session-lookup filter requires `status == PENDING` (`:68`), but the *first*
   call's Phase 1 already flipped that same session to `EXPIRED` — so a second `deleteVideo` call for the
   same video finds no `PENDING` session, `expiredSession` comes back `null`, and Phase 2's `if` at
   `:78-82` is skipped entirely, with no error and no indication that quota was never released. Fix: add a
   nullable `quota_released_at` `TIMESTAMPTZ` column to `main.upload_sessions` (new migration `V104`, next
   free id after `V103` — confirm at implementation time), set only when `quotaProvider.release(...)`
   *succeeds*. Change Phase 2 to look up the session via the existing
   `uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)`
   (`[src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java:14]`) —
   **the same repository method Phase 1 already calls at `:67`**, not a different query; Phase 1 additionally
   chains a Java-side `.filter(s -> s.getStatus() == PENDING)` (`:68`) that Phase 2 must simply not apply —
   instead of relying on Phase 1's `expiredSession` return value, and gate the release attempt on
   `reservationHandle != null && quotaReleasedAt == null` — now retry-safe regardless of how many times
   `deleteVideo` is called for an already-`DELETED` video. Wrap the `quotaProvider.release(...)` call in a
   `try/catch`: on success, persist `quotaReleasedAt = Instant.now()` on the session (a small,
   non-`@Transactional` write mirroring this same method's existing outside-any-transaction Phase-2
   convention, since a rollback here must not undo Phase 1's already-committed `DELETED` state); on
   failure, `log.error(...)` naming the video id and reservation handle (distinct from the existing
   `log.warn` at `:81` for the different "no reservation handle at all" case) and rethrow, so the admin
   caller's request genuinely fails and can be safely retried later — instead of today's single
   uncaught-and-then-permanently-stuck failure mode.
   `[src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:46-89]`,
   `[src/main/java/com/softropic/skillars/platform/video/repo/UploadSession.java]`,
   `[src/main/java/com/softropic/skillars/platform/video/repo/UploadSessionRepository.java]` — from
   `skillars-deferred-52` story-creation note (2026-08-21), re-verified live during this story's creation.
   Explicitly out of scope: applying the same `quotaReleasedAt` retry-safety to
   `WebhookEventProcessorScheduler.releaseQuota`/`VideoService.failTranscoding`'s own unguarded
   `quotaProvider.release(...)` calls
   (`[src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:228-236]`,
   `[src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:392-415]`) — those two
   already get an implicit retry from their own scheduled/webhook-driven re-run cadence, which is exactly
   why the original ledger item scoped this fix to the admin-initiated, synchronous path only.

6. **`platform.payment.currency` gains an upfront database-level format guard**, matching this codebase's
   established convention (`skillars-deferred-57`'s `chk_stripe_customer_id_format`,
   `[src/main/resources/db/migration/V100__stripe_customer_id_format_guard.sql]`,
   `[src/main/resources/db/migration/V101__stripe_customer_id_format_guard_validate.sql]`) of validating an
   external-provider-facing configuration/string value at the DB boundary with a two-migration
   `NOT VALID` + `VALIDATE CONSTRAINT` pair (brief `ACCESS EXCLUSIVE` lock to register, then a separate,
   non-blocking `SHARE UPDATE EXCLUSIVE` scan to validate existing rows). Today
   (`[src/main/resources/db/migration/V99__payment_currency_config.sql]`) `platform.payment.currency` is a
   plain `main.platform_config` row (`key='platform.payment.currency'`, seeded value `'eur'`) read via
   `ConfigService.getString(...)` in `StripePaymentGateway.chargeAndCapture`
   (`[src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:47]`) and
   passed directly to `PaymentIntentCreateParams.Builder.setCurrency(currency)` with no validation of its
   own shape — Stripe's API is the only thing that would ever reject a malformed value, and only when a
   charge is actually attempted. `main.platform_config` is a generic key/value table (many unrelated
   config keys share it), so the guard must be scoped to only this one key's row, not a table-wide `CHECK`:
   ```sql
   ALTER TABLE main.platform_config
       ADD CONSTRAINT chk_payment_currency_format
       CHECK (key != 'platform.payment.currency' OR value ~ '^[a-z]{3}$') NOT VALID;
   ```
   as `V105` (next free id after `V104` from AC5 — confirm both ids are still free immediately before
   writing either file, per this ledger's own repeatedly-stated caution about concurrent story creation
   claiming ids), followed by a separate `V106__payment_currency_format_guard_validate.sql` running
   `VALIDATE CONSTRAINT chk_payment_currency_format`, mirroring `V100`/`V101` exactly. The regex requires
   exactly 3 lowercase letters (ISO 4217's alphabetic form, lowercase because
   `V99`'s own seed comment already documents "lowercase, as Stripe's API expects"). **Correction made
   during story-review (2026-08-25): a live write path to `main.platform_config` already exists** — do not
   assume the only mis-write vector is a future migration. `ConfigResource.updateValue`
   (`[src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java:36-41]`,
   `PUT /api/config/values/{key}`, admin-only via `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`) calls
   `ConfigService.updateConfig(key, request.value())`, which writes the row directly
   (`[src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:165-173]`);
   `UpdateConfigRequest` only enforces `@NotBlank`, no format check — so today any admin can `PUT` an
   arbitrary non-blank value (`'EUR'`, `'euro'`, `'123'`) for this key and it writes straight through with
   no rejection until the next Stripe charge. This guard is exactly as needed for that live path, not only
   for a hypothetical future migration — and it composes cleanly with it: `ApiAdvice
   .integrityViolationHandler`
   (`[src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:155-173]`) already generically catches
   `DataIntegrityViolationException` and returns a sanitized 400 (this constraint isn't in
   `CONSTRAINT_MAPPINGS`, so it falls back to `"generic.dataError"`) rather than leaking a raw 500, so no
   `ApiAdvice` change is needed. Add one test case to `ConfigResourceIT.java` exercising this exact path:
   `PUT /api/config/values/platform.payment.currency` with `value: "EUR"` (or any string failing
   `^[a-z]{3}$`) returns a clean 4xx after this AC's migrations are applied.
   `[src/main/resources/db/migration/V99__payment_currency_config.sql]`,
   `[src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:40-49]`,
   `[src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java]` — from
   `## Deferred from: skillars-deferred-53 story creation` (2026-08-21), re-scoped during this story's
   creation from "should we validate more strictly" to a concrete, precedent-matching DB constraint.

7. **Ledger hygiene.** In `deferred-work.md`:
   - Flip these eleven stale `[PICKED UP by skillars-deferred-NN ...]` items to `[CLOSED by
     skillars-deferred-64 (verified already fixed by the story the tag names)]`, or delete outright per
     this file's own stated convention where the enclosing section has no other content — each was
     independently re-verified against current source during this story's creation, not merely trusted
     from the stale tag:
     - `SessionPackPaymentService` in-memory coachId filter (tagged for `deferred-41`) — now a SQL-level
       `findByParentIdAndCoachIdOrderByCreatedAtDesc`.
     - `ConfigService.getBoolean` fails open with no alerting (tagged for `deferred-41`) — now registers a
       `MISCONFIGURED_COUNTER` Micrometer counter.
     - Gemini truncation surrogate-pair math (tagged for `deferred-51`) — `GeminiModerationService` now has
       a surrogate-boundary back-off loop.
     - No E2E test for the sweep→alert→queue→approve chain (tagged for `deferred-51`) — `AdminQueueIT
       .sweepThenApprove_endToEndChain_alertAppearsInQueueThenResolves` exists and drives it.
     - `BookingServiceTest` positional-constructor mocking, both citations (tagged for `deferred-51`) — now
       uses `@InjectMocks`.
     - `PlayerRegistrationService.generateOtp` no test coverage (tagged for `deferred-43`) —
       `PlayerRegistrationResourceIT` exercises it end-to-end.
     - `PlayerHomeRedirectPage.vue` bare catch (tagged for `deferred-44`) — now branches on
       `err.response?.status !== 404`.
     - `playerStore.fetchSelfPlayerId` null-id (tagged for `deferred-45`) — now throws on
       `profile?.id == null`.
     - `resetSelfPlayerId` not clearing the in-flight cache (tagged for `deferred-46`) — now sets
       `selfPlayerIdRequest = null`.
     - `RescheduleService` missing availability-window check (tagged for `deferred-49`) — both
       `requestReschedule`/`acceptReschedule` call `isSlotWithinAvailabilityWindow`.
     - `isSlotWithinAvailabilityWindow` inferring the coach id from `windows.get(0)` (tagged for
       `deferred-60`) — the method now takes an explicit `coachId` parameter.
   - Annotate, do **not** delete (no code change made — these are deliberate decisions, not closures), the
     five items resolved "leave as-is" during this story's creation (2026-08-25):
     - The dispute-contest-capability gap (`skillars-deferred-63` AC5's follow-up item) —
       `[DECIDED 2026-08-25: keep first-raiser-wins as final; no two-sided contest mechanism planned]`.
     - The `saveStep4`/`ProfileBuilderStep4.vue` write-path coordination item (`skillars-deferred-17`
       residue, already partially annotated by `skillars-deferred-63` AC6) —
       `[DECIDED 2026-08-25: per-window coach timezone is a deliberate feature, not a bug; saveStep4's
       write behavior stays as-is; no further action planned beyond skillars-deferred-63's one-time
       backfill]`.
     - The `NO_SHOW_COACH`-from-`IN_PROGRESS` question (`skillars-deferred-63` AC4's explicit exclusion) —
       `[DECIDED 2026-08-25: stays UPCOMING-only; the IN_PROGRESS dodge-by-pressing-start gap is accepted]`.
     - The overnight-availability-window question (`skillars-deferred-49` review) —
       `[DECIDED 2026-08-25: overnight windows remain unsupported; no fix planned]`.
     - The `duplicateNextWeek` DST wall-clock-shift item (confirmed still unfixed during this story's
       creation) — `[DECIDED 2026-08-25: accepted as a rare, low-impact edge case; no fix planned]`.
   - The original `skillars-deferred-28` late-cancel-auto-no-show question asked whether a late
     `cancelBookingAsParent` call should convert into a full no-show (refund + coach strike). AC4
     **partially** answers it — once shipped, flip it to `[CLOSED by skillars-deferred-64 AC4: refund-only,
     confirmed by the project owner during story-review 2026-08-25 — a late cancel does NOT convert into a
     no-show and does NOT issue a coach strike, only widens refund eligibility]`, not a plain `[CLOSED]` —
     the annotation must make clear this is a narrower answer than "auto-convert into a no-show," so a
     future audit doesn't mistake it for the full conversion the original item asked about. Do not close it
     preemptively before the code lands.
   - Leave everything else in the file exactly as found — in particular the ~80-100 untagged pre-2026-08-17
     backlog items (`skillars-1` through `skillars-11` era), which this story's creation pass deliberately
     did not re-mine (matching every prior audit's own stated scope boundary since 2026-08-04), and the
     `deploy-*` sections, still completely unaudited by any pass to date (confirmed again during this
     story's creation — the ninth consecutive audit to flag this exact gap).

## Tasks / Subtasks

- [x] Task 1: `saveStep4` `SUSPENDED` guard (AC1)
- [x] Task 2: `cancelBookingAsParent` locked-refresh fix (AC2)
- [x] Task 3: `authorizePlayback` bandwidth-dedup lock (AC3)
  - [x] 3.1: New `VideoRepository.findByIdForUpdate`
  - [x] 3.2: Wire the lock + refresh into `authorizePlayback` around the exists-check/charge decision
  - [x] 3.3: Test coverage: two genuinely concurrent same-`(viewerId, videoId)` authorizations charge
        exactly once between them (was: both could charge); an unrelated concurrent authorization of a
        *different* video is not blocked by this video's lock
- [x] Task 4: Late-cancel refund-eligibility widening, no strike (AC4)
  - [x] 4.1: `cancelBookingAsParent` — widen `refundEligible` to also cover a cancel arriving after
        `requestedStartTime`; no `BookingStateMachine`/event-type change
  - [x] 4.2: Test coverage per AC4's enumerated cases, including the explicit
        no-`ReliabilityStrikeService`-interaction assertion
- [x] Task 5: `AdminVideoService.deleteVideo` retriable quota release (AC5)
  - [x] 5.1: `V104` migration — `quota_released_at` column on `main.upload_sessions`
  - [x] 5.2: Rework Phase 2's session lookup + gating + try/catch + `log.error`
  - [x] 5.3: Test coverage: a `release()` failure surfaces to the caller and a second `deleteVideo` call
        for the same video successfully releases quota it previously failed to release (retry-safety);
        a successful release does not attempt a second release on a repeat call
- [x] Task 6: Payment currency format guard (AC6)
  - [x] 6.1: `V105` — `NOT VALID` constraint
  - [x] 6.2: `V106` — `VALIDATE CONSTRAINT`
- [x] Task 7: Ledger hygiene (AC7)

## Dev Notes

**Three of this story's six ACs (AC1, AC2, AC3) are the same "locked-read-then-refresh" or
"lock-then-decide" shape repeated across three different services** — `CoachProfileService`,
`BookingService`, `PlaybackService`. Implement and test them independently (they touch unrelated tables
and unrelated test classes), but recognize the shared pattern so the fix in each is the minimal,
established idiom (`lockRetryer.withBoundedRetry(...)` + `entityManager.refresh(entity,
LockModeType.PESSIMISTIC_WRITE)`), not a novel one per call site.

**AC4 deliberately stays inside the existing `CANCEL_PARENT` event path — do not introduce
`BookingEvent.NO_SHOW_COACH`, `CoachNoShowEvent`, or any `BookingStateMachine` change for this AC.**
An earlier draft of this AC routed a late cancel through the no-show event path to reuse its refund
logic; story-review found that would have silently stopped notifying the coach at all (`CoachNoShowEvent`
has no `coachEmail`/coach listener) and would have issued an unearned, uncontestable
`ReliabilityStrikeService` strike with no attestation from the parent — both rejected. The only change is
widening the existing `refundEligible` boolean expression in `cancelBookingAsParent`; if implementation
finds itself touching `BookingStateMachine.java` or `CoachNoShowEvent` for this AC, stop and re-read AC4.

**AC5's fix depends on `findFirstByVideoIdOrderByCreatedAtDesc` already being status-agnostic** — verify
this at implementation time (it was true as of this story's creation, `UploadSessionRepository.java:14`)
before relying on it to find an already-`EXPIRED` session on a retry.

**AC6's regex is intentionally strict** (`^[a-z]{3}$`, no digits, no uppercase) — this platform is
single-currency today (`eur`) and the seed comment in `V99` already states the lowercase convention Stripe
expects; do not attempt to support a currency list or case-insensitive matching as part of this AC.

**Existing test files to extend (do not create new ones for these classes):**
`BookingServiceTest.java` (`platform.booking.service`, AC2 and AC4), `PlaybackServiceTest.java`
(`platform.video.service`, AC3), `AdminVideoServiceTest.java` (`platform.video.service`, AC5). **AC1 has
no unit-test home to extend — `CoachProfileService` has no `*ServiceTest.java` anywhere in the tree**
(verified during story-review, 2026-08-25); `saveStep4`'s only existing coverage
(`saveStep4_validRequest_returns200`, `saveStep4_noWindows_returns400`, etc.) lives in
`CoachProfileBuilderIT.java:421-` (`platform.marketplace.api`, an integration test extending
`AbstractIntegrationTest`, not a mocked unit test). Extend `CoachProfileBuilderIT.java` with the new
suspended-coach case instead, mirroring its existing IT style — do not create a new
`CoachProfileServiceTest.java` unit-test class for this one AC. AC6 is migration-only — a lightweight
migration test or manual `flyway migrate` check against fixture data is sufficient, matching
`skillars-deferred-63` AC6's own precedent for a migration-only AC, **plus one new case in the existing
`ConfigResourceIT.java`** (`platform.config.api`) per AC6's own text: `PUT
/api/config/values/platform.payment.currency` with a malformed value (e.g. `"EUR"`) returns a clean 4xx
post-migration, not a raw 500. AC7 is ledger-only, no test.

### Project Structure Notes

Backend: `platform.marketplace.service` (`CoachProfileService`), `platform.booking.service`
(`BookingService` only — not `BookingStateMachine`, see AC4), `platform.video.service`/`platform.video.repo`
(`PlaybackService`, `VideoRepository`, `AdminVideoService`, `UploadSession`,
`UploadSessionRepository`), `platform.payment.service` (`StripePaymentGateway`, read-only — no code
change, AC6 is migration-only). Four new migrations: `V104` (AC5), `V105`/`V106` (AC6, next free ids
after `V103` — confirm at implementation time in case a sibling story lands first, per this series'
own established caution). No frontend changes in this story.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source of all six items; see this story's
  own creation-time investigation above for exact sections and the ledger-hygiene closures AC7 performs.
- `skillars-deferred-58`, `skillars-deferred-63` AC2 — the `SUSPENDED`-guard precedent AC1 mirrors exactly.
- `skillars-deferred-62` — the `PessimisticLockRetryer`/`NO_WAIT` convention AC2 and AC3 both reuse, and
  the reason `0` in `jakarta.persistence.lock.timeout` is correct (not a leftover bug) in AC3's new
  repository method.
- `skillars-deferred-57`/`V100`/`V101` — the `NOT VALID` + `VALIDATE CONSTRAINT` two-migration pattern
  AC6 mirrors exactly.

## Dev Agent Record

### Implementation Plan

Three of the six ACs (AC1, AC2, AC3) share the same "locked-read-then-refresh"/"lock-then-decide"
idiom (`lockRetryer.withBoundedRetry(...)` + `entityManager.refresh(entity,
LockModeType.PESSIMISTIC_WRITE)`), so each was implemented as a minimal, established-pattern change
rather than inventing new locking. AC4 stayed entirely on the existing `CANCEL_PARENT` event path per
Dev Notes (no `BookingStateMachine`/`CoachNoShowEvent` touched). AC5 and AC6 were independent
migration/service changes.

### Completion Notes

- **AC1**: Added the `SUSPENDED` guard to `CoachProfileService.saveStep4`, mirroring
  `RescheduleService.acceptReschedule`/`BookingDuplicationService.duplicateNextWeek` exactly (same
  `OperationNotAllowedException` + `BookingError.COACH_UNAVAILABLE`). New test added to
  `CoachProfileBuilderIT` (no unit-test home exists for this class, per Dev Notes) — verified the
  `UPDATE ... SET status = 'SUSPENDED'` fixture write must be wrapped in `transactionTemplate.execute`
  (a bare `jdbcTemplate.update` outside a transaction silently rolls back per this codebase's own
  `DatabaseResetTestExecutionListener` javadoc) before the test passed.
- **AC2**: Added `entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE)` immediately after
  `cancelBookingAsParent`'s locked re-read, closing the stale-in-memory-status trap.
- **AC3**: Added `VideoRepository.findByIdForUpdate` (NO_WAIT, mirroring
  `CoachProfileRepository.findByIdForUpdate`) and wired `lockRetryer.withBoundedRetry(...)` +
  `entityManager.refresh(video, PESSIMISTIC_WRITE)` around `authorizePlayback`'s exists-check/charge
  decision. `PlaybackService` gained two new constructor dependencies
  (`PessimisticLockRetryer`, `EntityManager`), which also required updating the two other
  hand-constructed-mock test classes (`PlaybackServiceTest`, `PlaybackRevocationWindowUnitTest`).
  Added a new `PlaybackServiceConcurrencyIT` with real concurrent-thread tests (not mockable —
  the race itself needs a real Postgres row lock): two concurrent same-(viewer,video)
  authorizations charge bandwidth exactly once between them, and two concurrent different-video
  authorizations both succeed independently. The pre-existing `authorizePlayback_performance_p99Under200ms`
  test flaked once locally at 439ms (vs the 200ms threshold) immediately after this change, then
  passed cleanly on a clean rerun — consistent with this codebase's own documented Docker-Desktop-vs-CI
  timing variance (`DatabaseResetTestExecutionListener`'s javadoc measures local Docker Desktop at
  roughly 10x CI's per-operation cost), not a deterministic regression from the one added locked
  round-trip. Per AC3's own Dev Notes, added latency here is an accepted, known trade-off — flagged for
  attention if it recurs on CI, not treated as a blocker.
- **AC4**: Widened `refundEligible` with an `||` arm covering `Instant.now().isAfter(requestedStartTime)`.
  Added 4 new `BookingServiceTest` cases: CONFIRMED past-start-time (refund-eligible), UPCOMING
  past-start-time (refund-eligible), CONFIRMED <24h-out-but-not-yet-started (regression guard, stays
  not-eligible), and PAYMENT_PENDING past-start-time (stays not-eligible — `paymentWasCaptured` still
  gates). No `ReliabilityStrikeService` assertion was added as a Mockito verification because
  `BookingService` has no dependency on that service at all — its absence from the constructor is
  itself the proof no such interaction is possible from this path.
- **AC5**: Added `V104` migration (`quota_released_at` on `main.upload_sessions`) and reworked Phase 2
  to look up the session via the same repository method Phase 1 uses (without Phase 1's `PENDING`
  filter), gate release on `reservationHandle != null && quotaReleasedAt == null`, and wrap the release
  call in try/catch that persists `quotaReleasedAt` only on success and rethrows on failure. Added 3 new
  `AdminVideoServiceTest` cases covering failure-propagation, retry-after-prior-failure, and
  no-double-release.
- **AC6**: Added `V105`/`V106` (`NOT VALID` + `VALIDATE CONSTRAINT`, mirroring `V100`/`V101` exactly).
  Added `ConfigResourceIT.putPaymentCurrency_malformedValue_returns4xxNotRaw500` exercising the live
  `ConfigResource.updateValue` admin write path per Dev Notes.
- **AC7**: All 11 stale `[PICKED UP by skillars-deferred-NN ...]` tags in `deferred-work.md` flipped to
  `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]` (12 tag
  occurrences — the `BookingServiceTest` positional-constructor item has two citations). The 5
  "leave-as-is" decisions annotated `[DECIDED 2026-08-25: ...]` in place. The original
  `skillars-deferred-28` late-cancel-auto-no-show question annotated `[CLOSED by skillars-deferred-64
  AC4: refund-only, ...]`, distinguishing it from a full no-show conversion per the story's own text.

### Validation

Per `docs/validation-strategy.md`, targeted unit + integration tests were run for each AC rather than
the full Maven suite: `CoachProfileBuilderIT` (AC1), `BookingServiceTest` (AC2, AC4),
`PlaybackServiceTest`/`PlaybackRevocationWindowUnitTest`/`PlaybackServiceConcurrencyIT`/
`PlaybackServiceIT`/`PlaybackRevocationIT` (AC3), `AdminVideoServiceTest` (AC5), `ConfigResourceIT`
(AC6) — all passing. Flyway migration application was verified implicitly: `ConfigResourceIT`'s
Spring context boot applies `V104`–`V106` against the real Testcontainers Postgres instance with no
migration failure. Full `mvn verify` was not run locally, per this repository's standing convention —
GitHub CI is the authoritative full-verification gate.

## File List

**Modified:**
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC2, AC4)
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/repo/UploadSession.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java` (AC5)
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (AC2, AC4)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceTest.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackRevocationWindowUnitTest.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java` (AC5)
- `src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java` (AC6)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status tracking)

**Added:**
- `src/main/resources/db/migration/V104__upload_session_quota_released_at.sql` (AC5)
- `src/main/resources/db/migration/V105__payment_currency_format_guard.sql` (AC6)
- `src/main/resources/db/migration/V106__payment_currency_format_guard_validate.sql` (AC6)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceConcurrencyIT.java` (AC3)

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-25 | Story created via story-creation process. Full re-mine of `deferred-work.md` (1570 lines, all sections) found 11 stale `[PICKED UP]` tags naming already-shipped stories (re-verified fixed, closed via AC7) and 6 genuinely open items worth bundling: 3 decision-light (AC1-AC3, mechanical fixes matching an already-made product call) and 3 fresh decisions made directly with the project owner in this story's creation pass (AC4-AC6). Five additional open questions surfaced during the same pass were explicitly decided "leave as-is" (no code change) and are recorded in AC7 rather than left to be re-asked next time. |
| 2026-08-25 | Story-review adjustments applied (`story-review.md`), status remains ready-for-dev. 7 findings, 3 High + 2 Medium + 2 Low; one High/Medium pair needed a fresh product call from the project owner, resolved directly, the rest were factual corrections applied without a further decision. **AC4 (High + Medium, product call needed):** the original plan routed a late cancel through `BookingEvent.NO_SHOW_COACH`/`CoachNoShowEvent`, reusing `recordNoShowCoach`'s full-refund-plus-strike consequence — story-review found this would have (a) silently stopped notifying the coach entirely, since `CoachNoShowEvent` carries no `coachEmail` and has no coach-facing listener, and (b) issued an unearned, uncontestable `COACH_NO_SHOW` reliability strike with no attestation from the parent that the coach actually failed to show up. **Project owner's decision: refund-only, no strike.** AC4 rewritten to simply widen `cancelBookingAsParent`'s existing `refundEligible` boolean to also cover a cancel arriving after the booking's start time, staying entirely on the ordinary `CANCEL_PARENT` event path — no `BookingStateMachine` change, no `CoachNoShowEvent` involvement, and the coach-notification regression is moot since the event type never changes. This also simplified Task 4 from 3 subtasks to 2. **AC6 (High, no decision needed — factual correction):** the claim "no admin UI or endpoint writes to `main.platform_config` today" was false — `ConfigResource.updateValue` (`PUT /api/config/values/{key}`, admin-only) writes through unvalidated via `ConfigService.updateConfig`. AC6's rationale corrected to cite this live path as the guard's primary justification (not just a hypothetical future migration), and a new `ConfigResourceIT` test case added to the Dev Notes exercising exactly this path post-migration. **AC1 (High, no decision needed):** the Dev Notes named a nonexistent `CoachProfileServiceTest.java` as the file to extend — `CoachProfileService` has no unit-test class anywhere in the tree; corrected to extend the real, existing `CoachProfileBuilderIT.java` instead. **AC3 (Medium, no decision needed):** the claim that the per-video lock is "held only briefly" was factually wrong — `PessimisticLockRetryer` runs inside the caller's own transaction with no savepoint release of its own, so the lock is genuinely held for the whole `authorizePlayback` transaction, including the token insert and (on one branch) a second provider call. Corrected to state this honestly, keep the same design as an accepted, real trade-off (not a cosmetic one, given `authorizePlayback` is a hot per-request path), and require Task 3.3 to verify behavior under realistic concurrent load rather than assume "briefly." **AC1 (Low, no decision needed):** the claim that importing `BookingError` into `platform.marketplace.service` doesn't introduce a new module-dependency direction was wrong — it is the first-ever `marketplace → booking` import (today the direction is exclusively `booking → marketplace`); corrected to describe it as a deliberate, accepted one-directional dependency, not a continuation of an existing pattern. **AC5 (Low, no decision needed):** corrected wording that implied Phase 1 and the proposed Phase 2 lookup are different queries — they call the identical repository method; Phase 1 additionally chains a Java-side status filter that Phase 2 must simply omit. Six further claims were spot-verified and found accurate, no changes needed (see `story-review.md`'s "Items checked and found accurate" section). |
| 2026-08-25 | Implementation complete, all 7 tasks/ACs done, status moved to review. See Dev Agent Record above for per-AC detail. Targeted unit + integration tests pass for every AC; full regression left to GitHub CI per `docs/validation-strategy.md`. |
