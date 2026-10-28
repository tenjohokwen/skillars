// feat/messaging-completion: messagingErrors.js maps backend MessagingErrorCode values to i18n keys.
// The keys are chosen at runtime (so i18nTemplateKeyResolutionSpec can only record the call sites),
// hence this spec resolves every key the map — and the page's runtime-picked moderation/composer
// keys — can produce, in all three locales.

import { describe, it, expect } from 'vitest'
import enUS from 'src/i18n/en-US/index.js'
import deDE from 'src/i18n/de-DE/index.js'
import frFR from 'src/i18n/fr-FR/index.js'
import { messagingErrorI18nKey, messagingErrorCode } from 'src/utils/messagingErrors'

const BACKEND_CODES = [
  'messaging.noBookingRelationship',
  'messaging.invalidContent',
  'messaging.notAParty',
  'messaging.playerDirectMessagingRestricted',
  'messaging.parentalOversightNotApplicable',
  'messaging.parentMessagingRestrictedForAdult',
  'messaging.conversationBlocked',
]

const RUNTIME_PAGE_KEYS = [
  'messaging.errors.generic',
  'messaging.moderation.pendingReviewOwn',
  'messaging.moderation.underReview',
  'messaging.moderation.blockedOwn',
  'messaging.moderation.blocked',
  'messaging.blockedBanner',
  'messaging.cannotSend',
]

const resolve = (messages, key) => key.split('.').reduce((node, part) => node?.[part], messages)
const errorFor = (code) => ({ response: { data: { errorMsg: { errorKey: code } } } })

describe('messagingErrors', () => {
  it.each(BACKEND_CODES)('maps %s to its own messaging.errors.* key', (code) => {
    expect(messagingErrorI18nKey(errorFor(code))).toBe(
      `messaging.errors.${code.slice('messaging.'.length)}`,
    )
  })

  it('falls back to the generic key for unknown codes, network errors and nullish input', () => {
    expect(messagingErrorI18nKey(errorFor('messaging.somethingNew'))).toBe(
      'messaging.errors.generic',
    )
    expect(messagingErrorI18nKey(new Error('Network Error'))).toBe('messaging.errors.generic')
    expect(messagingErrorI18nKey(undefined)).toBe('messaging.errors.generic')
    expect(messagingErrorCode(undefined)).toBeNull()
  })

  it.each([
    ['en-US', enUS],
    ['de-DE', deDE],
    ['fr-FR', frFR],
  ])('every runtime-chosen messaging key resolves to a string in %s', (_locale, messages) => {
    const keys = [
      ...BACKEND_CODES.map((c) => messagingErrorI18nKey(errorFor(c))),
      ...RUNTIME_PAGE_KEYS,
    ]
    for (const key of keys) {
      expect(typeof resolve(messages, key), key).toBe('string')
    }
  })
})
