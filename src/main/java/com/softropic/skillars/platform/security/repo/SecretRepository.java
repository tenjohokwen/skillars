package com.softropic.skillars.platform.security.repo;



import com.softropic.skillars.infrastructure.persistence.EntityStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA persistence for the SecKey entity.
 */
public interface SecretRepository extends JpaRepository<Secret, Long> {

    Optional<Secret> findOneByVersionAndBusId(final String version, final String busId);

    Optional<Secret> findTopByBusIdAndStatusOrderByCreatedDateDesc(String busId, EntityStatus status);
}
