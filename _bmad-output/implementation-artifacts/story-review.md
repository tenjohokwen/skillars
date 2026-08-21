# Story Review: skillars-deferred-53

Reviewed: `skillars-deferred-53-concurrency-race-test-determinism-and-hardcoded-currency-configuration.md`
(Status at review time: `ready-for-dev`, Dev Agent Record empty, all task checkboxes unchecked.)

## Method

Every factual claim in the story was re-verified against the live repository rather than trusted from the
story text: read `BookingServiceConcurrencyIT.java` in full, `BookingService.java`'s `createBookingRequest`
and `acceptBooking` (including every query issued before the coach-row lock, to check for an earlier
lock-wait whose SQL text could false-trigger the new polling helper), `CoachProfileRepository.java`,
`CoachProfile.java`'s `@Table` mapping, `StripePaymentGateway.java`, `StripePaymentGatewayTest.java` in
full, `ConfigService.java`, every `platform_config`-seeding migration (`V20`, `V90`–`V93`) to check the
migration-style claim, `SharedContainers.java` and `AbstractIntegrationTest.java` (DB role, container
topology), `TestConfig.java` and every payment-module IT that references `PaymentGateway`
(`CaptureReservationIT`, `PaymentWebhookIdempotencyIT`, `SessionPackPaymentResourceIT`,
`StripeOnboardingResourceIT`) to check whether any of them actually exercise the real
`StripePaymentGateway` bean, `pom.xml`'s surefire/failsafe configuration, and `deferred-work.md` at all
three cited line numbers plus the new AC2 entry. AC1/AC2's core race-test analysis, AC3's currency-hardcoding
analysis, and AC4's ledger-tagging state all check out exactly as described — **no false positives are
reported below for those premises.** The findings below are real gaps in the story's execution detail, not
restatements of the story's own already-correct analysis.

---

## Finding 1 (Medium) — AC3's new migration omits `ON CONFLICT (key) DO NOTHING`, contradicting the story's own instruction to mirror V93's style

**What's wrong:** AC3's SQL snippet for `V99__payment_currency_config.sql` is a bare `INSERT ... VALUES (604, ...)` with no conflict clause. But every `platform_config` seed migration since this pattern was established — `V90`, `V91`, and `V93` (the exact migration this story cites as "the most recent prior seed" to match "column list/style") — ends in `ON CONFLICT (key) DO NOTHING;`. `V93`'s own migration comment spells out why this is not decorative:

> "The id MUST be free. `main.platform_config.id` is PRIMARY KEY with no sequence (V20:8) — ids are
> hand-assigned — and the ON CONFLICT target below is `key`, a DIFFERENT unique constraint
> (`uq_platform_config_key`, V20:9). An id collision therefore raises a PK violation the ON CONFLICT
> (key) clause never sees, failing Flyway on every database that has run V91."

AC3's instruction says to match V93's "exact column list/style," and does copy the column list and the
`NOW()` convention, but drops the one clause V93 itself explains is load-bearing for migration safety.

**Why it matters:** this is a real, verified deviation from an established, explicitly-documented project
convention, not a stylistic nit — every other seed of this table in the last several migrations does this on
purpose. Following AC3's snippet literally reintroduces the exact failure mode V93 was written to guard
against for `platform.payment.currency`'s row.

**Recommendation:** append `ON CONFLICT (key) DO NOTHING;` to AC3's migration snippet, matching `V90`/
`V91`/`V93` exactly.

---

## Finding 2 (Medium) — Task 3.4's IT-level "no wiring regression" check names an IT that, like every other payment-module IT, does not exercise the real `StripePaymentGateway`

**What's wrong:** Task 3.4 says: "Run `mvn -o integration-test -Dit.test=PaymentWebhookIdempotencyIT` (or
whichever payment-module IT actually exercises `chargeAndCapture` end to end — verify which one during
implementation rather than assuming) to confirm no wiring regression from the new config key." The premise —
that *some* IT exercises the real `StripePaymentGateway.chargeAndCapture()` — does not hold anywhere in this
codebase:

- `TestConfig.java:94-98` declares `@Primary @Bean PaymentGateway paymentGateway() { return new
  StubPaymentGateway(); }`. Every `*IT` class extends `AbstractIntegrationTest`, which `@Import`s
  `TestConfig` — so `StubPaymentGateway` is the `PaymentGateway` bean everywhere in the IT suite by
  construction; the real `@Service StripePaymentGateway` bean exists in the context but is never the one
  autowired behind the `PaymentGateway` interface.
- `PaymentWebhookIdempotencyIT` itself — the story's own named candidate — confirms this in its own code
  comment: `"First event: Case C (zero credit, full Stripe charge via StubPaymentGateway)"`. It never touches
  the real class.
- `CaptureReservationIT` (`BasePaymentIT`'s most payment-focused subclass) uses `@MockitoSpyBean
  PaymentGateway paymentGateway` — a spy wrapping the same `StubPaymentGateway` `@Primary` bean, stubbed via
  `doReturn(...).when(paymentGateway).chargeAndCapture(...)`. Its own Javadoc even name-checks
  `StripePaymentGateway` while testing the interface, not the implementation.
- `SessionPackPaymentResourceIT` fully replaces `PaymentGateway` with `@MockitoBean PaymentGateway
  paymentGateway` (a pure mock).
- A repo-wide grep for any test wiring the real bean (`new StripePaymentGateway`, a
  `StripePaymentGateway`-typed field, or a `@Qualifier` selecting it) returns zero hits outside the pure
  Mockito unit test `StripePaymentGatewayTest`.

**Why it matters:** no integration test in this suite can currently prove "no wiring regression" for the
`.setCurrency(configService.getString(...))` change, because none of them route through the code that would
call it. The task's parenthetical hedge ("verify which one... rather than assuming") correctly anticipates
that the named IT might be wrong, but doesn't anticipate that *no* IT is right — a dev could spend time
hunting for a nonexistent target, or worse, run `PaymentWebhookIdempotencyIT`/`CaptureReservationIT`, see
green, and believe the currency wiring was IT-verified when it was not (both exercise the stub).

**Recommendation:** drop the IT-level verification instruction, or replace it with an explicit statement
that no existing IT exercises the real gateway and that `StripePaymentGatewayTest`'s AC3 unit test (task 3.3)
is the only verification this change gets — which is an accurate and sufficient description of what AC3
actually delivers, just not what Task 3.4 currently claims.

---

## Finding 3 (Low-Medium) — AC1's own "run it 3-5 times" instruction names the wrong Maven goal, contradicting the story's own IT-execution gotcha and Task 1.3

**What's wrong:** AC1's Test Coverage paragraph says: "this file has no `@RepeatedTest` convention to reuse;
a manual repeated `mvn -o test` invocation is sufficient, no new annotation needed." But
`BookingServiceConcurrencyIT` is an `*IT` class, and this story's own Dev Notes state the opposite two
sections later: "`*IT` classes run under `maven-failsafe-plugin`, bound to `integration-test`/`verify`,
**not** `mvn test`. Use `mvn -o integration-test -Dit.test=<ClassName>`." Task 1.3, two paragraphs above AC1's
prose, already gets this right (`mvn -o integration-test -Dit.test=BookingServiceConcurrencyIT`).
`pom.xml`'s `maven-surefire-plugin` block has no custom `<includes>`, so default Surefire include patterns
apply (`**/*Test.java` and similar) — `*IT.java` files are excluded by convention and picked up only by
Failsafe's `integration-test`/`verify` goals.

**Why it matters:** `mvn -o test`, run as literally instructed, will not execute `BookingServiceConcurrencyIT`
at all and will still report `BUILD SUCCESS`. A dev following AC1's literal repeated-run instruction to
"build confidence the flakiness class is actually closed" could run this five times, see green five times,
and have verified nothing — the one check this AC exists to motivate.

**Recommendation:** fix AC1's Test Coverage paragraph to say `mvn -o integration-test
-Dit.test=BookingServiceConcurrencyIT#createBookingRequest_coachSuspendedAfterUnlockedRead_isRejectedWithCoachUnavailable`
(or the class-level form), matching Task 1.3 and the Dev Notes gotcha it otherwise correctly states
elsewhere in the same story.

---

## Summary

| # | Severity | Area | One-line issue |
|---|----------|------|-----------------|
| 1 | Medium | AC3 / migration | `V99` snippet omits `ON CONFLICT (key) DO NOTHING`, breaking the exact V93 convention AC3 says to mirror |
| 2 | Medium | Task 3.4 | No IT in the repo exercises the real `StripePaymentGateway` — the named/implied "end to end" IT check is unfindable, not just mis-named |
| 3 | Low-Medium | AC1 test plan | "`mvn -o test`" for a repeated manual run of an `*IT` class contradicts the story's own IT-execution gotcha and Task 1.3's correct command |

AC1/AC2's race-condition analysis (confirmed: `findByIdForUpdate` is the first and only lock-acquiring call
in both `createBookingRequest` and `acceptBooking` before the coach row lock is released, so the new
`pg_stat_activity`-polling helper cannot false-trigger on an earlier, unrelated lock wait), the
`pg_stat_activity` mechanism itself (confirmed safe against transaction MVCC snapshots, and confirmed both
threads share the same Postgres superuser role — `postgres`, per `SharedContainers.java` — so the restricted
per-role visibility Postgres applies to `pg_stat_activity.query`/`wait_event_type` for other roles' backends
never applies here), the `jakarta.persistence.lock.timeout`-has-no-effect context cited for why the booking
thread blocks indefinitely, AC3's currency-hardcoding analysis (`StripePaymentGateway.java:48` confirmed the
only `.setCurrency(...)` call site in the file), the migration id availability (`604` confirmed free; `603`
confirmed the current max), `StripePaymentGatewayTest`'s `stubCoachAndCommission()` mandatory-stub reasoning
(confirmed: all four existing `chargeAndCapture` tests reach the `setCurrency` line unconditionally before
any early-exception path, so the new stub cannot introduce an `UnnecessaryStubbingException`), and AC4's
ledger-tagging state (`deferred-work.md:1107`, `:1193`, and the new `:1691` entry all confirmed tagged
exactly as the story claims) were all independently re-verified and are accurate — no changes needed there.
