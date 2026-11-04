# Story skillars-deferred-170: Session-pack tier follow-ups (drop the old table, tier caption, purchase readiness order, fractional counts)

Status: ready-for-dev

<!-- CREATED 2026-11-04 (/bmad-create-story equivalent, written by hand). Owner-requested bundle of the
     four follow-ups skillars-deferred-168 (PR #436, merged 47180ea7) left in deferred-work.md, section
     "Deferred from: skillars-deferred-168 (builder packs become the purchasable tier, 2026-11-04)".
     Every citation re-read at origin/master 0ee68f07. The ledger's sixth bullet there (Pitchside ONB-06
     hand-off) is NOT in this story: it is done in PR #439 into feature/pitchside.
     SEQUENCING: start AC3 only after skillars-deferred-169 (payments/Stripe RS05-RS09, ready-for-dev in
     the main checkout) has merged; it rewrites SessionPackPaymentService.purchasePack and
     SessionPackPurchasePage.vue. AC1, AC2 and AC4 do not touch either and may start at once. -->

## Story

As a **coach and the parents who buy my packs**,
I want **the session-pack tier to be stored once, captioned once, refused early when I cannot be paid,
and to hold only whole session counts**,
so that **what I set up is exactly what parents see and buy, and a purchase that cannot succeed leaves
nothing behind**.

## Ledger mapping (so future passes do not re-pick this)

All four come from `deferred-work.md`, "Deferred from: skillars-deferred-168 (...)":

| Ledger bullet | AC |
|---|---|
| Drop `marketplace.session_packs` | AC1 |
| The coach's tier page repeats the tier's name in its caption | AC2 |
| `purchasePack` checks the coach's Stripe setup only after it has written the payer's Stripe customer | AC3 |
| A fractional `sessionCount` is truncated, not rejected | AC4 |

On completion, delete those four bullets (AC6). Leave the ONB-06 hand-off bullet, which PR #439 closes on
`feature/pitchside`.

Searched and **not** bundled: the pack-preselect bullet (owner decision pending; PR #433 on
`feature/pitchside`), the declined-card message bullet (PR #435 on `feature/pitchside`), and
`V184`'s deferred drop of `main.video_approval_requests` (same kind of task, but a different module).

## The problem (verified at origin/master 0ee68f07)

1. **Dead table.** `marketplace.session_packs` (`V138__baseline_schema.sql:1628`) has no reader or writer
   in `src/main`. skillars-deferred-168 removed `SessionPack`/`SessionPackRepository`, and `V226` copied its
   purchasable rows into `payment.session_pack_tiers`. What is left:
   - The frontend i18n key `marketplace.duplicateSessionPackCount` (en-US `index.js:393`, fr-FR `:397`,
     de-DE `:920`), with nothing that renders it. `en-US/index.js:398` names it in a comment as an
     example; reword that comment.
   - `docs/dev-docs/database/index.html:72` still lists `session_packs` under `marketplace`.
   - A Javadoc mention in `CoachSessionPackPort.java:12`, written in the past tense.
   - `BuilderPackTierBackfillIT` (`src/test/java/.../db/BuilderPackTierBackfillIT.java:69`) INSERTs into
     `marketplace.session_packs` and then runs V226's SQL. **It breaks the moment the table is dropped.**
2. **Doubled caption.** `CoachSessionPackTiersPage.vue` shows `tierName(tier)` as the heading (`:29`,
   `:77`), then the caption `t('payment.sessionPack.tierLabel', { label: tierName(tier), count, price })`
   (`:32-36`, `:80-84`). `tierLabel` is `'{label} — {count} sessions for €{price}'` (en-US `:2391`, de-DE
   `:1618`, fr-FR `:2044`). Every tier therefore names itself twice: "Season 10" / "Season 10 — 10 sessions
   for €380". A label-less tier reads "5 sessions" / "5 sessions — 5 sessions for €200". `tierLabel` has no
   other caller (grep over `src/frontend/src`).
3. **Readiness checked late.** `SessionPackPaymentService.purchasePack` (`:98`) runs, in order:
   - the player-ownership and age checks;
   - the tier lookup and active check (`:126-130`);
   - `requireBookableCoachStatus` (`:135`);
   - **`getOrCreateStripeCustomer` (`:140`)**, which for a first-time payer calls
     `paymentGateway.createStripeCustomer` (a Stripe API call) and saves a `stripe_customers` row. For a
     returning payer who sent a `paymentMethodId`, it overwrites the saved card (`:440-455`).
   - `chargeAndCapture` (`:144`). Only now does `StripePaymentGateway.charge` throw
     `payment.coachStripeNotConfigured` (`StripePaymentGateway.java:113-116`, `resolveCoachStripeAccountId:631`).

   Booking checks first: `BookingService.requireCoachPaymentReady` (`:599`) is called at `:415` and by
   `BookingBatchService:156`. **No money moves today.** The #436 PR body said a pack of an unready coach
   "can be bought"; that was wrong, and the ledger bullet corrects it. The parent sees the generic
   "Purchase failed" (`SessionPackPurchasePage.vue:189` reads `e.response.data.message`, which `ErrorDto`
   never has).
4. **Fractional counts truncated.** `application.yaml:134-137` sets no Jackson coercion, so
   `DeserializationFeature.ACCEPT_FLOAT_AS_INT` keeps its default of `true`. `2.5` binds as `2` in
   `ProfileBuilderStep3Request.SessionPackRequest.sessionCount` (`:42`) and
   `CreateSessionPackTierRequest.sessionCount` (`:27`), both boxed `Integer`, before any constraint runs.
   Only the UI blocks fractions (`step="1"`, `packErrorKey` in `src/frontend/src/utils/sessionPackForm.js`).
   skillars-deferred-168 recorded this as a known deviation in its Completion Notes ("Fractional counts
   are still truncated server-side").

## Decisions (defaults pinned here; the owner may overturn any at /mto-story-review)

- **D1: drop the table now, not "a release later".** Rule 2 of `migration-conventions.md` protects an
  old-release pod that still reads a table while a new release drops it. There has still never been a
  production deploy (the ledger says so repeatedly, e.g. `runbook.md:598-613` as cited at
  `deferred-work.md:2321`). The precedent is `V142__drop_pending_blob_deletions.sql`'s header (owner
  decision 2026-09-16, skillars-deferred-117), which dropped on that ground. The marker
  `drop-prepared-in: V226` is still required, and it is truthful: V226 is the migration that shipped with
  the removal of the last reader. *Alternative:* wait for a real release. Choose that only if a
  production deploy has happened since 2026-09-16.
- **D2: the readiness fix stays in this story but is sequenced after skillars-deferred-169.** That story
  rewrites `purchasePack`'s pre-charge and post-charge order (its AC1 attempt record, AC8 card write) and
  the purchase page's error and retry handling. Doing AC3 first would put a conflict on both files.
  *Alternative:* fold AC3 into 169, which is still ready-for-dev. It is a three-line move plus a test, so
  that is also cheap. Choose it if 169 has not started when this story is reviewed.
- **D3: the public profile keeps offering "Buy sessions" / "Book" for an unready coach.** This is out of
  scope. Hiding them needs a payment-readiness field on the public profile, reached through a marketplace
  port (marketplace must not import payment; `MarketplaceDoesNotDependOnPaymentTest`). Booking has the
  same late refusal today, though with a translated toast (`BookingRequestPage.vue:656`). After AC3,
  re-file this as its own ledger bullet (AC6).
- **D4: reject fractions only on the two `sessionCount` fields, not globally.** Turning
  `ACCEPT_FLOAT_AS_INT` off in `application.yaml` would change every integer field of every endpoint.
  *Alternative rejected:* change the fields to `BigDecimal` with `@Digits(fraction = 0)`. It would give a
  field error rather than a body error, but it ripples into `PackSpec`, `createTier` and every caller for
  an API-only case that the UI already blocks.

## Design (pinned; do not re-derive)

1. **AC1 migration** `V<next>__drop_marketplace_session_packs.sql`. Take the next free number at dev time;
   169 also claims V227+, and whichever merges second renumbers. Contents:
   - a header in the shape of V142's: the last reader was removed by skillars-deferred-168 (#436, V226);
     D1's pre-launch reasoning; the table's constraints and FK go with it;
   - `SET LOCAL lock_timeout = '5s';`
   - `-- migration-lint: drop-prepared-in: V226` immediately above
     `DROP TABLE IF EXISTS marketplace.session_packs;`

   `MigrationLint`'s reference scan (`DROP_WITHOUT_PRIOR_RELEASE_PREP`) searches `src/main/java` and
   `src/main/resources` for the table identifier. If it flags `CoachSessionPackPort.java:12`'s Javadoc,
   reword the Javadoc ("the profile builder's former pack table"). Do not add an allow marker.
2. **`BuilderPackTierBackfillIT`**: delete it. It tests a one-shot backfill whose source table no longer
   exists. In the migration header, record that V226's behaviour was pinned by it until this drop. Its
   coverage is not recoverable without the table, and recreating the table in a test schema would only
   test V226 against a fake.
3. **AC2 caption**: rename the key to `payment.sessionPack.tierSummary` = `'{count} sessions for €{price}'`
   (de-DE `'{count} Sitzungen für €{price}'`, fr-FR `'{count} séances pour {price} €'`) and drop
   `tierLabel`. Leave `payment.sessionPack.tierLabel: 'Tier'` alone; it is a different key, in a different
   block, with the same leaf name (en-US `:1466`). Check that the one you remove is the
   `'{label} — ...'` string. The page:
   - a tier **with** a label: heading = label, caption = `tierSummary`;
   - a tier **without** a label: heading = `tierSummary`, no caption line;
   - the same rule for the history list.

   `tierName` then reads `tier.label || tierSummary(tier)`. `marketplace.sessionsFallbackLabel` stays,
   because the public profile uses it.
4. **AC3 order**: in `purchasePack`, right after `requireBookableCoachStatus` (`:135`) and before the
   `stripeCustomerRepository.findById` snapshot (`:137`), add:
   `if (!paymentGateway.isCoachPaymentReady(tier.getCoachId())) throw new PaymentGatewayException("payment.coachStripeNotConfigured");`
   Use the same check `BookingService.requireCoachPaymentReady` makes. Do not call into `BookingService`
   (its method is package-private, and payment would gain a booking dependency for one line). Keep the
   gateway's own fail-fast in `charge()`: it is the backstop for a coach who loses readiness between the
   two checks. On the purchase page, show `payment.error.coachStripeNotConfigured` (it already exists in
   all three locales, en-US `:2327`) when the 422's `errorMsg.errorKey` is that code. Use whatever error
   reader is there after 169 (and #435, if it has reached master); if neither exists, read
   `e.response.data.errorMsg.errorKey`, as `BookingRequestPage.vue:656` does.
5. **AC4 strict count**: add `StrictIntegerDeserializer` (`infrastructure/validation`, next to
   `PhoneNumberDto`'s deserializer, the one existing `@JsonDeserialize` precedent). It extends Jackson's
   `NumberDeserializers.IntegerDeserializer` (or `StdDeserializer<Integer>`), so ints, `null` and today's
   numeric-string handling are unchanged. Any `VALUE_NUMBER_FLOAT` token (`2.5`, and `2.0` too, on purpose)
   is passed to `ctxt.handleWeirdNumberValue(...)` / raised as `InvalidFormatException`. Annotate both
   `sessionCount` fields with `@JsonDeserialize(using = StrictIntegerDeserializer.class)`. The response is
   `ApiAdvice.httpMessageNotReadableExceptionHandler` (`:486`): 400 `validation.badRequest`, with no field
   error. That is acceptable for an API-only path the UI never reaches; state it in the Javadoc.

## Acceptance Criteria

**AC1: `marketplace.session_packs` is gone.**
- The new migration drops the table as in Design 1. `MigrationConventionLintTest` passes with the
  marker in place and fails if the marker is removed (check by hand once; do not commit a failing
  fixture).
- `BuilderPackTierBackfillIT` is deleted.
- The i18n key `marketplace.duplicateSessionPackCount` is removed from all three locales.
  `i18nTemplateKeyResolutionSpec` and any unused-key check stay green.
- `docs/dev-docs/database/index.html:72` no longer lists `session_packs`. The search index is rebuilt
  (`node docs/dev-docs/tools/build-search-index.mjs`) and `check-docs.mjs` passes.
- `docs/deployment/provisioning-checklist.md` gets a line for the drop, next to V226's.
- A fully migrated test database has no `marketplace.session_packs`. Any IT that boots the schema
  proves this. Add one assertion to an existing schema or migration IT; do not add a new context.

**AC2: each tier names itself once.**
- On `CoachSessionPackTiersPage`, a labelled tier shows its label, then "10 sessions for €380". A
  label-less tier shows "5 sessions for €200" and no second line. This applies to both the current offer
  and the history list.
- `CoachSessionPackTiersPageSpec` asserts both shapes and that the label text appears exactly once.
  Mutation check: restore `label: tierName(tier)` in the caption, and the spec goes red.
- The old `'{label} — ...'` string is removed from all three locales.

**AC3: a purchase from an unready coach is refused before any write.** *(after 169 merges, D2)*
- A `purchasePack` for an active tier whose coach has no COMPLETE + charges-enabled Connect account
  throws `payment.coachStripeNotConfigured`. It then has neither called
  `paymentGateway.createStripeCustomer` nor saved a `stripe_customers` row (first-time payer), nor
  changed `stripe_payment_method_id` (returning payer who sent a card). It has also not called
  `chargeAndCapture`.
- This is proved in `SessionPackPaymentServiceTest` (Mockito: `verify(..., never())`) and through HTTP in
  `SessionPackPurchaseIT`: a 422 with `errorMsg.errorKey == payment.coachStripeNotConfigured` and no
  `payment.stripe_customers` row for the payer.
- The purchase page shows "This coach has not completed their payment setup yet" for that 422, not
  "Purchase failed". `SessionPackPurchasePageSpec` covers it.
- The gateway's own check in `charge()` stays (backstop).

**AC4: fractional session counts are rejected, not truncated.**
- `PUT /api/marketplace/coaches/me/profile/steps/3` with `sessionPacks: [{ sessionCount: 2.5, ... }]`,
  and `POST /api/payment/coaches/me/session-pack-tiers` with `sessionCount: 2.5`, each answer 400 and
  write nothing. The active tier is unchanged, and no pricing row is updated by the step-3 call.
- `sessionCount: 2` and `sessionCount: null` behave as today: accepted, or rejected by `@NotNull`.
- A unit test of the deserializer covers `2`, `2.5`, `2.0`, `null` and `"2"`. ITs cover both endpoints
  (`CoachProfileSelfEditIT` or `CoachProfileBuilderIT`, and `SessionPackPaymentServiceIT` or the tier
  resource IT, whichever already posts tiers).
- The "known deviation" line in skillars-deferred-168's Completion Notes is not edited (history). This
  story's Completion Notes say it is closed.

**AC5: docs.** `docs/dev-docs/payment/index.html` gets one line on the purchase-time readiness check
(AC3), and the marketplace or payment page gets one line saying `sessionCount` must be a whole number
(AC4). Then rebuild the search index.

**AC6: ledger.** Delete the four bullets in the Ledger mapping. Add one bullet for D3: the public profile
offers "Buy sessions" and "Book" for a coach who cannot be paid. Cite `CoachPublicProfilePage.vue:617` and
D3's port constraint.

**AC7: `/pr-review`** on the final commit, run to a clean round or to an owner decision. Then open the PR
(not `no-build`: there is code and a migration).

## Tasks / Subtasks

- [ ] T1 (AC1): migration; delete `BuilderPackTierBackfillIT`; reword the Javadoc if the lint flags it;
  remove the i18n key; update the database doc, provisioning checklist and search index; add the
  schema assertion.
- [ ] T2 (AC2): `tierSummary` key in three locales; page heading and caption rule; spec + mutation check.
- [ ] T3 (AC4): `StrictIntegerDeserializer` + unit test; annotate both fields; two ITs.
- [ ] T4 (AC3, **after 169 merges**): rebase; move the readiness check; unit test + IT; purchase-page
  message + spec.
- [ ] T5 (AC5, AC6): docs, ledger.
- [ ] T6 (AC7): `/pr-review`, PR.

## Dev Notes

- **Constraints carried from deferred-168's run:**
  - work only in this worktree (`../skillars-deferred-168`, branch
    `skillars-deferred-170-session-pack-tier-follow-ups`);
  - never run git checkout in the main checkout (another session works there on 169);
  - no local `mvn verify` (CI is the gate), targeted tests only;
  - never `git add -A` (the `src/frontend/node_modules` symlink).
- **`purchasePack` stays non-`@Transactional`** (its Javadoc, and 169's Dev Notes). The new check is a
  read before any write, so it needs no transaction.
- **i18n exception list:** `i18nTemplateKeyResolutionSpec` keys some dynamic call sites by line number.
  Editing `CoachSessionPackTiersPage.vue` does not touch a listed site today. Re-run the spec anyway.
- **Pitchside:** `feature/pitchside` (after #439) has `data-testid="tier-active-label"` on the tier
  heading. A Pitchside spec that reads the caption text is not known to exist. Grep `e2e/` when
  `feature/pitchside` next takes master.
- **Migration number:** at T1, `ls src/main/resources/db/migration | sort -V | tail -3`, and check open
  PRs for a claimed number. Master is at V226; 169 claims V227+.

### References

- `deferred-work.md`, section "Deferred from: skillars-deferred-168 (...)": the four bullets.
- `skillars-deferred-168-builder-packs-become-the-purchasable-tier.md`: Design, and the "Fractional counts"
  Completion Note.
- `skillars-deferred-169-stripe-retry-double-charge-webhook-ordering-and-card-save-fixes.md` (branch in
  the main checkout): `purchasePack` ordering, RS06 / RS09.
- `docs/deployment/migration-conventions.md` rule 2; `V142__drop_pending_blob_deletions.sql` header.
- PR #436 (body, "Known, low severity"), PR #439, PR #435.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-04: created (ready-for-dev).
