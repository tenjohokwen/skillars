package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                        "ledger.database.spy=true",
                        "enable.test.mail=true",
                        "spring.cloud.compatibility-verifier.enabled=false"
                })
@Import(TestConfig.class)
public class MailManagerIT {

    @Autowired
    private MailManager mailManager;

    @Test
    void contextLoads() {
    }

    @Test
    void testTestMailManagerDispatchesEmails() {
        assertThat(mailManager).isInstanceOf(TestMailManager.class);
        TestMailManager testMailManager = (TestMailManager) mailManager;

        Recipient recipient = new Recipient();
        recipient.setEmail("test@example.com");

        String sendId = UUID.randomUUID().toString();
        Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.ACTIVATION,
                Instant.now().plusSeconds(86400),
                Map.of("activationKey", "12345"),
                sendId
        );

        mailManager.sendEmailFromTemplate(envelope);

        await().until(() -> testMailManager.getEnvelope(sendId) != null);
        Envelope received = testMailManager.getEnvelope(sendId);
        assertThat(received).isNotNull();
        assertThat(received.sendId()).isEqualTo(sendId);
        assertThat(received.data().get("activationKey")).isEqualTo("12345");
    }

    @Test
    void testTestMailManagerDispatchesEmailsSynchronously() {
        assertThat(mailManager).isInstanceOf(TestMailManager.class);
        TestMailManager testMailManager = (TestMailManager) mailManager;

        Recipient recipient = new Recipient();
        recipient.setEmail("test@example.com");

        String sendId = UUID.randomUUID().toString();
        Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.ACTIVATION,
                Instant.now().plusSeconds(86400),
                Map.of("activationKey", "12345"),
                sendId
        );

        mailManager.sendEmailSync(envelope);

        Envelope received = testMailManager.getEnvelope(sendId);
        assertThat(received).isNotNull();
        assertThat(received.sendId()).isEqualTo(sendId);
        assertThat(received.data().get("activationKey")).isEqualTo("12345");
    }
}
