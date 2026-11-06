import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// Coach onboarding and the pages it feeds (test-id wave W2).

/** Quasar q-select: open it and pick an option by its visible text. */
async function pick(select: Locator, label: string): Promise<void> {
  await select.click()
  await select.page().getByRole('option', { name: label, exact: true }).click()
}

/**
 * The five-step profile builder. Every step's Next button is `builder-next`, and only the current
 * step is rendered, so the screen's locators always mean "on the step that is showing".
 */
export class CoachBuilderScreen extends Screen {
  readonly route = R.coachBuilder
  readonly readyTestId = 'builder-step-indicator'
  stepIndicator = () => this.tid('builder-step-indicator')
  stepTitle = () => this.tid('builder-step-title')
  railSteps = () => this.tid('builder-rail-step')
  error = () => this.tid('builder-error')
  next = () => this.tid('builder-next')

  // Step 1 · basics
  displayName = () => this.tid('builder-display-name')
  bio = () => this.tid('builder-bio')
  contactWarning = () => this.tid('builder-contact-warning')
  city = () => this.tid('builder-city')
  district = () => this.tid('builder-district')
  languages = () => this.tid('builder-languages')
  /** Steps 1 and 4 (on step 4 only as the recovery picker). */
  timezone = () => this.tid('builder-timezone')
  /** The picker's filter <input> (Quasar forwards the test id there when use-input is on). */
  timezoneSelect = () => this.tid('timezone-select')
  timezoneNoOption = () => this.tid('timezone-no-option')
  timezoneUnknownHint = () => this.tid('timezone-browser-unknown')

  // Step 2 · specialties
  specialties = () => this.tid('builder-specialties')
  ageGroup = (group: 'u10' | '10-12' | '13-17' | 'adult') => this.tid(`builder-age-${group}`)

  // Step 3 · pricing
  perSessionPrice = () => this.tid('builder-per-session-price')
  packRows = () => this.tid('builder-pack-row')
  addPack = () => this.tid('builder-add-pack')
  packError = () => this.tid('builder-pack-error')

  // Step 4 · availability
  windowRows = () => this.tid('builder-window-row')
  addWindow = () => this.tid('builder-add-window')
  timezoneReadonly = () => this.tid('builder-timezone-readonly')

  // Step 5 · photo
  photoFile = () => this.tid('builder-photo-file')
  photoUpload = () => this.tid('builder-photo-upload')
  photoSkip = () => this.tid('builder-photo-skip')

  async chooseLanguages(...labels: string[]): Promise<void> {
    for (const label of labels) await pick(this.languages(), label)
    await this.closeMenu()
  }

  async chooseSpecialties(...labels: string[]): Promise<void> {
    for (const label of labels) await pick(this.specialties(), label)
    await this.closeMenu()
  }

  /**
   * Close a multi-select's option menu and wait until it has gone: a click sent while it is still
   * animating out lands on the menu, not on the button underneath (seen with Next).
   */
  private async closeMenu(): Promise<void> {
    await this.page.keyboard.press('Escape')
    await this.page.getByRole('listbox').waitFor({ state: 'hidden' })
  }

  /** Type into the timezone picker's filter, as a coach searching the list would. */
  async searchTimezone(text: string): Promise<void> {
    // Open the menu first: typing alone does not reliably open it, and the no-match row only renders
    // inside an open menu. fill() on the q-select's root <label> retargets to its filter input.
    await this.timezoneSelect().click()
    await this.page.getByRole('listbox').waitFor()
    await this.timezoneSelect().fill(text)
  }

  async chooseTimezone(zone: string): Promise<void> {
    await this.searchTimezone(zone)
    await this.page.getByRole('option', { name: zone, exact: true }).click()
    // Wait until the choice has landed, then close the menu: a Next clicked earlier (seen under
    // load) saved the preselected browser zone instead. The selected value renders as its own text
    // in the picker's wrapper (`timezone-select` is on the inner <input>, which Quasar's use-input
    // select forwards attributes to); exact, because the hint "e.g. Europe/Berlin" contains it too.
    await this.timezone().getByText(zone, { exact: true }).waitFor()
    // Escape dismisses the option list (a use-input select can keep it open while focused); its
    // listbox is not reliably hidden afterwards, so nothing waits on that.
    await this.page.keyboard.press('Escape')
  }

  async addPackRow(pack: { sessionCount: number; totalPrice: number; label: string }) {
    await this.addPack().click()
    const row = this.packRows().last()
    await this.within(row, 'builder-pack-sessions').fill(String(pack.sessionCount))
    await this.within(row, 'builder-pack-price').fill(String(pack.totalPrice))
    await this.within(row, 'builder-pack-label').fill(pack.label)
  }

  /** `day` is the option's visible text (resolve it through `t()`); times are HH:mm. */
  async addWindowRow(day: string, start: string, end: string): Promise<void> {
    await this.addWindow().click()
    const row = this.windowRows().last()
    await pick(this.within(row, 'builder-window-day'), day)
    await this.within(row, 'builder-window-start').fill(start)
    await this.within(row, 'builder-window-end').fill(end)
  }
}

export class AvailabilityScreen extends Screen {
  readonly route = R.availability
  readonly readyTestId = 'availability-window-save'
  weekLabel = () => this.tid('availability-week-label')
  /** Monday first, as the calendar renders them. */
  calendarDays = () => this.tid('calendar-day')
  windows = () => this.tid('calendar-window')
  /** Windows drawn in one day column; 0 is Monday. */
  windowsOn = (dayIndex: number) =>
    this.within(this.calendarDays().nth(dayIndex), 'calendar-window')
  blocks = () => this.tid('calendar-block')
  windowDay = () => this.tid('availability-window-day')
  windowStart = () => this.tid('availability-window-start')
  windowEnd = () => this.tid('availability-window-end')
  saveWindow = () => this.tid('availability-window-save')
  windowSaved = () => this.tid('availability-window-saved')
  windowError = () => this.tid('availability-window-error')
  windowCancel = () => this.tid('availability-window-cancel')
  prevWeek = () => this.tid('availability-prev-week')
  nextWeek = () => this.tid('availability-next-week')
  /** Blocks drawn in one day column; 0 is Monday. */
  blocksOn = (dayIndex: number) => this.within(this.calendarDays().nth(dayIndex), 'calendar-block')
  deleteWindow = (window: Locator) => this.within(window, 'calendar-window-delete')
  deleteBlock = (block: Locator) => this.within(block, 'calendar-block-delete')

  /** Pick the weekday (its long name in the app's locale) in the add/edit panel. */
  chooseDay = (label: string) => pick(this.windowDay(), label)

  // The personal-time block panel.
  blockStartDate = () => this.tid('availability-block-start-date')
  blockStartTime = () => this.tid('availability-block-start-time')
  blockEndDate = () => this.tid('availability-block-end-date')
  blockEndTime = () => this.tid('availability-block-end-time')
  blockReason = () => this.tid('availability-block-reason')
  saveBlock = () => this.tid('availability-block-save')
  blockSaved = () => this.tid('availability-block-saved')

  // The confirmation before a delete, or before an edit that leaves booked sessions outside the hours.
  impactDialog = () => this.tid('availability-impact-dialog')
  impactMessage = () => this.tid('availability-impact-message')
  impactConfirm = () => this.tid('availability-impact-confirm')
  impactCancel = () => this.tid('availability-impact-cancel')
}

export class PackTiersScreen extends Screen {
  readonly route = R.packTiers
  readonly readyTestId = 'tier-create'
  activeTier = () => this.tid('tier-active')
  activeTierLabel = () => this.tid('tier-active-label')
  noActiveTier = () => this.tid('tier-none')
  createOrReplace = () => this.tid('tier-create')
  withdraw = () => this.tid('tier-withdraw')
  history = () => this.tid('tier-history-item')
  validity = () => this.tid('tier-validity')
  // The create/replace dialog is teleported to <body>; screen locators are page-wide, so that's fine.
  form = () => this.tid('tier-form')
  formLabel = () => this.tid('tier-form-label')
  formSessions = () => this.tid('tier-form-sessions')
  formPrice = () => this.tid('tier-form-price')
  formSubmit = () => this.tid('tier-form-submit')
  formCountError = () => this.tid('tier-form-count-error')
  formPriceError = () => this.tid('tier-form-price-error')
}
