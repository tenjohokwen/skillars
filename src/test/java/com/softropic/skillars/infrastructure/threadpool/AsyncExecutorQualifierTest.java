package com.softropic.skillars.infrastructure.threadpool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC17 — every {@code @Async} method names the pool it runs on.
 *
 * <h2>Correcting the ledger entry this AC came from</h2>
 *
 * {@code skillars-4-3} W6 (2026-06-17) claimed a bare {@code @Async} falls back to
 * {@code SimpleAsyncTaskExecutor} and is therefore an unbounded thread-creation vector.
 * <strong>That was not true of this codebase.</strong> A bean named exactly {@code taskExecutor}
 * exists ({@code infrastructure/config/AsyncConfig}), and
 * {@code AsyncExecutionAspectSupport#getDefaultExecutor} resolves: unique {@code TaskExecutor} bean →
 * on {@code NoUniqueBeanDefinitionException}, the bean literally named {@code taskExecutor} → only if
 * that is absent, {@code SimpleAsyncTaskExecutor}. With several executor beans present, step two
 * matched, so bare {@code @Async} already ran on the bounded pool. Spring Boot's auto-configured
 * {@code applicationTaskExecutor} is {@code @ConditionalOnMissingBean(Executor.class)} and backs off
 * entirely, so it does not complicate the resolution either.
 *
 * <h2>What was actually worth fixing</h2>
 *
 * The correct behaviour depended on a two-step fallback keyed on a <strong>bean-name string</strong>.
 * Renaming the {@code taskExecutor} bean — or removing one of the other executors so the "unique
 * TaskExecutor" branch starts matching a different bean — silently re-routes every bare
 * {@code @Async} in the application, possibly onto {@code SimpleAsyncTaskExecutor}, at which point
 * the ledger's original claim would finally become true. Nothing anywhere would have said so.
 *
 * <p>All 11 bare sites now carry {@code @Async("taskExecutor")} (the story said 10; its own
 * enumeration totals 11 — {@code VideoSseService} ×2, {@code TimelineEventListener} ×2,
 * {@code VideoPhysicalDeletionListener} ×2, plus {@code SluCalculationService},
 * {@code RadarCompositeCalculationService}, {@code ReportGenerationService},
 * {@code HomeworkAssignmentService} and {@code SessionPlanService}). All of them, not a subset:
 * a partial fix would leave the fallback load-bearing while looking as though it were not.
 *
 * <p>This test then pins two things: no bare {@code @Async} comes back, and no {@code @Async} names
 * a pool that does not exist. The second matters because a typo'd qualifier is worse than no
 * qualifier — Spring throws at invocation time, on a background thread, where
 * {@code AsyncUncaughtExceptionHandler} logs it and the caller never learns.
 */
@DisplayName("Every @Async method must name the executor it runs on")
class AsyncExecutorQualifierTest {

    /**
     * Pools an {@code @Async} may name. Deliberately the same set
     * {@link ExecutorShutdownConfigurationTest} maintains — if a pool is added there without being
     * added here, a qualifier pointing at it fails this test and the two lists are forced back
     * together.
     */
    private static final Set<String> KNOWN_POOLS =
        Set.of("taskExecutor", "outboxDrainPool", "sluRetryExecutor", "moderationTaskExecutor", "sendMailPool");

    private record AsyncSite(String type, String method, String qualifier) {
        @Override
        public String toString() {
            return type + "#" + method + (qualifier.isEmpty() ? " (bare @Async)" : " -> " + qualifier);
        }
    }

    private static List<AsyncSite> asyncSites() throws Exception {
        List<AsyncSite> sites = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        for (var resource : resolver.getResources("classpath*:com/softropic/skillars/**/*.class")) {
            String className;
            try {
                className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            } catch (Exception e) {
                continue;
            }
            if (className.contains("$")) {
                continue;
            }
            Class<?> c;
            try {
                c = ClassUtils.forName(className, AsyncExecutorQualifierTest.class.getClassLoader());
            } catch (Throwable e) {
                continue; // a class that cannot load without initialising is not our concern
            }
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable e) {
                continue;
            }
            for (Method m : methods) {
                Async async = m.getAnnotation(Async.class);
                if (async != null) {
                    sites.add(new AsyncSite(c.getSimpleName(), m.getName(), async.value()));
                }
            }
        }
        return sites;
    }

    @Test
    @DisplayName("no @Async is left bare, so none depends on the bean-name fallback")
    void everyAsyncNamesItsExecutor() throws Exception {
        List<AsyncSite> sites = asyncSites();

        assertThat(sites)
            .as("the scan found no @Async methods at all, so it is asserting nothing")
            .isNotEmpty();

        List<String> bare = sites.stream()
            .filter(s -> s.qualifier().isEmpty())
            .map(AsyncSite::toString)
            .sorted()
            .toList();

        assertThat(bare)
            .as("""
                A bare @Async resolves through AsyncExecutionAspectSupport's bean-NAME fallback: \
                unique TaskExecutor bean, else the bean literally called 'taskExecutor', else the \
                UNBOUNDED SimpleAsyncTaskExecutor. It happens to land on the bounded pool today, but \
                renaming that bean or changing the executor bean set silently re-routes it — with no \
                error, no log, and no test failure anywhere else. Name the pool: @Async("taskExecutor").""")
            .isEmpty();
    }

    @Test
    @DisplayName("no @Async names a pool that does not exist")
    void everyQualifierNamesAKnownPool() throws Exception {
        Set<String> unknown = new TreeSet<>();
        for (AsyncSite s : asyncSites()) {
            if (!s.qualifier().isEmpty() && !KNOWN_POOLS.contains(s.qualifier())) {
                unknown.add(s.toString());
            }
        }

        assertThat(unknown)
            .as("""
                An @Async naming a bean that does not exist fails at INVOCATION time, on a background \
                thread, where AsyncUncaughtExceptionHandler logs it and the caller never finds out — \
                strictly worse than no qualifier. Add the pool to KNOWN_POOLS (and to \
                ExecutorShutdownConfigurationTest's inventory and ExecutorShutdown's budget), or fix \
                the qualifier.""")
            .isEmpty();
    }
}
