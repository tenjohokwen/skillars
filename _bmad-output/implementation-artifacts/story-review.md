# Story Review: skillars-deferred-64-suspended-lock-consistency-late-cancel-conversion-and-config-safety-fixes

Reviewed against the current tree on `product-directed-fairness-and-consistency-fixes`
(HEAD `b918486`). Every finding below was verified directly against source (file/line citations
given); nothing here is speculative or restates a risk the story itself already calls out and
accepts. Items the story already flags and consciously accepts (e.g. AC3's coarser-than-per-viewer
lock, AC1's cross-module import direction as claimed) are only revisited where direct source
inspection shows the story's own supporting claim is factually wrong, not merely "worth
double-checking."

## Summary

| # | AC | Severity | Finding |
|---|----|----------|---------|
| 1 | AC4 | **High** | Coaches stop receiving any cancellation email once a late cancel converts to `CoachNoShowEvent` — a real, silent notification regression |
| 2 | AC1 | **High** | `CoachProfileServiceTest.java`, named as "the existing test file to extend," does not exist anywhere in the tree |
| 3 | AC6 | **High** | "No admin UI or endpoint writes to `main.platform_config`" is false — a live, admin-authorized `PUT /api/config/values/{key}` endpoint does exactly that today |
| 4 | AC4 | Medium | Auto-converting an ordinary late cancel into a no-show issues an unearned, uncontestable reliability strike without the parent ever attesting to a no-show |
| 5 | AC3 | Medium | The claimed "lock held only briefly, not for the whole call" trade-off is technically wrong given how `PessimisticLockRetryer` + `@Transactional` actually hold Postgres row locks |
| 6 | AC1 | Low | "Does not introduce a new module dependency direction" is inaccurate — this is the first `marketplace → booking` import in the codebase |
| 7 | AC5 | Low | Phase 1 and the proposed Phase 2 lookup are described as different queries; they are the same repository method |

---

## 1. AC4 — Coach loses all notification on a late-cancel-turned-no-show (High)

**Claim in story:** AC4 says the late-cancel branch should "run exactly what `recordNoShowCoach`
... already does," firing `CoachNoShowEvent` instead of the ordinary `CANCEL_PARENT` transition/event,
and lists test cases for the state transition and refund gating — but never considers what happens
to the *coach's* notification.

**What's actually true:**
- Today, an ordinary late cancel fires `BookingCancelledByParentEvent`, and
  `BookingEmailListener.onBookingCancelledByParent` (`BookingEmailListener.java:339-354`) emails the
  coach via `event.getCoachEmail()`.
- `CoachNoShowEvent` (`CoachNoShowEvent.java:9-46`) has **no `coachEmail` field at all** — it only
  carries `parentEmail`.
- Its only listener, `BookingEmailListener.onCoachNoShow` (`:383-`), emails the **parent**
  (`event.getParentEmail()`), confirming their own refund. No coach-facing listener for
  `CoachNoShowEvent` exists anywhere (`CancellationRefundService.onCoachNoShow` only issues the
  refund/strike; no notification).

**Consequence:** once AC4 ships, a coach whose booking is cancelled late by the parent goes from
*always receiving* a "booking cancelled by parent" email today to *receiving nothing at all* —
not that the booking ended, not that a no-show strike was just recorded against their reliability
record. This is a concrete, verifiable regression in coach communication, entirely a side effect of
reusing `CoachNoShowEvent` verbatim as AC4 instructs. It should be called out as a required part of
AC4 (either add a coach-facing notification for this path, or extend `CoachNoShowEvent`/its listener
to also email the coach), not left implicit.

---

## 2. AC1 — Referenced test file does not exist (High)

**Claim in story (Dev Notes):** "Existing test files to extend (do not create new ones for these
classes): `CoachProfileServiceTest.java` (`platform.marketplace.service`, AC1) ..."

**Verified:** `find src/test/java -iname "*CoachProfile*"` returns only
`CoachProfileResourceIT.java` and `CoachProfileBuilderIT.java` — no `CoachProfileServiceTest.java`
exists anywhere in the tree, and no test file in the repo references `CoachProfileService` as a unit
under test with mocks. `saveStep4`'s existing coverage (`saveStep4_validRequest_returns200`,
`saveStep4_noWindows_returns400`, etc.) all live in `CoachProfileBuilderIT.java:421-`, an
integration test (`extends AbstractIntegrationTest`), not a unit test.

**Why it matters:** combined with the Dev Notes' explicit "do not create new ones for these classes"
instruction, a dev following the story literally would either go looking for a file that isn't
there, or skip adding AC1's `SUSPENDED`-guard test coverage because the named home doesn't exist.
The correct instruction is to extend `CoachProfileBuilderIT.java` (or explicitly authorize creating
a new unit test class), matching the IT-style precedent AC6's Dev Notes already use for a
migration-only AC.

---

## 3. AC6 — "No endpoint writes to platform_config" is false (High)

**Claim in story:** "there is no admin UI or endpoint that writes to `main.platform_config` today
(confirmed by grep: no controller references `PlatformConfigRepository.save`) — so the only
realistic mis-write vector is a future migration."

**Verified:** `ConfigResource.java:36-42` exposes:
```java
@PutMapping("/values/{key}")
@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)
public ResponseEntity<ConfigValueResponse> updateValue(@PathVariable String key, @Valid @RequestBody UpdateConfigRequest request) {
    return ResponseEntity.ok(configService.updateConfig(key, request.value()));
}
```
`ConfigService.updateConfig` (`ConfigService.java:165-173`) calls `configRepository.save(entity)`
directly. `UpdateConfigRequest` (`UpdateConfigRequest.java`) only enforces `@NotBlank` — no format
check. So today, any admin-authenticated caller can `PUT /api/config/values/platform.payment.currency`
with an arbitrary non-blank string (`'EUR'`, `'euro'`, `'123'`) and it writes straight through. The
grep the story cites is narrowly true (the *controller* doesn't call `.save()` directly — the
*service* it calls does), but the substantive claim — that no live write path exists — is false.

**Impact on the fix itself:** none — the DB `CHECK` constraint AC6 adds will correctly reject a
malformed value written through this endpoint too, and `ApiAdvice.integrityViolationHandler`
(`ApiAdvice.java:155-173`) already generically catches `DataIntegrityViolationException` and returns
a sanitized 400 (constraint name isn't in `CONSTRAINT_MAPPINGS`, so it falls back to
`"generic.dataError"`) rather than a raw 500. So the constraint is not broken by this.

**What's missing:** because the story's own risk framing assumes no live write path, AC6's test plan
("a lightweight migration test or manual `flyway migrate` check ... is sufficient") never exercises
this real, admin-reachable path. Recommend adding one test that PUTs an invalid currency value
through `ConfigResource` post-migration and asserts a clean 4xx, and correcting the rationale text.

---

## 4. AC4 — Reliability strike issued without the parent's attestation (Medium)

`recordNoShowCoach` is a deliberate action the parent must choose to invoke — it's effectively an
attestation that the coach didn't show up. AC4 makes an *ordinary* `cancelBookingAsParent` call late
enough after start time silently produce the identical consequence — full refund **and** a
`COACH_NO_SHOW` reliability strike (`ReliabilityStrikeService.issue`, `ReliabilityStrikeService.java:33-`)
— with no signal from the caller that they're accusing the coach of anything, and no code-level way
to distinguish "parent apologetically cancelling 10 minutes late" from "coach genuinely never
showed." Strikes accumulate on a rolling 30-day window and can automatically force a coach to
`REDUCED` visibility or `PENDING_REVIEW` (same file, threshold checks). Per this same story's own
AC7 ledger note, there is still no contest mechanism for a strike/dispute a parent raises
("first-raiser-wins stays final"). A coach who was present and ready can now accumulate an unearned,
uncontestable strike purely because the parent used "cancel" instead of "report no-show" after the
start-time boundary passed.

This may well still be the right call — but the interaction with the strike-accumulation and
visibility-reduction machinery doesn't appear to have been surfaced during the story's decision
round, and is worth one explicit confirmation given the story's own "fairness" framing, rather than
folding it silently into the existing "leave first-raiser-wins as-is" decision (which was about
booking disputes, not the strike system).

---

## 5. AC3 — Lock-duration trade-off claim is technically inaccurate (Medium)

**Claim in story:** "the lock is held only for the short check-and-conditional-increment window, not
the whole `authorizePlayback` call (the provider URL generation and token save happen after the
lock's effective scope ends)."

**Verified:** `PessimisticLockRetryer.withBoundedRetry` (`PessimisticLockRetryer.java:83-105`)
explicitly documents and implements running the locked read "inside the caller's current
transaction" via JDBC savepoints — it never opens or commits a transaction of its own. Combined with
`authorizePlayback` being a single `@Transactional` method (`PlaybackService.java:54`), the Postgres
row lock taken via `findByIdForUpdate` is held until that transaction commits or rolls back at the
end of the method — not released the moment the Java code moves past the exists-check/charge block.
That means the lock remains held through the `PlaybackToken` INSERT (`:145-149`) and, for the
owner-viewing-their-own-video branch, a second call to `videoProviderAdapter.generateDownloadUrl`
(`:137-142`) — i.e. genuinely "for the whole rest of the call," not a short window.

This doesn't necessarily invalidate accepting a coarser, per-video lock (that part is a reasonable,
explicitly-flagged trade-off) — but the specific supporting argument used to justify it is wrong, and
`authorizePlayback` is a much hotter, per-play-request path than the occasional per-user writes AC1/
AC2 pattern this is borrowed from. Worth confirming under concurrent-viewer load (or reordering so
the token save happens outside the locked section, or genuinely narrowing the lock via a nested
transaction) before relying on "briefly" as the risk assessment.

---

## 6. AC1 — "No new module dependency direction" is inaccurate (Low)

**Claim in story:** "`BookingError` is already a cross-module-safe contract type (imported directly
by `BookingDuplicationService`), so importing it here does not introduce a new module dependency
direction that doesn't already exist elsewhere in this codebase."

**Verified:** `BookingDuplicationService` lives in `platform.booking.service` — it importing
`platform.booking.contract.BookingError` is booking depending on its own contract package, not
evidence of any cross-module precedent. Grepping the whole `platform.marketplace` package for any
existing import of `platform.booking.*` returns nothing — today the dependency direction between
these two packages is exclusively `booking → marketplace` (`RescheduleService`,
`BookingDuplicationService` both depend on `marketplace.CoachProfileRepository`/`CoachProfileStatus`).
AC1 would be the **first-ever `marketplace → booking` import**, not a continuation of an existing
pattern.

There's no build-level module boundary here (single Maven module, no ArchUnit/checkstyle
import-control rule found) so this won't break compilation — but the claim itself is wrong, and the
dev should treat this as "we are deliberately accepting a new reverse package dependency for
byte-for-byte error-code consistency," not "this direction already exists elsewhere."

---

## 7. AC5 — Phase 1/Phase 2 lookup description is misleading (Low, no functional impact)

**Claim in story:** Phase 2 should use "the existing `findFirstByVideoIdOrderByCreatedAtDesc(videoId)`
... already status-agnostic, unlike the PENDING-filtered lookup Phase 1 uses for its own different
purpose."

**Verified:** Phase 1's current code (`AdminVideoService.java:67-68`) calls the **exact same**
`uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)` and only applies
`.filter(s -> s.getStatus() == PENDING)` afterward in Java — there is no separate,
PENDING-filtered repository query. This doesn't change what Phase 2 should do (call the same method,
skip the filter), so it has no functional impact, but the wording could lead a dev to believe two
distinct queries are involved when there's only one.

---

## Items checked and found accurate (no finding)

For completeness, these specific claims were spot-verified against source and hold up:
- AC1/AC2/AC3's shared "locked-read-then-refresh" precedent in `RescheduleService.acceptReschedule`
  (`:213-227`) and `BookingDuplicationService.duplicateNextWeek` (`:66-78`).
- AC2's core claim: `cancelBookingAsParent`'s `findByIdForUpdate` call returns the same
  already-loaded, unrefreshed managed instance (Hibernate identity map) — genuinely a stale-read bug.
- AC3's `VideoRepository`/`CoachProfileRepository.findByIdForUpdate` NO_WAIT + `@QueryHints` pattern
  match exactly; no existing single-row blocking lock on `Video` exists today (all current Video
  locks are `FOR UPDATE SKIP LOCKED` batch queries), so no new deadlock-ordering risk.
- AC4's state-machine gap: `BookingStateMachine`'s `CONFIRMED` row genuinely lacks `NO_SHOW_COACH`
  (`BookingStateMachine.java:39-44`); `EVENT_ROLES` already permits `PARENT` to fire it (`:98`); no
  other code path assumes `NO_SHOW_COACH` is unreachable from `CONFIRMED` (grepped).
- AC5's core bug and fix shape: `findFirstByVideoIdOrderByCreatedAtDesc` is genuinely status-agnostic
  (`UploadSessionRepository.java:14`); `quotaProvider.release` is genuinely idempotent by contract
  and by implementation (`QuotaService.release`, atomic `ACTIVE → RELEASED` UPDATE), so no additional
  locking is needed around the retry-safety gate despite the check-then-act shape resembling AC3's.
- AC6's precedent match (`V100`/`V101`'s `NOT VALID` + `VALIDATE CONSTRAINT` pair) and the seeded
  `'eur'` value satisfying the proposed `^[a-z]{3}$` regex.
- Spot-checked 7 of the 11 AC7 "already fixed" closures (`SessionPackPaymentService`,
  `ConfigService.getBoolean`'s `MISCONFIGURED_COUNTER`, `PlayerHomeRedirectPage.vue`,
  `playerStore.resetSelfPlayerId`/`fetchSelfPlayerId`, `isSlotWithinAvailabilityWindow`'s explicit
  `coachId` param, `PlayerRegistrationResourceIT`'s OTP coverage, `AdminQueueIT`'s E2E chain test,
  `BookingServiceTest`'s `@InjectMocks`) — all confirmed accurate against current source.
- Migration IDs: V103 is confirmed the latest migration on disk, so V104/V105/V106 are free as of
  this review (still worth a final check immediately before writing the files, as the story itself
  says).
