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
  const canUploadVideo = ref(null)
  const uploadingDrillId = ref(null)
  const uploadProgress = ref(0)

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

  async function fetchVideoUploadEligibility() {
    if (canUploadVideo.value !== null) return
    try {
      const res = await sessionApi.checkVideoUploadEligibility()
      canUploadVideo.value = res.data.eligible === true
    } catch {
      // do not cache failure — leave null so the next mount retries
    }
  }

  async function initiateVideoUpload(drillId, file, durationSeconds) {
    uploadingDrillId.value = drillId
    uploadProgress.value = 0
    try {
      const payload = {
        fileName: file.name,
        fileSizeBytes: file.size,
        mimeType: file.type,
        durationSeconds: durationSeconds ?? 0,
      }
      const res = await sessionApi.initiateVideoUpload(drillId, payload)
      const { videoId, uploadSessionId, signedUploadUrl, expiresAt } = res.data
      return { videoId, uploadSessionId, signedUploadUrl, expiresAt }
    } catch (e) {
      error.value = e
      throw e
    } finally {
      uploadingDrillId.value = null
    }
  }

  function updateDrillVideoState(drillId, { hasVideo, videoUrl }) {
    const drill = drills.value.find((d) => d.id === drillId)
    if (drill) {
      drill.hasVideo = hasVideo
      drill.videoUrl = videoUrl ?? null
    }
    if (selectedDrill.value?.id === drillId) {
      selectedDrill.value.hasVideo = hasVideo
      selectedDrill.value.videoUrl = videoUrl ?? null
    }
  }

  async function removeVideo(drillId) {
    try {
      await sessionApi.deleteVideo(drillId)
      updateDrillVideoState(drillId, { hasVideo: false, videoUrl: null })
    } catch (e) {
      error.value = e
      throw e
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
    canUploadVideo,
    uploadingDrillId,
    uploadProgress,
    fetchDrills,
    searchDrills,
    cloneDrill,
    addTag,
    removeTag,
    fetchTagSuggestions,
    fetchVideoUploadEligibility,
    initiateVideoUpload,
    updateDrillVideoState,
    removeVideo,
  }
})
