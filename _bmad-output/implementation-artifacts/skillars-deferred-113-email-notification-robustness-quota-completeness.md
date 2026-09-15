# Story: Email/Notification Robustness & Quota Completeness

**Story Key:** `skillars-deferred-113-email-notification-robustness-quota-completeness`
**Epic:** Deferred Work
**Priority:** High (1 critical rate-limit bug + 3 email infrastructure gaps + 1 quota feature gap)
**Status:** ready-for-dev (REVISED post-review)
**Created:** 2026-09-15
**Last Updated:** 2026-09-15
**Review Status:** Corrected after story-review audit — removed 6 ACs already shipped by deferred-110/111, closed 1 CRITICAL AC (AC9) as not-reproducible, restructured to keep only 5 genuine open items

---

## User Story

As a **Platform Engineer/Developer**, I want to **harden email delivery against mid-loop rate-limiting failures, defend against unknown send outcomes in critical admin alerts, close a player video quota tier gap, and complete email infrastructure hardening** so that **multi-recipient envelopes are never partially delivered with retries, critical moderation alerts are never silently lost, players with SEMI_PRO/PRO subscription tiers receive correct video quotas, and edge-case email sending failures are handled robustly**.

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
1. Resolve player's subscription tier via `PaymentPlayerSubscriptionRepository.findByPlayerId(Long)` (read-only lookup, handles not-found case)
2. In `resolveTierKey`, after the coach UUID switch, handle player case:
   - Map subscription tier to quota tier: SEMI_PRO → "semiPro", PRO → "pro", else → "athlete"
3. Else branch already handles not-found correctly (defaults to "athlete")

**Verified by:**
- `QuotaConfigServicePlayerTierIT` creates players with SEMI_PRO/PRO tiers, confirms correct quota segments are used
- Mutation: remove player mapping → returns "athlete" for all players (test fails)
- `VideoQuotaReservationIT` confirms SEMI_PRO/PRO quotas are enforceable end-to-end

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:47-62` (resolveTierKey)]

---

### AC4: Recipient-address exception masking in logs

**Given** an email envelope with multiple recipients encounters an invalid recipient address during send,
**When** the `AddressException` or related recipient-validation error is logged,
**Then**:

- Recipient email addresses are redacted/masked in error output (consistent with OTP masking from `deferred-110`)
- Asymmetry between exception-chain logging and data-map masking is resolved (both use sanitized strings)
- No raw email addresses leak into logs even if error occurs during recipient iteration

**Verified by:**
- `MailManagerRecipientMaskingTest` confirms no recipient addresses in error logs
- Existing `RegistrationEmailDurabilityIT` logs show `recipients=[REDACTED]`

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (exception logging sites for recipient validation)]

---

### AC5: `envelope_entity_recipients` index & constraint hardening

**Given** `envelope_entity_recipients` table stores recipient email addresses for outbox durable messages,
**When** queries or constraint checks occur,
**Then**:

- A primary key or unique constraint exists to prevent duplicate recipient entries in a single envelope
- Query performance on recipient lookups/updates is optimized via index
- `V138__baseline_schema.sql` or a new migration pins the schema (AC9 of `deferred-109` already handled `envelope_entity` itself)

**Verified by:**
- Schema inspection confirms PK or unique constraint exists
- `EnvelopeEntityRecipientsIT` confirms duplicate-recipient insert is rejected
- Query explain plan shows indexed scan for `findRecipientsByEnvelopeId`

**Ledger:** [`src/main/resources/db/migration/V138__baseline_schema.sql` (envelope_entity_recipients definition)]

---

## Dev Notes

- **Genuine open items:** 5 ACs addressing a confirmed critical rate-limit bug (AC1), defense-in-depth for a rare unknown-outcome edge case (AC2), a quota feature gap (AC3), and two email infrastructure hardening items (AC4, AC5).
- **Story corrections post-review:** 6 ACs removed (already shipped by deferred-110 AC4/AC5/AC11 and deferred-111); 1 CRITICAL AC (AC9 circuit-breaker classification) closed as not-reproducible after bytecode-level trace + existing tests confirmed passing.
- **Risk areas:**
  - AC1 (rate-limit loop): Changes core `MailManager` send loop; high mutation test coverage required
  - AC2 (persisted==null): Touches only `VideoModerationEmailListener`; lower blast radius but choice between Option A/B needs risk assessment
  - AC3 (player quota): Isolated to `QuotaConfigService`; no regressions expected
  - AC4, AC5 (logging/schema): Isolated hardening; low risk
- **Testing approach:**
  - AC1: Integration test with real rate-limiter, multi-recipient envelope, verify per-recipient tracking
  - AC2: Integration test with `REQUIRES_NEW` transaction boundary + immediate read-back
  - AC3: Integration test with actual tier/quota rows; verify all player tiers map correctly
  - AC4: Unit + IT for masking consistency
  - AC5: Schema inspection + constraint validation IT
- **No mvn verify locally** per project convention; CI is the gate. Run targeted suites: email-ITs + quota ITs.

---

## Implementation Order Recommendation

1. **AC3 (QuotaConfigService)** — isolated, low risk, quick win; foundation for AC5 if needed
2. **AC4, AC5 (logging/schema)** — isolated fixes, low risk
3. **AC1 (mid-loop rate-limit)** — core change, needs careful per-recipient tracking; high mutation test bar
4. **AC2 (VideoModerationEmailListener)** — choose Option A/B, implement bounded-retry or exception-throw

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (lines 119-136, exception logging sites)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java` (lines 83-145, especially persisted==null branch)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` (lines 47-62)
- `src/main/java/com/softropic/skillars/payment/contract/PlayerSubscriptionTierBilling.java` (enum definition)
- `src/main/java/com/softropic/skillars/payment/repository/PaymentPlayerSubscriptionRepository.java` (read-only lookup)
- `src/main/resources/db/migration/V138__baseline_schema.sql` (envelope_entity schema + envelope_entity_recipients definition)
- `src/main/resources/db/migration/V139__baseline_seed_data.sql` (lines 171-172, 175-176 for quota tier rows)

---

## Verification Checklist

- [ ] AC1: Per-recipient tracking prevents duplicate sends on rate-limit retry; all recipients eventually delivered exactly once
- [ ] AC2: VideoModerationEmailListener handles persisted==null with chosen option (A/B); outbox row retained on unknown outcome
- [ ] AC3: Player SEMI_PRO/PRO tiers map to semiPro/pro quota segments; athlete fallback works for unmapped players
- [ ] AC4: Recipient addresses masked in error logs; masking asymmetry resolved
- [ ] AC5: `envelope_entity_recipients` has PK/unique constraint; schema is pinned in migration
- [ ] No regressions in existing email/quota tests
- [ ] Single-recipient envelope sends unchanged (AC1 regression check)

---

## References

- Deferred-work.md: lines 2006-2012 (VideoModerationEmailListener), 2013-2033 (MailManager.isRetryable), 2037 (mid-loop rate-limit), 2119-2123 (QuotaConfigService), 2064-2098 (2026-09-14 audit findings)
- Code review audits: ses-1-3, ses-1-4, deferred-109, deferred-110
- Story-review audit: 2026-09-15 story-review.md (revealed 6 shipped ACs, 1 non-reproducible CRITICAL AC)
