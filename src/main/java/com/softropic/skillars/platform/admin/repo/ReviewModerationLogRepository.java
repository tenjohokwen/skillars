package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReviewModerationLogRepository extends JpaRepository<ReviewModerationLog, UUID> {}
