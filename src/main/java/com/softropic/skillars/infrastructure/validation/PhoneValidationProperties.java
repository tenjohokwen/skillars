package com.softropic.skillars.infrastructure.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * skillars-deferred-99 AC16 — per-market phone-number validation, replacing the hard-wired Cameroon
 * assumptions ("exactly 9 digits", "start with 6", "MTN/Orange/NextTel").
 *
 * <p>The default is a permissive minimum-viable rule (optional leading {@code +}, 7–15 digits) — it
 * deliberately does not try to encode short codes or extensions. A market that needs something
 * stricter (or a local format) supplies its own {@code pattern} + {@code hint-key} under
 * {@code markets.<code>}. Passive value holder (infrastructure), registered via
 * {@code @EnableConfigurationProperties} in {@code ValidationConfig}.
 */
@ConfigurationProperties(prefix = "app.validation.phone")
public class PhoneValidationProperties {

    /** Applied when no market rule matches. Permissive by design. */
    private String defaultPattern = "^\\+?\\d{7,15}$";

    /** i18n key for the field hint shown with the default rule. */
    private String defaultHintKey = "validation.phone.hintDefault";

    /** Per-market overrides, keyed by an uppercase market/region code (e.g. {@code CM}, {@code DE}). */
    private Map<String, MarketRule> markets = new LinkedHashMap<>();

    public String getDefaultPattern() {
        return defaultPattern;
    }

    public void setDefaultPattern(String defaultPattern) {
        this.defaultPattern = defaultPattern;
    }

    public String getDefaultHintKey() {
        return defaultHintKey;
    }

    public void setDefaultHintKey(String defaultHintKey) {
        this.defaultHintKey = defaultHintKey;
    }

    public Map<String, MarketRule> getMarkets() {
        return markets;
    }

    public void setMarkets(Map<String, MarketRule> markets) {
        this.markets = markets;
    }

    public static class MarketRule {
        private String pattern;
        private String hintKey;

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public String getHintKey() {
            return hintKey;
        }

        public void setHintKey(String hintKey) {
            this.hintKey = hintKey;
        }
    }
}
