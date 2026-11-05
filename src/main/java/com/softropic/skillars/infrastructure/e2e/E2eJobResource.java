package com.softropic.skillars.infrastructure.e2e;

import com.softropic.skillars.infrastructure.exception.AppSetupException;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.payment.service.SessionPackForfeitureScheduler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Pitchside UI suite only: runs one scheduled job now, so a spec can see what the job does without waiting for
 * its schedule (the pack forfeiture runs hourly). Thresholds stay real: a spec seeds state that is already due
 * (an expiry in the past), then asks for the run.
 * <p>
 * Each job runs under its own ShedLock name, so it never overlaps the scheduled run or another trigger. The lock
 * is taken with no minimum hold: the scheduled method's {@code lockAtLeastFor} would otherwise skip every trigger
 * within minutes of the last run, silently. That is why the job's target is called, not its proxy (ShedLock
 * intercepts method calls, so the proxy would apply the annotation's hold). A busy lock answers 409 and the caller
 * retries.
 * <p>
 * Only the jobs named in {@link #jobs} can run; the name is never resolved to a bean. Full admins only, and only
 * under the {@code e2e} profile. Startup is refused alongside {@code prod} or {@code uat}, as
 * {@code StripeE2eApiBaseConfig} does.
 */
@Slf4j
@RestController
@Profile(E2eJobResource.E2E_PROFILE)
@RequestMapping("/api/e2e/jobs")
public class E2eJobResource {

    static final String E2E_PROFILE = "e2e";
    private static final Set<String> DEPLOYED_PROFILES = Set.of("prod", "uat");

    /** One runnable job: its ShedLock name (as on its {@code @SchedulerLock}) and the most it may hold it. */
    record Job(String lockName, Duration lockAtMostFor, Runnable run) {
    }

    private final Environment environment;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Map<String, Job> jobs;

    public E2eJobResource(Environment environment, LockProvider lockProvider,
                          SessionPackForfeitureScheduler packForfeiture) {
        this.environment = environment;
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        SessionPackForfeitureScheduler forfeiture = target(packForfeiture);
        this.jobs = Map.of(
            "pack-forfeiture", new Job("SessionPackForfeitureScheduler_expire", Duration.ofMinutes(15),
                forfeiture::forfeitExpiredPacks));
    }

    @PostConstruct
    void refuseDeployedProfiles() {
        String[] active = environment.getActiveProfiles();
        if (Arrays.stream(active).anyMatch(DEPLOYED_PROFILES::contains)) {
            throw new AppSetupException("The 'e2e' profile exposes a job trigger, and must never run alongside a "
                + "deployed profile; active profiles: " + Arrays.toString(active));
        }
    }

    /** 204 once the job has run; 404 for a job not on the list; 409 while its lock is held elsewhere. */
    @PostMapping("/{name}")
    @PreAuthorize(SecurityConstants.HAS_FULL_ADMIN_ROLE)
    public ResponseEntity<Void> run(@PathVariable String name) {
        Job job = jobs.get(name);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        LockConfiguration lock = new LockConfiguration(Instant.now(), job.lockName(), job.lockAtMostFor(),
            Duration.ZERO);
        LockingTaskExecutor.TaskResult<Void> result;
        try {
            result = lockingTaskExecutor.executeWithLock(
                () -> {
                    job.run().run();
                    return null;
                }, lock);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            // The task is a Runnable, so only the lock provider can get here with a checked exception.
            throw new IllegalStateException("e2e job '" + name + "' failed", e);
        }
        if (!result.wasExecuted()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        log.warn("e2e profile: job '{}' run on request", name);
        return ResponseEntity.noContent().build();
    }

    /** The bean behind a Spring proxy, so a call does not pass through the scheduled method's ShedLock advice. */
    @SuppressWarnings("unchecked")
    private static <T> T target(T bean) {
        Object target = AopProxyUtils.getSingletonTarget(bean);
        return target != null ? (T) target : bean;
    }
}
