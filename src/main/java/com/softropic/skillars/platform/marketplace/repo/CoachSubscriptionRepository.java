package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachSubscriptionRepository extends JpaRepository<CoachSubscription, UUID> {
    Optional<CoachSubscription> findByCoachId(UUID coachId);
}
