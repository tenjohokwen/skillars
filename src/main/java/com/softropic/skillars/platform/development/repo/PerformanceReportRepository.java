package com.softropic.skillars.platform.development.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PerformanceReportRepository extends JpaRepository<PerformanceReport, UUID> {
    List<PerformanceReport> findByPlayerIdOrderByGeneratedAtDesc(Long playerId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PerformanceReport p WHERE p.playerId = :playerId")
    int deleteAllByPlayerId(@org.springframework.data.repository.query.Param("playerId") Long playerId);
}
