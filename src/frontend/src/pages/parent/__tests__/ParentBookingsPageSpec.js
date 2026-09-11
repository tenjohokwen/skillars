// skillars-deferred-108 AC2 — ParentBookingsPage.vue reschedule end derivation (closes deferred-18 D6).
//
// Ledger (deferred-work.md:929): "ParentBookingsPage.vue derived read-only reschedule end".
// The proposed-end field is read-only and always start + originalDurationMs (a move, not a
// resize); opening the dialog for a booking with no derivable length notifies and refuses to open.
//
// The dialog teleports (q-dialog → document.body), so assert the backing `rescheduleProposedEnd`
// computed and dialog state via wrapper.vm rather than wrapper.find().
//
// Mutation check (run both ways, see Dev Agent Record): change `rescheduleProposedEnd` to return
// `rescheduleProposedStart.value` (drop the `+ rescheduleDurationMs.value` derivation) — the
// "derives end = start + duration" assertion turns red.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// booking.store → payment.api → @stripe/stripe-js runs a network fetch at import time in happy-dom.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

// Notify is not registered by installQuasarPlugin — stub useQuasar so $q.notify is callable.
const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import ParentBookingsPage from 'src/pages/parent/ParentBookingsPage.vue'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import { useBookingStore } from 'src/stores/booking.store'

const mounted = []

function mountPage(initialState = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState })
  const wrapper = mount(ParentBookingsPage, {
    global: {
      plugins: [pinia],
      stubs: { BookingStateChip: true, TimezoneNotice: true },
    },
  })
  mounted.push(wrapper)
  return { wrapper, bookingStore: useBookingStore(pinia) }
}

// NOTE (skillars-deferred-109 AC9): `id` stays numeric on purpose — it is what the API returns and
// what ParentBookingsPage:134 binds to `:booking-id`. BookingStateChip's prop is now
// `bookingId: { type: [String, Number] }` (was String-only), so a numeric id no longer logs a Vue
// "Invalid prop" warning. The AC9 test below mounts the real BookingStateChip (unstubbed) with a
// terminal-status fixture and asserts that.
const BOOKING_1H = {
  id: 42,
  status: 'CONFIRMED',
  canonicalTimezone: 'America/New_York',
  requestedStartTime: '2026-05-01T14:00:00Z',
  requestedEndTime: '2026-05-01T15:00:00Z',
  coachDisplayName: 'Coach A',
  playerName: 'Kid',
}

describe('ParentBookingsPage.vue — reschedule end derivation (deferred-108 AC2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  // skillars-deferred-108 code review (P14): this page is FULL-mounted (no `shallow`), so leaving
  // instances live leaks watchers and the shared useQuasar stub into later tests.
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    // skillars-deferred-109 code review: the AC9 test installs a console.warn spy that was never
    // restored, suppressing warnings for every test that ran after it in this file.
    vi.restoreAllMocks()
  })

  it('rescheduleProposedEnd derives end = start + the original booking length', async () => {
    const { wrapper } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(BOOKING_1H)
    expect(wrapper.vm.rescheduleDialogOpen).toBe(true)

    wrapper.vm.rescheduleProposedStart = '2026-06-10T09:30'
    await wrapper.vm.$nextTick()

    // 1-hour booking → end is exactly one hour after the proposed start, in datetime-local shape.
    expect(wrapper.vm.rescheduleProposedEnd).toBe('2026-06-10T10:30')
  })

  it('opening the dialog for a zero-length booking notifies and does not open', async () => {
    const zeroLen = {
      ...BOOKING_1H,
      requestedEndTime: BOOKING_1H.requestedStartTime, // duration 0
    }
    const { wrapper } = mountPage({ booking: { parentBookings: [zeroLen] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(zeroLen)

    expect(wrapper.vm.rescheduleDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'negative' }))
  })

  it('rescheduleProposedEnd is empty until a proposed start is entered', async () => {
    const { wrapper } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(BOOKING_1H)
    expect(wrapper.vm.rescheduleProposedEnd).toBe('')
  })

  // -------------------------------------------------------------------------
  // skillars-deferred-109 AC7 — pin the (correct) fixed-instant behaviour.
  // RescheduleService.java:176-184 validates Duration.between(Instant,Instant).equals(...), so the
  // submitted proposedStart/End pair MUST span the same elapsed ms as the original booking. The
  // story-review found the "wall-clock minutes" alternative was backwards; do NOT change the
  // arithmetic. happy-dom's Intl is UTC (no DST), so the DST-crossing drift cannot be exercised
  // here — the elapsed-ms invariant below is zone-independent and is the acceptance bar.
  // -------------------------------------------------------------------------
  it('AC7 — the submitted proposedStart/End span exactly the original booking duration', async () => {
    const { wrapper, bookingStore } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(BOOKING_1H)
    wrapper.vm.rescheduleProposedStart = '2026-06-10T09:30'
    await wrapper.vm.$nextTick()

    await wrapper.vm.submitReschedule()

    expect(bookingStore.handleRequestReschedule).toHaveBeenCalledTimes(1)
    const [, data] = bookingStore.handleRequestReschedule.mock.calls[0]
    const submittedMs = Date.parse(data.proposedEndTime) - Date.parse(data.proposedStartTime)
    const originalMs =
      Date.parse(BOOKING_1H.requestedEndTime) - Date.parse(BOOKING_1H.requestedStartTime)
    expect(submittedMs).toBe(originalMs)
    expect(submittedMs).toBe(3_600_000)
    // Mutation: drop the `+ rescheduleDurationMs.value` from rescheduleProposedEnd (end := start) →
    // submittedMs becomes 0 → RED. Any non-elapsed-ms formula fails this invariant.
  })

  // skillars-deferred-109 code review: the previous version of this test asserted
  // `rescheduleProposedEnd !== rescheduleProposedStart` on a fall-back date. Under happy-dom's UTC
  // Intl that is unconditionally true (no transition exists), and under a REAL fall-back zone it is
  // FALSE by design — the display string is an ambiguous wall time. It therefore asserted nothing
  // anywhere. The invariant that actually matters is the one RescheduleService validates: the
  // SUBMITTED pair must span the original elapsed duration, whatever the display shows.
  it('AC7 — inside a DST fall-back hour the SUBMITTED pair still spans the original duration', async () => {
    const { wrapper, bookingStore } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(BOOKING_1H)
    // 01:30 on US fall-back day: in America/New_York this wall time occurs twice.
    wrapper.vm.rescheduleProposedStart = '2026-11-01T01:30'
    await wrapper.vm.$nextTick()

    await wrapper.vm.submitReschedule()

    const [, data] = bookingStore.handleRequestReschedule.mock.calls[0]
    expect(Date.parse(data.proposedEndTime) - Date.parse(data.proposedStartTime)).toBe(3_600_000)
    // Mutation: derive proposedEndTime from `new Date(rescheduleProposedEnd.value)` (i.e. re-parse
    // the display string, the pre-review behaviour) → under TZ=America/New_York the ambiguous wall
    // time collapses to the pre-transition offset, the delta becomes 0 → RED. Run the suite with
    // `TZ=America/New_York` to exercise it; under the default UTC env this test still pins the
    // elapsed-ms invariant but cannot distinguish the two formulas (see deferred-work.md, code
    // review of skillars-deferred-109 — the CI timezone-leg gap).
  })

  it('AC7 — the derived end instant is always strictly after the proposed start', async () => {
    const { wrapper } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openRescheduleDialog(BOOKING_1H)
    wrapper.vm.rescheduleProposedStart = '2026-11-01T01:30'
    await wrapper.vm.$nextTick()

    // The INSTANT, not the display projection — the display may legitimately read equal to the
    // start inside a fall-back hour, which is exactly why the payload no longer goes through it.
    expect(wrapper.vm.rescheduleProposedEndInstant).not.toBeNull()
    expect(wrapper.vm.rescheduleProposedEndInstant.getTime()).toBeGreaterThan(
      new Date(wrapper.vm.rescheduleProposedStart).getTime(),
    )
    // Mutation: drop `+ rescheduleDurationMs.value` → equal, not greater → RED.
  })

  // skillars-deferred-109 AC9 — BookingStateChip.vue bookingId prop accepts a Number
  // (deferred-work.md:1935-1946). Mount the real chip (unstubbed) with a numeric id.
  it('AC9 — a numeric booking id passed to BookingStateChip logs no "Invalid prop" warning', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    // Terminal status → BookingStateChip does not open an SSE (no EventSource in happy-dom).
    const terminalBooking = { ...BOOKING_1H, id: 42, status: 'CANCELLED' }

    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: { booking: { parentBookings: [terminalBooking] } },
    })
    const wrapper = mount(ParentBookingsPage, {
      global: { plugins: [pinia], stubs: { TimezoneNotice: true } }, // BookingStateChip NOT stubbed
    })
    mounted.push(wrapper)
    await flushPromises()

    // skillars-deferred-109 code review: POSITIVE CONTROL FIRST. The assertion below is an
    // absence assertion — it passes vacuously if the chip never rendered at all (an initialState
    // key drift, a changed stub tree, a v-if that hides the chip for CANCELLED). Prove the chip is
    // actually mounted and actually received the numeric id before concluding anything from the
    // silence.
    const chip = wrapper.findComponent(BookingStateChip)
    expect(chip.exists()).toBe(true)
    expect(chip.props('bookingId')).toBe(42)

    const invalidPropWarnings = warnSpy.mock.calls
      .map((args) => args.join(' '))
      .filter((msg) => /Invalid prop/.test(msg) && /bookingId/.test(msg))
    expect(invalidPropWarnings).toEqual([])
    // Mutation: revert BookingStateChip's prop to `type: String` → Vue logs
    // 'Invalid prop: type check failed for prop "bookingId". Expected String … got Number' → RED.
  })
})
