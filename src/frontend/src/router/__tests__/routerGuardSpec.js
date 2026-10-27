// skillars-deferred-148 AC3/AC4 — router/index.js's beforeEach guard.
//
// No spec under src/frontend/src/router/__tests__ exercises router/index.js's beforeEach today
// (safeRedirectSpec.js tests the sibling safeRedirect.js module, not this file) — confirmed by
// `find` at story-creation time. This is a from-scratch test vehicle.
//
// `defineRouter` (from '#q-app/wrappers') is a plain identity wrapper in this project's pinned
// @quasar/app-vite version (`export const defineRouter = wrapper` where `wrapper = callback =>
// callback`) — the same mechanism already proven for `defineBoot` in
// `src/boot/__tests__/axiosSpec.js`. That means `src/router`'s default export, after unwrapping,
// IS the real factory function Quasar's boot sequence calls — calling it directly here returns a
// real `Router` instance with the real `beforeEach` installed, no mocking of `#q-app/wrappers`
// needed (the vitest.config.mjs alias already points it at the real
// `@quasar/app-vite/wrappers` module). `useAuthStore()`/`useProfileBuilderStore()` are called
// directly inside the guard (not injected), so a Pinia instance must be active before any
// navigation runs — `createTestingPinia` sets itself as the active Pinia as part of its own
// construction, mirroring how every other store-consuming spec in this codebase sets up Pinia.
//
// `let hydrated` lives inside the factory function's own closure (not at module scope), so each
// call to the default export gets a fresh router with its own hydration flag — no
// `vi.resetModules()` needed between tests.

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createTestingPinia } from '@pinia/testing'

// The router resolves a target's lazy page component before confirming a navigation. This spec
// tests the GUARD, not the pages, so the pages the navigation-reachability cases below visit are
// stubbed: a cold import of the real (chart-/Stripe-heavy) pages took 10s+ and timed tests out.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))
vi.mock('src/pages/coach/CoachBookingRequestsPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/AvailabilityManagerPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/DrillLibraryPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/SessionTemplateVault.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/CoachPaymentSettingsPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/CoachReliabilityPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/CoachSubscriptionPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/coach/CoachCommandCenterPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentBookingsPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentCreditWalletPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentApprovalPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentPlayerPortalPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentDevelopmentPortalPage.vue', () => ({
  default: { render: () => null },
}))
vi.mock('src/pages/parent/SessionPackDashboardPage.vue', () => ({
  default: { render: () => null },
}))
vi.mock('src/pages/parent/PlayerSubscriptionPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/parent/ParentDashboardPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/player/PlayerLockerRoomPage.vue', () => ({ default: { render: () => null } }))
vi.mock('src/pages/VideoManagementPage.vue', () => ({ default: { render: () => null } }))

async function buildRouter(initialState) {
  createTestingPinia({ createSpy: vi.fn, initialState })
  const { default: createRouterFactory } = await import('src/router')
  return createRouterFactory({})
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('router/index.js beforeEach — profile-builder gate resolvability (deferred-148 AC3)', () => {
  it('redirects a coach with an incomplete profile to /coach/profile-builder for the canonical path', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      profileBuilder: { status: { profileComplete: false } },
    })

    await router.push('/coach/command-center')

    expect(router.currentRoute.value.path).toBe('/coach/profile-builder')
  })

  it('redirects a coach with an incomplete profile for a trailing-slash spelling (/coach/command-center/)', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      profileBuilder: { status: { profileComplete: false } },
    })

    await router.push('/coach/command-center/')

    expect(router.currentRoute.value.path).toBe('/coach/profile-builder')
  })

  it('redirects a coach with an incomplete profile for a case-variant spelling (/COACH/COMMAND-CENTER)', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      profileBuilder: { status: { profileComplete: false } },
    })

    await router.push('/COACH/COMMAND-CENTER')

    expect(router.currentRoute.value.path).toBe('/coach/profile-builder')
  })

  it('does not redirect a coach with a complete profile', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      profileBuilder: { status: { profileComplete: true } },
    })

    await router.push('/coach/command-center')

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })
})

describe('router/index.js beforeEach — role-gated routes (deferred-148 AC4)', () => {
  it('redirects a PARENT away from /admin/health-dashboard via routeForRole, not the admin page', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })

    await router.push('/admin/health-dashboard')

    // routeForRole('PARENT') === '/parent/dashboard', which PARENT itself passes — no further
    // redirect chain to worry about.
    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('redirects a PLAYER away from /admin/health-dashboard via routeForRole, not the admin page', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PLAYER' } })

    await router.push('/admin/health-dashboard')

    expect(router.currentRoute.value.path).toBe('/player/home')
  })

  it('redirects a COACH away from /admin/health-dashboard via routeForRole, not the admin page', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      // Avoid the command-center profile-builder gate chaining this redirect further.
      profileBuilder: { status: { profileComplete: true } },
    })

    await router.push('/admin/health-dashboard')

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })

  it('lets an ADMIN reach /admin/health-dashboard', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'ADMIN' } })

    await router.push('/admin/health-dashboard')

    expect(router.currentRoute.value.path).toBe('/admin/health-dashboard')
  })

  it("redirects a non-PARENT role away from /parent/dashboard (representative of the player/home + player/development pair's identical gate shape)", async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      // Avoid the command-center profile-builder gate chaining this redirect further.
      profileBuilder: { status: { profileComplete: true } },
    })

    await router.push('/parent/dashboard')

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })

  it('does NOT redirect a PARENT away from /player/locker-room/:playerId — the dual-role regression this AC exists to prevent', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })

    await router.push('/player/locker-room/893573203704173564')

    expect(router.currentRoute.value.path).toBe('/player/locker-room/893573203704173564')
  })

  it('does not redirect a PLAYER away from /player/locker-room/:playerId either', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PLAYER' } })

    await router.push('/player/locker-room/893573203704173564')

    expect(router.currentRoute.value.path).toBe('/player/locker-room/893573203704173564')
  })

  it('redirects a non-PLAYER/PARENT role away from /player/locker-room/:playerId', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'ADMIN' } })

    await router.push('/player/locker-room/893573203704173564')

    // routeForRole('ADMIN') === '/admin/health-dashboard', which ADMIN itself passes.
    expect(router.currentRoute.value.path).toBe('/admin/health-dashboard')
  })

  // skillars-deferred-149 AC1: /parent/player/:playerId/subscription widened from role: 'PARENT'
  // to roles: ['PARENT', 'PLAYER'] — a self-registered PLAYER can now reach their own subscription
  // page, matching the backend's dual-check assertPlayerOwnership fix.
  it('does NOT redirect a self-registered PLAYER away from /parent/player/:playerId/subscription', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PLAYER' } })

    await router.push('/parent/player/893573203704173564/subscription')

    expect(router.currentRoute.value.path).toBe('/parent/player/893573203704173564/subscription')
  })

  it('redirects a COACH away from /parent/player/:playerId/subscription via routeForRole', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      // Avoid the command-center profile-builder gate chaining this redirect further.
      profileBuilder: { status: { profileComplete: true } },
    })

    await router.push('/parent/player/893573203704173564/subscription')

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })
})

// skillars-deferred-165 AC13: the real router's `:playerId(\d+)` constraint actually rejects a
// non-digit segment — code review of skillars-deferred-154 found zero coverage of this (every
// pushed :playerId in this file was an all-digit Tsid fixture).
describe('router/index.js — :playerId(\\d+) constraint rejects a non-digit segment (deferred-165 AC13)', () => {
  it('falls through to the catch-all 404 for a non-digit :playerId', async () => {
    const router = await buildRouter({
      auth: { userId: '1', role: 'COACH' },
      profileBuilder: { status: { profileComplete: true } },
    })

    await router.push('/coach/players/abc')

    expect(router.currentRoute.value.meta.notFound).toBe(true)
    // Mutation check: loosen routes.js's `coach/players/:playerId(\\d+)` back to an unconstrained
    // `:playerId` -> this resolves to the real coach-player-detail route instead, RED.
  })
})

// Navigation reachability: every destination the drawer / dashboards now link to must actually
// admit the role it is linked for, and still bounce the roles it is not for. A nav entry pointing
// at a route the guard rejects would look reachable but silently redirect.
describe('router/index.js — nav destinations admit their own role (navigation reachability)', () => {
  const PID = '893573203704173564'
  const COACH_COMPLETE = {
    auth: { userId: '1', role: 'COACH' },
    profileBuilder: { status: { profileComplete: true } },
  }

  it.each([
    '/coach/booking-requests',
    '/coach/availability',
    '/coach/drills',
    '/coach/session-templates',
    '/coach/payment-settings',
    '/coach/reliability',
    '/coach/subscription',
  ])('lets a COACH reach %s', async (path) => {
    const router = await buildRouter(COACH_COMPLETE)
    await router.push(path)
    expect(router.currentRoute.value.path).toBe(path)
  })

  it('redirects a PARENT away from a coach-only nav destination', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })
    await router.push('/coach/payment-settings')
    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it.each([
    '/parent/bookings',
    '/parent/credit-wallet',
    '/parent/approvals',
    `/parent/players/${PID}/sessions`,
    `/parent/players/${PID}/development`,
    // Verified for the parent role specifically: MainLayout only builds this link for a PLAYER's
    // own id, but the parent dashboard chips and child hub link here for each child.
    `/parent/players/${PID}/packs`,
    `/parent/player/${PID}/subscription`,
    `/player/locker-room/${PID}`,
  ])('lets a PARENT reach %s', async (path) => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })
    await router.push(path)
    expect(router.currentRoute.value.path).toBe(path)
  })

  it('names the child hub route parent-player-hub (ParentChildSwitcher navigates by name)', async () => {
    const router = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })
    await router.push({ name: 'parent-player-hub', params: { playerId: PID } })
    expect(router.currentRoute.value.path).toBe(`/parent/players/${PID}/sessions`)
  })

  it('lets a PLAYER reach /player/videos, and redirects a PARENT away from it', async () => {
    const playerRouter = await buildRouter({ auth: { userId: '1', role: 'PLAYER' } })
    await playerRouter.push('/player/videos')
    expect(playerRouter.currentRoute.value.path).toBe('/player/videos')

    const parentRouter = await buildRouter({ auth: { userId: '1', role: 'PARENT' } })
    await parentRouter.push('/player/videos')
    expect(parentRouter.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('redirects a COACH away from the parent child hub', async () => {
    const router = await buildRouter(COACH_COMPLETE)
    await router.push(`/parent/players/${PID}/sessions`)
    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })
})
