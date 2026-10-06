// skillars-deferred-142 AC3 — OtpPage.vue's redirect fallback was a hardcoded '/dashboard' string,
// unlike LoginPage.vue's routeForRole(response.role) + open-redirect guard. No spec existed for this
// page before this story. The fix reads authStore.role (hydrated from the 'skp' cookie, now set
// correctly on OTP completion per AC2) instead of a bare string, and copies LoginPage.vue's guard
// verbatim. createTestingPinia stubs actions (including hydrateFromCookie) by default, so these
// specs seed `initialState.auth.role` directly rather than relying on a real cookie round-trip —
// the thing under test is "handleSubmit uses routeForRole(authStore.role) + the redirect guard",
// not hydrateFromCookie's own parsing (already covered by auth.store's own tests).
//
// skillars-deferred-144 AC3/AC4/AC7: the inline guard was replaced by the shared, resolvability-
// checked isSafeRedirect(path, router) (src/router/safeRedirect.js). The mock route list below
// gains the tagged catch-all stub so the unresolvable-redirect case below can prove the guard
// excludes it, not merely that matched.length happened to be 0 — a mock list with no catch-all at
// all cannot catch that class of defect. The terminal redirect also switched router.push ->
// router.replace (AC4), asserted via a spy on the router instance.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/auth.api', () => ({
  authApi: { verifyOtp: vi.fn() },
}))
vi.mock('src/composables/useSession', () => ({
  useSession: () => ({ initSession: vi.fn() }),
}))

import OtpPage from 'src/pages/auth/OtpPage.vue'
import { authApi } from 'src/api/auth.api'
import { useAuthStore } from 'src/stores/auth.store'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage({ role = null, redirect } = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/otp', name: 'otp', component: OtpPage },
      { path: '/login', component: STUB },
      { path: '/dashboard', component: STUB },
      { path: '/coach/command-center', component: STUB },
      { path: '/parent/dashboard', component: STUB },
      { path: '/safe/custom-path', component: STUB },
      { path: '/:catchAll(.*)*', component: STUB, meta: { notFound: true } },
    ],
  })
  const query = { id: 'login-info-1' }
  if (redirect !== undefined) query.redirect = redirect
  router.push({ path: '/otp', query })
  await router.isReady()

  const wrapper = mount(OtpPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  return { wrapper, router, authStore: useAuthStore(pinia) }
}

async function submitOtp(wrapper) {
  const inputs = wrapper.findAll('input')
  expect(inputs).toHaveLength(6)
  for (let i = 0; i < 6; i++) {
    await inputs[i].setValue(String((i + 1) % 10))
  }
  await flushPromises()
}

describe('OtpPage.vue — role-aware redirect (skillars-deferred-142 AC3)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('falls back to routeForRole(authStore.role), not a hardcoded /dashboard', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router, authStore } = await mountPage({ role: 'COACH' })

    await submitOtp(wrapper)

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
    // This is the only thing connecting AC2's backend skp cookie to AC3's redirect — assert the
    // call happened, not just that the (mocked) store already had a role seeded in initialState.
    expect(authStore.hydrateFromCookie).toHaveBeenCalled()
  })

  it('rejects a protocol-relative redirect query value in favor of the role fallback', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({ role: 'PARENT', redirect: '//evil.example.com' })

    await submitOtp(wrapper)

    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('rejects an absolute redirect query value in favor of the role fallback', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({
      role: 'PARENT',
      redirect: 'https://evil.example.com',
    })

    await submitOtp(wrapper)

    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('honors a safe relative redirect query value over the role fallback', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({ role: 'COACH', redirect: '/safe/custom-path' })

    await submitOtp(wrapper)

    expect(router.currentRoute.value.path).toBe('/safe/custom-path')
  })

  it('lands on /dashboard via DEFAULT_ROUTE when skp hydration yields no role — intended, not a gap', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({ role: null })

    await submitOtp(wrapper)

    expect(router.currentRoute.value.path).toBe('/dashboard')
  })

  it('falls back to the role route when redirect is shape-safe but matches no real route (skillars-deferred-144 AC3)', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({ role: 'COACH', redirect: '/typo' })

    await submitOtp(wrapper)

    // Proves the fix is the tagged-catch-all exclusion, not merely "matched.length === 0": this
    // mock route list's catch-all DOES match '/typo' (matched.length === 1), so a guard that only
    // checked matched.length > 0 would wrongly accept it and land here on the catch-all stub.
    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })

  it('uses router.replace, not router.push, for the terminal redirect (skillars-deferred-144 AC4)', async () => {
    authApi.verifyOtp.mockResolvedValue({})
    const { wrapper, router } = await mountPage({ role: 'COACH' })
    const replaceSpy = vi.spyOn(router, 'replace')
    const pushSpy = vi.spyOn(router, 'push')

    await submitOtp(wrapper)

    expect(replaceSpy).toHaveBeenCalledWith('/coach/command-center')
    expect(pushSpy).not.toHaveBeenCalled()
  })
})
