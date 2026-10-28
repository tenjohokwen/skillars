// The ≥95% storage upgrade prompt linked to '/upgrade', a route that never existed. It now links to
// the caller's own subscription page, or is hidden when the role has none.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

import QuotaUsageBar from 'src/components/video/QuotaUsageBar.vue'
import { usePlayerStore } from 'src/stores/playerStore'

const STUB = { template: '<div />' }
const PLAYER_ID = '893573203704173564'
const mounted = []

async function mountBar({ role, selfPlayerId = null, usedPercent = 96 }) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: STUB },
      { path: '/coach/subscription', name: 'coach-subscription', component: STUB },
      {
        path: '/parent/player/:playerId/subscription',
        name: 'player-subscription',
        component: STUB,
      },
    ],
  })
  router.push('/')
  await router.isReady()
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role }, player: { selfPlayerId } },
  })
  usePlayerStore(pinia).fetchSelfPlayerId.mockResolvedValue(selfPlayerId)
  const wrapper = mount(QuotaUsageBar, {
    props: {
      storageUsedBytes: usedPercent,
      storageLimitBytes: 100,
      bandwidthUsedBytes: 0,
      bandwidthLimitBytes: 100,
    },
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

describe('QuotaUsageBar.vue — upgrade link target', () => {
  it('links a self-registered player to their own player-subscription page', async () => {
    const { wrapper } = await mountBar({ role: 'PLAYER', selfPlayerId: PLAYER_ID })
    const link = wrapper.find('.quota-upgrade-prompt a')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe(`/parent/player/${PLAYER_ID}/subscription`)
  })

  it('links a coach to the coach subscription page', async () => {
    const { wrapper } = await mountBar({ role: 'COACH' })
    expect(wrapper.find('.quota-upgrade-prompt a').attributes('href')).toBe('/coach/subscription')
  })

  it('never links to the non-existent /upgrade route, and hides the prompt for a role without its own subscription', async () => {
    const { wrapper } = await mountBar({ role: 'PARENT' })
    expect(wrapper.find('.quota-upgrade-prompt').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/upgrade')
  })

  it('shows no prompt below the 95% threshold', async () => {
    const { wrapper } = await mountBar({ role: 'COACH', usedPercent: 50 })
    expect(wrapper.find('.quota-upgrade-prompt').exists()).toBe(false)
  })
})
