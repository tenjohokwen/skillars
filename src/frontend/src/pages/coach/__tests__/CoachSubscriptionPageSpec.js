// Pitchside SUB-03: a Scout coach's Upgrade called changeCoachTier, which the server refuses with no
// subscription to change, and the subscribe dialog opened only from a lock overlay on the tiers BELOW the
// current one, where subscribing is refused as already active; the paid downgrade the server supports was
// unreachable. Scout now subscribes through the dialog and a paid coach upgrades in place.
// Owner decisions 2026-11-06: a paid downgrade is offered behind a confirmation and shown as pending with
// a way to take it back; a cancel is confirmed first and can be taken back; nothing changes while the plan
// is set to end.
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

async function mountPage(tier, cancelAtPeriodEnd = false, extra = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      payment: {
        coachSubscription: {
          tier,
          status: 'ACTIVE',
          cancelAtPeriodEnd,
          currentPeriodEnd: '2027-03-12T00:00:00Z',
          ...extra,
        },
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

  it('a paid coach upgrades on the subscription they have', async () => {
    const { wrapper, store } = await mountPage('INSTRUCTOR')
    expect(changeButtonTiers(wrapper)).toEqual(['ACADEMY'])
    wrapper.vm.$.setupState.chooseTier('ACADEMY')
    await flushPromises()
    expect(store.changeCoachTier).toHaveBeenCalledWith('ACADEMY')
    expect(store.subscribeCoach).not.toHaveBeenCalled()
    expect(wrapper.vm.$.setupState.subscribeDialog).toBe(false)
  })

  it('offers a paid downgrade but never Scout, and no change at all while the plan is set to end', async () => {
    const academy = await mountPage('ACADEMY')
    expect(changeButtonTiers(academy.wrapper)).toEqual(['INSTRUCTOR'])
    const cancelling = await mountPage('ACADEMY', true)
    expect(changeButtonTiers(cancelling.wrapper)).toEqual([])
  })

  it('a downgrade is confirmed first, then changes the tier on the subscription', async () => {
    const { wrapper, store } = await mountPage('ACADEMY')
    const setup = wrapper.vm.$.setupState
    setup.openDowngrade('INSTRUCTOR')
    expect(setup.downgradeDialog).toBe(true)
    expect(store.changeCoachTier).not.toHaveBeenCalled()
    await setup.confirmDowngrade()
    expect(store.changeCoachTier).toHaveBeenCalledWith('INSTRUCTOR')
    expect(setup.downgradeDialog).toBe(false)
  })

  it('a pending downgrade is shown with a way to keep the current tier, and is not offered again', async () => {
    const { wrapper, store } = await mountPage('ACADEMY', false, {
      pendingTier: 'INSTRUCTOR',
      pendingEffectiveAt: '2027-03-12T00:00:00Z',
    })
    expect(wrapper.find('[data-testid="subscription-change-pending"]').exists()).toBe(true)
    expect(changeButtonTiers(wrapper)).toEqual([])
    await wrapper.vm.$.setupState.handleKeepTier()
    expect(store.changeCoachTier).toHaveBeenCalledWith('ACADEMY')
  })
})

describe('CoachSubscriptionPage.vue — cancelling', () => {
  it('Cancel opens a confirmation; only its confirm cancels', async () => {
    const { wrapper, store } = await mountPage('ACADEMY')
    const setup = wrapper.vm.$.setupState
    await wrapper.find('[data-testid="subscription-cancel-btn"]').trigger('click')
    expect(setup.cancelDialog).toBe(true)
    expect(store.cancelCoachSubscription).not.toHaveBeenCalled()
    await setup.handleCancel()
    expect(store.cancelCoachSubscription).toHaveBeenCalled()
    expect(setup.cancelDialog).toBe(false)
  })

  it('a plan set to end offers Keep my plan, which takes the cancel back', async () => {
    const { wrapper, store } = await mountPage('ACADEMY', true)
    expect(wrapper.find('[data-testid="subscription-cancel-btn"]').exists()).toBe(false)
    await wrapper.vm.$.setupState.handleKeepPlan()
    expect(store.keepCoachSubscription).toHaveBeenCalled()
  })

  it('a refused action keeps its dialog open', async () => {
    const { wrapper, store } = await mountPage('ACADEMY')
    store.cancelCoachSubscription.mockRejectedValueOnce(new Error('down'))
    const setup = wrapper.vm.$.setupState
    setup.cancelDialog = true
    await setup.handleCancel()
    expect(setup.cancelDialog).toBe(true)
  })
})
