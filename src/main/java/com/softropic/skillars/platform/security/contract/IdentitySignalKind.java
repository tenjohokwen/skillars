package com.softropic.skillars.platform.security.contract;

/**
 * feat/security-s06-related-party: the identifier kinds {@code UserIdentitySignalService} records per user. Stored as a
 * string in {@code main.user_identity_signals.kind} (varchar(16), no CHECK) — keep names at most 16 characters.
 *
 * <p>{@code EMAIL} / {@code PHONE}: the account's own contact details, normalised ({@code IdentitySignalNormalizer}).
 * {@code CARD}: the Stripe {@code card.fingerprint} of a card the user saved or paid with. {@code BROWSER}: the
 * client-side fingerprint cookie ({@code RequestMetadata.getFingerprintCookie}) seen on the user's requests.
 */
public enum IdentitySignalKind {
    EMAIL, PHONE, CARD, BROWSER
}
