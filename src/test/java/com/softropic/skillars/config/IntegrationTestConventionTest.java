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
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
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
        // Sliced @SpringBootTest with its own WireMock server (ses-service) — story ses-1.1 Task
        // 4b. Deliberately doesn't reuse AbstractIntegrationTest's two named servers (bunny-service,
        // stripe-service): a third server name sharing that context would fork it for a test that
        // needs none of the rest of it (no database, no Redis).
        "com.softropic.skillars.infrastructure.ses.SesEmailEndToEndIT",
        // Not a @SpringBootTest at all — builds its own real SpringApplicationBuilder per test
        // method to prove EmailTransportPropertyValidator actually runs as a registered
        // EnvironmentPostProcessor (ApplicationContextRunner cannot do this — story ses-1.1 Task 6).
        "com.softropic.skillars.infrastructure.email.EmailTransportBootIT"
    );

    /**
     * Number of concrete {@code *IT} classes still carrying their own {@code @TestPropertySource}.
     *
     * <p>These are the properties that genuinely change behaviour for one class: webhook
     * max-attempts, playback revocation window, and {@code enable.test.mail=false} (the real
     * {@code MailManager}, shared by two classes). Each costs one extra Spring context — which is
     * now roughly 20 s of startup and ZERO containers, a trade that is acceptable.
     *
     * <p>skillars-deferred-160 AC6: 7 → 4. The three video-lifecycle declarations were removed
     * because those keys are DB-backed and the annotation changed nothing.
     *
     * <p><strong>If you are here because this assertion failed:</strong> you added (or removed)
     * a {@code @TestPropertySource}. That is allowed. Update this number, and put a
     * {@code // context-fork:} comment on the annotation saying why the property cannot live in
     * {@code application-test.yaml}. Making the number deliberate is the entire point.
     */
    private static final int EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 4;

    /**
     * Number of distinct Spring context configurations among the live (not class-level
     * {@code @Disabled}) concrete {@code *IT} classes that extend {@link AbstractIntegrationTest},
     * as modelled by {@link #contextKeyModel}.
     *
     * <p>skillars-deferred-160 set this at 14, down from 30. With the 3 standalone sliced
     * {@code @SpringBootTest} classes in the allowlist that makes 17 contexts in the failsafe JVM,
     * well under Spring's 32-entry context cache, so nothing is evicted and rebuilt.
     *
     * <p><strong>If you are here because this assertion failed:</strong> a class added (or
     * removed) something that is part of the context cache key: most often a
     * {@code @MockitoBean}/{@code @MockitoSpyBean} field, also a class-level {@code @Import},
     * {@code @TestPropertySource}, {@code @DynamicPropertySource} or nested
     * {@code @TestConfiguration}. The failure message lists every configuration with its classes.
     * First check whether the class can reuse an existing configuration: use an inherited spy, or
     * match a peer's mock set with the same field names. If the fork is genuinely needed, update
     * this number and say why next to the declaration. Making the number deliberate is the point.
     */
    private static final int EXPECTED_DISTINCT_CONTEXT_KEYS = 14;

    /**
     * Spring Boot test slices ({@code @WebMvcTest} and friends) build a cut-down context with no
     * database, no Redis and no container, so they are not what this convention governs — and
     * forcing them onto {@link AbstractIntegrationTest} would make them strictly more expensive.
     * They are exempt on their merits, which is why they are excluded here rather than listed in
     * {@link #ALLOWLIST}: the allowlist is for genuine exceptions that someone should have to
     * justify, and a slice test needs no justification.
     *
     * <p>Since skillars-deferred-160 no slice may be named {@code *IT} at all
     * ({@link #noSliceTestIsNamedIT}), so in practice this only filters the discovery helper.
     */
    private static boolean isSlice(Class<?> c) {
        // Slice *test* annotations only. This used to match everything under
        // org.springframework.boot.test.autoconfigure. too, but that package also holds
        // @AutoConfigureMockMvc, which is @Inherited and sits on AbstractIntegrationTest
        // (skillars-deferred-160 AC5): getAnnotations() would then report every integration test
        // as a slice and exclude it from these checks. The prefix rule had already been silently
        // excluding VideoWebhookResourceIT, the one class that declared it.
        return Stream.of(c.getAnnotations())
            .map(a -> a.annotationType().getSimpleName())
            .anyMatch(SLICE_ANNOTATIONS::contains);
    }

    private static final Set<String> SLICE_ANNOTATIONS = Set.of(
        "WebMvcTest", "WebFluxTest", "DataJpaTest", "DataJdbcTest", "JdbcTest", "JsonTest",
        "RestClientTest", "DataRedisTest", "JooqTest", "GraphQlTest", "WebServiceClientTest");

    private static List<Class<?>> integrationTestClasses() throws IOException {
        List<Class<?>> found = allConcreteItClasses().stream().filter(c -> !isSlice(c)).toList();
        assertThat(found)
            .as("expected to discover the integration-test classes")
            .hasSizeGreaterThan(100);
        return found;
    }

    // skillars-deferred-163 AC5.1: populated by allConcreteItClasses() with any class whose
    // Class.forName failed — see loadFailuresCollectEveryClassThatFailed below for why this must
    // be asserted on, not just collected.
    private static final Map<String, Throwable> LOAD_FAILURES = new TreeMap<>();

    /** Every concrete {@code *IT} class in {@code target/test-classes}, slices included. */
    private static List<Class<?>> allConcreteItClasses() throws IOException {
        Path root = Paths.get("target", "test-classes");
        assertThat(root)
            .as("target/test-classes must exist — run this through Maven, not standalone")
            .exists();

        LOAD_FAILURES.clear();
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
                    if (!java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                        found.add(c);
                    }
                } catch (Throwable t) {
                    // skillars-deferred-163 AC5.1: with initialize=false, only linkage errors are
                    // possible here — a class that fails to load for a genuine reason used to
                    // silently never enter the model, invisible to every check built on it,
                    // including the size-100 floor itself. Recorded, not swallowed; a DEDICATED
                    // test below asserts this is empty, so a real regression fails loudly instead
                    // of just shrinking the floor's safety margin unnoticed. A stale target/
                    // classes directory (e.g. a renamed/deleted class whose old .class file is
                    // still on disk from a prior build) is a known, local-only false-positive
                    // source here — a `mvn clean` resolves it; this is not a reason to skip the fix.
                    LOAD_FAILURES.put(cn, t);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("no *IT/slice class silently failed to load into the model this suite is built on")
    void loadFailuresCollectEveryClassThatFailed() throws IOException {
        integrationTestClasses(); // populates LOAD_FAILURES as a side effect

        assertThat(LOAD_FAILURES)
            .as("""
                Each of these classes exists under *.class in target/test-classes but
                Class.forName failed to load it (a LinkageError — initialize=false means
                nothing else can throw here). A class that fails to load for a genuine reason
                is invisible to every check in this file, including the size-100 floor itself,
                unless this assertion catches it. If this fails locally and the class was
                recently renamed, moved or deleted, run `mvn clean` first — a stale class file
                left over from a prior build is the one known local-only false positive.""")
            .isEmpty();
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

    @Test
    @DisplayName("the number of distinct context configurations among the base's subclasses is pinned")
    void distinctContextKeyCountIsPinned() throws IOException {
        SortedSet<String> baseKey = contextKeyModel(AbstractIntegrationTest.class);
        Map<String, List<String>> classesByKey = new TreeMap<>();
        for (Class<?> c : integrationTestClasses()) {
            if (!AbstractIntegrationTest.class.isAssignableFrom(c) || isClassDisabled(c)) {
                continue;
            }
            SortedSet<String> delta = new TreeSet<>(contextKeyModel(c));
            delta.removeAll(baseKey);
            classesByKey.computeIfAbsent(delta.isEmpty() ? "(base only)" : String.join("\n      ", delta),
                k -> new ArrayList<>()).add(c.getSimpleName());
        }

        String table = classesByKey.entrySet().stream()
            .map(e -> "  " + e.getValue().size() + " class(es): " + e.getValue().stream().sorted().toList()
                      + "\n      " + e.getKey())
            .collect(Collectors.joining("\n"));

        assertThat(classesByKey)
            .as("""
                Each distinct configuration below is a separate Spring ApplicationContext in the \
                failsafe JVM, and Spring caches only 32 of them before evicting and rebuilding. \
                If you added a bean override, @Import, @TestPropertySource, @DynamicPropertySource \
                or nested @TestConfiguration, see EXPECTED_DISTINCT_CONTEXT_KEYS's javadoc. \
                Configurations (difference from AbstractIntegrationTest):
                %s""", table)
            .hasSize(EXPECTED_DISTINCT_CONTEXT_KEYS);
    }

    @Test
    @DisplayName("no Spring Boot slice test is named *IT")
    void noSliceTestIsNamedIT() throws IOException {
        List<String> offenders = allConcreteItClasses().stream()
            .filter(IntegrationTestConventionTest::isSlice)
            .map(Class::getName)
            .sorted()
            .toList();

        assertThat(offenders)
            .as("""
                These are Spring Boot slice tests (@WebMvcTest and friends) named *IT, so failsafe \
                runs them in the integration-test JVM, where each one takes a slot in Spring's \
                32-entry context cache next to the full contexts. They need no container: name \
                them *WebMvcTest (or another *Test suffix) so surefire runs them instead \
                (skillars-deferred-160 AC2).""")
            .isEmpty();
    }

    /**
     * A conservative model of what Spring folds into a test class's context cache key, built from
     * the class hierarchy. Over-distinguishing is acceptable here, because the CI context-count
     * gate measures reality; under-distinguishing is not. Captured, per class in the hierarchy:
     * <ul>
     *   <li>class-level annotations that configure the context, with all attribute values
     *       ({@code toString()} includes them);</li>
     *   <li>{@code @DynamicPropertySource} methods and nested {@code @Configuration}/
     *       {@code @TestConfiguration} classes, by name, since each is specific to its class;</li>
     *   <li>every {@code @MockitoBean}/{@code @MockitoSpyBean} field: the annotation with its
     *       attributes, the field type, the field name and the field's other annotations. This
     *       mirrors {@code BeanOverrideHandler.equals}, which for by-type overrides compares the
     *       field name and qualifiers too, not just the type.</li>
     * </ul>
     */
    private static SortedSet<String> contextKeyModel(Class<?> testClass) {
        SortedSet<String> key = new TreeSet<>();
        for (Class<?> c = testClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Annotation a : c.getDeclaredAnnotations()) {
                if (isContextAnnotation(a)) {
                    key.add("class " + a);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (hasAnnotationNamed(m.getDeclaredAnnotations(), "DynamicPropertySource")) {
                    key.add("@DynamicPropertySource " + c.getName() + "#" + m.getName());
                }
            }
            for (Class<?> nested : c.getDeclaredClasses()) {
                if (hasAnnotationNamed(nested.getDeclaredAnnotations(), "TestConfiguration", "Configuration")) {
                    key.add("nested configuration " + nested.getName());
                }
            }
            for (Field f : c.getDeclaredFields()) {
                Annotation[] anns = f.getDeclaredAnnotations();
                if (hasAnnotationNamed(anns, "MockitoBean", "MockitoSpyBean", "MockBean", "SpyBean")) {
                    key.add("field " + f.getType().getName() + " " + f.getName() + " "
                            + Arrays.stream(anns).map(Annotation::toString).sorted().toList());
                }
            }
        }
        return key;
    }

    private static boolean isContextAnnotation(Annotation a) {
        String n = a.annotationType().getName();
        String simple = a.annotationType().getSimpleName();
        return n.startsWith("org.springframework.boot.test.autoconfigure.")
            || n.startsWith("org.wiremock.spring.")
            || Set.of("SpringBootTest", "ActiveProfiles", "Import", "TestPropertySource",
                      "TestPropertySources", "ContextConfiguration", "DirtiesContext",
                      "MockitoBean", "MockitoBeans", "MockitoSpyBean", "MockitoSpyBeans",
                      "BootstrapWith").contains(simple);
    }

    private static boolean hasAnnotationNamed(Annotation[] anns, String... simpleNames) {
        Set<String> names = Set.of(simpleNames);
        return Arrays.stream(anns).anyMatch(a -> names.contains(a.annotationType().getSimpleName()));
    }

    private static boolean isClassDisabled(Class<?> c) {
        return hasAnnotationNamed(c.getAnnotations(), "Disabled");
    }
}
