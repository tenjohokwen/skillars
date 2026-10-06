// skillars-deferred-144 AC3/AC7 — no spec existed for LoginPage.vue before this story (confirmed
// by `find`). LoginPage.vue's own inline open-redirect guard was migrated to the shared,
// resolvability-checked isSafeRedirect(path, router) (src/router/safeRedirect.js), mirroring
// OtpPageSpec.js's coverage for its own page, including the same tagged-catch-all mock-route
// stub — a mock list with no catch-all cannot catch the class of defect this guard exists to fix
// (router.resolve().matched.length > 0 is a no-op against a route table that has one).
// createTestingPinia stubs actions (including setUser) by default; LoginPage.vue derives its
// redirect fallback from the API response's `role` field directly, not from the auth store, so no
// initialState seeding is needed for that.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/auth.api', () => ({
  authApi: { skillarsLogin: vi.fn() },
}))
vi.mock('src/composables/useSession', () => ({
  useSession: () => ({ initSession: vi.fn() }),
}))

import LoginPage from 'src/pages/auth/LoginPage.vue'
import { authApi } from 'src/api/auth.api'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage({ redirect } = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginPage },
      { path: '/dashboard', component: STUB },
      { path: '/coach/command-center', component: STUB },
      { path: '/parent/dashboard', component: STUB },
      { path: '/safe/custom-path', component: STUB },
      { path: '/:catchAll(.*)*', component: STUB, meta: { notFound: true } },
    ],
  })
  const query = {}
  if (redirect !== undefined) query.redirect = redirect
  router.push({ path: '/login', query })
  await router.isReady()

  const wrapper = mount(LoginPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  return { wrapper, router }
}

async function submitLogin(wrapper) {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('coach@skillars.com')
  await inputs[1].setValue('correct-password')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

describe('LoginPage.vue — open-redirect guard (skillars-deferred-144 AC3)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('falls back to routeForRole(response.role) when no redirect query value is present', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: 'COACH' })
    const { wrapper, router } = await mountPage()

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })

  it('rejects a protocol-relative redirect query value in favor of the role fallback', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: 'PARENT' })
    const { wrapper, router } = await mountPage({ redirect: '//evil.example.com' })

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('rejects an absolute redirect query value in favor of the role fallback', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: 'PARENT' })
    const { wrapper, router } = await mountPage({ redirect: 'https://evil.example.com' })

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/parent/dashboard')
  })

  it('honors a safe relative redirect query value over the role fallback', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: 'COACH' })
    const { wrapper, router } = await mountPage({ redirect: '/safe/custom-path' })

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/safe/custom-path')
  })

  it('falls back to the role route when redirect is shape-safe but matches no real route (skillars-deferred-144 AC3)', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: 'COACH' })
    const { wrapper, router } = await mountPage({ redirect: '/typo' })

    await submitLogin(wrapper)

    // This mock route list's catch-all DOES match '/typo' (matched.length === 1) — proves the
    // fix is the tagged-catch-all exclusion, not merely "matched.length === 0".
    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })

  it('lands on /dashboard via DEFAULT_ROUTE when the response carries no known role', async () => {
    authApi.skillarsLogin.mockResolvedValue({ role: null })
    const { wrapper, router } = await mountPage()

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).toBe('/dashboard')
  })
})
