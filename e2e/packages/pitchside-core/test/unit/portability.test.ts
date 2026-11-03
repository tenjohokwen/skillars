import { readdir, readFile } from 'node:fs/promises'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * The portability contract, checked mechanically: the kernel may not import an app package, nor
 * name one. Terms below are the ones an app adapter is most likely to leak into shared code. The
 * ESLint `no-restricted-imports` rule guards imports at edit time; this test guards the words too,
 * and runs even when the kernel is built on its own with every app package deleted.
 */
const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src')
const FORBIDDEN = /skillars|softropic|\bskp\b|fcookie|broprint|quasar/i
const APP_IMPORT = /from\s+['"](?:[^'"]*\/)?[a-z-]*-e2e(?:\/[^'"]*)?['"]/

async function sourceFiles(dir: string): Promise<string[]> {
  const entries = await readdir(dir, { withFileTypes: true })
  const nested = await Promise.all(
    entries.map((entry) =>
      entry.isDirectory()
        ? sourceFiles(join(dir, entry.name))
        : Promise.resolve([join(dir, entry.name)]),
    ),
  )
  return nested.flat().filter((file) => file.endsWith('.ts'))
}

describe('portability', () => {
  it('names no application anywhere in the kernel source', async () => {
    const offenders: string[] = []
    for (const file of await sourceFiles(SRC)) {
      const lines = (await readFile(file, 'utf8')).split('\n')
      lines.forEach((line, index) => {
        if (FORBIDDEN.test(line) || APP_IMPORT.test(line))
          offenders.push(`${relative(SRC, file)}:${index + 1}: ${line.trim()}`)
      })
    }
    expect(offenders).toEqual([])
  })

  it('imports nothing but node built-ins, Playwright, axe and its own modules', async () => {
    const allowed = /^(node:|@playwright\/test$|@axe-core\/playwright$|\.{1,2}\/)/
    const offenders: string[] = []
    for (const file of await sourceFiles(SRC)) {
      for (const match of (await readFile(file, 'utf8')).matchAll(/from\s+['"]([^'"]+)['"]/g)) {
        if (!allowed.test(match[1] ?? '')) offenders.push(`${relative(SRC, file)}: ${match[1]}`)
      }
    }
    expect(offenders).toEqual([])
  })
})
