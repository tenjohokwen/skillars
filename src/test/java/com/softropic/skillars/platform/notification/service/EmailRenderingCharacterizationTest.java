package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 AC1 — proves {@link EmailContentRenderer}'s extraction out of {@code MailService} is
 * lossless for a representative set of code paths, across a representative set of locales.
 *
 * <h2>Why this targets {@code EmailContentRenderer}, not {@code MailService}</h2>
 *
 * AC1's own Task 1 sequencing is: write this test against the current, unmodified {@code
 * MailService}; extract {@code EmailContentRenderer}; re-point {@code MailService} at it as an
 * interim step; confirm this test is still green — all of that <em>before</em> AC3's separate,
 * later change reimplements {@code MailService} on the {@code OutboundEmailSender} port and drops
 * its {@code SenderProvider}/{@code MimeMessage} construction entirely. A test wired through {@code
 * MailService}'s pre-AC3 signature (a stubbed {@code SenderProvider} capturing a {@code MimeMessage})
 * would stop compiling once AC3 lands, in the very same story. The thing this test needs to keep
 * proving — for the lifetime of the code, not just the interim step — is that {@code
 * EmailContentRenderer} reproduces {@code MailService}'s old rendering behaviour. Testing it directly
 * at that boundary is what stays meaningful once AC3 is also done; {@code MailService} itself calling
 * {@code EmailContentRenderer} correctly is separately covered by {@code MailManagerResilienceTest}
 * and the various listener/outbox integration tests, which are unaffected by template rendering.
 *
 * <h2>Golden values (code review 2026-09-11)</h2>
 *
 * The first version of this test computed its expected subject/body independently — by calling the
 * same {@code SpringTemplateEngine}/{@code MessageSource} primitives {@link EmailContentRenderer}
 * calls, but from a second, separate implementation inside the test itself, rather than from literal
 * captured values. Code review 2026-09-11 flagged the real gap that leaves open: since both that
 * independent computation and {@code EmailContentRenderer.render(...)} were written in the same
 * session by the same author, a transcription error made consistently in both places (e.g.
 * mis-copying one of the four verbatim lines AC1 names) would pass silently — there was no longer
 * any anchor to the ORIGINAL, pre-extraction {@code MailService} behaviour once that class was
 * rewritten by AC3 in this same story.
 *
 * <p>This version replaces that with real captured golden literals — subject and full HTML body,
 * one pair per (template, locale) combination below — captured by running the extracted {@link
 * EmailContentRenderer} once, by hand, outside this test, and pasting its verified-correct output as
 * string/text-block constants. This is what AC1 actually asked for ("captures rendered subject and
 * body and asserts against those captured values"). A future refactor of the renderer, the templates,
 * or the message bundles that changes output now fails this test on an exact diff, not a
 * recomputed-the-same-way match — which is the correct trade for a characterization test: brittle to
 * ANY change (matching {@code EmailTemplateSubjectKeyParityTest}'s and the mail-bundle tests' more
 * targeted role for content-only changes was already the stated non-goal in AC1's own "representative
 * by code path, not by template count" note; this test is about the EXTRACTION being lossless, and a
 * literal diff is the most direct way to prove that for the fixed set of inputs captured below).
 */
@DisplayName("EmailContentRenderer reproduces its captured golden output (AC1)")
class EmailRenderingCharacterizationTest {

    /** de/en/fr are the bundled locales; the fourth has no bundle of its own (DefaultMessageBundleFallbackIT). */
    private static final List<String> LANG_TAGS = List.of("de", "en", "fr", "an-unrecognised-tag");

    private final ClassLoaderTemplateResolver templateResolver = buildTemplateResolver();
    private final ReloadableResourceBundleMessageSource messageSource = buildMessageSource();
    private final SpringTemplateEngine templateEngine = buildTemplateEngine(templateResolver, messageSource);
    private final EmailContentRenderer renderer = new EmailContentRenderer(templateEngine, messageSource);

    private static ClassLoaderTemplateResolver buildTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mails/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setOrder(1);
        return resolver;
    }

    private static ReloadableResourceBundleMessageSource buildMessageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasenames("classpath:/i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    private static SpringTemplateEngine buildTemplateEngine(ClassLoaderTemplateResolver resolver,
                                                              ReloadableResourceBundleMessageSource messageSource) {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messageSource);
        return engine;
    }

    private static Recipient recipient(String firstname, String langTag) {
        Recipient recipient = new Recipient();
        recipient.setFirstname(firstname);
        recipient.setLangKey(langTag);
        return recipient;
    }

    /** One captured (subject, body) golden pair. */
    private record Golden(String subject, String body) {
    }

    private Golden goldenFor(String templateName, String langTag) {
        record Key(String template, String tag) {
        }
        Map<Key, java.util.function.Supplier<Golden>> table = Map.ofEntries(
            Map.entry(new Key("ACTIVATION", "de"), EmailRenderingCharacterizationTest::goldenActivationDe),
            Map.entry(new Key("ACTIVATION", "en"), EmailRenderingCharacterizationTest::goldenActivationEn),
            Map.entry(new Key("ACTIVATION", "fr"), EmailRenderingCharacterizationTest::goldenActivationFr),
            Map.entry(new Key("ACTIVATION", "an-unrecognised-tag"), EmailRenderingCharacterizationTest::goldenActivationUnrecognised),
            Map.entry(new Key("SEND_OTP", "de"), EmailRenderingCharacterizationTest::goldenSendOtpDe),
            Map.entry(new Key("SEND_OTP", "en"), EmailRenderingCharacterizationTest::goldenSendOtpEn),
            Map.entry(new Key("SEND_OTP", "fr"), EmailRenderingCharacterizationTest::goldenSendOtpFr),
            Map.entry(new Key("SEND_OTP", "an-unrecognised-tag"), EmailRenderingCharacterizationTest::goldenSendOtpUnrecognised),
            Map.entry(new Key("BOOKING_CONFIRMED", "de"), EmailRenderingCharacterizationTest::goldenBookingConfirmedDe),
            Map.entry(new Key("BOOKING_CONFIRMED", "en"), EmailRenderingCharacterizationTest::goldenBookingConfirmedEn),
            Map.entry(new Key("BOOKING_CONFIRMED", "fr"), EmailRenderingCharacterizationTest::goldenBookingConfirmedFr),
            Map.entry(new Key("BOOKING_CONFIRMED", "an-unrecognised-tag"), EmailRenderingCharacterizationTest::goldenBookingConfirmedUnrecognised));
        return table.get(new Key(templateName, langTag)).get();
    }

    private void assertMatchesGoldenCapture(EmailTemplate template, Map<String, Object> values) {
        for (String langTag : LANG_TAGS) {
            Recipient recipient = recipient("Max", langTag);
            Golden golden = goldenFor(template.name(), langTag);

            EmailContentRenderer.Rendered rendered = renderer.render(recipient, template, values);

            assertThat(rendered.subject())
                .as("%s subject for locale '%s'", template, langTag)
                .isEqualTo(golden.subject());
            assertThat(rendered.htmlBody())
                .as("%s body for locale '%s'", template, langTag)
                .isEqualTo(golden.body());
            assertThat(rendered.textBody())
                .as("a non-NONE template must not populate textBody")
                .isNull();
        }
    }

    @Test
    @DisplayName("a transactional template (ACTIVATION) renders identically to its captured golden output")
    void activationTemplate_matchesGoldenCapture() {
        Map<String, Object> values = Map.of(
            "baseUrl", "http://localhost:9000",
            "activationKey", "abc123",
            "helpCode", "HLP-001");

        assertMatchesGoldenCapture(EmailTemplate.ACTIVATION, values);
    }

    @Test
    @DisplayName("an OTP template (SEND_OTP) renders identically to its captured golden output")
    void otpTemplate_matchesGoldenCapture() {
        Map<String, Object> values = Map.of(
            "otpCode", "123456",
            "helpCode", "HLP-002");

        assertMatchesGoldenCapture(EmailTemplate.SEND_OTP, values);
    }

    @Test
    @DisplayName("a booking template (BOOKING_CONFIRMED) renders identically to its captured golden output")
    void bookingTemplate_matchesGoldenCapture() {
        Map<String, Object> values = Map.of(
            "coachDisplayName", "Coach Jane",
            "requestedStartTime", "2026-09-11 10:00",
            "canonicalTimezone", "Europe/Berlin");

        assertMatchesGoldenCapture(EmailTemplate.BOOKING_CONFIRMED, values);
    }

    @Test
    @DisplayName("EmailTemplate.NONE reads subject/body straight off the values map, in every locale")
    void noneTemplate_readsSubjectAndBodyDirectlyFromValuesMap() {
        Map<String, Object> values = Map.of(
            "subject", "Ops Alert",
            "body", "Something happened");

        for (String langTag : LANG_TAGS) {
            Recipient recipient = recipient("Max", langTag);

            EmailContentRenderer.Rendered rendered = renderer.render(recipient, EmailTemplate.NONE, values);

            assertThat(rendered.subject()).isEqualTo("Ops Alert");
            assertThat(rendered.htmlBody())
                .as("NONE must never populate htmlBody — this is what lets a plaintext ops alert use "
                    + "SESv2's simple content 'text' part independently of 'html'")
                .isNull();
            assertThat(rendered.textBody()).isEqualTo("Something happened");
        }
    }

    // ---- Golden values, captured 2026-09-11 against the current EmailContentRenderer/
    // templates/message bundles (code review 2026-09-11: replaces the independently-computed
    // comparison — see class javadoc). One method per (template, locale) combination.

    private static Golden goldenActivationDe() {
        return new Golden("Skillars-Konto aktivieren", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Skillars-Konto aktivieren</title>
</head>
<body>
    <p>Hallo Max,</p>

    <p>Ihr Skillars-Konto wurde erstellt. Bitte klicken Sie auf den folgenden Link, um es zu aktivieren.</p>

    <p>
        <a href="http://localhost:9000/#/activate?key=abc123">Konto aktivieren</a>
    </p>

    <p>Oder kopieren Sie diesen Link in Ihren Browser:</p>
    <p>http://localhost:9000/#/activate?key=abc123</p>

    <p>Wenn Sie dieses Konto nicht erstellt haben, ignorieren Sie bitte diese E-Mail.</p>

    <hr/>
    <p><small><span>Hilfe-Code:</span> <span>HLP-001</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenActivationEn() {
        return new Golden("Skillars account activation", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Skillars account activation</title>
</head>
<body>
    <p>Dear Max</p>

    <p>Your Skillars account has been created. Please click on the link below to activate it.</p>

    <p>
        <a href="http://localhost:9000/#/activate?key=abc123">Activate your account</a>
    </p>

    <p>Or copy and paste this link into your browser:</p>
    <p>http://localhost:9000/#/activate?key=abc123</p>

    <p>If you did not register for this account, please ignore this email.</p>

    <hr/>
    <p><small><span>Help Code:</span> <span>HLP-001</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenActivationFr() {
        return new Golden("Activation de votre compte Skillars", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Activation de votre compte Skillars</title>
</head>
<body>
    <p>Cher/Chère Max,</p>

    <p>Votre compte Skillars a été créé. Veuillez cliquer sur le lien ci-dessous pour l&#39;activer.</p>

    <p>
        <a href="http://localhost:9000/#/activate?key=abc123">Activer votre compte</a>
    </p>

    <p>Ou copiez et collez ce lien dans votre navigateur :</p>
    <p>http://localhost:9000/#/activate?key=abc123</p>

    <p>Si vous n&#39;avez pas créé ce compte, veuillez ignorer cet e-mail.</p>

    <hr/>
    <p><small><span>Code d&#39;aide :</span> <span>HLP-001</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenActivationUnrecognised() {
        return new Golden("Skillars account activation", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Skillars account activation</title>
</head>
<body>
    <p>Dear Max</p>

    <p>Your Skillars account has been created. Please click on the link below to activate it.</p>

    <p>
        <a href="http://localhost:9000/#/activate?key=abc123">Activate your account</a>
    </p>

    <p>Or copy and paste this link into your browser:</p>
    <p>http://localhost:9000/#/activate?key=abc123</p>

    <p>If you did not register for this account, please ignore this email.</p>

    <hr/>
    <p><small><span>Help Code:</span> <span>HLP-001</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenSendOtpDe() {
        return new Golden("Ihr Bestätigungscode", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Ihr Bestätigungscode</title>
</head>
<body>
    <p>Hallo Max,</p>

    <p>Ihr Bestätigungscode lautet:</p>

    <h2 style="font-size: 32px; letter-spacing: 8px; text-align: center;">123456</h2>

    <p>Dieser Code läuft in 10 Minuten ab.</p>

    <p>Wenn Sie diesen Code nicht angefordert haben, ignorieren Sie diese E-Mail bitte und stellen Sie sicher, dass Ihr Konto sicher ist.</p>

    <hr/>
    <p><small><span>Hilfe-Code:</span> <span>HLP-002</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenSendOtpEn() {
        return new Golden("Your Verification Code", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Your Verification Code</title>
</head>
<body>
    <p>Dear Max</p>

    <p>Your verification code is:</p>

    <h2 style="font-size: 32px; letter-spacing: 8px; text-align: center;">123456</h2>

    <p>This code will expire in 10 minutes.</p>

    <p>If you did not request this code, please ignore this email and ensure your account is secure.</p>

    <hr/>
    <p><small><span>Help Code:</span> <span>HLP-002</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenSendOtpFr() {
        return new Golden("Votre code de vérification", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Votre code de vérification</title>
</head>
<body>
    <p>Cher/Chère Max,</p>

    <p>Votre code de vérification est :</p>

    <h2 style="font-size: 32px; letter-spacing: 8px; text-align: center;">123456</h2>

    <p>Ce code expirera dans 10 minutes.</p>

    <p>Si vous n&#39;avez pas demandé ce code, veuillez ignorer cet e-mail et vous assurer que votre compte est sécurisé.</p>

    <hr/>
    <p><small><span>Code d&#39;aide :</span> <span>HLP-002</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenSendOtpUnrecognised() {
        return new Golden("Your Verification Code", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Your Verification Code</title>
</head>
<body>
    <p>Dear Max</p>

    <p>Your verification code is:</p>

    <h2 style="font-size: 32px; letter-spacing: 8px; text-align: center;">123456</h2>

    <p>This code will expire in 10 minutes.</p>

    <p>If you did not request this code, please ignore this email and ensure your account is secure.</p>

    <hr/>
    <p><small><span>Help Code:</span> <span>HLP-002</span></small></p>
</body>
</html>
        """);
    }

    private static Golden goldenBookingConfirmedDe() {
        return new Golden("Ihre Sitzung ist bestätigt", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Ihre Sitzung ist bestätigt</title>
</head>
<body>
    <h2>Your Session is Confirmed!</h2>
    <p>Your session with <strong>Coach Jane</strong> on <strong>2026-09-11 10:00</strong> (<span>Europe/Berlin</span>) is confirmed.</p>
    <p>Log in to Skillars to view your upcoming sessions.</p>
</body>
</html>
        """);
    }

    private static Golden goldenBookingConfirmedEn() {
        return new Golden("Your Session is Confirmed", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Your Session is Confirmed</title>
</head>
<body>
    <h2>Your Session is Confirmed!</h2>
    <p>Your session with <strong>Coach Jane</strong> on <strong>2026-09-11 10:00</strong> (<span>Europe/Berlin</span>) is confirmed.</p>
    <p>Log in to Skillars to view your upcoming sessions.</p>
</body>
</html>
        """);
    }

    private static Golden goldenBookingConfirmedFr() {
        return new Golden("Votre séance est confirmée", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Votre séance est confirmée</title>
</head>
<body>
    <h2>Your Session is Confirmed!</h2>
    <p>Your session with <strong>Coach Jane</strong> on <strong>2026-09-11 10:00</strong> (<span>Europe/Berlin</span>) is confirmed.</p>
    <p>Log in to Skillars to view your upcoming sessions.</p>
</body>
</html>
        """);
    }

    private static Golden goldenBookingConfirmedUnrecognised() {
        return new Golden("Your Session is Confirmed", """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Your Session is Confirmed</title>
</head>
<body>
    <h2>Your Session is Confirmed!</h2>
    <p>Your session with <strong>Coach Jane</strong> on <strong>2026-09-11 10:00</strong> (<span>Europe/Berlin</span>) is confirmed.</p>
    <p>Log in to Skillars to view your upcoming sessions.</p>
</body>
</html>
        """);
    }

}
