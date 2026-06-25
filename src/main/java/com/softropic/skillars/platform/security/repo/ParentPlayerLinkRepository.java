package com.softropic.skillars.platform.security.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParentPlayerLinkRepository extends JpaRepository<ParentPlayerLink, Long> {

    boolean existsByPlayerId(Long playerId);

    boolean existsByParentIdAndPlayerId(Long parentId, Long playerId);

    Optional<ParentPlayerLink> findByPlayerId(Long playerId);

    List<ParentPlayerLink> findByParentId(Long parentId);

    List<ParentPlayerLink> findAllByPlayerId(Long playerId);
}
