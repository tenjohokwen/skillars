import { useI18n } from 'vue-i18n'

export function useTimezone(canonicalTimezone) {
  const { locale } = useI18n()
  const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

  function formatInPitchTimezone(isoString) {
    return new Intl.DateTimeFormat(locale.value, {
      timeZone: canonicalTimezone,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(isoString))
  }

  function formatInBrowserTimezone(isoString) {
    return new Intl.DateTimeFormat(locale.value, {
      timeZone: browserTimezone,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(isoString))
  }

  const timezonesDiffer = canonicalTimezone != null && canonicalTimezone !== browserTimezone

  return { formatInPitchTimezone, formatInBrowserTimezone, browserTimezone, timezonesDiffer }
}
