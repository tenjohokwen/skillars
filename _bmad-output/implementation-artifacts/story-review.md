# Story Review: skillars-deferred-55

Reviewed: `skillars-deferred-55-test-assertion-hardening-and-payment-config-lookup-safety.md`
(Status at review time: `ready-for-dev`, Dev Agent Record empty, all task checkboxes unchecked.)

## Method

Every factual claim in the story was re-verified against the live repository rather than trusted from the
story text: read `BookingDuplicationServiceTest.java` and `BookingDuplicationService.java` in full (AC1);
`AdminVideoServiceTest.java`, `AdminVideoService.java`, `VideoServiceTest.java`, and `VideoService.java` in
full, plus the `Video`/`UploadSession` entity field defaults (AC2); `BookingServiceConcurrencyIT.java`'s
helper and both race tests, `CoachProfile.java`'s `@Table` mapping (AC3); `StripePaymentGateway.java` in
full, `StripePaymentGatewayTest.java` in full, `ConfigService.getString`, `PaymentGatewayException`, and
every caller of `chargeAndCapture` (`PaymentLifecycleService.java`, `SessionPackPaymentService.java`) to
check whether any catches `IllegalStateException` specifically or otherwise depends on the current uncaught
behavior (AC4); the resolved `mockito-core` version (`5.17.0`, confirmed via `mvn dependency:tree`) and its
`Mockito`/`ArgumentMatchers` class hierarchy (AC1); and `deferred-work.md` at all four cited line numbers to
confirm the `[PICKED UP by skillars-deferred-55 ACn]` tags match the story's AC5 description. AC2's,
AC3's, and AC4's core technical analyses all check out exactly as described — **no false positives are
reported below for those premises.** The two findings below are real gaps in the story's execution detail.

---

## Finding 1 (Medium) — AC2 leaves `AdminVideoServiceTest`'s sibling test without the same state assertion it just added, missing a distinct regression the AC's own rationale exists to catch

**What's wrong:** AC2 adds `assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED)` and
`assertThat(session.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED)` to
`deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits` only. `AdminVideoServiceTest`'s other
`deleteVideo` test, `deleteVideo_noPendingSession_neverReleasesQuota`, still asserts nothing about the
video's resulting state after `service.deleteVideo(videoId)` runs — only `verify(quotaProvider,
never()).release(any())`.

Confirmed in `AdminVideoService.java:61-75`: the `v.setOperationalState(OperationalState.DELETED);
videoRepository.save(v);` pair executes **unconditionally**, before the session lookup that determines
whether a session gets expired:

```java
UploadSession expiredSession = transactionTemplate.execute(status -> {
    Video v = videoRepository.findById(videoId).orElseThrow(...);
    v.setOperationalState(OperationalState.DELETED);
    videoRepository.save(v);

    return uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
            .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
            .map(s -> { s.setStatus(UploadSessionStatus.EXPIRED); ...; return s; })
            .orElse(null);
});
```

**Why it matters:** AC2's own rationale is "a regression that dropped `v.setOperationalState(...)` before
`save(v)` would still pass the existing `InOrder` check unchanged... the two new assertions inspect the
actual object after the call completes, closing that gap." That argument is only complete for the
pending-session branch. A future refactor that accidentally moved the state-transition *inside* the
session-lookup's `.map(...)` callback (plausible: a dev "simplifying" the method by handling video-state and
session-state together only when a session exists) would still pass
`deleteVideo_pendingSession_releasesQuotaAfterTransactionCommits` (a session exists there, so the moved code
would still run) — but would silently leave a **session-less** video stuck out of `DELETED` state, with
`deleteVideo_noPendingSession_neverReleasesQuota` never noticing, since that test asserts nothing about
`video.getOperationalState()` at all. This is exactly the class of gap AC2 exists to close, just unclosed
for the one branch that doesn't happen to also exercise the session path.

**Recommendation:** add `assertThat(video.getOperationalState()).isEqualTo(OperationalState.DELETED);` to
`deleteVideo_noPendingSession_neverReleasesQuota` as well (no session-status assertion needed there — no
session exists in that test's fixture). One line, same object reference already in scope
(`video` is already a local variable in that test method).

---

## Finding 2 (Low) — AC1's instructed new imports (`ArgumentMatchers.eq`, `ArgumentMatchers.isNull`) are unnecessary; both already resolve via the file's existing wildcard import

**What's wrong:** AC1 instructs: "Add `import static org.mockito.ArgumentMatchers.eq;` and `import static
org.mockito.ArgumentMatchers.isNull;` (this file currently only statically imports
`org.mockito.ArgumentMatchers.any` alongside a wildcard `org.mockito.Mockito.*`, neither of which covers
`eq`/`isNull`)." That parenthetical premise is false: `org.mockito.Mockito` (resolved version `5.17.0`,
confirmed via `mvn -o dependency:tree`) is declared `public class Mockito extends ArgumentMatchers`
(confirmed via `javap` against the actual jar) — so every `ArgumentMatchers` static method, including `eq`
and `isNull`, is inherited by `Mockito` and therefore already reachable through the file's existing `import
static org.mockito.Mockito.*;` (`BookingDuplicationServiceTest.java:32`). This isn't hypothetical: `eq(...)`
is already called unqualified, with no dedicated import, at `BookingDuplicationServiceTest.java:111`
(`verify(bookingService).isSlotWithinAvailabilityWindow(eq(expectedStart), eq(expectedEnd), any());`) in this
exact file today — live proof the wildcard already resolves it.

**Why it matters:** low severity — adding the two explicit imports is harmless (a single-type static import
shadows a wildcard one without conflict per JLS, so no compile error results), but it's unnecessary busywork
the story presents as required, and a dev following it literally would add two redundant import lines
(and might waste time double-checking why a "missing" import compiles fine without their addition, when
in fact it always did).

**Recommendation:** drop the "Add `import static...`" instruction from AC1 for `eq`/`isNull` entirely —
only `ChronoUnit`/`List` etc. already imported in the file are needed, none of which require any addition
for this AC.

---

## Summary

| # | Severity | Area | One-line issue |
|---|----------|------|-----------------|
| 1 | Medium | AC2 / `AdminVideoServiceTest` | `deleteVideo_noPendingSession_neverReleasesQuota` gets no video-state assertion, missing a distinct regression class the sibling test can't catch |
| 2 | Low | AC1 / imports | Instructed `eq`/`isNull` imports are unnecessary — both already resolve via the file's existing `Mockito.*` wildcard |

AC2's re-scoping decision to leave `VideoServiceTest` unchanged (verified: `failTranscoding`'s state
transition happens only inside the mocked `videoLifecycleService`, so no real object exists there for a
stronger assertion — this is not a gap, just correctly identified as already-maximal for a mockist test),
AC3's `pg_locks` narrowing (verified: `marketplace.coach_profiles` is `CoachProfile`'s exact
schema-qualified table name per `CoachProfile.java:25`; the `EXISTS` clause's premise — that a session
executing `SELECT ... FOR UPDATE` against a table always holds a granted, immediately-visible table-level
lock on it before it can ever be blocked on the row itself — holds under Postgres's lock-acquisition order,
so there is no race window where the outer query could see the transactionid wait before the relation lock
is visible; and `deferred-53`'s own prior story-review already confirmed `findByIdForUpdate` is the first
and only lock-acquiring call in both `createBookingRequest` and `acceptBooking`, so the narrowed query
cannot introduce new flakiness against either race test), and AC4's config-safety fix (verified: neither
`PaymentLifecycleService` nor `SessionPackPaymentService` — the only two callers of `chargeAndCapture` —
catch `IllegalStateException` specifically anywhere, so nothing currently depends on the pre-fix uncaught
behavior; `PaymentLifecycleService.java:207`'s existing `catch (PaymentGatewayException e)` around the
`chargeAndCapture` call site already does the right thing — log and `persistPaymentFailure(...,
BigDecimal.ZERO, ...)` — for a charge that never reached Stripe, exactly the same as it already does for a
`StripeException`-derived failure, so routing the new config-lookup failure through the same exception type
is a strict improvement, not a new risk; both new unit test snippets use `CoachStripeAccount`'s real
Lombok-generated setters and avoid `stubCoachAndCommission()`'s "always succeeds" stub correctly, with no
unused-stub risk under `MockitoExtension`'s strict-stubs mode) were all independently re-verified and are
accurate — no changes needed there. AC5's ledger-tagging state (`deferred-work.md:1641`, `:1686`, `:1695`,
`:1698` all confirmed tagged exactly as the story's AC5 describes, at the same line numbers the story cites)
is also accurate.
