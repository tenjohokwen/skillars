package com.softropic.skillars.platform.development.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class DevelopmentConfig {

    private final EntityManager entityManager;

    public DevelopmentConfig(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Guards against a silent schema/column drift between SluRepository's native queries and the
    // development.player_skill_stats table (see V46__development_module_init.sql). A mismatch would
    // otherwise surface as a runtime BadSqlGrammarException on the first SLU read, not at startup.
    @PostConstruct
    void validateSluRepositorySchema() {
        try {
            entityManager.createNativeQuery(
                "SELECT slu_value, calculated_at FROM development.player_skill_stats LIMIT 0")
                .getResultList();
            log.info("SluRepository schema validation passed");
        } catch (RuntimeException e) {
            log.error("SluRepository schema validation failed — column names may not match migration", e);
            throw new AppSetupException("SluRepository schema mismatch: " + e.getMessage());
        }
    }
}
