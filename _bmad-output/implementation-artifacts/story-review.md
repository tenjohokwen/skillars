# Pre-Implementation Story Review — skillars-12-1-coach-player-roster-and-attention-summary

| | |
|---|---|
| **Story key** | `skillars-12-1-coach-player-roster-and-attention-summary` |
| **Story file** | `_bmad-output/implementation-artifacts/skillars-12-1-coach-player-roster-and-attention-summary.md` |
| **Status at review** | `ready-for-dev` (uncommitted in `sprint-status.yaml`) |
| **Audit date** | 2026-10-14 |
| **Real HEAD verified against** | `66932d90` — *"Add coach self-service UI for reviews, session-pack tiers, and report branding; harden null-ROLES auth, booking time gates, and CI/branch-protection provenance (#265)"* |
| **Method** | 4 parallel read-only verification layers + mandatory adversarial re-verification of every surviving finding by the orchestrator |

**All citations in this report were checked against working-tree source at HEAD `66932d90`, not against any SHA, line count, or "current as of" claim written inside the story itself.** The story contains no self-asserted SHA, so there was no stale-commit trap to fall into here — but every line range was still re-read rather than trusted.

**Process note (not a story defect):** `sprint-status.yaml`'s `last_updated:` changelog comment still narrates `skillars-deferred-151` and was not updated when Epic 12 was added. The skill's documented "first story key in the `last_updated` comment" heuristic therefore resolves to a `done` story. The target was identified instead from the map's single `ready-for-dev` entry, corroborated by the current branch name and the story file's creation date. Worth fixing the header comment so the heuristic stays reliable.

---

## 1. Citation verification (Layer 1)

15 citations checked. **No fabricated citations — every target resolves to real code at or adjacent to the claimed location.**

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `BookingRepository.java:144-159` — `SELECT DISTINCT b.playerId` precedent | **MATCH** | `@Query` opens at 144, signature closes at 159. (Returns a projection interface, not `List<Long>` — immaterial to the shape claim.) |
| `CoachPlayerAuthorizationService.java:20-21` — `ACTIVE_RELATIONSHIP_STATUSES` | **MATCH** | Exact. `private static final List<String> … List.of("ACCEPTED","CONFIRMED","COMPLETED","UPCOMING")` |
| `security/repo/PlayerProfileRepository.java` | **MATCH** | Exists; `extends JpaRepository<PlayerProfile, Long>` (`:14`) |
| `SluRepository.java:51-56` — `findLastSessionDate` | **MATCH** | Exact. Native query, returns bare `Instant` (nullable) |
| `NeglectedSkillFlagRepository.java:11` | **MATCH** | Exact line. `List<NeglectedSkillFlag> findByPlayerIdAndResolvedAtIsNull(Long)` |
| `SluDashboardService.java:97-125` — `getSkillTrends` | **MATCH** | Signature 97, closing brace 125 |
| `ConfigBounds.java:148-151` — `TIMELINE_COACH_ACCESS_EXPIRY_DAYS` | **MATCH** | Javadoc 148, declaration 149-151. `BoundedKey` record is at `:82`; `ALL` declared `:393`, populated in the static block `:395-443` |
| `TimelineQueryService.java:29-37` — try/catch-and-default | **MATCH** | try/catch is exactly 29-37 (method signature sits at `:28`) |
| `CoachBrandingResource.java:29-33` — `getBranding()` | **MATCH, with correction** | Method actually spans **29-35**. **There is no class-level `@RequestMapping`** — annotations are `@RestController` + `@RequiredArgsConstructor` only, and the full absolute path lives on `@GetMapping`. A new resource must likewise spell out the whole `/api/...` path. |
| `CoachReviewsPage.vue:6-32` — state branching | **MATCH, with correction** | Range is right, but the precedent has **five** branches, not four: loading (6-8), **incompleteProfile (13-24)**, loadError (26-28), empty (30-32), populated (`v-else` at **34**, outside the cited range). See finding M4. |
| `routes.js:163-178` — coach routes block | **DRIFTED (boundary)** | Content is coach routes, but the range starts and ends mid-object. Contiguous block is **`routes.js:162-218`**; further coach routes also at `67-85` and `317-331`. |
| `MainLayout.vue:144-180` — `<!-- Coach section -->` | **DRIFTED (boundary)** | The literal comment **does** exist at line 144, but the block runs to **194** (`</template>`); 144-180 truncates mid-`q-item`. |
| `src/frontend/src/api/development.api.js` | **MATCH** | Exists; 18 named exports, no roster-shaped export yet |
| `CoachReviewsPageSpec.js` + `__tests__/` convention | **MATCH** | `src/frontend/src/pages/coach/__tests__/CoachReviewsPageSpec.js`; mocks `src/api/reviews.api` via `vi.mock`. Note the convention is `*Spec.js`, not `*.spec.js`. |
| `PlayerTimelineResourceIT` + `@Sql({SecurityIT.SEC_DATA_SQL_PATH})` | **MATCH** | Real path confirmed; constant at `SecurityIT.java:52` = `"/sql/secData.sql"` |

---

## 2. Ledger & precedent attribution (Layer 2)

Every item below was checked against **all three** of: the referenced document, source comments/Javadoc near the code, and `git log --grep` / `git blame` / `git log -S`.

| Claim | Sources checked | Verdict |
|---|---|---|
| "not sourced from `planning-artifacts/epics.md` (that file's epics 1–10 are already complete)" | epics.md full read; skillars-epics.md; sprint-status.yaml | **CONTRADICTED** — see M5 |
| "there is no pre-existing epic entry for this work" | skillars-epics.md UX-DR5 + Story 3.5; CoachCommandCenterPage.vue; en-US i18n | **CONTRADICTED** — see M6 |
| Brainstorm doc path, Agreed Build Order, Priority 1 = Theme 1, Priorities 2–6 | brainstorm doc full read | **CONFIRMED** verbatim |
| Theme 4 "qualitative-private vs quantitative-shared" | brainstorm doc | **CONFIRMED** verbatim |
| Brainstorm's Codebase Grounding class list | per-class grep of the doc | **PARTIALLY_CONFIRMED** — `NeglectedSkillFlag` and `SluDashboardService` appear in Priority 1's "Resources needed", not in Codebase Grounding; and the "already `ROLE_COACH`-authorized" line is the doc's *security-gap bullet* (L116) cited as reassurance. Low severity. |
| Brainstorm flags the `/exposure`-family relationship-check gap | brainstorm L116 | **CONFIRMED** the doc says it — but the doc is itself wrong; see M3 |
| `skillars-epic-11` precedent, "tracked the same way in sprint-status.yaml" | sprint-status.yaml `:186-190` vs `:360-362`; `git log -S` → commit `461fbadf` | **PARTIALLY_CONFIRMED** — the sprint-status entry shape matches **exactly** (`skillars-N-M-slug` + epic/retrospective bookends), and Epic 11 really is the payment-cutover initiative. But `461fbadf` shows the epic-11 precedent also landed a 100-line `## Epic 11` section in `skillars-epics.md`; `grep "Epic 12" planning-artifacts/` returns zero hits. |
| "`skillars-deferred-140`…`151` bundle gaps mined from `deferred-work.md`" | sprint-status.yaml; `deferred-work.md` (real path: `_bmad-output/implementation-artifacts/deferred-work.md`); deferred-151 story References | **PARTIALLY_CONFIRMED** — all 12 keys exist and are `done`; 146 (CI perf) and 147 (nav link) aren't bug-mining. ~83% accurate rhetorical contrast; not a defect. |
| project-context.md: record DTOs, `<script setup>`, centralized `*.api.js`, mandatory `@PreAuthorize` w/ `SecurityConstants`, i18n | project-context.md L42/45/47/53/56/81/85 | **CONFIRMED** verbatim (5 of 6) |
| project-context.md: "Instancio per testing rules" | project-context.md L61 | **PARTIALLY_CONFIRMED** — the doc scopes Instancio to *"DTO and Entity test data"*; Task 5.2 applies it to scalar inputs of a pure scoring function. Over-citation, not fabrication. |
| `/slu/coach-contributions` as "aggregate, shared across coaches" precedent | SkillExposureResource.java:56-65; SluContributionService full read | **CONFIRMED** — and stronger than the story needs: it discloses *other coaches by name* plus their contribution share. Also confirms that endpoint has **no** relationship check. |
| "`NeglectedSkillDetectionService` already loops per-player at platform scale in its weekly job" | full class read; in-class comments L25-31, L98-104; cron | **PARTIALLY_CONFIRMED** — weekly ✓ (`0 0 6 * * MON`), per-player ✓, platform-scale ✓. But the class's own comments call the loop **"un-chunked"** with a runtime tripwire at 24 min of a 30-min `lockAtMostFor`. It is a documented scaling *liability*, not an endorsement. See M9. |
| de-DE / fr-FR "native-register conventions already established" | AST walk of all three locale files | **CONFIRMED** — genuinely native (`'Trainer-Kommandozentrale'`, `'Espace coach'`), `coach` namespace present in all three with identical 6-key sets. Pre-existing drift: en-US 1208 keys vs 1207 each; `auth.emailVerifiedPleaseLogin` is en-US-only. |

---

## 3. Mechanistic claims (Layer 3)

| # | Claim | Evidence | Verdict |
|---|---|---|---|
| 1 | Proposed `findDistinctPlayerIdsByCoachIdAndStatusIn(UUID, List<String>) → List<Long>` is type-correct | `Booking.java:33-34` `Long playerId`; `:36-37` `UUID coachId`; `:45-46` `private String status` (plain String, **no** `@Enumerated`); DB `V138:234` `varchar(30)` + 18-value CHECK at `:246` | **CONFIRMED** — compiles. Sibling methods `existsByCoachIdAndStatusIn` (`:191`) and `existsByCoachIdAndPlayerIdAndStatusIn` (`:200-203`) take the identical param types. The explicit `@Query` the story specifies **is** required (no derived-query property exists for this name). |
| 2 | `Booking.playerId` is a `PlayerProfile` PK | `PlayerProfile extends BaseEntity`; `BaseEntity.java:27-29` `@Id @Tsid protected Long id`; decisive usage `BookingService.java:180` resolves `req.playerId()` as a `PlayerProfile`, `:331` stamps the same value onto the booking | **CONFIRMED** — code-enforced only. There is **no DB FK** on `booking.bookings.player_id` (only `bookings_batch_id_fkey` and `bookings_session_pack_purchase_id_fkey` exist at `V138:4056,4063`). Ruled out the User id-space confusion: `Booking.parentId` *is* a `main."user"` id, and `BookingRepository.java:148`'s `b.playerId = :authorId` is the self-registered-adult case, not evidence of a User id. |
| 3a | `findLastSessionDate` returns `Instant` | `SluRepository.java:56` bare `Instant` (not `Optional`); `V138:370` `calculated_at timestamptz NOT NULL`; `MAX()` over zero rows → Java `null`; existing consumer `TimelineQueryService.java:43` already null-checks | **CONFIRMED** — the story's nullable DTO field is right |
| 3b | Display name via `PlayerProfileRepository.findAllById(ids)` is sufficient | `PlayerProfile.java:25-26` `@Column(nullable=false,length=100) private String name`; the exact batched pattern already exists at `BookingService.java:977-979` | **CONFIRMED** — Task 1.3 is sufficient; no `User` join needed. (Contrast `resolveParentName` at `:959`, which *does* need `User.firstName/lastName` — the trap the story avoided.) Minor tension: `PlayerProfileRepository.java:34` carries *"Always use this instead of findById — parentId enforces family isolation"*; `findAllById` bypasses that, safe here because coach-scoping comes from the booking query. |
| 4 | `ACTIVE_RELATIONSHIP_STATUSES` is `private static final` and matches AC1 exactly; package-private suffices | `CoachPlayerAuthorizationService.java:20-21`; sole consumer `:43-49` binds it as a JPQL `IN :statuses` param (`BookingRepository.java:198`); `package …platform.development.service` at `:1` | **CONFIRMED** — four members, identical set to AC1. Same package as the proposed `CoachRosterService`, so package-private works. Ruled out the second overload `requireCoachPlayerRelationship(Long, Long)` at `:30-37`, which shares the one constant. |
| 5 | `getSkillTrends` re-checks the relationship — "redundant … but harmless; do not bypass it" | `SluDashboardService.java:98` calls it; guard body `:132-138`; resolves coach from **SecurityContext** via `securityUtil.getCurrentCoachUserId()` (`SecurityUtil.java:293-303`) | **PARTIALLY_WRONG** — mechanism is as described and will **pass** (not throw) for the roster path, since Task 1's player set is derived from the same predicate. But: (a) it is a **`private` method of `SluDashboardService`**, not a method on `CoachPlayerAuthorizationService` — "do not bypass it" is vacuous, there is no way to; (b) "harmless" is wrong on cost — it adds **3 DB round-trips per player** (see M9); (c) because the coach comes from the SecurityContext, `getRoster` is only callable under a live coach principal (`requireCurrentUserId`/`getCurrentCoachUserId` throw `InsufficientAuthenticationException` otherwise), which constrains test design. Explicitly distinguished from the public `requireCoachPlayerRelationship` overloads. |
| 6 | `direction()` accessor; `DECLINING` and `INSUFFICIENT_DATA` exist | `SkillTrend.java:14-19` record component `direction` ⇒ `direction()`; `SkillTrendDirection.java:8-13` = `IMPROVING, FLAT, DECLINING, INSUFFICIENT_DATA` | **CONFIRMED** — Task 2.3's denominator correctly spans the other three. Ruled out the package-private `SluTrendClassifier.Result`, which also has a `direction()` accessor and *would* be visible to a same-package service. Dev must navigate `response.trends()` first. |
| 7 | `findByPlayerIdAndResolvedAtIsNull`; `resolvedAt` real; flag is player-level with no coach column | `NeglectedSkillFlagRepository.java:11`; entity `:16-33` (five fields, no `coachId`); `V138:312-318` (five columns, zero coach dimension) | **CONFIRMED** — the Dev Notes "Multi-coach data boundary" reasoning holds. Ruled out the near-identical `findByPlayerIdAndSkillCodeAndResolvedAtIsNull` at `:13`, which returns `Optional` — `.size()` on it would not compile. |
| 8 | Skill trends "derived from `player_skill_stats` across all coaches" | `player_skill_stats` **has** `coach_id uuid NOT NULL` (`V138:367`). But `getSkillTrends` reads `snapshotRepository.findByPlayerIdFromWeek` (`:106-107`) → `player_slu_weekly_snapshot`, whose DDL (`V138:395-401`) has **five columns and no coach dimension at all** | **PARTIALLY_WRONG** — see M7 |
| 9 | `SecurityConstants.HAS_COACH_ROLE`, `@Observed`, `getCoachIdByUserId`, `getCurrentCoachUserId` | `SecurityConstants.java:35` = `"hasRole('ROLE_COACH')"`; `@Observed` used in 76 files; `CoachProfileService.java:707` `public UUID getCoachIdByUserId(Long)`; `SecurityUtil.java:293` `public Long getCurrentCoachUserId()`; `CoachBrandingResource.java:33` | **CONFIRMED** — line 33 is character-for-character what Task 3.2 proposes. Highest-risk confusion in the audit, and the story got it right: ruled out `SecurityConstants.ROLE_COACH` (`:19`, the bare `"ROLE_COACH"` string — passing *that* to `@PreAuthorize` would be a silent authorization failure) and the two adjacent `SecurityUtil` id-resolvers. |
| 10 | `ConfigBounds.BoundedKey` with bounds `[1,3650]`, `failFast=false`; `configService.getBoundedLong(...)` | `ConfigBounds.java:82` record has **six** components | **PARTIALLY_WRONG — WILL NOT COMPILE.** See B2 and B3. |
| 11 | AC4 "no redeploy … immediately … on the next call" | `ConfigService.java:45-46` cache; `ensureFresh()` `:374-378` gated on `app.config.cache-ttl-seconds` (default **300**, `:63`); `invalidate()` is node-local per the class's own javadoc at `:340-352` | **PARTIALLY_WRONG** — see B3 |
| 12 | `meta: { requiresAuth: true, requiresCoach: true }`; relative `coach/players` path | `router/index.js:47,49,59,69` read exactly those two flags; `routes.js:1-6` nests all coach routes under the `/` MainLayout parent, so relative paths are correct; nine sibling coach routes use the identical meta | **CONFIRMED** — ruled out three other meta conventions in the same file (`meta.role` singular, `meta.roles` array, `meta.requiresGuest`) that coach routes deliberately do *not* use |

**Transaction boundary (traced, no defect found):** `CoachPlayerAuthorizationService` (`:17`), `SluDashboardService` (`:31`) and `TimelineQueryService` (`:21`) are all `@Transactional(readOnly = true)`. A `readOnly` `CoachRosterService` joins one read-only transaction (propagation `REQUIRED`) for the whole roster loop — a single consistent snapshot, zero writes, nothing to roll back. No atomicity or partial-commit claim in this story rests on anything unverified.

---

## 4. Corner cases, false assumptions, missed flows

Severity reflects what it costs to discover during implementation rather than now. Every finding below survived independent re-verification.

### BLOCKERS — the story cannot be implemented as written

#### B1. AC6 and Task 2.5 specify opposite behavior, and Task 5.1's fallback reads data no task fetches
*AC6, Task 2.5, Task 5.1, Task 1.1*

AC6: *"missing signals default to their **lowest-urgency** value, not null-propagate into the score."*
Task 2.5: *"daysSinceLastSession clamped to `[0, dormantAfterDays]` and normalized to `[0,1]` — a never-trained-together player normalizes to **`1`**, not null"*, combined as `0.4*recency`.

`1` is the **maximum** of `[0,1]`. A brand-new player therefore receives the largest possible recency contribution — 40% of the whole score — which is exactly the "missing data treated as maximum urgency" AC6 forbids. The other two signals *do* comply (a new player's `neglectedCount` is 0, and `decliningRatio` defaults to 0), so this is isolated to the recency term, but it is the heaviest-weighted one.

Task 5.1's escape hatch is not implementable either: it says such a player should be *"ranked by **recency-of-booking** alone"*, but nothing in Tasks 1–3 ever reads a booking date. Task 1.1 projects `List<Long>` playerIds only; Task 2.1's sole date source is SLU-based. Usable dates do exist — `Booking.java:58-59` (`created_at`) and `:39-40` (`requested_start_time`) — but no task projects either.

**Needs:** pick one semantic (missing → `0.0` per AC6, or missing → max per 2.5) and, if booking recency is the intended fallback, widen Task 1.1 to a projection such as `SELECT b.playerId, MIN(b.createdAt), MAX(b.requestedStartTime) … GROUP BY b.playerId`. That same projection also supplies the missing input for M10.

#### B2. Task 2.4's `BoundedKey` specification will not compile
*Task 2.4*

```java
// ConfigBounds.java:82
public record BoundedKey(String key, long min, long max, boolean failFast, String note, long defaultValue) {
```

Six components. Task 2.4 specifies only `key`, bounds `[1, 3650]`, and `failFast=false` — omitting `note` and `defaultValue` entirely. `new BoundedKey("development.roster.dormantAfterDays", 1L, 3650L, false)` is a constructor-arity compile error. The cited precedent at `:149-151` passes all six, including `"0/neg → …"` and `90L`.

**Needs:** Task 2.4 must specify a `note` string and `defaultValue` (`30L`, to match the story's own chosen default).

#### B3. AC4's runtime-configurability requirement is unachievable as specified
*AC4, Task 2.4, Dev Notes "Project Structure Notes"*

Task 2.4 says only *"register it in the `ALL` list"*, and the Dev Notes say *"No Flyway migration in this story."* Together those make AC4 impossible, for three compounding reasons:

1. **The write 404s.** `ConfigService.java:218-224` — `if (existing.isEmpty() && !ConfigBounds.HAS_CODE_DEFAULT.contains(key)) throw new ResourceNotFoundException(...)`. With no seeded row and no `HAS_CODE_DEFAULT` membership, `PUT` for the new key returns 404 and the operator cannot set the value at all. The repo documents this exact trap for this exact situation (`ConfigService.java:210-217`, skillars-deferred-127 AC2), and the cited model key **is** in the set (`ConfigBounds.java:387`).
2. **The read throws forever.** The 3-arg `getBoundedLong` the cited pattern uses calls `getLong(key)` → `getString(key)`, which throws `IllegalStateException` on an absent key (`:71-73`). The story's try/catch swallows it, so the hardcoded default is returned on **every** call regardless of operator intent — until a row exists, which per (1) it cannot.
3. **Every boot logs an ERROR.** `ConfigStartupAssertion.java:92-107` — a key in `ALL` but not in `HAS_CODE_DEFAULT` with no stored value emits `log.error("Platform config '{}' is absent or blank …")` plus a `config.value.misconfigured` metric on every startup in every environment. `failFast=false` means boot isn't blocked, just permanently noisy.

All three have the **same one-line fix**: add the key to `ConfigBounds.HAS_CODE_DEFAULT` as well as `ALL`. That enables the upsert path, which creates the row, which makes subsequent reads succeed.

Separately, **"immediately … on the next call" is still false** even once settable: `ensureFresh()` only refreshes after `app.config.cache-ttl-seconds` (default 300, `ConfigService.java:63`), and `invalidate()` clears only the cache of the JVM that served the write — the class's own javadoc at `:340-352` says so explicitly (*"a load-balanced retry can land on a different node each time"*). On a multi-instance deployment the change propagates in up to 300 s.

**Needs:** Task 2.4 adds `HAS_CODE_DEFAULT` registration; AC4 says "within the config cache TTL" rather than "immediately", or scopes the claim to single-node.

#### B4. AC3's `lastSessionDate` is a computation timestamp, and is absent entirely for whole classes of real completed sessions
*AC3, Task 2.1, transitively AC4/AC5/AC6*

`findLastSessionDate` is `SELECT MAX(calculated_at)`. `calculated_at` is stamped at computation time, not session time:

```java
// SluCalculationService.java:152, 166
Instant now = Instant.now();
…
stat.setCalculatedAt(now);
```

It runs in a `@TransactionalEventListener(AFTER_COMMIT) @Async` handler (`:48-50`), so it trails completion rather than naming the session.

Far more consequentially, an SLU row is written **only** if all of these hold (`SluCalculationService.java:52-114`):

- `event.isPlayerAttended()` — a no-show writes nothing (`:52-55`)
- a `Session` plan exists — *"No session plan for booking {} — SLU skipped (**Quick Complete** or no session builder usage)"* (`:58-62`)
- session status is `SAVED`/`COMPLETED` (`:66-71`), has blocks (`:73-77`), and those blocks have drills (`:111-114`)

`BookingCompletionService.initiateQuickComplete` is a first-class shipped coach flow. **A coach who Quick-Completes sessions, or doesn't use the session builder, has zero SLU rows** — so every player on their roster reports `lastSessionDate = null`, normalizes to max urgency under Task 2.5, and is flagged `dormant` under Task 2.6. The entire roster inverts into "everyone is urgent, everyone is dormant", which is precisely the failure AC6 was written to prevent.

`SluRepository.java:48-50`'s own comment is already narrower than AC3's claim: *"only `SluCalculationService.onBookingCompleted` writes rows here, and only for completed sessions."*

**Needs:** either rename the field to what it is ("last SLU-recorded session for this pair") and state the Quick-Complete blind spot explicitly in the AC, or source the date from `Booking.requestedStartTime` of the latest `COMPLETED` booking instead.

#### B5. AC4 (booking-based) and Task 2.6 (session-based) are different contracts
*AC4 vs Task 2.6*

AC4 defines dormant as *"no **booking** in the active statuses … within a configurable threshold number of days."* Task 2.6 computes `dormant = daysSinceLastSession > dormantAfterDays`, where `daysSinceLastSession` comes from SLU rows. Per B4 these diverge for every completed booking that produced no SLU row — so **the proposed implementation does not satisfy AC4 as written.**

AC4 is also silent on *which* booking date is measured. If `requestedStartTime`, a booking scheduled *next month* counts as "within the threshold" and suppresses dormancy for a player the coach hasn't seen in a year.

**Needs:** AC4 and Task 2.6 must agree on one data source, and AC4 must name the date field.

---

### MEDIUM

#### M1. AC1's status list omits genuinely active relationships, and Task 1.2 couples roster membership to an authorization predicate
*AC1, Task 1.2*

Full enum (`BookingStatus.java:3-22`, 18 values): `REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED, COMPLETED_PENDING_CONFIRMATION, COMPLETED, DECLINED, CANCELLED, CANCELLED_PARENT, CANCELLED_COACH, NO_SHOW_PLAYER, NO_SHOW_COACH, DISPUTED, REFUND_PENDING, REFUNDED`.

AC1 admits four. Correctly excluded: `REQUESTED`/`DECLINED` and the four `CANCELLED*`. Questionably excluded, per `BookingStateMachine.java:30-82` — and because `status` is a single column, a booking in these states no longer matches on its *former* value:

- **`IN_PROGRESS`** (`:54`) and **`PAUSED`** (`:61`) — a session happening right now. A player whose only booking is live is absent from the roster.
- **`COMPLETED_PENDING_CONFIRMATION`** (`:68`) — the window between session end and wrap-up/auto-confirm, hours to days wide (`BOOKING_QUICK_COMPLETE_TIMEOUT_HOURS`). A first-time player drops out and then reappears.
- **`PAYMENT_PENDING`** (`:34`) — the coach already accepted; the relationship exists.
- **`NO_SHOW_PLAYER`/`NO_SHOW_COACH`** — terminal, and arguably the *highest*-attention signal a roster could carry.
- **`DISPUTED`/`REFUND_PENDING`/`REFUNDED`** — reachable from `COMPLETED` (`:73-75`), so a dispute silently removes a previously-rostered player.

The deeper issue: `ACTIVE_RELATIONSHIP_STATUSES` was written to answer an **authorization** question ("may this coach read this player's data"), not a roster-membership question. Task 1.2 hard-couples the two by instruction. Later widening the roster to include `IN_PROGRESS` would silently widen the read-authorization surface on `/exposure`, `/narrative` and `/slu/skill-trends` (which all route through `SluDashboardService.java:43, 98, 128` to that same constant).

**Needs:** AC1 should define its own roster-status list (ideally adding `IN_PROGRESS, PAUSED, COMPLETED_PENDING_CONFIRMATION`), and Task 1.2 should either drop "reuse the *exact same* literal list" or document the auth/product coupling it creates as a deliberate accepted trade.

#### M2. Task 1.1 cites the one query in this repo that was deliberately capped, while dropping both the cap and the ordering
*AC1, Task 1.1*

`BookingRepository.java:127-143` carries a 17-line comment from skillars-deferred-150 AC3 explaining why an unordered, unbounded `SELECT DISTINCT b.playerId` is unsafe — *"WITHOUT it, an unordered SELECT DISTINCT truncated by a bare `limit` returns a Postgres-internal, plan-dependent, call-to-call-unstable subset"* — and `:152-153` adds `ORDER BY b.playerId` + `limit 50`. Task 1.1 cites this query as its shape precedent and silently omits both. HEAD's own predecessor commit `c873fb6c` is titled in part *"unbounded booking query."*

Relatedly, AC1 has **no recency bound at all**, while the story's own title promises *"currently has (or recently had)"*. A single `COMPLETED` booking from five years ago keeps that player on the roster permanently — an ever-growing list.

**Needs:** AC1 needs an explicit recency floor; Task 1.1 should state whether an `ORDER BY`/cap is required, and if not, why this query is exempt from the reasoning recorded 15 lines above its own cited precedent.

#### M3. AC7's factual claim about existing endpoints is false for three of the four it names
*AC7, Dev Notes "Explicitly out of scope"*

AC7 asserts a *"pre-existing gap on `/exposure`/`/narrative`/`/neglected-skills`/`/coach-contributions`"*. At HEAD:

| Endpoint | Relationship check | |
|---|---|---|
| `/exposure` | `SluDashboardService.java:43` | **present** |
| `/slu/skill-trends` | `SluDashboardService.java:98` | **present** (and this is the method Task 2.3 reuses) |
| `/narrative` | `SluDashboardService.java:128` | **present** |
| `/neglected-skills` | `SluTargetService.getNeglectedSkills` `:53-58` — straight to `flagRepository`, no auth call | **genuinely missing** |
| `/slu/coach-contributions` | `SluContributionService` — no `require*` call anywhere in the class | **genuinely missing** |

The claim was inherited verbatim from the brainstorm (`brainstorming-session-…:116`) without re-verification. Because AC10 *forbids* fixing the gap, a dev reading AC7 could build around a hole that doesn't exist, or "helpfully" add a check that is already there.

**Needs:** AC7 should name only `/neglected-skills` and `/slu/coach-contributions`.

#### M4. AC8 drops the one state branch the cited precedent added for this exact failure
*AC8, Task 4.2, Task 6.1, Task 3.2*

Task 3.2's `coachProfileService.getCoachIdByUserId(...)` throws:

```java
// CoachProfileService.java:707-712
return coachProfileRepository.findByUserId(userId)
    .map(CoachProfile::getId)
    .orElseThrow(() -> new ResourceNotFoundException("CoachProfile not found", "coach_profile"));
```

So a `ROLE_COACH` user with no `CoachProfile` row — reachable mid-profile-builder; `routes.js:67` has a `coach/profile-builder` route — gets a bare 404. The cited precedent handles this explicitly, with a comment naming the story that added it:

```html
<!-- CoachReviewsPage.vue:10-12 -->
<!-- skillars-deferred-151 AC1: a coach with no CoachProfile row yet (reachable mid
     profile-builder) gets a plain 404 from GET /api/reviews/coaches/me — render an
     incomplete-profile state instead of a generic error banner. -->
```
…backed by `if (err.response?.status === 404) { incompleteProfile.value = true }` and a dedicated test (`CoachReviewsPageSpec.js:121`).

The story's citation range covers that block, but its prose paraphrase enumerates only *"loading spinner → error banner → empty state → populated list"*; AC8 lists three states; Task 6.1 asks for four. This reintroduces the exact regression skillars-deferred-151 AC1 fixed on the sibling page one commit ago.

**Needs:** AC8 and Tasks 4.2/6.1 must add the incomplete-profile (404) branch.

#### M5. The story cites the wrong `epics.md`
*Source section*

The story says it is *"not sourced from `_bmad-output/planning-artifacts/epics.md` (that file's epics 1–10 are already complete; there is no pre-existing epic entry for this work)."* That file is a different module's document entirely:

```
# javatemplate - File Storage Module Epic Breakdown
inputDocuments:
  - '_bmad-output/planning-artifacts/file-storage-module/prd.md'
```

Its `## Epic List` is Epics **1–5** (Storage Module Foundation → Local Provider, Observability & Production Readiness). `grep -ciE "roster|coach"` over the whole file returns **0**. There are no epics 6–10 in it, and nothing in it concerns coaches.

The project's real epic document is `_bmad-output/planning-artifacts/skillars-epics.md` (`### Epic 1`…`### Epic 10` plus `## Epic 11: Session Pack Payment Consolidation` at `:3357`). The substantive point survives against *that* file — all 57 `skillars-{1..10}-N` story keys are `done` — but the citation as written is wrong, and it is the citation a dev would follow.

#### M6. A coach "active client roster" is already specified **and shipped**
*Source section, Dev Notes "The real architectural gap this story closes"*

`skillars-epics.md` specifies it twice:

- `:228` — *"UX-DR5: Coach Command Center — 3-column desktop layout (sidebar/schedule/revenue); projected weekly revenue, **active client roster**, schedule gaps all visible without navigation."*
- `:1146` (Story 3.5 AC, status `done`) — *"the layout is 3 columns on desktop: sidebar (**active client roster** + quick actions), …"*

And it is really built — `CoachCommandCenterPage.vue:435-445`:

```js
const activeClients = computed(() => {
  const bookings = bookingStore.coachSchedule?.bookings ?? []
  const seen = new Set()
  return bookings
    .filter((b) => { if (seen.has(b.playerId)) return false; seen.add(b.playerId); return true })
    .map((b) => ({ id: b.playerId, name: b.playerName }))
})
```

…rendered under `coach.commandCenterSidebar` = `'Active Clients'` with empty state `'No active clients this week'`.

**Calibration (this is why I downgraded it from the layer's HIGH):** this is a client-side, *week-scoped* distinct-`playerId` name list with no development signals and no attention ranking. Story 12.1 is **not** redundant, and the brainstorm's stronger claim — *"No coach-facing page … surfaces any of it"* (`:115`), meaning the development endpoints — is **true**. What is overstated is the adjacent half of that same sentence, *"there is no 'my players' roster anywhere in the app today"*, which the story inherits as "no pre-existing epic entry for this work".

**Needs:** the story should acknowledge `activeClients` and state whether the Command Center sidebar is superseded by, or coexists with, the new `/coach/players` page. That is a product decision a dev shouldn't have to make mid-task.

#### M7. Dev Notes name the wrong table for the skill-trend signal
*Dev Notes "Multi-coach data boundary"*

The story says trend direction is *"derived from `player_skill_stats` across all coaches."* But:

- `development.player_skill_stats` **has** `coach_id uuid NOT NULL` (`V138:367`)
- `getSkillTrends` doesn't read it — it reads `snapshotRepository.findByPlayerIdFromWeek` (`SluDashboardService.java:106-107`) → `PlayerSluWeeklySnapshot` → `development.player_slu_weekly_snapshot`, whose DDL is five columns with **no coach dimension at all** (`V138:395-401`)

The story's *conclusion* is right — and in fact stronger than claimed, since the trend signal cannot be coach-filtered even in principle. But anyone auditing the multi-coach boundary via the named table will find `coach_id NOT NULL` and reach the opposite conclusion. Note `SluRepository` (over the coach-aware `player_skill_stats`) and `SluWeeklySnapshotRepository` (over the coach-free snapshot table) are two similarly-named repositories over two different tables; Task 2.1 correctly uses the former for `lastSessionDate`.

#### M8. `SkillTrendDirection` was dropped from the response contract without being deferred
*AC3, AC5, AC10*

The brainstorm names it as a roster-row component twice:

- `:152` — *"Roster rows rank by a composite of days-since-last-session, open neglected-skill count, and `SkillTrendDirection`"*
- `:204` — *"with last-session date, open neglected-skill count, and `SkillTrendDirection`."*

AC3 enumerates `playerId`, name, `lastSessionDate`, `openNeglectedSkillCount`, `dormant`, `attentionScore` — no trend field. Task 2.3 consumes trend direction only internally, folded into the score. AC10's deferral list doesn't mention it. So AC5 makes declining trends load-bearing for ordering while giving the client no way to see *why* a row ranked where it did.

**Needs:** either add a declining-count/trend field to `RosterEntryResponse`, or add an explicit deferral line to AC10.

#### M9. The performance note undercounts by ~2× and its cited precedent isn't comparable
*Dev Notes "Performance note", Tasks 2.1–2.3*

Per roster player:

| Call | Queries |
|---|---|
| `findLastSessionDate` (Task 2.1) | 1 |
| `findByPlayerIdAndResolvedAtIsNull` (Task 2.2) | 1 |
| `getSkillTrends` → `requireCoachPlayerRelationshipIfCoach` (`SluDashboardService.java:132-138`): `existsByIdAndParentId` + `coachProfileRepository.findByUserId` (`CoachPlayerAuthorizationService.java:31`) + `existsByCoachIdAndPlayerIdAndStatusIn` (`:44-45`) | 3 |
| `snapshotRepository.findByPlayerIdFromWeek` (`:106-107`) | 1 |

**6 per player** — ≈300 synchronous queries for a 50-player roster, with `findByUserId` for the *same* coach repeated once per player. Task 2.3 calls that inner re-check *"harmless"*; it is half the per-player cost.

The cited precedent is also not like-for-like: `NeglectedSkillDetectionService` is `@Scheduled(cron = "0 0 6 * * MON")` + `@SchedulerLock` with a 30-minute budget, and its own comments (`:25-31`) describe the loop as **"un-chunked"** with a runtime tripwire *"before player growth actually lets ShedLock force-expire the lock mid-run"*. A weekly batch job's loop tolerance says nothing about a request-thread HTTP read path.

**Needs:** state the real per-player count, and (cheaply) authorize calling `SluWeeklySnapshotRepository.findByPlayerIdFromWeek` + `SluTrendClassifier.classify` directly instead of routing through `getSkillTrends` — that halves the cost with no batched native query, keeping the story's own "no premature optimization" rule intact. The roster-cardinality argument stands on its own; the batch-job analogy should be dropped.

#### M10. Task 2.6's "relationship age" has no source in any specified query
*Task 2.6*

*"or last session is null and **the coach-player relationship itself is older than** dormantAfterDays"* — the story never defines a relationship start date, and nothing in Tasks 1–3 loads one (Task 1.1 returns playerIds only). A source exists in the schema (`Booking.createdAt`, `Booking.java:58-59`), so this is underspecified rather than impossible — and the fix is the same widened projection B1 needs.

#### M11. `weeksBack` is never specified, and the clamp lives at a layer the new service bypasses
*Task 2.3, AC5*

Task 2.3 says *"reuse `SluDashboardService.getSkillTrends(playerId, weeksBack)`"* without naming a value. The clamp is at the **resource** layer, not the service: `SkillExposureResource.java:45` `int weeksBack = Math.min(Math.max(weeks, 1), 52);`. The service itself does `now.minusWeeks(weeksBack - 1)` (`:100`), so a new caller passing `0` yields a window starting *next* week.

Worse, `SluTrendClassifier.MIN_WEEKS = 3` (`:23`) and `classify` returns `INSUFFICIENT_DATA` for `n < 3` (`:44-46`) *and* for a zero mean (`:54-56`). Any `weeksBack < 3` makes every skill `INSUFFICIENT_DATA`, which under Task 2.5's `decliningRatio` default collapses AC5's *"more declining skill trends must rank higher"* clause into a no-op. The chosen window is load-bearing for a contract AC, not an implementation detail.

---

### LOW

- **L1 — AC5's dormancy clause is unsatisfiable against AC1 as literally written.** AC5: *"A player with **no booking in the active statuses** within the dormancy window must rank at or near the top."* AC1 admits only players **with** such a booking; read literally the set is empty. Charitably it means "no *recent* booking", but a dev could implement either reading. *AC1, AC5*
- **L2 — Dev Notes over-read AC2 to forbid a config seed row.** AC2 forbids *"no new entity, table, or persisted 'roster'/'relationship status' column."* The Dev Notes convert that into *"No Flyway migration in this story — AC2 explicitly forbids new persisted state."* A single `INSERT INTO platform_config` row is neither a roster nor a relationship-status column, and would be one legitimate fix for B3. The constraint is manufactured.
- **L3 — Task 5.1's "dormancy flips across the configured threshold" test won't work with the cited precedent's shape.** `PlayerTimelineResourceIT` never touches `platform_config` or `ConfigService`. Every IT that actually flips a config value calls `configService.invalidate()` first (`SubscriptionLifecycleIT.java:90,125,384`; `VideoSubscriptionLifecycleListenerIT.java:65,81`; `RadarCompositeCalculationServiceConcurrencyIT.java:410`) — without it `ensureFresh()` holds the stale value for up to 300 s and the test flakes. Task 5.1 should name `invalidate()` and cite a config-flipping IT for that one assertion.
- **L4 — The Prettier gate isn't mentioned.** `project-context.md:70`: *"**Prettier** is mandatory for all .js, .vue, .scss, and .json files."* The story touches 6+ frontend files and names CI as *"this project's sole full-verification authority"*, but the Testing Standards Summary never mentions Prettier — which is a build-blocking gate in this repo.
- **L5 — A GDPR-erased player ranks top of their coach's roster.** `GdprErasureService:1154-1158` deletes SLU rows, weekly snapshots and neglected-skill flags, but `player_profiles` survives as a tombstone (`developmentDataErasedAt`, `PlayerProfile.java:70`) and `booking.bookings` rows are untouched (no FK — see Layer 3 #2). So an erased player stays on the roster with `lastSessionDate = null`, `openNeglectedSkillCount = 0`, and under Task 2.5 is pinned to the top. Same root cause as B1; worth one explicit sentence given the privacy framing.
- **L6 — Bounds behavior at the extremes degenerates AC5's ordering.** The 3-arg `getBoundedLong` **clamps** rather than defaulting (`ConfigService.java:133-146`). Stored `0` → clamped to `1`: every player not seen in 24 h is dormant with recency normalized to `1`, so AC5's "dormant ranks at or near the top" becomes vacuous (everyone ties) and ordering reduces to the neglected + declining terms. Stored `99999` → `3650`: almost nobody is dormant. Worth stating so the bounds test asserts the right thing.
- **L7 — `getRoster` can't be unit-tested without a live coach principal.** Because `requireCoachPlayerRelationshipIfCoach` reads the coach from the SecurityContext, `requireCurrentUserId()`/`getCurrentCoachUserId()` (`SecurityUtil.java:263-273, 293-303`) throw `InsufficientAuthenticationException` outside a request. Task 5.2's pure-function scope correctly dodges this; Tasks 1.3/2.3 should note it so nobody attempts a repository-mocked unit test of `getRoster`.
- **L8 — Task 4.5's "all three locale files must stay in sync" has no mechanical gate.** `src/frontend/src/i18n/index.js` is a 9-line registry; no locale-parity spec or lint exists, and parity is already marginally broken at HEAD (`auth.emailVerifiedPleaseLogin` is en-US-only, 1208 vs 1207 keys). Worth saying parity is reviewer-enforced, and naming the pre-existing gap so a dev doesn't "fix" it as an unrelated drive-by.
- **L9 — Instancio is over-cited.** `project-context.md:61` scopes it to *"DTO and Entity test data"*; Task 5.2 applies it to scalar inputs of a pure scoring function. Defensible only if the method takes a record.
- **L10 — The epic-11 precedent is only partly applied.** `git log -S` → commit `461fbadf` shows the epic-11 precedent landed a 100-line `## Epic 11` section in `skillars-epics.md` alongside the first story and the sprint-status entry. `grep "Epic 12" planning-artifacts/` returns zero hits. The story's literal claim ("tracked the same way in `sprint-status.yaml`") is true — the entry shape matches exactly — but citing epic-11 as *the* precedent while skipping its epics-doc section is incomplete.
- **L11 — Codebase-Grounding attribution is loose.** `NeglectedSkillFlag` and `SluDashboardService` appear in the brainstorm's Priority 1 "Resources needed" line, not in its Codebase Grounding section; and the *"already `ROLE_COACH`-authorized at the API layer"* line is sourced from the doc's security-*gap* bullet (`:116`) and cited as reassurance. See also M3, where that same inherited claim is factually wrong.

---

## 5. What did not survive re-verification

This section exists because a report with uniformly high confidence and no calibration differences is itself evidence that the adversarial pass didn't happen. The following were raised (several by me, in the layer prompts) and then killed or downgraded:

| Raised | Why it died |
|---|---|
| **`UPCOMING` is a derived/UI-only state, so AC1's query cannot match it** — I seeded this suspicion myself, reasoning from HEAD's "booking time gates" commit message | **Refuted.** It is a persisted enum constant (`BookingStatus.java:8`), a DB CHECK value (`V138:246`), a state-machine state with real transitions in and out (`BookingStateMachine.java:45-53`), written by `BookingReminderScheduler.java:101` via `SCHEDULE_UPCOMING`, and queried directly (`BookingRepository.java:92`). AC1 is fine on this point. Had this gone unchecked it would have been the report's biggest false positive. |
| **`findAllById` with an empty id collection produces a malformed `IN ()`** | **Refuted.** Spring Data's `SimpleJpaRepository.findAllById` short-circuits to `emptyList()` on an empty iterable. The repo's empty-`IN` prior art (`BookingService.java:510,564`; `AgePolicyService.java:79`) applies only to explicit `IN :collection` `@Query` methods; Task 1.1's `IN :statuses` binds a hardcoded non-empty list. A zero-roster coach is safe. |
| **`@PreAuthorize` on the service would block the internal loop call** | **Refuted.** No `@PreAuthorize` exists on `SluDashboardService`; it lives on `SkillExposureResource` (`:30,40,50,57`). Internal calls unaffected. |
| **`LazyInitializationException` calling `getSkillTrends` in a loop from a new service** | **Refuted.** `SluDashboardService` is class-level `@Transactional(readOnly = true)` (`:31`) and returns fully-materialized records; no lazy proxy escapes. |
| **`findLastSessionDate` throws `EmptyResultDataAccessException` for a player with no SLU rows** | **Refuted.** `SELECT MAX(...)` with no `GROUP BY` always returns exactly one row (NULL). `TimelineQueryService.java:42-44` already relies on the null return. |
| **AC9's nav-gating assumption is wrong** | **Refuted.** Verified exact: `MainLayout.vue:145` `<template v-if="authStore.isCoach">`, backed by `meta: { requiresAuth, requiresCoach }` and `router/index.js:49,69`. The story's claim is accurate. |
| **A dual-role COACH+PARENT user is a new problem for this page** | **Refuted.** `authStore.isCoach` is single-role (`auth.store.js:16`, hydrated from the one-role `skp` cookie); such a user is already locked out of *every* coach route identically. Pre-existing and uniform, not introduced here. |
| **AC8/AC9 force touching something AC10 forbids** | **Refuted.** AC8 is a list page only; every AC10 item is avoidable. Task 2.3 *consumes* the existing relationship check without modifying the endpoints AC10 protects. |
| **MapStruct omission is a defect** (`project-context.md:43,80` mandates it for Entity/DTO mappings) | **Downgraded out.** A roster row is a computed projection assembled from four different sources, not an entity→DTO mapping. The rule doesn't bite. Not reported. |
| **The "Active Clients" overlap is HIGH severity** | **Downgraded to M6.** The existing widget is a week-scoped name list with no development signals; Story 12.1 isn't redundant, and the brainstorm's core claim (no coach page surfaces development data) is true. What's wrong is narrower: one overstated half-sentence, inherited into the Source section. |
| **"Epics 1–10 are already complete" is false because the epic roll-up keys read `in-progress`** | **Dropped as noise.** No `skillars-epic-N` roll-up is ever marked `done` in this project (1 through 11 all read `in-progress`); all 57 numbered *story* keys are `done`. The real defect is the wrong file path (M5), not the completion claim. |
| **"Every other recent story bundles `deferred-work.md` items" is inaccurate** | **Reported as INFO only.** True for 10 of 12 (146 is CI perf, 147 is a small feature). A rhetorical framing in a Dev Note, changing nothing a dev builds. |
| **A transaction/atomicity defect exists somewhere in the roster loop** | **Refuted by tracing the boundary.** All three relevant services are `@Transactional(readOnly = true)`; a read-only `CoachRosterService` joins one transaction for the whole loop. Zero writes, nothing to roll back. No claim in this story rests on unverified atomicity reasoning. |

---

## 6. Recommendation

**Do not start implementation.** Five findings (B1–B5) make the story either non-compiling or self-contradictory at the AC level, and three of them (B1, B4, B5) are about *what the feature is*, not how to build it — they need the owner's product call, not a dev's judgment.

Per-finding confidence, honestly calibrated:

| Confidence | Findings | Basis |
|---|---|---|
| **Certain** — mechanically checkable, re-read twice | B2 (record arity, `ConfigBounds.java:82`), B3 (`ConfigService.java:220-224` + `:71-73` + `ConfigStartupAssertion:92-107`), M3 (five endpoints individually grepped), M5 (file contents + `grep -ci` = 0), M7 (two DDL blocks compared), M1 (enum + state machine read in full) | Single-source facts, no interpretation |
| **High** — textual contradiction plus traced code | B1, B4, B5, M2, M4, M6, M9, M10, M11 | Each rests on both story text and quoted source; B4's blast radius (which coaches have zero SLU rows) depends on real-world Quick-Complete usage I can't measure from the repo |
| **Moderate** — real but interpretation-dependent | M8, L1, L2, L6, L8 | Turn on reading intent (AC5's wording, what AC2 "forbids", whether a trend field belongs in the contract) |
| **Low stakes** — process/accuracy notes | L3, L4, L5, L7, L9, L10, L11 | Won't block a dev who reads carefully; worth fixing cheaply now |

**What was independently re-checked, and what wasn't.** Every finding above was re-read from source by the orchestrator after the layers returned, against the files as they stand at HEAD `66932d90` — that pass killed or downgraded the 13 items in §5, including one false positive I had seeded myself in the layer prompts. Three things were **not** independently verified and are reported on the layers' evidence alone: the full-document reads of the brainstorming session and `project-context.md` (spot-checked at the specific quoted lines only, not end to end), the i18n key-parity counts (produced by a script I did not re-run), and the claim that no locale-parity spec exists (a negative result from grep, which cannot prove absence). None of the blockers depend on any of those.

**Suggested smallest set of story edits to reach implementable:** resolve B1's recency semantics and widen Task 1.1's projection (which also closes M10); add `note`/`defaultValue` to Task 2.4's key and register it in `HAS_CODE_DEFAULT` (closes B2, B3 and L2's pressure); reconcile AC3/AC4/Task 2.6 on one date source and state the Quick-Complete blind spot (B4, B5); correct AC7 to the two genuinely unguarded endpoints (M3); add the 404/incomplete-profile branch to AC8 (M4); fix the `epics.md` citation and add a supersede-or-coexist sentence about `activeClients` (M5, M6). The rest can be applied as text corrections without changing what gets built.
