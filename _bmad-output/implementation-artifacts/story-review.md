# Story Review: skillars-deferred-63-product-directed-fairness-and-consistency-fixes

Reviewer: senior-dev audit (pre-implementation), 2026-08-24. Scope: does the story as written
actually deliver what each AC claims, against the current state of the codebase (not the state
assumed when the story was written). Every finding below was verified by reading the actual
current source, not inferred from the story text alone. Findings that couldn't be confirmed
against real code were dropped rather than included as speculation.

## Summary

Five substantive findings. Two (#1, #4) mean an AC as literally specified will not deliver the
outcome its own rationale claims. One (#2) is a real concurrency gap that undercuts an explicit
"gaming-resistant" claim in the AC text. One (#3) is a scope inconsistency worth a product call
before implementation, not after. One (#5) is a ledger-traceability error that would cause AC8 to
incorrectly retire an unrelated, still-unanswered product question. Everything else in the story —
line citations, "unreachable via UI" claims, migration numbering, i18n file coverage, state-machine
transition claims — was checked against the current codebase and holds up.

---

## Finding 1 — AC5 doesn't actually let a coach contest an existing dispute (HIGH confidence, HIGH value)

**Claim in story:** "A coach can raise a dispute on their own booking, not only the parent/player,"
motivated by e.g. "a coach can dispute a no-show claim raised against them."

**What the code actually does:** `DisputeService.raiseDispute` (`DisputeService.java:65-101`) widens
`ownerEligible`, but unconditionally still calls, after the eligibility checks:

```java
disputeRepository.findOpenByBookingId(bookingId).ifPresent(d -> {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "disputes.alreadyRaised");
});
```

`findOpenByBookingId` (`DisputeRepository.java:12-18`) is a plain `bookingId` lookup with no
`raisedBy` filter — it returns *any* open dispute on the booking, regardless of who raised it. So:
if a parent has already filed a `Dispute` on a booking (e.g. `SESSION_QUALITY`, `UNAUTHORISED_CHARGE`
— both already-valid reasons), the coach's newly-unlocked ability to raise their own dispute on that
same booking 409s immediately with `disputes.alreadyRaised`. The coach cannot "contest" it through
this endpoint at all.

Separately, even in the cases where the coach's call *would* succeed (no prior `Dispute` row exists —
which is actually the common case for a `NO_SHOW_COACH` claim, since `recordNoShowCoach` transitions
the booking status directly and creates no `Dispute` row), the coach still cannot read the dispute a
parent already filed: `getDispute` (`DisputeService.java:107-120`) 403s unless
`dispute.getRaisedBy().equals(requesterId)`. There is no admin-detail-equivalent view a non-raiser can
reach. So even where AC5's widened `ownerEligible` check would pass, a coach has no way to see the
substance of a dispute they didn't personally file, to respond to it with any specifics.

**Net effect:** AC5 delivers "a coach may open their own, first, independent dispute on a booking with
no existing open dispute" — not "a coach can contest/rebut a dispute a parent already raised." The
story's own framing ("so that ... a coach's ability to contest a dispute", the AC5 prose citing
"dispute a no-show claim raised against them") oversells what ships. AC8's ledger note ("there is
still no coach-side rebuttal before an automatic no-show refund fires") acknowledges a *related*
gap but doesn't mention this specific conflict/visibility mechanism, so a reader would reasonably
believe AC5 alone gets a coach partway there. It doesn't, for the case where a parent has already
filed a formal dispute.

**Recommendation:** Either scope AC5 explicitly to "first dispute wins, symmetric raise-only right,
no contest capability" (accurate but should be stated, not implied), or extend `findOpenByBookingId`
in this same AC to permit a second, opposite-side dispute per booking (which has its own design
questions — do two open disputes on one booking need to be resolved together? does the DTO/admin UI
support that?). Flag for product either way before implementation, since this changes what AC5
is actually worth building.

---

## Finding 2 — AC3's dedup check has no locking, so its own "cannot inflate the charge" claim is false under concurrency (MEDIUM-HIGH confidence)

**Claim in story:** "This is gaming-resistant... a viewer repeatedly re-triggering re-authorization
within one token's lifetime cannot inflate the charge."

**What the code actually does:** `authorizePlayback` (`PlaybackService.java:53-160`) is a plain
`@Transactional` method (default `REQUIRED` propagation, no pessimistic lock). The AC3 design is:
query `PlaybackTokenRepository` for an existing non-revoked, non-expired token for
`(viewerId, videoId)` before charging; skip the charge if found. `playback_tokens`
(`V17__playback_tokens.sql`) has only a non-unique index on `(viewer_id, revoked_at)` — no unique
constraint or exclusion constraint backs the dedup rule at the database level, unlike the pattern this
codebase uses elsewhere for exactly this class of problem (e.g. `PessimisticLockRetryer` +
`findByIdForUpdate` in `BookingDuplicationService`/`RescheduleService`/`CoachProfileService.saveStep4`
for coach-row serialization; V87's exclusion constraint as a commit-time backstop for booking
overlaps).

Two concurrent `authorizePlayback` calls for the same `(viewerId, videoId)` — e.g. two browser tabs,
or a client retry racing the original request — will both execute the "does an active token already
exist" check before either has committed its own new `PlaybackToken` row. Both see "no active token,"
both proceed to `quotaService.incrementBandwidthUsedBytes`, both charge. This is exactly the
"re-triggering re-authorization to inflate... usage" scenario the AC's own rationale claims is closed.

**Note on severity:** the practical blast radius is small (double-charging bandwidth quota by one
extra `storageBytes` per race, not a money-movement or double-booking bug), and Task 3.3's test list
only covers the *sequential* case (re-auth within TTL doesn't double-charge), so a green test suite
would not surface this. Worth a decision: accept as a known, low-stakes gap (and correct the "cannot
inflate" claim to say so), or add a lock/`SELECT ... FOR UPDATE` scoped by `(viewer_id, video_id)`
before the exists-check, mirroring this codebase's established pattern elsewhere.

---

## Finding 3 — AC1 fixes the silent-zero bug for `FROZEN` only, leaving the same bug live for `CAPTURE_PENDING` and `CHARGE_FAILED` (MEDIUM confidence, worth a product call)

**Claim in story:** AC1 distinguishes a dormant `FROZEN` payment from a legitimately-zero
`sessionPrice`, because the existing `CAPTURED`-only filter folds "every non-`CAPTURED` status" into
the same silent zero.

**What's true:** `BookingPaymentStatus` (`BookingPaymentStatus.java`) defines exactly four statuses:
`CAPTURE_PENDING`, `CAPTURED`, `CHARGE_FAILED`, `FROZEN`. `FROZEN` is genuinely dormant — grep
confirms no code path in `src/main/java` ever sets it, matching the story's own investigation. But
`CAPTURE_PENDING` and `CHARGE_FAILED` are *not* dormant — both are live, actively-written statuses
(`BookingPaymentPersistenceService.java:102,219,287`, `PaymentPendingSweeper.java:163`), part of the
UAT.3 async-capture design where a `booking_payments` row can exist before money has actually moved,
or after a capture attempt has definitively failed. `RevenueReportingService.getCoachReceipt`'s own
comment acknowledges this directly: "a `booking_payments` row may now exist BEFORE the money moves."

If a booking with a lingering `CAPTURE_PENDING` row (per `PaymentLifecycleService`'s own docs, this
can persist when "a prior attempt died mid-capture" and needs manual reconciliation) or a
`CHARGE_FAILED` row ever reaches a dispute-eligible status (`COMPLETED`, `CANCELLED*`, `NO_SHOW_*`),
`resolveDispute`/`getCoachReceipt`/`getParentReceipt` hit the exact same silent-`sessionPrice=0`
trap AC1 is fixing for `FROZEN` — except the existing generic warning text ("pack-based or missing
payment record") stays just as misleading for these two statuses as it currently is for `FROZEN`,
because AC1 as scoped only special-cases `FROZEN`.

**Recommendation:** Either broaden AC1's distinguishing check to "any `BookingPayment` row exists
with a non-`CAPTURED` status" (naming the actual status in the WARN, not hardcoding `FROZEN`-only
logic) — which is a small, mechanical widening of the same fix, not new design — or get an explicit
product sign-off that `CAPTURE_PENDING`/`CHARGE_FAILED` reaching a disputable booking is considered
unreachable/acceptable-as-is, the same way `FROZEN`'s dormancy was explicitly investigated and
recorded. Right now the story investigated only one of the three non-`CAPTURED` cases before scoping
the fix.

---

## Finding 4 — AC6 silently defeats an existing, explicit frontend Step 4 timezone picker (HIGH confidence, HIGH value)

**Claim in story (Dev Notes):** "confirm during implementation whether the frontend even exposes a
per-window timezone picker distinct from the profile-level one — if it doesn't, there is nothing to
note beyond the code comment this AC already asks for."

**What's actually there:** It does exist, prominently.
`src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:66-68` renders its own
`TimezoneSelect` under a dedicated "Timezone" section, with helper copy (`step4TimezoneHelper`,
present in all three locale bundles) that reads: **"Windows above are interpreted in this
timezone."** It defaults to whatever Step 1 chose (`store.selectedTimezone`) but is fully editable,
and the component's own code comment is explicit that this is deliberate: changing it to auto-sync
from the store "would silently overwrite a per-window zone the coach had deliberately chosen here."
The submitted payload applies this one value to every window
(`ProfileBuilderStep4.vue:137-142`).

AC6's backend change makes `saveStep4` ignore this value entirely and force
`profile.getCanonicalTimezone()` instead. After AC6 ships as scoped, a coach who deliberately picks a
different timezone on this screen — which the UI's own helper text invites them to do — has that
choice silently discarded server-side with no client-side signal. The screen's copy ("Windows above
are interpreted in this timezone") becomes actively false the moment the coach's Step 4 selection
differs from their Step 1 selection. This is worse than the pre-fix state: before, the divergence was
a backend data-consistency bug invisible to the coach; after, it's a UI element that visibly lies
about what it does.

The story's own scope boundary ("Leave the wire contract... in place unchanged... avoiding a frontend
contract change this story does not need to make") is reasonable *if* the picker doesn't exist or is
inert — but the Dev Notes' own conditional ("if it doesn't [exist], there is nothing to note") shows
the story never actually resolved this before scoping the AC, and the answer is the branch the story
didn't plan for.

**Recommendation:** This needs an explicit decision before implementation, not a "confirm during
implementation" deferral: either (a) also update `ProfileBuilderStep4.vue` to drop the picker (or
replace it with read-only display of the profile's timezone + a "change it in Step 1" link) as part
of this same story, since shipping AC6 without it produces a materially misleading screen, or (b)
scope AC6 down to "backfill migration only, no `saveStep4` behavior change" until a frontend fix can
be bundled. Silently accepting the current Dev Notes framing as written will ship a UI regression.

---

## Finding 5 — AC8's ledger closure for AC4 references the wrong item, and would incorrectly retire an unanswered product question (HIGH confidence)

**Claim in story (AC8):** "AC4's `## Deferred from: skillars-deferred-30 story creation and review`
-era late-parent-cancel/no-show product question... to `[CLOSED by skillars-deferred-63 AC4]`."

**What's actually in `deferred-work.md`:** The item AC4 is presumably referring to (its text matches
almost exactly) is filed under a *different* heading, `## Deferred from:
skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test story
creation (2026-08-17)` (line 1311), not `skillars-deferred-30`. That's a citation error the story's
own hedge ("verify the exact current section heading... at implementation time") anticipates and
should catch — but there's a bigger problem underneath it than a wrong heading.

The actual ledger item's product question is: **"should a parent cancelling a booking after its
session start time has already passed settle as a coach no-show instead of an ordinary
`CANCEL_PARENT`?"** — i.e., should `BookingService.cancelBookingAsParent` auto-convert a late cancel
into a `NO_SHOW_COACH` event. That question is about the **cancel** path.

AC4, as actually specified and implemented, adds a guard to `recordNoShowCoach` — a *different*,
already-existing endpoint where a parent explicitly and separately reports a no-show — rejecting a
claim made before `requestedStartTime`. This is a legitimate, well-scoped fix for a real gap (a
parent could report "coach didn't show" before the session was even due to start), but it does not
touch `cancelBookingAsParent` at all, and does not answer the ledger item's actual question (should a
late *cancellation* auto-become a no-show). The `deferred-work.md` entry already carries a
`[PICKED UP by skillars-deferred-63 AC4 (time guard only; IN_PROGRESS extension explicitly
declined)]` annotation as of story-creation time, which itself already conflates the two — but "time
guard only" describes AC4's actual fix (on `recordNoShowCoach`), not a resolution of the ledger
item's actual cancel-vs-no-show question.

If AC8 is executed as written, whoever closes the ledger will mark the "should late-cancel become a
no-show" product question `[CLOSED by skillars-deferred-63 AC4]` — but that question was never
decided or implemented. It will silently vanish from the backlog, indistinguishable from an item that
was actually resolved, even though `cancelBookingAsParent`'s unconditional-transition behavior
(`BookingService.java:611-661`) is completely unchanged by this story.

**Recommendation:** Before executing AC8, split this into two ledger actions: (1) file a *new* item
(or note under the existing one) for the specific gap AC4 actually closes — premature
`recordNoShowCoach` claims — and mark that closed; (2) leave the original "late `CANCEL_PARENT` →
auto no-show?" product question open under its correct `skillars-deferred-28` heading, since it
remains genuinely undecided.

---

## Items checked and found accurate (no finding)

To keep this review honest about what *isn't* a problem, these specific claims in the story were
independently verified against current source and hold up:

- AC1: `FROZEN` is genuinely dormant — no write site anywhere in `src/main/java`. `resolveDispute`'s
  existing `log.warn` is genuinely `FULL_CREDIT`-branch-only, confirmed at
  `DisputeService.java:182`.
- AC2: `CoachProfileStatus.SUSPENDED` exists as claimed; `RescheduleService.acceptReschedule`'s
  precedent check (`RescheduleService.java:219-222`) matches the story's description exactly, same
  `BookingError.COACH_UNAVAILABLE` code.
- AC4: the `NO_SHOW_COACH` transition is only reachable from `UPCOMING`
  (`BookingStateMachine.java:48`), not `IN_PROGRESS` — confirms both the core bug claim and the
  correctness of the "out of scope" `IN_PROGRESS` boundary. `recordNoShowCoach` has exactly one
  caller in Java (`CancellationResource.java:53`, parent-only) and zero frontend call sites beyond
  the unused API wrapper — confirmed by grep.
- AC5: `booking.getCoachId()` is genuinely a `CoachProfile` UUID requiring the profile hop described;
  `ELIGIBLE_STATUSES`/`VALID_REASONS` genuinely need no change; `DisputeResource.resolveCurrentRole()`
  genuinely hardcodes `PARENT`/`PLAYER` with no `COACH` branch today.
- AC6: `AvailabilityService.updateWindow` genuinely never touches `canonicalTimezone` (confirmed —
  only `dayOfWeek`/`startTime`/`endTime` are set); `AvailabilityService.addWindow` already correctly
  sources it from `profile.getCanonicalTimezone()`, so `saveStep4` is genuinely the only write site
  needing the fix. `V101` is genuinely the latest migration, so `V102` is correctly the next id.
- AC7: `sessionDurationMinutes` genuinely appears in exactly one frontend file; the profile-builder
  flow genuinely never hydrates an existing value (`form.sessionDurationMinutes` starts `null` and
  there's no fetch-and-populate path) — the "unreachable via UI" claim holds.
- i18n: all four bundles (`messages.properties`, `messages_en/_de/_fr.properties`) carry an identical
  14-entry `booking.*` key set today, so the new `booking.noShowTooEarly` key genuinely needs adding
  to all four with no pre-existing divergence to account for.
