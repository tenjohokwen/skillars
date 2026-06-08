package com.softropic.skillars.infrastructure.persistence;

/**
 * Entity lifecycle status enumeration.
 * Represents the lifecycle state of persistent entities.
 */
public enum EntityStatus {
    ACTIVE, INACTIVE, DELETED;

    public boolean isOneOf(final EntityStatus... statuses) {
       return isContainedIn(statuses);
    }

    public boolean isNoneOf(final EntityStatus... statuses) {
        return !isContainedIn(statuses);
    }

    private boolean isContainedIn(final EntityStatus... statuses) {
        for (final EntityStatus status : statuses) {
            if(this == status) {
                return true;
            }
        }
        return false;
    }
}
