import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// J · Development: the same radar, exposure and trends seen by the player and by the parent.

/** What the player's dashboard and the parent's portal share. */
abstract class DevelopmentScreen extends Screen {
  readonly readyTestId = 'radar-card'
  error = () => this.tid('development-error')
  radar = () => this.tid('skills-radar')
  radarEmpty = () => this.tid('radar-empty')
  radarCurrent = () => this.tid('radar-current')
  radarBaseline = () => this.tid('radar-baseline')
  /** The radar's accessible table (the SVG is aria-hidden): one row per skill. */
  radarRow = (skill: string) =>
    this.page.locator(`[data-testid="radar-row"][data-skill="${skill}"]`)
  radarScore = (skill: string) => this.within(this.radarRow(skill), 'radar-row-score')
  radarBaselineScore = (skill: string) => this.within(this.radarRow(skill), 'radar-row-baseline')
  abstract baselineToggle(): Locator
  skillTrendRows = () => this.tid('skill-trend-row')
  skillTrendsEmpty = () => this.tid('skill-trends-empty')
}

export class PlayerDevelopmentScreen extends DevelopmentScreen {
  readonly route = R.playerDevelopment
  baselineToggle = () => this.tid('radar-baseline-toggle')
  neglectedAlerts = () => this.tid('exposure-neglected-skill')
  zeroSkill = (skill: string) =>
    this.page.locator(`[data-testid="exposure-zero-skill"][data-skill="${skill}"]`)
  narrative = () => this.tid('narrative-summary')
  /** Anything on the page a player could use to change a score. */
  editingControls = () =>
    this.page.locator('[data-testid="coach-add-assessment"], [data-testid="radar-entry-submit"]')
}

export class ParentDevelopmentScreen extends DevelopmentScreen {
  readonly route = R.parentDevelopment
  baselineToggle = () => this.tid('parent-radar-baseline-toggle')
  narrative = () => this.tid('narrative-summary')
  narrativeItems = () => this.tid('narrative-item')
  neglectedBanner = () => this.tid('parent-neglected-banner')
  skillTrendsCard = () => this.tid('parent-skill-trends-card')
}
