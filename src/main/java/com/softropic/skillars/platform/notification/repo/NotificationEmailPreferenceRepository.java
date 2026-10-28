package com.softropic.skillars.platform.notification.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sparse per-category email opt-out rows ({@code main.notification_email_preferences}); no row means
 * the default (email enabled). Plain {@link JdbcTemplate} rather than a JPA entity: the table is a
 * key/value toggle with no domain behaviour, and every write is one atomic upsert.
 */
@Repository
@RequiredArgsConstructor
public class NotificationEmailPreferenceRepository {

    private final JdbcTemplate jdbcTemplate;

    /** @return category name → emailEnabled, for the categories this user has an explicit row for */
    public Map<String, Boolean> findByUserId(final long userId) {
        final Map<String, Boolean> result = new HashMap<>();
        jdbcTemplate.query(
            "SELECT category, email_enabled FROM main.notification_email_preferences WHERE user_id = ?",
            rs -> {
                result.put(rs.getString("category"), rs.getBoolean("email_enabled"));
            },
            userId);
        return result;
    }

    public Optional<Boolean> findEmailEnabled(final long userId, final String category) {
        return jdbcTemplate.query(
                "SELECT email_enabled FROM main.notification_email_preferences WHERE user_id = ? AND category = ?",
                (rs, rowNum) -> rs.getBoolean(1), userId, category)
            .stream().findFirst();
    }

    /**
     * Atomic upsert — two concurrent writers (a settings toggle and a one-click unsubscribe) cannot
     * lose an update or collide on the primary key. The {@code EXISTS} guard turns an unknown user id
     * into a 0-row no-op instead of an FK violation, and also refuses a deactivated account: GDPR
     * erasure scrubs the user row (activated = false) rather than deleting it, and a never-expiring
     * unsubscribe token must not re-create preference rows for an erased user.
     *
     * @return 1 if the preference was written, 0 if the user does not exist or is not activated
     */
    public int upsert(final long userId, final String category, final boolean emailEnabled) {
        return jdbcTemplate.update(
            "INSERT INTO main.notification_email_preferences (user_id, category, email_enabled, updated_at) "
                + "SELECT ?, ?, ?, now() WHERE EXISTS (SELECT 1 FROM main.\"user\" WHERE id = ? AND activated) "
                + "ON CONFLICT (user_id, category) DO UPDATE "
                + "SET email_enabled = EXCLUDED.email_enabled, updated_at = EXCLUDED.updated_at",
            userId, category, emailEnabled, userId);
    }

    public int deleteAllForUser(final long userId) {
        return jdbcTemplate.update("DELETE FROM main.notification_email_preferences WHERE user_id = ?", userId);
    }
}
