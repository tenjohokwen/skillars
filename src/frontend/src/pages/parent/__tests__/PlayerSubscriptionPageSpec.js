// skillars-deferred-148 AC1: PlayerSubscriptionPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER, and the backend deliberately quotes Long as a JSON string so JS never
// has to represent one as a number (CommonConfig.longToStringModule) — converting it back to a
// Number here silently corrupted it, so every subscription API call on this page hit a player id
// that does not exist. Mirrors PlayerDevelopmentDashboardPageSpec.js's precedent (deferred-147).
//
// Mutation check: reintroduce `Number(route.params.playerId)` on the playerId computed → the
// "exact id, not corrupted" assertion below goes RED, since it would assert against a Number
// whose string form has already lost the trailing digits.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import PlayerSubscriptionPage from 'src/pages/parent/PlayerSubscriptionPage.vue'

const mounted = []

// A real backend Tsid shape — large enough that Number(...) silently corrupts it.
const LARGE_PLAYER_ID = '893573203704173564'
const CORRUPTED_PLAYER_ID = '893573203704173600'

async function mountPage(playerId = LARGE_PLAYER_ID) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'PARENT' } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/parent/player/:playerId/subscription',
        name: 'player-subscription',
        component: PlayerSubscriptionPage,
      },
    ],
  })
  router.push(`/parent/player/${playerId}/subscription`)
  await router.isReady()

  const wrapper = mount(PlayerSubscriptionPage, {
    shallow: true,
    global: { plugins: [pinia, router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, pinia }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('PlayerSubscriptionPage.vue — playerId precision (deferred-148 AC1)', () => {
  it('fetches the player subscription with the exact route-param id, not a Number-corrupted one', async () => {
    const { pinia } = await mountPage()

    const { usePaymentStore } = await import('src/stores/payment.store')
    const paymentStore = usePaymentStore(pinia)

    expect(paymentStore.fetchPlayerSubscription).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const [calledWithId] = paymentStore.fetchPlayerSubscription.mock.calls[0]
    expect(typeof calledWithId).toBe('string')
    expect(calledWithId).toBe(LARGE_PLAYER_ID)
    expect(calledWithId).not.toBe(CORRUPTED_PLAYER_ID)
  })
})
