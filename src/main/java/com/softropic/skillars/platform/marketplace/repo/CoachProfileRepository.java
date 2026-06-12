package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CoachProfileRepository extends JpaRepository<CoachProfile, UUID> {
    Optional<CoachProfile> findByUserId(Long userId);
    boolean existsByUserId(Long userId);
}
