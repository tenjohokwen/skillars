package com.softropic.skillars.platform.notification.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Resolves an email recipient address to the platform user who owns it. Email producers address
 * mail by address only ({@code Recipient} has no user id), and the address on every user-facing
 * email is read from that user's own {@code main."user".email} — so the owner of the address is the
 * recipient, which for a minor is the parent, exactly as for the email itself.
 *
 * <p>Exact match on purpose: {@code user_email_key} is a UNIQUE index on {@code email}, so this is an
 * index lookup returning at most one row. A case-insensitive match could return several rows (two
 * addresses differing only by case are distinct under that constraint) and has no index.
 */
@Repository
@RequiredArgsConstructor
public class NotificationRecipientLookup {

    private final JdbcTemplate jdbcTemplate;

    /** @param userId the main."user" id; {@code coach} whether the user holds ROLE_COACH */
    public record ResolvedRecipient(long userId, boolean coach) {
    }

    /**
     * @param resolveCoachRole whether to also compute {@link ResolvedRecipient#coach()} (an extra
     *     authority join) — callers that do not branch on the role pass {@code false} and always get
     *     {@code coach = false}
     */
    public Optional<ResolvedRecipient> findByEmail(final String email, final boolean resolveCoachRole) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        final String coachColumn = resolveCoachRole
            ? "EXISTS (SELECT 1 FROM main.user_authority ua JOIN main.authority a ON a.id = ua.authority_id "
                + "WHERE ua.user_id = u.id AND a.name = 'ROLE_COACH')"
            : "false";
        return jdbcTemplate.query(
                "SELECT u.id, " + coachColumn + " AS coach FROM main.\"user\" u WHERE u.email = ?",
                (rs, rowNum) -> new ResolvedRecipient(rs.getLong("id"), rs.getBoolean("coach")),
                email)
            .stream().findFirst();
    }
}
