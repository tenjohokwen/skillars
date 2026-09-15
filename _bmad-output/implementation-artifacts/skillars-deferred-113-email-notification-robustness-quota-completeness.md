# Story: Email/Notification Robustness & Quota Completeness

**Story Key:** `skillars-deferred-113-email-notification-robustness-quota-completeness`
**Epic:** Deferred Work
**Priority:** High (7 email infrastructure bugs + 2 retry classification bugs + 1 quota gap)
**Status:** ready-for-dev
**Created:** 2026-09-15
**Last Updated:** 2026-09-15

---

## User Story

As a **Platform Engineer/Developer**, I want to **close seven email infrastructure bugs, two critical retry/circuit-breaker misclassifications, and one quota feature gap** so that **email delivery becomes robust against mid-loop rate-limiting and admin alerts are never silently dropped, multi-tier video quotas are correctly enforced, and the SES/SMTP transport infrastructure is complete and hardened**.

---

## Acceptance Criteria

### AC1: ses-1-2 Adapter-wrap-depth guard

**Given** SMTP provider adapters are instantiated,
**When** multiple layers of provider configuration nesting occur,
**Then**:

- A defensive depth-guarding mechanism prevents adapter wrapping beyond expected nesting levels
- Nested provider configuration chains validate structure and fail clearly if depth assumptions are violated

**Verified by:**
- `SmtpPropertiesValidator` or `SmtpTransportBootIT` confirms nesting depth

**Ledger:** [`platform/notification/infrastructure/adapter/*MailSenderProvider.java`]

---

### AC2: ses-1-3 DOWN-health-caching TTL

**Given** SES health indicator returns DOWN status,
**When** the health response is cached and a subsequent check arrives within the TTL,
**Then**:

- The cached DOWN status is returned (confirmed no surprise state change)
- The TTL is configurable and appropriate for operational visibility

**Verified by:**
- `SesHealthIndicatorTest` confirms TTL behavior under DOWN state
- Cache is invalidated after timeout

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/infrastructure/health/SesHealthIndicator.java`]

---

### AC3: ses-1-3 Mid-loop rate-limit recipient duplication (CRITICAL)

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

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:74` (sendEmailSync recipient loop)]

---

### AC4: ses-1-3 Rate-limit WARN-log-level

**Given** a rate-limit rejection occurs,
**When** it is logged,
**Then**:

- Log level is consistent across all rate-limit rejection paths
- Distinguishable from actual transport failures in log aggregation

**Verified by:**
- Grep confirms unified log level across all `EmailTransportRateLimitedException` sites
- Log level matches operational alerting configuration

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java`]

---

### AC5: ses-1-4 Map.of→HashMap null-token guard

**Given** an email template contains a null-valued token,
**When** it is serialized via `Map.of(...)`,
**Then**:

- No `NullPointerException` or silent null-skipping occurs
- A defensive `HashMap` replacement handles null values correctly
- Token rendering either includes the null safely or explicitly notes it missing

**Verified by:**
- `EmailTemplateNullTokenIT` renders templates with null token values
- Mutation: revert to `Map.of` → NullPointerException

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:50-65` (Map construction in sendEmailSync)]

---

### AC6: ses-1-4 AC7 log-masking-bypass (OTP/verification tokens)

**Given** registration/OTP templates (COACH_EMAIL_VERIFY, COACH_OTP, PARENT_EMAIL_VERIFY, PARENT_OTP, PLAYER_EMAIL_VERIFY, PLAYER_OTP, SEND_OTP) are logged,
**When** error logging occurs,
**Then**:

- OTP codes and verification URLs are redacted from log output
- Redaction applies to both SLF4J dedicated Throwable arguments AND the data={} argument
- Asymmetry fixed: exception cause-chain is NOT unmasked while data argument IS redacted

**Failure Scenario:**
- Pre-fix: logger line passes exception as trailing argument (bypasses masking) while data={} is sanitized
- Post-fix: both use sanitized strings, symmetric masking

**Verified by:**
- `MailManagerLoggingMaskingTest` confirms OTP/verification tokens are absent from both exception and data log output
- Mutation: remove masking from exception handling → finds raw OTP code in logs
- `EmailRetrySchedulerIT` confirms no tokens leak on retry

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:110-120` (loggableData + logger.error calls)]

---

### AC7: ses-1-4 RegistrationEmailDurabilityIT global-state

**Given** registration email tests run concurrently or in sequence,
**When** test fixtures create shared outbox/template data,
**Then**:

- No global state leaks between test methods
- Each test cleans up its own outbox rows
- Template seed data is not mutated by individual tests

**Verified by:**
- `RegistrationEmailDurabilityIT` runs 6 cases with per-test cleanup
- Runs pass independently and in any order (no coupling)
- `@AfterEach` confirms rows cleaned before next test

**Ledger:** [`src/test/java/com/softropic/skillars/platform/notification/.../RegistrationEmailDurabilityIT.java`]

---

### AC8: VideoModerationEmailListener persisted==null outcome

**Given** `VideoModerationEmailListener.sendAdminAlertSync()` sends an admin alert via `MailManager`,
**When** the REQUIRES_NEW transaction commits but its result is not visible on immediate read-back (persisted==null),
**Then**:

- An unknown send outcome is distinguishable from a confirmed success or failure
- Admin alert is retained (not silently dropped) if outcome is unknown
- Two options for fix (choose one during dev based on risk assessment):
  - **Option A:** Bounded retry with exponential backoff (re-read with short wait)
  - **Option B:** Throw `IllegalStateException` to retain the outbox row for manual recovery

**Failure Scenario:**
- Current: unknown outcome (REQUIRES_NEW commit not visible) → log WARN + return normally
- Consequence: `ModerationAdminAlertOutboxHandler` treats normal return as success, deletes durable row
- Result: permanently-failed moderation audit alert silently lost

**Verified by:**
- `VideoModerationAdminAlertEnvelopeIT` includes case for persisted==null outcome
- Case fails pre-fix (row deleted despite unknown outcome), passes post-fix (row retained or bounded-retry succeeds)
- Mutation: remove the guard → alert row deleted on unknown outcome

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:102-107`]

**Dev Note:** This is a decision point. Document the choice (Option A vs B) in the implementation and link it to the corresponding runbook/monitoring guidance.

---

### AC9: MailManager.isRetryable circuit-breaker misclassification (CRITICAL)

**Given** `MailManager.sendEmailSync()` sends via a circuit breaker + retry template,
**When** a permanently undeliverable error (e.g., `AddressException` from `NON_REPAIRABLE_ERRORS`) triggers the circuit breaker,
**Then**:

- The original exception is preserved in the exception chain
- `isRetryable()` correctly classifies it as permanent (not retryable)
- **Current bug:** Circuit breaker wraps the cause in `RuntimeException("Email sending failed via Circuit Breaker")`, so the original exception is lost
- Result: `isRetryable()` sees no `NON_REPAIRABLE_ERRORS` member and classifies as retryable
- Consequence: permanently-failed envelopes retry forever → [OUTBOX_STUCK] alerts + infinite retry slots consumed

**Failure Scenario:**
1. `AddressException` thrown from `MailService` (non-repairable, should not retry)
2. Circuit breaker catches it, wraps it: `RuntimeException → TimeLimiter TimeoutException` (chain: cause is lost)
3. `isRetryable()` scans 3 depths, finds no `AddressException`, returns true
4. `toEnvelopeEntity()` stamps envelope FAILED/retry=true
5. `EmailRetryScheduler` retries forever; row occupies claim slot until [OUTBOX_STUCK]
6. Actual delivery status unknown but retry loop never stops

**Fix Options (choose during dev):**
- **Option A:** Have circuit breaker's fallback preserve the original throwable rather than replacing it
- **Option B:** Classify from the exception captured inside the retry loop (before circuit breaker wrapping) instead of from the CB layer's wrapper
- **Option C:** Hybrid: enrich the CB exception chain so the original cause survives

**Verified by:**
- `MailManagerCircuitBreakerClassificationIT` reproduces bug: throw permanent error → verify isRetry=true (fail pre-fix, pass post-fix)
- `VideoModerationAdminAlertEnvelopeIT.permanentFailure_NOT_retried` confirms permanent errors are not stamped retry=true
- Mutation: remove original cause preservation → test fails, confirms guard is live
- Real container test: `ComponentConfig` + real `Resilience4JCircuitBreakerFactory` confirms bug and fix

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:136-150` (isRetryable), `src/main/java/com/softropic/skillars/platform/notification/infrastructure/config/ComponentConfig.java:44-49` (CircuitBreakerFactory config)]

**Dev Note:** This is a decision point. Document the choice (Option A/B/C) and how it propagates to other circuit-breaker use sites if needed.

---

### AC10: QuotaConfigService.resolveTierKey player-tier gap

**Given** a player with subscription tier SEMI_PRO or PRO is queried for video quota,
**When** `QuotaConfigService.resolveTierKey(playerId)` is called,
**Then**:

- The player's subscription tier is resolved to the corresponding quota segment (semiPro or pro, not athlete)
- Video quota rows (`video.quota.semiPro.*` and `video.quota.pro.*` seeded in V53) are reachable
- `ConfigBounds` coverage is complete (AC10 of deferred-109 already added semiPro/pro to the list, so this story just wires the mapping)

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
1. Add `PlayerSubscriptionTierBilling` enum mapping (currently dead) or use the string literals (`"SEMI_PRO"` / `"PRO"`) that `SubscriptionService` uses
2. In `resolveTierKey`, after the coach UUID switch, handle player case:
   - Resolve player's subscription tier (via `PlayerSubscriptionTierBillingRepository` or `SubscriptionService` lookup)
   - Map to quota tier: SEMI_PRO → "semiPro", PRO → "pro", else → "athlete"
3. Include product decision: are player video quotas a shipped concern yet? (If not, AC10 stance is acceptable and this is a placeholder.)

**Verified by:**
- `QuotaConfigServicePlayerTierIT` creates players with SEMI_PRO/PRO tiers, confirms correct quota segments are used
- Mutation: remove player mapping → returns "athlete" for all players (test fails)
- `VideoQuotaReservationIT` confirms SEMI_PRO/PRO quotas are enforceable end-to-end

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:resolveTierKey`]

**Dev Note:** This requires a product decision on whether player video quotas are a shipped feature (i.e., players should be able to buy higher tiers and receive higher quotas). If not yet a product feature, this AC can be deferred to a future quota-completeness story. For now, implement the mapping so the quota tiers are reachable if/when needed.

---

## Dev Notes

- **Item grouping:** Seven email infrastructure bugs (AC1–AC7) are independent; two critical circuit-breaker/retry bugs (AC8–AC9) are related but distinct; one quota gap (AC10) is separate. All ten are low-interaction and can be worked in parallel.
- **Risk areas:**
  - AC3 (rate-limit loop): Changes core `MailManager` send loop; high mutation test coverage required
  - AC9 (circuit-breaker classification): Impacts retry logic across all email; needs real `Resilience4JCircuitBreakerFactory` test
  - AC8 (persisted==null): Touches only `VideoModerationEmailListener`; lower blast radius
  - AC10 (player quota): Isolated to `QuotaConfigService`; no regressions expected
- **Testing approach:**
  - Email bugs: mix of unit tests (masking, log levels) and integration tests (rate-limit loop, circuit breaker with real Resilience4J)
  - Quota bug: integration test with actual tier/quota rows
- **No mvn verify required** per project convention; CI is the gate. Run targeted suites locally: email-related ITs + QuotaConfigServiceIT.

---

## Implementation Order Recommendation

1. **AC10 (QuotaConfigService)** — isolated, low risk, quick win
2. **AC1, AC2, AC4 (health/logging)** — isolated config/logic fixes
3. **AC5, AC6, AC7 (template/test cleanup)** — isolated fixes
4. **AC3 (mid-loop rate-limit)** — core change, needs careful testing
5. **AC8, AC9 (retry logic)** — decision-dependent, coordinate with dev

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (lines 74, 110-120, 136-150)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (lines 102-107)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:resolveTierKey`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/config/ComponentConfig.java` (lines 44-49)
- `V53__video_quota_system.sql` (lines 37-38, 44-45 for SEMI_PRO/PRO seed rows)

---

## Verification Checklist

- [ ] All 10 ACs implemented and mutation-tested
- [ ] Email infrastructure IT suite green (MailManager, SES health, rate-limit loop, circuit breaker)
- [ ] VideoModerationEmailListener persisted==null case covered
- [ ] QuotaConfigService player tier mapping end-to-end verified
- [ ] No regressions in existing email/quota tests
- [ ] Log masking confirmed (no OTP/verification tokens in logs)
- [ ] Per-recipient tracking prevents duplicate sends on rate-limit retry
- [ ] Circuit-breaker classification correctly preserves original exception chain

---

## References

- Deferred-work.md: lines 2006-2012, 2013-2033, 2037, 2090-2093, 2119-2123
- Code review audits: ses-1-2 through ses-1-4, skillars-deferred-109, skillars-deferred-110
