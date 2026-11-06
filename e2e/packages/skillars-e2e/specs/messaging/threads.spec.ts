import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { adultThread, token } from '../../src/data/threads.js'
import { t } from '../../src/i18n.js'
import {
  AgeVerificationScreen,
  CoachPlayerDetailScreen,
  CoachPlayersScreen,
  CoachPublicProfileScreen,
  MessagingScreen,
} from '../../src/screens/index.js'
import { geminiCallsContaining } from '../../src/stubs/wiremock.js'

// K · Messaging, part 2: entry points, parent oversight, reporting, paging, the adult age gate, and
// contact details between adults (owner decision 2026-11-06: allowed, delivered as written).

/** The conversation a `?conversation=` URL points at. */
async function conversationIn(page: Page): Promise<string> {
  await expect(page).toHaveURL(/conversation=\d+/)
  return /conversation=(\d+)/.exec(page.url())![1]!
}

test.describe('K · Threads', () => {
  test(
    "between adults, contact details are delivered as written (only minors' threads hold them)",
    scenario({
      id: 'MSG-12',
      priority: 'P0',
      type: 'edge',
      tags: ['@regression', '@coach', '@player', '@messaging'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, player, conversationId } = await adultThread(provision)
      const self = await actor(asRole(coach))
      const coachChat = await new MessagingScreen(self.page, suite).openConversation(conversationId)
      const text = `Call me on 0791 112 3456 or coach.e2e@example.com ${token()}`
      await coachChat.input().fill(text)
      await coachChat.send().click()
      await expect(coachChat.bubbleWith(text)).toBeVisible()
      // Moderated like any message (SAFE), not held for contact details.
      expect(await geminiCallsContaining(text)).toBe(1)

      const other = await actor(asRole(player))
      const playerChat = await new MessagingScreen(other.page, suite).openConversation(
        conversationId,
      )
      await expect(playerChat.bubbleWith(text)).toHaveText(text)
    },
  )

  test(
    'every entry point opens the same thread through ?conversation=, created on first use',
    scenario({
      id: 'MSG-14',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@messaging'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.confirmedBooking(parent, coach)
      const playerId = parent.children[0]!.playerId

      // The coach's roster shortcut creates the thread.
      const self = await actor(asRole(coach))
      await new CoachPlayersScreen(self.page, suite).open()
      await new CoachPlayersScreen(self.page, suite).message(playerId).click()
      const fromRoster = await conversationIn(self.page)
      await expect(new MessagingScreen(self.page, suite).input()).toBeVisible()

      // The player page's button finds the same one.
      const detail = await new CoachPlayerDetailScreen(self.page, suite).open({ playerId })
      await detail.message().click()
      expect(await conversationIn(self.page)).toBe(fromRoster)

      // So does the family's button on the coach's profile.
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).open({
        coachId: coach.coachId!,
      })
      await profile.messageCoach().click()
      expect(await conversationIn(family.page)).toBe(fromRoster)
      await expect(new MessagingScreen(family.page, suite).input()).toBeVisible()
    },
  )

  test(
    "a parent oversees a child's thread read-only, with a way to reply from their own conversations",
    scenario({
      id: 'MSG-15',
      priority: 'P0',
      type: 'edge',
      tags: ['@regression', '@parent', '@messaging'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      // An 11-year-old with a booking, and an adult child: oversight never lists the adult.
      const parent = await provision.parent({ childAges: [11, 19] })
      await provision.saveCard(parent)
      const minor = parent.children[0]!
      const adult = parent.children[1]!
      await provision.confirmedBooking(parent, coach, { playerId: minor.playerId })
      const conversationId = await provision.conversation(coach, coach, minor.playerId)
      const fromCoach = `Training moved to 5pm ${token()}`
      await provision.seedMessages(conversationId, coach, 'COACH', [fromCoach])

      const family = await actor(asRole(parent))
      const inbox = await new MessagingScreen(family.page, suite).open()
      await inbox.oversightSelect().click()
      await expect(family.page.getByRole('option', { name: minor.name, exact: true })).toBeVisible()
      await expect(family.page.getByRole('option', { name: adult.name, exact: true })).toHaveCount(
        0,
      )
      await family.page.getByRole('option', { name: minor.name, exact: true }).click()

      await expect(inbox.oversightBanner()).toContainText(minor.name)
      const thread = inbox.conversation(conversationId)
      await expect(inbox.roleChip(thread)).toHaveText(t('messaging.oversight.primaryChip'))
      await thread.click()
      await expect(inbox.bubbleWith(fromCoach)).toBeVisible()
      await expect(inbox.input()).toHaveCount(0)

      // Reply leads to the same thread in the parent's own conversations, where they can write.
      await inbox.oversightReply().click()
      expect(await conversationIn(family.page)).toBe(conversationId)
      await expect(inbox.input()).toBeVisible()
      await expect(inbox.oversightBanner()).toHaveCount(0)
    },
  )

  test(
    'reporting a conversation warns that it blocks the thread, then locks the composer for both sides',
    scenario({
      id: 'MSG-17',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@player', '@messaging'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, player, conversationId } = await adultThread(provision)
      const other = await actor(asRole(player))
      const playerChat = await new MessagingScreen(other.page, suite).openConversation(
        conversationId,
      )
      const thread = playerChat.conversation(conversationId)
      await playerChat.conversationMenu(thread).click()
      await playerChat.reportConversation().click()
      await expect(playerChat.reportBlocksHint()).toHaveText(
        t('messaging.reportConversationBlocksHint'),
      )
      await expect(playerChat.reportSubmit()).toBeDisabled()
      await playerChat.chooseReportReason(t('messaging.reportReason.HARASSMENT'))
      // Details stop at 500 characters.
      await playerChat.reportDetails().fill('x'.repeat(600))
      await expect(playerChat.reportDetails()).toHaveValue('x'.repeat(500))
      await playerChat.reportSubmit().click()
      await expect(other.page.getByText(t('messaging.reportSuccess'))).toBeVisible()

      await expect(playerChat.composerLocked()).toHaveText(t('messaging.blockedBanner'))
      await expect(playerChat.input()).toHaveCount(0)

      const self = await actor(asRole(coach))
      const coachChat = await new MessagingScreen(self.page, suite).openConversation(conversationId)
      await expect(coachChat.composerLocked()).toHaveText(t('messaging.blockedBanner'))
      await expect(coachChat.input()).toHaveCount(0)
    },
  )

  test(
    '"Load older" pages history backwards without repeating a message',
    scenario({
      id: 'MSG-18',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@coach', '@messaging'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, player, conversationId } = await adultThread(provision)
      const run = token()
      const history = Array.from(
        { length: 25 },
        (_, i) => `Message ${run} number ${String(i).padStart(2, '0')}`,
      )
      await provision.seedMessages(conversationId, player, 'PLAYER', history)

      const self = await actor(asRole(coach))
      const chat = await new MessagingScreen(self.page, suite).openConversation(conversationId)
      // A page is 20, newest first: the five oldest wait behind Load older.
      await expect(chat.bubbles()).toHaveCount(20)
      await expect(chat.bubbleWith(history[24]!)).toBeVisible()
      await expect(chat.bubbleWith(history[0]!)).toHaveCount(0)
      await chat.loadOlder().click()
      await expect(chat.bubbles()).toHaveCount(25)
      for (const text of history) await expect(chat.bubbleWith(text)).toHaveCount(1)
      await expect(chat.loadOlder()).toHaveCount(0)
    },
  )

  test(
    "with age verification on, an unverified adult player's chat sends them to the age check, and back",
    scenario({
      id: 'MSG-19',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@player', '@messaging', '@config'],
    }),
    async ({ actor, suite, provision, runtimeConfig }) => {
      // The thread exists before the switch: with verification on, booking needs it too.
      const { player, conversationId } = await adultThread(provision)
      const key = 'identity.ageVerification.enabled'
      await provision.ensureConfigRow(key, 'false')
      await runtimeConfig.set(key, 'true')

      const other = await actor(asRole(player))
      const chat = new MessagingScreen(other.page, suite)
      await other.page.goto(suite.routing.toUrl(`/messaging?conversation=${conversationId}`))
      const check = new AgeVerificationScreen(other.page, suite)
      // The gate answers the first chat call it guards: opening, or else the first send.
      await expect(chat.input().or(check.required())).toBeVisible()
      if (await chat.input().isVisible()) {
        await chat.input().fill(`Hello coach ${token()}`)
        await chat.send().click()
      }
      await expect(check.required()).toBeVisible()
      await expect(other.page).toHaveURL(/next=/)

      // Verified (the Stripe-hosted check is done), the page returns them to the conversation.
      await provision.markAgeVerified(player)
      await other.page.reload()
      await check.continue().click()
      expect(await conversationIn(other.page)).toBe(conversationId)
      const hello = `Verified now ${token()}`
      await chat.input().fill(hello)
      await chat.send().click()
      await expect(chat.bubbleWith(hello)).toBeVisible()
    },
  )
})
