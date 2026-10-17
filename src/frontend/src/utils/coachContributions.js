// skillars-deferred-152 AC2 — shared 30%-contribution-threshold logic.
//
// CoachContributionSection.vue's own `topAttributions` computed and CoachPlayerDetailPage.vue's
// `hasTopContributions` computed independently re-implemented the identical group-by-skill /
// filter->=30% logic. A `<script setup>` block compiles to a single default export (no named
// export is possible from inside one), so the shared logic has to live in a plain module —
// this file — rather than being exported from the component itself.

export const CONTRIBUTION_THRESHOLD_PCT = 30

/**
 * For each skillCode, keep only the single highest-percentage contributor, then filter to
 * those at or above the display threshold.
 *
 * @param {Array<{skillCode: string, percentageContribution: number, coachDisplayName: string}>} contributions
 * @returns {Array} the top contributor per skill that clears the threshold
 */
export function topAttributions(contributions) {
  const bySkill = {}
  for (const c of contributions ?? []) {
    if (
      !bySkill[c.skillCode] ||
      c.percentageContribution > bySkill[c.skillCode].percentageContribution
    ) {
      bySkill[c.skillCode] = c
    }
  }
  return Object.values(bySkill).filter(
    (c) => c.percentageContribution >= CONTRIBUTION_THRESHOLD_PCT,
  )
}

/**
 * @param {Array} contributions
 * @returns {boolean} true if at least one skill's top contributor clears the threshold
 */
export function hasTopAttributions(contributions) {
  return topAttributions(contributions).length > 0
}
