// skillars-deferred-125 AC3 — App.vue's handleSessionExpired.
//
// Not new test infrastructure: mirrors MainLayoutSpec.js's `shallow: true` + real
// createRouter/createMemoryHistory + createTestingPinia template, and useSessionSpec.js's
// `vi.mock('src/plugins/sessionManager', { spy: true })` convention for observing
// cleanup()/startSessionMonitoring() calls without sessionManager's own real interval logic
// running. `handleSessionExpired` is a plain `window` listener (registered in onMounted, not
// exposed off the component instance under <script setup> without defineExpose), so it is
// exercised the same way production code reaches it: dispatching the real `session:expired` event.
//
// Mutation check (run both ways, see Dev Agent Record): revert pushLoginOrHardNavigate back to a
// bare `router.push(...)` call with no fallback → the "guard aborts" test below fails (window.location
// never changes, because a resolved NavigationFailure is not an exception a bare call would react to).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('src/plugins/sessionManager', { spy: true })

import * as sm from 'src/plugins/sessionManager'
import App from 'src/App.vue'
import { __resetSessionRedirectGuardForTests } from 'src/utils/sessionRedirect'

let wrapper

function mountApp(beforeEachGuard) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  if (beforeEachGuard) {
    router.beforeEach(beforeEachGuard)
  }
  router.push('/')
  wrapper = mount(App, {
    shallow: true,
    global: { plugins: [pinia, router] },
  })
  return { wrapper, router, pinia }
}

afterEach(() => {
  vi.clearAllMocks()
  __resetSessionRedirectGuardForTests()
  document.cookie = 'user=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;'
  if (wrapper) {
    wrapper.unmount()
    wrapper = null
  }
})

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

describe('App.vue — handleSessionExpired teardown + redirect (deferred-125 AC3)', () => {
  it('clears the user cookie, tears down auth/player/session state, and pushes to /login with the redirect query', async () => {
    const { router, pinia } = mountApp()
    await flushPromises()
    const { useAuthStore } = await import('src/stores/auth.store')
    const { usePlayerStore } = await import('src/stores/playerStore')
    const authStore = useAuthStore(pinia)
    const playerStore = usePlayerStore(pinia)
    const pushSpy = vi.spyOn(router, 'push')
    document.cookie = 'user=Some%20Name; path=/;'

    window.dispatchEvent(new Event('session:expired'))
    await flushPromises()

    expect(document.cookie).not.toMatch(/(^|;\s*)user=Some/)
    expect(authStore.logout).toHaveBeenCalledTimes(1)
    expect(playerStore.resetSelfPlayerId).toHaveBeenCalledTimes(1)
    expect(sm.cleanup).toHaveBeenCalledTimes(1)
    expect(pushSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/login',
        query: expect.objectContaining({ expired: 'true' }),
      }),
    )
  })

  it('router.push resolves with a NavigationFailure (guard aborts) — falls back to a hard navigation', async () => {
    mountApp((to) => {
      if (to.path === '/login') {
        return false
      }
    })
    await flushPromises()

    window.dispatchEvent(new Event('session:expired'))
    await flushPromises()

    expect(window.location.pathname).toBe('/login')
    expect(window.location.search).toContain('expired=true')
  })
})
