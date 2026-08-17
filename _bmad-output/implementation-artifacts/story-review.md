# Senior-Dev Review — Story `skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test`

Reviewer: senior dev (adversarial audit for missed corner cases, false assumptions, missed flows)
Date: 2026-08-17
Story reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test.md` (status `ready-for-dev`)
Verified against: working tree at branch `repo-ordering-test-coverage`, HEAD `efafd54`

> **Supersedes** the previous contents of this file, which reviewed the earlier draft
> (`skillars-deferred-27-test-coverage-and-booking-error-messaging-fixes.md`, now deleted). The story's
> References entry "[Source: story-review.md] — senior-dev review of this story's draft" now resolves to
> *this* document, not the draft review it means. Fix that citation or drop it.

Every finding below was confirmed by reading current source. Line numbers were re-derived at review time,
not taken from the story's own citations.

---

## Verdict

**Rework AC2, AC3 and AC4 before handoff. AC1 and AC5 ship as written.**

The rework did its job on everything the previous review raised: the ID collision is resolved, the two
already-closed items are gone, `helpCode` → `errorMsg.errorKey` is corrected and correctly explained,
the reschedule branch is narrowed to one code, the `SessionPackPaymentResourceIT`/`BasePaymentIT`
contradiction is resolved, and the `permitAll()`-still-401s reasoning is now right. No item is
pre-closed this time — all five carry live `[OWNED BY skillars-deferred-28 ACn]` markers in the ledger
and `sprint-status.yaml:1007` exists at `ready-for-dev`.

What remains:

- **AC2 reintroduces, for the batch flow, the exact defect the last review caught for reschedule** — two
  of its six branches are wired to a flow that structurally cannot throw them (B1).
- **AC2's prescribed English copy states the wrong batch limit** — it says 10, the configured limit is 5
  (B3).
- **AC3's mock list is two beans short of what the slice needs**; as specified the test class will not
  start (B2).
- **AC4 tells the dev to write a comment whose stated reason is false** (M1).

---

## Blockers

### B1 — AC2 wires two impossible codes into `submitBatchRequest()`; the "shared coach-suspension check" claim is false

AC2 states:

> `booking.coachUnavailable` — `BookingError.COACH_UNAVAILABLE` — single booking create, **batch create**
> (both go through the shared coach-suspension check)
> `booking.slotUnavailable` — `BookingError.SLOT_UNAVAILABLE` … — single booking create, **batch create**

and Task 2 accordingly says `submitBatchRequest()` should "branch on the same field for **all 6 codes**".

`createBatch` (`BookingBatchService.java:95-219`) — the only backend method behind
`submitBatchRequest()` — throws exactly **four** of the six:

| Code | Line |
|---|---|
| `booking.batchSizeExceeded` | `:98` |
| `booking.invalidSessionDuration` | `:139` |
| `booking.duplicateSlotStartTime` | `:159` |
| `booking.overlappingSlots` | `:179` |

It never throws `COACH_UNAVAILABLE` and never throws `SLOT_UNAVAILABLE`:

- **There is no shared coach-suspension check.** `createBatch:115-117` is its own inline check —
  `if (coach.getStatus() != CoachProfileStatus.ACTIVE) throw new OperationNotAllowedException("Coach
  profile is not active", SecurityError.MISSING_RIGHTS)`. It collapses *every* non-ACTIVE status,
  SUSPENDED included, into `MISSING_RIGHTS`. `BookingService.createBookingRequest` by contrast has two
  separate suspension checks (`:179` unlocked, `:239` under the pessimistic lock) that *do* emit
  `COACH_UNAVAILABLE`, then a separate non-active check emitting `MISSING_RIGHTS` (`:184`, `:244`).
  Three independent checks, two different codes, nothing shared.
- **`createBatch` has no cross-booking overlap check at all.** Its only overlap logic
  (`:173-181`) compares batch slots against *each other* and raises `booking.overlappingSlots`. There is
  no `findOverlappingBookings` call anywhere in the method — that check exists only in
  `createBookingRequest:247-255`.

**The DB-constraint fallback does not rescue it either.** AC2 claims `booking.slotUnavailable` is "also
raised directly by `ApiAdvice`'s `excl_bkg_coach_slot_overlap` constraint mapping … — single booking
create, batch create". The constraint's partial predicate
(`V87__booking_overlap_exclusion_constraint.sql:20-22`) is
`WHERE status IN ('ACCEPTED','PAYMENT_PENDING','CONFIRMED','UPCOMING','IN_PROGRESS','PAUSED')` — REQUESTED
is *deliberately* excluded, with a five-line comment in the migration saying so. Both parent create paths
insert REQUESTED: `createBatch:195` sets it explicitly, and `createBookingRequest:279-288` sets no status
at all so `Booking.java:90`'s `if (status == null) status = "REQUESTED"` applies. **So the constraint
cannot fire on either parent-side create — it fires only on the coach-side accept**, which this AC puts
out of scope.

This is the same class of error as the previous review's M1 (reschedule branching on codes it cannot
receive), corrected there and reintroduced here for batch.

**Action:**
- `submitBatchRequest()` branches on **four** codes: `batchSizeExceeded`, `invalidSessionDuration`,
  `duplicateSlotStartTime`, `overlappingSlots`.
- Delete "batch create" from the `coachUnavailable` and `slotUnavailable` rows, and delete the
  "(both go through the shared coach-suspension check)" parenthetical — it is false.
- Rewrite the `excl_bkg_coach_slot_overlap` sentence: that mapping is reachable from the coach accept
  path only, not from either flow in this AC's scope.
- `submit()` (single create) is unaffected — all three of its codes are genuinely reachable
  (`:179`/`:239` COACH_UNAVAILABLE, `:215` INVALID_SESSION_DURATION, `:254` SLOT_UNAVAILABLE). Keep it as
  specified.

### B2 — AC3's mock list is incomplete; the test class will not start

AC3 says:

> `@MockitoBean` the resource's **three** dependencies — `SubscriptionService`, `CoachProfileRepository`,
> `SecurityUtil` — the same way `PlayerSubscriptionOwnershipIT` mocks its own controller-adjacent beans.

`PlayerSubscriptionOwnershipIT` mocks **six** (`:71-76`), and two of the extra three are not about
`PlayerOwnershipGuard` — they are what makes the MVC slice bootable at all:

- `JwtSecretService` — `SecurityAdviceFilter` is a `@Component` extending `OncePerRequestFilter`
  (`SecurityAdviceFilter.java:34-46`), so `@WebMvcTest` includes it, and its constructor requires
  `JwtSecretService`.
- `VideoMetrics` — `VideoApiAdvice` is `@RestControllerAdvice` (`VideoApiAdvice.java:39-42`), so
  `@WebMvcTest` includes it, and it requires `VideoMetrics`.

`SessionPackPaymentResourceIT` — the other `@WebMvcTest` in this same package — mocks both for the same
reason (`:72-73`). Two independent precedents, same two beans.

Following AC3 literally produces a context-load failure, and the dev spends the session debugging Spring
wiring instead of writing the nine tests.

`PlayerProfileRepository` is genuinely *not* needed — it exists in the precedent only to feed the real
`PlayerOwnershipGuard`, which the new class does not `@Import`.

**Action:** AC3 should say "mock the same set `PlayerSubscriptionOwnershipIT` mocks, minus
`PlayerProfileRepository`: `SubscriptionService`, `CoachProfileRepository`, `SecurityUtil`,
`JwtSecretService`, `VideoMetrics`" — and say *why* the last two are there, so a future reader doesn't
prune them again.

### B3 — AC2's prescribed English copy for `batchSizeExceeded` states the wrong limit

AC2 prescribes: `booking.batchSizeExceeded=You can request up to 10 sessions in one batch.`

The limit is `configService.getLong("booking.batch.maxSize")` (`BookingBatchService.java:96`), seeded to
**`'5'`** by `V36__booking_batches.sql:19` and changed by no later migration
(`grep -rn "booking.batch.maxSize" src/main/resources/db/migration/` returns that one line).

The 10 comes from `CreateBatchRequest`'s `@Size(min = 1, max = 10)` — a **different** rejection producing
a **different** key: `MethodArgumentNotValidException` → `ApiAdvice.methodArgumentNotValidExceptionHandler`,
never `booking.batchSizeExceeded`. So as specified this AC ships a user-facing number that is wrong for
the error it is attached to, translated into German and French as well.

It is also unnecessary. The page already holds the real value: `maxBatchSize` (`BookingRequestPage.vue:258`,
populated from `getBatchConfig()` at `:530`), used by `batchAtMax` (`:262`) and `toggleSlotInBasket`
(`:300`), and the neighbouring i18n key already parameterizes it —
`booking.batch.selectedCount: '{n} of {max} slots selected'` (`en-US/index.js:901`).

**Action:** `'You can request up to {max} sessions in one batch.'`, passed
`t('booking.errors.batchSizeExceeded', { max: maxBatchSize.value })`, mirrored in de-DE/fr-FR. The
backend `messages*.properties` entry cannot be parameterized (the throw site passes no args —
`BatchRuleViolationException("booking.batchSizeExceeded")`), so word that one without a number.

---

## Material spec errors

### M1 — AC4 prescribes a comment whose stated reason is false

AC4 requires the comment to state that a negative `hoursUntilSession`

> reflects a caller using the wrong event rather than a refund-logic bug … a booking whose session start
> has already passed should have been settled via a `NO_SHOW_COACH`/`NO_SHOW_PLAYER` event, not a late
> parent cancellation

`cancelBookingAsParent` (`BookingService.java:610-662`) is the ordinary parent-facing cancel path and it
fires exactly `BookingEvent.CANCEL_PARENT` (`:653`). It has **no start-time guard**: it checks ownership,
reads the locked row, refuses only on `PAYMENT_PENDING` + `CAPTURE_PENDING` (`:639-643`), and transitions.
A booking still sitting in UPCOMING past its start time — the coach never started the session — is
cancellable by the parent through the normal UI, and lands squarely in the negative branch.
`CANCEL_PARENT` is the *correct* event there; nothing in the code routes that parent to a `NO_SHOW_*`
event, and nothing else can fire one on their behalf.

So the prescribed comment documents a reachable product behaviour as caller error. That is worse than no
comment: it tells the next reader the branch is unreachable-by-design and closes off the question. And the
question is live — the most likely real-world instance is a coach no-show, which is precisely where
`"NONE"` is the harshest of the three outcomes.

**Action:** either
- document what is actually true — no guard prevents a post-start parent cancellation, the value goes
  negative, and it falls through to `"NONE"`; whether that case should instead settle as a coach no-show
  is an open product question — and file that question as a ledger item; or
- drop AC4 from this story and file it as the product question it is.

Do not have the dev assert an intent the code does not have.

**Adjacent, pre-existing, do not fix here (worth a ledger item):** `cancelBookingAsParent:647-649`
computes a *second, independent* refund rule —
`refundEligible = paymentWasCaptured && requestedStartTime.isAfter(now.plus(24, HOURS))` — and puts it on
`BookingCancelledByParentEvent`, while `applyRefundLogic` writes the three-tier FULL/PARTIAL/NONE onto the
row. For a cancellation 6–24 h out the row says `"PARTIAL"` and the event says not-eligible. AC4's
one-line comment sits directly on top of that divergence.

### M2 — AC3's stated gap includes request validation, but its prescribed coverage never exercises it

AC3 justifies itself with:

> only the service-layer `SubscriptionLifecycleIT.java` exists … (auth annotations, **request validation**,
> response serialization are all untested for these nine)

Its "Minimum coverage" list is happy-path plus role/401 only. No case sends an invalid body, so after the
AC is complete request validation is still untested — the rationale and the deliverable disagree.

This is one cheap case away from being true: every request record carries `@NotNull` on every field —
`CoachSubscribeRequest(@NotNull String tier)`, `CoachChangeTierRequest(@NotNull String newTier)`,
`PlayerSubscribeRequest(@NotNull Long playerId, @NotNull String tier, @NotNull String billingInterval)`,
`PlayerChangeTierRequest(@NotNull Long playerId, @NotNull String newTier)`.

**Action:** add one 400 case (e.g. `POST /player/subscribe` with `{}`), or strike "request validation"
from the rationale. Prefer the former — it is three lines.

**Also worth stating explicitly in AC3:** because `SubscriptionService` is `@MockitoBean`-mocked, the
ownership enforcement protecting the three `playerId`-taking mutations is invisible to these tests. It
lives entirely inside the service — `assertPlayerOwnership` at `SubscriptionService.java:276`
(`subscribePlayer`), `:339` (`changePlayerTier`), `:414` (`cancelPlayerSubscription`). **There is no
IDOR** — those endpoints are properly guarded, unlike what their bare `@PreAuthorize(HAS_PARENT_ROLE)`
suggests at a glance. But a reader of "9 endpoints now have HTTP-level coverage" would reasonably assume
the check was tested, and it is not. One sentence in the AC prevents a false sense of coverage.

### M3 — AC2's verification plan will regress Prettier on four currently-clean files

AC2 requires only `npx eslint` clean. Checked at review time from `src/frontend`:

```
npx prettier --check src/i18n/{en-US,de-DE,fr-FR}/index.js \
  src/pages/parent/BookingRequestPage.vue src/pages/parent/ParentBookingsPage.vue
→ [warn] src/pages/parent/ParentBookingsPage.vue   ← only this one
```

The three i18n bundles and `BookingRequestPage.vue` are **currently Prettier-clean** — and the bundles are
clean precisely because merged deferred-27 AC6 just cleaned them (`deferred-work.md:1245` records the
`prettier --write` on exactly those three files). Hand-adding six keys per bundle is very likely to
re-break them, silently undoing that work.

**Action:** add `npx prettier --check` on the touched files to Task 2, and `--write` on the four that are
clean today. Leave `ParentBookingsPage.vue`'s pre-existing violation alone (in scope creep terms it is the
same call deferred-27 made about the wider ~124-file debt).

### M4 — AC6 under-specifies the ledger convention it claims to follow, and ignores the `[OWNED BY]` markers already present

All five target items already carry an ownership marker placed during story creation:
`deferred-work.md:809` (AC4), `:1120` (AC5), `:1253` (AC2), `:1387` (AC3), `:1439` (AC1) — each
`[OWNED BY skillars-deferred-28 ACn — see …]`.

AC6 says to "append the closure note the same way `skillars-deferred-24/-25/-26/-27` did". What those
stories actually did (`:569`, `:700`, `:748`, `:768`, `:784`, `:818`, `:852`, `:1245`, `:1435`, `:1440`,
`:1448`, `:1449`) is **replace** the ownership marker with `[CLOSED by … ACn]` **followed by a substantive
prose summary of what was done** — often several sentences, including corrections found in review. AC6 as
written yields a bare marker sitting next to a now-stale `[OWNED BY]` on the same line, matching none of
the twelve precedents.

**Action:** AC6 should say: replace each `[OWNED BY skillars-deferred-28 ACn …]` with
`[CLOSED by skillars-deferred-28 ACn]` plus a short description of the actual change, per the existing
entries at `:1440`/`:1448`/`:1449`.

---

## Minor

- **Task 3 count mismatch.** "Implement the minimum-coverage cases listed in AC3 (**8 endpoints**: coach
  tiers/me/subscribe/change-tier/cancel, player tiers/subscribe/change-tier/cancel)" — that list is nine,
  and AC3 correctly says nine throughout. Same class of arithmetic slip as the "four catch blocks" the
  last review caught; the story even warns "get this count right".
- **Wrong task number, three times.** AC2's scope note ("logged as a new `deferred-work.md` item by
  **Task 4**"), AC6's own bullet, and the `DrillDetailPanel.vue` reference all point at Task 4. Task 4 is
  the AC4 comment task; ledger logging is **Task 6**.
- **`ApiAdvice` path is wrong in References.** Cited as
  `src/main/java/com/softropic/skillars/infrastructure/security/api/ApiAdvice.java`; the real path is
  `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`. The line numbers inside it
  are mostly right — `toErrorDTO` at `:619-630` ✓, `logError` at `:636-651` ✓ — but `:142` is
  `defaultErrorHandler`; the `excl_bkg_coach_slot_overlap → booking.slotUnavailable` mapping is at `:153`
  (inside `CONSTRAINT_MAPPINGS`, `:150-154`), and it is in `CONFLICT_CONSTRAINTS` at `:163`, hence 409.
- **AC4 line drift.** AC4 says "`BookingService.java:762-763` computes `hoursUntilSession` and maps it";
  `hoursUntilSession` is at `:761` and the eligibility ternary at `:762`. "Add a short comment directly
  above line 762" would land between the two.
- **AC5's mirror target contains dead code.** `CashOutServiceTest.processCashOut_feeCalculatedCorrectly_…`
  declares `ArgumentCaptor<BigDecimal> amountCaptor` and `ArgumentCaptor<String> typeCaptor` and never
  uses either. "Mirroring its structure **exactly**" would clone them. Tell the dev to drop them.
- **Reachability of the surviving batch branches** — expectation-setting, not a defect. Of the four codes
  batch can actually throw, `duplicateSlotStartTime` cannot be produced by the UI (the basket is keyed by
  `startDatetime` — `isSlotInBasket`/`removeSlotFromBasket`), and `batchSizeExceeded` cannot either
  (`toggleSlotInBasket:297-303` caps the basket at `maxBatchSize`) except under config drift or a failed
  `getBatchConfig()`. `overlappingSlots` **is** genuinely reachable — the slot list can present
  overlapping rows of different lengths, per `BookingRequestPage.vue`'s own `slotRows` comment. Keep all
  four branches (they are cheap and defensive), but do not describe them all as user-visible wins.
- **AC2's headline oversells the reschedule flow.** "shows a specific message instead of one generic
  toast" — `requestReschedule` has five distinct rejections and this AC improves one. The other four all
  emit `SecurityError.MISSING_RIGHTS`: not-owner (`:57-59`), wrong status (`:60-63`), start not in future
  (`:64-67`), end not after start (`:68-71`), and — the most likely one in practice — "A pending
  reschedule request already exists" (`:94-98`), i.e. the double-submit case. Correctly out of scope per
  the AC's own note; just don't claim more than 1-of-5.
- **Two catch blocks cover more than their submit call.** `submit()`'s catch also wraps
  `submitBookingRequest`'s trailing `loadParentBookings()` (`booking.store.js:343-346`), and
  `submitReschedule()`'s wraps `handleRequestReschedule`'s trailing `loadParentBookings()` (`:452-464`).
  A reload failure *after* a successful write shows the submit-failed toast. Pre-existing, and the new
  branches degrade correctly (no `errorKey` match → generic message), so no action — noted so it isn't
  mistaken for a regression during hand-tracing.
- **Cross-class `@Import` is legal but not this package's convention.** `@Import(PlayerSubscriptionOwnershipIT.TestSecurityConfig.class)`
  works — same package, and a `@TestConfiguration` is a plain `@Configuration` when explicitly imported.
  But `SessionPackPaymentResourceIT` keeps its own copy rather than importing, so reuse here couples two
  ITs: a future edit to `PlayerSubscriptionOwnershipIT`'s filter chain silently changes
  `SubscriptionResourceIT`'s expectations. Defensible either way; worth one sentence in the AC.

---

## What is actually clean

**AC1 — ship unchanged.** Re-verified: `CoachMediaItem` is `@Getter @Setter @NoArgsConstructor`,
`onCreate()` is package-private at `CoachMediaItem.java:34-37` with the
`if (uploadedAt == null)` guard, `grep -rln CoachMediaItem src/test/java` is empty, and nothing in
`src/main` constructs the entity. The inline snippet compiles as written. The scope boundary it draws
(callback logic, not the "nothing constructs this yet" gap) is right.

**AC2's mechanism — verified end to end.** The `helpCode` → `errorMsg.errorKey` correction is right, and
the whole chain holds:

- `OperationNotAllowedException` → `operationDeniedHandler` (`ApiAdvice:267-277`) →
  `handleSecErrorAndReturnDTO(exception, defaultMsg, msgKey = exception.getErrorCode().getErrorCode())` →
  `logErrorAndReturnDTO` → `toErrorDTO` (`:619-630`) → `new ErrorDto(helpCode, new ErrorMsg(msgKey, message))`.
  403.
- `BatchRuleViolationException` → `batchRuleViolationHandler` (`:259-265`), passing
  `exception.getErrorCode()` as `msgKey` verbatim. 400.
- `BookingError.getErrorCode()` yields exactly the three camelCase strings AC2 lists.
- Frontend path is a clean pass-through: `booking.api.js:21,56,61` are bare `api.post` one-liners; the
  stores re-throw the original error object (`booking.store.js:458-460` reschedule, `:532-534` batch;
  `submitBookingRequest:343-346` doesn't catch at all); and `boot/axios.js` only `console.warn`s on 403
  (`:154-158`) and ends with `return Promise.reject(error)` (`:175`), so `error.response.data` is intact.
  `err?.response?.data?.errorMsg?.errorKey` is genuinely readable in all three catch blocks.

**AC2's i18n groundwork is accurate.** None of the six codes exists anywhere under
`src/main/resources/` (grep returns zero). The `booking.` hits in the properties files are all
`email.booking.*`, as the story says. The frontend block structure checks out exactly — `booking` at
en-US `:743`, de-DE `:268`, fr-FR `:1021`, with `requests`/`completion`/`reschedule`/`batch` siblings at
en-US `744/861/877/897`, de-DE `269/405/421/442`, fr-FR `1022/1140/1156/1176` — and no `errors` block
exists in any bundle. `messages_fr.properties` really does mix both escaping styles
(`:53` `Référence` vs. literal UTF-8 elsewhere), and `MvcConfig:30`'s
`setDefaultEncoding("UTF-8")` makes the literal style safe. The story's decision to leave
`error-messages.properties` alone is correct — it has no locale variants and `MvcConfig:29` lists
`messages` as a basename, so the new keys resolve.

**AC3's premise and inventory — accurate.** 10 endpoints; the `@PreAuthorize` guards, the two 200-returning
`subscribe` endpoints and the three `204 No Content` endpoints are all as described.
`grep -rln "payment/subscriptions" src/test/java` returns `PlayerSubscriptionOwnershipIT` and nothing
else, and `SubscriptionLifecycleIT extends BasePaymentIT` calling `subscriptionService.*` directly
confirms it is service-level. `HAS_COACH_ROLE`/`HAS_PARENT_ROLE` are the plain `hasRole('ROLE_COACH')`/
`hasRole('ROLE_PARENT')` the AC assumes (`SecurityConstants.java:35-36`). The `permitAll()`-still-401s
reasoning is right on both halves: the reused `TestSecurityConfig` declares
`anyRequest().authenticated()` with no carve-out, and in production
`/api/payment/subscriptions/**` is absent from `AppEndpoints.PUBLIC_ENDPOINTS` (`:24-42`, whose only
payment entry is `/api/payment/webhooks/stripe`). Dropping the duplicated ownership-guard case is right.

**AC5 — ship unchanged** (bar the dead-captor nit above). Both boundary assertions are correct as
specified, which is worth stating because both are the kind that silently pass for the wrong reason:

- `CreditRoutingTest`: with `getBalance` stubbed to `SESSION_PRICE`, `balance.min(price)` returns `this`
  on equality, so `creditToUse` keeps scale 2 and `eq(SESSION_PRICE)` matches under `BigDecimal.equals`
  (which compares scale). `stripeAmount` is `ZERO`, so `reserveCapture` is never reached and **no**
  `reservationGranted()` stub is needed — which also keeps Mockito strict-stubs quiet. The boundary under
  test (`PaymentLifecycleService:180`, `creditToUse.compareTo(sessionPrice) >= 0`) is exactly what the
  assertion pins.
- `CashOutServiceTest`: `balance.compareTo(requestedAmount)` is `0`, so `CashOutService:26`'s `< 0` guard
  passes; fee math is unchanged from the mirrored test (`100 × 0.025 + 0.25 = 2.75`, net `97.25`), so the
  `verify(paymentGateway).refund(…, 97.25)` assertion holds.

**No item is pre-closed — the failure mode that killed the last draft is absent.** All five ledger items
carry live `[OWNED BY skillars-deferred-28 ACn]` markers and none carries a `[CLOSED by …]` annotation.
`sprint-status.yaml:1007` exists at `ready-for-dev` with the full AC summary in the comment block above
it. The renumbering is complete and consistent.

---

## Recommended rework before handoff

1. **AC2 / Task 2 — B1:** `submitBatchRequest()` branches on four codes, not six. Delete the
   "shared coach-suspension check" claim and the "batch create" attributions for `coachUnavailable`/
   `slotUnavailable`. Correct the `excl_bkg_coach_slot_overlap` sentence (coach-accept path only —
   REQUESTED is excluded from the constraint).
2. **AC2 — B3:** `batchSizeExceeded` copy uses `{max}` fed from `maxBatchSize`, not a literal 10
   (configured limit is 5). Word the backend properties entry without a number.
3. **AC2 / Task 2 — M3:** add `prettier --check` on touched files, `--write` on the four clean ones.
4. **AC3 — B2:** mock five beans (`SubscriptionService`, `CoachProfileRepository`, `SecurityUtil`,
   `JwtSecretService`, `VideoMetrics`), with a note on why the last two are required.
5. **AC3 — M2:** add one invalid-body 400 case, or strike "request validation" from the rationale. Add a
   sentence noting the mocked service means `assertPlayerOwnership` is out of this AC's reach.
6. **AC4 — M1:** rewrite the comment to state what is true (no post-start guard exists on the parent
   cancel path) and file the "should a post-start parent cancellation settle as a coach no-show?" product
   question as a ledger item — or drop AC4 and file only the question.
7. **AC6 — M4:** replace the `[OWNED BY]` markers rather than appending beside them, and require the
   prose summary the twelve existing closures all carry.
8. **Minor sweep:** Task 3's "8 endpoints" → 9; three "Task 4" ledger references → Task 6; `ApiAdvice`
   path and `:142` → `:153`; AC4's `:762-763` → `:761-762`; tell the dev to drop the mirrored test's
   unused `ArgumentCaptor`s; fix this file's own citation in References.

**AC1 and AC5 need no changes.** Both can be implemented as written (AC5 minus the dead captors).
