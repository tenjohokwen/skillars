import { defineBoot } from '#q-app/wrappers'
import { createI18n } from 'vue-i18n'
import messages from 'src/i18n'

// Create i18n instance
const i18n = createI18n({
  locale: 'en-US',
  legacy: false,
  globalInjection: true,
  messages,
})

/**
 * Get the current locale for API requests.
 * @returns {string} Current locale (e.g., 'en-US', 'fr-FR')
 */
export function getCurrentLocale() {
  return i18n.global.locale.value
}

export default defineBoot(({ app }) => {
  // Set i18n instance on app
  app.use(i18n)
})

export { i18n }
