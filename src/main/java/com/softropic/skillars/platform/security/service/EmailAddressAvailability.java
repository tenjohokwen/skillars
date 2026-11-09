package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.EmailChangeRequest;
import com.softropic.skillars.platform.security.repo.EmailChangeRequestRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The one answer to "can this email address be put on an account?" (skillars-deferred-172), used by
 * registration ({@link RegistrationDuplicateGuard}, {@code AccountManagementFacade.registerAccount}) and by the
 * email change (request and confirm). A new uniqueness check on an email must come through here: an address
 * is taken not only when an account has it as email or login, but also while it is <b>reserved</b> as the old
 * address of a confirmed change whose undo window is still open, so the owner can still take it back.
 *
 * <p>Case-insensitive: V231 stores both columns lowercased and makes their {@code lower()} values unique, so
 * the exact lookups on the lowercased address find at most one account each.
 */
@Service
@RequiredArgsConstructor
public class EmailAddressAvailability {

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository emailChangeRequestRepository;

    /**
     * Who holds an address. {@code notifyAddress} is where a notice about it must go: the account's current
     * email for an account match, but the <b>reserved address itself</b> for a reservation (the account's
     * current email is then the new one, which may be the attacker's).
     */
    public record Holder(User user, String notifyAddress, boolean reserved) {
    }

    /**
     * @param address the typed address, any casing
     * @param requesterId the account asking, whose own addresses and reservations never count as taken for it;
     *     {@code null} at registration
     */
    public Optional<Holder> holderOf(final String address, final Long requesterId, final Instant now) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        final String lower = address.trim().toLowerCase(Locale.ROOT);
        final Optional<Holder> account = userRepository.findOneByEmail(lower)
            .or(() -> userRepository.findOneByLogin(lower))
            .filter(u -> !Objects.equals(u.getId(), requesterId))
            .map(u -> new Holder(u, u.getEmail(), false));
        if (account.isPresent()) {
            return account;
        }
        return emailChangeRequestRepository.findReservations(lower, now).stream()
            .map(EmailChangeRequest::getUserId)
            .filter(id -> !Objects.equals(id, requesterId))
            .findFirst()
            .flatMap(userRepository::findOneById)
            .map(u -> new Holder(u, lower, true));
    }
}
