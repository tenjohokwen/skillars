package com.softropic.skillars.platform.marketplace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payment depends on marketplace (coach profiles, the ports below), so marketplace must not depend on payment:
 * the two would form a cycle. Marketplace reaches payment only through ports it owns and payment implements
 * ({@code CoachSubscriptionEntitlementPort}, and since skillars-deferred-168 {@code CoachSessionPackPort}).
 * A source scan in the style of {@code InfrastructureLayeringAuditTest}; the project has no ArchUnit.
 */
class MarketplaceDoesNotDependOnPaymentTest {

    private static final Path MARKETPLACE_SRC =
        Path.of("src", "main", "java", "com", "softropic", "skillars", "platform", "marketplace");

    /** An import, or a fully-qualified reference anywhere in code (comments are stripped first). */
    private static final Pattern PAYMENT_REFERENCE =
        Pattern.compile("\\bcom\\.softropic\\.skillars\\.platform\\.payment\\.");

    @Test
    void noMarketplaceClassReferencesPayment() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MARKETPLACE_SRC)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(MarketplaceDoesNotDependOnPaymentTest::referencesPayment)
                .map(p -> MARKETPLACE_SRC.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        }

        assertThat(offenders)
            .as("marketplace classes referencing com.softropic.skillars.platform.payment.* — declare a port in "
                + "marketplace.contract and implement it in payment.adapter instead")
            .isEmpty();
    }

    private static boolean referencesPayment(Path file) {
        try {
            String code = Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\\n]*", " ");
            return PAYMENT_REFERENCE.matcher(code).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
