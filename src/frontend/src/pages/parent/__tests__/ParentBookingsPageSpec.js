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

// NOTE (skillars-deferred-108 code review): `id` stays numeric on purpose. BookingStateChip
// declares `bookingId: { type: String }` while ParentBookingsPage:134 binds `booking.id` straight
// from the API, so the Vue prop-type warning this fixture triggers is a faithful reproduction of
// production, not a test artefact. Coercing it here would hide the real mismatch; it is filed in
// deferred-work.md instead (fixing it needs a .vue edit, which this story's AC7 forbids).
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
})
