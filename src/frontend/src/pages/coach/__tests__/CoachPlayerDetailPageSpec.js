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

// skillars-12-9: the page now notifies on write failures; useQuasar().notify is the observable.
const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

import { api } from 'src/boot/axios'
import CoachPlayerDetailPage from 'src/pages/coach/CoachPlayerDetailPage.vue'
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'
import CoachRadarCard from 'src/components/development/CoachRadarCard.vue'
import SluNarrativeSummary from 'src/components/development/SluNarrativeSummary.vue'
import SluTargetEditor from 'src/components/development/SluTargetEditor.vue'
import PerformanceReportsPanel from 'src/components/development/PerformanceReportsPanel.vue'
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
      // The correlation teaser's upgrade CTA links here.
      { path: '/coach/subscription', component: { template: '<div />' } },
      { path: '/messaging', name: 'messaging', component: { template: '<div />' } },
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
      // skillars-12-9: the Chart.js charts need a real canvas (happy-dom has none), so they are
      // stubbed; SkillsRadarChart is stubbed too so a test can emit a skill selection from it.
      stubs: {
        PlayerTimelinePanel: true,
        SkillExposureBarChart: true,
        SkillExposureTrendChart: true,
        SkillsRadarChart: true,
      },
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

// skillars-12-9: unmatched URLs resolve to a REALISTIC per-endpoint default, not a bare []:
// RadarAssessmentHistoryList reads radarEntries.entries.length, the radar card reads
// radarDisplay.skills, etc. Order matters: the first matching substring wins.
function defaultFor(url) {
  if (url.includes('/radar/entries')) return { entries: [] }
  if (url.includes('/radar/display')) return { skills: [] }
  if (url.includes('/radar/preferences')) return { selectedSkillCodes: [] }
  if (url.includes('/radar/correlation')) return { insufficientData: true, minimumSessionCount: 5 }
  if (url.includes('/coaches/me/tier')) return { tier: 'INSTRUCTOR' }
  return []
}

function mockApiGetByUrl(responses) {
  api.get.mockImplementation((url) => {
    const match = Object.keys(responses).find((pattern) => url.includes(pattern))
    if (!match) return Promise.resolve(defaultFor(url))
    const entry = responses[match]
    return entry.reject ? Promise.reject(entry.reject) : Promise.resolve(entry.resolve)
  })
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
  vi.useRealTimers()
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

  // skillars-12-10 AC7: a 403 on a player-scoped core fetch (no relationship / access window closed) gets its
  // own neutral message, not the generic load error.
  it('shows the access-ended message on a 403 from a core player-scoped fetch', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { reject: { response: { status: 403, data: { errorMsg: { message: 'nope' } } } } },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailAccessEnded')
    expect(wrapper.text()).not.toContain('coach.playerDetailError')
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(false)
  })

  it('keeps the generic message for a non-403 core failure', async () => {
    mockApiGetByUrl({
      'skill-definitions': { resolve: SKILL_DEFINITIONS },
      exposure: { reject: { response: { status: 500, data: {} } } },
      'coach-contributions': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.playerDetailError')
    expect(wrapper.text()).not.toContain('coach.playerDetailAccessEnded')
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
      // skillars-12-9 D12.9-A: the neglected card is fed by /neglected-skills, not by /exposure.
      'neglected-skills': { resolve: [{ skillCode: 'PAC', detectedAt: '2026-10-01T08:00:00Z' }] },
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
      if (url.includes('neglected-skills')) {
        return Promise.resolve(
          url.includes(PLAYER_A) ? [{ skillCode: 'PAC', detectedAt: '2026-10-01T08:00:00Z' }] : [],
        )
      }
      if (url.includes('coach-contributions')) return Promise.resolve([])
      return Promise.resolve(defaultFor(url))
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
      return Promise.resolve(defaultFor(url))
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

// ---------------------------------------------------------------------------------------------
// skillars-12-9: coach player workspace parity (G16-G18)
// ---------------------------------------------------------------------------------------------

const PLAYER_A = LARGE_PLAYER_ID
const PLAYER_B = '893573203704173577'

const CORE = {
  'skill-definitions': { resolve: SKILL_DEFINITIONS },
  '/exposure': {
    resolve: { currentWeek: {}, trend: [], neglectedSkillCodes: [], playerName: 'Alex Kane' },
  },
  'coach-contributions': { resolve: [] },
}

function tier(name) {
  return { 'coaches/me/tier': { resolve: { tier: name } } }
}

function forbidden(errorKey, message = 'Denied') {
  return { response: { status: 403, data: { helpCode: 'abc', errorMsg: { errorKey, message } } } }
}

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function callsTo(fragment) {
  return api.get.mock.calls.filter(([url]) => url.includes(fragment))
}

const find = (wrapper, id) => wrapper.find(`[data-testid="${id}"]`)

describe('CoachPlayerDetailPage.vue — read parity (skillars-12-9 G16)', () => {
  it('renders every new section with data (AC1)', async () => {
    mockApiGetByUrl({
      ...CORE,
      ...tier('ACADEMY'),
      '/exposure': {
        resolve: {
          currentWeek: { PAC: 2 },
          trend: [{ isoYear: 2026, isoWeek: 40, totalsBySkill: { PAC: 2 } }],
          neglectedSkillCodes: [],
          playerName: 'Alex Kane',
        },
      },
      '/narrative': { resolve: [{ key: 'narr.one', params: {} }] },
      'neglected-skills': { resolve: [{ skillCode: 'PAC', detectedAt: '2026-10-01T08:00:00Z' }] },
      '/radar/display': { resolve: { skills: [{ skillCode: 'PAC', compositeScore: 70 }] } },
      '/reports': { resolve: [] },
    })

    const { wrapper } = await mountPage()
    await flushPromises()

    expect(wrapper.findComponent(CoachRadarCard).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SkillsRadarChart' }).props('skills')).toHaveLength(1)
    expect(wrapper.findComponent(SluNarrativeSummary).exists()).toBe(true)
    expect(wrapper.text()).toContain('narr.one')
    expect(wrapper.findComponent({ name: 'SkillExposureBarChart' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SkillExposureTrendChart' }).exists()).toBe(true)
    expect(wrapper.findComponent(PerformanceReportsPanel).props('isCoach')).toBe(true)
    expect(find(wrapper, 'coach-radar-history-card').exists()).toBe(true)
    expect(find(wrapper, 'coach-correlation-card').exists()).toBe(true)
    expect(find(wrapper, 'coach-add-assessment').exists()).toBe(true)
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
  })

  it('passes the player name to the reports panel so the generate dialog title is not blank', async () => {
    mockApiGetByUrl({ ...CORE })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.findComponent(PerformanceReportsPanel).props('playerName')).toBe('Alex Kane')
  })

  it('shows a narrative card with its empty state when there is no narrative', async () => {
    mockApiGetByUrl({ ...CORE, '/narrative': { resolve: [] } })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(find(wrapper, 'coach-narrative-card').exists()).toBe(true)
    expect(find(wrapper, 'coach-narrative-card').text()).toContain('coach.playerDetailNoNarrative')
  })

  it('lists neglected skills oldest streak first with a locale-formatted since-date (AC7)', async () => {
    const flags = [
      { skillCode: 'SHO', detectedAt: '2026-10-10T08:00:00Z' },
      { skillCode: 'PAC', detectedAt: '2026-10-01T08:00:00Z' },
    ]
    mockApiGetByUrl({ ...CORE, 'neglected-skills': { resolve: flags } })
    const { wrapper } = await mountPage()
    await flushPromises()

    const text = find(wrapper, 'coach-neglected-card').text()
    expect(text.indexOf('Pace')).toBeGreaterThan(-1)
    expect(text.indexOf('Pace')).toBeLessThan(text.indexOf('Shooting'))
    expect(text).toContain(new Date(flags[1].detectedAt).toLocaleDateString('en-US'))
    expect(callsTo('/neglected-skills')[0][0]).toContain(LARGE_PLAYER_ID)
  })

  describe.each([
    ['radar display', '/radar/display', 'coach-radar-error'],
    ['narrative', '/narrative', 'coach-narrative-error'],
    ['neglected skills', 'neglected-skills', 'coach-neglected-error'],
    ['targets', '/targets', 'coach-targets-error'],
    ['radar entries', '/radar/entries', 'coach-radar-history-error'],
  ])('failure isolation: %s (AC2)', (_label, endpoint, testId) => {
    it("shows only that card's message for a 403 FeatureGated and never blanks the page", async () => {
      mockApiGetByUrl({
        ...CORE,
        [endpoint]: { reject: forbidden('security.featureGated') },
      })
      const { wrapper } = await mountPage()
      await flushPromises()

      expect(find(wrapper, testId).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('coach.playerDetailError')
      expect(wrapper.text()).toContain('Alex Kane')
      expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
      expect(wrapper.findComponent(CoachRadarCard).exists()).toBe(true)
      expect(wrapper.findComponent(PerformanceReportsPanel).exists()).toBe(true)
    })
  })

  it('a correlation failure (ACADEMY) shows only in the correlation card and never blanks the page', async () => {
    mockApiGetByUrl({
      ...CORE,
      ...tier('ACADEMY'),
      '/radar/correlation': { reject: { response: { status: 500, data: {} } } },
    })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-correlation-card').text()).toContain(
      'development.radar.correlationError',
    )
    expect(wrapper.text()).not.toContain('coach.playerDetailError')
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
  })

  it('a failed report generate keeps the already-loaded reports list visible', async () => {
    mockApiGetByUrl({
      ...CORE,
      '/reports': { resolve: [{ id: 'r1', coachName: 'Me', generatedAt: '2026-10-01T10:00:00Z' }] },
    })
    api.post.mockRejectedValue(forbidden('security.opForbidden', 'Relationship not active'))
    const { wrapper, pinia } = await mountPage()
    await flushPromises()
    expect(find(wrapper, 'coach-reports-card').findAll('.q-item')).toHaveLength(1)

    await useDevelopmentStore(pinia)
      .generateReport(LARGE_PLAYER_ID, 'next steps')
      .catch(() => {})
    await flushPromises()

    expect(find(wrapper, 'coach-reports-card').findAll('.q-item')).toHaveLength(1)
    expect(find(wrapper, 'coach-reports-card').text()).not.toContain('Relationship not active')
  })

  it('shows a translated empty state in the trend card when there is no trend data (AC1)', async () => {
    mockApiGetByUrl({ ...CORE })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-trend-empty').text()).toContain('coach.playerDetailNoTrend')
  })

  it('reports list failure shows in the reports panel only', async () => {
    mockApiGetByUrl({
      ...CORE,
      '/reports': { reject: forbidden('security.opForbidden', 'No reports for you') },
    })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-reports-card').text()).toContain('No reports for you')
    expect(wrapper.text()).not.toContain('coach.playerDetailError')
    expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
  })

  it('a failed preferences GET shows a message in the radar card and falls back to all skills (AC12c)', async () => {
    mockApiGetByUrl({
      ...CORE,
      '/radar/preferences': { reject: forbidden('security.opForbidden') },
    })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-radar-prefs-error').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SkillsRadarChart' }).props('selectedSkillCodes')).toEqual(
      [],
    )
  })

  it('a core failure (exposure) still trips the page-level gate', async () => {
    mockApiGetByUrl({ ...CORE, '/exposure': { reject: { response: { data: {} } } } })
    const { wrapper } = await mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('coach.playerDetailError')
    expect(wrapper.findComponent(CoachRadarCard).exists()).toBe(false)
  })

  it("never shows player A's data under B, in flight or after B fails (AC3)", async () => {
    const pending = {}
    api.get.mockImplementation((url) => {
      const isB = url.includes(PLAYER_B)
      const who = isB ? 'B' : 'A'
      for (const key of [
        '/radar/display',
        '/narrative',
        'neglected-skills',
        '/reports',
        '/targets',
      ]) {
        if (url.includes(key)) {
          if (isB) {
            pending[key] = deferred()
            return pending[key].promise
          }
          if (key === '/radar/display')
            return Promise.resolve({ skills: [{ skillCode: 'PAC', who }] })
          if (key === '/narrative') return Promise.resolve([{ key: 'narr.fromA', params: {} }])
          if (key === 'neglected-skills')
            return Promise.resolve([{ skillCode: 'PAC', detectedAt: '2026-10-01T08:00:00Z' }])
          if (key === '/reports') return Promise.resolve([{ id: 'rA', coachName: 'CoachA' }])
          if (key === '/targets') return Promise.resolve([{ skillCode: 'PAC', weeklyTargetSlu: 3 }])
        }
      }
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure'))
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: [],
          playerName: isB ? 'Player B' : 'Player A',
        })
      return Promise.resolve(defaultFor(url))
    })

    const { wrapper, router } = await mountPage(PLAYER_A)
    await flushPromises()
    const store = useDevelopmentStore()
    expect(wrapper.text()).toContain('narr.fromA')
    expect(store.reports).toHaveLength(1)

    await router.push({ name: 'coach-player-detail', params: { playerId: PLAYER_B } })
    await flushPromises()

    // In flight for B: nothing of A's is visible or left in the store.
    expect(wrapper.text()).not.toContain('narr.fromA')
    expect(wrapper.text()).not.toContain('Pace')
    expect(store.radarDisplay).toBeNull()
    expect(store.narrative).toEqual([])
    expect(store.neglectedSkills).toEqual([])
    expect(store.targets).toEqual([])
    expect(store.reports).toEqual([])

    // B fails: still no A data, and the failure is B's own.
    for (const d of Object.values(pending)) d.reject(forbidden('security.opForbidden'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('narr.fromA')
    expect(store.radarDisplay).toBeNull()
    expect(store.reports).toEqual([])
    expect(store.narrative).toEqual([])
  })
})

describe('CoachPlayerDetailPage.vue — coach tools (skillars-12-9 G17)', () => {
  it('SCOUT: no assessment button, no radar history, no /radar/entries or /radar/correlation call; correlation teaser shown', async () => {
    mockApiGetByUrl({ ...CORE, ...tier('SCOUT') })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-add-assessment').exists()).toBe(false)
    expect(find(wrapper, 'coach-radar-history-card').exists()).toBe(false)
    expect(callsTo('/radar/entries')).toHaveLength(0)
    expect(callsTo('/radar/correlation')).toHaveLength(0)
    expect(find(wrapper, 'coach-correlation-card').exists()).toBe(true)
    expect(find(wrapper, 'coach-set-targets').exists()).toBe(true)
  })

  it('INSTRUCTOR: assessment + history, correlation teaser, no correlation call', async () => {
    mockApiGetByUrl({ ...CORE, ...tier('INSTRUCTOR') })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-add-assessment').exists()).toBe(true)
    expect(find(wrapper, 'coach-radar-history-card').exists()).toBe(true)
    expect(callsTo('/radar/entries')).toHaveLength(1)
    expect(callsTo('/radar/correlation')).toHaveLength(0)
    expect(find(wrapper, 'coach-correlation-card').exists()).toBe(true)
  })

  it('ACADEMY: calls /radar/correlation for the exact player', async () => {
    mockApiGetByUrl({ ...CORE, ...tier('ACADEMY') })
    await mountPage()
    await flushPromises()

    expect(callsTo('/radar/correlation')).toHaveLength(1)
    expect(callsTo('/radar/correlation')[0][0]).toContain(LARGE_PLAYER_ID)
  })

  // skillars-deferred-163 AC4.3: getCoachTier (marketplace.api.js) gives this call a scoped
  // timeout, so a hung tier fetch eventually settles (rejects) instead of leaving tierSettled
  // stuck forever -- the existing "failed tier fetch" test above covers the resulting UI; this
  // pins the wiring that makes a hang behave like any other rejection in the first place.
  it('fetches the coach tier with a scoped timeout', async () => {
    mockApiGetByUrl({ ...CORE, ...tier('INSTRUCTOR') })
    await mountPage()
    await flushPromises()

    const tierCall = callsTo('/coaches/me/tier')[0]
    expect(tierCall[1]).toEqual(expect.objectContaining({ timeout: 20000 }))
  })

  it('a failed tier fetch is "unknown", not SCOUT: tools hidden, one message, no upsell teaser (AC4)', async () => {
    mockApiGetByUrl({
      ...CORE,
      'coaches/me/tier': { reject: { response: { status: 500, data: {} } } },
    })
    const { wrapper } = await mountPage()
    await flushPromises()

    expect(find(wrapper, 'coach-tier-error').exists()).toBe(true)
    expect(find(wrapper, 'coach-correlation-card').exists()).toBe(false)
    expect(find(wrapper, 'coach-add-assessment').exists()).toBe(false)
    expect(find(wrapper, 'coach-radar-history-card').exists()).toBe(false)
    expect(callsTo('/radar/entries')).toHaveLength(0)
    expect(callsTo('/radar/correlation')).toHaveLength(0)
  })

  it("tier fetch still pending when switching A→B: B gets its gated fetches, A's are never issued (Task 3.1)", async () => {
    const tierGate = deferred()
    api.get.mockImplementation((url) => {
      if (url.includes('/coaches/me/tier')) return tierGate.promise
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure'))
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: [],
          playerName: 'P',
        })
      return Promise.resolve(defaultFor(url))
    })
    const { router } = await mountPage(PLAYER_A)
    await flushPromises()
    await router.push({ name: 'coach-player-detail', params: { playerId: PLAYER_B } })
    await flushPromises()

    tierGate.resolve({ tier: 'INSTRUCTOR' })
    await flushPromises()

    const entryCalls = callsTo('/radar/entries')
    expect(entryCalls).toHaveLength(1)
    expect(entryCalls[0][0]).toContain(PLAYER_B)
    expect(entryCalls[0][0]).not.toContain(PLAYER_A)
  })

  describe('targets data-loss guard (AC4)', () => {
    const targetsBtn = (wrapper) => find(wrapper, 'coach-set-targets')

    it('is disabled while GET /targets is pending and enabled after it succeeds', async () => {
      const gate = deferred()
      api.get.mockImplementation((url) => {
        if (url.includes('/targets')) return gate.promise
        if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
        if (url.includes('/exposure'))
          return Promise.resolve({
            currentWeek: {},
            trend: [],
            neglectedSkillCodes: [],
            playerName: 'P',
          })
        return Promise.resolve(defaultFor(url))
      })
      const { wrapper } = await mountPage()
      await flushPromises()
      expect(targetsBtn(wrapper).attributes('disabled')).toBeDefined()

      gate.resolve([{ skillCode: 'PAC', weeklyTargetSlu: 3 }])
      await flushPromises()
      expect(targetsBtn(wrapper).attributes('disabled')).toBeUndefined()
    })

    it('stays disabled, with a message, when GET /targets failed', async () => {
      mockApiGetByUrl({ ...CORE, '/targets': { reject: { response: { status: 500, data: {} } } } })
      const { wrapper } = await mountPage()
      await flushPromises()

      expect(targetsBtn(wrapper).attributes('disabled')).toBeDefined()
      expect(find(wrapper, 'coach-targets-error').exists()).toBe(true)
    })

    it('a successful save notifies and does NOT re-fetch exposure (code review: exposure does not depend on targets)', async () => {
      mockApiGetByUrl({ ...CORE })
      api.put.mockResolvedValue({})
      const { wrapper } = await mountPage()
      await flushPromises()
      const before = callsTo('/exposure').length

      wrapper
        .findComponent(SluTargetEditor)
        .vm.$emit('save', [{ skillCode: 'PAC', weeklyTargetSlu: 2 }])
      await flushPromises()

      expect(api.put).toHaveBeenCalledWith(expect.stringContaining('/targets'), [
        { skillCode: 'PAC', weeklyTargetSlu: 2 },
      ])
      expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
      expect(callsTo('/exposure').length).toBe(before)
      expect(wrapper.text()).toContain('Alex Kane')
    })

    it('a click on the disabled button while GET /targets is pending never opens the editor, so no all-null save can be sent', async () => {
      const gate = deferred()
      api.get.mockImplementation((url) => {
        if (url.includes('/targets')) return gate.promise
        if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
        if (url.includes('/exposure'))
          return Promise.resolve({
            currentWeek: {},
            trend: [],
            neglectedSkillCodes: [],
            playerName: 'P',
          })
        return Promise.resolve(defaultFor(url))
      })
      const { wrapper } = await mountPage()
      await flushPromises()

      await targetsBtn(wrapper).trigger('click')
      await flushPromises()

      expect(wrapper.findComponent(SluTargetEditor).props('modelValue')).toBe(false)
      expect(api.put).not.toHaveBeenCalled()
    })

    it('a failed save (write-relationship 403) notifies the server message and leaves the page intact (AC5)', async () => {
      mockApiGetByUrl({ ...CORE })
      api.put.mockRejectedValue(forbidden('security.opForbidden', 'Relationship not active'))
      const { wrapper } = await mountPage()
      await flushPromises()
      const before = callsTo('/exposure').length

      wrapper
        .findComponent(SluTargetEditor)
        .vm.$emit('save', [{ skillCode: 'PAC', weeklyTargetSlu: 2 }])
      await flushPromises()

      expect(notifySpy).toHaveBeenCalledWith({
        type: 'negative',
        message: 'Relationship not active',
      })
      expect(callsTo('/exposure').length).toBe(before)
      expect(wrapper.text()).toContain('Alex Kane')
      expect(wrapper.findComponent(PlayerTimelinePanel).exists()).toBe(true)
      expect(targetsBtn(wrapper).attributes('disabled')).toBeUndefined()
    })

    it('a failed post-save re-fetch disables the button until a successful reload', async () => {
      let targetsCalls = 0
      api.get.mockImplementation((url) => {
        if (url.includes('/targets')) {
          targetsCalls += 1
          return targetsCalls === 1
            ? Promise.resolve([{ skillCode: 'PAC', weeklyTargetSlu: 3 }])
            : Promise.reject({ response: { status: 500, data: {} } })
        }
        if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
        if (url.includes('/exposure'))
          return Promise.resolve({
            currentWeek: {},
            trend: [],
            neglectedSkillCodes: [],
            playerName: 'P',
          })
        return Promise.resolve(defaultFor(url))
      })
      api.put.mockResolvedValue({})
      const { wrapper } = await mountPage()
      await flushPromises()

      wrapper
        .findComponent(SluTargetEditor)
        .vm.$emit('save', [{ skillCode: 'PAC', weeklyTargetSlu: 2 }])
      await flushPromises()

      expect(targetsBtn(wrapper).attributes('disabled')).toBeDefined()
      expect(find(wrapper, 'coach-targets-error').exists()).toBe(true)
    })
  })
})

describe('CoachPlayerDetailPage.vue — radar preference persistence (skillars-12-9 AC12)', () => {
  const chart = (wrapper) => wrapper.findComponent({ name: 'SkillsRadarChart' })

  it("flushes a pending save with A's id on switch, and that save does not change B's selection", async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const putGate = deferred()
    api.put.mockReturnValue(putGate.promise)
    api.get.mockImplementation((url) => {
      if (url.includes('/radar/preferences'))
        return Promise.resolve({ selectedSkillCodes: url.includes(PLAYER_B) ? ['SHO'] : [] })
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure'))
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: [],
          playerName: 'P',
        })
      return Promise.resolve(defaultFor(url))
    })
    const { wrapper, router } = await mountPage(PLAYER_A)
    await flushPromises()

    chart(wrapper).vm.$emit('update:selectedSkillCodes', ['PAC'])
    await flushPromises()
    expect(api.put).not.toHaveBeenCalled() // still inside the 300ms debounce window

    await router.push({ name: 'coach-player-detail', params: { playerId: PLAYER_B } })
    await flushPromises()

    expect(api.put).toHaveBeenCalledTimes(1)
    expect(api.put.mock.calls[0][0]).toContain(PLAYER_A)
    expect(api.put.mock.calls[0][1]).toEqual({ selectedSkillCodes: ['PAC'] })

    expect(chart(wrapper).props('selectedSkillCodes')).toEqual(['SHO'])
    putGate.resolve({})
    await flushPromises()
    expect(chart(wrapper).props('selectedSkillCodes')).toEqual(['SHO'])
    expect(useDevelopmentStore().radarPreferences).toEqual({ selectedSkillCodes: ['SHO'] })
  })

  it('a late preferences response does not overwrite a click made after the fetch started', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.put.mockResolvedValue({})
    const prefsGate = deferred()
    api.get.mockImplementation((url) => {
      if (url.includes('/radar/preferences')) return prefsGate.promise
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure'))
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: [],
          playerName: 'P',
        })
      return Promise.resolve(defaultFor(url))
    })
    const { wrapper } = await mountPage(PLAYER_A)
    await flushPromises()

    chart(wrapper).vm.$emit('update:selectedSkillCodes', ['PAC'])
    prefsGate.resolve({ selectedSkillCodes: ['SHO'] })
    await flushPromises()

    expect(chart(wrapper).props('selectedSkillCodes')).toEqual(['PAC'])
  })

  it('applies the loaded preferences when nothing was clicked', async () => {
    mockApiGetByUrl({ ...CORE, '/radar/preferences': { resolve: { selectedSkillCodes: ['SHO'] } } })
    const { wrapper } = await mountPage(PLAYER_A)
    await flushPromises()
    expect(chart(wrapper).props('selectedSkillCodes')).toEqual(['SHO'])
  })

  it('a failed preferences save notifies with the server message (AC5)', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    api.put.mockRejectedValue(forbidden('security.opForbidden', 'Relationship not active'))
    mockApiGetByUrl({ ...CORE })
    const { wrapper } = await mountPage(PLAYER_A)
    await flushPromises()

    chart(wrapper).vm.$emit('update:selectedSkillCodes', ['PAC'])
    vi.advanceTimersByTime(400)
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: 'Relationship not active' })
  })
})

describe('CoachPlayerDetailPage.vue — out-of-order responses (skillars-12-9 AC3)', () => {
  it('a late radar/narrative response for A never overwrites B', async () => {
    const lateA = { display: deferred(), narrative: deferred() }
    api.get.mockImplementation((url) => {
      const isA = url.includes(PLAYER_A)
      if (url.includes('/radar/display')) {
        return isA ? lateA.display.promise : Promise.resolve({ skills: [{ skillCode: 'SHO' }] })
      }
      if (url.includes('/narrative')) {
        return isA ? lateA.narrative.promise : Promise.resolve([{ key: 'narr.fromB', params: {} }])
      }
      if (url.includes('skill-definitions')) return Promise.resolve(SKILL_DEFINITIONS)
      if (url.includes('/exposure'))
        return Promise.resolve({
          currentWeek: {},
          trend: [],
          neglectedSkillCodes: [],
          playerName: 'P',
        })
      return Promise.resolve(defaultFor(url))
    })
    const { wrapper, router } = await mountPage(PLAYER_A)
    await flushPromises()
    await router.push({ name: 'coach-player-detail', params: { playerId: PLAYER_B } })
    await flushPromises()

    lateA.display.resolve({ skills: [{ skillCode: 'PAC' }] })
    lateA.narrative.resolve([{ key: 'narr.fromA', params: {} }])
    await flushPromises()

    const store = useDevelopmentStore()
    expect(store.radarDisplay.skills).toEqual([{ skillCode: 'SHO' }])
    expect(wrapper.findComponent({ name: 'SkillsRadarChart' }).props('skills')).toEqual([
      { skillCode: 'SHO' },
    ])
    expect(wrapper.text()).toContain('narr.fromB')
    expect(wrapper.text()).not.toContain('narr.fromA')
  })
})

describe('CoachPlayerDetailPage.vue — Message player entry point (feat/messaging-completion)', () => {
  it('opens the coach/player conversation (coachId left to the backend) and lands on /messaging with it selected', async () => {
    mockApiGetByUrl({
      '/skill-definitions': { resolve: SKILL_DEFINITIONS },
      '/exposure': { resolve: { playerName: 'Alex', currentWeek: {}, history: [] } },
    })
    api.post.mockImplementation((url) =>
      url === '/api/messaging/conversations'
        ? Promise.resolve({ conversationId: '4242', status: 'ACTIVE', canSend: true })
        : Promise.resolve({}),
    )
    const { wrapper, router } = await mountPage()
    await flushPromises()

    await wrapper.find('[data-testid="coach-message-player"]').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/api/messaging/conversations', {
      playerId: LARGE_PLAYER_ID,
    })
    expect(router.currentRoute.value.name).toBe('messaging')
    expect(router.currentRoute.value.query.conversation).toBe('4242')
  })
})
