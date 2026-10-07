// skillars-deferred-148 AC1: ParentPlayerPortalPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER, and the backend deliberately quotes Long as a JSON string so JS never
// has to represent one as a number (CommonConfig.longToStringModule) — converting it back to a
// Number here silently corrupted it, so every schedule/packs API call on this page hit a player id
// that does not exist. Mirrors PlayerDevelopmentDashboardPageSpec.js's precedent (deferred-147).
//
// Mutation check: reintroduce `Number(route.params.playerId)` on the playerId const → the
// "exact id, not corrupted" assertion below goes RED, since it would assert against a Number
// whose string form has already lost the trailing digits.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

import ParentPlayerPortalPage from 'src/pages/parent/ParentPlayerPortalPage.vue'

const STUB = { template: '<div />' }
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
        path: '/parent/players/:playerId/sessions',
        name: 'parent-player-sessions',
        component: ParentPlayerPortalPage,
      },
      { path: '/player/locker-room/:playerId', name: 'player-locker-room', component: STUB },
    ],
  })
  router.push(`/parent/players/${playerId}/sessions`)
  await router.isReady()

  const wrapper = mount(ParentPlayerPortalPage, {
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

describe('ParentPlayerPortalPage.vue — playerId precision (deferred-148 AC1)', () => {
  it('loads the parent schedule with the exact route-param id, not a Number-corrupted one', async () => {
    const { pinia } = await mountPage()

    const { useBookingStore } = await import('src/stores/booking.store')
    const bookingStore = useBookingStore(pinia)

    expect(bookingStore.loadParentSchedule).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const [calledWithId] = bookingStore.loadParentSchedule.mock.calls[0]
    expect(typeof calledWithId).toBe('string')
    expect(calledWithId).toBe(LARGE_PLAYER_ID)
    expect(calledWithId).not.toBe(CORRUPTED_PLAYER_ID)
    expect(bookingStore.loadPlayerPacks).toHaveBeenCalledWith(LARGE_PLAYER_ID)
  })
})
