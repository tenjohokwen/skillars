// skillars-deferred-151 AC4 — CoachCommandCenterPage.vue's first-ever component-spec coverage.
//
// 37 specs existed repo-wide before this story, none under pages/coach/ — this page had zero
// coverage. Scoped narrowly to the AC4 dedup's actual behavior (the shared `runCoachAction`
// mutation->refresh->toast helper all five handlers now route through), not a test asserting a
// "refresh rejection" — that would require violating loadCoachSchedule's own documented
// never-rethrow contract (booking.store.js:360-368) and would protect nothing (see Finding 4).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})
// onMounted fetches missing recaps; unmocked it hits the network (ECONNREFUSED) and the axios
// interceptor's late console output can race worker teardown (EnvironmentTeardownError in CI).
vi.mock('src/api/booking.api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getMissingRecaps: vi.fn().mockResolvedValue({ count: 0, items: [] }) }
})

import CoachCommandCenterPage from 'src/pages/coach/CoachCommandCenterPage.vue'

function storeOf(wrapper) {
  return wrapper.vm.bookingStore ?? wrapper.vm.$.setupState.bookingStore
}

const mounted = []
function mountPage() {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: '1', role: 'COACH' },
      booking: { coachSchedule: null },
    },
  })
  const wrapper = mount(CoachCommandCenterPage, {
    shallow: true,
    global: { plugins: [pinia] },
  })
  mounted.push(wrapper)
  return wrapper
}

function positiveMessages() {
  return notifyMock.mock.calls.filter((c) => c[0].type === 'positive').map((c) => c[0].message)
}
function negativeMessages() {
  return notifyMock.mock.calls.filter((c) => c[0].type === 'negative').map((c) => c[0].message)
}

const BOOKING = {
  bookingId: 'b1',
  pendingReschedule: { id: 'r1' },
  requestedStartTime: '2026-03-01T10:00:00Z',
  requestedEndTime: '2026-03-01T11:00:00Z',
  canonicalTimezone: 'Europe/Paris',
}

describe('CoachCommandCenterPage.vue handler dedup (deferred-151 AC4)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('handleAcceptReschedule: success calls mutate, refreshes, and shows a positive toast', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleAcceptReschedule.mockResolvedValue()
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.handleAcceptReschedule(BOOKING)
    await flushPromises()

    expect(store.handleAcceptReschedule).toHaveBeenCalledWith('b1', 'r1')
    expect(store.loadCoachSchedule).toHaveBeenCalled()
    expect(positiveMessages()).toContain('booking.reschedule.accepted')
    expect(wrapper.vm.rescheduleActionId).toBeNull()
  })

  it('handleAcceptReschedule: a mapped errorKey shows its specific message and still refreshes', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleAcceptReschedule.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.coachUnavailable' } } },
    })
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.handleAcceptReschedule(BOOKING)
    await flushPromises()

    expect(negativeMessages()).toContain('booking.errors.coachUnavailable')
    expect(store.loadCoachSchedule).toHaveBeenCalled()
    expect(wrapper.vm.rescheduleActionId).toBeNull()
  })

  it('handleAcceptReschedule: an unmapped errorKey falls back to the generic accept-failed message', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleAcceptReschedule.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'something.unrecognized' } } },
    })
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.handleAcceptReschedule(BOOKING)
    await flushPromises()

    expect(negativeMessages()).toContain('booking.reschedule.acceptFailed')
  })

  it('a stale refresh (loadCoachSchedule resolves false) surfaces the stale-list warning alongside the outcome toast', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleAcceptReschedule.mockResolvedValue()
    store.loadCoachSchedule.mockResolvedValue(false)

    await wrapper.vm.handleAcceptReschedule(BOOKING)
    await flushPromises()

    const warnings = notifyMock.mock.calls
      .filter((c) => c[0].type === 'warning')
      .map((c) => c[0].message)
    expect(warnings).toContain('booking.errors.listMayBeStale')
    expect(positiveMessages()).toContain('booking.reschedule.accepted')
  })

  it('handleDeclineReschedule: rescheduleNotPending maps to its own message', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleDeclineReschedule.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.rescheduleNotPending' } } },
    })
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.handleDeclineReschedule(BOOKING)
    await flushPromises()

    expect(negativeMessages()).toContain('booking.errors.rescheduleNotPending')
  })

  it('handleCoachCancelConfirm: uses cancelCoachBookingId as the action id and maps alreadyCompleted', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    wrapper.vm.cancelCoachBookingId = 'b2'
    store.handleCoachCancelBooking.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.alreadyCompleted' } } },
    })
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.handleCoachCancelConfirm('WEATHER')
    await flushPromises()

    expect(store.handleCoachCancelBooking).toHaveBeenCalledWith('b2', 'WEATHER')
    expect(negativeMessages()).toContain('cancellation.alreadyCompleted')
    expect(wrapper.vm.coachCancelActionId).toBeNull()
  })

  it('confirmNoShowPlayer: closes the dialog immediately and succeeds via the shared helper', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    wrapper.vm.noShowPlayerBookingId = 'b3'
    wrapper.vm.noShowPlayerDialogOpen = true
    store.handleRecordNoShowPlayer.mockResolvedValue()
    store.loadCoachSchedule.mockResolvedValue(true)

    const pending = wrapper.vm.confirmNoShowPlayer()
    expect(wrapper.vm.noShowPlayerDialogOpen).toBe(false)
    await pending
    await flushPromises()

    expect(store.handleRecordNoShowPlayer).toHaveBeenCalledWith('b3')
    expect(positiveMessages()).toContain('cancellation.noShowPlayerSuccess')
  })

  // The one genuine outlier (Finding 4): submitCoachReschedule's catch does NOT refresh the
  // schedule at all, unlike the other four handlers.
  it('submitCoachReschedule: on failure, does NOT call loadCoachSchedule (refreshOnError: false)', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    wrapper.vm.coachRescheduleBookingId = 'b4'
    wrapper.vm.coachRescheduleDurationMs = 3600000
    wrapper.vm.coachRescheduleProposedStart = '2026-03-02T10:00'
    store.handleRequestRescheduleAsCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.notReschedulable' } } },
    })
    store.loadCoachSchedule.mockClear()

    await wrapper.vm.submitCoachReschedule()
    await flushPromises()

    expect(negativeMessages()).toContain('booking.errors.notReschedulable')
    expect(store.loadCoachSchedule).not.toHaveBeenCalled()
  })

  it('submitCoachReschedule: on success, closes the dialog (inside mutate) and still refreshes', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    wrapper.vm.coachRescheduleBookingId = 'b5'
    wrapper.vm.coachRescheduleDurationMs = 3600000
    wrapper.vm.coachRescheduleProposedStart = '2026-03-02T10:00'
    wrapper.vm.coachRescheduleDialogOpen = true
    store.handleRequestRescheduleAsCoach.mockResolvedValue()
    store.loadCoachSchedule.mockResolvedValue(true)

    await wrapper.vm.submitCoachReschedule()
    await flushPromises()

    expect(wrapper.vm.coachRescheduleDialogOpen).toBe(false)
    expect(store.loadCoachSchedule).toHaveBeenCalled()
    expect(positiveMessages()).toContain('booking.reschedule.requestSent')
  })

  it('submitCoachReschedule: a missing proposed start/end is a no-op client-side guard, never calling the store', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    wrapper.vm.coachRescheduleProposedStart = ''

    await wrapper.vm.submitCoachReschedule()
    await flushPromises()

    expect(store.handleRequestRescheduleAsCoach).not.toHaveBeenCalled()
    expect(negativeMessages()).toContain('booking.reschedule.requestFailed')
  })
})

// skillars-deferred-151 AC6: client-side pre-check (skip the API call for the common case) + the
// corresponding catch-branch for the race case, on all three UPCOMING-gated actions.
const FUTURE_BOOKING = {
  ...BOOKING,
  requestedStartTime: '2099-01-01T10:00:00Z',
}

describe('CoachCommandCenterPage.vue UPCOMING-action time gate (deferred-151 AC6)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('handleStartSession: a future-dated booking is rejected client-side without calling the store', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)

    await wrapper.vm.handleStartSession(FUTURE_BOOKING)
    await flushPromises()

    expect(store.handleStartSession).not.toHaveBeenCalled()
    expect(negativeMessages()).toContain('booking.errors.sessionNotYetStarted')
  })

  it('handleStartSession: the race case (pre-check passes, backend still rejects) shows the same message', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleStartSession.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.sessionNotYetStarted' } } },
    })

    await wrapper.vm.handleStartSession(BOOKING)
    await flushPromises()

    expect(store.handleStartSession).toHaveBeenCalledWith('b1')
    expect(negativeMessages()).toContain('booking.errors.sessionNotYetStarted')
  })

  it('handleQuickComplete: a future-dated booking is rejected client-side without calling the store', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)

    await wrapper.vm.handleQuickComplete(FUTURE_BOOKING)
    await flushPromises()

    expect(store.handleInitiateQuickComplete).not.toHaveBeenCalled()
    expect(negativeMessages()).toContain('booking.errors.sessionNotYetStarted')
  })

  it('confirmNoShowPlayer: a future-dated booking is rejected client-side without calling the store', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)

    wrapper.vm.openNoShowPlayerDialog(FUTURE_BOOKING)
    await wrapper.vm.confirmNoShowPlayer()
    await flushPromises()

    expect(store.handleRecordNoShowPlayer).not.toHaveBeenCalled()
    expect(negativeMessages()).toContain('booking.errors.noShowTooEarly')
  })

  it('confirmNoShowPlayer: the race case maps booking.noShowTooEarly to its own message', async () => {
    const wrapper = mountPage()
    const store = storeOf(wrapper)
    store.handleRecordNoShowPlayer.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.noShowTooEarly' } } },
    })

    wrapper.vm.openNoShowPlayerDialog(BOOKING)
    await wrapper.vm.confirmNoShowPlayer()
    await flushPromises()

    expect(store.handleRecordNoShowPlayer).toHaveBeenCalledWith('b1')
    expect(negativeMessages()).toContain('booking.errors.noShowTooEarly')
  })
})

// skillars-deferred-165 AC2: the "Raise a dispute" action only renders for eligible booking
// statuses (mirrors DisputeService.ELIGIBLE_STATUSES client-side, purely for UX). Full-mounted
// (not shallow): bookingsByDay groups purely by day-of-week from requestedStartTime, independent
// of selectedWeek, so any valid Instant lands in the grid regardless of the currently shown week.
describe('CoachCommandCenterPage.vue — dispute action visibility (deferred-165 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  function mountFullPage(status) {
    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: {
        auth: { userId: '1', role: 'COACH' },
        booking: {
          coachSchedule: {
            coachTimezone: 'UTC',
            bookings: [
              {
                bookingId: 'b1',
                status,
                requestedStartTime: '2026-03-02T10:00:00Z',
                requestedEndTime: '2026-03-02T11:00:00Z',
                playerName: 'Kid',
              },
            ],
            availabilityWindows: [],
            projectedGrossRevenue: 0,
            commissionDeduction: 0,
            projectedNetRevenue: 0,
          },
        },
      },
    })
    const wrapper = mount(CoachCommandCenterPage, {
      global: {
        plugins: [pinia],
        stubs: { BookingStateChip: true, TimezoneNotice: true, SessionDNAChart: true },
      },
    })
    mounted.push(wrapper)
    return wrapper
  }

  it('renders the dispute action for a COMPLETED booking', async () => {
    const wrapper = mountFullPage('COMPLETED')
    await flushPromises()

    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
  })

  it.each(['CANCELLED', 'CANCELLED_PARENT', 'CANCELLED_COACH', 'NO_SHOW_PLAYER', 'NO_SHOW_COACH'])(
    'renders the dispute action for a %s booking',
    async (status) => {
      const wrapper = mountFullPage(status)
      await flushPromises()

      expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
    },
  )

  it.each(['CONFIRMED', 'UPCOMING'])(
    'does not render the dispute action for a %s booking',
    async (status) => {
      const wrapper = mountFullPage(status)
      await flushPromises()

      expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(false)
    },
  )
})
