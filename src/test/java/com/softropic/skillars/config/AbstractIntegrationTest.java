package com.softropic.skillars.config;

import com.softropic.skillars.e2e.HttpTestClient;
import com.softropic.skillars.infrastructure.gemini.GeminiClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;

/**
 * The canonical base class for every integration test in this codebase.
 *
 * <h2>Why inheritance rather than {@code @Import(TestConfig.class)}</h2>
 *
 * {@code @Import(TestConfig.class)} communicates <em>which beans</em> a test needs. It
 * communicates <strong>nothing</strong> about the Spring context cache key — yet the cache key is
 * what decides whether a test class costs nothing or a full Spring Boot startup plus a pair of
 * Docker containers. The annotation that mattered was invisible in the mental model of the
 * annotation people were copying.
 *
 * <p>Correct sharing requires <em>every</em> contributing annotation to match exactly across all
 * ~130 classes: {@code @SpringBootTest} arguments, {@code @ActiveProfiles},
 * {@code @TestPropertySource}, {@code @Import}, {@code @EnableWireMock} server names, and the set
 * of {@code @MockitoBean}/{@code @MockitoSpyBean} declarations. With copy-paste, drift is not a
 * risk, it is a certainty: it produced 37+ contexts out of 7 genuinely distinct configurations,
 * with 23 of them serving a single class each. The drift was invisible at review time and free at
 * authoring time — adding one mocked type is a locally-correct one-line change whose real cost
 * appears nowhere near the diff.
 *
 * <p>Inheritance makes the cache key <strong>structurally identical by construction</strong>
 * rather than by convention. A subclass that adds nothing <em>cannot</em> fork the context.
 * Forking becomes an explicit act — adding an annotation to a concrete class — which
 * {@code IntegrationTestConventionTest} then fails the build on.
 *
 * <p>The honest trade-off: this consumes the single-inheritance slot and couples every test to one
 * hierarchy. A composed meta-annotation would give most of the same cache-key guarantee without
 * that. Inheritance was chosen anyway because this base must hold <em>behaviour</em> — shared
 * fixtures, {@link #baseUrl()}, {@link #releaseSchedulerLock(String)}, and the reset listener
 * registration — which a meta-annotation cannot, and because four {@code Base*IT} classes had
 * already established the pattern here.
 *
 * <h2>Writing a new integration test</h2>
 *
 * Extend this class (or one of the flavoured subclasses), seed the data your test needs, and add
 * no class-level annotations. If you genuinely need a different property, add it and expect the
 * guardrail test to fail — then update its pinned count with a justification. That failure is the
 * design working, not an obstacle.
 *
 * @see SharedContainers for why the containers are JVM-static and not beans
 * @see <a href="file:../../../../../../../docs/testing/readme.md">docs/testing/</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
@Import(TestConfig.class)
// Both named servers are declared here, and this is mandatory rather than cosmetic:
// application-test.yaml:35 binds app.video.bunny.api-base-url to
// ${wiremock.server.bunny-service.baseUrl} with NO default (unlike its neighbours at :34, :48
// and :52, which all carry a fallback). Previously only BaseVideoIT declared bunny-service and
// only BasePaymentIT declared stripe-service -- which was itself one of the context-key
// differences. Unifying the base without declaring both would fail placeholder resolution for
// any context that binds the Bunny properties. Two in-JVM WireMock servers cost nothing next to
// a container.
@EnableWireMock({
    @ConfigureWireMock(name = "bunny-service"),
    @ConfigureWireMock(name = "stripe-service")
})
// MERGE_WITH_DEFAULTS keeps Spring's own listeners (notably SqlScriptsTestExecutionListener and
// MockitoResetTestExecutionListener) and adds ours. @TestExecutionListeners does NOT contribute
// to MergedContextConfiguration, so this adds no contexts.
@TestExecutionListeners(
    listeners = DatabaseResetTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public abstract class AbstractIntegrationTest {

    /**
     * Hoisted here because 19 test classes mocked it independently, in several different
     * combinations, and each distinct combination cost a whole ApplicationContext.
     *
     * <p>Verified safe before hoisting, per AC4's trap-check: no {@code *IT} class references
     * {@code GeminiClient} without mocking it, and — decisively — no test declares the default
     * (unnamed) WireMock server. Only {@code bunny-service} and {@code stripe-service} exist, so
     * {@code infrastructure.gemini.api-base-url}
     * ({@code ${wiremock.server.baseUrl:http://localhost:9999}}, application-test.yaml:52) always
     * resolves to the dead-port fallback. There is no live Gemini WireMock path for this hoist to
     * silently shadow.
     *
     * <p>No manual reset is needed. {@code @MockitoBean.reset()} defaults to
     * {@code MockReset.AFTER} and the default {@code MockitoResetTestExecutionListener} enforces
     * it after every test method, so stubs cannot leak between classes. Do not add
     * {@code Mockito.reset(...)} for this field — it is redundant.
     */
    @MockitoBean
    protected GeminiClient geminiClient;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    /**
     * Component-scanned, because src/test/java shares the com.softropic.skillars root package and
     * is on the test classpath. Do NOT declare it as a {@code @Bean} in {@code TestConfig} — that
     * would create a duplicate-bean conflict.
     */
    @Autowired
    protected HttpTestClient httpTestClient;

    @LocalServerPort
    protected int randomServerPort;

    protected String baseUrl() {
        return "http://localhost:" + randomServerPort;
    }

    /**
     * Releases a ShedLock so a {@code @SchedulerLock}-annotated method can be invoked again.
     * Promoted from {@code BasePaymentIT}, where it was written and proved correct.
     *
     * <p>Not optional housekeeping: calling a scheduled bean method from a test goes through the
     * Spring proxy, so ShedLock applies. With {@code lockAtLeastFor} set (PT2M across this
     * codebase), a second invocation inside the same test class is silently SKIPPED — logged as
     * "held by another instance" — and the assertions that follow then pass or fail for reasons
     * unrelated to the code under test. Call this before every invocation.
     *
     * <p><strong>Expire the row; never delete it.</strong> {@code JdbcTemplateLockProvider} caches
     * the lock names it has already inserted and thereafter issues only
     * {@code UPDATE … WHERE name = ? AND lock_until <= now()}. Deleting the row leaves nothing for
     * that UPDATE to match, so every later acquisition is skipped — strictly worse than doing
     * nothing. Backdating works on both paths: the UPDATE matches, and on the very first call (no
     * row yet) it simply affects zero rows and the provider inserts as usual.
     */
    protected void releaseSchedulerLock(String lockName) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE main.shedlock SET lock_until = now() - interval '1 minute' WHERE name = ?",
                lockName);
            return null;
        });
    }
}
