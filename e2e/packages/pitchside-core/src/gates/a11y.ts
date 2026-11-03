import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import AxeBuilder from '@axe-core/playwright'
import type { Page } from '@playwright/test'
import { GateViolationError } from './GateMode.js'

/** One (rule, element) pair — the unit a baseline accepts. */
export interface A11yFinding {
  rule: string
  impact: string | null
  target: string
  help: string
}

export interface A11yBaseline {
  screen: string
  accepted: { rule: string; target: string }[]
}

export interface A11yCheckOptions {
  /** Baseline key, usually the screen object's name. */
  screen: string
  include?: string[]
  exclude?: string[]
  /** axe tags, default WCAG 2.x A and AA. */
  tags?: string[]
}

export interface A11yGateOptions {
  baselineDir: string
  /** Write the current findings as the new baseline instead of failing. */
  update?: boolean
}

interface AxeViolation {
  id: string
  impact?: string | null
  help: string
  nodes: { target: unknown[] }[]
}

export function flattenViolations(violations: readonly AxeViolation[]): A11yFinding[] {
  return violations.flatMap((violation) =>
    violation.nodes.map((node) => ({
      rule: violation.id,
      impact: violation.impact ?? null,
      target: node.target.map(String).join(' >>> '),
      help: violation.help,
    })),
  )
}

/** Findings that the baseline does not already accept. */
export function newFindings(
  findings: readonly A11yFinding[],
  baseline: A11yBaseline | null,
): A11yFinding[] {
  const accepted = new Set((baseline?.accepted ?? []).map((a) => `${a.rule}\u0000${a.target}`))
  return findings.filter((finding) => !accepted.has(`${finding.rule}\u0000${finding.target}`))
}

export function toBaseline(screen: string, findings: readonly A11yFinding[]): A11yBaseline {
  const accepted = [...new Map(findings.map((f) => [`${f.rule}\u0000${f.target}`, f])).values()]
    .map(({ rule, target }) => ({ rule, target }))
    .sort((a, b) => (a.rule === b.rule ? compare(a.target, b.target) : compare(a.rule, b.rule)))
  return { screen, accepted }
}

// Code-point order, not localeCompare: baselines must sort the same on every machine.
const compare = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0)

const safeName = (screen: string): string => screen.replace(/[^a-zA-Z0-9_-]+/g, '-')

/**
 * axe-core with a per-screen baseline: existing violations do not block, new ones do. This is what
 * makes an accessibility gate adoptable on an app that was not built to WCAG.
 */
export class A11yGate {
  constructor(private readonly options: A11yGateOptions) {}

  baselinePath(screen: string): string {
    return join(this.options.baselineDir, `${safeName(screen)}.json`)
  }

  async loadBaseline(screen: string): Promise<A11yBaseline | null> {
    try {
      return JSON.parse(await readFile(this.baselinePath(screen), 'utf8')) as A11yBaseline
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return null
      throw error
    }
  }

  async check(page: Page, options: A11yCheckOptions): Promise<A11yFinding[]> {
    let builder = new AxeBuilder({ page }).withTags(
      options.tags ?? ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'],
    )
    for (const selector of options.include ?? []) builder = builder.include(selector)
    for (const selector of options.exclude ?? []) builder = builder.exclude(selector)
    const results = await builder.analyze()
    const findings = flattenViolations(results.violations)

    if (this.options.update) {
      const path = this.baselinePath(options.screen)
      await mkdir(dirname(path), { recursive: true })
      await writeFile(path, `${JSON.stringify(toBaseline(options.screen, findings), null, 2)}\n`)
      return []
    }
    const fresh = newFindings(findings, await this.loadBaseline(options.screen))
    if (fresh.length > 0) {
      throw new GateViolationError(
        `A11y (${options.screen})`,
        fresh.map((f) => `[${f.impact ?? 'n/a'}] ${f.rule} at ${f.target}: ${f.help}`),
      )
    }
    return fresh
  }
}
