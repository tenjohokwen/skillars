// skillars-deferred-148 AC1: ParentDevelopmentPortalPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER, and the backend deliberately quotes Long as a JSON string so JS never
// has to represent one as a number (CommonConfig.longToStringModule) — converting it back to a
// Number here silently corrupted it, so every development API call on this page hit a player id
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

import ParentDevelopmentPortalPage from 'src/pages/parent/ParentDevelopmentPortalPage.vue'

const mounted = []

// A real backend Tsid shape — large enough that Number(...) silently corrupts it.
const LARGE_PLAYER_ID = '893573203704173564'
const CORRUPTED_PLAYER_ID = '893573203704173600'

async function mountPage(playerId = LARGE_PLAYER_ID, initialState = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role: 'PARENT' }, ...initialState },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/parent/players/:playerId/development',
        name: 'parent-development',
        component: ParentDevelopmentPortalPage,
      },
    ],
  })
  router.push(`/parent/players/${playerId}/development`)
  await router.isReady()

  const wrapper = mount(ParentDevelopmentPortalPage, {
    shallow: true,
    global: { plugins: [pinia, router] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, pinia, router }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('ParentDevelopmentPortalPage.vue — playerId precision (deferred-148 AC1)', () => {
  it('fetches exposure with the exact route-param id, not a Number-corrupted one', async () => {
    const { pinia } = await mountPage()

    const { useDevelopmentStore } = await import('src/stores/development.store')
    const store = useDevelopmentStore(pinia)

    expect(store.fetchExposure).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const [calledWithId] = store.fetchExposure.mock.calls[0]
    expect(typeof calledWithId).toBe('string')
    expect(calledWithId).toBe(LARGE_PLAYER_ID)
    expect(calledWithId).not.toBe(CORRUPTED_PLAYER_ID)
  })

  it('does not navigate when activePlayerId settles to the same id already on the route (string === string)', async () => {
    const { pinia, router } = await mountPage(LARGE_PLAYER_ID)
    const pushSpy = vi.spyOn(router, 'push')

    const { usePlayerStore } = await import('src/stores/playerStore')
    const playerStore = usePlayerStore(pinia)

    // Pre-fix, playerId.value was a Number and activePlayerId is always a string, so
    // `newId !== playerId.value` (string !== number) was unconditionally true whenever
    // activePlayerId was truthy — this watcher would have pushed a navigation even though the
    // ids represent the same player. Post-fix (both sides strings), no push should occur.
    playerStore.activePlayerId = LARGE_PLAYER_ID
    await flushPromises()

    expect(pushSpy).not.toHaveBeenCalled()
  })
})
