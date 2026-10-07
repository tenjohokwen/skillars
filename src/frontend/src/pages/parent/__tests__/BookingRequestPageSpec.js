// skillars-deferred-108 AC2 — BookingRequestPage.vue slot/timezone regression (closes deferred-17 D6).
//
// Ledger (deferred-work.md:913): "reverting the .vue and booking.store.js changes … would leave
// the entire suite green". deferred-17 AC1 renamed every slot-object read from .startTime/.endTime
// to .startDatetime/.endDatetime; deferred-18 D6 covers own-booking rows + coach-timezone week
// bounds.
//
// A navigated memory router is mandatory: the page reads route.params.coachId and
// route.query.playerId at setup, and without them ownBlockingBookings drops every fixture
// (coachId mismatch) and canSubmit is permanently false (submit() early-returns).
//
// Trimming (recorded per the story's scope note): shallow mount + assert via wrapper.vm, no
// rendered-text assertions. formatSlot/formatInZone use `dateStyle` + `timeZoneName` together,
// which Chrome accepts but Node's Intl.DateTimeFormat (the happy-dom env's Intl) rejects with
// "Invalid option : option" — a full mount would throw inside the slot-label render. No
// production seam added; the regression surface (slotRows mapping + submit payload) is fully
// reachable through wrapper.vm.
//
// Mutation check (run both ways, see Dev Agent Record):
//   - slotRows: `sortKey: Date.parse(slot.startDatetime)` → `slot.startTime` — the "slotRows has
//     a finite sortKey" assertion turns red (Date.parse(undefined) → NaN).
//   - submit(): selectedSlot.value.startDatetime → .startTime — the "submit payload carries
//     requestedStartTime" assertion turns red (payload field becomes undefined).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

// skillars-deferred-108 code review (P7): AC2 required these two onMounted calls to be mocked --
// getBookingRequestConfig feeds ownBlockingStatuses, and leaving it unmocked made both own-row
// tests pass on the hardcoded fallback (every run logged "Could not load booking request config").
// Partial mock: importOriginal is safe here because src/boot/axios is already stubbed above.
vi.mock('src/api/booking.api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getBatchConfig: vi.fn(), getBookingRequestConfig: vi.fn() }
})

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import BookingRequestPage from 'src/pages/parent/BookingRequestPage.vue'
import { useBookingStore } from 'src/stores/booking.store'
import { getBatchConfig, getBookingRequestConfig } from 'src/api/booking.api'

const STUB = { template: '<div />' }

const mounted = []

async function mountPage(bookingState, queryPlayerId = '5', playerState) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      booking: { batchBasket: [], availabilitySignature: 'sig-1', ...bookingState },
      auth: { role: 'PARENT' },
      ...(playerState ? { player: playerState } : {}),
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/parent/coaches/:coachId', name: 'req', component: BookingRequestPage },
      { path: '/parent/bookings', name: 'bookings', component: STUB },
      { path: '/marketplace', name: 'mkt', component: STUB },
    ],
  })
  router.push(`/parent/coaches/77?playerId=${queryPlayerId}`)
  await router.isReady()

  const wrapper = mount(BookingRequestPage, {
    shallow: true,
    global: {
      plugins: [pinia, router],
      stubs: { SessionPackTracker: true, BookingStateChip: true, PaymentMethodCard: true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, bookingStore: useBookingStore(pinia), router, pinia }
}

const SLOT = { startDatetime: '2026-03-10T15:00:00Z', endDatetime: '2026-03-10T16:00:00Z' }

describe('BookingRequestPage.vue — slot/timezone regression (deferred-108 AC2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Explicit config so ownBlockingStatuses is what the test says it is, not a silent fallback.
    getBatchConfig.mockResolvedValue({ maxSize: 4 })
    getBookingRequestConfig.mockResolvedValue({ activeSlotStatuses: ['REQUESTED', 'CONFIRMED'] })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('slotRows maps startDatetime into a finite sortKey (the .startTime→.startDatetime rename)', async () => {
    const { wrapper } = await mountPage({
      computedSlots: [SLOT],
      coachTimezone: 'America/New_York',
    })

    expect(wrapper.vm.slotRows).toHaveLength(1)
    expect(wrapper.vm.slotRows[0].type).toBe('available')
    expect(wrapper.vm.slotRows[0].slot).toEqual(SLOT)
    expect(Number.isNaN(wrapper.vm.slotRows[0].sortKey)).toBe(false)
    expect(wrapper.vm.slotRows[0].sortKey).toBe(Date.parse('2026-03-10T15:00:00Z'))

    // P7: the mocked onMounted config actually applied -- no silent fallback behind these tests.
    expect(getBatchConfig).toHaveBeenCalledTimes(1)
    expect(getBookingRequestConfig).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.maxBatchSize).toBe(4)
    expect(wrapper.vm.ownBlockingStatuses).toEqual(['REQUESTED', 'CONFIRMED'])
    // P8: a clean PARENT mount raises no error toast (notifySpy was previously never asserted).
    expect(notifySpy).not.toHaveBeenCalled()
  })

  it('after selectSlot, the submit payload carries a defined requestedStartTime/EndTime', async () => {
    const { wrapper, bookingStore } = await mountPage({
      computedSlots: [SLOT],
      coachTimezone: 'America/New_York',
    })

    wrapper.vm.selectSlot(wrapper.vm.slotRows[0].slot)
    await wrapper.vm.$nextTick()
    expect(wrapper.vm.canSubmit).toBe(true)

    await wrapper.vm.submit()

    expect(bookingStore.submitBookingRequest).toHaveBeenCalledTimes(1)
    const payload = bookingStore.submitBookingRequest.mock.calls[0][0]
    expect(payload.requestedStartTime).toBe('2026-03-10T15:00:00Z')
    expect(payload.requestedEndTime).toBe('2026-03-10T16:00:00Z')
    // skillars-deferred-148 AC1: playerId is now the raw string from route.query, not a
    // Number-parsed value — '5' here, not 5. See the deferred-148 describe block below for the
    // dedicated Tsid-precision regression coverage on a value too large to round-trip as a Number.
    expect(payload.playerId).toBe('5')
  })

  it('an own booking for the routed coach becomes a non-selectable "own" row that does not count toward the batch', async () => {
    const { wrapper, bookingStore } = await mountPage({
      computedSlots: [SLOT],
      coachTimezone: 'America/New_York',
      weekStart: '2026-03-09',
      parentBookings: [
        {
          id: 9,
          coachId: 77,
          status: 'REQUESTED',
          requestedStartTime: '2026-03-10T13:00:00Z',
          requestedEndTime: '2026-03-10T14:00:00Z',
        },
      ],
    })

    const ownRows = wrapper.vm.slotRows.filter((r) => r.type === 'own')
    expect(ownRows).toHaveLength(1)
    expect(ownRows[0].status).toBe('REQUESTED')
    expect(ownRows[0].start).toBe('2026-03-10T13:00:00Z')
    expect(ownRows[0]).not.toHaveProperty('slot') // nothing for selectSlot / basket to act on
    expect(bookingStore.batchBasketSize).toBe(0)
    expect(wrapper.vm.batchAtMax).toBe(false)
  })

  // skillars-deferred-109 AC6 — slotRows drops slots with an unparseable startDatetime
  // (deferred-work.md:1874-1878).
  it('AC6 — a slot with a missing/blank startDatetime is dropped, not rendered with key="slot-undefined"', async () => {
    const { wrapper } = await mountPage({
      computedSlots: [
        SLOT,
        { startDatetime: undefined, endDatetime: undefined },
        { startDatetime: '', endDatetime: '' },
        { startDatetime: 'not-a-date', endDatetime: 'not-a-date' },
      ],
      coachTimezone: 'America/New_York',
    })

    const available = wrapper.vm.slotRows.filter((r) => r.type === 'available')
    expect(available).toHaveLength(1)
    expect(available[0].slot).toEqual(SLOT)
    expect(wrapper.vm.slotRows.every((r) => !Number.isNaN(r.sortKey))).toBe(true)
    expect(wrapper.vm.slotRows.some((r) => r.key === 'slot-undefined')).toBe(false)
    // Mutation: remove the `.filter((slot) => !Number.isNaN(Date.parse(slot.startDatetime)))` →
    // the three bad slots return as rows with sortKey NaN and key "slot-undefined" → RED.
  })

  it('the own-booking week window is bounded in the coach timezone, not UTC', async () => {
    // A booking 2 h before the browser/UTC week start but before the NY week start too.
    const edgeBooking = {
      id: 9,
      coachId: 77,
      status: 'REQUESTED',
      requestedStartTime: '2026-06-01T02:00:00Z',
      requestedEndTime: '2026-06-01T03:00:00Z',
    }

    const ny = await mountPage({
      computedSlots: [SLOT],
      weekStart: '2026-06-01',
      coachTimezone: 'America/New_York', // week starts 2026-06-01T04:00Z → booking excluded
      parentBookings: [edgeBooking],
    })
    expect(ny.wrapper.vm.slotRows.filter((r) => r.type === 'own')).toHaveLength(0)

    const utc = await mountPage({
      computedSlots: [SLOT],
      weekStart: '2026-06-01',
      coachTimezone: 'UTC', // week starts 2026-06-01T00:00Z → booking included
      parentBookings: [edgeBooking],
    })
    expect(utc.wrapper.vm.slotRows.filter((r) => r.type === 'own')).toHaveLength(1)
  })

  // skillars-deferred-148 AC1 (fourth Tsid-corruption site, found on this story's second pass):
  // route.query.playerId was parsed via Number(...)/Number.isFinite(...) — a real backend Tsid
  // (18-19 digits, past Number.MAX_SAFE_INTEGER, quoted as a JSON string by the backend's own
  // CommonConfig.longToStringModule specifically so JS never represents it as a Number) silently
  // rounds and still passes the finite/positive check, so the corrupted value reached the
  // booking-submit write path undetected.
  describe('playerId precision from route.query (deferred-148 AC1)', () => {
    const LARGE_PLAYER_ID = '893573203704173564'
    const CORRUPTED_PLAYER_ID = '893573203704173600'

    it('submits with the exact large Tsid string from route.query, not a Number-corrupted one', async () => {
      const { wrapper, bookingStore } = await mountPage(
        { computedSlots: [SLOT], coachTimezone: 'America/New_York' },
        LARGE_PLAYER_ID,
      )

      wrapper.vm.selectSlot(wrapper.vm.slotRows[0].slot)
      await wrapper.vm.$nextTick()
      expect(wrapper.vm.canSubmit).toBe(true)

      await wrapper.vm.submit()

      expect(bookingStore.submitBookingRequest).toHaveBeenCalledTimes(1)
      const payload = bookingStore.submitBookingRequest.mock.calls[0][0]
      expect(typeof payload.playerId).toBe('string')
      expect(payload.playerId).toBe(LARGE_PLAYER_ID)
      expect(payload.playerId).not.toBe(CORRUPTED_PLAYER_ID)
    })

    it('falls back to playerStore.activePlayerId when route.query.playerId is a garbage value, not corrupted into it', async () => {
      const FALLBACK_ID = '893573203704173999'
      const { wrapper } = await mountPage(
        { computedSlots: [SLOT], coachTimezone: 'America/New_York' },
        'abc',
        { activePlayerId: FALLBACK_ID },
      )

      // This is the regression guard for Design A's validation gate: dropping the shape check
      // entirely (rather than replacing the Number-based one with an equivalent regex) would let
      // 'abc' flow straight through as playerId, which the page's own canSubmit comment names as
      // a case that must not be able to submit with an undefined/garbage playerId.
      expect(wrapper.vm.playerId).toBe(FALLBACK_ID)
      expect(wrapper.vm.playerId).not.toBe('abc')
    })
  })
})
