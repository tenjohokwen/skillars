import { createHash, randomBytes, randomInt } from 'node:crypto'
import { request, type APIRequestContext } from '@playwright/test'
import {
  ApiClient,
  MailpitSink,
  systemClock,
  UniqueNames,
  type Clock,
  type SqlExecutor,
} from 'pitchside-core'
import { API_BASE_URL, E2E } from '../env.js'

export type AccountKind = 'coach' | 'parent' | 'player'

export interface Account {
  kind: AccountKind
  email: string
  password: string
  phone: string
  firstName: string
  /** User id as a decimal string — Tsid-sized, never a JS number. */
  userId: string
}

export interface CoachAccount extends Account {
  kind: 'coach'
  /** marketplace.coach_profiles.id (uuid) — the id in /coaches/:coachId. */
  coachId?: string
}

export interface ParentAccount extends Account {
  kind: 'parent'
  children: { playerId: string; name: string; dateOfBirth: string }[]
}

export interface PlayerAccount extends Account {
  kind: 'player'
  playerId?: string
  dateOfBirth: string
}

export interface CoachOnboarding {
  displayName?: string
  city?: string
  timezone?: string
  perSessionPrice?: number
  pack?: { sessionCount: number; totalPrice: number; label: string } | null
  /** Seed a COMPLETE Stripe Connect account so the coach is payment-ready (bookable). */
  paymentReady?: boolean
}

/**
 * The `fcookie` device fingerprint for one account. The backend requires it on login (it becomes the
 * session's client id) and records it as a BROWSER identity signal at login and at booking time;
 * RelatedPartyService flags a booking whose payer and coach share a signal. So every persona gets its
 * own stable value, the same for the provisioning login and for every browser context replaying the
 * session — one shared value would make every coach–payer pair look related.
 */
export function fingerprintFor(email: string): string {
  return `e2e-${createHash('sha256').update(email.toLowerCase()).digest('hex').slice(0, 24)}`
}

/** A fresh fingerprint for an anonymous browser context (no login, so no recorded signal). */
export function guestFingerprint(): string {
  return `e2e-guest-${randomBytes(8).toString('hex')}`
}

const VERIFY_TOKEN = /token=([0-9a-f-]{36})/

/** Phones the stack itself seeds (docker-compose.e2e.yml); generated numbers must never take one. */
const RESERVED_PHONES = new Set([process.env.E2E_ADMIN_PHONE ?? '+15550000001'])

/**
 * Creates Skillars accounts the way a user would — register, follow the emailed link, log in, fill
 * the builders — over HTTP only, plus three narrow SQL writes for state that needs a real Stripe
 * (Connect onboarding, card on file, identity verdict). One instance per Playwright worker.
 */
export class Provisioner {
  private phoneCounter = 0
  /**
   * Six random digits drawn once per worker process — not derived from the run id, which workers
   * share in CI and which a reused local stack or a persistent environment sees again run after run.
   */
  private readonly phoneNonce = String(randomInt(0, 1_000_000)).padStart(6, '0')

  constructor(
    private readonly anonymous: APIRequestContext,
    private readonly mail: MailpitSink,
    private readonly names: UniqueNames,
    private readonly sql: () => Promise<SqlExecutor>,
    private readonly clock: Clock = systemClock,
  ) {}

  static async create(workerIndex: number, sql: () => Promise<SqlExecutor>): Promise<Provisioner> {
    const anonymous = await request.newContext()
    return new Provisioner(
      anonymous,
      new MailpitSink({ baseUrl: E2E.mailpitUrl }),
      new UniqueNames(workerIndex, {
        emailDomain: E2E.emailDomain,
        runId: E2E.runId ?? randomBytes(3).toString('hex'),
      }),
      sql,
    )
  }

  async dispose(): Promise<void> {
    await this.anonymous.dispose()
  }

  /** Register and verify by email. The phone-OTP step is off by default (seeded config). */
  async registerAndVerify(
    kind: AccountKind,
    extra: Record<string, unknown> = {},
  ): Promise<Account> {
    const firstName = `${kind.charAt(0).toUpperCase()}${kind.slice(1)}`
    const [, lastName = 'Tester'] = this.names.personName('x').split(' ')
    const account = {
      kind,
      email: this.names.email(`p${kind}`),
      password: E2E.accountPassword,
      phone: this.nextPhone(),
      firstName,
    }
    const since = new Date(Date.now() - 2_000)
    await this.api(this.anonymous).post(`/security/${kind}/register`, {
      data: { ...account, lastName, langKey: 'en', ...extra },
      expectStatus: 200,
    })
    const message = await this.mail.waitForMessage({
      to: account.email,
      subjectMatches: /verify/i,
      since,
      timeoutMs: 30_000,
    })
    const token = message.code(VERIFY_TOKEN)
    await this.api(this.anonymous).get(`/security/${kind}/verify-email`, {
      params: { token },
      expectStatus: 200,
    })
    const session = await this.login(account)
    try {
      return { ...account, userId: session.userId }
    } finally {
      await session.dispose()
    }
  }

  /** An API session for an account (its own cookie jar), for provisioning calls. */
  async login(account: Pick<Account, 'email' | 'password'>): Promise<{
    api: ApiClient
    userId: string
    role: string
    dispose: () => Promise<void>
  }> {
    const host = new URL(E2E.baseUrl)
    const context = await request.newContext({
      storageState: {
        cookies: [
          {
            name: 'fcookie',
            value: fingerprintFor(account.email),
            domain: host.hostname,
            path: '/',
            expires: -1,
            httpOnly: false,
            secure: false,
            sameSite: 'Lax',
          },
        ],
        origins: [],
      },
    })
    const api = this.api(context)
    const body = await api.json<{ userId: string | number; role: string }>('POST', '/auth/login', {
      data: { email: account.email, password: account.password },
    })
    return {
      api,
      userId: String(body.userId),
      role: body.role,
      dispose: () => context.dispose(),
    }
  }

  async coach(options: CoachOnboarding = {}): Promise<CoachAccount> {
    const account = (await this.registerAndVerify('coach')) as CoachAccount
    return this.onboardCoach(account, options)
  }

  /** A verified coach who never started the profile builder — the completeness gate holds them. */
  async incompleteCoach(): Promise<CoachAccount> {
    return (await this.registerAndVerify('coach')) as CoachAccount
  }

  async onboardCoach(account: CoachAccount, options: CoachOnboarding = {}): Promise<CoachAccount> {
    const timezone = options.timezone ?? 'Europe/Berlin'
    const pack =
      options.pack === undefined
        ? { sessionCount: 5, totalPrice: 250, label: 'Starter 5' }
        : options.pack
    const session = await this.login(account)
    try {
      const base = '/marketplace/coaches/me/profile'
      const put = (step: number, data: unknown) =>
        session.api.put(`${base}/steps/${step}`, { data, expectStatus: (s) => s >= 200 && s < 300 })
      await put(1, {
        displayName: options.displayName ?? `${account.firstName} Coaching`,
        bio: 'Technical coaching for young players.',
        city: options.city ?? 'Testville',
        district: 'Centre',
        languages: ['English'],
        canonicalTimezone: timezone,
      })
      await put(2, {
        specialties: ['Dribbling', 'Finishing'],
        ageGroups: ['AGE_10_12', 'AGE_13_17', 'ADULT'],
      })
      await put(3, {
        perSessionPrice: options.perSessionPrice ?? 55,
        sessionDurationMinutes: 60,
        sessionPacks: pack ? [pack] : [],
      })
      await put(4, {
        windows: [1, 2, 3, 4, 5].map((dayOfWeek) => ({
          dayOfWeek,
          startTime: '09:00',
          endTime: '17:00',
          canonicalTimezone: timezone,
        })),
      })
      await put(5, { photoUrl: null })
      const published = await session.api.json<{ coachId: string; profileComplete: boolean }>(
        'POST',
        `${base}/publish`,
      )
      if (!published.profileComplete) throw new Error(`Coach ${account.email} did not publish`)
      if (pack) {
        await session.api.post('/payment/coaches/me/session-pack-tiers', {
          data: pack,
          expectStatus: 201,
        })
      }
      const coach: CoachAccount = { ...account, coachId: published.coachId }
      if (options.paymentReady ?? true) await this.markCoachPaymentReady(coach)
      return coach
    } finally {
      await session.dispose()
    }
  }

  /**
   * SQL seam: Stripe Connect onboarding has no HTTP path without real Stripe, and booking requires
   * a COMPLETE, charges-enabled Connect account (BookingService.requireCoachPaymentReady).
   */
  async markCoachPaymentReady(coach: CoachAccount): Promise<void> {
    if (!coach.coachId) throw new Error('markCoachPaymentReady needs a published coach')
    const sql = await this.sql()
    await sql.query(
      `INSERT INTO payment.coach_stripe_accounts
         (coach_id, stripe_account_id, onboarding_status, charges_enabled, payouts_enabled)
       VALUES ($1::uuid, $2, 'COMPLETE', true, true)
       ON CONFLICT (coach_id) DO UPDATE
         SET onboarding_status = 'COMPLETE', charges_enabled = true, payouts_enabled = true`,
      [coach.coachId, `acct_e2e_${this.names.token('acct').replace(/-/g, '_')}`],
    )
  }

  async parent(options: { childAges?: number[] } = {}): Promise<ParentAccount> {
    const account = {
      ...(await this.registerAndVerify('parent')),
      kind: 'parent' as const,
      children: [] as ParentAccount['children'],
    }
    const ages = options.childAges ?? [11]
    if (ages.length === 0) return account
    const session = await this.login(account)
    try {
      for (const age of ages) {
        const dateOfBirth = this.clock.dateOfBirthForAge(age).toISODate()
        const name = this.names.personName('Kid')
        const minor = age < 18
        const child = await session.api.json<{ id: string | number }>('POST', '/security/players', {
          data: {
            name,
            dateOfBirth,
            position: 'MIDFIELDER',
            ...(minor ? { parentConsent: true, consentPolicyVersion: '1.0' } : {}),
          },
        })
        account.children.push({ playerId: String(child.id), name, dateOfBirth })
      }
      return account
    } finally {
      await session.dispose()
    }
  }

  /** A self-registered adult player with a player profile. */
  async adultPlayer(age = 25): Promise<PlayerAccount> {
    const dateOfBirth = this.clock.dateOfBirthForAge(age).toISODate()
    const account = await this.registerAndVerify('player', { dateOfBirth })
    const session = await this.login(account)
    try {
      const profile = await session.api.json<{ id: string | number }>(
        'POST',
        '/security/players/me',
        {
          data: { position: 'MIDFIELDER' },
        },
      )
      return { ...account, kind: 'player', dateOfBirth, playerId: String(profile.id) }
    } finally {
      await session.dispose()
    }
  }

  /**
   * `+1` + 6 random digits + 3 worker digits + 4 counter digits = 15 characters (`main.user.phone`
   * is UNIQUE). The fixed width keeps every generated number distinct from the shorter fixed numbers
   * the stack seeds — the bootstrap admin is `+15550000001` (12 characters) — and the 3-digit worker
   * field does not wrap until Playwright's worker index (new on every restart) passes 999. Within
   * the regexes of all three registration requests (at most 20 characters).
   */
  private nextPhone(): string {
    this.phoneCounter += 1
    if (this.phoneCounter > 9999)
      throw new Error('Provisioner phone counter exhausted (9999 accounts)')
    if (this.names.workerIndex > 999)
      throw new Error('Provisioner supports worker indexes up to 999')
    const worker = String(this.names.workerIndex).padStart(3, '0')
    const phone = `+1${this.phoneNonce}${worker}${String(this.phoneCounter).padStart(4, '0')}`
    if (RESERVED_PHONES.has(phone)) throw new Error(`Generated phone ${phone} is reserved`)
    return phone
  }

  private api(context: APIRequestContext): ApiClient {
    return new ApiClient(context, { baseUrl: API_BASE_URL, requestIdPrefix: 'e2e-provision' })
  }
}
