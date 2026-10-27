// A coach "share this slot" link carries ?slot=YYYY-MM-DDTHH:mm — the slot's wall-clock start in
// the COACH's timezone (that is what the coach sees on their schedule and what the booking page
// displays). Kept as a plain local date-time rather than a UTC instant so the link can be built
// from an availability window (dayOfWeek + startTime) without any timezone arithmetic.

const SHARED_SLOT_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/

/** The validated ?slot value, or null for anything malformed / absent. */
export function parseSharedSlot(raw) {
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && SHARED_SLOT_RE.test(value) ? value : null
}

/** The Monday ('YYYY-MM-DD') of the week containing a validated shared slot, or null. */
export function sharedSlotWeekStart(sharedSlot) {
  if (!sharedSlot) return null
  const [y, m, d] = sharedSlot.slice(0, 10).split('-').map(Number)
  // Pure calendar arithmetic in UTC — the slot is a wall-clock date, no timezone involved.
  const date = new Date(Date.UTC(y, m - 1, d))
  const day = date.getUTCDay()
  date.setUTCDate(date.getUTCDate() + (day === 0 ? -6 : 1 - day))
  return date.toISOString().slice(0, 10)
}

/** A wall-clock 'YYYY-MM-DDTHH:mm' for an instant, as seen in the given IANA timezone. */
export function toZonedLocalIso(instant, timeZone) {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return null
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: timeZone || 'UTC',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(date)
      .map((p) => [p.type, p.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}
