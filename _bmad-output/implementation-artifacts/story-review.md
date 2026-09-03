# Senior-dev audit — `skillars-deferred-91`

**Reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-91-transactional-outbox-generalization-completion-gated-payout-migration-safety-hardening-de-de-formal-register-and-genuine-bug-fixes.md` (v0.1, 20 ACs, status `ready-for-dev`)
**Date:** 2026-09-03 · **Branch:** `story/deferred-91-outbox-escrow-migration-i18n`
**Method:** every AC's named file, line, symbol and mechanism was opened on this branch and checked. `PathPattern` matching semantics (AC15) were verified by compiling and running `PathPatternParser` against the real `spring-web 6.2.19` jar rather than reasoned about. Nothing below is inferred from the ledger or from the story's own prose.

**Verdict: not ready for dev.** The ACs are well-organised and the scope decisions are coherent, but seven of them prescribe a fix that the code contradicts, and three of those would actively regress an invariant a previous story deliberately installed. AC1's outbox design is sound as a copy of `PendingBlobDeletionService` but is missing the properties that make an outbox safe for *money* specifically. Recommend a v0.2 pass on AC2, AC3, AC5, AC6, AC13, AC15, AC17 and AC19 before it goes to a dev.

> **Audit pass — 2026-09-03.** Every blocking finding (B1–B8) and the medium/low findings sampled (M2, M3, M4, M5, M6, M7, M9, M11, L1, L3, L4) were independently re-checked against the source on this branch. All substantive claims hold: the refund listeners genuinely touch no Stripe path and have no idempotency guard against re-drive (B1/B2); `MailManager` is the real send site, not the listeners (B4); `PaymentPendingSweeper` already exists with the counter AC5(a) asks for and a javadoc that states the opposite of AC5(a)'s fix (B5); `AccountDeletionCascadeListener` carries an explicit "Must NOT be `@Transactional`" and the `@Modifying` repos AC13 blames already declare `@Transactional` (B6); `AdminAlert.type` fails at Hibernate hydration, before `buildSummary` (B8); the migration-conventions doc lists **five** guard blind spots and its grandfather list is **seven** items incl. the V91 enum widen (M3/M4); the `IllegalStateException` handler has 26 throw sites, several genuine 409s (M5); `getPublicProfile` fires nine separate repository round-trips with no JPA association to collapse (M11). **No false positives found.** Two count slips in this review's own prose are corrected inline below: `BookingEmailListener` has 18 `@TransactionalEventListener` methods (not 16, B4) and `PUBLIC_ENDPOINTS` carries 18 unanchored-`**` patterns beyond the three `resend-otp**` AC15 names (not eleven, B7). Line citations in the blocking section have drifted 1–3 lines from the true positions (e.g. `ErrorLog` `String.format` is `:67`, not `:68`); the review already flags line drift as expected and Task 0 re-verifies, so these are left as-is.

---

## Summary table

| # | AC | Class | Finding |
|---|----|-------|---------|
| B1 | AC2 | False premise | No `AFTER_COMMIT` listener issues a Stripe refund; the refund idempotency key AC2 tells the dev to reuse does not exist |
| B2 | AC2 | Money bug | Re-driving these listeners double-credits an **append-only** ledger and duplicates strikes / history rows / pack-session restores |
| B3 | AC2, AC3 | Impossible as written | An `AFTER_COMMIT` listener cannot "enqueue inside the producing transaction" — the producer has already committed |
| B4 | AC3 | Wrong layer | `BookingEmailListener` / `SessionPackEmailListener` do not send email; `MailManager` does. Outboxing the listener leaves the drop in place |
| B5 | AC5(a) | Reverses a safety invariant | Auto-terminating a `CAPTURE_PENDING` row re-opens Deferred-12 D2 ("money captured, booking cancelled, no refund"); `PaymentPendingSweeper` already exists and deliberately refuses to do this |
| B6 | AC13 | Contradicts documented design | `AccountDeletionCascadeListener` is explicitly annotated "Must NOT be `@Transactional`"; the prescribed fix breaks per-video failure isolation and the deferred-77 AC12 quota guard. Root cause also unsupported |
| B7 | AC15 | Security regression | The prescribed `resend-otp/**` **widens** the public surface. The story's stated `PathPattern` semantics are wrong |
| B8 | AC6 | Wrong layer + untestable | The `Enum.valueOf` is Hibernate hydration, not `buildSummary`; a `grep Enum.valueOf` finds nothing; the IT cannot insert a bogus value because of `admin_alerts_type_check`; wrong column name |
| M1 | AC1 | Missing mechanism | No backoff, no `available_at`, no dead-letter, no per-domain fairness — a poison Stripe row retries forever every 5 min |
| M2 | AC4 | Not durable | Enqueuing from `@Recover` uses the resource that just failed; only the reconciliation branch actually closes the gap |
| M3 | AC7 | Incomplete | The conventions doc names **five** blind spots, not three; the git-based backport rule fires only in the commit that adds the file |
| M4 | AC8 | Self-defeating premise | Re-doing already-applied migrations "online-safely" buys nothing in any environment; the doc's grandfather list also includes V91, which AC8 omits |
| M5 | AC17 | Breaks real 409s | Several request-reachable `IllegalStateException`s are genuine state conflicts |
| M6 | AC19 | Internally contradictory | Routing through `getPlayerNamesByPlayerIds` reintroduces the NPE deferred-90 just fixed; only 1 of the 6 sites is an id→name lookup |
| M7 | AC16 | Not expressible | `@RateLimited` is an annotation; it cannot be "parameterized by the role rate-limit key" in a shared collaborator |
| M8 | AC18 | False premise | The bootstrap secret is `SecureRandom`, not "a known secret"; the localhost heuristic breaks the docker-compose dev stack |
| M9 | AC12 | Regresses deferred-90 D2 | Applying the same snippet to `logExpected` re-adds the stack trace that was deliberately removed |
| M10 | AC9, AC10 | Sweep design | The de-DE grep misses the majority of the work; a regex accent sweep will corrupt correct French |
| M11 | AC11 | Prescribed tool doesn't fit | `@EntityGraph` cannot collapse these — they are independent repositories with no JPA associations |
| M12 | — | Coverage gap | The one post-commit money-loss path the codebase itself documents — `PaymentLifecycleService`'s **charge** listeners — is covered by no AC |
| L1–L6 | various | Accuracy | Line/annotation/count errors in Dev Notes and Task 21 |

---

## Blocking findings

### B1 — AC2's entire premise is false: there is no Stripe refund on any `AFTER_COMMIT` listener

AC2 says *"Every `@TransactionalEventListener(AFTER_COMMIT)` that issues a Stripe refund…"* and instructs the dev to *"reuse the Stripe idempotency key already keyed on the booking/payment id."*

`CancellationRefundService` (`src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java`) contains five `AFTER_COMMIT` listeners and **none of them touches Stripe**. Every one is an internal credit-ledger write plus pack bookkeeping:

- `onBookingCancelledByParent:35`, `onBookingCancelledByCoach:57`, `onCoachNoShow:88`, `onBookingCancelledByAdmin:113` → `creditWalletService.writeLedgerEntry(...)` / `packSessionService.restoreSession(...)`
- `onPlayerNoShow:129` → a log line only.

The only two `paymentGateway.refund(...)` call sites in the codebase are `CashOutService.java:52` and `SessionPackPaymentService.java:90` — **neither is an event listener**. So the set AC2 enumerates is empty.

Worse, the idempotency key AC2 tells the dev to reuse does not exist. `StripePaymentGateway.refund` (`:121-133`) builds `RefundCreateParams` with `setPaymentIntent` + `setAmount` and calls `StripeClient.createRefund` (`:44`), which is a bare `Refund.create(params)` — **no `setIdempotencyKey`**. The only idempotency keys in the codebase are on payment-intent creation (`StripePaymentGateway:100`) and on subscriptions (`StripeClient:77/93/113`).

**Action:** rewrite AC2 around what these listeners actually do (credit-ledger + pack restore), and either (a) drop the Stripe-refund framing entirely, or (b) add a separate AC that gives `PaymentGateway.refund` an idempotency key derived from the booking id — which is a prerequisite for *any* retry-driven refund, and is currently missing.

### B2 — Re-driving the cancellation listeners double-credits an append-only ledger

This is the reason B1 matters rather than being a wording nit. AC2 assumes "make the handler idempotent and re-drive is safe." For these listeners it is not:

- **`CreditWalletService.writeLedgerEntry` (`:36-50`) has no idempotency guard.** It builds a `ParentCreditLedger` and `save`s it unconditionally. There is no unique constraint on `(type, reference_id)` — `V62__session_payment_credit_wallet.sql` creates only `pk_parent_credit_ledger` on `tx_id` and a plain `idx_pcl_parent_id`.
- **The ledger is enforced append-only at the DB layer.** `V79__credit_ledger_append_only.sql` installs `BEFORE UPDATE` / `BEFORE DELETE` triggers that `RAISE EXCEPTION 'parent_credit_ledger is append-only'`. A duplicate `BOOKING_REFUND` written by an outbox re-drive therefore **cannot be corrected** by any code path — it is a permanent credit grant.
- **`PackSessionService.restoreSession` (`:75-81`) is a blind `remainingSessions + 1`.** A re-drive hands out a free session.
- **`reliabilityStrikeService.issue(...)`** (in `onBookingCancelledByCoach:80` and `onCoachNoShow:107`) and **`saveCancellationHistory(...)`** (`:78`) run on the same listener body. A re-drive issues a second strike against the coach and writes a second history row — user-visible, punitive, and not what AC2's "the refund handler must be idempotent" contemplates.

**Action:** AC2 must specify idempotency at the *handler body* level, not just "the refund." The natural mechanism is a partial unique index on `parent_credit_ledger(type, reference_id) WHERE reference_id IS NOT NULL` plus an `ON CONFLICT DO NOTHING` write path, and an equivalent guard on `restoreSession` / strike issuance. That is real work and needs its own AC or its own story — it is not a free rider on AC1.

### B3 — "enqueue inside the producing transaction" is not possible from an `AFTER_COMMIT` listener (AC2 and AC3)

AC2: *"Every `@TransactionalEventListener(AFTER_COMMIT)` … now enqueues an outbox row **inside the producing transaction** and drains post-commit."* AC3 says the same for the email listeners.

By definition the listener runs **after** the producing transaction has committed. Nothing it writes is in that transaction. `AC1`'s own contract — *"a producer writes inside its own transaction"* — is satisfiable only by moving the `enqueue(...)` call to the **publisher** site (`BookingService`, `SessionPackForfeitureScheduler`, etc., where the event is published inside `@Transactional`), and deleting or reducing the listener.

Any implementation that enqueues from inside the listener has the identical hole the story is trying to close: the process can die between the producer's commit and the listener running, and the row is never written.

**Action:** rewrite AC2/AC3 as "the publisher enqueues; the listener is replaced by an outbox handler." This is a substantially larger change than "wire the listener onto the outbox" and should be reflected in the task breakdown.

### B4 — AC3 targets the wrong layer; the email drop is in `MailManager`, not in the listeners

`SessionPackEmailListener.onExpiryWarning` (`:39-61`) does not send an email. It builds an `Envelope` and calls `publisher.publishEvent(new Envelope(...))`. Same for `onPackExpired`, `onPackPaused`, and for all 18 listeners in `BookingEmailListener`.

The actual send is `MailManager.sendEmailFromTemplate` (`:57-61`):

```java
@Async("sendMailPool")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void sendEmailFromTemplate(final Envelope envelope) { ... }
```

So the chain is: business tx commits → `SessionPackEmailListener` (AFTER_COMMIT, **not** transactional) publishes an `Envelope` → `MailManager` is *also* an `AFTER_COMMIT` listener with the default `fallbackExecution = false`. Publishing a transactional event from inside another transaction's after-commit callback is exactly the fragile case `skillars-deferred-15` D1 recorded, and the `@Async` hand-off adds a second loss point (a queued task dies with the JVM).

Putting the intermediate listener behind the outbox does not help: the drain would still publish an `Envelope` outside a transaction and `MailManager` would still drop it.

**Action:** AC3's outbox handler must call `MailManager.sendEmailSync(envelope)` (`:54`) directly, bypassing the event hop. Also: `Envelope.sendId` is a `UUID.randomUUID()` minted at construction (`SessionPackEmailListener:55`, `:78`) — it is the obvious dedupe key for the "acceptable duplicate" wording in AC3 and must be **persisted with the outbox row**, not regenerated on re-drive. AC3 does not mention `sendId` at all.

### B5 — AC5(a) reverses a deliberately-installed money-safety invariant, and duplicates a component that already exists

`PaymentPendingSweeper` already exists (`src/main/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeper.java`), runs `@Scheduled(fixedDelay = 15, MINUTES)` with `@SchedulerLock`, and already emits the exact counter AC5(a) asks for — `booking.payment_pending.unrecoverable` with `tag("reason", …)`, values `CAPTURE_UNCONFIRMED` / `PAYMENT_ROW_PRESENT` (`:83`, `:186-192`).

Its class javadoc states the design decision AC5(a) proposes to overturn, verbatim:

> **CAPTURE_PENDING** (`reason=CAPTURE_UNCONFIRMED`) — an attempt reserved and never finished. Money may already be at Stripe with nothing recording it. **There is no automated exit: an operator must search Stripe by the booking or batch id and settle or decline the row by hand.**

And `sweepOne` (`:143-152`) takes a `findByIdForUpdate` lock specifically so it can never write `CHARGE_FAILED` over a row that a concurrent `reserveCapture` just created — *"recording 'no money moved' over a booking whose charge may already have reached Stripe, which is the exact harm this class refuses to risk."*

AC5(a) asks for precisely that write (`CHARGE_FAILED` or `CAPTURE_ABANDONED`) plus a slot release, while simultaneously stating *"No automatic charge/confirm — only a human can read the Stripe side."* Those two halves contradict each other: releasing the slot and terminating the payment row **is** deciding the Stripe outcome. It re-opens Deferred-12 D2 (money captured, booking cancelled, no refund) — with the added twist that the parent's cancel guard at `BookingService.java:736-746` would no longer fire, so the parent could then cancel a booking that was in fact charged.

AC5's premise that the parent's cancel is blocked **is** correct (`BookingService:743-746` throws `409 booking.paymentInProgress`) — that part checks out. But the harm being bounded is the *slot hold*, and the safe way to bound it without deciding the payment is to leave the booking's payment row alone and instead free the coach's availability (or fail the booking to a **new, explicitly non-terminal** "needs reconciliation" status that is excluded from `ACTIVE_SLOT_STATUSES` / V87's exclusion constraint but does **not** claim "no money moved").

**Action:** AC5(a) must be rewritten by Task 0's design doc, and Task 0 must read `PaymentPendingSweeper`'s javadoc and `docs/deployment/runbook.md` first. The AC as written should not go to a dev. AC20 should also require the runbook be updated if AC5 changes `CAPTURE_PENDING` handling.

### B6 — AC13's prescribed fix contradicts an explicit invariant, and its root cause is not supported by the code

Two separate problems.

**(a) The prescribed fix is banned by a comment written to ban exactly it.** `AccountDeletionCascadeListener.onAccountDeleted` (`src/main/java/com/softropic/skillars/platform/video/service/AccountDeletionCascadeListener.java:28-33`):

```java
/**
 * ...
 * Must NOT be @Transactional — AFTER_COMMIT means no surrounding transaction is active.
 * Each deleteVideo() call creates its own per-video transaction inside cascadeDeleteForAccount().
 */
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onAccountDeleted(AccountDeletionRequestedEvent event) {
```

and `VideoDeletionService.cascadeDeleteForAccount` (`:153-155`): *"NOT @Transactional at the method level — each deleteVideo() runs in its own transaction."* The class carries an `@Autowired @Lazy VideoDeletionService self` field (`:55-57`) specifically so `self.deleteVideo(...)` goes through the proxy and gets its own transaction.

Adding `@Transactional` at either level would wrap the whole cascade in one transaction and destroy two behaviours that were deliberately built:
- the per-video `try/catch → failedIds.add(...) → continue` loop (`:169-179`) — under one outer transaction, a failed `deleteVideo` marks the transaction rollback-only and the *entire* cascade is discarded at commit; and
- the deferred-77 AC12 guard at `:185-191`, which resets the quota **only** when `failedIds.isEmpty()`. Under one transaction the "some succeeded, some failed" state it exists to handle cannot occur — it becomes all-or-nothing, silently.

**(b) The stated root cause does not hold on this branch.** The ledger says *"A `@Modifying` query is executing without a transaction."* `VideoDeletionService.java:186` is `videoQuotaRepository.resetBytesForOwner(ownerId)`, and that method already carries its own transaction:

```java
@Modifying
@Transactional
@Query("UPDATE VideoQuota vq SET vq.storageUsedBytes = 0, vq.bandwidthUsedBytes = 0 WHERE vq.userId = :ownerId")
void resetBytesForOwner(@Param("ownerId") String ownerId);   // VideoQuotaRepository.java:19-22
```

`VideoApprovalRequestRepository.cancelAllPendingForOwners` (`:25-31`), the other `@Modifying` query the listener reaches, likewise carries `@Transactional`. So the observed `TransactionRequiredException` has some other source, and the AC's Task 1 ("reproduce and diagnose") is doing real work — but the AC then prescribes a fix for a diagnosis that has not been established.

**Action:** keep Task 1, delete the prescribed fix. Add an explicit constraint to the AC: *"the per-video transaction boundary and the `failedIds` continue-on-error loop must be preserved; do not add `@Transactional` to the listener or to `cascadeDeleteForAccount`."*

### B7 — AC15's recommended pattern widens the public surface (verified empirically)

AC15 states: *"`PathPatternRequestMatcher` semantics: a trailing `/**` does not cross `/`, an in-segment `**` does."* The second half is wrong, and the recommendation built on it is a security regression.

Verified by compiling `PathPatternParser` from `spring-web 6.2.19` (the version this build resolves — `pom.xml` parent `3.5.16`, and `SecuredHttpEndpointGuard:26-30` constructs `PathPatternRequestMatcher.withDefaults()`):

| pattern | `…/resend-otp` | `…/resend-otp-admin` | `…/resend-otp/admin` | `…/resend-otp/a/b` |
|---|---|---|---|---|
| `…/resend-otp**` (current) | ✅ | ✅ | ❌ | ❌ |
| `…/resend-otp/**` (AC15's fix) | ✅ | ❌ | ✅ | ✅ |

The story's core claim — that `resend-otp**` matches the same-segment sibling `resend-otp-admin` — **is correct** and is a genuine finding. But an in-segment `**` does **not** cross `/`. So the current pattern does not expose any sub-path, and swapping to `resend-otp/**` newly opens `/api/security/coach/resend-otp/<anything>` to `permitAll()`. The AC's own verification step — *"Verify no currently-public endpoint's path becomes non-matched by the tighter pattern"* — checks the wrong direction and would not catch this.

The correct anchoring is the **exact** pattern with no wildcard. Every endpoint under these prefixes is a single flat segment, confirmed in the resources:

```
@RequestMapping("/api/security/coach")   // CoachRegistrationResource.java:25
  @PostMapping("/register")   :32
  @GetMapping("/verify-email"):39
  @PostMapping("/verify-phone"):45
  @PostMapping("/resend-verification"):52
  @PostMapping("/resend-otp"):62
```
(identical shape in `ParentRegistrationResource:25-59` and `PlayerRegistrationResource:25-62`).

Two further points AC15 misses:
- `PUBLIC_ENDPOINTS` is consumed **twice** — by `SecurityConfiguration.configureRequestMatching` (`:60`) *and* by `SecuredHttpEndpointGuard.isUnrestricted` via `ALL_UNRESTRICTED` inside `JWTAuthorizationFilter` (`SecurityConfiguration:202`). Tightening a pattern changes filter behaviour too, not just `authorizeHttpRequests`. The Dev Notes list should cover both.
- The same unanchored form appears on **18** further `PUBLIC_ENDPOINTS` patterns AC15 does not name: `/v1/account/register**`, `/v1/account/regislink**`, `register**` / `verify-email**` / `verify-phone**` / `resend-verification**` (×3 roles each = 12), `/api/auth/login**`, `/api/auth/refresh**`, `/api/auth/logout**`, `/api/marketplace/coaches**`. AC15's "audit every pattern" covers them, but the disposition table in Dev Notes should enumerate them so the reviewer can check each.

**Action:** correct the semantics sentence, change the recommendation from `resend-otp/**` to an exact pattern, and make the convention test assert *"no `PUBLIC_ENDPOINTS` entry contains `**` unless it is preceded by `/` **and** the widening is justified in a comment."*

### B8 — AC6 fixes the wrong layer, greps for something that does not exist, and specifies an IT the schema forbids

Three concrete problems.

**(a) The `Enum.valueOf` is Hibernate hydration, not `buildSummary`.** `AdminAlert.type` is `@Enumerated(EnumType.STRING)` (`AdminAlert.java:33-35`). An unknown DB string throws while Hibernate materialises the entity — inside `adminAlertRepository.findByTypeAndStatus(...)` (`AdminQueueService:64`) and inside `countOpenByType()` (`:143`, where `row[0]` is cast to `AdminAlertType`). By the time `buildSummary` runs, hydration has already failed. `buildSummary` is a `switch` with a `default -> ""` arm (`AdminQueueService:110`) — it cannot see an unknown value.

The tolerant read has to happen at the query/projection layer: read `type` as a `String` in a projection and map with a fallback, or add an `AttributeConverter` with an `UNKNOWN` sentinel, or filter to known values in SQL.

**(b) `grep Enum.valueOf` finds nothing.** The only explicit `AdminAlertType.valueOf` in the class is `:45`, and it operates on the **request query parameter** `typeParam`, already correctly guarded with a `try/catch → 400`. AC6's instruction to *"audit `platform.admin` for any other `Enum.valueOf` on a DB string"* will return zero hits and give false confidence, because the real hazard is annotation-driven. The audit should target `@Enumerated(EnumType.STRING)` fields instead — of which `AdminAlert` has two more that carry the same risk: `referenceType` (`AdminAlert.java:40-42`, rendered at `AdminQueueService:57`) and `status`.

**(c) The IT as specified cannot run.** `V70__admin_alerts_action_log.sql:5` creates `CHECK (type IN (...))`, widened by `V91__messaging_moderation_recovery.sql:14-16` to `admin_alerts_type_check`. Postgres will reject `INSERT … VALUES ('BOGUS_TYPE')`. The IT must temporarily drop the constraint, use a value the enum lacks but the CHECK permits (there is none), or test the mapping layer directly.

**(d) Wrong column name.** AC6 and its IT say `admin_alerts.alert_type` three times. The column is `type` (`V70:5`).

---

## Medium findings

### M1 — AC1's outbox is missing the properties that make it safe for money

AC1 says "copy `PendingBlobDeletionService`'s shape," which is right as far as it goes. But that component was built for a homogeneous, idempotent, cheap operation (S3 `deleteObject`). Reused for refunds, emails and SLU writes it inherits gaps that matter much more:

- **No backoff and no `available_at`.** `PendingBlobDeletionChunkProcessor.processChunk` (`:57-77`) re-claims failed rows on the very next drain, and `sweep()` runs every 5 minutes (`PendingBlobDeletionService:99`). A refund failing on a Stripe 5xx or a rate limit is retried against Stripe every 5 minutes indefinitely. AC1's column list has no `next_attempt_at`.
- **No terminal park state.** AC1 says *"a row is **never** dropped"* and that crossing `STUCK_ATTEMPTS_THRESHOLD` (10) only logs. Combined with the previous point, a genuinely poison row calls a payment provider forever. For blobs that is acceptable; for money it is not. AC1 needs a `PARKED` / `attempts >= N → stop retrying, alert` state.
- **Ordering breaks FIFO across a shared table.** `PendingBlobDeletionRepository.claimNextChunk` orders `attempts ASC, id ASC` (`:29`) — deliberately, to stop head-of-line blocking. For a single-domain blob table that is correct. For a shared multi-domain table it means a refund enqueued after a state change can be drained before it, and one domain's repeatedly-failing rows migrate to the tail where a `MAX_CHUNKS_PER_DRAIN`-bounded drain may never reach them. AC1 needs to state the ordering/fairness contract explicitly (per-`aggregate_type` claim, or a partial index + per-domain drain).
- **`drain()` stops on an all-failed chunk** (`PendingBlobDeletionService:132-135`). With a shared table, a chunk that happens to be all-refund and all-failing halts the drain for the queued emails behind it.
- **"off the request path" is inaccurate.** The `AFTER_COMMIT` listener runs on the committing thread — i.e. still the request thread, just after commit. Only the `@Scheduled` sweeper is genuinely off-path. For a refund path that now includes a Stripe round-trip, this changes request latency. Worth stating so the dev does not assume otherwise.

Also worth deciding in AC1 (rolling-deploy relevant, given this story's own theme): how a `payload jsonb` written by a new instance is read by an old one mid-deploy. A `schema_version` column in the payload is cheap now and impossible to retrofit.

### M2 — AC4 offers two options as equivalent; only one is sound

AC4: *"Wire the failed snapshot write onto the outbox … **or** add a reconciliation sweep…"*

The outbox variant does not work. `SnapshotPersistenceRetrier.recoverSnapshotWriteFailure` (`:61-75`) is `@Recover` — it runs after `@Retryable` exhausts attempts on `DataAccessException` / `TransactionSystemException` / `CannotCreateTransactionException`. Enqueuing an outbox row from there means writing to the database that just failed, from a context (the `AFTER_COMMIT` path) with no transaction. In the `CannotCreateTransactionException` case the enqueue is guaranteed to fail too. You cannot durably record a failure using the resource that failed.

The reconciliation sweep — walking `player_skill_stats` for a session with no `player_slu_weekly_snapshot_applied` marker for its bucket — is the only option that actually closes the gap, and it is idempotent by construction thanks to the `V119` marker.

Two smaller points:
- **The signature does not carry a session.** `writeAllWithRetry(List<PlayerSkillStat> stats, short isoYear, short isoWeek)` (`:57`). AC4's *"re-drive `writeAllWithRetry` for that `(session, isoYear, isoWeek)` bucket"* implies a signature change, and an outbox payload would otherwise have to serialise entity snapshots. Store identifiers and re-read.
- **The IT's assertion may not hold.** AC4 says *"force `writeAllWithRetry` to `@Recover`, assert the snapshot is `0`."* Per the class javadoc (`:26-34`), `TransactionSystemException` explicitly includes the case where Postgres committed server-side and the client lost the ack — so a forced `@Recover` on that path can legitimately leave a non-zero snapshot. Force via `DataAccessException` and say so.

### M3 — AC7 covers three of the five documented blind spots

`docs/deployment/migration-conventions.md` § "What the guard cannot catch" lists **five** bullets. AC7 names three and asserts *"names three"*:

4. *a second validating constraint in the same statement* — `ALTER TABLE t ADD CONSTRAINT a CHECK (…) NOT VALID, ADD CONSTRAINT b CHECK (…);` passes because the rule splits on `;` and asks only whether `NOT VALID` appears somewhere.
5. *a second blocking index in a file that already carries an opt-out* — `-- migration-lint: allow-*` markers are matched against the whole file, so one opt-out silences the rule for every statement.

Both are mechanically checkable and both are cheaper to implement than the three AC7 does name. AC7 also instructs the dev to *"update the conventions doc's blind-spot list to reflect what is now covered"* — which under the AC as written would leave 4 and 5 listed while the AC's own text claims the section had three.

Separately, **the backport rule as specified is weak.** `MigrationLint.lint(Path dir, int baselineVersion)` (`MigrationLint.java:86`) is a pure-filesystem function and `MigrationConventionLintTest` runs in the `test` phase with no container. Comparing against `git show HEAD:…` means the rule fires only in the single commit that adds the backported file; once that commit is in `HEAD`, every later run on the same branch sees the file in `HEAD` and stays silent — so the guard passes on the PR's final CI run, which is the one that matters. It is also fragile under shallow clones and detached-HEAD CI checkouts. A durable alternative: a committed manifest (checksum list) of known migrations that the lint diffs against, so a below-baseline file absent from the manifest is flagged on every run.

### M4 — AC8's premise is self-defeating, and its list is short by one

AC8 asks for follow-up `V123+` migrations that re-do V60/V89/V94/V97/V98/V117 "online-safely."

Consider both environments this can run in:
- **A database where those migrations already ran.** The constraint/index is already installed and validated. A follow-up cannot make a lock that happened in the past shorter. To "re-do it online-safely" you must `DROP` the existing constraint and `ADD … NOT VALID` — and the `DROP` itself takes `ACCESS EXCLUSIVE`. Net effect: strictly more lock churn, zero benefit, plus a window in which the constraint is absent.
- **A fresh database.** Flyway runs V1…V122 in order, so V60 executes against a table that is empty or near-empty. The lock is instant. The conventions doc says as much: *"Skillars has no production system yet."*

There is no environment in which the rewrite helps. AC8's second branch — *"document why each is safe … in the grandfather list"* — is the only one that produces value, and it should be the whole AC. The per-migration disposition table in Dev Notes is still worth doing.

Also: the doc's grandfather list is *"`V60`, `V89`, `V94`, `V97`, `V98`, `V117`, **and the `AdminAlertType` enum widen**"* — that last item is `V91__messaging_moderation_recovery.sql:14-16`, which does `DROP CONSTRAINT … ; ADD CONSTRAINT … CHECK (…)` with no `NOT VALID`. AC8 lists six and omits it. If AC8 keeps a rewrite branch at all, V91 belongs in the audit; if it becomes documentation-only, the doc's own list is already complete and AC8 should say seven, not six.

Minor: AC1 and AC8 both specify "a new `V123+` migration." Only one can be `V123`. Worth a numbering note in Dev Notes so two tasks don't collide.

### M5 — AC17's blanket remap turns real 409s into 500s

AC17 maps `ApiAdvice.illegalStateExceptionHandler` (`src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:595-600`) from `409 CONFLICT` to `500`, on the grounds that a missing `platform_config` key is a server misconfiguration. That reasoning is sound for `ConfigService.getString:63` / `getLong:73`. It is wrong for several other request-reachable throw sites, which are genuine client-visible state conflicts and are correctly 409 today:

- `VideoLifecycleService.markPurged:154` — *"markPurged requires operationalState=READY, got …"*
- `VideoApprovalService:68` — *"createApprovalRequest called but video is not HIDDEN"*
- `VideoApprovalService:157`, `ModerationOrchestrationService:393`, `QuotaService:69`

There are 26 `throw new IllegalStateException` sites in `src/main/java`. AC17 asks the dev to *"confirm none legitimately means 409"* — the answer is that several do. The right fix is not a remap of the catch-all but a dedicated exception for configuration failures (e.g. `ConfigService` throwing `ConfigurationException extends RuntimeException`, handled at 500 with an `ErrorLog` helpCode), leaving `IllegalStateException → 409` in place for domain conflicts. Worth also noting `SecurityUtil:105` ("User not found!") is currently a 409 and is neither — a separate ledger item, not this AC.

### M6 — AC19 is internally contradictory and mostly a no-op

AC19 says: route the id→name sites through `PlayerProfileService.getPlayerNamesByPlayerIds`, and *"Keep the null-tolerant collector deferred-90 added."* Both cannot be true.

`getPlayerNamesByPlayerIds` (`marketplace/service/PlayerProfileService.java:42-48`) uses `Collectors.toMap(PlayerProfile::getId, PlayerProfile::getName, (a,b)->a)`. `Collectors.toMap` delegates to `Map.merge`, which **throws NPE on a null value**. That is precisely the failure `MessagingService:427-434` was changed to avoid, with the reason written into the code:

```java
// Null-tolerant on purpose: Collectors.toMap throws NPE on a null VALUE, where the per-row
// code this replaced degraded to "Unknown Player"/"Unknown Coach" via .orElse(...).
// ... a batched read path should not be the thing that turns a bad row into a 500 for the
// whole conversation list. (Code review, 3-layer run.)
```

Routing that site through the shared method reintroduces the exact NPE deferred-90's review just removed.

And the "six call sites" framing overstates the opportunity. The six `playerProfileRepository.*` calls in `MessagingService` are:

| line | call | id→name? |
|---|---|---|
| 76 | `findById(playerId)` | no |
| 131 | `findByUserId(callerUserId)` | no |
| 252 | `findByIdAndParentId(playerId, parentUserId)` | no |
| 278 | `findByIdAndParentId(playerId, parentUserId)` | no |
| 370 | `findByUserId(callerUserId)` | no |
| 430 | `findAllById(playerIds)` → name map | yes — and it must **not** be routed |

So the deliberate pass yields six "stays direct, because…" comments and zero changes. The cross-module-dependency justification is also moot — `MessagingService:5-6` already imports `platform.marketplace.repo`.

**Action:** either shrink AC19 to "add the one-line why-direct comments and make `getPlayerNamesByPlayerIds` null-tolerant so the two implementations converge," or drop it.

### M7 — AC16's parameterization is not expressible with an annotation

AC16 asks for `RegistrationOtpResendSupport` *"parameterized by the role rate-limit key + an OTP-email-event factory."* `@RateLimited(key = "parent_resend_otp", capacity = 3, duration = 30)` (`ParentRegistrationService:226`, `CoachRegistrationService:230`, `PlayerRegistrationService`) is an **annotation processed by an AOP aspect on the proxied bean method**. Its `key` is a compile-time constant; it cannot be a runtime parameter. If the dev moves the annotation into the shared collaborator, the three per-role IP buckets silently collapse into one shared bucket — a real security-relevant behaviour change that the "existing per-service ITs stay green" verification will not catch, because rate-limit bucketing is not asserted there.

The annotation must stay on the three service methods and only the body moves. AC16 should say so explicitly.

Also, the differences between the three are **three**, not two. Beyond `@RateLimited.key` and the OTP-email event type, the per-user cap key differs: `rateLimitingService.tryConsume(String.valueOf(userId), "parent_resend_otp_user", …)` vs `"coach_resend_otp_user"` (`ParentRegistrationService:232`, `CoachRegistrationService:236`). And `generateOtp()` / `hashOtp(otp, userId)` are private per-service methods — the extraction must confirm they are identical, not assume it.

### M8 — AC18's stated harm is false, and one of its two proposed guards breaks dev

AC18: *"A misconfigured non-dev deploy that sets the flag `true` must fail to boot, not silently seed **a known secret**."*

The secret is not known. `SecretService.createActiveSecret:40` → `createSecret:44` → `new Secret(version, busId)`, whose constructor calls `generateEncryptedVal()` (`Secret.java:77-87`): 256 bytes from `SECURE_RANDOM`, Base64-encoded, then Jasypt-encrypted. It is a cryptographically random secret.

The runner is also already idempotent (`JwtSecretBootstrapRunner:79-88` — skips if a secret exists) and the flag is set only in `application-dev.yaml:26-28`, a profile-scoped file, with `@Value("${app.bootstrap.jwt-secret.enabled:false}")` defaulting false everywhere else. So the residual risk is narrow: a real environment that has **no** provisioned secret *and* has the env var set would mint one silently instead of failing loudly. That is worth guarding, but the AC should say what the actual harm is (bypassing deliberate key provisioning / an unaudited key appearing in the DB), not "a known secret."

On the mechanism: the *"refuse to run when `spring.datasource.url` does not target `localhost`/`127.0.0.1`"* option will break the docker-compose dev stack, where the datasource host is a compose service name (see `deploy-1-2`). Prefer the explicit second-flag form, or better, gate on the presence of an `AdminBootstrapRunner`-style co-located property, and state the choice in the AC rather than leaving it as "dev's call" when one of the two options is known-broken.

### M9 — AC12's snippet regresses `logExpected`

The bug is real and correctly diagnosed. `ErrorLog.log` (`infrastructure/message/ErrorLog.java:68`) does `String.format(msgTemplate + " SUPPORT_ID: %s", helpCode)`, and `msgTemplate` reaches it as `ex.getMessage()` from ~20 `ApiAdvice` sites (`ApiAdvice.java:306, 351, 356, 365, 372, 379, 386, 393, 514, 520, 526, 532, 538, 547, …`). `"100% failed"` → `UnknownFormatConversionException`; `"%s"` → `MissingFormatArgumentException`. Both escape inside the handler. Parameterised logging is the right fix and additionally removes a second latent hazard — a `{}` inside an exception message currently consumes the `entries(ctx)` argument.

The problem is that AC12 gives one snippet and says *"(and the WARN variant)"*:

```java
log.error("{} SUPPORT_ID: {}", msgTemplate, helpCode, entries(ctx), throwable)
```

The current WARN call is `log.warn(fullMsg, entries(ctx))` — **no throwable, deliberately**. `logExpected`'s javadoc (`ErrorLog.java:41-52`) explains at length that `JWTAuthorizationFilter.writeUnauthorized` runs on an unauthenticated, unrate-limited path and that emitting a full stack trace for every tokenless request was the volume problem deferred-90 fixed. Applying the snippet verbatim re-adds it.

**Action:** AC12 should give the two call shapes separately and state that `logExpected` must not gain a throwable argument. Minor: the AC cites `ErrorLog.java:44`; on this branch the `String.format` is at `:68` (drift, covered by Task 0).

### M10 — the AC9 and AC10 sweeps are specified in a way that under- and over-shoots

**AC9 (de-DE).** The prescribed grep is `\bdu\b|\bdein|\bdich\b|\bdir\b|\bDeine?\b|Lass |schau `. It matches 44 lines in `src/frontend/src/i18n/de-DE/index.js`. It misses:
- **capitalised forms** — `\bdu\b` is case-sensitive, so `'Du musst mindestens 18 Jahre alt sein…'` (`:167`) and `'Du hast dieses Zeitfenster bereits angefragt'` (`:293`) are not matched; `\bDeine?\b` misses `Deinem`/`Deiner`/`Deines`;
- **the entire du-imperative class**, which is the bulk of the rewrite: `'Gib den 6-stelligen Code ein…'` (`:46`), `'Prüfe deine Verbindung…'` (`:100`), `'Verwalte die Coaching-Reise…'` (`:118`), `'Verfolge deine eigene Entwicklung…'` (`:153`), `'Finde einen Trainer…'` (`:144`), `'Biete vergünstigte Mehrfachpakete an'` (`:79`), `'…bitte wähle oben…'` (`:98`). None contain `du`/`dein` and none are matched.

The recorded "changed-string count" will therefore look far smaller than the real work, and a dev working the grep list will ship a bundle that is still half informal — the exact failure mode AC9 exists to prevent. Recommend: enumerate by section (onboarding / dashboard / reviews / video) and review every string in those sections, using the grep only as a starting point.

Backend `messages_de.properties` is already clean (0 informal matches), so AC9's frontend-only scope is right.

**AC10 (fr-FR).** The two named strings are real (`fr-FR/index.js:326` `'Session actualisee avec succes'`, `:543` `'Vous avez ete deconnecte avec succes.'`, `:546`). But *"a full sweep for missing `é`/`è`/`à`/`ê`/`ç` on French words"* is dangerous as a mechanical rule: `messages_fr.properties:119/123/128/132/150/154` contain `'Ce lien expire dans 24 heures.'` / `'Ce code expire dans 10 minutes.'` — where `expire` is the correct present-tense verb, **not** a missing accent. A regex sweep turns those into `expiré` and breaks correct French, while `MessageBundleParityTest` (values-only) cannot detect it.

**Action:** AC10 should require a per-string review with the rule stated explicitly (participle vs. present tense: `a expiré` vs `il expire`; `a été` vs `être`), and should list the specific strings changed in Dev Notes so a reviewer can check each. ~19 candidate lines in `fr-FR/index.js` plus 6 in `messages_fr.properties`.

### M11 — AC11's prescribed remedy does not fit the code

`CoachProfileService.getPublicProfile:331-…` issues nine independent round-trips before building the DTO:

```
coachProfileRepository.findById                                     :332
coachSpecialtyRepository.findByCoachId                              :338
coachAgeGroupRepository.findByCoachId                               :341
coachPricingRepository.findByCoachId                                :344
sessionPackRepository.findByCoachId                                 :346
coachAvailabilityWindowRepository.findByCoachIdOrderBy…             :351
coachReliabilityStrikeRepository.countByCoachIdAndCreatedAtAfter    :354
coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc        :357
coachCapabilityService.getActiveBadges                              :363
```

So the measurement AC11 gates on will trivially exceed its `> 4` threshold, and the "leave as-is, measured" branch is effectively dead. More importantly, **`@EntityGraph` cannot collapse these** — they are separate entities on separate repositories with no JPA association from `CoachProfile`; an entity graph has nothing to fetch. The real wins are different and cheaper:

- `:351` fetches every availability window purely to call `.isEmpty()` → `existsByCoachId`.
- `:357-361` fetches every media item and then `.limit(6)` in the stream → push the limit into the query (`Pageable` / `findTop6By…`).

Also, the "byte-for-byte unchanged response" parity IT is time-sensitive: `strikeCount` is computed against `OffsetDateTime.now().minusDays(STRIKE_WINDOW_DAYS)` (`:353-355`), so two invocations straddling a fixture's boundary can legitimately differ. Fix the clock in the IT or exclude that field.

### M12 — the post-commit money-loss path the codebase documents is covered by no AC

The story's motivating sentence is *"a post-commit Stripe refund … can no longer vanish with no retry."* But `PaymentPendingSweeper`'s javadoc names the real one:

> `BookingService.acceptAndInitiatePayment` commits the booking into PAYMENT_PENDING and **settlement happens in `PaymentLifecycleService`'s AFTER_COMMIT listeners, which have no retry and no dead-letter queue.** If the JVM dies in between, or the listener's own transaction fails outright, the booking rests there forever.

Those listeners are `PaymentLifecycleService.onBookingAccepted:137-139` and `onBatchBookingAccepted:236-238`, both `@Transactional(REQUIRES_NEW) @TransactionalEventListener(AFTER_COMMIT)` — the **charge** path, not the refund path. AC2 scopes itself to refunds and explicitly to listeners "following the same `REQUIRES_NEW` refund shape," so these fall outside every AC. The mitigation that exists (`PaymentPendingSweeper`) only *reports* them; AC5 is the only AC in the neighbourhood and it targets `CAPTURE_PENDING`, not the settle-failure case.

If the outbox is being built for exactly this class of problem, the charge listeners are the highest-value consumer and should either be in scope or be explicitly named in AC20's *"the `AFTER_COMMIT` reliability catalogue entries not covered (if any listener is deliberately left un-migrated, say which and why)"* residual — with the reason.

---

## Low-severity accuracy issues

**L1 — Dev Notes: `AccountDeletionCascadeListener` is mis-classified.** Dev Notes § "AFTER_COMMIT call-site inventory" states it *"is `@EventListener` not `@TransactionalEventListener`."* It is `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` (`AccountDeletionCascadeListener.java:32`). This matters because the whole AC13 diagnosis depends on which transaction context the method runs in.

**L2 — Dev Notes: the `platform.payment` listener inventory is incomplete.** It names three `CancellationRefundService` listeners; there are five (`:34, :56, :88, :113, :129` — `onBookingCancelledByParent` and `onPlayerNoShow` are omitted). It omits `PaymentLifecycleService`'s two entirely (`:139`, `:237`). AC2 says "enumerate them in Dev Notes"; the pre-filled list should not be wrong to begin with.

**L3 — Task 21: the pinned number is 5, not 37.** `IntegrationTestConventionTest.EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5` (`:70`, asserted at `:197`). "37" appears only in a javadoc line describing the historical problem (`:30`). A dev following Task 21 literally while adding AC11's `hibernate.generate_statistics` property will look for the wrong constant.

**L4 — AC14 fixes one of two identical teardown paths.** The diagnosis is correct: `computeTimeUntilExpiry`'s fast-teardown branch (`sessionManager.js:133`) is gated on `expiresAt === null`, so a sibling tab keeps rendering authenticated until `rint` expires. `rint` is `HttpOnly=false` (`JwtManagerImpl.java:244-248`, with the explicit comment *"HttpOnly=false so JS can read it"*) and `path=/` (`CookieUtil.addCookie:19`), so the client-side clear is feasible. But `App.vue:27-40 handleSessionExpired()` performs the identical teardown (`document.cookie = 'user=; …'` + `authStore.logout()` + `cleanup()`) and is **not** covered by AC14, so the same invisibility persists when the session ends by expiry rather than explicit logout. Add it to the AC.

**L5 — `ErrorLog.java:44` has drifted to `:68`.** Covered by Task 0, noted for completeness.

**L6 — `booking.payment_pending.unrecoverable` is presented as new in AC5(a).** It already exists as a tagged counter (`PaymentPendingSweeper:83`, `:187`). Adding a `CAPTURE_TIMEOUT` tag value is fine; the AC should say "add a tag value," not imply a new metric, so the dashboard/alert side is not duplicated.

---

## Things the story gets right (checked, no action)

- **AC12** — the `%` crash is real and reachable; `String.format(msgTemplate + " SUPPORT_ID: %s", helpCode)` at `ErrorLog.java:68` with `ex.getMessage()` from ~20 sites. Parameterised logging is the correct fix (see M9 for the WARN caveat only).
- **AC15's core claim** — `…/resend-otp**` really does match `…/resend-otp-admin`. Empirically confirmed. Only the recommended remedy is wrong.
- **AC5's cancel-block premise** — `BookingService.java:743-746` genuinely throws `409 booking.paymentInProgress` when a `CAPTURE_PENDING` row exists, so the parent's cancel is blocked. That part is accurate.
- **AC14's diagnosis** — accurate, and the cookie is client-writable. Only the coverage is incomplete (L4).
- **AC1's insistence on the `ChunkProcessor`-as-separate-bean shape** — correct and important; `PendingBlobDeletionChunkProcessor`'s javadoc (`:17-28`) documents exactly why the self-invoked form was rejected. Keeping that warning in the AC is good practice.
- **AC9's parity guard** — `MessageBundleParityTest` is values-agnostic, so a value-only rewrite keeps it green as the AC assumes.
- **The scope-discipline section and AC20's residuals list** — well-formed; AC5 Task 0 being the only scope-expansion valve is the right control.

---

## Recommended disposition

| AC | Recommendation |
|----|----------------|
| AC1 | Amend — add backoff/`available_at`, a park state, a per-domain fairness contract, payload versioning; correct "off the request path" |
| AC2 | **Rewrite** — wrong domain (credits, not Stripe), missing idempotency prerequisites, impossible enqueue point |
| AC3 | **Rewrite** — target `MailManager.sendEmailSync`; persist `sendId`; move the enqueue to the publisher |
| AC4 | Amend — drop the outbox branch, keep reconciliation; fix the signature and the IT's forcing exception |
| AC5 | **Hold for Task 0** — (a) as written reverses a documented invariant; Task 0 must read `PaymentPendingSweeper` + runbook first |
| AC6 | **Rewrite** — fix at the hydration/projection layer; fix the column name; make the IT feasible against `admin_alerts_type_check` |
| AC7 | Amend — cover all five blind spots; replace the git-diff backport rule with a committed manifest |
| AC8 | **Reduce to documentation-only**; add V91 to the audit list |
| AC9 | Amend — replace the grep-driven surface with a section-driven review |
| AC10 | Amend — per-string review with an explicit participle-vs-present rule; list changes in Dev Notes |
| AC11 | Amend — drop `@EntityGraph`; name the two real wins; fix the parity IT's clock |
| AC12 | Amend — separate ERROR and WARN call shapes; no throwable on `logExpected` |
| AC13 | **Rewrite** — keep Task 1, delete the prescribed fix, add the "do not add `@Transactional`" constraint |
| AC14 | Amend — extend to `App.vue handleSessionExpired` |
| AC15 | **Rewrite** — exact patterns, not `/**`; correct the semantics claim; cover the `JWTAuthorizationFilter` consumer |
| AC16 | Amend — `@RateLimited` stays on the three methods; list the third difference |
| AC17 | **Rewrite** — dedicated config exception, not a catch-all remap |
| AC18 | Amend — correct the stated harm; drop the localhost-datasource option |
| AC19 | Amend or drop — as written it is a no-op or a regression |
| AC20 | Amend — add the runbook update (if AC5 changes `CAPTURE_PENDING`) and the `PaymentLifecycleService` charge-listener residual |
