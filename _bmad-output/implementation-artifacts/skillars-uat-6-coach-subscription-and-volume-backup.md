# Story UAT.6: Coach Subscription Card Collection & Volume Backup Replacement

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **This story bundles the last two unclaimed rows in `uat-readiness-priorities.md` (P0-4, P1 #9) — two structurally unrelated fixes (payment backend/frontend vs. deploy/ops scripting) grouped only because they are the only work left besides P3 (explicitly out of scope, chronically re-deferred).** Do not look for a shared mechanism between AC1-4 and AC5-8; there isn't one. Both decisions were made by Mbah during this story's creation (2026-08-13): **build coach self-serve subscription card collection**, and **replace the broken volume snapshot with file-level backup to Object Storage**.
>
> **The central technical finding that reshapes AC1-4: P0-4 does NOT need a schema change.** `deferred-work.md`'s `skillars-deferred-11` D1 entry and `skillars-uat-5`'s own Dev Notes both assert the blocker is that a coach's id is "a UUID from a different table" than the `BIGINT` `payment.stripe_customers.parent_id` — framed as requiring "a second customer table or a re-keying migration." **This is stale/incomplete.** `CoachProfile` (`marketplace.coach_profiles`) has *both* a `UUID id` (its PK, what `payment.coach_subscriptions.coach_id` FKs to) *and* a `Long userId` column (`user_id`, `nullable=false, unique=true`) — the coach's `BIGINT` id from `main."user"`, the exact same id space `StripeCustomer.parentId` already uses. `payment.stripe_customers` has no FK and no CHECK constraint on `parent_id` — nothing stops a coach's `userId` from being that PK, and `SubscriptionLifecycleIT`'s own test fixture already does exactly this via raw SQL. **This is the identical opaque-id shortcut `skillars-uat-5` used for self-registered players** (see that story's callout) — a coach's own `userId` becomes the key into `payment.stripe_customers`, no migration, no new table. `resolveCoachId()` (present in both `SubscriptionResource.java:127-132` and `SessionPackPaymentResource.java` already) is the existing Long→UUID bridge; nothing new needs inventing there either.
>
> **The real blocker is narrower and purely code-level:** (a) the three generic card-collection endpoints (`/setup-intent`, `/save-payment-method`, `/payment-method`) are gated `HAS_PARENT_OR_PLAYER_ROLE`, not open to `ROLE_COACH`; and (b) `SubscriptionService.subscribeCoach()` reads its Stripe customer id from `PaymentCoachSubscription.stripeCustomerId` — a column **no production code path has ever written**, only ever hand-seeded by test SQL. The player-subscription path (`subscribePlayer`, `SubscriptionService.java:266-301`) already solves this exact problem correctly: it looks up the caller's `StripeCustomer` row directly by their own `userId` at call time, instead of trusting a stale/never-populated column on the subscription row. **AC2 is a straight mirror of `subscribePlayer`'s already-working pattern — copy it, don't redesign it.**

## Story

As a coach,
I want to save a payment card through the same Stripe Elements flow parents and players already use, and subscribe to a paid tier without typing a raw Stripe Payment Method ID,
so that the "coach pays a platform subscription" leg of the UAT journey (P0-4) actually works instead of requiring the coach to know their own `pm_...` id from a Stripe dashboard.

And, unrelated but bundled for dev-time efficiency:

As the operator standing this app up on a Hetzner VPS,
I want the data on the Hetzner Volume (`/opt/skillars/data` — redis, prometheus, loki, grafana, tempo, `acme.json`) backed up somewhere durable,
so that a lost/corrupted volume doesn't mean losing everything except the database (P1 #9 — the volume currently has **no working backup at all**, because the only mechanism ever written, `volume-snapshot.sh`, calls a Hetzner Cloud API endpoint that does not exist).

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`, **P0-4** and **P1 #9** — the only two rows left unclaimed besides P3 (chronically re-deferred, explicitly out of scope for UAT per the doc's own ranking rule). Both required a product/ops decision before they could be scoped as dev work; both decisions were made during this story's creation:

| Decision | Made by | Choice |
|---|---|---|
| Is coach subscription in the UAT script? | Mbah, 2026-08-13 | **Yes — build it.** |
| Volume backup replacement (file-level to Object Storage / Storage Box / accept dumps-only)? | Mbah, 2026-08-13 | **File-level backup to Object Storage**, alongside the existing pg_dump stream. |

Every fact below was re-verified against the tree at `01e812e` (the `skillars-uat-5` merge commit — nothing has landed since) by direct read of the named file, not carried forward from the priorities doc's or the deferred-work ledger's citations, several of which are stale (see callout above and AC4/AC8).

| AC | What it does | Verified current-state anchor |
|---|---|---|
| AC1 | Backend: coach can save a card via the existing generic endpoints | `SessionPackPaymentResource.java:143,163,196` `@PreAuthorize(HAS_PARENT_OR_PLAYER_ROLE)` on `/setup-intent`, `/save-payment-method`, `/payment-method` — no `ROLE_COACH` |
| AC2 | Backend: `subscribeCoach` resolves the payment method from the coach's saved card, not a request-body string | `SubscriptionService.java:102-139` reads `sub.getStripeCustomerId()` (never written by production code) and accepts a raw `paymentMethodId` param that the frontend currently free-types |
| AC3 | Frontend: `CoachSubscriptionPage.vue` drops the raw pm_id text input for the same card-entry UX parents/players get | `CoachSubscriptionPage.vue:117-123` renders a bare `q-input` asking for a Stripe PM id |
| AC4 | Ledger hygiene for the coach-subscription half | `deferred-work.md`'s `skillars-deferred-11` D1/D3 entries are stale (still say `HAS_PARENT_ROLE`, still say a schema change is required) |
| AC5 | New file-level volume backup script + cron | `deploy/backup/volume-snapshot.sh` POSTs to a Hetzner Cloud API endpoint that does not exist (`/v1/volumes/{id}/actions/create_snapshot`) — confirmed against 4 independent sources by `skillars-uat-3`; every cron run since has failed; no volume backup of any kind has ever existed |
| AC6 | `prune-backups.sh` grows a third stream, drops the dead one | `prune-backups.sh` has exactly two branches: pg-dump pruning (works) and Hetzner-snapshot pruning (correct against the real Images API, but permanently zero-match because nothing ever produces a snapshot) |
| AC7 | Restore path + docs | `restore-from-snapshot.sh` and `backup-restore.md` Section B assume snapshots that never existed; `restore-from-snapshot.sh` also has a pre-existing gap (no redis/traefik ownership restoration) worth fixing while rewriting it |
| AC8 | Ledger hygiene for the volume-backup half | Closes `skillars-uat-3` D1 and everything explicitly gated on it (2 duplicate findings + D16 + D10), plus `uat-readiness-priorities.md`'s Story claims / Still unclaimed / Suggested sequence |

**Explicitly out of scope for this story** (record as new deferred items under AC4/AC8, do not build):
- **`payment.coach_subscriptions.stripe_customer_id` column cleanup.** AC2 makes this column permanently dead (never written before this story, never read after it — `subscribePlayer`'s equivalent table, `payment.player_subscriptions`, never had this column at all, confirming it was always the wrong design). Dropping it needs a migration; leaving one dead nullable column costs nothing and is not this story's job.
- **Coach Stripe `Customer` metadata correctness.** `StripePaymentGateway.createStripeCustomer(Long parentId)` tags the Stripe-side customer with `metadata.parentId = <id>` regardless of caller role (`StripePaymentGateway.java:126-137`) — a coach's Stripe dashboard entry will say "parentId" in its metadata. Cosmetic, Stripe-dashboard-only, not fixed here; record as a deferred item.
- **A dedicated `SubscriptionResourceIT`.** No REST-layer IT exists for `SubscriptionResource` today (confirmed — only the service-layer `SubscriptionLifecycleIT`). Building full HTTP-level coverage for all 8 subscription endpoints is a separate, larger testing initiative; this story's testing surface is exactly what AC1-2 touch (see AC2's Testing section).
- **Redis AOF consistency during a live file-level backup.** AC5's archive includes `data/redis` while Redis may be actively appending to its AOF file. AOF is append-only under normal operation, so a `tar` mid-write should capture a valid-if-slightly-stale file in the common case, but this has not been validated against a live Redis instance in this codebase. Not blocking — flag in Dev Notes, do not add `BGREWRITEAOF` coordination or Redis-aware locking, which is a bigger change than this story's scope.
- **A live execution of the new backup/restore scripts against real Hetzner Object Storage.** Same posture as `skillars-uat-3` AC6/D10: `shellcheck`-clean and internally consistent is the bar this environment can verify; the first production run must be `--dry-run` and the first real restore must be a drill, logged in `drill-log.md`. Record as **STILL OPEN** in Dev Agent Record if not run.
- **Migrating `secrets-reference.md`/`.env.example`'s `HCLOUD_TOKEN` documentation for `apply-firewall.sh`.** `apply-firewall.sh` (`deploy/firewall/apply-firewall.sh`) also reads `HCLOUD_TOKEN`, but explicitly as a **local, operator-machine** env var, never from `/opt/skillars/.env` on the server (its own header says "Run from your LOCAL machine (not the server)"). AC7 removes `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from the **server's** `.env.example`/`secrets-reference.md` rows only — do not touch `deploy/firewall/README.md`'s or `apply-firewall.sh`'s own documentation of `HCLOUD_TOKEN`, which is a distinct, still-valid local use.

## Acceptance Criteria

### AC1 — Backend: a coach can save a card via the existing generic card-collection endpoints

1. `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java:38` (immediately after `HAS_PARENT_OR_PLAYER_ROLE`) — add:
   ```java
   public static final String HAS_PARENT_PLAYER_OR_COACH_ROLE =
       "hasRole('ROLE_PARENT') or hasRole('ROLE_PLAYER') or hasRole('ROLE_COACH')";
   ```
2. `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java` — widen `@PreAuthorize` on exactly three endpoints from `SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE` to `SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE`:
   - `:143` (`POST /setup-intent`, `createSetupIntent`)
   - `:163` (`POST /save-payment-method`, `savePaymentMethod`)
   - `:196` (`GET /payment-method`, `getSavedPaymentMethod`)
   **No other line in any of these three methods changes.** `securityUtil.getCurrentCoachUserId()` (the misleadingly-named, role-agnostic current-user-id resolver already confirmed used identically across parent/coach/admin contexts elsewhere in this codebase) already returns the right id for a coach caller. `StripeCustomer.parentId` (`StripeCustomer.java:21-23`, bare `@Id`, no FK, no CHECK) will accept a coach's own `userId` exactly as it already accepts a self-registered player's, per the callout above — `SubscriptionLifecycleIT`'s existing fixture already proves this with raw SQL, at `parent_id = 91001` (a coach's test userId).
3. Do **not** touch `/stripe/config` (`SecurityConstants.IS_AUTHENTICATED`, already open to any role) — no change needed there.
4. **A pre-existing test will start failing and must be updated, not left broken**: `SessionPackPaymentResourceIT.java:126-131`, `getSavedPaymentMethod_coachRole_returns403`, currently asserts `@WithMockUser(roles = "COACH")` hitting `GET /payment-method` returns `403`. After this AC, it returns `200`/`204`-shaped success. Rename/rewrite this test to assert success (mirror the existing player success-path tests just below it in the same file, e.g. around `:140+`, which the file's own comment marks "UAT.5 AC2" — same pattern, coach role instead of player).

**Testing:**
- `SessionPackPaymentResourceIT` — flip `getSavedPaymentMethod_coachRole_returns403` to a success-path test (mock the repository the same way the existing player success tests do). Add coach-role success cases for `/setup-intent` and `/save-payment-method`, mirroring the existing player cases in the same file.
- Mutation check: reverting the `@PreAuthorize` widening on any one of the three endpoints back to `HAS_PARENT_OR_PLAYER_ROLE` must fail a named test asserting `403` — not just "some test fails" (same bar every prior UAT story's mutation checks have held to).

### AC2 — Backend: `subscribeCoach` resolves the saved card, not a raw request field — mirror `subscribePlayer` exactly

**`SubscriptionService.subscribePlayer` (`SubscriptionService.java:266-301`) already solves this correctly for players. Do not invent a new pattern — copy this one.**

1. `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:102` — change the method signature:
   ```java
   // Before:
   public CoachSubscriptionResponse subscribeCoach(UUID coachId, String tier, String paymentMethodId) {
   // After:
   public CoachSubscriptionResponse subscribeCoach(UUID coachId, Long coachUserId, String tier) {
   ```
2. Replace the block at `:121-125`:
   ```java
   // DELETE:
   String stripeCustomerId = sub.getStripeCustomerId();
   if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
       throw new ...PaymentGatewayException("payment.subscription.noStripeAccount");
   }
   ```
   with the exact pattern `subscribePlayer` uses at `:284-292` (adjusted for the field/variable names in scope):
   ```java
   com.softropic.skillars.platform.payment.repo.StripeCustomer stripeCustomer =
       stripeCustomerRepository.findById(coachUserId)
           .orElseThrow(() -> new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
               "payment.noPaymentMethod"));
   String paymentMethodId = stripeCustomer.getStripePaymentMethodId();
   if (paymentMethodId == null || paymentMethodId.isBlank()) {
       throw new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException(
           "payment.noPaymentMethod");
   }
   String stripeCustomerId = stripeCustomer.getStripeCustomerId();
   ```
   `stripeCustomerRepository` is already a field on this class (`:46`) — no new injection needed.
3. Remove the now-redundant attach call at `:130` (`stripeClient.attachPaymentMethod(stripeCustomerId, paymentMethodId);`), inside the `try` block. Copy `subscribePlayer`'s own comment explaining exactly why (`:297-300`): the payment method was already attached to this customer when it was saved via `/setup-intent` + `confirmCardSetup` + `/save-payment-method` (AC1) — calling attach again on an already-attached payment method throws on Stripe's side. Keep `stripeSub = stripeClient.createSubscription(stripeCustomerId, priceId, paymentMethodId);` unchanged.
4. `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscribeRequest.java` — drop the now-unused field:
   ```java
   public record CoachSubscribeRequest(@NotNull String tier) {}
   ```
5. `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java:60-66` (`subscribeCoach`) — update the call site:
   ```java
   @PostMapping("/coach/subscribe")
   @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
   public ResponseEntity<CoachSubscriptionResponse> subscribeCoach(
           @Valid @RequestBody CoachSubscribeRequest request) {
       UUID coachId = resolveCoachId();
       Long coachUserId = securityUtil.getCurrentCoachUserId();
       CoachSubscriptionResponse response = subscriptionService.subscribeCoach(coachId, coachUserId, request.tier());
       return ResponseEntity.ok(response);
   }
   ```
   `resolveCoachId()` and `securityUtil.getCurrentCoachUserId()` each do their own lookup — two calls, not one refactored helper; this matches the existing style in this file rather than introducing a new overload for a single call site.
6. Do **not** touch `changeCoachTier`/`cancelCoachSubscription` — neither needs a new payment method (tier changes/cancellation act on the existing Stripe subscription, which already has a payment method attached from the initial subscribe).
7. `payment.subscription.noStripeAccount` i18n key becomes unused by this path once the above lands — check for other references before removing (grep across `src/main/resources` and any i18n-adjacent backend message bundles); if truly orphaned, remove in all locale/message files it appears in, otherwise leave it.

**Testing (all in `SubscriptionLifecycleIT.java`, `src/test/java/com/softropic/skillars/platform/payment/service/`):**
- All 11 call sites of `subscriptionService.subscribeCoach(coachId, "TIER", PAYMENT_METHOD)` (lines `133,152,155,163,179,199,228,243,266,292,315` — re-verify at implementation time, this story's line numbers are pre-implementation) change to `subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "TIER")`.
- `@BeforeEach setUpCoach()` (`:49-89`) — the `payment.stripe_customers` insert at `:56-60` currently only sets `parent_id`/`stripe_customer_id`. Add `stripe_payment_method_id`:
  ```java
  jdbcTemplate.update(
      "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) " +
      "VALUES (?, ?, ?) ON CONFLICT (parent_id) DO NOTHING",
      COACH_USER_ID, STRIPE_CUSTOMER_ID, PAYMENT_METHOD
  );
  ```
  Without this, `stripeCustomer.getStripePaymentMethodId()` is null and every existing `subscribeCoach` call in this file throws `payment.noPaymentMethod` — this is a required fixture fix, not optional cleanup. The `payment.coach_subscriptions` insert's `stripe_customer_id` column (`:64-68`) is now unused by the code path under test; leave the insert as harmless dead data rather than restructuring the fixture further.
- Add a new test: `coachSubscribe_noSavedCard_throwsNoPaymentMethod` — a coach with no `payment.stripe_customers` row (or one with a null `stripe_payment_method_id`) calling `subscribeCoach` gets `PaymentGatewayException` with the `payment.noPaymentMethod` reason, not a raw NPE or the old `noStripeAccount` message.
- Mutation check: restoring the old `attachPaymentMethod` call must not silently pass — add `verify(stripeClient, never()).attachPaymentMethod(anyString(), anyString());` to `coachSubscribe_createsActiveSubscription`, so a regression that reintroduces the redundant (and, once cards are real, Stripe-rejected) attach call fails a named assertion instead of relying on the happy-path mock (`doNothing()`) to mask it.

### AC3 — Frontend: `CoachSubscriptionPage.vue` reuses the existing card-entry component instead of a raw text field

**`PlayerSubscriptionPage.vue` (`src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`) already implements this exact pattern for players — its own code comment says so verbatim: "AC 5: gate confirm on a saved card instead of a raw, frontend-typed payment-method id — the backend resolves the saved card itself." Copy that page's `subscribeDialog`/`addCardDialog`/`hasCard` structure into `CoachSubscriptionPage.vue`, adjusted for the coach store actions that already exist.**

1. `src/frontend/src/pages/coach/CoachSubscriptionPage.vue` — script section:
   - Add import: `import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'`.
   - Add `const addCardDialog = ref(false)`.
   - Remove `const paymentMethodId = ref('')`.
   - Add `const hasCard = computed(() => paymentStore.savedPaymentMethod?.hasCard ?? false)`.
   - `onMounted` (`:160-167`) — add `paymentStore.fetchSavedPaymentMethod()` to the `Promise.all([...])` alongside the existing two calls.
   - `confirmSubscribe()` (`:194-209`) — replace the `if (!paymentMethodId.value)` guard with `if (hasCard.value === false)` (notify with `t('payment.card.addCardPrompt')`, matching `PlayerSubscriptionPage.vue`'s exact wording, not a coach-specific string), and change the store call payload from `{ tier: selectedTier.value, paymentMethodId: paymentMethodId.value }` to `{ tier: selectedTier.value }` — matches AC2's simplified `CoachSubscribeRequest`.
2. Template section:
   - Replace the `q-input` block at `:117-123` (the raw `subscription.paymentMethodId` field + `subscription.paymentMethodHint` caption) with the `hasCard === false` warning + "add card" button block from `PlayerSubscriptionPage.vue:112-116` (adjust nothing except that no `billingInterval` selector exists on this page — coaches are monthly-only, `CoachSubscribeRequest.java`'s own comment already says so, do not add one).
   - Add the `:disable="hasCard === false"` binding on the dialog's Confirm button (`:132-137` region), matching `PlayerSubscriptionPage.vue:120`.
   - Add a second `q-dialog v-model="addCardDialog"` wrapping `<PaymentMethodCard @saved="addCardDialog = false" />`, copied verbatim in structure from `PlayerSubscriptionPage.vue:129-138`.
3. **Do not touch** `isTierBlocked`/`openSubscribeDialog`'s existing branching logic (the "upgrade to unlock" overlay for a tier ranked *below* the current one) — it is pre-existing, unrelated to card collection, and reads as an existing UX oddity (the overlay's CTA is literally labelled "upgrade" for what is structurally a downgrade path) that is out of this story's scope. Flag it in Completion Notes if confirmed odd on inspection; do not fix blind.
4. i18n cleanup (do this as part of AC3, not separately): `subscription.paymentMethodId` and `subscription.paymentMethodHint` (`en-US/index.js:1151-1152`, `de-DE/index.js:1050-1051`, `fr-FR/index.js:911-912`) become fully orphaned once the raw input is removed — this is the **exact** item `skillars-deferred-11` D3 recorded and `skillars-uat-1` annotated "fold this into the coach-subscription story rather than tracking it separately" (see callout and AC4). Grep all three locale files (and confirm no other `.vue` file references either key — the only known consumer was this page) before deleting; also check `subscription.coach.paymentMethodRequired` (used by the guard this AC replaces) for other references and remove if orphaned too.

**Testing:** no frontend automated test suite exists in this codebase (consistent with every prior UAT story). Verify by: `npx eslint` clean on touched files, successful `quasar build`, and a manual/browser-tooled spot-check of coach login → subscription page → "add card" dialog (Stripe test card) → subscribe to a paid tier succeeds with no pm-id text field visible anywhere. Record as **STILL OPEN** in Dev Agent Record if a live browser check isn't possible in this environment, matching house convention.

### AC4 — Ledger hygiene (coach subscription)

1. `_bmad-output/implementation-artifacts/deferred-work.md` — close `skillars-deferred-11` D1 and D3 (`:1187-1190`) with a `[CLOSED 2026-08-13 by skillars-uat-6 AC1-AC3]` annotation, and correct the stale claims in the same annotation: (a) the endpoints were already widened to `HAS_PARENT_OR_PLAYER_ROLE` by `skillars-uat-5`, not still `HAS_PARENT_ROLE` as D1 says; (b) no schema change/second table was needed — `CoachProfile.userId` already bridges the id space, see this story's opening callout.
2. Record new deferred items for the "Explicitly out of scope" list above (dead `stripe_customer_id` column, Stripe customer metadata cosmetics, no dedicated `SubscriptionResourceIT`) — one entry each, terse, file:line-cited.
3. `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` — covered together with AC8's ledger updates (one combined edit pass over this file, not two separate passes).

---

### AC5 — New file-level volume backup script, replacing the broken snapshot mechanism

1. **Delete** `deploy/backup/volume-snapshot.sh` — its header already documents in full why it can never work (verified against 4 independent sources by `skillars-uat-3`); nothing here is salvageable, the endpoint it calls does not exist in the Hetzner Cloud API.
2. **New file** `deploy/backup/volume-backup.sh`, mirroring `pg-backup.sh`'s structure/conventions exactly (auth pattern, naming, logging prefix, non-empty-file check before upload):
   ```bash
   #!/usr/bin/env bash
   # Archives everything under /opt/skillars/data EXCEPT postgres/ (pg-backup.sh's pg_dump is the
   # database's backup; a live tar of a running postgres data directory is not a valid restore
   # source) to Hetzner Object Storage. Replaces volume-snapshot.sh, which called a Hetzner Cloud
   # API endpoint that does not exist — see deferred-work.md, skillars-uat-3 D1. Cron runs this
   # daily at 02:00 UTC via install-crons.sh, the same slot volume-snapshot.sh held.
   set -euo pipefail

   # shellcheck source=/dev/null
   . /opt/skillars/.env

   DATA_DIR="/opt/skillars/data"
   TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
   ARCHIVE_FILE="/tmp/skillars-volume-${TIMESTAMP}.tar.gz"
   PREFIX="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
   PREFIX="${PREFIX%/}/"

   if [ ! -d "$DATA_DIR" ]; then
     echo "[volume-backup][error] ${DATA_DIR} does not exist" >&2
     exit 1
   fi

   echo "[volume-backup] Archiving ${DATA_DIR} (excluding postgres/, covered by pg-backup.sh)..."
   tar --exclude='./postgres' -czf "${ARCHIVE_FILE}" -C "${DATA_DIR}" .

   if [ ! -s "${ARCHIVE_FILE}" ]; then
     echo "[volume-backup][error] archive file is empty or missing — aborting upload" >&2
     exit 1
   fi

   echo "[volume-backup] Uploading to s3://${HOS_BUCKET}/${PREFIX}skillars-volume-${TIMESTAMP}.tar.gz"
   AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" \
   AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
     aws s3 cp "${ARCHIVE_FILE}" \
     "s3://${HOS_BUCKET}/${PREFIX}skillars-volume-${TIMESTAMP}.tar.gz" \
     --endpoint-url "${HOS_ENDPOINT}" \
     --no-progress

   rm -f "${ARCHIVE_FILE}"
   echo "[volume-backup] Done. $(date -u)"
   ```
   **Verify the `--exclude='./postgres'` pattern actually excludes `postgres/` during implementation** (`tar tzf` the resulting archive and confirm no `postgres/` entries) before treating this as done — GNU tar exclude-pattern matching against `-C`-relative paths is easy to get subtly wrong, and a silent inclusion of a live, growing postgres data directory would produce a multi-GB archive with no backup value.
3. `deploy/backup/install-crons.sh` — swap the `volume-snapshot.sh` cron entry for `volume-backup.sh` at the same `0 2 * * *` slot. **This is more than a one-line text swap — the idempotency guard is a separate string match from the command text, and missing this breaks the script's own documented "safe to re-run" guarantee:**
   - `:10` — rename `SNAP_CRON` to `VOLUME_CRON` (or similar) and change its command from `.../volume-snapshot.sh` to `.../volume-backup.sh`.
   - `:21` — `if ! crontab -l 2>/dev/null | grep -qF "volume-snapshot.sh"; then` — **this grep target must change to `"volume-backup.sh"` too.** It is not derived from `SNAP_CRON`/the renamed variable; it's an independent literal string. If only the cron command text is swapped and this grep target is left as `"volume-snapshot.sh"`, every future re-run of `install-crons.sh` will never find `"volume-snapshot.sh"` in the installed crontab (because it was replaced), so it will re-append `volume-backup.sh` on every single run — duplicate cron entries, duplicate daily backups, forever. Update the two adjacent log messages (`:23,25`, "volume-snapshot cron installed"/"already present") to say "volume-backup" for consistency.
   - `:2` (top doc comment, "Installs backup cron entries for pg-backup.sh and volume-snapshot.sh.") — update to say `volume-backup.sh`. (`:11`'s "clear of both producers above (0 */6 and 0 2)" comment names no script and needs no edit.)
   - `deploy/backup/prune-backups.sh:3`'s own header comment ("clear of pg-backup.sh (0 */6) and volume-snapshot.sh (0 2)") is a **separate file and a separate edit** — see AC6 below; fixing this file does not fix that line.

**Testing:** `shellcheck` clean (matches the standard every sibling script in this directory already meets). Live execution against real Hetzner Object Storage credentials is out of scope for this environment (see "Explicitly out of scope" — record as **STILL OPEN**, first real run must be observed manually).

### AC6 — `prune-backups.sh`: add the volume-backup retention branch, remove the dead snapshot branch

1. `deploy/backup/prune-backups.sh` — remove `prune_snapshots()` (`:150-234` region) in full, including its `jq` dependency check (`jq` was needed only by this function; confirm no other part of the script uses it before deleting the check) and its call site (`prune_snapshots || SNAP_STATUS=$?`). **Two more things become orphaned by this removal and must go with it, not be left behind:**
   - `:43` — `SNAPSHOT_RETENTION_DAYS="${SNAPSHOT_RETENTION_DAYS:-7}"` — delete; its only consumer is `prune_snapshots()`.
   - `:58` — `require_positive_int SNAPSHOT_RETENTION_DAYS "$SNAPSHOT_RETENTION_DAYS"` — delete along with it.
   - `:3` — the file's own top-of-file doc comment, `"clear of pg-backup.sh (0 */6) and volume-snapshot.sh (0 2)"` — update to say `volume-backup.sh` instead of `volume-snapshot.sh` (the schedule slot is unchanged, only the script name at that slot changes). This is a **different line** from the "Two independent parts" list (`:5-7`) that item 4 below already covers — fixing one does not fix the other, both go stale independently.
2. Add `prune_volume_backups()`, structurally mirroring `prune_s3_dumps()` (`:47-99` region) exactly — same listing/sort/cutoff/delete pattern, parameterized differently:
   - Prefix: `${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}`
   - Retention vars: `VOLUME_BACKUP_RETENTION_DAYS` (default `14`, matching the pg-dump default), `VOLUME_BACKUP_RETENTION_MIN_KEEP` (default `4` — this stream runs daily, not every 6 hours, so 4 days' worth is a reasonable minimum floor rather than reusing the pg-dump stream's `8`).
   - Filename regex: adapt `prune_s3_dumps`'s `sed` pattern from `skillars-\([0-9]\{8\}T[0-9]\{6\}Z\)\.sql\.gz$` to `skillars-volume-\([0-9]\{8\}T[0-9]\{6\}Z\)\.tar\.gz$`, matching AC5's exact naming.
   - Add `require_positive_int VOLUME_BACKUP_RETENTION_DAYS "$VOLUME_BACKUP_RETENTION_DAYS"` and the min-keep equivalent to the existing validation block near the top of the script.
3. Replace the `SNAP_STATUS` variable with `VOLUME_STATUS` throughout (status tracking, the final `if` that decides overall exit code, and the final error-summary echo).
4. Update the script's top-of-file doc comment (the "Two independent parts" list) to describe the new pair: postgres dumps and volume backups, both to Object Storage.

**Testing:** `shellcheck` clean. `--dry-run` logic is unchanged in shape (same guard-flag parsing at the top of the file) — no new test needed there. Live execution against real credentials: same STILL-OPEN posture as AC5.

### AC7 — Restore script + documentation

1. **Delete** `deploy/backup/restore-from-snapshot.sh` — restores from a Hetzner volume snapshot that has never existed and never will via this mechanism.
2. **New file** `deploy/backup/restore-from-volume-backup.sh`. Mirror `restore-from-snapshot.sh`'s doc-comment header style and its numbered-step structure, but replace the manual Hetzner-Console volume-detach dance with a straight download-and-extract from Object Storage, and **fix the ownership-restoration gap `restore-from-snapshot.sh` had** (it restored `postgres`/`prometheus`/`loki`/`tempo`/`grafana` ownership but never `redis` or `traefik` — both are on the volume per `provision.sh` §7.5, and this restore script should not repeat that omission):
   ```bash
   #!/usr/bin/env bash
   # Restores /opt/skillars/data (excluding postgres/, which is not archived by volume-backup.sh —
   # restore the database separately via restore-from-dump.sh) from the latest, or an explicitly
   # named, file-level volume backup in Object Storage. Replaces restore-from-snapshot.sh, which
   # restored from Hetzner Cloud volume snapshots that were never actually created — see
   # deferred-work.md, skillars-uat-3 D1.
   set -euo pipefail
   # shellcheck source=/dev/null
   . /opt/skillars/.env

   DATA_DIR="/opt/skillars/data"
   PREFIX="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
   PREFIX="${PREFIX%/}/"
   KEY="${1:-}"   # optional: exact object key to restore; default = most recently modified

   echo "This will OVERWRITE non-postgres data under ${DATA_DIR}. Press ENTER to continue, Ctrl+C to abort."
   read -r _

   if [ -z "$KEY" ]; then
     KEY=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
       aws s3api list-objects-v2 --bucket "${HOS_BUCKET}" --prefix "${PREFIX}" \
       --endpoint-url "${HOS_ENDPOINT}" --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)
   fi
   if [ -z "$KEY" ] || [ "$KEY" = "None" ]; then
     echo "[restore-from-volume-backup][error] no volume backup found under ${PREFIX}" >&2
     exit 1
   fi

   echo "[restore-from-volume-backup] Restoring ${KEY}..."
   docker compose -f /opt/skillars/docker-compose.yml down

   ARCHIVE_FILE="/tmp/$(basename "$KEY")"
   AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
     aws s3 cp "s3://${HOS_BUCKET}/${KEY}" "${ARCHIVE_FILE}" --endpoint-url "${HOS_ENDPOINT}"
   tar -xzf "${ARCHIVE_FILE}" -C "${DATA_DIR}"
   rm -f "${ARCHIVE_FILE}"

   # Ownership restoration — same values provision.sh sections 7/7.5 set on first provisioning.
   # restore-from-snapshot.sh (the script this replaces) omitted redis/traefik; do not repeat that.
   chown -R 999:1000 "${DATA_DIR}/redis"
   chown -R 65534:65534 "${DATA_DIR}/prometheus"
   chown -R 10001:10001 "${DATA_DIR}/loki"
   chown -R 10001:10001 "${DATA_DIR}/tempo"
   chown -R 472:472 "${DATA_DIR}/grafana"
   chmod 700 "${DATA_DIR}/traefik"
   chmod 600 "${DATA_DIR}/traefik/acme.json"

   docker compose -f /opt/skillars/docker-compose.yml up -d

   echo "[restore-from-volume-backup] Done. Postgres was NOT restored by this script — use restore-from-dump.sh separately if needed."
   ```
3. `docs/deployment/backup-restore.md`:
   - Update the top warning blockquote (`:19-25`) — it currently says Section B does not work and lists everything unprotected. Replace with a short factual note that file-level volume backup now runs daily and covers everything except postgres (which `pg-backup.sh` already covers).
   - Rewrite "Section B: Restore from Volume Snapshot" as "Section B: Restore from Volume Backup", following Section A's documentation style (numbered script-step list + "Expected output on success" + external verification block) rather than Section B's old manual-Hetzner-Console flow.
   - Retention table — replace the `SNAPSHOT_RETENTION_DAYS` row ("Currently matches nothing") with `VOLUME_BACKUP_RETENTION_DAYS`/`VOLUME_BACKUP_RETENTION_MIN_KEEP`, matching AC6's defaults.
   - "When to Use Which Restore Path" table — update its Section B pointer/description accordingly.
4. `docs/deployment/runbook.md`:
   - Replace the loud warning blockquote (`:85-92`, "no working backup... open operational decision") with a short factual note: file-level volume backup runs daily via `volume-backup.sh`, pruned by `prune-backups.sh`.
   - Update the retention table (`:74-79`) — replace the Hetzner Cloud snapshots row with the new volume-backup row.

**Testing:** `shellcheck` clean on the new script. Doc changes are prose — no automated check; read them back against AC5/AC6's actual final variable names/defaults before considering this done (a common failure mode in this codebase's own history, per `skillars-uat-2`'s Dev Notes, is doc edits that don't match the code they describe).

### AC8 — Ledger hygiene (volume backup)

1. `_bmad-output/implementation-artifacts/deferred-work.md`:
   - Close `skillars-uat-3` D1 (`:1291`, the full volume-snapshot finding) with a `[CLOSED 2026-08-13 by skillars-uat-6 AC5-AC7]` annotation.
   - Close/re-scope the four findings explicitly gated on D1's resolution: the review-round duplicate of "AC6's safety rail is unimplemented" (`:1315`), the two pagination-gap notes gated on D1 (`:1323`, and its duplicate), and D16 (`:1341`, `HETZNER_VOLUME_ID` ownership check). All four become **moot-by-removal**: `prune_snapshots()` and the `HETZNER_VOLUME_ID`-based mechanism it gated no longer exist after AC6, so there is no "the snapshot" left for these checks to apply to. Annotate each `[CLOSED 2026-08-13 by skillars-uat-6 AC6 — mechanism removed]` rather than reopening them as new work.
   - Close D10 (`:1309`, the `jq` dependency note) — `jq` was required only by `prune_snapshots()`, which AC6 removes; the dependency no longer exists in this script. Note in the closing annotation that `provision.sh`'s apt list already includes `jq` regardless (added before this story, confirmed still present), so this closure is not conditional on anything else.
   - Record a new deferred item: `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` are removed from the server's `.env.example`/`secrets-reference.md` by AC7-adjacent cleanup below, but any **already-provisioned** node's live `/opt/skillars/.env` will still carry stale values — harmless (nothing reads them anymore) but worth a one-line ops note, not a script change.
2. `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` — single combined edit pass covering both halves of this story:
   - Add a row to the "Story claims" table for `skillars-uat-6-coach-subscription-and-volume-backup.md`, claiming P0-4 and P1 #9.
   - Remove both from "Still unclaimed" — the line should then read only "everything in P3" (already explicitly out of scope, never claimed by any story).
   - Update "Suggested sequence" item 5 (currently "Decide P0-4...") to reflect the decision made and this story shipping it, matching the strikethrough-plus-DONE convention every prior completed item in that list already uses.
   - Update "Suggested sequence" item 8 (currently "Decide the volume backup replacement...") the same way.
3. `.env.example` and `docs/deployment/secrets-reference.md` — remove `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from the **server** env template — nothing running on the server reads them after AC5-AC6 (only the local-only `apply-firewall.sh` still uses `HCLOUD_TOKEN`, from the operator's own machine, never from `/opt/skillars/.env` — do not touch `deploy/firewall/`'s own docs). **Remove the whole `--- Backup: Hetzner Cloud API ---` block, not just the two value lines**: `.env.example:85-90` is
   ```
   # --- Backup: Hetzner Cloud API ---
   # API token for creating volume snapshots (read/write scope required)
   HCLOUD_TOKEN=change-me
   # Numeric ID of the Hetzner Volume attached to this Node
   # Find in Hetzner Cloud Console → Volumes → click the volume → copy the ID from the URL
   HETZNER_VOLUME_ID=12345678
   ```
   Deleting only `:87`/`:90` (the two `KEY=value` lines) leaves the `# --- Backup: Hetzner Cloud API ---` section header and its two explanatory comment lines behind with nothing under them — delete the full six-line block (section header through the last comment/value pair), not just the assignment lines. Apply the same full-block removal to `secrets-reference.md`'s `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` rows (`:48-49`) and any section heading specific to those two vars in that file. Add a new row for `HOS_VOLUME_BACKUP_PREFIX` immediately after the existing `HOS_BACKUP_PREFIX` row/line, following that row's exact format and trailing-slash convention. Replace the `SNAPSHOT_RETENTION_DAYS` "tuning knob" row (`secrets-reference.md:57`) with `VOLUME_BACKUP_RETENTION_DAYS`/`VOLUME_BACKUP_RETENTION_MIN_KEEP`, matching AC6's actual default values.

## Tasks / Subtasks

- [x] **Task 1 — Backend: open card-collection endpoints to coaches (AC: 1)**
  - [x] `SecurityConstants.java`: add `HAS_PARENT_PLAYER_OR_COACH_ROLE`.
  - [x] `SessionPackPaymentResource.java`: widen `@PreAuthorize` on `/setup-intent`, `/save-payment-method`, `/payment-method`.
  - [x] `SessionPackPaymentResourceIT`: flip `getSavedPaymentMethod_coachRole_returns403` to success; add coach-role success cases for setup-intent/save-payment-method, mirroring the existing player cases. Mutation check on the `@PreAuthorize` widening.

- [x] **Task 2 — Backend: `subscribeCoach` resolves the saved card (AC: 2)**
  - [x] `SubscriptionService.subscribeCoach`: new signature `(UUID coachId, Long coachUserId, String tier)`; replace the `sub.getStripeCustomerId()` null-check with a `stripeCustomerRepository.findById(coachUserId)` lookup mirroring `subscribePlayer`; drop the redundant `attachPaymentMethod` call.
  - [x] `CoachSubscribeRequest.java`: drop `paymentMethodId` field.
  - [x] `SubscriptionResource.subscribeCoach`: update call site to pass `coachUserId`.
  - [x] `SubscriptionLifecycleIT`: update all 9 `subscribeCoach(...)` call sites; add `stripe_payment_method_id` to the coach fixture insert; new test for the no-saved-card case; mutation check verifying `attachPaymentMethod` is never called.
  - [x] Grep `payment.subscription.noStripeAccount` for other references; remove from message bundles if orphaned.

- [x] **Task 3 — Frontend: card-entry UI for coaches (AC: 3)**
  - [x] `CoachSubscriptionPage.vue`: import `PaymentMethodCard`; add `addCardDialog`/`hasCard`; wire `fetchSavedPaymentMethod` into `onMounted`; replace the raw pm_id input with the `hasCard`-gated warning + add-card dialog, copied from `PlayerSubscriptionPage.vue`; update `confirmSubscribe` payload and guard.
  - [x] Remove orphaned i18n keys (`subscription.paymentMethodId`, `subscription.paymentMethodHint`, and `subscription.coach.paymentMethodRequired` if unreferenced elsewhere) from all 3 locale bundles, after confirming no other consumer.
  - [x] Verify: `quasar build` succeeds; `npx eslint` clean; manual/browser-tooled spot-check recorded as STILL OPEN if unavailable.

- [x] **Task 4 — Ledger hygiene, coach subscription half (AC: 4)**
  - [x] `deferred-work.md`: close `skillars-deferred-11` D1/D3 with corrected annotation.
  - [x] `deferred-work.md`: record 3 new deferred items (dead column, Stripe metadata cosmetics, no dedicated REST IT).

- [x] **Task 5 — New file-level volume backup script + cron (AC: 5)**
  - [x] Delete `deploy/backup/volume-snapshot.sh`.
  - [x] New `deploy/backup/volume-backup.sh`, mirroring `pg-backup.sh`'s conventions; verify the postgres exclusion actually works via `tar tzf` inspection.
  - [x] `install-crons.sh`: swap the `volume-snapshot.sh` cron entry for `volume-backup.sh` at the same slot — rename `SNAP_CRON`, update its command text, **and separately update the `grep -qF "volume-snapshot.sh"` idempotency-check string at `:21` plus the two adjacent log messages** (this is a distinct literal string from the command text — missing it means every re-run appends a duplicate cron line); update the top doc comment (`:2`).
  - [x] `shellcheck` clean.

- [x] **Task 6 — `prune-backups.sh`: volume-backup branch, remove dead snapshot branch (AC: 6)**
  - [x] Remove `prune_snapshots()` and its `jq` check and call site, **plus its two now-orphaned dependents**: the `SNAPSHOT_RETENTION_DAYS` default (`:43`) and its `require_positive_int` validation call (`:58`).
  - [x] Add `prune_volume_backups()` mirroring `prune_s3_dumps()`; new retention env vars with validation.
  - [x] Rename `SNAP_STATUS` → `VOLUME_STATUS` throughout; update **both** stale doc comments — the top-of-file `"clear of ... volume-snapshot.sh (0 2)"` line (`:3`) and the separate "Two independent parts" list (`:5-7`).
  - [x] `shellcheck` clean.

- [x] **Task 7 — Restore script + docs (AC: 7)**
  - [x] Delete `deploy/backup/restore-from-snapshot.sh`.
  - [x] New `deploy/backup/restore-from-volume-backup.sh`, including the redis/traefik ownership restoration the old script omitted.
  - [x] `backup-restore.md`: rewrite Section B, update top warning + retention table + restore-path-selection table.
  - [x] `runbook.md`: replace the loud warning blockquote; update the retention table.
  - [x] `shellcheck` clean on the new script.

- [x] **Task 8 — Ledger hygiene, volume backup half + env cleanup (AC: 8)**
  - [x] `deferred-work.md`: close `skillars-uat-3` D1, its 4 D1-gated dependents, and D10; record the stale-.env ops note.
  - [x] `uat-readiness-priorities.md`: combined edit pass — Story claims row, Still unclaimed (P3-only), Suggested sequence items 5 and 8.
  - [x] `.env.example` / `secrets-reference.md`: remove the **whole** `--- Backup: Hetzner Cloud API ---` block (section header + both comment lines + both value lines, `.env.example:85-90` — not just the two `KEY=value` lines, which would leave a dangling empty section header behind) and the equivalent full rows in `secrets-reference.md`; add `HOS_VOLUME_BACKUP_PREFIX`; replace the snapshot retention row with the volume-backup retention rows.

- [x] **Task 9 — Verify**
  - [x] `mvn -o verify` full regression, 0F/0E.
  - [x] `npx eslint` clean on touched frontend files.
  - [x] `quasar build` succeeds.
  - [x] `shellcheck` clean on all 4 touched/new backup scripts (`volume-backup.sh`, `prune-backups.sh`, `restore-from-volume-backup.sh`, `install-crons.sh`).

### Review Findings

_Adversarial code review, 2026-08-13. Three parallel layers: Blind Hunter (diff-only), Edge Case Hunter (diff + project read access), Acceptance Auditor (diff + spec + project-context.md)._

- [x] [Review][Patch] No pre-overwrite safety net in `restore-from-volume-backup.sh` — decided 2026-08-13 (Mbah): add a pre-restore snapshot. Before extracting, rename the current data dir aside (e.g. `mv "$DATA_DIR" "${DATA_DIR}.pre-restore-${TIMESTAMP}"`, recreate `$DATA_DIR`) so a bad restore (wrong key, corrupt archive) can be manually rolled back instead of being unrecoverable. [deploy/backup/restore-from-volume-backup.sh]
- [x] [Review][Patch] `install-crons.sh` never purges the stale `volume-snapshot.sh` cron entry on an already-provisioned node — it only guards against re-adding the new entry, so an upgraded node keeps invoking the now-deleted script nightly alongside the new one [deploy/backup/install-crons.sh:21]
- [x] [Review][Patch] `volume-backup.sh` aborts the whole backup under `set -e` on `tar`'s harmless "file changed as we read it" exit code 1 warning, likely on every run since it archives a live, actively-written data dir [deploy/backup/volume-backup.sh:24]
- [x] [Review][Patch] `volume-backup.sh`'s `rm -f` cleanup for the temp archive runs only after the risky `aws s3 cp`; a failed upload leaves the archive orphaned in `/tmp` on every failed cron run [deploy/backup/volume-backup.sh:32]
- [x] [Review][Patch] `restore-from-volume-backup.sh` stops the app (`docker compose down`) before download/extract; if `aws s3 cp` or `tar` fails afterward, services stay stopped indefinitely with no automatic restart [deploy/backup/restore-from-volume-backup.sh:30]
- [x] [Review][Patch] `restore-from-volume-backup.sh` picks the "latest" backup by S3 `LastModified` rather than the embedded filename timestamp, inconsistent with `prune_volume_backups()` in this same diff, which explicitly distrusts mtime [deploy/backup/restore-from-volume-backup.sh:19]
- [x] [Review][Patch] `CoachSubscriptionPage.vue`'s `onMounted` wraps its `Promise.all` fetches (including the new `fetchSavedPaymentMethod`) in `try/finally` with no `catch` — a failed fetch surfaces as an uncaught rejection, with `hasCard` silently reading as "no card" instead of a clear error state [src/frontend/src/pages/coach/CoachSubscriptionPage.vue:169]
- [x] [Review][Patch] `restore-from-volume-backup.sh` dropped the 120-second post-restore Docker health check that the deleted `restore-from-snapshot.sh` had — a real regression in the DR signal an operator needs during an actual incident [deploy/backup/restore-from-volume-backup.sh]
- [x] [Review][Patch] `subscribeCoach`'s "no saved card" test only covers the missing-row case; the more realistic "row exists but `stripe_payment_method_id` is null/blank" branch (setup-intent started but never completed) has zero test coverage [src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java]
- [x] [Review][Patch] `deferred-work.md`'s new uat-6 deferred section is inserted in the middle of the existing uat-5 code-review section, orphaning that section's original D2 finding under the wrong heading and creating two colliding "D2" labels [_bmad-output/implementation-artifacts/deferred-work.md:1377]

- [x] [Review][Defer] `volume-backup.sh` sources `/opt/skillars/.env` with no readability guard — a missing/unreadable file still fails loudly via `set -e`, just untagged; identical to `pg-backup.sh`'s existing convention [deploy/backup/volume-backup.sh:10] — deferred, pre-existing
- [x] [Review][Defer] `restore-from-volume-backup.sh` has the same untagged-`.env`-failure gap [deploy/backup/restore-from-volume-backup.sh:9] — deferred, pre-existing
- [x] [Review][Defer] `restore-from-volume-backup.sh`'s `chown -R` calls assume every extracted archive has all expected subdirectories; one missing would fail `chown` mid-restore with services already stopped [deploy/backup/restore-from-volume-backup.sh:40] — deferred, low likelihood (no pre-story backup can exist; every new archive has a consistent structure)
- [x] [Review][Defer] A single account holding both a parent/player role and the coach role shares one `payment.stripe_customers` row keyed only on `userId` — a card saved under one role is silently reusable to fund a subscription under the other, with no per-role consent [src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java:143] — deferred, this is the deliberate opaque-id design this story's own Dev Notes call out ("do not add a `payer_type` column")
- [x] [Review][Defer] `volume-backup.sh` has no disk-space precheck before writing a full tar archive to `/tmp` [deploy/backup/volume-backup.sh] — deferred, identical gap exists in `pg-backup.sh`, the exact script this mirrors
- [x] [Review][Defer] Removing the `attachPaymentMethod` call in `subscribeCoach` removes a redundant safety net against a saved-but-since-detached Stripe payment method [src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:102] — deferred, this is the exact pattern `subscribePlayer` already ships in production; the spec explicitly directs mirroring it rather than inventing new handling here
- [x] [Review][Defer] `.env.example`/`secrets-reference.md` remove `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from the server template, but `apply-firewall.sh` still reads `HCLOUD_TOKEN` locally on the operator's machine [.env.example] — deferred, the spec explicitly scopes this out as a separate, untouched local-only concern
- [x] [Review][Defer] `prune_volume_backups()` trusts `aws s3api`'s output shape with no independent validation (the removed `prune_snapshots()` had a `jq -e 'has("images")'` check) [deploy/backup/prune-backups.sh] — deferred, identical gap exists in `prune_s3_dumps()`, the exact function this mirrors

**Dismissed as noise (4):** two claimed-missing i18n keys (`payment.card.addCardPrompt`/`addCardCta`/`title`) — verified already present pre-diff, reused from `PlayerSubscriptionPage.vue`'s earlier story; a claimed-missing `payment.noPaymentMethod` locale key — verified present in all 3 locale files; an "i18n orphan removal unverified" claim — resolved by the Acceptance Auditor's independent grep confirming zero remaining references; a claim that `resolveCoachId()`/`getCurrentCoachUserId()` should be reconciled into one lookup — contradicts the spec's explicit instruction to keep them as two separate calls, matching existing file style.

## Dev Notes

- **Module layout, coach subscription half**: `platform.payment` (`SessionPackPaymentResource`, `SubscriptionResource`, `SubscriptionService`, `StripeCustomer`, `StripePaymentGateway`, `StripeClient`), `platform.marketplace` (`CoachProfile`, `CoachProfileRepository` — read-only, not modified), `infrastructure.security` (`SecurityConstants`). No new module, no new table, no new migration — current migration high-water mark stays at **V95** (added by `skillars-uat-5`; confirm still true at implementation time).
- **The opaque-id shortcut is the load-bearing design decision here too**, exactly as in `skillars-uat-5`: `payment.stripe_customers.parent_id` now holds parent, self-registered-player, *and* coach ids, all as bare `BIGINT` values from `main."user".id` with no discriminator column. This is deliberate and consistent with how `booking.bookings.parent_id`/`messaging.conversations.parent_id` already work — do not add a `payer_type` column or split the table.
- **`subscribePlayer` is the reference implementation for AC2** — read `SubscriptionService.java:266-301` in full before touching `subscribeCoach`; the pattern (look up `StripeCustomer` by the caller's own userId, read `stripeCustomerId`/`stripePaymentMethodId` off it, no `attachPaymentMethod` call) is proven and already in production for players. Resist the urge to design something new.
- **Module layout, volume backup half**: `deploy/backup/` (bash scripts), `docs/deployment/` (markdown). No Java/Vue code touched by AC5-8 at all — this half of the story is entirely ops scripting and docs, unrelated to the coach-subscription half's Java/Vue surface. No conflicts possible between the two halves.
- **Postgres is deliberately excluded from the new volume-backup archive.** This distinction (file-level copy of a *live* postgres data directory ≠ a valid backup, unlike a true block-level snapshot) does not exist anywhere in this codebase's docs today — introduced fresh by this story. Do not let `volume-backup.sh` silently include `data/postgres`; verify the exclusion works, don't assume the `tar --exclude` flag syntax is correct without checking.
- **Every env var this story removes (`HCLOUD_TOKEN`, `HETZNER_VOLUME_ID`) was previously scoped to exactly two consumers**, both deleted by AC5/AC6 (`volume-snapshot.sh`, `prune_snapshots()`) — confirmed by grep across the full repo (the only other references are `apply-firewall.sh`, a local-machine-only script explicitly out of scope, and historical story/ledger files that don't execute). Removing them from the server's `.env.example` is safe.
- **Two role/authority sources exist in parallel** (`SecurityConstants` vs `AuthoritiesConstants`) — noted in `skillars-uat-5`'s Dev Notes and still true. Use `SecurityConstants` for the new composite constant in AC1, matching every other `@PreAuthorize` this story touches.

### Project Structure Notes

- Backend changes (AC1-2) are widenings of existing `@PreAuthorize` annotations and a signature/body change on one existing service method — no new resource classes, no new service classes, no new module, **no new migration file**.
- Frontend (AC3): no new page or store action — `CoachSubscriptionPage.vue` gains an import of the already-shared `PaymentMethodCard.vue` and mirrors `PlayerSubscriptionPage.vue`'s existing dialog structure; `payment.store.js`'s `subscribeCoach`/`fetchSavedPaymentMethod` actions already exist unchanged.
- Ops (AC5-7): two new scripts (`volume-backup.sh`, `restore-from-volume-backup.sh`), two deleted scripts (`volume-snapshot.sh`, `restore-from-snapshot.sh`), one substantially modified script (`prune-backups.sh`), one cron-list edit (`install-crons.sh`), two doc rewrites (`backup-restore.md` Section B, `runbook.md`'s warning block). No Java/Vue touched by this half.
- No conflicts detected with `skillars-uat-1` through `-5`'s changes. `skillars-uat-5` was the last story to touch `payment`-schema-adjacent code (widening the same three endpoints this story widens further, to `HAS_PARENT_OR_PLAYER_ROLE` — this story takes them one step further to include `ROLE_COACH`) and the last to touch a migration (V95) — re-read the current state of `SessionPackPaymentResource.java` fresh rather than trusting this story's line citations verbatim, since they were verified at `01e812e`, the exact commit `skillars-uat-5` merged as. `skillars-uat-3` was the last story to touch `deploy/backup/*` (retention/pruning) — this story's AC5-8 supersede its D1 finding directly.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md#P0-4`]
- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md#P1-9` (numbered "9" under the P1 section)]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 1187-1190, `skillars-deferred-11` D1/D3]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 1291, 1309, 1315, 1323, 1341, `skillars-uat-3` D1 and its gated dependents]
- [Source: `_bmad-output/implementation-artifacts/skillars-uat-5-player-self-booking.md` — opaque-id pattern precedent]
- [Source: `_bmad-output/implementation-artifacts/skillars-uat-3-payment-capture-integrity-and-backup-retention.md` — original volume-snapshot finding]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java`]
- [Source: `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfile.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscribeRequest.java`]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`]
- [Source: `src/main/resources/db/migration/V64__subscription_tiers.sql`]
- [Source: `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`]
- [Source: `src/frontend/src/pages/parent/PlayerSubscriptionPage.vue`]
- [Source: `src/frontend/src/components/payment/PaymentMethodCard.vue`]
- [Source: `src/frontend/src/stores/payment.store.js`]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java`]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`]
- [Source: `deploy/backup/volume-snapshot.sh`]
- [Source: `deploy/backup/pg-backup.sh`]
- [Source: `deploy/backup/prune-backups.sh`]
- [Source: `deploy/backup/restore-from-snapshot.sh`]
- [Source: `deploy/backup/install-crons.sh`]
- [Source: `deploy/provision.sh`]
- [Source: `deploy/backup/drill-log.md`]
- [Source: `docs/deployment/backup-restore.md`]
- [Source: `docs/deployment/runbook.md`]
- [Source: `docs/deployment/secrets-reference.md`]
- [Source: `.env.example`]
- [Source: `docker-compose.yml`]
- [Source: `deploy/firewall/apply-firewall.sh`]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Mutation check, AC1: reverting `HAS_PARENT_PLAYER_OR_COACH_ROLE` → `HAS_PARENT_OR_PLAYER_ROLE` on `/setup-intent` alone fails `createSetupIntent_coachRole_returns200` (expected 200, got 403) — confirms the widening test discriminates.
- Mutation check, AC2: reintroducing the old `stripeClient.attachPaymentMethod(...)` call in `subscribeCoach` fails `coachSubscribe_createsActiveSubscription` on `verify(stripeClient, never()).attachPaymentMethod(...)` (`NeverWantedButInvoked`) — confirms the removed-attach guard discriminates.
- One test-authoring bug found and fixed during Task 2: `coachSubscribe_noSavedCard_throwsNoPaymentMethod`'s in-test `jdbcTemplate.update("DELETE FROM payment.stripe_customers ...")` silently rolled back (this app runs Hikari with `auto-commit: false`, so any JDBC statement issued outside an explicit `transactionTemplate.execute(...)` block is rolled back when the connection returns to the pool) — the delete never took effect, so the test passed for the wrong reason (or rather failed with "expected a throwable" once the assertion was added). Fixed by wrapping the delete in `transactionTemplate.execute(...)`, matching every other DB mutation in this test class; re-ran and confirmed both the fix (test passes) and the mutation check (deleting the transactional wrapper again reproduces the original failure).
- Verified the `tar --exclude='./postgres'` pattern in `volume-backup.sh` actually excludes `postgres/` by constructing a scratch `data/{postgres,redis,traefik}` tree and inspecting the resulting archive with `tar tzf` — confirmed `postgres/` entries are absent, `redis/`/`traefik/` are present.
- Full `mvn -o verify`: BUILD SUCCESS, 11:25 min, 866 unit (0F/0E, 1 skipped) + 894 IT (0F/0E, 4 skipped). Contribution confirmed by diff against the pre-story baseline (`skillars-uat-5`'s recorded 866 unit + 891 IT): unit unchanged (no new unit-only tests added), IT +3 — matches exactly: net +2 in `SessionPackPaymentResourceIT` (3 new coach-role success tests added, 1 old `coachRole_returns403` test removed) and +1 in `SubscriptionLifecycleIT` (`coachSubscribe_noSavedCard_throwsNoPaymentMethod`).

### Completion Notes List

- **AC1** — `SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE` added; `SessionPackPaymentResource`'s three card-collection endpoints (`/setup-intent`, `/save-payment-method`, `/payment-method`) widened to it. `SessionPackPaymentResourceIT`'s `getSavedPaymentMethod_coachRole_returns403` flipped to `getSavedPaymentMethod_coachRole_returns200`; added `createSetupIntent_coachRole_returns200` and `savePaymentMethod_coachRole_returns204` mirroring the existing player success cases. Mutation-verified (see Debug Log).
- **AC2** — `SubscriptionService.subscribeCoach` re-signatured to `(UUID coachId, Long coachUserId, String tier)`, mirroring `subscribePlayer` exactly: resolves the coach's own `StripeCustomer` row by `coachUserId`, reads `stripePaymentMethodId`/`stripeCustomerId` off it, throws `payment.noPaymentMethod` if no card is saved, and no longer calls `attachPaymentMethod` (the card was already attached at save time). `CoachSubscribeRequest` dropped `paymentMethodId`; `SubscriptionResource.subscribeCoach` updated to resolve and pass `coachUserId` alongside `coachId`. `SubscriptionLifecycleIT`: all 11 `subscribeCoach(...)` call sites updated; `setUpCoach()` fixture now seeds `stripe_payment_method_id`; new `coachSubscribe_noSavedCard_throwsNoPaymentMethod` test; `verify(stripeClient, never()).attachPaymentMethod(...)` added to `coachSubscribe_createsActiveSubscription` as the mutation guard. Grepped `payment.subscription.noStripeAccount` across the repo — it was never externalized to any message bundle (only referenced as a raw exception key), so there was nothing to remove from locale files.
- **AC3** — `CoachSubscriptionPage.vue` now mirrors `PlayerSubscriptionPage.vue`'s card-entry pattern exactly: imports `PaymentMethodCard`, adds `addCardDialog`/`hasCard` (backed by `paymentStore.savedPaymentMethod`), wires `fetchSavedPaymentMethod()` into `onMounted`, replaces the raw `q-input` pm-id field with the `hasCard`-gated warning + "add card" button + a second `PaymentMethodCard` dialog, and simplifies `confirmSubscribe()`'s payload to `{ tier }` matching AC2's simplified request. Orphaned i18n keys (`subscription.paymentMethodId`, `subscription.paymentMethodHint`, `subscription.coach.paymentMethodRequired`) removed from all three locale bundles (`en-US`, `de-DE`, `fr-FR`) after confirming no other consumer by repo-wide grep. `isTierBlocked`'s pre-existing "upgrade to unlock" overlay on a lower tier (labelled "Upgrade" for what is structurally a downgrade-blocking gate) was left untouched per the story's explicit instruction — flagging it here as a pre-existing UX oddity, not a regression. `npx eslint` clean, `quasar build` succeeded. **STILL OPEN:** no live browser spot-check of the coach login → subscription page → add-card dialog → subscribe flow was run in this environment (house convention, consistent with every prior UAT story).
- **AC4** — `skillars-deferred-11` D1/D3 closed in `deferred-work.md` with `[CLOSED 2026-08-13 by skillars-uat-6 AC1-AC3]` annotations correcting both stale claims (endpoints were already `HAS_PARENT_OR_PLAYER_ROLE` since `skillars-uat-5`, not `HAS_PARENT_ROLE`; no schema change was ever needed). Three new deferred items recorded: the now-dead `payment.coach_subscriptions.stripe_customer_id` column, `StripePaymentGateway`'s coach-agnostic `metadata.parentId` Stripe customer tag, and the missing dedicated `SubscriptionResourceIT`. `uat-readiness-priorities.md`'s combined edit pass (Story claims row, Still-unclaimed line, Suggested-sequence items 5 and 8) was found already complete — written prospectively during this story's creation pass — verified against the story's exact AC8 wording rather than assumed.
- **AC5** — `deploy/backup/volume-snapshot.sh` deleted. New `deploy/backup/volume-backup.sh` mirrors `pg-backup.sh`'s structure exactly (auth pattern, naming, logging prefix, non-empty-file check); the `tar --exclude='./postgres'` exclusion was verified working by direct `tar tzf` inspection of a scratch archive, not assumed. `install-crons.sh` updated: `SNAP_CRON` renamed to `VOLUME_CRON` with the new command text, the separate `grep -qF "volume-snapshot.sh"` idempotency-check string updated to `"volume-backup.sh"` (a distinct literal from the command text — missing this would have appended a duplicate cron entry on every future re-run), both adjacent log messages, and the top doc comment. `shellcheck` clean.
- **AC6** — `prune_snapshots()` and its `jq` dependency check/call site removed from `prune-backups.sh`, along with its two orphaned dependents (`SNAPSHOT_RETENTION_DAYS` default and its `require_positive_int` validation call). New `prune_volume_backups()` mirrors `prune_s3_dumps()` exactly (same list/sort/cutoff/delete shape), parameterized with `HOS_VOLUME_BACKUP_PREFIX` (default `volume-backups/`), `VOLUME_BACKUP_RETENTION_DAYS` (default 14), `VOLUME_BACKUP_RETENTION_MIN_KEEP` (default 4), and the `skillars-volume-<stamp>.tar.gz` filename regex matching AC5's naming. `SNAP_STATUS` renamed to `VOLUME_STATUS` throughout; both stale doc comments (top-of-file schedule note and the "Two independent parts" list) updated. `shellcheck` clean.
- **AC7** — `deploy/backup/restore-from-snapshot.sh` deleted. New `deploy/backup/restore-from-volume-backup.sh` downloads the latest (or a named) object from Object Storage and extracts it over `/opt/skillars/data`, then restores ownership for `redis/`, `prometheus/`, `loki/`, `tempo/`, `grafana/`, and `traefik/`/`acme.json` — the redis/traefik values were cross-checked against `provision.sh`'s own `chown`/`chmod` lines (`999:1000`, `700`/`600`) rather than trusted from the story's citation, closing the ownership-restoration gap the old script had (it only ever restored prometheus/loki/tempo/grafana). `backup-restore.md`: Section B rewritten (download+extract flow, ownership-restore step list, "Expected output"-style structure matching Section A), top warning replaced with a factual note, retention table and restore-path-selection table updated. `runbook.md`: warning blockquote and retention table updated to match. `shellcheck` clean on the new script.
- **AC8** — `deferred-work.md`: `skillars-uat-3` D1 closed; the review-round duplicate safety-rail finding, both pagination-gap notes (the original and its D15 duplicate), and D16 (`HETZNER_VOLUME_ID` ownership check) all closed as moot-by-removal (`prune_snapshots()` no longer exists); D10 (`jq` dependency) closed, noting `provision.sh` already installs `jq` regardless (confirmed still present at `provision.sh:22`) so the closure isn't conditional on anything else; a new ops note recorded for stale `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` values that may linger in an already-provisioned node's live `.env`. `.env.example`: the full `--- Backup: Hetzner Cloud API ---` block (section header + both comment lines + both value lines) removed, not just the two `KEY=value` lines; `HOS_VOLUME_BACKUP_PREFIX` added immediately after `HOS_BACKUP_PREFIX`. `secrets-reference.md`: `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` rows removed (no dedicated section heading existed to remove — confirmed by grep, this file's Hetzner secrets live in one flat table); `HOS_VOLUME_BACKUP_PREFIX` row added; `SNAPSHOT_RETENTION_DAYS` row replaced with `VOLUME_BACKUP_RETENTION_DAYS`/`VOLUME_BACKUP_RETENTION_MIN_KEEP`. `deploy/firewall/apply-firewall.sh`'s own local-machine use of `HCLOUD_TOKEN` (and its mention in `docs/deployment/first-time-setup.md` Step 4) was confirmed untouched, per the story's explicit carve-out.
- **AC9 (Task 9 — Verify)** — Full `mvn -o verify`: BUILD SUCCESS (11:25 min), 866 unit + 894 IT, 0F/0E. `npx eslint src/` clean across the whole frontend tree. `quasar build` succeeded. `shellcheck` clean on all 4 touched/new backup scripts.
- **STILL OPEN** (recorded per house convention, matching `skillars-uat-1`/`-2`/`-3`'s posture): (1) no live browser spot-check of the coach subscription page's add-card flow (AC3); (2) live execution of `volume-backup.sh`/`prune-backups.sh`'s new branch/`restore-from-volume-backup.sh` against real Hetzner Object Storage credentials was not run in this environment — first production run must be `--dry-run`, first real restore must be a logged drill (same posture `skillars-uat-3` AC6/D10 already established for this bucket).

**Review findings addressed (2026-08-13, adversarial 3-layer review — Blind Hunter, Edge Case Hunter, Acceptance Auditor):** all 10 `[Review][Patch]` items applied; the 8 `[Review][Defer]` items were decisions/dismissals made during the review itself, no code change needed for those.
- `restore-from-volume-backup.sh`: added a pre-restore safety net (decided by Mbah) — the non-postgres subdirectories (`redis`, `prometheus`, `loki`, `tempo`, `grafana`, `traefik`) are moved aside into `${DATA_DIR}.pre-restore-<timestamp>` before extraction, postgres/ untouched; verified locally that postgres survives and the other dirs move correctly. Added an `ERR` trap after `docker compose down` that auto-restarts services with whatever data is on disk if the download/extract/chown sequence fails partway, closing the "services stay down indefinitely" gap. Restored the 120-second post-restore Docker health-check block the deleted `restore-from-snapshot.sh` had, adapted to this script's `log()`/`err()` style. Latest-backup selection now sorts by the embedded `skillars-volume-<stamp>.tar.gz` filename timestamp (same `list-objects-v2` + lexical-sort idiom `prune_volume_backups()` uses) instead of S3 `LastModified`, closing the inconsistency with the pruner's own explicit distrust of object mtime.
- `volume-backup.sh`: `tar`'s exit code 1 ("file changed as we read it" — expected when archiving a live, actively-written directory) no longer aborts the script under `set -e`; only exit codes ≥2 are treated as fatal, verified with a scratch `(exit 1) && ... || ...` harness. The temp archive cleanup moved into a `trap cleanup EXIT`, so a failed `aws s3 cp` no longer orphans the file in `/tmp`; verified with a scratch script that the trap fires on a simulated failure.
- `install-crons.sh`: an already-provisioned node's stale `volume-snapshot.sh` crontab line (left behind because deleting the script file doesn't touch an already-installed crontab) is now purged via `grep -vF | crontab -` before the new `volume-backup.sh` entry is installed; verified against a scratch crontab fixture.
- `CoachSubscriptionPage.vue`: `onMounted`'s `Promise.all` gained a `catch` (`$q.notify` with a new `subscription.coach.loadError` key, added to all 3 locale bundles) so a fetch failure surfaces as a visible error instead of leaving `hasCard` silently reading "no card" with no explanation.
- `SubscriptionLifecycleIT`: added `coachSubscribe_cardSetupIncomplete_throwsNoPaymentMethod`, covering the case the original `coachSubscribe_noSavedCard_throwsNoPaymentMethod` test missed — a `StripeCustomer` row that exists (setup-intent was started) but has `stripe_payment_method_id IS NULL` (save-payment-method was never completed). Both tests now assert `payment.noPaymentMethod` for their respective preconditions.
- `deferred-work.md`: fixed a real section-insertion bug from the first implementation pass — inserting the new `## Deferred from: skillars-uat-6...` heading had split the existing `## Deferred from: code review of skillars-uat-5-player-self-booking` section's D1/D2 pair, orphaning its D2 (the `BookingRequestPage.vue` `?playerId=` query-param finding) under the wrong heading and colliding it with this story's own D2. Moved D2 back to the `skillars-uat-5` code-review section immediately after its D1, restoring the original section boundaries; `skillars-uat-6`'s own section is now a clean D1/D2/D3 + Ops note with no foreign content mixed in.
- Post-patch full `mvn -o verify`: BUILD SUCCESS, 866 unit (0F/0E, 1 skipped) + 895 IT (0F/0E, 4 skipped) — the +1 IT vs. the pre-patch 894 is exactly the new `coachSubscribe_cardSetupIncomplete_throwsNoPaymentMethod` test. `npx eslint src/` clean. `quasar build` succeeded. `shellcheck` clean on all 4 backup scripts.

### File List

**Backend (Java) — source**
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SubscriptionResource.java`
- `src/main/java/com/softropic/skillars/platform/payment/contract/CoachSubscribeRequest.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`

**Backend (Java) — tests**
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionLifecycleIT.java`

**Frontend**
- `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Ops / deploy scripts**
- `deploy/backup/volume-snapshot.sh` (deleted)
- `deploy/backup/volume-backup.sh` (new)
- `deploy/backup/install-crons.sh`
- `deploy/backup/prune-backups.sh`
- `deploy/backup/restore-from-snapshot.sh` (deleted)
- `deploy/backup/restore-from-volume-backup.sh` (new)

**Docs**
- `docs/deployment/backup-restore.md`
- `docs/deployment/runbook.md`
- `docs/deployment/secrets-reference.md`
- `.env.example`

**Tracking / ledger**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-uat-6-coach-subscription-and-volume-backup.md` (this story file)

## Change Log

| Date | Change |
|---|---|
| 2026-08-13 | Story implemented: all 8 ACs done, full `mvn -o verify` green (866 unit + 894 IT, 0F/0E, 11:25 min). AC1-3 open coach self-serve card collection: `SecurityConstants.HAS_PARENT_PLAYER_OR_COACH_ROLE` widens the three generic card-collection endpoints; `SubscriptionService.subscribeCoach` mirrors `subscribePlayer`'s already-working saved-card resolution instead of the never-written `PaymentCoachSubscription.stripeCustomerId` column — no migration, no new table, the `CoachProfile.userId` opaque-id shortcut `skillars-uat-5` used for players closes this identically for coaches; `CoachSubscriptionPage.vue` drops its raw pm-id text input for the shared `PaymentMethodCard`/`hasCard` pattern. AC4 closes `skillars-deferred-11` D1/D3 with corrected annotations (both were stale) and records 3 new deferred items. AC5-7 replace the permanently-broken `volume-snapshot.sh`/`restore-from-snapshot.sh` (confirmed by `skillars-uat-3` to call a nonexistent Hetzner Cloud API endpoint) with file-level `volume-backup.sh`/`restore-from-volume-backup.sh` to Object Storage, fixing a real pre-existing gap in the old restore script (no redis/traefik ownership restoration) while rewriting it; `prune-backups.sh` gained a `prune_volume_backups()` branch mirroring the working S3-dump pruner and lost the dead snapshot branch entirely. AC8 closes `skillars-uat-3` D1 and 5 items gated on it, removes the now-fully-dead `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` server env block, and confirmed `uat-readiness-priorities.md`'s combined edit pass was already complete from story creation. One test-infrastructure bug found and fixed mid-implementation: a new IT's in-test `DELETE` silently rolled back because this app runs Hikari with `auto-commit: false` and the delete wasn't wrapped in `transactionTemplate.execute(...)` like every other DB mutation in the class — fixed, and both directions mutation-verified. STILL OPEN: no live browser spot-check of AC3's add-card flow; no live execution of the new backup/restore scripts against real Hetzner Object Storage credentials (same posture `skillars-uat-3` AC6/D10 already established). |
| 2026-08-13 | Review findings addressed — 10 patches applied, 8 pre-existing decisions/dismissals confirmed as-is: pre-restore safety net (move non-postgres dirs aside before extracting) plus an auto-restart-on-failure trap and the restored 120s health check in `restore-from-volume-backup.sh`; latest-backup selection switched from S3 `LastModified` to the embedded filename timestamp, matching the pruner; `volume-backup.sh` no longer aborts on tar's harmless exit-1 "file changed" warning and cleans up its temp archive via an EXIT trap even on a failed upload; `install-crons.sh` now purges a stale `volume-snapshot.sh` crontab entry on an already-provisioned node; `CoachSubscriptionPage.vue`'s fetch `Promise.all` gained a `catch` (new `subscription.coach.loadError` i18n key, 3 bundles); new `SubscriptionLifecycleIT` test covers the "row exists but payment method still null" case the original no-saved-card test missed; fixed a real `deferred-work.md` section-splice bug from the initial pass that had orphaned a `skillars-uat-5` code-review finding under this story's heading, colliding two "D2" labels. Full `mvn -o verify` re-run: BUILD SUCCESS, 866 unit + 895 IT (0F/0E, +1 IT matching the new test exactly). `npx eslint` clean; `quasar build` succeeded; `shellcheck` clean on all 4 backup scripts. |
