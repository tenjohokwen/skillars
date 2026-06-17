package com.softropic.skillars.platform.booking.contract;

import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class BatchBookingAcceptedEvent extends ApplicationEvent {

    private final UUID batchId;
    private final List<UUID> acceptedBookingIds;
    private final Long parentId;
    private final UUID coachId;
    private final BigDecimal totalAmount;
    private final String coachEmail;
    private final String parentEmail;
    private final String coachDisplayName;
    private final String parentName;
    private final int acceptedCount;

    public BatchBookingAcceptedEvent(Object source, UUID batchId, List<UUID> acceptedBookingIds,
                                     Long parentId, UUID coachId, BigDecimal totalAmount,
                                     String coachEmail, String parentEmail,
                                     String coachDisplayName, String parentName, int acceptedCount) {
        super(source);
        this.batchId = batchId;
        this.acceptedBookingIds = acceptedBookingIds;
        this.parentId = parentId;
        this.coachId = coachId;
        this.totalAmount = totalAmount;
        this.coachEmail = coachEmail;
        this.parentEmail = parentEmail;
        this.coachDisplayName = coachDisplayName;
        this.parentName = parentName;
        this.acceptedCount = acceptedCount;
    }

    public UUID getBatchId() { return batchId; }
    public List<UUID> getAcceptedBookingIds() { return acceptedBookingIds; }
    public Long getParentId() { return parentId; }
    public UUID getCoachId() { return coachId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCoachEmail() { return coachEmail; }
    public String getParentEmail() { return parentEmail; }
    public String getCoachDisplayName() { return coachDisplayName; }
    public String getParentName() { return parentName; }
    public int getAcceptedCount() { return acceptedCount; }
}
