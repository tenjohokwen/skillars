// Pitchside SUB-03: a Scout coach's Upgrade called changeCoachTier, which the server refuses with no
// subscription to change, and the subscribe dialog opened only from a lock overlay on the tiers BELOW the
// current one, where subscribing is refused as already active; the paid downgrade the server supports was
// unreachable. Scout now subscribes through the dialog, and a paid coach changes tier either way.
// Mutation check: make chooseTier always call handleChangeTier and the Scout case turns red.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: vi.fn() }) }
})

import CoachSubscriptionPage from 'src/pages/coach/CoachSubscriptionPage.vue'

const TIERS = ['SCOUT', 'INSTRUCTOR', 'ACADEMY'].map((tier) => ({ tier, features: [] }))
const mounted = []

async function mountPage(tier) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      payment: {
        coachSubscription: { tier, status: 'ACTIVE', cancelAtPeriodEnd: false },
        coachTiers: TIERS,
        savedPaymentMethod: { hasCard: true },
      },
    },
  })
  const wrapper = mount(CoachSubscriptionPage, {
    shallow: true,
    global: { plugins: [pinia], stubs: { QPage: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  const { usePaymentStore } = await import('src/stores/payment.store')
  return { wrapper, store: usePaymentStore(pinia) }
}

const changeButtonTiers = (wrapper) =>
  wrapper
    .findAll('[data-testid="subscription-tier-card"]')
    .filter((card) => card.find('[data-testid="subscription-change-btn"]').exists())
    .map((card) => card.attributes('data-tier'))

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachSubscriptionPage.vue — choosing a tier', () => {
  it('a Scout coach subscribes to a paid tier through the dialog', async () => {
    const { wrapper, store } = await mountPage('SCOUT')
    expect(changeButtonTiers(wrapper)).toEqual(['INSTRUCTOR', 'ACADEMY'])
    const setup = wrapper.vm.$.setupState
    setup.chooseTier('INSTRUCTOR')
    expect(setup.subscribeDialog).toBe(true)
    expect(store.changeCoachTier).not.toHaveBeenCalled()
    await setup.confirmSubscribe()
    expect(store.subscribeCoach).toHaveBeenCalledWith({ tier: 'INSTRUCTOR' })
  })

  it('a paid coach can move up or down a paid tier, on the subscription they have', async () => {
    const { wrapper, store } = await mountPage('ACADEMY')
    expect(changeButtonTiers(wrapper)).toEqual(['INSTRUCTOR'])
    wrapper.vm.$.setupState.chooseTier('INSTRUCTOR')
    await flushPromises()
    expect(store.changeCoachTier).toHaveBeenCalledWith('INSTRUCTOR')
    expect(store.subscribeCoach).not.toHaveBeenCalled()
    expect(wrapper.vm.$.setupState.subscribeDialog).toBe(false)
  })
})
