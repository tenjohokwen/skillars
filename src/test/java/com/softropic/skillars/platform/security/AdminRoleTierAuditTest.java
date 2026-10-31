package com.softropic.skillars.platform.security;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-28 guard: the limited admin tier ({@code ROLE_LTD_ADMIN}) must stay limited. Reflection over every
 * {@code @RestController} in the application (conditional ones included) enforces:
 * <ol>
 *   <li>every admin handler (path contains {@code /admin}, or a role expression naming ADMIN / LTD_ADMIN) is
 *       guarded by exactly {@link SecurityConstants#HAS_ADMIN_ROLE} (shared tier) or
 *       {@link SecurityConstants#HAS_FULL_ADMIN_ROLE} (ADMIN only) - no hand-rolled expression, no guard-less
 *       admin endpoint;</li>
 *   <li>a state-changing (POST/PUT/PATCH/DELETE) handler may use the shared tier only if it is on
 *       {@link #SHARED_TIER_WRITES}, with a reason;</li>
 *   <li>a service-layer {@code @PreAuthorize(HAS_ADMIN_ROLE)} is rejected: services use the full tier (the
 *       limited tier is only reachable through the controllers listed here).</li>
 * </ol>
 * A new admin endpoint therefore defaults to failing this test until it is classified; the safe choice is
 * {@code HAS_FULL_ADMIN_ROLE}. Loosening a handler to the shared tier is a deliberate edit of the list below.
 */
class AdminRoleTierAuditTest {

    /**
     * The ONLY state-changing endpoints open to ROLE_LTD_ADMIN: content moderation and marking an alert handled.
     * None moves money, changes enforcement, config, roles or deletes anything.
     */
    private static final Set<String> SHARED_TIER_WRITES = Set.of(
        // moderation of user-generated content (review queue)
        "AdminReviewResource#approveReview",
        "AdminReviewResource#blockReview",
        // moderation of held messages
        "AdminModerationResource#approveMessage",
        "AdminModerationResource#blockMessage",
        // "mark handled" on admin alerts; the underlying money/safety action is a separate full-admin endpoint
        "AdminModerationResource#resolveSafetyHoldAlert",
        "AdminModerationResource#resolvePaymentAlert");

    private static final Set<String> WRITE_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private record Handler(String id, String path, Set<String> verbs, String expression) {
        boolean isWrite() {
            return verbs.isEmpty() || verbs.stream().anyMatch(WRITE_VERBS::contains);
        }
    }

    @Test
    void everyAdminHandlerUsesAClassifiedTier() {
        List<String> offenders = new ArrayList<>();
        for (Handler h : adminHandlers()) {
            if (!SecurityConstants.HAS_ADMIN_ROLE.equals(h.expression())
                && !SecurityConstants.HAS_FULL_ADMIN_ROLE.equals(h.expression())) {
                offenders.add(h.id() + " " + h.verbs() + " " + h.path() + " -> " + h.expression());
            }
        }
        assertThat(offenders)
            .as("admin endpoints must be guarded by HAS_ADMIN_ROLE (read/moderate) or HAS_FULL_ADMIN_ROLE (ADMIN only)")
            .isEmpty();
    }

    @Test
    void sharedTierIsReadOnlyExceptTheAllowList() {
        List<String> offenders = new ArrayList<>();
        Set<String> seenSharedWrites = new TreeSet<>();
        for (Handler h : adminHandlers()) {
            if (SecurityConstants.HAS_ADMIN_ROLE.equals(h.expression()) && h.isWrite()) {
                if (SHARED_TIER_WRITES.contains(h.id())) {
                    seenSharedWrites.add(h.id());
                } else {
                    offenders.add(h.id() + " " + h.verbs() + " " + h.path());
                }
            }
        }
        assertThat(offenders)
            .as("state-changing admin endpoints open to ROLE_LTD_ADMIN: use HAS_FULL_ADMIN_ROLE, or add to "
                + "SHARED_TIER_WRITES with a reason if it is plain moderation")
            .isEmpty();
        assertThat(SHARED_TIER_WRITES)
            .as("a SHARED_TIER_WRITES entry no longer exists or is no longer on the shared tier - remove it")
            .isEqualTo(seenSharedWrites);
    }

    @Test
    void noServiceLayerMethodIsOpenToTheLimitedTier() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> c : scan(Component.class)) {
            if (c.isAnnotationPresent(RestController.class)) {
                continue;
            }
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(c, PreAuthorize.class);
            if (onClass != null && SecurityConstants.HAS_ADMIN_ROLE.equals(onClass.value())) {
                offenders.add(c.getSimpleName());
            }
            for (Method m : c.getDeclaredMethods()) {
                PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                if (pa != null && SecurityConstants.HAS_ADMIN_ROLE.equals(pa.value())) {
                    offenders.add(c.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertThat(offenders)
            .as("service-layer methods must use HAS_FULL_ADMIN_ROLE (or AdminTierGuard.requireFullAdmin())")
            .isEmpty();
    }

    @Test
    void thereAreAdminHandlersToCheck() {
        // Guards against the scan silently finding nothing (wrong package / classpath) and passing vacuously.
        assertThat(adminHandlers().stream().map(Handler::id))
            .contains("AdminUserResource#suspendUser", "AdminReviewResource#approveReview",
                "ConfigResource#updateValue", "AlertRuleAdminResource#create",
                "SesCutoverPreflightResource#runPreflight", "AdminVideoResource#deleteVideo");
    }

    // ── scanning ────────────────────────────────────────────────

    private static List<Handler> adminHandlers() {
        List<Handler> out = new ArrayList<>();
        for (Class<?> c : scan(RestController.class)) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String base = classMapping != null && classMapping.path().length > 0 ? classMapping.path()[0] : "";
            PreAuthorize onClass = AnnotatedElementUtils.findMergedAnnotation(c, PreAuthorize.class);
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                String expr = onMethod != null ? onMethod.value() : onClass != null ? onClass.value() : "<none>";
                String path = base + (mapping.path().length > 0 ? mapping.path()[0] : "");
                Set<String> verbs = new TreeSet<>();
                Arrays.stream(mapping.method()).map(RequestMethod::name).forEach(verbs::add);
                boolean adminPath = path.contains("/admin");
                boolean adminExpr = (expr.contains("ROLE_ADMIN") || expr.contains("ROLE_LTD_ADMIN"))
                    && !SecurityConstants.HAS_ANY_ROLE.equals(expr);
                if (adminPath || adminExpr) {
                    out.add(new Handler(c.getSimpleName() + "#" + m.getName(), path, verbs, expr));
                }
            }
        }
        return out;
    }

    private static List<Class<?>> scan(Class<? extends java.lang.annotation.Annotation> stereotype) {
        // Conditions are deliberately ignored: a transport-gated resource (SES) must be audited too.
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(stereotype));
        List<Class<?>> classes = new ArrayList<>();
        scanner.findCandidateComponents("com.softropic.skillars").forEach(bd -> {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException(e);
            }
        });
        return classes;
    }
}
