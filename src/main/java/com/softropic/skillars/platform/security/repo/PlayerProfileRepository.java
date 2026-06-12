package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, Long> {

    List<PlayerProfile> findByParentId(Long parentId);

    /** Always use this instead of findById — parentId enforces family isolation. */
    Optional<PlayerProfile> findByIdAndParentId(Long id, Long parentId);
}
