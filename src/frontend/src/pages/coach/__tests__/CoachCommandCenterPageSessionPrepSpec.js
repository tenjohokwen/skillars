// skillars-12-3 Task 5.2 — the new "Prep" button on CoachCommandCenterPage.vue. Kept as its own
// small spec rather than an addition to CoachCommandCenterPageSpec.js: that file mounts with
// `shallow: true` and `coachSchedule: null`, so no booking cards render at all today — an addition
// there would prove nothing. This spec mounts with real booking data so the week-grid cards (plain
// template markup, not a sub-component) actually render, while stubbing out the heavier
// self-fetching child components (ActiveSessionScreen, WrapUpSequence, SessionDNAChart,
// CancelCoachModal, TimezoneNotice, BookingStateChip, SessionPrepCard) that aren't this test's
// concern and would otherwise make real network/EventSource calls.

import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

import CoachCommandCenterPage from 'src/pages/coach/CoachCommandCenterPage.vue'
import SessionPrepCard from 'src/components/booking/SessionPrepCard.vue'

const UPCOMING_BOOKING = {
  bookingId: 'b-upcoming',
  playerId: 42,
  playerName: 'Alex Kane',
  status: 'UPCOMING',
  requestedStartTime: '2026-03-02T10:00:00Z',
  requestedEndTime: '2026-03-02T11:00:00Z',
}

const CONFIRMED_BOOKING = {
  bookingId: 'b-confirmed',
  playerId: 44,
  playerName: 'Jo Marsh',
  status: 'CONFIRMED',
  requestedStartTime: '2026-03-04T10:00:00Z',
  requestedEndTime: '2026-03-04T11:00:00Z',
}

const REQUESTED_BOOKING = {
  bookingId: 'b-requested',
  playerId: 45,
  playerName: 'Lee Park',
  status: 'REQUESTED',
  requestedStartTime: '2026-03-05T10:00:00Z',
  requestedEndTime: '2026-03-05T11:00:00Z',
}

const COMPLETED_BOOKING = {
  bookingId: 'b-completed',
  playerId: 43,
  playerName: 'Sam Rivera',
  status: 'COMPLETED',
  requestedStartTime: '2026-03-03T10:00:00Z',
  requestedEndTime: '2026-03-03T11:00:00Z',
}

const mounted = []
function mountPage(bookings) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: '1', role: 'COACH' },
      booking: {
        coachSchedule: {
          coachTimezone: 'Europe/Berlin',
          bookings,
          availabilityWindows: [],
        },
      },
    },
  })
  const wrapper = mount(CoachCommandCenterPage, {
    global: {
      plugins: [pinia],
      stubs: {
        ActiveSessionScreen: true,
        WrapUpSequence: true,
        SessionDNAChart: true,
        CancelCoachModal: true,
        TimezoneNotice: true,
        BookingStateChip: true,
        SessionPrepCard: true,
      },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('CoachCommandCenterPage.vue — Session Prep button (skillars-12-3 AC5)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  // skillars-12-6 AC14 (G6): CONFIRMED joins UPCOMING (the idiom the cancel button already uses);
  // COMPLETED and REQUESTED stay negative.
  it('renders the Prep button for CONFIRMED and UPCOMING booking cards only', () => {
    const wrapper = mountPage([
      UPCOMING_BOOKING,
      CONFIRMED_BOOKING,
      COMPLETED_BOOKING,
      REQUESTED_BOOKING,
    ])

    const prepButtons = wrapper
      .findAll('button')
      .filter((b) => b.text() === 'coach.sessionPrepButton')
    expect(prepButtons).toHaveLength(2)
  })

  // skillars-12-3 code review (2026-10-15) Patch: previously asserted only the page's own refs,
  // which openSessionPrep had just assigned two lines earlier — proving nothing about whether those
  // refs are actually bound to SessionPrepCard's props (the child is stubbed). Asserts the stub's
  // received props directly instead.
  it('opens the dialog with the correct bookingId/playerName passed through to SessionPrepCard', async () => {
    const wrapper = mountPage([UPCOMING_BOOKING])

    const prepBtn = wrapper.findAll('button').find((b) => b.text() === 'coach.sessionPrepButton')
    expect(prepBtn).toBeTruthy()
    await prepBtn.trigger('click')

    expect(wrapper.vm.showSessionPrep).toBe(true)
    const prepCard = wrapper.findComponent(SessionPrepCard)
    expect(prepCard.exists()).toBe(true)
    expect(prepCard.props('bookingId')).toBe('b-upcoming')
    expect(prepCard.props('playerName')).toBe('Alex Kane')
  })
})
