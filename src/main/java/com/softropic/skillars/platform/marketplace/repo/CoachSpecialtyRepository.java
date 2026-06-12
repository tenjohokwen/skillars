package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachSpecialtyRepository extends JpaRepository<CoachSpecialty, UUID> {
    List<CoachSpecialty> findByCoachId(UUID coachId);
    void deleteByCoachId(UUID coachId);
}
