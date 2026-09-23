package com.softropic.skillars.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-117 AC5 — {@link PessimisticLockRetryer#withBoundedRetry} retries its supplier
 * from a JDBC savepoint on every {@code PessimisticLockingFailureException}, so the supplier can
 * legitimately execute more than once for a single logical call. That is correct only if the supplier
 * is read-only/side-effect-free — a contract stated solely in {@code PessimisticLockRetryer}'s own
 * javadoc, with nothing mechanical enforcing it across a growing set of call sites (28 at story-
 * creation time, 30 as of {@code skillars-deferred-121}'s two new sites in {@code
 * AdminCoachEnforcementService.reinstateCoach}/{@code .deleteStrike} — mirrors this codebase's own {@code EmailTransportArchitectureTest} /
 * {@code NoStraySmtpConfigTest} convention: a hand-rolled source-text scan rather than a general-
 * purpose static-analysis dependency). Now 31 as of {@code skillars-deferred-127}'s new
 * {@code GdprErasureService.deletePlayerDevelopmentData} lock-acquisition site (the retried lambda
 * is the {@code findByIdForUpdate}+{@code orElseThrow} read only — the method's bulk deletes run
 * after {@code withBoundedRetry} returns, so it passes the same read-only contract as every other
 * site here). Now 32 as of {@code skillars-deferred-130}'s new
 * {@code CoachProfileService.publishProfile} lock-and-recheck site (AC1 Fix 3) — the retried lambda
 * is a {@code findByIdForUpdate}+{@code orElseThrow} read plus an {@code entityManager.refresh},
 * mirroring {@code saveStep4}'s own identical shape, so it passes the same read-only contract too.
 *
 * <p><strong>What this test actually proved, not merely asserted</strong> (AC5's own "Verified by"):
 * building this scan against the real call sites (28 at story-creation time) surfaced one genuine violation —
 * {@code DrillUploadService}'s {@code initiateUpload}/{@code deleteVideo} both had writes
 * ({@code setVideoId}/{@code upsertVideoId}/{@code clearVideoId}) and an {@code eventPublisher.publishEvent(...)}
 * inside the retried lambda. Tracing it, no double-execution was actually reachable (every
 * {@code PessimisticLockingFailureException}-throwing statement in those lambdas preceded every
 * write/publish), but the code was "safe" only by a fragile statement-ordering invariant, not by
 * satisfying the documented contract. Both were restructured — see {@code DrillUploadService}'s own
 * comments — to move the writes/publish to after {@code withBoundedRetry} returns, still inside the
 * same {@code @Transactional} method (the Postgres row lock is held for the whole transaction, not
 * just the lambda's duration, so this preserves the original locking guarantee exactly).
 *
 * <h2>Extraction mechanics</h2>
 * Every {@code .withBoundedRetry(} call site is located by a comment/string-literal-aware source
 * scan (comments and literal contents are blanked, not deleted, so line numbers and paren offsets
 * stay accurate), then the supplier argument is extracted by balanced-paren matching from the call's
 * own opening {@code (} to its matching close — not a naive regex up to the first {@code )} — so both
 * the single-expression form ({@code () -> repo.findByIdForUpdate(id).orElseThrow(...)}) and the
 * block form ({@code () -> { ...; return x; }}) are handled correctly, and a chained call AFTER
 * {@code withBoundedRetry(...)} returns (e.g. {@code withBoundedRetry(() -> repo.findByIdForUpdate(id)).ifPresent(...)}
 * — {@code BookingBatchService}'s two "fire and update" call sites) is correctly excluded from the
 * extracted argument, since the balanced-paren scan stops at {@code withBoundedRetry}'s own closing
 * paren, before the chained {@code .ifPresent(}.
 */
class PessimisticLockRetryerCallSiteAuditTest {

    private static final Path SRC_MAIN = Path.of("src", "main", "java");

    private static final Pattern CALL_SITE = Pattern.compile("\\.withBoundedRetry\\(");

    /**
     * Side-effecting patterns a retried {@code lockedOperation} supplier must never contain —
     * refined against what this codebase's real call sites actually use, per AC5's own
     * instruction not to add a pattern speculatively. Deliberately no bare {@code "Client."} entry
     * (code review: over-matches any local variable or entity field named {@code client}/{@code Client}
     * with no client call involved) — none of the sites use a raw HTTP/external SDK client inside
     * the lambda today, so no client-type denylist entry is needed either.
     */
    private static final List<Pattern> DENYLIST = List.of(
        Pattern.compile("\\.save\\("),
        Pattern.compile("\\.saveAndFlush\\("),
        Pattern.compile("\\.delete\\("),
        Pattern.compile("\\.deleteAll"),
        Pattern.compile("publishEvent\\("),
        Pattern.compile("new\\s+\\w*Event\\("),
        Pattern.compile("\\.send\\("),
        Pattern.compile("RestTemplate"),
        Pattern.compile("\\.enqueue\\(")
    );

    /**
     * The exact known count of real {@code .withBoundedRetry(} call sites under {@code src/main/java}
     * (28 at {@code skillars-deferred-117} story-creation time — the ledger's own stale count was 16 —
     * 30 after {@code skillars-deferred-121} added {@code AdminCoachEnforcementService
     * .reinstateCoach}/{@code .deleteStrike} — 31 after {@code skillars-deferred-127} added
     * {@code GdprErasureService.deletePlayerDevelopmentData} — now 32 after {@code skillars-deferred-130}
     * added {@code CoachProfileService.publishProfile}). Asserted explicitly so an added or removed
     * call site is loud (this count changes) rather than silently changing how much code this test
     * covers.
     */
    private static final int EXPECTED_CALL_SITE_COUNT = 32;

    private record CallSite(String file, int line, String argument) {
    }

    @Test
    @DisplayName("every .withBoundedRetry( call site's retried lambda is free of side-effecting patterns")
    void everyCallSite_lambdaIsReadOnly() throws IOException {
        List<CallSite> callSites = findCallSites();

        assertThat(callSites)
            .as("expected exactly %d real .withBoundedRetry( call sites under src/main/java. If this "
                + "count changed, a call site was added or removed — re-verify the new/changed site(s) "
                + "against the DENYLIST above rather than just updating this number. Found: %s",
                EXPECTED_CALL_SITE_COUNT, callSites)
            .hasSize(EXPECTED_CALL_SITE_COUNT);

        List<String> offenders = new ArrayList<>();
        for (CallSite site : callSites) {
            for (Pattern denied : DENYLIST) {
                if (denied.matcher(site.argument()).find()) {
                    offenders.add(site.file() + ":" + site.line() + " — matched '" + denied.pattern()
                        + "' inside the retried lambda: " + oneLine(site.argument()));
                }
            }
        }

        assertThat(offenders)
            .as("PessimisticLockRetryer.withBoundedRetry's contract requires the retried supplier to "
                + "be read-only/side-effect-free — it can legitimately execute more than once per "
                + "logical call. Move the offending call(s) to run after withBoundedRetry returns, "
                + "inside the same @Transactional method (the Postgres row lock is held for the whole "
                + "transaction, not just the lambda's duration, so this preserves the original locking "
                + "guarantee) — see DrillUploadService.initiateUpload/deleteVideo for the pattern.")
            .isEmpty();
    }

    // --- call-site discovery -------------------------------------------------------------------

    private static List<CallSite> findCallSites() throws IOException {
        List<CallSite> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC_MAIN)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
            for (Path file : javaFiles) {
                out.addAll(findCallSitesInFile(file));
            }
        }
        return out;
    }

    private static List<CallSite> findCallSitesInFile(Path file) {
        final String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String scanned = blankCommentsAndLiterals(raw);

        List<CallSite> out = new ArrayList<>();
        Matcher m = CALL_SITE.matcher(scanned);
        while (m.find()) {
            int openParen = m.end() - 1; // '.withBoundedRetry(' — m.end() - 1 is the '(' itself
            int closeParen = matchingParen(scanned, openParen);
            if (closeParen < 0) {
                throw new IllegalStateException(file + ": unbalanced parentheses scanning a "
                    + ".withBoundedRetry( call at offset " + m.start() + " — this audit test cannot "
                    + "verify this call site; fix the source (or the scanner if the source is "
                    + "legitimately unusual) rather than ignoring it");
            }
            String argument = scanned.substring(openParen + 1, closeParen);
            int line = 1 + countNewlines(scanned, m.start());
            out.add(new CallSite(file.toString(), line, argument));
        }
        return out;
    }

    /** The index of the {@code )} matching {@code text.charAt(openIdx) == '('}, or {@code -1}. */
    private static int matchingParen(String text, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int countNewlines(String text, int uptoExclusive) {
        int count = 0;
        for (int i = 0; i < uptoExclusive; i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Blanks {@code //}/{@code /* *}{@code /} comments and the contents of string/char literals
     * (including {@code """} text blocks) to single spaces, character-for-character, so every
     * remaining character keeps its original offset (line numbers stay accurate) and no stray paren
     * inside a comment or string literal can desync the balanced-paren scan above. Code, including
     * every real {@code (}/{@code )}/{@code {}/{@code }}, is left untouched.
     */
    private static String blankCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int nl = source.indexOf('\n', i);
                i = nl < 0 ? n : nl; // stop right before '\n' so it is appended normally next loop
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                for (int j = i; j < stop; j++) {
                    out.append(source.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = stop;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != quote) {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append(' '); // closing quote
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String oneLine(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }
}
