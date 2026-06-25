package com.softropic.skillars.platform.payment.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ParentCreditLedgerRepository extends JpaRepository<ParentCreditLedger, UUID> {

    @Query("SELECT SUM(l.amount) FROM ParentCreditLedger l WHERE l.parentId = :parentId")
    Optional<BigDecimal> sumByParentId(@Param("parentId") Long parentId);
}
