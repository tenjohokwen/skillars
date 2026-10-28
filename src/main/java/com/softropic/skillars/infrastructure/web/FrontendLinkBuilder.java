package com.softropic.skillars.infrastructure.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds absolute links into the SPA frontend for emails and browser redirects.
 *
 * <p>Two facts every such link has to get right, and which hand-built links kept getting wrong:
 * <ul>
 *   <li>the frontend is NOT served from {@code ${baseurl}:${server.port}} (that is the backend's own
 *       origin — {@code http://localhost:9990} unless overridden, and never set in prod); its origin
 *       is {@code app.frontend-url} (the same property the registration verify-email links use);</li>
 *   <li>the router runs in HASH mode ({@code quasar.config.js}), so every route lives after
 *       {@code /#/} — {@code https://host/coach/payment-settings} 404s or lands on the SPA root.</li>
 * </ul>
 * A query string appended to a {@link #route} result lands inside the hash fragment, which is where
 * vue-router (hash mode) reads {@code route.query} from.
 */
@Component
public class FrontendLinkBuilder {

    private final String frontendUrl;

    public FrontendLinkBuilder(@Value("${app.frontend-url}") String frontendUrl) {
        this.frontendUrl = stripTrailingSlashes(frontendUrl == null ? "" : frontendUrl.trim());
    }

    /** The frontend origin without a trailing slash — for templates that append {@code '/#/...'} themselves. */
    public String base() {
        return frontendUrl;
    }

    /**
     * Absolute link to a frontend route, e.g. {@code route("parent/bookings")} →
     * {@code https://skillars.com/#/parent/bookings}. Leading slashes and an already-present
     * {@code #/} prefix on {@code path} are tolerated.
     */
    public String route(String path) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("/#/")) {
            p = p.substring(3);
        } else if (p.startsWith("#/")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return frontendUrl + "/#/" + p;
    }

    /**
     * Resolves a configurable redirect target: an absolute {@code http(s)://} URL is returned as-is
     * (the operator is then responsible for including {@code #/}); anything else is treated as a
     * frontend route path and passed through {@link #route}.
     */
    public String resolve(String pathOrAbsoluteUrl) {
        if (pathOrAbsoluteUrl != null
                && (pathOrAbsoluteUrl.startsWith("https://") || pathOrAbsoluteUrl.startsWith("http://"))) {
            return pathOrAbsoluteUrl;
        }
        return route(pathOrAbsoluteUrl);
    }

    private static String stripTrailingSlashes(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
