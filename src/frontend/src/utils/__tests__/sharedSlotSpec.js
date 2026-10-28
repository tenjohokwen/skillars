import { describe, it, expect } from 'vitest'
import { parseSharedSlot, sharedSlotWeekStart, toZonedLocalIso } from 'src/utils/sharedSlot'

describe('sharedSlot utils', () => {
  it('accepts only a YYYY-MM-DDTHH:mm wall-clock value', () => {
    expect(parseSharedSlot('2026-11-04T09:30')).toBe('2026-11-04T09:30')
    expect(parseSharedSlot(['2026-11-04T09:30'])).toBe('2026-11-04T09:30')
    expect(parseSharedSlot('2026-11-04T09:30:00Z')).toBeNull()
    expect(parseSharedSlot('<script>')).toBeNull()
    expect(parseSharedSlot(undefined)).toBeNull()
  })

  it('finds the Monday of the shared slot week', () => {
    expect(sharedSlotWeekStart('2026-11-04T09:30')).toBe('2026-11-02') // Wednesday
    expect(sharedSlotWeekStart('2026-11-08T09:30')).toBe('2026-11-02') // Sunday
    expect(sharedSlotWeekStart('2026-11-02T09:30')).toBe('2026-11-02') // Monday
    expect(sharedSlotWeekStart(null)).toBeNull()
  })

  it('renders an instant as wall-clock time in the given timezone', () => {
    // 08:30Z on a November date is 09:30 in Berlin (CET, UTC+1).
    expect(toZonedLocalIso('2026-11-04T08:30:00Z', 'Europe/Berlin')).toBe('2026-11-04T09:30')
    expect(toZonedLocalIso('2026-11-04T08:30:00Z', null)).toBe('2026-11-04T08:30')
    expect(toZonedLocalIso('not-a-date', 'UTC')).toBeNull()
  })
})
