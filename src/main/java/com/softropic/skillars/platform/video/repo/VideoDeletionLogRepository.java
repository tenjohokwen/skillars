package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VideoDeletionLogRepository extends JpaRepository<VideoDeletionLog, UUID> {
}
