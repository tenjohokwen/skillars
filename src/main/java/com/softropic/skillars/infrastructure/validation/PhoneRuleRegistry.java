package com.softropic.skillars.infrastructure.validation;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * skillars-deferred-99 AC16 — resolves the active phone-validation rule (compiled pattern + hint
 * key) for a request, from {@link PhoneValidationProperties} via {@link MarketResolver}.
 *
 * <p>Also publishes itself to a {@code static} holder so {@link CamPhoneValidator} — a Bean
 * Validation {@code ConstraintValidator}, which this codebase does not Spring-inject (see
 * {@code ValidFocusCodeValidator}) — can reach it. When the holder is not yet populated (a
 * hand-instantiated validator in a unit test), callers fall back to a built-in permissive default.
 */
@Component
public class PhoneRuleRegistry {

    /** The permissive fallback when config is unavailable — same as {@code PhoneValidationProperties}' default. */
    static final Pattern DEFAULT_FALLBACK_PATTERN = Pattern.compile("^\\+?\\d{7,15}$");
    static final String DEFAULT_FALLBACK_HINT_KEY = "validation.phone.hintDefault";

    private static volatile PhoneRuleRegistry instance;

    private final PhoneValidationProperties properties;
    private final MarketResolver marketResolver;

    public PhoneRuleRegistry(PhoneValidationProperties properties, MarketResolver marketResolver) {
        this.properties = properties;
        this.marketResolver = marketResolver;
    }

    @PostConstruct
    void publish() {
        instance = this;
    }

    /** The registry bean once Spring has built it, or {@code null} in a bare unit test. */
    static PhoneRuleRegistry current() {
        return instance;
    }

    /** Test hook: publish this instance as the static one, or (with {@code null}) clear it. */
    static void publishForTest(PhoneRuleRegistry registry) {
        instance = registry;
    }

    public PhoneRule ruleFor(String explicitMarket) {
        String market = marketResolver.resolveMarketCode(explicitMarket);
        PhoneValidationProperties.MarketRule marketRule =
            market == null ? null : properties.getMarkets().get(market);
        if (marketRule != null && marketRule.getPattern() != null) {
            return new PhoneRule(compile(marketRule.getPattern()),
                marketRule.getHintKey() != null ? marketRule.getHintKey() : properties.getDefaultHintKey());
        }
        return new PhoneRule(compile(properties.getDefaultPattern()), properties.getDefaultHintKey());
    }

    private Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException bad) {
            // A misconfigured pattern must not make every phone number invalid; fall back to permissive.
            return DEFAULT_FALLBACK_PATTERN;
        }
    }

    public record PhoneRule(Pattern pattern, String hintKey) {
    }
}
