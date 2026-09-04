package com.softropic.skillars.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-deferred-90 AC12 (F23), extended by skillars-deferred-92 AC12 to cover the
 * <strong>default</strong> bundle: the key-set check alone proves nothing about translation
 * fidelity. This adds a <strong>placeholder-integrity</strong> gate: for every key,
 * {@code messages_de.properties} and {@code messages_fr.properties} must carry
 * <em>exactly</em> the same {@code {name}} / {@code {0}} placeholder token multiset and the same
 * {@code |} (vue-i18n pluralization) count as the {@code messages_en.properties} source — and the
 * key sets must match exactly (no missing keys, no foreign keys).
 *
 * <p>{@code *Test}, no Spring context — runs in the {@code test} phase.
 */
@DisplayName("Backend message bundles keep placeholder parity with messages_en")
class MessageBundleParityTest {

    private static final Path I18N = Path.of("src", "main", "resources", "i18n");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    @Test
    void germanBundle_matchesEnglishKeysAndPlaceholders() throws IOException {
        assertParity("messages_de.properties");
    }

    @Test
    void frenchBundle_matchesEnglishKeysAndPlaceholders() throws IOException {
        assertParity("messages_fr.properties");
    }

    /**
     * skillars-deferred-92 AC12 — the case whose absence was a <strong>live 500</strong>, not hygiene.
     *
     * <p>{@code messages.properties} carries no locale suffix, so it is the bundle
     * {@code ReloadableResourceBundleMessageSource} falls back to when a key is missing from the
     * resolved locale's bundle. It held <strong>86</strong> keys against {@code messages_en}'s 130.
     * The 46 absentees included {@code security.accountLocked} and <em>every</em> {@code email.*}
     * template key, so any client resolving to a locale other than {@code de}, {@code fr} or
     * {@code en} — {@code CookieLocaleResolver} falls through to {@code Accept-Language} when no
     * locale cookie is set — got a {@code NoSuchMessageException}: a 500 on the account-lockout
     * response, and a template failure on every transactional email.
     *
     * <p>The two tests above covered {@code messages_de} and {@code messages_fr} against
     * {@code messages_en} and nothing covered this file, which is the whole reason it drifted.
     *
     * <p>The drift ran <em>both</em> ways, which the story did not anticipate: this file also held two
     * keys {@code messages_en} did not ({@code email.platform_config_changed.title} /
     * {@code .preheader}, reading "Platform MSISDN Changed"). They belonged to
     * {@code mails/platformConfigChanged.html}, inherited from the {@code javatemplate} origin project
     * — no {@code EmailTemplate} constant, no sender, nothing in {@code src} referencing it, and no
     * MSISDN concept anywhere in a Stripe platform. Template and keys were deleted rather than
     * translated. {@code assertParity}'s existing "foreign keys" assertion is what would have caught
     * them, and now does.
     */
    @Test
    void defaultBundle_matchesEnglishKeysAndPlaceholders() throws IOException {
        assertParity("messages.properties");
    }

    private void assertParity(String translatedFile) throws IOException {
        Map<String, String> en = load(I18N.resolve("messages_en.properties"));
        Map<String, String> tr = load(I18N.resolve(translatedFile));

        List<String> missing = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        List<String> placeholderMismatch = new ArrayList<>();

        for (Map.Entry<String, String> e : en.entrySet()) {
            String translated = tr.get(e.getKey());
            if (translated == null) {
                missing.add(e.getKey());
                continue;
            }
            if (!placeholders(e.getValue()).equals(placeholders(translated))
                || pipeCount(e.getValue()) != pipeCount(translated)) {
                placeholderMismatch.add(e.getKey()
                    + "  EN=" + placeholders(e.getValue()) + " |x" + pipeCount(e.getValue())
                    + "  " + translatedFile + "=" + placeholders(translated) + " |x" + pipeCount(translated));
            }
        }
        for (String k : tr.keySet()) {
            if (!en.containsKey(k)) {
                foreign.add(k);
            }
        }

        assertThat(missing).as("%s is missing keys present in messages_en", translatedFile).isEmpty();
        assertThat(foreign).as("%s carries keys not in messages_en (delete them)", translatedFile).isEmpty();
        assertThat(placeholderMismatch)
            .as("%s placeholder / pluralization-pipe drift vs messages_en", translatedFile)
            .isEmpty();
    }

    /** Sorted multiset of {@code {...}} tokens in a value. */
    private static TreeMap<String, Integer> placeholders(String value) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            counts.merge(m.group(), 1, Integer::sum);
        }
        return counts;
    }

    private static long pipeCount(String value) {
        return value.chars().filter(c -> c == '|').count();
    }

    private static Map<String, String> load(Path p) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = raw.indexOf('=');
            out.put(raw.substring(0, eq).strip(), raw.substring(eq + 1));
        }
        return out;
    }
}
