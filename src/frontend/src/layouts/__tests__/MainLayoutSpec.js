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
// Keep the real module's exports (notably LOGOUT_BACKEND_WAIT_MS, which MainLayout now imports for
// its bounded logout race — skillars-deferred-109 AC3.1) and override only useSession().
vi.mock('src/composables/useSession', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useSession: () => ({ destroySession: destroySessionSpy }) }
})

import MainLayout from 'src/layouts/MainLayout.vue'

const STUB = { template: '<div />' }
let wrapper

async function mountLayout(authState = { role: 'PARENT' }, preMount) {
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

  // skillars-deferred-109 AC3.3: hook to arm store-action spies (e.g. fetchSelfPlayerId rejecting)
  // BEFORE the component's onMounted runs.
  if (preMount) await preMount({ pinia })

  wrapper = mount(MainLayout, {
    shallow: true,
    global: { plugins: [pinia, router], stubs: { ParentChildSwitcher: true } },
  })
  await flushPromises()
  return { wrapper, router, pinia }
}

afterEach(() => {
  // skillars-deferred-109 code review: the AC3.3 helper installs a console.error spy and the AC3.1
  // tests write real cookies, none of which were ever undone. Later tests in the file then ran with
  // console output suppressed — hiding genuine Vue prop-type and unhandled-rejection warnings from
  // anyone debugging a failure — and with a stale `rint`/`lang` still set.
  vi.restoreAllMocks()
  for (const name of ['rint', 'lang', 'user']) {
    document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;`
  }
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

// ---------------------------------------------------------------------------
// skillars-deferred-109 AC3 — handleLogout parity + localStorage guards + silent-404.
// Closes 3 deferred-108-CR bullets (deferred-work.md:1844-1858).
// ---------------------------------------------------------------------------
describe('MainLayout.vue — handleLogout parity (deferred-109 AC3.1)', () => {
  it('bounds a stalled POST /logout: handleLogout still resolves and navigates within the wait budget', async () => {
    const { wrapper, router, pinia } = await mountLayout()
    const { useAuthStore } = await import('src/stores/auth.store')
    const authStore = useAuthStore(pinia)
    const pushSpy = vi.spyOn(router, 'push')
    // Backend revocation never resolves.
    authStore.logout.mockImplementation(() => new Promise(() => {}))

    vi.useFakeTimers()
    try {
      const done = wrapper.vm.handleLogout()
      let settled = false
      done.then(() => {
        settled = true
      })
      await vi.advanceTimersByTimeAsync(3000) // LOGOUT_BACKEND_WAIT_MS
      await done
      expect(settled).toBe(true)
    } finally {
      vi.useRealTimers()
    }
    expect(pushSpy).toHaveBeenCalledWith('/login')
    // Mutation: revert the Promise.race bound to a bare `await authStore.logout()` → handleLogout
    // never resolves and this test times out → RED.
  })

  // skillars-deferred-109 code review: the original single test could not see the PRE-race clear.
  // Its logout mock re-set `rint` and then asserted the cookie was live — an assertion satisfied by
  // the line the mock itself had just written, whether or not the pre-race clear ran; the final
  // assertion was then satisfied by the post-race clear alone. Deleting the pre-race clear left the
  // file 9/9 green. AC3.1 asks for BOTH clears covered, "with and without a simulated in-flight
  // rint re-write" — so: two tests, and the pre-race one observes the cookie FROM INSIDE the race,
  // before anything re-writes it.
  it('clears rint BEFORE the backend race, so sibling tabs tear down immediately', async () => {
    const { wrapper, pinia } = await mountLayout()
    const { useAuthStore } = await import('src/stores/auth.store')
    const authStore = useAuthStore(pinia)

    document.cookie = 'rint=' + String(Date.now() + 900000) + '; path=/'
    expect(document.cookie).toMatch(/rint=\d/)

    // No re-write here — just read what handleLogout left behind at the moment the backend call
    // starts. This is the window sibling tabs spend in computeTimeUntilExpiry's fast-teardown
    // branch; without the pre-race clear they keep rendering an authenticated UI for the whole
    // LOGOUT_BACKEND_WAIT_MS.
    let cookieDuringRace = null
    authStore.logout.mockImplementation(async () => {
      cookieDuringRace = document.cookie
    })

    await wrapper.vm.handleLogout()

    expect(cookieDuringRace).not.toMatch(/rint=\d/)
    // Mutation: delete the PRE-race `document.cookie = 'rint=; …'` line → cookieDuringRace still
    // carries the live rint → RED. This is the half the previous test could not see.
  })

  it('clears rint AFTER the backend race too (survives an in-flight rint re-write)', async () => {
    const { wrapper, pinia } = await mountLayout()
    const { useAuthStore } = await import('src/stores/auth.store')
    const authStore = useAuthStore(pinia)

    // Simulate a request that was already in flight re-setting `rint` while the backend call runs
    // (JwtManagerImpl rewrites it with path=/ on every authenticated response).
    authStore.logout.mockImplementation(async () => {
      document.cookie = 'rint=' + String(Date.now() + 900000) + '; path=/'
    })

    await wrapper.vm.handleLogout()

    // The post-race clear must have wiped the re-written cookie.
    expect(document.cookie).not.toMatch(/rint=\d/)
    // Mutation: delete the POST-race `document.cookie = 'rint=; …'` line → the mid-race re-write
    // stands → RED.
  })
})

describe('MainLayout.vue — localStorage guards (deferred-109 AC3.2)', () => {
  it('changeLanguage: a throwing localStorage.setItem does not prevent the lang cookie clear', async () => {
    const { wrapper } = await mountLayout()
    document.cookie = 'lang=fr-FR; path=/'
    const setItemSpy = vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError')
    })

    expect(() => wrapper.vm.changeLanguage('de-DE')).not.toThrow()

    expect(document.cookie).not.toMatch(/lang=fr-FR/)
    setItemSpy.mockRestore()
    // Mutation: remove the try/catch around `localStorage.setItem('locale', lang)` → changeLanguage
    // throws before the cookie clear and the lang=fr-FR assertion goes RED. (locale.value = lang
    // runs before setItem, so it is not the RED signal — the cookie clear is.)
  })

  it('loadLanguagePreference: a throwing localStorage.getItem does not abort', async () => {
    const getItemSpy = vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError')
    })

    // onMounted calls loadLanguagePreference(); an unguarded getItem would throw out of onMounted.
    await expect(mountLayout()).resolves.toBeTruthy()
    expect(() => wrapper.vm.loadLanguagePreference()).not.toThrow()

    getItemSpy.mockRestore()
    // Mutation: remove the try/catch around `localStorage.getItem('locale')` → loadLanguagePreference
    // throws; the mountLayout() call rejects out of onMounted and the resolves assertion goes RED.
  })
})

describe('MainLayout.vue — silent-404 on self player id (deferred-109 AC3.3)', () => {
  async function mountAsPlayerWithFetchRejecting(rejection) {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    await mountLayout({ role: 'PLAYER', userId: 7 }, async ({ pinia }) => {
      const { usePlayerStore } = await import('src/stores/playerStore')
      const playerStore = usePlayerStore(pinia)
      playerStore.fetchSelfPlayerId.mockRejectedValue(rejection)
    })
    await flushPromises()
    return errSpy
  }

  it('a 404 from fetchSelfPlayerId is swallowed silently', async () => {
    const errSpy = await mountAsPlayerWithFetchRejecting({ response: { status: 404 } })
    expect(errSpy).not.toHaveBeenCalled()
  })

  it('a non-404 error from fetchSelfPlayerId is logged', async () => {
    const errSpy = await mountAsPlayerWithFetchRejecting({ response: { status: 500 } })
    expect(errSpy).toHaveBeenCalled()
    // Mutation: change `if (err.response?.status !== 404)` to a bare `if (err)` → the 404 case now
    // logs and the "swallowed silently" assertion goes RED.
  })
})
