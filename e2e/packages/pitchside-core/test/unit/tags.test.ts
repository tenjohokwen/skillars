import { describe, expect, it } from 'vitest'
import {
  buildGrep,
  defineTaxonomy,
  isQuarantineExpired,
  quarantineExpiry,
  scenarioDetails,
  validateTags,
} from '../../src/index.js'

const taxonomy = defineTaxonomy({
  persona: ['@guest', '@coach', '@coach-admin', '@parent'],
  domain: ['@booking'],
  trait: ['@tz'],
})

describe('defineTaxonomy', () => {
  it('merges the core scopes and traits with the app values', () => {
    expect(taxonomy.scope).toEqual(['@smoke', '@regression', '@nightly'])
    expect(taxonomy.trait).toContain('@destructive')
    expect(taxonomy.trait).toContain('@tz')
  })

  it('rejects malformed tags and tags declared on two axes', () => {
    expect(() => defineTaxonomy({ persona: ['coach'] })).toThrow(/Invalid tag "coach"/)
    expect(() => defineTaxonomy({ persona: ['@Coach'] })).toThrow(/Invalid tag/)
    expect(() => defineTaxonomy({ persona: ['@slow'] })).toThrow(
      /both trait and persona|both persona and trait/,
    )
  })
})

describe('validateTags', () => {
  it('accepts one scope plus one or more personas', () => {
    expect(validateTags(taxonomy, ['@smoke', '@coach', '@parent', '@booking'])).toEqual([])
  })

  it('reports every rule a tag set breaks', () => {
    const problems = validateTags(taxonomy, ['@smoke', '@nightly', '@nope', '@nope'])
    expect(problems.join(' | ')).toMatch(/unknown tag\(s\): @nope/)
    expect(problems.join(' | ')).toMatch(/exactly one scope/)
    expect(problems.join(' | ')).toMatch(/at least one persona/)
    expect(problems.join(' | ')).toMatch(/duplicate/)
  })
})

describe('buildGrep', () => {
  const titles = {
    coachSmoke: 'books a slot @smoke @coach @booking',
    adminSmoke: 'audits @smoke @coach-admin',
    parentRegression: 'pays @regression @parent @booking',
    slowParent: 'uploads @regression @parent @slow',
  }

  it('builds an AND of required tags with whole-tag matching', () => {
    const { grep } = buildGrep(taxonomy, { all: ['@coach', '@smoke'] })
    expect(grep?.test(titles.coachSmoke)).toBe(true)
    // @coach must not match inside @coach-admin
    expect(grep?.test(titles.adminSmoke)).toBe(false)
  })

  it('supports any-of and an exclusion list', () => {
    const { grep, grepInvert } = buildGrep(taxonomy, {
      any: ['@coach', '@parent'],
      none: ['@slow'],
    })
    expect(grep?.test(titles.parentRegression)).toBe(true)
    expect(grepInvert?.test(titles.slowParent)).toBe(true)
    expect(grepInvert?.test(titles.parentRegression)).toBe(false)
  })

  it('fails loudly on an unknown tag instead of selecting nothing', () => {
    expect(() => buildGrep(taxonomy, { all: ['@smok'] })).toThrow(/unknown tag/)
  })
})

describe('scenarioDetails', () => {
  it('carries priority and id as annotations, not tags', () => {
    const details = scenarioDetails(taxonomy, {
      id: 'BKG-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@parent', '@coach', '@booking'],
      requirement: 'FR-12',
    })
    expect(details.tag).toEqual(['@smoke', '@parent', '@coach', '@booking'])
    expect(details.annotation).toEqual([
      { type: 'scenario', description: 'BKG-01' },
      { type: 'priority', description: 'P0' },
      { type: 'scenario-type', description: 'happy' },
      { type: 'requirement', description: 'FR-12' },
    ])
  })

  it('rejects a bad id or invalid tags at collection time', () => {
    expect(() =>
      scenarioDetails(taxonomy, { id: 'bkg1', priority: 'P1', tags: ['@smoke', '@coach'] }),
    ).toThrow(/must look like/)
    expect(() =>
      scenarioDetails(taxonomy, { id: 'BKG-02', priority: 'P1', tags: ['@coach'] }),
    ).toThrow(/exactly one scope/)
  })

  it('adds @quarantine with owner and expiry, and refuses a bare @quarantine tag', () => {
    const details = scenarioDetails(taxonomy, {
      id: 'BKG-03',
      priority: 'P1',
      tags: ['@regression', '@parent'],
      quarantine: { owner: 'payments-team', since: '2026-10-01', reason: 'flaky webhook' },
    })
    expect(details.tag).toContain('@quarantine')
    expect(details.annotation).toContainEqual({
      type: 'quarantine-expires',
      description: '2026-10-15',
    })
    expect(() =>
      scenarioDetails(taxonomy, {
        id: 'BKG-04',
        priority: 'P1',
        tags: ['@regression', '@parent', '@quarantine'],
      }),
    ).toThrow(/requires quarantine/)
  })
})

describe('quarantine expiry', () => {
  it('expires 14 days after it started', () => {
    expect(quarantineExpiry('2026-02-20')).toBe('2026-03-06')
    expect(isQuarantineExpired('2026-03-06', new Date('2026-03-06T23:00:00Z'))).toBe(false)
    expect(isQuarantineExpired('2026-03-06', new Date('2026-03-07T00:00:01Z'))).toBe(true)
  })

  it('rejects a malformed date', () => {
    expect(() => quarantineExpiry('03/06/2026')).toThrow(/YYYY-MM-DD/)
  })
})
