import { randomUUID } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { BROWSER_TIME_ZONE } from '../../src/data/dates.js'
import type { CoachAccount, ParentAccount, Provisioner } from '../../src/data/provisioner.js'
import { E2E } from '../../src/env.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import {
  ChildHubScreen,
  CoachPlayerDetailScreen,
  CoachPlayersScreen,
  LockerRoomScreen,
} from '../../src/screens/index.js'

// J · Development, part 2: the locker room (homework), and the coach's roster and player page.
//
// Homework is assigned the product's way: the coach's wrap-up names the drills, and the family's
// confirmation completes the session. The locker room lists a coach's homework only while the
// family holds an ACTIVE pack with that coach, so each coach here also sells the family a pack.

/** A coach (with a distinct name) whose completed session gave the child `drill` as homework. */
async function homeworkFrom(
  provision: Provisioner,
  parent: ParentAccount,
  drill: { id: string },
): Promise<CoachAccount> {
  const coach = await provision.coach({ displayName: `Coach ${randomUUID().slice(0, 6)}` })
  await provision.completedSession(parent, coach, { homework: [drill.id] })
  await provision.buyPack(parent, coach)
  return coach
}

/** A family with a card, and the platform drills a coach may assign. */
async function family(provision: Provisioner) {
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const scout = await provision.coach()
  const drills = await provision.platformDrills(scout)
  return { parent, child: parent.children[0]!.playerId, drills }
}

test.describe('J · Locker room', () => {
  test(
    'homework is grouped by the coach who assigned it',
    scenario({
      id: 'DEV-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // completed sessions, packs and async homework assignment
      const { parent, child, drills } = await family(provision)
      const first = await homeworkFrom(provision, parent, drills[0]!)
      const second = await homeworkFrom(provision, parent, drills[1]!)
      const items = await provision.homework(parent, child, 2)

      const self = await actor(asRole(parent))
      const room = await new LockerRoomScreen(self.page, suite).open({ playerId: child })

      await expect(room.groups()).toHaveCount(2)
      for (const [coach, drill] of [
        [first, drills[0]!],
        [second, drills[1]!],
      ] as const) {
        const name = items.find((i) => i.drill.name === drill.name)!.coachDisplayName
        const group = room
          .groups()
          .filter({ has: self.page.getByText(drill.name, { exact: true }) })
        await expect(group).toHaveAttribute('data-coach-id', coach.coachId!)
        await expect(group.getByTestId('locker-room-group-header')).toHaveText(
          t('player.assignedBy', { coach: name }),
        )
      }
    },
  )

  test(
    'marking homework done cannot be undone, even after a reload',
    scenario({
      id: 'DEV-09',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // completed sessions, packs and async homework assignment
      const { parent, child, drills } = await family(provision)
      await homeworkFrom(provision, parent, drills[0]!)
      await provision.homework(parent, child, 1)

      const self = await actor(asRole(parent))
      const room = await new LockerRoomScreen(self.page, suite).open({ playerId: child })
      const item = room.assignment(drills[0]!.name)
      await expect(item).toHaveAttribute('data-completed', 'false')
      await expect(room.done(item)).toContainText(t('player.homeworkMarkDone'))

      await room.done(item).click()
      await expect(item).toHaveAttribute('data-completed', 'true')
      await expect(room.done(item)).toContainText(t('player.homeworkCompleted'))
      await expect(room.done(item)).toHaveAttribute('aria-disabled', 'true')

      // A second click does nothing, and the server agrees after a reload.
      await room.done(item).click({ force: true })
      await self.page.reload()
      await expect(room.assignment(drills[0]!.name)).toHaveAttribute('data-completed', 'true')
      await expect(room.done(room.assignment(drills[0]!.name))).toHaveAttribute(
        'aria-disabled',
        'true',
      )
      expect((await provision.homework(parent, child, 1))[0]!.completed).toBe(true)
    },
  )

  test(
    'a new player with no homework sees the empty locker room',
    scenario({
      id: 'DEV-10',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@player', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const room = await new LockerRoomScreen(self.page, suite).open({
        playerId: player.playerId!,
      })

      await expect(room.empty()).toContainText(t('player.homeworkEmptyTitle'))
      await expect(room.error()).toHaveCount(0)
      await expect(room.assignments()).toHaveCount(0)
    },
  )

  test(
    "a parent opens their child's locker room from the child hub",
    scenario({
      id: 'DEV-15',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // completed sessions, packs and async homework assignment
      const { parent, child, drills } = await family(provision)
      await homeworkFrom(provision, parent, drills[0]!)
      await provision.homework(parent, child, 1)

      const self = await actor(asRole(parent))
      const hub = await new ChildHubScreen(self.page, suite).open({ playerId: child })
      await hub.lockerRoom().click()

      const room = await new LockerRoomScreen(self.page, suite).waitReady()
      expect(room.currentRoute()).toBe(R.lockerRoom.replace(':playerId(\\d+)', child))
      await expect(room.assignment(drills[0]!.name)).toBeVisible()
    },
  )
})

test.describe("J · The coach's players", () => {
  test(
    "the coach's roster lists a booked player and opens their page",
    scenario({
      id: 'DEV-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const coach = await provision.coach()
      await provision.confirmedBooking(parent, coach)
      const child = parent.children[0]!.playerId

      const self = await actor(asRole(coach))
      const roster = await new CoachPlayersScreen(self.page, suite).open()
      await expect(roster.players()).toHaveCount(1)
      await roster.player(child).click()

      const detail = await new CoachPlayerDetailScreen(self.page, suite).waitReady()
      expect(detail.currentRoute()).toBe(R.coachPlayerDetail.replace(':playerId(\\d+)', child))
      await expect(detail.radarCard()).toBeVisible()
      await expect(detail.error()).toHaveCount(0)
    },
  )

  test(
    "a coach can't open a player they have never been booked with",
    scenario({
      id: 'DEV-12',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@parent', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const theirs = await provision.coach()
      await provision.confirmedBooking(parent, theirs)
      const child = parent.children[0]!.playerId
      const stranger = await provision.coach()

      const self = await actor(asRole(stranger))
      const detail = await new CoachPlayerDetailScreen(self.page, suite).open({ playerId: child })

      await expect(detail.error()).toHaveText(t('coach.playerDetailAccessEnded'))
      await expect(detail.radarCard()).toHaveCount(0)
      await expect(detail.homeworkCard()).toHaveCount(0)
    },
  )

  test(
    'a Scout coach sees the radar history and reports behind an upgrade overlay',
    scenario({
      id: 'DEV-13',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@parent', '@development', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const coach = await provision.coach() // no subscription: Scout
      await provision.confirmedBooking(parent, coach)
      const child = parent.children[0]!.playerId

      const self = await actor(asRole(coach))
      const detail = await new CoachPlayerDetailScreen(self.page, suite).open({ playerId: child })

      await expect(detail.radarCard()).toBeVisible()
      await expect(detail.radarHistoryGate()).toBeVisible()
      await expect(detail.radarHistory()).toHaveCount(0)
      await expect(detail.addAssessment()).toHaveCount(0)
      await expect(detail.gateUpgrade(detail.reportsCard())).toBeVisible()

      // The upgrade button leads to the coach's subscription page.
      await detail.gateUpgrade(detail.radarHistoryGate()).click()
      await expect.poll(() => detail.currentRoute()).toBe(R.coachSubscription)
    },
  )

  test(
    "once homework is done, the coach's homework card shows the date",
    scenario({
      id: 'DEV-16',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@development'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // completed sessions, packs and async homework assignment
      const { parent, child, drills } = await family(provision)
      const coach = await homeworkFrom(provision, parent, drills[0]!)
      await provision.homework(parent, child, 1)

      // Before: open.
      const coachSide = await actor(asRole(coach))
      const detail = await new CoachPlayerDetailScreen(coachSide.page, suite).open({
        playerId: child,
      })
      await expect(detail.homeworkRows()).toHaveCount(1)
      await expect(detail.homeworkRows().first()).toHaveAttribute('data-completed', 'false')

      // The family marks it done in the locker room.
      const familySide = await actor(asRole(parent))
      const room = await new LockerRoomScreen(familySide.page, suite).open({ playerId: child })
      await room.done(room.assignment(drills[0]!.name)).click()
      await expect(room.assignment(drills[0]!.name)).toHaveAttribute('data-completed', 'true')

      // After: the coach sees it completed, today.
      await coachSide.page.reload()
      const row = detail.homeworkRows().first()
      await expect(row).toHaveAttribute('data-completed', 'true')
      const today = new Date().toLocaleDateString(E2E.locale, { timeZone: BROWSER_TIME_ZONE })
      await expect(detail.homeworkCompleted(row)).toHaveText(
        t('coach.playerDetailHomeworkCompletedOn', { date: today }),
      )
    },
  )
})
