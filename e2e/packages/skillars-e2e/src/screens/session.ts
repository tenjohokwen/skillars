import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// I · Session delivery, part 4: the drill library, the session builder and the template vault.

/** Quasar q-select: open it and pick an option by its visible text. */
async function pick(select: Locator, label: string): Promise<void> {
  await select.click()
  await select.page().getByRole('option', { name: label, exact: true }).click()
}

export class DrillLibraryScreen extends Screen {
  readonly route = R.drillLibrary
  readonly readyTestId = 'new-drill'
  platformTab = () => this.tid('drill-tab-platform')
  privateTab = () => this.tid('drill-tab-private')
  search = () => this.tid('drill-search')
  filter = () => this.tid('drill-filter')
  filterDifficulty = () => this.tid('drill-filter-difficulty')
  filterApply = () => this.tid('drill-filter-apply')
  filterClear = () => this.tid('drill-filter-clear')
  noResults = () => this.tid('drill-no-results')
  clearFiltersFromEmpty = () => this.tid('drill-no-results-clear-filters')
  grid = () => this.tid('drill-grid')
  cards = () => this.tid('drill-card')
  card = (name: string) => this.cards().filter({ has: this.page.getByText(name, { exact: true }) })
  difficultyOf = (card: Locator) => this.within(card, 'drill-difficulty')
  /** Cards whose difficulty badge reads exactly this tier. */
  cardsOfTier = (tier: string) =>
    this.cards().filter({
      has: this.tid('drill-difficulty').filter({ hasText: new RegExp(`^\\s*${tier}\\s*$`) }),
    })

  /** Opens the filter sheet, sets the difficulty tier and applies it. */
  async filterByDifficulty(tier: string): Promise<void> {
    await this.filter().click()
    await pick(this.filterDifficulty(), tier)
    await this.filterApply().click()
    await this.tid('drill-filter-dialog').waitFor({ state: 'hidden' })
  }
}

export class SessionBuilderScreen extends Screen {
  readonly route = R.sessionBuilder
  readonly readyTestId = 'plan-blocks'
  privateTab = () => this.tid('plan-tab-private')
  drillSearch = () => this.tid('plan-drill-search')
  drillCards = () => this.tid('drill-card')
  /** The Add-to-session button on the library card with this name. */
  addDrill = (name: string) =>
    this.within(
      this.drillCards().filter({ has: this.page.getByText(name, { exact: true }) }),
      'drill-add-to-session',
    )
  blocks = () => this.tid('session-block')
  block = (index: number) => this.blocks().nth(index)
  /** The clickable wrapper that makes a block the active one. */
  blockWrapper = (index: number) => this.tid('plan-block').nth(index)
  blockHandle = (index: number) => this.within(this.block(index), 'block-drag-handle')
  blockName = (index: number) => this.within(this.block(index), 'block-name')
  drillsIn = (index: number) => this.within(this.block(index), 'block-drill')
  drillHandle = (row: Locator) => this.within(row, 'block-drill-handle')
  focusTab = () => this.tid('plan-tab-focus')
  focus = (code: string) => this.page.locator(`[data-testid="focus-chip"][data-focus="${code}"]`)
  saveDraft = () => this.tid('plan-save-draft')
  save = () => this.tid('plan-save')
  /** Offered once the plan exists on the server, so it doubles as "the save went through". */
  saveAsTemplate = () => this.tid('plan-save-template')
  templateName = () => this.tid('template-name-input')
  templateSave = () => this.tid('template-name-save')
  useTemplate = () => this.tid('use-template')
  templateChoices = () => this.tid('use-template-item')
  templateBanner = () => this.tid('plan-template-banner')

  /** Makes a block the target of the next add (a click anywhere on its card does it). */
  async activate(index: number): Promise<void> {
    await this.block(index).click()
    await this.blockWrapper(index).and(this.page.locator('[data-active="true"]')).waitFor()
  }

  /** Searches the library column, then adds that drill to the active block. */
  async add(name: string): Promise<void> {
    await this.drillSearch().fill(name)
    await this.addDrill(name).click()
  }

  /**
   * Drags `source` onto `target` by its handle with real mouse steps. vuedraggable (SortableJS)
   * listens for native drag events, which Chromium only raises for a pointer that actually moves.
   */
  async drag(source: Locator, target: Locator): Promise<void> {
    const from = await source.boundingBox()
    const to = await target.boundingBox()
    if (!from || !to) throw new Error('drag: an element is not on screen')
    const mouse = this.page.mouse
    await mouse.move(from.x + from.width / 2, from.y + from.height / 2)
    await mouse.down()
    await mouse.move(from.x + from.width / 2, from.y + from.height / 2 + 8, { steps: 4 })
    await mouse.move(to.x + to.width / 2, to.y + to.height / 2, { steps: 20 })
    await mouse.move(to.x + to.width / 2, to.y + to.height / 2 + 2, { steps: 4 })
    await mouse.up()
  }
}

export class TemplateVaultScreen extends Screen {
  readonly route = R.sessionTemplates
  readonly readyTestId = 'template-item'
  items = () => this.tid('template-item')
  item = (name: string) =>
    this.items().filter({
      has: this.page.getByTestId('template-name').getByText(name, { exact: true }),
    })
  meta = (item: Locator) => this.within(item, 'template-meta')
  empty = () => this.tid('templates-empty')
  menu = (item: Locator) => this.within(item, 'template-menu')
  // The menu is teleported to the body.
  menuUse = () => this.tid('template-use')
  menuRename = () => this.tid('template-rename')
  menuDelete = () => this.tid('template-delete')
  renameInput = () => this.tid('template-rename-input')
  renameSave = () => this.tid('template-rename-save')
  deleteConfirm = () => this.tid('template-delete-confirm')
  deleteCancel = () => this.tid('template-delete-cancel')
}
