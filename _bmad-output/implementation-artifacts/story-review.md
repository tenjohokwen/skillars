# Senior-dev audit — `skillars-deferred-110` (Email Adapter Hardening, Outbox Durability & Doc Accuracy)

**Reviewed:** 2026-09-14 against working tree at `story/deferred-110-email-adapter-hardening`
(story content re-verified against `master@f6a093e0`'s code).
**Scope:** correctness of the story's premises, reachability of the defects it claims, and whether each
AC's prescribed fix + test actually achieves its stated goal.
**Method:** every cited `file:line` re-read; regex behaviour executed; Spring Boot 3.5.16 /
spring-orm 6.2.8 bytecode inspected where the answer depended on framework semantics. Findings
below are only those I could evidence directly — speculative concerns were dropped.

**Verdict:** the story is well-researched and most citations are accurate, but **4 of 11 ACs rest on a
premise that does not hold** (AC1, AC2, AC6, AC9), **2 prescribe a fix that contradicts an explicit
decision already recorded in this codebase** (AC5, AC8), and **3 will fail or silently break something
as written** (AC7, AC9, AC10). Recommend re-scoping before dev-story rather than implementing as-is.

---

## Severity key

| Level | Meaning |
|---|---|
| **BLOCKER** | The AC's premise is false or its fix does not achieve its stated goal. Must be re-scoped before implementation. |
| **MAJOR** | The fix is directionally right but will break something, miss the real case, or contradict an existing decision. |
| **MINOR** | Accuracy/completeness gap; cheap to fix in-flight. |

---

## BLOCKER findings

### B1 — AC9: the "out-of-band provenance" premise is wrong. Hibernate is auto-creating the schema.

AC9 states that `envelope_entity` "exist[s] in every real environment only by out-of-band provenance"
because `spring.jpa.hibernate.ddl-auto: none`. That is not what this application does.

`src/main/resources/application.yaml:65-66`:

```yaml
  jpa:
    generate-ddl: true
    hibernate.ddl-auto: none
```

Those two lines together produce `hibernate.hbm2ddl.auto=update`, not "no DDL". Verified in the pinned
dependency bytecode:

1. `HibernateProperties` (spring-boot-autoconfigure 3.5.16) — when `ddl-auto` equals `none` it calls
   `Map.remove("hibernate.hbm2ddl.auto")` rather than putting `none`
   (`javap -c`: constant `#69 "none"` → branch to `Map.remove` at offset 75).
2. `HibernateJpaVendorAdapter` (spring-orm 6.2.8) — `if (isGenerateDdl())` puts
   `"hibernate.hbm2ddl.auto" = "update"` (offsets 58/65/67). `JpaBaseConfiguration.jpaVendorAdapter()`
   sets `generateDdl` from `spring.jpa.generate-ddl`.
3. `AbstractEntityManagerFactoryBean.createNativeEntityManagerFactory` merges the vendor map into the
   JPA property map with `putIfAbsent` — and step 1 removed the key, so `update` lands.

**Corroborating evidence, independent of the bytecode:** there is genuinely no DDL for this table
anywhere — `grep -rin envelope src/main/resources/db/migration/` returns nothing across all 129
migrations, and `src/test/resources/sql/createSchema.sql` is the single line
`CREATE SCHEMA IF NOT EXISTS main;` (it is the Testcontainers `withInitScript`, `SharedContainers.java:112`).
Yet `MailManagerDuplicateSendIdIT` asserts a real Postgres unique-index conflict on `send_id`, and
`EmailRetrySchedulerIT:60-61` and `sql/cleanup.sql:8-9` both `DELETE FROM main.envelope_entity`.
Those tests cannot pass unless something creates the table and its unique constraint. Hibernate's
`update` is that something.

**Consequences for AC9 as written:**

- **The migration is a permanent no-op.** Every environment that has ever booted the app already has
  the table, so an `IF NOT EXISTS`-guarded `CREATE TABLE` + `CREATE UNIQUE INDEX IF NOT EXISTS` will
  never execute anywhere. AC9's stated motivation — "`ses-1.4`'s `MailManager.saveAndFlush` and
  `MailManagerDuplicateSendIdIT` now actively depend on the `send_id` unique constraint firing" — is
  **not** discharged by it. The migration documents an assumption; it verifies and repairs nothing.
- **`retry`'s `columnDefinition = "text"` is load-bearing, not "a latent, separate bug".** With
  `hbm2ddl=update` in effect, `columnDefinition` *is* used for DDL, so `envelope_entity.retry` is a
  `text` column in every existing database — which is exactly why
  `EnvelopeEntityRepository:17`'s native query reads `WHERE e.retry = 'true'`. The story's Dev-Notes
  aside ("looks like a latent, separate bug worth flagging… out of this AC's scope to fix") invites a
  dev to normalise it to `boolean` and diverge fresh DBs from existing ones. `requirements/ses-email-consolidation.md:1230`
  carries the same wrong claim ("`ddl-auto` is `none` … so `columnDefinition` is never used for DDL") —
  the story inherited it without re-checking.
- **The real, larger gap is undocumented.** `docs/dev-docs/database/index.html:79` states
  "**Hibernate never creates or alters a table.** Every schema change must be a migration — there is no
  'it'll be created on startup' fallback." That is false today. For a story whose theme 4 is *doc
  accuracy*, this is a much bigger miss than either AC11 item.

**Recommendation:** split AC9. (a) Confirm empirically (boot dev with
`logging.level.org.hibernate.tool.schema=DEBUG`, or dump `hibernate.hbm2ddl.auto` from the EMF
properties) — 5 minutes. (b) Decide whether `generate-ddl: true` should be removed; that is an
owner-level decision with real blast radius (removing it means *every* Hibernate-managed-but-unmigrated
table must get a migration, not just this one). (c) Only then write the migration, pinned to the shape
the live schema actually has (dump `\d main.envelope_entity` from a dev DB — do **not** hand-guess, and
do not regenerate from an entity whose `columnDefinition` you may have just "fixed").

### B2 — AC6: the defect is unreachable. `loadCoachBookingRequests` never rejects.

AC6's failure path is: "`acceptAllBatch` succeeds → `setBatchAcceptResult(batchId, results)` →
`await loadCoachBookingRequests()` (`:653`) **throws** → `catch` (`:655`) →
`deleteBatchAcceptResult(batchId)` wipes the real results."

`loadCoachBookingRequests` (`src/frontend/src/stores/booking.store.js:388-450`) is a
`try { … } catch (e) { … return false } finally { … }` that **swallows every rejection**:

```js
    } catch (e) {
      if (requestId !== coachRequestsSequence) { …; return true }
      coachRequestsError.value = e
      return false
    }
```

It returns `false`; it does not propagate. The store's own test suite documents this explicitly —
`bookingStoreSpec.js:191-193`: *"Refresh rejects → loadCoachBookingRequests **swallows** it, its
success-path prune never runs"* — and the deferred-109 AC5.2 specs assert `refreshed === false` plus a
populated `coachRequestsError` on a bad response, never a thrown error.

Nothing between `setBatchAcceptResult(batchId, results)` (`:646`) and the end of the `try` can throw.
So `handleAcceptAllBatch`'s `catch` is reachable **only** when `acceptAllBatch` itself rejects — which
is precisely the case where the entry still holds the `null` seed, i.e. the case deferred-109 AC5.1
already handles correctly.

**Two further problems in the same AC:**

- **The contract quote is reversed.** AC6 justifies the fix as preserving "this function's own
  documented contract ('callers read results from here')". The comment at `:647-651` says the
  *opposite*: *"Callers must read results from here, **not** from `batchAcceptResultsByBatch[batchId]`"* —
  "here" is the return value. And on this path the promise rejects, so no caller receives anything
  either way.
- **The prescribed test cannot be written.** "unit — `acceptAllBatch` succeeds, `loadCoachBookingRequests`
  rejects" is not expressible against the real store: the specs mock the *API* (`getCoachBookingRequests`),
  and that rejection is swallowed inside the store function. You would have to change
  `loadCoachBookingRequests` to make the test possible — i.e. change the code to create the bug.

**Recommendation:** keep the guard if you want it (it costs one line and is a sound invariant), but
re-scope AC6 from "a real frontend defect… a coach's batch-accept UI never silently loses real results"
to "a defensive invariant against a future change that lets the refresh propagate", drop it from the
Story Overview's list of live defects, and replace the test with one that asserts the guard's logic
directly rather than an unreachable end-to-end path.

### B3 — AC1: the regex cannot match a bare literal, so the prescribed verification passes green.

AC1's premise: "A second, regression `from-address: noreply@skillars.com` line added later in the same
YAML is never examined." The pattern (`NoHardcodedSenderTest.java:27-28`) is:

```java
Pattern.compile("from-address:\\s*\"?\\$\\{APP_SES_FROM_ADDRESS(:([^}]*))?}\"?");
```

It **requires** the `${APP_SES_FROM_ADDRESS…}` placeholder form. A bare literal does not match at all,
so looping over `matcher.results()` changes nothing for it. Executed to confirm — against a file
containing both a valid placeholder line and `from-address: noreply@skillars.com`:

```
match 1 default='dev@localhost'
total matches = 1
```

The loop fix only catches a *second placeholder-form* line carrying a different default — a much
narrower (and much less likely) regression than the one the AC describes.

Worse, AC1's own verification step — *"add a temporary local mutation (a second `from-address` line
with a real domain) during development to confirm it now fails, then revert"* — **would pass**, giving
false confidence that the guard was strengthened.

**Recommendation:** if the intent is "no hardcoded sender literal", the fix is a second, separate
pattern that matches `from-address:` followed by anything that is *not* a `${…}` placeholder, asserted
to have zero matches. Then loop the existing pattern over all matches as a bonus. Fix the AC's
verification step to use a bare-literal mutation against the *new* pattern.

### B4 — AC2: the motivating failure (550 recipient rejection) is still classified transient after the fix.

AC2 is correct that `SmtpErrorClassifier.classify` (`:35-48`) never inspects
`MailSendException.getFailedMessages()` (API confirmed: `Map<Object, Exception> getFailedMessages()`,
plus `Exception[] getMessageExceptions()`). The **fix** is the problem.

`NON_REPAIRABLE_ERRORS` is `{MailParseException, MailPreparationException, AddressException, ParseException}`.
What `JavaMailSenderImpl.doSend` actually puts into `failedMessages` for a per-recipient 550 is a
`jakarta.mail.SendFailedException` — which extends `MessagingException`, **not** `AddressException`
(`AddressException extends ParseException extends MessagingException` is a different branch). Scanning
the map against the unchanged `NON_REPAIRABLE_ERRORS` list therefore leaves AC2's own headline case —
*"a permanent recipient rejection … misclassified as transient"* — still transient.

AC2's prescribed test (`failedMessages` containing an `AddressException`) would pass while the real bug
survives. And that test case is close to synthetic: `SmtpEmailSender:53` calls
`helper.setTo(request.toAddress())` *before* `javaMailSender.send(…)`, so a malformed address throws
`AddressException` directly out of the helper and is already classified permanent by the existing walk
(`SmtpEmailSender:64-66`).

**Second, separate problem — the circuit-breaker relief claim is false.** AC2 says the current
behaviour burns "circuit-breaker failure-rate pressure … that a single bad address can use to degrade
delivery for unrelated mail". Classifying the failure as *permanent* does not relieve that. In
`ComponentConfig.defaultCustomizer()` (`:97`) only `EmailTransportRateLimitedException` is
`ignoreException`'d; an `EmailTransportPermanentException` counts against the sliding window exactly
like a transient one. (AC4 does not change this either — `retryTemplate.execute` sits *inside*
`circuitBreaker.run` at `MailManager:118-133`, so the breaker records one call regardless of retry
count.) The genuine benefit of AC2 is narrower than claimed: `retry=false` on the envelope, so
`EmailRetryScheduler` stops re-driving it. Say that, and drop the breaker claim.

Mitigating note on blast radius: per-template breakers exist (`EmailTemplate.circuitBreakerName()` —
the six registration/OTP templates use `registrationEmailService`, everything else `emailService`), so
"unrelated mail" is bounded to the same breaker group, not the whole system.

**Recommendation:** widen the classification list (or add a targeted `SendFailedException` check with
`getInvalidAddresses()`/`getValidUnsentAddresses()` inspection — a `SendFailedException` with only
*invalid* addresses is permanent; one with *valid-unsent* addresses is transient), and test against a
`MailSendException(Map.of(msg, new SendFailedException("550 …")))`. Re-word the breaker claim, and
consider raising the companion `ignoreException` question alongside AC4's decision.

---

## MAJOR findings

### M1 — AC8: the fix contradicts this codebase's explicit, documented decision for the structurally identical case.

AC8 argues a missing enum constant is "unlike a missing handler, [it] can never self-resolve", and
prescribes log-ERROR + release the row (i.e. delete it).

`OutboxRowProcessor:109-114` records the opposite decision for the missing-handler case, in the same
scenario AC8 cites:

```java
if (handler == null) {
    // Never dropped: it keeps its data safe until a deploy that carries the handler picks
    // it up. Common during a rolling deploy, which is why the backoff below matters.
```

A removed/renamed enum constant *does* self-resolve by the same mechanism — a rollback, or the old pod
in a rolling deploy (both pods drain the same `outbox_messages` table). AC8's fix means the **new** pod
claims the row, fails `valueOf`, and permanently destroys a message the **old** pod could still have
delivered. That trades a loud, recoverable state for irreversible data loss.

And the current state is **not silent**: `OutboxRowProcessor.recordFailure` logs `[OUTBOX_RETRY]` with
backoff capped at 1h (`:48-49`), and `OutboxService.sweep()` (`:123-128`) raises
`[OUTBOX_STUCK]` at ERROR once attempts cross the threshold. AC8's "immortal poison row" is an alerting
state a human is meant to act on, which is a materially different problem statement from the one the
AC presents.

Three additional gaps in AC8:

- **`NullPointerException` is not caught.** `EmailTemplate.valueOf(null)` throws NPE, not
  `IllegalArgumentException`. A payload written with a null `template` reaches the same
  retry-forever path and is untouched by the prescribed catch.
- **No durable record.** The prescribed "log + release" leaves no `EnvelopeEntity` row — reintroducing
  exactly the asymmetry `ses-1.4`'s own code review closed for the sibling path
  (`NotificationEmailOutboxHandler:89-95`: *"a first-attempt deadline expiry used to leave no durable
  record at all — no EnvelopeEntity row, no [OUTBOX_STUCK], no metric — asymmetric with
  EmailRetryScheduler's identical DEADLINE_EXPIRED precheck"*). And here it genuinely cannot be
  persisted, because `EnvelopeEntity.emailTemplate` is the very enum that failed to resolve. At minimum
  log the full payload so the message is reconstructible.
- **"its test class" does not exist.** `grep -rl NotificationEmailOutboxHandler src/test/` returns only
  `MailManagerDuplicateSendIdIT`, `EmailDataRoundTripContractTest` and `RegistrationEmailDurabilityIT`.
  There is no dedicated test class; AC8's Files list implies one.

**Recommendation:** re-frame AC8 as a decision, not a mechanical fix — the choice is between
`[OUTBOX_STUCK]`-and-keep (current, matches the handler-missing precedent) and log-and-drop. If
log-and-drop wins, gate it behind an attempts threshold so a rolling deploy's window is survived, log
the raw payload, and catch `RuntimeException` (or explicitly `IllegalArgumentException | NullPointerException`).

### M2 — AC5: the premise is unproven, and the fix guarantees duplicate admin alerts.

AC5 treats a `null` read-back at `VideoModerationEmailListener:94` as "specifically a race between that
`REQUIRES_NEW` commit becoming visible and this read".

`MailManager.sendEmailSync` is `@Transactional(REQUIRES_NEW)` and is invoked cross-bean through the
Spring proxy, so its transaction has **committed** by the time the call returns. The caller's read then
runs under PostgreSQL's default READ COMMITTED, which takes a fresh snapshot per statement — a
committed row is immediately visible. Under that isolation the described race does not occur; under a
snapshot isolation it *never* resolves, and a bounded re-read of the same statement inside the same
transaction cannot help either way. The AC prescribes a retry loop without establishing what actually
produces the `null`.

**The fix's side effects are not analysed:**

- `adminAlertEnvelope()` mints a **fresh `sendId`** on every call
  (`:148`, `ShortCode.shortenInt(UUID.randomUUID().hashCode())`). Throwing after the bounded window
  retains the outbox row, which is re-driven with a new envelope and a new `sendId` — so if the
  original send *did* succeed (the case the AC is worried about), the fix guarantees a **duplicate
  admin alert**, and will keep producing one per backoff cycle for as long as the null persists.
  `ModerationAdminAlertOutboxHandler`'s javadoc does accept duplicates as the right side to err on, so
  this is a trade-off rather than a bug — but the AC should state it.
- If the null *is* persistent, throwing creates precisely the immortal poison row that AC8 in this same
  story exists to eliminate. The story contains both positions without reconciling them.

**Recommendation:** before implementing, establish the real cause (add the read-back result + the
transaction/isolation context to the existing WARN and wait for one occurrence), or state explicitly
that this is a belt-and-braces guard for an unproven condition. Either way, document the duplicate-alert
consequence and bound the retention (throw only up to N attempts, then log-and-release) so AC5 and AC8
do not pull in opposite directions.

### M3 — AC7: a `strategy.matrix` will break the workflow's artifact upload.

`.github/workflows/frontend-unit-tests.yml:69-75` uploads with a fixed artifact name:

```yaml
      - name: Upload coverage report
        uses: actions/upload-artifact@… # v7.0.1
        with:
          name: frontend-unit-reports-${{ github.run_id }}
```

`actions/upload-artifact` v4+ fails the step on a duplicate artifact name within a run. Two matrix legs
both resolve to the same `frontend-unit-reports-<run_id>`, so the second leg's upload errors out
(and the step has `if: always()`, so it is not skipped). The name must include the matrix value, e.g.
`frontend-unit-reports-${{ github.run_id }}-${{ matrix.tz }}`.

Not a false positive on the underlying gap: the job has no `TZ` env and no matrix, and
GitHub-hosted `ubuntu-latest` runners are UTC; `vitest.config.mjs` sets no timezone either. The gap is
real — only the prescribed mechanism is incomplete.

### M4 — AC9: the migration will fail `MigrationConventionLintTest`, which the AC names as its test.

`MigrationLint` (`src/test/java/com/softropic/skillars/db/MigrationLint.java`) binds two rules to the
`CREATE UNIQUE INDEX` AC9 prescribes:

- `Rule.BLOCKING_INDEX` — pattern at `:210` is
  `CREATE\s+(UNIQUE\s+)?INDEX\b(?!\s+CONCURRENTLY)`; requires a
  `-- migration-lint: allow-blocking-index <reason>` opt-out (`:626-627`).
- `Rule.MISSING_LOCK_TIMEOUT` — `lintLockTimeout` (`:926-950`) fires on `ANY_CREATE_INDEX` too, and
  requires either a bounded `SET lock_timeout = '<n>s'` earlier in the file or
  `-- migration-lint: allow-unbounded-lock-wait <reason>`.

`CREATE UNIQUE INDEX CONCURRENTLY` is not an escape hatch here — `V135`'s own header records that
"this project runs Flyway migrations inside a single transaction", and `CONCURRENTLY` cannot run inside
one. The clean answer is to declare the uniqueness **inline in the `CREATE TABLE`** as a constraint
(which matches what Hibernate's `@Column(unique = true)` actually produced) rather than as a separate
`CREATE INDEX` statement. AC9 should say so; as written its stated test expectation is wrong.

Also in AC9's Files list: *"possibly `src/test/resources/sql/createSchema.sql` if that fixture needs the
table too"*. It does not — that file is a single `CREATE SCHEMA IF NOT EXISTS main;` used as the
Testcontainers `withInitScript` (`SharedContainers.java:112`); putting a table there would fork the test
schema away from Flyway.

### M5 — AC3: the problem the AC diagnoses is not the problem its fix solves.

AC3's "Verified at HEAD" makes three observations; the "Fix approach" addresses one and a half.

- **"No `@ConditionalOnProperty` gate on the bean itself, so `Integer.parseInt(providerConfig.getPort())`
  at `:53` runs in every profile, including prod where `transport=ses`"** — correct
  (`MailSenderProvider` is a bare `@Component`, and `application.yaml:158-171` defines
  `provider-configs` unconditionally for all profiles). But the fix adds a validator gated on
  `transport=smtp`, which is **inactive in prod**. A malformed port in the base YAML still crashes prod
  boot with a bare `NumberFormatException` out of a bean constructor — the opaque failure
  `SesPropertiesValidator`'s javadoc says this pattern exists to prevent. Either gate
  `MailSenderProvider` itself on `transport=smtp` (it has exactly one consumer, `SmtpEmailSender`,
  which is already so gated) or move the parse into a lazily-evaluated path.
- **Validator-vs-constructor ordering is undefined for `port`, not just for the empty list.** AC3 flags
  ordering only for the divide-by-zero case. The same hazard applies to the non-numeric-port check: if
  `MailSenderProvider`'s constructor runs before the validator's `@PostConstruct`, the validator's
  friendly `AppSetupException` is never reached and the raw `NumberFormatException` wins. The AC's
  prescribed test ("non-numeric port throws `AppSetupException` naming the property") will pass as a
  plain unit test on `validate()` and be unreliable in any context test. Simplest robust answer: do the
  validation inside `MailSenderProvider`'s own constructor (or `@DependsOn` the validator).
- **`Math.floorMod` still divides by zero.** `Math.floorMod(x, 0)` throws `ArithmeticException` exactly
  as `%` does. The AC already hedges ("if ordering can't be guaranteed cheaply, also guard…") — given
  the point above, treat the direct guard as required, not optional.

One risk worth an explicit decision: rejecting a blank `username` at boot is a **new** hard failure for
anyone running `transport=smtp` against a local no-auth relay (MailHog/Mailpit). Existing tests are
safe — `SmtpTransportBootIT:62-66` sets `name`/`host`/`port`/`username`/`password` — but this is a
dev-experience regression the AC does not mention.

### M6 — AC10: the rename's verification scope misses 6 live references, and its riskiest file is unnamed.

- **`requirements/ses-email-consolidation.md` is out of the grep.** AC10's verification is *"Grep for
  `outbox-dir`/`outboxDir` across `src/` and `docs/`"*. `requirements/` holds 6 occurrences —
  `:431` (the `LoggingEmailSender` behaviour spec), `:494`, `:616`, `:831`, `:1044`, `:1114`, `:1206`
  (decision **D-4**, "Dev transport after cutover … `app.email.log.outbox-dir`, Phase 5"). Phase 5 is
  `ses-1-5-ses-cutover`, still `backlog` and explicitly out of this story's scope — so renaming now
  without updating the requirements doc leaves the still-to-be-implemented cutover spec naming a
  property that no longer exists. Widen the grep to the repo root and add
  `requirements/ses-email-consolidation.md` to the Files list.
- **`src/test/resources/application-test.yaml:128-136` is the highest-risk file and is not named.**
  Its own comment: *"the blank `outbox-dir` is load-bearing, not decorative. `AbstractIntegrationTest`
  is `@ActiveProfiles({"dev","test"})`, so every integration test loads `application-dev.yaml`'s
  `outbox-dir: target/mails` FIRST … `""` here disables file-writing rather than every registration-flow
  IT in the suite writing real files to `target/mails` on every run."* Rename the key in
  `application-dev.yaml` but not here and the override silently stops applying. The failure mode is
  **not a failing test** — it is ITs writing stray HTML files — so AC10's prescribed test ("existing
  `LoggingEmailSender`-related tests still pass") would not catch it. The repo-wide grep would, which is
  why the grep must be the gate, not the unit tests.
- Also update `application-dev.yaml:13`, whose prose comment names `app.email.log.outbox-dir`.

---

## MINOR findings

### m1 — AC11(a): the proposed rewording is still factually wrong.

AC11(a) proposes: *"…no boolean flag anywhere in `infrastructure.email`/`.ses`/`.email.smtp` —
`enable.test.mail` (`platform.notification`) is a different-area, **pre-existing exception**, not a stray
survivor in this one."* Singular "exception" is wrong: `EmailRetryScheduler:46` carries
`@ConditionalOnProperty(name = "email.retry.enabled", havingValue = "true", matchIfMissing = true)`, and
`application-prod.yaml:11-13` sets `email.retry.enabled: true`. That is a second surviving boolean
enable/disable toggle in the same `platform.notification` area. Either name both or drop the
enumeration and say "the email *transport* selection carries no boolean flag; other flags in
`platform.notification` (e.g. `enable.test.mail`, `email.retry.enabled`) are unrelated."

### m2 — AC11(b): the provider-config shape to be documented is incomplete, and hides a real bug.

AC11(b) prescribes documenting `app.email.smtp.provider-configs` as *"list of `{host, port, username,
password}`"*. `ProviderConfig` has **six** fields — the two omitted ones both matter:

- `name` — the per-provider label `SmtpHealthIndicator` surfaces in the `notification` health group
  (`:171`, `ProviderStatus`), so it is operator-visible, not decorative.
- `implicitTls` — added by `skillars-deferred-99` AC5, with its own javadoc ("defaults from
  `port == 465` when unset").

Worth flagging separately in the Dev Agent Record: `MailSenderProvider.toMailSender` (`:49-64`)
**ignores `implicitTls` entirely** — it hardcodes `protocol = "smtp"` and
`mail.smtp.starttls.enable = true` for every provider. So `SmtpHealthIndicator` probes a port-465
provider with a TLS handshake and reports it UP (`:213-217`), while the actual send path talks
plaintext-plus-STARTTLS to an implicit-TLS endpoint and fails. Documenting round-robin without this is
documenting a half-truth. (Out of scope to fix here — but it is a genuine open defect, and AC11 is the
AC that would enshrine the wrong description.)

Minor: AC11(b) also says to reference "the renamed-by-AC10 dump-dir property where relevant" under the
`infrastructure.email.smtp` bullet. `dump-dir` belongs to the `log` transport; it is not relevant there.

### m3 — Dev Notes: "No shared files across items" is not true.

The Dev Note asserts *"each of the 11 items touches a distinct file/file-set — they are independently
testable and revertible"*, and uses that to justify the bundle. **AC10 and AC11 both edit
`docs/dev-docs/infrastructure/index.html`** — AC11's own text acknowledges it ("Do this **after** AC10
lands, so nothing new here references the old `outbox-dir` name"), which contradicts the Dev Note.
Correct the note so the sequencing constraint is visible where a dev planning the commit order will
read it, and so the "independently revertible" rationale isn't over-claimed.

### m4 — AC4: the decision as framed omits its companion question.

AC4 is correctly scoped as a decision, and its evidence is accurate (`ComponentConfig:60-68` excludes
only `EmailTransportRateLimitedException`). Two clarifications for the owner:

- The circuit-breaker effect is **latency only**. `retryTemplate.execute` is nested *inside*
  `circuitBreaker.run` (`MailManager:118-133`), so the breaker records one call whether the retry
  template makes 1 or 3 attempts. The real wins are the 10 s `TimeLimiter` budget and the duration of
  the open `REQUIRES_NEW` transaction — which is what the AC text says, so just don't let it drift into
  a "reduces breaker pressure" claim (see B4).
- The symmetric question is unasked: should `EmailTransportPermanentException` also be added to
  `defaultCustomizer()`'s `ignoreException` predicate? `ses-1.3`'s code review reached exactly that
  conclusion for the rate-limited type once `notRetryOn` made it propagate on the first attempt
  (`ComponentConfig:70-87`). Deciding `notRetryOn` without deciding `ignoreException` at the same time
  repeats the sequence that produced that follow-up.

### m5 — AC5: minor attribution slip.

AC5 says *"`ModerationAdminAlertOutboxHandler` deletes the durable outbox row on any normal return from
this method"*. The delete is in `OutboxRowProcessor.claimAndHandle:116`, not in the handler. Harmless,
but the handler is where a dev will go looking.

---

## What the story got right (verified, no action needed)

- **AC1** — `NoHardcodedSenderTest:31` really does call `matcher.find()` once per file inside the
  `for (Case c : cases)` loop. (The *consequence* the AC draws from it is what's wrong — see B3.)
- **AC2** — `SmtpErrorClassifier:35-48` genuinely never touches `getFailedMessages()`; the API exists
  as described (`Map<Object, Exception>`).
- **AC3** — `MailSenderProvider` is an ungated `@Component`; `nextSender():66-70` is
  `counter.getAndIncrement() % providers.size()`; no `SmtpPropertiesValidator` exists;
  `SesPropertiesValidator` is the right template; `AppSetupException` is the right exception type.
  (Practical severity of the 2³¹-call overflow is near-zero, but the one-line `floorMod` fix is free.)
- **AC4** — `ComponentConfig.retryTemplate()` excludes only `EmailTransportRateLimitedException`;
  `EmailTransportPermanentException` is in `MailManager.NON_REPAIRABLE_ERRORS`. Flagging this as a
  decision rather than folding it in silently is the right call.
- **AC5** — `VideoModerationEmailListener:102-107` does WARN-and-return on `persisted == null`, and the
  outbox row is deleted on a normal return. The *gap* is real; the diagnosis and fix are what need work.
- **AC7** — the workflow has one job, no matrix, no `TZ`; `vitest.config.mjs` sets no timezone either.
- **AC8** — `EmailTemplate.valueOf(p.template())` at `NotificationEmailOutboxHandler:76` is unguarded,
  and payloads carry the template as a raw `String`.
- **AC9** — there is genuinely no `envelope_entity` DDL in any of the 129 migrations or in the test
  schema fixture. (The *explanation* for why the table nevertheless exists is what's wrong — see B1.)
- **AC10** — `outboxDir` exists at `EmailTransportProperties:33-40`, is set at
  `application-dev.yaml:18`, documented at `docs/dev-docs/infrastructure/index.html:182`. The name
  collision with `platform.outbox` is a real discoverability hazard.
- **AC11(a)** — `docs/dev-docs/infrastructure/index.html:173-174` really does say "no boolean
  enable/disable flag anywhere in this area any more", and `ComponentConfig:32`'s `enable.test.mail`
  really does falsify it.
- **AC11(b)** — line ~194 names `MailSenderProvider` with no description, and neither dev-doc page
  documents `provider-configs` or the round-robin.
- Every referenced test class exists except the one noted in M1:
  `VideoModerationEmailListenerTest`, `VideoModerationAdminAlertEnvelopeIT`,
  `MailManagerDuplicateSendIdIT`, `MigrationConventionLintTest`, `SmtpErrorClassifierTest`,
  `PessimisticLockRetryer`. `MailSenderProviderTest` does not exist (the AC says "new or extended" — fine).

---

## Suggested disposition

| AC | Action |
|---|---|
| AC1 | **Re-scope.** Add a bare-literal pattern; the `matcher.results()` loop alone does not close the stated gap. Fix the AC's own verification step. |
| AC2 | **Re-scope.** Widen the permanent-classification set to cover `SendFailedException` (ideally via invalid-vs-valid-unsent address inspection); drop the circuit-breaker claim. |
| AC3 | **Amend.** Gate `MailSenderProvider` on `transport=smtp` (or validate in its constructor); keep the empty-list guard as required, not optional; note the no-auth-relay dev regression. |
| AC4 | **Proceed as a decision**, with the `ignoreException` companion question attached. |
| AC5 | **Hold.** Establish the actual cause of the `null` read-back first; state the duplicate-alert trade-off; bound the retention so it doesn't become a poison row. |
| AC6 | **Downgrade / re-scope.** The path is unreachable. Keep the guard as a defensive invariant if wanted; remove it from the "real defect" framing and rewrite the test. |
| AC7 | **Amend.** Add the matrix value to the artifact name. |
| AC8 | **Hold as a decision.** It contradicts `OutboxRowProcessor`'s explicit "never dropped" precedent and the existing `[OUTBOX_STUCK]` alerting. Also catch NPE and log the raw payload. |
| AC9 | **Split & hold.** Confirm `hbm2ddl=update` empirically; the `generate-ddl: true` question is owner-level. Pin the migration to the live schema, not to regenerated DDL; declare uniqueness inline to pass `MigrationLint`. Add the `database/index.html:79` doc correction. |
| AC10 | **Amend.** Widen the grep to the repo root; explicitly name `src/test/resources/application-test.yaml` and `requirements/ses-email-consolidation.md`. |
| AC11 | **Amend.** Name both surviving toggles; document all six `ProviderConfig` fields; record the `implicitTls`-ignored-by-`MailSenderProvider` defect. |

**Net:** AC4, AC7 (with M3 fixed), AC10 (with M6 fixed) and AC11 (with m1/m2 fixed) are ready.
AC1, AC2, AC3 need their fix re-specified. AC5, AC8 and AC9 need an owner decision before any code is
written, and AC6 should not ship as a bug fix.
