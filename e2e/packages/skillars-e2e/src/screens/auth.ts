import { Screen, type ScreenContext } from 'pitchside-core'
import type { Locator, Page } from '@playwright/test'
import type { AccountKind } from '../data/provisioner.js'
import { R, REGISTRATION } from '../routes.js'

// Screens for the sign-up → verify → log-in funnel (test-id wave W1). The coach, parent and player
// variants of each registration step share their test ids, so one screen takes the role.

export class LoginScreen extends Screen {
  readonly route = R.login
  readonly readyTestId = 'login-email-input'
  email = () => this.tid('login-email-input')
  password = () => this.tid('login-password-input')
  submit = () => this.tid('login-submit-btn')
  error = () => this.tid('login-error')
  expiredBanner = () => this.tid('login-expired-banner')
  verifiedBanner = () => this.tid('login-verified-banner')
  notVerifiedBanner = () => this.tid('login-not-verified-banner')
  lockedBanner = () => this.tid('login-locked-banner')
  forgotPasswordLink = () => this.tid('login-forgot-password-link')

  async logIn(email: string, password: string): Promise<void> {
    await this.email().fill(email)
    await this.password().fill(password)
    await this.submit().click()
  }
}

export class ForgotPasswordScreen extends Screen {
  readonly route = R.forgotPassword
  readonly readyTestId = 'forgot-password-email-input'
  email = () => this.tid('forgot-password-email-input')
  dateOfBirth = () => this.tid('forgot-password-dob-input')
  submit = () => this.tid('forgot-password-submit-btn')
  success = () => this.tid('forgot-password-success')
  error = () => this.tid('forgot-password-error')

  async request(email: string, dateOfBirth: string): Promise<void> {
    await this.email().fill(email)
    await this.dateOfBirth().fill(dateOfBirth)
    await this.submit().click()
  }
}

export class ResetPasswordScreen extends Screen {
  readonly route = R.resetPassword
  readonly readyTestId = 'reset-password-password-input'
  password = () => this.tid('reset-password-password-input')
  confirm = () => this.tid('reset-password-confirm-input')
  submit = () => this.tid('reset-password-submit-btn')
  error = () => this.tid('reset-password-error')
  keyMissing = () => this.tid('reset-password-key-missing')

  async reset(password: string): Promise<void> {
    await this.password().fill(password)
    await this.confirm().fill(password)
    await this.submit().click()
  }
}

/** Coach, parent and player registration. Parent-only and player-only controls are noted. */
export class RegisterScreen extends Screen {
  readonly route: string
  readonly readyTestId = 'register-email-input'

  constructor(
    page: Page,
    context: ScreenContext,
    readonly kind: AccountKind,
  ) {
    super(page, context)
    this.route = REGISTRATION[kind].register
  }

  error = () => this.tid('register-error')
  firstName = () => this.tid('register-first-name-input')
  lastName = () => this.tid('register-last-name-input')
  email = () => this.tid('register-email-input')
  phone = () => this.tid('register-phone-input')
  password = () => this.tid('register-password-input')
  tos = () => this.tid('register-tos-checkbox')
  privacy = () => this.tid('register-privacy-checkbox')
  submit = () => this.tid('register-submit-btn')
  /** Player only. */
  dateOfBirth = () => this.tid('register-dob-input')
  /** Player only: shown while the date of birth is under 18. */
  mustBeAdultBanner = () => this.tid('register-must-be-adult-banner')
  /** Parent only: each policy must be scrolled to the end before its checkbox enables. */
  tosScrollBox = () => this.tid('register-tos-scroll-box')
  privacyScrollBox = () => this.tid('register-privacy-scroll-box')
  parentConsentScrollBox = () => this.tid('register-parent-consent-scroll-box')
  parentConsent = () => this.tid('register-parent-consent-checkbox')

  async fillDetails(details: {
    firstName: string
    lastName: string
    email: string
    phone: string
    password: string
    dateOfBirth?: string
  }): Promise<void> {
    await this.firstName().fill(details.firstName)
    await this.lastName().fill(details.lastName)
    await this.email().fill(details.email)
    await this.phone().fill(details.phone)
    if (details.dateOfBirth !== undefined) await this.dateOfBirth().fill(details.dateOfBirth)
    await this.password().fill(details.password)
  }
}

/** Scroll a policy box to its end, the way a reader would, so its scroll handler fires. */
export async function scrollToEnd(box: Locator): Promise<void> {
  await box.evaluate((el) => {
    el.scrollTop = el.scrollHeight
    el.dispatchEvent(new Event('scroll'))
  })
}

export class EmailPendingScreen extends Screen {
  readonly route: string
  readonly readyTestId = 'email-pending-title'

  constructor(
    page: Page,
    context: ScreenContext,
    readonly kind: AccountKind,
  ) {
    super(page, context)
    this.route = REGISTRATION[kind].emailPending
  }

  body = () => this.tid('email-pending-body')
  resend = () => this.tid('email-pending-resend-btn')
  resendSuccess = () => this.tid('email-pending-resend-success')
  error = () => this.tid('email-pending-error')
}

export class EmailVerifyScreen extends Screen {
  readonly route: string
  readonly readyTestId = 'email-verify-pending'

  constructor(
    page: Page,
    context: ScreenContext,
    readonly kind: AccountKind,
  ) {
    super(page, context)
    this.route = REGISTRATION[kind].verifyEmail
  }

  errorTitle = () => this.tid('email-verify-error-title')
  error = () => this.tid('email-verify-error')
  resend = () => this.tid('email-verify-resend-btn')
}

export class PlayerBuilderScreen extends Screen {
  readonly route = R.playerBuilder
  readonly readyTestId = 'player-builder-position-select'
  position = () => this.tid('player-builder-position-select')
  submit = () => this.tid('player-builder-submit-btn')
  error = () => this.tid('player-builder-error')

  /** `label` is the option's visible text (resolve it through `t()`). */
  async choosePosition(label: string): Promise<void> {
    await this.position().click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }
}
