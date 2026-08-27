package com.softropic.skillars.platform.development.config;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevelopmentConfigTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    @Test
    void validateSluRepositorySchema_columnsExist_doesNotThrow() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.List.of());

        DevelopmentConfig config = new DevelopmentConfig(entityManager);

        assertThatCode(config::validateSluRepositorySchema).doesNotThrowAnyException();
    }

    @Test
    void validateSluRepositorySchema_columnMismatch_throwsAppSetupException() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenThrow(new RuntimeException("column \"slu_value\" does not exist"));

        DevelopmentConfig config = new DevelopmentConfig(entityManager);

        assertThatThrownBy(config::validateSluRepositorySchema)
            .isInstanceOf(AppSetupException.class)
            .hasMessageContaining("SluRepository schema mismatch");
    }
}
