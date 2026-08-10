# UAT Readiness — Priorities for `deferred-work.md`

Ranking of the open items in `deferred-work.md` against one goal: **stand the app up on a VPS
(Hetzner/netcup), create a player, parent, coach and admin account, log in, search a coach, pay for
lessons, book lessons.**

Written 2026-08-09 against commit `a170e69`. Every claim in the P0 section and every claim in
"Ledger hygiene" below was verified by direct read of the tree today — the ledger's own audits have
flagged for seven consecutive rounds that most of its forward-references are unverified, so nothing
here is taken on the ledger's word.

## Ranking rule applied

1. **Does the UAT journey complete without it?** No → P0. Completes but gives a wrong or confusing
   result → P1.
2. **Deployment/infrastructure items rank below functional blockers** (your instruction). The one
   exception would be a deploy item that stops the app booting at all — there is none; see P2.
3. **Repeatedly deferred items rank lower** (your instruction). Two signals used: an item carrying a
   stale `[AUDIT: STILL OPEN]` marker after the story that promised to fix it already shipped, and an
   item that three or more separate audits recorded as "examined and deliberately left alone". Both
   mean the same thing in practice — every reviewer who looked at it decided it wasn't worth the cost.
   They are collected in P3.
4. **Items outside the journey do not rank at all.** The Epic 4/5/6 backlog (drill library, SLU
   engine, skills radar, video pipeline) is roughly half the file and touches nothing on your path.

## Journey verdict, step by step

| Step | Verdict | Blocking item |
|---|---|---|
| Deploy to VPS | **Works** | UAT profile, `docker-compose.uat.yml` and `docs/deployment/uat-deployment.md` all exist; CI is green on `master` and publishes `ghcr.io/…:sha-*` on every push |
| Create parent account | **Works** | — |
| Create coach account | **Works, with a lockout risk** | P0-3 |
| Create player account | **Works (registration only)** | P0-2 — the player can log in but cannot book |
| Create admin account | **Impossible** | **P0-1** |
| Log in | **Works** | — |
| Search a coach | **Works, once the coach publishes** | Coach profile is `DRAFT` until the 5-step builder completes; see P0-3 |
| Pay for lessons (parent) | **Works** | Stripe card collection shipped in `deferred-11` |
| Pay for subscription (coach) | **Not self-serve** | **P0-4** |
| Book lessons | **Completes, but produces nonsense** | **P0-5** |

---

## P0 — Fix before UAT is worth running

### P0-1. No admin account can be created — *not tracked in the ledger at all*

`main.authority` is seeded with exactly three rows: `100 ROLE_COACH`, `101 ROLE_PARENT`
(`V21__skillars_security_extension.sql:35-39`) and `102 ROLE_PLAYER`
(`V84__player_self_registration.sql:5-8`). **No migration ever seeds `ROLE_ADMIN` or
`ROLE_LTD_ADMIN`**, there is no admin registration endpoint, and no deployment doc describes
bootstrapping one. Meanwhile `SecurityConstants.HAS_ADMIN_ROLE` gates the entire admin surface —
moderation queue, disputes, coach enforcement, config, GDPR tools.

Today the only way to get an admin is hand-written SQL against a running database: insert the
authority row, insert a `main."user"` row with a bcrypt hash and `activated = true`, then insert the
join row. Nobody has written that down.

Smallest fix: a migration seeding the two authority rows, plus a documented (or scripted) bootstrap
for the first admin user. This is the single hardest blocker on your list — one of your four account
types simply cannot exist.

### P0-2. A player account cannot book anything — product gap, not a bug

Player self-registration works (`PlayerRegistrationResource`, `V84`). But every booking-creation
endpoint is parent-only: `BookingResource:36` and `BookingBatchResource:40` are both
`@PreAuthorize(HAS_PARENT_ROLE)`. A self-registered adult player can register, log in and browse,
and the journey stops there.

The ledger's `skillars-deferred-16 story creation` D1 is the visible tip of this — an adult player
has `parent_id IS NULL`, and `messaging.conversations.parent_id` is `NOT NULL`, so opening a
conversation for one would 500. That item correctly notes it is unreachable today *precisely
because* the player can never acquire a booking.

Decide before UAT which you want: scope the player journey to "register + browse" and test booking
as a parent, or build player self-booking. If you build it, the D1 messaging item stops being
theoretical and must ship alongside.

### P0-3. Coach profile builder can hard-lock a coach out — `deferred-18` D5

`ProfileBuilderStep1.vue:90` and `ProfileBuilderStep4.vue:75` send
`Intl.DateTimeFormat().resolvedOptions().timeZone` verbatim, with no zone picker and no fallback, and
`@IanaTimezone` (added by `deferred-18` AC4) is now the only gate on that value. A browser on newer
tzdata than the deployed JVM — `Europe/Kyiv` (tzdata 2022b), `America/Ciudad_Juarez` (2022g) — makes
Step 1 and Step 4 uncompletable, with a validation error naming the coach's own machine and no way to
override.

This matters more than the ledger entry suggests, because I confirmed the coach profile starts as
`DRAFT` (`CoachProfileService.java:71,81`) and only becomes `ACTIVE` when the builder completes
(`:204-210`), and search only returns `ACTIVE`/`REDUCED` (`CoachSearchSpecification.java:38`). **A
coach who cannot finish the builder never appears in search** — so this one defect can break both
"create a coach account" and "search a coach".

Whether it fires depends on your VPS JDK's tzdata versus your testers' browsers. Cheapest UAT
mitigation is pinning a recent JDK base image and verifying your testers' zones resolve; the real fix
is a zone picker or an alias fallback.

### P0-4. A coach cannot subscribe through the UI — `deferred-11` D1

`CoachSubscriptionPage.vue:118` still renders a raw text input asking the coach to type a Stripe
Payment Method ID (`pm_...`), which `deferred-11` removed from the parent path but could not remove
here. Verified still present today.

The blocker is structural, as the ledger records: `StripeCustomer`'s `@Id` is `parent_id` (a `Long`
user id), and both `POST /api/payment/setup-intent` and `GET /api/payment/payment-method` are
`@PreAuthorize(HAS_PARENT_ROLE)`. Supporting coach cards needs either a second customer table or a
re-key of `payment.stripe_customers` — a schema migration with a real design decision behind it.

**How urgent depends on your UAT script.** Coach search is not tier-gated (I checked —
`CoachSearchSpecification` filters on profile status only, not subscription), so an unsubscribed coach
is still findable and bookable. If your UAT does not include coach subscription, drop this to P1 and
test with a Payment Method ID pasted from the Stripe test dashboard. If it does, this is a real story.

### P0-5. One click books an 8-hour lesson — `deferred-17` D1

`AvailabilityService.computeAvailableSlots` returns whole window-minus-block **segments**. There is no
fixed-length slicing anywhere and no duration bound in the booking package. Backend validation is only:
start in the future, end after start, range inside a window.

So a coach with a 09:00–17:00 window and no blocks presents **exactly one row**, and clicking it books
an eight-hour session — consuming one pack credit and locking out the coach's entire day via
`findOverlappingBookings`. Batch mode multiplies this by ten (`CreateBatchRequest` `@Size(max = 10)`),
and `BookingBatchService:106-113` performs no availability-window check at all.

The ledger is right that this is "newly reachable, not newly introduced" — both submit paths were dead
before `deferred-17` fixed them. But that is exactly the point: **the first booking you attempt in UAT
will hit this.** Slot slicing or a session-duration field is a product decision, and it is the one
product decision that stands between you and a meaningful booking test.

---

## P1 — Will distort UAT results; fix during, not before

Ordered by how likely you are to trip over them.

1. **`deferred-18` D3 — a parent's own pending request makes the slot vanish.**
   `ACTIVE_SLOT_STATUSES` includes `REQUESTED`, `PAYMENT_PENDING` and `PAUSED`, so after submitting,
   the slot is simply absent on the next visit rather than rendering disabled. Testers will read this
   as "my booking didn't save". Backend behaviour is correct; the UX replacement is missing.
2. **`deferred-12` D2 — parent-cancel races the payment settle.** `cancelBookingAsParent` can commit
   `CANCELLED_PARENT` while `PaymentLifecycleService` is mid-capture; the listener's subsequent
   `PAYMENT_CAPTURED` is then an illegal transition, thrown and swallowed. Net: **money captured,
   booking cancelled, no refund, no compensation.** Narrow window, but you will be exercising cancel
   flows with test cards.
3. **`deferred-15` story creation D1 — no durable pre-capture record.** A crash between the Stripe
   capture and the DB write leaves money captured, booking in `PAYMENT_PENDING`, and no
   `booking_payments` row, with no signal that recovery is needed. This is what confines the
   `deferred-15` sweeper to pack-funded bookings. Recovery is manual Stripe-dashboard reconciliation.
   Survivable in test mode; you should know it exists before you trust any payment numbers.
4. **`deferred-17` D4 — `AvailabilityManagerPage.vue:333` still displays `windows[0]`'s timezone**,
   the exact column `deferred-17` AC4 stopped displaying from one page over. `bookingStore.coachTimezone`
   is already populated by a call this page makes and is simply unused. Cheap fix, visible inconsistency.
5. **`deferred-16` D1 — an unknown role string yields 500, not 403.** Latent (the resolver guarantees
   one of three values), but a 500 during UAT costs an investigation.
6. **`deferred-18` D1 — a DST gap can emit a negative-duration slot** that renders clickable and 400s
   behind a generic toast. Needs a ~02:30 Sunday window to trigger; unlikely but free to guard.
7. **`deferred-17` D3 — `formatSlot` hardcodes `'en'`.** Only matters if UAT is not English-only.
   Systemic across 4+ pages; do it as one sweep or not at all.
8. **`deferred-18` D6 — `ApiAdvice` can never resolve a non-English message bundle.**
   `SecurityAdviceFilter:59` stores `locale.getDisplayLanguage()` ("German"), and
   `Locale.forLanguageTag("German")` yields `"german"` — so `messages_de.properties` is never selected
   and every custom validator's translations are dead. Same condition as #7: English-only UAT, defer.

## P2 — Deployment and ops (below every functional blocker, per your instruction)

Good news first: **the deployment path is in better shape than the ledger implies.** The `deploy-*`
sections have never been re-checked by any of the seven audits, and several of their items are stale
(see Ledger hygiene). Specifically, `docker-compose.uat.yml` sets `SPRING_PROFILES_ACTIVE=uat`, the UAT
profile refuses to start with a live Stripe key, MinIO stands in for S3, and CI publishes an image on
every push to `master`. You can deploy today.

What is genuinely worth doing, in order:

1. **`deferred-17` D7 — `docker compose build` silently no-ops.** Neither compose file has a `build:`
   key, so `docker compose build app` does nothing without erroring and you deploy a stale jar. This
   already cost the `deferred-17` dev significant time — it made a shipped fix look broken. Only bites
   local/manual builds (the VPS pulls from GHCR), but it is a ten-minute fix for a failure mode that
   wastes hours.
2. **`deploy-1-5` — `acme.json` lives on the root disk.** A server rebuild loses all TLS certs, and
   Let's Encrypt rate limits make reissuance slow. Worth knowing before you rebuild a UAT box.
3. **`deploy-1-5` — Redis data on a named Docker volume, not the persistent Volume.** Sessions and
   cache are lost on rebuild. Acceptable for UAT; note it.
4. **`deploy-3-1` — no backup retention policy.** S3 dumps and Hetzner snapshots accumulate unbounded.
   Cost, not correctness.
5. **`deploy-2-1` D2 — no stable/`latest` tag alongside the SHA tag.** Every deploy needs the exact
   SHA typed in. Minor friction you will feel on every UAT redeploy.

Everything else under `deploy-*` — the restore-script hardening, alert-rule divide-by-zero guards,
fail2ban tuning, doc gaps in `rollback.md` — is production-hardening for a system that does not yet
have production traffic. Leave it.

## P3 — Chronically re-deferred; do not touch for UAT

These are the items your instinct about "deferred multiple times" points at, and the instinct is
right — every reviewer who looked at them decided the cost exceeded the benefit.

**Examined and explicitly left alone by three or more separate audits:**
- `skillars-10-2` D1 — `AFTER_COMMIT` listener failure silently drops refunds. Left alone by
  `deferred-13`, `-14` and `-15`, each recording the same reason: it is a platform-wide event-reliability
  concern needing an outbox, not a point fix. The correct home is a dedicated resilience epic, post-UAT.
- `skillars-8-1` D2 (N+1 in `getConversations`), D4 (`Instant.EPOCH` sentinel),
  `skillars-10-1 patches` D1/D2, `skillars-8-4` W5, `skillars-8-3` W1 — all carried through the
  `deferred-16` audit with an explicit "do not re-litigate" note.

**Carrying a stale `STILL OPEN` marker after the story that promised the fix already shipped** — i.e.
the fix was promised and quietly skipped, twice over:
- `skillars-5-3` DEF2 (`distinct_coach_count` — Story 5.4 shipped without it)
- `skillars-6-1` Def11 (`bandwidth_used_bytes` never incremented — Story 6.3 shipped without it)
- `skillars-7-2` Group 2 D6 (`BookingDisputedEvent` — Epic 10 shipped; the event type was never created)
- `skillars-7-2` Group 2 D7 (`SessionPackExhaustedEvent.playerId` holds a parentId — Story 7.3 shipped)

None are on your journey. Each is a real defect that nobody has ever thought worth the cost.

**Out of journey entirely** — roughly half the file: the Epic 4 drill-library items, Epic 5 SLU /
skills-radar items, Epic 6 video/quota/moderation items, and the messaging module beyond P1 #5. Your
UAT script touches none of this code.

---

## Ledger hygiene — items I verified as stale today

The ledger's audits have flagged seven times that forward-references go unverified. Four items are
provably out of date, and one should be re-scoped. Cleaning these costs nothing and stops you
budgeting work that is already done.

| Item | Claim | Reality at `a170e69` |
|---|---|---|
| `deferred-10` D1 | `ci.yml` triggers on `branches: [main]` but the repo default is `master`, so the image pipeline may never have auto-triggered | **Closed.** `ci.yml:4` reads `branches: [master]`, and `gh run list` shows 8 consecutive successful `CI` runs on `master`, each publishing to GHCR |
| `deploy-2-1` D1 | No `SPRING_PROFILES_ACTIVE` — the container boots on the base profile with dev defaults | **Closed for UAT.** `docker-compose.uat.yml:4` sets `SPRING_PROFILES_ACTIVE=uat`. Still true for the prod `docker-compose.yml` and the `Dockerfile` `ENTRYPOINT` — re-scope to prod-only |
| `deferred-16` D8 | `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` "currently fails on `master`" | **Not red.** The test is still present and still structurally flaky (it asserts on `latencies[99]`, the max of 100 un-warmed samples), but CI has passed 8 consecutive times. Downgrade from "master is broken" to "flaky perf assertion" |
| `deferred-9` D2 | `booking.*` keys unreachable in production locale — `en-US` has no `booking` block and is 474 lines vs `en`'s 889 | **Premise dead.** `en-US` is now 1167 lines with 178 `booking` keys; `de` is 1191 lines and `fr-FR` 1190, both with `booking` blocks. Re-scope to what is actually left: `de` is still not selectable (`MainLayout.vue:236-239` offers only `en-US` and `fr-FR`), `de` is not renamed `de-DE`, `en` survives as a redundant fourth bundle, and `fr-FR` has 154 booking keys against `en-US`'s 178 |
| `deferred-11` D3 | Kept `subscription.paymentMethodId` because `CoachSubscriptionPage.vue` still uses it | Still accurate — and it is the same root cause as P0-4. Fold this into that story rather than tracking separately |

## Suggested sequence

1. **`uat-bootstrap`** — seed `ROLE_ADMIN`/`ROLE_LTD_ADMIN`, script or document first-admin creation,
   document the UAT test-account set (P0-1). Nothing else can be tested admin-side until this lands.
2. **`booking-session-duration`** — slot slicing or a session-duration field, applied to both the
   single and batch paths, including the missing window check in `BookingBatchService` (P0-5). Needs
   your product call on fixed-length slots versus a duration picker.
3. **Decide P0-2** — scope the player journey to register-and-browse, or commit to player self-booking.
   This is a decision, not a story; it determines whether the messaging `parent_id` item becomes live.
4. **`coach-onboarding-resilience`** — timezone picker or fallback in the profile builder (P0-3), and
   the `windows[0]` display fix (P1 #4) since it is the same code area.
5. **Decide P0-4** — is coach subscription in the UAT script? If yes it is a schema story and should
   start now; if no, paste a test-mode `pm_...` and move on.
6. **`booking-ux-polish`** — the vanishing-slot affordance (P1 #1) plus the DST guard (P1 #6), once
   real testers have hit them and you know which matters.
7. **Payment integrity** (P1 #2, #3) — schedule after the first round of UAT payment testing, when you
   know whether the races actually fire at your volumes.
8. Everything in P2, then never P3 (until a post-UAT resilience epic picks up `skillars-10-2` D1 and
   the outbox question wholesale).
