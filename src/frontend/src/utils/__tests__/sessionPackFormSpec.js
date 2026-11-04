// skillars-deferred-168 pr-review V1/V2: packsPayload decides whether a save touches the coach's offer at all.
import { describe, it, expect } from 'vitest'
import { packsPayload } from 'src/utils/sessionPackForm'

const PACK = { sessionCount: 5, totalPrice: 200, label: 'Starter' }

describe('packsPayload', () => {
  it('is null when nothing changed, so a price-only save never touches the offer', () => {
    expect(packsPayload([PACK], [{ ...PACK }])).toBeNull()
    expect(packsPayload([], [])).toBeNull()
  })

  it('treats equal values in a different shape as unchanged (200 vs "200", empty label vs null)', () => {
    expect(
      packsPayload(
        [{ sessionCount: 5, totalPrice: 200, label: '' }],
        [{ sessionCount: '5', totalPrice: '200', label: null }],
      ),
    ).toBeNull()
  })

  it('is null for an added-then-emptied or added-then-removed row when the form started empty (V2)', () => {
    expect(packsPayload([], [{ sessionCount: null, totalPrice: null, label: '' }])).toBeNull()
    expect(packsPayload([], [{ sessionCount: '', totalPrice: '', label: '' }])).toBeNull()
  })

  it('sends the pack when one was added', () => {
    expect(packsPayload([], [PACK])).toEqual([PACK])
  })

  it('sends the new pack when it was edited', () => {
    expect(packsPayload([PACK], [{ ...PACK, totalPrice: 180 }])).toEqual([
      { ...PACK, totalPrice: 180 },
    ])
    expect(packsPayload([PACK], [{ ...PACK, label: 'Starter plus' }])).toHaveLength(1)
  })

  it('sends [] only when the coach removed the pack they were shown', () => {
    expect(packsPayload([PACK], [])).toEqual([])
    expect(packsPayload([PACK], [{ sessionCount: null, totalPrice: null, label: '' }])).toEqual([])
  })
})
