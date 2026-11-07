import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { ParentDevelopmentScreen, PlayerDevelopmentScreen } from '../../src/screens/index.js'

// J · Development, part 1: the player's dashboard and the parent's portal over the same data.
//
// Radar scores come from a coach's assessments through the product's API (an Instructor coach with
// a live booking). Specs compare the page with what /radar/display returns rather than hard-coding
// composites: how a missing assessment type weighs in is an open owner question. Weekly exposure
// and neglected-skill flags are SQL seams (a completed, planned session and a weekly job make them).

const SKILLS = ['PAC', 'SHO', 'PAS']

/** An Instructor coach with a live booking for the player, so the coach may assess them. */
async function assessingCoach(
  provision: Provisioner,
  payer: Parameters<Provisioner['confirmedBooking']>[0],
): Promise<CoachAccount> {
  const coach = await provision.coach()
  await provision.saveCard(coach)
  await provision.subscribeCoach(coach, 'INSTRUCTOR')
  await provision.saveCard(payer)
  await provision.confirmedBooking(payer, coach)
  return coach
}

/**
 * Two rounds of assessment, so each scored skill has a baseline (the first composite) that differs
 * from its current score. Returns the scores as the API reports them.
 */
async function assessTwice(
  provision: Provisioner,
  coach: CoachAccount,
  viewer: Parameters<Provisioner['radarScores']>[0],
  playerId: string,
) {
  await provision.radarAssessment(coach, playerId, 'OBJECTIVE', { PAC: 60, SHO: 50, PAS: 40 })
  await provision.radarScores(viewer, playerId, (s) => SKILLS.every((k) => s[k]?.baseline != null))
  await provision.radarAssessment(coach, playerId, 'OBJECTIVE', { PAC: 90, SHO: 80, PAS: 70 })
  return provision.radarScores(viewer, playerId, (s) =>
    SKILLS.every((k) => s[k]?.score != null && s[k].score !== s[k].baseline),
  )
}

/** PAC: 40 SLU over weeks 4–7 ago, 60 over the last four weeks — a 50% increase. */
const increasingPac = [
  ...[4, 5, 6, 7].map((weeksAgo) => ({ skill: 'PAC', weeksAgo, slu: 10 })),
  ...[0, 1, 2, 3].map((weeksAgo) => ({ skill: 'PAC', weeksAgo, slu: 15 })),
]

/** The UI prints scores as the API sends them; normalise both to a plain number. */
const num = (text: string | null) => Number((text ?? '').trim())

test.describe('J · Player development dashboard', () => {
  test(
    'the player sees their coach-scored radar',
    scenario({
      id: 'DEV-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@player', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const coach = await assessingCoach(provision, player)
      await provision.radarAssessment(coach, player.playerId!, 'OBJECTIVE', {
        PAC: 60,
        SHO: 50,
        PAS: 40,
      })
      const scores = await provision.radarScores(player, player.playerId!, (s) =>
        SKILLS.every((k) => s[k]?.score != null),
      )

      const self = await actor(asRole(player))
      const dashboard = await new PlayerDevelopmentScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      // Every assessed axis carries the coach's composite, and the shape is drawn.
      await expect(dashboard.radarCurrent()).toBeVisible()
      for (const skill of SKILLS) {
        expect(num(await dashboard.radarScore(skill).textContent())).toBe(scores[skill]!.score)
      }
      // An axis nobody assessed has no score.
      await expect(dashboard.radarScore('DRI')).toHaveText('—')
    },
  )

  test(
    'the baseline toggle overlays the first scores',
    scenario({
      id: 'DEV-02',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const coach = await assessingCoach(provision, player)
      const scores = await assessTwice(provision, coach, player, player.playerId!)

      const self = await actor(asRole(player))
      const dashboard = await new PlayerDevelopmentScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      await expect(dashboard.radarCurrent()).toBeVisible()
      await expect(dashboard.radarBaseline()).toHaveCount(0)
      await dashboard.baselineToggle().click()
      await expect(dashboard.radarBaseline()).toBeVisible()
      for (const skill of SKILLS) {
        expect(num(await dashboard.radarBaselineScore(skill).textContent())).toBe(
          scores[skill]!.baseline,
        )
        expect(num(await dashboard.radarScore(skill).textContent())).toBe(scores[skill]!.score)
      }
      await dashboard.baselineToggle().click()
      await expect(dashboard.radarBaseline()).toHaveCount(0)
    },
  )

  test(
    'the radar is read-only for the player, and the narrative is not shown to them',
    scenario({
      id: 'DEV-03',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const coach = await assessingCoach(provision, player)
      await provision.radarAssessment(coach, player.playerId!, 'OBJECTIVE', {
        PAC: 60,
        SHO: 50,
        PAS: 40,
      })
      await provision.radarScores(player, player.playerId!, (s) => s.PAC?.score != null)
      // Exposure that gives the parent's portal a narrative (DEV-06): none must show here.
      await provision.seedExposure(player.playerId!, increasingPac)

      const self = await actor(asRole(player))
      const dashboard = await new PlayerDevelopmentScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      await expect(dashboard.radarCurrent()).toBeVisible()
      await expect(dashboard.editingControls()).toHaveCount(0)
      await expect(dashboard.narrative()).toHaveCount(0)
    },
  )

  test(
    'the exposure chart names a neglected skill, even one with no exposure this week',
    scenario({
      id: 'DEV-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      await provision.seedExposure(player.playerId!, [{ skill: 'PAC', weeksAgo: 0, slu: 40 }])
      await provision.flagNeglected(player.playerId!, 'SHO')

      const self = await actor(asRole(player))
      const dashboard = await new PlayerDevelopmentScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      await expect(dashboard.neglectedAlerts()).toHaveText([
        t('development.neglectedSkillAlert', { skill: 'SHO' }),
      ])
      // SHO had no exposure this week, so it is not a bar: its chip carries the alert.
      await expect(dashboard.zeroSkill('SHO')).toHaveAttribute('data-neglected', 'true')
      await expect(dashboard.zeroSkill('SHO')).toContainText(
        t('development.neglectedSkillAlert', { skill: 'SHO' }),
      )
      // An unpractised skill that is not flagged stays neutral.
      await expect(dashboard.zeroSkill('PAS')).toHaveAttribute('data-neglected', 'false')
      await expect(dashboard.zeroSkill('PAS')).toContainText(t('development.noExposureYet'))
    },
  )

  test(
    'a player with no assessments sees the empty radar',
    scenario({
      id: 'DEV-05',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@player', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const dashboard = await new PlayerDevelopmentScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      await expect(dashboard.radarEmpty()).toHaveText(t('development.radar.noAssessmentsYet'))
      await expect(dashboard.radarCurrent()).toHaveCount(0)
      await expect(dashboard.baselineToggle()).toHaveCount(0)
      await expect(dashboard.error()).toHaveCount(0)
    },
  )
})

test.describe('J · Parent development portal', () => {
  test(
    "the parent sees the child's radar, the narrative summary and the neglected skills",
    scenario({
      id: 'DEV-06',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      const child = parent.children[0]!.playerId
      const coach = await assessingCoach(provision, parent)
      await provision.radarAssessment(coach, child, 'OBJECTIVE', { PAC: 60, SHO: 50, PAS: 40 })
      const scores = await provision.radarScores(parent, child, (s) =>
        SKILLS.every((k) => s[k]?.score != null),
      )
      await provision.seedExposure(child, increasingPac)
      await provision.flagNeglected(child, 'SHO')

      const self = await actor(asRole(parent))
      const portal = await new ParentDevelopmentScreen(self.page, suite).open({ playerId: child })

      // The same composites the player would see.
      await expect(portal.radarCurrent()).toBeVisible()
      for (const skill of SKILLS) {
        expect(num(await portal.radarScore(skill).textContent())).toBe(scores[skill]!.score)
      }
      // The narrative, which the player's own view does not have (DEV-03).
      await expect(portal.narrativeItems()).toContainText([
        t('development.narrative.increased', { skill: 'PAC', percent: 50 }),
      ])
      // The neglected-skill banner.
      const prefix = t('development.portal.neglectedAlert', { skills: '\u0000' }).split(
        '\u0000',
      )[0]!
      await expect(portal.neglectedBanner()).toContainText(prefix)
    },
  )

  test(
    "the parent's portal has the baseline toggle and the skill-trends card",
    scenario({
      id: 'DEV-14',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      const child = parent.children[0]!.playerId
      const coach = await assessingCoach(provision, parent)
      const scores = await assessTwice(provision, coach, parent, child)
      await provision.seedExposure(child, increasingPac)

      const self = await actor(asRole(parent))
      const portal = await new ParentDevelopmentScreen(self.page, suite).open({ playerId: child })

      await expect(portal.radarCurrent()).toBeVisible()
      await expect(portal.radarBaseline()).toHaveCount(0)
      await portal.baselineToggle().click()
      await expect(portal.radarBaseline()).toBeVisible()
      for (const skill of SKILLS) {
        expect(num(await portal.radarBaselineScore(skill).textContent())).toBe(
          scores[skill]!.baseline,
        )
      }
      await expect(portal.skillTrendsCard()).toBeVisible()
      await expect(portal.skillTrendRows().first()).toBeVisible()
    },
  )

  test(
    "a parent can't open another family's child's development page",
    scenario({
      id: 'DEV-07',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const owner = await provision.parent()
      const child = owner.children[0]!.playerId
      const coach = await assessingCoach(provision, owner)
      await provision.radarAssessment(coach, child, 'OBJECTIVE', { PAC: 70, SHO: 60, PAS: 50 })
      await provision.radarScores(owner, child, (s) => s.PAC?.score != null)
      await provision.seedExposure(child, increasingPac)
      const stranger = await provision.parent()

      const self = await actor(asRole(stranger))
      const portal = await new ParentDevelopmentScreen(self.page, suite).open({ playerId: child })

      // Refused, and none of the child's data reaches the page.
      await expect(portal.error()).toBeVisible()
      await expect(portal.radarCurrent()).toHaveCount(0)
      await expect(portal.radarScore('PAC')).toHaveCount(0)
      await expect(portal.narrative()).toHaveCount(0)
    },
  )
})
