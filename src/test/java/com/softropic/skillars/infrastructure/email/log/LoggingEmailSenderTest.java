package com.softropic.skillars.infrastructure.email.log;

import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;

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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story ses-1.1 AC9/AC9b.
 */
class LoggingEmailSenderTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger senderLogger;

    private Level originalLevel;

    @BeforeEach
    void setUp() {
        senderLogger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        // logback-test.xml pins the root at WARN, so this class's per-send INFO line is filtered
        // out by default and never reaches the appender. The masking assertion below is about that
        // INFO line specifically, so raise the level for this class only and restore it after.
        originalLevel = senderLogger.getLevel();
        senderLogger.setLevel(Level.INFO);
        logAppender = new ListAppender<>();
        logAppender.start();
        senderLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        senderLogger.detachAppender(logAppender);
        senderLogger.setLevel(originalLevel);
    }

    private static EmailTransportProperties propsWithOutboxDir(String dir) {
        EmailTransportProperties props = new EmailTransportProperties();
        props.getLog().setOutboxDir(dir);
        return props;
    }

    @Test
    void neverThrows_evenWhenNothingConfigured() {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(null));
        sender.createOutboxDirectory();

        OutboundEmailResult result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid"));

        assertThat(result).isEqualTo(new OutboundEmailResult("log:cid"));
    }

    /**
     * Both of these previously asserted that an unrelated {@code @TempDir} was empty — a directory
     * the sender was never told about, so the assertion held by construction and the tests could
     * not fail. Deleting {@code !configuredDir.isBlank()} from the constructor makes
     * {@code Path.of("")} resolve to the <strong>working directory</strong>, so the file lands in
     * the module root; that is the mutation these tests exist to catch, and it is what
     * {@code application-test.yaml} calls load-bearing (every registration-flow IT would otherwise
     * write rendered OTP emails into the repo). They now watch the working directory itself.
     */
    private static Set<Path> workingDirectorySnapshot() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(""))) {
            return files.collect(Collectors.toSet());
        }
    }

    @Test
    void unsetOutboxDir_writesNoFileAnywhere() throws IOException {
        Set<Path> before = workingDirectorySnapshot();

        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(null));
        sender.createOutboxDirectory();
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid-unset"));

        assertThat(workingDirectorySnapshot())
            .as("an unset outbox-dir must not write a file anywhere, including the working directory")
            .isEqualTo(before);
        assertThat(Path.of("cid-unset.html")).doesNotExist();
    }

    @Test
    void blankOutboxDir_treatedAsUnset_writesNoFileAnywhere() throws IOException {
        Set<Path> before = workingDirectorySnapshot();

        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(""));
        sender.createOutboxDirectory();
        sender.send(new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid-blank"));

        assertThat(workingDirectorySnapshot())
            .as("a blank outbox-dir must be treated as unset, not resolved to the working directory")
            .isEqualTo(before);
        assertThat(Path.of("cid-blank.html")).doesNotExist();
    }

    @Test
    void htmlBody_writesHtmlFile(@TempDir Path tempDir) {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(tempDir.toString()));
        sender.createOutboxDirectory();

        OutboundEmailResult result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<p>hi</p>", null, "cid-html"));

        assertThat(result).isEqualTo(new OutboundEmailResult("log:cid-html"));
        assertThat(tempDir.resolve("cid-html.html")).hasContent("<p>hi</p>");
        assertThat(tempDir.resolve("cid-html.txt")).doesNotExist();
    }

    @Test
    void textOnlyBody_writesTxtFileNotHtml(@TempDir Path tempDir) {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(tempDir.toString()));
        sender.createOutboxDirectory();

        sender.send(new OutboundEmailRequest("to@example.com", "subject", null, "plain body", "cid-text"));

        assertThat(tempDir.resolve("cid-text.txt")).hasContent("plain body");
        assertThat(tempDir.resolve("cid-text.html")).doesNotExist();
    }

    @Test
    void nonNullMessageIdAlways() {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(null));
        sender.createOutboxDirectory();

        OutboundEmailResult result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "abc-123"));

        assertThat(result.messageId()).isNotNull().isEqualTo("log:abc-123");
    }

    @Test
    void unwritableDirectory_degradesWithoutThrowing_andLogsWarn(@TempDir Path tempDir) throws IOException {
        Path notADirectory = tempDir.resolve("not-a-directory-its-a-file");
        Files.writeString(notADirectory, "occupied");

        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(notADirectory.toString()));
        sender.createOutboxDirectory(); // Files.createDirectories fails: path exists as a file

        OutboundEmailResult result = sender.send(
            new OutboundEmailRequest("to@example.com", "subject", "<html/>", null, "cid"));

        assertThat(result).isEqualTo(new OutboundEmailResult("log:cid"));
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Failed to create outbox directory");
        });
    }

    @Test
    void correlationIdWithPathTraversal_isSanitisedAndStaysInsideOutboxDir(@TempDir Path tempDir) throws IOException {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(tempDir.toString()));
        sender.createOutboxDirectory();

        sender.send(new OutboundEmailRequest(
            "to@example.com", "subject", "<html/>", null, "../../etc/passwd"));

        // Pin the sanitised name itself. The previous assertions were tautologies: Files.walk(tempDir)
        // can only yield paths under tempDir, and /etc/passwd.html never exists on any machine —
        // neither could fail, so sanitize()'s actual behaviour went unpinned.
        assertThat(tempDir.resolve(".._.._etc_passwd.html"))
            .as("every path separator and traversal segment must be replaced, not interpreted")
            .exists();
        try (Stream<Path> files = Files.walk(tempDir)) {
            assertThat(files.filter(Files::isRegularFile))
                .as("exactly one file, and it did not escape the outbox dir")
                .hasSize(1);
        }
    }

    /**
     * The {@code NoOpSesEmailService} this class replaces logged the subject only. Logging the full
     * recipient silently widened what lands in log storage: UAT runs this transport with
     * {@code LOKI_ENABLED=true}, and this platform's registrants include minors and their parents.
     * The rendered outbox file still carries everything, which is what dev actually reads
     * (code review 2026-09-11, D4).
     */
    @Test
    void recipientAddressIsMaskedOnTheInfoLine_butIntactInTheOutboxFile(@TempDir Path tempDir) {
        LoggingEmailSender sender = new LoggingEmailSender(propsWithOutboxDir(tempDir.toString()));
        sender.createOutboxDirectory();

        sender.send(new OutboundEmailRequest(
            "jane.doe@example.com", "subject", "<p>hi jane.doe@example.com</p>", null, "cid-mask"));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("j***@example.com");
            assertThat(event.getFormattedMessage()).doesNotContain("jane.doe@example.com");
        });
        assertThat(tempDir.resolve("cid-mask.html")).content().contains("jane.doe@example.com");
    }

    @Test
    void maskAddress_handlesValuesWithNoUsableLocalPart() {
        assertThat(LoggingEmailSender.maskAddress("a@b.com")).isEqualTo("a***@b.com");
        assertThat(LoggingEmailSender.maskAddress("@b.com")).isEqualTo("***");
        assertThat(LoggingEmailSender.maskAddress("no-at-sign")).isEqualTo("***");
    }
}