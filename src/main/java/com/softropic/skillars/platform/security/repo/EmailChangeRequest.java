package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

/**
 * One requested change of an account's sign-in email (skillars-deferred-172, V232).
 *
 * <p>Lifecycle: open (link sent to {@code newEmail}) → confirmed (account switched, undo link sent to
 * {@code oldEmail}, which stays reserved until {@code undoExpiresAt}) → optionally undone. An open request
 * ends as superseded when the user asks again, changes or resets their password, or the confirm checks fail.
 * Only SHA-256 hashes of the tokens are stored. {@code @Version} settles a double click: the losing confirm
 * or undo fails and is answered as an invalid link.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_change_requests")
public class EmailChangeRequest extends BaseEntity {

    @Version
    private Long version;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "old_email", nullable = false, updatable = false)
    private String oldEmail;

    @Column(name = "old_login", nullable = false, updatable = false)
    private String oldLogin;

    @Column(name = "new_email", nullable = false, updatable = false)
    private String newEmail;

    @Column(name = "confirm_token_hash", nullable = false, unique = true, updatable = false)
    private String confirmTokenHash;

    @Column(name = "confirm_expires_at", nullable = false, updatable = false)
    private Instant confirmExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "undo_token_hash", unique = true)
    private String undoTokenHash;

    @Column(name = "undo_expires_at")
    private Instant undoExpiresAt;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    public boolean isConfirmable(final Instant now) {
        return confirmedAt == null && supersededAt == null && now.isBefore(confirmExpiresAt);
    }

    public boolean isUndoable(final Instant now) {
        return confirmedAt != null && undoneAt == null && undoExpiresAt != null && now.isBefore(undoExpiresAt);
    }
}
