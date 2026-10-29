// fix/payment-subscription-entitlements: subscriptionErrors.js maps backend subscription error codes to
// i18n keys picked at runtime (i18nTemplateKeyResolutionSpec only records the call sites), so this spec
// resolves every key the map can produce in all three locales.

import { describe, it, expect } from 'vitest'
import enUS from 'src/i18n/en-US/index.js'
import deDE from 'src/i18n/de-DE/index.js'
import frFR from 'src/i18n/fr-FR/index.js'
import { subscriptionErrorI18nKey, subscriptionErrorCode } from 'src/utils/subscriptionErrors'

const BACKEND_CODES = [
  'payment.subscription.paymentDeclined',
  'payment.subscription.updatePaymentMethod',
  'payment.subscription.notActive',
  'payment.subscription.alreadyActive',
]

const FALLBACKS = [
  'subscription.coach.subscribeError',
  'subscription.coach.tierChangeError',
  'subscription.player.subscribeError',
]

function resolve(catalog, key) {
  return key.split('.').reduce((node, part) => node?.[part], catalog)
}

function apiError(code) {
  return { response: { data: { helpCode: code, errorMsg: { errorKey: code, message: code } } } }
}

describe('subscriptionErrors', () => {
  it('extracts the backend code from the ErrorDto', () => {
    expect(subscriptionErrorCode(apiError('payment.subscription.paymentDeclined'))).toBe(
      'payment.subscription.paymentDeclined',
    )
    expect(subscriptionErrorCode(new Error('network'))).toBeNull()
  })

  it.each(BACKEND_CODES)('maps %s to a subscription.errors.* key', (code) => {
    expect(subscriptionErrorI18nKey(apiError(code), 'fallback')).toMatch(/^subscription\.errors\./)
  })

  it('falls back to the caller key for unknown codes and network errors', () => {
    expect(subscriptionErrorI18nKey(apiError('payment.subscription.stripeError'), 'x.y')).toBe(
      'x.y',
    )
    expect(subscriptionErrorI18nKey(undefined, 'x.y')).toBe('x.y')
  })

  it.each([
    ['en-US', enUS],
    ['de-DE', deDE],
    ['fr-FR', frFR],
  ])('every producible key resolves to a string in %s', (_locale, catalog) => {
    const keys = [
      ...BACKEND_CODES.map((c) => subscriptionErrorI18nKey(apiError(c), 'none')),
      ...FALLBACKS,
    ]
    for (const key of keys) {
      expect(typeof resolve(catalog, key), key).toBe('string')
    }
  })
})
