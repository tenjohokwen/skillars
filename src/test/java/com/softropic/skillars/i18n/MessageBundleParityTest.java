package com.softropic.skillars.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
     * resolved locale's bundle. It held <strong>84</strong> keys against {@code messages_en}'s 130
     * (84 − 2 foreign {@code platform_config_changed} keys + 46 added = 130 — a previous version of
     * this note said 86, double-counting the two foreign keys removed below; skillars-deferred-92
     * code review, chunk 3).
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

    /**
     * skillars-deferred-92 AC13 — a value identical to the English is almost certainly untranslated.
     *
     * <p>Key parity and placeholder parity both pass happily on a bundle that simply repeats the
     * English, which is exactly what had shipped: {@code email.booking.quick_complete_confirm.title},
     * {@code .reschedule_declined_by_parent.title} and {@code .reschedule_requested_by_coach.title}
     * were English in <strong>both</strong> {@code messages_de} and {@code messages_fr}, so a French
     * or German parent received "Please confirm your session" as an email subject line. It survived
     * skillars-deferred-91 AC9's whole de-DE register pass, which looked for informal forms rather
     * than for English.
     *
     * <p>The allowlist is empty and should stay small. A genuine cognate — including a short one like
     * "Status" or "OK" — belongs in it with a reason. skillars-deferred-92 code review, chunk 3
     * removed the {@code > 15} character length floor that used to exempt short values from this
     * check outright: it let real subject/CTA strings ("Verify my email", "Password Reset") through
     * unchecked, and protected nothing that actually exists in this bundle today (no en/de or en/fr
     * pair here is identical at any length).
     */
    private static final Set<String> LEGITIMATELY_IDENTICAL_TO_ENGLISH = Set.of();

    @Test
    @DisplayName("no translated value is left as untouched English")
    void translatedBundles_containNoUntranslatedEnglish() throws IOException {
        for (String bundle : List.of("messages_de.properties", "messages_fr.properties")) {
            Map<String, String> en = load(I18N.resolve("messages_en.properties"));
            Map<String, String> tr = load(I18N.resolve(bundle));

            List<String> untranslated = en.entrySet().stream()
                // skillars-deferred-92 code review, chunk 3: a `> 15` character length floor let
                // real, live subject/CTA strings through undetected ("Verify my email" is exactly
                // 15; "Password Reset" / "Reset Password" are 14) — none happen to be untranslated
                // today (verified: no en/de or en/fr value pair in this bundle is identical at any
                // length), so the floor was pure attack surface with no upside. The allowlist below
                // is the precise instrument for a genuine cognate; a length heuristic is not.
                .filter(e -> !LEGITIMATELY_IDENTICAL_TO_ENGLISH.contains(e.getKey()))
                .filter(e -> e.getValue().strip().equalsIgnoreCase(
                    String.valueOf(tr.get(e.getKey())).strip()))
                .map(e -> e.getKey() + " == \"" + e.getValue().strip() + "\"")
                .sorted()
                .toList();

            assertThat(untranslated)
                .as("""
                    %s repeats messages_en verbatim for these keys, so a user of that locale reads \
                    English. Translate them, or add the key to LEGITIMATELY_IDENTICAL_TO_ENGLISH with \
                    a reason if the two languages really do share the string.""", bundle)
                .isEmpty();
        }
    }

    /**
     * skillars-deferred-92 code review, chunk 3: {@link #placeholders(String)} counts {@code {…}}
     * tokens as text, so two values with the same {@code {0}}/{@code {1}} multiset pass parity even
     * when one of them corrupts under real {@link MessageFormat} — a literal, un-doubled apostrophe
     * is a quote delimiter to {@code MessageFormat}, not punctuation. Reproduced before this test
     * existed: {@code messages_fr.properties}' {@code email.pw_reset.text2} had an odd (unescaped)
     * apostrophe count and silently dropped its {@code {0}} substitution entirely, and
     * {@code email.profile_change.email}'s single apostrophe survived placeholder parity while its
     * {@code n'avez} rendered as {@code navez} — the apostrophe consumed as a delimiter even though
     * the {@code {n}} tokens still matched. Every bundle value carrying a placeholder is now
     * round-tripped through the real formatter: every literal quote character in the source must
     * still be present in the formatted output, or {@code MessageFormat} was silently eating it.
     */
    @Test
    @DisplayName("a value with a placeholder does not lose literal apostrophes to MessageFormat quoting")
    void placeholderValues_surviveMessageFormatQuoting() throws IOException {
        for (String bundle : List.of("messages.properties", "messages_en.properties",
                                      "messages_de.properties", "messages_fr.properties")) {
            Map<String, String> tr = load(I18N.resolve(bundle));
            List<String> corrupted = new ArrayList<>();

            for (Map.Entry<String, String> e : tr.entrySet()) {
                String value = e.getValue();
                if (value.indexOf('\'') < 0 || !PLACEHOLDER.matcher(value).find()) {
                    continue;
                }
                // A correctly-escaped literal apostrophe is a doubled '' pair, which MessageFormat
                // collapses to one literal ' in the output. Any apostrophe NOT part of such a pair is
                // being read as a quote delimiter instead of punctuation — that is the corruption,
                // whether it swallows the {n} substitution outright (an odd, unterminated count) or
                // silently strips the delimiter characters while leaving {n} unaffected (an even count
                // that still isn't paired as '').
                long escapedPairs = (value.length() - value.replace("''", "").length()) / 2;
                long strayApostrophes = value.replace("''", "").chars().filter(c -> c == '\'').count();
                String formatted;
                try {
                    formatted = new MessageFormat(value).format(dummyArgs(value));
                } catch (IllegalArgumentException ex) {
                    corrupted.add(e.getKey() + " — MessageFormat rejects the pattern: " + ex.getMessage());
                    continue;
                }
                long survivingApostrophes = formatted.chars().filter(c -> c == '\'').count();
                if (strayApostrophes > 0 || survivingApostrophes != escapedPairs) {
                    corrupted.add(e.getKey() + " — " + strayApostrophes + " un-doubled apostrophe(s), "
                        + survivingApostrophes + " survive MessageFormat, " + escapedPairs
                        + " expected (escape every literal ' as '')");
                }
            }

            assertThat(corrupted)
                .as("%s: a value carrying a {n} placeholder must escape a literal apostrophe as ''",
                    bundle)
                .isEmpty();
        }
    }

    /** One dummy string argument per distinct numbered placeholder found in {@code value}. */
    private static Object[] dummyArgs(String value) {
        int max = -1;
        Matcher m = Pattern.compile("\\{(\\d+)[,}]").matcher(value);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        Object[] args = new Object[max + 1];
        Arrays.fill(args, "X");
        return args;
    }

    /**
     * skillars-deferred-92 code review, chunk 3: {@link #assertParity(String)} only compares key sets
     * and placeholder multisets between the default bundle and {@code messages_en} — never values —
     * so {@code messages.properties} is a second English bundle by construction with nothing keeping
     * its wording aligned to {@code messages_en} going forward. This story already had to hand-align
     * {@code email.pw_reset.text2} across both files once; this pins it rather than leaving the next
     * drift to ship unnoticed to every {@code en} client that resolves through the default bundle.
     */
    @Test
    @DisplayName("the default bundle's English values do not drift from messages_en")
    void defaultBundle_matchesEnglishBundleValues() throws IOException {
        Map<String, String> en = load(I18N.resolve("messages_en.properties"));
        Map<String, String> def = load(I18N.resolve("messages.properties"));

        List<String> drifted = en.entrySet().stream()
            .filter(e -> def.containsKey(e.getKey()))
            .filter(e -> !e.getValue().strip().equals(def.get(e.getKey()).strip()))
            .map(e -> e.getKey() + "\n    messages_en =" + e.getValue().strip()
                + "\n    messages    =" + def.get(e.getKey()).strip())
            .sorted()
            .toList();

        assertThat(drifted)
            .as("messages.properties must carry the same English wording as messages_en.properties "
                + "for every shared key — it is the fallback bundle for an 'en' client too")
            .isEmpty();
    }

    /** Sorted multiset of {@code {…}} tokens in a value. */
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

    /**
     * skillars-deferred-92 code review, chunk 3: the previous hand-rolled scanner skipped any line
     * without a literal {@code =} (so the legal {@code key: value} / {@code key value} forms were
     * invisible), treated only {@code #} as a comment (not {@code !}), and never joined a
     * {@code \}-continued line. All four are legal {@code .properties} syntax that
     * {@code ReloadableResourceBundleMessageSource} loads correctly — using the real
     * {@link Properties} parser means this parity gate sees exactly what Spring sees, closing the
     * exact class of drift it exists to stop.
     */
    private static Map<String, String> load(Path p) throws IOException {
        final Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        final Map<String, String> out = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            out.put(key, properties.getProperty(key));
        }
        return out;
    }
}
