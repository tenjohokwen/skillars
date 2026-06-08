package com.softropic.skillars.platform.tenant.repo;

import com.softropic.skillars.platform.tenant.contract.ApiKeyEnvironment;
import com.softropic.skillars.platform.tenant.contract.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


public interface TenantApiKeyRepository extends JpaRepository<TenantApiKey, Long> {

    @Query("""
        SELECT k FROM TenantApiKey k JOIN FETCH k.tenant
        WHERE k.keyHash = :keyHash
          AND (k.keyStatus = 'ACTIVE'
               OR (k.keyStatus = 'ROTATED'
                   AND k.rotatedAt > :graceDeadline))
        """)
    Optional<TenantApiKey> findValidKeyByHash(
        @Param("keyHash") String keyHash,
        @Param("graceDeadline") Instant graceDeadline
    );

    List<TenantApiKey> findAllByTenantId(Long tenantId);

    @Transactional
    @Modifying
    @Query("""
        UPDATE TenantApiKey k
        SET k.keyStatus = com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.REVOKED
        WHERE k.tenant.id = :tenantId
          AND k.keyStatus IN (
              com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.ACTIVE,
              com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.ROTATED
          )
        """)
    int revokeAllActiveAndRotatedByTenantId(@Param("tenantId") Long tenantId);

    @Query("""
        SELECT k FROM TenantApiKey k
        WHERE k.tenant.id = :tenantId
          AND k.environment = :environment
          AND k.keyStatus = com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.ROTATED
        """)
    Optional<TenantApiKey> findRotatedKeyByTenantIdAndEnvironment(
        @Param("tenantId") Long tenantId,
        @Param("environment") ApiKeyEnvironment environment
    );

    @Query("""
        SELECT k FROM TenantApiKey k
        WHERE k.tenant.id = :tenantId
          AND k.environment = :environment
          AND k.keyStatus = com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.ACTIVE
        """)
    Optional<TenantApiKey> findActiveKeyByTenantIdAndEnvironment(
        @Param("tenantId") Long tenantId,
        @Param("environment") ApiKeyEnvironment environment
    );

    @Query("""
        SELECT k FROM TenantApiKey k
        WHERE k.keyStatus = com.softropic.skillars.platform.tenant.contract.ApiKeyStatus.ROTATED
          AND k.rotatedAt < :cutoff
        """)
    List<TenantApiKey> findExpiredRotatedKeys(@Param("cutoff") Instant cutoff);
}
