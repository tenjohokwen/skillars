// fix/payment-session-pack-integrity: each purchase attempt carries one stable Idempotency-Key, so a
// double-submit or a retry after a lost response replays the same charge instead of buying a second
// pack. Mutation check: mint the key inside every confirmPurchase call → the "lost response" case goes RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { coachId: 'coach-1' }, query: { playerId: '7' } }),
  useRouter: () => ({ back: vi.fn() }),
}))
vi.mock('src/api/marketplace.api', () => ({
  getCoachProfile: vi.fn().mockResolvedValue({ displayName: 'Coach', currency: 'EUR' }),
}))
vi.mock('src/api/payment.api', () => ({
  fetchCoachSessionPackTiers: vi.fn().mockResolvedValue(null),
}))

import { useBookingStore } from 'src/stores/booking.store'
import SessionPackPurchasePage from 'src/pages/parent/SessionPackPurchasePage.vue'

const mounted = []

// realMessages: mount with the real en-US texts (the global test i18n has none, so te() is always false there).
async function mountPage({ realMessages = false } = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const plugins = [pinia]
  if (realMessages) {
    plugins.push(createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } }))
  }
  const wrapper = mount(SessionPackPurchasePage, {
    shallow: true,
    global: { plugins, stubs: { QPage: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  wrapper.vm.selected = 'tier-1'
  await flushPromises()
  return { wrapper, store: useBookingStore() }
}

const keysSent = (store) => store.purchasePack.mock.calls.map((c) => c[3])

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('SessionPackPurchasePage.vue — purchase attempt key', () => {
  it('reuses the key when the first try never got an answer (lost response)', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(new Error('Network Error'))

    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(first).toBeTruthy()
    expect(second).toBe(first)
  })

  const gatewayError = (errorKey) => ({
    response: { status: 422, data: { errorMsg: { errorKey, message: errorKey } } },
  })
  const statusError = (status, message) => ({
    response: { status, data: { errorMsg: { errorKey: 'generic.requestError', message } } },
  })

  // skillars-deferred-169: this used to assert a new key after ANY server answer, which let a retry after an
  // unknown outcome (5xx, 422 lifecycleFailure) charge twice. An unknown outcome keeps the key.
  it('keeps the key after an unknown outcome (5xx / unspecific 422)', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce({ response: { status: 500, data: {} } })
    store.purchasePack.mockRejectedValueOnce(gatewayError('payment.lifecycleFailure'))

    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()

    const [first, second, third] = keysSent(store)
    expect(second).toBe(first)
    expect(third).toBe(first)
  })

  it('mints a new key once the server proved nothing was charged', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(gatewayError('payment.noChargeMade'))

    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).toBeTruthy()
    expect(second).not.toBe(first)
  })

  it('mints a new key after a rejected key (invalidIdempotencyKey / idempotencyKeyReused)', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(statusError(400, 'payment.invalidIdempotencyKey'))
    store.purchasePack.mockRejectedValueOnce(statusError(409, 'payment.idempotencyKeyReused'))

    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()

    const [first, second, third] = keysSent(store)
    expect(second).not.toBe(first)
    expect(third).not.toBe(second)
  })

  it('mints a new key after a definite refusal raised before any attempt existed (403 / inactive tier)', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(gatewayError('payment.packTierInactive'))

    await wrapper.vm.confirmPurchase()
    wrapper.vm.onCardSaved()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).not.toBe(first)
  })

  it('keeps the key and says "still being confirmed" on attemptInProgress / attemptUnresolved', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(statusError(409, 'payment.attemptUnresolved'))

    await wrapper.vm.confirmPurchase()
    expect(wrapper.vm.purchaseError).toBe('booking.packs.purchasePending')
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).toBe(first)
  })

  it('does not mint a new key on a card save while the last answer left the outcome unknown', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce({ response: { status: 500, data: {} } })

    await wrapper.vm.confirmPurchase()
    wrapper.vm.onCardSaved()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).toBe(first)
  })

  it('mints a new key on a card save after a proven no-charge answer', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(gatewayError('payment.noChargeMade'))

    await wrapper.vm.confirmPurchase()
    wrapper.vm.onCardSaved()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).not.toBe(first)
  })

  it('ignores a second click while the purchase is in flight', async () => {
    const { wrapper, store } = await mountPage()
    let resolve
    store.purchasePack.mockReturnValueOnce(new Promise((r) => (resolve = r)))

    const firstClick = wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()
    resolve()
    await firstClick

    expect(store.purchasePack).toHaveBeenCalledTimes(1)
  })
})

// fix/pack-expiry-size-tiers: the tier DTO carries validityDays (from the pack-size tier config);
// the page shows it so the parent knows how long the pack lasts before buying.
describe('SessionPackPurchasePage.vue — pack validity', () => {
  async function mountWithTier(tier) {
    const { fetchCoachSessionPackTiers } = await import('src/api/payment.api')
    fetchCoachSessionPackTiers.mockResolvedValueOnce(tier)
    const pinia = createTestingPinia({ createSpy: vi.fn })
    const wrapper = mount(SessionPackPurchasePage, {
      shallow: true,
      global: {
        plugins: [pinia],
        renderStubDefaultSlot: true,
        stubs: { QPage: { template: '<div><slot /></div>' }, PaymentMethodCard: true },
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    return wrapper
  }

  const baseTier = {
    packTierId: 'tier-1',
    sessionCount: 5,
    totalPrice: 100,
    pricePerSession: 20,
  }

  it('shows the validity days for the offered tier', async () => {
    const wrapper = await mountWithTier({ ...baseTier, validityDays: 180 })

    const validity = wrapper.find('[data-testid="pack-validity"]')
    expect(validity.exists()).toBe(true)
    expect(validity.text()).toBe('booking.packs.validFor')
  })

  it('hides the validity line when the backend does not send it', async () => {
    const wrapper = await mountWithTier({ ...baseTier })

    expect(wrapper.find('[data-testid="pack-validity"]').exists()).toBe(false)
  })
})

describe('SessionPackPurchasePage.vue — what a failed purchase says', () => {
  const apiFailure = (errorKey) => ({
    response: { status: 422, data: { errorMsg: { errorKey, message: errorKey } } },
  })

  it('a refused card says so and offers to update the card; a new card clears it', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(apiFailure('payment.cardDeclined'))

    await wrapper.vm.confirmPurchase()

    expect(wrapper.vm.purchaseError).toBe('payment.error.cardDeclined')
    expect(wrapper.vm.cardDeclined).toBe(true)
    wrapper.vm.onCardSaved()
    expect(wrapper.vm.purchaseError).toBe(null)
    expect(wrapper.vm.cardDeclined).toBe(false)
  })

  it('any other failure (payment.lifecycleFailure) stays the generic message, never the raw code', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce(apiFailure('payment.lifecycleFailure'))

    await wrapper.vm.confirmPurchase()

    expect(wrapper.vm.purchaseError).toBe('booking.packs.purchaseError')
    expect(wrapper.vm.cardDeclined).toBe(false)
  })

  // skillars-deferred-170 AC3: the server refuses a coach who cannot be paid before any attempt exists.
  it('a coach who cannot be paid yet says so, not "Purchase failed", and the next try gets a new key', async () => {
    const { wrapper, store } = await mountPage({ realMessages: true })
    store.purchasePack.mockRejectedValueOnce(apiFailure('payment.coachStripeNotConfigured'))

    await wrapper.vm.confirmPurchase()

    expect(wrapper.vm.purchaseError).toBe('This coach has not completed their payment setup yet')
    expect(wrapper.vm.cardDeclined).toBe(false)
    wrapper.vm.onCardSaved()
    await wrapper.vm.confirmPurchase()
    const [first, second] = keysSent(store)
    expect(second).not.toBe(first)
  })
})
