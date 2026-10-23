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
  getNeglectedSkills,
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

  const skillDefinitionsError = ref(null)
  const exposureError = ref(null)
  // skillars-12-10 AC7: the server answers "no relationship" and "access window closed" with the same 403, so
  // the coach page only needs to know a PLAYER-scoped core fetch was forbidden (skill definitions are global).
  const exposureForbidden = ref(false)
  const coachContributionsForbidden = ref(false)
  const coreForbidden = computed(() => exposureForbidden.value || coachContributionsForbidden.value)
  const narrativeError = ref(null)
  const narrativeLoading = ref(false)
  const targetsError = ref(null)
  const targetsLoading = ref(false)
  const radarEntriesError = ref(null)
  const radarDisplayError = ref(null)
  const radarPreferencesError = ref(null)
  const radarPreferencesLoading = ref(false)
  const correlationError = ref(null)
  const neglectedSkills = ref([])
  const neglectedSkillsLoading = ref(false)
  const neglectedSkillsError = ref(null)

  const neglectedCodes = computed(() => exposure.value?.neglectedSkillCodes ?? [])

  // skillars-12-9 Task 1: the legacy shared `error` ref is kept (the parent portal and the
  // existing specs read it) but multi-section pages (CoachPlayerDetailPage) read the per-section
  // refs below instead, so one failing secondary section cannot blank the page or erase another
  // section's error. Player-scoped fetches clear-before-fetch and drop out-of-order responses
  // with a per-action sequence guard (pattern: fetchExposure).
  async function fetchSkillDefinitions() {
    error.value = null
    skillDefinitionsError.value = null
    try {
      const response = await getSkillDefinitions()
      skillDefinitions.value = response
    } catch (err) {
      error.value =
        err?.response?.data?.errorMsg?.message ??
        i18n.global.t('development.portal.skillDefinitionsLoadError')
      skillDefinitionsError.value = error.value
    }
  }

  let _exposureSeq = 0

  async function fetchExposure(playerId, weeks = 8) {
    if (!playerId) return
    const seq = ++_exposureSeq
    exposure.value = null
    loading.value = true
    error.value = null
    exposureError.value = null
    exposureForbidden.value = false
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
        exposureError.value = error.value
        exposureForbidden.value = err?.response?.status === 403
      }
    } finally {
      if (seq === _exposureSeq) {
        loading.value = false
      }
    }
  }

  let _narrativeSeq = 0

  async function fetchNarrative(playerId) {
    const seq = ++_narrativeSeq
    narrative.value = []
    narrativeLoading.value = true
    error.value = null
    narrativeError.value = null
    try {
      const response = await getNarrativeSummary(playerId)
      if (seq === _narrativeSeq) narrative.value = response
    } catch (err) {
      if (seq === _narrativeSeq) {
        error.value = getErrorMessage(err)
        narrativeError.value = error.value
      }
    } finally {
      if (seq === _narrativeSeq) narrativeLoading.value = false
    }
  }

  let _targetsSeq = 0
  // The player the LATEST fetchTargets was for. A write's follow-up re-fetch must not start for a
  // player the page has already left: it would take the newest seq and replace the current
  // player's targets with the old player's (12-9 code review).
  let _targetsPlayerId = null

  async function fetchTargets(playerId) {
    const seq = ++_targetsSeq
    _targetsPlayerId = playerId
    targets.value = []
    targetsLoading.value = true
    error.value = null
    targetsError.value = null
    try {
      const response = await getMyTargets(playerId)
      if (seq === _targetsSeq) targets.value = response
    } catch (err) {
      if (seq === _targetsSeq) {
        error.value = getErrorMessage(err)
        targetsError.value = error.value
      }
    } finally {
      if (seq === _targetsSeq) targetsLoading.value = false
    }
  }

  // Rethrows a failed SAVE so the caller can tell it from a failed post-save re-fetch: the
  // re-fetch is fetchTargets, which reports through targetsError and never throws.
  async function saveTargets(playerId, newTargets) {
    error.value = null
    try {
      await setMyTargets(playerId, newTargets)
    } catch (err) {
      error.value = getErrorMessage(err)
      throw err
    }
    if (_targetsPlayerId === null || _targetsPlayerId === playerId) await fetchTargets(playerId)
  }

  let _radarEntriesSeq = 0
  // See _targetsPlayerId.
  let _radarEntriesPlayerId = null

  async function fetchRadarEntries(playerId) {
    const seq = ++_radarEntriesSeq
    _radarEntriesPlayerId = playerId
    radarEntries.value = null
    radarLoading.value = true
    error.value = null
    radarEntriesError.value = null
    try {
      const response = await getMyRadarEntries(playerId)
      if (seq === _radarEntriesSeq) radarEntries.value = response
    } catch (err) {
      if (seq === _radarEntriesSeq) {
        error.value = getErrorMessage(err)
        radarEntriesError.value = error.value
      }
    } finally {
      if (seq === _radarEntriesSeq) radarLoading.value = false
    }
  }

  async function submitRadarAssessment(playerId, assessment) {
    error.value = null
    try {
      await postRadarAssessment(playerId, assessment)
    } catch (err) {
      error.value = getErrorMessage(err)
      throw err
    }
    if (_radarEntriesPlayerId === null || _radarEntriesPlayerId === playerId)
      await fetchRadarEntries(playerId)
  }

  let _radarDisplaySeq = 0

  async function fetchRadarDisplay(playerId) {
    const seq = ++_radarDisplaySeq
    radarDisplay.value = null
    radarDisplayLoading.value = true
    error.value = null
    radarDisplayError.value = null
    try {
      const response = await getRadarDisplay(playerId)
      if (seq === _radarDisplaySeq) radarDisplay.value = response
    } catch (err) {
      if (seq === _radarDisplaySeq) {
        error.value = getErrorMessage(err)
        radarDisplayError.value = error.value
      }
    } finally {
      if (seq === _radarDisplaySeq) radarDisplayLoading.value = false
    }
  }

  let _radarPreferencesSeq = 0

  // Returns the loaded value, or null when a newer fetch superseded this one, so the caller can
  // tell a stale response from a real one (skillars-12-9 AC12a). On error the store (and the
  // return value) fall back to "all skills", and radarPreferencesError says so.
  async function fetchRadarPreferences(playerId) {
    const seq = ++_radarPreferencesSeq
    radarPreferences.value = null
    radarPreferencesLoading.value = true
    radarPreferencesError.value = null
    try {
      const response = await getRadarPreferences(playerId)
      if (seq !== _radarPreferencesSeq) return null
      radarPreferences.value = response
      return response
    } catch (err) {
      if (seq !== _radarPreferencesSeq) return null
      radarPreferences.value = { selectedSkillCodes: [] }
      radarPreferencesError.value = getErrorMessage(err)
      return radarPreferences.value
    } finally {
      if (seq === _radarPreferencesSeq) radarPreferencesLoading.value = false
    }
  }

  // Deliberately does NOT write radarPreferences: a flushed save for player A can resolve after
  // player B's fetch has landed, and would overwrite B's preferences. The caller's local selection
  // is the source of truth; radarPreferences is only the last LOADED value (skillars-12-9 AC12b).
  // Rethrows so the caller can notify with getErrorMessage(err) (12-9 AC5).
  async function saveRadarPreferences(playerId, selectedSkillCodes) {
    try {
      await putRadarPreferences(playerId, selectedSkillCodes)
    } catch (err) {
      error.value = getErrorMessage(err)
      throw err
    }
  }

  let _correlationSeq = 0

  async function fetchCorrelationInsights(playerId) {
    const seq = ++_correlationSeq
    correlationInsights.value = null
    correlationLoading.value = true
    correlationError.value = null
    try {
      const response = await getCorrelationInsights(playerId)
      if (seq === _correlationSeq) correlationInsights.value = response
    } catch (err) {
      // Non-Academy coaches get 403 here — store null silently; UI shows teaser
      if (seq === _correlationSeq && err?.response?.status !== 403) {
        error.value = getErrorMessage(err)
        correlationError.value = error.value
      }
    } finally {
      if (seq === _correlationSeq) correlationLoading.value = false
    }
  }

  let _reportsSeq = 0
  // See _targetsPlayerId.
  let _reportsPlayerId = null

  async function fetchReports(playerId) {
    const seq = ++_reportsSeq
    _reportsPlayerId = playerId
    reports.value = []
    reportsLoading.value = true
    reportsError.value = null
    try {
      const response = await listReports(playerId)
      if (seq === _reportsSeq) reports.value = response
    } catch (err) {
      if (seq === _reportsSeq) reportsError.value = getErrorMessage(err)
    } finally {
      if (seq === _reportsSeq) reportsLoading.value = false
    }
  }

  // reportsError belongs to the report LIST fetch only: a failed generate must not replace the
  // already-loaded list with an error banner. The caller (GenerateReportDialog) notifies with the
  // thrown error instead.
  async function generateReport(playerId, nextSteps) {
    reportGenerating.value = true
    try {
      await apiGenerateReport(playerId, nextSteps)
      if (_reportsPlayerId === null || _reportsPlayerId === playerId) await fetchReports(playerId)
    } finally {
      reportGenerating.value = false
    }
  }

  let _neglectedSkillsSeq = 0

  // skillars-12-9 AC7 (D12.9-A): per-skill "neglected since" flags, oldest-streak ordering is the
  // page's job. The exposure endpoint's neglectedSkillCodes (neglectedCodes) stays the bar-chart
  // highlight source.
  async function fetchNeglectedSkills(playerId) {
    if (!playerId) return
    const seq = ++_neglectedSkillsSeq
    neglectedSkills.value = []
    neglectedSkillsLoading.value = true
    neglectedSkillsError.value = null
    try {
      const response = await getNeglectedSkills(playerId)
      if (seq === _neglectedSkillsSeq) neglectedSkills.value = response
    } catch (err) {
      if (seq === _neglectedSkillsSeq) neglectedSkillsError.value = getErrorMessage(err)
    } finally {
      if (seq === _neglectedSkillsSeq) neglectedSkillsLoading.value = false
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
        if (page === 0 || !timeline.value || response.accessExpired) {
          timeline.value = response
        } else {
          // skillars-deferred-155 AC15: a wrap-up submitted in another tab between this page-0
          // load and this "Load more" click can shift the offset and re-serve an already-rendered
          // event — concatenating without this id-keyed merge would duplicate it under a
          // duplicate :key.
          //
          // skillars-deferred-155 code review: merged via a Map so the SERVER's copy of a re-served
          // event wins, rather than filtering the server's copy out and keeping the one already on
          // screen. The payload can legitimately differ between the two requests — a coach can edit a
          // wrap-up, and TimelineQueryService.redactCoachPrivateFieldsIfNotAuthoringCoach's decision flips the
          // moment coach access expires — so keeping the stale copy could go on rendering recap text
          // the backend has just decided to withhold, which is the same hazard the accessExpired
          // branch above exists to close. Insertion order is preserved (already-loaded first, new
          // ones appended), because Map keeps first-insertion position when a key is overwritten.
          const merged = new Map(timeline.value.events.map((e) => [e.id, e]))
          response.events.forEach((e) => merged.set(e.id, e))
          timeline.value = { ...response, events: [...merged.values()] }
        }
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
    coachContributionsForbidden.value = false
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
        coachContributionsForbidden.value = err?.response?.status === 403
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
    skillDefinitionsError,
    exposureError,
    coreForbidden,
    narrativeError,
    narrativeLoading,
    targetsError,
    targetsLoading,
    radarEntriesError,
    radarDisplayError,
    radarPreferencesError,
    radarPreferencesLoading,
    correlationError,
    neglectedSkills,
    neglectedSkillsLoading,
    neglectedSkillsError,
    fetchNeglectedSkills,
  }
})
