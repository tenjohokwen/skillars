package com.softropic.skillars.infrastructure.ses;

import com.softropic.skillars.infrastructure.email.EmailAddressParser;
import com.softropic.skillars.infrastructure.email.EmailTransportPermanentException;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Story ses-1.1 AC4 — a malformed recipient address, and a comma-separated multi-address string,
 * both fail closed with {@link EmailTransportPermanentException} and zero SDK interaction.
 */
class SesAddressValidationTest {

    private SesV2Client client;
    private SesEmailSender sender;

    @BeforeEach
    void setUp() {
        client = mock(SesV2Client.class);
        SesProperties props = new SesProperties();
        props.setFromAddress("noreply@example.com");
        sender = new SesEmailSender(client, props, new EmailAddressParser(), new SesErrorClassifier());
    }

    @Test
    void malformedRecipient_rejectedWithNoSdkInteraction() {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "not-an-address", "subject", "<html/>", null, "cid");

        assertThatThrownBy(() -> sender.send(request))
            .isInstanceOf(EmailTransportPermanentException.class);

        verifyNoInteractions(client);
    }

    @Test
    void multiAddressRecipient_rejectedWithNoSdkInteraction() {
        OutboundEmailRequest request = new OutboundEmailRequest(
            "victim@x.com, attacker@y.com", "subject", "<html/>", null, "cid");

        assertThatThrownBy(() -> sender.send(request))
            .isInstanceOf(EmailTransportPermanentException.class);

        verifyNoInteractions(client);
    }
}
