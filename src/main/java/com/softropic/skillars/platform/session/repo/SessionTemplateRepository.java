package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionTemplateRepository extends JpaRepository<SessionTemplate, UUID> {

    List<SessionTemplate> findByCoachIdAndStatus(UUID coachId, String status);

    Optional<SessionTemplate> findByIdAndCoachId(UUID id, UUID coachId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionTemplate t SET t.deployCount = t.deployCount + 1, t.lastDeployedAt = :now WHERE t.id = :id")
    void incrementDeployCount(@Param("id") UUID id, @Param("now") Instant now);
}
