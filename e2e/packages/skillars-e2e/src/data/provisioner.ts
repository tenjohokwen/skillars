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
  /**
   * Stop after this builder step, leaving the profile an unpublished DRAFT (no Connect account;
   * a pack tier exists from step 3 on): the half-finished coach that search must never return.
   */
  stopAfterStep?: 1 | 2 | 3 | 4
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

export const VERIFY_TOKEN = /token=([0-9a-f-]{36})/

/** Phones the stack itself seeds (docker-compose.e2e.yml); generated numbers must never take one. */
const RESERVED_PHONES = new Set([process.env.E2E_ADMIN_PHONE ?? '+15550000001'])

/**
 * Creates Skillars accounts the way a user would — register, follow the emailed link, log in, fill
 * the builders — over HTTP only, plus three narrow SQL writes for state that needs a real Stripe
 * (Connect onboarding, card on file, identity verdict). One instance per Playwright worker.
 */
/** The price id keys a subscribe or tier change reads (ConfigStartupAssertion lists the same seven). */
const SUBSCRIPTION_PRICE_KEYS = [
  'subscription.coach.instructor.monthly.priceId',
  'subscription.coach.academy.monthly.priceId',
  'subscription.player.athlete.monthly.priceId',
  'subscription.player.athlete.quarterly.priceId',
  'subscription.player.athlete.yearly.priceId',
  'subscription.player.semi_pro.yearly.priceId',
  'subscription.player.pro.yearly.priceId',
]

export class Provisioner {
  private phoneCounter = 0
  private pricesReady?: Promise<void>
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

  /**
   * A place name no other run or worker has used, letters only (digits read as phone numbers to the
   * contact-detail filter). For searches that must see exactly the coaches a test seeded.
   */
  uniqueCity(): string {
    const letters = this.names
      .token('c')
      .replace(/[^a-z0-9]/g, '')
      .replace(/[0-9]/g, (d) => String.fromCharCode(97 + Number(d)))
    return `Town${letters}`
  }

  /**
   * Unique, valid registration details for a spec that registers through the UI itself: a fresh
   * email, a phone from the same per-worker sequence the API path uses, and the stack's password.
   */
  identity(kind: AccountKind): {
    email: string
    phone: string
    password: string
    firstName: string
    lastName: string
  } {
    const [, lastName = 'Tester'] = this.names.personName('x').split(' ')
    return {
      email: this.names.email(`u${kind}`),
      phone: this.nextPhone(),
      password: E2E.accountPassword,
      firstName: `${kind.charAt(0).toUpperCase()}${kind.slice(1)}`,
      lastName,
    }
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
      if (options.stopAfterStep === 1) return account
      await put(2, {
        specialties: ['Dribbling', 'Finishing'],
        ageGroups: ['AGE_10_12', 'AGE_13_17', 'ADULT'],
      })
      if (options.stopAfterStep === 2) return account
      // The step-3 pack becomes the coach's purchasable tier (skillars-deferred-168), so no tier POST follows.
      await put(3, {
        perSessionPrice: options.perSessionPrice ?? 55,
        sessionDurationMinutes: 60,
        sessionPacks: pack ? [pack] : [],
      })
      if (options.stopAfterStep === 3) return account
      await put(4, {
        windows: [1, 2, 3, 4, 5].map((dayOfWeek) => ({
          dayOfWeek,
          startTime: '09:00',
          endTime: '17:00',
          canonicalTimezone: timezone,
        })),
      })
      if (options.stopAfterStep === 4) return account
      await put(5, { photoUrl: null })
      const published = await session.api.json<{ coachId: string; profileComplete: boolean }>(
        'POST',
        `${base}/publish`,
      )
      if (!published.profileComplete) throw new Error(`Coach ${account.email} did not publish`)
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

  /**
   * Put a card on file through the app's own endpoint, the way the card dialog does after Stripe.js
   * confirms a SetupIntent. The payment method id is unique per call; the stack's WireMock answers
   * every `pm_e2e_*` lookup as a Visa ending 4242 (stack/wiremock/mappings/stripe-*.json).
   */
  async saveCard(
    account: Pick<Account, 'email' | 'password'>,
    options: { declines?: boolean } = {},
  ): Promise<string> {
    // pm_e2e_declined_* cards are refused at charge time (stripe-payment-intents-declined.json).
    const prefix = options.declines ? 'pm_e2e_declined_' : 'pm_e2e_'
    const paymentMethodId = `${prefix}${this.names.token('pm').replace(/-/g, '_')}`
    const session = await this.login(account)
    try {
      await session.api.post('/payment/save-payment-method', {
        data: { paymentMethodId },
        expectStatus: 204,
      })
      return paymentMethodId
    } finally {
      await session.dispose()
    }
  }

  /**
   * Buy a coach's active pack tier for a parent's child through the purchase endpoint (card on file
   * required, see saveCard; the stack's WireMock approves the charge). Returns the sessions bought.
   */
  async buyPack(
    parent: ParentAccount,
    coach: CoachAccount,
    playerId: string = parent.children[0]!.playerId,
  ): Promise<number> {
    if (!coach.coachId) throw new Error('buyPack needs a published coach')
    const session = await this.login(parent)
    try {
      const tier = await session.api.json<{ packTierId: string; sessionCount: number }>(
        'GET',
        `/payment/coaches/${coach.coachId}/session-pack-tiers`,
      )
      await session.api.post('/payment/session-packs/purchase', {
        // As a string: player ids exceed Number.MAX_SAFE_INTEGER, and Jackson reads a string into a Long.
        data: { packTierId: tier.packTierId, playerId },
        headers: { 'Idempotency-Key': `e2e-${this.names.token('buy')}` },
        expectStatus: (status) => status >= 200 && status < 300,
      })
      return tier.sessionCount
    } finally {
      await session.dispose()
    }
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
   * A limited administrator (`ROLE_LTD_ADMIN` without `ROLE_ADMIN`, S-28). SQL seam: no HTTP path
   * grants an admin tier. AdminBootstrapRunner creates only full admins, and operators grant the
   * limited tier by hand in the database. Registered as an adult player first (any role would do),
   * then the account's role is cleared and its authorities replaced, so login reports role ADMIN with
   * `limitedAdmin: true` (AuthService.login).
   */
  async limitedAdmin(): Promise<Account> {
    const dateOfBirth = this.clock.dateOfBirthForAge(30).toISODate()
    const account = await this.registerAndVerify('player', { dateOfBirth })
    const sql = await this.sql()
    await sql.query(`UPDATE main."user" SET skillars_role = NULL WHERE id = $1::bigint`, [
      account.userId,
    ])
    await sql.query(`DELETE FROM main.user_authority WHERE user_id = $1::bigint`, [account.userId])
    await sql.query(
      `INSERT INTO main.user_authority (user_id, authority_id)
       SELECT $1::bigint, id FROM main.authority WHERE name = 'ROLE_LTD_ADMIN'`,
      [account.userId],
    )
    return account
  }

  /**
   * The effective value of a runtime config key (C-16), as the admin config editor lists it: the
   * stored value, else the coded default. Read as the bootstrap admin, so specs follow the
   * environment's thresholds instead of hard-coding them.
   */
  async configValue(key: string): Promise<string> {
    const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
    try {
      const entries = await session.api.json<
        { key: string; value: string | null; codeDefault: number | null }[]
      >('GET', '/config/values')
      const entry = entries.find((e) => e.key === key)
      const value = entry?.value ?? entry?.codeDefault
      if (value === null || value === undefined) throw new Error(`No config value for ${key}`)
      return String(value)
    } finally {
      await session.dispose()
    }
  }

  /**
   * Set a runtime config value through the admin config API (which also drops the app's config cache)
   * and return the effective value it replaced. Global to the app: only for @config tests, through the
   * `runtimeConfig` fixture, which restores it.
   */
  async setConfigValue(key: string, value: string): Promise<string> {
    const previous = await this.configValue(key)
    const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
    try {
      await session.api.put(`/config/values/${key}`, { data: { value }, expectStatus: 200 })
      return previous
    } finally {
      await session.dispose()
    }
  }

  /**
   * Run a scheduled job now through the e2e-profile trigger (E2eJobResource), as the bootstrap admin,
   * instead of waiting for its schedule. Seed state that is already due first. The job holds its
   * ShedLock while it runs; a 409 means another run has it, so this retries until its own run lands.
   * A scheduled run (at boot, then on its schedule) keeps the lock for its `lockAtLeastFor`, 2 minutes
   * for the forfeiture, so the default wait outlasts that.
   */
  async runJob(name: 'pack-forfeiture', timeoutMs = 180_000): Promise<void> {
    const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
    try {
      const deadline = Date.now() + timeoutMs
      for (;;) {
        const response = await session.api.post(`/e2e/jobs/${name}`, {
          expectStatus: [204, 409],
        })
        if (response.status() === 204) return
        if (Date.now() > deadline) throw new Error(`Job ${name} stayed locked for ${timeoutMs}ms`)
        await new Promise((resolve) => setTimeout(resolve, 1_000))
      }
    } finally {
      await session.dispose()
    }
  }

  /**
   * Give every subscription price key a Stripe price id, as an operator does before launch; the seed
   * leaves them blank, and a blank price refuses every subscribe (priceNotConfigured). The stack's
   * WireMock accepts any price. Only blank keys are written, so a value someone set is kept. Once per
   * worker; through the admin config API, which also drops the config cache.
   */
  async subscriptionPrices(): Promise<void> {
    // A failed attempt is dropped, so the next caller retries instead of awaiting the same rejection.
    this.pricesReady ??= (async () => {
      const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
      try {
        const entries = await session.api.json<{ key: string; value: string | null }[]>(
          'GET',
          '/config/values',
        )
        for (const key of SUBSCRIPTION_PRICE_KEYS) {
          if (entries.find((e) => e.key === key)?.value) continue
          await session.api.put(`/config/values/${key}`, {
            data: { value: `price_e2e_${key.split('.').slice(1, 4).join('_')}` },
            expectStatus: 200,
          })
        }
      } finally {
        await session.dispose()
      }
    })().catch((error: unknown) => {
      this.pricesReady = undefined
      throw error
    })
    await this.pricesReady
  }

  /** Subscribe a coach to a paid tier through the API. Needs a card on file (saveCard). */
  async subscribeCoach(coach: CoachAccount, tier: 'INSTRUCTOR' | 'ACADEMY'): Promise<void> {
    await this.subscriptionPrices()
    const session = await this.login(coach)
    try {
      await session.api.post('/payment/subscriptions/coach/subscribe', {
        data: { tier },
        expectStatus: (status) => status >= 200 && status < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /**
   * Subscribe a player to a paid tier through the API, as the payer: the parent, or a self-registered
   * player for themselves. Needs the payer's card on file (saveCard).
   */
  async subscribePlayer(
    payer: Pick<Account, 'email' | 'password'>,
    playerId: string,
    tier: 'SEMI_PRO' | 'PRO',
  ): Promise<void> {
    await this.subscriptionPrices()
    const session = await this.login(payer)
    try {
      await session.api.post('/payment/subscriptions/player/subscribe', {
        // SEMI_PRO and PRO are sold yearly only. The player id as a string: it exceeds MAX_SAFE_INTEGER.
        data: { playerId, tier, billingInterval: 'YEARLY' },
        expectStatus: (status) => status >= 200 && status < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /**
   * Put a subscription into a billing state Stripe would report by webhook (none arrives on the stack).
   * PAST_DUE starts the grace period now. SQL seam on payment.coach_subscriptions or
   * payment.player_subscriptions; the subscription must exist (subscribeCoach / subscribePlayer).
   */
  async setSubscriptionStatus(
    owner: { coachId: string } | { playerId: string },
    status: 'PAST_DUE' | 'UNPAID' | 'PAUSED',
  ): Promise<void> {
    const [table, column, id, cast] =
      'coachId' in owner
        ? ['coach_subscriptions', 'coach_id', owner.coachId, 'uuid']
        : ['player_subscriptions', 'player_id', owner.playerId, 'bigint']
    const sql = await this.sql()
    const rows = await sql.query<{ status: string }>(
      `UPDATE payment.${table}
          SET status = $2::varchar, past_due_since = CASE WHEN $2::varchar = 'PAST_DUE' THEN now() END
        WHERE ${column} = $1::${cast} AND stripe_subscription_id IS NOT NULL
        RETURNING status`,
      [id, status],
    )
    if (rows.length !== 1) throw new Error(`No paid subscription for ${JSON.stringify(owner)}`)
  }

  /**
   * Publish APPROVED reviews of a coach and refresh the coach's rating aggregate, the way
   * CoachRatingService.recompute does after moderation. SQL seam: a review becomes visible only on a
   * Gemini verdict (C-8), and the stack has no Gemini stub yet. Each author must be a distinct account.
   */
  async seedApprovedReviews(
    coach: CoachAccount,
    reviews: { author: Account; rating: 1 | 2 | 3 | 4 | 5; body: string }[],
  ): Promise<void> {
    if (!coach.coachId) throw new Error('seedApprovedReviews needs a published coach')
    const sql = await this.sql()
    for (const { author, rating, body } of reviews) {
      await sql.query(
        `INSERT INTO reviews.coach_reviews (coach_id, author_id, author_role, rating, body, moderation_status)
         VALUES ($1::uuid, $2::bigint, $3, $4, $5, 'APPROVED')`,
        [
          coach.coachId,
          author.userId,
          author.kind === 'player' ? 'PLAYER' : 'PARENT',
          rating,
          body,
        ],
      )
    }
    await sql.query(
      `UPDATE marketplace.coach_profiles p
          SET review_count = agg.n, average_rating = ROUND(agg.avg, 1)
         FROM (SELECT COUNT(*) AS n, AVG(rating)::numeric AS avg FROM reviews.coach_reviews
                WHERE coach_id = $1::uuid AND moderation_status = 'APPROVED' AND related_party = false) agg
        WHERE p.id = $1::uuid`,
      [coach.coachId],
    )
  }

  /**
   * Put a family's one pack with a coach into a state, by SQL. Seam for states the UI reaches only
   * through time or other actors: expiry relative to now, a used or scheduled pause, an open card
   * dispute (FROZEN), a withdrawal (REVOKED), no sessions left (EXHAUSTED). Fields left out are kept.
   * Days are relative to now; the pause window is [pausedFromDays, pausedUntilDays).
   */
  async setPackState(
    parent: Pick<Account, 'userId'>,
    coach: CoachAccount,
    state: {
      expiresInDays?: number
      remainingSessions?: number
      pause?: { fromDays: number; untilDays: number }
      frozen?: boolean
      revokedReason?: 'CHARGEBACK_LOST' | 'EXTERNAL_REFUND' | 'COACH_SUSPENDED'
      extended?: boolean
    },
  ): Promise<void> {
    if (!coach.coachId) throw new Error('setPackState needs a published coach')
    const day = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString()
    const sets: string[] = []
    const params: unknown[] = [parent.userId, coach.coachId]
    const set = (column: string, value: unknown, cast: string) => {
      params.push(value)
      sets.push(`${column} = $${params.length}::${cast}`)
    }
    if (state.expiresInDays !== undefined)
      set('expires_at', day(state.expiresInDays), 'timestamptz')
    if (state.remainingSessions !== undefined)
      set('remaining_sessions', state.remainingSessions, 'int')
    if (state.pause) {
      set('paused_from', day(state.pause.fromDays), 'timestamptz')
      set('paused_until', day(state.pause.untilDays), 'timestamptz')
    }
    if (state.frozen !== undefined)
      set('dispute_frozen_at', state.frozen ? day(0) : null, 'timestamptz')
    if (state.revokedReason) {
      set('revoked_at', day(0), 'timestamptz')
      set('revoked_reason', state.revokedReason, 'varchar')
    }
    if (state.extended !== undefined)
      set('extended_at', state.extended ? day(0) : null, 'timestamptz')
    if (sets.length === 0) return
    const sql = await this.sql()
    const rows = await sql.query<{ purchase_id: string }>(
      `UPDATE payment.session_pack_purchases SET ${sets.join(', ')}
        WHERE parent_id = $1::bigint AND coach_id = $2::uuid
        RETURNING purchase_id`,
      params,
    )
    if (rows.length !== 1) throw new Error(`setPackState expected one pack, found ${rows.length}`)
  }

  /**
   * Append one positive entry to a family's credit wallet; returns its tx id and funding charge. SQL seam: wallet credit
   * is otherwise only written by refunds, dispute resolutions and chargeback restores, each at the end
   * of a multi-actor flow. `spendOnly` writes it the way an expired-pack refund is written (V208): a
   * BOOKING_REFUND whose whole amount is spend-only; otherwise a cashable DISPUTE_CREDIT. Each entry
   * references a fresh random id, so neither type's per-booking unique index can collide.
   *
   * `fundedBy` writes a cashable BOOKING_REFUND that names the card charge it came from
   * (`source_payment_intent_id`, V199), as a refund of a card booking is written: a cash-out refunds
   * onto that charge. The id is `pi_e2ecap_<cents>_…`, which the stack's WireMock reads back as a
   * charge that captured `capturedCents` and has refunded nothing (stripe-payment-intents-retrieve.json);
   * with `rejectsRefund` Stripe refuses any refund on it (stripe-refunds-rejected.json).
   */
  async seedWalletCredit(
    account: Pick<Account, 'userId'>,
    amount: number,
    options: {
      spendOnly?: boolean
      fundedBy?: { capturedCents: number; rejectsRefund?: boolean }
    } = {},
  ): Promise<{ txId: string; paymentIntent: string | null }> {
    const { spendOnly, fundedBy } = options
    const paymentIntent = fundedBy
      ? `pi_e2ecap_${fundedBy.capturedCents}_${fundedBy.rejectsRefund ? 'rejects_' : ''}${this.names
          .token('pi')
          .replace(/-/g, '_')}`
      : null
    const sql = await this.sql()
    const [row] = await sql.query<{ tx_id: string }>(
      `INSERT INTO payment.parent_credit_ledger
         (parent_id, amount, type, reference_id, description, spend_only_amount,
          source_payment_intent_id, source_card_amount)
       VALUES ($1::bigint, $2::numeric, $3, gen_random_uuid(), $4, $5::numeric, $6, $7::numeric)
       RETURNING tx_id`,
      [
        account.userId,
        amount.toFixed(2),
        spendOnly || fundedBy ? 'BOOKING_REFUND' : 'DISPUTE_CREDIT',
        spendOnly
          ? 'E2E seeded expired-pack refund'
          : fundedBy
            ? 'E2E seeded card booking refund'
            : 'E2E seeded dispute credit',
        (spendOnly ? amount : 0).toFixed(2),
        paymentIntent,
        fundedBy ? amount.toFixed(2) : null,
      ],
    )
    return { txId: row!.tx_id, paymentIntent }
  }

  /**
   * A family's wallet ledger, oldest first, each entry as its full row (every column, JSON-encoded).
   * The ledger is append-only (trg_ledger_no_update), so a row read twice must compare equal.
   */
  async walletLedger(
    account: Pick<Account, 'userId'>,
  ): Promise<{ txId: string; type: string; amount: string; row: string }[]> {
    const sql = await this.sql()
    return sql.query(
      `SELECT tx_id AS "txId", type, amount::text AS amount, to_jsonb(l)::text AS row
         FROM payment.parent_credit_ledger l
        WHERE parent_id = $1::bigint
        ORDER BY created_at, tx_id`,
      [account.userId],
    )
  }

  /**
   * Move a family's one booking with a coach to start `hoursFromNow` hours from now (one-hour
   * session). Returns the new start. SQL seam for time travel: the request page offers whole weeks of
   * the coach's published hours, so no spec can book a slot a fixed few hours ahead on any run date.
   * The original start moves with it (the cancel rule measures notice from the earlier of the two).
   */
  async moveBookingStart(
    parent: ParentAccount,
    coach: CoachAccount,
    hoursFromNow: number,
  ): Promise<Date> {
    if (!coach.coachId) throw new Error('moveBookingStart needs a published coach')
    const start = new Date(Date.now() + hoursFromNow * 3_600_000)
    start.setUTCSeconds(0, 0)
    const end = new Date(start.getTime() + 3_600_000)
    const sql = await this.sql()
    const rows = await sql.query<{ id: string }>(
      `UPDATE booking.bookings
          SET requested_start_time = $3::timestamptz, requested_end_time = $4::timestamptz,
              original_start_time = CASE WHEN original_start_time IS NULL THEN NULL ELSE $3::timestamptz END
        WHERE parent_id = $1::bigint AND coach_id = $2::uuid
        RETURNING id`,
      [parent.userId, coach.coachId, start.toISOString(), end.toISOString()],
    )
    if (rows.length !== 1) {
      throw new Error(`moveBookingStart expected one booking, found ${rows.length}`)
    }
    return start
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
