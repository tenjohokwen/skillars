# Story Deferred-63: Product-Directed Fairness & Consistency Fixes

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want seven independently product-decided fixes from `deferred-work.md` shipped together —
a loud-not-silent guard for `FROZEN` payments, a suspended-coach duplication guard, a bandwidth-quota
dedup rule, a no-show time guard, a coach's ability to contest a dispute, timezone reconciliation between
a coach profile and its availability windows, and a defensive session-duration dropdown fix —
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

1. **`DisputeService.resolveDispute` distinguishes a dormant `FROZEN` payment from a legitimately-zero
   `sessionPrice` (pack-based booking, or no payment record) instead of folding both into the same silent
   `sessionPrice = ZERO` path.** Today (`DisputeService.java:169-173`) the `CAPTURED`-only filter treats
   any non-`CAPTURED` status — including `FROZEN` — as if no payment exists at all, and the existing
   `log.warn` at `:182` only fires inside the `FULL_CREDIT` branch, with wording ("pack-based or missing
   payment record") that doesn't mention `FROZEN` and would be actively misleading if the row's real
   status is `FROZEN`. Add a distinct check against the *unfiltered* payment lookup: if a `BookingPayment`
   row exists with status `FROZEN` (`BookingPaymentStatus.FROZEN`,
   `[src/main/java/com/softropic/skillars/platform/payment/contract/BookingPaymentStatus.java:33]`), log a
   clearly-labeled `WARN` naming the dispute id, booking id, and the fact that automated credit/refund is
   not possible for a `FROZEN` payment until FROZEN handling is designed (see AC8's new ledger item) —
   distinct from the existing pack-based-zero warning, and regardless of which `resolution` branch is
   taken (not just `FULL_CREDIT`). Do **not** change what credit amount is actually issued (still zero for
   a `FROZEN` row, per the explicit "don't redesign the guard" decision) — this AC is entirely about
   making the dormant gap loud instead of silent, not about changing money-movement behavior.
   `[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:156-227]`
   `RevenueReportingService.getCoachReceipt`/`getParentReceipt`
   (`[src/main/java/com/softropic/skillars/platform/payment/service/RevenueReportingService.java:150,182]`)
   already document their identical `CAPTURED`-only filter and behave correctly for the current 404 case;
   add the same distinguishing `WARN` there for consistency, with no change to the existing 404 response.

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
   is revoked, the next authorization charges again. This is gaming-resistant in the way the ledger item's
   own text asked to weigh: a viewer repeatedly re-triggering re-authorization within one token's lifetime
   cannot inflate the charge, and cannot evade it either (an unexpired active token means the viewer has
   already been charged for exactly this window). Write a new repository query method (e.g.
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

5. **A coach can raise a dispute on their own booking, not only the parent/player.**
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

6. **`coach_availability_windows.canonical_timezone` mirrors `coach_profiles.canonical_timezone` instead
   of being independently writable**, closing the drift `CoachProfileService.saveStep4` and
   `AvailabilityService` never reconcile. Product-directed decision (2026-08-24): windows should never
   diverge from the profile — the profile is the sole source of truth, not a deliberate per-window
   feature. `CoachProfileService.saveStep4`
   (`[src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:251]`)
   currently sets each new window's `canonicalTimezone` from the request payload
   (`w.canonicalTimezone()`); change it to `profile.getCanonicalTimezone()` instead, so every window a
   coach saves through Step 4 always carries the profile's own value. Leave the wire contract
   (`ProfileBuilderStep4Request.AvailabilityWindowRequest.canonicalTimezone`,
   `[src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep4Request.java]`)
   and its `@NotBlank @IanaTimezone` validation in place unchanged — the field is now write-only-and-
   ignored server-side rather than removed, avoiding a frontend contract change this story does not need
   to make; note this explicitly in a code comment at the `saveStep4` change site so a future reader
   doesn't think the field is still authoritative. Add a new migration backfilling existing diverged rows
   (single `UPDATE ... FROM`, no batching needed — availability windows are capped at 14 per coach by the
   request DTO's own `@Size(max = 14)`, so no table-scale concern applies here the way it did for
   `Def10`/`V98`'s much larger tables):
   ```sql
   UPDATE marketplace.coach_availability_windows w
   SET canonical_timezone = p.canonical_timezone
   FROM marketplace.coach_profiles p
   WHERE w.coach_id = p.id
     AND w.canonical_timezone != p.canonical_timezone;
   ```
   Next available migration id is `V102` (`V101__stripe_customer_id_format_guard_validate.sql` is the
   latest at story-creation time — confirm before writing the file in case a sibling story lands first).
   `AvailabilityService.updateWindow` (`[src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:240]`)
   does not accept a `canonicalTimezone` field on its own `UpdateWindowRequest` today — confirm this
   during implementation and leave it alone if so; `saveStep4` is the only write path that needs changing.

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
   - Flip the picked-up items above (AC1's `skillars-deferred-41` FROZEN item; AC2's
     `skillars-deferred-58` review `duplicateNextWeek`-SUSPENDED item; AC3's `skillars-deferred-40` review
     bandwidth-dedup item; AC4's `## Deferred from: skillars-deferred-30 story creation and review`-era
     late-parent-cancel/no-show product question — verify the exact current section heading and item text
     at implementation time, since this file is actively edited by concurrent story-creation passes; AC5's
     dispute-fairness gap — filed fresh by this story's own creation pass, since no existing ledger item
     named it explicitly before this discussion surfaced it; AC6's `skillars-deferred-17` review
     `canonical_timezone` item; AC7's `session-duration q-select` item) to `[CLOSED by
     skillars-deferred-63 ACn]` with a one-line closure note each, or delete outright per this file's own
     stated convention where the enclosing section has no other content.
   - Annotate (not delete — there is no code change, so nothing to "close") the `repDensity` item with
     this story's investigation finding: no live path constructs a `Drill` with coach-submitted metadata
     today, so the "coach never set this" scenario has no current trigger. See this story's own "Why this
     story exists" section above for the full finding to copy into the annotation.
   - File a **new** `deferred-work.md` item recording the payment-capture-timing/escrow question this
     story's creation surfaced and deliberately did not implement: today, `BookingPaymentPersistenceService
     .reserveCapture` captures payment at booking confirmation, well before the session occurs, and no code
     path gates coach payout on session completion or parent confirmation — Stripe Connect settles the
     already-captured charge independently. Moving to a "coach isn't paid until the parent confirms" model
     would be a genuine escrow/delayed-payout architecture change (not a bundled fix), needing its own
     design pass on: how long funds stay held, what happens if a parent never confirms and the
     `QuickCompleteTimeoutService` auto-completes on their behalf anyway
     (`[src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java:36-61]`),
     and how it interacts with the already-one-sided dispute system this story's AC5 only partially
     addresses (a coach can now raise a dispute, but there is still no coach-side rebuttal *before* an
     automatic no-show refund fires). Also leave open, explicitly not picked up by this story: extending
     `NO_SHOW_COACH` to fire from `IN_PROGRESS` (a coach could otherwise dodge a no-show claim just by
     pressing "start" without showing up) — deliberately declined by the project owner this round.

## Tasks / Subtasks

- [ ] Task 1: FROZEN fail-loudly guard (AC1)
  - [ ] 1.1: `DisputeService.resolveDispute` distinguishing WARN
  - [ ] 1.2: `RevenueReportingService.getCoachReceipt`/`getParentReceipt` distinguishing WARN
- [ ] Task 2: `duplicateNextWeek` SUSPENDED guard (AC2)
- [ ] Task 3: Bandwidth dedup (AC3)
  - [ ] 3.1: New `PlaybackTokenRepository` query method
  - [ ] 3.2: Wire the check into `authorizePlayback` before the quota-increment call
  - [ ] 3.3: Test coverage: same-viewer-same-video re-authorization within an active token's TTL does not
        double-charge; a second, distinct viewer of the same video still charges independently; charging
        resumes once the prior token has expired/been revoked
- [ ] Task 4: No-show time guard (AC4)
- [ ] Task 5: Coach dispute-raising capability (AC5)
  - [ ] 5.1: `DisputeService.raiseDispute` `ownerEligible` widening
  - [ ] 5.2: `DisputeResource.resolveCurrentRole()` `COACH` branch
- [ ] Task 6: Timezone reconciliation (AC6)
  - [ ] 6.1: `CoachProfileService.saveStep4` change
  - [ ] 6.2: `V102` backfill migration (confirm next available id before writing)
  - [ ] 6.3: Confirm `AvailabilityService.updateWindow` has no separate timezone write path to fix
- [ ] Task 7: Session-duration dropdown defensive fix (AC7)
- [ ] Task 8: Ledger hygiene (AC8)

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

**AC6 leaves the frontend's per-window timezone field in the wire contract, deliberately unused.**
Removing it from `ProfileBuilderStep4Request` would be a breaking contract change requiring a coordinated
frontend edit this story does not need — the backend simply stops trusting the value. A future story can
clean up the now-dead frontend field/picker if one exists; not scoped here (confirm during implementation
whether the frontend even exposes a per-window timezone picker distinct from the profile-level one — if
it doesn't, there is nothing to note beyond the code comment this AC already asks for).

**AC7 is the lowest-value item in this bundle by a wide margin** (unreachable via any current UI path,
confirmed during this story's creation) — implement it last, and do not let it block or complicate the
other six ACs' review.

**Existing test files to extend (do not create new ones for these classes):**
`DisputeServiceTest.java` (`platform.admin.service`, AC1/AC5), `RevenueReportingServiceTest.java`
(`platform.payment.service`, AC1), `BookingDuplicationServiceTest.java` (`platform.booking.service`,
AC2), `PlaybackServiceTest.java` (`platform.video.service`, AC3), `BookingServiceTest.java`
(`platform.booking.service`, AC4). AC5's endpoint-level coverage belongs in
`DisputeSubmissionIT.java` (`platform.admin.api`) — there is no `DisputeResourceIT`, don't create one.
AC6's coverage belongs in `CoachProfileBuilderIT.java` (`platform.marketplace.api`) — confirm it already
exercises `saveStep4` before deciding whether a new test is needed. AC7 is frontend-only; this codebase
has no frontend test runner (a long-standing, deliberately-accepted gap — do not add one for this story).

### Project Structure Notes

Backend: `platform.admin.service` (DisputeService), `platform.payment.service`
(RevenueReportingService), `platform.booking.service` (BookingDuplicationService, BookingService),
`platform.booking.contract` (BookingError), `platform.video.service`/`platform.video.repo` (PlaybackService,
PlaybackTokenRepository), `platform.admin.api` (DisputeResource), `platform.marketplace.service`
(CoachProfileService), a new `V102` migration. Frontend: `ProfileBuilderStep3.vue` only.

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

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Description |
|------|-------------|
| 2026-08-24 | Story created via story-creation process, bundling seven items the project owner explicitly decided across a multi-round product-decision discussion (2026-08-24), breaking from this series' usual decision-light-only convention. An eighth decided item (`jakarta.persistence.lock.timeout`) was split out to its own story (`skillars-deferred-62`) for being too large to bundle safely. A ninth candidate (`DrillMetadata.repDensity`) was investigated and found to have no live trigger — annotated in `deferred-work.md`, not picked up as an AC. A tenth item (payment-capture/escrow timing) surfaced during the discussion and was filed as a brand-new `deferred-work.md` item rather than implemented. |
