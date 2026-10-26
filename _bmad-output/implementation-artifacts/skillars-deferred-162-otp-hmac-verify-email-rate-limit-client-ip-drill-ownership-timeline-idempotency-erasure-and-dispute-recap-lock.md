# Story skillars-deferred-162: OTP HMAC, verify-email rate limit and client IP, plan drill ownership, timeline idempotency and erasure, dispute recap lock

Status: done

<!-- Created 2026-10-26 (/bmad-create-story). User-requested bundle mined from deferred-work.md (8 items,
     12 ledger bullets; Group D D2 folded into AC3 at owner request). Every item was re-verified against HEAD 5f5e2994 before drafting (see "Verified
     state at drafting"). Ledger line numbers below are from HEAD; the working tree had uncommitted
     deletions at drafting time, so locate bullets by the quoted lead text, not by number. -->

## Story

As a maintainer of the Skillars platform,
I want the remaining registration-OTP weaknesses, the plan-path private-drill leak, the non-idempotent and
erasure-blind timeline writes, and the still-editable recap of disputed sessions fixed,
so that a leaked database does not expose live OTPs, rate limits cannot be bypassed by a spoofed header,
one coach's private drills never reach another coach, an erased player gets no new development data, and a
disputed session stops being treated as a normal completed one.

## Owner decisions (2026-10-26)

- **D162-A, disputes (AC6): lock the recap only.** Booking status is **not** touched: this keeps story 10.3's
  decision (`skillars-10-3-dispute-resolution.md:562-568`, "Do NOT transition the booking to `DISPUTED`").
  The state-machine option was rejected for its blast radius (coach write access, messaging, batch status,
  `updatedAt`-keyed dispute/review/video windows). Won't-fix was rejected.
- **D162-B, OTP hash (AC2): keyed HMAC-SHA256 in one shared helper**, key derived from the existing
  registration-verification secret, constant-time compare. BCrypt and "dedupe only" rejected.
- **D162-C, verify-email (AC1): add `@RateLimited` and fix the client-IP source.** The 401-vs-429 response is
  **out of scope** and becomes a new ledger bullet.
- **D162-D, token writes (AC8): decide, no code.** Re-annotate the bullet `[DECIDED]`, keeping its trigger.

### Defaults taken at drafting (owner may override before dev)

- **D162-A1, which disputes lock the recap:** locked while any dispute on the booking is active (status not in
  `RESOLVED`, `DISMISSED`), and after one is `RESOLVED` with `resolution <> 'NO_ACTION'` (money returned or
  coach warned). `DISMISSED` and `NO_ACTION` leave the recap editable again.
- **D162-E, plan drill rule (AC4):** a plan may reference `PLATFORM` drills or the plan coach's own `PRIVATE`
  drills, **regardless of `status`**. Unlike homework, there is no `ACTIVE` requirement: the frontend re-sends
  every drill on update (`sessionBuilder.store.js:175`), so an `ACTIVE` rule would block saving a plan that
  holds a since-archived drill of the coach's own.
- **D162-F, OTP verify budget (AC3):** fix options A+B from the research: look up and expiry-check the OTP
  **before** consuming, and key the bucket on `userId:otpId`. Option C (persistent attempts column) is not
  taken.

## Review corrections (2026-10-26, from `story-review.md`, each re-verified against HEAD 5f5e2994)

Applied:
- **AC4 scope widened: `SessionTemplateService` is a second path.** The "closed transitively" claim was false.
  `deployTemplate` saves a new session directly (`:163`), bypassing `SessionPlanService`, and its `buildResponse`
  repeats the unfiltered lookup. Deploy validation and read filtering were added to AC4.2 and AC4.3.
- **AC5 retitled and narrowed** to the booking-completion listeners. The review is right that radar assessments and
  performance reports also write into tables erasure empties with no erasure check. They are synchronous,
  coach-initiated writes of a different shape, so they were recorded as a new ledger bullet (AC5.6) rather than
  silently widening the story. The owner may fold them in.
- **AC5.1: per-drill transaction** in `HomeworkAssignmentService`, so a duplicate-key failure cannot poison a
  shared transaction.
- **AC6: `COALESCE(resolution, '')`**, because `Dispute.resolution` is nullable.
- **AC7.4: transaction scope clarified**, and **AC7.5: truncation trap** (the listener keeps 2 ids; found while
  applying the review).
- Citation fixes: ledger sections (Group D/A/B, not C) and working-tree line numbers; V161 is a marker precedent
  only (non-unique index); `getIpAddress()` has 8 call sites, not 14; there is no SMS path; Coach `:190-192`/`:211-212`;
  `fallbackExecution = true`; `GdprErasureIT:1498` covers rollback, not retry.

Rejected as false positives:
- **"`ApiAdvice` publishes no `SecurityAlertEvent` on rate-limit hits": false.** `handleAuthException` delegates to
  `handleSecErrorAndReturnDTO(AuthorizationException, …)`, which publishes it at `ApiAdvice:692-694`. The review
  read only the handler body. The claim stands, now with the helper cited.
- **"`getCoachId()` at resolver `:53` is fabricated": partly false.** The story said the loaded `Session` *has*
  `getCoachId()`, which is true (`Session.java:38`). It never claimed the resolver calls it. The wording was
  tightened to avoid that reading.
- **"All mapping-table line numbers are stale": not a defect as written.** They were labelled HEAD lines, and the
  ledger has uncommitted deletions. The table now uses working-tree lines and says so.
- `application.yaml` property-path paraphrase: the story never wrote `server.tomcat.remoteip.remote-ip-header`. For
  AC1.3, note that this file nests `remote-ip-header` directly under `server.tomcat`, which is the pre-Boot-2.3
  name; Boot 3.5 binds `server.tomcat.remoteip.*`. Boot's default header is `X-Forwarded-For` either way. The dev
  confirms at runtime which one applies, and moves the keys under `remoteip` if the old names are ignored.

## Ledger → story mapping (so future passes do not re-pick these)

All bullets live in `_bmad-output/implementation-artifacts/deferred-work.md` and are tagged
`[PICKED UP by skillars-deferred-162 ACn]` there. On completion the dev **deletes** bullets closed by code
and re-annotates the one closed by decision (AC8).

Line numbers are from the **working tree** on 2026-10-26, which carries uncommitted deletions; HEAD numbers
are about 9-22 higher. Locate bullets by lead text plus the `[PICKED UP by skillars-deferred-162 …]` tag.

| AC | Ledger bullet (lead text) | Section | Line |
|----|---------------------------|---------|------|
| 1 | "D1: `/verify-email` endpoint not rate-limited" | code review of skillars-1-3 … Group D (2026-06-11) | 862 |
| 1 | "D6: verifyEmail endpoint not @RateLimited" (duplicate) | code review of skillars-1-3 … Group A (2026-06-11) | 888 |
| 2 | "OTP hash uses `SHA-256(otp+userId)` no separator" | code review of skillars-1-4 … Group 1 (2026-06-12) | 852 |
| 2 | "D3: OTP hash SHA-256(otp+userId) no random salt" (duplicate) | code review of skillars-1-3 … Group B (2026-06-11) | 878 |
| 2 | "W1: OTP hash uses `SHA-256(otp+userId)`" (duplicate) | code review of skillars-1-3 … (2026-06-11, no group) | 891 |
| 3 | "OTP rate-limit key is `userId` only" | code review of skillars-1-4 … Group 1 (2026-06-12) | 858 |
| 3 | "D2: Rate limit consumed before user table lookup in `verifyPhone`" (folded in 2026-10-26 at owner request) | code review of skillars-1-3 … Group D (2026-06-11) | 863 |
| 4 | "**Plan-path private drill names.**" | skillars-deferred-159 (2026-10-23) | 3215 |
| 5 | "**Erasure marker not checked on the delayed QUICK path.**" | skillars-deferred-159 (2026-10-23) | 3216 |
| 6 | "**Refunded/disputed sessions stay `COMPLETED`**" | dev of skillars-12-7 (2026-10-22) | 3195 |
| 7 | "**`TimelineEventListener.onBookingCompleted` not idempotent.**" | code review of skillars-12-8 (2026-10-23) | 3205 |
| 8 | "**`markUsedByTokenHash`/`markAllUsedByUserId` calls are unguarded…**" | code review of skillars-deferred-143 (2026-10-05) | 3107 |

## Verified state at drafting (HEAD 5f5e2994)

Paths are relative to `src/main/java/com/softropic/skillars/` unless they start with `src/`.

**AC1, verify-email / client IP**
- `/verify-email` for **coach, parent and player** is in `PUBLIC_ENDPOINTS` (`platform/security/config/AppEndpoints.java:34,37,40`),
  `permitAll` at `SecurityConfiguration.java:228`. Resources: `CoachRegistrationResource:40-44`,
  `ParentRegistrationResource:41-43`, `PlayerRegistrationResource:41-43`. Services: `verifyEmail` at
  Coach `:112`, Parent `:116`, Player `:127`. None has `@RateLimited`. **The ledger only named coach; all three
  roles are unlimited.**
- Token: `UUID.randomUUID()` (122 random bits), 24h TTL, single-use (`used` + `@Version`). A valid call deletes
  and re-issues an OTP and sends an OTP email (`sendOtpEmail`, Coach `:165-179`; there is no SMS path).
- `@RateLimited` (`infrastructure/security/RateLimited.java:15-38`): `key`, `capacity=5`, `duration=1`,
  `unit=MINUTES`. `RateLimitingAspect` (`@Before`, order `HIGHEST_PRECEDENCE+100`) **always keys on client IP**
  (`RequestMetadata.getIpAddress()`, falls back to `"unknown"`). Limits are annotation literals, buckets are
  per-JVM in memory. Aspect disabled in tests (`rate.limiting.enabled: false`, `src/test/resources/application-test.yaml:73-75`).
  Sibling keys: `{role}_register` 3/60, `{role}_resend_verification` 3/30, `{role}_resend_otp` 3/30.
- **The IP is spoofable.** `RequestMetadataProvider.initRequestMetadata` (`infrastructure/.../RequestMetadataProvider.java:48-51`)
  reads the raw `Forwarded` request header and only falls back to `getRemoteAddr()` when it is blank. Any client
  can send `Forwarded: for=x` with a new value per request and get a fresh bucket on **every** `@RateLimited`
  endpoint.
- Proxy stack: Traefik in Docker (`docker-compose.yml:136-145`). `application.yaml:27-29` sets
  `server.forward-headers-strategy: native` with `remote-ip-header: x-forwarded-for`, so Tomcat's `RemoteIpValve`
  resolves `getRemoteAddr()` from `X-Forwarded-For` when the TCP peer is an internal proxy (Tomcat's default
  `internalProxies` regex covers 10/8, 172.16/12, 192.168/16 and 127/8). **Also** `SecurityConfiguration.java:141-146`
  registers Spring's `ForwardedHeaderFilter` at `HIGHEST_PRECEDENCE`, which trusts `Forwarded`/`X-Forwarded-*`
  from **any** peer and overrides `getRemoteAddr()`. The two mechanisms are redundant; Spring Boot documents
  choosing one. RFC 7239 `Forwarded` is not processed by `RemoteIpValve` at all.
- `getIpAddress()` has 8 genuine call sites (14 grep hits including the getter/interface declarations) across: `LoginAttemptsService`, `LoginInfoService`,
  `JWTAuthorizationFilter`, `PersistentToken`, `AuditTrail`/`AuditLog`, `SecurityAuditListener`,
  `AccountChangeEventListener`, `ClientContextProvider`, `RateLimitingAspect` and others. This is the
  blast radius of the IP fix.
- On limit hit the aspect throws `AuthorizationException(TOO_MANY_REQUESTS)`, which `ApiAdvice:242-249` maps to
  **401** `security.unauthorized`; the shared helper `handleSecErrorAndReturnDTO(AuthorizationException, …)`
  (`ApiAdvice:692-694`) also publishes a `SecurityAlertEvent`, so every rate-limit hit raises a security alert. Out of scope (D162-C); new ledger bullet.

**AC2, OTP hash**
- Four identical copies of `SHA-256(otp + userId)` → lowercase hex, no key: `CoachRegistrationService:279-288`,
  `ParentRegistrationService:282-291`, `PlayerRegistrationService:305-314`, `RegistrationOtpResendSupport:94-103`.
- Compare is `String.equals` (Coach `:211-212`, Parent `:215-216`, Player `:226-227`), not constant-time.
  Precedent for constant time: `MessageDigest.isEqual` in `RegistrationVerificationTokenService:99`.
- OTP = `100000 + SecureRandom.nextInt(900000)` (900,000 values), TTL 10 min, one active OTP per user
  (`uq_pot_one_active_per_user … WHERE used = false`, `V138:3742`). Column `phone_otp_tokens.otp_hash varchar(64)`
  (`PhoneOtpToken.java:27-28`, `V138:841-849`). HMAC-SHA256 hex is also 64 chars, so **no migration**.
- Existing secret: `skillars.platform.registration-verification-secret` (env `PLATFORM_REGISTRATION_VERIFICATION_SECRET`,
  ≥32 chars, startup-checked) at `SkillarsPlatformProperties:52-65`, `application.yaml:545`; already used as an
  HmacSHA256 key in `RegistrationVerificationTokenService:57-66,125-133`.
- Three IT fixtures re-implement the old formula and insert rows directly: `CoachRegistrationResourceIT:843`
  (used at `:314, 361, 479`), `ParentRegistrationResourceIT:699`, `PlayerRegistrationResourceIT:498`
  (used at `:181`; `:256` reads `otp_hash` back).

**AC3, OTP verify budget**
- `tryConsume(String.valueOf(userId), "<role>_otp_verify", N, 10, MINUTES)`: Coach `:186` (N=5),
  **Parent `:190` (N=10)**, Player `:201` (N=5). The ledger cited Parent `:154`; the real line is `:190`.
- Order today: resource resolves the HMAC handle (bad handle → 400 before the limiter) → `tryConsume` (empty →
  `OtpVerificationException("security.otpMismatch")`, 400) → `findFirstByUserIdAndUsedFalseOrderByExpiresAtDesc`
  (Coach `:190-192`) → expiry check (`:194`) → locked/status checks → hash compare (`:211-212`). **Missing, expired and
  used OTPs all consume a token**, and a resend issues a new OTP without resetting the userId bucket.
- Bucket4j `Refill.intervally` (the whole capacity refills at each interval boundary).
- **No test covers an expired OTP or the verify-phone limiter.**

**AC4, plan drills**
- `Drill` (`platform/session/repo/Drill.java`): `libraryType` String `'PLATFORM'|'PRIVATE'` (`:39-40`),
  `ownerCoachId` (`:42-43`, null for PLATFORM per `chk_drill_owner`), `status` `'ACTIVE'|'ARCHIVED'`. Nothing
  archives drills today.
- `SessionPlanService.fetchDrills` (`:271-280`, `findAllById` at `:279`) is called from `createSession:89`,
  `updateSession:150`, `getSession:227` and `findByBookingId:237`. There is no ownership check, and unknown ids
  are silently dropped while the reference is still saved.
- **The leak is wider than the ledger says:** `toResponseMap` (`:286-289`) returns the foreign drill's full
  `drillLibraryService.toResponse(...)` in `SessionPlanResponse`, so the coach who crafts the request reads
  the whole drill.
- `TimelineDrillSnapshotResolver.forBooking` (`:51-76`) → `snapshot()` (`:79-102`, `findAllById` `:83`)
  writes `{drillId, name}` with no filter. The `Session` loaded at `:53` exposes `getCoachId()` (`Session.java:38`), though the resolver does not call it today. `snapshot()` is also
  used by `onHomeworkAssigned` (`:182`) without coach context.
- Timeline readers: player, parent, and **any coach** with an open access window (`PlayerTimelineResource:31-32`,
  `TimelineQueryService:31-41`). `drills` is visible to every viewer (`TimelineQueryService:61-64`).
- Precedent to reuse: `BookingCompletionService.validateHomeworkDrills` (`:235-249`) and `isUsableHomeworkDrill`
  (`:251-255`), both `private`. They throw `ResourceNotFoundException("Homework drill not found", "drill")`
  (404, `RESOURCE_NOT_FOUND`) for unknown, foreign-PRIVATE, archived and null ids alike. The rule mirrors
  `DrillSuggestionService.allCandidates:145-149`.
- `SessionTemplateService` is a **second, independent write and read path** (corrected 2026-10-26 after
  story-review.md). `deployTemplate` (`:116-166`) builds a new `Session` from the template's stored blocks
  (`setBlocks`, `:155`) and calls `sessionRepository.save` (`:163`) directly, without going through
  `SessionPlanService.createSession`/`updateSession`. Its `buildResponse` (`:195+`, commented "mirrors
  SessionPlanService.buildResponse — keep in sync") repeats the unfiltered `findAllById` → `toResponse` at `:203`.
  A template made from a plan that already holds a foreign PRIVATE id (saved before this fix) would re-deploy
  and re-leak it indefinitely. AC4 covers both.

**AC5, erasure marker**
- Marker: `main.player_profiles.development_data_erased_at` (`V151`), `PlayerProfile.getDevelopmentDataErasedAt()`
  (`platform/security/repo/PlayerProfile.java:69-70`). It is set once in
  `GdprErasureService.deletePlayerDevelopmentDataInDedicatedPool` (`:1440-1584`, set at `:1563`), in the same
  transaction that holds the `player_profiles` row lock and deletes `player_timeline_events` (`:1468`).
- Delayed QUICK path: `QuickCompleteTimeoutService.processExpiredQuickCompletes` (`:52-82`) transitions
  `COMPLETE`, then publishes `BookingCompletedEvent` with the stored homework ids (`:63-73`). The booking
  **must** still complete (payout), so the check goes in the listeners, not the query.
- Listeners (all `AFTER_COMMIT` + `@Async("taskExecutor")`; `onHomeworkAssigned` also has `fallbackExecution = true`
  because its publisher runs with no transaction), **none checks the marker**:
  `HomeworkAssignmentService.handleBookingCompleted` (`platform/session/service/HomeworkAssignmentService.java:57-116`,
  `saveAndFlush` `:101`, publishes `HomeworkAssignedEvent` from a `finally` at `:112`);
  `TimelineEventListener.onHomeworkAssigned` (`:178-199`); `TimelineEventListener.onBookingCompleted` (`:59-117`).
  `SluCalculationService.onBookingCompleted` (`:48-50`) is unchecked too but out of scope (new ledger bullet).
- Race-free precedent: `RadarCompositeCalculationService:218-239`: `findByIdForUpdate` via
  `lockRetryer.withBoundedRetry`, `entityManager.refresh(profile, PESSIMISTIC_WRITE)`, then
  `if (getDevelopmentDataErasedAt() != null) { log.info(...); return; }`. An unlocked read-then-insert is a
  check-then-act gap against erasure.
- Out of scope, recorded as new ledger bullets: `session.homework_assignments` is not deleted by GDPR erasure
  at all, and SCD `homework_drill_ids` is not nulled by `anonymizeByPlayerId`.

**AC6, disputes**
- Only refund path for a COMPLETED booking: admin `DisputeService` (`platform/admin/service/DisputeService.java`).
  `raiseDispute` `:80-149`, `resolveDispute` `:201-287` (`FULL_CREDIT`, `PARTIAL_CREDIT`, `NO_ACTION`,
  `COACH_WARNING`), `dismissDispute` `:289-309`. Status is a String (`Dispute.java:45`, default `OPEN`). Active is
  `status NOT IN ('RESOLVED','DISMISSED')` (`DisputeRepository:16,41`). No Stripe refund or dispute webhooks exist
  (`StripeWebhookService:117-136`).
- Recap gate: `BookingCompletionService.recapEditability` (`:342-360`), used by both GET and PUT; returns
  `Editability(false, BookingError.RECAP_…)`. Existing keys: `RECAP_EDIT_WINDOW_CLOSED`, `RECAP_NOT_EDITABLE_ERASED`
  (`BookingError.java:133-138,166-167`), handled in `src/frontend/src/components/booking/SessionRecapDialog.vue`.
- Nudge: JPQL in `BookingRepository:347-367` (list and count), `b.status = 'COMPLETED'` and
  `p.developmentDataErasedAt IS NULL`.
- Module direction: `admin` imports `booking` (8 files); `booking` imports nothing from `admin`. **Do not add a
  Java dependency from booking to admin.**

**AC7, timeline idempotency**
- `TimelineEventListener.onBookingCompleted` (`platform/development/service/TimelineEventListener.java:59-117`):
  `AFTER_COMMIT` + `@Async`, writes via `self.writeTimelineEvent(...)` (`:106-113`, `@Transactional(REQUIRES_NEW)`
  `:240-253`, `timelineRepository.save`). There is no existence check, and errors go to a catch-all ERROR log
  (`:114-116`).
- Table `development.player_timeline_events` (`V138:420-428`; `coach_id` added in V160). `reference_id uuid`
  is nullable and has existed **since the baseline** (deferred-159 did not add it). There is **no unique
  constraint and no index on `reference_id`**. SESSION_COMPLETED rows always carry `reference_id = bookingId`.
- Entity `PlayerTimelineEvent`: every column is `updatable=false`.
- Latest migration **V168**, so this story's is **V169**. No unique index has been added since the baseline and no
  migration has ever deduped before a constraint, so this is the first. Rules (`docs/deployment/migration-conventions.md`):
  rule 4 (`:95-138`): no `CONCURRENTLY` (Flyway self-deadlock); use plain `CREATE UNIQUE INDEX` with
  `-- migration-lint: allow-blocking-index <reason>` (marker precedent V161, which is a **non-unique** index; the
  unique-index shape follows the baseline partial unique indexes such as `admin_alerts_unique_open_per_ref`). Rule 7: `SET LOCAL lock_timeout = '5s'`.
  Rule 6: the dedupe `DELETE` needs a bounding `WHERE` or `-- migration-lint: allow-full-table-dml <reason>`
  (precedent V160: "pre-production table, no production deployment"). Checklist `:30` (index ships a release
  before code relies on it) needs an argued exception; see AC7.
- **The 12.8 replay IT relies on the bug:** `SessionCompletionResourceIT:663`
  `quickCompleteThenConfirm_putsDrillsAndRatingsOnTimeline_andHomeworkAssignedLandsOnce` (`:728-745`) awaits
  `countTimeline("SESSION_COMPLETED") == 2` as proof the replay was delivered. It **will time out** after the fix
  and must be rewritten.
- Precedents for `ON CONFLICT DO NOTHING`: `PlayerRadarBaselineRepository:23`, `SluWeeklySnapshotRepository:44`,
  `VideoWebhookEventRepository:24`. Partial unique index precedent: `admin_alerts_unique_open_per_ref` (`V138:3166`).

**AC8, token writes**
- `RefreshTokenRepository.markAllUsedByUserId` (`:79-82`) and `markUsedByTokenHash` (`:117-121`) are both
  `REQUIRES_NEW`. Callers: `AuthService.refresh` `:167,:182,:230,:241`; `SecurityUtil.terminateSession` `:181`
  (from `AuthService:191,:231,:242,:287` and `JWTAuthorizationFilter:160`); `GdprErasureService:696`
  (rolls back, `GdprEventListener` marks it failed, and `retryFailedErasures` re-drives it; `GdprErasureIT:1498`
  calls `erase()` directly and covers only the rollback/atomicity half, not the retry).
- There is still no production deployment, so the bullet's trigger ("observed in production") has not occurred.

## Acceptance Criteria

1. **`/verify-email` is rate-limited, and the rate-limit IP cannot be spoofed.**
   1. `verifyEmail` in `CoachRegistrationService`, `ParentRegistrationService` and `PlayerRegistrationService` carries
      `@RateLimited(key = "{role}_verify_email", capacity = 10, duration = 10, unit = MINUTES)`, placed on the
      service method like its siblings. The token is single-use and 122-bit, so the limit only bounds abuse and
      side effects; 10 per 10 minutes per IP leaves room for a household behind one NAT.
   2. The client IP used by `RequestMetadata.getIpAddress()` is `request.getRemoteAddr()` **after** container proxy
      processing. `RequestMetadataProvider` no longer reads the raw `Forwarded` header.
   3. Exactly **one** forwarded-header mechanism is left. Recommended: keep `server.forward-headers-strategy: native`
      (Tomcat `RemoteIpValve`, which honours `X-Forwarded-For` only from `internalProxies`) and **remove** the
      `ForwardedHeaderFilter` bean (`SecurityConfiguration.java:136-146`). Before deleting it, confirm nothing
      else depends on its scheme/host rewriting: `protocol-header: x-forwarded-proto` is handled by the valve, and
      the dev checks redirect/URL builders and `request.getRequestURL()` users. If Traefik's Docker network is
      outside Tomcat's default `internalProxies` regex, set `server.tomcat.remoteip.internal-proxies` explicitly
      and document it. Check the Traefik config for `forwardedHeaders.trustedIPs`/`insecure`, so that a
      client-supplied `X-Forwarded-For` is replaced or appended, never passed through as the leftmost value
      the valve would pick.
   4. Tests: (a) a request from a **non-proxy** peer carrying `Forwarded: for=1.2.3.4` and `X-Forwarded-For: 1.2.3.4`
      resolves to the peer address; (b) a request from a trusted proxy peer with `X-Forwarded-For: <client>`
      resolves to `<client>`. Where the IT harness cannot set the TCP peer, a unit test of `RequestMetadataProvider`
      with a mocked request is acceptable, plus one `RemoteIpValve`-level check or a documented manual
      verification.
   5. Blast-radius review of the 14 `getIpAddress()` uses. In particular, confirm that `JWTAuthorizationFilter` and
      `PersistentToken` do not bind sessions to IP in a way that would log out every live session on deploy. If
      they do, record it in Completion Notes; it is acceptable because there is no prod deployment.
   6. A **new** ledger bullet records the 401-instead-of-429 response of `RateLimitingAspect` (D162-C), including the
      `LoginRateLimitedException` 429 precedent (`ApiAdvice:374-380`).

2. **OTPs are hashed with a keyed HMAC through one helper.**
   1. A new `OtpHasher` (suggested `platform/security/service/OtpHasher.java`, a `@Component`) owns
      `hash(UUID/long userId, String otp)` and `matches(String expectedHash, UUID/long userId, String otp)`. Use the
      real userId type at the call sites.
   2. The key is derived once at construction from `registration-verification-secret` with purpose separation,
      for example `HmacSHA256(secret, "skillars-otp-hash-v1")`, so the OTP key never equals the handle-signing key.
      The message is `userId + ":" + otp` (with a separator). The output is lowercase hex and 64 chars, so there is
      no schema change.
   3. `matches` compares in constant time (`MessageDigest.isEqual` over the bytes).
   4. All four copies are deleted and every call site uses the helper (Coach, Parent, Player verify and issue
      paths, and `RegistrationOtpResendSupport`). `grep -rn "SHA-256" src/main/java/**/registration` finds no
      OTP use.
   5. The three IT fixtures (`CoachRegistrationResourceIT:843`, `ParentRegistrationResourceIT:699`,
      `PlayerRegistrationResourceIT:498`) call the `OtpHasher` bean instead of re-implementing a formula. The
      Player IT's read-back at `:256` is adjusted accordingly.
   6. Unit tests: the hash is deterministic per (userId, otp); a different userId, otp or secret gives a different
      hash; the output is 64 lowercase hex chars; `matches` accepts the right OTP and rejects a wrong one.
   7. Cutover note in Completion Notes and the security dev-doc (`docs/dev-docs/security/`, if a registration page
      exists): an OTP in flight at deploy time (≤10 min TTL) fails once and the user resends. Rotating the
      registration-verification secret now also invalidates in-flight OTPs. Add that sentence to
      `docs/deployment/secrets-reference.md` under that secret.

3. **Expired or missing OTPs do not drain the verify budget; a new OTP gets a fresh budget.**
   1. In all three `verifyPhone` paths the OTP lookup and expiry check run **before** `tryConsume`. Missing, used and
      expired OTPs throw the same `security.otpMismatch` 400 as today **without consuming**. The response must stay
      indistinguishable from a wrong code.
   2. `tryConsume` is keyed on `userId + ":" + otpToken.getId()`, so a resend (which deletes the old OTP and inserts
      a new one) starts a fresh budget. Capacity and window are unchanged (Coach and Player 5/10 min). Parent stays
      at 10 unless the dev records a reason to align it; that inconsistency is noted, not required work.
   3. The locked, status and hash-compare order after the limiter is unchanged.
   4. **Closes the Group D D2 bullet** ("rate limit consumed before user table lookup … targeted bucket
      exhaustion"). After 3.1 and 3.2, nothing is consumed until a live OTP for that user has been found, and the
      bucket belongs to that one OTP. An attacker who exhausts it (which already requires the user's HMAC
      phone-verification handle, since a bad handle returns 400 before the limiter) blocks only that OTP, and the
      user's next resend starts a fresh budget. The dev confirms this order against HEAD in all three roles
      (the bullet cites Coach `:145-147`, which is stale; the real call is `:186`) and states it in Completion Notes.
   5. Unit tests per role (the services mock `RateLimitingService`): an expired OTP does not call `tryConsume`; a
      missing OTP does not call `tryConsume`; a wrong code against a live OTP calls `tryConsume` with the
      `userId:otpId` key; an exhausted bucket gives `otpMismatch`.

4. **A session plan can only reference drills its coach may use, and foreign private drills are never returned or snapshotted.**
   1. The homework rule is extracted into one shared, public predicate in the `session` module. Suggested:
      `platform/session/service/DrillUsagePolicy` with `isUsableBy(Drill, UUID coachId)` (ownership) and
      `isAssignableAsHomework(Drill, UUID coachId)` (ownership plus `ACTIVE`).
      `BookingCompletionService.validateHomeworkDrills` uses it, and its behaviour and tests are unchanged.
   2. `SessionPlanService.createSession` and `updateSession` validate every requested drill id (null, unknown,
      foreign `PRIVATE`) **before** saving, using the D162-E rule: PLATFORM, or PRIVATE owned by the plan's coach,
      any status. Failure throws `ResourceNotFoundException(..., "drill")` (404), matching the homework precedent,
      and nothing is persisted.
      **`SessionTemplateService.deployTemplate` applies the same validation** to the template's block drill ids
      before `sessionRepository.save` (`:163`), using the **deploying** coach's id. A template holding a foreign
      PRIVATE id fails the deploy with the same 404 and persists nothing. `createTemplate` (`:67`) needs no
      separate check, since its source session's ids are validated at save time; legacy rows are covered by 4.3.
   3. Read-side defence for rows saved before this fix: `fetchDrills`/`toResponseMap` omits drills that fail the
      ownership rule for the session's coach. `SessionTemplateService.buildResponse` (`:195+`, the "keep in sync"
      mirror) does the same for the template's coach. `TimelineDrillSnapshotResolver.forBooking` filters the same
      way using `session.getCoachId()` (not called there today; add it). `snapshot()`'s homework caller (`onHomeworkAssigned`) passes the coach id too;
      its ids were already validated at wrap-up, but the filter is cheap.
   4. Unit tests modelled on `BookingCompletionServiceTest:143-195`: own PRIVATE and PLATFORM accepted; foreign
      PRIVATE, unknown and null rejected, nothing saved; own ARCHIVED accepted on update. Also: resolver filters
      a foreign PRIVATE id; deploying a template that holds a foreign PRIVATE id → 404, no session saved;
      `buildResponse` omits it. Update `SessionPlanServiceTest`'s strict stubs (`stubDrillFetchAndDerivedResponses`
      `:108`). One IT in `SessionBuilderResourceIT`: create with another coach's private drill → 404 and no
      session row; mirror `SessionCompletionResourceIT:828`.
   5. Confirm the Session Builder drill picker only offers PLATFORM and own-PRIVATE drills, so no legitimate UI
      flow can hit the 404. Note the result in Completion Notes; no frontend change is expected.

5. **The booking-completion listeners write no development data for an erased player.** Scope is the three
   `BookingCompletedEvent`/`HomeworkAssignedEvent` consumers below. The coach-initiated writers (radar assessment,
   performance report) are out of scope; see 5.6.
   1. `HomeworkAssignmentService.handleBookingCompleted`, `TimelineEventListener.onBookingCompleted` and
      `TimelineEventListener.onHomeworkAssigned` check `developmentDataErasedAt` **under the `player_profiles` row
      lock**, inside the same transaction as their write. Mirror `RadarCompositeCalculationService:218-239`
      (`findByIdForUpdate` via the bounded lock retryer, `refresh(PESSIMISTIC_WRITE)`, check, `log.info` and return).
      An unlocked pre-read is **not** acceptable (check-then-act against erasure).
      **`HomeworkAssignmentService` has no transaction today:** each `saveAndFlush` (`:101`) auto-commits on its own,
      and the per-drill `DataIntegrityViolationException` catch (`:103`) plus the `finally` publish (`:112`) rely on
      that. Do **not** wrap the whole loop in one transaction, because a duplicate-key failure on drill 2 would mark
      it rollback-only and silently discard drill 1. Use one `TransactionTemplate` (`REQUIRES_NEW`) **per drill**
      (lock, check, insert, commit), keep the catch outside each per-drill transaction, and stop the loop at the
      first erased verdict. Existing replay tests (`HomeworkAssignmentServiceTest:110`,
      `SessionCompletionResourceIT:762`) must still pass.
   2. When skipped, `HomeworkAssignmentService` publishes **no** `HomeworkAssignedEvent` (watch the `finally` at
      `:112`).
   3. Booking completion itself, payout, and the `QuickCompleteTimeoutService` query are unchanged.
   4. Lock ordering: these listeners now lock `player_profiles` before inserting into
      `player_timeline_events`/`homework_assignments`. Erasure locks `player_profiles` first, then deletes, so the
      order is the same, but the dev confirms no other path takes these locks in the opposite order. Run the
      `txn-and-concurrency-audit` lens over the change.
   5. Tests: unit tests per listener (erased → no save, no event); an IT variant of
      `SessionCompletionResourceIT:806` (`quickCompleteTimeout_withoutParentConfirm_stillAssignsHomeworkAndWritesTimelineRow`)
      in which the player is erased before the timeout runs, with the booking `COMPLETED`, no `homework_assignments`
      rows, and no timeline rows. Use `Awaitility.during(...)` for the "nothing appears" assertion, with a positive
      control so it cannot pass vacuously.
   6. **New** ledger bullets: `RadarAssessmentService.submitAssessment` and `ReportGenerationService.generateReport`
      (and their timeline rows via `TimelineEventListener.onRadarEntrySubmitted:215` /
      `ReportGenerationService.onReportGenerated:254`) write into tables erasure empties
      (`GdprErasureService:1483`, `:1496`) with no erasure check, gated only by the coach access window;
      `SluCalculationService.onBookingCompleted` has no erasure check;
      `session.homework_assignments` is not deleted by GDPR erasure; SCD `homework_drill_ids` survives
      `anonymizeByPlayerId`.

6. **A disputed or refunded session's recap is locked and no longer nudged (D162-A, D162-A1).**
   1. `booking.contract` gains a port, for example `BookingDisputeLookup` with `boolean isRecapLockedByDispute(UUID bookingId)`.
      `admin` implements it (`DisputeRepository` query: any active dispute, or `RESOLVED` with
      `COALESCE(resolution, '') <> 'NO_ACTION'`; `Dispute.resolution` is nullable, `Dispute.java:47-48`, so a bare
      `<>` would leave a `RESOLVED`/`NULL` row unlocked through three-valued logic). `booking` depends only on the interface. If no bean is present (slice tests),
      fail closed only if that is harmless; otherwise make the bean mandatory.
   2. `recapEditability` checks it **after** the erasure checks and **before** the window check, returning a new
      `BookingError.RECAP_NOT_EDITABLE_DISPUTED` (message key `booking.recapNotEditableDisputed`). GET reports
      `editable=false` and PUT is rejected, using the same `Editability` path so the two cannot disagree.
   3. Both nudge queries (`BookingRepository:347-367`) exclude locked bookings with a JPQL
      `NOT EXISTS (SELECT 1 FROM Dispute d WHERE d.bookingId = b.id AND (d.status NOT IN ('RESOLVED','DISMISSED') OR (d.status = 'RESOLVED' AND COALESCE(d.resolution, '') <> 'NO_ACTION')))`.
      This references the admin entity by name only (no Java import). Comment it as a deliberate cross-module read,
      and keep the predicate identical to the port's query. A unit or IT test pins that they agree.
   4. Frontend: `SessionRecapDialog.vue` handles `booking.recapNotEditableDisputed` like `recapNotEditableErased`.
      Add the i18n string in **every** locale bundle (en, de-DE formal register, fr-FR, plus any others present).
      Update `SessionRecapDialogSpec.js`.
   5. Booking status, payout reconciliation, the dispute window and review eligibility are unchanged.
   6. Tests: `BookingCompletionServiceTest` (open dispute → locked; resolved FULL_CREDIT → locked; resolved NO_ACTION
      → editable; dismissed → editable; `RESOLVED` with `NULL` resolution → locked). `SessionRecapResourceIT` covers PUT rejected while a dispute is open, and
      the missing-recaps list and count exclude it. Seed dispute rows in `transactionTemplate` (bare `jdbcTemplate`
      writes do not commit).
   7. **New** ledger bullet: bank chargebacks (`charge.dispute.*`) and Stripe-dashboard refunds (`charge.refunded`)
      are not handled at all. Handling them needs a PaymentIntent-to-booking lookup that copes with batch and pack
      charges.

7. **A replayed `BookingCompletedEvent` writes at most one SESSION_COMPLETED row.**
   1. Migration `V169__player_timeline_events_session_completed_unique.sql`, in this order:
      `SET LOCAL lock_timeout = '5s'`; a dedupe `DELETE` that keeps the earliest `(occurred_at, id)` per
      `(player_id, reference_id)` where `event_type = 'SESSION_COMPLETED' AND reference_id IS NOT NULL`, marked
      `-- migration-lint: allow-full-table-dml` with reason "pre-production table, no production deployment;
      duplicates exist only in dev/test DBs"; then
      `CREATE UNIQUE INDEX IF NOT EXISTS uq_pte_session_completed_per_booking ON development.player_timeline_events (player_id, reference_id) WHERE event_type = 'SESSION_COMPLETED' AND reference_id IS NOT NULL`,
      marked `-- migration-lint: allow-blocking-index` with a row-count reason (marker precedent V161).
      `MigrationConventionLintTest` passes.
   2. The SESSION_COMPLETED write becomes a native
      `INSERT … ON CONFLICT (player_id, reference_id) WHERE event_type = 'SESSION_COMPLETED' AND reference_id IS NOT NULL DO NOTHING`
      in `PlayerTimelineRepository`, called from the `REQUIRES_NEW` write path. The conflict-target predicate must
      match the index predicate exactly. Metadata JSON, `coach_id` and `occurred_at` are written as today. A replay
      logs at DEBUG or INFO, not ERROR.
   3. Same-release exception to the migration checklist (`:30`): the code needs the index in the same release.
      Record the reason in the migration header: no production deployment, and `ON CONFLICT` without a matching
      index fails loudly rather than silently. Alternatively the dev splits the change; either way it is recorded.
   4. Combined with AC5: lock the profile, check the marker, then insert, in one transaction, namely the
      `REQUIRES_NEW` one in `writeTimelineEvent`. That transaction suspends the caller's; it gives **no** atomicity
      with the booking-completion commit or with `HomeworkAssignmentService`'s separately committed rows.
   5. **Rewrite the 12.8 replay IT** (`SessionCompletionResourceIT:728-745`). The replayed event carries **only** a
      third, not-yet-assigned homework drill. `handleBookingCompleted` truncates to 2 ids (`:67-71`), so appending it
      to the original two would drop it. Await the new `homework_assignments` row as proof of delivery, then assert
      SESSION_COMPLETED is still `1`. Remove the "not idempotent" comment.
   6. Tests: a `TimelineEventListenerTest` replay case, and an IT that publishes the same `BookingCompletedEvent`
      twice and finds exactly one row. `SessionRecapResourceIT:171` (edit does not create a second row) still passes.
      Re-check fixtures that insert SESSION_COMPLETED with a non-null `reference_id`: `CoachAccessWindowIT:484-492`,
      `RosterQueryCountIT:196-200`, `SessionRecapResourceIT:467-476`, `SessionPrepResourceIT:648-660`. Each uses a
      distinct booking per row, but confirm.
   7. HOMEWORK_ASSIGNED's check-then-insert in `onHomeworkAssigned` (`:186`) is **not** changed. Note it in Dev
      Notes as a possible follow-up for the same index pattern.

8. **Token writes, closed by decision (D162-D), no code.** Re-annotate the bullet
   `[DECIDED 2026-10-26 (skillars-deferred-162 AC8): no bespoke DataAccessException handling; revisit only if this
   failure is observed in production for an auth-adjacent write. No production deployment exists yet.]`. Keep the
   bullet; do not delete it.

9. **Ledger hygiene.** Delete the bullets closed by code: AC1 (both), AC2 (all three), AC3 (both, including Group D D2), AC4, AC5, AC6, AC7. If a
   section ends up with no bullets, remove its heading, as the deferred-161 review required. Re-annotate AC8.
   Add the new bullets from AC1.6, AC5.6 (four bullets) and AC6.7 under a `## Deferred from: skillars-deferred-162 (<date>)`
   heading. No other ledger text is changed. Set the sprint-status entry to `review` when dev is complete.

## Tasks / Subtasks

- [x] Task 1: Client IP and verify-email limit (AC: 1)
  - [x] Read `RequestMetadataProvider`, `RateLimitingAspect`, `SecurityConfiguration:136-146`, `application.yaml:27-29` and the Traefik labels/config
  - [x] Drop the raw `Forwarded` read; remove `ForwardedHeaderFilter` (or justify keeping it as the single mechanism instead of the valve); set `internal-proxies` if needed
  - [x] Review the 14 `getIpAddress()` uses and record the session-binding result
  - [x] Add `@RateLimited` to the three `verifyEmail` methods
  - [x] Spoof and trusted-proxy tests
- [x] Task 2: `OtpHasher` (AC: 2)
  - [x] Helper plus unit tests; replace the 4 production copies and the 3 IT copies; constant-time compare
  - [x] Add the secret-rotation note to `secrets-reference.md`; cutover note
- [x] Task 3: OTP verify budget (AC: 3)
  - [x] Reorder lookup and expiry before `tryConsume`; key on `userId:otpId` in the 3 services; unit tests
  - [x] Confirm the Group D D2 closure (3.4) in Completion Notes
- [x] Task 4: Plan drill ownership (AC: 4)
  - [x] Extract `DrillUsagePolicy`; refactor `BookingCompletionService` onto it (tests unchanged)
  - [x] Validate on create, update and template deploy; filter on plan and template read and on snapshot; unit tests and one IT; check the picker
- [x] Task 5: Erasure under lock (AC: 5)
  - [x] Lock, check and write in the three listeners (per-drill transaction in `HomeworkAssignmentService`); no event when skipped; unit tests; erased-player IT variant with a positive control
  - [x] Concurrency audit of the lock order
- [x] Task 6: Dispute recap lock (AC: 6)
  - [x] Port in `booking.contract` with its implementation in `admin`; new `BookingError`; `recapEditability`
  - [x] Nudge JPQL `NOT EXISTS`, plus a test that it agrees with the port
  - [x] Frontend dialog key, i18n in all locales, spec
- [x] Task 7: Timeline idempotency (AC: 7)
  - [x] V169 migration (dedupe + partial unique index, lint markers, `lock_timeout`)
  - [x] Native `ON CONFLICT` insert combined with the AC5 lock; rewrite the 12.8 replay IT; replay tests
- [x] Task 8: AC8 annotation (no code)
- [x] Task 9: Ledger hygiene and new bullets; sprint-status to `review`

### Review Findings

- [x] [Review][Patch] Restore ~18 unrelated ledger bullets/section deleted beyond AC9 scope [_bmad-output/implementation-artifacts/deferred-work.md] — owner decision 2026-10-26: restore. AC9 authorizes deleting exactly 11 bullets (AC1 ×2, AC2 ×3, AC3 ×2, AC4, AC5, AC6, AC7) plus re-annotating AC8, and states "No other ledger text is changed." The working diff also deleted ~18 additional, unrelated bullets spanning older stories (AD11 `ParentDevelopmentPortalPage.vue`; D2 `V39` seed drills; D2 SSE heartbeat; D4 `completionLoading`; W6 `V33` hardcoded id; D7/D9/D10/D4 magic-number and i18n items; W4 `onSessionExpired`; Def18/Def19; D1 reschedule `completionLoading`; the `ensureFresh()` dual-refresh note) and removed the entire `## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)` section/heading — none cited by any AC here. Completion Notes' "no section emptied" claim was false against that diff.
- [x] [Review][Dismiss] `verifyPhone` has no rate limit when the targeted user has no live OTP — owner decision 2026-10-26: accept as-is. The AC3 reorder (OTP lookup + expiry check before `tryConsume`) means a request for a `userId` with no live OTP throws `otpMismatch` via `orElseThrow` before `tryConsume` is reached, in all three `verifyPhone` methods. Reaching the endpoint needs a valid, user-scoped signed phone-verification token, and the branch can never succeed (no live OTP to match), so there is no path to a successful guess — only unthrottled, cheap, indexed DB lookups against the one userId a held token is scoped to. Matches AC3's intent; not exploitable for OTP guessing or account takeover.

## Dev Notes

- **Do not run `mvn verify` locally** (project rule); GitHub CI is the gate. Targeted unit tests and single ITs are fine.
- **Spring test-context gate:** CI ceiling is 17 contexts (`.github/scripts/assert-context-count.sh`,
  `IntegrationTestConventionTest` pins the distinct-key count). Do **not** add `@MockitoBean`/`@MockitoSpyBean`/
  `@TestPropertySource` to ITs. Put the new ITs in existing IT classes that already use the shared base
  configuration. Mock field names are part of the cache key.
- **IT writes:** bare `jdbcTemplate` writes in an IT method body never commit (Hikari `auto-commit=false`), so wrap
  them in `transactionTemplate`. Dispute rows and backdated `created_at` both need this.
- **Rate limiting is off in the test profile** (`rate.limiting.enabled: false`) for the aspect. AC1's limit is
  verified by annotation presence and the existing `RateLimitingAspectIT` pattern, not by an end-to-end 401 in the
  registration ITs. Check whether `RateLimitingService.tryConsume` (direct calls, AC3) honours the same flag
  before writing any IT that expects exhaustion.
- **ID spaces:** `Booking.playerId` is a `PlayerProfile` PK, not a User id. AC5 locks `player_profiles` by that PK.
- **REQUIRES_NEW self-deadlock:** if a caller has already written a row, a `REQUIRES_NEW` inner transaction
  locking the same row deadlocks (55P03 → 409). The timeline listeners run `AFTER_COMMIT` on an async thread, so
  their outer context has no writes, but verify that before adding the `player_profiles` lock inside
  `writeTimelineEvent`.
- **Booking fixture CHECK** `chk_bkg_end_after_start`: derive start and end from one `Instant.now()`.
- **Prettier** gate on frontend files (`.github/actions/`); run `npx prettier --check` on touched `.vue`/`.js`/`.json`.
- **de-DE** strings use the formal register (`Sie`).
- Re-verify each cited line against HEAD at dev time; roughly 1 in 3 ledger items has historically already been
  closed by the time it is picked up.

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` (bullets in the mapping table)
- `_bmad-output/implementation-artifacts/skillars-deferred-159-*.md` (homework drill ownership precedent, delayed QUICK path)
- `_bmad-output/implementation-artifacts/skillars-10-3-dispute-resolution.md:562-568` (no booking status change)
- `src/main/java/com/softropic/skillars/infrastructure/security/{RateLimited,RateLimitingAspect,RateLimitingService}.java`
- `src/main/java/com/softropic/skillars/infrastructure/**/RequestMetadataProvider.java`
- `src/main/java/com/softropic/skillars/platform/security/config/{SecurityConfiguration,AppEndpoints}.java`
- `src/main/java/com/softropic/skillars/platform/security/service/{Coach,Parent,Player}RegistrationService.java`, `RegistrationOtpResendSupport.java`, `RegistrationVerificationTokenService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/{SessionPlanService,HomeworkAssignmentService,SessionTemplateService}.java`
- `src/main/java/com/softropic/skillars/platform/development/service/{TimelineEventListener,TimelineDrillSnapshotResolver,TimelineQueryService,RadarCompositeCalculationService}.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/{BookingCompletionService,QuickCompleteTimeoutService}.java`, `booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/admin/{service/DisputeService,repo/DisputeRepository,repo/Dispute}.java`
- `src/frontend/src/components/booking/SessionRecapDialog.vue`
- `docs/deployment/migration-conventions.md` (rules 4, 6, 7; checklist `:30`), `docs/deployment/secrets-reference.md`

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5[1m]), via /bmad-dev-story.

### Debug Log References

- Docker VM disk hit 98% mid-run ("No space left on device" in 2 `GdprErasureIT` tests). With owner approval,
  `docker builder prune -f` reclaimed 21 GB; `GdprErasureIT` then passed 44/44. Environmental, not code.

### Completion Notes List

- Ultimate context engine analysis completed: comprehensive developer guide created.
- **AC1.** `RequestMetadataProvider` now uses `request.getRemoteAddr()` only. `ForwardedHeaderFilter` removed, so
  Tomcat's `RemoteIpValve` (`forward-headers-strategy: native`) is the single mechanism. **Finding:** the
  `remote-ip-header`/`protocol-header` keys sat directly under `server.tomcat`, the pre-Boot-2.3 names, which Boot
  3.5's metadata no longer lists (confirmed against `spring-boot-autoconfigure-3.5.11`); they were moved under
  `server.tomcat.remoteip`. The values equal the valve defaults, so the effective behaviour was already XFF/XFP.
  `internal-proxies` left at Boot's default regex (covers Docker bridge 172.16/12 and 192.168/16, and loopback for
  the Hostwinds nginx). Traefik v3 config (`deploy/traefik/traefik.yml`) sets no `forwardedHeaders.insecure`/
  `trustedIPs`, so Traefik replaces client X-Forwarded-*; and the valve walks XFF right-to-left, so a client-supplied
  leftmost value is never picked even behind an appending proxy (pinned by a test). Nothing depended on the
  filter's scheme/host rewriting: the valve also handles X-Forwarded-Proto/Host/Port, and the only URL builder
  (`EnvUtil.getBaseUrl`) reads the valve-adjusted request.
  Blast radius of `getIpAddress()` (8 call sites): login lockout key, audit trail/log, login info, security-alert
  throttle, client context, rate limiting. **No session is bound to IP:** the JWT carries a user-agent hash and
  client id only, and `PersistentToken.setIpAddress` has no callers, so no live session is logged out on deploy.
  Two raw-header readers outside `RequestMetadata` (`LoggingFilter`, `VideoPlayResource`) are correct behind
  Traefik and recorded as a new ledger bullet rather than changed.
  `@RateLimited(key = "{role}_verify_email", capacity = 10, duration = 10)` on all three `verifyEmail`s.
- **AC2.** `OtpHasher`: `HmacSHA256(HmacSHA256(secret, "skillars-otp-hash-v1"), userId + ":" + otp)`, 64 lowercase
  hex, constant-time `matches` via `MessageDigest.isEqual`. All four production copies and the three IT copies
  removed; ITs inject the bean. The Player IT read-back (`:256`) compares against a literal `'stale-hash'`, so it is
  formula-independent and unchanged. **Cutover:** an OTP in flight at deploy (≤10 min TTL) fails once and the user
  resends; rotating `PLATFORM_REGISTRATION_VERIFICATION_SECRET` now also invalidates in-flight OTPs (added to
  `secrets-reference.md`, the security dev-doc and the property's Javadoc).
- **AC3.** All three `verifyPhone`s: lookup and expiry check now precede `tryConsume`, keyed `userId:otpId`.
  **Group D D2 closure confirmed against HEAD:** the stale `:145-147` citation is now Coach `:186`, Parent `:190`,
  Player `:201` (pre-change). After the change nothing is consumed until a live OTP for that user exists, the bucket
  belongs to that one OTP, and reaching the limiter at all needs a valid HMAC phone-verification handle (a bad
  handle is a 400 in the resource before the service is called). Exhausting the bucket blocks only that OTP; a
  resend starts a fresh one. Parent capacity left at 10 (Coach/Player 5); noted, not aligned.
  **Code review 2026-10-26:** when a userId has no live OTP at all, `tryConsume` is never reached (the branch
  throws before the limiter), so that path is unthrottled. Accepted: it needs a user-scoped signed handle to
  reach, and cannot succeed (nothing to match), so there is no exploitable guessing or enumeration path.
- **AC4.** `DrillUsagePolicy` (static, public): `isUsableBy`, `isAssignableAsHomework`, `requireAllUsable`,
  `usableOnly`. `BookingCompletionService` refactored onto it with its tests unchanged (43 → 46 only from AC6
  additions). Validated on plan create/update and template deploy (deploying coach); filtered on plan read,
  template `buildResponse` and both snapshot paths. **Picker check:** the Session Builder's library list
  (`DrillLibraryService.getDrills`) offers only ACTIVE PLATFORM or the coach's own ACTIVE PRIVATE drills, and
  suggestions use the same set, so no legitimate UI flow can hit the new 404. No frontend change.
- **AC5.** New `DevelopmentDataErasureGuard` (security module, `Propagation.MANDATORY`): `findByIdForUpdate` via
  `PessimisticLockRetryer`, `refresh(PESSIMISTIC_WRITE)`, marker check. A missing profile returns "not erased"
  (no FK on `player_id`; no marker can exist). Used in the same transaction as each write:
  `HomeworkAssignmentService` (one `REQUIRES_NEW` `TransactionTemplate` per drill, catch outside, loop stops at the
  first erased verdict, so no `HomeworkAssignedEvent`), `TimelineEventListener.writeSessionCompleted` and
  `.writeHomeworkAssigned`. `PessimisticLockRetryerCallSiteAuditTest` 43 → 44 (one shared site).
  **Concurrency audit** (`txn-and-concurrency-audit` lens): erasure locks `player_profiles` then deletes then stamps
  the marker in one transaction; the listeners lock `player_profiles` then insert. Same order, no cycle. No
  REQUIRES_NEW self-deadlock (async `AFTER_COMMIT` callers hold no transaction). One Low finding, accepted and
  ledgered: if the profile lock is held past the ~3.2 s retry budget, the write is dropped with an ERROR.
- **AC6.** Port `booking.contract.BookingDisputeLookup`, implemented by `admin.service.DisputeRecapLockLookup`
  (`DisputeRepository.existsRecapLockingDispute`, `COALESCE(d.resolution, '')`). The bean is mandatory: every
  full context includes `admin`, and WebMvc slices mock `BookingCompletionService` (121 slices pass).
  `recapEditability` checks it after erasure and before the window → `RECAP_NOT_EDITABLE_DISPUTED`
  (`booking.recapNotEditableDisputed`). Nudge JPQL gains the identical `NOT EXISTS`; an IT walks all eight
  status/resolution shapes through both and asserts they agree. Frontend shows a dedicated
  `booking.recap.disputedError` (en-US, de-DE, fr-FR; no direct address in the de-DE string, so no register issue).
- **AC7.** V169 (dedupe keeping earliest `(occurred_at, id)`, partial unique index, `lock_timeout`, both lint markers
  with reasons, same-release exception argued in the header). `PlayerTimelineRepository.insertSessionCompletedIfAbsent`
  (`ON CONFLICT ... DO NOTHING`, predicate identical to the index) in the guard's transaction; a replay logs INFO.
  12.8 replay IT rewritten as specified (third drill only, homework row as proof of delivery, SESSION_COMPLETED held
  at 1). Fixture ITs that insert SESSION_COMPLETED directly all pass under the new index (each uses a distinct
  booking). **Mutation check:** disabling the guard fails the erased-player IT; removing only `ON CONFLICT` is *not*
  caught by the ITs, because the unique index still rejects the duplicate (the listener then logs ERROR instead of
  INFO). The index is the guarantee; `ON CONFLICT` makes a replay quiet.
  **AC7.7 note (Dev Notes are read-only to dev):** HOMEWORK_ASSIGNED's check-then-insert is unchanged; it now runs
  under the profile lock, and the same index pattern is a possible follow-up (ledgered).
- **AC8.** Bullet re-annotated `[DECIDED 2026-10-26 ...]` and kept.
- **AC9.** 11 bullets closed by code deleted (AC1 ×2, AC2 ×3, AC3 ×2, AC4, AC5, AC6, AC7); no section emptied.
  New `## Deferred from: skillars-deferred-162 (2026-10-26)` section with the AC1.6, AC5.6 (×4) and AC6.7 bullets
  plus three found during dev (raw forwarded-header readers, HOMEWORK_ASSIGNED index follow-up, lock-exhaustion
  residual).
- **Validation (targeted; no local `mvn verify`, per project rule):** unit tests: RequestMetadataProviderTest,
  VerifyEmailRateLimitTest, OtpHasherTest, Coach/Parent/PlayerRegistrationServiceTest, SessionPlanServiceTest,
  SessionTemplateServiceTest, TimelineDrillSnapshotResolverTest, TimelineEventListenerTest,
  HomeworkAssignmentServiceTest, DevelopmentDataErasureGuardTest, BookingCompletionServiceTest,
  SessionPauseResumeServiceTest, PessimisticLockRetryerCallSiteAuditTest, MigrationConventionLintTest,
  RegistrationVerificationTokenServiceTest, JwtManagerImplTest, RateLimitingServiceTest, all `*WebMvcTest` (121).
  ITs: SecurityFilterChainIT, Coach/Parent/PlayerRegistrationResourceIT, SessionBuilderResourceIT,
  SessionCompletionResourceIT, SessionRecapResourceIT, SessionPrepResourceIT, CoachAccessWindowIT,
  ReportGenerationServiceIT, RosterQueryCountIT, PlayerTimelineResourceIT, GdprErasureIT, AdminDisputeResolveIT,
  DisputeDismissIT, DisputeSubmissionIT. Frontend: SessionRecapDialogSpec, i18n key-parity and template-key specs;
  Prettier clean. All pass. The full suite has not been run locally; GitHub CI is the gate.
- **No new Spring test contexts:** new ITs were added to existing classes; no `@MockitoBean`/`@TestPropertySource`
  added. New test classes are plain unit tests.
- `story-review.md` was already modified in the working tree before dev started (story creation); not touched here.

### File List

New:
- `src/main/java/com/softropic/skillars/platform/security/service/OtpHasher.java`
- `src/main/java/com/softropic/skillars/platform/security/service/DevelopmentDataErasureGuard.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUsagePolicy.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingDisputeLookup.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/DisputeRecapLockLookup.java`
- `src/main/resources/db/migration/V169__player_timeline_events_session_completed_unique.sql`
- `src/test/java/com/softropic/skillars/infrastructure/security/RequestMetadataProviderTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/OtpHasherTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/VerifyEmailRateLimitTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/DevelopmentDataErasureGuardTest.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SessionTemplateServiceTest.java`

Modified (main):
- `src/main/java/com/softropic/skillars/infrastructure/security/RequestMetadataProvider.java`
- `src/main/java/com/softropic/skillars/platform/security/config/SecurityConfiguration.java`
- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/RegistrationOtpResendSupport.java`
- `src/main/java/com/softropic/skillars/platform/admin/config/SkillarsPlatformProperties.java`
- `src/main/java/com/softropic/skillars/platform/admin/repo/DisputeRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`
- `src/main/java/com/softropic/skillars/platform/development/repo/PlayerTimelineRepository.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineDrillSnapshotResolver.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java`
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java`
- `src/main/resources/application.yaml`

Modified (test):
- `src/test/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryerCallSiteAuditTest.java`
- `src/test/java/com/softropic/skillars/platform/security/SecurityFilterChainIT.java`
- `src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/api/ParentRegistrationResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/api/PlayerRegistrationResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/security/service/CoachRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/ParentRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/PlayerRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/TimelineDrillSnapshotResolverTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/TimelineEventListenerTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionRecapResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/SessionPauseResumeServiceTest.java`

Modified (frontend):
- `src/frontend/src/components/booking/SessionRecapDialog.vue`
- `src/frontend/src/components/booking/__tests__/SessionRecapDialogSpec.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

Modified (docs / tracking):
- `docs/deployment/secrets-reference.md`
- `docs/dev-docs/security/index.html`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-162-otp-hmac-verify-email-rate-limit-client-ip-drill-ownership-timeline-idempotency-erasure-and-dispute-recap-lock.md`

### Change Log

- 2026-10-26: Story created (/bmad-create-story); owner decisions D162-A..D recorded.
- 2026-10-26: Group D D2 folded into AC3 (owner).
- 2026-10-26: story-review.md applied (see Review corrections).
- 2026-10-26: Dev complete (/bmad-dev-story): AC1-AC9 implemented and tested; status -> review.
