package com.softropic.skillars.platform.session.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByBookingId(UUID bookingId);

    Optional<Session> findByBookingIdAndCoachId(UUID bookingId, UUID coachId);

    boolean existsByBookingId(UUID bookingId);
}
