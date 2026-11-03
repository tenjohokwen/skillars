import { expect, scenario, test } from '../../src/app.js'

test.describe('E · Payment — deterministic Stripe tier', () => {
  test(
    "the backend's Stripe calls are answered by the stack's WireMock, not by Stripe",
    scenario({
      id: 'PAY-90',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@parent', '@payment'],
    }),
    async ({ baseline, provision }) => {
      // Card setup creates the Stripe customer on first use, then a SetupIntent: two SDK calls. The
      // stack's key is a placeholder that real Stripe would reject, so a client secret in the
      // WireMock stub's shape proves both calls went to the stub (StripeE2eApiBaseConfig).
      const parent = await baseline('parent')
      const session = await provision.login(parent)
      try {
        const body = await session.api.json<{ clientSecret: string }>(
          'POST',
          '/payment/setup-intent',
        )
        expect(body.clientSecret).toMatch(/^seti_e2e_[A-Za-z0-9]{14}_secret_e2e$/)
      } finally {
        await session.dispose()
      }
    },
  )
})
