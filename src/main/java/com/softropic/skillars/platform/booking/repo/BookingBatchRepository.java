package com.softropic.skillars.platform.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingBatchRepository extends JpaRepository<BookingBatch, UUID> {

    List<BookingBatch> findByCoachIdAndStatus(UUID coachId, String status);
}
