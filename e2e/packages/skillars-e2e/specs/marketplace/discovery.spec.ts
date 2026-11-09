import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import {
  CoachPublicProfileScreen,
  CoachSafeguardingScreen,
  MarketplaceScreen,
} from '../../src/screens/index.js'

// D · Marketplace, backfill: what search shows before and after a city, the filters, sort and paging, a coach with
// no reviews or no such coach, the safeguarding badge, the deep link, and when "Message coach" is offered.

test.describe('D · Marketplace — search', () => {
  test(
    'before a city is entered the page prompts for one, with no results and no error',
    scenario({
      id: 'MKT-02',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite }) => {
      const market = await new MarketplaceScreen(page, suite).open()
      await expect(market.prompt()).toBeVisible()
      await expect(market.cards()).toHaveCount(0)
      await expect(market.empty()).toHaveCount(0)
      await expect(market.loading()).toHaveCount(0)
      await expect(page.getByTestId('load-error')).toHaveCount(0)
    },
  )

  test(
    'a city with no coaches says nothing was found, rather than spinning or prompting',
    scenario({
      id: 'MKT-03',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, provision }) => {
      const market = await new MarketplaceScreen(page, suite).open()
      await market.searchCity(provision.uniqueCity())
      await expect(market.empty()).toBeVisible()
      await expect(market.loading()).toHaveCount(0)
      await expect(market.prompt()).toHaveCount(0)
      await expect(market.cards()).toHaveCount(0)
    },
  )

  test(
    'district, age-group and language filters narrow the results, alone and together',
    scenario({
      id: 'MKT-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@guest', '@marketplace', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const city = provision.uniqueCity()
      // Three coaches in one city that differ in exactly one way each from the first.
      const [a, b, c] = await Promise.all([
        provision.coach({
          city,
          displayName: `${city} Alpha`,
          district: 'North',
          languages: ['English'],
          ageGroups: ['AGE_10_12'],
        }),
        provision.coach({
          city,
          displayName: `${city} Bravo`,
          district: 'South',
          languages: ['English'],
          ageGroups: ['AGE_10_12'],
        }),
        provision.coach({
          city,
          displayName: `${city} Charlie`,
          district: 'North',
          languages: ['German'],
          ageGroups: ['ADULT'],
        }),
      ])
      const names = [a, b, c].map((coach) => `${coach.firstName}`)
      expect(names).toHaveLength(3)
      const market = await new MarketplaceScreen(page, suite).open()
      await market.searchCity(city)
      await expect(market.cards()).toHaveCount(3)

      await market.district().fill('North')
      await expect(market.cards()).toHaveCount(2)
      await expect(market.card(`${city} Bravo`)).toHaveCount(0)

      await market.choose(market.language(), 'German')
      await expect(market.cards()).toHaveCount(1)
      await expect(market.card(`${city} Charlie`)).toHaveCount(1)

      await market.clearFilters().click()
      await expect(market.cards()).toHaveCount(3)
      await market.choose(market.ageGroup(), t('marketplace.ageGroup1012'))
      await expect(market.cards()).toHaveCount(2)
      await expect(market.card(`${city} Charlie`)).toHaveCount(0)
    },
  )

  test(
    'sorting by price or by name changes which coach comes first',
    scenario({
      id: 'MKT-05',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@guest', '@marketplace', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const city = provision.uniqueCity()
      // By name Aardvark comes first; by price Zebra (the cheaper) does.
      await Promise.all([
        provision.coach({ city, displayName: `Aardvark ${city}`, perSessionPrice: 90 }),
        provision.coach({ city, displayName: `Zebra ${city}`, perSessionPrice: 30 }),
      ])
      const market = await new MarketplaceScreen(page, suite).open()
      await market.searchCity(city)
      const first = market.cards().first().getByTestId('coach-card-name')
      await expect(market.cards()).toHaveCount(2)
      await expect(first).toHaveText(`Aardvark ${city}`)
      await market.choose(market.sort(), t('marketplace.sortPrice'))
      await expect(first).toHaveText(`Zebra ${city}`)
      await market.choose(market.sort(), t('marketplace.sortName'))
      await expect(first).toHaveText(`Aardvark ${city}`)
    },
  )

  test(
    '"Load more" appends the next page and keeps the filters',
    scenario({
      id: 'MKT-06',
      priority: 'P1',
      type: 'happy',
      tags: ['@nightly', '@guest', '@marketplace', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      // 22 coaches: one more page than the 20 a search returns. Provisioned five at a time.
      test.setTimeout(15 * 60_000)
      const city = provision.uniqueCity()
      for (let start = 0; start < 22; start += 5) {
        await Promise.all(
          Array.from({ length: Math.min(5, 22 - start) }, (_, i) =>
            provision.coach({
              city,
              displayName: `${city} ${String(start + i).padStart(2, '0')}`,
              district: start + i < 21 ? 'East' : 'West',
            }),
          ),
        )
      }
      const market = await new MarketplaceScreen(page, suite).open()
      await market.searchCity(city)
      await market.district().fill('East')
      await expect(market.cards()).toHaveCount(20)
      await expect(market.loadMore()).toBeVisible()
      await market.loadMore().click()
      // The 21st East coach is appended; the West one stays filtered out.
      await expect(market.cards()).toHaveCount(21)
      await expect(market.district()).toHaveValue('East')
      await expect(market.card(`${city} 21`)).toHaveCount(0)
      await expect(market.loadMore()).toHaveCount(0)
    },
  )

  test(
    '/marketplace?city= searches on load; without it the prompt stays',
    scenario({
      id: 'MKT-14',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, provision }) => {
      const city = provision.uniqueCity()
      const coach = await provision.coach({ city, displayName: `${city} Linked` })
      expect(coach.coachId).toBeTruthy()
      await page.goto(suite.routing.toUrl(`${R.marketplace}?city=${encodeURIComponent(city)}`))
      const market = new MarketplaceScreen(page, suite)
      await expect(market.card(`${city} Linked`)).toBeVisible()
      await expect(market.city()).toHaveValue(city)

      await page.goto(suite.routing.toUrl(R.marketplace))
      await page.reload()
      await expect(market.prompt()).toBeVisible()
      await expect(market.cards()).toHaveCount(0)
    },
  )
})

test.describe('D · Marketplace — the coach profile', () => {
  test(
    'a coach with no reviews shows the empty state',
    scenario({
      id: 'MKT-10',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, provision }) => {
      const coach = await provision.coach()
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(profile.reviewsEmpty()).toBeVisible()
      await expect(profile.reviewsEmpty()).toHaveText(t('marketplace.noReviewsYet'))
      await expect(profile.reviews()).toHaveCount(0)
    },
  )

  test(
    'an unknown coach id shows the not-found state, not an empty card',
    scenario({
      id: 'MKT-12',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, consoleGate }) => {
      consoleGate.allow('status of 404', 'status code 404', '[Server Error]')
      const profile = new CoachPublicProfileScreen(page, suite)
      await page.goto(
        suite.routing.toUrl(
          R.coachProfile.replace(':coachId', '00000000-0000-4000-8000-000000000000'),
        ),
      )
      await expect(profile.notFound()).toBeVisible()
      await expect(profile.name()).toHaveCount(0)
    },
  )

  test(
    'the safeguarding badge shows both states, in a neutral colour, on the card and the profile',
    scenario({
      id: 'MKT-13',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@guest', '@coach', '@marketplace', '@slow'],
    }),
    async ({ page, actor, suite, provision }) => {
      test.slow()
      const city = provision.uniqueCity()
      const coach = await provision.coach({ city, displayName: `${city} Checked` })
      const market = await new MarketplaceScreen(page, suite).open()
      const profile = new CoachPublicProfileScreen(page, suite)
      /** Neither badge may wear the warning or danger colour (S-09: no record is not a red flag). */
      const neutral = async (badge: ReturnType<typeof profile.safeguardingBadge>) => {
        const colour = await badge.evaluate((el) => getComputedStyle(el).color)
        const warning = await page.evaluate(() =>
          ['--accent-warning', '--accent-danger'].map((v) =>
            getComputedStyle(document.documentElement).getPropertyValue(v).trim(),
          ),
        )
        const rgb = (hex: string) =>
          hex.startsWith('#')
            ? `rgb(${parseInt(hex.slice(1, 3), 16)}, ${parseInt(hex.slice(3, 5), 16)}, ${parseInt(hex.slice(5, 7), 16)})`
            : hex
        expect(warning.map(rgb)).not.toContain(colour)
      }
      const cardBadge = () =>
        market.card(`${city} Checked`).locator('[data-testid^="safeguarding-badge-"]')

      // No check on record.
      await market.searchCity(city)
      await expect(cardBadge()).toHaveAttribute('data-testid', 'safeguarding-badge-none')
      await neutral(cardBadge())
      await profile.open({ coachId: coach.coachId! })
      await expect(profile.safeguardingBadge()).toHaveText(t('safeguarding.badge.none'))
      await neutral(profile.safeguardingBadge())

      // A check on record (the coach asks, an admin inspects it), revoked afterwards so records don't pile up.
      const self = await actor(asRole(coach))
      const own = await new CoachSafeguardingScreen(self.page, suite).open()
      await own.request().click()
      await expect(own.pending()).toBeVisible()
      const validUntil = await provision.inspectSafeguarding(coach)
      try {
        const [year, month] = validUntil.split('-')
        await page.reload()
        await profile.waitReady()
        await expect(profile.safeguardingBadge()).toHaveAttribute(
          'data-testid',
          'safeguarding-badge-on',
        )
        await expect(profile.safeguardingBadge()).toContainText(`${month}/${year}`)
        await neutral(profile.safeguardingBadge())
        await market.open()
        await market.searchCity(city)
        await expect(cardBadge()).toHaveAttribute('data-testid', 'safeguarding-badge-on')
        await neutral(cardBadge())
      } finally {
        await provision.revokeSafeguarding(coach)
      }
    },
  )

  test(
    '"Message coach" appears only once a booking is confirmed, names the child, and not for an adult child',
    scenario({
      id: 'MKT-15',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@marketplace', '@messaging', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const child = parent.children[0]!
      const family = await actor(asRole(parent))
      const profile = new CoachPublicProfileScreen(family.page, suite)

      // Requested only: no conversation yet.
      await provision.confirmedBooking(parent, coach, { accept: false, weekday: 2 })
      await profile.open({ coachId: coach.coachId! })
      await expect(profile.messageCoach()).toHaveCount(0)

      // Confirmed: offered, about this child.
      await provision.confirmedBooking(parent, coach, { weekday: 3 })
      await family.page.reload()
      await profile.waitReady()
      await expect(profile.messageCoach()).toBeVisible()
      await expect(profile.messageCoach()).toContainText(
        t('messaging.messageCoachAbout', { name: child.name }),
      )

      // An adult child's conversations are theirs (privacy at 18): not offered to the parent.
      const grownUp = await provision.parent({ childAges: [19] })
      await provision.saveCard(grownUp)
      await provision.confirmedBooking(grownUp, coach, { weekday: 4 })
      const other = await actor(asRole(grownUp))
      const adultsProfile = await new CoachPublicProfileScreen(other.page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(adultsProfile.messageCoach()).toHaveCount(0)
    },
  )
})
