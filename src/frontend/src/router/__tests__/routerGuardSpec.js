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
})
