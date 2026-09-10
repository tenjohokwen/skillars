// skillars-deferred-108 AC3 — booking.store.js coach-request ordering + batch-accept guards
// (closes deferred-35/-36/-37/-38).
//
// Ledger (deferred-work.md:997): "No automated test coverage for loadCoachBookingRequests()'s
// concurrency/request-sequencing guard … same accepted gap deferred-35/36/37 recorded."
//
// Real Pinia store (setActivePinia + createPinia) with both api modules mocked. Covers:
//   - out-of-order + stale-failure guard in loadCoachBookingRequests (coachRequestsSequence)
//   - batchAcceptResultsByBatch prune fidelity (reassign only when an entry was removed)
//   - setBatchAcceptResult LRU cap (exercised via handleAcceptAllBatch, refresh forced to reject
//     so the success-path prune never runs)
//   - handleAcceptAllBatch unwrap contract (results IS the response body, not response.data)
//
// batchId is a UUID on the wire, and the LRU trick relies on JS preserving string-key insertion
// order — which holds only for non-array-index keys — so every id here is UUID-shaped, never
// '1'..'201'.
//
// Mutation check (run both ways, see Dev Agent Record):
//   - delete `if (requestId !== coachRequestsSequence) return true` (success path) → the
//     out-of-order test fails.
//   - `const results = await acceptAllBatch(batchId)` → `(await acceptAllBatch(batchId)).data` →
//     the unwrap-contract test fails (results becomes undefined).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/booking.api', () => {
  const names = [
    'getCoachAvailability',
    'addAvailabilityWindow',
    'updateAvailabilityWindow',
    'deleteAvailabilityWindow',
    'addAvailabilityBlock',
    'deleteAvailabilityBlock',
    'createBookingRequest',
    'acceptBooking',
    'declineBooking',
    'cancelBooking',
    'getParentBookings',
    'getCoachBookingRequests',
    'getBookingById',
    'getCoachSchedule',
    'getParentSchedule',
    'startSession',
    'endSession',
    'pauseSession',
    'resumeSession',
    'submitWrapUp',
    'initiateQuickComplete',
    'confirmCompletion',
    'requestReschedule',
    'acceptReschedule',
    'declineReschedule',
    'requestRescheduleAsCoach',
    'acceptRescheduleAsParent',
    'declineRescheduleAsParent',
    'duplicateNextWeek',
    'createBatch',
    'acceptAllBatch',
  ]
  return Object.fromEntries(names.map((n) => [n, vi.fn()]))
})
vi.mock('src/api/payment.api', () => ({
  purchaseSessionPack: vi.fn(),
  getMySessionPacks: vi.fn(),
  pauseSessionPack: vi.fn(),
}))

import { getCoachBookingRequests, acceptAllBatch, createBatch } from 'src/api/booking.api'
import { useBookingStore } from 'src/stores/booking.store'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** UUID-shaped, deterministic, non-array-index string key. */
function uuid(n) {
  return `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
}

describe('booking.store — coach-request ordering + batch-accept guards (deferred-108 AC3)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('loadCoachBookingRequests sequencing guard', () => {
    it('discards an out-of-order success and does not stomp the loading flag', async () => {
      const store = useBookingStore()
      const a = deferred()
      const b = deferred()
      getCoachBookingRequests.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)

      const callA = store.loadCoachBookingRequests()
      const callB = store.loadCoachBookingRequests()
      expect(store.coachRequestsLoading).toBe(true)

      // A (the superseded call) resolves first.
      a.resolve({ singleBookings: ['A'], batchGroups: [] })
      await callA

      // A must not have written its payload, nor cleared the loading flag B still owns.
      expect(store.coachBookingRequests).toEqual([])
      expect(store.coachRequestsLoading).toBe(true)

      b.resolve({ singleBookings: ['B'], batchGroups: [{ batchId: uuid(1) }] })
      await callB

      expect(store.coachBookingRequests).toEqual(['B'])
      expect(store.coachRequestsLoading).toBe(false)
      await expect(callA).resolves.toBe(true)
    })

    it('logs and swallows a stale failure without touching coachRequestsError', async () => {
      const store = useBookingStore()
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const a = deferred()
      const b = deferred()
      getCoachBookingRequests.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)

      const callA = store.loadCoachBookingRequests()
      const callB = store.loadCoachBookingRequests()

      a.reject(new Error('stale boom'))
      await expect(callA).resolves.toBe(true)
      expect(warn).toHaveBeenCalled()
      expect(store.coachRequestsError).toBeNull()

      b.resolve({ singleBookings: ['B'], batchGroups: [] })
      await callB
      expect(store.coachRequestsError).toBeNull()
      expect(store.coachBookingRequests).toEqual(['B'])
    })
  })

  describe('batchAcceptResultsByBatch prune fidelity', () => {
    async function seed(batchIds) {
      const store = useBookingStore()
      acceptAllBatch.mockResolvedValue(['result'])
      // The refresh inside handleAcceptAllBatch keeps every seeded batch visible so nothing is
      // pruned during seeding.
      getCoachBookingRequests.mockResolvedValue({
        singleBookings: [],
        batchGroups: batchIds.map((id) => ({ batchId: id })),
      })
      for (const id of batchIds) await store.handleAcceptAllBatch(id)
      return store
    }

    it('drops an entry whose batch left the visible list', async () => {
      const store = await seed([uuid(1), uuid(2)])
      expect(Object.keys(store.batchAcceptResultsByBatch).sort()).toEqual([uuid(1), uuid(2)])

      getCoachBookingRequests.mockResolvedValueOnce({
        singleBookings: [],
        batchGroups: [{ batchId: uuid(1) }],
      })
      await store.loadCoachBookingRequests()

      expect(Object.keys(store.batchAcceptResultsByBatch)).toEqual([uuid(1)])
    })

    it('does not reassign the map when the prune removed nothing', async () => {
      const store = await seed([uuid(1), uuid(2)])
      const ref1 = store.batchAcceptResultsByBatch

      getCoachBookingRequests.mockResolvedValueOnce({
        singleBookings: [],
        batchGroups: [{ batchId: uuid(1) }, { batchId: uuid(2) }],
      })
      await store.loadCoachBookingRequests()

      expect(store.batchAcceptResultsByBatch).toBe(ref1)
    })
  })

  describe('setBatchAcceptResult LRU cap (via handleAcceptAllBatch, refresh rejecting)', () => {
    it('keeps the 200 most-recently-written batch ids and evicts the oldest', async () => {
      const store = useBookingStore()
      vi.spyOn(console, 'warn').mockImplementation(() => {})
      acceptAllBatch.mockResolvedValue(['r'])
      // Refresh rejects → loadCoachBookingRequests swallows it, its success-path prune never runs,
      // so the map is bounded only by setBatchAcceptResult's own cap.
      getCoachBookingRequests.mockRejectedValue(new Error('refresh down'))

      for (let i = 1; i <= 201; i++) await store.handleAcceptAllBatch(uuid(i))

      const keys = Object.keys(store.batchAcceptResultsByBatch)
      expect(keys).toHaveLength(200)
      expect(keys).not.toContain(uuid(1)) // oldest evicted
      expect(keys).toContain(uuid(201)) // newest kept

      // Re-touch an existing id → it moves to most-recent and survives the next eviction.
      await store.handleAcceptAllBatch(uuid(2))
      await store.handleAcceptAllBatch(uuid(202))
      const keys2 = Object.keys(store.batchAcceptResultsByBatch)
      expect(keys2).toHaveLength(200)
      expect(keys2).toContain(uuid(2))
      expect(keys2).toContain(uuid(202))
    })
  })

  describe('handleAcceptAllBatch unwrap contract', () => {
    it('returns { refreshed, results } with results equal to the response body (not response.data)', async () => {
      const store = useBookingStore()
      const body = [
        { bookingId: 'x', outcome: 'ACCEPTED' },
        { bookingId: 'y', outcome: 'SKIPPED' },
      ]
      acceptAllBatch.mockResolvedValue(body)
      getCoachBookingRequests.mockResolvedValue({
        singleBookings: [],
        batchGroups: [{ batchId: uuid(1) }],
      })

      const out = await store.handleAcceptAllBatch(uuid(1))

      expect(out.results).toEqual(body)
      expect(out.results).not.toBeUndefined()
      expect(out.refreshed).toBe(true)
    })
  })

  // skillars-deferred-108 code review decision 1a -- see the header note.
  describe('batch basket -> wire payload (deferred-17/-18 store half)', () => {
    // Both slots carry a stale `.startTime`/`.endTime` decoy holding the PRE-rename (wrong) value,
    // so reading the legacy field yields a defined-but-wrong payload, not undefined.
    const slotA = {
      startDatetime: '2026-06-01T14:00:00',
      endDatetime: '2026-06-01T15:00:00',
      startTime: '2026-06-01T09:00:00',
      endTime: '2026-06-01T10:00:00',
    }
    const slotB = {
      startDatetime: '2026-06-02T16:30:00',
      endDatetime: '2026-06-02T17:30:00',
      startTime: '2026-06-02T11:30:00',
      endTime: '2026-06-02T12:30:00',
    }

    it('maps each basket slot onto requestedStartTime/EndTime from .startDatetime/.endDatetime', async () => {
      const store = useBookingStore()
      createBatch.mockResolvedValue({ batchId: uuid(7) })
      store.availabilitySignature = 'sig-abc'
      store.addSlotToBasket(slotA)
      store.addSlotToBasket(slotB)

      await store.submitBatch('coach-1', 'player-1', 8000)

      expect(createBatch).toHaveBeenCalledTimes(1)
      expect(createBatch).toHaveBeenCalledWith({
        coachId: 'coach-1',
        playerId: 'player-1',
        totalAmount: 8000,
        availabilitySignature: 'sig-abc',
        slots: [
          { requestedStartTime: '2026-06-01T14:00:00', requestedEndTime: '2026-06-01T15:00:00' },
          { requestedStartTime: '2026-06-02T16:30:00', requestedEndTime: '2026-06-02T17:30:00' },
        ],
      })

      // Guard the decoy explicitly: no legacy field may reach the wire.
      const sent = createBatch.mock.calls[0][0].slots
      for (const s of sent) {
        expect(s.requestedStartTime).toBeDefined()
        expect(s.requestedEndTime).toBeDefined()
        expect(s.requestedStartTime).not.toBe(slotA.startTime)
        expect(s.requestedStartTime).not.toBe(slotB.startTime)
      }
    })

    it('clears the basket and returns the response on success', async () => {
      const store = useBookingStore()
      const body = { batchId: uuid(8), status: 'REQUESTED' }
      createBatch.mockResolvedValue(body)
      store.addSlotToBasket(slotA)

      const out = await store.submitBatch('coach-1', 'player-1', 4000)

      expect(out).toEqual(body)
      expect(store.batchBasket).toEqual([])
      expect(store.batchBasketSize).toBe(0)
      expect(store.batchSubmitting).toBe(false)
      expect(store.batchError).toBeNull()
    })

    it('records batchError, rethrows, and leaves the basket intact on failure', async () => {
      const store = useBookingStore()
      const boom = new Error('batch rejected')
      createBatch.mockRejectedValue(boom)
      store.addSlotToBasket(slotA)

      await expect(store.submitBatch('coach-1', 'player-1', 4000)).rejects.toBe(boom)

      expect(store.batchError).toBe(boom)
      expect(store.batchSubmitting).toBe(false)
      // Not cleared -- the parent must be able to retry without re-picking slots.
      expect(store.batchBasket).toEqual([slotA])
    })

    it('removes a basket slot by its .startDatetime, not its legacy .startTime', () => {
      const store = useBookingStore()
      store.addSlotToBasket(slotA)
      store.addSlotToBasket(slotB)
      expect(store.batchBasketSize).toBe(2)

      store.removeSlotFromBasket(slotA.startDatetime)

      expect(store.batchBasket).toEqual([slotB])
      expect(store.isSlotInBasket(slotA.startDatetime)).toBe(false)
      expect(store.isSlotInBasket(slotB.startDatetime)).toBe(true)
    })

    it('ignores a removal keyed by the stale .startTime value', () => {
      const store = useBookingStore()
      store.addSlotToBasket(slotA)

      store.removeSlotFromBasket(slotA.startTime)

      expect(store.batchBasket).toEqual([slotA])
    })
  })
})
