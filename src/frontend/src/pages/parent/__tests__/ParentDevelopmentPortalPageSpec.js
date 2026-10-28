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
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'

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

// deferred-152 AC2: `shallow: true` (above) stubs out <q-page> with no slot content at all
// (config.global.renderStubDefaultSlot is false), which would blank out this page's entire body
// — including the CoachContributionSection empty-state text this describe block below asserts
// on. Mirrors CoachPlayerDetailPageSpec.js's own documented precedent: mount non-shallow and
// stub only the heavy children unrelated to what's under test.
async function mountPageForContributions(playerId = LARGE_PLAYER_ID, initialState = {}) {
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
    global: {
      plugins: [pinia, router],
      stubs: {
        SkillsRadarChart: true,
        SluNarrativeSummary: true,
        SkillExposureBarChart: true,
        PerformanceReportsPanel: true,
        PlayerTimelinePanel: true,
        SessionPackTracker: true,
        SkillTrendsPanel: true,
      },
    },
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

describe('ParentDevelopmentPortalPage.vue — coach-contribution empty state (deferred-152 AC2)', () => {
  it('renders CoachContributionSection when a contribution clears the 30% threshold', async () => {
    const { wrapper } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { coachContributions: [{ skillCode: 'PAC', percentageContribution: 40 }] },
    })

    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(true)
  })

  it('shows the genuinely-no-data message when there is no contribution data at all', async () => {
    const { wrapper } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { coachContributions: [] },
    })

    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(false)
    expect(wrapper.text()).toContain('development.portal.noContributionsState')
    expect(wrapper.text()).not.toContain('development.portal.contributionsBelowThresholdState')
  })

  it('shows the below-threshold message when contribution data exists but none clears 30%', async () => {
    const { wrapper } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { coachContributions: [{ skillCode: 'PAC', percentageContribution: 10 }] },
    })

    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(false)
    expect(wrapper.text()).toContain('development.portal.contributionsBelowThresholdState')
    expect(wrapper.text()).not.toContain('development.portal.noContributionsState')
  })
})

// UI-gap chunk C7 — A14 (radar baseline toggle, previously hardcoded :show-baseline="false") and
// A15 (the SkillTrendsPanel the coach and player views already show).
describe('ParentDevelopmentPortalPage.vue — baseline toggle and skill trends (A14, A15)', () => {
  const radar = (baselineScore) => ({
    skills: [{ skillCode: 'PAC', compositeScore: 7, baselineScore }],
  })

  it('offers the baseline toggle only when a scored skill has a baseline, and drives the chart', async () => {
    const { wrapper } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { radarDisplay: radar(5) },
    })
    const chart = wrapper.findComponent({ name: 'SkillsRadarChart' })
    expect(chart.props('showBaseline')).toBe(false)

    const toggle = wrapper.findComponent('[data-testid="parent-radar-baseline-toggle"]')
    expect(toggle.exists()).toBe(true)
    await toggle.vm.$emit('update:modelValue', true)

    expect(chart.props('showBaseline')).toBe(true)
  })

  it('hides the baseline toggle when no skill has a baseline', async () => {
    const { wrapper } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { radarDisplay: radar(null) },
    })
    expect(wrapper.find('[data-testid="parent-radar-baseline-toggle"]').exists()).toBe(false)
  })

  it('fetches and renders skill trends for the exact route-param player', async () => {
    const { wrapper, pinia } = await mountPageForContributions(LARGE_PLAYER_ID, {
      development: { skillTrends: [{ skillCode: 'PAC', direction: 'UP' }] },
    })
    const { useDevelopmentStore } = await import('src/stores/development.store')
    const store = useDevelopmentStore(pinia)

    expect(store.fetchSkillTrends).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    expect(wrapper.find('[data-testid="parent-skill-trends-card"]').exists()).toBe(true)
    const panel = wrapper.findComponent({ name: 'SkillTrendsPanel' })
    expect(panel.props('trends')).toEqual([{ skillCode: 'PAC', direction: 'UP' }])
  })
})
