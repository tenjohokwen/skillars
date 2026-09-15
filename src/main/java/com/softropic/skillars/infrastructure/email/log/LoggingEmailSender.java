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
 * AC9b): logs every send and, when {@code app.email.log.dump-dir} is configured, writes the
 * rendered body to disk so template work has a real artifact to look at.
 *
 * <p><strong>Never throws.</strong> A misconfigured or since-removed dump directory degrades to
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
 *   <li><strong>Collision exhaustion (a correlationId reused past {@code MAX_COLLISION_ATTEMPTS}
 *       times) gets its own, separate transition-throttle flag</strong> (code review 2026-09-15, C2)
 *       — sharing one flag with the I/O-failure case above meant an exhausted-collisions send could
 *       flip it to "unwritable" and permanently swallow the next genuine {@code IOException} (disk
 *       full, permissions), since that later failure would see the flag already false and never
 *       observe the transition it throttles on. The two failure kinds are unrelated, so they no
 *       longer share state. Both bodies of one send (html/text) are aggregated into a single
 *       writable/exhausted decision per {@link #writeToDumpDir} call (code review 2026-09-15, H3) —
 *       resolving each {@code writeOneFile} outcome independently would let one body's success reset
 *       the flag the other body's failure just set, within the same send.
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

    private final String dumpDir;
    private final boolean dumpDirConfigured;

    /** Set once, at startup, if directory creation fails. Never cleared — see class javadoc. */
    private volatile boolean startupWriteDisabled = false;

    /** Tracks whether the directory is currently believed writable, for WARN-on-transition only. */
    private final AtomicBoolean directoryWritable = new AtomicBoolean(true);

    /**
     * Tracks collision exhaustion separately from {@link #directoryWritable} (code review 2026-09-15,
     * C2) — an unrelated failure mode (a correlationId's name space is full) sharing one flag with
     * genuine I/O errors let a collision-exhaustion event mask the next real {@code IOException}.
     */
    private final AtomicBoolean collisionAttemptsExhausted = new AtomicBoolean(false);

    public LoggingEmailSender(EmailTransportProperties properties) {
        String configuredDir = properties.getLog().getDumpDir();
        this.dumpDirConfigured = configuredDir != null && !configuredDir.isBlank();
        this.dumpDir = dumpDirConfigured ? configuredDir : null;
    }

    @PostConstruct
    void createDumpDirectory() {
        if (!dumpDirConfigured) {
            return;
        }
        try {
            Files.createDirectories(Path.of(dumpDir));
        } catch (IOException | InvalidPathException ex) {
            // InvalidPathException is unchecked and is NOT an IOException — Path.of throws it for a
            // value containing a NUL byte, or any illegal character on the host filesystem. Catching
            // IOException alone let it escape @PostConstruct and fail context refresh, which is the
            // exact opposite of this class's contract, for the transport whose whole purpose is to
            // be the safe default (code review 2026-09-11).
            startupWriteDisabled = true;
            log.warn("Failed to create dump directory '{}'; file-writing disabled for this process", dumpDir, ex);
        }
    }

    @Override
    public OutboundEmailResult send(OutboundEmailRequest request) {
        log.info("Sending email (log transport): to={}, subject={}, correlationId={}",
            maskAddress(request.toAddress()), request.subject(), request.correlationId());

        if (dumpDirConfigured && !startupWriteDisabled) {
            writeToDumpDir(request);
        }

        return new OutboundEmailResult("log:" + request.correlationId());
    }

    private void writeToDumpDir(OutboundEmailRequest request) {
        boolean hasHtml = isPresent(request.htmlBody());
        boolean hasText = isPresent(request.textBody());
        if (!hasHtml && !hasText) {
            // Both blank cannot happen (AC1 of ses-1.1), but guards against writing a null/empty file.
            return;
        }

        Path dir;
        try {
            dir = Path.of(dumpDir);
        } catch (InvalidPathException ex) {
            // Unreachable while startupWriteDisabled guards this call, but Path.of is unchecked and
            // this method is contractually "never throws" — see createDumpDirectory.
            if (directoryWritable.compareAndSet(true, false)) {
                log.warn("Dump directory '{}' is not a valid path; degraded to log-only", dumpDir, ex);
            }
            return;
        }

        // Write each present body as its own file, with its own independent collision-suffix
        // counter (AC2 of skillars-deferred-111) — a caller sending both an HTML and a text body
        // (none does today; OutboundEmailRequestValidationTest.bothBodiesPresent_isAccepted is the
        // only place this shape is exercised, and it's a validator test, not this class's) must not
        // have the text part silently dropped just because the HTML branch was picked first.
        //
        // The two outcomes are collected rather than acted on inside writeOneFile itself (code review
        // 2026-09-15, H3): resolving the throttle flags per-file let one body's success clear the
        // flag the other body's failure in the very same send had just set, spuriously re-arming the
        // WARN for the next reused correlationId.
        String baseName = sanitize(request.correlationId());
        WriteOutcome htmlOutcome = hasHtml ? writeOneFile(dir, baseName, "html", request.htmlBody()) : null;
        WriteOutcome textOutcome = hasText ? writeOneFile(dir, baseName, "txt", request.textBody()) : null;
        recordOutcome(htmlOutcome, textOutcome, request.correlationId());
    }

    private WriteOutcome writeOneFile(Path dir, String baseName, String extension, String content) {
        for (int attempt = 1; attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            String fileName = attempt == 1 ? baseName + "." + extension : baseName + "~" + attempt + "." + extension;
            Path target = dir.resolve(fileName);
            try {
                Files.writeString(target, content, StandardOpenOption.CREATE_NEW);
                return WriteOutcome.success();
            } catch (FileAlreadyExistsException ex) {
                // Collision on this correlation id/extension — try the next suffix.
            } catch (IOException ex) {
                return WriteOutcome.ioError(ex);
            }
        }
        // MAX_COLLISION_ATTEMPTS real CREATE_NEW syscalls still happen here every time — there is no
        // way to know a name is taken without attempting it, without reintroducing the exists-then-
        // create TOCTOU this class avoids elsewhere. What's throttled below is only the WARN log line,
        // not the syscalls themselves.
        return WriteOutcome.collisionExhausted();
    }

    /**
     * Applies the transition-throttle for both failure kinds exactly once per {@link
     * #writeToDumpDir} call, from the combined outcome of every body actually written — see that
     * method's javadoc note for why per-file resolution is wrong (code review 2026-09-15, C2 + H3).
     */
    private void recordOutcome(WriteOutcome first, WriteOutcome second, String correlationId) {
        boolean anySuccess = isKind(first, WriteOutcome.Kind.SUCCESS) || isKind(second, WriteOutcome.Kind.SUCCESS);
        IOException ioFailure = ioFailureOf(first) != null ? ioFailureOf(first) : ioFailureOf(second);
        boolean anyCollisionExhausted = isKind(first, WriteOutcome.Kind.COLLISION_EXHAUSTED)
            || isKind(second, WriteOutcome.Kind.COLLISION_EXHAUSTED);

        if (ioFailure != null) {
            if (directoryWritable.compareAndSet(true, false)) {
                log.warn("Failed to write dump file for correlationId={}: {}", correlationId, ioFailure.getMessage(), ioFailure);
            }
        } else if (anySuccess) {
            directoryWritable.set(true);
        }

        if (anyCollisionExhausted) {
            // Throttled the same way as the IOException branch above (AC1 of skillars-deferred-111): a
            // caller reusing a constant correlationId against an outbox already holding
            // MAX_COLLISION_ATTEMPTS matching files must not emit one WARN per send indefinitely —
            // only a genuine not-exhausted-to-exhausted transition logs.
            if (collisionAttemptsExhausted.compareAndSet(false, true)) {
                log.warn("Failed to write dump file for correlationId={} after {} collision attempts; degraded to log-only",
                    correlationId, MAX_COLLISION_ATTEMPTS);
            }
        } else if (anySuccess) {
            collisionAttemptsExhausted.set(false);
        }
    }

    private static boolean isKind(WriteOutcome outcome, WriteOutcome.Kind kind) {
        return outcome != null && outcome.kind() == kind;
    }

    private static IOException ioFailureOf(WriteOutcome outcome) {
        return outcome != null && outcome.kind() == WriteOutcome.Kind.IO_ERROR ? outcome.ioException() : null;
    }

    /** Outcome of one {@link #writeOneFile} attempt, resolved by the caller rather than logged inline. */
    private record WriteOutcome(Kind kind, IOException ioException) {
        enum Kind { SUCCESS, IO_ERROR, COLLISION_EXHAUSTED }

        static WriteOutcome success() {
            return new WriteOutcome(Kind.SUCCESS, null);
        }

        static WriteOutcome ioError(IOException ex) {
            return new WriteOutcome(Kind.IO_ERROR, ex);
        }

        static WriteOutcome collisionExhausted() {
            return new WriteOutcome(Kind.COLLISION_EXHAUSTED, null);
        }
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
     * The rendered dump file still carries everything, which is what dev actually reads
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
