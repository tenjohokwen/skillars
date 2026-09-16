# Story: Mail-Send Concurrency Hardening, Quota Fallback Audit Trail & SES Cutover Preflight Tooling

**Story Key:** `skillars-deferred-114-mail-concurrency-quota-audit-ses-preflight`
**Epic:** Deferred Work
**Priority:** Medium (1 defense-in-depth concurrency fix + 1 diagnostic + 1 audit-trail fix + 1 new ops tool + 2 ledger-only closures)
**Status:** done
**Created:** 2026-09-16

---

## Provenance & Scoping (read before starting)

This story was mined from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: code review of skillars-deferred-113 (2026-09-15)` section — all six findings
in that section, deferred the same day they were raised, none re-fixed since. Line citations below
were re-verified against HEAD (`9c7a918b`) at story-creation time, not trusted from the ledger text.

**The "genuine one-off bugs & gaps" bucket is confirmed exhausted** (re-mined multiple times, most
recently the 2026-09-15 full-file re-audit) — nothing usable was found there. Per explicit
project-owner direction, this story instead works the six ses-1-*/deferred-113-adjacent buckets
named at story-creation time, in the order given, with these outcomes:

| # | Bucket | Outcome |
|---|---|---|
| 1 | Drop `main.pending_blob_deletions` | **Out of scope, by owner decision.** Its own closing condition ("confirmed deployed to production AND the table is provably empty in every environment") is unmet — no production deploy has ever happened (`docs/deployment/runbook.md`, `docs/deployment/migration-rebaseline.md:137`). Do **not** touch this table, its entity/repo, or `PendingBlobDeletionResidualDrainRunner` in this story. Left as-is in the ledger. |
| 2 | `QuotaConfigService.resolveTierKey` player-tier gap | **Already closed** by `skillars-deferred-113` AC3 (verified in code: `resolvePlayerTierKey`/`playerTierToQuotaSegment` exist and are correct). Dropped from this story's scope — nothing to do. |
| 3 | Concurrent-retry edge case | **AC1 below.** |
| 4 | Defensive-not-curative throw | **AC2 below** (diagnostics only, by owner decision — see AC2). |
| 5 | Fail-open tier fallback | **AC3 below** (owner decision: add audit trail). |
| 6 | Check-then-use quota lookup | **AC4 below** (owner decision: leave as documented tradeoff — ledger-only, no code). |
| 7 | Manual runbook checklist | **AC5 below**, together with #8 (owner decision: build a preflight tool). |
| 8 | No automated health-check integration | **AC5 below.** |

AC6 is the standard ledger-hygiene closeout every `skillars-deferred-*` story in this project performs.

---

## User Story

As a **Platform Engineer**, I want to **close the remaining gap in `MailManager`'s per-sendId
concurrency protection, add a visible audit trail for both the quota-fallback and admin-alert
unknown-outcome edge cases the previous story left as diagnostics-only, and replace the SES
cutover runbook's purely-manual checklist with an executable preflight check**, so that **a future
direct caller of `sendEmailSync` can't silently double-send mail, an operator can see — via metrics,
not log-grepping — how often a player is quietly downgraded to athlete quota or an admin alert's
delivery outcome goes unconfirmed, and the first-ever production SES cutover is verified by running
one tool instead of reading a four-step checklist and improvising the test-email step by hand**.

---

## Acceptance Criteria

### AC1: `MailManager.sendEmailSync` — cluster-wide serialization independent of the caller

**Given** `sendEmailSync`'s only real per-`sendId` concurrency protection today is
`EnvelopeEntityRepository.findBySendId`'s `@Lock(LockModeType.PESSIMISTIC_WRITE)` (added `e82840ae`,
2026-06-08 — long-standing, not new), which serializes concurrent calls **only once a row already
exists** for that `sendId` (the second caller blocks at the `SELECT ... FOR UPDATE` until the first's
`REQUIRES_NEW` transaction commits, then correctly re-reads the updated delivered-set),

**When** two calls race on the **first-ever** send for a given `sendId` (no row exists yet — nothing
to pessimistic-lock against),

**Then**:

- Both calls are still serialized, cluster-wide, without depending on any caller. Today's three real
  callers are the `AFTER_COMMIT` async self-invocation (`sendEmailFromTemplate`),
  `EmailRetryScheduler`'s `@SchedulerLock`-protected redrive, and
  `NotificationEmailOutboxHandler.handle()` (invoked via `OutboxRowProcessor.claimAndHandle()`'s own
  `@Transactional(REQUIRES_NEW)` + `FOR UPDATE SKIP LOCKED` claim on the generic transactional
  outbox). None of the three collide with each other today — the first two because `@SchedulerLock`
  serializes whole invocations of `retryFailedEmails()` cluster-wide, and the outbox path because, by
  the time either retry mechanism re-drives a `sendId`, an `EnvelopeEntity` row already exists and the
  existing `PESSIMISTIC_WRITE` lock already serializes them. But `@SchedulerLock` only serializes
  `EmailRetryScheduler.retryFailedEmails()` itself, and the outbox's row-claim lock only protects the
  outbox row, not `MailManager.sendEmailSync` — neither protects arbitrary future direct callers of
  `sendEmailSync`
- The second call does not send a real duplicate email to the same recipient before observing the
  first call's outcome

**Recommended approach:** acquire a Postgres advisory transaction lock keyed by `sendId`
(`SELECT pg_advisory_xact_lock(hashtext(?))`) as the **first statement** inside `sendEmailSync`'s
existing `@Transactional(propagation = REQUIRES_NEW)` boundary, before `findBySendId`. This works
whether or not a row exists yet (unlike the row-level lock), is released automatically at
commit/rollback (no manual unlock/leak risk), and needs no new column or migration. `hashtext(text)`
returns a 32-bit `int`, which widens implicitly to the `bigint` overload of
`pg_advisory_xact_lock`. (A hash, not the raw string, is safe to use here: two different `sendId`s
hashing to the same 32-bit value would serialize against each other unnecessarily, but at this
codebase's realistic concurrency — single-digit concurrent sends, 10-row retry batches per
`fetchFailedEmails()` — the practical odds are negligible.)

**Implementation caveat — no existing precedent for this call shape:** `pg_advisory_xact_lock`
returns SQL `void`, not a row of data. Every existing native/`@Modifying` query in this codebase
(`VideoQuotaRepository`, `VideoRepository`, `RefreshTokenRepository`, etc.) is a real UPDATE/DELETE
DML statement, and `EnvelopeEntityRepository.fetchFailedEmails()` — the only other native query in
this exact repository — returns real `EnvelopeEntity` rows. Neither is a working template for "native
SELECT of a void-returning function." Verify the chosen mapping (a repository method declared to
return `void`, or `EntityManager.createNativeQuery(...).getSingleResult()` with the result discarded)
actually executes cleanly against this project's pinned Postgres/Hibernate versions via a real
Testcontainers test — don't assume it "just works" the way the DML `@Modifying` queries do.

**Design consideration — read before implementing:**
`MailManagerDuplicateSendIdIT` (`src/test/java/.../notification/infrastructure/MailManagerDuplicateSendIdIT.java`)
already exists and documents, in detail, that a first-send `sendId` collision **today** lets both
racing calls actually dispatch (to two different mocked recipients in that test) before one loses on
the DB unique-constraint at commit and throws. That test's own javadoc is explicit that this proves
"a genuine collision is a clear, propagating error … not that duplicate sends are prevented." Adding
the advisory lock **changes this documented behavior**: the second call will now block until the
first commits, then take the "existing row" branch instead of racing an `INSERT` — so the loser no
longer throws. Decide and document explicitly:
- Whether `MailManagerDuplicateSendIdIT`'s scenario (two *different* envelopes/recipients sharing a
  hand-authored duplicate `sendId` — a caller-misuse case, not the real production shape) still needs
  its own coverage post-fix (e.g., asserting the second call now blocks-then-updates cleanly instead
  of throwing), or whether it should be explicitly reframed as superseded by this AC's new test.
- That `applyDeliveryFlags` rebuilds `EnvelopeEntity.recipients` from the **calling envelope's own**
  recipient list (not merged with what's already persisted) — a second call for the same `sendId` but
  a genuinely different recipient list (only possible via caller misuse, since every real producer
  sends the identical envelope on redrive) silently overwrites the first call's delivered-recipient
  record instead of failing loudly. **This exact overwrite is not new** — a sequential (non-racing)
  second call already hits it today, since `findBySendId` already finds the existing row and takes the
  update branch. **What this AC does change is the *concurrent* case specifically**: today, two
  concurrent calls with different recipient lists both attempt to `INSERT` and one loses loudly on the
  DB unique constraint (`MailManagerDuplicateSendIdIT`'s exact scenario) — real duplicate sends still
  happen on both sides, but at least the collision is visible. After this AC's advisory lock, the same
  concurrent race instead resolves through the *same silent path* the sequential case already takes:
  the second call blocks, then updates cleanly with no exception. Net effect: this AC converts a loud
  failure into a silent one for a caller-misuse scenario that should never legitimately occur — an
  acceptable trade for closing the real race this AC targets, but it must be a **documented decision**,
  not an unnoticed side effect: add an explicit code comment at the `applyDeliveryFlags` call site
  (something like "a same-`sendId`-different-recipients race now resolves silently instead of via a DB
  collision — see skillars-deferred-114 AC1; this indicates caller misuse, not a legitimate retry, and
  no real producer does this") and cover it with the test case below.

**Verified by:**
- New test (extend `MailManagerDuplicateSendIdIT` or add a sibling) driving two threads calling
  `sendEmailSync` with the **same envelope** (same `sendId`, same recipients — the real production
  shape) concurrently for a `sendId` with no existing row; asserts exactly one real send reaches the
  recipient (the mocked `MailService` seam) and both calls return without throwing
- Caller-misuse case: two threads calling `sendEmailSync` with the **same `sendId`** but **different**
  recipients (mirroring `MailManagerDuplicateSendIdIT`'s existing scenario) — assert the second call
  now blocks then completes **without throwing**, and the final persisted row's recipients match only
  the second call's envelope (documenting the silent-overwrite behavior explicitly, per the Design
  Consideration above, rather than leaving it to be discovered later)
- Mutation check: temporarily remove the advisory-lock statement, confirm the new test goes red
  (both calls proceed concurrently / a duplicate send or DB collision reappears), then restore
- Rollback case: the first call in a serialized pair fails outright (not just rate-limited) and rolls
  back; confirm the second, now-unblocked call still runs as a clean first attempt (no row was
  persisted by the first call, so the second must not mistakenly treat itself as an "existing row"
  update)
- Existing PESSIMISTIC_WRITE-protected path (retry of an already-existing row) confirmed unaffected —
  re-run `MailManagerRateLimitIT` (skillars-deferred-113 AC1) green

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java:90-106`
(`sendEmailSync` top), `src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java:20-21`
(existing `@Lock` for comparison)]

---

### AC2: `VideoModerationEmailListener` `persisted==null` — occurrence counter (diagnostics only)

**Given** `sendAdminAlertSync`'s `persisted == null` branch already logs a rich diagnostic `WARN`
(transaction-active/name/read-only/isolation-level + thread, added by `skillars-deferred-110` AC5)
and then throws `IllegalStateException` to retain the outbox row (Option B, `skillars-deferred-113`
AC2) — root cause remains genuinely unconfirmed; the transaction-isolation analysis already in the
code says this read-back **should** already be consistent, and nothing has ever reproduced the race,

**When** this branch actually fires in any environment,

**Then**: the occurrence is visible as a Grafana-queryable metric, not only discoverable by grepping
logs for `[VIDEO_MODERATION_ADMIN_ALERT]`.

**Owner decision (this story):** diagnostics only — do **not** attempt a structural fix (bounded
retry / forced flush / re-read). The existing analysis already concludes a retry is unlikely to help
a race nothing has reproduced, and `skillars-deferred-113` AC2's Option-B risk assessment stands.
This AC's entire scope is: add a counter alongside the existing `WARN` log so a real occurrence rate
becomes visible without waiting for someone to notice a log line.

**Fix:** add a `recordAdminAlertOutcomeUnknown()` method to `MailMetrics`
(`platform.notification.service.MailMetrics` — the existing `MeterRegistry`-backed façade for this
module, mirrors `VideoMetrics`'s pattern), and call it from the `persisted == null` branch in
`VideoModerationEmailListener.sendAdminAlertSync`, immediately alongside the existing `log.warn`.

**Verified by:**
- Extend `VideoModerationEmailListenerTest`'s existing `persisted==null` unit cases
  (`noPersistedEnvelope_warnsNotYetVisible`, `noPersistedEnvelope_warnCarriesTransactionContext`) or
  `VideoModerationAdminAlertEnvelopeIT`'s equivalent case to also assert the new counter incremented
  (a `SimpleMeterRegistry` in the unit-test context, or a real `MeterRegistry` bean in the IT)
- Mutation check: remove the counter call, confirm the new assertion fails

**Ledger:** [`src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java:143-145`,
`src/main/java/com/softropic/skillars/platform/notification/service/MailMetrics.java`]

---

### AC3: `QuotaConfigService` — audit trail for a missing player-subscription row

**Given** `resolvePlayerTierKey` (`skillars-deferred-113` AC3) falls back to `"athlete"` in **two**
distinct unmapped shapes, only one of which currently logs anything:
- `Long.parseLong(ownerId)` fails → `log.debug(...)` already exists (line 87) — **not** this AC's
  target, already has a trail (debug is arguably too quiet for this one too, but it is out of scope
  here; this AC is about the genuinely-silent path below)
- `paymentPlayerSubscriptionRepository.findByPlayerId(playerId)` returns `Optional.empty()` (no
  subscription row exists at all for a syntactically-valid player id) → **zero logging today**, falls
  straight through `.map(...).map(...).orElse("athlete")` to the fallback

**When** a player has no subscription row at all (a real data-integrity signal — every player should
have one, seeded or created at registration) and their quota is checked,

**Then**: this specific shape is distinguishable, in logs and in a metric, from (a) the already-logged
non-`Long` `ownerId` case and (b) the already-logged unrecognized-tier-string case
(`playerTierToQuotaSegment`'s existing `log.warn`, line 104-105).

**Fix:**
1. In `resolvePlayerTierKey`, branch explicitly on the `Optional<PaymentPlayerSubscription>` from
   `findByPlayerId` instead of chaining straight through `.map(...).map(...).orElse(...)` — on empty,
   `log.warn` with the `playerId` and an explicit "no subscription row found" message before falling
   back to `"athlete"`.
2. Add a counter (inject `VideoMetrics` — the existing façade for this module — or add a narrowly-
   scoped new one if `VideoMetrics` is judged the wrong home; follow whichever this module's own
   convention favors) incremented on this specific fallback path, tagged/named distinctly from any
   existing quota-error counter so an operator can tell "no subscription row" apart from "unrecognized
   tier string" in a dashboard.

**Known characteristic, not a bug — document it rather than "fix" it:** `resolvePlayerTierKey` is
`private`, reachable only via `resolveTierKey` → `getStorageQuotaBytes`/`getBandwidthQuotaBytesMonthly`
(also `private`/package-internal call chain from the two public accessors). The **complete** inventory
of real call sites hitting those two public accessors (verified by direct search, not assumed to be a
single site):
- `QuotaService.check(ownerId, ...)` (`QuotaService.java:38`) — the advisory pre-check
- `QuotaService.reserve(ownerId, ...)` (`QuotaService.java:90`) — the authoritative reservation
- `VideoResource.java:123-124` — both storage **and** bandwidth, back-to-back

`VideoService`'s upload-initiation flow calls `quotaProvider.check(...)` immediately followed by
`quotaProvider.reserve(...)` for the **same `ownerId`** (`VideoService.java:140/154` and again at
`:270/279`, for two distinct upload paths) — so a single upload-initiation request for a player with
no subscription row fires this AC's new WARN log and counter **twice** from that flow alone, on top of
whatever `VideoResource`'s own dual call contributes if a quota-status page is hit separately. Note
this full inventory explicitly wherever the counter is documented (metric description, dashboard, or
a code comment) so nobody reads the raw count as "distinct players" or "distinct requests" —
resolving the tier once per logical caller would fix it but is a bigger refactor than this AC's scope
and isn't required here.

**Verified by:**
- Extend `QuotaConfigServiceTest` with a case: player id parses as a `Long`, repository returns
  `Optional.empty()`, assert both the new `WARN` log (via a log-capture idiom already used elsewhere
  in this test class, if any — otherwise assert the returned tier is `"athlete"` and the metric fired)
  and the counter increment
- A case proving the count is 2, not 1, for a single logical caller that invokes the resolver twice
  for the same no-subscription-row player (e.g. mirroring `QuotaService`'s own check-then-reserve
  sequence, or `VideoResource`'s storage-then-bandwidth pair) — so the multi-fire characteristic is
  documented from day one, not a surprise found later against a live dashboard
- Confirm the existing unrecognized-tier-string and non-`Long`-id paths are unaffected (their own
  existing log statements untouched) — re-run full `QuotaConfigServiceTest` green

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:82-94`]

---

### AC4: Check-then-use tier/quota lookup — re-confirm as accepted tradeoff (ledger-only, no code)

**Given** `QuotaConfigService.java:82-94`'s tier lookup and the caller's actual quota
enforcement are two separate, non-transactional reads — a player's subscription tier can change in
the window between them,

**When** deciding this story's scope,

**Then**: **no behavioral code change**, but not a silent no-op either. Owner decision: this is an
accepted, low-impact race (a brief quota-mismatch window, not a monetary-consistency or security
concern) — matching this codebase's other documented check-then-use tradeoffs. Two outputs, both
required:
1. A ledger annotation in `deferred-work.md` re-confirming the decision, dated to this story (see
   AC6), so a future audit doesn't re-litigate it from scratch.
2. A one-line code comment at `QuotaConfigService.resolvePlayerTierKey` (around the
   `findByPlayerId(...)` call, `QuotaConfigService.java:82-94`) recording the same decision inline —
   e.g. `// Tier lookup and quota enforcement are non-transactional by design — accepted tradeoff,
   re-confirmed skillars-deferred-114 AC4; do not add a lock/re-check here without revisiting that
   decision.` Without it, the next person reading this method has no signal that the race is
   deliberate rather than an oversight waiting for someone to "fix" with an ad hoc lock.

**Ledger:** [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java:82-94`]

---

### AC5: SES cutover preflight — an executable check, not just a runbook checklist

**Given** `docs/deployment/runbook.md`'s `## Pre-production release gate: SES cutover` section
(lines 627-674, added by `skillars-deferred-113` AC6) is a **purely textual** checklist: verify
`SesHealthIndicator`'s `productionAccessEnabled` detail, verify `ses:GetAccount`/`ses:SendEmail` IAM
permissions are attached, then "immediately after flipping the property, send one real test email …
and confirm both delivery and `GET /actuator/health/notification` reports UP" — and that last step
explicitly warns a cached health result (up to 60s TTL / 15s `DOWN` TTL) "is not proof a send just
worked,"

**When** an operator actually runs this checklist during the first production SES cutover,

**Then**: one tool call — not four manual steps improvised by hand — produces a definitive answer,
using the **live** transport rather than the cached actuator health check.

**Fix:** add an admin-only endpoint (new resource, e.g.
`platform.notification.api.SesCutoverPreflightResource`, `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`,
`@ConditionalOnProperty(name = "app.email.transport", havingValue = "ses")` — same bean-gating
`SesHealthIndicator` already uses, so this endpoint only exists in a profile where SES is actually the
active transport) that, on a single call:

1. Calls `SesV2Client.getAccount(...)` **directly, bypassing `SesHealthIndicator`'s TTL cache**
   (inject `SesV2Client` — do not go through the cached indicator), reporting
   `sendingEnabled`/`productionAccessEnabled`/`enforcementStatus`, and distinguishing an
   `SdkException` here (missing `ses:GetAccount`) in the response from a send failure below.
2. Sends one real test email — reuse `MailManager.sendEmailSync` directly (the actual production
   send path: real circuit breaker, real retry template, real `SesEmailSender`), not a bypass —
   to an admin-supplied recipient address, then reads back the resulting `EnvelopeEntity.status`
   to report `SENT` vs `FAILED` (this also exercises `ses:SendEmail` distinctly from `GetAccount`
   above). **Generate a fresh `UUID.randomUUID().toString()` `sendId` on every call** —
   `envelope_entity.send_id` carries a real DB `UNIQUE` constraint, and `sendEmailSync` branches its
   entire behavior on whether a row already exists for that `sendId`; a reused/hardcoded value would
   route a second run into the "existing row" update branch instead of a fresh probe attempt, so
   re-running the tool after fixing a config issue would silently stop being an independent test.
3. Returns one structured result — as project convention requires, a response `record`, not a
   hand-rolled `Map`/JSON blob — distinguishing all three outcomes an admin needs (account setup
   incomplete vs. account fine but send broke vs. both passed). Minimum shape (names illustrative, not
   mandatory):
   ```java
   record AccountCheckResult(boolean sendingEnabled, boolean productionAccessEnabled,
                              String enforcementStatus, String error) // error non-null only if
                                                                       // GetAccount itself threw
   record TestSendResult(String status, String error) // status: "SENT" | "FAILED"; error non-null
                                                        // only if status == "FAILED"
   record SesPreflightResult(AccountCheckResult accountCheck, TestSendResult testSend, boolean ready)
   ```
   This replaces the checklist's step 3 rather than duplicating steps 1-2's human-readable
   instructions (those stay in the runbook; this tool operationalizes step 3, the one the runbook
   itself flags as needing a live, uncached signal) — but the response must still let an admin tell
   "fix IAM permissions" apart from "fix the send itself" without reading source code to find out what
   the fields mean.
4. Needs a minimal new `EmailTemplate` constant + Thymeleaf template for the probe email (a one-line
   "SES cutover preflight test" body is sufficient — do not reuse an existing user-facing template).
   **Use the 3-arg `EmailTemplate` constructor with its own isolated circuit breaker name** (e.g.
   `"sesPreflightService"`) — **not** the single-arg constructor, which defaults to the shared
   `"emailService"` breaker used by booking confirmations and most other transactional templates
   (`CircuitBreakerFactory.create(name)` returns/reuses the same breaker instance for every caller
   with that name). A preflight tool must be able to report failure — that's the point of testing an
   unverified SES config — and a failing probe against the shared breaker would trip it for real
   production email traffic during the exact cutover window this tool exists to protect. This mirrors
   the existing rationale for the six registration/OTP templates' own isolated
   `"registrationEmailService"` breaker (`EmailTemplate.java`). The same isolated name doubles as a
   free filter: the preflight send goes through the normal `MailService`/SES logging pipeline like any
   other email (this is correct — it's the actual production path being tested), and the distinct
   template/breaker name is what an operator greps or dashboards on to exclude preflight test sends
   from production-traffic views. Mention this in the runbook update below rather than building a
   separate tagging mechanism.

**Follow this codebase's own resource conventions, not ad hoc ones:** the admin-supplied recipient
address must arrive via a validated request `record` (Jakarta Validation, e.g. `@NotBlank`/`@Email`)
per `project-context.md`'s "Validation" rule — not a raw `String` param — and the resource class
should carry `@Observed(name = "...")` per the same doc's "Observability" rule, matching
`AlertRuleAdminResource`'s own use of both.

**Update `docs/deployment/runbook.md`'s existing SES cutover gate section** to reference this new
endpoint for step 3, rather than "improvising a real user-facing flow" (the runbook's own current
wording for the gap this AC closes).

**Verified by:**
- Hermetic unit test for the account-check portion, mirroring `SesHealthIndicatorTest`'s pattern
  (mocked `SesV2Client`, no Spring context) — both the healthy and `SdkException`-thrown paths
- A resource-slice or full IT for the endpoint end-to-end (real `MailManager` path, a mocked/stubbed
  `MailService` or `SesEmailSender` seam per this module's existing IT conventions — see
  `VideoModerationAdminAlertEnvelopeIT`/`MailManagerRateLimitIT` for the seam-mocking pattern used
  elsewhere in this module), asserting the endpoint reports `SENT` on success and `FAILED` with a
  useful error on a simulated send failure
- `@PreAuthorize` present and enforced (non-admin caller gets 403). **No existing precedent for this
  in the codebase** — checked directly: `AlertRuleAdminResource` has no dedicated test file, and
  `AppEndpointsConventionTest` (the only "every-endpoint" security convention test that exists) covers
  `permitAll()` pattern anchoring only, not `@PreAuthorize` enforcement. This AC establishes the first
  such test in this codebase (e.g. a `@WebMvcTest` or full-context test asserting a non-`ROLE_ADMIN`
  JWT gets 403) — budget for writing it from scratch, not for extending an example

**Ledger:** [`docs/deployment/runbook.md:627-674`, `src/main/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicator.java`,
`src/main/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminResource.java` (sibling
admin-resource pattern to follow), `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`]

---

### AC6: Ledger hygiene

**Given** this project's standing convention (every `skillars-deferred-*` story closes out by
updating `deferred-work.md`),

**Then**, in `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: code review of skillars-deferred-113 (2026-09-15)` section:

- Delete the "Concurrent retry from multiple instances" bullet (closed by AC1) — real code fix, not a
  decision, so delete outright per this file's own convention.
- Delete the "Missing subscription row silently downgrades tier" bullet (closed by AC3) — same.
- Retag "Throw-on-null is defensive, not curative" as `[DECIDED 2026-09-16 (skillars-deferred-114):
  diagnostics-only — added an occurrence counter (AC2); root-cause investigation remains deliberately
  out of scope, per the existing Option-B risk assessment]` — not deleted, since no root-cause fix
  landed, only a decision + a diagnostic.
- Retag "No transactional consistency tier/quota lookup" as
  `[DECIDED 2026-09-16 (skillars-deferred-114): accepted tradeoff, re-confirmed — see AC4]`.
- Retag "Runbook checklist manual, not code-enforced" and "No automated health check integration" —
  both closed together by AC5's new preflight endpoint; delete both bullets and add one line noting
  the runbook section now references an executable tool for step 3.
- **Do not touch** the `main.pending_blob_deletions` follow-up bullet (`## Deferred from:
  skillars-deferred-100 implementation`) — explicitly out of scope this story, per the Provenance
  table above.
- Reconstruction check per this file's own convention: every surviving non-blank line must match the
  pre-edit file, in order, with nothing reworded/reordered beyond the deletions/retags above.

**Ledger:** [`_bmad-output/implementation-artifacts/deferred-work.md`]

---

## Tasks/Subtasks

- [x] **Task 1 — AC1: MailManager advisory-lock serialization** (do first: touches the file every
      other notification AC also touches; get the concurrency primitive settled before layering
      diagnostics on top)
  - [x] Add the `pg_advisory_xact_lock(hashtext(?))` call as the first statement in `sendEmailSync`
        (native query via `EntityManager`, or a small repository method — match whichever idiom
        `EnvelopeEntityRepository`'s existing native `fetchFailedEmails()` query establishes)
  - [x] Work through the Design Consideration in AC1 for `MailManagerDuplicateSendIdIT` — decide and
        document the resolution explicitly in this story's Dev Notes, don't leave it implicit
  - [x] New concurrent-same-envelope test (extend `MailManagerDuplicateSendIdIT` or add a sibling)
  - [x] Mutation check; re-run `MailManagerRateLimitIT` for the existing-row path regression

- [x] **Task 2 — AC3: QuotaConfigService audit trail** (isolated, low risk, quick)
  - [x] Branch explicitly on `Optional<PaymentPlayerSubscription>` in `resolvePlayerTierKey`
  - [x] Add the `WARN` log + counter on the no-subscription-row path
  - [x] Extend `QuotaConfigServiceTest`

- [x] **Task 3 — AC2: VideoModerationEmailListener diagnostic counter** (isolated, low risk, quick)
  - [x] Add `MailMetrics.recordAdminAlertOutcomeUnknown()`
  - [x] Wire the call into the `persisted == null` branch
  - [x] Extend the relevant existing test(s)

- [x] **Task 4 — AC5: SES cutover preflight endpoint** (largest task — new resource, new template,
      new tests; do after Tasks 1-3 so `MailManager`'s behavior is settled before this AC exercises it)
  - [x] New `EmailTemplate` constant + Thymeleaf template for the probe email
  - [x] New `SesCutoverPreflightResource` (admin-only, transport-gated) wiring `SesV2Client` (fresh
        account check) + `MailManager.sendEmailSync` (real test send)
  - [x] Update `docs/deployment/runbook.md`'s SES cutover gate section to reference the new endpoint
  - [x] Hermetic unit test (account-check portion) + resource-slice/IT (end-to-end)

- [x] **Task 5 — AC4 & AC6: ledger updates**
  - [x] AC4's decision annotation
  - [x] AC6's full ledger hygiene pass, per the bullet list above
  - [x] Reconstruction check

- [x] **Task 6 — Final validation**
  - [x] Run all new/modified targeted test classes together; confirm zero regressions
  - [x] Update Verification Checklist, File List, Change Log, Dev Agent Record
  - [x] Mark story Status → review

---

## Dev Notes

- **No local `mvn verify`** per project convention — GitHub CI is the sole full-verification gate.
  Run targeted suites only (notification module, video/quota module, the new resource's tests).
- **Risk areas:**
  - AC1 changes `MailManager.sendEmailSync`'s concurrency behavior in a way that has a documented,
    tested existing behavior to reconcile (`MailManagerDuplicateSendIdIT`) — highest-risk item in this
    story; do not treat the Design Consideration note as optional reading.
  - AC2, AC3: additive-only (a counter, a log branch + counter) — low risk, no behavior change to any
    existing return value or control flow. AC3's counter fires from multiple real call sites
    (`QuotaService.check`/`reserve`, `VideoResource`'s dual call) — confirm the full inventory below
    before assuming a single fire point.
  - AC4: near-zero code risk (a one-line inline comment + a ledger annotation — no behavioral change).
  - AC5: new surface area (new endpoint, new template) but no existing behavior to break; risk is in
    getting the transport-gating and admin auth right, not in regressing something else. Follow
    `AlertRuleAdminResource` and `SesHealthIndicator`'s existing conventions closely rather than
    inventing new ones.
- **Testing approach:** favor real, container-backed integration tests over mocks wherever this
  module already does (see `MailManagerRateLimitIT`, `MailManagerDuplicateSendIdIT`,
  `VideoModerationAdminAlertEnvelopeIT`, `QuotaConfigServicePlayerTierIT` for the established pattern:
  real Postgres + real `MailManager`/`EnvelopeEntityRepository`, only the outermost `MailService`/SES
  SDK seam mocked).
- **Project structure:** `SesCutoverPreflightResource` belongs in `platform.notification.api`
  (REST controllers, `Resource` suffix) per this project's DDD module-layer convention; every new
  resource method needs `@PreAuthorize` using `SecurityConstants` — no exceptions, per
  `project-context.md`'s "Critical Don't-Miss Rules."

### Project Structure Notes

- All touched files are within `platform.notification.*` and `platform.video.service` — no new
  module, no infrastructure-layer changes.
- `SesV2Client` is already a Spring-managed bean (autowired into `SesHealthIndicator` and
  `SesEmailSender` today) — inject it directly into the new resource, don't reach through the
  indicator.

---

## Files to Read Before Implementation

- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (AC1 — whole
  `sendEmailSync` method, lines 90-229)
- `src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java`
  (AC1 — existing `@Lock`/native-query conventions)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerDuplicateSendIdIT.java`
  (AC1 — read in full; its javadoc is the authoritative record of today's documented collision
  behavior, which this AC changes)
- `src/main/java/com/softropic/skillars/platform/notification/service/NotificationEmailOutboxHandler.java`,
  `src/main/java/com/softropic/skillars/platform/outbox/service/OutboxRowProcessor.java` (AC1 — the
  third real caller of `sendEmailSync`, easy to miss since it isn't in the notification package)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
  (AC2, lines ~90-160)
- `src/main/java/com/softropic/skillars/platform/notification/service/MailMetrics.java` (AC2 —
  existing façade to extend)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` (AC3/AC4, whole
  file — only 109 lines)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoMetrics.java` (AC3 — candidate
  façade for the new counter)
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java`,
  `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` (AC3 — the real
  `check()`/`reserve()` call sites that fire the new counter, on top of `VideoResource`'s dual call;
  see AC3's "Known characteristic" for the full inventory)
- `docs/deployment/runbook.md:627-674` (AC5 — the existing SES cutover gate section this AC extends)
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicator.java` (AC5 — transport
  gating pattern, TTL-cache-bypass rationale)
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesHealthIndicatorTest.java` (AC5 —
  hermetic mocked-`SesV2Client` test pattern)
- `src/main/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminResource.java` (AC5 —
  sibling admin-resource pattern: `@PreAuthorize`, `@RequestMapping`, module placement)
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` (AC5 — new
  constant)

---

## Verification Checklist

- [x] AC1: A concurrent same-envelope, same-`sendId`, first-ever-send race is serialized — exactly
      one real send reaches the recipient, neither call throws; existing-row retry path (already
      pessimistic-locked) unaffected; the caller-misuse case (same `sendId`, different recipients)
      completes silently rather than throwing, is covered by a test, and is documented with an inline
      code comment rather than left implicit
- [x] AC2: `persisted == null` branch increments a new counter alongside its existing WARN log
- [x] AC3: A player with no subscription row at all logs a distinct WARN and increments a distinct
      counter before falling back to athlete quota; the full multi-fire caller inventory
      (`QuotaService.check`/`reserve`, `VideoResource`'s dual call) is documented, not just one example
      site, and a test proves the count is >1 for a single logical caller
- [x] AC4: Ledger carries a `[DECIDED 2026-09-16 (skillars-deferred-114): ...]` annotation on the
      check-then-use bullet; `QuotaConfigService.resolvePlayerTierKey` carries an inline code comment
      recording the same decision; no behavioral code change
- [x] AC5: New admin, transport-gated endpoint reports a live (uncached) SES account check + a real
      test-send outcome in one call, in a documented response shape (an admin can tell "fix IAM" from
      "fix the send" from "ready" without reading source); `docs/deployment/runbook.md` references it;
      non-admin caller gets 403; the probe template uses its own isolated circuit breaker (not the
      shared `emailService` one) and that same name is usable to filter preflight sends out of
      production dashboards; a second invocation (fresh `sendId`) runs as an independent probe, not an
      update against the first one's row
- [x] AC6: `deferred-work.md`'s `code review of skillars-deferred-113` section reflects all of the
      above; `main.pending_blob_deletions` bullet untouched; reconstruction check passes
- [x] No regressions in existing notification/quota test suites

---

## References

- Deferred-work.md: `## Deferred from: code review of skillars-deferred-113 (2026-09-15)` (all six
  bullets — the entire source of AC1-AC5's scope); `## Deferred from: skillars-deferred-100
  implementation (2026-09-08)` (the `main.pending_blob_deletions` bullet, explicitly out of scope);
  `## Last audit: 2026-09-15 (ad-hoc verification...)` (confirms the player-tier gap already closed
  and the blob-table drop still blocked on an unmet precondition)
- Prior story: `skillars-deferred-113-email-notification-robustness-quota-completeness.md` (this
  story's entire scope is that story's own deferred code-review findings)
- `docs/deployment/runbook.md` (`## Pre-production release gate: SES cutover`, AC6 of deferred-113 —
  the section AC5 extends)

---

## Code Review Findings

### Patches (Decision Resolved)

- [x] [Review][Patch] **LOW: AC1 test case completeness — add scenario 4 (existing pessimistic-lock path)** [MailManagerDuplicateSendIdIT] — Added `existingRowWithPessimisticLock_concurrentCallersBlock_thenBothCompleteCleanly`: pre-persists a row in its own committed transaction (not created by a race), then races two further callers against it. Verified green against real Testcontainers Postgres (4/4 tests in the class, ~50s).

- [x] [Review][Patch] **HIGH: Null pointer dereference on `envelope.sendId()` before advisory lock** [MailManager.java] — Added an explicit null-check that throws `IllegalArgumentException` as the first statement in `sendEmailSync`. Confirmed against a real Postgres 16 container (`docker run postgres:16` + `psql`) that the actual mechanism is not a Postgres-side error: `hashtext(...)`/`pg_advisory_xact_lock(...)` are both STRICT, so a null key makes the native query return a null row silently, WITHOUT acquiring any lock and WITHOUT erroring — a null `sendId` would silently skip AC1's whole serialization guarantee rather than fail loudly. New hermetic unit test `MailManagerSendIdValidationTest` (2 tests, no Testcontainers needed — the guard fires before any repository interaction); mutation-checked (temporarily short-circuited the guard, confirmed `nullSendId_throwsImmediately...` goes red, restored, reconfirmed green).

- [x] [Review][Patch] **MEDIUM: Silent recipient-list overwrite on same-sendId/different-recipients race now undetected** [MailManager.java] — Added `log.warn(...)` at the `applyDeliveryFlags` call site, gated on the incoming recipient set actually differing from what's persisted (so a same-envelope redrive, the common case, doesn't log noise). New `recipientEmails(...)` helper overloads (entity/list) support the comparison. Covered indirectly by `MailManagerDuplicateSendIdIT`'s existing caller-misuse scenario (asserts the persisted-recipient outcome this warning is gated on); no new assertion on log output itself (this codebase doesn't unit-test log line content elsewhere either).

- [x] [Review][Patch] **MEDIUM: Quota tier fallback metric double-fires per request without complete caller inventory documented** [QuotaConfigService.java] — Grepped every caller of `getStorageQuotaBytes`/`getBandwidthQuotaBytesMonthly` (the only two callers of the private `resolveTierKey`/`resolvePlayerTierKey` chain) and replaced the vague inline comment with the confirmed, concrete list: `QuotaService.check` and `QuotaService.reserve` each call `getStorageQuotaBytes` only (1 fire each), `VideoResource.getMyQuota()` calls both (2 fires) — `QuotaService` never calls `getBandwidthQuotaBytesMonthly` at all. `QuotaConfigServiceTest` unaffected (9/9 green) — this was a comment-only change, not a behavior change.

- [x] [Review][Patch] **MEDIUM: Advisory lock exception handling doesn't prevent silent sends on lock acquisition failure** [MailManager.java] — Added a method-level Javadoc block on `sendEmailSync` stating direct callers must handle exceptions from this method to avoid silent send loss on lock-acquisition failure, cross-referencing which of today's three real callers already do.

- [x] [Review][Patch] **LOW: Metric name hard-coded as string literal without compile-time validation** [MailMetrics.java] — Extracted to `public static final String MAIL_ADMIN_ALERT_OUTCOME_UNKNOWN`, mirroring the existing `MAIL_SEND` constant pattern already in the same class. No test depended on the literal string, confirmed by grep before the change.

- [x] [Review][Patch] **LOW: SesCutoverPreflightResource read-back lacks transaction isolation guarantee** [SesCutoverPreflightResource.java] — Took option (b): reworded the null-case `TestSendResult` error message to explicitly hint "possible visibility lag — retry" instead of an unqualified failure, plus a code comment cross-referencing `VideoModerationEmailListener`'s own documented analysis of the identical read-back shape. Did not add option (a)'s extra lock/transaction — this endpoint already exists purely for a human operator to re-run, unlike the automated-redrive path (a) would matter for.

- [x] [Review][Patch] **MEDIUM → dismissed after review: Metric recording could fail in error-handling path without recovery** [VideoModerationEmailListener.java] — Investigated rather than patched: grepped every `videoMetrics.*`/`mailMetrics.*` call site in the codebase (~20, across `VideoApiAdvice`, `VideoService`, `ReconciliationWorkerScheduler`, `PlaybackService`, `WebhookEventProcessorScheduler`, `MailService`) — none are wrapped in try-catch; this call would be the first and only one, an inconsistent one-off. It is also immediately followed by `throw new IllegalStateException(...)` regardless, which already drives the same retry behavior in `ModerationAdminAlertOutboxHandler` — a try-catch here would not change what a caller observes on the (essentially theoretical, no-I/O) failure path. Moved to Dismissed below rather than patched, to keep this call site consistent with the rest of the codebase.

- [x] [Review][Patch] **LOW → dismissed after review: Missing null-guard on injected MailMetrics dependency** [VideoModerationEmailListener.java] — Investigated: none of this class's other five `@RequiredArgsConstructor` final fields (`publisher`, `configService`, `featureToggleService`, `mailManager`, `envelopeEntityRepository`) are null-checked either, and Spring's constructor injection already guarantees a non-null bean (a misconfigured/missing bean fails application startup, not a null field at call time). Singling out `mailMetrics` would be inconsistent with this class and with the rest of the codebase. Moved to Dismissed below.

- [x] [Review][Patch] **LOW → dismissed after review: CircuitBreakerFactory first-call initialization not tested for breaker isolation** [SesCutoverPreflightResourceIT] — Investigated: this suggestion doesn't fit the test it names. `SesCutoverPreflightResourceIT` is a `@WebMvcTest` slice with `MailManager` itself `@MockitoBean`-mocked (documented in that class's own javadoc) — there is no real `CircuitBreakerFactory` bean or breaker in that test's Spring context to exercise isolation against; a mocked `MailManager` never touches one. Real circuit-breaker behavior for the `sesPreflightService` breaker name is exercised the same way every other template's breaker is, through `MailManager`'s real send path (already covered by the module's other `MailManager`-based ITs) — a breaker-isolation test belongs there, not in a resource slice that mocks the class the breaker lives inside. Moved to Dismissed below as a false positive against this specific test class.

### Deferred

- [x] [Review][Defer] **Advisory lock assumes Postgres hashtext() behavior without explicit validation** [EnvelopeEntityRepository.java:37-38] — Code assumes 32-bit int widens to 64-bit safely, hash collisions negligible at skillars scale. Documented acceptable tradeoff per AC1; no behavioral fix needed, design decision stands.

- [x] [Review][Defer] **Diagnostics metric (mail.admin_alert.outcome_unknown) has no mitigation plan if underlying race never manifests** [VideoModerationEmailListener.java:128-133] — AC2 marks this diagnostics-only; counter will sit at 0 forever if race doesn't occur. Acceptable per owner decision; process gap noted but no code change required.

- [x] [Review][Defer] **Advisory lock exception handling clarity gap** [MailManager.java:91-104] — Direct callers must handle exceptions to prevent silent sends. Mostly a documentation clarity issue; core design sound. Addressed as a Javadoc patch above.

### Dismissed (Noise / Acceptable Tradeoffs)

- Advisory lock hashtext() collision risk — negligible at expected concurrency volumes; documented acceptable per AC1 acceptance audit
- Check-then-use quota race — documented as accepted tradeoff in AC4 inline comment + ledger annotation; decision re-confirmed
- Test emails visible in production logs — distinctive template name enables filtering; operator documentation can be added to runbook as guidance, not a code defect
- **Metric recording try-catch in `VideoModerationEmailListener`'s `persisted==null` branch** — would be the only try-caught metric call among ~20 call sites in the codebase (`VideoApiAdvice`, `VideoService`, `ReconciliationWorkerScheduler`, `PlaybackService`, `WebhookEventProcessorScheduler`, `MailService`, all unguarded), and the branch already unconditionally `throw`s an `IllegalStateException` immediately after, driving the same retry behavior regardless of whether the metric call itself threw. No behavior a caller can observe would change.
- **Null-guard on `VideoModerationEmailListener`'s injected `MailMetrics` field** — none of this class's other five `@RequiredArgsConstructor` final fields are null-checked either, and Spring constructor injection already guarantees non-null (a missing bean fails startup, not a null field at call time). Singling out one field would be inconsistent with this class and the wider codebase.
- **CircuitBreakerFactory breaker-isolation test proposed for `SesCutoverPreflightResourceIT`** — false positive against this specific test class: it's a `@WebMvcTest` slice with `MailManager` itself mocked, so there is no real `CircuitBreakerFactory`/breaker in that context to test isolation against. Real breaker behavior for any template (including `sesPreflightService`) is exercised through `MailManager`'s real send path, already covered by the module's other `MailManager`-based ITs — a test belongs there, not in a resource slice that mocks the class the breaker lives inside.

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5, via `/bmad-dev-story`.

### Debug Log References

- `MailManagerDuplicateSendIdIT` mutation check (AC1): `envelopeEntityRepository.acquireSendIdLock(...)`
  temporarily commented out in `MailManager.sendEmailSync` → `racingSendsWithTheSameSendIdAndSameRecipients_realProductionShape_exactlyOneRealSendReachesTheRecipient`
  went red with a real Postgres unique-constraint `DataIntegrityViolationException` (`uk428hhm4tjgrg8cy2092q025po`
  duplicate `send_id`), reproducing the exact pre-fix collision; restored and re-verified green
  (`mvn -o failsafe:integration-test -Dit.test=MailManagerDuplicateSendIdIT,MailManagerRateLimitIT`, 5/5).
- `VideoModerationEmailListenerTest` mutation check (AC2): `mailMetrics.recordAdminAlertOutcomeUnknown()`
  temporarily commented out in the `persisted == null` branch → `noPersistedEnvelope_warnsNotYetVisible`
  went red (`Wanted but not invoked … Actually, there were zero interactions with this mock`); restored
  and re-verified green.
- `EmailTransportArchitectureTest` false start: the first `SesCutoverPreflightResource` draft injected
  `SesV2Client` directly and failed rules 3/4 (SES SDK confined to `infrastructure.ses`). Extracted a
  new `SesAccountChecker` (in `infrastructure.ses`, returns a plain SDK-free `AccountStatus` record) so
  the resource never imports the SDK; re-verified `EmailTransportArchitectureTest` green. A second,
  narrower false start: the resource's own javadoc spelled out the literal SDK package name, which the
  containment test's regex also matches inside comments — reworded across two `{@code}` spans so the
  literal string never appears contiguously (same fix pattern that test's own javadoc already documents
  for rule 2).
- `SesCutoverPreflightResourceIT` (`@WebMvcTest` slice) required two additional `@MockitoBean`s beyond
  the resource's own three collaborators — `VideoMetrics` (a global `@RestControllerAdvice`,
  `VideoApiAdvice`, needs it) and `JwtSecretService` (`SecurityAdviceFilter` needs it) — both pulled
  into every `@WebMvcTest` slice regardless of which controller is under test; mirrors
  `AdminFinanceResourceIT`'s identical pair.
- Code review (HIGH, AC1 null `sendId`): before patching, spun up a real `postgres:16` container
  (`docker run -e POSTGRES_PASSWORD=pw postgres:16`) and ran
  `SELECT pg_advisory_xact_lock(hashtext(NULL::text));` directly via `psql` — confirmed it returns a
  single null row with NO error, i.e. the function is STRICT and silently no-ops on a null key rather
  than raising. This is what the fix's Javadoc and commit message rely on, not a guess. `MailManagerSendIdValidationTest`
  mutation check: the null-guard's `if` condition temporarily short-circuited to `if (false && ...)` →
  `nullSendId_throwsImmediately_beforeAnyRepositoryInteraction` went red
  (`AssertionError: Expecting code to raise a throwable`); restored and re-verified green.

### Completion Notes List

- **AC1** — `EnvelopeEntityRepository.acquireSendIdLock(String)` (`SELECT pg_advisory_xact_lock(hashtext(?1))`,
  plain non-`@Modifying` native query — `@Modifying` was rejected: it routes through JDBC
  `executeUpdate()`, which the Postgres driver rejects for a `SELECT`) called as the first statement in
  `MailManager.sendEmailSync`, before `findBySendId`. Verified against a real Testcontainers Postgres,
  not assumed: `MailManagerDuplicateSendIdIT` rewritten with three tests — the real-production-shape
  concurrent-same-envelope race (exactly one real send reaches the recipient), the caller-misuse
  concurrent-different-recipients race (documents the Design Consideration's silent-overwrite trade —
  no longer throws post-fix, pinned by a test rather than left implicit), and a rollback case (first
  call marked `setRollbackOnly()`, second call runs as a clean first attempt). Mutation-verified (see
  Debug Log). `MailManagerRateLimitIT` re-run green, confirming the existing-row/pessimistic-lock path
  is unaffected. An inline code comment at the `applyDeliveryFlags` call site documents the AC's Design
  Consideration decision explicitly, per the story's own requirement.
- **AC2** — `MailMetrics.recordAdminAlertOutcomeUnknown()` added and called from
  `VideoModerationEmailListener.sendAdminAlertSync`'s `persisted == null` branch, alongside the existing
  diagnostic `WARN`. Diagnostics only, per the owner decision — no structural fix attempted.
  Mutation-verified (see Debug Log). Extended `VideoModerationEmailListenerTest` (both the
  not-yet-visible case and a `never()` assertion on the SENT-envelope case) and
  `VideoModerationAdminAlertEnvelopeIT`'s real, container-backed `persisted==null` case with a
  dedicated mocked `MailMetrics` to verify the counter increments on a genuine occurrence.
- **AC3** — `QuotaConfigService.resolvePlayerTierKey` now branches explicitly on
  `Optional<PaymentPlayerSubscription>` instead of chaining through `.map(...).map(...).orElse(...)`;
  the empty-optional (no subscription row at all) branch logs a distinct `WARN` and calls the new
  `VideoMetrics.recordQuotaTierFallback("no_subscription_row")`, distinct from the two already-logged
  fallback shapes (non-`Long` id; unrecognised tier string). Extended `QuotaConfigServiceTest` with
  three cases: the WARN+counter fires, the counter fires exactly twice for a single logical caller that
  invokes the resolver twice (mirroring `QuotaService.check`/`reserve`'s real back-to-back call shape),
  and the two pre-existing fallback shapes do *not* fire this new counter.
- **AC4** — No behavioral code change. Added a one-line inline code comment at
  `QuotaConfigService.resolvePlayerTierKey`'s `findByPlayerId(...)` call recording the accepted
  check-then-use tradeoff, plus a matching `[DECIDED 2026-09-16 (skillars-deferred-114): ...]`
  annotation in `deferred-work.md`.
- **AC5** — New `SesCutoverPreflightResource` (`platform.notification.api`, admin-only via
  `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)`, transport-gated via
  `@ConditionalOnProperty(app.email.transport=ses)`, `@Observed`), `POST /v1/admin/ses/preflight`. Live
  account check delegated to a new `SesAccountChecker` (`infrastructure.ses`) — extracted mid-implementation
  after `EmailTransportArchitectureTest` caught the resource's first draft importing the SES SDK
  directly (see Debug Log); `SesAccountChecker` bypasses `SesHealthIndicator`'s TTL cache and returns a
  plain, SDK-free `AccountStatus` record. Test send reuses `MailManager.sendEmailSync` directly (the
  real production path) with a fresh `UUID`-derived `sendId` per call and a new
  `EmailTemplate.SES_CUTOVER_PREFLIGHT` constant carrying its own isolated `"sesPreflightService"`
  circuit breaker (3-arg constructor, not the shared `"emailService"` breaker). New
  `mails/sesCutoverPreflight.html` Thymeleaf template + `email.ses.cutover_preflight.title` subject key
  added to all four message bundles (verified via `EmailTemplateSubjectKeyParityTest` and the other
  i18n completeness suites, all green). Response is three records
  (`AccountCheckResult`/`TestSendResult`/`SesPreflightResult`) matching the story's illustrative shape,
  with a validated `SesCutoverPreflightRequest` record (`@NotBlank @Email`). `docs/deployment/runbook.md`'s
  SES cutover gate section rewritten to reference the endpoint for step 3. Verified by a hermetic
  `SesAccountCheckerTest` (mocked `SesV2Client`, mirrors `SesHealthIndicatorTest`'s pattern — healthy,
  partial-response, and `SdkException` paths), a hermetic `SesCutoverPreflightResourceTest` (mocked
  `SesAccountChecker`/`MailManager`/`EnvelopeEntityRepository`, the resource's own combining logic —
  `ready` computation across all outcome combinations), and a `@WebMvcTest` slice
  `SesCutoverPreflightResourceIT` covering the endpoint end-to-end (200/`ready` true and false,
  validation 400s) and `@PreAuthorize` enforcement (403 for `COACH`/`PARENT`, 200 for `ADMIN`/`LTD_ADMIN`,
  401/403 unauthenticated) — the first such enforcement test in this codebase, per the story's own audit
  that no precedent existed to extend.
- **AC6** — `deferred-work.md`'s `code review of skillars-deferred-113` section updated: the
  "Concurrent retry from multiple instances" and "Missing subscription row silently downgrades tier"
  bullets deleted outright (closed by real fixes, AC1/AC3); "Throw-on-null is defensive, not curative"
  and "No transactional consistency tier/quota lookup" retagged `[DECIDED 2026-09-16
  (skillars-deferred-114): ...]` (AC2/AC4, no code fix, decisions only); "Runbook checklist manual, not
  code-enforced" and "No automated health check integration" both deleted, replaced by one line noting
  the runbook now references the executable preflight endpoint (AC5). `main.pending_blob_deletions`
  bullet (a different section) left untouched, per the Provenance table's explicit out-of-scope
  direction. Reconstruction check: every surviving line in the target section matches the pre-edit
  content, in order, with only the specified deletions/retags applied.
- **Overall:** all targeted suites green — notification module (455 tests via
  `com.softropic.skillars.platform.notification.**`), video/quota module (part of the same 455), SES
  infrastructure + email transport architecture containment (`com.softropic.skillars.infrastructure.ses.**`,
  `com.softropic.skillars.infrastructure.email.**`), and i18n completeness suites — a combined final
  sweep of `notification.**` + `video.**` + `infrastructure.ses.**` + `infrastructure.email.**` ran
  718 tests, 0 failures, 0 errors (4 pre-existing skips, unrelated). No `mvn verify` run locally — CI is
  the gate, per project convention.

### File List

**New:**
- `src/main/java/com/softropic/skillars/infrastructure/ses/SesAccountChecker.java` (AC5 — live,
  uncached SES account check; keeps the SES SDK confined to `infrastructure.ses`)
- `src/main/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResource.java` (AC5)
- `src/main/resources/mails/sesCutoverPreflight.html` (AC5 — probe email template)
- `src/test/java/com/softropic/skillars/infrastructure/ses/SesAccountCheckerTest.java` (AC5 — hermetic,
  mirrors `SesHealthIndicatorTest`)
- `src/test/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResourceTest.java`
  (AC5 — hermetic, resource combining logic)
- `src/test/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResourceIT.java`
  (AC5 — `@WebMvcTest` slice, endpoint end-to-end + `@PreAuthorize` enforcement)
- `src/test/java/com/softropic/skillars/platform/notification/service/MailManagerSendIdValidationTest.java`
  (code review patch, HIGH — hermetic null-`sendId`-guard test, mutation-verified)

**Modified:**
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java` (AC5 — new
  `SES_CUTOVER_PREFLIGHT` constant, isolated `"sesPreflightService"` breaker)
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`
  (AC2 — diagnostic counter call)
- `src/main/java/com/softropic/skillars/platform/notification/repo/EnvelopeEntityRepository.java` (AC1
  — `acquireSendIdLock` native query)
- `src/main/java/com/softropic/skillars/platform/notification/service/MailManager.java` (AC1 — advisory
  lock as `sendEmailSync`'s first statement + Design Consideration inline comment; code review patches —
  sendId null-guard [HIGH], `sendEmailSync` exception-handling Javadoc [MEDIUM], gated `log.warn` +
  `recipientEmails(...)` helpers on the silent-overwrite path [MEDIUM])
- `src/main/java/com/softropic/skillars/platform/notification/service/MailMetrics.java` (AC2 —
  `recordAdminAlertOutcomeUnknown()`; code review patch — extracted `MAIL_ADMIN_ALERT_OUTCOME_UNKNOWN`
  constant [LOW])
- `src/main/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResource.java`
  (code review patch — null-read-back error message now hints at visibility lag [LOW])
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` (AC3 — explicit
  `Optional` branch + WARN + counter; AC4 — inline decision comment; code review patch — replaced with
  verified concrete caller-inventory comment [MEDIUM])
- `src/main/java/com/softropic/skillars/platform/video/service/VideoMetrics.java` (AC3 —
  `recordQuotaTierFallback(String)`)
- `src/main/resources/i18n/messages.properties` (AC5 — new subject key)
- `src/main/resources/i18n/messages_de.properties` (AC5)
- `src/main/resources/i18n/messages_en.properties` (AC5)
- `src/main/resources/i18n/messages_fr.properties` (AC5)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/MailManagerDuplicateSendIdIT.java`
  (AC1 — rewritten: real-production-shape race, caller-misuse race, rollback case; code review patch —
  added a 4th scenario isolating the advisory-lock/`PESSIMISTIC_WRITE` interaction against an
  already-existing, pre-committed row [LOW])
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationAdminAlertEnvelopeIT.java`
  (AC2 — counter assertion on the real, container-backed `persisted==null` case)
- `src/test/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListenerTest.java`
  (AC2 — counter assertions, constructor updated)
- `src/test/java/com/softropic/skillars/platform/video/service/QuotaConfigServiceTest.java` (AC3 — three
  new cases)
- `docs/deployment/runbook.md` (AC5 — SES cutover gate step 3 now references the preflight endpoint)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC6 — ledger hygiene)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status tracking)
- `_bmad-output/implementation-artifacts/skillars-deferred-114-mail-concurrency-quota-audit-ses-preflight.md`
  (this story file)

## Change Log

- 2026-09-16: Story created via `/bmad-create-story`, mined from `deferred-work.md`'s
  `code review of skillars-deferred-113` section per explicit project-owner direction on bucket
  order. Scope decisions (AC1 real fix, AC2 diagnostics-only, AC3 audit trail, AC4 no-code,
  AC5 preflight tooling, `main.pending_blob_deletions` out of scope) made together with the project
  owner before drafting ACs — see the Provenance & Scoping table above.
- 2026-09-16: Senior-dev audit (`story-review.md`) applied. AC1 gained an implementation caveat about
  `pg_advisory_xact_lock`'s void return having no existing native-query precedent in this codebase.
  AC3 gained a documented characteristic (the fallback fires twice per request at a confirmed real
  call site, `VideoResource.java:123-124`) plus a test case for it. AC5 gained two concrete
  requirements that were previously implicit — an isolated circuit breaker for the probe template
  (otherwise a failing preflight check could trip the shared `emailService` breaker used by real
  booking traffic) and a fresh `sendId` per invocation (otherwise a second run silently stops being an
  independent test, since `envelope_entity.send_id` is DB-unique) — and a corrected claim that no
  `@PreAuthorize`-enforcement test precedent exists anywhere in this codebase to copy from. The
  review's four Low findings were folded in too: AC1's caller inventory now names all three real
  callers of `sendEmailSync` (was missing `NotificationEmailOutboxHandler`, added to Files to Read
  alongside `OutboxRowProcessor`), gained a rollback test case and a one-sentence `hashtext()`
  collision caveat; AC5 gained explicit Jakarta Validation request-record and `@Observed` requirements
  matching this codebase's own resource conventions.
- 2026-09-16: Second-pass audit (a fresh `story-review.md`) applied, after checking each finding
  against real code — no false positives found. AC3's "known characteristic" was corrected from a
  single-example (`VideoResource.java:123-124`) to the actual complete caller inventory: verified
  `QuotaService.check()`/`reserve()` (`QuotaService.java:38,90`) also fire the resolver, and
  `VideoService`'s upload-initiation flow calls `check()` then `reserve()` back-to-back for the same
  `ownerId` (`VideoService.java:140/154`, `:270/279`) — a hotter, more concrete double-fire path than
  the story previously cited. AC1's Design Consideration was corrected: the silent recipient-list
  overwrite on a same-`sendId`/different-recipients race is not fully "pre-existing" as previously
  framed — the advisory lock specifically converts what is *today* a loud DB-collision failure in the
  *concurrent* case into the same silent update the *sequential* case already had; added an explicit
  test case and an inline-code-comment requirement so this is a documented decision, not a side effect
  found later. AC4 gained a required inline code comment in `QuotaConfigService.java` alongside the
  ledger annotation, so the accepted tradeoff is visible to the next person reading the method, not
  only to someone who thinks to check the ledger. AC5 gained a concrete response-shape sketch (three
  Java records) so "structured JSON result" isn't left for the dev agent to invent from scratch, plus
  a note that the isolated circuit-breaker/template name doubles as the filter an operator uses to
  exclude preflight test sends from production dashboards. One suggestion from this pass — a Dev Note
  confirming `CircuitBreakerFactory.create()` lazily initializes a breaker on first use — was
  considered and declined as unnecessary restatement of well-established Resilience4j/Spring behavior
  already implied elsewhere in AC5.
- 2026-09-16: `/bmad-dev-story` implementation complete, all 6 tasks. AC1: Postgres advisory-lock
  (`pg_advisory_xact_lock(hashtext(sendId))`) closes the first-send race in `MailManager.sendEmailSync`;
  `MailManagerDuplicateSendIdIT` rewritten (real-production-shape race, caller-misuse race, rollback
  case), mutation-verified against a real Testcontainers Postgres. AC2: `MailMetrics.recordAdminAlertOutcomeUnknown()`
  wired into `VideoModerationEmailListener`'s `persisted==null` branch, mutation-verified. AC3:
  `QuotaConfigService.resolvePlayerTierKey` branches explicitly on the subscription `Optional`, logging
  and counting the no-subscription-row fallback distinctly, with a test proving the double-fire
  characteristic. AC4: inline code comment + matching ledger annotation, no behavioral change. AC5: new
  `SesCutoverPreflightResource` (`POST /v1/admin/ses/preflight`) — mid-implementation, the first draft's
  direct `SesV2Client` injection was caught by `EmailTransportArchitectureTest`'s SDK-containment rules,
  so the live account check was extracted into a new `infrastructure.ses.SesAccountChecker`; the
  resource itself never imports the SES SDK. New `EmailTemplate.SES_CUTOVER_PREFLIGHT` (isolated
  `"sesPreflightService"` breaker), new Thymeleaf template, i18n keys in all four bundles, runbook
  updated, three new test classes (hermetic account-check, hermetic combining-logic, `@WebMvcTest`
  end-to-end + first-in-codebase `@PreAuthorize` enforcement test). AC6: `deferred-work.md`'s
  `code review of skillars-deferred-113` section closed per the story's exact bullet-by-bullet
  instructions; reconstruction check passed. Combined final sweep (`notification.**` + `video.**` +
  `infrastructure.ses.**` + `infrastructure.email.**`): 718 tests, 0 failures, 0 errors. No `mvn verify`
  locally — CI is the gate. Status → review.
- 2026-09-16: Code review findings addressed (`## Code Review Findings` section above, "Patches"
  subsection). 7 of 10 patched for real: AC1 sendId null-guard (confirmed against a real Postgres 16
  container that the actual failure mode is a silent lock no-op, not an error, before writing the
  fix — HIGH), a new scenario-4 test isolating the advisory-lock/`PESSIMISTIC_WRITE` interaction
  against an already-existing row (LOW), a gated `log.warn` on the silent recipient-overwrite path
  (MEDIUM), a verified concrete caller-inventory comment replacing the vaguer original in
  `QuotaConfigService` (MEDIUM), a method-level Javadoc on `sendEmailSync`'s exception-handling
  contract (MEDIUM), a `MailMetrics` constant extraction matching the class's own existing pattern
  (LOW), and a reworded `SesCutoverPreflightResource` error message hinting at visibility lag (LOW).
  3 investigated and dismissed rather than patched, each with a concrete reason recorded in the
  Dismissed subsection: a metric-call try-catch that would be the only one of ~20 such call sites in
  the codebase and changes no observable behavior (a `throw` follows it unconditionally either way);
  a null-guard on one constructor-injected field with no precedent on this class's other five; and a
  circuit-breaker-isolation test proposed against a `@WebMvcTest` slice that mocks the very class
  (`MailManager`) the breaker lives inside, so has no real breaker to test. New hermetic
  `MailManagerSendIdValidationTest` (2 tests) added for the null-guard, mutation-verified. Full
  regression sweep after all patches (`notification.**` + `video.service.QuotaConfigServiceTest` +
  `infrastructure.ses.**`, surefire) plus a targeted failsafe re-run of `MailManagerDuplicateSendIdIT`
  (4/4, ~50s) and `VideoModerationAdminAlertEnvelopeIT` (3/3): 0 failures across all report files. No
  `mvn verify` locally — CI is the gate. Status remains review.
- 2026-09-16: A follow-up ad-hoc audit (transaction boundaries / TOCTOU / batch-memory patterns across
  every `@Scheduled`/batch-processing class in the notification + video modules) found two unrelated,
  pre-existing issues in `ModerationSlaMonitorService` and `VideoLifecycleScheduler` — neither introduced
  by this story, both out of this story's own AC scope. Filed as a separate story
  (`skillars-deferred-115-scheduler-transaction-isolation-hardening`) on its own branch off `master`,
  not folded into this one. Status → done.
