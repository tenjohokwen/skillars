import { execSync } from 'node:child_process'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import { HealthDashboardScreen, ReliabilityScreen } from '../../src/screens/index.js'

// N · Reliability and ops: a coach's strikes, and the admin health dashboard.

test.describe('N · Coach reliability', () => {
  test(
    'the reliability page lists each strike with its reason and date',
    scenario({
      id: 'OPS-01',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@ops'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.seedStrike(coach, 'COACH_NO_SHOW', 3)
      await provision.seedStrike(coach, 'COACH_CANCELLATION_UNEXCUSED', 1)

      const self = await actor(asRole(coach))
      const page = await new ReliabilityScreen(self.page, suite).open()
      await expect(page.strikes()).toHaveCount(2)
      const reasons = await page
        .strikes()
        .getByTestId('reliability-strike-reason')
        .allTextContents()
      expect(reasons.sort()).toEqual(
        [
          t('reliability.reasonCoachNoShow'),
          t('reliability.reasonCoachCancellationUnexcused'),
        ].sort(),
      )
      for (const strike of await page.strikes().all()) {
        await expect(strike).toContainText(t('reliability.strikeDate'))
      }
    },
  )

  test(
    'acknowledging a strike records it for good, and the strike can still be appealed',
    scenario({
      id: 'OPS-02',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@ops'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.seedStrike(coach, 'COACH_NO_SHOW', 2)

      const self = await actor(asRole(coach))
      const page = await new ReliabilityScreen(self.page, suite).open()
      const strike = page.strikes().first()
      await expect(page.appeal(strike)).toBeVisible()
      await page.acknowledge(strike).click()
      await expect(strike).toContainText(t('reliability.strikeAcknowledged'))
      await expect(page.acknowledge(strike)).toHaveCount(0)

      await self.page.reload()
      await expect(page.strikes().first()).toContainText(t('reliability.strikeAcknowledged'))
      await expect(page.acknowledge(page.strikes().first())).toHaveCount(0)
    },
  )

  test(
    'a coach with no strikes sees a clean record',
    scenario({
      id: 'OPS-03',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@ops'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const page = await new ReliabilityScreen(self.page, suite).open()
      await expect(page.noStrikes()).toHaveText(t('reliability.noStrikes'))
      await expect(page.strikes()).toHaveCount(0)
    },
  )
})

test.describe('N · Health dashboard', () => {
  test(
    'an admin sees the overall status and a card for the database, the cache and storage',
    scenario({
      id: 'OPS-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@ops'],
    }),
    async ({ actor, suite }) => {
      const admin = await actor('admin')
      const health = await new HealthDashboardScreen(admin.page, suite).open()
      await expect(health.status()).toContainText('UP')
      for (const name of ['db', 'redis', 'storage']) {
        await expect(health.componentStatus(health.component(name))).toHaveText('UP')
      }
      await expect(health.accessDenied()).toHaveCount(0)
    },
  )

  test(
    'a limited admin sees only the overall status and the access notice, with no component detail',
    scenario({
      id: 'OPS-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@ltd-admin', '@ops'],
    }),
    async ({ actor, suite, provision }) => {
      const ltd = await provision.limitedAdmin()
      const self = await actor(asRole(ltd))
      const health = await new HealthDashboardScreen(self.page, suite).open()
      await expect(health.status()).toBeVisible()
      await expect(health.accessDenied()).toContainText(t('admin.health.accessDenied'))
      // Absent from the DOM, not hidden.
      await expect(health.components()).toHaveCount(0)
      await expect(self.page.getByTestId('health-components')).toHaveCount(0)
    },
  )

  test(
    'with storage stopped, its card reads DOWN with the error, and the overall status follows',
    scenario({
      id: 'OPS-06',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@admin', '@ops', '@destructive'],
    }),
    async ({ actor, suite }) => {
      test.slow()
      const compose = 'docker compose -p skillars-e2e -f stack/docker-compose.e2e.yml'
      execSync(`${compose} stop storage`, { stdio: 'ignore' })
      try {
        const admin = await actor('admin')
        const health = await new HealthDashboardScreen(admin.page, suite).open()
        const storage = health.component('storage')
        await expect(health.componentStatus(storage)).toHaveText('DOWN')
        await expect(health.details(storage).first()).toBeVisible()
        await expect(health.status()).toContainText('DOWN')
        // The other components are still reported.
        await expect(health.componentStatus(health.component('db'))).toHaveText('UP')
      } finally {
        execSync(`${compose} start storage`, { stdio: 'ignore' })
        execSync(
          `for i in $(seq 1 30); do [ "$(docker inspect -f '{{.State.Health.Status}}' skillars-e2e-storage-1)" = healthy ] && exit 0; sleep 2; done; exit 1`,
          { stdio: 'ignore', shell: '/bin/sh' },
        )
        // The stack's storage keeps no data across a restart: recreate the bucket.
        execSync(`${compose} up storage-init`, { stdio: 'ignore' })
      }
    },
  )
})
