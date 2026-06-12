import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getProfileBuilderStatus, saveProfileBuilderStep, publishProfile } from 'src/api/marketplace.api'

export const useProfileBuilderStore = defineStore('profileBuilder', () => {
  const status = ref(null)
  const currentStep = ref(1)
  const loading = ref(false)
  const error = ref(null)

  const isComplete = computed(() => status.value?.profileComplete === true)
  const lastCompletedStep = computed(() => status.value?.lastCompletedStep ?? 0)

  async function loadStatus() {
    loading.value = true
    error.value = null
    try {
      const res = await getProfileBuilderStatus()
      status.value = res.data
      currentStep.value = Math.min((res.data.lastCompletedStep ?? 0) + 1, 5)
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function submitStep(stepNumber, data) {
    loading.value = true
    error.value = null
    try {
      await saveProfileBuilderStep(stepNumber, data)
      if (status.value) {
        status.value = { ...status.value, lastCompletedStep: stepNumber }
      }
      currentStep.value = stepNumber + 1
    } catch (e) {
      error.value = e
      throw e
    } finally {
      loading.value = false
    }
  }

  async function finishAndPublish() {
    loading.value = true
    error.value = null
    try {
      const res = await publishProfile()
      status.value = res.data
    } catch (e) {
      error.value = e
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    status,
    currentStep,
    loading,
    error,
    isComplete,
    lastCompletedStep,
    loadStatus,
    submitStep,
    finishAndPublish,
  }
})
