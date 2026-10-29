// Notification centre (UI-gap chunk C9) — inbox text rendering and locale coverage.

import { describe, it, expect } from 'vitest'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US/index.js'
import deDE from 'src/i18n/de-DE/index.js'
import frFR from 'src/i18n/fr-FR/index.js'
import {
  notificationText,
  formatNotificationParams,
  safeDeepLink,
} from 'src/utils/notificationText'

// Every EmailTemplate that NotificationCatalog.java marks inApp — keep in sync with that switch.
const IN_APP_TYPES = [
  'BOOKING_REQUESTED',
  'BOOKING_RESCHEDULE_REQUESTED',
  'BOOKING_RESCHEDULE_DECLINED_BY_PARENT',
  'BOOKING_BATCH_REQUESTED',
  'BOOKING_CANCELLED_DUE_TO_PAUSE',
  'BOOKING_CANCELLED_BY_PARENT',
  'PLAYER_NO_SHOW',
  'BOOKING_CONFIRMED',
  'BOOKING_DECLINED',
  'BOOKING_PAYMENT_UNRESOLVED',
  'BOOKING_EXPIRED',
  'BOOKING_QUICK_COMPLETE_CONFIRM',
  'BOOKING_RESCHEDULE_DECLINED',
  'BOOKING_RESCHEDULE_REQUESTED_BY_COACH',
  'BOOKING_DUPLICATE_PROPOSED',
  'BOOKING_BATCH_ACCEPTED',
  'BOOKING_CANCELLED_BY_COACH',
  'COACH_NO_SHOW',
  'BOOKING_RESCHEDULE_ACCEPTED',
  'BOOKING_REMINDER',
  'SESSION_PACK_EXPIRY_WARNING',
  'SESSION_PACK_EXPIRED',
  'PACK_PAUSED',
  'PERFORMANCE_REPORT_SHARED',
  'VIDEO_MODERATION_OWNER_FLAGGED',
  'COACH_VISIBILITY_REDUCED',
  // B6: admin-outcome notices
  'COACH_SUSPENDED',
  'COACH_REINSTATED',
  'COACH_WARNING_ISSUED',
  'DISPUTE_RESOLVED',
  'DISPUTE_DISMISSED',
  'DISPUTE_COACH_UPHELD',
  'GDPR_EXPORT_READY',
  'REVIEW_APPROVED',
  'REVIEW_BLOCKED',
]

function i18nFor(locale) {
  const i18n = createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en-US',
    missingWarn: false,
    fallbackWarn: false,
    messages: { 'en-US': enUS, 'de-DE': deDE, 'fr-FR': frFR },
  })
  return { t: i18n.global.t, te: i18n.global.te, locale }
}

describe('notificationText', () => {
  it.each(['en-US', 'de-DE', 'fr-FR'])('%s has a message for every in-app type', (locale) => {
    const i18n = createI18n({
      legacy: false,
      locale,
      messages: { [locale]: { en: enUS, de: deDE, fr: frFR }[locale.slice(0, 2)] },
    })
    const missing = IN_APP_TYPES.filter(
      (type) => !i18n.global.te(`notifications.types.${type}`, locale),
    )
    expect(missing).toEqual([])
  })

  it('interpolates params and formats ISO instants', () => {
    const text = notificationText(i18nFor('en-US'), {
      type: 'BOOKING_CONFIRMED',
      params: { coachDisplayName: 'Coach Carter', requestedStartTime: '2026-11-01T10:00:00Z' },
    })

    expect(text).toContain('Coach Carter confirmed your session on')
    expect(text).not.toContain('2026-11-01T10:00:00Z')
  })

  it('falls back to a generic message for an unknown type', () => {
    expect(notificationText(i18nFor('en-US'), { type: 'SOMETHING_NEW', params: {} })).toBe(
      enUS.notifications.typeFallback,
    )
  })

  it('leaves non-instant params untouched', () => {
    expect(formatNotificationParams({ requestedCount: '3', note: 'x' }, 'en-US')).toEqual({
      requestedCount: '3',
      note: 'x',
    })
  })
})

describe('safeDeepLink', () => {
  it('accepts internal paths only', () => {
    expect(safeDeepLink('/parent/bookings')).toBe('/parent/bookings')
    expect(safeDeepLink('/parent/players/12/development')).toBe('/parent/players/12/development')
    expect(safeDeepLink('//evil.example.com')).toBeNull()
    expect(safeDeepLink('https://evil.example.com')).toBeNull()
    expect(safeDeepLink('/x?redirect=https://evil')).toBeNull()
    expect(safeDeepLink(null)).toBeNull()
  })
})
