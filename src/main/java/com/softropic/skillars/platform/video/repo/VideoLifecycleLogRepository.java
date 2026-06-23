package com.softropic.skillars.platform.video.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VideoLifecycleLogRepository extends JpaRepository<VideoLifecycleLog, UUID> {}
