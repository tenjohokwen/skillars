# Story skillars-deferred-168: A pack entered in the profile builder becomes the coach's purchasable tier

Status: review

<!-- CREATED 2026-11-04 (/bmad-create-story equivalent, written by hand from an investigation).
     Owner-requested single-item story: the ledger bullet "A pack added in the coach profile builder is
     advertised to parents but can't be bought" (deferred-work.md, "Deferred from: Pitchside UI tests,
     spec ONB-06 (2026-11-03)", merged in #421 as 8e7636a2). Owner decision 2026-11-04: OPTION A -
     payment.session_pack_tiers is the single source of truth, one pack per coach. Options B (several
     active tiers), C (sync two tables) and D (stop advertising builder packs) were considered and
     rejected - see "Options considered".
     REVIEWED 2026-11-04 (/mto-story-review against a95109b6, report in story-review.md): 61 claims checked,
     16 findings survived re-verification, all applied here. Main changes: nested pack validation is OFF
     today (no @Valid on the list), so Design 6 now turns it on (M1); the tier layer's 400 is not the
     validation envelope, so step 3 pre-checks everything createTier checks (M2); AC6 rewritten because
     @Version, not the index, decides a race when a tier exists (M3); AC9 now rewrites the query-count IT
     instead of claiming it passes unchanged (M4); label max unified at 200 (C1); the backfill skips
     withdrawn offers and erased coaches (C2); the default label reads back as null (C3); client-side
     checks and nested-error display (C4); an untouched step-3 pack sends null, not [] (C5); the missed
     tests/code list (C6); citation corrections (C9-C12). -->

## Story

As a coach who sets up a session pack while building my profile,
I want that pack to be the offer parents actually see and buy,
so that the pack price on my public profile is the price charged, the "Buy sessions" button always leads
to something purchasable, and my "My Session Pack Offer" page shows what I entered.

## Ledger mapping (so future passes do not re-pick this)

| Ledger item | This story |
|---|---|
| `deferred-work.md` "Deferred from: Pitchside UI tests, spec ONB-06 (2026-11-03)" - the single bullet "A pack added in the coach profile builder is advertised to parents but can't be bought" | All ACs |

Tag the bullet `**[PICKED UP skillars-deferred-168]**` at story start (done on this branch at creation). Delete
it when the PR merges, per the ledger's "deleted outright once implemented" rule (AC12). The DROP TABLE
follow-up (AC10) is a NEW ledger bullet.

## The problem (verified at origin/master a95109b6)

Two tables both hold "packs" and nothing connects them.

| | `marketplace.session_packs` | `payment.session_pack_tiers` |
|---|---|---|
| Written by | `CoachProfileService.saveStep3` (`:267`). Inside `if (req.sessionPacks() != null)` (`:287`) it deletes the coach's rows (`:291`), flushes (`:292`), re-inserts (`:301`). The "Edit pricing" dialog is NOT a separate writer: `EditCoachPricingDialog.vue:260` calls `saveProfileBuilderStep(3, ...)`, the same endpoint. | `SessionPackPaymentService.createTier` (`:296`), reached only from `POST /api/payment/coaches/me/session-pack-tiers` (the "My Session Pack Offer" page, which also has "Withdraw offer" -> `deactivateTier` `:337`). |
| Cardinality | 0..5 per coach (`ProfileBuilderStep3Request.java:23` `@Size(max = 5)`; no frontend cap), `uq_session_pack (coach_id, session_count)` (V138:2888) | Exactly one active per coach (`idx_spt_one_active_per_coach`, partial unique index, V138:3910). `createTier` deactivates the old active row (`saveAndFlush`, `:308-312`) and inserts a new one (`save`, `:330`); rows are never deleted. `SessionPackTier` has `@Version` (`:49`). |
| Label | optional, `varchar(100)` (V138:1633) | `NOT NULL varchar(200)` (V138:1904), `@NotBlank @Size(max = 200) @NoContactDetails` |
| Bounds | Declared `@Positive int` count, `@NotNull @DecimalMin("0.01")` price, `@Size(max = 100) @NoContactDetails` label (`:26-28`) - **but NOT ENFORCED over HTTP**: the list has no `@Valid` (see Design 6). | count 1..100, price 0.01..50000.00, at most 2 decimals (`CreateSessionPackTierRequest` `:22-27`); `createTier`'s guard (`:300-305`) also rejects a total whose per-session share rounds to 0.00 |
| Read by | `CoachProfileService:403` (`getOwnProfile`) and `:623` (`getPublicProfile`) build `CoachProfileDto.sessionPacks`; `CoachMarketplaceResource:78` passes it through; `ReportGenerationService:193` also calls `getPublicProfile` | `SessionPackPurchasePage.vue`, `SessionPackPaymentService.purchasePack`, `CoachSoldPackQueryService`, `RefundReconciliationService`, `SessionPackPurchase.packTierId` (`fk_spp_tier`, V138:4518) |

Effects, all confirmed:

- `CoachPublicProfilePage.vue:611` `coachHasPacks` (profile packs list non-empty) drives `showBuySessionsSecondary` (`:622`) -
  the "Buy sessions" button. A coach who only ever used the wizard shows the button, and the purchase page shows
  `booking.packs.noTierAvailable` (`SessionPackPurchasePage.vue:59`).
- The coach's own "My Session Pack Offer" page says "no active offer" right after they entered a pack.
- A coach can advertise several packs while only one, different, offer is sold; the profile prices can differ
  from the charged price.
- Why tests missed it: the Pitchside provisioner's step-3 PUT sends the pack (`provisioner.ts:262`, on
  `feature/pitchside`) and then creates the tier by a separate POST after publish (`provisioner.ts:281`). The
  Playwright spec ONB-06 (`e2e/packages/skillars-e2e/specs/onboarding/onboarding.spec.ts:109`, `:150`) is
  marked `test.fail(true, ...)` until this is fixed.
- How the split came about (for the record): Story 2.1 (`4dd4b013`, 2026-06-12) built `marketplace.session_packs`
  as profile display content; Story 7.2 (`e9486e11`, 2026-06-25) built `payment.session_pack_tiers` without
  referencing it; `docs/dev-docs/marketplace/index.html:126` documents the split as intentional ("Purchasable
  pack tiers live in `payment`") but no link was ever designed.

## Options considered (owner chose A on 2026-11-04)

| Option | Verdict |
|---|---|
| **A. `session_pack_tiers` is the source of truth; one pack per coach** | CHOSEN. Fixes all three symptoms, keeps pack entry in the wizard, respects the deliberate one-active-tier rule (Story 7.2 review patch, deferred-103 concurrency, deferred-151 replace UI). Cost: the wizard's "up to 5 packs" goes to one. |
| B. Several active tiers per coach | Rejected for now: drops `idx_spt_one_active_per_coach` and the `Optional` lookups, reworks the purchase page, tier page and the 103/151 work. If multi-pack matters it is its own story with a product decision first. |
| C. Keep both tables, sync wizard packs into tiers | Rejected: two sources remain and drift again as soon as a coach edits the tier page. |
| D. Profile stops advertising builder-only packs | Rejected: makes wizard pack entry useless. |

## Design (pinned; do not re-derive)

1. **Single source of truth.** The coach's pack is the one active `payment.session_pack_tiers` row (or none).
   Nothing reads or writes `marketplace.session_packs` after this story (the table itself is dropped by a
   later migration - AC10).
2. **Module boundary: port + adapter, exactly the existing precedent.** `payment` already imports
   `marketplace` classes; `marketplace` has no `platform.payment` import at all and its dev-doc says "no
   compile dependency on payment" (`docs/dev-docs/marketplace/index.html:192`). So marketplace must NOT call
   `SessionPackPaymentService` directly (that would create a cycle). Follow
   `marketplace.contract.CoachSubscriptionEntitlementPort` (interface owned by marketplace, already injected
   at `CoachProfileService.java:98`) implemented by `payment.adapter.CoachSubscriptionEntitlementAdapter`.
   New port `marketplace.contract.CoachSessionPackPort` with a read
   (`Optional<SessionPackDto> findActivePack(UUID coachId)`) and a write
   (`void replaceActivePack(UUID coachId, Optional<PackSpec> pack)` - spec = count, total price, label),
   implemented by a new `payment.adapter` class that delegates to `SessionPackPaymentService`. The adapter
   read MUST go through JPA (the tier repository), not `JdbcTemplate`: `CoachPublicProfileQueryCountIT`
   counts Hibernate prepared statements, and a JDBC read would pass the budget falsely. Confirm the
   application context still starts (`allow-circular-references: false`, `application.yaml:140`).
3. **The write is "set the offer", not "create a tier".** New `SessionPackPaymentService` method (name is the
   developer's call), propagation REQUIRED (never `REQUIRES_NEW`), that for a coach:
   - given a pack: reads the active tier; if `sessionCount` equal AND `totalPrice.compareTo(...) == 0` AND
     the effective label equal, does NOTHING (no new row, no deactivation); otherwise calls the existing
     `createTier` (which deactivates the old one and inserts - tier rows are never deleted, purchases and
     refunds reference them via `fk_spp_tier`);
   - given no pack: deactivates the active tier if there is one, else does nothing. Do not reuse
     `deactivateTier(coachId, tierId)` blindly: it uses `save` (not `saveAndFlush`), so its version check moves
     to commit time; use `saveAndFlush` as `createTier` does.
   It runs in the caller's transaction (`saveStep3` is method-level `@Transactional`, `:266`; its only caller
   `ProfileBuilderResource.saveStep3` `:131-136` is not transactional), so any exception rolls the whole step
   back and the pricing write (`:285`, not flushed yet) never commits alone. Nothing between `createTier` and
   `saveStep3` may catch and swallow an exception: that would commit a rollback-only transaction and produce
   an `UnexpectedRollbackException` (500). Translating to another RuntimeException is fine.
4. **`saveStep3` semantics.** `req.sessionPacks() == null` still means "leave the offer untouched" (existing
   contract, `:287`). A non-null EMPTY list means "no pack" and deactivates the active tier. A one-element
   list sets the offer. More than one element is a 400 (bean validation).
5. **Default label, invisible to readers.** The tier needs a non-blank label; the builder's is optional. A
   null/blank builder label (`trim().isEmpty()`) is stored as a constant (`"Session pack"` or similar,
   non-blank, <= 200). The READ side (`findActivePack`) maps that exact constant back to `null`, so
   `SessionPackPricingDisplay.vue:47-48` keeps showing the localized "N sessions" fallback
   (`marketplace.sessionsFallbackLabel`). The equality check in item 3 compares effective labels (blank ->
   constant), so the round trip (read null -> dialog sends `''` -> stored constant) creates no new tier.
6. **Validation: turn on nested validation and align with the tier's bounds.** Today
   `ProfileBuilderStep3Request.java:23` is `@Size(max = 5) List<SessionPackRequest>` with no `@Valid`, so NONE of
   the nested annotations run over HTTP - a pack label with contact details is accepted and shown publicly
   today (`ProfileBuilderRequestValidationTest:56-61` validates the inner record directly, hiding it). Change to:
   - `@Size(max = 1) List<@NotNull @Valid SessionPackRequest> sessionPacks` (precedent and its comment:
     `ProfileBuilderStep4Request.java:16-19`);
   - `SessionPackRequest`: `@NotNull @Min(1) @Max(100) Integer sessionCount` (boxed: a primitive `int` lets
     Jackson's default float-to-int coercion turn 2.5 into 2 silently), `@NotNull @DecimalMin("0.01")
     @DecimalMax("50000.00") @Digits(integer = 8, fraction = 2) BigDecimal totalPrice`,
     `@Size(max = 200) @NoContactDetails String label` (200, not 100: the tier page allows 200 and the dialog
     re-sends whatever the profile shows, so a 101-200 character tier label would otherwise block every later
     pricing save);
   - the numbers 100 / 50000.00 must equal `CreateSessionPackTierRequest.MAX_SESSION_COUNT` / `MAX_TOTAL_PRICE`
     WITHOUT a marketplace -> payment import: either move the constants to a shared location both import, or
     duplicate them in marketplace with a test that pins equality (developer's choice).
7. **The tier layer's rejection must not reach the user raw.** `createTier`'s guard throws
   `ResponseStatusException(BAD_REQUEST, "payment.invalidPackTier")`, which `ApiAdvice.java:132-136` renders as
   `errorKey: generic.requestError` with the untranslated reason as the message - not the validation envelope
   (`validation.invalidData` + field errors, `ApiAdvice:505-561`). So `saveStep3` pre-checks everything the
   guard checks that bean validation cannot: the per-session share
   `totalPrice.divide(count, 2, HALF_UP).signum() > 0`. Reject it with a translated key on `sessionPacks`
   (a class-level constraint on `SessionPackRequest` is preferred, so it lands in the same envelope; a
   `MarketplaceException` maps to 422, not 400 - `ApiAdvice:424-429` - acceptable only if the frontend shows
   its key). After this, the `createTier` guard is unreachable from step 3 and stays as the backstop.
8. **Profile reads.** `getOwnProfile` (`:403`) and `getPublicProfile` (`:623`) build `sessionPacks` from the
   port (a list of zero or one element, to keep `SessionPackDto` and the frontend contract unchanged - the
   frontend reads only `sessionCount`, `totalPrice`, `label`). The port read replaces
   `sessionPackRepository.findByCoachId`, one prepared statement for one, so the budget of 4 holds.
9. **No new purchase-time gate is needed.** `purchasePack` already refuses a coach who is not bookable
   (`SessionPackPaymentService:126-135`; `BookingService:587-596` allows only ACTIVE / REDUCED / PENDING_REVIEW,
   so DRAFT is refused), and `getPublicProfile` shows exactly the same three statuses, so a tier created while
   the profile is still DRAFT cannot be sold before publish.
10. **Frontend.**
    - `ProfileBuilderStep3.vue` and `EditCoachPricingDialog.vue` change from a repeatable list of rows to a
      single optional pack (count, total price, label) with a clear/remove control.
    - **Untouched means null.** `ProfileBuilderStep3.vue:117` starts with `sessionPacks: []` every time and the
      builder rail lets a coach go back to step 3 (`CoachProfileBuilderPage.vue:136 goToStep`). Under Design 4
      re-submitting `[]` would withdraw a tier the coach created on the tier page. So the builder sends
      `sessionPacks: null` unless the coach touched the pack fields (or, better, prefills step 3 from
      `getOwnProfile` like the dialog does at `:196-200` and sends what is shown). Clearing a shown pack sends `[]`.
    - The dialog always re-sends the pack it loaded (`:263`). That is acceptable now that the round trip is
      stable (Design 5) and the label bound matches (Design 6), but a stale second tab can still replace or
      restore the offer without the tier page's replace confirmation (`CoachSessionPackTiersPage.vue:194-205`).
      Document this in Dev Notes and the dev-doc; no lock is required.
    - **Client-side checks**, copied from the tier page's own (`CoachSessionPackTiersPage.vue` script, around
      `:326-336`): integer count 1-100, price 0.01-50000 with at most 2 decimals (`step="0.01"`), per-session
      share > 0, label <= 200.
    - **Nested errors must be visible.** Spring reports them as `sessionPacks[0].totalPrice`; the dialog only
      checks `hasFieldError('sessionPacks')` (`:102`, exact match in `useErrorHandler.js:87`) and hides its
      banner for validation errors; the builder shows only a generic banner (`CoachProfileBuilderPage.vue:110-113`).
      Map any `sessionPacks[...]` field error into the pack error slot in both components.
    - Remove the dialog's now-obsolete duplicate-session-count guard (`:233-252`). `SessionPackPricingDisplay.vue`
      keeps rendering a list (0 or 1). i18n: an unused key is fine, a deleted-but-referenced key is not (check the
      i18n spec floor).
11. **Existing data (backfill).** For each coach who has `marketplace.session_packs` rows AND **no
    `session_pack_tiers` row at all** (active or not - a coach with only inactive rows withdrew their offer on
    purpose via "Withdraw offer" and must not get it back) AND `coach_profiles.recap_data_erased_at IS NULL`
    (V166 tombstone), create one active tier from their **lowest-`session_count` valid row**, where valid =
    `session_count BETWEEN 1 AND 100`, `total_price BETWEEN 0.01 AND 50000`, `round(total_price / session_count, 2) > 0`
    (legacy rows were never validated - Design 6). Label: `COALESCE(NULLIF(trim(label), ''), <default constant>)`;
    `price_per_session = round(total_price / session_count, 2)` (HALF_UP, matching `createTier`). Other rows are
    discarded (never purchasable). Before writing the migration, query existing labels for contact details
    (they were never checked); if any exist, treat it as a decision for the owner, do not silently copy them.
    The migration header records how many rows it discards, and the provisioning checklist gets a line (AC10).

## Acceptance Criteria

1. **Builder pack becomes the tier.** Saving step 3 (or the "Edit pricing" dialog) with one pack leaves that
   coach with exactly one active `session_pack_tiers` row whose count, total price and label match
   (blank label -> the default constant). `pricePerSession` is derived as `createTier` already does.
2. **No churn.** Re-saving step 3 with an unchanged pack (including a blank label that was stored as the
   default) creates no new tier row and deactivates nothing; changing count, price or label deactivates the old
   row and creates a new one; two saves in a row with the same changed pack create one new row, not two.
3. **Clearing and untouched.** Saving step 3 with an empty `sessionPacks` list deactivates the active tier (no
   row deleted); `sessionPacks` omitted (null) leaves the tier untouched. The builder sends null when the coach
   did not touch the pack (Design 10), so revisiting step 3 never withdraws a tier made on the tier page. After
   clearing, the public profile has no packs and no "Buy sessions" button.
4. **One source for display and charge.** The profile's `sessionPacks` (own and public) equals the active
   tier's count, total price and label (the default constant reads back as `null`), and the amount charged by
   `purchasePack` is that same tier's `totalPrice`. A pack set on the "My Session Pack Offer" page appears on
   the profile, and vice versa; a 101-200 character label set there does not block a later pricing save.
5. **Validation is real, visible and never a 500.**
   - Nested validation is on (`List<@NotNull @Valid SessionPackRequest>`). Step 3 returns the validation
     envelope (400, `validation.invalidData` + field errors) for: more than one pack; a null pack element;
     count missing, < 1, > 100 or fractional; total price missing, < 0.01, > 50000.00 or with more than 2
     decimals; a label over 200 characters or containing contact details; a per-session share that rounds to
     0.00 (Design 7, with a translated key).
   - The client never sees `payment.invalidPackTier` or `generic.requestError` from step 3.
   - Any rejection rolls back the pricing write made in the same request.
   - Both pack forms run the same checks client-side and show any `sessionPacks[...]` server error in the pack
     error slot.
   - Tests go through the OUTER request (or HTTP), not only the inner record.
6. **Concurrency.** For two concurrent step-3 saves that both change the pack:
   - exactly one active tier remains;
   - any failure is a 409, never a 500, and the loser's whole transaction (including its pricing write) rolls back;
   - if the saves happen to serialise, both succeed and the second one's pack is the active tier.
   Cover BOTH cases through MockMvc/HTTP, asserting the status: (a) no tier yet - the partial unique index
   decides at commit (UUID ids defer the INSERT), 409 `payment.tierRaceConflict` (`ApiAdvice:164-166`,
   `:193-195`, `:204-226`); (b) an existing tier - `@Version` on the row being deactivated decides inside the
   request, 409 `generic.conflict` (`ObjectOptimisticLockingFailureException`, `ApiAdvice:703-709`). The
   deferred-103 IT (`SessionPackPaymentServiceIT:71-101`) is a pattern for running two transactions, not proof:
   it only races case (a), tolerates an unmapped `TransactionException`, and never asserts a 409.
7. **History and purchases untouched.** A coach who already sold packs and then edits their pack keeps every
   purchase, extension and refund pointing at its original tier row; the new offer applies only to later
   purchases; old purchases' locked `pricePerSession` (Story 7.2, V62) and `amount_charged` (V181) are
   unchanged.
8. **Module boundary.** `marketplace` has no import from `platform.payment`; the port lives in
   `marketplace.contract`, the adapter in `payment.adapter`. There is no ArchUnit in the project: add a
   source-scan test asserting no file under `platform/marketplace` imports `platform.payment`, in the style of
   `InfrastructureLayeringAuditTest` / `EmailTransportArchitectureTest`.
9. **Public profile query count unchanged, test rewritten.** `CoachPublicProfileQueryCountIT` keeps
   `EXPECTED_QUERY_COUNT = 4`, but it seeds `marketplace.session_packs` today (`:199-205`) and asserts parity
   against it (`:152-153`, `:184`), so it must be rewritten: seed one active tier plus a few inactive history
   rows, assert `sessionPacks` has size 1 and matches the active tier, and keep the "does not grow with
   profile size" check for the other child rows (packs can no longer scale).
10. **Legacy data and table.**
    - New migration (next free number at implementation time; V225 is taken by open PR #428 and nothing else
      on any remote branch is at V225+, so expect V226 - re-check `origin/master` before naming it) performs
      the Design 11 backfill. It follows `docs/deployment/migration-conventions.md`: additive,
      schema-qualified, `SET LOCAL lock_timeout`, chunked or provably small, passes
      `MigrationConventionLintTest`.
    - `marketplace.session_packs` is NOT dropped in this story: rule 2 (`migration-conventions.md:58`) says a
      table is dropped only in a release after the one that removed every code reference, and
      `MigrationLint.Rule.DROP_WITHOUT_PRIOR_RELEASE_PREP` (`src/test/java/com/softropic/skillars/db/MigrationLint.java:190-197`)
      requires a `-- migration-lint: drop-prepared-in: V<n>` marker naming an earlier release, with no live
      `src/main` reference left. Delete `SessionPack`, `SessionPackRepository` and every code reference here
      (including the `uq_session_pack` mapping, AC11). File a new ledger bullet "drop `marketplace.session_packs`
      (last reader removed in skillars-deferred-168, prepared in V<this migration>)". Alternative (owner may
      choose at review): drop it in the same release under the "prod is not live" exemption already listed in
      `docs/deployment/provisioning-checklist.md:160`; if so add this migration's number to that list.
    - `docs/deployment/provisioning-checklist.md`: add the backfill (what it creates, what it skips and
      discards) so the go-live checklist stays accurate.
11. **Tests and code that must change.**
    - Backend, new or rewritten:
      - `CoachProfileServiceTest` (`@InjectMocks` at `:66-83`: add a `@Mock CoachSessionPackPort`): null /
        empty / one pack / default label / unchanged = no write.
      - A service test for set-the-offer: no churn, replace, clear, default-label round trip.
      - The two concurrency cases (AC6).
      - A `CoachProfileBuilderIT` path asserting the tier row exists after step 3.
      - A profile-read IT asserting display == charged price (AC4).
      - A backfill-migration IT covering a withdrawn coach, an erased coach and an invalid legacy row (all
        skipped), plus a blank label (defaulted).
      - Outer-request validation tests for AC5, incl. the fractional count, over-max and 3-decimal cases.
      - The rewritten `CoachPublicProfileQueryCountIT` (AC9).
    - Backend, existing tests and code to update or delete:
      - `CoachProfileSelfEditIT:209` (resubmitting the same count) and `:238-263` (two packs with the same
        count, `marketplace.duplicateSessionPackCount`): the second becomes a `@Size(max = 1)` validation 400.
      - `ApiAdvice:167-172` `uq_session_pack` mapping and its `ApiAdviceTest` case: delete (dead once the
        table has no writer).
      - `ProfileBuilderRequestValidationTest:56-61`: keep, add outer-request cases.
      - Stale comments citing "V62:58" for the index (`SessionPackPaymentService:326`, the
        `SessionPackPaymentServiceIT` javadoc): it is V138:3910.
    - Frontend: update `ProfileBuilderStep3Spec.js` and `EditCoachPricingDialogSpec.js` for the single-pack
      shape (null when untouched in the builder, empty array on clear, no second row, client-side checks,
      nested error display); run the unit suite with the project's normal commands only (no local
      `mvn verify` - CI is the gate).
    - Pitchside (on `feature/pitchside`, NOT on this branch): remove `test.fail` from ONB-06
      (`onboarding.spec.ts:150`) and delete the extra tier POST at `provisioner.ts:281`. The step-3 PUT at
      `:262` already sends the pack, so after this story the POST would only create a second, churned tier row.
      This is a hand-off: note it in the PR description. If `feature/pitchside` has landed on master by the time
      this merges, do it in this PR.
12. **Docs and ledger.** Update:
    - `docs/dev-docs/marketplace/index.html:126` (the `SessionPack` row: gone; the profile reads and writes the
      offer through the port);
    - `docs/dev-docs/database/index.html:72` (lists `session_packs`);
    - the payment dev-doc section on tiers;
    - `docs/business-docs/coach/index.html:79` (Step 3 implies several packs). The other business docs already
      say one active pack.
    - Mermaid diagrams, if any show the two tables (mind the `;` gotcha in Mermaid labels).
    Delete the ONB-06 ledger bullet (and its now-empty section heading) on merge; add the DROP TABLE follow-up
    bullet.
13. **`/pr-review` loop.** Run `/pr-review` on the final commit and fix until a clean round (or the owner closes
    it), per the repo's PR workflow.

## Tasks / Subtasks

- [x] T1 (AC8, Design 2): add `CoachSessionPackPort` + `PackSpec` to `marketplace.contract`; add the payment adapter (JPA read, default-label -> null mapping); wire; confirm context starts; source-scan boundary test.
- [x] T2 (AC1-3, AC6, Design 3, 5): set-the-offer method in `SessionPackPaymentService` (REQUIRED; compare, replace, clear with `saveAndFlush`, default label); unit tests + both concurrency cases through HTTP.
- [x] T3 (AC1, AC3, AC5, Design 4, 6, 7): rework `CoachProfileService.saveStep3` to call the port; `ProfileBuilderStep3Request` (`@Size(max = 1) List<@NotNull @Valid ...>`, boxed `Integer`, bounds, label 200, per-session class-level constraint); shared or pinned constants; remove `SessionPackRepository` use.
- [x] T4 (AC4, AC9, Design 8): profile reads via the port in `getOwnProfile` and `getPublicProfile`; rewrite `CoachPublicProfileQueryCountIT`.
- [x] T5 (AC10, Design 11): pre-check legacy labels for contact details; backfill migration + lint + checklist line; delete `SessionPack`/`SessionPackRepository` and the `uq_session_pack` mapping; ledger bullet for the DROP.
- [x] T6 (AC3, AC5, Design 10): frontend single-pack UI in `ProfileBuilderStep3.vue` and `EditCoachPricingDialog.vue`; null-when-untouched (or prefill) in the builder; client-side checks; nested-error display; drop the duplicate-count guard; i18n; specs.
- [x] T7 (AC11): remaining backend tests (builder path, display == charged, backfill, `CoachProfileSelfEditIT`, `ApiAdviceTest`, stale comments); the Pitchside hand-off note.
- [x] T8 (AC12): dev-docs, business-docs, ledger hygiene.
- [ ] T9 (AC13): `/pr-review` to a clean round; open the PR (no `no-build` label - this touches code and a migration).

## Dev Notes

- **Do not touch `createTier`'s guard or the unique index.** The one-active rule is deliberate; this story
  routes the builder through it. The guard stays as the backstop, unreachable from step 3 after Design 7.
- **Do not add a marketplace -> payment import**, even for a constant (Design 2, 6). The port is the seam.
- **`saveStep3` transaction.** It is `@Transactional` (REQUIRED); the port call must join it (no
  `REQUIRES_NEW`), so a tier rejection or a race loss rolls back the pricing write. Do not isolate the tier
  write, and do not catch-and-swallow anything on the way up (Design 3).
- **Where race failures surface.** First-tier race: at commit (the UUID-id INSERT is deferred), translated to
  `DataIntegrityViolationException`. Existing-tier race: inside the request, at the deactivating
  `saveAndFlush`, as `ObjectOptimisticLockingFailureException`. `CoachPricing` has no `@Version`, so the
  pricing writes themselves just serialise; the tier conflict is what fails the loser.
- **Known accepted behaviour:** the dialog re-sends the pack it loaded, so a stale second tab can replace or
  restore the offer without the tier page's confirmation (Design 10). Document it; it is not fixed here.
- **Parallel PRs touch this area.** PR #428 (deferred-167) holds V225. Before choosing the migration number
  and before opening the PR, `git fetch` and rebase; expect conflicts in `deferred-work.md`,
  `sprint-status.yaml` and `provisioning-checklist.md`.
- **Worktree.** This work lives in `../skillars-deferred-168`; `src/frontend/.quasar` was copied in and
  `node_modules` is a symlink - never `git add -A` blindly (check `git status` for the symlink).
- **Line numbers** are as of a95109b6 and were re-verified by /mto-story-review; re-check after any rebase.
  (Correction from the first draft: the ledger's `CoachProfileService.java:287` is right - it is the
  `sessionPacks != null` check; `saveStep3` itself starts at `:267`.)
- **Out of scope, noted:** pack labels are not erased by `GdprErasureService` (neither table, before or after
  this story) - a pre-existing gap, not introduced here. `ReportGenerationService:193` also calls
  `getPublicProfile`; it gets the same one-query read.

### Project Structure Notes

Backend: `platform/marketplace/{contract,service,repo}`, `platform/payment/{adapter,service,contract}`,
`src/main/resources/db/migration`. Frontend: `components/profileBuilder`, `components/profile`,
`components/marketplace`, `pages/coach`, `i18n/*`. Tests mirror the same packages; naming per the repo
(`*Test` unit/slice, `*IT` failsafe - the CI context gate is 17 (`assert-context-count.sh:179`,
`pr-build.yml:127`): reuse the shared base, add no new `@MockBean` set; the only web slice here,
`SessionPackPaymentResourceWebMvcTest`, mocks the service and does not load the adapter).

### References

- `_bmad-output/implementation-artifacts/story-review.md` - the review this revision applies
- `_bmad-output/implementation-artifacts/deferred-work.md` - "Deferred from: Pitchside UI tests, spec ONB-06 (2026-11-03)"
- `_bmad-output/implementation-artifacts/skillars-2-1-coach-profile-builder.md` (origin of `marketplace.session_packs`)
- `_bmad-output/implementation-artifacts/skillars-7-2-session-payment-lifecycle-credit-wallet.md` (origin of tiers; the unique-index review patch; locked `pricePerSession`)
- `_bmad-output/implementation-artifacts/skillars-deferred-103-payment-reliability-pack-pause-hardening-and-cross-module-cleanup.md` (AC1, tier race)
- deferred-151 (#265): the tier page's replace confirmation and "Withdraw offer"
- `docs/deployment/migration-conventions.md`, `docs/deployment/provisioning-checklist.md`, `src/test/java/com/softropic/skillars/db/MigrationLint.java`
- Precedent for the port/adapter seam: `marketplace.contract.CoachSubscriptionEntitlementPort`, `payment.adapter.CoachSubscriptionEntitlementAdapter`
- Precedent for nested list validation: `ProfileBuilderStep4Request.java:16-19`

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5), /bmad-dev-story, 2026-11-04.

### Debug Log References

- Two local IT runs failed before any test started: Testcontainers' `postgres:17-alpine` timed out waiting for
  "ready to accept connections" while the `skillars-e2e-*` stack held ~2.5 GB of the 3.8 GB Docker VM (environmental,
  not a code failure). The third run, with that stack idle, was green.
- One red-then-green in the new validation test: the test's own boundary was wrong (0.05 / 100 rounds to 0.00, not
  0.01); corrected to 0.49 (rejected) / 0.50 (accepted).
- The i18n template-key scan (`i18nTemplateKeyResolutionSpec.js`) flagged the two new `t(packErrorKeyNow)` call sites
  and the shifted `CoachProfileBuilderPage.vue` line; its exception list was updated.

### Completion Notes List

**Validation run (targeted, per docs/validation-strategy.md; no `mvn verify`):**
- Backend unit (surefire, 122 tests, all green): `CoachProfileServiceTest` (23), `ProfileBuilderRequestValidationTest`
  (10), `SessionPackOfferTest` (7, new), `SessionPackInputBoundsTest` (5), `SessionPackPaymentServiceTest` (29),
  `ApiAdviceTest` (10), `MigrationConventionLintTest` (30), `MarketplaceDoesNotDependOnPaymentTest` (1, new),
  `InfrastructureLayeringAuditTest` (1), `IntegrationTestConventionTest` (6).
- Backend IT (failsafe, 78 tests, all green): `CoachProfileSelfEditIT` (20), `CoachProfileBuilderIT` (47),
  `CoachPublicProfileQueryCountIT` (3, budget still 4), `BuilderPackTierBackfillIT` (5, new), `SessionPackPaymentServiceIT` (3).
  Both AC6 race cases genuinely overlapped in this run: the log shows the existing-tier race failing on `@Version`
  (`ObjectOptimisticLockingFailureException` -> 409 `generic.conflict`) and the first-pack race on
  `idx_spt_one_active_per_coach` (-> 409 `payment.tierRaceConflict`); the tests assert any loser is a 409, one tier stays
  active, and a lone winner's price and pack are what remain (the loser's pricing rolled back).
- Frontend (Vitest, 26 files / 197 tests green): `ProfileBuilderStep3Spec`, `EditCoachPricingDialogSpec`, the i18n
  parity and template-key specs, `CoachProfileBuilderPageSpec`, `src/utils`, `src/components/{profileBuilder,profile,marketplace}`.
  Prettier clean on every touched frontend file. `node docs/dev-docs/tools/check-docs.mjs`: OK (26 pages, static).

**What was built (by task):**
- T1: `marketplace.contract.CoachSessionPackPort` (+ `PackSpec`) and `SessionPackLimits`; `payment.adapter.CoachSessionPackAdapter`
  (JPA read; the default label reads back as null). A constructor-graph walk from `SessionPackPaymentService` found no path
  back to `CoachProfileService`, so the adapter delegates to it directly (no extra service); the context starts (ITs green).
  `CreateSessionPackTierRequest`'s constants now alias `SessionPackLimits` (payment -> marketplace, the allowed direction).
- T2: `SessionPackPaymentService.replaceActiveOffer` (compare count / `compareTo` price / effective label; else `createTier`)
  and `withdrawActiveOffer` (`saveAndFlush` deactivation). Both REQUIRED.
- T3: `saveStep3` calls the port inside its transaction; `ProfileBuilderStep3Request` is
  `@Size(max = 1) List<@NotNull @Valid SessionPackRequest>`, boxed `Integer` count 1..100, price 0.01..50000.00 with 2
  decimals, label <= 200, and an `@AssertTrue isPerSessionPriceValid()` whose message is
  `marketplace.packPerSessionPriceTooLow|...` (translated in all four `messages*.properties`).
- T4: both profile reads use `findActivePack`; `CoachPublicProfileQueryCountIT` now seeds one active tier plus inactive
  history rows and asserts the pack matches.
- T5: `V226__backfill_builder_packs_into_session_pack_tiers.sql`; `SessionPack`/`SessionPackRepository` deleted; the
  `uq_session_pack` `ApiAdvice` mapping removed; checklist line; ledger follow-ups.
- T6: single-pack UI in both components, shared `src/utils/sessionPackForm.js` (client-side checks, request mapping,
  nested-error lookup); the builder sends `sessionPacks: null` until the pack is touched; the dialog's duplicate-count
  guard removed; the builder page banner shows a nested pack field error; i18n in en-US/de-DE/fr-FR.
- T7: ITs above; the Pitchside hand-off is a ledger bullet (ONB-06 `test.fail` and `provisioner.ts:281`).
- T8: dev-docs (marketplace, database, payment) + search index, business-docs coach Step 3, ledger.

**Deviations from the story, each deliberate:**
- **Fractional counts are still truncated server-side.** Design 6 assumed a boxed `Integer` makes Jackson reject 2.5; it
  does not (`ACCEPT_FLOAT_AS_INT` applies to `Integer` too). Changing the global ObjectMapper is out of scope, so the
  guard is client-side (both forms reject non-integers) and the record's Javadoc says so. The boxed type still turns a
  missing count into a `@NotNull` violation instead of 0.
- **Legacy label contact-detail check (Design 11).** UAT data could not be queried from here, so instead of an owner
  decision the backfill substitutes the default label for any legacy label that looks like contact details (`@`, URL,
  or a 7+ digit run). Recorded in the migration header and the checklist line.
- **Story said an `ApiAdviceTest` case for `uq_session_pack` exists; it does not** (grep found none). Only the mapping was removed.
- **i18n:** `auth.coach.step3PackDuplicateSessionCount` was replaced by the four new pack error keys (its only reference
  was removed); `marketplace.duplicateSessionPackCount` (now unreferenced) is left for the DROP follow-up.
- **Doc footer stamps were not bumped**: only the edited rows were verified, not whole pages.
- **Ledger:** the ONB-06 section is replaced in this PR (so it disappears when the PR merges) by a
  skillars-deferred-168 section with two follow-ups: the `DROP TABLE` (prepared in V226) and the Pitchside hand-off.

**Merge-order constraint:** V226 assumes PR #428's V225 lands first. Flyway runs without out-of-order, so if this
branch merges before #428, renumber (#428 would otherwise be rejected on any database already at V226).

**Not done here:** T9 / AC13 (`/pr-review` to a clean round, then the PR). It runs on the committed branch after this
workflow, as for skillars-deferred-167.

### File List

New:
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSessionPackPort.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/SessionPackLimits.java`
- `src/main/java/com/softropic/skillars/platform/payment/adapter/CoachSessionPackAdapter.java`
- `src/main/resources/db/migration/V226__backfill_builder_packs_into_session_pack_tiers.sql`
- `src/test/java/com/softropic/skillars/db/BuilderPackTierBackfillIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/MarketplaceDoesNotDependOnPaymentTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackOfferTest.java`
- `src/frontend/src/utils/sessionPackForm.js`

Deleted:
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/SessionPack.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/repo/SessionPackRepository.java`

Modified:
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep3Request.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CreateSessionPackTierRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/main/resources/i18n/messages.properties`, `messages_de.properties`, `messages_en.properties`, `messages_fr.properties`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileSelfEditIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachPublicProfileQueryCountIT.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderRequestValidationTest.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceTest.java`
- `src/frontend/src/components/profileBuilder/ProfileBuilderStep3.vue`
- `src/frontend/src/components/profileBuilder/__tests__/ProfileBuilderStep3Spec.js`
- `src/frontend/src/components/profile/EditCoachPricingDialog.vue`
- `src/frontend/src/components/profile/__tests__/EditCoachPricingDialogSpec.js`
- `src/frontend/src/pages/coach/CoachProfileBuilderPage.vue`
- `src/frontend/src/i18n/en-US/index.js`, `de-DE/index.js`, `fr-FR/index.js`
- `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`
- `docs/dev-docs/marketplace/index.html`, `docs/dev-docs/database/index.html`, `docs/dev-docs/payment/index.html`,
  `docs/dev-docs/assets/search-index.js`
- `docs/business-docs/coach/index.html`
- `docs/deployment/provisioning-checklist.md`
- `_bmad-output/implementation-artifacts/deferred-work.md`, `sprint-status.yaml`, this story file

### Change Log

- 2026-11-04: story created from the ONB-06 ledger entry; owner chose Option A.
- 2026-11-04: /mto-story-review findings applied (M1-M4, C1-C12; see story-review.md).
- 2026-11-04: implemented T1-T8 (/bmad-dev-story); 122 unit + 78 IT + 197 frontend tests green; status -> review. T9 (/pr-review, PR) next.
