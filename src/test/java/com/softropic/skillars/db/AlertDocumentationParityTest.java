package com.softropic.skillars.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-92 AC27.4 — the alert rules that ship and the alerts that are documented must be
 * the same set.
 *
 * <h2>Why this exists</h2>
 *
 * {@code docs/lgtm-observability.md} documented nine alerts — {@code CallbackRateZero},
 * {@code OrangeCircuitBreakerOpen}, {@code MtnCircuitBreakerOpen}, {@code ReconciliationDiscrepancy}
 * and friends — <strong>none of which exist</strong>. They were inherited from the {@code javatemplate}
 * origin project, a Mobile Money payment orchestrator. The nine alerts this repository actually
 * defines appeared nowhere in it. An operator following that document during an incident would hunt
 * for alerts that cannot fire and miss every one that can.
 *
 * <p>That drift survived an entire project pivot and six prior full-file documentation audits. A
 * grep-level check in the build is what stops it recurring, and it is cheap: two files, no Spring
 * context, no container.
 *
 * <h2>What it checks, and what it deliberately does not</h2>
 *
 * Both directions of set equality between the rule files and the response-action documentation:
 * a rule with no runbook is an alert that will page someone who then has nowhere to look, and a
 * documented alert with no rule is the exact failure above.
 *
 * <p>It does <strong>not</strong> check that the documented <em>response</em> is correct, or that the
 * PromQL threshold is sensible. Those need judgement. This is a name-level guard and its message
 * says so, because this project has a recorded habit of guards believed stronger than they are.
 *
 * <p>It is also a text-level guard, not a YAML parser — {@code PROM_ALERT}/{@code GRAFANA_ALERT} are
 * regexes over the raw file, so a spelling this class's patterns do not anticipate (a quoting style, a
 * new comment placement) can in principle still slip past it undetected. {@code 2026-09-04}'s review
 * closed the specific evasions that were reproduced (a per-file floor so one file cannot go silently
 * blind behind the other's count in a union total; a phantom-name filter that no longer exempts
 * acronym-leading or single-segment names; quoted/commented Prometheus alert lines; duplicate
 * definitions within one file) — it did not make the guard a parser.
 */
@DisplayName("Every shipped alert rule must be documented, and vice versa")
class AlertDocumentationParityTest {

    private static final Path PROM_RULES = Path.of("deploy", "lgtm", "alerts.yml");
    private static final Path GRAFANA_RULES = Path.of("deploy", "lgtm", "grafana-alerts.yml");
    private static final Path RUNBOOK = Path.of("docs", "deployment", "monitoring.md");

    /**
     * Prometheus rule: {@code - alert: NameOfAlert}, optionally quoted and/or followed by a trailing
     * {@code # comment}. {@code (?:"([^"\n]+)"|(\S+))} tries the quoted form first so a name containing
     * no quote characters still falls through to the bare-token alternative unquoted.
     */
    private static final Pattern PROM_ALERT =
        Pattern.compile("(?m)^\\s*-\\s*alert:\\s*(?:\"([^\"\\n]+)\"|(\\S+?))\\s*(?:#.*)?$");
    /**
     * Grafana provisioned rule: {@code title: NameOfAlert}. Contact-point templates in the same file
     * also use {@code title:}, but theirs are quoted Go templates ({@code "{{ len .Alerts... }}"}),
     * so requiring a bare identifier separates them without needing a YAML parser.
     */
    private static final Pattern GRAFANA_ALERT = Pattern.compile("(?m)^\\s*title:\\s*([A-Za-z][A-Za-z0-9_]*)\\s*$");
    /** Runbook heading: {@code #### NameOfAlert}. */
    private static final Pattern DOCUMENTED = Pattern.compile("(?m)^#{2,4}\\s+([A-Za-z][A-Za-z0-9_]*)\\s*$");
    /** Below this, the guard has stopped finding real rules rather than watching a deliberate retirement. */
    private static final int PROM_SANITY_FLOOR = 5;
    private static final int GRAFANA_SANITY_FLOOR = 3;

    private static String group(Matcher m) {
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private static List<String> allMatches(Path file, Pattern pattern) throws IOException {
        assertThat(file).as("%s must exist — this guard cannot check what it cannot read", file).exists();
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(pattern == PROM_ALERT ? group(m) : m.group(1));
        }
        return out;
    }

    private static Set<String> matches(Path file, Pattern pattern) throws IOException {
        return new TreeSet<>(allMatches(file, pattern));
    }

    private static Set<String> shippedAlerts() throws IOException {
        Set<String> all = new TreeSet<>(matches(PROM_RULES, PROM_ALERT));
        all.addAll(matches(GRAFANA_RULES, GRAFANA_ALERT));
        return all;
    }

    @Test
    @DisplayName("the guard sees a plausible number of rules in EACH file, so neither can go blind while the union stays green")
    void theRuleFilesAreActuallyParsed() throws IOException {
        assertThat(matches(PROM_RULES, PROM_ALERT))
            .as("""
                Implausibly few alert names parsed out of %s. Either the file moved, or its format \
                changed and PROM_ALERT no longer matches — in which case every assertion below would \
                pass while checking nothing. If this is a deliberate mass-retirement, lower \
                PROM_SANITY_FLOOR rather than raise it back to today's count.""", PROM_RULES)
            .hasSizeGreaterThanOrEqualTo(PROM_SANITY_FLOOR);
        assertThat(matches(GRAFANA_RULES, GRAFANA_ALERT))
            .as("""
                Implausibly few alert names parsed out of %s. A per-file floor exists specifically so \
                this file going fully blind (e.g. every 'title:' value becoming a quoted string, which \
                GRAFANA_ALERT deliberately does not match) cannot hide behind the Prometheus file's \
                count in a union total.""", GRAFANA_RULES)
            .hasSizeGreaterThanOrEqualTo(GRAFANA_SANITY_FLOOR);
    }

    @Test
    @DisplayName("no alert name is defined twice in the same rule file")
    void noDuplicateAlertDefinitions() throws IOException {
        assertNoDuplicates(PROM_RULES, PROM_ALERT);
        assertNoDuplicates(GRAFANA_RULES, GRAFANA_ALERT);
    }

    private static void assertNoDuplicates(Path file, Pattern pattern) throws IOException {
        List<String> all = allMatches(file, pattern);
        Set<String> seen = new HashSet<>();
        List<String> duplicated = all.stream().filter(name -> !seen.add(name)).distinct().toList();
        assertThat(duplicated)
            .as("""
                %s defines these alert names more than once. Every set-based check in this class \
                silently collapses duplicates via a Set, so two contradictory rule bodies can ship \
                under one name undetected unless this test catches it.""", file)
            .isEmpty();
    }

    @Test
    @DisplayName("every shipped alert has a documented response action")
    void everyShippedAlertIsDocumented() throws IOException {
        Set<String> documented = matches(RUNBOOK, DOCUMENTED);
        List<String> undocumented = shippedAlerts().stream()
            .filter(a -> !documented.contains(a))
            .toList();

        assertThat(undocumented)
            .as("""
                These alerts can fire but have no entry in docs/deployment/monitoring.md, so whoever \
                is paged has nowhere to look. Add a '#### <AlertName>' section with its meaning and \
                response.""")
            .isEmpty();
    }

    @Test
    @DisplayName("every documented alert still exists as a rule")
    void everyDocumentedAlertStillShips() throws IOException {
        Set<String> shipped = shippedAlerts();
        // Section headings that are structure, not alert names.
        Set<String> headings = Set.of("Critical", "High", "Warning", "Alerts", "Alert", "Overview",
            "Dashboards", "Contents", "Notes", "Monitoring", "Prerequisites", "Troubleshooting");

        // No CamelCase shape filter here on purpose: it used to require at least two capitalised
        // segments with a lowercase run after each capital, which silently exempted acronym-leading
        // names (JVMProbeAlert) and single-segment names (Heartbeat) from ever being flagged — the
        // exact drift class this test exists to catch. DOCUMENTED only ever captures a single
        // whitespace-free token, so the `headings` allowlist above is now the sole, load-bearing way
        // to tell a structural heading from a phantom alert name; keep it in sync with monitoring.md's
        // single-word `##`/`###`/`####` headings.
        List<String> phantom = matches(RUNBOOK, DOCUMENTED).stream()
            .filter(a -> !shipped.contains(a))
            .filter(a -> !headings.contains(a))
            .toList();

        assertThat(phantom)
            .as("""
                docs/deployment/monitoring.md documents these alerts, but no rule in deploy/lgtm/ \
                defines them. This is the exact failure that left docs/lgtm-observability.md \
                documenting nine Orange/MTN mobile-money alerts for a Stripe platform. Delete the \
                section, or add the rule.""")
            .isEmpty();
    }
}
