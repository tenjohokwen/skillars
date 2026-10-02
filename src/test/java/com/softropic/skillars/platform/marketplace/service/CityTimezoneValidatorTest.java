package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CityTimezoneValidatorTest {

    @Test
    void validate_crossRegionPair_throwsMarketplaceException() {
        assertThatThrownBy(() -> CityTimezoneValidator.validate("Paris", "America/New_York"))
            .isInstanceOf(MarketplaceException.class)
            .satisfies(e -> {
                MarketplaceException ex = (MarketplaceException) e;
                assertThat(ex.getErrorCode()).isEqualTo("marketplace.cityTimezoneMismatch");
                assertThat(ex.getMessage())
                    .contains("Paris").contains("Europe").contains("America/New_York").contains("Americas");
            });
    }

    @Test
    void validate_sameRegionDifferentCity_doesNotThrow() {
        assertThatCode(() -> CityTimezoneValidator.validate("Paris", "Europe/London")).doesNotThrowAnyException();
    }

    @Test
    void validate_sameRegionSameCity_doesNotThrow() {
        assertThatCode(() -> CityTimezoneValidator.validate("Paris", "Europe/Paris")).doesNotThrowAnyException();
    }

    @Test
    void validate_unknownCity_failsOpen() {
        assertThatCode(() -> CityTimezoneValidator.validate("Unknown_Place", "America/New_York"))
            .doesNotThrowAnyException();
    }

    @Test
    void validate_blankCity_failsOpen() {
        assertThatCode(() -> CityTimezoneValidator.validate("   ", "America/New_York")).doesNotThrowAnyException();
    }

    @Test
    void validate_nullCity_failsOpen() {
        assertThatCode(() -> CityTimezoneValidator.validate(null, "America/New_York")).doesNotThrowAnyException();
    }

    @Test
    void validate_utcLiteral_alwaysCompatible() {
        assertThatCode(() -> CityTimezoneValidator.validate("Paris", "UTC")).doesNotThrowAnyException();
    }

    @Test
    void validate_etcUtc_alwaysCompatible() {
        assertThatCode(() -> CityTimezoneValidator.validate("Paris", "Etc/UTC")).doesNotThrowAnyException();
    }

    // mto-story-review 2026-10-02, Finding E said "UTC/Etc/UTC (and any other zero-offset alias)"
    // — not the whole Etc/ namespace. These are the other zero-offset backward-compat links
    // ZoneId.getAvailableZoneIds() carries alongside UTC/Etc/UTC; every one must be exempted the
    // same way, not just the two the story names explicitly.
    @Test
    void validate_otherZeroOffsetAliases_alwaysCompatible() {
        for (String zone : new String[]{
            "GMT", "Greenwich", "UCT", "Universal", "Zulu",
            "Etc/GMT", "Etc/Greenwich", "Etc/UCT", "Etc/Universal", "Etc/Zulu",
            "Etc/GMT+0", "Etc/GMT-0", "Etc/GMT0"}) {
            assertThatCode(() -> CityTimezoneValidator.validate("Paris", zone))
                .as("zero-offset alias '%s' must be universally compatible", zone)
                .doesNotThrowAnyException();
        }
    }

    // The narrowing counterpart to the test above: Etc/GMT+5 is a REAL, non-zero fixed offset under
    // the Etc/ namespace (POSIX sign-inverted — it is actually UTC-5), not a UTC alias, so it must
    // NOT get the zero-offset exemption. A blanket "any Etc/ prefix is compatible" rule (the
    // pre-narrowing implementation) would have let this through silently; it must now fall through
    // to ordinary region extraction like any other zone, and "Etc" matches no city's region.
    @Test
    void validate_nonZeroOffsetEtcZone_isNotTreatedAsUtcFamily() {
        assertThatThrownBy(() -> CityTimezoneValidator.validate("Paris", "Etc/GMT+5"))
            .isInstanceOf(MarketplaceException.class);
    }

    @Test
    void validate_cityIsCaseAndWhitespaceInsensitive() {
        assertThatThrownBy(() -> CityTimezoneValidator.validate("  PARIS  ", "America/New_York"))
            .isInstanceOf(MarketplaceException.class);
    }

    // ---- skillars-deferred-140 code review ----

    // GMT0 is a real, separate entry in ZoneId.getAvailableZoneIds() — the bare backward-compat link
    // that "Etc/GMT0" aliases. The first pass listed Etc/GMT0 but not GMT0, so a coach submitting it
    // was rejected for a zone that is literally UTC. Enumerated by filtering getAvailableZoneIds()
    // on isFixedOffset() with a zero offset, which yields 16 ids; the set declared 15.
    @Test
    void validate_bareGmt0_alwaysCompatible() {
        assertThatCode(() -> CityTimezoneValidator.validate("Paris", "GMT0")).doesNotThrowAnyException();
    }

    /**
     * D3: a city whose name exists in two IANA regions cannot be confidently classified, so it must
     * fail OPEN. Previously it failed CLOSED off its best-known region — and because
     * {@code CoachProfileService.saveStep1} runs this before any mutation, a coach in London, Ontario
     * (~420k) could not save their bio, display name, district or languages either, with no override
     * short of misspelling their own city.
     */
    @Test
    void validate_ambiguousCityName_failsOpenInEitherRegion() {
        assertThatCode(() -> CityTimezoneValidator.validate("London", "America/Toronto"))
            .as("London, Ontario is a real place; the Europe mapping must not veto it")
            .doesNotThrowAnyException();
        assertThatCode(() -> CityTimezoneValidator.validate("London", "Europe/London"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CityTimezoneValidator.validate("Melbourne", "America/New_York"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CityTimezoneValidator.validate("Perth", "Europe/London"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CityTimezoneValidator.validate("Sydney", "America/Halifax"))
            .doesNotThrowAnyException();
        assertThatCode(() -> CityTimezoneValidator.validate("Rome", "America/New_York"))
            .doesNotThrowAnyException();
    }

    // The counterweight to the test above: broadening the ambiguity list must not quietly disable
    // AC2.2's own flagship prescribed case. Paris stays unambiguous (its Texas namesake is ~24k,
    // below the ~25k threshold documented on the map).
    @Test
    void validate_unambiguousCityStillRejectsCrossRegion() {
        assertThatThrownBy(() -> CityTimezoneValidator.validate("Paris", "America/New_York"))
            .isInstanceOf(MarketplaceException.class);
        assertThatThrownBy(() -> CityTimezoneValidator.validate("Tokyo", "Europe/Paris"))
            .isInstanceOf(MarketplaceException.class);
    }

    /**
     * A zone id with no {@code /} names a place or a POSIX offset, not a continent, so it cannot be
     * classified and must fail open. {@code @IanaTimezone} admits all 38 of these (it gates only on
     * {@code ZoneId.getAvailableZoneIds().contains(value)}), and the first pass returned the whole id
     * as its own "region", which could never match a continent label — so every one of them
     * hard-rejected against any known city.
     */
    @Test
    void validate_noSlashZoneIds_failOpen() {
        for (String zone : new String[]{
            "Japan", "Iceland", "Poland", "Portugal", "GB", "GB-Eire", "NZ", "Singapore", "Hongkong",
            "Egypt", "Turkey", "Israel", "Iran", "PRC", "ROK", "Cuba", "Jamaica", "Libya", "Eire",
            "Navajo", "W-SU", "CET", "EET", "WET", "MET", "EST5EDT", "CST6CDT", "MST7MDT", "PST8PDT"}) {
            assertThatCode(() -> CityTimezoneValidator.validate("Tokyo", zone))
                .as("unclassifiable zone id '%s' must fail open, not reject", zone)
                .doesNotThrowAnyException();
        }
    }

    // normalize() handled only case and trim, so the validator was silently inert for accented and
    // qualified spellings of cities in its own map — including "sao paulo", the one Brazilian entry.
    @Test
    void validate_normalizesAccentsInternalWhitespaceAndCountryQualifier() {
        assertThatThrownBy(() -> CityTimezoneValidator.validate("São Paulo", "Europe/Paris"))
            .as("diacritics must be stripped so the map's 'sao paulo' key matches")
            .isInstanceOf(MarketplaceException.class);
        assertThatThrownBy(() -> CityTimezoneValidator.validate("new  york", "Europe/Paris"))
            .as("internal whitespace must collapse to match 'new york'")
            .isInstanceOf(MarketplaceException.class);
        assertThatThrownBy(() -> CityTimezoneValidator.validate("Paris, France", "America/New_York"))
            .as("a trailing country qualifier must not defeat the lookup")
            .isInstanceOf(MarketplaceException.class);
    }
}
