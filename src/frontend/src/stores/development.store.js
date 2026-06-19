import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
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
  const reportsLoading = ref(false)
  const timelineLoading = ref(false)
  const reportGenerating = ref(false)
  const reportsError = ref(null)
  const timelineError = ref(null)

  const neglectedCodes = computed(() => exposure.value?.neglectedSkillCodes ?? [])

  async function fetchSkillDefinitions() {
    error.value = null
    try {
      const response = await getSkillDefinitions()
      skillDefinitions.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load skill definitions'
    }
  }

  async function fetchExposure(playerId, weeks = 8) {
    loading.value = true
    error.value = null
    try {
      const response = await getSkillExposure(playerId, weeks)
      exposure.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load skill exposure'
    } finally {
      loading.value = false
    }
  }

  async function fetchNarrative(playerId) {
    error.value = null
    try {
      const response = await getNarrativeSummary(playerId)
      narrative.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load narrative summary'
    }
  }

  async function fetchTargets(playerId) {
    error.value = null
    try {
      const response = await getMyTargets(playerId)
      targets.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load targets'
    }
  }

  async function saveTargets(playerId, newTargets) {
    error.value = null
    try {
      await setMyTargets(playerId, newTargets)
      await fetchTargets(playerId)
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to save targets'
    }
  }

  async function fetchRadarEntries(playerId) {
    radarLoading.value = true
    error.value = null
    try {
      const response = await getMyRadarEntries(playerId)
      radarEntries.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load radar entries'
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
      error.value = err?.response?.data?.message ?? 'Failed to submit assessment'
      throw err
    }
  }

  async function fetchRadarDisplay(playerId) {
    radarDisplayLoading.value = true
    error.value = null
    try {
      const response = await getRadarDisplay(playerId)
      radarDisplay.value = response.data
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to load radar display'
    } finally {
      radarDisplayLoading.value = false
    }
  }

  async function fetchRadarPreferences(playerId) {
    try {
      const response = await getRadarPreferences(playerId)
      radarPreferences.value = response.data
    } catch {
      radarPreferences.value = { selectedSkillCodes: [] }
    }
  }

  async function saveRadarPreferences(playerId, selectedSkillCodes) {
    try {
      await putRadarPreferences(playerId, selectedSkillCodes)
      radarPreferences.value = { selectedSkillCodes }
    } catch (err) {
      error.value = err?.response?.data?.message ?? 'Failed to save preferences'
    }
  }

  async function fetchCorrelationInsights(playerId) {
    correlationLoading.value = true
    try {
      const response = await getCorrelationInsights(playerId)
      correlationInsights.value = response.data
    } catch (err) {
      // Non-Academy coaches get 403 here — store null silently; UI shows teaser
      if (err?.response?.status !== 403) {
        error.value = err?.response?.data?.message ?? 'Failed to load correlation insights'
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
      reports.value = response.data
    } catch (err) {
      reportsError.value = err?.response?.data?.message ?? 'Failed to load reports'
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
      reportsError.value = err?.response?.data?.message ?? 'Failed to generate report'
      throw err
    } finally {
      reportGenerating.value = false
    }
  }

  async function fetchTimeline(playerId) {
    timelineLoading.value = true
    timelineError.value = null
    try {
      const response = await getTimeline(playerId)
      timeline.value = response.data
    } catch (err) {
      timelineError.value = err?.response?.data?.message ?? 'Failed to load timeline'
    } finally {
      timelineLoading.value = false
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
    reportsLoading,
    timelineLoading,
    reportGenerating,
    reportsError,
    timelineError,
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
  }
})
