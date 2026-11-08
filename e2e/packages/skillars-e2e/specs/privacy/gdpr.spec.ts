import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Account, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import {
  ChildHubPrivacyScreen,
  LoginScreen,
  ParentDashboardScreen,
  PrivacyScreen,
  UnsubscribeScreen,
} from '../../src/screens/index.js'

// Q · Privacy, part 1: self-service export and erasure (FR-TSC-007) and the email unsubscribe page.
//
// An export is built right after its request commits, in a few seconds, into a ZIP in object storage
// behind a signed link (the browser reaches the stack's storage directly). States a real request
// passes through too quickly to see, or never reaches on a healthy stack (processing, failed,
// expired), are seeded (seedExportRequest / setExportRequest). Erasure of a user's own account runs
// synchronously and signs them out.
//
// Erasures here act on accounts each test creates for itself, so they run in the parallel lane: the
// plan's @destructive tag (a serial run) is for actions with effects beyond their own data.

/** The bytes behind a download button: a ZIP starts with "PK". */
async function zipBehind(page: import('@playwright/test').Page, href: string | null) {
  expect(href).toBeTruthy()
  const response = await page.request.get(href!)
  expect(response.status()).toBe(200)
  const body = await response.body()
  return body.subarray(0, 2).toString('latin1')
}

/** Whether the user still gets reminder emails, as their own preferences API reports it. */
async function reminderEmails(provision: Provisioner, user: Account): Promise<boolean> {
  const session = await provision.login(user)
  try {
    const prefs = await session.api.json<{ category: string; emailEnabled: boolean }[]>(
      'GET',
      '/notifications/preferences',
    )
    const reminder = prefs.find((p) => p.category === 'REMINDER')
    if (!reminder) throw new Error('No REMINDER preference')
    return reminder.emailEnabled
  } finally {
    await session.dispose()
  }
}

test.describe('Q · Export and erasure', () => {
  test(
    'an export shows processing (button disabled), a failure, then a ready download, then expiry',
    scenario({
      id: 'PRV-01',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@privacy', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const player = await provision.adultPlayer()
      const earlier = await provision.seedExportRequest(player, 'PROCESSING')
      const self = await actor(asRole(player))
      const profile = await new PrivacyScreen(self.page, suite).open()
      const own = profile.own()

      // In flight: processing, and no second request.
      await expect(own.exportStatus()).toContainText(t('privacy.exportProcessing'))
      await expect(own.exportButton()).toBeDisabled()

      // It fails; the page's own poll picks that up and offers the button again.
      await provision.setExportRequest(earlier, { status: 'FAILED' })
      await expect(own.failed()).toHaveText(t('privacy.exportFailed'), { timeout: 15_000 })
      await expect(own.exportButton()).toBeEnabled()

      // A real request: built, then downloadable as a ZIP.
      await own.exportButton().click()
      await expect(profile.notification(t('privacy.exportRequested'))).toBeVisible()
      await expect(own.download()).toBeVisible({ timeout: 60_000 })
      expect(await zipBehind(self.page, await own.download().getAttribute('href'))).toBe('PK')

      // Once its link expires, the page says so instead of offering a dead link.
      const ready = await provision.latestExport(player)
      expect(ready?.status).toBe('COMPLETED')
      await provision.setExportRequest(ready!.id, { expired: true })
      await self.page.reload()
      await profile.waitReady()
      await expect(own.expired()).toHaveText(t('privacy.exportExpired'))
      await expect(own.download()).toHaveCount(0)
    },
  )

  test(
    'deleting an account needs the word typed, then signs the user out for good',
    scenario({
      id: 'PRV-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@player', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const profile = await new PrivacyScreen(self.page, suite).open()
      const own = profile.own()
      await own.openErasure().click()
      await expect(own.erasureDialog()).toBeVisible()
      const word = t('privacy.confirmWord')

      await expect(own.submitErasure()).toBeDisabled()
      await own.confirmInput().fill(word.slice(0, -1))
      await expect(own.submitErasure()).toBeDisabled()
      await own.confirmInput().fill(word)
      await expect(own.submitErasure()).toBeEnabled()
      await own.submitErasure().click()

      const login = await new LoginScreen(self.page, suite).waitReady({ timeoutMs: 30_000 })
      await login.logIn(player.email, player.password)
      await expect(login.error()).toBeVisible()
      expect(login.currentRoute()).toBe('/login')
    },
  )

  test(
    'a coach with a confirmed session cannot delete their account, and is told why',
    scenario({
      id: 'PRV-03',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // provisions a published coach and a paid booking before the browser part
      // Owner decision 2026-11-08: any live booking blocks it, not only an unanswered request.
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.confirmedBooking(parent, coach)
      const self = await actor(asRole(coach))
      const profile = await new PrivacyScreen(self.page, suite).open()
      const own = profile.own()
      await own.openErasure().click()
      await own.confirmInput().fill(t('privacy.confirmWord'))
      await own.submitErasure().click()

      // The erasure request runs synchronously; give it time under load.
      await expect(own.erasureError()).toHaveText(t('privacy.errors.activeBookings'), {
        timeout: 30_000,
      })
      await expect(own.erasureDialog()).toBeVisible()
      // Still signed in, with an account: the profile reloads.
      await self.page.reload()
      await profile.waitReady()
    },
  )

  test(
    "a parent exports a child's data from the profile, and deletes it from the child hub",
    scenario({
      id: 'PRV-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@privacy', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const parent = await provision.parent({ childAges: [12, 9] })
      const [kept, erased] = parent.children as [
        (typeof parent.children)[0],
        (typeof parent.children)[0],
      ]
      const family = await actor(asRole(parent))

      // Export, from the child's privacy dialog on /profile.
      const profile = await new PrivacyScreen(family.page, suite).open()
      await profile.childPrivacy(erased.playerId).click()
      await expect(profile.childDialog()).toContainText(erased.name)
      const child = profile.child()
      await child.exportButton().click()
      await expect(child.download()).toBeVisible({ timeout: 60_000 })
      expect(await zipBehind(family.page, await child.download().getAttribute('href'))).toBe('PK')
      expect((await provision.latestExport(parent, erased.playerId))?.status).toBe('COMPLETED')
      // The parent's own export is a separate thing, untouched.
      expect(await provision.latestExport(parent)).toBeUndefined()

      // Erasure, from the child hub: confirm, and the parent is sent back to a dashboard without them.
      const hub = await new ChildHubPrivacyScreen(family.page, suite).open({
        playerId: erased.playerId,
      })
      const privacy = hub.privacy()
      await privacy.openErasure().click()
      await expect(privacy.erasureDialog()).toContainText(erased.name)
      await privacy.confirmInput().fill(t('privacy.confirmWord'))
      await privacy.submitErasure().click()
      await expect(hub.notification(t('privacy.erasureChildDone'))).toBeVisible({ timeout: 30_000 })
      const dashboard = await new ParentDashboardScreen(family.page, suite).waitReady()
      await expect(dashboard.childCards()).toHaveCount(1)
      await expect(dashboard.childCards()).toContainText(kept.name)
    },
  )
})

test.describe('Q · Unsubscribe', () => {
  test(
    '/unsubscribe without a token says the link is incomplete',
    scenario({
      id: 'PRV-05',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@privacy'],
    }),
    async ({ page, suite }) => {
      const unsubscribe = await new UnsubscribeScreen(page, suite).open()
      await expect(unsubscribe.missing()).toHaveText(t('notifications.unsubscribe.missingToken'))
      await expect(unsubscribe.confirm()).toHaveCount(0)
    },
  )

  test(
    "a reminder email's unsubscribe link asks for a click, then turns reminders off",
    scenario({
      id: 'PRV-06',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@guest', '@parent', '@privacy', '@slow'],
    }),
    async ({ page, suite, provision, mail }) => {
      test.slow() // the reminder job holds its lock for two minutes after a scheduled run
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const since = new Date()
      const bookingId = await provision.confirmedBooking(parent, coach)
      await provision.startBookingIn(bookingId, 20)
      await provision.runJob('booking-reminders')
      const reminder = await mail.waitForMessage({
        to: parent.email,
        subjectMatches: /reminder/i,
        since,
        timeoutMs: 60_000,
      })
      const link = reminder.links().find((href) => href.includes('unsubscribe?token='))
      expect(link).toBeTruthy()

      // Logged out. Opening the page changes nothing; the click does.
      await page.goto(link!)
      const unsubscribe = await new UnsubscribeScreen(page, suite).waitReady()
      await expect(unsubscribe.confirm()).toBeVisible()
      expect(await reminderEmails(provision, parent)).toBe(true)

      await unsubscribe.confirm().click()
      await expect(unsubscribe.done()).toHaveText(
        t('notifications.unsubscribe.success', {
          category: t('notifications.preferences.reminder.label'),
        }),
      )
      expect(await reminderEmails(provision, parent)).toBe(false)
    },
  )

  test(
    'a tampered unsubscribe token shows the error banner',
    scenario({
      id: 'PRV-07',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@privacy'],
    }),
    async ({ page, suite, provision }) => {
      // A real token for a real user, with one character of its signature changed.
      const parent = await provision.parent()
      const token = provision.unsubscribeToken(parent, 'REMINDER')
      const at = token.length - 2
      const flipped = token.slice(0, at) + (token[at] === 'A' ? 'B' : 'A') + token.slice(at + 1)
      const visit = async (value: string) => {
        await page.goto(suite.routing.toUrl(`/unsubscribe?token=${encodeURIComponent(value)}`))
        return new UnsubscribeScreen(page, suite).waitReady()
      }

      const tampered = await visit(flipped)
      await tampered.confirm().click()
      await expect(tampered.error()).toHaveText(t('notifications.unsubscribe.invalid'))
      await expect(tampered.done()).toHaveCount(0)
      expect(await reminderEmails(provision, parent)).toBe(true)

      // The same token unaltered is accepted: only the changed character made it fail.
      const genuine = await visit(token)
      await genuine.confirm().click()
      await expect(genuine.done()).toBeVisible()
      expect(await reminderEmails(provision, parent)).toBe(false)
    },
  )
})
