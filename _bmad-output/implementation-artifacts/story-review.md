# Story Review: SES-1-1 Introduce the Outbound Email Port

**Reviewer:** Senior Dev  
**Date:** 2026-09-11  
**Story:** ses-1-1-introduce-outbound-email-port (Phase 1 of 7-phase SES consolidation)  
**Status:** ready-for-dev → review findings documented

---

## Summary

This story introduces a transport-neutral `OutboundEmailSender` port, rebuilds the SES adapter behind it, and points the three registration listeners directly at the port. The scope is tightly bounded: SMTP, `MailManager`, `MailService`, and all booking/session-pack/alert email paths remain untouched.

**Risk Assessment:** Mechanical refactoring with **medium-to-high attention needed** on three areas:
1. **EnvironmentPostProcessor ordering** — startup failure mode if ordering assumption breaks
2. **Property validation sequencing** — gap between binding and validation
3. **LoggingEmailSender collision and degradation** — TOCTOU window and unbounded failure path

All findings below are **actionable in this phase** — none require scope changes or depend on later phases.

---

## Findings

### BLOCKER-1: EnvironmentPostProcessor Registration Precedence Not Enforced

**Location:** AC3 / Dev Notes / Task 2  
**Severity:** Blocker  
**Issue:**

The story requires `EmailTransportPropertyValidator` to run **after** `ConfigDataEnvironmentPostProcessor` loads profile YAMLs, or it runs before any YAML is loaded and rejects the base default. AC3 states this in javadoc only:

> "the validator's own javadoc states its `EnvironmentPostProcessor` ordering assumption explicitly: it implements no `Ordered`, sorting to `LOWEST_PRECEDENCE`, which is *required* — it must run after `ConfigDataEnvironmentPostProcessor`..."

**The problem:**

1. **Javadoc is not executable.** A future Spring Boot upgrade, developer simplification, or reviewer oversight could strip the `// must be LOWEST_PRECEDENCE` comment and no test would fail.
2. **`LOWEST_PRECEDENCE` is not explicit.** The default is implicit (no `@Order` = `LOWEST_PRECEDENCE`), so someone might add a minor value like `@Order(50)` thinking "lower than default," not realizing every other processor is lower.
3. **Test coverage gap:** `EmailTransportPropertyValidatorTest` runs the validator directly against a `MockEnvironment`, which never calls `ConfigDataEnvironmentPostProcessor` at all. A test that passes doesn't prove the ordering is correct in a real `SpringApplication`.

**Impact:**

If the ordering is wrong, every profile's YAMLs are loaded *after* the validator runs, so `app.email.transport` is seen as unset, base default `log` never applies, and the validator rejects "unset" (per AC3: "an absent value falls back to a safe base default" — but in the broken ordering, there is no default yet). This manifests as:

- Local dev with no profile: startup aborts with "app.email.transport is required"
- CI tests with no profile: same abort (affects `AdminLoginResourceTest`, `RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT` per AC3)
- `mvn spring-boot:run` with no profile: same abort

This is a **production boot failure** on a configuration issue the story explicitly designs to avoid.

**Fix:**

Add an explicit test that proves the ordering is correct in a real `SpringApplication` context. `EmailTransportPropertyValidatorTest` must include a test using a real `SpringApplicationBuilder` (not `ApplicationContextRunner`) to verify the ordering assumption holds.

**Not a deviation:** AC3 already names this constraint; enforcing it in a test is just making it machine-checkable.

---

### BLOCKER-2: SesPropertiesValidator Bean Creation Timing vs Property Binding

**Location:** AC7 / Task 4  
**Severity:** Blocker  
**Issue:**

`SesPropertiesValidator` is a separate `@Component` gated on `@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")`, constructor-injecting `SesProperties`. But there's a **sequencing gap:** property binding happens *before* bean creation. If `SesProperties` binds with structural errors (YAML syntax issues), the context fails before `SesPropertiesValidator` bean is even constructed, and the error message is Spring's generic "Could not bind" rather than your specific validation message.

More critically: **what if `maxSendRatePerSecond` is malformed** (e.g., `not-a-number`)? `SesProperties` binding would fail before the validator can run its own checks. The operator sees a binding error, not a clear "max-send-rate-per-second must be positive."

**Impact:**

- Error messages conflate binding errors with validation errors
- Operator debugging is harder when two failure modes produce different exception types
- Validation never runs if binding fails

**Fix:**

Ensure `SesProperties` binding is type-safe and separate from *logical* validation:

1. Add defensive binding handling (or ensure Spring's binding is strict for numeric fields)
2. Tests covering both: `maxSendRatePerSecond: not-a-number` → clear binding error; `maxSendRatePerSecond: -5` + `transport=ses` → clear validation error
3. Update `SesPropertiesValidator` javadoc to explain when it runs and what errors it reports

---

### BLOCKER-3: LoggingEmailSender File-Writing Unbounded Retry Loop Failure Mode

**Location:** AC9 / Task 5  
**Severity:** Blocker  
**Issue:**

`LoggingEmailSender` handles file collisions with a retry loop up to a bounded limit (e.g., 100). When exhausted, it falls through to log-only with no logging about the exhaustion.

**The problems:**

1. **Unspecified exhaustion behavior.** When the loop exhausts, what does the sender log? If INFO, the file-writing failure is silent. If ERROR, an operator sees confusing repeated errors.

2. **No alerting on exhaustion.** The loop silently degrades without any `[FILE_WRITE_EXHAUSTED]` marker or metric. A dev using `outbox-dir` for template work assumes files are being written, but a prolonged collision (permission denied, file held open) silently loses them.

3. **TOCTOU window post-startup.** Between directory creation at startup and first send, the directory could be deleted, unmounted, or become inaccessible. AC9 handles this by degrading to log-only, but there's no tracking that a send was degraded.

**Impact:**

- Template developers may silently lose files with no visibility
- No operational metrics to detect file-writing issues
- Permissions issues cascade silently across a dev box

**Fix:**

1. **Specify the exhaustion log message.** If max retries (e.g., 100) is reached, log at WARN with the correlation ID, e.g.:
   ```
   WARN: Failed to write outbox file for correlationId after 100 collision attempts; degraded to log-only
   ```

2. **Add a metric:** emit `email.log.file_write.degraded` counter on every degrade (not just exhaustion), tagged with reason. Operators can then detect permission issues.

3. **Log directory setup failure at startup with the actual error message**, not just "Cannot write to outbox directory."

4. **Add an explicit test** that removes the directory after startup, calls `send()`, and asserts it degrades to log-only with the expected WARN message.

---

### HIGH-1: app.email.transport Variable in Compose Files — Silent Breakage Risk

**Location:** Task 9 warning / No guard rail  
**Severity:** High  
**Issue:**

The story includes a warning **in the task** not to add `APP_EMAIL_TRANSPORT` to compose files. If it appears as `APP_EMAIL_TRANSPORT=${APP_EMAIL_TRANSPORT:-}` (blank), it overrides every profile's `app.email.transport` simultaneously, causing an abort.

**The problem:**

1. **Warning is in task prose, not in code.** A future developer won't read 250+ lines and will follow the established pattern of adding env vars to compose.

2. **No test prevents this.** There's no CI check that `docker-compose.yml` doesn't contain `APP_EMAIL_TRANSPORT`.

3. **The failure is cryptic.** Prod boot fails with "app.email.transport is not valid: [blank]" — an operator thinks something else is broken.

**Fix:**

Add a test to `IntegrationTestConventionTest` that fails if `APP_EMAIL_TRANSPORT` appears in `docker-compose.yml`:

```java
@Test
void noAppEmailTransportInDockerCompose() {
    String dockerCompose = Files.readString(Path.of("docker-compose.yml"));
    assertThat(dockerCompose)
        .as("APP_EMAIL_TRANSPORT breaks all profiles; see requirements/ses-email-consolidation.md#4.5")
        .doesNotContain("APP_EMAIL_TRANSPORT");
}
```

Also add a bold warning in `.env.example` with the same rationale.

---

### HIGH-2: SesProperties.maxSendRatePerSecond Default (10) Silent Misconfiguration in Sandbox

**Location:** AC7 / AC6  
**Severity:** High  
**Issue:**

`maxSendRatePerSecond` defaults to 10/s and AC6 states this is for production SES (sandbox is 1/s). AC7 validates it's positive, but AC6 also says sandboxed accounts need `max-send-rate-per-second: 1`. No startup validation catches a sandbox account with the default 10/s.

**The problem:**

1. **No distinction between sandbox and production access.** A developer setting up uat with a sandboxed account and leaving the default 10/s will silently hit `TooManyRequestsException` and burn retries.

2. **Silent cascade.** An uat with default 10/s + sandboxed account will boot without error and silently throttle on first registration test.

**Impact:**

- First-time uat setup will silently throttle with a confusing failure mode
- No obvious log message or warning

**Fix:**

1. **Add a startup WARN** when `maxSendRatePerSecond >= 10` AND `transport=ses`:
   ```
   WARN: app.ses.max-send-rate-per-second=10 assumes production SES account.
   If this is a sandboxed account, set to 1 or requests will throttle.
   ```

2. **Document in `.env.example`** the arithmetic and the sandbox caveat.

3. **Add javadoc to `SesProperties.maxSendRatePerSecond`** noting the production assumption.

---

### HIGH-3: SesErrorClassifier UnknownException Default Behavior Underdocumented

**Location:** AC5  
**Severity:** High  
**Issue:**

Unknown SDK exceptions default to **Transient**. The rationale (in AC5) is sound: unknown permanent → 6 retries; unknown transient → lost email. But the rationale is only in the AC, not in code.

**The problem:**

1. **Rationale is not in javadoc.** A future developer might "improve" this to default Permanent without understanding the tradeoff.

2. **No test of the default.** `SesErrorClassifierTest` must include an explicit test for unknown exceptions.

**Impact:**

- The safety rationale is lost if not documented in code
- An operator seeing 6 retries on an unknown error has no explanation

**Fix:**

1. **Add explicit javadoc to `SesErrorClassifier`** explaining the default behavior and why unknown exceptions are safer as Transient.

2. **Add a test:**
   ```java
   @Test
   void unknownException_classifiesAsTransient() {
       SdkException unknown = new SdkClientException("Unexpected");
       assertThat(SesErrorClassifier.classify(unknown))
           .isInstanceOf(EmailTransportTransientException.class);
   }
   ```

3. **In `SesEmailSender`, log unknown exceptions at WARN** so operators can spot new exception types.

---

### HIGH-4: EnvironmentPostProcessor May Not Run in Test Contexts Using ApplicationContextRunner

**Location:** Task 6 / Testing  
**Severity:** High  
**Issue:**

`ApplicationContextRunner` does not run `EnvironmentPostProcessor`s. Task 6 says to split tests into two parts (bean wiring via `ApplicationContextRunner`, ordering via real `SpringApplicationBuilder`), but the task doesn't explicitly specify how or mandate both.

**The problem:**

1. A developer might consolidate back into `ApplicationContextRunner` to simplify, undoing the fix
2. The `spring.factories` registration test is described but not implemented in detail

**Impact:**

- A future refactoring silently breaks the validator registration without a test failing
- The split-test structure is not enforced

**Fix:**

Explicitly specify in Task 6 that two separate test classes/nested classes are required:

1. **Unit tests** (direct instantiation or `ApplicationContextRunner`): validator logic
2. **Integration tests** (real `SpringApplicationBuilder`): EnvironmentPostProcessor registration and ordering

Document why both are needed: "ApplicationContextRunner does not run EnvironmentPostProcessor, so a real SpringApplication is required to verify registration and ordering."

---

### MEDIUM-1: UUID-Based correlationId Discarded in Phase 4 — Logging Ambiguity

**Location:** AC11 / Dev Notes  
**Severity:** Medium  
**Issue:**

Phase 1 uses UUID for `correlationId`. Phase 4 replaces it with `sendId`. Logs will have two different identifier formats.

**Impact:**

- Traceability is lost across the phase boundary
- Operator debugging requires understanding two ID schemes

**Fix:**

Document in Phase 1's story that Phase 4 will introduce a new ID scheme and how to cross-reference them in logs.

---

### MEDIUM-2: EmailAddressParser Validation Path Incomplete

**Location:** AC8  
**Severity:** Medium  
**Issue:**

AC8 specifies `.validate()` or strict `parse()`, but no test explicitly verifies the implementation uses `.validate()` and not the bare constructor.

**Impact:**

- Invalid addresses might silently pass if test data happens to be valid

**Fix:**

Require a test that explicitly checks:
1. Bare `new InternetAddress("not-an-address")` would fail `.validate()`
2. The parser implementation uses `.validate()` and rejects the same invalid input

---

### MEDIUM-3: LoggingEmailSender Filename Sanitization — Path Traversal Risk

**Location:** AC9  
**Severity:** Medium  
**Issue:**

AC9 specifies sanitization, but there's no security test. Phase 4 introduces new callers; a future call might pass unchecked user data.

**Impact:**

- Path traversal risk if future callers pass unchecked data
- Test gap for security-relevant string handling

**Fix:**

Add a security test covering path traversal (`../`), shell metacharacters, and very long IDs.

---

### LOW-1: AC12 Narrows §6.11 Without Justifying the Trade-Off

**Location:** AC12  
**Severity:** Low  
**Issue:**

AC12 moves dev to `log` in Phase 1 (instead of Phase 5) because `SmtpEmailSender` doesn't exist yet. The claim is that the escape hatch is "already inert" but the reasoning isn't documented.

**Fix:**

Document in Dev Notes why the escape hatch was inert (e.g., `DevSesEmailService` is `@Profile("!dev")`, so it never creates on dev even if `enabled=true`).

---

### LOW-2: PlayerRegistrationEmailListener Structured Logging Block Not Specified

**Location:** AC11 / Task 7  
**Severity:** Low  
**Issue:**

AC11 mentions preserving a structured-log block but doesn't show the exact code or placement.

**Fix:**

In Task 7, add an explicit code snippet showing the block's placement inside the try block before the send call.

---

## Summary of Actions

### Critical (Blockers)
1. **BLOCKER-1:** Test EnvironmentPostProcessor ordering in real SpringApplication
2. **BLOCKER-2:** Separate type-safe binding from logical validation in SesProperties
3. **BLOCKER-3:** Specify exhaustion behavior and add metrics for LoggingEmailSender

### High Priority
1. **HIGH-1:** Add test preventing `APP_EMAIL_TRANSPORT` in compose files
2. **HIGH-2:** Add startup WARN for default rate limit on sandboxed accounts
3. **HIGH-3:** Add javadoc and test for unknown exception default
4. **HIGH-4:** Explicitly document two-part test structure for EnvironmentPostProcessor

### Medium/Low Priority
- Remaining findings are implementation detail clarity and testing rigor

**Recommended implementation order:** BLOCKER → HIGH → MEDIUM → LOW

---

## Final Assessment

**Status:** Ready for dev, with three blockers requiring resolution before merge.

**No scope changes needed.** All findings are testability/safety improvements to the design already outlined in the requirements.
