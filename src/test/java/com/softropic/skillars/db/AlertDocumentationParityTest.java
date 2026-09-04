package com.softropic.skillars.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
@DisplayName("Every shipped alert rule must be documented, and vice versa")
class AlertDocumentationParityTest {

    private static final Path PROM_RULES = Path.of("deploy", "lgtm", "alerts.yml");
    private static final Path GRAFANA_RULES = Path.of("deploy", "lgtm", "grafana-alerts.yml");
    private static final Path RUNBOOK = Path.of("docs", "deployment", "monitoring.md");

    /** Prometheus rule: {@code - alert: NameOfAlert}. */
    private static final Pattern PROM_ALERT = Pattern.compile("(?m)^\\s*-\\s*alert:\\s*(\\S+)\\s*$");
    /**
     * Grafana provisioned rule: {@code title: NameOfAlert}. Contact-point templates in the same file
     * also use {@code title:}, but theirs are quoted Go templates ({@code "{{ len .Alerts... }}"}),
     * so requiring a bare identifier separates them without needing a YAML parser.
     */
    private static final Pattern GRAFANA_ALERT = Pattern.compile("(?m)^\\s*title:\\s*([A-Za-z][A-Za-z0-9_]*)\\s*$");
    /** Runbook heading: {@code #### NameOfAlert}. */
    private static final Pattern DOCUMENTED = Pattern.compile("(?m)^#{2,4}\\s+([A-Za-z][A-Za-z0-9_]*)\\s*$");

    private static Set<String> matches(Path file, Pattern pattern) throws IOException {
        assertThat(file).as("%s must exist — this guard cannot check what it cannot read", file).exists();
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> shippedAlerts() throws IOException {
        Set<String> all = new TreeSet<>(matches(PROM_RULES, PROM_ALERT));
        all.addAll(matches(GRAFANA_RULES, GRAFANA_ALERT));
        return all;
    }

    @Test
    @DisplayName("the guard sees a plausible number of rules, so it cannot pass by finding nothing")
    void theRuleFilesAreActuallyParsed() throws IOException {
        assertThat(shippedAlerts())
            .as("""
                Zero (or implausibly few) alert names parsed out of deploy/lgtm/. Either the rule files \
                moved, or their format changed and the patterns in this class no longer match — in \
                which case every assertion below would pass while checking nothing.""")
            .hasSizeGreaterThanOrEqualTo(9);
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

        List<String> phantom = matches(RUNBOOK, DOCUMENTED).stream()
            .filter(a -> !shipped.contains(a))
            .filter(a -> !headings.contains(a))
            // Documented names are CamelCase alert identifiers; ordinary prose headings are not.
            .filter(a -> a.matches("[A-Z][a-z0-9]+([A-Z][A-Za-z0-9]*)+"))
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
