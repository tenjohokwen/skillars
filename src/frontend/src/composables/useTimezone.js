export function useTimezone(canonicalTimezone) {
  const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

  function formatInPitchTimezone(isoString) {
    return new Intl.DateTimeFormat('en', {
      timeZone: canonicalTimezone,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(isoString))
  }

  function formatInBrowserTimezone(isoString) {
    return new Intl.DateTimeFormat('en', {
      timeZone: browserTimezone,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(isoString))
  }

  const timezonesDiffer = canonicalTimezone != null && canonicalTimezone !== browserTimezone

  return { formatInPitchTimezone, formatInBrowserTimezone, browserTimezone, timezonesDiffer }
}
