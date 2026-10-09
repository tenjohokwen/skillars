# Story: Seed-Script Ownership Gap, JWT Role Precedence, Unbounded Booking Query, and Subscription/Review Hardening

**Status:** done

**Scope Level:** Medium (six independent, small-to-mechanical fixes across local dev tooling, JWT cookie internals, a booking query, review-edit concurrency defense-in-depth, and subscription-endpoint test coverage; no schema change, no new endpoint except a CI assertion step, no frontend change.)

---

## User Story

As **the developer maintaining local dev tooling, authentication internals, and the review/subscription modules**, I want six independently-verified, currently-open defects fixed — a local seed script that silently skips self-registered players, a JWT cookie writer with no deterministic role precedence for a future dual-role user, an unbounded review-eligibility query, a review-edit concurrency gap with no re-check after the row lock, an untested subscription-endpoint authorization boundary, and a CI pipeline that builds Docker image provenance but never asserts it — so that **local manual testing works for every account shape, a future multi-role user gets predictable behavior instead of hash-ordered luck, review-eligibility queries stay bounded as data grows, a future refactor of the review-edit cooldown/eligibility coupling fails loudly instead of silently, the subscription-ownership widening from skillars-deferred-149 has a test proving it didn't also widen authorization, and a future dependency bump that breaks git-provenance stamping is caught by CI instead of silently reintroducing the exact `git.dirty=true` bug skillars-deferred-149 just fixed.**

---

## Context: Current State (verified against HEAD `162608ff`, 2026-10-08)

This story bundles one fresh finding (AC1, found during this session's own live-testing follow-up, not yet in `deferred-work.md`) with five independently re-verified, still-open items mined from `deferred-work.md`'s tail sections (code reviews of skillars-deferred-142, -145 round 1, -145 round 2, and -149 — every section from skillars-deferred-140 through -149 was read in full and every candidate re-verified against current HEAD before inclusion; see the explicit scope caveat at the end of this section regarding everything *before* that tail). Per the project owner's explicit instruction, every candidate was independently re-verified against current HEAD before inclusion — three additional candidates found in the same sections turned out to be **already resolved** (ledger not pruned), **already closed**, or **not actionable**, and are called out explicitly below so they are not re-litigated.

### Finding 1 — `docs/deployment/local/seed-accounts.sql` only seeds a player subscription for shadow-account (parent-owned) players, never a self-registered adult player

Step 5's `INSERT INTO payment.player_subscriptions` (and its matching verification `SELECT` at the bottom of the file) resolves player ownership via:

```sql
FROM main.player_profiles pp
JOIN main."user" u ON u.id = pp.parent_id
WHERE u.email = :'owner_email'
```

(`docs/deployment/local/seed-accounts.sql:168-170` for the INSERT's join, `:215` for the verification query's identical join). But `main.player_profiles` carries:

```sql
CONSTRAINT chk_pp_owner CHECK ((((parent_id IS NOT NULL) AND (user_id IS NULL)) OR ((parent_id IS NULL) AND (user_id IS NOT NULL))))
```

(`V138__baseline_schema.sql:911`) — `parent_id` and `user_id` are **mutually exclusive**, not both populated. A self-registered adult player owns their own profile via `user_id`, never `parent_id`. The script's own comment directly above step 5 claims otherwise:

> "`player_profiles.parent_id` holds the OWNING user: the parent for a shadow account, **or the player themselves for a self-registered adult player**. Both cases are covered by this join."

That claim is false for the self-registered case — the join silently matches **zero rows**, so running the script against a self-registered player's account produces no subscription at all, with no error, and the script's own verification query at the bottom will show an empty/all-NULL row for that player, easy to misread as "nothing to seed" rather than "the join is wrong."

**Not caused by a Flyway migration.** Checked every migration `V139` through `V158` against the five tables this script's INSERT/UPDATE statements touch (`payment.coach_stripe_accounts`, `marketplace.coach_subscriptions`, `payment.coach_subscriptions`, `payment.parent_credit_ledger`, `payment.player_subscriptions`) — none of them were altered. (The script's joins also read `main."user"` and `main.player_profiles`; two migrations did add columns to those — `V151` on `player_profiles`, four migrations including `V158` on `main."user"` — but all are additive, nullable `ADD COLUMN IF NOT EXISTS`, none touch `chk_pp_owner`/`parent_id`/`user_id`, so this doesn't change the conclusion.) `chk_pp_owner` and the dual `parent_id`/`user_id` columns predate this script entirely: `ShadowAccountService.createSelfOwnedPlayerProfile` (the `user_id` writer) shipped in commit `1f24a5e5` (2026-07-04) — not `15d1ca2b` as an earlier draft of this finding cited (that commit, also 2026-06-12, added `ShadowAccountService` but only `createPlayerProfile`/`listPlayerProfiles`/`linkAdditionalParent`; `createSelfOwnedPlayerProfile` and `chk_pp_owner` both first appear in `1f24a5e5`). Either way, 2026-07-04 predates the script's own creation in commit `6d15a19a` (2026-09-02) — the conclusion holds, only the cited commit was wrong.

**The exact dual-ownership pattern already exists twice elsewhere in this codebase** — both written correctly:
- `ReviewSubmissionService.evaluateEligibility` (`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:302-303`): `authorId.equals(player.getUserId()) || authorId.equals(player.getParentId())`
- `SubscriptionService.assertPlayerOwnership` (`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:901-908`): `parentPlayerLinkRepository.existsByParentIdAndPlayerId(...) || playerProfileRepository.existsByIdAndUserId(...)`

### Finding 2 — `JwtManagerImpl.setSkillarsProfileCookie` resolves a multi-mapped-role claim set deterministically, but only by accident of a third-party library's sort order, not by any precedence this codebase owns

```java
private void setSkillarsProfileCookie(HttpServletResponse res, Map<String, Object> claims) {
    final SkillarsRole resolvedRole = getAuthoritiesSilently(claims).stream()
            .map(authority -> StringUtils.removeStart(authority.getAuthority(), "ROLE_"))
            .flatMap(name -> {
                try {
                    return Stream.of(SkillarsRole.valueOf(name));
                } catch (IllegalArgumentException e) {
                    return Stream.empty();
                }
            })
            .findFirst()
            .orElse(SkillarsRole.ANONYMOUS);
```

(`src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:113-124`).

**Corrected mechanism (an earlier draft of this finding got this backwards — do not re-introduce that error):** `getAuthoritiesSilently` (`JwtManagerImpl.java:141-148`) deserializes the JWT's own `ROLES` claim — a JSON array — into a `List<SimpleGrantedAuthority>` (JSON-array order preserved). That claim is written once, by `TokenCreatorImpl.java:49`: `claims.put(ROLES, toJson(principal.getAuthorities(), ...))`. `principal` is a `Principal extends org.springframework.security.core.userdetails.User` (**Spring Security's own `User` class — not this codebase's JPA `User` entity**; the two share a name and that caused the original mix-up). Spring Security's `User` constructor stores authorities via a private `sortAuthorities(...)` that returns a `SortedSet<GrantedAuthority>`, ordered by `AuthorityComparator.getAuthority().compareTo(...)` — confirmed directly against this project's own resolved `spring-security-core-6.5.11.jar` via `javap`. So the order reaching `.findFirst()` is **alphabetical and deterministic today**: for the four mapped roles, `ROLE_ADMIN < ROLE_COACH < ROLE_PARENT < ROLE_PLAYER` — meaning **today's accidental behavior already gives `ADMIN` top priority**, the opposite of "hash-ordered luck." The defect is real (relying on an unspecified third-party internal sort order is fragile and undocumented — a future Spring Security version is free to change it), but it is not the mechanism originally described here.

**This codebase has two existing, disagreeing precedents for this exact ambiguity, not one.** `MessagingResource.resolveRole` (`src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java:231-250`) falls back to `COACH > PARENT > PLAYER` and **throws** `NOT_A_PARTY` if nothing matches. `ReviewResource.resolveRole` (`src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java:162-166`) checks `COACH` then `PARENT` and **defaults to `PLAYER`** otherwise. **Neither handles `ADMIN` at all.** Any precedence this fix adopts for `ADMIN` is a new decision, not a copy of an established pattern — see Design B.

**Not currently triggerable** — verified every authority-*creation* call site in `src/main` assigns one authority at grant time, and `V139__baseline_seed_data.sql` seeds no multi-authority user (test fixtures do seed multi-authority users for unrelated purposes — `src/test/resources/sql/initTestData.sql:51-59` — but no production code path grants a second role-bearing authority after account creation). This is defensive hardening against a future multi-role account, not a live bug.

**Residual, explicitly not fixed by this AC:** `AuthService.login()`/`refresh()` (`AuthService.java:132`, `:281`) write the *same* `skp` cookie from a completely different source — `user.getSkillarsRole()`, a single DB scalar column — not from the JWT `ROLES` claim this finding's fix touches. For a hypothetical dual-role user, login would stamp the DB-authoritative role while a subsequent token refresh would stamp this fix's precedence-derived role, which can disagree. `JwtManagerImpl`'s own existing comment (`:102-109`) explains why `refreshLoginToken` can't just read the DB (it's deliberately DB-free). Document this as an accepted, pre-existing split-brain risk this AC does not resolve — fixing it would mean either giving `JwtManagerImpl` DB access (a bigger architectural change) or making `AuthService` claims-derived too (changing its own, independently-working mechanism). Out of scope here; worth its own future ledger item if a real multi-role account ever appears.

### Finding 3 — `BookingRepository.findQualifyingCompletedBookings` has no `LIMIT` or `ORDER BY`, and a new caller added this session makes the unbounded scan materially more reachable

```java
@Query("""
    SELECT DISTINCT b.playerId AS playerId
    FROM Booking b
    WHERE b.coachId = :coachId
      AND (b.parentId = :authorId OR b.playerId = :authorId)
      AND b.status = 'COMPLETED'
      AND b.updatedAt <= :maturedBefore
      AND b.updatedAt > :sinceAfter
    """)
List<BookingReviewEligibilityProjection> findQualifyingCompletedBookings(...)
```

(`src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:127-140`, confirmed unchanged since the deferred-145 review flagged it; confirmed **no `ORDER BY`** either — contrast the very next method in the same file, which has one). `ReviewSubmissionService.evaluateEligibility` iterates every row this returns, in whatever order Postgres happens to produce for `SELECT DISTINCT`, looking up each `playerId`'s `PlayerProfile` until it finds an owned one and `break`s (`:287-303` — `break` only fires on the eligible branch; a rejection scans every row).

**The ledger's own original "add a `LIMIT`" recommendation (which seeded this finding) was explicitly superseded five days later by the same story's own round-2 review — this story's text must not repeat it as live advice.** `skillars-deferred-145-review-eligibility-maturity-cooldown-and-dispute-gate-rework.md:509`, patch **R14**: *"add `DISTINCT` to the qualifying-bookings query … A `LIMIT` is no longer needed if `DISTINCT` lands."* `DISTINCT` landed (confirmed at `BookingRepository.java:128`). The ledger bullet was simply never pruned after R14 shipped.

**The real, fresh justification for bounding this query is a new caller this story's own HEAD introduced, not the stale LIMIT recommendation.** `HEAD 162608ff` (this session's own earlier review-eligibility-button bug fix) added:

```java
// ReviewSubmissionService.java:256-261
public ReviewEligibilityDto checkWriteEligibility(UUID coachId, Long authorId) {
    if (!coachProfileRepository.existsById(coachId)) {
        throw new ResourceNotFoundException("Coach", coachId.toString());
    }
    return evaluateEligibility(coachId, authorId, Instant.EPOCH);
}
```

reached from `GET /coaches/{coachId}/eligibility` (`ReviewResource.java:79-85`) and called by `CoachPublicProfilePage.vue` on every coach-profile page load for any parent/player who hasn't yet reviewed that coach. Before `#262` this query ran only at the moment of an actual submit/update; it now runs on every such page view, for the common case (not-yet-eligible) that never short-circuits. **A `LIMIT` with no `ORDER BY` would be a correctness hazard on its own** (see Design C) — pairing a bound with real ordering, now that the query is reachable far more often, is the actual case for this AC.

### Finding 4 — `ReviewSubmissionService.updateReview` never re-derives eligibility after taking the row lock

```java
Instant sinceAfter = review.getAuthorLastEditedAt() != null
    ? review.getAuthorLastEditedAt() : review.getCreatedAt();
checkEligibility(review.getCoachId(), authorId, sinceAfter);          // :152-154, computed from the STALE unlocked read
...
entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);        // :176
...
ReviewModerationStatus lockedStatus = locked.getModerationStatus();   // :185 — re-checked post-refresh
if (lockedStatus == ReviewModerationStatus.BLOCKED
        || lockedStatus == ReviewModerationStatus.UNDER_REVIEW) { ... }
int lockedCooldownDays = configService.getBoundedInt(...);            // :192 — re-checked post-refresh
if (locked.getLastModifiedAt().isAfter(...)) { ... }
// eligibility (checkEligibility / sinceAfter) is NEVER re-checked here
```

(`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:152-198`). The moderation-status and cooldown guards are deliberately re-checked on the fresh, locked instance (per the existing comment at `:178-184` explaining why — a concurrent winning edit must not be silently overwritten), but eligibility is not. **Latent, not reachable today**: `updateReview` is the only *normal-API* writer of `authorLastEditedAt` (`submitReview` also sets it once, at creation — `:103` — and `V157` backfilled historical rows from `createdAt`; neither changes the argument), and `updateReview` always writes `authorLastEditedAt` together with `lastModifiedAt` from the same local `now()` (`:203-205`) — so any concurrent edit that would invalidate this caller's `sinceAfter` also trips the already-re-checked cooldown guard first, pinned by `ReviewSubmissionServiceConcurrencyIT`. The coupling is implicit and undefended: a future change that decouples the two timestamp writes, or adds a per-role/per-review cooldown override, would silently make the "new qualifying session since last edit" rule bypassable by a near-simultaneous pair of edits.

### Finding 5 — No test proves a self-registered PLAYER is rejected by the three mutating subscription endpoints

`skillars-deferred-149` AC1 widened `SubscriptionService.assertPlayerOwnership` to also accept a self-registered player acting on their own behalf:

```java
private void assertPlayerOwnership(Long parentUserId, Long playerId) {
    boolean owned = parentPlayerLinkRepository.existsByParentIdAndPlayerId(parentUserId, playerId)
        || playerProfileRepository.existsByIdAndUserId(playerId, parentUserId);
    ...
}
```

(`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:901-908`, shared by call sites at `:114` `getPlayerSubscription`, `:319` `subscribePlayer`, `:383` `changePlayerTier`, `:458` `cancelPlayerSubscription`). After that widening, the service layer can no longer distinguish "is a parent of this player" from "is this player" — the **only** thing stopping a self-registered PLAYER from subscribing/re-tiering/cancelling their own subscription is the controller-level annotation:

```java
@PostMapping("/player/subscribe")       @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)   // :100
@PostMapping("/player/change-tier")     @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)   // :110
@DeleteMapping("/player")               @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)   // :118
```

(`src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`). A self-registered player holds `ROLE_PLAYER`, not `ROLE_PARENT`, so these three endpoints **already reject them correctly today** — verified directly, this is not a live vulnerability. But `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java` has zero tests of this shape (confirmed by listing every test method — the only role-mismatch test present is `getMyCoachSubscription_parentCaller_returns403`, covering the symmetric coach-only endpoint, not these three). The parameter is also still named `parentUserId` while now also meaning "the player themselves," which invites a future author to assume parent-only semantics still hold — and the rename is bigger than it looks: `parentUserId` appears **12 times on 12 lines** in this file (`:114,115,319,321,338,383,384,458,459,901,902,903`), not just at the 5 method/field declarations. One of the 7 usage sites is not cosmetic: `subscribePlayer`'s `stripeCustomerRepository.findById(parentUserId)` (`:338`) resolves the Stripe customer **by the caller's own user id** — exactly the dependency a future self-registered-player billing widening would also need to satisfy (a self-registered player would need their own `StripeCustomer` row under their own user id, which nothing in today's self-registration flow creates). The day a future story grants self-registered adult players parent-equivalent billing authority, every one of these three methods would silently open with no code change and no failing test to catch it — and that future story would also need to handle this Stripe-customer dependency, which is otherwise easy to miss.

### Finding 6 — Nothing in CI asserts the built Docker image's `git.properties` actually has `git.dirty=false`

`skillars-deferred-149` AC7 fixed a permanent `git.dirty=true` false positive by reordering the Dockerfile and patching `git.properties` into the built jar post-`mvn package`. That mechanism rests on `-Dmaven.gitcommitid.skip=true` (`Dockerfile:24`) matching `git-commit-id-maven-plugin`'s real skip property, and on the `jar uf` patch step actually running and actually using the right commit SHA. Nothing in CI reads the result back:

```
grep -rl "git.properties\|git.dirty" .github/workflows/
# zero matches
```

Only the composite action's own comments (`.github/actions/docker-build/action.yml:26-39`) mention these strings — no workflow step extracts and checks the image's actual `git.properties` contents. A future `git-commit-id-maven-plugin` upgrade with a renamed/changed skip property, or a bound execution overriding the patch step's output, would silently reintroduce exactly the bug `skillars-deferred-149` just fixed, with CI reporting green throughout. There is already a hand-run precedent for the exact extraction mechanism to automate: `docker create` / `docker cp` / `unzip -p`, used manually during `skillars-deferred-149`'s own empirical verification of this same file.

### Explicitly NOT picked up (verified, excluded — do not re-add to this story)

- **"Clear-on-login has only mock-level coverage"** (`deferred-work.md`, "Deferred from: code review of skillars-deferred-149") — **obsolete, not merely fixed.** Its premise is that `AuthService.login()` clears `securitySessionInvalidatedAt` and needs a real-DB test proving the clear durably sticks. `skillars-deferred-149`'s own post-implementation review response (merged this session, PR #261) **reversed that behavior entirely** — `login()` now deliberately does **not** clear the column, and `AuthServiceTest.login_success_doesNotClearPreviouslySetInvalidationFlag` now asserts the opposite of what this ledger item describes testing. Delete this bullet from `deferred-work.md` rather than picking it up (see Dev Notes).
- **"Required status checks on `master` live only in GitHub ruleset config, not a versioned artifact"** (same section) — genuinely open, but needs a product/process decision (commit-as-code vs. a scheduled drift-assertion) before any code change — explicitly left for the owner, not bundled here.
- **`[deferred-19]` reset-mean drift (14.0 → 15.8 → 16.0 ms across three runs)** (deferred-146 section) — the ledger's own text says "re-check on the next few PR builds"; this is monitoring/variance tracking, not a code fix.
- **i18n hardcoded-values item, fr/de native-speaker review, AC4.3 manual browser walkthroughs** (deferred-140/145 sections) — either already `[CLOSED]` in the ledger (independently re-verified) or explicitly human-only with no code change possible in this session.
- **`/dashboard` is `DEFAULT_ROUTE` for any unmapped/null role, with no nav link** (deferred-141 section) — genuinely open, **not** closed and **not** human-only (an earlier draft of this story mischaracterized it as such — corrected). The ledger's own text explains why no action is warranted *yet*: `SkillarsRole` holds exactly four values and nothing emits a fifth into the `skp` cookie today, so the gap is unreachable. Leave this bullet in `deferred-work.md` untouched.
- **`markUsedByTokenHash`/`markAllUsedByUserId` unguarded against transient DB failures** (deferred-143 section) — same correction: genuinely open, not closed/human-only. The ledger's own text says it's only worth bespoke handling "if this class of DB failure is ever observed in production" — speculative, not actionable now. Leave this bullet untouched too.
- **A caveat on the scope of this mining pass itself:** `deferred-work.md`'s own header (`:7-9`) states its convention plainly — *"This is a list of open work only. Items are deleted outright once they are implemented."* By that rule every untagged bullet anywhere in the file is, formally, open; an earlier draft of this story claimed everything before the deferred-142 section was "already closed by the file's own chain of 'Last audit' sweep entries," which overstates what those sweeps actually did (the most recent is dated 2026-09-24, covering nothing from skillars-deferred-140 onward, and even the most thorough full-file sweep — 2026-09-15 — restored 4 bullets to open and deleted only 2). This story's own mining effort was genuinely exhaustive for the deferred-142-through-149 tail (every section read, every candidate re-verified against HEAD, as the items above demonstrate), but it is **not** a certified closure audit of everything before that tail — treat the pre-142 sections as out of this story's scope, not as confirmed-clean.

---

## Design

### A. `docs/deployment/local/seed-accounts.sql` (Finding 1)

Widen both joins (step 5's `INSERT` and the final verification `SELECT`) to match either ownership shape, mirroring `ReviewSubmissionService`/`SubscriptionService`'s existing pattern:

```sql
-- step 5 INSERT
FROM main.player_profiles pp
JOIN main."user" u ON u.id = pp.parent_id OR u.id = pp.user_id
WHERE u.email = :'owner_email'
```

Apply the identical change to the verification query's `JOIN` near the bottom of the file. **Safe by construction, exhaustively checked** — `chk_pp_owner` forces exactly one of `parent_id`/`user_id` non-null per row, so each `pp` row still joins on exactly one disjunct (never both, never a cross-match); `main."user".email`'s own unique constraint and `uq_pp_user_id` (capping one self-owned profile per user) together bound the result to one row per distinct `pp.id` either way. A parent who is also a self-registered player correctly gets both their own subscription and their children's.

**Two comment blocks need correcting, not one** — the false "both cases are covered by this join" claim is restated almost verbatim in the file's own **header** (`:16-17`, the prerequisites list: *"At least one player profile created (parent-created shadow account, or an adult player's self-owned profile)"*) as well as in the step-5 comment. Fix both.

**Document two things explicitly in the fixed file (or in this story's AC) rather than letting them surprise whoever runs it next:**
- **Verification here is manual-only.** Nothing automated exercises this script — no test, no CI job references it. "Verified" means a human ran it against a real local Postgres with both account shapes present and read the output.
- **Step 4 (the credit-wallet seed) is non-idempotent and cannot be corrected, only added to.** `payment.parent_credit_ledger` is append-only (`trg_ledger_no_update`/`trg_ledger_no_delete` reject UPDATE/DELETE outright) — the file's own comment already warns about this, but it means every verification pass of this fix (including the dev's own) adds one more irreversible credit row to whatever test account is used. Not a defect to fix, just something to know before running it twice.
- **Constructing the self-registered-player test case is non-trivial.** The script only *upgrades* an existing account (`:10`); a self-registered adult player must be produced via the real UI registration flow plus the email-verification round trip, and the script must be invoked with `-v owner_email=<the player's own email>` — a usage shape neither the script's header nor `manual-testing.md` currently documents for the self-registered case (both existing worked examples pass a parent's email).

### B. `JwtManagerImpl.setSkillarsProfileCookie` (Finding 2)

Replace the implicit, library-internal ordering with an explicit, code-owned precedence pick. **Place `ADMIN` first**, not last — this preserves today's actual (accidental) behavior, since the alphabetical sort already resolves a dual-role user to `ADMIN` over any of `COACH`/`PARENT`/`PLAYER`, and an admin-held authority should not be silently masked by an incidental second grant. Collect the mapped roles into a `Set<SkillarsRole>` first, then pick by priority:

```java
final Set<SkillarsRole> mappedRoles = getAuthoritiesSilently(claims).stream()
        .map(authority -> StringUtils.removeStart(authority.getAuthority(), "ROLE_"))
        .flatMap(name -> {
            try {
                return Stream.of(SkillarsRole.valueOf(name));
            } catch (IllegalArgumentException e) {
                return Stream.empty();
            }
        })
        .collect(Collectors.toSet());
final SkillarsRole resolvedRole = mappedRoles.contains(SkillarsRole.ADMIN)  ? SkillarsRole.ADMIN
    : mappedRoles.contains(SkillarsRole.COACH)  ? SkillarsRole.COACH
    : mappedRoles.contains(SkillarsRole.PARENT) ? SkillarsRole.PARENT
    : mappedRoles.contains(SkillarsRole.PLAYER) ? SkillarsRole.PLAYER
    : SkillarsRole.ANONYMOUS;
```

**This `ADMIN`-first choice is a judgment call, not a proven-correct product decision — flag it as such rather than treating it as settled.** No multi-role user exists today to confirm actual product intent, and neither existing precedent (`MessagingResource.resolveRole`, `ReviewResource.resolveRole` — see Finding 2) handles `ADMIN` at all, so there is nothing to copy for this specific choice. "Preserve current accidental behavior" is the safest default precisely *because* it changes nothing observable for any account that exists today; confirm it doesn't conflict with admin-specific frontend gating (`auth.store.js:19`'s `isAdmin`, `routes.js:345`'s `roles: ['ADMIN']`, `MainLayout.vue:268`'s `v-if="authStore.isAdmin"`) before finalizing — a single targeted grep, not a deep investigation, but do it before marking this AC done.

**The new test is the actual deliverable here, and it must assert the *specific intended* order, not just "the result is deterministic."** A dual-role claim set already resolves deterministically today (via Spring Security's alphabetical sort) — a test that only checks "the cookie's role doesn't change between runs" would pass identically before and after this change, proving nothing. Add a new `JwtManagerImplTest` case constructing claims with two mapped authorities (e.g. `ROLE_PARENT` + `ROLE_PLAYER`, and separately `ROLE_ADMIN` + `ROLE_COACH`) and assert the cookie's role is the one this fix's precedence order says it should be — pin intent, not incidental behavior.

### C. `BookingRepository.findQualifyingCompletedBookings` (Finding 3)

**A `LIMIT` must be paired with an `ORDER BY` — adding one without the other is a regression, not a fix.** `SELECT DISTINCT` with no ordering returns a non-deterministic subset of matching rows. If the author's one actually-owned `playerId` happens to fall outside a bare cap, `evaluateEligibility` finds nothing owned, `eligible` stays `false`, and the caller is wrongly denied `NO_QUALIFYING_SESSION` — a real, silent false-denial, not a theoretical one. It's also not purely hypothetical data: `booking.bookings` has **no foreign key on `player_id`** (confirmed — the table's only FKs are on `batch_id` and `session_pack_purchase_id`), so orphaned `playerId`s are a real, already-handled shape (`ReviewSubmissionService.java:288-291`'s own `continue; // orphaned playerId` comment) that could consume cap slots ahead of the row that actually matters. Add `ORDER BY b.playerId` (or similar) alongside the cap.

**Use a literal HQL `limit` clause, not `Pageable`.** This repository has 15 `@Query` methods today and none use `Pageable`/`Slice`/`Page<>`/derived `Top`/`First` — a `Pageable` parameter changes this method's signature, which breaks the existing 4-arg mock stub at `ReviewSubmissionServiceTest.java:83` (`when(bookingRepository.findQualifyingCompletedBookings(eq(COACH_ID), eq(AUTHOR_ID), any(), any()))...`) at **compile** time, not just at the test-outcome level. Hibernate 6.6's HQL parser supports a literal `limit` clause directly in the `@Query` string (confirmed viable against this project's actual resolved Hibernate/spring-data-jpa versions, no `hibernate.jpa.compliance.*` flag or ArchUnit convention test blocks it) — add it there instead, keeping the method signature unchanged. A generous bound (e.g. 50) is safely above any realistic distinct-player-per-author-per-coach count and exists only as insurance.

No existing test fixture has more than a handful of qualifying bookings, so the fix itself shouldn't change any current test's outcome — but `ReviewSubmissionServiceTest.java` must be named as a touched file regardless (it needs no change if the HQL-literal route is used, but verify that explicitly rather than assuming). Add one new test proving the ordering+cap combination still picks the correct owned player when the result set is artificially large, if practical.

**Sequencing note:** Finding 4 (Design D) adds a *second* call site for this same query, executed while holding a `PESSIMISTIC_WRITE` row lock on the review being edited. Land this AC's `ORDER BY`/bound fix before or together with Design D, not after — a non-deterministically truncated result deciding whether a lock-holding transaction aborts is a strictly worse version of the same hazard.

### D. `ReviewSubmissionService.updateReview` (Finding 4)

After `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)` (`:176`), alongside the existing re-checks of moderation status and cooldown, re-derive `sinceAfter` from the **refreshed** `locked` instance and re-run the eligibility check:

```java
Instant lockedSinceAfter = locked.getAuthorLastEditedAt() != null
    ? locked.getAuthorLastEditedAt() : locked.getCreatedAt();
checkEligibility(locked.getCoachId(), authorId, lockedSinceAfter);
```

Place this with the other two post-refresh re-checks (after the status/cooldown guards, before the field mutations at `:200+`), matching their existing ordering rationale (most-actionable-first). This closes the gap without changing any currently-passing test's outcome, since the only reachable state has the two timestamps coupled — add a comment stating that explicitly.

**Test recipe — a fire-and-forget racing write will not work; two existing precedents in this codebase show the correct shape, use them rather than inventing a new one:**
- A bare `jdbcTemplate.update` racing the method cannot prove anything on its own: if it commits *before* `updateReview`'s unlocked pre-read (`:124-127`), the **pre-lock** eligibility check already fails and the post-refresh re-check this AC adds is never exercised; if it commits *after* the method returns, `entityManager.refresh` never observes it. The decoupling write must land strictly between the pre-read and the lock being taken.
- **Write shape precedent:** `ReviewUpdateIT.java:302-307` already performs exactly this kind of decoupled, `author_last_edited_at`-only `UPDATE`, correctly wrapped in `transactionTemplate.execute(...)` (this project's bare-`jdbcTemplate`-writes-never-commit pitfall applies here as everywhere else — wrap it).
- **Timing-choreography precedent:** `ReviewSubmissionIT.java:703-726` runs an external thread that takes `SELECT ... FOR UPDATE`, mutates, holds via a latch, then commits, while the method under test blocks on `findByIdForUpdateNoWait`'s bounded retry — the same shape needed here to land the decoupling write inside the window between pre-read and lock acquisition.

### E. `SubscriptionResourceIT` (Finding 5)

**No Testcontainers fixture is needed — `SubscriptionResourceIT` is a mock slice, not a real-Postgres IT despite the name.** It's `@WebMvcTest(SubscriptionResource.class)` with `SubscriptionService` mocked (`:37-39`). `@PreAuthorize(HAS_PARENT_ROLE)` rejects a `ROLE_PLAYER` caller before the (mocked) service is ever entered, so the three new tests need nothing beyond `@WithMockUser(roles = "PLAYER")` plus the request itself — do **not** reuse `SubscriptionServiceOwnershipIT`'s real-Postgres self-registered-player fixture here; that class lives one layer down and its setup is unnecessary (and the wrong shape) for a controller-level 403 check. Add three cases asserting 403 on:
- `POST /api/payment/subscriptions/player/subscribe`
- `POST /api/payment/subscriptions/player/change-tier`
- `DELETE /api/payment/subscriptions/player`

As a secondary, low-risk cleanup: rename the `parentUserId` parameter to `callerUserId` throughout `SubscriptionService.java` — **12 occurrences on 12 lines** (`:114,115,319,321,338,383,384,458,459,901,902,903`; re-verify with a fresh `grep` before starting, since line numbers may have drifted by implementation time), confirmed to all live in this one file (the controller passes the value positionally and never names it). Pure rename, no behavior change — but note `:338`'s `stripeCustomerRepository.findById(parentUserId)` when renaming, since it's a real usage, not just a label.

### F. CI git-provenance assertion (Finding 6)

**Scope to `pr-build.yml`'s `docker-image` job only — do not blindly duplicate into `ci.yml`.** `pr-build.yml`'s job passes `load: 'true'` (`:133`), so the built image is in the local Docker daemon and reachable. `ci.yml`'s equivalent job (`build-and-push`, `:238-248`) passes `push: 'true'` and **no `load:`** — `action.yml:9-12` defaults `load` to `'false'`, so that image is never loaded locally; a `docker create` there fails outright with "no such image." Either leave `ci.yml` alone, or treat adding `load: 'true'` there as an explicit, separate follow-up (it has its own cost/tradeoff and is not required to satisfy this AC).

**`git.properties` lives *inside* the jar, not as a loose file on the image filesystem — the extraction must go through the jar.** `Dockerfile:40-47` patches it into `BOOT-INF/classes/git.properties` **inside** the already-built jar via `jar uf`; the runtime stage's only `COPY` (`Dockerfile:76`) brings in `app.jar` itself, nothing else. So `docker cp <ctr>:/app/BOOT-INF/classes/git.properties` (a bare path on the container filesystem) does not exist and will fail. The correct sequence, mirroring the exact commands already run by hand during `skillars-deferred-149`'s own verification:

1. `docker create` a container from the just-built image tag (`skillars-app:pr-${{ github.event.pull_request.number }}`, set by the step named `Build Docker image (no push)` at `pr-build.yml:129`).
2. `docker cp <ctr>:/app/app.jar ./app.jar` (the runtime stage's `WORKDIR` is `/app`, confirmed, and the jar is named `app.jar` there per `Dockerfile:76`'s `COPY ... app.jar`).
3. `unzip -p app.jar BOOT-INF/classes/git.properties` and pipe that output into the assertions, via `grep`: `git.dirty=false` present, and `git.commit.id.full=` followed by a non-empty value that is **not** the literal string `local`.
4. Fail the job (non-zero exit) if either assertion fails, with a clear error message naming which one.
5. Clean up the created container and the extracted jar regardless of outcome.

Consider extracting the assertion into a small script under `.github/scripts/` (matching the existing `.github/scripts/assert-context-count.sh` precedent) if the inline shell gets unwieldy.

---

## Acceptance Criteria

1. **Seed script ownership fix.** `docs/deployment/local/seed-accounts.sql` step 5's `INSERT` and its final verification `SELECT` both resolve player ownership via `pp.parent_id OR pp.user_id`, matching a self-registered adult player as well as a shadow-account player. Both the step-5 comment block **and** the file's header prerequisites list are corrected to describe the actual join. AC includes an explicit note that verification is manual-only (no automated test touches this file) and that re-running step 4 adds another irreversible credit-ledger row each time (append-only table). Manually verified against a real local Postgres with a self-registered-player fixture that such a player's row is now matched.

2. **JWT role-precedence hardening.** `JwtManagerImpl.setSkillarsProfileCookie` resolves a multi-mapped-role claim set via an explicit, code-owned precedence order (`ADMIN > COACH > PARENT > PLAYER`, preserving today's actual — if accidental — behavior) instead of relying on Spring Security's internal alphabetical authority sort. New `JwtManagerImplTest` case(s) assert the cookie resolves to the *specific intended* role for at least one dual-role claim set (not merely "a deterministic result," which today's code already produces). All existing `JwtManagerImplTest` cases still pass unchanged. The accepted residual — `AuthService.login()`/`refresh()` deriving the same cookie's role from a different source (`user.getSkillarsRole()`) for a hypothetical dual-role user — is documented, not fixed, by this AC.

3. **Bounded, ordered eligibility query.** `BookingRepository.findQualifyingCompletedBookings` carries both a defensive bound (HQL-literal `limit`, not a `Pageable`-signature change) **and** an `ORDER BY`, with a comment explaining the chosen bound and why ordering is required alongside it (an unordered truncation can silently false-deny a real caller). `ReviewSubmissionServiceTest.java` is confirmed to still compile/pass unchanged (its 4-arg mock stub is untouched by the HQL-literal approach). Landed before or together with AC4, since AC4 adds a second caller of this same query.

4. **Review-update eligibility re-check.** `ReviewSubmissionService.updateReview` re-derives `sinceAfter` from the refreshed, locked `CoachReview` instance and re-runs the eligibility check after `entityManager.refresh`, alongside the existing post-refresh moderation-status/cooldown re-checks. A new test — built on the write-shape precedent in `ReviewUpdateIT.java:302-307` and the timing-choreography precedent in `ReviewSubmissionIT.java:703-726`, not a fire-and-forget racing write — proves the re-check fires when the two timestamp writes are desynchronized (not reachable via the normal `updateReview` path alone). All existing `ReviewSubmissionService*` tests pass unchanged.

5. **Subscription self-player authorization test coverage.** `SubscriptionResourceIT` gains three new `@WithMockUser(roles = "PLAYER")` tests (no Testcontainers fixture needed — this class is a `@WebMvcTest` mock slice) proving a self-registered PLAYER caller is rejected (403) by `POST /player/subscribe`, `POST /player/change-tier`, and `DELETE /player`. `SubscriptionService.java`'s `parentUserId` parameter is renamed to `callerUserId` throughout that one file (12 occurrences — re-verify the exact count before starting), including the `:338` Stripe-customer lookup usage, not just the 5 declarations — skip the rename entirely (ship the tests alone) if it's found to touch any file beyond `SubscriptionService.java`.

6. **CI git-provenance assertion.** `pr-build.yml`'s `docker-image` job extracts `app.jar` from the built container, unzips `BOOT-INF/classes/git.properties` from *within* the jar (not a bare container-filesystem path), and fails the build if `git.dirty` is not `false` or the commit SHA is empty/`local`. Scoped to `pr-build.yml` only — `ci.yml`'s equivalent job pushes without loading the image locally and is explicitly out of scope unless `load: 'true'` is separately added there. Empirically verified by a real `docker build` + the new assertion step both passing against a real commit SHA, and (separately, to prove the assertion actually catches a regression) failing when run against an image built with no `--build-arg` (the `local`-default case).

7. **Testing & regression.** Full targeted backend suite for every touched class passes (0 failures). No `mvn verify` run locally, per standing project convention — GitHub CI is the full-verification gate. Frontend is untouched by this story (confirm no frontend file needs to change before marking any task complete).

---

## Tasks / Subtasks

- [x] Task 1: Fix seed-accounts.sql ownership join (AC: 1)
  - [x] Widen step 5's INSERT join to `pp.parent_id OR pp.user_id`
  - [x] Widen the verification query's matching join identically
  - [x] Correct the step-5 comment block AND the file's header prerequisites list (both make the same false claim)
  - [x] Verify against a real local Postgres with a self-registered-player fixture (built via real UI registration + email verification, invoked with `-v owner_email=<player's own email>`)

- [x] Task 2: JWT role precedence (AC: 2)
  - [x] Replace `.findFirst()` with an explicit, code-owned precedence pick in `setSkillarsProfileCookie`, ordered `ADMIN > COACH > PARENT > PLAYER`
  - [x] Grep the admin-gating call sites named in Design B to confirm `ADMIN`-first doesn't conflict with anything
  - [x] New `JwtManagerImplTest` case(s) asserting the cookie resolves to the *intended* role for a dual-role claim set, not just "a deterministic" one
  - [x] Re-run full `JwtManagerImplTest` to confirm no regression

- [x] Task 3: Bound AND order `findQualifyingCompletedBookings` (AC: 3) — **land before/with Task 4**
  - [x] Add an HQL-literal `limit` (not a `Pageable` signature change) plus `ORDER BY b.playerId`, with a documented rationale for both
  - [x] Confirm `ReviewSubmissionServiceTest.java:83`'s 4-arg mock stub still compiles/passes unchanged
  - [x] Re-run `ReviewSubmissionServiceTest`/`ReviewSubmissionIT`/`ReviewSubmissionServiceConcurrencyIT` to confirm no regression

- [x] Task 4: Re-check eligibility after lock in `updateReview` (AC: 4) — depends on Task 3 landing first
  - [x] Re-derive `sinceAfter` from the refreshed `locked` instance; re-run `checkEligibility`
  - [x] New test using the `ReviewUpdateIT.java:302-307` (write shape) + `ReviewSubmissionIT.java:703-726` (timing choreography) precedents — not a fire-and-forget racing write
  - [x] Re-run full `ReviewSubmissionService*` suite to confirm no regression

- [x] Task 5: Subscription self-player test coverage + param rename (AC: 5)
  - [x] Three new `SubscriptionResourceIT` cases (subscribe/change-tier/cancel, `@WithMockUser(roles = "PLAYER")`, expect 403) — no fixture needed, this class is a `@WebMvcTest` mock slice
  - [x] Re-verify the `parentUserId` occurrence count with a fresh grep (expect 12, all in `SubscriptionService.java`); rename to `callerUserId` throughout that file only, including the `:338` Stripe-customer usage — skip if it touches any other file
  - [x] Re-run full `SubscriptionResourceIT`/`SubscriptionServiceTest`/`SubscriptionServiceOwnershipIT` to confirm no regression

- [x] Task 6: CI git-provenance assertion (AC: 6)
  - [x] Add the step to `pr-build.yml`'s `docker-image` job ONLY: `docker cp` the jar out, `unzip -p` `git.properties` from inside it, then assert
  - [x] Confirm `ci.yml`'s `build-and-push` job is correctly left alone (no `load: 'true'` there) rather than duplicating a step that would fail
  - [x] Verify empirically: real build passes, `local`-default build fails the new assertion
  - [x] Clean up the temporary container and extracted jar in all cases (including assertion failure)

- [x] Task 7: Final verification (AC: 7)
  - [x] Full targeted backend regression across every touched class
  - [x] Confirm zero frontend files were touched (or justify any that were)
  - [x] Update `deferred-work.md`: delete the five now-resolved bullets this story closes, plus the already-obsolete "Clear-on-login has only mock-level coverage" bullet (see Dev Notes — not fixed by this story, just stale)

### Review Findings

_Added by `/bmad-code-review` (2026-10-09). Three parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor) plus orchestrator re-verification. 13 findings dismissed as false positives — see the dismissal record at the end._

- [x] [Review][Decision] **AC3's stated rationale for `ORDER BY` is unsound** — RESOLVED 2026-10-09 (option 1: keep the bound, fix the comment, add a truncation signal). All three layers plus the orchestrator independently converged here. The comment at `BookingRepository.java:127-134` claims `ORDER BY b.playerId` exists so the bound cannot "silently drop the caller's one actually-owned playerId". It cannot do that: ordering by `playerId` gives the owned row no preference, it only makes *which* 50 rows survive deterministic. The ownership predicate runs in Java at `ReviewSubmissionService.java:308`, strictly after truncation. With >50 distinct qualifying playerIds where the owned one sorts above the 50th lowest, the result is a reproducible false `NO_QUALIFYING_SESSION` 403. Reachable because `booking.bookings` has no FK on `player_id` (`V138__baseline_schema.sql:883-901`; only `batch_id`/`session_pack_purchase_id` are FKs), so orphaned low-numbered playerIds survive profile deletion and occupy cap slots. **Decision: the bound stays** — unreachable in practice, and the real defect is the comment misleading a future maintainer. Rejected alternative (pushing the ownership predicate into the query, correct-by-construction) is recorded in `deferred-work.md` as its own item rather than bolted onto this story. Resolves into the two patch items below.

- [x] [Review][Patch] AC3 comment misstates what `ORDER BY` buys — it is determinism, not safety [src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:127-134] — FIXED 2026-10-09: comment rewritten to state plainly that ordering gives the owned row no preference (the ownership check runs in Java strictly after truncation) and only makes which 50 rows survive deterministic, not safe; cross-references the new truncation log line below.

- [x] [Review][Patch] Provenance gate never compares the SHA to the run's own commit [.github/scripts/assert-git-provenance.sh:59,70 / .github/workflows/pr-build.yml:143] — FIXED 2026-10-09: added an optional `expected-commit-sha` 2nd arg; `pr-build.yml` now passes `${{ github.sha }}`. Re-verified empirically against real Docker images: matching SHA passes, a deliberately wrong SHA fails with a clear message, and the no-arg form stays backward compatible.
- [x] [Review][Patch] Concurrency IT passes with AC4 reverted when the interleaving slips [src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java:389,420,431] — FIXED 2026-10-09: added a wall-clock elapsed-time assertion (must exceed 800ms) that disambiguates "B blocked on the row lock and only failed after refresh" (the fix's own code path) from "B's pre-existing stale pre-check caught it instantly" (which would pass identically whether or not AC4's fix is present).
- [x] [Review][Patch] No log or metric when the AC3 cap actually truncates [src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:293-295] — FIXED 2026-10-09: added a `QUALIFYING_BOOKINGS_CAP` constant and a `log.warn` when the query result hits the cap, naming coachId/authorId for triage.
- [x] [Review][Patch] AC2's required residual-documentation sub-point has no trace at the fix site [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:114-122] — FIXED 2026-10-09: added the accepted-residual comment (AuthService.login()/refresh() deriving the same cookie's role from a different source) directly above `setSkillarsProfileCookie`, not just in the story file.
- [x] [Review][Patch] AC1 left one header prerequisite still parent-only [docs/deployment/local/seed-accounts.sql:15] — FIXED 2026-10-09: prerequisite #2 reworded to "the owner account (a parent, OR a self-registered adult player seeding their own profile)".
- [x] [Review][Patch] `BookingRepositoryIT` class-level comment is now stale ("all seeded rows use REQUESTED") [src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java:18-24] — FIXED 2026-10-09: comment scoped to the `findOverlappingBookings` tests specifically, with a note that the new `findQualifyingCompletedBookings` test seeds COMPLETED rows instead.
- [x] [Review][Patch] New booking IT has no negative fixture, so filter-then-limit ordering is unpinned [src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java:200-230] — FIXED 2026-10-09: added four negative-fixture rows (wrong coach, wrong status, too-recent, too-old), each sorting ahead of every matching row, each violating exactly one filter. Verifying this surfaced two real bugs in the fix itself: the time-boundary pushes were insufficiently far past the maturity window, and the raw `jdbcTemplate.update()` calls were bare (never committed, per this project's own known Hikari-autocommit-false pitfall) — both fixed; all 11 tests in the class now pass with the negative fixtures correctly excluded.
- [x] [Review][Patch] `mktemp -d` is unguarded under `set -uo pipefail` with no `-e` [.github/scripts/assert-git-provenance.sh:25] — FIXED 2026-10-09: explicit `|| { echo FAIL...; exit 1; }` guard added.
- [x] [Review][Patch] `docker cp`/`unzip` stderr discarded, so any other failure is misattributed [.github/scripts/assert-git-provenance.sh:41,47] — FIXED 2026-10-09: both commands now capture and print stderr on failure instead of discarding it.
- [x] [Review][Patch] `grep` key patterns leave `.` unescaped in a script whose purpose is detecting renamed keys [.github/scripts/assert-git-provenance.sh:58,59] — FIXED 2026-10-09: escaped to `git\.dirty=`/`git\.commit\.id\.full=`.
- [x] [Review][Patch] Hand-rolled `catchThrowableFromUpdateReview` duplicates AssertJ's `catchThrowable` [src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java:461-468] — FIXED 2026-10-09: replaced with AssertJ's own `catchThrowable`, helper deleted.
- [x] [Review][Patch] Nested ternary + `Collectors.toSet()` where an `EnumSet` precedence list belongs [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:124-138] — FIXED 2026-10-09: refactored to a `ROLE_PRECEDENCE` `List<SkillarsRole>` constant plus `EnumSet` + `.stream().filter().findFirst()`, no nested ternary.
- [x] [Review][Patch] AC2 test comment overclaims — the two new tests cannot discriminate pre- from post-fix code [src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java:660-665] — FIXED 2026-10-09, and this one was independently re-verified as genuinely real (not just taken on the review's word): this codebase's own `Principal extends` Spring Security's `User`, whose constructor unconditionally re-sorts authorities alphabetically — and this fix's own chosen precedence order was deliberately picked to equal alphabetical order, so NO principal-based test can ever discriminate the two mechanisms. Rewrote both tests to hand-build raw tokens via `buildTokenWithCustomClaims` with the ROLES claim's JSON array in deliberately non-alphabetical order, forcing genuine divergence. Confirmed by temporarily reverting the production fix: both tests now correctly FAIL against the old `.findFirst()` code and PASS against the new precedence logic.
- [x] [Review][Patch] AC4 comment documents correctness but not the duplicated query work inside the lock window [src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:198-208] — FIXED 2026-10-09: added a comment documenting that the post-refresh eligibility re-check re-runs the full `evaluateEligibility` query work a second time, inside the `PESSIMISTIC_WRITE` lock window, and why that's an accepted cost (mirrors the already-accepted status/cooldown re-check cost for the identical reason).

- [x] [Review][Defer] Null `ROLES` claim throws `IllegalArgumentException` past `getAuthoritiesSilently`'s catch [src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java:155-162] — deferred, pre-existing
- [x] [Review][Defer] The published image (`ci.yml`'s `build-and-push`) has no provenance assertion [.github/workflows/ci.yml:247] — deferred, pre-existing

#### Dismissed (13) — recorded so they are not re-litigated

Verified false positives, each disproven by direct read of current HEAD:

1. **55 identical-slot bookings collide with `excl_bkg_coach_slot_overlap`** — it is a *partial* constraint; its `WHERE` covers only `ACCEPTED/PAYMENT_PENDING/CONFIRMED/UPCOMING/IN_PROGRESS/PAUSED` (`V138__baseline_schema.sql:2188`). Fixture uses `COMPLETED`.
2. **Ternary chain silently downgrades unlisted `SkillarsRole` values to `ANONYMOUS`** — the enum has exactly `COACH, PARENT, PLAYER, ADMIN, ANONYMOUS` (`SkillarsRole.java:4,20`). The chain names four and falls through to `ANONYMOUS`; no constant resolves wrongly. (The refactor suggestion survives as a patch item above, on readability grounds only.)
3. **CSRF makes the three new 403 tests vacuous** — `PlayerSubscriptionOwnershipIT.TestSecurityConfig:50` disables CSRF. The 403s are genuine `@PreAuthorize` denials.
4. **AC1's widening and AC5's 403 tests contradict each other; the widening is dead code** — false dilemma. `GET /player/me` gates on `@playerOwnershipGuard.check(...)` (`SubscriptionResource.java:92`), and `PlayerOwnershipGuard` deliberately includes the self-owned branch (skillars-deferred-147). Read allows self-registered players; writes deliberately do not. Two intentional gates.
5. **The read path is untested for a self-registered player** — `PlayerSubscriptionOwnershipIT.java:109` (`getPlayerSubscription_selfRegisteredPlayer_returns200`) already pins it.
6. **A third `player_profiles` ownership join was left un-updated** — no such join exists. Only `:185` and `:231` touch `pp.parent_id`/`pp.user_id`; the others key on `coach_profiles.user_id` and `parent_credit_ledger.parent_id = u.id`.
7. **The widened `OR` join can raise `ON CONFLICT DO UPDATE cannot affect row a second time` (21000)** — `chk_pp_owner` (`V138:911`) forces exactly one of the two columns non-null, so each profile matches at most one disjunct; even duplicate `user.email` rows cannot duplicate `pp.id`.
8. **The concurrency IT cannot pass — `NOWAIT` does not block** — `PessimisticLockRetryer` defaults to `max-attempts:8` with a ~2.2-3.2s jittered budget, which covers the holder's 1200ms hold. It passes. (The real defect in this test is the slip case, kept as a patch item above.)
9. **New test mutating the shared `coachId` field is unsafe** — `@AfterEach tearDown` cleans up *via* that field; setting it is required, and `seedExisting()` does the same.
10. **Avoiding `Pageable` to protect a mock stub is a weak basis for a production signature** — AC3 decided this explicitly; Hibernate is the only provider and `limit` parses at 6.6.53.Final.
11. **The post-lock re-check is unreachable, so it is cost with no value** — the story explicitly scopes it as defensive hardening for a future decoupling. Resolved design decision. (Its undocumented cost is kept as a patch item above.)
12. **Step 4's non-idempotency should be fixed, not documented** — this diff is what documents it, per AC1; local-dev-only script.
13. **File List omits `sprint-status.yaml` / `story-review.md`** — workflow bookkeeping artifacts, deliberately excluded from the reviewed diff.

Also recorded as checked-and-sound by the layers (no action): the ledger pruning is exactly the six promised bullets with all three must-keep items surviving; the AC5 rename is confined to `SubscriptionService.java` with zero leftovers (12 occurrences); `jar uf` preserves nested-jar integrity (verified empirically); the CI step placement is correct (`load: 'true'` present in `pr-build.yml:133`, absent in `ci.yml`); the script's exec bit will commit (`0755`, `core.fileMode=true`); `locked.getCreatedAt()` can never be null; and the post-lock `checkEligibility` introduces no new error code or HTTP status.

---

## Dev Notes

- **This story went through an independent adversarial review (`story-review.md`, 2026-10-08) before implementation began, and several findings required real rewrites — read this section as corrected, not as the original draft.** The review's most consequential catches: AC2's original mechanism description was backwards (it blamed the wrong `User` class — see Finding 2/Design B) and its proposed precedence order would have regressed a hypothetical dual-role admin; AC3's original justification cited a ledger recommendation ("add a LIMIT") that the *same* ledger's own later review round had explicitly superseded ("no longer needed if DISTINCT lands" — DISTINCT landed), and missed that this session's own earlier work added a new, more-frequently-reached caller of the same query; AC5 undercounted a rename's blast radius by more than half (5 cited vs. 12 actual) and missed a real forward-looking dependency; AC6's extraction steps targeted a file path that doesn't exist on the running container, and would have silently failed if duplicated into `ci.yml`. All incorporated below and in the Context/Design sections above. Two of the review's own findings were themselves wrong and were caught by its own internal adversarial re-check (see `story-review.md` §5, K1/K2) — a reminder to re-verify this review's claims too where it matters, not just trust the "CONFIRMED" labels.
- **`deferred-work.md` cleanup, per this project's standing convention (delete resolved bullets in the same commit as the fix, not at story-creation time):** when this story's items ship, delete: the `findFirst()` role-precedence bullet under "Deferred from: code review of skillars-deferred-142 (2026-10-05)"; the `findQualifyingCompletedBookings` bullet under "Deferred from: code review of skillars-deferred-145 (2026-10-06)" (leave the i18n bullet in that same section alone — it is already marked `[CLOSED]`); the `sinceAfter`/re-check bullet under "Deferred from: code review of skillars-deferred-145, round 2 (2026-10-06)"; and the self-registered-PLAYER-test bullet plus the git.properties-CI-assertion bullet under "Deferred from: code review of skillars-deferred-149 (2026-10-08)". **Also delete** that same section's "Clear-on-login has only mock-level coverage" bullet — not because this story fixes it, but because it is now factually obsolete (see Context Finding note above); deleting it is ledger hygiene, not a credit this story should claim. **Do NOT delete** the "required status checks... not a versioned artifact" bullet, the `/dashboard` `DEFAULT_ROUTE` nav-link-gap bullet (deferred-141), or the `markUsedByTokenHash`/`markAllUsedByUserId` unguarded-DB-failure bullet (deferred-143) — all three are genuinely open and non-actionable right now, not closed or human-only (an earlier draft of this story mischaracterized the latter two — corrected in the "Explicitly NOT picked up" list above).
- **AC2 (JWT role precedence) is genuinely unreachable today — do not let that reduce rigor.** The pick is already deterministic (alphabetical, via Spring Security's own `User.sortAuthorities`), which is precisely why a naive "prove it's deterministic" test would be worthless — see Design B. Treat the new test's *assertion of intended order* as the actual deliverable, not the production code change itself, and treat the `ADMIN`-first ordering choice as something to sanity-check against the live frontend gates, not something to assume is obviously right.
- **AC3's bound value is a judgment call, not a prescribed number — but the `ORDER BY` is not optional.** Err toward a generous bound (tens, not single digits) for the `limit`; the ordering is what makes any bound safe to add at all (Design C).
- **AC4's fix must not change any currently-passing test's outcome.** The whole point is that the gap is latent — if implementing the fix breaks an existing test, that test was relying on the bug, and the test (not the fix) is probably wrong. Investigate before "fixing" the test.
- **AC5's rename is optional/secondary — do not let it block the AC — but size it correctly before deciding.** 12 occurrences in one file (`SubscriptionService.java`) is still small; don't skip it based on the original (wrong) 5-occurrence estimate. Skip it only if a fresh grep at implementation time finds it touching a file other than `SubscriptionService.java`.
- **AC6 is deliberately scoped to `pr-build.yml` only — this is a decision, not an oversight to "fix" by also touching `ci.yml`.** `ci.yml`'s equivalent job doesn't load the image locally; adding the same step there without also adding `load: 'true'` would just fail, and adding `load: 'true'` there is its own tradeoff, out of scope for this AC.
- **General:** every citation in this version of the story was independently re-verified against HEAD `162608ff`, including after the adversarial review surfaced drift in the original draft's citations (see the corrected ones throughout this document — `JwtManagerImpl`'s role-resolution method is at `:113-124`, not the ledger's stale `:105`; `Dockerfile`'s skip flag is at `:20`, not `:24`; etc.). Re-verify line numbers again at actual implementation time regardless — this codebase changes fast enough that even same-day citations drift.

### Project Structure Notes

- Changed file (docs/tooling, not source): `docs/deployment/local/seed-accounts.sql`.
- Changed backend files: `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java`, `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`, `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`, `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`.
- Changed test files: `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java`, `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java` (or a new dedicated test — dev's discretion), `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java`. **Verify, don't assume:** `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceTest.java` (has the 4-arg `findQualifyingCompletedBookings` mock stub AC3 must not break) and `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceTest.java`/`SubscriptionServiceOwnershipIT.java` (if the AC5 rename lands, confirm neither references the old parameter name by position in a way that breaks).
- Changed infra/CI files: `.github/workflows/pr-build.yml`, possibly `.github/workflows/ci.yml`, possibly a new `.github/scripts/assert-git-provenance.sh`.
- No schema migration, no new REST endpoint, no frontend change expected. If implementation reveals a frontend change is needed anywhere, treat that as a signal this story's scope assumption was wrong and flag it rather than silently expanding scope.
- Deliberately NOT touched: `marketplace.coach_subscriptions`/`payment.coach_subscriptions`/`payment.coach_stripe_accounts`/`payment.parent_credit_ledger` table structures (Finding 1 confirmed these need no schema change, only a query-join fix in the seed script itself).

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-142 (2026-10-05)"] — Finding 2 (JWT role precedence).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-145 (2026-10-06)"] — Finding 3 (unbounded booking query).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-145, round 2 (2026-10-06)"] — Finding 4 (review-update re-check gap).
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, "Deferred from: code review of skillars-deferred-149 (2026-10-08)"] — Findings 5 and 6 (subscription test gap, CI git-provenance assertion), and the excluded-obsolete "Clear-on-login" bullet.
- [Source: this session's own live-testing follow-up to `skillars-deferred-149`, 2026-10-08] — Finding 1 (seed-accounts.sql), found and verified directly against HEAD, not sourced from the ledger.
- [Source: `_bmad-output/implementation-artifacts/story-review.md`, 2026-10-08] — the independent pre-implementation adversarial review this version of the story responds to; see Change Log v1.1 for the full list of what it caught and what was independently re-verified before being applied.
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java:231-250`] — the `resolveRole` precedence-order precedent Design B partially follows (for `COACH`/`PARENT`/`PLAYER` only — see Finding 2 for why `ADMIN` isn't copied from here).
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/api/ReviewResource.java:162-166`] — the second, disagreeing `resolveRole` precedent (defaults to `PLAYER`, no `ADMIN` handling) — read this alongside `MessagingResource`'s before assuming either settles the `ADMIN` question.
- [Source: `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:299`, `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:901-908`] — the existing dual-ownership (`userId`/`parentId`) pattern Design A's SQL fix mirrors.
- [Source: `V138__baseline_schema.sql:896-912`] — `main.player_profiles`'s `chk_pp_owner` constraint, the root fact behind Finding 1.
- [Source: `.github/scripts/assert-context-count.sh`] — the existing "assert via script, fail CI loudly" precedent Design F may follow.
- [Source: `spring-security-core-6.5.11.jar` (this project's resolved version), class `org.springframework.security.core.userdetails.User`, inspected via `javap`] — confirms `sortAuthorities`/`AuthorityComparator`, the actual mechanism behind Finding 2 (not the JPA `User` entity's `HashSet`, which an earlier draft of this finding incorrectly cited).

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5

### Debug Log References

- `mvn -o test -Dtest=JwtManagerImplTest,BookingRepositoryIT,ReviewSubmissionServiceTest,ReviewSubmissionIT,ReviewSubmissionServiceConcurrencyIT,SubscriptionResourceIT,SubscriptionServiceTest,SubscriptionServiceOwnershipIT` — final combined regression, 98/98 passed, 0 failures/errors.
- Empirical AC1 verification: registered a real self-registered adult PLAYER account via the live local app (`POST /api/security/player/register` + DB-read verification token + `POST /api/security/player/verify-email` + login + `POST /api/security/players/me`), confirmed `player_profiles.parent_id IS NULL AND user_id IS NOT NULL`, ran the fixed `seed-accounts.sql` with `owner_email=<that player's email>` against the running local Postgres, confirmed the `payment.player_subscriptions` row was seeded (previously would have matched zero rows under the old join).
- Empirical AC6 verification: `docker build --build-arg GIT_COMMIT_SHA=$(git rev-parse HEAD) -t skillars-app:test-real .` then `assert-git-provenance.sh skillars-app:test-real` → PASS (`git.dirty=false`, real SHA). `docker build -t skillars-app:test-local .` (no build-arg, `local` default) then `assert-git-provenance.sh skillars-app:test-local` → FAIL as expected (`git.dirty=true`, `git.commit.id.full=local`). Both test images removed after verification.
- **Code review response (2026-10-09), empirical re-verification of the review's own claims:**
  - Rebuilt `skillars-app:test-real`/`test-local` images again after patching `assert-git-provenance.sh` with SHA comparison: confirmed PASS with the matching SHA, FAIL with a deliberately wrong SHA (all three failure conditions — dirty, non-local, SHA-mismatch — fire together on the `local`-default image), and PASS with no 2nd arg (backward compat preserved).
  - Temporarily reverted `JwtManagerImpl.setSkillarsProfileCookie` to the old `.findFirst()` implementation and re-ran the two rewritten `JwtManagerImplTest` dual-role cases: both correctly FAILED against the old code (`testRefreshLoginToken_dualRole_resolvesToHigherPrecedenceParentOverPlayer`/`...AdminOverCoach`, 2/2 failures), confirming the original versions of these tests (flagged by the review as unable to discriminate pre/post-fix code) were a genuine gap and the rewrite actually closes it. Restored the fix; re-ran the full class (37/37 passed).
  - Re-running the new `BookingRepositoryIT` negative-fixture test surfaced two real, independent bugs in the fix itself (not the review's claim, but found while fixing it): the `tooRecent`/`tooOld` time-boundary pushes were insufficiently far past the maturity window (off by exactly the margin that made the old assertion accidentally pass), and the raw `jdbcTemplate.update()` calls overriding `updated_at` were bare — never committed, this project's own documented Hikari-autocommit-false pitfall (see `project_skillars_it_autocommit` memory). Both fixed; `BookingRepositoryIT` now 11/11 passing with all four negative fixtures correctly excluded.
- `mvn -o test -Dtest=JwtManagerImplTest,BookingRepositoryIT,ReviewSubmissionServiceTest,ReviewSubmissionIT,ReviewSubmissionServiceConcurrencyIT,SubscriptionResourceIT,SubscriptionServiceTest,SubscriptionServiceOwnershipIT` re-run after all review-response fixes — 98/98 passed, 0 failures/errors.

### Completion Notes List

- **AC1**: Widened both the step-5 `INSERT` join and the verification `SELECT` join in `seed-accounts.sql` to `pp.parent_id OR pp.user_id`; corrected the false "both cases are covered" claim in both the step-5 comment and the file's header prerequisites list; added explicit header notes that verification is manual-only and that step 4 (credit-ledger seed) is non-idempotent; documented that `owner_email` may be a parent's or a self-registered player's own email. Empirically verified end-to-end against a real local Postgres (see Debug Log).
- **AC2**: Replaced the `.findFirst()` pick in `JwtManagerImpl.setSkillarsProfileCookie` with an explicit `ADMIN > COACH > PARENT > PLAYER` precedence over a `Set<SkillarsRole>`, preserving today's accidental behavior. Grepped the three admin-gating frontend call sites (`auth.store.js`, `routes.js`, `MainLayout.vue`) — all gate on the resolved role string equalling `ADMIN`, so `ADMIN`-first introduces no conflict. Added two new `JwtManagerImplTest` cases pinning the *intended* order for `PARENT+PLAYER` → `PARENT` and `ADMIN+COACH` → `ADMIN`. Full class: 37/37 passed.
- **AC3**: Added an HQL-literal `limit 50` plus `ORDER BY b.playerId` to `BookingRepository.findQualifyingCompletedBookings`, keeping the method's 4-arg signature unchanged (confirmed `ReviewSubmissionServiceTest.java`'s mock stub still compiles/passes). Added a new `BookingRepositoryIT` test seeding 55 distinct qualifying playerIds and asserting the ordering+cap combination deterministically returns exactly the 50 lowest playerIds, not an arbitrary Postgres-dependent subset. Full regression (`ReviewSubmissionServiceTest`/`ReviewSubmissionIT`/`ReviewSubmissionServiceConcurrencyIT`): 29/29 passed.
- **AC4**: `ReviewSubmissionService.updateReview` now re-derives `sinceAfter` from the refreshed `locked` instance and re-runs `checkEligibility` after `entityManager.refresh`, alongside the existing post-refresh moderation-status/cooldown re-checks. New concurrency test in `ReviewSubmissionServiceConcurrencyIT` uses the prescribed timing-choreography precedent (external holder takes a raw `FOR UPDATE`, writes only `author_last_edited_at` — decoupling it from `last_modified_at` — holds via latch, then commits) to prove the post-refresh re-check actually fires and rejects with `NO_QUALIFYING_SESSION` in a scenario a fire-and-forget write cannot reach. Full class: 5/5 passed.
- **AC5**: Added three new `SubscriptionResourceIT` cases (`@WithMockUser(roles = "PLAYER")`, no fixture needed since this class is a `@WebMvcTest` mock slice) proving a self-registered PLAYER caller is rejected 403 by all three mutating subscription endpoints. Re-verified the `parentUserId` occurrence count via fresh grep (12, all in `SubscriptionService.java`) and renamed to `callerUserId` throughout that one file, including the `:338` Stripe-customer lookup usage. Confirmed no other file references the old name. Full regression (`SubscriptionResourceIT`/`SubscriptionServiceTest`/`SubscriptionServiceOwnershipIT`): 20/20 passed.
- **AC6**: Added `.github/scripts/assert-git-provenance.sh` (new script, mirrors `assert-context-count.sh`'s style) and wired it into `pr-build.yml`'s `docker-image` job only, between the image build and the Trivy scan. The script `docker create`s a container, `docker cp`s `app.jar` out, `unzip -p`s `BOOT-INF/classes/git.properties` from inside the jar (not a bare container-filesystem path), and fails if `git.dirty != false` or the commit SHA is empty/`local`; cleans up the container and extracted jar via a `trap` regardless of outcome. Confirmed `ci.yml`'s `build-and-push` job has no `load:` input (defaults `false`) and was correctly left untouched. Empirically verified both the pass and fail paths by building real local Docker images (see Debug Log) — no leftover containers after either run.
- **AC7**: Full combined targeted regression across every touched class: 98/98 passed, 0 failures/errors (see Debug Log). Zero frontend files touched (confirmed via `git status`). Updated `deferred-work.md`: deleted the `findFirst()` role-precedence bullet (deferred-142 section), the `findQualifyingCompletedBookings` bullet (deferred-145 section, i18n bullet in the same section left untouched since already `[CLOSED]`), the `sinceAfter`/re-check bullet and its now-empty parent header (deferred-145-round-2 section), the self-registered-PLAYER-test bullet and the git-provenance-CI-assertion bullet (deferred-149 section, required-status-checks bullet in the same section left untouched since still genuinely open), and the factually-obsolete "Clear-on-login has only mock-level coverage" bullet (same section) per Dev Notes — including a dangling orphan "When picked up" line belonging to that last bullet that a first pass missed.
- No `mvn verify` run locally at any point, per standing project convention — GitHub CI is the full-verification gate.

### File List

- `docs/deployment/local/seed-accounts.sql` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java` (modified)
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java` (modified)
- `src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java` (modified)
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java` (modified)
- `src/test/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionServiceConcurrencyIT.java` (modified)
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` (modified)
- `src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceIT.java` (modified)
- `.github/scripts/assert-git-provenance.sh` (new)
- `.github/workflows/pr-build.yml` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)

## Change Log

| Date | Version | Change | Author |
| :--- | :--- | :--- | :--- |
| 2026-10-08 | 1.0 | Created via `/bmad-create-story` at the user's explicit request to bundle an intentionally large story: one fresh finding (seed-accounts.sql ownership gap, found this session) plus five independently re-verified still-open items mined from `deferred-work.md`'s unmined tail (code reviews of skillars-deferred-142, -145 x2, -149). One additional ledger item ("Clear-on-login has only mock-level coverage") was found to be factually obsolete — not fixed, moot — per the user's explicit warning that some ledger bullets may already be stale; flagged for deletion without being picked up as work. Two further items (required-status-checks-as-code, `[deferred-19]` reset-mean drift) were confirmed open but excluded as decision-needed/non-code-fix respectively. Status -> ready-for-dev. | Claude Sonnet 5 |
| 2026-10-08 | 1.1 | **Pre-implementation review response** (`story-review.md`, independently re-verified against HEAD `162608ff` before applying — every finding re-checked by direct read/`javap`/`git show`, not taken on the review's "CONFIRMED" label alone). One BLOCKING defect found and fixed: AC2's stated root-cause mechanism was backwards (blamed the JPA `User` entity's `HashSet`; the real mechanism is Spring Security's own `User.sortAuthorities`, confirmed by decompiling this project's actual resolved `spring-security-core-6.5.11.jar`, which already makes today's pick deterministic — alphabetically, giving `ADMIN` top priority by accident) and Design B's original snippet (`COACH > PARENT > PLAYER` with no `ADMIN` handling) would have regressed a hypothetical dual-role admin; rewritten to `ADMIN > COACH > PARENT > PLAYER`, explicitly flagged as "preserve current behavior," and the new test now asserts the intended order rather than mere determinism. Two HIGH defects in AC3: the original "add a LIMIT" justification cited a ledger recommendation the *same* story's own round-2 review had already superseded five days earlier ("no longer needed if DISTINCT lands" — DISTINCT landed); replaced with a fresh justification (this session's own earlier `checkWriteEligibility`/`Instant.EPOCH` addition to `ReviewSubmissionService`, reached from the new coach-profile-page eligibility endpoint on every page load) and added a missing `ORDER BY` requirement (a bare `LIMIT` with no ordering on a `SELECT DISTINCT` is a silent false-denial risk, aggravated by `booking.bookings` having no FK on `player_id`) plus a note that `Pageable` would break `ReviewSubmissionServiceTest.java:83`'s existing mock stub. Two further HIGH defects in AC5: the rename's blast radius was undercounted (5 cited occurrences vs. 12 actual, all in one file) and a real forward-looking dependency was missed (`SubscriptionService.java:338`'s Stripe-customer-by-caller's-own-id lookup); both corrected, and the fixture advice corrected separately (`SubscriptionResourceIT` is a `@WebMvcTest` mock slice needing no Testcontainers fixture at all, not real-Postgres setup). Two HIGH defects in AC6: the extraction steps targeted a container-filesystem path that doesn't exist (`git.properties` lives inside the jar, not loose on disk) and the step would silently fail if duplicated into `ci.yml` (that job never loads its image locally); both fixed, scope explicitly narrowed to `pr-build.yml`. MEDIUM/LOW citation drifts also corrected: `createSelfOwnedPlayerProfile` shipped in `1f24a5e5` (2026-07-04), not `15d1ca2b` as originally cited (conclusion unchanged — both predate the script); the ledger-exhaustiveness claim ("everything before deferred-142 already closed") was an overstatement contradicted by the ledger's own header convention and corrected; two genuinely-open tail bullets (the `/dashboard` `DEFAULT_ROUTE` nav gap, `markUsedByTokenHash` unguarded-DB-failure) were previously miscategorized as "human-only or already closed" in Dev Notes and are now correctly listed as open-but-non-actionable, with the instruction not to delete them left unchanged (it was already correct, only the reasoning was wrong). AC1 and AC4 were confirmed sound as originally designed, with added clarity (AC1: manual-only verification, non-idempotent credit-ledger step, real fixture complexity; AC4: a corrected "sole writer" claim and a concrete test-recipe pointing at two real precedents, `ReviewUpdateIT.java:302-307` and `ReviewSubmissionIT.java:703-726`, since a fire-and-forget racing write cannot work). Status remains ready-for-dev — this is a pre-implementation correction pass, nothing has been built yet. | Claude Sonnet 5 |
| 2026-10-09 | 2.0 | **Implementation complete.** All 6 ACs implemented and independently verified: seed-script ownership fix (AC1, empirically verified against a real self-registered-player account), JWT role-precedence hardening (AC2, 2 new test cases), bounded+ordered booking-eligibility query (AC3, 1 new repository IT test), review-update eligibility re-check (AC4, 1 new concurrency IT test using the prescribed timing-choreography precedent), subscription self-player test coverage + param rename (AC5, 3 new controller-slice tests + 12-occurrence rename), CI git-provenance assertion (AC6, new script + workflow step, empirically verified pass/fail). Full combined targeted regression: 98/98 passed. `deferred-work.md` pruned of the six now-resolved/obsolete bullets per Dev Notes. Zero frontend files touched. Status -> review. | Claude Sonnet 5 |
| 2026-10-09 | 2.1 | **Code review response** (`/bmad-code-review`, three parallel layers + orchestrator; 13 false positives dismissed, independently spot-checked and confirmed correct). Resolved 1 Decision + 15 Patch findings, all independently re-verified against current source before being accepted (per explicit instruction to watch for false positives in the review itself) — most consequential catches, found during that re-verification rather than merely applied on the review's say-so: the two AC2 `JwtManagerImplTest` cases could not actually discriminate pre- from post-fix code (this codebase's `Principal` extends Spring Security's `User`, which re-sorts authorities alphabetically before this fix's own precedence logic ever runs, and the fix's chosen order was deliberately picked to equal that alphabetical order — so no principal-based test can ever tell the two mechanisms apart); rewrote both via hand-built raw tokens with non-alphabetical claim ordering, and confirmed by temporarily reverting the production fix that they now correctly fail pre-fix and pass post-fix. Fixing the new negative-fixture `BookingRepositoryIT` test (added per another finding) surfaced two further real, independent bugs: insufficiently-pushed time boundaries, and bare `jdbcTemplate.update()` calls that never committed (this project's own documented autocommit pitfall) — both fixed. Also fixed: a misleading `BookingRepository` comment that overclaimed `ORDER BY` provides safety rather than mere determinism; a provenance gate that never compared the embedded SHA against the run's own commit (added an `expected-commit-sha` arg, re-verified empirically); a concurrency test that could pass for the wrong reason if its timing ever slipped (added a wall-clock disambiguation assertion); a missing truncation-observability log line; a missing in-code trace of AC2's documented residual risk; a still-parent-only seed-script prerequisite; a stale test-class comment; three shell-script hardening items (`mktemp` guard, surfaced stderr, escaped regex dots); a duplicated-logic test helper (replaced with AssertJ's own `catchThrowable`); a nested-ternary readability refactor (`EnumSet` + precedence list); and an AC4 comment gap around the re-check's lock-hold-duration cost. Full combined regression re-run after all fixes: 98/98 passed. Status remains review. | Claude Sonnet 5 |
