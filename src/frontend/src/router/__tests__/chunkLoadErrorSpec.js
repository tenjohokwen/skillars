// Pitchside X-10: a navigation whose page code cannot be fetched is reported, not dropped silently.

import { describe, it, expect, vi } from 'vitest'

vi.mock('src/boot/i18n', () => ({ i18n: { global: { t: (key) => key } } }))

import { isChunkLoadError } from 'src/router/index.js'

describe('isChunkLoadError', () => {
  it.each([
    'Failed to fetch dynamically imported module: http://x/assets/Page.js',
    'Importing a module script failed.',
    'error loading dynamically imported module',
    'Unable to preload CSS for /assets/ParentCreditWalletPage.css',
  ])('recognises "%s"', (message) => {
    expect(isChunkLoadError(new TypeError(message))).toBe(true)
  })

  it('ignores ordinary navigation errors', () => {
    expect(isChunkLoadError(new Error('Navigation cancelled'))).toBe(false)
    expect(isChunkLoadError(null)).toBe(false)
  })
})
