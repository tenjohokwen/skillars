package com.softropic.skillars.infrastructure.validation;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * skillars-deferred-99 AC16 — resolves the "market" whose rules apply to the current request, with a
 * fixed, documented precedence:
 *
 * <ol>
 *   <li><b>explicit request signal</b> — an {@code explicitMarket} argument (from a DTO field or an
 *       {@code X-Market} header), when a caller supplies one. No request carries this today, so this
 *       tier is currently inert — it is the seam for adding it without touching call sites again.</li>
 *   <li><b>authenticated user's stored market</b> — also inert today (no such profile field);
 *       reserved as the second tier.</li>
 *   <li><b>the region subtag of the resolved request {@link Locale}</b> ({@code fr-CM} → {@code CM}),
 *       via {@link LocaleContextHolder}. A bare-language locale ({@code fr}, no region) has no
 *       country and falls straight through — never guess a country from a language.</li>
 *   <li><b>default</b> — {@code null}, meaning "use the permissive default rule".</li>
 * </ol>
 */
@Component
public class MarketResolver {

    /** @return an uppercase market code, or {@code null} to mean "use the default rule". */
    public String resolveMarketCode(String explicitMarket) {
        if (explicitMarket != null && !explicitMarket.isBlank()) {
            return explicitMarket.trim().toUpperCase(Locale.ROOT);
        }
        Locale locale = LocaleContextHolder.getLocale();
        String region = locale != null ? locale.getCountry() : "";
        return region == null || region.isBlank() ? null : region.toUpperCase(Locale.ROOT);
    }

    /** Convenience for the common case where no explicit signal is available. */
    public String resolveMarketCode() {
        return resolveMarketCode(null);
    }
}
