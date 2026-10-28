import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listMyAppeals, submitAppeal } from 'src/api/appeals.api'
import { useAuthStore } from 'src/stores/auth.store'

// FR-ADM-004: the caller's own appeals, shared by every AppealAction on a page so a list of N
// appealable rows costs one GET, not N.
export const useAppealStore = defineStore('appeal', () => {
  const appeals = ref([])
  const loaded = ref(false)
  // Whose appeals are cached: a logout + login as someone else in the same tab must not show the
  // previous user's appeal chips (no logout hook resets this store — it self-invalidates instead).
  let loadedForUserId = null
  let inFlight = null

  // Concurrent callers (every AppealAction mounting at once) share one request.
  function load({ force = false } = {}) {
    const userId = useAuthStore().userId ?? null
    if (loadedForUserId !== userId) {
      appeals.value = []
      loaded.value = false
    }
    if (inFlight) return inFlight
    if (loaded.value && !force) return Promise.resolve(appeals.value)
    inFlight = (async () => {
      try {
        const data = await listMyAppeals()
        appeals.value = Array.isArray(data) ? data : []
        loaded.value = true
        loadedForUserId = userId
        return appeals.value
      } finally {
        inFlight = null
      }
    })()
    return inFlight
  }

  // An appeal belongs to ONE enforcement of a subject (backend uq_appeals_per_enforcement is on
  // subject + enforced_at): a coach re-suspended, or a review re-blocked, after an earlier appeal is a
  // new, separately appealable action. With enforcedAt known, only an appeal against that same
  // enforcement counts; unknown (null) falls back to "any appeal for this subject".
  function findFor(subjectType, subjectId, enforcedAt = null) {
    if (!subjectId) return null
    const id = String(subjectId)
    const enforcedMs = enforcedAt ? Date.parse(enforcedAt) : NaN
    // Newest first from the backend; the first match is the latest appeal for this subject.
    return (
      appeals.value.find(
        (a) =>
          a.subjectType === subjectType &&
          String(a.subjectId) === id &&
          (Number.isNaN(enforcedMs) || Date.parse(a.enforcedAt) === enforcedMs),
      ) ?? null
    )
  }

  async function submit(payload) {
    const created = await submitAppeal(payload)
    if (created?.appealId) {
      appeals.value = [created, ...appeals.value.filter((a) => a.appealId !== created.appealId)]
    }
    return created
  }

  function reset() {
    appeals.value = []
    loaded.value = false
    loadedForUserId = null
    inFlight = null
  }

  return { appeals, loaded, load, findFor, submit, reset }
})
