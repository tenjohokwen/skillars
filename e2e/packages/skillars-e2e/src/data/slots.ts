import type { Page } from '@playwright/test'
import { E2E } from '../env.js'

/**
 * A coach shared-slot value (`?slot=YYYY-MM-DDTHH:mm`, wall-clock in the coach's zone) for a weekday
 * of NEXT week. The request page shows one week; a shared slot makes it load that slot's week and
 * preselect it, so a booking spec never depends on which day of the week it runs. Provisioned
 * coaches are available Monday to Friday, 09:00–17:00, on the hour.
 */
export function nextWeekSlot(isoWeekday: 1 | 2 | 3 | 4 | 5 | 6 | 7 = 2, time = '10:00'): string {
  const now = new Date()
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()))
  const today = date.getUTCDay() || 7
  date.setUTCDate(date.getUTCDate() + (8 - today) + (isoWeekday - 1))
  return `${date.toISOString().slice(0, 10)}T${time}`
}

/** The instant a shared-slot wall-clock value (`YYYY-MM-DDTHH:mm`) denotes in `timeZone`. */
export function slotInstant(slot: string, timeZone: string): Date {
  const asUtc = Date.parse(`${slot}:00Z`)
  // The zone's offset at that moment, read back through Intl; one correction step suffices away
  // from a DST transition, and slots are never placed on one.
  const offset = (at: number) => {
    const parts = Object.fromEntries(
      new Intl.DateTimeFormat('en-US', {
        timeZone,
        hourCycle: 'h23',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
        .formatToParts(new Date(at))
        .map((p) => [p.type, p.value]),
    )
    const wall = Date.UTC(
      Number(parts.year),
      Number(parts.month) - 1,
      Number(parts.day),
      Number(parts.hour),
      Number(parts.minute),
    )
    return wall - at
  }
  return new Date(asUtc - offset(asUtc - offset(asUtc)))
}

/**
 * How the request page labels a slot starting at `slot` (wall-clock in `timeZone`): formatted in the
 * browser with the page's own options (SLOT_FORMAT in BookingRequestPage — explicit components,
 * because dateStyle cannot be combined with timeZoneName).
 */
export function slotLabel(page: Page, slot: string, timeZone: string): Promise<string> {
  return zonedLabel(page, slotInstant(slot, timeZone), timeZone, true)
}

/**
 * An instant as the app renders it in `timeZone`: with its zone name like the request page's slots,
 * or without, like the bookings list (dateStyle medium, timeStyle short). Runs in the browser, since
 * Node's Intl differs (it rejects some option sets Chrome's resolves differently).
 */
export function zonedLabel(
  page: Page,
  instant: Date,
  timeZone: string,
  withZone: boolean,
): Promise<string> {
  return page.evaluate(
    ([iso, zone, locale, labelled]) =>
      new Intl.DateTimeFormat(
        locale,
        labelled
          ? {
              timeZone: zone,
              year: 'numeric',
              month: 'short',
              day: 'numeric',
              hour: 'numeric',
              minute: '2-digit',
              timeZoneName: 'short',
            }
          : { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' },
      ).format(new Date(iso)),
    [instant.toISOString(), timeZone, E2E.locale, withZone] as const,
  )
}
