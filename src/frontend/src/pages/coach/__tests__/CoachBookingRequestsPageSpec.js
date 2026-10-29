// fix/booking-request-consent: a session the coach proposed ("repeat next week") is confirmed by the
// family, never accepted by the coach — the inbox shows it as awaiting parent confirmation and offers
// no Accept (the server refuses that accept with booking.parentConfirmationRequired).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// booking.store → payment.api → @stripe/stripe-js runs a network fetch at import time in happy-dom.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import CoachBookingRequestsPage from 'src/pages/coach/CoachBookingRequestsPage.vue'
import { useBookingStore } from 'src/stores/booking.store'

const REQUEST = {
  id: 'b-1',
  status: 'REQUESTED',
  playerName: 'Kid',
  parentName: 'Pat Parent',
  canonicalTimezone: 'Europe/Berlin',
  requestedStartTime: '2026-11-10T14:00:00Z',
  requestedEndTime: '2026-11-10T15:00:00Z',
  proposedByCoach: false,
}

const mounted = []

function mountPage(requests) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { booking: { coachBookingRequests: requests, coachBatchGroups: [] } },
  })
  const bookingStore = useBookingStore(pinia)
  bookingStore.loadCoachBookingRequests.mockResolvedValue(true)
  const wrapper = mount(CoachBookingRequestsPage, { global: { plugins: [pinia] } })
  mounted.push(wrapper)
  return { wrapper, bookingStore }
}

function buttonLabels(wrapper) {
  return wrapper.findAll('button').map((b) => b.text())
}

describe('CoachBookingRequestsPage.vue — coach proposals (booking-request-consent)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it("offers Accept and Decline on a family's request", async () => {
    const { wrapper } = mountPage([REQUEST])
    await flushPromises()
    expect(buttonLabels(wrapper)).toEqual(
      expect.arrayContaining(['booking.requests.accept', 'booking.requests.decline']),
    )
    expect(wrapper.find('[data-testid="awaiting-parent-confirmation"]').exists()).toBe(false)
  })

  it('shows its own proposal as awaiting parent confirmation, with no Accept', async () => {
    const { wrapper } = mountPage([{ ...REQUEST, proposedByCoach: true }])
    await flushPromises()
    expect(wrapper.find('[data-testid="awaiting-parent-confirmation"]').exists()).toBe(true)
    const labels = buttonLabels(wrapper)
    expect(labels).not.toContain('booking.requests.accept')
    expect(labels).toContain('booking.proposal.withdrawProposal')
  })

  it('maps a parentConfirmationRequired rejection to its copy', async () => {
    const { wrapper, bookingStore } = mountPage([REQUEST])
    await flushPromises()
    bookingStore.approveBooking.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'booking.parentConfirmationRequired' } } },
    })

    await wrapper.vm.handleAccept('b-1')

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'negative',
        message: 'booking.errors.parentConfirmationRequired',
      }),
    )
  })
})
