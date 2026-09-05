# Senior-dev audit — story skillars-deferred-92

Reviewer pass date: 2026-09-04. Method: every finding below was checked against `master` source at
`c2c47c1`, not against the ledger text. Findings are ordered by severity. A short list of premises
that **did** hold is at the end, to bound false-positive risk.

**Audit pass date: 2026-09-04 (second pass, independent).** Every finding in the original review was
re-verified against source. Each carries an `**AUDIT:**` verdict. Result: **13 of 15 findings
CONFIRMED**, **2 partially false-positive** (M2's parenthetical, L5's key-count bullet), **1
under-called and escalated to a blocker** (H1 → B1 — the finding is real but far more serious than the
review's framing), and **1 finding the review missed entirely** (N1 — AC17 rests on a false premise).
The review's own coverage gap is stated in § *What this review did not check*.

Verdict after audit: **the story is sound in direction, but it must not go to a dev agent unamended.**
One live production bug was surfaced by the review's own investigation and not recognised as one (B1);
one AC is built on a mechanism that does not behave as the story claims (N1); one AC's headline
verification does not test the property it names (M4).

---

## Blocker

### B1 — Booking reminder emails have never been sent. `EmailTemplate.BOOKING_REMINDER` is unreachable.

*Escalated from the original review's H1, which found the right code and drew too small a conclusion.*

The review correctly observed that `BookingEmailListener.onBookingReminder` (`:545`) carries no
`@TransactionalEventListener`, and framed the consequence as a scoping question — *"decide whether to
convert it … or leave it on its current (synchronous, in-scheduler-thread) path."* **It has no path.**

Verified at `c2c47c1`:

- `onBookingReminder` has **no annotation at all** — not `@EventListener`, not
  `@TransactionalEventListener`. `grep -c "@EventListener"` on the file returns 0.
- `BookingEmailListener` is a bare `@Component` (`:45-47`). It does **not** implement
  `ApplicationListener`.
- Spring dispatches `publishEvent` only to `@EventListener`/`@TransactionalEventListener` methods or
  `ApplicationListener` implementations. An unannotated public method on a `@Component` is never
  invoked, no matter that its parameter type extends `ApplicationEvent`.
- `BookingReminderScheduler.processReminderWindows()` (`@Scheduled(fixedDelay = 5, MINUTES)`,
  `@SchedulerLock`, `@Transactional`) publishes `BookingReminderEvent` at `:59` and `:73`.
- `EmailTemplate.BOOKING_REMINDER` is referenced in exactly **one** place in `src/main` — the
  unreachable method (`:562`, `:565`). There is no alternative sender.
- `onBookingReminder` is the **only** unannotated `public void on*` method across both email
  listeners. Every sibling is correctly annotated. This is a single omission, not a pattern.

So the event is published into the void on every 5-minute cycle, and no booking reminder — 24-hour
primary or 2-hour secondary — has ever been delivered to a parent or a coach.

**Three things make it silent, which is why it survived a full story, a 3-layer code review, and this
review's own first pass:**

1. `BookingReminderScheduler:60` logs `"Transitioned booking {} to UPCOMING and sent primary reminder"`
   — the log asserts a send that never happened.
2. `:58` calls `b.setPrimaryReminderSentAt(now)` on a managed entity inside `@Transactional`, so the
   **database records the reminder as sent**. Any "did we remind them?" query says yes.
3. `BookingEmailListenerTest` (`:101-125`) invokes `listener.onBookingReminder(event)` **directly**.
   The unit tests pass and prove the method body composes the right email — they cannot detect that
   nothing calls it. This is the codebase's own recorded antipattern ("a guard whose test passes
   without the guard") in its purest form: a *feature* whose test passes without the feature being
   wired.

`skillars-deferred-91`'s `OutboxService` javadoc (`:65`) names `onBookingReminder` as a live producer
that "previously published one marker per recipient" — so a prior review also assumed it was wired.
The assumption has been propagating.

**Fix:** this is no longer an AC4 sub-question. It needs its own AC: add
`@TransactionalEventListener(phase = BEFORE_COMMIT)` (consistent with AC4's target state, and
`BookingReminderScheduler` already publishes inside `@Transactional`, so the ambient transaction AC4
requires is present), plus an integration test that publishes the event through
`ApplicationEventPublisher` and asserts an outbox row appears — **not** another direct-invocation unit
test, which would reproduce the blind spot. Also correct the premature `log.info` and consider whether
`primaryReminderSentAt` should be stamped before or after the enqueue.

**AUDIT: CONFIRMED and escalated.** The review's underlying observation is correct and was the single
most valuable thing it found; only its severity assessment was wrong.

---

## High severity

### H1 — AC4: the "23 listener methods" count is wrong

AC4 instructs: *"Move each of the 23 listener methods from `@TransactionalEventListener(AFTER_COMMIT)`
to `BEFORE_COMMIT`."* Source says:

| File | `@TransactionalEventListener` annotations | `enqueueEmail` sites |
|---|---|---|
| `BookingEmailListener` | **19** | 20 — the 20th is `onBookingReminder` (`:545`), unannotated (see B1) |
| `SessionPackEmailListener` | **3** | 3 |

So there are **22** flippable transactional listeners, not 23 — exactly the number
`NotificationOutboxSupport`'s own javadoc already uses ("moving the enqueue into each of the 22
producing transactions"). The story's headline "23", its "20 … under 20 `@TransactionalEventListener`
methods", and its "3 under 4" are all off.

**AUDIT: CONFIRMED.** Root cause identified: the story's counts came from `grep -c
"@TransactionalEventListener"`, which counts the `import` line too (20 = 1 import + 19 annotations;
4 = 1 import + 3). The corrected figures are 19 + 3 = **22 listeners**, **23 enqueue sites**, the 23rd
being B1's unreachable one. Both numbers appear in the story and both must be stated separately —
"22 listeners, 23 enqueue sites" — because conflating them is what produced the error.

**Fix:** correct to 22 listeners / 23 enqueue sites; add `BookingReminderScheduler` to AC4's
producer-audit list; handle `onBookingReminder` under B1's new AC, not AC4.

### H2 — AC3 item 4: the five-pool assertion is unrunnable under the test profile

AC3.4 asks for a `@SpringBootTest` asserting all five pools report
`isWaitForTasksToCompleteOnShutdown() == true`. But `OutboxConfig` registers `outboxDrainPool` as a
`SyncTaskExecutor` whenever `app.outbox.drain-async=false`, and the test profile sets exactly that.
Under the default test profile the bean is a `SyncTaskExecutor` — no
`isWaitForTasksToCompleteOnShutdown()` method, cast fails.

**AUDIT: CONFIRMED, verbatim.** `OutboxConfig:68-71` declares a second `@Bean(name =
"outboxDrainPool")` guarded by `@ConditionalOnProperty(havingValue = "false")` returning
`new SyncTaskExecutor()`; `src/test/resources/application-test.yaml:124-125` sets
`app.outbox.drain-async: false`. The two beans are mutually exclusive by condition, so overriding the
property to `true` in the test does yield exactly one bean and no duplicate-definition error — the
review's suggested fix is sound as written.

---

## Medium severity

### M1 — AC3: the real shutdown deadline is docker-compose `stop_grace_period: 30s`, not the lifecycle-phase timeout

`ThreadPoolTaskExecutor.destroy()` runs during `ApplicationContext.close()` bean destruction, which is
**not** governed by `spring.lifecycle.timeout-per-shutdown-phase` (that bounds `SmartLifecycle`
`stop()` phases). AC3.2's framing is anchored to the wrong mechanism. The operative constraint is: sum
of all five pools' `awaitTerminationSeconds` **plus** context teardown must fit inside the 30 s
`stop_grace_period`, or the drain is SIGKILLed regardless.

**AUDIT: CONFIRMED.** Verified: **9** services in `docker-compose.yml` carry `stop_grace_period: 30s`;
`server.shutdown` and `spring.lifecycle.timeout-per-shutdown-phase` appear nowhere in
`src/main/resources/application*.yaml`. `ExecutorConfigurationSupport` is a `DisposableBean`, not a
`SmartLifecycle`, on Spring Framework 6.2.12 (Boot 3.5.16) — so the review's mechanism claim is
correct for this exact version, not merely in general. The review's added point about SIGTERM
forwarding is worth keeping: if the container entrypoint does not `exec`, the JVM never receives
SIGTERM and no amount of pool configuration matters.

### M2 — AC12 vs Task 4: direct contradiction on `setDefaultLocale`

AC12 item 5 is emphatic and correct: **do not** call `CookieLocaleResolver.setDefaultLocale(...)`. But
Task 4's checklist line (story `:411`) says *"`setFallbackToSystemLocale(false)` + explicit
`setDefaultLocale`"*. These cannot both stand.

**AUDIT: CONFIRMED — but the review's parenthetical is a FALSE POSITIVE.**

The contradiction is real and must be fixed: story `:411` still carries the instruction that AC12.5
(story `:207`) explicitly forbids. It is an editing residue — AC12 was revised during story creation
and Task 4 was not.

The review then adds: *"Also: `ReloadableResourceBundleMessageSource` has no `setDefaultLocale` method,
so 'explicit `setDefaultLocale`' has no valid reading here."* **This is wrong.** Verified by inspecting
`spring-context-6.2.12.jar`: `AbstractResourceBasedMessageSource` — which
`ReloadableResourceBundleMessageSource` extends — declares `setDefaultLocale`, `getDefaultLocale`,
`setFallbackToSystemLocale` and `isFallbackToSystemLocale`.

This matters beyond pedantry: it obscures a legitimate option. `messageSource.setDefaultLocale(
Locale.ENGLISH)` alongside `setFallbackToSystemLocale(false)` pins *which* bundle the fallback resolves
to (`messages_en.properties`) rather than relying on the basename bundle, and carries **none** of the
`Accept-Language` regression that the *resolver*'s `setDefaultLocale` does. The two methods share a
name and are otherwise unrelated. AC12.5 is right to forbid the resolver one; nothing forbids the
message-source one.

**Fix:** strike "explicit `setDefaultLocale`" from Task 4 and replace with
"`messageSource.setFallbackToSystemLocale(false)`; resolver `defaultLocale` left unset per AC12.5".
Optionally note `messageSource.setDefaultLocale(Locale.ENGLISH)` as a safe additional pin — and say
explicitly that it is a different method from the forbidden one, or the next reader re-derives this.

### M3 — AC4: the existing `catch (JsonProcessingException)` in `enqueueEmail` defeats AC4's intended semantic

`NotificationOutboxSupport.enqueueEmail` swallows a serialisation failure — logs
`[NOTIFICATION_EMAIL_ENQUEUE_FAILED]` and returns normally. Under AC4's design (`BEFORE_COMMIT` +
`MANDATORY`/`REQUIRED`), the intended, deliberately-accepted semantic is "a malformed payload rolls the
business transaction back". The existing catch means the opposite: the booking commits and the email is
silently lost — the exact failure mode AC4 exists to close.

**AUDIT: CONFIRMED, verbatim.** `NotificationOutboxSupport:77-84` catches `JsonProcessingException`,
logs at ERROR, and does not rethrow. The comment above it ("Loud log, not a silent drop — this is a
notification a committed transaction promised") is reasoning that was correct under `AFTER_COMMIT` and
becomes wrong the moment AC4 lands: after the flip the transaction has *not* committed yet, so the
choice is available and must be made deliberately. Strong finding — AC4 as written would ship a
contradiction between its stated semantic and its actual behaviour.

### M4 — AC4 item 5(b): the "rollback → 0 rows" test, as described, does not prove atomicity

A `BEFORE_COMMIT` listener only fires when the transaction is about to commit. If the business code
throws *before* commit, the listener never runs, so "rolled-back business transaction leaves zero outbox
rows" is trivially true. The assertion that actually proves the change is: **enqueue row written during
`beforeCommit`, then the COMMIT itself fails ⇒ 0 rows** — which needs a forced commit-time failure
(deferred constraint, serialization conflict, or an injected `TransactionSynchronization` that throws in
`beforeCommit` after the listener).

**AUDIT: CONFIRMED, and stronger than the review states.** The described test passes **identically
before and after the change** — today's `AFTER_COMMIT` listener also does not fire on a rollback, so
zero rows is the current behaviour too. It is therefore not merely weak evidence; it is a test that
cannot fail in either direction, i.e. precisely the antipattern the story's own Dev Notes §1 lists
three prior instances of (`deferred-13`, `deferred-15`, `uat-3` D11) and instructs the dev not to add a
fourth. AC4.5(b) as written would add the fourth.

### M5 — AC8: "non-`CONCURRENTLY` `CREATE INDEX`" does not take `ACCESS EXCLUSIVE`

It takes a `SHARE` lock — blocks writes and other DDL, allows reads. Requiring `lock_timeout` for it is
still right, but the rule's javadoc and the `migration-conventions.md` text must not call it
`ACCESS EXCLUSIVE`.

**AUDIT: CONFIRMED.** PostgreSQL lock levels for the DDL AC8 enumerates: `ALTER TABLE … ADD/DROP
COLUMN`, `ADD/DROP CONSTRAINT` and `DROP TABLE` take `ACCESS EXCLUSIVE`; `CREATE INDEX` takes `SHARE`;
`CREATE INDEX CONCURRENTLY` takes `SHARE UPDATE EXCLUSIVE`. AC8.1 lumps all of them under "DDL that
takes `ACCESS EXCLUSIVE`", which is wrong for one of three cases. The review's point that this is the
story's own overstatement warning in miniature is fair and worth keeping in the fix.

---

## Low severity

### L1 — AC4: `requestDrainAfterCommit()` invoked from a `BEFORE_COMMIT` context is an untested path

**AUDIT: CONFIRMED.** `NotificationOutboxSupport:76` calls `outboxService.requestDrainAfterCommit()`
immediately after `enqueue`, and `OutboxService.requestDrainAfterCommit` (`:69-70`) guards on
`TransactionSynchronizationManager.isSynchronizationActive()` and registers an `AFTER_COMMIT`
synchronization. Registering a synchronization from within `beforeCommit` is permitted by Spring, and
the guard will be satisfied — but it is a new path here, and `OutboxService:60-67`'s javadoc documents a
transaction-scoped dedup resource whose unbinding interacts with completion ordering. The AC4 IT should
assert the drain still fires, not just that the row is written.

### L2 — AC11 item 1: per-clause `NOT VALID` evaluation needs paren-aware parsing

`ALTER TABLE t ADD CONSTRAINT a CHECK (x IN (1,2,3)) NOT VALID, ADD CONSTRAINT b CHECK (...);` cannot
be split on `,` naively — the `CHECK` body carries commas. The story calls out the analogous parsing
hazard for AC7's identifier grep but not here.

**AUDIT: CONFIRMED.** Consistent with AC7.2's own "document the limitation rather than overstate the
guarantee" allowance, which AC11 should be granted explicitly too.

### L3 — AC12 item 3: the `error-messages` bundle is effectively vestigial

**AUDIT: CONFIRMED.** `src/main/resources/i18n/` contains exactly `error-messages.properties`,
`messages.properties`, `messages_{en,de,fr}.properties`. `error-messages.properties` is **1 line, 1
key**, with no locale variants. AC12.3 is near-moot as written; the dev should record that explicitly
(Dev Notes §6) and it is worth asking whether that basename should stay registered in
`MvcConfig.messageSource()` at all.

### L4 — Numbering and Task-header drift

**AUDIT: CONFIRMED.** Story AC order on disk is literally `1 … 26, 28, 29, 27` — AC28/AC29 were
inserted ahead of AC27 during the deploy-audit pass. Task 5's header reads `(AC: 15–26)` (story `:414`)
while its body now carries AC28 and AC29 checklist lines. Renumber, or make the header read
`15–26, 28, 29` and move AC27 last.

### L5 — Several "verified live" counts do not survive a re-count

**AUDIT: PARTIALLY CONFIRMED — one bullet is a FALSE POSITIVE.**

- Listener count **22, not 23** — **CONFIRMED** (see H1). `SessionPackEmailListener` is 3 listeners /
  3 enqueues, not "3 under 4" — **CONFIRMED**.
- Migration denominator — **CONFIRMED**. `ls src/main/resources/db/migration/*.sql | wc -l` = **121**
  files; the highest version is `V127` (the sequence has gaps). The story's "2 of 127" should read
  "2 of 121". The **2** (`V55`, `V57`) is correct.
- `messages.properties` gap **"~44 … vs the story's 46"** — **FALSE POSITIVE. The story's 46 is
  correct.** Re-derived with a properties-aware parser (skips blank lines, `#` and `!` comments,
  accepts both `=` and `:` as separators, trims keys): `messages_en` = 130 keys, `messages` = 86 keys,
  **46** present in the former and absent from the latter. The review's `~44` is a counting artefact,
  and its hedge — "regex-dependent; the dev will re-derive" — is the right instinct applied to the
  wrong side. AC12 may keep 46.

The review's closing advice on this finding ("re-count at implementation time, don't treat as fixed")
remains sound and survives the correction.

### L6 — AC9: the resumability predicate leans on the column Def8 flags as quirky

AC9.2 wants each chunk's `WHERE` scoped on `bandwidth_period_start` for idempotent resume; AC9.3 puts
Def8's `bandwidth_period_start = NOW()`-on-run-date drift explicitly out of scope. Compatible, but the
dev must confirm the predicate actually separates "already reset this cycle" from "not yet reset" given
that behaviour before relying on it.

**AUDIT: CONFIRMED as a legitimate caution.** Not a defect in the story; a dependency the story asserts
past without naming.

---

## Findings the review missed

### N1 — AC17 rests on a false premise: bare `@Async` does **not** resolve to `SimpleAsyncTaskExecutor` here

AC17 states that `VideoPhysicalDeletionListener`'s two bare `@Async` annotations mean *"Spring resolves
that to the application's default `AsyncTaskExecutor`; where no single candidate resolves, it falls back
to `SimpleAsyncTaskExecutor`, which creates an **unbounded** new thread per task"*, and calls it "an
unbounded thread-creation vector". Verified at `c2c47c1`, that is not what happens:

- A bean **named exactly `taskExecutor`** exists — `infrastructure/config/AsyncConfig.java:32`, a
  bounded `ThreadPoolTaskExecutor`.
- `AsyncExecutionAspectSupport.getDefaultExecutor` resolves in this order: unique `TaskExecutor` bean →
  on `NoUniqueBeanDefinitionException`, the bean literally named `taskExecutor` → only if that is
  absent does it fall back to `SimpleAsyncTaskExecutor`. With five executor beans present the second
  step matches, so bare `@Async` runs on the bounded `taskExecutor`.
- Spring Boot's auto-configured `applicationTaskExecutor` is `@ConditionalOnMissingBean(Executor.class)`
  and backs off entirely here, so it does not muddy the resolution.

So the stated failure mode does not exist. Two further facts the AC omits:

- `@EnableAsync` sits on `notification/config/AsyncConfig.java:23`;
  `infrastructure/config/AsyncConfig.java:21` carries a javadoc explicitly recording that it omits
  `@EnableAsync` deliberately. The wiring is intentional and documented, not accidental.
- There are **10** bare `@Async` sites in `src/main` (`VideoSseService` ×2, `TimelineEventListener` ×2,
  `RadarCompositeCalculationService`, `SluCalculationService`, `ReportGenerationService`,
  `HomeworkAssignmentService`, `SessionPlanService`, `VideoPhysicalDeletionListener` ×2 — the last
  being AC17's target). AC17 singles out one file for a codebase-wide convention, on a premise that
  does not hold, without saying why that file and not the other eight.

**This is the exact trap the story's own Dev Notes §2 warns about** — implementing against a premise
copied from the ledger (`skillars-4-3` W6, written 2026-06-17) without re-verifying it against current
wiring. The ledger entry may well have been true when the codebase had fewer executor beans.

**Fix:** rewrite AC17. The remediation (an explicit qualifier) is still defensible as hygiene — it
removes reliance on a two-step fallback that a future bean rename would silently break — but it must be
justified on those grounds, not on unbounded threads. And it should either cover all 10 sites or state
plainly why only these two. Note that AC3's graceful-shutdown work makes the correct answer more
valuable, since a bare `@Async` inherits whatever `taskExecutor` is configured to do on shutdown.

---

## What this review did not check

Stated so the coverage gap is explicit rather than implied (the story's own Dev Notes §6 asks for this;
a review owes the same discipline).

- **Checked in some depth:** AC1, AC3, AC4, AC8, AC10, AC12, AC15, AC16, AC21, AC22, plus AC9 and AC11
  at the level of their stated approach.
- **Not checked at all:** AC2, AC5, AC6, AC7, AC13, AC14, AC17 (until this audit's N1), AC18, AC19,
  AC20, AC23, AC24, AC25, AC26, AC27, AC28, AC29.
- N1 was found by auditing one of the unchecked ACs. The remaining 15 unchecked ACs have had **no**
  premise verification by either pass. Given that the two ACs examined outside the review's original
  scope yielded one blocker (B1, via H1) and one false premise (N1), the unchecked set should not be
  assumed clean.

---

## Premises that checked out (false-positive control)

Verified against source and **correct** as the story states them:

- **AC3** — `grep` for `setWaitForTasksToCompleteOnShutdown` / `setAwaitTerminationSeconds` returns
  zero hits; all five executor beans exist and none is profile-gated out of production. The
  `sendMailPool` bean (`AsyncConfig.threadPoolTaskExecutor`) genuinely calls
  `setRejectedExecutionHandler(new CallerRunsPolicy())` **after** `afterPropertiesSet()`, and
  `moderationTaskExecutor` directly above it orders the two calls correctly. **Bug confirmed** — and
  re-confirmed by this audit at the mechanism level: `ExecutorConfigurationSupport.afterPropertiesSet()`
  → `initialize()` builds the `ThreadPoolExecutor` from the fields set so far, and the inherited
  default is `AbortPolicy`; a later setter mutates only the wrapper's field. `sendMailPool` runs with
  `AbortPolicy`.
- **AC4 core mechanism** — `@TransactionalEventListener(BEFORE_COMMIT)` runs inside the producing
  transaction's synchronisation with the transaction still open, so switching `enqueueEmail` to
  `MANDATORY`/`REQUIRED` and relying on the write flushing atomically with the business work is sound.
  The listeners carry **no** `@Async`, so there is no detached-thread hazard. `RefundOutboxSupport` is
  a valid reference shape.
- **AC10** — `V20__platform_config.sql` declares `id BIGINT NOT NULL, PRIMARY KEY (id)` with no
  sequence / no `DEFAULT` / no `GENERATED`, and hand-seeds ids. Next free migration is `V128`.
  `BY DEFAULT AS IDENTITY` (not `ALWAYS`) is the right call and `pg_get_serial_sequence` works for
  identity columns.
- **AC12** — `messages.properties` = 86 keys vs `messages_en` = 130 (gap **46**, see L5);
  `MvcConfig.messageSource()` registers `error-messages` + `messages` with no
  `setFallbackToSystemLocale` / no `setUseCodeAsDefaultMessage`; `localeResolver()` is a bare
  `CookieLocaleResolver` with no `setDefaultLocale`. `MessageBundleParityTest` covers only
  `messages_de` / `messages_fr`. The AC12.5 warning about the **resolver**'s `setDefaultLocale`
  disabling `Accept-Language` is correct. (Severity caveat retained: the 500 manifests when the
  container JVM default locale is non-`en` and non-`de`/`fr`; "genuine live bug" is defensible but
  conditional, and the story says so.)
- **AC8** — only `V55` and `V57` set `lock_timeout`; `migration-conventions.md` mentions it once as
  precedent. Confirmed. (Denominator corrected to 121 — see L5.)
- **AC15** — `SecurityConfiguration` passes `PUBLIC_ENDPOINTS.toArray(new String[0])` to
  `requestMatchers(String...)`. The matcher-fidelity concern is real.
- **AC16** — `ROLE_ROUTES` is defined twice (`LoginPage.vue:145`, `router/index.js:38`) and read at six
  sites (1 + 5). `routes.js:232` is only a comment reference. Confirmed.
- **AC21** — `docs/dev-docs/index.html:152` does say "deep dive on the JWT refresh-token rotation
  flow", and `:150` links `lgtm-observability.md`, so the AC21 / AC28 coordination note is valid.
- **AC22** — `CoachPublicProfilePage.vue` `onMounted` at `:446`, page fetch at `:535`,
  `listCoachReviews` imported at `:370`. Structure matches; AC22.3's "shapes match" hedge is
  appropriately cautious.
- **AC1** — `ci.yml` contains no `prettier` / `eslint` / `lint` step. Confirmed.

---

## Required story amendments, consolidated

| # | Change | Source |
|---|---|---|
| 1 | New AC for the unreachable `onBookingReminder` — annotate, event-published IT (not direct-invocation), fix the premature `log.info` | B1 |
| 2 | AC4: "22 listeners / 23 enqueue sites"; add `BookingReminderScheduler` to the producer audit | H1 |
| 3 | AC3.4: require `app.outbox.drain-async=true` override for the five-pool test | H2 |
| 4 | AC3.2: re-anchor to `stop_grace_period: 30s`; add the SIGTERM-forwarding check | M1 |
| 5 | Task 4: strike "explicit `setDefaultLocale`"; note the message-source method is a *different*, safe one | M2 |
| 6 | AC4: decide the fate of `enqueueEmail`'s `catch (JsonProcessingException)` — rethrow or documented residual | M3 |
| 7 | AC4.5(b): prescribe a commit-time failure mechanism, or the test cannot fail | M4 |
| 8 | AC8: correct the lock levels — `CREATE INDEX` is `SHARE`, not `ACCESS EXCLUSIVE` | M5 |
| 9 | AC4 IT: assert the drain fires after a `BEFORE_COMMIT` enqueue | L1 |
| 10 | AC11: grant the same "document the limitation" allowance AC7.2 has | L2 |
| 11 | AC12.3: record that `error-messages` is 1 key with no variants | L3 |
| 12 | Renumber AC27–AC29; fix Task 5's header | L4 |
| 13 | AC8: denominator 121, not 127 | L5 |
| 14 | AC9.2: name the dependency on Def8's `bandwidth_period_start` behaviour | L6 |
| 15 | **AC17: rewrite — the `SimpleAsyncTaskExecutor` premise is false; rejustify or drop, and address all 10 bare `@Async` sites or say why not** | N1 |
