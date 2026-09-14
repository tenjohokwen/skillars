package com.softropic.skillars.infrastructure.ses;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 Task 9 (§7.2 item 9) — guards against reintroducing a hardcoded {@code
 * app.ses.from-address} literal into a shipped {@code application*.yaml}.
 *
 * <p>The rule that matters is "no real domain, and no non-empty default in uat/prod" — not "no
 * default anywhere". {@code application-dev.yaml}'s own {@code ${APP_SES_FROM_ADDRESS:dev@localhost}}
 * <strong>is</strong> a literal default in a shipped YAML by design (AC12: dev has no real AWS
 * account, so a placeholder-with-default is the point). A naive "no literal at all" version of this
 * test fails against this story's own, correct, dev configuration.
 *
 * <p>skillars-deferred-110 AC1: the original single {@link #FROM_ADDRESS_VALUE} pattern only matched
 * the placeholder form — a bare literal {@code from-address: noreply@skillars.com} did not match it
 * at all, so {@code matcher.find()} finding nothing was silently correct-looking even when a
 * hardcoded literal sender was present. Fixed by matching per line, anchored to the key at line
 * start ({@link #FROM_ADDRESS_KEY}), then requiring the captured value to satisfy the placeholder
 * pattern ({@link #FROM_ADDRESS_VALUE}) rather than by scanning the whole file for the placeholder
 * form and treating "not found" as "must be a literal".
 *
 * <p>Code review 2026-09-14 (patch): a whole-file, unanchored, no-comment-stripping regex had two
 * further gaps, both closed by matching line-by-line instead: (1) with no line anchor, {@code \s*}
 * before the value happily consumes a newline, so a {@code from-address:} key with nothing after it
 * on its own line could match the start of a completely unrelated, later YAML key as if it were the
 * "value" — a confusing failure naming a sender that does not exist (confirmed by execution against
 * a constructed case). (2) with no comment stripping, a commented-out example line (a bare {@code #
 * from-address: ...} in prose) would trip the same false failure. {@link #FROM_ADDRESS_KEY} is
 * anchored to {@code ^\s*from-address:} per line, which structurally excludes both: a
 * same-line-only match can never reach a different YAML entry, and a line where {@code #} precedes
 * {@code from-address:} never matches the key anchor at all.
 */
class NoHardcodedSenderTest {

    /** Matches only a real {@code from-address:} YAML key at the start of a line (whitespace-indented
     * is fine; a preceding {@code #} is not, so a commented-out line never matches). Captures the rest
     * of that same line, trimmed, as the value — never anything from a different line. */
    private static final Pattern FROM_ADDRESS_KEY = Pattern.compile("^\\s*from-address:\\s*(.*?)\\s*$");

    /** The full accepted value shape for a {@code from-address:} line: the placeholder form, with an
     * optional trailing same-line comment. Group 2 is the default (empty string if none). */
    private static final Pattern FROM_ADDRESS_VALUE = Pattern.compile(
        "^\"?\\$\\{APP_SES_FROM_ADDRESS(:([^}]*))?}\"?(\\s*#.*)?$");

    @Test
    void noRealDomainLiteral_andNoNonEmptyDefaultOutsideDev() throws IOException {
        record Case(String file, String allowedDefault) {
        }
        List<Case> cases = List.of(
            new Case("src/main/resources/application-dev.yaml", "dev@localhost"),
            new Case("src/main/resources/application-uat.yaml", ""),
            new Case("src/main/resources/application-prod.yaml", ""));

        for (Case c : cases) {
            List<String> lines = Files.readAllLines(Path.of(c.file()));
            boolean foundAny = false;

            for (String line : lines) {
                Matcher keyMatcher = FROM_ADDRESS_KEY.matcher(line);
                if (!keyMatcher.matches()) {
                    continue;
                }
                foundAny = true;
                String value = keyMatcher.group(1);

                Matcher valueMatcher = FROM_ADDRESS_VALUE.matcher(value);
                assertThat(valueMatcher.matches())
                    .as(c.file() + " must set app.ses.from-address via the "
                        + "${APP_SES_FROM_ADDRESS:...} placeholder form, not a bare literal — found: '"
                        + line + "'")
                    .isTrue();

                String defaultValue = valueMatcher.group(2) == null ? "" : valueMatcher.group(2);
                assertThat(defaultValue)
                    .as(c.file() + " app.ses.from-address default must be exactly '" + c.allowedDefault()
                        + "' — no other literal domain is permitted here")
                    .isEqualTo(c.allowedDefault());
            }

            assertThat(foundAny)
                .as(c.file() + " must set app.ses.from-address via the "
                    + "${APP_SES_FROM_ADDRESS:...} placeholder form, not a bare literal")
                .isTrue();
        }
    }

    @Test
    void noAppEmailTransportEnvVarInComposeFiles() throws IOException {
        for (String file : List.of("docker-compose.yml", "docker-compose.uat.yml",
                                    "docker-compose.uat-hostwinds.yml", "docker-compose.local.yml")) {
            assertThat(Files.readString(Path.of(file)))
                .as("APP_EMAIL_TRANSPORT in " + file + " would override app.email.transport in "
                    + "every profile simultaneously — see requirements/ses-email-consolidation.md#4.5 "
                    + "and EmailTransportPropertyValidator's javadoc")
                .doesNotContain("APP_EMAIL_TRANSPORT");
        }
    }
}
