# Senior Dev Review: skillars-deferred-41 (Dispute Payment-Status Guard, Feature-Gate Misconfiguration Metrics, Session-Pack Coach Filter & Day-Name Localization)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-41-dispute-payment-guard-feature-gate-metrics-session-pack-coach-filter-and-day-name-localization.md`

Method: every factual claim was re-verified against current code, not taken on the story's word. Read in
full: `DisputeService.java`, `RevenueReportingService.java`, `BookingStateMachine.java`, `BookingStatus.java`,
`BookingPayment.java`/`BookingPaymentStatus.java`, `BookingPaymentPersistenceService.java`,
`BookingService.cancelBookingAsParent`, `ConfigService.java`, `DrillLibraryService.java`,
`DrillUploadService.java`, `PaymentPendingSweeper.java`, `ShedLockConfig.java`,
`SessionPackPaymentService.java`, `SessionPackPurchaseRepository.java`, `QuotaReservationTimeoutService.java`,
`AccountManagementFacade.java`, all four target `.vue` files, and every test file the story names as a
manual-construction or fixture site. All counter names were grepped repo-wide for collisions. AC1's central
safety claim was chased through the full booking/payment state machine, including the specific crash-recovery
scenario `BookingStateMachine.java:30-33`'s own comment describes, to check whether a non-`CAPTURED` payment
row can actually reach an eligible dispute today — it cannot, because `BookingService.cancelBookingAsParent`
(lines 645-655) independently refuses to cancel a `PAYMENT_PENDING` booking while a `CAPTURE_PENDING` row
exists, closing off the only reachable path. That claim holds.

Every line number, code excerpt, test-fixture claim, and ledger citation in AC1–AC4 checked out exactly against
the current repo — an unusually high hit rate for this kind of story. The one real, confirmed problem is in
AC5: its ledger-hygiene task list describes work that has already been fully applied, at story-creation time,
in the same commit that created this story file.

---

## Finding 1 (Medium, confirmed): AC5's ledger-hygiene task list (Task 5) describes work that is already 100% complete — the story's own creation commit already applied every tag it asks for

**Where:** AC5, Task 5 (5.1–5.5), and the "Status: ready-for-dev" / unchecked `- [ ]` checkboxes on Task 5.

`deferred-work.md`'s current content — already committed in this story's own creation commit (`944f642`,
`git show 944f642 -- _bmad-output/implementation-artifacts/deferred-work.md`) — contains every single tag AC5
and Task 5 describe as still-to-do:

- `[PICKED UP by skillars-deferred-41 AC1]` already sits on both D5 (`deferred-work.md:1299`) and D18
  (`deferred-work.md:1345`).
- `[PICKED UP by skillars-deferred-41 AC2]` already sits on the `ConfigService.getBoolean` item
  (`deferred-work.md:1410`, under `## Deferred from: code review of skillars-deferred-21-...`) and the
  fully-misconfigured-gate item (`deferred-work.md:1420`, under `## Deferred from: code review of
  skillars-deferred-22-...`).
- `[PICKED UP by skillars-deferred-41 AC3]` already sits on the in-memory-`coachId`-filter item
  (`deferred-work.md:834`).
- `[PICKED UP by skillars-deferred-41 AC4]` already sits on the `skillars-uat-4` D2 hardcoded-weekday item
  (`deferred-work.md:1351`).
- The three complete `[STALE — verified against current code by skillars-deferred-41 story creation,
  2026-08-20: ...]` annotations — full reasoning text, not a placeholder — are already appended verbatim to
  Def1 (`deferred-work.md:1053`), D2/`skillars-deferred-4` (`deferred-work.md:1150`), and Def16
  (`deferred-work.md:1068`), matching AC5's specified text word-for-word.

So there is nothing left in `deferred-work.md` for Task 5 to change. Two concrete problems follow for whoever
picks this story up next:

1. **Task 5's five unchecked `- [ ]` boxes, and "Status: ready-for-dev," both present this as pending work.**
   A dev (or an automated `dev-story` run) following the task list literally has nothing to do here — at best
   this is a no-op that wastes a verification pass; at worst, a naive patch/diff step that expects to find the
   *untagged* original line (to append a tag to it) will fail to match, since the line already carries the tag
   in the position the story's own instructions describe adding it to.
2. **This is not this project's established convention.** Grepping the entire 1599-line ledger for `PICKED UP
   by` turns up exactly these six lines — every one of them from this story's own creation commit. No other
   story anywhere in this ledger's history pre-applies a `[PICKED UP by ...]` tag at story-creation time; the
   established pattern (visible everywhere else in the file) is to tag `[CLOSED by ...]` retroactively, after
   `dev-story` actually ships the fix. Doing the tagging up front, before any of AC1–AC4's code has been
   written, risks leaving the ledger permanently misleading (items marked "picked up" with no corresponding
   code change) if this story is abandoned or paused after creation but before `dev-story` runs.

**Recommendation:** Either (a) check off Task 5.1–5.5 now and add a Dev Notes line stating AC5's ledger
hygiene was already completed as part of story creation, so `dev-story` knows to skip it and only verify, or
(b) if the tags were applied prematurely, revert them in `deferred-work.md` until AC1–AC4's code actually
ships, and let `dev-story` apply them at completion time per the codebase's established after-the-fact
convention.
