/**
 * Tag vocabulary on four orthogonal axes. The kernel owns the axes and the values that change
 * infrastructure behaviour (scopes, and the traits its gates and lanes react to); an app adds its
 * personas, domains and any extra traits.
 */
export interface TagTaxonomy {
  readonly scope: readonly string[]
  readonly persona: readonly string[]
  readonly domain: readonly string[]
  readonly trait: readonly string[]
}

export type TagAxis = keyof TagTaxonomy

/** Scope is the contract for which runs pick a test up. Exactly one per test. */
export const CORE_SCOPES = ['@smoke', '@regression', '@nightly'] as const

/**
 * Traits the kernel itself reacts to:
 * - `@a11y` turns on the axe gate, `@visual` marks screenshot tests;
 * - `@destructive` and `@config` route a test to a serial lane;
 * - `@quarantine` is excluded from label runs and must carry an owner and a date;
 * - `@slow` is a scheduling hint.
 */
export const CORE_TRAITS = [
  '@slow',
  '@destructive',
  '@config',
  '@quarantine',
  '@a11y',
  '@visual',
] as const

const TAG_PATTERN = /^@[a-z0-9]+(?:-[a-z0-9]+)*$/

export interface TaxonomyExtension {
  persona: readonly string[]
  domain?: readonly string[]
  trait?: readonly string[]
}

/** Builds a taxonomy from the kernel's core values plus the app's own, validating every tag. */
export function defineTaxonomy(extension: TaxonomyExtension): TagTaxonomy {
  const taxonomy: TagTaxonomy = {
    scope: [...CORE_SCOPES],
    persona: [...extension.persona],
    domain: [...(extension.domain ?? [])],
    trait: [...CORE_TRAITS, ...(extension.trait ?? [])],
  }
  const seen = new Map<string, TagAxis>()
  for (const axis of Object.keys(taxonomy) as TagAxis[]) {
    for (const tag of taxonomy[axis]) {
      if (!TAG_PATTERN.test(tag)) {
        throw new Error(`Invalid tag "${tag}" on axis ${axis}: expected @lower-kebab-case`)
      }
      const previous = seen.get(tag)
      if (previous) {
        throw new Error(`Tag "${tag}" is declared on both ${previous} and ${axis}`)
      }
      seen.set(tag, axis)
    }
  }
  return taxonomy
}

export function axisOf(taxonomy: TagTaxonomy, tag: string): TagAxis | undefined {
  return (Object.keys(taxonomy) as TagAxis[]).find((axis) => taxonomy[axis].includes(tag))
}

/**
 * Returns the rule violations for one test's tags (empty when valid): unknown tags, not exactly
 * one scope, no persona. Several personas are fine — a two-persona test carries both.
 */
export function validateTags(taxonomy: TagTaxonomy, tags: readonly string[]): string[] {
  const problems: string[] = []
  const unknown = tags.filter((tag) => axisOf(taxonomy, tag) === undefined)
  if (unknown.length > 0) problems.push(`unknown tag(s): ${unknown.join(', ')}`)
  const scopes = tags.filter((tag) => taxonomy.scope.includes(tag))
  if (scopes.length !== 1) {
    problems.push(
      `exactly one scope tag required (${taxonomy.scope.join(' | ')}), got ${scopes.length}`,
    )
  }
  if (!tags.some((tag) => taxonomy.persona.includes(tag))) {
    problems.push(`at least one persona tag required (${taxonomy.persona.join(' | ')})`)
  }
  const duplicates = tags.filter((tag, index) => tags.indexOf(tag) !== index)
  if (duplicates.length > 0) problems.push(`duplicate tag(s): ${duplicates.join(', ')}`)
  return problems
}

const escapeRegExp = (value: string): string => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** Matches a whole tag: `@coach` must not match inside `@coach-admin`. */
const tagMatcher = (tag: string): string => `${escapeRegExp(tag)}(?![a-z0-9-])`

export interface GrepQuery {
  /** Every one of these tags must be present. */
  all?: readonly string[]
  /** At least one of these tags must be present. */
  any?: readonly string[]
  /** None of these tags may be present. */
  none?: readonly string[]
}

export interface GrepResult {
  grep?: RegExp
  grepInvert?: RegExp
}

/**
 * Turns a tag query into Playwright's `grep` / `grepInvert` pair, e.g. for a config file or a CI
 * script, instead of hand-writing look-ahead regexes. Tags are checked against the taxonomy so a
 * typo fails loudly rather than silently selecting nothing.
 */
export function buildGrep(taxonomy: TagTaxonomy, query: GrepQuery): GrepResult {
  const every = [...(query.all ?? []), ...(query.any ?? []), ...(query.none ?? [])]
  const unknown = every.filter((tag) => axisOf(taxonomy, tag) === undefined)
  if (unknown.length > 0) throw new Error(`buildGrep: unknown tag(s) ${unknown.join(', ')}`)

  const lookaheads = (query.all ?? []).map((tag) => `(?=.*${tagMatcher(tag)})`)
  if (query.any && query.any.length > 0) {
    lookaheads.push(`(?=.*(?:${query.any.map(tagMatcher).join('|')}))`)
  }
  const result: GrepResult = {}
  if (lookaheads.length > 0) result.grep = new RegExp(`^${lookaheads.join('')}`)
  if (query.none && query.none.length > 0) {
    result.grepInvert = new RegExp(query.none.map(tagMatcher).join('|'))
  }
  return result
}
