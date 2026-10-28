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

async function mountPage(playerId = LARGE_PLAYER_ID, initialState = {}, { shallow = true } = {}) {
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
      { path: '/parent/players/:playerId/development', component: STUB },
      { path: '/parent/players/:playerId/packs', component: STUB },
      { path: '/parent/player/:playerId/subscription', component: STUB },
      { path: '/parent/approvals', component: STUB },
    ],
  })
  router.push(`/parent/players/${playerId}/sessions`)
  await router.isReady()

  const wrapper = mount(ParentPlayerPortalPage, {
    shallow,
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

// Navigation reachability: this page is now the per-child hub.
describe('ParentPlayerPortalPage.vue — child hub', () => {
  it('links to every per-child destination for THIS child (exact Tsid, not corrupted)', async () => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, {}, { shallow: false })

    for (const href of [
      `/parent/players/${LARGE_PLAYER_ID}/development`,
      `/parent/players/${LARGE_PLAYER_ID}/packs`,
      `/parent/player/${LARGE_PLAYER_ID}/subscription`,
      `/player/locker-room/${LARGE_PLAYER_ID}`,
      `/parent/players/${LARGE_PLAYER_ID}/videos`,
      '/parent/approvals',
    ]) {
      expect(wrapper.find(`[href="${href}"]`).exists()).toBe(true)
    }
  })

  it("shows the child's name from the parent's linked players", async () => {
    const { wrapper } = await mountPage(
      LARGE_PLAYER_ID,
      { player: { players: [{ id: LARGE_PLAYER_ID, name: 'Mia' }] } },
      { shallow: false },
    )
    expect(wrapper.find('[data-testid="child-hub-name"]').text()).toBe('Mia')
  })

  it('marks the child on screen as the active child (keeps the header switcher in step)', async () => {
    const { pinia } = await mountPage()
    const { usePlayerStore } = await import('src/stores/playerStore')
    expect(usePlayerStore(pinia).setActivePlayer).toHaveBeenCalledWith(LARGE_PLAYER_ID)
  })

  it('reloads for the new child when only :playerId changes (component reuse across children)', async () => {
    const { pinia, router } = await mountPage()
    const { useBookingStore } = await import('src/stores/booking.store')
    const bookingStore = useBookingStore(pinia)

    await router.push('/parent/players/893573203704173999/sessions')
    await flushPromises()

    expect(bookingStore.loadParentSchedule).toHaveBeenLastCalledWith('893573203704173999')
    expect(bookingStore.loadPlayerPacks).toHaveBeenLastCalledWith('893573203704173999')
    // Mutation check: revert playerId to a setup-time const + drop the route-param watch → the
    // last call is still the original id, RED.
  })

  it('shows an error instead of a possibly-stale session list when the schedule load failed', async () => {
    const { wrapper } = await mountPage(
      LARGE_PLAYER_ID,
      {
        booking: {
          parentScheduleError: new Error('500'),
          parentSchedule: { sessions: [{ bookingId: 'old-child-booking' }] },
        },
      },
      { shallow: false },
    )
    expect(wrapper.find('[data-testid="child-hub-schedule-error"]').exists()).toBe(true)
  })

  it('serializes loads: a switch while a load is in flight runs the new child last', async () => {
    const { pinia, router } = await mountPage()
    const { useBookingStore } = await import('src/stores/booking.store')
    const bookingStore = useBookingStore(pinia)
    bookingStore.loadParentSchedule.mockClear()

    let releaseFirst
    bookingStore.loadParentSchedule.mockImplementationOnce(
      () => new Promise((resolve) => (releaseFirst = resolve)),
    )
    await router.push('/parent/players/111/sessions')
    await flushPromises()
    await router.push('/parent/players/222/sessions')
    await flushPromises()
    // 222's load has not started while 111's is still in flight.
    expect(bookingStore.loadParentSchedule.mock.calls.map((c) => c[0])).toEqual(['111'])

    releaseFirst()
    await flushPromises()
    expect(bookingStore.loadParentSchedule.mock.calls.map((c) => c[0])).toEqual(['111', '222'])
    // Mutation check: replace the loadChain with a direct await → both start at once, RED above.
  })
})
