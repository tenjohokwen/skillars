package com.softropic.skillars.infrastructure.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link ReloadableResourceBundleMessageSource} that, with {@code useCodeAsDefaultMessage} on,
 * degrades a completely-unresolvable code to "the code, plus one WARN" instead of a
 * {@code NoSuchMessageException} 500 (skillars-deferred-99 AC3).
 *
 * <h2>Why</h2>
 *
 * <p>{@code MvcConfig} sets {@code setFallbackToSystemLocale(false)} (deliberate — removes a
 * container-{@code Locale.getDefault()} dependency) but not {@code setUseCodeAsDefaultMessage}. So a
 * key present in code but missing from <em>every</em> bundle — including base
 * {@code messages.properties} — threw {@code NoSuchMessageException} at request time for any locale
 * outside de/fr/en. {@code MessageBundleParityTest} keeps the four static bundles in parity, so this
 * can now only bite a <strong>dynamically-constructed</strong> code
 * ({@code getMessage(prefix + var, ...)}) — which no build-time literal scan can catch. This class is
 * the runtime safety net for exactly that case.
 *
 * <h2>Bounded warn-once</h2>
 *
 * <p>The seen-codes set is a bounded LRU ({@value #MAX_WARNED_CODES} entries) rather than an
 * unbounded {@code Set<String>}: a production bug that resolves many distinct dynamic missing codes
 * must not leak memory. An evicted code may be warned about again later — acceptable; the point is a
 * bound, not exactly-once.
 */
public class WarnOnMissingMessageSource extends ReloadableResourceBundleMessageSource {

    private static final Logger log = LoggerFactory.getLogger(WarnOnMissingMessageSource.class);

    // skillars-deferred-99 AC3: configurable to avoid silent degradation if deployment uses >1000 dynamic keys
    static final int MAX_WARNED_CODES = Integer.parseInt(System.getProperty("skillars.i18n.warn-cache-size", "1000"));

    private final Map<String, Boolean> warned = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_WARNED_CODES;
            }
        });

    /**
     * {@code AbstractMessageSource} calls this only when a code resolved to nothing in any bundle and
     * {@code useCodeAsDefaultMessage} is on — i.e. exactly the "would have been a 500" path.
     */
    @Override
    protected String getDefaultMessage(String code) {
        if (isUseCodeAsDefaultMessage() && warned.putIfAbsent(code, Boolean.TRUE) == null) {
            log.warn("i18n: no message for code '{}' in any bundle (incl. base messages.properties) — "
                + "returning the code to the client. Add it to i18n/messages.properties, or check "
                + "for a dynamically-built code.", code);
        }
        return super.getDefaultMessage(code);
    }
}
