# Story skillars-deferred-180: Pre-launch hardening (JWT chain check fails closed; Idempotency-Key required; refresh rate-limited)

Status: in-progress

<!-- CREATED 2026-11-08 from the owner's decisions on the deferred list (one-by-one session, 2026-11-08):
     decision 1 (A), 4 (A), 5 (A), 6 (A), 7 (A), 8 (B). Code read at origin/master f0fe8e7d. -->

## Story

As **the platform before go-live**, I want the session-chain check to refuse a JWT it cannot tie to a chain, every pack
purchase to carry a client idempotency key, and the two open token endpoints to be rate-limited, so that a copied cookie
jar cannot keep a session alive forever, a client retry cannot buy a second pack, and one account cannot exhaust the
connection pool through one refresh-token row on refresh.

## Ledger mapping

| Ledger item | Owner decision 2026-11-08 | AC |
|---|---|---|
| skillars-deferred-167 fail-closed flip (option a, "pending a date") | flip now: prod is not live, so no real claimless holders | AC1 |
| RS06c `Idempotency-Key` optional | require it (400 `payment.idempotencyKeyRequired`) | AC2 |
| skillars-deferred-166 residual: unbounded lock wait on refresh/logout | per-IP `@RateLimited` on both; `SET LOCAL lock_timeout` stays ledgered | AC3 |
| RS06d partial refund | the pack stays whole (partial = goodwill, full = revoke); docs only | AC4 |
| RS05b invoice events unparked | accepted, closed | AC4 |
| skillars-deferred-167 MAX_GRACE_HOPS residual | stays ledgered; revisit before any client calls `POST /api/auth/refresh` (ledger + provisioning checklist) | AC4 |

## Background (code at f0fe8e7d)

- `JWTAuthorizationFilter.assertSessionChainStillLive` returns early on a `null` chain id (AC5 of 167). The id is the JWT's
  `rtsid` claim, or, when absent, the one the request's own `rtkn` cookie names (`resolveRefreshSessionId`, owner-scoped).
  Both JWT mint paths (`AuthService.login` and `AuthService.refresh`) always carry an id: login mints one, and a rotation of a
  pre-V225 row mints one for the successor (`AuthService` ~:416). So after this story only these are refused: a JWT minted
  before V225 whose chain never rotated, and any claimless JWT sent without its `rtkn` (the thief shape). The check runs only
  on the DB-reauth branch (every `DB_REFRESH_TOKEN_INTERVAL`, 5 min), so the exposure bound becomes 5 minutes.
- The binding in `TokenValidatorImpl.isTokenFixed` (UA hash, `bcookie`/`fcookie`, `ION`) compares client-sent values only,
  so it does not stop a whole-jar copy; it is unchanged.
- `SessionPackPaymentResource.purchaseSessionPack` takes `Idempotency-Key` with `required = false`; the service mints a key
  when null. The SPA always sends one (`SessionPackPurchasePage.vue` `attemptKey ??= newAttemptKey()`).
- `AuthResource` `POST /api/auth/refresh` and `/logout` are `permitAll`, not rate-limited; both lock the refresh-token row
  `PESSIMISTIC_WRITE` with no wait bound. Pool size 25.

## Decisions

**D23.** The key requirement is enforced at the API boundary (the only production caller of `purchasePack`). The service's
4-argument overload, used only by tests, keeps minting a key; a missing or blank header answers 400 with
`payment.idempotencyKeyRequired` (checked in the resource so the error code is ours, not Spring's generic
`MissingRequestHeaderException`). Format validation stays in the service.

**D24.** `@RateLimited(key = "auth_refresh", capacity = 20, duration = 1 minute)`, per IP like every other bucket (per JVM).
The SPA never calls refresh, so only API clients meet it. **Logout is deliberately not limited** (review round 1, both
arms): a per-IP bucket on an unauthenticated endpoint can be drained by anyone behind the same NAT, and a refused logout
revokes nothing and clears no HttpOnly cookie while the SPA reports success. Its lock queue is bounded by the unlocked
already-revoked pre-check.

## Acceptance Criteria

1. **AC1** On the DB-reauth branch, a JWT whose chain id cannot be resolved (no claim and no owner-matching `rtkn`) is refused
   with the same 401 as a dead chain; a claimless JWT sent with its own live `rtkn` is still repaired and passes.
2. **AC2** `POST /api/payment/session-packs/purchase` without `Idempotency-Key` (or blank) answers 400
   `payment.idempotencyKeyRequired` and never reaches the service.
3. **AC3** `POST /api/auth/refresh` carries `@RateLimited` (20/min per IP); `/logout` deliberately does not.
4. **AC4** Javadocs, dev docs, the payments/security runbook text, the provisioning checklist and `deferred-work.md` reflect
   the decisions above; the closed items are deleted from the ledger.

## Tasks / Subtasks

- [x] T1 (AC1) invert the early return; javadoc; `JWTAuthorizationFilterTest` + `AuthResourceIT` assertions.
- [x] T2 (AC2) resource check; WebMvc tests.
- [x] T3 (AC3) annotations; annotation test.
- [x] T4 (AC4) docs, checklist, ledger, sprint status.

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### File List

- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`
- `src/main/java/com/softropic/skillars/platform/security/api/AuthResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- javadoc only: `ClaimsExtractorImpl.java`, `RefreshToken.java`, `RefreshTokenRepository.java`, `LoginTokenManager.java`,
  `RefreshTokenRevocationService.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java`
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceRateLimitTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceWebMvcTest.java`
- `docs/session-refresh-mechanism.md`, `docs/security-api-endpoints.md`, `docs/deployment/provisioning-checklist.md`
- `docs/dev-docs/security/index.html`, `docs/dev-docs/payment/index.html`, `docs/dev-docs/assets/search-index.js`
- `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-11-08: /pr-review round 1 (both arms, medium): the logout rate limit let anyone on a shared IP block others'
  logouts; removed, refresh keeps it.
- 2026-11-08: created from the owner's decisions; D23/D24; implemented T1-T4. The unannotated Spring `POST /api/logout`
  reaches the same lock only until the first revoke commits (unlocked pre-check); recorded in the ledger, not changed.
