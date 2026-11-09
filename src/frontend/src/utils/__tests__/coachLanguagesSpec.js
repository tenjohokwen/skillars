// skillars-deferred-173 (RS04, D4): the search filter offered German, English, Turkish and Arabic while coaches
// could pick English, German, French, Spanish, Arabic and Portuguese, so three languages were unsearchable and
// Turkish matched nobody. All three places now read COACH_LANGUAGES; this guards against a list creeping back.

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { COACH_LANGUAGES } from 'src/utils/coachLanguages'

const USERS = [
  'src/components/profileBuilder/ProfileBuilderStep1.vue',
  'src/components/profile/EditCoachIdentityDialog.vue',
  'src/pages/marketplace/MarketplacePage.vue',
]

// Vitest runs from src/frontend.
function source(path) {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

describe('COACH_LANGUAGES', () => {
  it('lists the seven languages once each', () => {
    expect(COACH_LANGUAGES).toEqual([
      'English',
      'German',
      'French',
      'Spanish',
      'Arabic',
      'Portuguese',
      'Turkish',
    ])
    expect(new Set(COACH_LANGUAGES).size).toBe(COACH_LANGUAGES.length)
  })

  for (const path of USERS) {
    it(`${path} takes its language options from COACH_LANGUAGES, not a list of its own`, () => {
      const code = source(path)
      expect(code).toContain("from 'src/utils/coachLanguages'")
      expect(code).toMatch(/const languageOptions = COACH_LANGUAGES\b/)
      expect(code).not.toMatch(/\[\s*'(English|German)'\s*,/)
    })
  }
})
