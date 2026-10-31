package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.contract.IdentitySignalKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** feat/security-s06-related-party: the canonical forms two accounts of one person would share. */
class IdentitySignalNormalizerTest {

    @Test
    void email_caseTagAndWhitespaceFoldAway() {
        assertThat(IdentitySignalNormalizer.email("  Coach.Name+skillars@Example.COM "))
            .contains("coach.name@example.com");
    }

    @Test
    void email_gmailDotsAndGooglemailDomainFold() {
        assertThat(IdentitySignalNormalizer.email("j.o.h.n@googlemail.com")).contains("john@gmail.com");
        assertThat(IdentitySignalNormalizer.email("john+x@gmail.com")).contains("john@gmail.com");
    }

    @Test
    void email_dotsAreKeptOnOtherDomains() {
        assertThat(IdentitySignalNormalizer.email("j.ohn@example.com")).contains("j.ohn@example.com");
    }

    @Test
    void email_notAnAddress_isEmpty() {
        assertThat(IdentitySignalNormalizer.email(null)).isEmpty();
        assertThat(IdentitySignalNormalizer.email("nope")).isEmpty();
        assertThat(IdentitySignalNormalizer.email("@example.com")).isEmpty();
        assertThat(IdentitySignalNormalizer.email("a@")).isEmpty();
        assertThat(IdentitySignalNormalizer.email("+tag@example.com")).isEmpty();
    }

    @Test
    void phone_countryCodeTrunkZeroAndFormattingFold() {
        assertThat(IdentitySignalNormalizer.phone("+237 677 89 12 34")).contains("677891234");
        assertThat(IdentitySignalNormalizer.phone("0677-891-234")).contains("677891234");
        assertThat(IdentitySignalNormalizer.phone("(237) 677891234")).contains("677891234");
    }

    @Test
    void phone_tooShortOrMissing_isEmpty() {
        assertThat(IdentitySignalNormalizer.phone(null)).isEmpty();
        assertThat(IdentitySignalNormalizer.phone("12345")).isEmpty();
        assertThat(IdentitySignalNormalizer.phone("abc")).isEmpty();
    }

    @Test
    void opaque_blankIsEmpty_andImplausibleBrowserValuesAreDropped() {
        assertThat(IdentitySignalNormalizer.opaque(IdentitySignalKind.CARD, "  ")).isEmpty();
        assertThat(IdentitySignalNormalizer.opaque(IdentitySignalKind.CARD, " fp_1 ")).contains("fp_1");
        assertThat(IdentitySignalNormalizer.opaque(IdentitySignalKind.BROWSER, "short")).isEmpty();
        assertThat(IdentitySignalNormalizer.opaque(IdentitySignalKind.BROWSER, "x".repeat(600))).isEmpty();
        assertThat(IdentitySignalNormalizer.opaque(IdentitySignalKind.BROWSER, "abcdef0123456789"))
            .contains("abcdef0123456789");
    }

    @Test
    void hash_isStable64HexAndScopedByKind() {
        String a = IdentitySignalNormalizer.hash(IdentitySignalKind.EMAIL, "x@example.com");
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdentitySignalNormalizer.hash(IdentitySignalKind.EMAIL, "x@example.com")).isEqualTo(a);
        assertThat(IdentitySignalNormalizer.hash(IdentitySignalKind.CARD, "x@example.com")).isNotEqualTo(a);
        assertThat(a).doesNotContain("example");
    }
}
