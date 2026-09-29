// skillars-deferred-138 AC2 — CoachEmailVerifyPage.vue's nextStep branching had no prior test
// coverage; the old code hardcoded navigation to /coach/verify-phone regardless of the response.
// New coverage, not an extension.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/coachRegistration.api', () => ({
  coachRegistrationApi: { verifyEmail: vi.fn() },
}))

import CoachEmailVerifyPage from 'src/pages/auth/CoachEmailVerifyPage.vue'
import { coachRegistrationApi } from 'src/api/coachRegistration.api'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/coach/verify-email', name: 'verify', component: CoachEmailVerifyPage },
      { path: '/coach/verify-phone', name: 'phone', component: STUB },
      { path: '/login', name: 'login', component: STUB },
    ],
  })
  router.push({ path: '/coach/verify-email', query: { token: 'tok-123' } })
  await router.isReady()

  const wrapper = mount(CoachEmailVerifyPage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

describe('CoachEmailVerifyPage.vue — nextStep branching (skillars-deferred-138 AC2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('nextStep "verify-phone" stores the handle and navigates to /coach/verify-phone', async () => {
    coachRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'verify-phone', verificationToken: 'handle-abc' },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('coachVerificationToken')).toBe('handle-abc')
    expect(router.currentRoute.value.path).toBe('/coach/verify-phone')
  })

  it('nextStep "login" (phone OTP not required) skips sessionStorage and goes to /login?verified=true', async () => {
    coachRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'login', verificationToken: null },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('coachVerificationToken')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.verified).toBe('true')
  })
})
