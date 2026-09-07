package com.softropic.skillars.infrastructure.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-99 AC16 — the phone rule is config-driven per market, defaulting to permissive
 * international, and market resolution follows a fixed precedence.
 */
class PhoneValidationConfigTest {

    private final MarketResolver marketResolver = new MarketResolver();

    @AfterEach
    void clearState() {
        LocaleContextHolder.resetLocaleContext();
        PhoneRuleRegistry.publishForTest(null);
    }

    // ---------------------------------------------------------------- MarketResolver precedence

    @Test
    void marketResolver_explicitSignalWins() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-BE"));
        assertThat(marketResolver.resolveMarketCode("cm")).isEqualTo("CM");
    }

    @Test
    void marketResolver_fallsBackToLocaleRegion() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr-CM"));
        assertThat(marketResolver.resolveMarketCode(null)).isEqualTo("CM");
    }

    @Test
    void marketResolver_bareLanguageLocale_fallsThroughToDefault_neverGuessesCountry() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr"));
        assertThat(marketResolver.resolveMarketCode(null)).isNull();
    }

    // ---------------------------------------------------------------- PhoneRuleRegistry

    private PhoneRuleRegistry registryWith(PhoneValidationProperties props) {
        return new PhoneRuleRegistry(props, marketResolver);
    }

    @Test
    void ruleFor_noMarketMatch_usesPermissiveDefault() {
        PhoneRuleRegistry registry = registryWith(new PhoneValidationProperties());

        var rule = registry.ruleFor(null);

        assertThat(rule.pattern().matcher("+15551234567").matches()).isTrue();
        assertThat(rule.pattern().matcher("77012345").matches()).isTrue();
        assertThat(rule.pattern().matcher("abc").matches()).isFalse();
        assertThat(rule.pattern().matcher("1234").matches()).isFalse();
    }

    @Test
    void ruleFor_configuredMarketPattern_isEnforcedWhenMarketResolves() {
        PhoneValidationProperties props = new PhoneValidationProperties();
        PhoneValidationProperties.MarketRule cm = new PhoneValidationProperties.MarketRule();
        cm.setPattern("^(\\+237|00237)?6\\d{8}$");
        cm.setHintKey("validation.phone.hintCm");
        props.getMarkets().put("CM", cm);
        PhoneRuleRegistry registry = registryWith(props);

        var cmRule = registry.ruleFor("cm");
        assertThat(cmRule.hintKey()).isEqualTo("validation.phone.hintCm");
        assertThat(cmRule.pattern().matcher("677012345").matches()).isTrue();
        assertThat(cmRule.pattern().matcher("+15551234567").matches()).isFalse();

        // A market with no rule still gets the permissive default.
        assertThat(registry.ruleFor("us").pattern().matcher("+15551234567").matches()).isTrue();
    }

    @Test
    void ruleFor_invalidConfiguredPattern_fallsBackToPermissive_ratherThanRejectingEverything() {
        PhoneValidationProperties props = new PhoneValidationProperties();
        PhoneValidationProperties.MarketRule broken = new PhoneValidationProperties.MarketRule();
        broken.setPattern("([unclosed");
        props.getMarkets().put("CM", broken);

        var rule = registryWith(props).ruleFor("cm");

        assertThat(rule.pattern().matcher("+15551234567").matches()).isTrue();
    }

    // ---------------------------------------------------------------- CamPhoneValidator

    @Test
    void camPhoneValidator_usesTheConfiguredDefault_whenRegistryIsPublished() {
        PhoneRuleRegistry.publishForTest(registryWith(new PhoneValidationProperties()));
        CamPhoneValidator validator = new CamPhoneValidator();
        ConstraintValidatorContext ctx = permissiveContext();

        assertThat(validator.isValid("+15551234567", ctx)).isTrue();
        assertThat(validator.isValid("77012345", ctx)).isTrue();
        assertThat(validator.isValid(null, ctx)).isTrue();
        assertThat(validator.isValid("   ", ctx)).isTrue();
        assertThat(validator.isValid("abc", ctx)).isFalse();
        assertThat(validator.isValid("1234", ctx)).isFalse();
    }

    @Test
    void camPhoneValidator_noRegistry_stillPermissiveViaBuiltinFallback() {
        CamPhoneValidator validator = new CamPhoneValidator();
        ConstraintValidatorContext ctx = permissiveContext();

        assertThat(validator.isValid("+441234567890", ctx)).isTrue();
        assertThat(validator.isValid("nope", ctx)).isFalse();
    }

    private static ConstraintValidatorContext permissiveContext() {
        ConstraintValidatorContext ctx = Mockito.mock(ConstraintValidatorContext.class);
        ConstraintValidatorContext.ConstraintViolationBuilder builder =
            Mockito.mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        Mockito.lenient().when(ctx.buildConstraintViolationWithTemplate(Mockito.anyString())).thenReturn(builder);
        return ctx;
    }
}
