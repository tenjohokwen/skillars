import { defineStore } from 'pinia'
import { ref, computed, onUnmounted } from 'vue'
import {
  getCoachAvailability,
  addAvailabilityWindow,
  updateAvailabilityWindow,
  deleteAvailabilityWindow,
  addAvailabilityBlock,
  deleteAvailabilityBlock,
  getPlayerPacks,
  purchaseSessionPack,
  createBookingRequest,
  acceptBooking,
  declineBooking,
  getParentBookings,
  getCoachBookingRequests,
  getBookingById,
  getCoachSchedule,
  getParentSchedule,
  startSession,
  endSession,
  pauseSession,
  resumeSession,
  submitWrapUp,
  initiateQuickComplete,
  confirmCompletion,
} from 'src/api/booking.api'

export function useBookingSse(bookingId) {
  const status = ref(null)
  const connectionState = ref('disconnected')
  let es = null
  let retryCount = 0
  let pollingInterval = null
  const delays = [1000, 2000, 4000, 8000, 16000, 30000]

  function connect() {
    es = new EventSource(`/api/bookings/${bookingId}/events`, { withCredentials: true })
    connectionState.value = 'reconnecting'
    es.onopen = () => {
      connectionState.value = 'connected'
    }
    es.addEventListener('status', (e) => {
      status.value = e.data
      retryCount = 0
      if (pollingInterval) {
        clearInterval(pollingInterval)
        pollingInterval = null
        connectionState.value = 'connected'
      }
    })
    es.onerror = () => {
      es.close()
      retryCount++
      if (retryCount >= 3 && !pollingInterval) {
        connectionState.value = 'polling'
        pollingInterval = setInterval(async () => {
          const r = await getBookingById(bookingId)
          status.value = r.data.status
        }, 2000)
      } else if (!pollingInterval) {
        connectionState.value = 'reconnecting'
        const delay = delays[Math.min(retryCount - 1, delays.length - 1)]
        setTimeout(connect, delay)
      }
    }
    es.addEventListener('heartbeat', () => {
      es.close()
      retryCount = 0
      clearInterval(pollingInterval)
      pollingInterval = null
      connect()
    })
  }

  function cleanup() {
    es?.close()
    clearInterval(pollingInterval)
    pollingInterval = null
    connectionState.value = 'disconnected'
  }

  connect()
  onUnmounted(cleanup)
  return { status, connectionState, cleanup }
}

export const useBookingStore = defineStore('booking', () => {
  const windows = ref([])
  const blocks = ref([])
  const computedSlots = ref([])
  const weekStart = ref(null)
  const loading = ref(false)
  const error = ref(null)

  const hasWindows = computed(() => windows.value.length > 0)

  const sessionPacks = ref([])
  const packsLoading = ref(false)
  const packsError = ref(null)

  const parentBookings = ref([])
  const bookingsLoading = ref(false)
  const bookingsError = ref(null)

  const coachBookingRequests = ref([])
  const coachRequestsLoading = ref(false)
  const coachRequestsError = ref(null)

  const coachSchedule = ref(null)
  const coachScheduleLoading = ref(false)
  const coachScheduleError = ref(null)

  const parentSchedule = ref(null)
  const parentScheduleLoading = ref(false)
  const parentScheduleError = ref(null)

  const activeSessionBookingId = ref(null)
  const completionLoading = ref(false)
  const completionError = ref(null)

  const creditsForCoach = computed(
    () => (coachId) =>
      sessionPacks.value
        .filter((p) => p.status === 'ACTIVE' && p.coachId === coachId)
        .reduce((sum, p) => sum + p.creditsRemaining, 0),
  )

  function currentMonday() {
    const today = new Date()
    const day = today.getDay()
    const diff = day === 0 ? -6 : 1 - day
    const monday = new Date(today)
    monday.setDate(today.getDate() + diff)
    return monday.toISOString().slice(0, 10)
  }

  async function loadAvailability(coachId, date) {
    loading.value = true
    error.value = null
    try {
      const ws = date ?? currentMonday()
      weekStart.value = ws
      const res = await getCoachAvailability(coachId, ws)
      windows.value = res.data.windows ?? []
      blocks.value = res.data.blocks ?? []
      computedSlots.value = res.data.computedSlots ?? []
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function createWindow(coachId, data) {
    await addAvailabilityWindow(data)
    await loadAvailability(coachId, weekStart.value ?? currentMonday())
  }

  async function editWindow(coachId, id, data) {
    await updateAvailabilityWindow(id, data)
    await loadAvailability(coachId, weekStart.value ?? currentMonday())
  }

  async function removeWindow(coachId, id) {
    await deleteAvailabilityWindow(id)
    await loadAvailability(coachId, weekStart.value ?? currentMonday())
  }

  async function createBlock(coachId, data) {
    await addAvailabilityBlock(data)
    await loadAvailability(coachId, weekStart.value ?? currentMonday())
  }

  async function removeBlock(coachId, id) {
    await deleteAvailabilityBlock(id)
    await loadAvailability(coachId, weekStart.value ?? currentMonday())
  }

  async function loadPlayerPacks(playerId) {
    packsLoading.value = true
    packsError.value = null
    try {
      const res = await getPlayerPacks(playerId)
      sessionPacks.value = res.data ?? []
    } catch (e) {
      packsError.value = e
    } finally {
      packsLoading.value = false
    }
  }

  async function purchasePack(playerId, request) {
    await purchaseSessionPack(playerId, request)
    await loadPlayerPacks(playerId)
  }

  async function loadParentBookings() {
    bookingsLoading.value = true
    bookingsError.value = null
    try {
      const res = await getParentBookings()
      parentBookings.value = res.data ?? []
    } catch (e) {
      bookingsError.value = e
    } finally {
      bookingsLoading.value = false
    }
  }

  async function loadCoachBookingRequests() {
    coachRequestsLoading.value = true
    coachRequestsError.value = null
    try {
      const res = await getCoachBookingRequests()
      coachBookingRequests.value = res.data ?? []
    } catch (e) {
      coachRequestsError.value = e
    } finally {
      coachRequestsLoading.value = false
    }
  }

  async function loadCoachSchedule(weekStart) {
    coachSchedule.value = null
    coachScheduleLoading.value = true
    coachScheduleError.value = null
    try {
      const res = await getCoachSchedule(weekStart)
      coachSchedule.value = res.data
    } catch (e) {
      coachScheduleError.value = e
    } finally {
      coachScheduleLoading.value = false
    }
  }

  async function loadParentSchedule(playerId) {
    parentScheduleLoading.value = true
    parentScheduleError.value = null
    try {
      const res = await getParentSchedule(playerId)
      parentSchedule.value = res.data
    } catch (e) {
      parentScheduleError.value = e
    } finally {
      parentScheduleLoading.value = false
    }
  }

  async function submitBookingRequest(request) {
    await createBookingRequest(request)
    await loadParentBookings()
  }

  async function approveBooking(id) {
    await acceptBooking(id)
    await loadCoachBookingRequests()
  }

  async function rejectBooking(id) {
    await declineBooking(id)
    await loadCoachBookingRequests()
  }

  async function handleStartSession(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await startSession(bookingId)
      activeSessionBookingId.value = bookingId
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleEndSession(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await endSession(bookingId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handlePauseSession(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await pauseSession(bookingId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleResumeSession(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await resumeSession(bookingId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleSubmitWrapUp(bookingId, wrapUpData) {
    completionLoading.value = true
    completionError.value = null
    try {
      await submitWrapUp(bookingId, wrapUpData)
      activeSessionBookingId.value = null
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleInitiateQuickComplete(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await initiateQuickComplete(bookingId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleConfirmCompletion(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await confirmCompletion(bookingId)
      await loadParentBookings()
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  return {
    windows,
    blocks,
    computedSlots,
    weekStart,
    loading,
    error,
    hasWindows,
    sessionPacks,
    packsLoading,
    packsError,
    creditsForCoach,
    parentBookings,
    bookingsLoading,
    bookingsError,
    coachBookingRequests,
    coachRequestsLoading,
    coachRequestsError,
    loadAvailability,
    createWindow,
    editWindow,
    removeWindow,
    createBlock,
    removeBlock,
    loadPlayerPacks,
    purchasePack,
    loadParentBookings,
    loadCoachBookingRequests,
    submitBookingRequest,
    approveBooking,
    rejectBooking,
    coachSchedule,
    coachScheduleLoading,
    coachScheduleError,
    parentSchedule,
    parentScheduleLoading,
    parentScheduleError,
    loadCoachSchedule,
    loadParentSchedule,
    activeSessionBookingId,
    completionLoading,
    completionError,
    handleStartSession,
    handleEndSession,
    handlePauseSession,
    handleResumeSession,
    handleSubmitWrapUp,
    handleInitiateQuickComplete,
    handleConfirmCompletion,
  }
})
