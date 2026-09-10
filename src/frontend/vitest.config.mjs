import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { quasar, transformAssetUrls } from '@quasar/vite-plugin'
import tsconfigPaths from 'vite-tsconfig-paths'

// skillars-deferred-104: Vitest config for the frontend unit suite.
//
// This is the scaffold shipped by @quasar/quasar-app-extension-testing-unit-vitest
// (the "official Quasar Vitest AE", decision D2), placed by hand rather than through
// `quasar ext add`. The AE's index runner (`api.compatibleWith('@quasar/app-vite',
// '^1.6.0 || >=2.0.0 <2.4.0')`) rejects this repo's @quasar/app-vite 2.4.1 on every
// `quasar dev` / `quasar build`, so registering the extension in quasar.config.js would
// break `mvn verify`'s `npx quasar build` step — the exact outcome skillars-deferred-104
// forbids. Used purely as a library instead: this config + `installQuasarPlugin` from the
// same package in test/vitest/setup-file.js. quasar.config.js is left untouched.
//
// Alias sync with the app build: `vite-tsconfig-paths` reads .quasar/tsconfig.json, which
// `quasar prepare` regenerates from quasar.config.js on every `npm install` — so the
// `src/`, `components/`, `stores/`, `boot/` … aliases stay in lockstep with the app
// automatically; they are never hand-maintained here.
//
// NOT wired into `mvn verify`: the Maven `frontend-maven-plugin` `npm test` execution
// calls the `test` npm script, which is a permanent no-op stub. Vitest is reachable only
// via `npm run test:unit` locally and the opt-in `.github/workflows/frontend-unit-tests.yml`
// job (manual dispatch or the `frontend-tests` PR label).
export default defineConfig({
  test: {
    environment: 'happy-dom',
    // This project imports `describe` / `it` / `expect` / `vi` explicitly (see the existing
    // SkillsRadarChartSpec.js and eslint.config.js, which registers no test-runner globals).
    globals: false,
    setupFiles: ['./test/vitest/setup-file.js'],
    include: [
      // Sibling `*.spec.js` / `*.test.js` layout (future specs) …
      'src/**/*.{spec,test}.{js,mjs}',
      // … and the pre-existing `__tests__/…Spec.js` layout (SkillsRadarChartSpec.js,
      // sessionManagerSpec.js).
      'src/**/__tests__/**/*.{js,mjs}',
    ],
  },
  plugins: [
    vue({
      template: { transformAssetUrls },
    }),
    quasar({
      sassVariables: 'src/css/quasar.variables.scss',
    }),
    tsconfigPaths(),
  ],
})
