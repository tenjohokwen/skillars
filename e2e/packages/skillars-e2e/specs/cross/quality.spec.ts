import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { lowContrastText, useTheme } from '../../src/data/contrast.js'
import { dateInputValue } from '../../src/data/dates.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { sweepWorld, visit, type Persona } from '../../src/data/sweep.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  LoginScreen,
  MarketplaceScreen,
  PackPurchaseScreen,
  PacksDashboardScreen,
  ParentBookingsScreen,
  ParentDashboardScreen,
} from '../../src/screens/index.js'

// X · Cross-cutting, part 3: themes, phones, keyboards, accessibility and the limits a dialog mirrors from runtime
// config.

test.describe('X · Look and reach', () => {
  for (const theme of ['dark', 'light'] as const) {
    test(
      `every P0 screen keeps its text legible in the ${theme} theme`,
      scenario({
        id: 'X-05',
        priority: 'P1',
        type: 'edge',
        tags: [
          '@regression',
          '@guest',
          '@parent',
          '@coach',
          '@player',
          '@admin',
          '@cross-cutting',
          '@slow',
        ],
      }),
      async ({ page, actor, suite, provision }) => {
        test.setTimeout(480_000) // 22 screens, each loaded and measured
        const world = await sweepWorld(provision)
        const pages = new Map<Persona, Page>()
        const pageFor = async (persona: Persona): Promise<Page> => {
          let target = pages.get(persona)
          if (!target) {
            if (persona === 'guest') target = page
            else if (persona === 'admin') target = (await actor('admin')).page
            else {
              const account =
                persona === 'parent'
                  ? world.parent
                  : persona === 'coach'
                    ? world.coach
                    : world.player
              target = (await actor(asRole(account))).page
            }
            await useTheme(target, theme)
            pages.set(persona, target)
          }
          return target
        }
        const problems: string[] = []
        for (const stop of world.stops) {
          const target = await pageFor(stop.persona)
          await visit(target, suite, stop)
          for (const low of await lowContrastText(target)) {
            problems.push(
              `${stop.name}: "${low.text}" ${low.ratio}:1 (${low.color} on ${low.background})`,
            )
          }
        }
        expect(problems).toEqual([])
      },
    )
  }

  test(
    'on a 390×844 phone a parent logs in, finds a coach and buys a pack',
    scenario({
      id: 'X-06',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@cross-cutting', '@mobile', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const city = provision.uniqueCity()
      const coachName = `${city} Coaching`
      const coach = await provision.coach({ city, displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await page.setViewportSize({ width: 390, height: 844 })

      const login = await new LoginScreen(page, suite).open()
      await login.logIn(parent.email, parent.password)
      await new ParentDashboardScreen(page, suite).waitReady()

      const market = await new MarketplaceScreen(page, suite).open()
      await market.searchCity(city)
      await market.card(coachName).click()
      const profile = await new CoachPublicProfileScreen(page, suite).waitReady()
      await profile.buySessionsSecondary().click()
      const purchase = await new PackPurchaseScreen(page, suite).waitReady()
      await purchase.packOption().click()
      await expect(purchase.confirm()).toBeEnabled()
      await purchase.confirm().click()

      await profile.waitReady()
      await expect(profile.credits()).toBeVisible()
      expect(coach.coachId).toBeTruthy()
    },
  )

  test(
    'login and a booking request can be completed with the keyboard alone, with visible focus',
    scenario({
      id: 'X-14',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@cross-cutting', '@a11y', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const coach = await provision.coach({ displayName: `${provision.uniqueCity()} Coaching` })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)

      /** Press Tab until the element with this test id has focus; that it was reachable is the point. */
      const tabTo = async (testId: string, max = 60) => {
        for (let i = 0; i < max; i++) {
          const focused = await page.evaluate(
            () =>
              document.activeElement?.closest('[data-testid]')?.getAttribute('data-testid') ?? '',
          )
          if (focused === testId) return
          await page.keyboard.press('Tab')
        }
        throw new Error(`${testId} is not reachable with Tab`)
      }
      /** The focused element shows it: a focus ring, an outline or Quasar's focus helper. */
      const focusIsVisible = () =>
        page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null
          if (!el) return false
          const style = getComputedStyle(el)
          const field = el.closest('.q-field')
          return (
            (style.outlineStyle !== 'none' && style.outlineWidth !== '0px') ||
            style.boxShadow !== 'none' ||
            !!field?.classList.contains('q-field--focused') ||
            !!el.querySelector('.q-focus-helper')
          )
        })

      const login = await new LoginScreen(page, suite).open()
      await tabTo('login-email-input')
      expect(await focusIsVisible()).toBe(true)
      await page.keyboard.type(parent.email)
      await tabTo('login-password-input')
      await page.keyboard.type(parent.password)
      await page.keyboard.press('Enter')
      await new ParentDashboardScreen(page, suite).waitReady()
      expect(login.currentRoute()).not.toBe('/login')

      const profile = await new CoachPublicProfileScreen(page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(),
      )
      await tabTo('coach-primary-cta')
      expect(await focusIsVisible()).toBe(true)
      await page.keyboard.press('Enter')
      const request = await new BookingRequestScreen(page, suite).waitReady()
      await expect(
        request.packOption(
          t('booking.requests.packOptionLabel', { remaining: sessions, total: sessions }),
        ),
      ).toBeChecked()
      await tabTo('booking-submit')
      expect(await focusIsVisible()).toBe(true)
      await page.keyboard.press('Enter')
      await new ParentBookingsScreen(page, suite).waitReady()
      expect(profile).toBeTruthy()
    },
  )

  test(
    'axe finds no accessibility violation beyond each screen’s accepted baseline',
    scenario({
      id: 'X-15',
      priority: 'P2',
      type: 'edge',
      tags: [
        '@nightly',
        '@guest',
        '@parent',
        '@coach',
        '@player',
        '@admin',
        '@cross-cutting',
        '@a11y',
        '@slow',
      ],
    }),
    async ({ page, actor, suite, provision, a11y }) => {
      test.setTimeout(480_000) // 22 screens, each loaded and checked
      const world = await sweepWorld(provision)
      const pages = new Map<Persona, Page>()
      for (const stop of world.stops) {
        let target = pages.get(stop.persona)
        if (!target) {
          if (stop.persona === 'guest') target = page
          else if (stop.persona === 'admin') target = (await actor('admin')).page
          else {
            const account =
              stop.persona === 'parent'
                ? world.parent
                : stop.persona === 'coach'
                  ? world.coach
                  : world.player
            target = (await actor(asRole(account))).page
          }
          pages.set(stop.persona, target)
        }
        await visit(target, suite, stop)
        await a11y.check(target, { screen: stop.name })
      }
    },
  )
})

test.describe('X · Limits mirrored from config', () => {
  test(
    'the pause and batch-size limits a dialog enforces are the configured ones',
    scenario({
      id: 'X-18',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@cross-cutting', '@config', '@slow'],
    }),
    async ({ actor, suite, provision, runtimeConfig }) => {
      test.slow()
      await runtimeConfig.set('pack.pause.maxDays', 9)
      await runtimeConfig.set('booking.batch.maxSize', 2)
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const family = await actor(asRole(parent))

      // Pause: the dialog names, and enforces, the configured maximum.
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await packs.pause(packs.card(coachName)).click()
      await expect(packs.pauseDialog()).toContainText(
        t('booking.packs.pauseDurationHint', { max: 9 }),
      )
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill('10')
      await expect(packs.confirmPause()).toBeDisabled()
      await family.page.keyboard.press('Escape')

      // Batch: after two slots in the basket, a third cannot be added.
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(),
      )
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      await family.page.getByTestId('booking-batch-toggle').click()
      const slots = request.slots()
      await slots.nth(0).click()
      await slots.nth(1).click()
      await expect(slots.nth(2)).toHaveAttribute('aria-disabled', 'true')
    },
  )
})
