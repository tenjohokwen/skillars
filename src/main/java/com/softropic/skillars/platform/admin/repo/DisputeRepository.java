package com.softropic.skillars.platform.admin.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    @Query("""
        SELECT d FROM Dispute d
        WHERE d.bookingId = :bookingId
          AND d.status NOT IN ('RESOLVED', 'DISMISSED')
        """)
    Optional<Dispute> findOpenByBookingId(@Param("bookingId") UUID bookingId);

    List<Dispute> findByRaisedBy(Long raisedBy);
}
