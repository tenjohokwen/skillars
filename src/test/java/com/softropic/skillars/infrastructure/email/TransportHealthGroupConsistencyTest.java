package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.5 AC2 — each shipped profile's {@code app.email.transport} and its
 * {@code management.endpoint.health.group.notification.include} must agree.
 *
 * <p>Why a test that reads the profile files instead of booting a context: {@code
 * AbstractIntegrationTest} runs {@code @ActiveProfiles({"dev","test"})} and {@code
 * application-test.yaml} pins {@code transport: log} and overrides the group, so no integration test
 * can ever see a dev/uat transport↔group mismatch. Such a mismatch aborts boot in the real
 * environment ({@code HealthEndpointGroupMembershipValidator}: the included indicator bean exists
 * only for the matching transport — see {@code application.yaml}'s notification-group comment and
 * {@code TransportWiringTest}, which pins the validator behaviour with synthetic properties, not
 * with these files).
 *
 * <p>Rules ({@link #violation}): {@code log} ⇒ no non-empty include (a blank include means "every
 * contributor" in Spring Boot, not "no group", so absence is the only correct shape); {@code ses}
 * ⇒ includes {@code ses}, not {@code smtp}; {@code smtp} ⇒ includes {@code smtp}, not {@code ses}.
 * The rule itself is unit-tested below with synthetic inputs so each branch is demonstrably red
 * under its own mutation, independent of what the shipped files happen to say today.
 */
class TransportHealthGroupConsistencyTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Pattern PROFILE_FILE = Pattern.compile("application-(.+)\\.yaml");

    /** Every shipped profile file, so a newly added profile cannot silently escape this test. */
    static Stream<String> shippedProfiles() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            List<String> profiles = files
                .map(p -> PROFILE_FILE.matcher(p.getFileName().toString()))
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .sorted()
                .toList();
            assertThat(profiles).as("profile files under %s", RESOURCES).contains("dev", "uat", "prod");
            return profiles.stream();
        }
    }

    @ParameterizedTest
    @MethodSource("shippedProfiles")
    void shippedProfile_transportAndNotificationGroupAgree(String profile) throws IOException {
        // Spring overlays the profile file on the base application.yaml, so a value the profile leaves
        // unset is inherited from the base — resolve both keys the same way.
        Map<String, Object> base = parse(RESOURCES.resolve("application.yaml"));
        Map<String, Object> yaml = parse(RESOURCES.resolve("application-" + profile + ".yaml"));

        String transport = resolved(yaml, base, "app", "email", "transport");
        String include = resolved(yaml, base, "management", "endpoint", "health", "group", "notification", "include");

        assertThat(string(yaml, "app", "email", "transport"))
            .as("application-%s.yaml must declare app.email.transport explicitly", profile)
            .isNotNull();
        assertThat(violation(transport, include))
            .as("application-%s.yaml (over application.yaml): transport=%s include=%s", profile, transport, include)
            .isEmpty();
    }

    @Test
    void log_withNoInclude_isConsistent() {
        assertThat(violation("log", null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"log,smtp", "log,ses", "log,'smtp,ses'", "log,''"})
    void log_withAnyDeclaredInclude_isAViolation(String transport, String include) {
        // '' (blank) is a violation too: Spring Boot treats an empty include as "all contributors".
        assertThat(violation(transport, include)).isPresent();
    }

    @Test
    void ses_requiresSesAndForbidsSmtp() {
        assertThat(violation("ses", "ses")).isEmpty();
        assertThat(violation("ses", "smtp")).isPresent();
        assertThat(violation("ses", "smtp,ses")).isPresent();
        assertThat(violation("ses", null)).isPresent();
    }

    @Test
    void smtp_requiresSmtpAndForbidsSes() {
        assertThat(violation("smtp", "smtp")).isEmpty();
        assertThat(violation("smtp", "ses")).isPresent();
        assertThat(violation("smtp", "smtp,ses")).isPresent();
        assertThat(violation("smtp", null)).isPresent();
    }

    @Test
    void unknownTransport_isAViolation() {
        assertThat(violation("sendgrid", null)).isPresent();
    }

    /** Empty when consistent; otherwise a description of the mismatch. */
    static Optional<String> violation(String transport, String include) {
        List<String> names = include == null
            ? List.of()
            : Arrays.stream(include.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
        boolean declared = include != null;
        return switch (transport) {
            case "log" -> declared
                ? Optional.of("transport=log has no health indicator; declare no notification include (a blank "
                    + "include means every contributor in Spring Boot)")
                : Optional.empty();
            case "ses" -> names.contains("ses") && !names.contains("smtp")
                ? Optional.empty()
                : Optional.of("transport=ses requires include to name ses and not smtp, was " + names);
            case "smtp" -> names.contains("smtp") && !names.contains("ses")
                ? Optional.empty()
                : Optional.of("transport=smtp requires include to name smtp and not ses, was " + names);
            default -> Optional.of("unknown transport " + transport);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Path yaml) throws IOException {
        try (InputStream in = Files.newInputStream(yaml)) {
            Object loaded = new Yaml().load(in);
            return loaded == null ? Map.of() : (Map<String, Object>) loaded;
        }
    }

    private static String resolved(Map<String, Object> profile, Map<String, Object> base, String... path) {
        String value = string(profile, path);
        return value != null ? value : string(base, path);
    }

    private static String string(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            current = map.get(key);
        }
        return current == null ? null : current.toString();
    }
}
