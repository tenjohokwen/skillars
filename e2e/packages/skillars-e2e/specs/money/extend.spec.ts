import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { DAY_MS, uiShortDate } from '../../src/data/dates.js'
import { packOf } from '../../src/data/packs.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { CoachPackTiersScreen } from '../../src/screens/index.js'

// G · A coach extends a pack they sold: once, by 30 days, and only inside the 14 days before it
// expires (SessionPackPaymentService.EXTENSION_WINDOW). Expiry is seeded relative to now.

const EXTENSION_DAYS = 30

/** A coach who has sold one pack, its expiry moved to `expiresInDays` from now. */
async function soldPack(provision: Provisioner, state: Parameters<Provisioner['setPackState']>[2]) {
  const coach = await provision.coach({ displayName: `${provision.uniqueCity()} Coaching` })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  await provision.buyPack(parent, coach)
  await provision.setPackState(parent, coach, state)
  return { coach, parent, player: parent.children[0]!.name }
}

/** POST the extend endpoint as the coach and return the refusal's status and body. */
async function extendAsCoach(provision: Provisioner, coach: CoachAccount, purchaseId: string) {
  const session = await provision.login(coach)
  try {
    const response = await session.api.post(`/payment/session-packs/${purchaseId}/extend`, {
      expectStatus: (status) => status >= 400 && status < 500,
    })
    return { status: response.status(), body: await response.text() }
  } finally {
    await session.dispose()
  }
}

test.describe('G · Pack extension', () => {
  test(
    'a coach extends a pack by 30 days inside the extension window',
    scenario({
      id: 'MNY-09',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent, player } = await soldPack(provision, { expiresInDays: 10 })
      const before = await packOf(provision, parent, coach)

      const coachSide = await actor(asRole(coach))
      const tiers = await new CoachPackTiersScreen(coachSide.page, suite).open()
      const pack = tiers.soldPack(player)
      await tiers.extend(pack).click()
      await expect(tiers.extend(pack)).toHaveCount(0)

      const after = await packOf(provision, parent, coach)
      expect(Date.parse(after.expiresAt) - Date.parse(before.expiresAt)).toBe(
        EXTENSION_DAYS * DAY_MS,
      )
      await expect(tiers.expiry(pack)).toHaveText(
        t('payment.myTiers.expiresOn', { date: uiShortDate(after.expiresAt) }),
      )
    },
  )

  test(
    'an extension outside the 14-day window is refused',
    scenario({
      id: 'MNY-10',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent, player } = await soldPack(provision, { expiresInDays: 30 })
      const before = await packOf(provision, parent, coach)

      const coachSide = await actor(asRole(coach))
      const tiers = await new CoachPackTiersScreen(coachSide.page, suite).open()
      const pack = tiers.soldPack(player)
      await expect(pack).toHaveCount(1)
      await expect(tiers.extend(pack)).toHaveCount(0)
      await expect(tiers.expiringSoon(pack)).toHaveCount(0)

      const refused = await extendAsCoach(provision, coach, before.purchaseId)
      expect(refused.body).toContain('payment.packExtensionNotEligible')
      expect((await packOf(provision, parent, coach)).expiresAt).toBe(before.expiresAt)
    },
  )

  test(
    'a second extension is refused',
    scenario({
      id: 'MNY-11',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent, player } = await soldPack(provision, {
        expiresInDays: 10,
        extended: true,
      })
      const before = await packOf(provision, parent, coach)

      const coachSide = await actor(asRole(coach))
      const tiers = await new CoachPackTiersScreen(coachSide.page, suite).open()
      const pack = tiers.soldPack(player)
      await expect(tiers.alreadyExtended(pack)).toHaveText(t('payment.myTiers.alreadyExtended'))
      await expect(tiers.extend(pack)).toHaveCount(0)

      const refused = await extendAsCoach(provision, coach, before.purchaseId)
      expect(refused.status).toBe(409)
      expect(refused.body).toContain('payment.packAlreadyExtended')
      expect((await packOf(provision, parent, coach)).expiresAt).toBe(before.expiresAt)
    },
  )

  test(
    'a coach sees a sold pack expiring soon, extends it once, and then sees it already extended',
    scenario({
      id: 'MNY-24',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, player } = await soldPack(provision, { expiresInDays: 5 })

      const coachSide = await actor(asRole(coach))
      const tiers = await new CoachPackTiersScreen(coachSide.page, suite).open()
      const pack = tiers.soldPack(player)
      await expect(tiers.expiringSoon(pack)).toHaveText(t('payment.myTiers.expiringSoon'))
      await expect(tiers.alreadyExtended(pack)).toHaveCount(0)
      await tiers.extend(pack).click()

      // 35 days out now: outside the window, so no longer "expiring soon", and marked extended.
      await expect(tiers.alreadyExtended(pack)).toHaveText(t('payment.myTiers.alreadyExtended'))
      await expect(tiers.extend(pack)).toHaveCount(0)
      await expect(tiers.expiringSoon(pack)).toHaveCount(0)
      await expect(tiers.extendError()).toHaveCount(0)
    },
  )
})
