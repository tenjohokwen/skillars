// S-09: display helpers for the safeguarding check.

/**
 * "2031-03-15" -> "03/2031". Pure string handling on the API's ISO date: going through Date would shift the
 * month for a viewer west of UTC when the date is the 1st.
 */
export function formatMonthYear(isoDate) {
  const match = /^(\d{4})-(\d{2})-\d{2}/.exec(isoDate ?? '')
  return match ? `${match[2]}/${match[1]}` : ''
}
