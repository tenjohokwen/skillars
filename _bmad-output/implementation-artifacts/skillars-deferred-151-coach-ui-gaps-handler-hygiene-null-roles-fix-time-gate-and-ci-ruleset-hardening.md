# Story: Coach Self-Service UI Gaps, Null-ROLES Auth Hardening, Booking Time-Gate Enforcement, and CI/Branch-Protection Provenance Hardening

**Status:** done

**Scope Level:** Large (eight items, revised after an independent adversarial story review — `story-review.md`, 2026-10-14 — found two of the original eight unimplementable as scoped. No schema change beyond what's noted per-item.)

---

## User Story

As **a coach using Skillars, and the developer maintaining its CI/branch-protection posture**, I want eight independently-verified, currently-open defects and gaps fixed — three fully-built backend features with no coach-facing UI at all (reviews self-service, session-pack tier management, report branding — the last two requiring a small backend fix alongside the UI, found during story review), a worthwhile dedup of five near-identical command-center action handlers (not a live bug — see Finding 4's correction), a latent auth-hardening fix, no time-based gate on any UPCOMING-gated coach action (including the newly-shipped "Report No-Show" button, whose financial-forfeiture consequence makes this the most urgent item in the bundle), a published CI Docker image with no git-provenance assertion, and a branch-protection ruleset that exists only as live, unversioned GitHub config — so that **coaches can see and respond to their own reviews, sell and manage their own session-pack offer, and set their own report branding without every save silently 403ing; the command-center's five action handlers share one well-tested code path instead of five near-duplicates; a null `ROLES` claim degrades gracefully instead of risking an uncaught exception if that code path is ever reached; a coach can no longer report a no-show (or start/quick-complete a session) before it has actually started; every image anyone actually runs in production carries a verified git-provenance assertion, not just PR-build images; and master's required status checks are reviewable, re-appliable git history instead of a single point of live, silently-droppable configuration.**

---

## Context: Current State (verified against HEAD `34f9726a`, 2026-10-14)

This story bundles eight items mined from `_bmad-output/implementation-artifacts/deferred-work.md`'s most recent sections — "Deferred from: bmad-quick-dev coach UI-gap scan (2026-10-14)", "Deferred from: code review of spec-coach-booking-cancel-no-show (2026-10-14)", "Deferred from: code review of skillars-deferred-150 (2026-10-09)", and "Deferred from: code review of skillars-deferred-149 (2026-10-08)". Four items needed a product/process decision, resolved directly with the project owner before drafting — each marked **USER DECISION** below, final.

**This version (v1.1) incorporates a full adversarial story review** (`story-review.md`, 4 parallel verification layers + a mandatory re-verification pass, 35 citations / 17 ledger attributions / 24 mechanistic claims / 9 ACs checked). Two findings were **BLOCKING** — AC3 and AC4 as originally drafted cannot be implemented — and are corrected in place below rather than left as a changelog footnote, since an implementer reading only the Design/AC sections must see the corrected version, not the withdrawn one. Several of the review's own findings were in turn independently re-verified by me and one was found to be a **false positive** (the "ledger exhaustiveness" claim in §2c of the review — see the correction at the end of this section); the review's other findings held up under direct re-verification against source and are incorporated.

### Finding 1 — Coach reviews self-service is a fully-built, completely orphaned backend feature

`GET /api/reviews/coaches/me` (`ReviewResource.java:52-61`, `listMyReviews`, `@PreAuthorize(HAS_COACH_ROLE)`, paginated via `CoachOwnReviewListResponse`, page size fixed at 10 server-side) and `POST /api/reviews/{reviewId}/response` (`ReviewResource.java:137-148`, `submitCoachResponse`, same role gate, body validated via `CoachResponseRequest(@NotBlank @Size(max = 500) String body)`) have existed since before this session and have **zero** frontend client functions and **zero** pages. `src/frontend/src/api/reviews.api.js` exports exactly five functions — none coach-self-service. `CoachPublicProfilePage.vue` only consumes the parent/player-facing paths; it has no code path reachable by a coach caller.

**Response DTO shapes:** `CoachOwnReviewListResponse(List<CoachOwnReviewDto> reviews, int page, int totalPages, long totalElements, boolean hasNext)`; `CoachOwnReviewDto(UUID reviewId, String authorRole, int rating, String body, String moderationStatus, String coachResponseBody, Instant coachResponseAt, Instant createdAt, Instant lastModifiedAt)` — field-for-field identical to the existing parent-facing `AuthorReviewDto`.

**Two corrections found by story review, both load-bearing for the composer gate:**
1. **`listCoachOwnReviews` applies no moderation filter, unlike the public path.** `ReviewQueryService.java:54` is `reviewRepository.findByCoachId(coachId, pageable)` — every moderation status (`PENDING`, `APPROVED`, `BLOCKED`, `UNDER_REVIEW`; **there is no `REJECTED` status** — `ReviewModerationStatus.java` has exactly those four) comes back, unlike the public path's `findByCoachIdAndModerationStatus(... APPROVED ...)` at `:34-35`.
2. **`submitCoachResponse` itself rejects any non-`APPROVED` review** (`ReviewSubmissionService.java:244-248`, `REVIEW_NOT_APPROVED`), checked **before** the "already responded" check at `:249-252`. So a composer gated only on "`coachResponseBody` is null" (the original design) would render on a `PENDING`/`BLOCKED`/`UNDER_REVIEW` review and 403 on submit. **The gate must be `coachResponseBody == null && moderationStatus === 'APPROVED'`** — and since the public-profile display precedent never renders a non-`APPROVED` review at all, there is no existing UI convention for what a coach should see for their own pending/blocked reviews; design new copy (e.g. "awaiting moderation" / "removed by moderation") rather than silently hiding the row.

**Also found by story review:** a coach caller with no `CoachProfile` row yet (reachable mid-profile-builder — the route has no completeness gate) gets a bare 404 `RESOURCE_NOT_FOUND` with no i18n entry in any bundle (`CoachProfileService.java:706-711`). Render an empty/incomplete-profile state for this case rather than a generic error banner.

**Visual precedent to mirror:** `CoachPublicProfilePage.vue`'s review-list block (`:88-127`) already renders a `q-rating` (readonly, amber, 16px), a review-item card, the review body, a `coach-response` sub-block, and a `loadMore`-gated pagination button. The new piece is a response composer (≤500 chars) shown only for a review passing both gates above.

### Finding 2 — Coach session-pack tier management: the backend has no "catalog" to build a catalog UI around

`GET/POST /api/payment/coaches/me/session-pack-tiers`, `PATCH .../{tierId}/deactivate` (`SessionPackPaymentResource.java:103-126`) and `POST /api/payment/session-packs/{purchaseId}/extend` (`:93-99`) all already have client functions in `api/payment.api.js` — `fetchMySessionPackTiers`, `createSessionPackTier`, `deactivateSessionPackTier`, `extendSessionPack` — with zero call sites anywhere except `fetchMySessionPackTiers`, which `payment.store.js`'s **`fetchSessionPackTiers`** action (`:95-105` — this is the real action name; an earlier draft of this story called it `loadMySessionPackTiers`, which does not exist) already calls, into `sessionPackTiers` state read by zero page components. `createSessionPackTier`/`deactivateSessionPackTier` are **not imported** into `payment.store.js` at all (the import block, `:2-23`, carries only `fetchMySessionPackTiers`) — an earlier draft of this story said they were "already-imported-but-unused," which is wrong; they need a fresh import.

**Major correction found by story review — there is no tier "catalog" to manage.** `SessionPackPaymentService.createTier` (`:181-197`) **deactivates every existing active tier before inserting the new one**, backed by a DB partial unique index making two active tiers for one coach impossible:

```sql
-- V138__baseline_schema.sql:3910
CREATE UNIQUE INDEX idx_spt_one_active_per_coach ON payment.session_pack_tiers USING btree (coach_id) WHERE (is_active = true);
```

There is also **no reactivate endpoint anywhere** (confirmed by a full grep of the payment package). So "list tiers, create, deactivate per-row" (the original design) describes a UI for a catalog that cannot exist server-side: at most one tier is ever active, creating a second silently and permanently retires the first with no confirmation, and the full history list (`GET .../session-pack-tiers`, unbounded, no `ORDER BY`) grows by one dead row per price change in whatever order Postgres returns it.

**Decision: re-model as "your current offer + its replaced history."** The page shows the one active tier prominently (if any), a stable-sorted (e.g. by `createdAt DESC`) read-only history of inactive ones below it, and a create form that is explicitly framed as "replace your current offer" with a confirmation step — not an anonymous "create tier" button. No deactivate-per-row UI is needed for inactive tiers (they're already inactive); the only destructive action is "replace," which already carries the confirmation.

**Create-form validation gap found by story review:** `CreateSessionPackTierRequest(@NotBlank @Size(max=200) String label, @NotNull @Min(1) Integer sessionCount, @NotNull @DecimalMin("0.01") BigDecimal totalPrice)` has no upper bound and no currency/precision guard. `pricePerSession` is derived server-side (`totalPrice / sessionCount`, `HALF_UP` to 2dp) and a combination like `totalPrice=0.01, sessionCount=10` rounds to `0.00`, which then trips `chk_spt_price_per_session_positive` (`V138:1911`) as a raw 500, not a validation 400. The create form must enforce `totalPrice / sessionCount >= 0.005` client-side (and reasonable upper bounds) to avoid this.

**Manual-extend widget — scoped narrowly, documented honestly.** `extendSessionPack`'s ownership is enforced server-side before any mutation (`SessionPackPaymentService.java:154-156` — confirmed **no IDOR risk** in the raw-purchase-id-input design), but **the action fails in most real states**: one extend ever per purchase (`:158`, 409 if already extended), and only inside a 14-day pre-expiry window (`:168-171`, else a `PaymentGatewayException`) — both collapse through `ApiAdvice`'s generic `ResponseStatusException` handling to `errorKey: "generic.requestError"`, **not branchable** into a specific message. None of the three backend reason strings (`payment.packAlreadyExtended`, `payment.packExtensionNotEligible`, and the success case) exists in any frontend locale bundle. The widget's help text must state the 14-day/one-shot constraints up front (so the common failure isn't a surprise) and its error handling must show one generic "could not extend — check the pack is within its eligible window and hasn't already been extended" message, not attempt to distinguish the backend's two failure reasons.

**Where it belongs:** NOT `CoachPaymentSettingsPage.vue` (Stripe Connect onboarding only). New page.

### Finding 3 — Coach report branding: a backend id-space bug blocks every save, and the ACADEMY gate is unreachable by its own intended audience

`GET/PUT /api/development/coaches/me/branding` (`CoachBrandingResource.java:29-44`, `HAS_COACH_ROLE`) have existing client functions `getCoachBranding()`/`saveCoachBranding(logoKey, brandColour)` in `development.api.js:41-44`, called nowhere. Shapes: `CoachBrandingRequest(@Size(max=500) String logoKey, @Pattern("^#[0-9A-Fa-f]{6}$") String brandColour)`; `CoachBrandingResponse(String logoSignedUrl, String brandColour)`.

**Three defects found by story review, one of them BLOCKING:**

1. **BLOCKING — `saveBranding`'s ownership check compares the wrong identifier space; the designed upload flow 403s on every save.** `ReportGenerationService.saveBranding` (`:279-281`):
   ```java
   if (request.logoKey() != null) {
       fileStorageService.assertOwnership(request.logoKey(), coachId.toString());
   }
   ```
   `coachId` is the `CoachProfile` **UUID**. But `FileStorageService.getOwnerId()` (`:68-70`) — the value stamped onto the blob's `FileStorageObject.ownerId` at `signUpload`/`confirmUpload` time (`:149`) — returns `securityUtil.requireCurrentBusinessId()`, i.e. the caller's **numeric user id** (`Principal.businessId`, self-documented at `Principal.java:20` as `// user id`). A `CoachProfile` UUID can never equal a numeric user-id string, so following the designed presign→PUT→`confirmUpload`→`saveBranding` flow literally **always** 403s with `MISSING_RIGHTS` on the save step. Skipping `confirmUpload` avoids the 403 but leaves the blob unregistered (no `FileStorageObject` row — `assertOwnership`'s `.ifPresent` then passes silently — `:334-340`) and unquota'd. The one call site in the repo has **zero real-path test coverage**: `ReportGenerationServiceTest` mocks `FileStorageService` away entirely (`:67`), so this bug has never executed against the real service. **This falsifies an earlier draft of this finding, which claimed the backend "already exists and is correct as-is."**

   **Fix required (backend, in scope for this AC):** thread the caller's own user id through to the ownership check instead of the `CoachProfile` UUID. Change `CoachBrandingResource.saveBranding` to resolve and pass `securityUtil.getCurrentCoachUserId()` alongside `coachId`, and change `ReportGenerationService.saveBranding`'s signature to accept it, calling `fileStorageService.assertOwnership(request.logoKey(), String.valueOf(coachUserId))` instead of `coachId.toString()`. Add a test that actually exercises `FileStorageService` for this path (not a full mock) — e.g. a focused IT, or a unit test against a real-shaped `FileStorageObject` fixture rather than a bare mock — since the existing test suite's blind spot is exactly what let this ship.

2. **BLOCKING — `authStore.coachTier` is `null` on any cold load of `/profile`, hiding the feature from the coaches entitled to it.** An earlier draft of this finding claimed the coach's tier was "already available without a new call." Verified: `coachTier` (`auth.store.js:13`) is populated **only** by `fetchCoachTier()` (`:34-39`, hits `GET /api/marketplace/coaches/me/tier` — a different endpoint from the subscription-fetch path this draft assumed), which has exactly **one** caller repo-wide: `PlayerDevelopmentDashboardPage.vue:250` — a page a coach has no reason to visit. `ProfilePage.vue` has zero references to `coachTier`/`fetchCoachTier`. So on any direct-URL/bookmark/hard-refresh visit to `/profile`, `coachTier.value` is still its initial `null`, and a gate of `authStore.coachTier === 'ACADEMY'` reads `false` for a genuine ACADEMY coach — the opposite of the intended behavior.

   **Fix required:** call `authStore.fetchCoachTier()` from `ProfilePage.vue`'s `onMounted`, and render a three-state gate (loading / locked / unlocked), not a direct read of a possibly-stale ref. (`PerformanceReportsPanel.vue:83` already guards `coachTier != null` explicitly — a precedent for not trusting the ref bare.)

3. **A colour-only edit silently destroys an existing logo.** `saveBranding` (`:282-287`) is an unconditional full replace, not a merge — `b.setLogoKey(request.logoKey())` always runs. The GET response exposes only `logoSignedUrl` (a derived, time-limited signed URL), never the raw `logoKey`, so the frontend has no way to resend the existing key on a colour-only save unless the upload happened in the same session.

   **Fix required (small backend addition):** add a raw `logoKey` field to `CoachBrandingResponse` alongside `logoSignedUrl` (this project already round-trips a raw storage key through a confusingly-named `photoUrl` field in `ProfileBuilderStep5Request`/`CoachProfileSelfResponse` — logoKey is not sensitive, it's a storage path, not a secret) so the dialog can resubmit it unchanged when the coach only edits the colour. Alternative considered and rejected: a partial-update/PATCH semantic distinguishing "field omitted" from "field explicitly null" — bigger change, not needed here.

**ACADEMY-tier gate, correctly identified, mechanism confirmed sound:** `getBranding`/`saveBranding` (`:262-277`) both check `coachProfileService.getCoachSubscriptionTier(coachId) != ACADEMY`; `getBranding` degrades to `(null, null)`, `saveBranding` throws `FeatureGatedException` → HTTP 403, `errorKey: "security.featureGated"` — already present in all three frontend bundles. Gate the form on the (now correctly cold-loaded) `authStore.coachTier === 'ACADEMY'`, with a defensive catch on save for the same `errorKey` (the tier could still change mid-session).

**Upload mechanism, confirmed sound:** mirror `EditCoachPhotoDialog.vue`'s presign→PUT→confirm→save flow with `entity: 'coach_branding'` — `StorageKeyGenerator.generate` sanitizes and accepts any entity string, no filestorage change needed for the key itself (the bug is entirely in the ownership check above, not the key generation). **One judgment call worth making knowingly, not silently:** `StorageResource.java:38`'s `OWNER_BOUND_ENTITIES = Set.of("coach_profile")` binds the presign's `entityId` to the caller for that one entity only; `coach_branding` gets no such binding unless added to that set. Decide during implementation whether branding needs the same entityId↔caller binding `coach_profile` has, or whether `assertOwnership`'s own post-upload check (once fixed per item 1 above) is sufficient on its own.

### Finding 4 — Command-center handler duplication: a worthwhile dedup, **not** a live defect (corrected)

**An earlier draft of this finding described a defect that cannot occur, and would have been the third time this exact claim was filed and withdrawn.** `booking.store.js:453-467`'s `loadCoachSchedule` carries an explicit contract comment (`:360-368`):

> *"CONTRACT — loadCoachBookingRequests and loadCoachSchedule below NEVER RETHROW. Each swallows its own failure into coachRequestsError / coachScheduleError and resolves normally, so an `await` on either can never reject and callers need no `.catch()` guard. Two independent review passes on the same day (2026-08-18) filed the same 'unguarded refresh → unhandled promise rejection' defect against call sites of these loaders; both were withdrawn as factually wrong."*

Read in full, `loadCoachSchedule`'s only `await` is inside its own `try`; every path — success or failure — resolves to `true`/`false` and is caught internally. So in `CoachCommandCenterPage.vue`'s five handlers (`handleAcceptReschedule`, `handleDeclineReschedule`, `handleCoachCancelConfirm`, `confirmNoShowPlayer`, `submitCoachReschedule`), the `await bookingStore.loadCoachSchedule(...)` calls — both the one inside each handler's `try` and the one inside four of the five handlers' `catch` blocks — **cannot throw**. There is no reachable "refresh rejection propagates unguarded" case, and no reachable "refresh failure after a successful mutation gets misreported as 'action failed'" case either: a failed refresh resolves `false`, which `notifyIfScheduleStale(false)` (`:582-586`) turns into a stale-list warning toast, shown alongside the success toast — the correct message pair, not a misreport.

**What is still genuinely true, and worth fixing on maintainability grounds alone:** the five handlers are near-identical copies of the same mutation→refresh→toast shape, which is real duplication (and `submitCoachReschedule` is the one genuine outlier — its `catch`, `:739-760`, does not call `loadCoachSchedule` at all, unlike the other four). **Decision: keep a light dedup in scope, reframed honestly as a DRY cleanup, not a bug fix.** Extract a shared helper covering the common mutation+refresh+toast shape; each handler keeps its own loading-ref `finally` block and any pre-validation/dialog-close logic the helper has no reason to know about (`rescheduleActionId`, `coachCancelActionId`, `noShowActionId`; `submitCoachReschedule`'s own pre-validation and dialog-close-inside-try). Any error-key-to-message map the helper takes must apply `t()` to both the mapped and the fallback case — existing branches all do. **Add a one-line comment pointing future readers at `booking.store.js:360-368`** so a fourth pass doesn't refile this as a live bug. No new Vitest coverage asserting a "refresh rejection" is required or meaningful — writing one would mean mocking `loadCoachSchedule` to violate its own documented contract, a test that could only ever pass and would protect nothing.

### Finding 5 — Null `ROLES` claim escapes `getAuthoritiesSilently`'s catch — latent hardening, not a reachable live bug (severity corrected)

`JwtManagerImpl.getAuthoritiesSilently` (`:167-175` — re-anchored from the ledger's stale `:155-162`, correctly, by an earlier draft of this story):

```java
private Collection<SimpleGrantedAuthority> getAuthoritiesSilently(Map<String, Object> claims) {
    try {
        final String roles = (String) claims.get(ROLES);
        return jwtConfiguration.getMapper().readValue(roles, new TypeReference<List<SimpleGrantedAuthority>>() {});
    } catch (JsonProcessingException e) {
        log.error("Could not parse authorities from claims", e);
    }
    return List.of();
}
```

A null `ROLES` claim makes `ObjectMapper.readValue((String) null, ...)` throw `IllegalArgumentException`, not `JsonProcessingException` — escaping this catch. The one call site that matters, `setSkillarsProfileCookie` (`:136-153`) called from `refreshLoginToken` (`:95`), runs **after** `createAndSetJwt` (`:94`) has already written the JWT's `Set-Cookie` header.

**Two corrections found by story review:**
1. **Severity — this is latent defensive hardening, not a live, reachable bug.** `grep -rn "put(ROLES" src/main/` finds exactly one production call site, unconditional: `TokenCreatorImpl.java:49`, `claims.put(ROLES, toJson(principal.getAuthorities(), ...))`. `getAuthorities()` is never null, and the token is HMAC-verified before `refreshLoginToken` runs, so a client cannot forge a ROLES-absent token through any live path today. The fix is still correct and cheap to make — but do not rank it above the UI items on a "can 500 mid-login" urgency claim; it can't, today.
2. **Mechanism — the "half-applied" claim named the wrong durable half.** The JWT `Set-Cookie` header is written via `res.addHeader(...)` (a header buffer write, not a flush — nothing commits the response here), and it survives onto the eventual 500 only because `ApiAdvice` resolves via `@ResponseStatus` rather than `sendError()`/`reset()` — not because anything was "durably written" at that point. The actual irreversible half is upstream and unrelated to cookies: `LoginInfoService` is class-level `@Transactional` (`:31-32`), so `markLoginInfoAsConsumed`'s own transaction commits the OTP-consumed row **before** `refreshLoginToken` is even entered. That commit — not the cookie — is what can't be rolled back if `refreshLoginToken` then throws.

**Fix (unchanged in substance):**

```java
private Collection<SimpleGrantedAuthority> getAuthoritiesSilently(Map<String, Object> claims) {
    final Object rolesClaim = claims.get(ROLES);
    if (!(rolesClaim instanceof String roles)) {
        log.error("ROLES claim missing or not a string; treating as no authorities");
        return List.of();
    }
    try {
        return jwtConfiguration.getMapper().readValue(roles, new TypeReference<List<SimpleGrantedAuthority>>() {});
    } catch (JsonProcessingException e) {
        log.error("Could not parse authorities from claims", e);
    }
    return List.of();
}
```

**Test recipe — corrected.** `setSkillarsProfileCookie` is `private`, and `JwtManagerImplTest` uses no reflection anywhere — every existing test drives the public interface. Use the file's own established precedent for hand-building a raw token with an arbitrary claim shape, **`buildTokenWithCustomClaims`** (`JwtManagerImplTest.java:149`, already used at `:691`, `:717`), to build a token with no `ROLES` claim (but with `BUS_ID`/`GENDER` present — `authentication()` at `:161` calls `Gender.valueOf(...)` on it unconditionally, so omitting it NPEs for an unrelated reason), then call `refreshLoginToken` and assert: **pre-fix**, `IllegalArgumentException` is thrown and **no `skp` cookie is written at all** (not "resolves to ANONYMOUS" — the throw happens before that fallback is ever reached); **post-fix**, no exception and the `skp` cookie resolves to `ANONYMOUS` (matching the existing pattern at `JwtManagerImplTest.java:644-658`, which already pins the ANONYMOUS fallback for a different input shape).

### Finding 6 — No UPCOMING-gated coach action has a time-based gate, including the new "Report No-Show" button — USER DECISION: gate all three (scope corrected)

Verified directly, all three server methods have no time-based check: `BookingCompletionService.startSession` (`:49-60`), `initiateQuickComplete` (`:106-121`), `BookingService.recordNoShowPlayer` (`:838-858`). Its sibling, `recordNoShowCoach`, **does** have one, verified character-for-character at `:870-874`:

```java
if (Instant.now().isBefore(booking.getRequestedStartTime())) {
    throw new OperationNotAllowedException(
        "Cannot report a no-show before the booking's scheduled start time",
        Map.of("requested start time", booking.getRequestedStartTime()), BookingError.NO_SHOW_TOO_EARLY);
}
```

**USER DECISION (final): gate all three.** `NO_SHOW_TOO_EARLY` already exists (`BookingError.java:76`, wire key `booking.noShowTooEarly`) and — correcting an earlier draft of this finding — **does** already have localized copy in the four backend `.properties` bundles (`messages.properties:93`, `messages_en.properties:141`, `messages_de.properties:83`, `messages_fr.properties:104`), just not in the three frontend Quasar bundles, which is the real gap.

**Two things found by story review that must be fixed before this AC is implementable as stated:**

1. **Two existing tests break and are not mentioned in any earlier draft.** `BookingServiceTest`'s shared `makeBooking` helper (`:1663-1664`) sets `requestedStartTime` to `Instant.now().plusSeconds(7200)` for every booking it builds, including the fixture used by `recordNoShowPlayer_concurrentModification_throwsRetryableException` (`:1478-1495`) — once the time gate lands, that test throws `NO_SHOW_TOO_EARLY` before it ever reaches the optimistic-lock path its assertion checks. `SessionCompletionResourceIT`'s `insertUpcomingBooking` helper (`:455-465`) inserts a start time 24h in the future, used unconditionally by `setUp()`; `startSession_upcomingBooking_returns204AndStatusIsInProgress` (`:135-148`) would get 403 instead of 204. **Both fixtures need an explicit past `requestedStartTime`** (still satisfying `chk_bkg_end_after_start`) before this AC's new guards land, or these tests go red and AC9's "0 failures" cannot be met. Separately, `BookingCompletionServiceTest`'s own fixture (`:82`, `booking.setRequestedStartTime(Instant.now())`, no offset) survives only by not having an offset at all — give it an explicit small negative offset too, rather than leaving it to pass by the coincidence of `Instant.now()` being called twice in quick succession (this project's own documented `Instant.now()`-pair flake class, see `chk_bkg_end_after_start`'s history).

2. **The suggested wiring target for `recordNoShowCoach`'s pre-existing, still-missing frontend message does not exist.** An earlier draft of this finding said to "find and wire whichever existing page calls `recordNoShowCoach`." Verified: `grep -rn "recordNoShowCoach" src/frontend/src` returns exactly one line — the API function's own definition (`booking.api.js:89`). There is no store action, no page, no button. This is not an oversight: `skillars-deferred-63`'s own story explicitly documented the exclusion when it added this exact backend guard — *"No frontend change is needed or expected: `recordNoShowCoach` ... is called from no page in the frontend today — confirmed by grep ... so there is no existing `errorKey`-branching call site ... to extend."* **Corrected scope:** add the `booking.errors.noShowTooEarly` frontend key anyway (closing a real, long-standing gap for whenever a parent-facing no-show-report surface is eventually built), but do not go build that surface as part of this story — it is unbudgeted scope, and deferred-63's exclusion still holds.

**New error code, backend bundles now included:** add `BookingError.SESSION_NOT_YET_STARTED` → `booking.sessionNotYetStarted` (the enum's `switch` has no `default` arm, `:94-118`, so this cannot be half-applied — a missing case arm is a compile error). Add this key to **all four** backend `.properties` files (matching the existing `booking.noShowTooEarly` pattern in each) in addition to the three frontend bundles — an earlier draft of this story scoped only the frontend bundles for both new/changed keys, but the backend bundles are the established home for every other `BookingError` string and must not be skipped for the one new code this story adds.

**This decision explicitly reverses a "Never" from the immediately preceding story — say so, don't leave it implicit.** `spec-coach-booking-cancel-no-show.md`'s own Never/Design Notes state: *"Do not add a client-side 'too early' guard on no-show reporting — the backend intentionally has none for this endpoint ... adding one would be an unrequested product decision."* The project owner's decision here **legitimately** supersedes that — a product decision is exactly what a "Never" needs to be overridden — but note the reversal explicitly in the code (a one-line comment at the new guard, citing this story) so a future reader doesn't find a flat contradiction between two story files with no resolution.

**Frontend wiring, corrected:** `CoachCommandCenterPage.vue`'s `handleStartSession` (`:518-535`) and `handleQuickComplete` (`:537-551`) currently have **bare `catch {}` with no `err` binding** — the signature must change to `catch (err)` before an `errorKey` branch can be added at all (the underlying store actions do rethrow on failure, `booking.store.js:499-502,521-523,552-554`, so the branch is reachable once the signature changes). Add a client-side `new Date(booking.requestedStartTime) > new Date()` pre-check (immediate UX feedback, skip the API call) to `handleStartSession`, `handleQuickComplete`, and `confirmNoShowPlayer`, plus the corresponding `'booking.sessionNotYetStarted'` / `'booking.noShowTooEarly'` catch branches for the race case.

### Finding 7 — The published CI Docker image (`ci.yml`'s `build-and-push`) has no git-provenance assertion

`pr-build.yml`'s `docker-image` job has `assert-git-provenance.sh` wired in (`load: 'true'` at `:133`, assertion at `:142-143`). `ci.yml`'s `build-and-push` job — the one that pushes the image anyone actually runs, `commit-sha: ${{ github.sha }}` at `:248` — has no equivalent (`push: 'true'` only, no `load:`, so the action's own `'false'` default applies and the image is never loaded into this job's local daemon).

**USER DECISION (final): `docker pull` the just-pushed tag, then run the existing `assert-git-provenance.sh` unchanged.** The script already supports an optional `expected-commit-sha` argument (added by `skillars-deferred-150`'s own code-review response, confirmed by that script's header comment, not inferrable from `git log` alone since the commit that added the script and the commit that added the argument are the same squashed commit). The job already runs `docker login` to `ghcr.io` earlier in the same job (`:110-115`), so the pull needs no new credentials. The tag to pull is `ghcr.io/${{ github.repository }}:sha-${{ steps.sha.outputs.short }}` — always published by every run that executes (only `:latest` publication is conditional, per the `:latest`-ordering-guard step's own logic, confirmed by reading every branch of it, not just its comment).

**Citation correction found by story review:** the job's "Build and push Docker image" step runs from `:238` to `:255` (the file's own last line), not `:238-248` as an earlier draft said — `:248` is mid-step (the `commit-sha:` input), and the step continues through a multi-line `labels:` block. Insert the new step as the job's final step, after the whole "Build and push Docker image" step completes — not "after `:248`," which would corrupt that step's YAML.

**Accepted residual, documented rather than silently left for someone to discover:** this is a post-push **detector**, not a gate. The push step is the job's last step and already publishes the tag (including `:latest`, conditionally) in the same step; nothing declares `needs: build-and-push` anywhere that would block on this new step, and `deploy.yml` is manually triggered and gated only on tag existence. A failing assertion here produces a red CI job while the already-pushed, unverified image remains pullable and deployable. Closing that gap properly would mean restructuring to `push: 'false'` + `load: 'true'` + assert + a separate push step (or an equivalent pre-push gate) — a larger change than this AC's scope. Documented here so it is a disclosed tradeoff, not a silent gap.

### Finding 8 — Required branch-protection status checks on `master` are unversioned, live-only GitHub config

Confirmed via `gh api repos/tenjohokwen/skillars/rulesets/20583638`: ruleset id `20583638`, name `NoDirectPush`, target `branch`, four rules (`deletion`, `non_fast_forward`, `pull_request`, `required_status_checks` with contexts `build` + `docker-image`). No committed artifact anywhere in the repo. **Correction found by story review on attribution, which if anything strengthens this AC's case rather than weakening it:** `skillars-deferred-149`'s own completion notes applied only the `required_status_checks` rule out-of-band; the ruleset and its other three rules pre-date that story with no traceable origin in any story file at all — so the "no versioned artifact" problem is older and wider than the one rule deferred-149 touched.

**USER DECISION (final): commit the ruleset as code, with an applying workflow — not a passive assertion.**

New file `.github/rulesets/no-direct-push.json` — the live ruleset JSON with fields stripped per what the write endpoints actually accept, **not** the narrower strip list an earlier draft proposed. Two fields beyond `id`/`node_id`/`created_at`/`updated_at`/`_links` need handling: `bypass_actors: []` is writable (keep it, since it matches the live value and is a deliberate choice either way) and `current_user_can_bypass: "never"` is **read-only** — it must be stripped too, or the `PUT`/`POST` call is rejected outright.

New manually-triggered workflow, `.github/workflows/apply-branch-ruleset.yml`:

```yaml
name: Apply branch-protection ruleset
on:
  workflow_dispatch:
jobs:
  apply:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<pinned-sha>
      - name: Apply or update ruleset
        env:
          GH_TOKEN: ${{ secrets.RULESETS_PAT }}
        run: |
          set -euo pipefail
          existing_id=$(gh api "repos/${{ github.repository }}/rulesets" \
            --jq 'first(.[] | select(.name=="NoDirectPush") | .id) // empty') || existing_id=""
          if [ -n "$existing_id" ]; then
            gh api --method PUT "repos/${{ github.repository }}/rulesets/${existing_id}" --input .github/rulesets/no-direct-push.json
          else
            gh api --method POST "repos/${{ github.repository }}/rulesets" --input .github/rulesets/no-direct-push.json
          fi
```

**Two corrections to the original design, both found by story review:**

1. **A `permissions: administration: write` block (the original design) is invalid YAML for GitHub Actions and will fail the whole workflow to parse — this is not a fallback situation, it is the only non-viable path.** The Actions `permissions:` key accepts a fixed, closed set of scopes (`actions, attestations, checks, contents, deployments, discussions, id-token, issues, models, packages, pages, pull-requests, repository-projects, security-events, statuses`) — `administration` is a GitHub-App/fine-grained-PAT **repository** permission, not an Actions token scope, and `GITHUB_TOKEN` cannot hold it under any `permissions:` spelling. Confirmed against this repo's own precedent: every existing `permissions:` block in `.github/workflows/` uses only `contents: read`/`packages: write`. **Use a dedicated PAT secret (`RULESETS_PAT`, with the fine-grained `Administration` repository permission) from the start** — there is no `GITHUB_TOKEN`-based fallback to attempt first.

2. **The jq lookup needed a shape fix, independently re-verified against real shell semantics, not just assumed.** `... | head -1` under `set -euo pipefail` is not actually unsafe for the empty-match case (`jq`/`head` both exit 0 on an empty stream, so `[ -n "$existing_id" ]` degrades safely) — but a **failing** `gh api` call (404, SSO challenge, rate limit, or exactly the 403 a wrong/insufficient PAT would produce) aborts at the assignment under `pipefail` before the create-if-absent branch can run, and chaining through `head -1` adds a SIGPIPE/141 surface on top. The `--jq 'first(... ) // empty'` form above, with an explicit `|| existing_id=""` fallback, avoids both; this also makes the loop fail loudly (not silently) on a genuine auth problem, which is the outcome you want when verifying PAT permissions empirically.

**Verify by execution, per standing project convention:** run the workflow once (ideally against the ruleset's own current unchanged state first, as a no-op check) and confirm the PAT actually has sufficient permission before considering this AC done.

### Explicitly NOT picked up (verified, excluded — do not re-add to this story)

- **`findQualifyingCompletedBookings`'s `limit 50` safety cap** — **USER DECISION:** leave deferred, per its own prior note ("revisit if the truncation warning ever fires" — it hasn't; an earlier draft of this story misquoted this as "**only** if," inserting an exclusivity the ledger never stated — corrected here).
- **fr-FR/de-DE native-speaker review, and `skillars-deferred-140` AC4.3's manual browser walkthrough** — human-only, no code change possible in this session.
- **`[deferred-19]` reset-mean drift** — monitoring/variance tracking per the ledger's own text, not a code fix.
- **The `/dashboard` `DEFAULT_ROUTE`-has-no-nav-link gap** (deferred-141 section) and **`markUsedByTokenHash`/`markAllUsedByUserId` unguarded against transient DB failures** (deferred-143 section) — both genuinely still open in `deferred-work.md`, but both are long-standing, already-reasoned-through exclusions from `skillars-deferred-150`'s own Dev Notes (*"genuinely open, not human-only, leave alone"* / *"only worth it if observed in production"*), not new findings this story surfaces. Listed here explicitly, rather than silently, so a future pass's exhaustiveness check doesn't need to re-derive why they're excluded.
- **Everything else in `deferred-work.md`'s `:3449-:3756` range** (code reviews of skillars-deferred-141/142/143, the manual-review-during-143 section, and skillars-deferred-145) — **correcting a scope-boundary imprecision an earlier draft of this story had** (it said "lines 1–3271 are fully pruned" while separately claiming its own mined scope started at 2026-10-02, i.e. line `:3407` — the two numbers didn't line up). Measured directly: of the 17 bullets across that exact range, **15 already carry an explicit `[CLOSED by skillars-deferred-144 ...]` tag** (confirmed by grep: `grep -c "\[CLOSED by"` over that range returns 15; `grep -c "^- \*\*"` returns 17). The only two that don't are the pair already named above. **A separate review pass flagged this section as "0 bullets closed, 17 open" and recommended re-auditing or deleting the exhaustiveness claim — that specific count is wrong** (verified directly, not taken on the review's word); its own cited examples (the `skp` `URLEncoder` form-encoding mismatch, `JwtManagerImpl.deleteLoginToken` not clearing `skp`) are among the 15 already-closed bullets, both closed by `skillars-deferred-144`. No further action needed in this range beyond the two named exclusions above.

---

## Acceptance Criteria

1. **Coach reviews self-service.** New `CoachReviewsPage.vue` at route `coach/reviews` (with `meta: { requiresAuth: true, requiresCoach: true }`, matching every other `coach/*` route) lists the calling coach's own reviews (paginated) with a response composer for a review only when `coachResponseBody == null && moderationStatus === 'APPROVED'` — not on `coachResponseBody == null` alone. Non-`APPROVED` own-reviews render dedicated copy (not hidden, not the public-page's APPROVED-only treatment). A missing-`CoachProfile` 404 renders an incomplete-profile state, not a raw error banner. Wired to two new `reviews.api.js` functions. Linked from both `routes.js` and a new `MainLayout.vue` Coach-section nav item.

2. **Coach session-pack offer management.** New `CoachSessionPackTiersPage.vue` at route `coach/session-pack-tiers` (same `meta` requirement) shows the coach's one current active offer (if any) plus a stable-sorted read-only history of replaced ones — not a multi-row catalog with per-row deactivate, which the backend cannot support (at most one active tier per coach, DB-enforced). Creating a new offer is framed and confirmed as "replace your current offer." Create-form validation enforces `totalPrice / sessionCount >= 0.005` client-side to avoid a 500 from the backend's own rounding+CHECK-constraint interaction. `payment.store.js` gains `createSessionPackTier`/`deactivateSessionPackTier` actions (newly imported — they are not already imported) following the store's `LOADING_KEYS`/`loading.X`/`error.X` convention. A manual-extend widget (purchase-id text input) states its 14-day/one-shot constraints in its own help text and shows one generic failure message, not an attempt to branch the backend's un-branchable 409. Linked from `routes.js` and a new nav item.

3. **Coach report branding — backend fix + UI.** `ReportGenerationService.saveBranding`'s ownership check is fixed to compare against the caller's own user id (not the `CoachProfile` UUID), with a test that exercises the real `FileStorageService` path rather than a full mock. `CoachBrandingResponse` gains a raw `logoKey` field (alongside `logoSignedUrl`) so a colour-only edit can resend the existing key instead of silently clearing it. New coach-only section on `ProfilePage.vue`, which calls `authStore.fetchCoachTier()` on mount and three-states its render (loading/locked/unlocked) rather than reading a possibly-`null` ref directly; gated on `authStore.coachTier === 'ACADEMY'` with an upsell state otherwise (reusing `security.featureGated`). New `EditCoachBrandingDialog.vue` mirrors `EditCoachPhotoDialog.vue`'s upload flow (`entity: 'coach_branding'`), with client-side hex-colour validation and a defensive `security.featureGated` catch on save.

4. **Command-center handler dedup (not a bug fix — see Finding 4).** All five `CoachCommandCenterPage.vue` handlers route their common mutation→refresh→toast shape through a shared helper, each retaining its own loading-ref `finally` and any handler-specific pre-validation/dialog-close logic. A one-line comment at the dedup site points to `booking.store.js:360-368`'s own contract comment, so this is not refiled as a live defect a fourth time. No new test asserts a "refresh rejection" — that would require violating `loadCoachSchedule`'s documented never-rethrow contract and would protect nothing.

5. **Null-ROLES auth hardening (latent, not live — see Finding 5).** `JwtManagerImpl.getAuthoritiesSilently` degrades to `List.of()` when the `ROLES` claim is missing or not a string. New `JwtManagerImplTest` case uses `buildTokenWithCustomClaims` (not the private method directly) to build a token with no `ROLES` claim but with `BUS_ID`/`GENDER` present, asserting pre-fix behavior is `IllegalArgumentException` with **no** `skp` cookie written, and post-fix behavior is no exception with `skp` resolving to `ANONYMOUS`.

6. **UPCOMING-action time gate (scope corrected — see Finding 6).** `BookingCompletionService.startSession`, `initiateQuickComplete`, and `BookingService.recordNoShowPlayer` all reject with a time-based guard (`BookingError.SESSION_NOT_YET_STARTED` for the first two, the existing `NO_SHOW_TOO_EARLY` for the third) when `Instant.now().isBefore(booking.getRequestedStartTime())`. Two pre-existing tests (`BookingServiceTest`'s `recordNoShowPlayer_concurrentModification_throwsRetryableException`, `SessionCompletionResourceIT`'s `startSession_upcomingBooking_returns204AndStatusIsInProgress`) have their fixtures updated to a past `requestedStartTime` so they keep passing; `BookingCompletionServiceTest`'s zero-offset fixture gets an explicit past offset too. `booking.sessionNotYetStarted` is added to all three frontend bundles **and** all four backend `.properties` bundles; `booking.noShowTooEarly` is added only to the three frontend bundles (it already exists backend-side). `handleStartSession`/`handleQuickComplete` gain an `err` binding on their currently-bare `catch {}` plus the new error-key branches; `confirmNoShowPlayer` gains the same. This decision explicitly, in-code, overrides `spec-coach-booking-cancel-no-show.md`'s prior "Never add a client-side too-early guard" note. No parent-facing UI is built for `recordNoShowCoach` — it has zero UI callers today, by `skillars-deferred-63`'s own documented and still-valid design decision, not an oversight this story closes.

7. **CI git-provenance on the published image (citation corrected — see Finding 7).** `ci.yml`'s `build-and-push` job pulls the just-pushed `sha-<short>` tag and runs the existing, unmodified `assert-git-provenance.sh` against it with `${{ github.sha }}` as the expected commit, as the job's final step (after the complete "Build and push Docker image" step, which runs through the file's own last line). Documented as a post-push detector, not a pre-push gate — a disclosed, not silent, residual.

8. **Branch-protection ruleset as code (fixed — see Finding 8).** `.github/rulesets/no-direct-push.json` is committed, matching the live ruleset's `name`/`target`/`enforcement`/`conditions`/`rules`/`bypass_actors`, with the read-only `current_user_can_bypass` field also stripped alongside the previously-identified server-assigned fields. A new manually-triggered workflow applies it via `gh api`, authenticated with a dedicated `RULESETS_PAT` secret from the start (no `GITHUB_TOKEN`/`permissions:` attempt — `administration` is not a valid Actions permissions scope, confirmed against the spec and this repo's own precedent), using a robust `--jq 'first(...) // empty'` lookup-by-name rather than a hardcoded id or a `head -1` pipe.

9. **Testing & regression.** Full targeted backend suite for every touched Java class passes (0 failures) — including the two pre-existing tests named in AC6 that need fixture updates, not just new tests. Full targeted frontend suite (Vitest) for every touched/new Vue file passes; note that `CoachCommandCenterPage.vue` has no existing spec file today (37 specs exist, none under `pages/coach/`), so AC4's dedup needs its *first* coverage, and the Vitest job itself is opt-in CI (manual dispatch or the `frontend-tests` PR label) — apply that label or dispatch the job manually so AC9's frontend requirement is actually checked by CI, not just locally. No `mvn verify` run locally, per standing project convention. After implementation, delete the now-resolved bullets this story closes from `deferred-work.md` in the same commit.

---

## Tasks / Subtasks

- [x] Task 1: Coach reviews self-service page (AC: 1)
  - [x] Add `listMyReviews`/`submitCoachResponse` to `reviews.api.js`
  - [x] Build `CoachReviewsPage.vue`: list, composer gated on `coachResponseBody == null && moderationStatus === 'APPROVED'`, dedicated copy for non-APPROVED own-reviews, incomplete-profile empty state for the no-`CoachProfile` 404
  - [x] Add route `coach/reviews` (with the standard `coach/*` `meta` block) + `MainLayout.vue` nav item
  - [x] New i18n copy for the composer, empty states, and pending/blocked review copy, all three locales (reused existing `reviews.pendingModeration`/`reviews.blocked` for non-APPROVED own-review copy rather than inventing new strings)
  - [x] Component-spec coverage for list render, gated composer visibility, response submit, pagination, and the 404 empty state (9 tests, `CoachReviewsPageSpec.js`)

- [x] Task 2: Coach session-pack offer management page (AC: 2)
  - [x] Add `createSessionPackTier`/`deactivateSessionPackTier` imports + actions to `payment.store.js`, registering their keys in `LOADING_KEYS` (also added `extendSessionPack`, needed by the same page's manual-extend widget — not separately listed in this subtask but required by AC2's own text)
  - [x] Build `CoachSessionPackTiersPage.vue`: current-offer + history model (not a catalog), replace-confirmation on create, client-side price-floor validation, manual-extend widget with explicit constraint help text and a single generic failure message
  - [x] Add route `coach/session-pack-tiers` (standard `meta`) + nav item
  - [x] New i18n copy for all of the above, three locales
  - [x] Component-spec coverage for current-offer display, history sort, replace-confirmation flow, validation rejection, and the extend widget's generic-failure path (8 tests, `CoachSessionPackTiersPageSpec.js`)

- [x] Task 3: Coach report branding — backend fix + UI (AC: 3)
  - [x] Fix `ReportGenerationService.saveBranding`'s `assertOwnership` call to use the caller's user id, not the `CoachProfile` UUID; thread it through `CoachBrandingResource`
  - [x] Add a real-`FileStorageService`-exercising test for `saveBranding` (not a full mock) proving the fixed ownership check actually passes for a real caller
  - [x] Add `logoKey` to `CoachBrandingResponse`
  - [x] Add coach-only branding section to `ProfilePage.vue`; call `authStore.fetchCoachTier()` on mount; three-state render (loading/locked/unlocked)
  - [x] Build `EditCoachBrandingDialog.vue` (presign/upload/confirm/save, resending the existing `logoKey` on a colour-only edit, client-side hex validation, defensive `security.featureGated` catch)
  - [x] Decide and document whether `coach_branding` needs an `OWNER_BOUND_ENTITIES` entry — decided yes, added alongside `coach_profile` for the same caller-owns-their-own-asset shape
  - [x] Component-spec coverage including the non-ACADEMY locked-state render and the colour-only-edit-preserves-logo case (6 tests `EditCoachBrandingDialogSpec.js` + 3 new tests in `ProfilePageSpec.js`; backend: 18 tests `ReportGenerationServiceTest`, BUILD SUCCESS)

- [x] Task 4: Command-center handler dedup — DRY cleanup, not a bug fix (AC: 4)
  - [x] Extract shared mutation+refresh+toast helper in `CoachCommandCenterPage.vue`; preserve each handler's own loading-ref/dialog-state logic
  - [x] Apply `t()` consistently to both mapped and fallback error messages
  - [x] Add the `booking.store.js:360-368` pointer comment
  - [x] First-ever `CoachCommandCenterPage` component-spec coverage, scoped to the dedup's actual behavior (not a mocked-contract-violation test) (10 tests, `CoachCommandCenterPageSpec.js`)

- [x] Task 5: Null-ROLES guard — latent hardening (AC: 5)
  - [x] Fix `getAuthoritiesSilently` to degrade on a missing/non-string `ROLES` claim
  - [x] New `JwtManagerImplTest` case via `buildTokenWithCustomClaims`, asserting the corrected pre-fix/post-fix observables
  - [x] Re-run full `JwtManagerImplTest` for regression (38/38 pass)

- [x] Task 6: UPCOMING-action time gate (AC: 6)
  - [x] Add `BookingError.SESSION_NOT_YET_STARTED`, both switch and all four backend `.properties` files
  - [x] Add the time check to `startSession`, `initiateQuickComplete`, `recordNoShowPlayer`
  - [x] Fix `BookingServiceTest`, `SessionCompletionResourceIT`, `BookingCompletionServiceTest` fixtures to use a past `requestedStartTime` where needed
  - [x] Add `booking.sessionNotYetStarted` (3 frontend + 4 backend bundles) and `booking.noShowTooEarly` (3 frontend bundles only) i18n keys
  - [x] Fix `handleStartSession`/`handleQuickComplete`'s bare `catch {}` to `catch (err)`; add client-side pre-checks + catch branches to those two plus `confirmNoShowPlayer`
  - [x] One-line in-code note disclosing the override of `spec-coach-booking-cancel-no-show.md`'s prior "Never"
  - [x] New backend tests for all three gated methods; frontend component-spec coverage for the client-side pre-checks (full `booking` module backend regression: 0 failures across all classes; +5 CoachCommandCenterPageSpec AC6 tests)

- [x] Task 7: CI git-provenance on published image (AC: 7)
  - [x] Add the pull+assert step as `ci.yml`'s `build-and-push` job's final step (after the complete push step, not mid-step) — `actionlint`/`shellcheck` clean
  - [ ] Verify empirically once this story's branch is pushed to `master` — corrected per review: `ci.yml` is `on: push: branches: [master]` + `workflow_dispatch` only, with no `pull_request` trigger (AC7's own text: "a post-push detector, not a pre-push gate"), so the new step cannot and will not run on this story's PR itself; the only real verification point is the `build-and-push` job's next run after merge (cannot be verified from a local dev session; flagging for that post-merge CI pass)

- [x] Task 8: Branch-protection ruleset as code (AC: 8)
  - [x] Commit `.github/rulesets/no-direct-push.json`, stripping `id`/`node_id`/`created_at`/`updated_at`/`_links`/`current_user_can_bypass` (also stripped `source`/`source_type`, read-only/derived-from-endpoint fields the POST/PUT bodies don't accept either) — pulled live via `gh api repos/tenjohokwen/skillars/rulesets/20583638`
  - [x] Add `apply-branch-ruleset.yml` using `RULESETS_PAT` from the start and the robust jq lookup — `actionlint`/`shellcheck` clean
  - [ ] Create the PAT (fine-grained, `Administration: write` repo permission) and store it as `RULESETS_PAT`; empirically verify the workflow succeeds against the ruleset's own current (unchanged) state — **owner action required**: fine-grained PATs can only be created via the GitHub web UI (no `gh`/API path), and `workflow_dispatch` needs this workflow file present on a branch GitHub recognizes first. Flagging rather than attempting — this is an account-level credential/secret action outside a local dev session's scope.

- [x] Task 9: Final verification (AC: 9)
  - [x] Full targeted backend regression across every touched class, including the two fixed pre-existing test fixtures — `booking`, `filestorage`, `development`, `security` modules all green (0 failures/errors); `mvn compile`/`test-compile` clean
  - [x] Full targeted frontend regression (Vitest) across every touched/new file — 291/291 pass; `npx quasar build` (prod bundle + `vue/no-bare-strings-in-template` lint) clean. Dispatching the opt-in `frontend-tests` CI job / applying its PR label is a release-workflow step for after this PR is opened — flagged, not done from this session.
  - [x] Update `deferred-work.md` per the deletion list in Dev Notes


### Review Findings

_From `/bmad-code-review` 2026-10-14 — three parallel layers (Blind Hunter diff-only, Edge Case Hunter diff+repo, Acceptance Auditor diff+spec+repo), cross-deduplicated, every surviving finding re-verified at its cited line against the working tree. 2 decision-needed, 10 patch, 3 deferred, 6 dismissed as false positives or already-handled._

- [x] [Review][Decision] Client-side time pre-check trusts the device clock with zero skew tolerance, and fails open on an unparseable start time — `isBeforeScheduledStart` (`CoachCommandCenterPage.vue:523-525`) and the inline no-show check (`:729`) compare `new Date(booking.requestedStartTime)` against the browser clock. A coach whose laptop clock is slow (NTP drift after sleep) is hard-blocked from Start Session, Quick Complete AND Report No-Show even though the server would allow all three; there is no grace window and no override. In the other direction `new Date(undefined)` is `Invalid Date` and `NaN > now` is `false`, so a booking with no `requestedStartTime` sails past both guards. Separately, the server-side gate is also zero-tolerance on `startSession`/`initiateQuickComplete`, which carry none of the financial-forfeiture consequence that motivated AC6 for no-show. Needs a product call: skew tolerance, a grace window on the two completion-path actions only, or client pre-check removal in favour of the authoritative server guard. — **Resolved: owner chose "add a grace window."** `SESSION_START_GRACE_MS` (2 min) added to `isBeforeScheduledStart`, shared by all three call sites (Start Session, Quick Complete, no-show, the last via a parameter object rather than the raw inline comparison it used before); an unparseable start time now fails closed (`Number.isNaN` guard returns `true`, i.e. blocked client-side) instead of silently sailing through. Backend zero-tolerance left untouched — out of scope for this decision.
- [x] [Review][Decision] `deactivateSessionPackTier` is wired into `payment.store.js` (import `:7`, `LOADING_KEYS` `:36`, action `:131-143`) with zero call sites anywhere in the frontend — recreating one layer up the exact "defined but never called" anti-pattern AC2 exists to remove. Functionally this leaves a coach able to *replace* an offer but with no way to *withdraw* one: to stop selling they must create an offer they do not want. Either expose a withdraw control or drop the dead action. — **Resolved: owner chose "add a withdraw control."** `CoachSessionPackTiersPage.vue` gained a "Withdraw offer" button next to the active tier (only rendered when one exists — still exactly one affordance, not per-row, so AC2's no-catalog constraint holds) plus a confirm dialog mirroring the existing replace-confirmation pattern, calling the existing `deactivateSessionPackTier` action. The old `CoachSessionPackTiersPageSpec.js` assertion forbidding any "deactivate" text was itself one of this review's own deferred weak-spec findings (§Defer, 3rd bullet) — replaced with assertions for exactly one withdraw control and its confirm→call flow.

- [x] [Review][Patch] Prettier gate fails on 8 files this diff touches — blocks `build-and-push` [src/frontend/src/{api/reviews.api.js,i18n/en-US/index.js,i18n/fr-FR/index.js,layouts/MainLayout.vue,pages/coach/CoachReviewsPage.vue,pages/coach/CoachSessionPackTiersPage.vue,pages/coach/__tests__/CoachReviewsPageSpec.js,components/profile/__tests__/EditCoachBrandingDialogSpec.js}] — fixed via `prettier --write` on the full touched-file set (including every file edited by this review response) after all other fixes landed.
- [x] [Review][Patch] `loadCoachBranding` has no `catch`, so a failed branding GET renders the *unlocked* card with `coachBranding` null — the next colour-only save then resends `logoKey: null` and silently wipes the stored logo with a success toast, defeating the round-trip AC3 added to prevent exactly that [src/frontend/src/pages/ProfilePage.vue:535-545] — fixed: `loadCoachBranding` now catches the failure into a new `coachBrandingError` ref that gates the edit affordance behind a retry-able error state, mirroring the existing `coachProfileError` pattern immediately below it. New `profile.coachBrandingLoadError` key, all three locales.
- [x] [Review][Patch] A failed "Load more" sets `loadError` and the template tests it ahead of the list branch, replacing every already-rendered review and every typed-but-unsubmitted draft with a bare error line; `loadError` is never reset and there is no retry [src/frontend/src/pages/coach/CoachReviewsPage.vue:26,168-177] — fixed: `loadMore` now sets a separate `loadMoreError` ref (reset on each retry attempt) rendered inline next to the Load More button; the already-rendered list and drafts are no longer touched by a pagination failure.
- [x] [Review][Patch] A failed tier fetch is swallowed by the store and renders as "no active offer", so the CTA reads "create" and the replace-warning is suppressed by `v-if="activeTier"` — the coach then replaces a live offer with no confirmation, which is the one destructive action AC2 required to be confirmed [src/frontend/src/pages/coach/CoachSessionPackTiersPage.vue:26-37,94-99,182-184] — fixed: the page now checks `paymentStore.error.sessionPackTiers` (already set by the store on failure) and renders a distinct error + retry state ahead of the current-offer block, so a failed fetch can never be misread as "no active offer."
- [x] [Review][Patch] `payment.myTiers.priceFloorError` is unreachable dead code — the submit button is `:disable="!canSubmitCreate"`, the identical condition the guard tests, so the message added to all three locales can never render and the coach sees only a greyed-out button with no explanation [src/frontend/src/pages/coach/CoachSessionPackTiersPage.vue:137,196-198] — fixed: the price-floor condition is now a reactive `priceFloorInvalid` computed shown live under the form fields as the coach types, independent of the (still-disabled) submit button; `submitCreate`'s now-genuinely-unreachable early-return guard was removed rather than left as dead code.
- [x] [Review][Patch] Non-APPROVED own-reviews reuse author-perspective strings — a coach reading a parent's blocked review of them is told "**Your** review was removed by moderation", in all three locales. AC1 and corrected Finding 1 explicitly ruled this reuse out and called for new copy [src/frontend/src/pages/coach/CoachReviewsPage.vue:55,61] — fixed: new coach-perspective `reviews.ownReviewPendingModeration`/`reviews.ownReviewBlocked` keys (all three locales), wired in place of the reused `reviews.pendingModeration`/`reviews.blocked` strings; `CoachReviewsPageSpec.js`'s two assertions updated to match.
- [x] [Review][Patch] `apply-branch-ruleset.yml`'s `|| existing_id=""` collapses "listing failed" and "ruleset absent" into the same empty string, so a 5xx or a narrowed PAT makes the workflow POST a *second* `NoDirectPush` ruleset and report success; the lookup is also unpaginated [.github/workflows/apply-branch-ruleset.yml:31-37] — fixed: dropped the `|| existing_id=""` fallback so a real `gh api` failure now fails the workflow under `set -e`, while jq's `// empty` still cleanly yields an empty string (exit 0) for the genuine not-found case; added `--paginate`. `actionlint`/`shellcheck` clean.
- [x] [Review][Patch] Fractional `sessionCount` (e.g. `2.5`) passes the client guard — the input has `min="1"` but no `step="1"`, and `canSubmitCreate` only checks truthiness and the price floor — then Jackson's default `ACCEPT_FLOAT_AS_INT` truncates it to `2`, creating a tier with a count and derived `pricePerSession` the coach never entered [src/frontend/src/pages/coach/CoachSessionPackTiersPage.vue:109-117,175-180] — fixed: added `step="1"` to the input plus a `sessionCountInvalid` computed (`Number.isInteger`) folded into `canSubmitCreate`, with its own live inline message (`payment.myTiers.sessionCountWholeError`, all three locales).
- [x] [Review][Patch] Task 7.2 directs the owner to verify AC7 "on this story's own PR run", but `ci.yml` is `on: push: branches: [master]` + `workflow_dispatch` with no `pull_request` trigger — the new step cannot run on a PR, risking AC7 being recorded as CI-verified when nothing ran [.github/workflows/ci.yml:3-5] — fixed: Task 7.2's own text corrected below to point at the post-merge `master` push run instead of a nonexistent PR run (documentation-only; `ci.yml` itself is unchanged, consistent with AC7's "post-push detector, not a pre-push gate" design).
- [x] [Review][Patch] `errorMessages[errorKey]` is an unguarded object index on a server-supplied key — `toString`/`constructor`/`valueOf` resolve to truthy `Object.prototype` members and get passed to `t()`. Practically unreachable; `Object.hasOwn` costs nothing [src/frontend/src/pages/coach/CoachCommandCenterPage.vue:646] — fixed: guarded with `Object.hasOwn(errorMessages, errorKey)`.

- [x] [Review][Defer] No end-to-end integration test pins the `coach_branding` id space across all four places that must agree on it (`CoachBrandingResource`, `ReportGenerationService`, `StorageResource`'s `OWNER_BOUND_ENTITIES`, and the dialog's `entityId`) — deferred, the AC3 unit test was empirically confirmed to go red when the fix is reverted, so this is added assurance rather than an open defect
- [x] [Review][Defer] `ProfilePage.vue` fetches `logoSignedUrl` and never renders it — a coach who has uploaded a logo cannot see it from the profile page, only from inside the dialog [src/frontend/src/pages/ProfilePage.vue:385-403] — deferred, cosmetic/incomplete rather than wrong
- [x] [Review][Defer] Two weak spec assertions: `expect(wrapper.text()).not.toContain('deactivate')` runs against untranslated i18n keys so it would pass even with a wired deactivate button [CoachSessionPackTiersPageSpec.js:72], and `global.fetch` is stubbed `{ ok: true }` where the component never reads `ok` [EditCoachBrandingDialogSpec.js:22] — deferred, test-quality not product defects
---

## Dev Notes

- **This story went through an independent adversarial review (`story-review.md`, 2026-10-14) before implementation began — read Findings 3 and 4 as corrected, not as originally drafted.** The review's two BLOCKING catches (AC3's backend ownerId mismatch, AC4's unreachable premise) are both load-bearing for how those ACs must be built; do not revert to the pre-review design for either. One of the review's own findings (§2c, "the ledger exhaustiveness claim is contradicted") was itself independently re-verified and found to be a false positive — 15 of the 17 bullets it counted as open are already `[CLOSED by skillars-deferred-144 ...]`, confirmed by direct grep, not taken on the review's count. Re-verify claims from any review (including this one) against source before trusting them, rather than treating "CONFIRMED" or "CONTRADICTED" labels as settled.
- **AC3's backend fix is in scope for this story, not a separate follow-up — it blocks the AC entirely if skipped.** `saveBranding`'s `assertOwnership` call has never executed against a real `FileStorageService` in any existing test; don't let the new test for it be another full mock, or the bug ships again with green tests.
- **AC4 is explicitly NOT a bug fix.** If time pressure forces a cut across this story, AC4 is the safest one to defer entirely (pure code-quality, no user-facing defect), unlike AC3/AC5/AC6 which touch real correctness or money paths.
- **AC2's "no catalog" constraint is a hard DB fact (`idx_spt_one_active_per_coach`), not a design preference** — do not build a multi-active-tier UI and discover the unique-index conflict at implementation time.
- **AC6 is the highest-stakes item in this bundle** — Report No-Show's financial-forfeiture consequence (full fee, no refund, no coach-reliability-strike counterweight — `CancellationRefundService.onPlayerNoShow`) makes a time-gate bypass materially worse than the others. Prioritize it if sequencing matters.
- **AC6's two test-fixture fixes are mandatory, not optional polish** — AC9 demands 0 failures, and both named tests break without them. Find them by the exact method names given, not by re-deriving from scratch (both fixtures were re-read in full during story review).
- **`deferred-work.md` cleanup, same commit as the fix:** delete the three bullets under "bmad-quick-dev coach UI-gap scan (2026-10-14)"; both bullets under "code review of spec-coach-booking-cancel-no-show (2026-10-14)"; the git-provenance bullet under "code review of skillars-deferred-150 (2026-10-09)" (leave `findQualifyingCompletedBookings` in that section alone); the required-status-checks bullet under "code review of skillars-deferred-149 (2026-10-08)". Do **not** delete the `/dashboard`/`markUsedByTokenHash` bullets named in "Explicitly NOT picked up" above — they remain genuinely open and unrelated to this story's scope.
- **General:** every citation in this version was independently re-verified against HEAD `34f9726a` during story review, including several range corrections (noted inline per-finding above) to citations an earlier draft had re-anchored but not quite precisely. Re-verify line numbers again at actual implementation time regardless — this codebase changes fast enough that even same-day citations drift, as both this story's own drafting and its review each independently demonstrated.

### Project Structure Notes

- New frontend files: `src/frontend/src/pages/coach/CoachReviewsPage.vue`, `src/frontend/src/pages/coach/CoachSessionPackTiersPage.vue`, `src/frontend/src/components/profile/EditCoachBrandingDialog.vue`.
- Changed frontend files: `src/frontend/src/api/reviews.api.js`, `src/frontend/src/stores/payment.store.js`, `src/frontend/src/pages/ProfilePage.vue`, `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`, `src/frontend/src/router/routes.js`, `src/frontend/src/layouts/MainLayout.vue`, `src/frontend/src/i18n/{en-US,fr-FR,de-DE}/index.js`.
- Changed backend files: `JwtManagerImpl.java`, `BookingError.java`, `BookingCompletionService.java`, `BookingService.java`, `ReportGenerationService.java`, `CoachBrandingResource.java`, `CoachBrandingResponse.java`, and all four `src/main/resources/i18n/messages*.properties` files.
- Changed test files: `JwtManagerImplTest.java`, `BookingServiceTest.java` (fixture fix), `SessionCompletionResourceIT.java` (fixture fix), `BookingCompletionServiceTest.java` (fixture fix), `ReportGenerationServiceTest.java` (new real-path-exercising test), plus new Vitest specs for all three new/changed Vue files (`CoachCommandCenterPage` has none today).
- Changed infra/CI files: `.github/workflows/ci.yml`, new `.github/rulesets/no-direct-push.json`, new `.github/workflows/apply-branch-ruleset.yml`. No change to `.github/scripts/assert-git-provenance.sh` or `pr-build.yml`.
- New required secret: `RULESETS_PAT` (fine-grained PAT, `Administration: write` on this repo) — must exist before Task 8's workflow can be verified by execution.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — Findings 1–8's originating sections, as dated in each Finding above.
- [Source: `_bmad-output/implementation-artifacts/story-review.md`, 2026-10-14] — the independent adversarial review this version (v1.1) responds to; see Change Log for what was applied vs. independently re-checked and found to be a false positive.
- [Source: `src/frontend/src/stores/booking.store.js:360-368,453-467`] — the `loadCoachSchedule` never-rethrow contract that invalidates the original Finding 4.
- [Source: `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java:68-70,149,334-341`, `Principal.java:20,181`] — the id-space mismatch behind Finding 3's BLOCKING backend bug.
- [Source: `src/frontend/src/stores/auth.store.js`, full read] — `coachTier`'s single caller and its absence from `ProfilePage.vue`, behind Finding 3's second BLOCKING issue.
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:154-216`, `V138__baseline_schema.sql:3910`] — the one-active-tier DB constraint behind Finding 2's re-model.
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java:1663-1664,1478-1495`, `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java:455-465`] — the two pre-existing tests Finding 6's gate breaks.
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-63-product-directed-fairness-and-consistency-fixes.md:152-156`] — `recordNoShowCoach`'s documented zero-UI-caller design decision, behind Finding 6's corrected scope.
- [Source: `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/TokenCreatorImpl.java:49`] — the unconditional ROLES-claim write behind Finding 5's corrected severity.
- [Source: `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java:149,644-658,691,717`] — the `buildTokenWithCustomClaims` precedent Finding 5's corrected test recipe uses.

---

## Dev Agent Record

### Completion Notes

All nine tasks implemented and validated. Summary per AC:

- **AC1 (reviews self-service):** `CoachReviewsPage.vue` + two new `reviews.api.js` functions. Composer gated on `coachResponseBody == null && moderationStatus === 'APPROVED'`; non-APPROVED own-reviews reuse the existing `reviews.pendingModeration`/`reviews.blocked` copy rather than new strings. 404 (no `CoachProfile` yet) renders an incomplete-profile state. 9 Vitest specs.
- **AC2 (session-pack offer management):** `CoachSessionPackTiersPage.vue`, current-offer + read-only-history model (not a catalog — DB enforces at most one active tier). `payment.store.js` gained `createSessionPackTier`/`deactivateSessionPackTier`/`extendSessionPack` actions (none were previously imported/used). Client-side price-floor guard (`totalPrice/sessionCount >= 0.005`) avoids the backend's rounding+CHECK-constraint 500. 8 Vitest specs.
- **AC3 (report branding):** Fixed the real backend bug — `ReportGenerationService.saveBranding`'s ownership check now compares the caller's own user id (threaded through from `CoachBrandingResource`), not the `CoachProfile` UUID; added a test that exercises a REAL `FileStorageService` (not the full mock every other test in the file used) with a real-shaped `FileStorageObject` fixture — proven to fail pre-fix. Added raw `logoKey` to `CoachBrandingResponse` so a colour-only edit doesn't silently wipe the logo. `ProfilePage.vue` now calls `authStore.fetchCoachTier()` on mount (previously only read by an unrelated player page, so `coachTier` was `null` on any cold `/profile` load) with a loading/locked/unlocked three-state render. New `EditCoachBrandingDialog.vue`. Decided `coach_branding` belongs in `StorageResource`'s `OWNER_BOUND_ENTITIES` alongside `coach_profile`. 18 backend tests (`ReportGenerationServiceTest`) + 6 + 3 Vitest specs.
- **AC4 (command-center dedup):** Extracted `runCoachAction` shared mutation→refresh→toast helper in `CoachCommandCenterPage.vue`; all five handlers route through it, each keeping its own loading-ref/dialog-state logic. Explicitly a DRY cleanup, not a bug fix (Finding 4's "unguarded refresh" claim is false — `loadCoachSchedule` never rethrows). First-ever coverage for this page: 10 Vitest specs (later extended to 15 for AC6).
- **AC5 (null-ROLES hardening):** `getAuthoritiesSilently` now degrades to `List.of()` on a missing/non-string `ROLES` claim instead of letting `IllegalArgumentException` escape. New `JwtManagerImplTest` case via `buildTokenWithCustomClaims`, pinning the post-fix no-exception/ANONYMOUS-cookie behavior.
- **AC6 (time gate):** Added `BookingError.SESSION_NOT_YET_STARTED` and a `verifyNotBeforeStartTime` guard to `startSession`/`initiateQuickComplete`; added the existing `NO_SHOW_TOO_EARLY` guard to `recordNoShowPlayer` (explicit reversal of `spec-coach-booking-cancel-no-show.md`'s prior "Never" — documented in-code). Fixed three pre-existing test fixtures that would otherwise go red (`BookingServiceTest.makeBooking`-derived no-show test, `SessionCompletionResourceIT.insertUpcomingBooking`, `BookingCompletionServiceTest`'s zero-offset fixture — the last one was this project's own documented `Instant.now()`-pair flake class). Added i18n keys to all 4 backend bundles + all 3 frontend bundles. Frontend client-side pre-checks + catch branches on `handleStartSession`/`handleQuickComplete`/`confirmNoShowPlayer`.
- **AC7 (CI provenance):** New step in `ci.yml`'s `build-and-push` job (after the complete "Build and push Docker image" step) pulls the just-pushed `sha-<short>` tag and runs the existing `assert-git-provenance.sh` unchanged. Documented as a post-push detector, not a pre-push gate. `actionlint`/`shellcheck` clean.
- **AC8 (ruleset as code):** `.github/rulesets/no-direct-push.json` pulled live via `gh api` and sanitized (stripped `id`/`node_id`/`source`/`source_type`/`created_at`/`updated_at`/`_links`/`current_user_can_bypass`). New `apply-branch-ruleset.yml` (manual dispatch, `RULESETS_PAT`-authenticated, robust `--jq 'first(...) // empty'` lookup). **Owner action still required**: creating the fine-grained PAT and storing it as `RULESETS_PAT`, then dispatching the workflow once to verify — both are account-level actions outside a local dev session's scope (fine-grained PATs have no `gh`/API creation path, and `workflow_dispatch` needs the workflow file live on GitHub first).
- **AC9 (verification):** Full targeted regression green across every touched module — see Change Log for exact counts. `deferred-work.md` pruned per Dev Notes' deletion list (the null-ROLES bullet under skillars-deferred-150's section was also removed, beyond the Dev Notes' literal list, since AC5 resolves it — leaving a resolved-bug description in the ledger would be stale bookkeeping).

### File List

**New:**
- `src/frontend/src/pages/coach/CoachReviewsPage.vue`
- `src/frontend/src/pages/coach/CoachSessionPackTiersPage.vue`
- `src/frontend/src/components/profile/EditCoachBrandingDialog.vue`
- `src/frontend/src/pages/coach/__tests__/CoachReviewsPageSpec.js`
- `src/frontend/src/pages/coach/__tests__/CoachSessionPackTiersPageSpec.js`
- `src/frontend/src/pages/coach/__tests__/CoachCommandCenterPageSpec.js`
- `src/frontend/src/components/profile/__tests__/EditCoachBrandingDialogSpec.js`
- `.github/rulesets/no-direct-push.json`
- `.github/workflows/apply-branch-ruleset.yml`

**Changed (frontend):**
- `src/frontend/src/api/reviews.api.js`
- `src/frontend/src/stores/payment.store.js`
- `src/frontend/src/pages/ProfilePage.vue`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/layouts/MainLayout.vue`
- `src/frontend/src/i18n/en-US/index.js`, `src/frontend/src/i18n/fr-FR/index.js`, `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/pages/__tests__/ProfilePageSpec.js`

**Changed (backend):**
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingError.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/development/api/CoachBrandingResource.java`
- `src/main/java/com/softropic/skillars/platform/development/contract/CoachBrandingResponse.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/api/StorageResource.java`
- `src/main/resources/i18n/messages.properties`, `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`

**Changed (tests):**
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingCompletionServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/SessionCompletionResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`

**Changed (infra/CI):**
- `.github/workflows/ci.yml`

**Changed (ledger/docs):**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

---

## Change Log

| Date | Version | Change | Author |
| :--- | :--- | :--- | :--- |
| 2026-10-14 | 1.0 | Created via `/bmad-create-story`, bundling eight items from `deferred-work.md`'s most recent tail sections. Status -> ready-for-dev. | Claude Sonnet 5 |
| 2026-10-14 | 1.1 | **Story-review response** (`story-review.md`, 4 parallel verification layers + adversarial re-check; every surviving finding independently re-verified against current source before being applied, not taken on the review's labels alone — one review finding, §2c's "ledger exhaustiveness" claim, was itself found to be a false positive on re-check and is corrected in this story's own "Explicitly NOT picked up" section, not accepted). Two BLOCKING defects fixed: **Finding 4 (command-center handlers)** described a defect that cannot occur — `booking.store.js`'s `loadCoachSchedule` carries an explicit, twice-already-litigated "never rethrows" contract comment; rewritten as a DRY dedup, explicitly not a bug fix, with the originally-prescribed "refresh rejection" test removed since it could only pass by violating that contract. **Finding 3 (branding)** had a real backend ownership-check bug (`coachId.toString()` compared against a value actually stamped from the caller's user id — two different identifier spaces, confirmed never exercised by any real test) that would 403 the designed upload flow on every save, plus an `authStore.coachTier` read that is `null` on any cold `/profile` load (its one real-codebase caller is an unrelated player page) — both fixed in the story's design, with a third, lower-severity issue (colour-only edits silently wiping an existing logo) also fixed via a small response-DTO addition. **Finding 6 (time gate)** needed two pre-existing test fixtures corrected (both would otherwise go red under AC9's 0-failures requirement) and a corrected scope claim — the "existing `recordNoShowCoach` caller" this story's first draft said to wire does not exist, confirmed against `skillars-deferred-63`'s own documented decision not to build one; i18n scope widened to include the backend `.properties` bundles the new error code also needs. **Finding 5 (null-ROLES)** severity corrected from "can 500 mid-2FA-completion" to "latent defensive hardening, unreachable via any current client path" (`TokenCreatorImpl` always sets the claim unconditionally), and its test recipe corrected to use the existing `buildTokenWithCustomClaims` helper rather than the story's original (unworkable, since the target method is private) approach. **Finding 7 (CI provenance)** had a citation range that would have corrupted the YAML step it was inserted into if followed literally; corrected, and the "detector not gate" residual is now explicitly disclosed rather than silently assumed. **Finding 8 (ruleset)** had an invalid `permissions: administration: write` block that would fail the whole workflow to parse (not a valid Actions scope); corrected to use a dedicated PAT secret from the start, plus a strip-list fix (`current_user_can_bypass` is read-only) and a jq-lookup robustness fix. Several smaller citation-range corrections applied throughout (noted inline per-finding) along with one corrected misquotation of the ledger's own wording ("only if" → "if"). Status remains ready-for-dev — nothing has been built yet; this is a pre-implementation correction pass. | Claude Sonnet 5 |
| 2026-10-14 | 2.0 | **Implementation complete** (`/bmad-dev-story`) on branch `story/deferred-151-coach-ui-gaps-handler-hygiene-and-ci-hardening`. All 9 tasks done, all ACs implemented. Backend regression green across every touched module (`booking`: all classes 0 failures; `filestorage`: all classes 0 failures, incl. `StorageResourceIT` for the `OWNER_BOUND_ENTITIES` addition; `development`: `ReportGenerationServiceTest` 18/18 incl. the new real-`FileStorageService` test; `security`: `JwtManagerImplTest` 38/38 incl. the new null-ROLES case, plus the rest of the module 0 failures). Frontend: 291/291 Vitest specs pass (41 files, +4 new spec files / +51 new tests this story), `npx quasar build` (prod bundle + lint) clean. `actionlint`/`shellcheck` clean on both touched/new workflow files. Status -> review. AC7's "verify empirically on this story's own PR run" and AC8's PAT creation + workflow dispatch are explicitly flagged as owner/PR-stage actions, not done from this session (see Dev Agent Record). | Claude Sonnet 5 |
