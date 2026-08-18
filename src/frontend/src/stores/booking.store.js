import { defineStore } from 'pinia'
import { ref, computed, onUnmounted } from 'vue'
import {
  getCoachAvailability,
  addAvailabilityWindow,
  updateAvailabilityWindow,
  deleteAvailabilityWindow,
  addAvailabilityBlock,
  deleteAvailabilityBlock,
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
  requestReschedule,
  acceptReschedule,
  declineReschedule,
  duplicateNextWeek,
  createBatch,
  acceptAllBatch,
} from 'src/api/booking.api'
import {
  purchaseSessionPack,
  getMySessionPacks,
  pauseSessionPack,
} from 'src/api/payment.api'

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
          status.value = r.status
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
  const coachTimezone = ref(null)
  const weekStart = ref(null)
  const loading = ref(false)
  const error = ref(null)

  const hasWindows = computed(() => windows.value.length > 0)

  const sessionPacks = ref([])
  const sessionPacksPlayerId = ref(null)
  const packsLoading = ref(false)
  const packsError = ref(null)

  const parentBookings = ref([])
  const bookingsLoading = ref(false)
  const bookingsError = ref(null)

  const coachBookingRequests = ref([])
  const coachBatchGroups = ref([])
  const coachRequestsLoading = ref(false)
  const coachRequestsError = ref(null)

  const packPauseLoading = ref(false)
  const packPauseError = ref(null)
  const packPauseConflicts = ref([])
  const packPauseResult = ref(null)

  const batchBasket = ref([])
  const batchSubmitting = ref(false)
  const batchError = ref(null)

  const batchBasketSize = computed(() => batchBasket.value.length)
  const isSlotInBasket = computed(() => (startDatetime) =>
    batchBasket.value.some((s) => s.startDatetime === startDatetime),
  )

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
    // Clear on entry, not only on success: a failed load must not leave the previous coach's
    // slots and timezone on screen under the new coach's title.
    windows.value = []
    blocks.value = []
    computedSlots.value = []
    coachTimezone.value = null
    try {
      const ws = date ?? currentMonday()
      weekStart.value = ws
      const res = await getCoachAvailability(coachId, ws)
      windows.value = res.windows ?? []
      blocks.value = res.blocks ?? []
      computedSlots.value = res.computedSlots ?? []
      coachTimezone.value = res.canonicalTimezone ?? null
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

  // The new payment-path list endpoint (GET /api/payment/session-packs) is parent-scoped, not
  // player-scoped like the legacy endpoint was — it returns every pack for every child of the
  // authenticated parent. loadPlayerPacks(playerId) keeps its player-scoped name/signature for
  // callers, filtering the parent-wide result down to one player here so existing consumers don't
  // need to change. Field names also differ from legacy (purchaseId/remainingSessions vs.
  // id/creditsRemaining) — normalized here so templates built against the legacy shape keep working.
  function normalizePack(p) {
    return {
      ...p,
      id: p.purchaseId,
      creditsRemaining: p.remainingSessions,
    }
  }

  async function loadPlayerPacks(playerId) {
    packsLoading.value = true
    packsError.value = null
    try {
      const res = await getMySessionPacks()
      const packs = (res ?? []).map(normalizePack)
      sessionPacks.value = playerId
        ? packs.filter((p) => String(p.playerId) === String(playerId))
        : packs
      // AC 8: tag which player this snapshot belongs to, so a future consumer can detect a
      // mismatch (e.g. a stale sessionPacks left over from a different child) instead of
      // silently trusting it. `null` means "all players" (playerId not passed).
      sessionPacksPlayerId.value = playerId ?? null
    } catch (e) {
      packsError.value = e?.response?.data?.message ?? e?.message ?? 'Failed to load session packs'
    } finally {
      packsLoading.value = false
    }
  }

  async function purchasePack(playerId, packTierId, paymentMethodId) {
    await purchaseSessionPack(packTierId, playerId, paymentMethodId)
    await loadPlayerPacks(playerId)
  }

  async function initiatePausePack(purchaseId, pauseStartDate, pauseDurationDays) {
    packPauseLoading.value = true
    packPauseError.value = null
    try {
      const res = await pauseSessionPack(purchaseId, { pauseStartDate, pauseDurationDays })
      packPauseResult.value = res
      packPauseConflicts.value = res.conflictingBookings ?? []
      return res
    } catch (e) {
      packPauseError.value = e
      throw e
    } finally {
      packPauseLoading.value = false
    }
  }

  async function confirmPausePack(playerId, purchaseId, pauseStartDate, pauseDurationDays, confirmedCancellationIds) {
    packPauseLoading.value = true
    packPauseError.value = null
    try {
      const res = await pauseSessionPack(purchaseId, {
        pauseStartDate,
        pauseDurationDays,
        confirmedCancellationIds,
      })
      packPauseResult.value = res
      packPauseConflicts.value = []
      await loadPlayerPacks(playerId)
      return res
    } catch (e) {
      packPauseError.value = e
      throw e
    } finally {
      packPauseLoading.value = false
    }
  }

  async function loadParentBookings() {
    bookingsLoading.value = true
    bookingsError.value = null
    try {
      const res = await getParentBookings()
      parentBookings.value = res ?? []
    } catch (e) {
      bookingsError.value = e
    } finally {
      bookingsLoading.value = false
    }
  }

  // CONTRACT — loadCoachBookingRequests and loadCoachSchedule below NEVER RETHROW. Each swallows its
  // own failure into coachRequestsError / coachScheduleError and resolves normally, so an `await` on
  // either can never reject and callers need no .catch() guard. Two independent review passes on the
  // same day (2026-08-18) filed the same "unguarded refresh → unhandled promise rejection" defect
  // against call sites of these loaders; both were withdrawn as factually wrong. The real residual is
  // the opposite one — a failed refresh is silent, because coachRequestsError / coachScheduleError
  // are rendered by no component — which is what the coach pages' stale-list warnings close
  // (skillars-deferred-31 AC1). Do not add a rethrow here without fixing those call sites;
  // BookingRequestPage relies on a failed fetch being swallowed rather than blanking the slot list.
  //
  // Each therefore RETURNS ITS OWN OUTCOME: true if this invocation refreshed, false if it failed.
  // Callers deciding whether to warn "list may be out of date" must branch on that return value and
  // NOT re-read the error ref. The refs are module-scoped and reset-then-written on every call, so a
  // concurrent load — two rows accepted in quick succession (the per-row accepting[id]/declining[id]
  // flags deliberately allow it), a week-selector watcher, or onWrapUpComplete's 3-second setTimeout
  // reload — can overwrite or clear the ref between an earlier caller's await and its check, which
  // would warn the wrong coach action or swallow a real staleness. The return value is per-invocation
  // and cannot race. The refs stay as they are: they carry the error object itself, which a future
  // error-banner treatment will want. (skillars-deferred-31 AC1, review follow-up.)
  async function loadCoachBookingRequests() {
    coachRequestsLoading.value = true
    coachRequestsError.value = null
    try {
      const res = await getCoachBookingRequests()
      coachBookingRequests.value = res.singleBookings ?? []
      coachBatchGroups.value = res.batchGroups ?? []
      return true
    } catch (e) {
      coachRequestsError.value = e
      return false
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
      coachSchedule.value = res
      return true
    } catch (e) {
      coachScheduleError.value = e
      return false
    } finally {
      coachScheduleLoading.value = false
    }
  }

  async function loadParentSchedule(playerId) {
    parentScheduleLoading.value = true
    parentScheduleError.value = null
    try {
      const res = await getParentSchedule(playerId)
      parentSchedule.value = res
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

  // Both return their own refresh outcome so the caller can warn about a stale list without
  // re-reading the shared error ref — see the CONTRACT note above loadCoachBookingRequests.
  async function approveBooking(id) {
    await acceptBooking(id)
    return await loadCoachBookingRequests()
  }

  async function rejectBooking(id) {
    await declineBooking(id)
    return await loadCoachBookingRequests()
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

  async function handleRequestReschedule(bookingId, data) {
    completionLoading.value = true
    completionError.value = null
    try {
      await requestReschedule(bookingId, data)
      await loadParentBookings()
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleAcceptReschedule(bookingId, rescheduleId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await acceptReschedule(bookingId, rescheduleId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleDeclineReschedule(bookingId, rescheduleId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await declineReschedule(bookingId, rescheduleId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  async function handleDuplicateNextWeek(bookingId) {
    completionLoading.value = true
    completionError.value = null
    try {
      await duplicateNextWeek(bookingId)
    } catch (e) {
      completionError.value = e
      throw e
    } finally {
      completionLoading.value = false
    }
  }

  function addSlotToBasket(slot) {
    batchBasket.value.push(slot)
  }

  function removeSlotFromBasket(startDatetime) {
    batchBasket.value = batchBasket.value.filter((s) => s.startDatetime !== startDatetime)
  }

  function clearBatchBasket() {
    batchBasket.value = []
  }

  async function submitBatch(coachId, playerId, totalAmount) {
    batchSubmitting.value = true
    batchError.value = null
    try {
      const res = await createBatch({
        coachId,
        playerId,
        slots: batchBasket.value.map((s) => ({
          requestedStartTime: s.startDatetime,
          requestedEndTime: s.endDatetime,
        })),
        totalAmount,
      })
      clearBatchBasket()
      return res
    } catch (e) {
      batchError.value = e
      throw e
    } finally {
      batchSubmitting.value = false
    }
  }

  const batchAcceptLoading = ref(false)
  const batchAcceptError = ref(null)

  async function handleAcceptAllBatch(batchId) {
    batchAcceptLoading.value = true
    batchAcceptError.value = null
    try {
      await acceptAllBatch(batchId)
      // Returns its own refresh outcome — see the CONTRACT note above loadCoachBookingRequests.
      return await loadCoachBookingRequests()
    } catch (e) {
      batchAcceptError.value = e
      throw e
    } finally {
      batchAcceptLoading.value = false
    }
  }

  return {
    windows,
    blocks,
    computedSlots,
    coachTimezone,
    weekStart,
    loading,
    error,
    hasWindows,
    sessionPacks,
    sessionPacksPlayerId,
    packsLoading,
    packsError,
    creditsForCoach,
    parentBookings,
    bookingsLoading,
    bookingsError,
    coachBookingRequests,
    coachBatchGroups,
    coachRequestsLoading,
    coachRequestsError,
    batchBasket,
    batchBasketSize,
    batchSubmitting,
    batchError,
    isSlotInBasket,
    loadAvailability,
    createWindow,
    editWindow,
    removeWindow,
    createBlock,
    removeBlock,
    packPauseLoading,
    packPauseError,
    packPauseConflicts,
    packPauseResult,
    loadPlayerPacks,
    purchasePack,
    initiatePausePack,
    confirmPausePack,
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
    handleRequestReschedule,
    handleAcceptReschedule,
    handleDeclineReschedule,
    handleDuplicateNextWeek,
    addSlotToBasket,
    removeSlotFromBasket,
    clearBatchBasket,
    submitBatch,
    batchAcceptLoading,
    batchAcceptError,
    handleAcceptAllBatch,
  }
})
