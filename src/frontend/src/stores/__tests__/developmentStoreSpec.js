// skillars-deferred-152 AC1/AC10 — development.store.js's hardcoded-English error-message sweep.
//
// No spec file covered this store before this story. This file adds focused coverage per
// touched action (not full-store coverage): each of the ten call sites AC1 rewires through
// `getErrorMessage` (src/utils/errorHandler.js) now surfaces the real, translated status-code
// fallback instead of a hardcoded English literal on a rejected API call.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { i18n } from 'boot/i18n'

vi.mock('src/api/development.api', () => ({
  getSkillDefinitions: vi.fn(),
  getSkillExposure: vi.fn(),
  getNarrativeSummary: vi.fn(),
  getMyTargets: vi.fn(),
  setMyTargets: vi.fn(),
  postRadarAssessment: vi.fn(),
  getMyRadarEntries: vi.fn(),
  getRadarDisplay: vi.fn(),
  getRadarPreferences: vi.fn(),
  putRadarPreferences: vi.fn(),
  getCorrelationInsights: vi.fn(),
  generateReport: vi.fn(),
  listReports: vi.fn(),
  getTimeline: vi.fn(),
  getCoachContributions: vi.fn(),
  getNeglectedSkills: vi.fn(),
  getSkillTrends: vi.fn(),
}))

import {
  getNarrativeSummary,
  getMyTargets,
  setMyTargets,
  getMyRadarEntries,
  postRadarAssessment,
  getRadarDisplay,
  putRadarPreferences,
  getCorrelationInsights,
  listReports,
  generateReport,
  getTimeline,
  getNeglectedSkills,
  getSkillExposure,
  getRadarPreferences,
  getSkillTrends,
} from 'src/api/development.api'
import { useDevelopmentStore } from 'src/stores/development.store'

function rejection(status) {
  return { response: { status, data: {} } }
}

describe('development.store — error-message i18n sweep (deferred-152 AC1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    i18n.global.locale.value = 'fr-FR'
  })

  afterEach(() => {
    i18n.global.locale.value = 'en-US'
  })

  it('fetchNarrative surfaces the translated fallback, not an English literal', async () => {
    getNarrativeSummary.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchNarrative('p1')
    expect(store.error).toBe(i18n.global.t('error.generic'))
    expect(store.error).not.toBe('Failed to load narrative summary')
  })

  it('fetchTargets surfaces the translated fallback', async () => {
    getMyTargets.mockRejectedValue(rejection(404))
    const store = useDevelopmentStore()
    await store.fetchTargets('p1')
    expect(store.error).toBe(i18n.global.t('error.notFound'))
  })

  // skillars-deferred-152 code review (2026-10-17): error.badRequest, not
  // validation.badRequest -- the latter is developer-facing copy ("Check method argument
  // mismatch, missing parameters e.t.c.") that this sweep was surfacing to end users.
  // skillars-12-9 AC4: saveTargets now rethrows a failed save so the page can tell it from a
  // failed post-save re-fetch (which lands in targetsError and never throws).
  it('saveTargets surfaces the translated fallback and rethrows', async () => {
    setMyTargets.mockRejectedValue(rejection(400))
    const store = useDevelopmentStore()
    await expect(store.saveTargets('p1', [])).rejects.toBeTruthy()
    expect(store.error).toBe(i18n.global.t('error.badRequest'))
    expect(store.error).not.toBe(i18n.global.t('validation.badRequest'))
  })

  it('fetchRadarEntries surfaces the translated fallback', async () => {
    getMyRadarEntries.mockRejectedValue(rejection(401))
    const store = useDevelopmentStore()
    await store.fetchRadarEntries('p1')
    expect(store.error).toBe(i18n.global.t('security.sessionExpired'))
  })

  it('submitRadarAssessment surfaces the translated fallback and still rethrows', async () => {
    postRadarAssessment.mockRejectedValue(rejection(403))
    const store = useDevelopmentStore()
    await expect(store.submitRadarAssessment('p1', {})).rejects.toBeTruthy()
    expect(store.error).toBe(i18n.global.t('security.opForbidden'))
  })

  it('fetchRadarDisplay surfaces the translated fallback', async () => {
    getRadarDisplay.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchRadarDisplay('p1')
    expect(store.radarDisplayLoading).toBe(false)
    expect(store.error).toBe(i18n.global.t('error.generic'))
  })

  it('saveRadarPreferences surfaces the translated fallback', async () => {
    putRadarPreferences.mockRejectedValue(rejection(404))
    const store = useDevelopmentStore()
    await expect(store.saveRadarPreferences('p1', ['SHOOTING'])).rejects.toBeTruthy()
    expect(store.error).toBe(i18n.global.t('error.notFound'))
  })

  it('fetchCorrelationInsights surfaces the translated fallback for a non-403 failure', async () => {
    getCorrelationInsights.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchCorrelationInsights('p1')
    expect(store.error).toBe(i18n.global.t('error.generic'))
  })

  it('fetchCorrelationInsights stays silent (no error) for a 403 (non-Academy teaser)', async () => {
    getCorrelationInsights.mockRejectedValue(rejection(403))
    const store = useDevelopmentStore()
    store.error = null
    await store.fetchCorrelationInsights('p1')
    expect(store.error).toBeNull()
    expect(store.correlationInsights).toBeNull()
  })

  it('fetchReports surfaces the translated fallback', async () => {
    listReports.mockRejectedValue(rejection(404))
    const store = useDevelopmentStore()
    await store.fetchReports('p1')
    expect(store.reportsError).toBe(i18n.global.t('error.notFound'))
  })

  // skillars-12-9 AC5: a failed generate rethrows (the dialog notifies) but must NOT write
  // reportsError, which would replace the already-loaded reports list with a banner.
  it('generateReport rethrows and leaves reportsError and reports untouched', async () => {
    generateReport.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    store.reports = [{ id: 'r1' }]
    await expect(store.generateReport('p1', 'next steps')).rejects.toBeTruthy()
    expect(store.reportsError).toBeNull()
    expect(store.reports).toEqual([{ id: 'r1' }])
    expect(store.reportGenerating).toBe(false)
  })
})

// skillars-12-9 Task 1: multi-section hardening — per-section error refs, clear-before-fetch and
// out-of-order response guards on every player-scoped fetch the coach page issues.
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('development.store — multi-section hardening (skillars-12-9)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  const SEQ_CASES = [
    ['fetchNarrative', getNarrativeSummary, 'narrative', [], [{ key: 'A' }]],
    ['fetchTargets', getMyTargets, 'targets', [], [{ skillCode: 'PAC' }]],
    ['fetchRadarEntries', getMyRadarEntries, 'radarEntries', null, { entries: [{ a: 1 }] }],
    ['fetchRadarDisplay', getRadarDisplay, 'radarDisplay', null, { skills: [{ a: 1 }] }],
    [
      'fetchRadarPreferences',
      getRadarPreferences,
      'radarPreferences',
      null,
      { selectedSkillCodes: ['PAC'] },
    ],
    ['fetchCorrelationInsights', getCorrelationInsights, 'correlationInsights', null, { a: 1 }],
    ['fetchReports', listReports, 'reports', [], [{ id: 'r1' }]],
    ['fetchNeglectedSkills', getNeglectedSkills, 'neglectedSkills', [], [{ skillCode: 'PAC' }]],
  ]

  it.each(SEQ_CASES)(
    "%s keeps only the latest player's response when responses arrive out of order",
    async (action, apiFn, stateKey, empty, valueA) => {
      const a = deferred()
      const b = deferred()
      apiFn.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
      const store = useDevelopmentStore()

      const pA = store[action]('A')
      const pB = store[action]('B')
      const valueB = Array.isArray(valueA) ? [{ ...valueA[0], who: 'B' }] : { ...valueA, who: 'B' }
      b.resolve(valueB)
      await pB
      a.resolve(valueA)
      await pA

      expect(store[stateKey]).toEqual(valueB)
    },
  )

  it.each(SEQ_CASES)(
    '%s clears its data at fetch start',
    async (action, apiFn, stateKey, empty, valueA) => {
      apiFn.mockResolvedValueOnce(valueA)
      const store = useDevelopmentStore()
      await store[action]('A')
      const pending = deferred()
      apiFn.mockReturnValueOnce(pending.promise)

      const p = store[action]('B')
      expect(store[stateKey]).toEqual(empty)
      pending.resolve(valueA)
      await p
    },
  )

  it('a failed secondary fetch sets only its own error ref (plus the legacy error where written today)', async () => {
    getNarrativeSummary.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchNarrative('p1')
    expect(store.narrativeError).toBe(i18n.global.t('error.generic'))
    expect(store.error).toBe(i18n.global.t('error.generic'))
    expect(store.targetsError).toBeNull()
    expect(store.radarDisplayError).toBeNull()
    expect(store.narrativeLoading).toBe(false)
  })

  it("a failed secondary fetch does not erase another section's error", async () => {
    getNarrativeSummary.mockRejectedValue(rejection(500))
    getMyTargets.mockResolvedValue([])
    const store = useDevelopmentStore()
    await store.fetchNarrative('p1')
    await store.fetchTargets('p1')
    expect(store.narrativeError).toBe(i18n.global.t('error.generic'))
    expect(store.targetsError).toBeNull()
  })

  it('a stale failure for a superseded player does not set the error', async () => {
    const a = deferred()
    getRadarDisplay.mockReturnValueOnce(a.promise).mockResolvedValueOnce({ skills: [] })
    const store = useDevelopmentStore()
    const pA = store.fetchRadarDisplay('A')
    await store.fetchRadarDisplay('B')
    a.reject(rejection(500))
    await pA
    expect(store.radarDisplayError).toBeNull()
    expect(store.radarDisplay).toEqual({ skills: [] })
  })

  it('fetchExposure sets exposureError alongside the legacy error', async () => {
    getSkillExposure.mockRejectedValue({ response: { data: { errorMsg: { message: 'boom' } } } })
    const store = useDevelopmentStore()
    await store.fetchExposure('p1')
    expect(store.exposureError).toBe('boom')
    expect(store.error).toBe('boom')
  })

  it('fetchRadarPreferences returns the loaded value, null when superseded, fallback on error', async () => {
    const a = deferred()
    getRadarPreferences
      .mockReturnValueOnce(a.promise)
      .mockResolvedValueOnce({ selectedSkillCodes: ['SHO'] })
    const store = useDevelopmentStore()
    const pA = store.fetchRadarPreferences('A')
    const rB = await store.fetchRadarPreferences('B')
    a.resolve({ selectedSkillCodes: ['PAC'] })
    expect(await pA).toBeNull()
    expect(rB).toEqual({ selectedSkillCodes: ['SHO'] })
    expect(store.radarPreferences).toEqual({ selectedSkillCodes: ['SHO'] })

    getRadarPreferences.mockRejectedValueOnce(rejection(500))
    const rErr = await store.fetchRadarPreferences('C')
    expect(rErr).toEqual({ selectedSkillCodes: [] })
    expect(store.radarPreferences).toEqual({ selectedSkillCodes: [] })
    expect(store.radarPreferencesError).toBe(i18n.global.t('error.generic'))
  })

  it('saveRadarPreferences success leaves radarPreferences untouched', async () => {
    putRadarPreferences.mockResolvedValue({})
    const store = useDevelopmentStore()
    store.radarPreferences = { selectedSkillCodes: ['B-pref'] }
    await store.saveRadarPreferences('A', ['PAC'])
    expect(store.radarPreferences).toEqual({ selectedSkillCodes: ['B-pref'] })
  })

  it('saveRadarPreferences failure rethrows so the caller can notify with the real message', async () => {
    putRadarPreferences.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await expect(store.saveRadarPreferences('A', ['PAC'])).rejects.toBeTruthy()
  })

  // 12-9 code review: a write's follow-up re-fetch must not replace a newer player's data.
  it('saveTargets for A does not re-fetch once B has been loaded', async () => {
    let resolvePut
    setMyTargets.mockReturnValue(new Promise((r) => (resolvePut = r)))
    getMyTargets.mockImplementation((id) =>
      Promise.resolve([{ skillCode: 'PAC', weeklyTargetSlu: id }]),
    )
    const store = useDevelopmentStore()
    await store.fetchTargets('A')
    const saving = store.saveTargets('A', [])
    await store.fetchTargets('B')
    resolvePut({})
    await saving
    expect(getMyTargets).toHaveBeenLastCalledWith('B')
    expect(store.targets).toEqual([{ skillCode: 'PAC', weeklyTargetSlu: 'B' }])
  })

  it('submitRadarAssessment for A does not re-fetch entries once B has been loaded', async () => {
    let resolvePost
    postRadarAssessment.mockReturnValue(new Promise((r) => (resolvePost = r)))
    getMyRadarEntries.mockImplementation((id) => Promise.resolve({ entries: [{ skillCode: id }] }))
    const store = useDevelopmentStore()
    await store.fetchRadarEntries('A')
    const submitting = store.submitRadarAssessment('A', {})
    await store.fetchRadarEntries('B')
    resolvePost({})
    await submitting
    expect(getMyRadarEntries).toHaveBeenLastCalledWith('B')
    expect(store.radarEntries).toEqual({ entries: [{ skillCode: 'B' }] })
  })

  it('generateReport for A does not re-fetch reports once B has been loaded', async () => {
    let resolveGen
    generateReport.mockReturnValue(new Promise((r) => (resolveGen = r)))
    listReports.mockImplementation((id) => Promise.resolve([{ id }]))
    const store = useDevelopmentStore()
    await store.fetchReports('A')
    const generating = store.generateReport('A', 'next')
    await store.fetchReports('B')
    resolveGen({})
    await generating
    expect(listReports).toHaveBeenLastCalledWith('B')
    expect(store.reports).toEqual([{ id: 'B' }])
  })

  it('saveTargets: a failed post-save re-fetch does not throw and lands in targetsError', async () => {
    setMyTargets.mockResolvedValue({})
    getMyTargets.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await expect(store.saveTargets('p1', [])).resolves.toBeUndefined()
    expect(store.targetsError).toBe(i18n.global.t('error.generic'))
  })

  it('fetchCorrelationInsights 403 leaves correlationError null', async () => {
    getCorrelationInsights.mockRejectedValue(rejection(403))
    const store = useDevelopmentStore()
    await store.fetchCorrelationInsights('p1')
    expect(store.correlationError).toBeNull()
  })
})

// skillars-deferred-153 AC4: fetchTimeline's append-on-page>0 restructure — page 0 replaces
// (today's established behavior), page > 0 appends under a SEPARATE timelineLoadingMore flag
// rather than hiding the already-rendered panel.
describe('development.store — fetchTimeline pagination (deferred-153 AC4)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('page 0 replaces the timeline wholesale', async () => {
    getTimeline.mockResolvedValue({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()

    await store.fetchTimeline('p1')

    expect(getTimeline).toHaveBeenCalledWith('p1', 0)
    expect(store.timeline.events).toEqual([{ id: 'e1' }])
    expect(store.timeline.hasMore).toBe(true)
    expect(store.timelinePage).toBe(0)
  })

  it('page > 0 appends to the existing events array without clearing it first', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e2' }],
      hasMore: false,
    })
    await store.fetchTimeline('p1', 1)

    expect(getTimeline).toHaveBeenLastCalledWith('p1', 1)
    expect(store.timeline.events).toEqual([{ id: 'e1' }, { id: 'e2' }])
    expect(store.timeline.hasMore).toBe(false)
    expect(store.timelinePage).toBe(1)
  })

  // skillars-deferred-155 AC15: a row inserted at the head between page 0 and "Load more" can
  // shift the offset and re-serve an already-rendered event — the merge must dedupe by id.
  it('page > 0 does not duplicate an event id already present from an earlier page', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }, { id: 'e2' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    // A new event was inserted at the head between requests, shifting the offset — the backend
    // re-serves e2 (already loaded) alongside the genuinely new e3.
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e2' }, { id: 'e3' }],
      hasMore: false,
    })
    await store.fetchTimeline('p1', 1)

    expect(store.timeline.events).toEqual([{ id: 'e1' }, { id: 'e2' }, { id: 'e3' }])
  })

  it('page > 0 uses timelineLoadingMore, not timelineLoading, and never hides the panel', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    let sawLoadingDuringAppend
    getTimeline.mockImplementationOnce(async () => {
      sawLoadingDuringAppend = {
        loading: store.timelineLoading,
        loadingMore: store.timelineLoadingMore,
      }
      return {
        accessExpired: false,
        accessExpiryDays: null,
        events: [{ id: 'e2' }],
        hasMore: false,
      }
    })
    await store.fetchTimeline('p1', 1)

    expect(sawLoadingDuringAppend).toEqual({ loading: false, loadingMore: true })
    expect(store.timeline).not.toBeNull()
    expect(store.timelineLoadingMore).toBe(false)
  })

  it('page 0 surfaces the translated error fallback on failure', async () => {
    getTimeline.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')
    expect(store.timelineError).toBe(i18n.global.t('development.timeline.loadError'))
  })

  // skillars-deferred-153 code review: a failed "Load more" must not take the whole rendered
  // timeline down with it. timelineError drives PlayerTimelinePanel.vue's full-panel banner,
  // which replaces the events list (and the retry button), so the page > 0 failure path has to
  // land in its own ref and leave both timelineError and the loaded events alone.
  it('page > 0 failure sets timelineLoadMoreError, not timelineError, and keeps the events', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    getTimeline.mockRejectedValueOnce(rejection(500))
    await store.fetchTimeline('p1', 1)

    expect(store.timelineLoadMoreError).toBe(i18n.global.t('development.timeline.loadMoreError'))
    expect(store.timelineError).toBeNull()
    expect(store.timeline.events).toEqual([{ id: 'e1' }])
    expect(store.timeline.hasMore).toBe(true)
    expect(store.timelineLoadingMore).toBe(false)
  })

  it('a page > 0 request does not clear a standing page-0 error', async () => {
    getTimeline.mockRejectedValueOnce(rejection(500))
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')
    expect(store.timelineError).toBe(i18n.global.t('development.timeline.loadError'))

    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e2' }],
      hasMore: false,
    })
    await store.fetchTimeline('p1', 1)

    expect(store.timelineError).toBe(i18n.global.t('development.timeline.loadError'))
  })

  it('a successful page 0 clears both the page-0 and the load-more error', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    getTimeline.mockRejectedValueOnce(rejection(500))
    await store.fetchTimeline('p1', 1)
    expect(store.timelineLoadMoreError).not.toBeNull()

    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e3' }],
      hasMore: false,
    })
    await store.fetchTimeline('p1')

    expect(store.timelineError).toBeNull()
    expect(store.timelineLoadMoreError).toBeNull()
  })

  // skillars-deferred-153 code review: coach access can expire between page 0 and page 1. The
  // backend then answers with accessExpired: true and an empty events array; appending would
  // keep the earlier events on screen under the expiry banner — displaying precisely the data
  // the backend just withheld. The expired response therefore replaces rather than merges.
  it('page > 0 with accessExpired replaces the timeline instead of appending', async () => {
    getTimeline.mockResolvedValueOnce({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1' }],
      hasMore: true,
    })
    const store = useDevelopmentStore()
    await store.fetchTimeline('p1')

    getTimeline.mockResolvedValueOnce({
      accessExpired: true,
      accessExpiryDays: 90,
      events: [],
      hasMore: false,
    })
    await store.fetchTimeline('p1', 1)

    expect(store.timeline.accessExpired).toBe(true)
    expect(store.timeline.accessExpiryDays).toBe(90)
    expect(store.timeline.events).toEqual([])
  })
})

// skillars-deferred-165 AC4: fetchSkillTrends unwraps SkillTrendResponse's `{ trends: [...] }`
// envelope — unlike the SEQ_CASES actions above, the stored value is NOT the raw API response.
describe('development.store — fetchSkillTrends (deferred-165 AC4)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('unwraps the trends array from the response envelope', async () => {
    getSkillTrends.mockResolvedValue({ trends: [{ skillCode: 'PAC', direction: 'IMPROVING' }] })
    const store = useDevelopmentStore()

    await store.fetchSkillTrends('p1')

    expect(getSkillTrends).toHaveBeenCalledWith('p1', 8)
    expect(store.skillTrends).toEqual([{ skillCode: 'PAC', direction: 'IMPROVING' }])
  })

  it('defaults to an empty array when the response has no trends field', async () => {
    getSkillTrends.mockResolvedValue({})
    const store = useDevelopmentStore()

    await store.fetchSkillTrends('p1')

    expect(store.skillTrends).toEqual([])
  })

  it('clears skillTrends at fetch start', async () => {
    getSkillTrends.mockResolvedValueOnce({ trends: [{ skillCode: 'PAC' }] })
    const store = useDevelopmentStore()
    await store.fetchSkillTrends('p1')

    const pending = deferred()
    getSkillTrends.mockReturnValueOnce(pending.promise)
    const p = store.fetchSkillTrends('p2')
    expect(store.skillTrends).toEqual([])
    pending.resolve({ trends: [] })
    await p
  })

  it("keeps only the latest player's response when responses arrive out of order", async () => {
    const a = deferred()
    const b = deferred()
    getSkillTrends.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const store = useDevelopmentStore()

    const pA = store.fetchSkillTrends('A')
    const pB = store.fetchSkillTrends('B')
    b.resolve({ trends: [{ skillCode: 'B_SKILL' }] })
    await pB
    a.resolve({ trends: [{ skillCode: 'A_SKILL' }] })
    await pA

    expect(store.skillTrends).toEqual([{ skillCode: 'B_SKILL' }])
  })

  it('a failed fetch sets skillTrendsError and leaves skillTrends empty', async () => {
    getSkillTrends.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()

    await store.fetchSkillTrends('p1')

    expect(store.skillTrends).toEqual([])
    expect(store.skillTrendsError).not.toBeNull()
  })
})
