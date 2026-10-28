// fix/payment-session-pack-integrity: each purchase attempt carries one stable Idempotency-Key, so a
// double-submit or a retry after a lost response replays the same charge instead of buying a second
// pack. Mutation check: mint the key inside every confirmPurchase call → the "lost response" case goes RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

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

async function mountPage() {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(SessionPackPurchasePage, {
    shallow: true,
    global: { plugins: [pinia], stubs: { QPage: { template: '<div><slot /></div>' } } },
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

  it('mints a new key once the server answered a failure', async () => {
    const { wrapper, store } = await mountPage()
    store.purchasePack.mockRejectedValueOnce({ response: { status: 500, data: {} } })

    await wrapper.vm.confirmPurchase()
    await wrapper.vm.confirmPurchase()

    const [first, second] = keysSent(store)
    expect(second).toBeTruthy()
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
