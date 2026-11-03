import js from '@eslint/js'
import tseslint from 'typescript-eslint'

// Flake policy, enforced: never sleep — wait for a locator, a response or a URL instead.
const noSleeping = {
  selector: "CallExpression[callee.property.name='waitForTimeout']",
  message: 'waitForTimeout is banned (flake policy): wait for a locator, a response or a URL.',
}

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/test-results/**',
      '**/playwright-report/**',
      '**/blob-report/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
    rules: {
      // A missing `await` on a Playwright call is the single most common cause of UI flake.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      'no-restricted-syntax': ['error', noSleeping],
      // Fakes legitimately implement async interfaces without awaiting anything.
      '@typescript-eslint/require-await': 'off',
      // Playwright fixtures must destructure their first argument, even when it is empty.
      'no-empty-pattern': 'off',
    },
  },
  {
    // The portability boundary: the kernel may never import an app package. CI also builds and
    // tests the kernel alone with every app package deleted, which catches what a lint rule can't.
    files: ['packages/pitchside-core/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['*-e2e', '*-e2e/*', '**/packages/*-e2e/**', '../*-e2e/**', '../../*-e2e/**'],
              message:
                'pitchside-core must stay app-agnostic: move app-specific code to the app package.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.js', '**/*.mjs'],
    extends: [tseslint.configs.disableTypeChecked],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
        URL: 'readonly',
        setTimeout: 'readonly',
      },
    },
  },
  {
    files: ['packages/pitchside-core/test/browser/app/**'],
    languageOptions: {
      globals: {
        document: 'readonly',
        location: 'readonly',
        fetch: 'readonly',
        addEventListener: 'readonly',
      },
    },
  },
)
