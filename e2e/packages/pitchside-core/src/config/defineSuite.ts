import { expect } from '@playwright/test'
import { createSuiteTest, type PitchsideTest } from '../fixtures/base.js'
import { TEST_ID_ATTRIBUTE } from '../selectors/testid.js'
import { scenarioDetails, type ScenarioDetails, type ScenarioMeta } from '../tags/scenario.js'
import { buildGrep, type GrepQuery, type GrepResult } from '../tags/taxonomy.js'
import { validateSuiteEnv, type SuiteEnv } from './SuiteEnv.js'

export interface SuiteHandle {
  readonly env: SuiteEnv
  /** The extended Playwright `test` with every Pitchside fixture. */
  readonly test: PitchsideTest
  readonly expect: typeof expect
  /** `test('…', suite.scenario({ id, priority, tags }), async (…) => …)` */
  scenario(meta: ScenarioMeta): ScenarioDetails
  grep(query: GrepQuery): GrepResult
  /** `use` defaults for playwright.config.ts. */
  readonly use: { baseURL: string; testIdAttribute: string }
}

/** One call describes the whole environment and returns the app's test API. */
export function defineSuite(env: SuiteEnv): SuiteHandle {
  const problems = validateSuiteEnv(env)
  if (problems.length > 0) {
    throw new Error(`Invalid SuiteEnv for "${env.name ?? '?'}":\n  - ${problems.join('\n  - ')}`)
  }
  const frozen = Object.freeze({ ...env })
  return {
    env: frozen,
    test: createSuiteTest(frozen),
    expect,
    scenario: (meta) => scenarioDetails(frozen.taxonomy, meta),
    grep: (query) => buildGrep(frozen.taxonomy, query),
    use: { baseURL: frozen.baseUrl, testIdAttribute: TEST_ID_ATTRIBUTE },
  }
}
