# Story Deferred-42: OTP SecureRandom Reuse, Session-Pack Dead-Query Removal & Duplicate i18n Key Cleanup

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want three independently-real, independently-small, decision-light hygiene gaps found by re-mining the
full `deferred-work.md` ledger closed in one pass — three OTP generators reusing the same `SecureRandom`
pattern this codebase already established elsewhere, a confirmed-dead repository query method deleted, and
a duplicate/unused i18n key removed from all three live locale bundles —
so that these gaps stop accumulating as separate single-item stories, matching the bundling convention every
prior `skillars-deferred-*` pass has followed.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-41`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1603 lines as of this story's creation) was
read end to end, top to bottom, following the file's own documented protocol (near its top): sections are
**not** chronological, so the date in each `## Deferred from:` heading was read individually; an item is
non-actionable (skipped) if it carries `[CLOSED by ...]`, `[PICKED UP by ...]`, `[STALE — ...]`,
`[DISMISSED — ...]`, `[WITHDRAWN — ...]`, `[SUPERSEDED — ...]`, `[MITIGATED — ...]`, or prose saying it was
"examined and deliberately left alone". Only items carrying none of these markers (or a `STILL OPEN` audit
tag not since picked up) were treated as candidates. Every candidate below was re-verified against the live
code — not trusted from the ledger's own prose — before being used, including two items discovered during
that re-verification to already be resolved (bundled as this story's own hygiene AC, mirroring `deferred-41`
AC5's pattern).

**Three genuinely open, decision-light items were found and are bundled here:**

- **Three OTP generators re-instantiate `SecureRandom` on every call, when this codebase already
  established a shared-static-instance pattern for the identical need.** `## Deferred from: code review of
  skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)`, D8: "SecureRandom
  re-instantiated per generateOtp() call — low severity performance concern
  [CoachRegistrationService.java:generateOtp]". Re-verified today: `CoachRegistrationService.java:213`
  still opens `generateOtp()` with a fresh `SecureRandom random = new SecureRandom();` on every invocation.
  Re-verification also found the identical, byte-for-byte-copied pattern in **two more files the ledger
  item never named** — `ParentRegistrationService.java:235` and `PlayerRegistrationService.java:239` both
  have the exact same `private String generateOtp() { SecureRandom random = new SecureRandom(); int code =
  100000 + random.nextInt(900000); return String.valueOf(code); }` method body, confirmed identical by
  direct read of all three files. This codebase already has an established, directly-mirrorable pattern for
  exactly this: `TwoFactorLoginService.java:26` declares `private static final SecureRandom SECURE_RANDOM =
  new SecureRandom();` as a class-level field reused across every call, and `Secret.java:37` does the same
  — both relying on `SecureRandom`'s documented thread-safety to share one instance safely. No
  `@EqualsAndHashCode`-style novel pattern needed; this is a one-line-per-file mirror of an
  already-shipped, already-in-production idiom. **Candidate for this story (AC1).**

- **`SessionPackPurchaseRepository` carries a dead derived-query method with zero callers anywhere in the
  codebase.** `## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)`,
  D2: "Duplicate expiry query methods — `findByCoachIdAndExpiresAtBetween...` (coach-scoped) and
  `findExpiringWithinWindowAndSessionsRemaining` (JPQL all-coaches) overlap; coach-scoped method appears
  unused; verify in Group 2 service review [`SessionPackPurchaseRepository.java:21-25`]". This item was
  never actually verified in any subsequent story or review pass in this ledger's history — re-verified
  today for the first time. `SessionPackPurchaseRepository.java:23-24` still declares
  `findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan(UUID coachId, Instant
  from, Instant to, int minSessions)`, and `grep -rn
  "findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan" src/main src/test`
  returns exactly one hit — the method's own declaration. The two schedulers that read pack-expiry state
  (`SessionPackExpiryNotifier.java:57` and `SessionPackForfeitureScheduler.java:38`) call
  `findExpiringWithinWindowAndSessionsRemaining` and `findExpiredNotYetNotified` respectively — neither
  calls the coach-scoped method. This codebase already has an established, directly-mirrorable pattern for
  removing confirmed-dead code once verified by grep: `getNeglectedSkills` in `development.api.js` was
  removed as dead code by `skillars-deferred-21` AC1, `composables/useTimezone.js` was deleted outright by
  `skillars-deferred-23` AC3 once confirmed zero-caller, and the `PURGED` dead-code branch in
  `VideoManagementPage.vue` was removed by `skillars-deferred-21` AC2 — all three verified unused by grep
  first, exactly as this item now is. **Candidate for this story (AC2).**

- **A duplicate, unused i18n key risks silent divergence from its sibling.** `## Deferred from: code review
  of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)`: "Duplicate i18n key
  `auth.coach.bioSanitizationWarning` (near-identical to `contactDetailWarning`, trailing period differs) —
  unused by this story but will silently diverge if either string is updated [src/frontend/src/i18n/en/
  index.js]". The exact file path this item names (`i18n/en/index.js`) no longer exists — that redundant
  fourth bundle was deleted outright by `skillars-uat-4` AC3, per this ledger's own record of that closure.
  The underlying key, however, survived the bundle consolidation and is still present, still duplicated,
  and still unused in all three of the bundles that remain live today: `en-US/index.js:122`,
  `de-DE/index.js:115`, and `fr-FR/index.js:232` each carry `bioSanitizationWarning:` immediately alongside
  a near-identical `contactDetailWarning:` string under the same `auth.coach` block (differing only in a
  trailing period — e.g. `en-US`: `'Contact details will be removed on save.'` vs. `'Contact details will
  be removed on save'`). Confirmed unused today by `grep -rn "bioSanitizationWarning" src/frontend/src
  --include="*.vue" --include="*.js"` outside the three `i18n/*/index.js` bundles themselves — zero hits.
  This codebase already has an established, directly-mirrorable pattern for removing a confirmed-orphaned
  i18n key once its only consumer's raw-input UI was replaced: `skillars-uat-6` AC3 removed
  `subscription.paymentMethodId`/`subscription.paymentMethodHint`/`subscription.coach.paymentMethodRequired`
  from all locale bundles once `CoachSubscriptionPage.vue`'s raw input was replaced and grep confirmed
  zero remaining consumers. **Candidate for this story (AC3).**

**Two additional items were found to be stale during this research — not story material, but corrected
here as a hygiene by-product (AC4):**

- **Def7 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system`,
  2026-06-20), never audited before now — found already fixed.** "`VideoConfig.quotaProviderValidator`
  consistency guarantee logging — AC 10 requires logging the guarantee at startup; validator not in this
  diff; needs verification that it calls `getConsistencyGuarantee()` and logs it. [Out-of-diff verification
  needed]" Re-verified today: `VideoConfig.java:53-63`'s `quotaProviderValidator` bean already calls
  `log.info("QuotaProvider active: {} — consistency guarantee: {}", quotaProvider.getClass().getSimpleName(),
  quotaProvider.getConsistencyGuarantee())` unconditionally at startup, immediately after resolving the
  active `QuotaProvider` bean — exactly the verification this item asked for. Superseded by shipped code,
  not a gap.
- **The `getRequestedStartTime()` null-guard item (`## Deferred from: code review of
  skillars-3-4-booking-state-machine-sse`, 2026-06-15), never audited before now — found moot, its target
  method deleted.** "`getRequestedStartTime()` null not guarded before `ChronoUnit.HOURS.between()` in
  `applyRefundLogic` — in practice never null (set at creation); add guard if entity nullability changes
  [BookingService.java:256]". Re-verified today: `grep -rn "applyRefundLogic" src/main/java src/test/java`
  returns zero hits — the method itself, along with the `Booking.refundEligibility`/`Booking.refundAmount`
  fields it wrote, was deleted outright by `skillars-deferred-28`'s own deferred-work item
  (`## Deferred from: skillars-deferred-28-...`, "Two independent refund-eligibility computations can
  disagree on the same booking"), closed by `skillars-deferred-33` AC7, which "deleted the dead scaffolding
  entirely rather than reconciling the two rules" via migration `V97__drop_booking_refund_eligibility_and_
  amount.sql`. There is no `applyRefundLogic` method left to guard. Superseded by deletion, not a gap.

**Decision made during this story's creation — why these three and not others:** every other candidate
examined either already carries its own "examined and left alone"/"deliberately not fixed"/"needs a
design decision" reasoning on record (e.g. the `jakarta.persistence.lock.timeout`-has-no-effect gap
tracked across four repositories, explicitly deferred pending a Postgres-locking design decision; the
`NeglectedSkillDetectionService` per-player-loop bound gap, whose own in-code comment explains a
mechanical `MAX_RUN_DURATION`-style bail-out mirroring `QuotaReservationTimeoutService` is unsafe for this
specific weekly, un-chunked job and needs real design work instead; the `GdprExportService.buildBookings`
`.distinct()`-on-object-identity concern, whose actual reachability turned out to hinge on unresolved
questions about `Booking.parentId`/`playerId` id-space semantics for self-registered players that this
story's research could not fully resolve with confidence, so it was deliberately left alone rather than
risk shipping a mechanical "fix" against a misdiagnosed premise); needs product input (the
`phone_otp_tokens` partial-unique-index item, which requires deciding whether existing-token invalidation
already makes a DB-level constraint safe to add); or would itself require the standing, repeatedly-declined
frontend-test-infrastructure investment (every `skillars-deferred-*` pass since `-17` has left this alone
for the same reason, and this pass leaves it alone too). AC1 and AC2 are backend fixes across three
unrelated modules (`security`, `payment`); AC3 is a frontend i18n fix unrelated to either — bundled here
purely because all three are small, real, decision-light, and this pass was asked to bundle rather than
defer a candidate that clears this bar.

## Acceptance Criteria

1. **AC1 — `CoachRegistrationService`, `ParentRegistrationService` and `PlayerRegistrationService` reuse a
   shared `SecureRandom` instance, the same way `TwoFactorLoginService` and `Secret` already do.** In each
   of the three files' `generateOtp()` methods
   (`CoachRegistrationService.java:212-216`, `ParentRegistrationService.java:234-238`,
   `PlayerRegistrationService.java:238-242` — all three currently identical:
   ```java
   private String generateOtp() {
       SecureRandom random = new SecureRandom();
       int code = 100000 + random.nextInt(900000);
       return String.valueOf(code);
   }
   ```
   ), replace the per-call `SecureRandom random = new SecureRandom();` with a class-level
   `private static final SecureRandom SECURE_RANDOM = new SecureRandom();` field (declared alongside each
   class's existing fields, matching `TwoFactorLoginService.java:26`'s placement immediately after the
   class declaration and before the constructor-injected fields), and change the method body to read
   `int code = 100000 + SECURE_RANDOM.nextInt(900000);`. No other change to any of the three methods or
   their callers. Behavior-preserving: `SecureRandom.nextInt(int)` is documented thread-safe, matching the
   exact justification `TwoFactorLoginService`/`Secret.java` already rely on for sharing one instance
   across concurrent requests; the generated codes remain uniformly distributed 6-digit numbers exactly as
   before.

2. **AC2 — `SessionPackPurchaseRepository`'s confirmed-dead
   `findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan` method is deleted.**
   Remove the method declaration at `SessionPackPurchaseRepository.java:23-24` in full. The comment block
   immediately below it (lines 26-28, which documents `findExpiringWithinWindowAndSessionsRemaining`) has a
   third sentence — "The derived query above is a different caller — leave it alone" — that refers to
   exactly the method this AC deletes (it is the only method-name-derived, non-`@Query` method directly
   above that comment). Once lines 23-24 are gone, that sentence becomes dangling — there will be no
   derived query directly above it anymore. Remove or reword that sentence so the comment no longer
   references a deleted method; keep the rest of the comment (the `expiryWarnedAt IS NULL` rationale and
   the `findExpiredNotYetNotified` mirror note), since that part still documents
   `findExpiringWithinWindowAndSessionsRemaining` correctly. No other method in the file changes. Verify no
   compile break: `grep -rn "findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan"
   src/main src/test` must return zero hits after the deletion, confirming this was genuinely unreferenced
   before and after.

3. **AC3 — Remove the duplicate, unused `auth.coach.bioSanitizationWarning` i18n key from all three live
   locale bundles.** Delete the `bioSanitizationWarning:` line from `en-US/index.js:122`, `de-DE/
   index.js:115`, and `fr-FR/index.js:232` (each sits inside the `auth.coach` block, immediately below or
   near a `contactDetailWarning:` entry carrying the same string with only a trailing-period difference).
   Do not touch any `contactDetailWarning` entry — those are the real, in-use keys across multiple
   `auth.*` sub-blocks and are unrelated to this cleanup beyond sharing similar text in one block. Verify
   via `grep -rn "bioSanitizationWarning" src/frontend/src` returning zero hits after the change (no `.vue`
   or `.js` file outside the three i18n bundles ever referenced this key, confirmed before making the
   change).

4. **AC4 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification
     Group B (2026-06-11)` D8 item (SecureRandom re-instantiation) with
     `` `[PICKED UP by skillars-deferred-42 AC1]` ``.
   - Tag the `## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)`
     D2 item (duplicate expiry query methods) with `` `[PICKED UP by skillars-deferred-42 AC2]` ``.
   - Tag the `## Deferred from: code review of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)`
     duplicate-i18n-key item with `` `[PICKED UP by skillars-deferred-42 AC3]` ``.
   - Tag Def7 (`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system`,
     2026-06-20) with `` `[STALE — verified against current code by skillars-deferred-42 story creation,
     2026-08-20: already fixed. VideoConfig.java:53-63's quotaProviderValidator bean already logs
     "QuotaProvider active: {} — consistency guarantee: {}" at startup, immediately after resolving the
     active QuotaProvider bean — exactly the verification this item asked for. Added by an earlier story,
     unannotated in this ledger.]` `` — do not delete the item, per this file's own "delete only once
     genuinely implemented, not once merely annotated" convention; the tag is enough for future audits to
     skip it.
   - Tag the `getRequestedStartTime()` null-guard item under `## Deferred from: code review of
     skillars-3-4-booking-state-machine-sse (2026-06-15)` with `` `[STALE — verified against current code
     by skillars-deferred-42 story creation, 2026-08-20: moot. applyRefundLogic — the method this item's
     null-guard concern was about — was deleted outright by skillars-deferred-33 AC7 (migration
     V97__drop_booking_refund_eligibility_and_amount.sql), which also dropped the
     Booking.refundEligibility/refundAmount fields it wrote. grep -rn "applyRefundLogic" src/main/java
     src/test/java returns zero hits. Superseded by deletion, not a gap.]` ``.

## Tasks / Subtasks

- [ ] Task 1: OTP `SecureRandom` reuse (AC: #1)
  - [ ] 1.1 In `CoachRegistrationService.java`, add `private static final SecureRandom SECURE_RANDOM = new
    SecureRandom();` as a class-level field and update `generateOtp()` to use it instead of a per-call
    local instance.
  - [ ] 1.2 Apply the identical change to `ParentRegistrationService.java` and `PlayerRegistrationService.java`.
  - [ ] 1.3 Run `CoachRegistrationResourceIT` and `ParentRegistrationResourceIT` (the two existing
    integration tests that exercise OTP-issuing registration flows end to end) and confirm they remain
    green — behavior-preserving, so no assertion should need updating. **These two ITs cover only
    `CoachRegistrationService` and `ParentRegistrationService`.** No test of any kind (integration or unit)
    references `PlayerRegistrationService` anywhere in this repo — confirmed by
    `grep -rln "PlayerRegistrationService" src/test` returning zero hits. Its `generateOtp()` change is the
    same mechanical, behavior-preserving edit mirrored identically across all three files, but is verified
    only by code-reading, not test execution. Either confirm via a manual/API-level smoke test that player
    registration's OTP flow still issues a valid 6-digit code after the change, or note explicitly in the
    Dev Agent Record that this file's change is unverified by automated tests (standing gap, not introduced
    by this AC).
- [ ] Task 2: `SessionPackPurchaseRepository` dead-query removal (AC: #2)
  - [ ] 2.1 Delete `findByCoachIdAndExpiresAtBetweenAndExtendedAtIsNullAndRemainingSessionsGreaterThan`
    from `SessionPackPurchaseRepository.java`.
  - [ ] 2.2 The adjacent comment block's (currently lines 26-28) third sentence — "The derived query above
    is a different caller — leave it alone" — refers to the method just deleted in 2.1. Remove or reword
    that sentence so it no longer dangles; keep the rest of the comment intact, since it correctly
    documents `findExpiringWithinWindowAndSessionsRemaining`.
  - [ ] 2.3 Confirm the module compiles and `SessionPackPurchaseRepositoryIT`,
    `SessionPackExpiryNotifierTest`/`SessionPackExpiryNotifierIT` (or whichever test classes currently
    cover this repository/its two live schedulers) remain green.
- [ ] Task 3: Duplicate i18n key removal (AC: #3)
  - [ ] 3.1 Remove the `bioSanitizationWarning` key from `en-US/index.js`, `de-DE/index.js`, and
    `fr-FR/index.js`.
  - [ ] 3.2 Run `npx eslint` on all three touched files and confirm clean.
- [x] Task 4: Ledger hygiene (AC: #4) — **already applied during story creation; verify only, do not
  re-apply.** See Dev Notes.
  - [x] 4.1 Apply `[PICKED UP by skillars-deferred-42 AC1]` to the `skillars-1-3` Group B D8 item.
  - [x] 4.2 Apply `[PICKED UP by skillars-deferred-42 AC2]` to the `skillars-7-2` Group 1 D2 item.
  - [x] 4.3 Apply `[PICKED UP by skillars-deferred-42 AC3]` to the `skillars-2-4` duplicate-i18n-key item.
  - [x] 4.4 Apply the two `[STALE — ...]` annotations to Def7 (`skillars-6-1`) and the
    `getRequestedStartTime()` null-guard item (`skillars-3-4`) as specified in AC4 above.

## Dev Notes

- **This story bundles three unrelated fixes across three modules (`security`, `payment`, frontend i18n)
  by explicit instruction — do not look for a unifying theme beyond "small, real, decision-light, and this
  pass was asked to bundle."**
- **AC1 is a pure mirror of an already-shipped pattern** — do not introduce a shared utility class or a new
  `SecureRandomProvider`-style abstraction; `TwoFactorLoginService` and `Secret.java` each declare their own
  `private static final SecureRandom` field independently, and this codebase has no shared-random-provider
  precedent to extend instead. Three separate one-line field declarations, matching the three separate
  files this fixes.
  - **`SecureRandom.nextInt(int)` thread-safety is the load-bearing assumption here — it is already relied
    on elsewhere in this codebase**, not a new risk introduced by this AC. `TwoFactorLoginService` has
    served concurrent login-2FA requests off its single shared instance since it shipped; nothing about
    OTP generation for registration flows is different in kind.
- **AC2's dead-code claim was verified by grep across both `src/main` and `src/test` before writing this
  story, and must be re-verified the same way immediately before deleting** — if a caller was added to the
  method between this story's creation and its implementation, stop and re-scope rather than deleting a
  now-live method.
- **AC3's key removal is scoped to exactly one key, in exactly three files** — do not also touch any
  `contactDetailWarning` entry, and do not attempt to reconcile the trailing-period inconsistency between
  the two keys' text (moot once `bioSanitizationWarning` no longer exists to diverge from its sibling).
- **AC4's ledger hygiene (Task 4) was already applied in this story's own creation commit** — all three
  `[PICKED UP by skillars-deferred-42 AC1-3]` tags and both `[STALE — ...]` annotations (Def7, the
  `skillars-3-4` `getRequestedStartTime()` item) are already present in `deferred-work.md` as committed
  alongside this story file (`c8a958a`). This deviates from this ledger's normal after-the-fact
  `[CLOSED by ...]` convention (tagging is usually applied once `dev-story` ships the fix, not at
  story-creation time) — noted here so `dev-story` does not waste a pass trying to re-apply tags that
  already exist, or fail to match a line that no longer has the untagged form the task text describes
  adding to. Confirm the three `[PICKED UP]` tags and two `[STALE]` annotations are still present verbatim
  in `deferred-work.md` before marking Task 4 complete; do not re-run the edits.
- Per `docs/validation-strategy.md`, run targeted tests only (the two existing registration ITs for AC1,
  whichever repository/scheduler tests cover `SessionPackPurchaseRepository` for AC2, and `npx eslint` on
  the three touched frontend i18n files for AC3) — do not run `mvn verify` unless targeted tests prove
  insufficient or this is final pre-PR validation.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java` — one new
  static field + one-line change inside `generateOtp()` (AC1).
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java` — same
  change (AC1).
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerRegistrationService.java` — same
  change (AC1).
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` — one
  method deletion (AC2).
- `src/frontend/src/i18n/en-US/index.js` — one key deletion (AC3).
- `src/frontend/src/i18n/de-DE/index.js` — one key deletion (AC3).
- `src/frontend/src/i18n/fr-FR/index.js` — one key deletion (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — three `[PICKED UP]` tags + two `[STALE]`
  corrections (AC4).
- No new backend or frontend files. No new tests required — AC1 and AC2 are behavior-preserving
  refactors/deletions covered by existing test suites; AC3 is a frontend-only key removal with no automated
  frontend test infrastructure in this repo (standing gap, per every prior story's Dev Notes), verified by
  `npx eslint` and grep instead. No changes to `TwoFactorLoginService.java`, `Secret.java`,
  `SessionPackExpiryNotifier.java`, `SessionPackForfeitureScheduler.java`, or any `contactDetailWarning`
  i18n entry — all are read-only precedents or adjacent-but-untouched content this story does not modify.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)` D8 — AC1's source]
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java`
  lines 212-216, `ParentRegistrationService.java` lines 234-238, `PlayerRegistrationService.java` lines
  238-242 — AC1's three targets]
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/TwoFactorLoginService.java`
  line 26, `src/main/java/com/softropic/skillars/platform/security/repo/Secret.java` line 37 — AC1's
  shared-static-`SecureRandom` pattern to mirror]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: adversarial code
  review of skillars-7-2 Group 1 DB+Entities (2026-06-24)` D2 — AC2's source]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
  lines 23-24 — AC2's target; lines 57 of `SessionPackExpiryNotifier.java` and line 38 of
  `SessionPackForfeitureScheduler.java` — confirming which two sibling queries remain live]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-2-4-contact-detail-sanitization-ux (2026-06-13)` — AC3's source]
- [Source: `src/frontend/src/i18n/en-US/index.js` line 122, `de-DE/index.js` line 115, `fr-FR/index.js`
  line 232 — AC3's three targets]
- [Source: `src/main/java/com/softropic/skillars/platform/video/config/VideoConfig.java` lines 53-63 —
  AC4's Def7 stale-item verification]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` (searched,
  zero hits for `applyRefundLogic`) — AC4's `skillars-3-4` stale-item verification]

## Dev Agent Record

### Completion Notes

(none yet — story not implemented)

### File List

(none yet — story not implemented)

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 3-item story per explicit instruction not to create another small story. Re-read `deferred-work.md` end to end (1603 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1 closes an OTP `SecureRandom` per-call re-instantiation gap in `CoachRegistrationService`, found also present (undiscovered by the ledger) in `ParentRegistrationService` and `PlayerRegistrationService`, by mirroring `TwoFactorLoginService`/`Secret.java`'s already-shipped shared-static-instance pattern. AC2 deletes a `SessionPackPurchaseRepository` derived-query method confirmed to have zero callers anywhere in `src/main`/`src/test`, an item never actually verified since it was first raised in 2026-06-24. AC3 removes a duplicate, unused `auth.coach.bioSanitizationWarning` i18n key from all three live locale bundles, mirroring the orphaned-key-removal pattern `skillars-uat-6` AC3 already established. AC4 additionally closes 2 stale ledger items (Def7 from `skillars-6-1`, and a `skillars-3-4` null-guard item mooted by `skillars-deferred-33` AC7's deletion of its target method) found already resolved as a research by-product of the full re-read. |
| 2026-08-20 | Senior-dev review (`_bmad-output/implementation-artifacts/story-review.md`) confirmed AC1-AC3 check out exactly against current code (every line-number citation and code excerpt verified byte-for-byte), and raised 3 findings. Finding 1 (Medium): Task 4's ledger-hygiene checkboxes described work already fully applied in this story's own creation commit (`c8a958a`) — verified all three `[PICKED UP]` tags and two `[STALE]` annotations are already present verbatim in `deferred-work.md`; the Dev Notes line claiming otherwise was also wrong. Resolved per the review's recommendation (a), mirroring how `skillars-deferred-41` resolved the identical mistake: Task 4.1-4.4 checked off as already done, and the Dev Notes line rewritten so `dev-story` treats AC4 as verify-only rather than re-applying tags that already exist. Finding 2 (Low-Medium): AC2's parenthetical incorrectly claimed the adjacent comment's "The derived query above is a different caller" sentence referred to "a different pairing than this AC removes" — it in fact refers to the exact method AC2 deletes (the only method-name-derived, non-`@Query` method directly above that comment). Corrected AC2 and Task 2.2 to state the sentence must be removed or reworded once the method above it is gone. Finding 3 (Low): AC1's `PlayerRegistrationService.generateOtp()` change has zero automated test coverage anywhere in the repo (`CoachRegistrationResourceIT`/`ParentRegistrationResourceIT` only cover the other two files) — Task 1.3 updated to flag this explicitly and require either a manual smoke test or an explicit Dev Agent Record note, rather than implying full coverage. |
