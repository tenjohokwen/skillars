package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.service.AgePolicyService;
import com.softropic.skillars.platform.video.contract.event.VideoModerationAdminAlertEvent;
import com.softropic.skillars.platform.video.contract.exception.MinorUploadRefusedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.softropic.skillars.infrastructure.security.RateLimitingService;

import java.util.concurrent.TimeUnit;

/**
 * ADR "Minors never upload" (product decision 2026-10-28): a player video upload is allowed only for a
 * self-registered ADULT profile. The only player upload endpoints resolve the caller's own profile via
 * {@code PlayerProfile.userId}, and only adults (18+) self-register, so reaching a refusal here means an
 * invariant broke (a minor holding a player account, or a parent-managed profile with a user id) — the
 * refusal therefore also raises an urgent admin alert.
 *
 * <p>Fail-closed everywhere: a missing profile, a parent-managed profile ({@code parent_id} set) and a
 * missing date of birth are all refusals, never "treat as adult". Replaces the old post-upload
 * parental-approval gate (VideoApprovalService), which let a minor's footage reach the provider and
 * moderation before anyone decided whether it should exist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinorUploadPolicy {

    static final String REASON_NO_PROFILE = "PROFILE_NOT_FOUND";
    static final String REASON_MANAGED = "PARENT_MANAGED_PROFILE";
    static final String REASON_NO_DOB = "NO_DATE_OF_BIRTH";
    static final String REASON_MINOR = "MINOR";

    /** At most one admin alert per refused owner per hour — the refusal itself is never rate-limited. */
    static final String ALERT_RATE_KEY = "video.minor_upload_refused.alert";

    private final AgePolicyService agePolicyService;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final RateLimitingService rateLimitingService;

    /** @return {@code null} when the profile may own an upload, otherwise the refusal reason. */
    public String refusalReason(PlayerProfile profile) {
        if (profile == null) {
            return REASON_NO_PROFILE;
        }
        if (profile.getParentId() != null) {
            return REASON_MANAGED;
        }
        if (profile.getDateOfBirth() == null) {
            return REASON_NO_DOB;
        }
        return agePolicyService.isMinor(profile.getDateOfBirth()) ? REASON_MINOR : null;
    }

    /**
     * Refuses (403 {@code VIDEO_MINOR_UPLOAD_REFUSED}) and alerts an admin when {@code profile} may not
     * upload. Called at upload initiation and retry, before any quota reservation or provider call.
     */
    public void assertMayUpload(PlayerProfile profile) {
        String reason = refusalReason(profile);
        if (reason == null) {
            return;
        }
        String ownerId = profile != null ? String.valueOf(profile.getId()) : "unknown";
        log.error("[VIDEO_MINOR_UPLOAD_REFUSED ownerId={} reason={}] player upload refused — a minor or "
            + "managed profile reached a player upload endpoint (invariant break)", ownerId, reason);
        try {
            // A refused account looping the endpoint must not flood the admin inbox (the check runs
            // before VideoService's upload rate limit): one alert per owner per hour.
            if (rateLimitingService.tryConsume(ownerId, ALERT_RATE_KEY, 1, 1, TimeUnit.HOURS)) {
                VideoModerationAdminAlertEvent alert = new VideoModerationAdminAlertEvent(null, ownerId,
                    "Video upload refused — minor or managed player profile",
                    "A player upload was refused at initiation: ownerId=" + ownerId + " reason=" + reason
                        + ". Minors never upload; reaching this means a minor holds a self-registered player "
                        + "account or a managed profile carries a user id. Review the account.",
                    true);
                // Published inside a transaction: the admin-alert envelope is sent by MailManager's
                // AFTER_COMMIT listener, which silently drops an event published with no transaction
                // (this request thread has none).
                transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(alert));
            }
        } catch (RuntimeException e) {
            // The refusal must stand even if the alert cannot be sent; the ERROR log above remains.
            log.error("[VIDEO_MINOR_UPLOAD_REFUSED_ALERT_FAILED ownerId={}]", ownerId, e);
        }
        throw new MinorUploadRefusedException(ownerId, reason);
    }
}
