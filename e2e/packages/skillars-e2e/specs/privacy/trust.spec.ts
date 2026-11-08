import type { Page, Request } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import {
  AgeVerificationScreen,
  CoachPublicProfileScreen,
  CoachSafeguardingScreen,
  PlayerDashboardScreen,
} from '../../src/screens/index.js'

// Q · Privacy, part 2: Stripe Identity age verification (S-27) and the coach safeguarding check (S-09).
//
// Age verification is off on the stack, and Stripe Identity is a hosted page: the page's states are driven by
// answering its status endpoint in the browser (C-17), and the start request by answering the session endpoint
// with a local stub URL. Stripe is never contacted; a spec asserts that. One state, VERIFIED, also comes from
// the real backend (markAgeVerified). The safeguarding check runs on the real API; the admin's inspection is
// the provisioner's (ADM-14 in suite R drives it through the console).

type AgeState = 'NOT_STARTED' | 'PENDING' | 'VERIFIED' | 'FAILED' | 'BLOCKED'

/** Answers GET /api/security/age-verification with this status; returns how many times it was asked. */
async function ageStatus(
  page: Page,
  status: { state: AgeState; enabled?: boolean; attemptsRemainingToday?: number },
): Promise<() => number> {
  let calls = 0
  await page.unroute('**/api/security/age-verification')
  await page.route('**/api/security/age-verification', async (route) => {
    calls += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        applicable: true,
        enabled: status.enabled ?? true,
        state: status.state,
        attemptsRemainingToday: status.attemptsRemainingToday ?? 3,
      }),
    })
  })
  return () => calls
}

/** /player/age-verification, optionally as the gate opens it (with where to go back to). */
async function openAgePage(
  page: Page,
  suite: ConstructorParameters<typeof AgeVerificationScreen>[1],
  query = '',
) {
  await page.goto(suite.routing.toUrl(`/player/age-verification${query}`))
  // A hash change inside an already-loaded app does not re-render the page: reload, so it asks for the status anew.
  await page.reload()
  return new AgeVerificationScreen(page, suite).waitReady()
}

/** The part of a message before its {date} placeholder (the date itself is locale-formatted). */
const before = (key: string) => t(key, { date: '\u0000' }).split('\u0000')[0]!.trim()

test.describe('Q · Age verification', () => {
  test(
    'the age-verification page shows each state the status endpoint reports',
    scenario({
      id: 'PRV-08',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@player', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const page = self.page

      // Switched off: says so, and offers nothing to start.
      await ageStatus(page, { state: 'NOT_STARTED', enabled: false })
      let age = await openAgePage(page, suite)
      await expect(age.unavailable()).toHaveText(t('player.ageVerification.unavailable'))
      await expect(age.start()).toHaveCount(0)

      // Checking a document: says so, and keeps asking until the answer changes.
      const asked = await ageStatus(page, { state: 'PENDING' })
      age = await openAgePage(page, suite)
      await expect(age.pending()).toContainText(t('player.ageVerification.pending'))
      await expect(age.start()).toContainText(t('player.ageVerification.resume'))
      await expect.poll(asked, { timeout: 15_000 }).toBeGreaterThanOrEqual(2)

      // Verified, reached through the gate: offers the way back to where the player was.
      await ageStatus(page, { state: 'VERIFIED' })
      age = await openAgePage(page, suite, '?next=/marketplace&required=1')
      await expect(age.verified()).toContainText(t('player.ageVerification.verified'))
      await expect(age.required()).toHaveCount(0)
      await age.continue().click()
      await expect.poll(() => age.currentRoute()).toBe('/marketplace')

      // Failed: says why, and the button retries.
      await ageStatus(page, { state: 'FAILED' })
      age = await openAgePage(page, suite)
      await expect(age.failed()).toHaveText(t('player.ageVerification.failed'))
      await expect(age.start()).toContainText(t('player.ageVerification.retry'))

      // Under 18: blocked, nothing to retry.
      await ageStatus(page, { state: 'BLOCKED' })
      age = await openAgePage(page, suite)
      await expect(age.blocked()).toContainText(t('player.ageVerification.blocked'))
      await expect(age.start()).toHaveCount(0)
    },
  )

  test(
    'starting verification goes to the session URL the API returns, and never contacts Stripe',
    scenario({
      id: 'PRV-09',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const page = self.page
      const stripe: string[] = []
      page.on('request', (request: Request) => {
        if (/(^|\.)stripe\.com$/.test(new URL(request.url()).hostname)) stripe.push(request.url())
      })
      const hosted = 'https://identity-stub.e2e.test/session/e2e'
      await page.route('**/api/security/age-verification/session', (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ state: 'PENDING', url: hosted }),
        }),
      )
      await page.route('https://identity-stub.e2e.test/**', (route) =>
        route.fulfill({ status: 200, contentType: 'text/html', body: '<h1>Hosted check</h1>' }),
      )
      await ageStatus(page, { state: 'NOT_STARTED' })

      const age = await openAgePage(page, suite, '?next=/marketplace&required=1')
      await expect(age.required()).toHaveText(t('player.ageVerification.required'))
      await age.start().click()
      await page.waitForURL(hosted)
      expect(stripe).toEqual([])
    },
  )

  test(
    'with no attempts left today, the start button is disabled and says so',
    scenario({
      id: 'PRV-10',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@player', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      await ageStatus(self.page, { state: 'FAILED', attemptsRemainingToday: 0 })
      const age = await openAgePage(self.page, suite)
      await expect(age.exhausted()).toHaveText(t('player.ageVerification.attemptsExhausted'))
      await expect(age.start()).toBeDisabled()
      await expect(self.page.getByTestId('age-verification-page')).toContainText(
        t('player.ageVerification.attemptsLeft', { count: 0 }),
      )
    },
  )

  test(
    "the player dashboard's age-verification tile reflects the status",
    scenario({
      id: 'PRV-11',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@player', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      // Feature off and not verified: no tile at all.
      const fresh = await provision.adultPlayer()
      const freshSide = await actor(asRole(fresh))
      const freshHome = await new PlayerDashboardScreen(freshSide.page, suite).open()
      await expect(freshHome.ageTile()).toHaveCount(0)

      // Verified on the real backend: the tile shows it, even with the feature off.
      const verified = await provision.adultPlayer()
      await provision.markAgeVerified(verified)
      const verifiedSide = await actor(asRole(verified))
      const verifiedHome = await new PlayerDashboardScreen(verifiedSide.page, suite).open()
      await expect(verifiedHome.ageTile()).toHaveAttribute('data-state', 'VERIFIED')
      await expect(verifiedHome.ageTile()).toContainText(
        t('player.ageVerification.dashboardVerified'),
      )

      // With the feature on, the other states (answered in the browser).
      for (const [state, text] of [
        ['NOT_STARTED', t('player.ageVerification.dashboardPending')],
        ['FAILED', t('player.ageVerification.dashboardFailed')],
        ['BLOCKED', t('player.ageVerification.blocked')],
      ] as const) {
        await ageStatus(freshSide.page, { state })
        await freshSide.page.reload()
        await freshHome.waitReady()
        await expect(freshHome.ageTile()).toHaveAttribute('data-state', state)
        await expect(freshHome.ageTile()).toContainText(text)
      }
      // The tile opens the verification page.
      await freshHome.ageTile().click()
      await new AgeVerificationScreen(freshSide.page, suite).waitReady()
    },
  )
})

/** A minimal, well-formed one-page PDF (the upload is sniffed for its type). */
function pdf(name = 'certificate.pdf') {
  const body = [
    '%PDF-1.4',
    '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj',
    '2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj',
    '3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >> endobj',
    'trailer << /Root 1 0 R >>',
    '%%EOF',
  ].join('\n')
  return { name, mimeType: 'application/pdf', buffer: Buffer.from(body, 'latin1') }
}

test.describe('Q · Coach safeguarding check', () => {
  test(
    'a coach uploads a certificate, sees it pending with its deletion date, and can withdraw it',
    scenario({
      id: 'PRV-12',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@privacy'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const page = await new CoachSafeguardingScreen(self.page, suite).open()
      await expect(page.status()).toContainText(t('safeguarding.coach.status.NONE'))

      // Over 5 MB: refused in the browser, nothing sent.
      await page.fileInput().setInputFiles({
        name: 'too-big.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.alloc(5 * 1024 * 1024 + 1, 1),
      })
      await expect(page.message()).toHaveText(t('safeguarding.coach.fileRejected'))

      await page.fileInput().setInputFiles(pdf())
      await page.request().click()
      await expect(page.notification(t('safeguarding.coach.requestSuccess'))).toBeVisible()
      await expect(page.status()).toContainText(t('safeguarding.coach.status.PENDING'))
      await expect(page.pending()).toContainText(before('safeguarding.coach.uploadDeletesOn'))
      await expect(page.pending()).toContainText(before('safeguarding.coach.requestedOn'))

      // Withdraw asks first, then removes everything.
      await page.withdraw().click()
      await expect(page.withdrawDialog()).toContainText(t('safeguarding.coach.withdrawBody'))
      await page.withdrawConfirm().click()
      await expect(page.notification(t('safeguarding.coach.withdrawSuccess'))).toBeVisible()
      await expect(page.status()).toContainText(t('safeguarding.coach.status.NONE'))
      await expect(page.pending()).toHaveCount(0)
    },
  )

  test(
    'once an admin inspects the check, the coach and the public profile show it on record until its date',
    scenario({
      id: 'PRV-13',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@guest', '@privacy'],
    }),
    async ({ actor, page, suite, provision }) => {
      const coach = await provision.coach()
      const profile = new CoachPublicProfileScreen(page, suite)
      await profile.open({ coachId: coach.coachId! })
      await expect(profile.safeguardingBadge()).toHaveText(t('safeguarding.badge.none'))

      // The coach asks without attaching a file (staff inspect another way).
      const self = await actor(asRole(coach))
      const own = await new CoachSafeguardingScreen(self.page, suite).open()
      await own.request().click()
      await expect(own.pending()).toContainText(t('safeguarding.coach.noUploadNote'))

      // The record is revoked afterwards: on a long-lived stack, valid records would pile up ahead of ADM-14's.
      const validUntil = await provision.inspectSafeguarding(coach)
      const [year, month] = validUntil.split('-')
      const shown = `${month}/${year}`

      await self.page.reload()
      await own.waitReady()
      await expect(own.status()).toContainText(t('safeguarding.coach.status.VERIFIED'))
      await expect(own.valid()).toHaveText(t('safeguarding.coach.validUntil', { date: shown }))

      await page.reload()
      await profile.waitReady()
      await expect(profile.safeguardingBadge()).toHaveAttribute(
        'data-testid',
        'safeguarding-badge-on',
      )
      await expect(profile.safeguardingBadge()).toContainText(
        t('safeguarding.badge.onRecord', { date: shown }),
      )
      await provision.revokeSafeguarding(coach)
    },
  )
})
