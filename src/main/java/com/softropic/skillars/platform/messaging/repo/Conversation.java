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

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "conversations", schema = "messaging")
public class Conversation extends BaseEntity {

    @Column(name = "coach_id", nullable = false)
    private UUID coachId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "parent_id", nullable = false)
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
