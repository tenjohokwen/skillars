package com.softropic.skillars.platform.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.NotificationDto;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup;
import com.softropic.skillars.platform.notification.repo.NotificationRecipientLookup.ResolvedRecipient;
import com.softropic.skillars.platform.notification.repo.UserNotification;
import com.softropic.skillars.platform.notification.repo.UserNotificationRepository;
import com.softropic.skillars.platform.notification.repo.UserNotificationWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The in-app inbox (notification centre, UI-gap chunk C9).
 *
 * <h2>Where rows come from</h2>
 * {@link #recordForEmail} is called at the exact point an in-app-eligible user-facing email is
 * triggered, inside the business transaction that triggers it — not from a parallel event path:
 * <ul>
 *   <li>{@code NotificationOutboxSupport.enqueueEmail} (the outbox route: every booking, reminder and
 *       session-pack email), which is {@code MANDATORY} inside the producers' BEFORE_COMMIT
 *       listeners;</li>
 *   <li>{@code InAppEnvelopeListener} (the direct-{@code Envelope} route: report shared, video
 *       moderation owner notice), BEFORE_COMMIT on the same {@code Envelope} event that
 *       {@code MailManager} sends AFTER_COMMIT.</li>
 * </ul>
 * Both run inside the producing transaction, so the inbox row commits with the business change or
 * not at all: a rolled-back transaction never creates a notification. Templates that
 * {@link NotificationCatalog} marks email-only return before any DB access.
 *
 * <h2>The inbox can never fail the business transaction</h2>
 * The work runs in a JDBC savepoint ({@link JdbcSavepoints}) and every failure is caught and logged:
 * a broken inbox write rolls back to the savepoint and the booking / report / moderation transaction
 * commits as it would have without this feature. (A {@code @Transactional(MANDATORY)} method could
 * not promise that: its proxy marks the shared transaction rollback-only before any caller's catch
 * runs, and on PostgreSQL a failed statement aborts the transaction regardless.) The price is that
 * an inbox row can, rarely, be missing for an email that was sent — the email is the record.
 *
 * <h2>Writes are recipient-scoped single statements</h2>
 * Mark-read / mark-all-read are one {@code UPDATE ... WHERE id = ? AND recipient_user_id = ?} each,
 * so another user's notification id is a silent no-op (no existence oracle), repeats are idempotent
 * and there is no load-check-write window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InAppNotificationService {

    static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PARAM_LENGTH = 200;
    /** A deep link must be an internal hash-router path: leading single slash, safe characters only. */
    private static final Pattern SAFE_DEEP_LINK = Pattern.compile("^/(?!/)[A-Za-z0-9/_\\-]{0,500}$");

    private final UserNotificationRepository notificationRepository;
    private final UserNotificationWriter notificationWriter;
    private final NotificationRecipientLookup recipientLookup;
    private final JdbcSavepoints savepoints;
    private final FrontendLinkBuilder frontendLinks;
    private final ObjectMapper objectMapper;

    /**
     * Records the inbox row for an email being triggered, inside the caller's transaction (in a
     * savepoint). Never throws — see "The inbox can never fail the business transaction" above.
     */
    public void recordForEmail(final EmailTemplate template, final String recipientEmail,
                               final Map<String, Object> data, final String sendId) {
        final NotificationCatalog.Entry entry = NotificationCatalog.of(template);
        if (!entry.inApp()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Both callers run inside the producing transaction; a write here without one would
            // never commit (hikari auto-commit=false) and would break the atomicity contract.
            log.warn("[IN_APP_NOTIFICATION_SKIPPED] no active transaction; template={} sendId={}", template, sendId);
            return;
        }
        try {
            savepoints.run(() -> {
                insert(entry, template, recipientEmail, data, sendId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("[IN_APP_NOTIFICATION_FAILED] template={} sendId={} — inbox row not written; the "
                + "business transaction and the email are unaffected", template, sendId, e);
        }
    }

    private void insert(final NotificationCatalog.Entry entry, final EmailTemplate template,
                        final String recipientEmail, final Map<String, Object> data, final String sendId) {
        // The role join is only needed when the deep link actually differs by audience.
        final boolean needsRole = entry.coachLink() != null && !entry.coachLink().equals(entry.otherLink());
        final Optional<ResolvedRecipient> recipient = recipientLookup.findByEmail(recipientEmail, needsRole);
        if (recipient.isEmpty()) {
            // An address with no platform account (e.g. an admin alias) has no inbox to write to.
            log.debug("[IN_APP_NOTIFICATION] no user owns the recipient address; skipped template={} sendId={}",
                template, sendId);
            return;
        }
        final ResolvedRecipient r = recipient.get();
        final String deepLink = deepLinkFor(entry, r.coach(), data);
        final String paramsJson = toJson(whitelistedParams(data));
        notificationWriter.insertIfAbsent(
            r.userId(), entry.category().name(), template.name(), paramsJson, deepLink, sendId);
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> list(final long userId, final int page, final int size) {
        final int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        final int boundedPage = Math.max(0, page);
        return notificationRepository
            .findByRecipientUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(boundedPage, boundedSize))
            .map(InAppNotificationService::toDto);
    }

    @Transactional(readOnly = true)
    public long unreadCount(final long userId) {
        return notificationRepository.countByRecipientUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(final long userId, final UUID notificationId) {
        notificationRepository.markRead(notificationId, userId);
    }

    @Transactional
    public int markAllRead(final long userId) {
        return notificationRepository.markAllRead(userId);
    }

    static NotificationDto toDto(final UserNotification n) {
        return new NotificationDto(n.getId(), n.getCategory(), n.getType(),
            n.getParams() == null ? Map.of() : n.getParams(), n.getDeepLink(), n.getCreatedAt(), n.getReadAt());
    }

    private String deepLinkFor(final NotificationCatalog.Entry entry, final boolean coach,
                               final Map<String, Object> data) {
        if (entry.linkDataKey() != null && data != null
                && data.get(entry.linkDataKey()) instanceof String url) {
            final String prefix = frontendLinks.base() + "/#";
            if (url.startsWith(prefix)) {
                final String path = url.substring(prefix.length());
                if (SAFE_DEEP_LINK.matcher(path).matches()) {
                    return path;
                }
            }
        }
        final String link = entry.deepLinkFor(coach);
        return link != null && SAFE_DEEP_LINK.matcher(link).matches() ? link : null;
    }

    /** Only whitelisted keys with string (or number/boolean) values survive; everything else is dropped. */
    static Map<String, String> whitelistedParams(final Map<String, Object> data) {
        final Map<String, String> params = new TreeMap<>();
        if (data == null) {
            return params;
        }
        for (final String key : NotificationCatalog.PARAM_WHITELIST) {
            final Object raw = data.get(key);
            // Some producers still put ints (creditsRemaining, requestedCount, acceptedCount) despite
            // the string-typed email-data contract; keep them rather than render a blank.
            final String value = raw instanceof String str ? str
                : raw instanceof Number || raw instanceof Boolean ? String.valueOf(raw) : null;
            if (value != null && !value.isBlank()) {
                params.put(key, value.length() > MAX_PARAM_LENGTH ? value.substring(0, MAX_PARAM_LENGTH) : value);
            }
        }
        return params;
    }

    private String toJson(final Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            // Unreachable for a Map<String, String>; rethrown (not swallowed) for the same reason
            // NotificationOutboxSupport rethrows — this layer reports, it does not decide.
            throw new IllegalStateException("could not serialise in-app notification params", e);
        }
    }
}
