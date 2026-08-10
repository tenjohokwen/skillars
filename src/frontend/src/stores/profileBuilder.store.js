import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getProfileBuilderStatus,
  saveProfileBuilderStep,
  publishProfile,
  getSupportedTimezones,
} from 'src/api/marketplace.api'

export const useProfileBuilderStore = defineStore('profileBuilder', () => {
  const status = ref(null)
  const currentStep = ref(1)
  const loading = ref(false)
  const error = ref(null)

  // Timezones the server will accept. Fetched once per session and shared by Step 1 and Step 4 —
  // the list is ~486 entries and does not change while the builder is open.
  const supportedTimezones = ref([])
  // The zone chosen in Step 1, so Step 4 can default to it instead of re-detecting the browser.
  // Not persisted: a coach resuming the builder in a fresh session falls back to the
  // validated-browser-zone rule in TimezoneSelect.
  const selectedTimezone = ref(null)
  // Distinguishes "the zone list failed to load" from "the list is legitimately empty".
  // Without it both render as an empty dropdown and the coach cannot tell which happened.
  const timezonesFailed = ref(false)

  const isComplete = computed(() => status.value?.profileComplete === true)
  const lastCompletedStep = computed(() => status.value?.lastCompletedStep ?? 0)

  async function loadStatus() {
    loading.value = true
    error.value = null
    try {
      const res = await getProfileBuilderStatus()
      status.value = res
      currentStep.value = Math.min((res.lastCompletedStep ?? 0) + 1, 5)
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function loadSupportedTimezones() {
    if (supportedTimezones.value.length > 0) return supportedTimezones.value
    timezonesFailed.value = false
    try {
      supportedTimezones.value = await getSupportedTimezones()
    } catch {
      // Deliberately not surfaced through `error`: that ref drives the builder's own failure state,
      // and a zone list that failed to load must not read as "saving your profile failed".
      //
      // But an empty list on its own is NOT a usable signal — it is indistinguishable from "your
      // search matched nothing", and it leaves the coach at a disabled Next button with no
      // explanation, which is the same dead end this whole feature exists to remove. Hence a
      // separate flag: the picker can then say the list failed to load and offer a retry, instead
      // of silently looking like an empty dropdown.
      supportedTimezones.value = []
      timezonesFailed.value = true
    }
    return supportedTimezones.value
  }

  function setSelectedTimezone(zone) {
    selectedTimezone.value = zone
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
      status.value = res
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
    supportedTimezones,
    selectedTimezone,
    timezonesFailed,
    loadStatus,
    loadSupportedTimezones,
    setSelectedTimezone,
    submitStep,
    finishAndPublish,
  }
})
