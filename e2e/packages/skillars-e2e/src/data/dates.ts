import { E2E } from '../env.js'

/** The browser's zone: playwright.config.ts sets timezoneId from TZ, defaulting to UTC. */
export const BROWSER_TIME_ZONE = process.env.TZ || 'UTC'

/** A date as the pack dashboard and wallet pages print it (`toLocaleDateString`, dateStyle medium). */
export function uiDate(instant: string | Date): string {
  return new Date(instant).toLocaleDateString(E2E.locale, {
    dateStyle: 'medium',
    timeZone: BROWSER_TIME_ZONE,
  })
}

/** `YYYY-MM-DD` for a `<input type="date">`, `days` calendar days from today in the browser's zone. */
export function dateInputValue(days: number): string {
  const d = new Date(Date.now() + days * 86_400_000)
  return d.toLocaleDateString('en-CA', { timeZone: BROWSER_TIME_ZONE })
}

export const DAY_MS = 86_400_000
