import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { packOf } from '../../src/data/packs.js'
import { t } from '../../src/i18n.js'
import { PacksDashboardScreen } from '../../src/screens/index.js'

// G · An expired pack's unused sessions are forfeited, not refunded, and the paying family is emailed.
// The expiry is seeded in the past (provision.setPackState) and the hourly SessionPackForfeitureScheduler
// is run on request (provision.runJob) rather than waited for. Not @destructive although the job is global: it
// touches only packs already past expiry, and only stamps them and mails their families, which no other
// spec reads (an expired pack's badge reads Expired either way).

test.describe('G · Pack forfeiture', () => {
  test(
    "an expired pack's unused sessions are forfeited without a refund, and the family is emailed",
    scenario({
      id: 'MNY-12',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, mail, provision }) => {
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      await provision.setPackState(parent, coach, { expiresInDays: -1 })
      const ledger = await provision.walletLedger(parent)
      const since = new Date(Date.now() - 2_000)

      await provision.runJob('pack-forfeiture')

      // The family is told how many sessions were lost, and with whom.
      const message = await mail.waitForMessage({
        to: parent.email,
        subjectMatches: /session pack expired/i,
        since,
        timeoutMs: 30_000,
      })
      expect(message.text).toContain(coachName)
      expect(message.text).toContain(String(sessions))

      // Not refunded: no wallet entry, and the sessions stay on the pack, unusable.
      expect(await provision.walletLedger(parent)).toEqual(ledger)
      const pack = await packOf(provision, parent, coach)
      expect(pack.status).toBe('EXPIRED')
      expect(pack.remainingSessions).toBe(sessions)

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await expect(packs.status(card)).toHaveText(t('booking.packs.expiredLabel'))
      await expect(packs.pause(card)).toHaveCount(0)

      // A second run finds nothing left to forfeit: still no refund.
      await provision.runJob('pack-forfeiture')
      expect(await provision.walletLedger(parent)).toEqual(ledger)
    },
  )
})
