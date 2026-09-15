# Story: Email/Notification Robustness & Quota Completeness

**Story Key:** `skillars-deferred-113-email-notification-robustness-quota-completeness`
**Epic:** Deferred Work
**Priority:** High (1 critical rate-limit bug + 1 quota feature gap + 3 email infrastructure hardening items + 1 documentation-only cutover gate)
**Status:** done
**Created:** 2026-09-15
**Last Updated:** 2026-09-16
**Review Status:** Corrected after story-review audit — removed 6 ACs already shipped by deferred-110/111, closed 1 CRITICAL AC (AC9) as not-reproducible. **Second pass:** the AC4/AC5 this revision had added (recipient-address masking, `envelope_entity_recipients` index) turned out to be *another* pair of already-shipped items — `EmailPiiSanitizer` (deferred-111 AC11) and the composite PK in `V138__baseline_schema.sql` already cover them — removed and replaced with the two genuinely-open items deferred-work.md's ses-1-2/ses-1-4 sections actually describe. **Third pass:** a further sweep for additional in-scope items found nothing new in code (all other `Map.of`/outbox/async candidates checked out as either unrelated or already correct) — added one documentation-only AC (AC6) for the one item that held up, ses-1-1's still-open "no non-production environment exercises the SES path" note.

---

## User Story

As a **Platform Engineer/Developer**, I want to **harden email delivery against mid-loop rate-limiting failures, defend against unknown send outcomes in critical admin alerts, close a player video quota tier gap, close two remaining email-classification/data hardening gaps, and put a documented safeguard in front of the first-ever production SES cutover** so that **multi-recipient envelopes are never partially delivered with retries, critical moderation alerts are never silently lost, players with SEMI_PRO/PRO subscription tiers receive correct video quotas, permanent send failures can't silently misclassify as retryable if an adapter's wrapping ever drifts, a null OTP/verification token can't silently serialize into a delivered email, and the SES transport's first real-world exercise is a verified, planned event rather than an unverified surprise in production traffic**.

---

## Acceptance Criteria

### AC1: Mid-loop rate-limit recipient duplication (CRITICAL)

**Given** an envelope with multiple recipients hits a rate-limit rejection mid-loop (e.g., at recipient k of n),
**When** the envelope is retried,
**Then**:

- Per-recipient delivery tracking prevents duplicating recipients 1..k-1 on the retry
- Rate-limited sends after recipient k on the first attempt are not duplicated
- Latent case: `SendMailListener` accepts arbitrary-length `userIds` lists; today only single-id calls exist but any multi-recipient producer activates this bug

**Failure Scenario:**
- Envelope with 5 recipients sent to SES-via-rate-limiter
- Rate-limit rejection occurs at recipient 3
- Retry restarts loop at recipient 1, re-sends to 1 and 2 (duplicates), gets rejected at 3 again, never reaches 4–5
- Iterate 6 times: MAX_RETRY_ATTEMPTS exhausted, marked ATTEMPTS_EXHAUSTED (retry=false), recipient 4–5 never delivered

**Verified by:**
- `MailManagerRateLimitIT.multiRecipientRateLimitMidLoop_allRecipientsDeliveredExactlyOnce` demonstrates per-recipient tracking
- Mutation test: remove per-recipient tracking → fails with duplicate sends
- No single-recipient regression: `SendMailListenerIT` confirmed unchanged

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:119-136` (sendEmailSync recipient loop)]

---

### AC2: VideoModerationEmailListener persisted==null outcome (defense-in-depth)

**Given** `VideoModerationEmailListener.sendAdminAlertSync()` sends an admin alert via `MailManager` with `REQUIRES_NEW` transaction scope,
**When** the REQUIRES_NEW transaction commits but its result is not visible on immediate read-back (persisted==null),
**Then**:

- An unknown send outcome is distinguishable from a confirmed success
- Admin alert is retained (not silently dropped) if outcome is unknown
- Choose one fix option based on risk assessment:
  - **Option A:** Bounded retry with exponential backoff (re-read with short wait, up to 3 attempts)
  - **Option B:** Throw `IllegalStateException` to retain the outbox row for manual recovery

**Context:**
- `VideoModerationAdminAlertOutboxHandler.handle()` treats normal return as success, deletes durable row
- If a persisted==null outcome returns normally, the row is deleted despite unknown actual send status
- Prior investigation (`deferred-110 AC5`) found root cause unconfirmed under this codebase's transaction semantics, but precautionary hardening is justified regardless

**Verified by:**
- `VideoModerationAdminAlertEnvelopeIT` includes case for persisted==null outcome
- Case passes post-fix (row retained or bounded-retry succeeds)
- Mutation: remove the guard → row deleted on unknown outcome

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:102-107`]

---

### AC3: QuotaConfigService.resolveTierKey player-tier mapping

**Given** a player with subscription tier SEMI_PRO or PRO is queried for video quota,
**When** `QuotaConfigService.resolveTierKey(playerId)` is called,
**Then**:

- The player's subscription tier is resolved to the corresponding quota segment (semiPro or pro, not athlete)
- Video quota rows (`video.quota.semiPro.*` and `video.quota.pro.*` seeded in `V139__baseline_seed_data.sql:171-172,175-176`) are reachable
- `ConfigBounds` coverage is complete (AC10 of `deferred-109` already added semiPro/pro to the list)

**Current Behavior:**
- `resolveTierKey` has a switch over `CoachSubscriptionTier {SCOUT, INSTRUCTOR, ACADEMY}` for UUID `ownerId`
- For non-UUID (player) `ownerId`, it returns bare `"athlete"` fallback
- Result: all players receive ATHLETE quota (2 GiB storage / 10 GiB bandwidth)
- Unreachable: SEMI_PRO and PRO quota rows (4 GiB / 25 GiB; 7 GiB / 30 GiB) are never used

**Failure Scenario:**
- Player with `PlayerSubscriptionTierBilling.SEMI_PRO` uploads 3 GiB of video
- Calls `QuotaService.getStorageQuotaBytes(playerId)` → calls `resolveTierKey(playerId)` → returns "athlete" → 2 GiB
- Storage check returns false even though SEMI_PRO quota is 4 GiB
- Upload rejected despite remaining quota

**Fix:**
1. Resolve player's subscription tier via `PaymentPlayerSubscriptionRepository.findByPlayerId(Long)` (read-only lookup, handles not-found case). Do **not** use `SubscriptionService.getPlayerSubscription(parentUserId, playerId)` — it requires a `parentUserId` this method's only input (`ownerId`) doesn't carry, and its actual no-ownership-check lookup (`findOrCreatePlayerSubscription`) is `private` and **writes** a new subscription row on a miss (a side effect a quota *check* must not have). `PlayerSubscriptionTierBillingRepository`, named in earlier drafts of this AC, does not exist.
2. `resolveTierKey`'s existing `catch (IllegalArgumentException)` block parses `ownerId` as a UUID first (coach path); the non-UUID player path already has the raw `String ownerId` in hand — parse it as `Long.parseLong(ownerId)` for the repository call, and fall back to `"athlete"` (not a thrown exception) if that parse itself fails, matching this method's existing fail-open posture for every other unrecognised shape.
3. Map subscription tier to quota tier: SEMI_PRO → "semiPro", PRO → "pro", else (including not-found / any other tier) → "athlete"

**Verified by:**
- `QuotaConfigServicePlayerTierIT` creates players with SEMI_PRO/PRO tiers, confirms correct quota segments are used
- Mutation: remove player mapping → returns "athlete" for all players (test fails)
- `VideoQuotaReservationIT` confirms SEMI_PRO/PRO quotas are enforceable end-to-end

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:47-62` (resolveTierKey)]

---

### AC4: `isRetryable` depth-robustness against adapter wrap-depth drift

**Given** `MailManager.isRetryable` classifies a caught exception by walking a **fixed** three-level cause chain (direct / cause / cause-of-cause) against `NON_REPAIRABLE_ERRORS`,
**When** an `OutboundEmailSender` adapter (`SmtpErrorClassifier`, `SesErrorClassifier`) wraps a permanent failure into that chain,
**Then**:

- The classification is proven correct against the **real** adapter, not a mock that hand-constructs the exception at whatever depth is convenient
- The fixed-depth walk is exercised at every depth an adapter actually produces today, so a future adapter change that adds one more wrapper "for context" is caught by a failing test instead of silently pushing the real type out of range

**Context:**
- This is the actual, still-open content of `requirements/ses-email-consolidation.md` §7.2 item 13 ("adapter-wrap-depth guard") — deferred out of `ses-1-2` at the time, and miscited in an earlier draft of this story against a nonexistent `MailSenderProvider` config-nesting concern that doesn't exist in this codebase.
- Not the same bug the earlier-drafted AC9 in this story claimed: that draft's specific reproduction (permanent error silently reclassified as retryable through the *real, container-configured* `CircuitBreakerFactory`) does not reproduce — bytecode-traced through the exact pinned `resilience4j`/`spring-cloud-circuitbreaker-resilience4j` versions this project resolves, and confirmed by two already-passing tests (`MailManagerResilienceTest.isRetryable_permanentTransportException_persistsRetryFalse`, `VideoModerationEmailListenerTest$RealMailManagerMappingAC122.permanentFailure_producesNonRetryableRow_andListenerReleases`) that already exercise a real `CircuitBreakerFactory` + `TimeLimiter`. `MailManager`'s cause-preserving fallback closure has been unchanged since the initial commit. This AC is narrower and still real: prove the adapters themselves keep feeding `isRetryable` a classifiable exception, which today is asserted only by mocks.

**Verified by:**
- A case driving a permanent failure through the real `SmtpEmailSender` + `SmtpErrorClassifier` and asserting `isRetryable`/the persisted `EnvelopeEntity.retry` is `false`
- A parallel case through the real `SesEmailSender` + `SesErrorClassifier`
- Mutation: add one extra wrapping layer inside either classifier → the new test(s) fail, proving they actually bind to depth, not just to type

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:283-298` (`isRetryable`), `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java`, `src/main/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifier.java`, `requirements/ses-email-consolidation.md:1072-1082`]

---

### AC5: `Map.of`→`HashMap` null-token guard in the registration listeners

**Given** `CoachRegistrationEmailListener`, `ParentRegistrationEmailListener` and `PlayerRegistrationEmailListener` each build their `SendMailEvent`/`Envelope` data map as a bare `new HashMap<>()` (replacing an earlier `Map.of(...)` during `ses-1.4`),
**When** any future producer supplies a null OTP code or verification-URL token,
**Then**:

- The null is handled explicitly (fail fast with a clear error, or an equivalent guard) instead of silently serializing as `{"otpCode":null}`, surviving the outbox round trip, and being delivered with `EnvelopeEntity.status = SENT`
- `Map.of(...)`'s original implicit NPE-on-null-value guard — lost when it was replaced with `HashMap` to allow other legitimately-nullable values — is restored for the token-bearing keys specifically, not reintroduced generally (a blanket revert to `Map.of` would NPE on legitimately-nullable fields these listeners also carry)

**Context:**
- Not currently reachable: `generateOtp()` always returns a non-null `String`, and `verifyUrl` is built by string concatenation — but nothing in the type system or these listeners prevents a future change from introducing a nullable value here, and the failure mode (a token-less "verify your account" email, silently marked delivered) is worth guarding against directly rather than relying on today's callers staying well-behaved.

**Verified by:**
- A unit test constructing each listener's data map with a null token value, asserting the chosen guard fires (exception, or an explicit non-null placeholder) rather than a silent `null` reaching `MailManager`
- Existing registration-email ITs (`RegistrationEmailDurabilityIT`) unaffected — no producer supplies null today

**Ledger:** [`src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/CoachRegistrationEmailListener.java`, `ParentRegistrationEmailListener.java`, `PlayerRegistrationEmailListener.java`]

---

### AC6: Pre-production SES cutover safeguard (documentation-only)

**Given** `dev`/`uat`/`test` all run `app.email.transport: log`, `smtp` is deliberately rejected until Phase 2, and `DevSesEmailService` (the old way to exercise SES from a dev box) was deleted during `ses-1.1`,
**When** the platform first switches `app.email.transport: ses` in production,
**Then**:

- That switch is the **first time ever** `SesEmailSender` runs against real AWS — real credentials, real region, real verified-domain state, a 3s attempt timeout, zero retries — with no prior non-production exercise of the path
- A documented pre-production checklist exists so this is a planned, verified cutover rather than a surprise discovered in production traffic

**Context:**
- This is deliberate, not a defect: `ses-1.1`'s own phase plan calls for `smtp` in non-prod and `ses` only in prod, and the item was originally filed only "so it is not rediscovered at the Phase 5 cutover." Not code-fixable — there is no bug to patch, only a gap in pre-flight verification coverage.
- Matches this codebase's existing precedent for this exact shape of item: `skillars-deferred-100`'s cutover added a `## Pre-production release gate: queued webhook events` section to `docs/deployment/runbook.md` rather than a code change, for an analogous "verify before the first real production deploy" gap.

**Fix:**
- Add a `## Pre-production release gate: SES cutover` section to `docs/deployment/runbook.md` (or extend the existing gate section if one exists by then), covering at minimum: verify the target SES account/domain is out of the sandbox (`SesHealthIndicator`'s `productionAccessEnabled` detail, readable via the `notification` actuator health group once `transport=ses` is live) and `ses:GetAccount`/`ses:SendEmail` IAM permissions are attached before flipping the property; send one real test email to an internal address immediately after the flip and confirm delivery + `SesHealthIndicator` reports `UP` before considering the cutover complete; roll back to `smtp`/`log` if either check fails.
- Optionally: a manually-triggered (not scheduled) admin/ops endpoint or CLI step that sends one probe email through the live `SesEmailSender` on demand, so the checklist's "send one real test email" step doesn't require improvising one through a real user flow. Left to the implementer's judgement — the runbook section is the actual AC requirement, this is a nice-to-have.

**Verified by:**
- `docs/deployment/runbook.md` contains the new gate section
- Manual review confirms it names concrete, checkable steps (not just "verify SES works")

**Ledger:** [`docs/deployment/runbook.md`, `src/main/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicator.java`, `src/main/resources/application-prod.yaml`]

---

## Tasks/Subtasks

- [ ] **Task 0 — Pre-implementation verification of AC4/AC5 against current HEAD** (dev-story activation check, 2026-09-15)
  - [x] Verify AC4 (`isRetryable` depth-robustness): `AdapterWrapDepthTest` (added by `skillars-deferred-111` AC3) already drives a real `SmtpEmailSender`+`SmtpErrorClassifier` permanent failure and a real `SesEmailSender`+`SesErrorClassifier` permanent failure end-to-end and asserts `EnvelopeEntity.isRetry()==false` for both — exactly AC4's "Verified by" list. Ran `AdapterWrapDepthTest`: 2/2 pass. **No code or test change needed — AC4 is already fully satisfied on HEAD.**
  - [x] Verify AC5 (null-token guard): `CoachRegistrationEmailListener`/`ParentRegistrationEmailListener`/`PlayerRegistrationEmailListener` already call `Objects.requireNonNull(event.verifyUrl(), ...)` / `Objects.requireNonNull(event.otp(), ...)` outside the try/catch (added by `skillars-deferred-111` AC6), and each has a unit test asserting `NullPointerException` is thrown. Ran all three `*RegistrationEmailListenerTest` classes: 28/28 pass. **No code or test change needed — AC5 is already fully satisfied on HEAD.**
  - [x] Document this finding in Dev Agent Record and proceed to the four genuinely-open ACs (AC1, AC2, AC3, AC6) below — same pattern this story's own review history already established for the six ACs removed in prior passes.

- [x] **Task 1 — AC3: QuotaConfigService player-tier mapping** (Implementation Order Recommendation #1 — isolated, low risk)
  - [x] Add `PaymentPlayerSubscriptionRepository` dependency to `QuotaConfigService`
  - [x] In `resolveTierKey`, after the UUID (coach) parse fails, attempt `Long.parseLong(ownerId)`; on success, look up `PaymentPlayerSubscriptionRepository.findByPlayerId(Long)` and map tier: SEMI_PRO → "semiPro", PRO → "pro", else (incl. not-found/ATHLETE) → "athlete"
  - [x] If the `Long.parseLong` itself fails, fall back to "athlete" (fail-open, matching existing posture) rather than throwing
  - [x] Write `QuotaConfigServicePlayerTierIT`: players with SEMI_PRO/PRO tiers resolve to semiPro/pro quota segments; unmapped/not-found players resolve to athlete
  - [x] Mutation check (reasoned, not re-run live): each tier's IT assertion targets a distinct, real seeded quota amount (athlete 2 GiB vs semiPro 4 GiB vs pro 7 GiB) — removing the mapping branch collapses all three to "athlete" and the semiPro/pro assertions fail by construction
  - [x] `QuotaConfigServiceTest` (unit) + `QuotaConfigServicePlayerTierIT` (real DB) both green; no separate `VideoQuotaReservationIT` exists in this codebase (story ledger reference was aspirational/miscited — confirmed by search) — `QuotaServiceConcurrencyIT` (the closest real end-to-end quota IT) mocks `QuotaConfigService` and is unaffected

- [x] **Task 2 — AC6: Pre-production SES cutover safeguard (documentation-only)** (Implementation Order Recommendation #3 — no code dependencies)
  - [x] Add a `## Pre-production release gate: SES cutover` section to `docs/deployment/runbook.md`, modeled on the existing `## Pre-production release gate: queued webhook events` section
  - [x] Cover: verify SES account/domain is out of sandbox (`SesHealthIndicator`'s `productionAccessEnabled` detail via the `notification` actuator health group), verify `ses:GetAccount`/`ses:SendEmail` IAM permissions before flipping `app.email.transport: ses`; send one real test email to an internal address immediately after the flip and confirm delivery + `SesHealthIndicator` reports `UP`; roll back to `smtp`/`log` if either check fails
  - [x] Manual review: section names four concrete, checkable steps plus an explicit rollback trigger — not just "verify SES works"

- [x] **Task 3 — AC1: Mid-loop rate-limit recipient duplication (CRITICAL)** (Implementation Order Recommendation #5 — core change, high mutation bar)
  - [x] Add per-recipient delivery tracking to `MailManager.sendEmailSync`'s recipient loop so a rate-limit rejection mid-loop does not re-send to already-delivered recipients on retry (new `RecipientEntity.delivered` column, `V140__envelope_entity_recipients_delivered_flag.sql`)
  - [x] Ensure recipients rate-limited after the failure point on the first attempt are not duplicated and are eventually reached
  - [x] Write `MailManagerRateLimitIT.multiRecipientRateLimitMidLoop_allRecipientsDeliveredExactlyOnce` with a real rate-limiter (production-shaped `RetryTemplate`), multi-recipient envelope
  - [x] Mutation check: reverted the `pendingRecipients` loop filter back to the full recipient list, confirmed the IT fails (`recipient 1 sent exactly once` — expected 1, was 2), then restored and reconfirmed green
  - [x] No `SendMailListenerIT` exists in this codebase (story ledger reference was miscited — confirmed by search); added `MailManagerRateLimitIT.singleRecipientEnvelope_unaffectedByPerRecipientTracking` instead as the single-recipient regression check — green

- [x] **Task 4 — AC2: VideoModerationEmailListener persisted==null outcome (defense-in-depth)** (Implementation Order Recommendation #6)
  - [x] Chose **Option B** (throw `IllegalStateException` to retain the outbox row) over Option A (bounded retry with backoff): the transaction-isolation analysis already in the code (and `skillars-deferred-110` AC5's own finding) says a just-committed row *should* already be visible on this read, so a bounded retry would mostly just delay reaching the same unresolved outcome for a race nothing has ever reproduced — Option B reuses the identical, already-proven retain-the-row pattern this same method uses two branches below for a confirmed retryable FAILED send, with no new retry/backoff surface to get wrong
  - [x] Implemented in `VideoModerationEmailListener.sendAdminAlertSync`'s `persisted == null` branch — diagnostic WARN log kept (unchanged), now followed by the throw instead of a plain `return`
  - [x] Added `VideoModerationAdminAlertEnvelopeIT.unknownOutcome_throwsToRetainOutboxRow_despiteRealSendSucceeding`: a real send commits a genuine SENT row while the listener under test is wired with a separate, blinded `EnvelopeEntityRepository` that always returns `null` — proves the throw retains the row even though the real send succeeded
  - [x] Mutation check: reverted the throw to a plain `return`, confirmed the new IT fails (`Expecting code to raise a throwable`), then restored and reconfirmed green
  - [x] Updated the two existing `VideoModerationEmailListenerTest` unit cases (`noPersistedEnvelope_warnsNotYetVisible`, `noPersistedEnvelope_warnCarriesTransactionContext`) from asserting no-throw to asserting the new `IllegalStateException` — both green

- [x] **Task 5 — Final validation**
  - [x] Ran all new/modified targeted test classes together (notification module: 104/104; quota module: 30/30; `MigrationConventionLintTest`: 13/13; `AdapterWrapDepthTest` + all three `*RegistrationEmailListenerTest`: re-confirmed green) — zero regressions
  - [x] Updated Verification Checklist items in this story to checked
  - [x] Updated File List, Change Log, Dev Agent Record completion notes
  - [x] Updated `_bmad-output/implementation-artifacts/deferred-work.md`: closed the 4 genuinely-fixed ledger items this story addresses, and corrected 2 items the same-day "full-file re-audit" entry had incorrectly re-opened (adapter-wrap-depth guard, `Map.of`→`HashMap` guard — both already shipped by `skillars-deferred-111`, confirmed by running their existing tests)
  - [x] Mark story Status → review

### Review Findings (2026-09-15)

**Patch findings — action items:**

- [x] [Review][Patch] **[FALSE POSITIVE — dismissed 2026-09-15, no code change]** ~~Null tier in subscription not handled~~ — `QuotaConfigService.java:90-94`. The claim: `PaymentPlayerSubscription.getTier()` returning null would make `PlayerSubscriptionTierBilling.valueOf(null)` throw an NPE "outside the catch block." This does not hold: the call chain is `.map(PaymentPlayerSubscription::getTier).map(this::playerTierToQuotaSegment).orElse("athlete")` — `Optional.map()` short-circuits to `Optional.empty()` when the mapper returns null, per its own contract ("if the result is non-null, return an Optional describing the result; otherwise return an empty Optional"). A null tier therefore never reaches `playerTierToQuotaSegment`/`valueOf` at all; it falls straight through to `.orElse("athlete")`. Verified empirically with a standalone repro (`Optional.of("x").map(v -> (String) null).map(riskyFn)` → no NPE, resolves to the `orElse` branch) rather than relying on the Javadoc alone. Additionally, the premise itself is weaker than stated: `PaymentPlayerSubscription.tier` is `@Column(nullable = false)` with a Java-side default of `"ATHLETE"` — it is enforced, both at the entity default and the DB constraint, not merely "per schema default, but not enforced" as the finding claims. No defensive check needed; not applied.

- [x] [Review][Patch] **[FALSE POSITIVE (as stated) — dismissed 2026-09-15, no code change]** ~~Migration V140 doesn't backfill delivered=true for old SENT rows~~ — `V140__envelope_entity_recipients_delivered_flag.sql`. The premise (`delivered=false` by default for pre-existing rows) is true, but the risk scenario built on it doesn't correspond to any live code path: it requires something to reset a `SENT` row back to `FAILED`/`retry=true`, and nothing in this codebase does that. The only component that re-triggers a send against an existing `sendId`, `ResendEmailService.resendEmail()`, was traced back to the initial `init` commit and has **zero callers anywhere** (no controller, no scheduler, no test) — it is unreferenced dead code. Separately, the proposed backfill (`... WHERE status='SENT'`) targets the wrong rows even if the scenario were live: the one genuine, narrow gap this fix cannot retroactively close is an envelope mid-loop-rate-limited *under the pre-fix code* landing as a `FAILED`/`retry=true` row with no per-recipient history at the moment this deploy lands — `SENT` rows are not the ones at risk. That gap is a one-time, bounded cost scoped to whatever sits in the retry queue at the exact deploy moment, and is not backfillable regardless (the pre-fix code never recorded which specific recipients succeeded before an old failure, so there is no data to backfill from). No migration change made.

**Deferred findings — noted, not actionable now:**

- [x] [Review][Defer] Concurrent retry from multiple instances — `MailManager.java:106,195-203`. Mitigated by `@SchedulerLock` cluster-wide serialization, but not enforced at method level. A direct call to `sendEmailSync` from multiple threads with same sendId is unsupported but not prevented. Acceptable since scheduler is the only real caller.

- [x] [Review][Defer] Throw-on-null is defensive, not curative — `VideoModerationEmailListener.java:143-145`. The fix retains the outbox row but doesn't diagnose why `persisted==null` occurs. Defensive approach correct per AC2 Option B decision; root-cause diagnosis remains unaddressed.

- [x] [Review][Defer] Missing subscription row silently downgrades tier — `QuotaConfigService.java:90-94`. Players with missing subscription rows fall back to "athlete" quota with no logging. Operational concern; fallback is intentional and matches fail-open posture.

- [x] [Review][Defer] No transactional consistency tier/quota lookup — `QuotaConfigService.java:82-94`. Subscription tier can be updated between lookup and quota enforcement; acceptable check-then-use pattern, not a code bug.

- [x] [Review][Defer] Runbook checklist manual, not code-enforced — `docs/deployment/runbook.md:644-665`. Pre-production SES checks are textual procedures with no deployment blocker. AC6 design: runbook documentation only; enforcement is procedural.

- [x] [Review][Defer] No automated health check integration — `docs/deployment/runbook.md:657-662`. Health check documented but optional; cached response doesn't prove current transport is live. Acceptable given manual pre-production review gate precedent (`skillars-deferred-100`).

## Dev Notes

- **Genuine open items:** 6 ACs addressing a confirmed critical rate-limit bug (AC1), defense-in-depth for a rare unknown-outcome edge case (AC2), a quota feature gap (AC3), two email-classification/data hardening items (AC4, AC5), and one documentation-only pre-production safeguard (AC6).
- **Story corrections post-review (first pass):** 6 ACs removed (already shipped by deferred-110 AC4/AC5/AC11 and deferred-111); 1 CRITICAL AC (AC9 circuit-breaker classification) closed as not-reproducible after bytecode-level trace + existing tests confirmed passing.
- **Story corrections post-review (second pass):** the AC4/AC5 introduced by the first pass (recipient-address exception masking, `envelope_entity_recipients` index/constraint) were themselves already shipped by `deferred-111` AC11 (`EmailPiiSanitizer`) and the `V138` baseline's composite PK — replaced with the two items deferred-work.md's `ses-1-2`/`ses-1-4` sections actually still carry as open (`isRetryable` adapter-wrap-depth robustness, the registration-listener null-token guard). A third candidate from the same sections — `RegistrationEmailDurabilityIT`'s `findAll()` test helper — was considered and **not** added: it's confined to one already-justified test helper (`skillars-deferred-111` AC7's own comment calls it "the one `findAll()` this class cannot avoid"), the production `EmailRetryScheduler.fetchFailedEmails()` path is already properly scoped (`WHERE retry=true AND status=FAILED ... LIMIT 10 FOR UPDATE SKIP LOCKED`, not a full scan), and deferred-work.md's own bullet overstates the production scheduler's exposure — not enough live gap to justify a story AC.
- **Story corrections post-review (third pass):** searched for further additions beyond the deferred-work.md ses-1-* sections already mined — `AccountManagementFacade`'s `Map.of(...)` calls (REST exception payloads, not email data), `TwoFactorLoginService`'s OTP `Map.of(...)` (already fail-loud, unlike AC5's HashMap sites), `NotificationEmailOutboxHandler`'s unsaved-looking entity mutation (runs inside `OutboxRowProcessor`'s active transaction, Hibernate dirty-checking covers it — not a bug), and two codebase TODOs (`AsyncConfig`, `AlertEvaluationService`, both vague/unrelated) all checked out clean. One item held up and was added as AC6: ses-1-1's "no non-production environment exercises the SES path" — not code-fixable (the phase plan is deliberate), so scoped as a documentation-only runbook gate, matching this codebase's existing `## Pre-production release gate: ...` precedent (`skillars-deferred-100`'s webhook-event gate).
- **Risk areas:**
  - AC1 (rate-limit loop): Changes core `MailManager` send loop; high mutation test coverage required
  - AC2 (persisted==null): Touches only `VideoModerationEmailListener`; lower blast radius but choice between Option A/B needs risk assessment
  - AC3 (player quota): Isolated to `QuotaConfigService`; no regressions expected
  - AC4 (isRetryable depth-robustness): Test-only change (no production code touched unless the tests reveal a real gap); low risk
  - AC5 (null-token guard): Isolated to three listener classes; low risk, currently unreachable in production
  - AC6 (SES cutover gate): Documentation-only; zero code risk
- **Testing approach:**
  - AC1: Integration test with real rate-limiter, multi-recipient envelope, verify per-recipient tracking
  - AC2: Integration test with `REQUIRES_NEW` transaction boundary + immediate read-back
  - AC3: Integration test with actual tier/quota rows; verify all player tiers map correctly
  - AC4: Integration tests driving real `SmtpErrorClassifier`/`SesErrorClassifier`, not mocks
  - AC5: Unit tests per listener with a null token value
  - AC6: Manual review of the new runbook section for concrete, checkable steps
- **No mvn verify locally** per project convention; CI is the gate. Run targeted suites: email-ITs + quota ITs.

---

## Implementation Order Recommendation

1. **AC3 (QuotaConfigService)** — isolated, low risk, quick win
2. **AC5 (null-token guard)** — isolated, low risk
3. **AC6 (SES cutover gate)** — documentation-only, no code dependencies
4. **AC4 (isRetryable depth-robustness)** — test-only, low risk, but needs both real adapters wired
5. **AC1 (mid-loop rate-limit)** — core change, needs careful per-recipient tracking; high mutation test bar
6. **AC2 (VideoModerationEmailListener)** — choose Option A/B, implement bounded-retry or exception-throw

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (lines 119-136 recipient loop, 283-298 `isRetryable`)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (lines 83-145, especially persisted==null branch)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` (lines 47-62)
- `src/main/java/com/softropic/skillars/platform/payment/contract/PlayerSubscriptionTierBilling.java` (enum definition)
- `src/main/java/com/softropic/skillars/platform/payment/repo/PaymentPlayerSubscriptionRepository.java` (`findByPlayerId(Long)` — read-only lookup)
- `src/main/java/com/softropic/skillars/infrastructure/email/smtp/SmtpErrorClassifier.java`, `src/main/java/com/softropic/skillars/infrastructure/ses/SesErrorClassifier.java` (real adapter classifiers for AC4)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/{Coach,Parent,Player}RegistrationEmailListener.java` (AC5)
- `src/main/resources/db/migration/V138__baseline_schema.sql` (envelope_entity schema)
- `src/main/resources/db/migration/V139__baseline_seed_data.sql` (lines 171-172, 175-176 for quota tier rows)
- `docs/deployment/runbook.md` (existing `## Pre-production release gate: ...` sections, for AC6's format precedent)
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicator.java`, `src/main/resources/application-{dev,uat,prod}.yaml` (AC6 — current transport-per-profile split)

---

## Verification Checklist

- [x] AC1: Per-recipient tracking prevents duplicate sends on rate-limit retry; all recipients eventually delivered exactly once — `MailManagerRateLimitIT`, 2/2 green, mutation-verified
- [x] AC2: VideoModerationEmailListener handles persisted==null with chosen option (Option B: throw); outbox row retained on unknown outcome — `VideoModerationAdminAlertEnvelopeIT`, 3/3 green, mutation-verified
- [x] AC3: Player SEMI_PRO/PRO tiers map to semiPro/pro quota segments; athlete fallback works for unmapped players and unparseable ownerIds — `QuotaConfigServicePlayerTierIT` (4/4) + `QuotaConfigServiceTest` (7/7) green
- [x] AC4: A permanent failure through the real `SmtpErrorClassifier`/`SesErrorClassifier` still classifies `isRetry=false`; an added wrapper layer fails the new test(s) — already satisfied by `AdapterWrapDepthTest` (`skillars-deferred-111` AC3); verified against HEAD, no change needed
- [x] AC5: A null OTP/verify-URL token in any of the three registration listeners is guarded, not silently delivered — already satisfied by `Objects.requireNonNull` guards (`skillars-deferred-111` AC6); verified against HEAD, no change needed
- [x] AC6: `docs/deployment/runbook.md` carries a concrete, checkable SES pre-production cutover gate — `## Pre-production release gate: SES cutover` section added
- [x] No regressions in existing email/quota tests — notification module 104/104, quota module 30/30, `MigrationConventionLintTest` 13/13, all green
- [x] Single-recipient envelope sends unchanged (AC1 regression check) — `MailManagerRateLimitIT.singleRecipientEnvelope_unaffectedByPerRecipientTracking` green

---

## References

- Deferred-work.md: `VideoModerationEmailListener` persisted==null bullet (`code review of skillars-deferred-109` section), mid-loop rate-limit bullet (`code review of ses-1-3-...` section), `QuotaConfigService.resolveTierKey` bullet (`skillars-deferred-109 story creation` section), adapter-wrap-depth guard bullet (`code review of ses-1-2-...` section, restored 2026-09-15), `Map.of`→`HashMap` null-token bullet (`code review of ses-1-4-...` section, restored 2026-09-15), "no non-production environment exercises the SES path" bullet (`code review of ses-1-1-...` section, restored 2026-09-15), `## Last audit: 2026-09-15 (full-file re-audit + premature-prune correction)`
- Code review audits: ses-1-1, ses-1-2, ses-1-3, ses-1-4, deferred-100 (runbook gate precedent), deferred-109, deferred-110, deferred-111
- Story-review audit: 2026-09-15 `story-review.md` (revealed 6 shipped ACs, 1 non-reproducible CRITICAL AC — first pass); this story's own second revision (AC4/AC5 were themselves already-shipped duplicates, replaced with the actually-open ses-1-2/ses-1-4 items); third revision (added AC6 after a further sweep found nothing else in-scope)

---

## Dev Agent Record

### Debug Log

- **2026-09-15, pre-implementation check:** before starting Task 1, re-verified AC4 and AC5 against HEAD per this story's own established pattern of catching already-shipped ACs. Found both already fully implemented and tested:
  - AC4: `src/test/java/com/softropic/skillars/infrastructure/email/smtp/AdapterWrapDepthTest.java` (added by `skillars-deferred-111` AC3, commit `4a3f218d`) already drives a real `SmtpEmailSender`+`SmtpErrorClassifier` RCPT-TO-550 failure and a real `SesEmailSender`+`SesErrorClassifier` `BadRequestException` failure end-to-end and asserts `EnvelopeEntity.isRetry()==false` for both. Ran it: `Tests run: 2, Failures: 0, Errors: 0`.
  - AC5: `CoachRegistrationEmailListener`/`ParentRegistrationEmailListener`/`PlayerRegistrationEmailListener` already guard `event.verifyUrl()`/`event.otp()` with `Objects.requireNonNull(...)` outside the try/catch (added by `skillars-deferred-111` AC6, same commit `4a3f218d`), each covered by an existing unit test asserting `NullPointerException`. Ran all three `*RegistrationEmailListenerTest` classes: `Tests run: 28, Failures: 0, Errors: 0` (10+9+9).
  - Conclusion: no code or test changes needed for AC4/AC5. This is the same "already shipped" pattern this story's own three prior review passes already identified and removed for six other ACs — the story-review audit's AC4/AC5 verification simply wasn't re-run after the third revision renamed/replaced them. Proceeding directly to the four genuinely-open ACs: AC1, AC2, AC3, AC6.

### Completion Notes

All 6 ACs satisfied. AC4 and AC5 required no code changes (already shipped by `skillars-deferred-111`
AC3/AC6 respectively — see Debug Log above). The four genuinely-open ACs (AC1, AC2, AC3, AC6) were
implemented and verified with real, container-backed tests where applicable:

- **AC3 (QuotaConfigService player-tier mapping):** `resolveTierKey` now resolves a non-UUID
  `ownerId` via `PaymentPlayerSubscriptionRepository.findByPlayerId`, mapping the player's stored
  `SEMI_PRO`/`PRO`/`ATHLETE` tier string to the `semiPro`/`pro`/`athlete` quota segment, failing open
  to `athlete` for every unrecognised shape (unparsable id, no subscription row, unknown tier
  string) — matching the method's existing posture. New `QuotaConfigServicePlayerTierIT` proves all
  three tiers resolve to their real `V139`-seeded quota amounts (athlete 2 GiB, semiPro 4 GiB, pro
  7 GiB storage) against a real Postgres; `QuotaConfigServiceTest` extended with 4 new unit cases for
  the mapping and fail-open paths.
- **AC6 (SES cutover safeguard, documentation-only):** added a
  `## Pre-production release gate: SES cutover` section to `docs/deployment/runbook.md`, modeled on
  the existing webhook-event gate — four concrete checks (SES out of sandbox via
  `SesHealthIndicator`'s `productionAccessEnabled`, IAM permissions, a real test-email send + health
  check immediately after cutover, explicit rollback trigger). No code change.
- **AC1 (mid-loop rate-limit recipient duplication, CRITICAL):** added per-recipient delivery
  tracking. New `RecipientEntity.delivered` boolean column
  (`V140__envelope_entity_recipients_delivered_flag.sql`, additive/defaulted per the expand-contract
  convention). `MailManager.sendEmailSync` now looks up any existing `EnvelopeEntity` for the
  `sendId` at the top of the method, filters the recipient loop down to not-yet-delivered
  recipients, and persists the updated delivered set (monotonically growing across attempts)
  regardless of whether this attempt ends in success or failure. New
  `MailManagerRateLimitIT.multiRecipientRateLimitMidLoop_allRecipientsDeliveredExactlyOnce` reproduces
  a 5-recipient envelope rate-limited at recipient 3 on the first attempt, proves recipients 1–2 are
  each sent to exactly once across two attempts (not duplicated), recipient 3 is sent to twice
  (rejected, then accepted), and 4–5 are reached only on the second attempt — mutation-verified
  (reverting the recipient-loop filter turns this test red). A second new test case confirms
  single-recipient sends are unchanged.
- **AC2 (VideoModerationEmailListener persisted==null, defense-in-depth):** chose **Option B**
  (throw `IllegalStateException` to retain the outbox row) over Option A (bounded retry) — see Tasks
  above for the risk-assessment rationale. The `persisted == null` branch now throws after its
  existing diagnostic WARN log, reusing the identical retain-the-row pattern already used for a
  confirmed retryable FAILED send two branches below. New
  `VideoModerationAdminAlertEnvelopeIT.unknownOutcome_throwsToRetainOutboxRow_despiteRealSendSucceeding`
  reproduces a real committed SENT row the listener under test is deliberately blinded to (via a
  separate, mocked `EnvelopeEntityRepository` wired only into the listener, not into `MailManager`)
  and proves the throw retains the row — mutation-verified. The two existing
  `VideoModerationEmailListenerTest` unit cases for this branch were updated from asserting no-throw
  to asserting the new exception.

**Ledger hygiene:** `_bmad-output/implementation-artifacts/deferred-work.md` updated — closed the 4
ledger items this story's genuinely-open ACs addressed, and corrected 2 items (adapter-wrap-depth
guard, `Map.of`→`HashMap` null-token guard) that the same-day "full-file re-audit" entry had
incorrectly re-opened; both were already shipped by `skillars-deferred-111`, confirmed by running
their existing tests (`AdapterWrapDepthTest` 2/2, all three `*RegistrationEmailListenerTest` 28/28).

**Validation:** targeted suites only, per project convention (no local `mvn verify`; CI is the
gate). Notification module: 104/104. Quota module: 30/30. `MigrationConventionLintTest`: 13/13 (new
`V140` migration passes lint). All green, zero regressions.

**Post-review (2026-09-15):** both Patch action items from the Review Findings above were verified
against actual code/behavior rather than accepted at face value, and both were dismissed as false
positives — see the struck-through entries under "Review Findings" for the full rationale (in short:
`Optional.map()`'s documented null-short-circuit semantics make the claimed NPE unreachable, and no
code path in this repo ever resets a `SENT` envelope row back to `FAILED`, so the migration-backfill
scenario has no live trigger and its proposed fix targets the wrong rows regardless). No code
changes made in response.

## File List

**New:**
- `src/main/resources/db/migration/V140__envelope_entity_recipients_delivered_flag.sql`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerRateLimitIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaConfigServicePlayerTierIT.java`
- `src/main/resources/mails/videoModerationAdminAlert.html` (post-PR CI fix — was missing entirely; see Change Log 2026-09-16)
- `src/main/resources/mails/videoModerationOwnerFlagged.html` (post-PR CI fix — same missing-template gap, sibling template)

**Modified:**
- `src/main/java/com/softropic/skillars/platform/notification/repo/RecipientEntity.java` (AC1 — new `delivered` field)
- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (AC1 — per-recipient delivery tracking)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (AC2 — throw on unknown outcome)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` (AC3 — player-tier mapping)
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaConfigServiceTest.java` (AC3 — new unit cases)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationAdminAlertEnvelopeIT.java` (AC2 — new persisted==null case)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListenerTest.java` (AC2 — updated two existing unit cases for the new throw behaviour)
- `docs/deployment/runbook.md` (AC6 — new SES cutover gate section)
- `_bmad-output/implementation-artifacts/deferred-work.md` (ledger closures + correction)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status tracking)
- `_bmad-output/implementation-artifacts/skillars-deferred-113-email-notification-robustness-quota-completeness.md` (this story file)
- `src/test/java/com/softropic/skillars/platform/outbox/ModerationOutboxIT.java` (post-PR CI fix — forked context, `TestMailManager` → real `EnvelopeEntityRepository` read-back)
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java` (post-PR CI fix — `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` 6→7)

## Change Log

- 2026-09-15: Story picked up for dev via `/bmad-dev-story`. Added Tasks/Subtasks breakdown.
- 2026-09-15: Verified AC4/AC5 already satisfied on HEAD (no code change) — see Dev Agent Record Debug Log.
- 2026-09-15: Implemented AC3 (QuotaConfigService player-tier mapping) — `QuotaConfigServicePlayerTierIT` + extended `QuotaConfigServiceTest`, both green.
- 2026-09-15: Implemented AC6 (SES cutover documentation gate) — `docs/deployment/runbook.md` new section.
- 2026-09-15: Implemented AC1 (mid-loop rate-limit recipient duplication, CRITICAL) — new `V140` migration, `RecipientEntity.delivered`, `MailManager` per-recipient tracking, `MailManagerRateLimitIT`, mutation-verified.
- 2026-09-15: Implemented AC2 (VideoModerationEmailListener persisted==null defense-in-depth, Option B) — throw on unknown outcome, new `VideoModerationAdminAlertEnvelopeIT` case, updated 2 existing unit tests, mutation-verified.
- 2026-09-15: Updated `deferred-work.md` — closed 4 items, corrected 2 same-day ledger errors.
- 2026-09-15: All targeted suites green (notification 104/104, quota 30/30, migration lint 13/13). Status → review.
- 2026-09-15: Code review completed, added to story as "Review Findings" (2 Patch, 6 Defer). Both Patch items verified against code and dismissed as false positives, with rationale, per explicit instruction — no code change made. Deferred items reviewed for false positives too; all 6 confirmed as accurate, already-acknowledged design tradeoffs, left as-is.
- 2026-09-16: Status → done.
- 2026-09-16: CI build failure post-PR: `ModerationOutboxIT.adminAlert_roundTripsThroughTheOutbox` broke under AC2's new throw. Root cause traced to two separate pre-existing gaps AC2 exposed rather than caused: (1) `TestMailManager` (the universal test double, `enable.test.mail=true` by default) persists nothing, so the outbox-driven, fully-autowired `VideoModerationEmailListener` in that IT could only ever see `persisted == null` — fixed by forking a dedicated Spring context for that class (`@TestPropertySource(enable.test.mail=false)`, `IntegrationTestConventionTest.EXPECTED_TEST_PROPERTY_SOURCE_COUNT` 6→7), mirroring `RegistrationEmailDurabilityIT`/`MailManagerDuplicateSendIdIT`'s existing precedent, and rewriting the two `TestMailManager`-based assertions to read the real `EnvelopeEntity` row back instead. (2) Once routed through the real `MailManager`, the send itself failed: `mails/videoModerationAdminAlert.html` (and the sibling `mails/videoModerationOwnerFlagged.html`) had never existed — `VIDEO_MODERATION_ADMIN_ALERT`/`VIDEO_MODERATION_OWNER_FLAGGED` emails have apparently never been sendable via the real template-rendering path in any environment, in any prior story, because no test before this one exercised real `MailService` + real Thymeleaf rendering for either template (`VideoModerationAdminAlertEnvelopeIT` mocks `MailService` entirely). Added both missing HTML templates. All targeted suites re-verified green after both fixes (notification module + video module + outbox module + `IntegrationTestConventionTest`, plus the full CI build).
