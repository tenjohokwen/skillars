package com.softropic.skillars.platform.messaging.repo;

import com.softropic.skillars.infrastructure.persistence.BaseEntity;
import com.softropic.skillars.platform.messaging.contract.ConversationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "conversations", schema = "messaging")
// skillars-deferred-173 (RS10): no @Version, and readers, senders, reporters and the admin unblock all load and
// save this row unlocked. A static full-row UPDATE wrote back whatever that transaction had loaded, so a
// getMessages that loaded ACTIVE and committed after a report undid the BLOCKED. With @DynamicUpdate a save
// writes only the columns that transaction changed (same fix as User).
@DynamicUpdate
public class Conversation extends BaseEntity {

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    /** Nullable since V95 (UAT.5 AC3): a self-registered adult PLAYER has no real parent. */
    @Column(name = "parent_id")
    private Long parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationStatus status = ConversationStatus.ACTIVE;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "coach_last_read_at")
    private Instant coachLastReadAt;

    @Column(name = "parent_last_read_at")
    private Instant parentLastReadAt;

    @Column(name = "player_last_read_at")
    private Instant playerLastReadAt;
}
