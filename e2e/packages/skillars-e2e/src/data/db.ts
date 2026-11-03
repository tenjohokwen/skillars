import pg from 'pg'
import type { SqlExecutor } from 'pitchside-core'

/**
 * The kernel's SQL port over node-postgres. Used ONLY for state no HTTP path can create without a
 * real third party (Stripe Connect onboarding, a card on file, a Stripe Identity verdict). Anything
 * with service-layer side effects is seeded through the API instead.
 */
export function postgresExecutor(connectionString: string): SqlExecutor {
  const pool = new pg.Pool({ connectionString, max: 2 })
  return {
    async query<Row = Record<string, unknown>>(sql: string, params: readonly unknown[] = []) {
      const result = await pool.query(sql, [...params])
      return result.rows as Row[]
    },
    async end() {
      await pool.end()
    },
  }
}
