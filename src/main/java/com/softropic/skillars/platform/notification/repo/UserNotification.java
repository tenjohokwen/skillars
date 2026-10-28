package com.softropic.skillars.platform.notification.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One in-app inbox row (notification centre, UI-gap chunk C9). Read-only through JPA: rows are
 * inserted and marked read exclusively by the native statements on
 * {@link UserNotificationRepository}, each of which carries its own recipient predicate, so no code
 * path loads-checks-writes a notification.
 */
@Entity
@Immutable
@Table(schema = "main", name = "user_notifications")
@Getter
@NoArgsConstructor
public class UserNotification {

    @Id
    private UUID id;

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> params;

    @Column(name = "deep_link")
    private String deepLink;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;
}
