// skillars-12-7 AC7/AC7a/AC9 -- the recap buttons on week-grid blocks, the "Resume wrap-up" button for an
// abandoned quick-complete, and the missing-recaps banner. Same mounting approach as
// CoachCommandCenterPageSessionPrepSpec.js (real booking data, heavy children stubbed).

import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))
vi.mock('src/api/booking.api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getMissingRecaps: vi.fn() }
})

import { getMissingRecaps } from 'src/api/booking.api'
import CoachCommandCenterPage from 'src/pages/coach/CoachCommandCenterPage.vue'
import SessionRecapDialog from 'src/components/booking/SessionRecapDialog.vue'

const base = { playerId: 42, playerName: 'Alex Kane', requestedEndTime: '2026-03-03T11:00:00Z' }
const COMPLETED_NO_RECAP = {
  ...base,
  bookingId: 'b-c1',
  status: 'COMPLETED',
  hasRecap: false,
  wrapUpSubmitted: true,
  requestedStartTime: '2026-03-03T10:00:00Z',
}
const COMPLETED_RECAP = {
  ...base,
  bookingId: 'b-c2',
  status: 'COMPLETED',
  hasRecap: true,
  wrapUpSubmitted: true,
  requestedStartTime: '2026-03-03T10:00:00Z',
}
// COMPLETED without a session_completion_data row (no wrap-up ever submitted): GET /recap would 404.
const COMPLETED_NO_WRAP_UP = {
  ...base,
  bookingId: 'b-c3',
  status: 'COMPLETED',
  hasRecap: false,
  wrapUpSubmitted: false,
  requestedStartTime: '2026-03-03T10:00:00Z',
}
const CPC_ABANDONED = {
  ...base,
  bookingId: 'b-p1',
  status: 'COMPLETED_PENDING_CONFIRMATION',
  hasRecap: false,
  wrapUpSubmitted: false,
  requestedStartTime: '2026-03-03T10:00:00Z',
}
const CPC_SUBMITTED = {
  ...base,
  bookingId: 'b-p2',
  status: 'COMPLETED_PENDING_CONFIRMATION',
  hasRecap: true,
  wrapUpSubmitted: true,
  requestedStartTime: '2026-03-03T10:00:00Z',
}

const mounted = []
async function mountPage(bookings) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: '1', role: 'COACH' },
      booking: {
        coachSchedule: { coachTimezone: 'Europe/Berlin', bookings, availabilityWindows: [] },
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
        SessionRecapDialog: true,
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const byTestId = (w, id) => w.findAll(`[data-testid="${id}"]`)

describe('CoachCommandCenterPage.vue — recap lifecycle (skillars-12-7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('labels the recap button Add vs Edit from hasRecap, only on COMPLETED blocks', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([COMPLETED_NO_RECAP, COMPLETED_RECAP, CPC_ABANDONED])

    const labels = byTestId(w, 'recap-btn').map((b) => b.text())
    expect(labels).toEqual(['booking.schedule.addRecap', 'booking.schedule.editRecap'])
  })

  it('hides the recap button on a COMPLETED block that never had a wrap-up submitted', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([
      COMPLETED_NO_WRAP_UP,
      { ...COMPLETED_NO_RECAP, wrapUpSubmitted: null },
    ])

    expect(byTestId(w, 'recap-btn')).toHaveLength(0)
  })

  it('opens the recap dialog with the clicked booking', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([COMPLETED_NO_RECAP])

    await byTestId(w, 'recap-btn')[0].trigger('click')

    const dialog = w.findComponent(SessionRecapDialog)
    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('bookingId')).toBe('b-c1')
    expect(dialog.props('playerName')).toBe('Alex Kane')
  })

  it('shows "Resume wrap-up" only for an abandoned COMPLETED_PENDING_CONFIRMATION block', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([CPC_ABANDONED, CPC_SUBMITTED, COMPLETED_NO_RECAP])

    expect(byTestId(w, 'resume-wrap-up-btn')).toHaveLength(1)
  })

  it('resuming an abandoned quick-complete (startedLive null) opens the wizard in non-live mode', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([{ ...CPC_ABANDONED, startedLive: null }])

    await byTestId(w, 'resume-wrap-up-btn')[0].trigger('click')

    const wrapUp = w.findComponent({ name: 'WrapUpSequence' })
    expect(wrapUp.exists()).toBe(true)
    expect(wrapUp.props('bookingId')).toBe('b-p1')
    expect(wrapUp.props('isLiveMode')).toBe(false)
  })

  it('resuming a quick-complete with startedLive false keeps non-live mode', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([{ ...CPC_ABANDONED, startedLive: false }])

    await byTestId(w, 'resume-wrap-up-btn')[0].trigger('click')

    expect(w.findComponent({ name: 'WrapUpSequence' }).props('isLiveMode')).toBe(false)
  })

  // A session ended live also lands in COMPLETED_PENDING_CONFIRMATION; the resume keeps live mode.
  it('resuming a session that was started live re-opens the wizard in live mode', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([{ ...CPC_ABANDONED, startedLive: true }])

    await byTestId(w, 'resume-wrap-up-btn')[0].trigger('click')

    const wrapUp = w.findComponent({ name: 'WrapUpSequence' })
    expect(wrapUp.props('bookingId')).toBe('b-p1')
    expect(wrapUp.props('isLiveMode')).toBe(true)
  })

  // fix/audit3-completion-dispute-integrity: the family confirmed before the wrap-up — the coach resumes the
  // WRAP-UP (attendance, ratings, homework), never the recap-only dialog.
  it('a COMPLETED block awaiting the wrap-up shows "Resume wrap-up", not the recap button', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const awaiting = {
      ...COMPLETED_NO_WRAP_UP,
      bookingId: 'b-aw',
      wrapUpSubmitted: false,
      awaitingWrapUp: true,
    }
    const w = await mountPage([awaiting])

    expect(byTestId(w, 'recap-btn')).toHaveLength(0)
    expect(byTestId(w, 'resume-wrap-up-btn')).toHaveLength(1)
    await byTestId(w, 'resume-wrap-up-btn')[0].trigger('click')
    const wrapUp = w.findComponent({ name: 'WrapUpSequence' })
    expect(wrapUp.exists()).toBe(true)
    expect(wrapUp.props('bookingId')).toBe('b-aw')
  })

  it('a missing-recap item awaiting the wrap-up resumes the wizard instead of the recap dialog', async () => {
    getMissingRecaps.mockResolvedValue({
      count: 1,
      items: [
        {
          bookingId: 'm-aw',
          playerId: 3,
          playerName: 'Kim',
          sessionDate: '2026-03-01T10:00:00Z',
          awaitingWrapUp: true,
        },
      ],
    })
    const w = await mountPage([])
    await byTestId(w, 'missing-recaps-toggle')[0].trigger('click')

    const action = byTestId(w, 'missing-recap-add')[0]
    expect(action.text()).toBe('booking.schedule.resumeWrapUp')
    await action.trigger('click')

    expect(w.findComponent(SessionRecapDialog).props('modelValue')).toBe(false)
    const wrapUp = w.findComponent({ name: 'WrapUpSequence' })
    expect(wrapUp.props('bookingId')).toBe('m-aw')
    expect(wrapUp.props('isLiveMode')).toBe(false)
  })

  it('hides the banner at count 0', async () => {
    getMissingRecaps.mockResolvedValue({ count: 0, items: [] })
    const w = await mountPage([])

    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(0)
  })

  it('shows the banner with a list and an Add recap action that opens the dialog', async () => {
    getMissingRecaps.mockResolvedValue({
      count: 2,
      items: [
        { bookingId: 'm1', playerId: 1, playerName: 'Sam', sessionDate: '2026-03-01T10:00:00Z' },
        { bookingId: 'm2', playerId: 2, playerName: 'Jo', sessionDate: '2026-03-02T10:00:00Z' },
      ],
    })
    const w = await mountPage([])
    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(1)
    expect(byTestId(w, 'missing-recap-item')).toHaveLength(0)

    await byTestId(w, 'missing-recaps-toggle')[0].trigger('click')
    expect(byTestId(w, 'missing-recap-item')).toHaveLength(2)
    expect(byTestId(w, 'missing-recaps-title')[0].text()).toBe('booking.recap.missingTitle')

    await byTestId(w, 'missing-recap-add')[1].trigger('click')
    const dialog = w.findComponent(SessionRecapDialog)
    expect(dialog.props('bookingId')).toBe('m2')
    expect(dialog.props('playerName')).toBe('Jo')
  })

  it('shows a quiet inline error (no banner, no toast) when the lookup fails', async () => {
    getMissingRecaps.mockRejectedValue(new Error('boom'))
    const w = await mountPage([])

    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(0)
    const error = byTestId(w, 'missing-recaps-error')
    expect(error).toHaveLength(1)
    expect(error[0].text()).toBe('booking.recap.missingLoadError')
  })

  it('renders neither banner nor error while the lookup is still loading', async () => {
    getMissingRecaps.mockReturnValue(new Promise(() => {}))
    const w = await mountPage([])

    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(0)
    expect(byTestId(w, 'missing-recaps-error')).toHaveLength(0)
  })

  it('clears the inline error once a later lookup succeeds', async () => {
    getMissingRecaps.mockRejectedValueOnce(new Error('boom'))
    getMissingRecaps.mockResolvedValueOnce({
      count: 1,
      items: [
        { bookingId: 'm1', playerId: 1, playerName: 'Sam', sessionDate: '2026-03-01T10:00:00Z' },
      ],
    })
    const w = await mountPage([])
    expect(byTestId(w, 'missing-recaps-error')).toHaveLength(1)

    await w.findComponent(SessionRecapDialog).vm.$emit('saved', 'x')
    await flushPromises()

    expect(byTestId(w, 'missing-recaps-error')).toHaveLength(0)
    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(1)
  })

  it('refreshes the banner after a recap is saved', async () => {
    getMissingRecaps.mockResolvedValueOnce({
      count: 1,
      items: [
        { bookingId: 'm1', playerId: 1, playerName: 'Sam', sessionDate: '2026-03-01T10:00:00Z' },
      ],
    })
    getMissingRecaps.mockResolvedValueOnce({ count: 0, items: [] })
    const w = await mountPage([])
    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(1)

    await w.findComponent(SessionRecapDialog).vm.$emit('saved', 'm1')
    await flushPromises()

    expect(byTestId(w, 'missing-recaps-banner')).toHaveLength(0)
  })
})
