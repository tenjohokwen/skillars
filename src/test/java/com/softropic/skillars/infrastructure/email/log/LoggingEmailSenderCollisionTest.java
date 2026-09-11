package com.softropic.skillars.infrastructure.email.log;

import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC9 — the "never overwrite" collision-retry loop and its exhaustion path.
 */
class LoggingEmailSenderCollisionTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    @BeforeEach
    void setUp() {
        senderLogger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
    }

    private static LoggingEmailSender sender(Path dir) {
        EmailTransportProperties props = new EmailTransportProperties();
        props.getLog().setOutboxDir(dir.toString());
        LoggingEmailSender sender = new LoggingEmailSender(props);
        sender.createOutboxDirectory();
        return sender;
    }

    @Test
    void twoSendsSharingCorrelationId_produceTwoFiles(@TempDir Path tempDir) {
        LoggingEmailSender sender = sender(tempDir);

        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<p>first</p>", null, "shared-cid"));
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<p>second</p>", null, "shared-cid"));

        assertThat(tempDir.resolve("shared-cid.html")).hasContent("<p>first</p>");
        assertThat(tempDir.resolve("shared-cid-2.html")).hasContent("<p>second</p>");
    }

    @Test
    void directoryRemovedBetweenStartupAndSend_degradesToLogOnly_logsWarn(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = sender(tempDir);
        Files.delete(tempDir); // removed after startup succeeded, before this send

        var result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid"));

        assertThat(result.messageId()).isEqualTo("log:cid");
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Failed to write outbox file");
        });
    }

    @Test
    void exhaustingCollisionRetries_logsSpecificWarnMessage(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = sender(tempDir);
        String correlationId = "collision-cid";

        // Pre-create every filename the collision loop will try (base + -2 .. -100), so every
        // attempt hits FileAlreadyExistsException and the loop is forced to exhaust.
        Files.writeString(tempDir.resolve(correlationId + ".html"), "occupied");
        for (int i = 2; i <= 100; i++) {
            Files.writeString(tempDir.resolve(correlationId + "-" + i + ".html"), "occupied");
        }

        var result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, correlationId));

        assertThat(result.messageId()).isEqualTo("log:" + correlationId);
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .contains("after 100 collision attempts")
                .contains(correlationId);
        });

        try (Stream<Path> files = Files.list(tempDir)) {
            // No new file was created beyond the 100 pre-seeded ones.
            assertThat(files).hasSize(100);
        }
    }
}
