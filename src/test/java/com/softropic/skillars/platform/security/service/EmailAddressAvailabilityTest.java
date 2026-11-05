package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.platform.security.repo.EmailChangeRequest;
import com.softropic.skillars.platform.security.repo.EmailChangeRequestRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** skillars-deferred-172 AC3/AC7: the shared "is this address taken?" answer. */
@ExtendWith(MockitoExtension.class)
class EmailAddressAvailabilityTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailChangeRequestRepository requestRepository;

    @InjectMocks
    private EmailAddressAvailability availability;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(7L);
        owner.setEmail("anna@x.com");
        lenient().when(userRepository.findOneByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.findOneByLogin(anyString())).thenReturn(Optional.empty());
        lenient().when(requestRepository.findReservations(anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of());
    }

    @Test
    void anAccountsEmail_inAnyCasing_isTakenAndNoticeGoesToTheAccount() {
        when(userRepository.findOneByEmail("anna@x.com")).thenReturn(Optional.of(owner));

        Optional<EmailAddressAvailability.Holder> holder = availability.holderOf(" Anna@X.COM ", null, NOW);

        assertThat(holder).hasValueSatisfying(h -> {
            assertThat(h.user()).isSameAs(owner);
            assertThat(h.notifyAddress()).isEqualTo("anna@x.com");
            assertThat(h.reserved()).isFalse();
        });
    }

    @Test
    void anAccountsLogin_isTaken() {
        when(userRepository.findOneByLogin("anna@x.com")).thenReturn(Optional.of(owner));

        assertThat(availability.holderOf("anna@x.com", null, NOW)).isPresent();
    }

    @Test
    void theRequestersOwnAddress_isNotTakenForThem() {
        when(userRepository.findOneByEmail("anna@x.com")).thenReturn(Optional.of(owner));

        assertThat(availability.holderOf("anna@x.com", 7L, NOW)).isEmpty();
    }

    @Test
    void aReservedOldAddress_isTaken_andTheNoticeGoesToTheReservedAddressNotTheAccountsCurrentOne() {
        owner.setEmail("new-maybe-attacker@y.com");
        EmailChangeRequest reservation = new EmailChangeRequest();
        reservation.setUserId(7L);
        reservation.setOldEmail("anna@x.com");
        when(requestRepository.findReservations("anna@x.com", NOW)).thenReturn(List.of(reservation));
        when(userRepository.findOneById(7L)).thenReturn(Optional.of(owner));

        Optional<EmailAddressAvailability.Holder> holder = availability.holderOf("ANNA@x.com", 99L, NOW);

        assertThat(holder).hasValueSatisfying(h -> {
            assertThat(h.user()).isSameAs(owner);
            assertThat(h.notifyAddress()).isEqualTo("anna@x.com");
            assertThat(h.reserved()).isTrue();
        });
    }

    @Test
    void theRequestersOwnReservation_isNotTakenForThem() {
        EmailChangeRequest reservation = new EmailChangeRequest();
        reservation.setUserId(7L);
        when(requestRepository.findReservations("anna@x.com", NOW)).thenReturn(List.of(reservation));

        assertThat(availability.holderOf("anna@x.com", 7L, NOW)).isEmpty();
    }

    @Test
    void aFreeAddress_isNotTaken() {
        assertThat(availability.holderOf("free@x.com", null, NOW)).isEmpty();
        assertThat(availability.holderOf(" ", null, NOW)).isEmpty();
    }
}
