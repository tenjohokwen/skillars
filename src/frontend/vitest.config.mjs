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
// `#q-app/wrappers` is the one alias that does NOT stay in lockstep automatically and needs a
// hand-maintained entry below (skillars-deferred-126 AC4, discovered empirically while writing the
// first boot-file spec this suite has ever had — no prior spec loaded a real boot file, every one
// mocked it away, so this gap was never exercised before). `.quasar/tsconfig.json`'s own `paths`
// entry for `#q-app/wrappers` points at `@quasar/app-vite/types/app-wrappers.d.ts` — a pure TS type
// declaration with no runtime output. `vite-tsconfig-paths` happily resolves the import SPECIFIER to
// that file (no "cannot find module" error), but the compiled module then has no real `defineBoot`
// export, so any boot file loaded for real under Vitest throws "defineBoot is not a function" at
// import time. In the real app this only works because `@quasar/app-vite`'s own CLI injects a
// runtime Vite alias for `#q-app/*` when it builds `quasar.config.js` — machinery this project's
// vitest.config.mjs deliberately does not include (see the note above on why the Vitest AE itself is
// unregistered). The explicit alias below points at the SAME real runtime module the public `
// @quasar/app-vite/wrappers` subpath export already resolves to (`defineBoot = callback => callback`
// — a plain identity wrapper, confirmed by reading its source), so this is not a stand-in for
// different behavior, only a resolution-path fix.
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
  resolve: {
    alias: [
      // See the "#q-app/wrappers" note above — must come before `tsconfigPaths()` runs (plugin order
      // in the `plugins` array below is irrelevant to this; `resolve.alias` is checked ahead of any
      // plugin's own `resolveId`), so this wins over the `.d.ts`-pointing tsconfig `paths` entry.
      { find: '#q-app/wrappers', replacement: '@quasar/app-vite/wrappers' },
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
