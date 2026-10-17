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
