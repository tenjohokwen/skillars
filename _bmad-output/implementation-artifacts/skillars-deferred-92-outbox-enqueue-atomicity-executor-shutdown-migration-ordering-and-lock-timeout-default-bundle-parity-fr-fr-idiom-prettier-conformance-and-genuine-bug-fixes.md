# Story skillars-deferred-92: Outbox enqueue atomicity for the 23 transactional-email producers, graceful shutdown for every executor pool, the rolling-deploy hazards the convention doc names but does not enforce (expand/contract ordering, `SET lock_timeout`, unbatched DML, `platform_config` hand-assigned ids), the default-message-bundle parity hole, a full fr-FR idiom pass plus a frontend hardcoded-English sweep, repo-wide Prettier conformance with a CI gate, and the genuine one-off bugs still open in `deferred-work.md`

Status: review

<!-- v0.1 — story-creation pass (2026-09-04), immediately after skillars-deferred-91 merged (PR #146,
     master clean at c2c47c1). Project-owner-selected large cross-cutting bundle, same standing
     instruction as skillars-deferred-89/-90/-91. Seven decisions taken during creation (see
     § "Project-owner decisions folded in"). Anchor one-off bugs AC15–AC26. This story is deliberately
     very large per the project owner's standing "no small stories" instruction. -->

## Story

As the **Skillars engineering team**,
I want the durable outbox's own atomicity contract actually honoured by the 23 transactional-email producers that currently enqueue *after* their business commit, every `ThreadPoolTaskExecutor` in the application to drain instead of being killed on shutdown, the rolling-deploy hazards that `docs/deployment/migration-conventions.md` describes in prose but leaves entirely to human review turned into lint rules, the default backend message bundle brought to parity so a non-`de`/`fr`/`en` client stops falling into a 46-key hole, one idiom pass over fr-FR to match what `skillars-deferred-91` AC9 did for de-DE, every remaining user-visible hardcoded English string routed through `$t()`, and the repo's own mandatory Prettier rule made true and permanently enforced,
so that a crash between a booking commit and its email enqueue can no longer lose the email, a container restart can no longer abandon queued outbox drains and SLU writes, a `DROP COLUMN` can no longer ship in the same release as the code that stopped reading it without anyone noticing, a Spanish-locale request stops 500-ing on `security.accountLocked`, French users stop reading machine-translated phrasing, and `npx prettier --check` stops failing on 130 of ~150 frontend files.

## Story creation context

Created 2026-09-04 via the story-creation process, immediately after `skillars-deferred-91` merged (PR #146; `master` at `c2c47c1`, working tree clean). Project-owner instruction (unchanged from `skillars-deferred-89`/`-90`/`-91`):

> *"Go through `_bmad-output/implementation-artifacts/deferred-work.md` and put together a story that touches Genuine one-off bugs & gaps. If they are exhausted, get the ones that touch the following buckets in the order listed: Rolling-deploy column-drop ordering; i18n / hardcoded-English / de-DE native review as well as french locale; N+1 / query batching. If there is an item that needs a decision from me, let's go through together so that you get the decisions for the story. Do not create small stories. `deferred-work.md` has many issues that need to be handled, so small stories are not an option. You can even add unrelated tasks."*

**Genuine one-off bugs are not exhausted.** `skillars-deferred-91` closed its own set (AC12–AC19) but its *own* code review filed three fresh items on 2026-09-03, and the older, repeatedly-unread sections of the ledger still carry verified-live defects. AC15–AC26 anchor this story on twelve of them, every one re-verified against source during this creation pass rather than trusted from the ledger text.

The three named buckets each have real, *newly-scoped* residue, because `skillars-deferred-90`/`-91` closed the surface layer of each and exposed the layer underneath:

| Bucket | What `-90`/`-91` closed | What this story closes |
|---|---|---|
| Rolling-deploy | `MigrationLint` rules for `DROP … IF EXISTS`, `NOT VALID`, `CONCURRENTLY`, backport-below-baseline, repeatable hazards, inline FK; a written expand/contract standard; retroactive disposition of 6 grandfathered migrations | The **ordering** half — nothing verifies a dropped column stopped being read in a *prior* release (AC7); `SET lock_timeout` is prose-only, present in 2 of 127 migrations (AC8); unbatched full-table DML in migrations and in `BandwidthResetService` (AC9); `platform_config`'s hand-assigned PK, the recurring hazard V99's own comment documents at length (AC10); the lint's two self-documented blind spots (AC11) |
| i18n | de-DE informal→formal `Sie` rewrite (54 groups, 0 informal left); frontend bundle parity 1021/1021 × 3; `MessageBundleParityTest` for `messages_de`/`messages_fr` vs `messages_en` | `messages.properties` — the **default** bundle Spring falls back to — is missing **46** keys `messages_en` has, and no test covers it (AC12); fr-FR has never had the idiom pass de-DE got (AC13); user-visible hardcoded English still exists outside the bundles (AC14) |
| N+1 / batching | `getParentBookings`, `getConversations`' age-policy lookup, `getPublicProfile` (measured at a constant 8 round-trips, closed as leave-as-is) | `CoachPublicProfilePage.vue`'s wholly redundant second reviews fetch (AC22) — the last *measured, reachable* redundant-query item left in the ledger |

### Project-owner decisions folded in (2026-09-04)

Seven decisions taken during the story-creation discussion. Each was offered with options; the choice is recorded verbatim so the dev agent does not re-litigate them.

1. **Prettier — reformat everything + CI gate.** Verified live during creation: `npx prettier --check "src/**/*.{js,vue,scss,json}"` inside `src/frontend` reports **"Code style issues found in 130 files"**, and `.github/workflows/ci.yml` runs **neither** Prettier nor ESLint. `_bmad-output/project-context.md:69` makes Prettier mandatory. Owner chose: run `prettier --write` across the whole frontend source tree **and** add a CI job so it can never drift again. Options "changed-files-only", "flip `.prettierrc` to `semi: true`", and "leave it" were all rejected. → **AC1**
2. **i18n — all three of: default-bundle parity fix, fr-FR idiom pass, frontend hardcoded-English sweep.** The de-DE *native-speaker* review is explicitly **not** in scope: it needs a human native German speaker and stays as a carried-forward residual. → **AC12, AC13, AC14**
3. **Rolling-deploy — all four of: expand/contract ordering rule, `SET lock_timeout` rule, unbatched-DML rule, `platform_config` identity sequence.** → **AC7, AC8, AC9, AC10**
4. **Reliability — all three of: executor graceful shutdown, moving the 23 email enqueues inside their producing transactions, and migrating `ModerationSlaMonitorService` onto the generic outbox.** → **AC3, AC4, AC5**
5. **`VideoApprovalRequestRepository.autoRejectExpired()` — delete the dead query.** Wiring an auto-reject scheduler is a product feature, not a bug fix; whoever builds it should write the query correctly against the requirements of the day rather than inherit a broken one. → **AC20**
6. **`ConfigService` 5-minute TTL — document, do not change the TTL.** No behaviour change, no extra polling; the surprise is closed with an explicit note on the admin config surface and in the ops docs. → **AC24**
7. **Story size — deliberately very large.** Standing instruction; unrelated tasks explicitly welcome.

### Standing decisions recorded (not ACs — do not re-open)

- **de-DE native-speaker review** — owed, human-only, carried forward. `skillars-deferred-91` AC9 brought the whole bundle to consistent formal `Sie` with 0 informal forms and 0 placeholder drift; register/idiom verification by a native German speaker remains outstanding and is **not** something this story or any dev agent can close. Re-record it in `deferred-work.md` unchanged.
- **No frontend test framework.** ~6 recorded coverage gaps (`5-4` W9, `deferred-17`/`-18`/`-30`/`-37`/`-38`/`-43` D6) depend on standing up Vitest / Vue Test Utils. Out of scope — its own initiative. Every `.vue` / `.js` change here is verified by ESLint + Prettier + `quasar build` + code reading, this project's established path for frontend-only changes.
- **`sessionManager.js` `startSessionMonitoring()` early-return-with-no-timer path** — project owner previously decided "leave as documented" (`deferred-work.md`, skillars-deferred-90 section). Not revisited.
- **Completion-gated coach payout (`skillars-deferred-91` AC5 Part B)** — its own story. A Stripe destination-charges → separate-charges-and-transfers model change with `docs/architecture/payout-and-capture-pending.md` D1–D5 still unanswered. **Explicitly not in this story.**
- **`getPublicProfile`** — measured at a constant 8 round-trips by `skillars-deferred-91` AC11, guarded by `CoachPublicProfileQueryCountIT`. Leave as-is; do not "optimise" it.
- **`BookingService` `"Unknown Player/Coach/Parent"` fallbacks**, **two-sided disputes**, **`saveStep4` per-window timezone write**, **overnight availability windows**, **shared `stripe_customers` row across roles**, **`DrillMetadata.repDensity` primitive**, **`RadarDisplayService` skill-deactivation drop**, **`IMPROVEMENT_THRESHOLD` hardcoding**, **40k-char ledger-line hygiene** — all previously DECIDED wont-fix / DISMISSED. Not touched.
- **`PessimisticLockRetryer` connection-holding sleep**, **`@IanaTimezone` `Etc/GMT±N` acceptance**, **`V101` non-conforming-row remediation** — tracked, no action available without production data or a wider redesign. Not touched.

---

## Acceptance Criteria

### AC1 — Repo-wide Prettier conformance and a CI gate that keeps it

**Verified live during story creation** (`src/frontend`, `npx prettier --check "src/**/*.{js,vue,scss,json}"`): *"Code style issues found in 130 files."* `.prettierrc.json` is `{ semi: false, singleQuote: true, printWidth: 100 }`; the code largely uses semicolons. `_bmad-output/project-context.md:69` states **"Prettier is mandatory for all `.js`, `.vue`, `.scss`, and `.json` files."** `ci.yml` runs no formatting or lint check at all, which is why the rule has been violated for the entire life of the repo and was re-recorded three separate times (1.7a review, 1.7b review, skillars-deferred-90).

1. Run `npx prettier --write "src/**/*.{js,vue,scss,json}"` from `src/frontend`. Do **not** hand-edit files to conform; let the tool do it, so the diff is provably mechanical.
2. `npx prettier --check "src/**/*.{js,vue,scss,json}"` exits 0 with zero warnings.
3. `npx eslint .` still passes (Prettier's `semi: false` output must not break a conflicting ESLint rule — if it does, reconcile by disabling the conflicting stylistic ESLint rule, **not** by changing `.prettierrc.json`).
4. `quasar build` succeeds. **This is the load-bearing regression check for the whole AC** — a mechanical reformat that breaks the build is not mechanical.
5. `.github/workflows/ci.yml` gains a frontend-quality step (in the existing frontend job if one exists, else a new job) running **both** `npx prettier --check "src/**/*.{js,vue,scss,json}"` and `npx eslint .`, failing the build on either.
6. **Commit the reformat separately** from every other change in this story, with a message that says it is a mechanical `prettier --write` and touches no behaviour. A reviewer must be able to skip it wholesale.

> **Dev note — sequencing.** Do AC1 **first**, before any other `.vue`/`.js` edit in this story. Otherwise every later frontend change lands in the reformat commit and the "provably mechanical" property is lost.

### AC2 — The transactional-email `data` map stops being type-lossy across the outbox

`NotificationOutboxSupport.enqueueEmail` serialises `Map<String,Object>` to JSON and `NotificationEmailOutboxHandler` deserialises it back, so any non-`String` value returns as `String`/`Integer`/`Double`/`List`/`Map`. Verified a non-issue **today**: all 69 `data.put(...)` call sites across `BookingEmailListener` and `SessionPackEmailListener` put a `String` or a `List<String>`. It is latent, not live — the first `Instant` or `BigDecimal` value added to a template silently degrades (an ISO blob, or `40.0` where the template wants `40.00`) with nothing to catch it.

- Add a serialisation round-trip assertion: a test that walks every `data` map produced by the two listeners (or, if that is impractical to enumerate, asserts the *contract* — that every value put into an email `data` map is a `String` or `List<String>`) and fails if a value's post-round-trip type differs from its pre-round-trip type.
- Document the constraint in `NotificationOutboxSupport`'s javadoc: **email template data is string-typed by contract; format numbers and instants at the producer, not in the template.**
- Do **not** redesign the payload into a typed record — that is a larger change and the current contract holds. The deliverable is the guard, not the redesign.
- **Reference:** `deferred-work.md` § *code review of skillars-deferred-91 (2026-09-03)*; `src/main/java/com/softropic/skillars/platform/notification/service/NotificationOutboxSupport.java:45`.

### AC3 — Every `ThreadPoolTaskExecutor` drains on shutdown instead of being killed

**Verified live during story creation:** `grep -rn "setWaitForTasksToCompleteOnShutdown\|setAwaitTerminationSeconds" --include="*.java" src/main/java` returns **zero hits**, while five `ThreadPoolTaskExecutor` beans exist:

| Bean | File | What is abandoned on `shutdownNow()` |
|---|---|---|
| `outboxDrainPool` | `platform/outbox/config/OutboxConfig.java:40` | queued + in-flight **durable-outbox drains** — money refunds, notification emails, SLU snapshot writes |
| `sluRetryExecutor` | `platform/development/config/DevelopmentConfig.java:64` | up to 10 queued + 4 in-flight SLU + snapshot batches |
| `moderationTaskExecutor` | `platform/notification/config/AsyncConfig.java:38` | queued moderation work |
| `sendMailPool` | `platform/notification/config/AsyncConfig.java:51` | queued mail sends |
| `taskExecutor` | `infrastructure/config/AsyncConfig.java:34` | the shared `@Async` pool |

On context close, `ExecutorConfigurationSupport.destroy()` calls `shutdownNow()`: queued tasks are discarded, in-flight `@Backoff` sleeps get an `InterruptedException` that matches no `retryFor`/`@Recover`, and `MdcDecorator` swallows the result with a generic "Exception thrown from detached thread" log. The ledger recorded this as "a pre-existing project-wide convention" when three pools existed; `skillars-deferred-91` then added a **fourth pool that drains a durable outbox**, which turns a convention into a reliability defect — the whole point of the outbox is that a row is never lost, and an abandoned drain leaves rows behind (recoverable by the sweeper on the next boot, but the abandonment is silent).

1. All five beans set `setWaitForTasksToCompleteOnShutdown(true)` and `setAwaitTerminationSeconds(n)` with an `n` justified in a comment per pool (short for `taskExecutor`/`moderationTaskExecutor`; long enough for `outboxDrainPool` and `sluRetryExecutor` to finish a chunk).
2. **Size the awaits against `stop_grace_period`, not against the Spring lifecycle timeout.** `ExecutorConfigurationSupport` is a `DisposableBean`, not a `SmartLifecycle`, so `destroy()` runs during context bean destruction and is **not** governed by `spring.lifecycle.timeout-per-shutdown-phase` (that bounds `SmartLifecycle.stop()` phases). Verified on this project's Spring Framework 6.2.12 / Boot 3.5.16. The operative wall-clock bound is docker-compose's `stop_grace_period: 30s`, set on **9** services in `docker-compose.yml`; after it, Docker sends SIGKILL regardless of what any pool is waiting for. So: sum of all five pools' `awaitTerminationSeconds` **plus** context teardown must fit inside 30 s with margin, or raise `stop_grace_period`. State the arithmetic in a comment.
   - Neither `server.shutdown` nor `spring.lifecycle.timeout-per-shutdown-phase` is set anywhere in `src/main/resources/application*.yaml`. Setting `server.shutdown=graceful` is worth doing so in-flight HTTP requests drain too, but it is not what bounds the pools.
   - **Verify the JVM actually receives SIGTERM.** If the container entrypoint does not `exec` (or use an init that forwards signals), the Spring shutdown hook never runs and none of this configuration has any effect. Check it and record the result — otherwise the whole AC is theatre.
3. **Bug found in passing — `sendMailPool` calls `setRejectedExecutionHandler(new CallerRunsPolicy())` *after* `afterPropertiesSet()`** (`platform/notification/config/AsyncConfig.java`, the `threadPoolTaskExecutor()` bean). `ExecutorConfigurationSupport.afterPropertiesSet()` → `initialize()` builds the underlying `ThreadPoolExecutor` from the fields set *so far*; a later setter mutates the Spring wrapper's field but not the live executor. The pool therefore runs with the default `AbortPolicy`, not `CallerRunsPolicy` — a full queue throws `RejectedExecutionException` at the caller instead of running the mail send inline. Move the call **before** `afterPropertiesSet()` and add a test asserting `((ThreadPoolTaskExecutor) bean).getThreadPoolExecutor().getRejectedExecutionHandler()` is a `CallerRunsPolicy`. Compare with `moderationTaskExecutor` directly above it, which orders the two calls correctly.
4. A test (`@SpringBootTest`, or a focused config test) asserts each of the five pools reports `isWaitForTasksToCompleteOnShutdown() == true` — so a sixth pool added later without it is caught by intent, not by luck. **It must run with `app.outbox.drain-async=true` overridden** (e.g. `@TestPropertySource`): `OutboxConfig:68-71` registers a second `@Bean(name = "outboxDrainPool")` returning a plain `SyncTaskExecutor` under `@ConditionalOnProperty(havingValue = "false")`, and `src/test/resources/application-test.yaml:124-125` sets exactly that — so under the default test profile the bean is a `SyncTaskExecutor`, has no such method, and the cast fails. The two beans are mutually exclusive by condition, so the override yields one bean and no duplicate definition.
5. **Reference:** `deferred-work.md` § *code review of skillars-deferred-86*, `sluRetryExecutor` bullet.

### AC4 — The 23 transactional-email enqueues move **inside** their producing transactions

`skillars-deferred-91` AC1 defines the outbox contract as *"the producer writes its outbox row inside the business transaction"* — that atomicity is the entire reason a transactional outbox exists. AC3 of that story routed all email sends through the outbox and correctly closed the nested-`AFTER_COMMIT` silent drop, but it could not honour AC1's contract: the producing listeners are themselves `@TransactionalEventListener(AFTER_COMMIT)`, so `NotificationOutboxSupport.enqueueEmail`'s `REQUIRES_NEW` transaction starts **after** the business transaction has already committed. A crash or DB failure in that window still loses the email, and the loss is swallowed by the listeners' `catch (Exception e) { log.error(...) }`.

**Verified live (corrected by the 2026-09-04 review audit — the original figures counted the `import` line):** `BookingEmailListener` has **19** `@TransactionalEventListener` methods and **20** `enqueueEmail` sites; `SessionPackEmailListener` has **3** and **3**. So there are **22 flippable transactional listeners** and **23 enqueue sites** — the 23rd is `BookingEmailListener.onBookingReminder` (`:545`), which carries no annotation at all and is **unreachable**. It is handled by **AC29**, not here. Keep the two numbers distinct; conflating them is what produced the original error. Producers that publish these events: `BookingService`, `BookingCompletionService`, `QuickCompleteTimeoutService`, `BookingPaymentPersistenceService`, `PaymentPendingSweeper`, `PackSessionService`, `SessionPackForfeitureScheduler`, `SessionPackExpiryNotifier`, `AdminCoachEnforcementService`, and — added by the 2026-09-04 review audit — **`BookingReminderScheduler`** (`:59`, `:73`), which AC29 brings into scope. That makes **ten** producers to audit, not nine.

**Prescribed approach — change the listener phase, not the nine producers.** Move each of the **22** transactional listener methods from `@TransactionalEventListener(phase = AFTER_COMMIT)` to `@TransactionalEventListener(phase = BEFORE_COMMIT)`. A `BEFORE_COMMIT` listener runs inside the producing transaction's synchronisation, so the `enqueue` write is flushed and committed atomically with the business work — which is exactly what AC1 asks for — without touching a single producer service. Correspondingly:

1. `NotificationOutboxSupport.enqueueEmail` drops `Propagation.REQUIRES_NEW` and becomes `Propagation.MANDATORY` (preferred: it makes "there must be an ambient transaction" a compile-of-runtime contract rather than a hope) or plain `REQUIRED`. If `MANDATORY` is chosen, every call path must be verified to have an ambient transaction — that is the point of choosing it.
2. **Decide the fate of `enqueueEmail`'s existing `catch (JsonProcessingException)` — this is not optional.** `NotificationOutboxSupport:77-84` currently logs `[NOTIFICATION_EMAIL_ENQUEUE_FAILED]` and returns normally. That was correct under `AFTER_COMMIT` (the transaction had already committed, so a loud log was the only option left). After the flip it is **the opposite of this AC's intent**: the business transaction is still open, so swallowing means the booking commits and the email is silently lost — exactly the failure AC4 exists to close. Either rethrow (accept a business rollback on a serialisation failure, consistent with item 3 below) or keep swallowing and record the residual explicitly with its new, narrower scope. Update the comment above the catch either way; it currently states reasoning that no longer applies. AC2's guard shrinks this surface but does not remove it.
3. **Failure semantics change and must be handled deliberately.** A throw inside a `BEFORE_COMMIT` listener rolls the business transaction back. That is the correct atomic semantic for an outbox — but it means a malformed email payload could now roll back a booking. Keep payload construction defensive: build the `Map`, serialise, and enqueue with no I/O, no external call, and no lookup that can fail on data the business transaction did not already validate. If any listener currently does a repository read to build its payload, either hoist that read into the event's payload or keep that specific listener on `AFTER_COMMIT` and record why.
4. **`fallbackExecution`.** `BEFORE_COMMIT` listeners do not fire without an ambient transaction. Verify every one of the **ten** producers publishes inside `@Transactional` (spot-check each; `SessionPackExpiryNotifier` is the known-bad case — `deferred-work.md` records it as **not** `@Transactional` and opening no `TransactionTemplate`, which is why its expiry-warning emails were silently discarded). Any producer that publishes outside a transaction must be fixed to publish inside one, or its listener stays `AFTER_COMMIT` with a recorded reason.
5. Update `NotificationOutboxSupport`'s javadoc: the long "**Still open:** a crash … in the window between the business commit and this enqueue's commit still loses the email" block is now stale and must be rewritten to state what actually holds.
6. **Tests — and read this before writing them, because the obvious test is worthless.** A `BEFORE_COMMIT` listener only fires when the transaction is *about to commit*. So "business transaction throws before commit ⇒ zero outbox rows" is **trivially true and passes identically before and after this change** — today's `AFTER_COMMIT` listener does not fire on a rollback either. That test cannot fail in either direction; it is the fourth instance of the antipattern Dev Notes §1 names three prior cases of. Do not write it and call the AC verified.

   The assertion that actually proves atomicity is: **the enqueue row is written during `beforeCommit`, and then the COMMIT itself fails ⇒ zero rows.** Prescribed mechanisms, pick one and say which: a `DEFERRABLE INITIALLY DEFERRED` constraint violated at commit; a serialization conflict; or a `TransactionSynchronization` registered *after* the listener that throws in its own `beforeCommit`. Pair it with the straightforward commit case (commits ⇒ exactly one row) and with L1's drain assertion below.
7. **Assert the drain still fires.** `enqueueEmail:76` calls `outboxService.requestDrainAfterCommit()`, which registers an `AFTER_COMMIT` synchronization and is guarded on `TransactionSynchronizationManager.isSynchronizationActive()`. Registering a synchronization from inside `beforeCommit` is permitted by Spring and the guard will be satisfied — but it is a new path here, and `OutboxService:60-67` documents a transaction-scoped dedup resource whose unbinding interacts with completion ordering. The IT must assert the drain actually runs after a `BEFORE_COMMIT` enqueue, not merely that the row was written.
8. `RefundOutboxSupport` (AC2 of deferred-91) is **already correct** — it is called inside the listener's own `REQUIRES_NEW`. Use it as the reference shape; do not "fix" it.
9. **Reference:** `deferred-work.md` § *code review of skillars-deferred-91 (2026-09-03)*, third bullet; `story-review.md` §§ H1, M3, M4, L1.

### AC5 — `ModerationSlaMonitorService` re-queues through the outbox

`ModerationSlaMonitorService` publishes `VideoModerationRetryEvent` (`:93`) and `VideoModerationAdminAlertEvent` (`:72`) directly via `ApplicationEventPublisher`. If the app crashes after `findScanningOlderThan()` returns but before the events publish, that cycle's retry intents are lost; the next cycle recovers, so the impact is bounded — but it is the last uncovered case in the ledger's `AFTER_COMMIT`-reliability catalogue and it is now mechanical, because `skillars-deferred-91` built the generic outbox.

1. Route both publish sites through the generic outbox with a new handler (`VIDEO_MODERATION_RETRY`), following `SluSnapshotOutboxSupport`/`SluSnapshotOutboxHandler` as the reference pair.
2. The handler is **idempotent**: a repeat retry-request for a video already out of `SCANNING` is a documented no-op, not an error.
3. Unit test for the handler's idempotence + an IT for the enqueue→drain round-trip.
4. **Reference:** `deferred-work.md` § *code review of skillars-6-3-content-moderation-pipeline (2026-06-22)*, W3.

### AC6 — `deferred-work.md`'s stale `AFTER_COMMIT` catalogue is corrected

The ledger's `skillars-deferred-91` residuals section lists the un-migrated listeners as a deliberate scope boundary. AC4 and AC5 change that list. Rewrite the bullet to state exactly what is now on the outbox and what deliberately is not (`PendingBlobDeletionService`'s own outbox; `CancellationRefundService`'s `packSessionService.restoreSession(...)` calls; registration-email listeners; SSE / video / development / admin / reviews / session listeners that are not money- or compliance-relevant). Do not leave a superseded catalogue in place — that is precisely the drift this file's own "How to read this file" section warns about.

---

### AC7 — A lint rule for expand/contract **ordering**, not just expand/contract *shape*

`docs/deployment/migration-conventions.md` § *The expand / contract standard* prescribes the two-release ordering; `MigrationLint` enforces the *shape* of individual statements (`IF EXISTS`, `NOT VALID`, `CONCURRENTLY`) but has no rule about **ordering**, so `skillars-11-3` D2's exact defect — *"code deletion and the destructive `DROP TABLE` migration ship together with no staged rollout"* — passes the lint clean today. During a rolling deploy the old pods are still reading the column when the new pod's migration drops it.

1. Add `Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP` to `MigrationLint`. Any migration containing `DROP TABLE` or `DROP COLUMN` must carry a machine-readable marker naming the release/migration in which reads of that object were removed — e.g. `-- migration-lint: drop-prepared-in: V123` — in its header block. A `DROP` with no such marker is a violation.
2. **Make the marker load-bearing, not decorative:** the rule additionally greps `src/main/java` and `src/main/resources` for the dropped identifier (table or column name) and reports a violation if any live reference remains. A marker that claims preparation while the code still reads the column is the failure mode worth catching.
   - **Practicality constraint the dev agent must handle, not discover the hard way:** column names like `id`, `status`, `amount` are far too generic to grep usefully and will produce a wall of false positives. Match on the **qualified** form the codebase actually uses (`snake_case` column name co-occurring with the table name in the same file, plus the camelCase JPA field name on the mapped entity), and provide a documented per-migration allowlist marker for the residual noise. If a qualified match still proves unworkable for a given identifier, it is acceptable for the rule to fall back to requiring the marker alone for that case — **but say so in the rule's javadoc**, so the guarantee the rule actually provides is never overstated. This project has three recorded instances of a guard believed stronger than it was; do not add a fourth.
3. Grandfathering: the rule applies from the next migration version onward, consistent with `GRANDFATHER_BASELINE`. Do **not** retrofit V96/V97 — `skillars-deferred-91` AC8 already dispositioned every pre-`V122` migration; re-opening them contradicts a shipped decision.
4. Fixtures: a valid fixture (marker present, no live references) and an invalid fixture per failure mode (no marker; marker present but a live reference remains). `MigrationConventionLintTest.invalidFixtures_triggerEveryRule` must trigger the new rule, and `widenedRules_triggerOnTheirOwnFixtures` gains a pinned assertion for it.
5. `docs/deployment/migration-conventions.md` § *What the guard now covers* is updated; the § *PR checklist* gains the marker.
6. **Reference:** `deferred-work.md` § *code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)*, D2.

### AC8 — `SET lock_timeout` becomes a convention with a lint rule

**Verified live:** only **2** of **121** migrations set `lock_timeout` (`V55`, `V57`). (121 is the file count on disk; the highest version is `V127` — the sequence has gaps, so do not use 127 as a denominator.) `migration-conventions.md:71` mentions it once, as a precedent, not a requirement. Every `ACCESS EXCLUSIVE` migration in this repo therefore waits **indefinitely** for its lock — and worse, a blocked `ALTER TABLE` queues behind a long-running reader while every subsequent query on that table queues behind the `ALTER`, so one slow `SELECT` can stall the whole table and exhaust the connection pool. This is the concrete mechanism behind the three separate ledger entries for `V60`, `V94` and `V97`.

1. Add `Rule.MISSING_LOCK_TIMEOUT`: any migration containing DDL that takes a table lock blocking concurrent DML or DDL must contain a `SET lock_timeout` (or `SET LOCAL lock_timeout`) statement before it. **Get the lock level right per case in both the rule's javadoc and the doc — do not describe them all as `ACCESS EXCLUSIVE`:**
   - `ALTER TABLE … ADD/DROP COLUMN`, `ADD/DROP CONSTRAINT`, `DROP TABLE` → `ACCESS EXCLUSIVE` (blocks reads too)
   - `CREATE INDEX` (non-`CONCURRENTLY`) → `SHARE` (blocks writes and other DDL, **allows reads**)
   - `CREATE INDEX CONCURRENTLY` → `SHARE UPDATE EXCLUSIVE`
   All three deserve a bounded wait, but this story warns three separate times against guards and docs that overstate what they cover; getting this wrong in the guard's own javadoc would be that failure in miniature. Opt-out marker `-- migration-lint: allow-unbounded-lock-wait` with a stated reason, matching the existing opt-out convention.
2. Add the convention to `migration-conventions.md` with the *why* (the queue-behind-the-blocked-ALTER mechanism above, stated explicitly — the doc currently gives the rule without the reason, which is why it was ignored) and to the PR checklist.
3. Fixtures + pinned `widenedRules_triggerOnTheirOwnFixtures` assertion, as AC7.
4. Grandfathered below the baseline. Existing migrations are not rewritten.
5. **Reference:** `deferred-work.md` — `skillars-6-6` W3 (`V60`), *code review of skillars-uat-3* D13 (`V94`), *code review of skillars-deferred-33* (`V97`).

### AC9 — Unbatched full-table DML: a lint rule, and the two live instances fixed

1. **Lint rule** `Rule.UNBATCHED_DML`: an `UPDATE` or `DELETE` in a migration with no `WHERE` clause, or with a `WHERE` that cannot bound the row count, is a violation unless it carries `-- migration-lint: allow-full-table-dml` with a reason. Fixtures + pinned assertion, as AC7/AC8. Grandfathered below the baseline.
2. **`BandwidthResetService.resetMonthlyBandwidth` (live code, not a migration)** — a single unpartitioned `UPDATE` over all `video_quotas` rows at the month boundary, which locks every row and blocks concurrent `reserve()` calls (`Def10`). Rewrite as a bounded chunked loop (`UPDATE … WHERE id IN (SELECT id … LIMIT n)` repeated until zero rows affected), matching the chunking shape the outbox drainer already uses. Add a test asserting more than one chunk runs for a row count above the chunk size.
   - **Each chunk must commit in its own transaction** (a `REQUIRES_NEW` chunk-processor bean, exactly as the outbox drainer does — see `PendingBlobDeletionChunkProcessor`). A chunked loop inside one long transaction holds every row lock until the end and is strictly *worse* than the single `UPDATE` it replaces. Do not self-invoke a `@Transactional` method from within the same bean; that is the mistake deferred-90's review already forced out of the outbox once.
   - **Raise `@SchedulerLock`'s `lockAtMostFor` to cover the longer wall-clock runtime.** A chunked loop takes materially longer than one statement; if `lockAtMostFor` expires mid-run a second node starts a concurrent reset. State the new value and the row count it assumes.
   - The loop must be idempotent and safely resumable — a crash mid-run leaves some rows reset and some not, and the next run must complete rather than double-reset. Scope each chunk's `WHERE` on `bandwidth_period_start` (or equivalent) so an already-reset row is not picked up again. **Confirm that predicate actually separates "already reset this cycle" from "not yet reset" before relying on it** — `Def8` (item 3 below) records that `bandwidth_period_start` is stamped with the actual run date rather than the period boundary, which is exactly the column this resumability rule leans on. The two are compatible (a row with an older `period_start` still needs resetting) but the dependency is real and must be verified, not assumed.
3. `Def8`'s known period drift (`bandwidth_period_start` set to `NOW()` on the actual run date rather than the 1st) is **explicitly out of scope** — it is a separate, accepted design note. Do not change it while you are in the file.
4. `V98`'s backfill is already applied and is grandfathered; do **not** rewrite it.
5. **Reference:** `deferred-work.md` — *code review of skillars-6-1 … Run 2*, `Def10`; *code review of skillars-deferred-40*, `V98` bullet.

### AC10 — `main.platform_config.id` gets an identity sequence

`main.platform_config.id` is a `BIGINT PRIMARY KEY` with **no** sequence (`V20__platform_config.sql:2,8`), so every migration that seeds the table hand-picks the next free id. `V99`'s own header spends six lines explaining the hazard: the `ON CONFLICT` target is `key` (a *different* unique constraint), so an id collision raises a PK violation the `ON CONFLICT (key)` clause never sees, **failing Flyway on every database that has run a later migration reusing that id.** The ledger carries five separate instances of the same worry (`V25` 112–114, `V33` id=39, `V46` 70–72, `V48`, `V53` 117–132).

1. New migration (`V128`): attach an identity to `main.platform_config.id`, started safely above the current maximum id, computed in the migration — never hardcoded.
   - **Use `ADD GENERATED BY DEFAULT AS IDENTITY`, not `ALWAYS`.** `ALWAYS` rejects any `INSERT` that supplies an explicit `id`, which would break **every existing seed migration** (`V20`, `V25`, `V33`, `V46`, `V48`, `V53`, `V93`, `V99` …) the moment Flyway replays them on a fresh database — i.e. it would break CI and every new environment while continuing to appear fine on already-migrated ones. `BY DEFAULT` lets the historical explicit-id inserts keep working and supplies a value only when one is omitted.
   - Seed from the live max, e.g. `SELECT setval(pg_get_serial_sequence('main.platform_config','id'), (SELECT COALESCE(max(id), 0) FROM main.platform_config));`
   - This migration takes `ACCESS EXCLUSIVE` on `main.platform_config`, so it must itself satisfy AC8's `SET lock_timeout` rule. Verify it against the rule you just wrote.
2. Future seeds omit `id` entirely. Update `migration-conventions.md` to say so, and the PR checklist.
3. Optional but preferred: a `MigrationLint` rule flagging an `INSERT INTO main.platform_config` that supplies an explicit `id`, so the old pattern cannot come back.
4. The migration itself must satisfy every existing lint rule (it is above the baseline).
5. **Reference:** `deferred-work.md` § *code review of skillars-deferred-53*; `src/main/resources/db/migration/V99__payment_currency_config.sql:1-10`.

### AC11 — The two blind spots `migration-conventions.md` admits under *"What the guard still cannot catch"*

The doc names both and concludes *"Review still owns these."* Both are mechanically closable:

1. **Multi-constraint statement.** The `NOT VALID` rule splits on `;` and asks only whether `NOT VALID` appears *somewhere* in the statement, so `ALTER TABLE t ADD CONSTRAINT a CHECK (…) NOT VALID, ADD CONSTRAINT b CHECK (…);` passes even though `b` validates. Evaluate each `ADD CONSTRAINT` clause within a statement independently. **This needs balanced-paren scanning, not a split on `,`** — a `CHECK` body carries its own commas (`CHECK (x IN (1,2,3))`). AC7.2's allowance applies here too: if a robust parse proves impractical, implement what you can and **state the limitation in the rule's javadoc** rather than letting the rule imply a guarantee it does not deliver.
2. **Whole-file opt-out.** `-- migration-lint: allow-*` markers are matched against the whole file, so one opt-out silences that rule for every statement in the migration. Scope each marker to the statement it precedes (or, minimally, to the line range following it until the next `;`).
3. Fixtures for both + pinned assertions. Update the doc: move both bullets out of *"cannot catch"* into *"now covers"*, and leave the section honest about whatever genuinely remains.

---

### AC12 — `messages.properties` (the default fallback bundle) reaches parity, and a test keeps it there

**Verified live during story creation.** `MvcConfig.messageSource()` registers basenames `classpath:/i18n/error-messages` and `classpath:/i18n/messages`, and `localeResolver()` is a `CookieLocaleResolver` with **no** `setDefaultLocale`, so an unset locale cookie falls through to the request's `Accept-Language`. `ReloadableResourceBundleMessageSource` resolves a missing key by falling back to the basename bundle — `messages.properties`. Key counts:

| Bundle | Keys |
|---|---|
| `messages_en.properties` | 130 |
| `messages_de.properties` | 130 |
| `messages_fr.properties` | 130 |
| **`messages.properties`** | **86** |

**46 keys** present in `messages_en` are absent from the default bundle, including `security.accountLocked`, `security.otpResendInProgress`, `security.emailTokenExpired`, `security.emailTokenInvalid`, `security.emailTokenUsed`, and **every** `email.*` template key (all coach/parent/player OTP + verification emails, every booking email title, every profile-change notice). `MessageBundleParityTest` covers only `messages_de` and `messages_fr` against `messages_en`, so nothing catches it.

Consequence: a client whose resolved locale is neither `de`, `fr` nor the JVM's own default (`fallbackToSystemLocale` is on by default, and a container's default locale is environment-dependent and not pinned anywhere in this repo) resolves these keys from `messages.properties` and gets a `NoSuchMessageException` — a 500 on an account-lockout response, and a template failure on every transactional email.

1. Bring `messages.properties` to full key parity with `messages_en.properties` (English text — it is the fallback, not a locale).
2. Extend `MessageBundleParityTest` with a third case asserting the **default** bundle matches `messages_en` on keys *and* placeholders, using the same `assertParity` helper. This is the part that stops the hole reopening.
3. `error-messages.properties` is **1 line / 1 key with no locale variants** (verified 2026-09-04 — `src/main/resources/i18n/` holds only it plus `messages{,_en,_de,_fr}.properties`), so this sub-item is near-moot. Record that finding explicitly per Dev Notes §6 rather than leaving a reviewer unsure whether the audit ran, and raise whether that basename should stay registered in `MvcConfig.messageSource()` at all.
4. Pin the *message-source* fallback so resolution stops depending on the container's JVM default locale: `messageSource.setFallbackToSystemLocale(false)`. With AC12.1 done, a key missing from `messages_de`/`messages_fr` then resolves deterministically from `messages.properties` on every host instead of from whatever `Locale.getDefault()` happens to be.
5. **Do NOT call `CookieLocaleResolver.setDefaultLocale(...)`.** This is a trap. `CookieLocaleResolver.determineDefaultLocale(request)` returns `this.defaultLocale` **if set**, and falls through to `request.getLocale()` (i.e. `Accept-Language`) only when it is `null`. Setting it would therefore **disable Accept-Language negotiation entirely** — a German browser arriving with no locale cookie gets German today and would get English afterwards. That is a user-visible regression, not a hardening. Leave the **resolver's** `defaultLocale` unset; AC12.4 alone makes resolution deterministic.
   - Do not confuse it with the identically-named method on the *message source*. `AbstractResourceBasedMessageSource` (which `ReloadableResourceBundleMessageSource` extends) also declares `setDefaultLocale` — verified in `spring-context-6.2.12.jar`. `messageSource.setDefaultLocale(Locale.ENGLISH)` is **safe** and optional: it pins which bundle the fallback resolves to and carries none of the `Accept-Language` regression. Two different classes, same method name, opposite risk. Only the `CookieLocaleResolver` one is forbidden.
6. An IT driving a request with `Accept-Language: es-ES` (or a locale cookie set to something unsupported) through a path that resolves `security.accountLocked`, asserting a clean English response rather than a 500. Add a second case asserting `Accept-Language: de-DE` with **no** locale cookie still resolves German — the regression guard for item 5.
7. **This is a genuine live bug, not hygiene** — treat it as such in the story's completion notes.

### AC13 — A full fr-FR idiom and terminology pass

`skillars-deferred-91` AC9 did this for de-DE (informal → formal, 54 replacement groups). fr-FR has never had the equivalent. **Verified live:** fr-FR is already fully formal — 0 informal forms (`tu`/`ton`/`ta`/`tes`/`toi`), 121 `vous`/`votre`/`vos` occurrences — so register is **not** the problem and must not be "fixed". The gap is idiom: the bundle is AI-authored and has never been reviewed for stiff literal translations, anglicisms, wrong domain terminology, or awkward word order.

1. Sweep all 1021 keys of `src/frontend/src/i18n/fr-FR/index.js` for literal-from-English phrasing, missing or wrong accents, anglicisms where a standard French term exists, and sports/coaching terminology that does not match French usage (e.g. how a *séance*, an *entraîneur*, a *créneau* are actually named).
2. Placeholder integrity is non-negotiable: every `{placeholder}` and every vue-i18n pluralization pipe `|` must survive byte-identical. Verify with the existing frontend bundle parity check — 1021/1021, 0 drift, before and after.
3. Preserve `vous` throughout. Do not introduce `tu`.
4. Do the same idiom sweep over `messages_fr.properties` (130 keys) — including the 46 keys AC12 may have exposed as under-reviewed.
5. Record in `deferred-work.md`, in the same shape as the de-DE residual: **AI-authored to native quality, not yet human-verified by a native French speaker.** Do not claim the review is closed.

### AC14 — Frontend hardcoded-English sweep, with a guard

1. Sweep `src/frontend/src` for user-visible string literals not routed through `$t()` / `t()` — template text, `label`/`placeholder`/`title`/`aria-label` attributes, toast and notify messages, `q-table` column labels, validation messages, empty-state copy.
2. Extract each into all three bundles (`en-US`, `de-DE`, `fr-FR`) under the existing key namespaces, keeping the three at exact parity.
3. **Exclusions, stated explicitly so the sweep is reviewable:** developer-facing `console.*` output, error keys and enum values used as lookup keys, test fixtures, and anything already recorded as a decided leave-as-is (the `BookingService` `"Unknown …"` backend fallbacks are backend and out of this AC's scope regardless).
4. Add a guard so new ones are caught: an ESLint rule (e.g. `vue/no-bare-strings-in-template` with a configured allowlist) wired into the ESLint config that AC1's CI step now runs. If the rule's noise on this codebase proves impractical, say so explicitly in the completion notes and land the sweep without it — but attempt it.
5. Report the count found and fixed. A sweep with no number is not a verifiable sweep.
6. **Every new fr-FR string this AC introduces must meet AC13's bar**, and every new de-DE string must be formal `Sie` per `skillars-deferred-91` AC9. Do AC13's sweep first, then hold AC14's additions to the same standard — otherwise this AC silently re-introduces exactly the machine-translated phrasing AC13 just removed.
7. Re-run the three-bundle parity check after the sweep: all three must land on the same new total with 0 `{placeholder}` drift.
8. **Reference:** the i18n bucket in the project owner's standing instruction.

---

### AC15 — `AppEndpointsConventionTest` validates the matcher production actually uses

`AppEndpointsConventionTest` evaluates the `permitAll` patterns with `PathPatternRequestMatcher`, while `SecurityConfiguration.java:231` passes raw strings to `requestMatchers(String...)`. Under `PathPattern` semantics the pre-`skillars-deferred-91`-AC15 patterns (`resend-otp**`) are not valid at all, which implies production was matching with **Ant** semantics. AC15's anchoring fix was therefore correct, but the convention test's guarantee does not necessarily transfer to the matcher that actually secures the app — the test could pass while production matches differently.

1. Determine which matcher `requestMatchers(String...)` resolves to on this project's Spring Security version. Record the finding (version + resolution rule) in the test's javadoc, with a citation — this is the load-bearing fact and it must not be re-derived by the next reader.
2. Align the test to evaluate patterns with **that** matcher.
3. If the two semantics differ for any current pattern, that difference is a live security-surface finding — report it and fix the pattern, do not just align the test.
4. **Reference:** `deferred-work.md` § *code review of skillars-deferred-91 (2026-09-03)*, first bullet.

### AC16 — `ROLE_ROUTES` is defined once

**Verified live:** `ROLE_ROUTES` is declared twice — `src/frontend/src/pages/auth/LoginPage.vue:145` and `src/frontend/src/router/index.js:38` — and is read at six sites across the two files. The ledger flags the risk precisely: divergence causes an **infinite redirect loop** (the router sends a role to route A, the login page sends it to route B), and there is no current divergence only by luck.

1. Extract the map to a single module (e.g. `src/frontend/src/router/roleRoutes.js`) and import it in both places.
2. Verify the two current definitions are in fact identical before merging them; if they differ, that is a live bug — report which one is right and why.
3. `quasar build` + a manual read of every one of the six call sites.
4. **Reference:** `deferred-work.md` § *code review of skillars-1-5-authentication-jwt-security (2026-06-12)*.

### AC17 — Bare `@Async` stops depending on a two-step bean-name fallback

**Rewritten after the 2026-09-04 review audit found the original premise false — read this before implementing.**

The ledger entry behind this AC (`skillars-4-3` W6, written 2026-06-17) claims a bare `@Async` falls back to `SimpleAsyncTaskExecutor` and is therefore an unbounded thread-creation vector. **That is not what happens in this codebase today.** Verified at `c2c47c1`:

- A bean named **exactly `taskExecutor`** exists — `infrastructure/config/AsyncConfig.java:32`, a bounded `ThreadPoolTaskExecutor`.
- `AsyncExecutionAspectSupport.getDefaultExecutor` resolves: unique `TaskExecutor` bean → on `NoUniqueBeanDefinitionException`, the bean literally named `taskExecutor` → only if *that* is absent, `SimpleAsyncTaskExecutor`. With five executor beans present, step two matches. Bare `@Async` runs on the bounded pool.
- Spring Boot's auto-configured `applicationTaskExecutor` is `@ConditionalOnMissingBean(Executor.class)` and backs off entirely, so it does not complicate the resolution.
- `@EnableAsync` sits on `platform/notification/config/AsyncConfig.java:23`; `infrastructure/config/AsyncConfig.java:21` carries a javadoc recording that it omits `@EnableAsync` deliberately. The wiring is intentional and documented.

So there is **no unbounded-thread bug to fix**. What remains is a genuine but much smaller hygiene concern, and that is what this AC now asks for:

1. The current behaviour depends on a two-step fallback keyed on a **bean name string**. Renaming the `taskExecutor` bean, or adding a sixth executor, silently re-routes every bare `@Async` in the application — possibly onto `SimpleAsyncTaskExecutor`, at which point the ledger's original claim would become true. Make the dependency explicit.
2. **Verified live: there are 10 bare `@Async` sites in `src/main`**, not 2 — `VideoSseService` ×2, `TimelineEventListener` ×2, `RadarCompositeCalculationService`, `SluCalculationService`, `ReportGenerationService`, `HomeworkAssignmentService`, `SessionPlanService`, `VideoPhysicalDeletionListener` ×2. Either give **all** of them an explicit qualifier, or scope this AC to a subset and **state why those and not the others**. Do not silently fix two and leave eight, which is what the original AC would have produced.
3. Add a test asserting the default `@Async` executor resolves to the bounded `taskExecutor` bean — that is the actual guarantee worth pinning, and it fails loudly if a future rename breaks the fallback.
4. Whichever pool these land on must satisfy AC3's graceful-shutdown requirement; AC3 makes the correct routing more valuable, since a bare `@Async` inherits whatever its resolved pool does on shutdown.
5. **Correct the ledger entry** rather than deleting it — `skillars-4-3` W6's premise was probably true when the codebase had fewer executor beans, and a future reader should see why it stopped being true. Record it in AC30's new section.
6. **Reference:** `_bmad-output/implementation-artifacts/story-review.md` § N1.

### AC18 — `report_generate` rate limiting gains a per-coach dimension

`ReportGenerationService.java:100` is `@RateLimited(key = "report_generate", capacity = 10, duration = 1, MINUTES)`, and `RateLimitingAspect` keys on **client IP**, collapsing to the literal bucket `"report_generate:unknown"` whenever the IP lookup fails (`RateLimitingAspect.java:80-81`). Consequences: coaches behind one office NAT share a single 10/minute budget, and any caller whose IP is unresolvable shares one global bucket with every other such caller.

1. Add a per-coach guard alongside the existing IP limit, mirroring exactly what `skillars-deferred-89` AC7 did for `resendPhoneOtp`: an explicit `rateLimitingService.tryConsume(coachId, "report_generate_user", …)` call inside the service. Do **not** change `RateLimitingAspect`'s keying strategy — that is a shared aspect with a much wider blast radius, which is why the ledger deferred it.
2. Both limits apply; the per-coach one is the meaningful bound, the IP one stays as the anti-abuse floor.
3. Tests for: per-coach exhaustion returning the right error, and two different coaches on one IP not exhausting each other.
4. **Reference:** `deferred-work.md` § *code review of skillars-deferred-57*, first bullet.

### AC19 — The 401 JSON body carries `Cache-Control: no-store`

`JWTAuthorizationFilter.writeUnauthorized` (`platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`) sets status, content type and charset but no cache directives, so a misconfigured intermediary could in principle cache a 401. The body contains no secrets, so impact is low — it is a one-line correctness fix on an auth response.

Add `Cache-Control: no-store` (and `Pragma: no-cache` if the project's other error paths do). Assert it in the filter's existing test.

**Reference:** `deferred-work.md` § *code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)*, first bullet.

### AC20 — Delete `VideoApprovalRequestRepository.autoRejectExpired()`

**Project-owner decision 5.** The method (`platform/video/repo/VideoApprovalRequestRepository.java:51`) is dead — `grep -rn "autoRejectExpired" src` finds the declaration and nothing else — and its JPQL uses `current_timestamp` (which yields a `java.util.Date`) against an `Instant`-typed field, so it would fail the moment anyone wired it. A code comment records that it is deliberately not wired.

1. Delete the method and its comment.
2. Verify no test, no `@Query` fragment and no doc references it.
3. Record in `deferred-work.md` that auto-rejection of expired video approval requests is a **product feature**, not a pending bug fix, and that whoever builds it writes the query fresh against the requirements of the day.
4. **Reference:** `deferred-work.md` § *code review of skillars-6-6-player-video-management-portal (2026-06-24)*, W4.

### AC21 — `dev-docs` index no longer misdescribes the session-refresh doc

`docs/dev-docs/index.html:152` describes `session-refresh-mechanism.md` as *"deep dive on the JWT refresh-token rotation flow."* The rewritten doc is about the **sliding-window keep-alive** mechanism; rotation is `POST /api/auth/refresh`, a different endpoint the doc now explicitly distinguishes. A reader following the index lands on the opposite of what they were promised.

1. Correct the card's summary text.
2. While in the file, verify every other card summary still matches its target doc's actual subject; correct any that do not, and say which.
3. **Reference:** `deferred-work.md` § *code review of 1-7-session-refresh-mechanism-fix (2026-09-02)*, second bullet.

### AC22 — `CoachPublicProfilePage.vue` stops re-fetching reviews it already has

`CoachProfileDto.reviews` is built server-side by `reviewQueryService.getFirstPageForCoach` (`CoachMarketplaceResource.java:70-81`) and is byte-for-byte the data `listCoachReviews(coachId)`'s default call (`page=0, sort='newest'`) returns — yet `CoachPublicProfilePage.vue`'s `onMounted` (`:446`) ignores `profile.value.reviews` and fires a second, wholly redundant `GET /api/reviews/coaches/{coachId}` on every page view.

1. Seed the reviews list from `profile.value.reviews` on mount; call `listCoachReviews` only for subsequent pages / sort changes (`:535` already handles paging).
2. Alternatively, drop the backend's now-redundant `reviews` enrichment and keep the single client fetch. **Pick one and state the reasoning** — carrying both is the actual defect.
3. Confirm the shapes match before deleting either side (`CoachProfileDto.reviews` vs the `listCoachReviews` response envelope) — if they differ, that difference is the reason both exist and must be recorded.
4. `quasar build` + a manual page load.
5. **Reference:** `deferred-work.md` § *code review of skillars-deferred-83 (2026-08-30)*, second bullet.

### AC23 — `AdminBootstrapRunner`'s silent skip becomes visible

If the configured bootstrap email already belongs to a coach, parent or player, `AdminBootstrapRunner` skips rather than granting `ROLE_ADMIN`. That is deliberate and stays deliberate — an "upgrade this user to admin" path is a different, riskier feature. But an operator who typos the address into an existing user's email gets a skip explained only by an INFO line they will not be reading.

1. Raise the log level to WARN and make the message unambiguous: which email, which existing role, and that **no admin was created**.
2. Do **not** add elevation. The skip behaviour is correct.
3. **Reference:** `deferred-work.md` § *skillars-uat-1-admin-bootstrap-and-onboarding-unblock (2026-08-10)*, D3.

### AC24 — The `ConfigService` cache TTL surprise is documented

**Project-owner decision 6 — document, do not change the TTL.** `ConfigService` refreshes on `@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")` (`platform/config/service/ConfigService.java:54`). A change made through the config API calls `invalidate()` and is immediate; a change made **directly in the database** takes up to 5 minutes, which reads as "the setting is broken" (recorded for `booking.session.defaultDurationMinutes`).

1. Document the two-path behaviour on the admin config surface (a note in the admin config UI or its API docs) **and** in `docs/deployment/runbook.md`.
2. State both facts explicitly: API changes are immediate; direct-DB changes take up to `app.config.cache-ttl-seconds`.
3. No TTL change, no new endpoint.
4. **Reference:** `deferred-work.md` § *skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)*, D5.

### AC25 — Two frontend styling gaps recorded since Story 1.2

1. **`.glass-card`'s `transition: all`** — `src/frontend/src/css/glass.scss` uses `transition: all 0.2s ease` at four sites (`:15`, `:42`, `:56`, `:70`), inconsistent with `.hover-lift`, which Story 1.2 narrowed to `transform + box-shadow` precisely because `transition: all` animates every property change including layout ones. Narrow all four to the properties that actually animate, and verify visually that no intended transition is lost.
2. **`variables.scss` dual import path** — `app.scss` imports `tokens/colors` directly **and** `variables.scss` forwards to `tokens/colors`, so any file importing `variables.scss` picks up the colour tokens twice. Resolve to a single path and confirm the build emits no duplicate-import or deprecation warning.
3. Both verified by `quasar build` and a page load in both light and dark theme (this project's design system is dual-theme; a token-import change is exactly the kind that breaks one theme and not the other).
4. **Reference:** `deferred-work.md` § *code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)*, W1 and W5.

### AC26 — The `skillars-deferred-90` AC8 verification that was never run

`skillars-deferred-90` Task 8 fixed a cross-test bundle-pollution bug (`RequestMetadataProvider.cleanup()` in two `tearDown()`s plus defensive cleanup in `LoginAttemptsServiceTest`). The fix is sound, but the "12/14 before / 14/14 after" reproduction was never actually run: `LoginInfoServiceIT`, `LoginAttemptsServiceTest`, `NeglectedSkillDetectionServiceIT` and `AccountManagementFacadeIT` were each run in **isolation** (green), never **together in the order that produced the reported failure**. So the evidence the AC asks for does not exist.

1. Run the four classes together, in the order that originally produced the failure, at a commit **before** the fix (or with the fix reverted locally) and record the actual result.
2. Run them together at `HEAD` and record the result.
3. If the failure does not reproduce, say so plainly — a non-reproducing bug report is a legitimate outcome and is more useful than a claim nobody checked. Do **not** revert the fix either way; it is defensively correct.
4. This is an author-verification task, not a code change.
5. **Reference:** `deferred-work.md` § *3-layer adversarial code review of skillars-deferred-90 (2026-09-03)*.

---

### AC27 — `docs/lgtm-observability.md` is reconciled with the alerts that actually ship

Found by the 2026-09-04 `deploy-*` re-audit (the first ever run on those sections — six prior full-file audits each closed by recording that they had skipped them).

The doc's alert catalogue is `CallbackRateZero`, `WebhookPermanentFailure`, `OrangeCircuitBreakerOpen`, `MtnCircuitBreakerOpen`, `PaymentFailureRateHigh`, `CallbackFailureRatioHigh`, `FraudBlockRateHigh`, `ReconciliationDiscrepancy` and `ProviderLatencyP*` — **Orange / MTN mobile-money alerts inherited from the `javatemplate` origin project.** Skillars is a Stripe platform and ships **none** of them. The nine alerts `deploy/lgtm/alerts.yml` actually defines (`AppDown`, `NodeExporterDown`, `BookingPaymentSettleFailureRateHigh`, `DbConnectionPoolHigh`, `JvmHeapHigh`, `SubscriptionInvoicePaymentFailureHigh`, `DiskDataVolumeHigh`, `DiskRootHigh`, `MemoryPressureHigh`) appear nowhere in it. 51 lines still discuss Orange/MTN/mobile money.

`docs/deployment/monitoring.md` **was** correctly rewritten for the real alert set by `skillars-deferred-76` — which is exactly why the two `deploy-3-4` ledger items citing it went stale — but `lgtm-observability.md` was left behind, and **two `dev-docs` pages link to it as the authoritative observability reference** (`docs/dev-docs/index.html:150`, `docs/dev-docs/monitoring/index.html:101,138`). An operator following it during an incident hunts for alerts that cannot fire and misses every one that can.

1. Replace the alert catalogue with the nine live alerts, sourced from `deploy/lgtm/alerts.yml` and `deploy/lgtm/grafana-alerts.yml`. `docs/deployment/monitoring.md`'s § *Alert Inventory and Response Actions* is the already-correct model — **cross-reference it rather than duplicating it**, so the two cannot drift apart again.
2. Remove or rewrite the Orange/MTN/mobile-money content. If any of it is genuinely reusable as a worked LogQL/PromQL/TraceQL example, keep the *query* and relabel it against a real Skillars metric; do not keep a payment-provider narrative that describes a system this project does not have.
3. Sweep the rest of the doc for the same origin-project drift while you are in it — it is 1143 lines and only the alert section was audited. Report what else you find.
4. **Add a test or CI check** asserting every `alert:` name in `deploy/lgtm/alerts.yml` appears in `docs/deployment/monitoring.md`, and that no alert name documented there is absent from the rules. This drift went unnoticed across a whole project pivot; a one-line grep check in CI is what stops it recurring.
5. **Coordinate with AC21** — both edit `docs/dev-docs/index.html`. Do them in one pass.

### AC28 — `/coach/resend-otp` and `/player/resend-otp` get a frontend caller

`skillars-deferred-89` AC7 shipped both endpoints — `permitAll`, `@RateLimited(3 / 30 min)` per role, plus the per-user guard `skillars-deferred-91` added — and **nothing in the frontend calls either one.** Verified live 2026-09-04: `parentRegistration.api.js:16` has `resendOtp(userId)` and `ParentPhoneVerifyPage.vue:50` renders the button, but `coachRegistration.api.js` and `playerRegistration.api.js` expose only `resendVerification(email)` — the *email*-verification resend, a different endpoint — and neither `CoachPhoneVerifyPage.vue` nor `PlayerPhoneVerifyPage.vue` has a resend control.

Net effect: a coach or player whose OTP email is lost or expired has **no self-service recovery**, while a parent does. Two hardened, rate-limited public endpoints sit unreachable.

1. Add `resendOtp(userId)` to `coachRegistration.api.js` and `playerRegistration.api.js`, pointing at `/api/security/coach/resend-otp` and `/api/security/player/resend-otp`.
2. Add the resend control to `CoachPhoneVerifyPage.vue` and `PlayerPhoneVerifyPage.vue`, **mirroring `ParentPhoneVerifyPage.vue:50` exactly** — same cooldown behaviour, same disabled state, same i18n key shape. Do not invent a second pattern; the parent flow is the reference implementation.
3. i18n keys go into all three bundles at parity, and the fr-FR/de-DE strings must meet AC13 / `skillars-deferred-91` AC9's bar.
4. Handle the documented error responses the backend already returns: 409 `security.otpResendInProgress`, 400 `security.accountLocked`, 400 `security.otpMismatch`. **These are among the 46 keys AC12 adds to the default bundle** — verify AC12 landed first or the error path renders nothing.
5. Verified by ESLint + Prettier + `quasar build` + code reading (no frontend test framework — standing gap).

### AC29 — Booking reminder emails have never been sent: `onBookingReminder` is unreachable

**BLOCKER-class live bug, found by the story review and confirmed by its audit (2026-09-04).** Not a scoping question — a feature that has never worked.

`BookingEmailListener.onBookingReminder` (`:545`) carries **no annotation at all** — not `@EventListener`, not `@TransactionalEventListener`. `BookingEmailListener` is a bare `@Component` (`:45-47`) and does not implement `ApplicationListener`. Spring dispatches `publishEvent` only to annotated methods or `ApplicationListener` beans, so an unannotated public method taking an `ApplicationEvent` subtype is **never invoked**.

Meanwhile `BookingReminderScheduler.processReminderWindows()` — `@Scheduled(fixedDelay = 5, MINUTES)`, `@SchedulerLock`, `@Transactional` — publishes `BookingReminderEvent` at `:59` (primary) and `:73` (secondary) on every cycle. `EmailTemplate.BOOKING_REMINDER` is referenced in exactly one place in `src/main`: the unreachable method. There is no alternative sender.

**No booking reminder — 24-hour primary or 2-hour secondary — has ever been delivered to a parent or a coach.**

Three things kept it silent through a full story, a 3-layer code review, and the first pass of this story's own review:

- `BookingReminderScheduler:60` logs `"Transitioned booking {} to UPCOMING and sent primary reminder"` — the log asserts a send that never happened.
- `:58` calls `b.setPrimaryReminderSentAt(now)` on a managed entity inside `@Transactional`, so **the database records the reminder as sent**. Any "did we remind them?" query answers yes.
- `BookingEmailListenerTest:101-125` invokes `listener.onBookingReminder(event)` **directly**, so the unit tests pass and prove the method body composes the right email — they cannot detect that nothing calls it.

`skillars-deferred-91`'s `OutboxService` javadoc (`:65`) names `onBookingReminder` as a live producer, so the assumption that it was wired has already propagated through one prior review.

1. Add `@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)` to `onBookingReminder`, consistent with AC4's target state. `BookingReminderScheduler` already publishes inside `@Transactional`, so AC4's required ambient transaction is present — verify that before relying on it.
2. **The regression test must publish the event through `ApplicationEventPublisher` and assert an outbox row appears.** A direct-invocation unit test reproduces the exact blind spot that hid this for months; the existing `BookingEmailListenerTest` cases may stay, but they do not satisfy this AC.
3. Add a guard against the class of bug, not just this instance: a test asserting that every `public void on*(…Event)` method on `BookingEmailListener` and `SessionPackEmailListener` carries `@TransactionalEventListener`. `onBookingReminder` is currently the only unannotated one across both classes — the guard is what stops the twenty-third from silently becoming a twenty-fourth.
4. Fix `BookingReminderScheduler:60`'s premature `log.info` — it must not claim a send that has only been enqueued. Decide and state whether `primaryReminderSentAt`/`secondaryReminderSentAt` should be stamped before or after the enqueue.
5. Check whether any booking currently carries a non-null `primaryReminderSentAt`/`secondaryReminderSentAt`. Those rows are stamped-but-never-sent, and the story must say whether they are backfilled, left alone, or simply recorded as known-bad history.
6. **Reference:** `_bmad-output/implementation-artifacts/story-review.md` § B1.

---

### AC30 — `deferred-work.md` is updated per its own stated convention

The file's own "How to read this file" rule: **closed items are deleted outright, not annotated.** Every item this story closes is **removed**, not tagged `[CLOSED by …]`. A section whose bullets are all removed is removed with them.

**Already done during story creation (2026-09-04) — do not repeat, verify and extend:**

- The prune has run: 19 `[CLOSED]`/`[STALE]`/`[WITHDRAWN]` bullets and the 12 sections they emptied are deleted, verified by a line-for-line reconstruction check (1343 → 1295 lines). `[DISMISSED]`/`[DECIDED]`/`[PICKED UP]` bullets were deliberately kept — they are declined or decided, not done, and the file keeps them so decisions are not re-litigated.
- The **first-ever `deploy-*` re-audit** has run — six prior full-file audits each closed by recording that they skipped those sections. 18 items examined: 3 closed/stale and deleted, 6 corrected in place, 9 confirmed unchanged, 2 new findings filed (now AC27 and AC28). Recorded under `## Last audit: 2026-09-04`.

**Still owed by the dev agent at implementation time:**

1. Delete every bullet closed by AC1–AC26.
2. Add one `## Deferred from: skillars-deferred-92 story creation and implementation (2026-09-04)` section recording only what genuinely remains, including:
   - **de-DE native-speaker review** — still owed, human-only (carried forward unchanged for the third story running).
   - **fr-FR idiom pass is AI-authored, not native-verified** (new, from AC13).
   - Anything AC4 could not move to `BEFORE_COMMIT`, with the specific reason per listener.
   - Anything AC14's sweep deliberately excluded.
   - The AC15 finding if the two matcher semantics turn out to differ.
3. Correct the stale `AFTER_COMMIT` catalogue per AC6.
4. Do **not** re-litigate anything in § *Standing decisions recorded* above.


---

## Tasks / Subtasks

- [x] **Task 1 — Prettier reformat, first and alone (AC: 1)**
  - [x] `cd src/frontend && npx prettier --write "src/**/*.{js,vue,scss,json}"`
  - [x] `npx prettier --check …` → 0 warnings; `npx eslint .` → pass; `quasar build` → success
  - [x] Add the Prettier + ESLint step to `.github/workflows/ci.yml`
  - [x] **Commit this alone**, message states it is a mechanical reformat with no behaviour change
- [x] **Task 2 — Outbox atomicity and executor lifecycle (AC: 2, 3, 4, 5, 6)**
  - [x] AC3 first (it is independent and de-risks everything else): graceful shutdown on all 5 pools; fix `sendMailPool`'s post-`afterPropertiesSet` `setRejectedExecutionHandler`; add the intent test
  - [x] AC4: audit all 9 producers for an ambient transaction (start with `SessionPackExpiryNotifier`, the known-bad one); flip the 23 listeners to `BEFORE_COMMIT`; change `enqueueEmail`'s propagation; rewrite the stale javadoc
  - [x] AC4 tests: commit → exactly 1 outbox row; **rollback → 0 outbox rows**
  - [x] AC2: round-trip type assertion + contract javadoc
  - [x] AC5: `VIDEO_MODERATION_RETRY` outbox handler, idempotent, + IT
  - [x] AC6: rewrite the ledger's `AFTER_COMMIT` catalogue
- [x] **Task 3 — Migration safety: ordering, locks, DML, config ids (AC: 7, 8, 9, 10, 11)**
  - [x] AC7 `DROP_WITHOUT_PRIOR_RELEASE_PREP` + source-reference grep + fixtures + pinned assertions + doc
  - [x] AC8 `MISSING_LOCK_TIMEOUT` + fixtures + doc (**with the why**) + PR checklist
  - [x] AC9 `UNBATCHED_DML` + fixtures; chunk `BandwidthResetService.resetMonthlyBandwidth` + test
  - [x] AC10 identity sequence migration (`setval` from `max(id)`, not hardcoded) + doc + optional lint rule
  - [x] AC11 per-clause `NOT VALID` evaluation; statement-scoped opt-out markers; move both bullets in the doc
  - [x] `MigrationConventionLintTest` green, every new rule triggered by its own fixture
- [x] **Task 4 — i18n (AC: 12, 13, 14)**
  - [x] AC12: 46 keys into `messages.properties`; third `MessageBundleParityTest` case; `messageSource.setFallbackToSystemLocale(false)` with the **resolver's** `defaultLocale` left unset per AC12.5; `Accept-Language: es-ES` IT + the `de-DE`-no-cookie regression case
  - [x] AC13: fr-FR idiom sweep (frontend 1021 keys + `messages_fr` 130); parity 1021/1021 and 0 placeholder drift before and after; `vous` preserved
  - [x] AC14: hardcoded-English sweep across `src/frontend/src`, extract to all 3 bundles, attempt the ESLint guard, report the count
- [x] **Task 5 — Genuine one-off bugs (AC: 15–29)**
  - [x] AC15 matcher-fidelity investigation → record the version fact → align → report any semantic difference as a finding
  - [x] AC16 single `ROLE_ROUTES` module (verify the two are identical first)
  - [x] AC17 qualified executors on `VideoPhysicalDeletionListener`
  - [x] AC18 per-coach `report_generate` guard + 2 tests
  - [x] AC19 `Cache-Control: no-store` on the 401 + assertion
  - [x] AC20 delete `autoRejectExpired`
  - [x] AC21 `dev-docs/index.html` card summaries
  - [x] AC22 `CoachPublicProfilePage.vue` single reviews source (shapes verified first)
  - [x] AC23 `AdminBootstrapRunner` WARN
  - [x] AC24 ConfigService TTL documented (admin surface + runbook)
  - [x] AC25 `glass.scss` × 4 + `variables.scss` dual import; both themes checked
  - [x] AC26 run the 4 test classes together, before and after; record the honest result
  - [x] AC27 reconcile `lgtm-observability.md` with the 9 live alerts + CI drift check (coordinate with AC21 — both edit `dev-docs/index.html`)
  - [x] AC28 `resendOtp` api + resend control on Coach/Player phone-verify pages, mirroring the parent flow (needs AC12's keys)
  - [x] **AC29 (do this first in Task 5) — annotate `onBookingReminder`; event-published IT, NOT direct-invocation; annotation-coverage guard over both listeners; fix the premature `log.info`; decide what to do about already-stamped `*ReminderSentAt` rows**
- [x] **Task 6 — Ledger (AC: 30)**
  - [x] Delete every closed bullet outright; delete emptied sections
  - [x] One new `skillars-deferred-92` section with only genuine residue
  - [x] Update `sprint-status.yaml`

---

## Dev Notes

### Sequencing that matters

0. **AC29 is the one live user-facing bug in this story — do it early and independently.** Booking reminder emails have never been sent. It touches two files, depends on no other AC, and every day it waits is another day of undelivered reminders while the database and the logs both claim otherwise. Do not let it queue behind the Prettier reformat or the outbox refactor.
1. **AC1 (Prettier) must land first and alone.** Every later `.vue`/`.js` edit otherwise disappears into a 130-file reformat and becomes unreviewable.
2. **AC3 before AC4.** AC4 changes transaction boundaries around the outbox; doing it while the pools can still be killed mid-drain makes any failure ambiguous.
3. **AC12 before AC13.** AC12 may add 46 keys to a bundle AC13 then has to review; doing them the other way means reviewing the same strings twice.
4. **AC7/AC8/AC9/AC10/AC11 are one coherent unit** — all touch `MigrationLint` + `migration-conventions.md`. Do them in one pass to avoid three rounds of doc churn.

### The `BEFORE_COMMIT` decision (AC4), stated plainly

The ledger frames the fix as *"moving the enqueue into each of the 22 producing transactions — a large refactor."* It does not have to be. `@TransactionalEventListener(phase = BEFORE_COMMIT)` runs the listener inside the producing transaction's synchronisation, so the enqueue is committed atomically with the business work — the same guarantee, obtained by changing 23 annotations rather than 9 services.

The cost is real and must be handled, not hand-waved: **a throw in a `BEFORE_COMMIT` listener rolls the business transaction back.** That is the correct semantic for an outbox (an un-enqueued email means the transaction did not fully succeed), but it means payload construction must not be able to fail on anything the business transaction has not already validated. If a listener does a repository read to build its payload, either move that data into the event or leave that one listener on `AFTER_COMMIT` and record the exception. Both outcomes are acceptable; an unrecorded one is not.

`RefundOutboxSupport` is the already-correct reference shape — it is invoked inside the listener's own `REQUIRES_NEW`. Do not change it.

### Previous-story intelligence (`skillars-deferred-89` → `-90` → `-91`)

Patterns this project has learned the hard way. Every one of them is directly reachable from this story's ACs.

1. **A lock or guard whose test passes without it is not proven.** Recorded three times (`deferred-13`, `deferred-15`, `uat-3` D11 — the last one deleted its own IT after discovering it passed against the unlocked code). Applies to AC4 (the *rollback → 0 rows* assertion is the one that proves the change; the commit case would pass today), AC7–AC11 (every new lint rule needs an invalid fixture that fails without the rule), and AC3.
2. **The ledger's text is not evidence.** Six consecutive audits found items marked open that were already fixed, and items marked fixed that were not — including one (`deferred-86`) where all three review layers missed an existing migration that already satisfied the item. Every AC in this story cites a fact re-verified against source on 2026-09-04; if the dev agent finds one that no longer holds, **say so and stop**, rather than implementing against a stale premise.
3. **Do not self-invoke a `@Transactional` method from within the same bean.** deferred-90's 3-layer review forced `PendingBlobDeletionService` off exactly that shape onto a separate `@Transactional(REQUIRES_NEW)` chunk-processor bean. AC9's chunked reset must follow the corrected shape, not regress to the original.
4. **A `@Scheduled` job that publishes outside a transaction silently drops its events.** `SessionPackExpiryNotifier` sent *zero* expiry warnings for months because it was not `@Transactional` and its listener was `AFTER_COMMIT` with `fallbackExecution = false`. AC4 must audit for this before flipping any listener to `BEFORE_COMMIT` — a `BEFORE_COMMIT` listener with no ambient transaction does not fire at all, which would turn a working path into a silent one.
5. **Delete closed ledger items outright; do not tag them.** `deferred-41`…`-60` drifted into appending `[CLOSED by …]` annotations and the file grew as items closed, until a 2026-08-24 pass deleted 175 tagged bullets mechanically. AC30 restores the stated convention; do not re-introduce the tag style.
6. **A passing unit test is not proof a feature is wired.** AC29's bug — no booking reminder ever sent — survived a full story and a 3-layer code review because `BookingEmailListenerTest` invokes the listener method *directly*. Direct-invocation tests verify a method body; they cannot verify that Spring dispatches to it. Where an AC's value depends on framework wiring (an annotation, a bean name, a listener registration), the test must exercise the framework path — publish the event, resolve the bean, hit the endpoint — not call the method.
7. **State scope gaps explicitly.** Every audit in this file that survived scrutiny did so because it said what it did *not* check. Partial completion of an AC is acceptable; unrecorded partial completion is not.

### Files this story touches (non-exhaustive, verified during creation)

**Backend**
- `platform/notification/service/NotificationOutboxSupport.java` (AC2, AC4)
- `platform/notification/service/NotificationEmailOutboxHandler.java` (AC2)
- `platform/notification/infrastructure/listener/BookingEmailListener.java` — 20 listeners / 20 enqueues (AC4)
- `platform/notification/infrastructure/listener/SessionPackEmailListener.java` — 4 listeners / 3 enqueues (AC4)
- `platform/outbox/config/OutboxConfig.java:40` (AC3)
- `platform/development/config/DevelopmentConfig.java:64` (AC3)
- `platform/notification/config/AsyncConfig.java:38,51` (AC3 — **including the `setRejectedExecutionHandler` ordering bug at the `sendMailPool` bean**)
- `infrastructure/config/AsyncConfig.java:34` (AC3)
- `platform/video/service/ModerationSlaMonitorService.java:72,93` (AC5)
- `platform/video/service/BandwidthResetService.java` (AC9)
- `platform/video/repo/VideoApprovalRequestRepository.java:51` (AC20)
- `platform/notification/infrastructure/listener/BookingEmailListener.java:545` — `onBookingReminder`, unannotated (AC29)
- `platform/booking/service/BookingReminderScheduler.java:58-60,72-73` — premature `log.info`, `*ReminderSentAt` stamping (AC29)
- `src/test/.../BookingEmailListenerTest.java:101-125` — the direct-invocation tests that hid AC29's bug (AC29)
- 10 bare `@Async` sites across `platform/video`, `platform/development`, `platform/session` (AC17)
- `platform/security/config/MvcConfig.java` (AC12)
- `platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` (AC19)
- `platform/security/config/SecurityConfiguration.java:231` (AC15, read-only unless a semantic difference is found)
- `platform/development/service/ReportGenerationService.java:100` (AC18)
- `platform/config/service/ConfigService.java:54` (AC24, doc only)
- `AdminBootstrapRunner` (AC23)

**Migrations / lint**
- `src/test/java/com/softropic/skillars/db/MigrationLint.java` + `MigrationConventionLintTest.java` + fixtures (AC7–AC11)
- `docs/deployment/migration-conventions.md` (AC7–AC11)
- New migration for AC10 — **next free version is `V128`** (highest existing is `V127__narrow_pcl_reference_dedup.sql`)

**i18n**
- `src/main/resources/i18n/messages.properties` (AC12), `messages_fr.properties` (AC13)
- `src/test/java/com/softropic/skillars/i18n/MessageBundleParityTest.java` (AC12)
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (AC13, AC14)

**Frontend**
- everything under `src/frontend/src` (AC1, mechanical)
- `src/frontend/src/pages/auth/LoginPage.vue:145` + `src/frontend/src/router/index.js:38` (AC16)
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue:446,535` (AC22)
- `src/frontend/src/css/glass.scss:15,42,56,70` + `variables.scss` / `app.scss` (AC25)

**Ops / docs / CI**
- `.github/workflows/ci.yml` (AC1)
- `docs/dev-docs/index.html:152` (AC21)
- `docs/deployment/runbook.md` (AC24)

### Testing standards

- Backend: JUnit 5 + AssertJ; ITs are `@SpringBootTest` + `@Testcontainers` against PostgreSQL. Follow `IntegrationTestConventionTest`'s rules.
- **Do not run `mvn verify` locally** — GitHub CI is this project's sole full-verification gate (standing project rule). Run targeted tests only.
- Frontend has no test framework (standing gap). `.vue`/`.js` changes are verified by ESLint + Prettier + `quasar build` + code reading.
- Schedulers are disabled under the test profile via `app.scheduling.enabled`; tests call drain/sweep methods directly.
- New lint rules must be provably load-bearing: each needs an **invalid** fixture that fails without the rule, pinned in `widenedRules_triggerOnTheirOwnFixtures`. This project has three recorded instances of a guard whose test passed without the guard (`deferred-13`, `deferred-15`, `uat-3` D11) — do not add a fourth.

### Project Structure Notes

- Module boundaries hold: `filestorage` owns blob storage (never reference `infrastructure.blobstore` directly); the generic outbox lives in `platform/outbox` with per-domain `*OutboxSupport` / `*OutboxHandler` pairs in the owning module. AC5's new handler belongs in `platform/video`, not in `platform/outbox`.
- `main.platform_config` is the platform-config table; AC10 changes only its id allocation, not its `key`-based `ON CONFLICT` idempotency pattern.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — every AC cites its own section inline
- [Source: `docs/deployment/migration-conventions.md`] — §§ *The expand / contract standard*, *Grandfathering*, *What the guard now covers*, *What the guard still cannot catch*, *PR checklist*
- [Source: `_bmad-output/project-context.md:69`] — Prettier mandate
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-91-…md`] — AC1 outbox contract, AC3 email routing, AC7/AC8 migration lint + grandfathering, AC9 de-DE register
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-89-…md`] — AC7 per-user rate-limit guard, the pattern AC18 mirrors

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code, `/bmad-dev-story`)

### Debug Log References

Verifications run during implementation, each recorded because the AC asked for evidence rather than a claim:

- **AC29 guard is load-bearing** — removed the new `@TransactionalEventListener` and re-ran
  `EmailListenerAnnotationCoverageTest`: fails with exactly
  `["BookingEmailListener.onBookingReminder(BookingReminderEvent)"]`. Restored.
- **AC3.3 is a real bug, not a reshuffle** — restored the original
  `setRejectedExecutionHandler`-after-`afterPropertiesSet()` ordering and re-ran
  `ExecutorShutdownConfigurationTest`: the live executor reports `AbortPolicy`. Restored.
- **AC7–AC11 rules bind the real tree** — removed `SET lock_timeout` from `V128` and ran the lint over
  `src/main/resources/db/migration`: `MISSING_LOCK_TIMEOUT` fired on `V128`. Restored.
- **AC13's untranslated-value guard** — set `messages_fr`'s `email.booking.reminder.title` to the exact
  English string; `MessageBundleParityTest` failed naming it. Restored.
- **AC27.4 parity guard, both directions** — added a phantom `#### PhantomAlertThatDoesNotExist` heading to
  `monitoring.md` (failed), then an `UndocumentedNewAlert` rule to `alerts.yml` (failed). Both restored.
- **AC26 reproduction** — see the completion note; the only run whose *failure* is the deliverable.
- **Spring source read rather than assumed** — `spring-context-6.2.19-sources.jar`
  (`ExecutorConfigurationSupport`) for AC3, and `spring-security-config-6.5.11-sources.jar`
  (`AbstractRequestMatcherRegistry`, `RequestMatcherFactory`) for AC15.

### Completion Notes List

**Story premises corrected against source.** Dev Notes §2 asks that a stale premise be reported rather than
implemented around. Seven were:

1. **AC3.2 — `ExecutorConfigurationSupport` IS a `SmartLifecycle`** (and the version is **6.2.19**, not
   6.2.12). The conclusion survives but by a different mechanism: setting
   `waitForTasksToCompleteOnShutdown(true)` sets `lateShutdown`, which makes `stop()` a no-op, so the
   blocking await happens only in `destroy()`. Recorded in `ExecutorShutdown`'s javadoc.
2. **AC3's inventory missed a pool.** There are **six**, not five —
   `BlobstoreConfig#storageUploadExecutor` is a raw `ThreadPoolExecutor`, invisible to a
   `ThreadPoolTaskExecutor` grep. Found by AC3.4's coverage test on its first run, which is exactly the
   "caught by intent, not by luck" the AC asked for. Its non-blocking `shutdown()` let the JVM exit with S3
   uploads in flight.
3. **AC2's premise** — there are **72** `data.put` sites, not 69, and three put a boxed `int`, not a
   `String`/`List<String>`. Harmless (an `Integer` round-trips), but not the stated contract; the test
   asserts type *fidelity* instead, which is the property AC2 actually wants.
4. **AC4's known-bad producer is stale.** `SessionPackExpiryNotifier` publishes inside
   `transactionTemplate.execute` — deferred-15 AC6 fixed it and its javadoc says so. All 28 publish sites
   across twelve producers (not nine) were already transactional, so no listener had to stay on
   `AFTER_COMMIT`.
5. **AC17's premise was false.** A bare `@Async` never fell back to `SimpleAsyncTaskExecutor` here. The
   real risk was that correctness rested on a bean-*name*. Also 11 sites, not 10 — the story's own list
   totals 11.
6. **AC12's count of 46 is right** (the naive grep says 44), but the drift ran **both ways**: two keys
   existed only in the default bundle, and three more drifted on *placeholders*.
7. **AC25.2 was not two live import paths** but a dead forwarder — nothing imported `variables.scss` at all.

**Live bugs fixed (not hygiene).**

- **AC29 — booking reminders had never been sent, ever.** `onBookingReminder` carried no annotation, so
  Spring never dispatched to it. The scheduler logged "sent primary reminder" and stamped
  `primaryReminderSentAt`, so both the logs and the database asserted a delivery that never happened, and
  `BookingEmailListenerTest` invoked the method directly so the unit tests passed.
- **AC12 — a live 500.** `messages.properties` (the fallback bundle) held 86 of 130 keys. Any client
  resolving to a locale other than de/fr/en got `NoSuchMessageException` on `security.accountLocked` and on
  every `email.*` template key.
- **AC13 — three email subject lines shipped as untranslated English in BOTH de and fr.**
- **AC14 — `ParentRegisterPage`'s Terms box still began with literal Lorem ipsum**, in English, in a box a
  parent must scroll to accept.
- **AC3.3 — `sendMailPool` ran with `AbortPolicy`** for the life of the bean.
- **AC27 — the observability guide documented nine alerts that do not exist and none of the nine that do**,
  and its Quick Start named a compose file that has never existed.

**AC26 — the reproduction that had never been run.** All four classes together in one JVM, in the reported
order. With the fix reverted: `LoginAttemptsServiceTest` **12 of 14 FAILED**. At HEAD: **14/14**, bundle
32/32. So the bug reproduces and the fix is load-bearing — but the ledger's "12/14 before" is wrong in
direction: 12 is the count that *failed*. It was 2/14. No production code changed; the reverts were local
and restored (verified: `git diff` on `src/test` empty).

**Deliberate scope decisions, stated rather than left implicit.**

- **AC4.2** — `enqueueEmail` now *rethrows*; each listener's `catch (Exception)` is the documented policy
  boundary. The split is precise: an outbox INSERT failure marks the transaction rollback-only, so the
  business work rolls back whether or not the listener catches — genuinely atomic. Only a pure in-memory
  serialisation failure is swallowed, and AC2's contract guard shrinks that to nothing.
- **AC9.2** — `resetMonthlyBandwidth` had to LOSE `@Transactional`; one enclosing transaction would hold
  every row lock to the end, strictly worse than the statement it replaced. That changed
  `SchedulerLockTransactionOrderingIT`'s assertion for that bean from "ShedLock outermost" to "must not be
  `@Transactional`".
- **The new lint rules bind `V128+`, not `V122+`.** Flyway checksums whole files, so `V122`–`V127` are
  applied and cannot carry the required markers. A second baseline rather than rewriting shipped
  migrations.
- **AC22** kept the server-side enrichment and dropped the client's duplicate fetch (not the reverse),
  because that direction removes a round trip from first paint.
- **AC25** narrowed only `glass.scss`'s four `transition: all`; `components.scss`'s two are recorded, not
  silently swept in.
- **AC14's ESLint guard landed as an `error`**, with an allowlist of brand/currency/symbols only, two
  provably-unrouted dead files deleted, and one targeted disable with a reason.

**Not run:** `mvn verify`. Per `docs/validation-strategy.md`, CI is this project's sole full-verification
gate. Targeted runs used instead — see below.

**Testing performed.**

- Full unit suite: **1285 tests, 145 classes, 0 failures, 0 errors** (`mvn -o test`).
- Integration: 246 booking+payment ITs after the `BEFORE_COMMIT` flip; plus
  `NotificationEmailOutboxAtomicityIT`, `BookingReminderEmailWiringIT`, `ModerationOutboxIT`,
  `BandwidthResetChunkingIT`, `DefaultMessageBundleFallbackIT`, `SchedulerLockTransactionOrderingIT`,
  `RefundOutboxIT`, `SluSnapshotOutboxIT`, `MailManagerIT`, `EmailRetrySchedulerIT`,
  `SessionPackExpiryWarningIT`, `VideoDeletionOutboxProcessorIT` — all green.
- Frontend: `prettier --check` clean, `eslint .` clean (with the new bare-strings rule as an error),
  `quasar build` succeeds, bundle parity **1083/1083/1083** with 0 placeholder drift, 0 informal forms in
  fr-FR and de-DE.

**Residual risk worth naming for review.** The `BEFORE_COMMIT` flip changes failure semantics on 22 email
paths. A throw inside such a listener now rolls the business transaction back. Neither listener holds a
repository, and payload construction does no I/O, so the exposure is structurally small — but this is the
change in this story with the widest blast radius and is where review attention is best spent.

### File List

**Added (32)**

- `src/frontend/src/router/roleRoutes.js`
- `src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java`
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetChunkProcessor.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationAdminAlertOutboxHandler.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationRetryOutboxHandler.java`
- `src/main/resources/db/migration/V128__platform_config_identity.sql`
- `src/main/resources/db/migration/V129__reset_never_sent_secondary_reminders.sql`
- `src/test/java/com/softropic/skillars/db/AlertDocumentationParityTest.java`
- `src/test/java/com/softropic/skillars/i18n/DefaultMessageBundleFallbackIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/threadpool/AsyncExecutorQualifierTest.java`
- `src/test/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdownConfigurationTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingReminderEmailWiringIT.java`
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/EmailListenerAnnotationCoverageTest.java`
- `src/test/java/com/softropic/skillars/platform/notification/service/EmailDataRoundTripContractTest.java`
- `src/test/java/com/softropic/skillars/platform/outbox/ModerationOutboxIT.java`
- `src/test/java/com/softropic/skillars/platform/outbox/NotificationEmailOutboxAtomicityIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/BandwidthResetChunkingIT.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationRetryOutboxHandlerTest.java`
- `src/test/resources/migration-lint/fixture-src/WidgetRepository.java`
- `src/test/resources/migration-lint/invalid/V910__drop_column_no_prepared_marker.sql`
- `src/test/resources/migration-lint/invalid/V911__drop_column_marker_but_live_reference.sql`
- `src/test/resources/migration-lint/invalid/V912__missing_lock_timeout.sql`
- `src/test/resources/migration-lint/invalid/V913__unbatched_dml.sql`
- `src/test/resources/migration-lint/invalid/V914__platform_config_explicit_id.sql`
- `src/test/resources/migration-lint/invalid/V915__second_constraint_validates.sql`
- `src/test/resources/migration-lint/invalid/V916__optout_leaks_to_later_statement.sql`
- `src/test/resources/migration-lint/valid/V809__prepared_drop.sql`
- `src/test/resources/migration-lint/valid/V810__bounded_dml_and_lock_timeout.sql`
- `src/test/resources/migration-lint/valid/V811__full_table_dml_optout.sql`
- `src/test/resources/migration-lint/valid/V812__per_clause_not_valid.sql`
- `src/test/resources/migration-lint/valid/V813__optout_per_statement.sql`

**Deleted (4)**

- `src/frontend/src/css/variables.scss`
- `src/frontend/src/pages/IndexPage.vue`
- `src/frontend/src/pages/marketplace/CoachPublicProfilePlaceholderPage.vue`
- `src/main/resources/mails/platformConfigChanged.html`

**Modified (179 total)** — including 130 frontend source files touched by AC1's mechanical
`prettier --write` (commit `cb20f11`, no behaviour change; a reviewer can skip that commit wholesale).
The behaviourally-modified files outside that reformat:

- `.github/workflows/ci.yml`
- `.github/workflows/pr-build.yml`
- `docker-compose.yml`
- `docs/deployment/migration-conventions.md`
- `docs/deployment/runbook.md`
- `docs/dev-docs/index.html`
- `docs/lgtm-observability.md`
- `src/frontend/eslint.config.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/main/java/com/softropic/skillars/infrastructure/blobstore/config/BlobstoreConfig.java`
- `src/main/java/com/softropic/skillars/infrastructure/config/AsyncConfig.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingReminderScheduler.java`
- `src/main/java/com/softropic/skillars/platform/config/api/ConfigResource.java`
- `src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`
- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`
- `src/main/java/com/softropic/skillars/platform/development/service/TimelineEventListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/BookingEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/SessionPackEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/NotificationOutboxSupport.java`
- `src/main/java/com/softropic/skillars/platform/outbox/config/OutboxConfig.java`
- `src/main/java/com/softropic/skillars/platform/security/config/MvcConfig.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`
- `src/main/java/com/softropic/skillars/platform/security/service/AdminBootstrapRunner.java`
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java`
- `src/main/java/com/softropic/skillars/platform/session/service/VideoPhysicalDeletionListener.java`
- `src/main/java/com/softropic/skillars/platform/video/repo/VideoApprovalRequestRepository.java`
- `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorService.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSseService.java`
- `src/main/resources/application.yaml`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_fr.properties`
- `src/test/java/com/softropic/skillars/db/MigrationConventionLintTest.java`
- `src/test/java/com/softropic/skillars/db/MigrationLint.java`
- `src/test/java/com/softropic/skillars/i18n/MessageBundleParityTest.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java`
- `src/test/java/com/softropic/skillars/platform/security/config/AppEndpointsConventionTest.java`
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java`
- `src/test/java/com/softropic/skillars/platform/video/service/ModerationSlaMonitorServiceTest.java`
- `src/test/resources/migration-lint/valid/R__repeatable_drop_optout.sql`
- `src/frontend/src/… (see commit cb20f11 for the mechanical reformat)`

### Change Log

| Date | Change |
|---|---|
| 2026-09-04 | AC1: repo-wide `prettier --write` over `src/frontend/src` + a `frontend-quality` CI job in `ci.yml` and `pr-build.yml`. Committed alone (`cb20f11`). |
| 2026-09-04 | AC29/AC3/AC4/AC2/AC5: booking-reminder dispatch fixed; 22 email listeners moved to `BEFORE_COMMIT` with `enqueueEmail` on `MANDATORY`; graceful shutdown on all six executor pools; `sendMailPool`'s `AbortPolicy` bug; email-data round-trip contract; `ModerationSlaMonitorService` onto the outbox. |
| 2026-09-04 | AC7–AC11: five new `MigrationLint` rules, per-clause `NOT VALID`, statement-scoped opt-outs, `V128` identity, chunked `BandwidthResetService`, conventions doc + PR checklist. |
| 2026-09-04 | AC12: 46 keys into the default bundle, third parity case, `setFallbackToSystemLocale(false)`, fallback IT. |
| 2026-09-04 | AC13/AC14: fr-FR idiom pass (58 strings), untranslated-value guard, hardcoded-English sweep (59 new keys), `vue/no-bare-strings-in-template` as an error. |
| 2026-09-04 | AC15/AC17/AC18/AC19/AC20/AC23: backend one-off bugs. |
| 2026-09-04 | AC16/AC22/AC25/AC28: frontend one-off bugs. |
| 2026-09-04 | AC21/AC24/AC26/AC27: docs reconciled, alert-parity guard, AC26 reproduction run and recorded. |
| 2026-09-04 | AC6/AC30: `deferred-work.md` — 23 closed bullets and 6 emptied sections deleted, `AFTER_COMMIT` catalogue rewritten, new residuals section. Status -> review. |
