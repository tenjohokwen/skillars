import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { DAY_MS, dateInputValue } from '../../src/data/dates.js'
import { packOf } from '../../src/data/packs.js'
import type { ParentAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { PacksDashboardScreen } from '../../src/screens/index.js'
import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'

// G · The longest pack pause is runtime config (pack.pause.maxDays, default 90). The dialog used to
// hard-code 90 and "1–90 days", and the request DTO capped it at 90 too, so a lower limit was refused
// with a generic error and a higher one could never take effect. Each pack now carries the live limit.

/** Open the pause dialog on the parent's pack with this coach. */
async function openPause(page: Page, suite: SuiteEnv, parent: ParentAccount, coachName: string) {
  const packs = await new PacksDashboardScreen(page, suite).open({
    playerId: parent.children[0]!.playerId,
  })
  await packs.pause(packs.card(coachName)).click()
  await expect(packs.pauseDialog()).toBeVisible()
  return packs
}

/** A coach with a fresh name, and a parent holding one of their packs. */
async function packHolder(provision: Provisioner) {
  const coachName = `${provision.uniqueCity()} Coaching`
  const coach = await provision.coach({ displayName: coachName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  await provision.buyPack(parent, coach)
  return { coach, coachName, parent }
}

test.describe('G · Pause limit', () => {
  test(
    'a pause longer than the configured maximum cannot be sent, and the dialog says what the maximum is',
    scenario({
      id: 'MNY-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const max = Number(await provision.configValue('pack.pause.maxDays'))
      const { coachName, parent } = await packHolder(provision)

      const family = await actor(asRole(parent))
      const packs = await openPause(family.page, suite, parent, coachName)
      await expect(packs.pauseDialog()).toContainText(t('booking.packs.pauseDurationHint', { max }))
      await packs.pauseStart().fill(dateInputValue(2))

      await packs.pauseDays().fill(String(max + 1))
      await expect(packs.pauseDialog()).toContainText(
        t('booking.packs.pauseDurationInvalid', { max }),
      )
      await expect(packs.confirmPause()).toBeDisabled()

      await packs.pauseDays().fill(String(max))
      await expect(packs.confirmPause()).toBeEnabled()
    },
  )

  test(
    'the pause limit follows runtime config both ways: lowered to 30 and raised past the old 90',
    scenario({
      id: 'MNY-05',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@money', '@config'],
    }),
    async ({ actor, suite, provision, runtimeConfig }) => {
      const { coach, coachName, parent } = await packHolder(provision)
      const family = await actor(asRole(parent))

      await runtimeConfig.set('pack.pause.maxDays', 30)
      const packs = await openPause(family.page, suite, parent, coachName)
      await expect(packs.pauseDialog()).toContainText(
        t('booking.packs.pauseDurationHint', { max: 30 }),
      )
      await packs.pauseDays().fill('31')
      await expect(packs.confirmPause()).toBeDisabled()

      // Above the old hard-coded 90: the dialog allows it and the server applies it. Reload, since the
      // pack list (and the limit it carries) is read once per visit.
      await runtimeConfig.set('pack.pause.maxDays', 120)
      await family.page.reload()
      await packs.waitReady()
      await packs.pause(packs.card(coachName)).click()
      await expect(packs.pauseDialog()).toBeVisible()
      await expect(packs.pauseDialog()).toContainText(
        t('booking.packs.pauseDurationHint', { max: 120 }),
      )
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill('100')
      await packs.confirmPause().click()
      await expect(packs.pauseDialog()).toBeHidden()

      const pack = await packOf(provision, parent, coach)
      expect(Date.parse(pack.pausedUntil!) - Date.parse(pack.pausedFrom!)).toBe(100 * DAY_MS)
    },
  )
})
