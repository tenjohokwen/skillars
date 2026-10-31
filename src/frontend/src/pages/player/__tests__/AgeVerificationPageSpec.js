// S-27 — AgeVerificationPage.vue: the self-registered player's Stripe Identity flow.
// States are server-owned (GET /api/security/age-verification); the page only renders them, starts a session
// and hands the player to the hosted Stripe URL. Polling while PENDING is bounded and stops on unmount.

import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US/index.js'

const getStatus = vi.fn()
const start = vi.fn()
vi.mock('src/api/ageVerification.api', () => ({
  ageVerificationApi: { getStatus: (...a) => getStatus(...a), start: (...a) => start(...a) },
}))
const notify = vi.fn()
vi.mock('quasar', async (orig) => ({ ...(await orig()), useQuasar: () => ({ notify }) }))
const push = vi.fn()
let routeQuery = {}
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeQuery }),
  useRouter: () => ({ push }),
}))

import AgeVerificationPage from 'src/pages/player/AgeVerificationPage.vue'

const i18n = createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } })
const mounted = []

async function mountPage() {
  const wrapper = mount(AgeVerificationPage, {
    global: {
      plugins: [i18n],
      stubs: { QPage: { template: '<div><slot /></div>' } },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const status = (over = {}) => ({
  applicable: true,
  enabled: true,
  state: 'NOT_STARTED',
  attemptsRemainingToday: 3,
  ...over,
})

beforeEach(() => {
  routeQuery = {}
  vi.useFakeTimers()
})

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('AgeVerificationPage.vue', () => {
  it('NOT_STARTED: explains why, offers the start button and shows the attempts left', async () => {
    getStatus.mockResolvedValue(status())
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Skillars is for adults')
    expect(wrapper.find('[data-test="age-start"]').text()).toContain('Verify my age')
    expect(wrapper.text()).toContain('Attempts left today: 3')
  })

  it('start: redirects to the hosted Stripe URL', async () => {
    getStatus.mockResolvedValue(status())
    start.mockResolvedValue({ state: 'PENDING', url: 'https://verify.stripe.com/start/abc' })
    const hrefSetter = vi.fn()
    const original = window.location
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {
        ...original,
        set href(v) {
          hrefSetter(v)
        },
      },
    })
    try {
      const wrapper = await mountPage()
      await wrapper.find('[data-test="age-start"]').trigger('click')
      await flushPromises()
      expect(start).toHaveBeenCalledTimes(1)
      expect(hrefSetter).toHaveBeenCalledWith('https://verify.stripe.com/start/abc')
    } finally {
      Object.defineProperty(window, 'location', { configurable: true, value: original })
    }
  })

  it('PENDING: shows the checking copy and polls until the server reports VERIFIED', async () => {
    getStatus
      .mockResolvedValueOnce(status({ state: 'PENDING' }))
      .mockResolvedValue(status({ state: 'VERIFIED' }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-pending"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(4100)
    await flushPromises()
    expect(wrapper.find('[data-test="age-verified"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="age-pending"]').exists()).toBe(false)
  })

  it('FAILED: tells the player to retry and the button says "Try again"', async () => {
    getStatus.mockResolvedValue(status({ state: 'FAILED', attemptsRemainingToday: 2 }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="age-start"]').text()).toContain('Try again')
  })

  it('attempts exhausted: the start button is disabled and the daily-cap copy shows', async () => {
    getStatus.mockResolvedValue(status({ state: 'FAILED', attemptsRemainingToday: 0 }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-exhausted"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="age-start"]').attributes('disabled')).toBeDefined()
  })

  it('BLOCKED: shows the under-18 notice and no start button', async () => {
    getStatus.mockResolvedValue(status({ state: 'BLOCKED' }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-blocked"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="age-start"]').exists()).toBe(false)
  })

  it('feature off: explains the fallback instead of offering a flow', async () => {
    getStatus.mockResolvedValue(status({ enabled: false }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="age-start"]').exists()).toBe(false)
  })

  it('VERIFIED with a same-app next path: the continue button navigates there', async () => {
    routeQuery = { next: '/messaging?conversation=4' }
    getStatus.mockResolvedValue(status({ state: 'VERIFIED' }))
    const wrapper = await mountPage()
    await wrapper.find('[data-test="age-continue"]').trigger('click')
    expect(push).toHaveBeenCalledWith('/messaging?conversation=4')
  })

  it('VERIFIED: an off-site or protocol-relative next is ignored', async () => {
    routeQuery = { next: '//evil.example/x' }
    getStatus.mockResolvedValue(status({ state: 'VERIFIED' }))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="age-continue"]').exists()).toBe(false)
  })

  it('a start failure with the attempts-exceeded code toasts the daily-cap copy', async () => {
    getStatus.mockResolvedValue(status())
    start.mockRejectedValue({
      response: {
        status: 429,
        data: { errorMsg: { errorKey: 'security.ageVerificationAttemptsExceeded' } },
      },
    })
    const wrapper = await mountPage()
    await wrapper.find('[data-test="age-start"]').trigger('click')
    await flushPromises()
    expect(notify).toHaveBeenCalledWith(
      expect.objectContaining({ message: expect.stringContaining('verification attempts') }),
    )
  })
})
