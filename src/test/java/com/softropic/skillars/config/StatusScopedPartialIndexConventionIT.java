package com.softropic.skillars.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-89 AC6 (generalised) — every status-scoped partial index in the live schema must
 * use a <em>whitelist</em> predicate ({@code status = 'X'} / {@code status IN (...)} →
 * {@code = ANY (ARRAY[...])} after Postgres normalises it), never a blacklist ({@code status <> 'X'}
 * / {@code status NOT IN (...)} → {@code <> ALL (ARRAY[...])}). A whitelist excludes any
 * future-added status by default; a blacklist silently widens the set of rows an "active" index
 * covers the day a new status is introduced.
 *
 * <p><strong>Why generalised.</strong> The original AC6 target — {@code chk_spp_status} plus
 * {@code idx_session_packs_purchased_coach_expires} on {@code booking.session_packs_purchased}
 * ({@code V76} → whitelist-converted by {@code V83}) — no longer exists: {@code V89}
 * ({@code DROP TABLE booking.session_packs_purchased}, Story Deferred-15) removed the table, its
 * CHECK constraint and that index. Its replacement {@code payment.session_pack_purchases} has no
 * status column at all. Both story creation and the {@code dab5b88} audit missed {@code V88}/{@code V89}/{@code V90}.
 * Rather than guard a dropped object, this pins deferred-3 D1's actual intent across the whole schema:
 * a future migration cannot reintroduce a status-scoped blacklist unnoticed.
 *
 * <p><strong>The one intentional exception</strong> is {@code admin.disputes}'
 * {@code idx_disputes_unique_open_per_booking … WHERE status NOT IN ('RESOLVED','DISMISSED')}
 * ({@code V74}) — there "any non-terminal status = an open dispute" is the correct model, and
 * {@code V74:9-10} explicitly reserves {@code UNDER_REVIEW} as a future non-terminal status that
 * <em>should</em> fall inside it. It is allowlisted by name here; do not "fix" {@code V74}.
 *
 * <p>Reuses {@link AbstractIntegrationTest}'s context verbatim — no {@code @MockitoBean} /
 * {@code @TestPropertySource} / extra config, so the CI context count is unchanged.
 */
class StatusScopedPartialIndexConventionIT extends AbstractIntegrationTest {

    /**
     * Deliberate blacklist predicates. Adding to this set is a design decision (a status model where
     * "not terminal" is the natural predicate), not a fix — document the rationale on the migration.
     */
    private static final Set<String> BLACKLIST_ALLOWLIST = Set.of("idx_disputes_unique_open_per_booking");

    // The catalog renders a status-column reference as `status` or `(status)::text`. Match the bare
    // column AND compound names (`moderation_status`, `verification_status`, `payment_status`, …) —
    // `_` is a word char so `\bstatus\b` alone silently skips every compound-named status column,
    // contradicting this class's "every status-scoped partial index" promise (code review P6). The
    // trailing `\b` keeps `status_changed_at` / `last_status_at` out (those are timestamps, not the
    // scoped predicate).
    private static final Pattern STATUS_REF =
        Pattern.compile("\\bstatus\\b|\\w*_status\\b", Pattern.CASE_INSENSITIVE);

    // SQL `IS NOT NULL` / `IS NOT TRUE` / `IS NOT FALSE` / `IS NOT DISTINCT FROM` are perfectly valid
    // inside a whitelist predicate (`WHERE status = 'ACTIVE' AND deleted_at IS NOT NULL`). Strip them
    // before the residual-`NOT` check so they don't false-positive as a blacklist (code review P5).
    private static final Pattern IS_NOT_CLAUSE =
        Pattern.compile("IS\\s+NOT\\s+(NULL|TRUE|FALSE|DISTINCT\\s+FROM)", Pattern.CASE_INSENSITIVE);

    private List<Map<String, Object>> statusScopedPartialIndexes() {
        return jdbcTemplate.queryForList("""
            SELECT n.nspname || '.' || t.relname AS table_name,
                   c.relname                     AS index_name,
                   pg_get_expr(i.indpred, i.indrelid) AS predicate
            FROM pg_index i
            JOIN pg_class c     ON c.oid = i.indexrelid
            JOIN pg_class t     ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE i.indpred IS NOT NULL
              AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            ORDER BY 1, 2
            """).stream()
            .filter(row -> STATUS_REF.matcher((String) row.get("predicate")).find())
            .toList();
    }

    @Test
    void everyStatusScopedPartialIndexIsAWhitelist_exceptTheAllowlistedDisputesBlacklist() {
        List<Map<String, Object>> indexes = statusScopedPartialIndexes();

        // Non-vacuity: if this drops to near zero the sweep has stopped seeing the schema.
        assertThat(indexes)
            .as("expected the live schema to carry several status-scoped partial indexes")
            .hasSizeGreaterThanOrEqualTo(5);

        for (Map<String, Object> idx : indexes) {
            String indexName = (String) idx.get("index_name");
            String predicate = (String) idx.get("predicate");
            String upper = predicate.toUpperCase();
            // Remove the benign `IS NOT …` clauses first (code review P5), then look for any negation.
            String negationScan = IS_NOT_CLAUSE.matcher(upper).replaceAll(" ");
            boolean isBlacklist = negationScan.contains("<>")
                || negationScan.contains("!=")
                || negationScan.contains("NOT IN")
                || negationScan.contains(" ALL (")   // `<> ALL (ARRAY[...])` — the normalised NOT IN
                || negationScan.matches(".*\\bNOT\\b.*");

            if (BLACKLIST_ALLOWLIST.contains(indexName)) {
                assertThat(isBlacklist)
                    .as("allowlisted index %s (%s) is expected to be the intentional blacklist — if "
                        + "it is now a whitelist, remove it from BLACKLIST_ALLOWLIST", indexName, predicate)
                    .isTrue();
                continue;
            }

            assertThat(isBlacklist)
                .as("status-scoped partial index %s on %s uses a BLACKLIST predicate `%s` — a future "
                    + "status would silently fall inside it. Rewrite as a whitelist (status = 'X' / "
                    + "status IN (...)), or, if the blacklist is intentional, add %s to "
                    + "BLACKLIST_ALLOWLIST with a rationale on the migration.",
                    indexName, idx.get("table_name"), predicate, indexName)
                .isFalse();
            // Positive check — the predicate is a genuine equality/whitelist, not something the
            // blacklist markers just happened to miss.
            assertThat(upper)
                .as("whitelist predicate on %s must contain an equality: %s", indexName, predicate)
                .contains("=");
        }
    }

    @Test
    void theAllowlistedDisputesBlacklistStillExists() {
        // Guards against a rename quietly making the allowlist entry dead (and the exception
        // un-audited).
        assertThat(statusScopedPartialIndexes())
            .as("BLACKLIST_ALLOWLIST names idx_disputes_unique_open_per_booking — it must still be a "
                + "status-scoped partial index, or the allowlist entry is stale")
            .anySatisfy(idx -> assertThat(idx.get("index_name")).isEqualTo("idx_disputes_unique_open_per_booking"));
    }
}
