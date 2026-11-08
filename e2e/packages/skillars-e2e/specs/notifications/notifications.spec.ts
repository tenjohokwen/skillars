import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import {
  CoachRequestsScreen,
  MarketplaceScreen,
  NotificationBell,
  NotificationPreferencesScreen,
  NotificationsScreen,
  ParentBookingsScreen,
  ParentDashboardScreen,
} from '../../src/screens/index.js'

// P · Notifications: the header bell, the /notifications inbox, and the email preferences on /profile.
//
// An inbox row is written in the same transaction that queues its email, for the templates the
// catalogue marks in-app (NotificationCatalog). The bell has no push channel: it refreshes its count
// on load, on every route change and when the tab regains focus. Rows a spec needs in bulk are
// seeded (seedNotifications); the ones that prove the pipeline come from a real booking.

test.describe('P · The bell', () => {
  test(
    "a coach accepting a booking puts an unread item in the parent's bell",
    scenario({
      id: 'NTF-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.confirmedBooking(parent, coach, { accept: false })
      const child = parent.children[0]!

      const family = await actor(asRole(parent))
      await new ParentDashboardScreen(family.page, suite).open()
      const bell = new NotificationBell(family.page)
      await expect(bell.button()).toBeVisible()
      await expect(bell.badge()).toHaveCount(0)

      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      await inbox.accept(inbox.request(child.name)).click()
      await expect(inbox.request(child.name)).toHaveCount(0)

      // The parent's page was never reloaded: the count arrives when the tab regains focus.
      await expect
        .poll(async () => {
          await bell.refocus()
          return bell.badge().count()
        })
        .toBe(1)
      await expect(bell.badge()).toHaveText('1')
      await bell.open()
      await expect(bell.items()).toHaveCount(1)
      await expect(bell.items().first()).toHaveAttribute('data-type', 'BOOKING_CONFIRMED')
      await expect(bell.items().first()).toHaveAttribute('data-unread', 'true')
    },
  )

  test(
    '"Mark all as read" in the bell clears the badge, and it stays cleared after a reload',
    scenario({
      id: 'NTF-02',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.seedNotifications(parent, 3)
      const family = await actor(asRole(parent))
      await new ParentDashboardScreen(family.page, suite).open()
      const bell = new NotificationBell(family.page)
      await expect(bell.badge()).toHaveText('3')

      await bell.open()
      await expect(bell.items()).toHaveCount(3)
      await bell.markAll().click()
      await expect(bell.badge()).toHaveCount(0)
      await expect(bell.markAll()).toHaveCount(0)

      await family.page.reload()
      await new ParentDashboardScreen(family.page, suite).waitReady()
      await expect(bell.button()).toBeVisible()
      await expect(bell.badge()).toHaveCount(0)
      expect(await provision.unreadNotifications(parent)).toBe(0)
    },
  )

  test(
    'an empty inbox says "all caught up", in the bell and on the inbox page',
    scenario({
      id: 'NTF-03',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@player', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const inbox = await new NotificationsScreen(self.page, suite).open()
      await inbox.loaded()
      await expect(inbox.empty()).toHaveText(t('notifications.empty'))
      await expect(inbox.markAll()).toHaveCount(0)
      await expect(inbox.pagination()).toHaveCount(0)

      const bell = new NotificationBell(self.page)
      await expect(bell.badge()).toHaveCount(0)
      await bell.open()
      await expect(bell.empty()).toHaveText(t('notifications.empty'))
      await expect(bell.markAll()).toHaveCount(0)
    },
  )

  test(
    'a guest has no bell',
    scenario({
      id: 'NTF-08',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@notifications'],
    }),
    async ({ page, suite, actor, provision }) => {
      await new MarketplaceScreen(page, suite).open()
      await expect(page.getByTestId('notification-bell')).toHaveCount(0)

      // The same page, logged in, has one: the check above is not passing on a missing header.
      const parent = await provision.parent()
      const family = await actor(asRole(parent))
      await new MarketplaceScreen(family.page, suite).open()
      await expect(new NotificationBell(family.page).button()).toBeVisible()
    },
  )
})

test.describe('P · The inbox page', () => {
  test(
    '/notifications pages 20 at a time and marks the unread ones',
    scenario({
      id: 'NTF-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      // 25 rows, newest first; the 4 newest are read, so page 1 holds 16 unread and page 2 holds 5.
      await provision.seedNotifications(parent, 25, { read: 4 })
      const family = await actor(asRole(parent))
      const inbox = await new NotificationsScreen(family.page, suite).open()
      await inbox.loaded()
      await expect(inbox.items()).toHaveCount(20)
      await expect(inbox.unread()).toHaveCount(16)
      await expect(inbox.unreadDots()).toHaveCount(16)
      await expect(inbox.items().first()).toHaveAttribute('data-unread', 'false')

      await inbox.pageButton(2).click()
      await expect(inbox.items()).toHaveCount(5)
      await expect(inbox.unreadDots()).toHaveCount(5)
      await expect(new NotificationBell(family.page).badge()).toHaveText('21')
    },
  )

  test(
    'opening an item marks it read and follows its link to a hash route',
    scenario({
      id: 'NTF-05',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.confirmedBooking(parent, coach)
      const family = await actor(asRole(parent))
      const inbox = await new NotificationsScreen(family.page, suite).open()
      await inbox.loaded()
      const confirmed = inbox.item('BOOKING_CONFIRMED')
      await expect(confirmed).toHaveAttribute('data-unread', 'true')

      await confirmed.click()
      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      expect(bookings.currentRoute()).toBe('/parent/bookings')
      expect(new URL(family.page.url()).hash).toBe('#/parent/bookings')
      expect(await provision.unreadNotifications(parent)).toBe(0)
      await expect(new NotificationBell(family.page).badge()).toHaveCount(0)
    },
  )
})

test.describe('P · Email preferences', () => {
  test(
    'reminder and development toggles persist, are disabled while saving, and transactional email is explained',
    scenario({
      id: 'NTF-06',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@notifications'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      const family = await actor(asRole(parent))
      const prefs = await new NotificationPreferencesScreen(family.page, suite).open()
      await expect(prefs.transactionalNote()).toHaveText(
        t('notifications.preferences.transactionalNote'),
      )
      await expect(prefs.toggle('reminder')).toBeChecked()
      await expect(prefs.toggle('development')).toBeChecked()

      // Hold the save: the toggle is disabled until the server answers.
      let release: () => void = () => {}
      const held = new Promise<void>((resolve) => (release = resolve))
      await family.page.route('**/api/notifications/preferences/REMINDER', async (route) => {
        await held
        await route.continue()
      })
      await prefs.toggle('reminder').click()
      await expect(prefs.toggle('reminder')).toBeDisabled()
      await expect(prefs.toggle('development')).toBeEnabled()
      release()
      await expect(prefs.notification(t('notifications.preferences.saved'))).toBeVisible()
      await expect(prefs.toggle('reminder')).toBeEnabled()
      await expect(prefs.toggle('reminder')).not.toBeChecked()
      await family.page.unroute('**/api/notifications/preferences/REMINDER')

      await prefs.toggle('development').click()
      await expect(prefs.toggle('development')).not.toBeChecked()
      await expect(prefs.toggle('development')).toBeEnabled()

      await family.page.reload()
      await prefs.waitReady()
      await expect(prefs.toggle('reminder')).not.toBeChecked()
      await expect(prefs.toggle('development')).not.toBeChecked()
    },
  )

  test(
    'with reminders off, the reminder email is not sent, but booking email still is',
    scenario({
      id: 'NTF-07',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@notifications', '@slow'],
    }),
    async ({ actor, suite, provision, mail }) => {
      test.slow() // the reminder job holds its lock for two minutes after a scheduled run
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const family = await actor(asRole(parent))
      const prefs = await new NotificationPreferencesScreen(family.page, suite).open()
      await prefs.toggle('reminder').click()
      await expect(prefs.notification(t('notifications.preferences.saved'))).toBeVisible()

      // Transactional: the confirmation still arrives.
      const since = new Date()
      const bookingId = await provision.confirmedBooking(parent, coach)
      await mail.waitForMessage({
        to: parent.email,
        subjectMatches: /confirmed/i,
        since,
        timeoutMs: 60_000,
      })

      // Due for its day-before reminder, which goes to both sides of the booking: the coach's
      // arrives, and the parent's (queued in the same transaction) is dropped at send time.
      await provision.startBookingIn(bookingId, 20)
      await provision.runJob('booking-reminders')
      await expect.poll(() => provision.bookingStatus(bookingId)).toBe('UPCOMING')
      await mail.waitForMessage({
        to: coach.email,
        subjectMatches: /reminder/i,
        since,
        timeoutMs: 60_000,
      })
      // The parent's was processed (recorded as handled), and no mailbox received it.
      await expect
        .poll(() => provision.emailStatus(parent.email, 'BOOKING_REMINDER'), { timeout: 60_000 })
        .toBe('SENT')
      await expect(
        mail.waitForMessage({
          to: parent.email,
          subjectMatches: /reminder/i,
          since,
          timeoutMs: 3_000,
        }),
      ).rejects.toThrow()
    },
  )
})
