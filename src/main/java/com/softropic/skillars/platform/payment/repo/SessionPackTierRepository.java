package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPackTierRepository extends JpaRepository<SessionPackTier, UUID> {

    Optional<SessionPackTier> findByCoachIdAndIsActiveTrue(UUID coachId);

    List<SessionPackTier> findAllByCoachIdAndIsActiveTrue(UUID coachId);

    List<SessionPackTier> findAllByCoachId(UUID coachId);
}
