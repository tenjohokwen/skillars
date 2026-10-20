// skillars-deferred-155 AC14: the global `$t` test-stub used throughout the rest of the frontend
// suite echoes the key back (setup-file.js), so every raw-key assertion in every other spec pins
// only the template spelling — a typo'd or missing key in any locale file stays green while
// production renders the literal key string to users. This spec is the one place that actually
// opens the three locale files and compares their real key sets.
//
// Scope, explicit (per the story): structural key-SET parity across the three locale files only —
// same keys present at the same nesting, values may differ. Does NOT scan .vue templates for
// $t()/$te() calls and verify each resolves against a real key; that is a separate, larger
// static-analysis effort left for a future story.

import { describe, it, expect } from 'vitest'
import enUS from '../en-US/index.js'
import deDE from '../de-DE/index.js'
import frFR from '../fr-FR/index.js'

/** Recursively collects every leaf key path, e.g. "booking.requests.playerRequestLabel". */
function collectKeyPaths(obj, prefix = '') {
  const paths = []
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      paths.push(...collectKeyPaths(value, path))
    } else {
      paths.push(path)
    }
  }
  return paths
}

function diff(a, b) {
  const bSet = new Set(b)
  return a.filter((k) => !bSet.has(k))
}

describe('i18n locale key parity (skillars-deferred-155 AC14)', () => {
  const enKeys = collectKeyPaths(enUS)
  const deKeys = collectKeyPaths(deDE)
  const frKeys = collectKeyPaths(frFR)

  it('de-DE has no keys missing relative to en-US', () => {
    expect(diff(enKeys, deKeys)).toEqual([])
  })

  it('de-DE has no extra keys relative to en-US', () => {
    expect(diff(deKeys, enKeys)).toEqual([])
  })

  it('fr-FR has no keys missing relative to en-US', () => {
    expect(diff(enKeys, frKeys)).toEqual([])
  })

  it('fr-FR has no extra keys relative to en-US', () => {
    expect(diff(frKeys, enKeys)).toEqual([])
  })
})
