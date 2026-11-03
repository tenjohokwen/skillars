/**
 * A coach shared-slot value (`?slot=YYYY-MM-DDTHH:mm`, wall-clock in the coach's zone) for a weekday
 * of NEXT week. The request page shows one week; a shared slot makes it load that slot's week and
 * preselect it, so a booking spec never depends on which day of the week it runs. Provisioned
 * coaches are available Monday to Friday, 09:00–17:00, on the hour.
 */
export function nextWeekSlot(isoWeekday: 1 | 2 | 3 | 4 | 5 = 2, time = '10:00'): string {
  const now = new Date()
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()))
  const today = date.getUTCDay() || 7
  date.setUTCDate(date.getUTCDate() + (8 - today) + (isoWeekday - 1))
  return `${date.toISOString().slice(0, 10)}T${time}`
}
