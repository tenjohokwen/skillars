package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CoachCancellationHistoryRepository extends JpaRepository<CoachCancellationHistory, UUID> {
}
