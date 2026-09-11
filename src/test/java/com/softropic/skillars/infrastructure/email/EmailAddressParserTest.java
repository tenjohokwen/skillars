package com.softropic.skillars.infrastructure.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story ses-1.1 AC8/Task 3.
 *
 * <p>{@code "not-an-address"} is the case that matters most here: empirically (verified against the
 * resolved {@code angus-mail} jar while writing this test), the bare, non-strict
 * {@code new InternetAddress("not-an-address")} constructor does <strong>not</strong> throw — it
 * happily returns an address with that literal value. Only {@link jakarta.mail.internet.InternetAddress#validate()}
 * (which {@link EmailAddressParser} calls after strict parsing) rejects it, with "Missing final
 * '@domain'". A test built only from inputs that already fail the bare constructor would pass
 * against a non-validating implementation and give false confidence that {@code .validate()} is
 * actually being invoked — this test specifically proves it is.
 */
class EmailAddressParserTest {

    private final EmailAddressParser parser = new EmailAddressParser();

    @Test
    void bareAddress_isAccepted() {
        assertThat(parser.validateSingle("john@example.com")).isEqualTo("john@example.com");
    }

    @Test
    void displayNameForm_isAcceptedAndReturnsBareAddress() {
        assertThat(parser.validateSingle("Display Name <john@example.com>"))
            .isEqualTo("john@example.com");
    }

    @Test
    void notAnAddress_passesTheBareConstructorButIsRejectedByValidate() {
        // Documented above: new InternetAddress("not-an-address") does NOT throw. Only .validate()
        // catches this — proving the parser actually validates rather than merely parsing.
        assertThatThrownBy(() -> parser.validateSingle("not-an-address"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localPartWithUnescapedSpace_isRejected() {
        assertThatThrownBy(() -> parser.validateSingle("john smith@example.com"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commaSeparatedMultiAddress_isRejectedNotNarrowedToFirst() {
        assertThatThrownBy(() -> parser.validateSingle("a@b.com, c@d.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }

    /**
     * The comma form above splits into two addresses, so {@code parsed.length != 1} catches it.
     * RFC 822 <strong>group</strong> syntax does not: verified against the resolved
     * {@code angus-mail} jar, {@code InternetAddress.parse("undisclosed: a@x.com, b@y.com;", true)}
     * returns an array of length <strong>one</strong> whose element validates cleanly and whose
     * {@code getAddress()} is the entire group string. Without the {@code isGroup()} guard the
     * length check never fires for this input and a second recipient rides along inside a field
     * documented to hold exactly one (code review 2026-09-11).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "undisclosed: victim@example.com, attacker@evil.com;",
        "g: a@b.com;",
        "Group Name: one@x.com, two@y.com, three@z.com;"
    })
    void rfc822GroupSyntax_isRejected(String raw) {
        assertThatThrownBy(() -> parser.validateSingle(raw))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("group syntax");
    }

    /**
     * Callers send the raw string on the wire and use this class only for validation, so a value
     * that passes <em>because</em> the parser normalised it away would be validated in one form and
     * transmitted in another. All of these parse clean and normalise to the bare address; the raw
     * form then fails at the transport, permanently, on a value reported as valid.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "  john@example.com  ",
        "john@example.com ",
        " john@example.com",
        "john@example.com\n",
        "john@example.com\r\n",
        "a@b.com,",
        ",a@b.com"
    })
    void valuesThatOnlyPassAfterNormalisation_areRejected(String raw) {
        assertThatThrownBy(() -> parser.validateSingle(raw))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAddress_throwsIllegalArgumentNotNullPointer() {
        // InternetAddress.parse dereferences before any syntax check. An NPE here would escape
        // SesEmailSender's IllegalArgumentException-only conversion, the port's declared taxonomy,
        // and the listeners' catch — propagating out of an AFTER_COMMIT listener.
        assertThatThrownBy(() -> parser.validateSingle(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
    }
}
