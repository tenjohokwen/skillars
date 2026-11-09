# Story skillars-deferred-179: Auth token clean-ups (revoke-all stamps revoked_at; one JWT parse on the fast path)

Status: review

<!-- CREATED 2026-11-08 from deferred-work.md: the skillars-deferred-166 residual (`markAllUsedByUserId` sets `used` but not
     `revoked_at`) and the skillars-deferred-167 residual (`extendTtlOfToken` verifies the JWT four times). Owner chose this
     bundle on 2026-11-08 ("yes, go ahead with the auth token clean-ups"). Code read at origin/master 0904935c. -->

## Story

As **a user whose sessions were all ended** (password change or reset, suspension, email change, theft response, GDPR
erasure), I want a replayed old token to be refused as ended, not treated as theft that signs me out again.

As **the platform**, I want the per-request token extension to verify the JWT once.

## Ledger mapping

| Ledger item | Severity | What | AC |
|---|---|---|---|
| skillars-deferred-166 residual | low | `markAllUsedByUserId` sets `used` but not `revoked_at`, so a replay reaches the reuse branch and revokes again | AC1 |
| skillars-deferred-167 residual | low / performance | `extendTtlOfToken` parses and HMAC-verifies the JWT four times per authenticated request | AC2 |

Both entries are deleted from `deferred-work.md` in this PR.

## Background (code at 0904935c)

- `security/repo/RefreshTokenRepository.markAllUsedByUserId` (ten callers): `REQUIRES_NEW` bulk `UPDATE ... SET used = true,
  version = version + 1 WHERE user_id = :userId`. Its sibling `markAllRevokedByUserId(userId, revokedAt)` (deferred-166, for
  `RefreshTokenRevocationService.revokeAllForUser`) also stamps `revoked_at` on rows not yet revoked. `AuthService.refresh`
  reads `used` without `revoked_at` as "rotated, successor unknown" (reuse branch: account-wide revocation, epoch bump, 401
  "Token reuse detected"), and `revoked_at` set as `Revoked` (plain 401).
- `security/infrastructure/jwt/JwtManagerImpl.extendTtlOfToken`, the filter's fast path for every authenticated request,
  calls `ClaimsExtractor.extractPrincipal`, `extractDbRefreshToken`, `extractSessionIssuedAt` and `extractRefreshSessionId`,
  and each re-reads the `potc` cookie and parses and verifies the JWT.

## Decisions (recommended option each)

**D21.** Keep the name `markAllUsedByUserId` (ten callers, 25 test references) and make it a Spring Data default method that
delegates to `markAllRevokedByUserId(userId, now)`: same `REQUIRES_NEW` propagation and `version` bump (both on the delegate),
rows already revoked keep their earlier `revoked_at`. A rename would touch every caller for no behaviour change.

**D22.** Add claims-based readers to `ClaimsExtractor` (`principalFrom`, `dbRefreshTokenFrom`, `sessionIssuedAtFrom`,
`refreshSessionIdFrom`); the request-based readers parse and delegate; `extendTtlOfToken` reads the cookie once, parses once,
and reads all four claims from that result. Error behaviour unchanged (a blank cookie is a missing principal; a bad token
throws from the one parse, as from the first of the four before).

## Acceptance Criteria

1. **AC1** After `markAllUsedByUserId`, every row of the user is `used` with `revoked_at` set; an earlier `revoked_at` is kept;
   the write commits even when the caller's transaction rolls back.
2. **AC2** `extendTtlOfToken` calls `extractClaims` once and carries the principal, db-refresh-token, session-issued-at and
   refresh-session-id claims forward unchanged.
3. **AC3** Javadocs that described the old asymmetry, the security dev doc, and the ledger are updated.

## Tasks / Subtasks

- [x] T1 (AC1) default method; `RefreshTokenRepositoryIT` test.
- [x] T2 (AC2) claims-based readers, single parse; `JwtManagerImplTest` spy test.
- [x] T3 (AC3) docs and ledger.

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### File List

- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/ClaimsExtractor.java`, `ClaimsExtractorImpl.java`, `JwtManagerImpl.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` (javadoc)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` (javadoc)
- `src/test/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepositoryIT.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java`
- `docs/dev-docs/security/index.html`, `docs/dev-docs/assets/search-index.js`
- `_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-11-08: created from the two residuals; D21/D22; implemented T1-T3.
- 2026-11-08: `/pr-review` round 1 (6e44b6d6): security clean; one general finding refuted (a successor surviving a
  concurrent bulk revoke is caught by the session epoch every revoke-all caller stamps right after it, including both
  `EmailChangeService` paths; `GdprErasureService` locks the account). Status -> review; PR opened.
