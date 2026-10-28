package com.softropic.skillars.platform.admin.service;

import com.softropic.skillars.platform.admin.contract.AdminSuspendCoachResponse;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.security.contract.event.AccountSuspensionRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Admin-side half of a CSAM safety suspension (B6, issue 6). {@code AccountSuspensionEventListener}
 * (security) suspends the <em>account</em>; without this the coach's marketplace profile stayed ACTIVE —
 * still listed, still bookable — for as long as it took an admin to notice.
 *
 * <p>ID space: {@link AccountSuspensionRequestedEvent#ownerId()} is the video's {@code ownerId} — the
 * {@code CoachProfile} UUID for a coach upload and the {@code PlayerProfile} id (a number) for a player
 * upload ({@code VideoResource.resolveCurrentOwnerId}). Only a UUID that names an existing coach profile is
 * acted on; a player owner has no marketplace profile to take down.
 *
 * <p>Commit safety: {@code AFTER_COMMIT} with {@code fallbackExecution = true} — the publisher
 * ({@code ModerationOrchestrationService}) has no transaction today, so this runs immediately; if a
 * future publisher does, the suspension only happens once that transaction has committed. The work runs
 * in its own {@code REQUIRES_NEW} transaction ({@link AdminCoachEnforcementService#suspendCoachForSafetyHold})
 * and is idempotent, and this method never throws: the publisher's sibling listeners (the account
 * suspension itself) must still run, and the publisher already raised the CSAM admin alert before
 * publishing. {@code HIGHEST_PRECEDENCE}: with no transaction the multicaster calls listeners inline and stops at
 * the first one that throws, so this never-throwing listener runs first — a failure in the account suspension
 * listener can then never prevent the profile hold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachSafetySuspensionListener {

    /** Whole-hold attempts on lock contention (each already retries every row lock ~3s). */
    static final int SAFETY_HOLD_ATTEMPTS = 3;

    private final CoachProfileRepository coachProfileRepository;
    private final AdminCoachEnforcementService enforcementService;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountSuspensionRequested(AccountSuspensionRequestedEvent event) {
        UUID coachId = parseCoachId(event.ownerId());
        if (coachId == null) {
            return;
        }
        try {
            if (!coachProfileRepository.existsById(coachId)) {
                log.warn("[COACH_SAFETY_SUSPENSION] ownerId={} is a UUID but names no coach profile; "
                    + "videoId={}", event.ownerId(), event.videoId());
                return;
            }
            AdminSuspendCoachResponse result = suspendWithRetry(coachId, event.videoId());
            log.error("[COACH_SAFETY_SUSPENSION] coachId={} videoId={} newlySuspended={} cancelledBookings={} "
                    + "urgentBookingAlerts={}", coachId, event.videoId(), result.newlySuspended(),
                result.cancelledBookings(), result.alertedBookings());
        } catch (RuntimeException e) {
            log.error("CRITICAL: [COACH_SAFETY_SUSPENSION_FAILED] coach profile still live after a CSAM match: "
                + "coachId={} videoId={} — suspend it from the admin console", coachId, event.videoId(), e);
        }
    }

    /**
     * C2: the hold now also locks every not-yet-started booking of the coach, so a single booking row
     * held past {@code PessimisticLockRetryer}'s budget (a parent cancelling at that instant) exhausts it
     * — and, the final NOWAIT failure having aborted the Postgres transaction, rolls the whole hold
     * back, profile included. The hold must land: re-run it (each attempt is its own
     * {@code REQUIRES_NEW} transaction and the hold is idempotent) before giving up loudly.
     */
    private AdminSuspendCoachResponse suspendWithRetry(UUID coachId, UUID videoId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return enforcementService.suspendCoachForSafetyHold(coachId, videoId);
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= SAFETY_HOLD_ATTEMPTS) {
                    throw e;
                }
                log.warn("[COACH_SAFETY_SUSPENSION] lock contention on attempt {} — retrying: coachId={}",
                    attempt, coachId);
            }
        }
    }

    private static UUID parseCoachId(String ownerId) {
        if (ownerId == null) {
            return null;
        }
        try {
            return UUID.fromString(ownerId);
        } catch (IllegalArgumentException e) {
            return null; // a PlayerProfile id — no marketplace profile
        }
    }
}
