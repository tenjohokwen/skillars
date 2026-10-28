import { describe, it, expect } from 'vitest'
import { isUuid, formatDateTime } from '../adminFormat.js'

describe('adminFormat', () => {
  it('isUuid accepts a (trimmed) UUID and rejects anything else', () => {
    expect(isUuid(' aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee ')).toBe(true)
    expect(isUuid('AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE')).toBe(true)
    expect(isUuid('not-a-uuid')).toBe(false)
    expect(isUuid(null)).toBe(false)
    expect(isUuid(42)).toBe(false)
  })

  it('formatDateTime renders a dash for a missing value', () => {
    expect(formatDateTime(null, 'en-US')).toBe('—')
    expect(formatDateTime('2026-10-01T10:00:00Z', 'en-US')).toContain('2026')
  })
})
