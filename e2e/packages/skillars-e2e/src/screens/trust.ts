import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/** /player/dashboard: the self-registered player's home tiles. */
export class PlayerDashboardScreen extends Screen {
  readonly route = R.playerDashboard
  readonly readyTestId = 'player-dashboard'
  /** S-27: shown once age verification is switched on, or once the player is verified. */
  ageTile = () => this.tid('age-verification-tile')
}

/** /coach/safeguarding: the coach's optional safeguarding check (S-09). */
export class CoachSafeguardingScreen extends Screen {
  readonly route = R.coachSafeguarding
  readonly readyTestId = 'safeguarding-page'
  status = () => this.tid('safeguarding-status')
  valid = () => this.tid('safeguarding-valid')
  pending = () => this.tid('safeguarding-pending')
  /** The certificate picker; files go to its native input. */
  fileInput = () =>
    this.page.locator(
      '[data-testid="safeguarding-file"] input[type="file"], input[type="file"][data-testid="safeguarding-file"]',
    )
  message = () => this.tid('safeguarding-message')
  request = () => this.tid('safeguarding-request-btn')
  withdraw = () => this.tid('safeguarding-withdraw-btn')
  withdrawDialog = () => this.page.getByTestId('safeguarding-withdraw-dialog')
  withdrawConfirm = () => this.page.getByTestId('safeguarding-withdraw-confirm')
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
}
