import { expect, type Locator, type Page } from '@playwright/test'

export interface VisualPolicy {
  /** Elements always masked (timestamps, avatars, anything data-dependent). */
  defaultMasks?: (page: Page) => Locator[]
  /** Stylesheet(s) applied while capturing, e.g. to hide a blinking cursor or a live clock. */
  stylePath?: string | string[]
  maxDiffPixelRatio?: number
}

export interface VisualCheckOptions {
  mask?: Locator[]
  fullPage?: boolean
}

/**
 * The masked screenshot policy: animations off, caret hidden, data-dependent regions masked, a
 * small tolerance. One place to change it instead of forty `toHaveScreenshot` call sites.
 */
export async function expectScreen(
  page: Page,
  name: string,
  policy: VisualPolicy = {},
  options: VisualCheckOptions = {},
): Promise<void> {
  await expect(page).toHaveScreenshot(`${name}.png`, {
    animations: 'disabled',
    caret: 'hide',
    fullPage: options.fullPage ?? true,
    mask: [...(policy.defaultMasks?.(page) ?? []), ...(options.mask ?? [])],
    maxDiffPixelRatio: policy.maxDiffPixelRatio ?? 0.01,
    stylePath: policy.stylePath,
  })
}
