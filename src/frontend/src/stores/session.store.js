import { defineStore } from 'pinia'
import { ref } from 'vue'
import { sessionApi } from 'src/api/session.api'

export const useSessionStore = defineStore('session', () => {
  const drills = ref([])
  const loading = ref(false)
  const error = ref(null)
  const searchQuery = ref('')
  const activeFilters = ref({
    skill: null,
    difficultyTier: null,
    equipment: null,
    weakFootBias: null,
  })
  const selectedDrill = ref(null)
  const tagSuggestions = ref([])

  async function fetchDrills(library) {
    loading.value = true
    error.value = null
    try {
      const response = await sessionApi.getDrills(library)
      drills.value = response.data
    } catch (err) {
      error.value = err
    } finally {
      loading.value = false
    }
  }

  async function searchDrills(library) {
    loading.value = true
    error.value = null
    try {
      const params = {}
      if (searchQuery.value) params.q = searchQuery.value
      if (activeFilters.value.skill) params.skill = activeFilters.value.skill
      if (activeFilters.value.difficultyTier)
        params.difficultyTier = activeFilters.value.difficultyTier
      if (activeFilters.value.equipment) params.equipment = activeFilters.value.equipment
      if (
        activeFilters.value.weakFootBias !== null &&
        activeFilters.value.weakFootBias !== undefined
      ) {
        params.weakFootBias = activeFilters.value.weakFootBias
      }
      const response = await sessionApi.getDrills(library, params)
      drills.value = response.data
    } catch (err) {
      error.value = err
    } finally {
      loading.value = false
    }
  }

  async function cloneDrill(drillId) {
    error.value = null
    try {
      const response = await sessionApi.cloneDrill(drillId)
      const drill = drills.value.find((d) => d.id === drillId)
      if (drill) {
        drill.isClonedByMe = true
        drill.cloneId = response.data.id
      }
      return response
    } catch (err) {
      error.value = err
      throw err
    }
  }

  async function addTag(drillId, tag) {
    error.value = null
    try {
      await sessionApi.addTag(drillId, tag)
      const drill = drills.value.find((d) => d.id === drillId)
      if (drill && drill.tags && !drill.tags.includes(tag)) {
        drill.tags.push(tag)
      }
    } catch (err) {
      error.value = err
    }
  }

  async function removeTag(drillId, tag) {
    error.value = null
    try {
      await sessionApi.removeTag(drillId, tag)
      const drill = drills.value.find((d) => d.id === drillId)
      if (drill && drill.tags) {
        const idx = drill.tags.indexOf(tag)
        if (idx !== -1) drill.tags.splice(idx, 1)
      }
    } catch (err) {
      error.value = err
    }
  }

  async function fetchTagSuggestions() {
    try {
      const response = await sessionApi.getTagSuggestions()
      tagSuggestions.value = response.data
    } catch (err) {
      error.value = err
    }
  }

  return {
    drills,
    loading,
    error,
    searchQuery,
    activeFilters,
    selectedDrill,
    tagSuggestions,
    fetchDrills,
    searchDrills,
    cloneDrill,
    addTag,
    removeTag,
    fetchTagSuggestions,
  }
})
