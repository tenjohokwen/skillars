# Story Review: skillars-deferred-111

**Date:** 2026-09-14 | **Reviewer:** Senior Dev Audit | **Status:** Ready-for-dev with noted clarifications

---

## Executive Summary

The story is well-scoped, correctly identifies and verifies 12 actionable items against the current codebase, and has good rigor in its AC structure. No false positives found. Three ACs are appropriately flagged as decision-needed. 

**Minor clarifications needed before dev** (all dev-time details, not blockers):
- AC2 collision-suffix independent-counter behavior needs explicit test case
- AC3 and AC7 could benefit from slight spec clarification
- AC11 exception-logging bypass needs sanitizer decision scope clarified
- AC12 duplicate-detection confirmation needs a verification approach

---

## Detailed Audit by AC

### AC1: LoggingEmailSender Collision-Exhaustion WARN Unthrottled ✅

**Analysis:** Correctly identified, properly scoped.

- **Issue verified:** `:147-148` log.warn sits outside directoryWritable guard loop. Correct.
- **Fix approach sound:** Reusing directoryWritable via compareAndSet mirrors the existing IOException/FileAlreadyExistsException pattern correctly.
- **State persistence concern:** The directoryWritable flag is class-level and persists across successive sends with *different* correlationIds. This is correct: a later successful write for a different ID clears the flag, restoring normal logging. Test should verify this doesn't create cross-test fragility — confirm test cleanup/reset strategy.
- **Tested edge case:** "seed 100 collision files, send twice with same ID, assert one WARN" is correct. Test should also verify: send with ID=1 (collision), then send with ID=2 (success) → flag cleared → send again with ID=1 (collision) → WARN emitted again. This validates the cross-ID behavior.

**No false positives.** Mutation test approach solid.

---

### AC2: LoggingEmailSender Silent Text-Part Discard ✅

**Analysis:** Issue correctly identified; implementation approach needs one clarification.

- **Verified issue:** `:106-108` only writes htmlBody when both present. Correct.
- **Fix approach clear:** Write both .html and .txt files when both bodies present.
- **Collision-suffix behavior — NEEDS CLARIFICATION:** The AC states "collision-suffix logic still applies independently to each extension (two .html files with the same correlation id still suffix -2/-3/…, and likewise for .txt, without cross-extension collisions)."

  This is correct *if* the implementation:
  - Writes 1.html, if collision, tries 1-2.html, etc. (independent per extension)
  - Then writes 1.txt, if collision, tries 1-2.txt, etc. (independent counter)
  - Result: can be 1-2.html and 1.txt (asymmetric suffixes) OR 1-2.html and 1-2.txt (symmetric), depending on which collides

  **Test must explicitly verify the expected asymmetry:** 
  - First send, ID=1 → 1.html, 1.txt both created
  - Second send, ID=1 → 1-2.html, 1-2.txt both created (both collide, both same suffix)
  - *Hypothetical partial collision case:* If only 1.html pre-exists but 1.txt doesn't → expect 1-2.html, 1.txt (different suffixes)
  
  The current AC test case doesn't explicitly verify independent suffix counters. Add a sub-case testing asymmetric collision.

**No false positive on the core issue.** Implementation detail flag only.

---

### AC3: Adapter-Wrap-Depth Test Against Real Adapters ✅

**Analysis:** Requirement and gap correctly identified. Test approach sound with one scope note.

- **Requirement validated:** `requirements/ses-email-consolidation.md:1078-1082` confirms 2-level cause-chain walk is the contract.
- **Current gap validated:** `MailManagerResilienceTest` is indeed mock-only (`:42-48`). Correct.
- **Fix approach:** "at least one case through real `SesEmailSender` + `SesErrorClassifier` and one through real `SmtpEmailSender` + `SmtpErrorClassifier`" — this is minimally sufficient.
- **Mutation test validated:** Mutation (add extra wrapper) → breaks → revert. Solid.

**Dev note:** The phrase "at least one case" leaves room for interpretation. Should the test cover *multiple* permanent-failure types (e.g., one SES auth failure + one SMTP relay-denied)? Or is one per adapter sufficient? The story doesn't mandate breadth here; current guidance ("at least one") is acceptable but slightly under-specified. Dev can exercise judgment.

**No false positives.** Test correctness depends on actually driving a real permanent failure, which the approach validates.

---

### AC4: Health Indicator DOWN-Caching TTL — Decision-Needed ✅

**Analysis:** Decision-point correctly framed. No false assumptions in the problem statement.

- **Current behavior verified:** Both `SesHealthIndicator` (hardcoded 60s) and `SmtpHealthIndicator` (configurable, default 60s) cache UP and DOWN identically. Correct.
- **Recovery visibility impact correctly analyzed:** "Single blip yields exactly two consecutive failures against a 60s TTL — one short of tripping the threshold" — this is accurate given Docker's 30s interval + retries:3.
  - Transient failure at t=0 → checks at t=30 (fail, cache hit DOWN), t=60 (fail, still in cache), t=90 (cache expired, re-probes UP). Two failures < 3 retries, so container stays running. ✅
  - Recovery invisible until t=60 when cache expires. True.
- **Correctly flagged as decision-needed:** (1) Is asymmetric TTL wanted? (2) If yes, what value? (3) Config constant vs. property?

**No false positives.** This is a valid trade-off that needs product/ops input.

---

### AC5: SesSendRateLimiter Unthrottled WARN ✅

**Analysis:** Correctly identified and properly scoped.

- **Issue verified:** `:52-53` logs WARN unconditionally on every rejection. Correct.
- **Coupling concern resolved:** AC correctly notes ses-1-3 AC (D3) already shipped, so rate-limit rejection no longer consumes retries. This AC is now independent. Good.
- **Fix approach:** AtomicBoolean throttle on transition to throttled state; DEBUG for every rejection detail. Mirrors AC1's pattern correctly.
- **Test case:** "two consecutive rejections → one WARN, successful acquire, another rejection → second WARN" validates the transition behavior.

**No false positives.** Straightforward fix.

---

### AC6: Registration Listeners — HashMap Null-Token Fail-Fast ✅

**Analysis:** Loss-of-guard correctly identified. Implementation straightforward.

- **Issue verified:** `Map.of()` had implicit null-check; `HashMap` accepts null silently. `:61-62, 84-85` in CoachRegistrationEmailListener confirmed; stated as same pattern in other two listeners.
- **Ledger claims 6 call sites:** 3 listeners × 2 tokens (otp, verifyUrl) = 6 sites. Matches the fix approach.
- **Fix approach sound:** `Objects.requireNonNull(event.otp(), ...)` before each `data.put(...)` call restores fail-fast.
- **Risk mitigation:** AC correctly notes null token would otherwise serialize as `{"otpCode":null}`, survive outbox round-trip, and be *delivered* as SENT. requireNonNull prevents this.

**Potential dev clarification:** Are all three listeners called synchronously before entity persistence? If any are async-wrapped, throwing might be swallowed by event-handler error logging. Probable but should verify during dev.

**No false positives.** Test case (each listener/token pair → null → NullPointerException) is correct.

---

### AC7: RegistrationEmailDurabilityIT Fragility — Global Table Scans ✅

**Analysis:** Fragility correctly identified. Fix approach incomplete on *one* detail.

- **Issue verified:** `:75-78` `committedRowFor` uses `findAll().stream()` + in-memory filter. `:114, 137, 173, 193, 226, 238` all call it. Scheduler also polls entire table. Correct.
- **Current safety:** UUID-unique email address in test data makes test pass today even with global scan. But cost grows with suite and accidental re-drive of other tests' FAILED rows is fragile.
- **Repository method availability confirmed:** `findBySendId` exists and is used elsewhere. Good.
- **Fix approach offered two options:** (1) ArgumentCaptor on event, (2) query by email after first send.

**Dev clarification needed (minor):** The fix approach says "e.g. via ArgumentCaptor/test seam on the event, or by having the IT read the row back once by email immediately after the listener fires…". Which is recommended? ArgumentCaptor is fragile if event contract changes; query-by-email is more direct. The story leaves this to dev judgment, which is OK but could nudge toward one. Suggest: query-by-email-then-capture-sendId is simpler and more robust.

**No false positives.** Test validation approach (run alongside seeded unrelated FAILED row, confirm scoped assertions unaffected) is solid.

---

### AC8: Envelope-Entity Flyway Callout — Stale Documentation ✅

**Analysis:** Doc gap correctly identified and verified.

- **Current state:** Callout at `docs/dev-docs/notification/index.html:336` still frames V136 as missing; references `deferred-work.md` without a real `<a>` link.
- **Verification:** `skillars-deferred-110` AC9 shipped `V136__pin_envelope_entity_schema.sql`, so premise is now stale. Post-prune of deferred-work.md (2026-09-14) already corrected the ledger bullet. Doc-only fix remains open. Correct.
- **Fix approach:** Reword to mention V136, add proper link matching page convention.
- **Test:** HTML tag-balance check (same tool as ses-1-7).

**No false positives.** Straightforward doc fix.

---

### AC9: SMTP Password Retried Until Exhausted ✅

**Analysis:** Correctly identified and properly scoped.

- **Issue verified:** `:83-84` NON_REPAIRABLE_ERRORS omits MailAuthenticationException/AuthenticationFailedException. Result: transient classification, 3+6 retries against impossible credential.
- **Docker-Compose exposure:** AC correctly notes `docker-compose.local.yml`'s bogus `${GMX_PASSWORD:dev_gmx_password}` triggers this.
- **Wrap-depth validated:** `JavaMailSenderImpl.doSend` catches `AuthenticationFailedException` and rethrows Spring's `MailAuthenticationException`, wrapping the former. So `direct` should catch `MailAuthenticationException` directly.
- **Dev verification noted:** "verify during dev whether the underlying `AuthenticationFailedException` also needs to be listed" — this is a good cautious flag for dev.

**Dev note:** The exception might surface unwrapped in some paths; the test case should construct the exception the way `JavaMailSenderImpl.doSend` actually throws it to validate real path coverage.

**No false positives.** Mutation test (add to NON_REPAIRABLE_ERRORS, verify test still passes; remove, verify test fails) is correct.

---

### AC10: MailSenderProvider Ignores implicitTls ✅

**Analysis:** Issue correctly identified and cross-verified. Extraction approach sound.

- **Issue verified:** `:65-78` hardcodes `protocol = "smtp"` and `mail.smtp.starttls.enable = true` for all providers, never reads `providerConfig.getImplicitTls()`. Correct.
- **Cross-check:** SmtpHealthIndicator already has `isImplicitTls(ProviderConfig, port)` helper (`:213, 229-231`), correctly defaulting from `port == 465`. So the two are out of sync: health check reports UP for implicit-TLS, send path still uses plaintext+STARTTLS. Real defect.
- **Fix approach:** Extract shared helper, use in both classes, set protocol="smtps" and mail.smtps.* namespace (not mail.smtp.*) when implicit TLS.

**Dev implementation note:** Confirm that `JavaMailSenderImpl` needs `mail.smtps.*` properties (not `mail.smtp.*`) when protocol="smtps". This is a real-world detail; test should validate it. Current AC is correct but would benefit from a note: "JavaMailSender uses protocol-prefixed property namespaces; verify during implementation."

**No false positives.** Test case (implicit-TLS provider → assert protocol="smtps" and starttls.enable is unset; STARTTLS provider → assert unchanged) is correct.

---

### AC11: SMTP Failure Messages Expose PII (Recipient Addresses & OTPs) — Decision-Needed ✅

**Analysis:** PII-exposure gaps correctly identified. Two related issues, appropriately surfaced as one decision.

- **Issue 1 verified:** SmtpErrorClassifier (`:98, 108, 116`) and MailManager (`:248`) both persist raw recipient addresses in exception messages and envelope_entity.error stacktraces. Correct.
- **Issue 2 verified:** MailManager's `logger.error(..., exception)` (`:146-149`) passes exception as SLF4J's trailing throwable, bypassing the `loggableData()` masking. Correct.
  - Risk: Jackson serialization failure echoes partially-written JSON (could include OTP); JDBC failure echoes bound parameters (could include OTP).
- **Precedent context:** skillars-deferred-110's masking work makes asymmetry newly visible; no logging-layer sanitizer exists yet.
- **Correctly flagged as decision-needed:** (1) Is a sanitizer worth building now? (2) Sanitize durable record, logs, or both? (3) Interim narrow fix acceptable?

**Clarification note (not a false positive):** The story correctly notes AC11 is "explicitly narrower in scope than the isRetryable/circuit-breaker architectural gap" (skillars-deferred-109/110 exclusions). Good boundary-setting. But dev should confirm: is the "interim fix on classifier only" option a blocker for this story, or can that gap remain open? The story doesn't prohibit leaving AC11 unimplemented if no decision reached, which is correct.

**No false positives.** Decision properly framed; scope boundaries respected.

---

### AC12: envelope_entity_recipients — No Primary Key or FK Index — Decision-Needed ✅

**Analysis:** Schema gap correctly identified and appropriately elevated to decision-point.

- **Current state verified:** `V136__pin_envelope_entity_schema.sql:103-111` has no PK and no index on `envelope_entity_id` FK. Faithful pin of Hibernate's auto-DDL output. Correct.
- **Performance impact:** Every collection load and FK cascade-check is a sequential scan. Real but latent.
- **Usage pattern verified:** Current single-recipient-per-envelope assumption holds. Multi-recipient planned but not yet.
- **Decision correctly framed:** (1) Is current shape acceptable? Defer until multi-recipient real? (2) If fixing now, surrogate PK + non-unique index, or composite PK on (envelope_entity_id, recipient)?
  - **Key tradeoff noted:** Composite PK prevents duplicate recipients (stronger guarantee) but changes behavior (true duplicates now fail instead of silent success). Need to "confirm no current caller relies on" silent success.

**Dev verification approach needed (minor gap):** The story says "confirm no current caller relies on" silent duplicate success, but doesn't specify how to check. Suggestion: grep for all calls to envelope_entity_recipients inserts (Hibernate @ElementCollection is managed, so this is rare, but should be verified). Also check for any business logic that assumes duplicate-insert succeeds silently (unlikely but possible in idempotency patterns).

**No false positives.** Decision properly scoped; migration constraints cited (docs/deployment/migration-conventions.md, MigrationLint).

---

## Cross-Cutting Observations

### AC Cohesion & Dependencies

- **AC9 + AC10 neighborhood:** Both touch SMTP adapter code (SmtpErrorClassifier, MailSenderProvider). Story correctly suggests sequencing them in one commit pass. ✅
- **AC1 + AC5 pattern reuse:** Both use AtomicBoolean transition-throttle pattern, correctly mirroring each other. ✅
- **AC2 + AC6 no conflict:** Different files (LoggingEmailSender vs. registration listeners). ✅
- **Decision ACs (AC4, AC11, AC12):** Story correctly notes these need explicit decisions before dev implementation, per project pattern. Dev agent record notes this responsibility. ✅

### Testing Standards

- **Mutation tests:** AC1, AC2, AC3, AC9, AC10 all specify "// Mutation:" comments naming the reverts. Story enforcement is clear. ✅
- **AC-specific test guidance:** Every code-change AC includes test strategy. Good.
- **Test clarity gaps:** AC2 (independent suffix counters) and AC7 (ArgumentCaptor vs. query-by-email) could benefit from slightly tighter test specifications, but not false positives — just dev-time details.

---

## False Positives: None Found ✅

Each issue verified against HEAD (post-skillars-deferred-110). No misidentified problems, no overclaimed scope.

---

## Assumptions Validated ✅

1. **AC1:** directoryWritable persists across sends → correct, enables cross-ID throttle behavior.
2. **AC2:** Collision logic can be extended per-extension → reasonable, test will validate.
3. **AC3:** 2-level cause-chain walk is sufficient → verified in requirements doc.
4. **AC6:** requireNonNull throws before persistence → correct if listener is synchronous.
5. **AC9:** JavaMailSenderImpl wraps AuthenticationFailedException in MailAuthenticationException → stated from JavaMailSenderImpl.doSend, dev can verify.
6. **AC10:** mail.smtps.* properties are correct for implicit TLS → assumption, should be validated by test.
7. **AC11:** Exception logging bypasses masking → verified in story.
8. **AC12:** Single-recipient per envelope today → stated in SmtpErrorClassifier javadoc (implied).

---

## Minor Clarifications for Dev (Not Blockers)

1. **AC2:** Explicitly test the asymmetric collision-suffix case (one extension collides, the other doesn't).
2. **AC3:** "At least one case per adapter" is minimal; consider whether multiple permanent-failure types should be tested.
3. **AC6:** Confirm listener is called synchronously before entity persistence; async wrapping would swallow requireNonNull.
4. **AC7:** Query-by-email approach is likely more robust than ArgumentCaptor; suggest this in dev kickoff.
5. **AC10:** Test must validate mail.smtps.* properties are actually honored by JavaMailSender (not just that the code sets them).
6. **AC11:** Defer test writing until decision is made; do not pin a specific sanitizer shape prematurely.
7. **AC12:** Add a verification step to check for existing duplicate recipients (unlikely but should confirm none exist before adding composite PK).

---

## Ledger Cross-Reference Spot-Checks ✅

- AC1–AC8: Ledger citations point to correct headings in deferred-work.md. Spot-checked AC1, AC4, AC8. ✅
- AC9–AC12: Post-merge ledger-prune (2026-09-14) updated citations to point to skillars-deferred-110 and ses-1-4 sections. ✅

---

## Recommendation

**Story is ready for dev.** All 12 ACs are correctly specified, verified against current codebase, and free of false positives. Three decision-point ACs (AC4, AC11, AC12) are appropriately flagged for early dev-time decision-making. Dev notes are clear about off-limits boundaries (SES cutover, SMTP removal) and testing standards (mutation tests, no local mvn verify).

Minor dev-time clarifications noted above (AC2 asymmetry test case, AC7 approach preference, AC10 property namespace validation, AC12 duplicate detection verification) are not blockers — all are routine implementation details that dev will address during the work.
