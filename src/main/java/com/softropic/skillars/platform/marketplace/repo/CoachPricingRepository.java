package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachPricingRepository extends JpaRepository<CoachPricing, UUID> {
    Optional<CoachPricing> findByCoachId(UUID coachId);
}
