# Story Deferred-63: Product-Directed Fairness & Consistency Fixes

Status: done

## Story

As an engineer operating this platform,
I want seven independently product-decided fixes from `deferred-work.md` shipped together —
a loud-not-silent guard for non-`CAPTURED` payments (`FROZEN`, `CAPTURE_PENDING`, `CHARGE_FAILED`), a
suspended-coach duplication guard, a bandwidth-quota dedup rule, a no-show time guard, a coach's ability
to raise their own dispute (a symmetric first-raise right, not a rebuttal/contest mechanism — see AC5), a
one-time backfill reconciling existing drift between a coach profile and its availability windows'
timezones, and a defensive session-duration dropdown fix —
so that the ledger's decision-needed backlog actually drains once the project owner has made the calls,
instead of accumulating indefinitely as each prior bundled-fix story correctly declined to make these
calls unilaterally.

### Why this story exists

Unlike every prior story in this `skillars-deferred-*` series, this one does **not** follow the series'
own established convention of picking only "decision-light" items. `skillars-deferred-59`,
`skillars-deferred-60`, and `skillars-deferred-61` each explicitly surveyed `deferred-work.md` in full and
explicitly declined every item this story now closes, each time citing the same reason: "needs a
product/architecture decision this kind of bundled small-fix story should not make ad hoc." The project
owner made those decisions directly, in a multi-round discussion on 2026-08-24 covering nine
`deferred-work.md` items (two more were separately either found moot on investigation or scoped into the
sibling story `skillars-deferred-62`; see below). This story implements the seven that resulted in a
concrete fix; `skillars-deferred-62` — split out separately because it alone touches 14 call sites across
9 services — carries the eighth (the `jakarta.persistence.lock.timeout`-has-no-effect-on-Postgres fix).

**One item from the same discussion was investigated and found not to need a code change.** The
`DrillMetadata.repDensity cannot represent "coach never set this"` item (originally filed during
`skillars-deferred-45`'s AC4 spec audit) assumed a live JSON-deserialization path where a coach's custom
drill upload could omit `repDensity`. Investigated during this story's creation: no such path exists.
`grep -rn "new Drill(\|Drill.builder()\|new DrillMetadata("` across `platform.session` finds exactly one
non-test construction site — `DrillLibraryService.java:129`'s `clone.setMetadata(source.getMetadata())`,
which *copies* an existing, already-persisted drill's metadata; it does not deserialize a fresh
coach-submitted payload. `DrillUploadService`/`DrillUploadResource` (the only "drill upload" surface in
the app) handle the drill **video file** only — `DrillUploadInitiateRequest` has no `DrillMetadata` field
at all. Every `Drill` row's `metadata` column is therefore populated exclusively by migration/seed data
under full application-team control, never by a live user-facing request. The "coach never set
`repDensity`" scenario the item worried about has no reachable trigger today. **Not picked up as an AC**
— left as an untagged, now-annotated item in `deferred-work.md` rather than a code change, since there is
nothing to fix. If a future story ever adds a real coach-facing "propose a custom drill" endpoint that
accepts metadata as a request body, this investigation's finding no longer applies and the item should be
re-opened.

**One new item surfaced by this discussion and deliberately not picked up here, filed as a fresh entry
instead**: the idea that a coach shouldn't be paid until the parent confirms a session took place (raised
while scoping the no-show/dispute items below) would require moving payment capture from its current
point — booking confirmation, well before the session (`BookingPaymentPersistenceService.reserveCapture`,
confirmed by this story's own creation-time investigation: no code path anywhere gates coach payout on
`BookingCompletedEvent` or any other post-session signal; Stripe Connect settles the already-captured
charge independently) — to some point after session completion. That is a genuine escrow/delayed-payout
architecture change, not a bundled fix, and is recorded as its own new `deferred-work.md` item (this
story's AC8) for a dedicated future design pass rather than attempted here.

## Acceptance Criteria

1. **`DisputeService.resolveDispute` distinguishes any non-`CAPTURED` payment status from a
   legitimately-zero `sessionPrice`** (pack-based booking, or no payment record at all) instead of
   folding all of them into the same silent `sessionPrice = ZERO` path. **Scope widened during
   story-review (2026-08-24): originally FROZEN-only, broadened to cover every non-`CAPTURED` status** —
   `FROZEN` (`BookingPaymentStatus.FROZEN`,
   `[src/main/java/com/softropic/skillars/platform/payment/contract/BookingPaymentStatus.java:33]`,
   confirmed genuinely dormant: no write site anywhere in `src/main/java`) is not the only non-`CAPTURED`
   status that can exist on a `BookingPayment` row — `CAPTURE_PENDING` and `CHARGE_FAILED` are live,
   actively-written statuses as part of the UAT.3 async-capture design
   (`BookingPaymentPersistenceService.java:102,219,287`, `PaymentPendingSweeper.java:163`), and if either
   ever lingers on a booking that later reaches a dispute-eligible status, the current `CAPTURED`-only
   filter hits the exact same silent-zero trap. Today (`DisputeService.java:169-173`) that filter treats
   any non-`CAPTURED` status as if no payment exists at all, and the existing `log.warn` at `:182` only
   fires inside the `FULL_CREDIT` branch, with wording ("pack-based or missing payment record") that
   names no specific status and would be misleading regardless of which of the three the row's real
   status turns out to be. Add a distinct check against the *unfiltered* payment lookup: if a
   `BookingPayment` row exists with any status other than `CAPTURED`, log a clearly-labeled `WARN` naming
   the dispute id, booking id, and the actual status found (`FROZEN`, `CAPTURE_PENDING`, or
   `CHARGE_FAILED` — do not hardcode `FROZEN`-only logic) — distinct from the existing pack-based-zero
   warning, and regardless of which `resolution` branch is taken (not just `FULL_CREDIT`). Do **not**
   change what credit amount is actually issued (still zero in every non-`CAPTURED` case, per the
   explicit "don't redesign the guard" decision) — this AC is entirely about making the dormant/in-flight
   gap loud instead of silent, not about changing money-movement behavior.
   `[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:156-227]`
   `RevenueReportingService.getCoachReceipt`/`getParentReceipt`
   (`[src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java:150,182]`)
   already document their identical `CAPTURED`-only filter and behave correctly for the current 404 case;
   add the same distinguishing `WARN` there for consistency (naming the actual non-`CAPTURED` status
   found), with no change to the existing 404 response.

2. **`BookingDuplicationService.duplicateNextWeek` blocks duplicating a booking for a since-suspended
   coach**, mirroring `RescheduleService.acceptReschedule`'s existing check exactly.
   `duplicateNextWeek` (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:44-65]`)
   already takes the coach-row lock (`skillars-deferred-58` AC2) but never checks
   `CoachProfileStatus.SUSPENDED` on the locked, refreshed row — unlike `acceptReschedule`'s identical
   lock pattern one file over
   (`[src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:208-219]`),
   which does. Add the same check, in the same place relative to the lock (immediately after
   `entityManager.refresh(coach, PESSIMISTIC_WRITE)`, before the availability-window re-check), using the
   same `BookingError.COACH_UNAVAILABLE` error code `acceptReschedule` uses for byte-for-byte consistent
   caller-facing behavior between the two sibling methods.

3. **`PlaybackService.authorizePlayback` stops re-charging a viewer's playback to the video owner's
   bandwidth quota when a still-active `PlaybackToken` already exists for the same `(viewerId, videoId)`
   pair.** Today
   (`[src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java:111-125]`) every
   successful authorization unconditionally charges `video.getStorageBytes()` to
   `quotaService.incrementBandwidthUsedBytes(video.getOwnerId(), storageBytes)`, so a token refresh, page
   reload, retry, or a second concurrent viewer session all re-charge the full file size again with no
   dedup window, even though `bandwidth_used_bytes` feeds an *enforced* quota, not just a reporting
   number. Product-directed dedup rule (2026-08-24): **per viewer+video+time-bucket**, implemented by
   reusing the `PlaybackToken` table this method already writes to on every call
   (`[src/main/java/com/softropic/skillars/platform/video/repo/PlaybackToken.java]`,
   `[.../repo/PlaybackTokenRepository.java]`) rather than introducing new schema or a new config key: add
   a repository query checking whether a non-revoked, non-expired `PlaybackToken` already exists for this
   exact `(viewerId, videoId)` pair *before* the new token is saved at `:139-143`; skip the
   `quotaService.incrementBandwidthUsedBytes` call (but still authorize playback and issue a fresh token
   normally) when one does. This ties the dedup window directly to the token TTL already computed earlier
   in the same method (`ttlMinutes`/`expiresAt`, `:98-101`) — a re-authorization while a prior token for
   the same viewer+video is still valid is treated as the same viewing session; once that token expires or
   is revoked, the next authorization charges again. **Known, accepted gap (story-review, 2026-08-24):**
   this closes the *sequential* re-authorization case (a viewer repeatedly re-triggering re-authorization
   within one token's lifetime cannot inflate the charge that way, and cannot evade it either — an
   unexpired active token means the viewer has already been charged for exactly this window), but the
   check-then-charge sequence is not locked — two genuinely concurrent `authorizePlayback` calls for the
   same `(viewerId, videoId)` (e.g. two browser tabs, or a client retry racing the original request) can
   both pass the "no active token yet" check before either commits its new row, and both charge. Accepted
   as-is: the blast radius is a bandwidth-quota over-count by at most one extra `storageBytes` per race
   (not a money-movement or double-booking bug), and adding row-level locking here is explicitly out of
   scope for this story — do not add a `SELECT ... FOR UPDATE` or equivalent. Write a new repository
   query method (e.g.
   `existsActiveForViewerAndVideo(viewerId, videoId, now)`), analogous in shape to the existing
   `hasRecentRevocation` query in the same repository, rather than fetching and filtering in Java.

4. **`BookingService.recordNoShowCoach` rejects a no-show claim raised before the booking's scheduled
   start time has actually passed.** Today
   (`[src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:739-758]`) the
   method checks only that the caller owns the booking as parent — a parent could call
   `POST /api/bookings/{id}/no-show-coach` at any point while the booking is still `UPCOMING`, including
   well before `requestedStartTime`, and it would fire the same automatic full-refund + coach-strike
   consequence (`CancellationRefundService.onCoachNoShow`) a genuine no-show gets. Add a check
   immediately after the existing ownership check: if `Instant.now().isBefore(booking
   .getRequestedStartTime())`, throw `OperationNotAllowedException` with a new `BookingError
   .NO_SHOW_TOO_EARLY` code (`"booking.noShowTooEarly"`, added alongside the module's existing codes at
   `[src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java]` — this project's
   own established convention per that enum's own doc comment: split a specific, actionable error code
   rather than reuse a generic one). Out of scope for this AC, deliberately (the project owner's
   discussion did not select it): extending `NO_SHOW_COACH` to be raisable from `IN_PROGRESS` (i.e. after
   the coach has pressed "start") — that gap remains open in `deferred-work.md`, tracked separately.
   Add the new code's message to all four backend i18n bundles
   (`src/main/resources/i18n/messages.properties`, `messages_de.properties`, `messages_fr.properties`,
   `messages_en.properties`), following the exact key/value pattern the other `BookingError` codes already
   use there (e.g. `booking.coachUnavailable=This coach is currently unavailable.`). **No frontend change
   is needed or expected**: `recordNoShowCoach` (`src/frontend/src/api/booking.api.js:80`) is called from
   no page in the frontend today — confirmed by grep, there is currently no "report coach no-show" button
   anywhere in the UI — so there is no existing `errorKey`-branching call site (the pattern
   `ParentBookingsPage.vue:209-233` uses for its own reschedule errors) to extend for this new code.

5. **A coach can raise a dispute on their own booking, not only the parent/player.** **Scope clarified
   during story-review (2026-08-24): this is a symmetric first-raise right, not a contest/rebuttal
   mechanism.** `DisputeService.raiseDispute` already rejects a second dispute on any booking that has
   one open (`disputeRepository.findOpenByBookingId(bookingId)`, no `raisedBy` filter,
   `[DisputeService.java:76-79]` after this AC's `ownerEligible` widening) and `getDispute` already 403s
   any caller who isn't the original raiser (`[DisputeService.java:107-120]`) — neither of those
   pre-existing behaviors changes here. So this AC delivers: **whichever party (parent or coach) raises a
   dispute on a booking first gets to; the other party cannot then also raise one, and cannot read the
   first party's dispute to respond to it.** Concretely, this means a coach can raise their own,
   independent dispute on a booking that has no dispute yet (e.g. the common case where a parent's
   `NO_SHOW_COACH` report never itself creates a `Dispute` row, since `recordNoShowCoach` transitions the
   booking status directly) — but if a parent has *already* filed a formal dispute on that booking, the
   coach's `raiseDispute` call 409s with `disputes.alreadyRaised`, same as it would for any other
   already-disputed booking; there is no way for the coach to contest, rebut, or even view that existing
   dispute through this AC. Do not attempt to change `findOpenByBookingId` or `getDispute`'s visibility
   rule to work around this — a true two-sided contest mechanism (does a second open dispute on one
   booking need to be resolved jointly? does the admin UI support that?) is a separate design question,
   explicitly not decided here; see AC8's ledger note.
   `DisputeService.raiseDispute`'s `ownerEligible` check
   (`[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:76]`) currently
   only matches `booking.getParentId()`/`booking.getPlayerId()`; widen it to also match the coach's own
   user id, resolved via `coachProfileRepository.findById(booking.getCoachId()).map(CoachProfile
   ::getUserId)` (`coachProfileRepository` is already injected in this class,
   `[DisputeService.java:58]`; `CoachProfile.userId` is the `Long` user id field,
   `[src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java:36]` — note
   `booking.getCoachId()` is the coach *profile* UUID, not a user id, so this needs the same profile
   lookup `getAdminDisputeDetail` already does at `:133-134` for the coach's display name). No change
   needed to `ELIGIBLE_STATUSES`/`VALID_REASONS`
   (`[DisputeService.java:49-53]`) — `NO_SHOW_COACH` is already an eligible status (so a coach can dispute
   a no-show claim raised against them) and `"OTHER"` is already a valid reason a coach can use pending any
   future coach-specific reason taxonomy. Also fix `DisputeResource.resolveCurrentRole()`
   (`[src/main/java/com/softropic/skillars/platform/admin/api/DisputeResource.java:74-81]`), which today
   hardcodes `"PARENT"` or falls back to `"PLAYER"` for *any* non-parent caller — including a coach, who
   would today have their own dispute incorrectly recorded with `raisedByRole = "PLAYER"`. Add an explicit
   `"COACH"` branch (checking for `ROLE_COACH`, mirroring the existing `ROLE_PARENT` check) ahead of the
   `PLAYER` fallback. **Backend-only change, no frontend work**: no dispute-raising UI exists anywhere in
   the frontend today for any role (confirmed by grep — `POST /api/disputes` has no caller in
   `src/frontend/src`), so this AC only makes the API itself fair; it does not add a UI a coach could use
   it from.

6. **A one-time migration backfills existing drift between `coach_availability_windows.canonical_timezone`
   and `coach_profiles.canonical_timezone`.** **Scope narrowed during story-review (2026-08-24): backfill
   only, no `saveStep4` behavior change this story.** The original framing ("windows should never diverge
   from the profile — the profile is the sole source of truth") is still the product's stated direction,
   but story-review found that `ProfileBuilderStep4.vue` (`:66-68`) already ships a real, coach-editable
   `TimezoneSelect` under its own "Timezone" section, with helper copy (`step4TimezoneHelper`, present in
   all three locale bundles) reading **"Windows above are interpreted in this timezone,"** and the
   component's own code comment explains this is deliberate — auto-syncing it from the profile "would
   silently overwrite a per-window zone the coach had deliberately chosen here." Changing
   `CoachProfileService.saveStep4`
   (`[src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:251]`) to
   ignore the request's `canonicalTimezone` and force `profile.getCanonicalTimezone()` instead — this
   AC's original plan — would make that picker and its helper text actively lie about what the screen
   does, with no client-side signal that the coach's choice was silently discarded. Shipping that backend
   change without a coordinated frontend change (drop the picker, or make it read-only with a "change it
   in Step 1" link) would be a UI regression, not a pure bug fix. **Do NOT change `saveStep4`'s write
   behavior in this story.** Instead, only add a new migration backfilling *existing* diverged rows to
   match their profile's current value (single `UPDATE ... FROM`, no batching needed — availability
   windows are capped at 14 per coach by the request DTO's own `@Size(max = 14)`, so no table-scale
   concern applies here the way it did for `Def10`/`V98`'s much larger tables):
   ```sql
   UPDATE marketplace.coach_availability_windows w
   SET canonical_timezone = p.canonical_timezone
   FROM marketplace.coach_profiles p
   WHERE w.coach_id = p.id
     AND w.canonical_timezone != p.canonical_timezone;
   ```
   Next available migration id is `V102` (`V101__stripe_customer_id_format_guard_validate.sql` is the
   latest at story-creation time — confirm before writing the file in case a sibling story lands first).
   This is a one-time cleanup of *past* drift only — coaches can continue to diverge a window's timezone
   from their profile's through Step 4 after this migration runs, exactly as they can today. The
   `saveStep4` write-path fix and its required `ProfileBuilderStep4.vue` counterpart are deliberately
   deferred together to a follow-up story (see AC8's new ledger item), so backend and frontend change in
   the same story rather than the backend silently getting ahead of the UI. `AvailabilityService
   .updateWindow` (`[src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:240]`)
   does not accept a `canonicalTimezone` field on its own `UpdateWindowRequest` — noted for the follow-up
   story's benefit, not actionable here since this AC makes no `AvailabilityService` change.

7. **`ProfileBuilderStep3.vue`'s session-duration `q-select` shows a coach's actual value as a synthetic
   option when it falls outside the 5 preset choices, instead of rendering unselected.** Lowest-priority
   item in this bundle — investigated during this story's creation and confirmed **currently unreachable
   through any live UI path**: `sessionDurationMinutes` appears nowhere else in the frontend
   (`grep -rln "sessionDurationMinutes" src/frontend/src` returns only this one file), and
   `ProfileBuilderStep3.vue` is used exclusively by the profile-creation flow
   (`CoachProfileBuilderPlaceholderPage.vue`), where the field always starts `null` and is only ever set
   from `durationOptions`
   (`[src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue:103-119]`) — there is no
   coach-facing edit page that loads and redisplays an existing value at all. The scenario is reachable
   only via a direct API call bypassing the UI, exactly as the original ledger item's own text says. Ship
   it anyway as defensive hardening (product-directed decision: "fix dropdown only, no new cap") in the
   same spirit as this project's other reachable-only-via-a-non-UI-path defensive guards (e.g.
   `skillars-deferred-59` AC4's `PaymentMethodCard.vue` guard): when `form.sessionDurationMinutes` is
   non-null and not one of `DURATION_CHOICES`, add it to `durationOptions` as an extra synthetic entry (raw
   number as its own label) so the select renders the true value instead of appearing unselected/reset.

8. **Ledger hygiene.** In `deferred-work.md`:
   - Flip these picked-up items to `[CLOSED by skillars-deferred-63 ACn]` with a one-line closure note
     each, or delete outright per this file's own stated convention where the enclosing section has no
     other content:
     - AC1's `skillars-deferred-41` FROZEN item — closure note: scope widened during story-review to
       cover all three non-`CAPTURED` statuses (`FROZEN`, `CAPTURE_PENDING`, `CHARGE_FAILED`), not just
       `FROZEN`.
     - AC2's `skillars-deferred-58` review `duplicateNextWeek`-SUSPENDED item.
     - AC3's `skillars-deferred-40` review bandwidth-dedup item — closure note: closes the *sequential*
       re-authorization case only; the concurrent-race double-charge case is a known, accepted,
       deliberately-unfixed gap — see the new item filed below, do not word this closure as if the gap is
       fully solved.
     - AC5's dispute-fairness gap — filed fresh by this story's own creation pass, since no existing
       ledger item named it explicitly before this discussion surfaced it. Closure note: ships a
       symmetric first-raise right only, not a contest/rebuttal mechanism — see the new item filed below
       for what remains open.
     - AC7's `session-duration q-select` item.
   - **AC6 — file as a new item, do NOT close `skillars-deferred-17`'s `canonical_timezone` review item.**
     Story-review narrowed AC6 to a one-time backfill migration only (see AC6); `saveStep4` still writes
     each new window's `canonicalTimezone` from the request payload, so new divergence can still occur
     going forward. Update the existing `skillars-deferred-17` item's annotation to record partial
     progress ("existing drift backfilled by `skillars-deferred-63` `V102`; the write-path fix that
     prevents new drift is still open") rather than closing it, and see the new follow-up item filed below
     for what that write-path fix needs to include.
   - **AC4 — split into two ledger actions (story-review, 2026-08-24, correcting a citation error in this
     story's original AC8 text).** The late-parent-cancel/no-show product question is filed under
     `## Deferred from: skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-
     timestamp-test story creation (2026-08-17)` (not `skillars-deferred-30`, as this AC originally said —
     verify the exact current heading at implementation time regardless, since this file is actively
     edited by concurrent story-creation passes). AC4 as implemented adds a time guard to
     `recordNoShowCoach` (a parent explicitly *reporting* a no-show) — a real, separate gap from that
     item's actual question (should a late `cancelBookingAsParent` call auto-convert into a no-show);
     `cancelBookingAsParent` is untouched by this story. Do not let AC4 retire that question:
     - File a **new** item (or a sub-note under the `[PICKED UP by skillars-deferred-63 AC4 ...]`
       annotation already present as of story-creation) closing only the specific gap AC4 actually fixes
       — a parent could call `recordNoShowCoach` before the booking's scheduled start time —
       `[CLOSED by skillars-deferred-63 AC4]`.
     - Leave the original "should a late `CANCEL_PARENT` auto-become a no-show?" question **open**, under
       its correct `skillars-deferred-28` heading. It remains genuinely undecided and is not answered by
       AC4.
   - Annotate (not delete — there is no code change, so nothing to "close") the `repDensity` item with
     this story's investigation finding: no live path constructs a `Drill` with coach-submitted metadata
     today, so the "coach never set this" scenario has no current trigger. See this story's own "Why this
     story exists" section above for the full finding to copy into the annotation.
   - File a **new** `deferred-work.md` item for AC5's remaining gap (story-review, 2026-08-24): AC5 ships
     a symmetric first-raise right only — a coach still cannot contest, rebut, or even view a dispute a
     parent already filed (`findOpenByBookingId` has no `raisedBy` filter; `getDispute` 403s any
     non-raiser). Record this as distinct from, but related to, the pre-existing "no coach-side rebuttal
     before an automatic no-show refund fires" item filed below — both belong to a future two-sided-dispute
     design pass.
   - File a **new** `deferred-work.md` item for AC6's deferred write-path half (story-review,
     2026-08-24): this story ships only the one-time backfill migration (`V102`).
     `CoachProfileService.saveStep4` still writes each window's `canonicalTimezone` from the request
     payload rather than the profile, and `ProfileBuilderStep4.vue` still exposes a coach-editable
     per-window timezone picker with helper text ("Windows above are interpreted in this timezone") — the
     two need to change together in one follow-up story (drop or make the picker read-only, *then* make
     `saveStep4` stop trusting the request value), so the backend never gets ahead of what the UI still
     promises.
   - File a **new** `deferred-work.md` item for AC3's accepted concurrency gap (story-review,
     2026-08-24): the bandwidth-quota dedup check in `PlaybackService.authorizePlayback` is not locked —
     two genuinely concurrent authorizations for the same `(viewerId, videoId)` can both charge before
     either commits its token row. Accepted as a low-stakes gap (bandwidth over-count, not a
     money-movement bug) and deliberately not fixed by this story; a future fix would need a
     `(viewer_id, video_id)`-scoped lock around the exists-check, mirroring this codebase's
     `PessimisticLockRetryer` pattern elsewhere.
   - File a **new** `deferred-work.md` item recording the payment-capture-timing/escrow question this
     story's creation surfaced and deliberately did not implement: today, `BookingPaymentPersistenceService
     .reserveCapture` captures payment at booking confirmation, well before the session occurs, and no code
     path gates coach payout on session completion or parent confirmation — Stripe Connect settles the
     already-captured charge independently. Moving to a "coach isn't paid until the parent confirms" model
     would be a genuine escrow/delayed-payout architecture change (not a bundled fix), needing its own
     design pass on: how long funds stay held, what happens if a parent never confirms and the
     `QuickCompleteTimeoutService` auto-completes on their behalf anyway
     (`[src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java:36-61]`),
     and how it interacts with the still-one-sided dispute system this story's AC5 only partially
     addresses (a coach can now raise their own dispute, but there is still no way for a coach to contest
     one a parent already filed, and no coach-side rebuttal *before* an automatic no-show refund fires).
     Also leave open, explicitly not picked up by this story: extending `NO_SHOW_COACH` to fire from
     `IN_PROGRESS` (a coach could otherwise dodge a no-show claim just by pressing "start" without showing
     up) — deliberately declined by the project owner this round.

## Tasks / Subtasks

- [x] Task 1: Non-`CAPTURED` fail-loudly guard (AC1, widened during story-review from FROZEN-only to
      every non-`CAPTURED` status)
  - [x] 1.1: `DisputeService.resolveDispute` distinguishing WARN
  - [x] 1.2: `RevenueReportingService.getCoachReceipt`/`getParentReceipt` distinguishing WARN
- [x] Task 2: `duplicateNextWeek` SUSPENDED guard (AC2)
- [x] Task 3: Bandwidth dedup (AC3)
  - [x] 3.1: New `PlaybackTokenRepository` query method
  - [x] 3.2: Wire the check into `authorizePlayback` before the quota-increment call
  - [x] 3.3: Test coverage: same-viewer-same-video re-authorization within an active token's TTL does not
        double-charge; a second, distinct viewer of the same video still charges independently; charging
        resumes once the prior token has expired/been revoked. The concurrent-race double-charge case
        (accepted gap, see AC3) is explicitly out of scope — no test for it is expected.
- [x] Task 4: No-show time guard (AC4)
- [x] Task 5: Coach dispute-raising capability (AC5, symmetric first-raise right only — see AC5 for what
      this deliberately does not cover)
  - [x] 5.1: `DisputeService.raiseDispute` `ownerEligible` widening
  - [x] 5.2: `DisputeResource.resolveCurrentRole()` `COACH` branch
  - [x] 5.3 (found during implementation, not in the original AC text): new `V102` migration widening
        `admin.disputes`' `raised_by_role` CHECK constraint to permit `'COACH'` — V74's inline constraint
        only ever allowed `'PARENT'`/`'PLAYER'`, so a coach-raised dispute 400'd at insert time
        (`generic.dataError`) until this was added. Confirmed live via `DisputeSubmissionIT` before and
        after the fix.
- [x] Task 6: Timezone drift backfill (AC6, backfill-only — no `saveStep4` change, see AC6 for why)
  - [x] 6.1: Backfill migration (confirm next available id before writing — `V102` was taken by Task 5's
        unplanned `raised_by_role` CHECK-widening fix found during implementation, so this shipped as
        `V103` instead; verified the exact SQL from the AC against seeded diverged/matching rows in a
        throwaway Postgres container before adding it to the migration path, then confirmed it applies
        cleanly against the real schema via a full `CoachProfileBuilderIT` run, 31/31 green)
- [x] Task 7: Session-duration dropdown defensive fix (AC7)
- [x] Task 8: Ledger hygiene (AC8)

### Review Findings

- [x] [Review][Patch] Suspended coach can still raise a dispute — `DisputeService.raiseDispute`'s new `isCoach` `ownerEligible` widening (AC5) has no `CoachProfileStatus.SUSPENDED` check, unlike the parallel guard AC2 added to `BookingDuplicationService.duplicateNextWeek` for the identical since-suspended-coach scenario. **Resolved during code review (2026-08-25): added the guard, mirroring AC2** — a suspended coach can no longer raise a dispute; new test `raiseDispute_coachOwnsBookingButSuspended_throwsNotEligible` added. `[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:81-95]`
- [x] [Review][Patch] `DisputeService.raiseDispute` computed `isCoach` via an unconditional `coachProfileRepository.findById` DB lookup before checking the cheaper `parentId`/`playerId` equality — wasted a DB round-trip on the common (non-coach) path. **Fixed**: reordered to short-circuit — `parentId`/`playerId` checked first, the coach lookup (now also filtering out `SUSPENDED`) only runs lazily if neither matches. `[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:81-95]`
- [x] [Review][Patch] New test methods in `DisputeServiceTest.java` used fully-qualified `java.time.Instant.now()` and `org.mockito.Mockito.never()` inline instead of adding imports, inconsistent with the file's existing import-based style. **Fixed**: added `java.time.Instant` and `static org.mockito.Mockito.never` imports, replaced all fully-qualified usages. `[src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java]`
- [x] [Review][Patch] `V102__disputes_raised_by_role_coach.sql` used `DROP CONSTRAINT disputes_raised_by_role_check` without `IF EXISTS`, while its own cited precedents (`V72`, `V91`) both use `DROP CONSTRAINT IF EXISTS`. **Fixed**: added `IF EXISTS`. `[src/main/resources/db/migration/V102__disputes_raised_by_role_coach.sql]`

**Verification after patches**: `DisputeServiceTest` 8/8 green (was 7, +1 new suspended-coach test), `DisputeSubmissionIT` 12/12 green, `mvn test-compile` clean.

## Dev Notes

**This story intentionally breaks the series' own "decision-light only" convention.** Every AC here
implements a decision the project owner made directly (multi-round discussion, 2026-08-24), not a
decision this story's own creation pass made unilaterally — unlike every prior `skillars-deferred-*`
story, which explicitly declined items needing exactly this kind of call. Do not treat the presence of a
product/architecture decision in an AC's rationale as license to make further, unstated decisions the same
way — where an AC specifies a scope boundary (e.g. AC4's explicit exclusion of the `IN_PROGRESS`
no-show-transition question, AC1's explicit "don't change what credit is issued"), that boundary is
deliberate, not an oversight.

**AC3's dedup design deliberately reuses existing schema.** `PlaybackToken` already carries `videoId`,
`viewerId`, `expiresAt`, and `revokedAt` on every authorization — no new table or config key is needed.
Model the new repository query on the existing `hasRecentRevocation` query in the same file
(`[PlaybackTokenRepository.java]`) for a consistent style.

**AC5's `booking.getCoachId()` is a coach-*profile* id (UUID), not a user id.** Every other
`ownerEligible` comparison in `raiseDispute` compares directly against a `Long` user id
(`parentId`/`playerId`, which are user ids at the `Booking` level already) — the coach check needs an
extra `coachProfileRepository` hop to get from the profile id to `CoachProfile.userId` before comparing.
Get this wrong and every coach-raised dispute will silently 403 as `NOT_ELIGIBLE`.

**AC6 is backfill-only (scope narrowed during story-review, 2026-08-24) — do not touch `saveStep4`,
`ProfileBuilderStep4Request`, or `ProfileBuilderStep4.vue` in this story.** Story-review confirmed
`ProfileBuilderStep4.vue` ships a real, coach-editable per-window `TimezoneSelect` with explicit helper
copy ("Windows above are interpreted in this timezone") and its own code comment stating the divergence
is deliberate. Making `saveStep4` silently discard that value — the AC's original plan — would ship a UI
that visibly lies about what it does, with no frontend signal. The `saveStep4` write-path fix and its
required frontend counterpart are deferred together to a follow-up story (AC8's new ledger item), so they
land as one coordinated change instead of the backend getting ahead of the UI.

**AC7 is the lowest-value item in this bundle by a wide margin** (unreachable via any current UI path,
confirmed during this story's creation) — implement it last, and do not let it block or complicate the
other six ACs' review.

**Existing test files to extend (do not create new ones for these classes):**
`DisputeServiceTest.java` (`platform.admin.service`, AC1/AC5), `RevenueReportingServiceTest.java`
(`platform.payment.service`, AC1), `BookingDuplicationServiceTest.java` (`platform.booking.service`,
AC2), `PlaybackServiceTest.java` (`platform.video.service`, AC3), `BookingServiceTest.java`
(`platform.booking.service`, AC4). AC5's endpoint-level coverage belongs in
`DisputeSubmissionIT.java` (`platform.admin.api`) — there is no `DisputeResourceIT`, don't create one.
AC6 is now migration-only (scope narrowed during story-review — no `saveStep4` behavior change), so its
proof is that the `V102` backfill `UPDATE` runs cleanly against seeded diverged rows, not a
`CoachProfileBuilderIT` assertion; a lightweight migration test (or a manual `flyway migrate` check
against fixture data) is sufficient, whichever this codebase's existing migration-test convention uses.
AC7 is frontend-only; this codebase has no frontend test runner (a long-standing, deliberately-accepted
gap — do not add one for this story).

### Project Structure Notes

Backend: `platform.admin.service` (DisputeService), `platform.payment.service`
(RevenueReportingService), `platform.booking.service` (BookingDuplicationService, BookingService),
`platform.booking.contract` (BookingError), `platform.video.service`/`platform.video.repo` (PlaybackService,
PlaybackTokenRepository), `platform.admin.api` (DisputeResource), a new `V102` migration (data-only, no
Java change alongside it — `CoachProfileService`/`platform.marketplace.service` is NOT touched by this
story, see AC6). Frontend: `ProfileBuilderStep3.vue` only.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — source of all seven items; see this story's
  own creation-time investigation findings above for the exact sections and why each is annotated the way
  it is in AC8.
- `skillars-deferred-58`, `skillars-deferred-49`/`-50` — the existing `acceptReschedule` SUSPENDED-check
  and coach-lock precedents AC2 mirrors exactly.
- `skillars-deferred-59` AC4 — the precedent for shipping a defensive guard against a scenario only
  reachable via a non-UI path, cited by AC7.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

- `mvn -o test -Dtest=DisputeServiceTest,RevenueReportingServiceTest,BookingDuplicationServiceTest,PlaybackServiceTest,BookingServiceTest,PessimisticLockRetryerTest,ExpiredPackBookingValidationTest` — 80/80 green (final consolidated run across every touched unit test class).
- `mvn -o test -Dtest=DisputeSubmissionIT,CoachProfileBuilderIT,PlaybackServiceIT,PlaybackRevocationIT` — 56/56 green (every IT touching a modified persistence/endpoint path).
- `mvn -o test-compile` (full project, `-Dfrontend.skip=true`) — BUILD SUCCESS.
- `npx eslint src/components/profileBuilder/ProfileBuilderStep3.vue` — clean.
- AC5's `V102` migration need was discovered empirically: `raiseDispute_coach_eligible_returns201WithCoachRole` first failed with `400 generic.dataError` (`admin.disputes`' `raised_by_role` CHECK constraint only allowed `PARENT`/`PLAYER`) before the migration was added; re-ran green after.
- AC6's `V103` backfill SQL was verified against seeded diverged/matching rows in a throwaway `docker run postgres:16-alpine` container before being committed to the migration path (2 of 3 seeded rows updated, the already-matching row correctly left alone), then confirmed to apply cleanly against the real schema via `CoachProfileBuilderIT` (31/31 green).
- `mvn verify` not run locally, per `docs/validation-strategy.md`; full suite deferred to GitHub CI.

### Completion Notes List

- All 7 ACs (AC1–AC7) shipped as scoped by the story-review adjustments (see Change Log below); AC8 ledger hygiene applied to `deferred-work.md`.
- **Two gaps not anticipated by the story text were found and fixed during implementation, both squarely inside their AC's own described behavior (not scope creep):**
  - AC5: `admin.disputes.raised_by_role`'s CHECK constraint (`V74`) only ever permitted `'PARENT'`/`'PLAYER'` — a coach-raised dispute 400'd at insert time until `V102` widened it to also permit `'COACH'`. Without this, AC5's `ownerEligible` widening would compile and pass unit tests but 400 on every real coach-raised dispute.
  - AC6: `V102` was claimed by AC5's fix above, so AC6's backfill migration shipped as `V103` instead of the `V102` the story text anticipated — exactly the "confirm before writing the file in case a sibling story lands first" contingency the AC's own text already flagged.
- AC1/AC3/AC4/AC5 had no pre-existing test coverage for the exact behavior touched (`resolveDispute`'s WARN paths, `getCoachReceipt`/`getParentReceipt`'s 404 paths, `authorizePlayback`'s dedup check, `recordNoShowCoach` had zero tests at all, `raiseDispute` had zero tests at all) — new tests were added rather than extended for those specific methods, within the existing test classes the Dev Notes named (no new test *files* created).
- AC8's ledger edits went slightly beyond the story's original AC8 text where story-review's scope narrowing (AC3, AC5, AC6) created new gaps that needed their own tracking: filed three new items (AC5's remaining contest-capability gap, AC6's deferred `saveStep4`/frontend write-path half, AC3's accepted concurrency gap) under a new `## Deferred from: story-review and implementation of skillars-deferred-63...` heading, and split the `skillars-deferred-28` no-show/cancel item into "closed the specific gap AC4 fixes" (new bullet, same heading) vs. "leave the original late-cancel question open" (reverted its incorrect `[PICKED UP]` tag), per AC8's own instructions and this story's story-review Finding 5.
- No frontend automated test added for AC7 — this codebase has no frontend test runner, a standing, deliberately-accepted gap the Dev Notes explicitly say not to fix as part of this story.

### File List

**Backend — main:**
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java` (AC1, AC5)
- `src/main/java/com/softropic/skillars/platform/admin/api/DisputeResource.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java` (AC4)
- `src/main/java/com/softropic/skillars/platform/video/repo/PlaybackTokenRepository.java` (AC3)
- `src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java` (AC3)
- `src/main/resources/i18n/messages.properties`, `messages_en.properties`, `messages_de.properties`, `messages_fr.properties` (AC4)
- `src/main/resources/db/migration/V102__disputes_raised_by_role_coach.sql` (AC5, found necessary during implementation)
- `src/main/resources/db/migration/V103__availability_window_timezone_backfill.sql` (AC6)

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/admin/service/DisputeServiceTest.java` (AC1, AC5)
- `src/test/java/com/softropic/skillars/platform/payment/service/RevenueReportingServiceTest.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingDuplicationServiceTest.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceTest.java` (AC3)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` (AC4)
- `src/test/java/com/softropic/skillars/platform/admin/api/DisputeSubmissionIT.java` (AC5)

**Frontend:**
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue` (AC7)

**Ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC8)

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-24 | Story created via story-creation process, bundling seven items the project owner explicitly decided across a multi-round product-decision discussion (2026-08-24), breaking from this series' usual decision-light-only convention. An eighth decided item (`jakarta.persistence.lock.timeout`) was split out to its own story (`skillars-deferred-62`) for being too large to bundle safely. A ninth candidate (`DrillMetadata.repDensity`) was investigated and found to have no live trigger — annotated in `deferred-work.md`, not picked up as an AC. A tenth item (payment-capture/escrow timing) surfaced during the discussion and was filed as a brand-new `deferred-work.md` item rather than implemented. |
| 2026-08-24 | Story-review adjustments applied (`story-review.md`), status remains ready-for-dev. Five findings, all resolved with the project owner before editing (four needed an explicit product/scope call, one was a mechanical citation fix). Finding 1/HIGH (AC5): the widened `raiseDispute` eligibility still 409s via the existing `findOpenByBookingId` (no `raisedBy` filter) if the other party already has an open dispute on the booking, and `getDispute` still 403s any non-raiser — so AC5 could not actually let a coach *contest* an existing dispute, only raise their own first one. **Decision: scope down to a symmetric first-raise right**, AC5 rewritten to state this explicitly and warn future readers off "fixing" `findOpenByBookingId`/`getDispute` as a workaround; the real contest-capability gap is now tracked as a new AC8 ledger item instead. Finding 2/MEDIUM-HIGH (AC3): the dedup check's exists-then-charge sequence has no lock, so concurrent same-viewer-same-video authorizations can both charge, contradicting the AC's own "cannot inflate the charge" claim. **Decision: accept as a known, low-stakes gap** (bandwidth over-count only, not money) — AC3's claim softened to describe the sequential-only guarantee, row-level locking explicitly ruled out of scope, and Task 3.3 now states the concurrent case is untested by design; also filed as a new AC8 ledger item for future revisit. Finding 3/MEDIUM (AC1): the FROZEN-only scoping left the identical silent-`sessionPrice=0` bug live for the two other non-`CAPTURED` statuses, `CAPTURE_PENDING`/`CHARGE_FAILED`, which — unlike `FROZEN` — are confirmed live/actively-written, not dormant. **Decision: broaden AC1** to warn on any non-`CAPTURED` status (naming the actual status found), a small mechanical widening of the same fix. Finding 4/HIGH (AC6): story-review found `ProfileBuilderStep4.vue` already ships a real, coach-editable per-window timezone picker with explicit helper text ("windows above are interpreted in this timezone") that AC6's original `saveStep4` change would have silently defeated with no frontend signal — shipping a UI that visibly lies about what it does. **Decision: scope AC6 down to the one-time backfill migration only**; the `saveStep4` write-path change and its required frontend counterpart are deferred together to a follow-up story (new AC8 ledger item) so they land as one coordinated change. Finding 5/HIGH (AC8, no decision needed — pure citation error): AC4's ledger-closure text cited the wrong section heading (`skillars-deferred-30` instead of the correct `skillars-deferred-28-...`) and, more substantively, would have incorrectly retired the ledger's actual "should a late cancel auto-become a no-show?" question — a question AC4 (a `recordNoShowCoach` time guard) never actually answers, since `cancelBookingAsParent` is untouched by this story. Fixed by splitting AC8's AC4 closure into two actions: close only the specific `recordNoShowCoach` gap AC4 fixes, and explicitly leave the original cancel-vs-no-show question open under its corrected heading. Six items checked during the same review (line citations, "unreachable via UI" claims, migration numbering, i18n coverage, state-machine transitions) were independently re-verified and found accurate — no changes needed for those. |
| 2026-08-24 | Dev-story implementation complete, status → review. All 8 tasks / AC1-AC8 shipped exactly as scoped by the story-review adjustments above. Two gaps not anticipated by the story text were found and fixed during implementation, both inside their AC's own described behavior: AC5 needed a new `V102` migration widening `admin.disputes.raised_by_role`'s CHECK constraint to permit `'COACH'` (a coach-raised dispute 400'd at insert time without it — caught by the new `DisputeSubmissionIT` coach-eligible test); AC6's backfill migration shipped as `V103` instead of the anticipated `V102` since AC5's fix claimed it first (exactly the "confirm before writing... in case a sibling story lands first" contingency AC6's own text already flagged). AC6's `V103` backfill SQL was verified against seeded diverged/matching rows in a throwaway Postgres container before being added to the real migration path. AC8's ledger edits went slightly beyond its original text to also track the three new gaps story-review's own scope-narrowing decisions opened up (AC5's remaining contest-capability gap, AC6's deferred `saveStep4`/frontend write-path half, AC3's accepted concurrency gap), filed under a new `## Deferred from: story-review and implementation of skillars-deferred-63...` heading, and split the `skillars-deferred-28` no-show/cancel item per story-review Finding 5 (closed the specific gap AC4 fixes as a new bullet; reverted the incorrect `[PICKED UP]` tag on the original late-cancel question, left it open). Full detail in the Dev Agent Record above. Verified: 80/80 targeted unit tests, 56/56 targeted ITs, full `mvn test-compile` clean, `npx eslint` clean on the one touched frontend file; `mvn verify` not run locally per `docs/validation-strategy.md`. |
| 2026-08-25 | Code review complete (3-layer review — Blind Hunter, Edge Case Hunter, Acceptance Auditor), status → done. Acceptance Auditor confirmed 0 AC violations across AC1-AC8. 1 decision-needed and 3 patch findings survived triage against 15 verified false positives / explicit-by-spec design choices (including a top Blind Hunter claim that `BookingPaymentStatus.CAPTURED.equals(...)` was a broken enum-vs-String comparison — verified false, `BookingPaymentStatus` is deliberately not an enum). Decision resolved: a suspended coach could still raise a dispute via AC5's new `ownerEligible` widening, unlike the parallel `SUSPENDED` guard AC2 added to `BookingDuplicationService` — **decided to add the same guard**, mirroring AC2. All 4 patches applied: `DisputeService.raiseDispute` now blocks a suspended coach and short-circuits the parent/player check before the coach DB lookup (was previously unconditional); `DisputeServiceTest` import cleanup (`java.time.Instant`, `Mockito.never`) plus a new `raiseDispute_coachOwnsBookingButSuspended_throwsNotEligible` test; `V102__disputes_raised_by_role_coach.sql` now uses `DROP CONSTRAINT IF EXISTS`, matching its own cited precedents (`V72`, `V91`). Verified: `DisputeServiceTest` 8/8, `DisputeSubmissionIT` 12/12, `mvn test-compile` clean. Full detail in the story file's Review Findings section. |
