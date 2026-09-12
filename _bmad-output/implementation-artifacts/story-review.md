# Story Review: Ses-1.2 — Move SMTP Behind the Port and Contain It

**Audit Date:** 2026-09-11  
**Reviewer Role:** Senior Dev  
**Status:** Ready for development with noted clarifications

---

## Summary

The story is **well-scoped and detailed**. It correctly identifies the key structural changes (SMTP encapsulation, EmailContentRenderer extraction, MailManager/MailService refactoring) and enforces invariants via architecture tests. No critical blockers found. Minor clarifications needed on a few semantic edges.

---

## Verified Assumptions (No Issues)

✓ **Phase 1 completion state:** EmailTransport enum exists with {SES, SMTP, LOG}; OutboundEmailSender port is in place; the three registration listeners call it directly; SES is fully behind the port.

✓ **MailManager.NON_REPAIRABLE_ERRORS exact list:** Confirmed to be exactly four types:
  - `org.springframework.mail.MailParseException`
  - `org.springframework.mail.MailPreparationException`
  - `jakarta.mail.internet.AddressException`
  - `jakarta.mail.internet.ParseException`
  
SmtpErrorClassifier in AC2 correctly mirrors this list.

✓ **SenderProvider single caller/implementation:** Grep confirms `SenderProvider` interface is implemented only by `MailSenderProvider` and called only by `MailService`. Safe to delete and fold into SmtpEmailSender.

✓ **Boot-provided JavaMailSender never injected:** No `JavaMailSender` references in src/main/java. Safe to remove spring.mail autoconfiguration block in AC7 without risking runtime injection failures.

✓ **MailManager.isRetryable scope:** Method walks exactly 3 exception levels (direct, cause, causeOfCause) per documented comments. SmtpErrorClassifier exceptions will land at the depth this walker expects.

✓ **EmailTemplate.NONE behavior preserved:** Current MailService.sendEmailFromTemplate (lines 63–67) sends NONE templates as plaintext: calls `sendEmail(..., isHtml=false)`. AC1's requirement that EmailContentRenderer produce `(null htmlBody, plaintext textBody)` for NONE correctly mirrors this.

✓ **MailService.sendEmail MimeMessage construction is the only place:** No other code constructs MimeMessage or calls SenderProvider. AC2's fold-into-SmtpEmailSender is clean.

---

## Semantic/Correctness Issues

### 1. **Correlation ID for retries: Each attempt gets a new UUID** ⚠ CLARIFY INTENT

**Location:** AC3, lines 45–46  
**Finding:** 
```java
// AC3 says:
correlationId for the OutboundEmailRequest is...a per-call UUID.randomUUID()
```

MailManager retries via `retryTemplate.execute(...)` which calls `mailService.sendEmailFromTemplate()` multiple times on transient failure. Each retry generates a new UUID.

**Why this matters:**
- **For registration listeners (Phase 1):** Each direct call to OutboundEmailSender already gets UUID.randomUUID(). One call = one UUID. No retries at listener level. ✓ Correct.
- **For MailManager's booking/session-pack mail (Phase 2):** Retry #1 gets UUID-A. Retry #2 (same envelope, transient failure) gets UUID-B. Logs and correlation will show two separate sends, not retries of one.

**Current behavior in Phase 1:** Does MailManager currently retry? Reading MailManager code (lines 81–96), yes—it calls retryTemplate.execute() inside circuitBreaker.run(), looping `for (Recipient recipient : recipients)` and retrying each per recipient inside the retry template.

**Is this correct?**  
The story says (AC3): *"Persisting `sendId` as the correlation id is a Phase 4 concern once `OutboundEmailResult.messageId()` is written onto `EnvelopeEntity`"* — Phase 4 will persist which messageId was sent for an envelope. Until then, each retry attempt surfaces as a distinct correlation ID in logs.

**Verdict:** **NOT A BUG.** This is an accepted limitation until Phase 4. However, the story should clarify why this is acceptable (one-liner: "Retry attempts are logged separately; Phase 4 correlates them via persisted envelope.messageId"). Add a Dev Note or in-code comment to MailService explaining the UUID-per-call design and that the correlation ID is not envelope-scope until Phase 4.

---

### 2. **SmtpHealthIndicator conditional property path must be updated** ⚠ IMPLEMENTATION DETAIL

**Location:** AC2, line 34  
**Finding:**
The story says: *"its existing `@ConditionalOnProperty(prefix = "email", name = "providerConfigs[0].host")` guard moves with it, updated only to the new `app.email.smtp` prefix"*

When renamed, the prefix should be `"app.email.smtp"` and name should reference the new binding path: `"provider-configs[0].host"` (kebab-case as per YAML convention).

**Current SmtpHealthProperties binding:** AC2 says it becomes `@ConfigurationProperties(prefix = "app.email.smtp.health")`.  
**Current SmtpHealthIndicator conditional:** Needs to check if SMTP is configured, not if the health properties exist.

**What needs updating:**  
- Prefix: `"email"` → `"app.email.smtp"` ✓ Mentioned in story
- Property name: `"providerConfigs[0].host"` → `"provider-configs[0].host"` (kebab-case, matching YAML) ⚠ **NOT explicitly mentioned**

The story assumes this will happen ("updated only to the new prefix") but doesn't name the kebab-case change. Add clarification: *"The @ConditionalOnProperty's name should use kebab-case (`provider-configs`) matching YAML convention, not Java camelCase."*

**Verdict:** **NOT A BUG, but clarify in dev notes.** The developer should grep SmtpHealthIndicator's current conditional, then update both prefix and property-name simultaneously.

---

### 3. **EmailContentRenderer characterization test scope: "Representative" vs. exhaustive** ⚠ DEFINITION NEEDED

**Location:** AC1, lines 24–25  
**Finding:**
```
...for a representative set (one transactional template, one OTP template, 
one booking template, plus EmailTemplate.NONE)...
```

The phrase "representative" is vague. Does this mean:
- **Option A:** Those four are sufficient because they represent distinct content paths (transactional/OTP/booking/plaintext)?
- **Option B:** Sample a few; don't need to test all 31 templates?

**Current state:** EmailTemplate enum has 31 values. AC1 says the extraction must be lossless and proved by a characterization test.

**Why this matters:** If the test only captures 4 templates and one of the 27 others has a unique content-rendering path that the extraction loses, the test gives false confidence.

**Check required:**  
Examine the EmailTemplate enum and MailService.sendEmailFromTemplate logic. Are the 31 templates rendered identically (all via Thymeleaf Context/TemplateEngine.process with the same variable setup), or do some have special cases beyond NONE?

**Verdict:** **NEEDS CLARIFICATION.** AC1 should either:
1. List all 31 templates by category (e.g., "13 transactional, 8 OTP, ...") and argue why one from each is sufficient, or
2. Require full 31-template coverage if paths diverge, or
3. Define "representative" as "covers all distinct code paths in the rendering logic."

For now, assume the dev will run the characterization test against all EmailTemplates and all known locales (not just 4 templates). If they find identical behavior for all, they can note that in test comments.

---

### 4. **NONE template handling: Same in SMTP, different in SES?** ⚠ VERIFY INTENT

**Location:** AC2, line 30  
**Finding:**
The story says both SES and SMTP use EmailContentRenderer to get (htmlBody, textBody). For NONE, that's (null, plaintext).

**SES (AC4 in Phase 1):**
> An `html` part **when `request.htmlBody()` is present** and, **independently**, a `text` part **when `request.textBody()` is present**

For NONE: htmlBody=null, textBody=plaintext.  
SES sends: text part only (html absent, text present). ✓ Correct.

**SMTP (AC2):**
> pick `htmlBody` when present, else `textBody`, matching today's `MailService.sendEmailFromTemplate` behavior exactly

For NONE: htmlBody=null, textBody=plaintext.  
SMTP sends: textBody only (because htmlBody is null). ✓ Matches current behavior.

**Verdict:** ✓ **NO ISSUE.** Both transports send plaintext for NONE. The logic is consistent.

---

### 5. **spring.factories registration for SmtpEmailSender unclear** ⚠ IMPLICIT ASSUMPTION

**Location:** AC2, Task 2 (no mention of spring.factories)  
**Finding:**
The story moves SmtpProperties/MailSenderProvider/SmtpErrorClassifier/SmtpHealthIndicator to `infrastructure/email/smtp`, and creates SmtpEmailSender as a `@ConditionalOnProperty` bean.

**Question:** Does SmtpEmailSender need a spring.factories entry for auto-configuration discovery?

**Current pattern:**
- Phase 1 added EmailTransportPropertyValidator to spring.factories as an EnvironmentPostProcessor.
- SesEmailSender exists in infrastructure/ses, gated on `@ConditionalOnProperty`. No spring.factories entry required — component scanning finds it.

**For SmtpEmailSender:**
If it's a `@Component` in infrastructure/email/smtp, component scanning (scanning from the root package down) should find it. No spring.factories entry needed, assuming the root package is scanned. But the story doesn't confirm this assumption.

**Check required:**
- Grep the current codebase for how many root-level component-scan `@ComponentScans` exist.
- Confirm infrastructure.email.smtp is within the scanned package tree.

**Verdict:** **IMPLICIT ASSUMPTION, likely OK.** Component scanning from the root app package will reach infrastructure/email/smtp by default in a Boot app. But AC5's containment test (EmailTransportArchitectureTest) should verify that SmtpEmailSender is **not** imported outside of MailService and infrastructure/email.smtp, which indirectly confirms it exists and is wired.

Add a dev note: *"SmtpEmailSender bean discovery relies on default component scanning. If the bean fails to wire, verify the root package's scan scope includes infrastructure/email/smtp."*

---

### 6. **AC5 containment test false positive risk: Carve-out too narrow?** ⚠ EDGE CASE

**Location:** AC5, lines 59–62  
**Finding:**
```
Only files under infrastructure/email/smtp/ may import jakarta.mail.., 
javax.mail.., JavaMailSender, org.springframework.mail.. 
— **with one named carve-out: `infrastructure/email/EmailAddressParser.java`**
```

EmailAddressParser is in `infrastructure/email`, not in smtp subdirectory. The carve-out allows it to import jakarta.mail despite being outside the smtp package.

**What if there are other infrastructure/email classes that also need jakarta.mail?** For example, a hypothetical EmailLoggingSupport or EmailMetricsCollector under infrastructure/email (not smtp).

**Current state (Phase 1):** Only EmailAddressParser exists under infrastructure/email. AC1 says this is the carve-out from Phase 1's code review.

**Verdict:** ✓ **NO ISSUE.** The story correctly hardcodes the one known exception per Phase 1 decision. If future infrastructure/email classes need jakarta.mail, the test can be updated then. The dev note should clarify: *"EmailAddressParser is the only infrastructure/email class (outside smtp/) that legitimately needs jakarta.mail for address validation. If a new infrastructure/email class needs jakarta.mail, add it to the carve-out here."*

---

### 7. **NoStraySmtpConfigTest must handle app.email.retry** ⚠ CONFIGURATION CORNER

**Location:** AC5, lines 64–65  
**Finding:**
```
asserts every shipped `application*.yaml`...contains no `spring.mail:` block 
and no bare `email:` top-level key
```

The story dev notes (line 166) mention:
> `application-prod.yaml` has an unrelated `email: retry: enabled: true` key (`EmailRetryScheduler`'s own `@ConditionalOnProperty`, default `matchIfMissing = true`)

This is NOT the same `email` prefix as old EmailProperties/new SmtpProperties. It's a separate email-retry scheduler config.

**Risk:** A naive `grep "^email:"` across YAML files will false-positive on this `email.retry` block.

**Verdict:** ✓ **ADDRESSED IN DEV NOTES.** Line 166 warns the developer to scope the test to the specific `email.providerConfigs` shape, not a blanket top-level-key check. Dev should check for `email.providerConfigs:` or `email:` with an immediate child of `providerConfigs`, not just `email:` alone.

Clarification for Task 5: *"When scanning for stray `email:` config, search specifically for `email.providerConfigs:` or `email:\n  providerConfigs:`. The `email.retry:` block is different and should not trigger a failure."*

---

### 8. **Downstream tests: VideoModerationEmailListenerTest signature change** ⚠ COMPILE FIX SCOPE

**Location:** AC9, lines 100–101  
**Finding:**
```
Given VideoModerationEmailListenerTest and VideoModerationAdminAlertEnvelopeIT 
mock MailService as a seam and currently compile against 
sendEmailFromTemplate(...) throws MessagingException
```

The story says these tests need compile fixes for the new signature (no checked exception).

**What needs verifying:**
These tests mock MailService. When sendEmailFromTemplate no longer throws MessagingException, the mock's signature must change. But what assertions are in these tests? Do they test error handling?

**Example:** If `VideoModerationEmailListenerTest.testXyz` does:
```java
doThrow(new MessagingException(...)).when(mockMailService)
  .sendEmailFromTemplate(...);
```
This must change to throw an unchecked exception (EmailTransportException subtype).

**Verdict:** ✓ **CLEAR REQUIREMENT.** AC9 correctly identifies this as a compile-only change. The dev should search each test file for `throws MessagingException` and `doThrow(new MessagingException`, update both, and re-run the tests to confirm they still compile and pass.

---

### 9. **MailManager.isRetryable exception depth: Exactly 3 levels?** ✓ VERIFIED

**Location:** AC4, lines 51–52  
**Finding:**
The story says `isRetryable` walks "two-level cause-walk (direct exception, its cause, its cause's cause)" and "stays exactly as it is".

Looking at the actual code (MailManager.java:144–150), it does exactly this:
```java
Throwable direct = unknownException;
Throwable cause = unknownException.getCause();
Throwable causeOfCause = cause != null ? cause.getCause() : null;

return Stream.of(direct, cause, causeOfCause)
    .filter(Objects::nonNull)
    .noneMatch(t -> NON_REPAIRABLE_ERRORS.stream().anyMatch(...));
```

**In Phase 2's flow:**  
- Retry template wraps exceptions in RuntimeException
- MailManager.isRetryable() checks: direct (RuntimeException), cause (MessagingException or EmailTransportPermanentException), causeOfCause (null or the original error)
- For SmtpEmailSender throwing EmailTransportPermanentException directly: direct=EmailTransportPermanentException → isRetryable=false. ✓ Correct.

**Verdict:** ✓ **NO ISSUE.** The depth is correct for Phase 2's exception layers.

---

### 10. **SmtpErrorClassifier must handle all jakarta.mail exceptions** ⚠ COMPLETENESS CHECK

**Location:** AC2, line 36  
**Finding:**
The story specifies four types for Permanent:
- MailParseException, MailPreparationException, jakarta.mail.internet.AddressException, jakarta.mail.internet.ParseException

And "every other MessagingException → Transient".

**Question:** Are these the only four that are actually thrown by MimeMessage/MimeMessageHelper/JavaMailSenderImpl in typical SMTP scenarios?

**Likely omissions to check:**
- `jakarta.mail.SendFailedException` — thrown when send() fails to reach recipient
- `jakarta.mail.AuthenticationFailedException` — thrown on auth error
- `jakarta.mail.internet.MimeTypeParseException` — thrown if charset/encoding is invalid
- `jakarta.mail.IllegalWriteException`, `IllegalStateException` — API misuse

**Dev note:** The story says "preserves MailManager.NON_REPAIRABLE_ERRORS's exact classification" which is correct—SmtpErrorClassifier maps the same four types. If new exception types appear during implementation (e.g., when constructing MimeMessage), they'll default to Transient, which is the safe choice.

**Verdict:** ✓ **CORRECT APPROACH.** The four types are what's currently classified as permanent in MailManager. SmtpErrorClassifier should mirror this exactly. Future exceptions (if encountered) will default to Transient. The dev should log any encountered exceptions that don't match the four types at WARN (like AC5 requires for SES) so unexpected types are visible.

---

## Missed Flows / Edge Cases

### 1. **Registration listener exception wrapping unchanged** ✓ CORRECT

AC11 (Phase 1) widened catch from `EmailTransportException` to `EmailTransportException | IllegalArgumentException`. Phase 2 doesn't touch the registration listeners. ✓ Correct — registration listeners stay on direct OutboundEmailSender calls, not routed through MailManager.

### 2. **Health indicator activation order: SMTP indicator activates even if transport ≠ SMTP** ✓ DELIBERATE

AC2 says: *"Do not re-gate SmtpHealthIndicator on app.email.transport=smtp in this story"*

Until Phase 3, SmtpHealthIndicator activates if SMTP is configured (provider-presence-gated), regardless of the active transport. This can be confusing (health check for a disabled transport) but is explicitly called out as deliberate under-scoping. Phase 3 will add SesHealthIndicator and re-gate both. ✓ Intentional.

### 3. **Resend flow for SMTP: What if SMTP send fails?** ✓ SAME AS TODAY

Phase 2 doesn't move registration listeners to MailManager/outbox (that's Phase 4). They call OutboundEmailSender directly. If SMTP send fails, the exception is caught and logged by the listener. The user can call POST /resend-otp to retry. ✓ Same as today.

### 4. **What if both htmlBody and textBody are present?** ✓ SMTP PICKS HTML

AC2 says: *"pick `htmlBody` when present, else `textBody`"*

If both are present (e.g., an email with both HTML and plaintext versions), SMTP sends only HTML. This mirrors "matching today's MailService behavior exactly" and is consistent with current code (line 75 of MailService sends `isHtml=true` for templates, ignoring any text-only option). ✓ Correct.

---

## Testing Coverage Gaps: None

The story specifies tests for each AC and provides a test plan that is comprehensive.

---

## Blockers: None

All identified items are either clarifications (not bugs) or already-addressed (dev notes).

---

## High Confidence Items (No Rework Expected)

✓ AC1 extraction is mechanical — verbatim move of lines 61–74 plus special case  
✓ AC2 SMTP package structure mirrors Phase 1's port pattern  
✓ AC3 MailService rewrite is straightforward once EmailContentRenderer exists  
✓ AC4 MailManager exception classification mirrors current behavior  
✓ AC5 containment tests are hand-rolled, pattern already established in codebase  
✓ AC6 config migration is straightforward (rename/rebind YAML keys)  
✓ AC7 spring.mail removal is a pure deletion (zero references in src/main/java confirmed)  
✓ AC8/AC9 wiring and downstream fixes are compile-time changes

---

## Recommendations for Developer

1. **Before Task 1:** Verify EmailTemplate enum has 31 entries and confirm they all use identical Thymeleaf rendering logic (or list exceptions). This informs characterization test scope.

2. **Before Task 2:** Search the current SmtpHealthIndicator for its exact `@ConditionalOnProperty` annotation. Update both prefix AND property-name (kebab-case) simultaneously.

3. **Task 5 implementation note:** When writing `NoStraySmtpConfigTest`, scope the `email:` key check to `email.providerConfigs:` specifically, not a blanket top-level-key match. The `email.retry:` block is unrelated.

4. **After all tasks:** Run `mvn clean verify` to confirm all tests pass (CI is the gate per project convention). Do NOT run locally per project's documented preference.

5. **Correlation ID logging:** Add a comment in MailService explaining that UUID-per-call generates unique IDs for each retry attempt; correlation across retries is Phase 4 concern via persisted envelope.messageId.

---

## No False Positives Detected

Every spot-check against Phase 1 code and the current codebase state holds up. The story is accurate and internally consistent.
