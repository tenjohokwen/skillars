// Test-only helper shared by src/i18n/__tests__/localeKeyParitySpec.js and
// i18nTemplateKeyResolutionSpec.js. skillars-deferred-156 AC6 extracted it so both specs use the
// identical recursive-leaf-key-path logic; skillars-deferred-157 AC8 moved it here from
// src/i18n/ so test tooling no longer ships in production app source. It is not collected as a
// spec: vitest.config.mjs's `include` globs only match under src/. It is still linted and
// Prettier-checked by CI (`npx eslint .`, the frontend-quality action's Prettier glob).

/** Recursively collects every leaf key path, e.g. "booking.requests.playerRequestLabel". */
export function collectKeyPaths(obj, prefix = '') {
  const paths = []
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      paths.push(...collectKeyPaths(value, path))
    } else {
      paths.push(path)
    }
  }
  return paths
}
