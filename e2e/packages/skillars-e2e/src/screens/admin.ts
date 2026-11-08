import type { Locator, Page } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/** The admin section of the header drawer (MainLayout), one item per admin page. */
export class AdminDrawer {
  constructor(private readonly page: Page) {}

  static readonly pages = [
    'health-dashboard',
    'disputes',
    'moderation',
    'coaches',
    'safeguarding',
    'users',
    'appeals',
    'videos',
    'bookings',
    'finance',
    'gdpr',
    'config',
  ] as const

  item = (name: (typeof AdminDrawer.pages)[number]) => this.page.getByTestId(`nav-admin-${name}`)
  limitedNote = () => this.page.getByTestId('nav-limited-admin-note')
}

/** Base for the admin pages: a root id per page, and Quasar's notifications. */
abstract class AdminScreen extends Screen {
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })

  /**
   * Page forward through the list's paginator until `target` is on screen. The admin queues list oldest
   * first, so on a long-lived stack a row a spec just created can sit several pages in.
   */
  async pageUntil(target: Locator, maxPages = 40, pagerId = 'admin-pagination'): Promise<void> {
    const pager = this.page.getByTestId(pagerId)
    // The list loads after the page: wait for the row itself, or for a paginator (only shown past one page).
    await target
      .first()
      .or(pager)
      .first()
      .waitFor({ state: 'attached', timeout: 15_000 })
      .catch(() => undefined)
    for (let n = 2; n <= maxPages; n++) {
      if ((await target.count()) > 0) return
      const next = pager.getByRole('button', { name: String(n), exact: true })
      if ((await next.count()) === 0) return
      await next.click()
      await target
        .first()
        .waitFor({ state: 'attached', timeout: 1_500 })
        .catch(() => undefined)
    }
  }
  /** Absent from the DOM (not merely disabled): the S-28 boundary. */
  protected absent = (...ids: string[]): Locator =>
    this.page.locator(ids.map((id) => `[data-testid="${id}"]`).join(', '))
}

/** /admin/coaches: the coach list and its enforcement dialog. */
export class AdminCoachesScreen extends AdminScreen {
  readonly route = R.adminCoaches
  readonly readyTestId = 'admin-coaches-page'
  views = () => this.tid('coach-row-view-btn')
  view = (coachId: string) =>
    this.page.locator(`[data-testid="coach-row-view-btn"][data-coach-id="${coachId}"]`)
  dialog = () => this.page.getByTestId('coach-profile-dialog')
  content = () => this.page.getByTestId('coach-profile-content')

  /** Deep link the moderation alerts use: /admin/coaches?coachId=… opens that coach's profile. */
  async openCoach(coachId: string): Promise<this> {
    await this.page.goto(this.context.routing.toUrl(`/admin/coaches?coachId=${coachId}`))
    await this.page.reload()
    await this.waitReady()
    await this.content().waitFor({ state: 'visible' })
    return this
  }
  suspend = () => this.page.getByTestId('coach-suspend-open-btn')
  actionDialog = () => this.page.getByTestId('coach-action-dialog')
  actionReason = () => this.page.getByTestId('coach-action-reason-input')
  cancelUpcoming = () => this.page.getByTestId('coach-cancel-upcoming-checkbox')
  actionSubmit = () => this.page.getByTestId('coach-action-submit-btn')
  /** Every enforcement control the dialog can hold. */
  enforcementControls = () =>
    this.absent(
      'coach-suspend-open-btn',
      'coach-reinstate-open-btn',
      'coach-strike-open-btn',
      'coach-tier-select',
      'coach-tier-save-btn',
      'coach-strike-delete-btn',
    )
}

/** /admin/users: search, the user detail and its account actions. */
export class AdminUsersScreen extends AdminScreen {
  readonly route = R.adminUsers
  readonly readyTestId = 'admin-users-page'
  search = () => this.tid('user-search-input')
  searchButton = () => this.tid('user-search-btn')
  views = () => this.tid('user-row-view-btn')
  view = (userId: string) =>
    this.page.locator(`[data-testid="user-row-view-btn"][data-user-id="${userId}"]`)
  dialog = () => this.page.getByTestId('user-detail-dialog')
  failedLogins = () => this.page.getByTestId('user-failed-logins')
  privileged = () => this.page.getByTestId('user-privileged-banner')
  unlock = () => this.page.getByTestId('user-unlock-open-btn')
  suspend = () => this.page.getByTestId('user-suspend-open-btn')
  actionDialog = () => this.page.getByTestId('user-action-dialog')
  actionReason = () => this.page.getByTestId('user-action-reason-input')
  actionSubmit = () => this.page.getByTestId('user-action-submit-btn')
  accountControls = () =>
    this.absent('user-unlock-open-btn', 'user-suspend-open-btn', 'user-reactivate-open-btn')

  async find(email: string): Promise<void> {
    await this.search().fill(email)
    await this.searchButton().click()
  }
}

/** /admin/disputes. */
export class AdminDisputesScreen extends AdminScreen {
  readonly route = R.adminDisputes
  readonly readyTestId = 'admin-disputes-page'
  views = () => this.tid('dispute-row-view-btn')
  view = (disputeId: string) =>
    this.page.locator(`[data-testid="dispute-row-view-btn"][data-dispute-id="${disputeId}"]`)
  dialog = () => this.page.getByTestId('dispute-detail-dialog')
  resolveOpen = () => this.page.getByTestId('dispute-resolve-open-btn')
  resolveDialog = () => this.page.getByTestId('dispute-resolve-dialog')
  resolution = () => this.page.getByTestId('dispute-resolution-select')
  creditAmount = () => this.page.getByTestId('dispute-credit-amount-input')
  resolutionNote = () => this.page.getByTestId('dispute-resolution-note-input')
  resolveSubmit = () => this.page.getByTestId('dispute-resolve-submit-btn')
  content = () => this.page.getByTestId('dispute-detail-content')
  decisionControls = () => this.absent('dispute-resolve-open-btn', 'dispute-dismiss-open-btn')
}

/** /admin/appeals. */
export class AdminAppealsScreen extends AdminScreen {
  readonly route = R.adminAppeals
  readonly readyTestId = 'admin-appeals-page'
  views = () => this.tid('appeal-row-view-btn')
  view = (appealId: string) =>
    this.page.locator(`[data-testid="appeal-row-view-btn"][data-appeal-id="${appealId}"]`)
  dialog = () => this.page.getByTestId('appeal-detail-dialog')
  note = () => this.page.getByTestId('appeal-decision-note-input')
  uphold = () => this.page.getByTestId('appeal-uphold-btn')
  overturn = () => this.page.getByTestId('appeal-overturn-btn')
  content = () => this.page.getByTestId('appeal-detail-content')
  decisionControls = () =>
    this.absent('appeal-uphold-btn', 'appeal-overturn-btn', 'appeal-decision-note-input')
}

/** /admin/videos. */
export class AdminVideosScreen extends AdminScreen {
  readonly route = R.adminVideos
  readonly readyTestId = 'admin-videos-page'
  idInput = () => this.tid('video-id-input')
  lookup = () => this.tid('video-lookup-btn')
  dialog = () => this.page.getByTestId('video-detail-dialog')
  content = () => this.page.getByTestId('video-detail-content')
  block = () => this.page.getByTestId('video-block-btn')
  restore = () => this.page.getByTestId('video-restore-btn')
  archivedHint = () => this.page.getByTestId('video-archived-hint')
  actionDialog = () => this.page.getByTestId('video-action-dialog')
  actionSubmit = () => this.page.getByTestId('video-action-submit-btn')
  accessControls = () =>
    this.absent('video-block-btn', 'video-restore-btn', 'video-reconcile-btn', 'video-delete-btn')

  async findVideo(videoId: string): Promise<void> {
    await this.idInput().fill(videoId)
    await this.lookup().click()
  }
}

/** /admin/bookings: lookup by id, force-complete and admin cancel. */
export class AdminBookingsScreen extends AdminScreen {
  readonly route = R.adminBookings
  readonly readyTestId = 'admin-bookings-page'
  idInput = () => this.tid('booking-id-input')
  lookup = () => this.tid('booking-lookup-btn')
  result = () => this.tid('booking-lookup-result')
  moneyControls = () => this.absent('booking-force-complete-open-btn', 'booking-cancel-open-btn')
  forceComplete = () => this.tid('booking-force-complete-open-btn')
  notStuck = () => this.tid('booking-not-stuck-hint')
  cancel = () => this.tid('booking-cancel-open-btn')
  liveHint = () => this.tid('booking-live-session-hint')
  confirmDialog = () => this.page.getByTestId('booking-confirm-dialog')
  note = () => this.page.getByTestId('booking-note-input')
  forceCompleteSubmit = () => this.page.getByTestId('booking-force-complete-submit-btn')

  async find(bookingId: string): Promise<void> {
    await this.idInput().fill(bookingId)
    await this.lookup().click()
  }
}

/** /admin/safeguarding: the inspection queue and the records on file (full admins only). */
export class AdminSafeguardingScreen extends AdminScreen {
  readonly route = R.adminSafeguarding
  readonly readyTestId = 'admin-safeguarding-page'
  tab = (name: 'requests' | 'records') =>
    this.tid('safeguarding-tabs')
      .getByRole('tab')
      .nth(name === 'requests' ? 0 : 1)
  inspect = (coachId: string) =>
    this.page.locator(`[data-testid="safeguarding-inspect-btn"][data-coach-id="${coachId}"]`)
  revoke = (coachId: string) =>
    this.page.locator(`[data-testid="safeguarding-revoke-btn"][data-coach-id="${coachId}"]`)
  actionDialog = () => this.page.getByTestId('safeguarding-action-dialog')
  reason = () => this.page.getByTestId('safeguarding-reason-input')
  confirm = () => this.page.getByTestId('safeguarding-action-confirm')
}

/** /admin/gdpr: the GDPR request tracker (full admins only). */
export class AdminGdprScreen extends AdminScreen {
  readonly route = R.adminGdpr
  readonly readyTestId = 'admin-gdpr-page'
  statusFilter = () => this.tid('gdpr-status-filter')
  row = (requestId: string) =>
    this.page.locator(`[data-testid="gdpr-row-status"][data-request-id="${requestId}"]`)
  overdue = (row: Locator) => row.getByTestId('gdpr-overdue-chip')

  /** Pick a status in the filter (a plain Quasar select: open it, choose the option). */
  async filterStatus(label: string): Promise<void> {
    await this.statusFilter().click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }
}

/** /admin/config: runtime configuration (full admins only). */
export class AdminConfigScreen extends AdminScreen {
  readonly route = R.adminConfig
  readonly readyTestId = 'admin-config-page'
  search = () => this.tid('config-search-input')
  edit = () => this.tid('config-edit-btn')
  /** The Edit button on one key's row. */
  editFor = (key: string) => this.page.locator(`[data-testid="config-edit-btn"][data-key="${key}"]`)
  dialog = () => this.page.getByTestId('config-edit-dialog')
  value = () => this.page.getByTestId('config-value-input')
  save = () => this.page.getByTestId('config-save-btn')
}

/** /admin/moderation: the alert queue (oldest first) and the review-moderation tab. */
export class AdminModerationScreen extends AdminScreen {
  readonly route = R.adminModeration
  readonly readyTestId = 'admin-moderation-page'
  /** The queue row for a held message, by the message id it refers to. */
  messageAlert = (messageId: string) =>
    this.page.locator(`[data-testid="alert-open-message-btn"][data-reference-id="${messageId}"]`)
  messageDialog = () => this.page.getByTestId('message-dialog')
  messageContent = () => this.page.getByTestId('message-detail-content')
  approve = () => this.page.getByTestId('message-approve-btn')
  blockOpen = () => this.page.getByTestId('message-block-open-btn')
  blockReason = () => this.page.getByTestId('message-block-reason-input')
  blockSubmit = () => this.page.getByTestId('message-block-submit-btn')
  reviewsTab = () => this.tid('tab-reviews')
  review = (reviewId: string) =>
    this.page.locator(`[data-testid="review-row-view-btn"][data-review-id="${reviewId}"]`)
  reviewDialog = () => this.page.getByTestId('review-detail-dialog')
  reviewApprove = () => this.page.getByTestId('review-approve-btn')
  reviewBlockOpen = () => this.page.getByTestId('review-block-open-btn')
  reviewBlockReason = () => this.page.getByTestId('review-block-reason-input')
  reviewBlockSubmit = () => this.page.getByTestId('review-block-submit-btn')
}

/** /admin/finance (full admins only). */
export class AdminFinanceScreen extends AdminScreen {
  readonly route = R.adminFinance
  readonly readyTestId = 'admin-finance-page'
  from = () => this.tid('finance-from-input')
  to = () => this.tid('finance-to-input')
  apply = () => this.tid('finance-apply-btn')
  overview = () => this.tid('finance-overview')
  metrics = () => this.page.locator('[data-testid^="finance-metric-"]')
  coachId = () => this.tid('finance-coach-id-input')
  coachLookup = () => this.tid('finance-coach-id-btn')
  coachDialog = () => this.page.getByTestId('finance-coach-dialog')
  coachRevenue = () => this.page.getByTestId('finance-coach-revenue')
  releaseHeld = () => this.page.getByTestId('finance-release-held-btn')
  releaseDialog = () => this.page.getByTestId('finance-release-held-dialog')
  releaseSubmit = () => this.page.getByTestId('finance-release-held-submit-btn')
}
