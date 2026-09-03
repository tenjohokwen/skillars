package com.softropic.skillars.infrastructure.message;

import com.softropic.skillars.infrastructure.exception.ApplicationException;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * skillars-deferred-91 AC12: {@link ErrorLog} used to build its log line with
 * {@code String.format(msgTemplate + " SUPPORT_ID: %s", helpCode)}. {@code msgTemplate} is
 * frequently {@code ex.getMessage()} (~20 {@code ApiAdvice} call sites +
 * {@code JWTAuthorizationFilter.writeUnauthorized}); an exception message carrying a bare {@code %}
 * — user input echoed by a validation message, a raw driver error — made {@code String.format}
 * throw {@code UnknownFormatConversionException} / {@code MissingFormatArgumentException}
 * <em>inside</em> the exception handler, collapsing the response to a bare 500 with no
 * {@code ErrorDto} body. The fix logs the template as a parameterised argument, removing the
 * {@code %} hazard entirely (same failure class as skillars-deferred-90 AC1's NPE).
 */
class ErrorLogTest {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorLogTest.class);

    @Test
    void logError_messageWithPercentConversion_doesNotThrow_returnsHelpCode() {
        String[] holder = new String[1];

        assertThatCode(() -> holder[0] =
                ErrorLog.logError(LOG, new RuntimeException("boom"), "100% of writes failed near %s and %d"))
            .doesNotThrowAnyException();

        assertThat(holder[0]).isNotBlank();
    }

    @Test
    void logError_messageWithLoneTrailingPercent_doesNotThrow() {
        assertThatCode(() ->
                ErrorLog.logError(LOG, new IllegalArgumentException("value 50% is invalid: "),
                    "value 50% is invalid: %"))
            .doesNotThrowAnyException();
    }

    @Test
    void logError_messageWithSlf4jPlaceholders_isInertNotInterpolated() {
        // SLF4J substitutes each {} with the argument verbatim and does not re-scan the substituted
        // value, so a {} inside msgTemplate must not blow up arg resolution either.
        String helpCode = ErrorLog.logError(LOG, new RuntimeException("x"),
            "unexpected token {} at {} in payload");

        assertThat(helpCode).isNotBlank();
    }

    @Test
    void logExpected_messageWithPercent_doesNotThrow_returnsHelpCode() {
        String helpCode =
            ErrorLog.logExpected(LOG, new RuntimeException("token 100% expired %s"), "token 100% expired %s");

        assertThat(helpCode).isNotBlank();
    }

    @Test
    void logError_applicationException_reusesSupportId() {
        ApplicationException appEx = new ApplicationException("bad %s state");

        String helpCode = ErrorLog.logError(LOG, appEx, "bad %s state");

        assertThat(helpCode).isEqualTo(appEx.getSupportId());
    }
}
