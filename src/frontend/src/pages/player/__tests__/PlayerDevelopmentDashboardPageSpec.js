// skillars-deferred-147: PlayerDevelopmentDashboardPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER, and the backend deliberately quotes Long as a JSON string so JS never
// has to represent one as a number (CommonConfig.longToStringModule) — converting it back to a
// Number here silently corrupted it (e.g. …173564 became …173600, confirmed via `node -e
// "console.log(893573203704173564)"`), so every development API call on this page hit a player id
// that does not exist, surfacing as 403s on manual testing of the new nav link (deferred-147).
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

import PlayerDevelopmentDashboardPage from 'src/pages/player/PlayerDevelopmentDashboardPage.vue'

const mounted = []

// A real backend Tsid shape — large enough that Number(...) silently corrupts it (not a
// round/small test fixture number that would pass by accident).
const LARGE_PLAYER_ID = '893573203704173564'

async function mountPage(playerId = LARGE_PLAYER_ID) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role: 'PLAYER' } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/player/development/:playerId', component: PlayerDevelopmentDashboardPage }],
  })
  router.push(`/player/development/${playerId}`)
  await router.isReady()

  const wrapper = mount(PlayerDevelopmentDashboardPage, {
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

describe('PlayerDevelopmentDashboardPage.vue — playerId precision (deferred-147)', () => {
  it('fetches exposure with the exact route-param id, not a Number-corrupted one', async () => {
    const { pinia } = await mountPage()

    const { useDevelopmentStore } = await import('src/stores/development.store')
    const store = useDevelopmentStore(pinia)

    expect(store.fetchExposure).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const [calledWithId] = store.fetchExposure.mock.calls[0]
    expect(typeof calledWithId).toBe('string')
    expect(calledWithId).toBe(LARGE_PLAYER_ID)
    // The corrupted value Number(...) would have produced — asserting it is NOT this is the
    // direct regression guard for the bug found during manual testing.
    expect(calledWithId).not.toBe('893573203704173600')
  })

  it('re-fetches with the new exact id when the route param changes (no remount)', async () => {
    const OTHER_LARGE_ID = '893573203704173999'
    const { wrapper, pinia } = await mountPage()

    await wrapper.vm.$router.push(`/player/development/${OTHER_LARGE_ID}`)
    await flushPromises()

    const { useDevelopmentStore } = await import('src/stores/development.store')
    const store = useDevelopmentStore(pinia)
    const lastCall = store.fetchExposure.mock.calls.at(-1)
    expect(lastCall[0]).toBe(OTHER_LARGE_ID)
  })
})
