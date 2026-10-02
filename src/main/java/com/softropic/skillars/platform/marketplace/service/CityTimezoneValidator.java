package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AC2 (skillars-deferred-140): now that a coach's profile {@code canonicalTimezone} is authoritative
 * for all availability (AC1), a {@code city}/{@code canonicalTimezone} pair in plainly different
 * regions (e.g. {@code city="Paris"} with {@code canonicalTimezone="America/New_York"}) is a
 * coherence bug, not cosmetic — it would silently drive every availability window from a zone the
 * coach's stated city contradicts.
 *
 * <p>This is a <em>plausibility</em> check, not geocoding: {@code city} stays free text, and the
 * region map below is a small, hand-maintained allow-list. Deliberately fails open on anything it
 * cannot confidently classify (blank/null city, a city not in the map, a city whose name exists in
 * more than one region, or a zone whose id yields no IANA continent label) — the goal is to catch
 * obvious contradictions, not to reject every coach whose city this map happens not to know.
 *
 * <p><b>skillars-deferred-140 code review:</b> the fail-open contract above was previously asserted
 * but not honoured in three cases, each of which hard-rejected a legitimate coach and — because
 * {@code CoachProfileService.saveStep1} calls this before any mutation — locked them out of editing
 * <em>every</em> Step 1 field (bio, display name, district, languages), not just the timezone:
 * <ul>
 *   <li>D3: a city name that legitimately exists in two regions (London, Ontario; Melbourne,
 *       Florida; Perth, Scotland) was classified by its best-known region alone. Cities now map to a
 *       <em>set</em> of plausible regions and reject only when that set is unambiguous.</li>
 *   <li>A zone id with no {@code /} ({@code Japan}, {@code Iceland}, {@code Poland}, {@code GB},
 *       {@code NZ}, {@code CET}, …) yielded the whole id as its "region", which can never match a
 *       continent label. {@code @IanaTimezone} admits all 38 of these, so they reached here and
 *       always threw. They now fail open.</li>
 *   <li>{@code GMT0} was missing from the zero-offset exemption set.</li>
 * </ul>
 */
final class CityTimezoneValidator {

    /**
     * The ten IANA continent prefixes {@code CoachProfileService.getSupportedTimezones()} itself
     * allow-lists. A zone region outside this set is not something this validator can classify, so
     * it fails open rather than guessing — see {@link #regionOf}.
     */
    private static final Set<String> IANA_CONTINENT_PREFIXES = Set.of(
        "Africa", "America", "Antarctica", "Arctic", "Asia",
        "Atlantic", "Australia", "Europe", "Indian", "Pacific");

    /**
     * Every fixed zero-UTC-offset zone id in {@code ZoneId.getAvailableZoneIds()} — the no-slash
     * backward-compat links plus their {@code Etc/} equivalents and the explicit
     * {@code Etc/GMT(+|-)0}/{@code Etc/GMT0}/{@code GMT0} spellings. Enumerated by filtering
     * {@code getAvailableZoneIds()} on {@code getRules().isFixedOffset()} with a zero offset, so the
     * set is complete rather than hand-recalled; {@code GMT0} was missing from the first pass.
     *
     * <p>New coach profiles default to {@code UTC} ({@code CoachProfileService.getOrCreateDraft}) and
     * the picker offers {@code Etc/UTC} for UTC-zoned browsers, so none of these may ever be treated
     * as a mismatch against a real city.
     *
     * <p>Deliberately NOT a blanket {@code Etc/} prefix match — {@code Etc/GMT+5}/{@code Etc/GMT-10}
     * are real, non-zero fixed offsets under the {@code Etc/} namespace (POSIX sign-inverted), not
     * UTC aliases, and a city mismatch against one of those is exactly as meaningful as against any
     * other fixed offset. Note every entry here is genuinely fixed-offset and DST-free, including
     * {@code Zulu} — an earlier version of this javadoc wrongly excluded it as "DST-affected".
     */
    private static final Set<String> ZERO_OFFSET_ALIASES = Set.of(
        "UTC", "GMT", "GMT0", "Greenwich", "UCT", "Universal", "Zulu",
        "Etc/UTC", "Etc/GMT", "Etc/Greenwich", "Etc/UCT", "Etc/Universal", "Etc/Zulu",
        "Etc/GMT+0", "Etc/GMT-0", "Etc/GMT0");

    /** Readable region labels for the error message where the bare IANA prefix reads oddly. */
    private static final Map<String, String> DISPLAY_REGION_LABEL = Map.of("America", "Americas");

    private static final Map<String, Set<String>> CITY_REGIONS = buildCityRegions();

    private CityTimezoneValidator() {
    }

    static void validate(String city, String canonicalTimezone) {
        if (city == null || city.isBlank()) {
            return;
        }
        if (ZERO_OFFSET_ALIASES.contains(canonicalTimezone)) {
            return;
        }

        Set<String> cityRegions = CITY_REGIONS.get(normalize(city));
        // Unknown city, or a name whose region is genuinely ambiguous (London ON vs London UK):
        // nothing can be confidently contradicted, so allow it.
        if (cityRegions == null || cityRegions.size() > 1) {
            return;
        }

        String zoneRegion = regionOf(canonicalTimezone);
        // A zone id this validator cannot classify into an IANA continent (no-slash links like
        // `Japan`/`Iceland`/`CET`, which @IanaTimezone accepts): fail open.
        if (zoneRegion == null) {
            return;
        }

        String cityRegion = cityRegions.iterator().next();
        if (!cityRegion.equals(zoneRegion)) {
            throw new MarketplaceException("marketplace.cityTimezoneMismatch",
                "Coach's city and timezone must be in the same region. City '" + city + "' is in "
                    + displayLabel(cityRegion) + ", but timezone '" + canonicalTimezone + "' is in "
                    + displayLabel(zoneRegion) + ".");
        }
    }

    /**
     * The zone's region label, or {@code null} when the id carries none and so cannot be classified
     * ({@code null} means fail-open to the caller).
     *
     * <p>The no-slash case is the fail-open one: a bare backward-compat link ({@code Japan},
     * {@code GB}, {@code NZ}, {@code Iceland}) or a legacy POSIX-style id ({@code CET},
     * {@code EST5EDT}, {@code W-SU}) names a place or an offset, not a continent, and
     * {@code @IanaTimezone} accepts all 38 of them. Returning the whole id as its own "region" — the
     * pre-fix behaviour — could never match a continent label, so every one of these hard-rejected
     * against any known city.
     *
     * <p>A slash-bearing prefix outside {@link #IANA_CONTINENT_PREFIXES} is returned as-is rather
     * than nulled, so it still mismatches. That keeps the {@code Etc/} namespace meaningful:
     * {@code Etc/GMT+5}/{@code Etc/GMT-10} are real non-zero fixed offsets (POSIX sign-inverted), and
     * pairing one with a known city IS a genuine contradiction worth rejecting. Zero-offset
     * {@code Etc/} aliases never reach here — {@link #ZERO_OFFSET_ALIASES} exempts them first.
     */
    private static String regionOf(String zone) {
        if (zone == null) {
            return null;
        }
        int slash = zone.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return zone.substring(0, slash);
    }

    private static String displayLabel(String region) {
        return DISPLAY_REGION_LABEL.getOrDefault(region, region);
    }

    /**
     * Lowercases, strips diacritics, drops any trailing country/region qualifier after a comma, and
     * collapses internal whitespace, so the map's plain-ASCII single-spaced keys actually match what
     * coaches type. Without the diacritic and whitespace handling the validator was silently inert
     * for spellings of cities in its own map ({@code "São Paulo"} missed the key {@code "sao paulo"},
     * {@code "Zürich"}, {@code "new  york"}, {@code "Paris, France"}).
     *
     * <p>{@code Locale.ROOT} avoids the Turkish dotless-i hazard.
     */
    private static String normalize(String city) {
        String withoutQualifier = city.split(",", 2)[0];
        String decomposed = Normalizer.normalize(withoutQualifier, Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return decomposed.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Minimal, hand-maintained map (AC2.2) — can grow later without any schema change. Region labels
     * mirror {@link CoachProfileService#getSupportedTimezones()}'s own IANA continent prefixes, so a
     * zone's region is always one of these same ten names.
     *
     * <p>Values are a <em>set</em> of plausible regions (code review D3). An entry with more than one
     * region is a name this validator refuses to adjudicate: the coach could legitimately mean either
     * place, and a wrong guess locks them out of all Step 1 editing. The multi-region entries below
     * are the collisions identified in review — each is a real settlement, not a hypothetical.
     */
    private static Map<String, Set<String>> buildCityRegions() {
        Map<String, Set<String>> m = new HashMap<>();

        // --- Unambiguous: no significant same-name settlement in another region. ---
        // Europe
        m.put("paris", Set.of("Europe"));
        m.put("berlin", Set.of("Europe"));
        m.put("madrid", Set.of("Europe"));
        m.put("amsterdam", Set.of("Europe"));
        m.put("warsaw", Set.of("Europe"));
        m.put("lisbon", Set.of("Europe"));
        m.put("vienna", Set.of("Europe"));
        // Americas
        m.put("toronto", Set.of("America"));
        m.put("new york", Set.of("America"));
        m.put("los angeles", Set.of("America"));
        m.put("chicago", Set.of("America"));
        m.put("vancouver", Set.of("America"));
        m.put("mexico city", Set.of("America"));
        m.put("sao paulo", Set.of("America"));
        m.put("buenos aires", Set.of("America"));
        m.put("bogota", Set.of("America"));
        // Asia
        m.put("tokyo", Set.of("Asia"));
        m.put("beijing", Set.of("Asia"));
        m.put("shanghai", Set.of("Asia"));
        m.put("mumbai", Set.of("Asia"));
        m.put("dubai", Set.of("Asia"));
        m.put("singapore", Set.of("Asia"));
        m.put("seoul", Set.of("Asia"));
        m.put("hong kong", Set.of("Asia"));
        // Australia
        m.put("brisbane", Set.of("Australia"));
        // Africa
        m.put("johannesburg", Set.of("Africa"));
        m.put("nairobi", Set.of("Africa"));
        m.put("casablanca", Set.of("Africa"));
        // Pacific / Atlantic / Indian
        m.put("honolulu", Set.of("Pacific"));
        m.put("auckland", Set.of("Pacific"));
        m.put("reykjavik", Set.of("Atlantic"));
        m.put("male", Set.of("Indian"));

        m.put("cairo", Set.of("Africa"));
        m.put("delhi", Set.of("Asia"));

        // --- Ambiguous: a large same-name settlement in a DIFFERENT IANA region, so never rejected.
        //
        // Threshold: roughly 25k+ inhabitants. Below that the namesake is too small to plausibly host
        // a coach, and exempting it would cost real coverage — notably `paris`, whose Texas namesake
        // (~24k) sits just under the line and whose rejection against America/New_York is AC2.2's own
        // flagship prescribed test case. Smaller namesakes (Paris TX, Lagos PT ~22k, Amsterdam NY
        // ~18k, Vienna VA ~16k, Warsaw IN ~16k, Berlin NH, Madrid IA, Lisbon ND, Cairo IL, Delhi ON)
        // are the residual accepted risk recorded in the code-review decision: such a coach is still
        // rejected and must adjust their city text. Raise one to ambiguous if that ever shows up in
        // support.
        m.put("london", Set.of("Europe", "America"));        // London, Ontario (~420k)
        m.put("melbourne", Set.of("Australia", "America"));  // Melbourne, Florida (~84k)
        m.put("perth", Set.of("Australia", "Europe"));       // Perth, Scotland (~47k)
        m.put("rome", Set.of("Europe", "America"));          // Rome, Georgia (~37k)
        m.put("sydney", Set.of("Australia", "America"));     // Sydney, Nova Scotia (~30k)

        return m;
    }
}
