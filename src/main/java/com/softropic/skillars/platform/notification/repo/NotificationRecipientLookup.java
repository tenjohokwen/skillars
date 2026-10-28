package com.softropic.skillars.platform.notification.repo;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Resolves an email recipient address to the platform user who owns it. Email producers address
 * mail by address only ({@code Recipient} has no user id), and the address on every user-facing
 * email is read from that user's own {@code main."user".email} — so the owner of the address is the
 * recipient, which for a minor is the parent, exactly as for the email itself.
 *
 * <p>Case-insensitive (B6), in two steps. Addresses are compared as mailbox owners see them: the
 * registration paths store {@code req.email()} as typed (only the legacy account path lower-cases), so a
 * producer holding a differently-cased copy of the address used to find no user — no inbox row, the
 * opt-out ignored at send time, no unsubscribe link. Step 1 is the exact match on the
 * {@code user_email_key} UNIQUE index (every producer that reads the address off the user row hits it).
 * Only on a miss, step 2 compares {@code lower(email)}; there is no index on {@code lower(email)} (no
 * online-safe way to build one in this Flyway runner yet — migration-conventions.md rule 4), so it is a
 * scan of {@code main."user"}, paid only for addresses that matched nothing exactly. Two accounts whose
 * addresses differ only by case are still distinct under the unique constraint; if the lower-cased
 * address matches more than one user the lookup is ambiguous and returns empty — the same answer as
 * "no account", never someone else's inbox.
  */
@Repository
@RequiredArgsConstructor
public class NotificationRecipientLookup {

    private static final RowMapper<ResolvedRecipient> ROW_MAPPER =
        (rs, rowNum) -> new ResolvedRecipient(rs.getLong("id"), rs.getBoolean("coach"));

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
        final String select = "SELECT u.id, " + coachColumn + " AS coach FROM main.\"user\" u WHERE ";
        final List<ResolvedRecipient> exact = jdbcTemplate.query(select + "u.email = ?", ROW_MAPPER, email);
        if (!exact.isEmpty()) {
            return Optional.of(exact.get(0));
        }
        final List<ResolvedRecipient> folded =
            jdbcTemplate.query(select + "lower(u.email) = lower(?) LIMIT 2", ROW_MAPPER, email);
        return folded.size() == 1 ? Optional.of(folded.get(0)) : Optional.empty();
    }
}
