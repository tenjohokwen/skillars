package com.softropic.skillars.infrastructure.email.smtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 AC5 — asserts every shipped {@code application*.yaml} and the test profile carries
 * no {@code spring.mail:} block, and that {@code provider-configs} (or its pre-rename camelCase
 * spelling {@code providerConfigs}) never appears anywhere except its one correct location,
 * {@code app.email.smtp.provider-configs}.
 *
 * <p><strong>Code review 2026-09-11 rewrite.</strong> The original version did a text-only
 * {@code doesNotContain("providerConfigs")} check, which is satisfied by construction once every
 * shipped file used the new kebab-case spelling — it could not tell "correctly placed under
 * {@code app.email.smtp}" apart from "kebab-case {@code provider-configs} resurrected at the wrong
 * nesting level", which is exactly the regression this test exists to catch. This version parses
 * each file as real YAML (SnakeYAML — already on the classpath via Spring Boot's own YAML property
 * source support, no new dependency) and walks the resulting structure, so a {@code provider-configs}
 * key anywhere other than {@code app.email.smtp.provider-configs} — top level, under a different
 * parent, in either spelling — fails the assertion by path, not by a substring that happens not to
 * appear today.
 *
 * <p>The file list is also no longer hand-picked: every {@code application*.yaml} under {@code
 * src/main/resources} is discovered by glob, so a new profile added later is covered automatically,
 * plus the one pinned test-profile file.
 *
 * <p>The {@code spring.mail:} check is scoped to that one structural location (the {@code mail} key
 * directly under {@code spring}), not a blanket "no key literally named {@code mail:}" text scan —
 * deliberately, so it cannot false-positive on an unrelated key that happens to share the name at a
 * different nesting level.
 */
@DisplayName("No shipped YAML resurrects spring.mail or misplaces provider-configs")
class NoStraySmtpConfigTest {

    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");
    private static final Path TEST_PROFILE = Path.of("src/test/resources/application-test.yaml");

    /** Every {@code application*.yaml} shipped under {@code src/main/resources}, plus the pinned test profile. */
    private static List<Path> shippedYaml() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MAIN_RESOURCES, "application*.yaml")) {
            stream.forEach(files::add);
        }
        files.add(TEST_PROFILE);
        assertThat(files)
            .as("expected to discover the known application.yaml/-dev/-uat/-prod profiles plus the test profile")
            .hasSizeGreaterThanOrEqualTo(5);
        return files;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path yaml) throws IOException {
        try (InputStream in = Files.newInputStream(yaml)) {
            Object loaded = new Yaml().load(in);
            return loaded == null ? Map.of() : (Map<String, Object>) loaded;
        }
    }

    @Test
    @DisplayName("no file carries a spring.mail: block")
    void noSpringMailBlock() throws IOException {
        for (Path yaml : shippedYaml()) {
            Object spring = parse(yaml).get("spring");
            if (spring instanceof Map<?, ?> springMap) {
                assertThat(springMap.containsKey("mail"))
                    .as("%s must not carry spring.mail: — SMTP config lives under app.email.smtp "
                        + "(story ses-1.2 D9)", yaml)
                    .isFalse();
            }
        }
    }

    @Test
    @DisplayName("provider-configs (either spelling) appears only at app.email.smtp.provider-configs")
    void providerConfigsOnlyAppearsAtItsOneCorrectLocation() throws IOException {
        for (Path yaml : shippedYaml()) {
            List<String> offendingPaths = new ArrayList<>();
            collectMisplacedProviderConfigs(parse(yaml), List.of(), offendingPaths);
            assertThat(offendingPaths)
                .as("%s: 'provider-configs'/'providerConfigs' must appear only at "
                    + "app.email.smtp.provider-configs (story ses-1.2 AC2/AC6), found at: %s",
                    yaml, offendingPaths)
                .isEmpty();
        }
    }

    private static final List<String> CORRECT_PATH = List.of("app", "email", "smtp", "provider-configs");

    @SuppressWarnings("unchecked")
    private static void collectMisplacedProviderConfigs(Object node, List<String> path, List<String> offenders) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                List<String> childPath = append(path, key);
                if ((key.equals("provider-configs") || key.equalsIgnoreCase("providerConfigs"))
                        && !childPath.equals(CORRECT_PATH)) {
                    offenders.add(String.join(".", childPath));
                }
                collectMisplacedProviderConfigs(entry.getValue(), childPath, offenders);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectMisplacedProviderConfigs(item, path, offenders);
            }
        }
    }

    private static List<String> append(List<String> path, String key) {
        List<String> copy = new ArrayList<>(path);
        copy.add(key);
        return copy;
    }
}
