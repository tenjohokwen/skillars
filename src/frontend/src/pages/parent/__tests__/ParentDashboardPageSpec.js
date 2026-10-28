// Navigation reachability — ParentDashboardPage.vue's child cards. Each card used to be a plain,
// non-interactive <div>; it now opens that child's hub and carries quick-action chips.
//
// Mutation check: hardcode the card link to a fixed path (or drop it) → the per-child href
// assertions go RED; ids are real Tsid-shaped strings so a Number(...) round-trip would also fail.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

import ParentDashboardPage from 'src/pages/parent/ParentDashboardPage.vue'

const MIA = '893573203704173564'
const LEO = '893573203704173565'
const mounted = []

async function mountPage(players) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role: 'PARENT' }, player: { players } },
  })
  const STUB = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: STUB },
      { path: '/:pathMatch(.*)*', component: STUB },
    ],
  })
  router.push('/')
  await router.isReady()
  // The page chains .catch/.finally on this action's promise; a bare testing-pinia spy returns
  // undefined.
  const { usePaymentStore } = await import('src/stores/payment.store')
  usePaymentStore(pinia).fetchCreditBalance.mockResolvedValue()
  const wrapper = mount(ParentDashboardPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('ParentDashboardPage.vue — child cards (navigation reachability)', () => {
  it('renders one card per child, each linking to that child hub', async () => {
    const wrapper = await mountPage([
      { id: MIA, name: 'Mia', ageTierLabel: 'U12' },
      { id: LEO, name: 'Leo', ageTierLabel: 'U10' },
    ])

    const hubLinks = wrapper
      .findAll('[data-testid="child-card-hub-link"]')
      .map((a) => a.attributes('href'))
    expect(hubLinks).toEqual([`/parent/players/${MIA}/sessions`, `/parent/players/${LEO}/sessions`])
  })

  it("gives each card quick-action chips to that child's development, packs and locker room", async () => {
    const wrapper = await mountPage([{ id: MIA, name: 'Mia', ageTierLabel: 'U12' }])

    const chipHrefs = wrapper
      .findAll('[data-testid="child-chip"]')
      .map((chip) => chip.attributes('href'))
    // No "sessions" chip: the card itself already links to the hub, whose content IS the sessions.
    expect(chipHrefs).toEqual([
      `/parent/players/${MIA}/development`,
      `/parent/players/${MIA}/packs`,
      `/player/locker-room/${MIA}`,
    ])
  })
})
