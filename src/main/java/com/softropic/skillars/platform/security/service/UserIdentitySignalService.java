package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.IdentitySignalKind;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserIdentitySignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * feat/security-s06-related-party: the per-user store of hashed identifiers ({@code main.user_identity_signals})
 * that {@code RelatedPartyService} compares between a booking's payer and the booked coach.
 *
 * <p>Every {@code record*} method is best-effort: it logs and returns on failure, because a signal that could not be
 * written must never fail a login, a card save or a charge. A missed signal only means a possible collusion is not
 * caught on that signal. Nothing here logs a raw or hashed value.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentitySignalService {

    private final UserIdentitySignalRepository repository;

    /** The user's own normalised email and phone. */
    @Transactional
    public void recordContact(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        record(user.getId(), IdentitySignalKind.EMAIL, IdentitySignalNormalizer.email(user.getEmail()));
        record(user.getId(), IdentitySignalKind.PHONE,
            IdentitySignalNormalizer.phone(user.getPhone() == null ? null : user.getPhone().getPhone()));
    }

    /** The client-side fingerprint cookie of the current request, for the user it authenticated as. */
    @Transactional
    public void recordCurrentBrowser(Long userId) {
        if (userId == null) {
            return;
        }
        String fingerprint = RequestMetadataProvider.getClientInfo().getFingerprintCookie();
        record(userId, IdentitySignalKind.BROWSER, IdentitySignalNormalizer.opaque(IdentitySignalKind.BROWSER, fingerprint));
    }

    /** A Stripe {@code card.fingerprint} the user saved or paid with. */
    @Transactional
    public void recordCardFingerprint(Long userId, String fingerprint) {
        if (userId == null) {
            return;
        }
        record(userId, IdentitySignalKind.CARD, IdentitySignalNormalizer.opaque(IdentitySignalKind.CARD, fingerprint));
    }

    /** The kinds on which the two users share an identifier; empty when none or when either id is null / equal. */
    @Transactional(readOnly = true)
    public Set<IdentitySignalKind> sharedKinds(Long userA, Long userB) {
        Set<IdentitySignalKind> kinds = EnumSet.noneOf(IdentitySignalKind.class);
        if (userA == null || userB == null || userA.equals(userB)) {
            return kinds;
        }
        for (String kind : repository.findSharedKinds(userA, userB)) {
            kinds.add(IdentitySignalKind.valueOf(kind));
        }
        return kinds;
    }

    /** GDPR user erasure: every identifier recorded for the user (their own and, for a coach, the coach's). */
    @Transactional
    public int eraseForUser(Long userId) {
        return repository.deleteAllByUserId(userId);
    }

    private void record(Long userId, IdentitySignalKind kind, Optional<String> canonical) {
        canonical.ifPresent(value -> {
            try {
                repository.upsert(userId, kind.name(), IdentitySignalNormalizer.hash(kind, value));
            } catch (RuntimeException e) {
                log.warn("[IDENTITY_SIGNAL_RECORD_FAILED] kind={} userId={} error={}", kind, userId, e.getClass().getSimpleName());
            }
        });
    }
}
