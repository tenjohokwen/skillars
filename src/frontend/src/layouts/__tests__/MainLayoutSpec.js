// skillars-deferred-108 AC4 + AC6 — MainLayout.vue.
//
// AC4 (deferred-43): handleLogout() runs authStore.logout() → playerStore.resetSelfPlayerId()
//   → session teardown → router.push('/login'), in that order.
// AC6 (deferred-91 i18n residual): changeLanguage() / loadLanguagePreference() locale behaviour.
//
// Trimming (recorded per the story): shallow mount + assert via wrapper.vm. MainLayout pulls in
// the whole nav tree (ParentChildSwitcher, theme boot, useSession); the logout ordering and the
// locale helpers are fully reachable through wrapper.vm without a full mount. No production seam.
//
// Mutation checks (run both ways, see Dev Agent Record):
//   - AC4: delete `playerStore.resetSelfPlayerId()` from handleLogout → the "resetSelfPlayerId is
//     called, before teardown" assertion fails.
//   - AC6: delete `document.cookie = 'lang=; Max-Age=0; path=/'` from changeLanguage → the
//     "lang cookie expired" assertion fails.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('src/boot/theme', () => ({
  toggleTheme: vi.fn(() => false),
  isDarkMode: vi.fn(() => false),
}))

const { destroySessionSpy } = vi.hoisted(() => ({ destroySessionSpy: vi.fn() }))
vi.mock('src/composables/useSession', () => ({
  useSession: () => ({ destroySession: destroySessionSpy }),
}))

import MainLayout from 'src/layouts/MainLayout.vue'

const STUB = { template: '<div />' }
let wrapper

async function mountLayout(authState = { role: 'PARENT' }) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: authState } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: STUB },
      { path: '/login', component: STUB },
    ],
  })
  router.push('/')
  await router.isReady()

  wrapper = mount(MainLayout, {
    shallow: true,
    global: { plugins: [pinia, router], stubs: { ParentChildSwitcher: true } },
  })
  await flushPromises()
  return { wrapper, router, pinia }
}

afterEach(() => {
  if (wrapper) {
    // Restore the shared vue-i18n global locale the AC6 tests mutate.
    try {
      wrapper.vm.locale = 'en-US'
    } catch {
      // wrapper without an i18n scope — nothing to restore.
    }
    wrapper.unmount()
    wrapper = null
  }
  localStorage.clear()
  document.cookie = 'lang=; Max-Age=0; path=/'
  vi.clearAllMocks()
})

describe('MainLayout.vue — handleLogout order (deferred-108 AC4)', () => {
  it('runs authStore.logout → resetSelfPlayerId → destroySession → router.push(/login), in order', async () => {
    const { wrapper, router, pinia } = await mountLayout()
    const { useAuthStore } = await import('src/stores/auth.store')
    const { usePlayerStore } = await import('src/stores/playerStore')
    const authStore = useAuthStore(pinia)
    const playerStore = usePlayerStore(pinia)
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.vm.handleLogout()

    expect(authStore.logout).toHaveBeenCalledTimes(1)
    expect(playerStore.resetSelfPlayerId).toHaveBeenCalledTimes(1)
    expect(destroySessionSpy).toHaveBeenCalledTimes(1)
    expect(pushSpy).toHaveBeenCalledWith('/login')

    const logoutOrder = authStore.logout.mock.invocationCallOrder[0]
    const resetOrder = playerStore.resetSelfPlayerId.mock.invocationCallOrder[0]
    const teardownOrder = destroySessionSpy.mock.invocationCallOrder[0]
    const pushOrder = pushSpy.mock.invocationCallOrder[0]

    expect(logoutOrder).toBeLessThan(resetOrder)
    expect(resetOrder).toBeLessThan(teardownOrder)
    expect(teardownOrder).toBeLessThan(pushOrder)
  })
})

describe('MainLayout.vue — locale switching (deferred-108 AC6)', () => {
  it('changeLanguage sets the locale, persists it, and expires a stuck lang cookie', async () => {
    const { wrapper } = await mountLayout()
    // A prior `?language=` visit left a backend `lang` cookie outranking Accept-Language.
    document.cookie = 'lang=fr-FR; path=/'
    expect(document.cookie).toMatch(/lang=fr-FR/)

    wrapper.vm.changeLanguage('de-DE')

    expect(wrapper.vm.locale).toBe('de-DE')
    expect(localStorage.getItem('locale')).toBe('de-DE')
    expect(document.cookie).not.toMatch(/lang=fr-FR/)
  })

  it('loadLanguagePreference applies a valid saved locale and ignores an invalid one', async () => {
    const { wrapper } = await mountLayout()

    localStorage.setItem('locale', 'fr-FR')
    wrapper.vm.loadLanguagePreference()
    expect(wrapper.vm.locale).toBe('fr-FR')

    localStorage.setItem('locale', 'xx-YY')
    wrapper.vm.loadLanguagePreference()
    expect(wrapper.vm.locale).toBe('fr-FR') // unchanged — 'xx-YY' is not an offered language
  })
})
