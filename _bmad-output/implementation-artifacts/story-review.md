# Story Review: skillars-deferred-163

## 1. Header

- **Story:** `skillars-deferred-163-configservice-hardening-recap-timeline-frontend-robustness-ci-gates-datamodel-cleanup`
  (`_bmad-output/implementation-artifacts/skillars-deferred-163-...-datamodel-cleanup.md`)
- **Audit date:** 2026-10-26
- **Real HEAD at audit:** `064846ce docs(story): create skillars-deferred-163 config/recap/ci/datamodel cleanup story`
- All citations were checked against the working tree at `064846ce`, **not** against the `0f089e5b` the story says it
  was drafted against. (The only commit between them is the story file itself, so code drift is nil. The drifts listed
  below were already inaccurate at drafting time.)
- Method: four parallel read-only verification layers (citations, ledger/precedent, mechanism, corner cases). Then a
  hands-on adversarial re-check of every surviving finding (Step 4b), with the deciding lines re-read directly.

---

## 2. Citation verification (Layer 1)

About 90 code citations were checked. Most are exact MATCHes. Only the non-MATCH rows are listed below, plus a few
MATCH rows that matter to the findings.

| Story citation | Verdict | Evidence / corrected location |
|---|---|---|
| `OutboxService.java:78-86` as an `afterCommit()` precedent (AC1.3, Verified-state AC1) | **MISDESCRIBED** | 78-86 registers a synchronization with **`afterCompletion`** that unbinds a resource. Outbox's real after-commit path is `@TransactionalEventListener(phase = AFTER_COMMIT)` at `:109`. Only `EmailRetryScheduler.java:147-150` is a true `registerSynchronization(...).afterCommit()` precedent. Its guard is `isActualTransactionActive()`, whereas Outbox uses `isSynchronizationActive()`. |
| References: `infrastructure/**/EmailRetryScheduler.java` | DRIFTED | The file is at `platform/notification/infrastructure/EmailRetryScheduler.java`. |
| `User.java:197-215` "javadoc on the sibling `skillarsRole` field" | MISDESCRIBED | 197-215 is `verificationStatus`'s **own** javadoc, which points to `skillarsRole`'s javadoc (starts ~164). |
| AC6 rationale "do not add the CHECK to `user_aud` … constraining it would be new, out-of-scope risk surface" | **FALSE PREMISE** | `V145__user_aud_role_verification_status.sql:65-71` already adds `user_aud_verification_status_check` (NOT VALID). The conclusion (leave `user_aud` alone) is still right, but the stated reason is wrong. V145 also establishes the `<table>_<column>_check` naming, so the V170 constraint should be named `user_verification_status_check`. |
| `V149:39` "explicitly notes none exists" | MATCH but **under-described** | `V149:34-42` says "CHECK CONSTRAINT — deliberately not added … Adding one now would create a NEW divergence". It is a recorded decision **against** exactly what AC6 does (see §5, F2). |
| AC7.3 marker `drop-prepared-in: <this story's PR/merge>` | **MISDESCRIBED** | `docs/deployment/migration-conventions.md:63-64`: the marker takes a **migration version** (`-- migration-lint: drop-prepared-in: V123`), which the linter checks is earlier than the drop. It does not take a PR or merge reference. |
| `SessionPlanService.updateSession` `:115-158` | DRIFTED | Now 116-164. |
| `SessionPlanService` terminal-booking check `:138-144` | DRIFTED | Null-status check is 138-142; `isTerminal` check is 143-147. |
| `SessionTemplateService.isBookingPlannable` `:179-187` | DRIFTED (minor) | Now 179-189. |
| `booking.api.js` `acceptBooking`/`declineBooking` `:24-28` | DRIFTED | Now 23-30, with one shared deferred-40 comment rather than one comment each. |
| `IntegrationTestConventionTest.allConcreteItClasses()` `:163-171` | DRIFTED | The method is 147-175. 163-171 is only the try/catch. The catch at 169-171 MATCHes. |
| `assert-context-count.sh` "a single line, `grep … \| tail -1`" | MISDESCRIBED (minor) | The script is 263 lines. The stats grep is one line at `:194`, and `:205-241` run the size≥maxSize and missCount−size checks on that same line, so scoping `:194` covers all three checks. |
| `setCoachPlanNote` "delegates to `Booking.isPrepNoteEditable(now)`" | MISDESCRIBED (minor) | `BookingService.java:802-806` checks status explicitly first (`BOOKING_NOT_EDITABLE`), then `:807` calls `isPrepNoteEditable(now)` (`SESSION_ALREADY_ENDED`). That is already the two-code split AC3.4 asks for, so copy it verbatim. |
| `StorageMigrationService.migrateAll` `:39-52` | DRIFTED (minor) | Now 40-55. |
| AC3.5 "`PlayerTimelineRepositoryTest`/IT **gains** a case" | CANNOT_LOCATE | No such file exists. `grep -rl findLatestWhatsNextSuggestion src/test` returns nothing, so the query has **no** test today. This is a new test class, not an extension. |
| AC4.4 "no existing Vitest coverage" | MATCH (qualified) | There is no dedicated `CoachRadarCard`/`fetchCoachTier` spec. But `developmentStoreSpec.js:114,288,296` already test `saveRadarPreferences`, and `CoachPlayerDetailPageSpec.js` mounts `CoachRadarCard`. |
| FileStorageObject field list (AC8) | MATCH (omission) | The list leaves out `uploadConfirmedAt` (53-54). |

Every other citation is a MATCH. That covers all ConfigService lines, ApiAdvice 127-132/626-648, the ConfigServiceTest
methods, BookingCompletionService 154-230/168-175/179-190/192-198/207-218/339-364/378-399/429-451/470-475,
WrapUpSequence.vue 296-308, SessionPrepService 107/111-120, BookingService 149/771/801/811/838-847/1124-1128, Booking
42/45/140-144, TimelineEventListener 120-136/131/281, PlayerTimelineRepository 100-108, CoachRadarCard 63-129, the
development.store seq lines, auth.store 34-41, CoachPlayerDetailPage 220/329-334/386-389/431, useSession.js:91,
MainLayout.vue:458, pr-build.yml:71/88, V138:1290, V149:56, V168 shape, V169-is-latest, SessionCompletionData 49-50,
SessionCompletionDataRepository 53/60, GdprErasureService:413, migration-conventions rule 2 58-85,
StorageKeyGenerator, FileStorageService 244-246, FileStorageObjectRepository 22-25, and DeletionSchedulerService 61-98.

## 3. Ledger & precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| The 20 mapping-table rows (lines 3250-3253, 909, 3214, 3209, 3218, 3198, 3207, 3192, 3193, 3222-3224, 3236, 3237, 893, 3205, 3206) | Ledger grep of lead text plus the nearest `##` heading | **CONFIRMED.** All 20 sit at exactly the cited lines, under the cited sections, and are untagged (still open). |
| Line 909 "409" is stale | Ledger, `ApiAdvice:643-648` | CONFIRMED. The ledger says 409 and the code returns 500. The story handles this correctly. |
| Line 893 "VARCHAR(20)" | Ledger, V149:56 | CONFIRMED stale. The column was widened to 255, which the story notes. |
| Out-of-scope `~845`, `~2186`, `~1749-1768`, `3208` | Ledger | CONFIRMED. |
| Out-of-scope `~2643-2651` (frontend-unit-test accepted design) | Ledger | **WRONG.** 2643-2651 is the ses-1-4 `RegistrationEmailDurabilityIT` bullet. The frontend-job note is at **~2637-2642**. |
| AC9 "Delete the **19** bullets … all rows in the mapping table above except the recap-attendance item at line 3208" | Table count | **WRONG.** The table has **20** rows, and 3208 isn't one of them, so the count is 20. |
| AC8.3 "not a recurring `@Scheduled` job, per **D163-A**" | Story lines 28-31 | **WRONG letter.** D163-A is the bundling decision; the one-off decision is **D163-B**. |
| ApiAdvice ISE→500 "since skillars-deferred-91 AC17" | Javadoc `ApiAdvice:627-642`, deferred-91 story, commit c2c47c1e | CONFIRMED. The javadoc is at 627-642, not 626-641. |
| Frontend unit-test opt-in "decided at deferred-129" | Ledger :1749/:2637, `detect-frontend-changes.sh` header, commit 1746965d | PARTIAL. Opt-in originated at deferred-104/108 D2; deferred-129 reaffirmed it and added the auto-detector. Harmless for scoping. |
| `main.user` index blocked at deferred-125, dropped at deferred-161 | Ledger :2186 tags, deferred-161 story D161-A | CONFIRMED. It was first deferred at deferred-123. |
| `[DECIDED: accepted risk — skillars-deferred-124]` | `User.java:202-203`, commit 77036728 | CONFIRMED. The story's inference from it (new risk surface on `user_aud`) is wrong; see §2. |
| "same rationale as V168 and V162" for `allow-validating-constraint` | V162:6,18; V168 marker | PARTIAL. V162's primary reason is table size, with "no prod" secondary. V168's marker cites V162. Harmless. |
| `audio/webm` introduced in 97ceb58f, removed in 05f81d6d (12.7, #292) | `git log -S`, `git show`, ledger :3206 | CONFIRMED. The real call also passed `fileSizeBytes: blob.size, checksum: null`. |
| `booking.api.js` timeouts cite their deferred stories | Code | CONFIRMED (deferred-39; deferred-40 shared). |
| 12.7 stopped creating voice-note uploads | 12.7 story D3/AC12, 05f81d6d | CONFIRMED. |
| "~1 in 3 ledger items drifted" | — | UNVERIFIABLE heuristic. Here 0/20 line numbers drifted; 2/20 have stale content (909, 893). |

## 4. Mechanistic claims (Layer 3, re-checked in 4b)

| # | Claim | Deciding evidence | Verdict |
|---|---|---|---|
| a | Must-be-fresh waiter parks in uninterruptible `lock()` | `ConfigService:436-440`: `if (!mustBeFresh()) return; refreshLock.lock(); refreshLock.unlock();` inside `while (isStale())` | CORRECT. After the leader's load **fails**, each waiter loops back, wins `tryLock`, and runs its own `findAll()`. That is the ledger's N × connection-timeout serialisation, which a bounded `tryLock` alone does not remove (§5, F9). |
| b | `scheduledRefresh()` "can park the shared `@Scheduled` thread" | `:74-75` → `refreshCache()` | PARTIAL. It parks only when `mustBeFresh()` is true and another thread holds the lock; most ticks no-op. The scheduler is a pool (`application.yaml:117-119`, size 8), not one shared thread. The fix is still reasonable. |
| c | `invalidate()` only increments an AtomicLong; after-commit deferral is safe | `:380-382`; `updateConfig:243/248` never calls `refreshCache` | CORRECT. Deferring keeps the epoch protocol intact. All ITs call `invalidate()` outside a transaction. |
| d | `putAll`+`retainAll` is not atomic | `:47` `ConcurrentHashMap`, `:455-456` | CORRECT but low value. All readers do a single `cache.get` (`:80,207,212`), and no API reads multiple keys, so a cross-key mix cannot be observed via the API. |
| e | `updateConfig`/`ConfigResource.updateValue` not `@Transactional` | `ConfigResource.java:77-80` | CORRECT. |
| f | Failure surfaces as 500 | `ApiAdvice:643-648`, `:127-132` | PARTIAL. A DB outage throws `DataAccessException` → Throwable fallback → 500. **But `PessimisticLockingFailureException` maps to 409 (`:670-676`)**, so the new timeout exception must not be `CannotAcquireLockException`. |
| g | MISSING_RIGHTS for a swept booking at 168-175 | `:168-173` `isSweeperPlaceholder(...)` → throw before any write | CORRECT. The placeholder is a reliable signal, and it fires for admin force-complete too (`:314-317`). |
| h | `updateRecap` usable post-sweep; `SessionRecapDialog` can take the prefill | `updateRecap:384` `verifyStatus(COMPLETED)`; `SessionRecapDialog.vue:122-126` props = `modelValue, bookingId, playerName`; `load():172-177` overwrites the fields from the GET | PARTIAL. The backend is correct. The dialog has **no prefill prop**, and `load()` would clobber a prefill with `''`. It is only mounted in `CoachCommandCenterPage.vue`. |
| i | Recap stored raw; `sanitizeAndTruncate` reusable | `:188-190`; `BookingService:1124` package-private static, same package | CORRECT. `ContactDetailSanitizer` is not injected into `BookingCompletionService` yet. Columns are 500 (`SessionCompletionData:52-59`). |
| j | `SessionPrepService` sanitizes at read | `:119-120` | CORRECT. |
| k | QUICK snapshot "the same way `homeworkDrillIds` is serialized" | Entity columns (`SessionCompletionData:28-67`): no plan-drill column. Timeline drills come from `TimelineDrillSnapshotResolver:54` `sessionRepository.findByBookingId` (live plan) | PARTIAL. It needs a **new column, i.e. a migration** (§5, F7). |
| l | Both `saveTimelineEvent:281` and `:131` need the session-end time | `saveTimelineEvent` callers: `:236` HOMEWORK_ASSIGNED, `:270` generic `writeTimelineEvent` (RADAR_ASSESSMENT from `onRadarEntrySubmitted`, PERFORMANCE_REPORT from `ReportGenerationService:267`) | **WRONG.** See §5, F4. |
| m | `findLatestWhatsNextSuggestion` has no time bound | `PlayerTimelineRepository:100-108` | CORRECT. A join precedent against `booking.bookings` exists at `:45`. |
| n | Both `isBookingPlannable` copies accept only CONFIRMED | `SessionPlanService:242-252`, `SessionTemplateService:179-189` | CORRECT. Note that the existing warning says rejecting UPCOMING is *intentional*. UPCOMING is set ~24h pre-start by `BookingReminderScheduler:101`. |
| o | `createSession` uses one code for two conditions | `SessionPlanService:76-86` | CORRECT but incomplete. **`SessionTemplateService:130-139` has the same misuse** (§5, F10). |
| p | `rejectIfNotEditablePlayerRequest` duplicates `isPrepNoteEditable` on the wrong clock | `BookingService:838-847` vs `Booking:140-144` | CORRECT. |
| q | `pagehide` fix via `keepalive` **or `sendBeacon`** | `RadarDisplayResource:52` `@PutMapping`; `boot/axios.js` `withCredentials: true` (cookie auth); CSRF disabled | PARTIAL. **`sendBeacon` cannot work** because it is POST-only. `fetch(..., {method:'PUT', keepalive:true, credentials:'include'})` works. |
| r | `_radarSaveSeq` fixes unordered PUTs | `development.store.js:273-280` writes no state on success | **WRONG fix for the defect** (§5, F5). |
| s | `fetchCoachTier` has no timeout; centralised-API rule applies | `auth.store.js:34-41`; `project-context.md:47` | CORRECT. A `tierUnknown` indicator already exists (`CoachPlayerDetailPage:128-131,329`), and a second caller exists in `ProfilePage.vue:597`. |
| t | `catch (Throwable ignored)` swallows load failures | `IntegrationTestConventionTest:169-171`, `Class.forName(cn, false, …)` | CORRECT. With `initialize=false` only linkage errors are possible. A strict assertion can trip locally on stale `target/` classes. |
| u | Scope via `sed '/maven-failsafe-plugin.*integration-test/,$p'` | Real CI log: `[INFO] --- failsafe:3.5.6:integration-test (default) @ skillars ---` | **WRONG** (§5, F1). |
| v | V170 CHECK has no conflicts | `UserSchemaWidthIT:73-86` | **WRONG** (§5, F2). Fixture/test SQL uses only the 4 enum values, so there are no data conflicts. |
| w | Dropping the `voiceNoteText` check from `isSweeperPlaceholder` is safe | `:470-475`; only caller `submitWrapUp:170`; both outcomes throw, nothing is written | CORRECT, low risk. |
| x | `softDeleteByKey` path; scheduler handles physical deletion | `FileStorageObjectRepository:22-25` `@Modifying @Transactional`; `findEligibleForPhysicalDeletion` keys on `deleted_at` only | CORRECT mechanically, but the AC8 premise is false (§5, F3). |
| y | Column names | `main.file_storage_objects`, `key`, `content_type`, `deleted_at` | CORRECT. |

## 5. Corner cases / false assumptions / missed flows (survived Step 4b)

Severity reflects impact if the story is implemented as written.

### HIGH

**F1. AC5.2's proposed regex never matches, so the gate would fail every CI build.**
The real CI log (run 40338236660, saved at
`/private/tmp/claude-501/-Users-mokwen-dev-gitrepos-bluegithub-skillars/09323642-b7e4-43d6-a6c4-8bb85f75e367/scratchpad/build.log`)
line 709 reads `[INFO] --- surefire:3.5.6:test (default-test) @ skillars ---` and line 10516 reads
`[INFO] --- failsafe:3.5.6:integration-test (default) @ skillars ---`. Maven 3.9 prints the short plugin prefix, and the
deferred-146 story records the same marker (`skillars-deferred-146-…md:132`). `/maven-failsafe-plugin.*integration-test/`
selects nothing, so the script reports "no missCount found" (a misleading cause) and exits 1. The story's banner snippet
(lines 216-221) is wrong. Use something like `/--- (maven-)?failsafe[^ ]*:integration-test/`, and decide what happens
when no banner is found.
*Re-verified:* grep on the saved log.

**F2. AC6's V170 CHECK breaks an existing IT and silently reverses a recorded decision.**
`UserSchemaWidthIT.userTable_skillarsRoleAndVerificationStatus_haveNoCheckConstraint` (`:73-86`) asserts that **no**
CHECK on `verification_status` exists on `main."user"`. `V149:34-42` records a deliberate decision not to add one
(Flyway-built vs already-booted divergence). The story cites V149:39 only as "notes none exists" and never mentions the
IT. The AC needs to (a) state that it reverses the V149/deferred-124 decision and why: a Flyway V170 runs on every
environment, so the divergence argument no longer applies; and (b) rewrite or invert `UserSchemaWidthIT` (keeping its
`skillars_role` half if that one stays unconstrained).
*Re-verified:* read both files.

**F3. AC8's target rows almost certainly never existed. The "orphaned uploads" premise is false.**
- `audio/webm` and the `webm` extension have never been in the filestorage allowlist: `application.yaml:451-467` today,
  and `git show 97ceb58f:src/main/resources/application.yaml` lines 229-238 at the time of the producer commit. Only
  image/pdf/text/octet are listed. The only `audio` hits under resources are email-blacklist domains.
- `signUpload` runs the validation chain (`FileStorageService:87`), and `MimeTypeValidationStep:22-23` throws
  "MIME type not allowed", so every sign call failed. The client's `catch` (97ceb58f WrapUpSequence.vue diff lines
  266-269) silently set the placeholder text.
- Even past signing, `confirmUpload(key, {})` fails `ConfirmUploadRequest`'s `@NotBlank contentType` /
  `@Positive fileSizeBytes`. The `file_storage_objects` row is only created inside `confirmUpload` (`:160`).

Recommendation: replace AC8 with a one-time read-only count of the discriminator in dev/uat. If it is 0 (expected), close
ledger bullet 3206 with that evidence instead of building a cleanup service. If there is a reason to keep the job,
document why the count can be non-zero.
*Re-verified:* allowlist at both revisions, validator, DTO constraints. Residual uncertainty: a non-default per-environment
allowlist override was not checked outside the repo.

**F4. AC3.1 targets the wrong method. `saveTimelineEvent:281` never writes SESSION_COMPLETED.**
`insertSessionCompletedIfAbsent` (`TimelineEventListener:131`) is the sole SESSION_COMPLETED writer. `saveTimelineEvent`
serves HOMEWORK_ASSIGNED (`:236`) and the generic `writeTimelineEvent` (`:270`), which is used for RADAR_ASSESSMENT
(`referenceId` null) and PERFORMANCE_REPORT (`ReportGenerationService:267`). Those events have no booking, so
"stamp with the booking's session-end time" is impossible or wrong for them. Scope the change to `:131` (and optionally
the homework path). `BookingCompletedEvent` carries no booking times, so the listener must load `requested_end_time`.
There is also no "captured session-end instant" for QUICK/swept bookings, so `requestedEndTime` is the only anchor. The
AC's "reuse whatever instant … if one already exists" has nothing to reuse.
*Re-verified:* grep of `saveTimelineEvent` callers.

**F5. AC4.2's `_radarSaveSeq` cannot fix the ledger defect.**
Ledger 3223 is about the **server** ending up with an older selection when PUTs arrive out of order.
`saveRadarPreferences` (`development.store.js:273-280`) writes no state on success, so a response-side seq guard can
only suppress a stale error/rethrow. It cannot change server-side apply order. Overlap is real: the debounced fn is
fired from `setTimeout` without awaiting the previous call (`CoachRadarCard:69-74`). The real fix is to serialise saves
(chain on the in-flight promise) or send a client version/timestamp the server honours. The AC's own "check whether
overlap can even happen" note is answered: it can.

**F6. AC2.2 redirects the coach into `updateRecap`, which AC2.3 leaves unsanitised.**
`BookingCompletionService:393-395` (`scd.setWhatWeDid(req.whatWeDid().trim())`, etc.) stores raw text, and that text
flows to the timeline and PDF. AC2.3 sanitises only `submitWrapUp`, so the AC2.2 recovery path writes exactly what AC2.3
is meant to prevent. Sanitise in `updateRecap` too. Also, the LIVE branch publishes raw `req.recapWhatWeDid()/
recapWhatsNext()` into `BookingCompletedEvent` (`:217`); pass the saved/sanitised `scd` values instead, as
`confirmCompletion:449` already does.
*Re-verified:* read lines 188-190, 213-218, 393-395, 446-449.

### MEDIUM

**F7. AC2.4 (QUICK drill snapshot) needs a schema migration that the story doesn't plan for.**
`SessionCompletionData` (`:28-67`) has no column for plan drill ids (`homework_drill_ids` is a different concept). A new
nullable column means a migration, which collides with "V170 is AC6's" and with the Dev Notes' implied migration count.
`TimelineDrillSnapshotResolver.forBooking:54` must switch to read the snapshot. `SluCalculationService:57` also reads the
live plan, so QUICK SLU and timeline drills will disagree unless both move. `QuickCompleteTimeoutService` is a third
QUICK completion path that the AC doesn't mention.

**F8. AC2.2 overclaims "no retyping, no silent data loss".**
`SessionRecapDialog` has no prefill prop (`:122-126`), and its `load()` (`:172-177`) overwrites fields with the GET result
(`''` for a placeholder). A prefill must merge after the load, and the dialog must be mounted from `WrapUpSequence` (today
only `CoachCommandCenterPage`). `updateRecap` persists only the three text fields, so attendance, ratings and homework
typed in the wizard are still lost, and the placeholder keeps `playerAttended=false`. Admin force-complete produces the
same placeholder, so the new error text should not say "auto-completed" exclusively. Also, the typed text is not
literally "discarded" today: the wizard stays open with refs intact. The real gap is the misleading message and having no
path to save.

**F9. AC1.1's "rethrow the last failure" has no failure to rethrow.**
A waiter never sees the leader's exception (`refreshCache:416-442`). After a timed `tryLock` it would loop, win the lock,
and run its own `findAll()`, bounded only by Hikari `connection-timeout: 30000` (`application.yaml:187`). The ledger's
N × timeout serialisation (3250) remains. The fix needs a shared `lastFailure` plus timestamp that waiters inside the
backoff window rethrow, a timeout justified against 30 s (or pool contention becomes spurious 500s), and
`InterruptedException` handling. Per §4 f, the exception must not be a `PessimisticLockingFailureException` subtype
(→409).

**F10. AC3.3 leaves the second misuse of `SESSION_BOOKING_NOT_OWNED` in place.**
`SessionTemplateService:135-139` (template deploy) throws `SESSION_BOOKING_NOT_OWNED` for its own `isBookingPlannable`
rejection. AC3.3 accepts UPCOMING in both copies but only re-codes `SessionPlanService.createSession`.
*Re-verified:* read lines 130-139.

**F11. Existing frontend session-error branches are dead code. Don't copy them for the new code.**
`SessionBuilderPage.vue:390-393` compares `e.response.data.helpCode` to `'SESSION_PLAN_LOCKED'` etc. But
`ApiAdvice.operationDeniedHandler:333-338` passes the error code as the **msgKey**, and `logErrorAndReturnDTO:705-707`
sets `helpCode` from `logError(...)` (a support id). The code name lands in `errorMsg.errorKey`. A
`SESSION_BOOKING_NOT_PLANNABLE` mapping copied from this pattern would never fire. Branch on `errorKey`, as
`WrapUpSequence.vue:301` does. (The existing dead branches are a separate pre-existing bug and a ledger candidate.)
*Re-verified:* read the ApiAdvice handler chain.

**F12. AC3.1 shifts the semantics of `CoachPlayerLastSessionResolver.isRecapAttributable`.**
`:58-69` documents that "calculated_at > occurred_at is the NORMAL ordering" because `occurred_at` is processing time.
With session-time stamps, `occurredAt` will almost always be before `sluDate`, so the fast path stops firing and every
call takes the `findLastSessionBookingId` query. That is still correct, but it is an extra query and a changed contract
that needs a test. Existing rows are not backfilled, so timeline ordering will mix processing-time and session-time
stamps.

**F13. AC3.2 bounds only one of the prep card's two sources.**
`lastWhatWeDid`/`lastWhatsNext` come from `CoachPlayerLastSessionResolver`, which is also unbounded relative to the
prepped booking, so the card can pair a "last session" from after the prepped booking with a bounded suggestion. The
bounded query should also `ORDER BY` the referenced booking's `requested_start_time DESC` and decide what happens to
legacy `reference_id IS NULL` rows (currently included, `:103`).

**F14. AC7.3's follow-up shape violates migration rule 2.**
After this story the entity field (`SessionCompletionData:49-50`) is still mapped, so Hibernate still selects and inserts
`voice_note_text`. This story is therefore *not* "the release that removed the last non-scrub code reference". AC7.3
proposes dropping the column "removing the entity field and both scrub references in the same change", which breaks
old pods during a rolling deploy (`migration-conventions.md:58-61`). The follow-up must be two releases: (1) remove the
field and scrubs, (2) `DROP COLUMN` with `drop-prepared-in: V<n>` naming the step-1 migration. Combine this with the
marker-format fix in §2.

**F15. AC1.2 interacts with the test reset listener.**
`DatabaseResetTestExecutionListener.evictInProcessCaches:713-717` calls `configService.scheduledRefresh()` to evict after
truncation. Because `refreshCache` is gated on `isStale()` (`:390-393, 417`), this already does nothing unless the cache
is 300 s old or invalidated. Making `scheduledRefresh` skip-if-busy weakens it further. The listener should call
`invalidate()` (plus a refresh) instead, and this should be folded into AC1.

### LOW

- **F16. AC1.4's test as written is not observable.** There is no map/size accessor (`cache` is private, read only at
  `:80,207,212`), so the "size is old or new" loop needs reflection. Consider asserting via a seam or dropping the
  size-based assertion.
- **F17. AC1.5 timing:** the existing 5-reader concurrency test allows a load up to 10 s, and a very short bounded wait
  could flake on slow CI. The rewritten test will see `TIMED_WAITING`, not `WAITING`.
- **F18. AC2.3/AC2.4 constructor changes** also break `SessionPauseResumeServiceTest`, which constructs
  `BookingCompletionService` directly. The story lists only `BookingCompletionServiceTest`/`AdminBookingCompletionServiceTest`.
- **F19. AC4.1:** drop the `sendBeacon` option (POST-only; the endpoint is `@PutMapping` at `RadarDisplayResource:52`).
  Use `fetch` with `keepalive` and `credentials`, defined in `development.api.js`. It bypasses the axios interceptors.
- **F20. AC4.3:** `/api/marketplace/...` arguably belongs in `marketplace.api.js`, not `auth.api.js` (object-style
  `authApi`). The "visible indication" already exists (`tierUnknown`, `CoachPlayerDetailPage:128-131,329`); only the
  timeout is missing. `ProfilePage.vue:597` is a second caller that benefits too.
- **F21. AC3.3 product note:** the current `isBookingPlannable` warning says rejecting UPCOMING is intentional ("no
  session-plan creation window remains once a booking is UPCOMING"). Flipping it is an owner decision that the story
  should record explicitly, not just a bug fix. (The frontend already shows Prep on CONFIRMED/UPCOMING:
  `CoachCommandCenterPage:180,239`.)
- **F22. Text nits:** AC9 "19" → 20; AC8.3 "D163-A" → D163-B; out-of-scope `~2643-2651` → `~2637-2642`; the AC6
  `user_aud` rationale (§2); the V170 constraint name `user_verification_status_check` per V145's convention.

## 6. What did not survive re-verification

- **"Scoping in AC5.2 is still needed because surefire `*WebMvcTest` slices print cache-stats lines" (Layer 3 u).**
  Killed: in the real CI log the first `cache statistics` line is at 10559, after the failsafe banner at 10516, and
  there are none in the surefire section. AC5.2's scoping is defensive against a hypothetical failsafe-crash log, not a
  live problem. It is still worth doing with the corrected regex and a crafted fixture (AC5.3).
- **"Removing `voiceNoteText` from `isSweeperPlaceholder` could misclassify legacy rows and overwrite them" (Layer 4
  prompt hypothesis).** Killed: both outcomes of the check throw before any write (`:168-175`), and legacy
  `voice_note_text` held the client placeholder string, not real coach text. AC7.1 is safe.
- **"`scheduledRefresh()` parks *the* shared scheduler thread" (ledger 3251 / AC1.2 premise).** Downgraded, not killed:
  it only parks under must-be-fresh plus a held lock, and the scheduler pool is 8 threads (`application.yaml:117-119`).
  The fix is still reasonable.
- **"`load()` cross-key mix is a live bug" (AC1.4 premise).** Downgraded: no API reads two keys in one call, so the mix
  is not observable today. Keep the swap as hygiene.
- **"The story's line numbers drifted because code changed since 0f089e5b."** Killed: the only commit between
  0f089e5b and HEAD is the story file. Every DRIFTED row in §2 was already wrong at drafting.
- **Layer 1 "AC5 `allConcreteItClasses :163-171` MISDESCRIBED".** Downgraded to DRIFTED: the cited range does contain
  the catch the AC is about; it just isn't the whole method.

## 7. Recommendation

**Do not start dev until the story is amended.** Six HIGH findings change what gets built: F1 (regex), F2 (breaking IT
plus a reversed decision), F3 (AC8 is likely a no-op), F4 (wrong method), F5 (wrong fix), F6 (sanitisation hole).

Confidence by claim:
- **Directly re-read by the reviewer in Step 4b (high confidence):** F1, F2, F3 (repo-side; per-environment allowlist
  overrides outside the repo not checked), F4, F6, F7 (entity columns), F8 (dialog props/load), F10, F11, F14 (rule-2
  text, entity mapping), F15, all §2 FALSE PREMISE/MISDESCRIBED rows for OutboxService, V145, and the
  `drop-prepared-in` format; AC9 count; D163 letter.
- **Verified by one layer and consistent with code read during re-check, but not independently traced end-to-end
  (medium):** F5 (server apply-order reasoning), F9 (Hikari 30 s bound and the exact retry loop under failure), F12
  (resolver fast-path frequency), F13 (resolver unboundedness), F16-F20.
- **Judgment/owner calls, not defects:** F21 (UPCOMING intent), the AC8 replace-vs-keep decision, and where
  `fetchCoachTier` lives (F20).
- **Not checked:** runtime behaviour (no tests were run, per the project's no-local-`mvn verify` rule); browser
  `keepalive` semantics beyond the PUT/credentials constraints; dev/uat database contents for the AC8 count.
