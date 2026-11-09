import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { dateInputValue } from '../../src/data/dates.js'
import { t } from '../../src/i18n.js'
import {
  AdminAppealsScreen,
  AdminBookingsScreen,
  AdminCoachesScreen,
  AdminConfigScreen,
  AdminDisputesScreen,
  AdminDrawer,
  AdminGdprScreen,
  AdminRequiredScreen,
  AdminSafeguardingScreen,
  AdminUsersScreen,
  AdminVideosScreen,
  CoachPublicProfileScreen,
  CoachSafeguardingScreen,
  PacksDashboardScreen,
} from '../../src/screens/index.js'
import { E2E } from '../../src/env.js'

// R · Admin console, part 1: the limited-admin boundary (S-28) in the drawer and on each page, the safeguarding
// page (full admins only, owner decision 2026-11-08), unlocking a login lockout, a config edit taking effect,
// verifying and revoking a safeguarding check, and the GDPR tracker's overdue flag.
//
// "Absent" is asserted on the DOM, not on a disabled state: a limited admin must not be offered the control.

const LIMITED_ONLY_HIDDEN = ['finance', 'gdpr', 'config', 'safeguarding'] as const

test.describe('R · The limited-admin boundary', () => {
  test(
    "a limited admin's drawer hides Finance, GDPR, Config and Safeguarding and says why",
    scenario({
      id: 'ADM-01',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@ltd-admin', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      const ltd = await actor(asRole(await provision.limitedAdmin()))
      await new AdminUsersScreen(ltd.page, suite).open()
      const drawer = new AdminDrawer(ltd.page)
      await expect(drawer.limitedNote()).toHaveText(t('nav.adminLimitedNote'))
      for (const name of AdminDrawer.pages) {
        if ((LIMITED_ONLY_HIDDEN as readonly string[]).includes(name)) {
          await expect(drawer.item(name)).toHaveCount(0)
        } else {
          await expect(drawer.item(name)).toBeVisible()
        }
      }

      // A full admin has every item and no notice.
      const admin = await actor('admin')
      await new AdminUsersScreen(admin.page, suite).open()
      const full = new AdminDrawer(admin.page)
      for (const name of AdminDrawer.pages) await expect(full.item(name)).toBeVisible()
      await expect(full.limitedNote()).toHaveCount(0)
    },
  )

  test(
    'a limited admin is offered no enforcement or money control on coaches, users, disputes, appeals, videos or bookings',
    scenario({
      id: 'ADM-02',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@ltd-admin', '@admin', '@admin-console', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // seeds one of each subject: a coach, a dispute, an appeal, a video and a booking
      // Something to look at on every page.
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const disputed = await provision.completedSession(parent, coach)
      await provision.raiseDispute(parent, disputed)
      await provision.appealStrike(coach, await provision.seedStrike(coach, 'COACH_NO_SHOW'))
      const player = await provision.adultPlayer()
      const videoId = await provision.seedVideo({
        ownerId: player.playerId!,
        title: 'ltd-check.mp4',
      })
      const bookingId = await provision.confirmedBooking(parent, coach, { weekday: 4 })

      const ltd = await actor(asRole(await provision.limitedAdmin()))
      const admin = await actor('admin')

      // Coaches: the profile opens, the enforcement controls are not there (a full admin has them).
      const coaches = await new AdminCoachesScreen(ltd.page, suite).openCoach(coach.coachId!)
      await expect(coaches.enforcementControls()).toHaveCount(0)
      const fullCoaches = await new AdminCoachesScreen(admin.page, suite).openCoach(coach.coachId!)
      await expect(fullCoaches.suspend()).toBeVisible()

      // Users.
      const users = await new AdminUsersScreen(ltd.page, suite).open()
      await users.find(parent.email)
      await users.view(parent.userId).click()
      await expect(users.dialog()).toBeVisible()
      await expect(users.accountControls()).toHaveCount(0)
      const fullUsers = await new AdminUsersScreen(admin.page, suite).open()
      await fullUsers.find(parent.email)
      await fullUsers.view(parent.userId).click()
      await expect(fullUsers.suspend()).toBeVisible()

      // Disputes and appeals.
      const disputes = await new AdminDisputesScreen(ltd.page, suite).open()
      await disputes.views().first().click()
      await expect(disputes.content()).toBeVisible()
      await expect(disputes.decisionControls()).toHaveCount(0)
      const appeals = await new AdminAppealsScreen(ltd.page, suite).open()
      await appeals.views().first().click()
      await expect(appeals.content()).toBeVisible()
      await expect(appeals.decisionControls()).toHaveCount(0)

      // Videos and bookings.
      const videos = await new AdminVideosScreen(ltd.page, suite).open()
      await videos.findVideo(videoId)
      await expect(videos.content()).toBeVisible()
      await expect(videos.accessControls()).toHaveCount(0)
      const fullVideos = await new AdminVideosScreen(admin.page, suite).open()
      await fullVideos.findVideo(videoId)
      await expect(fullVideos.block()).toBeVisible()

      const bookings = await new AdminBookingsScreen(ltd.page, suite).open()
      await bookings.find(bookingId)
      await expect(bookings.result()).toBeVisible()
      await expect(bookings.moneyControls()).toHaveCount(0)
      const fullBookings = await new AdminBookingsScreen(admin.page, suite).open()
      await fullBookings.find(bookingId)
      await expect(fullBookings.moneyControls()).not.toHaveCount(0)
    },
  )

  test(
    'a limited admin opening /admin/safeguarding is sent to the "requires administrator" page',
    scenario({
      id: 'ADM-03',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@ltd-admin', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      // Owner decision 2026-11-08: full admins only, like Finance, GDPR and Config (it used to load into 403s).
      const ltd = await actor(asRole(await provision.limitedAdmin()))
      await ltd.page.goto(suite.routing.toUrl('/admin/safeguarding'))
      const required = await new AdminRequiredScreen(ltd.page, suite).waitReady()
      expect(required.currentRoute()).toBe('/admin/requires-administrator')
      await expect(ltd.page.getByTestId('admin-safeguarding-page')).toHaveCount(0)
    },
  )
})

test.describe('R · Accounts, config and records', () => {
  test(
    'unlocking a user clears a login lockout; a privileged account offers no unlock or suspend',
    scenario({
      id: 'ADM-07',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      // Five wrong passwords lock the (email, IP) pair: then even the right one is refused.
      for (let i = 0; i < 5; i++) {
        expect(await provision.loginStatus(parent.email, 'Wrong-Passw0rd!')).toBe(401)
      }
      expect(await provision.loginStatus(parent.email, parent.password)).toBe(429)

      const admin = await actor('admin')
      const users = await new AdminUsersScreen(admin.page, suite).open()
      await users.find(parent.email)
      await users.view(parent.userId).click()
      await expect(users.failedLogins()).toContainText('5')
      await users.unlock().click()
      await expect(users.actionDialog()).toBeVisible()
      await users.actionSubmit().click()
      await expect(users.actionDialog()).toBeHidden()
      expect(await provision.loginStatus(parent.email, parent.password)).toBe(200)
      await admin.page.keyboard.press('Escape')
      await expect(users.dialog()).toBeHidden()

      // The bootstrap admin is privileged: a banner, and no account actions.
      const adminSession = await provision.login({
        email: E2E.adminEmail,
        password: E2E.adminPassword,
      })
      const adminId = adminSession.userId
      await adminSession.dispose()
      await users.find(E2E.adminEmail)
      await users.view(adminId).click()
      await expect(users.privileged()).toBeVisible()
      await expect(users.accountControls()).toHaveCount(0)
    },
  )

  test(
    'a config edit is checked against its bounds, saved, and takes effect where the limit is used',
    scenario({
      id: 'ADM-12',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@parent', '@admin-console', '@config'],
    }),
    async ({ actor, suite, provision, runtimeConfig }) => {
      test.slow() // provisions a pack holder
      const key = 'pack.pause.maxDays'
      // Registered first so the fixture restores the original value after the UI edit.
      await runtimeConfig.set(key, await provision.configValue(key))

      const admin = await actor('admin')
      const config = await new AdminConfigScreen(admin.page, suite).open()
      await config.search().fill(key)
      await config.editFor(key).click()
      await expect(config.dialog()).toContainText(key)
      await config.value().fill('100000')
      await expect(config.save()).toBeDisabled()
      await config.value().fill('7')
      await config.save().click()
      await expect(config.dialog()).toBeHidden()
      await expect.poll(() => provision.configValue(key)).toBe('7')

      // A parent's pause dialog now names 7 as the maximum.
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await packs.pause(packs.card(coachName)).click()
      await expect(packs.pauseDialog()).toContainText(
        t('booking.packs.pauseDurationHint', { max: 7 }),
      )
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill('8')
      await expect(packs.confirmPause()).toBeDisabled()
    },
  )

  test(
    'verifying a safeguarding request creates a record and the public badge; revoking removes both',
    scenario({
      id: 'ADM-14',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@coach', '@guest', '@admin-console'],
    }),
    async ({ actor, page, suite, provision }) => {
      test.slow() // three personas, two of them across a reload
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const own = await new CoachSafeguardingScreen(self.page, suite).open()
      await own.request().click()
      await expect(own.pending()).toBeVisible()

      try {
        const admin = await actor('admin')
        const queue = await new AdminSafeguardingScreen(admin.page, suite).open()
        await queue.inspect(coach.coachId!).click()
        await expect(queue.actionDialog()).toBeVisible()
        await queue.confirm().click()
        await expect(queue.actionDialog()).toBeHidden()
        await expect(queue.inspect(coach.coachId!)).toHaveCount(0)

        const profile = new CoachPublicProfileScreen(page, suite)
        await profile.open({ coachId: coach.coachId! })
        await expect(profile.safeguardingBadge()).toHaveAttribute(
          'data-testid',
          'safeguarding-badge-on',
        )

        await queue.tab('records').click()
        await queue.revoke(coach.coachId!).click()
        await queue.reason().fill('Certificate withdrawn by the issuing authority.')
        await queue.confirm().click()
        await expect(queue.revoke(coach.coachId!)).toHaveCount(0)

        await page.reload()
        await profile.waitReady()
        await expect(profile.safeguardingBadge()).toHaveText(t('safeguarding.badge.none'))
      } finally {
        await provision.revokeSafeguarding(coach)
      }
    },
  )

  test(
    'the GDPR tracker flags a request left open for more than 30 days as overdue',
    scenario({
      id: 'ADM-15',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@admin', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      // One open request of each kind per user: two parents.
      const stuck = await provision.seedStuckGdprRequest(await provision.parent(), 31)
      const recent = await provision.seedStuckGdprRequest(await provision.parent(), 2)

      const admin = await actor('admin')
      const tracker = await new AdminGdprScreen(admin.page, suite).open()
      await tracker.filterStatus(t('admin.gdpr.statuses.PROCESSING'))
      await expect(tracker.overdue(tracker.row(stuck))).toBeVisible()
      await expect(tracker.row(recent)).toBeVisible()
      await expect(tracker.overdue(tracker.row(recent))).toHaveCount(0)
    },
  )
})
