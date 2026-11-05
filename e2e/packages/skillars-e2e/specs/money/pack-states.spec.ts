import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { dateInputValue, uiDate } from '../../src/data/dates.js'
import { packOf } from '../../src/data/packs.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { PacksDashboardScreen } from '../../src/screens/index.js'

// G · Pack states the family sees. The server computes the status (precedence REVOKED > EXHAUSTED >
// FROZEN > PAUSED > EXPIRED > ACTIVE); states that need time or another actor are seeded by SQL
// (provision.setPackState), the way each would be written.

type PackState = Parameters<Provisioner['setPackState']>[2]

/** A family holding one pack with a fresh coach, in `state`. */
async function familyWithPack(provision: Provisioner, state: PackState = {}) {
  const coachName = `${provision.uniqueCity()} Coaching`
  const coach = await provision.coach({ displayName: coachName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  await provision.buyPack(parent, coach)
  await provision.setPackState(parent, coach, state)
  return { coach, coachName, parent }
}

const BADGES: { name: string; state: PackState; badge: string }[] = [
  { name: 'Paused', state: { pause: { fromDays: -1, untilDays: 10 } }, badge: 'pausedStatus' },
  { name: 'Frozen', state: { frozen: true }, badge: 'frozenStatus' },
  // Withdrawn outranks Frozen: a lost chargeback both froze and then revoked the pack.
  {
    name: 'Withdrawn',
    state: { frozen: true, revokedReason: 'CHARGEBACK_LOST', remainingSessions: 0 },
    badge: 'revokedStatus',
  },
  { name: 'Expired', state: { expiresInDays: -1 }, badge: 'expiredLabel' },
  // Exhausted outranks Frozen.
  { name: 'Exhausted', state: { remainingSessions: 0, frozen: true }, badge: 'exhaustedLabel' },
]

test.describe('G · Pack states', () => {
  test(
    'a pause starting in the past is refused',
    scenario({
      id: 'MNY-06',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, coachName, parent } = await familyWithPack(provision)
      const before = await packOf(provision, parent, coach)

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await packs.pause(card).click()
      // Today has already started, so it is in the past too; the picker's minimum is tomorrow.
      await packs.pauseStart().fill(dateInputValue(0))
      await packs.pauseDays().fill('7')
      await packs.confirmPause().click()
      await expect(family.page.getByText(t('booking.packs.pauseStartInPast'))).toBeVisible()
      await expect(packs.pauseDialog()).toBeVisible()

      const after = await packOf(provision, parent, coach)
      expect(after.pausedUntil).toBeNull()
      expect(after.expiresAt).toBe(before.expiresAt)
    },
  )

  for (const { name, state, badge } of BADGES) {
    test(
      `the status badge reads ${name}`,
      scenario({
        id: 'MNY-18',
        priority: 'P1',
        type: 'happy',
        tags: ['@regression', '@parent', '@money'],
      }),
      async ({ actor, suite, provision }) => {
        const { coachName, parent } = await familyWithPack(provision, state)
        const family = await actor(asRole(parent))
        const packs = await new PacksDashboardScreen(family.page, suite).open({
          playerId: parent.children[0]!.playerId,
        })
        await expect(packs.status(packs.card(coachName))).toHaveText(t(`booking.packs.${badge}`))
      },
    )
  }

  test(
    'a pause scheduled for later leaves the pack Active, with a "Pause scheduled" note',
    scenario({
      id: 'MNY-19',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, coachName, parent } = await familyWithPack(provision, {
        pause: { fromDays: 5, untilDays: 20 },
      })
      const pack = await packOf(provision, parent, coach)

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await expect(packs.status(card)).toHaveText(t('booking.packs.activeStatus'))
      await expect(packs.pauseScheduled(card)).toHaveText(
        t('booking.packs.pauseScheduledLabel', {
          from: uiDate(pack.pausedFrom!),
          until: uiDate(pack.pausedUntil!),
        }),
      )
      // Not the "paused until" note of a pause in progress, and the one pause is spent.
      await expect(packs.pausedUntil(card)).toHaveCount(0)
      await expect(packs.pause(card)).toHaveCount(0)
      await expect(packs.alreadyPaused(card)).toHaveText(t('booking.packs.alreadyPausedLabel'))
    },
  )

  test(
    'frozen and withdrawn packs offer no pause, and a pack whose pause is over says it used it',
    scenario({
      id: 'MNY-20',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      // Three coaches, one family: one pack in each state.
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const packsWith = async (state: PackState) => {
        const coachName = `${provision.uniqueCity()} Coaching`
        const coach = await provision.coach({ displayName: coachName })
        await provision.buyPack(parent, coach)
        await provision.setPackState(parent, coach, state)
        return coachName
      }
      const frozen = await packsWith({ frozen: true })
      const withdrawn = await packsWith({ revokedReason: 'COACH_SUSPENDED', remainingSessions: 0 })
      const usedPause = await packsWith({ pause: { fromDays: -20, untilDays: -10 } })

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await expect(packs.cards()).toHaveCount(3)

      const frozenCard = packs.card(frozen)
      await expect(packs.pause(frozenCard)).toHaveCount(0)
      await expect(packs.frozenNotice(frozenCard)).toHaveText(t('booking.packs.frozenNotice'))

      const withdrawnCard = packs.card(withdrawn)
      await expect(packs.pause(withdrawnCard)).toHaveCount(0)
      await expect(packs.revokedReason(withdrawnCard)).toHaveText(
        t('booking.packs.revokedCoachSuspended'),
      )
      await expect(packs.expiry(withdrawnCard)).toHaveCount(0)

      const usedCard = packs.card(usedPause)
      await expect(packs.status(usedCard)).toHaveText(t('booking.packs.activeStatus'))
      await expect(packs.pause(usedCard)).toHaveCount(0)
      await expect(packs.alreadyPaused(usedCard)).toHaveText(t('booking.packs.alreadyPausedLabel'))
    },
  )
})
