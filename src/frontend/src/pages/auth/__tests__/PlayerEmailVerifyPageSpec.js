// skillars-deferred-138 AC2 — PlayerEmailVerifyPage.vue's nextStep branching had no prior test
// coverage; the old code hardcoded navigation to /player/verify-phone regardless of the response.
// New coverage, not an extension.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/playerRegistration.api', () => ({
  playerRegistrationApi: { verifyEmail: vi.fn() },
}))

import PlayerEmailVerifyPage from 'src/pages/auth/PlayerEmailVerifyPage.vue'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/player/verify-email', name: 'verify', component: PlayerEmailVerifyPage },
      { path: '/player/verify-phone', name: 'phone', component: STUB },
      { path: '/login', name: 'login', component: STUB },
    ],
  })
  router.push({ path: '/player/verify-email', query: { token: 'tok-123' } })
  await router.isReady()

  const wrapper = mount(PlayerEmailVerifyPage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

describe('PlayerEmailVerifyPage.vue — nextStep branching (skillars-deferred-138 AC2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('nextStep "verify-phone" stores the handle and navigates to /player/verify-phone', async () => {
    playerRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'verify-phone', verificationToken: 'handle-abc' },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('playerVerificationToken')).toBe('handle-abc')
    expect(router.currentRoute.value.path).toBe('/player/verify-phone')
  })

  it('nextStep "login" (phone OTP not required) skips sessionStorage and goes to /login?verified=true', async () => {
    playerRegistrationApi.verifyEmail.mockResolvedValue({
      data: { nextStep: 'login', verificationToken: null },
    })

    const { router } = await mountPage()

    expect(sessionStorage.getItem('playerVerificationToken')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.verified).toBe('true')
  })
})
