// Notification centre (UI-gap chunk C9): renders an inbox entry's text from its server-supplied
// `type` (an EmailTemplate name) and string `params`.
//
// The i18n key is built at runtime from `type`; NotificationTextSpec checks that every in-app type
// the backend can emit has a message in all three locales.

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/

/** Formats ISO-8601 instant params in the viewer's locale; other params pass through unchanged. */
export function formatNotificationParams(params, locale) {
  const out = {}
  for (const [key, value] of Object.entries(params || {})) {
    if (typeof value === 'string' && ISO_INSTANT.test(value)) {
      const date = new Date(value)
      out[key] = Number.isNaN(date.getTime())
        ? value
        : date.toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' })
    } else {
      out[key] = value
    }
  }
  return out
}

/**
 * @param {{ t: Function, te: Function, locale: string }} i18n — from useI18n()
 * @param {{ type: string, params?: object }} notification
 */
export function notificationText(i18n, notification) {
  const key = `notifications.types.${notification?.type}`
  if (!notification?.type || !i18n.te(key)) return i18n.t('notifications.typeFallback')
  return i18n.t(key, formatNotificationParams(notification.params, i18n.locale))
}

/** Only internal hash-router paths are followed — never an absolute or protocol-relative URL. */
export function safeDeepLink(link) {
  return typeof link === 'string' && /^\/(?!\/)[A-Za-z0-9/_-]*$/.test(link) ? link : null
}

export const CATEGORY_ICONS = {
  BOOKING: 'event',
  REMINDER: 'alarm',
  SESSION_PACK: 'confirmation_number',
  DEVELOPMENT: 'insights',
  MODERATION: 'shield',
  ACCOUNT: 'person',
  SYSTEM: 'info',
}
