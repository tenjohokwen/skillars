package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoachCancellationHistoryRepository extends JpaRepository<CoachCancellationHistory, UUID> {

    List<CoachCancellationHistory> findByCoachIdOrderByCreatedAtDesc(UUID coachId);
}
