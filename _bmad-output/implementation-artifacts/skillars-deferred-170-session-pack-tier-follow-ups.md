# Story skillars-deferred-170: Session-pack tier follow-ups (drop the old table, tier caption, purchase readiness order, fractional counts)

Status: ready-for-dev

<!-- CREATED 2026-11-04 (/bmad-create-story equivalent, written by hand). Owner-requested bundle of the
     four follow-ups skillars-deferred-168 (PR #436, merged 47180ea7) left in deferred-work.md, section
     "Deferred from: skillars-deferred-168 (builder packs become the purchasable tier, 2026-11-04)".
     Every citation re-read at origin/master 281b8433 (b21ea376 with this story). The section's second
     bullet (Pitchside ONB-06 hand-off) is NOT in this story: it is in review in PR #439 into
     feature/pitchside.
     REVIEWED 2026-11-04 (/mto-story-review against b21ea376, report in story-review.md): 43 citations,
     11 attributions and 11 mechanisms checked; 10 findings applied (3 medium: F1 the purchase unit tests'
     gateway mock answers "not ready", F2 SessionPackPurchaseIT has no HTTP path, F3 169 reclassifies the
     backstop's error code and the check must sit before 169's attempt row; 7 low), plus text fixes:
     IntegerDeserializer is final, the lint WILL flag the port Javadoc, the "keep" key is
     admin.coaches.tierLabel, and the D1 runbook citation had drifted.
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

On completion, delete those four bullets (AC6). Leave the ONB-06 hand-off bullet, which PR #439 (open)
closes on `feature/pitchside`.

Searched and **not** bundled: the pack-preselect bullet (PR #433, merged into `feature/pitchside`), the
declined-card message bullet (PR #435, merged into `feature/pitchside`), and
`V184`'s deferred drop of `main.video_approval_requests` (same kind of task, but a different module).

## The problem (verified at b21ea376, parent origin/master 281b8433)

1. **Dead table.** `marketplace.session_packs` (`V138__baseline_schema.sql:1628`) has no reader or writer
   in `src/main`. skillars-deferred-168 removed `SessionPack`/`SessionPackRepository`, and `V226` copied its
   purchasable rows into `payment.session_pack_tiers`. What is left:
   - The frontend i18n key `marketplace.duplicateSessionPackCount` (en-US `index.js:393`, fr-FR `:397`,
     de-DE `:920`), with nothing that renders it. `en-US/index.js:398` names it in a comment as an
     example; reword that comment.
   - `docs/dev-docs/database/index.html:72` still lists `session_packs` under `marketplace`.
   - Two docs still promise a later drop: `docs/dev-docs/marketplace/index.html:126` ("... is dropped by
     a later migration") and `docs/deployment/provisioning-checklist.md:171` (V226's entry: "stays one
     release").
   - A Javadoc mention in `CoachSessionPackPort.java:12`, written in the past tense.
   - `BuilderPackTierBackfillIT` (`src/test/java/.../db/BuilderPackTierBackfillIT.java:69`) INSERTs into
     `marketplace.session_packs` and then runs V226's SQL. **It breaks the moment the table is dropped.**
2. **Doubled caption.** `CoachSessionPackTiersPage.vue` shows `tierName(tier)` as the heading (`:29`,
   `:77`), then the caption `t('payment.sessionPack.tierLabel', { label: tierName(tier), count, price })`
   (`:32-36`, `:80-84`). `tierLabel` is `'{label} — {count} sessions for €{price}'` (en-US `:2391`, de-DE
   `:1618`, fr-FR `:2044`). Every tier therefore names itself twice: "Season 10" / "Season 10 — 10 sessions
   for €380". A label-less tier reads "5 sessions" / "5 sessions — 5 sessions for €200". `tierLabel` has no
   other caller (grep over `src/frontend/src`). On the same page, the sold-packs list renders
   `{{ pack.tierLabel || '—' }}` (`:135`, fed `tier.getLabel()` by `CoachSoldPackQueryService.java:89`), so
   every pack bought from a label-less tier (each V226 backfill, each unlabelled builder tier) reads
   "— · 3 of 5 remaining".
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
   Checked against the pinned jackson-databind 2.21.7 (`pom.xml:95`): number tokens `2.5`, `2.0` and
   `2.9e0` all bind as `2`; the string `"2"` binds as `2`; the strings `"2.5"` and `"2.0"` are **already
   rejected** (`InvalidFormatException`). So the hole is fractional number tokens only.
   Only the UI blocks fractions (`step="1"`, `packErrorKey` in `src/frontend/src/utils/sessionPackForm.js`),
   and it never sends a float or a string (`packsForRequest`; `v-model.number` + `Number.isInteger` at
   `CoachSessionPackTiersPage.vue:325`).
   skillars-deferred-168 recorded this as a known deviation in its Completion Notes ("Fractional counts
   are still truncated server-side").

## Decisions (defaults pinned here; the owner may overturn any at /mto-story-review)

- **D1: drop the table now, not "a release later".** Rule 2 of `migration-conventions.md` protects an
  old-release pod that still reads a table while a new release drops it. There has still never been a
  production deploy: `docs/deployment/migration-rebaseline.md:137` ("no production deploy has ever
  happened"), `runbook.md:691` ("prod is not live yet"), `provisioning-checklist.md:160-172`; no commit
  since 2026-09-16 says otherwise. (The ledger's own citation, `runbook.md:598-613` at
  `deferred-work.md:2321`, has drifted and no longer says this.) The precedent is `V142__drop_pending_blob_deletions.sql`'s header (owner
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
   `src/main/resources` (skipping `db/migration`, `MigrationLint.java:994`) for `\bsession_packs\b` in
   whole file bodies, comments included. It **will** flag `CoachSessionPackPort.java:12`'s Javadoc, the
   only such file. Reword it ("the profile builder's former pack table"). Do not add an
   `allow-drop-reference-scan` marker. Copy V142's header shape, not its plain `SET lock_timeout` line.
2. **`BuilderPackTierBackfillIT`**: delete it. It tests a one-shot backfill whose source table no longer
   exists. In the migration header, record that V226's behaviour was pinned by it until this drop. Its
   coverage is not recoverable without the table, and recreating the table in a test schema would only
   test V226 against a fake.
3. **AC2 caption**: rename the key to `payment.sessionPack.tierSummary` = `'{count} sessions for €{price}'`
   (de-DE `'{count} Sitzungen für €{price}'`, fr-FR `'{count} séances pour {price} €'`) and drop
   `payment.sessionPack.tierLabel` (the `'{label} — ...'` string; it exists once per locale). Leave
   **`admin.coaches.tierLabel: 'Tier'`** alone (en-US `:1466`, de-DE `:1919`, fr-FR `:1367`, used at
   `AdminCoachEnforcementPage.vue:139`): same leaf name, different block. The page:
   - a tier **with** a label: heading = label, caption = `tierSummary`;
   - a tier **without** a label: heading = `tierSummary`, no caption line;
   - the same rule for the history list;
   - the sold-packs list (`:135`): `pack.tierLabel || t('marketplace.sessionsFallbackLabel', { count:
     pack.sessionCount })` instead of `'—'`. `CoachSoldPackDto.sessionCount` (`CoachSoldPackDto.java:22`)
     is the tier's count, or `0` when the tier row is missing (`CoachSoldPackQueryService.java:89-90`).
     Keep `'—'` for that `0` case rather than rendering "0 sessions".

   `tierName` then reads `tier.label || tierSummary(tier)`. `marketplace.sessionsFallbackLabel` stays: the
   public profile uses it (`SessionPackPricingDisplay.vue:48`, rendered at `CoachPublicProfilePage.vue:318`),
   and so does the sold-packs list above.
4. **AC3 order**: add
   `if (!paymentGateway.isCoachPaymentReady(tier.getCoachId())) throw new PaymentGatewayException("payment.coachStripeNotConfigured");`
   - **Where, after 169 (the expected case):** in 169's new-attempt gate block, after its replay lookup
     ("look up before you gate", 169 story `:562-563`) and **before its attempt row is inserted**. The
     attempt row is a write, so a check after it would break "refused before any write". A replay of an
     existing key skips the gate, as 169 designs it, and is answered from its recorded outcome.
   - **Where, if 169 is abandoned:** right after `requireBookableCoachStatus` (`:135`) and before the
     `stripeCustomerRepository.findById` snapshot (`:137`). Both line numbers are pre-169.
   - It is the same predicate `BookingService.requireCoachPaymentReady` uses (`PaymentGateway.java:134-135`;
     `StripePaymentGateway.java:601-605`, identical to `resolveCoachStripeAccountId:631-636`). Do not call
     into `BookingService` (its method is package-private, and payment would gain a booking dependency for
     one line).
   - Keep the gateway's own fail-fast in `charge()` as the backstop for a coach who loses readiness between
     the two checks. **After 169 that backstop no longer reaches the client as `coachStripeNotConfigured`:**
     169 AC1b (its story `:200-207`) rethrows any plain `PaymentGatewayException` from `chargeAndCapture`
     as a pack-service subclass with its own no-charge code (e.g. `payment.noChargeMade`). That is
     acceptable (no money moved, and the page says so); do not special-case it. The page's
     `coachStripeNotConfigured` message covers the early check only.
   - On the purchase page, show `payment.error.coachStripeNotConfigured` (all three locales, en-US `:2327`)
     when the 422's `errorMsg.errorKey` is that code. Use whatever error reader is there after 169 (and
     #435's `errorHandler.js`, if it has reached master, which may already map the key). If neither exists,
     read `e.response.data.errorMsg.errorKey`, as `BookingRequestPage.vue:644/656` does.
   - **Existing unit tests:** `SessionPackPaymentServiceTest` (`@Mock PaymentGateway` `:54`, strict
     `MockitoExtension` `:46`), `PackPriceLockedOnPurchaseTest` and `SessionPackInputBoundsTest` mock the
     gateway, so an unstubbed `isCoachPaymentReady` answers `false`. Every purchase test past the coach
     gate would then throw. Add `lenient().when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true)`
     in each `@BeforeEach`, like the `lenient()` stub at `SessionPackPaymentServiceTest:72`. Use `lenient`
     because the ownership and age tests throw before the check. ITs are unaffected
     (`StubPaymentGateway.java:97` returns `true`); an IT that needs "not ready" must override it.
5. **AC4 strict count**: add `StrictIntegerDeserializer` (`infrastructure/validation`, next to
   `PhoneNumberDto`, whose nested `PhoneNumberDeserializer` is the only `@JsonDeserialize` in src/main;
   that one is applied at type level, this one goes on record components).
   - **Base class: `StdScalarDeserializer<Integer>`.** `NumberDeserializers.IntegerDeserializer` is
     `public final` in 2.21.7 (checked with `javap`) and cannot be extended.
   - For every token except `VALUE_NUMBER_FLOAT`, delegate to the inherited `_parseInteger(p, ctxt,
     Integer.class)`, so ints, `null` and today's string handling (`"2"` accepted, `"2.5"` already
     rejected) are unchanged.
   - Any `VALUE_NUMBER_FLOAT` token (`2.5`, `2.0` and `1e1` too, on purpose) fails through
     `ctxt.handleWeirdNumberValue(...)`, i.e. an `InvalidFormatException`. Do **not** fail with a
     definition-style error: Spring maps `InvalidDefinitionException` to `HttpMessageConversionException`,
     not `HttpMessageNotReadableException`, and the 400 below would be lost.
   - Annotate both `sessionCount` record components with
     `@JsonDeserialize(using = StrictIntegerDeserializer.class)`. Jackson reads it from the propagated
     field, accessor or parameter.
   - The response is `ApiAdvice.httpMessageNotReadableExceptionHandler` (`:486-491`): 400
     `validation.badRequest`, with no field error. No package advice intercepts it first (`PaymentApiAdvice`
     handles only `PaymentGatewayException`). That is acceptable for an API-only path the UI never
     reaches; state it in the Javadoc.
   - Out of scope, one line in Completion Notes: `ProfileBuilderStep3Request.sessionDurationMinutes`
     (`:26`, boxed `Integer`) truncates the same way. Leave it.

## Acceptance Criteria

**AC1: `marketplace.session_packs` is gone.**
- The new migration drops the table as in Design 1. `MigrationConventionLintTest` passes with the
  marker in place and fails if the marker is removed (check by hand once; do not commit a failing
  fixture).
- `BuilderPackTierBackfillIT` is deleted.
- The `CoachSessionPackPort.java:12` Javadoc no longer names `session_packs` (Design 1). The lint passes
  without an allow marker.
- The i18n key `marketplace.duplicateSessionPackCount` is removed from all three locales (and the
  `en-US/index.js:398` comment reworded). `localeKeyParitySpec` and `i18nTemplateKeyResolutionSpec` stay
  green.
- Docs:
  - `docs/dev-docs/database/index.html:72` no longer lists `session_packs`.
  - `docs/dev-docs/marketplace/index.html:126` says the table was dropped (by `V<n>`), not "by a later
    migration".
  - `docs/deployment/provisioning-checklist.md`: V226's entry (`:171`, "stays one release") is corrected;
    the drop gets its own line next to it; and both V226 and the drop join the "Recent migrations assume
    'prod is not live'" list at `:160` (it ends at V224 today), because D1 rests on that assumption.
  - The search index is rebuilt (`node docs/dev-docs/tools/build-search-index.mjs`) and `check-docs.mjs`
    passes.
- A fully migrated test database has no `marketplace.session_packs`. Add one assertion to an existing IT
  that already queries `information_schema.tables` on the base context (e.g. `ShedLockConfigIT.java:35`,
  or `StatusScopedPartialIndexConventionIT`). Do not add a new context or mock.

**AC2: each tier names itself once.**
- On `CoachSessionPackTiersPage`, a labelled tier shows its label, then "10 sessions for €380". A
  label-less tier shows "5 sessions for €200" and no second line. This applies to both the current offer
  and the history list.
- In the sold-packs list, a pack from a label-less tier shows "5 sessions", not "—" (Design 3).
- `CoachSessionPackTiersPageSpec` asserts both shapes and that the label text appears exactly once.
  - **The global test i18n cannot catch this as is.** `test/vitest/setup-file.js:18-26` installs
    `createI18n` with `messages: { 'en-US': {} }`, so `t(key, params)` renders the bare key and drops
    `label`. Mount this spec with its own i18n instance carrying the real en-US `tierSummary` and
    `sessionsFallbackLabel` messages, so the rendered text is real.
  - Mutation check: restore `label: tierName(tier)` in a `{label}`-bearing caption, and the spec goes red.
    Run it once by hand and record that in the Completion Notes.
  - The existing test at `CoachSessionPackTiersPageSpec.js:110-119` ("titles a label-less tier ... with
    the localized 'N sessions' fallback") expects `marketplace.sessionsFallbackLabel` for a label-less
    tier's heading. Under Design 3 that heading is `tierSummary`, so rewrite it to the new rule. Keep its
    "no `null` in the text" assertion.
- The old `'{label} — ...'` string (`payment.sessionPack.tierLabel`) is removed from all three locales;
  `admin.coaches.tierLabel` is untouched.

**AC3: a purchase from an unready coach is refused before any write.** *(after 169 merges, D2)*
- A `purchasePack` for an active tier whose coach has no COMPLETE + charges-enabled Connect account
  throws `payment.coachStripeNotConfigured`. It then has neither called
  `paymentGateway.createStripeCustomer` nor saved a `stripe_customers` row (first-time payer), nor
  changed `stripe_payment_method_id` (returning payer who sent a card). It has also not called
  `chargeAndCapture`.
  After 169 this also means no attempt row is written (Design 4).
- This is proved at three levels:
  - **`SessionPackPaymentServiceTest`** (Mockito: `verify(..., never())` on `createStripeCustomer`,
    `stripeCustomerRepository.save` and `chargeAndCapture`). The existing purchase tests get the default
    `lenient()` "ready" stub (Design 4).
  - **`SessionPackPurchaseIT`**, which calls the service directly (`:47`, `:64`, `:88`), not over HTTP:
    with the stub gateway overridden to answer "not ready" for this coach, `purchasePack` throws
    `payment.coachStripeNotConfigured` and no `payment.stripe_customers` row exists for the payer. If the IT
    base exposes the gateway only as `StubPaymentGateway` (always ready) and not as a spy, add the override
    the cheapest way that adds no new Spring context (the context-cache gate). Record which.
  - **The 422 shape over HTTP** (`errorMsg.errorKey == payment.coachStripeNotConfigured`) is asserted in
    `SessionPackPaymentResourceWebMvcTest`, with the service mocked to throw.
- The purchase page shows "This coach has not completed their payment setup yet" for that 422, not
  "Purchase failed". `SessionPackPurchasePageSpec` covers it.
- The gateway's own check in `charge()` stays (backstop). After 169 its failure reaches the client under
  169's no-charge code, which is intended (Design 4).

**AC4: fractional session counts are rejected, not truncated.**
- `PUT /api/marketplace/coaches/me/profile/steps/3` with `sessionPacks: [{ sessionCount: 2.5, ... }]`,
  and `POST /api/payment/coaches/me/session-pack-tiers` with `sessionCount: 2.5`, each answer 400 and
  write nothing. The active tier is unchanged, and no pricing row is updated by the step-3 call.
- `sessionCount: 2` and `sessionCount: null` behave as today: accepted, or rejected by `@NotNull`.
- A unit test of the deserializer covers: `2` → 2; `2.5`, `2.0` and `1e1` → `InvalidFormatException`;
  `null` → null; `"2"` → 2; `"2.5"` → still rejected.
- HTTP tests, where Jackson actually runs. `SessionPackPaymentServiceIT` calls the service, so it cannot
  test this.
  - **Tier POST:** `SessionPackPaymentResourceWebMvcTest` (already posts the endpoint, `:410-430`; real
    Jackson converter; assert 400 and that the mocked service was never called).
  - **Step 3:** `CoachProfileSelfEditIT` or `CoachProfileBuilderIT` (assert 400 and that the active tier
    and pricing rows are unchanged).
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

- [ ] T1 (AC1): migration; delete `BuilderPackTierBackfillIT`; reword the port Javadoc (required);
  remove the i18n key and reword the `:398` comment; update the database and marketplace docs, the
  provisioning checklist (V226 entry, new line, the "prod is not live" list) and the search index; add the
  schema assertion.
- [ ] T2 (AC2): `tierSummary` key in three locales; page heading and caption rule; sold-packs fallback;
  spec with a local i18n instance; rewrite the `:110-119` test; mutation check.
- [ ] T3 (AC4): `StrictIntegerDeserializer` (`StdScalarDeserializer<Integer>`) + unit test; annotate both
  fields; WebMvc test for the tier POST, IT for step 3.
- [ ] T4 (AC3, **after 169 merges**): rebase; add the readiness check in 169's new-attempt gate, before
  its attempt row; default `lenient()` stub in the three unit-test classes; unit test, service-level IT,
  WebMvc 422 test; purchase-page message + spec.
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
  heading. Its screen object defines `activeTierLabel` (`coach.ts:142`), but no spec calls it or reads the
  caption text (review sweep, 2026-11-04). Expect a merge conflict on `CoachSessionPackTiersPage.vue` when
  `feature/pitchside` next takes master.
- **AC3 mainly protects direct API callers.** The shipped purchase page requires a saved card and sends no
  `paymentMethodId` (`SessionPackPurchasePage.vue:136`, `:184`), so for UI users the Stripe customer is
  created by `/setup-intent` or `/save-payment-method`, not by `purchasePack`.
- **Replays are unchanged by AC3.** Today `charge()` already throws for an unready coach before the
  `alreadyRecorded` lookup (`SessionPackPaymentService.java:144` vs `:148`). After 169 the lookup precedes
  the gate.
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
- 2026-11-04: /mto-story-review findings applied (story-review.md, HEAD b21ea376): F1-F10 and the text
  fixes listed in the header comment. Still ready-for-dev.
