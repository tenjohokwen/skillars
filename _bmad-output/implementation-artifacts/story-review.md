# Senior Dev Review: skillars-deferred-31

Target: `_bmad-output/implementation-artifacts/skillars-deferred-31-coach-accept-flow-refresh-reschedule-error-split-and-slu-repository-coverage.md`

Method: every citation in the story (file:line, code snippets, "verified by grep" claims) was
independently re-checked against the current working tree — not taken on the story's word — by
reading the actual throw sites, catch blocks, i18n bundles, migrations and `deferred-work.md`
entries it references. Findings below are only the ones that survived that check; several
initially-suspicious items (e.g. the `payment.sessionPack.expired` vs. `packExpired` near-duplicate
key, the batch-status transactional boundary, the "eight" vs. AC1's per-page bullets) were
investigated and ruled out or downgraded — see the Non-Findings section at the end.

## Finding 1 (HIGH) — AC3's RescheduleService table miscounts the throw sites: one real site is missing entirely, and a different one is mislabeled

**Claim being checked:** AC3 says *"`RescheduleService` throws `SecurityError.MISSING_RIGHTS` at
nine sites, only two of which are genuine authorization failures"* and gives a 10-row table (2 keep
+ 8 recode) mapping each site to a line number and target error code. Task 3 confirms: *"Re-code the
eight non-authz throw sites per AC3's table."*

**What the code actually has** (`RescheduleService.java`, current working tree):

| Line | Method | Condition | Story's table row |
|---|---|---|---|
| 58 | `requestReschedule` | parent doesn't own booking | `:58` — keep MISSING_RIGHTS ✓ |
| 61-63 | `requestReschedule` | not CONFIRMED/UPCOMING | `:61-62` — BOOKING_NOT_RESCHEDULABLE ✓ |
| 64-67 | `requestReschedule` | start not in future | `:65-66` — START_TIME_IN_PAST ✓ |
| 68-71 | `requestReschedule` | end not after start | `:69-70` — INVALID_TIME_RANGE ✓ |
| 94-98 | `requestReschedule` | pending reschedule exists | `:96-97` — RESCHEDULE_ALREADY_PENDING ✓ |
| 129 | `acceptReschedule` | coach doesn't own booking | `:129` — keep MISSING_RIGHTS ✓ |
| 137-140 | `acceptReschedule` | not PENDING (unlocked pre-check) | `:138-139` — RESCHEDULE_NOT_PENDING ✓ |
| 141-144 | `acceptReschedule` | booking not reschedulable | `:142-143` — BOOKING_NOT_RESCHEDULABLE ✓ |
| 145-148 | `acceptReschedule` | start no longer in future | `:146-147` — START_TIME_IN_PAST ✓ |
| **164-167** | **`acceptReschedule`** | **not PENDING (locked re-check, post-refresh)** | Story's table lists this exact line range (`:165-166`) but labels it **"(decline path) request is not PENDING"** — it is not the decline path; it's the *second* PENDING check inside `acceptReschedule`, four lines after the first one. |
| **223-225** | **`declineReschedule`** | **coach doesn't own booking** | **Not in the table at all.** A third genuine-authz "coach does not own this booking" throw, structurally identical to the two rows marked "keep." |
| **236-238** | **`declineReschedule`** | **not PENDING** | **Not in the table at all** — this is the actual decline-path "not PENDING" throw the table's `:165-166` row claims to describe, but no row cites its real line number (236-238). |

So the file has **12** `MISSING_RIGHTS` throw sites (3 genuine authz, 9 to recode), not the "nine
sites, two genuine" the AC3 prose claims (whose own table, counted literally, only has 10 rows —
already inconsistent with its own "nine" and with Task 3's "eight non-authz sites"). Two real sites
are absent from the table outright (the `declineReschedule` ownership check and its real "not
PENDING" throw), and the line number given for "(decline path) request is not PENDING" actually
points at an unrelated throw site inside `acceptReschedule`.

**Why this matters for the dev doing the work:** the frontend half of AC3 correctly anticipates a
`booking.rescheduleNotPending` branch on `handleDeclineReschedule` (Task 3: "branches in ...
`handleDeclineReschedule`"), so the *intent* to fix decline's mapping is present — but a dev
mechanically following the backend table's line citations will recode `acceptReschedule`'s second
PENDING check (harmless — it needed `RESCHEDULE_NOT_PENDING` too) while never being pointed at
`declineReschedule:236-238`, the throw site the frontend fix is actually for. The two target codes
happen to coincide (`RESCHEDULE_NOT_PENDING` either way), which is the only reason this doesn't
silently produce a wrong mapping — but nothing in the story tells the dev to open
`declineReschedule` and look, and `RescheduleResourceIT` currently has zero `errorKey`/
`MISSING_RIGHTS` assertions (`grep -n "errorKey\|MISSING_RIGHTS" RescheduleResourceIT.java` returns
nothing), so there is no test safety net that would catch the throw site being missed. The
`declineReschedule` ownership check (223-225) being absent from the table doesn't cause a wrong
recode (it's correctly left as MISSING_RIGHTS by default, since it's not listed to change) — but it
means the story's own audit trail is wrong about how many genuine-authz sites exist, which is exactly
the kind of miscount this story's stated methodology ("reading the throw sites... directly rather
than trusting the ledger's own citations") was supposed to catch.

**Suggested fix before dev starts:** add two rows to AC3's table — `declineReschedule:223-225` "keep
MISSING_RIGHTS (genuine authz, third site)" and `declineReschedule:236-238` "not PENDING →
RESCHEDULE_NOT_PENDING" — and correct `:165-166`'s row to describe what it actually is (the second,
locked-recheck PENDING throw inside `acceptReschedule`, not the decline path). Update "nine sites,
two genuine" to "twelve sites, three genuine" and "eight non-authz" to "nine non-authz" throughout.

## Finding 2 (MEDIUM) — AC1's success-path stale-check has no concrete anchor point for two of the three CoachBookingRequestsPage handlers

**Claim being checked:** AC1 requires: *"Add the stale check on the success paths too — a silent
refresh failure after a successful accept is the more likely case."* Task 1's per-handler bullets are
explicit about *where* to add the failure-path check (e.g. "move `await loadCoachBookingRequests()`
above the toast; after it, if... add the stale warning") but the success-path instruction is one
generic bullet with no per-handler location.

**What the code actually looks like:** `handleAccept` and `handleDecline`
(`CoachBookingRequestsPage.vue:151-180`) have **no success-path code at all** today — no notify, no
statement between the successful `await bookingStore.approveBooking(id)` and the `finally` block.
The refresh that happens on success is buried a level down, inside the store's `approveBooking`/
`rejectBooking` (`booking.store.js:348-356`), which the page never sees return a value it can key
off. A dev implementing this AC has to invent a code location — insert an `if
(bookingStore.coachRequestsError)` check directly after the successful `await`, with no existing
notify or refresh call at that point to model it on. `handleAcceptAll` and both
`CoachCommandCenterPage` handlers are unambiguous by contrast: they already have an explicit
success-path refresh + notify block to attach the check to (e.g.
`CoachCommandCenterPage.vue:375-377`). This isn't a false claim in the story — the requirement is
correct and the fix is genuinely simple (one `if` block) — but the task breakdown's asymmetry (fully
specified for failure paths, generic for success paths) is exactly the kind of gap that produces an
inconsistent implementation across the three flows the AC is trying to unify. Worth a one-line
addition to Task 1 naming the insertion point for `handleAccept`/`handleDecline` explicitly, the same
way it does for the failure paths.

## Non-findings (checked, not defects)

- **`approveBooking`/`rejectBooking` lack their own try/catch in the store.** Verified this is
  intentional and correct as-is: `acceptBooking(id)`/`declineBooking(id)` are unguarded so a mutation
  failure propagates to the page's catch, while the *refresh* call one line later
  (`loadCoachBookingRequests()`) never throws — so the net behavior matches what AC1 describes.
  Nothing to fix here beyond AC1's own scope.
- **`payment.sessionPack.expired` vs. the new `payment.sessionPack.packExpired`.** These are
  different, pre-existing keys with overlapping subject matter (`expired` has a `{date}` param and is
  currently unused anywhere in `src/frontend/src`; `packExpired` is the one AC4 relocates). Confirmed
  via grep that `expired` has zero call sites today. This is a pre-existing dead-key oddity, not
  something AC4 creates or worsens, and AC4's own convention rule doesn't need to resolve it. Not
  flagged as a story defect — mentioned only in case a reviewer wonders about the naming overlap.
  Out of this story's scope.
- **AC2's `throw` replacing `return` inside `acceptedIds.isEmpty()`.** Checked whether this could
  interact badly with the per-booking `REQUIRES_NEW` transactions or the trailing transaction: it
  can't — the branch sits before any write in the enclosing `@Transactional` method, and every
  per-booking accept already committed (or rolled back) independently via `perBookingTx`. Throwing
  here rolls back nothing that matters. Consistent with the Dev Notes' preserved-invariant guidance.
- **AC5's repository query has no `skill_code` filter**, so `findByPlayerIdFromWeek` returns rows
  across every skill for a player in the date range — checked both production callers
  (`SluDashboardService.java:49`, `SluNarrativeService.java:45`) and both already treat the result as
  multi-skill-code by design (grouping/mapping by skill after the fetch). AC5's required fixture rows
  don't need multiple skill codes to prove the year/week predicate, so this isn't a gap in the new
  IT's design — noted only because it's a detail the AC doesn't call out, not because it's wrong.
- **AC7's withdrawal of the `CoachBookingRequestsPage.vue:164` ledger item.** Independently confirmed
  against both the current store code (`booking.store.js:302-314`, catches with no rethrow) and the
  ledger entry itself (`deferred-work.md:1519`), which matches the story's characterization of it
  word-for-word. The withdrawal is correct.
- **AC2/AC5/AC6's file:line citations, i18n bundle anchors (`en-US/index.js:924`, `:1046`,
  `:1068-1069`), and the V48/V46 migration claims** (`player_id` has no FK; `skill_code` FKs to
  `skill_definitions`; `PAC`/`SHO` are seeded by V46) — all independently verified against the actual
  files and match exactly.

## Summary

The story is unusually well-verified — nearly every citation checked out exactly against current
code, and the withdrawal of the false `:164` ledger item is correct and well-evidenced. The one
substantive problem is Finding 1: AC3's RescheduleService throw-site table is short two real sites
and has mislabeled a third, which — combined with `RescheduleResourceIT` currently asserting nothing
on these codes — creates a real risk that `declineReschedule`'s actual "not PENDING" throw site never
gets touched, discovered only because the frontend task bullet for `handleDeclineReschedule` happens
to name the right target key anyway. Recommend fixing AC3's table before dev-story starts. Finding 2
is a minor task-breakdown gap, not a factual error, and can be fixed with one added sentence in Task 1.
