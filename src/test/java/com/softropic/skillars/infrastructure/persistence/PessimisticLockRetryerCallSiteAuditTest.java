package com.softropic.skillars.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-117 AC5 — {@link PessimisticLockRetryer#withBoundedRetry} retries its supplier
 * from a JDBC savepoint on every {@code PessimisticLockingFailureException}, so the supplier can
 * legitimately execute more than once for a single logical call. That is correct only if the supplier
 * is read-only/side-effect-free — a contract stated solely in {@code PessimisticLockRetryer}'s own
 * javadoc, with nothing mechanical enforcing it across a growing set of call sites (28 at story-
 * creation time, 30 as of {@code skillars-deferred-121}'s two new sites in {@code
 * AdminCoachEnforcementService.reinstateCoach}/{@code .deleteStrike} — mirrors this codebase's own {@code EmailTransportArchitectureTest} /
 * {@code NoStraySmtpConfigTest} convention: a hand-rolled source-text scan rather than a general-
 * purpose static-analysis dependency). Now 31 as of {@code skillars-deferred-127}'s new
 * {@code GdprErasureService.deletePlayerDevelopmentData} lock-acquisition site (the retried lambda
 * is the {@code findByIdForUpdate}+{@code orElseThrow} read only — the method's bulk deletes run
 * after {@code withBoundedRetry} returns, so it passes the same read-only contract as every other
 * site here). Now 32 as of {@code skillars-deferred-130}'s new
 * {@code CoachProfileService.publishProfile} lock-and-recheck site (AC1 Fix 3) — the retried lambda
 * is a {@code findByIdForUpdate}+{@code orElseThrow} read plus an {@code entityManager.refresh},
 * mirroring {@code saveStep4}'s own identical shape, so it passes the same read-only contract too.
 * Now 33 as of {@code skillars-deferred-131}'s new {@code SubscriptionService.syncMarketplaceTier}
 * lock-acquisition site (AC1 Fix 4) — the retried lambda is a {@code findByIdForUpdate}+
 * {@code orElseThrow} read only, the same shape as this class's own {@code deleteVideo}/
 * {@code initiateUpload} restructure below; the {@code coachSubscriptionRepository} writes run after
 * {@code withBoundedRetry} returns. Now 34 as of {@code skillars-deferred-132}'s new
 * {@code ReviewFlagService.flag} lock-acquisition site (AC1 Fix 2) -- converts {@code flag()}'s own
 * lock from the shared blocking {@code CoachReviewRepository.findByIdForUpdate} to a new NOWAIT-only
 * {@code findByIdForUpdateNoWait} method used exclusively by this call site; the retried lambda is a
 * {@code findByIdForUpdateNoWait}+{@code orElseThrow} read only, the flag insert and auto-hold writes
 * run after {@code withBoundedRetry} returns. Now 39 as of {@code skillars-deferred-135} AC3's 5 new
 * sites -- {@code findByIdForUpdateNoWait} is no longer exclusive to {@code flag()}: {@code
 * AdminReviewService.approveReview}/{@code .blockReview}, {@code ReviewSubmissionService
 * .updateReview}/{@code .submitCoachResponse}, and {@code ReviewModerationService
 * .handleReviewSubmitted} all converted from the shared blocking {@code findByIdForUpdate} to it too,
 * each wrapped in its own {@code withBoundedRetry} call mirroring {@code flag()}'s exact shape. Every
 * one of the 5 retried lambdas is a {@code findByIdForUpdateNoWait}+{@code orElseThrow} (or, for
 * {@code handleReviewSubmitted}, a bare {@code findByIdForUpdateNoWait} with the {@code orElseThrow}
 * moved to the caller's own {@code .ifPresentOrElse}) read only -- every write (status/body updates,
 * {@code ReviewModerationLog}/event publishes, {@code coachRatingService.recompute}) runs after
 * {@code withBoundedRetry} returns, the same read-only contract as every other site here. Now 42 as
 * of {@code skillars-deferred-139}'s review Patches 3/4 added 3 new sites -- {@code
 * ShadowAccountService.updateOwnPosition}/{@code .updateChildPosition} and {@code
 * CoachProfileService.deletePhoto} -- each a {@code findByIdForUpdate}+{@code orElseThrow} plus an
 * {@code entityManager.refresh}, mirroring {@code saveStep4}'s own lock-then-refresh shape; the
 * {@code setPosition}/{@code save} and photo-clear/{@code save} writes all run after {@code
 * withBoundedRetry} returns. Now 43 as of {@code skillars-deferred-140} AC1.2's new
 * {@code CoachProfileService.saveStep1} lock site, taken (only when the coach's timezone actually
 * changes) BEFORE any field is set on the profile, so the subsequent bulk availability-window
 * re-stamp serializes against the same per-coach lock {@code saveStep4}/{@code AvailabilityService}
 * already take -- same {@code findByIdForUpdate}+{@code orElseThrow}+{@code entityManager.refresh}
 * read-only shape as every other site here; the profile/window writes all run after {@code
 * withBoundedRetry} returns. Now 55 (master 54 after C3/C4) as of C5's {@code GdprErasureService.scrubCoachProfile}: the
 * coach-profile scrub of a COACH erasure (CoachProfile is {@code @Version}ed since V188) locks and
 * refreshes the row ({@code findByIdForUpdate}+{@code orElseThrow}+{@code entityManager.refresh}) after
 * the recap scrub's REQUIRES_NEW block has committed; the field writes and {@code save} run after
 * {@code withBoundedRetry} returns.
 * Now 56 as of feat/payment-forfeit-payouts-and-clawback's {@code RefundClawbackService.rededuct}: a
 * read-only {@code findByIdForUpdate(packId).orElse(null)} on the pack row; the remaining-sessions check
 * and {@code PackSessionService.deductSession} run after {@code withBoundedRetry} returns.
 * Now 58 as of fix/payment-pack-refund-rounding's {@code PackRefundAllocationService.allocateSessionRefund}
 * and {@code releaseSessionRefund}: each a read-only {@code findByIdForUpdate(packId).orElse(null)} on the
 * pack row; the marker insert/delete, the ledger update and {@code save} run after it returns.
 * Now 61 as of fix/booking-request-consent: {@code BookingService.confirmProposedBooking} (a coach-row
 * {@code findByIdForUpdate}+{@code orElseThrow} plus {@code entityManager.refresh}, the acceptBooking shape)
 * and {@code BookingBatchService.claimBatchForAccept}/{@code releaseAcceptClaim} (each a read-only batch-row
 * {@code findByIdForUpdate}; the ACCEPTING / PENDING status write and {@code save} run after
 * {@code withBoundedRetry} returns — in the claim after {@code .orElseThrow}, in the release inside the
 * chained {@code .ifPresent(}, which the balanced-paren scan correctly excludes).
 * Now 62 as of fix/payment-charge-idempotency's {@code ChargeOutcomeReconciler.lockBooking}: a read-only
 * {@code BookingRepository.findByIdForUpdate(bookingId).orElse(null)}; the reservation re-read, the settle
 * ({@code persistPaymentSuccess}) or decline writes and the event publish run after it returns.
 * Now 64 as of fix/payment-pack-integrity's {@code PackSessionService.refundUnusedSessionsForSuspendedCoach}
 * and {@code PackSessionService.lockPackByPaymentIntent}: each a read-only
 * {@code findByIdForUpdate(purchaseId).orElse(null)} on the pack row; the refund / freeze / revoke writes run
 * after it returns.
 * Now 65 as of fix/audit3-booking-scheduling-integrity's {@code BookingService.lockPackRow}: a read-only
 * {@code findByIdForUpdate(packId).orElseThrow}; the {@code entityManager.refresh} and every check and write
 * run after it returns.
 * Now 66 as of S-27's {@code AgeVerificationService.lockAndLoad}: a read-only
 * {@code userRepository.findByIdForUpdate(userId).orElseThrow}; the daily-cap check, the provider call, the
 * DOB correction and the underage lock all run after it returns.
 * Now 67 as of S-09's {@code CoachSafeguardingService.lockProfile}: a read-only
 * {@code findByIdForUpdate(coachId).orElseThrow}; the erasure-tombstone check and the request / upload writes run
 * after it returns.
 *
 * <p><strong>What this test actually proved, not merely asserted</strong> (AC5's own "Verified by"):
 * building this scan against the real call sites (28 at story-creation time) surfaced one genuine violation —
 * {@code DrillUploadService}'s {@code initiateUpload}/{@code deleteVideo} both had writes
 * ({@code setVideoId}/{@code upsertVideoId}/{@code clearVideoId}) and an {@code eventPublisher.publishEvent(...)}
 * inside the retried lambda. Tracing it, no double-execution was actually reachable (every
 * {@code PessimisticLockingFailureException}-throwing statement in those lambdas preceded every
 * write/publish), but the code was "safe" only by a fragile statement-ordering invariant, not by
 * satisfying the documented contract. Both were restructured — see {@code DrillUploadService}'s own
 * comments — to move the writes/publish to after {@code withBoundedRetry} returns, still inside the
 * same {@code @Transactional} method (the Postgres row lock is held for the whole transaction, not
 * just the lambda's duration, so this preserves the original locking guarantee exactly).
 *
 * <h2>Extraction mechanics</h2>
 * Every {@code .withBoundedRetry(} call site is located by a comment/string-literal-aware source
 * scan (comments and literal contents are blanked, not deleted, so line numbers and paren offsets
 * stay accurate), then the supplier argument is extracted by balanced-paren matching from the call's
 * own opening {@code (} to its matching close — not a naive regex up to the first {@code )} — so both
 * the single-expression form ({@code () -> repo.findByIdForUpdate(id).orElseThrow(...)}) and the
 * block form ({@code () -> { ...; return x; }}) are handled correctly, and a chained call AFTER
 * {@code withBoundedRetry(...)} returns (e.g. {@code withBoundedRetry(() -> repo.findByIdForUpdate(id)).ifPresent(...)}
 * — {@code BookingBatchService}'s two "fire and update" call sites) is correctly excluded from the
 * extracted argument, since the balanced-paren scan stops at {@code withBoundedRetry}'s own closing
 * paren, before the chained {@code .ifPresent(}.
 */
class PessimisticLockRetryerCallSiteAuditTest {

    private static final Path SRC_MAIN = Path.of("src", "main", "java");

    private static final Pattern CALL_SITE = Pattern.compile("\\.withBoundedRetry\\(");

    /**
     * Side-effecting patterns a retried {@code lockedOperation} supplier must never contain —
     * refined against what this codebase's real call sites actually use, per AC5's own
     * instruction not to add a pattern speculatively. Deliberately no bare {@code "Client."} entry
     * (code review: over-matches any local variable or entity field named {@code client}/{@code Client}
     * with no client call involved) — none of the sites use a raw HTTP/external SDK client inside
     * the lambda today, so no client-type denylist entry is needed either.
     */
    private static final List<Pattern> DENYLIST = List.of(
        Pattern.compile("\\.save\\("),
        Pattern.compile("\\.saveAndFlush\\("),
        Pattern.compile("\\.delete\\("),
        Pattern.compile("\\.deleteAll"),
        Pattern.compile("publishEvent\\("),
        Pattern.compile("new\\s+\\w*Event\\("),
        Pattern.compile("\\.send\\("),
        Pattern.compile("RestTemplate"),
        Pattern.compile("\\.enqueue\\(")
    );

    /**
     * The exact known count of real {@code .withBoundedRetry(} call sites under {@code src/main/java}
     * (28 at {@code skillars-deferred-117} story-creation time — the ledger's own stale count was 16 —
     * 30 after {@code skillars-deferred-121} added {@code AdminCoachEnforcementService
     * .reinstateCoach}/{@code .deleteStrike} — 31 after {@code skillars-deferred-127} added
     * {@code GdprErasureService.deletePlayerDevelopmentData} — 32 after {@code skillars-deferred-130}
     * added {@code CoachProfileService.publishProfile} — 33 after {@code skillars-deferred-131}
     * added {@code SubscriptionService.syncMarketplaceTier} -- 34 after {@code skillars-deferred-132}
     * added {@code ReviewFlagService.flag} -- 39 after {@code skillars-deferred-135} AC3
     * added 5 sites: {@code AdminReviewService.approveReview}/{@code .blockReview}, {@code
     * ReviewSubmissionService.updateReview}/{@code .submitCoachResponse}, and {@code
     * ReviewModerationService.handleReviewSubmitted} -- now 42 after {@code skillars-deferred-139}
     * added {@code ShadowAccountService.updateOwnPosition}/{@code .updateChildPosition} and
     * {@code CoachProfileService.deletePhoto} -- now 43 after {@code skillars-deferred-140} AC1.2
     * added {@code CoachProfileService.saveStep1}'s zone-change lock site -- now 44 after {@code
     * skillars-deferred-162} AC5 added {@code DevelopmentDataErasureGuard.isErasedUnderLock}, one shared site
     * for the timeline and homework listeners -- now 46 after feat/admin-moderation-and-enforcement
     * added {@code AdminCoachEnforcementService.setVerificationTier} and {@code
     * AdminUserService.lockAndLoad} (shared by suspendUser/reactivateUser), both read-only locked
     * loads -- now 48 after feat/user-self-service-safety added {@code
     * AppealService.reverseSuspension}/{@code .reverseReviewBlock}, both read-only locked loads taken
     * before the appeal reversal re-locks the same row -- now 50 after feat/coach-workflow-gaps added
     * {@code DrillLibraryService.lockOwnedPrivateDrill} (shared by updateDrill/deleteDrill: a
     * findByIdForUpdate+orElseThrow plus entityManager.refresh, the DrillUploadService shape) and
     * {@code CoachGalleryService.lockProfile} (shared by addImage/deleteItem: a findByIdForUpdate+
     * orElseThrow read); every drill/gallery write runs after withBoundedRetry returns) -- now 51 after
     * fix/payment-money-integrity added {@code CreditWalletService.debitForCashOut} (a
     * StripeCustomerRepository.findByIdForUpdate+orElseThrow read, the per-payer wallet lock; the
     * balance read and ledger writes run after withBoundedRetry returns) -- now 52 after
     * fix/security-session-revocation added {@code AccountSuspensionEventListener.onAccountSuspensionRequested}'s
     * findByIdForUpdate read (the SUSPENDED/lock writes and session revocation run after it returns) -- now 53
     * after fix/content-consistency-and-lost-updates added {@code SessionPlanService.updateSession} (a
     * SessionRepository.findByIdForUpdate+orElseThrow plus entityManager.refresh; the plan save runs
     * after withBoundedRetry returns) -- now 54 after feat/coach-payouts-pack-and-credit added {@code
     * CoachPayoutOutboxSupport.allocatePackShare} (a
     * SessionPackPurchaseRepository.findByIdForUpdate+orElse(null) read; the pack allocation write runs
     * after withBoundedRetry returns) -- now 62 after fix/payment-charge-idempotency added {@code
     * ChargeOutcomeReconciler.lockBooking} (a BookingRepository.findByIdForUpdate+orElse(null) read; the
     * settle/decline writes and event publish run after withBoundedRetry returns). Asserted explicitly so an
     * added or removed call site is loud (this count changes) rather than silently changing how much
     * code this test covers.
     */
    private static final int EXPECTED_CALL_SITE_COUNT = 67;

    private record CallSite(String file, int line, String argument) {
    }

    @Test
    @DisplayName("every .withBoundedRetry( call site's retried lambda is free of side-effecting patterns")
    void everyCallSite_lambdaIsReadOnly() throws IOException {
        List<CallSite> callSites = findCallSites();

        assertThat(callSites)
            .as("expected exactly %d real .withBoundedRetry( call sites under src/main/java. If this "
                + "count changed, a call site was added or removed — re-verify the new/changed site(s) "
                + "against the DENYLIST above rather than just updating this number. Found: %s",
                EXPECTED_CALL_SITE_COUNT, callSites)
            .hasSize(EXPECTED_CALL_SITE_COUNT);

        List<String> offenders = new ArrayList<>();
        for (CallSite site : callSites) {
            for (Pattern denied : DENYLIST) {
                if (denied.matcher(site.argument()).find()) {
                    offenders.add(site.file() + ":" + site.line() + " — matched '" + denied.pattern()
                        + "' inside the retried lambda: " + oneLine(site.argument()));
                }
            }
        }

        assertThat(offenders)
            .as("PessimisticLockRetryer.withBoundedRetry's contract requires the retried supplier to "
                + "be read-only/side-effect-free — it can legitimately execute more than once per "
                + "logical call. Move the offending call(s) to run after withBoundedRetry returns, "
                + "inside the same @Transactional method (the Postgres row lock is held for the whole "
                + "transaction, not just the lambda's duration, so this preserves the original locking "
                + "guarantee) — see DrillUploadService.initiateUpload/deleteVideo for the pattern.")
            .isEmpty();
    }

    /**
     * skillars-deferred-132 AC4 Fix 11. The test above only ever looks INSIDE a
     * {@code .withBoundedRetry(} call it already found — it never asks "does every NOWAIT lock
     * acquisition have a wrapper at all." A brand-new {@code findByIdForUpdate}-style call added to one
     * of the NOWAIT repositories below, without a {@code .withBoundedRetry(} anywhere in the same file,
     * is entirely invisible to {@link #everyCallSite_lambdaIsReadOnly}.
     *
     * <p><strong>Scope — file, not method (a deliberate, cheaper choice, named explicitly):</strong>
     * the existing scan above has no method-boundary parsing (it works on file offsets and
     * balanced-paren matching from {@code .withBoundedRetry(} itself), and a genuine "is THIS call
     * inside THIS method wrapped" check would require adding that. This check instead asks the
     * cheaper question "does this file contain both a NOWAIT lock-method call and at least one
     * {@code .withBoundedRetry(}" — consistent with the existing test's own rigor level, at the cost of
     * a higher false-negative rate (an unwrapped call in a file that also happens to wrap something
     * else elsewhere in that same file would pass undetected).
     *
     * <p><strong>Exempt, by design — four repositories declare {@code findByIdForUpdate} WITHOUT
     * {@code @QueryHints(lock.timeout = 0)}</strong> — genuinely blocking locks that correctly have no
     * retry wrapper: {@code VideoQuotaRepository} ({@code QuotaService}), {@code CoachPayoutRepository}
     * ({@code CoachPayoutTransferHandler}, {@code DisputeService}), {@code MessageRepository}
     * ({@code AdminMessageService}, {@code MessagingService}, {@code ModerationResultApplier},
     * {@code MessageModerationSweeper}), and {@code CoachReviewRepository}'s OWN {@code
     * findByIdForUpdate} (its five other call sites — {@code ReviewSubmissionService.updateReview}/
     * {@code submitCoachResponse}, {@code AdminReviewService.approveReview}/{@code blockReview},
     * {@code ReviewModerationService}'s {@code AFTER_COMMIT} listener — deliberately stay blocking;
     * only {@code CoachReviewRepository.findByIdForUpdateNoWait}, added by this story's own Fix 2 for
     * {@code ReviewFlagService.flag()} alone, is NOWAIT). This is the set that would need revisiting if
     * the NOWAIT convention is ever made universal across this codebase — not a temporary gap.
     */
    @Test
    @DisplayName("every NOWAIT findByIdForUpdate-style lock call has a .withBoundedRetry( wrapper somewhere in its file")
    void everyNoWaitLockCallSite_hasAWithBoundedRetryWrapperInItsFile() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_MAIN)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
            for (Path file : javaFiles) {
                offenders.addAll(findUnwrappedNoWaitLockCallsInFile(file));
            }
        }

        assertThat(offenders)
            .as("A NOWAIT findByIdForUpdate-style lock acquisition was found in a file with no "
                + ".withBoundedRetry( anywhere in it — see this test's own javadoc for the exempt "
                + "(genuinely-blocking) repositories that must NOT trigger this, and "
                + "PessimisticLockRetryer's own javadoc for why an unretried NOWAIT lock is a bug, not a "
                + "style choice.")
            .isEmpty();
    }

    /**
     * Platform cleanup (B8): the map below is hand-maintained, and it silently missed
     * {@code UserRepository} — the scan above then simply never looked at its call sites. This derives
     * the NOWAIT set from the repositories themselves (every method annotated with
     * {@code jakarta.persistence.lock.timeout = "0"}) and fails if the map does not cover it.
     */
    @Test
    @DisplayName("every NOWAIT repository lock method is registered for the unwrapped-call scan")
    void everyNoWaitRepositoryMethod_isRegisteredInTheScanMap() throws IOException {
        // Any @QueryHint whose attributes (in either order, any spacing) name the lock-timeout hint
        // with value "0" — then the first method name declared after the enclosing annotations.
        Pattern hint = Pattern.compile("@QueryHint\\s*\\(([^)]*)\\)");
        Pattern isNoWait = Pattern.compile(
            "(?=.*name\\s*=\\s*\"jakarta\\.persistence\\.lock\\.timeout\")(?=.*value\\s*=\\s*\"0\")",
            Pattern.DOTALL);
        Pattern nextMethod = Pattern.compile("\\b(\\w+)\\s*\\(\\s*@Param|\\b(\\w+)\\s*\\([^)@]*\\)\\s*;");
        List<String> missing = new ArrayList<>();
        int found = 0;
        int rawNoWaitHints = 0;
        try (Stream<Path> files = Files.walk(SRC_MAIN)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
            for (Path file : javaFiles) {
                String source = blankCommentsOnly(Files.readString(file, StandardCharsets.UTF_8));
                String repoType = file.getFileName().toString().replace(".java", "");
                Matcher h = hint.matcher(source);
                while (h.find()) {
                    if (!isNoWait.matcher(h.group(1)).find()) {
                        continue;
                    }
                    rawNoWaitHints++;
                    Matcher m = nextMethod.matcher(source);
                    if (!m.find(h.end())) {
                        missing.add(repoType + ": NOWAIT hint with no method after it");
                        continue;
                    }
                    found++;
                    String method = m.group(1) != null ? m.group(1) : m.group(2);
                    if (!method.equals(NOWAIT_LOCK_METHODS_BY_REPOSITORY_TYPE.get(repoType))) {
                        missing.add(repoType + "." + method);
                    }
                }
            }
        }
        assertThat(found).as("every NOWAIT hint must resolve to a method").isEqualTo(rawNoWaitHints);
        assertThat(found).as("sanity: the NOWAIT-method scan must find the known repositories")
            .isGreaterThanOrEqualTo(NOWAIT_LOCK_METHODS_BY_REPOSITORY_TYPE.size());
        assertThat(missing)
            .as("NOWAIT lock methods missing from NOWAIT_LOCK_METHODS_BY_REPOSITORY_TYPE — an unretried "
                + "call to them would go undetected")
            .isEmpty();
    }

    /**
     * Repository type → its NOWAIT-only lock method name. Only repositories confirmed today (per this
     * test's own javadoc) to declare {@code @QueryHints(jakarta.persistence.lock.timeout = "0")} on
     * that method belong here. {@code UserRepository} was missing until the platform cleanup (B8)
     * even though its {@code findByIdForUpdate} is NOWAIT (admin suspend/reactivate in
     * {@code AdminUserService}); {@code Map.ofEntries} because {@code Map.of} stops at ten pairs.
     */
    private static final Map<String, String> NOWAIT_LOCK_METHODS_BY_REPOSITORY_TYPE = Map.ofEntries(
        Map.entry("VideoRepository", "findByIdForUpdate"),
        Map.entry("PlayerProfileRepository", "findByIdForUpdate"),
        Map.entry("BookingRescheduleRequestRepository", "findByIdForUpdate"),
        Map.entry("BookingRepository", "findByIdForUpdate"),
        Map.entry("BookingBatchRepository", "findByIdForUpdate"),
        Map.entry("SessionPackPurchaseRepository", "findByIdForUpdate"),
        Map.entry("CoachProfileRepository", "lockByIdForUpdate"),
        Map.entry("DrillRepository", "findByIdForUpdate"),
        Map.entry("CoachReviewRepository", "findByIdForUpdateNoWait"),
        Map.entry("StripeCustomerRepository", "findByIdForUpdate"),
        Map.entry("SessionRepository", "lockByIdForUpdate"),
        Map.entry("UserRepository", "findByIdForUpdate")
    );

    /**
     * C5: repository type → a {@code default} method that delegates to that repository's registered
     * NOWAIT lock method (scalar lock + {@code findById}, so a {@code @Version} entity that is already
     * managed is never version-checked by a lock upgrade). Callers use the delegating name, so the
     * unwrapped-call scan checks it too — it takes the same NOWAIT lock.
     */
    private static final Map<String, String> NOWAIT_DELEGATING_DEFAULT_METHODS = Map.of(
        "CoachProfileRepository", "findByIdForUpdate",
        "SessionRepository", "findByIdForUpdate"
    );

    private static List<String> findUnwrappedNoWaitLockCallsInFile(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String scanned = blankCommentsAndLiterals(raw);
        boolean fileHasWrapper = CALL_SITE.matcher(scanned).find();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> entry : NOWAIT_LOCK_METHODS_BY_REPOSITORY_TYPE.entrySet()) {
            String repoType = entry.getKey();
            List<String> lockMethods = NOWAIT_DELEGATING_DEFAULT_METHODS.containsKey(repoType)
                ? List.of(entry.getValue(), NOWAIT_DELEGATING_DEFAULT_METHODS.get(repoType))
                : List.of(entry.getValue());

            // Field/param declarations of this repository type, e.g. "CoachProfileRepository
            // coachProfileRepository" — captures the local variable name this file calls it through.
            Matcher fieldMatcher = Pattern.compile(
                "\\b" + Pattern.quote(repoType) + "\\s+(\\w+)\\s*[;,)]").matcher(scanned);
            while (fieldMatcher.find()) {
                String varName = fieldMatcher.group(1);
                for (String lockMethod : lockMethods) {
                    Pattern callPattern = Pattern.compile(
                        Pattern.quote(varName) + "\\." + Pattern.quote(lockMethod) + "\\(");
                    if (callPattern.matcher(scanned).find() && !fileHasWrapper) {
                        offenders.add(file + ": declares " + repoType + " " + varName + " and calls ."
                            + lockMethod + "( but the file contains no .withBoundedRetry( anywhere");
                    }
                }
            }
        }
        return offenders;
    }

    // --- call-site discovery -------------------------------------------------------------------

    private static List<CallSite> findCallSites() throws IOException {
        List<CallSite> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_MAIN)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
            for (Path file : javaFiles) {
                out.addAll(findCallSitesInFile(file));
            }
        }
        return out;
    }

    private static List<CallSite> findCallSitesInFile(Path file) {
        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String scanned = blankCommentsAndLiterals(raw);

        List<CallSite> out = new ArrayList<>();
        Matcher m = CALL_SITE.matcher(scanned);
        while (m.find()) {
            int openParen = m.end() - 1; // '.withBoundedRetry(' — m.end() - 1 is the '(' itself
            int closeParen = matchingParen(scanned, openParen);
            if (closeParen < 0) {
                throw new IllegalStateException(file + ": unbalanced parentheses scanning a "
                    + ".withBoundedRetry( call at offset " + m.start() + " — this audit test cannot "
                    + "verify this call site; fix the source (or the scanner if the source is "
                    + "legitimately unusual) rather than ignoring it");
            }
            String argument = scanned.substring(openParen + 1, closeParen);
            int line = 1 + countNewlines(scanned, m.start());
            out.add(new CallSite(file.toString(), line, argument));
        }
        return out;
    }

    /** The index of the {@code )} matching {@code text.charAt(openIdx) == '('}, or {@code -1}. */
    private static int matchingParen(String text, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int countNewlines(String text, int uptoExclusive) {
        int count = 0;
        for (int i = 0; i < uptoExclusive; i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Blanks {@code //}/{@code /* *}{@code /} comments and the contents of string/char literals
     * (including {@code """} text blocks) to single spaces, character-for-character, so every
     * remaining character keeps its original offset (line numbers stay accurate) and no stray paren
     * inside a comment or string literal can desync the balanced-paren scan above. Code, including
     * every real {@code (}/{@code )}/{@code {}/{@code }}, is left untouched.
     */
    private static String blankCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int nl = source.indexOf('\n', i);
                i = nl < 0 ? n : nl; // stop right before '\n' so it is appended normally next loop
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append(' '); // closing quote
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Like {@link #blankCommentsAndLiterals} but keeps string literals (annotation values live there). */
    private static String blankCommentsOnly(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static String oneLine(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }
}
