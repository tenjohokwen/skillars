import type { Page } from '@playwright/test'
import { measureHorizontalOverflow, interpolate } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import {
  sweepWorld,
  visit,
  type Persona,
  type Stop,
  type SweepWorld,
} from '../../src/data/sweep.js'
import { E2E } from '../../src/env.js'
import { MESSAGE_NAMESPACES } from '../../src/i18n.js'
import { R } from '../../src/routes.js'

// X · Cross-cutting, part 1: sweeps over the P0 screens of every persona. Each check runs on all of them, so a
// regression on any screen fails one named test. The console and network gates are strict on every page these
// tests open; X-01 and X-02 make that their whole point and assert it explicitly.

type Fixtures = {
  page: Page
  actor: (role: string) => Promise<{ page: Page }>
}

/** One page per persona, logged in as that persona (the guest uses the anonymous default page). */
function personaPages(fixtures: Fixtures, world: SweepWorld) {
  const pages = new Map<Persona, Page>()
  return async (persona: Persona): Promise<Page> => {
    const known = pages.get(persona)
    if (known) return known
    let page: Page
    if (persona === 'guest') page = fixtures.page
    else if (persona === 'admin') page = (await fixtures.actor('admin')).page
    else {
      const account =
        persona === 'parent' ? world.parent : persona === 'coach' ? world.coach : world.player
      page = (await fixtures.actor(asRole(account))).page
    }
    pages.set(persona, page)
    return page
  }
}

/** Raw message keys left on screen (`booking.packs.title`), by the app's real namespaces. */
const RAW_KEY = new RegExp(
  // Not part of an email address: user text such as coach.e2e@example.com is not a message key.
  `(?<![\\w.@-])(?:${MESSAGE_NAMESPACES.join('|')})\\.[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*\\b(?![@.-]\\w)`,
  'g',
)

test.describe('X · Sweeps over the P0 screens', () => {
  test(
    'no console error or uncaught exception on any P0 screen',
    scenario({
      id: 'X-01',
      priority: 'P0',
      type: 'neg',
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
    async ({ page, actor, suite, provision, consoleGate }) => {
      test.slow()
      const world = await sweepWorld(provision)
      const pageFor = personaPages({ page, actor }, world)
      for (const stop of world.stops) {
        await visit(await pageFor(stop.persona), suite, stop)
      }
      expect(consoleGate.violations()).toEqual([])
    },
  )

  test(
    'no request leaves the allow-listed hosts, money pages included',
    scenario({
      id: 'X-02',
      priority: 'P1',
      type: 'neg',
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
    async ({ page, actor, suite, provision, networkGate }) => {
      test.slow()
      const world = await sweepWorld(provision)
      const pageFor = personaPages({ page, actor }, world)
      // The pages that load payment and video SDKs, and the parent's money pages.
      const money: Stop[] = [
        {
          persona: 'parent',
          name: 'pack purchase',
          route: interpolate(R.purchasePack, { coachId: world.coach.coachId! }),
          ready: 'purchase-title',
        },
        {
          persona: 'parent',
          name: 'request booking',
          route: interpolate(R.requestBooking, { coachId: world.coach.coachId! }),
          ready: 'booking-request-title',
        },
        ...world.stops.filter((stop) =>
          ['credit wallet', 'child packs', 'revenue', 'player videos'].includes(stop.name),
        ),
      ]
      for (const stop of money) await visit(await pageFor(stop.persona), suite, stop)
      expect(networkGate.violations()).toEqual([])
    },
  )

  test(
    'every P0 screen renders in fr-FR with no raw message key and no sideways overflow',
    scenario({
      id: 'X-03',
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
        '@i18n',
        '@slow',
      ],
    }),
    async ({ page, actor, suite, provision }) => {
      test.slow()
      const world = await sweepWorld(provision)
      const pageFor = personaPages({ page, actor }, world)
      const problems: string[] = []
      const french = new Set<Page>()
      for (const stop of world.stops) {
        const target = await pageFor(stop.persona)
        if (!french.has(target)) {
          await target.context().addInitScript(() => {
            try {
              localStorage.setItem('locale', 'fr-FR')
            } catch {
              // storage unavailable: the page stays English and the check below says so
            }
          })
          french.add(target)
        }
        await visit(target, suite, stop)
        // The header (every logged-in page) names the active language: proof the sweep is really in French.
        const menu = target.getByTestId('language-menu-btn')
        if ((await menu.count()) > 0) await expect(menu).toContainText('Français')
        const text = await target.locator('body').innerText()
        const raw = [...new Set(text.match(RAW_KEY) ?? [])]
        if (raw.length) problems.push(`${stop.name}: raw keys ${raw.join(', ')}`)
        const overflow = await measureHorizontalOverflow(target)
        if (overflow.overflows) {
          problems.push(
            `${stop.name}: ${overflow.scrollWidth}px wide in a ${overflow.clientWidth}px window`,
          )
        }
      }
      expect(problems).toEqual([])
    },
  )

  test(
    'no P0 screen scrolls sideways at a 390 px phone width',
    scenario({
      id: 'X-07',
      priority: 'P1',
      type: 'neg',
      tags: [
        '@regression',
        '@guest',
        '@parent',
        '@coach',
        '@player',
        '@admin',
        '@cross-cutting',
        '@mobile',
        '@slow',
      ],
    }),
    async ({ page, actor, suite, provision }) => {
      test.slow()
      const world = await sweepWorld(provision)
      const pageFor = personaPages({ page, actor }, world)
      const wide: string[] = []
      for (const stop of world.stops) {
        const target = await pageFor(stop.persona)
        await target.setViewportSize({ width: 390, height: 844 })
        await visit(target, suite, stop)
        const overflow = await measureHorizontalOverflow(target)
        if (overflow.overflows) wide.push(`${stop.name}: ${overflow.scrollWidth}px`)
      }
      expect(wide).toEqual([])
    },
  )

  test(
    'a hard reload on a deep authenticated route comes back to the same page',
    scenario({
      id: 'X-13',
      priority: 'P0',
      type: 'edge',
      tags: ['@regression', '@guest', '@parent', '@coach', '@player', '@admin', '@cross-cutting'],
    }),
    async ({ page, actor, suite, provision }) => {
      test.slow() // provisions the sweep's family and coach
      const world = await sweepWorld(provision)
      const pageFor = personaPages({ page, actor }, world)
      const deep = world.stops.filter((stop) =>
        ['child packs', 'child hub', 'availability', 'admin users', 'credit wallet'].includes(
          stop.name,
        ),
      )
      for (const stop of deep) {
        const target = await pageFor(stop.persona)
        await visit(target, suite, stop)
        await target.reload()
        await target.getByTestId(stop.ready!).first().waitFor({ state: 'visible' })
        expect(suite.routing.fromUrl(target.url()), stop.name).toBe(stop.route)
      }
    },
  )
})

test.describe('X · HTTP surface', () => {
  test(
    'every document response carries the security headers',
    scenario({
      id: 'X-16',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@cross-cutting', '@security'],
    }),
    async ({ page, suite }) => {
      // The app shell is one document whatever the hash route, so each URL the server can serve a document
      // for is fetched directly (a second hash navigation in a loaded page makes no document request at all).
      const first = await page.goto(suite.routing.toUrl(R.login))
      const documents = [first!.headers()]
      for (const path of ['/', '/index.html']) {
        documents.push((await page.request.get(`${E2E.baseUrl}${path}`)).headers())
      }
      for (const headers of documents) {
        expect(headers['x-frame-options']).toBe('SAMEORIGIN')
        expect(headers['content-security-policy']).toContain("frame-ancestors 'self'")
        expect(headers['referrer-policy']).toBeTruthy()
        expect(headers['permissions-policy']).toBeTruthy()
      }
    },
  )

  test(
    'an unmapped /api path answers a JSON error, never the app shell',
    scenario({
      id: 'X-17',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@parent', '@cross-cutting'],
    }),
    async ({ playwright, provision }) => {
      // Logged in: 404. A guest gets 401 first; either way it is JSON, not index.html.
      const parent = await provision.parent()
      const session = await provision.login(parent)
      try {
        const signedIn = await session.api.get('/this-endpoint-does-not-exist', {
          expectStatus: () => true,
        })
        expect(signedIn.status()).toBe(404)
        expect(signedIn.headers()['content-type']).toContain('json')
      } finally {
        await session.dispose()
      }
      const anonymous = await playwright.request.newContext()
      try {
        const guest = await anonymous.get(`${E2E.baseUrl}/api/this-endpoint-does-not-exist`)
        expect(guest.status()).toBeGreaterThanOrEqual(400)
        expect(guest.headers()['content-type']).toContain('json')
        expect(await guest.text()).not.toContain('<div id="q-app"')
      } finally {
        await anonymous.dispose()
      }
    },
  )
})
