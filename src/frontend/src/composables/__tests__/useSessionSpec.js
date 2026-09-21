// skillars-deferred-108 AC6 — useSession.js handleLogout (closes deferred-91 AC14 residual).
//
// Ledger (deferred-work.md:1151): "the deferred-91 handleLogout / i18n coverage is now in-scope
// for its own follow-up story."
//
// useSession() calls useRouter(), so it is exercised inside a tiny host component with Pinia +
// a memory router. sessionManager's statically-imported named exports can't be reliably
// vi.spyOn'd (non-configurable ESM namespace), so the module is mocked with { spy: true }.
//
// Mutation checks (run both ways, see Dev Agent Record):
//   - remove the SECOND `document.cookie = 'rint=; …'` (post-race) line → the "rint double-clear"
//     test fails (a mid-logout re-established rint survives).
//   - remove the `new Promise(r => setTimeout(r, LOGOUT_BACKEND_WAIT_MS))` arm from Promise.race
//     → the "bounded wait" test hangs (times out).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('src/plugins/sessionManager', { spy: true })

import * as sm from 'src/plugins/sessionManager'
import { useSession } from 'src/composables/useSession'
import { __resetSessionRedirectGuardForTests } from 'src/utils/sessionRedirect'

const STUB = { template: '<div />' }

function clearRint() {
  document.cookie = 'rint=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
}

function mountUseSession(beforeEachGuard) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: STUB },
      { path: '/login', component: STUB },
    ],
  })
  if (beforeEachGuard) {
    router.beforeEach(beforeEachGuard)
  }
  router.push('/')

  const Host = defineComponent({
    setup() {
      return useSession()
    },
    render: () => h('div'),
  })
  const wrapper = mount(Host, { global: { plugins: [pinia, router] } })
  return { wrapper, router, pinia }
}

describe('useSession — handleLogout (deferred-108 AC6)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    clearRint()
    window.history.pushState({}, '', '/')
  })

  afterEach(() => {
    vi.useRealTimers()
    // Not vi.restoreAllMocks(): it unwraps the { spy: true } sessionManager module spies for
    // subsequent tests. Fresh Pinia + fresh router per test keeps the per-test spies isolated.
    clearRint()
    __resetSessionRedirectGuardForTests()
  })

  it('happy path: stopSessionMonitoring → authStore.logout → resetSelfPlayerId → cleanup → router.push(/login)', async () => {
    const { wrapper, router, pinia } = mountUseSession()
    const { useAuthStore } = await import('src/stores/auth.store')
    const { usePlayerStore } = await import('src/stores/playerStore')
    const authStore = useAuthStore(pinia)
    const playerStore = usePlayerStore(pinia)
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.vm.handleLogout()

    expect(sm.stopSessionMonitoring).toHaveBeenCalled()
    expect(authStore.logout).toHaveBeenCalledTimes(1)
    expect(playerStore.resetSelfPlayerId).toHaveBeenCalledTimes(1)
    expect(sm.cleanup).toHaveBeenCalled()
    // skillars-deferred-125 AC3: handleLogout's final push now goes through the shared
    // pushLoginOrHardNavigate helper, which pushes an object (path + redirect query) rather than the
    // bare '/login' string this call site used before. /bmad-code-review fix (2026-09-21): a
    // DELIBERATE logout must not carry expired:'true' (that banner is for App.vue's genuine
    // session-expiry teardown only) — handleLogout calls pushLoginOrHardNavigate with no options, so
    // the query must NOT contain 'expired' at all.
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/login',
        query: { redirect: '/' },
      }),
    )

    const order = (spy) => spy.mock.invocationCallOrder[0]
    expect(order(sm.stopSessionMonitoring)).toBeLessThan(order(authStore.logout))
    expect(order(authStore.logout)).toBeLessThan(order(playerStore.resetSelfPlayerId))
    expect(order(playerStore.resetSelfPlayerId)).toBeLessThan(order(sm.cleanup))
    expect(order(sm.cleanup)).toBeLessThan(order(pushSpy))
  })

  it('rint double-clear: a rint re-established mid-logout is expired again after the race resolves', async () => {
    const { wrapper, pinia } = mountUseSession()
    const { useAuthStore } = await import('src/stores/auth.store')
    const authStore = useAuthStore(pinia)
    // An authenticated response already in flight when the pre-race clear ran lands during
    // authStore.logout() and re-writes rint with path=/.
    authStore.logout.mockImplementation(async () => {
      document.cookie = 'rint=99999999999999; path=/'
    })

    await wrapper.vm.handleLogout()

    expect(document.cookie).not.toMatch(/(^|;\s*)rint=/)
  })

  it('bounded wait: a backend logout that never resolves still lets handleLogout finish', async () => {
    const { wrapper, router, pinia } = mountUseSession()
    const { useAuthStore } = await import('src/stores/auth.store')
    const authStore = useAuthStore(pinia)
    const pushSpy = vi.spyOn(router, 'push')
    authStore.logout.mockReturnValue(new Promise(() => {})) // never settles

    const done = wrapper.vm.handleLogout()
    await vi.advanceTimersByTimeAsync(3000) // LOGOUT_BACKEND_WAIT_MS
    await done

    expect(sm.cleanup).toHaveBeenCalled()
    // skillars-deferred-125 AC3: handleLogout's final push now goes through the shared
    // pushLoginOrHardNavigate helper, which pushes an object (path + redirect query) rather than the
    // bare '/login' string this call site used before. /bmad-code-review fix (2026-09-21): a
    // DELIBERATE logout must not carry expired:'true' (that banner is for App.vue's genuine
    // session-expiry teardown only) — handleLogout calls pushLoginOrHardNavigate with no options, so
    // the query must NOT contain 'expired' at all.
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/login',
        query: { redirect: '/' },
      }),
    )
  })

  // /bmad-code-review fix (2026-09-21): AC3 task 4 called for "one integration-style case per call
  // site" proving the fallback actually fires from THIS call site's own teardown — the assertion
  // above only proved the push's shape, never that a failed push falls back. sessionRedirectSpec.js
  // covers the mechanism in isolation; this proves useSession.js's own call site is wired to it.
  it('router.push does not land (guard aborts) — falls back to a hard navigation', async () => {
    const { wrapper } = mountUseSession((to) => {
      if (to.path === '/login') {
        return false
      }
    })

    await wrapper.vm.handleLogout()

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).not.toContain('expired')
  })
})
