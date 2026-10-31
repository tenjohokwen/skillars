package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S-09: the safeguarding expiry reminder renders in every bundled locale with the data the listener sends. */
class CoachSafeguardingEmailRenderingTest {

    private static EmailContentRenderer buildRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mails/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasenames("classpath:/i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(ms);
        return new EmailContentRenderer(engine, ms);
    }

    @Test
    void rendersInEveryBundledLocale_withTheDateAndTheLink_andNothingAccusatory() {
        EmailContentRenderer renderer = buildRenderer();
        for (String lang : List.of("en", "de", "fr")) {
            Recipient recipient = new Recipient();
            recipient.setLangKey(lang);
            EmailContentRenderer.Rendered rendered = renderer.render(recipient,
                EmailTemplate.COACH_SAFEGUARDING_EXPIRING,
                Map.of("coachDisplayName", "Coach Kim", "expiresAt", "2031-03-15",
                    "safeguardingUrl", "https://app.test/#/coach/safeguarding"));

            assertThat(rendered.subject()).as("%s subject", lang)
                .isNotBlank().doesNotContain("email.coach.safeguarding");
            assertThat(rendered.htmlBody()).as("%s body", lang)
                .contains("Coach Kim").contains("2031-03-15").contains("https://app.test/#/coach/safeguarding");
        }
    }
}
