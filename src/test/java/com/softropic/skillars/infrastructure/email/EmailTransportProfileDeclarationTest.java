package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC3 — "every profile states its own value" is about explicitness, not about the
 * base file being unparseable (the base default in {@code application.yaml} makes the key
 * technically optional everywhere). This test asserts the intent doesn't silently erode: each
 * profile file below still declares {@code app.email.transport} for itself.
 */
class EmailTransportProfileDeclarationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "src/main/resources/application-dev.yaml",
        "src/main/resources/application-uat.yaml",
        "src/main/resources/application-prod.yaml",
        "src/test/resources/application-test.yaml"
    })
    void profileFileDeclaresTransportExplicitly(String path) throws IOException {
        String content = Files.readString(Path.of(path));
        assertThat(content)
            .as(path + " must declare app.email.transport explicitly (AC3/AC12)")
            // 'smtp' became a fully working transport in story ses-1.2 (AC6) — dev/uat now declare
            // it explicitly. EmailTransportPropertyValidator accepts all three values the same way.
            .containsPattern("(?m)^\\s*transport:\\s*(ses|smtp|log)\\s*$");
    }

    @Test
    void baseApplicationYaml_declaresLogAsTheDefault() throws IOException {
        String content = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(content).containsPattern("(?m)^\\s*transport:\\s*log\\s*$");
    }
}
