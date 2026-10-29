// fix/payment-subscription-entitlements: maps a subscription API failure to an i18n key under
// `subscription.errors.*`, so a declined card, a blocked re-subscribe (existing subscription still
// awaiting payment) or a tier change on an unhealthy subscription gets an actionable message.
//
// PaymentApiAdvice returns ErrorDto { helpCode, errorMsg: { errorKey, message } } with errorKey set to
// the backend code ('payment.subscription.<code>'). Anything unrecognised falls back to the caller's
// generic key.
const KNOWN_CODES = {
  'payment.subscription.paymentDeclined': 'subscription.errors.paymentDeclined',
  'payment.subscription.updatePaymentMethod': 'subscription.errors.updatePaymentMethod',
  'payment.subscription.notActive': 'subscription.errors.notActive',
  'payment.subscription.alreadyActive': 'subscription.errors.alreadyActive',
}

export function subscriptionErrorCode(err) {
  return err?.response?.data?.errorMsg?.errorKey ?? null
}

export function subscriptionErrorI18nKey(err, fallbackKey) {
  return KNOWN_CODES[subscriptionErrorCode(err)] ?? fallbackKey
}
