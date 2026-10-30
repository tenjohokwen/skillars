package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6: the cancellation / no-show templates render with the data BookingEmailListener sends, in every
 * bundled locale, and say the right thing about the refund.
 */
class BookingCancellationEmailRenderingTest {

    private final EmailContentRenderer renderer = buildRenderer();

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

    private static Map<String, Object> data(String refundType, String refundAmount) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestedStartTime", "Oct 1, 2026, 10:00:00 AM");
        data.put("canonicalTimezone", "Europe/Berlin");
        data.put("refundType", refundType);
        data.put("refundAmount", refundAmount);
        data.put("bookingsUrl", "https://app.test/#/parent/bookings");
        data.put("coachDisplayName", "Coach Kim");
        data.put("cancelledBy", "COACH_UNAVAILABLE");
        data.put("disputeWindowDays", "14");
        data.put("paidWith", "CHARGE");
        data.put("sessionPrice", "40.00");
        return data;
    }

    private String render(EmailTemplate template, Map<String, Object> data) {
        Recipient recipient = new Recipient();
        recipient.setLangKey("en");
        return renderer.render(recipient, template, data).htmlBody();
    }

    @ParameterizedTest
    @EnumSource(value = EmailTemplate.class, names = {
        "BOOKING_CANCELLED_BY_PARENT", "BOOKING_CANCELLED_BY_COACH", "COACH_NO_SHOW", "PLAYER_NO_SHOW",
        "BOOKING_CANCELLED_BY_SKILLARS", "SESSION_FORFEITED_NO_SHOW", "SESSION_FORFEITED_LATE_CANCEL"})
    void rendersInEveryBundledLocale(EmailTemplate template) {
        for (String lang : List.of("en", "de", "fr")) {
            Recipient recipient = new Recipient();
            recipient.setLangKey(lang);
            EmailContentRenderer.Rendered rendered = renderer.render(recipient, template, data("CREDIT", "40.00"));

            assertThat(rendered.subject()).as("%s/%s subject", template, lang)
                .isNotBlank().doesNotContain("email.booking.");
            assertThat(rendered.htmlBody()).as("%s/%s body", template, lang)
                .contains("Oct 1, 2026").contains("Europe/Berlin").contains("https://app.test/#/parent/bookings");
        }
    }

    @Test
    void coachCancellation_statesTheRefundTheParentGot() {
        assertThat(render(EmailTemplate.BOOKING_CANCELLED_BY_COACH, data("CREDIT", "40.00")))
            .contains("fully refunded").contains("40.00").doesNotContain("nothing to refund");
        assertThat(render(EmailTemplate.BOOKING_CANCELLED_BY_COACH, data("PACK", "")))
            .contains("returned to your session pack").doesNotContain("fully refunded");
        assertThat(render(EmailTemplate.BOOKING_CANCELLED_BY_COACH, data("NONE", "")))
            .contains("nothing to refund").doesNotContain("fully refunded").doesNotContain("session pack");
    }

    @Test
    void coachNoShow_statesTheRefund() {
        assertThat(render(EmailTemplate.COACH_NO_SHOW, data("CREDIT", "25.50"))).contains("25.50");
        assertThat(render(EmailTemplate.COACH_NO_SHOW, data("PACK", ""))).contains("session pack");
    }

    /** PACK_PAUSED's body used to be misnamed (sessionPackPaused.html), so every pause notice failed. */
    @Test
    void packPaused_renders() {
        Map<String, Object> data = new HashMap<>();
        data.put("coachDisplayName", "Coach Kim");
        data.put("newExpiresAt", "Dec 1, 2026");
        data.put("cancelledBookingTimes", List.of("Oct 1, 2026, 10:00:00 AM"));
        assertThat(render(EmailTemplate.PACK_PAUSED, data)).contains("Coach Kim").contains("Dec 1, 2026")
            .contains("Oct 1, 2026, 10:00:00 AM");
    }

    @Test
    void adminCancellationNotice_namesTheCauseOnlyForASuspensionSweep() {
        Map<String, Object> sweep = data("CREDIT", "40.00");
        assertThat(render(EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE, sweep))
            .contains("the coach is unavailable").doesNotContain("Skillars team");

        Map<String, Object> single = data("CREDIT", "40.00");
        single.put("cancelledBy", "SKILLARS");
        assertThat(render(EmailTemplate.BOOKING_CANCELLED_COACH_UNAVAILABLE, single))
            .contains("cancelled by the Skillars team").doesNotContain("the coach is unavailable");
    }

    /** fix/forfeit-family-emails: the family is told the session is forfeited, why, and how to dispute. */
    @Test
    void playerNoShowForfeit_statesNoRefundAndTheDisputeWindow() {
        assertThat(render(EmailTemplate.SESSION_FORFEITED_NO_SHOW, data("NONE", "")))
            .contains("did not attend").contains("forfeits the session").contains("no refund is due")
            .contains("raise a dispute").contains("<strong>14</strong> days");
    }

    @Test
    void lateCancelForfeit_statesWhatIsNotRefunded() {
        Map<String, Object> card = data("NONE", "");
        assertThat(render(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL, card))
            .contains("less than 24 hours").contains("no refund is due").contains("40.00")
            .contains("raise a dispute").doesNotContain("session pack");

        Map<String, Object> pack = data("NONE", "");
        pack.put("paidWith", "PACK");
        pack.put("sessionPrice", "");
        assertThat(render(EmailTemplate.SESSION_FORFEITED_LATE_CANCEL, pack))
            .contains("not returned to the pack").doesNotContain("40.00");
    }
}
