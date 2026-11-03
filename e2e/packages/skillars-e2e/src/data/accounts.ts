import type { Credentials, SqlExecutor } from 'pitchside-core'
import { E2E } from '../env.js'
import { postgresExecutor } from './db.js'
import {
  Provisioner,
  type CoachAccount,
  type ParentAccount,
  type PlayerAccount,
} from './provisioner.js'

/**
 * The worker's baseline personas, created lazily on first use and shared by every test in the
 * worker (each Playwright worker is its own process, so module state is per worker). A test that
 * mutates a persona in a way others could observe should provision its own account instead.
 */
export interface Baseline {
  coach: CoachAccount
  /** Verified, profile builder never started: held by the completeness gate. */
  'coach-incomplete': CoachAccount
  /** One child aged 11. */
  parent: ParentAccount
  /** Self-registered adult with a player profile. */
  player: PlayerAccount
}

export type BaselineRole = keyof Baseline
export type SkillarsRole = BaselineRole | 'admin'

const workerIndex = Number(process.env.TEST_WORKER_INDEX ?? 0)

let sqlPromise: Promise<SqlExecutor> | undefined
const sql = (): Promise<SqlExecutor> =>
  (sqlPromise ??= Promise.resolve(postgresExecutor(E2E.databaseUrl)))

let provisionerPromise: Promise<Provisioner> | undefined
export const provisioner = (): Promise<Provisioner> =>
  (provisionerPromise ??= Provisioner.create(workerIndex, sql))

const baseline = new Map<BaselineRole, Promise<Baseline[BaselineRole]>>()

const recipes: { [R in BaselineRole]: (p: Provisioner) => Promise<Baseline[R]> } = {
  coach: (p) => p.coach(),
  'coach-incomplete': (p) => p.incompleteCoach(),
  parent: (p) => p.parent(),
  player: (p) => p.adultPlayer(),
}

export function ensureBaseline<R extends BaselineRole>(role: R): Promise<Baseline[R]> {
  let pending = baseline.get(role) as Promise<Baseline[R]> | undefined
  if (!pending) {
    pending = provisioner().then((p) => recipes[role](p))
    baseline.set(role, pending)
    pending.catch(() => baseline.delete(role))
  }
  return pending
}

const ACCOUNT_ROLE_PREFIX = 'account:'
const adhoc = new Map<string, Credentials>()

/**
 * A role key for an account a test provisioned itself, so `actor(asRole(account))` opens a logged-in
 * context for it with the same priming, gates and session handling as a baseline persona. For
 * two-persona tests whose accounts must not be the shared baseline (e.g. a coach whose slots a booking
 * occupies).
 */
export function asRole(account: { email: string; password: string }): string {
  const role = `${ACCOUNT_ROLE_PREFIX}${account.email}`
  adhoc.set(role, { username: account.email, password: account.password })
  return role
}

/** The kernel's credentials resolver: baseline personas are provisioned on demand. */
export async function credentialsFor(role: string): Promise<Credentials> {
  if (role === 'admin') return { username: E2E.adminEmail, password: E2E.adminPassword }
  const registered = adhoc.get(role)
  if (registered) return registered
  if (role.startsWith(ACCOUNT_ROLE_PREFIX)) {
    throw new Error(`"${role}" was never registered: call asRole(account) in this worker first`)
  }
  if (!(role in recipes)) throw new Error(`Unknown Skillars role "${role}"`)
  const account = await ensureBaseline(role as BaselineRole)
  return { username: account.email, password: account.password }
}

export async function closeAccounts(): Promise<void> {
  await (await provisionerPromise)?.dispose()
  await (await sqlPromise)?.end?.()
  provisionerPromise = undefined
  sqlPromise = undefined
  baseline.clear()
  adhoc.clear()
}
