---
title: 'Expose coach booking cancellation and no-show reporting in the UI'
type: 'feature'
created: '2026-10-14'
status: 'done'
context: []
baseline_commit: 'c873fb6c56f295ba4b1da533b95a50f8bc3eb8eb'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The backend already implements `POST /api/bookings/{id}/coach-cancel` and `POST /api/bookings/{id}/no-show-player` (`CancellationResource.java:35,43`), and the frontend already has API client functions, a fully built reason-capture modal (`CancelCoachModal.vue`), and complete `cancellation.*` i18n copy in all three locales — but nothing calls any of it. A coach has no way to cancel a confirmed booking or report that a player didn't show up.

**Approach:** Wire the existing pieces together on `CoachCommandCenterPage.vue` (the coach's confirmed/upcoming schedule view): add two conditional action buttons per booking that trigger the already-built modal (cancel) and a new simple confirm dialog (no-show), following the exact thin-wrapper + component-driven-refresh pattern already used there for reschedule actions.

## Boundaries & Constraints

**Always:** Reuse `CancelCoachModal.vue`, the existing `coachCancelBooking`/`recordNoShowPlayer` API functions, and the existing `cancellation.*` i18n keys as-is. Mirror `handleAcceptReschedule`'s thin-wrapper store pattern (no internal refresh) and its try/catch/finally + `notifyIfScheduleStale` + errorKey-branching shape in the page. Gate "Cancel Session" to `['CONFIRMED','UPCOMING'].includes(booking.status)` and "Report No-Show" to `booking.status === 'UPCOMING'` — these are the backend's valid state-machine source states intersected with what `getCoachWeekSchedule` actually returns. Add any new i18n key to all three locales (en-US, de-DE, fr-FR).

**Ask First:** If a reviewer wants a reliability-strike warning added inside the cancel-reason modal — confirm before touching `CancelCoachModal.vue`, which is out of scope here (see Never).

**Never:** Do not modify `CancelCoachModal.vue`. Do not add a client-side "too early" guard on no-show reporting — the backend intentionally has none for this endpoint (unlike the parent-facing `recordNoShowCoach`), and adding one would be an unrequested product decision. Do not touch `CoachBookingRequestsPage.vue` (pending-request inbox) or any parent-facing flow. Do not invent a frontend test harness/convention — none exists in this project (0 `*.spec.js` files under `src/frontend`); rely on lint + manual check.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Cancel a CONFIRMED/UPCOMING booking, excused reason | status=CONFIRMED, reason=HEALTH_MEDICAL | 204; schedule refreshes; success toast `cancellation.coachCancelSuccess` | N/A |
| Cancel with unexcused reason | reason=OTHER_UNEXCUSED or SCHEDULING_PREFERENCE | 204; same success path (backend silently strikes the coach server-side) | N/A |
| Cancel races a completion | backend 403 `booking.alreadyCompleted` | — | toast `cancellation.alreadyCompleted` |
| Cancel/no-show races another transition | backend 409 `booking.invalidTransition` | — | toast `cancellation.invalidTransition` |
| Report no-show on UPCOMING booking | status=UPCOMING | 204; schedule refreshes; success toast `cancellation.noShowPlayerSuccess` | N/A |
| Either action's refresh silently fails | `loadCoachSchedule` returns falsy | existing `notifyIfScheduleStale` warning fires | `booking.errors.listMayBeStale` toast |

</frozen-after-approval>

## Code Map

- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` -- add buttons, dialogs, refs, handlers (lines ~102-181 template, ~529-593 script for the pattern to mirror)
- `src/frontend/src/components/booking/CancelCoachModal.vue` -- already built; wire in unmodified
- `src/frontend/src/stores/booking.store.js` -- add two thin-wrapper actions (mirror lines 538-548)
- `src/frontend/src/api/booking.api.js:85-88` -- `coachCancelBooking`/`recordNoShowPlayer` already exist, no change
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` -- add new button-label and error-toast keys only

## Tasks & Acceptance

**Execution:**
- [x] `stores/booking.store.js` -- import `coachCancelBooking`, `recordNoShowPlayer`; add `handleCoachCancelBooking(bookingId, cancelReason)` and `handleRecordNoShowPlayer(bookingId)` as plain `await`-and-return wrappers, export both -- caller owns the refresh, per existing convention
- [x] `i18n/en-US/index.js`, `de-DE/index.js`, `fr-FR/index.js` -- add `booking.schedule.cancelSession`, `booking.schedule.reportNoShow` button labels; add `cancellation.noShowPlayerConfirmTitle`, `cancellation.noShowPlayerConfirmBody`, `cancellation.coachCancelFailed`, `cancellation.noShowPlayerFailed`, `cancellation.alreadyCompleted`, `cancellation.invalidTransition` -- only copy with no existing equivalent
- [x] `pages/coach/CoachCommandCenterPage.vue` -- import `CancelCoachModal`; add two `q-btn`s inside `week-grid__booking-block`, independent of the `pendingReschedule` branch: "Report No-Show" (`status === 'UPCOMING'`) and "Cancel Session" (`['CONFIRMED','UPCOMING'].includes(status)`)
- [x] same file -- add `<CancelCoachModal v-model="cancelCoachModalOpen" @confirm="handleCoachCancelConfirm" />`, plus a plain `q-dialog`/`q-card` confirm for no-show (title/body from the new keys, buttons `common.cancel`/`common.confirm`)
- [x] same file -- add refs (`cancelCoachModalOpen`, `cancelCoachBookingId`, `coachCancelActionId`, `noShowPlayerDialogOpen`, `noShowPlayerBookingId`, `noShowActionId`) and handlers `openCancelCoachDialog`, `handleCoachCancelConfirm(reason)`, `openNoShowPlayerDialog`, `confirmNoShowPlayer` -- mirror `handleAcceptReschedule`'s try/catch/finally shape; branch `booking.alreadyCompleted`→`cancellation.alreadyCompleted`, `booking.invalidTransition`→`cancellation.invalidTransition`, else generic failed-key; success uses `cancellation.{coachCancelSuccess|noShowPlayerSuccess}`

**Acceptance Criteria:**
- Given a CONFIRMED/UPCOMING booking, when the coach cancels with a reason, then `coach-cancel` is called, the schedule refreshes, and a success toast appears.
- Given an UPCOMING booking, when the coach confirms no-show, then `no-show-player` is called, the schedule refreshes, and a success toast appears.
- Given a 403 `booking.alreadyCompleted` or 409 `booking.invalidTransition` response, then the matching specific toast appears, not a generic failure.
- Given a booking outside the gated statuses, then the corresponding button does not render.

## Spec Change Log

## Design Notes

`CancelCoachModal.vue` emits `confirm` and closes itself immediately, before the parent's async call resolves — identical to how the existing reschedule dialogs behave. All error handling therefore lives in the page's handler, not the modal.

## Verification

**Commands:**
- `npm run lint --prefix src/frontend` -- expected: passes, no new `vue/no-bare-strings-in-template` violations

**Manual checks (if no CLI):**
- Log in as a coach with one CONFIRMED and one UPCOMING booking on `/coach/command-center`; verify both new buttons appear/disappear per status, the cancel modal and no-show dialog both complete successfully with a toast, and switching locale (en-US/de-DE/fr-FR) renders the new copy correctly.

## Suggested Review Order

**Action triggers (where the new capability surfaces)**

- New "Report No-Show" button, gated to `UPCOMING` only, matching the backend's `recordNoShowPlayer` precondition.
  [`CoachCommandCenterPage.vue:132`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L132)

- New "Cancel Session" button, gated to `CONFIRMED`/`UPCOMING`, matching `cancelBookingAsCoach`'s valid state-machine sources.
  [`CoachCommandCenterPage.vue:143`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L143)

- Both reused components added here: the already-built reason-capture modal, and a new minimal confirm dialog for no-show (no reason needed).
  [`CoachCommandCenterPage.vue:288`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L288)

**Handler logic (the design intent: mirror the existing reschedule pattern)**

- `handleCoachCancelConfirm` — try/catch/finally shape copied from `handleAcceptReschedule`, branching on `booking.alreadyCompleted`/`booking.invalidTransition` before falling back to a generic failure toast.
  [`CoachCommandCenterPage.vue:653`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L653)

- `confirmNoShowPlayer` — same shape, no reason parameter since the backend endpoint takes no body.
  [`CoachCommandCenterPage.vue:680`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L680)

- Dialog-open helpers tracking which booking is being acted on.
  [`CoachCommandCenterPage.vue:648`](../../src/frontend/src/pages/coach/CoachCommandCenterPage.vue#L648)

**Store wiring (thin wrappers, no internal refresh — caller owns the refresh)**

- `handleCoachCancelBooking` / `handleRecordNoShowPlayer`, mirroring `handleAcceptReschedule`'s shape rather than the self-refreshing `handleCancelBooking` shape.
  [`booking.store.js:548`](../../src/frontend/src/stores/booking.store.js#L548)

- New imports from the already-existing API client functions — no API-layer changes needed.
  [`booking.store.js:35`](../../src/frontend/src/stores/booking.store.js#L35)

**i18n copy (peripheral — all three locales, same key set)**

- New button labels and error/confirm copy, en-US.
  [`en-US/index.js:1009`](../../src/frontend/src/i18n/en-US/index.js#L1009)

- Same keys, de-DE.
  [`de-DE/index.js:416`](../../src/frontend/src/i18n/de-DE/index.js#L416)

- Same keys, fr-FR.
  [`fr-FR/index.js:1181`](../../src/frontend/src/i18n/fr-FR/index.js#L1181)
