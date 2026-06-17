package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DrillTagRepository extends JpaRepository<DrillTag, DrillTagId> {

    List<DrillTag> findByIdDrillIdInAndIdCoachId(Collection<UUID> drillIds, UUID coachId);

    @Query("SELECT DISTINCT dt.id.tag FROM DrillTag dt WHERE dt.id.coachId = :coachId ORDER BY dt.id.tag")
    List<String> findDistinctTagsByCoachId(@Param("coachId") UUID coachId);

    void deleteByIdDrillIdAndIdTagAndIdCoachId(UUID drillId, String tag, UUID coachId);
}
