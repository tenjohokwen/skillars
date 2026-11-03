import { validateTags, type TagTaxonomy } from './taxonomy.js'

export type Priority = 'P0' | 'P1' | 'P2'
export type ScenarioType = 'happy' | 'edge' | 'neg'

export interface QuarantineInfo {
  /** Who owns getting the test green again. */
  owner: string
  /** ISO date (YYYY-MM-DD) the test was quarantined. */
  since: string
  reason?: string
}

export interface ScenarioMeta {
  /** Plan id, e.g. "AUTH-01". */
  id: string
  priority: Priority
  type?: ScenarioType
  tags: readonly string[]
  /** Optional requirement / story reference carried into the report. */
  requirement?: string
  quarantine?: QuarantineInfo
}

/** Structural match for Playwright's TestDetails, so this module stays importable without it. */
export interface ScenarioDetails {
  tag: string[]
  annotation: { type: string; description?: string }[]
}

export const QUARANTINE_MAX_DAYS = 14
const SCENARIO_ID = /^[A-Z]+-\d{2,}$/
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export const ANNOTATION = {
  scenario: 'scenario',
  priority: 'priority',
  type: 'scenario-type',
  requirement: 'requirement',
  quarantine: 'quarantine',
  quarantineExpires: 'quarantine-expires',
} as const

/** The date a quarantine runs out, as YYYY-MM-DD. */
export function quarantineExpiry(since: string, maxDays = QUARANTINE_MAX_DAYS): string {
  if (!ISO_DATE.test(since)) throw new Error(`quarantine.since must be YYYY-MM-DD, got "${since}"`)
  const date = new Date(`${since}T00:00:00Z`)
  if (Number.isNaN(date.getTime())) throw new Error(`quarantine.since is not a date: "${since}"`)
  date.setUTCDate(date.getUTCDate() + maxDays)
  return date.toISOString().slice(0, 10)
}

export function isQuarantineExpired(expires: string, today: Date = new Date()): boolean {
  return today.toISOString().slice(0, 10) > expires
}

/**
 * Builds the `{ tag, annotation }` details object for a scenario, validating its tags against the
 * taxonomy at collection time — a mistagged test fails the whole run immediately instead of quietly
 * dropping out of every grep. Priority is an annotation, not a tag, so the report can group by risk
 * without doubling the tag vocabulary.
 */
export function scenarioDetails(taxonomy: TagTaxonomy, meta: ScenarioMeta): ScenarioDetails {
  if (!SCENARIO_ID.test(meta.id)) {
    throw new Error(`Scenario id "${meta.id}" must look like ABC-01`)
  }
  const tags = [...meta.tags]
  if (meta.quarantine && !tags.includes('@quarantine')) tags.push('@quarantine')
  if (!meta.quarantine && tags.includes('@quarantine')) {
    throw new Error(`${meta.id}: @quarantine requires quarantine { owner, since }`)
  }
  const problems = validateTags(taxonomy, tags)
  if (problems.length > 0) throw new Error(`${meta.id}: ${problems.join('; ')}`)

  const annotation: ScenarioDetails['annotation'] = [
    { type: ANNOTATION.scenario, description: meta.id },
    { type: ANNOTATION.priority, description: meta.priority },
  ]
  if (meta.type) annotation.push({ type: ANNOTATION.type, description: meta.type })
  if (meta.requirement) {
    annotation.push({ type: ANNOTATION.requirement, description: meta.requirement })
  }
  if (meta.quarantine) {
    if (!meta.quarantine.owner.trim()) throw new Error(`${meta.id}: quarantine.owner is required`)
    const { owner, since, reason } = meta.quarantine
    annotation.push({
      type: ANNOTATION.quarantine,
      description: `${owner} since ${since}${reason ? ` — ${reason}` : ''}`,
    })
    annotation.push({ type: ANNOTATION.quarantineExpires, description: quarantineExpiry(since) })
  }
  return { tag: tags, annotation }
}
