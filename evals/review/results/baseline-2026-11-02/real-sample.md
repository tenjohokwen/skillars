# Human sample of judge `real` verdicts, baseline 2026-11-02

The judge labelled 188 unmatched reviewer reports as real defects outside the answer key (69 control, 119 blind-hunter). This is a stratified sample of 20 (10 per arm; 5 high, 4 medium, 1 low each, spread across cases). Fill in the two columns per item.

**Verdict** is one of:

- `real` — a genuine defect at the input revision (whether or not it has since been fixed).
- `not-real` — speculative, already handled by code the reviewer did not read, a policy choice, or wrong.
- `known` — real, but it is one of the dataset's own expected findings in another case, or a drafted case in `cases/_drafts/`.

**Status at HEAD** is `fixed`, `open` or `unknown`. An `open` real defect is a bug to file; a `fixed` one is still a valid new eval case.

How to look: every item names the input revision. `git show <rev>:<file>` shows the file the reviewer saw; the exported tree is also at `~/.cache/skillars-review-evals/<case>/tree/` (run `evals/review/bin/materialize.sh <case>` if it is gone).

| # | verdict | status at HEAD | note |
|---|---|---|---|
| S01 | real | open | HEAD terminateSession still skips used tokens (markUsedByTokenHash requires used=false); no successor link |
| S02 | known | fixed | = case 384 F1; fixed by nonce-based consumeOAuthState |
| S03 | real | open | same lookup at HEAD; duplicate of S15 — one draft case |
| S04 | real | open | HEAD updateUserEmail unchanged |
| S05 | real | open | frontend stores 'English' etc., so the filter never matches for anyone |
| S06 | real | open | reconcile sweep skips matched rows, so the dropped transition is never healed |
| S07 | real | fixed | HEAD uppercases tier before persist + invalidTier rejection |
| S08 | known | fixed | root = 359 F "double-click creates two subscriptions" (same random per-attempt key); HEAD claim-marker design, narrow takeover residual |
| S09 | real | open | HEAD frontend still drops key on any response; UNRECONCILED path answers 500 without refund |
| S10 | not-real | - | refundEligible already requires CONFIRMED/UPCOMING (Deferred-12 AC4 whitelist in cancelBookingAsParent) |
| S11 | real | open | superseded branch still warn-and-return at HEAD; caller reports success after proration invoice |
| S12 | real | open | date-only string → UTC midnight vs server strict instant check; HEAD identical |
| S13 | real | open | same defect as S17 — one draft case; HEAD identical |
| S14 | not-real | - | idx_disputes_unique_open_per_booking (V138, partial unique) blocks the second open dispute; residue is only 23505→500 mapping |
| S15 | real | open | duplicate of S03 — one draft case |
| S16 | real | open | sibling of S04 (register path); HEAD identical |
| S17 | real | open | same defect as S13 — one draft case |
| S18 | real | open | no @Version anywhere; getMessages/sendMessage still full-row save at HEAD |
| S19 | real | fixed | dead guard confirmed (fetchSecret never decrypts); runner deleted by #388 |
| S20 | real | open | no @Transactional/@Valid at HEAD; delete commits before failing save |

---

## S01 · [control] `242-logout-race-and-cookie-id` R3 · high / concurrency

**Logout does not revoke the live refresh token when the presented token was already rotated**

logout only marks the token from the request cookie, and only if it is not already used (.filter(t -> !t.isUsed())). Trigger: a /api/auth/refresh from another tab, or an interceptor retry, rotates the token while the logout POST is in flight carrying the old cookie. Logout finds a used token and does nothing, so the successor token stays valid in the database for its 7-day TTL. If the refresh response lands after the logout response, its Set-Cookie also puts the rtkn/JWT/skp cookies back in the browser, and the user is silently logged in again after clicking Logout.

*Reviewer's own caveat:* Fire POST /api/auth/refresh and POST /api/auth/logout at the same time with the same rtkn cookie, then check whether any refresh_tokens row for the user still has used=false.

*Judge:* Logout skips an already-used token, so a refresh that rotates the token concurrently leaves the successor unrevoked; the trigger is concrete.

- where: `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` :: `AuthService.logout`
- input revision: `9923ffb02479`  →  `git show 9923ffb02479:src/main/java/com/softropic/skillars/platform/security/service/AuthService.java`

## S02 · [blind-hunter] `307-frontend-links-and-tus` R1 · high / security

**Stripe OAuth state is the coach's own (public) coachId, not a nonce — CSRF account-linking still possible**

The P18 fix compares `state` to `coachId.toString()`. A coach's UUID can be predicted or found: the marketplace exposes it at /coaches/:coachId. An attacker can start OAuth with their own Stripe account, take the resulting `code`, and send a logged-in victim coach to /api/payment/coaches/me/stripe/callback?code=<attacker code>&state=<victim coachId>. The state check passes, and onboardingService.handleOAuthCallback links the attacker's Stripe account to the victim, so the victim's payouts go to the attacker. The state has to be an unguessable value bound to the session and used only once.

*Reviewer's own caveat:* Check StripeOnboardingService.generateOnboardingUrl to see what it puts in `state`. If it is coachId, the attack works. Also check that public coach-profile URLs or DTOs expose the same UUID.

*Judge:* The callback compares state to the caller's public coachId, so an attacker can forge the state and link their own Stripe account to a victim coach.

- where: `src/main/java/com/softropic/skillars/platform/payment/api/StripeOnboardingResource.java` :: `handleOAuthCallback`
- input revision: `70dbaec8da3f`  →  `git show 70dbaec8da3f:src/main/java/com/softropic/skillars/platform/payment/api/StripeOnboardingResource.java`

## S03 · [blind-hunter] `319-csam-suspension-logout-login-limiter` R6 · high / logic

**Refresh grace window consumes the user's newest live token from any device, not this token's successor**

In the grace branch the code calls `findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(ownerId, now)`, which returns the newest live token for the user across all sessions, then rotates it. Scenario: device A refreshes (T0→T1), and within 30 s device B logs in (creates T2). A stale request carrying T0 then 'redirects' to T2 and rotates it to T3, which is sent to the stale caller. Device B's cookie T2 is now used; its next refresh more than 30 s later hits the theft branch and revokes every session for the user. The same lookup also gives anyone replaying a just-rotated token, within 30 s, a fresh credential for whatever session is newest. There is no successor link (e.g. replacedBy) tying T0 to T1.

*Reviewer's own caveat:* Check whether RefreshToken has a successor/family column. Integration test: two logins, then replay one session's rotated token within 30 s and assert the other session's token is untouched.

*Judge:* The grace branch rotates the user's newest live token from any session, so a stale replay consumes another device's token, which later trips the theft revocation; the scenario is concrete.

- where: `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` :: `refresh`
- input revision: `651ed1effc04`  →  `git show 651ed1effc04:src/main/java/com/softropic/skillars/platform/security/service/AuthService.java`

## S04 · [control] `319-csam-suspension-logout-login-limiter` R10 · medium / logic

**Email change sets login to the raw new email (no lowercasing, no uniqueness check)**

u.setLogin(newEmail) stores the value exactly as submitted. AuthService.login looks users up with findOneByLogin(email.toLowerCase()), so a user who types a mixed-case new address (e.g. 'John@X.com') can never log in again. There is also no check that another account already uses that email or login, so the change fails at commit with a constraint violation (500) or, without a constraint, creates duplicate logins. ProfileResource documents a verification email to the new address, but the change is applied immediately without one.

*Reviewer's own caveat:* Check the unique constraints on main."user".login and email in V138__baseline_schema.sql.

*Judge:* The login is stored with its original case but looked up lowercased, so a mixed-case new email locks the user out.

- where: `src/main/java/com/softropic/skillars/platform/security/service/UserProfileService.java` :: `UserProfileService.updateUserEmail`
- input revision: `651ed1effc04`  →  `git show 651ed1effc04:src/main/java/com/softropic/skillars/platform/security/service/UserProfileService.java`

## S05 · [blind-hunter] `330-missing-version-lost-updates` R13 · medium / logic

**findIdsByLanguage lowercases only the input, not the stored array elements**

`lower(:lang) = ANY(languages)` matches only if stored values are already lowercase. saveStep1 stores req.languages() verbatim. A coach who saved "EN" or "German" never appears in language-filtered search.

*Reviewer's own caveat:* Check ProfileBuilderStep1Request validation or normalisation of languages, and existing data (SELECT DISTINCT unnest(languages)).

*Judge:* Only the input is lowercased, so mixed-case stored languages never match.

- where: `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java` :: `findIdsByLanguage`
- input revision: `deefa90f6d10`  →  `git show deefa90f6d10:src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java`

## S06 · [blind-hunter] `337-subscription-entitlement-integrity` R12 · medium / concurrency

**customer.subscription.updated arriving before persistCoachSubscription is recorded as processed and permanently dropped**

In the window between the Stripe create call and the local persist, no row has that stripeSubscriptionId yet. The idempotency row is inserted, the orphan alert is suppressed by the grace window, and the event is acknowledged. Stripe won't redeliver it, so a status transition (e.g. incomplete→active after 3DS) is lost and the local row keeps the stale status.

*Reviewer's own caveat:* Consider throwing (so Stripe retries) inside SUBSCRIBE_RACE_GRACE_WINDOW instead of returning silently.

*Judge:* Concrete trigger: an update webhook arriving before the local persist is acknowledged and lost, so the status transition is dropped.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java` :: `handleSubscriptionUpdated`
- input revision: `cb23925514e5`  →  `git show cb23925514e5:src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java`

## S07 · [control] `337-subscription-entitlement-integrity` R5 · high / edge-case

**Tier string is stored unnormalised, so lowercase input rolls back the DB write after Stripe has charged**

The request tier is validated only with @NotNull. The price lookup lowercases it and rank lookups uppercase it, but setTier stores it as received. For tier="instructor", Stripe creates the subscription, and then syncMarketplaceTier calls CoachSubscriptionTier.valueOf("instructor"). The resulting IllegalArgumentException is not caught (only PessimisticLockingFailureException is), so the transaction rolls back and the charged Stripe subscription has no local row. changeCoachTier("academy") rolls back the same way after Stripe was moved to the ACADEMY price. For players, "pro" is stored as-is, and QuotaConfigService's valueOf fails, so the player gets athlete quotas while paying for PRO.

*Judge:* Concrete trigger: a lowercase tier makes CoachSubscriptionTier.valueOf throw after the Stripe charge, which rolls back the local row.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` :: `subscribeCoach / persistCoachSubscription / persistCoachTierUpgrade / syncMarketplaceTier`
- input revision: `cb23925514e5`  →  `git show cb23925514e5:src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`

## S08 · [control] `340-unpaid-paused-grace-sweep` R2 · high / concurrency

**Per-attempt random idempotency key lets a retried subscribe create and charge a duplicate Stripe subscription**

The key is now "sub-create-" + UUID.randomUUID() on every call. If Stripe creates and charges the subscription but the response is lost (timeout / ApiConnectionException), the user sees stripeError and retries. The retry gets a fresh key, so Stripe creates and charges a second subscription. The first one was never persisted, so isOtherLiveSubscription cannot detect it, and the customer keeps paying for an orphaned subscription. The key should be stable per logical attempt (e.g. a client-supplied request id), or the code should look up existing subscriptions for the customer/price before creating.

*Reviewer's own caveat:* Confirm whether the reconciliation scheduler cancels or refunds orphaned subscriptions automatically or only raises an alert.

*Judge:* A random idempotency key on each call means a retry after a lost response creates and charges a second Stripe subscription.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` :: `createPaidStripeSubscription`
- input revision: `eea53ee9a612`  →  `git show eea53ee9a612:src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`

## S09 · [blind-hunter] `350-pack-refund-rounding-remainder` R4 · high / logic

**Throwing away the key on any server response lets a retry double-charge after the server's own failures**

The comment calls a response 'a definite failure', but the server's own design says otherwise. In SessionPackPaymentService, the path where the charge is captured but the pack record and intent lookup fail returns payment.lifecycleFailure WITHOUT a refund (PACK_PURCHASE_UNRECONCILED). The same applies when the compensating refund fails, and to any 5xx from a gateway or proxy timeout. Stripe also answers a concurrent in-flight request on the same key with an idempotency error, which is mapped to lifecycleFailure. In all these cases the client mints a fresh key and the retry captures a second charge. The test 'mints a new key once the server answered a failure' with a 500 locks this unsafe behaviour in. Only clearly pre-charge 4xx validation errors should release the key.

*Reviewer's own caveat:* Map each status/errorKey that purchasePack can return to whether a charge may already have been captured. In particular, check what HTTP status PaymentGatewayException('payment.lifecycleFailure') becomes.

*Judge:* Concrete trigger: after capture the server returns lifecycleFailure/5xx (PACK_PURCHASE_UNRECONCILED), the client mints a new key, and the retry double-charges.

- where: `src/frontend/src/pages/parent/SessionPackPurchasePage.vue` :: `confirmPurchase`
- input revision: `cb23925514e5`  →  `git show cb23925514e5:src/frontend/src/pages/parent/SessionPackPurchasePage.vue`

## S10 · [control] `350-pack-refund-rounding-remainder` R2 · high / logic

**Parent cancellation more than 24h ahead adds a pack session back that was never taken off**

The new restore enqueue runs whenever sessionPackPurchaseId != null && isRefundEligible(), with no check that the booking was ever charged. A pack session is only deducted when the booking settles (CONFIRMED/UPCOMING), so a parent who cancels a REQUESTED or ACCEPTED pack booking more than 24h ahead gets remainingSessions + 1, which is a free session. The coach, admin, no-show and pause paths all check this with isPackRestore()/paymentCaptured (C6); only the parent path doesn't. The new per-booking marker doesn't help, because that booking was never deducted in the first place.

*Reviewer's own caveat:* Check whether BookingCancelledByParentEvent's refundEligible already takes captured status into account. If it only reflects the >24h rule, cancelling an ACCEPTED pack booking 48h ahead will increase remaining_sessions.

*Judge:* Concrete trigger: a parent cancels a REQUESTED/ACCEPTED pack booking more than 24h ahead, and a session is restored that was never deducted.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/RefundEnqueueListener.java` :: `RefundEnqueueListener.onBookingCancelledByParent`
- input revision: `cb23925514e5`  →  `git show cb23925514e5:src/main/java/com/softropic/skillars/platform/payment/service/RefundEnqueueListener.java`

## S11 · [blind-hunter] `359-charge-idempotency-unknown-outcome` R15 · low / edge-case

**Paid upgrade silently not granted when the subscription is superseded after the Stripe charge**

changeCoachTier charges the proration at Stripe (ALWAYS_INVOICE) and then calls persist. If the row's stripeSubscriptionId changed in between, persist logs a warning and returns. The caller reports success, the user paid for an upgrade that was never applied locally, and nothing alerts or reverts the Stripe price.

*Reviewer's own caveat:* Code shown: the early return only logs a warning; the caller does not check a result.

*Judge:* Concrete trigger: the subscription id changes between the Stripe proration charge and persist, so a paid upgrade is silently dropped.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java` :: `persistCoachTierUpgrade / persistPlayerTierUpgrade`
- input revision: `97f527f111a7`  →  `git show 97f527f111a7:src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java`

## S12 · [blind-hunter] `368-pack-status-badges` R4 · medium / edge-case

**The pause start date is sent as midnight UTC, so the earliest date the picker allows can be refused for users west of UTC**

`new Date('YYYY-MM-DD')` is parsed as UTC midnight. minPauseStartDate is computed from the local date. Example: a user in UTC-5 at 20:00 local on Nov 2 (01:00 UTC Nov 3) can pick at minimum Nov 3. That becomes 2026-11-03T00:00Z, which is already past, so the server throws booking.pauseStartInPast for the earliest date the UI allows. For every user, the pause also starts at UTC midnight rather than local midnight or the coach's timezone. Bookings in the hours around midnight are therefore included in or excluded from the conflict window unexpectedly.

*Reviewer's own caveat:* Pick tomorrow's date with the browser TZ set to America/New_York after 19:00 local. The request should be rejected with pauseStartInPast.

*Judge:* new Date('YYYY-MM-DD') parses as UTC midnight, so users west of UTC can have the earliest allowed date rejected as pauseStartInPast.

- where: `src/frontend/src/pages/parent/SessionPackDashboardPage.vue` :: `submitPause / minPauseStartDate`
- input revision: `b123c73de463`  →  `git show b123c73de463:src/frontend/src/pages/parent/SessionPackDashboardPage.vue`

## S13 · [control] `368-pack-status-badges` R6 · medium / logic

**The parent's saved default card is overwritten before the charge, so a declined card stays saved and the subscription sync is skipped later**

getOrCreateStripeCustomer saves the new paymentMethodId to stripe_customers before chargeAndCapture. If card B is declined, the row keeps B as the default card, even though the code says only a successful charge moves the card. If the parent then retries with B and it succeeds, previousCard is already B. scheduleSubscriptionCardSync then returns early, so subscriptions stay on the old card A while the saved default is B.

*Reviewer's own caveat:* Check whether chargeAndCapture's failure path restores the previous payment method. Nothing in purchasePack does.

*Judge:* The default card is saved before the charge, so a declined card stays as the default and a later successful retry skips the subscription card sync, with a concrete trigger.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` :: `purchasePack / getOrCreateStripeCustomer`
- input revision: `b123c73de463`  →  `git show b123c73de463:src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`

## S14 · [blind-hunter] `376-completion-dispute-integrity` R3 · medium / concurrency

**Concurrent raiseDispute can create two open disputes for one booking, which breaks findOpenByBookingId**

The duplicate check is check-then-insert with no lock. Two raises at the same moment (for example parent and player, or a double-submit) both see none and both insert. After that, findOpenByBookingId returns Optional<Dispute> over two rows and throws IncorrectResultSizeDataAccessException on every later raise. One-dispute-per-booking reasoning elsewhere (the reconcile code assuming the freeze belongs to this dispute) also stops holding: resolving one dispute lifts dispute_frozen while the other is still open.

*Reviewer's own caveat:* Check for a partial unique index on disputes(booking_id) WHERE status NOT IN ('RESOLVED','DISMISSED') in the migrations.

*Judge:* An unlocked check-then-insert lets a double-submit create two open disputes, which then breaks the Optional lookup.

- where: `src/main/java/com/softropic/skillars/platform/admin/repo/DisputeRepository.java` :: `findOpenByBookingId`
- input revision: `ec997389b69f`  →  `git show ec997389b69f:src/main/java/com/softropic/skillars/platform/admin/repo/DisputeRepository.java`

## S15 · [blind-hunter] `381-auth-hardening-rate-limits-and-keys` R1 · high / logic

**Refresh grace window hands the caller another session's live token, then triggers mass revocation**

When a used token is presented within 30s of rotation, the code switches to findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(ownerId). That returns the newest live token of the whole account, not the successor of this token. A user with two devices (laptop A, phone B): A's tab-race refresh rotates and consumes B's token and gets a session. Phone B's next refresh, more than 30s later, presents a used token outside the grace window, so it is treated as theft: markAllUsedByUserId plus invalidateSessionsForUser log out every session. It also means anyone holding a just-rotated stolen token can mint a fresh session for 30s.

*Reviewer's own caveat:* Check that RefreshToken has no successor/family link column. If there is none, the lookup is account-wide as written.

*Judge:* The grace-window lookup returns the account's newest live token rather than this token's successor; the stated two-device trigger is concrete.

- where: `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` :: `AuthService.refresh`
- input revision: `fa62da9dc634`  →  `git show fa62da9dc634:src/main/java/com/softropic/skillars/platform/security/service/AuthService.java`

## S16 · [control] `381-auth-hardening-rate-limits-and-keys` R3 · medium / logic

**Legacy registerAccount stores the login as the raw-case email while lower-casing the email field**

registerAccount sets userDTO.setLogin(userDTO.getEmail()) and toUser copies it into user.login unchanged, while user.email is lower-cased. A user who registers with 'Bob@X.com' gets login='Bob@X.com'. AuthService.login looks up findOneByLogin('bob@x.com'), so the user can never authenticate. The duplicate check (findUserByEmailOrLogin with lower-cased values) also misses the mixed-case login.

*Reviewer's own caveat:* Register via POST /v1/account/register with a mixed-case email, activate it, then try POST /api/auth/login.

*Judge:* Concrete trigger: legacy registerAccount stores the raw-case email as the login while login looks it up lower-cased, so a mixed-case registrant cannot authenticate.

- where: `src/main/java/com/softropic/skillars/platform/security/api/AccountManagementFacade.java` :: `AccountManagementFacade.registerAccount / toUser`
- input revision: `fa62da9dc634`  →  `git show fa62da9dc634:src/main/java/com/softropic/skillars/platform/security/api/AccountManagementFacade.java`

## S17 · [blind-hunter] `385-pack-tier-and-payment-method-input-bounds` R1 · high / logic

**Client-supplied card is saved as the payer's default before the charge, so a declined card sticks**

`getOrCreateStripeCustomer(parentId, paymentMethodId)` writes `stripe_customers.stripe_payment_method_id` before `chargeAndCapture`. If the charge is declined or throws, nothing restores `previousCard`. The javadoc on `scheduleSubscriptionCardSync` says the card change happens only after the charge succeeds ('a declined card is never pushed'), but only the subscription sync is gated that way; the saved default has already been changed. Result: a declined or invalid card becomes the default that `subscribeCoach`/`subscribePlayer` and later pack charges use, while the Stripe subscriptions still point at the old card. The DB and Stripe now disagree, and because `syncPaymentMethodToSubscriptions`' 'superseded' check compares against this new value, the mismatch is never fixed.

*Reviewer's own caveat:* Check whether `PaymentGateway.chargeAndCapture` reads the card from `stripe_customers` (the comment says it does). If so, a failed charge needs to restore `previousCard`, or the card should be passed to the charge directly and saved only after it succeeds.

*Judge:* Concrete trigger: the client card is written to stripe_customers before chargeAndCapture, and nothing restores the old card when the charge is declined.

- where: `src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java` :: `purchasePack / getOrCreateStripeCustomer`
- input revision: `980eff338df6`  →  `git show 980eff338df6:src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`

## S18 · [control] `386-messaging-contact-detail-hardening` R4 · high / concurrency

**Saving the whole Conversation row can undo a concurrent BLOCKED status (report is lost)**

Conversation has no @Version and no @DynamicUpdate, so each save() writes every column. getMessages loads the conversation (status ACTIVE), sets the last-read timestamp and saves. If reportConversation commits BLOCKED in between, the commit from getMessages writes status=ACTIVE back over it. The same race exists in sendMessage's inner transaction: it reads c without a lock, the comment's 're-check' passes, and the save then overwrites a BLOCKED status committed just before. A reported (safety-flagged) conversation silently becomes active again, and the same race can also overwrite lastMessageAt and other parties' last-read columns.

*Reviewer's own caveat:* Confirmed: there is no @Version in BaseEntity/Conversation and reportConversation does not lock the row. To reproduce in an IT, run getMessages and reportConversation in interleaved transactions and check the final status.

*Judge:* Concrete lost-update race: an unversioned full-row save in getMessages/sendMessage can overwrite a concurrently committed BLOCKED status.

- where: `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java` :: `getMessages / sendMessage (transactionTemplate block)`
- input revision: `a4b3ed40c542`  →  `git show a4b3ed40c542:src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`

## S19 · [control] `388-jwt-signing-key-in-database` R2 · low / logic

**Bootstrap runner's 'fail on corrupt/undecryptable row' guard never fires**

The existence check calls secretService.fetchSecret, which only loads the entity and never calls getSecretBytes, so DECR_ERROR/BLANK_SECRET can never be raised here. A broken (v1, jot) row (e.g. hand-written SQL with the wrong Jasypt password) is logged as 'skip_existing SUCCESS' and the app boots, then fails every request when JwtConfiguration.getSecretKey decrypts it, contrary to the javadoc's promise to stop the boot.

*Reviewer's own caveat:* Confirmed by reading SecretService.fetchSecret (no decryption) versus the javadoc's 'Fails startup loudly' bullet.

*Judge:* fetchSecret never decrypts, so a row that cannot be decrypted passes the bootstrap check and the app boots, which contradicts the documented fail-at-startup behaviour.

- where: `src/main/java/com/softropic/skillars/platform/security/service/JwtSecretBootstrapRunner.java` :: `JwtSecretBootstrapRunner.run`
- input revision: `0621d23f5462`  →  `git show 0621d23f5462:src/main/java/com/softropic/skillars/platform/security/service/JwtSecretBootstrapRunner.java`

## S20 · [control] `398-ltd-admin-full-admin-rights` R7 · medium / database

**AlertRule update deletes the old rule before saving the new one in separate transactions, so a failed save loses the rule**

update() is not @Transactional. alertRuleRepository.deleteById(id) commits in its own repository transaction, and save() runs afterwards in a separate one. The request is not validated (@Valid is absent and there are no constraints), so a PUT with a null metricName, a null threshold, or a threshold above the precision(10,4) range breaks the NOT NULL or numeric constraint on save. The original rule has already been deleted, so it is lost and alerting for that metric stops silently. Even when the save succeeds, the rule gets a new generated id, so PUT /{id} changes the resource's id.

*Judge:* Non-transactional delete-then-save with an unvalidated body gives a concrete trigger: a PUT with a null metricName fails the save after the delete has committed, so the rule is lost.

- where: `src/main/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminResource.java` :: `AlertRuleAdminResource.update`
- input revision: `4899534f12a1`  →  `git show 4899534f12a1:src/main/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminResource.java`

## When done

Tally the verdicts. If `real`+`known` is below ~60% of the sample, both arms' precision in `scorecard.md` is materially overstated and the judge prompt needs a stricter `real` rule. Every `real` item becomes a draft case in `cases/_drafts/` (or an added expected finding on an existing case), and every `open` one is a bug to fix.

---

## Tally (reviewed 2026-11-03)

**16 real · 2 known · 2 not-real → real+known = 18/20 (90%)**, well above the ~60% bar: the judge's `real` rule holds up and the scorecard precision is not materially overstated. Both arms sampled identically: control 8 real / 1 known (S08) / 1 not-real (S10); blind-hunter 8 real / 1 known (S02) / 1 not-real (S14).

Both not-real items share a failure shape worth a judge-prompt note: the reviewer *named the exact check that would falsify the finding* in its own caveat (S10: "does refundEligible take captured status into account" — it does; S14: "is there a partial unique index on disputes(booking_id)" — there is, `idx_disputes_unique_open_per_booking` in V138), and the judge credited the finding without running that check.

### Follow-up work

Dedup: S03=S15 (refresh grace window) and S13=S17 (card saved before charge) are one defect each; S04+S16 are two siblings of one root (login stored raw-case, looked up lowercased).

New draft cases: logout-successor-revocation (S01), refresh-grace-window-account-wide (S03/S15), login-case-normalisation (S04+S16), language-filter-case (S05), card-default-before-charge (S13/S17).

Added expected findings on existing cases (same input revision, in scope): S06+S07 → 337, S09 → 350 (frontend file — check scope), S11 → 359, S12 → 368, S18 → 386, S19 → 388, S20 → 398.

Open bugs to fix (status `open` above): S01, S03/S15, S04, S05, S06, S09, S11, S12, S13/S17, S16, S18, S20 — 12 distinct defects, headlined by the auth pair (S01 logout, S03/S15 grace window) and the messaging BLOCKED lost-update (S18).
