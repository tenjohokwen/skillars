import { failFirst, type SeedContext } from '../../src/index.js'
import { expect, HomeScreen, LoginScreen, test, toy } from './toySuite.js'

// Self-tests of the kernel against the toy app. Each one proves a fixture in a real browser.

test.describe('sessions and actors', () => {
  test(
    'the default page is a guest and is redirected to login',
    toy.scenario({ id: 'KRN-01', priority: 'P0', tags: ['@smoke', '@guest', '@kernel'] }),
    async ({ page, suite, settledRoute }) => {
      await page.goto(suite.routing.toUrl('/home'))
      expect(await settledRoute('/login')).toBe('/login')
      await new LoginScreen(page, suite).waitReady()
    },
  )

  test(
    'an actor is logged in through the API and primed before first paint',
    toy.scenario({ id: 'KRN-02', priority: 'P0', tags: ['@smoke', '@member', '@kernel'] }),
    async ({ actor, suite }) => {
      const { page } = await actor('member')
      const home = await new HomeScreen(page, suite).open()
      await expect(home.role()).toHaveText('member')
      await expect(home.fingerprint()).toHaveText('fixed-fingerprint')
    },
  )

  test(
    'two personas act side by side in one test',
    toy.scenario({
      id: 'KRN-03',
      priority: 'P1',
      tags: ['@regression', '@member', '@admin', '@kernel'],
    }),
    async ({ actor, suite }) => {
      const member = await actor('member')
      const admin = await actor('admin')
      await expect((await new HomeScreen(member.page, suite).open()).role()).toHaveText('member')
      await expect((await new HomeScreen(admin.page, suite).open()).role()).toHaveText('admin')
      expect(member.context).not.toBe(admin.context)
    },
  )

  test(
    'the login form works when driven through a screen object',
    toy.scenario({ id: 'KRN-04', priority: 'P1', tags: ['@regression', '@guest', '@kernel'] }),
    async ({ page, suite, settledRoute }) => {
      const login = await new LoginScreen(page, suite).open()
      await login.username().fill('member')
      await login.password().fill('wrong')
      await login.submit().click()
      await expect(login.error()).toHaveText('Wrong credentials')
      await login.password().fill('secret')
      await login.submit().click()
      await settledRoute('/home')
    },
  )
})

test.describe('routing', () => {
  test(
    'settledRoute waits through intermediate redirects',
    toy.scenario({ id: 'KRN-05', priority: 'P1', tags: ['@regression', '@guest', '@kernel'] }),
    async ({ gotoRoute, settledRoute, page }) => {
      await gotoRoute('/chain')
      expect(await settledRoute('/landing/:id(\\d+)')).toBe('/landing/42')
      await expect(page.getByTestId('landing-title')).toHaveText('Landing 42')
    },
  )
})

test.describe('gates', () => {
  test(
    'the console gate fails a test whose page logs an error',
    toy.scenario({ id: 'KRN-06', priority: 'P0', tags: ['@smoke', '@guest', '@kernel'] }),
    async ({ gotoRoute, page }) => {
      test.fail(true, 'expected: the console gate must turn a console.error into a failure')
      await gotoRoute('/broken')
      await expect(page.getByTestId('broken-title')).toBeVisible()
    },
  )

  test.describe('in warn mode', () => {
    test.use({ consoleGateMode: 'warn', networkGateMode: 'warn' })

    test(
      'violations are recorded but do not fail the test',
      toy.scenario({ id: 'KRN-07', priority: 'P1', tags: ['@regression', '@guest', '@kernel'] }),
      async ({ gotoRoute, page, consoleGate, networkGate }) => {
        await gotoRoute('/broken')
        await expect(page.getByTestId('broken-title')).toBeVisible()
        await gotoRoute('/leaky')
        await expect(page.getByTestId('leaky-title')).toBeVisible()
        await expect.poll(() => networkGate.violations().length).toBe(1)
        expect(networkGate.violations()[0]?.url).toBe('https://tracker.example.invalid/pixel.png')
        expect(consoleGate.violations().map((v) => v.kind)).toEqual(['console.error'])
      },
    )
  })

  test(
    'the network gate fails a test whose page reaches a host outside the allow-list',
    toy.scenario({ id: 'KRN-08', priority: 'P0', tags: ['@smoke', '@guest', '@kernel'] }),
    async ({ gotoRoute, page }) => {
      test.fail(true, 'expected: the network gate must turn a third-party request into a failure')
      await gotoRoute('/leaky')
      await expect(page.getByTestId('leaky-title')).toBeVisible()
      await page.waitForLoadState('networkidle')
    },
  )

  test(
    'the a11y gate passes an accessible screen',
    toy.scenario({
      id: 'KRN-09',
      priority: 'P2',
      tags: ['@nightly', '@guest', '@kernel', '@a11y'],
    }),
    async ({ page, suite, a11y }) => {
      await new LoginScreen(page, suite).open()
      await expect(a11y.check(page, { screen: 'LoginScreen' })).resolves.toEqual([])
    },
  )
})

test.describe('network stubs', () => {
  test(
    'prepareContext stubs a third-party script on every context, ahead of the network gate',
    toy.scenario({ id: 'KRN-14', priority: 'P1', tags: ['@regression', '@member', '@kernel'] }),
    async ({ gotoRoute, page, actor, suite, networkGate }) => {
      await gotoRoute('/widget')
      await expect(page.getByTestId('widget-status')).toHaveText('stubbed')
      const member = await actor('member')
      await member.page.goto(suite.routing.toUrl('/widget'))
      await expect(member.page.getByTestId('widget-status')).toHaveText('stubbed')
      expect(networkGate.violations()).toEqual([])
    },
  )

  test(
    'failFirst produces an error state that recovers on retry',
    toy.scenario({ id: 'KRN-10', priority: 'P1', tags: ['@regression', '@guest', '@kernel'] }),
    async ({ page, gotoRoute }) => {
      const outage = await failFirst(page, '**/api/data', { status: 500 })
      await gotoRoute('/data')
      await expect(page.getByTestId('data-error')).toBeVisible()
      await page.getByTestId('data-retry-btn').click()
      await expect(page.getByTestId('data-value')).toHaveText('fresh data')
      expect(outage.failed()).toBe(1)
    },
  )
})

test.describe('data and mail', () => {
  test(
    'seed specs get collision-free ids and unique names from the worker',
    toy.scenario({ id: 'KRN-11', priority: 'P1', tags: ['@regression', '@guest', '@kernel'] }),
    async ({ seed, ids }) => {
      const make = (label: string) => ({
        kind: 'item',
        seed: async ({ ids: allocator, names, api }: SeedContext) => {
          const item = { id: allocator.nextString(), owner: names.email(label) }
          await api?.post('/items', { data: item, expectStatus: 201 })
          return item
        },
      })
      const first = await seed(make('owner'))
      const second = await seed(make('owner'))
      expect(first.id).not.toBe(second.id)
      expect(first.owner).not.toBe(second.owner)
      expect(ids.owns(BigInt(first.id))).toBe(true)
    },
  )

  test(
    'a mail sink closes the loop from sign-up email to the confirmation page',
    toy.scenario({ id: 'KRN-12', priority: 'P0', tags: ['@smoke', '@guest', '@kernel'] }),
    async ({ api, names, mail, page }) => {
      const email = names.email('signup')
      const since = new Date(Date.now() - 1000)
      await api.post('/signup', { data: { email }, expectStatus: 202 })
      const message = await mail.waitForMessage({ to: email, subjectMatches: /confirm/i, since })
      const code = message.code()
      const link = message.links()[0]
      expect(link).toBeDefined()
      await page.goto(link!)
      await expect(page.getByTestId('confirm-code')).toHaveText(code)
    },
  )
})

test.describe('quarantine', () => {
  // Declared with test.fail(...) rather than calling test.fail() in the body: the guard throws
  // before the body ever runs.
  test.fail(
    'an expired quarantine fails instead of hiding forever',
    toy.scenario({
      id: 'KRN-13',
      priority: 'P2',
      tags: ['@regression', '@guest', '@kernel'],
      quarantine: {
        owner: 'kernel-maintainers',
        since: '2020-01-01',
        reason: 'self-test of the guard',
      },
    }),
    async () => {
      // Unreachable: the quarantine guard fails the test during fixture setup.
    },
  )
})
