import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { sessionApi } from 'src/api/session.api'

export const useSessionBuilderStore = defineStore('sessionBuilder', () => {
  const sessionId = ref(null)
  const bookingId = ref(null)
  const status = ref('DRAFT')
  const blocks = ref([])
  const developmentFocus = ref([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref(null)
  const isGated = ref(false)

  const sessionDna = computed(() => {
    const allDrills = blocks.value.flatMap((b) => b.drills.map((d) => d.drill?.metadata).filter(Boolean))
    if (!allDrills.length) return { technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }

    const count = allDrills.length
    let sumTech = 0, sumPhys = 0, sumCog = 0, sumMatch = 0, weakFoot = 0
    for (const m of allDrills) {
      sumTech += ((m.intensity ?? 1) + (m.pressureLevel ?? 1)) / 2
      sumPhys += (m.intensity ?? 1)
      sumCog += (m.cognitiveLoad ?? 1)
      sumMatch += (m.matchRealism ?? 1)
      if (m.weakFootBias) weakFoot++
    }

    return {
      technical: mapToScore(sumTech / count),
      physical: mapToScore(sumPhys / count),
      cognitive: mapToScore(sumCog / count),
      matchRealism: mapToScore(sumMatch / count),
      weakFootFocus: Math.round((weakFoot / count) * 100),
    }
  })

  const equipmentList = computed(() => {
    const items = blocks.value
      .flatMap((b) => b.drills.map((d) => d.drill?.metadata?.equipmentRequired ?? []))
      .flat()
      .map((e) => e.toLowerCase().trim())
      .filter((e) => e.length > 0)
    return [...new Set(items)].sort()
  })

  function blockSlu(block) {
    return block.drills.reduce((sum, d) => {
      const meta = d.drill?.metadata
      if (!meta) return sum
      const skillTotal = Math.max(1, Object.values(meta.skillWeighting ?? {}).reduce((a, v) => a + v, 0))
      return sum + (meta.repDensity ?? 0) * skillTotal
    }, 0)
  }

  function initForBooking(bId) {
    sessionId.value = null
    bookingId.value = bId
    status.value = 'DRAFT'
    blocks.value = [
      { _uid: 1, blockType: 'WARM_UP', blockName: 'Warm-Up', durationMinutes: 10, drills: [] },
      { _uid: 2, blockType: 'TECHNICAL_FOUNDATION', blockName: 'Technical Foundation', durationMinutes: 15, drills: [] },
      { _uid: 3, blockType: 'GAME_INTENSITY', blockName: 'Game Intensity', durationMinutes: 25, drills: [] },
      { _uid: 4, blockType: 'COOL_DOWN_REVIEW', blockName: 'Cool-Down & Review', durationMinutes: 10, drills: [] },
    ]
    developmentFocus.value = []
    error.value = null
    isGated.value = false
  }

  async function fetchExistingPlan(bId) {
    initForBooking(bId)
    loading.value = true
    try {
      const resp = await sessionApi.getSessionPlanByBooking(bId)
      const plan = resp.data
      sessionId.value = plan.id
      bookingId.value = plan.bookingId
      status.value = plan.status
      blocks.value = plan.blocks.map((b, i) => ({
        _uid: i + 1,
        blockType: b.blockType,
        blockName: b.blockName,
        durationMinutes: b.durationMinutes,
        drills: b.drills.map((d) => ({ drillId: d.drillId, order: d.order, drill: d.drill })),
      }))
      developmentFocus.value = [...plan.developmentFocus]
    } catch (e) {
      if (e?.response?.status === 404) {
        initForBooking(bId)
      } else if (e?.response?.status === 403 && e?.response?.data?.helpCode === 'security.featureGated') {
        isGated.value = true
      } else {
        error.value = e
      }
    } finally {
      loading.value = false
    }
  }

  async function savePlan(newStatus) {
    if (saving.value) return
    saving.value = true
    error.value = null
    try {
      const payloadStatus = newStatus ?? status.value
      const payload = {
        blocks: blocks.value.map((b) => ({
          blockType: b.blockType,
          blockName: b.blockName,
          durationMinutes: b.durationMinutes,
          drills: b.drills.map((d, idx) => ({ drillId: d.drillId, order: idx })),
        })),
        developmentFocus: developmentFocus.value,
        ...(['DRAFT', 'SAVED'].includes(payloadStatus) && { status: payloadStatus }),
      }

      let resp
      if (sessionId.value) {
        resp = await sessionApi.updateSessionPlan(sessionId.value, payload)
      } else {
        resp = await sessionApi.createSessionPlan({ bookingId: bookingId.value, ...payload })
        sessionId.value = resp.data.id
      }
      status.value = resp.data.status
    } catch (e) {
      error.value = e
      throw e
    } finally {
      saving.value = false
    }
  }

  function addDrillToBlock(blockIndex, drill) {
    if (blockIndex < 0 || blockIndex >= blocks.value.length) return
    const block = blocks.value[blockIndex]
    if (block.drills.some((d) => d.drillId === drill.id)) return
    const order = block.drills.length
    block.drills.push({ drillId: drill.id, order, drill })
  }

  function removeDrillFromBlock(blockIndex, drillIndex) {
    if (blockIndex < 0 || blockIndex >= blocks.value.length) return
    blocks.value[blockIndex].drills.splice(drillIndex, 1)
  }

  function updateBlockMeta(blockIndex, meta) {
    if (blockIndex < 0 || blockIndex >= blocks.value.length) return
    Object.assign(blocks.value[blockIndex], meta)
  }

  return {
    sessionId,
    bookingId,
    status,
    blocks,
    developmentFocus,
    loading,
    saving,
    error,
    isGated,
    sessionDna,
    equipmentList,
    blockSlu,
    initForBooking,
    fetchExistingPlan,
    savePlan,
    addDrillToBlock,
    removeDrillFromBlock,
    updateBlockMeta,
  }
})

function mapToScore(avg) {
  return Math.max(0, Math.min(100, Math.round((avg - 1.0) * 25.0)))
}
