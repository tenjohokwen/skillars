package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.platform.security.contract.IdentitySignalKind;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserIdentitySignalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserIdentitySignalServiceTest {

    @Mock UserIdentitySignalRepository repository;
    @InjectMocks UserIdentitySignalService service;

    @Test
    void recordContact_storesOnlyHashesOfNormalisedEmailAndPhone() {
        User user = new User();
        user.setId(7L);
        user.setEmail("Some.One+x@gmail.com");
        user.setPhone(new PhoneNumber("+237 677 891 234", "CM"));

        service.recordContact(user);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(eq(7L), eq("EMAIL"), hash.capture());
        assertThat(hash.getValue()).isEqualTo(IdentitySignalNormalizer.hash(IdentitySignalKind.EMAIL, "someone@gmail.com"));
        verify(repository).upsert(eq(7L), eq("PHONE"),
            eq(IdentitySignalNormalizer.hash(IdentitySignalKind.PHONE, "677891234")));
    }

    @Test
    void recordCardFingerprint_blankIsIgnored_andAFailedWriteNeverEscapes() {
        service.recordCardFingerprint(7L, "  ");
        verify(repository, never()).upsert(anyLong(), anyString(), anyString());

        when(repository.upsert(eq(7L), eq("CARD"), anyString())).thenThrow(new IllegalStateException("db"));
        service.recordCardFingerprint(7L, "fp_abc");
    }

    @Test
    void sharedKinds_mapsRowsAndShortCircuitsOnSameOrMissingUser() {
        when(repository.findSharedKinds(1L, 2L)).thenReturn(List.of("CARD", "EMAIL"));

        assertThat(service.sharedKinds(1L, 2L)).isEqualTo(Set.of(IdentitySignalKind.CARD, IdentitySignalKind.EMAIL));
        assertThat(service.sharedKinds(1L, 1L)).isEmpty();
        assertThat(service.sharedKinds(null, 2L)).isEmpty();
        verify(repository, never()).findSharedKinds(eq(1L), eq(1L));
    }

    @Test
    void eraseForUser_deletesEveryRowOfTheUser() {
        when(repository.deleteAllByUserId(9L)).thenReturn(4);
        assertThat(service.eraseForUser(9L)).isEqualTo(4);
    }
}
