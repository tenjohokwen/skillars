import type { Page } from '@playwright/test'

export interface OverflowReport {
  scrollWidth: number
  clientWidth: number
  overflows: boolean
}

/**
 * Measures horizontal page overflow — a real assertion, not a screenshot. Run it at a phone
 * viewport (e.g. 390 px) to catch a layout that forces sideways scrolling.
 */
export async function measureHorizontalOverflow(
  page: Pick<Page, 'evaluate'>,
): Promise<OverflowReport> {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  return { scrollWidth, clientWidth, overflows: scrollWidth > clientWidth }
}

export async function assertNoHorizontalOverflow(
  page: Pick<Page, 'evaluate' | 'url'>,
): Promise<void> {
  const report = await measureHorizontalOverflow(page)
  if (report.overflows) {
    throw new Error(
      `Horizontal overflow on ${page.url()}: scrollWidth ${report.scrollWidth} > clientWidth ${report.clientWidth}`,
    )
  }
}
