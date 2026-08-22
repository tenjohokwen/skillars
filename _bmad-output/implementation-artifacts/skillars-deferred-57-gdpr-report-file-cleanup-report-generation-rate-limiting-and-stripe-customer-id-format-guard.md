# Story Deferred-57: GDPR Report-File Cleanup, Report-Generation Rate Limiting & Stripe Customer ID Format Guard

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want GDPR erasure to actually delete a player's performance-report PDFs from S3 (not just their DB
rows), the report-generation endpoint to be rate-limited the same way every other cost-sensitive
endpoint in this codebase already is, and `payment.stripe_customers.stripe_customer_id` to carry the
same defence-in-depth format guard this project's other externally-sourced string columns already get,
so that a completed GDPR erasure request is actually complete (Article 17), a coach cannot cost-amplify
this platform's S3/PDF/email pipeline with a request loop, and a malformed Stripe customer id written by
a bug or a bad migration is caught at the DB boundary instead of surfacing later as a confusing Stripe
API error.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1753 lines at the time this story was created)
was re-mined end to end, section by section, at commit `38a89c8` (the tip of `skillars-deferred-56`).
`skillars-deferred-56`'s own creation notes already established that the recently-active tail
(post-`skillars-deferred-49`) and the oldest untouched span (2026-06-11 through 2026-06-25) were both
re-mined down to a single candidate apiece. This pass covered the remaining middle span this ledger's
audits have repeatedly flagged as "not re-checked" — 2026-06-19 through 2026-06-30 (Stories 5.1–5.6,
6.1–6.6, 7.1–7.5, 8.1–8.4, 10.1–10.4, 11.1–11.3) — plus a fresh direct-code re-verification of every
candidate found there, since none of those sections' claims have been independently re-checked by any
prior `skillars-deferred-*` story-creation pass.

Most of that span turned out to be either explicitly spec-intentional/accepted-by-design (the large
majority — TOCTOU windows already covered by a DB constraint, "revisit when X story ships" notes for
stories that still haven't shipped, N+1 query performance notes, frontend-test-infrastructure gaps this
ledger has left alone across dozens of prior stories, and items that now explicitly need a product/design
decision before any code can be written), or already closed by later, unrelated work and simply never
tagged. Three genuine, still-open, bounded, decision-light items survived, each verified live against the
current tree rather than trusted from the ledger's own text:

- **D1 (this story's AC1)** — sourced from `## Deferred from: code review of
  skillars-5-5-pdf-performance-report-unified-player-timeline (2026-06-19)`, item D5 (line 657): the
  ledger's own text asks for "`PerformanceReportRepository.deleteByPlayerId` + S3 object deletion for each
  `storage_key`" as part of a future GDPR erasure story. **Half of that is already done, unannotated**:
  `GdprErasureService.deletePlayerDevelopmentData(Long playerId)`
  (`GdprErasureService.java:185-196`) already calls
  `performanceReportRepository.deleteAllByPlayerId(playerId)` — a bulk `@Modifying` JPQL `DELETE`
  (`PerformanceReportRepository.java:11-13`), added by some earlier, unannotated change. **The S3 half is
  still missing**: a bulk JPQL `DELETE` never loads the `PerformanceReport` entities it deletes, so it
  never reads their `storage_key` column and never calls `fileStorageService.deleteRawBytes(...)` — the
  exact call this same service already makes for GDPR export zips three lines below
  (`GdprErasureService.java:129-138`), wrapped in the exact log-and-swallow `try`/`catch` pattern this AC
  reuses verbatim. Net effect today: every player's GDPR erasure silently leaves every one of their
  performance-report PDFs sitting in S3 forever — a live Article 17 gap, not a theoretical one. The
  ledger item's other half — "coach cannot correct defamatory or incorrect notes after generation" — is a
  separate, out-of-scope product question about post-generation editing; re-scoped out, tagged inline.
- **D2 (this story's AC2)** — sourced from the same `skillars-5-5` review, item D4 (line 656): `POST
  /api/development/players/{playerId}/reports` has no rate limit — "a coach can call in a loop; each call
  generates a PDF, uploads to S3, inserts a DB row, writes a timeline event, and queues a parent email;
  trivial cost-amplification DoS vector." Re-verified live: `PerformanceReportResource.java`'s
  `generateReport` (`:29-36`) carries no `@RateLimited` and `ReportGenerationService.generateReport`
  (`:91-92`) carries no rate-limit annotation either. Re-verified this project has an established,
  ready-to-reuse mechanism for exactly this: `@RateLimited` (`infrastructure/security/RateLimited.java`) is
  a declarative, IP-keyed, Bucket4j-backed annotation already applied at 6 call sites across
  `CoachRegistrationService`/`PlayerRegistrationService`/`ParentRegistrationService` (e.g.
  `@RateLimited(key = "coach_register", capacity = 3, duration = 60)`), enforced by a generic
  `RateLimitingAspect` with its own dedicated `RateLimitingAspectIT` covering the mechanism itself. Closing
  this gap is a one-line annotation addition, not new infrastructure.
- **D3 (this story's AC3)** — sourced from `## Deferred from: adversarial code review of skillars-7-2
  Group 1 DB+Entities (2026-06-24)`, item D6 (line 622): no `CHECK (stripe_customer_id LIKE 'cus_%')`
  format guard exists on `payment.stripe_customers`. Re-verified live: `V62__session_payment_credit_wallet.sql:30-37`
  declares `stripe_customer_id VARCHAR NOT NULL` with no `CHECK`, and no later migration (`V96` drops an
  unrelated column on a different table, `coach_subscriptions`) adds one. Re-verified this is safe to add
  now, not merely safe in theory: `StripePaymentGateway.createStripeCustomer` (`:166`) writes
  `stripeClient.createCustomer(params).getId()` — the raw Stripe SDK `Customer` object's own id, which the
  Stripe API always prefixes `cus_` for both live and test-mode keys, not an app-constructed string — and
  every test fixture across `SessionPackPaymentResourceIT`, `PackPriceLockedOnPurchaseTest`,
  `CaptureReservationIT`, `StripePaymentGatewayTest` and `SessionPackPaymentServiceTest` already uses a
  `"cus_..."`-prefixed value (grepped, zero exceptions). **Story-review Finding 1 (see "Review Findings"
  below) found that this table's actual current row count in any live environment was never independently
  verified, and the migration history citation originally used to argue size-based safety didn't actually
  support that claim.** Rather than assert a number nobody checked, AC3 now uses Postgres's standard
  low-risk pattern for adding a `CHECK` constraint to a populated table regardless of its actual size —
  `ADD CONSTRAINT ... NOT VALID` (a brief `ACCESS EXCLUSIVE` lock, no full-table scan) followed by a
  separate `VALIDATE CONSTRAINT` migration (a much lighter `SHARE UPDATE EXCLUSIVE` lock that doesn't block
  concurrent writes).

**Examined and deliberately not picked up, beyond the already-thin span's own excluded set:**
`skillars-3-4`'s "`verifyIsParty` has no admin bypass path — no admin role exists yet; revisit when admin
management stories are implemented" (line 808) is now stale in its literal premise — Epic 10 shipped an
admin role and admin management stories — but turning that into a concrete fix (should `ROLE_ADMIN` bypass
the booking-SSE party check? for which endpoints, under what audit trail?) is a product/security-posture
decision, not a mechanical fix, so it stays open rather than being picked up ad hoc. `skillars-6-5` W8
(`cascadeDeleteForAccount` quota reset non-atomic) explicitly needs "a future reconciliation job spec'd in
dev notes" — a new scheduled job is bigger than this story's bounded-fix bar. `skillars-10-2`'s `GET
/coaches/me/strikes` unbounded-list item (line 1125) is explicitly tagged "low risk" by its own text and
was left alone rather than padded into this story to hit a size target.

## Acceptance Criteria

1. **AC1 — GDPR erasure deletes a player's performance-report PDFs from S3, not just their DB rows.**
   - File: `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:185-196`
     (`deletePlayerDevelopmentData`).
   - Current shape:
     ```java
     private void deletePlayerDevelopmentData(Long playerId) {
         playerTimelineRepository.deleteByPlayerId(playerId);
         sluRepository.deleteAllByPlayerId(playerId);
         sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
         sluTargetRepository.deleteAllByPlayerId(playerId);
         neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
         playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
         playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
         radarAssessmentRepository.deleteAllByPlayerId(playerId);
         performanceReportRepository.deleteAllByPlayerId(playerId);
         homeworkCompletionRepository.deleteAllByPlayerId(playerId);
     }
     ```
   - Before the existing `performanceReportRepository.deleteAllByPlayerId(playerId)` bulk delete, fetch the
     player's reports via the repository's existing `findByPlayerIdOrderByGeneratedAtDesc(Long playerId)`
     method (`PerformanceReportRepository.java:9`) and delete each report's S3 object first, mirroring the
     exact log-and-swallow shape this same class already uses for GDPR export zips
     (`GdprErasureService.java:129-138`) so one bad S3 delete cannot abort the rest of erasure:
     ```java
     private void deletePlayerDevelopmentData(Long playerId) {
         playerTimelineRepository.deleteByPlayerId(playerId);
         sluRepository.deleteAllByPlayerId(playerId);
         sluWeeklySnapshotRepository.deleteAllByPlayerId(playerId);
         sluTargetRepository.deleteAllByPlayerId(playerId);
         neglectedSkillFlagRepository.deleteAllByPlayerId(playerId);
         playerRadarBaselineRepository.deleteAllByPlayerId(playerId);
         playerRadarCompositeRepository.deleteAllByPlayerId(playerId);
         radarAssessmentRepository.deleteAllByPlayerId(playerId);
         performanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc(playerId).forEach(report -> {
             try {
                 fileStorageService.deleteRawBytes(report.getStorageKey());
             } catch (Exception e) {
                 log.warn("[GDPR_ERASURE_S3_DELETE_WARN] Failed to delete performance report PDF: "
                     + "reportId={} playerId={}", report.getId(), playerId, e);
             }
         });
         performanceReportRepository.deleteAllByPlayerId(playerId);
         homeworkCompletionRepository.deleteAllByPlayerId(playerId);
     }
     ```
     `fileStorageService` is already a constructor-injected field on this class (`:68`, used at `:133`) —
     no new dependency. `PerformanceReport.getStorageKey()` already exists (Lombok `@Getter` on
     `PerformanceReport.java:35`). No new imports needed.
   - **Why fetch-then-delete instead of changing the repository's bulk delete to something S3-aware**: a
     JPA repository method cannot call an S3 client; the fetch must happen in the service layer that
     already holds `fileStorageService`. Keeping the existing `deleteAllByPlayerId` bulk delete for the DB
     half (rather than switching to per-entity `delete(...)` calls) avoids N extra DB round-trips for the
     common case (this story does not change how DB rows are deleted, only adds the missing S3 cleanup
     before it).
   - **Test coverage**: `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`.
     `fileStorageService` is already `@MockitoBean` in this class (`:51-52`), unused by any assertion
     today. Add one new test proving the S3 delete call actually fires, using the fixture id range already
     claimed by this file (`PLAYER_ID = 9210_000_003L`, `coachProfileId`, both from `setUp()`):
     ```java
     @Test
     void erase_playerUser_deletesPerformanceReportFromS3() {
         UUID reportId = UUID.randomUUID();
         String storageKey = "reports/" + reportId + "/report.pdf";
         transactionTemplate.execute(status -> {
             jdbcTemplate.update(
                 "INSERT INTO development.performance_reports "
                     + "(id, coach_id, player_id, generated_at, storage_key, next_steps) "
                     + "VALUES (?, ?, ?, ?, ?, 'Keep working on first touch')",
                 reportId, coachProfileId, PLAYER_ID, Timestamp.from(Instant.now()), storageKey);
             return null;
         });

         String cookies = loginAndGetCookies(PLAYER_EMAIL);
         httpTestClient.makeHttpRequest(
             baseUrl() + ERASURE_URL, HttpMethod.POST, null, authenticatedHeaders(cookies), Map.class);

         verify(fileStorageService).deleteRawBytes(storageKey);
         int count = jdbcTemplate.queryForObject(
             "SELECT COUNT(*) FROM development.performance_reports WHERE id = ?", Integer.class, reportId);
         assertThat(count).isZero();
     }
     ```
     Add `import static org.mockito.Mockito.verify;` to this file's existing import block (no other new
     imports needed — `UUID`, `Timestamp`, `Instant`, `jdbcTemplate`, `transactionTemplate` are all already
     imported/available). This test uses the `PLAYER_EMAIL` login (role `PLAYER`), so
     `deletePlayerDevelopmentData` is invoked directly with the player's own `userId` — no dependency on a
     `player_profiles` parent-link row existing. Run
     `mvn -o integration-test -Dit.test=GdprErasureIT` (Failsafe — `*IT` class) and confirm all tests
     remain green, including the new one.

2. **AC2 — Rate-limit `POST /api/development/players/{playerId}/reports`.**
   - File:
     `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java:91-92`.
   - Current shape:
     ```java
     @Transactional
     public void generateReport(Long coachUserId, Long playerId, String nextSteps) {
     ```
   - Add `@RateLimited`, mirroring the exact annotation shape and placement (service-layer method, above
     `@Transactional`... actually below is fine since Spring AOP ordering for these two orthogonal aspects
     is not order-sensitive here — no transaction state is read by the rate-limit check) already
     established at `CoachRegistrationService.registerCoach`/`resendVerificationEmail`:
     ```java
     @Transactional
     @RateLimited(key = "report_generate", capacity = 10, duration = 1, unit = TimeUnit.MINUTES)
     public void generateReport(Long coachUserId, Long playerId, String nextSteps) {
     ```
     Add two imports: `com.softropic.skillars.infrastructure.security.RateLimited` and
     `java.util.concurrent.TimeUnit`.
   - **Why 10 per minute, not the registration endpoints' `capacity = 3, duration = 60` (seconds)**: this
     is a judgment call, not spec-mandated — the source ledger item names no specific number. Registration
     endpoints are one-shot-per-account flows where 3/minute is already generous; report generation is a
     coach's routine per-player workflow during a review session (a coach reviewing 4-5 players in one
     sitting, generating one report each, should never be throttled). 10/minute is loose enough not to
     interfere with any plausible legitimate session while still bounding a tight-loop abuse pattern to
     roughly 1/6s sustained. Whoever revisits this number has the same latitude — it is not a contract any
     test or AC pins to an exact value.
   - **What this does NOT change**: no behavior on the happy path below the new limit; the existing
     `@Transactional` boundary, quota checks, and PDF/S3/email pipeline are all untouched.
   - **Test coverage**: none added, matching the established precedent for all 6 existing `@RateLimited`
     call sites — `RateLimitingAspectIT` (`src/test/java/com/softropic/skillars/infrastructure/security/RateLimitingAspectIT.java`)
     already proves the aspect mechanism itself (enforcement, per-IP bucketing, `AuthorizationException`
     with `TOO_MANY_REQUESTS`) generically against a throwaway test bean; no existing registration endpoint
     has its own endpoint-specific 429 test, and this AC does not introduce a new pattern. Run
     `mvn -o test -Dtest=ReportGenerationServiceTest` (if one exists — check first; if not, the existing
     `mvn -o integration-test -Dit.test=PerformanceReportResourceIT`-style coverage, if any exists, must
     stay green) to confirm no regression to the happy path from the added annotation.

3. **AC3 — Add a format-guard `CHECK` constraint on `payment.stripe_customers.stripe_customer_id`.**
   - New files: `src/main/resources/db/migration/V100__stripe_customer_id_format_guard.sql` and
     `V101__stripe_customer_id_format_guard_validate.sql` (V99 is the current highest migration). Split
     into two migrations, each its own Flyway transaction, per story-review Finding 1: folding both
     statements into one migration/transaction would hold V100's `ACCESS EXCLUSIVE` lock for the full
     duration of V101's validation scan, defeating the point of using `NOT VALID` at all.
     ```sql
     -- V100__stripe_customer_id_format_guard.sql
     -- Defence-in-depth format guard, mirroring this project's established convention of validating
     -- external-provider-sourced string columns at the DB boundary. Real Stripe API responses always
     -- prefix customer ids "cus_" (StripePaymentGateway.createStripeCustomer writes the Stripe SDK
     -- Customer object's own .getId() verbatim, never an app-constructed string), so this only catches a
     -- misconfigured/placeholder value written directly, not a live Stripe response shape.
     -- Added NOT VALID: registers the constraint for all new/future writes immediately via a brief
     -- ACCESS EXCLUSIVE lock, with no full-table scan of existing rows — this table's actual current row
     -- count in any live environment was never independently verified (story-review Finding 1), so the
     -- scan is deferred to V101's separate, lighter-locking migration instead of asserted safe here.
     ALTER TABLE payment.stripe_customers
         ADD CONSTRAINT chk_stripe_customer_id_format CHECK (stripe_customer_id LIKE 'cus_%') NOT VALID;
     ```
     ```sql
     -- V101__stripe_customer_id_format_guard_validate.sql
     -- Validates the NOT VALID constraint added in V100 against every existing row, under a SHARE UPDATE
     -- EXCLUSIVE lock that does not block concurrent reads/writes — deliberately a separate migration
     -- (separate transaction) from V100, so V100's brief ACCESS EXCLUSIVE lock is not held for this
     -- scan's duration.
     ALTER TABLE payment.stripe_customers
         VALIDATE CONSTRAINT chk_stripe_customer_id_format;
     ```
   - **Why this is safe to add now, not merely "safe in theory"**: verified every current write site
     (`StripePaymentGateway.createStripeCustomer:166`) and every test fixture across
     `SessionPackPaymentResourceIT`, `PackPriceLockedOnPurchaseTest`, `CaptureReservationIT`,
     `StripePaymentGatewayTest`, `SessionPackPaymentServiceTest` already uses a `"cus_..."`-prefixed value
     — this migration adds no new failure mode to any existing code path or test. The `NOT VALID`/
     `VALIDATE CONSTRAINT` split additionally makes this safe independent of the table's actual row count
     in any environment, sidestepping the question story-review Finding 1 raised rather than resting on an
     unverified size claim.
   - **Why an unescaped `LIKE 'cus_%'` (not a stricter regex or an escaped literal underscore)**: matches
     the source ledger item's own exact suggested text verbatim; this project has no existing `LIKE`
     pattern anywhere in its migrations to establish an escaping convention, and an unescaped `_` (SQL's
     single-char wildcard) is strictly *more* permissive than intended, never less — it cannot reject a
     genuine `cus_...` value, only fail to reject an unlikely `cusX...` value. Defence-in-depth, not a
     strict format validator.
   - **Test coverage**: new file `src/test/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepositoryIT.java`,
     extending `AbstractIntegrationTest` (matching `SessionPackPurchaseRepositoryIT`'s existing pattern one
     package over), claiming fixture ids `9640000001`–`9640000002` per
     `docs/testing/test-data-isolation.md`'s free-block registry (update that doc's table and claimed-prefix
     list in the same commit, adding `9640`):
     ```java
     package com.softropic.skillars.platform.payment.repo;

     import com.softropic.skillars.config.AbstractIntegrationTest;
     import org.junit.jupiter.api.Test;
     import org.springframework.beans.factory.annotation.Autowired;
     import org.springframework.dao.DataIntegrityViolationException;

     import java.time.Instant;

     import static org.assertj.core.api.Assertions.assertThatCode;
     import static org.assertj.core.api.Assertions.assertThatThrownBy;

     class StripeCustomerRepositoryIT extends AbstractIntegrationTest {

         @Autowired private StripeCustomerRepository stripeCustomerRepository;

         @Test
         void validCusPrefixedId_saves() {
             StripeCustomer sc = new StripeCustomer();
             sc.setParentId(9_640_000_001L);
             sc.setStripeCustomerId("cus_valid_test_id");
             sc.setCreatedAt(Instant.now());

             assertThatCode(() -> stripeCustomerRepository.saveAndFlush(sc)).doesNotThrowAnyException();

             stripeCustomerRepository.deleteById(9_640_000_001L);
         }

         @Test
         void nonCusPrefixedId_throwsDataIntegrity() {
             StripeCustomer sc = new StripeCustomer();
             sc.setParentId(9_640_000_002L);
             sc.setStripeCustomerId("acct_wrong_prefix");
             sc.setCreatedAt(Instant.now());

             assertThatThrownBy(() -> stripeCustomerRepository.saveAndFlush(sc))
                 .isInstanceOf(DataIntegrityViolationException.class);
         }
     }
     ```
     Run `mvn -o integration-test -Dit.test=StripeCustomerRepositoryIT` (Failsafe — new `*IT` class) and
     confirm both tests green. Also run
     `mvn -o integration-test -Dit.test=SessionPackPaymentResourceIT,CaptureReservationIT` (both seed
     `StripeCustomer` rows via fixtures already confirmed `cus_`-prefixed) to confirm the new constraint
     causes no regression.

4. **AC4 — Ledger hygiene.** This project's established convention (confirmed against the "Create Story"
   commits for `deferred-49` through `-56`) is: at **story-creation** time, tag an item this story is about
   to fix as `` `[PICKED UP by skillars-deferred-57 ACn]` ``, appended after the item's existing
   text/citation. `` `[CLOSED by ...]` `` is reserved for items **verified already fixed by separate,
   completed work**. Only flip a `PICKED UP` tag to `CLOSED` in the **implementation** commit, once the
   corresponding code change actually lands — never at story-creation time. This was already applied
   correctly at this story's creation:
   - `deferred-work.md` line 656 (`skillars-5-5` D4, the report-generation rate-limit item) tagged
     `` `[PICKED UP by skillars-deferred-57 AC2]` ``.
   - `deferred-work.md` line 657 (`skillars-5-5` D5, the performance-report GDPR/S3 item) tagged
     `` `[PICKED UP by skillars-deferred-57 AC1 — re-scoped to the S3-orphan half specifically ...]` ``,
     with an inline note explaining the DB-row half is already closed by unannotated earlier work.
   - `deferred-work.md` line 622 (`skillars-7-2` Group 1 D6, the `stripe_customer_id` format-guard item)
     tagged `` `[PICKED UP by skillars-deferred-57 AC3]` ``.
   This AC's job during **implementation** is to flip all three `PICKED UP` tags to `CLOSED` once AC1/AC2/AC3
   actually land — one commit, matching the code:
   - Once AC1 ships: flip line 657's tag to `` `[CLOSED by skillars-deferred-57 AC1]` `` with a one-line
     closure note.
   - Once AC2 ships: flip line 656's tag to `` `[CLOSED by skillars-deferred-57 AC2]` `` the same way.
   - Once AC3 ships: flip line 622's tag to `` `[CLOSED by skillars-deferred-57 AC3]` `` the same way.
   - **If a partial implementation lands**, flip only the tags for the ACs that actually shipped — leave
     the others at `PICKED UP`. The ledger must never claim a still-unfixed item is `CLOSED`.

## Tasks / Subtasks

- [ ] Task 1: GDPR erasure S3 cleanup for performance reports (AC: #1)
  - [ ] 1.1 Add the fetch-then-delete-then-bulk-delete shape to
    `GdprErasureService.deletePlayerDevelopmentData`, per AC1's snippet.
  - [ ] 1.2 Add `erase_playerUser_deletesPerformanceReportFromS3` to `GdprErasureIT.java`, per AC1's
    snippet, including the new `Mockito.verify` static import.
  - [ ] 1.3 Run `mvn -o integration-test -Dit.test=GdprErasureIT` and confirm all tests green (existing
    plus the new one).
- [ ] Task 2: Rate-limit report generation (AC: #2)
  - [ ] 2.1 Add `@RateLimited(key = "report_generate", capacity = 10, duration = 1, unit = TimeUnit.MINUTES)`
    to `ReportGenerationService.generateReport`, with the two new imports.
  - [ ] 2.2 Confirm no existing report-generation test regresses (targeted run per AC2's Test coverage
    note — check for an existing `ReportGenerationServiceTest`/`PerformanceReportResourceIT` first).
- [ ] Task 3: `stripe_customer_id` format guard (AC: #3)
  - [ ] 3.1 Add `V100__stripe_customer_id_format_guard.sql` (`NOT VALID`) and
    `V101__stripe_customer_id_format_guard_validate.sql` (`VALIDATE CONSTRAINT`) as two separate
    migrations, per AC3's snippet and story-review Finding 1.
  - [ ] 3.2 Add `StripeCustomerRepositoryIT.java` (new file), per AC3's snippet.
  - [ ] 3.3 Update `docs/testing/test-data-isolation.md`'s fixture registry table and claimed-prefix list
    to add `9640000001`–`9640000002` / `StripeCustomerRepositoryIT`.
  - [ ] 3.4 Run `mvn -o integration-test -Dit.test=StripeCustomerRepositoryIT` and confirm both tests
    green; run `mvn -o integration-test -Dit.test=SessionPackPaymentResourceIT,CaptureReservationIT` and
    confirm no regression.
- [ ] Task 4: Ledger hygiene (AC: #4) — flip the three `PICKED UP` tags applied at story creation to
  `CLOSED` once AC1/AC2/AC3 land, per AC4.

### Review Findings

Pre-implementation story review (`_bmad-output/implementation-artifacts/story-review.md`) of this story's
draft against live code, 2026-08-22. Every AC1/AC2/AC3 code citation, method signature, line number, table
schema, and test-fixture claim was independently re-verified and matched live code exactly, except one.

- [x] [Review][Fix] **Applied:** AC3's original text justified an unconditional `ALTER TABLE ... ADD
  CONSTRAINT` (an `ACCESS EXCLUSIVE`-locking, full-table-scanning operation) by citing `V94`/`V99`'s
  migration comments as documenting an "established tolerance" for this at current table size. Both
  citations were independently checked and neither actually says that: `V94`'s comment only explains why
  reusing a constraint name is safe and why a value fits a column's `VARCHAR` width; `V99` isn't even an
  `ALTER TABLE` (it's a single-row `INSERT`) and its comment is about a hand-assigned-PK collision hazard.
  A full search of every migration file for any discussion of `ACCESS EXCLUSIVE` locking, table size, or
  row count found none — the "established tolerance" didn't exist in this codebase, and
  `payment.stripe_customers`'s actual current row count in any live environment was never independently
  verified. Fixed by adopting the reviewer's recommendation (b): split AC3's migration into
  `V100__stripe_customer_id_format_guard.sql` (`ADD CONSTRAINT ... NOT VALID`, a brief `ACCESS EXCLUSIVE`
  lock with no table scan) and `V101__stripe_customer_id_format_guard_validate.sql` (`VALIDATE CONSTRAINT`,
  a lighter `SHARE UPDATE EXCLUSIVE` lock that doesn't block concurrent writes), as two separate Flyway
  migrations/transactions so `V100`'s lock is not held for `V101`'s scan duration. This makes AC3 safe
  regardless of the table's actual size in any environment, sidestepping the unverified claim rather than
  requiring a production row-count check before merging (recommendation (a), not pursued — no production DB
  access available at story-review time). AC1 and AC2 had zero findings; see "Everything else independently
  re-verified as accurate" in `story-review.md` for the full list of what was checked, including several
  things this story didn't even originally claim to verify.

## Dev Notes

- **This story bundles three independent, decision-light findings from three different modules — it is
  not a single coherent feature.** AC1 touches `GdprErasureService` (admin/GDPR module) plus one test
  file. AC2 touches `ReportGenerationService` (development module), a one-line annotation addition. AC3
  is a new migration plus a new repository IT (payment module). None of the three ACs' code changes
  overlap or depend on each other; they can be implemented and reviewed in any order.
- **AC1's test class runs under Failsafe** (`mvn -o integration-test -Dit.test=GdprErasureIT`, bound to
  `integration-test`/`verify`, **not** `mvn -o test`) — this gotcha has tripped up prior stories in this
  same ledger and is worth restating every time. **AC3's new `StripeCustomerRepositoryIT` is also an
  `*IT` class**, same command family, different class name.
- **AC1's erasure runs synchronously, not async**: `GdprErasureIT`'s own existing tests (e.g.
  `erase_deactivatesUser_oldSessionRejected`, whose comment states this explicitly) confirm `erase(...)`
  completes before the `202 Accepted` response returns to the caller — so the new AC1 test can assert DB
  and mock state immediately after the HTTP call returns, no polling/`Awaitility` needed.
- **AC2's rate-limit capacity (10/minute) is a judgment call, not a spec-mandated number** — see AC2's own
  "Why 10 per minute" explanation. Do not treat it as load-bearing for any test; no test in this story
  pins the exact threshold.
- **AC3's migration only guards new/future writes** — like `skillars-deferred-18`'s AC4 precedent (IANA
  timezone validation), no audit of existing `stripe_customers` rows is run. Not needed here: every
  current row was written by the one code path already confirmed `cus_`-prefixed.
- **AC3 ships as two migration files, not one** (`V100` = `ADD CONSTRAINT ... NOT VALID`, `V101` =
  `VALIDATE CONSTRAINT`), per story-review Finding 1 — this project's migration history doesn't actually
  document a verified-safe row count for `stripe_customers`, so the split makes the constraint's addition
  safe regardless of the table's actual size in any environment, instead of resting on an unverified size
  claim. Do not merge these back into a single `ALTER TABLE` statement or a single migration file/transaction
  — doing so would hold `V100`'s `ACCESS EXCLUSIVE` lock for `V101`'s full validation-scan duration.
- **No frontend changes in this story.** All three ACs are backend-only (one service method each, one new
  migration, two new/extended test files).
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify`
  unless targeted verification proves insufficient.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java` —
  `deletePlayerDevelopmentData` gains a fetch-then-delete S3 cleanup step before the existing bulk DB
  delete (AC1). No new imports, no new constructor dependency (`fileStorageService` already injected).
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java` — one new test method, one
  new static import (AC1).
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java` —
  `generateReport` gains one annotation, two new imports (AC2).
- `src/main/resources/db/migration/V100__stripe_customer_id_format_guard.sql` and
  `V101__stripe_customer_id_format_guard_validate.sql` — two new files, `NOT VALID` add + separate
  `VALIDATE CONSTRAINT`, per story-review Finding 1 (AC3).
- `src/test/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepositoryIT.java` — new file
  (AC3).
- `docs/testing/test-data-isolation.md` — fixture registry updated with the new `9640` block (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — three `PICKED UP`→`CLOSED` tag flips (AC4).
- No frontend files, no changes to any `*Resource`/`*Controller` class signature in this story (AC2 adds
  an annotation to the service method the resource already calls, not to the resource method itself).

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 656, section `## Deferred from:
  code review of skillars-5-5-pdf-performance-report-unified-player-timeline (2026-06-19)` — this story's
  AC2 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 657, same section — this story's
  AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 622, section `## Deferred from:
  adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)` — this story's AC3 source]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:129-138,185-196`
  — the existing export-zip S3-delete pattern this AC1 mirrors, and `deletePlayerDevelopmentData`, AC1's
  target]
- [Source: `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReportRepository.java`
  — `findByPlayerIdOrderByGeneratedAtDesc`, `deleteAllByPlayerId`]
- [Source: `src/main/java/com/softropic/skillars/platform/development/repo/PerformanceReport.java:35` —
  `storageKey` field]
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java:91-92`
  — `generateReport`, AC2's target]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/security/RateLimited.java`,
  `RateLimitingAspect.java`, `RateLimitingService.java` — the existing declarative rate-limit mechanism
  AC2 reuses]
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java:70`
  — an existing `@RateLimited` usage AC2's annotation shape mirrors]
- [Source: `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql:30-37` — `stripe_customers`
  table definition, AC3's target]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/StripePaymentGateway.java:166`
  — `createStripeCustomer`, confirming every write of `stripe_customer_id` is a raw Stripe SDK id]
- [Source: `src/test/java/com/softropic/skillars/platform/video/repo/VideoRepositoryIT.java:21-27` — the
  `DataIntegrityViolationException` repository-IT pattern AC3's new test mirrors]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java`
  — the `AbstractIntegrationTest`-based payment-repository IT pattern AC3's new test file mirrors]
- [Source: `docs/testing/test-data-isolation.md` — fixture id registry, free-block list AC3 claims from]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]
- [Source: `_bmad-output/implementation-artifacts/story-review.md` — pre-implementation story review;
  Finding 1, resolved, drove AC3's `NOT VALID`/`VALIDATE CONSTRAINT` migration split]

## Dev Agent Record

### Agent Model Used

_To be filled in by the dev agent._

### Debug Log References

_To be filled in by the dev agent._

### Completion Notes List

_To be filled in by the dev agent._

### File List

_To be filled in by the dev agent._

## Change Log

| Date | Change |
|---|---|
| 2026-08-22 | Story created via story-creation process, bundling three items re-mined from `deferred-work.md`'s middle span (2026-06-19 through 2026-06-30 — Stories 5.1-5.6, 6.1-6.6, 7.1-7.5, 8.1-8.4, 10.1-10.4, 11.1-11.3), the one large section this ledger's own audits have repeatedly flagged as "not re-checked" by any prior `skillars-deferred-*` story-creation pass. AC1 closes the S3-orphan half of `skillars-5-5` D5 (the DB-row half was already closed by earlier unannotated work) — GDPR erasure was silently leaving every player's performance-report PDFs in S3 forever. AC2 closes `skillars-5-5` D4 — rate-limits report generation using this project's existing `@RateLimited` mechanism, already proven at 6 other call sites. AC3 closes `skillars-7-2` Group 1 D6 — adds a format-guard `CHECK` constraint on `stripe_customer_id`, verified safe against every current write site and test fixture. AC4 is ledger hygiene for all three. Considered and explicitly not picked up: `skillars-3-4`'s stale "no admin role exists yet" admin-bypass item (now reachable in principle since Epic 10 shipped admin roles, but the actual fix needs a security-posture decision); `skillars-6-5` W8 (needs a new reconciliation job, bigger than a bounded fix); `skillars-10-2`'s unbounded strikes list (explicitly tagged low-risk by its own text). |
| 2026-08-22 | story-review adjustments applied, status remains ready-for-dev. `story-review.md` filed 1 finding against the draft (Medium), fixed. Finding 1: AC3's original safety argument for an unconditional `ALTER TABLE ... ADD CONSTRAINT` cited `V94`/`V99` as documenting an "established tolerance" for `ACCESS EXCLUSIVE` locking at this table's size — both citations independently re-checked and neither actually supports that claim, and no migration in this project's history discusses lock duration or row-count tolerance at all; the table's actual current row count in any live environment was never independently verified. Per the finding's recommendation (b), fixed by splitting AC3's migration into two files/transactions — `V100` (`ADD CONSTRAINT ... NOT VALID`, brief `ACCESS EXCLUSIVE` lock, no scan) and `V101` (`VALIDATE CONSTRAINT`, lighter `SHARE UPDATE EXCLUSIVE` lock) — making the constraint's addition safe regardless of actual table size, rather than pursuing recommendation (a) (verifying a live row count, not possible at story-review time with no production DB access). AC1 and AC2, and every other AC3 claim, were independently re-verified as accurate — no changes needed there. |
