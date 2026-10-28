package com.softropic.skillars.platform.notification.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one INSERT into {@code main.user_notifications}. Plain JDBC, deliberately not a Spring Data
 * query: it runs inside a savepoint of the producing business transaction
 * ({@code InAppNotificationService} / {@code JdbcSavepoints}), and a JPA query would auto-flush the
 * business session first — rolling back to the savepoint would then discard business SQL Hibernate
 * believes it flushed.
 */
@Repository
@RequiredArgsConstructor
public class UserNotificationWriter {

    private final JdbcTemplate jdbcTemplate;

    /**
     * {@code ON CONFLICT DO NOTHING} against {@code uq_un_recipient_send_id}: a second hook for the
     * same (recipient, sendId) is a no-op rather than a duplicate inbox row.
     *
     * @return 1 if a row was inserted, 0 if it already existed
     */
    public int insertIfAbsent(final long recipientUserId, final String category, final String type,
                              final String paramsJson, final String deepLink, final String sendId) {
        return jdbcTemplate.update(
            "INSERT INTO main.user_notifications "
                + "(recipient_user_id, category, type, params, deep_link, source_send_id, created_at) "
                + "VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, now()) "
                + "ON CONFLICT (recipient_user_id, source_send_id) WHERE source_send_id IS NOT NULL DO NOTHING",
            recipientUserId, category, type, paramsJson, deepLink, sendId);
    }
}
