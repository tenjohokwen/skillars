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

  /** Open the page on one thread, the way every entry point does (`?conversation=`). */
  async openConversation(conversationId: string): Promise<this> {
    await this.page.goto(this.context.routing.toUrl(`${this.route}?conversation=${conversationId}`))
    await this.waitReady()
    await this.input().or(this.composerLocked()).waitFor()
    return this
  }
}
