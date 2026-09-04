import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'
import pluginQuasar from '@quasar/app-vite/eslint'
import prettierSkipFormatting from '@vue/eslint-config-prettier/skip-formatting'

export default [
  {
    /**
     * Ignore the following files.
     * Please note that pluginQuasar.configs.recommended() already ignores
     * the "node_modules" folder for you (and all other Quasar project
     * relevant folders and files).
     *
     * ESLint requires "ignores" key to be the only one in this object
     */
    // ignores: []
  },

  ...pluginQuasar.configs.recommended(),
  js.configs.recommended,

  /**
   * https://eslint.vuejs.org
   *
   * pluginVue.configs.base
   *   -> Settings and rules to enable correct ESLint parsing.
   * pluginVue.configs[ 'flat/essential']
   *   -> base, plus rules to prevent errors or unintended behavior.
   * pluginVue.configs["flat/strongly-recommended"]
   *   -> Above, plus rules to considerably improve code readability and/or dev experience.
   * pluginVue.configs["flat/recommended"]
   *   -> Above, plus rules to enforce subjective community defaults to ensure consistency.
   */
  ...pluginVue.configs['flat/essential'],

  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',

      globals: {
        ...globals.browser,
        ...globals.node, // SSR, Electron, config files
        process: 'readonly', // process.env.*
        ga: 'readonly', // Google Analytics
        cordova: 'readonly',
        Capacitor: 'readonly',
        chrome: 'readonly', // BEX related
        browser: 'readonly', // BEX related
      },
    },

    // add your custom rules here
    rules: {
      'prefer-promise-reject-errors': 'off',
      // skillars-deferred-92 AC14.4: catches user-visible text that never reached the i18n
      // bundles. The allowlist is only for strings that are genuinely not translatable —
      // the brand name, symbols, and product acronyms. Anything else belongs in
      // src/i18n/{en-US,de-DE,fr-FR}/index.js.
      'vue/no-bare-strings-in-template': [
        'error',
        {
          allowlist: [
            'Skillars',
            'SLU',
            'Stripe',
            'EUR',
            '€',
            '$',
            '404',
            '—',
            '–',
            '✕',
            '✓',
            '←',
            '→',
            '&larr;',
            '·',
            '|',
            '/',
            '(',
            ')',
            ':',
            '-',
            '+',
            '*',
            ',',
            '.',
            '&',
            '#',
            '%',
            '@',
            '…',
            '...',
          ],
        },
      ],

      // allow debugger during development only
      'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'off',
    },
  },

  {
    files: ['src-pwa/custom-service-worker.js'],
    languageOptions: {
      globals: {
        ...globals.serviceworker,
      },
    },
  },

  prettierSkipFormatting,
]
