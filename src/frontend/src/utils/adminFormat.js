// Shared helpers for the admin console pages (feat/admin-operations).

/** Canonical (any-version) UUID string, case-insensitive. */
export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value.trim())
}

/** Medium date + short time in the given locale; an em dash for a missing value. */
export function formatDateTime(iso, locale) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
