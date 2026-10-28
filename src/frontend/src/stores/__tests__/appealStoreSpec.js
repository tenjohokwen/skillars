// FR-ADM-004: appeal.store findFor — an appeal belongs to ONE enforcement of a subject.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/appeals.api', () => ({ listMyAppeals: vi.fn(), submitAppeal: vi.fn() }))

import { listMyAppeals } from 'src/api/appeals.api'
import { useAppealStore } from 'src/stores/appeal.store'
import { useAuthStore } from 'src/stores/auth.store'

const FIRST_SUSPENSION = '2026-10-01T10:00:00Z'
const SECOND_SUSPENSION = '2026-10-20T10:00:00Z'

describe('appeal.store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    listMyAppeals.mockResolvedValue([
      {
        appealId: 'a1',
        subjectType: 'COACH_SUSPENSION',
        subjectId: 'c1',
        enforcedAt: '2026-10-01T10:00:00.000Z',
        status: 'OVERTURNED',
      },
    ])
  })

  it('matches an appeal only against the same enforcement when enforcedAt is known', async () => {
    const store = useAppealStore()
    await store.load()
    expect(store.findFor('COACH_SUSPENSION', 'c1', FIRST_SUSPENSION)?.appealId).toBe('a1')
    // Re-suspended later: a new, separately appealable action.
    expect(store.findFor('COACH_SUSPENSION', 'c1', SECOND_SUSPENSION)).toBeNull()
    // Unknown enforcement: any appeal for the subject counts.
    expect(store.findFor('COACH_SUSPENSION', 'c1')?.appealId).toBe('a1')
  })

  it("does not show a previous user's appeals after the signed-in user changes", async () => {
    const store = useAppealStore()
    const auth = useAuthStore()
    auth.userId = 'u1'
    await store.load()
    expect(store.appeals).toHaveLength(1)

    auth.userId = 'u2'
    listMyAppeals.mockResolvedValue([])
    await store.load()
    expect(store.appeals).toHaveLength(0)
    expect(listMyAppeals).toHaveBeenCalledTimes(2)
  })
})
