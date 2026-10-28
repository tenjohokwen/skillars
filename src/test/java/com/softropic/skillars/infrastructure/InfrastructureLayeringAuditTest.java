package com.softropic.skillars.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Infrastructure must not depend on platform (project-context.md, "Dependency Flow"). A handful of
 * pre-existing inversions remain and are listed below so they stay visible; this test fails if a
 * NEW infrastructure class imports, or references by fully-qualified name, anything in
 * {@code com.softropic.skillars.platform}.
 *
 * <p>Platform cleanup (B8) removed {@code RateLimitingService}'s import of
 * {@code platform.config.service.ConfigService} by introducing the
 * {@link com.softropic.skillars.infrastructure.security.RateLimitBucketTtlSource} port. Remove an
 * entry from {@link #KNOWN_INVERSIONS} when its import is fixed — the test also fails on a stale
 * entry, so the list cannot silently outlive the code.
 */
class InfrastructureLayeringAuditTest {

    private static final Path INFRASTRUCTURE_SRC =
        Path.of("src", "main", "java", "com", "softropic", "skillars", "infrastructure");

    /** An import, or a fully-qualified reference anywhere in code (comments are stripped first). */
    private static final Pattern PLATFORM_REFERENCE =
        Pattern.compile("\\bcom\\.softropic\\.skillars\\.platform\\.");

    /** Pre-existing inversions, by file name relative to the infrastructure package root. */
    private static final Set<String> KNOWN_INVERSIONS = Set.of(
        "gemini/GeminiClient.java",
        "gemini/GeminiClientImpl.java",
        "security/RequestMetadataProvider.java",
        "video/BunnyVideoProviderAdapter.java");

    @Test
    void noNewInfrastructureClassImportsPlatform() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(INFRASTRUCTURE_SRC)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(InfrastructureLayeringAuditTest::importsPlatform)
                .map(p -> INFRASTRUCTURE_SRC.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }

        assertThat(offenders)
            .as("infrastructure.* classes importing com.softropic.skillars.platform.* — depend on a "
                + "port declared in infrastructure and implement it in platform instead")
            .isSubsetOf(KNOWN_INVERSIONS);
        assertThat(KNOWN_INVERSIONS)
            .as("a KNOWN_INVERSIONS entry no longer imports platform — remove it from the list")
            .isSubsetOf(offenders);
    }

    private static boolean importsPlatform(Path file) {
        try {
            String code = Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ");
            return PLATFORM_REFERENCE.matcher(code).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
