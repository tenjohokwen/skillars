package com.softropic.skillars.platform.notification.service;

import com.google.common.base.CaseFormat;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C6 guard: every {@link EmailTemplate} has its Thymeleaf body at {@code mails/<lowerCamelName>.html}
 * — the exact name {@code EmailContentRenderer} asks the engine for.
 *
 * <p>Four booking templates (BOOKING_CANCELLED_BY_PARENT, BOOKING_CANCELLED_BY_COACH, COACH_NO_SHOW,
 * PLAYER_NO_SHOW) shipped with a subject key but no body, and PACK_PAUSED's body was misnamed
 * ({@code sessionPackPaused.html}, not {@code packPaused.html}). Each send threw Thymeleaf's
 * {@code TemplateInputException}, which {@code MailManager.isRetryable} treats as retryable: the envelope
 * went FAILED, {@code EmailRetryScheduler} re-tried it every minute until its delivery deadline, and
 * then it was dropped as DEADLINE_EXPIRED. Nobody was ever told their session was cancelled. Nothing in
 * the build noticed, because the subject-key parity test only looks at the i18n bundles.
 *
 * <p>Exemptions are explicit and pinned in both directions — an exempt constant that gains a template
 * must be removed from the list.
 */
@DisplayName("Every EmailTemplate has an HTML template file")
class EmailTemplateFileParityTest {

    private static final Path MAILS = Path.of("src", "main", "resources", "mails");

    /** Constants that legitimately have no file, with the reason. */
    private static final Map<EmailTemplate, String> EXEMPT = Map.of(
        // Plain-text ops alerts: EmailContentRenderer takes subject/body from the data map, no template.
        EmailTemplate.NONE, "rendered without a template",
        // No producer sends these (NotificationCatalog / AccountManagementFacade, which sends an email change
        // as PROFILE_CHANGE); give one a body together with its producer.
        EmailTemplate.COACH_VISIBILITY_REDUCED, "no producer",
        EmailTemplate.EMAIL_CHANGE, "no producer",
        // V184 (#328): the minor-video approval gate was removed; the constants stay @Deprecated so old
        // envelope rows still map, but nothing sends them and their bodies were deleted.
        EmailTemplate.VIDEO_APPROVAL_REQUESTED, "deprecated, no producer",
        EmailTemplate.VIDEO_APPROVAL_APPROVED, "deprecated, no producer",
        EmailTemplate.VIDEO_APPROVAL_REJECTED, "deprecated, no producer");

    static String fileName(EmailTemplate template) {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, template.name()) + ".html";
    }

    @Test
    void everySentTemplate_hasAnHtmlFile() {
        var missing = Arrays.stream(EmailTemplate.values())
            .filter(t -> !EXEMPT.containsKey(t))
            .filter(t -> !Files.isRegularFile(MAILS.resolve(fileName(t))))
            .map(t -> t.name() + " -> mails/" + fileName(t))
            .sorted()
            .toList();

        assertThat(missing)
            .as("an EmailTemplate without a mails/*.html body fails every send (retried until it expires); "
                + "add the template in the same style as its siblings")
            .isEmpty();
    }

    @Test
    void exemptTemplates_stillHaveNoFile() {
        var stale = EXEMPT.keySet().stream()
            .filter(t -> Files.isRegularFile(MAILS.resolve(fileName(t))))
            .map(Enum::name)
            .toList();

        assertThat(stale).as("exempt constant now has a template — remove it from EXEMPT").isEmpty();
    }
}
