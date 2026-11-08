import type { Locator, Page } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/**
 * The header bell, on every page a logged-in user sees (MainLayout). Not a screen: it has no route
 * of its own. Its dropdown is a Quasar menu, teleported to the end of <body>.
 */
export class NotificationBell {
  constructor(private readonly page: Page) {}

  button = () => this.page.getByTestId('notification-bell')
  badge = () => this.page.getByTestId('notification-badge')
  menu = () => this.page.getByTestId('bell-menu')
  items = () => this.menu().getByTestId('notification-item')
  empty = () => this.page.getByTestId('bell-empty')
  markAll = () => this.page.getByTestId('bell-mark-all')
  viewAll = () => this.page.getByTestId('bell-view-all')

  /** Opens the dropdown, which loads the newest items. */
  async open(): Promise<void> {
    await this.button().click()
    await this.menu().waitFor({ state: 'visible' })
  }

  /** What the app does when its tab regains focus: refresh the unread count. */
  async refocus(): Promise<void> {
    await this.page.evaluate(() => window.dispatchEvent(new Event('focus')))
  }
}

/** /notifications: the full inbox, 20 a page, newest first. */
export class NotificationsScreen extends Screen {
  readonly route = R.notifications
  readonly readyTestId = 'notifications-page'
  items = () => this.page.getByTestId('page-list').getByTestId('notification-item')
  unread = () => this.page.locator('[data-testid="page-list"] [data-unread="true"]')
  /** The newest item of a type (an EmailTemplate name, e.g. BOOKING_CONFIRMED). */
  item = (type: string) =>
    this.page.getByTestId('page-list').locator(`[data-type="${type}"]`).first()
  unreadDots = () => this.page.getByTestId('page-list').getByTestId('notification-unread-dot')
  empty = () => this.tid('page-empty')
  loading = () => this.tid('page-loading')
  markAll = () => this.tid('page-mark-all')
  pagination = () => this.tid('page-pagination')
  /** A page button in the pager, by its number. */
  pageButton = (n: number): Locator =>
    this.pagination().getByRole('button', { name: String(n), exact: true })

  /** Waits for the list (or the empty state), not just the page frame. */
  async loaded(): Promise<this> {
    await this.page
      .locator('[data-testid="page-list"], [data-testid="page-empty"]')
      .first()
      .waitFor({ state: 'visible' })
    return this
  }
}

/** /profile, the email-preferences section every role has. */
export class NotificationPreferencesScreen extends Screen {
  readonly route = R.profile
  readonly readyTestId = 'notification-preferences'
  section = () => this.tid('notification-preferences')
  /** The toggle for an opt-out-able category: `reminder` or `development`. */
  toggle = (category: 'reminder' | 'development') => this.tid(`pref-toggle-${category}`)
  transactionalNote = () => this.tid('pref-transactional-note')
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
}
