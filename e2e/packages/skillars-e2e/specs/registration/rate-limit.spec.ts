import { execSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, scenario, test } from '../../src/app.js'
import { scrollToEnd, EmailPendingScreen, RegisterScreen } from '../../src/screens/index.js'

// B · Registration, backfill: the per-IP registration limit (3 an hour). The e2e stack runs with rate limiting
// off, because every worker shares one IP (Blocker 2), and the switch is read once at startup. So this test
// restarts the app with limits on, registers four times from one address, and restores the stack.

const compose = 'docker compose -p skillars-e2e -f stack/docker-compose.e2e.yml'

function waitHealthy(): void {
  execSync(
    `for i in $(seq 1 90); do [ "$(docker inspect -f '{{.State.Health.Status}}' skillars-e2e-app-1)" = healthy ] && exit 0; sleep 3; done; exit 1`,
    { stdio: 'ignore', shell: '/bin/sh' },
  )
}

test.describe('B · Registration — rate limit', () => {
  test(
    'a fourth registration from one address within the hour is refused with 429',
    scenario({
      id: 'REG-18',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@registration', '@ratelimit', '@destructive', '@slow'],
    }),
    async ({ page, suite, provision, consoleGate }) => {
      test.setTimeout(10 * 60_000) // two app restarts
      consoleGate.allow('status of 429', 'status code 429', '[Server Error]')
      const dir = mkdtempSync(join(tmpdir(), 'pitchside-ratelimit-'))
      const override = join(dir, 'ratelimit.yml')
      writeFileSync(
        override,
        'services:\n  app:\n    environment:\n      - RATE_LIMITING_ENABLED=true\n',
      )
      try {
        execSync(`${compose} -f ${override} up -d app`, { stdio: 'ignore' })
        waitHealthy()

        // Three registrations from this address are accepted.
        for (let i = 0; i < 3; i++) {
          const who = provision.identity('parent')
          const response = await page.request.post(`${suite.apiBaseUrl}/security/parent/register`, {
            data: { ...who, langKey: 'en' },
          })
          expect(response.status(), `registration ${i + 1}`).toBe(200)
        }

        // The fourth, through the form, is refused and the page says so.
        const register = await new RegisterScreen(page, suite, 'parent').open()
        await register.fillDetails(provision.identity('parent'))
        await scrollToEnd(register.tosScrollBox())
        await scrollToEnd(register.privacyScrollBox())
        await scrollToEnd(register.parentConsentScrollBox())
        await register.tos().click()
        await register.privacy().click()
        await register.parentConsent().click()
        const refused = page.waitForResponse((r) => r.url().includes('/security/parent/register'))
        await register.submit().click()
        const response = await refused
        expect(response.status(), await response.text()).toBe(429)
        await expect(register.error()).toBeVisible()
        await expect(
          page.getByTestId(new EmailPendingScreen(page, suite, 'parent').readyTestId),
        ).toHaveCount(0)
      } finally {
        // Back to the stack every other test expects: limits off, and a fresh JVM's empty buckets.
        execSync(`${compose} up -d app`, { stdio: 'ignore' })
        waitHealthy()
        rmSync(dir, { recursive: true, force: true })
      }
    },
  )
})
