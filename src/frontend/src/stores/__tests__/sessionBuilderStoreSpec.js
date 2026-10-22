// skillars-12-6 AC13 - sessionBuilder.store prep state: fetched after the plan, reset by
// initForBooking (including the 404 "no plan yet" path), and a failed fetch leaves the builder usable.

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/session.api', () => ({
  sessionApi: { getSessionPlanByBooking: vi.fn() },
}))
vi.mock('src/api/development.api', () => ({
  getSessionPrep: vi.fn(),
}))

import { sessionApi } from 'src/api/session.api'
import { getSessionPrep } from 'src/api/development.api'
import { useSessionBuilderStore } from 'src/stores/sessionBuilder.store'

describe('sessionBuilder.store prep state (skillars-12-6)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('starts with no prep', () => {
    expect(useSessionBuilderStore().prep).toBeNull()
  })

  it('fetchPrep stores the prep response', async () => {
    getSessionPrep.mockResolvedValue({ suggestedWhatsNext: 'Work on first touch' })
    const store = useSessionBuilderStore()
    store.initForBooking('b-1')

    await store.fetchPrep('b-1')

    expect(getSessionPrep).toHaveBeenCalledWith('b-1')
    expect(store.prep.suggestedWhatsNext).toBe('Work on first touch')
  })

  it('a failed prep fetch is swallowed into null and leaves the builder state intact', async () => {
    getSessionPrep.mockRejectedValue({ response: { status: 403 } })
    const store = useSessionBuilderStore()
    store.initForBooking('b-1')

    await expect(store.fetchPrep('b-1')).resolves.toBeUndefined()

    expect(store.prep).toBeNull()
    expect(store.error).toBeNull()
    expect(store.blocks).toHaveLength(4)
  })

  it('initForBooking clears a previously fetched prep', async () => {
    getSessionPrep.mockResolvedValue({ suggestedWhatsNext: 'x' })
    const store = useSessionBuilderStore()
    store.initForBooking('b-1')
    await store.fetchPrep('b-1')
    expect(store.prep).not.toBeNull()

    store.initForBooking('b-2')

    expect(store.prep).toBeNull()
  })

  // The store outlives the page: a response for booking A that resolves after the coach has moved on
  // to booking B must be dropped, or B's builder would show (and PATCH) A's plan note.
  it('a late prep response for a previous booking is ignored', async () => {
    let resolveA
    getSessionPrep.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveA = resolve
      }),
    )
    const store = useSessionBuilderStore()
    store.initForBooking('b-a')
    const pendingA = store.fetchPrep('b-a')

    store.initForBooking('b-b')
    resolveA({ coachPlanNote: 'note for A' })
    await pendingA

    expect(store.bookingId).toBe('b-b')
    expect(store.prep).toBeNull()
  })

  it('a late prep failure for a previous booking does not clear the current prep', async () => {
    let rejectA
    getSessionPrep.mockReturnValueOnce(
      new Promise((_, reject) => {
        rejectA = reject
      }),
    )
    getSessionPrep.mockResolvedValueOnce({ coachPlanNote: 'note for B' })
    const store = useSessionBuilderStore()
    store.initForBooking('b-a')
    const pendingA = store.fetchPrep('b-a')

    store.initForBooking('b-b')
    await store.fetchPrep('b-b')
    rejectA({ response: { status: 500 } })
    await pendingA

    expect(store.prep.coachPlanNote).toBe('note for B')
  })

  // The page awaits fetchExistingPlan and only THEN calls fetchPrep: on the 404 "no plan yet" path
  // fetchExistingPlan calls initForBooking a second time, which would wipe a concurrently fetched prep.
  it('prep fetched after a 404 "no plan yet" fetchExistingPlan survives', async () => {
    sessionApi.getSessionPlanByBooking.mockRejectedValue({ response: { status: 404 } })
    getSessionPrep.mockResolvedValue({ suggestedWhatsNext: 'carry me' })
    const store = useSessionBuilderStore()

    await store.fetchExistingPlan('b-1')
    await store.fetchPrep('b-1')

    expect(store.isGated).toBe(false)
    expect(store.prep.suggestedWhatsNext).toBe('carry me')
  })

  // Store-level only: fetchExistingPlan sets isGated, which SessionBuilderPage.onMounted reads to skip
  // fetchPrep. The page-level skip itself has no spec yet (deferred-work.md, "No page-level spec for
  // SessionBuilderPage mount ordering").
  it('a scout-gated plan fetch sets isGated, which the page uses to skip fetchPrep', async () => {
    sessionApi.getSessionPlanByBooking.mockRejectedValue({
      response: { status: 403, data: { helpCode: 'security.featureGated' } },
    })
    const store = useSessionBuilderStore()

    await store.fetchExistingPlan('b-1')

    expect(store.isGated).toBe(true)
    expect(store.prep).toBeNull()
  })
})
