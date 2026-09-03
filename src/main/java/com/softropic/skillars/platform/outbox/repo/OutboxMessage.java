package com.softropic.skillars.platform.outbox.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * skillars-deferred-91 AC1: one durable outbox message. Written inside a business transaction and
 * drained off the request path by {@code OutboxService} after that transaction commits. Modelled on
 * skillars-deferred-90's {@code PendingBlobDeletion}, with a discriminator ({@link #aggregateType})
 * + JSON payload so more than one domain can share the table.
 */
@Entity
@Table(schema = "main", name = "outbox_messages")
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Selects the {@code OutboxMessageHandler} that re-drives this message. */
    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    /** Handler-defined JSON describing the operation to re-drive. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Earliest time this row may be claimed again (skillars-deferred-91 review D6, {@code V126}).
     * Stamped by {@code OutboxRowProcessor.recordFailure} with an exponential backoff off
     * {@link #attempts}; defaults to now so a freshly enqueued row is due immediately.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    public OutboxMessage(String aggregateType, String payload) {
        this.aggregateType = aggregateType;
        this.payload = payload;
    }
}
