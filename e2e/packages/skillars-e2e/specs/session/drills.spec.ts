import { randomUUID } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { t, tc } from '../../src/i18n.js'
import {
  DrillLibraryScreen,
  SessionBuilderScreen,
  TemplateVaultScreen,
} from '../../src/screens/index.js'

// I · Session delivery, part 4: the drill library, the session builder and the template vault.
//
// The library is open to every coach; the builder and templates are a paid-tier feature
// (feature.sessionBuilder.enabled.SCOUT is false), so those specs subscribe the coach to Instructor.
// The platform library is the 20 seeded drills; a coach's own drills are made through the API.

const unique = (label: string) => `${label} ${randomUUID().slice(0, 8)}`

/** An Instructor coach with one CONFIRMED booking per weekday given, next week. */
async function plannerWithBookings(provision: Provisioner, weekdays: (1 | 2 | 3 | 4 | 5)[]) {
  const coach = await provision.coach()
  await provision.saveCard(coach)
  await provision.subscribeCoach(coach, 'INSTRUCTOR')
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookings: string[] = []
  for (const weekday of weekdays) {
    bookings.push(await provision.confirmedBooking(parent, coach, { weekday, time: '10:00' }))
  }
  const platform = await provision.platformDrills(coach)
  return { coach, bookings, platform }
}

test.describe('I · Drill library', () => {
  test(
    'Platform and My Library are separate tabs, and the filter narrows each',
    scenario({
      id: 'SES-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const mine = unique('Wall pass ladder')
      await provision.privateDrill(coach, { name: mine, difficultyTier: 'U16' })

      const self = await actor(asRole(coach))
      const library = await new DrillLibraryScreen(self.page, suite).open()

      // Platform: only platform drills, and not the coach's own.
      await expect(library.cards().first()).toBeVisible()
      const platformCount = await library.cards().count()
      expect(platformCount).toBeGreaterThan(1)
      await expect(library.cards().and(self.page.locator('[data-library="PRIVATE"]'))).toHaveCount(
        0,
      )
      await expect(library.card(mine)).toHaveCount(0)

      // The filter narrows the platform list to one difficulty tier.
      await library.filterByDifficulty('U8')
      // Wait out the reload: every card shown is a U8 one.
      await expect
        .poll(async () => {
          const shown = await library.cards().count()
          return shown > 0 && shown === (await library.cardsOfTier('U8').count())
        })
        .toBe(true)
      expect(await library.cards().count()).toBeLessThan(platformCount)

      // My Library: the coach's drill alone (switching tabs clears the filter).
      await library.privateTab().click()
      await expect(library.cards()).toHaveCount(1)
      await expect(library.card(mine)).toBeVisible()

      // A tier it is not: the filtered-empty state, whose Clear brings it back.
      await library.filterByDifficulty('U8')
      await expect(library.noResults()).toBeVisible()
      await expect(library.noResults()).toContainText(t('session.drillLibrary.noResults'))
      await library.clearFiltersFromEmpty().click()
      await expect(library.card(mine)).toBeVisible()

      // Its own tier keeps it.
      await library.filterByDifficulty('U16')
      await expect(library.cards()).toHaveCount(1)
      await expect(library.card(mine)).toBeVisible()

      // And back on Platform it is still absent, the full list restored.
      await library.platformTab().click()
      await expect(library.cards()).toHaveCount(platformCount)
      await expect(library.card(mine)).toHaveCount(0)
    },
  )
})

test.describe('I · Session builder', () => {
  test(
    'drills are added to blocks, dragged between blocks, blocks reordered, and the plan saves',
    scenario({
      id: 'SES-12',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, bookings, platform } = await plannerWithBookings(provision, [2])
      const [first, second] = platform
      const self = await actor(asRole(coach))
      const builder = await new SessionBuilderScreen(self.page, suite).open({
        bookingId: bookings[0]!,
      })

      // The four default blocks; Warm-Up is active, so the first drill lands there.
      await expect(builder.blocks()).toHaveCount(4)
      await builder.add(first!.name)
      await expect(builder.drillsIn(0)).toHaveCount(1)
      // Choosing the second block makes it the target of the next add.
      await builder.activate(1)
      await builder.add(second!.name)
      await expect(builder.drillsIn(1)).toHaveCount(1)

      // Drag the Warm-Up drill into the second block, by its handle.
      await builder.drag(
        builder.drillHandle(builder.drillsIn(0).first()),
        builder.drillsIn(1).first(),
      )
      await expect(builder.drillsIn(0)).toHaveCount(0)
      await expect(builder.drillsIn(1)).toHaveCount(2)

      // Drag the second block above the first, by the block handle.
      await builder.drag(builder.blockHandle(1), builder.blockHandle(0))
      await expect(builder.block(0)).toHaveAttribute('data-block-type', 'TECHNICAL_FOUNDATION')
      await expect(builder.block(1)).toHaveAttribute('data-block-type', 'WARM_UP')

      // Save needs a development focus.
      await expect(builder.save()).toBeDisabled()
      await builder.focusTab().click()
      await builder.focus('technical').click()
      await builder.save().click()
      await expect(builder.saveAsTemplate()).toBeVisible()

      // The server holds the order and the drills as arranged.
      const plan = await provision.sessionPlanOf(coach, bookings[0]!)
      expect(plan.map((b) => b.blockType)).toEqual([
        'TECHNICAL_FOUNDATION',
        'WARM_UP',
        'GAME_INTENSITY',
        'COOL_DOWN_REVIEW',
      ])
      expect([...plan[0]!.drills].sort()).toEqual([first!.id, second!.id].sort())
      expect(plan[1]!.drills).toEqual([])

      // And a reload shows the same plan.
      await self.page.reload()
      await expect(builder.block(0)).toHaveAttribute('data-block-type', 'TECHNICAL_FOUNDATION')
      await expect(builder.drillsIn(0)).toHaveCount(2)
      await expect(builder.drillsIn(1)).toHaveCount(0)
    },
  )

  test(
    'a plan saved as a template is reused on another booking',
    scenario({
      id: 'SES-13',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, bookings, platform } = await plannerWithBookings(provision, [2, 3])
      const [planned, fresh] = bookings as [string, string]
      const drill = platform[0]!
      await provision.sessionPlan(coach, planned, [drill.id])
      const name = unique('Rondo day')

      const self = await actor(asRole(coach))
      const builder = await new SessionBuilderScreen(self.page, suite).open({ bookingId: planned })
      await builder.saveAsTemplate().click()
      await builder.templateName().fill(name)
      await builder.templateSave().click()
      await expect(builder.templateName()).toBeHidden()

      // The vault lists it: one drill, never used.
      const vault = await new TemplateVaultScreen(self.page, suite).open()
      const item = vault.item(name)
      await expect(vault.meta(item)).toContainText(
        tc('session.templates.drillCount', 1, { count: 1 }),
      )
      await expect(vault.meta(item)).toContainText(
        tc('session.templates.deployCount', 0, { count: 0 }),
      )

      // On the other booking, which has no plan yet, the builder offers it.
      const other = await new SessionBuilderScreen(self.page, suite).open({ bookingId: fresh })
      await other.useTemplate().click()
      await other.templateChoices().filter({ hasText: name }).click()
      await expect(other.templateBanner()).toContainText(
        t('session.templates.templateIndicator', { name }),
      )
      await expect(other.drillsIn(0)).toHaveCount(1)
      await expect(other.drillsIn(0)).toContainText(drill.name)
      expect((await provision.sessionPlanOf(coach, fresh))[0]!.drills).toEqual([drill.id])

      // And the vault counts the use.
      await vault.open()
      await expect(vault.meta(vault.item(name))).toContainText(
        tc('session.templates.deployCount', 1, { count: 1 }),
      )
    },
  )

  test(
    'a template is renamed and deleted from its menu, and the delete asks first',
    scenario({
      id: 'SES-14',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, bookings, platform } = await plannerWithBookings(provision, [2])
      const sessionId = await provision.sessionPlan(coach, bookings[0]!, [platform[0]!.id])
      const name = unique('Finishing')
      const renamed = unique('Finishing under pressure')
      await provision.sessionTemplate(coach, sessionId, name)

      const self = await actor(asRole(coach))
      const vault = await new TemplateVaultScreen(self.page, suite).open()
      await expect(vault.items()).toHaveCount(1)

      await vault.menu(vault.item(name)).click()
      await vault.menuRename().click()
      await expect(vault.renameInput()).toHaveValue(name)
      await vault.renameInput().fill(renamed)
      await vault.renameSave().click()
      await expect(vault.renameInput()).toBeHidden()
      await expect(vault.item(renamed)).toHaveCount(1)
      await expect(vault.item(name)).toHaveCount(0)

      // Delete asks first; Cancel keeps it.
      await vault.menu(vault.item(renamed)).click()
      await vault.menuDelete().click()
      await expect(self.page.getByText(t('session.templates.confirmDelete'))).toBeVisible()
      await vault.deleteCancel().click()
      await expect(vault.item(renamed)).toHaveCount(1)

      await vault.menu(vault.item(renamed)).click()
      await vault.menuDelete().click()
      await vault.deleteConfirm().click()
      await expect(vault.empty()).toBeVisible()
      await expect(vault.items()).toHaveCount(0)

      // Gone for good, not just from this page.
      await self.page.reload()
      await expect(vault.empty()).toBeVisible()
    },
  )
})
