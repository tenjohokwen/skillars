package com.softropic.skillars.infrastructure.email.log;

import com.softropic.skillars.infrastructure.email.EmailTransportProperties;
import com.softropic.skillars.infrastructure.email.OutboundEmailRequest;
import com.softropic.skillars.infrastructure.email.OutboundEmailResult;
import com.softropic.skillars.infrastructure.email.OutboundEmailSender;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The dev-friendly logging transport, replacing {@code NoOpSesEmailService} (story ses-1.1 AC9/
 * AC9b): logs every send and, when {@code app.email.log.outbox-dir} is configured, writes the
 * rendered body to disk so template work has a real artifact to look at.
 *
 * <p><strong>Never throws.</strong> A misconfigured or since-removed outbox directory degrades to
 * log-only rather than failing the send — see the two distinct disable mechanisms below.
 *
 * <ul>
 *   <li><strong>Startup failure is permanent for the process.</strong> The directory is created at
 *       startup, not on first send. If that fails, file-writing is disabled for the remaining
 *       lifetime of this bean — every subsequent send just logs and skips the write attempt
 *       entirely. Context startup itself is not aborted (an operator sees a WARN at boot, not a
 *       failed deploy).
 *   <li><strong>A later, transient loss of the directory (TOCTOU — creation and writing happen at
 *       different times) only degrades the one affected send</strong>, not the process: the next
 *       send tries again, in case the directory reappears. The repeated-WARN spam this could cause
 *       under concurrent senders is throttled with an {@link AtomicBoolean} that tracks the
 *       currently-believed writable state: a failure only logs when it is a genuine transition from
 *       writable to unwritable, and a later success silently clears the flag so a <em>future</em>
 *       failure logs again rather than being permanently suppressed.
 * </ul>
 *
 * <p><strong>{@code matchIfMissing = true} is load-bearing, not decoration.</strong> The
 * {@code NoOpSesEmailService} this class replaces carried it, which is what made
 * {@code EmailTransportPropertyValidator}'s "an absent value is allowed" safe: with no
 * {@code matchIfMissing} anywhere, "property absent" is the single state in which the validator
 * waves the boot through and <em>zero</em> {@link OutboundEmailSender} beans exist, so the three
 * registration listeners die on constructor injection with the
 * {@code NoSuchBeanDefinitionException} that validator exists to prevent. {@code application.yaml}'s
 * base default normally covers it, but a {@code spring.config.location}/{@code spring.config.name}
 * override can shadow that file. The safe transport is the right fallback (code review 2026-09-11).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.transport", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements OutboundEmailSender {

    private static final Pattern UNSAFE_CORRELATION_ID_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final int MAX_CORRELATION_ID_LENGTH = 200;
    private static final int MAX_COLLISION_ATTEMPTS = 100;

    private final String outboxDir;
    private final boolean outboxConfigured;

    /** Set once, at startup, if directory creation fails. Never cleared — see class javadoc. */
    private volatile boolean startupWriteDisabled = false;

    /** Tracks whether the directory is currently believed writable, for WARN-on-transition only. */
    private final AtomicBoolean directoryWritable = new AtomicBoolean(true);

    public LoggingEmailSender(EmailTransportProperties properties) {
        String configuredDir = properties.getLog().getOutboxDir();
        this.outboxConfigured = configuredDir != null && !configuredDir.isBlank();
        this.outboxDir = outboxConfigured ? configuredDir : null;
    }

    @PostConstruct
    void createOutboxDirectory() {
        if (!outboxConfigured) {
            return;
        }
        try {
            Files.createDirectories(Path.of(outboxDir));
        } catch (IOException | InvalidPathException ex) {
            // InvalidPathException is unchecked and is NOT an IOException — Path.of throws it for a
            // value containing a NUL byte, or any illegal character on the host filesystem. Catching
            // IOException alone let it escape @PostConstruct and fail context refresh, which is the
            // exact opposite of this class's contract, for the transport whose whole purpose is to
            // be the safe default (code review 2026-09-11).
            startupWriteDisabled = true;
            log.warn("Failed to create outbox directory '{}'; file-writing disabled for this process", outboxDir, ex);
        }
    }

    @Override
    public OutboundEmailResult send(OutboundEmailRequest request) {
        log.info("Sending email (log transport): to={}, subject={}, correlationId={}",
            maskAddress(request.toAddress()), request.subject(), request.correlationId());

        if (outboxConfigured && !startupWriteDisabled) {
            writeToOutbox(request);
        }

        return new OutboundEmailResult("log:" + request.correlationId());
    }

    private void writeToOutbox(OutboundEmailRequest request) {
        boolean isHtml = isPresent(request.htmlBody());
        String content = isHtml ? request.htmlBody() : request.textBody();
        if (!isPresent(content)) {
            // Both blank cannot happen (AC1), but guards against writing a null/empty file.
            return;
        }
        String extension = isHtml ? "html" : "txt";
        String baseName = sanitize(request.correlationId());
        Path dir;
        try {
            dir = Path.of(outboxDir);
        } catch (InvalidPathException ex) {
            // Unreachable while startupWriteDisabled guards this call, but Path.of is unchecked and
            // this method is contractually "never throws" — see createOutboxDirectory.
            if (directoryWritable.compareAndSet(true, false)) {
                log.warn("Outbox directory '{}' is not a valid path; degraded to log-only", outboxDir, ex);
            }
            return;
        }

        for (int attempt = 1; attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            String fileName = attempt == 1 ? baseName + "." + extension : baseName + "-" + attempt + "." + extension;
            Path target = dir.resolve(fileName);
            try {
                Files.writeString(target, content, StandardOpenOption.CREATE_NEW);
                directoryWritable.set(true);
                return;
            } catch (FileAlreadyExistsException ex) {
                // Collision on this correlation id — try the next suffix.
            } catch (IOException ex) {
                if (directoryWritable.compareAndSet(true, false)) {
                    log.warn("Failed to write outbox file for correlationId={}: {}",
                        request.correlationId(), ex.getMessage(), ex);
                }
                return;
            }
        }
        log.warn("Failed to write outbox file for correlationId={} after {} collision attempts; degraded to log-only",
            request.correlationId(), MAX_COLLISION_ATTEMPTS);
    }

    /**
     * Restricts a correlation id to {@code [A-Za-z0-9._-]} and truncates to a sane length before it
     * becomes part of a file path — {@link OutboundEmailSender} is a public port, and today's only
     * caller passes a random UUID, but nothing guarantees every future caller will.
     */
    private static String sanitize(String correlationId) {
        String cleaned = UNSAFE_CORRELATION_ID_CHARS.matcher(correlationId).replaceAll("_");
        return cleaned.length() > MAX_CORRELATION_ID_LENGTH
            ? cleaned.substring(0, MAX_CORRELATION_ID_LENGTH)
            : cleaned;
    }

    /**
     * Masks the local part for the INFO line. The {@code NoOpSesEmailService} this class replaces
     * logged the subject only; logging the full recipient was a silent widening of what lands in
     * log storage. UAT runs this transport with {@code LOKI_ENABLED=true}, and this platform's
     * registrants include minors and their parents, so the full address does not belong at INFO.
     * The rendered outbox file still carries everything, which is what dev actually reads
     * (code review 2026-09-11, D4).
     */
    static String maskAddress(String address) {
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
