import type { Locator, Page } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/**
 * The export-and-erasure block (PrivacyDataSection). The same component serves a user's own data on
 * /profile and, scoped to a container, a parent acting for one child (the profile's child dialog, or
 * the child hub's privacy card).
 */
export class PrivacyPanel {
  constructor(
    private readonly page: Page,
    private readonly scope: Locator | Page = page,
  ) {}

  private tid = (id: string) => this.scope.getByTestId(id)
  exportButton = () => this.tid('privacy-export-btn')
  exportStatus = () => this.tid('privacy-export-status')
  download = () => this.tid('privacy-download-btn')
  expired = () => this.tid('privacy-export-expired')
  failed = () => this.tid('privacy-export-failed')
  openErasure = () => this.tid('privacy-erasure-open-btn')
  /** The erasure dialog is teleported to <body>, outside any scope. */
  erasureDialog = () => this.page.getByTestId('privacy-erasure-dialog')
  confirmInput = () => this.erasureDialog().getByTestId('privacy-erasure-confirm-input')
  submitErasure = () => this.erasureDialog().getByTestId('privacy-erasure-submit-btn')
  erasureError = () => this.erasureDialog().getByTestId('privacy-erasure-error')
}

/** /profile, the Privacy section every role has. */
export class PrivacyScreen extends Screen {
  readonly route = R.profile
  readonly readyTestId = 'profile-privacy'
  own = () => new PrivacyPanel(this.page, this.tid('profile-privacy'))
  /** A parent's per-child privacy button in "My children". */
  childPrivacy = (playerId: string) =>
    this.page.locator(`[data-testid="child-privacy-btn"][data-player-id="${playerId}"]`)
  childDialog = () => this.page.getByTestId('child-privacy-dialog')
  child = () => new PrivacyPanel(this.page, this.childDialog())
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
}

/** /parent/players/:playerId/sessions, the child hub, with its privacy card. */
export class ChildHubPrivacyScreen extends Screen {
  readonly route = R.childHub
  readonly readyTestId = 'child-hub-privacy'
  privacy = () => new PrivacyPanel(this.page, this.tid('child-hub-privacy'))
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
}

/** /unsubscribe?token=…, public: it asks for one click before unsubscribing. */
export class UnsubscribeScreen extends Screen {
  readonly route = R.unsubscribe
  readonly readyTestId = 'unsubscribe-page'
  missing = () => this.tid('unsub-missing')
  confirm = () => this.tid('unsub-confirm')
  done = () => this.tid('unsub-done')
  error = () => this.tid('unsub-error')
}
