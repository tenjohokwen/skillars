package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DrillVideoRefRepository extends JpaRepository<DrillVideoRef, UUID> {

    Optional<DrillVideoRef> findByDrillId(UUID drillId);

    List<DrillVideoRef> findByDrillIdIn(Collection<UUID> drillIds);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE DrillVideoRef d SET d.refCount = d.refCount + 1 WHERE d.drillId = :drillId")
    void incrementRefCount(@Param("drillId") UUID drillId);
}
