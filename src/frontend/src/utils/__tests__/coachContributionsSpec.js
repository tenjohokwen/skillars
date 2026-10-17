// skillars-deferred-152 AC2 — shared 30%-contribution-threshold logic.
//
// CoachContributionSection.vue's own `topAttributions` computed and
// CoachPlayerDetailPage.vue's `hasTopContributions` computed (plus
// ParentDevelopmentPortalPage.vue's new third consumer) all now import this module instead of
// each re-implementing the identical group-by-skill/>=30% filter. The critical regression this
// guards against is future drift between those copies at the exact boundary value.

import { describe, it, expect } from 'vitest'
import {
  topAttributions,
  hasTopAttributions,
  contributionEmptyStateKind,
} from 'src/utils/coachContributions'

function contribution(skillCode, percentageContribution, coachDisplayName = 'Coach One') {
  return { skillCode, percentageContribution, coachDisplayName }
}

describe('coachContributions — 30% threshold boundary', () => {
  it('includes a contribution at exactly 30% (>=, not >)', () => {
    const result = topAttributions([contribution('SHOOTING', 30)])
    expect(result).toHaveLength(1)
    expect(hasTopAttributions([contribution('SHOOTING', 30)])).toBe(true)
  })

  it('excludes a contribution just below 30%', () => {
    const result = topAttributions([contribution('SHOOTING', 29.99)])
    expect(result).toHaveLength(0)
    expect(hasTopAttributions([contribution('SHOOTING', 29.99)])).toBe(false)
  })

  it('keeps only the single highest-percentage contributor per skill', () => {
    const result = topAttributions([
      contribution('PASSING', 40, 'Coach A'),
      contribution('PASSING', 60, 'Coach B'),
    ])
    expect(result).toHaveLength(1)
    expect(result[0].coachDisplayName).toBe('Coach B')
  })

  it('returns an empty list and false for no contributions', () => {
    expect(topAttributions([])).toEqual([])
    expect(hasTopAttributions([])).toBe(false)
    expect(hasTopAttributions(undefined)).toBe(false)
  })

  it('returns true when at least one of several skills clears the threshold', () => {
    expect(hasTopAttributions([contribution('SHOOTING', 10), contribution('PASSING', 35)])).toBe(
      true,
    )
  })
})

// skillars-deferred-153 AC10: the empty-state SELECTION RULE — which was independently
// re-implemented on CoachPlayerDetailPage.vue and ParentDevelopmentPortalPage.vue (differing only
// in which i18n namespace each page maps the two outcomes onto) — is now this one shared
// discriminator both pages call instead of each re-deriving the length===0 check.
describe('contributionEmptyStateKind', () => {
  it('returns "none" for an empty array', () => {
    expect(contributionEmptyStateKind([])).toBe('none')
  })

  it('returns "none" for undefined/null (no data fetched yet)', () => {
    expect(contributionEmptyStateKind(undefined)).toBe('none')
    expect(contributionEmptyStateKind(null)).toBe('none')
  })

  it('returns "belowThreshold" when contributions exist, regardless of their percentages', () => {
    expect(contributionEmptyStateKind([contribution('SHOOTING', 5)])).toBe('belowThreshold')
    // Even a contribution that itself clears the 30% threshold — this discriminator only answers
    // "does raw contribution data exist at all," not "did anything clear the threshold"; the
    // caller combines it with hasTopAttributions separately for the full UI decision.
    expect(contributionEmptyStateKind([contribution('SHOOTING', 90)])).toBe('belowThreshold')
  })
})
