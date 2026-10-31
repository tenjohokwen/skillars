// Maps a messaging API failure to an i18n key under `messaging.errors.*`.
//
// MessagingApiAdvice returns ErrorDto { helpCode, errorMsg: { errorKey, message } } where errorKey is
// a MessagingErrorCode value ('messaging.<code>'). Anything unrecognised (network failure, 5xx, a
// code this map does not know) falls back to the generic message rather than leaking raw text.
const KNOWN_CODES = {
  'messaging.noBookingRelationship': 'messaging.errors.noBookingRelationship',
  'messaging.invalidContent': 'messaging.errors.invalidContent',
  'messaging.notAParty': 'messaging.errors.notAParty',
  'messaging.playerDirectMessagingRestricted': 'messaging.errors.playerDirectMessagingRestricted',
  'messaging.parentalOversightNotApplicable': 'messaging.errors.parentalOversightNotApplicable',
  'messaging.parentMessagingRestrictedForAdult':
    'messaging.errors.parentMessagingRestrictedForAdult',
  'messaging.conversationBlocked': 'messaging.errors.conversationBlocked',
  // S-27: age-verification gates (SecurityError-less codes owned by the security module).
  'security.ageVerificationRequired': 'messaging.errors.ageVerificationRequired',
  'security.playerNotAgeVerified': 'messaging.errors.playerNotAgeVerified',
  'security.completedSessionRequired': 'messaging.errors.completedSessionRequired',
}

export function messagingErrorCode(err) {
  return err?.response?.data?.errorMsg?.errorKey ?? null
}

export function messagingErrorI18nKey(err) {
  return KNOWN_CODES[messagingErrorCode(err)] ?? 'messaging.errors.generic'
}
