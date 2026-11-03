import { expect, scenario, test } from '../../src/app.js'
import { CoachPublicProfileScreen, MarketplaceScreen } from '../../src/screens/index.js'

// Every search uses a city no other run or worker has used, so the results are exactly the coaches
// the test seeded, whatever else the stack holds.

test.describe('D · Marketplace & discovery', () => {
  test(
    'a guest searches a city and sees its coach, with location and price',
    scenario({
      id: 'MKT-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, provision }) => {
      const city = provision.uniqueCity()
      const displayName = `${city} Football`
      await provision.coach({ city, displayName, perSessionPrice: 55 })

      const marketplace = await new MarketplaceScreen(page, suite).open()
      await expect(marketplace.prompt()).toBeVisible()
      await marketplace.searchCity(city)

      await expect(marketplace.cards()).toHaveCount(1)
      const card = marketplace.card(displayName)
      await expect(card).toBeVisible()
      await expect(marketplace.cardLocation(card)).toContainText(city)
      await expect(marketplace.cardPrice(card)).toContainText('55.00')

      await card.click()
      const profile = await new CoachPublicProfileScreen(page, suite).waitReady()
      await expect(profile.name()).toHaveText(displayName)
    },
  )

  test(
    'a coach who has not finished the builder never appears in results',
    scenario({
      id: 'MKT-07',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, provision }) => {
      // Visibility is gated on all five builder steps. The control is a published coach in the
      // same city, so an empty result can't pass for the wrong reason.
      const city = provision.uniqueCity()
      // Distinct names: every provisioned coach's first name is "Coach".
      const published = `${city} Published`
      const draft = `${city} Draft`
      await provision.coach({ city, displayName: published })
      await provision.onboardCoach(await provision.incompleteCoach(), {
        city,
        displayName: draft,
        stopAfterStep: 4,
      })

      const marketplace = await new MarketplaceScreen(page, suite).open()
      await marketplace.searchCity(city)
      await expect(marketplace.card(published)).toBeVisible()
      await expect(marketplace.cards()).toHaveCount(1)
      await expect(marketplace.card(draft)).toHaveCount(0)
    },
  )

  test(
    'a coach’s public profile renders bio, languages, specialties, price and packs',
    scenario({
      id: 'MKT-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@guest', '@marketplace'],
    }),
    async ({ page, suite, baseline }) => {
      // The console gate fails the test on any console error: this page once shipped a crash on
      // averageRating that only showed up in the browser.
      const coach = await baseline('coach')
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(profile.name()).toHaveText(`${coach.firstName} Coaching`)
      await expect(profile.bio()).toHaveText('Technical coaching for young players.')
      await expect(profile.languages()).toContainText('English')
      await expect(profile.location()).toContainText('Testville')
      await expect(profile.specialties()).toContainText('Dribbling')
      await expect(profile.specialties()).toContainText('Finishing')
      await expect(profile.price()).toContainText('55')
      await expect(profile.packs()).toContainText('Starter 5')
    },
  )
})
