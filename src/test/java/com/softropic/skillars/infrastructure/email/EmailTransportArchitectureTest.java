package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.2 AC5 — containment invariants for the email transport packages, enforced from the
 * moment {@code infrastructure/email/smtp} exists (per §3.4, "so the invariants hold from the moment
 * the package exists"). This is what keeps Phase 6's eventual SMTP removal a one-commit job.
 *
 * <p>This codebase has <strong>no ArchUnit or ClassGraph dependency</strong> (confirmed: not in
 * {@code pom.xml}, no prior use anywhere in {@code src/test}) — this test deliberately does not add
 * one. It follows the established pattern this codebase already uses for exactly this kind of rule
 * ({@code MigrationConventionLintTest}/{@code MigrationLint}, {@code NoHardcodedSenderTest}): a plain
 * JUnit test that walks {@code .java} source text with {@link Files#walk} and regexes/{@code
 * String.contains}, no bytecode inspection needed.
 *
 * <p><strong>Scope: {@code src/main/java} only.</strong> This is a production-code isolation rule.
 * {@code src/test/java} is deliberately out of scope — {@code TransportWiringTest} and {@code
 * SmtpHealthIndicatorTest}/{@code SmtpEmailSenderTest}/{@code SmtpErrorClassifierTest} all
 * legitimately reference {@code SmtpEmailSender}/{@code SmtpProperties}/{@code ProviderConfig}/
 * {@code MailSenderProvider}/{@code SmtpHealthIndicator} by simple name from outside the {@code smtp}
 * package to prove wiring (AC8) — the invariant this test enforces is "no other PRODUCTION module
 * couples itself to SMTP internals", not "no test may name the class it is testing".
 */
@DisplayName("Email transport packages are contained (no leakage of SMTP/SES internals)")
class EmailTransportArchitectureTest {

    private static final Path MAIN_SOURCE = Path.of("src", "main", "java");

    /**
     * Code review 2026-09-11: matching only {@code import} lines lets a fully-qualified inline
     * reference (e.g. {@code jakarta.mail.internet.InternetAddress x = ...}, with no import at all)
     * evade rules 1/3/4 entirely — not hypothetical, since the moved {@code SmtpHealthIndicator}
     * itself already uses inline FQNs for other types as house style (e.g. {@code
     * java.net.InetAddress.getLocalHost()}). Rules 1/3/4 therefore scan the whole file's text for the
     * package prefix as its own token, matching a use anywhere — import, inline reference, or
     * javadoc/comment prose. The word-boundary anchor means a prefix is only flagged where it stands
     * on its own (not as part of a longer, unrelated identifier), and this deliberately catches prose
     * the same way rule 2 already does — see that rule's own experience of having to reword doc
     * comments that named a contained class, which is the correct outcome for a containment
     * invariant meant to keep Phase 6's removal a clean one-commit job.
     */
    private static Pattern fqcnToken(String prefix) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(prefix) + "(?![\\w])");
    }

    private static final Pattern JAKARTA_MAIL_TOKEN = fqcnToken("jakarta.mail");
    private static final Pattern JAVAX_MAIL_TOKEN = fqcnToken("javax.mail");
    private static final Pattern SPRING_MAIL_TOKEN = fqcnToken("org.springframework.mail");
    private static final Pattern SES_SDK_TOKEN = fqcnToken("software.amazon.awssdk.services.sesv2");

    /** The one named carve-out from rule 1 — per the Phase 1 code-review decision (§3.4). */
    private static final String EMAIL_ADDRESS_PARSER = normalized(
        Path.of("com", "softropic", "skillars", "infrastructure", "email", "EmailAddressParser.java"));

    private static final List<String> SMTP_ONLY_CLASS_NAMES = List.of(
        "SmtpEmailSender", "SmtpProperties", "ProviderConfig", "MailSenderProvider", "SmtpHealthIndicator");

    private record JavaFile(Path path, String relativePath, String content) {
    }

    private static List<JavaFile> allMainSourceFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE)) {
            List<JavaFile> files = new ArrayList<>();
            for (Path p : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                files.add(new JavaFile(p, normalized(MAIN_SOURCE.relativize(p)), Files.readString(p)));
            }
            return files;
        }
    }

    private static String normalized(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static boolean isUnderSmtpPackage(String relativePath) {
        return relativePath.contains("infrastructure/email/smtp/");
    }

    private static boolean isUnderSesPackage(String relativePath) {
        return relativePath.contains("infrastructure/ses/");
    }

    private static boolean isUnderPlatform(String relativePath) {
        return relativePath.contains("/platform/") || relativePath.startsWith("platform/");
    }

    private static boolean containsAny(String content, Pattern... tokens) {
        for (Pattern token : tokens) {
            if (token.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scoped to the SES v2 SDK specifically ({@code software.amazon.awssdk.services.sesv2}), not the
     * whole {@code software.amazon.awssdk} tree: {@code infrastructure.blobstore} and {@code
     * platform.filestorage} legitimately import the (unrelated) S3 SDK, and generic packages like
     * {@code software.amazon.awssdk.core}/{@code .regions}/{@code .auth.credentials} are shared AWS
     * SDK infrastructure, not SES-specific — restricting those would false-positive on that
     * pre-existing, unrelated S3 usage. The containment invariant this rule protects is "SES-specific
     * SDK usage stays in infrastructure.ses", which {@code sesv2} alone identifies.
     */
    private static boolean referencesSesSdk(String content) {
        return SES_SDK_TOKEN.matcher(content).find();
    }

    private static boolean referencesMailApi(String content) {
        return containsAny(content, JAKARTA_MAIL_TOKEN, JAVAX_MAIL_TOKEN, SPRING_MAIL_TOKEN);
    }

    @Test
    @DisplayName("rule 1: only infrastructure/email/smtp (and the EmailAddressParser carve-out) may reference a mail API")
    void onlySmtpPackageReferencesMailApis() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : allMainSourceFiles()) {
            if (isUnderSmtpPackage(file.relativePath()) || file.relativePath().equals(EMAIL_ADDRESS_PARSER)) {
                continue;
            }
            if (referencesMailApi(file.content())) {
                offenders.add(file.relativePath());
            }
        }
        assertThat(offenders)
            .as("only infrastructure/email/smtp (and the hardcoded EmailAddressParser carve-out) may "
                + "reference jakarta.mail/javax.mail/org.springframework.mail: %s", offenders)
            .isEmpty();
    }

    @Test
    @DisplayName("rule 2: no file outside infrastructure/email/smtp references its internal classes by simple name")
    void smtpInternalsAreNotReferencedFromOutsideThePackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : allMainSourceFiles()) {
            if (isUnderSmtpPackage(file.relativePath())) {
                continue;
            }
            for (String className : SMTP_ONLY_CLASS_NAMES) {
                if (Pattern.compile("\\b" + className + "\\b").matcher(file.content()).find()) {
                    offenders.add(file.relativePath() + " references " + className);
                }
            }
        }
        assertThat(offenders)
            .as("SMTP internals must stay contained to infrastructure/email/smtp: %s", offenders)
            .isEmpty();
    }

    @Test
    @DisplayName("rule 3: only infrastructure/ses may reference the SES v2 SDK")
    void onlySesPackageReferencesTheSesSdk() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : allMainSourceFiles()) {
            if (isUnderSesPackage(file.relativePath())) {
                continue;
            }
            if (referencesSesSdk(file.content())) {
                offenders.add(file.relativePath());
            }
        }
        assertThat(offenders)
            .as("only infrastructure/ses may reference software.amazon.awssdk.services.sesv2: %s", offenders)
            .isEmpty();
    }

    @Test
    @DisplayName("rule 4: no platform/** class references the SES SDK or a mail API directly")
    void platformModulesStayTransportAgnostic() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (JavaFile file : allMainSourceFiles()) {
            if (!isUnderPlatform(file.relativePath())) {
                continue;
            }
            if (referencesSesSdk(file.content()) || referencesMailApi(file.content())) {
                offenders.add(file.relativePath());
            }
        }
        assertThat(offenders)
            .as("platform/** must stay transport-agnostic — no SES SDK or mail API reference: %s", offenders)
            .isEmpty();
    }
}
