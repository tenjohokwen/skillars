package com.softropic.skillars.platform.admin.repo;

import com.softropic.skillars.platform.admin.contract.AdminAlertStatus;
import com.softropic.skillars.platform.admin.contract.AdminAlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminAlertRepository extends JpaRepository<AdminAlert, UUID> {

    @Query("""
        SELECT a FROM AdminAlert a
        WHERE (:type IS NULL OR a.type = :type)
          AND a.status = :status
        ORDER BY a.createdAt ASC
        """)
    Page<AdminAlert> findByTypeAndStatus(
        @Param("type") @Nullable AdminAlertType type,
        @Param("status") AdminAlertStatus status,
        Pageable pageable);

    @Query("SELECT a.type, COUNT(a) FROM AdminAlert a WHERE a.status = 'OPEN' GROUP BY a.type")
    List<Object[]> countOpenByType();

    Optional<AdminAlert> findFirstByReferenceIdAndTypeAndStatus(
        String referenceId, AdminAlertType type, AdminAlertStatus status);
}
