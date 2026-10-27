// Navigation reachability — CoachCommandCenterPage.vue's Story 7.1 Stripe-setup banner and its
// booking-requests entry point.
//
// Mutation checks:
//   - drop `status != null &&` from stripeSetupIncomplete → the "status not loaded" case renders the
//     banner, RED.
//   - drop `paymentStore.fetchStripeStatus()` from onMounted → the "fetches on mount" case goes RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

import { createRouter, createMemoryHistory } from 'vue-router'

// Same mounting approach as CoachCommandCenterPageRecapSpec.js (full mount, heavy children stubbed).
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))
vi.mock('src/api/booking.api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getMissingRecaps: vi.fn().mockResolvedValue({ count: 0, items: [] }) }
})

import CoachCommandCenterPage from 'src/pages/coach/CoachCommandCenterPage.vue'

const mounted = []

async function mountPage(stripeStatus, stripeError = null) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: '1', role: 'COACH' },
      booking: { coachSchedule: null },
      payment: { stripeStatus, error: { stripeStatus: stripeError } },
    },
  })
  const STUB = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/coach/booking-requests', '/coach/payment-settings'].map((path) => ({
      path,
      component: STUB,
    })),
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(CoachCommandCenterPage, {
    global: {
      plugins: [pinia, router],
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
  return { wrapper, pinia }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachCommandCenterPage.vue — Stripe setup banner (Story 7.1)', () => {
  it('fetches the Stripe status on mount via the shared payment store action', async () => {
    const { pinia } = await mountPage(null)
    const { usePaymentStore } = await import('src/stores/payment.store')
    expect(usePaymentStore(pinia).fetchStripeStatus).toHaveBeenCalledTimes(1)
  })

  it.each(['PENDING', 'RESTRICTED', 'NOT_CONNECTED'])(
    'shows the banner while onboarding is %s',
    async (onboardingStatus) => {
      const { wrapper } = await mountPage({ onboardingStatus })
      expect(wrapper.find('[data-testid="stripe-setup-banner"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="stripe-setup-link"]').attributes('href')).toBe(
        '/coach/payment-settings',
      )
    },
  )

  it('hides the banner once onboarding is COMPLETE', async () => {
    const { wrapper } = await mountPage({ onboardingStatus: 'COMPLETE' })
    expect(wrapper.find('[data-testid="stripe-setup-banner"]').exists()).toBe(false)
  })

  it('hides the banner while the status is not loaded (loading, or the fetch failed)', async () => {
    const { wrapper } = await mountPage(null)
    expect(wrapper.find('[data-testid="stripe-setup-banner"]').exists()).toBe(false)
  })
})

describe('CoachCommandCenterPage.vue — Stripe setup banner ignores a stale status', () => {
  it("hides the banner when this mount's fetch failed, even if a PENDING status lingers", async () => {
    // payment.store keeps the previous stripeStatus on a failed refetch (and across logout).
    const { wrapper } = await mountPage({ onboardingStatus: 'PENDING' }, new Error('503'))
    expect(wrapper.find('[data-testid="stripe-setup-banner"]').exists()).toBe(false)
  })
})

describe('CoachCommandCenterPage.vue — booking requests entry point', () => {
  it('links to /coach/booking-requests', async () => {
    const { wrapper } = await mountPage(null)
    const link = wrapper.find('[data-testid="booking-requests-link"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/coach/booking-requests')
  })
})
