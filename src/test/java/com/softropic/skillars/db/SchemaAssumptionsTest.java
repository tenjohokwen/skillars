package com.softropic.skillars.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins schema-wide assumptions that production code hard-codes.
 *
 * <p>No Spring context, no database — a plain text scan of the Flyway migrations, in the
 * {@code test} phase, same as {@link MigrationConventionLintTest}.
 */
class SchemaAssumptionsTest {

    /** {@code ADD CONSTRAINT <name> EXCLUDE …} — the only way this schema declares one. */
    private static final Pattern EXCLUSION_CONSTRAINT = Pattern.compile(
        "(?is)\\bADD\\s+CONSTRAINT\\s+([A-Za-z0-9_]+)\\s+EXCLUDE\\b");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /**
     * Code review of skillars-deferred-90 (P1).
     *
     * <p>{@code ApiAdvice.resolveConstraintName} recovers the constraint name for a PostgreSQL
     * exclusion violation (SQLSTATE 23P01), which Hibernate's templated extractor maps to
     * {@code null}. When the driver message cannot be parsed it falls back to naming
     * {@code excl_bkg_coach_slot_overlap} outright, so that a V87 breach still surfaces as
     * {@code 409 booking.slotUnavailable} rather than an unmapped 500.
     *
     * <p>That fallback is only correct while the schema declares exactly ONE exclusion constraint.
     * Add a second and any violation of it would be reported to the user as "this time slot is no
     * longer available" — a plausible-looking, wrong 409. This test fails the moment that
     * assumption breaks, so whoever adds the constraint has to revisit the handler.
     */
    @Test
    void schemaDeclaresExactlyOneExclusionConstraint_whichApiAdviceHardcodesFor23P01() throws IOException {
        final List<String> names = exclusionConstraintNames();

        assertThat(names)
            .as("ApiAdvice.resolveConstraintName's 23P01 fallback hard-codes the single exclusion "
                + "constraint. Found %s. Adding another means that fallback can mislabel a "
                + "violation as booking.slotUnavailable — update ApiAdvice (map by SQLSTATE + "
                + "parsed name only, no blanket fallback) before adding one.", names)
            .containsExactly("excl_bkg_coach_slot_overlap");
    }

    private static List<String> exclusionConstraintNames() throws IOException {
        try (Stream<Path> files = Files.list(MigrationLint.REAL_MIGRATIONS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                        .sorted()
                        .flatMap(SchemaAssumptionsTest::namesIn)
                        .distinct()
                        .toList();
        }
    }

    private static Stream<String> namesIn(Path file) {
        final String sql;
        try {
            sql = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        final String stripped =
            LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll("");
        final Matcher m = EXCLUSION_CONSTRAINT.matcher(stripped);
        return m.results().map(r -> r.group(1));
    }
}
