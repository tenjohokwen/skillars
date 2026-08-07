package com.softropic.skillars.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail: stops the Spring-context fragmentation this codebase just spent a story removing
 * from growing back.
 *
 * <p>Before that work, ~130 integration tests hand-copied {@code @SpringBootTest} +
 * {@code @ActiveProfiles} + {@code @Import(TestConfig.class)} + {@code @TestPropertySource} onto
 * themselves, and the copies drifted. Every distinct combination is a separate
 * {@code MergedContextConfiguration}, therefore a separate ApplicationContext, therefore (before
 * containers were made JVM-static) another PostgreSQL and another Redis container. That produced
 * 37+ contexts out of 7 genuinely distinct configurations.
 *
 * <p>The cost of adding one was invisible at authoring time and invisible at review time. This
 * test makes it visible: it fails the build.
 *
 * <p><strong>Named {@code *Test}, not {@code *IT}, deliberately.</strong> Surefire runs it in the
 * {@code test} phase, ahead of failsafe, so it fails in seconds without starting a container.
 * Renaming it would defeat that.
 *
 * <p>It reads compiled classes from {@code target/test-classes} reflectively rather than parsing
 * source, and needs no new dependency (ArchUnit would add one for no material gain here).
 */
@DisplayName("Integration tests must not declare their own Spring context")
class IntegrationTestConventionTest {

    /**
     * The only classes permitted to configure their own context. Each is a deliberate,
     * documented fork — see docs/testing/. Adding to this list is a design decision, not a fix.
     */
    private static final Set<String> ALLOWLIST = Set.of(
        // Sliced @SpringBootTest(classes = ...) — starts no containers at all.
        "com.softropic.skillars.infrastructure.security.RateLimitingAspectIT",
        "com.softropic.skillars.infrastructure.feature.PropertiesFeatureToggleServiceIT",
        // Needs its own FailureEventCapture inner @Configuration to observe fail-closed behaviour.
        "com.softropic.skillars.platform.messaging.api.ModerationFailClosedIT",
        // Sliced @SpringBootTest for the moderation sweeper.
        "com.softropic.skillars.platform.messaging.service.MessageModerationSweeperIT"
    );

    /**
     * Number of concrete {@code *IT} classes still carrying their own {@code @TestPropertySource}.
     *
     * <p>These are the properties that genuinely change behaviour for one class: webhook
     * max-attempts, playback revocation window, video-lifecycle outbox attempts, and the
     * payments/invoicing feature toggles. Each costs one extra Spring context — which is now
     * roughly 20 s of startup and ZERO containers, a trade that is acceptable.
     *
     * <p><strong>If you are here because this assertion failed:</strong> you added (or removed)
     * a {@code @TestPropertySource}. That is allowed. Update this number, and put a
     * {@code // context-fork:} comment on the annotation saying why the property cannot live in
     * {@code application-test.yaml}. Making the number deliberate is the entire point.
     */
    private static final int EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 5;

    /**
     * Spring Boot test slices ({@code @WebMvcTest} and friends) build a cut-down context with no
     * database, no Redis and no container, so they are not what this convention governs — and
     * forcing them onto {@link AbstractIntegrationTest} would make them strictly more expensive.
     * They are exempt on their merits, which is why they are excluded here rather than listed in
     * {@link #ALLOWLIST}: the allowlist is for genuine exceptions that someone should have to
     * justify, and a slice test needs no justification.
     *
     * <p>This mirrors the offline context-count analysis, which likewise counts only
     * {@code @SpringBootTest} classes (129 of the 144 {@code *IT} files).
     */
    private static boolean isSlice(Class<?> c) {
        return Stream.of(c.getAnnotations())
            .map(a -> a.annotationType().getName())
            .anyMatch(n -> n.startsWith("org.springframework.boot.test.autoconfigure.")
                        || n.endsWith(".WebMvcTest")
                        || n.endsWith(".DataJpaTest")
                        || n.endsWith(".JsonTest")
                        || n.endsWith(".WebFluxTest")
                        || n.endsWith(".JdbcTest"));
    }

    private static List<Class<?>> integrationTestClasses() throws IOException {
        Path root = Paths.get("target", "test-classes");
        assertThat(root)
            .as("target/test-classes must exist — run this through Maven, not standalone")
            .exists();

        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith("IT.class") || name.contains("$")) {
                    continue;
                }
                String cn = root.relativize(p).toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replaceAll("\\.class$", "");
                try {
                    Class<?> c = Class.forName(cn, false,
                        IntegrationTestConventionTest.class.getClassLoader());
                    if (!java.lang.reflect.Modifier.isAbstract(c.getModifiers()) && !isSlice(c)) {
                        found.add(c);
                    }
                } catch (Throwable ignored) {
                    // A class that cannot be loaded without initialising it is not our concern.
                }
            }
        }
        assertThat(found)
            .as("expected to discover the integration-test classes")
            .hasSizeGreaterThan(100);
        return found;
    }

    @Test
    @DisplayName("every *IT extends AbstractIntegrationTest, or is an explicit documented exception")
    void everyIntegrationTestExtendsTheCanonicalBase() throws IOException {
        List<String> offenders = integrationTestClasses().stream()
            .filter(c -> !AbstractIntegrationTest.class.isAssignableFrom(c))
            .map(Class::getName)
            .filter(n -> !ALLOWLIST.contains(n))
            .sorted()
            .toList();

        assertThat(offenders)
            .as("""
                These *IT classes neither extend AbstractIntegrationTest nor appear in the \
                allowlist. Extend AbstractIntegrationTest (or a flavoured subclass). A class that \
                adds nothing cannot fork the Spring context, which is the whole point — see \
                docs/testing/why-inheritance-over-import.md.""")
            .isEmpty();
    }

    @Test
    @DisplayName("no *IT re-declares @SpringBootTest, @ActiveProfiles or @Import(TestConfig)")
    void noIntegrationTestRedeclaresContextAnnotations() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : integrationTestClasses()) {
            if (ALLOWLIST.contains(c.getName())) {
                continue;
            }
            // getDeclaredAnnotation, not getAnnotation: inheriting these from the base is
            // exactly what we want; re-declaring them locally is what forks the context.
            if (c.getDeclaredAnnotation(SpringBootTest.class) != null) {
                offenders.add(c.getName() + " declares @SpringBootTest");
            }
            if (c.getDeclaredAnnotation(ActiveProfiles.class) != null) {
                offenders.add(c.getName() + " declares @ActiveProfiles");
            }
            Import imp = c.getDeclaredAnnotation(Import.class);
            if (imp != null) {
                for (Class<?> v : imp.value()) {
                    if (v.equals(TestConfig.class)) {
                        offenders.add(c.getName() + " declares @Import(TestConfig.class)");
                    }
                }
            }
        }

        assertThat(offenders)
            .as("""
                AbstractIntegrationTest already carries these. Re-declaring one produces a \
                different MergedContextConfiguration, hence a separate ApplicationContext, hence \
                a full Spring Boot startup this test class did not need. Delete the annotation.\
                """)
            .isEmpty();
    }

    @Test
    @DisplayName("the number of context-forking @TestPropertySource declarations is pinned")
    void testPropertySourceCountIsPinned() throws IOException {
        List<String> withProps = integrationTestClasses().stream()
            .filter(c -> c.getDeclaredAnnotation(TestPropertySource.class) != null)
            .map(Class::getName)
            .sorted()
            .toList();

        assertThat(withProps)
            .as("""
                Each of these costs one extra Spring context. That is an acceptable trade for a \
                property that genuinely changes behaviour — but it must be a deliberate one. If \
                you added or removed a @TestPropertySource, update \
                EXPECTED_TEST_PROPERTY_SOURCE_COUNT and add a // context-fork: comment \
                explaining why the property cannot live in application-test.yaml.""")
            .hasSize(EXPECTED_TEST_PROPERTY_SOURCE_COUNT);
    }
}
