// skillars-deferred-138 AC2 — ParentEmailVerifyPage.vue's nextStep branching had no prior test
// coverage; the old code hardcoded navigation to /parent/verify-phone regardless of the response.
// New coverage, not an extension.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/parentRegistration.api', () => ({
  parentRegistrationApi: { verifyEmail: vi.fn() },
}))

import ParentEmailVerifyPage from 'src/pages/auth/ParentEmailVerifyPage.vue'
import { parentRegistrationApi } from 'src/api/parentRegistration.api'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/parent/verify-email', name: 'verify', component: ParentEmailVerifyPage },
      { path: '/parent/verify-phone', name: 'phone', component: STUB },
      { path: '/login', name: 'login', component: STUB },
    ],
  })
  router.push({ path: '/parent/verify-email', query: { token: 'tok-123' } })
  await router.isReady()

  const wrapper = mount(ParentEmailVerifyPage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

describe('ParentEmailVerifyPage.vue — nextStep branching (skillars-deferred-138 AC2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('nextStep "verify-phone" stores the handle and navigates to /parent/verify-phone', async () => {
    parentRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'verify-phone', verificationToken: 'handle-abc' },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('parentVerificationToken')).toBe('handle-abc')
    expect(router.currentRoute.value.path).toBe('/parent/verify-phone')
  })

  it('nextStep "login" (phone OTP not required) skips sessionStorage and goes to /login?verified=true', async () => {
    parentRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'login', verificationToken: null },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('parentVerificationToken')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.verified).toBe('true')
  })
})
