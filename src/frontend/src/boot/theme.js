import { defineBoot } from '#q-app/wrappers'

const STORAGE_KEY = 'skillars-theme'

export function toggleTheme() {
  if (typeof document === 'undefined') return
  const isLight = document.documentElement.getAttribute('data-theme') === 'light'
  if (isLight) {
    document.documentElement.removeAttribute('data-theme')
    try { localStorage.setItem(STORAGE_KEY, 'dark') } catch { /* private browsing */ }
  } else {
    document.documentElement.setAttribute('data-theme', 'light')
    try { localStorage.setItem(STORAGE_KEY, 'light') } catch { /* private browsing */ }
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
    } catch { /* private browsing */ }
    document.body.classList.add('app-bg')
  }
})
