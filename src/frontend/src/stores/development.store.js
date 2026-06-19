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
    fetchSkillDefinitions,
    fetchExposure,
    fetchNarrative,
    fetchTargets,
    saveTargets,
    fetchRadarEntries,
    submitRadarAssessment,
  }
})
