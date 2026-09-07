package com.softropic.skillars.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-99 AC3, part 1 — every message code that {@code src/main} resolves as a
 * <em>string literal</em> must exist in the base {@code messages.properties} bundle.
 *
 * <h2>Why base, specifically</h2>
 *
 * <p>{@code MvcConfig.messageSource} sets {@code setFallbackToSystemLocale(false)}, so
 * {@code messages.properties} is the only fallback when a key is missing from the resolved locale's
 * bundle. A code present in Java but absent from base then threw {@code NoSuchMessageException} — a
 * request-time 500 — for any client whose locale is not de/fr/en. AC3 also adds
 * {@code setUseCodeAsDefaultMessage(true)} + {@code WarnOnMissingMessageSource} as the runtime net;
 * this test is the build-time gate that keeps the <em>static</em> codes from regressing in the first
 * place.
 *
 * <h2>Scope — stated honestly</h2>
 *
 * <p>This codebase resolves almost every message code <strong>dynamically</strong>: from
 * {@code ApplicationException.getErrorCode()}, from {@code ConstraintViolation.getMessageTemplate()},
 * from a {@code msgKey} variable, or from an {@code EmailTemplate} enum constant. A literal-string
 * scan cannot see any of those, and this test does not pretend to. It covers the two literal forms
 * that <em>do</em> occur —
 * <ol>
 *   <li>a dotted string literal passed as the first arg to {@code .getMessage(...)}, and</li>
 *   <li>every {@code EmailTemplate.*.subjectKey()} (the enum is the literal source for the
 *       no-default {@code getMessage(key, args, locale)} calls in the registration email listeners
 *       and {@code MailService})</li>
 * </ol>
 * — and documents that dynamically-built keys are out of its reach, which is exactly what
 * {@code WarnOnMissingMessageSource} exists to degrade gracefully.
 *
 * <p>{@code *Test}, no Spring context — runs in the {@code test} phase.
 */
@DisplayName("Every string-literal message code resolves in base messages.properties")
class MessageCodeBaseBundleCompletenessTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path BASE_BUNDLE = Path.of("src", "main", "resources", "i18n", "messages.properties");
    private static final Path EMAIL_TEMPLATE_ENUM = MAIN_JAVA.resolve(
        "com/softropic/skillars/platform/notification/contract/EmailTemplate.java");

    /** A dotted code literal as the first argument to any {@code .getMessage(...)} overload. */
    private static final Pattern GET_MESSAGE_LITERAL =
        Pattern.compile("\\.getMessage\\(\\s*\"([a-z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)\"");
    /** A bean-validation {@code message = "{some.key}"} template. */
    private static final Pattern VALIDATION_TEMPLATE =
        Pattern.compile("message\\s*=\\s*\"\\{([a-z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)\\}\"");
    /** The value inside an {@code EmailTemplate} enum constant: {@code XYZ("email.some.title")}. */
    private static final Pattern ENUM_KEY = Pattern.compile("\\(\\s*\"([a-zA-Z][a-zA-Z0-9_.]*)\"\\s*\\)");

    @Test
    void everyLiteralMessageCode_isPresentInBaseBundle() throws IOException {
        Set<String> baseKeys = baseBundleKeys();
        Set<String> codes = new LinkedHashSet<>();

        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            files.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    String body = read(p);
                    for (Matcher m = GET_MESSAGE_LITERAL.matcher(body); m.find(); ) {
                        codes.add(m.group(1));
                    }
                    for (Matcher m = VALIDATION_TEMPLATE.matcher(body); m.find(); ) {
                        codes.add(m.group(1));
                    }
                });
        }

        String enumBody = read(EMAIL_TEMPLATE_ENUM);
        for (Matcher m = ENUM_KEY.matcher(enumBody); m.find(); ) {
            if (!m.group(1).isEmpty()) {
                codes.add(m.group(1));
            }
        }

        List<String> missing = codes.stream().filter(c -> !baseKeys.contains(c)).sorted().toList();
        assertThat(missing)
            .as("""
                These message codes are resolved as string literals in src/main but are missing from \
                src/main/resources/i18n/messages.properties — a client on a locale outside de/fr/en \
                would get the code shown (skillars-deferred-99 AC3's WarnOnMissingMessageSource) \
                instead of real copy. Add them to the base bundle (and messages_en/de/fr for parity).""")
            .isEmpty();
    }

    private static Set<String> baseBundleKeys() throws IOException {
        Properties p = new Properties();
        try (BufferedReader r = Files.newBufferedReader(BASE_BUNDLE, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new LinkedHashSet<>(p.stringPropertyNames());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ""; // unreadable file is not evidence of a missing code
        }
    }
}
