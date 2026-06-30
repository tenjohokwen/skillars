package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParentCreditLedgerRepository extends JpaRepository<ParentCreditLedger, UUID> {

    @Query("SELECT SUM(l.amount) FROM ParentCreditLedger l WHERE l.parentId = :parentId")
    Optional<BigDecimal> sumByParentId(@Param("parentId") Long parentId);

    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'BOOKING_REFUND' AND l.referenceId IN :bookingIds")
    BigDecimal sumRefundsByBookingIds(@Param("bookingIds") List<UUID> bookingIds);

    @Query("SELECT l FROM ParentCreditLedger l WHERE l.parentId = :parentId AND l.createdAt BETWEEN :from AND :to ORDER BY l.createdAt DESC")
    Page<ParentCreditLedger> findByParentAndPeriod(@Param("parentId") Long parentId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to,
                                                   Pageable pageable);

    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ParentCreditLedger l WHERE l.parentId = :parentId AND l.createdAt < :before")
    BigDecimal sumByParentIdAndCreatedAtBefore(@Param("parentId") Long parentId,
                                               @Param("before") Instant before);

    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'BOOKING_REFUND' AND l.createdAt BETWEEN :from AND :to")
    BigDecimal sumTotalRefundCredit(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COALESCE(SUM(ABS(l.amount)), 0) FROM ParentCreditLedger l WHERE l.type = 'CASH_OUT_DEBIT' AND l.createdAt BETWEEN :from AND :to")
    BigDecimal sumTotalCashOuts(@Param("from") Instant from, @Param("to") Instant to);

    List<ParentCreditLedger> findAllByParentId(Long parentId);
}
