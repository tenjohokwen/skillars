package com.softropic.skillars.platform.marketplace.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CoachMediaItemRepository extends JpaRepository<CoachMediaItem, UUID> {
    List<CoachMediaItem> findByCoachIdOrderByDisplayOrderAsc(UUID coachId);
}
