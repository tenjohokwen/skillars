# Story Review — skillars-deferred-156

**Story:** `skillars-deferred-156-booking-notes-gdpr-coverage-pdf-font-encoding-player-privacy-and-hardening-sweep`
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-156-booking-notes-gdpr-coverage-pdf-font-encoding-player-privacy-and-hardening-sweep.md`
**Status at review:** `ready-for-dev`
**Audit date:** 2026-10-20
**Real HEAD at audit:** `890f5c67` — `feat(development,booking,admin): recap-to-report tie-in, player session-request flag, coach-erasure metadata scrub (skillars-deferred-155) (#281)`

Every citation below was checked against the **working tree at `890f5c67`**, not against any SHA, line number, or "Master is at …" claim written inside the story. The story's own self-description (`890f5c67`, branch `master`) happens to be accurate for the commit — `master` and `story/skillars-deferred-156-hardening-sweep` both point at `890f5c67`, and the story file's mtime (12:04) precedes the branch checkout (12:05) — so no citation could have drifted between drafting and audit. The drifts found below were already present at drafting time.

**Coverage:** 33 code citations, 17 ledger/precedent attributions, 20 mechanistic claims, and full AC-coverage walking across 7 ACs. **24 findings survived adversarial re-verification; 13 candidate findings were killed by it.**

---

## 1. Citation verification (Layer 1)

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `GdprErasureService:91` BookingRepository injected | MATCH | `private final BookingRepository bookingRepository;` |
| `:1337-1339` / `:1339` `anonymizeByPlayerId` after comment block | MATCH | comment `:1327-1337`, `setStatementLockTimeout(lockTimeoutSeconds)` `:1338`, call `:1339` |
| `:1267-1349` `deletePlayerDevelopmentDataInDedicatedPool` | **DRIFTED (end off by 32)** | Signature correct at `:1267`; method actually ends `:1381`. See finding 15. |
| `:396-408` / `:396-421` coach scrub block | MATCH (two granularities) | `requiresNewTemplate` block is `:399-408`; `:396` adds the lock-timeout read + `try`; `:421` extends through the catch/alert. Neither range is wrong. |
| `:202-203` `COACH_RECAP_LOCK_TIMEOUT` / `COACH_RECAP_CONTENDED` | MATCH | both `private static final String` |
| `:341-344` player- vs coach-authored comment | MATCH (content contradicts the use made of it) | Real text is about `anonymizeByPlayerId` being keyed on playerId so a coach's own erasure missed the table. See finding 9. |
| `:480` PLAYER call site in `eraseTransactional` | MATCH | `deletePlayerDevelopmentData(pp.getId(), playerBranchLockTimeoutSeconds);` |
| `:851` PARENT per-child call | MATCH | `deletePlayerDevelopmentData(child.getId(), lockTimeoutSeconds);` |
| `BookingService:346` `setNotes(req.notes())` | MATCH (exact) | |
| `:737-756` `setPlayerRequest` | MATCH (end off by 2) | method is `:737-758` |
| `:744-749` sanitize+truncate; `:749` exact | MATCH (exact) | `StringUtils.truncate(contactDetailSanitizer.sanitize(playerRequest).sanitized(), 500)` |
| `:756` `return toBookingResponse(booking)` | DRIFTED (off by 1) | actual `:757` |
| `:485-492` `toBookingResponse` → `toResponse(…,null×4)` | MATCH on the key line; range starts 3 early | method is `:488-493`; the `toResponse(...)` call is `:492` |
| `:495-532` `getParentBookings` "populates all four" | **DRIFTED + SUBSTANTIVELY WRONG** | method is `:495-537`; it passes `parentName = null`. See finding 10. |
| `:1024-1026` `resolvePlayerName` | MATCH (exact) | |
| `:1046-1053` `resolvePlayerNames`/`resolveParentNames` | MATCH (end off by 1) | second helper ends `:1054` |
| `Booking.java:51-52` notes, `:57-58` playerRequest | MATCH (exact) | both `@Column(length = 500)`; notes unnamed, playerRequest `name = "player_request"` |
| `BookingRepository:18-115` existing `@Query` methods | MATCH (story wrote "etc.") | interface is 323 lines with further `@Query` at 163, 181, 205, 213, 234, 282, 306 |
| `ContactDetailSanitizer:21` REDACTION | MATCH (exact) | `"[contact details removed]"` — 25 chars |
| `ReportGenerationService:457-460` four fonts | DRIFTED (off by 1) | fonts are `:458-461`; `:457` is blank |
| `:460` `normalFont` | MATCH (exact) | `FontFactory.getFont(FontFactory.HELVETICA, 10)` |
| `:589-590` `" ✓ Verified"` | MATCH (exact) | U+2713, only occurrence in the file |
| `SkillExposureResponse` 3 components, no playerName | MATCH | `currentWeek`, `trend`, `neglectedSkillCodes` |
| `SluDashboardService:37-79` `getWeeklyExposure` | MATCH (exact) | |
| `pom.xml:525-532` openpdf 2.0.5 pin | MATCH (exact) | comment `:525-529`, `<version>` `:532` |
| `Dockerfile:50` alpine runtime | MATCH | `FROM eclipse-temurin:17-jre-alpine` |
| `ReportGenerationServiceTest:298-309` `extractPdfText` | MATCH (end off by 1) | ends `:310`; built on `PdfReader`/`PdfTextExtractor` as claimed |
| `booking.store.js:542-544` `handleSetPlayerRequest` | MATCH | function `:542-545`, comment `:537-541`; response discarded, `loadParentBookings()` refetch |
| `CoachPlayerDetailPage.vue:92-93` playerName computed | MATCH (exact) | |
| `:141-145` `fetchExposure` on mount | MATCH (nuance) | that is the `Promise.all` inside `loadPlayerDetail`; the `onMounted` hook is `:150` |
| `MyPlayersPage.vue:110-116` / `:115` | MATCH (`:110` blank; `:115` exact) | `query: { playerName: player.playerName },` |
| `development.store.js:25,80` exposure ref | MATCH (exact) | `ref(null)` / `exposure.value = response` |
| `localeKeyParitySpec.js:17-29` `collectKeyPaths` | MATCH (off by 1) | function is `:18-29`; **not exported** — the file has no export statements |
| `setup-file.js:34-35` global `$t` mock | MATCH (nuance) | not a pure echo: special-cases `params.date`. See finding 18. |
| `CoachPlayerDetailPageSpec.js:37,50` + mocks `:84,108,129,147,160,181,202-229` | MATCH (all) | every cited line hits |
| `MyPlayersPageSpec.js:155,181` | MATCH (exact) | both assert `query: { playerName: 'Alex Kane' }` |

Every pinpoint single-line citation (`:460`, `:749`, `:115`, `:155`, `:181`, `:25`, `:80`, `:50`, `:21`, `:91`, `:1339`, `:202-203`, `:346`, `:1024-1026`) landed exactly. Drifts are ≤3 lines except the two flagged above.

---

## 2. Ledger & precedent attribution (Layer 2)

Method: each claim checked against **(a)** `deferred-work.md` and sibling artifacts, **(b)** in-code comments/Javadoc near the symbol, **(c)** `git log --grep` / `git show` / `git blame`. A zero-hit grep was never treated as proof of fabrication.

| Claim | Sources checked | Verdict |
|---|---|---|
| GDPR gap on `notes` + `player_request` → "code review of skillars-deferred-155 (2026-10-20)" | (a) `deferred-work.md:3333` heading + bullet; (b) `GdprErasureService` comments; (c) `git show --stat 890f5c67` | **SUPPORTED** — ledger bullet explicitly says "fix `notes` and `player_request` together in one pass" |
| `Booking.notes` unsanitized → "`/mto-story-review` of skillars-deferred-155 (2026-10-19)" | (a) `deferred-work.md:3329`; (b) deferred-155 Dev Notes; (c) n/a | **SUPPORTED** — ledger says `:336`, story says `:346`; the story is right at HEAD |
| `StringUtils.truncate` surrogate split → 2026-10-20 review | (a) ledger 2026-10-20 §; (b) `BookingService:746-748` comment; (c) n/a | **SUPPORTED as an attribution** — the ledger does say this. The underlying technical claim is false; see finding 1. |
| PATCH returns 4 null fields → 2026-10-20 review | (a) ledger; (b) `booking.store.js` comment; (c) `git show --stat 890f5c67` | **SUPPORTED** — the story's `PlayerRequestResponse(UUID, String)` is the ledger's own suggested remedy. The ledger also carries the `getParentBookings` error the story inherited (finding 10). |
| Non-WinAnsi chars dropped → 2026-10-20 review | (a) ledger; (b) deferred-155 §Post-draft corrections; (c) `git show --stat` | **SUPPORTED** (two independent records) |
| `playerName` in URL → "code review of skillars-12-4… (2026-10-16)" + revisit condition | (a) `deferred-work.md:3243` | **SUPPORTED** — the story's paraphrase of the revisit condition is word-for-word. Remedy diverges; see finding 19. |
| No i18n template-key test → "code review of skillars-12-5… (2026-10-19)" | (a) `deferred-work.md:3314`; (b) `localeKeyParitySpec.js:1-10` header; (c) `git log --diff-filter=A` | **SUPPORTED** (ledger + the spec's own header both scope it out) |
| Coach-erasure option (b) deferred, option (a) shipped | (a) ledger 2026-10-20 last bullet; (b) `GdprErasureService:420-427` load-bearing comment + `:573`; (c) `890f5c67` (+86 in that file) | **SUPPORTED, both halves** |
| deferred-155 AC14 left template-key-resolution open | (a) deferred-155 story `:138`; (b) spec header | **SUPPORTED** — AC14's own words: "a separate, larger static-analysis effort explicitly left for a future story" |
| `localeKeyParitySpec.js` added by deferred-155 AC14 | (a) ledger PARTIALLY-CLOSED tag; (b) file header "skillars-deferred-155 AC14:"; (c) single-commit history = `890f5c67` | **SUPPORTED** |
| Brainstorming companion "fully covered already" | (a) `_bmad-output/brainstorming/brainstorming-session-2026-10-14-19-55-07.md` + `sprint-status.yaml`; (b) inline annotations; (c) `git show 890f5c67` | **PARTIALLY SUPPORTED** — conclusion correct, cited doc stale. See finding 20. |
| `/api/disputes` zero frontend usage + own ledger entry | (a) `deferred-work.md:3238`; (b) `DisputeResource:29`; (c) n/a | **SUPPORTED** — 8 frontend hits, all i18n/chip labels, zero API calls |
| deferred-155 documented the metadata-key vs column trap | (a) deferred-155 `:225-227`; (b) `TimelineEventListener:55,61` | **SUPPORTED** — and the story's conclusion that it doesn't apply to AC1 is sound |
| `notes` vs `playerRequest` functional distinction | (a) deferred-155 `:233-237`; (b) `BookingService:142-143`, `:778-787`, `CoachBookingRequestsPage.vue:101-102`, `SessionPrepCard.vue:59-63` | **SUPPORTED** in both ledger and live code |
| `docs/validation-strategy.md`: frontend suite outside `mvn verify` | (a) `docs/validation-strategy.md:45-51` | **SUPPORTED** (verbatim) |
| `pom.xml` openpdf comment rationale | (b) `pom.xml:525-529` | **SUPPORTED** — and it adds a fact AC4 needs: 2.x keeps the `com.lowagie.text.*` package |
| "re-verified against real HEAD 890f5c67 / branch master" | (c) `git log`, `git branch --contains`, `git reflog`, file mtime | **SUPPORTED for the moment described** |

**No fabricated citation and no misattribution was found anywhere in the story.**

---

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict | Deciding evidence |
|---|---|---|
| `GdprErasureService` injects `BookingRepository` with **zero** call sites | **CONFIRMED** | `grep -c "bookingRepository\." → 0`; only `:15` import and `:91` field. Confusable `sessionCompletionDataRepository` (`:92`) *is* used, at `:401` and `:1339`. |
| No erasure path touches `booking.bookings` at all | **CONFIRMED** | All booking-schema hits in the file are comments or `session_completion_data`. Only native query is `:1385` `set_config`. No `JdbcTemplate` in `platform/admin/`. `BookingRepository` has **no** `delete*`/`@Modifying` anywhere — nothing hard-deletes a booking row, so the anonymize-UPDATE is neither moot nor conflicting. |
| Both call sites funnel through one shared method | **CONFIRMED** (one hop the story omits) | `:480` and `:851` both call `deletePlayerDevelopmentData` (`:1230`), which calls `deletePlayerDevelopmentDataInDedicatedPool` (`:1239`). Confusable pair ruled out: the two near-identical private methods differ only by the `InDedicatedPool` suffix and share a signature. |
| **ID space: AC1's playerId-keyed query matches the right rows** | **CONFIRMED — the story got this right** | `:1105-1109` Javadoc: "`playerId` here is always an already-resolved `player_profiles.id` (a TSID) — never a `main.user.id`". Callers pass `pp.getId()`/`child.getId()`. `Booking.playerId` is set only from a validated `PlayerProfile` PK (`BookingService:190,341`; `BookingBatchService:117,241`) and `BookingRepository:151-152` states it outright. `SessionCompletionData.playerId` is copied from `Booking.playerId` (`BookingCompletionService:155,258`). Confusable `Booking.parentId` (also `Long`, but a **User** id, `BookingService:739`) correctly not used. |
| PLAYER branch uses per-group `nextGroupLockTimeoutSecondsOrThrow`/`spendDownLockBudget` against `cumulativeLockWaitBudget` | **PARTIALLY CONFIRMED** | Structure confirmed for groups 1-3 (`:1291-1325`); group 4 (`:1347-1352`) has the timeout call but **no** spend-down. Harmless (last group) and pre-existing. |
| PLAYER branch groups share ONE transaction | **CONFIRMED** | `:1259-1265` Javadoc: "rolls back the ENTIRE `requiresNewTemplate` transaction … including every earlier group's already-executed statements in THIS SAME call — because they all share the one REQUIRES_NEW transaction below; there is no partial-commit risk to reason about". Boundary: `:1268` `requiresNewTemplate.executeWithoutResult` wraps every group; `:224-226` sets `PROPAGATION_REQUIRES_NEW`. The "dedicated pool" is thread-local datasource routing (`:1237`), not a transaction mechanism. **This is the basis of findings 3 and 4.** |
| `anonymizeByPlayerId` sits outside the budget groups under its own `setStatementLockTimeout`, same convention | **CONFIRMED, with a material qualification** | Comment `:1327-1337` says exactly that. But the call is *inside* the same `requiresNewTemplate` transaction opened at `:1268`: it is independently **bounded**, not independently **durable**, and its elapsed wait is never spent down (today's worst case is `cumulativeLockWaitBudget + 1 × lockTimeoutSeconds`; AC1 makes it `+ 2 ×`). |
| Coach branch: one block, one `lock_timeout`, two reason codes, later failure rolls back earlier scrubs | **CONFIRMED** | Block `:399-408`; `set_config(..., true)` is transaction-scoped; catch at `:409` is *outside* the template so the whole REQUIRES_NEW transaction rolls back; codes used only at `:414-415`. |
| Coach branch has "four lock-taking statements" | **REFUTED** | Three lock-taking UPDATEs. `findStorageKeysByCoachId` (`PerformanceReportRepository:40-41`) is a plain `SELECT`, no `FOR UPDATE`. `blobDeletionOutboxSupport.enqueue` is conditional and 0..N INSERTs, and `main.outbox_messages` has no unique index beyond its PK (`V138:688`, `:3406`) so it cannot block on a concurrent uncommitted insert. See finding 14. |
| **`StringUtils.truncate` truncates at a raw code-unit index with no surrogate awareness** | **REFUTED** | See finding 1 — executed proof. |
| `truncate` throws on short input / null behavior | PARTIALLY CONFIRMED | Throws only on negative `offset`/`maxWidth`; clamps (never throws) for short input; returns `null` for `null`. |
| `setNotes` write path has no sanitizer | **CONFIRMED** | Exactly two `setNotes(` sites repo-wide: `BookingService:346` and `RadarAssessmentService:82` (a different entity). No `@PrePersist` logic on notes (`Booking:85-90` sets only timestamps/status). |
| `notes` max length is 500 | **CONFIRMED** | `Booking:51 @Column(length = 500)`; `V138:236 notes character varying(500)`; `CreateBookingRequest:16 @Size(max = 500)`. Confusable `V138:443` is `development.radar_assessment_entries.notes` — same width, different table. |
| Sanitizing can grow a string | **CONFIRMED** | `REDACTION` is 25 chars; min EMAIL match 6 chars (`a@b.cc`), min PHONE match 8 — growth up to ~19 chars per match, unbounded in aggregate. |
| Postgres driver substitutes rather than throws | **UNVERIFIABLE FROM REPO (mechanism sound, scenario unreachable)** | Driver pinned `pom.xml:90` 42.7.12; no charset configured anywhere. `Encoding.encode` is literally `String.getBytes(Charset)`, whose JDK contract is replacement-not-throw. But per finding 1 the lone-high-surrogate input can't arise. |
| `toBookingResponse` leaves 4 fields null | **CONFIRMED** | `:488-493` → `toResponse(booking, coachName, playerName, null, null, null, null)`; `:1134` confirms param 4 is `parentName`. Callers: exactly three (`:485` `getBooking`, `:757` `setPlayerRequest`, `BookingEventResource:70`) — only the middle one is touched by AC3. |
| `getParentBookings` populates all four | **REFUTED** | See finding 10. |
| `Booking.getId()` returns `UUID` | **CONFIRMED** | `Booking:26-28` `@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id`. Confusable: `Booking` does **not** extend `BaseEntity` (whose `getId()` is `Long`), unlike `PlayerProfile`. |
| `GdprExportService` serializes whole `Booking` entities | **CONFIRMED** | `:117-136` returns `List<Booking>`, serialized at `:73`. DSAR needs no change. |
| openpdf base-14 drops out-of-range glyphs; ✓ already invisible | **PARTIALLY CONFIRMED** | Repo facts exact: all four fonts are `FontFactory` two-arg calls (`:458-461`), zero `BaseFont` anywhere, no encoding argument passed; ✓ exists at `:589-590`; **no test asserts the ✓ or "Verified"** (zero hits in either test class), so nothing contradicts "already dropped". The glyph-dropping behavior itself is openpdf-internal and unverifiable from repo source. |
| `resolvePlayerName` shape, `PlayerProfileRepository` location, no new cross-module dep | **CONFIRMED** | `:1024-1026` exact; `platform.security.repo` confirmed; four `development` classes already inject it (incl. `CoachPlayerAuthorizationService:42`, same package as `SluDashboardService`); **no ArchUnit dependency exists** (`EmailTransportArchitectureTest:21-26` documents this). Confusable plural `resolvePlayerNames` (`:1046-1049`) correctly not cited. |
| `getWeeklyExposure` id space = `PlayerProfile` PK; one construction site | **CONFIRMED** | `:38` `requireCoachPlayerRelationshipIfCoach` → `existsByIdAndParentId`/`existsByIdAndUserId` on `PlayerProfileRepository`. `SkillExposureResponse` built in exactly one place (`:78`), zero in tests. Confusable `getSkillTrends` (`:92-120`) is near-identical for its first eleven lines but returns `SkillTrendResponse`. |
| Frontend discards the PATCH response; sole consumer | **CONFIRMED** | `booking.api.js:92-93` is the only HTTP caller; `booking.store.js:543` is the only call, return value dropped. AC3 is a zero-frontend-change narrowing. |
| `exposure` ref holds the raw response | **CONFIRMED (with a caveat that becomes finding 6)** | `:25` `ref(null)`, `:80` `exposure.value = response`; but `:74` nulls it at the start of every fetch and the catch leaves it null. |
| Global `$t` mock echoes keys, so typos stay green | **PARTIALLY CONFIRMED** | Conclusion holds. Mechanism mis-stated (finding 18) and "every other spec" is one spec off: `CoachProfileBuilderPlaceholderPageSpec.js` deliberately installs the real catalogs. `$te` is not mocked at all and therefore always returns `false` in specs — a second, unflagged blind spot. |

---

## 4. Corner cases, false assumptions, missed flows

### HIGH — fix before implementation starts

**1. AC2's entire surrogate-pair premise is false against the pinned dependency; the proposed guard is dead code.**
`BookingService:54` imports `org.apache.commons.lang3.StringUtils`, pinned at `pom.xml:368-371` to **3.21.0**, whose `truncate` **is** surrogate-aware — it calls a private `splitsSurrogatePair(String,int)` and adjusts the end index backward by one when the cut would split a pair. Executed against the real jar with an astral char (U+1F600) straddling index 500:

```
input len=505
truncated len=499
last char high surrogate? false
```

The scenario AC2 exists to fix ("can leave a lone trailing high surrogate") **cannot occur**. The proposed guard
`if (truncated.length() == maxLength && Character.isHighSurrogate(truncated.charAt(maxLength - 1)))`
can only ever fire on input that already contained an *unpaired* high surrogate. AC2's consolidation value (one helper, two call sites) and the genuine `notes` gap are both real — but ledger item 3, AC2's stated rationale, and Task 2.4's "astral-plane character straddling the truncation boundary is dropped whole, not split" test must be rewritten. That test as specified will pass against unmodified code.

**2. AC7 Task 7.3 instructs the dev to update tests that do not exist.**
AC7: *"Update `GdprErasureIT`'s existing coach-branch contention/timeout tests to assert the new, more specific reason codes."* `COACH_RECAP_CONTENDED`/`COACH_RECAP_LOCK_TIMEOUT` have **zero references in `src/test`** (repo-wide grep returns only `GdprErasureService.java:202,203,415`). `GdprErasureIT`'s coach tests are happy-path only (`:240`, `:743`, `:808`, `:896`); every contention/timeout IT (`:1608`, `:1709`, `:1826`, `:1916`, `:2004`) is PLAYER or PARENT branch. This is net-new lock-choreography test authoring — and the same file records at `:1989-1993` that a comparable contention IT was *deliberately skipped* as "unreasonably long test or exact-timing choreography across several simultaneous held locks." AC7 is materially larger than "update assertions."

**3. The PLAYER-branch precedent AC7 says to mirror provably cannot deliver AC7's stated goal.**
AC7 asks the dev to "read `deletePlayerDevelopmentDataInDedicatedPool`'s own Javadoc on why its groups share one transaction before deciding." That Javadoc (`GdprErasureService:1259-1265`) says the opposite of what the AC hopes: a bail-out "rolls back the **ENTIRE** `requiresNewTemplate` transaction … including every earlier group's already-executed statements in THIS SAME call … there is no partial-commit risk to reason about." Mirroring its shape buys per-group timeout budgeting and per-group labels and **zero** durability. AC7's goal ("durability of earlier successful scrubs when a later one fails") is reachable only via genuinely separate `REQUIRES_NEW` transactions — which collides with finding 4. The AC's hedge sentence asks the right question; the answer is that its named precedent is not one.

**4. AC7 lists the blob-deletion outbox enqueue as a splittable fourth group; splitting it permanently orphans S3 blobs.**
`BlobDeletionOutboxSupport:21-23,37-38`: *"Producers call `enqueue` **inside** their business transaction (so the rows commit atomically with the work that decided they should be deleted) … **MUST be called inside the producing business transaction.**"* `performanceReportRepository.anonymizeByCoachId` sets `storage_key = NULL` (`PerformanceReportRepository:64-66`), and `findStorageKeysByCoachId` filters `storage_key IS NOT NULL` (`:61`). If the scrub commits and the enqueue's separate transaction does not, the keys are gone from the only place that recorded them and the retry finds nothing. The PLAYER branch's own Javadoc already names this hazard: "permanently orphaning those S3 blobs (a real Article 17 violation)" (`GdprErasureService:1143-1149`). **The enqueue must stay in the same transaction as the `performance_reports` scrub — it is not a splittable table.**

**5. AC2 misses the email flow: the coach's booking-requested notification still carries raw, unsanitized notes.**
AC2 rewrites only `BookingService:346`. Five lines later, `:351-354` publishes `new BookingRequestedEvent(…, req.notes(), …)` — the **raw** request value — which `BookingEmailListener:72` puts into the template and `mails/bookingRequested.html:11` renders as `Player notes: <em th:text="${map.notes}">`. Since `ContactDetailSanitizer` exists specifically to block off-platform contact exchange, and email is the most direct such channel, sanitizing only the DB copy leaves the more exposed path open. Fix: sanitize once into a local and use it at both `:346` and `:353`.

**6. AC5 regresses the page heading during load and permanently on error.**
`CoachPlayerDetailPage.vue:16` renders `$t('coach.playerDetailTitle', { playerName })` **above** the `v-if="!loading && !pageError"` guard at `:22` and above the error banner at `:18`. `development.store.js:74` nulls `exposure.value` at the start of every fetch and the catch at `:85-88` leaves it null. So `store.exposure?.playerName` yields the fallback — `'this player'` (`en-US/index.js:266`) — while loading, and *forever* on a 403/500, where `route.query.playerName` supplies the real name today. Note `CoachPlayerDetailPageSpec.js:108` mocks `exposure: { reject: … }`: the story's instruction to "add a `playerName` field to each mocked `/exposure` response" is impossible for precisely the uncovered case. Needs a resilient fallback (router `state`, or retain last-known name) or an explicit accept-and-document decision.

### MEDIUM

**7. AC7's eight new reason codes are mostly unobservable.** `V138:3166` `CREATE UNIQUE INDEX admin_alerts_unique_open_per_ref … (reference_id, type) WHERE status = 'OPEN'`, and the dedup check (`GdprErasureService:1014-1020`) is **reason-blind** — it keys on `(referenceId, GDPR_ERASURE_DEADLINE, OPEN)` and returns early, logging at `debug` only (`:966-968`). Today only one failure can occur per request, so two codes suffice. With four independent groups, groups 2-4 can also fail and every subsequent alert is silently swallowed. The observability gain materializes only for the *first* failing group.

**8. AC1: `Booking` is `@Version`-managed but its cited precedent entity is not — erased notes can be resurrected with no optimistic-lock failure.** `Booking:60-62` has `@Version`; `SessionCompletionData` has **none**, which is why the precedent JPQL is safe. Hibernate does not bump `version` on a bulk HQL `UPDATE` (that needs `UPDATE VERSIONED`), and `Booking` is not `@DynamicUpdate`, so a concurrently-loaded `Booking` rewrites every column from its stale snapshot — including `notes`/`player_request` — and the version check passes. Live paths that do exactly this: `BookingService:893-894` (`setCancelReason` + `save`), `:751-753` (`setPlayerRequest` + `save`). Not bumping `updated_at` is *desirable* (four queries key on it), so `UPDATE VERSIONED` is the targeted fix rather than an entity loop. Pre-existing pattern elsewhere (`UserRepository:75-80`, also `@Version`), but it is a real deviation from the precedent AC1 names and should be decided explicitly.

**9. AC1 cites its governing precedent backwards.** AC1 justifies playerId-only keying by "the existing precedent that already governs this distinction (`session_completion_data`'s player-authored vs. coach-authored halves, documented inline at `:341-344`)". **There is no such split.** `GdprErasureService:341-344` and `SessionCompletionDataRepository:36-50` both state the three columns are **coach-authored**, and the two queries null the *same three columns*, differing only in key:
```java
… SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL WHERE s.playerId = :playerId   // :42
… SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL WHERE s.coachId  = :coachId    // :49
```
Read correctly, the precedent argues *for* a second coachId-keyed arm — the opposite of AC1's conclusion. The conclusion is still right on the merits (both booking columns are player/parent-authored, so a coach's erasure should not null them), but the citation will mislead whoever implements or reviews it, and the real precedent is that `session_completion_data` got **both** arms.

**10. "whereas `getParentBookings` populates all four from real lookups" is wrong — it populates three.** `BookingService:534-535` passes `parentName = null` just like `toBookingResponse` does; it populates only `pendingReschedule` (`:507-514`), `batchId`, and `batchSize` (`:520-526`). `parentName` is populated by `getCoachBookingRequests` (`:558` `resolveParentNames`, used at `:567`/`:591`). The error originates in the ledger bullet and was carried into the story unchanged. It does not change what AC3 does, but anyone "fixing" `getParentBookings` by copying it would copy the same null.

**11. AC4's suggested proof string would pass against unfixed code.** AC4 offers `"Session went well — très bien ✓"`. Verified against cp1252: `è` ✅ encodable, `—` ✅ encodable, `é`/`à` ✅ encodable; only `✓` and `Ł` are out of range. A test asserting `contains("très bien")` is green pre-fix. The assertion must target `✓` or `Ł` specifically — and should be written to fail against current code before the fix lands, since it is also the first real proof of the premise (which is otherwise unverifiable from the repo).

**12. AC6's specified glob + regex covers a small minority of real call sites and produces bogus failures.** Measured across `src/frontend/src`: ~1600 `t`-ish call sites, of which only **218** use `$t(`/`$te(` in `.vue` — the dominant form is the bare composition-API `t('…')` in `<script setup>`. A further **34** `i18n.global.t(` sites live in `.js` (`development.store.js`, `utils/errorHandler.js`), invisible to a `.vue`-only glob. Non-literal keys are **16**, not "maybe zero" (2 of them in `.js`). And AC6's own suggested regex puts the backtick *inside* the opening character class, so it matches template literals and extracts 9 unique bogus keys, e.g. `development.timeline.eventType.${event.eventType}` (`PlayerTimelinePanel.vue:40`), plus two string-concatenation prefixes (`DevelopmentCorrelationPanel.vue:72`, `ParentDashboardPlaceholderPage.vue:28`). Task 6.3 ("treat a failure as a real bug to fix") would send the dev chasing nine non-bugs. No genuinely missing key exists in `en-US` today (1258 leaves), so the spec's first green run requires getting the extraction right, not fixing the catalog.

**13. AC4: a single Regular weight makes 3 of the 4 fonts faux bold.** `ReportGenerationService:458-461` uses `HELVETICA_BOLD` for `headerFont`/`sectionFont`/`tableHeaderFont`. With `new Font(baseFont, 18, Font.BOLD)` over a Regular `BaseFont`, openpdf 2.0.5 emits `2 Tr` + `0.6 w` (fill-stroke render mode with a 0.6pt stroke) instead of selecting a real bold face — visible on every report header, section heading, and table header, worst at 10pt. *(Verdict: PLAUSIBLE — from a content-stream dump against the real jar; I did not independently re-run the dump.)* Either vendor a Bold weight too, or state the tradeoff in the AC.

**14. AC7's "four lock-taking statements" is three.** `findStorageKeysByCoachId` is a plain `SELECT` (MVCC read, no `FOR UPDATE`) and the outbox enqueue is conditional 0..N INSERTs that cannot block (no unique index beyond the PK on `main.outbox_messages`). Giving each of those its own contended/timeout code pair buys nothing. The real split candidates are three UPDATEs — and per finding 4 the third must keep the enqueue with it.

### LOW

**15. `deletePlayerDevelopmentDataInDedicatedPool` is `:1267-1381`, not `:1267-1349`.** The cited range stops before group 4 (blob-deletion outbox, `:1341-1352`), the sticky tombstone write + `entityManager.flush()` (`:1354-1368`), and both catch clauses (`CannotAcquireLockException` → rethrow; `PessimisticLockingFailureException` → `DeleteStatementLockTimeoutException`, `:1369-1379`). AC1 and AC7 both plan against this method; a change made against the truncated view would miss its exception-classification contract.

**16. AC5 breaks `SluDashboardServiceTest` compilation, not just its assertions.** `SluDashboardServiceTest:46-48` constructs the service manually with four args: `new SluDashboardService(snapshotRepository, flagRepository, narrativeService, coachPlayerAuthorizationService)`. Adding a fifth `@RequiredArgsConstructor` field is a compile break. Task 5.6's "whichever exists" → **both** `SluDashboardServiceTest` and `SkillExposureResourceIT` exist.

**17. AC3 leaves a now-wrong comment behind and omits its file.** `booking.store.js:537-541` justifies the refetch with "the single-booking mapper leaves parentName/pendingReschedule/batchId/batchSize null (toBookingResponse:482)". After AC3 the PATCH returns no such fields at all, so the rationale needs rewording; the file is absent from the story's "Likely files touched". (The refetch itself stays correct.) That comment's own `toBookingResponse:482` citation is independently stale — actual `:488`.

**18. AC6 mis-cites the mechanism it depends on.** AC6 attributes key-echoing to "the global `$t` test mock (`setup-file.js:34-35`)". That mock covers only the ~218 `$t(` sites and is not a pure echo (it special-cases `params.date`). What makes the dominant bare `t()` form echo is the **real** `createI18n({ messages: { 'en-US': {} }, missingWarn: false, fallbackWarn: false })` installed at `setup-file.js:18-27`. Premise sound, citation wrong. Related: `$te` is not stubbed at all and always returns `false` in specs, so `PlayerTimelinePanel.vue:40` renders raw enum names under test — worth naming in the new spec's rationale.

**19. AC5's remedy is stricter than the ledger's recorded end-state, without acknowledging it.** The ledger's revisit condition (quoted faithfully) continues: "at which point the query param can become a **presentational hint** rather than the only source." AC5 removes the param outright. Defensible on privacy grounds — and finding 6 is the cost of that choice — but the story presents the ledger as having prescribed the remedy it picked.

**20. The brainstorming doc's build order still marks two shipped items open.** Lines 5-6 of its "Agreed Build Order" read "still open, no story yet" / "still parked" for the recap→report tie-in and the player/parent-initiated flag, both shipped by deferred-155 (the per-idea annotations further down the same file, added by `890f5c67`, say so). The story's conclusion ("fully covered") is correct, but the stale list is exactly what a future mining pass would re-pick — and this file already caused one such correction during deferred-155's creation.

**21. One adjacent free-text column is scrubbed by no erasure branch.** `booking.coach_availability_blocks.reason varchar(500)` (`CoachAvailabilityBlock:37-38`) is coach-authored free text touched by neither branch. Out of AC1's playerId scope (no `player_id` column), but it belongs in the ledger. By contrast `bookings.cancel_reason varchar(50)` is correctly out of scope — it is validated against `VALID_CANCEL_REASONS` (`BookingService:862-865,875-878`), not free text.

**22. A second reader of the same anti-pattern survives AC5.** `PlayerDevelopmentDashboardPage.vue:192` `route.query.playerName ?? ''`, fed to `PerformanceReportsPanel` at `:106`. Nothing pushes `playerName` onto that route today (`routes.js:285`), so the prop is always `''` — inert, but the same shape the story claims to close.

**23. No `.gitattributes` exists**, so the repo's first binary `*.ttf` has no `binary` marker. Git's NUL-byte heuristic handles it and `core.autocrlf` is unset, so this is insurance, not a defect — but `*.ttf binary` is cheap to add alongside the font.

**24. AC4 needs one fact the story omits:** openpdf 2.x keeps the `com.lowagie.text.*` package (the rename to `org.openpdf.text.*` landed in 3.x only) — per `pom.xml:527-529`. The `BaseFont`/`Font` imports AC4 writes are `com.lowagie.text.pdf.BaseFont` / `com.lowagie.text.Font`, matching the existing `:4-5` imports.

Minor citation drifts to correct in place: fonts `:458-461` (not `:457-460`); `toBookingResponse` `:488-493` (not `:485-492`); `setPlayerRequest`'s return `:757` (not `:756`); `getParentBookings` `:495-537`; `collectKeyPaths` `:18-29` and **not exported** — AC6 must export it first; `setPlayerRequest` `:737-758`; `extractPdfText` `:298-310`.

---

## 5. What did not survive re-verification

These were raised by a layer and **killed or downgraded** in Step 4b. Listing them is the point of this section.

1. **The ID-space trap on AC1 — KILLED.** This was the single most likely defect going in (this codebase has a documented history of `Booking.playerId` being a `PlayerProfile` PK rather than a `User` id, and an earlier story was bitten by exactly that). Re-checked from both ends: `deletePlayerDevelopmentData`'s Javadoc (`:1105-1109`) states its `playerId` is "always an already-resolved `player_profiles.id` … never a `main.user.id`", both callers pass `PlayerProfile#getId()`, and `Booking.playerId` is populated only from a validated `PlayerProfile` PK. **The story got this right**, and it correctly avoided the confusable `Booking.parentId` (a User id). (Collateral, out of scope: `GdprExportService:122` *does* commit this trap — `findAllByPlayerId(userId)` — harmless today only because the `parentId` arm at `:119` already catches those rows. Ledger candidate.)
2. **Maven resource filtering corrupting the vendored TTF — KILLED.** The highest-value "silent build breakage" hypothesis. `pom.xml` has no project-level `<resources>` override; `spring-boot-starter-parent:3.5.16` filters only `**/application*.{yml,yaml,properties}` and copies everything else unfiltered; the one filtered execution (`copy-resources` over `src/frontend/dist/spa`, `:653-681`) *already* declares `<nonFilteredFileExtension>ttf</nonFilteredFileExtension>`. `.gitignore` has no `*.ttf` rule. No corruption.
3. **AC5 as a privacy regression on authorization — KILLED.** I expected adding a minor's name to `/exposure` to widen exposure. It does not: `getWeeklyExposure`'s first statement is `requireCoachPlayerRelationshipIfCoach` (`:38`), which uses `READ_RELATIONSHIP_STATUSES` — and `CoachRosterService:59-60` sets the roster's own `ROSTER_MEMBERSHIP_STATUSES = CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES`, i.e. identical width to the endpoint that supplies the name today. Resource guard `SkillExposureResource:30` is `hasRole('ROLE_COACH') or @playerOwnershipGuard.check(...)`. Only sliver: the roster's extra 730-day recency floor and `maxPlayers` cap, for a coach already authorized to read that player's full exposure payload. Not a meaningful leak.
4. **"`BookingRepository:18-115` understates the interface" — DOWNGRADED to non-finding.** The story wrote ":18-115 **etc.**", which acknowledges incompleteness. The further `@Query` methods at 163-306 are real but the story never claimed otherwise.
5. **"`:480` is not a call to `eraseTransactional`" — KILLED as a false positive.** The story says "the self-registered-PLAYER-erasure **call site** (`eraseTransactional`, `:480`)" — i.e. the call site *inside* `eraseTransactional`, which is exactly what `:480` is.
6. **"`notes` truncated to 500 is a silent data-loss regression" — KILLED.** The column, the entity, and the request record all cap at 500; 500 is the right bound.
7. **AC1's `SET … = NULL` failing on NOT NULL columns — KILLED.** Both columns are nullable (`V138` bookings DDL; `V163__booking_bookings_player_request.sql`).
8. **AC1 deviating from the repo's anonymize idiom — KILLED.** No `@Modifying` method in any of the three comparable repositories uses `clearAutomatically`/`flushAutomatically`; the proposed JPQL-not-native, unqualified-entity-name shape matches `SessionCompletionDataRepository:41-43` exactly. Native + schema-qualified is used only where JSONB forces it. And `idx_bkg_player_coach_status` (`V138:3256`) leads on `player_id`, so the UPDATE is an index scan.
9. **AC1 being unable to use a JPA repository on the dedicated pool — KILLED.** The pool is a `RoutingDataSource` selected via thread-local (`:1234-1242`), not a separate `EntityManagerFactory`; the neighboring anonymize calls are ordinary JPA repositories running there already.
10. **AC7 requiring a Flyway migration for new reason codes — KILLED.** `admin.admin_alerts.reason` is `character varying(64)` with **no** CHECK constraint (only `type`, `status`, `reference_type` have them). The story's "no new Flyway migration anywhere" holds for all seven ACs. One real constraint to respect: new code names must be ≤64 chars.
11. **AC6 needing a new glob devDependency — KILLED.** `engines.node` allows `^20+`, so `fs.readdirSync(dir, { recursive: true })` is available; the story's own fallback works with no new dep.
12. **AC5 needing a new i18n key — KILLED.** `coach.playerDetailUnknownPlayerName` already exists in all three catalogs (`en-US:266`, `de-DE:300`, `fr-FR:30`).
13. **AC4's `BaseFont.createFont("fonts/X.ttf", IDENTITY_H, EMBEDDED)` classpath idiom and `PdfTextExtractor` round-trip — both CLEARED empirically** against the real openpdf 2.0.5 jar (classpath-relative load works; `CMapAwareDocumentFont` is present and extracts embedded-TTF text correctly; the 3-arg overload caches, so per-call cost is a map lookup). AC4's "confirm the exact loading idiom this openpdf version expects" is satisfied. Also cleared: `ReportGenerationService` is the repo's only PDF producer, and `ReportGenerationServiceIT` has no font-sensitive assertions.
14. **AC2's blank→null behavior change — KILLED as inert.** Only two `setNotes` sites exist; the sole frontend reader guards with `v-if="booking.notes"` (falsy for both `""` and `null`); no test asserts `""`; `BookingBatchService`/`BookingDuplicationService` never set notes.
15. **AC3 breaking a contract consumer — KILLED.** No `@PostAuthorize`, no `ResponseBodyAdvice`, no `@JsonView` anywhere in `src/main/java`; no OpenAPI snapshot pins the shape; `PlayerRequestResourceIT` reads the body as a `Map` keyed on `"playerRequest"` (`:142`) and survives unchanged; only `BookingServiceTest:1611`'s declared variable type needs editing (its `:1613` assertion compiles against the new record as-is). AC3's claim that test fallout is wider than that is an overestimate, not an error.

---

## 6. Recommendation

**Do not start implementation on AC2 or AC7 as written.** The rest of the story is sound and unusually well-cited.

Per-AC confidence, with what was and was not independently re-checked:

| AC | Verdict | Confidence | Basis |
|---|---|---|---|
| **AC1** (booking free-text GDPR) | Proceed after deciding findings 8-9 | **High** on the core design — the ID-space question that would have invalidated it was re-traced from both ends and the story is right. The `@Version` interaction (8) and the backwards precedent citation (9) are both re-verified directly and need an explicit decision, not a rewrite. |
| **AC2** (shared sanitize/truncate) | **Rewrite the rationale; keep the consolidation** | **High** — refutation established by *executing* `truncate` against the pinned 3.21.0 jar, not by reading docs. The missed email path (5) was traced end-to-end (service → event → listener → template). |
| **AC3** (narrow PATCH response) | Proceed as written | **High** — sole consumer verified, no contract/advice/snapshot consumers exist, test fallout is one declared type. Only the stale store comment (17) needs adding to scope. |
| **AC4** (PDF font) | Proceed; fix the test string and decide on bold | **Medium-high** — repo facts exact and the classpath/extraction mechanics were cleared empirically. The cp1252 coverage behind finding 11 I verified myself; the faux-bold content-stream evidence (13) I did **not** re-run and it is marked PLAUSIBLE. The base-14 glyph-drop premise itself remains unverifiable from the repo — AC4's own round-trip test is the right proof, and should be written to fail first. |
| **AC5** (playerName off the URL) | Proceed after resolving finding 6 | **High** — the privacy-regression hypothesis was checked and cleared; the load/error-state regression was confirmed by reading the template's guard order and the store's null-on-fetch. Finding 6 is a design decision the story must make, not a detail. |
| **AC6** (i18n key resolution) | Proceed; re-specify extraction before writing it | **Medium-high** — the call-site counts and 9 bogus matches come from an actual extraction run against the real catalog, which I did not independently repeat; the mechanism mis-citation (18) and the unstubbed `$te` I did verify. Task 6.1 already asks for the survey; it must happen before the regex is written, not after. |
| **AC7** (coach-erasure split) | **Re-scope before implementation** | **High** on all three blockers — the shared-transaction Javadoc, the zero test references, and the outbox "MUST be inside the producing transaction" contract were each re-read directly in Step 4b. The AC's goal and its named precedent are incompatible, and one of its four "tables" must not be split at all. |

Concretely, the smallest set of story edits that unblocks dev: rewrite AC2's rationale around the real `notes` gap and add the event/email call site; correct AC1's precedent citation and decide the `@Version` question; fix AC4's test character and the bold decision; resolve AC5's loading/error fallback; re-specify AC6's extraction; and re-scope AC7 to three groups (with the outbox staying attached to the `performance_reports` scrub) plus an explicit choice between real durability and observability-only — acknowledging that its coach-branch tests are new work.

What was **not** independently re-checked, and should be treated as single-source: the openpdf content-stream dump behind finding 13, the AC6 extraction counts in finding 12, and the openpdf base-14 glyph-dropping behavior underpinning AC4's premise (unverifiable from the repo by design).
