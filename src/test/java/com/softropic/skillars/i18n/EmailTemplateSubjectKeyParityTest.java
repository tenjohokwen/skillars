package com.softropic.skillars.i18n;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-93 AC7 — ties the {@link EmailTemplate} enum to the i18n bundles, catching
 * enum↔bundle drift that {@link MessageBundleParityTest} cannot see: it only compares the bundles
 * to <em>each other</em>, so a subject key renamed in the enum but not the bundles, or added to
 * three bundles and not the default, slips past it and surfaces as a runtime
 * {@code NoSuchMessageException} on the affected locale.
 *
 * <p>No live bug today — every non-{@code NONE} {@code subjectKey()} is present in all four bundles.
 * The value is drift detection.
 *
 * <p>{@code *Test}, no Spring context — reads the {@code .properties} files straight off disk with
 * the real {@link Properties} parser, exactly as {@code MessageBundleParityTest} does, so it runs
 * in the {@code test} phase with no database or application context.
 */
@DisplayName("EmailTemplate subject keys are present in every message bundle")
class EmailTemplateSubjectKeyParityTest {

    private static final Path I18N = Path.of("src", "main", "resources", "i18n");

    private static final List<String> BUNDLES = List.of(
        "messages.properties",       // default fallback bundle
        "messages_en.properties",
        "messages_de.properties",
        "messages_fr.properties");

    @Test
    void everyNonNoneSubjectKey_isPresentInEveryBundle() throws IOException {
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String bundle : BUNDLES) {
            loaded.put(bundle, load(I18N.resolve(bundle)));
        }

        List<String> missing = Arrays.stream(EmailTemplate.values())
            .filter(t -> t != EmailTemplate.NONE)
            .peek(t -> assertThat(t.subjectKey())
                .as("template %s must declare a non-blank subjectKey", t)
                .isNotBlank())
            .flatMap(t -> BUNDLES.stream()
                .filter(bundle -> !loaded.get(bundle).containsKey(t.subjectKey()))
                .map(bundle -> t.name() + " -> " + t.subjectKey() + " absent from " + bundle))
            .sorted()
            .toList();

        assertThat(missing)
            .as("every EmailTemplate.subjectKey() must resolve in all four bundles — "
                + "add the key, or fix the enum constant if it was renamed")
            .isEmpty();
    }

    @Test
    void noneTemplate_isSpecialCasedWithAnEmptySubjectKey() {
        assertThat(EmailTemplate.NONE.subjectKey()).isEmpty();
    }

    private static Map<String, String> load(Path p) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            out.put(key, properties.getProperty(key));
        }
        return out;
    }
}
