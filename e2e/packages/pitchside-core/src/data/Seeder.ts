import type { ApiClient } from '../http/ApiClient.js'
import type { Clock } from '../time/Clock.js'
import type { IdAllocator } from './IdAllocator.js'
import type { UniqueNames } from './UniqueNames.js'

/**
 * A minimal SQL port. The kernel ships no database driver; the app wraps whichever one it uses
 * (e.g. `pg`) in these two methods.
 */
export interface SqlExecutor {
  query<Row = Record<string, unknown>>(sql: string, params?: readonly unknown[]): Promise<Row[]>
  end?(): Promise<void>
}

/** Everything a seed spec may use. Ports are optional so a project can seed through the API only. */
export interface SeedContext {
  readonly ids: IdAllocator
  readonly names: UniqueNames
  readonly clock: Clock
  readonly sql?: SqlExecutor
  readonly api?: ApiClient
}

/**
 * A seed spec carries its own recipe. Use SQL for rows with a stable schema and no side effects,
 * and the app's API for anything with service-layer side effects (outbox rows, ledger entries):
 * hand-written INSERTs for a payment ledger are how fixtures drift from reality.
 */
export interface SeedSpec<T> {
  /** Short label for errors and reports, e.g. "coach" or "pack-purchase". */
  readonly kind: string
  seed(context: SeedContext): Promise<T>
}

export type ResetScope = 'worker' | 'suite'

export interface Seeder {
  seed<T>(spec: SeedSpec<T>): Promise<T>
  /**
   * `suite`: return the database to its empty baseline — global setup, or a dedicated destructive
   * worker only, never while other workers are running. `worker`: clean up this worker's own data.
   */
  reset(scope: ResetScope): Promise<void>
  /** Tables that must survive a reset — reference data, migration history. */
  readonly protectedTables: readonly string[]
}

export type Resetter = (scope: ResetScope, context: SeedContext) => Promise<void>

export interface ContextSeederOptions {
  context: SeedContext
  resetters?: readonly Resetter[]
  protectedTables?: readonly string[]
}

export class SeedError extends Error {
  constructor(
    readonly kind: string,
    cause: unknown,
  ) {
    super(`Seeding "${kind}" failed: ${cause instanceof Error ? cause.message : String(cause)}`, {
      cause,
    })
    this.name = 'SeedError'
  }
}

/** Runs seed specs against a shared context and delegates resets to pluggable strategies. */
export class ContextSeeder implements Seeder {
  readonly protectedTables: readonly string[]

  constructor(private readonly options: ContextSeederOptions) {
    this.protectedTables = options.protectedTables ?? []
  }

  async seed<T>(spec: SeedSpec<T>): Promise<T> {
    try {
      return await spec.seed(this.options.context)
    } catch (error) {
      throw new SeedError(spec.kind, error)
    }
  }

  async reset(scope: ResetScope): Promise<void> {
    for (const resetter of this.options.resetters ?? []) {
      await resetter(scope, this.options.context)
    }
  }
}

export interface SqlTruncateOptions {
  /** Schemas whose tables are truncated on a suite reset. */
  schemas: readonly string[]
  /** `table` (any schema) or `schema.table`; a trailing `*` matches a prefix. */
  protectedTables: readonly string[]
  /** Lists `schema.table` names. Defaults to a PostgreSQL information_schema query. */
  listTables?: (sql: SqlExecutor, schemas: readonly string[]) => Promise<string[]>
  /** Builds the truncate statement. Defaults to PostgreSQL `TRUNCATE … RESTART IDENTITY CASCADE`. */
  truncateStatement?: (qualifiedTables: readonly string[]) => string
}

export function isProtected(qualified: string, protectedTables: readonly string[]): boolean {
  const table = qualified.includes('.') ? qualified.slice(qualified.indexOf('.') + 1) : qualified
  return protectedTables.some((pattern) => {
    const target = pattern.includes('.') ? qualified : table
    return pattern.endsWith('*') ? target.startsWith(pattern.slice(0, -1)) : target === pattern
  })
}

const quoteIdent = (identifier: string): string => `"${identifier.replace(/"/g, '""')}"`

const postgresListTables = async (
  sql: SqlExecutor,
  schemas: readonly string[],
): Promise<string[]> => {
  const rows = await sql.query<{ table_schema: string; table_name: string }>(
    `SELECT table_schema, table_name FROM information_schema.tables
      WHERE table_type = 'BASE TABLE' AND table_schema = ANY($1)
      ORDER BY table_schema, table_name`,
    [schemas],
  )
  return rows.map((row) => `${row.table_schema}.${row.table_name}`)
}

const postgresTruncate = (tables: readonly string[]): string =>
  `TRUNCATE TABLE ${tables
    .map((qualified) => qualified.split('.').map(quoteIdent).join('.'))
    .join(', ')} RESTART IDENTITY CASCADE`

/**
 * A suite-scope resetter that truncates every table in the given schemas except the protected
 * ones, in one statement. Worker scope is left to the app (only it knows which rows a worker owns).
 */
export function sqlTruncateResetter(options: SqlTruncateOptions): Resetter {
  return async (scope, context) => {
    if (scope !== 'suite') return
    if (!context.sql) throw new Error('sqlTruncateResetter needs a SqlExecutor in the seed context')
    const list = options.listTables ?? postgresListTables
    const tables = (await list(context.sql, options.schemas)).filter(
      (table) => !isProtected(table, options.protectedTables),
    )
    if (tables.length === 0) return
    await context.sql.query((options.truncateStatement ?? postgresTruncate)(tables))
  }
}
