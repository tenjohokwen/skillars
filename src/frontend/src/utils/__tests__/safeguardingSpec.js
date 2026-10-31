// S-09: formatMonthYear turns the API's ISO valid-until date into the "MM/YYYY" the public badge shows.

import { describe, it, expect } from 'vitest'
import { formatMonthYear } from 'src/utils/safeguarding'

describe('formatMonthYear', () => {
  it('formats an ISO date as MM/YYYY', () => {
    expect(formatMonthYear('2031-03-15')).toBe('03/2031')
    expect(formatMonthYear('2031-12-01')).toBe('12/2031')
  })

  it('does not shift the month for the 1st of a month (no Date round trip)', () => {
    expect(formatMonthYear('2031-01-01')).toBe('01/2031')
  })

  it('returns an empty string for a missing or malformed value', () => {
    expect(formatMonthYear(null)).toBe('')
    expect(formatMonthYear(undefined)).toBe('')
    expect(formatMonthYear('soon')).toBe('')
  })
})
