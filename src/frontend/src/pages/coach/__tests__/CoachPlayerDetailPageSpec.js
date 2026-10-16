// skillars-12-4 AC12/Task 6.1: built on ParentDevelopmentPortalPageSpec.js's setup precedent —
// vi.mock('src/boot/axios', ...) + createTestingPinia + a real memory router, rather than
// vi.mock('development.api.js') (which would need to supply all of development.store.js's
// named exports or throw). `stubActions: false` is deliberate, unlike the Parent spec: this
// page's AC6/Task 4.0 fixes live inside the store's REAL fetchExposure/fetchCoachContributions
// implementations (clear-before-fetch, the errorMsg.message error path), so stubbing those
// actions away would make this spec unable to exercise (or catch a regression in) the very
// behavior it exists to cover.
//
// Uses a realistic 18-19 digit Tsid string for playerId (reused from that same spec file) so a
// Number(route.params.playerId) regression on this page would actually fail the test.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

import { api } from 'src/boot/axios'
import CoachPlayerDetailPage from 'src/pages/coach/CoachPlayerDetailPage.vue'
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'

const LARGE_PLAYER_ID = '893573203704173564'
const CORRUPTED_PLAYER_ID = '893573203704173600'

const SKILL_DEFINITIONS = [
  { code: 'PAC', displayName: 'Pace' },
  { code: 'SHO', displayName: 'Shooting' },
]

const mounted = []

async function mountPage(playerId = LARGE_PLAYER_ID, playerName = 'Alex Kane') {
  const pinia = createTestingPinia({ createSpy: vi.fn, stubActions: false })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/coach/players', name: 'coach-players', component: { template: '<div />' } },
      {
        path: '/coach/players/:playerId',
        name: 'coach-player-detail',
        component: CoachPlayerDetailPage,
      },
    ],
  })
  router.push({ name: 'coach-player-detail', params: { playerId }, query: { playerName } })
  await router.isReady()

  // Not shallow: @vue/test-utils stubs render no slot content by default
  // (config.global.renderStubDefaultSlot is false), which would blank out QPage/QCard's own
  // children — i.e. this page's actual content. PlayerTimelinePanel is stubbed individually
  // instead: it independently calls the real (unstubbed) store.fetchTimeline, whose shape this
  // spec isn't about, and its own internal rendering (store.timeline.events.length etc.) assumes
  // a real timeline response this spec's generic axios mock doesn't model.
  const wrapper = mount(CoachPlayerDetailPage, {
    global: { plugins: [pinia, router], stubs: { PlayerTimelinePanel: true } },
  })
  mounted.push(wrapper)
  return { wrapper, pinia, router }
}

function mockApiGetByUrl(responses) {
  api.get.mockImplementation((url) => {
    const match = Object.keys(responses).find((pattern) => url.includes(pattern))
    if (!match) return Promise.resolve([])
    const entry = responses[match]
    return entry.reject ? Promise.reject(entry.reject) : Promise.resolve(entry.resolve)
  })
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachPlayerDetailPage.vue (skillars-12-4)', () => {
  it('fetches with the exact route-param id, never a Number-corrupted one', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [] } },
      'coach-contributions': { resolve: [] },
    })

    await mountPage()
    await flushPromises()

    const exposureCall = api.get.mock.calls.find(([url]) => url.includes('/exposure'))
    expect(exposureCall[0]).toContain(LARGE_PLAYER_ID)
    expect(exposureCall[0]).not.toContain(CORRUPTED_PLAYER_ID)
  })

  it('shows a loading spinner while fetches are in flight', async () => {
    api.get.mockReturnValue(new Promise(() => {}))

    const { wrapper } = await mountPage()

    expect(wrapper.findComponent({ name: 'QInnerLoading' }).props('showing')).toBe(true)
  })

  it('shows the error banner and hides all three sections on a rejected fetch (AC6, review Patch 1/5)', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      // No errorMsg at all — exercises the store's translated-default fallback path.
      exposure: { reject: { response: { data: {} } } },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    // The banner renders the page's own generic, translated coach.playerDetailError message —
    // not the raw per-call store message, which can (and here does) describe a different one of
    // the page's concurrent fetches than the one that actually failed.
    expect(wrapper.text()).toContain('coach.playerDetailError')
    // Review Patch 1: a partial failure hides every section rather than showing empty states
    // that read as verified facts about the player (e.g. "no neglected skills") next to the
    // error banner.
    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(false)
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(false)
  })

  it('renders all three sections on a populated load', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: ['PAC'] } },
      'coach-contributions': {
        resolve: [{ coachDisplayName: 'Coach A', skillCode: 'PAC', percentageContribution: 60 }],
      },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Pace')
    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(true)
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
    expect(wrapper.findComponent(PlayerTimelinePanel).props('playerId')).toBe(LARGE_PLAYER_ID)
  })

  it('shows the empty-neglected-skills state when none are open', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [] } },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailNoNeglectedSkills')
  })

  it('shows the AC9 empty-contributions state when topAttributions filters to empty', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [] } },
      // Below the component's own 30% top-attribution threshold.
      'coach-contributions': {
        resolve: [{ coachDisplayName: 'Coach A', skillCode: 'PAC', percentageContribution: 10 }],
      },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailNoContributions')
    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(false)
  })

  // Review Patch 6: AC7's player-switch path had zero coverage — none of the tests above ever
  // change the route param, so the `watch`, the `:key` remount, and the store's clear-before-fetch
  // were all unexercised.
  it('re-fetches and shows the new player on a route-param-only change, with no stale data (AC7)', async () => {
    const PLAYER_A = LARGE_PLAYER_ID
    const PLAYER_B = '893573203704173577'

    api.get.mockImplementation((url) => {
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure')) {
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: url.includes(PLAYER_A) ? ['PAC'] : [],
        })
      }
      if (url.includes('coach-contributions')) return Promise.resolve([])
      return Promise.resolve([])
    })

    const { wrapper, router } = await mountPage(PLAYER_A, 'Player A')
    await flushPromises()
    expect(wrapper.text()).toContain('Pace')

    await router.push({
      name: 'coach-player-detail',
      params: { playerId: PLAYER_B },
      query: { playerName: 'Player B' },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Pace')
    expect(wrapper.text()).toContain('coach.playerDetailNoNeglectedSkills')
    const exposureCallForB = api.get.mock.calls.find(
      ([url]) => url.includes('/exposure') && url.includes(PLAYER_B),
    )
    expect(exposureCallForB).toBeDefined()
    expect(wrapper.findComponent(PlayerTimelinePanel).props('playerId')).toBe(PLAYER_B)
  })
})
