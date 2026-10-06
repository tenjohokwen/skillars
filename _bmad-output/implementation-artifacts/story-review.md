# Story Review — skillars-deferred-145

**Story:** `skillars-deferred-145-review-eligibility-maturity-cooldown-and-dispute-gate-rework`
**File:** `_bmad-output/implementation-artifacts/skillars-deferred-145-review-eligibility-maturity-cooldown-and-dispute-gate-rework.md`
**Status in `sprint-status.yaml`:** `ready-for-dev` (line 334) — confirmed in the map, not from the `last_updated` comment prose
**Audit date:** 2026-10-06
**Real HEAD at audit time:** `74d405d2` — *"Story Deferred-144: skp Cookie Consolidation, Open-Redirect Guard Dedup, and Auth Review/Test Cleanup (#251)"*

Every citation below was re-opened at `74d405d2`. No SHA, line number, or "current, shipped" claim written inside the story was taken on trust — including the story's own `a5f27563` attribution, which was independently re-derived from `git log`.

**Method note:** the four verification layers were run inline by the reviewing session rather than as parallel subagents (no subagent was requested for this task). Every quoted line below was read first-hand from the working tree, which is also what Step 4b (adversarial re-verification) requires. Section 6 lists what that second pass killed.

---

## 1. Verdict summary

| Severity | Count | Headline |
|---|---|---|
| **Critical** | 2 | The dispute gate can never fire; the parent/self discriminator is always false and locks out self-registered adult players |
| **High** | 2 | Test blast radius understated by ≥5 classes (incl. tests the story says to "keep unchanged"); prescribed frontend grep finds nothing |
| **Medium** | 2 | Wrong error code on the fail-closed path; both new `BoundedKey` notes misdescribe the negative case |
| **Low** | 5 | Stale advice description, non-existent migration precedent, unbounded query, a Dev Note describing a path no code takes, scope asymmetry |
| **Killed by re-verification** | 9 | See §6 — including four "test will break" claims that are wrong |

The story is well-written, unusually honest about its own staleness risk (Task 1 explicitly tells the dev not to trust the story's citations for migration numbering — good instinct, and correct), and its citation hygiene is strong: **27 of 29 code/precedent citations verified exact**. The two Critical findings are not citation drift. They are false assumptions about how the existing system actually behaves, and both would ship a feature that silently does nothing or silently blocks real users.

---

## 2. Citation verification (Layer 1)

| # | Story citation | Verdict | Evidence at `74d405d2` |
|---|---|---|---|
| 1 | `ReviewSubmissionService.java` — `checkEligibility` (line 207) | **MATCH** | `207: private void checkEligibility(UUID coachId, Long authorId) {` |
| 2 | `ReviewSubmissionService.java` — 365-day gate (line 114) | **MATCH** | `114: if (review.getLastModifiedAt().isAfter(Instant.now().minus(365, ChronoUnit.DAYS))) {` |
| 3 | `ReviewSubmissionService.java` — `checkEligibility` call (line 125) | **MATCH** | `125: checkEligibility(review.getCoachId(), authorId);` |
| 4 | `ReviewSubmissionService.java` — lock/refresh sequence (lines 135-160) | **MATCH** | 135-137 `lockRetryer.withBoundedRetry` → 147 `entityManager.refresh(locked, PESSIMISTIC_WRITE)` → 154-160 re-check. Range is exact. |
| 5 | `BookingRepository.java:127-138` — `existsRecentCompletedBookingByAuthor` | **MATCH** | `@Query` opens at 127, method signature 135-138. Exact. |
| 6 | `BookingRepository.java:122-138` — "current `existsRecentCompletedBooking`/`…ByAuthor`" | **MINOR DRIFT** | Both methods are there, but the first one's `@Query` block starts at **114** (122 is only its method-name line). Cosmetic. |
| 7 | `AgePolicyService.java:48-50` — `isMinor(LocalDate)` | **MATCH** | `48-50: public boolean isMinor(LocalDate dateOfBirth) { return isMinor(getAgeTier(dateOfBirth)); }` |
| 8 | `AgePolicyService.java:29-54` — `getAgeTier`/`isMinor` x2 | **MATCH** | `getAgeTier` 29-41, `isMinor(AgeTier)` 44-46, `isMinor(LocalDate)` 48-50, `isIndependentAccountAllowed` 52-54. |
| 9 | `AgePolicyService.java:57-61` — `getMessagingPolicy` (throwing write-path) | **MATCH** | 56 javadoc *"Write paths only: refusing on an unresolvable player is the safe answer there."*; 57-61 `.orElseThrow(UserNotFoundException)`. |
| 10 | `AgePolicyService.java:68-70` — `findMessagingPolicy` (degrading read-path) | **MATCH** | 68-70 `return playerProfileRepository.findById(playerId).map(this::resolvePolicy);` |
| 11 | `ConfigBounds.java:185-188` — the key being removed | **MATCH** | 185 javadoc, 186-188 `REVIEWS_SUBMISSION_WINDOW_DAYS = new BoundedKey("reviews.submissionWindowDays", 1L, 365L, true, …, 14L)`. |
| 12 | `ConfigBounds.java:37-46` — fail-fast principle | **MATCH** | `37: <h2>Fail-fast principle</h2>`, list 38-46. Exact. |
| 13 | `ConfigBounds.java:48-81` — "the `BoundedKey` record and the fail-fast principle" | **DRIFTED** | 48 is `public final class ConfigBounds {`; 53-81 is the record's **javadoc**; the record itself is at **line 82**. The fail-fast principle is at 37-46 (cited correctly elsewhere). Corrected location: **`ConfigBounds.java:82`** for the record. |
| 14 | `epics.md:149` — FR-REV-001 | **MATCH** | `149: - FR-REV-001: Review eligibility — at least one completed paid session and no active dispute.` Story's quote is verbatim-accurate. |
| 15 | `epics.md:144` — FR-MSG-002 | **MATCH (line)** / **INFERENCE (paraphrase)** | Line 144 is FR-MSG-002. But its text reads *"13–17: … all messages mandatory-visible to parent; 18+: unrestricted within scope"* — it never says parents "lose automatic visibility". The story's reading is a sound inference from "unrestricted", not a quote. Flagging only so the dev doesn't go looking for wording that isn't there. |
| 16 | `AgeTier.java` — `U10`, `AGE_10_12`, `AGE_13_17`, `ADULT` | **MATCH** | All four present; `ADULT.displayLabel()` returns `"18+"`, corroborating the 18+ cutoff. |
| 17 | `Booking.java` — `parentId`/`playerId` both `Long`, `status` plain `String`, `updatedAt` `Instant` | **MATCH** | Lines 31, 34, 46, 62. All four exact. |
| 18 | `V139__baseline_seed_data.sql` — `(key, value, value_type, description)` shape | **MATCH** | V139:140 uses exactly that 4-column shape. The story's correction away from the 5-column `(id, key, value, type, description)` shape is right. |
| 19 | `9.1` shipped at `a5f27563`, 2026-06-29 | **MATCH** | `git log -1 a5f27563` → `a5f27563 2026-06-29 Review Submission & Eligibility`. SHA, date and subject all exact. |
| 20 | "No `coach_reviews` table changes needed" | **MATCH** | Confirmed: nothing in AC1-AC5 requires a column; `last_modified_at` already exists `NOT NULL DEFAULT now()` (V138:1984). |
| 21 | Next migration number is **not** `V67+N`; check disk | **MATCH (and correct)** | Highest on disk is `V155__backfill_availability_window_canonical_timezone.sql`. Next is **V156**. The story's instruction to distrust its own citations here was the right call. |
| 22 | `ConfigService.getBoundedInt(key, default, min, max)` 4-arg exists | **MATCH** | `ConfigService.java:148`. |
| 23 | `AgePolicyService` + `PlayerProfileRepository` "already exist in `platform.security`" | **MATCH** | `platform.security.service.AgePolicyService`, `platform.security.repo.PlayerProfileRepository`. |
| 24 | `ReviewErrorCode.java` — `NO_RECENT_SESSION` present; enum grown since 9.1 | **MATCH** | `6: NO_RECENT_SESSION("reviews.noRecentSession")`; flagging codes at 17-24 confirm the growth claim. |
| 25 | `lastModifiedAt` is surfaced publicly on every review row | **MATCH** | `ReviewDto.java:14`; emitted at `ReviewQueryService.java:45, 65, 85`; also the default sort (`:53, :96`). |
| 26 | `existsRecentCompletedBookingByAuthor` has no caller besides `ReviewSubmissionService` | **MATCH** | Only `ReviewSubmissionService.java:216` + `ReviewSubmissionServiceTest.java:67`. `VideoAccessGuard.java:100` uses the *other* overload (`existsRecentCompletedBooking`), which the story correctly leaves alone. |
| 27 | No other `platform.reviews` service reads the config key or calls `checkEligibility` | **MATCH** | Key grep clean outside `ConfigBounds`/`ReviewSubmissionService`/V139/its unit test; `checkEligibility` is `private`, so externally uncallable by construction. |
| 28 | `DISPUTED` is a valid `bookings.status` value | **MATCH (schema)** | `chk_bkg_status` lists `'DISPUTED'` (V138:246). Legal in the schema — but unreachable in code. See **F1**. |
| 29 | `platform_config` INSERT may omit `id` | **MATCH** | `ALTER TABLE main.platform_config ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY` (V138:869-876). |

---

## 3. Ledger & precedent attribution (Layer 2)

The story cites **no** `deferred-work.md` entries, so there are no ledger line references to verify. It is sourced from a business review dated 2026-10-06 — consistent with the precedent set by `skillars-deferred-143`, whose own `sprint-status.yaml` note records it as *"Sourced from manual security analysis (not deferred-work.md, not a code review run)"*. Not a defect.

| Claim | Sources checked | Verdict |
|---|---|---|
| "several deferred stories (88, 107, 131, 132, 135) touched `ReviewSubmissionService.java` after 9.1 shipped" | `git log --format` on the file; in-file Javadoc/comments; `git log --grep` | **EXACT.** `git log` on that path returns precisely six commits: `595ed7c1` (135), `6af4531c` (132), `2842df52` (131), `dda13653` (107), `4f5b5cb7` (88), `a5f27563` (9.1). Exactly the five named, no omissions, no extras. Corroborated by in-file comments at lines 43-44 (135), 48 (132), 102 (88), 208-212 (107 + 132), 133-134 (135). |
| "…hardening concurrency and lock handling, but none changed the eligibility logic itself" | Same three sources | **SUPPORTED.** 107 and 132 did edit `checkEligibility`'s *body* (introducing `getBoundedInt`, then swapping a raw string literal for `ConfigBounds…key()`), but neither altered a rule. The story cites those exact comments itself, so it is not unaware. Fair as written. |
| "see 9.1's Change Log 1.1 entry for why it stayed `in-progress` until now" | `skillars-9-1-…md` Change Log; `sprint-status.yaml:148` | **MATCH.** Row 1.1 (2026-10-06) exists and reads *"Status corrected from `in-progress` to `done` … this file's own Status header was never synced to match `sprint-status.yaml`."* Refers to the **file header**, not the work. (This initially looked like a self-contradiction against "is `done`" — see §6.1.) |
| "`FR-MSG-002` is the precedent `reviews.parentalReviewNotApplicable` mirrors" | `epics.md:144`, `:374`; `AgePolicyService`; `AgeTier` | **SUPPORTED, with the inference noted at §2 #15.** The story is explicit and honest that the parent-of-minor gate is *"not in the original epic text"* — it does not overclaim epic authority. |
| "`FR-ADM-003`'s admin dispute-resolution path" | `epics.md:156`; `DisputeService`; `BookingStateMachine` | **CITATION MATCHES, MECHANISM DOES NOT EXIST.** Line 156 says what the story says. The code does not implement it. See **F1** / **F7**. |
| "deferred-63 AC5 allows a coach to raise a dispute" (implicit in AC2.c's threat model) | `DisputeService.java:85-101` | **MATCH** — in-code comment explicitly cites "Deferred-63 AC5". Relevant to **F11**. |

---

## 4. Mechanistic claims (Layer 3)

| Claim (story's wording) | Quoted evidence | Verdict |
|---|---|---|
| "`ReviewSubmissionService.checkEligibility()` … only ever checked for a recent `COMPLETED` booking — it never checked whether an unrelated booking … is currently `DISPUTED`" | `ReviewSubmissionService.java:213-222` — one `getBoundedInt`, one `existsRecentCompletedBookingByAuthor`, one throw. | **TRUE** |
| "`existsRecentCompletedBookingByAuthor` returns a plain `boolean` and cannot support the parent/age-tier distinction or the 'new session since last review' bound" | `BookingRepository.java:135-138` returns `boolean`; query has no projection and uses `updatedAt >= :windowStart`. | **TRUE** |
| "the 4-arg `getBoundedInt` … 0/neg → gate easier to satisfy" | `ConfigService.java:110-118`: out-of-range → `return defaultValue` (**no clamp**). | **HALF FALSE** — see **F6**. A negative value falls back to the safe default (7/30) and does *not* weaken the gate. Only `0` does, and with `min=0L` that value is *in range*, so nothing warns. |
| "`failFast=false` follows from `ConfigBounds`'s own fail-fast principle given that direction" | `ConfigBounds.java:37-46` — `failFast=true` is for data loss or *halting a core flow*. | **TRUE, and the reasoning is sound.** A lower-bound floor that degrades is correctly `failFast=false`. The story's warning not to copy `true` by habit is well-placed. |
| "`AgePolicyService.isMinor(LocalDate)` … already does exactly what's needed; do not add a new age-tier method" | `AgePolicyService.java:48-50`, 29-41. | **TRUE.** Note: `PlayerProfile` also carries a denormalised `age_tier` column (`PlayerProfile.java:36-37`); computing live from DOB is the more correct choice, since the stored tier can go stale. |
| "`isLinkedPlayerMinor` fails closed … follows `findMessagingPolicy`'s 'degrade this one row' convention, not `getMessagingPolicy`'s throwing write-path convention" | `AgePolicyService.java:56` vs `:63-67` javadoc. | **TRUE for the degrade decision.** But the *resulting error code* is wrong — see **F5**. |
| "the pessimistic-lock/refresh sequence … none of that concurrency machinery is affected by this story" | `ReviewSubmissionService.java:135-160`; `checkEligibility` call at `:125` sits *before* it and shares no state. | **TRUE at the code level. FALSE at the test level** — the regression tests that protect that machinery break. See **F3**. |
| "`updatedAt` remains the proxy for 'when this booking last changed state'" | `Booking.java:86-89` `@PreUpdate { updatedAt = Instant.now(); }` | **TRUE** — and stamped on *any* row write, which is what makes **F7** bite. |
| "A booking that bounces `COMPLETED → DISPUTED → COMPLETED` via `FR-ADM-003` gets its maturity clock reset" | `BookingStateMachine.java:73-78` permits it; **no code fires it**. | **FALSE** — see **F1**/**F7**. |
| "the author is reviewing on behalf of a linked player, never via `playerId = authorId`" | `ReviewResource.java:130-140` (authorId = User id) vs `BookingService.java:180` (`playerId` = PlayerProfile PK). | **FALSE** — see **F2**. This is the story's load-bearing assumption for AC2.b. |
| "`b.status = 'DISPUTED'` identifies an active dispute between author and coach" | `Dispute.java:45` `private String status = "OPEN"`; `DisputeRepository.findOpenByBookingId`. | **FALSE** — see **F1**. Dispute state lives in the `disputes` table, not `bookings.status`. |
| "the existing duplicate check (`409`) … unchanged" | `ReviewSubmissionService.java:66` then `:67` — eligibility runs **first**. | **TRUE for the code, FALSE for the test** — see **F3**. |

---

## 5. Corner cases, false assumptions, missed flows (Layer 4, post-Step-4b)

### F1 — CRITICAL: the active-dispute gate can never fire. `Booking.status` never becomes `DISPUTED`.

The story calls this gap *"a real gap, not a hypothetical"* and frames the whole story as finally implementing `FR-REV-001`. As specified, it implements nothing.

AC2.c and Task 3 predicate the gate on `b.status = 'DISPUTED'`. **No code path in the application ever puts a booking in that status.**

- `BookingEvent.DISPUTE` occurs in main source at exactly five places: `BookingService.java:106` (the `EVENT_ACTOR_ROLES` map *declaration*) and `BookingStateMachine.java:57, 64, 71, 74` (the transition *table*). **Zero invocation sites.**
- `DISPUTED` occurs in main Java only at `BookingStatus.java:19` and in that same state-machine table.
- In tests it appears only in `BookingStateMachineTest` — i.e. the status is exercised solely as a table entry, never as a reachable runtime state.
- `DisputeService.raiseDispute` (`DisputeService.java:82-133`) creates a `Dispute` row (`Dispute.java:45`, default `status = "OPEN"`) and publishes `DisputeRaisedEvent`. It never calls a booking transition.
- `resolveDispute` (`:254-270`) and `dismissDispute` (`:273-292`) set `Dispute.status` to `RESOLVED`/`DISMISSED` and touch payouts and alerts. Neither writes `Booking.status`.
- `ELIGIBLE_STATUSES` (`DisputeService.java:57-58`) includes `"COMPLETED"`.

That last point is what makes this a live correctness hole rather than merely dead code: **a disputed booking stays `COMPLETED`.** So the same booking simultaneously satisfies AC2.a (qualifying matured session) and is under an open dispute, while AC2.c never fires. The exact scenario the story exists to prevent — *"a live dispute is exactly the situation where a review is most likely to be used as leverage"* — remains wide open after the story ships.

It would also ship with a **green test that proves nothing.** Task 7's `submitReview_activeDisputeOnOtherBooking_returns403()` sets a booking to `DISPUTED` by raw SQL insert, exactly as the story describes. The fixture manufactures a state production never produces, so the test passes and the gate is never exercised against reality.

**Fix:** predicate on the `disputes` table — an open `Dispute` (`status = 'OPEN'`) joined to `bookings` on `booking_id`, filtered to the author/coach pair — or keep the booking-status check *and* additionally fix the dispute lifecycle. `DisputeRepository.findOpenByBookingId` is the existing shape to follow. Note the author/coach pairing must come from the joined `bookings` row, since `Dispute` stores only `bookingId` and `raisedBy`.

### F2 — CRITICAL: AC2.b's `playerId = authorId` discriminator is always false; self-registered adult players are permanently locked out with a nonsensical error.

AC2.b rests on treating `playerId = authorId` as "the player is reviewing for themselves" and `parentId = authorId` as "a parent is reviewing on behalf of a linked player". **These are different ID spaces.**

- `authorId` is a **User** id: `ReviewResource.resolveUserId()` → `Long.parseLong(p.getBusinessId())` (`ReviewResource.java:130-140`), passed in at `:82` and `:94`.
- `Booking.playerId` is a **PlayerProfile primary key**: `BookingService.createBookingRequest` resolves it with `playerProfileRepository.findById(req.playerId())` (`BookingService.java:180`), and `PlayerProfileRepository extends JpaRepository<PlayerProfile, Long>`.
- `Booking.parentId` *is* a User id (the authenticated caller).
- Both tables draw ids from `BaseEntity`'s `@Id @Tsid` (`BaseEntity.java:27`). They are separate entities; a user id equalling a profile id carries no meaning and is never true by construction.

So in the Task 6 snippet, `eligibleAsSelf` is dead code. Trace a **self-registered adult player** (a supported flow: `chk_pp_owner` at V138:911 allows `user_id` set with `parent_id` null; `BookingService.java:187-191`'s else-branch validates `player.getUserId() == parentId` and stores `booking.parentId` = that same user id; `POST /api/bookings` is `@PreAuthorize(HAS_PARENT_OR_PLAYER_ROLE)`, `BookingResource.java:37`; `AuthorRole` includes `PLAYER`; `CoachPublicProfilePage.vue:420-422` has dedicated handling *"for a self-registered player caller"*):

1. `eligibleAsSelf` → `authorId.equals(b.getPlayerId())` → **false** (user id vs profile id).
2. `eligibleAsParentOfMinor` → `authorId.equals(b.getParentId())` true, `!authorId.equals(b.getPlayerId())` true, `isLinkedPlayerMinor(theirOwnProfile)` → they are an adult → **false**.
3. Falls into the throw block; `onlyAdultParentMatches` → **true**.
4. → `403 reviews.parentalReviewNotApplicable`, *"Linked player is 18+ — parent cannot review on their behalf."*

**An adult player who trained with a coach can never review that coach, and is told they are a parent of an adult.** This is a new, permanent lockout introduced by this story — today these users pass eligibility fine, because the shipped `(parentId = :authorId OR playerId = :authorId)` only needs the `parentId` half to match.

In fairness: the cross-ID-space OR is **inherited**, not invented here — it is already in `BookingRepository.java:131` and repeated at `DisputeService.java:92`, even though `DisputeService.java:85-88` explicitly documents the profile-vs-user-id distinction for `coachId`. The defect specific to this story is making that meaningless comparison **load-bearing** for branch selection and error-code choice.

**Fix:** derive "is the author the player themselves" from `PlayerProfile.userId`, not from `Booking.playerId`. The projection already returns `playerId`; resolve the profile (which `isLinkedPlayerMinor` does anyway) and compare `authorId` against `profile.getUserId()` for the self case and `profile.getParentId()` for the parent case. That also removes the need for the `!authorId.equals(b.getPlayerId())` guard.

### F3 — HIGH: the test blast radius is understated by at least five classes, and three tests the story says to "keep unchanged" will fail.

Every affected fixture shares two properties that the new rules invalidate: the only `COMPLETED` booking is **1 hour to 3 days old** (inside the new 7-day floor), and the only player profile is **`age_tier='ADULT'`, `date_of_birth = now − 18 years`** (so `isMinor` is false). The story never instructs changing either.

**Named "keep unchanged" but will fail:**
- `ReviewSubmissionIT.submitReview_ratingOnly_returns201` — fixture booking is `now − 3d` (`ReviewSubmissionIT.java:105-106`), player ADULT (`:88-90`) → `403`.
- `ReviewSubmissionIT.submitReview_duplicate_returns409` — cannot reach `409`: `checkEligibility` runs at `ReviewSubmissionService.java:66`, **before** the duplicate check at `:67`, so the *first* POST already 403s.
- `ReviewUpdateIT`'s new `updateReview_afterCooldownWithNewSession_returns204` — its player is ADULT (`ReviewUpdateIT.java:90-92`); passes the cooldown, then 403s on the parental gate.

**Never mentioned anywhere in the story, and will fail:**
- `ReviewSubmissionIT.submitReview_validEligibility_returns201WithReviewId` (`:113-131`) — the module's primary happy path. Absent from Task 7 entirely.
- `ReviewSubmissionIT.updateReview_epochBumpAppliesToFreshLockedState_notStaleInstance` (`:245-295`) — calls the service directly; `lastModifiedAt = now − 400d` clears the cooldown, but the `now − 3d` booking fails the 7-day floor → `403`.
- `ReviewSubmissionServiceConcurrencyIT` (`:83-102`) — ADULT player, booking `updated_at = now − 3600s`; both tests call `submitReview`.
- `ReviewFlagServiceConcurrencyIT` (`:104-124`, call at `:178`) — ADULT player, booking `now − 3600s`; calls `updateReview`.
- `ReviewModerationIT` (`:89-117`) — ADULT player, booking `now − 3600s`; **all five** tests POST `/coaches/{coachId}` (`:130, :160, :181, :201, :253`).
- `ReviewSubmissionServiceTest` — a **hard compile break**: `:74` references `ConfigBounds.REVIEWS_SUBMISSION_WINDOW_DAYS` and `:67` stubs `existsRecentCompletedBookingByAuthor`, both of which Tasks 2 and 3 delete. It also needs new `@Mock AgePolicyService` / `@Mock PlayerProfileRepository`, or `@InjectMocks` (`:49-50`) injects nulls and the new code NPEs. Task 2's `grep -rn REVIEWS_SUBMISSION_WINDOW_DAYS src/test/` would surface it, but it appears in neither Task 7 nor any File List.

Note the irony on two of these: `ReviewSubmissionServiceConcurrencyIT` and `ReviewFlagServiceConcurrencyIT` are precisely the regression tests that deferred-132/135 built to protect the `REQUIRES_NEW` isolation and lock/refresh sequence the story says *"must survive this story untouched."* The production code does survive untouched; its guardians do not.

### F4 — HIGH: the prescribed frontend grep returns zero hits, so three i18n bundles get missed and two new error codes ship with no message.

Task 4 instructs: *"Grep the frontend codebase for the literal string `"reviews.noRecentSession"`."* **That string does not exist anywhere in `src/frontend`.** The bundles store it nested:

```js
reviews: {
  noRecentSession:
    'You need a recently completed session with this coach before you can leave a review.',
```
— `en-US/index.js:391`, `fr-FR/index.js:386`, `de-DE/index.js:727`.

A dev following the instruction literally concludes there are no frontend references. The resolution path is `useErrorHandler.errorMessage` (`useErrorHandler.js:40-49`), which does `te(key)` / `t(key)` on the **full dotted** `errorKey` and otherwise falls back to the server's raw English `message`. Consequences:

1. After the rename, all three locales fall through to the English service message (*"No qualifying completed session with this coach"*), leaving a dead `noRecentSession` key behind.
2. `reviews.activeDispute` and `reviews.parentalReviewNotApplicable` have **no entry in any locale** — two new 403s whose user-facing text is untranslated English. The story only covers *renaming* references; it never says to add entries.
3. `updateTooSoon`'s copy still says "once per year" in all three (`en-US:395`, `fr-FR:390`, `de-DE:731`). The story does flag this copy change — but not that it is three files.

There is direct precedent for treating this as a regression class: `errorHandler.js:52-57` documents `skillars-deferred-92 AC14`, created because *"an English literal here reached a French or German user verbatim."*

### F5 — MEDIUM: the fail-closed path reports a factually wrong error code.

`isLinkedPlayerMinor` returns `orElse(false)` for a missing `PlayerProfile`. Because the subsequent `onlyAdultParentMatches` branch uses only `parentId`/`playerId` and not the age result, an orphaned row produces `403 reviews.parentalReviewNotApplicable` — *"Linked player is 18+"* — when the real cause is a missing row. The story's Task 6 note carefully justifies the *degrade-don't-throw* decision (correct, and it genuinely matches `findMessagingPolicy`'s convention at `AgePolicyService.java:63-70`), but not the *message*. Prefer `NO_QUALIFYING_SESSION` plus a WARN log for the unresolvable-profile case, so the 403 does not assert something untrue about the player's age.

Related and benign: `PlayerProfile.dateOfBirth` is `nullable = false` (`PlayerProfile.java:28`; V138:899), so `getAgeTier`'s `Period.between(null, …)` NPE is not reachable through a normal row. Worth knowing only because the story's `.map(p -> …isMinor(p.getDateOfBirth()))` would propagate it unguarded if that ever changed.

### F6 — MEDIUM: both new `BoundedKey` notes misdescribe the negative case, and `0` is silently legal.

`getBoundedLong(key, default, min, max)` (`ConfigService.java:110-118`) **does not clamp** — an out-of-range value falls back to `defaultValue`. With `min = 0L`:

- A stored `-5` → out of range → falls back to `7` / `30`. **Safe.** So *"0/neg → sessions count as matured instantly"* and *"0/neg → an author can edit their review with no cooldown"* are both **wrong about `neg`**.
- A stored `0` → **in range** → honored. The floor/cooldown silently vanishes, and `ConfigStartupAssertion` (`:119-128`) never even logs it, because 0 is not a violation.

These `note` strings are operator-facing — they are interpolated into the startup ERROR line (`ConfigBounds.java:58`, `ConfigStartupAssertion.java:120-122`). Every other day-window key in the registry uses `min = 1`: `disputes.submissionWindowDays` (`:94`), the key being replaced (`:187`), `reviews.autoHoldFlagThreshold` (`:192`). The one `min = 0` precedent — the video-quota keys at `:410-413` — words its note precisely for that choice: *"neg → … math breaks; 0 is a legitimate \"no upload\" sentinel."*

Pick one: `min = 1` (matching every sibling day-window key, making 0 a flagged violation), or keep `min = 0` as a deliberate "disable the gate" sentinel and reword both notes to say so.

### F7 — LOW: the `COMPLETED → DISPUTED → COMPLETED` Dev Note describes a path no code takes.

Follows from **F1**. `BookingStateMachine.java:73-78` permits `COMPLETED --DISPUTE--> DISPUTED --SETTLE_COMPLETE--> COMPLETED`, but nothing fires either event, and `resolveDispute` never writes `Booking.status`. `FR-ADM-003` (`epics.md:156`) specifies *"resolves for coach (→COMPLETED) or parent (→REFUNDED)"* — unimplemented in the shipped dispute feature. The paragraph's conclusion (*"That reset is correct"*) is reasoning about a mechanism that does not run.

Two related gaps in the same note's enumeration:
- It names the DISPUTED bounce as *the* maturity-clock reset case but omits `COMPLETED_PENDING_CONFIRMATION → COMPLETED` (`BookingStateMachine.java:68-71`), where `updatedAt` is the confirmation or quick-complete-timeout moment rather than session end — up to a day of drift via `booking.quick_complete_timeout_hours`.
- More broadly, `Booking.@PreUpdate` (`Booking.java:86-89`) stamps `updatedAt` on **any** row write (`primaryReminderSentAt`, `secondaryReminderSentAt`, `cancelReason`, `batchId`, a `@Version` bump). **The harm direction inverts with this story:** under the old upper-bound window an incidental bump *extended* eligibility; under a lower-bound floor the same bump *revokes* it for another 7 days. The note reasons about the DISPUTED case in isolation and never generalises.

### F8 — LOW: Task 1 defers a decision to a precedent that does not exist.

Task 1 offers `DELETE` *"or an `UPDATE`/replace if the project's migration convention prefers never deleting config rows — check recent migrations for the established pattern before choosing."* There is nothing to find: **only V138 and V139 reference `platform_config` across all 155 migrations**, and there is no `DELETE FROM … platform_config` anywhere. The dev searches, finds nothing, and the decision stays open. The story should just pick one.

Verified-correct in the same task: next number is **V156**; the `(key, value, value_type, description)` shape (V139:140); omitting `id` is safe (V138:869). Not stated but constrained: `value_type` must be `'LONG'`, per `chk_platform_config_type` (V138:863) — worth spelling out since the task gives no literal.

### F9 — LOW: Task 5's description of `ReviewApiAdvice` is stale.

The **conclusion is correct** — both new codes fall through to `else → 403 FORBIDDEN` (`ReviewApiAdvice.java:59-62`) — so the task's outcome holds. But its description of the advice is the 9.1-era shape:
- The `409` branch holds **five** codes, not the two named: `ALREADY_FLAGGED`, `ALREADY_APPROVED`, `ALREADY_BLOCKED` were added at `:40-42`.
- A third branch goes unmentioned: `COACH_PROFILE_MISSING → 500` (`:44-55`), whose own comment warns *"This branch must stay explicit: the else fallback is 403, so an unlisted code would silently become FORBIDDEN"* — directly relevant context for adding two new codes.

### F10 — LOW: the qualifying-session query is unbounded.

By design there is no upper time bound (the story is explicit that this is deliberate), and `findQualifyingCompletedBookings` has no `LIMIT` and no `ORDER BY` — it returns every matching `COMPLETED` booking the pair ever had, then streams it up to three times.

Honest sizing, because my first pass overstated this: the per-row cost is small. `configService.find` is **cache-backed**, not a query per call (`ConfigService.java:190-193`, `ensureFresh()` over an in-memory `cache`), and repeated `playerProfileRepository.findById` for the same id collapses via Hibernate's persistence-context identity map — so profile lookups scale with *distinct players* (typically one or two), not bookings. It is a tidiness and worst-case concern, not a hot path. Still: a `LIMIT`, or an exists-shaped query for the self case plus a `DISTINCT` projection for the parent case, costs nothing. If a batched lookup is ever wanted, `AgePolicyService.findMessagingPoliciesByPlayerIds` (`:78-84`, added by deferred-90 AC13 for exactly this reason) is the precedent.

### F11 — LOW (scope decisions worth making explicitly)

Both are moot until **F1** is fixed, but should be decided now:

1. **The gate is submit/update-only.** A dispute opened *after* a review is approved leaves the public review untouched. Defensible as an eligibility story, but narrower than the stated rationale (*"used as leverage by either party"*). Worth one sentence saying so deliberately.
2. **An unconditional dispute gate hands coaches a one-sided veto.** `BookingEvent.DISPUTE` is allowed to `PARENT` **or** `COACH` (`BookingService.java:106`), and `DisputeService.java:85-101` explicitly permits coach-raised disputes per deferred-63 AC5. Dispute resolution is admin-only (`SETTLE_*` is `ActorRole.SYSTEM`) with no time bound on how long a dispute may stay open. So a coach can block an incoming review — and every future edit of an existing one — for as long as an admin leaves the dispute open. Consider scoping the gate to disputes the *author* raised, or to a bounded window.

---

## 6. What did **not** survive re-verification

Listed because the calibration matters more than a clean report. Nine claims from the first pass were killed or downgraded on a second, skeptical read:

1. **"The story contradicts itself — 9.1 is both `done` and 'stayed `in-progress`'."** **Killed.** 9.1's Change Log row 1.1 (2026-10-06) says the *story file's Status header* lagged `sprint-status.yaml`, not that the work was incomplete. The story's reference is accurate; I had read "stayed in-progress" as a claim about the work.
2. **"`submitReview_bodyTooLong_returns400` will break."** **Killed.** `@Size(max = 1000)` lives on the DTO (`SubmitReviewRequest.java:10`), so it raises `MethodArgumentNotValidException` and is handled by `ReviewApiAdvice.handleValidation` (`:66-83`) — before `ReviewSubmissionService` is entered at all. The story's "keep unchanged" is right.
3. **"`submitReview_coachNotFound_returns404` will break."** **Killed.** `coachProfileRepository.existsById` is at `ReviewSubmissionService.java:63`, before `checkEligibility` at `:66`. Right as written.
4. **"`updateReview_blockedStatus_returns403` and `updateReview_wrongAuthor_returns403` will break."** **Killed.** Author check (`:109`), cooldown (`:114`) and moderation status (`:119`) all precede `checkEligibility` (`:125`), and the fixture's `lastModifiedAt = now − 400d` clears the new 30-day cooldown. Both correctly listed as unchanged.
5. **"Three config DB reads per booking inside the `anyMatch` stream."** **Killed.** `ConfigService.find` is cache-backed (`:190-193`). Folded into **F10** with the real sizing stated.
6. **"`ReviewFlagIT` is affected."** **Killed.** It only POSTs `/{reviewId}/flag`; its reviews are raw SQL inserts and its `booking.bookings` insert (`:121`) is not on the eligibility path.
7. **"`getAgeTier` could NPE on a null date of birth."** **Downgraded** into **F5**. `date_of_birth` is `NOT NULL` (`PlayerProfile.java:28`, V138:899), so it is not reachable through a normal row.
8. **"`HAS_CODE_DEFAULT` is typed `Set<BoundedKey>`, so Task 2's instruction is wrong."** **Killed.** It is `Set<String>` of `.key()` values (`ConfigBounds.java:352-370`), and the story never claims otherwise — it just says "add both", which is right.
9. **"Stories 107/132 *did* change eligibility logic, contradicting the Dev Note."** **Killed as a finding.** They changed how the window value is *read* inside `checkEligibility`, not any rule, and the story cites those very comments (`ReviewSubmissionService.java:208-212`). Fair as written.

Also checked and found clean (no finding): `CoachResponseIT` is correctly scoped out (only `/{reviewId}/response`); `AuthorSelfViewIT` and `PublicReviewListIT` are GET-only on the review-read paths; `PessimisticLockRetryerCallSiteAuditTest` references `updateReview` only in Javadoc; `VideoAccessGuard` uses the overload the story leaves alone; `submitCoachResponse` genuinely needs no change.

---

## 7. Recommendation

**Do not start implementation as written.** Per-claim confidence, not a blanket score:

| Finding | Confidence | Basis |
|---|---|---|
| **F1** dispute gate can never fire | **Very high** | Exhaustive grep of `BookingEvent.DISPUTE` and `DISPUTED` across `src/main` and `src/test`, plus reading all three `DisputeService` lifecycle methods end to end. Zero invocation sites found; corroborated by `DISPUTED` appearing in exactly one test file, a pure state-machine unit test. |
| **F2** `playerId = authorId` always false; adult players locked out | **Very high** | Both ID origins read first-hand (`ReviewResource.java:130-140`, `BookingService.java:180`), `@Tsid` chain traced through `BaseEntity`, `chk_pp_owner` and the else-branch at `BookingService.java:187-191` confirm the self-registered shape, and the frontend confirms the flow is live. |
| **F3** test blast radius | **Very high** | Every fixture read line by line; each verdict traced through the actual guard ordering in `ReviewSubmissionService`. Four first-pass claims in this area were *wrong* and are retracted in §6 — the eight that remain were each re-derived. |
| **F4** frontend grep / i18n | **High** | The literal string's absence and all three nested keys verified directly; resolution mechanism read in `useErrorHandler.js:40-49`. |
| **F5** wrong error code on orphaned row | **High** (logic) / **judgment** (severity) | Logic follows from the Task 6 snippet as written. Whether a misleading 403 message warrants a code change is a product call. |
| **F6** `BoundedKey` notes vs `min = 0` | **High** | `getBoundedLong`'s no-clamp behaviour and `ConfigStartupAssertion`'s range check both read directly; registry convention compared across all sibling keys. |
| **F7, F9** stale / non-existent mechanism descriptions | **High** | Direct reads. Documentation-accuracy issues, not functional defects. |
| **F8** no migration precedent | **High** | Exhaustive grep over all 155 migrations. |
| **F10** unbounded query | **Medium** | Real, but materially smaller than first assessed once the config cache and Hibernate identity map were accounted for. Sizing is stated honestly above. |
| **F11** scope decisions | **Judgment** | Not defects; decisions the story should make explicitly. |

**Minimum changes before `ready-for-dev` again:**

1. **Rewrite AC2.c and `existsActiveDisputeByAuthor`** against the `disputes` table (open dispute joined to `bookings`), or pair the story with a fix to the dispute→booking-status lifecycle. As written the gate is inert, and its prescribed test would certify it anyway.
2. **Rewrite AC2.b's discriminator** to use `PlayerProfile.userId` / `PlayerProfile.parentId` rather than `Booking.playerId`, and add an explicit AC plus test for *"self-registered adult player reviews their own coach → 201"*.
3. **Expand Task 7** to name `ReviewModerationIT`, `ReviewSubmissionServiceConcurrencyIT`, `ReviewFlagServiceConcurrencyIT`, `ReviewSubmissionServiceTest`, `ReviewSubmissionIT.submitReview_validEligibility_…`, and `ReviewSubmissionIT.updateReview_epochBumpAppliesToFreshLockedState_…`; state explicitly that each shared `@BeforeEach` fixture needs its booking aged past the floor and its player DOB set below 18. Correct the three "keep unchanged" entries that will not hold.
4. **Replace Task 4's grep instruction** with the nested-key reality (`reviews: { noRecentSession: … }` in three bundles) and add an explicit subtask to create `activeDispute` and `parentalReviewNotApplicable` entries in all three locales plus re-word `updateTooSoon` in all three.
5. **Decide `min`** for both new keys and make the two `note` strings accurate about the negative case.
6. Minor: fix the `ConfigBounds.java:48-81` → `:82` citation, refresh Task 5's description of the advice's branches, decide DELETE-vs-UPDATE in Task 1 and name `value_type = 'LONG'`.

**What was *not* independently re-checked:** no test was executed and no migration was run — every "will fail" verdict in **F3** is derived by reading fixtures against guard ordering, not from a red test run. Given the standing convention against local `mvn verify`, the cheapest confirmation is to let CI run the affected classes once the fixtures are updated. I also did not audit `ReviewModerationService`, `CoachRatingService`, or `ReviewQueryService` beyond confirming they neither read the config key nor can reach the private `checkEligibility`; the story's scope boundary there held up on the checks performed.
