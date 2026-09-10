// skillars-deferred-104: shared setup for the frontend Vitest unit suite.
// Runs once before each test file (see vitest.config.mjs `test.setupFiles`).

import { config } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { installQuasarPlugin } from '@quasar/quasar-app-extension-testing-unit-vitest'

// Register the Quasar Vue plugin for mounted components (q-* components, $q, directives).
// No-op for pure-logic specs that never call `mount()`.
installQuasarPlugin()

// Minimal real vue-i18n instance, installed globally for every mounted component.
// Required (not just convenience): components that call `useI18n()` in <script setup>
// — e.g. SkillsRadarChart.vue, which reads `locale` for date formatting — throw
// "Need to install with `app.use` function" without an i18n instance on the app.
// `messages: {}` + silenced missing-key warnings means `$t('some.key')` returns the key
// verbatim, which is exactly what component specs assert against today.
const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'en-US',
  fallbackLocale: 'en-US',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'en-US': {} },
})
config.global.plugins.unshift(i18n)

// Global vue-i18n `$t` / `$tc` stub so component specs don't each re-declare it. Mirrors the
// inline mock the pre-existing SkillsRadarChartSpec.js carries in its `globalConfig.global.mocks`:
// keys pass through unchanged, except the one interpolated "last assessed" string the radar
// component renders in a (stubbed) tooltip. A spec that needs real translations can register
// its own i18n instance and override this.
config.global.mocks.$t = (key, params) =>
  params && params.date ? `Last assessed: ${params.date}` : key
config.global.mocks.$tc = (key) => key

// Pinia is intentionally NOT installed globally here. Specs that need a store opt in with
// `createTestingPinia()` from `@pinia/testing`.
