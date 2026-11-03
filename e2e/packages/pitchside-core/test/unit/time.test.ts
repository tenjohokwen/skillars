import { describe, expect, it } from 'vitest'
import { ageOn, CalendarDate, Clock, daysFromBirthday } from '../../src/index.js'

const at = (iso: string): Clock => new Clock(() => new Date(iso))

describe('CalendarDate', () => {
  it('validates and formats dates', () => {
    expect(CalendarDate.of(2024, 2, 29).toISODate()).toBe('2024-02-29')
    expect(() => CalendarDate.of(2025, 2, 29)).toThrow(/Invalid calendar date/)
    expect(CalendarDate.parse('2026-01-05').toISODate()).toBe('2026-01-05')
  })

  it('keeps Feb 29 + 1 year on Feb 28', () => {
    expect(CalendarDate.of(2024, 2, 29).plusYears(1).toISODate()).toBe('2025-02-28')
    expect(CalendarDate.of(2024, 2, 29).plusYears(4).toISODate()).toBe('2028-02-29')
  })

  it('does day arithmetic across month and year ends', () => {
    expect(CalendarDate.of(2026, 12, 31).plusDays(1).toISODate()).toBe('2027-01-01')
    expect(CalendarDate.of(2026, 3, 1).minusDays(1).toISODate()).toBe('2026-02-28')
    expect(CalendarDate.of(2026, 1, 1).daysUntil(CalendarDate.of(2026, 3, 1))).toBe(59)
  })
})

describe('ageOn / daysFromBirthday', () => {
  it('counts completed years', () => {
    const dob = CalendarDate.of(2008, 11, 3)
    expect(ageOn(dob, CalendarDate.of(2026, 11, 2))).toBe(17)
    expect(ageOn(dob, CalendarDate.of(2026, 11, 3))).toBe(18)
  })

  it('measures distance to the nearest birthday, wrapping the year end', () => {
    expect(daysFromBirthday(CalendarDate.of(2010, 1, 2), CalendarDate.of(2026, 12, 30))).toBe(3)
  })
})

describe('Clock', () => {
  it('builds a date of birth for an exact age that keeps clear of the birthday', () => {
    const clock = at('2026-11-03T10:00:00Z')
    const dob = clock.dateOfBirthForAge(11)
    expect(dob.toISODate()).toBe('2015-09-24')
    expect(ageOn(dob, clock.today())).toBe(11)
    expect(daysFromBirthday(dob, clock.today())).toBeGreaterThanOrEqual(40)
  })

  it('stays correct on every day of a leap year', () => {
    for (let day = 0; day < 366; day++) {
      const now = new Date(Date.UTC(2028, 0, 1 + day, 12))
      const clock = new Clock(() => now)
      for (const age of [9, 10, 12, 13, 17, 18]) {
        const dob = clock.dateOfBirthForAge(age)
        expect(ageOn(dob, clock.today())).toBe(age)
        expect(daysFromBirthday(dob, clock.today())).toBeGreaterThanOrEqual(39)
      }
    }
  })

  it('derives a window end from its start, never from a second now()', () => {
    let calls = 0
    const clock = new Clock(() => new Date(Date.UTC(2026, 0, 1, 12, 0, 0, calls++)))
    const window = clock.window(30, 60)
    expect(window.end.getTime() - window.start.getTime()).toBe(60 * 60_000)
    expect(calls).toBe(1)
  })

  it('offers relative instants and days', () => {
    const clock = at('2026-11-03T10:00:00Z')
    expect(clock.hoursAgo(49).toISOString()).toBe('2026-11-01T09:00:00.000Z')
    expect(clock.minutesFromNow(15).toISOString()).toBe('2026-11-03T10:15:00.000Z')
    expect(clock.daysFromNow(10).toISODate()).toBe('2026-11-13')
    expect(() => clock.window(0, 0)).toThrow(/positive/)
  })
})
