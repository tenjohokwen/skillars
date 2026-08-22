# Story Review: Deferred-57 — GDPR Report-File Cleanup, Report-Generation Rate Limiting & Stripe Customer ID Format Guard

Senior-dev audit of `skillars-deferred-57-gdpr-report-file-cleanup-report-generation-rate-limiting-and-stripe-customer-id-format-guard.md`
(status `ready-for-dev`) against live code, before implementation. Every claim below was independently
re-verified against the actual production/test files and `deferred-work.md`/`docs/` citations — nothing here
is taken on the story's word alone.

This story is unusually well-verified: every AC1/AC2/AC3 code citation, method signature, line number, table
schema, and test-fixture claim I checked matched live code exactly (see the "Everything else verified accurate"
section for the full list of what was independently confirmed, including several things the story didn't even
claim to check). One substantive finding survived that level of scrutiny.

---

## Finding 1 (Medium) — AC3's "safe to add now" argument cites two migrations that don't actually document what it claims they document; the real safety question (current row count / lock duration on a live production table) is never actually answered

**What's wrong:** AC3's own text justifies adding an unconditional `ALTER TABLE payment.stripe_customers ADD
CONSTRAINT ... CHECK (...)` (which takes a Postgres `ACCESS EXCLUSIVE` lock and validates every existing row
before committing) with: *"The table has one row per parent/coach with a Stripe customer, well within this
project's own established tolerance for an `ACCESS EXCLUSIVE`-locking `ALTER TABLE ... ADD CONSTRAINT` at
current size (the same tolerance `V94`/`V99`'s own migration comments already document for sibling payment
tables)."*

Checked both cited files directly:
- `V94__booking_payment_capture_pending.sql` does perform a comparable `ALTER TABLE ... DROP CONSTRAINT
  chk_bp_status, ADD CONSTRAINT chk_bp_status CHECK (...)` — but its actual comment only explains why the
  constraint *name* is safe to reuse and why `'CAPTURE_PENDING'` fits the column's `VARCHAR(16)` width. It says
  nothing about table size, row count, or lock-duration tolerance.
- `V99__payment_currency_config.sql` isn't even the same *kind* of operation — it's a single-row `INSERT INTO
  main.platform_config`, not an `ALTER TABLE` on a live data table at all, and takes nothing like an `ACCESS
  EXCLUSIVE` lock. Its comment is entirely about a hand-assigned-PK id-collision hazard, unrelated to locking
  or table size.

A broader search of every migration file in `src/main/resources/db/migration/` for any actual discussion of
`ACCESS EXCLUSIVE` locking, table size, row count, or lock-duration tolerance (multiple phrasings tried) found
**zero files** containing such a discussion — not V94, not V99, not anywhere else in the migration history.
The "established tolerance" this citation claims to point to does not exist in this codebase.

**Why it matters:** this migration will run against whatever real `payment.stripe_customers` table exists in
every environment the migration deploys to — not just the local/CI test database, where the row count is
trivially small by construction. The story's actual safety argument for *existing data* rests entirely on
"one row per parent/coach... well within established tolerance," backed by a citation that, on inspection,
doesn't support that claim. Unlike AC1 and AC2 (where every load-bearing claim I checked held up exactly), this
is the one place in the story where a safety argument for a production-affecting operation is asserted rather
than actually demonstrated. This doesn't mean the migration *is* unsafe — plausibly the table really is small
today, matching the story's description — but the story doesn't currently establish that with anything more
solid than an inaccurate citation, for the one AC in this story that touches a live, unconditional schema lock
on a payment table.

**Recommendation:** either (a) verify the actual current row count of `payment.stripe_customers` in whatever
environment(s) this migration will run against before merging, and cite that number directly instead of the
V94/V99 citation, or (b) sidestep the question entirely using Postgres's standard low-risk pattern for adding a
constraint to a populated table: `ADD CONSTRAINT ... CHECK (...) NOT VALID`, followed by a separate `VALIDATE
CONSTRAINT` statement — `NOT VALID` takes only a brief `ACCESS EXCLUSIVE` lock to register the constraint
(applies to all new/future writes immediately) without an immediate full-table scan, and `VALIDATE CONSTRAINT`
can then run with a much lighter `SHARE UPDATE EXCLUSIVE` lock that doesn't block concurrent writes. Either
fix is small; the constraint's `CHECK (stripe_customer_id LIKE 'cus_%')` expression itself doesn't need to
change.

---

## Everything else independently re-verified as accurate, no changes needed

**AC1 (GDPR erasure S3 cleanup):**
- `GdprErasureService.erase(...)` is `@Transactional(propagation = REQUIRES_NEW)`; `deletePlayerDevelopmentData`
  is a private method called from within it — confirmed at the exact cited lines (export-zip S3-delete pattern
  at `:129-138`, `deletePlayerDevelopmentData` at `:185`).
- `PerformanceReportRepository.findByPlayerIdOrderByGeneratedAtDesc` and `.deleteAllByPlayerId` both exist
  exactly as described; `PerformanceReport.getStorageKey()` exists via the class-level Lombok `@Getter`.
- `FileStorageService.deleteRawBytes(storageKey)` is confirmed to be the *exact* method and key format already
  used elsewhere in `ReportGenerationService` itself (its own orphan-PDF-cleanup path on a failed DB insert,
  `ReportGenerationService.java:144-148`, uses the identical `"reports/" + UUID + "/report.pdf"` key shape) —
  this independently confirms AC1's proposed call uses the right API with the right key format, beyond what the
  story itself cited.
- The erasure-runs-synchronously claim was independently re-traced end to end (not just taken from the cited
  test's comment): `GdprResource.requestErasure` → `GdprRequestService.requestErasure` (`@Transactional`,
  publishes `GdprErasureRequestedEvent`) → `GdprEventListener.onErasureRequested`
  (`@TransactionalEventListener(AFTER_COMMIT)`, **no** `@Async`) → `GdprErasureService.erase(...)`. Since the
  listener isn't `@Async`, Spring invokes it synchronously on the same request thread immediately after commit,
  before the controller method returns — the 202 response genuinely cannot be sent until `erase()` (and thus
  the new S3-delete loop) has run. The cited test's comment (`erase_deactivatesUser_oldSessionRejected`,
  "Erasure runs synchronously via AFTER_COMMIT listener") matches this independently-traced mechanism exactly.
- `GdprErasureIT`'s fixture claims (`@MockitoBean FileStorageService`, `PLAYER_ID = 9210_000_003L`,
  `coachProfileId`, `PLAYER_EMAIL`, `ERASURE_URL`) all confirmed present exactly as cited. The new test's raw
  `INSERT INTO development.performance_reports` statement supplies every `NOT NULL` column except `version`,
  which has a DB-level `DEFAULT 1` (`V52__pdf_report_timeline.sql:11`) — no insert failure risk. The table has
  no `player_id`/`coach_id` foreign keys, so the test's fixture values need no pre-existing parent rows beyond
  what `setUp()` already creates.

**AC2 (rate limiting):**
- `@RateLimited`'s actual field shape (`key()`, `capacity() default 5`, `duration() default 1`, `unit() default
  MINUTES`) matches the story's proposed usage exactly; `generateReport` is confirmed at line 92 (matching the
  cited "91-92"), with exactly one call site (`PerformanceReportResource.java:35`, an external bean call
  through the Spring proxy — no self-invocation risk that would bypass the AOP aspect).
- Checked whether `@Transactional` + `@RateLimited` on the same method is actually already-proven, not just
  plausible: all three registration classes the story cites apply `@Transactional` at the **class** level, so
  every one of their `@RateLimited` methods (`registerCoach`, `resendVerificationEmail`, etc.) already carries
  both annotations simultaneously today — this exact combination is already live in production, not a novel
  pairing this story would be first to test.
- Checked whether IP-keyed rate limiting (the only keying `RateLimitingAspect.getClientIdentifier()` supports)
  has ever been applied to an *authenticated* endpoint before, since report-generation is authenticated (unlike
  the registration flows, which are necessarily pre-account/IP-only): confirmed yes —
  `AccountManagementFacade.changeEmail`'s `@RateLimited(key = "change_email", ...)` sits behind
  `ProfileResource`'s class-level `@PreAuthorize(HAS_ANY_ROLE)`, so IP-keyed limiting on an authenticated,
  per-user action is already an established, shipped pattern, not something novel AC2 would be first to try.
- `ReportGenerationServiceTest` exists (no `PerformanceReportResourceIT`), confirmed a pure
  `@ExtendWith(MockitoExtension.class)` unit test with no Spring context — adding a annotation with zero
  compile-time behavior has no way to regress it.
- (Minor, non-blocking: the story says `@RateLimited` is "already applied at 6 call sites across
  CoachRegistrationService/PlayerRegistrationService/ParentRegistrationService" — those three files actually
  total 7 call sites (2+2+3), and the mechanism is used at 15 call sites platform-wide once
  `AccountManagementFacade`/`SmsRegistrationStrategy`/`EmailRegistrationStrategy` are included. Doesn't affect
  AC2's correctness — the broader count only reinforces that the mechanism is even more established than the
  story states.)

**AC3 (format guard), beyond the locking-safety citation above:**
- `stripe_customers`' actual table definition (`V62__session_payment_credit_wallet.sql:30-37`) matches exactly:
  `parent_id BIGINT PRIMARY KEY`, `stripe_customer_id VARCHAR NOT NULL`, no existing `CHECK`, and — checked —
  no later migration ever touches this table, and it has **no foreign key** on `parent_id`, so the new IT
  test's fixture insert (`parentId = 9_640_000_001L`, no real parent row) cannot hit an FK violation.
- The story names one write-site citation (`StripePaymentGateway.createStripeCustomer:166`, the Stripe API call
  that produces the id) but the actual DB-persisting call sites are three, across two files
  (`SessionPackPaymentResource.java:151,171`, `SessionPackPaymentService.java:213`) — traced all three
  independently and confirmed every one sources its value from `paymentGateway.createStripeCustomer(...)`,
  which only ever returns a real Stripe SDK id or throws (no placeholder/fallback path exists in production).
  Also checked two test files the story doesn't cite at all — `StubPaymentGateway` (used by other ITs in place
  of the real gateway) returns `"cus_stub_" + parentId`, and `CashOutServiceTest` is a pure Mockito unit test
  with no real DB access — both confirmed to pose no regression risk to the new constraint either.
- `docs/testing/test-data-isolation.md`'s `9640` block is confirmed genuinely free (present only in the "Free
  blocks" list, not in the "claimed four-digit prefixes" list). `V100` is confirmed the next free migration
  number (`V99` is the current highest).
- `StripeCustomer`'s entity fields (`parentId`, `stripeCustomerId`, `createdAt` with a `@PrePersist` default)
  and `StripeCustomerRepository extends JpaRepository<StripeCustomer, Long>` match the new test snippet
  exactly; `saveAndFlush`/`deleteById` are both inherited, no custom repository methods needed.
- All three `[PICKED UP by skillars-deferred-57 ACn]` ledger tags (lines 622, 656, 657) confirmed present with
  wording matching the story's own description exactly, including AC1's re-scoping note (DB-row half already
  closed by earlier unannotated work, only the S3-orphan half is new).
