import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { i18n } from 'boot/i18n'
import { getErrorMessage } from 'src/utils/errorHandler'
import {
  getSkillDefinitions,
  getSkillExposure,
  getNarrativeSummary,
  getMyTargets,
  setMyTargets,
  postRadarAssessment,
  getMyRadarEntries,
  getRadarDisplay,
  getRadarPreferences,
  putRadarPreferences,
  getCorrelationInsights,
  generateReport as apiGenerateReport,
  listReports,
  getTimeline,
  getCoachContributions,
} from 'src/api/development.api'

export const useDevelopmentStore = defineStore('development', () => {
  const skillDefinitions = ref([])
  const exposure = ref(null)
  const targets = ref([])
  const narrative = ref([])
  const loading = ref(false)
  const error = ref(null)
  const radarEntries = ref(null)
  const radarLoading = ref(false)
  const radarDisplay = ref(null)
  const radarPreferences = ref(null)
  const correlationInsights = ref(null)
  const radarDisplayLoading = ref(false)
  const correlationLoading = ref(false)
  const reports = ref([])
  const timeline = ref(null)
  const timelinePage = ref(0)
  const reportsLoading = ref(false)
  const timelineLoading = ref(false)
  const timelineLoadingMore = ref(false)
  const reportGenerating = ref(false)
  const reportsError = ref(null)
  const timelineError = ref(null)
  // skillars-deferred-153 code review: load-more failures need their OWN error ref. timelineError
  // is consumed by PlayerTimelinePanel.vue as a full-panel banner that replaces the events list,
  // so reusing it for a page > 0 failure wiped the already-loaded timeline (and with it the
  // "Load more" button that was the only way to clear the error) — a dead end until remount.
  const timelineLoadMoreError = ref(null)
  const coachContributions = ref([])
  const coachContributionsLoading = ref(false)
  const coachContributionsError = ref(null)

  const neglectedCodes = computed(() => exposure.value?.neglectedSkillCodes ?? [])

  async function fetchSkillDefinitions() {
    error.value = null
    try {
      const response = await getSkillDefinitions()
      skillDefinitions.value = response
    } catch (err) {
      error.value =
        err?.response?.data?.errorMsg?.message ??
        i18n.global.t('development.portal.skillDefinitionsLoadError')
    }
  }

  let _exposureSeq = 0

  async function fetchExposure(playerId, weeks = 8) {
    if (!playerId) return
    const seq = ++_exposureSeq
    exposure.value = null
    loading.value = true
    error.value = null
    try {
      const response = await getSkillExposure(playerId, weeks)
      if (seq === _exposureSeq) {
        exposure.value = response
      }
    } catch (err) {
      if (seq === _exposureSeq) {
        error.value =
          err?.response?.data?.errorMsg?.message ??
          i18n.global.t('development.portal.exposureLoadError')
      }
    } finally {
      if (seq === _exposureSeq) {
        loading.value = false
      }
    }
  }

  async function fetchNarrative(playerId) {
    error.value = null
    try {
      const response = await getNarrativeSummary(playerId)
      narrative.value = response
    } catch (err) {
      error.value = getErrorMessage(err)
    }
  }

  async function fetchTargets(playerId) {
    error.value = null
    try {
      const response = await getMyTargets(playerId)
      targets.value = response
    } catch (err) {
      error.value = getErrorMessage(err)
    }
  }

  async function saveTargets(playerId, newTargets) {
    error.value = null
    try {
      await setMyTargets(playerId, newTargets)
      await fetchTargets(playerId)
    } catch (err) {
      error.value = getErrorMessage(err)
    }
  }

  async function fetchRadarEntries(playerId) {
    radarLoading.value = true
    error.value = null
    try {
      const response = await getMyRadarEntries(playerId)
      radarEntries.value = response
    } catch (err) {
      error.value = getErrorMessage(err)
    } finally {
      radarLoading.value = false
    }
  }

  async function submitRadarAssessment(playerId, assessment) {
    error.value = null
    try {
      await postRadarAssessment(playerId, assessment)
      await fetchRadarEntries(playerId)
    } catch (err) {
      error.value = getErrorMessage(err)
      throw err
    }
  }

  async function fetchRadarDisplay(playerId) {
    radarDisplayLoading.value = true
    error.value = null
    try {
      const response = await getRadarDisplay(playerId)
      radarDisplay.value = response
    } catch (err) {
      error.value = getErrorMessage(err)
    } finally {
      radarDisplayLoading.value = false
    }
  }

  async function fetchRadarPreferences(playerId) {
    try {
      const response = await getRadarPreferences(playerId)
      radarPreferences.value = response
    } catch {
      radarPreferences.value = { selectedSkillCodes: [] }
    }
  }

  async function saveRadarPreferences(playerId, selectedSkillCodes) {
    try {
      await putRadarPreferences(playerId, selectedSkillCodes)
      radarPreferences.value = { selectedSkillCodes }
    } catch (err) {
      error.value = getErrorMessage(err)
    }
  }

  async function fetchCorrelationInsights(playerId) {
    correlationLoading.value = true
    try {
      const response = await getCorrelationInsights(playerId)
      correlationInsights.value = response
    } catch (err) {
      // Non-Academy coaches get 403 here — store null silently; UI shows teaser
      if (err?.response?.status !== 403) {
        error.value = getErrorMessage(err)
      }
      correlationInsights.value = null
    } finally {
      correlationLoading.value = false
    }
  }

  async function fetchReports(playerId) {
    reportsLoading.value = true
    reportsError.value = null
    try {
      const response = await listReports(playerId)
      reports.value = response
    } catch (err) {
      reportsError.value = getErrorMessage(err)
    } finally {
      reportsLoading.value = false
    }
  }

  async function generateReport(playerId, nextSteps) {
    reportGenerating.value = true
    reportsError.value = null
    try {
      await apiGenerateReport(playerId, nextSteps)
      await fetchReports(playerId)
    } catch (err) {
      reportsError.value = getErrorMessage(err)
      throw err
    } finally {
      reportGenerating.value = false
    }
  }

  let _timelineSeq = 0

  // skillars-deferred-153 AC4: page === 0 replaces (today's established behavior — a fresh
  // player selection must not show stale events); page > 0 appends under a SEPARATE loading flag
  // so the already-rendered panel is not hidden while "Load more" is in flight.
  async function fetchTimeline(playerId, page = 0) {
    if (!playerId) return
    const seq = ++_timelineSeq
    if (page === 0) {
      timeline.value = null
      timelineLoading.value = true
      // A fresh page-0 load supersedes both error states — it rebuilds the panel from scratch.
      timelineError.value = null
      timelineLoadMoreError.value = null
    } else {
      timelineLoadingMore.value = true
      // skillars-deferred-153 code review: deliberately does NOT clear timelineError. A page > 0
      // request is only reachable from the events branch, which never renders while a page-0
      // error stands; clearing it here would silently drop a failure the user has not seen.
      timelineLoadMoreError.value = null
    }
    try {
      const response = await getTimeline(playerId, page)
      if (seq === _timelineSeq) {
        // skillars-deferred-153 code review: an appended response carrying accessExpired REPLACES
        // instead of merging. The backend answers an expired coach-access window with
        // accessExpired: true and an empty events array — merging would keep the previously
        // loaded events on screen underneath the expiry banner, i.e. keep displaying exactly the
        // data the backend just decided to withhold.
        timeline.value =
          page === 0 || !timeline.value || response.accessExpired
            ? response
            : { ...response, events: [...timeline.value.events, ...response.events] }
        timelinePage.value = page
      }
    } catch (err) {
      if (seq === _timelineSeq) {
        // page 0 failed with nothing rendered → full-panel banner. page > 0 failed with a
        // populated timeline on screen → inline message beside "Load more", list untouched.
        // The fallback copy differs too: "Failed to load timeline" misdescribes a load-more
        // failure, where the timeline itself is still right there above the message.
        const serverMessage = err?.response?.data?.errorMsg?.message
        if (page === 0) {
          timelineError.value = serverMessage ?? i18n.global.t('development.timeline.loadError')
        } else {
          timelineLoadMoreError.value =
            serverMessage ?? i18n.global.t('development.timeline.loadMoreError')
        }
      }
    } finally {
      if (seq === _timelineSeq) {
        timelineLoading.value = false
        timelineLoadingMore.value = false
      }
    }
  }

  let _coachContributionsSeq = 0

  async function fetchCoachContributions(playerId) {
    if (!playerId) return
    const seq = ++_coachContributionsSeq
    coachContributions.value = []
    coachContributionsLoading.value = true
    coachContributionsError.value = null
    try {
      const response = await getCoachContributions(playerId)
      if (seq === _coachContributionsSeq) {
        coachContributions.value = response
      }
    } catch (err) {
      if (seq === _coachContributionsSeq) {
        coachContributionsError.value =
          err?.response?.data?.errorMsg?.message ??
          i18n.global.t('development.portal.contributionsLoadError')
      }
    } finally {
      if (seq === _coachContributionsSeq) {
        coachContributionsLoading.value = false
      }
    }
  }

  return {
    skillDefinitions,
    exposure,
    targets,
    neglectedCodes,
    narrative,
    loading,
    error,
    radarEntries,
    radarLoading,
    radarDisplay,
    radarPreferences,
    correlationInsights,
    radarDisplayLoading,
    correlationLoading,
    reports,
    timeline,
    timelinePage,
    reportsLoading,
    timelineLoading,
    timelineLoadingMore,
    reportGenerating,
    reportsError,
    timelineError,
    timelineLoadMoreError,
    fetchSkillDefinitions,
    fetchExposure,
    fetchNarrative,
    fetchTargets,
    saveTargets,
    fetchRadarEntries,
    submitRadarAssessment,
    fetchRadarDisplay,
    fetchRadarPreferences,
    saveRadarPreferences,
    fetchCorrelationInsights,
    fetchReports,
    generateReport,
    fetchTimeline,
    coachContributions,
    coachContributionsLoading,
    coachContributionsError,
    fetchCoachContributions,
  }
})
