# Story Review — skillars-deferred-150

| | |
| :--- | :--- |
| **Story** | `skillars-deferred-150-seed-script-ownership-gap-jwt-role-precedence-unbounded-query-and-subscription-review-hardening` |
| **Story status** | `ready-for-dev` |
| **Audit date** | 2026-10-08 |
| **Verified against HEAD** | `162608ff` — *Fix review-eligibility pre-check, coach-review role gate, and coach photo signing (#262)* |
| **Method** | 4 parallel read-only verification layers (citations / ledger+precedent / mechanistic / corner-case), followed by a mandatory adversarial re-verification pass in which every finding's evidence was re-read from source by the reviewer, actively trying to refute it. |

> **Every citation below was re-checked against the real working tree at `162608ff`, not against any SHA, line number, or "Master is at…" claim written inside the story.** The story happens to name the same HEAD, and that HEAD is correct — but it was confirmed independently (`git rev-parse HEAD`) before any claim was read, and each citation was re-opened rather than trusted.

**Headline:** the story is unusually well-researched — its SQL fix is provably correct and complete, its transaction reasoning is sound, and its hardest exclusion call ("clear-on-login is obsolete, not open") is right. But **AC2's stated defect mechanism is backwards, and Design B's own code snippet would introduce a live regression**; AC3's central premise was retired by the very story it cites; and AC5's rename instruction is self-cancelling. 13 raised findings were killed during re-verification, including one where a layer wrongly called a correct citation fabricated.

---

## 1. Citation verification

### Exact matches (re-opened and confirmed)

| Citation | Evidence |
| :--- | :--- |
| `seed-accounts.sql:168-170` | `FROM main.player_profiles pp` / `JOIN main."user" u ON u.id = pp.parent_id` / `WHERE u.email = :'owner_email'` — verbatim |
| `seed-accounts.sql:215` | `JOIN main."user" u ON u.id = pp.parent_id` in the verification `SELECT` — verbatim |
| `V138__baseline_schema.sql:911` | `CONSTRAINT chk_pp_owner CHECK ((((parent_id IS NOT NULL) AND (user_id IS NULL)) OR ((parent_id IS NULL) AND (user_id IS NOT NULL))))` — verbatim |
| `V138__baseline_schema.sql:896-912` | `CREATE TABLE main.player_profiles` block — correct range |
| `SubscriptionService.java:901-908` | `assertPlayerOwnership` with the quoted two-disjunct body — verbatim |
| `SubscriptionService.java:114/319/383/458` | `getPlayerSubscription` / `subscribePlayer` / `changePlayerTier` / `cancelPlayerSubscription` declarations — all four exact |
| `JwtManagerImpl.java:113-124` | `setSkillarsProfileCookie` through `.orElse(SkillarsRole.ANONYMOUS)` — exact (full method runs to `:127`) |
| `BookingRepository.java:127-140` | the `@Query` + method signature — exact, and **no `ORDER BY`** confirmed |
| `ReviewSubmissionService.java:152-154` | `Instant sinceAfter = …` + `checkEligibility(review.getCoachId(), authorId, sinceAfter);` — exact |
| `ReviewSubmissionService.java:176` | `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);` — exact |
| `ReviewSubmissionService.java:185` | `ReviewModerationStatus lockedStatus = locked.getModerationStatus();` — exact |
| `ReviewSubmissionService.java:192` | `int lockedCooldownDays = configService.getBoundedInt(` — exact |
| `SubscriptionResource.java:100/110/118` | `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` ×3 — exact; base path composes to the full URLs the story states; **no class-level `@PreAuthorize`** |
| `.github/actions/docker-build/action.yml:26-39` | `git.properties` / `git.dirty` comments — within range |
| `grep -rl "git.properties\|git.dirty" .github/workflows/` | **zero matches** — reproduced exactly |
| `.github/scripts/assert-context-count.sh` | exists |
| `pr-build.yml` job `docker-image` | exists (`:118`), passes `load: 'true'` (`:133`) and `commit-sha: ${{ github.sha }}` (`:135`) |
| `SubscriptionResourceIT.java` | 13 `@Test` methods; **zero** self-registered-PLAYER rejection tests; `getMyCoachSubscription_parentCaller_returns403` (`:107`) is indeed the only role-mismatch case |
| `AuthServiceTest.login_success_doesNotClearPreviouslySetInvalidationFlag` | exists (`:149`) |
| `SkillarsRole` | exactly 5 constants — nothing collapses silently under a ternary chain |
| `SecurityConstants.HAS_PARENT_ROLE` | `"hasRole('ROLE_PARENT')"` |

### Drifted (corrected locations)

The story asserts at line 290 that *"every citation in this story was independently re-verified against HEAD `162608ff` … not copied from `deferred-work.md`'s own text without checking."* Eight citations contradict that.

| Cited | Actual | Note |
| :--- | :--- | :--- |
| `User.java:199` | **`:225`** | `:199` is Javadoc prose. **Identical to the ledger's own stale citation** (`deferred-work.md:3473-3482`) — copied, not re-checked |
| `Dockerfile:24` | **`:20`** | `:24` is a comment. Also identical to the ledger's (`:3807`), and `:24` was **never** correct — `git log -- Dockerfile` shows the flag at `:20` in the commit that introduced it |
| `ReviewSubmissionService.java:302-303` | **`:299`** | one line, not two; cited twice (Finding 1 and References) |
| `ReviewSubmissionService.java:202-204` | **`:203-205`** | the cited range **excludes `setAuthorLastEditedAt(now)` at `:205`** — the very call the claim is about |
| `ReviewSubmissionService.java:177-183` | **`:178-184`** | `:177` is blank |
| `ReviewSubmissionService.java:199+` | **`:200+`** | `:199` is blank |
| `MessagingResource.java:231-248` | **`:231-250`** | truncates the terminal `throw` |
| `SubscriptionResource.java:100/110/118` | mappings at **`:99/109/117`** | cited lines are the `@PreAuthorize`, one below each mapping |

All of AC4's load-bearing citations are exact. No citation was `CANNOT_LOCATE`; every cited artifact exists.

---

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
| :--- | :--- | :--- |
| deferred-142 §, `findFirst()` bullet | ledger `:3467`,`:3473-3482`; source comments `JwtManagerImpl:100-112`; `git log/blame` | **CONFIRMED** — ledger cites `:105`, story's `:113-124` is correct at HEAD |
| deferred-145 §, LIMIT bullet + `[CLOSED]` i18n | ledger `:3743`,`:3745-3767`; source; git | **CONFIRMED** (both halves) — but see Finding 4 |
| deferred-145 r2 §, `sinceAfter` bullet | ledger `:3775-3789`; source `:146-151`; git | **CONFIRMED** — the ledger's prescribed fix is **verbatim** Design D |
| deferred-149 §, all four bullets | ledger `:3797-3811` | **CONFIRMED** — all four present and quoted accurately |
| deferred-146 §, `[deferred-19]` drift | ledger `:3791-3795` | **CONFIRMED** — 14.0→15.8→16.0 figures exact |
| deferred-149 AC1 widened `assertPlayerOwnership` | deferred-149 file AC1/Completion Notes; `SubscriptionService:901-908`; `git log -S existsByIdAndUserId` → `c1ee810b` | **CONFIRMED** |
| deferred-149 AC7 Dockerfile/git.properties mechanism | deferred-149 AC7; real `Dockerfile:20,39-47` | **CONFIRMED** mechanism, **wrong line** (`:24`→`:20`) |
| deferred-145 Dev Notes sized it cheap | deferred-145 `:224` | **CONFIRMED verbatim** |
| "clear-on-login is obsolete, not open" | ledger `:3810`; `AuthService:100,129-132`; `AuthServiceTest:149`; `gh pr view 261`; `AuthServiceSessionInvalidationIT` | **CONFIRMED** — premise genuinely gone; the recommended ledger deletion is the right call |
| `docker create`/`docker cp`/`unzip -p` hand-run precedent | deferred-149 Debug Log `:476` | **CONFIRMED** — exact three commands recorded |
| V145 / V157 / V158 descriptions | all three migration files | **CONFIRMED** |
| "script first added in `6d15a19a` (2026-09-02)" | `git log --follow`, `--name-status` | **CONFIRMED** — see §6/K1 |
| "20 migrations V139–V158, none altered the 5 tables" | all 20 files grepped for DDL | **CONFIRMED as stated**; the *table set* is incomplete (§5.19) |
| `MessagingResource.resolveRole` is the sole precedent | source grep; ledger `:3386` | **WRONG** — §5.18 |
| "everything before deferred-142 is already closed" | ledger header `:7`; all 36 `## Last audit` entries | **WRONG** — §5.7 |
| `createSelfOwnedPlayerProfile` shipped in `15d1ca2b` | `git show 15d1ca2b`; `git log --reverse -S` | **WRONG** — §5.21 |

---

## 3. Mechanistic claims

| Claim | Verdict |
| :--- | :--- |
| `Authority.hashCode()` is `name.hashCode()` | **CONFIRMED** (`Authority.java:72-74`, null-guarded; `getAuthority()` returns bare `name` — the `ROLE_` prefix is in the data) |
| Order traces to `User.authorities = new HashSet<>()`, so the pick is hash-derived | **FALSE** — §5.1. The field is at `:225` and is an unordered `HashSet`, but `setSkillarsProfileCookie` never reads it |
| `updateReview` is the sole writer of `authorLastEditedAt` | **FALSE** — §5.11 (`submitReview:103` also writes it; `V157:41-43` backfills). Finding 4's *conclusion* still holds |
| Both timestamps come from one `now()`; `lastModifiedAt` is not framework-managed | **CONFIRMED** — `:203-205` one local `Instant now`; `CoachReview` has **no** `@EntityListeners`/`@LastModifiedDate`/`@UpdateTimestamp`, does not extend `AbstractAuditingEntity`, and no `orm.xml` declares a default listener |
| Any edit invalidating `sinceAfter` trips the cooldown guard first | **CONFIRMED** — and the suspected zero-cooldown hole does **not** exist (§6/K3) |
| Transaction boundary / rollback safety of the AC4 insertion | **CONFIRMED** — §6/K5 |
| `evaluateEligibility` short-circuits on the owned player | **CONFIRMED** in substance (`for`/`break` at `:287-303`, not a stream `findFirst()`); the row set is fully materialized by the repository call regardless |
| Adding a `LIMIT` is behaviour-neutral | **FALSE** — §5.3 |
| JPQL `LIMIT` is feasible here | **CONFIRMED** viable (Hibernate 6.6.53, spring-data-jpa 3.5.13 `HqlParser.RULE_limitClause`, no `hibernate.jpa.compliance.*` flags set, no ArchUnit/convention test constrains query shapes) — but §5.17 |
| A self-registered player holds `ROLE_PLAYER`, not `ROLE_PARENT` | **CONFIRMED** (`PlayerRegistrationService:98-99,114,116`; disjoint from `ParentRegistrationService:99-101`) |
| Only the controller annotation stops a self-registered PLAYER | **CONFIRMED** — `/api/**` admits all six authorities (`AppEndpoints:59,64-67`); no class-level `@PreAuthorize`; no service-layer gate beyond `assertPlayerOwnership`, which passes by design |
| `-Dmaven.gitcommitid.skip=true` is the real skip property | **CONFIRMED** against the plugin JAR's own `plugin.xml` for the configured v10.0.1 (`skipViaCommandLine` ← `${maven.gitcommitid.skip}`) |
| `git.dirty` derived, `.properties` format, default arg is `local` | **CONFIRMED** (`Dockerfile:39-45`; `action.yml:41`); a `local` build fails **both** AC6 assertions, so the negative control is genuinely constructible |
| Design A's `OR`-join is correct and complete | **CONFIRMED** — §6/K11, K12 |

---

## 4. Corner cases, false assumptions & missed flows

### BLOCKING

**4.1 — AC2's defect mechanism is backwards, and Design B's snippet is a live regression.**

The story's premise (`:64`): *"`.findFirst()` on this stream is ordering-sensitive, and the order traces back to `User.authorities = new HashSet<>()` … so the pick is hash-derived, not business-meaningful."* The User Story restates it as *"hash-ordered luck."*

Traced end to end from source:

- `setSkillarsProfileCookie` reads the **JWT `ROLES` claim**, not `User.authorities` — `getAuthoritiesSilently` (`JwtManagerImpl.java:141-149`) does `mapper.readValue(roles, new TypeReference<List<SimpleGrantedAuthority>>() {})`, a `List`, preserving JSON-array order.
- `TokenCreatorImpl.java:49` — `claims.put(ROLES, toJson(principal.getAuthorities(), …))` — is the **only** writer of that claim.
- `Principal extends org.springframework.security.core.userdetails.User` and its ctor calls `super(…, builder.authorities)`.
- Spring Security's `User` constructor passes authorities through `sortAuthorities(...)`, which returns a `SortedSet` (verified by `javap` against **`spring-security-core-6.5.8`**, same 6.5.x line as this project's resolved `6.5.11`): `private static java.util.SortedSet<GrantedAuthority> sortAuthorities(...)`, with `User$AuthorityComparator.compare` doing `getAuthority().compareTo(getAuthority())`.

So the order reaching `.findFirst()` is **deterministic and alphabetical**, not hash-derived. For the four mapped roles that is `ROLE_ADMIN < ROLE_COACH < ROLE_PARENT < ROLE_PLAYER` — i.e. **today's accidental behaviour is already `ADMIN > COACH > PARENT > PLAYER`.**

Two consequences, both material:

1. **Design B's snippet is a regression, not a hardening.** It orders `COACH > PARENT > PLAYER > ADMIN`. An ADMIN who also holds `ROLE_PLAYER` resolves to `ADMIN` today and would resolve to `PLAYER` after the change. The frontend gates on a single scalar: `auth.store.js:19` `const isAdmin = computed(() => role.value === 'ADMIN')`; `router/index.js:84` `if (requiresOneOfRoles && isAuthenticated && !rolesMeta.includes(authStore.role))` → redirect; `routes.js:345` `roles: ['ADMIN']`; `MainLayout.vue:268` `v-if="authStore.isAdmin"`. That user would be redirected off the admin routes and lose the admin nav after a token refresh.
2. **AC2's new test would pass before the production change.** AC2 makes the test the proof of determinism and Dev Notes call it *"the actual deliverable"* — but a dual-role claim set already resolves deterministically, so the test cannot distinguish pre- from post-change. It needs to assert the *specific intended* order and be paired with a note that it pins intent over an upstream-library accident.

The change is still worth making — relying on Spring Security's internal sort order is fragile and undocumented — but the AC must be rewritten: the real defect is "deterministic only by accident of a third-party sort," not "hash-ordered luck," and the precedence must be `ADMIN`-first to preserve current behaviour.

**4.2 — Design B's code snippet contradicts its own prose two lines later.** Snippet (`:178-182`) places `ADMIN` **last**; prose (`:185`) says *"placing `ADMIN` above all three is a reasonable default since an admin-held authority should not be masked by an incidental PARENT/PLAYER/COACH grant."* The snippet is what a dev copies, and per 4.1 it is the wrong one. The story then defers the question — *"confirm this doesn't conflict with any admin-specific frontend gating before finalizing"* — but that answer is a single grep and determines the fix; leaving it open in a `ready-for-dev` AC is not acceptable.

### HIGH

**4.3 — AC3: a bare `LIMIT` with no `ORDER BY` is a silent false-denial regression.** `BookingRepository.java:127-135` has no `ORDER BY` (contrast `:149` `ORDER BY b.requestedStartTime ASC` in the next method down). A truncated `SELECT DISTINCT` with no ordering returns a non-deterministic subset. If the author's one owned `playerId` falls outside the cap, `evaluateEligibility` leaves `eligible = false` and the caller is wrongly denied `NO_QUALIFYING_SESSION`.

Aggravating: the consumer loop discards rows before testing ownership — `ReviewSubmissionService.java:288-291` `findById(...).orElse(null)` → `continue; // orphaned playerId`. And `booking.bookings` has **no FK on `player_id`** (the only FKs on that table are `bookings_batch_id_fkey` and `bookings_session_pack_purchase_id_fkey`, `V138:4056`/`:4063`), so orphaned ids are a real, already-documented shape that would consume cap slots ahead of the owned row.

This directly contradicts AC3's *"No existing test's outcome changes"* and Dev Notes' *"insurance … not a real constraint"* — both true only when the cap never fires, false in exactly the case it does. The ledger said so and the story's paraphrase dropped it: `deferred-work.md:3762` is titled **"`findQualifyingCompletedBookings` has no `LIMIT` _or ordering_"**. Minimum correct fix: pair the cap with `ORDER BY b.playerId`.

**4.4 — AC3's premise was retired by the very story it cites.** Finding 3 says a `LIMIT` is *"still worth it … per the ledger's own recommendation."* But deferred-145's own round-2 review reclassified that item and closed it out — `skillars-deferred-145-….md:509`:

> **R14 — add `DISTINCT` to the qualifying-bookings query** … **reclassified from round-1 Defer.** … **A `LIMIT` is no longer needed if `DISTINCT` lands.**

`DISTINCT` landed (`BookingRepository.java:128`, `SELECT DISTINCT b.playerId`). The ledger bullet was simply never pruned, so the story cites it in good faith — but AC3 rests on a recommendation its own source explicitly superseded, and the story does not disclose the conflict. AC3 needs a fresh justification (4.5 supplies one) or should be dropped.

**4.5 — AC3 misses the new caller that HEAD `162608ff` itself added.** The commit the story claims to verify against introduced a public GET that calls the unbounded query with the **widest possible window**:

```java
// ReviewSubmissionService.java:256-261
public ReviewEligibilityDto checkWriteEligibility(UUID coachId, Long authorId) {
    if (!coachProfileRepository.existsById(coachId)) {
        throw new ResourceNotFoundException("Coach", coachId.toString());
    }
    return evaluateEligibility(coachId, authorId, Instant.EPOCH);
}
```

reached from `@GetMapping("/coaches/{coachId}/eligibility")` (`ReviewResource.java:79-85`) and wired into `CoachPublicProfilePage.vue` by the same commit. `sinceAfter = Instant.EPOCH` means no lower bound at all, and the ledger notes the ineligible path does not short-circuit (`deferred-work.md:3769-3770`: *"The `break` short-circuits only the eligible case — both rejection paths scan the full set"*) — and on page load, ineligible is the common case.

Finding 3 says the query is *"confirmed unchanged since the deferred-145 review flagged it"* — true of the query text, false of its callers. Before `#262` it ran only on submit/update; it now runs on every coach-profile render. This both strengthens the case for a bound and makes 4.3's truncation hazard materially more reachable. The story cites `evaluateEligibility` (`:44`), so HEAD's refactor was read — its new caller was not.

**4.6 — AC5's rename instruction is factually wrong and self-cancelling.** The story (`:210`, `:235`): *"rename … at all five occurrences in `SubscriptionService.java` (`:114`, `:319`, `:383`, `:458`, `:901`) — **confirmed these are the only occurrences**"*, gated by *"if (and only if) the rename is confirmed to touch no more than those five sites."*

Real count — **12 occurrences on 12 lines**: `114, 115, 319, 321, 338, 383, 384, 458, 459, 901, 902, 903`. The five cited are the parameter **declarations** only; the seven usages must change or it will not compile. Read literally, a dev greps, counts 12, and skips the rename — AC5's second half cancels itself. The gate should read "no file other than `SubscriptionService.java`."

**4.7 — missed flow at the occurrence the story omitted.** `SubscriptionService.java:338` is not a cosmetic hit:

```java
stripeCustomerRepository.findById(parentUserId)
    .orElseThrow(() -> new PaymentGatewayException("payment.noPaymentMethod"));
```

`subscribePlayer` resolves the **Stripe customer by the caller's own user id**. This is exactly the scenario Finding 5 raises — *"the day a future story grants self-registered adult players parent-equivalent billing authority … every one of these three methods would silently open"* — and it would not open cleanly: a self-registered player would need a `StripeCustomer` row under their own user id. That is a real, unmentioned dependency for the future-widening flow the AC exists to protect, and it belongs in AC5's notes.

**4.8 — the ledger-exhaustiveness claim is false and self-refuting.** The story (`:17`): *"every section from skillars-deferred-140 through -149 was read in full; everything before skillars-deferred-142's section is confirmed already closed by the file's own chain of 'Last audit' sweep entries."*

- The ledger's own header states the opposite convention — `deferred-work.md:7`: **"This is a list of open work only.** Items are deleted outright once they are implemented." By that rule, every untagged bullet still present is open, and hundreds remain before the deferred-140 sections.
- **No sweep entry exists after 2026-09-24** (last `## Last audit` heading is at `:3271`), so no sweep covers deferred-140…-149 at all.
- The one full-file sweep says the reverse of what the story attributes to it (`:42`): *"almost everything untagged and still standing is either a deliberate, well-reasoned 'not a bug' note **or a genuinely open item nobody has closed yet.**"* Its net effect was 4 bullets restored, 2 deleted.

Many surviving bullets are deliberate "not a bug" / "currently unreachable" notes rather than queued work, so this is an overstatement of closure rather than 167 missed fixes — but as written the claim will mislead anyone who reads it as "the tail is now fully mined."

**4.9 — two still-open tail bullets silently omitted and mischaracterized.** Neither appears in the story's "Explicitly NOT picked up" list:

- `deferred-work.md:3453` (deferred-141) — *"`/dashboard` is `DEFAULT_ROUTE` for any unmapped or null role, which now has no nav link at all"*, with a live **"When picked up:"** clause. *(Directly adjacent to AC2's subject matter.)*
- `deferred-work.md:3616` (deferred-143) — *"`markUsedByTokenHash`/`markAllUsedByUserId` calls are unguarded against transient DB failures"*, also with a live **"When picked up:"** clause.

Both are untagged and open, yet Dev Notes (`:284`) says *"**Do NOT delete** … anything in the deferred-140/deferred-141/deferred-143 sections (human-only or already closed)"* — neither is human-only and neither is closed. Aggravating: deferred-149's own Completion Notes (`:504`) already enumerate both as deliberately left open, so a correct inventory existed in the immediately preceding story file.

**4.10 — AC6/Design F step 2 is unexecutable as written.** Design F (`:216`) says to `docker cp` *"`BOOT-INF/classes/git.properties`"* out of the container. That path does not exist on the image filesystem. `Dockerfile:40-47` injects the file **into the jar** (`jar uf "${JAR_FILE}" -C /tmp/gitprops BOOT-INF/classes/git.properties`), and `Dockerfile:76` copies **only the jar** into the runtime stage (`COPY --from=builder /app/target/skillars-*.jar app.jar`). The required sequence is `docker cp <ctr>:/app/app.jar .` then `unzip -p app.jar BOOT-INF/classes/git.properties`. Line `:221` does mention `unzip -p`, so intent is recoverable — but the numbered steps, and AC6's own wording (`:237`, "extracts … from the built image"), are wrong.

**4.11 — AC6: `ci.yml` builds an image but never loads it locally, so the assertion cannot run there.** Dev Notes (`:289`) asks the dev to *"check whether `ci.yml` independently builds a Docker image at all."* It does — job `build-and-push` (`ci.yml:88`, step `:238-248`), same composite action, same `commit-sha: ${{ github.sha }}` — but with `push: 'true'` and **no `load:`**, and `action.yml:9-12` defaults `load` to `'false'`. The image never enters the local daemon, so a `docker create` step there fails with "No such image." `pr-build.yml` is fine (`load: 'true'`, `:133`). AC6 should scope the step to `pr-build.yml` explicitly, or specify pulling the pushed tag back.

### MEDIUM

**4.12 — AC2 omits the second, authoritative writer of the same cookie.** `JwtManagerImpl:126` is not the only producer of `skp`'s role — `AuthService.java:132` and `:281` both do:

```java
String role = user.getSkillarsRole() != null ? user.getSkillarsRole().name() : "ADMIN";
new SkillarsProfileCookie(String.valueOf(user.getId()), role).writeTo(res);
```

`User.skillarsRole` is a single scalar column, and `JwtManagerImpl:102-109` already documents the split-brain (*"refreshLoginToken only has the JWT's own claims, so derive the role from the existing ROLES … Fallback is `SkillarsRole.ANONYMOUS`, not AuthService's `"ADMIN"` convention"*). For the dual-role user AC2 targets, login writes the DB-authoritative role while refresh would write a precedence-derived one — so a user with `skillarsRole = PARENT` who also holds `ROLE_COACH` would get `PARENT` at login and silently flip to `COACH` on refresh under Design B's order. AC2 treats this as a one-file ordering tweak; the real goal is agreement with `user.skillarsRole`.

**4.13 — AC3 and AC4 are not independent, though the story's Scope Level calls all six "independent."** AC4 adds a **second** consumer of `findQualifyingCompletedBookings`, executed while holding a `PESSIMISTIC_WRITE` row lock (inserted after `:176`). AC3's cap then governs that in-lock query too, so a non-deterministically truncated result would decide whether a lock-holding transaction aborts. AC3's correctness question (4.3) must be settled before AC4 adds the consumer. *(Deadlock risk specifically is low — both added queries are plain `SELECT`s with no `FOR UPDATE`, so no lock-ordering inversion exists; the cost is lock-hold time.)*

**4.14 — AC3's "no existing test's outcome changes" is falsifiable under one of the two mechanisms the story offers.** Design C (`:189`) suggests *"`Pageable`, or a literal cap via `setMaxResults`-equivalent."* The `Pageable` route changes the method signature and breaks this 4-arg stub at **compile** time:

```java
// ReviewSubmissionServiceTest.java:83
when(bookingRepository.findQualifyingCompletedBookings(eq(COACH_ID), eq(AUTHOR_ID), any(), any()))
    .thenReturn(Collections.emptyList());
```

The story's Project Structure Notes (`:295-296`) list `ReviewSubmissionService.java` only as an AC4 change and omit `ReviewSubmissionServiceTest.java` entirely. AC3 should prescribe the HQL-literal mechanism (no signature change) or list both files.

**4.15 — AC4's test design has no viable recipe as written, and the story names neither existing precedent.** Design D (`:201`) suggests *"a raw SQL update racing the method."* A fire-and-forget `jdbcTemplate.update` cannot work: it either commits **before** `updateReview`'s unlocked pre-read (`:125`), in which case the *pre-lock* check fails and nothing about the re-check is proven, or **after** the method finishes, in which case `refresh()` never sees it. The write must land between the pre-read and the lock release. Two precedents exist and the story cites neither:

- **Write shape** — `ReviewUpdateIT.java:302-307` already performs exactly the decoupled `author_last_edited_at`-only UPDATE, correctly wrapped in `transactionTemplate.execute`.
- **Timing choreography** — `ReviewSubmissionIT.java:703-726` runs an external thread that takes `SELECT … FOR UPDATE`, mutates, holds via latch, then commits, while `updateReview` waits on `findByIdForUpdateNoWait`'s bounded retry.

Relatedly, AC4 gives no warning about this project's bare-`jdbcTemplate`-never-commits pitfall. Residual risk is low (both candidate files already wrap writes correctly — e.g. `ReviewSubmissionServiceConcurrencyIT:74,293,390,481`), but the guidance is absent.

**4.16 — AC1's verification is manual-only, and the script is non-idempotent in a way that compounds per verification pass.** Nothing automated exercises this file (`grep -rn "seed-accounts"` outside `_bmad-output/` hits only `manual-testing.md:405,412,578` and the script's own header) — no test, no CI job. So AC1's *"Manually verified (or verified via a new/existing integration test against the real schema)"* resolves to manual-only. Meanwhile step 4 is the one step with no `ON CONFLICT`, and the file flags it itself (`:138-144`):

> *"IDEMPOTENCY WARNING: The table is append-only (`trg_ledger_no_update` / `trg_ledger_no_delete` … reject UPDATE and DELETE) … Re-running this file ADDS another credit row rather than replacing the previous one. If you mis-seed, you can only add more credit to offset the mistake, never delete or correct it."*

Each manual verification pass therefore adds another irreversible credit row. Worth stating in the AC rather than discovering mid-verification.

**4.17 — AC1's fixture is not a one-liner, and the usage shape it needs is undocumented.** `seed-accounts.sql:10` — *"PREREQUISITES — this script only *upgrades* accounts, it does not create them."* A self-registered adult player must come from a full UI registration plus the manual email-token round trip (`manual-testing.md:339-350`, 18+ only). It also requires `-v owner_email=<the player's own email>`, a shape neither the script header nor `manual-testing.md:408-413` documents — both worked examples pass a parent's email. Task 1's *"Verify against a real local Postgres with a self-registered-player fixture"* understates this.

Also doc-level and missed: the file's **header** makes the same false promise as the step-5 comment — `:16-17` *"At least one player profile created (parent-created shadow account, **or an adult player's self-owned profile**)"* — so it needs the same correction. AC1 names only the step-5 comment block.

**4.18 — the role-precedence precedent is not sole, and the two copies disagree.** Design B (`:185`): *"`COACH > PARENT > PLAYER` is the established precedent from `MessagingResource`."* There is a second implementation, in the very module this story touches:

```java
// ReviewResource.java:162-166
private String resolveRole(Authentication auth) {
    if (...ROLE_COACH...) return "COACH";
    if (...ROLE_PARENT...) return "PARENT";
    return "PLAYER";
}
```

The ledger itself references it (`deferred-work.md:3386`, *"`ReviewResource.resolveRole`'s role-precedence"*). The two diverge on the terminal case — `MessagingResource:247-249` **throws** `NOT_A_PARTY`; `ReviewResource:165` **defaults to PLAYER** — and **neither handles `ADMIN`**, which is the root of 4.2: Design B invents the ADMIN rule while claiming to mirror an established one.

**4.19 — Finding 1's "5 tables the script touches" omits the two tables the broken join is actually on.** The audited set was `coach_stripe_accounts`, `marketplace.coach_subscriptions`, `payment.coach_subscriptions`, `parent_credit_ledger`, `player_subscriptions`. The script also touches `main."user"`, `main.player_profiles`, and `marketplace.coach_profiles` — and two of those **were** altered inside V139–V158: `V151__player_profiles_development_data_erased_at.sql:31` (`ALTER TABLE main.player_profiles ADD COLUMN IF NOT EXISTS …`) and `main."user"` (4 migrations, incl. V158). Both are additive nullable `ADD COLUMN IF NOT EXISTS` and neither touches `chk_pp_owner`, `parent_id`, or `user_id`, so **Finding 1's conclusion survives intact** — the defect is in the stated exhaustiveness of a deliberately enumerated audit.

### LOW

**4.20 — Design C's stated guide does not exist.** *"check the simplest mechanism consistent with this repository's other `@Query` methods"* — `BookingRepository` has **15** `@Query` methods and **not one** uses `Pageable`, `limit`, `setMaxResults`, `Top`/`First`, `Slice`, or `Page<>`. Repo-wide, all 76 `LIMIT`s are in `nativeQuery = true` queries. There is no non-native precedent; the dev will be introducing the first one.

**4.21 — wrong commit attributed for `createSelfOwnedPlayerProfile`.** Story (`:41`): *"`ShadowAccountService.createSelfOwnedPlayerProfile` (the `user_id` writer) shipped in commit `15d1ca2b` (2026-06-12)."* `git show 15d1ca2b` contains **zero** occurrences of that method — the commit added `ShadowAccountService` with only `createPlayerProfile`, `listPlayerProfiles`, `linkAdditionalParent`, and introduced neither `user_id` nor `chk_pp_owner` on `player_profiles`. Both first appeared in **`1f24a5e5` (2026-07-04)** (`git log --reverse -S'createSelfOwnedPlayerProfile'`, `-S'chk_pp_owner'`). The **conclusion survives** — 2026-07-04 still predates the script's 2026-09-02 creation, so "the dual-ownership columns predate this script entirely" holds. Citation defect, not a reasoning defect.

**4.22 — AC6's step name is wrong.** Story (`:214`): *"between 'Build Docker image' and 'Scan image for vulnerabilities'."* The actual step is `- name: Build Docker image (no push)` (`pr-build.yml:129`). The second name matches exactly (`:137`).

**4.23 — AC2's characterization of the existing suite is wrong.** AC2 (`:229`): *"All existing `JwtManagerImplTest` cases (single-role) still pass unchanged."* Two existing cases are already multi-authority — `JwtManagerImplTest.java:163` and `:192`, both `Set.of(new SimpleGrantedAuthority(ROLE_USER), new SimpleGrantedAuthority("ROLE_ADMIN"))`. Harmless in outcome (`generateToken`/`createLoginToken` never call `setSkillarsProfileCookie` — its only caller is `refreshLoginToken` at `:94`), but it is the only description of the existing suite the dev is given. Note also that the two existing `skp` tests (`:626`, `:645`) both use single-element sets, so multi-authority ordering is genuinely untested today.

**4.24 — "every authority-assignment call site uses `setAuthorities(Set.of(oneAuthority))`" is inaccurate.** `UserRegistrationService.java:43,67-68` builds a mutable `new HashSet<>()` and `add`s one element — and `.orElse(null)` at `:42` can add a `null`. The **conclusion survives**: `ROLE_USER` maps to no `SkillarsRole`, and all five grant sites are create-only with no append/upgrade path (confirmed: no `INSERT INTO main.user_authority` in any migration or the seed script). Worth noting that test fixtures already seed multi-authority users (`src/test/resources/sql/initTestData.sql:51-59`, `userData.sql:14-15`).

**4.25 — Design E's fixture advice misdirects; AC5 is easier than implied.** Design E (`:205`) suggests reusing *"`SubscriptionServiceOwnershipIT`'s existing self-registered-player fixture pattern."* That class does exist with that fixture (`:67-74`) — but it `extends AbstractIntegrationTest` against real Postgres, whereas the target is a mock slice: `SubscriptionResourceIT.java:37-39` is `@WebMvcTest(SubscriptionResource.class)` with `@MockitoBean SubscriptionService`. The three new tests need no fixture at all — just `@WithMockUser(roles = "PLAYER")`, since `@PreAuthorize` rejects before the service is entered. The advice points the dev at Testcontainers work that is both unnecessary and impossible in this slice.

---

## 5. What did not survive re-verification

Listed so the report's own reliability can be judged. Each was raised by a layer (or by me) and killed or downgraded on a second, skeptical read.

| | Raised | Why it was killed |
| :--- | :--- | :--- |
| **K1** | *"The story's `6d15a19a` (2026-09-02) attribution for the seed script's creation is **factually false** — `a0a427ae` (2026-09-18) added it."* | **False positive — the story is correct.** The layer ran `git log --diff-filter=A` on the **current path only**. With `--follow`: `6d15a19a` (2026-09-02) added the file as `requirements/deployment/local/seed-local-test-accounts.sql`; `a0a427ae` renamed it (`R083`) to `docs/deployment/local/seed-accounts.sql`. Confirmed by `git log --follow --name-status`. **This is precisely the failure mode this review format exists to catch** — a correct citation called fabricated after checking one source. I also verified the broken join was present at birth (`git show 6d15a19a:requirements/…:150,191` both read `ON u.id = pp.parent_id`), so *"pre-existing authorship mistake, not drift"* is right too. |
| **K2** | *"The `15d1ca2b` / `createSelfOwnedPlayerProfile` claim is **CONFIRMED**."* | **Overruled — the claim is wrong (see 4.21).** This layer verified only that the SHA exists with the stated date and title; it never checked whether the commit *contains* the method. Direct check: 0 occurrences in `git show 15d1ca2b`; first appearance `1f24a5e5`. Two layers reached opposite verdicts on this one item; the direct read settles it against the story. |
| **K3** | *A zero-day cooldown would let the Finding 4 coupling break today.* | **Killed.** `ConfigBounds.java:205-207` pins min `1L` (*"out-of-range (incl. 0) falls back to the coded default (30), never to an unbounded cooldown"*), and `ConfigService.getBoundedLong:115-121` returns the default for anything below min. A stored `0` becomes `30`. The coupling holds. |
| **K4** | *Design D's placement is wrong — the re-check sits after the cooldown guard, so it can never fire.* | **Killed.** Backwards: if the two timestamp writes were decoupled (the scenario AC4 defends against), the cooldown guard would **not** trip and the eligibility re-check **would** fire. Placement after the guard is correct, and matches the existing most-actionable-first ordering rationale. |
| **K5** | *Adding `checkEligibility` post-lock risks a premature flush or a dirty partial write.* | **Killed by reading the transaction boundary.** `updateReview` has no method-level annotation and inherits class-level `@Transactional` (`:40`) — `REQUIRED` *(the `REQUIRES_NEW` at `:68` belongs to `submitReview`, the method immediately above — the exact adjacent-method confusion to avoid)*. At Design D's insertion point the context is clean (`refresh` at `:176` cleaned `locked`; no mutation yet), so the two JPQL reads cause no premature flush. `checkEligibility` is pure-read (config cache, two `SELECT`s, a `findById`) with no writes, events, or metrics. `OperationNotAllowedException` is unchecked → rollback → Postgres releases the row lock at transaction end. Clean. |
| **K6** | *AC4's fix will break existing tests.* | **Killed.** Traced all of them. `ReviewSubmissionIT:687` omits `author_last_edited_at` from its INSERT, so `sinceAfter = createdAt` pre- and post-refresh and the external holder touches only `moderation_epoch`. The `ReviewSubmissionServiceTest` cases throw before reaching the lock. Also: the recomputed `maturedBefore` (`:281`) moves **forward** with a later clock read, making `b.updatedAt <= :maturedBefore` strictly *more* permissive while `sinceAfter` is unchanged — so the second clock read cannot newly fail a legitimate caller. AC4's "no test outcome changes" is correct. |
| **K7** | *AC6's "verify empirically with a real docker build" conflicts with AC7 / the no-local-`mvn verify` convention.* | **Killed.** `Dockerfile:20` runs `mvn package -Dmaven.test.skip=true`, not `mvn verify`, with tests skipped. No convention violation. The *cost* is understated (two full Quasar/node builds, the `local` case uncacheable on the SHA-dependent layer) but that is not a conflict. |
| **K8** | *Design E cites `SubscriptionServiceOwnershipIT`, which doesn't exist (the real class is `PlayerSubscriptionOwnershipIT`).* | **Killed — both exist.** `SubscriptionServiceOwnershipIT` (`payment/service/`, real Postgres, with the self-registered fixture at `:67-74`) and `PlayerSubscriptionOwnershipIT` (`payment/api/`, `@WebMvcTest` with mocks). The story's named class is right. The advice is still misdirected for a different reason — see 4.25. |
| **K9** | *AC4's proposed test will silently no-op on this project's Hikari `auto-commit=false` pitfall.* | **Downgraded to a note inside 4.15.** Both candidate files already wrap every setup write in `transactionTemplate.execute` (`ReviewSubmissionServiceConcurrencyIT:74,218,293,312,390,409,481`; `ReviewUpdateIT:302`), so a dev working in them will copy the right pattern. Real gap is the missing *timing* recipe, not the commit pitfall. |
| **K10** | *A literal `LIMIT` in a non-native `@Query` won't parse, making Design C's primary suggestion non-viable.* | **Killed.** Hibernate `6.6.53.Final` HQL supports `limit`; `spring-data-jpa-3.5.13`'s `HqlParser` exposes `RULE_limitClause`/`RULE_offsetClause`; `application.yaml` sets no `hibernate.jpa.compliance.*` flag; no ArchUnit/convention test inspects query shapes. Viable as written. |
| **K11** | *The proposed `OR` join could match multiple rows and trip `ON CONFLICT DO UPDATE` ("cannot affect row a second time") or duplicate subscriptions.* | **Killed.** `chk_pp_owner` (`V138:911`) forces exactly one of `parent_id`/`user_id` non-null, and `u.id = NULL` is never true — so each `pp` row joins on exactly one disjunct. `user_email_key` (`V138:2713`) makes the `WHERE` yield at most one `u`; `uq_pp_user_id` (`V138:3748`) caps the self-owned profile at one. Result is still one row per distinct `pp.id`, and `uq_pps_player_id` (`V138:3052-3055`) backs the conflict target. A parent who is also a self-registered player correctly gets self **plus** children, all distinct. The fix is correct. |
| **K12** | *The claim "no other statement in the file needs to change" is probably incomplete.* | **Killed — exhaustively verified correct.** `pp.parent_id` appears exactly twice (`:169`, `:215`), both named. `player_profiles` appears 3× (the third is the comment at `:154`). Steps 1-3 resolve via `cp.user_id`; step 4 keys `parent_credit_ledger.parent_id` off `main."user".id` directly with no `player_profiles` join — correct for a self-registered player too, since both booking-creation paths set `parentId` to the acting user's id (`DisputeRepository.java:27-28`). The other two verification SELECTs are unaffected. Only the prose at `:154-156` and `:16-17` additionally needs fixing (4.17). |
| **K13** | *`b.playerId = :authorId` in the eligibility query compares a `PlayerProfile` PK against a `User` id (cross-ID-space), inflating the result set that AC3 proposes to truncate.* | **Downgraded out of the findings — my own over-read.** The ID-space mismatch is real (`playerProfileRepository.findById(booking.getPlayerId())` at `:288` confirms `playerId` is a `PlayerProfile` PK, while `authorId` is a `User` id), but it is pre-existing, untouched by this story, and **not systematically exploitable**: `b.parentId = :authorId` is the arm that actually fires for both account shapes, making the second arm redundant rather than wrong. Any inflation requires a coincidental numeric collision between two id spaces — hypothetical risk with no concrete trigger, which does not meet the bar. |
| **K14** | *"167 still-open bullets precede deferred-142" (exact figure).* | **Direction kept, figure attributed not asserted.** The conclusion is certain and independently confirmed three ways (ledger header `:7`; no sweep after 2026-09-24; the 2026-09-15 sweep's own wording). My own cruder count differs from the layer's parse, and many survivors are deliberate "not a bug"/"currently unreachable" notes rather than queued work. 4.8 therefore states the overstatement without asserting a precise count. |

---

## 6. Recommendation

**Verdict: do not implement AC2 as written; AC3, AC5 and AC6 need edits first. AC1 and AC4 are sound.** Per-AC confidence below — deliberately uneven, because the evidence is uneven.

| AC | Assessment | Confidence |
| :--- | :--- | :--- |
| **AC1** seed script | **Ready.** The fix is provably correct and provably complete (K11, K12). Fix the header prose too (4.17); state that verification is manual-only and that step 4 double-credits per pass (4.16). | **High** — SQL re-read in full; all three constraints that make the fix safe verified by line |
| **AC2** JWT precedence | **Rewrite required.** The stated mechanism is backwards, the snippet would regress a dual-role admin, the snippet contradicts the prose, and the new test would pass pre-change (4.1, 4.2). Also address the second cookie writer (4.12) and fix the "(single-role)" claim (4.23). | **High** on the mechanism — traced `Principal` → Spring `sortAuthorities` → `TokenCreatorImpl:49` → `getAuthoritiesSilently`, with `javap` against the 6.5.x jar this project resolves, plus first-hand reads of the frontend gates. **Medium** on ADMIN-first being the right order (it preserves today's behaviour; no dual-role user exists to confirm product intent) |
| **AC3** bounded query | **Edit required.** Add `ORDER BY`, or push ownership into the query (4.3); re-justify on 4.5 rather than the retired R14 recommendation (4.4); prescribe the HQL-literal mechanism and list the test file (4.14). Sequence after/with AC4 (4.13). | **High** on the missing `ORDER BY`, the retired premise (direct quote), and the new `Instant.EPOCH` caller (verified first-hand). **Medium** on reachability — needs an implausible distinct-player count today |
| **AC4** review re-check | **Ready, with a test recipe added.** The design is correct, matches the ledger's prescription verbatim, is transaction-safe, and breaks no existing test (K4, K5, K6). Point the dev at the two existing precedents (4.15) and fix the "sole writer" wording (§3). | **High** — every citation exact; transaction boundary, rollback and lock behaviour traced, not assumed |
| **AC5** subscription tests | **Edit required.** Restate the rename gate per-file (4.6); document the `:338` Stripe-customer dependency (4.7); correct the fixture advice — the tests are easier than implied (4.25). | **High** — occurrence count and `:338` re-verified by direct grep; 403 confirmed by an existing same-file denial test |
| **AC6** CI provenance | **Edit required.** Fix the extraction sequence (4.10), scope to `pr-build.yml` or handle `ci.yml`'s missing `load` (4.11), fix the step name (4.22). | **High** on both blockers — `Dockerfile:76` and `action.yml:9-12` read directly. **Medium** on effort — two local Docker builds is real cost, though not a convention breach (K7) |
| **Ledger hygiene** | **Correct the claims.** Drop the exhaustiveness assertion (4.8); add the two omitted open bullets or move them to the exclusions list, and fix the Dev Note that calls them closed (4.9). The "clear-on-login is obsolete" call is **right** and should be kept. | **High** — ledger header, sweep chain and both bullets re-read |

**What was and was not independently re-checked.** Every finding above was re-opened from source by me after the layers reported, and 14 raised items were killed or downgraded in that pass (§5). Two layers disagreed on the `15d1ca2b` attribution and one layer produced a false "fabricated citation" verdict (K1) — both resolved by direct reads, which is why §5 exists. Not independently re-run: the exact count of open pre-deferred-140 ledger bullets (K14, direction confirmed, figure attributed), and nothing was executed — no test, build, or `docker build` was run, so every "test passes / breaks" statement here is a read of the code, not an observed result. Claims resting on third-party internals (Spring Security's sort) were verified by `javap` against the resolved artifact rather than from memory.
