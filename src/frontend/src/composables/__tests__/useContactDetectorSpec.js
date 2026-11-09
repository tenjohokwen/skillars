// Pitchside REG-07: the registration name fields warn about the contact details the backend strips from them.

import { describe, it, expect } from 'vitest'
import { ref } from 'vue'
import { useContactDetector } from 'src/composables/useContactDetector'

const detects = (text) => useContactDetector(ref(text)).hasContactDetail.value

describe('useContactDetector', () => {
  it('flags a phone number, an email address and a link', () => {
    expect(detects('Call 0791 112 3456')).toBe(true)
    expect(detects('kid@example.com')).toBe(true)
    expect(detects('www.example.com')).toBe(true)
    expect(detects('see https://example.com/me')).toBe(true)
  })

  it('leaves ordinary names alone, dotted initials included', () => {
    expect(detects('Alex')).toBe(false)
    expect(detects('J.R. Smith')).toBe(false)
    expect(detects('St.Clair')).toBe(false)
    expect(detects('')).toBe(false)
  })
})
