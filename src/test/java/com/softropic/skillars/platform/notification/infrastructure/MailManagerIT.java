package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.config.AbstractIntegrationTest;

import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.MailManager;
import com.softropic.skillars.utils.TestMailManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class MailManagerIT extends AbstractIntegrationTest {

    @Autowired
    private MailManager mailManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;


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
