import { describe, expect, it, vi } from 'vitest'
import {
  ContextSeeder,
  IdAllocator,
  isProtected,
  SeedError,
  sqlTruncateResetter,
  systemClock,
  UniqueNames,
  type SeedContext,
  type SqlExecutor,
} from '../../src/index.js'

describe('IdAllocator', () => {
  it('gives each worker its own disjoint range', () => {
    const w0 = new IdAllocator(0, { base: 9_650_000_000n })
    const w3 = new IdAllocator(3, { base: 9_650_000_000n })
    expect(w0.next()).toBe(9_650_000_000n)
    expect(w0.next()).toBe(9_650_000_001n)
    expect(w3.next()).toBe(9_653_000_000n)
    expect(w3.range()).toEqual({ from: 9_653_000_000n, to: 9_653_999_999n })
    expect(w3.owns(9_653_000_000n)).toBe(true)
    expect(w0.owns(9_653_000_000n)).toBe(false)
    expect(w0.nextString()).toBe('9650000002')
  })

  it('refuses to run past its stride', () => {
    const ids = new IdAllocator(0, { base: 0n, stride: 2n })
    ids.next()
    ids.next()
    expect(() => ids.next()).toThrow(/exhausted/)
  })

  it('validates its inputs', () => {
    expect(() => new IdAllocator(-1, { base: 0n })).toThrow()
    expect(() => new IdAllocator(0, { base: 0n, stride: 0n })).toThrow()
  })
})

describe('UniqueNames', () => {
  it('produces unique, run- and worker-scoped emails', () => {
    const names = new UniqueNames(2, { emailDomain: 'example.test', runId: 'Run 42!' })
    expect(names.runId).toBe('run42')
    expect(names.email('Coach')).toBe('coach-rrun42-w2-1@example.test')
    expect(names.email('coach')).toBe('coach-rrun42-w2-2@example.test')
    expect(names.token('')).toBe('x-rrun42-w2-3')
  })

  it('makes letters-only person names', () => {
    const names = new UniqueNames(1, { emailDomain: 'example.test', runId: 'a' })
    expect(names.personName('Alex')).toMatch(/^Alex [A-Z][a-z]+$/)
    expect(names.personName('Alex')).not.toBe(names.personName('Alex'))
  })

  it('rejects a non-domain', () => {
    expect(() => new UniqueNames(0, { emailDomain: 'localhost' })).toThrow(/not a domain/)
  })
})

const context = (sql?: SqlExecutor): SeedContext => ({
  ids: new IdAllocator(0, { base: 1000n }),
  names: new UniqueNames(0, { emailDomain: 'example.test', runId: 't' }),
  clock: systemClock,
  sql,
})

describe('ContextSeeder', () => {
  it('runs a spec against the shared context', async () => {
    const seeder = new ContextSeeder({ context: context() })
    const coach = await seeder.seed({
      kind: 'coach',
      seed: async ({ ids, names }) => ({ id: ids.next(), email: names.email('coach') }),
    })
    expect(coach).toEqual({ id: 1000n, email: 'coach-rt-w0-1@example.test' })
  })

  it('names the failing spec', async () => {
    const seeder = new ContextSeeder({ context: context() })
    const failing = seeder.seed({
      kind: 'pack',
      seed: async () => {
        throw new Error('constraint violated')
      },
    })
    await expect(failing).rejects.toBeInstanceOf(SeedError)
    await expect(failing).rejects.toThrow('Seeding "pack" failed: constraint violated')
  })

  it('delegates resets to every resetter in order', async () => {
    const order: string[] = []
    const seeder = new ContextSeeder({
      context: context(),
      resetters: [
        async (scope) => void order.push(`a:${scope}`),
        async (scope) => void order.push(`b:${scope}`),
      ],
      protectedTables: ['flyway_schema_history'],
    })
    await seeder.reset('worker')
    expect(order).toEqual(['a:worker', 'b:worker'])
    expect(seeder.protectedTables).toEqual(['flyway_schema_history'])
  })
})

describe('sqlTruncateResetter', () => {
  it('matches protected tables by name, schema-qualified name or prefix', () => {
    const rules = ['flyway_schema_history', 'main.platform_config', 'ref_*']
    expect(isProtected('main.flyway_schema_history', rules)).toBe(true)
    expect(isProtected('main.platform_config', rules)).toBe(true)
    expect(isProtected('audit.platform_config', rules)).toBe(false)
    expect(isProtected('main.ref_countries', rules)).toBe(true)
    expect(isProtected('main.bookings', rules)).toBe(false)
  })

  it('truncates every unprotected table in one quoted statement on a suite reset only', async () => {
    const query = vi.fn(async (sql: string) => {
      if (sql.includes('information_schema')) {
        return [
          { table_schema: 'main', table_name: 'bookings' },
          { table_schema: 'main', table_name: 'flyway_schema_history' },
          { table_schema: 'main', table_name: 'odd"name' },
        ]
      }
      return []
    }) as unknown as SqlExecutor['query']
    const reset = sqlTruncateResetter({
      schemas: ['main'],
      protectedTables: ['flyway_schema_history'],
    })

    await reset('worker', context({ query }))
    expect(query).not.toHaveBeenCalled()

    await reset('suite', context({ query }))
    expect(query).toHaveBeenLastCalledWith(
      'TRUNCATE TABLE "main"."bookings", "main"."odd""name" RESTART IDENTITY CASCADE',
    )
  })

  it('needs a SQL port', async () => {
    const reset = sqlTruncateResetter({ schemas: ['main'], protectedTables: [] })
    await expect(reset('suite', context())).rejects.toThrow(/SqlExecutor/)
  })
})
