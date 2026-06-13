import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getCoachAvailability,
  addAvailabilityWindow,
  updateAvailabilityWindow,
  deleteAvailabilityWindow,
  addAvailabilityBlock,
  deleteAvailabilityBlock,
} from 'src/api/booking.api'

export const useBookingStore = defineStore('booking', () => {
  const windows = ref([])
  const blocks = ref([])
  const computedSlots = ref([])
  const weekStart = ref(null)
  const loading = ref(false)
  const error = ref(null)

  const hasWindows = computed(() => windows.value.length > 0)

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

  return {
    windows, blocks, computedSlots, weekStart, loading, error, hasWindows,
    loadAvailability, createWindow, editWindow, removeWindow, createBlock, removeBlock,
  }
})
