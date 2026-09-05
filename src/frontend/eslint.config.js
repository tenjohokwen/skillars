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
          // skillars-deferred-92 code review, chunk 3: the rule does `opts.allowlist ||
          // DEFAULT_ALLOWLIST` (whole-list replacement, not merge — verified against
          // eslint-plugin-vue's own source). This project's list omitted several of the
          // built-in's own punctuation tokens (=, [, ], {, }, <, >, !, ?, •, ‐, −), so a bare
          // template node made of only one of those became an unexplained build error. '&larr;'
          // was also dead: Vue decodes HTML entities before this rule ever sees the text, so only
          // the literal '←' below does anything.
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
            '−',
            '‐',
            '✕',
            '✓',
            '←',
            '→',
            '·',
            '•',
            '|',
            '/',
            '(',
            ')',
            '[',
            ']',
            '{',
            '}',
            '<',
            '>',
            ':',
            '-',
            '+',
            '*',
            ',',
            '.',
            '=',
            '!',
            '?',
            '&',
            '#',
            '%',
            '@',
            '…',
            '...',
          ],
          // skillars-deferred-92 code review, chunk 3: the rule's own DEFAULT_ATTRIBUTES only
          // covers title/aria-* (any element) plus <input placeholder> and <img alt> — in a
          // Quasar codebase the user-visible text is overwhelmingly in q-* component props
          // (label, hint, placeholder, no-data-label, rows-per-page-label, error-message), which
          // were invisible to this guard. Providing `attributes` replaces the default wholesale
          // (same non-merge behaviour as `allowlist`), so the built-in title/aria-*/input/img
          // entries are repeated here rather than silently dropped.
          attributes: {
            '/.+/': [
              'title',
              'aria-label',
              'aria-placeholder',
              'aria-roledescription',
              'aria-valuetext',
            ],
            input: ['placeholder'],
            img: ['alt'],
            '/^q-/': [
              'label',
              'hint',
              'placeholder',
              'no-data-label',
              'rows-per-page-label',
              'error-message',
            ],
          },
        },
      ],

      // What the rule above still cannot catch (skillars-deferred-92 code review, chunk 3): it is
      // a template-node/attribute linter and structurally cannot see inside <script> — a
      // hardcoded string in a plain JS array (the weekday/address-type bugs this same review
      // found, fixed separately), Notify.create({ message: '...' }), a validation `rules` array,
      // or a router `meta.title` are all invisible to it regardless of the attributes/allowlist
      // configuration above.

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
