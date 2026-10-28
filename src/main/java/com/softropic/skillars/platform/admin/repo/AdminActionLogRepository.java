package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AdminActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, UUID> {

    /**
     * Most recent admin actions of the given types against one reference (e.g. a user id for the
     * admin Users page's account history). The type filter matters: reference_id is not unique
     * across action families (a message id and a user id can share the same digits).
     */
    List<AdminActionLog> findTop50ByReferenceIdAndActionTypeInOrderByCreatedAtDesc(
        String referenceId, Collection<AdminActionType> actionTypes);

    /**
     * B6: whether an action of this type was logged against the reference at or after {@code since} —
     * used to tell a coach's current suspension apart as a child-safety hold (COACH_SAFETY_HOLD logged
     * with the same instant as the profile's statusChangedAt).
     */
    boolean existsByReferenceIdAndActionTypeAndCreatedAtGreaterThanEqual(
        String referenceId, AdminActionType actionType, java.time.Instant since);
}
