// skillars-deferred-156 AC6: extracted from localeKeyParitySpec.js (skillars-deferred-155 AC14),
// which is not itself a module meant to be imported by another spec, so i18nTemplateKeyResolutionSpec.js
// can reuse the identical recursive-leaf-key-path logic rather than re-deriving it. Lives outside
// __tests__/ deliberately: vitest.config.mjs's `include` glob (`src/**/__tests__/**/*.{js,mjs}`)
// treats every .js file under __tests__/ as a spec file and fails the run if it finds no
// describe/it blocks in it.

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
