import type { Locator } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/**
 * /messaging, one page for every role: the conversation list, the open thread and its composer, the
 * report dialog, and a parent's read-only oversight of a child's threads. Message bubbles carry their
 * message id; a hidden message shows a moderation notice in place of its text.
 */
export class MessagingScreen extends Screen {
  readonly route = R.messaging
  readonly readyTestId = 'messaging-page'
  conversation = (conversationId: string) => this.tid(`conversation-item-${conversationId}`)
  conversations = () => this.page.locator('[data-testid^="conversation-item-"]')
  bubbles = () => this.page.locator('[data-testid^="message-bubble-"]')
  bubble = (messageId: string) => this.tid(`message-bubble-${messageId}`)
  /** The bubble whose visible text includes `text`. */
  bubbleWith = (text: string) => this.bubbles().filter({ hasText: text })
  notices = () => this.page.locator('[data-testid^="moderation-notice-"]')
  input = () => this.tid('message-input')
  send = () => this.tid('send-btn')
  composerLocked = () => this.tid('composer-locked-banner')
  loadOlder = () => this.tid('load-older-btn')
  oversightSelect = () => this.tid('oversight-child-select')
  oversightBanner = () => this.tid('oversight-banner')
  roleChip = (conversation: Locator) => this.within(conversation, 'oversight-role-chip')
  oversightReply = () => this.tid('oversight-reply-btn')
  blockedChip = (conversation: Locator) => this.within(conversation, 'conversation-blocked-chip')
  conversationMenu = (conversation: Locator) => this.within(conversation, 'conversation-kebab-btn')
  reportConversation = () => this.tid('report-conversation-action')
  reportDialog = () => this.tid('report-dialog')
  reportBlocksHint = () => this.tid('report-conversation-blocks-hint')
  reportDetails = () => this.tid('report-details-input')
  reportSubmit = () => this.tid('report-submit-btn')

  /** `label` is the reason's visible text (resolve it through `t()`). */
  async chooseReportReason(label: string): Promise<void> {
    await this.tid('report-reason-select').click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }

  /** Switch the parent's view to one child's threads (read-only oversight). */
  async overseeChild(name: string): Promise<void> {
    await this.oversightSelect().click()
    await this.page.getByRole('option', { name, exact: true }).click()
  }

  /** Open the page on one thread, the way every entry point does (`?conversation=`). */
  async openConversation(conversationId: string): Promise<this> {
    await this.page.goto(this.context.routing.toUrl(`${this.route}?conversation=${conversationId}`))
    await this.waitReady()
    await this.input().or(this.composerLocked()).waitFor()
    return this
  }
}

/** /coach/players: the coach's roster, each player with a message shortcut. */
export class CoachPlayersScreen extends Screen {
  readonly route = R.coachPlayers
  readonly readyTestId = 'my-players-page'
  message = (playerId: string) => this.tid(`roster-message-${playerId}`)
}

/** /coach/players/:playerId: one player's page, with "Send message". */
export class CoachPlayerDetailScreen extends Screen {
  readonly route = R.coachPlayerDetail
  readonly readyTestId = 'coach-message-player'
  message = () => this.tid('coach-message-player')
}

/** /player/age-verification: where the age gate sends an unverified adult player (C-17). */
export class AgeVerificationScreen extends Screen {
  readonly route = R.ageVerification
  readonly readyTestId = 'age-verification-page'
  /** "Verification required" — shown when the gate, not the player, opened the page. */
  required = () => this.tid('age-verification-required')
  verified = () => this.tid('age-verified')
  /** On a verified check: back to where the gate interrupted (`next`). */
  continue = () => this.tid('age-continue')
}
