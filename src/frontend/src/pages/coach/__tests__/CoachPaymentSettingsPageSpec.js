// StripeOnboardingResource redirects the coach back to #/coach/payment-settings?stripe_error=<code>
// when the Stripe OAuth callback fails; the page used to ignore the query entirely, so a cancelled
// or rejected connection looked identical to "never tried".

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('src/api/payment.api', async (importOriginal) => ({
  ...(await importOriginal()),
  getStripeOnboardingUrl: vi.fn(),
}))

import CoachPaymentSettingsPage from 'src/pages/coach/CoachPaymentSettingsPage.vue'

const mounted = []

async function mountAt(url) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/coach/payment-settings', component: CoachPaymentSettingsPage }],
  })
  router.push(url)
  await router.isReady()
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(CoachPaymentSettingsPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachPaymentSettingsPage.vue — ?stripe_error banner', () => {
  it('shows no callback banner without the query', async () => {
    const { wrapper } = await mountAt('/coach/payment-settings')
    expect(wrapper.find('.stripe-callback-error').exists()).toBe(false)
  })

  it.each([
    ['access_denied', 'payment.error.stripeCallback.accessDenied'],
    ['state_mismatch', 'payment.error.stripeCallback.stateMismatch'],
    ['missing_code', 'payment.error.stripeCallback.generic'],
    ['some_future_code', 'payment.error.stripeCallback.generic'],
  ])('maps stripe_error=%s to %s', async (code, key) => {
    const { wrapper } = await mountAt(`/coach/payment-settings?stripe_error=${code}`)
    const banner = wrapper.find('.stripe-callback-error')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain(key)
  })

  it('dismissing the banner drops stripe_error from the route so a refresh does not re-show it', async () => {
    const { wrapper, router } = await mountAt('/coach/payment-settings?stripe_error=access_denied')
    await wrapper.find('.stripe-callback-error button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.stripe_error).toBeUndefined()
    expect(wrapper.find('.stripe-callback-error').exists()).toBe(false)
  })
})
