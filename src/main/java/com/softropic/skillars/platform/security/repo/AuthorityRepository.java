package com.softropic.skillars.platform.security.repo;

import com.softropic.skillars.platform.security.repo.Authority;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA persistence for the Authority entity.
 */
public interface AuthorityRepository extends JpaRepository<Authority, UUID> {

    Optional<Authority> findOneByName(String name);
}
