// fix/payment-money-integrity: PaymentApiAdvice answers a failed cash-out with
// { helpCode: 'payment.<code>', errorMsg, fieldErrors } — there is no `message` field, so the page's
// old `err.response.data.message ?? insufficientCredit` fallback told the parent "insufficient
// credit" for EVERY failure (refund failed, amount above the refundable charge, too small...).
//
// Mutation check: restore the `data.message ?? 'payment.credits.insufficientCredit'` line → the
// refundFailed / cashOutExceedsRefundable / unknown-code assertions go RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

vi.mock('src/api/payment.api', () => ({ cashOut: vi.fn() }))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { cashOut } from 'src/api/payment.api'
import ParentCreditWalletPage from 'src/pages/parent/ParentCreditWalletPage.vue'

const mounted = []

async function mountPage(creditBalance = { balance: 100, currency: 'EUR' }) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { payment: { creditBalance } },
  })
  const wrapper = mount(ParentCreditWalletPage, {
    shallow: true,
    global: {
      plugins: [pinia],
      stubs: {
        QPage: { template: '<div><slot /></div>' },
        QCard: { template: '<div><slot /></div>' },
        QCardSection: { template: '<div><slot /></div>' },
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

function rejectWith(helpCode) {
  cashOut.mockRejectedValueOnce({
    response: { status: 422, data: { helpCode, errorMsg: { key: helpCode } } },
  })
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('ParentCreditWalletPage.vue — cash-out error messages', () => {
  it.each([
    ['payment.insufficientCredit', 'payment.credits.insufficientCredit'],
    ['payment.noPaymentMethod', 'payment.credits.noPaymentMethod'],
    ['payment.cashOutTooSmall', 'payment.credits.cashOutTooSmall'],
    ['payment.cashOutExceedsRefundable', 'payment.credits.cashOutExceedsRefundable'],
    ['payment.cashOutBlockedByDispute', 'payment.credits.cashOutBlockedByDispute'],
    ['payment.cashOutExceedsCashable', 'payment.credits.cashOutExceedsCashable'],
    ['payment.refundFailed', 'payment.credits.refundFailed'],
    ['payment.refundOutcomeUnknown', 'payment.credits.refundOutcomeUnknown'],
  ])('maps backend helpCode %s to %s', async (helpCode, i18nKey) => {
    const wrapper = await mountPage()
    wrapper.vm.cashoutAmount = 50
    rejectWith(helpCode)

    await wrapper.vm.handleCashOut()

    expect(wrapper.vm.cashoutError).toBe(i18nKey)
  })

  it('falls back to a generic message for an unknown failure, not "insufficient credit"', async () => {
    const wrapper = await mountPage()
    wrapper.vm.cashoutAmount = 50
    cashOut.mockRejectedValueOnce(new Error('Network Error'))

    await wrapper.vm.handleCashOut()

    expect(wrapper.vm.cashoutError).toBe('payment.credits.cashoutFailed')
  })

  it('clears the error and notifies on success', async () => {
    const wrapper = await mountPage()
    wrapper.vm.cashoutAmount = 50
    cashOut.mockResolvedValueOnce({})

    await wrapper.vm.handleCashOut()

    expect(cashOut).toHaveBeenCalledWith(50)
    expect(wrapper.vm.cashoutError).toBeNull()
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })
})

// fix/payment-wallet-dispute-integrity: spend-only credit (an expired-pack refund) can be booked with but never
// cashed out. Mutation check: drop the v-if split block, or compare the cash-out amount against `balance`
// again, and these go RED.
describe('ParentCreditWalletPage.vue — cashable vs spend-only credit', () => {
  const split = { balance: 100, currency: 'EUR', cashableBalance: 40, spendOnlyBalance: 60 }

  it('shows the cashable and booking-only parts when part of the credit is spend-only', async () => {
    const wrapper = await mountPage(split)

    const block = wrapper.find('[data-testid="wallet-split"]')
    expect(block.exists()).toBe(true)
    expect(block.text()).toContain('payment.credits.cashableBalance')
    expect(block.text()).toContain('payment.credits.spendOnlyBalance')
    expect(wrapper.vm.cashable).toBe(40)
    expect(wrapper.vm.spendOnly).toBe(60)
  })

  it('hides the split when everything is cashable (and for a response without it)', async () => {
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="wallet-split"]').exists()).toBe(false)
    expect(wrapper.vm.cashable).toBe(100)
  })

  it('disables the cash-out above the cashable amount', async () => {
    const wrapper = await mountPage(split)
    wrapper.vm.cashoutAmount = 50
    expect(wrapper.vm.cashoutDisabled).toBe(true)

    wrapper.vm.cashoutAmount = 40
    expect(wrapper.vm.cashoutDisabled).toBe(false)
  })
})
