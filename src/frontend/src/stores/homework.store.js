import { defineStore } from 'pinia'
import { ref } from 'vue'
import { homeworkApi } from 'src/api/homework.api'

export const useHomeworkStore = defineStore('homework', () => {
  const assignments = ref([])
  const loading = ref(false)
  const error = ref(null)

  async function fetchDrills(playerId) {
    loading.value = true
    error.value = null
    try {
      const res = await homeworkApi.getLockerRoomDrills(playerId)
      assignments.value = res.data
    } catch (e) {
      error.value = e
      assignments.value = []
    } finally {
      loading.value = false
    }
  }

  async function markComplete(assignmentId) {
    await homeworkApi.markComplete(assignmentId)
    const a = assignments.value.find(a => a.assignmentId === assignmentId)
    if (a) a.completed = true
  }

  return { assignments, loading, error, fetchDrills, markComplete }
})
