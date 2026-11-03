const MS_PER_DAY = 86_400_000
const MS_PER_MINUTE = 60_000

/**
 * A timezone-free calendar date (year, month 1-12, day). Dates of birth, expiry dates and similar
 * "day" values must never be built from literals in a fixture — a hard-coded date of birth is a
 * test that passes for a year and then fails on one specific morning.
 */
export class CalendarDate {
  private constructor(
    readonly year: number,
    readonly month: number,
    readonly day: number,
  ) {}

  static of(year: number, month: number, day: number): CalendarDate {
    const probe = new Date(Date.UTC(year, month - 1, day))
    if (
      probe.getUTCFullYear() !== year ||
      probe.getUTCMonth() !== month - 1 ||
      probe.getUTCDate() !== day
    ) {
      throw new Error(`Invalid calendar date ${year}-${month}-${day}`)
    }
    return new CalendarDate(year, month, day)
  }

  /** The UTC calendar day of an instant. */
  static fromInstant(instant: Date): CalendarDate {
    return new CalendarDate(
      instant.getUTCFullYear(),
      instant.getUTCMonth() + 1,
      instant.getUTCDate(),
    )
  }

  static parse(iso: string): CalendarDate {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
    if (!match) throw new Error(`Expected YYYY-MM-DD, got "${iso}"`)
    return CalendarDate.of(Number(match[1]), Number(match[2]), Number(match[3]))
  }

  plusDays(days: number): CalendarDate {
    return CalendarDate.fromInstant(new Date(this.toUtcMidnight().getTime() + days * MS_PER_DAY))
  }

  minusDays(days: number): CalendarDate {
    return this.plusDays(-days)
  }

  /** Feb 29 plus a year lands on Feb 28, never on Mar 1. */
  plusYears(years: number): CalendarDate {
    const year = this.year + years
    const lastDay = new Date(Date.UTC(year, this.month, 0)).getUTCDate()
    return new CalendarDate(year, this.month, Math.min(this.day, lastDay))
  }

  minusYears(years: number): CalendarDate {
    return this.plusYears(-years)
  }

  compare(other: CalendarDate): number {
    return this.toUtcMidnight().getTime() - other.toUtcMidnight().getTime()
  }

  /** Whole days from this date to `other` (positive when `other` is later). */
  daysUntil(other: CalendarDate): number {
    return Math.round(
      (other.toUtcMidnight().getTime() - this.toUtcMidnight().getTime()) / MS_PER_DAY,
    )
  }

  toUtcMidnight(): Date {
    return new Date(Date.UTC(this.year, this.month - 1, this.day))
  }

  toISODate(): string {
    const pad = (value: number, size: number): string => String(value).padStart(size, '0')
    return `${pad(this.year, 4)}-${pad(this.month, 2)}-${pad(this.day, 2)}`
  }

  toString(): string {
    return this.toISODate()
  }
}

/** Completed years of age on `on` for someone born on `dateOfBirth`. */
export function ageOn(dateOfBirth: CalendarDate, on: CalendarDate): number {
  let age = on.year - dateOfBirth.year
  if (
    on.month < dateOfBirth.month ||
    (on.month === dateOfBirth.month && on.day < dateOfBirth.day)
  ) {
    age -= 1
  }
  return age
}

/** Days between `on` and the nearest anniversary of `dateOfBirth` (before or after). */
export function daysFromBirthday(dateOfBirth: CalendarDate, on: CalendarDate): number {
  const thisYear = dateOfBirth.plusYears(on.year - dateOfBirth.year)
  const candidates = [thisYear.minusYears(1), thisYear, thisYear.plusYears(1)]
  return Math.min(...candidates.map((candidate) => Math.abs(on.daysUntil(candidate))))
}

export interface TimeWindow {
  start: Date
  end: Date
}

/**
 * Relative dates for fixtures. Deliberately not a frozen clock: the backend runs its own
 * schedulers, and a frozen browser clock drifts away from them. Scenarios that need "time travel"
 * (expiry, grace periods) should seed a backdated row instead of moving a clock.
 */
export class Clock {
  constructor(private readonly nowFn: () => Date = () => new Date()) {}

  now(): Date {
    return this.nowFn()
  }

  today(): CalendarDate {
    return CalendarDate.fromInstant(this.now())
  }

  daysAgo(days: number): CalendarDate {
    return this.today().minusDays(days)
  }

  daysFromNow(days: number): CalendarDate {
    return this.today().plusDays(days)
  }

  yearsAgo(years: number): CalendarDate {
    return this.today().minusYears(years)
  }

  minutesFromNow(minutes: number): Date {
    return new Date(this.now().getTime() + minutes * MS_PER_MINUTE)
  }

  minutesAgo(minutes: number): Date {
    return this.minutesFromNow(-minutes)
  }

  hoursFromNow(hours: number): Date {
    return this.minutesFromNow(hours * 60)
  }

  hoursAgo(hours: number): Date {
    return this.minutesFromNow(-hours * 60)
  }

  /**
   * A date of birth that makes someone exactly `age` today and keeps their birthday at least
   * `marginDays` away from the run date, so an age-boundary test never runs ON a birthday.
   */
  dateOfBirthForAge(age: number, marginDays = 40): CalendarDate {
    if (marginDays < 1 || marginDays > 150) {
      throw new Error(`marginDays must be between 1 and 150, got ${marginDays}`)
    }
    const dateOfBirth = this.yearsAgo(age).minusDays(marginDays)
    const actual = ageOn(dateOfBirth, this.today())
    if (actual !== age) {
      throw new Error(`dateOfBirthForAge(${age}) produced age ${actual}: margin too large`)
    }
    return dateOfBirth
  }

  /**
   * A time window that starts `startInMinutes` from now and lasts `durationMinutes`. The end is
   * derived from the start, never from a second `now()` call — two separate "now" reads can
   * straddle a tick and break an end-after-start constraint only on a slow CI runner.
   */
  window(startInMinutes: number, durationMinutes: number): TimeWindow {
    if (durationMinutes <= 0) throw new Error('durationMinutes must be positive')
    const start = this.minutesFromNow(startInMinutes)
    return { start, end: new Date(start.getTime() + durationMinutes * MS_PER_MINUTE) }
  }
}

export const systemClock = new Clock()
