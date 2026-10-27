// Navigation reachability — ParentChildSwitcher.vue's destination on selecting a child.
//
// Previously it always went to the development portal. It now keeps the parent on the same page
// when that page reloads on a :playerId change (development portal, child hub) and otherwise lands
// on the selected child's hub.
//
// Mutation check: revert selectPlayer to always push 'parent-development' → the "from the
// dashboard" case goes RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

import ParentChildSwitcher from 'src/components/ParentChildSwitcher.vue'

const A = '893573203704173564'
const B = '893573203704173565'
const STUB = { template: '<div />' }
const mounted = []

async function mountAt(path) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      player: {
        players: [
          { id: A, name: 'Mia', ageTierLabel: 'U12' },
          { id: B, name: 'Leo', ageTierLabel: 'U10' },
        ],
        activePlayerId: A,
      },
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/parent/dashboard', component: STUB },
      { path: '/parent/players/:playerId/sessions', name: 'parent-player-hub', component: STUB },
      {
        path: '/parent/players/:playerId/development',
        name: 'parent-development',
        component: STUB,
      },
      { path: '/parent/players/:playerId/packs', component: STUB },
    ],
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(ParentChildSwitcher, {
    shallow: true,
    global: { plugins: [pinia, router] },
  })
  mounted.push(wrapper)
  return { wrapper, router, pinia }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('ParentChildSwitcher.vue — selectPlayer destination', () => {
  it("from the dashboard, lands on the selected child's hub", async () => {
    const { wrapper, router, pinia } = await mountAt('/parent/dashboard')

    wrapper.vm.selectPlayer(B)
    await flushPromises()

    expect(router.currentRoute.value.path).toBe(`/parent/players/${B}/sessions`)
    const { usePlayerStore } = await import('src/stores/playerStore')
    expect(usePlayerStore(pinia).setActivePlayer).toHaveBeenCalledWith(B)
  })

  it('on the development portal, stays on the development portal (prior behaviour kept)', async () => {
    const { wrapper, router } = await mountAt(`/parent/players/${A}/development`)

    wrapper.vm.selectPlayer(B)
    await flushPromises()

    expect(router.currentRoute.value.path).toBe(`/parent/players/${B}/development`)
  })

  it('on the child hub, stays on the hub for the new child', async () => {
    const { wrapper, router } = await mountAt(`/parent/players/${A}/sessions`)

    wrapper.vm.selectPlayer(B)
    await flushPromises()

    expect(router.currentRoute.value.path).toBe(`/parent/players/${B}/sessions`)
  })

  it('on a per-child page that does not reload on a param change, falls back to the hub', async () => {
    const { wrapper, router } = await mountAt(`/parent/players/${A}/packs`)

    wrapper.vm.selectPlayer(B)
    await flushPromises()

    expect(router.currentRoute.value.path).toBe(`/parent/players/${B}/sessions`)
  })
})

describe('ParentChildSwitcher.vue — ordering', () => {
  it('marks the child active only AFTER the route already names it', async () => {
    const { wrapper, router, pinia } = await mountAt(`/parent/players/${A}/development`)
    const { usePlayerStore } = await import('src/stores/playerStore')
    const playerStore = usePlayerStore(pinia)
    let routeParamWhenActivated
    playerStore.setActivePlayer.mockImplementation(() => {
      routeParamWhenActivated = router.currentRoute.value.params.playerId
    })

    await wrapper.vm.selectPlayer(B)

    // ParentDevelopmentPortalPage pushes to activePlayerId whenever it differs from the route
    // param — activating first would trigger a second, competing navigation.
    expect(routeParamWhenActivated).toBe(B)
  })
})
