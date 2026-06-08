package com.softropic.skillars.platform.tenant.repo;

import com.softropic.skillars.platform.tenant.contract.TenantStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTenantRef(String tenantRef);

    boolean existsByNameIgnoreCase(String name);

    Optional<Tenant> findByTenantRefAndTenantStatus(String tenantRef, TenantStatus status);

    Page<Tenant> findByTenantStatus(TenantStatus tenantStatus, Pageable pageable);
}
