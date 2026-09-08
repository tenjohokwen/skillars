import { defineBoot } from '#q-app/wrappers'

const STORAGE_KEY = 'skillars-theme'

// skillars-deferred-102 AC16 (skillars-1-2 W6): re-entrancy latch. toggleTheme is synchronous, so
// this never actually blocks a real click, but it makes a rapid double-invocation deterministic and
// guards against a future async caller: the DOM attribute is the single source of truth, read once
// per invocation, and every caller takes the returned state rather than re-reading the DOM.
let toggling = false

/**
 * Flip the persisted theme. Returns the resulting dark-mode boolean so callers never have to read
 * the DOM attribute back (which is what could desync a reactive ref from the DOM under a burst of
 * clicks). Safe to call before hydration — no-ops and reports dark when `document` is absent.
 */
export function toggleTheme() {
  if (typeof document === 'undefined') return true
  if (toggling) return isDarkMode()
  toggling = true
  try {
    const nextIsDark = document.documentElement.getAttribute('data-theme') === 'light'
    if (nextIsDark) {
      document.documentElement.removeAttribute('data-theme')
    } else {
      document.documentElement.setAttribute('data-theme', 'light')
    }
    try {
      localStorage.setItem(STORAGE_KEY, nextIsDark ? 'dark' : 'light')
    } catch {
      /* private browsing */
    }
    return nextIsDark
  } finally {
    toggling = false
  }
}

export function isDarkMode() {
  if (typeof document === 'undefined') return true
  return document.documentElement.getAttribute('data-theme') !== 'light'
}

export default defineBoot(() => {
  if (process.env.CLIENT) {
    // Apply persisted theme before first render — prevents flash
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved === 'light') {
        document.documentElement.setAttribute('data-theme', 'light')
      }
    } catch {
      /* private browsing */
    }
    document.body.classList.add('app-bg')
  }
})
