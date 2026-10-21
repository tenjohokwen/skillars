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
import { useDevelopmentStore } from 'src/stores/development.store.js'

const LARGE_PLAYER_ID = '893573203704173564'
const CORRUPTED_PLAYER_ID = '893573203704173600'

const SKILL_DEFINITIONS = [
  { code: 'PAC', displayName: 'Pace' },
  { code: 'SHO', displayName: 'Shooting' },
]

const mounted = []

// skillars-deferred-156 AC5: playerName no longer travels through the route's query string — the
// page now reads it off the /exposure fetch response instead, so mountPage no longer accepts or
// pushes a playerName query param. Callers that care about the rendered name set it on their own
// exposure mock's resolve object.
async function mountPage(playerId = LARGE_PLAYER_ID) {
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
  router.push({ name: 'coach-player-detail', params: { playerId } })
  await router.isReady()

  // Not shallow: @vue/test-utils stubs render no slot content by default
  // (config.global.renderStubDefaultSlot is false), which would blank out QPage/QCard's own
  // children — i.e. this page's actual content. PlayerTimelinePanel is stubbed individually
  // instead: it independently calls the real (unstubbed) store.fetchTimeline, whose shape this
  // spec isn't about, and its own internal rendering (store.timeline.events.length etc.) assumes
  // a real timeline response this spec's generic axios mock doesn't model.
  const wrapper = mount(CoachPlayerDetailPage, {
    global: {
      plugins: [pinia, router],
      stubs: { PlayerTimelinePanel: true },
      // skillars-deferred-156 AC5: setup-file.js's global $t mock only special-cases
      // params.date (for SkillsRadarChart's tooltip) — every other call, including this page's
      // own `$t('coach.playerDetailTitle', { playerName })`, returns the bare key with no
      // interpolation at all, so the rendered heading text never contains the player's name
      // under the shared default mock. Mirrors that same date-param convention for playerName,
      // scoped to this spec only, so AC5's "reads the name from the fetch response, not the
      // route query" behavior is actually observable in wrapper.text().
      mocks: {
        $t: (key, params) =>
          params && params.playerName
            ? params.playerName
            : params && params.date
              ? `Last assessed: ${params.date}`
              : key,
      },
    },
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
      exposure: {
        resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
      },
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
      exposure: {
        resolve: {
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: ['PAC'],
          playerName: 'Alex Kane',
        },
      },
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
      exposure: {
        resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
      },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailNoNeglectedSkills')
  })

  it('shows the AC9 empty-contributions state when topAttributions filters to empty', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: {
        resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
      },
      // Below the component's own 30% top-attribution threshold.
      'coach-contributions': {
        resolve: [{ coachDisplayName: 'Coach A', skillCode: 'PAC', percentageContribution: 10 }],
      },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    // deferred-152 AC2: data exists but didn't clear 30%, so this is the below-threshold
    // message, NOT the genuinely-no-data one -- the two were a single conflated string before
    // this story's split.
    expect(wrapper.text()).toContain('coach.playerDetailContributionsBelowThreshold')
    expect(wrapper.text()).not.toContain('coach.playerDetailNoContributions')
    expect(wrapper.findComponent(CoachContributionSection).exists()).toBe(false)
  })

  it('shows the genuinely-no-data empty-contributions state when there is no contribution data at all (deferred-152 AC2)', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: {
        resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
      },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailNoContributions')
    expect(wrapper.text()).not.toContain('coach.playerDetailContributionsBelowThreshold')
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
          playerName: url.includes(PLAYER_A) ? 'Player A' : 'Player B',
        })
      }
      if (url.includes('coach-contributions')) return Promise.resolve([])
      return Promise.resolve([])
    })

    const { wrapper, router } = await mountPage(PLAYER_A)
    await flushPromises()
    expect(wrapper.text()).toContain('Pace')
    expect(wrapper.text()).toContain('Player A')

    await router.push({ name: 'coach-player-detail', params: { playerId: PLAYER_B } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('Pace')
    expect(wrapper.text()).toContain('coach.playerDetailNoNeglectedSkills')
    expect(wrapper.text()).toContain('Player B')
    const exposureCallForB = api.get.mock.calls.find(
      ([url]) => url.includes('/exposure') && url.includes(PLAYER_B),
    )
    expect(exposureCallForB).toBeDefined()
    expect(wrapper.findComponent(PlayerTimelinePanel).props('playerId')).toBe(PLAYER_B)
  })

  // skillars-deferred-156 AC5: the regression this fix introduced and the AC explicitly calls out
  // to resolve — the heading renders above both the pageError guard and the loading content guard,
  // so it is always visible. On a fetch error with no prior successful load for THIS player, there
  // is no last-known name to fall back to, so the honest answer is the translated placeholder, not
  // the stale route-query value the old implementation would have shown forever.
  it('falls back to the translated placeholder in the heading when the exposure fetch fails with no prior name known (AC5)', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { reject: { response: { data: {} } } },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailUnknownPlayerName')
  })

  // (code review of skillars-deferred-156) The cache-HIT path — the entire reason
  // lastKnownPlayerNames exists, and previously the one link in the three-link fallback chain with
  // no test at all. The two sibling tests cover only the ends of the chain: one asserts the head
  // (store.exposure.playerName present) and one asserts the tail (nothing cached -> translated
  // placeholder). Both of those pass with the cache and its watcher deleted outright, so AC5's
  // actual resolution mechanism was unverified. This pins the middle: a successful load caches the
  // name, a later FAILED refetch for that same player nulls store.exposure, and the heading must
  // still show the real cached name rather than regressing to the placeholder.
  it('keeps showing the last known playerName in the heading when a later refetch for that same player fails (AC5)', async () => {
    let exposureCallCount = 0
    api.get.mockImplementation((url) => {
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure')) {
        exposureCallCount += 1
        // First load succeeds and seeds the cache; every later call fails.
        return exposureCallCount === 1
          ? Promise.resolve({
              currentWeek: {},
              trend: [],
              neglectedSkillCodes: [],
              playerName: 'Alex Kane',
            })
          : Promise.reject({ response: { data: {} } })
      }
      if (url.includes('coach-contributions')) return Promise.resolve([])
      return Promise.resolve([])
    })

    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('Alex Kane')

    // Refetch the SAME player and let it fail. Driven through the real store action (stubActions is
    // false) rather than a route change, because re-pushing an identical route param would not
    // retrigger the page's own watcher — and it is specifically the same-player refetch that must
    // fall back to the cache instead of the placeholder.
    const store = useDevelopmentStore()
    await store.fetchExposure(LARGE_PLAYER_ID)
    await flushPromises()

    expect(store.exposure).toBeNull()
    expect(exposureCallCount).toBeGreaterThan(1)
    expect(wrapper.text()).toContain('Alex Kane')
    expect(wrapper.text()).not.toContain('coach.playerDetailUnknownPlayerName')
  })

  // skillars-deferred-156 AC5: proves the heading reads playerName off the /exposure response
  // itself, not the route's own query string (which no longer carries it at all per mountPage's
  // own comment above).
  it('renders the heading using the playerName from the exposure response, not the route query', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: {
        resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
      },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Alex Kane')
  })
})
