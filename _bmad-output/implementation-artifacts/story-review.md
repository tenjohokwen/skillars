# Story Review: skillars-deferred-52

Reviewed: `skillars-deferred-52-video-quota-release-transaction-isolation-and-gdpr-export-booking-dedup.md`
(Status at review time: `ready-for-dev`, Dev Agent Record empty, all task checkboxes unchecked.)

## Method

Every factual claim in the story (line numbers, "confirmed by grep", "no test exists", cited patterns) was
re-verified against the live repository rather than trusted from the story text — reading
`VideoService.java`, `AdminVideoService.java`, `GdprExportService.java`, `Booking.java`,
`QuotaService.java`, `UploadSessionExpiryScheduler.java`, `WebhookEventProcessorScheduler.java`,
`VideoServiceTest.java`, `VideoPurgedEventIT.java`, `AdminVideoIT.java`, `GdprExportIT.java`, and the
relevant `deferred-work.md` sections, plus `git log`/`git show` across ~8 prior "Create Story" commits to
establish this project's actual ledger-tagging convention. The story's core technical premises for AC1,
AC2, and AC3 (the transaction-boundary bug in both services, the reference-identity `.distinct()` bug, the
line numbers, the "no existing test file" claims) all check out exactly as described — **no false positives
are reported below for those**. The findings that follow are real gaps, not restatements of the story's own
already-correct analysis.

---

## Finding 1 (High) — AC4's ledger edits are already committed with the wrong status tag, before any code fix exists

**What's wrong:** AC4 instructs tagging `Def24`, the new `AdminVideoService.deleteVideo()` item, and `D2` as
`` `[CLOSED by skillars-deferred-52 ACn]` ``. This has **already been applied to `deferred-work.md` and
committed to master** in `3a44618` ("Create Story Deferred-52..."), i.e. at story-creation time, before any
implementation. But the actual code these items describe is **still unfixed**:
`VideoService.failTranscoding()` still carries method-level `@Transactional` with the release call inside it
(`VideoService.java:391-411`), `AdminVideoService.deleteVideo()` still releases quota inside the same
`transactionTemplate.execute(...)` block as the `DELETED` write (`AdminVideoService.java:58-73`), and
`GdprExportService.buildBookings()` still ends in `.stream().distinct()` and is still `private`
(`GdprExportService.java:115-126`). The story's own Status (`ready-for-dev`), empty Dev Agent Record, and
all-unchecked task list confirm no implementation has happened.

**Why this matters:** this project's own established convention — confirmed by inspecting the "Create
Story" commits for deferred-38, -40, -41, -42, -45, -48, -49, -50, and -51 — is to tag items a new story is
about to fix (but hasn't yet) as `` [PICKED UP by skillars-deferred-NN ACn] ``, and reserve
`` [CLOSED by ...] `` for items **verified already fixed** by separate, completed work (the "found stale
during re-mining" case). E.g. `c8a958a` (Create Story Deferred-42) tags its own not-yet-implemented targets
`[PICKED UP by skillars-deferred-42 AC1/AC2/AC3]`, and those tags are still `PICKED UP` — never rewritten
to `CLOSED` — even after the implementation commit (`8b22c1e`) landed. Story-52's AC4 breaks this
convention for its own three items (Def24→AC1, the new item→AC2, D2→AC3) by writing `CLOSED` at creation
time instead. (The fourth AC4 edit — RW3's "outside transaction" half — correctly uses `CLOSED`, because
that half genuinely was already fixed by separate, prior work; that one is not in question here.)

**Concrete risk:** if this story stalls, is reprioritized, or only partially implemented (e.g. AC1/AC2 land
but AC3 doesn't), the ledger will permanently and incorrectly report a real, still-present bug as fixed.
This project's own workflow explicitly relies on the ledger being trustworthy for exactly this purpose —
`skillars-deferred-49`'s and `-50`'s own creation notes cite prior re-mining passes confirming sections are
"thin" precisely by trusting `CLOSED`/`STALE` tags rather than re-reading every line. A future re-mining
pass will skip these three items forever, believing them done.

**Recommendation:** change all three of this story's own tags from `CLOSED` to `PICKED UP` in
`deferred-work.md` right now (matching the established convention), and correct AC4's instructions
accordingly so the tags only flip to `CLOSED` once the corresponding code fix actually lands (in the
implementation commit, per precedent).

---

## Finding 2 (Medium) — AC1's mandated "regression test" cannot distinguish fixed from buggy code

**What's wrong:** AC1 requires a test that stubs `quotaProvider.release(...)` to throw and asserts
`videoLifecycleService.transitionOperationalState(...)` was still invoked with `FAILED`, claiming "the
pre-fix code could never prove this." That claim is incorrect. `VideoServiceTest` constructs `VideoService`
as a plain object (`service = new VideoService(...)`, `VideoServiceTest.java:66-68`) with no Spring
transactional AOP proxy in play — so `@Transactional`'s rollback semantics are never exercised by this test
class regardless of whether the annotation is present on the method. In **both** the current buggy code and
the proposed fix, `transitionOperationalState(...)` is called strictly before `quotaProvider.release(...)`
in program order (`VideoService.java:399` then `:406-407` today; same relative order after the AC1
refactor). Run the exact same test — unmodified — against today's pre-fix `failTranscoding()`, and
`verify(videoLifecycleService).transitionOperationalState(videoId, OperationalState.FAILED)` passes
identically, because Mockito verification only checks that a call happened, not whether a surrounding
transaction would have rolled it back.

**Why it matters:** this test provides no actual regression protection for the bug the story exists to fix
(a real DB-level rollback of the FAILED transition). This project does have an established way to test real
rollback behavior — `VideoPurgedEventIT.java` wraps a call in
`transactionTemplate.execute(status -> { ...; status.setRollbackOnly(); return null; })` inside a genuine
Spring IT context with a real transaction manager — but that requires an integration test, which AC1 doesn't
attempt (AC2 explicitly rules out the equivalent IT approach as impractical for the same reason; AC1 doesn't
raise the question at all).

**Recommendation:** keep the test (it's still useful as a documentation/structural check that the two calls
are sequenced correctly), but drop the "proves the fix" / "pre-fix code could never prove this" framing from
AC1 — it overclaims what a mock-based unit test can show. If real regression coverage of the rollback
behavior is wanted, it needs an IT-level test following the `VideoPurgedEventIT` pattern (inject a quota
failure and assert the video is *not* left in `PROCESSING`/still-committed `FAILED` after a real transaction
boundary) — likely out of scope for a "decision-light" bundled story, in which case say so explicitly rather
than asserting the unit test already covers it.

---

## Finding 3 (Medium) — Task 2.3 names the wrong IT as end-to-end coverage for AC2's change

**What's wrong:** Task 2.3 says: "Also run `mvn -o integration-test -Dit.test=AdminVideoIT,VideoPurgedEventIT`
to confirm the existing end-to-end `deleteVideo` coverage still passes unchanged." `VideoPurgedEventIT.java`
does **not** exercise `AdminVideoService.deleteVideo()` at all — every test in that file calls
`videoDeletionService.deleteVideo(video.getId(), LifecycleTrigger.SYSTEM, true)` (`:55`, `:67`), where
`videoDeletionService` is `VideoDeletionService`, a completely separate class (confirmed: grep for
`AdminVideoService` inside `VideoDeletionService.java` returns zero hits — it has its own
`VideoRepository`/`VideoDeletionOutboxRepository` fields and no dependency on `AdminVideoService`
whatsoever). Only `AdminVideoIT.java` actually calls `adminVideoService.deleteVideo(video.getId())`.

**Why it matters:** running `VideoPurgedEventIT` after the AC2 refactor will pass regardless of whether the
refactor is correct, since it never touches the changed code path — it provides zero verification signal for
this story's change, contrary to what Task 2.3 claims. A dev following the task list as written would get a
false sense that two ITs validated the change when only one did.

**Recommendation:** drop `VideoPurgedEventIT` from Task 2.3's verification command (or, if there's a reason
to also confirm `VideoDeletionService`'s unrelated deletion path is unaffected, say so explicitly rather than
implying it covers `AdminVideoService.deleteVideo()`).

---

## Finding 4 (Low) — AC1/AC2's cited "existing test pattern" doesn't exist where claimed

**What's wrong:** AC1 says to add tests "mirroring however this file's existing `completeTranscoding` tests
already stub `transactionTemplate`" — but `VideoServiceTest.java` has **zero** tests for `completeTranscoding`
(confirmed by grep across the whole test tree — the only references to `completeTranscoding` in tests are
`verify(videoService).completeTranscoding(...)` mock-verification calls in an unrelated test file,
`ModerationOrchestrationServiceTest.java`, not a test of `VideoService` itself). `VideoServiceTest`'s actual
`transactionTemplate` handling (`:58-69`) is not a per-test Mockito stub at all — it's a single hand-written
anonymous `TransactionTemplate` subclass built once in `@BeforeEach` that always invokes the callback,
shared by every test in the file. This fixture is sufficient for AC1's new tests as-is (no extra "mocking"
step is actually needed), so the practical impact is low — but the citation points a dev toward a pattern
that isn't there.

AC2 has the same issue one level worse: it says to mirror "`VideoServiceTest`'s
`transactionTemplate.execute(any())`-invokes-the-real-callback stubbing style" while also specifying
`@InjectMocks AdminVideoService` + `@Mock` for every field (implying a genuine Mockito-mocked
`TransactionTemplate`, stubbed per test) — a style `VideoServiceTest` does not use anywhere. That style
*does* exist and is genuinely reusable in this codebase (e.g. `ModerationOrchestrationServiceTest.java:57,78`:
`@Mock TransactionTemplate transactionTemplate;` + `lenient().when(transactionTemplate.execute(any())).thenAnswer(...)`),
just under a different file than the one cited.

**Recommendation:** for AC1, drop the "completeTranscoding tests already stub transactionTemplate" claim —
just say to reuse the existing `@BeforeEach` `txTemplate` fixture for the new tests. For AC2, cite
`ModerationOrchestrationServiceTest.java` (or similar) as the pattern to mirror instead of `VideoServiceTest`.

---

## Summary

| # | Severity | Area | One-line issue |
|---|----------|------|-----------------|
| 1 | High | AC4 / ledger | `CLOSED` tags already committed for unimplemented fixes; should be `PICKED UP` per established convention |
| 2 | Medium | AC1 test plan | Mandated "regression test" passes identically against the pre-fix code — proves nothing about the actual bug |
| 3 | Medium | Task 2.3 | `VideoPurgedEventIT` cited as `deleteVideo` coverage but tests an unrelated `VideoDeletionService` method |
| 4 | Low | AC1/AC2 test plan | Cited "existing" test-stubbing patterns don't exist in the named file (real pattern exists, just elsewhere / doesn't need inventing) |

AC1/AC2/AC3's core bug analysis, all cited file:line references, the "no existing test file"/"zero tests"
claims, the `Collectors` import caveat, the `Booking`/`QuotaService`/`UploadSessionExpiryScheduler`/
`WebhookEventProcessorScheduler` technical claims, and the "Deliberately not picked up" scoping were all
independently re-verified and are accurate — no changes needed there.
