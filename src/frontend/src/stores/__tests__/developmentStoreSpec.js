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
  it('saveTargets surfaces the translated fallback', async () => {
    setMyTargets.mockRejectedValue(rejection(400))
    const store = useDevelopmentStore()
    await store.saveTargets('p1', [])
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
    await store.saveRadarPreferences('p1', ['SHOOTING'])
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

  it('generateReport surfaces the translated fallback and still rethrows', async () => {
    generateReport.mockRejectedValue(rejection(500))
    const store = useDevelopmentStore()
    await expect(store.generateReport('p1', 'next steps')).rejects.toBeTruthy()
    expect(store.reportsError).toBe(i18n.global.t('error.generic'))
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
