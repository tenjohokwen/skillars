# Story skillars-deferred-176: Payment residuals (Stripe email serialisation, card writes on erased rows, unreconciled no-charge, upgrade retry, file ownership)

Status: in-progress

<!-- CREATED 2026-11-07 from deferred-work.md (RS section), owner-chosen bundle "payment residuals": RS03d, RS03e,
     RS03f, RS06i, RS07c, RS09g, RS09h. Code read at origin/master 0df391d5 (skillars-deferred-175 merged, PR #489);
     all seven items re-verified OPEN at that HEAD. Citations are line numbers at 0df391d5; re-verify before editing.
     Decisions D9-D14 continue 174/175's numbering; owner decided option 1 in each (2026-11-07). -->

## Story

As **a payer or subscriber**, I want Stripe to hold my current email and no card to be written back onto an account
I erased, so that receipts reach me and an erasure is final.

As **an operator**, I want an attempt Stripe shows no charge for to be closable from the admin action, and a retried
upgrade not to be billed twice.

As **a coach or player**, I want to delete files I uploaded before I changed my email address.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | AC |
|---|---|---|---|
| RS03f | low / concurrency | Confirm/undo email syncs race each other; a sync races a GDPR erasure's scrub | AC1 |
| RS03e | low / edge-case | An email change during the first Stripe customer creation is never synced | AC2 |
| RS09h | low / concurrency | `reapplyPaymentMethod` writes a card onto a row an erasure just NULLed | AC3 |
| RS09g | low / logic | `getOrCreateStripeCustomer` saves the card with a full-row load-and-save | AC3 |
| RS06i | low / logic | UNRECONCILED attempt with no charge: card reverted anyway, close action refuses `writeLost` | AC4 |
| RS07c | low / edge-case | A retried upgrade re-sends the price with `always_invoice`; duplicate reconciliation rows | AC5 |
| RS03d | low / logic | `FileStorageService.softDelete` compares `created_by` (the login), broken by an email change | AC6 |

Every line above is deleted from `deferred-work.md` in this PR (AC8). Residuals the decisions accept are filed as new
lines, not left in the old ones.

## Background (code at 0df391d5)

Paths are relative to `src/main/java/com/softropic/skillars/platform/`.

### The outbox and the two Stripe-customer handlers (RS03f)

- Claim: `outbox/repo/OutboxMessageRepository` (JPQL, `PESSIMISTIC_WRITE`, `lock.timeout = -2` = SKIP LOCKED, page 1,
  `ORDER BY attempts, id`). `outbox/service/OutboxRowProcessor.claimAndHandle` (`@Transactional(REQUIRES_NEW)`, :101)
  claims, calls `handler.handle(payload)` (:116) and deletes the row (:117) in ONE transaction: the handler's own
  queries run on that transaction's connection, and the row lock is held while the handler calls Stripe.
- Drains run in parallel: an `@Async("outboxDrainPool")` AFTER_COMMIT drain on every pod plus the ShedLocked sweep
  (`OutboxService.java:108-130`). `main.outbox_messages` has no aggregate key column (V138 :688-696), so two rows for
  one user can run at the same time on different threads/pods.
- `payment/service/StripeCustomerEmailSyncOutboxHandler.handle` (:57-91): parse `{userId}` → `userRepository.findOneById`
  → skip gone/blank/`@erased.io` → `stripeCustomerRepository.findById(userId)` → `stripeClient.updateCustomerEmail`.
- `payment/service/StripeCustomerErasureOutboxHandler.handle` (:46-81): payload `{stripeCustomerId}` only (no userId) →
  retrieve → detach payment methods → `deleteCustomer`; a non-`resource_missing` `InvalidRequestException` falls back to
  `scrubCustomer`.
- `admin/service/GdprErasureService.eraseTransactional` (`REQUIRES_NEW`, :411) sets the tombstone
  (`deleted.<id>@erased.io`, `locked=true`, :424-435) and calls `StripeCustomerErasureOutboxSupport.scheduleErasure`
  (:815) in the same transaction, so the erasure row becomes claimable only after the tombstone commits.
- Advisory-lock precedent: two-key `pg_advisory_xact_lock(<NAMESPACE>, hashtext(:key))` with a 4-char hex namespace
  constant on the repository, e.g. `CoachCancellationHistoryRepository:39-44` (`0x4343584C` "CCXL"); others
  `0x46534F51` (FSOQ), `0x4453504C` (DSPL). `MailManager.java:107` records that `hashtext(NULL)` returns a null row
  WITHOUT locking — the key must be non-null.

### First Stripe customer creation (RS03e)

- `StripePaymentGateway.createStripeCustomer` (:676-690, no transaction) reads the user's email and calls
  `stripeClient.createCustomer` (the ledger's `:647` has drifted). Its only callers insert the `stripe_customers` row
  afterwards, in their own repository transaction: `SessionPackPaymentService.getOrCreateStripeCustomer` (:995-1001) and
  `SessionPackPaymentResource.createSetupIntent` (:179-187, catches `DataIntegrityViolationException`).
- `StripeCustomerEmailSyncOutboxSupport.onAccountEmailChanged` (:33-50, `@EventListener`, `MANDATORY`) enqueues only if
  `stripeCustomerRepository.existsById(userId)` — false while the new row is uncommitted, so the change is missed.

### Card writes on `stripe_customers` (RS09g, RS09h)

- Row columns: `parent_id` PK, `stripe_customer_id NOT NULL`, `stripe_payment_method_id`, `last_payment_intent_id`,
  `created_at` (V138 :1920-1927). No `@Version`, no `@DynamicUpdate`: `save` writes every column.
- RS09g: `SessionPackPaymentService.getOrCreateStripeCustomer` (:988-1003, reached from non-transactional `purchasePack`
  :210) does `findById` → `setStripePaymentMethodId` → `save` (:992).
- RS09h: `StripeCustomerRepository.reapplyPaymentMethod` (native) matches
  `stripe_payment_method_id = :attempted OR stripe_payment_method_id IS NOT DISTINCT FROM CAST(:previous AS varchar)`.
  Sole caller `SubscriptionService.reapplyPaymentMethodAndScheduleSync` (:787-794, `@Transactional`, also enqueues the
  subscription card sync), reached from `SessionPackPaymentService.scheduleSubscriptionCardSync` (:737) on the request
  path (:291). A payer with no saved card (`previous` null) whose purchase succeeds while an erasure NULLs the column
  gets the card written back and a card sync enqueued for the erased user.
- Erasure: `StripeCustomerErasureOutboxSupport.scheduleErasure` (:41-58) keeps the row and NULLs only the card column
  (load-modify-save, :46). Nothing on the row marks it erased; the user's tombstone email is the only specific marker
  (`locked` is also set by suspensions).
- Why a plain WHERE subquery on `main."user"` is not enough: an UPDATE that blocks on the erasure's row lock re-checks its
  WHERE against the new row version only (EvalPlanQual); a subquery on another table is not reliably re-read. Taking the
  row lock first (`StripeCustomerRepository.findByIdForUpdateWaiting`, :49-51) and THEN reading the user in a new
  statement does see the committed tombstone under READ COMMITTED.
- 174 AC2 precedent for targeted writes: `updateLastPaymentIntentId` (JPQL `UPDATE ... WHERE c.parentId = :parentId`,
  `@Modifying(flushAutomatically, clearAutomatically) @Transactional`), used at `StripePaymentGateway.java:261`.
- Other `stripeCustomerRepository.save(` sites (not in scope unless noted): `SessionPackPaymentResource:184` (insert),
  `SubscriptionService:778` (`savePaymentMethodAndScheduleSync`, load-modify-save), `StripeCustomerErasureOutboxSupport:46`.

### UNRECONCILED attempt with no charge (RS06i)

- UNRECONCILED is written at `SessionPackPaymentService.java:264`, `:279` after `recordReconciliation(...)` (so a
  `refund_failures` row exists). `Status.OPEN = {IN_PROGRESS, UNKNOWN, UNRECONCILED}` (`SessionPackPurchaseAttempt:39`).
- `resolveAttempt` (:405) reaches `noCharge(a)` with no status filter at :429-431, :441-444 and :451-453 (the last needs
  no age). `markNoCharge` (:543-550) calls `revertDeclinedCard(a)` unconditionally, then CASes to NO_CHARGE with
  `expected = {IN_PROGRESS, UNKNOWN}`; for UNRECONCILED the CAS returns 0 and `reread` yields `OpenReason.WRITE_LOST`
  (:510-513). `revertDeclinedCard`'s javadoc (:552-555) says "Never on UNKNOWN/UNRECONCILED"; the body (:562-567) has no
  guard. The enum doc (:394-397) states UNRECONCILED "never becomes NO_CHARGE" by design.
- `markNeedsOperatorIfDue` (:655-667) marks `WRITE_LOST` after `payment.packAttempt.operatorAfterMinutes` (default 60,
  `ConfigBounds:283-285`); `closeAttemptByAdmin` (:680-721) answers `WRITE_LOST` with 409 `payment.packAttempt.writeLost`
  (:716). So only SQL closes it.

### Retried upgrade (RS07c)

- `SubscriptionService.changeCoachTier` (:298-336) / `changePlayerTier` (:596+) compute the rank from the LOCAL tier, which
  a failed persist left old, so a retry calls `updateStripePrice(..., true, "sub-upgrade-", ...)` again with a fresh
  idempotency key (`keyPrefix + UUID.randomUUID()`, :1813).
- `StripeClient.updateSubscriptionTier` (:303-322) retrieves the subscription, takes item 0 and sets the new price with
  `ALWAYS_INVOICE` + `ERROR_IF_INCOMPLETE`. It never compares item 0's current price with the target, although it holds it.
- `PaymentRecoveryRecorder.recordUngrantedUpgradeTx` (:80-91, REQUIRES_NEW on the payment pool) skips an insert when an
  open row exists for (subscription, tier, invoice) — a check, not a constraint. Table from V228 :67-81: no unique
  constraint, only `idx_sur_unresolved (created_at) WHERE resolved_at IS NULL`. Next migration would be V234.

### File ownership (RS03d)

- `filestorage/service/FileStorageService.softDelete` (:318-349) refuses unless `fso.getCreatedBy().equals(currentUserLogin)`
  (:328). `created_by` is the login (`AbstractAuditingEntity`, `updatable=false`, filled by `SpringSecurityAuditorAware`
  from `UserDetails.getUsername()`). `EmailChangeService` rewrites `login` on confirm (:229-232) and undo (:264-265).
- `owner_id` (`FileStorageObject:31`, `varchar(255) NOT NULL`, indexed `idx_storage_objects_owner_id`) is the user id as
  a string, set from `securityUtil.requireCurrentBusinessId()` (`FileStorageService:81-83`, :546-548). Migrated files
  (`StorageMigrationService.importMetadata` :81-94) copy `owner_id` but get the auditor's `created_by`, so they fail the
  compare too.
- Callers: `StorageResource:67-75` (`DELETE /api/storage/**`, passes `securityUtil.getCurrentUserName()`, no tolerance);
  `CoachProfileService.deletePhoto` (:458) and `CoachGalleryService.deleteItem` (:119) catch `AuthorizationException`,
  log "likely a stale created_by" and clear the reference anyway, leaving the storage object live (still counted in quota).
- `_bmad-output/project-context.md` (Framework rules) says the delete check is "via `fso.getCreatedBy()`" — update it if
  D14 changes the check. So does `CoachProfileService.deletePhoto`'s javadoc (:408-424, the skillars-deferred-139 D2
  paragraph explaining the `AuthorizationException` tolerance as a stale login); rewrite it with the check.

## Decisions (DECIDED 2026-11-07: option 1 in each)

- **D9 (RS03f): serialising a user's Stripe-customer outbox work. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* Both handlers take a blocking `pg_advisory_xact_lock(<NS>, hashtext(:stripeCustomerId))` before
     reading anything, keyed on the Stripe customer id (the erasure payload carries only that; the sync resolves it from
     its `stripe_customers` row, then re-reads the user under the lock). Pro: strict — whichever runs second reads state
     committed after the first finished (a sync after an erasure sees the tombstone; the later of confirm/undo writes
     last); no schema change; matches the CCXL/FSOQ precedent. Con: a second handler holds an outbox thread and
     connection while it waits for the first one's Stripe call (bounded by the Stripe client timeout).
  2. `pg_try_advisory_xact_lock`, and throw to retry when taken. Pro: never blocks a thread. Con: the loser waits a
     backoff interval; contention shows up as outbox failures/`last_error` noise.
  3. No lock: after its Stripe call the sync re-reads the user and, if now erased or the email changed, enqueues a
     follow-up (re-scrub / re-sync). Pro: no locking. Con: eventual, not strict; can loop under churn; more code.
- **D10 (RS03e): the email change missed during first customer creation. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* `createStripeCustomer` returns the email it sent; each caller, after inserting the
     `stripe_customers` row, re-reads the user's email and, if it differs, enqueues `STRIPE_CUSTOMER_EMAIL_SYNC` (in a
     transaction, since the enqueue is `MANDATORY`). Pro: one extra read, no Stripe call in the common case. Con: leaves a
     residual where the email-change transaction spans both the row insert and the re-read (filed as a new low line).
  2. Always enqueue a sync after inserting a new customer row. Pro: simpler. Con: one extra Stripe call per new customer;
     same residual.
  3. Accept and close the ledger line as won't-fix. Pro: free. Con: receipts go to the old address until the next change.
- **D11 (RS09g, RS09h): card writes onto an erased row. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* One `StripeCustomerRepository`/service helper used by `getOrCreateStripeCustomer` (existing-row
     branch) and `reapplyPaymentMethodAndScheduleSync`: in a transaction, `findByIdForUpdateWaiting(parentId)`, then read
     the user; if the email is the `@erased.io` tombstone (or the user is gone), write nothing and enqueue nothing;
     otherwise a targeted UPDATE of the card column (RS09g: no full-row save; RS09h: keep the reapply's compare). Pro: no
     migration; the lock-then-read order sees a committed erasure. Con: a row lock on the purchase path (short; the
     resolver already uses this finder).
  2. Add `stripe_customers.erased_at` (V234), set by `scheduleErasure`, and `AND erased_at IS NULL` on every targeted card
     write. Pro: the WHERE re-check handles it without a lock. Con: migration + provisioning entry; every future write
     must remember the predicate.
  3. RS09g only (targeted update), accept RS09h. Con: leaves the GDPR write-back.
- **D12 (RS06i): an UNRECONCILED attempt Stripe shows no charge for. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* `markNoCharge` skips `revertDeclinedCard` when the attempt is UNRECONCILED (fix the javadoc to say
     "never on UNRECONCILED"; UNKNOWN keeps the revert, because the resolver has just confirmed no succeeded charge). The
     job keeps leaving it open (WRITE_LOST → NEEDS_OPERATOR, unchanged). `closeAttemptByAdmin` alone may CAS UNRECONCILED
     → NO_CHARGE when Stripe shows no succeeded charge, and closes the linked `refund_failures` row. Pro: an operator
     closes it from the UI after checking; the automated path still never overrides a seen capture. Con: one more branch
     in the close action.
  2. Skip the revert only; closing stays SQL. Pro: smallest. Con: the "only SQL closes it" half stays open.
  3. Also let the job CAS UNRECONCILED → NO_CHARGE. Con: lets an inference from absence override a capture the request
     thread saw; contradicts the enum's documented design.
- **D13 (RS07c): the retried upgrade. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* `StripeClient.updateSubscriptionTier` returns without updating when item 0 already has the target
     price (a signal to the caller, e.g. a result flag), so the retry only persists the tier; and a successful persist
     resolves any open `subscription_upgrade_reconciliations` row for that subscription and target tier (`resolved_at`,
     with a log line). Keep the open-row check; accept the same-instant duplicate (two concurrent failures of one
     subscriber's upgrade) and record it in the story, not as a ledger line. Pro: removes the only real money risk (a
     second `always_invoice`) without depending on Stripe's zero-proration behaviour; no migration. Con: the
     same-instant duplicate row stays possible (harmless: same invoice id visible).
  2. Option 1 plus a partial unique index (V234,
     `UNIQUE (stripe_subscription_id, target_tier, coalesce(latest_invoice_id,'')) WHERE resolved_at IS NULL`) and
     `INSERT ... ON CONFLICT DO NOTHING`. Pro: a real constraint. Con: migration, a dedupe step for existing open rows,
     a provisioning entry, for a duplicate that needs two concurrent failures.
  3. Only verify with Stripe test mode that a same-price update bills zero, and document it. Con: depends on Stripe
     behaviour staying that way; no code guard.
- **D14 (RS03d): who may delete a stored file. DECIDED 2026-11-07: option 1.**
  1. *(Recommended)* `softDelete` compares `owner_id` with the caller's business id
     (`securityUtil.requireCurrentBusinessId()`), and the three callers pass that instead of the login. The
     `deletePhoto`/`deleteItem` catches stay (they now fire only for a genuinely foreign object) with the "stale
     created_by" log text corrected; update `project-context.md`. Pro: stable across email changes; fixes migrated files
     too; `owner_id` is NOT NULL and indexed. Con: signature change across three callers.
  2. Accept either `owner_id` or `created_by`. Pro: transitional. Con: keeps the login compare alive for no gain.
  3. Rewrite `created_by` on email confirm/undo. Con: column is `updatable=false`; a security → filestorage write; misses
     migrated files.

## Acceptance Criteria

1. **AC1 (RS03f, D9)** A confirm-sync and an undo-sync for one user never leave Stripe on the confirmed address after the
   undo committed, and a sync never writes a real address after the erasure handler touched that customer. With D9.1:
   both handlers take the advisory lock (new namespace constant, non-null key) before their first read; the sync re-reads
   the user under the lock. An IT holds the lock in one transaction and shows the other handler waits, then reads the
   committed state (tombstone → no Stripe call).
2. **AC2 (RS03e, D10)** An email change that commits between `createStripeCustomer`'s email read and the
   `stripe_customers` insert ends with Stripe on the new address (D10.1: a sync is enqueued when the stored email differs
   from the one sent; none when equal). Tested on both callers' paths.
3. **AC3 (RS09g, RS09h, D11)** No path writes a card onto the `stripe_customers` row of a user whose email is the
   `@erased.io` tombstone, and none enqueues a subscription card sync for one. `getOrCreateStripeCustomer` no longer
   calls `save` on an existing row (a targeted UPDATE instead). The reapply keeps its compare semantics for live users.
   Tests: unit tests for the erased/live branches; an IT that commits an erasure while a reapply waits on the row lock.
4. **AC4 (RS06i, D12)** `markNoCharge` never reverts the card for an UNRECONCILED attempt (the javadoc matches the code).
   With D12.1: the admin close of an UNRECONCILED attempt that Stripe shows no succeeded charge for closes it as
   NO_CHARGE and resolves its `refund_failures` row; the job still never does. Existing UNKNOWN behaviour is unchanged.
5. **AC5 (RS07c, D13)** A retry of an upgrade whose Stripe item already has the target price makes no Stripe update call
   and persists the tier; a successful persist resolves the open reconciliation row for that subscription and tier.
   Coach and player paths both tested.
6. **AC6 (RS03d, D14)** A user can delete through `DELETE /api/storage/**` a file they uploaded before changing their email;
   another user still cannot (403 as today). `project-context.md` describes the new check.
7. **AC7** Docs: payment dev-docs (`docs/dev-docs/payment/index.html`) describe the lock, the erased-row guard, the
   admin close of an UNRECONCILED attempt and the same-price upgrade skip; filestorage dev-docs the ownership check; the
   search index rebuilt; `check-docs` OK. Provisioning checklist entry only if a migration or config key is added.
8. **AC8** Ledger: RS03d, RS03e, RS03f, RS06i, RS07c, RS09g, RS09h deleted from `deferred-work.md`; any residual a
   decision accepts filed as a new line. sprint-status updated.

## Tasks / Subtasks

- [x] T0 Owner decisions D9-D14 recorded in this file (option 1 each).
- [x] T1 (AC1) Advisory lock in both Stripe-customer handlers; IT.
- [x] T2 (AC2) Post-insert email compare and sync enqueue in both customer-creation callers; tests.
- [x] T3 (AC3) Erased-row-safe targeted card writes for `getOrCreateStripeCustomer` and the reapply; tests.
- [x] T4 (AC4) `markNoCharge` revert guard; admin close for UNRECONCILED; tests (`PackPurchaseAttemptIT` and unit).
- [x] T5 (AC5) Same-price skip in `updateSubscriptionTier`; resolve open reconciliation row on successful persist; tests.
- [x] T6 (AC6) `softDelete` owner-id check, callers, `project-context.md`; tests.
- [x] T7 (AC7) Docs and search index.
- [ ] T8 (AC8) Ledger lines; sprint-status; `/pr-review` until a clean round; PR.

## Dev Notes

- **Transactions.** The outbox handler runs inside `claimAndHandle`'s REQUIRES_NEW transaction, so an advisory xact lock
  taken from a repository inside `handle` is held until that transaction commits (after the row delete) — check that the
  lock query runs on the same connection (the payment `RoutingDataSourceContext` must not route it to another pool; the
  175 handler's repository calls already run there). `purchasePack` and `createSetupIntent` are non-transactional: the
  D10/D11 helpers need their own `@Transactional` boundary (a service method, reached through the proxy).
- **Keys.** `hashtext(NULL)` locks nothing (`MailManager:107-108`): guard a blank Stripe customer id before locking.
  Pick a namespace constant not equal to CCXL/FSOQ/DSPL; grep `NAMESPACE = 0x` to confirm.
- **Erased marker.** Use the same `@erased.io` suffix the email-sync handler uses; consider one shared constant (security
  module owns the tombstone format, `GdprErasureService:424`).
- **Do not** widen the job's NO_CHARGE CAS to UNRECONCILED (D12 option 3 is rejected unless the owner picks it).
- **Tests.** ITs that need `StripeClient` use the shared `@MockitoBean StripeClient stripeClient` field exactly as
  `SubscriptionLifecycleIT`/`EmailChangeServiceIT` declare it, so no new Spring context is created
  (`IntegrationTestConventionTest`, failsafe context-count gate). Committed setup writes go through `transactionTemplate`
  (hikari auto-commit is off). Seed a parent user and player profile before any `player_subscriptions` row
  (`fk_pps_player`). Lock-holding pattern: `OperationalBacklogMetricsIT.aTimedOutRefresh...` (executor + latches).
- **Run** targeted tests only: `-DskipFrontend -Djacoco.skip=true`; ITs via `-Dtest=none
  -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=...`. No local full `mvn verify`; CI is the gate.
- **Stripe SDK 28.4.0:** `RateLimitException extends InvalidRequestException` — catch it first wherever 4xx handling is
  touched.

### References

- `deferred-work.md` RS section (lines for RS03d-RS09h at 0df391d5).
- skillars-deferred-174 (card ownership, targeted writes, admin close) and skillars-deferred-175 (email sync, upgrade
  recorder) story files.
- `docs/deployment/migration-conventions.md` (only if D11.2 or D13.2 is picked).

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### Debug Log References

- The first IT runs failed before any test ran: the Postgres test container timed out on startup (Docker VM 3.8 GB, two
  other compose stacks using ~3 GB). After stopping the unrelated `my-hotel` stack (owner's choice) the six ITs ran green.
- `SessionPackPaymentServiceTest.purchasePack_chargesTheNamedCard_evenWhenTheResolverRevertedTheColumnBeforeTheCharge`
  hooked the RS09d interleaving on `stripeCustomerRepository.save`; moved to `writeCardIfPayerLive`. Three admin-close
  unit tests used an UNRECONCILED attempt as a generic open row and expected the card revert (the RS06i behaviour); they now
  use IN_PROGRESS, and the UNRECONCILED case has its own test.

### Completion Notes List

- Story created 2026-11-07 (bmad-create-story); all seven items re-verified open at 0df391d5. Owner decided D9-D14
  option 1 each.
- AC1 (D9.1): `StripeCustomerRepository.acquireCustomerOutboxLock` (`pg_advisory_xact_lock(0x5343554C "SCUL",
  hashtext(:stripeCustomerId))`). The email-sync handler resolves the customer id from its row, locks, then reads the
  user; the erasure handler locks first (blank id: no-op, no lock). IT in `EmailChangeServiceIT`: while a transaction holds
  the lock and commits the tombstone, a sync waits on it and then makes no Stripe call. Handler unit tests check the order.
- AC2 (D10.1, implemented differently): rather than changing `PaymentGateway.createStripeCustomer`'s return type (interface,
  `StubPaymentGateway`, five tests), each caller reads the email through `StripeCustomerEmailSyncOutboxSupport.currentEmail`
  BEFORE creating the customer and calls `syncIfEmailChangedSince` after the row insert (own `TransactionTemplate`; never
  throws). Same effect: any change after the pre-read is caught. Residual filed as RS03g.
- AC3 (D11.1): new `StripeCustomerCardWrites` (`lockIfPayerLive`: `findByIdForUpdateWaiting`, then the user; erased =
  `@erased.io` tombstone or gone; a null email is live). `getOrCreateStripeCustomer`'s existing-row branch uses
  `writeCardIfPayerLive` (new one-column `updatePaymentMethod`); `reapplyPaymentMethodAndScheduleSync` calls
  `lockIfPayerLive` before its compare-and-set. IT in `StripeCustomerRepositoryIT`: a reapply waiting on an uncommitted
  erasure returns false and the card stays NULL.
- AC4 (D12.1): `resolve(attemptId, byAdmin)`; `noCharge` never reaches `markNoCharge` (and its revert) for UNRECONCILED. Only
  `closeAttemptByAdmin` CASes UNRECONCILED → NO_CHARGE (`[PACK_PURCHASE_UNRECONCILED_NO_CHARGE]`) and resolves the
  refund-failure row; the job still gets WRITE_LOST. Javadocs (revert, enum, close action) corrected. Tests: unit (operator
  close, job leaves it and never reverts) and `PackPurchaseAttemptIT` (closes, keeps its intent id).
- AC5 (D13.1): `StripeClient.updateSubscriptionTier` returns the retrieved subscription without updating when item 0 already
  has the price (`alreadyAtPrice`, `[STRIPE_SUBSCRIPTION_PRICE_UNCHANGED]`). `persist*TierUpgrade` now returns whether it
  granted; on true, `change*Tier` calls `PaymentRecoveryRecorder.resolveUngrantedUpgrades` (REQUIRES_NEW on the payment pool,
  never throws) after the persist committed. The same-instant duplicate-row case is accepted here (D13.1), not ledgered.
  Tests: `StripeClientTest`, `SubscriptionEntitlementIntegrityTest` (resolve on grant, not on failure/superseded),
  `PackPurchaseAttemptIT` (resolves only the target tier).
- AC6 (D14.1): `FileStorageService.softDelete(key)` compares `owner_id` with `securityUtil.requireCurrentBusinessId()`
  (as `signDownload` does); `deletePhoto(userId)` / `deleteItem(userId, mediaId)` and both endpoints drop the login argument;
  the tolerant catches keep their behaviour with corrected log text; `deletePhoto` javadoc and `project-context.md` updated.
  IT in `FileStorageDeletionIT` (stale `created_by` deletes; another owner's file refused).
- AC7: payment and filestorage dev-docs, monitoring.md MoneyIntegrityEvent text; search index rebuilt; `check-docs` OK. No
  migration, config key, env var or job, so no provisioning-checklist entry.
- AC8: RS03d, RS03e, RS03f, RS06i, RS07c, RS09g, RS09h deleted; RS03g filed; section note updated.
- Tests (targeted; CI is the full gate): 1788 unit tests across payment, filestorage, marketplace, security service, db,
  config and convention tests; ITs `EmailChangeServiceIT` (24), `StripeCustomerRepositoryIT` (7), `PackPurchaseAttemptIT`
  (18), `SessionPackPurchaseIT` (4), `FileStorageDeletionIT` (7), `SubscriptionLifecycleIT` (28), all green.
- No red run before the fixes (deviation, as in 175).

### File List

- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/SubscriptionUpgradeReconciliationRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerCardWrites.java` (new)
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerEmailSyncOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerEmailSyncOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeCustomerErasureOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/PaymentRecoveryRecorder.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeClient.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/service/FileStorageService.java`
- `src/main/java/com/softropic/skillars/platform/filestorage/api/StorageResource.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachGalleryService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java`
- Tests: `StripeCustomerCardWritesTest` (new), `StripeClientTest` (new), `StripeCustomerEmailSyncOutboxHandlerTest`,
  `StripeCustomerEmailSyncOutboxSupportTest`, `StripeCustomerErasureOutboxHandlerTest`, `SessionPackPaymentServiceTest`,
  `SessionPackPaymentResourceWebMvcTest`, `SubscriptionEntitlementIntegrityTest`, `CoachProfileServiceTest`,
  `CoachGalleryServiceTest`, `EmailChangeServiceIT`, `StripeCustomerRepositoryIT`, `PackPurchaseAttemptIT`,
  `FileStorageDeletionIT`
- Docs: `docs/dev-docs/payment/index.html`, `docs/dev-docs/filestorage/index.html`, `docs/dev-docs/assets/search-index.js`,
  `docs/deployment/monitoring.md`, `_bmad-output/project-context.md`
- Ledger: `_bmad-output/implementation-artifacts/deferred-work.md`, `sprint-status.yaml`, this story

### Change Log

- 2026-11-07: created (ready-for-dev) from the owner-chosen payment-residuals bundle; decisions D9-D14 pending owner.
- 2026-11-07: owner decided D9-D14 (option 1 each); implemented T1-T7 and the ledger part of T8 (dev-story). `/pr-review` and the PR pending.
- 2026-11-07: `/pr-review` round 1 (f9d72bed): V1 the admin close of an UNRECONCILED row now decides from its stored PaymentIntent (`retrieveCharge`), never from the charge search, and an absence verdict never closes it (no stored intent: 409 capturedNoPack); a refunded one closes as REFUNDED and resolves its refund-failure row. V2 `scheduleErasure` NULLs the card with an UPDATE (`clearPaymentMethod`) that always locks the row. Tests: 5 new/rewritten unit tests, the UNRECONCILED IT now asserts the refusal, and `aReapplyRacingTheRealErasureOfAnAlreadyEmptyCard_waits_andWritesNoCard`.
- 2026-11-07: `/pr-review` round 2 (445557b7): V1 `StripeClient.updateSubscriptionTier` returns `TierUpdate(subscription, sent)`, and a granted upgrade resolves the open ungranted-upgrade rows only when no update was sent (nothing billed now); a grant that billed again leaves the earlier charge owed. V2 the card-write and Stripe email-sync checks treat only the exact `deleted.<id>@erased.io` tombstone as erased (`StripeCustomerCardWrites.isErasureTombstone`), not the whole domain.
