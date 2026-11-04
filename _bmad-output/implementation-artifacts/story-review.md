# Story review: skillars-deferred-168-builder-packs-become-the-purchasable-tier

- **Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-168-builder-packs-become-the-purchasable-tier.md` (status `ready-for-dev`)
- **Audit date:** 2026-11-04
- **Real HEAD:** `a95109b6` "docs(ledger): Pitchside findings — blank booking slot labels, pack holders default to per-session, generic decline error, registration enumeration, completeness-gate scope (#429)", which equals `origin/master`. The worktree has three staged, uncommitted files: the story, `sprint-status.yaml` and `deferred-work.md`.
- **What citations were checked against:** this HEAD, not any SHA the story itself names. Items on `feature/pitchside` were read from `origin/feature/pitchside`.
- **Method:** four parallel read-only verification layers (citations, ledger and precedents, mechanistic claims, corner cases). Then I re-read the deciding lines of every High and Medium finding myself, trying to refute each one (Step 4b).

## 1. Citation verification (Layer 1, 25 items)

19 MATCH, 6 DRIFTED, 0 CANNOT_LOCATE.

| Citation in story | Verdict | Evidence or corrected location |
|---|---|---|
| `CoachProfileService.java:267` saveStep3 | MATCH | `:267 public ProfileBuilderStepResponse saveStep3(...)` |
| `:290-301` delete, flush, re-insert | DRIFTED (minor) | `:291 deleteByCoachId`, `:292 flush`, `:301 saveAll`; line 290 is a comment |
| `:285` `req.sessionPacks() != null`, "pack block :285-302" | DRIFTED | The check is at **`:287`** and the block is **`:287-302`**; `:285` is `coachPricingRepository.save(pricing)` |
| `:403` getOwnProfile packs | MATCH | `sessionPackRepository.findByCoachId(...)` |
| `:623` getPublicProfile packs | MATCH | Preceded by `:622 // (3) session packs` |
| `EditCoachPricingDialog.vue:261` | DRIFTED (minor) | The call opens at **`:260`** `await saveProfileBuilderStep(3, {` |
| `SessionPackPaymentService.java:296` createTier | MATCH | |
| `:131-135` purchasePack bookable-coach gate | MATCH | `:135 BookingService.requireBookableCoachStatus(...)` |
| `ProfileBuilderStep3Request` bounds and `@Size(max = 5)` | MATCH | `:23`, `:26-28` (but see M1: the nested bounds are not enforced) |
| `V138:3910` idx_spt_one_active_per_coach | MATCH | |
| `CreateSessionPackTierRequest` constants and annotations | MATCH | `:22`, `:24`, `:26`, `:27` |
| Labels: varchar(100) nullable vs varchar(200) NOT NULL; `uq_session_pack` | MATCH | V138 `:1633`, `:2888`, `:1904` |
| `CoachMarketplaceResource.java:78` | MATCH | |
| `CoachPublicProfilePage.vue:611` / `:622` | MATCH | |
| `SessionPackPurchasePage.vue:59` | MATCH | |
| `provisioner.ts:260` (pitchside) extra tier POST | DRIFTED | The POST is at **`:281`**. `:260` is in the step-3 PUT, which also sends `:262 sessionPacks: pack ? [pack] : []` (see C9) |
| `onboarding.spec.ts` ONB-06 `test.fail` | MATCH | `:109`, `:150` |
| `docs/dev-docs/marketplace/index.html:126`, `:192` | MATCH | |
| `provisioning-checklist.md:160` | MATCH | |
| `migration-conventions.md` rule 2 and `drop-prepared-in` | MATCH, with a correction | `:58`, `:64`, `:70`. The rule lives in `src/test/java/com/softropic/skillars/db/MigrationLint.java:190-197` (it also requires no live `src/main` reference); `MigrationConventionLintTest` only runs it against fixtures |
| `CoachPublicProfileQueryCountIT` four round trips | MATCH | `:58 EXPECTED_QUERY_COUNT = 4`; javadoc `:40-45` lists session packs as one of the queries |
| `fk_spp_tier` | MATCH | V138 `:4518` |
| `SessionPackPricingDisplay.vue` renders a list | MATCH | `:13 v-for` |
| Builder and dialog allow a repeatable pack list | MATCH | No frontend cap; only the backend `@Size(max = 5)` |
| Ledger ONB-06 section, and its `:287` citation | MATCH | `deferred-work.md:3344`. **The ledger's `:287` is CORRECT** (the pack check line). The story's Dev Note calling it drift (and saying `:267`) is wrong (see C12) |

## 2. Ledger and precedent attribution (Layer 2, 12 items)

| Claim | Sources checked | Verdict |
|---|---|---|
| ONB-06 section added by #421 as `8e7636a2` | ledger, `git log -S`, `git show` | CONFIRMED |
| Story 2.1 (`4dd4b013`) created `marketplace.session_packs` | story file AC4 `:19`, commit (V26) | CONFIRMED |
| Story 7.2 (`e9486e11`) created tiers without referencing session_packs; index from a review patch | story file, commit (V62), `git log -S` | CONFIRMED |
| deferred-103: race → index → ApiAdvice 409 | story file AC1 `:89`, `SessionPackPaymentService:324-329`, `ApiAdvice:164-166` and `:193-195`, `SessionPackPaymentServiceIT:72` | CONFIRMED, but the IT does not prove a 409 and only races the first-tier case (see M3) |
| deferred-151 replace UI | story file, `CoachSessionPackTiersPage.vue:194-205`, #265 | CONFIRMED (it also added "Withdraw offer", which matters for C2) |
| "V181" protects old purchases' locked `pricePerSession` / amount charged | V181 header, `createPurchase :422` | PARTLY: V181 adds `amount_charged`; the locked `pricePerSession` is Story 7.2 (V62). V181 backfilled old rows from the then-current tier price |
| PR #428 holds V225; expect V226+ | all 138 `origin/*` refs, `gh pr` | CONFIRMED (master tops out at V224; only #428 has V225) |
| Port/adapter precedent; marketplace has no payment import | source grep, test grep | CONFIRMED. There is no module-edge test and no ArchUnit; `EmailTransportArchitectureTest` / `InfrastructureLayeringAuditTest` use a source scan |
| "Known parallel-merge hotspots" (ledger, sprint-status, checklist) | repo docs, git log, private memory | PARTLY: only partly in repo docs. Memory lists the checklist and V-numbers, not the ledger or sprint-status. Harmless as advice |
| "17-context cache budget" | `assert-context-count.sh:179`, `pr-build.yml:127`, `IntegrationTestConventionTest:109` | CONFIRMED |
| Business docs say "up to five packs" | grep of `docs/business-docs`, `docs/dev-docs` | **WRONG**: they already say one active pack. Real targets: `docs/business-docs/coach/index.html:79`, `docs/dev-docs/marketplace/index.html:126`, `docs/dev-docs/database/index.html:72` |
| The three one-active-rule precedents are real | combination of the above | CONFIRMED |

## 3. Mechanistic claims (Layer 3, 12 items)

| # | Claim | Evidence | Verdict |
|---|---|---|---|
| 1 | `saveStep3` is `@Transactional` REQUIRED; a port call joins it | `CoachProfileService:266-267` (method-level, no propagation); resource `:131-136` is not transactional | TRUE |
| 2 | `createTier` deactivates with saveAndFlush, inserts, never deletes, throws `payment.invalidPackTier` | `:295-330`; the insert is plain `save` | TRUE. Also: a null `totalPrice` NPEs in the guard (500), and a null label only fails at the column |
| 3 | A tier rejection rolls back the pricing write and returns "the same 400, existing validation envelope" | rollback: inner REQUIRED marks rollback-only. Envelope: `ApiAdvice:132-136` gives `errorKey: generic.requestError`, `message: payment.invalidPackTier`; bean validation gives `validation.invalidData` + field errors (`:505-561`) | Rollback TRUE. **"Same envelope" FALSE** (see M2) |
| 4 | Concurrent saves → index → 409 `payment.tierRaceConflict` | UUID id ⇒ the INSERT flushes at commit; JpaTransactionManager translates it to DataIntegrityViolationException, which `ApiAdvice:204-226` maps | TRUE only when no active tier exists yet |
| 5 | The index decides a change race | `SessionPackTier:49 @Version`; the loser's `saveAndFlush` of the old row blocks on the row lock, then fails the version check, giving ObjectOptimisticLockingFailureException → `ApiAdvice:703-709` 409 `generic.conflict` | **PARTLY** (see M3). I reasoned this through but did not execute it |
| 6 | purchasePack refuses a non-bookable coach (DRAFT) before charging | `:126-135`; `BookingService:587-596` allows only ACTIVE / REDUCED / PENDING_REVIEW | TRUE |
| 7 | Query count unchanged; "verify, do not assume" | the tier derived query is one statement; but the IT seeds and asserts against `marketplace.session_packs` (`:152-153`, `:199-205`) | Count TRUE. **AC9 as worded ("still passes, no budget change") FALSE**: the IT must be rewritten (M4) |
| 8 | `getOwnProfile` readOnly; a port read is safe | `:395` | TRUE |
| 9 | `SessionPackDto` contract unchanged for the frontend | frontend reads `sessionCount`, `totalPrice`, `label` only | Shape TRUE; behaviour changes (C3, C4) |
| 10 | Bean validation enforces the pack fields | `ProfileBuilderStep3Request:23` has no `@Valid` on the list or its elements; compare `ProfileBuilderStep4Request:19 List<@NotNull @Valid ...>` | **FALSE**: the most important finding (M1) |
| 11 | Only the wizard and the dialog send step 3; no raw-SQL readers | `profileBuilder.store.js:72`, `EditCoachPricingDialog.vue:260`; raw SQL only in V138, the entity, the query-count IT | TRUE (plus the Pitchside provisioner, C9) |
| 12 | Builder completeness / publish do not depend on packs | `validateAllStepsComplete :755-771`, `publishProfile :482-542` | TRUE (safe) |

## 4. Corner cases, false assumptions, missed flows (survived Step 4b)

### Must fix in the story before dev

- **M1 (High, AC5 / Design 6). Nested pack validation is off today.**
  - `ProfileBuilderStep3Request.java:23` is `@Size(max = 5) List<SessionPackRequest>` with no `@Valid`. None of `@Positive`, `@DecimalMin`, `@Size(max=100)` or `@NoContactDetails` runs over HTTP. A pack label carrying contact details is accepted and shown publicly today.
  - Any bound the story adds would be equally inert.
  - `ProfileBuilderRequestValidationTest:56-61` validates the inner record directly, which hides this.
  - Fix: `List<@NotNull @Valid SessionPackRequest>` (the step-4 precedent and its comment), tests through the outer request or HTTP, and a pre-backfill check of existing labels for contact details.
  - Re-verified: I read the record myself.
- **M2 (Medium, AC5). Two different 400s.**
  - A `payment.invalidPackTier` rejection renders as `generic.requestError` with the raw reason as its message. Neither key has a translation, so the user sees the literal key.
  - It is not the validation envelope AC5 promises.
  - Fix: the adapter translates it into a marketplace field error on `sessionPacks` with an i18n entry, or AC5 accepts the different envelope.
  - Note: `MarketplaceException` maps to 422, not 400.
  - Re-verified: `ApiAdvice:132-136`.
- **M3 (Medium, AC6). The concurrency expectation is wrong.**
  - When a tier already exists, `@Version` decides the race (409 `generic.conflict`), not the index (409 `payment.tierRaceConflict`). The index decides only the first-tier race.
  - Serialised saves both succeed, creating two successive rows, so "the loser gets a 409" is not guaranteed.
  - The deferred-103 IT tolerates a commit-time `TransactionException`, which is not mapped (500), and never asserts a 409.
  - Fix: restate AC6 as: exactly one active tier remains; any failure is a 409, never a 500; the loser's pricing write rolls back. Test both cases through MockMvc.
  - Re-verified: `SessionPackTier:49`, `ApiAdvice:703-709`, IT `:92-101`.
- **M4 (Medium, AC9 / T4). `CoachPublicProfileQueryCountIT` must be rewritten.**
  - It seeds 2 then 4 `session_packs` rows and asserts parity against that table, so it fails as soon as reads move.
  - Fix: seed one active tier plus inactive history rows, assert size 1, keep `EXPECTED_QUERY_COUNT = 4`. Keep the adapter read on JPA: a JdbcTemplate read would escape Hibernate statistics and pass falsely.
  - Re-verified: IT `:152-153`, `:199-205`.

### Should fix in the story

- **C1 (High, Design 6 / AC4). Label bounds conflict.**
  - The tier page allows 200-character labels; the builder and dialog cap at 100 (once M1 makes that real).
  - The dialog always re-sends the pack from the profile (`EditCoachPricingDialog.vue:196-200`, `:263`), so a 101–200-character tier label makes every later pricing save fail, even a price-only change.
  - Fix: one maximum on both paths (200 matches the column).
- **C2 (Medium, Design 10). The backfill resurrects withdrawn offers.**
  - "No active tier" includes coaches who pressed "Withdraw offer" (`deactivateTier`, `:337`).
  - Fix:
    - Backfill only coaches with no tier rows at all.
    - Skip erased coaches (`coach_profiles.recap_data_erased_at`, V166).
    - Pick the lowest-count valid row (`round(total/count, 2) > 0`, count ≤ 100, price ≤ 50000), because legacy rows were never validated (M1).
    - Use `NULLIF(trim(label), '')` before applying the default.
- **C3 (Medium, Design 5). The English default label overrides the localized fallback.**
  - `SessionPackPricingDisplay.vue:47-48` shows translated "N sessions" when `label` is null. Storing "Session pack" replaces that in every locale.
  - Fix: the read side maps the default constant back to null. The write round trip stays stable.
- **C4 (Medium, Design 9 / G). Nested errors are invisible in the UI.**
  - Field names like `sessionPacks[0].totalPrice` don't match the dialog's `hasFieldError('sessionPacks')`, and the dialog hides its banner for validation errors. The builder shows only a generic banner.
  - Fix: add the tier page's client-side checks to both pack forms (integer count 1–100; price 0.01–50000 with at most 2 decimals; per-session floor), and map `sessionPacks[...]` errors to the pack slot.
- **C5 (Medium, Design 4 / A). Stale or empty forms silently change the offer.**
  - `ProfileBuilderStep3.vue:117` initialises `sessionPacks: []`, and the builder rail allows going back (`CoachProfileBuilderPage.vue:136 goToStep`). Re-submitting step 3 sends `[]`, which under Design 4 now withdraws a tier the coach created on the tier page.
  - The dialog replaces the offer without the tier page's confirmation, and a stale second tab can resurrect a withdrawn offer.
  - Fix: prefill step 3 from `getOwnProfile`, or send `sessionPacks: null` when the pack fields were untouched. Document the dialog's replace semantics.
- **C6 (Low–Medium). Code and tests the story does not list.**
  - `CoachProfileSelfEditIT:209` and `:238-263` (resubmission and duplicate-count tests, `marketplace.duplicateSessionPackCount`).
  - The `uq_session_pack` mapping in `ApiAdvice:167-172` and its `ApiAdviceTest` case, which become dead.
  - The dialog's duplicate-count guard (`:233-252`).
  - `CoachProfileServiceTest` uses `@InjectMocks`, so the new port needs a `@Mock`.
  - The stale "V62:58" comments in `SessionPackPaymentService:326` and the IT javadoc.
- **C7 (Low, AC8). There is no ArchUnit.** Write the module-edge assertion as a source scan, after `InfrastructureLayeringAuditTest` / `EmailTransportArchitectureTest`.
- **C8 (Low, Design 6). A fractional session count is truncated.** `sessionCount` is a primitive `int`, and Jackson's `ACCEPT_FLOAT_AS_INT` defaults on (no override found in `src/main`), so 2.5 becomes 2. Add an integer check on the frontend (C4) and consider a boxed `Integer` with `@NotNull`.
- **C9 (Low, AC11 hand-off). Pitchside provisioner detail.**
  - The step-3 PUT already sends the pack (`provisioner.ts:262`). After this story it creates the tier, and the extra POST at `:281` (not `:260`) churns a second row.
  - The hand-off should delete the `:281` call.
- **C10 (Low, AC7 wording).** Attribute the locked `pricePerSession` to Story 7.2 (V62), and `amount_charged` to V181.
- **C11 (Low, AC12 wording).** Replace "business-docs say up to five packs" with the three real targets in section 2.
- **C12 (Low, Dev Notes). False drift claim.** The note "ledger says `:287`; it is `:267`" is wrong; the ledger's `:287` is the pack check. Also correct `:285` to `:287` in Design 4.

## 5. What did not survive re-verification

- **Circular-reference risk from the adapter calling `SessionPackPaymentService`** (Layer 4 #12). Speculative: no path back to `CoachProfileService` was found, and `allow-circular-references: false` would fail context start loudly, not silently. Downgraded to "confirm the context starts". This is not a finding.
- **"The story's 'expect V226+' may be wrong"**. Killed: all remote refs checked, and only #428 carries V225.
- **"The 17-context budget is threatened"**. Killed: the only web slice mocks the service; no full-context IT adds a mock set.
- **"GDPR erasure gap from moving labels to tiers"**. Killed: neither pack table is touched by `GdprErasureService` today, so there is no new gap. A pre-existing non-erasure of pack labels is out of scope.
- **"Hotspot list is fabricated"**. Downgraded: it is partly from private memory rather than the repo, but it is accurate advice, so no story change is needed.

## 6. Recommendation

The story's direction (Option A, port/adapter seam, set-the-offer semantics, deferred DROP) holds. Its precedents are real, and most citations are exact. It should not go to dev until these are edited in:

1. **M1, nested `@Valid`.** HIGH confidence. I read the record and the step-4 precedent myself. Without it, every validation AC is inert.
2. **M3 and M4, AC6 and AC9 rewording plus test changes.** HIGH confidence on M4 (IT lines re-read). MEDIUM-HIGH on M3: the `@Version` mechanism and the 409 mapping were re-read, but the lock-then-version-failure sequence was reasoned through, not executed.
3. **M2, the error envelope.** HIGH confidence (handler re-read). The translation choice is a design decision for the owner or dev.
4. **C1, C2, C5, which change behaviour coaches would notice.**
   - C1: HIGH confidence.
   - C2: HIGH on the withdraw case; the erased-coach exclusion is advisory.
   - C5: HIGH that step 3 starts empty; MEDIUM on how often coaches revisit step 3.
5. **C3, C4, C6–C12, wording and test-list corrections.** MEDIUM to HIGH. C8's Jackson default was not checked against the live ObjectMapper config beyond a grep.

**Not independently re-checked by me in Step 4b:**
- the Pitchside line numbers (Layer 1 only);
- the docs targets in C11 (Layer 2 only);
- `ReportGenerationService:193`, the other `getPublicProfile` caller (one extra query, no behaviour change; Layer 4 only).
