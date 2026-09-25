package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.config.AbstractIntegrationTest;
import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import com.softropic.skillars.platform.admin.repo.AdminAlertRepository;
import com.softropic.skillars.platform.admin.repo.GdprRequest;
import com.softropic.skillars.platform.admin.repo.GdprRequestRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-135 AC1: proves {@link GdprErasureService#markFailed} — the alert-raising path
 * this AC fixes, sharing the same {@link GdprErasureService#raiseErasureAlert}/{@code
 * insertErasureAlertIfAbsent} mechanism {@code raiseErasureAlert}'s other callers already use — no
 * longer lets a genuinely concurrent duplicate-alert race take down its own primary write. Pre-fix,
 * {@code markFailed} was a single {@code @Transactional(REQUIRES_NEW)} method whose own catch for a
 * losing {@code (requestId, GDPR_ERASURE_DEADLINE)} race sat INSIDE that transaction — a losing racer
 * would throw {@code UnexpectedRollbackException} at its own commit, silently discarding the
 * {@code FAILED} status write along with the alert. Post-fix, the status write ({@link
 * GdprErasureService#markFailedStatusUpdate}) commits in its own, separate {@code REQUIRES_NEW}
 * transaction BEFORE the alert-raise (now routed through {@link
 * GdprErasureService#raiseErasureAlert}, whose catch sits OUTSIDE its own {@code REQUIRES_NEW}
 * boundary) even begins.
 *
 * <h2>Why two concurrent {@code markFailed} calls for the SAME {@code requestId}, not two different
 * requestIds or a full {@code erase()}-driven race</h2>
 *
 * Unlike {@link AdminAlertEventListenerConcurrencyIT}'s {@code MessagingReportService.reportMessage},
 * which has seven independent public callers that can genuinely race for the same
 * {@code (referenceId, type)} slot, {@code GdprErasureService}'s own alert-raising paths are keyed by
 * {@code requestId} — a single {@code GdprRequest}'s own id — and this codebase has no reachable way
 * to produce two genuinely concurrent racers for the IDENTICAL {@code requestId} today:
 * <ul>
 *   <li>Two duplicate {@code GdprRequest} rows for the same user, processed concurrently, was the
 *       first candidate considered and ruled out: {@code GdprRequestService.requestErasure} guards
 *       against a second {@code PENDING}/{@code PROCESSING} {@code ERASURE} request for the same
 *       {@code userId} ({@code existsByUserIdAndRequestTypeAndStatusIn}), so two DIFFERENT
 *       {@code requestId}s can never collide on the same {@code admin_alerts} unique-index slot
 *       anyway — this scenario would not have exercised the bug even if reachable.</li>
 *   <li>{@code eraseParentChildren}'s own loop over a PARENT's children (the other caller of {@code
 *       raiseErasureAlert}) is a plain sequential {@code for} loop, not concurrent — its own Javadoc
 *       (on {@code insertErasureAlertIfAbsent}) already documents why the reason-blind
 *       {@code alreadyOpen} check alone, not a race, is what protects that path.</li>
 *   <li>{@code markFailed} itself is only ever called from {@link GdprEventListener#onErasureRequested}'s
 *       own catch block, exactly once per thrown exception from a single {@code erase()} invocation —
 *       there is no retry or duplicate-dispatch mechanism in this codebase that would fire it twice
 *       for the same {@code requestId} in production today.</li>
 * </ul>
 *
 * <p><strong>Disclosed: this test's fixture (two direct, concurrent {@code markFailed(requestId)}
 * calls for the identical {@code requestId}) is therefore synthetic, not a reproduction of a reachable
 * production race</strong> — matching this project's own "closed by structural reasoning, not
 * exhaustively proven" precedent for a scenario ruled out (or, here, never reachable) by construction
 * (see {@code skillars-deferred-132} AC1 Fix 6). It is still the most direct, honestly-reachable way to
 * exercise the actual fixed mechanism through the real public API — no reflection, no visibility
 * widening — since after this fix both of {@code GdprErasureService}'s original call shapes
 * ({@code raiseErasureAlert}'s {@code TransactionTemplate} callback, {@code markFailed}'s former
 * annotation-based {@code REQUIRES_NEW}) converge on the identical {@link
 * GdprErasureService#raiseErasureAlert} code path.
 *
 * <p><strong>Mutation-checked by hand (mirroring {@code AdminAlertEventListenerConcurrencyIT}'s own
 * discipline):</strong>
 * <ol>
 *   <li>Reverted the catch in {@code raiseErasureAlert} back inside {@code insertErasureAlertIfAbsent}
 *       (isolation left in place) — this test then fails on assertion (a): the losing racer's
 *       {@code markFailed} call throws {@code UnexpectedRollbackException} out to the caller.</li>
 *   <li>Reverted {@code markFailed} back to a single {@code @Transactional(REQUIRES_NEW)} method
 *       calling {@code insertErasureAlertIfAbsent} directly (pre-135 shape) — this test then also
 *       fails on assertion (a): with the catch removed from {@code insertErasureAlertIfAbsent} as
 *       part of this same fix, the losing racer's raw {@code DataIntegrityViolationException}
 *       propagates straight out of {@code markFailed} uncaught (empirically observed — the
 *       JPA-spec rollback-only marking that produces {@code UnexpectedRollbackException} in
 *       mutation 1 above does not get a chance to apply here, since the flush exception itself
 *       surfaces synchronously before the annotation proxy's own commit-time logic runs). Either
 *       way, the loser's own {@code FAILED} status write is lost along with the poisoned alert
 *       insert, since both share the one transaction in this reverted shape.</li>
 * </ol>
 */
class GdprErasureServiceConcurrencyIT extends AbstractIntegrationTest {

    @Autowired GdprErasureService gdprErasureService;
    @Autowired GdprRequestRepository gdprRequestRepository;
    @Autowired AdminAlertRepository adminAlertRepository;

    /**
     * Two concurrent {@code markFailed} calls for the SAME {@code requestId} — see this class's own
     * Javadoc for why this is the fixture, and why it is disclosed as synthetic rather than a
     * reproduction of a reachable production race.
     */
    @Test
    @Timeout(30)
    void markFailed_concurrentCallsSameRequestId_bothCallsSurviveStatusPersistsAndExactlyOneAlertOpen()
            throws Exception {
        UUID requestId = transactionTemplate.execute(status -> {
            GdprRequest request = new GdprRequest(96602001L, "ERASURE", "PROCESSING");
            return gdprRequestRepository.save(request).getId();
        });

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> callA = pool.submit(() -> {
                startLatch.await();
                gdprErasureService.markFailed(requestId);
                return null;
            });
            Future<?> callB = pool.submit(() -> {
                startLatch.await();
                gdprErasureService.markFailed(requestId);
                return null;
            });

            startLatch.countDown();

            // (a) neither call throws -- no UnexpectedRollbackException escapes either racer.
            assertNoException(callA);
            assertNoException(callB);
        } finally {
            pool.shutdownNow();
        }

        // (b) the FAILED status write durably persists for both racers (they write the SAME row to
        // the SAME terminal value, so this also proves neither racer's own status update was silently
        // rolled back by the other's losing alert-insert race) -- re-read from the database.
        GdprRequest reread = gdprRequestRepository.findById(requestId).orElseThrow();
        assertThat(reread.getStatus())
            .as("markFailedStatusUpdate's own commit must survive even if this racer lost the alert-insert race")
            .isEqualTo("FAILED");

        // (c) exactly one OPEN admin_alerts row exists for this requestId -- the unique index
        // suppressed the loser's duplicate insert, not left two OPEN rows or none at all.
        assertThat(adminAlertRepository.countOpenByReferenceId(requestId.toString()))
            .as("the unique index must have suppressed the loser's duplicate insert, not left two OPEN rows")
            .isEqualTo(1L);
        assertThat(adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
                requestId.toString(), AdminAlertType.GDPR_ERASURE_DEADLINE, AdminAlertStatus.OPEN))
            .isPresent();
    }

    private void assertNoException(Future<?> future) throws InterruptedException, TimeoutException {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("expected markFailed to complete without throwing", e.getCause());
        }
    }
}
