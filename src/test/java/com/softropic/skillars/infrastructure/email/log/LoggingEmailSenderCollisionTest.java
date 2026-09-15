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
import java.io.UncheckedIOException;
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
        props.getLog().setDumpDir(dir.toString());
        LoggingEmailSender sender = new LoggingEmailSender(props);
        sender.createDumpDirectory();
        return sender;
    }

    @Test
    void twoSendsSharingCorrelationId_produceTwoFiles(@TempDir Path tempDir) {
        LoggingEmailSender sender = sender(tempDir);

        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<p>first</p>", null, "shared-cid"));
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<p>second</p>", null, "shared-cid"));

        assertThat(tempDir.resolve("shared-cid.html")).hasContent("<p>first</p>");
        assertThat(tempDir.resolve("shared-cid~2.html")).hasContent("<p>second</p>");
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
            assertThat(event.getFormattedMessage()).contains("Failed to write dump file");
        });
    }

    @Test
    void exhaustingCollisionRetries_logsSpecificWarnMessage(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = sender(tempDir);
        String correlationId = "collision-cid";

        // Pre-create every filename the collision loop will try (base + ~2 .. ~100), so every
        // attempt hits FileAlreadyExistsException and the loop is forced to exhaust.
        Files.writeString(tempDir.resolve(correlationId + ".html"), "occupied");
        for (int i = 2; i <= 100; i++) {
            Files.writeString(tempDir.resolve(correlationId + "~" + i + ".html"), "occupied");
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

    /**
     * skillars-deferred-111 AC1. Before this fix, the collision-exhausted WARN sat outside the
     * {@code directoryWritable} transition-throttle the {@code IOException} branch already uses, so
     * a caller reusing a constant correlationId against an already-full outbox performed
     * {@code MAX_COLLISION_ATTEMPTS} syscalls and logged once per send, indefinitely.
     * {@code // Mutation:} removing the {@code directoryWritable.compareAndSet(true, false)} guard
     * around the loop-exhausted {@code log.warn} turns this red (two WARNs instead of one).
     */
    @Test
    void exhaustingCollisionRetriesTwice_logsOnlyOneWarn_dueToThrottle(@TempDir Path tempDir) {
        LoggingEmailSender sender = sender(tempDir);
        String correlationId = "collision-cid-throttled";

        for (int i = 1; i <= 100; i++) {
            String fileName = i == 1 ? correlationId + ".html" : correlationId + "~" + i + ".html";
            try {
                Files.writeString(tempDir.resolve(fileName), "occupied");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, correlationId));
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, correlationId));

        long collisionExhaustedWarns = logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .filter(event -> event.getFormattedMessage().contains("after 100 collision attempts"))
            .count();
        assertThat(collisionExhaustedWarns)
            .as("only the first send's collision-exhaustion should log; the second is throttled")
            .isEqualTo(1);
    }

    /**
     * skillars-deferred-111 code review 2026-09-15 (H3). Before this fix, {@code writeOneFile}
     * resolved the throttle flag per file: the html body exhausting collisions logged a WARN and
     * flipped the flag, but the text body's own, unrelated success on the same send reset it — so
     * the very next reused-correlationId send logged a second WARN instead of staying throttled.
     * Resolving both bodies' outcomes together before touching the flag (this class's {@code
     * recordOutcome}) closes that gap.
     */
    @Test
    void exhaustingCollisionRetriesTwice_bothBodiesPresent_logsOnlyOneWarn(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = sender(tempDir);
        String correlationId = "both-bodies-throttled";

        // Only the html name space is pre-filled to MAX_COLLISION_ATTEMPTS; the text body is left
        // free so it succeeds on every send — the exact shape that reset the shared flag pre-fix.
        for (int i = 1; i <= 100; i++) {
            String fileName = i == 1 ? correlationId + ".html" : correlationId + "~" + i + ".html";
            Files.writeString(tempDir.resolve(fileName), "occupied");
        }

        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", "text body", correlationId));
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", "text body", correlationId));

        assertThat(tempDir.resolve(correlationId + ".txt")).hasContent("text body");
        assertThat(tempDir.resolve(correlationId + "~2.txt")).hasContent("text body");

        long collisionExhaustedWarns = logAppender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .filter(event -> event.getFormattedMessage().contains("after 100 collision attempts"))
            .count();
        assertThat(collisionExhaustedWarns)
            .as("the text body's success must not re-arm the html body's collision-exhaustion throttle")
            .isEqualTo(1);
    }

    /**
     * skillars-deferred-111 code review 2026-09-15 (C2). Before this fix, collision exhaustion and
     * genuine I/O failure shared one {@code AtomicBoolean}: an exhausted-collision send flipped it to
     * "unwritable", so a later, unrelated genuine {@code IOException} (disk full, permissions — here,
     * a directory removed out from under the sender) found the flag already false and never logged,
     * silencing the exact signal operators need. The two failure kinds now use independent flags.
     */
    @Test
    void collisionExhaustion_doesNotSuppressALaterGenuineIoFailureWarn(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = sender(tempDir);
        String exhaustedId = "collision-then-io-failure";

        for (int i = 1; i <= 100; i++) {
            String fileName = i == 1 ? exhaustedId + ".html" : exhaustedId + "~" + i + ".html";
            Files.writeString(tempDir.resolve(fileName), "occupied");
        }
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, exhaustedId));

        assertThat(logAppender.list).as("collision exhaustion must log its own transition WARN first")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("after 100 collision attempts");
            });
        logAppender.list.clear();

        try (Stream<Path> files = Files.list(tempDir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(tempDir); // genuine, unrelated I/O failure for a fresh correlationId
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "unrelated-cid"));

        assertThat(logAppender.list)
            .as("the genuine IOException must still log, unsuppressed by the earlier collision-exhaustion state")
            .anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Failed to write dump file");
            });
    }
}
