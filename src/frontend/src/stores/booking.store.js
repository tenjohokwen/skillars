import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
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
} from 'src/api/booking.api'

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
  }
})
