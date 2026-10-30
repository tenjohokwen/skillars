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
import { useMessagingStore } from 'src/stores/messaging.store'

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

// skillars-deferred-155 AC7/AC8: the player/parent session-prep request edit affordance.
describe('ParentBookingsPage.vue — player request edit affordance (deferred-155)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  it('opening the dialog pre-fills the existing playerRequest text', async () => {
    const withRequest = { ...BOOKING_1H, playerRequest: 'Work on free kicks' }
    const { wrapper } = mountPage({ booking: { parentBookings: [withRequest] } })
    await flushPromises()

    wrapper.vm.openPlayerRequestDialog(withRequest)

    expect(wrapper.vm.playerRequestDialogOpen).toBe(true)
    expect(wrapper.vm.playerRequestText).toBe('Work on free kicks')
  })

  it('opening the dialog for a booking with no existing request starts with empty text', async () => {
    const { wrapper } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openPlayerRequestDialog(BOOKING_1H)

    expect(wrapper.vm.playerRequestText).toBe('')
  })

  it('submitting calls bookingStore.handleSetPlayerRequest and notifies success', async () => {
    const { wrapper, bookingStore } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()

    wrapper.vm.openPlayerRequestDialog(BOOKING_1H)
    wrapper.vm.playerRequestText = 'Shooting drills next time'
    await wrapper.vm.submitPlayerRequest()

    expect(bookingStore.handleSetPlayerRequest).toHaveBeenCalledWith(
      BOOKING_1H.id,
      'Shooting drills next time',
    )
    expect(wrapper.vm.playerRequestDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('a failed submit keeps the dialog open and notifies an error', async () => {
    const { wrapper, bookingStore } = mountPage({ booking: { parentBookings: [BOOKING_1H] } })
    await flushPromises()
    bookingStore.handleSetPlayerRequest.mockRejectedValueOnce(new Error('network error'))

    wrapper.vm.openPlayerRequestDialog(BOOKING_1H)
    wrapper.vm.playerRequestText = 'Shooting drills next time'
    await wrapper.vm.submitPlayerRequest()

    expect(wrapper.vm.playerRequestDialogOpen).toBe(true)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'negative' }))
  })
})

// skillars-deferred-165 AC2: the "Raise a dispute" action only renders for eligible booking
// statuses (mirrors DisputeService.ELIGIBLE_STATUSES client-side, purely for UX).
describe('ParentBookingsPage.vue — dispute action visibility (deferred-165 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  it('renders the dispute action for a COMPLETED booking', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [{ ...BOOKING_1H, status: 'COMPLETED' }] },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
  })

  // fix/audit3-completion-dispute-integrity: a session awaiting the family's confirmation is disputable.
  it.each([
    'CANCELLED',
    'CANCELLED_PARENT',
    'CANCELLED_COACH',
    'NO_SHOW_PLAYER',
    'NO_SHOW_COACH',
    'COMPLETED_PENDING_CONFIRMATION',
  ])('renders the dispute action for a %s booking', async (status) => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [{ ...BOOKING_1H, status }] },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
  })

  it.each(['CONFIRMED', 'UPCOMING'])(
    'does not render the dispute action for a %s booking',
    async (status) => {
      const { wrapper } = mountPage({
        auth: { role: 'PARENT' },
        booking: { parentBookings: [{ ...BOOKING_1H, status }] },
      })
      await flushPromises()

      expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(false)
    },
  )
})

describe('ParentBookingsPage.vue — Message coach entry point (feat/messaging-completion)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  const booking = (overrides) => ({
    ...BOOKING_1H,
    coachId: 'coach-uuid-1',
    playerId: '893573203704173564',
    ...overrides,
  })

  it.each(['CONFIRMED', 'UPCOMING', 'IN_PROGRESS', 'COMPLETED'])(
    'offers Message coach on a %s booking and opens that coach/child conversation',
    async (status) => {
      const { wrapper } = mountPage({
        auth: { userId: '1', role: 'PARENT' },
        booking: { parentBookings: [booking({ status })] },
      })
      await flushPromises()
      const messagingStore = useMessagingStore()
      messagingStore.openConversation.mockResolvedValue({ conversationId: '9' })

      const btn = wrapper.find('[data-testid="message-coach-42"]')
      expect(btn.exists()).toBe(true)
      await btn.trigger('click')

      expect(messagingStore.openConversation).toHaveBeenCalledWith(
        'coach-uuid-1',
        '893573203704173564',
      )
    },
  )

  it("hides Message coach for a parent's 18+ child (parents have no access to adult conversations)", async () => {
    const { wrapper } = mountPage({
      auth: { userId: '1', role: 'PARENT' },
      player: { players: [{ id: '893573203704173564', name: 'Grown', ageTier: 'ADULT' }] },
      booking: { parentBookings: [booking({ status: 'CONFIRMED' })] },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="message-coach-42"]').exists()).toBe(false)
  })

  it.each(['REQUESTED', 'DECLINED', 'CANCELLED'])(
    'hides Message coach on a %s booking (no booking relationship yet / any more)',
    async (status) => {
      const { wrapper } = mountPage({
        auth: { userId: '1', role: 'PARENT' },
        booking: { parentBookings: [booking({ status })] },
      })
      await flushPromises()

      expect(wrapper.find('[data-testid="message-coach-42"]').exists()).toBe(false)
    },
  )
})

// A13: "Coach didn't show" — mirrors BookingService.recordNoShowCoach (UPCOMING only, not before
// the scheduled start) for visibility; the backend stays authoritative.
describe('ParentBookingsPage.vue — coach no-show report (A13)', () => {
  const PAST = new Date(Date.now() - 10 * 60 * 1000).toISOString()
  const FUTURE = new Date(Date.now() + 60 * 60 * 1000).toISOString()

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
    notifySpy.mockReset()
  })

  it('shows the action for an UPCOMING booking whose start has passed', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: {
        parentBookings: [{ ...BOOKING_1H, status: 'UPCOMING', requestedStartTime: PAST }],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(true)
  })

  it.each([
    ['UPCOMING', FUTURE],
    ['CONFIRMED', PAST],
    ['IN_PROGRESS', PAST],
    ['COMPLETED', PAST],
  ])('hides the action for a %s booking starting at %s', async (status, start) => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [{ ...BOOKING_1H, status, requestedStartTime: start }] },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(false)
  })

  it('fails closed (hidden) on an unparseable start time', async () => {
    const { wrapper } = mountPage({ auth: { role: 'PARENT' }, booking: { parentBookings: [] } })
    await flushPromises()
    expect(
      wrapper.vm.canReportCoachNoShow({ status: 'UPCOMING', requestedStartTime: 'not-a-date' }),
    ).toBe(false)
  })

  it('confirming calls the store and notifies success', async () => {
    const booking = { ...BOOKING_1H, status: 'UPCOMING', requestedStartTime: PAST }
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [booking] },
    })
    await flushPromises()
    bookingStore.handleRecordNoShowCoach.mockResolvedValue()

    wrapper.vm.openNoShowCoachDialog(booking)
    expect(wrapper.vm.noShowCoachDialogOpen).toBe(true)
    await wrapper.vm.submitNoShowCoach()

    expect(bookingStore.handleRecordNoShowCoach).toHaveBeenCalledWith(42)
    expect(wrapper.vm.noShowCoachDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'cancellation.noShowCoachSuccess' }),
    )
  })

  it('a too-early rejection maps to its copy and reloads the list', async () => {
    const booking = { ...BOOKING_1H, status: 'UPCOMING', requestedStartTime: PAST }
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [booking] },
    })
    await flushPromises()
    bookingStore.handleRecordNoShowCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.noShowTooEarly' } } },
    })

    wrapper.vm.openNoShowCoachDialog(booking)
    await wrapper.vm.submitNoShowCoach()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.errors.coachNoShowTooEarly' }),
    )
    expect(bookingStore.loadParentBookings).toHaveBeenCalled()
  })

  // feat/payment-forfeit-payouts-and-clawback: not before start + the late-cancel grace.
  it('inside the grace window hides the action and says when it opens', async () => {
    const started = new Date(Date.now() - 10 * 60 * 1000).toISOString()
    const reportableAt = new Date(Date.now() + 20 * 60 * 1000).toISOString()
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: {
        parentBookings: [
          {
            ...BOOKING_1H,
            status: 'UPCOMING',
            requestedStartTime: started,
            coachNoShowReportableAt: reportableAt,
          },
        ],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="no-show-coach-pending"]').exists()).toBe(true)
  })

  // fix/audit3-completion-dispute-integrity: a Quick Complete the coach never started stays reportable.
  it('offers the action on a never-started session awaiting confirmation', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: {
        parentBookings: [
          {
            ...BOOKING_1H,
            status: 'COMPLETED_PENDING_CONFIRMATION',
            liveStarted: false,
            requestedStartTime: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
            coachNoShowReportableAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
          },
        ],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
  })

  it('hides the action on a started session awaiting confirmation (dispute instead)', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: {
        parentBookings: [
          {
            ...BOOKING_1H,
            status: 'COMPLETED_PENDING_CONFIRMATION',
            liveStarted: true,
            requestedStartTime: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
            coachNoShowReportableAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
          },
        ],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
  })

  it('a session-was-started rejection maps to its copy', async () => {
    const booking = {
      ...BOOKING_1H,
      status: 'COMPLETED_PENDING_CONFIRMATION',
      liveStarted: false,
      requestedStartTime: PAST,
    }
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [booking] },
    })
    await flushPromises()
    bookingStore.handleRecordNoShowCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.sessionWasStarted' } } },
    })

    wrapper.vm.openNoShowCoachDialog(booking)
    await wrapper.vm.submitNoShowCoach()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'negative',
        message: 'cancellation.noShowCoachSessionStarted',
      }),
    )
  })

  it('offers the action once the grace has passed', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: {
        parentBookings: [
          {
            ...BOOKING_1H,
            status: 'UPCOMING',
            requestedStartTime: new Date(Date.now() - 40 * 60 * 1000).toISOString(),
            coachNoShowReportableAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
          },
        ],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="no-show-coach-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="no-show-coach-pending"]').exists()).toBe(false)
  })
})

// feat/coach-workflow-gaps A23: a Receipt link on rows whose payment was captured (backend-computed
// receiptAvailable), for parents and self-booking players alike.
describe('ParentBookingsPage.vue — receipt link (feat/coach-workflow-gaps A23)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  it('shows a Receipt link only on rows with receiptAvailable', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PLAYER' },
      booking: {
        parentBookings: [
          { ...BOOKING_1H, id: 1, receiptAvailable: true },
          { ...BOOKING_1H, id: 2, receiptAvailable: false },
        ],
      },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-test="booking-receipt"]')).toHaveLength(1)
  })
})

// fix/booking-request-consent: a coach-proposed booking ("repeat next week") waits on the family —
// Confirm (accept + charge) or Decline — and any other REQUESTED booking can be withdrawn.
describe('ParentBookingsPage.vue — coach proposals and request withdrawal (booking-request-consent)', () => {
  const PROPOSAL = { ...BOOKING_1H, status: 'REQUESTED', proposedByCoach: true }
  const OWN_REQUEST = { ...BOOKING_1H, status: 'REQUESTED', proposedByCoach: false }

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
    notifySpy.mockReset()
  })

  it('a coach proposal shows Confirm and Decline, not Withdraw', async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [PROPOSAL] },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="confirm-proposal-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="decline-proposal-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="withdraw-request-btn"]').exists()).toBe(false)
  })

  it("the family's own request shows Withdraw, not Confirm", async () => {
    const { wrapper } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [OWN_REQUEST] },
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="withdraw-request-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="confirm-proposal-btn"]').exists()).toBe(false)
  })

  it.each(['CONFIRMED', 'PAYMENT_PENDING', 'DECLINED'])(
    'offers neither action once the booking is %s',
    async (status) => {
      const { wrapper } = mountPage({
        auth: { role: 'PARENT' },
        booking: { parentBookings: [{ ...PROPOSAL, status }] },
      })
      await flushPromises()
      expect(wrapper.find('[data-testid="coach-proposal"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="withdraw-request-btn"]').exists()).toBe(false)
    },
  )

  it('confirming a proposal calls the store and notifies success', async () => {
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [PROPOSAL] },
    })
    await flushPromises()
    bookingStore.handleConfirmProposedBooking.mockResolvedValue()

    wrapper.vm.openConfirmProposalDialog(PROPOSAL)
    expect(wrapper.vm.confirmProposalDialogOpen).toBe(true)
    await wrapper.vm.submitConfirmProposal()

    expect(bookingStore.handleConfirmProposedBooking).toHaveBeenCalledWith(42)
    expect(wrapper.vm.confirmProposalDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'booking.proposal.confirmed' }),
    )
  })

  it('a proposal already answered maps to its copy and reloads the list', async () => {
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [PROPOSAL] },
    })
    await flushPromises()
    bookingStore.handleConfirmProposedBooking.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.requestNotPending' } } },
    })

    wrapper.vm.openConfirmProposalDialog(PROPOSAL)
    await wrapper.vm.submitConfirmProposal()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.errors.requestNotPending' }),
    )
    expect(bookingStore.loadParentBookings).toHaveBeenCalled()
  })

  it('declining a proposal withdraws it with the declined toast', async () => {
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [PROPOSAL] },
    })
    await flushPromises()
    bookingStore.handleWithdrawBookingRequest.mockResolvedValue()

    await wrapper.find('[data-testid="decline-proposal-btn"]').trigger('click')
    await flushPromises()

    expect(bookingStore.handleWithdrawBookingRequest).toHaveBeenCalledWith(42)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'booking.proposal.declined' }),
    )
  })

  it('withdrawing an own request notifies with the withdrawn toast', async () => {
    const { wrapper, bookingStore } = mountPage({
      auth: { role: 'PARENT' },
      booking: { parentBookings: [OWN_REQUEST] },
    })
    await flushPromises()
    bookingStore.handleWithdrawBookingRequest.mockResolvedValue()

    await wrapper.vm.submitWithdraw(OWN_REQUEST, 'withdrawn')

    expect(bookingStore.handleWithdrawBookingRequest).toHaveBeenCalledWith(42)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'booking.proposal.withdrawn' }),
    )
  })
})

// fix/audit3-booking-scheduling-integrity: accepting the coach's proposed time used to show the generic
// failure for every refusal; the server's reason (e.g. the pack is paused at the new time) is now shown.
describe('ParentBookingsPage.vue — accepting a coach reschedule', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it.each([
    ['payment.packPaused', 'payment.sessionPack.packPaused'],
    ['booking.slotUnavailable', 'booking.errors.slotUnavailable'],
    ['booking.rescheduleNotPending', 'booking.errors.rescheduleNotPending'],
    ['something.unmapped', 'booking.reschedule.acceptFailed'],
  ])('a %s refusal is shown as %s', async (errorKey, message) => {
    const { wrapper, bookingStore } = mountPage({ booking: { parentBookings: [] } })
    await flushPromises()
    bookingStore.handleAcceptRescheduleAsParent.mockRejectedValue({
      response: { data: { errorMsg: { errorKey } } },
    })

    await wrapper.vm.handleAcceptRescheduleAsParent(42, 'r-1')

    expect(notifySpy).toHaveBeenCalledWith({ message, type: 'negative' })
  })
})
