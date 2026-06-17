package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DrillRepository extends JpaRepository<Drill, UUID> {

    List<Drill> findByLibraryTypeAndStatus(String libraryType, String status);

    List<Drill> findByOwnerCoachIdAndStatus(UUID ownerCoachId, String status);
}
