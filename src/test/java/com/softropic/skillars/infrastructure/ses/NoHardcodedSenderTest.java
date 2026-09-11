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
 */
class NoHardcodedSenderTest {

    private static final Pattern FROM_ADDRESS_LINE = Pattern.compile(
        "from-address:\\s*\"?\\$\\{APP_SES_FROM_ADDRESS(:([^}]*))?}\"?");

    @Test
    void noRealDomainLiteral_andNoNonEmptyDefaultOutsideDev() throws IOException {
        record Case(String file, String allowedDefault) {
        }
        List<Case> cases = List.of(
            new Case("src/main/resources/application-dev.yaml", "dev@localhost"),
            new Case("src/main/resources/application-uat.yaml", ""),
            new Case("src/main/resources/application-prod.yaml", ""));

        for (Case c : cases) {
            String content = Files.readString(Path.of(c.file()));
            Matcher matcher = FROM_ADDRESS_LINE.matcher(content);

            assertThat(matcher.find())
                .as(c.file() + " must set app.ses.from-address via the "
                    + "${APP_SES_FROM_ADDRESS:...} placeholder form, not a bare literal")
                .isTrue();

            String defaultValue = matcher.group(2) == null ? "" : matcher.group(2);
            assertThat(defaultValue)
                .as(c.file() + " app.ses.from-address default must be exactly '" + c.allowedDefault()
                    + "' — no other literal domain is permitted here")
                .isEqualTo(c.allowedDefault());
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
