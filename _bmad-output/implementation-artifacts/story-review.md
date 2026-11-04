# Story review: skillars-deferred-170-session-pack-tier-follow-ups

- **Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-170-session-pack-tier-follow-ups.md` (status `ready-for-dev`)
- **Audit date:** 2026-11-04
- **HEAD verified against:** `b21ea376` "docs(story): create skillars-deferred-170 — session-pack tier follow-ups from deferred-168". Its parent is `281b8433` = origin/master (#434).
- **The story says it was verified at `0ee68f07`.** That is stale. #434 has landed since and changed `BookingRequestPage.vue`. Every citation below was re-checked at `b21ea376`, not at the SHA the story names.
- **Method:** four parallel read-only layers (citations, ledger/precedents, mechanisms, corner cases), then my own skeptical re-read of every finding (Step 4b).
- **Also checked:** Jackson behaviour was checked against the pinned jar, jackson-databind 2.21.7 (`pom.xml:95`), using `javap` and a jshell run. The skillars-deferred-169 story was read at its branch tip `05b8bea8`.

## 1. Citation verification (43 citations)

| Citation | Verdict | Evidence / correction |
|---|---|---|
| `V138__baseline_schema.sql:1628` | MATCH | `CREATE TABLE marketplace.session_packs (` |
| i18n `duplicateSessionPackCount` en-US `:393`, fr-FR `:397`, de-DE `:920`; en-US `:398` comment | MATCH | key lines and `// ... (e.g. duplicateSessionPackCount above,` |
| `docs/dev-docs/database/index.html:72` | MATCH | marketplace row lists `session_packs` |
| `CoachSessionPackPort.java:12` | MATCH | `{@code marketplace.session_packs} table that nothing could buy.` |
| `BuilderPackTierBackfillIT.java:69` | MATCH | `INSERT INTO marketplace.session_packs (...)` |
| `CoachSessionPackTiersPage.vue:29`, `:77`, `:32-36`, `:80-84` | MATCH | heading `tierName(...)`; caption `t('payment.sessionPack.tierLabel', { label: tierName(...), ... })` |
| `tierLabel` en-US `:2391`, de-DE `:1618`, fr-FR `:2044` | MATCH | `'{label} — {count} sessions for €{price}'` etc. |
| "`payment.sessionPack.tierLabel: 'Tier'`" en-US `:1466` (Design 3) | **WRONG PATH** | `:1466` is **`admin.coaches.tierLabel`** (used at `AdminCoachEnforcementPage.vue:139`). `payment.sessionPack.tierLabel` exists once per locale and is the `'{label} — …'` string. See F6. |
| `SessionPackPaymentService.java:98`, `:126-130`, `:135`, `:137`, `:140`, `:144`, `:440-455` | MATCH | quoted by Layer 1; re-read by me at `:88-158`, `:440-455` |
| `StripePaymentGateway.java:113-116`, `:631` | MATCH | `resolveCoachStripeAccountId(coachId);` first in `charge()`; throws at `:635` |
| `BookingService.java:599`, `:415`; `BookingBatchService.java:156` | MATCH | package-private `requireCoachPaymentReady`; its two callers |
| `SessionPackPurchasePage.vue:189` | MATCH | `e?.response?.data?.message ?? t('booking.packs.purchaseError')` |
| `application.yaml:134-137` | MATCH | jackson block sets only `time-zone` and `WRITE_DATES_AS_TIMESTAMPS` |
| `ProfileBuilderStep3Request.java:42`, `CreateSessionPackTierRequest.java:27` | MATCH | boxed `Integer sessionCount` |
| `BookingRequestPage.vue:656` | MATCH (no drift despite #434) | `} else if (errorKey === 'payment.coachStripeNotConfigured') {` |
| `CoachPublicProfilePage.vue:617` | MATCH | `if (!coachHasPerSessionPrice.value && !coachHasPacks.value) return 'unbookable'` |
| `ApiAdvice.java:486` | MATCH (minor) | `:486` is the `@ExceptionHandler`; the method is at `:488` |
| en-US `:2327` `payment.error.coachStripeNotConfigured` | MATCH | all three locales have it |
| `deferred-work.md:2321` → `runbook.md:598-613` (D1) | **DRIFTED** | `runbook.md:598-613` is now "Config change appears to have no effect". The statement lives at `docs/deployment/migration-rebaseline.md:137` ("no production deploy has ever happened") and at `runbook.md:691` ("prod is not live yet"). |
| `V142__drop_pending_blob_deletions.sql` header | MATCH | owner decision 2026-09-16 (skillars-deferred-117), drop now because nothing was ever deployed. Note: it uses plain `SET lock_timeout`; Design 1's `SET LOCAL` is the current convention, so it is right not to copy that line. |
| `PhoneNumberDto.java:24` as the `@JsonDeserialize` precedent | MATCH, partly | It is the only `@JsonDeserialize` in src/main. It is applied at TYPE level, not on a record component, so it is not an exact precedent. |
| `sessionPackForm.js` `packErrorKey`; `ProfileBuilderStep3.vue` `step="1"` | MATCH | `:29` / `:36 Number.isInteger`; `:49` |

## 2. Ledger and precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| The four ledger bullets map to AC1–AC4 | ledger `:3558-3566` (tagged `[PICKED UP by skillars-deferred-170]`) | SUPPORTED |
| ONB-06 is "the ledger's sixth bullet there", "done in PR #439" | ledger; `gh pr view 439` | **WRONG / OVERSTATED.** It is the **second** of five bullets in that section. #439 is **OPEN** (in review, not merged). |
| #436 removed `SessionPack`/`SessionPackRepository`; V226 copied rows | `git show --stat 47180ea7`; V226 header; ledger `:3558` | SUPPORTED |
| #436's body said an unready coach's pack "can be bought" | `gh pr view 436` body ("Known, low severity") | SUPPORTED (that sentence is quoted; the ledger corrects it) |
| deferred-168 Completion Notes record the fractional-count deviation | story file `:406`; Javadoc `ProfileBuilderStep3Request.java:37-40` | SUPPORTED |
| No production deploy has happened | ledger `:2321` (stale citation, see §1); `migration-rebaseline.md:137`; `runbook.md:691`; `provisioning-checklist.md:160-172`; git log after 2026-09-16 (latest #428 still says prod not live) | SUPPORTED (citation drifted) |
| V142 precedent (deferred-117) | V142 header; commit e374aa6c (#197) | SUPPORTED. V142's marker named a separate *marker* migration (V141); here V226 plays that role, which the lint accepts (`preparedVersion < version`). |
| Rule 2 and the lint marker / reference scan | `migration-conventions.md:58-85`; `MigrationLint.java:97-99`, `:197`, `:994` (skips `db/migration`) | SUPPORTED |
| 169 rewrites `purchasePack` ordering and the purchase page's error/retry handling; claims V227+ | 169 story at `05b8bea8` (AC1a/AC1b, AC8, Task 2; V227+ at `:24`, `:96`, `:155`) | SUPPORTED. **169 adds no readiness check**, so AC3 is not redundant. |
| PR #433 / #435 / #439 | gh | #433 and #435 are **MERGED into `feature/pitchside`** (the story calls them "on `feature/pitchside`", which is fine). #439 is open. |
| V184 defers the `main.video_approval_requests` drop | `V184:16-18` | SUPPORTED |

## 3. Mechanistic claims

1. **`purchasePack` has no transaction; the customer writes commit before the charge and survive its failure.** TRUE.
   - The Javadoc at `:78` says it is "Not annotated @Transactional ..."; the controller is not transactional either, and `open-in-view: false`.
   - `StripeCustomerRepository.save` commits in its own transaction.
2. **No Stripe charge call happens for an unready coach.** TRUE. `charge()`'s first statement is `resolveCoachStripeAccountId` (`:116`), before currency resolution and before the PaymentIntent builder. The only Stripe call earlier in the flow is `createStripeCustomer`, from `purchasePack:140`.
3. **`isCoachPaymentReady` is on the `PaymentGateway` interface and uses the same predicate.** TRUE (`PaymentGateway.java:134-135`; `StripePaymentGateway.java:601-605` against `:631-636`, both `COMPLETE && chargesEnabled`). `BookingService.requireCoachPaymentReady` is a package-private instance method.
4. **The 422 shape is `{helpCode, errorMsg:{errorKey, message}, fieldErrors}`.** TRUE. `PaymentApiAdvice` covers `payment.api` (where `SessionPackPaymentResource` lives) and handles only `PaymentGatewayException`.
5. **The purchase page always shows the generic text.** TRUE. The axios error interceptor rejects with the original error (`boot/axios.js:268`), so `e.response.data` is the `ErrorDto`, which has no `message`.
6. **Jackson truncates 2.5 → 2 by default.** TRUE.
   - jackson-databind 2.21.7, `ACCEPT_FLOAT_AS_INT` defaults on. No customizer, `ObjectMapper` bean or coercion config exists in src/main.
   - jshell results: `2.5`, `2.0`, `2.9e0` and `"2"` all bind to 2. `"2.5"` and `"2.0"` are **already rejected** with `InvalidFormatException`.
7. **Design 5's "extends `NumberDeserializers.IntegerDeserializer`".** **FALSE: that class is `public final`** (re-checked with `javap`). `StdScalarDeserializer<Integer>` / `StdDeserializer<Integer>` works, and `_parseInteger(p, ctxt, Integer.class)` is callable for non-float tokens. `@JsonDeserialize` on a record component is honoured (javac propagates it to the field, accessor and parameter).
8. **The resulting response is `ApiAdvice`'s 400 `validation.badRequest`.** TRUE for `InvalidFormatException`/`MismatchedInputException`, which arrive as `HttpMessageNotReadableException`. A deserializer that fails with a *definition* error (`InvalidDefinitionException`) would surface as `HttpMessageConversionException` instead, so the deserializer must raise its failure through `ctxt.handleWeirdNumberValue` / `InvalidFormatException`. No package advice intercepts it first.
9. **Endpoints:** TRUE. Step 3 is `PUT /api/marketplace/coaches/me/profile/steps/3` (`ProfileBuilderResource`). The tier POST is `POST /api/payment/coaches/me/session-pack-tiers` (`SessionPackPaymentResource:138`, 201).
10. **`payment.sessionPack.tierLabel` has only the two page callers; `sessionsFallbackLabel` stays because the public profile uses it.** TRUE (`SessionPackPricingDisplay.vue:48`, rendered by `CoachPublicProfilePage.vue:318`).
11. **The lint "may" flag `CoachSessionPackPort.java:12`.** It **will**:
    - the scan reads whole files, Javadoc included, and finds `\bsession_packs\b`, which matches here;
    - it is the only src/main file outside `db/migration` that does.

    The rewording is required work, not an "if".

## 4. Corner cases, false assumptions, missed flows (survived Step 4b)

- **F1 (medium, AC3): existing purchase unit tests go red.**
  - `SessionPackPaymentServiceTest` (`@Mock PaymentGateway`, `:54`; strict `MockitoExtension`, `:46`; 21 `purchasePack(` calls) and `PackPriceLockedOnPurchaseTest` both get `false` from an unstubbed `isCoachPaymentReady`. Every test that gets past the coach-status gate would then throw `coachStripeNotConfigured`.
  - Fix: `lenient().when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true)` in `@BeforeEach`, following the existing `lenient()` stub at `:72`.
  - `SessionPackInputBoundsTest` also mocks the gateway; check it.
  - ITs are unaffected (`StubPaymentGateway:97` returns `true`).
- **F2 (medium, AC3): `SessionPackPurchaseIT` calls the service directly, not over HTTP** (`:47`, `:64`, `:88`). The "422 with `errorMsg.errorKey`" assertion cannot live there as written.
  - Option 1: assert the code and the absence of a `stripe_customers` row at service level in that IT.
  - Option 2: put the HTTP shape in `SessionPackPaymentResourceWebMvcTest` (service mocked to throw).
  - Either way the IT must override the stub's always-ready answer, e.g. `doReturn(false)` on the shared spy, if the base class exposes one. Confirm at dev time.
- **F3 (medium, AC3 and 169): after 169, the backstop no longer answers `coachStripeNotConfigured`.**
  - 169 AC1b (its story `:200-207`) reclassifies any plain `PaymentGatewayException` from `chargeAndCapture` as a pack-service subclass with its own code (e.g. `payment.noChargeMade`).
  - So the page message for `coachStripeNotConfigured` covers only the new early check. A coach losing readiness between the two checks would get the no-charge message. That is acceptable, but the story should say so.
  - 169 also orders "look up before you gate" (`:562-563`): the replay lookup runs first, and the new-attempt gates run before the attempt row is inserted. AC3's check belongs in that gate block, or "refused before any write" is false, because 169's attempt row is a write.
- **F4 (low, AC2): the "label appears once" mutation check cannot go red under the default setup.** `test/vitest/setup-file.js:18-26` installs `createI18n` with `messages: { 'en-US': {} }`, so `t(key, params)` renders the bare key and ignores `label`. The spec needs real messages for `tierSummary` (a local i18n instance) or must assert the params passed to `t`.
- **F5 (low, AC2): an existing spec breaks.** `CoachSessionPackTiersPageSpec.js:110-119` expects `marketplace.sessionsFallbackLabel` for label-less tiers. Under Design 3 the page no longer renders it. Rewrite that test.
- **F6 (low, AC2): Design 3 names the wrong key to keep.** See §1: the `'Tier'` key is `admin.coaches.tierLabel`. As written, the instruction keeps and deletes the same path `payment.sessionPack.tierLabel`.
- **F7 (low, AC2, missed flow): the sold-packs list shows "—" for a label-less tier.** `CoachSessionPackTiersPage.vue:135` renders `{{ pack.tierLabel || '—' }}`, fed by `CoachSoldPackQueryService.java:89` (`tier.getLabel()`, null for every V226 backfill and unlabelled builder tier). It is the same naming rule on the third list of the same page; include it in AC2 (use the count-based name).
- **F8 (low, AC1): two more docs promise a later drop.**
  - `docs/dev-docs/marketplace/index.html:126` ("... is dropped by a later migration").
  - `docs/deployment/provisioning-checklist.md:171` (V226 entry: "stays one release").

  Also, because D1 relies on prod not being live, the new migration (and V226, which is missing too) belongs in the checklist list at `:160` ("Recent migrations assume 'prod is not live'", which ends at V224).
- **F9 (low, AC4, partly refuted, see §5): the wrong IT is named.** `SessionPackPaymentServiceIT` calls the service, so Jackson never runs. Use:
  - `SessionPackPaymentResourceWebMvcTest` (already POSTs the tier endpoint through MockMvc with real Jackson, `:410-430`) for the tier POST;
  - `CoachProfileSelfEditIT` / `CoachProfileBuilderIT` (HTTP) for step 3.
- **F10 (low, AC4, clarification): fractional strings are already rejected.** Of the deserializer unit test's cases, `"2"` should stay accepted and `"2.5"` already fails today. Exponent floats (`1e1`) are float tokens and will be rejected, which matches the intent. Out of scope but worth a line: `ProfileBuilderStep3Request.sessionDurationMinutes` (`:26`) truncates the same way. The UI never sends floats or strings (`packsForRequest`; `v-model.number` + `Number.isInteger` at `CoachSessionPackTiersPage.vue:325`).
- **Story text errors:**
  - "sixth bullet" should be "second".
  - "done in PR #439" should be "in review in PR #439".
  - "verified at 0ee68f07" should be `281b8433`.
  - The D1 runbook citation should be `migration-rebaseline.md:137`.
  - "extends `IntegerDeserializer`" is impossible (§3.7).
  - "if the lint flags it" should be "the lint will flag it" (§3.11).

## 5. What did not survive re-verification

- **"A replay for a coach who lost readiness would now 422 instead of returning the recorded pack" (Layer 4 hypothesis).** Killed. Today `charge()` already throws before the `alreadyRecorded` lookup (`SessionPackPaymentService.java:144` vs `:148`), so a replay to an unready coach already fails. After 169, the lookup precedes the gates, so a replay skips the new check. The early check changes nothing for replays in either world.
- **"No tier-resource IT exists, so AC4's tier-endpoint test has no home" (F9 as raised).** Partly killed. The claim that only `CoachProfileSelfEditIT` POSTs the tier endpoint over HTTP is wrong: `SessionPackPaymentResourceWebMvcTest:410-430` does, and a WebMvc slice runs the real Jackson converter. The finding survives only as "`SessionPackPaymentServiceIT` is the wrong place".
- **"About 23 tests break" (F1's count).** Downgraded to approximate. I confirmed the mock default and 21 `purchasePack(` calls in one class, but did not count, test by test, which ones pass the earlier gates. The finding stands; the exact number does not matter.
- **"A non-null `stripeAccountId` difference between `isCoachPaymentReady` and `resolveCoachStripeAccountId`" (Layer 3 aside).** Killed. The column is NOT NULL in both the entity and the schema.

## 6. Recommendation

The story's diagnosis holds. Every defect it describes reproduces at `b21ea376`, and the ledger correction about `purchasePack` is right: no PaymentIntent is created for an unready coach. The design needs edits before dev. Confidence by area:

| Area | Confidence | Basis |
|---|---|---|
| AC1 (drop) | high | Lint mechanics read in full; reference sweep done. Apply F8 and the required Javadoc rewording. D1 stays an owner call; its evidence is current, but its citation needs fixing. |
| AC2 (caption) | high on the defect, medium on the test plan | Apply F4–F7. |
| AC3 (readiness) | high on mechanics | Re-read by me. Its sequencing on 169 is right, and F3 makes it more so. Apply F1–F3. 169's final shape is not merged, so the AC3 details must be re-derived on rebase, as D2 already says. |
| AC4 (fractional) | high | Checked against the pinned Jackson jar. Fix the base class (§3.7), the failure type (§3.8) and the test homes (F9). F10 narrows the test cases. |

Not independently re-checked by me: Layer 4's sweep of e2e/ and Pitchside for caption-text assertions (accepted from that layer: none found), and Layer 2's git-log sweep for a production deploy (spot-checked #428 only).
