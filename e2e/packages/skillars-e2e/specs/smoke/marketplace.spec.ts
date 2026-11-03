import { expect, scenario, test } from '../../src/app.js'
import { t } from '../../src/i18n.js'
import { CoachPublicProfileScreen } from '../../src/screens/index.js'

test.describe('D · Marketplace — smoke', () => {
  test(
    'a guest sees "Sign up to book" on a published coach',
    scenario({
      id: 'MKT-11',
      priority: 'P1',
      type: 'edge',
      tags: ['@smoke', '@guest', '@marketplace'],
    }),
    async ({ page, suite, baseline }) => {
      const coach = await baseline('coach')
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(profile.primaryCta()).toHaveText(t('marketplace.signUpToBook'))
      await expect(profile.buySessionsSecondary()).toHaveCount(0)
    },
  )

  test(
    'a parent without credits can book a priced coach per session, with packs offered alongside',
    scenario({
      id: 'BKG-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@parent', '@coach', '@marketplace', '@booking'],
    }),
    async ({ actor, suite, baseline }) => {
      const coach = await baseline('coach')
      const { page } = await actor('parent')
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(profile.primaryCta()).toHaveText(t('marketplace.bookSession'))
      await expect(profile.buySessionsSecondary()).toHaveText(t('booking.packs.buySessions'))
    },
  )
})
