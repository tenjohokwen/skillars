package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationCategory;
import com.softropic.skillars.platform.notification.contract.NotificationPreferenceDto;
import com.softropic.skillars.platform.notification.repo.NotificationEmailPreferenceRepository;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup.ResolvedRecipient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-category email opt-out (notification centre, UI-gap chunk C9). Only categories with
 * {@link NotificationCategory#optOutable()} can be turned off; transactional and security email is
 * always sent — see {@link NotificationCategory} for the classification.
 *
 * <p>Enforcement is at <strong>send time</strong> ({@link #emailDecision}, called by
 * {@code MailService} immediately before rendering), not at enqueue time, so an opt-out made while
 * a reminder sits in the outbox or in {@code EmailRetryScheduler}'s retry queue still takes effect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    /** The send-time outcome for one recipient of one email — see {@link #emailDecision}. */
    public record EmailDecision(boolean suppressed, String unsubscribeToken) {
        static final EmailDecision SEND_PLAIN = new EmailDecision(false, null);
        static final EmailDecision SUPPRESS = new EmailDecision(true, null);
    }

    private final NotificationEmailPreferenceRepository preferenceRepository;
    private final NotificationRecipientLookup recipientLookup;
    private final UnsubscribeTokenService tokenService;
    private final JdbcSavepoints savepoints;

    @Transactional(readOnly = true)
    public List<NotificationPreferenceDto> getPreferences(final long userId) {
        final Map<String, Boolean> stored = preferenceRepository.findByUserId(userId);
        return Arrays.stream(NotificationCategory.values())
            .filter(NotificationCategory::optOutable)
            .map(c -> new NotificationPreferenceDto(c, stored.getOrDefault(c.name(), Boolean.TRUE)))
            .toList();
    }

    @Transactional
    public NotificationPreferenceDto setPreference(final long userId, final NotificationCategory category,
                                                   final boolean emailEnabled) {
        if (!category.optOutable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "notification category " + category + " is transactional and cannot be turned off");
        }
        preferenceRepository.upsert(userId, category.name(), emailEnabled);
        return new NotificationPreferenceDto(category, emailEnabled);
    }

    /**
     * One-click unsubscribe (RFC 8058) — unauthenticated; the signed token is the credential.
     * Idempotent (an upsert to the same value). Rate-limited per client IP like the other public
     * token endpoints ({@code verify-email}, {@code resend-otp}), but with a much larger bucket: RFC
     * 8058 one-click POSTs are sent by the mailbox provider's servers, so many users' unsubscribes
     * share one source IP. Brute force is not the threat here (a 256-bit MAC), DB load is.
     */
    @RateLimited(key = "notification_unsubscribe", capacity = 120, duration = 1)
    @Transactional
    public NotificationCategory unsubscribe(final String token) {
        final UnsubscribeTokenService.Claims claims = tokenService.verify(token)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid unsubscribe token"));
        if (preferenceRepository.upsert(claims.userId(), claims.category().name(), false) == 0) {
            // Correctly signed but the user no longer exists (hard-deleted) — same response as a bad
            // token so the endpoint is not an account-existence oracle.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid unsubscribe token");
        }
        log.info("[EMAIL_UNSUBSCRIBE] userId={} category={}", claims.userId(), claims.category());
        return claims.category();
    }

    /**
     * Send-time decision for one recipient of one email. Transactional templates short-circuit with
     * no DB access. For an opt-out-able template: suppressed if the recipient's user has turned the
     * category off; otherwise sent with a signed unsubscribe token (null when the address belongs to
     * no platform user, in which case there is nothing to unsubscribe).
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}, and the reads run in a JDBC savepoint
     * ({@link JdbcSavepoints}): this runs inside {@code MailManager.sendEmailSync}'s own
     * {@code REQUIRES_NEW} transaction, and a failed statement there would otherwise abort that
     * transaction (PostgreSQL) or mark it rollback-only (a participating proxy) — taking down the
     * {@code EnvelopeEntity} FAILED/retry bookkeeping {@code MailManager} writes afterwards. A
     * failure is rethrown for {@code MailService} to report as a retryable send failure: neither
     * sending (it might ignore an opt-out) nor dropping (it might lose a wanted reminder) is safe.
     */
    public EmailDecision emailDecision(final EmailTemplate template, final String toAddress) {
        if (template == null) {
            return EmailDecision.SEND_PLAIN;
        }
        final NotificationCategory category = NotificationCatalog.of(template).category();
        if (!category.optOutable()) {
            return EmailDecision.SEND_PLAIN;
        }
        return savepoints.run(() -> {
            final Optional<ResolvedRecipient> recipient = recipientLookup.findByEmail(toAddress, false);
            if (recipient.isEmpty()) {
                return EmailDecision.SEND_PLAIN;
            }
            final long userId = recipient.get().userId();
            final boolean enabled =
                preferenceRepository.findEmailEnabled(userId, category.name()).orElse(Boolean.TRUE);
            if (!enabled) {
                return EmailDecision.SUPPRESS;
            }
            return new EmailDecision(false, tokenService.mint(userId, category));
        });
    }
}
