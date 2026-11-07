import { createHash, createHmac, randomBytes, randomInt, randomUUID } from 'node:crypto'
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
import { nextWeekSlot, slotInstant } from './slots.js'

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
   * Give a config key with no platform_config row one, holding its code default, so the admin config
   * API (which 404s a key with neither a row nor a ConfigBounds code default) can then set and restore
   * it. SQL seam; changes nothing the app reads, since the row repeats the default.
   */
  async ensureConfigRow(key: string, codeDefault: string): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      // The id is the table's identity; ON CONFLICT (key) makes concurrent callers safe.
      `INSERT INTO main.platform_config (key, value, value_type, description, updated_at)
       VALUES ($1::varchar, $2::text, 'STRING',
               'Seeded by the Pitchside e2e suite with its code default', now())
       ON CONFLICT (key) DO NOTHING`,
      [key, codeDefault],
    )
  }

  /**
   * Record a completed Stripe Identity check for a self-registered player, as the verification
   * webhook would. SQL seam: the check itself runs on a Stripe-hosted page.
   */
  async markAgeVerified(player: PlayerAccount): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      `INSERT INTO main.age_verifications
         (id, user_id, stripe_session_id, status, verified_tier, verified_at, created_at, updated_at)
       VALUES ($1::bigint, $2::bigint, $3, 'VERIFIED', 'ADULT', now(), now(), now())`,
      [
        (BigInt(randomInt(1, 2 ** 31)) * 2n ** 31n + BigInt(randomInt(0, 2 ** 31))).toString(),
        player.userId,
        `vs_e2e_${this.names.token('vs').replace(/-/g, '_')}`,
      ],
    )
  }

  /**
   * Run a scheduled job now through the e2e-profile trigger (E2eJobResource), as the bootstrap admin,
   * instead of waiting for its schedule. Seed state that is already due first. The job holds its
   * ShedLock while it runs; a 409 means another run has it, so this retries until its own run lands.
   * A scheduled run (at boot, then on its schedule) keeps the lock for its `lockAtLeastFor`, 2 minutes
   * for the forfeiture, so the default wait outlasts that. `unresolved-sessions` is the sweeper that
   * auto-refunds a session nobody started, claimed or completed 48 hours after its start.
   * `stuck-completions` force-completes a Quick Complete whose wrap-up never came
   * (`booking.stuck_completion_timeout_hours` after its last update): COMPLETED, attendance not
   * recorded, no recap.
   */
  async runJob(
    name: 'pack-forfeiture' | 'unresolved-sessions' | 'stuck-completions',
    timeoutMs = 180_000,
  ): Promise<void> {
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

  /** Cancel a coach's paid plan at period end through the API, as the coach. */
  async cancelCoachPlan(coach: CoachAccount): Promise<void> {
    const session = await this.login(coach)
    try {
      await session.api.delete('/payment/subscriptions/coach', { expectStatus: 204 })
    } finally {
      await session.dispose()
    }
  }

  /**
   * Leave a coach's subscribe attempt marked as running, the way a first click does while Stripe answers,
   * so the next subscribe is refused as in progress. SQL seam: the window between claim and release is
   * too short to hit from a test. The marker expires on its own (SUBSCRIBE_ATTEMPT_WINDOW).
   */
  async markCoachSubscribeInProgress(coach: CoachAccount): Promise<void> {
    if (!coach.coachId) throw new Error('markCoachSubscribeInProgress needs a published coach')
    const sql = await this.sql()
    const rows = await sql.query(
      `UPDATE payment.coach_subscriptions
          SET subscribe_attempt_key = 'e2e-in-flight', subscribe_attempt_at = now()
        WHERE coach_id = $1::uuid
        RETURNING coach_id`,
      [coach.coachId],
    )
    if (rows.length !== 1) throw new Error(`No subscription row for coach ${coach.coachId}`)
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
   * A CONFIRMED one-hour booking, paid per session with the payer's card: requested through the API
   * as the payer (a parent for their first child, or a self-registered player for themselves), for a slot next week inside the coach's published hours, and accepted as the coach.
   * Needs a card on file (saveCard). Returns the booking id. Distinct slots (weekday, time) keep two
   * bookings with one coach apart. Move it in time afterwards with setBooking.
   */
  async confirmedBooking(
    payer: ParentAccount | PlayerAccount,
    coach: CoachAccount,
    options: {
      playerId?: string
      weekday?: 1 | 2 | 3 | 4 | 5
      time?: string
      /** The coach's zone (onboardCoach's default). */
      timezone?: string
      /** false: leave it REQUESTED (the coach never answers). */
      accept?: boolean
    } = {},
  ): Promise<string> {
    if (!coach.coachId) throw new Error('confirmedBooking needs a published coach')
    const start = slotInstant(
      nextWeekSlot(options.weekday ?? 2, options.time ?? '10:00'),
      options.timezone ?? 'Europe/Berlin',
    )
    const end = new Date(start.getTime() + 3_600_000)
    const playerId =
      options.playerId ?? (payer.kind === 'parent' ? payer.children[0]!.playerId : payer.playerId)
    if (!playerId) throw new Error('confirmedBooking needs a player')
    const family = await this.login(payer)
    let bookingId: string
    try {
      const created = await family.api.json<{ id: string }>('POST', '/bookings/requests', {
        data: {
          coachId: coach.coachId,
          // As a string: player ids exceed Number.MAX_SAFE_INTEGER.
          playerId,
          requestedStartTime: start.toISOString(),
          requestedEndTime: end.toISOString(),
        },
      })
      bookingId = created.id
    } finally {
      await family.dispose()
    }
    if (options.accept === false) return bookingId
    const self = await this.login(coach)
    try {
      await self.api.put(`/bookings/requests/${bookingId}/accept`, {
        expectStatus: (status) => status >= 200 && status < 300,
      })
    } finally {
      await self.dispose()
    }
    // Acceptance charges the card and confirms the booking in a transaction after the accept commits.
    const deadline = Date.now() + 20_000
    for (;;) {
      const status = await this.bookingStatus(bookingId)
      if (status === 'CONFIRMED') return bookingId
      if (Date.now() > deadline) throw new Error(`Booking ${bookingId} stuck in ${status}`)
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
  }

  /** The per-session fee locked when the booking was requested. */
  async agreedPrice(bookingId: string): Promise<number> {
    const sql = await this.sql()
    const [row] = await sql.query<{ price: string }>(
      'SELECT agreed_price::text AS price FROM booking.bookings WHERE id = $1::uuid',
      [bookingId],
    )
    if (!row) throw new Error(`No booking ${bookingId}`)
    return Number(row.price)
  }

  async bookingStatus(bookingId: string): Promise<string> {
    const sql = await this.sql()
    const [row] = await sql.query<{ status: string }>(
      'SELECT status FROM booking.bookings WHERE id = $1::uuid',
      [bookingId],
    )
    if (!row) throw new Error(`No booking ${bookingId}`)
    return row.status
  }

  /** When a booking ends (`requested_end_time`), the anchor of its recap edit window. */
  async bookingEnd(bookingId: string): Promise<Date> {
    const sql = await this.sql()
    const [row] = await sql.query<{ end: string }>(
      'SELECT requested_end_time AS "end" FROM booking.bookings WHERE id = $1::uuid',
      [bookingId],
    )
    if (!row) throw new Error(`No booking ${bookingId}`)
    return new Date(row.end)
  }

  /**
   * Time travel for one booking: start it `startMinutesFromNow` minutes from now (negative = in the past;
   * a one-hour session), and optionally put it in a status its scheduler or the coach would have set.
   * SQL seam, like moveBookingStart.
   * - UPCOMING: what the reminder job sets a day before; it is marked reminded so the job leaves it.
   * - COMPLETED_PENDING_CONFIRMATION: a Quick Complete with no live start (live_started_at stays NULL).
   * - COMPLETED: settled long enough ago to anchor the dispute window (`statusChangedDaysAgo`).
   */
  async setBooking(
    bookingId: string,
    change: {
      startMinutesFromNow: number
      status?: 'UPCOMING' | 'COMPLETED_PENDING_CONFIRMATION' | 'COMPLETED'
      statusChangedDaysAgo?: number
    },
  ): Promise<Date> {
    const start = new Date(Date.now() + change.startMinutesFromNow * 60_000)
    start.setUTCSeconds(0, 0)
    const end = new Date(start.getTime() + 3_600_000)
    const changedAt = new Date(Date.now() - (change.statusChangedDaysAgo ?? 0) * 86_400_000)
    const sql = await this.sql()
    const rows = await sql.query(
      `UPDATE booking.bookings
          SET requested_start_time = $2::timestamptz, requested_end_time = $3::timestamptz,
              original_start_time = CASE WHEN original_start_time IS NULL THEN NULL ELSE $2::timestamptz END,
              status = COALESCE($4::varchar, status),
              status_changed_at = CASE WHEN $4::varchar IS NULL THEN status_changed_at ELSE $5::timestamptz END,
              updated_at = CASE WHEN $4::varchar IS NULL THEN updated_at ELSE $5::timestamptz END,
              primary_reminder_sent_at = COALESCE(primary_reminder_sent_at, now()),
              live_started_at = NULL,
              confirmation_requested_at = CASE WHEN $4::varchar = 'COMPLETED_PENDING_CONFIRMATION'
                                               THEN now() ELSE confirmation_requested_at END
        WHERE id = $1::uuid
        RETURNING id`,
      [
        bookingId,
        start.toISOString(),
        end.toISOString(),
        change.status ?? null,
        changedAt.toISOString(),
      ],
    )
    if (rows.length !== 1) throw new Error(`No booking ${bookingId}`)
    return start
  }

  /** The coach Quick Completes a started session (no wrap-up yet): COMPLETED_PENDING_CONFIRMATION. */
  async quickComplete(coach: CoachAccount, bookingId: string): Promise<void> {
    const session = await this.login(coach)
    try {
      await session.api.post(`/bookings/${bookingId}/quick-complete`, {
        expectStatus: (s) => s >= 200 && s < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /** The coach's wrap-up, as the wizard sends it: attended, five stars each, no homework. */
  async submitWrapUp(
    coach: CoachAccount,
    bookingId: string,
    recap: { whatWeDid: string; whatsNext: string },
    /** Drills (at most 2) the wrap-up assigns as homework. */
    homeworkDrillIds: string[] = [],
  ): Promise<void> {
    const session = await this.login(coach)
    try {
      await session.api.post(`/bookings/${bookingId}/complete`, {
        data: {
          playerAttended: true,
          effortRating: 5,
          focusRating: 5,
          techniqueRating: 5,
          homeworkDrillIds,
          mode: 'QUICK',
          recapWhatWeDid: recap.whatWeDid,
          recapWhatsNext: recap.whatsNext,
        },
        expectStatus: (s) => s >= 200 && s < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /** The family's "Confirm session" on a Quick Complete. */
  async confirmCompletion(family: ParentAccount | PlayerAccount, bookingId: string): Promise<void> {
    const session = await this.login(family)
    try {
      await session.api.put(`/bookings/${bookingId}/confirm-completion`, {
        expectStatus: (s) => s >= 200 && s < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /**
   * A booked session that ended `endedMinutesAgo` minutes ago and is COMPLETED through the product's
   * own path: Quick Complete, then (unless `recap` is null) the wrap-up, then the family confirms.
   * With `recap: null` the family confirms before any wrap-up: COMPLETED, awaiting the wrap-up.
   */
  async completedSession(
    parent: ParentAccount,
    coach: CoachAccount,
    options: {
      endedMinutesAgo?: number
      weekday?: 1 | 2 | 3 | 4 | 5
      recap?: { whatWeDid: string; whatsNext: string } | null
      /** Homework the wrap-up assigns (needs a recap). */
      homework?: string[]
    } = {},
  ): Promise<string> {
    const bookingId = await this.confirmedBooking(parent, coach, { weekday: options.weekday })
    await this.setBooking(bookingId, {
      startMinutesFromNow: -60 - (options.endedMinutesAgo ?? 30),
      status: 'UPCOMING',
    })
    await this.quickComplete(coach, bookingId)
    const recap =
      options.recap === undefined
        ? { whatWeDid: 'Passing patterns', whatsNext: 'Weak-foot finishing' }
        : options.recap
    if (recap) await this.submitWrapUp(coach, bookingId, recap, options.homework ?? [])
    await this.confirmCompletion(parent, bookingId)
    const status = await this.bookingStatus(bookingId)
    if (status !== 'COMPLETED') throw new Error(`Booking ${bookingId} is ${status}, not COMPLETED`)
    return bookingId
  }

  /**
   * A Quick Complete whose wrap-up never came, swept by the stuck-completion job: COMPLETED with an
   * empty recap the coach can still add. The booking is put `daysAgo` days back (past the 72-hour
   * sweep timeout) before the job runs.
   */
  async sweptSession(parent: ParentAccount, coach: CoachAccount, daysAgo = 4): Promise<string> {
    const bookingId = await this.confirmedBooking(parent, coach, { weekday: 1 })
    await this.setBooking(bookingId, {
      startMinutesFromNow: -daysAgo * 1440,
      status: 'COMPLETED_PENDING_CONFIRMATION',
      statusChangedDaysAgo: daysAgo,
    })
    await this.runJob('stuck-completions')
    const status = await this.bookingStatus(bookingId)
    if (status !== 'COMPLETED') throw new Error(`Swept booking ${bookingId} is ${status}`)
    return bookingId
  }

  /**
   * Open (or find) the conversation between a coach and a player through the API, as either party:
   * the coach, the player's parent or the self-registered player. Needs a booking in CONFIRMED,
   * UPCOMING, IN_PROGRESS or COMPLETED. Returns the conversation id (a TSID, as a string).
   */
  async conversation(
    caller: CoachAccount | ParentAccount | PlayerAccount,
    coach: CoachAccount,
    playerId: string,
  ): Promise<string> {
    const session = await this.login(caller)
    try {
      const created = await session.api.post('/messaging/conversations', {
        // Ids as strings: TSIDs exceed Number.MAX_SAFE_INTEGER.
        data: caller.kind === 'coach' ? { playerId } : { coachId: coach.coachId, playerId },
        expectStatus: (status) => status >= 200 && status < 300,
      })
      // Read the id from the raw text: JSON.parse would round a TSID.
      const id = /"conversationId"\s*:\s*"?(\d+)/.exec(await created.text())?.[1]
      if (!id) throw new Error(`No conversationId in ${await created.text()}`)
      return id
    } finally {
      await session.dispose()
    }
  }

  /** The HTTP status of opening a conversation as `caller` (for the refusals: 403 and its reason). */
  async conversationRefusal(
    caller: CoachAccount | ParentAccount | PlayerAccount,
    coach: CoachAccount,
    playerId: string,
  ): Promise<{ status: number; errorKey?: string }> {
    const session = await this.login(caller)
    try {
      const response = await session.api.post('/messaging/conversations', {
        data: caller.kind === 'coach' ? { playerId } : { coachId: coach.coachId, playerId },
        expectStatus: (status) => status >= 400,
      })
      const body = (await response.json().catch(() => ({}))) as {
        errorMsg?: { errorKey?: string }
      }
      return { status: response.status(), errorKey: body.errorMsg?.errorKey }
    } finally {
      await session.dispose()
    }
  }

  /**
   * Put messages straight into a conversation, as `sender` with `role`, oldest first, one second apart
   * and ending now. SQL seam for a state the API cannot hold still (a PENDING message, whose verdict
   * arrives inside the send request) or for volume (more than a page of history). Returns their ids.
   */
  async seedMessages(
    conversationId: string,
    sender: Account,
    role: 'COACH' | 'PARENT' | 'PLAYER',
    contents: string[],
    status: 'PENDING' | 'APPROVED' | 'UNDER_REVIEW' | 'BLOCKED' = 'APPROVED',
  ): Promise<string[]> {
    const sql = await this.sql()
    const ids: string[] = []
    for (const [index, content] of contents.entries()) {
      const [row] = await sql.query<{ id: string }>(
        `INSERT INTO messaging.messages
           (id, conversation_id, sender_id, sender_role, content, moderation_status, delivered_at, created_at)
         VALUES ($7::bigint, $1::bigint, $2::bigint, $3::varchar, $4::text, $5::varchar,
                 CASE WHEN $5::varchar = 'APPROVED' THEN now() END,
                 now() - make_interval(secs => $6::int))
         RETURNING id::text AS id`,
        [
          conversationId,
          sender.userId,
          role,
          content,
          status,
          contents.length - index,
          // Ids are app-generated TSIDs with no database default: a random 62-bit id, unique enough
          // across workers seeding at once.
          (BigInt(randomInt(1, 2 ** 31)) * 2n ** 31n + BigInt(randomInt(0, 2 ** 31))).toString(),
        ],
      )
      ids.push(row!.id)
    }
    await sql.query(
      'UPDATE messaging.conversations SET last_message_at = now() WHERE id = $1::bigint',
      [conversationId],
    )
    return ids
  }

  /** Raise a dispute through the API, as the family (or the coach). Returns its id. */
  async raiseDispute(
    raiser: Pick<Account, 'email' | 'password'>,
    bookingId: string,
    reason: 'COACH_NO_SHOW' | 'SESSION_QUALITY' | 'SAFETY_CONCERN' | 'OTHER' = 'SESSION_QUALITY',
  ): Promise<string> {
    const session = await this.login(raiser)
    try {
      const raised = await session.api.json<{ disputeId: string }>('POST', '/disputes', {
        data: { bookingId, reason, details: 'Raised by the e2e provisioner.' },
      })
      return raised.disputeId
    } finally {
      await session.dispose()
    }
  }

  /** The latest dispute raised on a booking (e.g. through the UI), or undefined. */
  async disputeOn(bookingId: string): Promise<{ id: string; status: string } | undefined> {
    const sql = await this.sql()
    const [row] = await sql.query<{ id: string; status: string }>(
      `SELECT id::text AS id, status FROM admin.disputes WHERE booking_id = $1::uuid
        ORDER BY created_at DESC LIMIT 1`,
      [bookingId],
    )
    return row
  }

  /** Close a dispute as the bootstrap admin: dismiss it, or resolve it with no money moved. */
  async closeDispute(
    disputeId: string,
    how: { dismiss: string } | { resolveNote: string },
  ): Promise<void> {
    const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
    try {
      if ('dismiss' in how) {
        await session.api.post(`/admin/disputes/${disputeId}/dismiss`, {
          data: { reason: how.dismiss },
          expectStatus: (status) => status >= 200 && status < 300,
        })
      } else {
        await session.api.post(`/admin/disputes/${disputeId}/resolve`, {
          data: { resolution: 'NO_ACTION', resolutionNote: how.resolveNote },
          expectStatus: (status) => status >= 200 && status < 300,
        })
      }
    } finally {
      await session.dispose()
    }
  }

  /** Decide the one appeal on a subject (a dispute or a strike) as the bootstrap admin. */
  async decideAppeal(
    subjectId: string,
    outcome: 'UPHELD' | 'OVERTURNED',
    note: string,
  ): Promise<void> {
    const sql = await this.sql()
    const [appeal] = await sql.query<{ id: string }>(
      "SELECT id::text AS id FROM admin.appeals WHERE subject_id = $1::uuid AND status = 'OPEN'",
      [subjectId],
    )
    if (!appeal) throw new Error(`No open appeal on ${subjectId}`)
    const session = await this.login({ email: E2E.adminEmail, password: E2E.adminPassword })
    try {
      await session.api.post(`/admin/appeals/${appeal.id}/decide`, {
        data: { outcome, note },
        expectStatus: (status) => status >= 200 && status < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /**
   * Record excused coach cancellations (reason HEALTH_MEDICAL) within the cap's window, as earlier
   * bookings would have left them. SQL seam: each needs a booking of its own to cancel otherwise.
   */
  async seedExcusedCancellations(coach: CoachAccount, count: number): Promise<void> {
    if (!coach.coachId) throw new Error('seedExcusedCancellations needs a published coach')
    const sql = await this.sql()
    for (let i = 0; i < count; i++) {
      await sql.query(
        `INSERT INTO payment.coach_cancellation_history (id, coach_id, booking_id, cancel_reason, created_at, after_start)
         VALUES (gen_random_uuid(), $1::uuid, gen_random_uuid(), 'HEALTH_MEDICAL', now() - make_interval(days => $2), false)`,
        [coach.coachId, i + 1],
      )
    }
  }

  /** The coach's reliability strikes, oldest first. */
  async strikes(coach: CoachAccount): Promise<{ reason: string; bookingId: string | null }[]> {
    if (!coach.coachId) throw new Error('strikes needs a published coach')
    const sql = await this.sql()
    return sql.query(
      `SELECT reason, booking_id::text AS "bookingId"
         FROM marketplace.coach_reliability_strikes
        WHERE coach_id = $1::uuid
        ORDER BY created_at`,
      [coach.coachId],
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

  // ─── Suite J: development ────────────────────────────────────────────────────────────────────

  /**
   * One radar assessment by a coach, through the product's API. Needs an Instructor or Academy coach
   * with a live booking for the player (confirmedBooking). The composite is computed asynchronously:
   * follow with radarScores() to wait for it.
   */
  async radarAssessment(
    coach: CoachAccount,
    playerId: string,
    type: 'OBJECTIVE' | 'MATCH_OBSERVATION' | 'COACH_EVALUATION',
    scores: Record<string, number>,
  ): Promise<void> {
    const session = await this.login(coach)
    try {
      await session.api.post(`/development/players/${playerId}/radar/entries`, {
        data: {
          assessmentGroupId: randomUUID(),
          assessmentDate: new Date().toISOString().slice(0, 10),
          assessmentType: type,
          entries: Object.entries(scores).map(([skillCode, score]) => ({ skillCode, score })),
        },
        expectStatus: (status) => status >= 200 && status < 300,
      })
    } finally {
      await session.dispose()
    }
  }

  /**
   * The radar's composite and baseline per skill as the viewer's /radar/display returns them,
   * polled until `until` holds (the composite is written after the assessment commits).
   */
  async radarScores(
    viewer: Pick<Account, 'email' | 'password'>,
    playerId: string,
    until: (skills: Record<string, { score: number | null; baseline: number | null }>) => boolean,
  ): Promise<Record<string, { score: number | null; baseline: number | null }>> {
    const session = await this.login(viewer)
    try {
      const deadline = Date.now() + 30_000
      for (;;) {
        const display = await session.api.json<{
          skills: {
            skillCode: string
            compositeScore: number | null
            baselineScore: number | null
          }[]
        }>('GET', `/development/players/${playerId}/radar/display`)
        const skills = Object.fromEntries(
          display.skills.map((s) => [
            s.skillCode,
            {
              score: s.compositeScore === null ? null : Number(s.compositeScore),
              baseline: s.baselineScore === null ? null : Number(s.baselineScore),
            },
          ]),
        )
        if (until(skills)) return skills
        if (Date.now() > deadline)
          throw new Error(`radarScores: timed out (${JSON.stringify(skills)})`)
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
    } finally {
      await session.dispose()
    }
  }

  /**
   * SQL seam: weekly skill exposure (SLU). The product writes these only when a completed session
   * with a saved plan is attended; `weeksAgo` 0 is the current UTC ISO week.
   */
  async seedExposure(
    playerId: string,
    rows: { skill: string; weeksAgo: number; slu: number }[],
  ): Promise<void> {
    const sql = await this.sql()
    for (const row of rows) {
      await sql.query(
        `INSERT INTO development.player_slu_weekly_snapshot (player_id, skill_code, iso_year, iso_week, total_slu)
         SELECT $1::bigint, $2,
                extract(isoyear FROM w)::smallint, extract(week FROM w)::smallint, $3
           FROM (SELECT (now() AT TIME ZONE 'UTC') - make_interval(weeks => $4::int) AS w) t
         ON CONFLICT (player_id, skill_code, iso_year, iso_week) DO UPDATE SET total_slu = EXCLUDED.total_slu`,
        [playerId, row.skill, row.slu, row.weeksAgo],
      )
    }
  }

  /** SQL seam: an open neglected-skill flag (the detector is a weekly job with a warm-up). */
  async flagNeglected(playerId: string, skill: string): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      `INSERT INTO development.neglected_skill_flags (player_id, skill_code) VALUES ($1::bigint, $2)`,
      [playerId, skill],
    )
  }

  // ─── Suite I part 4: drills, session plans, templates ─────────────────────────────────────────

  /** A drill the coach authored (their PRIVATE library), through the product's own API. */
  async privateDrill(
    coach: CoachAccount,
    drill: { name: string; difficultyTier?: string; primarySkill?: string },
  ): Promise<string> {
    const session = await this.login(coach)
    try {
      const created = await session.api.json<{ id: string }>('POST', '/session/drills', {
        data: {
          name: drill.name,
          description: 'Authored by an e2e test.',
          primarySkills: [drill.primarySkill ?? 'passing'],
          secondarySkills: [],
          repDensity: 20,
          intensity: 5,
          pressureLevel: 3,
          cognitiveLoad: 3,
          matchRealism: 3,
          weakFootBias: false,
          difficultyTier: drill.difficultyTier ?? 'U12',
          equipmentRequired: ['ball'],
          coachingPoints: ['Head up before the first touch'],
        },
      })
      return created.id
    } finally {
      await session.dispose()
    }
  }

  /** The platform library as a coach sees it, name and id. */
  async platformDrills(coach: CoachAccount): Promise<{ id: string; name: string }[]> {
    const session = await this.login(coach)
    try {
      return await session.api.json<{ id: string; name: string }[]>(
        'GET',
        '/session/drills?library=PLATFORM',
      )
    } finally {
      await session.dispose()
    }
  }

  /**
   * A saved session plan on a CONFIRMED booking: the four default blocks, with `drillIds` in the
   * first. Needs a coach whose tier has the session builder (subscribeCoach INSTRUCTOR).
   */
  async sessionPlan(coach: CoachAccount, bookingId: string, drillIds: string[]): Promise<string> {
    const session = await this.login(coach)
    try {
      const plan = await session.api.json<{ id: string }>('POST', '/session/sessions', {
        data: {
          bookingId,
          status: 'SAVED',
          developmentFocus: ['technical'],
          blocks: [
            ['WARM_UP', 'Warm-Up', 10],
            ['TECHNICAL_FOUNDATION', 'Technical Foundation', 15],
            ['GAME_INTENSITY', 'Game Intensity', 25],
            ['COOL_DOWN_REVIEW', 'Cool-Down & Review', 10],
          ].map(([blockType, blockName, durationMinutes], i) => ({
            blockType,
            blockName,
            durationMinutes,
            drills: i === 0 ? drillIds.map((drillId, order) => ({ drillId, order })) : [],
          })),
        },
      })
      return plan.id
    } finally {
      await session.dispose()
    }
  }

  /** A template saved from an existing session plan. */
  async sessionTemplate(coach: CoachAccount, sessionId: string, name: string): Promise<string> {
    const session = await this.login(coach)
    try {
      const template = await session.api.json<{ id: string }>('POST', '/session/templates', {
        data: { sessionId, name },
      })
      return template.id
    } finally {
      await session.dispose()
    }
  }

  /** The booking's saved plan as the server holds it: block types in order, drill ids per block. */
  async sessionPlanOf(
    coach: CoachAccount,
    bookingId: string,
  ): Promise<{ blockType: string; drills: string[] }[]> {
    const session = await this.login(coach)
    try {
      const plan = await session.api.json<{
        blocks: { blockType: string; drills: { drillId: string; order: number }[] }[]
      }>('GET', `/session/sessions/by-booking/${bookingId}`)
      return plan.blocks.map((b) => ({
        blockType: b.blockType,
        drills: [...b.drills].sort((x, y) => x.order - y.order).map((d) => d.drillId),
      }))
    } finally {
      await session.dispose()
    }
  }

  // ─── Suite L: video ──────────────────────────────────────────────────────────────────────────

  /**
   * A Bunny Stream webhook, signed the way the app verifies it (hex HMAC-SHA256 of the raw body,
   * keyed by APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET in the stack). Status 7 = upload finished,
   * 3 = encoding finished, 5 = encoding failed, 8 = upload failed.
   */
  async bunnyWebhook(guid: string, status: 3 | 5 | 7 | 8): Promise<void> {
    const body = JSON.stringify({ VideoLibraryId: 123456, VideoGuid: guid, Status: status })
    const signature = createHmac('sha256', 'e2e-webhook-signing-secret').update(body).digest('hex')
    const context = await request.newContext()
    try {
      const response = await context.post(`${API_BASE_URL}/video/webhooks/bunny`, {
        headers: { 'Content-Type': 'application/json', 'X-BunnyStream-Signature': signature },
        data: body,
      })
      if (!response.ok()) {
        throw new Error(`bunnyWebhook ${status} for ${guid}: HTTP ${response.status()}`)
      }
    } finally {
      await context.dispose()
    }
  }

  /** The newest video an owner has (owner id: a PlayerProfile id, or a coach-profile UUID). */
  async latestVideo(
    ownerId: string,
  ): Promise<{ id: string; guid: string | null; state: string; storageBytes: number | null }> {
    const sql = await this.sql()
    const [row] = await sql.query<{
      id: string
      guid: string | null
      state: string
      storage_bytes: string | null
    }>(
      `SELECT id, provider_asset_id AS guid, operational_state AS state, storage_bytes
         FROM main.videos WHERE owner_id = $1 ORDER BY created_at DESC LIMIT 1`,
      [ownerId],
    )
    if (!row) throw new Error(`No video for owner ${ownerId}`)
    return {
      id: row.id,
      guid: row.guid,
      state: row.state,
      storageBytes: row.storage_bytes === null ? null : Number(row.storage_bytes),
    }
  }

  /** Polls a video's operational state until it is `state` (webhooks are processed every 5 s). */
  async videoState(videoId: string, state: string, timeoutMs = 45_000): Promise<void> {
    const sql = await this.sql()
    const deadline = Date.now() + timeoutMs
    for (;;) {
      const [row] = await sql.query<{ state: string }>(
        'SELECT operational_state AS state FROM main.videos WHERE id = $1::uuid',
        [videoId],
      )
      if (row?.state === state) return
      if (Date.now() > deadline) {
        throw new Error(`videoState: ${videoId} is ${row?.state ?? 'missing'}, not ${state}`)
      }
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
  }

  /**
   * SQL seam: a video row in a settled state (READY or FAILED), skipping upload and processing.
   * `storageBytes` is what a delete gives back to the quota.
   */
  async seedVideo(video: {
    ownerId: string
    title?: string
    state?: 'READY' | 'FAILED'
    type?: 'HOMEWORK' | 'COACH_REVIEW' | 'DRILL_DEMO'
    storageBytes?: number
    subject?: { bookingId: string; playerId: string }
  }): Promise<string> {
    const sql = await this.sql()
    const [row] = await sql.query<{ id: string }>(
      `INSERT INTO main.videos
         (owner_id, provider, provider_asset_id, operational_state, access_state, title, visibility,
          video_type, storage_bytes, subject_booking_id, subject_player_id, created_at, updated_at)
       VALUES ($1, 'bunny', $2, $3, 'ACTIVE', $4, 'PRIVATE', $5, $6, $7::uuid, $8::bigint, now(), now())
       RETURNING id`,
      [
        video.ownerId,
        randomUUID(),
        video.state ?? 'READY',
        video.title ?? `clip-${randomUUID().slice(0, 6)}.mp4`,
        video.type ?? 'HOMEWORK',
        video.storageBytes ?? 204800,
        video.subject?.bookingId ?? null,
        video.subject?.playerId ?? null,
      ],
    )
    return row!.id
  }

  /** SQL seam: an owner's used storage (the quota bar and the over-quota check read it). */
  async setVideoQuotaUsed(ownerId: string, storageUsedBytes: number): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      `INSERT INTO main.video_quotas (user_id, storage_used_bytes, bandwidth_used_bytes, bandwidth_period_start)
       VALUES ($1, $2, 0, date_trunc('month', now()))
       ON CONFLICT (user_id) DO UPDATE SET storage_used_bytes = EXCLUDED.storage_used_bytes`,
      [ownerId, storageUsedBytes],
    )
  }

  /** SQL seam: a passed scan, so the video may be shared past its uploader (the S-18 gate). */
  async markVideoScanned(videoId: string): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      `INSERT INTO main.video_moderation_scans (video_id, layer, outcome)
       VALUES ($1::uuid, 'VIDEOINTEL', 'PASSED')
       ON CONFLICT (video_id, layer) DO UPDATE SET outcome = 'PASSED'`,
      [videoId],
    )
  }

  /** SQL seam: a player's date of birth (minors cannot register themselves). */
  async setDateOfBirth(playerId: string, age: number): Promise<void> {
    const sql = await this.sql()
    await sql.query(
      `UPDATE main.player_profiles SET date_of_birth = (now() - make_interval(years => $2::int, days => 40))::date
        WHERE id = $1::bigint`,
      [playerId, age],
    )
  }

  /**
   * The player's locker-room homework as the viewer's API returns it, polled until `count` items
   * are there (assignment runs asynchronously on the session's completion).
   */
  async homework(
    viewer: Pick<Account, 'email' | 'password'>,
    playerId: string,
    count: number,
  ): Promise<
    {
      assignmentId: string
      coachId: string
      coachDisplayName: string
      completed: boolean
      drill: { name: string }
    }[]
  > {
    const session = await this.login(viewer)
    try {
      const deadline = Date.now() + 30_000
      for (;;) {
        const items = await session.api.json<
          {
            assignmentId: string
            coachId: string
            coachDisplayName: string
            completed: boolean
            drill: { name: string }
          }[]
        >('GET', `/session/players/${playerId}/homework`)
        if (items.length >= count) return items
        if (Date.now() > deadline) {
          throw new Error(`homework: ${items.length} of ${count} after 30 s for player ${playerId}`)
        }
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
    } finally {
      await session.dispose()
    }
  }

  private api(context: APIRequestContext): ApiClient {
    return new ApiClient(context, { baseUrl: API_BASE_URL, requestIdPrefix: 'e2e-provision' })
  }
}
