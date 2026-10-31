package com.softropic.skillars.platform.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AppEndpoints.PUBLIC_ENDPOINTS} opens {@code /api/marketplace/coaches/**} and
 * {@code /api/reviews/coaches/**} at the filter chain (guests browse coaches and read reviews), so under those
 * prefixes the URL layer enforces nothing: the only gate for a new handler is its own method-level
 * {@code @PreAuthorize}. A handler that forgets it would be silently world-readable (or writable). This fails
 * the build for any handler under those prefixes without one; an intentionally public handler states so with
 * {@code @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)}.
 */
class PublicPrefixPreAuthorizeConventionTest {

    private static final List<String> PUBLIC_PREFIXES = List.of("/api/marketplace/coaches", "/api/reviews/coaches");

    @Test
    void everyHandlerUnderAPublicPrefixDeclaresAMethodLevelPreAuthorize() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<String> offenders = new ArrayList<>();
        int handlersUnderPrefix = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.softropic.skillars")) {
            Class<?> controller = Class.forName(bd.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> bases = paths(classMapping);
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                boolean underPrefix = false;
                for (String base : bases) {
                    for (String sub : paths(mapping)) {
                        underPrefix |= isUnderPublicPrefix(base + sub);
                    }
                }
                if (!underPrefix) {
                    continue;
                }
                handlersUnderPrefix++;
                if (!method.isAnnotationPresent(PreAuthorize.class)) {
                    offenders.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(handlersUnderPrefix).as("the scan must find the marketplace and review handlers").isPositive();
        assertThat(offenders)
            .as("handlers under a permitAll URL prefix with no method-level @PreAuthorize "
                + "(use SecurityConstants.IS_PERMIT_ALL for an intentionally public one)")
            .isEmpty();
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return List.of("");
        }
        return List.of(mapping.path());
    }

    private static boolean isUnderPublicPrefix(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
